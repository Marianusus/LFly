package org.sonora.android.api

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SpotifyDataExportImporterTest {

    private val importer = SpotifyDataExportImporter()

    private val yourLibraryJson = """
        {
          "tracks": [
            {"artist":"The Weeknd","album":"After Hours","track":"Blinding Lights","uri":"spotify:track:0VjIjW4GlUZAMYd2vXMi3b"},
            {"artist":"Daft Punk","album":"Random Access Memories","track":"Get Lucky","uri":"spotify:track:2Foc5Q5jnhlX3t9GyD4w9t"}
          ],
          "albums": [],
          "artists": [],
          "shows": [],
          "bannedTracks": []
        }
    """.trimIndent()

    private val playlistsJson = """
        {
          "playlists": [
            {
              "name": "Road Trip",
              "items": [
                {"track":{"trackName":"Africa","artistName":"TOTO","albumName":"Toto IV","trackUri":"spotify:track:abc"}},
                {"track":{"trackName":"Take On Me","artistName":"a-ha","albumName":"Hunting High and Low","trackUri":"spotify:track:def"}}
              ]
            },
            {
              "name": "Empty",
              "items": []
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesYourLibraryAsLikedSongs() = runBlocking {
        val collections = importer.parseCollections(yourLibraryJson.byteInputStream())
        assertEquals(1, collections.size)
        val liked = collections.first()
        assertEquals(SpotifyDataExportImporter.LIKED_PLAYLIST_NAME, liked.name)
        assertEquals(2, liked.tracks.size)
        assertEquals("Blinding Lights", liked.tracks[0].title)
        assertEquals("The Weeknd", liked.tracks[0].artist)
        assertEquals("After Hours", liked.tracks[0].album)
        assertEquals("0VjIjW4GlUZAMYd2vXMi3b", liked.tracks[0].id)
    }

    @Test
    fun parsesPlaylistFileAndSkipsEmptyPlaylists() = runBlocking {
        val collections = importer.parseCollections(playlistsJson.byteInputStream())
        assertEquals(1, collections.size)
        val road = collections.first()
        assertEquals("Road Trip", road.name)
        assertEquals(2, road.tracks.size)
        assertEquals("Africa", road.tracks[0].title)
        assertEquals("TOTO", road.tracks[0].artist)
        assertEquals("abc", road.tracks[0].id)
    }

    @Test
    fun parsesZipWithLikedSongsAndPlaylists() = runBlocking {
        val zipBytes = buildZip(
            "YourLibrary.json" to yourLibraryJson,
            "Playlist1.json" to playlistsJson
        )
        val collections = importer.parseCollections(ByteArrayInputStream(zipBytes))
        assertEquals(2, collections.size)
        assertTrue(collections.any { it.name == SpotifyDataExportImporter.LIKED_PLAYLIST_NAME })
        assertTrue(collections.any { it.name == "Road Trip" })
        assertEquals(4, collections.sumOf { it.tracks.size })
    }

    @Test
    fun buildTrackPopulatesArtistCredits() {
        val raw = SpotifyRawTrack(
            id = "track123",
            title = "Bohemian Rhapsody",
            artist = "Queen"
        )
        val track = importer.buildTrack(raw)
        assertEquals(listOf("Queen"), track.artists?.map { it.name })
    }

    @Test
    fun ignoresUnrelatedJsonDocuments() = runBlocking {
        val collections = importer.parseCollections("""{"foo":"bar"}""".byteInputStream())
        assertTrue(collections.isEmpty())
    }

    private fun buildZip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
