package io.github.i2996.sleepcover

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.view.View
import android.view.WindowManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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

    private var foregroundPkg: String? = null

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
        // 와이파이 설정 창이 뜨면 스위치를 찾아 누른다.
        if (wifiPendingUntil > 0L && !wifiClicked) main.postDelayed({ tryClickWifiSwitch() }, 300)
        val isReader = pkg in Prefs.readers(this)

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val cls = event.className?.toString()
            // 리모컨 버튼은 지금 앞에 떠 있는 앱이 독서 앱일 때만 바꾼다.
            if (!isOverlay(pkg) || pkg == packageName) foregroundPkg = pkg
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

    /**
     * 리모컨(블루투스 키) 처리.
     * - 앱 화면이 버튼을 배우는 중이면 그쪽으로 넘긴다.
     * - 독서 앱이 앞에 있고 연결된 동작이 있으면 그 동작을 하고 원래 키는 막는다.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        RemoteKeys.learner?.let { learn ->
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) main.post { learn(code) }
            return true
        }
        if (!RemoteKeys.enabled(this)) return false
        val binding = RemoteKeys.map(this)[code] ?: return false
        if (!binding.anywhere) {
            // 지금 화면의 앱을 직접 확인하고, 안 되면 마지막으로 본 앞쪽 앱을 쓴다.
            val fg = try {
                rootInActiveWindow?.packageName?.toString()
            } catch (e: Exception) {
                null
            } ?: foregroundPkg ?: return false
            if (fg !in Prefs.readers(this)) return false
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) perform(binding)
        return true
    }

    private fun perform(binding: RemoteKeys.Binding) {
        val dm = RemoteKeys.screenSize(this)
        val w = dm.first.toFloat()
        val h = dm.second.toFloat()
        when (binding.action) {
            RemoteKeys.Action.LAUNCH_APP -> {
                val i = packageManager.getLaunchIntentForPackage(binding.pkg)
                if (i == null) {
                    Toast.makeText(this, "앱을 찾을 수 없어요: ${binding.pkg}", Toast.LENGTH_SHORT).show()
                } else {
                    try {
                        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Exception) {
                        Toast.makeText(this, "앱을 열 수 없어요: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            RemoteKeys.Action.NEXT -> tap(w * 0.85f, h * 0.5f)
            RemoteKeys.Action.PREV -> tap(w * 0.15f, h * 0.5f)
            RemoteKeys.Action.MENU -> tap(w * 0.5f, h * 0.5f)
            RemoteKeys.Action.SWIPE_NEXT -> swipe(w * 0.8f, h * 0.5f, w * 0.2f, h * 0.5f)
            RemoteKeys.Action.SWIPE_PREV -> swipe(w * 0.2f, h * 0.5f, w * 0.8f, h * 0.5f)
            RemoteKeys.Action.SCROLL_DOWN -> swipe(w * 0.5f, h * 0.7f, w * 0.5f, h * 0.3f)
            RemoteKeys.Action.SCROLL_UP -> swipe(w * 0.5f, h * 0.3f, w * 0.5f, h * 0.7f)
            RemoteKeys.Action.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            RemoteKeys.Action.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            RemoteKeys.Action.REFRESH -> flashRefresh()
            RemoteKeys.Action.WIFI -> startWifiToggle()
            RemoteKeys.Action.BLOCK -> {}
        }
    }

    // ── 와이파이 켜기/끄기 ──
    // 안드로이드 10+에서는 앱이 와이파이를 직접 못 바꾸므로,
    // 와이파이 설정 창을 띄우고 그 안의 스위치를 대신 누른 뒤 창을 닫는다.
    private var wifiPendingUntil = 0L
    private var wifiClicked = false

    private fun startWifiToggle() {
        wifiClicked = false
        wifiPendingUntil = SystemClock.uptimeMillis() + 6000
        val intents = mutableListOf<Intent>()
        if (Build.VERSION.SDK_INT >= 29) intents += Intent(Settings.Panel.ACTION_WIFI)
        intents += Intent(Settings.ACTION_WIFI_SETTINGS)
        for (i in intents) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                main.postDelayed({ tryClickWifiSwitch() }, 700)
                return
            } catch (e: Exception) {
            }
        }
        wifiPendingUntil = 0
        Toast.makeText(this, "와이파이 설정을 열 수 없어요", Toast.LENGTH_SHORT).show()
    }

    private fun tryClickWifiSwitch() {
        if (wifiClicked || wifiPendingUntil == 0L) return
        if (SystemClock.uptimeMillis() > wifiPendingUntil) {
            wifiPendingUntil = 0
            Toast.makeText(this, "와이파이 스위치를 찾지 못했어요", Toast.LENGTH_SHORT).show()
            return
        }
        val root = try {
            rootInActiveWindow
        } catch (e: Exception) {
            null
        } ?: return
        if (root.packageName?.toString()?.contains("settings", ignoreCase = true) != true) return
        val sw = findSwitch(root) ?: return
        val target = generateSequence(sw) { it.parent }.firstOrNull { it.isClickable } ?: return
        wifiClicked = true
        wifiPendingUntil = 0
        val wasOn = sw.isChecked
        target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        main.postDelayed({
            performGlobalAction(GLOBAL_ACTION_BACK)
            Toast.makeText(this, if (wasOn) "와이파이 껐어요" else "와이파이 켰어요", Toast.LENGTH_SHORT).show()
        }, 700)
    }

    private fun findSwitch(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < 400) {
            val n = queue.removeFirst()
            seen++
            if (n.isCheckable && n.className?.toString()?.contains("Switch") == true) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.addLast(it) }
        }
        return null
    }

    private var flashing = false

    /**
     * 전자잉크 잔상 지우기: 화면 전체를 잠깐 검게 → 하얗게 덮었다가 걷어서
     * 화면 전체가 다시 그려지게 한다 (기기 종류와 상관없이 동작).
     */
    private fun flashRefresh() {
        if (flashing) return
        if (booxRefresh()) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val v = View(this).apply { setBackgroundColor(Color.BLACK) }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        )
        try {
            wm.addView(v, lp)
        } catch (e: Exception) {
            Toast.makeText(this, "새로고침 화면을 띄우지 못했어요: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        flashing = true
        main.postDelayed({ v.setBackgroundColor(Color.WHITE) }, 600)
        main.postDelayed({
            try {
                wm.removeView(v)
            } catch (e: Exception) {
            }
            flashing = false
        }, 1200)
    }

    /**
     * Boox(오닉스) 기기의 자체 전체 새로고침을 부른다.
     * 오닉스 SDK가 쓰는 숨은 기능(View.repaintEverything)을 찾아 호출하고, 없으면 false.
     */
    private fun booxRefresh(): Boolean {
        val isBoox = listOf(Build.MANUFACTURER, Build.BRAND).any {
            it?.contains("onyx", ignoreCase = true) == true || it?.contains("boox", ignoreCase = true) == true
        }
        if (!isBoox) return false
        return try {
            val view = View::class.java
            val mode = listOf("UI_GC_MODE", "EINK_GC_MODE", "UI_GC4_MODE").firstNotNullOfOrNull { name ->
                try {
                    view.getField(name).getInt(null)
                } catch (e: Throwable) {
                    null
                }
            } ?: return false
            val m = view.getMethod("repaintEverything", Int::class.javaPrimitiveType)
            m.invoke(null, mode)
            true
        } catch (e: Throwable) {
            false
        }
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build(),
            null, null,
        )
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 250)).build(),
            null, null,
        )
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
        val found = TitleFinder.find(
            root, resources.displayMetrics.heightPixels, pkg, Prefs.titleKeys(this), Prefs.blockKeys(this)
        ) ?: return
        val title = found.text
        Prefs.setDetected(this, title, pkg)
        Prefs.addRecent(this, pkg, found.viewId, title)
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
