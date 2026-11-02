package org.sonora.android.playback.auto

import org.sonora.android.api.Track
import org.sonora.android.data.Mix
import org.sonora.android.data.MusicRepository
import org.sonora.android.data.Playlist
import java.net.URLDecoder
import java.net.URLEncoder

/** Ids of the Android Auto browse tree. A track id keeps its folder so the queue can be rebuilt. */
internal object AutoIds {
    const val ROOT = "lfly_root"
    const val HOME = "home"
    const val FAVORITES = "favorites"
    const val PLAYLISTS = "playlists"
    const val DOWNLOADS = "downloads"
    const val HISTORY = "history"
    const val CHARTS = "charts"

    private const val PLAYLIST_PREFIX = "playlist/"
    private const val MIX_PREFIX = "mix/"
    private const val SEARCH_PREFIX = "search/"
    private const val TRACK_PREFIX = "t|"

    fun playlist(id: String) = PLAYLIST_PREFIX + id
    fun mix(id: String) = MIX_PREFIX + id

    /** Search results keep their query, so a tap still works after another search or a restart. */
    fun search(query: String): String = SEARCH_PREFIX + URLEncoder.encode(query, Charsets.UTF_8.name())
    fun track(folderId: String, trackId: String) = "$TRACK_PREFIX$folderId|$trackId"

    fun playlistIdOf(folderId: String): String? =
        folderId.takeIf { it.startsWith(PLAYLIST_PREFIX) }?.removePrefix(PLAYLIST_PREFIX)

    fun mixIdOf(folderId: String): String? =
        folderId.takeIf { it.startsWith(MIX_PREFIX) }?.removePrefix(MIX_PREFIX)

    fun searchQueryOf(folderId: String): String? =
        folderId.takeIf { it.startsWith(SEARCH_PREFIX) }?.let { id ->
            runCatching { URLDecoder.decode(id.removePrefix(SEARCH_PREFIX), Charsets.UTF_8.name()) }.getOrNull()
        }

    /** Splits a track item id into its folder and track id, or null for anything else. */
    fun parseTrack(mediaId: String): Pair<String, String>? {
        if (!mediaId.startsWith(TRACK_PREFIX)) return null
        val parts = mediaId.removePrefix(TRACK_PREFIX).split('|', limit = 2)
        if (parts.size != 2 || parts[0].isEmpty() || parts[1].isEmpty()) return null
        return parts[0] to parts[1]
    }
}

internal enum class AutoIcon { HOME, FAVORITES, PLAYLISTS, DOWNLOADS, HISTORY, CHARTS }

internal data class AutoLabels(
    val home: String,
    val favorites: String,
    val playlists: String,
    val downloads: String,
    val history: String,
    val charts: String
)

internal sealed interface AutoNode {
    val id: String

    data class Folder(
        override val id: String,
        val title: String,
        val subtitle: String? = null,
        val icon: AutoIcon? = null,
        val coverUrl: String? = null
    ) : AutoNode

    data class Song(override val id: String, val track: Track) : AutoNode
}

internal data class AutoLibrarySnapshot(
    val favorites: List<Track> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val mixes: List<Mix> = emptyList(),
    val downloads: List<Track> = emptyList(),
    val history: List<Track> = emptyList(),
    val charts: List<Track> = emptyList(),
    val searchResults: List<Track> = emptyList(),
    val dislikedTrackIds: Set<String> = emptySet()
)

/** What Android Auto can browse. Pure: Android types stay in the adapter, labels are injected. */
internal object AutoBrowseTree {

    private const val MAX_TRACKS = 500

    /** Android Auto shows these as tabs, and it shows at most four. */
    fun root(labels: AutoLabels): List<AutoNode.Folder> = listOf(
        AutoNode.Folder(AutoIds.HOME, labels.home, icon = AutoIcon.HOME),
        AutoNode.Folder(AutoIds.FAVORITES, labels.favorites, icon = AutoIcon.FAVORITES),
        AutoNode.Folder(AutoIds.PLAYLISTS, labels.playlists, icon = AutoIcon.PLAYLISTS),
        AutoNode.Folder(AutoIds.DOWNLOADS, labels.downloads, icon = AutoIcon.DOWNLOADS)
    )

    /** Children of [folderId], or null when the folder does not exist. */
    fun children(folderId: String, snapshot: AutoLibrarySnapshot, labels: AutoLabels): List<AutoNode>? =
        when (folderId) {
            AutoIds.ROOT -> root(labels)
            AutoIds.HOME -> home(snapshot, labels)
            AutoIds.PLAYLISTS -> playlists(snapshot)
            else -> tracksFor(folderId, snapshot)?.map { track ->
                AutoNode.Song(AutoIds.track(folderId, track.id), track)
            }
        }

    /** Playable tracks of [folderId] in order, or null when it does not hold tracks. */
    fun tracksFor(folderId: String, snapshot: AutoLibrarySnapshot): List<Track>? {
        val tracks = when (folderId) {
            AutoIds.FAVORITES -> snapshot.favorites
            AutoIds.DOWNLOADS -> snapshot.downloads
            AutoIds.HISTORY -> snapshot.history
            AutoIds.CHARTS -> snapshot.charts
            else -> {
                val playlistId = AutoIds.playlistIdOf(folderId)
                val mixId = AutoIds.mixIdOf(folderId)
                when {
                    playlistId != null -> snapshot.playlists.firstOrNull { it.id == playlistId }?.tracks
                    mixId != null -> snapshot.mixes.firstOrNull { it.id == mixId }?.let(::mixTracks)
                    AutoIds.searchQueryOf(folderId) != null -> snapshot.searchResults
                    else -> null
                }
            }
        } ?: return null
        return playable(tracks, snapshot.dislikedTrackIds)
    }

    /** Local copies and downloads of the same file count once, as they do in the app. */
    fun mergeOffline(downloads: List<Track>, locals: List<Track>): List<Track> =
        (downloads + locals).distinctBy { track ->
            track.streamUrl?.removePrefix("file://")?.takeIf { it.isNotBlank() } ?: track.id
        }

    private fun home(snapshot: AutoLibrarySnapshot, labels: AutoLabels): List<AutoNode> = buildList {
        add(AutoNode.Folder(AutoIds.HISTORY, labels.history, icon = AutoIcon.HISTORY))
        add(AutoNode.Folder(AutoIds.CHARTS, labels.charts, icon = AutoIcon.CHARTS))
        snapshot.mixes
            .filter { mixTracks(it).isNotEmpty() }
            .forEach { mix ->
                add(AutoNode.Folder(AutoIds.mix(mix.id), mix.name, coverUrl = mix.displayCoverUrl))
            }
    }

    private fun playlists(snapshot: AutoLibrarySnapshot): List<AutoNode> =
        snapshot.playlists
            .filter { it.tracks.isNotEmpty() && it.id != MusicRepository.PLAYLIST_DOWNLOADED_ID }
            .map { playlist ->
                AutoNode.Folder(
                    id = AutoIds.playlist(playlist.id),
                    title = playlist.name,
                    coverUrl = playlist.displayCoverUrl
                )
            }

    private fun mixTracks(mix: Mix): List<Track> = mix.savedTracks?.takeIf { it.isNotEmpty() } ?: mix.tracks

    private fun playable(tracks: List<Track>, disliked: Set<String>): List<Track> =
        tracks
            .filter { track ->
                track.id.isNotBlank() &&
                    !track.id.startsWith("album_") &&
                    !track.id.startsWith("artist_") &&
                    track.id !in disliked
            }
            .distinctBy { it.id }
            .take(MAX_TRACKS)
}
