package org.sonora.android.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.sonora.android.R
import org.sonora.android.api.Track
import org.sonora.android.data.MusicRepository
import org.sonora.android.playback.DownloadManager
import org.sonora.android.playback.PlaybackManager
import kotlin.random.Random
import org.sonora.android.wear.WearProtocol as P

/**
 * The phone's library as the watch browses it: liked songs, recently played, downloads (when
 * there are any) and the playlists, each one opened and played on the phone's player. Works with
 * LFly closed: Play services wakes the app, which waits for the library to load from disk.
 */
internal object WatchLibrary {
    private const val TAG = "LFlyWear"

    /** A cold start reads the library from disk first; past this, whatever is loaded is sent. */
    private const val LOAD_WAIT_MS = 5_000L

    private class Collection(
        val id: String,
        val title: String,
        val kind: String,
        val tracks: List<Track>,
        /** What the player says it plays from, as the phone's library screen says it. */
        val playingFrom: String,
        /** The list's own cover, else its first song's; liked songs get the watch's own tile. */
        val cover: String? = tracks.firstOrNull { !it.coverUrl.isNullOrBlank() }?.coverUrl
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Publishes [P.PATH_LIBRARY]. */
    fun publishLibrary(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val all = collections(app)
            val rows = all.map { collection ->
                DataMap().apply {
                    putString(P.KEY_COLLECTION_ID, collection.id)
                    putString(P.KEY_TITLE, collection.title)
                    putString(P.KEY_SUBTITLE, count(app, collection.tracks.size))
                    putString(P.KEY_COLLECTION_KIND, collection.kind)
                }
            }
            val art = DataMap().also { map ->
                RowArt.putAll(app, map, all.map { it.cover.takeIf { _ -> it.kind != P.COLLECTION_KIND_LIKED } })
            }
            put(app, P.PATH_LIBRARY) {
                putAll(art)
                putDataMapArrayList(P.KEY_COLLECTIONS, ArrayList(rows))
            }
        }
    }

    /** Publishes [P.PATH_COLLECTION] for [id]: its first [P.COLLECTION_MAX_ROWS] songs (none when unknown). */
    fun publishCollection(context: Context, id: String) {
        val app = context.applicationContext
        scope.launch {
            val collection = collections(app).firstOrNull { it.id == id }
            val tracks = collection?.tracks.orEmpty()
            val rows = tracks.take(P.COLLECTION_MAX_ROWS).mapIndexed { index, track ->
                DataMap().apply {
                    putInt(P.KEY_INDEX, index)
                    putString(P.KEY_TITLE, track.title)
                    putString(P.KEY_ARTIST, track.artist)
                }
            }
            val art = DataMap().also { RowArt.putAll(app, it, tracks.map { track -> track.coverUrl }) }
            put(app, P.PATH_COLLECTION) {
                putAll(art)
                putString(P.KEY_COLLECTION_ID, id)
                collection?.let {
                    putString(P.KEY_TITLE, it.title)
                    putString(P.KEY_SUBTITLE, count(app, tracks.size))
                    putString(P.KEY_COLLECTION_KIND, it.kind)
                }
                putDataMapArrayList(P.KEY_ITEMS, ArrayList(rows))
            }
        }
    }

    /** "<collection id>\n<index>": plays that collection on the phone from that row, -1 shuffled. */
    fun play(context: Context, payload: String) {
        val app = context.applicationContext
        val id = payload.substringBefore('\n').trim()
        val index = payload.substringAfter('\n', "0").trim().toIntOrNull() ?: 0
        scope.launch {
            val collection = collections(app).firstOrNull { it.id == id } ?: return@launch
            val tracks = collection.tracks
            if (tracks.isEmpty()) return@launch
            withContext(Dispatchers.Main) {
                val manager = PlaybackManager.getInstance(app)
                if (collection.kind == P.COLLECTION_KIND_PLAYLIST) {
                    MusicRepository.getInstance(app).recordPlaylistPlay(id.removePrefix(P.COLLECTION_PLAYLIST_PREFIX))
                }
                val start = if (index < 0) {
                    if (!manager.playbackState.value.isShuffle) manager.toggleShuffle()
                    Random.nextInt(tracks.size)
                } else {
                    index.coerceIn(0, tracks.lastIndex)
                }
                Log.i(TAG, "Watch plays '${collection.title}' from #$start")
                manager.playQueue(tracks, start, collection.playingFrom)
            }
        }
    }

    private suspend fun collections(app: Context): List<Collection> {
        val repository = MusicRepository.getInstance(app)
        withTimeoutOrNull(LOAD_WAIT_MS) { repository.awaitLibraryLoaded() }
        val list = ArrayList<Collection>()
        list += Collection(
            P.COLLECTION_LIKED, app.getString(R.string.library_favorites_title), P.COLLECTION_KIND_LIKED,
            repository.favorites.value, app.getString(R.string.main_context_favorites)
        )
        list += Collection(
            P.COLLECTION_RECENT, app.getString(R.string.library_history_subtitle), P.COLLECTION_KIND_RECENT,
            repository.history.value, app.getString(R.string.main_context_history)
        )
        val downloads = downloads(repository)
        if (downloads.isNotEmpty()) {
            list += Collection(
                P.COLLECTION_DOWNLOADS, app.getString(R.string.library_downloads_title), P.COLLECTION_KIND_DOWNLOADS,
                downloads, app.getString(R.string.main_context_downloaded)
            )
        }
        repository.playlists.value.forEach { playlist ->
            list += Collection(
                P.COLLECTION_PLAYLIST_PREFIX + playlist.id, playlist.name, P.COLLECTION_KIND_PLAYLIST,
                playlist.tracks, app.getString(R.string.main_context_playlist_fmt, playlist.name),
                cover = playlist.coverUrl?.takeIf { it.isNotBlank() }
                    ?: playlist.tracks.firstOrNull { !it.coverUrl.isNullOrBlank() }?.coverUrl
            )
        }
        return list
    }

    /** The app's downloads and the songs on the phone, once each, as the library's offline list shows them. */
    private fun downloads(repository: MusicRepository): List<Track> {
        val seen = HashSet<String>()
        return (DownloadManager.downloadedTracks.value + repository.localTracks.value).filter { track ->
            seen.add(track.streamUrl?.removePrefix("file://")?.takeIf { it.isNotBlank() } ?: track.id)
        }
    }

    private fun count(app: Context, songs: Int): String =
        app.resources.getQuantityString(R.plurals.watch_collection_songs, songs, songs)

    /** Blocking put of a DataItem the watch asked for, stamped so asking again always lands. */
    private fun put(app: Context, path: String, fill: DataMap.() -> Unit) {
        val request = PutDataMapRequest.create(path)
        request.dataMap.apply {
            fill()
            putLong(P.KEY_STAMP, System.currentTimeMillis())
        }
        runCatching {
            Tasks.await(Wearable.getDataClient(app).putDataItem(request.asPutDataRequest().setUrgent()))
        }.onFailure { Log.w(TAG, "Could not send $path: ${it.message}") }
    }
}
