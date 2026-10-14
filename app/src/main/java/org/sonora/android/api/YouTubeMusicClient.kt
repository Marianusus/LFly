package org.sonora.android.api

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Selected audio stream format extracted from YouTube InnerTube streamingData.
 */
data class AudioFormatInfo(
    val url: String,
    val mimeType: String,
    val bitrate: Int,
    val codecs: String? = null
)

/**
 * Native YouTube Music client using YouTube InnerTube API (WEB_REMIX client, guest mode).
 * Directly matches the architecture used by desktop Sonora (sonorahq/sonora ytmusic-rs).
 * Allows discovering and streaming all tracks, including recent releases (e.g. Panzerkraken,
 * remixes, independent artists) not indexed by traditional metadata catalogs.
 */
open class YouTubeMusicClient(
    private val invidiousFallback: InvidiousStreamResolver = InvidiousStreamResolver()
) {

    companion object {
        private const val TAG = "YouTubeMusicClient"
        const val BASE_URL = "https://music.youtube.com"
        private const val YTM_SEARCH_URL = "$BASE_URL/youtubei/v1/search"
        private const val BROWSE_URL = "$BASE_URL/youtubei/v1/browse"
        private const val NEXT_URL = "$BASE_URL/youtubei/v1/next"
        private const val YTM_PLAYER_URL = "$BASE_URL/youtubei/v1/player?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8&prettyPrint=false"
        private const val SONGS_FILTER_PARAMS = "EgWKAQIIAWoKEAUQAxAEEAkQBQ==" // Filters results to songs
        private const val ALBUMS_FILTER_PARAMS = "EgWKAQIYAWoKEAkQChAFEAMQBA==" // Filters results to albums
        private const val VIDEOS_FILTER_PARAMS = "EgWKAQIQAWoKEAkQChAFEAMQBA==" // Filters results to videos
        private const val PLAYLISTS_FILTER_PARAMS = "EgWKAQIoAWoKEAkQChAFEAMQBA==" // Filters results to playlists
        private const val MAX_RADIO_PAGES = 4
        private const val MAX_SEARCH_PAGES = 5
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private const val ANDROID_MUSIC_UA = "com.google.android.apps.youtube.music/6.42.51 (Linux; U; Android 12; en_US; Pixel 5 Build/SQ3A.220705.004)"
        private const val IOS_UA = "com.google.ios.youtube/19.16.2 (iPhone14,3; U; CPU iOS 15_6 like Mac OS X)"

        // Guest VISIONOS client: direct (non-ciphered) URLs, no API key.
        private const val VISIONOS_PLAYER_URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false&alt=json"
        private const val VISITOR_URL = "https://www.youtube.com/sw.js_data"
        internal const val VISIONOS_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"
        private const val VISIONOS_CLIENT_NAME = "VISIONOS"
        private const val VISIONOS_CLIENT_VERSION = "1.02"
        private const val VISIONOS_CLIENT_ID = "101"
        private const val WEB_ORIGIN = "https://www.youtube.com"
        private val VISITOR_REGEX = Regex("\"(Cg[A-Za-z0-9%_+=-]{20,})\"")
        private const val CPN_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        private val VIDEO_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")
        private val DUR_PARAM_REGEX = Regex("[?&]dur=(\\d+(?:\\.\\d+)?)")

        fun isValidVideoId(id: String): Boolean = VIDEO_ID_REGEX.matches(id)

        /**
         * Pure, network-free selection of the best full-length audio format.
         * Extracts streamingData.adaptiveFormats (then formats), prefers Opus/AAC, highest bitrate.
         * Discards any 30s-truncated preview (URL `dur` param <= 30 or approxDurationMs <= 30000).
         */
        fun selectBestAudioFormat(json: String): AudioFormatInfo? {
            val root = try {
                JsonParser.parseString(json)
            } catch (_: Exception) {
                return null
            }
            if (!root.isJsonObject) return null
            return selectBestAudioFormat(root.asJsonObject)
        }

        fun isPreviewStreamUrl(url: String): Boolean {
            val match = DUR_PARAM_REGEX.find(url) ?: return false
            val dur = match.groupValues[1].toDoubleOrNull() ?: return false
            return dur <= 30.0
        }

        private val STREAM_COUNT_REGEX = Regex("([\\d][\\d.,]*)\\s*([KMB])?\\s*(plays|views)", RegexOption.IGNORE_CASE)

        /** Parses a YouTube "3.6B plays" / "20M views" label into an absolute count. */
        internal fun parseStreamCount(text: String): Long? {
            val match = STREAM_COUNT_REGEX.find(text) ?: return null
            val value = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
            val multiplier = when (match.groupValues[2].uppercase()) {
                "K" -> 1_000L
                "M" -> 1_000_000L
                "B" -> 1_000_000_000L
                else -> 1L
            }
            return (value * multiplier).toLong()
        }

        private fun selectBestAudioFormat(root: JsonObject): AudioFormatInfo? {
            val streaming = root.getAsJsonObject("streamingData") ?: return null
            val formats = mutableListOf<JsonObject>()
            streaming.getAsJsonArray("adaptiveFormats")?.forEach { elem ->
                if (elem != null && elem.isJsonObject) formats.add(elem.asJsonObject)
            }
            if (formats.isEmpty()) {
                streaming.getAsJsonArray("formats")?.forEach { elem ->
                    if (elem != null && elem.isJsonObject) formats.add(elem.asJsonObject)
                }
            }
            if (formats.isEmpty()) return null

            val candidates = formats.mapNotNull { parseAudioFormat(it) }
            if (candidates.isEmpty()) return null

            // Prefer AAC (audio/mp4) at the max bitrate, else the max bitrate direct stream.
            val aac = candidates
                .filter { it.mimeType.startsWith("audio/mp4", ignoreCase = true) }
                .maxByOrNull { it.bitrate }
            return aac ?: candidates.maxByOrNull { it.bitrate }
        }

        private fun parseAudioFormat(format: JsonObject): AudioFormatInfo? {
            val mime = format.get("mimeType")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
            if (!mime.startsWith("audio", ignoreCase = true)) return null

            // Ciphered formats carry no usable direct url: skip them.
            if (format.has("signatureCipher")) return null

            val url = format.get("url")?.takeIf { it.isJsonPrimitive }?.asString
            if (url.isNullOrBlank()) return null
            if (isPreviewStreamUrl(url)) return null

            val approxDurationMs = try {
                format.get("approxDurationMs")?.asLong
            } catch (_: Exception) {
                null
            }
            if (approxDurationMs != null && approxDurationMs in 1..30_000L) return null

            val bitrate = try {
                format.get("bitrate")?.asInt ?: 0
            } catch (_: Exception) {
                0
            }
            val codecs = Regex("codecs=\"?([^\";]+)").find(mime)?.groupValues?.get(1)
            return AudioFormatInfo(url, mime, bitrate, codecs)
        }
    }

    private enum class YtmClient { ANDROID_MUSIC, IOS }


    private val client = HttpClientProvider.fastClient

    /**
     * Searches YouTube Music for tracks.
     * Tries song-filtered search first; falls back to general search, then to Invidious if needed.
     */
    open suspend fun searchTracks(query: String, limit: Int = 25): Result<List<Track>> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext Result.success(emptyList())

        try {
            // 1. General search first: it carries YouTube's native ranking, which keeps
            //    Music-Video / video-only entries (old songs YT classified as music) that the
            //    song-only filter strips out. Song-filtered results are then appended as
            //    enrichment so official catalog songs are never lost.
            val general = queryInnerTube(trimmed, params = null, limit = limit)
            val songFiltered = queryInnerTube(trimmed, params = SONGS_FILTER_PARAMS, limit = limit)
            val merged = mergeSearchResults(general, songFiltered, limit)
            if (merged.isNotEmpty()) {
                Log.i(TAG, "✓ YouTube Music InnerTube returned ${general.size} general + ${songFiltered.size} songs (merged ${merged.size}) for '$trimmed'")
                return@withContext Result.success(merged)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "InnerTube search failed for '$trimmed': ${e.message}. Falling back to Invidious...")
        }

        // 3. Fallback to Invidious search
        try {
            val invidiousResults = invidiousFallback.searchTracks(trimmed, limit = limit)
            if (invidiousResults.isSuccess) {
                val list = invidiousResults.getOrDefault(emptyList())
                if (list.isNotEmpty()) {
                    Log.i(TAG, "✓ Invidious fallback returned ${list.size} tracks for '$trimmed'")
                    return@withContext Result.success(list)
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Invidious search fallback failed: ${e.message}")
        }

        Result.success(emptyList())
    }

    /**
     * Dedicated video search ("Video" tab). Uses the InnerTube video filter so a music query
     * returns real music videos (with "views" + channel) instead of a handful of video-only
     * entries left over from the general/songs search. All results are flagged `isVideo = true`
     * because the filtered feed drops the explicit "Video" label the parser looks for.
     */
    open suspend fun searchVideos(query: String, limit: Int = 30): Result<List<Track>> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext Result.success(emptyList())
        try {
            val videos = queryInnerTube(trimmed, params = VIDEOS_FILTER_PARAMS, limit = limit)
                .map { it.copy(isVideo = true) }
            Log.i(TAG, "✓ YouTube Music video search returned ${videos.size} videos for '$trimmed'")
            Result.success(videos)
        } catch (e: Throwable) {
            Log.w(TAG, "Video search failed for '$trimmed': ${e.message}")
            Result.success(emptyList())
        }
    }

    /**
     * Merges YouTube's general search (native ranking: keeps Music-Video / video-only entries) with
     * the song-filtered search.
     *
     * Order follows the general ranking, so video-only tracks are never pushed out by songs. When the
     * same videoId exists in the song-filtered set, its richer metadata (duration / album / cover) is
     * grafted onto the general entry. Remaining song-only entries are appended afterwards.
     * Dedup: exact id first, then normalized title+artist.
     */
    internal fun mergeSearchResults(
        general: List<Track>,
        songs: List<Track>,
        limit: Int
    ): List<Track> {
        val songById = HashMap<String, Track>()
        for (song in songs) songById.putIfAbsent(song.id, song)

        val result = ArrayList<Track>(minOf(limit, general.size + songs.size))
        val seenIds = HashSet<String>()
        val seenKeys = HashSet<String>()
        fun add(track: Track) {
            if (result.size >= limit) return
            if (!seenIds.add(track.id)) return
            val key = normalizedKey(track.title, track.artist)
            if (key.isNotBlank() && seenKeys.contains(key)) return
            seenKeys.add(key)
            result.add(track)
        }

        // 1. General ranking first (videos stay), enriched by the song version of the same video.
        for (item in general) {
            val enriched = songById[item.id]
            add(
                if (enriched == null) item else item.copy(
                    album = item.album ?: enriched.album,
                    durationMs = if (item.durationMs > 0L) item.durationMs else enriched.durationMs,
                    coverUrl = item.coverUrl ?: enriched.coverUrl
                )
            )
        }
        // 2. Song-only entries not already present.
        for (song in songs) add(song)
        return result
    }

    private fun normalizedKey(title: String, artist: String): String {
        fun clean(value: String): String = value.lowercase()
            .replace(Regex("\\(.*?\\)"), " ")
            .replace(Regex("\\[.*?\\]"), " ")
            .replace(Regex("\\b(feat\\.?|featuring|ft\\.?)"), " ")
            .replace(Regex("\\b(remix|remaster(ed)?|deluxe|version|edit|live)\\b"), " ")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val primaryArtist = artist.split(",", "&", " feat", " ft").firstOrNull() ?: artist
        return "${clean(title)}|${clean(primaryArtist)}"
    }

    /** One search page: the parsed tracks plus the token for the next page, if any. */
    private data class SearchPage(val tracks: List<Track>, val continuation: String?)

    /**
     * Runs the InnerTube search, following continuation tokens until [limit] unique tracks are
     * collected (bounded by [MAX_SEARCH_PAGES]). A single page returns ~20 results, so the
     * "Brani" tab relies on this to reach 50+.
     */
    private fun queryInnerTube(query: String, params: String?, limit: Int): List<Track> {
        val unique = LinkedHashMap<String, Track>()
        var continuation: String? = null
        var pages = 0
        while (pages < MAX_SEARCH_PAGES && unique.size < limit) {
            val page = fetchSearchPage(if (continuation == null) query else null, params, continuation)
            if (page.tracks.isEmpty() && page.continuation == null) break
            for (track in page.tracks) unique.putIfAbsent(track.id, track)
            continuation = page.continuation
            pages++
            if (continuation == null) break
        }
        return unique.values.take(limit)
    }

    private fun fetchSearchPage(query: String?, params: String?, continuation: String?): SearchPage {
        val root = fetchSearchRoot(query, params, continuation) ?: return SearchPage(emptyList(), null)
        val renderers = mutableListOf<JsonObject>()
        findResponsiveListItems(root, renderers)
        val tracks = renderers.mapNotNull { parseTrackFromItem(it) }
        return SearchPage(tracks, findContinuationToken(root))
    }

    private fun fetchSearchRoot(query: String?, params: String?, continuation: String? = null): JsonObject? {
        val payloadObj = JsonObject().apply {
            val contextObj = JsonObject().apply {
                val clientObj = JsonObject().apply {
                    addProperty("clientName", "WEB_REMIX")
                    addProperty("clientVersion", "1.20240722.01.00")
                    addProperty("hl", "en")
                    addProperty("gl", "US")
                }
                add("client", clientObj)
            }
            add("context", contextObj)
            if (continuation != null) {
                addProperty("continuation", continuation)
            } else {
                addProperty("query", query)
                if (!params.isNullOrBlank()) {
                    addProperty("params", params)
                }
            }
        }

        val requestBody = payloadObj.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(YTM_SEARCH_URL)
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .addHeader("Origin", "https://music.youtube.com")
            .addHeader("Referer", "https://music.youtube.com/")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} from InnerTube search")
            }
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return null
            val root = JsonParser.parseString(body)
            return if (root.isJsonObject) root.asJsonObject else null
        }
    }

    private fun findResponsiveListItems(element: JsonElement, output: MutableList<JsonObject>, depth: Int = 0) {
        if (depth > 20) return
        if (element.isJsonObject) {
            val obj = element.asJsonObject
            if (obj.has("musicResponsiveListItemRenderer") && obj.get("musicResponsiveListItemRenderer").isJsonObject) {
                val renderer = obj.getAsJsonObject("musicResponsiveListItemRenderer")
                output.add(renderer)
            }
            for (entry in obj.entrySet()) {
                findResponsiveListItems(entry.value, output, depth + 1)
            }
        } else if (element.isJsonArray) {
            for (item in element.asJsonArray) {
                findResponsiveListItems(item, output, depth + 1)
            }
        }
    }

    private fun parseTrackFromItem(item: JsonObject): Track? {
        return try {
            val flexCols = item.getAsJsonArray("flexColumns") ?: return null
            if (flexCols.size() == 0) return null

            // 1. Title & VideoId from Column 0
            val col0Elem = flexCols.get(0)
            if (!col0Elem.isJsonObject) return null
            val col0 = col0Elem.asJsonObject
                .getAsJsonObject("musicResponsiveListItemFlexColumnRenderer")
                ?.getAsJsonObject("text")
            val title = extractRunsText(col0).trim()
            if (title.isBlank()) return null

            var videoId = extractVideoId(col0)
            if (videoId == null) {
                val plItemData = item.getAsJsonObject("playlistItemData")
                if (plItemData != null && plItemData.has("videoId") && plItemData.get("videoId").isJsonPrimitive) {
                    videoId = plItemData.get("videoId").asString
                }
            }
            if (videoId == null) {
                val overlay = item.getAsJsonObject("overlay")
                    ?.getAsJsonObject("musicItemThumbnailOverlayRenderer")
                    ?.getAsJsonObject("content")
                    ?.getAsJsonObject("musicPlayButtonRenderer")
                    ?.getAsJsonObject("playNavigationEndpoint")
                    ?.getAsJsonObject("watchEndpoint")
                val vidElem = overlay?.get("videoId")
                if (vidElem != null && vidElem.isJsonPrimitive) {
                    videoId = vidElem.asString
                }
            }
            if (videoId.isNullOrBlank()) return null

            // 2. Artist, Album, Duration from Column 1
            var artist = "YouTube Music"
            var album: String? = null
            var durationMs = 0L
            var isVideo = false

            if (flexCols.size() > 1) {
                val col1Elem = flexCols.get(1)
                if (col1Elem.isJsonObject) {
                    val col1 = col1Elem.asJsonObject
                        .getAsJsonObject("musicResponsiveListItemFlexColumnRenderer")
                        ?.getAsJsonObject("text")

                    val runs = col1?.getAsJsonArray("runs")
                    if (runs != null && runs.size() > 0) {
                        val parts = mutableListOf<String>()
                        for (r in runs) {
                            if (r.isJsonObject) {
                                val textElem = r.asJsonObject.get("text")
                                if (textElem != null && textElem.isJsonPrimitive) {
                                    val t = textElem.asString.trim()
                                    if (t.isNotBlank() && t != "•") {
                                        parts.add(t)
                                    }
                                }
                            }
                        }

                        // Identify duration in parts (e.g. "3:45" or "1:02:15")
                        val durationRegex = Regex("^(\\d{1,2}:)?\\d{1,2}:\\d{2}$")
                        val durationPart = parts.firstOrNull { durationRegex.matches(it) }
                        if (durationPart != null) {
                            durationMs = parseDurationMs(durationPart)
                            parts.remove(durationPart)
                        }

                        // Remove generic kind prefixes like "Song" or "Video" (and remember the kind)
                        if (parts.isNotEmpty() && (parts[0].equals("Song", ignoreCase = true) || parts[0].equals("Video", ignoreCase = true))) {
                            if (parts[0].equals("Video", ignoreCase = true)) isVideo = true
                            parts.removeAt(0)
                        }

                        if (parts.isNotEmpty()) {
                            artist = parts[0]
                        }
                        if (parts.size > 1) {
                            val candidateAlbum = parts[1]
                            if (!candidateAlbum.equals("YouTube", ignoreCase = true) &&
                                !candidateAlbum.equals(title, ignoreCase = true) &&
                                !candidateAlbum.equals(artist, ignoreCase = true)) {
                                album = candidateAlbum
                            }
                        }
                    }
                }
            }

            // Duration fallback: fixedColumns / lengthText / any flex column (real YT layouts).
            if (durationMs <= 0L) {
                durationMs = extractDurationMs(item, flexCols)
            }

            // 3. High quality thumbnail artwork
            val artworkUrl = extractThumbnail(item)

            // 4. Popularity ("3.6B plays" for songs, "20M views" for videos)
            val streams = extractStreamCount(flexCols)

            Track(
                id = "yt_$videoId",
                title = title,
                artist = artist,
                album = album,
                // Unknown duration stays 0L; the UI renders "—" instead of a fake "3:00".
                durationMs = durationMs,
                coverUrl = artworkUrl,
                streamUrl = null,
                provider = "YouTube",
                streams = streams,
                isVideo = isVideo
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Failed parsing InnerTube track item: ${t.message}")
            null
        }
    }

    private fun extractStreamCount(flexCols: JsonArray): Long? {
        for (index in 0 until flexCols.size()) {
            val column = flexCols.get(index).takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val text = column
                .getAsJsonObject("musicResponsiveListItemFlexColumnRenderer")
                ?.getAsJsonObject("text") ?: continue
            val runs = text.getAsJsonArray("runs") ?: continue
            for (run in runs) {
                if (!run.isJsonObject) continue
                val value = run.asJsonObject.get("text")
                    ?.takeIf { it.isJsonPrimitive }?.asString ?: continue
                parseStreamCount(value)?.let { return it }
            }
        }
        return null
    }

    private fun extractRunsText(textObj: JsonObject?): String {
        if (textObj == null) return ""
        val simpleTextElem = textObj.get("simpleText")
        if (simpleTextElem != null && simpleTextElem.isJsonPrimitive) {
            return simpleTextElem.asString
        }
        val runs = textObj.getAsJsonArray("runs") ?: return ""
        val sb = StringBuilder()
        for (elem in runs) {
            if (elem.isJsonObject) {
                val r = elem.asJsonObject
                val textElem = r.get("text")
                if (textElem != null && textElem.isJsonPrimitive) {
                    sb.append(textElem.asString)
                }
            }
        }
        return sb.toString()
    }

    private fun extractVideoId(textObj: JsonObject?): String? {
        val runs = textObj?.getAsJsonArray("runs") ?: return null
        for (elem in runs) {
            if (!elem.isJsonObject) continue
            val nav = elem.asJsonObject.getAsJsonObject("navigationEndpoint") ?: continue
            val watch = nav.getAsJsonObject("watchEndpoint") ?: continue
            val vid = watch.get("videoId")
            if (vid != null && vid.isJsonPrimitive) {
                val s = vid.asString
                if (s.isNotBlank()) return s
            }
        }
        return null
    }

    private fun extractThumbnail(item: JsonObject): String? {
        // `musicResponsiveListItemRenderer` uses `thumbnail`; `musicTwoRowItemRenderer`
        // (artist album/playlist carousels) uses `thumbnailRenderer`.
        val thumbObj = (item.getAsJsonObject("thumbnail") ?: item.getAsJsonObject("thumbnailRenderer"))
            ?.getAsJsonObject("musicThumbnailRenderer")
            ?.getAsJsonObject("thumbnail")
            ?.getAsJsonArray("thumbnails")
            ?: return null

        if (thumbObj.size() == 0) return null
        val last = thumbObj.get(thumbObj.size() - 1)
        if (!last.isJsonObject) return null
        val urlElem = last.asJsonObject.get("url")
        if (urlElem == null || !urlElem.isJsonPrimitive) return null
        val lastThumb = urlElem.asString

        // Enhance resolution from 60x60 / 120x120 to high-res 544x544
        return if (lastThumb.contains("=w") && lastThumb.contains("-h")) {
            lastThumb.replace(Regex("=w\\d+-h\\d+"), "=w544-h544")
        } else {
            lastThumb
        }
    }

    private val durationRegex = Regex("^(\\d{1,2}:)?\\d{1,2}:\\d{2}$")

    /** Extracts a duration (ms) from any real YT Music layout; 0L when unknown. */
    private fun extractDurationMs(item: JsonObject, flexCols: JsonArray): Long {
        for (col in flexCols) {
            if (!col.isJsonObject) continue
            val text = col.asJsonObject
                .getAsJsonObject("musicResponsiveListItemFlexColumnRenderer")
                ?.getAsJsonObject("text")
            firstDurationText(text)?.let { return parseDurationMs(it) }
        }
        item.getAsJsonArray("fixedColumns")?.forEach { col ->
            if (col.isJsonObject) {
                val text = col.asJsonObject
                    .getAsJsonObject("musicResponsiveListItemFixedColumnRenderer")
                    ?.getAsJsonObject("text")
                firstDurationText(text)?.let { return parseDurationMs(it) }
            }
        }
        firstDurationText(item.getAsJsonObject("lengthText"))?.let { return parseDurationMs(it) }
        return 0L
    }

    private fun firstDurationText(textObj: JsonObject?): String? {
        if (textObj == null) return null
        val simple = textObj.get("simpleText")
        if (simple != null && simple.isJsonPrimitive) {
            val s = simple.asString.trim()
            if (durationRegex.matches(s)) return s
        }
        textObj.getAsJsonArray("runs")?.forEach { r ->
            if (r.isJsonObject) {
                val t = r.asJsonObject.get("text")
                if (t != null && t.isJsonPrimitive) {
                    val s = t.asString.trim()
                    if (durationRegex.matches(s)) return s
                }
            }
        }
        return null
    }

    private fun parseDurationMs(durationStr: String): Long {
        return try {
            val parts = durationStr.split(":")
            if (parts.size == 2) {
                val min = parts[0].toLong()
                val sec = parts[1].toLong()
                (min * 60 + sec) * 1000L
            } else if (parts.size == 3) {
                val hr = parts[0].toLong()
                val min = parts[1].toLong()
                val sec = parts[2].toLong()
                (hr * 3600 + min * 60 + sec) * 1000L
            } else {
                0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Resolves the best full-length audio stream for a [Track] via /youtubei/v1/player.
     * Tries ANDROID_MUSIC first, then falls back to the IOS client. Never returns 30s previews.
     */
    suspend fun resolveStream(track: Track): StreamSource? = withContext(Dispatchers.IO) {
        try {
            val requestId = track.id
            val videoId = resolveVideoId(track) ?: return@withContext null

            // 1. Guest VISIONOS client first: direct (non-ciphered) urls, no API key.
            var probeUa = VISIONOS_UA
            var info = fetchVisionOsPlayer(videoId)?.let { selectBestAudioFormat(it) }
            if (info != null && !probeStreamUrl(info.url, probeUa)) {
                Log.w(TAG, "VISIONOS stream url refused for $videoId, trying fallback clients")
                info = null
            }

            // 2. Existing fallback chain: ANDROID_MUSIC, then IOS.
            if (info == null) {
                info = fetchPlayer(videoId, YtmClient.ANDROID_MUSIC)?.let { selectBestAudioFormat(it) }
                probeUa = ANDROID_MUSIC_UA
            }
            if (info == null) {
                info = fetchPlayer(videoId, YtmClient.IOS)?.let { selectBestAudioFormat(it) }
                probeUa = IOS_UA
            }
            if (info == null) return@withContext null
            if (!probeStreamUrl(info.url, probeUa)) return@withContext null

            // Discard a resolution that finished after the track changed.
            if (track.id != requestId) return@withContext null

            StreamSource(
                url = info.url,
                format = info.mimeType,
                quality = qualityForBitrate(info.bitrate),
                provider = "YouTube Music",
                bitrate = if (info.bitrate > 0) info.bitrate / 1000 else null,
                isPreview = false
            )
        } catch (e: Exception) {
            Log.w(TAG, "YouTube Music /player resolution failed for '${track.title}': ${e.message}")
            null
        }
    }

    /**
     * Convenience wrapper returning only the direct audio URL.
     */
    suspend fun resolveStreamUrl(track: Track): String? = resolveStream(track)?.url

    private suspend fun resolveVideoId(track: Track): String? {
        val raw = track.id.removePrefix("yt_").trim()
        if (isValidVideoId(raw)) return raw

        val query = "${track.title} ${track.artist}".trim()
        if (query.isBlank()) return null
        val results = searchTracks(query, limit = 5).getOrNull().orEmpty()
        return results.firstOrNull { it.id.startsWith("yt_") }
            ?.id?.removePrefix("yt_")?.takeIf { isValidVideoId(it) }
    }

    private fun qualityForBitrate(bitrateBps: Int): AudioQuality = when {
        bitrateBps >= 256_000 -> AudioQuality.HIGH
        bitrateBps >= 128_000 -> AudioQuality.MEDIUM
        else -> AudioQuality.LOW
    }

    @Volatile
    private var cachedVisitorData: String? = null
    private val visitorLock = Any()

    private fun getVisitorData(): String? {
        cachedVisitorData?.let { return it }
        synchronized(visitorLock) {
            cachedVisitorData?.let { return it }
            val fetched = fetchVisitorData()
            if (fetched != null) cachedVisitorData = fetched
            return fetched
        }
    }

    private fun fetchVisitorData(): String? = try {
        val request = Request.Builder()
            .url(VISITOR_URL)
            .get()
            .addHeader("User-Agent", VISIONOS_UA)
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            VISITOR_REGEX.find(body)?.groupValues?.getOrNull(1)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Visitor id fetch failed: ${e.message}")
        null
    }

    private fun fetchVisionOsPlayer(videoId: String): JsonObject? {
        var visitor = getVisitorData()
        var root = requestVisionOsPlayer(videoId, visitor)
        if (root != null && playabilityStatus(root) == "LOGIN_REQUIRED") {
            Log.d(TAG, "VISIONOS LOGIN_REQUIRED for $videoId, renewing visitorData and retrying once")
            synchronized(visitorLock) { cachedVisitorData = null }
            visitor = getVisitorData()
            root = requestVisionOsPlayer(videoId, visitor)
        }
        return root
    }

    private fun requestVisionOsPlayer(videoId: String, visitorData: String?): JsonObject? {
        val clientObj = JsonObject().apply {
            addProperty("clientName", VISIONOS_CLIENT_NAME)
            addProperty("clientVersion", VISIONOS_CLIENT_VERSION)
            addProperty("hl", "en")
            addProperty("gl", "US")
            if (!visitorData.isNullOrBlank()) addProperty("visitorData", visitorData)
        }
        val payload = JsonObject().apply {
            add("context", JsonObject().apply { add("client", clientObj) })
            addProperty("videoId", videoId)
            addProperty("contentCheckOk", true)
            addProperty("racyCheckOk", true)
            addProperty("cpn", randomCpn())
            add("playbackContext", JsonObject().apply {
                add("contentPlaybackContext", JsonObject().apply {
                    addProperty("html5Preference", "HTML5_PREF_WANTS")
                })
            })
        }
        val builder = Request.Builder()
            .url(VISIONOS_PLAYER_URL)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "*/*")
            .addHeader("Origin", WEB_ORIGIN)
            .addHeader("User-Agent", VISIONOS_UA)
            .addHeader("X-Youtube-Client-Name", VISIONOS_CLIENT_ID)
            .addHeader("X-Youtube-Client-Version", VISIONOS_CLIENT_VERSION)
        if (!visitorData.isNullOrBlank()) {
            builder.addHeader("X-Goog-Visitor-Id", visitorData)
        }
        return try {
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "VISIONOS /player HTTP ${response.code} for $videoId")
                    return null
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return null
                val parsed = JsonParser.parseString(body)
                if (parsed.isJsonObject) parsed.asJsonObject else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "VISIONOS /player request failed for $videoId: ${e.message}")
            null
        }
    }

    private fun playabilityStatus(root: JsonObject): String? = try {
        root.getAsJsonObject("playabilityStatus")?.get("status")?.asString
    } catch (_: Exception) {
        null
    }

    private fun probeStreamUrl(url: String, userAgent: String): Boolean = try {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-1")
            .header("User-Agent", userAgent)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            response.isSuccessful || response.code == 206
        }
    } catch (e: Exception) {
        Log.w(TAG, "Stream url probe failed: ${e.message}")
        false
    }

    private fun randomCpn(): String = (0 until 16)
        .map { CPN_ALPHABET[kotlin.random.Random.nextInt(CPN_ALPHABET.length)] }
        .joinToString("")

    private fun fetchPlayer(videoId: String, clientKind: YtmClient): JsonObject? {
        val payload = JsonObject().apply {
            val clientObj = JsonObject().apply {
                when (clientKind) {
                    YtmClient.ANDROID_MUSIC -> {
                        addProperty("clientName", "ANDROID_MUSIC")
                        addProperty("clientVersion", "6.42.51")
                        addProperty("androidSdkVersion", 31)
                        addProperty("osName", "Android")
                        addProperty("osVersion", "12")
                    }
                    YtmClient.IOS -> {
                        addProperty("clientName", "IOS")
                        addProperty("clientVersion", "19.16.2")
                        addProperty("deviceMake", "Apple")
                        addProperty("deviceModel", "iPhone14,3")
                        addProperty("osName", "iPhone")
                        addProperty("osVersion", "15.6.0.19G71")
                    }
                }
                addProperty("hl", "en")
                addProperty("gl", "US")
            }
            add("context", JsonObject().apply { add("client", clientObj) })
            addProperty("videoId", videoId)
            addProperty("contentCheckOk", true)
            addProperty("racyCheckOk", true)
        }

        val userAgent = if (clientKind == YtmClient.ANDROID_MUSIC) ANDROID_MUSIC_UA else IOS_UA
        val request = Request.Builder()
            .url(YTM_PLAYER_URL)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", userAgent)
            .addHeader("Origin", BASE_URL)
            .addHeader("Referer", "$BASE_URL/")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} from InnerTube player")
            }
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return null
            val root = JsonParser.parseString(body)
            return if (root.isJsonObject) root.asJsonObject else null
        }
    }

    // ---------------------------------------------------------------------------------------------
    // YouTube Music discovery (Milestone 1): artists, albums, playlists (metadata only).
    // ---------------------------------------------------------------------------------------------

    private val artistDetailCache = YouTubeDiscoveryCache<YouTubeArtistDetail>()
    private val albumDetailCache = YouTubeDiscoveryCache<YouTubeAlbumDetail>()
    private val playlistDetailCache = YouTubeDiscoveryCache<YouTubePlaylistDetail>()
    private val radioCache = YouTubeDiscoveryCache<List<Track>>(ttlMs = 15 * 60 * 1000L)

    suspend fun searchArtists(query: String, limit: Int = 20): Result<List<YouTubeArtist>> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext Result.success(emptyList())
        try {
            val root = fetchSearchRoot(trimmed, params = null) ?: return@withContext Result.success(emptyList())
            Result.success(parseYouTubeArtistSearch(root, limit))
        } catch (e: Throwable) {
            Log.w(TAG, "searchArtists failed for '$trimmed': ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun searchAlbums(query: String, limit: Int = 20): Result<List<YouTubeAlbum>> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext Result.success(emptyList())
        try {
            // Dedicated Albums filter yields many more results than the general search.
            val albumRoot = fetchSearchRoot(trimmed, params = ALBUMS_FILTER_PARAMS)
            val filtered = albumRoot?.let { parseYouTubeAlbumSearch(it, limit) }.orEmpty()
            if (filtered.size >= limit) return@withContext Result.success(filtered)
            val general = fetchSearchRoot(trimmed, params = null)
                ?.let { parseYouTubeAlbumSearch(it, limit) }.orEmpty()
            Result.success(mergeAlbums(filtered, general, limit))
        } catch (e: Throwable) {
            Log.w(TAG, "searchAlbums failed for '$trimmed': ${e.message}")
            Result.failure(e)
        }
    }

    /** Merges albums (filtered first) with dedup by detail id, then normalized title. */
    internal fun mergeAlbums(
        primary: List<YouTubeAlbum>,
        secondary: List<YouTubeAlbum>,
        limit: Int
    ): List<YouTubeAlbum> {
        val out = ArrayList<YouTubeAlbum>(minOf(limit, primary.size + secondary.size))
        val seenIds = HashSet<String>()
        val seenKeys = HashSet<String>()
        for (album in primary + secondary) {
            if (out.size >= limit) break
            val id = album.detailId
            if (id != null && !seenIds.add(id)) continue
            val key = normalizedNameKey(album.title)
            if (id == null && key.isNotBlank() && !seenKeys.add(key)) continue
            if (key.isNotBlank()) seenKeys.add(key)
            out.add(album)
        }
        return out
    }

    suspend fun searchPlaylists(query: String, limit: Int = 20): Result<List<YouTubePlaylist>> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext Result.success(emptyList())
        try {
            // Dedicated Playlists filter yields many more results than the general search.
            val playlistRoot = fetchSearchRoot(trimmed, params = PLAYLISTS_FILTER_PARAMS)
            val filtered = playlistRoot?.let { parseYouTubePlaylistSearch(it, limit) }.orEmpty()
            if (filtered.size >= limit) return@withContext Result.success(filtered)
            val general = fetchSearchRoot(trimmed, params = null)
                ?.let { parseYouTubePlaylistSearch(it, limit) }.orEmpty()
            Result.success(mergePlaylists(filtered, general, limit))
        } catch (e: Throwable) {
            Log.w(TAG, "searchPlaylists failed for '$trimmed': ${e.message}")
            Result.failure(e)
        }
    }

    /** Merges playlists (filtered first) with dedup by playlist id, then normalized title. */
    internal fun mergePlaylists(
        primary: List<YouTubePlaylist>,
        secondary: List<YouTubePlaylist>,
        limit: Int
    ): List<YouTubePlaylist> {
        val out = ArrayList<YouTubePlaylist>(minOf(limit, primary.size + secondary.size))
        val seenIds = HashSet<String>()
        val seenKeys = HashSet<String>()
        for (playlist in primary + secondary) {
            if (out.size >= limit) break
            val id = playlist.detailId
            if (id != null && !seenIds.add(id)) continue
            val key = normalizedNameKey(playlist.title)
            if (id == null && key.isNotBlank() && !seenKeys.add(key)) continue
            if (key.isNotBlank()) seenKeys.add(key)
            out.add(playlist)
        }
        return out
    }

    open suspend fun getYouTubeArtistDetail(browseId: String): Result<YouTubeArtistDetail> = withContext(Dispatchers.IO) {
        val id = browseId.trim()
        if (id.isBlank()) return@withContext Result.failure(IllegalArgumentException("Blank artist browseId"))
        artistDetailCache.get(id)?.let { return@withContext Result.success(it) }
        try {
            val root = fetchBrowseRoot(id) ?: return@withContext Result.failure(IOException("Empty artist browse response"))
            val detail = parseYouTubeArtistDetail(root)
                ?: return@withContext Result.failure(IllegalStateException("Unparseable artist detail"))
            artistDetailCache.put(id, detail)
            Result.success(detail)
        } catch (e: Throwable) {
            Log.w(TAG, "getYouTubeArtistDetail failed for '$id': ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun getYouTubeAlbumDetail(browseId: String): Result<YouTubeAlbumDetail> = withContext(Dispatchers.IO) {
        val id = browseId.trim()
        if (id.isBlank()) return@withContext Result.failure(IllegalArgumentException("Blank album browseId"))
        albumDetailCache.get(id)?.let { return@withContext Result.success(it) }
        try {
            val root = fetchBrowseRoot(id) ?: return@withContext Result.failure(IOException("Empty album browse response"))
            val detail = parseYouTubeAlbumDetail(root)
                ?: return@withContext Result.failure(IllegalStateException("Unparseable album detail"))
            albumDetailCache.put(id, detail)
            Result.success(detail)
        } catch (e: Throwable) {
            Log.w(TAG, "getYouTubeAlbumDetail failed for '$id': ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun getYouTubePlaylistDetail(playlistId: String): Result<YouTubePlaylistDetail> = withContext(Dispatchers.IO) {
        val raw = playlistId.trim()
        if (raw.isBlank()) return@withContext Result.failure(IllegalArgumentException("Blank playlist id"))
        val browseId = if (raw.startsWith("VL")) raw else "VL$raw"
        playlistDetailCache.get(browseId)?.let { return@withContext Result.success(it) }
        try {
            val root = fetchBrowseRoot(browseId) ?: return@withContext Result.failure(IOException("Empty playlist browse response"))
            val detail = parseYouTubePlaylistDetail(root)
                ?: return@withContext Result.failure(IllegalStateException("Unparseable playlist detail"))
            playlistDetailCache.put(browseId, detail)
            Result.success(detail)
        } catch (e: Throwable) {
            Log.w(TAG, "getYouTubePlaylistDetail failed for '$browseId': ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Fetches a full public YouTube Music playlist, following continuation tokens so that
     * playlists larger than the first browse page are returned in their entirety (capped at
     * [maxTracks] as a safety guard). Track entries are already playable `yt_` tracks, so no
     * per-track resolution is required.
     */
    open suspend fun getYouTubePlaylist(
        playlistId: String,
        onProgress: ((loaded: Int) -> Unit)? = null,
        maxTracks: Int = 2000
    ): Result<YouTubePlaylistDetail> = withContext(Dispatchers.IO) {
        val raw = playlistId.trim()
        if (raw.isBlank()) return@withContext Result.failure(IllegalArgumentException("Blank playlist id"))
        val browseId = if (raw.startsWith("VL")) raw else "VL$raw"
        try {
            val first = getYouTubePlaylistDetail(raw).getOrElse { return@withContext Result.failure(it) }
            val all = LinkedHashMap<String, Track>()
            first.tracks.forEach { all[it.id] = it }
            onProgress?.invoke(all.size)

            var token = first.continuation
            var guard = 0
            while (!token.isNullOrBlank() && all.size < maxTracks && guard < 60) {
                guard++
                val pageResult = getYouTubeSongsContinuation(browseId, token)
                if (pageResult.isFailure) break
                val page = pageResult.getOrThrow()
                if (page.tracks.isEmpty()) break
                var added = 0
                page.tracks.forEach { if (all.put(it.id, it) == null) added++ }
                onProgress?.invoke(all.size)
                if (page.continuation.isNullOrBlank() || page.continuation == token || added == 0) break
                token = page.continuation
                delay(40)
            }

            Log.i(TAG, "Full playlist '$browseId' resolved to ${all.size} tracks")
            Result.success(first.copy(tracks = all.values.toList(), continuation = null))
        } catch (e: Throwable) {
            Log.w(TAG, "getYouTubePlaylist failed for '$browseId': ${e.message}")
            Result.failure(e)
        }
    }

    /** Full artist song list ("Cele mai populare melodii") via the shelf's Show-all endpoint. */
    suspend fun getYouTubeArtistSongs(browseId: String, params: String?): Result<YouTubeSongsPage> =
        withContext(Dispatchers.IO) {
            val id = browseId.trim()
            if (id.isBlank()) return@withContext Result.failure(IllegalArgumentException("Blank songs browseId"))
            try {
                val root = fetchBrowseRoot(id, params)
                    ?: return@withContext Result.failure(IOException("Empty songs browse response"))
                Result.success(parseYouTubeSongsPage(root))
            } catch (e: Throwable) {
                Log.w(TAG, "getYouTubeArtistSongs failed for '$id': ${e.message}")
                Result.failure(e)
            }
        }

    /** Appends the next page of artist songs using the real continuation token. */
    suspend fun getYouTubeSongsContinuation(browseId: String, continuation: String): Result<YouTubeSongsPage> =
        withContext(Dispatchers.IO) {
            if (continuation.isBlank()) return@withContext Result.success(YouTubeSongsPage())
            try {
                val root = fetchBrowseRoot(browseId.trim(), null, continuation)
                    ?: return@withContext Result.failure(IOException("Empty songs continuation response"))
                Result.success(parseYouTubeSongsPage(root))
            } catch (e: Throwable) {
                Log.w(TAG, "getYouTubeSongsContinuation failed: ${e.message}")
                Result.failure(e)
            }
        }

    /**
     * Fetches the YouTube Music "radio"/mix for a seed [videoId] via the InnerTube `/next`
     * (watch-next) endpoint, which returns the auto-generated radio queue
     * (`playlistPanelVideoRenderer` items). Returns only valid playable `yt_` tracks,
     * de-duplicated, capped at [limit]. Cached in-memory for 15 minutes.
     *
     * Note: the bare `browse` endpoint with `browseId=RDAMVM<videoId>` returns HTTP 400; the
     * radio must be requested through `/next` with `playlistId=RDAMVM<videoId>`.
     */
    open suspend fun getYouTubeRadio(videoId: String, limit: Int = 50): Result<List<Track>> =
        withContext(Dispatchers.IO) {
            val vid = videoId.trim().removePrefix("yt_")
            if (!isValidVideoId(vid)) {
                return@withContext Result.failure(IllegalArgumentException("Invalid videoId for radio: $videoId"))
            }
            radioCache.get(vid)?.let { return@withContext Result.success(it.take(limit)) }
            try {
                val root = fetchNextRoot(vid) ?: return@withContext Result.failure(IOException("Empty radio response"))
                val tracks = parseWatchNextTracks(root, limit)
                radioCache.put(vid, tracks)
                Log.i(TAG, "YouTube radio for $vid returned ${tracks.size} tracks")
                Result.success(tracks.take(limit))
            } catch (e: Throwable) {
                Log.w(TAG, "getYouTubeRadio failed for $vid: ${e.message}")
                Result.failure(e)
            }
        }

    /**
     * Artist "top tracks" harvested from the artist detail page. Used as a taste-based
     * recommendation source (followed artists / top played artists) without extra endpoints.
     */
    open suspend fun getYouTubeArtistTopTracks(browseId: String, limit: Int = 20): Result<List<Track>> {
        if (browseId.isBlank()) return Result.failure(IllegalArgumentException("Blank artist browseId"))
        return getYouTubeArtistDetail(browseId).map { detail ->
            detail.topTracks.filter { it.id.startsWith("yt_") }.take(limit)
        }
    }

    private fun fetchBrowseRoot(
        browseId: String,
        params: String? = null,
        continuation: String? = null
    ): JsonObject? {
        val payloadObj = JsonObject().apply {
            val contextObj = JsonObject().apply {
                val clientObj = JsonObject().apply {
                    addProperty("clientName", "WEB_REMIX")
                    addProperty("clientVersion", "1.20240722.01.00")
                    addProperty("hl", "en")
                    addProperty("gl", "US")
                }
                add("client", clientObj)
            }
            add("context", contextObj)
            addProperty("browseId", browseId)
            if (!params.isNullOrBlank()) addProperty("params", params)
            if (!continuation.isNullOrBlank()) addProperty("continuation", continuation)
        }

        val requestBody = payloadObj.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(BROWSE_URL)
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .addHeader("Origin", "https://music.youtube.com")
            .addHeader("Referer", "https://music.youtube.com/")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} from InnerTube browse")
            }
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return null
            val root = JsonParser.parseString(body)
            return if (root.isJsonObject) root.asJsonObject else null
        }
    }

    /** InnerTube `/next` (watch-next) request used to fetch the auto-generated radio queue. */
    private fun fetchNextRoot(videoId: String): JsonObject? {
        val payloadObj = JsonObject().apply {
            val contextObj = JsonObject().apply {
                val clientObj = JsonObject().apply {
                    addProperty("clientName", "WEB_REMIX")
                    addProperty("clientVersion", "1.20240722.01.00")
                    addProperty("hl", "en")
                    addProperty("gl", "US")
                }
                add("client", clientObj)
            }
            add("context", contextObj)
            addProperty("videoId", videoId)
            addProperty("playlistId", "RDAMVM$videoId")
            addProperty("isAudioOnly", true)
        }

        val requestBody = payloadObj.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(NEXT_URL)
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .addHeader("Origin", "https://music.youtube.com")
            .addHeader("Referer", "https://music.youtube.com/")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} from InnerTube next")
            }
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return null
            val root = JsonParser.parseString(body)
            return if (root.isJsonObject) root.asJsonObject else null
        }
    }

    /** Parses the radio queue from a `/next` response (`playlistPanelVideoRenderer` items). */
    internal fun parseWatchNextTracks(root: JsonElement, limit: Int): List<Track> {
        val out = ArrayList<Track>()
        val seen = HashSet<String>()
        for (panel in collectRenderers(root, "playlistPanelVideoRenderer")) {
            if (out.size >= limit) break
            val videoId = panel.optString("videoId")
            if (videoId == null || !isValidVideoId(videoId)) continue
            if (!seen.add(videoId)) continue
            val title = extractRunsText(panel.optObject("title")).trim()
            if (title.isBlank()) continue
            val byline = extractRunsText(panel.optObject("shortBylineText")).ifBlank {
                extractRunsText(panel.optObject("longBylineText"))
            }
            val artist = byline.substringBefore('•').trim().ifBlank { "YouTube Music" }
            val durationMs = parseDurationMs(extractRunsText(panel.optObject("lengthText")))
            val coverUrl = panel.optObject("thumbnail")
                ?.get("thumbnails")
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?.lastOrNull()
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.optString("url")
            out.add(
                Track(
                    id = "yt_$videoId",
                    title = title,
                    artist = artist,
                    album = null,
                    durationMs = durationMs,
                    coverUrl = coverUrl,
                    streamUrl = null,
                    provider = "YouTube"
                )
            )
        }
        return out
    }

    // --- Search parsing ---------------------------------------------------------------------------

    internal fun parseYouTubeArtistSearch(root: JsonElement, limit: Int): List<YouTubeArtist> {
        if (limit <= 0) return emptyList()
        val out = ArrayList<YouTubeArtist>()
        val seenIds = HashSet<String>()
        val seenNames = HashSet<String>()

        // 1. Top result (musicCardShelfRenderer) has priority and is emitted first.
        for (card in collectRenderers(root, "musicCardShelfRenderer")) {
            if (out.size >= limit) break
            try {
                val titleObj = card.optObject("title")
                val name = extractRunsText(titleObj).trim()
                if (name.isBlank()) continue
                val subtitleObj = card.optObject("subtitle")
                val subtitle = extractRunsText(subtitleObj).trim()
                val browse = firstBrowseEndpoint(titleObj)
                    ?: firstBrowseEndpoint(subtitleObj)
                    ?: firstBrowseEndpointDeep(card)
                val browseId = browse?.optString("browseId")
                val kind = classifyEntity(pageType(browse), browseId, subtitle)
                if (kind != YouTubeDiscoveryKind.ARTIST) continue
                val channelId = extractChannelId(titleObj) ?: browseId?.takeIf { it.startsWith("UC") }
                if (!browseId.isNullOrBlank() && !seenIds.add(browseId)) continue
                val nameKey = normalizedNameKey(name)
                if (nameKey.isNotBlank()) seenNames.add(nameKey)
                out.add(YouTubeArtist(browseId, channelId, name, extractThumbnail(card), subtitle.ifBlank { null }))
            } catch (_: Exception) {
            }
        }

        // 2. Responsive list rows (id may live at item level, not on the title run).
        for (item in collectRenderers(root, "musicResponsiveListItemRenderer")) {
            if (out.size >= limit) break
            try {
                val col0 = flexColumnText(item, 0) ?: continue
                val title = extractRunsText(col0).trim()
                if (title.isBlank()) continue
                val subtitle = subtitleText(item)
                val colBrowse = firstBrowseEndpoint(col0)
                val itemBrowse = item.optObject("navigationEndpoint")?.optObject("browseEndpoint")
                val browse = colBrowse ?: itemBrowse
                val browseId = browse?.optString("browseId")
                val kind = classifyEntity(pageType(itemBrowse) ?: pageType(colBrowse), browseId, subtitle)
                if (kind != YouTubeDiscoveryKind.ARTIST) continue
                val channelId = extractChannelId(col0)
                    ?: browseId?.takeIf { pageType(itemBrowse) == "MUSIC_PAGE_TYPE_ARTIST" }
                if (!browseId.isNullOrBlank() && !seenIds.add(browseId)) continue
                val nameKey = normalizedNameKey(title)
                if (nameKey.isNotBlank() && !seenNames.add(nameKey)) continue
                out.add(YouTubeArtist(browseId, channelId, title, extractThumbnail(item), subtitle.ifBlank { null }))
            } catch (_: Exception) {
            }
        }

        // 3. Artist carousels (some queries return a dedicated "Artists" shelf / two-row items).
        for (carousel in collectRenderers(root, "musicCarouselShelfRenderer")) {
            if (out.size >= limit) break
            for (item in collectRenderers(carousel, "musicTwoRowItemRenderer")) {
                if (out.size >= limit) break
                try {
                    val title = extractRunsText(item.optObject("title")).trim()
                    if (title.isBlank()) continue
                    val browseId = item.optObject("navigationEndpoint")
                        ?.optObject("browseEndpoint")?.optString("browseId")
                    if (browseId == null || !browseId.startsWith("UC")) continue
                    val subtitle = extractRunsText(item.optObject("subtitle")).trim()
                    if (!seenIds.add(browseId)) continue
                    val nameKey = normalizedNameKey(title)
                    if (nameKey.isNotBlank() && !seenNames.add(nameKey)) continue
                    out.add(YouTubeArtist(browseId, browseId, title, extractThumbnail(item), subtitle.ifBlank { null }))
                } catch (_: Exception) {
                }
            }
        }
        return out
    }

    internal fun parseYouTubeAlbumSearch(root: JsonElement, limit: Int): List<YouTubeAlbum> {
        if (limit <= 0) return emptyList()
        val out = ArrayList<YouTubeAlbum>()
        val seenIds = HashSet<String>()
        val seenKeys = HashSet<String>()
        for (item in collectRenderers(root, "musicResponsiveListItemRenderer")) {
            if (out.size >= limit) break
            try {
                val col0 = flexColumnText(item, 0) ?: continue
                val title = extractRunsText(col0).trim()
                if (title.isBlank()) continue
                val segments = subtitleSegments(item)
                val subtitle = segments.joinToString(" • ")
                val colBrowse = firstBrowseEndpoint(col0)
                val itemBrowse = item.optObject("navigationEndpoint")?.optObject("browseEndpoint")
                val browseId = (colBrowse ?: itemBrowse)?.optString("browseId")
                val itemWatchPid = item.optObject("navigationEndpoint")?.optObject("watchEndpoint")?.optString("playlistId")
                val playlistId = extractWatchPlaylistId(col0) ?: itemWatchPid
                val kind = classifyEntity(pageType(itemBrowse) ?: pageType(colBrowse), browseId, subtitle)
                if (kind != YouTubeDiscoveryKind.ALBUM) continue
                val artist = firstMeaningfulSegment(segments)
                val year = firstYear(subtitle)
                val idKey = browseId ?: playlistId
                if (idKey != null) {
                    if (!seenIds.add(idKey)) continue
                } else {
                    val key = "${normalizedNameKey(title)}|${normalizedNameKey(artist ?: "")}"
                    if (!seenKeys.add(key)) continue
                }
                out.add(YouTubeAlbum(browseId, playlistId, title, artist, year, extractThumbnail(item)))
            } catch (_: Exception) {
            }
        }

        // Also collect album carousels (two-row items) when the response includes them.
        for (carousel in collectRenderers(root, "musicCarouselShelfRenderer")) {
            if (out.size >= limit) break
            for (item in collectRenderers(carousel, "musicTwoRowItemRenderer")) {
                if (out.size >= limit) break
                try {
                    val title = extractRunsText(item.optObject("title")).trim()
                    if (title.isBlank()) continue
                    val browseId = item.optObject("navigationEndpoint")
                        ?.optObject("browseEndpoint")?.optString("browseId")
                    val playlistId = item.optObject("navigationEndpoint")
                        ?.optObject("watchEndpoint")?.optString("playlistId")
                    val isAlbum = (browseId != null && browseId.startsWith("MPRE")) ||
                        (playlistId != null && playlistId.startsWith("OLAK"))
                    if (!isAlbum) continue
                    val subtitle = extractRunsText(item.optObject("subtitle"))
                    val year = firstYear(subtitle)
                    val artist = subtitle.split('•').map { it.trim() }
                        .firstOrNull { it.any { c -> c.isLetter() } && !Regex("^\\d{4}$").matches(it) }
                    val idKey = browseId ?: playlistId
                    if (idKey != null && !seenIds.add(idKey)) continue
                    out.add(YouTubeAlbum(browseId, playlistId, title, artist, year, extractThumbnail(item)))
                } catch (_: Exception) {
                }
            }
        }
        return out
    }

    internal fun parseYouTubePlaylistSearch(root: JsonElement, limit: Int): List<YouTubePlaylist> {
        if (limit <= 0) return emptyList()
        val out = ArrayList<YouTubePlaylist>()
        val seenIds = HashSet<String>()
        val seenKeys = HashSet<String>()
        for (item in collectRenderers(root, "musicResponsiveListItemRenderer")) {
            if (out.size >= limit) break
            try {
                val col0 = flexColumnText(item, 0) ?: continue
                val title = extractRunsText(col0).trim()
                if (title.isBlank()) continue
                val segments = subtitleSegments(item)
                val subtitle = segments.joinToString(" • ")
                val colBrowse = firstBrowseEndpoint(col0)
                val itemBrowse = item.optObject("navigationEndpoint")?.optObject("browseEndpoint")
                val browseId = (colBrowse ?: itemBrowse)?.optString("browseId")
                val itemWatchPid = item.optObject("navigationEndpoint")?.optObject("watchEndpoint")?.optString("playlistId")
                val playlistFromWatch = extractWatchPlaylistId(col0) ?: itemWatchPid
                val kind = classifyEntity(pageType(itemBrowse) ?: pageType(colBrowse), browseId, subtitle)
                if (kind != YouTubeDiscoveryKind.PLAYLIST) continue
                val author = firstMeaningfulSegment(segments)
                val trackCount = parseTrackCount(subtitle)
                val playlistId = playlistFromWatch ?: browseId?.removePrefix("VL")?.takeIf { it.isNotBlank() }
                val idKey = playlistId ?: browseId
                if (idKey != null) {
                    if (!seenIds.add(idKey)) continue
                } else {
                    val key = "${normalizedNameKey(title)}|${normalizedNameKey(author ?: "")}"
                    if (!seenKeys.add(key)) continue
                }
                out.add(YouTubePlaylist(playlistId, browseId, title, author, trackCount, extractThumbnail(item)))
            } catch (_: Exception) {
            }
        }
        return out
    }

    // --- Detail parsing ---------------------------------------------------------------------------

    internal fun parseYouTubeArtistDetail(root: JsonElement): YouTubeArtistDetail? {
        val header = collectRenderers(root, "musicImmersiveHeaderRenderer").firstOrNull()
            ?: collectRenderers(root, "musicVisualHeaderRenderer").firstOrNull()
            ?: return null
        val name = extractRunsText(header.optObject("title")).trim()
        if (name.isBlank()) return null

        val browseId = collectStringField(header, "browseId").firstOrNull { it.startsWith("UC") }
            ?: collectStringField(root, "browseId").firstOrNull { it.startsWith("UC") }
        // Real audience text: prefer InnerTube "monthlyListenerCount", else the subscriber count.
        val listenerText = extractRunsText(header.optObject("monthlyListenerCount")).trim()
        val subscriberText = collectRenderers(header, "subscribeButtonRenderer").firstOrNull()
            ?.let { extractRunsText(it.optObject("subscriberCountText")).trim() }
            .orEmpty()
        val subtitle = listenerText.ifBlank {
            subscriberText.takeIf { it.isNotBlank() }?.let { "$it iscritti" }.orEmpty()
        }
        val artist = YouTubeArtist(
            browseId = browseId,
            channelId = browseId?.takeIf { it.startsWith("UC") },
            name = name,
            thumbnailUrl = extractThumbnail(header),
            subtitle = subtitle.takeIf { it.isNotBlank() }
        )

        val songsEndpoint = parseArtistSongsEndpoint(root)
        return YouTubeArtistDetail(
            artist = artist,
            topTracks = parseArtistTopTracks(root),
            albums = parseArtistAlbums(root),
            playlists = parseArtistPlaylists(root),
            singlesOrEps = parseArtistSinglesOrEps(root),
            videos = parseArtistVideos(root),
            songsBrowseId = songsEndpoint?.first,
            songsBrowseParams = songsEndpoint?.second
        )
    }

    internal fun parseYouTubeAlbumDetail(root: JsonElement): YouTubeAlbumDetail? {
        val header = collectRenderers(root, "musicDetailHeaderRenderer").firstOrNull()
            ?: collectRenderers(root, "musicResponsiveHeaderRenderer").firstOrNull()
            ?: return null
        val title = extractRunsText(header.optObject("title")).trim()
        if (title.isBlank()) return null

        val subtitle = extractRunsText(header.optObject("subtitle"))
        val strapline = extractRunsText(header.optObject("straplineTextOne"))
        val segments = subtitle.ifBlank { strapline }.split('•').map { it.trim() }.filter { it.isNotBlank() }
        val artist = strapline.takeIf { it.isNotBlank() } ?: firstMeaningfulSegment(segments)
        val year = firstYear(subtitle.ifBlank { strapline })
        val type = segments.firstOrNull { seg -> seg.any { it.isLetter() } && !Regex("^\\d{4}$").matches(seg) }
        val trackCount = parseTrackCount(extractRunsText(header.optObject("secondSubtitle")))

        val browseId = collectStringField(header, "browseId").firstOrNull { it.startsWith("MPRE") }
            ?: collectStringField(root, "browseId").firstOrNull { it.startsWith("MPRE") }
            ?: firstBrowseEndpointDeep(header)?.optString("browseId")?.takeIf { it.startsWith("MPRE") }
        val playlistId = collectStringField(root, "playlistId").firstOrNull()
        val album = YouTubeAlbum(
            browseId = browseId,
            playlistId = playlistId,
            title = title,
            artist = artist,
            year = year,
            thumbnailUrl = extractThumbnail(header),
            releaseYear = year?.toIntOrNull(),
            releaseDate = null,
            trackCount = trackCount,
            type = type
        )
        return YouTubeAlbumDetail(album, parseFirstShelfTracks(root))
    }

    internal fun parseYouTubePlaylistDetail(root: JsonElement): YouTubePlaylistDetail? {
        val header = collectRenderers(root, "musicDetailHeaderRenderer").firstOrNull()
            ?: collectRenderers(root, "musicEditablePlaylistDetailHeaderRenderer").firstOrNull()
                ?.let { collectRenderers(it, "musicDetailHeaderRenderer").firstOrNull() }
            ?: collectRenderers(root, "musicResponsiveHeaderRenderer").firstOrNull()
            ?: return null
        val title = extractRunsText(header.optObject("title")).trim()
        if (title.isBlank()) return null

        val subtitle = extractRunsText(header.optObject("subtitle"))
        val strapline = extractRunsText(header.optObject("straplineTextOne"))
        val segments = subtitle.ifBlank { strapline }.split('•').map { it.trim() }.filter { it.isNotBlank() }
        val author = strapline.takeIf { it.isNotBlank() } ?: firstMeaningfulSegment(segments)

        val watchPlaylist = collectStringField(root, "playlistId").firstOrNull()
        val browseId = collectStringField(root, "browseId").firstOrNull()
        val playlistId = watchPlaylist ?: browseId?.removePrefix("VL")?.takeIf { it.isNotBlank() }
        val playlist = YouTubePlaylist(playlistId, browseId, title, author, null, extractThumbnail(header))
        return YouTubePlaylistDetail(playlist, parsePlaylistTracks(root), playlistContinuation(root))
    }

    /** Continuation token scoped to the playlist shelf, falling back to a deep search. */
    private fun playlistContinuation(root: JsonElement): String? {
        collectRenderers(root, "musicPlaylistShelfRenderer").firstOrNull()?.let { shelf ->
            findContinuationToken(shelf)?.let { return it }
        }
        return findContinuationToken(root)
    }

    private fun parseArtistTopTracks(root: JsonElement): List<Track> {
        val shelves = collectRenderers(root, "musicShelfRenderer")
        if (shelves.isEmpty()) return emptyList()
        val songShelf = shelves.firstOrNull { shelf ->
            extractRunsText(shelf.optObject("title")).contains("song", ignoreCase = true)
        }
        val candidates = if (songShelf != null) listOf(songShelf) else shelves
        for (shelf in candidates) {
            val tracks = collectRenderers(shelf, "musicResponsiveListItemRenderer")
                .mapNotNull { parseTrackFromItem(it) }
            if (tracks.isNotEmpty()) return tracks
        }
        return emptyList()
    }

    private fun carouselTitle(carousel: JsonObject): String =
        extractRunsText(
            carousel.optObject("header")
                ?.optObject("musicCarouselShelfBasicHeaderRenderer")
                ?.optObject("title")
        ).lowercase()

    private fun artistAlbumsFromCarousels(root: JsonElement, predicate: (String) -> Boolean): List<YouTubeAlbum> {
        val out = ArrayList<YouTubeAlbum>()
        val seen = HashSet<String>()
        for (carousel in collectRenderers(root, "musicCarouselShelfRenderer")) {
            if (!predicate(carouselTitle(carousel))) continue
            for (item in collectRenderers(carousel, "musicTwoRowItemRenderer")) {
                try {
                    val title = extractRunsText(item.optObject("title")).trim()
                    if (title.isBlank()) continue
                    val browseId = item.optObject("navigationEndpoint")
                        ?.optObject("browseEndpoint")?.optString("browseId")
                    if (browseId != null && !browseId.startsWith("MPRE")) continue
                    val subtitle = extractRunsText(item.optObject("subtitle"))
                    val year = firstYear(subtitle)
                    val type = subtitle.split('•').map { it.trim() }
                        .firstOrNull { it.any { c -> c.isLetter() } && !Regex("^\\d{4}$").matches(it) }
                    val key = browseId ?: "${normalizedNameKey(title)}|${year ?: ""}"
                    if (!seen.add(key)) continue
                    out.add(
                        YouTubeAlbum(
                            browseId = browseId,
                            playlistId = null,
                            title = title,
                            artist = null,
                            year = year,
                            thumbnailUrl = extractThumbnail(item),
                            releaseYear = year?.toIntOrNull(),
                            type = type
                        )
                    )
                } catch (_: Exception) {
                }
            }
        }
        return out.take(50)
    }

    private fun parseArtistAlbums(root: JsonElement): List<YouTubeAlbum> =
        artistAlbumsFromCarousels(root) { t -> t.contains("album") && !t.contains("featured") }

    private fun parseArtistSinglesOrEps(root: JsonElement): List<YouTubeAlbum> =
        artistAlbumsFromCarousels(root) { t -> t.contains("single") || t.contains("ep") }

    /** Musical videos (navigationEndpoint.watchEndpoint) as playable `yt_` tracks. */
    private fun parseArtistVideos(root: JsonElement): List<Track> {
        val out = ArrayList<Track>()
        val seen = HashSet<String>()
        for (carousel in collectRenderers(root, "musicCarouselShelfRenderer")) {
            if (!carouselTitle(carousel).contains("video")) continue
            for (item in collectRenderers(carousel, "musicTwoRowItemRenderer")) {
                try {
                    val title = extractRunsText(item.optObject("title")).trim()
                    if (title.isBlank()) continue
                    val videoId = item.optObject("navigationEndpoint")
                        ?.optObject("watchEndpoint")?.optString("videoId")
                        ?: continue
                    val id = "yt_$videoId"
                    if (!seen.add(id)) continue
                    val artist = extractRunsText(item.optObject("subtitle")).substringBefore('•').trim()
                    out.add(
                        Track(
                            id = id,
                            title = title,
                            artist = artist.ifBlank { "YouTube Music" },
                            album = null,
                            durationMs = 0L,
                            coverUrl = extractThumbnail(item),
                            streamUrl = null,
                            provider = "YouTube",
                            isVideo = true
                        )
                    )
                } catch (_: Exception) {
                }
            }
        }
        return out.take(50)
    }

    private fun parseArtistPlaylists(root: JsonElement): List<YouTubePlaylist> {
        val out = ArrayList<YouTubePlaylist>()
        val seen = HashSet<String>()
        for (carousel in collectRenderers(root, "musicCarouselShelfRenderer")) {
            for (item in collectRenderers(carousel, "musicTwoRowItemRenderer")) {
                try {
                    val title = extractRunsText(item.optObject("title")).trim()
                    if (title.isBlank()) continue
                    val browseId = item.optObject("navigationEndpoint")
                        ?.optObject("browseEndpoint")?.optString("browseId")
                    if (browseId == null || !browseId.startsWith("VL")) continue
                    val playlistId = browseId.removePrefix("VL")
                    if (playlistId.isBlank() || !seen.add(playlistId)) continue
                    out.add(
                        YouTubePlaylist(
                            playlistId = playlistId,
                            browseId = browseId,
                            title = title,
                            author = null,
                            trackCount = null,
                            thumbnailUrl = extractThumbnail(item)
                        )
                    )
                } catch (_: Exception) {
                }
            }
        }
        return out.take(50)
    }

    private fun parseFirstShelfTracks(root: JsonElement): List<Track> {
        for (shelf in collectRenderers(root, "musicShelfRenderer")) {
            val tracks = collectRenderers(shelf, "musicResponsiveListItemRenderer")
                .mapNotNull { parseTrackFromItem(it) }
            if (tracks.isNotEmpty()) return tracks
        }
        return emptyList()
    }

    private fun parsePlaylistTracks(root: JsonElement): List<Track> {
        for (shelf in collectRenderers(root, "musicPlaylistShelfRenderer")) {
            val tracks = collectRenderers(shelf, "musicResponsiveListItemRenderer")
                .mapNotNull { parseTrackFromItem(it) }
            if (tracks.isNotEmpty()) return tracks
        }
        return parseFirstShelfTracks(root)
    }

    /** Parses a full artist songs page (tracks + real continuation token, if any). */
    internal fun parseYouTubeSongsPage(root: JsonElement): YouTubeSongsPage =
        YouTubeSongsPage(parsePlaylistTracks(root), findContinuationToken(root))

    /** Deep-searches a browse response for the first real continuation token. */
    private fun findContinuationToken(root: JsonElement): String? {
        var result: String? = null
        fun walk(el: JsonElement) {
            if (result != null) return
            when {
                el.isJsonObject -> {
                    for ((k, v) in el.asJsonObject.entrySet()) {
                        if (result != null) break
                        if ((k == "continuation" || k == "token") && v.isJsonPrimitive && v.asJsonPrimitive.isString) {
                            result = v.asString
                        } else if (v.isJsonObject || v.isJsonArray) {
                            walk(v)
                        }
                    }
                }
                el.isJsonArray -> for (c in el.asJsonArray) {
                    if (result != null) break
                    walk(c)
                }
            }
        }
        walk(root)
        return result
    }

    /** Reads the "Show all songs" browse endpoint from the artist "Top songs" shelf. */
    private fun parseArtistSongsEndpoint(root: JsonElement): Pair<String?, String?>? {
        val shelves = collectRenderers(root, "musicShelfRenderer")
        val shelf = shelves.firstOrNull {
            extractRunsText(it.optObject("title")).contains("song", ignoreCase = true)
        } ?: shelves.firstOrNull() ?: return null
        val browse = shelf.optObject("bottomEndpoint")?.optObject("browseEndpoint") ?: return null
        val id = browse.optString("browseId").orEmpty().takeIf { it.isNotBlank() } ?: return null
        val params = browse.optString("params").orEmpty().takeIf { it.isNotBlank() }
        return id to params
    }

    // --- Parsing helpers --------------------------------------------------------------------------

    private fun collectRenderers(
        element: JsonElement?,
        key: String,
        output: MutableList<JsonObject> = mutableListOf(),
        depth: Int = 0
    ): MutableList<JsonObject> {
        if (element == null || depth > 25) return output
        if (element.isJsonObject) {
            val obj = element.asJsonObject
            val child = obj.get(key)
            if (child != null && child.isJsonObject) output.add(child.asJsonObject)
            for (entry in obj.entrySet()) {
                collectRenderers(entry.value, key, output, depth + 1)
            }
        } else if (element.isJsonArray) {
            for (e in element.asJsonArray) {
                collectRenderers(e, key, output, depth + 1)
            }
        }
        return output
    }

    private fun collectStringField(
        element: JsonElement?,
        field: String,
        output: MutableList<String> = mutableListOf(),
        depth: Int = 0
    ): MutableList<String> {
        if (element == null || depth > 25) return output
        if (element.isJsonObject) {
            val obj = element.asJsonObject
            val v = obj.get(field)
            if (v != null && v.isJsonPrimitive) {
                try {
                    output.add(v.asString)
                } catch (_: Exception) {
                }
            }
            for (entry in obj.entrySet()) {
                collectStringField(entry.value, field, output, depth + 1)
            }
        } else if (element.isJsonArray) {
            for (e in element.asJsonArray) {
                collectStringField(e, field, output, depth + 1)
            }
        }
        return output
    }

    private fun JsonObject.optObject(name: String): JsonObject? {
        val e = get(name) ?: return null
        return if (e.isJsonObject) e.asJsonObject else null
    }

    private fun JsonObject.optString(name: String): String? {
        val e = get(name) ?: return null
        if (!e.isJsonPrimitive) return null
        return try {
            e.asString
        } catch (_: Exception) {
            null
        }
    }

    private fun flexColumnText(item: JsonObject, index: Int): JsonObject? {
        val cols = item.get("flexColumns") ?: return null
        if (!cols.isJsonArray) return null
        val arr = cols.asJsonArray
        if (index < 0 || index >= arr.size()) return null
        val colElem = arr.get(index)
        if (!colElem.isJsonObject) return null
        return colElem.asJsonObject
            .optObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optObject("text")
    }

    private fun subtitleText(item: JsonObject): String {
        val text = flexColumnText(item, 1) ?: return ""
        return extractRunsText(text).replace('•', ' ').replace(Regex("\\s+"), " ").trim()
    }

    private fun subtitleSegments(item: JsonObject): List<String> {
        val text = flexColumnText(item, 1) ?: return emptyList()
        return extractRunsText(text).split('•').map { it.trim() }.filter { it.isNotBlank() }
    }

    private fun firstBrowseEndpoint(textObj: JsonObject?): JsonObject? {
        val runs = textObj?.get("runs") ?: return null
        if (!runs.isJsonArray) return null
        for (run in runs.asJsonArray) {
            if (!run.isJsonObject) continue
            val browse = run.asJsonObject.optObject("navigationEndpoint")?.optObject("browseEndpoint")
            if (browse != null) return browse
        }
        return null
    }

    private fun firstBrowseEndpointDeep(element: JsonElement?, depth: Int = 0): JsonObject? {
        if (element == null || depth > 25) return null
        if (element.isJsonObject) {
            val obj = element.asJsonObject
            obj.optObject("browseEndpoint")?.let { return it }
            for (entry in obj.entrySet()) {
                firstBrowseEndpointDeep(entry.value, depth + 1)?.let { return it }
            }
        } else if (element.isJsonArray) {
            for (e in element.asJsonArray) {
                firstBrowseEndpointDeep(e, depth + 1)?.let { return it }
            }
        }
        return null
    }

    private fun pageType(browseEndpoint: JsonObject?): String? =
        browseEndpoint?.optObject("browseEndpointContextSupportedConfigs")
            ?.optObject("browseEndpointContextMusicConfig")
            ?.optString("pageType")

    private fun classifyByPageType(pt: String?): YouTubeDiscoveryKind = when (pt) {
        "MUSIC_PAGE_TYPE_ARTIST" -> YouTubeDiscoveryKind.ARTIST
        "MUSIC_PAGE_TYPE_ALBUM" -> YouTubeDiscoveryKind.ALBUM
        "MUSIC_PAGE_TYPE_PLAYLIST" -> YouTubeDiscoveryKind.PLAYLIST
        else -> YouTubeDiscoveryKind.UNKNOWN
    }

    /**
     * Resolves the entity kind. A present-but-unsupported [pageType] (e.g. USER_CHANNEL, PODCAST)
     * is authoritative and yields UNKNOWN, so profiles/podcasts are never misclassified by id shape.
     */
    private fun classifyEntity(pageType: String?, browseId: String?, subtitle: String): YouTubeDiscoveryKind {
        if (pageType != null) return classifyByPageType(pageType)
        val byId = classifyDiscoveryBrowseId(browseId)
        if (byId != YouTubeDiscoveryKind.UNKNOWN) return byId
        return classifyBySubtitle(subtitle)
    }

    private fun extractWatchPlaylistId(textObj: JsonObject?): String? {
        val runs = textObj?.get("runs") ?: return null
        if (!runs.isJsonArray) return null
        for (run in runs.asJsonArray) {
            if (!run.isJsonObject) continue
            val nav = run.asJsonObject.optObject("navigationEndpoint") ?: continue
            nav.optObject("watchEndpoint")?.optString("playlistId")?.takeIf { it.isNotBlank() }?.let { return it }
            nav.optObject("watchPlaylistEndpoint")?.optString("playlistId")?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun extractChannelId(textObj: JsonObject?): String? {
        val runs = textObj?.get("runs") ?: return null
        if (!runs.isJsonArray) return null
        for (run in runs.asJsonArray) {
            if (!run.isJsonObject) continue
            val base = run.asJsonObject.optObject("navigationEndpoint")
                ?.optObject("browseEndpoint")?.optString("canonicalBaseUrl") ?: continue
            val match = Regex("/channel/([A-Za-z0-9_-]+)").find(base) ?: continue
            return match.groupValues[1]
        }
        return null
    }

    private fun classifyBySubtitle(subtitle: String): YouTubeDiscoveryKind {
        val s = subtitle.lowercase()
        return when {
            Regex("\\bartist\\b").containsMatchIn(s) -> YouTubeDiscoveryKind.ARTIST
            Regex("\\balbum\\b|\\bsingle\\b|\\bep\\b").containsMatchIn(s) -> YouTubeDiscoveryKind.ALBUM
            Regex("\\bplaylist\\b").containsMatchIn(s) -> YouTubeDiscoveryKind.PLAYLIST
            else -> YouTubeDiscoveryKind.UNKNOWN
        }
    }

    private fun firstMeaningfulSegment(segments: List<String>): String? =
        segments.firstOrNull { it.isNotBlank() && !isKindWord(it) && !isYearToken(it) }

    private fun isKindWord(value: String): Boolean =
        KIND_WORDS.contains(value.trim().lowercase())

    private fun isYearToken(value: String): Boolean = Regex("^\\d{4}$").matches(value.trim())

    private fun firstYear(value: String): String? =
        Regex("\\b(\\d{4})\\b").find(value)?.groupValues?.get(1)

    private fun parseTrackCount(value: String): Int? {
        val match = Regex("(\\d+)\\s*(brano|brani|track|tracks|song|songs)", RegexOption.IGNORE_CASE)
            .find(value) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    private fun normalizedNameKey(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]"), "")

    private val KIND_WORDS = setOf(
        "song", "songs", "video", "videos", "album", "albums", "single", "singles",
        "ep", "eps", "playlist", "playlists", "artist", "artists"
    )
}
