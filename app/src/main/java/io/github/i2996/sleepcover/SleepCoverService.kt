package io.github.i2996.sleepcover

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * 고른 독서 앱이 화면에 있을 때 책 제목을 읽고, 그 책 표지를 슬립 이미지로 저장한다.
 *
 * 챕터 제목을 책 제목으로 오인하지 않도록:
 * - 검색 결과 제목이 감지된 제목과 사실상 같을 때만 적용하고,
 * - 한 번 적용하면 그 책 화면을 떠날 때(서재·다른 앱으로 이동)까지 다시 바꾸지 않는다.
 */
class SleepCoverService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var scanScheduled = false
    private var currentPkg: String? = null
    private var currentActivity: String? = null
    private var pendingTitle: String? = null

    // 표지를 정한 뒤 고정된 화면 (앱 패키지 + 화면 이름)
    private var lockedPkg: String? = null
    private var lockedActivity: String? = null

    @Volatile
    private var working = false

    @Volatile
    private var failedTitle: String? = null

    @Volatile
    private var failedAt = 0L

    private val scan = Runnable {
        scanScheduled = false
        scanNow()
    }
    private val applyPending = Runnable { pendingTitle?.let { applyTitle(it) } }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        val isReader = pkg in Prefs.readers(this)

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val cls = event.className?.toString()
            if (!isReader) {
                if (!isOverlay(pkg)) unlock()
                return
            }
            if (cls != null && cls.endsWith("Activity")) {
                // 같은 앱 안에서도 다른 화면(서재 등)으로 가면 고정을 푼다.
                if (lockedPkg == pkg && lockedActivity != null && cls != lockedActivity) unlock()
                currentActivity = cls
            }
        }
        if (!isReader) return
        maybeCheckUpdate()
        if (currentPkg != pkg) currentActivity = null
        currentPkg = pkg
        if (!scanScheduled) {
            scanScheduled = true
            main.postDelayed(scan, 800)
        }
    }

    /** 상단바·키보드·알림처럼 독서 화면 위에 잠깐 뜨는 것들은 화면 이동으로 보지 않는다. */
    private fun isOverlay(pkg: String) =
        pkg == packageName || pkg == "android" ||
            listOf("systemui", "inputmethod", "keyboard", "honeyboard").any { it in pkg }

    /** 책을 읽는 중 하루에 한 번 새 버전을 확인하고, 있으면 알려 준다. */
    private fun maybeCheckUpdate() {
        val now = System.currentTimeMillis()
        if (now - Prefs.lastUpdateCheck(this) < 24 * 60 * 60 * 1000L) return
        Prefs.setLastUpdateCheck(this, now)
        val ctx = applicationContext
        Thread {
            val v = try {
                Updater.newerVersion(ctx)
            } catch (e: Exception) {
                // 와이파이 없을 때 등: 1시간 뒤 다시 확인
                Prefs.setLastUpdateCheck(ctx, now - 23 * 60 * 60 * 1000L)
                null
            }
            if (v != null) {
                main.post {
                    Toast.makeText(ctx, "슬립 표지 새 버전 #$v 이 있어요. 앱을 열면 업데이트할 수 있어요.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun unlock() {
        lockedPkg = null
        lockedActivity = null
    }

    private fun scanNow() {
        val pkg = currentPkg ?: return
        val root = try {
            rootInActiveWindow
        } catch (e: Exception) {
            null
        } ?: return
        if (root.packageName?.toString() != pkg) return
        val title = TitleFinder.find(root, resources.displayMetrics.heightPixels) ?: return
        Prefs.setDetected(this, title, pkg)
        if (!Prefs.auto(this)) return
        if (lockedPkg == pkg) return
        if (title == Prefs.attempted(this) || title == pendingTitle) return
        // 인터넷이 없어서 실패한 책은 1분마다만 다시 시도한다.
        if (title == failedTitle && SystemClock.uptimeMillis() - failedAt < 60_000) return
        pendingTitle = title
        main.removeCallbacks(applyPending)
        main.postDelayed(applyPending, 4000)
    }

    private fun lockHere() {
        lockedPkg = currentPkg
        lockedActivity = currentActivity
    }

    private fun applyTitle(title: String) {
        pendingTitle = null
        if (working) return
        working = true
        Prefs.setAttempted(this, title)
        val ctx = applicationContext
        Thread {
            var applied = false
            val msg = try {
                if (CoverWriter.applyCached(ctx, title) != null) {
                    applied = true
                    "슬립 표지: $title"
                } else {
                    val book = CoverSearch.search(title, Prefs.ttbKey(ctx))
                        .firstOrNull { it.exact && TitleMatch.matches(title, it.title) }
                    if (book == null) {
                        // 챕터 제목 등 책 제목이 아닌 글자일 가능성이 높으니 조용히 넘어간다.
                        null
                    } else {
                        CoverWriter.apply(ctx, book.coverUrl, title)
                        applied = true
                        "슬립 표지: $title"
                    }
                }
            } catch (e: Exception) {
                // 와이파이가 없을 때 등: 나중에 다시 시도하도록 기록을 지운다.
                Prefs.setAttempted(ctx, "")
                val firstFailure = failedTitle != title
                failedTitle = title
                failedAt = SystemClock.uptimeMillis()
                // 같은 책에서 1분마다 실패 알림이 뜨지 않게 처음 한 번만 알린다.
                if (firstFailure) "슬립 표지 실패 (와이파이 연결되면 다시 시도해요): ${e.message}" else null
            } finally {
                working = false
            }
            main.post {
                if (applied) lockHere()
                if (msg != null) Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
