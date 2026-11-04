package org.sonora.android.playback.automix

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.google.gson.Gson
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

class TransitionEffectsTest {

    private val rate = 44_100

    // ── Curves ──────────────────────────────────────────────────────────────

    @Test
    fun `every effect starts silent, only repeats what was heard and rings out`() {
        val beat = 1f / 64
        for (curve in EffectCurve.entries) {
            val start = TransitionEffects.effect(curve, 0f, beat)
            assertEquals("$curve dry at start", 1f, start.dry, 1e-6f)
            assertEquals("$curve send at start", 0f, start.send, 1e-6f)
            assertEquals("$curve noise at start", 0f, start.noise, 1e-6f)
            val end = TransitionEffects.effect(curve, 1f, beat)
            assertEquals("$curve send at the end", 0f, end.send, 1e-6f)
            assertEquals("$curve noise at the end", 0f, end.noise, 1e-6f)
            if (curve.kind != EffectCurve.Kind.NOISE && curve != EffectCurve.NONE) {
                assertEquals("$curve dry at the end", 0f, end.dry, 1e-6f)
                // The send closes no later than the song's own level.
                for (i in 0..200) {
                    val f = TransitionEffects.effect(curve, i / 200f, beat)
                    assertTrue("$curve sends after its dry is gone at ${i / 200f}", f.dry > 1e-3f || f.send < 1e-3f || curve.ending == EffectCurve.Ending.OUT_END)
                }
                val tail = 0.25f
                assertEquals("$curve wet at the end of its tail", 0f, TransitionEffects.effect(curve, 1f + tail, beat, tail).wet, 1e-4f)
                assertEquals("$curve wet early in its tail", 1f, TransitionEffects.effect(curve, 1f + tail * 0.3f, beat, tail).wet, 1e-6f)
            }
        }
    }

    @Test
    fun `reverb out center is gone by the middle, echo cut keeps the song until the last beat`() {
        val beat = 1f / 32
        assertEquals(0f, TransitionEffects.effect(EffectCurve.REVERB_OUT_CENTER, 0.5f, beat).dry, 1e-6f)
        assertTrue(TransitionEffects.effect(EffectCurve.REVERB_OUT_CENTER, 0.42f, beat).send > 0.9f)
        val beforeCut = TransitionEffects.effect(EffectCurve.ECHO_1_CUT, 1f - beat - 0.001f, beat)
        assertEquals(1f, beforeCut.dry, 1e-6f)
        assertEquals(1f, beforeCut.send, 1e-6f)
        assertEquals(0f, TransitionEffects.effect(EffectCurve.ECHO_1_CUT, 1f - beat, beat).dry, 1e-6f)
    }

    @Test
    fun `a record stops from its own speed, a spinback goes backwards and both fall silent`() {
        for (curve in JogwheelCurve.entries.filter { it != JogwheelCurve.NONE }) {
            assertEquals("$curve start", 1.0, TransitionEffects.jogRate(curve, 0.0), 1e-9)
            assertEquals("$curve end", 0.0, TransitionEffects.jogRate(curve, 1.0), 1e-9)
            assertEquals("$curve silent at the end", 0.0, TransitionEffects.jogLevel(curve, 1.0), 1e-9)
            assertEquals("$curve full at the start", 1.0, TransitionEffects.jogLevel(curve, 0.0), 1e-9)
        }
        var previous = 1.0
        for (i in 0..100) {
            val r = TransitionEffects.jogRate(JogwheelCurve.VINYL_STOP_FAST_2, i / 100.0)
            assertTrue(r <= previous + 1e-12)
            previous = r
        }
        assertTrue(TransitionEffects.jogRate(JogwheelCurve.SPINBACK_2, 0.2) < -1.0)
        val (start, length) = TransitionEffects.jogSpan(JogwheelCurve.SPINBACK_4, 1f / 64)!!
        assertEquals(1f - 4f / 64, start, 1e-6f)
        assertEquals(4f / 64, length, 1e-6f)
        // Longer than the mix, it takes the whole mix.
        assertEquals(0f, TransitionEffects.jogSpan(JogwheelCurve.VINYL_STOP_SLOW, 1f / 4)!!.first, 1e-6f)
    }

