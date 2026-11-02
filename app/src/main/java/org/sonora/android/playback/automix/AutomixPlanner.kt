package org.sonora.android.playback.automix

import kotlin.math.abs
import kotlin.math.roundToLong

/** A beat-matched transition between two songs. */
internal data class AutomixPlan(
    /** Where in the outgoing song the incoming one starts: a bar line, on a phrase when one is known. */
    val exitMs: Long,
    /** Where the incoming song starts: a bar line, so a bar meets a bar; early enough for its first rise to land as the mix ends. */
    val entryMs: Long,
    /** Tempo of the incoming song during the mix, as a factor of its own (pitch kept). */
    val incomingRate: Float,
    /** How long the two songs overlap, on the outgoing song's clock. */
    val mixMs: Long,
    /** Length of the overlap in beats of the outgoing song: 8, 16 or 32. */
    val mixBeats: Int = 16
)

/**
 * Decides whether two analysed songs can be mixed on the beat, and how. Pure, so it can be tested.
 *
 * Songs are built in phrases of eight bars (32 beats in 4/4), and a mix that starts and ends on
 * phrase lines sounds planned rather than spliced. Bars are the grid's downbeats; a grid without
 * downbeats counts groups of four beats instead. The mix lasts 32, 16 or 8 beats: a whole phrase
 * only when both songs are known to have that much room, otherwise half or a quarter of one.
 */
internal object AutomixPlanner {
    /** Below this, a grid is a guess: the transition falls back to a plain crossfade. */
    const val MIN_CONFIDENCE = 0.55

    /** Farther apart than this, stretching one song to the other's tempo becomes audible. */
    const val MAX_STRETCH = 0.08

    private const val PHRASE_BEATS = 32
    private const val MIX_BEATS = 16
    private const val SHORT_MIX_BEATS = 8
    private const val BAR_BEATS = 4
    private const val MAX_MIX_MS = 20_000L

    /** A whole phrase may run longer, since it is only chosen when both songs have room for it. */
    private const val MAX_PHRASE_MIX_MS = 30_000L

    /** Songs shorter than this end on their own. */
    const val MIN_SONG_MS = 60_000L

    /** Nothing after the last beat is worth mixing over: a fade-out tail or silence. */
    private const val END_MARGIN_BEATS = 1.0

    /** A marker this close after a bar line still belongs to it: energy is measured per bar, the grid per beat. */
    private const val SNAP_TOLERANCE_BEATS = 0.5

    /** The incoming grid may stop a beat short of the mix end: its phase keeps counting with the tempo. */
    private const val TRACKED_TOLERANCE_BEATS = 1.0

    /**
     * [outgoingEnergy] (the outgoing song's ending) and [incomingEnergy] (the incoming song's start),
     * when known, move the mix onto phrases: over the outro, with the incoming song's first rise
     * landing as the outgoing one leaves.
     *
     * The longest mix wins whose exit and entry both work out. With neither marker the mix is 16
     * beats (8 when that would pass twenty seconds), as before the phrase rules.
     */
    fun plan(
        outgoing: BeatGrid,
        outgoingDurationMs: Long,
        incoming: BeatGrid,
        outgoingEnergy: EnergyProfile? = null,
        incomingEnergy: EnergyProfile? = null
    ): AutomixPlan? {
        if (outgoingDurationMs < MIN_SONG_MS) return null
        if (outgoing.confidence < MIN_CONFIDENCE || incoming.confidence < MIN_CONFIDENCE) return null

        val rate = outgoing.bpm / incoming.bpm
        if (abs(rate - 1.0) > MAX_STRETCH) return null

        val beatMs = 60_000.0 / outgoing.bpm
        val outro = outgoingEnergy?.outroStart
        val rise = incomingEnergy?.firstRise
        val lengths = buildList {
            if (outro != null && rise != null && PHRASE_BEATS * beatMs <= MAX_PHRASE_MIX_MS) add(PHRASE_BEATS)
            if (MIX_BEATS * beatMs <= MAX_MIX_MS) add(MIX_BEATS)
            add(SHORT_MIX_BEATS)
        }

        val durationSeconds = outgoingDurationMs / 1_000.0
        val musicEnd = minOf(durationSeconds, outgoing.beats.last() + END_MARGIN_BEATS * outgoing.beatSeconds)

        // Landing the rise comes first; when no length allows it, the incoming song starts on its first bar.
        val rises = if (rise != null) listOf(rise, null) else listOf(null)
        for (target in rises) {
            for (mixBeats in lengths) {
                if (mixBeats == PHRASE_BEATS && target == null) continue
                val mixSeconds = mixBeats * beatMs / 1_000.0
                val exit = exitPoint(outgoing, musicEnd, mixSeconds, outro, mustFitOutro = mixBeats == PHRASE_BEATS)
                    ?: continue
                val entry = entryPoint(incoming, mixBeats, target) ?: continue
                return AutomixPlan(
                    exitMs = (exit * 1_000).roundToLong(),
                    entryMs = (entry * 1_000).roundToLong(),
                    incomingRate = rate.toFloat(),
                    mixMs = (mixSeconds * 1_000).roundToLong(),
                    mixBeats = mixBeats
                )
            }
        }
        return null
    }

