package org.sonora.android.data

import org.sonora.android.api.Track

/**
 * Stable, idempotent migration for imported (Spotify) playlist entries.
 *
 * Imported entries must never persist runtime stream URLs (Googlevideo/Invidious/Cobalt/
 * JioSaavn/Monochrome/Spotify previews). Migration strips only those URLs and normalizes the
 * stable id/provider, keeping metadata and the playlist itself intact.
 */
internal fun isImportedTrack(track: Track): Boolean {
    return track.id.startsWith("spotify_") ||
        track.id.startsWith("saavn_") ||
        track.provider.equals("Spotify", ignoreCase = true)
}

internal fun stableImportedTrackHash(title: String, artist: String, durationMs: Long): String {
    val key = "${title.lowercase().trim()}|${artist.lowercase().trim()}|$durationMs"
    return Integer.toHexString(key.hashCode())
}

internal fun migrateImportedTrack(track: Track): Track {
    if (!isImportedTrack(track)) return track
    val stableId = if (track.id.startsWith("spotify_")) {
        track.id
    } else {
        "spotify_${stableImportedTrackHash(track.title, track.artist, track.durationMs)}"
    }
    if (stableId == track.id && track.streamUrl == null && track.provider.equals("Spotify", ignoreCase = true)) {
        return track
    }
    return track.copy(id = stableId, streamUrl = null, provider = "Spotify")
}

internal fun migratePlaylist(playlist: Playlist): Playlist {
    val migratedTracks = playlist.tracks.map { migrateImportedTrack(it) }
    return playlist.copy(tracks = migratedTracks, schemaVersion = PLAYLIST_SCHEMA_VERSION)
}
