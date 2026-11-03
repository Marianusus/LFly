package org.sonora.android.playback.automix

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * One deck of a DJ mixer inside ExoPlayer's audio chain: gain, a three-band EQ, and a high-pass and a
 * low-pass with resonance.
 * Settings come from any thread through [set] and are glided to over a few tens of milliseconds,
 * so moving them never clicks. At rest it copies the audio through untouched.
 */
@OptIn(UnstableApi::class)
internal class DjFilterProcessor : BaseAudioProcessor() {

    private companion object {
        /** Coefficients are recomputed once per block while settings move. */
        const val BLOCK_FRAMES = 32
        const val GLIDE_SECONDS = 0.03
        const val SNAP = 1e-3
        const val MAX_Q = 4.0
    }

    @Volatile
    private var target = DjFilterSettings.NEUTRAL

    fun set(settings: DjFilterSettings) {
        target = settings
    }

    fun setNeutral() = set(DjFilterSettings.NEUTRAL)

    // Current (glided) values. High-pass and low-pass are kept in log frequency so sweeps sound even.
    private var gain = 1.0
    private var bassDb = 0.0
    private var midDb = 0.0
    private var highDb = 0.0
    private var q = DjFilterMath.FLAT_Q
    private var logHighPass = 0.0
    private var logLowPass = 0.0

    private var sampleRate = 0.0
    private var channels = 0
    private var shelf = emptyArray<BiquadState>()
    private var mid = emptyArray<BiquadState>()
    private var high = emptyArray<BiquadState>()
    private var highPass = emptyArray<BiquadState>()
    private var lowPass = emptyArray<BiquadState>()

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun onFlush() {
        sampleRate = inputAudioFormat.sampleRate.toDouble()
        channels = inputAudioFormat.channelCount
        if (shelf.size != channels) {
            shelf = Array(channels) { BiquadState() }
            mid = Array(channels) { BiquadState() }
            high = Array(channels) { BiquadState() }
            highPass = Array(channels) { BiquadState() }
            lowPass = Array(channels) { BiquadState() }
        }
        (shelf + mid + high + highPass + lowPass).forEach(BiquadState::clear)
        val settled = target
        gain = settled.gain.toDouble()
        bassDb = settled.bassDb.toDouble()
        midDb = settled.midDb.toDouble()
        highDb = settled.highDb.toDouble()
        q = qOf(settled)
        logHighPass = ln(highPassOf(settled))
        logLowPass = ln(lowPassOf(settled))
    }

    override fun onReset() {
        target = DjFilterSettings.NEUTRAL
        shelf = emptyArray()
        mid = emptyArray()
        high = emptyArray()
        highPass = emptyArray()
        lowPass = emptyArray()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val output = replaceOutputBuffer(bytes)
        val float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val frameBytes = channels * (if (float) 4 else 2)
        val frames = bytes / frameBytes
        val want = target

        if (isSettled(want)) {
            // Nothing to do but keep each filter's history, so the next move starts clean.
            for (frame in 0 until frames) {
                for (ch in 0 until channels) {
                    val x = read(inputBuffer, float)
                    shelf[ch].pass(x)
                    mid[ch].pass(x)
                    high[ch].pass(x)
                    highPass[ch].pass(x)
                    lowPass[ch].pass(x)
                    write(output, float, x)
                }
            }
        } else {
            process(inputBuffer, output, float, frames, want)
        }
        // A partial frame (never expected) is passed on as it is.
        while (inputBuffer.hasRemaining()) output.put(inputBuffer.get())
        output.flip()
    }

