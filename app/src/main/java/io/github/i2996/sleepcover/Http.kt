package io.github.i2996.sleepcover

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

object Http {
    // PC 브라우저처럼 접속해야 서점들이 모바일 사이트(구조가 다름)로 돌려보내지 않는다.
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    fun bytes(url: String): ByteArray {
        var current = url
        repeat(5) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9")
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: throw IOException("HTTP $code")
                    current = URL(URL(current), loc).toString()
                    return@repeat
                }
                if (code !in 200..299) throw IOException("HTTP $code")
                return conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("redirect가 너무 많아요")
    }

    fun text(url: String): String {
        val b = bytes(url)
        val head = String(b, 0, minOf(b.size, 4096), Charsets.ISO_8859_1).lowercase()
        val name = Regex("""charset=["']?([a-z0-9_-]+)""").find(head)?.groupValues?.get(1)
        val cs = try {
            if (name != null) Charset.forName(name) else Charsets.UTF_8
        } catch (e: Exception) {
            Charsets.UTF_8
        }
        return String(b, cs)
    }
}
