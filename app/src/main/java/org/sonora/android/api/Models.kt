package org.sonora.android.api

import java.util.Locale

/**
 * Formats a track duration; unknown duration (<= 0) renders as an em dash instead of a fake "0:00"/"3:00".
 */
fun formatDurationOrUnknown(durationMs: Long): String {
    if (durationMs <= 0L) return "—"
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}

/**
 * Core domain representation of an audio track.
 */
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val coverUrl: String?,
    val streamUrl: String? = null,
    val provider: String = "Unknown",
    val artistId: String? = null,
    val bpm: Float? = null,
    val gain: Float? = null,
    val rank: Int? = null,
    /** Popularity signal (YouTube/Music plays or video views) when the source exposes it. */
    val streams: Long? = null,
    /** True when this track originates from a YouTube *video* (not an official audio/song). */
    val isVideo: Boolean = false,
    /** Release year when the provider or local tags expose it. */
    val releaseYear: Int? = null,
    /** Album browse id when the row exposed one (used to resolve release metadata). */
    val albumId: String? = null,
    /** Every credited artist with its provider ids; null when the source only had a string. */
    val artists: List<ArtistCredit>? = null,
) {
    val durationSeconds: Long get() = durationMs / 1000
    val artworkUrl: String? get() = coverUrl

    val formattedDuration: String get() = formatDurationOrUnknown(durationMs)
}

/**
 * Extra facts about a track, resolved on demand from the provider. Only the
 * fields a source actually supplied are non-null; callers merge them over the
 * track they already have instead of replacing it, so the stream URL and queue
 * identity are never touched.
 */
data class TrackMetadata(
    val title: String? = null,
    val artist: String? = null,
    val artistId: String? = null,
    val album: String? = null,
    val albumId: String? = null,
    val durationMs: Long? = null,
    val releaseYear: Int? = null,
    val releaseDate: String? = null,
    val streams: Long? = null,
    val artworkUrl: String? = null,
    val artists: List<ArtistCredit>? = null,
)

/** Overlays resolved metadata without discarding playback identity or stream state. */
fun Track.withMetadata(metadata: TrackMetadata?): Track {
    metadata ?: return this
    return copy(
        title = metadata.title?.takeIf { it.isNotBlank() } ?: title,
        artist = metadata.artist?.takeIf { it.isNotBlank() } ?: artist,
        artistId = metadata.artistId ?: artistId,
        album = metadata.album?.takeIf { it.isNotBlank() } ?: album,
        albumId = metadata.albumId ?: albumId,
        durationMs = metadata.durationMs?.takeIf { it > 0L } ?: durationMs,
        releaseYear = metadata.releaseYear ?: releaseYear,
        streams = metadata.streams ?: streams,
        coverUrl = metadata.artworkUrl?.takeIf { it.isNotBlank() } ?: coverUrl,
        artists = ArtistCredits.merge(
            existing = artists,
            incoming = metadata.artists,
            legacyName = metadata.artist?.takeIf { it.isNotBlank() } ?: artist,
            legacyArtistId = metadata.artistId ?: artistId,
        ),
    )
}

/**
 * Domain representation of an album.
 */
data class Album(
    val id: String,
    val title: String,
    val artist: String,
    val coverUrl: String?,
    val trackCount: Int = 0,
    val releaseDate: String? = null,
    val genres: List<String> = emptyList(),
    val label: String? = null
) {
    val artworkUrl: String? get() = coverUrl
}

/**
 * Domain representation of a music artist.
 */
data class Artist(
    val id: String,
    val name: String,
    val pictureUrl: String?,
    val nbAlbum: Int = 0,
    val nbFan: Int = 0,
    val radio: Boolean = false
) {
    val artworkUrl: String? get() = pictureUrl
}

/**
 * A single timed token inside a lyric line (word-level timing).
 * Populated from Enhanced LRC word tags when available, or estimated from line timings.
 */
data class WordTiming(
    val text: String,
    val startMs: Long,
    val endMs: Long
)

/** How the current lyric line is highlighted while playing. */
enum class HighlightMode {
    /** Word-by-word when word timings are usable, otherwise fall back to line-level. */
    AUTO,
    /** Always word-by-word (estimated timings when the source is line-only). */
    WORD,
    /** Highlight the whole active line only. */
    LINE;

    companion object {
        fun fromString(raw: String?): HighlightMode = when (raw?.trim()?.lowercase()) {
            "word", "parola", "parole" -> WORD
            "line", "riga", "righe" -> LINE
            else -> AUTO
        }
    }
}

/**
 * Single line of time-synchronized lyrics.
 */
data class LyricLine(
    val timeMs: Long,
    val text: String,
    val translation: String? = null,
    // Nullable on purpose: entries serialized before this field existed deserialize as null (Gson).
    val wordTimings: List<WordTiming>? = null,
    /** Answering/background vocal timed separately from the lead line. */
    val background: String? = null,
)

/**
 * Lyrics payload containing raw text, LRC-synchronized text, and parsed line tokens.
 */
data class Lyrics(
    val id: String,
    val plainLyrics: String?,
    val syncedLyrics: String?,
    val lines: List<LyricLine> = emptyList()
) {
    val hasSynced: Boolean get() = lines.isNotEmpty()
}

/**
 * Audio streaming source resolved by a provider.
 */
data class StreamSource(
    val url: String,
    val format: String,
    val quality: AudioQuality,
    val provider: String,
    val bitrate: Int? = null,
    val expiresAt: Long? = null,
    val isPreview: Boolean = false,
    val resolvedCoverUrl: String? = null
)

/**
 * Perceived audio quality tier.
 */
enum class AudioQuality {
    LOW,     // ~64-96 kbps
    MEDIUM,  // ~128-160 kbps
    HIGH,    // ~256-320 kbps
    ULTRA    // Lossless / FLAC
}

/**
 * Endpoint category for upstream microservices and mirror instances.
 */
enum class EndpointType {
    DEEZER,
    PIPED,
    INVIDIOUS,
    LRCLIB,
    JIOSAAVN,
    COBALT,
    MONOCHROME,
    YOUTUBE_MUSIC
}

/**
 * Real-time operational health state of an API endpoint.
 */
enum class HealthStatus {
    ONLINE,
    DEGRADED,
    OFFLINE
}

/**
 * Descriptive registration entry for an API endpoint instance.
 */
data class ApiEndpoint(
    val name: String,
    val baseUrl: String,
    val type: EndpointType,
    val isCustom: Boolean = false,
    val enabled: Boolean = true
)

/**
 * Real-time health metrics for an API provider or instance.
 */
data class ProviderHealth(
    val providerName: String,
    val endpointUrl: String,
    val latencyMs: Long,
    val status: HealthStatus,
    val lastChecked: Long,
    val consecutiveFailures: Int = 0,
    val message: String? = null
)
