package org.sonora.android.playback.automix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AutomixTest {

    // ── Spectrogram ─────────────────────────────────────────────────────────

    @Test
    fun `one frame every hop, centred like torch stft`() {
        val second = FloatArray(LogMelSpectrogram.SAMPLE_RATE)
        val spectrogram = LogMelSpectrogram.compute(second)!!
        assertEquals(LogMelSpectrogram.SAMPLE_RATE / LogMelSpectrogram.HOP + 1, spectrogram.frames)
        assertEquals(50.0, LogMelSpectrogram.FRAME_RATE, 1e-9)
    }

    @Test
    fun `silence is zero after log1p`() {
        val spectrogram = LogMelSpectrogram.compute(FloatArray(8_000))!!
        assertTrue(spectrogram.values.all { it == 0f })
    }

    @Test
    fun `a 1 kHz tone lights the band centred on 1 kHz`() {
        val rate = LogMelSpectrogram.SAMPLE_RATE
        val tone = FloatArray(rate) { (0.5 * sin(2 * PI * 1_000.0 * it / rate)).toFloat() }
        val spectrogram = LogMelSpectrogram.compute(tone)!!
        val frame = spectrogram.frames / 2
        val row = (0 until LogMelSpectrogram.MELS).map { spectrogram.values[frame * LogMelSpectrogram.MELS + it] }
        val loudest = row.indices.maxBy { row[it] }

        val melMin = LogMelSpectrogram.hzToMel(30.0)
        val melMax = LogMelSpectrogram.hzToMel(11_000.0)
        val centre = LogMelSpectrogram.melToHz(melMin + (melMax - melMin) * (loudest + 1) / 129.0)
        assertEquals(1_000.0, centre, 60.0)
    }

    @Test
    fun `slaney mel scale round trips and bends at 1 kHz`() {
        listOf(30.0, 500.0, 1_000.0, 4_000.0, 11_000.0).forEach {
            assertEquals(it, LogMelSpectrogram.melToHz(LogMelSpectrogram.hzToMel(it)), 1e-6)
        }
        assertEquals(15.0, LogMelSpectrogram.hzToMel(1_000.0), 1e-9)
    }

    // ── Beat grid ───────────────────────────────────────────────────────────

    /** Logits with a beat every 25 frames (120 BPM at 50 fps) and a downbeat every fourth. */
    private fun logits(frames: Int, beatEvery: Int = 25, firstBeat: Int = 10): Pair<FloatArray, FloatArray> {
        val beat = FloatArray(frames) { -5f }
        val downbeat = FloatArray(frames) { -5f }
        var index = firstBeat
        var count = 0
        while (index < frames) {
            beat[index] = 4f
            if (index > 0) beat[index - 1] = 1f
            if (index + 1 < frames) beat[index + 1] = 1f
            if (count % 4 == 0) downbeat[index] = 3f
            index += beatEvery
            count++
        }
        return beat to downbeat
    }

    @Test
    fun `a steady pulse becomes a confident 120 BPM grid in 4-4`() {
        val (beat, downbeat) = logits(1_500)
        val grid = BeatGrid.fromLogits(beat, downbeat, 50.0, offsetSeconds = 100.0)!!

        assertEquals(120.0, grid.bpm, 0.01)
        assertEquals(100.2, grid.beats.first(), 0.001)
        assertEquals(4, grid.beatsPerBar)
        assertTrue(grid.confidence > 0.8)
        assertEquals(0.0, grid.barPhase(grid.downbeats[1]), 1e-9)
        assertEquals(1.0, grid.barPhase(grid.downbeats[1] + 0.5), 1e-9)
        assertEquals(2.5, grid.barPhase(grid.downbeats[1] + 1.25), 1e-9)
    }

    @Test
    fun `the phase keeps counting past the last tracked beat`() {
        val (beat, downbeat) = logits(1_500)
        val grid = BeatGrid.fromLogits(beat, downbeat, 50.0, 0.0)!!
        val after = grid.beats.last() + 10 * grid.beatSeconds
        assertEquals(grid.beats.size - 1 + 10.0, grid.beatPosition(after), 1e-9)
    }

    @Test
    fun `the fitted tempo sees through jitter and a dropped beat`() {
        val jitter = doubleArrayOf(0.012, -0.009, 0.004, -0.015, 0.008, 0.0, -0.006, 0.011)
        val beats = (0 until 60)
            .filter { it != 23 }
            .map { 1.0 + it * 60.0 / 124.0 + jitter[it % jitter.size] }
            .toDoubleArray()
        assertEquals(124.0, BeatGrid.tempo(beats), 0.05)
    }

    @Test
    fun `no pulse, no grid`() {
        assertNull(BeatGrid.fromLogits(FloatArray(1_500) { -3f }, FloatArray(1_500) { -3f }, 50.0, 0.0))
    }

    @Test
    fun `peaks are refined between frames`() {
        val logits = FloatArray(20) { -1f }
        logits[9] = 2f
        logits[10] = 3f
        logits[11] = 2.5f
        val peak = BeatGrid.pickPeaks(logits).single()
        assertTrue(peak > 10.0 && peak < 10.5)
    }

    @Test
    fun `phase error takes the short way round the bar`() {
        assertEquals(-0.5, BeatGrid.wrapCentered(3.5, 4.0), 1e-9)
        assertEquals(1.0, BeatGrid.wrapCentered(1.0, 4.0), 1e-9)
        assertEquals(0.25, BeatGrid.wrapCentered(-3.75, 4.0), 1e-9)
    }

    // ── Planner ─────────────────────────────────────────────────────────────

    private fun grid(bpm: Double, start: Double, seconds: Double, confidence: Double = 0.9): BeatGrid {
        val interval = 60.0 / bpm
        val beats = generateSequence(start) { it + interval }.takeWhile { it < start + seconds }.toList().toDoubleArray()
        val downbeats = beats.filterIndexed { index, _ -> index % 4 == 0 }.toDoubleArray()
        return BeatGrid(beats, downbeats, bpm, confidence)
    }

    @Test
    fun `close tempos mix on a downbeat, the incoming song stretched to match`() {
        val outgoing = grid(120.0, 150.5, 29.0)
        val incoming = grid(123.0, 0.8, 29.0)
        val plan = AutomixPlanner.plan(outgoing, 180_000L, incoming)!!

        assertTrue(outgoing.downbeats.any { abs(it * 1_000 - plan.exitMs) < 1 })
        assertEquals(800L, plan.entryMs)
        assertEquals(120f / 123f, plan.incomingRate, 1e-4f)
        assertEquals(8_000L, plan.mixMs)
        assertTrue(plan.exitMs + plan.mixMs <= 180_000L)
    }

    @Test
    fun `far apart tempos, weak grids and short songs are left to the crossfade`() {
        assertNull(AutomixPlanner.plan(grid(120.0, 150.5, 29.0), 180_000L, grid(140.0, 0.5, 29.0)))
        assertNull(AutomixPlanner.plan(grid(120.0, 150.5, 29.0), 180_000L, grid(121.0, 0.5, 29.0, confidence = 0.4)))
        assertNull(AutomixPlanner.plan(grid(120.0, 20.0, 29.0), 50_000L, grid(120.0, 0.5, 29.0)))
    }

    @Test
    fun `slow songs get a shorter mix so it stays under twenty seconds`() {
        val plan = AutomixPlanner.plan(grid(45.0, 150.0, 29.0), 180_000L, grid(46.0, 0.0, 29.0))
        assertNotNull(plan)
        assertEquals(10_667L, plan!!.mixMs)
    }

    private fun energy(outroStart: Double? = null, firstRise: Double? = null) =
        EnergyProfile(DoubleArray(0), DoubleArray(0), isCalm = false, outroStart = outroStart, firstRise = firstRise)

    /** 120 BPM, bars every 2 s from 120.5 and phrases every 16 s; the music ends at 179.5 s. */
    private val tail = grid(120.0, 120.5, 59.0)

    /** 123 BPM from 0.8 s, a bar every four beats. */
    private val head = grid(123.0, 0.8, 59.0)

    private fun headBeat(index: Int) = head.beats[index]

    @Test
    fun `without energy the exit snaps to the last phrase that leaves room`() {
        val plan = AutomixPlanner.plan(tail, 180_000L, head)!!

        assertEquals(168_500L, plan.exitMs)
        assertEquals(800L, plan.entryMs)
        assertEquals(16, plan.mixBeats)
        assertEquals(8_000L, plan.mixMs)
    }

    @Test
    fun `energy without markers plans like no energy at all`() {
        assertEquals(
            AutomixPlanner.plan(tail, 180_000L, head),
            AutomixPlanner.plan(tail, 180_000L, head, energy(), energy())
        )
    }

    @Test
    fun `the mix starts where a long outro starts, even off the phrase`() {
        val plan = AutomixPlanner.plan(tail, 180_000L, head, outgoingEnergy = energy(outroStart = 164.6))!!

        assertEquals(164_500L, plan.exitMs)
        assertEquals(16, plan.mixBeats)
    }

    @Test
    fun `a short outro ends the mix with the music`() {
        val plan = AutomixPlanner.plan(tail, 180_000L, head, outgoingEnergy = energy(outroStart = 176.5))!!

        assertEquals(170_500L, plan.exitMs)
        assertTrue(plan.exitMs + plan.mixMs <= 179_500L)
        assertTrue(plan.exitMs + plan.mixMs >= 179_500L - 2_000L)
    }

    @Test
    fun `the first rise lands as the outgoing song leaves`() {
        val rise = headBeat(48)
        val plan = AutomixPlanner.plan(tail, 180_000L, head, incomingEnergy = energy(firstRise = rise))!!

        assertEquals(16, plan.mixBeats)
        assertTrue(head.downbeats.any { abs(it * 1_000 - plan.entryMs) < 1 })
        val mixEnd = plan.entryMs / 1_000.0 + plan.mixBeats * head.beatSeconds
        assertEquals(rise, mixEnd, head.beatSeconds)
    }

    @Test
    fun `an early rise shortens the mix, then gives up on landing it`() {
        val shorter = AutomixPlanner.plan(tail, 180_000L, head, incomingEnergy = energy(firstRise = headBeat(12)))!!
        assertEquals(8, shorter.mixBeats)
        assertEquals(headBeat(4) * 1_000, shorter.entryMs.toDouble(), 1.0)

        val fallback = AutomixPlanner.plan(tail, 180_000L, head, incomingEnergy = energy(firstRise = headBeat(4)))!!
        assertEquals(16, fallback.mixBeats)
        assertEquals(800L, fallback.entryMs)
    }

    @Test
    fun `a whole phrase is mixed when both songs have room`() {
        val plan = AutomixPlanner.plan(
            tail, 180_000L, head,
            outgoingEnergy = energy(outroStart = 152.5),
            incomingEnergy = energy(firstRise = headBeat(64))
        )!!

        assertEquals(32, plan.mixBeats)
        assertEquals(16_000L, plan.mixMs)
        assertEquals(152_500L, plan.exitMs)
        assertEquals(headBeat(32) * 1_000, plan.entryMs.toDouble(), 1.0)
    }

    @Test
    fun `a short outro or intro keeps the mix to half a phrase`() {
        val shortOutro = AutomixPlanner.plan(
            tail, 180_000L, head,
            outgoingEnergy = energy(outroStart = 170.5),
            incomingEnergy = energy(firstRise = headBeat(64))
        )!!
        assertEquals(16, shortOutro.mixBeats)
        assertEquals(170_500L, shortOutro.exitMs)

        val shortIntro = AutomixPlanner.plan(
            tail, 180_000L, head,
            outgoingEnergy = energy(outroStart = 152.5),
            incomingEnergy = energy(firstRise = headBeat(24))
        )!!
        assertEquals(16, shortIntro.mixBeats)
        assertEquals(152_500L, shortIntro.exitMs)
    }

    @Test
    fun `the exit stays inside the tracked beats`() {
        val plan = AutomixPlanner.plan(tail, 180_000L, head, outgoingEnergy = energy(outroStart = 60.0))!!
        assertEquals(120_500L, plan.exitMs)
    }

    @Test
    fun `energy does not rescue songs the planner refuses`() {
        val outro = energy(outroStart = 160.5)
        val rise = energy(firstRise = 20.0)
        assertNull(AutomixPlanner.plan(tail, 180_000L, grid(140.0, 0.5, 59.0), outro, rise))
        assertNull(AutomixPlanner.plan(tail, 180_000L, grid(121.0, 0.5, 59.0, confidence = 0.4), outro, rise))
        assertNull(AutomixPlanner.plan(grid(120.0, 20.0, 29.0), 50_000L, head, outro, rise))
    }

    // ── Resampler ───────────────────────────────────────────────────────────

    @Test
    fun `resampling keeps pitch and level`() {
        val from = 44_100
        val to = LogMelSpectrogram.SAMPLE_RATE
        val input = FloatArray(from) { (0.8 * sin(2 * PI * 440.0 * it / from)).toFloat() }
        val output = Resampler.resample(input, from, to)

        assertEquals(to, output.size)
        val middle = output.copyOfRange(2_000, to - 2_000)
        val crossings = (1 until middle.size).count { middle[it - 1] < 0f && middle[it] >= 0f }
        assertEquals(440.0 * middle.size / to, crossings.toDouble(), 2.0)
        assertEquals(0.8f, middle.max(), 0.02f)
    }

    @Test
    fun `tones above the new nyquist are filtered out`() {
        val from = 44_100
        val input = FloatArray(from) { (0.8 * sin(2 * PI * 15_000.0 * it / from)).toFloat() }
        val output = Resampler.resample(input, from, LogMelSpectrogram.SAMPLE_RATE)
        val middle = output.copyOfRange(2_000, output.size - 2_000)
        assertTrue(middle.maxOf { abs(it) } < 0.05f)
    }
}
