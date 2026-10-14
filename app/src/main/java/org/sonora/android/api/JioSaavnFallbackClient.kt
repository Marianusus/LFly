package org.sonora.android.api

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Resilient, high-speed streaming client utilizing JioSaavn's public catalog.
 * Provides direct high-bitrate AAC (up to 320 kbps & 160 kbps) streams with:
 * 1. Primary high-speed Vercel REST mirrors returning complete direct CDN URLs.
 * 2. Automatic multi-mirror failover across active healthy instances.
 * 3. DES-decrypted fallback to JioSaavn's official API if all REST mirrors fail.
 */
open class JioSaavnFallbackClient(
    mirrors: List<String> = DEFAULT_REST_MIRRORS,
    private val healthCallback: ((url: String, success: Boolean, latencyMs: Long, error: String?) -> Unit)? = null
) {

    companion object {
        private const val TAG = "JioSaavnClient"
        const val DEFAULT_BASE_URL = "https://jiosaavn-api-black.vercel.app"
        const val OFFICIAL_BASE_URL = "https://www.jiosaavn.com/api.php"
        private const val PROVIDER_NAME = "JioSaavn"
        private const val DES_KEY = "38346591"
        private const val CIPHER_ALGORITHM = "DES/ECB/PKCS5Padding"

        const val BLACKOUT_TIMEOUT_MS = 3500L
        const val DEGRADATION_COOLDOWN_MS = 5 * 60 * 1000L // 5 minutes cooldown

        val DEFAULT_REST_MIRRORS = listOf(
            "https://jiosaavn-api-black.vercel.app/api",
            "https://jiosaavn-api-sage.vercel.app/api",
            "https://jiosaavn-api-self.vercel.app/api",
            "https://jiosaavn-api-nu.vercel.app/api",
            "https://saavn.me/api",
            "https://jiosaavn-api-black.vercel.app",
            "https://jiosaavn-api-sage.vercel.app",
            "https://jiosaavn-api-self.vercel.app"
        )

        val CANDIDATE_SELF_HEALING_MIRRORS = listOf(
            "https://jiosaavn-api-black.vercel.app/api",
            "https://jiosaavn-api-sage.vercel.app/api",
            "https://jiosaavn-api-self.vercel.app/api",
            "https://jiosaavn-api-nu.vercel.app/api",
            "https://saavn.me/api",
            "https://saavn.dev/api",
            "https://jio-music-api.vercel.app/api",
            "https://jiosaavn-api-black.vercel.app",
            "https://jiosaavn-api-sage.vercel.app"
        )
    }

    private val mirrorList = CopyOnWriteArrayList<String>(
        if (mirrors.isNotEmpty()) mirrors else DEFAULT_REST_MIRRORS
    )

    private val degradedMirrors = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private val gson: Gson = HttpClientProvider.gson

    /**
     * Backward-compatible single-URL constructor.
     */
    constructor(
        baseUrl: String,
        healthCallback: ((url: String, success: Boolean, latencyMs: Long, error: String?) -> Unit)? = null
    ) : this(listOf(baseUrl), healthCallback)

    /**
     * Dynamically updates the active healthy mirrors pool.
     */
    open fun updateMirrors(mirrors: List<String>) {
        if (mirrors.isNotEmpty()) {
            mirrorList.clear()
            mirrorList.addAll(mirrors)
            Log.d(TAG, "Updated JioSaavn active mirrors pool: $mirrors")
        }
    }

    /**
     * Marks a mirror as degraded and temporarily removes it from active rotation.
     */
    fun markMirrorDegraded(mirrorUrl: String, reason: String? = null) {
        val normalized = normalizeUrl(mirrorUrl)
        val until = System.currentTimeMillis() + DEGRADATION_COOLDOWN_MS
        degradedMirrors[normalized] = until
        Log.w(TAG, "Blackout detected: marked JioSaavn mirror degraded for 5m: $normalized (reason=$reason)")
    }

    /**
     * Checks if a mirror is healthy and not in a blackout degradation cooldown.
     */
    fun isMirrorHealthy(mirrorUrl: String): Boolean {
        val normalized = normalizeUrl(mirrorUrl)
        val until = degradedMirrors[normalized] ?: return true
        return System.currentTimeMillis() > until
    }

    /**
     * Self-heals the mirror pool by restoring recovered mirrors and adding candidate endpoints.
     */
    fun selfHealMirrors(): List<String> {
        val now = System.currentTimeMillis()
        val expired = degradedMirrors.entries.filter { it.value <= now }.map { it.key }
        expired.forEach { degradedMirrors.remove(it) }

        // Ensure candidate mirrors are present in pool
        for (candidate in CANDIDATE_SELF_HEALING_MIRRORS) {
            val normalized = normalizeUrl(candidate)
            if (!mirrorList.contains(normalized)) {
                mirrorList.add(normalized)
            }
        }
        return getPrioritizedMirrors()
    }

    /**
     * Returns prioritized mirrors, with currently healthy instances ranked first.
     */
    fun getPrioritizedMirrors(): List<String> {
        val now = System.currentTimeMillis()
        val current = mirrorList.toList().ifEmpty { DEFAULT_REST_MIRRORS }
        return current.sortedBy { mirror ->
            val degradedUntil = degradedMirrors[normalizeUrl(mirror)] ?: 0L
            if (degradedUntil > now) 1 else 0
        }
    }

    /**
     * Resolves the highest quality audio stream for a given track title and artist.
     * Uses aggressive metadata cleaning and automatic fallback to title-only search
     * filtered by artist if the combined query returns no results.
     */
    open suspend fun resolveStream(
        title: String,
        artist: String,
        preferredQuality: AudioQuality = AudioQuality.HIGH
    ): Result<StreamSource> = withContext(Dispatchers.IO) {
        try {
            val cleanTitle = cleanTitle(title)
            val cleanArtist = cleanArtist(artist)

            // 1. Try combined query: cleanTitle + cleanArtist
            val combinedQuery = "$cleanTitle $cleanArtist".trim()
            val songs = searchSongs(combinedQuery, limit = 5).getOrElse { emptyList() }

            if (songs.isNotEmpty()) {
                val matchedSong = findBestMatch(songs, cleanTitle, cleanArtist)
                if (matchedSong != null) {
                    return@withContext resolveBestStream(listOf(matchedSong), preferredQuality)
                }
            }

            // 2. Automatic fallback: search with cleanTitle only, then filter results by artist
            if (cleanTitle.isNotBlank()) {
                Log.d(TAG, "Combined query '$combinedQuery' returned no songs. Falling back to search by cleanTitle: '$cleanTitle'")
                val fallbackSongs = searchSongs(cleanTitle, limit = 10).getOrElse { emptyList() }
                if (fallbackSongs.isNotEmpty()) {
                    val matchedSong = findArtistMatch(fallbackSongs, cleanTitle, cleanArtist)
                    if (matchedSong != null) {
                        Log.i(TAG, "✓ Matched song from cleanTitle fallback for artist '$cleanArtist': '${matchedSong.title}' by '${matchedSong.artist}'")
                        return@withContext resolveBestStream(listOf(matchedSong), preferredQuality)
                    }
                }
            }

            Result.failure(IOException("No JioSaavn stream candidate found for '$title - $artist'"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Searches JioSaavn for songs matching a query.
     * Iterates over healthy REST mirrors with active blackout detection (<=3.5s timeout).
     * If all REST mirrors fail, transparently falls back to official DES API.
     */
    suspend fun searchSongs(query: String, limit: Int = 10): Result<List<JioSaavnSong>> = withContext(Dispatchers.IO) {
        val prioritized = getPrioritizedMirrors()

        // 1. Try healthy REST mirrors in priority order
        for (mirror in prioritized) {
            val start = System.currentTimeMillis()
            val restResult = searchRestMirror(mirror, query, limit)
            val latency = System.currentTimeMillis() - start

            if (restResult.isSuccess) {
                val songs = restResult.getOrDefault(emptyList())
                if (songs.isNotEmpty()) {
                    degradedMirrors.remove(normalizeUrl(mirror))
                    healthCallback?.invoke(mirror, true, latency, null)
                    return@withContext Result.success(songs)
                }
            } else {
                val err = restResult.exceptionOrNull()?.message
                markMirrorDegraded(mirror, err)
                healthCallback?.invoke(mirror, false, latency, err)
                Log.w(TAG, "Mirror $mirror blackout or search failed: $err. Instantly rotating to next mirror...")
            }
        }

        // 2. Fallback to official JioSaavn API with DES decryption
        val officialStart = System.currentTimeMillis()
        val officialResult = searchOfficialApi(query, limit)
        val officialLatency = System.currentTimeMillis() - officialStart

        if (officialResult.isSuccess) {
            val songs = officialResult.getOrDefault(emptyList())
            if (songs.isNotEmpty()) {
                healthCallback?.invoke(OFFICIAL_BASE_URL, true, officialLatency, null)
                return@withContext Result.success(songs)
            }
        } else {
            val err = officialResult.exceptionOrNull()?.message
            healthCallback?.invoke(OFFICIAL_BASE_URL, false, officialLatency, err)
            Log.e(TAG, "Official JioSaavn fallback failed: $err")
        }

        Result.failure(IOException("All JioSaavn mirrors and official fallback failed for query '$query'"))
    }

    /**
     * Searches via a high-speed REST mirror with strict 3.5s timeout.
     */
    private suspend fun searchRestMirror(
        mirrorUrl: String,
        query: String,
        limit: Int
    ): Result<List<JioSaavnSong>> = withContext(Dispatchers.IO) {
        try {
            kotlinx.coroutines.withTimeoutOrNull(BLACKOUT_TIMEOUT_MS) {
                val url = buildSearchUrl(mirrorUrl, query, limit)
                val responseResult = HttpClientProvider.getJson(url)
                responseResult.fold(
                    onSuccess = { rawJson ->
                        val songs = parseRestSongs(rawJson)
                        if (songs.isNotEmpty()) {
                            Result.success(songs)
                        } else {
                            Result.failure(IOException("Empty results returned from $mirrorUrl"))
                        }
                    },
                    onFailure = { Result.failure(it) }
                )
            } ?: Result.failure(IOException("Blackout timeout: mirror $mirrorUrl exceeded ${BLACKOUT_TIMEOUT_MS}ms"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Builds the proper search URL for mirrors ending in /api or root.
     */
    fun buildSearchUrl(mirrorUrl: String, query: String, limit: Int): String {
        val cleanBase = normalizeUrl(mirrorUrl)
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        return if (cleanBase.endsWith("/api")) {
            "$cleanBase/search/songs?query=$encodedQuery&limit=$limit"
        } else {
            "$cleanBase/api/search/songs?query=$encodedQuery&limit=$limit"
        }
    }

    /**
     * Parses the JSON payload from jiosaavn-api implementations.
     */
    private fun parseRestSongs(rawJson: String): List<JioSaavnSong> {
        val cleanJson = rawJson.trim().dropWhile { it != '{' }.takeLastWhile { it != '}' }
        if (cleanJson.isBlank()) return emptyList()

        return try {
            val rootElement = JsonParser.parseString(cleanJson)
            if (!rootElement.isJsonObject) return emptyList()
            val rootObj = rootElement.asJsonObject

            val resultsArray: JsonArray? = when {
                rootObj.has("data") && rootObj.get("data").isJsonObject &&
                        rootObj.getAsJsonObject("data").has("results") -> {
                    rootObj.getAsJsonObject("data").getAsJsonArray("results")
                }
                rootObj.has("data") && rootObj.get("data").isJsonArray -> {
                    rootObj.getAsJsonArray("data")
                }
                rootObj.has("results") && rootObj.get("results").isJsonArray -> {
                    rootObj.getAsJsonArray("results")
                }
                else -> null
            }

            if (resultsArray == null || resultsArray.size() == 0) return emptyList()

            val songs = mutableListOf<JioSaavnSong>()
            for (element in resultsArray) {
                if (!element.isJsonObject) continue
                val item = element.asJsonObject

                val id = item.get("id")?.asString.orEmpty()
                val songTitle = cleanHtml(
                    item.get("name")?.asString
                        ?: item.get("title")?.asString
                        ?: item.get("song")?.asString
                        ?: ""
                )
                if (songTitle.isBlank()) continue

                // Parse artists
                val artistName = cleanHtml(extractArtistName(item))

                // Parse album
                val albumTitle = cleanHtml(
                    if (item.has("album") && item.get("album").isJsonObject) {
                        item.getAsJsonObject("album").get("name")?.asString.orEmpty()
                    } else {
                        item.get("album")?.asString.orEmpty()
                    }
                )

                // Parse duration
                val durationSec = item.get("duration")?.let {
                    if (it.isJsonPrimitive) it.asLong else 0L
                } ?: 0L

                // Parse cover artwork
                val coverUrl = extractCoverUrl(item)

                // Parse downloadUrl array containing high quality 320k & 160k AAC streams
                val downloadUrls = mutableListOf<DownloadUrlItem>()
                if (item.has("downloadUrl") && item.get("downloadUrl").isJsonArray) {
                    val dlArray = item.getAsJsonArray("downloadUrl")
                    for (dlElem in dlArray) {
                        if (dlElem.isJsonObject) {
                            val dlObj = dlElem.asJsonObject
                            val quality = dlObj.get("quality")?.asString.orEmpty()
                            val streamUrl = dlObj.get("url")?.asString
                                ?: dlObj.get("link")?.asString
                                ?: ""
                            if (streamUrl.isNotBlank()) {
                                downloadUrls.add(DownloadUrlItem(quality, streamUrl))
                            }
                        } else if (dlElem.isJsonPrimitive) {
                            val streamUrl = dlElem.asString
                            if (streamUrl.isNotBlank()) {
                                downloadUrls.add(DownloadUrlItem("auto", streamUrl))
                            }
                        }
                    }
                }

                // If media_url or encrypted_media_url exists
                var decryptedBaseUrl: String? = downloadUrls.lastOrNull()?.url
                if (item.has("encrypted_media_url")) {
                    val encUrl = item.get("encrypted_media_url")?.asString.orEmpty()
                    val decrypted = decryptMediaUrl(encUrl)
                    if (decrypted != null) {
                        decryptedBaseUrl = decrypted
                    }
                }

                songs.add(
                    JioSaavnSong(
                        id = id,
                        title = songTitle,
                        artist = artistName,
                        album = albumTitle,
                        durationSeconds = durationSec,
                        coverUrl = coverUrl,
                        downloadUrls = downloadUrls,
                        decryptedBaseUrl = decryptedBaseUrl
                    )
                )
            }

            songs
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing REST Saavn JSON: ${e.message}")
            emptyList()
        }
    }

    /**
     * Searches official JioSaavn API directly with DES media URL decryption.
     */
    private suspend fun searchOfficialApi(query: String, limit: Int): Result<List<JioSaavnSong>> = withContext(Dispatchers.IO) {
        try {
            kotlinx.coroutines.withTimeoutOrNull(BLACKOUT_TIMEOUT_MS) {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val url = "$OFFICIAL_BASE_URL?__call=search.getResults&_format=json&_marker=0&cc=in&includeMetaTags=1&p=1&n=$limit&q=$encodedQuery"

                val responseResult = HttpClientProvider.getJson(url)
                responseResult.fold(
                    onSuccess = { rawJson ->
                        val cleanJson = rawJson.trim().dropWhile { it != '{' }.takeLastWhile { it != '}' }
                        val responseDto: JioSaavnSearchResponseDto? = gson.fromJson(cleanJson, JioSaavnSearchResponseDto::class.java)

                        val songs = responseDto?.results.orEmpty().mapNotNull { dto ->
                            val encryptedUrl = dto.encryptedMediaUrl
                                ?: dto.moreInfo?.encryptedMediaUrl
                                ?: return@mapNotNull null

                            val decryptedUrl = decryptMediaUrl(encryptedUrl) ?: return@mapNotNull null

                            val songTitle = cleanHtml(dto.song ?: dto.title.orEmpty())
                            val artistName = cleanHtml(
                                dto.primaryArtists
                                    ?: dto.singers
                                    ?: dto.moreInfo?.artistMap?.primaryArtists?.firstOrNull()?.name.orEmpty()
                            )
                            val albumTitle = cleanHtml(dto.album.orEmpty())
                            val durationSec = dto.duration?.toLongOrNull()
                                ?: dto.moreInfo?.duration?.toLongOrNull()
                                ?: 0L

                            val downloadUrls = listOf(
                                DownloadUrlItem("320kbps", buildQualityUrl(decryptedUrl, AudioQuality.HIGH)),
                                DownloadUrlItem("160kbps", buildQualityUrl(decryptedUrl, AudioQuality.MEDIUM))
                            )

                            JioSaavnSong(
                                id = dto.id.orEmpty(),
                                title = songTitle,
                                artist = artistName,
                                album = albumTitle,
                                durationSeconds = durationSec,
                                coverUrl = getHighResCover(dto.image),
                                downloadUrls = downloadUrls,
                                decryptedBaseUrl = decryptedUrl
                            )
                        }

                        Result.success(songs)
                    },
                    onFailure = { Result.failure(it) }
                )
            } ?: Result.failure(IOException("Blackout timeout: official JioSaavn API exceeded ${BLACKOUT_TIMEOUT_MS}ms"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Selects and builds the highest quality full audio stream source.
     * Prioritizes direct 320 kbps AAC CDN streams and guarantees complete song playback.
     */
    private fun resolveBestStream(
        candidates: List<JioSaavnSong>,
        preferredQuality: AudioQuality
    ): Result<StreamSource> {
        val candidate = candidates.firstOrNull()
            ?: return Result.failure(IOException("No playable JioSaavn candidates available"))

        val targetBitrate = when (preferredQuality) {
            AudioQuality.ULTRA, AudioQuality.HIGH -> 320
            AudioQuality.MEDIUM -> 160
            AudioQuality.LOW -> 96
        }

        // 1. Check for exact quality match in downloadUrls
        var selectedUrl: String? = null
        var selectedBitrate: Int = targetBitrate

        val explicit320 = candidate.downloadUrls.firstOrNull {
            it.quality.contains("320") || it.url.contains("_320.mp4")
        }?.url

        val explicit160 = candidate.downloadUrls.firstOrNull {
            it.quality.contains("160") || it.url.contains("_160.mp4")
        }?.url

        val explicit96 = candidate.downloadUrls.firstOrNull {
            it.quality.contains("96") || it.url.contains("_96.mp4")
        }?.url

        when (preferredQuality) {
            AudioQuality.ULTRA, AudioQuality.HIGH -> {
                if (explicit320 != null) {
                    selectedUrl = explicit320
                    selectedBitrate = 320
                } else if (explicit160 != null) {
                    // Direct JioSaavn CDNs host 320kbps variants predictably by swapping suffix
                    selectedUrl = buildQualityUrl(explicit160, AudioQuality.HIGH)
                    selectedBitrate = 320
                }
            }
            AudioQuality.MEDIUM -> {
                if (explicit160 != null) {
                    selectedUrl = explicit160
                    selectedBitrate = 160
                } else if (explicit320 != null) {
                    selectedUrl = explicit320
                    selectedBitrate = 320
                }
            }
            AudioQuality.LOW -> {
                if (explicit96 != null) {
                    selectedUrl = explicit96
                    selectedBitrate = 96
                }
            }
        }

        // 2. If not found in downloadUrls, fall back to decryptedBaseUrl or any downloadUrl upgraded
        if (selectedUrl.isNullOrBlank()) {
            val baseFallback = candidate.decryptedBaseUrl
                ?: candidate.downloadUrls.lastOrNull()?.url
                ?: candidate.downloadUrls.firstOrNull()?.url

            if (baseFallback != null) {
                selectedUrl = buildQualityUrl(baseFallback, preferredQuality)
                selectedBitrate = targetBitrate
            }
        }

        if (selectedUrl.isNullOrBlank()) {
            return Result.failure(IOException("Unable to extract valid stream URL for ${candidate.title}"))
        }

        Log.i(TAG, "Resolved JioSaavn full audio stream: '${candidate.title}' -> $selectedUrl ($selectedBitrate kbps)")

        return Result.success(
            StreamSource(
                url = selectedUrl,
                format = "audio/mp4",
                quality = preferredQuality,
                provider = "$PROVIDER_NAME (${selectedBitrate}k)",
                bitrate = selectedBitrate,
                isPreview = false
            )
        )
    }

    /**
     * Replaces standard quality token with 320kbps or 160kbps CDN variants.
     */
    fun buildQualityUrl(rawUrl: String, quality: AudioQuality): String {
        if (rawUrl.isBlank()) return rawUrl
        val targetSuffix = when (quality) {
            AudioQuality.ULTRA, AudioQuality.HIGH -> "_320.mp4"
            AudioQuality.MEDIUM -> "_160.mp4"
            AudioQuality.LOW -> "_96.mp4"
        }
        return if (rawUrl.contains(Regex("_\\d+\\.mp4"))) {
            rawUrl.replace(Regex("_\\d+\\.mp4"), targetSuffix)
        } else if (rawUrl.endsWith(".mp4")) {
            rawUrl.replace(Regex("\\.mp4$"), targetSuffix)
        } else {
            rawUrl
        }
    }

    /**
     * Decrypts JioSaavn's DES-ECB encrypted media URL.
     */
    fun decryptMediaUrl(encryptedUrl: String): String? {
        if (encryptedUrl.isBlank()) return null
        return try {
            val keyBytes = DES_KEY.toByteArray(Charsets.UTF_8)
            val keySpec = SecretKeySpec(keyBytes, "DES")
            val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, keySpec)

            val decodedBytes = decodeBase64Safe(encryptedUrl)
            val decryptedBytes = cipher.doFinal(decodedBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeBase64Safe(input: String): ByteArray {
        return try {
            java.util.Base64.getDecoder().decode(input)
        } catch (_: Throwable) {
            android.util.Base64.decode(input, android.util.Base64.DEFAULT)
        }
    }

    private fun extractArtistName(item: JsonObject): String {
        if (item.has("artists") && item.get("artists").isJsonObject) {
            val artistsObj = item.getAsJsonObject("artists")
            if (artistsObj.has("primary") && artistsObj.get("primary").isJsonArray) {
                val primaryArray = artistsObj.getAsJsonArray("primary")
                val names = mutableListOf<String>()
                for (elem in primaryArray) {
                    if (elem.isJsonObject) {
                        elem.asJsonObject.get("name")?.asString?.let { names.add(it) }
                    }
                }
                if (names.isNotEmpty()) return names.joinToString(", ")
            }
            if (artistsObj.has("all") && artistsObj.get("all").isJsonArray) {
                val allArray = artistsObj.getAsJsonArray("all")
                val names = mutableListOf<String>()
                for (elem in allArray) {
                    if (elem.isJsonObject) {
                        elem.asJsonObject.get("name")?.asString?.let { names.add(it) }
                    }
                }
                if (names.isNotEmpty()) return names.joinToString(", ")
            }
        }
        return item.get("primary_artists")?.asString
            ?: item.get("singers")?.asString
            ?: ""
    }

    private fun extractCoverUrl(item: JsonObject): String? {
        if (item.has("image")) {
            val imgElem = item.get("image")
            if (imgElem.isJsonArray) {
                val imgArr = imgElem.asJsonArray
                var highest: String? = null
                for (elem in imgArr) {
                    if (elem.isJsonObject) {
                        val obj = elem.asJsonObject
                        val q = obj.get("quality")?.asString.orEmpty()
                        val u = obj.get("url")?.asString ?: obj.get("link")?.asString
                        if (q == "500x500" && u != null) return u
                        if (u != null) highest = u
                    }
                }
                if (highest != null) return highest
            } else if (imgElem.isJsonPrimitive) {
                return getHighResCover(imgElem.asString)
            }
        }
        return null
    }

    private fun findBestMatch(candidates: List<JioSaavnSong>, title: String, artist: String): JioSaavnSong? {
        val cleanTitleLower = title.lowercase().trim()
        val cleanArtistLower = artist.lowercase().trim()

        return candidates.firstOrNull { song ->
            val songTitleLower = song.title.lowercase()
            val songArtistLower = song.artist.lowercase()
            val titleMatches = songTitleLower.contains(cleanTitleLower) || cleanTitleLower.contains(songTitleLower)
            val artistMatches = if (cleanArtistLower.isNotBlank()) {
                songArtistLower.contains(cleanArtistLower) || cleanArtistLower.contains(songArtistLower)
            } else true
            titleMatches && artistMatches
        }
    }

    private fun findArtistMatch(candidates: List<JioSaavnSong>, cleanTitle: String, cleanArtist: String): JioSaavnSong? {
        val cleanArtistLower = cleanArtist.lowercase().trim()
        val cleanTitleLower = cleanTitle.lowercase().trim()

        // 1. Both title and artist match
        val bothMatch = candidates.firstOrNull { song ->
            val songTitleLower = song.title.lowercase()
            val songArtistLower = song.artist.lowercase()
            (songTitleLower.contains(cleanTitleLower) || cleanTitleLower.contains(songTitleLower)) &&
                    (songArtistLower.contains(cleanArtistLower) || cleanArtistLower.contains(songArtistLower))
        }
        if (bothMatch != null) return bothMatch

        // 2. Artist match alone (since songs were already fetched using cleanTitle)
        if (cleanArtistLower.isNotBlank()) {
            val artistMatch = candidates.firstOrNull { song ->
                val songArtistLower = song.artist.lowercase()
                songArtistLower.contains(cleanArtistLower) || cleanArtistLower.contains(songArtistLower)
            }
            if (artistMatch != null) return artistMatch
        }

        return null
    }

    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("\\s*\\(feat\\..*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\[feat\\..*?\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(ft\\..*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\[ft\\..*?\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(with.*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\[with.*?\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(featuring.*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\[featuring.*?\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(remaster.*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\[remaster.*?\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*-\\s*Remaster.*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(extended.*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\[extended.*?\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*-\\s*Extended.*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(club mix.*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(original mix.*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(radio edit.*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*-\\s*Radio Edit.*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*-\\s*Single.*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*-\\s*EP.*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(deluxe.*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\[deluxe.*?\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(anniversary.*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\[anniversary.*?\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(bonus.*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\[bonus.*?\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(special edition.*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\[special edition.*?\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(official.*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\[official.*?\\]", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun cleanArtist(artist: String): String {
        return artist
            .replace(Regex("\\s*\\(feat\\..*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\[feat\\..*?\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(ft\\..*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\[ft\\..*?\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(with.*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\[with.*?\\]", RegexOption.IGNORE_CASE), "")
            .split(Regex("(?i)\\s+(feat\\.?|ft\\.?|with|featuring)\\s+|[,&/;+]"))[0]
            .trim()
    }

    private fun getHighResCover(image: String?): String? {
        if (image.isNullOrBlank()) return null
        return image
            .replace("150x150", "500x500")
            .replace("50x50", "500x500")
    }

    private fun cleanHtml(text: String): String {
        return text
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()
    }

    private fun normalizeUrl(url: String): String {
        return url.trim().removeSuffix("/")
    }

    // --- Domain and DTOs ---

    data class JioSaavnSong(
        val id: String,
        val title: String,
        val artist: String,
        val album: String,
        val durationSeconds: Long,
        val coverUrl: String?,
        val downloadUrls: List<DownloadUrlItem> = emptyList(),
        val decryptedBaseUrl: String? = null
    )

    data class DownloadUrlItem(
        val quality: String,
        val url: String
    )

    internal data class JioSaavnSearchResponseDto(
        val total: Int? = null,
        val start: Int? = null,
        val results: List<JioSaavnSongDto>? = null
    )

    internal data class JioSaavnSongDto(
        val id: String? = null,
        val title: String? = null,
        val song: String? = null,
        val album: String? = null,
        val year: String? = null,
        @SerializedName("primary_artists") val primaryArtists: String? = null,
        val singers: String? = null,
        val image: String? = null,
        val duration: String? = null,
        @SerializedName("encrypted_media_url") val encryptedMediaUrl: String? = null,
        @SerializedName("more_info") val moreInfo: JioSaavnMoreInfoDto? = null
    )

    internal data class JioSaavnMoreInfoDto(
        val duration: String? = null,
        @SerializedName("encrypted_media_url") val encryptedMediaUrl: String? = null,
        @SerializedName("artistMap") val artistMap: JioSaavnArtistMapDto? = null
    )

    internal data class JioSaavnArtistMapDto(
        @SerializedName("primary_artists") val primaryArtists: List<JioSaavnArtistRefDto>? = null
    )

    internal data class JioSaavnArtistRefDto(
        val id: String? = null,
        val name: String? = null
    )
}
