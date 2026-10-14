package org.sonora.android.playback

/**
 * Repeat modes supported by Sonora's playback engine.
 */
enum class RepeatMode {
    OFF,
    ALL,
    ONE;

    fun next(): RepeatMode = when (this) {
        OFF -> ALL
        ALL -> ONE
        ONE -> OFF
    }
}
