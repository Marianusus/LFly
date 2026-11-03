package org.sonora.android.playback.dj

import org.sonora.android.api.Track

/** Why the DJ picked a block of songs; the DJ says it out loud. */
enum class DjBlockKind {
    /** Starts from one of the most played songs, then each song flows out of the one before. */
    FLOW,

    /** Songs the listener plays most. */
    TOP_PLAYED,

    /** Around one followed artist; [DjBlock.subject] is the artist's name. */
    FOLLOWED_ARTIST,

    /** New songs close to the listener's taste. */
    DISCOVERY,

    /** Favourites not played for a while. */
    THROWBACK,

    /** From one of the listener's genre mixes; [DjBlock.subject] is the mix's name. */
    MIX
}

/** A few songs the DJ plays together and introduces with one line. */
data class DjBlock(
    val kind: DjBlockKind,
    val tracks: List<Track>,
    val subject: String? = null
)

/** What the UI shows about the DJ. */
data class DjState(
    val active: Boolean = false,
    /** Building the first block. */
    val loading: Boolean = false,
    /** The block playing now. */
    val block: DjBlock? = null,
    /** True while the DJ's voice is speaking. */
    val speaking: Boolean = false,
    /** The last line the DJ said, for a caption. */
    val lastLine: String? = null,
    /** `SystemClock.uptimeMillis()` when the voice actually started the line; null until then. */
    val voiceStartedAtMs: Long? = null
)
