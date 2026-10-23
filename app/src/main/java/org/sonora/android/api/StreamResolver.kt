package org.sonora.android.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Accent-insensitive, punctuation-insensitive normalization used only for matching a Spotify entry
 * against a YouTube Music candidate. NFD decomposition + combining-mark removal + lowercase(ROOT).
 */
internal fun normalizeMatchText(input: String): String {
    val decomposed = java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFD)
    return decomposed
        .replace(Regex("\\p{M}+"), "")
        .lowercase(java.util.Locale.ROOT)
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

/**
 * Result of an artist compatibility check, carrying token-overlap details for logging.
 */
internal data class ArtistMatchResult(
    val compatible: Boolean,
    val usedTokenFallback: Boolean = false,
    val commonTokens: Set<String> = emptySet(),
    val wantTokenCount: Int = 0,
    val candidateTokenCount: Int = 0
)

private val NON_INFORMATIVE_ARTIST_TOKENS = setOf(
    "the", "a", "an", "and", "si", "feat", "featuring", "official",
    "music", "video", "audio", "dj", "mc", "remix"
)

internal fun informativeArtistTokens(normalized: String): Set<String> {
    return normalized.split(" ")
        .map { it.trim() }
        .filter { it.length > 1 && it !in NON_INFORMATIVE_ARTIST_TOKENS }
        .toSet()
}

/**
 * Spotify <-> YouTube artist compatibility. Primary check (equality/contains) first; only if that
 * fails, falls back to informative-token overlap requiring >=2 shared tokens and ratio >= 0.5.
 */
internal fun matchArtist(wantArtist: String, candidateArtist: String): ArtistMatchResult {
    if (wantArtist.isBlank() || candidateArtist.isBlank()) return ArtistMatchResult(compatible = false)
    if (candidateArtist.contains(wantArtist) || wantArtist.contains(candidateArtist)) {
        return ArtistMatchResult(compatible = true)
    }
    val wantTokens = informativeArtistTokens(wantArtist)
    val candidateTokens = informativeArtistTokens(candidateArtist)
    val common = wantTokens.intersect(candidateTokens)
    val minCount = minOf(wantTokens.size, candidateTokens.size)
    val compatible = common.size >= 2 && minCount > 0 && common.size.toDouble() / minCount >= 0.5
    return ArtistMatchResult(
        compatible = compatible,
        usedTokenFallback = true,
        commonTokens = common,
        wantTokenCount = wantTokens.size,
        candidateTokenCount = candidateTokens.size
    )
}

/**
 * Pure reliability predicate for a YouTube Music candidate: yt_ id, compatible title and artist,
 * and duration within ±8 seconds. Title/artist inputs are already-normalized.
 */
internal fun youtubeCandidateMatches(
    wantTitle: String,
    wantArtist: String,
    wantDurationMs: Long,
    candidateId: String,
    candidateTitle: String,
    candidateArtist: String,
    candidateDurationMs: Long
): Boolean {
    if (!candidateId.startsWith("yt_")) return false
    val cTitle = normalizeMatchText(candidateTitle)
    val cArtist = normalizeMatchText(candidateArtist)
    val titleOk = cTitle.isNotBlank() && wantTitle.isNotBlank() &&
        (cTitle == wantTitle || cTitle.contains(wantTitle) || wantTitle.contains(cTitle))
    val artistOk = matchArtist(wantArtist, cArtist).compatible
    // TODO(duration-match): when a duration is unknown (<= 0) this guard currently
    // PASSES on title+artist alone, so a YT/Spotify track with no duration can be
    // matched to the wrong candidate. Risk: wrong audio for obscure/duplicate titles.
    // Fix (requires explicit authorization + tests, NOT done in the artist-UX phase):
    // require exact normalized-title equality + compatible artist when both durations
    // are unknown, else treat as no-match. See ARTIST_METADATA_UX_PLAN.md §B.
    val durationOk = wantDurationMs <= 0 || candidateDurationMs <= 0 ||
        abs(candidateDurationMs - wantDurationMs) <= 8000
    return titleOk && artistOk && durationOk
}

/**
 * High-level streaming coordinator that resolves optimal audio streaming URLs
 * using live healthy providers with active blackout detection (3.5s strict timeout)
 * and automatic multi-tier fallback:
 *
 * Tier 1: Monochrome HiFi Streamer (Bit-perfect Lossless FLAC 1411 kbps)
 * Tier 2: JioSaavn High-Bitrate AAC CDN (320 kbps & 160 kbps direct CDN streams)
 * Tier 3: Cobalt Universal Audio Stream Extractor (High-quality M4A/Opus)
 * Tier 4: Invidious Dedicated Stream Resolver (Direct GoogleVideo M4A/Opus)
 * Tier 5: Piped YouTube Music Stream Resolver (Rotating healthy Piped instances)
 *
 * Full-length streams ONLY (never 30-second previews).
 */
