package org.sonora.android.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sonora.android.cast.CastStreamServer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class EqualizerProcessorTest {
    private val rate = 48_000

    @After
    fun tearDown() {
        EqualizerCurve.current = EqualizerCurve.FLAT
    }

    @Test
    fun flatPassesTheAudioThroughBitForBit() {
        EqualizerCurve.current = EqualizerCurve(enabled = true, bandsDb = FloatArray(5), bassBoost = 0)
        val eq = configured()
        val input = sine(1_000.0, 0.5, 4_800)
        val expected = shorts(input.duplicate())
        val out = shorts(run(eq, input))
        assertArrayEquals(expected, out)
    }

    @Test
    fun aBoostedBandIsLouderAtItsFrequency() {
        // +6 dB at 910 Hz, settled: the 910 Hz tone comes out about 6 dB louder, minus the headroom.
        EqualizerCurve.current = EqualizerCurve(enabled = true, bandsDb = floatArrayOf(0f, 0f, 6f, 0f, 0f), bassBoost = 0)
        val eq = configured()
        repeat(10) { run(eq, sine(910.0, 0.1, 4_800)) }
        val out = run(eq, sine(910.0, 0.1, 4_800))
        val gainDb = 20 * log10(rms(out) / (0.1 / sqrt(2.0)))
        // 6 dB band, 3.6 dB preamp taken off: about +2.4 dB.
        assertEquals(2.4, gainDb, 0.6)
    }

    @Test
    fun aBandLeavesFarFrequenciesAlone() {
        EqualizerCurve.current = EqualizerCurve(enabled = true, bandsDb = floatArrayOf(0f, 0f, 0f, 0f, -12f), bassBoost = 0)
        val eq = configured()
        repeat(10) { run(eq, sine(100.0, 0.1, 4_800)) }
        val out = run(eq, sine(100.0, 0.1, 4_800))
        val gainDb = 20 * log10(rms(out) / (0.1 / sqrt(2.0)))
        assertEquals(0.0, gainDb, 0.3)
    }

    @Test
    fun loudBoostsAreRoundedOffNotClipped() {
        EqualizerCurve.current = EqualizerCurve(enabled = true, bandsDb = floatArrayOf(15f, 15f, 15f, 15f, 15f), bassBoost = 1000)
        val eq = configured()
        repeat(10) { run(eq, sine(60.0, 0.95, 4_800)) }
        val out = shorts(run(eq, sine(60.0, 0.95, 4_800)))
        assertTrue(out.none { it == Short.MAX_VALUE || it == Short.MIN_VALUE })
    }

    @Test
    fun turnedOffIsFlat() {
        EqualizerCurve.current = EqualizerCurve(enabled = false, bandsDb = floatArrayOf(9f, 9f, 9f, 9f, 9f), bassBoost = 800)
        assertTrue(EqualizerCurve.current.isFlat)
    }

    @Test
    fun castRangesAreRead() {
        assertEquals(0L to null, CastStreamServer.parseRange("bytes=0-"))
        assertEquals(1_000L to 1_999L, CastStreamServer.parseRange("bytes=1000-1999"))
        assertEquals(null, CastStreamServer.parseRange("bytes=-500"))
        assertEquals(null, CastStreamServer.parseRange(null))
        assertEquals(null, CastStreamServer.parseRange("bytes=9-3"))
    }

    @Test
    fun dacFormatsAreDescribed() {
        assertEquals("48 kHz · 16 bit", UsbDacOutput.describe(48_000, android.media.AudioFormat.ENCODING_PCM_16BIT))
        assertEquals("44.1 kHz · 24 bit", UsbDacOutput.describe(44_100, android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED).replace(',', '.'))
    }

    private fun configured() = EqualizerProcessor().apply {
        configure(AudioProcessor.AudioFormat(rate, 1, C.ENCODING_PCM_16BIT))
        flush()
    }

    private fun run(eq: EqualizerProcessor, input: ByteBuffer): ByteBuffer {
        eq.queueInput(input)
        return eq.getOutput()
    }

    private fun sine(freq: Double, amplitude: Double, frames: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(frames * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) buffer.putShort((sin(2 * PI * freq * i / rate) * amplitude * 32_767).toInt().toShort())
        buffer.flip()
        return buffer
    }

    private fun shorts(buffer: ByteBuffer): ShortArray {
        val copy = buffer.duplicate().order(ByteOrder.nativeOrder())
        return ShortArray(copy.remaining() / 2) { copy.getShort() }
    }

    private fun rms(buffer: ByteBuffer): Double {
        val s = shorts(buffer)
        // Skip the first part, where the filter is still settling on this buffer's start.
        val tail = s.drop(s.size / 4)
        return sqrt(tail.sumOf { (it / 32_768.0) * (it / 32_768.0) } / tail.size)
    }
}
