package io.github.i2996.sleepcover

import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/** [exact]: 서점이 알려 준 실제 책 제목인지 (false면 검색어를 제목 자리에 넣은 것). */
data class Book(val title: String, val author: String, val coverUrl: String, val exact: Boolean = true)

/** 책 제목으로 표지 이미지를 찾는다. 알라딘 API(키 있을 때) → 알라딘·예스24·교보 웹 검색 → Google 도서 순. */
object CoverSearch {
    private val COVER_SIZE = Regex("""/cover(sum|\d+)/""")

    /** 알라딘 표지 주소를 가장 큰 크기(500px)로 바꾼다. */
    fun bigCover(url: String): String = COVER_SIZE.replace(url, "/cover500/")

    fun search(query: String, ttbKey: String): List<Book> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val errors = mutableListOf<String>()
        if (ttbKey.isNotBlank()) {
            try {
                val r = searchAladinApi(q, ttbKey)
                if (r.isNotEmpty()) return r
            } catch (e: Exception) {
                errors += "알라딘 API: ${e.message}"
            }
        }
        // 키 없이 되는 서점 검색들을 모두 돌려서 결과를 합친다 (한 곳이 막혀도 다른 곳에서 찾게).
        val merged = mutableListOf<Book>()
        val sources = listOf<Pair<String, (String) -> List<Book>>>(
            "알라딘" to { s: String -> searchAladinWeb(s) },
            "예스24" to { s: String -> searchYes24(s) },
            "교보" to { s: String -> searchKyobo(s) },
        )
        for ((name, fn) in sources) {
            try {
                merged += fn(q).take(6)
            } catch (e: Exception) {
                errors += "$name: ${e.message}"
            }
        }
        if (merged.isNotEmpty()) return merged.distinctBy { it.coverUrl }
        try {
            val r = searchGoogle(q)
            if (r.isNotEmpty()) return r
        } catch (e: Exception) {
            errors += "Google: ${e.message}"
        }
        if (errors.isNotEmpty()) throw IOException(errors.joinToString(" / "))
        return emptyList()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun https(u: String) = if (u.startsWith("http://")) "https://" + u.removePrefix("http://") else u

    private fun unescape(s: String) = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        .trim()

    private fun searchAladinApi(q: String, key: String): List<Book> {
        val url = "https://www.aladin.co.kr/ttb/api/ItemSearch.aspx?ttbkey=${enc(key)}&Query=${enc(q)}" +
            "&QueryType=Keyword&MaxResults=10&start=1&SearchTarget=All&output=js&Version=20131101&Cover=Big"
        var body = Http.text(url).trim().replace("\\'", "'")
        if (body.endsWith(";")) body = body.dropLast(1)
        val json = JSONObject(body)
        if (json.has("errorMessage")) throw IOException(json.optString("errorMessage"))
        val items = json.optJSONArray("item") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val o = items.getJSONObject(i)
            val cover = o.optString("cover")
            if (cover.isBlank()) null else Book(o.optString("title"), o.optString("author"), https(cover))
        }
    }

    private val ALADIN_IMG = Regex(
        """https?://image\.aladin\.co\.kr/product/[^"'\s>]+?\.(?:jpg|jpeg|png|gif)""",
        RegexOption.IGNORE_CASE,
    )
    private val ALADIN_TITLE = Regex("""class="bo3"[^>]*>\s*(?:<b>)?([^<]+)""")

    private fun searchAladinWeb(q: String): List<Book> {
        val html = Http.text(
            "https://www.aladin.co.kr/search/wsearchresult.aspx?SearchTarget=All&SearchWord=${enc(q)}"
        )
        val books = mutableListOf<Book>()
        val seen = HashSet<String>()
        for (block in html.split("ss_book_box").drop(1)) {
            val img = ALADIN_IMG.findAll(block).map { it.value }.firstOrNull { "/cover" in it } ?: continue
            val title = ALADIN_TITLE.find(block)?.groupValues?.get(1)?.let(::unescape).orEmpty()
            if (seen.add(img)) books += Book(title.ifEmpty { q }, "알라딘", https(img), exact = title.isNotEmpty())
            if (books.size >= 10) break
        }
        if (books.isEmpty()) {
            ALADIN_IMG.findAll(html).map { it.value }.filter { "/cover" in it }.distinct().take(10)
                .forEach { books += Book(q, "", https(it), exact = false) }
        }
        return books
    }

    private val YES24_IMG = Regex("""image\.yes24\.com/goods/(\d+)/""")
    private val YES24_TITLE = Regex("""class="gd_name"[^>]*>([^<]+)""")

    private fun searchYes24(q: String): List<Book> {
        val html = Http.text("https://www.yes24.com/Product/Search?domain=ALL&query=${enc(q)}")
        val ids = YES24_IMG.findAll(html).map { it.groupValues[1] }.distinct().take(10).toList()
        val titles = YES24_TITLE.findAll(html).map { unescape(it.groupValues[1]) }.toList()
        val sameCount = titles.size == ids.size
        return ids.mapIndexed { i, id ->
            Book(if (sameCount) titles[i] else q, "예스24", "https://image.yes24.com/goods/$id/XL", exact = sameCount)
        }
    }

    private val KYOBO_IMG = Regex("""contents\.kyobobook\.co\.kr/sih/fit-in/\d+x0/pdt/([0-9A-Za-z_-]+)\.jpg""")

    private fun searchKyobo(q: String): List<Book> {
        val html = Http.text("https://search.kyobobook.co.kr/search?keyword=${enc(q)}&gbCode=TOT&target=total")
        return KYOBO_IMG.findAll(html).map { it.groupValues[1] }.distinct().take(10).map {
            Book(q, "교보문고", "https://contents.kyobobook.co.kr/sih/fit-in/458x0/pdt/$it.jpg", exact = false)
        }.toList()
    }

    private fun searchGoogle(q: String): List<Book> {
        val json = JSONObject(Http.text("https://www.googleapis.com/books/v1/volumes?maxResults=10&q=${enc(q)}"))
        val items = json.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val info = items.getJSONObject(i).optJSONObject("volumeInfo") ?: return@mapNotNull null
            val links = info.optJSONObject("imageLinks") ?: return@mapNotNull null
            val img = links.optString("thumbnail").ifBlank { links.optString("smallThumbnail") }
            if (img.isBlank()) return@mapNotNull null
            val authors = info.optJSONArray("authors")
            val author = if (authors == null) "" else (0 until authors.length()).joinToString(", ") { authors.getString(it) }
            Book(info.optString("title", q), author, https(img).replace("&edge=curl", ""))
        }
    }
}
