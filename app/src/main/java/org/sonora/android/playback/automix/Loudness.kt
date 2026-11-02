package org.sonora.android.playback.automix

import kotlin.math.log10
import kotlin.math.pow

/**
 * How loud a stretch of music sounds, after ITU-R BS.1770 (K-weighting, 400 ms blocks, absolute and
 * relative gates). Measured on the mono analysis audio, so only differences between songs matter.
 */
internal object Loudness {
    private const val BLOCK_SECONDS = 0.4
    private const val STEP_SECONDS = 0.1
    private const val ABSOLUTE_GATE = -70.0
    private const val RELATIVE_GATE = -10.0

    /** An Automix never turns the outgoing song down more than this ... */
    const val MAX_CUT_DB = -6.0

    /** ... nor up more than this, to stay clear of clipping. */
    const val MAX_BOOST_DB = 4.0

    /** Loudness in LUFS (mono), or null when there is too little or only silent audio. */
    fun lufs(samples: FloatArray, sampleRate: Int): Double? {
        val block = (BLOCK_SECONDS * sampleRate).toInt()
        val step = (STEP_SECONDS * sampleRate).toInt()
        if (samples.size < block || step <= 0) return null

        val rate = sampleRate.toDouble()
        val shelf = DjFilterMath.highShelf(rate, 1_681.97, 4.0)
        val highPass = DjFilterMath.highPass(rate, 38.13, 0.5)
        val shelfState = BiquadState()
        val highPassState = BiquadState()
        val squares = DoubleArray(samples.size) {
            val y = highPassState.run(highPass, shelfState.run(shelf, samples[it].toDouble()))
            y * y
        }

        // Prefix sums make every overlapping block a subtraction.
        val sums = DoubleArray(squares.size + 1)
        for (i in squares.indices) sums[i + 1] = sums[i] + squares[i]
        val powers = ArrayList<Double>()
        var start = 0
        while (start + block <= squares.size) {
            powers += (sums[start + block] - sums[start]) / block
            start += step
        }

        val loud = powers.filter { toLufs(it) > ABSOLUTE_GATE }
        if (loud.isEmpty()) return null
        val gate = toLufs(loud.average()) + RELATIVE_GATE
        val gated = loud.filter { toLufs(it) > gate }
        return toLufs(gated.average())
    }

    /** Gain, in dB, that brings the outgoing song to the incoming song's level. */
    fun matchGainDb(outgoingLufs: Double?, incomingLufs: Double?): Double {
        if (outgoingLufs == null || incomingLufs == null) return 0.0
        return (incomingLufs - outgoingLufs).coerceIn(MAX_CUT_DB, MAX_BOOST_DB)
    }

    fun gain(db: Double): Float = 10.0.pow(db / 20.0).toFloat()

    private fun toLufs(meanSquare: Double): Double = -0.691 + 10 * log10(meanSquare.coerceAtLeast(1e-12))
}
