package org.sonora.android.party

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncEngineTest {

    @Test
    fun targetAdvancesWithHostClockWhilePlaying() {
        // Host clock = local clock + 500. Anchor at host 1_000_000 with pos 10_000.
        val target = SyncEngine.targetPositionMs(
            statePositionMs = 10_000L,
            startsAtHostMs = 1_000_000L,
            hostTimeAtSendMs = 0L,
            isPlaying = true,
            clockOffsetMs = 500L,
            localNowMs = 1_000_200L
        )
        // hostNow = 1_000_700 -> 700 ms elapsed
        assertEquals(10_700L, target)
    }

    @Test
    fun pausedTargetIgnoresElapsedTime() {
        val target = SyncEngine.targetPositionMs(
            statePositionMs = 10_000L,
            startsAtHostMs = 1_000_000L,
            hostTimeAtSendMs = 0L,
            isPlaying = false,
            clockOffsetMs = 500L,
            localNowMs = 1_000_200L
        )
        assertEquals(10_000L, target)
    }

    @Test
    fun nudgeMakesThisDevicePlayEarlier() {
        val target = SyncEngine.targetPositionMs(
            statePositionMs = 10_000L,
            startsAtHostMs = 0L,
            hostTimeAtSendMs = 0L,
            isPlaying = false,
            clockOffsetMs = 0L,
            localNowMs = 0L,
            nudgeMs = 250L
        )
        assertEquals(9_750L, target)
    }

    @Test
    fun targetNeverGoesNegative() {
        val target = SyncEngine.targetPositionMs(
            statePositionMs = 100L,
            startsAtHostMs = 0L,
            hostTimeAtSendMs = 0L,
            isPlaying = false,
            clockOffsetMs = 0L,
            localNowMs = 0L,
            nudgeMs = 500L
        )
        assertEquals(0L, target)
    }

    @Test
    fun missingAnchorFallsBackToTheSentPosition() {
        val target = SyncEngine.targetPositionMs(
            statePositionMs = 5_000L,
            startsAtHostMs = 0L,
            hostTimeAtSendMs = 0L,
            isPlaying = true,
            clockOffsetMs = 100L,
            localNowMs = 9_999_999L
        )
        assertEquals(5_000L, target)
    }

    @Test
    fun aClockRunningAheadNeverProducesNegativeElapsed() {
        val target = SyncEngine.targetPositionMs(
            statePositionMs = 5_000L,
            startsAtHostMs = 2_000_000L,
            hostTimeAtSendMs = 0L,
            isPlaying = true,
            clockOffsetMs = 0L,
            localNowMs = 1_000_000L
        )
        assertEquals(5_000L, target)
    }

    @Test
    fun tinyDriftIsLeftAlone() {
        assertEquals(
            SyncEngine.DriftAction.NONE,
            SyncEngine.driftAction(currentPositionMs = 10_000L, targetPositionMs = 10_020L, isPlaying = true)
        )
    }

    @Test
    fun smallDriftIsCorrectedWithSpeedNotASeek() {
        assertEquals(
            SyncEngine.DriftAction.SPEED_UP,
            SyncEngine.driftAction(currentPositionMs = 10_000L, targetPositionMs = 10_150L, isPlaying = true)
        )
        assertEquals(
            SyncEngine.DriftAction.SLOW_DOWN,
            SyncEngine.driftAction(currentPositionMs = 10_150L, targetPositionMs = 10_000L, isPlaying = true)
        )
    }

    @Test
    fun largeDriftFallsBackToASeek() {
        assertEquals(
            SyncEngine.DriftAction.SEEK,
            SyncEngine.driftAction(currentPositionMs = 10_000L, targetPositionMs = 10_500L, isPlaying = true)
        )
        assertEquals(
            SyncEngine.DriftAction.SEEK,
            SyncEngine.driftAction(currentPositionMs = 10_000L, targetPositionMs = 4_000L, isPlaying = true)
        )
    }

    @Test
    fun pausedGuestsAreNeverResynced() {
        assertEquals(
            SyncEngine.DriftAction.NONE,
            SyncEngine.driftAction(currentPositionMs = 10_000L, targetPositionMs = 40_000L, isPlaying = false)
        )
    }

    @Test
    fun delayUntilStartIsClampedToZero() {
        assertEquals(800L, SyncEngine.delayUntilStartMs(1_000_800L, clockOffsetMs = 0L, localNowMs = 1_000_000L))
        assertEquals(0L, SyncEngine.delayUntilStartMs(1_000_000L, clockOffsetMs = 0L, localNowMs = 1_000_500L))
        assertEquals(0L, SyncEngine.delayUntilStartMs(0L, clockOffsetMs = 0L, localNowMs = 1_000_500L))
    }

    @Test
    fun stateMessagesMustBeOrdered() {
        assertTrue(SyncEngine.isNewer(2L, 1L))
        assertFalse(SyncEngine.isNewer(1L, 1L))
        assertFalse(SyncEngine.isNewer(0L, 1L))
    }
}
