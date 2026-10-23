package org.sonora.android.playback

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShuffleIndexMathTest {

    private fun order(vararg values: Int): MutableList<Int> = values.toMutableList()

    @Test
    fun `add to queue keeps the existing shuffle order and appends the new index`() {
        val indices = order(0, 2, 1, 3)
        val pointer = ShuffleIndexMath.onInsert(
            indices = indices,
            pointer = 1,
            insertAt = 4,
            count = 1,
            asNext = false,
            random = Random(7)
        )

        assertEquals(1, pointer)
        assertEquals(5, indices.size)
        assertEquals(setOf(0, 1, 2, 3, 4), indices.toSet())
        // The tracks already in the queue keep their relative shuffled order.
        assertEquals(listOf(0, 2, 1, 3), indices.filter { it != 4 })
    }

    @Test
    fun `play next puts the new track immediately after the current one`() {
        val indices = order(0, 2, 1, 3)
        val pointer = ShuffleIndexMath.onInsert(
            indices = indices,
            pointer = 1,
            insertAt = 2,
            count = 1,
            asNext = true
        )

        assertEquals(1, pointer)
        assertEquals(5, indices.size)
        assertEquals(listOf(0, 3, 2, 1, 4), indices)
        assertEquals(3, indices[1]) // current track, shifted by the insertion
        assertEquals(2, indices[2]) // inserted "next"
    }

    @Test
    fun `batch insert shifts indices and keeps a valid permutation`() {
        val indices = order(0, 3, 1, 2)
        val pointer = ShuffleIndexMath.onInsert(
            indices = indices,
            pointer = 0,
            insertAt = 1,
            count = 2,
            asNext = false,
            random = Random(3)
        )

        assertEquals(0, pointer)
        assertEquals(6, indices.size)
        assertEquals((0..5).toSet(), indices.toSet())
        assertTrue(indices.contains(1) && indices.contains(2))
        assertEquals(listOf(0, 5, 3, 4), indices.filter { it != 1 && it != 2 })
    }

    @Test
    fun `removing a track before the current one decrements the pointer`() {
        val indices = order(0, 2, 1, 3)
        val pointer = ShuffleIndexMath.onRemove(indices, pointer = 2, queueIndex = 0)

        assertEquals(1, pointer)
        assertEquals(listOf(1, 0, 2), indices)
        assertEquals(0, indices[pointer]) // the old current track (queue 1)
    }

    @Test
    fun `removing a track after the current one keeps the pointer`() {
        val indices = order(0, 2, 1, 3)
        val pointer = ShuffleIndexMath.onRemove(indices, pointer = 1, queueIndex = 1)

        assertEquals(1, pointer)
        assertEquals(listOf(0, 1, 2), indices)
        assertEquals(1, indices[pointer]) // the current track (queue 2 became 1)
    }

    @Test
    fun `removing the current track advances to the next shuffled track`() {
        val indices = order(0, 2, 1, 3)
        val pointer = ShuffleIndexMath.onRemove(indices, pointer = 1, queueIndex = 2)

        assertEquals(1, pointer)
        assertEquals(listOf(0, 1, 2), indices)
        assertEquals(1, indices[pointer]) // next in the shuffled order
    }

    @Test
    fun `removing the last current track wraps to the first`() {
        val indices = order(0, 2, 1)
        val pointer = ShuffleIndexMath.onRemove(indices, pointer = 2, queueIndex = 1)

        assertEquals(0, pointer)
        assertEquals(listOf(0, 1), indices)
        assertEquals(0, indices[pointer])
    }

    @Test
    fun `removing the only track empties the order`() {
        val indices = order(0)
        assertEquals(-1, ShuffleIndexMath.onRemove(indices, pointer = 0, queueIndex = 0))
        assertTrue(indices.isEmpty())
    }

    @Test
    fun `moving a queue item remaps indices without reshuffling`() {
        val indices = order(0, 2, 1, 3)
        ShuffleIndexMath.onMove(indices, from = 0, to = 2)

        assertEquals(listOf(2, 1, 0, 3), indices)
        assertEquals((0..3).toSet(), indices.toSet())
    }

    @Test
    fun `moving the current item keeps the pointer on it`() {
        val indices = order(0, 2, 1, 3)
        val pointer = 1
        ShuffleIndexMath.onMove(indices, from = 2, to = 3)

        assertEquals(listOf(0, 3, 1, 2), indices)
        assertEquals(3, indices[pointer]) // old queue 2 is now queue 3
    }

    @Test
    fun `appending autoplay tracks preserves the existing order`() {
        val indices = order(0, 2, 1)
        ShuffleIndexMath.onAppend(indices, listOf(3, 4), random = Random(5))

        assertEquals(5, indices.size)
        assertEquals((0..4).toSet(), indices.toSet())
        assertEquals(listOf(0, 2, 1), indices.filter { it < 3 })
    }
}