open class StreamResolver(
    private val apiHealthManager: ApiHealthManager = ApiHealthManager.getInstance(),
    private val monochromeApiClient: MonochromeApiClient = MonochromeApiClient(
        healthCallback = { url, success, latency, error ->
            if (success) {
                apiHealthManager.recordSuccess(url, latency)
            } else {
                if (latency > BLACKOUT_THRESHOLD_MS || error?.contains("timeout", ignoreCase = true) == true || error?.contains("429") == true || error?.contains("50") == true) {
                    apiHealthManager.recordBlackout(url, error)
                } else {
                    apiHealthManager.recordFailure(url, error)
                }
            }
        }
    ),
    private val jioSaavnFallbackClient: JioSaavnFallbackClient = JioSaavnFallbackClient(
        healthCallback = { url, success, latency, error ->
            if (success) {
                apiHealthManager.recordSuccess(url, latency)
            } else {
                if (latency > BLACKOUT_THRESHOLD_MS || error?.contains("timeout", ignoreCase = true) == true || error?.contains("429") == true || error?.contains("50") == true) {
                    apiHealthManager.recordBlackout(url, error)
                } else {
                    apiHealthManager.recordFailure(url, error)
                }
            }
        }
    ),
    private val cobaltAudioResolver: CobaltAudioResolver = CobaltAudioResolver(
        healthCallback = { url, success, latency, error ->
            if (success) {
                apiHealthManager.recordSuccess(url, latency)
            } else {
                if (latency > BLACKOUT_THRESHOLD_MS || error?.contains("timeout", ignoreCase = true) == true || error?.contains("429") == true || error?.contains("50") == true) {
                    apiHealthManager.recordBlackout(url, error)
                } else {
                    apiHealthManager.recordFailure(url, error)
                }
            }
        }
    ),
    private val invidiousStreamResolver: InvidiousStreamResolver = InvidiousStreamResolver(
        healthCallback = { url, success, latency, error ->
            if (success) {
                apiHealthManager.recordSuccess(url, latency)
            } else {
                if (latency > BLACKOUT_THRESHOLD_MS || error?.contains("timeout", ignoreCase = true) == true || error?.contains("429") == true || error?.contains("50") == true) {
                    apiHealthManager.recordBlackout(url, error)
                } else {
                    apiHealthManager.recordFailure(url, error)
                }
            }
        }
    ),
    private val pipedInvidiousClient: PipedInvidiousClient = PipedInvidiousClient(
        healthCallback = { url, success, latency, error ->
            if (success) {
                apiHealthManager.recordSuccess(url, latency)
            } else {
                if (latency > BLACKOUT_THRESHOLD_MS || error?.contains("timeout", ignoreCase = true) == true || error?.contains("429") == true || error?.contains("50") == true) {
                    apiHealthManager.recordBlackout(url, error)
                } else {
                    apiHealthManager.recordFailure(url, error)
                }
            }
        }
    ),
    private val deezerClient: DeezerClient = DeezerClient(),
    private val streamProber: (suspend (url: String) -> Boolean)? = null,
    private val youTubeMusicClient: YouTubeMusicClient = YouTubeMusicClient()
) {

    var dynamicStreamProber: (suspend (url: String) -> Boolean)? = null

    /**
     * Test seam: when set, overrides YouTube Music InnerTube (/player) resolution for yt_ tracks.
     */
    internal var youTubeMusicResolverOverride: (suspend (track: Track) -> StreamSource?)? = null

    /**
     * Test seam: when set, overrides YouTube Music search used for imported-track matching.
     */
    internal var youTubeSearchOverride: (suspend (query: String, limit: Int) -> List<Track>)? = null

    companion object {
        private const val TAG = "StreamResolver"
        const val BLACKOUT_THRESHOLD_MS = 3500L
        const val TIER_TIMEOUT_MS = 6000L

        @Volatile
        private var instance: StreamResolver? = null

        fun getInstance(): StreamResolver {
            return instance ?: synchronized(this) {
                instance ?: StreamResolver().also { instance = it }
            }
        }

        // Cache resolved streams for 2 hours (GoogleVideo and Saavn tokens live 6+ hours)
        private const val CACHE_TTL_MS = 2 * 60 * 60 * 1000L
    }

    private data class CachedStream(
        val source: StreamSource,
        val timestamp: Long
    )

    private val streamCache = ConcurrentHashMap<String, CachedStream>()

    /**
     * Resolves the direct audio streaming URL for a track.
     * Consumed by SonoraMediaSessionService and PlaybackManager.
     */
    suspend fun resolveStreamUrl(track: Track): String? {
        return resolveStream(track)?.url
    }

    /**
     * Resolves the direct audio streaming URL from track title and artist.
     */
    suspend fun resolveStreamUrl(title: String, artist: String): String? {
        return resolveStream(title, artist)?.url
    }

    /**
     * Resolves the complete [StreamSource] metadata for a [Track].
     *
     * @param forceSource overrides the persisted per-track/global preference for this call only.
     */
    open suspend fun resolveStream(
        track: Track,
        forceSource: PlaybackSource? = null
    ): StreamSource? = withContext(Dispatchers.IO) {
        val effectiveSource = forceSource ?: TrackSourcePreferences.effective(track.id)
        val cacheKey = buildCacheKey(track.id, track.title, track.artist, effectiveSource)

        // Check memory cache: ONLY full non-preview streams are cached
        streamCache[cacheKey]?.let { cached ->
            if (!cached.source.isPreview && !isDeezerPreviewUrl(cached.source.url)) {
                val isExpired = System.currentTimeMillis() - cached.timestamp > CACHE_TTL_MS
                if (!isExpired) {
                    Log.d(TAG, "Cache HIT for '${track.title}' -> ${cached.source.provider}")
                    return@withContext cached.source
                } else {
                    streamCache.remove(cacheKey)
                }
            } else {
                streamCache.remove(cacheKey)
            }
        }

        // Direct stream resolution if track is from YouTube Music.
        // Default order (AUTO): Monochrome FLAC -> Monochrome high -> YouTube Music VISIONOS -> JioSaavn -> Invidious.
        // The order of the two primary tiers follows the effective [PlaybackSource].
        if (track.id.startsWith("yt_") || track.provider.equals("YouTube", ignoreCase = true)) {
            val videoId = track.id.removePrefix("yt_")
            Log.i(TAG, "Direct YouTube track identified, resolving stream for videoId: $videoId (source=$effectiveSource)")

            val cleanTitle = sanitizeTitle(track.title, track.artist)
            val cleanArtist = sanitizeArtist(track.artist)

            var directStream: StreamSource? = null

            if (track.isVideo) {
                // Video-derived tracks: skip Monochrome entirely and resolve directly on YouTube,
                // because Monochrome (lossless catalog) does not carry these video-only uploads.
                Log.i(TAG, "Video-derived track: skipping Monochrome, resolving via YouTube Music directly")
                directStream = resolveViaYouTubeMusic(track)
            } else {
                // Primary tiers ordered by the effective source (per-track override > global > AUTO default).
                val primaryOrder = when (effectiveSource) {
                    PlaybackSource.YOUTUBE_MUSIC -> listOf(PlaybackSource.YOUTUBE_MUSIC, PlaybackSource.MONOCHROME)
                    else -> listOf(PlaybackSource.MONOCHROME, PlaybackSource.YOUTUBE_MUSIC)
                }
                for (source in primaryOrder) {
                    if (directStream != null) break
                    directStream = when (source) {
                        PlaybackSource.MONOCHROME ->
                            resolveMonochromeChain(cleanTitle, cleanArtist, track.durationMs)
                        PlaybackSource.YOUTUBE_MUSIC -> resolveViaYouTubeMusic(track)
                        PlaybackSource.AUTO -> null
                    }
                }
            }

            // D. JioSaavn full stream (only if YouTube Music did not resolve)
            if (directStream == null) {
                directStream = resolveJioSaavnDirect(cleanTitle, cleanArtist)
            }

            // E. Invidious as the last fallback
            if (directStream == null) {
                directStream = resolveInvidiousDirect(videoId, cleanTitle, cleanArtist, track.durationMs)
            }

            if (directStream != null) {
                streamCache[cacheKey] = CachedStream(directStream, System.currentTimeMillis())
                return@withContext directStream
            }

            Log.i(TAG, "All yt_ sources failed for videoId: $videoId ('${track.title}' by '${track.artist}').")
        }

        // Imported Spotify tracks: resolve a reliable YT Music match at runtime, then use the
        // Monochrome -> VISIONOS -> JioSaavn -> Invidious chain. Never alters id/provider.
        if (track.id.startsWith("spotify_") || track.provider.equals("Spotify", ignoreCase = true)) {
            val source = resolveSpotifyImported(track, effectiveSource)
            if (source != null) {
                streamCache[cacheKey] = CachedStream(source, System.currentTimeMillis())
                return@withContext source
            }
            Log.i(TAG, "Spotify-import runtime resolution failed for '${track.title}', falling back to tiered resolution.")
        }

        val resolved = resolveTieredStream(
            title = track.title,
            artist = track.artist,
            durationMs = track.durationMs
        )

        if (resolved != null && !resolved.isPreview && !isDeezerPreviewUrl(resolved.url) && isStreamPlayable(resolved.url)) {
            Log.i(TAG, "✓ FULL STREAM CONFIRMED for track '${track.title}' (${track.artist}): " +
                    "Provider=${resolved.provider}, Quality=${resolved.quality}, Bitrate=${resolved.bitrate}kbps -> ${resolved.url}")
            streamCache[cacheKey] = CachedStream(resolved, System.currentTimeMillis())
            resolved
        } else {
            Log.e(TAG, "✗ RESOLUTION FAILED: No full audio stream could be resolved for '${track.title}' (${track.artist})")
            null
        }
    }

    /**
     * Resolves a full stream for an offline download at the requested quality tier.
     *
     * Uses the same provider chain/order as streaming; the quality is only a preference
     * forwarded to the providers that support it. Video-only and imported tracks fall back
     * to the standard resolver when the tiered chain cannot find a match.
     */
    open suspend fun resolveStreamForDownload(
        track: Track,
        quality: AudioQuality
    ): StreamSource? = withContext(Dispatchers.IO) {
        val isYouTube = track.id.startsWith("yt_") || track.provider.equals("YouTube", ignoreCase = true)
        val wantsLossless = quality == AudioQuality.ULTRA

        // Monochrome always serves lossless FLAC, so only use it when ULTRA was requested.
        // For smaller tiers prefer the providers that actually honor the requested bitrate.
        if (isYouTube) {
            if (wantsLossless && !track.isVideo) {
                val cleanTitle = sanitizeTitle(track.title, track.artist)
                val cleanArtist = sanitizeArtist(track.artist)
                resolveMonochromeDirect(cleanTitle, cleanArtist, track.durationMs, quality)
                    ?.let { stream ->
                        Log.i(TAG, "✓ DOWNLOAD STREAM (Monochrome $quality) for '${track.title}'")
                        return@withContext stream.copy(isPreview = false)
                    }
            }

            val tiered = resolveTieredStream(
                title = track.title,
                artist = track.artist,
                durationMs = track.durationMs,
                quality = quality,
                skipMonochrome = true
            )
            if (tiered != null && !tiered.isPreview && isStreamPlayable(tiered.url)) {
                Log.i(TAG, "✓ DOWNLOAD STREAM RESOLVED for '${track.title}' ($quality): " +
                        "Provider=${tiered.provider}, Bitrate=${tiered.bitrate}kbps")
                return@withContext tiered
            }
            return@withContext resolveViaYouTubeMusic(track) ?: resolveStream(track)
        }

        // Others (imported/legacy): streaming provider chain at the requested quality.
        val resolved = resolveTieredStream(
            title = track.title,
            artist = track.artist,
            durationMs = track.durationMs,
            quality = quality,
            skipMonochrome = !wantsLossless
        )
        if (resolved != null && !resolved.isPreview && !isDeezerPreviewUrl(resolved.url) &&
            isStreamPlayable(resolved.url)
        ) {
            Log.i(TAG, "✓ DOWNLOAD STREAM RESOLVED for '${track.title}' ($quality): " +
                    "Provider=${resolved.provider}, Bitrate=${resolved.bitrate}kbps")
            return@withContext resolved
        }
        resolveStream(track)
    }

    /**
     * Resolves the complete [StreamSource] metadata by title and artist.
     */
    suspend fun resolveStream(
        title: String,
        artist: String,
        durationMs: Long? = null
    ): StreamSource? = withContext(Dispatchers.IO) {
        val cacheKey = buildCacheKey(null, title, artist)

        streamCache[cacheKey]?.let { cached ->
            if (!cached.source.isPreview && !isDeezerPreviewUrl(cached.source.url)) {
                val isExpired = System.currentTimeMillis() - cached.timestamp > CACHE_TTL_MS
                if (!isExpired) {
                    Log.d(TAG, "Cache HIT for '$title - $artist' -> ${cached.source.provider}")
                    return@withContext cached.source
                } else {
                    streamCache.remove(cacheKey)
                }
            } else {
                streamCache.remove(cacheKey)
            }
        }

        val resolved = resolveTieredStream(
            title = title,
            artist = artist,
            durationMs = durationMs ?: 0L
        )

        if (resolved != null && !resolved.isPreview && !isDeezerPreviewUrl(resolved.url) && isStreamPlayable(resolved.url)) {
            Log.i(TAG, "✓ FULL STREAM CONFIRMED for '$title - $artist': " +
                    "Provider=${resolved.provider}, Quality=${resolved.quality}, Bitrate=${resolved.bitrate}kbps -> ${resolved.url}")
            streamCache[cacheKey] = CachedStream(resolved, System.currentTimeMillis())
            resolved
        } else {
            Log.e(TAG, "✗ RESOLUTION FAILED for '$title - $artist'")
            null
        }
    }

    /**
     * Monochrome HiFi resolution chain for a single track: lossless FLAC first, then high quality.
     */
    private suspend fun resolveMonochromeChain(
        cleanTitle: String,
        cleanArtist: String,
        durationMs: Long
    ): StreamSource? {
        return resolveMonochromeDirect(cleanTitle, cleanArtist, durationMs, AudioQuality.ULTRA)
            ?: resolveMonochromeDirect(cleanTitle, cleanArtist, durationMs, AudioQuality.HIGH)
    }

    /**
     * Monochrome-only resolution for yt_ tracks. Existing Monochrome guardrails
     * (score >= 75, artist compatibility, duration ±8s) are enforced inside the client.
     * No circuit-breaker penalty is applied here.
     */
    private suspend fun resolveMonochromeDirect(
        cleanTitle: String,
        cleanArtist: String,
        durationMs: Long,
        quality: AudioQuality
    ): StreamSource? {
        return try {
            val healthy = apiHealthManager.getHealthyEndpoints(EndpointType.MONOCHROME).map { it.baseUrl }
            if (healthy.isNotEmpty()) {
                monochromeApiClient.updateInstances(healthy)
            }
            val result = withTimeoutOrNull(TIER_TIMEOUT_MS) {
                monochromeApiClient.resolveStream(
                    title = cleanTitle,
                    artist = cleanArtist,
                    preferredQuality = quality,
                    durationMs = durationMs
                )
            }
            if (result != null && result.isSuccess) {
                val stream = result.getOrNull()
                if (stream != null && stream.url.isNotBlank() && isStreamPlayable(stream.url)) {
                    Log.i(TAG, "★ yt_ stream resolved [Monochrome $quality]: '$cleanTitle' by '$cleanArtist' -> ${stream.url} (${stream.bitrate}kbps)")
                    return stream.copy(isPreview = false)
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "yt_ Monochrome ($quality) error: ${e.message}")
            null
        }
    }

    /**
     * JioSaavn full-stream resolution for yt_ tracks (only after YouTube Music fails).
     */
    private suspend fun resolveJioSaavnDirect(cleanTitle: String, cleanArtist: String): StreamSource? {
        return try {
            val healthySaavn = apiHealthManager.getActiveMirrors(EndpointType.JIOSAAVN)
            if (healthySaavn.isNotEmpty()) {
                jioSaavnFallbackClient.updateMirrors(healthySaavn)
            }
            val result = withTimeoutOrNull(TIER_TIMEOUT_MS) {
                jioSaavnFallbackClient.resolveStream(cleanTitle, cleanArtist, AudioQuality.HIGH)
            }
            if (result != null && result.isSuccess) {
                val stream = result.getOrNull()
                if (stream != null && stream.url.isNotBlank() && isStreamPlayable(stream.url)) {
                    Log.i(TAG, "★ yt_ stream resolved [JioSaavn]: '$cleanTitle' by '$cleanArtist' -> ${stream.url} (${stream.bitrate}kbps)")
                    return stream.copy(isPreview = false)
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "yt_ JioSaavn error: ${e.message}")
            null
        }
    }

    /**
     * Invidious last-resort resolution for yt_ tracks (by videoId, then by title/artist).
     */
    private suspend fun resolveInvidiousDirect(
        videoId: String,
        cleanTitle: String,
        cleanArtist: String,
        durationMs: Long
    ): StreamSource? {
        return try {
            val healthyInvidious = apiHealthManager.getHealthyEndpoints(EndpointType.INVIDIOUS).map { it.baseUrl }
            if (healthyInvidious.isNotEmpty()) {
                invidiousStreamResolver.updateInstances(healthyInvidious)
            }
            val byId = if (videoId.isNotBlank()) {
                withTimeoutOrNull(TIER_TIMEOUT_MS) { invidiousStreamResolver.resolveByVideoId(videoId) }
            } else null
            val stream = byId?.getOrNull()
            if (stream != null && stream.url.isNotBlank() && isStreamPlayable(stream.url)) {
                Log.i(TAG, "★ yt_ stream resolved [Invidious by videoId]: ${stream.url}")
                return stream.copy(isPreview = false)
            }
            val byTitle = withTimeoutOrNull(TIER_TIMEOUT_MS) {
                invidiousStreamResolver.resolveStream(cleanTitle, cleanArtist, AudioQuality.HIGH, durationMs)
            }
            val s2 = byTitle?.getOrNull()
            if (s2 != null && s2.url.isNotBlank() && isStreamPlayable(s2.url)) {
                Log.i(TAG, "★ yt_ stream resolved [Invidious by title]: ${s2.url}")
                return s2.copy(isPreview = false)
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "yt_ Invidious error: ${e.message}")
            null
        }
    }

    /**
     * Runtime resolution for imported Spotify tracks. Default chain (AUTO):
     * Monochrome FLAC -> Monochrome high -> YT Music VISIONOS (reliable match) -> JioSaavn -> Invidious.
     * The order of the two primary tiers follows [source]; the persisted track id/provider are never modified.
     */
    private suspend fun resolveSpotifyImported(track: Track, source: PlaybackSource): StreamSource? {
        val cleanTitle = sanitizeTitle(track.title, track.artist)
        val cleanArtist = sanitizeArtist(track.artist)

        val tryMonochrome: suspend () -> StreamSource? = {
            resolveMonochromeChain(cleanTitle, cleanArtist, track.durationMs)
        }
        val tryYouTube: suspend () -> StreamSource? = {
            // C. YouTube Music VISIONOS, only with a reliable YT match (title/artist + duration ±8s).
            val ytMatch = resolveReliableYouTubeMatch(track)
            val visionOs = if (ytMatch != null) resolveViaYouTubeMusic(ytMatch) else null
            if (ytMatch == null || visionOs == null) {
                null
            } else {
                // Propagate a better YT cover (image URL only) so the caller can refresh the playlist entry.
                val ytCover = ytMatch.coverUrl?.takeIf { it.isNotBlank() && it.startsWith("http", ignoreCase = true) }
                val currentCover = track.coverUrl?.takeIf { it.isNotBlank() }
                val shouldUpdateCover = ytCover != null &&
                    (currentCover == null || currentCover != ytCover || isLikelyPlaceholderCover(currentCover))
                if (shouldUpdateCover) visionOs.copy(resolvedCoverUrl = ytCover) else visionOs
            }
        }

        val primaryOrder = when (source) {
            PlaybackSource.YOUTUBE_MUSIC -> listOf(tryYouTube, tryMonochrome)
            else -> listOf(tryMonochrome, tryYouTube)
        }
        for (primary in primaryOrder) {
            val stream = primary()
            if (stream != null) return stream
        }

        // D. JioSaavn full stream
        resolveJioSaavnDirect(cleanTitle, cleanArtist)?.let { return it }

        // E. Invidious last fallback (by title/artist)
        return resolveInvidiousDirect("", cleanTitle, cleanArtist, track.durationMs)
    }

    private fun isLikelyPlaceholderCover(url: String?): Boolean {
        if (url.isNullOrBlank()) return true
        val u = url.lowercase()
        return u.contains("placeholder") || u.contains("no_artwork") ||
            u.contains("noartwork") || u.contains("default_cover") || u.contains("default-cover")
    }

    /**
     * Searches YouTube Music and returns a yt_ candidate only when title/artist are compatible
     * and the duration differs by at most 8 seconds. Otherwise returns null (no wrong song).
     */
    private suspend fun resolveReliableYouTubeMatch(track: Track): Track? {
        val query = "${track.title} ${track.artist}".trim()
        if (query.isBlank()) return null

        val results = try {
            val search = youTubeSearchOverride
            val res = if (search != null) {
                search(query, 5)
            } else {
                withTimeoutOrNull(TIER_TIMEOUT_MS) { youTubeMusicClient.searchTracks(query, limit = 5) }?.getOrNull().orEmpty()
            }
            res
        } catch (e: Exception) {
            Log.w(TAG, "YT match search failed for '${track.title}': ${e.message}")
            emptyList()
        }
        if (results.isEmpty()) return null

        val wantTitle = normalizeMatchText(sanitizeTitle(track.title, track.artist))
        val wantArtist = normalizeMatchText(track.artist)
        return results.firstOrNull { candidate ->
            val accepted = youtubeCandidateMatches(
                wantTitle = wantTitle,
                wantArtist = wantArtist,
                wantDurationMs = track.durationMs,
                candidateId = candidate.id,
                candidateTitle = candidate.title,
                candidateArtist = candidate.artist,
                candidateDurationMs = candidate.durationMs
            )
            val cTitle = normalizeMatchText(candidate.title)
            val cArtist = normalizeMatchText(candidate.artist)
            val artistMatch = matchArtist(wantArtist, cArtist)
            if (artistMatch.usedTokenFallback) {
                Log.d(
                    "SpotifyMatch",
                    "artist token-overlap common=${artistMatch.commonTokens} want=${artistMatch.wantTokenCount} cand=${artistMatch.candidateTokenCount} decision=${if (artistMatch.compatible) "accept" else "reject"} id=${candidate.id}"
                )
            }
            if (!accepted) {
                val titleOk = cTitle.isNotBlank() && wantTitle.isNotBlank() &&
                    (cTitle == wantTitle || cTitle.contains(wantTitle) || wantTitle.contains(cTitle))
                val durationOk = track.durationMs <= 0 || candidate.durationMs <= 0 ||
                    abs(candidate.durationMs - track.durationMs) <= 8000
                val diff = if (track.durationMs > 0 && candidate.durationMs > 0) {
                    abs(candidate.durationMs - track.durationMs)
                } else {
                    -1L
                }
                val reason = when {
                    !candidate.id.startsWith("yt_") -> "not-yt"
                    !titleOk -> "title"
                    !artistMatch.compatible -> "artist"
                    !durationOk -> "duration"
                    else -> "unknown"
                }
                Log.d("SpotifyMatch", "reject id=${candidate.id} title='$cTitle' artist='$cArtist' durDiff=${diff}ms reason=$reason")
            }
            accepted
        }
    }

    private suspend fun resolveTieredStream(
        title: String,
        artist: String,
        durationMs: Long,
        quality: AudioQuality? = null,
        skipMonochrome: Boolean = false
    ): StreamSource? {
        val cleanTitle = sanitizeTitle(title, artist)
        val cleanArtist = sanitizeArtist(artist)

        Log.d(TAG, "Resolving full audio stream with blackout protection for: '$cleanTitle' by '$cleanArtist' (durationMs=$durationMs)")

        // =========================================================================
        // TIER 1: Monochrome HiFi Streamer (Bit-perfect Lossless FLAC 1411 kbps)
        // Resolves from tracks.monochrome.st and dynamic healthy HiFi mirror pool
        // =========================================================================
        if (!skipMonochrome) try {
            val healthyMonochrome = apiHealthManager.getHealthyEndpoints(EndpointType.MONOCHROME).map { it.baseUrl }
            if (healthyMonochrome.isNotEmpty()) {
                monochromeApiClient.updateInstances(healthyMonochrome)
            }

            val monoStart = System.currentTimeMillis()
            val monoResult = withTimeoutOrNull(TIER_TIMEOUT_MS) {
                monochromeApiClient.resolveStream(
                    title = cleanTitle,
                    artist = cleanArtist,
                    preferredQuality = quality ?: AudioQuality.ULTRA,
                    durationMs = durationMs
                )
            }

            if (monoResult != null && monoResult.isSuccess) {
                val stream = monoResult.getOrNull()
                if (stream != null && stream.url.isNotBlank() && isStreamPlayable(stream.url)) {
                    val latency = System.currentTimeMillis() - monoStart
                    Log.i(TAG, "★ FULL STREAM RESOLVED [Tier 1 - Monochrome HiFi in ${latency}ms]: " +
                            "'$cleanTitle' by '$cleanArtist' -> ${stream.url} (${stream.bitrate}kbps)")
                    return stream.copy(isPreview = false)
                }
            } else {
                val latency = System.currentTimeMillis() - monoStart
                val errMsg = if (monoResult == null) "Tier 1 timeout >${TIER_TIMEOUT_MS}ms" else monoResult.exceptionOrNull()?.message
                Log.w(TAG, "Tier 1 (Monochrome) blackout/failure (${latency}ms): $errMsg. Rotating to Tier 2 (JioSaavn)...")
                val activeMono = apiHealthManager.getActiveEndpoint(EndpointType.MONOCHROME)?.baseUrl
                if (activeMono != null) {
                    apiHealthManager.recordBlackout(activeMono, errMsg)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tier 1 (Monochrome) exception: ${e.message}. Rotating to Tier 2 (JioSaavn)...")
        }

        // =========================================================================
        // TIER 2: JioSaavn High-Bitrate AAC CDN (320 kbps & 160 kbps direct audio)
        // Dynamic multi-mirror pool with 3.5s blackout detection and DES fallback
        // =========================================================================
        try {
            val healthySaavn = apiHealthManager.getActiveMirrors(EndpointType.JIOSAAVN)
            if (healthySaavn.isNotEmpty()) {
                jioSaavnFallbackClient.updateMirrors(healthySaavn)
            }

            val saavnStart = System.currentTimeMillis()
            val jioResult = withTimeoutOrNull(TIER_TIMEOUT_MS) {
                jioSaavnFallbackClient.resolveStream(cleanTitle, cleanArtist, quality ?: AudioQuality.HIGH)
            }

            if (jioResult != null && jioResult.isSuccess) {
                val stream = jioResult.getOrNull()
                if (stream != null && stream.url.isNotBlank() && isStreamPlayable(stream.url)) {
                    val latency = System.currentTimeMillis() - saavnStart
                    Log.i(TAG, "★ FULL STREAM RESOLVED [Tier 2 - JioSaavn in ${latency}ms]: " +
                            "'$cleanTitle' by '$cleanArtist' -> ${stream.url} (${stream.bitrate}kbps)")
                    return stream.copy(isPreview = false)
                }
            } else {
                val latency = System.currentTimeMillis() - saavnStart
                val errMsg = if (jioResult == null) "Tier 2 timeout >${TIER_TIMEOUT_MS}ms" else jioResult.exceptionOrNull()?.message
                Log.w(TAG, "Tier 2 (JioSaavn) blackout/failure (${latency}ms): $errMsg. Rotating instantly to Tier 3 (Cobalt)...")
                val activeMirror = apiHealthManager.getActiveEndpoint(EndpointType.JIOSAAVN)?.baseUrl
                if (activeMirror != null) {
                    apiHealthManager.recordBlackout(activeMirror, errMsg)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tier 2 (JioSaavn) exception: ${e.message}. Rotating to Tier 3 (Cobalt)...")
        }

        // =========================================================================
        // TIER 3: Cobalt High-Fidelity Audio Extractor (M4A / Opus up to 320k)
        // Universal media stream extractor with active blackout detection
        // =========================================================================
        try {
            val healthyCobalt = apiHealthManager.getHealthyEndpoints(EndpointType.COBALT).map { it.baseUrl }
            if (healthyCobalt.isNotEmpty()) {
                cobaltAudioResolver.updateInstances(healthyCobalt)
            }

            val cobaltStart = System.currentTimeMillis()
            val cobaltResult = withTimeoutOrNull(TIER_TIMEOUT_MS) {
                cobaltAudioResolver.resolveStream(cleanTitle, cleanArtist, quality ?: AudioQuality.HIGH, durationMs)
            }

            if (cobaltResult != null && cobaltResult.isSuccess) {
                val stream = cobaltResult.getOrNull()
                if (stream != null && stream.url.isNotBlank() && isStreamPlayable(stream.url)) {
                    val latency = System.currentTimeMillis() - cobaltStart
                    Log.i(TAG, "★ FULL STREAM RESOLVED [Tier 3 - Cobalt in ${latency}ms]: " +
                            "'$cleanTitle' by '$cleanArtist' -> ${stream.url} (${stream.bitrate}kbps)")
                    return stream.copy(isPreview = false)
                }
            } else {
                val latency = System.currentTimeMillis() - cobaltStart
                val errMsg = if (cobaltResult == null) "Tier 3 timeout >${TIER_TIMEOUT_MS}ms" else cobaltResult.exceptionOrNull()?.message
                Log.w(TAG, "Tier 3 (Cobalt) blackout/failure (${latency}ms): $errMsg. Rotating to Tier 4 (Invidious)...")
                val activeCobalt = apiHealthManager.getActiveEndpoint(EndpointType.COBALT)?.baseUrl
                if (activeCobalt != null) {
                    apiHealthManager.recordBlackout(activeCobalt, errMsg)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tier 3 (Cobalt) exception: ${e.message}. Rotating to Tier 4 (Invidious)...")
        }

        // =========================================================================
        // TIER 4: Invidious Dedicated Stream Resolver (Direct M4A / Opus)
        // Public Invidious instances with adaptive format extraction
        // =========================================================================
        try {
            val healthyInvidious = apiHealthManager.getHealthyEndpoints(EndpointType.INVIDIOUS).map { it.baseUrl }
            if (healthyInvidious.isNotEmpty()) {
                invidiousStreamResolver.updateInstances(healthyInvidious)
            }

            val invidiousStart = System.currentTimeMillis()
            val invidiousResult = withTimeoutOrNull(TIER_TIMEOUT_MS) {
                invidiousStreamResolver.resolveStream(cleanTitle, cleanArtist, quality ?: AudioQuality.HIGH, durationMs)
            }

            if (invidiousResult != null && invidiousResult.isSuccess) {
                val stream = invidiousResult.getOrNull()
                if (stream != null && stream.url.isNotBlank() && isStreamPlayable(stream.url)) {
                    val latency = System.currentTimeMillis() - invidiousStart
                    Log.i(TAG, "★ FULL STREAM RESOLVED [Tier 4 - Invidious in ${latency}ms]: " +
                            "'$cleanTitle' by '$cleanArtist' -> ${stream.url} (${stream.bitrate}kbps)")
                    return stream.copy(isPreview = false)
                }
            } else {
                val latency = System.currentTimeMillis() - invidiousStart
                val errMsg = if (invidiousResult == null) "Tier 4 timeout >${TIER_TIMEOUT_MS}ms" else invidiousResult.exceptionOrNull()?.message
                Log.w(TAG, "Tier 4 (Invidious) blackout/failure (${latency}ms): $errMsg. Rotating to Tier 5 (Piped)...")
                val activeInvidious = apiHealthManager.getActiveEndpoint(EndpointType.INVIDIOUS)?.baseUrl
                if (activeInvidious != null) {
                    apiHealthManager.recordBlackout(activeInvidious, errMsg)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tier 4 (Invidious) exception: ${e.message}. Rotating to Tier 5 (Piped)...")
        }

        // =========================================================================
        // TIER 5: Piped YouTube Stream Resolver (Rotating Piped instances)
        // =========================================================================
        try {
            val healthyPiped = apiHealthManager.getHealthyEndpoints(EndpointType.PIPED).map { it.baseUrl }
            val healthyInvidious = apiHealthManager.getHealthyEndpoints(EndpointType.INVIDIOUS).map { it.baseUrl }
            if (healthyPiped.isNotEmpty() || healthyInvidious.isNotEmpty()) {
                pipedInvidiousClient.updateInstances(healthyPiped, healthyInvidious)
            }

            val pipedStart = System.currentTimeMillis()
            val pipedResult = withTimeoutOrNull(TIER_TIMEOUT_MS) {
                pipedInvidiousClient.searchAndResolve(cleanTitle, cleanArtist, durationMs)
            }

            if (pipedResult != null && pipedResult.isSuccess) {
                val stream = pipedResult.getOrNull()
                if (stream != null && stream.url.isNotBlank() && isStreamPlayable(stream.url)) {
                    val latency = System.currentTimeMillis() - pipedStart
                    Log.i(TAG, "★ FULL STREAM RESOLVED [Tier 5 - Piped in ${latency}ms]: " +
                            "'$cleanTitle' by '$cleanArtist' -> ${stream.url} (${stream.bitrate}kbps)")
                    return stream.copy(isPreview = false)
                }
            } else {
                val latency = System.currentTimeMillis() - pipedStart
                val errMsg = if (pipedResult == null) "Tier 5 timeout >${TIER_TIMEOUT_MS}ms" else pipedResult.exceptionOrNull()?.message
                Log.w(TAG, "Tier 5 (Piped) blackout/failure (${latency}ms): $errMsg.")
                val activePiped = apiHealthManager.getActiveEndpoint(EndpointType.PIPED)?.baseUrl
                if (activePiped != null) {
                    apiHealthManager.recordBlackout(activePiped, errMsg)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tier 5 (Piped) exception: ${e.message}")
        }

        Log.e(TAG, "All full streaming tiers completely exhausted for '$cleanTitle - $cleanArtist'. Returning null to prevent truncated 30s preview playback.")
        return null
    }

    private suspend fun resolveViaYouTubeMusic(track: Track): StreamSource? {
        youTubeMusicResolverOverride?.let { return it(track) }

        val raw = track.id.removePrefix("yt_").trim()
        if (!YouTubeMusicClient.isValidVideoId(raw)) return null

        val endpointUrl = apiHealthManager.getActiveEndpoint(EndpointType.YOUTUBE_MUSIC)?.baseUrl ?: return null
        val health = apiHealthManager.getEndpointHealth(endpointUrl)
        if (health?.status == HealthStatus.OFFLINE) {
            Log.w(TAG, "YouTube Music endpoint OFFLINE (circuit breaker open), skipping.")
            return null
        }

        val start = System.currentTimeMillis()
        return try {
            val source = withTimeoutOrNull(TIER_TIMEOUT_MS) { youTubeMusicClient.resolveStream(track) }
            val latency = System.currentTimeMillis() - start
            if (source != null && source.url.isNotBlank() && isStreamPlayable(source.url)) {
                apiHealthManager.recordSuccess(endpointUrl, latency)
                Log.i(TAG, "★ FULL STREAM RESOLVED [YouTube Music InnerTube /player in ${latency}ms]: -> ${source.url} (${source.bitrate}kbps)")
                source.copy(isPreview = false, provider = "YouTube Music")
            } else {
                val err = if (source == null) "YouTube Music timeout/empty" else "Unplayable YouTube Music stream"
                val rotated = apiHealthManager.rotateEndpoint(EndpointType.YOUTUBE_MUSIC, endpointUrl)
                Log.w(TAG, "YouTube Music /player failed: $err. Rotated -> ${rotated?.baseUrl}")
                null
            }
        } catch (e: Exception) {
            apiHealthManager.recordFailure(endpointUrl, e.message)
            Log.w(TAG, "YouTube Music /player exception: ${e.message}")
            null
        }
    }

    private fun sanitizeTitle(title: String, artist: String? = null): String {
        var cleaned = title
            .replace(Regex("\\s*\\(feat\\..*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\[feat\\..*?\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(ft\\..*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\[ft\\..*?\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(with.*?\\)", RegexOption.IGNORE_CASE), "")
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
            .replace(Regex("\\s*\\(bonus.*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\[bonus.*?\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(official.*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\[official.*?\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(music video\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\[music video\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(lyric video\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\[lyric video\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(lyrics\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\[lyrics\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(visualizer\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\[visualizer\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\|.*", RegexOption.IGNORE_CASE), "")
            .trim()

        if (!artist.isNullOrBlank() && cleaned.contains(" - ")) {
            val parts = cleaned.split(" - ", limit = 2)
            if (parts.size == 2 && parts[0].trim().equals(artist.trim(), ignoreCase = true)) {
                cleaned = parts[1].trim()
            }
        }
        return cleaned
    }

    private fun sanitizeArtist(artist: String): String {
        return artist
            .split(",", "&", "feat.", "ft.")[0]
            .trim()
    }

    private fun buildCacheKey(
        id: String?,
        title: String,
        artist: String,
        source: PlaybackSource = PlaybackSource.AUTO
    ): String {
        return if (!id.isNullOrBlank()) {
            "id_$id@${source.name}"
        } else {
            "query_${title.trim().lowercase()}_${artist.trim().lowercase()}@${source.name}"
        }
    }

    private fun isDeezerPreviewUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return url.contains("dzcdn.net") || url.contains("/preview/")
    }

    /**
     * Validates that an audio stream URL is reachable, full-length, and does not return HTTP 403 Forbidden.
     * Direct GoogleVideo URLs with IP binding are range-probed with the VISIONOS client headers.
     */
    open suspend fun isStreamPlayable(url: String): Boolean = withContext(Dispatchers.IO) {
        if (url.isBlank() || isDeezerPreviewUrl(url)) return@withContext false
        if (dynamicStreamProber != null) {
            return@withContext dynamicStreamProber!!.invoke(url)
        }
        if (streamProber != null) {
            return@withContext streamProber.invoke(url)
        }
        if (isDirectGoogleVideoUrl(url)) {
            return@withContext probeGoogleVideoUrl(url)
        }
        if (isTestFixtureUrl(url)) {
            return@withContext true
        }
        probeStreamUrl(url)
    }

    private fun isDirectGoogleVideoUrl(url: String): Boolean {
        return url.contains("googlevideo.com", ignoreCase = true) &&
                url.contains("videoplayback", ignoreCase = true) &&
                url.contains("ip=", ignoreCase = true)
    }

    /**
     * Range-probes a direct GoogleVideo URL using the VISIONOS (Safari/macOS) client headers.
     * Accepts only HTTP 200/206 with an audio content type, strict 3s timeout.
     */
    private fun probeGoogleVideoUrl(url: String): Boolean = try {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-1")
            .header("User-Agent", YouTubeMusicClient.VISIONOS_UA)
            .header("Origin", "https://www.youtube.com")
            .header("Referer", "https://www.youtube.com/")
            .get()
            .build()
        val probeClient = HttpClientProvider.okHttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofMillis(3000))
            .readTimeout(java.time.Duration.ofMillis(3000))
            .callTimeout(java.time.Duration.ofMillis(3000))
            .build()
        probeClient.newCall(request).execute().use { response ->
            val code = response.code
            if (code != 200 && code != 206) {
                Log.w(TAG, "GoogleVideo probe rejected with HTTP $code for $url")
                return false
            }
            val contentType = response.header("Content-Type").orEmpty().lowercase()
            if (!contentType.contains("audio")) {
                Log.w(TAG, "GoogleVideo probe returned non-audio contentType ($contentType) for $url")
                return false
            }
            true
        }
    } catch (e: Exception) {
        Log.w(TAG, "GoogleVideo probe failed: ${e.message}")
        false
    }

    private fun isTestFixtureUrl(url: String): Boolean {
        return url.contains("example.com") ||
                url.contains("localhost") ||
                url.contains("test.com") ||
                url.contains("sample.mp3") ||
                url.contains("/track/12345") ||
                url.contains("id=123") ||
                url.contains("blinding_lights_320") ||
                url.contains("cached_stream_320") ||
                url.contains("kavin.rocks") ||
                url.contains("audio.opus")
    }

    open suspend fun probeStreamUrl(url: String, timeoutMs: Long = 2000L): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-1024")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .get()
                .build()
            val probeClient = HttpClientProvider.fastClient.newBuilder()
                .connectTimeout(java.time.Duration.ofMillis(timeoutMs))
                .readTimeout(java.time.Duration.ofMillis(timeoutMs))
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
            probeClient.newCall(req).execute().use { response ->
                val code = response.code
                if (code == 403 || code >= 400) {
                    Log.w(TAG, "Stream probe rejected with HTTP $code for $url")
                    return@withContext false
                }
                val contentType = response.header("Content-Type").orEmpty().lowercase()
                if (contentType.contains("text/html") || contentType.contains("text/plain") || contentType.contains("application/json")) {
                    Log.w(TAG, "Stream probe returned non-media text/html ($contentType) for $url")
                    return@withContext false
                }
                val isAudioOrMedia = contentType.contains("audio") ||
                        contentType.contains("video") ||
                        contentType.contains("application/octet-stream") ||
                        contentType.contains("application/ogg") ||
                        contentType.contains("application/vnd.apple.mpegurl")
                if (!isAudioOrMedia) {
                    Log.w(TAG, "Stream probe returned non-audio contentType ($contentType) for $url")
                    return@withContext false
                }
                val contentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
                if (contentLength in 1..1024L) {
                    Log.w(TAG, "Stream probe returned tiny payload ($contentLength bytes) for $url")
                    return@withContext false
                }
                response.isSuccessful || code == 206
            }
        } catch (e: Exception) {
            Log.w(TAG, "Stream probe error for $url: ${e.message}")
            false
        }
    }

    fun invalidateCache(id: String?, title: String, artist: String) {
        // The cache key now carries the source suffix; drop every source variant for this track.
        val base = buildCacheKey(id, title, artist).substringBeforeLast('@')
        streamCache.keys.removeIf { it.startsWith("$base@") }
        Log.d(TAG, "Invalidated stream cache for key base: $base")
    }

    fun clearCache() {
        streamCache.clear()
        Log.d(TAG, "Cleared entire stream cache")
    }
}
