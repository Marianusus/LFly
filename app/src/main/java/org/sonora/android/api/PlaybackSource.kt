package org.sonora.android.api

/**
 * User-selectable audio source preference.
 *
 * - [AUTO] keeps the historical default cascade (Monochrome first, then YouTube Music).
 * - [YOUTUBE_MUSIC] tries YouTube Music first, then Monochrome, then the remaining tiers.
 * - [MONOCHROME] tries Monochrome first, then YouTube Music, then the remaining tiers.
 *
 * A per-track override always wins over the global preference.
 */
enum class PlaybackSource {
    AUTO,
    YOUTUBE_MUSIC,
    MONOCHROME;

    companion object {
        fun fromNameOrAuto(name: String?): PlaybackSource =
            entries.firstOrNull { it.name == name } ?: AUTO
    }
}
