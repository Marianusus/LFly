package org.sonora.android.api

import com.google.gson.Gson
import java.io.File
import java.security.MessageDigest

data class CachedLyrics(
    val notFound: Boolean = false,
    val id: String? = null,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null
)

class LyricsCache(private val cacheDir: File?) {

    private val gson: Gson = HttpClientProvider.gson

    private fun fileFor(key: String): File? {
        val base = cacheDir ?: return null
        val dir = File(base, "lyrics")
        if (!dir.exists() && !dir.mkdirs()) return null
        return File(dir, "${sha1(key)}.json")
    }

    fun read(key: String): CachedLyrics? {
        val file = fileFor(key) ?: return null
        if (!file.exists()) return null
        return try {
            gson.fromJson(file.readText(), CachedLyrics::class.java)
        } catch (_: Exception) {
            null
        }
    }

    fun write(key: String, entry: CachedLyrics) {
        val file = fileFor(key) ?: return
        try {
            file.writeText(gson.toJson(entry))
        } catch (_: Exception) {
        }
    }

    fun clear() {
        val dir = cacheDir?.let { File(it, "lyrics") } ?: return
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun sha1(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
