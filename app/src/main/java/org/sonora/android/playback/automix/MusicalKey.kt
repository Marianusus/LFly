package org.sonora.android.playback.automix

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A song's key: its tonic as a pitch class (0 is C) and whether it is minor. */
data class MusicalKey(val tonic: Int, val minor: Boolean) {
    /** Position on the Camelot wheel, 1 to 12: neighbours a fifth apart sit next to each other. */
    val camelotNumber: Int
        get() {
            val major = if (minor) (tonic + 3) % 12 else tonic
            return ((major * 7) % 12 + 7) % 12 + 1
        }

    /** The DJ's shorthand, as Spotify's Mix shows it: "8A" is A minor, "8B" C major. */
    val camelot: String get() = "$camelotNumber${if (minor) "A" else "B"}"

    /** The usual name, such as "Am" or "F♯". */
    val name: String get() = NAMES[tonic] + if (minor) "m" else ""

    /**
     * Steps between two keys on the Camelot wheel: 0 for the same key, 1 for a neighbour (a fifth
     * away, or the relative major or minor), which DJs treat as compatible.
     */
    fun distance(other: MusicalKey): Int {
        val diff = abs(camelotNumber - other.camelotNumber)
        val around = min(diff, 12 - diff)
        return around + if (minor != other.minor) 1 else 0
    }

    companion object {
        private val NAMES = arrayOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")
    }
}

/**
 * Finds the key of a stretch of music: a chromagram (energy per pitch class) correlated with the
 * Krumhansl-Kessler key profiles, the usual approach for a whole song section. Pure.
 */
internal object KeyDetector {
    private const val FFT_SIZE = 8_192
    private const val HOP = 4_096
    private const val MIN_HZ = 100.0
    private const val MAX_HZ = 4_000.0

    /** Below this correlation the music has no clear key (drums, noise): no answer. */
    private const val MIN_CORRELATION = 0.5

    private val MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

    /** [samples] mono at [sampleRate]. Null when there is too little audio or no clear key. */
    fun detect(samples: FloatArray, sampleRate: Int): MusicalKey? {
        val chroma = chroma(samples, sampleRate) ?: return null
        var best: MusicalKey? = null
        var bestScore = -1.0
        for (tonic in 0 until 12) {
            for (minor in listOf(false, true)) {
                val profile = if (minor) MINOR else MAJOR
                val score = correlation(chroma) { profile[(it - tonic + 12) % 12] }
                if (score > bestScore) {
                    bestScore = score
                    best = MusicalKey(tonic, minor)
                }
            }
        }
        return best.takeIf { bestScore >= MIN_CORRELATION }
    }

    /** Energy per pitch class over the whole stretch, each frame weighted the same so loud bars do not decide alone. */
    internal fun chroma(samples: FloatArray, sampleRate: Int): DoubleArray? {
        if (samples.size < FFT_SIZE || sampleRate <= 0) return null
        val window = DoubleArray(FFT_SIZE) { 0.5 - 0.5 * cos(2 * PI * it / FFT_SIZE) }
        val pitchClass = IntArray(FFT_SIZE / 2) { bin ->
            val hz = bin.toDouble() * sampleRate / FFT_SIZE
            if (hz < MIN_HZ || hz > MAX_HZ) -1
            else ((69 + 12 * ln(hz / 440.0) / ln(2.0)).roundToInt() % 12 + 12) % 12
        }
        val fft = Fft(FFT_SIZE)
        val real = DoubleArray(FFT_SIZE)
        val imaginary = DoubleArray(FFT_SIZE)
        val total = DoubleArray(12)
        var frames = 0
        var start = 0
        while (start + FFT_SIZE <= samples.size) {
            for (index in 0 until FFT_SIZE) {
                real[index] = samples[start + index] * window[index]
                imaginary[index] = 0.0
            }
            fft.transform(real, imaginary)
            val frame = DoubleArray(12)
            for (bin in 1 until FFT_SIZE / 2) {
                val pc = pitchClass[bin]
                if (pc < 0) continue
                frame[pc] += sqrt(real[bin] * real[bin] + imaginary[bin] * imaginary[bin])
            }
            val sum = frame.sum()
            if (sum > 1e-6) {
                for (pc in 0 until 12) total[pc] += frame[pc] / sum
                frames++
            }
            start += HOP
        }
        return if (frames == 0) null else total
    }

