package org.sonora.android.api

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyPlaylistImporterTest {

    private val importer = SpotifyPlaylistImporter()

    @Test
    fun testExtractPlaylistIdFromStandardUrl() {
        val url = "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"
        val id = importer.extractPlaylistId(url)
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", id)
    }

    @Test
    fun testExtractPlaylistIdWithQueryParams() {
        val url = "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=e2f336e9ff9440ea&pi=abc"
        val id = importer.extractPlaylistId(url)
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", id)
    }

    @Test
    fun testExtractPlaylistIdFromLocalizedUrl() {
        val urlIt = "https://open.spotify.com/intl-it/playlist/37i9dQZF1DXcBWIGoYBM5M"
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", importer.extractPlaylistId(urlIt))

        val urlDe = "https://open.spotify.com/intl-de/playlist/37i9dQZF1DXcBWIGoYBM5M?si=123"
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", importer.extractPlaylistId(urlDe))
    }

    @Test
    fun testExtractPlaylistIdFromUserPlaylistUrl() {
        val url = "https://open.spotify.com/user/spotify/playlist/37i9dQZF1DXcBWIGoYBM5M"
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", importer.extractPlaylistId(url))
    }

    @Test
    fun testExtractPlaylistIdFromSpotifyUri() {
        val uri = "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", importer.extractPlaylistId(uri))

        val userUri = "spotify:user:spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", importer.extractPlaylistId(userUri))
    }

    @Test
    fun testExtractPlaylistIdFromProtocolLessUrl() {
        val url = "open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", importer.extractPlaylistId(url))
    }

    @Test
    fun testCleanTrackTitle() {
        assertEquals("Bohemian Rhapsody", importer.cleanTrackTitle("Bohemian Rhapsody - Remastered 2011"))
        assertEquals("Bohemian Rhapsody", importer.cleanTrackTitle("Bohemian Rhapsody (2011 Remaster)"))
        assertEquals("Solar Eclipse", importer.cleanTrackTitle("Solar Eclipse (feat. Don Toliver)"))
        assertEquals("Solar Eclipse", importer.cleanTrackTitle("Solar Eclipse [ft. Don Toliver]"))
        assertEquals("Hotel California", importer.cleanTrackTitle("Hotel California - 2013 Remaster"))
        assertEquals("Ordinary Song", importer.cleanTrackTitle("Ordinary Song"))
    }

    @Test
    fun testExtractPlaylistIdFromRawId() {
        val rawId = "37i9dQZF1DXcBWIGoYBM5M"
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", importer.extractPlaylistId(rawId))
    }

    @Test
    fun testExtractPlaylistIdInvalid() {
        assertNull(importer.extractPlaylistId("https://open.spotify.com/track/1234567890123456789012"))
        assertNull(importer.extractPlaylistId("https://youtube.com/watch?v=123"))
        assertNull(importer.extractPlaylistId("not a playlist"))
    }

    @Test
    fun testFallbackTrackResolution() = runTest {
        val rawTrack = SpotifyRawTrack(
            id = "test12345",
            title = "Test Song",
            artist = "Test Artist",
            durationMs = 210000L,
            previewUrl = "https://p.scdn.co/mp3-preview/sample.mp3",
            coverUrl = "https://i.scdn.co/image/cover.jpg"
        )

        val resolved = importer.resolveTrack(rawTrack, fallbackCoverUrl = "https://i.scdn.co/image/cover.jpg", fallbackAlbum = "Test Album")
        assertNotNull(resolved)
        assertNotNull(resolved.title)
        assertNotNull(resolved.artist)
        assertEquals("spotify_test12345", resolved.id)
        assertEquals("Spotify", resolved.provider)
        assertNull("Imported track must never persist a runtime stream URL", resolved.streamUrl)
    }

    @Test
    fun testPlaylistConversion() = runTest {
        val fakeImporter = FakeSpotifyImporter()
        fakeImporter.playlistInfoToReturn = SpotifyPlaylistInfo(
            id = "37i9dQZF1DXcBWIGoYBM5M",
            title = "Today's Top Hits",
            description = "The biggest hits right now",
            coverUrl = "https://i.scdn.co/image/playlist.jpg",
            tracks = listOf(
                SpotifyRawTrack(
                    id = "track_1",
                    title = "Espresso",
                    artist = "Sabrina Carpenter",
                    durationMs = 175_000L,
                    previewUrl = "https://preview.url/1.mp3",
                    coverUrl = "https://cover.url/1.jpg"
                ),
                SpotifyRawTrack(
                    id = "track_2",
                    title = "Birds of a Feather",
                    artist = "Billie Eilish",
                    durationMs = 190_000L,
                    previewUrl = "https://preview.url/2.mp3",
                    coverUrl = "https://cover.url/2.jpg"
                )
            )
        )

        val progressUpdates = mutableListOf<Triple<Int, Int, String>>()
        val result = fakeImporter.importPlaylist(
            "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M",
            onProgress = { cur, tot, title -> progressUpdates.add(Triple(cur, tot, title)) }
        )
        org.junit.Assert.assertTrue("Playlist conversion should succeed", result.isSuccess)
        assertEquals(2, progressUpdates.size)
        assertEquals(Triple(1, 2, "Espresso"), progressUpdates[0])
        assertEquals(Triple(2, 2, "Birds of a Feather"), progressUpdates[1])

        val (info, tracks) = result.getOrThrow()
        assertEquals("Today's Top Hits", info.title)
        assertEquals(2, tracks.size)

        assertEquals("sonora_track_1", tracks[0].id)
        assertEquals("Espresso", tracks[0].title)
        assertEquals("Sabrina Carpenter", tracks[0].artist)
        assertEquals("Today's Top Hits", tracks[0].album)
        assertEquals("SpotifyImport", tracks[0].provider)

        assertEquals("sonora_track_2", tracks[1].id)
        assertEquals("Birds of a Feather", tracks[1].title)
        assertEquals("Billie Eilish", tracks[1].artist)
    }

    class FakeSpotifyImporter : SpotifyPlaylistImporter() {
        var playlistInfoToReturn: SpotifyPlaylistInfo? = null

        override suspend fun fetchPlaylistMetadata(playlistId: String): Result<SpotifyPlaylistInfo> {
            val info = playlistInfoToReturn ?: return Result.failure(Exception("Not found"))
            return Result.success(info)
        }

        override suspend fun resolveTrack(
            spotifyTrack: SpotifyRawTrack,
            fallbackCoverUrl: String?,
            fallbackAlbum: String?
        ): Track {
            return Track(
                id = "sonora_${spotifyTrack.id}",
                title = spotifyTrack.title,
                artist = spotifyTrack.artist,
                album = fallbackAlbum ?: "Unknown Album",
                durationMs = spotifyTrack.durationMs,
                coverUrl = spotifyTrack.coverUrl ?: fallbackCoverUrl,
                streamUrl = spotifyTrack.previewUrl,
                provider = "SpotifyImport"
            )
        }
    }
}