    @Test
    fun `a loop holds from the start of the mix and a loop roll halves over the last two bars`() {
        assertNull(TransitionEffects.loop(LoopCurve.FOUR_BEAT, -0.01f, 64.0))
        assertEquals(4.0, TransitionEffects.loop(LoopCurve.FOUR_BEAT, 0.5f, 64.0)!!.lengthBeats, 1e-9)
        assertEquals(0.0, TransitionEffects.loop(LoopCurve.FOUR_BEAT, 0.5f, 64.0)!!.anchorBeats, 1e-9)
        assertNull(TransitionEffects.loop(LoopCurve.HALVING, 0.5f, 64.0))
        val lengths = listOf(56.5, 60.5, 62.5, 63.2, 63.7, 63.99).map {
            TransitionEffects.loop(LoopCurve.HALVING, (it / 64).toFloat(), 64.0)!!.lengthBeats
        }
        assertEquals(listOf(2.0, 1.0, 0.5, 0.25, 0.125, 0.125), lengths)
        assertEquals(56.0, TransitionEffects.loopAnchorBeats(LoopCurve.HALVING, 64.0), 1e-9)
    }

    @Test
    fun `a custom transition saved before the effects existed loads without any`() {
        val old = Gson().fromJson("""{"volume":"OVERLAP","eq":"THREE_BAND"}""", MixRecipe::class.java)
        val repaired = old.repaired()
        assertEquals(EffectCurve.NONE, repaired.effect)
        assertEquals(JogwheelCurve.NONE, repaired.jogwheel)
        assertEquals(LoopCurve.NONE, repaired.loop)
        assertEquals(EqCurve.THREE_BAND, repaired.eq)
        assertTrue(!repaired.hasEffects)
        assertTrue(MixRecipe(VolumeCurve.OVERLAP, loop = LoopCurve.ONE_BEAT).hasEffects)
    }

    // ── The deck playing them ───────────────────────────────────────────────

    @Test
    fun `the deck knows which moment of the song it processes`() {
        val deck = configured()
        assertNull(deck.leadMs(0L))
        deck.stamp(10_000_000L)
        run(deck, FloatArray(rate))
        // One second processed from 10 s: heard at 10.5 s, the deck is half a second ahead.
        assertEquals(500L, deck.leadMs(10_500L))
        // Ahead by more than any output buffer holds: a clock gone wrong.
        assertNull(deck.leadMs(0L))
    }

    @Test
    fun `an echo cut leaves the last beat ringing at the beat's distance`() {
        val deck = configured()
        // 16 beats of 500 ms; the cut falls on the last beat, at 7.5 s.
        val recipe = MixRecipe(VolumeCurve.OVERLAP, effect = EffectCurve.ECHO_1_CUT)
        deck.schedule(DeckSchedule(recipe, startMs = 0.0, mixMs = 8_000.0, beatMs = 500.0, swapWidth = 1f / 16))
        val input = FloatArray(rate * 10)
        burst(input, at = 7.4, seconds = 0.01)
        deck.stamp(0L)
        val out = run(deck, input)
        val heard = peak(out, 7.395, 7.42)
        assertTrue("the burst itself is heard: $heard", heard > 0.4)
        assertTrue("silence between the cut and the first echo", peak(out, 7.6, 7.88) < 0.01)
        val first = peak(out, 7.895, 7.92)
        val second = peak(out, 8.395, 8.42)
        assertTrue("first echo one beat later: $first", first > 0.3)
        assertTrue("echoes die away: $first then $second", second in 0.01..(first * 0.8))
        assertTrue("nothing else of the song", peak(out, 8.0, 8.38) < 0.02)
    }

    @Test
    fun `a beat loop repeats the same beat`() {
        val deck = configured()
        val beatMs = 100.0
        val recipe = MixRecipe(VolumeCurve.OVERLAP, loop = LoopCurve.ONE_BEAT)
        deck.schedule(DeckSchedule(recipe, startMs = 1_000.0, mixMs = 2_000.0, beatMs = beatMs))
        val input = FloatArray(rate * 3) { (0.5 * sin(2 * PI * 97.0 * it / rate) * (1 + it % 7) / 7).toFloat() }
        deck.stamp(0L)
        val out = run(deck, input)
        val beat = (beatMs * rate / 1_000).toInt()
        val loopStart = rate
        val cross = (0.004 * rate).toInt()
        var worst = 0.0
        for (repeat in 1..5) {
            for (i in 0 until beat - cross) {
                worst = maxOf(worst, abs(out[loopStart + repeat * beat + i] - out[loopStart + i]))
            }
        }
        assertTrue("each repeat is the first beat again: off by $worst", worst < 1e-4)
        // Before the loop the song plays as it is.
        assertEquals(input[loopStart - 100].toDouble(), out[loopStart - 100], 1e-4)
    }

