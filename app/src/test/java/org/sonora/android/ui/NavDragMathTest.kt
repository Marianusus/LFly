package org.sonora.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavDragMathTest {

    @Test
    fun tabWidthAndStepAccountForGaps() {
        val gap = 6f
        val count = 4
        val rowWidth = 300f

        assertEquals(70.5f, navTabWidthPx(rowWidth, gap, count), 0.001f)
        assertEquals(76.5f, navTabStepPx(rowWidth, gap, count), 0.001f)
        assertEquals(rowWidth, navTabWidthPx(rowWidth, gap, count) * count + gap * (count - 1), 0.001f)
    }

    @Test
    fun dragSnapsToNearestTab() {
        val step = 76.5f
        assertEquals(0, navDragTargetIndex(0, 0f, step, 3))
        assertEquals(1, navDragTargetIndex(0, 38.25f, step, 3))
        assertEquals(1, navDragTargetIndex(0, step, step, 3))
        assertEquals(3, navDragTargetIndex(0, step * 10f, step, 3))
        assertEquals(0, navDragTargetIndex(3, -step * 10f, step, 3))
    }

    @Test
    fun dragOffsetStaysRelativeToSelection() {
        val step = 76.5f
        assertEquals(step, navDragOffsetPx(0, step, step, 0, 3), 0.001f)
        assertEquals(-step, navDragOffsetPx(1, -step, step, 1, 3), 0.001f)
        assertEquals(0f, navDragOffsetPx(0, 0f, step, 0, 3), 0.001f)
    }

    @Test
    fun invalidGeometryDoesNotDivideByZero() {
        assertEquals(0f, navTabWidthPx(0f, 6f, 4), 0f)
        assertEquals(0f, navTabStepPx(300f, 6f, 0), 0f)
        assertEquals(2, navDragTargetIndex(2, 100f, 0f, 3))
        assertEquals(0f, navDragOffsetPx(2, 100f, 0f, 2, 3), 0f)
        assertTrue(navTabWidthPx(4f, 6f, 4) == 0f)
    }
}
