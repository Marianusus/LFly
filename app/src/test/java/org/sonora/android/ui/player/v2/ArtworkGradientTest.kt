package org.sonora.android.ui.player.v2

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkGradientTest {

    @Test
    fun shadeKeepsAlphaAndChannelsInRange() {
        val shaded = shadeArgb(0xFF7AC7FF.toInt(), maxValue = 0.47f, scale = 0.80f)
        assertEquals(0xFF, (shaded ushr 24) and 0xFF)
        val r = (shaded shr 16) and 0xFF
        val g = (shaded shr 8) and 0xFF
        val b = shaded and 0xFF
        assertTrue(r in 0..255 && g in 0..255 && b in 0..255)
        assertTrue("blue should stay dominant", b >= r)
    }

    @Test
    fun shadePreservesSaturatedHue() {
        val shaded = shadeArgb(0xFFFF0000.toInt(), maxValue = 0.47f, scale = 0.80f)
        val r = (shaded shr 16) and 0xFF
        val g = (shaded shr 8) and 0xFF
        val b = shaded and 0xFF
        assertTrue(r > g && r > b)
    }

    @Test
    fun shadeCapsLuminance() {
        val shaded = shadeArgb(0xFFFFFFFF.toInt(), maxValue = 0.47f, scale = 0.80f)
        val r = (shaded shr 16) and 0xFF
        val g = (shaded shr 8) and 0xFF
        val b = shaded and 0xFF
        assertTrue("white should be darkened", r < 160 && g < 160 && b < 160)
    }

    @Test
    fun fallbackIsDarkAndDeterministic() {
        val first = ArtworkGradients.fallback(Color(0xFFE53935))
        val second = ArtworkGradients.fallback(Color(0xFFE53935))
        assertEquals(first, second)
        assertTrue(first.top.red >= first.bottom.red)
    }

    @Test
    fun cacheEvictsLeastRecentlyUsed() {
        val cache = ArtworkGradientCache(maxEntries = 2)
        cache.put("a", ArtworkGradients.fallback())
        cache.put("b", ArtworkGradients.fallback())
        cache.get("a")
        cache.put("c", ArtworkGradients.fallback())
        assertEquals(2, cache.size)
        assertTrue(cache.get("a") != null)
        assertTrue(cache.get("b") == null)
        assertTrue(cache.get("c") != null)
    }
}
