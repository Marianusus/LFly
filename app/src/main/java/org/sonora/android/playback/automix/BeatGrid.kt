package org.sonora.android.playback.automix

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor

/**
 * Beats and downbeats of a stretch of a song, in seconds on the song's own timeline.
 *
 * The phase functions extrapolate with the tempo outside the tracked beats, so a mix can keep
 * counting through a breakdown or past the end of the analysed window.
 */
internal class BeatGrid(
    val beats: DoubleArray,
    val downbeats: DoubleArray,
    val bpm: Double,
    /** 0..1: how regular the beats are and how decisive the model was. */
    val confidence: Double
) {
    val beatSeconds: Double get() = 60.0 / bpm

    /** Beats are counted in 4/4 when the model found downbeats; otherwise only the beat lines up. */
    val beatsPerBar: Int get() = if (downbeats.size >= 2) 4 else 1

    /** A continuous beat count: whole at every tracked beat, linear between beats. */
    fun beatPosition(time: Double): Double {
        val first = beats.first()
        val last = beats.last()
        if (time <= first) return (time - first) / beatSeconds
        if (time >= last) return (beats.size - 1) + (time - last) / beatSeconds
        var low = 0
        var high = beats.size - 1
        while (high - low > 1) {
            val middle = (low + high) ushr 1
            if (beats[middle] <= time) low = middle else high = middle
        }
        return low + (time - beats[low]) / (beats[high] - beats[low])
    }

    /** Where [time] falls inside its bar, in beats: 0 on a downbeat, up to [beatsPerBar]. */
    fun barPhase(time: Double): Double {
        val position = beatPosition(time)
        if (beatsPerBar == 1) return position - floor(position)
        val anchor = beatPosition(downbeats.first())
        return floorMod(position - anchor, beatsPerBar.toDouble())
    }

    companion object {
        /** A frame is a beat when it tops a seven-frame window and the model leans towards yes. */
        private const val PEAK_WINDOW = 7
        const val MIN_BEATS = 8
        private const val MIN_BPM = 50.0
        private const val MAX_BPM = 210.0

        /**
         * Builds a grid from the model's per-frame logits. [offsetSeconds] is where the analysed
         * audio starts in the song. Null when the model found no usable pulse.
         */
        fun fromLogits(
            beatLogits: FloatArray,
            downbeatLogits: FloatArray,
            frameRate: Double,
            offsetSeconds: Double
        ): BeatGrid? {
            val beatFrames = pickPeaks(beatLogits)
            if (beatFrames.size < MIN_BEATS) return null
            val beats = DoubleArray(beatFrames.size) { beatFrames[it] / frameRate + offsetSeconds }
            val bpm = tempo(beats)
            if (bpm <= 0.0) return null
            // The two heads peak independently; every downbeat is snapped onto its nearest beat.
            val downbeats = pickPeaks(downbeatLogits)
                .map { frame -> nearest(beats, frame / frameRate + offsetSeconds) }
                .distinct()
                .sorted()
                .toDoubleArray()
            val strengths = beatFrames.map { beatLogits[it.toInt().coerceIn(0, beatLogits.size - 1)].toDouble() }
            return BeatGrid(beats, downbeats, bpm, confidence(beats, strengths))
        }

        /**
         * Positive local maxima over [PEAK_WINDOW] frames, ties merged, refined between frames by a
         * parabola through the peak and its neighbours (a 20 ms frame is coarse for beat matching).
         */
        fun pickPeaks(logits: FloatArray): List<Double> {
            val half = PEAK_WINDOW / 2
            val peaks = ArrayList<Int>()
            for (index in logits.indices) {
                val value = logits[index]
                if (value <= 0f) continue
                var isMaximum = true
                for (neighbour in maxOf(0, index - half)..minOf(logits.size - 1, index + half)) {
                    if (logits[neighbour] > value) {
                        isMaximum = false
                        break
                    }
                }
                if (isMaximum) peaks += index
            }
            val merged = ArrayList<Int>()
            var index = 0
            while (index < peaks.size) {
                var sum = peaks[index]
                var count = 1
                while (index + 1 < peaks.size && peaks[index + 1] - peaks[index] <= 1) {
                    index++
                    sum += peaks[index]
                    count++
                }
                merged += Math.round(sum.toDouble() / count).toInt()
                index++
            }
            return merged.map { frame ->
                if (frame <= 0 || frame >= logits.size - 1) return@map frame.toDouble()
                val left = logits[frame - 1].toDouble()
                val centre = logits[frame].toDouble()
                val right = logits[frame + 1].toDouble()
                val curvature = left - 2 * centre + right
                if (abs(curvature) < 1e-9) frame.toDouble()
                else frame + (0.5 * (left - right) / curvature).coerceIn(-0.5, 0.5)
            }
        }

        /**
         * The tempo, fitted over the whole stretch: each beat is numbered by counting median gaps
         * between neighbours (so a dropped beat keeps the count right), then a least-squares line
         * through number and time gives the interval. Over thirty seconds this is far finer than any
         * single gap, which matters when two songs must stay together for a whole mix.
         */
        fun tempo(beats: DoubleArray): Double {
            if (beats.size < MIN_BEATS) return 0.0
            val gaps = (1 until beats.size).map { beats[it] - beats[it - 1] }
            val rough = median(gaps)
            if (rough <= 0.0) return 0.0
            // Gap by gap, so the small bias of the median cannot add up over sixty beats.
            val numbers = DoubleArray(beats.size)
            for (index in 1 until beats.size) {
                val step = Math.round(gaps[index - 1] / rough).coerceAtLeast(1L)
                numbers[index] = numbers[index - 1] + step
            }
            val meanNumber = numbers.average()
            val meanTime = beats.average()
            var covariance = 0.0
            var variance = 0.0
            for (index in beats.indices) {
                val dn = numbers[index] - meanNumber
                covariance += dn * (beats[index] - meanTime)
                variance += dn * dn
            }
            val interval = if (variance > 0.0) covariance / variance else rough
            // A fit far from the median gap means the numbering went wrong: trust the median.
            val chosen = if (interval > 0.0 && abs(interval - rough) <= rough * 0.1) interval else rough
            val bpm = 60.0 / chosen
            return if (bpm in MIN_BPM..MAX_BPM) bpm else 0.0
        }

        /** Share of one-beat gaps and the median peak strength, kept below 1: a model is evidence, not proof. */
        fun confidence(beats: DoubleArray, peakLogits: List<Double>): Double {
            if (beats.size < MIN_BEATS) return 0.0
            val gaps = (1 until beats.size).map { beats[it] - beats[it - 1] }
            val interval = median(gaps)
            if (interval <= 0.0) return 0.0
            val regular = gaps.count { abs(it - interval) <= interval * 0.1 }.toDouble() / gaps.size
            val strength = 1.0 / (1.0 + exp(-(median(peakLogits) - 0.5)))
            return (0.35 + 0.4 * regular + 0.25 * strength).coerceIn(0.0, 0.95)
        }

        /** [value] wrapped into (-period/2, period/2]: the shortest way round a bar. */
        fun wrapCentered(value: Double, period: Double): Double {
            val wrapped = floorMod(value, period)
            return if (wrapped > period / 2) wrapped - period else wrapped
        }

        private fun floorMod(value: Double, period: Double): Double = value - period * floor(value / period)

        private fun nearest(sorted: DoubleArray, time: Double): Double =
            sorted.minByOrNull { abs(it - time) } ?: time

        private fun median(values: List<Double>): Double {
            if (values.isEmpty()) return 0.0
            val sorted = values.sorted()
            return sorted[sorted.size / 2]
        }
    }
}
