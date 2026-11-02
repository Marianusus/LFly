package org.sonora.android.playback.automix

import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Loudness of a song edge bar by bar, on the song's own timeline. It lets the planner find where an
 * outro winds down and where an intro opens up.
 */
internal class EnergyProfile(
    /** Start of each bar, in seconds on the song's timeline. */
    val barStarts: DoubleArray,
    /** Short-term loudness of each bar in dB, relative to the loudest bar (so 0 or below). */
    val barEnergyDb: DoubleArray,
    /** True when the stretch is soft or has no driving beat throughout: a ballad or an ambient ending. */
    val isCalm: Boolean,
    /** Seconds where the music winds down for good (the outro), on a bar line; null when it never does. */
    val outroStart: Double?,
    /** Seconds where the music first opens up after the intro (drop or first verse), on a bar line; null without a clear rise. */
    val firstRise: Double?
) {
    companion object {
        /** Fewer bars than this say nothing about sections. */
        private const val MIN_BARS = 4

        /** Silence reads as this, so one empty bar cannot stretch the scale. */
        private const val FLOOR_DB = -60.0

        /** A section change must be at least this big: about what a listener hears as "it drops". */
        private const val CHANGE_DB = 5.0

        /** After a change, the music may drift back this close before the change stops counting. */
        private const val HOLD_DB = 3.0

        /** The main section before an outro, the intro before a rise, and the outro itself, in bars. */
        private const val MIN_MAIN_BARS = 4
        private const val MIN_INTRO_BARS = 2
        private const val MIN_OUTRO_BARS = 2

        /** Within this of the window's loud level, the music is already at full energy. */
        private const val NEAR_MAIN_DB = 4.0

        /** Downbeats that miss the window edge by less than this share of a bar still make a bar. */
        private const val EDGE_BARS = 0.1

        /** Envelope resolution for the beat measure: fine enough to see a drum hit. */
        private const val FRAME_SECONDS = 0.01

        /** Where the kick lives and where hats and snares live. */
        private const val LOW_BAND_HZ = 150.0
        private const val HIGH_BAND_HZ = 4_000.0

        /** A band this far below the whole mix is inaudible behind it and says nothing about the beat. */
        private const val QUIET_BAND_DB = -30.0

        /** Envelope contrast within a beat below which nothing drives the music. */
        private const val NO_BEAT_DB = 6.0

        /** A moderate beat still counts as calm when even the loudest bar is softer than [SOFT_LUFS]. */
        private const val SOFT_BEAT_DB = 10.0

        /** On the mono analysis audio, which reads a few dB under a stereo meter. */
        private const val SOFT_LUFS = -14.0

        /**
         * [samples] are mono at [sampleRate] and start at [startSeconds] in the song; [grid] was tracked
         * on the same audio. Null when there is too little to measure.
         *
         * Calm is judged from how hard the music hits on the beat, not from the grid's confidence (the
         * model finds a steady pulse in a piano ballad too). Within every beat, the contrast between the
         * loudest 10 ms and the typical level is taken in two bands: below 150 Hz (the kick) and above
         * 4 kHz (hats and snare). Drums give 10 dB or more in at least one band, every beat; pads,
         * strings and sustained voices barely move. The median beat of the louder band decides: under
         * 6 dB the stretch is calm, under 10 dB it is calm when it is also soft (a gentle ballad
         * mastered quietly). Loud, less-compressed tracks have even bigger transients, so they stay out.
         */
        fun compute(samples: FloatArray, sampleRate: Int, startSeconds: Double, grid: BeatGrid): EnergyProfile? {
            if (sampleRate <= 0 || samples.isEmpty() || grid.beats.isEmpty()) return null
            val rate = sampleRate.toDouble()
            val windowEnd = startSeconds + samples.size / rate
            val bars = barLines(grid, startSeconds, windowEnd)
            if (bars.size < MIN_BARS + 1) return null

            val envelope = Envelope.measure(samples, rate)
            fun frameAt(time: Double): Int =
                ((time - startSeconds) * rate / envelope.frame).roundToInt().coerceIn(0, envelope.count)

            val count = bars.size - 1
            val meanSquares = DoubleArray(count) { envelope.meanSquare(envelope.weighted, frameAt(bars[it]), frameAt(bars[it + 1])) }
            val loudest = meanSquares.max()
            if (loudest <= 1e-12) return null
            val energyDb = DoubleArray(count) { (10 * log10(meanSquares[it].coerceAtLeast(1e-12) / loudest)).coerceAtLeast(FLOOR_DB) }

            val beats = ArrayList<IntRange>(count * 4)
            for (bar in 0 until count) {
                val quarter = (bars[bar + 1] - bars[bar]) / 4
                for (beat in 0 until 4) {
                    val from = frameAt(bars[bar] + beat * quarter)
                    beats += from until frameAt(bars[bar] + (beat + 1) * quarter)
                }
            }
            val pulse = maxOf(envelope.pulseDb(envelope.low, beats), envelope.pulseDb(envelope.high, beats))
            val soft = Loudness.toLufs(loudest) < SOFT_LUFS
            val calm = pulse < NO_BEAT_DB || (pulse < SOFT_BEAT_DB && soft)

            val starts = bars.copyOf(count)
            return EnergyProfile(
                barStarts = starts,
                barEnergyDb = energyDb,
                isCalm = calm,
                outroStart = outroBar(energyDb)?.let { starts[it] },
                firstRise = riseBar(energyDb)?.let { starts[it] }
            )
        }

        /**
         * Bar lines covering the window, the last one closing the final bar. Tracked downbeats are used
         * where they exist (groups of four beats without them); gaps between them and the stretches
         * before and after are filled at the grid's tempo.
         */
        internal fun barLines(grid: BeatGrid, start: Double, end: Double): DoubleArray {
            val bar = 4 * grid.beatSeconds
            val anchors = if (grid.beatsPerBar > 1) {
                grid.downbeats
            } else {
                DoubleArray((grid.beats.size + 3) / 4) { grid.beats[it * 4] }
            }
            if (anchors.isEmpty() || bar <= 0.0) return DoubleArray(0)
            val edge = EDGE_BARS * bar

            val lines = ArrayList<Double>()
            var back = anchors[0] - bar
            while (back >= start - edge) {
                lines += back
                back -= bar
            }
            lines.reverse()
            lines += anchors[0]
            for (index in 1 until anchors.size) {
                val last = lines.last()
                val gap = anchors[index] - last
                // A downbeat half a bar after the last one is a stray; one several bars on had misses between.
                if (gap < bar / 2) continue
                val steps = (gap / bar).roundToInt().coerceAtLeast(1)
                for (step in 1 until steps) lines += last + gap * step / steps
                lines += anchors[index]
            }
            var next = lines.last() + bar
            while (next <= end + edge) {
                lines += next
                next += bar
            }
            return lines.filter { it >= start - edge && it <= end + edge }.toDoubleArray()
        }

        /**
         * The first bar that sits [CHANGE_DB] under the main section before it (its upper quartile, so
         * a breakdown does not lower it) with everything after it staying at least [HOLD_DB] under.
         */
        internal fun outroBar(energyDb: DoubleArray): Int? {
            for (index in MIN_MAIN_BARS..energyDb.size - MIN_OUTRO_BARS) {
                val main = percentile(energyDb, 0, index, 0.75)
                if (energyDb[index] > main - CHANGE_DB) continue
                if ((index until energyDb.size).all { energyDb[it] <= main - HOLD_DB }) return index
            }
            return null
        }

        /**
         * The first bar [CHANGE_DB] over the intro before it (its median) whose next bar stays at least
         * [HOLD_DB] over too. A song whose opening bars are already near its loud level has no rise.
         */
        internal fun riseBar(energyDb: DoubleArray): Int? {
            if (energyDb.size < MIN_INTRO_BARS + 2) return null
            val main = percentile(energyDb, 0, energyDb.size, 0.9)
            if ((0 until MIN_INTRO_BARS).any { energyDb[it] >= main - NEAR_MAIN_DB }) return null
            for (index in MIN_INTRO_BARS until energyDb.size - 1) {
                val intro = percentile(energyDb, 0, index, 0.5)
                if (energyDb[index] >= intro + CHANGE_DB && energyDb[index + 1] >= intro + HOLD_DB) return index
            }
            return null
        }

        private fun percentile(values: DoubleArray, from: Int, to: Int, share: Double): Double {
            val sorted = values.copyOfRange(from, to).apply { sort() }
            return sorted[((sorted.size - 1) * share).roundToInt()]
        }
    }

    /**
     * Energy per 10 ms frame, measured in one pass and without keeping any filtered audio: K-weighted
     * for loudness, plus the kick and hi-hat bands for the beat.
     */
    private class Envelope(
        val frame: Int,
        val count: Int,
        private val lengths: IntArray,
        val weighted: DoubleArray,
        val low: DoubleArray,
        val high: DoubleArray
    ) {
        fun meanSquare(band: DoubleArray, from: Int, to: Int): Double {
            var sum = 0.0
            var length = 0
            for (index in from until to) {
                sum += band[index]
                length += lengths[index]
            }
            return if (length == 0) 0.0 else sum / length
        }

        /** Median, over the beats, of how far each beat's loudest frame stands over its typical frame. */
        fun pulseDb(band: DoubleArray, beats: List<IntRange>): Double {
            if (meanSquare(band, 0, count) < meanSquare(weighted, 0, count) * dbToPower(QUIET_BAND_DB)) return 0.0
            val levels = DoubleArray(count) { 10 * log10(band[it] / lengths[it].coerceAtLeast(1) + 1e-20) }
            val floor = levels.max() + FLOOR_DB
            for (index in levels.indices) levels[index] = levels[index].coerceAtLeast(floor)
            val contrasts = beats.filter { it.last - it.first >= 2 }.map { beat ->
                val inBeat = levels.copyOfRange(beat.first, beat.last + 1).apply { sort() }
                inBeat.last() - inBeat[inBeat.size / 2]
            }
            if (contrasts.isEmpty()) return 0.0
            return contrasts.sorted()[contrasts.size / 2]
        }

        companion object {
            fun measure(samples: FloatArray, rate: Double): Envelope {
                val frame = (rate * FRAME_SECONDS).roundToInt().coerceAtLeast(1)
                val count = (samples.size + frame - 1) / frame
                val lengths = IntArray(count) { minOf(frame, samples.size - it * frame) }
                val weighted = DoubleArray(count)
                val low = DoubleArray(count)
                val high = DoubleArray(count)
                val weighting = Loudness.KWeighting(rate)
                // Two biquads each: 24 dB per octave keeps the vocals out of the kick band and vice versa.
                val lowPass = DjFilterMath.lowPass(rate, LOW_BAND_HZ)
                val highPass = DjFilterMath.highPass(rate, HIGH_BAND_HZ)
                val low1 = BiquadState()
                val low2 = BiquadState()
                val high1 = BiquadState()
                val high2 = BiquadState()
                for (index in samples.indices) {
                    val x = samples[index].toDouble()
                    val slot = index / frame
                    val w = weighting.run(x)
                    weighted[slot] += w * w
                    val l = low2.run(lowPass, low1.run(lowPass, x))
                    low[slot] += l * l
                    val h = high2.run(highPass, high1.run(highPass, x))
                    high[slot] += h * h
                }
                return Envelope(frame, count, lengths, weighted, low, high)
            }

            private fun dbToPower(db: Double): Double = 10.0.pow(db / 10)
        }
    }
}
