package org.sonora.android.wear.speaker

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import java.io.DataInputStream
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.math.min
import org.sonora.android.wear.WearProtocol as P

/**
 * Reads a track's audio from the phone over a Data Layer channel, the way ExoPlayer reads a file:
 * the watch has no internet of its own here, the phone has the stream (or the download).
 *
 * One channel per open. After a seek or a dropped link ExoPlayer opens again at the byte it needs,
 * and the phone starts writing from there (`from` in [P.PATH_STREAM]).
 */
@OptIn(UnstableApi::class)
class PhoneStreamDataSource(private val context: Context) : BaseDataSource(/* isNetwork = */ true) {
    private val channels = Wearable.getChannelClient(context)
    private var channel: ChannelClient.Channel? = null
    private var input: DataInputStream? = null
    private var uri: Uri? = null
    private var remaining = 0L
    private var transferring = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val id = trackId(dataSpec.uri) ?: throw FileNotFoundException("Not a phone track: ${dataSpec.uri}")
        // Plain IOExceptions: ExoPlayer retries them, playing on from its buffer meanwhile.
        val phone = findPhone(context) ?: throw IOException("Phone not reachable")
        val opened = await(channels.openChannel(phone, "${P.PATH_STREAM}?id=${Uri.encode(id)}&from=${dataSpec.position}"))
        channel = opened
        val stream = DataInputStream(await(channels.getInputStream(opened)))
        input = stream
        val status = stream.readInt()
        val length = stream.readLong()
        stream.readUTF() // MIME type: the extractors sniff the bytes anyway.
        // The phone already went through its own fallbacks: no point retrying, report the error.
        if (status != P.STREAM_OK) throw FileNotFoundException("The phone could not stream $id")
        val unset = C.LENGTH_UNSET.toLong()
        remaining = when {
            dataSpec.length == unset -> if (length >= 0) length else unset
            length >= 0 -> min(length, dataSpec.length)
            else -> dataSpec.length
        }
        transferring = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val known = remaining != C.LENGTH_UNSET.toLong()
        val stream = input ?: throw IOException("Not open")
        val read = stream.read(buffer, offset, if (known) min(remaining, length.toLong()).toInt() else length)
        if (read == -1) {
            // Cut short (the link dropped): an error, so ExoPlayer opens again from here.
            if (known) throw EOFException("The phone stream ended early")
            return C.RESULT_END_OF_INPUT
        }
        if (known) remaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        runCatching { input?.close() }
        channel?.let { channels.close(it) }
        input = null
        channel = null
        if (transferring) {
            transferring = false
            transferEnded()
        }
    }

    class Factory(context: Context) : DataSource.Factory {
        private val context = context.applicationContext
        override fun createDataSource(): DataSource = PhoneStreamDataSource(context)
    }

    companion object {
        private const val SCHEME = "lfly-phone"
        private const val TIMEOUT_S = 10L

        /** The media item URI of a phone track; this source reads only these. */
        fun uri(trackId: String): Uri = Uri.parse("$SCHEME://track/${Uri.encode(trackId)}")

        fun trackId(uri: Uri): String? = uri.takeIf { it.scheme == SCHEME }?.lastPathSegment

        /** The phone's node id, preferring one connected directly; null when none is reachable. Blocks. */
        fun findPhone(context: Context): String? = runCatching {
            val nodes = Tasks.await(
                Wearable.getCapabilityClient(context).getCapability(P.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE),
                TIMEOUT_S,
                TimeUnit.SECONDS
            ).nodes
            (nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull())?.id
        }.getOrNull()

        private fun <T> await(task: Task<T>): T = try {
            Tasks.await(task, TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Canceled")
        } catch (e: Exception) {
            throw IOException("Phone link: ${e.message}", e)
        }
    }
}
