package org.sonora.android.playback

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.sonora.android.api.DeezerClient
import org.sonora.android.api.YouTubeMusicClient
import org.sonora.android.api.StreamResolver
import org.sonora.android.api.StreamSource
import org.sonora.android.api.Track
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Taste-driven recommendation and continuous radio engine for Sonora.
 *
 * Candidate sources are now YouTube Music (InnerTube) instead of Deezer:
 *   1. Seed track radio (`RDAMVM<videoId>` browse) — cohesive algorithmic station.
 *   2. Taste artist top tracks (followed artists + top-played artists from play stats).
 *   3. Taste seed track radio (recent history / top-played tracks).
 *   4. Artist text-search fallback.
 *
 * The scoring, anti-repetition (LRU + signature dedup), artist diversity and dislike
 * filtering logic is source-agnostic and retained from the previous engine.
 */
class RecommendationEngine(
    private val youTubeMusicClient: YouTubeMusicClient = YouTubeMusicClient(),
    private val streamResolver: StreamResolver = StreamResolver.getInstance()
) {

    companion object {
        private const val TAG = "RecommendationEngine"
        private const val DEFAULT_RECOMMENDATION_COUNT = 10
        private const val MAX_RECENT_HISTORY_SIZE = 250
        private const val MAX_ARTIST_TRACK_RATIO = 2 // Max tracks by same artist per batch of 10
        private const val MAX_TASTE_ARTISTS = 4
        private const val MAX_TASTE_TRACKS = 3

        @Volatile
        private var instance: RecommendationEngine? = null

        /**
         * Singleton accessor. [deezerClient] is retained only for call-site compatibility and
         * is not used anymore: candidates come from [YouTubeMusicClient].
         */
        @Suppress("UNUSED_PARAMETER")
        fun getInstance(
            deezerClient: DeezerClient = DeezerClient(),
            streamResolver: StreamResolver = StreamResolver.getInstance()
        ): RecommendationEngine {
            return instance ?: synchronized(this) {
                instance ?: RecommendationEngine(
                    youTubeMusicClient = YouTubeMusicClient(),
                    streamResolver = streamResolver
                ).also { instance = it }
            }
        }
    }

    data class AcousticProfile(
        val bpm: Float?,
        val gain: Float?,
        val rank: Int?
    )

    enum class CandidateSource(val baseScore: Float) {
        SEED_RADIO(45f),          // Seed track YouTube radio/mix
        TASTE_ARTIST_TOP(40f),    // Top tracks of followed / top-played artists
        TASTE_TRACK_RADIO(30f),   // Radio of taste seed tracks (history / top-played)
        SEARCH_FALLBACK(15f)      // Artist text search
    }

    private data class CandidateWithMeta(
        val track: Track,
        val source: CandidateSource
    )

    private data class ScoredCandidate(
        val track: Track,
        val score: Float
    )

    // Cache of resolved YouTube video ids (trackId -> videoId)
    private val videoIdCache = ConcurrentHashMap<String, String>()

    // Thread-safe LRU set of recently recommended track IDs to prevent replay loops
    private val recentlyRecommendedTrackIds: MutableSet<String> = Collections.synchronizedSet(
        object : java.util.LinkedHashSet<String>() {
            override fun add(element: String): Boolean {
                if (size >= MAX_RECENT_HISTORY_SIZE) {
                    val iterator = iterator()
                    if (iterator.hasNext()) {
                        iterator.next()
                        iterator.remove()
                    }
                }
                return super.add(element)
            }
        }
    )

    // Thread-safe blacklist sets for explicit dislikes
    private val blacklistedTrackIds = ConcurrentHashMap.newKeySet<String>()
    private val blacklistedArtists = ConcurrentHashMap.newKeySet<String>()

    fun blacklistTrack(trackId: String) {
        blacklistedTrackIds.add(trackId)
        Log.i(TAG, "Track blacklisted in RecommendationEngine: $trackId")
    }

    fun blacklistArtist(artist: String) {
        val norm = normalizeArtist(artist)
        if (norm.isNotBlank()) {
            blacklistedArtists.add(norm)
            Log.i(TAG, "Artist blacklisted in RecommendationEngine: $norm")
        }
    }

    /**
     * Recommends a cohesive batch of tracks matching the musical style of [currentTrack],
     * enriched by the user's [tasteProfile] (followed artists, top-played, recent history).
     */
    suspend fun getRecommendedTracks(
        currentTrack: Track,
        count: Int = DEFAULT_RECOMMENDATION_COUNT,
        excludedTrackIds: Set<String> = emptySet(),
        activeQueueTracks: List<Track> = emptyList(),
        historyTracks: List<Track> = emptyList(),
        dislikedTrackIds: Set<String> = emptySet(),
        dislikedArtists: Set<String> = emptySet(),
        tasteProfile: TasteProfile? = null
    ): List<Track> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Generating taste-based recommendations for: '${currentTrack.title}' by '${currentTrack.artist}'")

            val seedArtistNorm = normalizeArtist(currentTrack.artist)
            val combinedDislikedArtists = dislikedArtists + blacklistedArtists
            val combinedDislikedTrackIds = dislikedTrackIds + blacklistedTrackIds
            val dislikedArtistsNorm = combinedDislikedArtists
                .map { normalizeArtist(it) }
                .filter { it.isNotBlank() }
                .toSet()

            if (currentTrack.id in combinedDislikedTrackIds || seedArtistNorm in dislikedArtistsNorm) {
                Log.w(TAG, "Seed track '${currentTrack.title}' is disliked; skipping recommendation generation.")
                return@withContext emptyList()
            }

            val seedProfile = resolveAcousticProfile(currentTrack)

            val candidates = mutableListOf<CandidateWithMeta>()
            val seenCandidateIds = mutableSetOf<String>()

            fun addCandidate(track: Track, source: CandidateSource) {
                if (track.id.startsWith("yt_") && seenCandidateIds.add(track.id)) {
                    candidates.add(CandidateWithMeta(track, source))
                }
            }

            // Tier 1: seed track YouTube radio/mix.
            val seedVideoId = resolveVideoId(currentTrack)
            if (seedVideoId != null) {
                youTubeMusicClient.getYouTubeRadio(seedVideoId, limit = 50)
                    .onSuccess { radio ->
                        Log.d(TAG, "Seed radio returned ${radio.size} tracks for videoId=$seedVideoId")
                        radio.forEach { addCandidate(it, CandidateSource.SEED_RADIO) }
                    }
                    .onFailure { Log.w(TAG, "Seed radio query failed: ${it.message}") }
            }

            // Tier 2: taste artist top tracks (followed + top-played artists).
            tasteProfile?.seedArtists
                ?.asSequence()
                ?.filter { !it.browseId.isNullOrBlank() }
                ?.filter { normalizeArtist(it.name) !in dislikedArtistsNorm }
                ?.take(MAX_TASTE_ARTISTS)
                ?.forEach { seed ->
                    youTubeMusicClient.getYouTubeArtistTopTracks(seed.browseId!!, limit = 12)
                        .onSuccess { tracks -> tracks.forEach { addCandidate(it, CandidateSource.TASTE_ARTIST_TOP) } }
                        .onFailure { Log.w(TAG, "Artist top tracks failed for ${seed.name}: ${it.message}") }
                }

            // Tier 3: radio for taste seed tracks (history / top-played), excluding the current seed.
            tasteProfile?.seedTracks
                ?.asSequence()
                ?.filter { it.id != currentTrack.id }
                ?.filter { normalizeArtist(it.artist) !in dislikedArtistsNorm }
                ?.take(MAX_TASTE_TRACKS)
                ?.forEach { seedTrack ->
                    val vid = resolveVideoId(seedTrack) ?: return@forEach
                    youTubeMusicClient.getYouTubeRadio(vid, limit = 15)
                        .onSuccess { radio -> radio.forEach { addCandidate(it, CandidateSource.TASTE_TRACK_RADIO) } }
                }

            // Fallback: artist text search when the pool is still small (no global charts).
            if (candidates.size < count * 2) {
                youTubeMusicClient.searchTracks(currentTrack.artist, limit = 20)
                    .onSuccess { results -> results.forEach { addCandidate(it, CandidateSource.SEARCH_FALLBACK) } }
            }

            if (candidates.isEmpty()) {
                Log.w(TAG, "No candidate tracks found for recommendation")
                return@withContext emptyList()
            }

            val filtered = filterAndRankCandidates(
                candidates = candidates,
                seedTrack = currentTrack,
                seedProfile = seedProfile,
                count = count,
                excludedTrackIds = excludedTrackIds,
                activeQueueTracks = activeQueueTracks,
                historyTracks = historyTracks,
                dislikedTrackIds = combinedDislikedTrackIds,
                dislikedArtists = combinedDislikedArtists,
                preferredArtists = tasteProfile?.seedArtists?.map { it.name }?.toSet().orEmpty()
            )

            for (track in filtered) {
                recentlyRecommendedTrackIds.add(track.id)
            }

            Log.i(TAG, "Generated ${filtered.size} recommendations for '${currentTrack.title}'")
            filtered
        } catch (e: Exception) {
            Log.e(TAG, "Error generating recommendations", e)
            emptyList()
        }
    }

    /**
     * Generates home page recommendations ("Consigliati per te") from the user's [tasteProfile].
     */
    suspend fun getHomeRecommendations(
        tasteProfile: TasteProfile,
        count: Int = 10,
        historyTracks: List<Track> = emptyList(),
        dislikedTrackIds: Set<String> = emptySet(),
        dislikedArtists: Set<String> = emptySet()
    ): List<Track> = withContext(Dispatchers.IO) {
        try {
            val combinedDislikedArtists = dislikedArtists + blacklistedArtists
            val combinedDislikedTrackIds = dislikedTrackIds + blacklistedTrackIds
            val dislikedArtistsNorm = combinedDislikedArtists
                .map { normalizeArtist(it) }
                .filter { it.isNotBlank() }
                .toSet()

            val candidates = mutableListOf<CandidateWithMeta>()
            val seenCandidateIds = mutableSetOf<String>()

            fun addCandidate(track: Track, source: CandidateSource) {
                if (track.id.startsWith("yt_") && seenCandidateIds.add(track.id)) {
                    candidates.add(CandidateWithMeta(track, source))
                }
            }

            // 1. Artist top tracks for followed / top-played artists.
            tasteProfile.seedArtists
                .asSequence()
                .filter { !it.browseId.isNullOrBlank() }
                .filter { normalizeArtist(it.name) !in dislikedArtistsNorm }
                .take(MAX_TASTE_ARTISTS)
                .forEach { seed ->
                    youTubeMusicClient.getYouTubeArtistTopTracks(seed.browseId!!, limit = 12)
                        .onSuccess { tracks -> tracks.forEach { addCandidate(it, CandidateSource.TASTE_ARTIST_TOP) } }
                        .onFailure { Log.w(TAG, "Home artist top tracks failed for ${seed.name}: ${it.message}") }
                }

            // 2. Radio of the strongest seed tracks (top-played / recent history).
            tasteProfile.seedTracks
                .asSequence()
                .filter { normalizeArtist(it.artist) !in dislikedArtistsNorm && it.id !in combinedDislikedTrackIds }
                .take(MAX_TASTE_ARTISTS)
                .forEach { seedTrack ->
                    val vid = resolveVideoId(seedTrack) ?: return@forEach
                    youTubeMusicClient.getYouTubeRadio(vid, limit = 15)
                        .onSuccess { radio -> radio.forEach { addCandidate(it, CandidateSource.TASTE_TRACK_RADIO) } }
                }

            // 3. Last-resort artist search for the strongest seed track (no global charts).
            if (candidates.size < count) {
                val fallbackArtist = tasteProfile.seedArtists.firstOrNull { normalizeArtist(it.name) !in dislikedArtistsNorm }
                    ?.name
                    ?: tasteProfile.seedTracks.firstOrNull()?.artist
                if (!fallbackArtist.isNullOrBlank()) {
                    youTubeMusicClient.searchTracks(fallbackArtist, limit = 20)
                        .onSuccess { results -> results.forEach { addCandidate(it, CandidateSource.SEARCH_FALLBACK) } }
                }
            }

            if (candidates.isEmpty()) {
                Log.w(TAG, "No candidates found for home recommendations")
                return@withContext emptyList()
            }

            val historyIds = (tasteProfile.seedTracks + historyTracks).map { it.id }.toSet()

            val filtered = filterAndRankCandidates(
                candidates = candidates,
                seedTrack = tasteProfile.seedTracks.firstOrNull(),
                seedProfile = tasteProfile.seedTracks.firstOrNull()?.let { resolveAcousticProfile(it) },
                count = count,
                excludedTrackIds = historyIds,
                activeQueueTracks = emptyList(),
                historyTracks = if (historyTracks.isNotEmpty()) historyTracks else tasteProfile.seedTracks,
                dislikedTrackIds = combinedDislikedTrackIds,
                dislikedArtists = combinedDislikedArtists,
                preferredArtists = tasteProfile.seedArtists.map { it.name }.toSet()
            )

            Log.i(TAG, "Generated ${filtered.size} home recommendations")
            filtered
        } catch (e: Exception) {
            Log.e(TAG, "Error generating home recommendations", e)
            emptyList()
        }
    }

    /**
     * Pre-resolves audio stream URL for [track] using [StreamResolver] in background.
     */
    suspend fun prefetchTrack(track: Track): StreamSource? = withContext(Dispatchers.IO) {
        try {
            streamResolver.resolveStream(track)
        } catch (e: Exception) {
            Log.w(TAG, "Failed prefetching stream for track ${track.title}", e)
            null
        }
    }

    /**
     * Clears internal recommendation history cache.
     */
    fun clearHistory() {
        recentlyRecommendedTrackIds.clear()
        Log.d(TAG, "Cleared recommendation history cache")
    }

    // ==========================================
    // Internal Filtering, Scoring & Ranking
    // ==========================================

    private fun filterAndRankCandidates(
        candidates: List<CandidateWithMeta>,
        seedTrack: Track?,
        seedProfile: AcousticProfile?,
        count: Int,
        excludedTrackIds: Set<String>,
        activeQueueTracks: List<Track> = emptyList(),
        historyTracks: List<Track> = emptyList(),
        dislikedTrackIds: Set<String> = emptySet(),
        dislikedArtists: Set<String> = emptySet(),
        preferredArtists: Set<String> = emptySet()
    ): List<Track> {
        val dislikedArtistsNorm = dislikedArtists
            .map { normalizeArtist(it) }
            .filter { it.isNotBlank() }
            .toSet()

        val preferredArtistsNorm = preferredArtists
            .map { normalizeArtist(it) }
            .filter { it.isNotBlank() }
            .toSet()

        // 1. Strict ID exclusion set
        val allExcludedIds = excludedTrackIds + recentlyRecommendedTrackIds + dislikedTrackIds +
                (if (seedTrack != null) setOf(seedTrack.id) else emptySet())

        // 2. Strict normalized song signature exclusion set (artist::title)
        val excludedSignatures = mutableSetOf<String>()

        if (seedTrack != null) {
            excludedSignatures.add("${normalizeArtist(seedTrack.artist)}::${normalizeTitle(seedTrack.title)}")
        }
        for (qTrack in activeQueueTracks) {
            excludedSignatures.add("${normalizeArtist(qTrack.artist)}::${normalizeTitle(qTrack.title)}")
        }
        for (hTrack in historyTracks.take(100)) {
            excludedSignatures.add("${normalizeArtist(hTrack.artist)}::${normalizeTitle(hTrack.title)}")
        }

        // 3. Filter candidates and compute similarity score
        val scoredCandidates = mutableListOf<ScoredCandidate>()
        val seenInCurrentFiltering = mutableSetOf<String>()

        for (candidateMeta in candidates) {
            val candidate = candidateMeta.track
            val candidateId = candidate.id
            if (candidateId.isBlank() || candidateId in allExcludedIds || candidateId in seenInCurrentFiltering) {
                continue
            }

            val candArtistNorm = normalizeArtist(candidate.artist)
            val candTitleNorm = normalizeTitle(candidate.title)

            if (candArtistNorm in dislikedArtistsNorm) {
                continue
            }

            if (dislikedArtistsNorm.any { disliked -> candidate.title.contains(disliked, ignoreCase = true) }) {
                continue
            }

            val signature = "$candArtistNorm::$candTitleNorm"
            if (signature in excludedSignatures) {
                continue
            }

            if (!isValidMusicTrack(candidate, seedTrack)) {
                continue
            }

            seenInCurrentFiltering.add(candidateId)

            // --- Multi-dimensional similarity score ---
            var score = candidateMeta.source.baseScore

            // A. Popularity Rank Bonus
            val rank = candidate.rank
            if (rank != null && rank > 0) {
                val normalizedRank = (rank.coerceIn(0, 1_000_000) / 1_000_000f)
                score += normalizedRank * 20f
            }

            // B. Tempo (BPM) Acoustic Similarity
            val seedBpm = seedProfile?.bpm ?: seedTrack?.bpm
            val candBpm = candidate.bpm
            if (seedBpm != null && seedBpm > 0f && candBpm != null && candBpm > 0f) {
                val bpmDiff = abs(candBpm - seedBpm)
                score += when {
                    bpmDiff <= 5f -> 25f
                    bpmDiff <= 12f -> 16f
                    bpmDiff <= 20f -> 8f
                    abs(candBpm * 2f - seedBpm) <= 6f || abs(candBpm - seedBpm * 2f) <= 6f -> 14f
                    bpmDiff > 40f -> -20f
                    else -> 0f
                }
            }

            // C. Energy & Loudness (Gain) Acoustic Similarity
            val seedGain = seedProfile?.gain ?: seedTrack?.gain
            val candGain = candidate.gain
            if (seedGain != null && candGain != null) {
                val gainDiff = abs(candGain - seedGain)
                score += when {
                    gainDiff <= 2.0f -> 15f
                    gainDiff <= 4.0f -> 8f
                    gainDiff > 7.0f -> -15f
                    else -> 0f
                }
            }

            // D. Duration Proximity & Radio Sweet Spot
            if (seedTrack != null && seedTrack.durationMs > 0) {
                val durationDiffSec = abs(candidate.durationMs - seedTrack.durationMs) / 1000L
                if (durationDiffSec <= 60L) {
                    score += 5f
                }
            }
            if (candidate.durationMs in 120_000L..330_000L) {
                score += 4f
            }

            // E. User Preferred Artist Affinity (taste profile)
            if (candArtistNorm in preferredArtistsNorm) {
                score += 15f
            }

            scoredCandidates.add(ScoredCandidate(candidate, score))
        }

        val rankedPool = scoredCandidates.sortedByDescending { it.score }.toMutableList()

        // 4. Sequencing with strict artist diversity (max 2 per 10) and interleaving
        val result = mutableListOf<Track>()
        val seenBatchSignatures = mutableSetOf<String>()
        val artistCountMap = mutableMapOf<String, Int>()

        while (result.size < count && rankedPool.isNotEmpty()) {
            val lastArtist = result.lastOrNull()?.let { normalizeArtist(it.artist) }

            val primaryIndex = rankedPool.indexOfFirst { item ->
                val a = normalizeArtist(item.track.artist)
                val sig = "$a::${normalizeTitle(item.track.title)}"
                val c = artistCountMap.getOrDefault(a, 0)
                c < MAX_ARTIST_TRACK_RATIO && sig !in seenBatchSignatures && (lastArtist == null || a != lastArtist)
            }

            if (primaryIndex != -1) {
                val picked = rankedPool.removeAt(primaryIndex)
                val a = normalizeArtist(picked.track.artist)
                result.add(picked.track)
                seenBatchSignatures.add("$a::${normalizeTitle(picked.track.title)}")
                artistCountMap[a] = artistCountMap.getOrDefault(a, 0) + 1
            } else {
                val secondaryIndex = rankedPool.indexOfFirst { item ->
                    val a = normalizeArtist(item.track.artist)
                    val sig = "$a::${normalizeTitle(item.track.title)}"
                    val c = artistCountMap.getOrDefault(a, 0)
                    c < MAX_ARTIST_TRACK_RATIO && sig !in seenBatchSignatures
                }
                if (secondaryIndex != -1) {
                    val picked = rankedPool.removeAt(secondaryIndex)
                    val a = normalizeArtist(picked.track.artist)
                    result.add(picked.track)
                    seenBatchSignatures.add("$a::${normalizeTitle(picked.track.title)}")
                    artistCountMap[a] = artistCountMap.getOrDefault(a, 0) + 1
                } else {
                    break
                }
            }
        }

        return result
    }

    /**
     * Checks if a candidate track is valid musical content (not karaoke, ringtone, tribute, etc.).
     */
    private fun isValidMusicTrack(candidate: Track, seedTrack: Track?): Boolean {
        if (candidate.durationMs < 45_000L || candidate.durationMs > 900_000L) {
            return false
        }

        val titleLower = candidate.title.lowercase()
        val seedTitleLower = seedTrack?.title?.lowercase().orEmpty()

        val junkKeywords = listOf(
            "karaoke",
            "instrumental version",
            "tribute to",
            "originally performed by",
            "in the style of",
            "ringtone",
            "sound effect",
            "asmr",
            "white noise",
            "rain sound",
            "guided meditation",
            "ambient sleep",
            "1 hour loop"
        )

        for (keyword in junkKeywords) {
            if (titleLower.contains(keyword) && !seedTitleLower.contains(keyword)) {
                return false
            }
        }

        return true
    }

    // ==========================================
    // Seed Acoustic & Video ID Resolution
    // ==========================================

    private fun resolveAcousticProfile(track: Track): AcousticProfile? {
        if (track.bpm != null || track.gain != null || track.rank != null) {
            return AcousticProfile(track.bpm, track.gain, track.rank)
        }
        return null
    }

    /** Resolves the YouTube videoId for a seed track: direct `yt_` id, else a YouTube search. */
    private suspend fun resolveVideoId(track: Track): String? {
        val raw = track.id.removePrefix("yt_")
        if (YouTubeMusicClient.isValidVideoId(raw)) return raw

        videoIdCache[track.id]?.let { return it }

        val query = "${track.title} ${track.artist}".trim()
        if (query.isBlank()) return null
        val results = youTubeMusicClient.searchTracks(query, limit = 5).getOrNull().orEmpty()
        val resolved = results
            .firstOrNull { it.id.startsWith("yt_") }
            ?.id?.removePrefix("yt_")
            ?.takeIf { YouTubeMusicClient.isValidVideoId(it) }
        if (resolved != null) videoIdCache[track.id] = resolved
        return resolved
    }

    // ==========================================
    // Normalization Utilities
    // ==========================================

    private fun normalizeTitle(title: String): String {
        return title.lowercase()
            .replace(Regex("\\s*\\(feat\\..*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\[feat\\..*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(with.*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(remaster.*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\[remaster.*?\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*-\\s*Remaster.*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(live.*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\[live.*?\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(radio edit\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(deluxe.*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\[deluxe.*?\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[^a-z0-9]"), "")
            .trim()
    }

    private fun normalizeArtist(artist: String): String {
        return artist.lowercase()
            .split(",", "&", "feat.", "ft.", "featuring")[0]
            .replace(Regex("[^a-z0-9]"), "")
            .trim()
    }
}
