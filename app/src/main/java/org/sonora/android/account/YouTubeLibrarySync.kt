package org.sonora.android.account

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.sonora.android.api.Track
import org.sonora.android.api.YouTubeMusicClient
import org.sonora.android.auth.YouTubeAuth
import org.sonora.android.data.MusicRepository
import org.sonora.android.data.Playlist

/** Where the library import is, for the account section. */
data class LibrarySyncState(
    val running: Boolean = false,
    /** What the import is doing, as "playlist 3 of 12"; null while it reads the liked songs. */
    val playlistIndex: Int = 0,
    val playlistCount: Int = 0,
    val lastSyncAt: Long? = null,
    val lastPlaylists: Int = 0,
    val lastLiked: Int = 0,
    val failed: Boolean = false
)

/**
 * Brings the listener's YouTube Music library into LFly, as BitChord does: every playlist in the
 * library becomes a native playlist, and Liked Music joins the favourites. Playlists keep the id
 * `ytm_<playlist id>`, so a second import updates them instead of making copies. YouTube Music is
 * the source of truth for their songs; the favourites only grow (a song liked only in LFly stays).
 *
 * The other way round: a heart on a YouTube song likes it on YouTube Music ([onFavoriteChanged]),
 * and a song added to an imported playlist is added there too ([onAddedToPlaylist]).
 */
class YouTubeLibrarySync private constructor(context: Context) {
    companion object {
        private const val TAG = "YouTubeLibrarySync"
        private const val PREFS = "youtube_library_sync"
        private const val KEY_LAST_SYNC = "last_sync_at"
        private const val KEY_LAST_PLAYLISTS = "last_playlists"
        private const val KEY_LAST_LIKED = "last_liked"
        const val PLAYLIST_PREFIX = "ytm_"
        private const val VIDEO_PREFIX = "yt_"

        @Volatile
        private var instance: YouTubeLibrarySync? = null

        fun getInstance(context: Context): YouTubeLibrarySync =
            instance ?: synchronized(this) {
                instance ?: YouTubeLibrarySync(context.applicationContext).also { instance = it }
            }

        /** The YouTube Music id of an imported playlist, or null for a playlist made in LFly. */
        fun youTubePlaylistId(playlistId: String): String? =
            playlistId.takeIf { it.startsWith(PLAYLIST_PREFIX) }?.removePrefix(PLAYLIST_PREFIX)

        /**
         * [remote] as LFly keeps it: the same id as last time, the playlist's own creation date and
         * any cover the listener chose, and YouTube Music's songs.
         */
        internal fun merged(existing: Playlist?, remoteId: String, title: String, author: String?, cover: String?, tracks: List<Track>, now: Long): Playlist =
            Playlist(
                id = PLAYLIST_PREFIX + remoteId,
                name = existing?.name?.takeIf { it.isNotBlank() } ?: title,
                // The first version kept "Author • N tracks"; such a stale count is replaced.
                description = existing?.description?.takeUnless { it.contains(" • ") } ?: author,
                coverUrl = existing?.coverUrl ?: cover,
                tracks = tracks.distinctBy { it.id },
                createdAt = existing?.createdAt ?: now,
                updatedAt = now
            )
    }

    private val repository = MusicRepository.getInstance(context)
    private val client = YouTubeMusicClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var job: Job? = null

    private val _state = MutableStateFlow(
        LibrarySyncState(
            lastSyncAt = prefs.getLong(KEY_LAST_SYNC, 0L).takeIf { it > 0 },
            lastPlaylists = prefs.getInt(KEY_LAST_PLAYLISTS, 0),
            lastLiked = prefs.getInt(KEY_LAST_LIKED, 0)
        )
    )
    val state: StateFlow<LibrarySyncState> = _state.asStateFlow()

    /** Imports the library of the account in use; one import at a time. */
    fun sync() {
        if (job?.isActive == true) return
        val account = YouTubeAuth.active ?: return
        job = scope.launch {
            _state.value = _state.value.copy(running = true, failed = false, playlistIndex = 0, playlistCount = 0)
            try {
                val liked = client.likedSongs(account).getOrElse {
                    Log.w(TAG, "Liked Music could not be read: ${it.message}")
                    emptyList()
                }
                val addedLiked = repository.addFavorites(liked)
                val playlists = client.libraryPlaylists(account).getOrThrow()
                _state.value = _state.value.copy(playlistCount = playlists.size)
                var imported = 0
                playlists.forEachIndexed { index, remote ->
                    _state.value = _state.value.copy(playlistIndex = index + 1)
                    val id = remote.playlistId ?: return@forEachIndexed
                    val detail = client.getYouTubePlaylist(id, account = account).getOrElse {
                        Log.w(TAG, "Playlist '${remote.title}' could not be read: ${it.message}")
                        return@forEachIndexed
                    }
                    val existing = repository.playlists.value.firstOrNull { it.id == PLAYLIST_PREFIX + id }
                    repository.savePlaylist(
                        merged(existing, id, remote.title, remote.author, remote.thumbnailUrl ?: detail.playlist.thumbnailUrl, detail.tracks, System.currentTimeMillis())
                    )
                    imported++
                }
                // Episodes for Later was imported as a playlist by the first version; it holds podcasts.
                repository.playlists.value.firstOrNull { it.id == PLAYLIST_PREFIX + "SE" }?.let { repository.deletePlaylist(it.id) }
                val now = System.currentTimeMillis()
                prefs.edit().putLong(KEY_LAST_SYNC, now).putInt(KEY_LAST_PLAYLISTS, imported).putInt(KEY_LAST_LIKED, liked.size).apply()
                Log.i(TAG, "Library imported: $imported playlists, ${liked.size} liked songs ($addedLiked new)")
                _state.value = LibrarySyncState(lastSyncAt = now, lastPlaylists = imported, lastLiked = liked.size)
            } catch (e: Exception) {
                Log.w(TAG, "Library import failed: ${e.message}")
                _state.value = _state.value.copy(running = false, failed = true)
            }
        }
    }

    /** A heart on a YouTube song likes it on YouTube Music too; off, it takes the like back. Best effort. */
    fun onFavoriteChanged(track: Track, favourite: Boolean) {
        val account = YouTubeAuth.active ?: return
        val videoId = track.id.takeIf { it.startsWith(VIDEO_PREFIX) }?.removePrefix(VIDEO_PREFIX) ?: return
        scope.launch {
            val done = client.rate(account, videoId, if (favourite) true else null)
            if (done) Log.d(TAG, "${if (favourite) "Liked" else "Unliked"} '${track.title}' on YouTube Music")
            else Log.w(TAG, "Could not ${if (favourite) "like" else "unlike"} '${track.title}' on YouTube Music")
        }
    }

    /** A YouTube song added to an imported playlist is added on YouTube Music too. Best effort. */
    fun onAddedToPlaylist(playlistId: String, track: Track) {
        val account = YouTubeAuth.active ?: return
        val remote = youTubePlaylistId(playlistId) ?: return
        val videoId = track.id.takeIf { it.startsWith(VIDEO_PREFIX) }?.removePrefix(VIDEO_PREFIX) ?: return
        scope.launch {
            if (!client.addToPlaylist(account, remote, videoId)) {
                Log.w(TAG, "Could not add '${track.title}' to the YouTube Music playlist")
            }
        }
    }
}
