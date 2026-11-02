package org.sonora.android.playback.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.sonora.android.api.Track

class AutoSearchCacheTest {

    private var clockMs = 0L
    private val cache = AutoSearchCache { clockMs }
    private val tracks = listOf(
        Track(id = "yt_1", title = "Title", artist = "Artist", album = null, durationMs = 1L, coverUrl = null, streamUrl = null)
    )

    @Test
    fun `online results do not expire`() {
        cache.putOnline("q", tracks)
        clockMs = Long.MAX_VALUE / 2

        assertEquals(tracks, cache.get("q"))
    }

    @Test
    fun `results of a failed search are retried after a short while`() {
        cache.putFallback("q", tracks)
        clockMs = 29_999L
        assertEquals(tracks, cache.get("q"))

        clockMs = 30_000L
        assertNull(cache.get("q"))
    }

    @Test
    fun `an older query stays playable after a new one`() {
        cache.putOnline("a", tracks)
        cache.putOnline("b", emptyList())

        assertEquals(tracks, cache.get("a"))
    }

    @Test
    fun `the least recently used query is dropped first`() {
        (0..7).forEach { cache.putOnline("q$it", tracks) }
        cache.get("q0")

        cache.putOnline("new", tracks)

        assertNotNull(cache.get("q0"))
        assertNull(cache.get("q1"))
    }
}
