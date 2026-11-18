package io.github.i2996.sleepcover

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 독서 앱 화면에서 책 제목으로 보이는 글자를 고른다.
 * - id에 "title"이 들어간 글자를 가장 우선
 * - 아니면 화면 위쪽 20% 안의 짧은 글자
 * - 본문(WebView 안), 쪽수·시계·퍼센트·장 제목·메뉴 글자는 제외
 */
object TitleFinder {
    private val IGNORE = setOf(
        "목차", "메뉴", "설정", "검색", "북마크", "책갈피", "뒤로", "뒤로 가기", "닫기", "더보기",
        "하이라이트", "메모", "공유", "보기 설정", "뷰어 설정", "글꼴", "밝기", "서재", "내 서재",
        "홈", "책 정보", "이전", "다음", "확인", "취소", "듣기", "TTS", "구매", "대여",
    )

    private val BAD = listOf(
        Regex("""^[\d\s/%.:,·()\-~]+$"""),
        Regex("""\d+\s*%"""),
        Regex("""^\d{1,2}:\d{2}"""),
        Regex("""\d+\s*/\s*\d+"""),
        Regex("""페이지|남은\s*시간|분\s*남음|쪽\s*남음"""),
        Regex("""^(제\s*)?\d+\s*(장|부|화|권|편)\b"""),
        Regex("""^(chapter|part|prologue|epilogue)\b""", RegexOption.IGNORE_CASE),
        Regex("""^(프롤로그|에필로그|들어가며|들어가는 말|머리말|차례|서문|추천사)"""),
        // 책 앞뒤에 붙는 쪽 이름 (전자책 소제목으로 자주 보임)
        Regex(
            """^(속\s*표지|겉\s*표지|앞\s*표지|뒤\s*표지|표지|판권|판권\s*면|판권\s*페이지|저작권|목차|일러두기|""" +
                """감사의\s*말|작가의\s*말|지은이의\s*말|옮긴이의\s*말|역자\s*후기|저자\s*소개|작가\s*소개|""" +
                """해설|부록|참고\s*문헌|미주|주석|찾아보기|색인|발문|헌사|본문|시작하며|마치며|나가며)\s*$"""
        ),
        Regex(
            """^(cover|title\s*page|half\s*title|copyright|contents|table\s*of\s*contents|dedication|""" +
                """acknowledg(e)?ments|about\s*the\s*author|index|notes|introduction|preface|foreword|appendix)\s*$""",
            RegexOption.IGNORE_CASE,
        ),
    )

    private val SPACES = Regex("""\s+""")

    fun find(root: AccessibilityNodeInfo, screenH: Int): String? {
        var best: String? = null
        var bestScore = 0
        val topLimit = (screenH * 0.2).toInt()
        val r = Rect()
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Boolean>>()
        stack.addLast(root to false)
        var visited = 0
        while (stack.isNotEmpty() && visited < 600) {
            val (n, inWeb) = stack.removeLast()
            visited++
            val web = inWeb || (n.className?.toString()?.contains("WebView") == true)
            val text = n.text?.toString()?.replace(SPACES, " ")?.trim()
            if (!web && !text.isNullOrEmpty() && n.isVisibleToUser && looksLikeTitle(text)) {
                n.getBoundsInScreen(r)
                val id = n.viewIdResourceName?.lowercase().orEmpty()
                var s = 0
                if ("title" in id) s += 100
                if ("chapter" in id || "toc" in id || "section" in id || "subtitle" in id) s -= 80
                if (r.top in 0 until topLimit) s += 40
                if (s > 0) {
                    s += minOf(text.length, 30)
                    if (s > bestScore) {
                        bestScore = s
                        best = text
                    }
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.addLast(it to web) }
        }
        return best
    }

    private fun looksLikeTitle(t: String) =
        t.length in 2..60 && t !in IGNORE && BAD.none { it.containsMatchIn(t) }

    /** 공유하기로 받은 글에서 책 제목만 뽑는다. 예) "[리디] 책제목 - 저자 https://..." */
    fun fromShared(raw: String): String {
        val line = raw.replace(Regex("""https?://\S+"""), " ")
            .lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
        var t = line
            .replace(Regex("""^[\[【(<「『][^\]】)>」』]{0,20}[\]】)>」』]\s*"""), "")
            .replace(Regex("""[「」『』《》"“”]"""), "")
        for (sep in listOf(" - ", " | ", " / ", " : ")) {
            val i = t.indexOf(sep)
            if (i > 1) t = t.substring(0, i)
        }
        return t.trim().take(60)
    }
}
