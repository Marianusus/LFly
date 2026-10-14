package org.sonora.android.api

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class LrclibClientTest {

    private val client = LrclibClient()

    @Test
    fun testParseLrcSyncedTimestamps() {
        val lrc = """
            [00:12.50]Line 1
            [00:15.80]Line 2
            [01:05.123]Line 3
        """.trimIndent()

        val parsed = client.parseLrc(lrc)
        assertEquals(3, parsed.size)
        assertEquals(12500L, parsed[0].timeMs)
        assertEquals("Line 1", parsed[0].text)
        assertEquals(15800L, parsed[1].timeMs)
        assertEquals("Line 2", parsed[1].text)
        assertEquals(65123L, parsed[2].timeMs)
        assertEquals("Line 3", parsed[2].text)
    }

    @Test
    fun testParseLrcEnhancedWordTags() {
        val lrc = "[00:10.00]<00:10.00>Hello <00:10.60>beautiful <00:11.20>world"
        val parsed = client.parseLrc(lrc)

        assertEquals(1, parsed.size)
        assertEquals(10_000L, parsed[0].timeMs)
        assertEquals("Hello beautiful world", parsed[0].text)
        val words = parsed[0].wordTimings
        assertEquals(3, words?.size)
        assertEquals("Hello", words?.get(0)?.text)
        assertEquals(10_000L, words?.get(0)?.startMs)
        assertEquals(10_600L, words?.get(1)?.startMs)
        assertEquals("world", words?.get(2)?.text)
    }

    @Test
    fun testParseLrcWithoutWordTagsHasEmptyTimings() {
        val parsed = client.parseLrc("[00:12.50]Line 1")
        assertEquals(1, parsed.size)
        assertTrue(parsed[0].wordTimings.isNullOrEmpty())
    }

    @Test
    fun testGetLyricsWithYouTubeClutterLive() = runBlocking {
        // Query with video noise that previously failed:
        val result = client.getLyrics(
            title = "Blinding Lights (Official Music Video)",
            artist = "The Weeknd - Topic",
            album = "YouTube"
        )
        assertTrue("Should resolve lyrics for cleaned title and artist", result.isSuccess)
        val lyrics = result.getOrNull()
        assertNotNull(lyrics)
        assertTrue(lyrics!!.lines.isNotEmpty() || !lyrics.plainLyrics.isNullOrBlank())
    }

    @Test
    fun testActiveLineIndex() {
        val lines = listOf(
            LyricLine(1000L, "a"),
            LyricLine(2000L, "b"),
            LyricLine(3000L, "c")
        )
        assertEquals(-1, activeLineIndex(lines, 500L))
        assertEquals(0, activeLineIndex(lines, 1000L))
        assertEquals(0, activeLineIndex(lines, 1500L))
        assertEquals(1, activeLineIndex(lines, 2500L))
        assertEquals(2, activeLineIndex(lines, 3000L))
        assertEquals(2, activeLineIndex(lines, 9999L))
        assertEquals(-1, activeLineIndex(emptyList(), 100L))
    }

    @Test
    fun testLyricsCacheReadWrite() {
        val dir = Files.createTempDirectory("lyrics-cache").toFile()
        val cache = LyricsCache(dir)
        val key = "Song|Artist|123"

        assertNull(cache.read(key))

        cache.write(key, CachedLyrics(notFound = false, id = "42", plainLyrics = "p", syncedLyrics = "[00:01.00]x"))
        val read = cache.read(key)
        assertNotNull(read)
        assertEquals("42", read!!.id)
        assertEquals("p", read.plainLyrics)
        assertEquals("[00:01.00]x", read.syncedLyrics)

        cache.write("missing|A|1", CachedLyrics(notFound = true))
        assertTrue(cache.read("missing|A|1")!!.notFound)

        cache.clear()
        assertNull(cache.read(key))
    }
}
