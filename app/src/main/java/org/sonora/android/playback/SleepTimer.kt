package org.sonora.android.playback

/** When playback stops by itself. */
sealed interface SleepTimer {
    data object Off : SleepTimer

    /** Stops at [endsAtElapsedMs], on the `SystemClock.elapsedRealtime` clock. */
    data class At(val endsAtElapsedMs: Long) : SleepTimer

    /** Stops when the current song ends. */
    data object EndOfTrack : SleepTimer

    companion object {
        val PRESET_MINUTES = listOf(15, 30, 45, 60, 90)
    }
}

/** Speeds offered in the player; pitch stays the same. */
val PLAYBACK_SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
