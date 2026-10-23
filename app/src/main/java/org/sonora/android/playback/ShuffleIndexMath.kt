package org.sonora.android.playback

import kotlin.random.Random

/**
 * Pure index bookkeeping for the shuffled playback order.
 *
 * The list holds **queue indices** in play order; [onInsert]/[onRemove]/[onMove] keep it
 * consistent without ever reshuffling the tracks the user has not touched. This is what
 * prevents "Play next" / "Add to queue" from destroying the current shuffle sequence.
 */
internal object ShuffleIndexMath {

    /**
     * Registers [count] new queue items inserted at [insertAt].
     *
     * Existing indices are shifted right. The new items are inserted after the current
     * pointer (immediately after it for "play next", at a random later slot otherwise).
     * The pointer does not move because the current track keeps its position in the order.
     */
    fun onInsert(
        indices: MutableList<Int>,
        pointer: Int,
        insertAt: Int,
        count: Int,
        asNext: Boolean,
        random: Random = Random.Default
    ): Int {
        if (count <= 0) return pointer
        if (indices.isEmpty()) return pointer

        for (i in indices.indices) {
            if (indices[i] >= insertAt) indices[i] += count
        }

        val newIndices = (insertAt until insertAt + count).toMutableList()
        if (asNext) {
            val at = (pointer + 1).coerceIn(0, indices.size)
            indices.addAll(at, newIndices)
        } else {
            newIndices.shuffle(random)
            val start = (pointer + 1).coerceIn(0, indices.size)
            val at = if (start >= indices.size) indices.size else random.nextInt(start, indices.size + 1)
            indices.addAll(at, newIndices)
        }
        return pointer
    }

    /**
     * Removes [queueIndex] from both the queue and the shuffle order, shifting indices and
     * moving the pointer when the removed item was before (or was) the current one.
     * Returns the new pointer, or -1 when the order becomes empty.
     */
    fun onRemove(indices: MutableList<Int>, pointer: Int, queueIndex: Int): Int {
        val position = indices.indexOf(queueIndex)
        for (i in indices.indices) {
            if (indices[i] > queueIndex) indices[i] -= 1
        }
        if (position < 0) return pointer

        indices.removeAt(position)
        if (indices.isEmpty()) return -1

        var newPointer = pointer
        when {
            position < pointer -> newPointer -= 1
            position == pointer && pointer >= indices.size -> newPointer = 0
        }
        return newPointer.coerceIn(0, indices.size - 1)
    }

    /** Remaps queue indices after a user-queue drag from [from] to [to]. */
    fun onMove(indices: MutableList<Int>, from: Int, to: Int) {
        if (from == to) return
        for (i in indices.indices) {
            val index = indices[i]
            indices[i] = when {
                index == from -> to
                from < to && index in (from + 1)..to -> index - 1
                from > to && index in to until from -> index + 1
                else -> index
            }
        }
    }

    /**
     * Removes the contiguous queue range `[from, toExclusive)` from the shuffle order.
     *
     * The current-entry pointer only shifts when an entry before it disappears, so clearing
     * upcoming tracks keeps the current position untouched while the remaining radio tail
     * is compacted down by the number of rows that were dropped.
     */
    fun onRemoveRange(
        indices: MutableList<Int>,
        pointer: Int,
        from: Int,
        toExclusive: Int
    ): Int {
        if (from >= toExclusive) return pointer
        val removedBeforePointer = indices.count { it in from until toExclusive && it < pointer }
        var write = 0
        for (read in indices.indices) {
            val index = indices[read]
            if (index in from until toExclusive) continue
            indices[write++] = if (index >= toExclusive) index - (toExclusive - from) else index
        }
        while (indices.size > write) indices.removeAt(indices.size - 1)
        if (indices.isEmpty()) return -1
        return (pointer - removedBeforePointer).coerceIn(0, indices.size - 1)
    }

    /** Appends freshly fetched autoplay items in a random order. */
    fun onAppend(indices: MutableList<Int>, newIndices: List<Int>, random: Random = Random.Default) {
        if (newIndices.isEmpty()) return
        indices.addAll(newIndices.shuffled(random))
    }
}
