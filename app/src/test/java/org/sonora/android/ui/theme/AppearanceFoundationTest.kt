package org.sonora.android.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceFoundationTest {

    // ---------- AppearanceMode ----------

    @Test
    fun fromStringRoundTripsAllModes() {
        AppearanceMode.entries.forEach { mode ->
            assertEquals(mode, AppearanceMode.fromString(mode.name))
        }
    }

    @Test
    fun fromStringDefaultsToAmoledForUnknownOrNull() {
        assertEquals(AppearanceMode.AMOLED_BLACK, AppearanceMode.fromString(null))
        assertEquals(AppearanceMode.AMOLED_BLACK, AppearanceMode.fromString("does-not-exist"))
    }

    @Test
    fun lightAndDynamicFlagsAreConsistent() {
        assertFalse(AppearanceMode.AMOLED_BLACK.isLight)
        assertTrue(AppearanceMode.PURE_WHITE.isLight)
        assertFalse(AppearanceMode.DYNAMIC_DARK.isLight)
        assertTrue(AppearanceMode.DYNAMIC_LIGHT.isLight)

        assertFalse(AppearanceMode.AMOLED_BLACK.isDynamic)
        assertFalse(AppearanceMode.PURE_WHITE.isDynamic)
        assertTrue(AppearanceMode.DYNAMIC_DARK.isDynamic)
        assertTrue(AppearanceMode.DYNAMIC_LIGHT.isDynamic)
    }

    // ---------- BackgroundScheme: fallback without cover ----------

    @Test
    fun fallbackProvidesThreeStopsForEveryMode() {
        AppearanceMode.entries.forEach { mode ->
            val scheme = BackgroundSchemeFactory.fallback(mode)
            assertEquals(3, scheme.gradient.size)
            assertEquals(mode, scheme.mode)
            assertNotNull(scheme.accent)
        }
    }

    @Test
    fun amoledBlackCanvasIsAbsoluteBlack() {
        val scheme = BackgroundSchemeFactory.fallback(AppearanceMode.AMOLED_BLACK)
        scheme.gradient.forEach { assertEquals(Color(0xFF000000), it) }
        assertFalse(scheme.isLight)
    }

    @Test
    fun fallbackWithoutCoverUsesSonoraAccent() {
        val scheme = BackgroundSchemeFactory.fallback(AppearanceMode.AMOLED_BLACK)
        // Fallback accent is the Sonora violet/blue pair (luminance-clamped for contrast).
        assertTrue(scheme.accent.luma() >= 0.5f)
        assertTrue(scheme.accentSecondary.luma() >= 0.5f)
    }

    // ---------- Dynamic dark: colors stay dark ----------

    @Test
    fun dynamicDarkProducesLowLuminanceCanvas() {
        val scheme = BackgroundSchemeFactory.build(
            AppearanceMode.DYNAMIC_DARK,
            accent = Color(0xFF00E5FF),
            accentSecondary = Color(0xFFFA2D48)
        )
        assertFalse(scheme.isLight)
        scheme.gradient.forEach { color ->
            assertTrue("dynamic dark stop too bright: luma=${color.luma()}", color.luma() <= 0.30f)
        }
    }

    @Test
    fun dynamicDarkWithBrightCoverStillStaysDark() {
        val scheme = BackgroundSchemeFactory.build(
            AppearanceMode.DYNAMIC_DARK,
            accent = Color(0xFFFFFFFF),
            accentSecondary = Color(0xFFF5F5F5)
        )
        scheme.gradient.forEach { assertTrue(it.luma() <= 0.30f) }
    }

    @Test
    fun dynamicModesShowVisibleAccentGradient() {
        val dark = BackgroundSchemeFactory.build(
            AppearanceMode.DYNAMIC_DARK,
            accent = Color(0xFF00E5FF),
            accentSecondary = Color(0xFFFA2D48)
        )
        val darkDelta = dark.gradient.first().luma() - dark.gradient.last().luma()
        assertTrue("dynamic dark accent not visible: delta=$darkDelta", darkDelta >= 0.08f)
        assertTrue(dark.gradient.first().luma() <= 0.30f)

        val light = BackgroundSchemeFactory.build(
            AppearanceMode.DYNAMIC_LIGHT,
            accent = Color(0xFF00E5FF),
            accentSecondary = Color(0xFFFA2D48)
        )
        val lightDelta = light.gradient.last().luma() - light.gradient.first().luma()
        assertTrue("dynamic light accent not visible: delta=$lightDelta", lightDelta >= 0.05f)
        light.gradient.forEach { assertTrue(it.luma() >= 0.80f) }
    }

    // ---------- Legacy theme migration ----------

    @Test
    fun legacyThemeStyleMigratesToAppearanceMode() {
        assertEquals(AppearanceMode.AMOLED_BLACK, AppearanceMode.migrateFromLegacy("SONORA_AMOLED"))
        assertEquals(AppearanceMode.DYNAMIC_DARK, AppearanceMode.migrateFromLegacy("APPLE_LIQUID_GLASS"))
        assertEquals(AppearanceMode.DYNAMIC_DARK, AppearanceMode.migrateFromLegacy("MIDNIGHT_NEON"))
        assertEquals(AppearanceMode.AMOLED_BLACK, AppearanceMode.migrateFromLegacy(null))
        assertEquals(AppearanceMode.AMOLED_BLACK, AppearanceMode.migrateFromLegacy("unknown"))
    }

    // ---------- Dynamic light: colors stay light + readable ----------

    @Test
    fun dynamicLightProducesHighLuminanceCanvas() {
        val scheme = BackgroundSchemeFactory.build(
            AppearanceMode.DYNAMIC_LIGHT,
            accent = Color(0xFF1B1B2F),
            accentSecondary = Color(0xFF203040)
        )
        assertTrue(scheme.isLight)
        scheme.gradient.forEach { color ->
            assertTrue("dynamic light stop too dark: luma=${color.luma()}", color.luma() >= 0.8f)
        }
    }

    @Test
    fun dynamicLightWithDarkCoverStillStaysLight() {
        val scheme = BackgroundSchemeFactory.build(
            AppearanceMode.DYNAMIC_LIGHT,
            accent = Color(0xFF000000),
            accentSecondary = Color(0xFF101010)
        )
        scheme.gradient.forEach { assertTrue(it.luma() >= 0.8f) }
    }

    // ---------- Contrast helper ----------

    @Test
    fun onColorForPicksDarkInkOnLightAndWhiteOnDark() {
        assertTrue(onColorFor(Color(0xFFFFFFFF)).luma() < 0.3f)
        assertTrue(onColorFor(Color(0xFFF2F2F7)).luma() < 0.3f)
        assertEquals(Color.White, onColorFor(Color(0xFF000000)))
        assertEquals(Color.White, onColorFor(Color(0xFF101018)))
    }

    @Test
    fun lightSchemesUseDarkTextAndDarkSchemesUseWhiteText() {
        val light = BackgroundSchemeFactory.fallback(AppearanceMode.PURE_WHITE)
        assertTrue(light.onBackground.luma() < 0.3f)

        val dark = BackgroundSchemeFactory.fallback(AppearanceMode.DYNAMIC_DARK)
        assertEquals(Color.White, dark.onBackground)
    }

    // ---------- Cover accent cache (memoization) ----------

    @Test
    fun coverAccentCacheComputesOncePerUrlAndCaches() {
        val cache = CoverAccentCache(maxEntries = 8)
        var computations = 0
        val accent = CoverAccent(Color(0xFF112233), Color(0xFF445566))

        val first = cache.getOrPut("cover-a") { computations++; accent }
        val second = cache.getOrPut("cover-a") { computations++; accent }

        assertEquals(1, computations)
        assertSame(first, second)
        assertEquals(1, cache.size)
    }

    @Test
    fun coverAccentCacheReturnsNullAndDoesNotCacheFailures() {
        val cache = CoverAccentCache(maxEntries = 8)
        var computations = 0
        val result = cache.getOrPut("broken") { computations++; null }
        assertNull(result)
        assertEquals(0, cache.size)
        assertEquals(1, computations)
    }

    @Test
    fun coverAccentCacheEvictsEldestBeyondCapacity() {
        val cache = CoverAccentCache(maxEntries = 2)
        cache.put("a", CoverAccent(Color.Red, Color.Green))
        cache.put("b", CoverAccent(Color.Blue, Color.Yellow))
        // touch "a" so "b" becomes least recently used
        assertNotNull(cache.get("a"))
        cache.put("c", CoverAccent(Color.Magenta, Color.Cyan))

        assertEquals(2, cache.size)
        assertNull(cache.get("b"))
        assertNotNull(cache.get("a"))
        assertNotNull(cache.get("c"))
    }

    // ---------- Motion preferences ----------

    @Test
    fun reduceMotionZeroesAllDurationsAndDisablesContinuousMotion() {
        val reduced = MotionPreferences(reduceMotion = true)
        assertEquals(0, reduced.microMs)
        assertEquals(0, reduced.standardMs)
        assertEquals(0, reduced.navigationMs)
        assertEquals(0, reduced.backgroundMs)
        assertEquals(1f, reduced.pressScale)
        assertFalse(reduced.allowContinuousMotion)
    }

    @Test
    fun defaultMotionUsesDesignDurationsAndPressScale() {
        val normal = MotionPreferences.Default
        assertEquals(Durations.MICRO, normal.microMs)
        assertEquals(Durations.STANDARD, normal.standardMs)
        assertEquals(Durations.NAVIGATION, normal.navigationMs)
        assertEquals(Durations.BACKGROUND, normal.backgroundMs)
        assertEquals(0.97f, normal.pressScale)
        assertTrue(normal.allowContinuousMotion)
    }
}
