package org.sonora.android.playback

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import org.sonora.android.api.Lyrics
import java.io.File
import java.security.MessageDigest

/**
 * Lyrics resolved for an offline download.
 *
 * [notFound] is a negative marker: the lookup ran and no source had lyrics, so the
 * fetcher does not need to ask again on every playback. A store miss is `null`.
 */
data class OfflineLyrics(
    val lyrics: Lyrics?,
    val source: String?,
    val notFound: Boolean,
    val fetchedAt: Long = System.currentTimeMillis(),
)

/**
 * Disk persistence for lyrics belonging to downloaded tracks.
 *
 * One JSON file per track under [root], named by the SHA-1 of the track id so ids with
 * slashes, colons or other provider-specific characters can never escape the directory.
 * Writes go through a temp file + rename, and an unreadable/corrupt file is treated as a
 * miss instead of crashing playback. Files live in `filesDir/downloaded_lyrics`, never in
 * the cache directory, because offline lyrics must survive cache eviction.
 */
class DownloadLyricsStore(private val root: File) {

    private val gson = Gson()

    fun read(trackId: String): OfflineLyrics? {
        val file = fileFor(trackId) ?: return null
        if (!file.isFile) return null
        return try {
            gson.fromJson(file.readText(Charsets.UTF_8), OfflineLyrics::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "Unreadable lyrics file '${file.name}': ${e.message}")
            null
        }
    }

    fun write(trackId: String, value: OfflineLyrics) {
        val file = fileFor(trackId, createDir = true) ?: return
        val temp = File(file.parentFile, "${file.name}.tmp")
        try {
            temp.writeText(gson.toJson(value), Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                // Some file systems refuse to replace an existing target with rename.
                file.delete()
                if (!temp.renameTo(file)) {
                    temp.copyTo(file, overwrite = true)
                    temp.delete()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write lyrics file '${file.name}': ${e.message}")
            temp.delete()
        }
    }

    fun delete(trackId: String) {
        val file = fileFor(trackId) ?: return
        file.delete()
        File(file.parentFile, "${file.name}.tmp").delete()
    }

    fun clear() {
        root.listFiles()?.forEach { it.delete() }
    }

    fun has(trackId: String): Boolean = fileFor(trackId)?.isFile == true

    private fun fileFor(trackId: String, createDir: Boolean = false): File? {
        if (trackId.isEmpty()) return null
        if (createDir && !root.isDirectory && !root.mkdirs() && !root.isDirectory) {
            Log.w(TAG, "Cannot create lyrics directory '${root.absolutePath}'")
            return null
        }
        return File(root, sha1(trackId) + ".json")
    }

    private fun sha1(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "DownloadLyricsStore"
        private const val DIR_NAME = "downloaded_lyrics"

        fun default(context: Context): DownloadLyricsStore =
            DownloadLyricsStore(File(context.filesDir, DIR_NAME))
    }
}
