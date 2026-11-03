package org.sonora.android.playback.automix

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class DjMixerTest {

    private val rate = 44_100.0

    // ── Filter design ───────────────────────────────────────────────────────

    @Test
    fun `bass kill cuts the kick and leaves the vocals`() {
        val shelf = DjFilterMath.lowShelf(rate, DjFilterMath.BASS_HZ, DjFilterMath.BASS_KILL_DB.toDouble())
        assertTrue(DjFilterMath.magnitudeDb(shelf, 50.0, rate) < -25)
        assertTrue(DjFilterMath.magnitudeDb(shelf, 2_000.0, rate) > -1)
    }

    @Test
    fun `filters are flat in their pass band and -3 dB at the corner`() {
        val highPass = DjFilterMath.highPass(rate, 1_000.0)
        val lowPass = DjFilterMath.lowPass(rate, 1_000.0)
        assertEquals(-3.0, DjFilterMath.magnitudeDb(highPass, 1_000.0, rate), 0.1)
        assertEquals(-3.0, DjFilterMath.magnitudeDb(lowPass, 1_000.0, rate), 0.1)
        assertEquals(0.0, DjFilterMath.magnitudeDb(highPass, 15_000.0, rate), 0.1)
        assertEquals(0.0, DjFilterMath.magnitudeDb(lowPass, 50.0, rate), 0.1)
        assertTrue(DjFilterMath.magnitudeDb(highPass, 100.0, rate) < -35)
        assertTrue(DjFilterMath.magnitudeDb(lowPass, 10_000.0, rate) < -35)
    }

    @Test
    fun `zero dB shelf changes nothing`() {
        val shelf = DjFilterMath.lowShelf(rate, DjFilterMath.BASS_HZ, 0.0)
        for (freq in listOf(30.0, 180.0, 1_000.0, 12_000.0)) {
            assertEquals(0.0, DjFilterMath.magnitudeDb(shelf, freq, rate), 1e-9)
        }
    }

    // ── Loudness ────────────────────────────────────────────────────────────

    @Test
    fun `a 1 kHz sine at full scale reads about -3 LUFS, and 6 dB down reads 6 dB less`() {
        val sampleRate = 22_050
        val loud = sine(1_000.0, sampleRate, 5.0, 1.0)
        val quiet = sine(1_000.0, sampleRate, 5.0, 0.5)
        val loudLufs = Loudness.lufs(loud, sampleRate)!!
        assertEquals(-3.0, loudLufs, 0.3)
        assertEquals(6.02, loudLufs - Loudness.lufs(quiet, sampleRate)!!, 0.05)
    }

    @Test
    fun `silence and too short audio have no loudness`() {
        assertNull(Loudness.lufs(FloatArray(22_050), 22_050))
        assertNull(Loudness.lufs(FloatArray(100), 22_050))
    }

    @Test
    fun `level match is limited and missing data changes nothing`() {
        assertEquals(-3.0, Loudness.matchGainDb(-8.0, -11.0), 1e-9)
        assertEquals(Loudness.MAX_CUT_DB, Loudness.matchGainDb(-5.0, -30.0), 1e-9)
        assertEquals(Loudness.MAX_BOOST_DB, Loudness.matchGainDb(-30.0, -5.0), 1e-9)
        assertEquals(0.0, Loudness.matchGainDb(null, -10.0), 1e-9)
    }

    // ── Transition curves ───────────────────────────────────────────────────

    @Test
    fun `bass swap keeps both songs up, the incoming one without bass until the last downbeat`() {
        val start = TransitionCurves.frame(TransitionStyle.BASS_SWAP, 0f, outgoingGain = 0.5f)
        assertEquals(0f, start.incoming.volume, 1e-6f)
        assertEquals(TransitionCurves.LOW_CUT_DB, start.incoming.filter.bassDb, 1e-6f)
        assertEquals(0f, start.outgoing.filter.bassDb, 1e-6f)
        // The hand-off itself never jumps in level.
        assertEquals(1f, start.outgoing.filter.gain, 1e-6f)

        val width = 1f / 64
        for (p in listOf(0.3f, 0.6f, 0.9f, 1f - width - 0.001f)) {
            val frame = TransitionCurves.frame(TransitionStyle.BASS_SWAP, p, swapWidth = width)
            assertEquals("outgoing at $p", 1f, frame.outgoing.volume, 1e-6f)
            assertEquals("outgoing bass at $p", 0f, frame.outgoing.filter.bassDb, 1e-6f)
            assertEquals("incoming bass at $p", TransitionCurves.LOW_CUT_DB, frame.incoming.filter.bassDb, 1e-6f)
        }
        assertEquals(1f, TransitionCurves.frame(TransitionStyle.BASS_SWAP, 0.6f).incoming.volume, 1e-6f)

        val middle = TransitionCurves.frame(TransitionStyle.BASS_SWAP, 1f - width / 2, swapWidth = width)
        val power = gain(middle.outgoing.filter.bassDb) * gain(middle.outgoing.filter.bassDb) +
            gain(middle.incoming.filter.bassDb) * gain(middle.incoming.filter.bassDb)
        assertEquals(1.0, power, 0.01)

        val end = TransitionCurves.frame(TransitionStyle.BASS_SWAP, 1f, outgoingGain = 0.5f)
        assertEquals(0f, end.outgoing.volume, 1e-6f)
        assertEquals(1f, end.incoming.volume, 1e-6f)
        assertEquals(DjFilterSettings.NEUTRAL, end.incoming.filter)
        assertEquals(0.5f, end.outgoing.filter.gain, 1e-6f)
    }

    @Test
    fun `eq swap hands over the highs on the quarter, then mids and bass in the middle`() {
        val width = 1f / 64
        val early = TransitionCurves.frame(TransitionStyle.EQ_SWAP, 0.2f, swapWidth = width)
        assertEquals(TransitionCurves.LOW_CUT_DB, early.incoming.filter.bassDb, 1e-6f)
        assertEquals(TransitionCurves.MID_HIGH_CUT_DB, early.incoming.filter.midDb, 1e-6f)
        assertEquals(TransitionCurves.MID_HIGH_CUT_DB, early.incoming.filter.highDb, 1e-6f)
        assertEquals(0f, early.outgoing.filter.highDb, 1e-6f)

        val quarter = TransitionCurves.frame(TransitionStyle.EQ_SWAP, 0.25f + width + 0.001f, swapWidth = width)
        assertEquals(0f, quarter.incoming.filter.highDb, 1e-6f)
        assertEquals(TransitionCurves.MID_HIGH_CUT_DB, quarter.outgoing.filter.highDb, 1e-6f)
        assertEquals(TransitionCurves.MID_HIGH_CUT_DB, quarter.incoming.filter.midDb, 1e-6f)
        assertEquals(0f, quarter.outgoing.filter.bassDb, 1e-6f)

        val late = TransitionCurves.frame(TransitionStyle.EQ_SWAP, 0.75f, swapWidth = width)
        assertEquals(0f, late.incoming.filter.bassDb, 1e-6f)
        assertEquals(0f, late.incoming.filter.midDb, 1e-6f)
        assertEquals(TransitionCurves.LOW_CUT_DB, late.outgoing.filter.bassDb, 1e-6f)
        assertEquals(TransitionCurves.MID_HIGH_CUT_DB, late.outgoing.filter.midDb, 1e-6f)
        // Both faders stay up through the mix; the outgoing one goes on the last beat.
        assertEquals(1f, late.outgoing.volume, 1e-6f)
        assertEquals(1f, late.incoming.volume, 1e-6f)
    }

    @Test
    fun `filter sweeps resonate while they move and are flat at rest`() {
        val flat = DjFilterMath.FLAT_Q.toFloat()
        val sweeping = TransitionCurves.frame(TransitionStyle.BASS_SWAP, 0.4f).incoming.filter
        assertTrue(sweeping.resonance > 2f)
        assertTrue(sweeping.lowPassHz in 1_000f..20_000f)
        assertEquals(flat, TransitionCurves.frame(TransitionStyle.BASS_SWAP, 0.6f).incoming.filter.resonance, 1e-6f)
        assertTrue(TransitionCurves.frame(TransitionStyle.BASS_SWAP, 0.9f).outgoing.filter.resonance > 2f)
        assertEquals(flat, TransitionCurves.frame(TransitionStyle.BASS_SWAP, 0.3f).outgoing.filter.resonance, 1e-6f)
    }

    @Test
    fun `beat-matched styles fall back to the filter when the beats do not line up`() {
        assertEquals(TransitionStyle.FILTER, TransitionCurves.resolve(MixStyle.BASS_SWAP, beatMatched = false, calm = false))
        assertEquals(TransitionStyle.FILTER, TransitionCurves.resolve(MixStyle.EQ_SWAP, beatMatched = false, calm = false))
        assertEquals(TransitionStyle.EQ_SWAP, TransitionCurves.resolve(MixStyle.EQ_SWAP, beatMatched = true, calm = true))
    }

    @Test
    fun `fade is the equal-power crossfade with the level match`() {
        val middle = TransitionCurves.frame(TransitionStyle.FADE, 0.5f, outgoingGain = 2f)
        assertEquals(1f, middle.outgoing.volume * middle.outgoing.volume + middle.incoming.volume * middle.incoming.volume, 1e-5f)
        assertEquals(2f, middle.outgoing.filter.gain, 1e-6f)
        assertEquals(DjFilterSettings.NEUTRAL, middle.incoming.filter)
    }

    // ── Processor ───────────────────────────────────────────────────────────

    @Test
    fun `at rest the deck passes audio through bit for bit`() {
        val deck = configured()
        val input = pcm16(sine(440.0, rate.toInt(), 0.2, 0.5))
        val output = run(deck, input)
        assertEquals(input.toList(), output.toList())
    }

    @Test
    fun `a bass kill silences a low tone and keeps a high one`() {
        val deck = configured()
        deck.set(DjFilterSettings(bassDb = DjFilterMath.BASS_KILL_DB))
        val low = run(deck, pcm16(sine(50.0, rate.toInt(), 1.0, 0.5)))
        assertTrue(rms(low.copyOfRange(low.size / 2, low.size)) < 0.5 / sqrt(2.0) * 0.06)

        val high = run(deck, pcm16(sine(3_000.0, rate.toInt(), 1.0, 0.5)))
        assertEquals(0.5 / sqrt(2.0), rms(high.copyOfRange(high.size / 2, high.size)), 0.02)
    }

    @Test
    fun `mid and high cuts leave the other bands alone`() {
        val deck = configured()
        deck.set(DjFilterSettings(midDb = -12f, highDb = -12f))
        val low = run(deck, pcm16(sine(60.0, rate.toInt(), 1.0, 0.5)))
        assertEquals(0.5 / sqrt(2.0), rms(low.copyOfRange(low.size / 2, low.size)), 0.03)

        val deckHigh = configured()
        deckHigh.set(DjFilterSettings(highDb = -12f))
        val high = run(deckHigh, pcm16(sine(10_000.0, rate.toInt(), 1.0, 0.5)))
        assertTrue(rms(high.copyOfRange(high.size / 2, high.size)) < 0.5 / sqrt(2.0) * 0.35)
    }

    @Test
    fun `a resonant high-pass peaks at its corner and stays stable`() {
        val resonant = DjFilterMath.highPass(rate, 1_000.0, 2.5)
        assertTrue(DjFilterMath.magnitudeDb(resonant, 1_000.0, rate) > 6)
        val deck = configured()
        deck.set(DjFilterSettings(highPassHz = 2_000f, resonance = 2.5f))
        val out = run(deck, pcm16(sine(2_000.0, rate.toInt(), 1.0, 0.2)))
        assertTrue(out.all { it.isFinite() && abs(it) <= 1.0 })
    }

    @Test
    fun `gain glides instead of jumping`() {
        val deck = configured()
        deck.set(DjFilterSettings(gain = 0f))
        val out = run(deck, pcm16(FloatArray(8_820) { 0.5f }))
        // The first samples are still close to the input, the last ones close to silence.
        assertTrue(abs(out[0]) > 0.4)
        assertTrue(abs(out.last()) < 0.01)
    }

    private fun configured(): DjFilterProcessor = DjFilterProcessor().apply {
        val format = AudioProcessor.AudioFormat(rate.toInt(), 1, C.ENCODING_PCM_16BIT)
        assertNotNull(configure(format))
        flush()
    }

    private fun run(deck: DjFilterProcessor, input: ByteBuffer): DoubleArray {
        deck.queueInput(input)
        val out = deck.getOutput()
        return DoubleArray(out.remaining() / 2) { out.getShort() / 32_768.0 }
    }

    private fun ByteBuffer.toList(): List<Short> {
        val copy = duplicate().order(ByteOrder.nativeOrder())
        copy.rewind()
        return List(copy.remaining() / 2) { copy.getShort() }
    }

    private fun DoubleArray.toList(): List<Short> = map { (it * 32_768.0).toInt().toShort() }

    private fun pcm16(samples: FloatArray): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        for (s in samples) buffer.putShort((s * 32_767f).toInt().toShort())
        buffer.flip()
        return buffer
    }

    private fun sine(freq: Double, sampleRate: Int, seconds: Double, amplitude: Double) =
        FloatArray((seconds * sampleRate).toInt()) { (amplitude * sin(2 * PI * freq * it / sampleRate)).toFloat() }

    private fun rms(values: DoubleArray) = sqrt(values.sumOf { it * it } / values.size)

    private fun gain(db: Float) = DjFilterMath.dbToGain(db.toDouble())
}
