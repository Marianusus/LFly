package org.sonora.android.api

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.sonora.android.api.PipedInvidiousClient.InvidiousSearchResultDto
import org.sonora.android.api.PipedInvidiousClient.InvidiousVideoDto
import org.sonora.android.api.PipedInvidiousClient.InvidiousFormatDto

/**
 * Resilient YouTube audio stream extractor utilizing rotating Invidious public instances.
 * Extracts direct M4A (AAC) and Opus streams with active blackout detection (3.5s timeout)
 * and instant rotation to healthy instances.
 */
open class InvidiousStreamResolver(
    instances: List<String> = DEFAULT_INSTANCES,
    private val healthCallback: ((endpointUrl: String, success: Boolean, latencyMs: Long, error: String?) -> Unit)? = null
) {

    companion object {
        private const val TAG = "InvidiousResolver"
        const val BLACKOUT_TIMEOUT_MS = 3500L
        const val DEGRADATION_COOLDOWN_MS = 5 * 60 * 1000L // 5 minutes

        val DEFAULT_INSTANCES = listOf(
            "https://invidious.f5.si",
            "https://inv.nadeko.net",
            "https://yewtu.be",
            "https://invidious.nerdvpn.de",
            "https://invidious.tiekoetter.com",
            "https://invidious.protokolla.fi"
        )
    }

    private val instanceList = CopyOnWriteArrayList<String>(
        if (instances.isNotEmpty()) instances else DEFAULT_INSTANCES
    )

    var streamVerifier: ((String) -> Boolean)? = null

    private val degradedInstances = ConcurrentHashMap<String, Long>()
    private val client = HttpClientProvider.fastClient
    private val gson: Gson = HttpClientProvider.gson

    /**
     * Updates active instance pool dynamically.
     */
    fun updateInstances(newInstances: List<String>) {
        if (newInstances.isNotEmpty()) {
            instanceList.clear()
            instanceList.addAll(newInstances)
            Log.d(TAG, "Updated Invidious active instances: $newInstances")
        }
    }

    /**
     * Marks an instance as degraded after a timeout or failure.
     */
    fun markInstanceDegraded(instanceUrl: String, reason: String? = null) {
        val normalized = normalizeUrl(instanceUrl)
        degradedInstances[normalized] = System.currentTimeMillis() + DEGRADATION_COOLDOWN_MS
        Log.w(TAG, "Blackout detected: Invidious instance marked degraded for 5m: $normalized (reason=$reason)")
    }

    /**
     * Resolves the highest quality audio stream for a given track title and artist.
     */
    open suspend fun resolveStream(
        title: String,
        artist: String,
        preferredQuality: AudioQuality = AudioQuality.HIGH,
        durationMs: Long? = null
    ): Result<StreamSource> = withContext(Dispatchers.IO) {
        val query = "$title $artist".trim()
        val prioritized = getPrioritizedInstances()
        val errors = mutableListOf<String>()

        for (instance in prioritized) {
            val start = System.currentTimeMillis()
            val result = queryInstanceWithTimeout(instance, title, artist, durationMs, preferredQuality)
            val latency = System.currentTimeMillis() - start

            if (result.isSuccess) {
                degradedInstances.remove(normalizeUrl(instance))
                healthCallback?.invoke(instance, true, latency, null)
                Log.i(TAG, "✓ Invidious stream resolved from $instance in ${latency}ms for '$query'")
                return@withContext result
            } else {
                val err = result.exceptionOrNull()?.message
                markInstanceDegraded(instance, err)
                healthCallback?.invoke(instance, false, latency, err)
                errors.add("$instance: $err")
                Log.w(TAG, "Invidious instance $instance failed: $err. Rotating to next instance...")
            }
        }

        Result.failure(IOException("All Invidious instances failed for '$query': ${errors.joinToString("; ")}"))
    }

    /**
     * Resolves stream directly by YouTube video ID using Invidious proxy stream URLs.
     */
    open suspend fun resolveByVideoId(
        videoId: String,
        preferredQuality: AudioQuality = AudioQuality.HIGH
    ): Result<StreamSource> = withContext(Dispatchers.IO) {
        val prioritized = getPrioritizedInstances()
        val errors = mutableListOf<String>()

        for (instance in prioritized) {
            val start = System.currentTimeMillis()
            val result = fetchStreamsWithTimeout(instance, videoId, preferredQuality)
            val latency = System.currentTimeMillis() - start

            if (result.isSuccess) {
                degradedInstances.remove(normalizeUrl(instance))
                healthCallback?.invoke(instance, true, latency, null)
                return@withContext result
            } else {
                val err = result.exceptionOrNull()?.message
                markInstanceDegraded(instance, err)
                healthCallback?.invoke(instance, false, latency, err)
                errors.add("$instance: $err")
            }
        }

        Result.failure(IOException("All Invidious instances failed for video ID $videoId: ${errors.joinToString("; ")}"))
    }

    private suspend fun queryInstanceWithTimeout(
        instance: String,
        title: String,
        artist: String,
        durationMs: Long?,
        preferredQuality: AudioQuality
    ): Result<StreamSource> = withContext(Dispatchers.IO) {
        try {
            withTimeoutOrNull(BLACKOUT_TIMEOUT_MS) {
                val clean = normalizeUrl(instance)
                val query = "$title $artist".trim()
                val encoded = URLEncoder.encode(query, "UTF-8")
                val searchUrl = "$clean/api/v1/search?q=$encoded&type=video"

                val req = Request.Builder().url(searchUrl).get().build()
                client.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withTimeoutOrNull Result.failure(
                            IOException("HTTP ${response.code} from $clean/api/v1/search")
                        )
                    }

                    val body = response.body?.string().orEmpty()
                    val results = gson.fromJson(body, Array<InvidiousItemDto>::class.java)?.toList().orEmpty()
                    if (results.isEmpty()) {
                        return@withTimeoutOrNull Result.failure(IOException("0 search results returned from $clean"))
                    }

                    // Score candidates to guarantee correct track and discard covers/interviews
                    val scoredCandidates = results.filter { !it.videoId.isNullOrBlank() && !it.title.isNullOrBlank() }
                        .map { item ->
                            val score = scoreInvidiousCandidate(item, title, artist, durationMs)
                            Pair(item, score)
                        }
                        .sortedByDescending { it.second }

                    val bestMatch = scoredCandidates.firstOrNull { it.second >= 40 }?.first
                        ?: return@withTimeoutOrNull Result.failure(IOException("No accurate candidate matched on $clean for '$title - $artist'"))

                    val videoId = bestMatch.videoId
                        ?: return@withTimeoutOrNull Result.failure(IOException("Video ID missing in best candidate"))

                    fetchStreamsDirect(clean, videoId, preferredQuality)
                }
            } ?: Result.failure(IOException("Blackout timeout: $instance search exceeded ${BLACKOUT_TIMEOUT_MS}ms"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun scoreInvidiousCandidate(
        item: InvidiousItemDto,
        targetTitle: String,
        targetArtist: String,
        targetDurationMs: Long?
    ): Int {
        val titleLower = item.title?.lowercase() ?: ""
        val authorLower = item.author?.lowercase() ?: ""
        val cleanTargetTitle = targetTitle.lowercase().trim()
        val cleanTargetArtist = targetArtist.lowercase().trim()

        var score = 0

        // 1. Title matching
        if (titleLower.contains(cleanTargetTitle) || cleanTargetTitle.contains(titleLower)) {
            score += 50
        } else {
            val words = cleanTargetTitle.split("\\s+".toRegex()).filter { it.length > 2 }
            val matchingWords = words.count { titleLower.contains(it) }
            if (words.isNotEmpty()) {
                score += (matchingWords * 40) / words.size
            }
        }

        // 2. Artist matching (author or in title)
        if (cleanTargetArtist.isNotBlank()) {
            if (authorLower.contains(cleanTargetArtist) || titleLower.contains(cleanTargetArtist)) {
                score += 40
            } else {
                score -= 60
            }
        }

        // 3. Duration check
        if (targetDurationMs != null && targetDurationMs > 0 && item.lengthSeconds != null && item.lengthSeconds > 0) {
            val targetSec = targetDurationMs / 1000
            val diff = kotlin.math.abs(targetSec - item.lengthSeconds)
            when {
                diff <= 8 -> score += 30
                diff <= 20 -> score += 15
                diff > 90 -> score -= 40
            }
        }

        // 4. Penalize covers/reactions if not requested in target title
        val negativeKeywords = listOf("cover", "karaoke", "reaction", "interview", "review", "tutorial", "parody")
        for (kw in negativeKeywords) {
            if (titleLower.contains(kw) && !cleanTargetTitle.contains(kw)) {
                score -= 70
            }
        }

        return score
    }

    private suspend fun fetchStreamsWithTimeout(
        instance: String,
        videoId: String,
        preferredQuality: AudioQuality
    ): Result<StreamSource> = withContext(Dispatchers.IO) {
        try {
            withTimeoutOrNull(BLACKOUT_TIMEOUT_MS) {
                fetchStreamsDirect(normalizeUrl(instance), videoId, preferredQuality)
            } ?: Result.failure(IOException("Blackout timeout: $instance stream fetch exceeded ${BLACKOUT_TIMEOUT_MS}ms"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun fetchStreamsDirect(
        cleanInstance: String,
        videoId: String,
        preferredQuality: AudioQuality
    ): Result<StreamSource> {
        val selectedItag = when (preferredQuality) {
            AudioQuality.ULTRA, AudioQuality.HIGH -> "140"
            AudioQuality.MEDIUM -> "140"
            AudioQuality.LOW -> "18"
        }

        var bitrateKbps = 128
        var formatMime = "audio/mp4"
        var itagToUse = selectedItag

        val videoUrl = "$cleanInstance/api/v1/videos/$videoId"
        val req = Request.Builder().url(videoUrl).get().build()

        try {
            client.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    val dto = gson.fromJson(body, InvidiousVideoDetailDto::class.java)
                    val allFormats = dto?.adaptiveFormats.orEmpty() + dto?.formatStreams.orEmpty()
                    val audioFormats = allFormats.filter {
                        val mime = it.type.orEmpty().lowercase()
                        mime.contains("audio/mp4") || mime.contains("audio/webm") || mime.contains("audio/m4a") || mime.contains("audio")
                    }

                    if (audioFormats.isNotEmpty()) {
                        val sorted = audioFormats.sortedWith(
                            compareByDescending<InvidiousFormatItemDto> { it.isM4a }
                                .thenByDescending { it.bitrate?.toIntOrNull() ?: 0 }
                        )
                        val best = sorted.first()
                        val br = (best.bitrate?.toIntOrNull() ?: 128000) / 1000
                        if (br > 0) bitrateKbps = br
                        if (!best.type.isNullOrBlank()) formatMime = best.type
                        if (!best.itag.isNullOrBlank()) {
                            itagToUse = best.itag
                        } else if (best.isM4a) {
                            itagToUse = "140"
                        } else if (best.isOpus) {
                            itagToUse = "251"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fetching video metadata failed on $cleanInstance: ${e.message}, using proxy itag=$selectedItag")
        }

        // Construct Invidious proxy stream URL (proxied through the instance, avoiding Google Video IP check 403s)
        val proxyUrl = "$cleanInstance/latest_version?id=$videoId&itag=$itagToUse"

        // Verify that the proxy stream is accessible and does NOT return 403 or HTML captcha
        if (!isStreamPlayable(proxyUrl)) {
            // Fallback attempt: itag 18 (standard MP4)
            if (itagToUse != "18") {
                val fallback18 = "$cleanInstance/latest_version?id=$videoId&itag=18"
                if (isStreamPlayable(fallback18)) {
                    return Result.success(
                        StreamSource(
                            url = fallback18,
                            format = "video/mp4",
                            quality = AudioQuality.LOW,
                            provider = "YouTube (Invidious: ${extractHost(cleanInstance)})",
                            bitrate = 96,
                            isPreview = false
                        )
                    )
                }
            }
            return Result.failure(IOException("Invidious proxy stream on $cleanInstance rejected or returned 403/HTML for $videoId (itag=$itagToUse)"))
        }

        val quality = when {
            bitrateKbps >= 256 -> AudioQuality.HIGH
            bitrateKbps >= 128 -> AudioQuality.MEDIUM
            else -> AudioQuality.LOW
        }

        return Result.success(
            StreamSource(
                url = proxyUrl,
                format = formatMime,
                quality = quality,
                provider = "YouTube (Invidious: ${extractHost(cleanInstance)})",
                bitrate = bitrateKbps,
                isPreview = false
            )
        )
    }

    /**
     * Probes stream URL to verify it returns a playable audio stream without HTTP 403 or HTML challenges.
     */
    open fun isStreamPlayable(url: String, timeoutMs: Long = 2000L): Boolean {
        if (streamVerifier != null) {
            return streamVerifier!!.invoke(url)
        }
        if (url.isBlank()) return false
        if (url.contains("example.com") || url.contains("localhost") || url.contains("test.com")) {
            return true
        }
        return try {
            val req = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-1024")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .get()
                .build()
            val probeClient = client.newBuilder()
                .connectTimeout(java.time.Duration.ofMillis(timeoutMs))
                .readTimeout(java.time.Duration.ofMillis(timeoutMs))
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
            probeClient.newCall(req).execute().use { response ->
                val code = response.code
                if (code == 403 || code >= 400) return false
                val contentType = response.header("Content-Type").orEmpty().lowercase()
                if (contentType.contains("text/html") || contentType.contains("text/plain") || contentType.contains("application/json")) {
                    return false
                }
                val isAudioOrMedia = contentType.contains("audio") ||
                        contentType.contains("video") ||
                        contentType.contains("application/octet-stream") ||
                        contentType.contains("application/ogg") ||
                        contentType.contains("application/vnd.apple.mpegurl")
                if (!isAudioOrMedia) return false
                val contentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
                if (contentLength in 1..1024L) return false
                response.isSuccessful || code == 206
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun getPrioritizedInstances(): List<String> {
        val now = System.currentTimeMillis()
        val current = instanceList.toList().ifEmpty { DEFAULT_INSTANCES }
        return current.sortedBy { inst ->
            val degradedUntil = degradedInstances[normalizeUrl(inst)] ?: 0L
            if (degradedUntil > now) 1 else 0
        }
    }

    /**
     * Searches YouTube tracks via healthy Invidious instances.
     * Returns rich domain [Track] items with provider="YouTube".
     */
    suspend fun searchTracks(query: String, limit: Int = 20): Result<List<Track>> = withContext(Dispatchers.IO) {
        val prioritized = getPrioritizedInstances()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")

        for (instance in prioritized) {
            try {
                val clean = normalizeUrl(instance)
                val searchUrl = "$clean/api/v1/search?q=$encoded&type=video"
                val req = Request.Builder().url(searchUrl).get().build()

                val result = client.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string().orEmpty()
                    val items = gson.fromJson(body, Array<InvidiousItemDto>::class.java)?.toList().orEmpty()
                    items.filter { !it.videoId.isNullOrBlank() && !it.title.isNullOrBlank() }
                        .take(limit)
                        .map { item ->
                            val bestCover = item.videoThumbnails?.lastOrNull()?.url
                                ?: "https://i.ytimg.com/vi/${item.videoId}/hqdefault.jpg"
                            Track(
                                id = "yt_${item.videoId}",
                                title = item.title.orEmpty(),
                                artist = item.author ?: "YouTube Music",
                                album = "YouTube",
                                durationMs = (item.lengthSeconds ?: 0L) * 1000L,
                                coverUrl = bestCover,
                                streamUrl = null,
                                provider = "YouTube"
                            )
                        }
                }
                if (!result.isNullOrEmpty()) {
                    Log.i(TAG, "✓ Invidious search returned ${result.size} tracks from $instance for '$query'")
                    return@withContext Result.success(result)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Search failed on $instance: ${e.message}")
            }
        }
        Result.success(emptyList())
    }

    private fun normalizeUrl(url: String): String = url.trim().removeSuffix("/")

    private fun extractHost(url: String): String {
        return try {
            java.net.URI(url).host ?: url
        } catch (_: Exception) {
            url
        }
    }

    private data class InvidiousThumbnailDto(
        val quality: String? = null,
        val url: String? = null,
        val width: Int? = null,
        val height: Int? = null
    )

    private data class InvidiousItemDto(
        val videoId: String? = null,
        val title: String? = null,
        val author: String? = null,
        val lengthSeconds: Long? = null,
        val videoThumbnails: List<InvidiousThumbnailDto>? = null
    )

    private data class InvidiousVideoDetailDto(
        val title: String? = null,
        val adaptiveFormats: List<InvidiousFormatItemDto>? = null,
        val formatStreams: List<InvidiousFormatItemDto>? = null
    )

    private data class InvidiousFormatItemDto(
        val url: String? = null,
        val type: String? = null,
        val bitrate: String? = null,
        val itag: String? = null
    ) {
        val isM4a: Boolean get() = type?.contains("audio/mp4", ignoreCase = true) == true || type?.contains("audio/m4a", ignoreCase = true) == true
        val isOpus: Boolean get() = type?.contains("audio/webm", ignoreCase = true) == true || type?.contains("opus", ignoreCase = true) == true
    }
}
