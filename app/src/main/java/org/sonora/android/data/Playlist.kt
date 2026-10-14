package org.sonora.android.data

import org.sonora.android.api.Track
import java.util.UUID

/** Current persisted playlist schema version. Older playlists are migrated on load. */
const val PLAYLIST_SCHEMA_VERSION = 2

/**
 * Custom playlist created and managed locally by the user.
 */
data class Playlist(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String? = null,
    val coverUrl: String? = null,
    val tracks: List<Track> = emptyList(),
    val schemaVersion: Int = PLAYLIST_SCHEMA_VERSION,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    /**
     * Backward-compatible constructor for positional calls without description or coverUrl.
     */
    constructor(
        id: String,
        name: String,
        tracks: List<Track>,
        createdAt: Long,
        updatedAt: Long
    ) : this(
        id = id,
        name = name,
        description = null,
        coverUrl = null,
        tracks = tracks,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    val trackCount: Int get() = tracks.size

    val totalDurationMs: Long get() = tracks.sumOf { it.durationMs }    /**
     * Resolved artwork URL: returns the custom playlist cover URL if present,
     * otherwise falls back to the cover URL of the first track in the playlist.
     */
    val displayCoverUrl: String?
        get() = coverUrl?.takeIf { it.isNotBlank() }
            ?: tracks.firstOrNull { !it.coverUrl.isNullOrBlank() }?.coverUrl

    val formattedDuration: String
        get() {
            val totalSeconds = totalDurationMs / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            return when {
                hours > 0 -> String.format("%d hr %d min", hours, minutes)
                minutes > 0 -> String.format("%d min", minutes)
                totalSeconds > 0 -> String.format("%d sec", totalSeconds)
                else -> "0 min"
            }
        }
}
