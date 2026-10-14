package org.sonora.android.api

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.sonora.android.api.lyrics.LyricsRank
import java.io.IOException
import java.net.URLEncoder
import java.util.regex.Pattern

/**
 * Client for fetching synchronized and plain text lyrics from LRCLIB (https://lrclib.net).
 * Includes robust LRC timestamp parsing.
 */
class LrclibClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    cacheDir: java.io.File? = null
) {

    companion object {
        const val DEFAULT_BASE_URL = "https://lrclib.net"
        private const val USER_AGENT = "Sonora/1.0.0 (https://github.com/sonora/sonora-android)"

        /**
         * Bump when matching rules change so previously cached (possibly wrong) lyrics are dropped.
         * v2: strict title+artist(+duration) verification of every search candidate.
         */
        private const val CACHE_VERSION = "v2"

        // Matches timestamps like [01:23.45], [00:15.71], [02:10.123], [03:45]
        private val TIMESTAMP_PATTERN: Pattern = Pattern.compile(
            "\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?\\]"
        )

        // Enhanced LRC word timestamps like <00:15.71> inside a line
        private val WORD_TAG_PATTERN: Pattern = Pattern.compile(
            "<(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?>"
        )
    }

    private val client = HttpClientProvider.okHttpClient
    private val gson: Gson = HttpClientProvider.gson
    private val cache = LyricsCache(cacheDir)

    fun clearCache() = cache.clear()

    /**
     * Fetches lyrics for a given Track domain object.
     */
    suspend fun getLyrics(track: Track): Result<Lyrics> {
        val durationSec = if (track.durationMs > 0) track.durationMs / 1000 else null
        return getLyrics(
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationSeconds = durationSec
        )
    }

    /**
     * Fetches lyrics using title, artist, optional album, and duration.
     * Attempts exact match first, falling back to search query if exact match fails.
     */
    suspend fun getLyrics(
        title: String,
        artist: String,
        album: String? = null,
        durationSeconds: Long? = null
    ): Result<Lyrics> = withContext(Dispatchers.IO) {
        val cacheKey = "$CACHE_VERSION|$title|$artist|$durationSeconds"
        cache.read(cacheKey)?.let { cached ->
            if (cached.notFound) {
                return@withContext Result.failure(IOException("No lyrics found for '$title' by '$artist'"))
            }
            return@withContext Result.success(
                Lyrics(
                    id = cached.id.orEmpty(),
                    plainLyrics = cached.plainLyrics,
                    syncedLyrics = cached.syncedLyrics,
                    lines = parseLrc(cached.syncedLyrics)
                )
            )
        }

        val (extractedArtist, extractedTitle) = extractArtistAndTitle(title, artist)
        val cleanTitle = cleanTrackTitle(extractedTitle)
        val cleanArtist = cleanArtistName(extractedArtist)
        val cleanAlbum = if (!album.isNullOrBlank() && 
                             !album.equals("YouTube", ignoreCase = true) && 
                             !album.equals(title, ignoreCase = true)) album else null

        // 1. Try exact match endpoint: /api/get with album & duration
        val exactResult = fetchExactLyrics(cleanTitle, cleanArtist, cleanAlbum, durationSeconds)
        if (exactResult.isSuccess) {
            return@withContext store(cacheKey, exactResult)
        }

        // 2. Try exact match endpoint without album or duration constraint
        if (cleanAlbum != null || durationSeconds != null) {
            val relaxedExact = fetchExactLyrics(cleanTitle, cleanArtist, null, null)
            if (relaxedExact.isSuccess) {
                return@withContext store(cacheKey, relaxedExact)
            }
        }

        // 3. Try search endpoint: /api/search?track_name=...&artist_name=... (verified candidates only)
        val searchByNameResult = searchLyricsByFields(cleanTitle, cleanArtist, durationSeconds)
        if (searchByNameResult.isSuccess && searchByNameResult.getOrNull() != null) {
            return@withContext store(cacheKey, Result.success(searchByNameResult.getOrNull()!!))
        }

        // 4. Try general search: /api/search?q=... (still verified against title+artist)
        val searchByQueryResult = searchLyricsByQuery("$cleanTitle $cleanArtist", cleanTitle, cleanArtist, durationSeconds)
        if (searchByQueryResult.isSuccess && searchByQueryResult.getOrNull() != null) {
            return@withContext store(cacheKey, Result.success(searchByQueryResult.getOrNull()!!))
        }

        // 5. Title-only search: allowed, but a candidate is accepted only if its artist still matches.
        if (cleanTitle.isNotBlank() && cleanTitle.length >= 3) {
            val titleOnlyResult = searchLyricsByQuery(cleanTitle, cleanTitle, cleanArtist, durationSeconds)
            if (titleOnlyResult.isSuccess && titleOnlyResult.getOrNull() != null) {
                return@withContext store(cacheKey, Result.success(titleOnlyResult.getOrNull()!!))
            }
        }

        // Return error if all attempts fail
        cache.write(cacheKey, CachedLyrics(notFound = true))
        Result.failure(IOException("No lyrics found for '$title' by '$artist'"))
    }

    private fun store(key: String, result: Result<Lyrics>): Result<Lyrics> {
        result.onSuccess { lyrics ->
            cache.write(
                key,
                CachedLyrics(
                    notFound = false,
                    id = lyrics.id,
                    plainLyrics = lyrics.plainLyrics,
                    syncedLyrics = lyrics.syncedLyrics
                )
            )
        }
        return result
    }

    /**
     * Direct query search returning all matched lyrics.
     */
    suspend fun searchLyrics(query: String): Result<List<Lyrics>> = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
            val url = "$baseUrl/api/search?q=$encodedQuery"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${response.code} from LRCLIB search"))
                }
                val body = response.body?.string().orEmpty()
                val items: List<LrclibResponseDto> = gson.fromJson(body, Array<LrclibResponseDto>::class.java)?.toList() ?: emptyList()
                val lyricsList = items.map { it.toDomainLyrics() }
                Result.success(lyricsList)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun fetchExactLyrics(
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Long?
    ): Result<Lyrics> = withContext(Dispatchers.IO) {
        try {
            val encodedTitle = URLEncoder.encode(title, "UTF-8")
            val encodedArtist = URLEncoder.encode(artist, "UTF-8")
            val urlBuilder = StringBuilder("$baseUrl/api/get?track_name=$encodedTitle&artist_name=$encodedArtist")

            if (!album.isNullOrBlank()) {
                urlBuilder.append("&album_name=").append(URLEncoder.encode(album, "UTF-8"))
            }
            if (durationSeconds != null && durationSeconds > 0) {
                urlBuilder.append("&duration=").append(durationSeconds)
            }

            val request = Request.Builder()
                .url(urlBuilder.toString())
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${response.code}: track not found in /api/get"))
                }
                val body = response.body?.string().orEmpty()
                val dto = gson.fromJson(body, LrclibResponseDto::class.java)
                    ?: return@withContext Result.failure(IOException("Null response from LRCLIB"))

                Result.success(dto.toDomainLyrics())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun searchLyricsByFields(
        title: String,
        artist: String,
        durationSeconds: Long?
    ): Result<Lyrics?> = withContext(Dispatchers.IO) {
        try {
            val encodedTitle = URLEncoder.encode(title, "UTF-8")
            val encodedArtist = URLEncoder.encode(artist, "UTF-8")
            val url = "$baseUrl/api/search?track_name=$encodedTitle&artist_name=$encodedArtist"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${response.code}"))
                }
                val body = response.body?.string().orEmpty()
                val items: List<LrclibResponseDto> = gson.fromJson(body, Array<LrclibResponseDto>::class.java)?.toList() ?: emptyList()
                Result.success(pickVerified(items, title, artist, durationSeconds))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun searchLyricsByQuery(
        query: String,
        title: String,
        artist: String,
        durationSeconds: Long?
    ): Result<Lyrics?> = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
            val url = "$baseUrl/api/search?q=$encodedQuery"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${response.code}"))
                }
                val body = response.body?.string().orEmpty()
                val items: List<LrclibResponseDto> = gson.fromJson(body, Array<LrclibResponseDto>::class.java)?.toList() ?: emptyList()
                Result.success(pickVerified(items, title, artist, durationSeconds))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Returns the first candidate whose title/artist (and duration, when known) reliably match the
     * query, preferring synced lyrics over plain. Unverified candidates are never returned, so a
     * generic title can no longer surface another artist's song.
     */
    private fun pickVerified(
        items: List<LrclibResponseDto>,
        title: String,
        artist: String,
        durationSeconds: Long?
    ): Lyrics? {
        val queryMs = durationSeconds?.let { it * 1000 } ?: 0L
        val acceptable = items.filter { dto ->
            LyricsRank.matches(
                title = title,
                artist = artist,
                durationMs = queryMs,
                hitTitle = dto.trackName.orEmpty(),
                hitArtist = dto.artistName.orEmpty(),
                hitDurationMs = dto.duration?.let { (it * 1000).toLong() }
            )
        }
        if (acceptable.isEmpty()) return null
        val best = acceptable.firstOrNull { !it.syncedLyrics.isNullOrBlank() }
            ?: acceptable.firstOrNull { !it.plainLyrics.isNullOrBlank() }
        return best?.toDomainLyrics()
    }

    /**
     * Parses LRC synchronized timestamp content into ordered [LyricLine] list.
     * Supports formats like:
     *   [00:15.71] Line text
     *   [01:04.250] Line text
     *   [00:10][00:20] Repeated line
     */
    fun parseLrc(lrcContent: String?): List<LyricLine> {
        if (lrcContent.isNullOrBlank()) return emptyList()

        val parsedLines = mutableListOf<LyricLine>()

        lrcContent.lineSequence().forEach { rawLine ->
            val trimmedLine = rawLine.trim()
            if (trimmedLine.isEmpty()) return@forEach

            // Ignore metadata tags like [ar:Artist], [ti:Title], [length:03:40], [re:...], etc.
            if (trimmedLine.startsWith("[ar:") ||
                trimmedLine.startsWith("[ti:") ||
                trimmedLine.startsWith("[al:") ||
                trimmedLine.startsWith("[by:") ||
                trimmedLine.startsWith("[offset:") ||
                trimmedLine.startsWith("[length:") ||
                trimmedLine.startsWith("[re:")
            ) {
                return@forEach
            }

            val matcher = TIMESTAMP_PATTERN.matcher(trimmedLine)
            val lineTimestamps = mutableListOf<Long>()

            while (matcher.find()) {
                val minutes = matcher.group(1)?.toLongOrNull() ?: 0L
                val seconds = matcher.group(2)?.toLongOrNull() ?: 0L
                val fractionStr = matcher.group(3).orEmpty()

                val fractionMs = when (fractionStr.length) {
                    1 -> fractionStr.toLong() * 100L
                    2 -> fractionStr.toLong() * 10L
                    3 -> fractionStr.toLong()
                    else -> 0L
                }

                val timestampMs = (minutes * 60_000L) + (seconds * 1_000L) + fractionMs
                lineTimestamps.add(timestampMs)
            }

            if (lineTimestamps.isNotEmpty()) {
                val stripped = TIMESTAMP_PATTERN.matcher(trimmedLine).replaceAll("").trim()
                val (text, wordTimings) = parseEnhancedWordTimings(stripped)
                for (timestampMs in lineTimestamps) {
                    parsedLines.add(
                        LyricLine(timeMs = timestampMs, text = text, wordTimings = wordTimings)
                    )
                }
            }
        }

        // Return chronological sorted order
        return parsedLines.sortedBy { it.timeMs }
    }

    /**
     * Parses Enhanced LRC word tags (e.g. `<00:12.34>Hello <00:12.90>world`).
     * Returns the display text plus real word timings, or the untouched line when
     * fewer than two word tags are present.
     */
    private fun parseEnhancedWordTimings(line: String): Pair<String, List<WordTiming>> {
        val matcher = WORD_TAG_PATTERN.matcher(line)
        val tagStarts = ArrayList<Int>()
        val tagEnds = ArrayList<Int>()
        val tagTimes = ArrayList<Long>()
        while (matcher.find()) {
            val minutes = matcher.group(1)?.toLongOrNull() ?: 0L
            val seconds = matcher.group(2)?.toLongOrNull() ?: 0L
            val fractionStr = matcher.group(3).orEmpty()
            val fractionMs = when (fractionStr.length) {
                1 -> fractionStr.toLong() * 100L
                2 -> fractionStr.toLong() * 10L
                3 -> fractionStr.toLong()
                else -> 0L
            }
            tagStarts.add(matcher.start())
            tagEnds.add(matcher.end())
            tagTimes.add((minutes * 60_000L) + (seconds * 1_000L) + fractionMs)
        }
        if (tagTimes.size < 2) return line to emptyList()

        val words = ArrayList<WordTiming>(tagTimes.size)
        for (i in tagTimes.indices) {
            val textStart = tagEnds[i]
            val textEnd = if (i + 1 < tagTimes.size) tagStarts[i + 1] else line.length
            val wordText = line.substring(textStart, textEnd.coerceIn(textStart, line.length)).trim()
            if (wordText.isEmpty()) continue
            val startMs = tagTimes[i]
            val endMs = if (i + 1 < tagTimes.size) tagTimes[i + 1] else startMs + 500L
            words.add(WordTiming(text = wordText, startMs = startMs, endMs = endMs))
        }
        if (words.size < 2) return line to emptyList()
        return words.joinToString(" ") { it.text } to words
    }

    private fun extractArtistAndTitle(rawTitle: String, rawArtist: String): Pair<String, String> {
        val trimmedTitle = rawTitle.trim()
        val trimmedArtist = rawArtist.trim()

        // Handle YouTube title formats like "Artist - Title"
        if (trimmedTitle.contains(" - ")) {
            val parts = trimmedTitle.split(" - ", limit = 2)
            val potentialArtist = parts[0].trim()
            val potentialTitle = parts[1].trim()

            // If given artist is generic or YouTube, use the extracted artist
            if (trimmedArtist.isBlank() ||
                trimmedArtist.equals("YouTube", ignoreCase = true) ||
                trimmedArtist.equals("YouTube Music", ignoreCase = true) ||
                trimmedArtist.contains("Topic", ignoreCase = true) ||
                potentialArtist.contains(trimmedArtist, ignoreCase = true)
            ) {
                return potentialArtist to potentialTitle
            }
        }

        return trimmedArtist to trimmedTitle
    }

    private fun cleanTrackTitle(title: String): String {
        return title
            // Remove pipe and anything after (e.g. "| Official Video", "| Napalm Records")
            .replace(Regex("\\|.*$"), "")
            // Remove brackets containing video/audio tags
            .replace(Regex("\\[.*?(Official|Video|Audio|Lyric|Visualizer|Clip|HD|4K|Remaster|Music).*?\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\(.*?(Official|Video|Audio|Lyric|Visualizer|Clip|HD|4K|Remaster|Music|Live).*?\\)", RegexOption.IGNORE_CASE), "")
            // Remove features and with tags
            .replace(Regex("\\s*\\(feat\\..*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\[feat\\..*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(with.*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*feat\\..*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*ft\\..*$", RegexOption.IGNORE_CASE), "")
            // Remove dash suffixes like " - Official Video"
            .replace(Regex("\\s*-\\s*(Official|Lyric|Visualizer|Audio|Clip|Remaster).*", RegexOption.IGNORE_CASE), "")
            // Remove enclosing quotes
            .replace(Regex("^[\"']+|[\"']+$"), "")
            .trim()
    }

    private fun cleanArtistName(artist: String): String {
        return artist
            .replace(Regex("\\s*-\\s*Topic", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*VEVO", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*Official", RegexOption.IGNORE_CASE), "")
            .split(",", "&", "feat.", "ft.", "x ")[0]
            .trim()
    }

    private fun LrclibResponseDto.toDomainLyrics(): Lyrics {
        val lines = parseLrc(syncedLyrics)
        val resolvedPlain = plainLyrics?.takeIf { it.isNotBlank() }
            ?: lines.joinToString("\n") { it.text }.takeIf { it.isNotBlank() }

        return Lyrics(
            id = id?.toString() ?: name.orEmpty(),
            plainLyrics = resolvedPlain,
            syncedLyrics = syncedLyrics,
            lines = lines
        )
    }

    internal data class LrclibResponseDto(
        val id: Long? = null,
        val name: String? = null,
        val trackName: String? = null,
        val artistName: String? = null,
        val albumName: String? = null,
        val duration: Double? = null,
        val instrumental: Boolean? = null,
        val plainLyrics: String? = null,
        val syncedLyrics: String? = null
    )
}

/**
 * Returns the index of the last [LyricLine] whose timeMs is <= [positionMs],
 * or -1 if none (e.g. empty list or position before first line).
 */
fun activeLineIndex(lines: List<LyricLine>, positionMs: Long): Int {
    var low = 0
    var high = lines.size - 1
    var result = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (lines[mid].timeMs <= positionMs) {
            result = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return result
}
