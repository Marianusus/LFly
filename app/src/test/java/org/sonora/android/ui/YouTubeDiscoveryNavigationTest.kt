package org.sonora.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.sonora.android.api.YouTubeAlbum
import org.sonora.android.api.YouTubeArtist
import org.sonora.android.api.YouTubePlaylist

/**
 * Minimal ViewModel/UI-contract test for the discovery tap decision:
 * a navigable entity emits a detail id (→ navigate), otherwise null (→ visible feedback, never a silent tap).
 */
class YouTubeDiscoveryNavigationTest {

    @Test
    fun navigableArtistEmitsDetailId() {
        assertEquals("UC_a", YouTubeArtist(browseId = "UC_a", channelId = null, name = "A", thumbnailUrl = null).discoveryDetailId())
        assertEquals("UC_b", YouTubeArtist(browseId = null, channelId = "UC_b", name = "B", thumbnailUrl = null).discoveryDetailId())
    }

    @Test
    fun nonNavigableArtistEmitsNull() {
        assertNull(YouTubeArtist(browseId = null, channelId = null, name = "C", thumbnailUrl = null).discoveryDetailId())
    }

    @Test
    fun navigableAlbumEmitsDetailId() {
        assertEquals("MPREb_x", YouTubeAlbum(browseId = "MPREb_x", playlistId = null, title = "T", artist = "a", thumbnailUrl = null).discoveryDetailId())
        assertEquals("PLz", YouTubeAlbum(browseId = null, playlistId = "PLz", title = "T", artist = "a", thumbnailUrl = null).discoveryDetailId())
    }

    @Test
    fun nonNavigableAlbumEmitsNull() {
        assertNull(YouTubeAlbum(browseId = null, playlistId = null, title = "T", artist = "a", thumbnailUrl = null).discoveryDetailId())
    }

    @Test
    fun navigablePlaylistEmitsDetailId() {
        assertEquals("PL1", YouTubePlaylist(playlistId = "PL1", browseId = null, title = "T", thumbnailUrl = null).discoveryDetailId())
        assertEquals("VLPL2", YouTubePlaylist(playlistId = null, browseId = "VLPL2", title = "T", thumbnailUrl = null).discoveryDetailId())
    }

    @Test
    fun nonNavigablePlaylistEmitsNull() {
        assertNull(YouTubePlaylist(playlistId = null, browseId = null, title = "T", thumbnailUrl = null).discoveryDetailId())
    }
}
