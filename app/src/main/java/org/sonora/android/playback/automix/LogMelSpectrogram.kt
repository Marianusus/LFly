package org.sonora.android.playback.automix

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The input Beat This! was trained on, written from its published preprocessing (CPJKU/beat_this,
 * `LogMelSpect`): mono 22 050 Hz, a centred and reflect-padded STFT (n_fft 1024, hop 441, periodic
 * Hann, `normalized` so the transform is divided by sqrt(n_fft)), magnitude, 128 Slaney mel bands
 * from 30 Hz to 11 kHz with unit-peak triangles, then log1p(1000 · x). Any difference here shifts
 * the model's input and quietly costs beats, so none of it is a taste setting.
 */
internal object LogMelSpectrogram {
    const val SAMPLE_RATE = 22_050
    const val FFT_SIZE = 1_024
    const val HOP = 441
    const val MELS = 128

    /** Frames per second: one every 20 ms. */
    const val FRAME_RATE = SAMPLE_RATE.toDouble() / HOP

    private const val MIN_HZ = 30.0
    private const val MAX_HZ = 11_000.0
    private const val LOG_MULTIPLIER = 1_000.0
    private const val BINS = FFT_SIZE / 2 + 1

    /** Row-major: frame f, band m is at `f * MELS + m`. */
    class Spectrogram(val frames: Int, val values: FloatArray)

    private class Filter(val firstBin: Int, val weights: DoubleArray)

    private val window = DoubleArray(FFT_SIZE) { 0.5 - 0.5 * cos(2 * PI * it / FFT_SIZE) }
    private val filters: Array<Filter> by lazy { melFilters() }
    private val bitReversed: IntArray by lazy { bitReversal(FFT_SIZE) }
    private val cosTable = DoubleArray(FFT_SIZE / 2) { cos(-2 * PI * it / FFT_SIZE) }
    private val sinTable = DoubleArray(FFT_SIZE / 2) { sin(-2 * PI * it / FFT_SIZE) }

    /** [samples] must already be mono at [SAMPLE_RATE]. Null when there is too little audio. */
    fun compute(samples: FloatArray): Spectrogram? {
        val pad = FFT_SIZE / 2
        val count = samples.size
        if (count <= pad + 1) return null

        // torch.stft(center=True, pad_mode="reflect"): frame f is centred on sample f * HOP.
        val padded = FloatArray(count + 2 * pad)
        for (index in 0 until pad) padded[index] = samples[pad - index]
        System.arraycopy(samples, 0, padded, pad, count)
        for (index in 0 until pad) padded[pad + count + index] = samples[count - 2 - index]

        val frames = (padded.size - FFT_SIZE) / HOP + 1
        val values = FloatArray(frames * MELS)
        val real = DoubleArray(FFT_SIZE)
        val imaginary = DoubleArray(FFT_SIZE)
        val magnitude = DoubleArray(BINS)
        val normalization = sqrt(FFT_SIZE.toDouble())
        val bands = filters

        for (frame in 0 until frames) {
            val start = frame * HOP
            for (index in 0 until FFT_SIZE) {
                real[index] = padded[start + index] * window[index]
                imaginary[index] = 0.0
            }
            fft(real, imaginary)
            for (bin in 0 until BINS) {
                magnitude[bin] = sqrt(real[bin] * real[bin] + imaginary[bin] * imaginary[bin]) / normalization
            }
            val row = frame * MELS
            for (mel in 0 until MELS) {
                val filter = bands[mel]
                var energy = 0.0
                for (offset in filter.weights.indices) {
                    energy += magnitude[filter.firstBin + offset] * filter.weights[offset]
                }
                values[row + mel] = ln1p(LOG_MULTIPLIER * energy).toFloat()
            }
        }
        return Spectrogram(frames, values)
    }

    /** In-place iterative radix-2 FFT. */
    private fun fft(real: DoubleArray, imaginary: DoubleArray) {
        val reversed = bitReversed
        for (index in 0 until FFT_SIZE) {
            val target = reversed[index]
            if (index < target) {
                val r = real[index]; real[index] = real[target]; real[target] = r
                val i = imaginary[index]; imaginary[index] = imaginary[target]; imaginary[target] = i
            }
        }
        var length = 2
        while (length <= FFT_SIZE) {
            val half = length / 2
            val step = FFT_SIZE / length
            var start = 0
            while (start < FFT_SIZE) {
                for (offset in 0 until half) {
                    val wr = cosTable[offset * step]
                    val wi = sinTable[offset * step]
                    val oddIndex = start + offset + half
                    val evenIndex = start + offset
                    val oddReal = real[oddIndex] * wr - imaginary[oddIndex] * wi
                    val oddImaginary = real[oddIndex] * wi + imaginary[oddIndex] * wr
                    real[oddIndex] = real[evenIndex] - oddReal
                    imaginary[oddIndex] = imaginary[evenIndex] - oddImaginary
                    real[evenIndex] += oddReal
                    imaginary[evenIndex] += oddImaginary
                }
                start += length
            }
            length *= 2
        }
    }

    private fun bitReversal(size: Int): IntArray {
        val bits = Integer.numberOfTrailingZeros(size)
        return IntArray(size) { Integer.reverse(it) ushr (32 - bits) }
    }

    /** torchaudio's `melscale_fbanks(norm=None, mel_scale="slaney")`, stored sparsely. */
    private fun melFilters(): Array<Filter> {
        val melMin = hzToMel(MIN_HZ)
        val melMax = hzToMel(MAX_HZ)
        val edges = DoubleArray(MELS + 2) { melToHz(melMin + (melMax - melMin) * it / (MELS + 1)) }
        return Array(MELS) { mel ->
            val left = edges[mel]
            val centre = edges[mel + 1]
            val right = edges[mel + 2]
            val weights = DoubleArray(BINS) { bin ->
                val hz = bin.toDouble() * SAMPLE_RATE / FFT_SIZE
                val rising = (hz - left) / (centre - left)
                val falling = (right - hz) / (right - centre)
                maxOf(0.0, minOf(rising, falling))
            }
            val first = weights.indexOfFirst { it > 0.0 }.coerceAtLeast(0)
            val last = weights.indexOfLast { it > 0.0 }.coerceAtLeast(first)
            Filter(first, weights.copyOfRange(first, last + 1))
        }
    }

    private const val SLANEY_STEP_HZ = 200.0 / 3.0
    private const val SLANEY_LOG_START_HZ = 1_000.0
    private val slaneyLogStep = ln(6.4) / 27.0

    internal fun hzToMel(hz: Double): Double {
        val logStartMel = SLANEY_LOG_START_HZ / SLANEY_STEP_HZ
        return if (hz >= SLANEY_LOG_START_HZ) {
            logStartMel + ln(hz / SLANEY_LOG_START_HZ) / slaneyLogStep
        } else {
            hz / SLANEY_STEP_HZ
        }
    }

    internal fun melToHz(mel: Double): Double {
        val logStartMel = SLANEY_LOG_START_HZ / SLANEY_STEP_HZ
        return if (mel >= logStartMel) {
            SLANEY_LOG_START_HZ * exp(slaneyLogStep * (mel - logStartMel))
        } else {
            SLANEY_STEP_HZ * mel
        }
    }
}
