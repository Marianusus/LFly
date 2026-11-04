package org.sonora.android.playback.automix

/**
 * The ready-made transitions a mixed playlist offers between two songs, as in Spotify's Mix.
 * [recipe] is null for AUTO, which picks one per transition, and for NONE, which plays the songs
 * one after the other.
 */
enum class MixPreset(val recipe: MixRecipe?) {
    AUTO(null),

    /** A smooth crossfade with the bass handed over in the middle. */
    FADE(MixRecipe(VolumeCurve.SMOOTH_CROSSFADE, EqCurve.CENTER_BASS_SWAP)),

    /** Build and drop: both songs up, the new one filtered and without bass until the last downbeat. */
    RISE(TransitionStyle.BASS_SWAP.recipe),

    /** Both songs up while a three-band EQ hands one over to the other. */
    BLEND(TransitionStyle.EQ_SWAP.recipe),

    /** Both songs up, each under a sweeping low-pass, the bass swapped in the middle. */
    WAVE(TransitionStyle.WAVE.recipe),

    /** The new song melts in under a high-pass while the old one fades under its own. */
    MELT(TransitionStyle.FILTER.recipe),

    /** A hard cut on the middle downbeat. */
    SLAM(TransitionStyle.SLAM.recipe),

    /** No transition: the first song ends, then the next one starts. */
    NONE(null);

    companion object {
        /** The order the editor shows them in. */
        val SHOWN = listOf(AUTO, FADE, RISE, BLEND, WAVE, MELT, SLAM, NONE)
    }
}

/**
 * What the listener chose for one transition of a mixed playlist. Every field has a default, so a
 * transition saved by an older version still loads.
 */
data class MixTransition(
    val preset: MixPreset = MixPreset.AUTO,
    /** Set when the listener changed the ingredients in the editor: it then wins over [preset]. */
    val custom: MixRecipe? = null,
    /** Mix length in bars of the outgoing song; null lets Automix choose. */
    val bars: Int? = null,
    /** False plays the two songs at their own tempo, without lining up the beats. */
    val beatmatch: Boolean = true,
    /** Where the outgoing song hands over, on one of its bar lines; null lets Automix choose. */
    val exitMs: Long? = null,
    /** Where the incoming song starts, on one of its bar lines; null lets Automix choose. */
    val entryMs: Long? = null
) {
    /** The ingredients this transition plays with, or null when Automix picks them (AUTO) or there is no transition. */
    val recipe: MixRecipe? get() = custom ?: preset.recipe

    val isNone: Boolean get() = custom == null && preset == MixPreset.NONE

    companion object {
        val AUTO = MixTransition()

        /** Bar counts the editor offers, as Spotify's duration sheet does. */
        val BAR_CHOICES = listOf(2, 4, 8, 16, 32)
    }
}

/** Progress of a mixed playlist's analysis: the song at [done] (from 0) of [total] songs is being analysed. */
data class MixAnalysisProgress(val playlistId: String, val done: Int, val total: Int)

/** A playlist's Mix: on or off, and the transitions the listener edited, keyed by [pairKey]. */
data class PlaylistMix(
    val enabled: Boolean = false,
    val transitions: Map<String, MixTransition> = emptyMap()
) {
    fun transition(fromId: String, toId: String): MixTransition =
        transitions[pairKey(fromId, toId)] ?: MixTransition.AUTO

    companion object {
        /** A transition belongs to the pair of songs, so it follows them when the playlist is reordered. */
        fun pairKey(fromId: String, toId: String): String = "$fromId>$toId"
    }
}
