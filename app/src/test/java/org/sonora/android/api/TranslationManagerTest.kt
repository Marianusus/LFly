package org.sonora.android.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class TranslationManagerTest {

    @Test
    fun shouldTranslate_sameLanguage_returnsFalse() {
        assertFalse(TranslationManager.shouldTranslate("it", "it"))
        assertFalse(TranslationManager.shouldTranslate("EN", "en"))
    }

    @Test
    fun shouldTranslate_differentLanguage_returnsTrue() {
        assertTrue(TranslationManager.shouldTranslate("en", "it"))
        assertTrue(TranslationManager.shouldTranslate("fr", "it"))
    }

    @Test
    fun shouldTranslate_undeterminedOrBlank_returnsFalse() {
        assertFalse(TranslationManager.shouldTranslate("und", "it"))
        assertFalse(TranslationManager.shouldTranslate("", "it"))
        assertFalse(TranslationManager.shouldTranslate("en", ""))
    }

    @Test
    fun cache_writeThenRead_roundTripsTranslatedLines() {
        val dir = Files.createTempDirectory("sonora-translations").toFile()
        val cache = TranslationCache(dir)
        val lines = listOf(
            LyricLine(timeMs = 0L, text = "Hello", translation = "Ciao"),
            LyricLine(timeMs = 1500L, text = "World", translation = null)
        )

        cache.write("lyrics-42|it", lines)
        val read = cache.read("lyrics-42|it")

        assertEquals(lines, read)
    }

    @Test
    fun cache_missingKey_returnsNull() {
        val dir = Files.createTempDirectory("sonora-translations-empty").toFile()
        val cache = TranslationCache(dir)
        assertEquals(null, cache.read("does-not-exist|it"))
    }
}
