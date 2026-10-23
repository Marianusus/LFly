package org.sonora.android.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.sonora.android.api.Track

class QueueClearTest {

    private fun track(id: String) = Track(
        id = id,
        title = "title-$id",
        artist = "artist",
        album = "album",
        durationMs = 1_000L,
        coverUrl = null
    )

    @Test
    fun clearUserUpcoming_keepsCurrentHistoryAndRadio() {
        val queue = listOf("h", "current", "u1", "u2", "radio1", "radio2").map(::track)

        val result = clearUserUpcoming(queue, currentIndex = 1, userQueueSize = 4)

        assertEquals(listOf("h", "current", "radio1", "radio2"), result?.queue?.map { it.id })
        assertEquals(2, result?.userQueueSize)
        assertEquals(2, result?.removedFrom)
        assertEquals(4, result?.removedTo)
    }

    @Test
    fun clearUserUpcoming_isNoOpWhenNothingIsQueued() {
        val queue = listOf("h", "current", "radio").map(::track)

        assertNull(clearUserUpcoming(queue, currentIndex = 1, userQueueSize = 2))
    }

    @Test
    fun clearUserUpcoming_isNoOpWithInvalidCurrentTrack() {
        assertNull(clearUserUpcoming(emptyList(), currentIndex = -1, userQueueSize = 0))
    }

    @Test
    fun clearUpcoming_keepsCurrentAndDropsEverythingAfter() {
        val queue = listOf("h", "current", "u1", "radio1", "radio2").map(::track)

        val result = clearUpcoming(queue, currentIndex = 1)

        assertEquals(listOf("h", "current"), result?.queue?.map { it.id })
        assertEquals(2, result?.userQueueSize)
        assertEquals(2, result?.removedFrom)
        assertEquals(5, result?.removedTo)
    }

    @Test
    fun canMoveWithinQueueSection_allowsUserAndRadioButNotTheBoundary() {
        assertEquals(true, canMoveWithinQueueSection(1, 3, userQueueSize = 4))
        assertEquals(true, canMoveWithinQueueSection(4, 6, userQueueSize = 4))
        assertEquals(false, canMoveWithinQueueSection(3, 4, userQueueSize = 4))
        assertEquals(false, canMoveWithinQueueSection(4, 3, userQueueSize = 4))
    }

    @Test
    fun removeRange_compactsShuffleAndKeepsCurrentPointer() {
        val indices = mutableListOf(0, 4, 1, 2, 3, 5)
        val pointer = 0 // current queue index 0

        val result = ShuffleIndexMath.onRemoveRange(indices, pointer, from = 1, toExclusive = 4)

        assertEquals(listOf(0, 1, 2), indices)
        assertEquals(0, result)
    }
}
