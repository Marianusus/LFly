package org.sonora.android.playback.automix

/**
 * Where the next transition is planned on the playing song's timeline, so the seek bar can show it.
 * [beatMatched] is true for an Automix on the beat, false for a plain crossfade.
 */
data class MixPoint(
    val trackId: String,
    val startMs: Long,
    val endMs: Long,
    val beatMatched: Boolean
)