    @Test
    fun `a vinyl stop slows the pitch down to silence`() {
        val deck = configured()
        val recipe = MixRecipe(VolumeCurve.OVERLAP, jogwheel = JogwheelCurve.VINYL_STOP_FAST_4)
        // A 4-beat stop over the last second of a 4 s mix.
        deck.schedule(DeckSchedule(recipe, startMs = 0.0, mixMs = 4_000.0, beatMs = 250.0, swapWidth = 0.005f))
        val input = FloatArray(rate * 5) { (0.5 * sin(2 * PI * 440.0 * it / rate)).toFloat() }
        deck.stamp(0L)
        val out = run(deck, input)
        val before = crossings(out, 2.7, 2.9)
        val early = crossings(out, 3.0, 3.2)
        val late = crossings(out, 3.6, 3.8)
        assertTrue("440 Hz before the stop: $before", abs(before - 0.2 * 880) < 6)
        assertTrue("slower and slower: $early then $late", late < early && early < before)
        assertTrue("silent once stopped", peak(out, 4.05, 4.5) < 1e-3)
    }

    @Test
    fun `a spinback plays the song backwards`() {
        val deck = configured()
        val recipe = MixRecipe(VolumeCurve.OVERLAP, jogwheel = JogwheelCurve.SPINBACK_4)
        deck.schedule(DeckSchedule(recipe, startMs = 0.0, mixMs = 4_000.0, beatMs = 250.0, swapWidth = 0.005f))
        // A slow rising ramp: played backwards it falls.
        val input = FloatArray(rate * 5) { (0.8 * it / (rate * 5.0)).toFloat() }
        deck.stamp(0L)
        val out = run(deck, input)
        val a = out[(3.2 * rate).toInt()]
        val b = out[(3.4 * rate).toInt()]
        assertTrue("backwards: $a then $b", b < a)
        assertTrue("silent once stopped", peak(out, 4.05, 4.5) < 1e-3)
        assertTrue(out.all { it.isFinite() })
    }

    @Test
    fun `the reverb washes at about the song's level and dies away`() {
        val reverb = Reverb(1, rate.toDouble())
        val random = NoiseSource()
        val noise = DoubleArray(rate * 2) { random.next() * 0.3 }
        val wet = DoubleArray(noise.size) { reverb.process(0, noise[it]) }
        val ratio = rms(wet.copyOfRange(rate, wet.size)) / rms(noise)
        assertTrue("wet level $ratio of the dry one", ratio in 0.3..1.5)

        val impulse = Reverb(1, rate.toDouble())
        val response = DoubleArray(rate * 4) { impulse.process(0, if (it == 0) 1.0 else 0.0) }
        val early = rms(response.copyOfRange((0.1 * rate).toInt(), (0.3 * rate).toInt()))
        val late = rms(response.copyOfRange((3.5 * rate).toInt(), (4 * rate).toInt()))
        assertTrue("rings: $early", early > 1e-4)
        assertTrue("and dies away: $late vs $early", late < early * 0.05)
    }

    @Test
    fun `without a schedule the deck still passes the song through untouched`() {
        val deck = configured()
        deck.stamp(0L)
        val input = FloatArray(4_410) { (0.3 * sin(2 * PI * 220.0 * it / rate)).toFloat() }
        val out = run(deck, input)
        for (i in input.indices) assertEquals(input[i].toDouble(), out[i], 1e-7)
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun configured(): DjFilterProcessor = DjFilterProcessor().apply {
        assertNotNull(configure(AudioProcessor.AudioFormat(rate, 1, C.ENCODING_PCM_FLOAT)))
        flush()
    }

    /** Feeds [samples] in buffers the size a decoder hands over, as the sink does. */
    private fun run(deck: DjFilterProcessor, samples: FloatArray): DoubleArray {
        val out = DoubleArray(samples.size)
        var at = 0
        while (at < samples.size) {
            val n = minOf(1_024, samples.size - at)
            val buffer = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
            for (i in 0 until n) buffer.putFloat(samples[at + i])
            buffer.flip()
            deck.queueInput(buffer)
            val result = deck.getOutput()
            for (i in 0 until n) out[at + i] = result.getFloat().toDouble()
            at += n
        }
        return out
    }

    private fun burst(into: FloatArray, at: Double, seconds: Double) {
        val from = (at * rate).toInt()
        for (i in 0 until (seconds * rate).toInt()) into[from + i] = (0.8 * sin(2 * PI * 1_000.0 * i / rate)).toFloat()
    }

    private fun peak(values: DoubleArray, from: Double, to: Double): Double =
        (((from * rate).toInt()) until ((to * rate).toInt())).maxOf { abs(values[it]) }

    private fun crossings(values: DoubleArray, from: Double, to: Double): Int {
        var count = 0
        for (i in ((from * rate).toInt() + 1) until (to * rate).toInt()) {
            if ((values[i - 1] < 0) != (values[i] < 0)) count++
        }
        return count
    }

    private fun rms(values: DoubleArray) = sqrt(values.sumOf { it * it } / values.size)
}
