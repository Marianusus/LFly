package org.sonora.android.wear

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.sonora.android.api.Track
import org.sonora.android.playback.AudioCacheManager
import org.sonora.android.playback.DownloadManager
import org.sonora.android.playback.PlaybackManager
import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import org.sonora.android.wear.WearProtocol as P

/**
 * The audio of the song the watch plays, served over a Data Layer channel: the watch has no
 * internet of its own here, so the phone reads the stream the way its own player does (the same
 * resolver, full streams only, the same disk cache and HTTP client with its headers, local files
 * for downloads) and copies the bytes, from whatever offset the watch asks.
 *
 * A channel the watch closes (it seeked, skipped or stopped) just ends the copy.
 */
@OptIn(UnstableApi::class)
internal object WatchStream {
    private const val TAG = "LFlyWatchStream"
    private const val CHUNK = 64 * 1024

    /** Upstream drops survived per request: a paused watch leaves the connection idle for long. */
    private const val REOPENS = 3

    private const val QUERY_ID = "id"
    private const val QUERY_FROM = "from"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val open: MutableSet<ChannelClient.Channel> = ConcurrentHashMap.newKeySet()

    /** One readable copy of the song, from a byte offset. */
    private interface Source : Closeable {
        /** Bytes from the offset to the end, or -1 when unknown. */
        val length: Long
        val mime: String

        /** Like [java.io.InputStream.read]: -1 at the end. */
        fun read(buffer: ByteArray): Int
    }

    private class FileSource(file: File, offset: Long) : Source {
        private val input = FileInputStream(file).also { it.channel.position(offset) }
        override val length = (file.length() - offset).coerceAtLeast(0L)
        override val mime = ""
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
        }

        override fun read(buffer: ByteArray): Int {
            val read = data.read(buffer, 0, buffer.size)
            return if (read == C.RESULT_END_OF_INPUT) -1 else read
        }

        override fun close() = data.close()
    }

    /** The upstream gave out mid-song more often than [REOPENS]. */
    private class UpstreamLost(cause: IOException) : IOException(cause)

    /** A channel the watch opened on [P.PATH_STREAM]; the copy runs off the caller's thread. */
    fun serve(context: Context, channel: ChannelClient.Channel) {
        val app = context.applicationContext
        scope.launch {
            open += channel
            try {
                pipe(app, channel)
            } catch (e: Exception) {
                Log.w(TAG, "Stream ended: ${e.message}")
                runCatching { Wearable.getChannelClient(app).close(channel) }
            } finally {
                open -= channel
            }
        }
    }

    /** Closes every stream: the watch is no longer the speaker. */
    fun closeAll(context: Context) {
        val channels = Wearable.getChannelClient(context)
        open.toList().forEach { runCatching { channels.close(it) } }
        open.clear()
    }

    private suspend fun pipe(app: Context, channel: ChannelClient.Channel) {
        val query = Uri.parse(channel.path)
        val id = query.getQueryParameter(QUERY_ID).orEmpty()
        val from = query.getQueryParameter(QUERY_FROM)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        val raw = Tasks.await(Wearable.getChannelClient(app).getOutputStream(channel))
        val out = DataOutputStream(raw)
        var source: Source? = null
        try {
            val track = WatchSpeaker.trackFor(id)
            source = track?.let { openSource(app, it, from, fresh = false) }
            if (track == null || source == null) {
                Log.w(TAG, "No stream for '$id'")
                writeHeader(out, P.STREAM_FAILED, -1L, "")
                return
            }
            writeHeader(out, P.STREAM_OK, source.length, source.mime)
            Log.d(TAG, "Streaming '${track.title}' from byte $from (${source.length} bytes)")
            val buffer = ByteArray(CHUNK)
            var offset = from
            var reopens = 0
            while (true) {
                val current: Source = source ?: break
                val read = try {
                    current.read(buffer)
                } catch (e: IOException) {
                    // The connection dropped (often idle while the watch's buffer was full): go on from here.
                    runCatching { current.close() }
                    source = null
                    if (++reopens > REOPENS) throw UpstreamLost(e)
                    source = openSource(app, track, offset, fresh = reopens > 1) ?: throw UpstreamLost(e)
                    continue
                }
                if (read < 0) break
                if (!copy(out, buffer, read)) return
                offset += read
            }
            out.flush()
        } finally {
            runCatching { source?.close() }
            // End of the stream: a short one makes the watch ask again from where it stopped.
            runCatching { out.close() }
        }
    }

    /** False once the watch closed the channel (a seek, the next song, or stop): end quietly. */
    private fun copy(out: OutputStream, buffer: ByteArray, count: Int): Boolean = try {
        out.write(buffer, 0, count)
        true
    } catch (_: IOException) {
        false
    }

    private fun writeHeader(out: DataOutputStream, status: Int, length: Long, mime: String) {
        out.writeInt(status)
        out.writeLong(length)
        out.writeUTF(mime)
        out.flush()
    }

    /**
     * Opens [track] at [offset] the way the player would: the file or URL it plays from, a
     * download, else a fresh resolution. A failing URL (signed links expire) is resolved again once.
     */
    private suspend fun openSource(app: Context, track: Track, offset: Long, fresh: Boolean): Source? {
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
}
