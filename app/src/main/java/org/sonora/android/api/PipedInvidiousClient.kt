package org.sonora.android.api

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

/**
 * Resilient YouTube Music stream resolver using rotating Piped and Invidious public instances.
 * Extracts audio-only M4A (AAC) and WebM (Opus) streams with transparent failover on 429/timeout.
 */
open class PipedInvidiousClient(
    customPipedInstances: List<String>? = null,
    customInvidiousInstances: List<String>? = null,
    private val healthCallback: ((endpointUrl: String, success: Boolean, latencyMs: Long, error: String?) -> Unit)? = null
) {
    companion object {
        val DEFAULT_PIPED_INSTANCES = listOf(
            "https://piped.projectsegfau.lt",
            "https://pipedapi.ducks.party",
            "https://pipedapi.kavin.rocks",
            "https://api.piped.privacydev.net",
            "https://piped-api.lunar.icu",
            "https://pipedapi.leptons.xyz",
            "https://pipedapi.drgns.space"
        )

        val DEFAULT_INVIDIOUS_INSTANCES = listOf(
            "https://invidious.f5.si",
            "https://inv.nadeko.net",
            "https://invidious.tiekoetter.com",
            "https://yt.chocolatemoo53.com",
            "https://invidious.nerdvpn.de",
            "https://iv.melmac.space"
        )
    }

    private val client = HttpClientProvider.okHttpClient
    private val gson: Gson = HttpClientProvider.gson

    @Volatile
    private var pipedInstances: List<String> = customPipedInstances ?: DEFAULT_PIPED_INSTANCES

    @Volatile
    private var invidiousInstances: List<String> = customInvidiousInstances ?: DEFAULT_INVIDIOUS_INSTANCES

    private val pipedIndex = AtomicInteger(0)
    private val invidiousIndex = AtomicInteger(0)

    /**
     * Resolves the best audio streaming URL for a track title and artist.
     * Tries Invidious and Piped instances with automatic failover.
     */
    open suspend fun searchAndResolve(
        title: String,
        artist: String,
        durationMs: Long? = null
    ): Result<StreamSource> = withContext(Dispatchers.IO) {
        val query = "$title $artist".trim()

        // 1. First attempt via Invidious instances (very stable direct googlevideo audio streams)
        val invidiousResult = resolveViaInvidious(query)
        if (invidiousResult.isSuccess) {
            return@withContext invidiousResult
        }

        // 2. Second attempt via Piped instances
        val pipedResult = resolveViaPiped(query)
        if (pipedResult.isSuccess) {
            return@withContext pipedResult
        }

        Result.failure(
            IOException(
                "Failed to resolve audio stream for '$query' across all Piped and Invidious instances. " +
                        "Errors: Invidious=[${invidiousResult.exceptionOrNull()?.message}], " +
                        "Piped=[${pipedResult.exceptionOrNull()?.message}]"
            )
        )
    }

    /**
     * Resolves stream directly by YouTube video ID.
     */
    suspend fun resolveStream(videoId: String): Result<StreamSource> = withContext(Dispatchers.IO) {
        // Try Invidious first
        val invidiousCandidate = resolveInvidiousVideo(videoId)
        if (invidiousCandidate.isSuccess) {
            return@withContext invidiousCandidate
        }

        // Try Piped
        val pipedCandidate = resolvePipedVideo(videoId)
        if (pipedCandidate.isSuccess) {
            return@withContext pipedCandidate
        }

        Result.failure(IOException("Failed to resolve stream for video ID: $videoId"))
    }

    /**
     * Updates instance lists dynamically.
     */
    open fun updateInstances(piped: List<String>?, invidious: List<String>?) {
        if (!piped.isNullOrEmpty()) {
            this.pipedInstances = piped
        }
        if (!invidious.isNullOrEmpty()) {
            this.invidiousInstances = invidious
        }
    }

    // --- Invidious Implementation ---

    private suspend fun resolveViaInvidious(query: String): Result<StreamSource> {
        val instances = getOrderedInvidiousInstances()
        val errors = mutableListOf<String>()

        for (instance in instances) {
            val start = System.currentTimeMillis()
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val searchUrl = "$instance/api/v1/search?q=$encodedQuery&type=video"
                val request = Request.Builder().url(searchUrl).get().build()

                client.newCall(request).execute().use { response ->
                    val latency = System.currentTimeMillis() - start
                    if (!response.isSuccessful) {
                        healthCallback?.invoke(instance, false, latency, "HTTP ${response.code}")
                        errors.add("$instance HTTP ${response.code}")
                        return@use
                    }

                    val body = response.body?.string().orEmpty()
                    val results: List<InvidiousSearchResultDto> = gson.fromJson(body, Array<InvidiousSearchResultDto>::class.java)?.toList().orEmpty()

                    val targetVideo = results.firstOrNull()
                    if (targetVideo?.videoId == null) {
                        errors.add("$instance returned 0 search items")
                        return@use
                    }

                    healthCallback?.invoke(instance, true, latency, null)

                    // Now fetch video streams
                    val videoResult = fetchInvidiousStreams(instance, targetVideo.videoId)
                    if (videoResult.isSuccess) {
                        return videoResult
                    } else {
                        errors.add("$instance stream fetch failed: ${videoResult.exceptionOrNull()?.message}")
                    }
                }
            } catch (e: Exception) {
                val latency = System.currentTimeMillis() - start
                healthCallback?.invoke(instance, false, latency, e.message)
                errors.add("$instance error: ${e.message}")
            }
        }

        return Result.failure(IOException("All Invidious attempts failed: ${errors.joinToString("; ")}"))
    }

    private suspend fun resolveInvidiousVideo(videoId: String): Result<StreamSource> {
        val instances = getOrderedInvidiousInstances()
        for (instance in instances) {
            val result = fetchInvidiousStreams(instance, videoId)
            if (result.isSuccess) return result
        }
        return Result.failure(IOException("Could not resolve Invidious video: $videoId"))
    }

    private fun fetchInvidiousStreams(instance: String, videoId: String): Result<StreamSource> {
        val start = System.currentTimeMillis()
        return try {
            val videoUrl = "$instance/api/v1/videos/$videoId"
            val request = Request.Builder().url(videoUrl).get().build()

            client.newCall(request).execute().use { response ->
                val latency = System.currentTimeMillis() - start
                if (!response.isSuccessful) {
                    healthCallback?.invoke(instance, false, latency, "HTTP ${response.code}")
                    return Result.failure(IOException("HTTP ${response.code} from $instance/api/v1/videos"))
                }

                val body = response.body?.string().orEmpty()
                val dto = gson.fromJson(body, InvidiousVideoDto::class.java)

                val audioFormats = dto?.adaptiveFormats.orEmpty().filter {
                    val mime = it.type.orEmpty().lowercase()
                    mime.contains("audio/mp4") || mime.contains("audio/webm") || mime.contains("audio/m4a")
                }

                if (audioFormats.isEmpty()) {
                    return Result.failure(IOException("No audio formats available for $videoId on $instance"))
                }

                // Pick the highest bitrate stream, preferring M4A/AAC when available
                val sorted = audioFormats.sortedWith(
                    compareByDescending<InvidiousFormatDto> { it.isM4a }
                        .thenByDescending { it.bitrate?.toIntOrNull() ?: 0 }
                )

                val best = sorted.first()
                val streamUrl = best.url
                if (streamUrl.isNullOrBlank()) {
                    return Result.failure(IOException("Audio format missing URL"))
                }

                healthCallback?.invoke(instance, true, latency, null)
                val bitrateInt = (best.bitrate?.toIntOrNull() ?: 128000) / 1000
                val quality = when {
                    bitrateInt >= 256 -> AudioQuality.HIGH
                    bitrateInt >= 128 -> AudioQuality.MEDIUM
                    else -> AudioQuality.LOW
                }

                Result.success(
                    StreamSource(
                        url = streamUrl,
                        format = best.type ?: "audio/mp4",
                        quality = quality,
                        provider = "YouTube (Invidious: ${extractHost(instance)})",
                        bitrate = bitrateInt
                    )
                )
            }
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - start
            healthCallback?.invoke(instance, false, latency, e.message)
            Result.failure(e)
        }
    }

    // --- Piped Implementation ---

    private suspend fun resolveViaPiped(query: String): Result<StreamSource> {
        val instances = getOrderedPipedInstances()
        val errors = mutableListOf<String>()

        for (instance in instances) {
            val start = System.currentTimeMillis()
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val searchUrl = "$instance/search?q=$encodedQuery&filter=music_songs"
                val request = Request.Builder().url(searchUrl).get().build()

                client.newCall(request).execute().use { response ->
                    val latency = System.currentTimeMillis() - start
                    if (!response.isSuccessful) {
                        healthCallback?.invoke(instance, false, latency, "HTTP ${response.code}")
                        errors.add("$instance HTTP ${response.code}")
                        return@use
                    }

                    val body = response.body?.string().orEmpty()
                    val searchDto = gson.fromJson(body, PipedSearchDto::class.java)
                    val firstItem = searchDto?.items.orEmpty().firstOrNull()

                    val rawUrl = firstItem?.url.orEmpty()
                    val videoId = when {
                        rawUrl.contains("/watch?v=") -> rawUrl.substringAfter("/watch?v=").substringBefore("&")
                        firstItem?.id != null -> firstItem.id
                        else -> null
                    }

                    if (videoId.isNullOrBlank()) {
                        errors.add("$instance: no videoId in items")
                        return@use
                    }

                    healthCallback?.invoke(instance, true, latency, null)

                    val streamResult = fetchPipedStreams(instance, videoId)
                    if (streamResult.isSuccess) {
                        return streamResult
                    } else {
                        errors.add("$instance stream fetch failed: ${streamResult.exceptionOrNull()?.message}")
                    }
                }
            } catch (e: Exception) {
                val latency = System.currentTimeMillis() - start
                healthCallback?.invoke(instance, false, latency, e.message)
                errors.add("$instance error: ${e.message}")
            }
        }

        return Result.failure(IOException("All Piped attempts failed: ${errors.joinToString("; ")}"))
    }

    private suspend fun resolvePipedVideo(videoId: String): Result<StreamSource> {
        val instances = getOrderedPipedInstances()
        for (instance in instances) {
            val result = fetchPipedStreams(instance, videoId)
            if (result.isSuccess) return result
        }
        return Result.failure(IOException("Could not resolve Piped video: $videoId"))
    }

    private fun fetchPipedStreams(instance: String, videoId: String): Result<StreamSource> {
        val start = System.currentTimeMillis()
        return try {
            val streamEndpoint = "$instance/streams/$videoId"
            val request = Request.Builder().url(streamEndpoint).get().build()

            client.newCall(request).execute().use { response ->
                val latency = System.currentTimeMillis() - start
                if (!response.isSuccessful) {
                    healthCallback?.invoke(instance, false, latency, "HTTP ${response.code}")
                    return Result.failure(IOException("HTTP ${response.code} from $instance/streams"))
                }

                val body = response.body?.string().orEmpty()
                val streamDto = gson.fromJson(body, PipedStreamDto::class.java)

                val audioStreams = streamDto?.audioStreams.orEmpty().filter {
                    val mime = it.mimeType.orEmpty().lowercase()
                    val fmt = it.format.orEmpty().lowercase()
                    mime.contains("audio") || fmt.contains("m4a") || fmt.contains("opus")
                }

                if (audioStreams.isEmpty()) {
                    return Result.failure(IOException("No audio streams returned from Piped $instance"))
                }

                // Prefer M4A then highest bitrate
                val sorted = audioStreams.sortedWith(
                    compareByDescending<PipedAudioStreamDto> { it.isM4a }
                        .thenByDescending { it.bitrate ?: 0 }
                )

                val best = sorted.first()
                val url = best.url
                if (url.isNullOrBlank()) {
                    return Result.failure(IOException("Piped audio stream missing URL"))
                }

                healthCallback?.invoke(instance, true, latency, null)
                val bitrateKbps = (best.bitrate ?: 128000) / 1000
                val quality = when {
                    bitrateKbps >= 256 -> AudioQuality.HIGH
                    bitrateKbps >= 128 -> AudioQuality.MEDIUM
                    else -> AudioQuality.LOW
                }

                Result.success(
                    StreamSource(
                        url = url,
                        format = best.mimeType ?: "audio/mp4",
                        quality = quality,
                        provider = "YouTube (Piped: ${extractHost(instance)})",
                        bitrate = bitrateKbps
                    )
                )
            }
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - start
            healthCallback?.invoke(instance, false, latency, e.message)
            Result.failure(e)
        }
    }

    // --- Helpers and Rotation ---

    private fun getOrderedPipedInstances(): List<String> {
        val list = pipedInstances
        if (list.isEmpty()) return DEFAULT_PIPED_INSTANCES
        val offset = Math.floorMod(pipedIndex.getAndIncrement(), list.size)
        return list.drop(offset) + list.take(offset)
    }

    private fun getOrderedInvidiousInstances(): List<String> {
        val list = invidiousInstances
        if (list.isEmpty()) return DEFAULT_INVIDIOUS_INSTANCES
        val offset = Math.floorMod(invidiousIndex.getAndIncrement(), list.size)
        return list.drop(offset) + list.take(offset)
    }

    private fun extractHost(url: String): String {
        return try {
            java.net.URI(url).host ?: url
        } catch (_: Exception) {
            url
        }
    }

    // --- DTOs ---

    data class VideoCandidate(
        val videoId: String,
        val title: String,
        val author: String?,
        val durationSeconds: Long?
    )

    internal data class InvidiousSearchResultDto(
        val videoId: String? = null,
        val title: String? = null,
        val author: String? = null,
        val lengthSeconds: Long? = null
    )

    internal data class InvidiousVideoDto(
        val title: String? = null,
        val adaptiveFormats: List<InvidiousFormatDto>? = null
    )

    internal data class InvidiousFormatDto(
        val type: String? = null,
        val bitrate: String? = null,
        val url: String? = null,
        val audioQuality: String? = null
    ) {
        val isM4a: Boolean get() = type?.contains("mp4", ignoreCase = true) == true || type?.contains("m4a", ignoreCase = true) == true
    }

    internal data class PipedSearchDto(
        val items: List<PipedSearchItemDto>? = null
    )

    internal data class PipedSearchItemDto(
        val url: String? = null,
        val id: String? = null,
        val title: String? = null,
        val uploaderName: String? = null,
        val duration: Long? = null
    )

    internal data class PipedStreamDto(
        val title: String? = null,
        val audioStreams: List<PipedAudioStreamDto>? = null
    )

    internal data class PipedAudioStreamDto(
        val url: String? = null,
        val format: String? = null,
        val quality: String? = null,
        val mimeType: String? = null,
        val bitrate: Int? = null,
        val codec: String? = null
    ) {
        val isM4a: Boolean get() = mimeType?.contains("mp4", ignoreCase = true) == true || format?.contains("m4a", ignoreCase = true) == true
    }
}
