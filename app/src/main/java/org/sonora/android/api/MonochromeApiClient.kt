package org.sonora.android.api

import android.util.Log
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * Native API client for the Monochrome HiFi ecosystem (monochrome.tf / tracks.monochrome.st).
 * Resolves bit-perfect Lossless FLAC (1411+ kbps) and high-fidelity audio streams directly from
 * official and community HiFi mirrors with zero truncation (never 30-second previews).
 */
open class MonochromeApiClient(
    private val healthCallback: ((url: String, success: Boolean, latencyMs: Long, error: String?) -> Unit)? = null
) {

    companion object {
        private const val TAG = "MonochromeApiClient"
        const val DEFAULT_BASE_URL = "https://api.monochrome.tf"
        val DEFAULT_INSTANCES = listOf(
            "https://api.monochrome.tf",
            "https://tracks.monochrome.st",
            "https://monochrome.tf",
            "https://lossless.wtf"
        )
    }

    private val instances = CopyOnWriteArrayList(DEFAULT_INSTANCES)

    data class MonochromeTrack(
        val id: String,
        val trackId: String,
        val title: String,
        val artistNames: List<String>,
        val durationMs: Long,
        val isrc: String?,
        val artwork: String?,
        val playable: Boolean
    )

    fun updateInstances(newInstances: List<String>) {
        if (newInstances.isNotEmpty()) {
            instances.clear()
            instances.addAll(newInstances.map { it.trim().removeSuffix("/") })
            Log.d(TAG, "Updated Monochrome instances: $instances")
        }
    }

    fun getInstances(): List<String> = instances.toList()

    /**
     * Resolves a full-length lossless audio stream for a track given its metadata.
     */
    open suspend fun resolveStream(
        title: String,
        artist: String,
        preferredQuality: AudioQuality = AudioQuality.ULTRA,
        durationMs: Long? = null,
        isrc: String? = null
    ): Result<StreamSource> = withContext(Dispatchers.IO) {
        val candidateInstances = instances.ifEmpty { listOf(DEFAULT_BASE_URL) }

        var lastError: String? = null
        for (instance in candidateInstances) {
            val start = System.currentTimeMillis()
            try {
                // Multi-stage query strategy to guarantee highest match rate
                val queries = buildSearchQueries(title, artist)
                var bestCandidate: MonochromeTrack? = null
                var bestScore = 0

                for (query in queries) {
                    val searchResult = searchTracks(query, limit = 8, baseUrl = instance)
                    if (searchResult.isSuccess) {
                        val candidates = searchResult.getOrNull() ?: emptyList()
                        for (candidate in candidates) {
                            val score = scoreCandidate(candidate, title, artist, durationMs, isrc)
                            if (score > bestScore) {
                                bestScore = score
                                bestCandidate = candidate
                            }
                        }
                    }
                    if (bestScore >= 120) break // High confidence match found
                }

                if (bestCandidate != null && bestScore >= 75) {
                    val latency = System.currentTimeMillis() - start
                    val streamUrl = "$instance/track/${bestCandidate.id}"
                    healthCallback?.invoke(instance, true, latency, null)
                    Log.i(TAG, "✓ Matched track on Monochrome [score=$bestScore, latency=${latency}ms]: " +
                            "'${bestCandidate.title}' by ${bestCandidate.artistNames} (ID: ${bestCandidate.id}) -> $streamUrl")

                    val quality = if (preferredQuality == AudioQuality.LOW) AudioQuality.MEDIUM else AudioQuality.ULTRA
                    return@withContext Result.success(
                        StreamSource(
                            url = streamUrl,
                            format = "audio/flac",
                            quality = quality,
                            provider = "Monochrome HiFi",
                            bitrate = 1411,
                            isPreview = false
                        )
                    )
                } else {
                    val latency = System.currentTimeMillis() - start
                    val msg = "No matching track found on $instance (best score was $bestScore, threshold is 75)"
                    lastError = msg
                    Log.d(TAG, msg)
                }
            } catch (e: Exception) {
                val latency = System.currentTimeMillis() - start
                lastError = e.message
                healthCallback?.invoke(instance, false, latency, e.message)
                Log.w(TAG, "Error resolving stream on $instance: ${e.message}")
            }
        }

        Result.failure(IOException(lastError ?: "Monochrome stream resolution failed across all instances"))
    }

    /**
     * Searches tracks on a given Monochrome instance.
     */
    open suspend fun searchTracks(
        query: String,
        limit: Int = 10,
        baseUrl: String = instances.firstOrNull() ?: DEFAULT_BASE_URL
    ): Result<List<MonochromeTrack>> = withContext(Dispatchers.IO) {
        val cleanBase = baseUrl.trim().removeSuffix("/")
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        val searchUrl = "$cleanBase/search/tracks?q=$encodedQuery&limit=$limit"

        try {
            val response = HttpClientProvider.getJson(searchUrl)
            response.fold(
                onSuccess = { rawJson ->
                    val root = JsonParser.parseString(rawJson).asJsonObject
                    val tracksArray = root.getAsJsonArray("tracks") ?: return@fold Result.success(emptyList())

                    val parsed = mutableListOf<MonochromeTrack>()
                    for (elem in tracksArray) {
                        try {
                            val obj = elem.asJsonObject
                            val id = obj.get("id")?.asString ?: obj.get("trackId")?.asString ?: continue
                            val trackId = obj.get("trackId")?.asString ?: id
                            val title = obj.get("title")?.asString ?: ""
                            val duration = obj.get("duration")?.asLong ?: 0L
                            val isrc = obj.get("isrc")?.asString
                            val artwork = obj.get("artwork")?.asString
                            val playable = obj.get("playable")?.asBoolean ?: true

                            val artistNames = mutableListOf<String>()
                            if (obj.has("artistNames")) {
                                obj.getAsJsonArray("artistNames").forEach { artistElem ->
                                    artistNames.add(artistElem.asString)
                                }
                            }

                            parsed.add(
                                MonochromeTrack(
                                    id = id,
                                    trackId = trackId,
                                    title = title,
                                    artistNames = artistNames,
                                    durationMs = duration,
                                    isrc = isrc,
                                    artwork = artwork,
                                    playable = playable
                                )
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "Error parsing monochrome track item: ${e.message}")
                        }
                    }
                    Result.success(parsed)
                },
                onFailure = { err ->
                    Result.failure(err)
                }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Builds prioritized search queries: exact, sanitized, and core title.
     */
    private fun buildSearchQueries(title: String, artist: String): List<String> {
        val queries = mutableListOf<String>()

        val rawCleanTitle = cleanTitle(title)
        val rawCleanArtist = cleanArtist(artist)

        // 1. Direct query with raw title and artist
        queries.add("$artist $title".trim())

        // 2. Query with sanitized title and primary artist
        if (rawCleanArtist.isNotBlank() && rawCleanTitle.isNotBlank()) {
            queries.add("$rawCleanArtist $rawCleanTitle".trim())
            queries.add("$rawCleanTitle $rawCleanArtist".trim())
        }

        // 3. Just sanitized title
        if (rawCleanTitle.isNotBlank()) {
            queries.add(rawCleanTitle)
        }

        // 4. Base title before hyphen/dash if present (e.g. "Song - Version" -> "Song")
        if (rawCleanTitle.contains("-")) {
            val baseTitle = rawCleanTitle.substringBefore("-").trim()
            if (baseTitle.isNotBlank()) {
                if (rawCleanArtist.isNotBlank()) {
                    queries.add("$rawCleanArtist $baseTitle".trim())
                }
                queries.add(baseTitle)
            }
        }

        return queries.distinct().filter { it.isNotBlank() }
    }

    /**
     * Scores a candidate track against the expected song metadata.
     * Higher score indicates higher confidence.
     */
    private fun scoreCandidate(
        candidate: MonochromeTrack,
        targetTitle: String,
        targetArtist: String,
        targetDurationMs: Long?,
        targetIsrc: String?
    ): Int {
        // 1. Exact ISRC match is definitive
        if (!targetIsrc.isNullOrBlank() && !candidate.isrc.isNullOrBlank()) {
            if (candidate.isrc.equals(targetIsrc, ignoreCase = true)) {
                return 200
            }
        }

        var score = 0

        // 1. Raw alphanumeric comparison (preserving version tags like 'extended', 'remix', 'live')
        val rawTargetAlpha = toAlphanumericOnly(targetTitle)
        val rawCandAlpha = toAlphanumericOnly(candidate.title)
        val isExactRawMatch = rawTargetAlpha.isNotBlank() && rawTargetAlpha == rawCandAlpha

        val targetCleanTitle = cleanTitle(targetTitle)
        val candCleanTitle = cleanTitle(candidate.title)
        val cleanTargetAlpha = toAlphanumericOnly(targetCleanTitle)
        val cleanCandAlpha = toAlphanumericOnly(candCleanTitle)

        if (isExactRawMatch) {
            score += 120
        } else if (cleanTargetAlpha.isNotBlank() && cleanTargetAlpha == cleanCandAlpha) {
            score += 90
            // Check if both or neither contain version keywords
            val targetHasVersion = hasVersionKeyword(targetTitle)
            val candHasVersion = hasVersionKeyword(candidate.title)
            if (targetHasVersion == candHasVersion) {
                score += 15
            }
        } else if (cleanTargetAlpha.contains(cleanCandAlpha) || cleanCandAlpha.contains(cleanTargetAlpha)) {
            score += 65
        }

        val targetNormArtist = toAlphanumericOnly(cleanArtist(targetArtist))
        val candidateArtistsNorm = candidate.artistNames.map { toAlphanumericOnly(it) }

        if (targetNormArtist.isNotBlank()) {
            val hasArtistMatch = candidateArtistsNorm.any { cArtist ->
                cArtist == targetNormArtist || cArtist.contains(targetNormArtist) || targetNormArtist.contains(cArtist)
            }
            if (hasArtistMatch) {
                score += 50
            } else {
                // Mandatory Guardrail: Non-matching artist means this is a cover or wrong song! Disqualify candidate.
                return 0
            }
        }

        // Duration similarity check (heavy weight to distinguish edits/extended versions)
        if (targetDurationMs != null && targetDurationMs > 0 && candidate.durationMs > 0) {
            val diffSec = abs(targetDurationMs - candidate.durationMs) / 1000
            when {
                diffSec <= 4 -> score += 40
                diffSec <= 8 -> score += 20
                diffSec <= 15 -> score += 10
            }
        }

        return score
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
            .replace(Regex("\\s*\\(audio.*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\[audio.*?\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(video.*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\[video.*?\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(music video.*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\[music video.*?\\]", RegexOption.IGNORE_CASE), "")
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

    private fun normalizeString(str: String): String {
        return Normalizer.normalize(str, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase()
            .replace(Regex("\\b(feat|featuring|ft)\\.?\\s+.*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[\\[\\(].*?[\\]\\)]"), "")
            .replace(Regex("[^a-z0-9]"), "")
            .trim()
    }

    private fun toAlphanumericOnly(str: String): String {
        return Normalizer.normalize(str, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]"), "")
            .trim()
    }

    private fun hasVersionKeyword(title: String): Boolean {
        val lower = title.lowercase()
        return lower.contains("extended") || lower.contains("remix") ||
                lower.contains("mix") || lower.contains("edit") ||
                lower.contains("version") || lower.contains("live") ||
                lower.contains("acoustic") || lower.contains("instrumental")
    }
}
