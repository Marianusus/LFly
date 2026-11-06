/*
 * The Spotify library client is adapted from BitChord (https://github.com/kushagrasinghx/BitChord,
 * GPL-3.0): data/spotify/SpotifyLibrary.kt.
 */
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.sonora.android.R
import org.sonora.android.api.ArtistCredits
import org.sonora.android.api.HttpClientProvider
import org.sonora.android.api.Track
import org.sonora.android.auth.SpotifyAuth
import org.sonora.android.data.MusicRepository
import org.sonora.android.data.Playlist

/** A playlist in the listener's Spotify library. */
data class SpotifyPlaylistRef(val id: String, val name: String, val owner: String?, val imageUrl: String?)

/** Spotify's private web-player API, as the web player itself calls it. */
internal object SpotifyWebApi {
    private const val GQL = "https://api-partner.spotify.com/pathfinder/v2/query"
    private const val LIBRARY = "973e511ca44261fda7eebac8b653155e7caee3675abb4fb110cc1b8c78b091c3"
    private const val PLAYLIST = "346811f856fb0b7e4f6c59f8ebea78dd081c6e2fb01b77c954b26259d5fc6763"
    private const val LIKED = "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240"
    const val LIKED_ID = "liked"
    const val LIKED_COVER = "https://misc.scdn.co/liked-songs/liked-songs-640.png"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    class SignedOut : Exception("Spotify sign-in expired")

    /** Name and picture of the connected account, or null. */
    suspend fun profile(): Pair<String, String?>? {
        val headers = headers()
        val request = Request.Builder().url("https://api.spotify.com/v1/me").apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        val body = runCatching {
            HttpClientProvider.okHttpClient.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull() ?: return null
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val name = root.optString("display_name").takeIf { it.isNotBlank() && it != "null" } ?: root.optString("id")
        val avatar = root.optJSONArray("images")?.let { images ->
            (0 until images.length()).mapNotNull { images.optJSONObject(it) }
                .maxByOrNull { it.optInt("width") }?.optString("url")?.takeIf { it.isNotBlank() }
        }
        return name.takeIf { it.isNotBlank() }?.let { it to avatar }
    }

    /** Every playlist in the library, Liked Songs first. */
    suspend fun playlists(likedName: String): List<SpotifyPlaylistRef> {
        val headers = headers()
        val all = ArrayList<SpotifyPlaylistRef>()
        var offset = 0
        while (true) {
            val variables = JSONObject()
                .put("filters", JSONArray().put("Playlists"))
                .put("order", JSONObject.NULL)
                .put("textFilter", "")
                .put("features", JSONArray(listOf("LIKED_SONGS", "YOUR_EPISODES_V2", "PRERELEASES", "EVENTS")))
                .put("limit", 50)
                .put("offset", offset)
                .put("flatten", true)
                .put("expandedFolders", JSONArray())
                .put("folderUri", JSONObject.NULL)
                .put("includeFoldersWhenFlattening", false)
            val library = gql("libraryV3", LIBRARY, variables, headers).path("data", "me", "libraryV3")
                ?: error("Spotify library response was empty")
            val items = library.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val wrapper = items.optJSONObject(i)?.optJSONObject("item") ?: continue
                if (!wrapper.optString("__typename").contains("Playlist", ignoreCase = true)) continue
                val data = wrapper.optJSONObject("data") ?: continue
                if (data.optString("__typename") != "Playlist") continue
                val uri = wrapper.optString("_uri").takeIf { it.isNotBlank() } ?: continue
                val name = data.optString("name").takeIf { it.isNotBlank() } ?: continue
                all += SpotifyPlaylistRef(
                    id = uri.substringAfterLast(':'),
                    name = name,
                    owner = data.path("ownerV2", "data")?.optString("name")?.takeIf { it.isNotBlank() },
                    imageUrl = largest(data.optJSONObject("images"))
                )
            }
            offset += items.length()
            if (items.length() == 0 || offset >= library.optInt("totalCount", offset)) break
        }
        return listOf(SpotifyPlaylistRef(LIKED_ID, likedName, null, LIKED_COVER)) + all
    }

