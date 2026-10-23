package org.sonora.android.playback

import kotlinx.coroutines.CancellationException
import org.sonora.android.api.Track
import org.sonora.android.api.YouTubeMusicClient
import org.sonora.android.api.lyrics.LyricsBridge
import org.sonora.android.api.lyrics.LyricsRepository
import org.sonora.android.api.lyrics.LyricsText
import org.sonora.android.api.lyrics.sources.LyricsSource

/**
 * Network-only lyrics resolution for offline downloads.
 *
 * Mirrors the lookup the player performs (same normalization, video id and source order)
 * and returns a persistable [OfflineLyrics]. It never touches [DownloadLyricsStore]: the
 * caller decides whether and where to persist the result.
 *
 * Returns `null` when the lookup failed (network/parse error), so no negative marker is
 * written and a later attempt can still succeed. Cancellation is always rethrown.
 */
object DownloadLyricsFetcher {

    suspend fun fetch(
        track: Track,
        sources: Set<LyricsSource>,
        order: List<LyricsSource>,
        prioritizeSyllableSync: Boolean,
        apiKey: String?,
    ): OfflineLyrics? = try {
        val videoId = track.id.takeIf { YouTubeMusicClient.isValidVideoId(it) }
        val (title, artist) = LyricsText.splitArtistTitle(track.title, track.artist)
        val result = LyricsRepository.lookup(
            title = title,
            artist = artist,
            album = track.album,
            durationMs = track.durationMs,
            videoId = videoId,
            sources = sources,
            order = order,
            prioritizeSyllableSync = prioritizeSyllableSync,
            apiKey = apiKey,
        )
        if (result == null) {
            OfflineLyrics(lyrics = null, source = null, notFound = true)
        } else {
            OfflineLyrics(
                lyrics = LyricsBridge.toDomain(result.hit.lyrics, track.id),
                source = result.source.name,
                notFound = false,
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
