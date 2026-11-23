package io.github.i2996.sleepcover

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import android.view.KeyEvent

/** 리모컨 버튼(키 코드) → 동작 연결. 독서 앱이 화면에 있을 때만 쓴다. */
object RemoteKeys {
    enum class Action(val label: String) {
        NEXT("다음 페이지 (오른쪽 탭)"),
        PREV("이전 페이지 (왼쪽 탭)"),
        SWIPE_NEXT("다음 페이지 (왼쪽으로 넘기기)"),
        SWIPE_PREV("이전 페이지 (오른쪽으로 넘기기)"),
        SCROLL_DOWN("아래로 스크롤"),
        SCROLL_UP("위로 스크롤"),
        MENU("메뉴 열기 (가운데 탭)"),
        REFRESH("화면 새로고침 (잔상 지우기)"),
        BACK("뒤로 가기"),
        HOME("홈 화면"),
        BLOCK("아무것도 안 함 (버튼 막기)"),
    }

    /** 배우기 모드: 앱 화면이 리모컨 버튼을 기다리는 중이면 여기로 키 코드를 넘긴다. */
    @Volatile
    var learner: ((Int) -> Unit)? = null

    fun enabled(c: Context) = sp(c).getBoolean("remoteOn", true)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("remoteOn", v).apply()

    /** 저장된 버튼 연결 (키 코드 → 동작). */
    fun map(c: Context): Map<Int, Action> =
        sp(c).getString("keymap", "")!!.split(';').filter { '=' in it }.mapNotNull {
            val (k, a) = it.split('=', limit = 2)
            val code = k.toIntOrNull() ?: return@mapNotNull null
            val action = Action.values().firstOrNull { x -> x.name == a } ?: return@mapNotNull null
            code to action
        }.toMap()

    fun put(c: Context, keyCode: Int, action: Action) = save(c, map(c) + (keyCode to action))

    fun remove(c: Context, keyCode: Int) = save(c, map(c) - keyCode)

    private fun save(c: Context, m: Map<Int, Action>) =
        sp(c).edit().putString("keymap", m.entries.joinToString(";") { "${it.key}=${it.value.name}" }).apply()

    /** 지금 화면 방향 기준 (가로, 세로) 실제 픽셀. 터치 위치 계산용. */
    fun screenSize(c: Context): Pair<Int, Int> {
        val dm = c.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        dm.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }

    fun keyName(code: Int): String =
        KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").replace('_', ' ')

    private fun sp(c: Context) =
        c.applicationContext.getSharedPreferences("sleepcover", Context.MODE_PRIVATE)
}
