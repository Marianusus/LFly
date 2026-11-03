package org.sonora.android.playback.automix

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class PlaylistMixTest {

    // ── Keys ────────────────────────────────────────────────────────────────

    @Test
    fun `keys sit on the Camelot wheel like a DJ reads them`() {
        assertEquals("8B", MusicalKey(0, minor = false).camelot) // C major
        assertEquals("8A", MusicalKey(9, minor = true).camelot) // A minor
        assertEquals("9B", MusicalKey(7, minor = false).camelot) // G major
        assertEquals("7B", MusicalKey(5, minor = false).camelot) // F major
        assertEquals("5A", MusicalKey(0, minor = true).camelot) // C minor
        assertEquals("1B", MusicalKey(11, minor = false).camelot) // B major
    }

    @Test
    fun `neighbours on the wheel are one step apart`() {
        val aMinor = MusicalKey(9, minor = true)
        assertEquals(0, aMinor.distance(aMinor))
        assertEquals(1, aMinor.distance(MusicalKey(0, minor = false))) // relative major, 8B
        assertEquals(1, aMinor.distance(MusicalKey(4, minor = true))) // E minor, 9A
        assertEquals(1, aMinor.distance(MusicalKey(2, minor = true))) // D minor, 7A
        // 12A and 1A are neighbours across the top of the wheel.
        assertEquals(1, MusicalKey(1, minor = true).distance(MusicalKey(6, minor = true)))
        assertTrue(aMinor.distance(MusicalKey(3, minor = true)) >= 5)
    }

    @Test
    fun `a sustained chord is heard in its key`() {
        val rate = 22_050
        // A minor triad over an A bass: A2, A3, C4, E4.
        val aMinor = tones(rate, 6.0, 110.0, 220.0, 261.63, 329.63)
        val found = KeyDetector.detect(aMinor, rate)
        assertNotNull(found)
        assertEquals(9, found!!.tonic)
        assertTrue(found.minor)

        // C major triad over a C bass: C3, C4, E4, G4.
        val cMajor = tones(rate, 6.0, 130.81, 261.63, 329.63, 392.0)
        val major = KeyDetector.detect(cMajor, rate)!!
        assertEquals(0, major.tonic)
        assertTrue(!major.minor)
    }

    @Test
    fun `silence and very short audio have no key`() {
        assertNull(KeyDetector.detect(FloatArray(22_050 * 3), 22_050))
        assertNull(KeyDetector.detect(FloatArray(100), 22_050))
    }

    @Test
    fun `the waveform follows the level and stays in range`() {
        val rate = 22_050
        val quiet = FloatArray(rate) { (0.1 * sin(2 * PI * 440 * it / rate)).toFloat() }
        val loud = FloatArray(rate) { (0.8 * sin(2 * PI * 440 * it / rate)).toFloat() }
        val wave = Waveform.compute(quiet + loud, rate, 10.0)!!
        assertEquals(10.0, wave.startSeconds, 1e-9)
        assertEquals(Waveform.RATE, wave.rate, 0.5)
        assertTrue(wave.levels.all { it in 0f..1f })
        assertTrue(wave.levels.first() < 0.2f)
        assertTrue(wave.levels.last() > 0.95f)
    }

    // ── Smart Reorder ───────────────────────────────────────────────────────

    @Test
    fun `smart reorder walks the tempos and keeps the opener`() {
        val infos = listOf(
            info(120.0, MusicalKey(9, true)),
            info(140.0, MusicalKey(9, true)),
            info(122.0, MusicalKey(4, true)),
            info(138.0, MusicalKey(4, true)),
            info(124.0, MusicalKey(11, true))
        )
        val order = MixOrder.suggest(infos)
        assertEquals(0, order.first())
        assertEquals(listOf(0, 2, 4, 3, 1), order)
        assertTrue(MixOrder.totalCost(infos, order) < MixOrder.totalCost(infos, infos.indices.toList()))
    }

    @Test
    fun `songs without analysis keep their order at the end`() {
        val infos = listOf(info(120.0), null, info(140.0), info(121.0), null)
        val order = MixOrder.suggest(infos)
        assertEquals(listOf(0, 3, 2, 1, 4), order)
    }

    @Test
    fun `too little to go on leaves the order alone`() {
        assertEquals(listOf(0, 1), MixOrder.suggest(listOf(info(120.0), info(150.0))))
        assertEquals(listOf(0, 1, 2), MixOrder.suggest(listOf(info(120.0), null, null)))
    }

    @Test
    fun `a clashing key costs more than a neighbour`() {
        val from = info(120.0, MusicalKey(9, true))
        val near = info(120.0, MusicalKey(0, false))
        val far = info(120.0, MusicalKey(3, true))
        assertTrue(MixOrder.cost(from, near) < MixOrder.cost(from, far))
        // A tempo the beats cannot follow costs more than any key.
        assertTrue(MixOrder.cost(from, info(150.0, MusicalKey(9, true))) > MixOrder.cost(from, far))
    }

    // ── Presets and saved transitions ───────────────────────────────────────

    @Test
    fun `presets are the ingredients of the ready-made styles`() {
        assertEquals(TransitionStyle.BASS_SWAP.recipe, MixPreset.RISE.recipe)
        assertEquals(TransitionStyle.EQ_SWAP.recipe, MixPreset.BLEND.recipe)
        assertEquals(TransitionStyle.FILTER.recipe, MixPreset.MELT.recipe)
        assertEquals(TransitionStyle.WAVE.recipe, MixPreset.WAVE.recipe)
        assertNull(MixPreset.AUTO.recipe)
        assertNull(MixPreset.NONE.recipe)
        assertTrue(MixTransition(preset = MixPreset.NONE).isNone)
        assertTrue(!MixTransition(preset = MixPreset.NONE, custom = MixRecipe(VolumeCurve.CROSSFADE)).isNone)
    }

    @Test
    fun `every preset starts on the outgoing song and ends on the incoming one`() {
        for (preset in MixPreset.entries) {
            val recipe = preset.recipe ?: continue
            val start = TransitionCurves.frame(recipe, 0f)
            val end = TransitionCurves.frame(recipe, 1f)
            assertEquals("$preset start", 1f, start.outgoing.volume, 1e-4f)
            assertEquals("$preset start incoming", 0f, start.incoming.volume, 1e-4f)
            assertEquals("$preset end", 0f, end.outgoing.volume, 1e-4f)
            assertEquals("$preset end incoming", 1f, end.incoming.volume, 1e-4f)
            assertEquals("$preset end filter", DjFilterSettings.NEUTRAL, end.incoming.filter)
        }
    }

    @Test
    fun `wave sweeps a low-pass on both songs and slam cuts in the middle`() {
        val wave = TransitionCurves.frame(TransitionStyle.WAVE, 0.75f).outgoing.filter
        assertTrue(wave.lowPassHz in 200f..10_000f)
        assertTrue(TransitionCurves.frame(TransitionStyle.WAVE, 0.25f).incoming.filter.lowPassHz in 200f..10_000f)

        val before = TransitionCurves.frame(TransitionStyle.SLAM, 0.49f)
        val after = TransitionCurves.frame(TransitionStyle.SLAM, 0.52f)
        assertEquals(1f, before.outgoing.volume, 1e-6f)
        assertEquals(0f, before.incoming.volume, 1e-6f)
        assertEquals(0f, after.outgoing.volume, 1e-6f)
        assertEquals(1f, after.incoming.volume, 1e-6f)
    }

    @Test
    fun `a saved mix written by an older version still loads with its defaults`() {
        val gson = Gson()
        val type = object : TypeToken<Map<String, PlaylistMix>>() {}.type
        val json = """{"p1":{"enabled":true,"transitions":{"a>b":{"preset":"BLEND"}}}}"""
        val map: Map<String, PlaylistMix> = gson.fromJson(json, type)
        val transition = map.getValue("p1").transition("a", "b")
        assertEquals(MixPreset.BLEND, transition.preset)
        assertTrue(transition.beatmatch)
        assertNull(transition.bars)
        assertEquals(MixTransition.AUTO, map.getValue("p1").transition("b", "a"))

        val roundTrip: Map<String, PlaylistMix> = gson.fromJson(gson.toJson(map), type)
        assertEquals(map, roundTrip)
    }

    // ── Planner fixes from the editor ───────────────────────────────────────

    @Test
    fun `the editor's bars, exit and entry are kept on bar lines`() {
        val tail = grid(120.0, 120.5, 59.0)
        val head = grid(123.0, 0.8, 59.0)
        val plan = AutomixPlanner.plan(
            tail, 180_000L, head,
            fixes = PlanFixes(bars = 8, exitSeconds = 150.9, entrySeconds = 9.0)
        )!!
        assertEquals(8, plan.mixBars)
        // 150.5 is the bar line nearest 150.9; bars on the incoming song fall every four beats from 0.8 s.
        assertEquals(150_500L, plan.exitMs)
        assertTrue(head.downbeats.any { abs(it * 1_000 - plan.entryMs) < 1 })
        assertEquals(9.0, plan.entryMs / 1_000.0, 2 * head.beatSeconds)
    }

    @Test
    fun `a fixed exit too late for the mix moves to the last bar that fits`() {
        val tail = grid(120.0, 120.5, 59.0)
        val head = grid(123.0, 0.8, 59.0)
        val plan = AutomixPlanner.plan(tail, 180_000L, head, fixes = PlanFixes(bars = 16, exitSeconds = 175.0))!!
        assertEquals(146_500L, plan.exitMs)
    }

    private fun info(bpm: Double, key: MusicalKey? = null) = MixTrackInfo(headBpm = bpm, tailBpm = bpm, key = key)

    private fun grid(bpm: Double, start: Double, seconds: Double): BeatGrid {
        val interval = 60.0 / bpm
        val beats = generateSequence(start) { it + interval }.takeWhile { it < start + seconds }.toList().toDoubleArray()
        val downbeats = beats.filterIndexed { index, _ -> index % 4 == 0 }.toDoubleArray()
        return BeatGrid(beats, downbeats, bpm, 0.9)
    }

    private fun tones(rate: Int, seconds: Double, vararg hz: Double): FloatArray =
        FloatArray((rate * seconds).toInt()) { i ->
            (hz.sumOf { 0.2 * sin(2 * PI * it * i / rate) }).toFloat()
        }
}
