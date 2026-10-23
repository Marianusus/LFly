package org.sonora.android.ui.home

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.sonora.android.ui.theme.onColorFor
import org.junit.Test

class HomeLayoutPolicyTest {

    @Test
    fun shelfUsesTwoColumnsOnNormalPhoneWidth() {
        assertEquals(2, homeShelfColumns(screenWidthDp = 360, fontScale = 1f))
        assertEquals(2, homeShelfColumns(screenWidthDp = 412, fontScale = 1f))
    }

    @Test
    fun shelfFallsBackToOneColumnOnNarrowOrLargeText() {
        assertEquals(1, homeShelfColumns(screenWidthDp = 320, fontScale = 1f))
        assertEquals(1, homeShelfColumns(screenWidthDp = 412, fontScale = 1.3f))
        assertEquals(1, homeShelfColumns(screenWidthDp = 412, fontScale = 2f))
    }

    @Test
    fun homeTimeUsesMinuteColonSeconds() {
        assertEquals("0:00", formatHomeTime(0L))
        assertEquals("0:07", formatHomeTime(7_900L))
        assertEquals("3:05", formatHomeTime(185_000L))
    }

    @Test
    fun brightAccentGetsDarkForeground() {
        val accent = Color(0xFF1DB954)
        assertEquals(Color(0xFF101012), homeOnColor(accent))
        assertEquals(Color(0xFF15151A), onColorFor(accent))
        assertTrue(contrastRatio(accent, Color.White) < 4.5)
    }
}
