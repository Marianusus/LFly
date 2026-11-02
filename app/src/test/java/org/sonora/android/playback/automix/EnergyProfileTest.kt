package org.sonora.android.playback.automix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

class EnergyProfileTest {

    private val rate = LogMelSpectrogram.SAMPLE_RATE

    /** Where the window starts in the song, and the first beat inside it (120 BPM, so a bar is 2 s). */
    private val start = 100.0
    private val firstBeat = 100.25
    private val bar = 2.0

    /** A 120 BPM grid in 4/4 from [first], with a downbeat every fourth beat. */
    private fun grid(first: Double, count: Int, withDownbeats: Boolean = true): BeatGrid {
        val beats = DoubleArray(count) { first + 0.5 * it }
        val downbeats = if (withDownbeats) beats.filterIndexed { index, _ -> index % 4 == 0 }.toDoubleArray() else DoubleArray(0)
        return BeatGrid(beats, downbeats, 120.0, 0.9)
    }

    private fun gridFor(seconds: Double) = grid(firstBeat, ((seconds - (firstBeat - start)) / 0.5).toInt())

    /**
     * Steady noise with a decaying noise click on every beat, scaled by [gainDb] at each moment of
     * the song: a stand-in for a mix with drums.
     */
    private fun drums(seconds: Double, noise: Double = 0.1, click: Double = 0.6, gainDb: (Double) -> Double): FloatArray {
        val random = Random(7)
        return FloatArray((seconds * rate).toInt()) { index ->
            val time = start + index.toDouble() / rate
            val sinceBeat = (time - firstBeat).mod(0.5)
            val burst = if (time >= firstBeat) click * exp(-sinceBeat / 0.01) else 0.0
            val value = (noise + burst) * (random.nextDouble() * 2 - 1)
            (value * 10.0.pow(gainDb(time) / 20)).toFloat()
        }
    }

    @Test
    fun `a 10 dB drop at bar 8 starts the outro there`() {
        val drop = firstBeat + 8 * bar
        val profile = EnergyProfile.compute(drums(48.0) { if (it >= drop) -10.0 else 0.0 }, rate, start, gridFor(48.0))!!
        assertEquals(drop, profile.outroStart!!, 0.05)
        assertNull(profile.firstRise)
        assertFalse(profile.isCalm)
        assertTrue(profile.barEnergyDb.all { it <= 0.0 })
        assertEquals(-10.0, profile.barEnergyDb.last(), 0.5)
    }

    @Test
    fun `a quiet intro rises at bar 4`() {
        val rise = firstBeat + 4 * bar
        val profile = EnergyProfile.compute(drums(48.0) { if (it < rise) -12.0 else 0.0 }, rate, start, gridFor(48.0))!!
        assertEquals(rise, profile.firstRise!!, 0.05)
        assertNull(profile.outroStart)
    }

    @Test
    fun `a constant level has neither outro nor rise`() {
        val profile = EnergyProfile.compute(drums(48.0) { 0.0 }, rate, start, gridFor(48.0))!!
        assertNull(profile.outroStart)
        assertNull(profile.firstRise)
        assertTrue(profile.barEnergyDb.all { abs(it) < 1.0 })
    }

    @Test
    fun `a breakdown that comes back is not an outro, and a full-energy start is not a rise`() {
        val energy = DoubleArray(16) { if (it in 6..9) -10.0 else 0.0 }
        assertNull(EnergyProfile.outroBar(energy))
        assertNull(EnergyProfile.riseBar(energy))
        val build = doubleArrayOf(-20.0, -18.0, -17.0, -16.0, 0.0, 0.0, -1.0, 0.0)
        assertEquals(4, EnergyProfile.riseBar(build))
    }

    @Test
    fun `a soft sine pad is calm, a loud click track is not`() {
        val pad = FloatArray(32 * rate) { (0.1 * sin(2 * PI * 220.0 * it / rate)).toFloat() }
        assertTrue(EnergyProfile.compute(pad, rate, start, gridFor(32.0))!!.isCalm)

        val clicks = drums(32.0, noise = 0.0, click = 0.9) { 0.0 }
        assertFalse(EnergyProfile.compute(clicks, rate, start, gridFor(32.0))!!.isCalm)
    }

    @Test
    fun `too little audio gives no profile`() {
        assertNull(EnergyProfile.compute(drums(3.0) { 0.0 }, rate, start, grid(firstBeat, 5)))
        assertNull(EnergyProfile.compute(FloatArray(0), rate, start, gridFor(10.0)))
    }

    @Test
    fun `bars reach the window edges at the grid tempo, through missing downbeats`() {
        // Beats tracked only from 110 s to 130 s of a 100..140 s window, and no downbeats at all.
        val sparse = grid(110.0, 41, withDownbeats = false)
        val lines = EnergyProfile.barLines(sparse, 100.0, 140.0)
        assertEquals(100.0, lines.first(), 0.25)
        assertEquals(140.0, lines.last(), 0.25)
        for (index in 1 until lines.size) assertEquals(bar, lines[index] - lines[index - 1], 1e-9)

        // Downbeats with two missing in the middle and one stray half a beat off: still one line per bar.
        val beats = DoubleArray(80) { 100.0 + 0.5 * it }
        val downbeats = (beats.filterIndexed { index, _ -> index % 4 == 0 && index !in setOf(32, 36) } + 120.5)
            .sorted().toDoubleArray()
        val withGaps = EnergyProfile.barLines(BeatGrid(beats, downbeats, 120.0, 0.9), 100.0, 140.0)
        for (index in 1 until withGaps.size) assertEquals(bar, withGaps[index] - withGaps[index - 1], 1e-9)
        assertNotNull(withGaps.firstOrNull { abs(it - 116.0) < 1e-9 })
    }
}