    /** Every song of a playlist ([LIKED_ID] for Liked Songs), as LFly tracks resolved when played. */
    suspend fun tracks(playlistId: String): List<Track> {
        val headers = headers()
        val all = ArrayList<Track>()
        var offset = 0
        while (true) {
            val liked = playlistId == LIKED_ID
            val root = if (liked) {
                gql("fetchLibraryTracks", LIKED, JSONObject().put("offset", offset).put("limit", 100), headers)
            } else {
                gql(
                    "fetchPlaylist", PLAYLIST,
                    JSONObject().put("uri", "spotify:playlist:$playlistId").put("offset", offset).put("limit", 100)
                        .put("enableWatchFeedEntrypoint", false),
                    headers
                )
            }
            val content = (if (liked) root.path("data", "me", "library", "tracks") else root.path("data", "playlistV2", "content"))
                ?: error("Spotify playlist response was empty")
            val items = content.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val entry = items.optJSONObject(i) ?: continue
                val wrapper = (if (liked) entry.optJSONObject("track") else entry.optJSONObject("itemV2")) ?: continue
                val data = wrapper.optJSONObject("data") ?: continue
                track(data, wrapper.optString("_uri"))?.let { all += it }
            }
            offset += items.length()
            if (items.length() == 0 || offset >= content.optInt("totalCount", offset)) break
        }
        return all
    }

    private fun track(data: JSONObject, wrapperUri: String): Track? {
        val title = data.optString("name").takeIf { it.isNotBlank() } ?: return null
        val uri = data.optString("uri").takeIf { it.startsWith("spotify:track:") }
            ?: wrapperUri.takeIf { it.startsWith("spotify:track:") } ?: return null
        val artists = data.optJSONObject("artists")?.optJSONArray("items")?.let { list ->
            (0 until list.length()).mapNotNull { list.optJSONObject(it)?.path("profile")?.optString("name")?.takeIf { n -> n.isNotBlank() } }
        }.orEmpty()
        val album = data.optJSONObject("albumOfTrack")
        val duration = (data.optJSONObject("trackDuration") ?: data.optJSONObject("duration"))?.optLong("totalMilliseconds") ?: 0L
        val artist = artists.joinToString(", ")
        return Track(
            id = "spotify_" + uri.substringAfterLast(':'),
            title = title,
            artist = artist,
            album = album?.optString("name")?.takeIf { it.isNotBlank() },
            durationMs = duration,
            coverUrl = album?.optJSONObject("coverArt")?.let { largest(JSONObject().put("items", JSONArray().put(it))) },
            streamUrl = null,
            provider = "Spotify",
            artists = ArtistCredits.fromText(artist).takeIf { it.isNotEmpty() }
        )
    }

    private suspend fun headers(): Map<String, String> {
        val tokens = SpotifyAuth.tokens() ?: throw SignedOut()
        return buildMap {
            put("Authorization", "Bearer ${tokens.bearer}")
            put("Accept", "application/json")
            put("app-platform", "WebPlayer")
            put("Origin", "https://open.spotify.com")
            put("Referer", "https://open.spotify.com/")
            put("User-Agent", SpotifyAuth.USER_AGENT)
            tokens.clientToken?.let { put("Client-Token", it) }
        }
    }

    private fun gql(operation: String, hash: String, variables: JSONObject, headers: Map<String, String>): JSONObject {
        val body = JSONObject()
            .put("variables", variables)
            .put("operationName", operation)
            .put("extensions", JSONObject().put("persistedQuery", JSONObject().put("version", 1).put("sha256Hash", hash)))
        val request = Request.Builder()
            .url(GQL)
            .post(body.toString().toRequestBody(JSON))
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        val text = HttpClientProvider.okHttpClient.newCall(request).execute().use { response ->
            if (response.code == 401) throw SignedOut()
            if (!response.isSuccessful) error("Spotify $operation failed (${response.code})")
            response.body?.string().orEmpty()
        }
        val root = JSONObject(text)
        root.optJSONArray("errors")?.optJSONObject(0)?.optString("message")?.takeIf { it.isNotBlank() }?.let { error(it) }
        return root
    }

    /** The widest source of an image list's first entry: Spotify lists sizes in no fixed order. */
    private fun largest(images: JSONObject?): String? {
        val sources = images?.optJSONArray("items")?.optJSONObject(0)?.optJSONArray("sources") ?: return null
        return (0 until sources.length()).mapNotNull { sources.optJSONObject(it) }
            .filter { it.optString("url").isNotBlank() }
            .maxByOrNull { it.optInt("width") }?.optString("url")
    }

    private fun JSONObject.path(vararg keys: String): JSONObject? =
        keys.fold(this as JSONObject?) { node, key -> node?.optJSONObject(key) }
}

/**
 * Brings the listener's Spotify playlists into LFly as native playlists with the id
 * `spl_<playlist id>`: a second import updates them instead of making copies. Liked Songs join the
 * favourites. Songs keep
 * their Spotify ids and are matched to a playable stream when played, as a playlist imported from a
 * link already is.
 */
class SpotifyLibrarySync private constructor(private val context: Context) {
    companion object {
        private const val TAG = "SpotifyLibrarySync"
        private const val PREFS = "spotify_library_sync"
        private const val KEY_LAST_SYNC = "last_sync_at"
        private const val KEY_LAST_PLAYLISTS = "last_playlists"
        private const val KEY_LAST_SONGS = "last_liked"
        const val PLAYLIST_PREFIX = "spl_"

        @Volatile
        private var instance: SpotifyLibrarySync? = null

        fun getInstance(context: Context): SpotifyLibrarySync =
            instance ?: synchronized(this) {
                instance ?: SpotifyLibrarySync(context.applicationContext).also { instance = it }
            }
    }

