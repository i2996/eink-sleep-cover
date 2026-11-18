package io.github.i2996.sleepcover

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * 고른 독서 앱이 화면에 있을 때 책 제목을 읽고,
 * 제목이 바뀌면 (4초 동안 그대로면) 그 책 표지를 슬립 이미지로 저장한다.
 */
class SleepCoverService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var scanScheduled = false
    private var currentPkg: String? = null
    private var pendingTitle: String? = null

    @Volatile
    private var working = false

    private val scan = Runnable {
        scanScheduled = false
        scanNow()
    }
    private val applyPending = Runnable { pendingTitle?.let { applyTitle(it) } }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg !in Prefs.readers(this)) return
        currentPkg = pkg
        if (!scanScheduled) {
            scanScheduled = true
            main.postDelayed(scan, 800)
        }
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
        if (title == Prefs.attempted(this) || title == pendingTitle) return
        pendingTitle = title
        main.removeCallbacks(applyPending)
        main.postDelayed(applyPending, 4000)
    }

    private fun applyTitle(title: String) {
        pendingTitle = null
        if (working) return
        working = true
        Prefs.setAttempted(this, title)
        val ctx = applicationContext
        Thread {
            val msg = try {
                val book = CoverSearch.search(title, Prefs.ttbKey(ctx)).firstOrNull()
                if (book == null) {
                    "‘$title’ 표지를 찾지 못했어요"
                } else {
                    CoverWriter.apply(ctx, book.coverUrl, title)
                    "슬립 표지: $title"
                }
            } catch (e: Exception) {
                "슬립 표지 실패: ${e.message}"
            } finally {
                working = false
            }
            main.post { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
        }.start()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
