package org.sonora.android.playback.automix

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.File
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Mono audio at the model's rate, and where it starts in the song. */
internal class DecodedWindow(val samples: FloatArray, val startSeconds: Double)

/**
 * Decodes one stretch of a song with the platform codecs. Remote songs are read through the
 * player's own cache and HTTP stack, so analysis reuses what playback downloaded (and the other way
 * round) and sends the same requests the player would.
 */
@OptIn(UnstableApi::class)
internal object AudioWindowDecoder {

    private const val TIMEOUT_US = 10_000L

    fun decode(
        source: String,
        startSeconds: Double,
        durationSeconds: Double,
        remote: DataSource.Factory
    ): DecodedWindow? {
        val extractor = MediaExtractor()
        var dataSource: MediaDataSource? = null
        var codec: MediaCodec? = null
        try {
            val localPath = localPathOf(source)
            if (localPath != null) {
                extractor.setDataSource(localPath)
            } else {
                dataSource = Media3MediaDataSource(remote, Uri.parse(source))
                extractor.setDataSource(dataSource)
            }
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null

            val startUs = (startSeconds.coerceAtLeast(0.0) * 1_000_000).toLong()
            val endUs = startUs + (durationSeconds * 1_000_000).toLong()
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }

            val mono = FloatSamples()
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false
            var firstKeptUs = -1L
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val buffer = codec.getInputBuffer(inputIndex) ?: return null
                        val size = extractor.readSampleData(buffer, 0)
                        val time = extractor.sampleTime
                        if (size < 0 || time > endUs) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, time, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val output = codec.outputFormat
                        sampleRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        isFloat = output.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            output.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    outputIndex >= 0 -> {
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        val buffer = codec.getOutputBuffer(outputIndex)
                        if (buffer != null && info.size > 0 && channels > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val ordered = buffer.order(ByteOrder.nativeOrder())
                            val frames = if (isFloat) info.size / (4 * channels) else info.size / (2 * channels)
                            for (frame in 0 until frames) {
                                val timeUs = info.presentationTimeUs + frame * 1_000_000L / sampleRate
                                if (timeUs < startUs || timeUs >= endUs) {
                                    // Skip the frame's samples without keeping them.
                                    if (isFloat) repeat(channels) { ordered.float } else repeat(channels) { ordered.short }
                                    continue
                                }
                                if (firstKeptUs < 0) firstKeptUs = timeUs
                                var sum = 0f
                                repeat(channels) {
                                    sum += if (isFloat) ordered.float else ordered.short / 32_768f
                                }
                                mono.add(sum / channels)
                            }
                            if (info.presentationTimeUs >= endUs) outputDone = true
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }

            if (firstKeptUs < 0 || mono.size == 0) return null
            val resampled = Resampler.resample(mono.toArray(), sampleRate, LogMelSpectrogram.SAMPLE_RATE)
            return DecodedWindow(resampled, firstKeptUs / 1_000_000.0)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            runCatching { dataSource?.close() }
        }
    }

    private fun localPathOf(source: String): String? = when {
        source.startsWith("file://") -> Uri.parse(source).path
        source.startsWith("/") -> source
        else -> null
    }?.takeIf { File(it).isFile }

    private class FloatSamples {
        private var data = FloatArray(1 shl 16)
        var size = 0
            private set

        fun add(value: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = value
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }
}

/**
 * Gives MediaExtractor random access to a Media3 [DataSource]. Reads usually follow each other, so
 * one open stream is kept and only reopened when the extractor jumps.
 */
@OptIn(UnstableApi::class)
private class Media3MediaDataSource(factory: DataSource.Factory, private val uri: Uri) : MediaDataSource() {
    private val source = factory.createDataSource()
    private var position = -1L
    private var length = C.LENGTH_UNSET.toLong()
    private var opened = false

    @Synchronized
    override fun getSize(): Long {
        if (length == C.LENGTH_UNSET.toLong()) openAt(0L)
        return if (length == C.LENGTH_UNSET.toLong()) -1L else length
    }

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        if (length != C.LENGTH_UNSET.toLong() && position >= length) return -1
        if (!opened || position != this.position) openAt(position)
        var total = 0
        while (total < size) {
            val read = source.read(buffer, offset + total, size - total)
            if (read == C.RESULT_END_OF_INPUT) break
            total += read
            this.position += read
        }
        return if (total == 0) -1 else total
    }

    private fun openAt(target: Long) {
        if (opened) runCatching { source.close() }
        val opened = source.open(DataSpec.Builder().setUri(uri).setPosition(target).build())
        if (target == 0L && opened != C.LENGTH_UNSET.toLong()) length = opened
        else if (opened != C.LENGTH_UNSET.toLong() && length == C.LENGTH_UNSET.toLong()) length = target + opened
        this.opened = true
        position = target
    }

    @Synchronized
    override fun close() {
        if (opened) runCatching { source.close() }
        opened = false
    }
}

/** Band-limited resampling (a Hann-windowed sinc), so no audio above the new Nyquist folds back. */
internal object Resampler {
    private const val HALF_TAPS = 16

    fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate || input.isEmpty()) return input
        val ratio = fromRate.toDouble() / toRate
        val cutoff = minOf(1.0, 1.0 / ratio) * 0.95
        val outputSize = floor(input.size / ratio).toInt()
        val output = FloatArray(outputSize)
        val reach = (HALF_TAPS / cutoff).toInt()
        for (index in 0 until outputSize) {
            val centre = index * ratio
            val base = floor(centre).toInt()
            var sum = 0.0
            var weightSum = 0.0
            for (tap in base - reach + 1..base + reach) {
                if (tap < 0 || tap >= input.size) continue
                val distance = centre - tap
                val x = distance * cutoff
                val sinc = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
                val window = 0.5 + 0.5 * cos(PI * distance / reach)
                val weight = sinc * window
                sum += input[tap] * weight
                weightSum += weight
            }
            output[index] = if (weightSum == 0.0) 0f else (sum / weightSum).toFloat()
        }
        return output
    }
}
