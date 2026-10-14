package org.sonora.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.sonora.android.api.Track

class PlaylistModelTest {

    private fun createTrack(id: String, durationMs: Long, coverUrl: String? = "https://cover.jpg/$id"): Track {
        return Track(
            id = id,
            title = "Track $id",
            artist = "Artist",
            album = "Album",
            durationMs = durationMs,
            coverUrl = coverUrl
        )
    }

    @Test
    fun testTrackCountAndDurationCalculation() {
        val tracks = listOf(
            createTrack("1", 120_000), // 2 min
            createTrack("2", 180_000)  // 3 min
        )
        val playlist = Playlist(
            name = "Test Playlist",
            tracks = tracks
        )

        assertEquals(2, playlist.trackCount)
        assertEquals(300_000L, playlist.totalDurationMs)
        assertEquals("5 min", playlist.formattedDuration)
    }

    @Test
    fun testFormattedDurationHoursAndMinutes() {
        val tracks = listOf(
            createTrack("1", 3_600_000), // 1 hour
            createTrack("2", 1_500_000)  // 25 min
        )
        val playlist = Playlist(
            name = "Long Playlist",
            tracks = tracks
        )

        assertEquals("1 hr 25 min", playlist.formattedDuration)
    }

    @Test
    fun testFormattedDurationShortSeconds() {
        val tracks = listOf(
            createTrack("1", 45_000) // 45 sec
        )
        val playlist = Playlist(
            name = "Short Playlist",
            tracks = tracks
        )

        assertEquals("45 sec", playlist.formattedDuration)
    }

    @Test
    fun testDisplayCoverUrlFallback() {
        val tracks = listOf(
            createTrack("1", 100_000, coverUrl = null),
            createTrack("2", 100_000, coverUrl = "https://example.com/cover2.jpg")
        )

        // Without custom cover, falls back to first track with cover
        val playlist = Playlist(
            name = "Fallback Playlist",
            tracks = tracks
        )
        assertEquals("https://example.com/cover2.jpg", playlist.displayCoverUrl)

        // With custom cover, uses custom cover
        val customPlaylist = Playlist(
            name = "Custom Cover Playlist",
            coverUrl = "https://custom.com/cover.jpg",
            tracks = tracks
        )
        assertEquals("https://custom.com/cover.jpg", customPlaylist.displayCoverUrl)
    }

    @Test
    fun testEmptyPlaylistDisplayCoverIsNull() {
        val playlist = Playlist(name = "Empty", tracks = emptyList())
        assertNull(playlist.displayCoverUrl)
        assertEquals("0 min", playlist.formattedDuration)
    }
}
