package org.sonora.android.playback.dj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sonora.android.api.Track
import kotlin.random.Random

class DjFlowTest {

    private fun track(id: String, artist: String = "Artist $id", bpm: Float? = null, durationMs: Long = 200_000L) =
        Track(id = id, title = "Song $id", artist = artist, album = null, durationMs = durationMs, coverUrl = null, bpm = bpm)

    @Test
    fun `tempos match at the same speed and at double or half time`() {
        assertEquals(0.0, DjFlow.tempoDistance(120f, 120f)!!, 1e-9)
        assertEquals(0.0, DjFlow.tempoDistance(70f, 140f)!!, 1e-9)
        assertEquals(0.0, DjFlow.tempoDistance(140f, 70f)!!, 1e-9)
        assertTrue(DjFlow.tempoDistance(120f, 128f)!! < DjFlow.tempoDistance(120f, 100f)!!)
        assertNull(DjFlow.tempoDistance(null, 120f))
        assertNull(DjFlow.tempoDistance(120f, 0f))
    }

    @Test
    fun `a matching tempo beats a better rank with a clashing one`() {
        val previous = track("p", bpm = 124f)
        val clashing = track("a", bpm = 95f)
        val matching = track("b", bpm = 125f)
        val clash = DjFlow.score(previous, clashing, rank = 0, total = 10, recentArtists = emptyList())
        val match = DjFlow.score(previous, matching, rank = 4, total = 10, recentArtists = emptyList())
        assertTrue(match > clash)
    }

    @Test
    fun `the same artist again is pushed back, a recent one a little`() {
        val previous = track("p", artist = "Daft Punk")
        val same = DjFlow.score(previous, track("a", artist = "Daft Punk"), 0, 5, emptyList())
        val recent = DjFlow.score(previous, track("b", artist = "Justice"), 0, 5, listOf("Justice"))
        val fresh = DjFlow.score(previous, track("c", artist = "Kavinsky"), 0, 5, emptyList())
        assertTrue(fresh > recent)
        assertTrue(recent > same)
    }

    @Test
    fun `songs too short to mix lose out`() {
        val previous = track("p")
        val short = DjFlow.score(previous, track("a", durationMs = 40_000L), 0, 5, emptyList())
        val normal = DjFlow.score(previous, track("b"), 0, 5, emptyList())
        assertTrue(normal > short)
    }

    @Test
    fun `the next song is always one of the three best`() {
        val previous = track("p", bpm = 120f)
        val candidates = listOf(
            track("far1", bpm = 90f), track("far2", bpm = 92f), track("good1", bpm = 121f),
            track("far3", bpm = 88f), track("good2", bpm = 119f), track("good3", bpm = 60f)
        )
        val best = setOf("good1", "good2", "good3")
        repeat(200) { seed ->
            val next = DjFlow.pickNext(previous, candidates, emptyList(), Random(seed))
            assertNotNull(next)
            assertTrue(next!!.id in best)
        }
    }

    @Test
    fun `no candidates means no next song`() {
        assertNull(DjFlow.pickNext(track("p"), emptyList(), emptyList(), Random(1)))
        assertNull(DjFlow.pickAnchor(emptyList(), null, Random(1)))
    }

    @Test
    fun `the anchor comes from the most played songs`() {
        val pool = (1..30).map { track("t$it") }
        repeat(100) { seed ->
            val anchor = DjFlow.pickAnchor(pool, previous = null, random = Random(seed))!!
            assertTrue(pool.indexOf(anchor) < DjFlow.ANCHOR_POOL)
        }
    }

    @Test
    fun `a later anchor follows the song before it`() {
        val previous = track("p", artist = "Same", bpm = 128f)
        val pool = listOf(
            track("slow", bpm = 90f), track("sameArtist", artist = "Same", bpm = 128f),
            track("fits", bpm = 127f), track("slow2", bpm = 85f)
        )
        val picks = (0 until 200).map { DjFlow.pickAnchor(pool, previous, Random(it))!!.id }
        val fits = picks.count { it == "fits" }
        assertTrue(fits > picks.size / 2)
        assertNotEquals("sameArtist", picks.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key)
    }

    @Test
    fun `same seed, same choice`() {
        val previous = track("p")
        val candidates = (1..10).map { track("c$it") }
        assertEquals(
            DjFlow.pickNext(previous, candidates, emptyList(), Random(7)),
            DjFlow.pickNext(previous, candidates, emptyList(), Random(7))
        )
    }
}
