package org.sonora.android.playback.automix

/** The transition style picked in Settings. [AUTO] lets Automix choose one for each transition. */
enum class MixStyle {
    AUTO,
    FADE,
    BASS_SWAP,
    FILTER,
    RISE;

    companion object {
        fun fromKey(key: String?): MixStyle = entries.firstOrNull { it.name == key } ?: AUTO
    }
}
