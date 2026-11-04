package org.sonora.android.playback.automix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10

class TransitionCurvesTest {
    private val open = DjFilterMath.OPEN_HIGH_PASS_HZ.toFloat()

    @Test
    fun everyStyleStartsOnTheOutgoingSongAndEndsOnTheIncomingOne() {
        for (style in TransitionStyle.entries) {
            val start = TransitionCurves.frame(style, 0f, outgoingGain = 0.5f)
            assertEquals("$style start volume", 1f, start.outgoing.volume, 1e-4f)
            assertEquals("$style start gain", 1f, start.outgoing.filter.gain, 1e-4f)
            assertEquals("$style start bass", 0f, start.outgoing.filter.bassDb, 1e-4f)
            assertTrue("$style start high-pass", start.outgoing.filter.highPassHz <= open)
            assertEquals("$style start low-pass", 0f, start.outgoing.filter.lowPassHz)
            assertEquals("$style start incoming", 0f, start.incoming.volume, 1e-4f)

            val end = TransitionCurves.frame(style, 1f, outgoingGain = 0.5f)
            assertEquals("$style end outgoing", 0f, end.outgoing.volume, 1e-4f)
            assertEquals("$style end incoming", 1f, end.incoming.volume, 1e-4f)
            assertEquals("$style end incoming filter", DjFilterSettings.NEUTRAL, end.incoming.filter)
        }
    }

    @Test
    fun theLoudnessMatchReachesTheOutgoingDeckInEveryStyle() {
        for (style in TransitionStyle.entries) {
            val plain = TransitionCurves.frame(style, 0.3f).outgoing.filter.gain
            val matched = TransitionCurves.frame(style, 0.3f, outgoingGain = 0.5f).outgoing.filter.gain
            assertEquals("$style", plain * 0.5f, matched, 1e-4f)
        }
    }

    @Test
    fun curvesAreContinuousApartFromTheCuts() {
        val step = 0.002f
        for (style in TransitionStyle.entries) {
            var previous = TransitionCurves.frame(style, 0f)
            var p = step
            while (p <= 1f) {
                val now = TransitionCurves.frame(style, p)
                // RISE cuts on the middle downbeat; the full-level styles take the outgoing song out on the last beat.
                val inCut = when (style) {
                    TransitionStyle.RISE, TransitionStyle.SLAM ->
                        p > TransitionCurves.SWAP_AT - step && p < TransitionCurves.SWAP_AT + TransitionCurves.SWAP_WIDTH + step
                    TransitionStyle.BASS_SWAP, TransitionStyle.ROLL_DROP, TransitionStyle.EQ_SWAP, TransitionStyle.WAVE ->
                        p > 1f - TransitionCurves.SWAP_WIDTH - step
                    else -> false
                }
                val maxVolumeStep = if (inCut) 0.25f else 0.02f
                assertTrue("$style outgoing at $p", abs(now.outgoing.volume - previous.outgoing.volume) < maxVolumeStep)
                assertTrue("$style incoming at $p", abs(now.incoming.volume - previous.incoming.volume) < maxVolumeStep)
                assertTrue("$style gain at $p", abs(now.outgoing.filter.gain - previous.outgoing.filter.gain) < 0.01f)
                assertTrue("$style high-pass at $p", logStep(previous.outgoing.filter.highPassHz, now.outgoing.filter.highPassHz) < 0.05)
                assertTrue("$style low-pass at $p", logStep(lowPassOf(previous.incoming), lowPassOf(now.incoming)) < 0.05)
                previous = now
                p += step
            }
        }
    }

    @Test
    fun filterSweepsOnlyEverCloseOnTheOutgoingSongAndOpenOnTheIncomingOne() {
        var lastOutgoing = 0f
        var lastIncoming = Float.MAX_VALUE
        for (i in 0..100) {
            val frame = TransitionCurves.frame(TransitionStyle.FILTER, i / 100f)
            val outgoing = frame.outgoing.filter.highPassHz
            val incoming = frame.incoming.filter.highPassHz
            assertTrue("outgoing high-pass at $i", outgoing >= lastOutgoing)
            assertTrue("incoming high-pass at $i", incoming <= lastIncoming)
            lastOutgoing = outgoing
            lastIncoming = incoming
        }
        assertTrue(lastOutgoing in 8_000f..10_000f)
        assertEquals(0f, lastIncoming)

        // The incoming song starts thin, only its hats, and is open by the middle.
        assertTrue(TransitionCurves.frame(TransitionStyle.FILTER, 0.01f).incoming.filter.highPassHz > 5_000f)
        assertEquals(0f, TransitionCurves.frame(TransitionStyle.FILTER, 0.55f).incoming.filter.highPassHz)
        // The outgoing fader holds until the middle, then goes down.
        assertEquals(1f, TransitionCurves.frame(TransitionStyle.FILTER, 0.45f).outgoing.volume)
        assertTrue(TransitionCurves.frame(TransitionStyle.FILTER, 0.75f).outgoing.volume < 1f)
    }

    @Test
    fun theSumNeitherDipsNorPeaks() {
        // The filter peaks at +3 dB in the middle, where both songs are briefly open.
        for ((style, top) in listOf(TransitionStyle.FADE to 3f, TransitionStyle.FILTER to 3.1f)) {
            for (swapAt in listOf(0.4f, 0.5f, 0.6f)) {
                for (i in 0..20) {
                    val p = i / 20f
                    val db = perceivedDb(TransitionCurves.frame(style, p, swapAt = swapAt))
                    assertTrue("$style at $p: $db dB", db in -3f..top)
                }
            }
        }
    }

