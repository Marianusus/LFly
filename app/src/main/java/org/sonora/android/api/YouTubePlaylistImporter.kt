package org.sonora.android.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.sonora.android.data.MusicRepository
import org.sonora.android.data.Playlist
import java.io.IOException

/**
 * One-click YouTube Music playlist importer.
 *
 * Resolves a public (or unlisted) YouTube Music playlist link through the native InnerTube
 * client — no login and no API key required — and saves it as a native Sonora [Playlist].
 *
 * Unlike the Spotify importer, every entry is already a playable `yt_<videoId>` track with full
 * metadata, so there is **no fuzzy matching and no 100-track cap**: playlists larger than the
 * first browse page are completed through continuation tokens.
 */
open class YouTubePlaylistImporter(
    private val client: YouTubeMusicClient = YouTubeMusicClient(),
    private val repository: MusicRepository? = null
) {

    companion object {
        private const val TAG = "YouTubeImporter"

        private val LIST_PARAM_REGEX = Regex("""[?&]list=([A-Za-z0-9_-]+)""")
        private val RAW_ID_REGEX = Regex("""^[A-Za-z0-9_-]{11,80}$""")
    }

    /**
     * Extracts a YouTube Music playlist id from a URL (`…/playlist?list=…`, `…watch?v=…&list=…`),
     * a raw playlist id (`PL…`, `OLAK5uy…`, `VL…`) or returns `null` when nothing matches.
     */
    fun extractPlaylistId(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return null

        val direct = !trimmed.contains("http", ignoreCase = true) &&
            !trimmed.contains("youtu", ignoreCase = true) &&
            !trimmed.contains("music.", ignoreCase = true)
        if (direct && RAW_ID_REGEX.matches(trimmed)) return normalizeId(trimmed)

        LIST_PARAM_REGEX.find(trimmed)?.groupValues?.getOrNull(1)?.let { raw ->
            return normalizeId(raw)
        }
        return null
    }

    private fun normalizeId(id: String): String = id.trim().removePrefix("VL").ifBlank { id.trim() }

    /**
     * Fetches the full tracklist (paged) without saving. `open` so it can be faked in tests.
     */
    open suspend fun fetchPlaylistFull(
        playlistId: String,
        onProgress: ((loaded: Int) -> Unit)? = null
    ): Result<YouTubePlaylistDetail> = client.getYouTubePlaylist(playlistId, onProgress)

    /**
     * Full pipeline: resolves the link, fetches every track and persists a native [Playlist].
     */
    suspend fun importAndSavePlaylist(
        urlOrId: String,
        repository: MusicRepository = this.repository ?: error("MusicRepository must be provided"),
        onProgress: (suspend (current: Int, total: Int, trackTitle: String) -> Unit)? = null
    ): Result<Playlist> = withContext(Dispatchers.IO) {
        try {
            val playlistId = extractPlaylistId(urlOrId)
                ?: return@withContext Result.failure(
                    IllegalArgumentException("Link playlist YouTube Music non valido: '$urlOrId'")
                )

            val detail = fetchPlaylistFull(playlistId).getOrElse {
                return@withContext Result.failure(it)
            }

            val tracks = detail.tracks
            if (tracks.isEmpty()) {
                return@withContext Result.failure(
                    IOException("Nessun brano trovato. Verifica che la playlist sia pubblica o non elencata.")
                )
            }

            val total = tracks.size
            tracks.forEachIndexed { index, track ->
                onProgress?.invoke(index + 1, total, track.title)
                delay(2)
            }

            val saved = repository.createPlaylistWithTracks(
                name = detail.playlist.title,
                tracks = tracks,
                description = detail.playlist.author,
                coverUrl = detail.playlist.thumbnailUrl
            )

            Log.i(TAG, "Imported YouTube Music playlist '${saved.name}' (${saved.tracks.size} tracks)")
            Result.success(saved)
        } catch (e: Exception) {
            Log.e(TAG, "YouTube Music import error: ${e.message}", e)
            Result.failure(e)
        }
    }
}
