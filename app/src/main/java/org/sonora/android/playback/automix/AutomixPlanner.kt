package org.sonora.android.playback.automix

import kotlin.math.abs
import kotlin.math.roundToLong

/** A beat-matched transition between two songs. */
internal data class AutomixPlan(
    /** Where in the outgoing song the incoming one starts: a bar line, on a phrase when one is known. */
    val exitMs: Long,
    /** Where the incoming song starts: the bar its intro starts on, or later when the intro outlasts the mix. */
    val entryMs: Long,
    /** Tempo of the incoming song during the mix, as a factor of its own (pitch kept). */
    val incomingRate: Float,
    /** How long the two songs overlap, on the outgoing song's clock. */
    val mixMs: Long,
    /** Length of the overlap in bars of the outgoing song: 16, 8, 4 or 2. */
    val mixBars: Int = 16,
    /** True when the incoming song's first rise (its drop or first verse) lands as the mix ends. */
    val landsRise: Boolean = false
) {
    val mixBeats: Int get() = mixBars * BEATS_PER_BAR

    private companion object {
        const val BEATS_PER_BAR = 4
    }
}

/** What the listener fixed in a playlist's transition editor; null fields are left to the planner. */
internal data class PlanFixes(
    val bars: Int? = null,
    /** Where the outgoing song should hand over; moved to its nearest bar line that leaves room. */
    val exitSeconds: Double? = null,
    /** Where the incoming song should start; moved to its nearest bar line. */
    val entrySeconds: Double? = null
)

/**
 * Decides whether two analysed songs can be mixed on the beat, and how. Pure, so it can be tested.
 *
 * The rules follow how a club DJ, and Spotify's Automix, blend two songs: the overlap is counted in
 * bars (16, else 8, 4 or 2 when there is no room) and capped at 35 seconds and a fifth of the
 * outgoing song; it starts on a bar line over the outgoing song's outro; the incoming song plays its
 * intro from the first bar, and its first rise lands as the outgoing song leaves whenever the intro
 * is long enough. Bars are the grid's downbeats; a grid without downbeats counts groups of four beats.
 */
internal object AutomixPlanner {
    /** Below this, a grid is a guess: the transition falls back to a plain crossfade. */
    const val MIN_CONFIDENCE = 0.55

    /** Farther apart than this, stretching one song to the other's tempo becomes audible. */
    const val MAX_STRETCH = 0.10

    /** Mix lengths in bars, longest first. */
    private val MIX_BARS = intArrayOf(16, 8, 4, 2)

    /** The shortest mix that is worth skipping part of an intro for, so its rise lands on the mix end. */
    private const val MIN_LANDING_BARS = 8

    private const val BAR_BEATS = 4

    /** The longest mix the editor offers. */
    private const val MAX_FIXED_BARS = 32
    private const val PHRASE_BARS = 8
    private const val MAX_MIX_MS = 35_000L

    /** A mix never takes more than this share of the outgoing song. */
    private const val MAX_MIX_SHARE = 0.2

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
     * when known, place the mix over the outro and land the incoming rise on the mix end.
     * The first plan that works wins: a landed rise over at least [MIN_LANDING_BARS], then the
     * longest mix from the incoming song's first bar.
     */
    fun plan(
        outgoing: BeatGrid,
        outgoingDurationMs: Long,
        incoming: BeatGrid,
        outgoingEnergy: EnergyProfile? = null,
        incomingEnergy: EnergyProfile? = null,
        fixes: PlanFixes? = null
    ): AutomixPlan? {
        if (outgoingDurationMs < MIN_SONG_MS) return null
        if (outgoing.confidence < MIN_CONFIDENCE || incoming.confidence < MIN_CONFIDENCE) return null

        val rate = outgoing.bpm / incoming.bpm
        if (abs(rate - 1.0) > MAX_STRETCH) return null

        val barMs = BAR_BEATS * 60_000.0 / outgoing.bpm
        // A length the listener chose is kept even past the caps: it only has to fit the analysed music.
        val lengths = fixes?.bars?.let { listOf(it.coerceIn(1, MAX_FIXED_BARS)) } ?: MIX_BARS.filter {
            it * barMs <= MAX_MIX_MS && it * barMs <= MAX_MIX_SHARE * outgoingDurationMs
        }
        val durationSeconds = outgoingDurationMs / 1_000.0
        val musicEnd = minOf(durationSeconds, outgoing.beats.last() + END_MARGIN_BEATS * outgoing.beatSeconds)
        val outro = outgoingEnergy?.outroStart
        val rise = incomingEnergy?.firstRise

        val fixedEntry = fixes?.entrySeconds
        val candidates = buildList {
            if (rise != null && fixedEntry == null) lengths.filter { it >= MIN_LANDING_BARS }.forEach { add(it to rise) }
            lengths.forEach { add(it to null) }
        }
        for ((bars, target) in candidates) {
            val mixBeats = bars * BAR_BEATS
            val mixSeconds = bars * barMs / 1_000.0
            val exit = fixes?.exitSeconds?.let { nearestExit(outgoing, musicEnd, mixSeconds, it) }
                ?: exitPoint(outgoing, musicEnd, mixSeconds, outro)
                ?: continue
            val entry = if (fixedEntry != null) fittedEntry(incoming, mixBeats, fixedEntry) else entryPoint(incoming, mixBeats, target)
            if (entry == null) continue
            val lands = target != null || (rise != null && fixedEntry != null &&
                abs(incoming.beatPosition(entry) + mixBeats - incoming.beatPosition(rise)) <= SNAP_TOLERANCE_BEATS * 2)
            return AutomixPlan(
                exitMs = (exit * 1_000).roundToLong(),
                entryMs = (entry * 1_000).roundToLong(),
                incomingRate = rate.toFloat(),
                mixMs = (mixSeconds * 1_000).roundToLong(),
                mixBars = bars,
                landsRise = lands
            )
        }
        return null
    }

