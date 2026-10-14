package org.sonora.android.api.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcTest {

    @Test
    fun readsAStamp() {
        assertEquals(62_500L, Lrc.stampOf("01:02.50"))
        assertEquals(9_000L, Lrc.stampOf("00:09"))
        assertNull(Lrc.stampOf("bogus"))
    }

    @Test
    fun aStampPastTheLongestDurationIsNotAStamp() {
        assertNull(Lrc.stampOf("00:1e30"))
        assertNull(Lrc.stampOf("18446744073709551615:00"))
        assertEquals(1, Lrc.parse("[00:1e30]lost\n[00:01.00]kept").size)
    }

    @Test
    fun parsesLrcAndClosesEveryLine() {
        val lines = Lrc.parse("[00:10.00] first\n[00:14.50] second\n[bad] skipped\n")
        assertEquals(2, lines.size)
        assertEquals("first", lines[0].text)
        assertEquals(14_500L, lines[0].endMs)
        assertNull(lines[1].endMs)
    }

    @Test
    fun anEmptyTimedLineBecomesAGapBetweenLyrics() {
        val lines = Lrc.parse("[00:09.36] sung\n[00:11.97]\n[00:24.16] next\n")
        assertEquals(2, lines.size)
        assertEquals(11_970L, lines[0].endMs)
        assertEquals(24_160L, lines[1].startMs)
    }

    @Test
    fun readsWordTags() {
        val lines = Lrc.parse("[00:12.50]<00:12.50>I <00:12.80>see <00:13.10>trees\n[00:15.00]next")
        val words = lines[0].words
        requireNotNull(words)
        assertEquals(3, words.size)
        assertEquals("I ", words[0].text)
        assertEquals(12_800L, words[1].startMs)
        assertEquals(15_000L, words[2].endMs)
        assertEquals("I see trees", lines[0].text)
    }

    @Test
    fun aBareEndTagClosesTheLastWord() {
        val lines = Lrc.parse("[00:01.00]<00:01.00>one <00:01.50>two<00:02.00>")
        val words = lines[0].words
        requireNotNull(words)
        assertEquals(2, words.size)
        assertEquals(2_000L, words[1].endMs)
    }

    @Test
    fun angleBracketsThatAreNotStampsStayInTheText() {
        val lines = Lrc.parse("[00:01.00]a <3 b")
        assertEquals("a <3 b", lines[0].text)
        assertNull(lines[0].words)
    }

    @Test
    fun oneLineCanCarrySeveralStamps() {
        val lines = Lrc.parse("[00:01.00][00:31.00]chorus")
        assertEquals(2, lines.size)
        assertEquals(1_000L, lines[0].startMs)
        assertEquals(31_000L, lines[1].startMs)
    }

    @Test
    fun sectionLabelsAndDecorativeMarksAreNotLyrics() {
        val lines = Lrc.parse(
            "[00:01.00][Chorus]\n[00:02.00].\n[00:03.00]∮\n[00:04.00]- solo -\n[00:08.00]Actual lyric"
        )
        assertEquals(1, lines.size)
        assertEquals("Actual lyric", lines[0].text)
    }

    @Test
    fun parentheticalWordsBecomeAnIndependentlyTimedBackgroundLane() {
        val lines = Lrc.parse("[00:01.00]<00:01.00>Lead <00:01.50>(echo) <00:03.00>after")
        assertEquals("Lead after", lines[0].text)
        assertEquals(1, lines[0].secondary.size)
        assertEquals("(Echo)", lines[0].secondary[0].text)
        assertEquals(1_500L, lines[0].secondary[0].startMs)
        assertTrue(lines[0].secondary[0].words != null || lines[0].secondary[0].endMs != null)
    }
}