    /**
     * Where the outgoing song hands over, or null when a mix of [mixSeconds] does not fit.
     *
     * With an [outro], the mix starts on the bar line at or just before it, so it plays over the
     * winding down; an outro shorter than the mix moves the start back until the mix ends with the
     * music, unless [mustFitOutro]. Without one, the last phrase line that leaves room wins, then
     * the last bar line. The exit always lies inside the tracked beats.
     */
    private fun exitPoint(
        grid: BeatGrid,
        musicEnd: Double,
        mixSeconds: Double,
        outro: Double?,
        mustFitOutro: Boolean
    ): Double? {
        val earliest = grid.beats.first().coerceAtLeast(0.0)
        val fits = { time: Double -> time >= earliest && time + mixSeconds <= musicEnd }
        val bars = barLines(grid)
        // Today's choice, kept as the last resort: any downbeat, or any beat on a grid without bars.
        val anyBar = if (grid.beatsPerBar > 1) grid.downbeats else grid.beats

        if (outro != null) {
            val start = bars.lastOrNull { it <= outro + SNAP_TOLERANCE_BEATS * grid.beatSeconds } ?: bars.firstOrNull()
            if (start != null && fits(start)) return start
            if (mustFitOutro) return null
            return anyBar.lastOrNull(fits)
        }

        val anchor = bars.firstOrNull() ?: return anyBar.lastOrNull(fits)
        val anchorPosition = grid.beatPosition(anchor)
        val phrase = bars.lastOrNull {
            fits(it) && Math.round(grid.beatPosition(it) - anchorPosition) % PHRASE_BEATS == 0L
        }
        return phrase ?: anyBar.lastOrNull(fits)
    }

    /**
     * Where the incoming song starts, or null when a mix of [mixBeats] would run past its tracked beats.
     *
     * With a [rise], the bar line [mixBeats] beats before it, so the rise lands as the outgoing song
     * leaves; null when that would fall before the first bar. Without one, the first bar.
     */
    private fun entryPoint(grid: BeatGrid, mixBeats: Int, rise: Double?): Double? {
        val bars = barLines(grid)
        val first = bars.firstOrNull() ?: grid.beats.first()
        val entry = if (rise == null) first else {
            val target = grid.beatPosition(rise) - mixBeats
            if (target < grid.beatPosition(first) - SNAP_TOLERANCE_BEATS) return null
            bars.minBy { abs(grid.beatPosition(it) - target) }
        }
        if (grid.beatPosition(entry) + mixBeats > grid.beats.size - 1 + TRACKED_TOLERANCE_BEATS) return null
        return entry.coerceAtLeast(0.0)
    }

    /** Bar lines of [grid]: its downbeats, or every fourth beat when the model found none. */
    private fun barLines(grid: BeatGrid): DoubleArray =
        if (grid.beatsPerBar > 1) grid.downbeats
        else grid.beats.filterIndexed { index, _ -> index % BAR_BEATS == 0 }.toDoubleArray()
}
