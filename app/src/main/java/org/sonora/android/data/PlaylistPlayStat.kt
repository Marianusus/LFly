package org.sonora.android.data

/**
 * Persisted listening statistics for a user playlist.
 *
 * @param playCount how many times the playlist has been explicitly started (Play/Shuffle).
 * @param lastPlayedAtEpochMs wall-clock timestamp of the most recent start.
 */
data class PlaylistPlayStat(
    val playlistId: String,
    val playCount: Int = 0,
    val lastPlayedAtEpochMs: Long = 0L
)
