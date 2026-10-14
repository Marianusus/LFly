package org.sonora.android.data

import org.sonora.android.api.Track

/**
 * Persisted per-track listening statistics used by the taste-based recommendation engine.
 *
 * @param playCount number of times playback of this track actually started.
 * @param completionCount number of times the track was listened past the completion threshold
 *                        (~85% of its duration, or to the natural end when the duration is unknown).
 * @param lastPlayedAtEpochMs wall-clock timestamp of the most recent play start.
 */
data class PlayStat(
    val track: Track,
    val playCount: Int = 0,
    val completionCount: Int = 0,
    val lastPlayedAtEpochMs: Long = 0L
)
