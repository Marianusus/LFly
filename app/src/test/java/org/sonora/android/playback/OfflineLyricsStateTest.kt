package org.sonora.android.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure state mapping for offline lyrics, used by [DownloadManager.lyricsStates]. */
class OfflineLyricsStateTest {

    @Test
    fun storeMissMapsToNone() {
        val info = offlineLyricsInfo(null)

        assertEquals(OfflineLyricsStatus.NONE, info.status)
        assertNull(info.source)
        assertEquals(0L, info.fetchedAt)
    }

    @Test
    fun negativeMarkerMapsToNotFound() {
        val info = offlineLyricsInfo(
            OfflineLyrics(lyrics = null, source = null, notFound = true)
        )

        assertEquals(OfflineLyricsStatus.NOT_FOUND, info.status)
    }

    @Test
    fun foundLyricsKeepSourceAndTimestamp() {
        val fetchedAt = 1_234_567L
        val info = offlineLyricsInfo(
            OfflineLyrics(
                lyrics = null,
                source = "Musixmatch",
                notFound = false,
                fetchedAt = fetchedAt
            )
        )

        assertEquals(OfflineLyricsStatus.FOUND, info.status)
        assertEquals("Musixmatch", info.source)
        assertEquals(fetchedAt, info.fetchedAt)
    }

    @Test
    fun needsDownloadForMissingOrRetryableStates() {
        assertTrue(needsLyricsDownload(null))
        assertTrue(needsLyricsDownload(OfflineLyricsInfo(OfflineLyricsStatus.NONE)))
        assertTrue(needsLyricsDownload(OfflineLyricsInfo(OfflineLyricsStatus.NOT_FOUND)))
        assertTrue(needsLyricsDownload(OfflineLyricsInfo(OfflineLyricsStatus.ERROR)))
    }

    @Test
    fun doesNotNeedDownloadWhileFetchingOrWhenFound() {
        assertFalse(needsLyricsDownload(OfflineLyricsInfo(OfflineLyricsStatus.FETCHING)))
        assertFalse(needsLyricsDownload(OfflineLyricsInfo(OfflineLyricsStatus.FOUND)))
    }
}
