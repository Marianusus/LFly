package org.sonora.android.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sonora.android.api.Track

class QueueReorderTest {

    private fun track(id: String) = Track(
        id = id,
        title = "title-$id",
        artist = "artist",
        album = "album",
        durationMs = 1_000L,
        coverUrl = null
    )

    @Test
    fun stableTrackKeys_areUniqueForDuplicateIds() {
        val keys = stableTrackKeys(listOf(track("a"), track("b"), track("a"), track("a")))

        assertEquals(listOf("a", "b", "a#1", "a#2"), keys)
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun stableTrackKeys_areIndependentOfReorderForDistinctTracks() {
        val original = listOf(track("a"), track("b"), track("c"))
        val reordered = listOf(track("c"), track("a"), track("b"))

        val originalKeys = stableTrackKeys(original).toSet()
        val reorderedKeys = stableTrackKeys(reordered).toSet()

        assertEquals(originalKeys, reorderedKeys)
    }

    @Test
    fun mapQueueReorderByKey_translatesLocalKeysToAbsoluteQueueIndices() {
        val keys = listOf("t0", "t1", "t2", "t3")

        val moved = mapQueueReorderByKey(keys, fromKey = "t3", toKey = "t1", upcomingBaseIndex = 5)

        assertEquals(8 to 6, moved)
    }

    @Test
    fun mapQueueReorderByKey_withZeroBase_returnsLocalTrackIndices() {
        // Playlists and Liked Songs reorder on 0-based local indices (no list header offset).
        val keys = listOf("a", "b", "c", "d")

        assertEquals(0 to 2, mapQueueReorderByKey(keys, fromKey = "a", toKey = "c", upcomingBaseIndex = 0))
        assertEquals(3 to 1, mapQueueReorderByKey(keys, fromKey = "d", toKey = "b", upcomingBaseIndex = 0))
    }

    @Test
    fun mapQueueReorderByKey_returnsNullForUnknownKeysOrNoOp() {
        val keys = listOf("t0", "t1", "t2")

        assertNull(mapQueueReorderByKey(keys, "t1", "t1", upcomingBaseIndex = 2))
        assertNull(mapQueueReorderByKey(keys, "missing", "t0", upcomingBaseIndex = 2))
        assertNull(mapQueueReorderByKey(keys, "t0", "missing", upcomingBaseIndex = 2))
    }

    @Test
    fun mapQueueReorderByKey_isMonotonicRegardlessOfDirection() {
        val keys = listOf("t0", "t1", "t2")
        val base = 4

        val forward = mapQueueReorderByKey(keys, "t0", "t2", base)
        val backward = mapQueueReorderByKey(keys, "t2", "t0", base)

        assertTrue(forward != null && backward != null)
        assertEquals(4 to 6, forward)
        assertEquals(6 to 4, backward)
    }
}
