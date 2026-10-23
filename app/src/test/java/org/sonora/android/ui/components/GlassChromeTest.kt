package org.sonora.android.ui.components

import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sonora.android.ui.components.backdrop.isLiquidGlassSupported

class GlassChromeTest {

    @Test
    fun liquidGlassRequiresRenderEffectSupport() {
        assertFalse(isLiquidGlassSupported(Build.VERSION_CODES.R))
        assertTrue(isLiquidGlassSupported(Build.VERSION_CODES.S))
        assertTrue(isLiquidGlassSupported(Build.VERSION_CODES.TIRAMISU))
    }

    @Test
    fun chromeCollapsesAfterDownwardScrollAndExpandsOnUpwardScroll() {
        val state = GlassChromeScrollState(thresholdPx = 50f)
        state.connection.onPreScroll(Offset(0f, -60f), NestedScrollSource.UserInput)
        assertTrue(state.isInline)
        state.connection.onPreScroll(Offset(0f, 60f), NestedScrollSource.UserInput)
        assertFalse(state.isInline)
    }

    @Test
    fun oppositeScrollDirectionResetsAccumulator() {
        val state = GlassChromeScrollState(thresholdPx = 50f)
        state.connection.onPreScroll(Offset(0f, -40f), NestedScrollSource.UserInput)
        assertFalse(state.isInline)
        state.connection.onPreScroll(Offset(0f, 20f), NestedScrollSource.UserInput)
        state.connection.onPreScroll(Offset(0f, -40f), NestedScrollSource.UserInput)
        assertFalse("direction changes must restart the fold gesture", state.isInline)
    }

    @Test
    fun expiryExpandsImmediately() {
        val state = GlassChromeScrollState(thresholdPx = 50f)
        state.connection.onPreScroll(Offset(0f, -60f), NestedScrollSource.UserInput)
        assertTrue(state.isInline)
        state.expand()
        assertFalse(state.isInline)
    }
}
