package org.sonora.android.api.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsRankTest {

    private fun line(startSec: Long, endSec: Long?, text: String) =
        SheetLine(startMs = startSec * 1000, endMs = endSec?.times(1000), text = text)

    private fun hit(
        title: String,
        artist: String,
        seconds: Long,
        synced: Boolean,
        worded: Boolean = false
    ): LyricsHit {
        val lyrics: Sheet = if (synced) {
            val lines = mutableListOf(line(0, seconds - 2, title))
            if (worded) {
                lines[0].words = mutableListOf(
                    SheetWord(0, 1000, "another take")
                )
                lines[0].text = "another take"
            }
            Sheet.Synced(lines)
        } else {
            Sheet.Plain("la $title")
        }
        return LyricsHit(
            source = "test",
            trust = 0,
            lyrics = lyrics,
            instrumental = false,
            title = title,
            artist = artist,
            album = null,
            durationMs = seconds * 1000,
        )
    }

    private fun query() = LyricsQuery("Jaded", "Spiritbox", null, 263_000)

    @Test
    fun theClosestDurationWins() {
        val ranked = LyricsRank.rank(
            query(),
            listOf(hit("Jaded", "Spiritbox", 200, true), hit("Jaded", "Spiritbox", 263, false))
        )
        assertEquals(263_000L, ranked[0].durationMs)
    }

    @Test
    fun syncedBreaksATie() {
        val ranked = LyricsRank.rank(
            query(),
            listOf(hit("Jaded", "Spiritbox", 263, false), hit("Jaded", "Spiritbox", 263, true))
        )
        assertEquals(true, ranked[0].lyrics.synced())
    }

    @Test
    fun aWrongTrackFallsBehind() {
        val ranked = LyricsRank.rank(
            query(),
            listOf(hit("Something Else", "Nobody", 263, true), hit("Jaded", "Spiritbox", 261, false))
        )
        assertEquals("Jaded", ranked[0].title)
        assertEquals(1, ranked.size)
    }

    @Test
    fun wordedBeatsMerelySynced() {
        val ranked = LyricsRank.rank(
            query(),
            listOf(hit("Jaded", "Spiritbox", 263, true), hit("Jaded", "Spiritbox", 263, true, worded = true))
        )
        assertEquals(true, ranked[0].lyrics.worded())
    }

    @Test
    fun aTruncatedSyncFallsBehind() {
        val cut = hit("Jaded", "Spiritbox", 263, true)
        cut.lyrics.let { if (it is Sheet.Synced) it.lines[0].endMs = 40_000 }
        val ranked = LyricsRank.rank(query(), listOf(cut, hit("Jaded", "Spiritbox", 263, true)))
        assertEquals(true, (ranked[0].lyrics.spanMs() ?: 0L) > 200_000L)
    }

    @Test
    fun theActiveLineFollowsTheClock() {
        val lines = listOf(line(0, 5, "one"), line(5, 9, "two"))
        assertEquals(0, LyricsRank.activeLine(lines, 2_000))
        assertEquals(1, LyricsRank.activeLine(lines, 6_000))
        assertEquals(1, LyricsRank.activeLine(lines, 30_000))
    }

    @Test
    fun theActiveWordFollowsTheClock() {
        val words = listOf(
            SheetWord(0, 400, "one "),
            SheetWord(400, 900, "two"),
        )
        assertEquals(0, LyricsRank.activeWord(words, 100))
        assertEquals(1, LyricsRank.activeWord(words, 500))
        assertNull(LyricsRank.activeWord(words, 2_000))
    }

    @Test
    fun alikeMatchesAsExpected() {
        assertEquals(LyricsRank.undecorated("Jaded (Remastered 2024)"), LyricsRank.undecorated("Jaded"))
    }

    @Test
    fun matchesAcceptsTheSameTitleArtistAndDuration() {
        assertTrue(
            LyricsRank.matches(
                title = "Talk", artist = "Alexandra Stan", durationMs = 167_000,
                hitTitle = "Talk", hitArtist = "Alexandra Stan", hitDurationMs = 166_000
            )
        )
    }

    @Test
    fun matchesRejectsAnotherArtistWithTheSameTitle() {
        assertFalse(
            LyricsRank.matches(
                title = "Talk", artist = "Alexandra Stan", durationMs = 167_000,
                hitTitle = "TALK", hitArtist = "ALLDAY PROJECT", hitDurationMs = 170_000
            )
        )
    }

    @Test
    fun matchesRejectsAWayOffDuration() {
        assertFalse(
            LyricsRank.matches(
                title = "Talk", artist = "Alexandra Stan", durationMs = 167_000,
                hitTitle = "Talk", hitArtist = "Alexandra Stan", hitDurationMs = 230_000
            )
        )
    }

    @Test
    fun matchesIgnoresDurationWhenUnknown() {
        assertTrue(
            LyricsRank.matches(
                title = "Talk", artist = "Alexandra Stan", durationMs = 0,
                hitTitle = "Talk", hitArtist = "Alexandra Stan", hitDurationMs = null
            )
        )
    }
}
