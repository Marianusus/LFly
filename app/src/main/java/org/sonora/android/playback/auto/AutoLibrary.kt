package org.sonora.android.playback.auto

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService.LibraryParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.sonora.android.R
import org.sonora.android.api.DeezerClient
import org.sonora.android.api.Track
import org.sonora.android.api.YouTubeMusicClient
import org.sonora.android.data.EuropeChartsCache
import org.sonora.android.data.MusicRepository
import org.sonora.android.playback.DownloadManager
import org.sonora.android.playback.PlaybackState

/** A playback request coming from the car, already resolved to LFly tracks. */
internal data class AutoStart(
    val tracks: List<Track>,
    val index: Int,
    val label: String?,
    val playlistId: String? = null
)

/** Connects the pure browse tree to LFly's data and to Media3 items. */
internal class AutoLibrary(
    private val context: Context,
    private val repository: MusicRepository,
    private val youTubeMusic: YouTubeMusicClient,
    private val deezer: DeezerClient
) {

    private companion object {
        const val TAG = "AutoLibrary"
        const val LOAD_TIMEOUT_MS = 3_000L
        const val SEARCH_LIMIT = 25
        const val CHART_LIMIT = 50
        const val SEARCH_SUPPORTED_KEY = "android.media.browse.SEARCH_SUPPORTED"
    }

    private val labels: AutoLabels
        get() = AutoLabels(
            home = context.getString(R.string.auto_home),
            favorites = context.getString(R.string.auto_favorites),
            playlists = context.getString(R.string.auto_playlists),
            downloads = context.getString(R.string.auto_downloads),
            history = context.getString(R.string.auto_history),
            charts = context.getString(R.string.auto_charts)
        )

    private var lastSearchQuery: String? = null
    private var lastSearchResults: List<Track> = emptyList()
    private var fallbackCharts: List<Track> = emptyList()

    val rootItem: MediaItem
        get() = folderItem(AutoNode.Folder(AutoIds.ROOT, context.getString(R.string.app_name)))

    /** Grid of covers for folders and a list for songs, plus the search button. */
    val rootParams: LibraryParams
        get() = LibraryParams.Builder()
            .setExtras(
                Bundle().apply {
                    putInt(
                        MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                        MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
                    )
                    putInt(
                        MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                        MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
                    )
                    putBoolean(SEARCH_SUPPORTED_KEY, true)
                }
            )
            .build()

    suspend fun children(folderId: String): List<MediaItem>? {
        val nodes = AutoBrowseTree.children(folderId, snapshot(folderId), labels) ?: return null
        return nodes.map(::toMediaItem)
    }

    suspend fun item(mediaId: String): MediaItem? {
        if (mediaId == AutoIds.ROOT) return rootItem
        val parsed = AutoIds.parseTrack(mediaId)
        if (parsed != null) {
            val (folderId, trackId) = parsed
            val track = AutoBrowseTree.tracksFor(folderId, snapshot(folderId))?.firstOrNull { it.id == trackId }
            return track?.let { toMediaItem(AutoNode.Song(mediaId, it)) }
        }
        return (AutoBrowseTree.root(labels) + knownFolders())
            .firstOrNull { it.id == mediaId }
            ?.let(::folderItem)
    }

    suspend fun search(query: String): List<MediaItem> =
        searchTracks(query).map { toMediaItem(AutoNode.Song(AutoIds.track(AutoIds.SEARCH, it.id), it)) }

    /** Null means "nothing to start": the car simply resumes what is already loaded. */
    suspend fun resolveStart(request: MediaItem?, playback: PlaybackState): AutoStart? {
        val query = request?.requestMetadata?.searchQuery?.trim().orEmpty()
        val mediaId = request?.mediaId.orEmpty()
        if (mediaId.isEmpty()) return resolveVoice(query, playback)

        val parsed = AutoIds.parseTrack(mediaId)
        if (parsed == null) {
            val index = playback.queue.indexOfFirst { it.id == mediaId }
            return if (index >= 0) AutoStart(playback.queue, index, playback.sourceContext) else null
        }

        val (folderId, trackId) = parsed
        val tracks = AutoBrowseTree.tracksFor(folderId, snapshot(folderId)).orEmpty()
        val index = tracks.indexOfFirst { it.id == trackId }
        if (index < 0) return null
        return AutoStart(tracks, index, labelFor(folderId), AutoIds.playlistIdOf(folderId))
    }

    private suspend fun resolveVoice(query: String, playback: PlaybackState): AutoStart? {
        if (query.isNotEmpty()) {
            val results = searchTracks(query)
            return results.takeIf { it.isNotEmpty() }?.let { AutoStart(it, 0, query) }
        }
        if (playback.currentTrack != null) return null
        val snapshot = snapshot(AutoIds.ROOT)
        val tracks = AutoBrowseTree.tracksFor(AutoIds.HISTORY, snapshot).orEmpty()
            .ifEmpty { AutoBrowseTree.tracksFor(AutoIds.FAVORITES, snapshot).orEmpty() }
        return tracks.takeIf { it.isNotEmpty() }?.let { AutoStart(it, 0, null) }
    }

    private suspend fun searchTracks(query: String): List<Track> {
        if (query == lastSearchQuery) return lastSearchResults
        val disliked = repository.dislikedTrackIds.value
        val online = withContext(Dispatchers.IO) {
            youTubeMusic.searchTracks(query, SEARCH_LIMIT).getOrNull().orEmpty()
        }
        val results = online.ifEmpty { searchOffline(query) }.filter { it.id !in disliked }
        lastSearchQuery = query
        lastSearchResults = results
        return results
    }

    private fun searchOffline(query: String): List<Track> =
        (offlineTracks() + repository.favorites.value)
            .filter {
                it.title.contains(query, ignoreCase = true) || it.artist.contains(query, ignoreCase = true)
            }
            .distinctBy { it.id }

    private suspend fun snapshot(folderId: String): AutoLibrarySnapshot {
        withTimeoutOrNull(LOAD_TIMEOUT_MS) { repository.awaitLibraryLoaded() }
        return AutoLibrarySnapshot(
            favorites = repository.favorites.value,
            playlists = repository.playlists.value,
            mixes = repository.mixes.value,
            downloads = offlineTracks(),
            history = repository.history.value,
            charts = if (folderId == AutoIds.CHARTS) charts() else emptyList(),
            searchResults = lastSearchResults,
            dislikedTrackIds = repository.dislikedTrackIds.value
        )
    }

    private fun offlineTracks(): List<Track> =
        AutoBrowseTree.mergeOffline(DownloadManager.downloadedTracks.value, repository.localTracks.value)

    private suspend fun charts(): List<Track> {
        val cached = withContext(Dispatchers.IO) { EuropeChartsCache.read(context) }
            ?.tracks
            ?.map { it.track }
            .orEmpty()
        if (cached.isNotEmpty()) return cached
        if (fallbackCharts.isEmpty()) {
            fallbackCharts = deezer.getChartTopTracks(CHART_LIMIT)
                .onFailure { Log.w(TAG, "Chart fallback failed: ${it.message}") }
                .getOrNull()
                .orEmpty()
        }
        return fallbackCharts
    }

    private suspend fun knownFolders(): List<AutoNode.Folder> {
        val snapshot = snapshot(AutoIds.ROOT)
        return listOf(AutoIds.HOME, AutoIds.PLAYLISTS)
            .flatMap { AutoBrowseTree.children(it, snapshot, labels).orEmpty() }
            .filterIsInstance<AutoNode.Folder>()
    }

    private fun labelFor(folderId: String): String? = when {
        folderId == AutoIds.FAVORITES -> labels.favorites
        folderId == AutoIds.DOWNLOADS -> labels.downloads
        folderId == AutoIds.HISTORY -> labels.history
        folderId == AutoIds.CHARTS -> labels.charts
        else -> {
            val playlistId = AutoIds.playlistIdOf(folderId)
            val mixId = AutoIds.mixIdOf(folderId)
            playlistId?.let { id -> repository.playlists.value.firstOrNull { it.id == id }?.name }
                ?: mixId?.let { id -> repository.mixes.value.firstOrNull { it.id == id }?.name }
        }
    }

    private fun toMediaItem(node: AutoNode): MediaItem = when (node) {
        is AutoNode.Folder -> folderItem(node)
        is AutoNode.Song -> songItem(node)
    }

    private fun folderItem(folder: AutoNode.Folder): MediaItem {
        val artwork = folder.icon?.let(::iconUri) ?: AutoArtworkProvider.uriFor(context, folder.coverUrl)
        val metadata = MediaMetadata.Builder()
            .setTitle(folder.title)
            .setSubtitle(folder.subtitle)
            .setArtworkUri(artwork)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            .build()
        return MediaItem.Builder().setMediaId(folder.id).setMediaMetadata(metadata).build()
    }

    private fun songItem(song: AutoNode.Song): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(song.track.title)
            .setArtist(song.track.artist)
            .setSubtitle(song.track.artist)
            .setAlbumTitle(song.track.album)
            .setArtworkUri(AutoArtworkProvider.uriFor(context, song.track.coverUrl))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
        return MediaItem.Builder().setMediaId(song.id).setMediaMetadata(metadata).build()
    }

    private fun iconUri(icon: AutoIcon): Uri {
        val resource = when (icon) {
            AutoIcon.HOME -> R.drawable.ic_auto_home
            AutoIcon.FAVORITES -> R.drawable.ic_heart_filled
            AutoIcon.PLAYLISTS -> R.drawable.ic_auto_playlists
            AutoIcon.DOWNLOADS -> R.drawable.ic_auto_downloads
            AutoIcon.HISTORY -> R.drawable.ic_auto_history
            AutoIcon.CHARTS -> R.drawable.ic_auto_charts
        }
        return Uri.Builder()
            .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
            .authority(context.packageName)
            .appendPath(context.resources.getResourceTypeName(resource))
            .appendPath(context.resources.getResourceEntryName(resource))
            .build()
    }
}
