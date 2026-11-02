package org.sonora.android.playback.automix

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A second-order filter, normalised so that a0 is 1. */
internal class Biquad(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a1: Double,
    val a2: Double
) {
    companion object {
        val IDENTITY = Biquad(1.0, 0.0, 0.0, 0.0, 0.0)
    }
}

/** History of one filter on one channel (direct form I, which tolerates coefficients that move). */
internal class BiquadState {
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun run(c: Biquad, x: Double): Double {
        val y = c.b0 * x + c.b1 * x1 + c.b2 * x2 - c.a1 * y1 - c.a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        return y
    }

    /** While a filter is skipped its output is its input: keeping the history so avoids a click on return. */
    fun pass(x: Double) {
        x2 = x1
        x1 = x
        y2 = y1
        y1 = x
    }

    fun clear() {
        x1 = 0.0
        x2 = 0.0
        y1 = 0.0
        y2 = 0.0
    }
}

/**
 * What a DJ mixer does to one deck: a fader ([gain]), the low band of the EQ ([bassDb]) and a
 * sweeping filter ([highPassHz] / [lowPassHz]). Zero means the filter is open.
 */
internal data class DjFilterSettings(
    val gain: Float = 1f,
    val bassDb: Float = 0f,
    val highPassHz: Float = 0f,
    val lowPassHz: Float = 0f
) {
    companion object {
        val NEUTRAL = DjFilterSettings()
    }
}

/** Filter design (RBJ audio EQ cookbook), apart from the audio thread so it can be tested. */
internal object DjFilterMath {
    /** Corner of the bass band, where a DJ mixer's low EQ sits. */
    const val BASS_HZ = 180.0

    /** A "kill": the low band all the way down. */
    const val BASS_KILL_DB = -30f

    /** Below this a high-pass changes nothing audible; it is skipped. */
    const val OPEN_HIGH_PASS_HZ = 20.0

    /** A low-pass this close to Nyquist changes nothing audible; it is skipped. */
    const val OPEN_LOW_PASS_FRACTION = 0.45

    private const val BUTTERWORTH_Q = 0.7071067811865476

    fun lowShelf(sampleRate: Double, freq: Double, gainDb: Double, q: Double = BUTTERWORTH_Q): Biquad {
        val a = 10.0.pow(gainDb / 40.0)
        val w = 2 * PI * freq / sampleRate
        val cosW = cos(w)
        val alpha = sin(w) / (2 * q)
        val twoRootAAlpha = 2 * sqrt(a) * alpha
        val a0 = (a + 1) + (a - 1) * cosW + twoRootAAlpha
        return normalise(
            a0,
            b0 = a * ((a + 1) - (a - 1) * cosW + twoRootAAlpha),
            b1 = 2 * a * ((a - 1) - (a + 1) * cosW),
            b2 = a * ((a + 1) - (a - 1) * cosW - twoRootAAlpha),
            a1 = -2 * ((a - 1) + (a + 1) * cosW),
            a2 = (a + 1) + (a - 1) * cosW - twoRootAAlpha
        )
    }

    fun highShelf(sampleRate: Double, freq: Double, gainDb: Double, q: Double = BUTTERWORTH_Q): Biquad {
        val a = 10.0.pow(gainDb / 40.0)
        val w = 2 * PI * freq / sampleRate
        val cosW = cos(w)
        val alpha = sin(w) / (2 * q)
        val twoRootAAlpha = 2 * sqrt(a) * alpha
        val a0 = (a + 1) - (a - 1) * cosW + twoRootAAlpha
        return normalise(
            a0,
            b0 = a * ((a + 1) + (a - 1) * cosW + twoRootAAlpha),
            b1 = -2 * a * ((a - 1) + (a + 1) * cosW),
            b2 = a * ((a + 1) + (a - 1) * cosW - twoRootAAlpha),
            a1 = 2 * ((a - 1) - (a + 1) * cosW),
            a2 = (a + 1) - (a - 1) * cosW - twoRootAAlpha
        )
    }

    fun highPass(sampleRate: Double, freq: Double, q: Double = BUTTERWORTH_Q): Biquad {
        val w = 2 * PI * freq / sampleRate
        val cosW = cos(w)
        val alpha = sin(w) / (2 * q)
        return normalise(
            a0 = 1 + alpha,
            b0 = (1 + cosW) / 2,
            b1 = -(1 + cosW),
            b2 = (1 + cosW) / 2,
            a1 = -2 * cosW,
            a2 = 1 - alpha
        )
    }

    fun lowPass(sampleRate: Double, freq: Double, q: Double = BUTTERWORTH_Q): Biquad {
        val w = 2 * PI * freq / sampleRate
        val cosW = cos(w)
        val alpha = sin(w) / (2 * q)
        return normalise(
            a0 = 1 + alpha,
            b0 = (1 - cosW) / 2,
            b1 = 1 - cosW,
            b2 = (1 - cosW) / 2,
            a1 = -2 * cosW,
            a2 = 1 - alpha
        )
    }

    /** The filter's gain at [freq], in dB. */
    fun magnitudeDb(c: Biquad, freq: Double, sampleRate: Double): Double {
        val w = 2 * PI * freq / sampleRate
        val numRe = c.b0 + c.b1 * cos(w) + c.b2 * cos(2 * w)
        val numIm = -c.b1 * sin(w) - c.b2 * sin(2 * w)
        val denRe = 1 + c.a1 * cos(w) + c.a2 * cos(2 * w)
        val denIm = -c.a1 * sin(w) - c.a2 * sin(2 * w)
        val power = (numRe * numRe + numIm * numIm) / (denRe * denRe + denIm * denIm)
        return 10 * log10(power)
    }

    fun dbToGain(db: Double): Double = 10.0.pow(db / 20.0)

    private fun normalise(a0: Double, b0: Double, b1: Double, b2: Double, a1: Double, a2: Double) =
        Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
}
