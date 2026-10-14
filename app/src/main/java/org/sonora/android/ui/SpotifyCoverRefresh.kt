package org.sonora.android.ui

import org.sonora.android.api.Track
import org.sonora.android.api.normalizeMatchText
import org.sonora.android.api.youtubeCandidateMatches

/**
 * Pure helpers for the automatic refresh of imported (Spotify) playlist covers.
 * They never resolve audio streams and never touch the player; matching reuses the exact
 * same reliability predicate as stream resolution ([youtubeCandidateMatches]).
 */
internal const val SPOTIFY_COVER_REFRESH_TAG = "SpotifyCoverRefresh"
internal const val SPOTIFY_COVER_MAX_TRACKS = 25
internal const val SPOTIFY_COVER_SEARCH_TIMEOUT_MS = 5000L
internal const val SPOTIFY_COVER_BATCH_DELAY_MS = 200L
internal const val SPOTIFY_COVER_CONCURRENCY = 2

internal enum class CoverRefreshOutcome { UPDATED, SKIPPED, FAILED }

private val KNOWN_VALID_COVER_HOSTS = listOf("yt3.googleusercontent.com", "i.scdn.co")

internal fun isImportedSpotifyTrack(track: Track): Boolean =
    track.id.startsWith("spotify_") && track.provider.equals("Spotify", ignoreCase = true)

internal fun isPlaceholderCover(url: String?): Boolean {
    if (url.isNullOrBlank()) return true
    val u = url.lowercase()
    return u.contains("placeholder") || u.contains("no_artwork") ||
        u.contains("noartwork") || u.contains("default_cover") || u.contains("default-cover")
}

internal fun isHttpCover(url: String?): Boolean {
    val u = url?.trim() ?: return false
    return u.startsWith("http://", ignoreCase = true) || u.startsWith("https://", ignoreCase = true)
}

/**
 * Candidate for refresh: imported Spotify track whose cover is absent, placeholder, or a clearly
 * non-valid/legacy CDN. Valid YT covers and valid Spotify covers (i.scdn.co) are preserved.
 */
internal fun isCoverRefreshCandidate(track: Track): Boolean {
    if (!isImportedSpotifyTrack(track)) return false
    val cover = track.coverUrl?.trim()
    if (cover.isNullOrBlank()) return true
    if (isPlaceholderCover(cover)) return true
    val lower = cover.lowercase()
    return KNOWN_VALID_COVER_HOSTS.none { lower.contains(it) }
}

/**
 * Returns the HTTP(S) cover of the first reliable YT candidate (same title/artist/duration criteria
 * as stream resolution), or null when there is no reliable match or no usable cover.
 */
internal fun pickReliableYtCover(track: Track, candidates: List<Track>): String? {
    val wantTitle = normalizeMatchText(track.title)
    val wantArtist = normalizeMatchText(track.artist)
    val match = candidates.firstOrNull { candidate ->
        youtubeCandidateMatches(
            wantTitle = wantTitle,
            wantArtist = wantArtist,
            wantDurationMs = track.durationMs,
            candidateId = candidate.id,
            candidateTitle = candidate.title,
            candidateArtist = candidate.artist,
            candidateDurationMs = candidate.durationMs
        )
    } ?: return null
    val cover = match.coverUrl?.trim()
    return if (isHttpCover(cover)) cover else null
}
