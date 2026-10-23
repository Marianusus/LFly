package org.sonora.android.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.sonora.android.api.Track

/**
 * Pure lookup logic behind [DownloadManager.canonicalTrackFor]: a Local snapshot must resolve
 * back to the catalog track of its download by id first, then by audio path.
 */
class DownloadManagerCanonicalTrackTest {

    private val catalogTrack = Track(
        id = "yt_abc",
        title = "Title",
        artist = "Artist",
        album = "Album",
        durationMs = 180_000L,
        coverUrl = "https://example.com/cover.jpg",
        provider = "YouTube"
    )

    private val entry = DownloadEntry(
        track = catalogTrack,
        fileName = "Artist - Title [abcd1234].m4a"
    )

    private val entries = mapOf(catalogTrack.id to entry)

    private fun entryPath(download: DownloadEntry): String = "/music/${download.fileName}"

    @Test
    fun resolvesByIdBeforeLookingAtThePath() {
        val snapshot = catalogTrack.copy(streamUrl = "/somewhere/else.m4a")

        val resolved = DownloadManager.canonicalEntryFor(snapshot, entries, ::entryPath)

        assertEquals(entry, resolved)
    }

    @Test
    fun resolvesLocalCopyByAbsolutePath() {
        val localCopy = Track(
            id = "local_123",
            title = "Title",
            artist = "Artist",
            album = null,
            durationMs = 180_000L,
            coverUrl = null,
            streamUrl = "/music/Artist - Title [abcd1234].m4a",
            provider = "Local"
        )

        assertEquals(entry, DownloadManager.canonicalEntryFor(localCopy, entries, ::entryPath))
    }

    @Test
    fun resolvesLocalCopyByFileUri() {
        val localCopy = Track(
            id = "local_456",
            title = "Title",
            artist = "Artist",
            album = null,
            durationMs = 180_000L,
            coverUrl = null,
            streamUrl = "file:///music/Artist - Title [abcd1234].m4a",
            provider = "Local"
        )

        assertEquals(entry, DownloadManager.canonicalEntryFor(localCopy, entries, ::entryPath))
    }

    @Test
    fun returnsNullWhenNothingMatches() {
        val unknown = Track(
            id = "local_999",
            title = "Other",
            artist = "Other",
            album = null,
            durationMs = 100_000L,
            coverUrl = null,
            streamUrl = "/music/other-file.m4a",
            provider = "Local"
        )

        assertNull(DownloadManager.canonicalEntryFor(unknown, entries, ::entryPath))
        assertNull(DownloadManager.canonicalEntryFor(unknown.copy(streamUrl = null), entries, ::entryPath))
    }

    @Test
    fun normalizeLocalPathAcceptsUrisAndPlainPaths() {
        assertEquals(
            "/music/Artist - Title [abcd1234].m4a",
            DownloadManager.normalizeLocalPath("file:///music/Artist - Title [abcd1234].m4a")
        )
        assertEquals(
            "/music/Artist - Title [abcd1234].m4a",
            DownloadManager.normalizeLocalPath("/music/Artist - Title [abcd1234].m4a")
        )
        assertNull(DownloadManager.normalizeLocalPath("   "))
        assertNull(DownloadManager.normalizeLocalPath(null))
    }
}
