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
    fun `bass swap starts with the incoming bass cut and ends with the outgoing song gone`() {
        val start = TransitionCurves.frame(TransitionStyle.BASS_SWAP, 0f, outgoingGain = 0.5f)
        assertEquals(0f, start.incoming.volume, 1e-6f)
        assertEquals(DjFilterMath.BASS_KILL_DB, start.incoming.filter.bassDb, 1e-6f)
        assertEquals(0f, start.outgoing.filter.bassDb, 1e-6f)
        // The hand-off itself never jumps in level.
        assertEquals(1f, start.outgoing.filter.gain, 1e-6f)

        val end = TransitionCurves.frame(TransitionStyle.BASS_SWAP, 1f, outgoingGain = 0.5f)
        assertEquals(0f, end.outgoing.volume, 1e-6f)
        assertEquals(1f, end.incoming.volume, 1e-6f)
        assertEquals(0f, end.incoming.filter.bassDb, 1e-6f)
        assertEquals(0.5f, end.outgoing.filter.gain, 1e-6f)
    }

    @Test
    fun `bass lines change places only across the swap and keep their sum`() {
        val swapAt = 0.5f
        val width = 0.0625f
        val before = TransitionCurves.frame(TransitionStyle.BASS_SWAP, swapAt - 0.01f, swapAt = swapAt, swapWidth = width)
        assertEquals(0f, before.outgoing.filter.bassDb, 1e-6f)
        assertEquals(DjFilterMath.BASS_KILL_DB, before.incoming.filter.bassDb, 1e-6f)
        assertEquals(1f, before.outgoing.volume, 1e-6f)

        val middle = TransitionCurves.frame(TransitionStyle.BASS_SWAP, swapAt + width / 2, swapAt = swapAt, swapWidth = width)
        val power = gain(middle.outgoing.filter.bassDb) * gain(middle.outgoing.filter.bassDb) +
            gain(middle.incoming.filter.bassDb) * gain(middle.incoming.filter.bassDb)
        assertEquals(1.0, power, 0.01)

        val after = TransitionCurves.frame(TransitionStyle.BASS_SWAP, swapAt + width + 0.01f, swapAt = swapAt, swapWidth = width)
        assertEquals(DjFilterMath.BASS_KILL_DB, after.outgoing.filter.bassDb, 1e-6f)
        assertEquals(0f, after.incoming.filter.bassDb, 1e-6f)
        assertTrue(after.outgoing.volume < 1f)
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
