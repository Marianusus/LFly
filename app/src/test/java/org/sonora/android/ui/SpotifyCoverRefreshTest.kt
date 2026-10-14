package org.sonora.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sonora.android.api.Track

class SpotifyCoverRefreshTest {

    private fun spotifyTrack(
        id: String = "spotify_abc",
        title: String = "O Sticla Doua Trei",
        artist: String = "Liviu Guta",
        durationMs: Long = 200_000L,
        coverUrl: String? = null,
        provider: String = "Spotify"
    ) = Track(
        id = id,
        title = title,
        artist = artist,
        album = null,
        durationMs = durationMs,
        coverUrl = coverUrl,
        provider = provider
    )

    private fun ytCandidate(
        title: String = "O Sticla Doua Trei",
        artist: String = "Liviu Guta si Play Aj",
        durationMs: Long = 200_000L,
        coverUrl: String? = "https://yt3.googleusercontent.com/cover.jpg"
    ) = Track(
        id = "yt_P-sfIzmlp5Q",
        title = title,
        artist = artist,
        album = null,
        durationMs = durationMs,
        coverUrl = coverUrl,
        provider = "YouTube"
    )

    @Test
    fun nullCoverIsCandidate() {
        assertTrue(isCoverRefreshCandidate(spotifyTrack(coverUrl = null)))
    }

    @Test
    fun blankCoverIsCandidate() {
        assertTrue(isCoverRefreshCandidate(spotifyTrack(coverUrl = "   ")))
    }

    @Test
    fun placeholderCoverIsCandidate() {
        assertTrue(isCoverRefreshCandidate(spotifyTrack(coverUrl = "https://cdn.example.com/placeholder.png")))
    }

    @Test
    fun ytCoverIsNotCandidate() {
        assertFalse(isCoverRefreshCandidate(spotifyTrack(coverUrl = "https://yt3.googleusercontent.com/x.jpg")))
    }

    @Test
    fun validSpotifyCoverIsNotCandidate() {
        assertFalse(isCoverRefreshCandidate(spotifyTrack(coverUrl = "https://i.scdn.co/image/ab67616d0000b273")))
    }

    @Test
    fun unknownLegacyCdnCoverIsCandidate() {
        assertTrue(isCoverRefreshCandidate(spotifyTrack(coverUrl = "https://e-cdns-images.dzcdn.net/images/cover/x.jpg")))
    }

    @Test
    fun nonSpotifyTrackIsNotCandidate() {
        assertFalse(isCoverRefreshCandidate(spotifyTrack(coverUrl = null, provider = "YouTube")))
        assertFalse(isCoverRefreshCandidate(spotifyTrack(id = "yt_123", coverUrl = null)))
        assertFalse(isCoverRefreshCandidate(spotifyTrack(id = "dz_123", coverUrl = null, provider = "Deezer")))
    }

    @Test
    fun reliableMatchReturnsHttpCover() {
        val cover = pickReliableYtCover(spotifyTrack(), listOf(ytCandidate()))
        assertEquals("https://yt3.googleusercontent.com/cover.jpg", cover)
    }

    @Test
    fun nonHttpCoverIsNotReturned() {
        val cover = pickReliableYtCover(
            spotifyTrack(),
            listOf(ytCandidate(coverUrl = "file:///local/cover.png"))
        )
        assertNull(cover)
    }

    @Test
    fun unreliableMatchReturnsNull() {
        val cover = pickReliableYtCover(
            spotifyTrack(),
            listOf(ytCandidate(title = "Completely Different Song", artist = "Someone Else"))
        )
        assertNull(cover)
    }

    @Test
    fun nullCoverMatchDoesNotUpdate() {
        val cover = pickReliableYtCover(spotifyTrack(), listOf(ytCandidate(coverUrl = null)))
        assertNull(cover)
    }

    @Test
    fun emptyCandidatesReturnsNull() {
        assertNull(pickReliableYtCover(spotifyTrack(), emptyList()))
    }

    @Test
    fun placeholderHelpers() {
        assertTrue(isPlaceholderCover(null))
        assertTrue(isPlaceholderCover("https://x/no_artwork.png"))
        assertFalse(isPlaceholderCover("https://yt3.googleusercontent.com/x.jpg"))
        assertTrue(isHttpCover("http://x/y.jpg"))
        assertTrue(isHttpCover("https://x/y.jpg"))
        assertFalse(isHttpCover(null))
        assertFalse(isHttpCover("ftp://x/y.jpg"))
    }
}
