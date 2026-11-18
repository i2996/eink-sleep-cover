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

    /** 사용자가 "제목 맞음"으로 고정한 자리들 (TitleFinder.idKey 형식). */
    fun titleKeys(c: Context): Set<String> = sp(c).getStringSet("titleKeys", emptySet())!!.toSet()

    /** 사용자가 "아님"으로 막은 자리/글자들. */
    fun blockKeys(c: Context): Set<String> = sp(c).getStringSet("blockKeys", emptySet())!!.toSet()

    fun markTitle(c: Context, pkg: String, viewId: String) {
        val key = TitleFinder.idKey(pkg, viewId)
        // 한 앱에 고정 자리는 하나만 둔다.
        val titles = titleKeys(c).filterNot { it.startsWith("$pkg|") }.toSet() + key
        sp(c).edit()
            .putStringSet("titleKeys", HashSet(titles))
            .putStringSet("blockKeys", HashSet(blockKeys(c) - key))
            .apply()
    }

    fun markNotTitle(c: Context, pkg: String, viewId: String, text: String) {
        // 화면 요소 이름이 "title" 같은 공용 이름이면 진짜 제목까지 막힐 수 있어 글자로 막는다.
        val key = if (viewId.isNotEmpty() && !viewId.lowercase().endsWith("/title")) {
            TitleFinder.idKey(pkg, viewId)
        } else {
            TitleFinder.textKey(pkg, text)
        }
        sp(c).edit()
            .putStringSet("blockKeys", HashSet(blockKeys(c) + key))
            .putStringSet("titleKeys", HashSet(titleKeys(c) - key))
            .apply()
    }

    fun clearRules(c: Context) =
        sp(c).edit().remove("titleKeys").remove("blockKeys").apply()

    /** 최근 감지 기록: "pkg\tviewId\ttext" 줄들, 최신이 앞. */
    fun recent(c: Context): List<Triple<String, String, String>> =
        sp(c).getString("recent", "")!!.lines().filter { it.isNotBlank() }.mapNotNull {
            val p = it.split('\t')
            if (p.size == 3) Triple(p[0], p[1], p[2]) else null
        }

    fun addRecent(c: Context, pkg: String, viewId: String, text: String) {
        val line = listOf(pkg, viewId, text).joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }
        val old = sp(c).getString("recent", "")!!.lines().filter { it.isNotBlank() }
        if (old.firstOrNull() == line) return
        val lines = (listOf(line) + old.filter { it != line }).take(12)
        sp(c).edit().putString("recent", lines.joinToString("\n")).apply()
    }

    /** 업데이트를 마지막으로 확인한 시각 (System.currentTimeMillis). */
    fun lastUpdateCheck(c: Context) = sp(c).getLong("lastUpdateCheck", 0L)
    fun setLastUpdateCheck(c: Context, v: Long) = sp(c).edit().putLong("lastUpdateCheck", v).apply()

    /** 슬립 이미지로 마지막으로 저장한 책 제목. */
    fun applied(c: Context): String = sp(c).getString("applied", "")!!
    fun setApplied(c: Context, v: String) = sp(c).edit().putString("applied", v).apply()
}
