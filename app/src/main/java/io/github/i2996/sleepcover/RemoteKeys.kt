package io.github.i2996.sleepcover

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import android.view.KeyEvent

/** 리모컨 버튼(키 코드) → 동작 연결. */
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
        LAUNCH_APP("앱 열기 (어디서든)"),
        WIFI("와이파이 켜기/끄기 (어디서든)"),
        BACK("뒤로 가기"),
        HOME("홈 화면"),
        BLOCK("아무것도 안 함 (버튼 막기)"),
    }

    /** 버튼 하나의 설정. [pkg]는 LAUNCH_APP일 때 열 앱. */
    data class Binding(val action: Action, val pkg: String = "") {
        /** 앱 열기·와이파이는 어디서든, 나머지는 독서 앱이 앞에 있을 때만. */
        val anywhere get() = action == Action.LAUNCH_APP || action == Action.WIFI
    }

    /** 배우기 모드: 앱 화면이 리모컨 버튼을 기다리는 중이면 여기로 키 코드를 넘긴다. */
    @Volatile
    var learner: ((Int) -> Unit)? = null

    fun enabled(c: Context) = sp(c).getBoolean("remoteOn", true)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("remoteOn", v).apply()

    /** 저장 형식: "키코드=동작" 또는 "키코드=LAUNCH_APP|패키지" 를 ';'로 이음. */
    fun map(c: Context): Map<Int, Binding> =
        sp(c).getString("keymap", "")!!.split(';').filter { '=' in it }.mapNotNull {
            val (k, v) = it.split('=', limit = 2)
            val code = k.toIntOrNull() ?: return@mapNotNull null
            val name = v.substringBefore('|')
            val action = Action.values().firstOrNull { x -> x.name == name } ?: return@mapNotNull null
            code to Binding(action, v.substringAfter('|', ""))
        }.toMap()

    fun put(c: Context, keyCode: Int, b: Binding) = save(c, map(c) + (keyCode to b))

    fun remove(c: Context, keyCode: Int) = save(c, map(c) - keyCode)

    private fun save(c: Context, m: Map<Int, Binding>) =
        sp(c).edit().putString(
            "keymap",
            m.entries.joinToString(";") { (k, b) ->
                if (b.pkg.isEmpty()) "$k=${b.action.name}" else "$k=${b.action.name}|${b.pkg}"
            },
        ).apply()

    fun label(c: Context, b: Binding): String {
        if (b.action != Action.LAUNCH_APP) return b.action.label
        val name = try {
            c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(b.pkg, 0)).toString()
        } catch (e: Exception) {
            b.pkg
        }
        return "앱 열기: $name"
    }

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
