package org.sonora.android.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sonora.android.api.PlaybackSource
import org.sonora.android.api.Track

/**
 * A fuzzy local match may provide the audio file, but the track that reaches the player
 * must keep the catalog id: favorites/dislikes/history are keyed by it, and adopting the
 * `local_...` id made the player heart watch a different id than the one being toggled.
 */
class PlaybackTrackIdentityTest {

    @Test
    fun `pinned source beats the local copy while online`() {
        assertTrue(PlaybackManager.shouldStreamPinnedSource("yt_abc", PlaybackSource.MONOCHROME, isOnline = true))
        assertTrue(PlaybackManager.shouldStreamPinnedSource("spotify_abc", PlaybackSource.YOUTUBE_MUSIC, isOnline = true))
    }

    @Test
    fun `local copy keeps playing offline or without a pinned source`() {
        assertFalse(PlaybackManager.shouldStreamPinnedSource("yt_abc", PlaybackSource.MONOCHROME, isOnline = false))
        assertFalse(PlaybackManager.shouldStreamPinnedSource("yt_abc", null, isOnline = true))
    }

    @Test
    fun `purely local tracks have no source to pin`() {
        assertFalse(PlaybackManager.shouldStreamPinnedSource("local_abc", PlaybackSource.MONOCHROME, isOnline = true))
    }

    private val catalogTrack = Track(
        id = "yt_abc",
        title = "Title",
        artist = "Artist",
        album = "Album",
        durationMs = 180_000L,
        coverUrl = "https://example.com/catalog.jpg",
        provider = "YouTube"
    )

    private val localMatch = Track(
        id = "local_123",
        title = "Title (local tags)",
        artist = "Artist",
        album = "Download yt-dlp",
        durationMs = 181_000L,
        coverUrl = "file:///music/cover.jpg",
        streamUrl = "/music/Artist - Title [abcd1234].m4a",
        provider = "Local"
    )

    @Test
    fun localMatchKeepsCatalogIdentity() {
        val result = PlaybackManager.playableTrackFor(
            track = catalogTrack,
            localMatch = localMatch,
            streamUrl = localMatch.streamUrl!!,
            resolvedCoverUrl = null,
            localCoverUrl = null,
            isResolvedLocal = true
        )

        assertEquals(catalogTrack.id, result.id)
        assertEquals(catalogTrack.title, result.title)
        assertEquals(catalogTrack.artist, result.artist)
        assertEquals(catalogTrack.album, result.album)
        assertEquals(localMatch.streamUrl, result.streamUrl)
        assertEquals(localMatch.coverUrl, result.coverUrl)
        assertEquals("Local", result.provider)
    }

    @Test
    fun downloadCoverWinsOverLocalMatchCover() {
        val result = PlaybackManager.playableTrackFor(
            track = catalogTrack,
            localMatch = localMatch,
            streamUrl = localMatch.streamUrl!!,
            resolvedCoverUrl = null,
            localCoverUrl = "file:///music/abcd1234.jpg",
            isResolvedLocal = true
        )

        assertEquals(catalogTrack.id, result.id)
        assertEquals("file:///music/abcd1234.jpg", result.coverUrl)
    }

    @Test
    fun remoteStreamKeepsCatalogProviderAndResolvedCover() {
        val result = PlaybackManager.playableTrackFor(
            track = catalogTrack,
            localMatch = null,
            streamUrl = "https://stream.example.com/audio.m4a",
            resolvedCoverUrl = "https://example.com/resolved.jpg",
            localCoverUrl = null,
            isResolvedLocal = false
        )

        assertEquals(catalogTrack.id, result.id)
        assertEquals("YouTube", result.provider)
        assertEquals("https://example.com/resolved.jpg", result.coverUrl)
    }

    @Test
    fun keepsCatalogCoverWhenNothingElseIsAvailable() {
        val result = PlaybackManager.playableTrackFor(
            track = catalogTrack,
            localMatch = null,
            streamUrl = "https://stream.example.com/audio.m4a",
            resolvedCoverUrl = null,
            localCoverUrl = null,
            isResolvedLocal = false
        )

        assertEquals(catalogTrack.coverUrl, result.coverUrl)
        assertEquals(catalogTrack.id, result.id)
    }
}
