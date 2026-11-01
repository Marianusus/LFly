package org.sonora.android.playback.auto

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoArtworkRegistryTest {

    @After
    fun tearDown() = AutoArtworkRegistry.clear()

    @Test
    fun `web covers are served from an opaque key`() {
        val url = "https://example.com/cover.jpg"

        val key = AutoArtworkRegistry.register(url)!!

        assertEquals(url, AutoArtworkRegistry.sourceFor(key))
        assertNotEquals(url, key)
    }

    @Test
    fun `local covers become plain paths`() {
        val key = AutoArtworkRegistry.register("file:///data/local_covers/a.jpg")!!

        assertEquals("/data/local_covers/a.jpg", AutoArtworkRegistry.sourceFor(key))
    }

    @Test
    fun `local files that are not images are refused`() {
        assertNull(AutoArtworkRegistry.register("/data/data/org.sonora.android/files/favorites.json"))
        assertNull(AutoArtworkRegistry.register("file:///dev/zero"))
    }

    @Test
    fun `other schemes and blanks are refused`() {
        assertNull(AutoArtworkRegistry.register("content://other/secret"))
        assertNull(AutoArtworkRegistry.register("  "))
        assertNull(AutoArtworkRegistry.register(null))
    }

    @Test
    fun `an unregistered key resolves to nothing`() {
        assertNull(AutoArtworkRegistry.sourceFor("../../etc/passwd"))
    }
}
