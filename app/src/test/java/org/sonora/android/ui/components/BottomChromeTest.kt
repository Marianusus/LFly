package org.sonora.android.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sonora.android.ui.theme.AppearanceMode
import org.sonora.android.ui.theme.BackgroundSchemeFactory

class BottomChromeTest {

    @Test
    fun insetWithoutMiniPlayerIsNavPlusInset() {
        val inset = BottomChrome.inset(hasMiniPlayer = false, navigationBarsInset = 0.dp)
        val expected = BottomChrome.EdgeMargin + BottomChrome.NavBarHeight +
            BottomChrome.ContentBreathingRoom
        assertEquals(expected, inset)
    }

    @Test
    fun insetWithMiniPlayerAddsMiniHeightAndGap() {
        val without = BottomChrome.inset(hasMiniPlayer = false, navigationBarsInset = 0.dp)
        val with = BottomChrome.inset(hasMiniPlayer = true, navigationBarsInset = 0.dp)
        val delta = with - without
        assertEquals(BottomChrome.StackGap + BottomChrome.MiniPlayerHeight + BottomChrome.EdgeMargin, delta)
        assertTrue("miniplayer inset must exceed nav-only", with > without)
    }

    @Test
    fun navigationBarsInsetIsAddedOnTop() {
        val base = BottomChrome.inset(hasMiniPlayer = true, navigationBarsInset = 0.dp)
        val withBars = BottomChrome.inset(hasMiniPlayer = true, navigationBarsInset = 24.dp)
        assertEquals(base + 24.dp, withBars)
    }

    @Test
    fun insetIsNeverSmallerThanNavBarHeight() {
        BottomChrome.inset(false, 0.dp).let { assertTrue(it >= BottomChrome.NavBarHeight) }
    }

    @Test
    fun chromeSurfaceIsFullAndDistinctPerAppearanceMode() {
        val surfaces = AppearanceMode.entries.map { mode ->
            chromeSurface(BackgroundSchemeFactory.build(mode))
        }
        surfaces.forEach { surface ->
            assertTrue("chrome must stay mostly opaque", surface.tint.alpha >= 0.88f)
        }
        assertEquals(
            "each appearance mode must composite a different chrome tint",
            AppearanceMode.entries.size,
            surfaces.map { it.tint }.toSet().size
        )
    }

    @Test
    fun chromeSurfaceUsesContextualAccentForDynamicModes() {
        val accentA = androidx.compose.ui.graphics.Color(0xFFFF3B30)
        val accentB = androidx.compose.ui.graphics.Color(0xFF34C759)
        val darkA = chromeSurface(BackgroundSchemeFactory.build(AppearanceMode.DYNAMIC_DARK, accentA))
        val darkB = chromeSurface(BackgroundSchemeFactory.build(AppearanceMode.DYNAMIC_DARK, accentB))
        assertTrue("dynamic dark chrome should pick up the cover accent", darkA.tint != darkB.tint)
    }
}
