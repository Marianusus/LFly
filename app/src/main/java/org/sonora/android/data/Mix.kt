package org.sonora.android.data

import org.sonora.android.api.Track

/** Reserved id of the auto-generated "On Repeat" mix. */
const val MIX_ON_REPEAT_ID = "mix_on_repeat"

const val MIX_KIND_ON_REPEAT = "on_repeat"
const val MIX_KIND_GENRE = "genre"

/**
 * A generated mix shown in Home. Mixes are stored in `mixes.json` but are **not**
 * user playlists: the Library only shows the ones the user explicitly saved.
 *
 * @param tracks the live, auto-updated track list.
 * @param savedTracks an optional frozen snapshot created by "Save to library".
 *                    While present the mix appears in Library and no longer changes there.
 */
data class Mix(
    val id: String,
    val kind: String = MIX_KIND_GENRE,
    val genreKey: String? = null,
    val name: String,
    val tracks: List<Track> = emptyList(),
    val updatedAt: Long = 0L,
    val savedTracks: List<Track>? = null,
    val savedAt: Long? = null,
    /** The saved copy's own name, description and cover, set as on a playlist; null keeps the mix's. */
    val savedName: String? = null,
    val savedDescription: String? = null,
    val savedCover: String? = null
) {
    /** Saved to the Library: the copy stays there even when the listener takes every song out of it. */
    val isSaved: Boolean get() = savedTracks != null

    val trackCount: Int get() = tracks.size
    val savedTrackCount: Int get() = savedTracks?.size ?: 0

    /** Number shown next to the mix: the frozen snapshot once saved. */
    val displayTrackCount: Int get() = if (isSaved) savedTrackCount else trackCount

    val coverUrl: String? get() = firstCover(tracks)
    val savedCoverUrl: String? get() = firstCover(savedTracks.orEmpty())
    val displayCoverUrl: String? get() = if (isSaved) savedCoverUrl ?: coverUrl else coverUrl

    /**
     * The saved copy as the Library shows and edits it: a playlist with the mix's id, so every
     * playlist screen and action works on it. Null while the mix is not saved.
     */
    fun asSavedPlaylist(): Playlist? {
        val tracks = savedTracks ?: return null
        val at = savedAt ?: updatedAt
        return Playlist(
            id = id,
            name = savedName?.takeIf { it.isNotBlank() } ?: name,
            description = savedDescription,
            coverUrl = savedCover,
            tracks = tracks,
            createdAt = at,
            updatedAt = at
        )
    }

    /** This mix rebuilt, keeping the copy saved from [previous]: its songs and what the listener changed. */
    fun keepingSavedCopy(previous: Mix?): Mix = if (previous == null) this else copy(
        savedTracks = previous.savedTracks,
        savedAt = previous.savedAt,
        savedName = previous.savedName,
        savedDescription = previous.savedDescription,
        savedCover = previous.savedCover
    )

    private fun firstCover(list: List<Track>): String? =
        list.firstOrNull { !it.coverUrl.isNullOrBlank() }?.coverUrl
}
