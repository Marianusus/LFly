package org.sonora.android.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** What the equalizer should sound like; read by every player's [EqualizerProcessor]. */
internal class EqualizerCurve(
    val enabled: Boolean,
    /** Gains of the five bands in dB, lowest band first. */
    val bandsDb: FloatArray,
    /** Bass boost, 0..1000 as the slider gives it. */
    val bassBoost: Int
) {
    val bassDb: Float get() = bassBoost.coerceIn(0, 1000) / 1000f * MAX_BASS_DB

    /** Nothing to do: the audio passes through untouched, bit for bit. */
    val isFlat: Boolean get() = !enabled || (bandsDb.all { abs(it) < 0.05f } && bassBoost <= 0)

    companion object {
        const val MAX_BASS_DB = 12f

        val FLAT = EqualizerCurve(enabled = false, bandsDb = FloatArray(5), bassBoost = 0)

        /** The curve in use, set by [EqualizerManager]. */
        @Volatile
        var current: EqualizerCurve = FLAT
    }
}

/**
 * The five-band equalizer and bass boost inside the player's own audio chain.
 *
 * The platform's equalizer effect depends on the phone: on many (Samsung's among them) it is
 * overridden by the system's sound effects, and it never reaches a USB DAC played bit-perfect. This
 * one shapes the decoded audio before it leaves the app, so it sounds the same on every output.
 * Flat, it copies the audio through untouched.
 */
@OptIn(UnstableApi::class)
internal class EqualizerProcessor : BaseAudioProcessor() {

    private companion object {
        /** Center frequency of each band; the top one is a shelf, so it is placed lower. */
        val FREQS = doubleArrayOf(60.0, 230.0, 910.0, 3600.0, 10_000.0)
        const val Q = 1.0
        const val BASS_HZ = 90.0

        /** Gains glide this many dB per buffer, so moving a slider never clicks. */
        const val GLIDE_DB = 1.0

        /** Part of the loudest boost taken off up front; the soft limiter catches the rest. */
        const val HEADROOM_SHARE = 0.6

        /** Where the limiter starts to round the peaks off, and the most it lets out. */
        const val KNEE = 0.89
        const val CEILING = 0.98
    }

    private var sampleRate = 44_100.0
    private var channels = 2

    /** Gains the filters are set to now (5 bands, then bass, then preamp), gliding to the curve's. */
    private val now = DoubleArray(7)
    private var filters: Array<Array<Biquad>> = emptyArray()
    private var bypassed = true

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun onFlush() {
        sampleRate = inputAudioFormat.sampleRate.toDouble().coerceAtLeast(8_000.0)
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        // Six filters per channel: the five bands and the bass boost.
        filters = Array(channels) { Array(6) { Biquad() } }
        val target = targetOf(EqualizerCurve.current)
        target.copyInto(now)
        bypassed = EqualizerCurve.current.isFlat
        design()
    }

    override fun onReset() {
        filters = emptyArray()
        now.fill(0.0)
        bypassed = true
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val output = replaceOutputBuffer(bytes)
        val curve = EqualizerCurve.current
        val target = targetOf(curve)

        if (curve.isFlat && bypassed) {
            output.put(inputBuffer)
            output.flip()
            return
        }
        if (bypassed) {
            // Coming back from rest: start from flat and glide in.
            now.fill(0.0)
            filters.forEach { channel -> channel.forEach(Biquad::clear) }
            bypassed = false
        }
        if (glide(target)) design()

        val float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val frameBytes = channels * (if (float) 4 else 2)
        val frames = bytes / frameBytes
        val preamp = 10.0.pow(now[6] / 20.0)
        for (frame in 0 until frames) {
            for (ch in 0 until channels) {
                var x = if (float) inputBuffer.float.toDouble() else inputBuffer.short / 32_768.0
                val chain = filters[ch]
                x *= preamp
                for (f in chain.indices) x = chain[f].process(x)
                x = limit(x)
                if (float) output.putFloat(x.toFloat())
                else output.putShort((x * 32_768.0).toInt().coerceIn(-32_768, 32_767).toShort())
            }
        }
        // A partial frame at the end (never expected) is dropped rather than misread.
        inputBuffer.position(inputBuffer.limit())
        output.flip()

        // Settled at flat again: from the next buffer on, pass through untouched.
        if (curve.isFlat && now.all { abs(it) < 1e-3 }) bypassed = true
    }

