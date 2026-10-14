package org.sonora.android.playback

import org.sonora.android.api.Track

/**
 * Immutable state representation of Sonora's playback engine for UI consumption.
 */
data class PlaybackState(
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    /**
     * Boundary between the user's queue and autoplay/radio additions: tracks in
     * [0, userQueueSize) are the ones the user queued; tracks in [userQueueSize, queue.size)
     * were appended automatically by the autoplay/radio engine.
     */
    val userQueueSize: Int = 0,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val isShuffle: Boolean = false,
    val isAutoplayEnabled: Boolean = true,
    val sourceContext: String? = null,
    /** Streaming provider/API currently serving the audio (e.g. "Monochrome HiFi", "YouTube Music"). */
    val streamProvider: String? = null,
    val error: String? = null
) {
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    val hasNext: Boolean
        get() = when {
            queue.isEmpty() -> false
            isShuffle -> true
            repeatMode == RepeatMode.ALL -> true
            isAutoplayEnabled -> true
            else -> currentIndex in 0 until queue.size - 1
        }

    val hasPrevious: Boolean
        get() = when {
            queue.isEmpty() -> false
            isShuffle -> true
            repeatMode == RepeatMode.ALL -> true
            else -> currentIndex > 0
        }

    val formattedPosition: String
        get() {
            val totalSeconds = (positionMs / 1000).coerceAtLeast(0)
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format("%d:%02d", minutes, seconds)
        }

    val formattedDuration: String
        get() {
            val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format("%d:%02d", minutes, seconds)
        }
}
