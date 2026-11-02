package org.sonora.android.playback.automix

import kotlin.math.abs
import kotlin.math.roundToLong

/** A beat-matched transition between two songs. */
internal data class AutomixPlan(
    /** Where in the outgoing song the incoming one starts: a downbeat. */
    val exitMs: Long,
    /** Where the incoming song starts: its first downbeat, so a bar meets a bar. */
    val entryMs: Long,
    /** Tempo of the incoming song during the mix, as a factor of its own (pitch kept). */
    val incomingRate: Float,
    /** How long the two songs overlap, on the outgoing song's clock. */
    val mixMs: Long
)

/** Decides whether two analysed songs can be mixed on the beat, and how. Pure, so it can be tested. */
internal object AutomixPlanner {
    /** Below this, a grid is a guess: the transition falls back to a plain crossfade. */
    const val MIN_CONFIDENCE = 0.55

    /** Farther apart than this, stretching one song to the other's tempo becomes audible. */
    const val MAX_STRETCH = 0.08

    private const val MIX_BEATS = 16
    private const val SHORT_MIX_BEATS = 8
    private const val MAX_MIX_MS = 20_000L

    /** Songs shorter than this end on their own. */
    const val MIN_SONG_MS = 60_000L

    /** Nothing after the last beat is worth mixing over: a fade-out tail or silence. */
    private const val END_MARGIN_BEATS = 1.0

    fun plan(outgoing: BeatGrid, outgoingDurationMs: Long, incoming: BeatGrid): AutomixPlan? {
        if (outgoingDurationMs < MIN_SONG_MS) return null
        if (outgoing.confidence < MIN_CONFIDENCE || incoming.confidence < MIN_CONFIDENCE) return null

        val rate = outgoing.bpm / incoming.bpm
        if (abs(rate - 1.0) > MAX_STRETCH) return null

        val beatMs = 60_000.0 / outgoing.bpm
        val mixBeats = if (MIX_BEATS * beatMs <= MAX_MIX_MS) MIX_BEATS else SHORT_MIX_BEATS
        val mixSeconds = mixBeats * beatMs / 1_000.0

        val durationSeconds = outgoingDurationMs / 1_000.0
        val musicEnd = minOf(durationSeconds, outgoing.beats.last() + END_MARGIN_BEATS * outgoing.beatSeconds)
        val exits = if (outgoing.beatsPerBar > 1) outgoing.downbeats else outgoing.beats
        val exit = exits.lastOrNull { it + mixSeconds <= musicEnd && it >= 0.0 } ?: return null

        val entry = (if (incoming.beatsPerBar > 1) incoming.downbeats.first() else incoming.beats.first())
            .coerceAtLeast(0.0)

        return AutomixPlan(
            exitMs = (exit * 1_000).roundToLong(),
            entryMs = (entry * 1_000).roundToLong(),
            incomingRate = rate.toFloat(),
            mixMs = (mixSeconds * 1_000).roundToLong()
        )
    }
}
