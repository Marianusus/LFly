package org.sonora.android.party

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncClockTest {

    @Test
    fun estimatesTheOffsetOnASymmetricRound() {
        val clock = SyncClock()
        // True offset: host clock = local clock + 200 ms; 40 ms each way.
        val offset = clock.addRound(localSendMs = 1_000L, hostRecvMs = 1_240L, localRecvMs = 1_080L)
        assertEquals(200L, offset)
        assertTrue(clock.isReady)
        assertEquals(200L, clock.offsetMs())
    }

    @Test
    fun discardsRoundsWithAnAbsurdRtt() {
        val clock = SyncClock()
        assertNull(clock.addRound(localSendMs = 0L, hostRecvMs = 10L, localRecvMs = 5_000L))
        assertFalse(clock.isReady)
    }

    @Test
    fun discardsBackwardsRounds() {
        val clock = SyncClock()
        assertNull(clock.addRound(localSendMs = 500L, hostRecvMs = 500L, localRecvMs = 100L))
        assertFalse(clock.isReady)
    }

    @Test
    fun theMedianAbsorbsASingleSpike() {
        val clock = SyncClock()
        clock.addRound(0L, 200L, 100L)        // +150
        clock.addRound(10_000L, 12_900L, 10_100L) // spike
        clock.addRound(20_000L, 20_200L, 20_100L) // +150
        assertEquals(150L, clock.offsetMs())
    }

    @Test
    fun keepsOnlyTheMostRecentSamples() {
        val clock = SyncClock(maxSamples = 2)
        clock.addRound(0L, 1_000L, 100L)
        clock.addRound(0L, 2_000L, 100L)
        clock.addRound(0L, 3_000L, 100L)
        assertEquals(2_950L, clock.offsetMs())
    }

    @Test
    fun resetClearsTheEstimate() {
        val clock = SyncClock()
        clock.addRound(0L, 200L, 100L)
        clock.reset()
        assertNull(clock.offsetMs())
        assertFalse(clock.isReady)
    }
}
