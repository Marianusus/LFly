package org.sonora.android.api.lyrics.providers

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.sonora.android.api.lyrics.SheetLine
import org.junit.Test

class KugouProviderTest {

    private val provider = KugouProvider()

    private fun candidate(name: String, singer: String, durationSec: Int): JsonObject =
        JsonParser.parseString(
            """{"SongName":"$name","SingerName":"$singer","Duration":$durationSec,"FileHash":"h"}"""
        ).asJsonObject

    @Test
    fun pickCandidate_prefersTheSameArtistAndDuration() {
        val candidates = listOf(
            candidate("Blinding Lights", "Young Xuc, May Pian", 203),
            candidate("Blinding Lights", "The Weeknd", 200),
            candidate("Blinding Lights (Remix)", "The Weeknd, ROSALÍA", 216),
        )
        val picked = provider.pickCandidate(candidates, "Blinding Lights", "The Weeknd", 200_000L)
        assertEquals("The Weeknd", picked!!.get("SingerName").asString)
    }

    @Test
    fun pickCandidate_returnsNullOnEmptyInput() {
        assertNull(provider.pickCandidate(emptyList(), "x", "y", 0L))
    }

    @Test
    fun pickCandidate_rejectsCandidatesWithADifferentArtist() {
        val candidates = listOf(
            candidate("TALK", "ALLDAY PROJECT", 170),
            candidate("Talk", "Some Other Band", 167),
        )
        assertNull(provider.pickCandidate(candidates, "Talk", "Alexandra Stan", 167_000L))
    }

    @Test
    fun pickCandidate_prefersTheMatchingArtistAmongOtherwiseSimilarTitles() {
        val candidates = listOf(
            candidate("Talk", "Khalid", 200),
            candidate("Talk", "Alexandra Stan", 167),
        )
        val picked = provider.pickCandidate(candidates, "Talk", "Alexandra Stan", 167_000L)
        assertEquals("Alexandra Stan", picked!!.get("SingerName").asString)
    }

    @Test
    fun dropIntroCard_removesATitleArtistCard() {
        val lines = mutableListOf(
            SheetLine(0, 1000, "Blinding Lights - The Weeknd", words = null),
            SheetLine(5000, 1000, "real line", words = null),
        )
        provider.dropIntroCard(lines, "Blinding Lights", "The Weeknd")
        assertEquals(1, lines.size)
        assertEquals("real line", lines[0].text)
    }

    @Test
    fun dropIntroCard_keepsARealFirstLine() {
        val lines = mutableListOf(SheetLine(0, 1000, "Yeah", words = null))
        provider.dropIntroCard(lines, "Blinding Lights", "The Weeknd")
        assertEquals(1, lines.size)
    }
}