    private fun process(input: ByteBuffer, output: ByteBuffer, float: Boolean, frames: Int, want: DjFilterSettings) {
        val step = 1 - exp(-BLOCK_FRAMES / (GLIDE_SECONDS * sampleRate))
        val wantHighPass = ln(highPassOf(want))
        val wantLowPass = ln(lowPassOf(want))
        val openHighPass = ln(DjFilterMath.OPEN_HIGH_PASS_HZ)
        val openLowPass = ln(DjFilterMath.OPEN_LOW_PASS_FRACTION * sampleRate)
        var done = 0
        while (done < frames) {
            val count = minOf(BLOCK_FRAMES, frames - done)
            val startGain = gain
            gain = glide(gain, want.gain.toDouble(), step)
            bassDb = glide(bassDb, want.bassDb.toDouble(), step)
            midDb = glide(midDb, want.midDb.toDouble(), step)
            highDb = glide(highDb, want.highDb.toDouble(), step)
            q = glide(q, qOf(want), step)
            logHighPass = glide(logHighPass, wantHighPass, step)
            logLowPass = glide(logLowPass, wantLowPass, step)

            val shelfOn = abs(bassDb) > SNAP
            val midOn = abs(midDb) > SNAP
            val highOn = abs(highDb) > SNAP
            val highPassOn = logHighPass > openHighPass + SNAP
            val lowPassOn = logLowPass < openLowPass - SNAP
            val shelfC = if (shelfOn) DjFilterMath.lowShelf(sampleRate, DjFilterMath.BASS_HZ, bassDb) else Biquad.IDENTITY
            val midC = if (midOn) DjFilterMath.peaking(sampleRate, DjFilterMath.MID_HZ, midDb) else Biquad.IDENTITY
            val highC = if (highOn) DjFilterMath.highShelf(sampleRate, DjFilterMath.HIGH_HZ, highDb) else Biquad.IDENTITY
            val highPassC = if (highPassOn) DjFilterMath.highPass(sampleRate, exp(logHighPass), q) else Biquad.IDENTITY
            val lowPassC = if (lowPassOn) DjFilterMath.lowPass(sampleRate, exp(logLowPass), q) else Biquad.IDENTITY

            for (i in 0 until count) {
                val g = startGain + (gain - startGain) * (i + 1) / count
                for (ch in 0 until channels) {
                    var x = read(input, float)
                    if (shelfOn) x = shelf[ch].run(shelfC, x) else shelf[ch].pass(x)
                    if (midOn) x = mid[ch].run(midC, x) else mid[ch].pass(x)
                    if (highOn) x = high[ch].run(highC, x) else high[ch].pass(x)
                    if (highPassOn) x = highPass[ch].run(highPassC, x) else highPass[ch].pass(x)
                    if (lowPassOn) x = lowPass[ch].run(lowPassC, x) else lowPass[ch].pass(x)
                    write(output, float, x * g)
                }
            }
            done += count
        }
    }

    private fun isSettled(want: DjFilterSettings): Boolean {
        if (want != DjFilterSettings.NEUTRAL) return false
        if (abs(gain - 1.0) > SNAP || abs(bassDb) > SNAP || abs(midDb) > SNAP || abs(highDb) > SNAP) return false
        if (logHighPass > ln(DjFilterMath.OPEN_HIGH_PASS_HZ) + SNAP) return false
        if (logLowPass < ln(DjFilterMath.OPEN_LOW_PASS_FRACTION * sampleRate) - SNAP) return false
        gain = 1.0
        bassDb = 0.0
        midDb = 0.0
        highDb = 0.0
        q = DjFilterMath.FLAT_Q
        return true
    }

    private fun glide(current: Double, wanted: Double, step: Double): Double {
        val next = current + (wanted - current) * step
        return if (abs(wanted - next) < SNAP) wanted else next
    }

    /** Resonance stays where a sweep can sing without whistling or going unstable. */
    private fun qOf(s: DjFilterSettings): Double = s.resonance.toDouble().coerceIn(DjFilterMath.FLAT_Q, MAX_Q)

    /** An open high-pass sits at its open frequency, so a sweep starts from where it is inaudible. */
    private fun highPassOf(s: DjFilterSettings): Double =
        s.highPassHz.toDouble().coerceIn(DjFilterMath.OPEN_HIGH_PASS_HZ, DjFilterMath.OPEN_LOW_PASS_FRACTION * sampleRate)

    private fun lowPassOf(s: DjFilterSettings): Double {
        val open = DjFilterMath.OPEN_LOW_PASS_FRACTION * sampleRate
        return if (s.lowPassHz <= 0f) open else s.lowPassHz.toDouble().coerceIn(DjFilterMath.OPEN_HIGH_PASS_HZ, open)
    }

    private fun read(buffer: ByteBuffer, float: Boolean): Double =
        if (float) buffer.getFloat().toDouble() else buffer.getShort() / 32_768.0

    private fun write(buffer: ByteBuffer, float: Boolean, value: Double) {
        if (float) {
            buffer.putFloat(value.coerceIn(-1.0, 1.0).toFloat())
        } else {
            buffer.putShort((value * 32_768.0).toInt().coerceIn(-32_768, 32_767).toShort())
        }
    }
}

/** Builds players whose audio passes through a [DjFilterProcessor] before the speed changer. */
@OptIn(UnstableApi::class)
internal object DjAudio {
    /** The deck of the main player, the one the session knows. Null while the service is down. */
    @Volatile
    var main: DjFilterProcessor? = null

    /**
     * Tempo changes go to the platform's AudioTrack, which stretches time as it plays: ExoPlayer's own
     * stretcher would drain its buffer on every change and leave a tiny gap, audible when Automix
     * nudges the tempo during a mix or eases a song back to its own tempo.
     */
    fun renderersFactory(context: Context, processor: DjFilterProcessor): RenderersFactory =
        (object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf<AudioProcessor>(processor))
                .build()
        }).setEnableAudioTrackPlaybackParams(true)
}
