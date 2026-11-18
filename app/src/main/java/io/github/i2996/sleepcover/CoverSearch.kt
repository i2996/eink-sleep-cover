package io.github.i2996.sleepcover

import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

data class Book(val title: String, val author: String, val coverUrl: String)

/** 책 제목으로 표지 이미지를 찾는다. 알라딘 API(키 있을 때) → 알라딘 웹 검색 → Google 도서 순. */
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
        try {
            val r = searchAladinWeb(q)
            if (r.isNotEmpty()) return r
        } catch (e: Exception) {
            errors += "알라딘: ${e.message}"
        }
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
            val title = ALADIN_TITLE.find(block)?.groupValues?.get(1)?.let(::unescape).orEmpty().ifEmpty { q }
            if (seen.add(img)) books += Book(title, "", https(img))
            if (books.size >= 10) break
        }
        if (books.isEmpty()) {
            ALADIN_IMG.findAll(html).map { it.value }.filter { "/cover" in it }.distinct().take(10)
                .forEach { books += Book(q, "", https(it)) }
        }
        return books
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
