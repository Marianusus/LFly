package org.sonora.android.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.sonora.android.api.Track
import org.sonora.android.data.Playlist

class YouTubeLibrarySyncTest {

    private fun track(id: String) = Track(id = "yt_$id", title = "Song $id", artist = "A", album = null, durationMs = 200_000L, coverUrl = null)

    @Test
    fun `a first import makes a playlist with a stable id`() {
        val playlist = YouTubeLibrarySync.merged(null, "PL123", "Gym", "Marian", "cover", listOf(track("a"), track("b"), track("a")), now = 10L)
        assertEquals("ytm_PL123", playlist.id)
        assertEquals("Gym", playlist.name)
        assertEquals("cover", playlist.coverUrl)
        assertEquals(listOf("yt_a", "yt_b"), playlist.tracks.map { it.id })
        assertEquals(10L, playlist.createdAt)
    }

    @Test
    fun `importing again takes YouTube's songs and keeps what the listener set`() {
        val existing = Playlist(id = "ytm_PL123", name = "Palestra", description = "mia", coverUrl = "mine", tracks = listOf(track("old")), createdAt = 1L, updatedAt = 1L)
        val playlist = YouTubeLibrarySync.merged(existing, "PL123", "Gym", "Marian", "cover", listOf(track("c")), now = 20L)
        assertEquals("ytm_PL123", playlist.id)
        assertEquals("Palestra", playlist.name)
        assertEquals("mine", playlist.coverUrl)
        assertEquals("mia", playlist.description)
        assertEquals(listOf("yt_c"), playlist.tracks.map { it.id })
        assertEquals(1L, playlist.createdAt)
        assertEquals(20L, playlist.updatedAt)
    }

    @Test
    fun `only imported playlists have a YouTube Music id`() {
        assertEquals("PL123", YouTubeLibrarySync.youTubePlaylistId("ytm_PL123"))
        assertNull(YouTubeLibrarySync.youTubePlaylistId("6f1c-uuid"))
    }
}
