package io.github.i2996.sleepcover

import android.content.Context
import android.content.SharedPreferences
import android.os.Build

object Prefs {
    /** 처음 설치했을 때 기본으로 고르는 독서 앱 (앱 화면에서 바꿀 수 있음). */
    val DEFAULT_READERS = setOf(
        "com.initialcoms.ridi",            // 리디
        "com.kyobo.ebook.common.b2c",      // 교보eBook
        "kr.co.millie.millieshelf",        // 밀리의 서재
        "com.google.android.apps.books",   // Google Play 북
        "com.yes24.ebook.fourth",          // 예스24 eBook
        "kr.co.aladin.ebook",              // 알라딘 eBook
    )

    private fun sp(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("sleepcover", Context.MODE_PRIVATE)

    fun isCrema(): Boolean =
        listOf(Build.MANUFACTURER, Build.BRAND, Build.MODEL, Build.PRODUCT, Build.DEVICE)
            .any { it?.contains("crema", ignoreCase = true) == true }

    /** 크레마는 내부 저장소의 Wallpaper 폴더 이미지만 슬립 이미지로 보여 줌. */
    fun defaultFolder(): String = if (isCrema()) "Wallpaper" else "Pictures/SleepCover"

    fun readers(c: Context): Set<String> =
        sp(c).getStringSet("readers", null)?.toSet() ?: DEFAULT_READERS

    fun setReaders(c: Context, v: Set<String>) =
        sp(c).edit().putStringSet("readers", HashSet(v)).apply()

    fun folder(c: Context): String {
        val v = sp(c).getString("folder", null)
            ?.trim()?.trim('/')
            ?.split('/')?.filter { it.isNotBlank() && it != "." && it != ".." }
            ?.joinToString("/")
        return if (v.isNullOrEmpty()) defaultFolder() else v
    }

    fun setFolder(c: Context, v: String) = sp(c).edit().putString("folder", v.trim()).apply()

    fun ttbKey(c: Context): String = sp(c).getString("ttbKey", "")!!.trim()
    fun setTtbKey(c: Context, v: String) = sp(c).edit().putString("ttbKey", v.trim()).apply()

    fun auto(c: Context) = sp(c).getBoolean("auto", true)
    fun setAuto(c: Context, v: Boolean) = sp(c).edit().putBoolean("auto", v).apply()

    fun fill(c: Context) = sp(c).getBoolean("fill", false)
    fun setFill(c: Context, v: Boolean) = sp(c).edit().putBoolean("fill", v).apply()

    fun gray(c: Context) = sp(c).getBoolean("gray", false)
    fun setGray(c: Context, v: Boolean) = sp(c).edit().putBoolean("gray", v).apply()

    /** 접근성 서비스가 마지막으로 읽은 (제목, 앱 패키지). */
    fun detected(c: Context): Pair<String, String>? {
        val t = sp(c).getString("detTitle", null) ?: return null
        return t to sp(c).getString("detPkg", "")!!
    }

    fun setDetected(c: Context, title: String, pkg: String) =
        sp(c).edit().putString("detTitle", title).putString("detPkg", pkg).apply()

    /** 자동 적용을 마지막으로 시도한 제목 (같은 책에서 반복 검색하지 않게). */
    fun attempted(c: Context): String = sp(c).getString("attempted", "")!!
    fun setAttempted(c: Context, v: String) = sp(c).edit().putString("attempted", v).apply()

    /** 슬립 이미지로 마지막으로 저장한 책 제목. */
    fun applied(c: Context): String = sp(c).getString("applied", "")!!
    fun setApplied(c: Context, v: String) = sp(c).edit().putString("applied", v).apply()
}
