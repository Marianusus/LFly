package org.sonora.android.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfadeMathTest {

    private val song = 200_000L

    @Test
    fun `off or short songs never crossfade`() {
        assertNull(CrossfadeMath.handoffPositionMs(195_000L, song, 0L))
        assertNull(CrossfadeMath.handoffPositionMs(20_000L, 25_000L, 6_000L))
    }

    @Test
    fun `it waits until the fade and the preparation fit in what is left`() {
        // The second player needs the preparation lead to load and line itself up before the hand-off.
        assertNull(CrossfadeMath.handoffPositionMs(189_000L, song, 6_000L))
        assertEquals(194_000L, CrossfadeMath.handoffPositionMs(190_000L, song, 6_000L))
    }

    @Test
    fun `a seek close to the end still leaves time to prepare`() {
        assertEquals(197_200L, CrossfadeMath.handoffPositionMs(196_500L, song, 6_000L))
    }

    @Test
    fun `too close to the end the song simply ends`() {
        assertNull(CrossfadeMath.handoffPositionMs(198_500L, song, 6_000L))
    }

    @Test
    fun `the two curves keep the loudness steady`() {
        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { p ->
            val out = CrossfadeMath.fadeOutGain(p)
            val inn = CrossfadeMath.fadeInGain(p)
            assertEquals(1f, out * out + inn * inn, 0.0001f)
        }
        assertEquals(1f, CrossfadeMath.fadeOutGain(0f), 0.0001f)
        assertEquals(0f, CrossfadeMath.fadeOutGain(1f), 0.0001f)
        assertTrue(CrossfadeMath.fadeInGain(2f) <= 1f)
    }
}
