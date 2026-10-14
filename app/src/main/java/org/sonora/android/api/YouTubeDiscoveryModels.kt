package org.sonora.android.api

/**
 * Discovery-only models for YouTube Music artists, albums and playlists.
 *
 * These are intentionally separate from the Deezer-oriented [Artist]/[Album] domain
 * models. They carry stable YouTube identifiers when the InnerTube response provides
 * them; entities without a stable id are still representable but flagged non-navigable.
 */

/** Local browse kind of a YouTube Music discovery entity. */
enum class YouTubeDiscoveryKind { ARTIST, ALBUM, PLAYLIST, UNKNOWN }

/**
 * Classifies a YouTube Music `browseId` into a discovery kind based on its real prefix.
 * `VL` is the playlist browse prefix, `MPRE` marks albums, `UC` marks artist channels.
 */
internal fun classifyDiscoveryBrowseId(browseId: String?): YouTubeDiscoveryKind {
    val id = browseId?.trim().orEmpty()
    if (id.isBlank()) return YouTubeDiscoveryKind.UNKNOWN
    return when {
        id.startsWith("VL") -> YouTubeDiscoveryKind.PLAYLIST
        id.startsWith("MPRE") -> YouTubeDiscoveryKind.ALBUM
        id.startsWith("UC") -> YouTubeDiscoveryKind.ARTIST
        id.startsWith("RD") -> YouTubeDiscoveryKind.PLAYLIST
        else -> YouTubeDiscoveryKind.UNKNOWN
    }
}

/** Stable local key for a followed artist, derived from browseId, channelId or name. */
internal fun youTubeArtistKey(browseId: String?, channelId: String?, name: String): String {
    if (!browseId.isNullOrBlank()) return "yt_browse_$browseId"
    if (!channelId.isNullOrBlank()) return "yt_channel_$channelId"
    return "yt_name_${normalizeArtistNameForKey(name)}"
}

/** Normalizes an artist name into a stable, filesystem/pref-safe key fragment. */
internal fun normalizeArtistNameForKey(name: String): String =
    name.lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .ifBlank { "unknown" }

/** A YouTube Music artist result/entity. */
data class YouTubeArtist(
    val browseId: String?,
    val channelId: String?,
    val name: String,
    val thumbnailUrl: String?,
    val subtitle: String? = null
) {
    val artistKey: String get() = youTubeArtistKey(browseId, channelId, name)

    /** Whether a detail screen can be opened for this entity. */
    val navigable: Boolean get() = !browseId.isNullOrBlank() || !channelId.isNullOrBlank()
}

/** A YouTube Music album result/entity. */
data class YouTubeAlbum(
    val browseId: String?,
    val playlistId: String?,
    val title: String,
    val artist: String?,
    val year: String? = null,
    val thumbnailUrl: String?,
    /** Numeric release year parsed from browse subtitle when available. */
    val releaseYear: Int? = null,
    /** Full release date only when InnerTube actually provides it. */
    val releaseDate: String? = null,
    /** Track count parsed from browse `secondSubtitle` when available. */
    val trackCount: Int? = null,
    /** Album type: "Album", "Single", "EP", … when provided. */
    val type: String? = null
) {
    /** Id usable to fetch detail (browseId preferred, playlistId fallback). */
    val detailId: String? get() = browseId?.takeIf { it.isNotBlank() } ?: playlistId?.takeIf { it.isNotBlank() }
    val navigable: Boolean get() = detailId != null
}

/** A YouTube Music playlist result/entity. */
data class YouTubePlaylist(
    val playlistId: String?,
    val browseId: String?,
    val title: String,
    val author: String? = null,
    val trackCount: Int? = null,
    val thumbnailUrl: String?
) {
    /** Id usable to fetch detail (playlistId preferred, browseId fallback). */
    val detailId: String? get() = playlistId?.takeIf { it.isNotBlank() } ?: browseId?.takeIf { it.isNotBlank() }
    val navigable: Boolean get() = detailId != null
}

/** Lazy-loaded artist detail: header entity plus top tracks and separated discography (metadata only). */
data class YouTubeArtistDetail(
    val artist: YouTubeArtist,
    val topTracks: List<Track> = emptyList(),
    val albums: List<YouTubeAlbum> = emptyList(),
    val playlists: List<YouTubePlaylist> = emptyList(),
    val singlesOrEps: List<YouTubeAlbum> = emptyList(),
    /** Musical videos (playable as `yt_` tracks). Empty when the artist has none. */
    val videos: List<Track> = emptyList(),
    /** Browse endpoint of the "all songs" page ("Cele mai populare melodii"), when provided. */
    val songsBrowseId: String? = null,
    val songsBrowseParams: String? = null
) {
    val hasAllSongs: Boolean get() = !songsBrowseId.isNullOrBlank()
}

/** A page of an artist's full song list, with an optional continuation token for lazy loading. */
data class YouTubeSongsPage(
    val tracks: List<Track> = emptyList(),
    val continuation: String? = null
)

/** Lazy-loaded album detail: header entity plus tracks (metadata only). */
data class YouTubeAlbumDetail(
    val album: YouTubeAlbum,
    val tracks: List<Track> = emptyList()
)

/** Lazy-loaded playlist detail: header entity plus tracks (metadata only). */
data class YouTubePlaylistDetail(
    val playlist: YouTubePlaylist,
    val tracks: List<Track> = emptyList(),
    /** Continuation token for the next page of playlist tracks, when more exist. */
    val continuation: String? = null
)
