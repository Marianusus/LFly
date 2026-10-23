package org.sonora.android.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniPlayerPullTest {

    @Test
    fun pullLiftIsZeroWithoutPull() {
        assertEquals(0f, miniPlayerPullLift(0f, 72f), 0f)
        assertEquals(0f, miniPlayerPullLift(-20f, 72f), 0f)
        assertEquals(0f, miniPlayerPullLift(20f, 0f), 0f)
    }

    @Test
    fun pullLiftGrowsAndSaturatesGracefully() {
        val first = miniPlayerPullLift(24f, 72f)
        val second = miniPlayerPullLift(72f, 72f)
        val third = miniPlayerPullLift(144f, 72f)

        assertTrue(first > 0f)
        assertTrue(second > first)
        assertTrue(third > second)
        assertTrue("lift must not run away", third < 144f)
    }
}