    /** Band gains, bass and preamp the curve asks for; all zero when it is off. */
    private fun targetOf(curve: EqualizerCurve): DoubleArray {
        val out = DoubleArray(7)
        if (curve.isFlat) return out
        for (i in 0 until 5) out[i] = curve.bandsDb.getOrElse(i) { 0f }.toDouble()
        out[5] = curve.bassDb.toDouble()
        // The loudest the curve can get: the biggest band boost, with the bass shelf on top of the low band.
        val peak = max(out.take(5).max(), max(0.0, out[0]) + out[5]).coerceAtLeast(0.0)
        out[6] = -peak * HEADROOM_SHARE
        return out
    }

    /** Moves the gains a step toward [target]; true when anything changed. */
    private fun glide(target: DoubleArray): Boolean {
        var moved = false
        for (i in now.indices) {
            val diff = target[i] - now[i]
            if (abs(diff) < 1e-4) continue
            now[i] += diff.coerceIn(-GLIDE_DB, GLIDE_DB)
            moved = true
        }
        return moved
    }

    private fun design() {
        for (channel in filters) {
            for (b in 0 until 4) channel[b].peaking(FREQS[b], Q, now[b], sampleRate)
            channel[4].highShelf(FREQS[4], now[4], sampleRate)
            channel[5].lowShelf(BASS_HZ, now[5], sampleRate)
        }
    }

    /** Leaves everything under the knee alone and rounds off what goes past it, instead of clipping. */
    private fun limit(x: Double): Double {
        val a = abs(x)
        if (a <= KNEE) return x
        val room = CEILING - KNEE
        val shaped = KNEE + room * tanh((a - KNEE) / room)
        return if (x < 0) -shaped else shaped
    }
}

/** One second-order filter (RBJ cookbook), transposed direct form II. */
internal class Biquad {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var z1 = 0.0
    private var z2 = 0.0

    fun clear() {
        z1 = 0.0
        z2 = 0.0
    }

    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    fun peaking(freq: Double, q: Double, gainDb: Double, rate: Double) {
        if (abs(gainDb) < 1e-4) return passThrough()
        val a = 10.0.pow(gainDb / 40.0)
        val w = 2.0 * PI * safe(freq, rate) / rate
        val alpha = sin(w) / (2.0 * q)
        val c = cos(w)
        set(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
    }

    fun lowShelf(freq: Double, gainDb: Double, rate: Double) {
        if (abs(gainDb) < 1e-4) return passThrough()
        val a = 10.0.pow(gainDb / 40.0)
        val w = 2.0 * PI * safe(freq, rate) / rate
        val c = cos(w)
        val alpha = sin(w) / 2.0 * sqrt(2.0)
        val s = 2.0 * sqrt(a) * alpha
        set(
            a * ((a + 1) - (a - 1) * c + s), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - s),
            (a + 1) + (a - 1) * c + s, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - s
        )
    }

    fun highShelf(freq: Double, gainDb: Double, rate: Double) {
        if (abs(gainDb) < 1e-4) return passThrough()
        val a = 10.0.pow(gainDb / 40.0)
        val w = 2.0 * PI * safe(freq, rate) / rate
        val c = cos(w)
        val alpha = sin(w) / 2.0 * sqrt(2.0)
        val s = 2.0 * sqrt(a) * alpha
        set(
            a * ((a + 1) + (a - 1) * c + s), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - s),
            (a + 1) - (a - 1) * c + s, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - s
        )
    }

    private fun passThrough() = set(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

    /** Keeps a band under Nyquist at low sample rates. */
    private fun safe(freq: Double, rate: Double) = freq.coerceAtMost(rate * 0.45)

    private fun set(nb0: Double, nb1: Double, nb2: Double, na0: Double, na1: Double, na2: Double) {
        b0 = nb0 / na0
        b1 = nb1 / na0
        b2 = nb2 / na0
        a1 = na1 / na0
        a2 = na2 / na0
    }
}
