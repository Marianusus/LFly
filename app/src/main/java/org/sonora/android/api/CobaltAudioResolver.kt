package org.sonora.android.api

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Resilient, high-fidelity audio stream extractor powered by public Cobalt instances.
 * Extracts direct M4A / Opus audio streams with active blackout detection (3.5s timeout)
 * and automatic failover across live healthy instances.
 */
open class CobaltAudioResolver(
    instances: List<String> = DEFAULT_INSTANCES,
    private val healthCallback: ((endpointUrl: String, success: Boolean, latencyMs: Long, error: String?) -> Unit)? = null
) {

    companion object {
        private const val TAG = "CobaltResolver"
        const val BLACKOUT_TIMEOUT_MS = 3500L
        const val DEGRADATION_COOLDOWN_MS = 5 * 60 * 1000L // 5 minutes

        val DEFAULT_INSTANCES = listOf(
            "https://sunny.imput.net",
            "https://kityune.imput.net",
            "https://blossom.imput.net",
            "https://nachos.imput.net",
            "https://cobalt-backend.canine.tools",
            "https://cobalt-api.kwiatekmiki.com"
        )

        private val SEARCH_FALLBACK_INSTANCES = listOf(
            "https://invidious.f5.si",
            "https://api.piped.private.coffee",
            "https://inv.nadeko.net",
            "https://invidious.tiekoetter.com"
        )
    }

    private val instanceList = CopyOnWriteArrayList<String>(
        if (instances.isNotEmpty()) instances else DEFAULT_INSTANCES
    )

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
            Log.d(TAG, "Updated Cobalt active instances: $newInstances")
        }
    }

    /**
     * Marks an instance as degraded after a timeout or failure.
     */
    fun markInstanceDegraded(instanceUrl: String, reason: String? = null) {
        val normalized = normalizeUrl(instanceUrl)
        degradedInstances[normalized] = System.currentTimeMillis() + DEGRADATION_COOLDOWN_MS
        Log.w(TAG, "Blackout detected: Cobalt instance marked degraded for 5m: $normalized (reason=$reason)")
    }

    /**
     * Resolves the best full-length audio stream for a track title and artist.
     */
    open suspend fun resolveStream(
        title: String,
        artist: String,
        preferredQuality: AudioQuality = AudioQuality.HIGH,
        durationMs: Long? = null
    ): Result<StreamSource> = withContext(Dispatchers.IO) {
        val query = "$title $artist".trim()

        // 1. Resolve candidate YouTube video ID
        val videoId = searchVideoId(query).getOrNull()
        if (videoId.isNullOrBlank()) {
            return@withContext Result.failure(IOException("No YouTube video candidate found for '$query'"))
        }

        // 2. Extract stream via Cobalt
        resolveByVideoId(videoId, preferredQuality)
    }

    /**
     * Extracts direct full audio stream by YouTube Video ID.
     */
    open suspend fun resolveByVideoId(
        videoId: String,
        preferredQuality: AudioQuality = AudioQuality.HIGH
    ): Result<StreamSource> = withContext(Dispatchers.IO) {
        val targetUrl = "https://www.youtube.com/watch?v=$videoId"
        val prioritized = getPrioritizedInstances()
        val errors = mutableListOf<String>()

        for (instance in prioritized) {
            val start = System.currentTimeMillis()
            val result = extractFromInstance(instance, targetUrl, preferredQuality)
            val latency = System.currentTimeMillis() - start

            if (result.isSuccess) {
                degradedInstances.remove(normalizeUrl(instance))
                healthCallback?.invoke(instance, true, latency, null)
                Log.i(TAG, "✓ Cobalt stream resolved from $instance in ${latency}ms for video $videoId")
                return@withContext result
            } else {
                val err = result.exceptionOrNull()?.message
                markInstanceDegraded(instance, err)
                healthCallback?.invoke(instance, false, latency, err)
                errors.add("$instance: $err")
                Log.w(TAG, "Cobalt instance $instance failed: $err. Rotating to next instance...")
            }
        }

        Result.failure(IOException("All Cobalt instances failed for $videoId: ${errors.joinToString("; ")}"))
    }

    /**
     * Performs POST extraction on a specific Cobalt instance with 3.5s blackout timeout.
     */
    private suspend fun extractFromInstance(
        instanceUrl: String,
        targetMediaUrl: String,
        quality: AudioQuality
    ): Result<StreamSource> = withContext(Dispatchers.IO) {
        try {
            withTimeoutOrNull(BLACKOUT_TIMEOUT_MS) {
                val cleanBase = normalizeUrl(instanceUrl)
                val targetBitrate = when (quality) {
                    AudioQuality.ULTRA, AudioQuality.HIGH -> "320"
                    AudioQuality.MEDIUM -> "160"
                    AudioQuality.LOW -> "96"
                }

                // Cobalt v10/v11 payload
                val payloadObj = JsonObject().apply {
                    addProperty("url", targetMediaUrl)
                    addProperty("downloadMode", "audio")
                    addProperty("audioFormat", "m4a")
                    addProperty("audioBitrate", targetBitrate)
                }

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val requestBody = payloadObj.toString().toRequestBody(mediaType)

                val request = Request.Builder()
                    .url("$cleanBase/")
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .post(requestBody)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withTimeoutOrNull Result.failure(
                            IOException("HTTP ${response.code}: ${response.message} from $cleanBase")
                        )
                    }

                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) {
                        return@withTimeoutOrNull Result.failure(IOException("Empty response body from $cleanBase"))
                    }

                    val root = JsonParser.parseString(body).asJsonObject
                    val status = root.get("status")?.asString.orEmpty().lowercase()

                    if (status == "error") {
                        val errMsg = root.getAsJsonObject("error")?.get("code")?.asString ?: "Unknown Cobalt error"
                        return@withTimeoutOrNull Result.failure(IOException(errMsg))
                    }

                    val streamUrl = root.get("url")?.asString.orEmpty()
                    if (streamUrl.isBlank()) {
                        return@withTimeoutOrNull Result.failure(IOException("No download URL in Cobalt response"))
                    }

                    val bitrateInt = targetBitrate.toIntOrNull() ?: 320
                    Result.success(
                        StreamSource(
                            url = streamUrl,
                            format = "audio/mp4",
                            quality = quality,
                            provider = "Cobalt (${extractHost(cleanBase)})",
                            bitrate = bitrateInt,
                            isPreview = false
                        )
                    )
                }
            } ?: Result.failure(IOException("Blackout timeout: $instanceUrl exceeded ${BLACKOUT_TIMEOUT_MS}ms"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Resolves YouTube video ID using Invidious public search instances with strict timeout.
     */
    private suspend fun searchVideoId(query: String): Result<String> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query, "UTF-8")
        for (searchHost in SEARCH_FALLBACK_INSTANCES) {
            try {
                val candidate = withTimeoutOrNull(2500L) {
                    if (searchHost.contains("piped")) {
                        val url = "$searchHost/search?q=$encoded&filter=all"
                        val req = Request.Builder().url(url).get().build()
                        client.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) return@use null
                            val body = resp.body?.string().orEmpty()
                            val root = JsonParser.parseString(body).asJsonObject
                            val items = root.getAsJsonArray("items")
                            val firstUrl = items?.firstOrNull()?.asJsonObject?.get("url")?.asString
                            firstUrl?.substringAfter("v=")?.substringBefore("&")
                        }
                    } else {
                        val url = "$searchHost/api/v1/search?q=$encoded&type=video"
                        val req = Request.Builder().url(url).get().build()
                        client.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) return@use null
                            val body = resp.body?.string().orEmpty()
                            val root = JsonParser.parseString(body).asJsonArray
                            val firstItem = root.firstOrNull()?.asJsonObject
                            firstItem?.get("videoId")?.asString
                        }
                    }
                }
                if (!candidate.isNullOrBlank()) {
                    return@withContext Result.success(candidate)
                }
            } catch (_: Exception) {
                // Try next search instance
            }
        }
        Result.failure(IOException("Unable to find video candidate for query: $query"))
    }

    /**
     * Returns prioritized instances, with healthy endpoints ordered first.
     */
    private fun getPrioritizedInstances(): List<String> {
        val now = System.currentTimeMillis()
        val current = instanceList.toList().ifEmpty { DEFAULT_INSTANCES }
        return current.sortedBy { inst ->
            val degradedUntil = degradedInstances[normalizeUrl(inst)] ?: 0L
            if (degradedUntil > now) 1 else 0
        }
    }

    private fun normalizeUrl(url: String): String = url.trim().removeSuffix("/")

    private fun extractHost(url: String): String {
        return try {
            java.net.URI(url).host ?: url
        } catch (_: Exception) {
            url
        }
    }
}
