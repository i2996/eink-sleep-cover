package io.github.i2996.sleepcover

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** 한 번 받은 표지 원본을 제목별로 앱 안에 저장해 두고, 와이파이 없이도 다시 쓴다. */
object CoverCache {
    private val SPACES = Regex("""\s+""")

    private fun dir(c: Context) = File(c.filesDir, "covers").apply { mkdirs() }

    private fun file(c: Context, title: String): File {
        val key = title.lowercase().replace(SPACES, "")
        val hash = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir(c), "$hash.img")
    }

    fun get(c: Context, title: String): ByteArray? {
        if (title.isBlank()) return null
        val f = file(c, title)
        return if (f.exists()) f.readBytes() else null
    }

    fun put(c: Context, title: String, bytes: ByteArray) {
        if (title.isBlank()) return
        file(c, title).writeBytes(bytes)
    }

    fun has(c: Context, title: String) = title.isNotBlank() && file(c, title).exists()

    fun count(c: Context) = dir(c).listFiles()?.size ?: 0
}
