package io.github.i2996.sleepcover

/** 자동 감지된 글자와 검색 결과의 책 제목이 사실상 같은지 판단한다 (챕터 제목 오인 방지). */
object TitleMatch {
    private val BRACKETS = Regex("""\(.*?\)|\[.*?]|<.*?>|【.*?】""")
    private val NON_WORD = Regex("""[^\p{L}\p{N}]""")

    private fun norm(s: String) = s.lowercase().replace(BRACKETS, "").replace(NON_WORD, "")

    /** "제목 - 부제", "제목 : 부제" 에서 제목만. */
    private fun base(s: String) = s.split(" - ", " : ", ": ", " | ").first()

    fun matches(candidate: String, bookTitle: String): Boolean {
        val a = norm(candidate)
        if (a.length < 2) return false
        for (t in listOf(bookTitle, base(bookTitle))) {
            val b = norm(t)
            if (b.isEmpty()) continue
            if (a == b) return true
            val (short, long) = if (a.length <= b.length) a to b else b to a
            if (long.startsWith(short) && short.length >= long.length * 0.7) return true
        }
        return false
    }
}
