package org.sonora.android.api

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.sonora.android.data.MusicRepository
import org.sonora.android.data.Playlist
import java.io.IOException
import java.util.UUID

/**
 * Raw track information extracted from Spotify embed metadata.
 */
data class SpotifyRawTrack(
    val id: String,
    val title: String,
    val artist: String,
    val durationMs: Long = 0L,
    val previewUrl: String? = null,
    val coverUrl: String? = null,
    val album: String? = null
)

/**
 * Public metadata and tracks of a Spotify playlist extracted without login or API keys.
 */
data class SpotifyPlaylistInfo(
    val id: String,
    val title: String,
    val description: String?,
    val coverUrl: String?,
    val tracks: List<SpotifyRawTrack>
)

/**
 * High-performance 1-click Spotify Playlist Importer.
 *
 * Extracts playlist metadata and full tracklists via Spotify public embed pages and oEmbed,
 * without requiring user login or Spotify developer credentials.
 * Matches and resolves each track into a Sonora [Track] using Deezer and JioSaavn catalogs,
 * and automatically saves native Sonora playlists into [MusicRepository].
 */
open class SpotifyPlaylistImporter(
    private val deezerClient: DeezerClient = DeezerClient(),
    private val saavnClient: JioSaavnFallbackClient = JioSaavnFallbackClient(),
    private val repository: MusicRepository? = null,
    private val httpClient: OkHttpClient = HttpClientProvider.okHttpClient
) {

    companion object {
        private const val TAG = "SpotifyImporter"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

        private val SPOTIFY_PLAYLIST_REGEX = Regex(
            """(?:(?:https?://)?(?:open\.)?spotify\.com/(?:(?:[a-zA-Z0-9_-]+/)*)?playlist/|spotify:(?:user:[^:]+:)?playlist:)([a-zA-Z0-9]{15,35})"""
        )
        private val RAW_ID_REGEX = Regex("""^[a-zA-Z0-9]{15,35}$""")
    }

    /**
     * Extracts a Spotify playlist ID from a URL, URI or plain ID string.
     */
    fun extractPlaylistId(input: String): String? {
        val trimmed = input.trim()
        if (RAW_ID_REGEX.matches(trimmed)) return trimmed
        val match = SPOTIFY_PLAYLIST_REGEX.find(trimmed)
        return match?.groupValues?.get(1)
    }

    /**
     * Resolves shortened links (e.g., spotify.link) via HTTP redirect and extracts the playlist ID.
     */
    suspend fun resolvePlaylistId(input: String): String? = withContext(Dispatchers.IO) {
        val clean = input.trim()
        extractPlaylistId(clean)?.let { return@withContext it }

        val urlToFollow = when {
            clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true) -> clean
            clean.contains("spotify.link", ignoreCase = true) || clean.contains("spotify.com", ignoreCase = true) -> "https://$clean"
            else -> null
        }

        if (urlToFollow != null) {
            try {
                val req = Request.Builder()
                    .url(urlToFollow)
                    .header("User-Agent", USER_AGENT)
                    .head()
                    .build()
                httpClient.newCall(req).execute().use { response ->
                    response.header("Location")?.let { loc ->
                        extractPlaylistId(loc)?.let { return@withContext it }
                    }
                    val resolvedUrl = response.request.url.toString()
                    extractPlaylistId(resolvedUrl)?.let { return@withContext it }
                }
            } catch (_: Exception) {
                try {
                    val getReq = Request.Builder()
                        .url(urlToFollow)
                        .header("User-Agent", USER_AGENT)
                        .get()
                        .build()
                    httpClient.newCall(getReq).execute().use { response ->
                        response.header("Location")?.let { loc ->
                            extractPlaylistId(loc)?.let { return@withContext it }
                        }
                        val resolvedUrl = response.request.url.toString()
                        extractPlaylistId(resolvedUrl)?.let { return@withContext it }
                    }
                } catch (_: Exception) {}
            }
        }
        null
    }

    /**
     * Fetches public playlist metadata and raw tracklist from the Spotify embed endpoint.
     */
    open suspend fun fetchPlaylistMetadata(playlistId: String): Result<SpotifyPlaylistInfo> = withContext(Dispatchers.IO) {
        try {
            val embedUrl = "https://open.spotify.com/embed/playlist/$playlistId"
            val request = Request.Builder()
                .url(embedUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            var html: String? = null
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    html = response.body?.string()
                } else {
                    Log.w(TAG, "Embed fetch returned HTTP ${response.code} for $playlistId")
                }
            }

            var title: String? = null
            var description: String? = null
            var coverUrl: String? = null
            val rawTracks = mutableListOf<SpotifyRawTrack>()

            if (!html.isNullOrBlank()) {
                // Parse Next.js __NEXT_DATA__ JSON payload
                val nextDataRegex = Regex(
                    """<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""",
                    RegexOption.DOT_MATCHES_ALL
                )
                val match = nextDataRegex.find(html!!)

                if (match != null) {
                    try {
                        val jsonStr = match.groupValues[1]
                        val root = JsonParser.parseString(jsonStr).asJsonObject
                        val props = root.getAsJsonObject("props")
                        val pageProps = props?.getAsJsonObject("pageProps")
                        val state = pageProps?.getAsJsonObject("state")
                        val data = state?.getAsJsonObject("data")
                        val entity = data?.getAsJsonObject("entity")
                            ?: pageProps?.getAsJsonObject("entity")
                            ?: state?.getAsJsonObject("entity")
                            ?: findEntityWithTrackList(root)

                        if (entity != null) {
                            title = entity.get("title")?.asString
                                ?: entity.get("name")?.asString

                            description = entity.get("subtitle")?.asString

                            if (description.isNullOrBlank() && entity.has("attributes")) {
                                val attrs = entity.getAsJsonArray("attributes")
                                for (attr in attrs) {
                                    if (attr.isJsonObject) {
                                        val obj = attr.asJsonObject
                                        val key = obj.get("key")?.asString
                                        if (key == "episode_description" || key == "description") {
                                            description = obj.get("value")?.asString
                                            break
                                        }
                                    }
                                }
                            }

                            // Artwork extraction
                            if (entity.has("coverArt") && entity.get("coverArt").isJsonObject) {
                                val coverArt = entity.getAsJsonObject("coverArt")
                                val sources = coverArt.getAsJsonArray("sources")
                                if (sources != null && sources.size() > 0) {
                                    coverUrl = sources[0].asJsonObject.get("url")?.asString
                                }
                            }

                            if (coverUrl.isNullOrBlank() && entity.has("visualIdentity") && entity.get("visualIdentity").isJsonObject) {
                                val visual = entity.getAsJsonObject("visualIdentity")
                                val images = visual.getAsJsonArray("image")
                                if (images != null && images.size() > 0) {
                                    coverUrl = images[images.size() - 1].asJsonObject.get("url")?.asString
                                        ?: images[0].asJsonObject.get("url")?.asString
                                }
                            }

                            // Fallback to attributes image_url / header_image_url_desktop
                            if (coverUrl.isNullOrBlank() && entity.has("attributes") && entity.get("attributes").isJsonArray) {
                                val attrs = entity.getAsJsonArray("attributes")
                                for (attr in attrs) {
                                    if (attr.isJsonObject) {
                                        val obj = attr.asJsonObject
                                        val key = obj.get("key")?.asString
                                        if (key == "image_url" || key == "header_image_url_desktop") {
                                            val url = obj.get("value")?.asString
                                            if (!url.isNullOrBlank()) {
                                                coverUrl = url
                                                break
                                            }
                                        }
                                    }
                                }
                            }

                            // Tracklist extraction
                            if (entity.has("trackList") && entity.get("trackList").isJsonArray) {
                                val trackList = entity.getAsJsonArray("trackList")
                                for (item in trackList) {
                                    if (!item.isJsonObject) continue
                                    val obj = item.asJsonObject
                                    val tTitle = cleanHtml(obj.get("title")?.asString.orEmpty())
                                    val tArtist = cleanHtml(obj.get("subtitle")?.asString.orEmpty()).replace("\u00a0", " ")
                                    if (tTitle.isBlank()) continue

                                    val tDuration = obj.get("duration")?.asLong ?: 0L
                                    val uri = obj.get("uri")?.asString.orEmpty()
                                    val trackId = uri.substringAfterLast(":").ifBlank {
                                        obj.get("uid")?.asString.orEmpty().ifBlank { UUID.randomUUID().toString() }
                                    }

                                    var previewUrl: String? = null
                                    if (obj.has("audioPreview") && obj.get("audioPreview").isJsonObject) {
                                        previewUrl = obj.getAsJsonObject("audioPreview").get("url")?.asString
                                    }

                                    rawTracks.add(
                                        SpotifyRawTrack(
                                            id = trackId,
                                            title = tTitle,
                                            artist = tArtist,
                                            durationMs = tDuration,
                                            previewUrl = previewUrl,
                                            coverUrl = coverUrl
                                        )
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error parsing __NEXT_DATA__ JSON: ${e.message}")
                    }
                }

                // Fallback: Regex extraction from raw HTML if __NEXT_DATA__ was not present or empty
                if (rawTracks.isEmpty()) {
                    val trackRegex = Regex(
                        """"uri":"(spotify:track:[^"]+)".*?"title":"([^"]+)","subtitle":"([^"]+)".*?"duration":(\d+)"""
                    )
                    for (m in trackRegex.findAll(html!!)) {
                        val uri = m.groupValues[1]
                        val tTitle = cleanHtml(m.groupValues[2])
                        val tArtist = cleanHtml(m.groupValues[3]).replace("\u00a0", " ")
                        val tDuration = m.groupValues[4].toLongOrNull() ?: 0L
                        if (tTitle.isNotBlank()) {
                            rawTracks.add(
                                SpotifyRawTrack(
                                    id = uri.substringAfterLast(":"),
                                    title = tTitle,
                                    artist = tArtist,
                                    durationMs = tDuration,
                                    coverUrl = coverUrl
                                )
                            )
                        }
                    }
                }
            }

            // Fallback / supplement title and cover from oEmbed if needed
            if (title.isNullOrBlank() || coverUrl.isNullOrBlank()) {
                val oEmbed = fetchOEmbed(playlistId)
                if (oEmbed != null) {
                    if (title.isNullOrBlank()) title = oEmbed.first
                    if (coverUrl.isNullOrBlank()) coverUrl = oEmbed.second
                }
            }

            val finalTitle = cleanHtml(title.orEmpty()).ifBlank { "Spotify Playlist" }
            val finalDescription = description?.let { cleanHtml(it) }

            if (rawTracks.isEmpty()) {
                return@withContext Result.failure(
                    IOException("No tracks could be found in Spotify playlist '$playlistId'. Check if the playlist is public.")
                )
            }

            Log.i(TAG, "Fetched Spotify playlist: '$finalTitle' with ${rawTracks.size} tracks")
            Result.success(
                SpotifyPlaylistInfo(
                    id = playlistId,
                    title = finalTitle,
                    description = finalDescription,
                    coverUrl = coverUrl,
                    tracks = rawTracks
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching playlist metadata: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Builds a stable, persistent Sonora [Track] from Spotify metadata.
     * Runtime stream URLs are never stored: the entry is resolved at playback time.
     */
    open suspend fun resolveTrack(
        spotifyTrack: SpotifyRawTrack,
        fallbackCoverUrl: String? = null,
        fallbackAlbum: String? = null
    ): Track = withContext(Dispatchers.IO) {
        val rawId = spotifyTrack.id.substringAfterLast(":").trim()
        val stableId = if (rawId.isNotBlank()) {
            "spotify_$rawId"
        } else {
            "spotify_${stableTrackHash(spotifyTrack.title, spotifyTrack.artist, spotifyTrack.durationMs)}"
        }

        Track(
            id = stableId,
            title = spotifyTrack.title,
            artist = spotifyTrack.artist,
            album = fallbackAlbum,
            durationMs = spotifyTrack.durationMs,
            coverUrl = spotifyTrack.coverUrl ?: fallbackCoverUrl,
            streamUrl = null,
            provider = "Spotify"
        )
    }

    private fun stableTrackHash(title: String, artist: String, durationMs: Long): String {
        val key = "${title.lowercase().trim()}|${artist.lowercase().trim()}|$durationMs"
        return Integer.toHexString(key.hashCode())
    }

    /**
     * Executes the complete transfer pipeline:
     * 1. Resolves Spotify playlist link and metadata.
     * 2. Resolves each track into a Sonora [Track] with live progress notifications.
     * 3. Saves the playlist directly into [MusicRepository].
     */
    suspend fun importAndSavePlaylist(
        urlOrId: String,
        repository: MusicRepository = this.repository ?: error("MusicRepository must be provided"),
        onProgress: (suspend (current: Int, total: Int, trackTitle: String) -> Unit)? = null
    ): Result<Playlist> = withContext(Dispatchers.IO) {
        try {
            val playlistId = resolvePlaylistId(urlOrId)
                ?: return@withContext Result.failure(
                    IllegalArgumentException("Invalid Spotify playlist URL: '$urlOrId'")
                )

            val metaResult = fetchPlaylistMetadata(playlistId)
            if (metaResult.isFailure) {
                return@withContext Result.failure(
                    metaResult.exceptionOrNull() ?: IOException("Failed to load playlist from Spotify")
                )
            }

            val info = metaResult.getOrThrow()
            val total = info.tracks.size
            val resolvedTracks = ArrayList<Track>(total)

            for ((index, rawTrack) in info.tracks.withIndex()) {
                onProgress?.invoke(index + 1, total, rawTrack.title)
                val resolved = resolveTrack(
                    spotifyTrack = rawTrack,
                    fallbackCoverUrl = info.coverUrl,
                    fallbackAlbum = info.title
                )
                resolvedTracks.add(resolved)
                delay(30) // Gentle delay to avoid upstream rate limits
            }

            val savedPlaylist = repository.createPlaylistWithTracks(
                name = info.title,
                tracks = resolvedTracks,
                description = info.description,
                coverUrl = info.coverUrl
            )

            Log.i(TAG, "Successfully transferred Spotify playlist '${savedPlaylist.name}' (${savedPlaylist.tracks.size} tracks)")
            Result.success(savedPlaylist)
        } catch (e: Exception) {
            Log.e(TAG, "Spotify transfer pipeline error: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Imports a playlist and returns the pair without saving to a repository.
     */
    suspend fun importPlaylist(
        urlOrId: String,
        onProgress: (suspend (current: Int, total: Int, trackTitle: String) -> Unit)? = null
    ): Result<Pair<SpotifyPlaylistInfo, List<Track>>> = withContext(Dispatchers.IO) {
        try {
            val playlistId = resolvePlaylistId(urlOrId)
                ?: return@withContext Result.failure(IllegalArgumentException("Invalid Spotify playlist URL"))

            val metaResult = fetchPlaylistMetadata(playlistId)
            if (metaResult.isFailure) return@withContext Result.failure(metaResult.exceptionOrNull()!!)

            val info = metaResult.getOrThrow()
            val total = info.tracks.size
            val resolvedTracks = ArrayList<Track>(total)

            for ((index, rawTrack) in info.tracks.withIndex()) {
                onProgress?.invoke(index + 1, total, rawTrack.title)
                val resolved = resolveTrack(rawTrack, info.coverUrl, info.title)
                resolvedTracks.add(resolved)
                delay(30)
            }

            Result.success(Pair(info, resolvedTracks))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Private Matching & Helper Functions ---

    private suspend fun fetchOEmbed(playlistId: String): Pair<String, String?>? = withContext(Dispatchers.IO) {
        try {
            val url = "https://open.spotify.com/oembed?url=https://open.spotify.com/playlist/$playlistId"
            val res = HttpClientProvider.getJson(url, client = httpClient)
            res.fold(
                onSuccess = { json ->
                    val root = JsonParser.parseString(json).asJsonObject
                    val title = root.get("title")?.asString.orEmpty()
                    val thumb = root.get("thumbnail_url")?.asString
                    Pair(title, thumb)
                },
                onFailure = { null }
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun findBestDeezerMatch(
        candidates: List<Track>,
        targetTitle: String,
        targetArtist: String
    ): Track? {
        if (candidates.isEmpty()) return null
        val normTargetTitle = normalizeString(targetTitle)
        val normTargetArtist = normalizeString(targetArtist)
        val primaryArtist = normTargetArtist.split(" ", ",", "&").firstOrNull { it.length > 2 } ?: normTargetArtist

        // 1. Exact title & matching artist
        candidates.firstOrNull { c ->
            val normTitle = normalizeString(c.title)
            val normArtist = normalizeString(c.artist)
            (normTitle == normTargetTitle || normTitle.contains(normTargetTitle) || normTargetTitle.contains(normTitle)) &&
                    (normArtist.contains(primaryArtist) || normTargetArtist.contains(normArtist))
        }?.let { return it }

        // 2. Exact title match
        candidates.firstOrNull { c ->
            normalizeString(c.title) == normTargetTitle
        }?.let { return it }

        // 3. Artist matches candidate 0
        candidates.firstOrNull { c ->
            val normArtist = normalizeString(c.artist)
            normArtist.contains(primaryArtist) || normTargetArtist.contains(normArtist)
        }?.let { return it }

        // 4. Default to first candidate
        return candidates.firstOrNull()
    }

    private fun findBestSaavnMatch(
        candidates: List<JioSaavnFallbackClient.JioSaavnSong>,
        targetTitle: String,
        targetArtist: String
    ): JioSaavnFallbackClient.JioSaavnSong? {
        val normTitle = normalizeString(targetTitle)
        val normArtist = normalizeString(targetArtist)

        return candidates.firstOrNull { song ->
            val sTitle = normalizeString(song.title)
            val sArtist = normalizeString(song.artist)
            (sTitle.contains(normTitle) || normTitle.contains(sTitle)) &&
                    (sArtist.contains(normArtist) || normArtist.contains(sArtist))
        } ?: candidates.firstOrNull { song ->
            val sTitle = normalizeString(song.title)
            sTitle.contains(normTitle) || normTitle.contains(sTitle)
        }
    }

    fun cleanTrackTitle(title: String): String {
        return title
            .replace(Regex("(?i)\\s*[\\[(](?:feat\\.?|ft\\.?|with)\\b[^\\])]*[\\])]"), "")
            .replace(Regex("(?i)\\s*[\\[(](?:.*?(?:remaster|deluxe|bonus|radio|live|version|single|mix|edit|anniversary|expanded).*?)[\\])]"), "")
            .replace(Regex("(?i)\\s*-\\s*(?:.*?(?:remaster|deluxe|bonus|radio|live|version|single|mix|edit|anniversary|expanded).*)$"), "")
            .trim()
    }

    private fun normalizeString(text: String): String {
        return text.lowercase()
            .replace(Regex("[^a-z0-9 ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun cleanHtml(text: String): String {
        return text
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace("\u00a0", " ")
            .trim()
    }

    private fun findEntityWithTrackList(obj: JsonObject): JsonObject? {
        if (obj.has("trackList") && obj.get("trackList").isJsonArray) return obj
        for (entry in obj.entrySet()) {
            val value = entry.value
            if (value.isJsonObject) {
                val found = findEntityWithTrackList(value.asJsonObject)
                if (found != null) return found
            }
        }
        return null
    }
}