    @Test
    fun riseKeepsTheIncomingSongOutUntilTheDownbeat() {
        val swapAt = 0.6f
        val width = 0.05f
        for (i in 0..59) {
            val p = i / 100f
            val frame = TransitionCurves.frame(TransitionStyle.RISE, p, swapAt = swapAt, swapWidth = width)
            assertEquals("incoming at $p", 0f, frame.incoming.volume)
            assertEquals("outgoing at $p", 1f, frame.outgoing.volume, 1e-6f)
        }
        val build = TransitionCurves.frame(TransitionStyle.RISE, swapAt, swapAt = swapAt, swapWidth = width).outgoing.filter
        assertTrue(build.highPassHz in 800f..1_200f)
        assertTrue(build.gain > 1f && build.gain <= DjFilterMath.dbToGain(1.6).toFloat())

        for (i in 66..100) {
            val p = i / 100f
            val frame = TransitionCurves.frame(TransitionStyle.RISE, p, swapAt = swapAt, swapWidth = width)
            assertEquals("incoming at $p", 1f, frame.incoming.volume, 1e-6f)
            assertEquals("incoming filter at $p", DjFilterSettings.NEUTRAL, frame.incoming.filter)
            assertEquals("outgoing at $p", 0f, frame.outgoing.volume, 1e-6f)
        }
    }

    @Test
    fun outOfRangeSwapPointsAreClamped() {
        for (style in TransitionStyle.entries) {
            for ((swapAt, width) in listOf(-1f to 0f, 0f to -1f, 2f to 2f, 0.99f to 0.5f)) {
                for (i in 0..20) {
                    val frame = TransitionCurves.frame(style, i / 20f, swapAt = swapAt, swapWidth = width)
                    for (deck in listOf(frame.outgoing, frame.incoming)) {
                        assertTrue("$style $swapAt/$width", deck.volume.isFinite() && deck.volume in -1e-4f..1.0001f)
                        assertTrue("$style $swapAt/$width", deck.filter.gain.isFinite() && deck.filter.highPassHz.isFinite())
                    }
                }
                val end = TransitionCurves.frame(style, 1f, swapAt = swapAt, swapWidth = width)
                assertEquals(1f, end.incoming.volume, 1e-4f)
                assertEquals(0f, end.outgoing.volume, 1e-4f)
            }
        }
    }

    @Test
    fun resolveFollowsTheListenerAndAutoReadsTheSongs() {
        for (beatMatched in listOf(false, true)) {
            for (calm in listOf(false, true)) {
                for (landsRise in listOf(false, true)) {
                    assertEquals(TransitionStyle.FADE, TransitionCurves.resolve(MixStyle.FADE, beatMatched, calm, landsRise))
                    assertEquals(TransitionStyle.FILTER, TransitionCurves.resolve(MixStyle.FILTER, beatMatched, calm, landsRise))
                    assertEquals(TransitionStyle.RISE, TransitionCurves.resolve(MixStyle.RISE, beatMatched, calm, landsRise))
                    val full = if (beatMatched) TransitionStyle.BASS_SWAP else TransitionStyle.FILTER
                    assertEquals(full, TransitionCurves.resolve(MixStyle.BASS_SWAP, beatMatched, calm, landsRise))
                    for (seed in 0..1) {
                        val auto = when {
                            calm -> TransitionStyle.REVERB_FADE
                            beatMatched && landsRise -> if (seed == 0) TransitionStyle.ROLL_DROP else TransitionStyle.BASS_SWAP
                            beatMatched -> TransitionStyle.EQ_SWAP
                            else -> TransitionStyle.ECHO_OUT
                        }
                        assertEquals(
                            "AUTO beat=$beatMatched calm=$calm rise=$landsRise seed=$seed",
                            auto,
                            TransitionCurves.resolve(MixStyle.AUTO, beatMatched, calm, landsRise, seed)
                        )
                    }
                    // A style the listener picked plays without effects.
                    for (choice in MixStyle.entries.filter { it != MixStyle.AUTO }) {
                        assertTrue(!TransitionCurves.resolve(choice, beatMatched, calm, landsRise).recipe.hasEffects)
                    }
                }
            }
        }
    }

    /** An open low-pass counts as the top of the audible band, so the sweep can be compared. */
    private fun lowPassOf(deck: DeckMix): Float =
        if (deck.filter.lowPassHz <= 0f) TOP_HZ else deck.filter.lowPassHz

    private fun logStep(a: Float, b: Float): Double =
        abs(ln(b.coerceAtLeast(open).toDouble()) - ln(a.coerceAtLeast(open).toDouble()))

    /**
     * Loudness of the two decks together against one song alone, in dB. Music is modelled as pink
     * noise (equal energy per octave over 20 Hz to 20 kHz) and the filters as brick walls.
     */
    private fun perceivedDb(frame: MixFrame): Float {
        fun band(deck: DeckMix): Double {
            val low = deck.filter.highPassHz.coerceIn(open, TOP_HZ).toDouble()
            val high = lowPassOf(deck).coerceIn(open, TOP_HZ).toDouble()
            return (ln(high / low) / ln(TOP_HZ / open.toDouble())).coerceAtLeast(0.0)
        }
        fun power(deck: DeckMix): Double {
            val amplitude = deck.volume * deck.filter.gain
            return amplitude * amplitude * band(deck)
        }
        return (10 * log10(power(frame.outgoing) + power(frame.incoming))).toFloat()
    }

    private companion object {
        const val TOP_HZ = 20_000f
    }
}