    private val repository = MusicRepository.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var job: Job? = null

    private val _state = MutableStateFlow(
        LibrarySyncState(
            lastSyncAt = prefs.getLong(KEY_LAST_SYNC, 0L).takeIf { it > 0 },
            lastPlaylists = prefs.getInt(KEY_LAST_PLAYLISTS, 0),
            lastLiked = prefs.getInt(KEY_LAST_SONGS, 0)
        )
    )

    val state: StateFlow<LibrarySyncState> = _state.asStateFlow()

    /** Learns the account's name and picture, for the settings row. */
    fun refreshProfile() {
        scope.launch {
            runCatching { SpotifyWebApi.profile() }.getOrNull()?.let { (name, avatar) -> SpotifyAuth.updateProfile(name, avatar) }
        }
    }

    /** Imports every playlist of the connected account; one import at a time. */
    fun sync() {
        if (job?.isActive == true || !SpotifyAuth.isConnected) return
        job = scope.launch {
            _state.value = _state.value.copy(running = true, failed = false, playlistIndex = 0, playlistCount = 0)
            try {
                if (SpotifyAuth.account.value?.name.isNullOrBlank()) {
                    runCatching { SpotifyWebApi.profile() }.getOrNull()?.let { (name, avatar) -> SpotifyAuth.updateProfile(name, avatar) }
                }
                val playlists = SpotifyWebApi.playlists(context.getString(R.string.spotify_liked_songs))
                _state.value = _state.value.copy(playlistCount = playlists.size)
                var imported = 0
                var songs = 0
                var liked = 0
                playlists.forEachIndexed { index, remote ->
                    _state.value = _state.value.copy(playlistIndex = index + 1)
                    val tracks = runCatching { SpotifyWebApi.tracks(remote.id) }.getOrElse {
                        if (it is SpotifyWebApi.SignedOut) throw it
                        Log.w(TAG, "Playlist '${remote.name}' could not be read: ${it.message}")
                        return@forEachIndexed
                    }
                    if (remote.id == SpotifyWebApi.LIKED_ID) {
                        // Spotify's Liked Songs are LFly's favourites, not a playlist. They only grow:
                        // a song liked only in LFly stays.
                        liked = tracks.size
                        val added = repository.addFavorites(tracks)
                        Log.i(TAG, "Liked Songs: ${tracks.size} on Spotify, $added new favourites")
                        return@forEachIndexed
                    }
                    val id = PLAYLIST_PREFIX + remote.id
                    val existing = repository.playlists.value.firstOrNull { it.id == id }
                    val now = System.currentTimeMillis()
                    repository.savePlaylist(
                        Playlist(
                            id = id,
                            name = existing?.name?.takeIf { it.isNotBlank() } ?: remote.name,
                            description = existing?.description ?: remote.owner,
                            coverUrl = existing?.coverUrl ?: remote.imageUrl,
                            tracks = tracks.distinctBy { it.id },
                            createdAt = existing?.createdAt ?: now,
                            updatedAt = now
                        )
                    )
                    imported++
                    songs += tracks.size
                }
                // The first version imported Liked Songs as a playlist: its songs join the favourites.
                repository.playlists.value.firstOrNull { it.id == PLAYLIST_PREFIX + SpotifyWebApi.LIKED_ID }?.let { old ->
                    repository.addFavorites(old.tracks)
                    repository.deletePlaylist(old.id)
                }
                val now = System.currentTimeMillis()
                prefs.edit().putLong(KEY_LAST_SYNC, now).putInt(KEY_LAST_PLAYLISTS, imported).putInt(KEY_LAST_SONGS, liked).apply()
                Log.i(TAG, "Spotify library imported: $imported playlists, $songs songs, $liked liked")
                _state.value = LibrarySyncState(lastSyncAt = now, lastPlaylists = imported, lastLiked = liked)
            } catch (e: SpotifyWebApi.SignedOut) {
                Log.w(TAG, "Spotify refused the saved login")
                SpotifyAuth.markExpired()
                _state.value = _state.value.copy(running = false, failed = true)
            } catch (e: Exception) {
                Log.w(TAG, "Spotify import failed: ${e.message}")
                _state.value = _state.value.copy(running = false, failed = true)
            }
        }
    }

    /** Forgets the last import's summary, when the account is disconnected. Imported playlists stay. */
    fun reset() {
        job?.cancel()
        prefs.edit().clear().apply()
        _state.value = LibrarySyncState()
    }
}
