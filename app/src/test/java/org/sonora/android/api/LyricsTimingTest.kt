package org.sonora.android.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTimingTest {

    @Test
    fun splitWords_splitsOnWhitespace() {
        assertEquals(listOf("Hello", "beautiful", "world"), LyricsTiming.splitWords("  Hello   beautiful world "))
        assertTrue(LyricsTiming.splitWords("   ").isEmpty())
        assertTrue(LyricsTiming.splitWords("").isEmpty())
    }

    @Test
    fun splitWords_splitsCjkPerCharacter() {
        val words = LyricsTiming.splitWords("君の名は")
        assertEquals(listOf("君", "の", "名", "は"), words)
    }

    @Test
    fun syllableCount_estimatesLatinAndCyrillic() {
        assertEquals(2, LyricsTiming.syllableCount("Hello"))
        assertEquals(3, LyricsTiming.syllableCount("beautiful"))
        assertEquals(1, LyricsTiming.syllableCount("world"))
        assertEquals(2, LyricsTiming.syllableCount("Винтаж"))
        assertEquals(1, LyricsTiming.syllableCount(""))
    }

    @Test
    fun estimateWordTimings_coversWindow_andIsMonotonic() {
        val words = LyricsTiming.estimateWordTimings("Hello beautiful world", 10_000L, 13_000L)
        assertEquals(3, words.size)
        assertEquals("Hello", words.first().text)
        assertEquals("world", words.last().text)
        assertEquals(10_000L, words.first().startMs)
        assertEquals(13_000L, words.last().endMs)
        for (i in 1 until words.size) {
            assertTrue("word $i starts after previous", words[i].startMs >= words[i - 1].startMs)
            assertTrue("word $i ends after start", words[i].endMs > words[i].startMs)
        }
    }

    @Test
    fun estimateWordTimings_capsSweepAcrossLongInstrumentalGap() {
        // A 20s gap (instrumental drop) must not stretch the two words across the silence.
        val words = LyricsTiming.estimateWordTimings("one two", 10_000L, 30_000L)
        assertEquals(2, words.size)
        assertEquals(10_000L, words.first().startMs)
        assertTrue("sweep must finish early, not across the gap", words.last().endMs < 13_000L)
        assertTrue(words.last().endMs > words.first().startMs)
    }

    @Test
    fun estimateWordTimings_handlesEmptyAndDegenerateWindow() {
        assertTrue(LyricsTiming.estimateWordTimings("", 0L, 1000L).isEmpty())
        val words = LyricsTiming.estimateWordTimings("one two", 5_000L, 5_000L)
        assertEquals(2, words.size)
        assertTrue(words.last().endMs > words.first().startMs)
    }

    @Test
    fun activeWordIndex_binarySearch() {
        val words = listOf(
            WordTiming("a", 10_000L, 10_600L),
            WordTiming("b", 10_600L, 11_200L),
            WordTiming("c", 11_200L, 12_000L)
        )
        assertEquals(-1, LyricsTiming.activeWordIndex(words, 5_000L))
        assertEquals(0, LyricsTiming.activeWordIndex(words, 10_500L))
        assertEquals(1, LyricsTiming.activeWordIndex(words, 10_600L))
        assertEquals(2, LyricsTiming.activeWordIndex(words, 99_000L))
        assertEquals(-1, LyricsTiming.activeWordIndex(emptyList(), 100L))
    }

    @Test
    fun wordTimingsForLine_prefersRealTags() {
        val real = listOf(
            WordTiming("real", 1_000L, 1_500L),
            WordTiming("tags", 1_500L, 2_000L)
        )
        val line = LyricLine(timeMs = 1_000L, text = "real tags", wordTimings = real)
        assertEquals(real, LyricsTiming.wordTimingsForLine(line, nextLineStartMs = 5_000L))
    }

    @Test
    fun wordTimingsForLine_estimatesUpToNextLine() {
        val line = LyricLine(timeMs = 20_000L, text = "shine on you crazy diamond")
        val words = LyricsTiming.wordTimingsForLine(line, nextLineStartMs = 24_000L)
        assertEquals(5, words.size)
        assertEquals(20_000L, words.first().startMs)
        assertEquals(24_000L, words.last().endMs)
    }

    @Test
    fun wordProgress_isBounded() {
        val words = listOf(
            WordTiming("a", 1_000L, 2_000L),
            WordTiming("b", 2_000L, 3_000L)
        )
        assertEquals(0f, LyricsTiming.wordProgress(words, 0L), 0.0001f)
        assertEquals(0.5f, LyricsTiming.wordProgress(words, 2_000L), 0.0001f)
        assertEquals(1f, LyricsTiming.wordProgress(words, 9_000L), 0.0001f)
        assertEquals(0f, LyricsTiming.wordProgress(emptyList(), 100L), 0.0001f)
    }
}
