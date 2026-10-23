package org.sonora.android.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sonora.android.api.Track

class DownloadManagerTest {

    private fun track(id: String = "yt_abc", artist: String = "Artist", title: String = "Title") =
        Track(
            id = id,
            title = title,
            artist = artist,
            album = "Album",
            durationMs = 180_000L,
            coverUrl = null
        )

    @Test
    fun sanitizeFileNameStripsIllegalCharactersAndCollapsesWhitespace() {
        val sanitized = DownloadManager.sanitizeFileName("AC/DC: Back? \"Live\" <1979> |*")
        assertFalse(sanitized.contains('/'))
        assertFalse(sanitized.contains(':'))
        assertFalse(sanitized.contains('?'))
        assertFalse(sanitized.contains('"'))
        assertFalse(sanitized.contains('<'))
        assertFalse(sanitized.contains('>'))
        assertFalse(sanitized.contains('|'))
        assertFalse(sanitized.contains('*'))
        assertFalse(sanitized.contains("  "))
    }

    @Test
    fun sanitizeFileNameFallsBackWhenNothingRemains() {
        assertEquals("track", DownloadManager.sanitizeFileName("   "))
    }

    @Test
    fun buildFileNameKeepsExtensionAndTrackIdentitySuffix() {
        val fileName = DownloadManager.buildFileName(track(), "m4a")
        assertTrue(fileName.endsWith(".m4a"))
        assertTrue(fileName.contains("Artist - Title"))
        assertTrue(fileName.contains("["))
    }

    @Test
    fun buildFileNameIsStableForTheSameTrack() {
        assertEquals(
            DownloadManager.buildFileName(track(), "m4a"),
            DownloadManager.buildFileName(track(), "m4a")
        )
    }

    @Test
    fun extensionMappingCoversKnownFormats() {
        assertEquals("flac", DownloadManager.extensionFor("flac"))
        assertEquals("mp3", DownloadManager.extensionFor("MP3"))
        assertEquals("opus", DownloadManager.extensionFor("opus"))
        assertEquals("opus", DownloadManager.extensionFor("ogg"))
        assertEquals("webm", DownloadManager.extensionFor("webm"))
        assertEquals("m4a", DownloadManager.extensionFor("m4a"))
        assertEquals("m4a", DownloadManager.extensionFor("mp4"))
        assertEquals("m4a", DownloadManager.extensionFor("aac"))
        assertEquals("flac", DownloadManager.extensionFor("audio/flac"))
        assertEquals("m4a", DownloadManager.extensionFor("audio/mp4"))
        assertEquals("mp3", DownloadManager.extensionFor("audio/mpeg"))
        assertEquals("opus", DownloadManager.extensionFor("audio/opus"))
        assertEquals("m4a", DownloadManager.extensionFor(null))
        assertEquals("m4a", DownloadManager.extensionFor("weird"))
    }

    @Test
    fun downloadProgressFlagsActiveAndDownloadedStates() {
        assertFalse(DownloadProgress().isActive)
        assertFalse(DownloadProgress().isDownloaded)
        assertTrue(DownloadProgress(DownloadStatus.QUEUED).isActive)
        assertTrue(DownloadProgress(DownloadStatus.DOWNLOADING, 0.4f).isActive)
        assertFalse(DownloadProgress(DownloadStatus.DOWNLOADING, 0.4f).isDownloaded)
        assertTrue(DownloadProgress(DownloadStatus.DOWNLOADED, 1f).isDownloaded)
        assertFalse(DownloadProgress(DownloadStatus.FAILED).isActive)
    }
}
