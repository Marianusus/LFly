package org.sonora.android.playback.dj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sonora.android.api.Track
import org.sonora.android.playback.automix.MusicalKey
import kotlin.random.Random

class DjFlowTest {

    private fun track(id: String, artist: String = "Artist $id", bpm: Float? = null, durationMs: Long = 200_000L) =
        Track(id = id, title = "Song $id", artist = artist, album = null, durationMs = durationMs, coverUrl = null, bpm = bpm)

    @Test
    fun `tempos match at the same speed, not at double or half time, which Automix cannot mix`() {
        assertEquals(0.0, DjFlow.tempoDistance(120f, 120f)!!, 1e-9)
        assertTrue(DjFlow.tempoDistance(70f, 140f)!! > 0.5)
        assertEquals(DjFlow.tempoDistance(70f, 140f)!!, DjFlow.tempoDistance(140f, 70f)!!, 1e-9)
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
        val previous = track("p")
        val candidates = (1..8).map { track("c$it") }
        val best = setOf("c1", "c2", "c3")
        repeat(200) { seed ->
            val next = DjFlow.pickNext(previous, candidates, emptyList(), Random(seed))
            assertNotNull(next)
            assertTrue(next!!.id in best)
        }
    }

    @Test
    fun `when some songs mix on the beat, the next song is one of them`() {
        val previous = track("p", bpm = 120f)
        val candidates = listOf(
            track("far1", bpm = 90f), track("far2", bpm = 92f), track("good1", bpm = 121f),
            track("far3", bpm = 88f), track("unknown"), track("good2", bpm = 116f), track("double", bpm = 60f)
        )
        val onTheBeat = setOf("good1", "good2")
        val picks = (0 until 200).map { DjFlow.pickNext(previous, candidates, emptyList(), Random(it))!!.id }
        assertTrue(picks.all { it in onTheBeat })
        assertTrue(picks.toSet() == onTheBeat)
    }

    @Test
    fun `the analysed tempo and key are used when the catalogue has none`() {
        val previous = track("p")
        val clashing = track("a")
        val fitting = track("b")
        val grooves = mapOf(
            "p" to DjFlow.Groove(124f, MusicalKey(9, minor = true)),
            "a" to DjFlow.Groove(124f, MusicalKey(1, minor = false)),
            "b" to DjFlow.Groove(125f, MusicalKey(4, minor = true))
        )
        val groove = { t: Track -> grooves.getValue(t.id) }
        val clash = DjFlow.score(previous, clashing, rank = 0, total = 10, recentArtists = emptyList(), groove = groove)
        val fit = DjFlow.score(previous, fitting, rank = 2, total = 10, recentArtists = emptyList(), groove = groove)
        assertTrue(fit > clash)
        assertTrue(DjFlow.onTheBeat(previous, fitting, groove))
        // Both are on the beat and close in the engine's order; the one in a fitting key is picked more often.
        val others = (1..8).map { track("far$it") }
        val withOthers = grooves + others.associate { it.id to DjFlow.Groove(90f) }
        val pool = listOf(clashing, fitting) + others
        val picks = (0 until 200).map {
            DjFlow.pickNext(previous, pool, emptyList(), Random(it)) { t -> withOthers.getValue(t.id) }!!.id
        }
        assertTrue(picks.all { it == "a" || it == "b" })
        assertTrue(picks.count { it == "b" } > picks.size / 2)
    }

    @Test
    fun `candidates are probed best first on style and variety`() {
        val previous = track("p", artist = "Same")
        val candidates = listOf(track("sameArtist", artist = "Same"), track("c1"), track("c2"), track("c3"))
        val order = DjFlow.probeOrder(previous, candidates, emptyList()).map { it.id }
        assertEquals(listOf("c1", "c2", "c3", "sameArtist"), order)
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
