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
            SyncEngine.driftAction(driftMs = 20L, isPlaying = true, previous = SyncEngine.DriftAction.NONE)
        )
    }

    @Test
    fun smallDriftIsCorrectedWithSpeedNotASeek() {
        assertEquals(
            SyncEngine.DriftAction.SPEED_UP,
            SyncEngine.driftAction(driftMs = 200L, isPlaying = true, previous = SyncEngine.DriftAction.NONE)
        )
        assertEquals(
            SyncEngine.DriftAction.SLOW_DOWN,
            SyncEngine.driftAction(driftMs = -200L, isPlaying = true, previous = SyncEngine.DriftAction.NONE)
        )
    }

    @Test
    fun largeDriftFallsBackToASeek() {
        assertEquals(
            SyncEngine.DriftAction.SEEK,
            SyncEngine.driftAction(driftMs = 800L, isPlaying = true, previous = SyncEngine.DriftAction.NONE)
        )
        assertEquals(
            SyncEngine.DriftAction.SEEK,
            SyncEngine.driftAction(driftMs = -6_000L, isPlaying = true, previous = SyncEngine.DriftAction.NONE)
        )
    }

    @Test
    fun pausedGuestsAreNeverResynced() {
        assertEquals(
            SyncEngine.DriftAction.NONE,
            SyncEngine.driftAction(driftMs = 30_000L, isPlaying = false, previous = SyncEngine.DriftAction.SPEED_UP)
        )
    }

    @Test
    fun speedCorrectionHoldsInsideTheDeadband() {
        // Above the enter threshold: start speeding up.
        assertEquals(
            SyncEngine.DriftAction.SPEED_UP,
            SyncEngine.driftAction(driftMs = 180L, isPlaying = true, previous = SyncEngine.DriftAction.NONE)
        )
        // Inside the deadband (40..150) the previous action is held instead of flip-flopping.
        assertEquals(
            SyncEngine.DriftAction.SPEED_UP,
            SyncEngine.driftAction(driftMs = 100L, isPlaying = true, previous = SyncEngine.DriftAction.SPEED_UP)
        )
        // Back below the exit threshold: settle to normal speed.
        assertEquals(
            SyncEngine.DriftAction.NONE,
            SyncEngine.driftAction(driftMs = 30L, isPlaying = true, previous = SyncEngine.DriftAction.SPEED_UP)
        )
    }

    @Test
    fun seekNeverHoldsAsASpeedAction() {
        assertEquals(
            SyncEngine.DriftAction.NONE,
            SyncEngine.driftAction(driftMs = 100L, isPlaying = true, previous = SyncEngine.DriftAction.SEEK)
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
