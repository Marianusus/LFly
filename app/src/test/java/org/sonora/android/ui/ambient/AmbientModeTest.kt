package org.sonora.android.ui.ambient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AmbientModeTest {

    private fun show(
        hasTrack: Boolean = true,
        isPlayerExpanded: Boolean = true,
        isLandscape: Boolean = false,
        rotationEnabled: Boolean = true,
        openedFromMenu: Boolean = false,
        dismissed: Boolean = false
    ) = shouldShowAmbient(hasTrack, isPlayerExpanded, isLandscape, rotationEnabled, openedFromMenu, dismissed)

    @Test
    fun `rotating the full player to landscape opens ambient`() {
        assertTrue(show(isLandscape = true))
    }

    @Test
    fun `the setting turns off opening by rotation only`() {
        assertFalse(show(isLandscape = true, rotationEnabled = false))
        assertTrue(show(isLandscape = true, rotationEnabled = false, openedFromMenu = true))
    }

    @Test
    fun `the menu opens ambient in any orientation`() {
        assertTrue(show(openedFromMenu = true))
    }

    @Test
    fun `portrait without the menu stays in the player`() {
        assertFalse(show())
    }

    @Test
    fun `a dismissal holds while the phone stays in landscape`() {
        assertFalse(show(isLandscape = true, dismissed = true))
    }

    @Test
    fun `ambient needs the full player and a track`() {
        assertFalse(show(isLandscape = true, isPlayerExpanded = false))
        assertFalse(show(isLandscape = true, hasTrack = false))
    }

    @Test
    fun `the lyric sweep fills the first row before the second`() {
        assertEquals(listOf(0.5f, 0f), lyricRevealRows(listOf(100f, 100f), 0.25f))
        assertEquals(listOf(1f, 0.5f), lyricRevealRows(listOf(100f, 100f), 0.75f))
    }

    @Test
    fun `the lyric sweep stays in bounds and survives empty rows`() {
        assertEquals(listOf(0f), lyricRevealRows(listOf(100f), -1f))
        assertEquals(listOf(1f), lyricRevealRows(listOf(100f), 2f))
        assertEquals(listOf(0f, 1f), lyricRevealRows(listOf(0f, 100f), 1f))
        assertEquals(emptyList<Float>(), lyricRevealRows(emptyList(), 1f))
    }

    @Test
    fun `burn in offsets stay small, change at every step and repeat`() {
        val offsets = (0L until 8L).map(::ambientBurnInOffset)

        assertTrue(offsets.all { (x, y) -> abs(x) <= 6 && abs(y) <= 6 })
        assertTrue(offsets.zipWithNext().all { (a, b) -> a != b })
        assertEquals(ambientBurnInOffset(0L), ambientBurnInOffset(8L))
    }
}