    /** Bar lines of the outgoing song where a mix of [mixSeconds] fits, for the editor to snap a drag to. */
    fun exitChoices(grid: BeatGrid, outgoingDurationMs: Long, mixSeconds: Double): DoubleArray {
        val musicEnd = minOf(outgoingDurationMs / 1_000.0, grid.beats.last() + END_MARGIN_BEATS * grid.beatSeconds)
        val earliest = grid.beats.first().coerceAtLeast(0.0)
        return barLines(grid).filter { it >= earliest && it + mixSeconds <= musicEnd }.toDoubleArray()
    }

    /** Bar lines of the incoming song a mix of [mixBeats] can start on. */
    fun entryChoices(grid: BeatGrid, mixBeats: Int): DoubleArray =
        barLines(grid).filter { grid.beatPosition(it) + mixBeats <= grid.lastBeatNumber + TRACKED_TOLERANCE_BEATS }
            .map { it.coerceAtLeast(0.0) }
            .toDoubleArray()

    /** The outgoing bar line nearest [wanted] that leaves room for the mix, or null when none does. */
    private fun nearestExit(grid: BeatGrid, musicEnd: Double, mixSeconds: Double, wanted: Double): Double? {
        val earliest = grid.beats.first().coerceAtLeast(0.0)
        return barLines(grid).filter { it >= earliest && it + mixSeconds <= musicEnd }.minByOrNull { abs(it - wanted) }
    }

    /** The incoming bar line nearest [wanted] from which the mix stays inside the tracked beats. */
    private fun fittedEntry(grid: BeatGrid, mixBeats: Int, wanted: Double): Double? =
        entryChoices(grid, mixBeats).minByOrNull { abs(it - wanted) }

    /**
     * Where the outgoing song hands over, or null when a mix of [mixSeconds] does not fit.
     *
     * With an [outro], the mix starts on the bar line at or just before it, so it plays over the
     * winding down; an outro shorter than the mix moves the start back until the mix ends with the
     * music. Without one, the last phrase line that leaves room wins, then the last bar line. The
     * exit always lies inside the tracked beats.
     */
    private fun exitPoint(grid: BeatGrid, musicEnd: Double, mixSeconds: Double, outro: Double?): Double? {
        val earliest = grid.beats.first().coerceAtLeast(0.0)
        val fits = { time: Double -> time >= earliest && time + mixSeconds <= musicEnd }
        val bars = barLines(grid)
        if (bars.isEmpty()) return null

        if (outro != null) {
            val start = bars.lastOrNull { it <= outro + SNAP_TOLERANCE_BEATS * grid.beatSeconds } ?: bars.first()
            if (fits(start)) return start
            return bars.lastOrNull(fits)
        }

        val anchorPosition = grid.beatPosition(bars.first())
        val phrase = bars.lastOrNull {
            fits(it) && Math.round(grid.beatPosition(it) - anchorPosition) % (PHRASE_BARS * BAR_BEATS) == 0L
        }
        return phrase ?: bars.lastOrNull(fits)
    }

    /**
     * Where the incoming song starts, or null when a mix of [mixBeats] would run past its tracked
     * beats or cannot land [rise].
     *
     * Without a rise, the first bar: the intro plays in full. With one, the bar line [mixBeats]
     * before it, so the rise lands as the outgoing song leaves; null when the intro is shorter than
     * the mix, since landing it would mean starting before the song does.
     */
    private fun entryPoint(grid: BeatGrid, mixBeats: Int, rise: Double?): Double? {
        val bars = barLines(grid)
        val first = bars.firstOrNull() ?: grid.beats.first()
        val entry = if (rise == null) first else {
            val target = grid.beatPosition(rise) - mixBeats
            if (target < grid.beatPosition(first) - SNAP_TOLERANCE_BEATS) return null
            bars.minBy { abs(grid.beatPosition(it) - target) }
        }
        if (grid.beatPosition(entry) + mixBeats > grid.lastBeatNumber + TRACKED_TOLERANCE_BEATS) return null
        return entry.coerceAtLeast(0.0)
    }

    /** Bar lines of [grid]: its downbeats, or every fourth beat when the model found none. */
    private fun barLines(grid: BeatGrid): DoubleArray =
        if (grid.beatsPerBar > 1) grid.downbeats
        else grid.beats.filterIndexed { index, _ -> index % BAR_BEATS == 0 }.toDoubleArray()
}
