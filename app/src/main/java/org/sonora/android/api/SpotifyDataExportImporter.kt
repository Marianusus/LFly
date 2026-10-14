package org.sonora.android.api

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.sonora.android.data.MusicRepository
import org.sonora.android.data.Playlist
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * A collection of raw tracks extracted from a Spotify data export (one playlist, or the
 * user's "Liked Songs").
 */
data class SpotifyExportCollection(
    val name: String,
    val tracks: List<SpotifyRawTrack>,
    val description: String? = null
)

/**
 * Importer for the **official Spotify data export** ("Account data").
 *
 * Unlike the public embed (which is capped at 100 tracks), the export ZIP contains the complete
 * tracklist of every playlist the user owns **plus** the full "Liked Songs" library, with no
 * authentication and no API key required.
 *
 * Recognised files inside the ZIP (or a single JSON document):
 *  - `YourLibrary.json` -> the saved/liked songs (imported as "Brani che ti piacciono").
 *  - `Playlist*.json` / `Playlists.json` -> one native Sonora playlist per source playlist.
 *
 * The export has no album art or durations, so generated [Track]s carry title/artist/album only;
 * the stream (and its real duration) is resolved lazily at playback time, like every other track.
 */
open class SpotifyDataExportImporter(
    private val repository: MusicRepository? = null
) {

    companion object {
        private const val TAG = "SpotifyExport"
        const val LIKED_PLAYLIST_NAME = "Brani che ti piacciono"
    }

    /**
     * Full pipeline: parses the export and persists every discovered collection as a native
     * [Playlist], reporting aggregate progress across all tracks.
     */
    suspend fun importFromExport(
        input: InputStream,
        repository: MusicRepository = this.repository ?: error("MusicRepository must be provided"),
        onProgress: (suspend (current: Int, total: Int, trackTitle: String) -> Unit)? = null
    ): Result<List<Playlist>> = withContext(Dispatchers.IO) {
        try {
            val collections = parseCollections(input)
            if (collections.isEmpty()) {
                return@withContext Result.failure(
                    IOException("Nessun brano trovato. Assicurati di aver selezionato il file ZIP dell'esportazione dati di Spotify (Account data).")
                )
            }

            val total = collections.sumOf { it.tracks.size }
            val saved = ArrayList<Playlist>(collections.size)
            var index = 0
            for (collection in collections) {
                val resolved = ArrayList<Track>(collection.tracks.size)
                for (raw in collection.tracks) {
                    index++
                    onProgress?.invoke(index, total, raw.title)
                    resolved.add(buildTrack(raw))
                    delay(2)
                }
                saved.add(
                    repository.createPlaylistWithTracks(
                        name = collection.name,
                        tracks = resolved,
                        description = collection.description,
                        coverUrl = null
                    )
                )
            }

            Log.i(TAG, "Imported ${saved.size} playlist(s) / $total tracks from Spotify export")
            Result.success(saved)
        } catch (e: Exception) {
            Log.e(TAG, "Spotify export import error: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Parses a ZIP export or a standalone JSON document into raw collections without saving.
     */
    suspend fun parseCollections(input: InputStream): List<SpotifyExportCollection> = withContext(Dispatchers.IO) {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input, 64 * 1024)
        try {
            if (isZip(buffered)) {
                parseZip(buffered)
            } else {
                val text = buffered.readBytes().toString(Charsets.UTF_8)
                parseJsonDocument("input.json", text)
            }
        } finally {
            runCatching { buffered.close() }
        }
    }

    // --- Parsing helpers ---

    private fun isZip(input: BufferedInputStream): Boolean {
        input.mark(4)
        val b0 = input.read()
        val b1 = input.read()
        input.reset()
        return b0 == 0x50 && b1 == 0x4B
    }

    private fun parseZip(input: InputStream): List<SpotifyExportCollection> {
        val collections = mutableListOf<SpotifyExportCollection>()
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val baseName = entry.name.substringAfterLast('/').substringAfterLast('\\')
                val lower = baseName.lowercase()
                if (!entry.isDirectory && lower.endsWith(".json") &&
                    (lower == "yourlibrary.json" || lower.startsWith("playlist"))
                ) {
                    val text = runCatching { zip.readBytes().toString(Charsets.UTF_8) }.getOrDefault("")
                    collections.addAll(parseJsonDocument(baseName, text))
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return collections
    }

    private fun parseJsonDocument(fileName: String, text: String): List<SpotifyExportCollection> {
        if (text.isBlank()) return emptyList()
        return try {
            val root = JsonParser.parseString(text)
            if (!root.isJsonObject) return emptyList()
            val obj = root.asJsonObject
            val out = mutableListOf<SpotifyExportCollection>()

            // YourLibrary.json -> liked songs: { "tracks": [ {track, artist, album, uri} ] }
            val libraryTracks = obj.getAsJsonArray("tracks")
            if (libraryTracks != null && hasLibraryTrackShape(libraryTracks)) {
                val tracks = libraryTracks.mapNotNull { parseLibraryTrack(it) }
                if (tracks.isNotEmpty()) {
                    out.add(SpotifyExportCollection(LIKED_PLAYLIST_NAME, tracks))
                }
            }

            // Playlist*.json -> { "playlists": [ { name, items: [ { track: {...} } ] } ] }
            val playlists = obj.getAsJsonArray("playlists")
            if (playlists != null) {
                for (element in playlists) {
                    if (!element.isJsonObject) continue
                    val playlistObj = element.asJsonObject
                    val name = playlistObj.get("name")?.asString?.takeIf { it.isNotBlank() } ?: continue
                    val items = playlistObj.getAsJsonArray("items") ?: continue
                    val tracks = items.mapNotNull { parsePlaylistItem(it) }
                    if (tracks.isNotEmpty()) {
                        out.add(SpotifyExportCollection(name, tracks))
                    }
                }
            }

            out
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse '$fileName': ${e.message}")
            emptyList()
        }
    }

    private fun hasLibraryTrackShape(array: JsonArray): Boolean {
        val first = array.firstOrNull { it.isJsonObject }?.asJsonObject ?: return false
        val trackField = first.get("track") ?: return false
        return trackField.isJsonPrimitive
    }

    private fun parseLibraryTrack(element: JsonElement): SpotifyRawTrack? {
        if (!element.isJsonObject) return null
        val obj = element.asJsonObject
        val title = obj.get("track")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
            ?: return null
        val artist = obj.get("artist")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val album = obj.get("album")?.takeIf { it.isJsonPrimitive }?.asString
        val uri = obj.get("uri")?.takeIf { it.isJsonPrimitive }?.asString
        return SpotifyRawTrack(
            id = uri?.substringAfterLast(':')?.ifBlank { null } ?: fallbackId(title, artist),
            title = title,
            artist = artist,
            album = album
        )
    }

    private fun parsePlaylistItem(element: JsonElement): SpotifyRawTrack? {
        if (!element.isJsonObject) return null
        val obj = element.asJsonObject
        val trackObj = obj.getAsJsonObject("track") ?: obj
        val title = trackObj.get("trackName")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
            ?: trackObj.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
            ?: return null
        val artist = trackObj.get("artistName")?.takeIf { it.isJsonPrimitive }?.asString
            ?: trackObj.get("artist")?.takeIf { it.isJsonPrimitive }?.asString
            ?: ""
        val album = trackObj.get("albumName")?.takeIf { it.isJsonPrimitive }?.asString
            ?: trackObj.get("album")?.takeIf { it.isJsonPrimitive }?.asString
        val uri = trackObj.get("trackUri")?.takeIf { it.isJsonPrimitive }?.asString
            ?: trackObj.get("uri")?.takeIf { it.isJsonPrimitive }?.asString
        return SpotifyRawTrack(
            id = uri?.substringAfterLast(':')?.ifBlank { null } ?: fallbackId(title, artist),
            title = title,
            artist = artist,
            album = album
        )
    }

    private fun fallbackId(title: String, artist: String): String =
        Integer.toHexString("${title.lowercase().trim()}|${artist.lowercase().trim()}".hashCode())

    private fun buildTrack(raw: SpotifyRawTrack): Track {
        val rawId = raw.id.substringAfterLast(':').trim()
        val stableId = if (rawId.isNotBlank()) "spotify_$rawId" else "spotify_${fallbackId(raw.title, raw.artist)}"
        return Track(
            id = stableId,
            title = raw.title,
            artist = raw.artist,
            album = raw.album,
            durationMs = raw.durationMs,
            coverUrl = raw.coverUrl,
            streamUrl = null,
            provider = "Spotify"
        )
    }
}