    private fun correlation(chroma: DoubleArray, profile: (Int) -> Double): Double {
        val meanC = chroma.average()
        val meanP = (0 until 12).sumOf(profile) / 12
        var covariance = 0.0
        var varC = 0.0
        var varP = 0.0
        for (pc in 0 until 12) {
            val c = chroma[pc] - meanC
            val p = profile(pc) - meanP
            covariance += c * p
            varC += c * c
            varP += p * p
        }
        return if (varC <= 0.0 || varP <= 0.0) 0.0 else covariance / sqrt(varC * varP)
    }

    /** Iterative radix-2 FFT of a fixed size. */
    private class Fft(private val size: Int) {
        private val reversed = IntArray(size).also { table ->
            val bits = Integer.numberOfTrailingZeros(size)
            for (index in 0 until size) table[index] = Integer.reverse(index) ushr (32 - bits)
        }
        private val cosTable = DoubleArray(size / 2) { cos(-2 * PI * it / size) }
        private val sinTable = DoubleArray(size / 2) { sin(-2 * PI * it / size) }

        fun transform(real: DoubleArray, imaginary: DoubleArray) {
            for (index in 0 until size) {
                val target = reversed[index]
                if (index < target) {
                    val r = real[index]; real[index] = real[target]; real[target] = r
                    val i = imaginary[index]; imaginary[index] = imaginary[target]; imaginary[target] = i
                }
            }
            var length = 2
            while (length <= size) {
                val half = length / 2
                val step = size / length
                var start = 0
                while (start < size) {
                    for (offset in 0 until half) {
                        val wr = cosTable[offset * step]
                        val wi = sinTable[offset * step]
                        val odd = start + offset + half
                        val even = start + offset
                        val oddReal = real[odd] * wr - imaginary[odd] * wi
                        val oddImaginary = real[odd] * wi + imaginary[odd] * wr
                        real[odd] = real[even] - oddReal
                        imaginary[odd] = imaginary[even] - oddImaginary
                        real[even] += oddReal
                        imaginary[even] += oddImaginary
                    }
                    start += length
                }
                length *= 2
            }
        }
    }
}

/** A song stretch's loudness over time, for the transition editor to draw. */
class Waveform(
    /** Where the first point is, in seconds on the song's timeline. */
    val startSeconds: Double,
    /** Points per second. */
    val rate: Double,
    /** Level of each point, 0..1 (relative to the stretch's loudest point). */
    val levels: FloatArray
) {
    val endSeconds: Double get() = startSeconds + levels.size / rate

    companion object {
        const val RATE = 20.0

        fun compute(samples: FloatArray, sampleRate: Int, startSeconds: Double): Waveform? {
            if (samples.isEmpty() || sampleRate <= 0) return null
            val slot = (sampleRate / RATE).toInt().coerceAtLeast(1)
            val count = samples.size / slot
            if (count == 0) return null
            val levels = FloatArray(count) { index ->
                var sum = 0.0
                for (i in index * slot until (index + 1) * slot) sum += samples[i] * samples[i]
                sqrt(sum / slot).toFloat()
            }
            val loudest = levels.maxOrNull()?.takeIf { it > 1e-6f } ?: return null
            for (index in levels.indices) levels[index] = (levels[index] / loudest).coerceIn(0f, 1f)
            return Waveform(startSeconds, sampleRate.toDouble() / slot, levels)
        }
    }
}
