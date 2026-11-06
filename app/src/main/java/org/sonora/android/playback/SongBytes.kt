package org.sonora.android.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import org.sonora.android.api.Track
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * The audio bytes of a song, read the way the phone's own player reads them: the same resolver
 * (full streams only), the same disk cache and HTTP client with its headers, local files for
 * downloads. Another speaker the phone feeds (the watch, a Chromecast) pulls the song through this.
 */
@OptIn(UnstableApi::class)
internal object SongBytes {
    private const val TAG = "LFlySongBytes"

    /** One readable copy of the song, from a byte offset. */
    interface Source : Closeable {
        /** Bytes from the offset to the end, or -1 when unknown. */
        val length: Long

        /** The stream's content type; empty for local files. */
        val mime: String

        /** Like [java.io.InputStream.read]: -1 at the end. */
        fun read(buffer: ByteArray): Int
    }

    private class FileSource(file: File, offset: Long) : Source {
        private val input = FileInputStream(file).also { it.channel.position(offset) }
        override val length = (file.length() - offset).coerceAtLeast(0L)
        override val mime = mimeOfFile(file.name)
        override fun read(buffer: ByteArray): Int = input.read(buffer)
        override fun close() = input.close()
    }

    /** Through the player's own cache: the next song's prefetched head is there already. */
    private class WebSource(context: Context, url: String, offset: Long) : Source {
        private val data: DataSource = AudioCacheManager.getCacheDataSourceFactory(context).createDataSource()
        override val length: Long
        override val mime: String

        init {
            val opened = try {
                data.open(DataSpec.Builder().setUri(Uri.parse(url)).setPosition(offset).build())
            } catch (e: IOException) {
                runCatching { data.close() }
                throw e
            }
            length = if (opened == C.LENGTH_UNSET.toLong()) -1L else opened
            mime = data.responseHeaders.entries
                .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                ?.value?.firstOrNull().orEmpty()
                .ifBlank { mimeOfUrl(url) }
        }

        override fun read(buffer: ByteArray): Int {
            val read = data.read(buffer, 0, buffer.size)
            return if (read == C.RESULT_END_OF_INPUT) -1 else read
        }

        override fun close() = data.close()
    }

    /**
     * Opens [track] at [offset] the way the player would: the file or URL it plays from, a
     * download, else a fresh resolution. A failing URL (signed links expire) is resolved again once.
     */
    suspend fun open(app: Context, track: Track, offset: Long, fresh: Boolean): Source? {
        val remembered = track.streamUrl?.takeIf { !fresh }
        if (remembered != null && PlaybackManager.isLocalStreamUrl(remembered)) {
            File(remembered.removePrefix("file://")).takeIf { it.isFile }?.let { return FileSource(it, offset) }
        }
        val web = remembered?.takeIf { isFullWebStream(track, it) }
        if (web == null) {
            DownloadManager.localPathFor(track.id)?.let { path ->
                File(path).takeIf { it.isFile }?.let { return FileSource(it, offset) }
            }
        }
        var url = web ?: resolve(app, track, fresh)
        repeat(2) { attempt ->
            val target = url ?: return null
            if (PlaybackManager.isLocalStreamUrl(target)) {
                return File(target.removePrefix("file://")).takeIf { it.isFile }?.let { FileSource(it, offset) }
            }
            try {
                return WebSource(app, target, offset)
            } catch (e: IOException) {
                Log.w(TAG, "Could not open '${track.title}' (${e.message})")
                url = if (attempt == 0) resolve(app, track, fresh = true) else null
            }
        }
        return null
    }

    private fun isFullWebStream(track: Track, url: String): Boolean =
        (url.startsWith("https://") || url.startsWith("http://")) &&
            !PlaybackManager.isPreviewUrl(url) &&
            !track.provider.equals("Deezer", ignoreCase = true)

    /** A full stream from the phone's resolver; never a 30 s preview, which would end the song early. */
    private suspend fun resolve(app: Context, track: Track, fresh: Boolean): String? {
        val resolver = PlaybackManager.getInstance(app).streamResolver
        if (fresh) resolver.invalidateCache(track.id, track.title, track.artist)
        val stream = runCatching { resolver.resolveStream(track) }.getOrNull() ?: return null
        if (stream.isPreview || PlaybackManager.isPreviewUrl(stream.url)) return null
        return stream.url
    }

    fun mimeOfFile(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a", "mp4", "aac" -> "audio/mp4"
        "flac" -> "audio/flac"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/ogg"
        "webm", "weba" -> "audio/webm"
        "wav" -> "audio/wav"
        else -> ""
    }

    /** YouTube's links say the format in a `mime=` query parameter. */
    private fun mimeOfUrl(url: String): String =
        runCatching { Uri.parse(url).getQueryParameter("mime") }.getOrNull()?.substringBefore(';').orEmpty()
}
