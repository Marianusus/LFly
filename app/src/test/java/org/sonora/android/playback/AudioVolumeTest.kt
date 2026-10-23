package org.sonora.android.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioVolumeTest {
    @Test
    fun fractionIsClampedToTheStreamRange() {
        assertEquals(0f, audioVolumeFraction(-1, 15), 0f)
        assertEquals(0.5f, audioVolumeFraction(5, 10), 0f)
        assertEquals(1f, audioVolumeFraction(20, 10), 0f)
    }

    @Test
    fun indexRoundsDownAndStaysInsideTheStreamRange() {
        assertEquals(0, audioVolumeIndex(-0.2f, 15))
        assertEquals(7, audioVolumeIndex(0.5f, 15))
        assertEquals(15, audioVolumeIndex(1.4f, 15))
    }

    @Test
    fun zeroMaximumDoesNotDivideByZero() {
        assertEquals(0f, audioVolumeFraction(5, 0), 0f)
        assertEquals(0, audioVolumeIndex(0.7f, 0))
    }
}
