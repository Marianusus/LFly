package org.sonora.android.ui.components

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class HeldTrackMenuLayoutTest {

    private fun assertPosition(
        expectedX: Float,
        expectedY: Float,
        actual: androidx.compose.ui.geometry.Offset
    ) {
        assertEquals(expectedX, actual.x, 0.001f)
        assertEquals(expectedY, actual.y, 0.001f)
    }

    @Test
    fun menuIsPlacedBelowAnchorWhenThereIsRoom() {
        val position = calculateHeldMenuPosition(
            anchor = Rect(100f, 100f, 300f, 160f),
            screenSize = Size(400f, 800f),
            menuSize = Size(200f, 120f),
            safeTop = 0f,
            safeBottom = 0f
        )

        // 160 (anchor bottom) + 8 (gap), horizontally centered on the anchor.
        assertPosition(expectedX = 100f, expectedY = 168f, actual = position)
    }

    @Test
    fun menuIsPlacedAboveAnchorWhenBelowIsTooTight() {
        val position = calculateHeldMenuPosition(
            anchor = Rect(100f, 600f, 300f, 660f),
            screenSize = Size(400f, 800f),
            menuSize = Size(200f, 200f),
            safeTop = 0f,
            safeBottom = 0f
        )

        // 600 (anchor top) - 8 (gap) - 200 (menu height).
        assertPosition(expectedX = 100f, expectedY = 392f, actual = position)
    }

    @Test
    fun menuIsClampedToLeftEdge() {
        val position = calculateHeldMenuPosition(
            anchor = Rect(0f, 100f, 50f, 160f),
            screenSize = Size(400f, 800f),
            menuSize = Size(200f, 100f),
            safeTop = 0f,
            safeBottom = 0f
        )

        assertEquals(8f, position.x, 0.001f)
    }

    @Test
    fun menuIsClampedToRightEdge() {
        val position = calculateHeldMenuPosition(
            anchor = Rect(350f, 100f, 400f, 160f),
            screenSize = Size(400f, 800f),
            menuSize = Size(200f, 100f),
            safeTop = 0f,
            safeBottom = 0f
        )

        // 400 - 8 (edge margin) - 200 (menu width).
        assertEquals(192f, position.x, 0.001f)
    }

    @Test
    fun menuWiderThanScreenSticksToLeftMargin() {
        val position = calculateHeldMenuPosition(
            anchor = Rect(100f, 100f, 300f, 160f),
            screenSize = Size(300f, 800f),
            menuSize = Size(400f, 100f),
            safeTop = 0f,
            safeBottom = 0f
        )

        assertEquals(8f, position.x, 0.001f)
    }

    @Test
    fun menuTallerThanSafeAreaIsPinnedToTopSafeInset() {
        val position = calculateHeldMenuPosition(
            anchor = Rect(100f, 300f, 300f, 360f),
            screenSize = Size(400f, 800f),
            menuSize = Size(200f, 900f),
            safeTop = 50f,
            safeBottom = 40f
        )

        // safeTop (50) + edge margin (8); the panel then scrolls internally.
        assertPosition(expectedX = 100f, expectedY = 58f, actual = position)
    }

    @Test
    fun menuNeverEscapesTheBottomSafeEdge() {
        val position = calculateHeldMenuPosition(
            anchor = Rect(100f, 100f, 300f, 160f),
            screenSize = Size(400f, 300f),
            menuSize = Size(200f, 200f),
            safeTop = 0f,
            safeBottom = 0f
        )

        // Below (168..368) and above (-108..92) both overflow; the panel is
        // clamped so its bottom lands exactly on 300 - 8 = 292.
        assertPosition(expectedX = 100f, expectedY = 92f, actual = position)
        assertEquals(292f, position.y + 200f, 0.001f)
    }
}
