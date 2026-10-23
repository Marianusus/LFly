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
    val savedAt: Long? = null
) {
    val isSaved: Boolean get() = !savedTracks.isNullOrEmpty()

    val trackCount: Int get() = tracks.size
    val savedTrackCount: Int get() = savedTracks?.size ?: 0

    /** Number shown next to the mix: the frozen snapshot once saved. */
    val displayTrackCount: Int get() = if (isSaved) savedTrackCount else trackCount

    val coverUrl: String? get() = firstCover(tracks)
    val savedCoverUrl: String? get() = firstCover(savedTracks.orEmpty())
    val displayCoverUrl: String? get() = if (isSaved) savedCoverUrl ?: coverUrl else coverUrl

    private fun firstCover(list: List<Track>): String? =
        list.firstOrNull { !it.coverUrl.isNullOrBlank() }?.coverUrl
}
