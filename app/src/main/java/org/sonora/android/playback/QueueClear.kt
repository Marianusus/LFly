package org.sonora.android.playback

import org.sonora.android.api.Track

/**
 * Result of dropping the user-queued tracks that are still ahead of the current one.
 *
 * [removedFrom] is inclusive and [removedTo] exclusive, expressed in the *old* queue
 * indices. The current track and the radio/autoplay tail are deliberately preserved.
 */
data class ClearedUserQueue(
    val queue: List<Track>,
    val userQueueSize: Int,
    val removedFrom: Int,
    val removedTo: Int
)

/**
 * Removes the upcoming USER queue only.
 *
 * The timeline is `[history/current] + [user queue] + [radio/autoplay]`. Clearing the
 * user queue splices the tail back after the current track, so playback continues with
 * the same track and the radio is not destroyed.
 */
fun clearUserUpcoming(
    queue: List<Track>,
    currentIndex: Int,
    userQueueSize: Int
): ClearedUserQueue? {
    if (currentIndex !in queue.indices) return null
    val boundary = userQueueSize.coerceIn(0, queue.size)
    val removedFrom = currentIndex + 1
    if (boundary <= removedFrom) return null

    val remaining = queue.take(removedFrom) + queue.drop(boundary)
    return ClearedUserQueue(
        queue = remaining,
        userQueueSize = removedFrom.coerceAtMost(remaining.size),
        removedFrom = removedFrom,
        removedTo = boundary
    )
}


/** True when both queue positions belong to the same reorderable region. */
fun canMoveWithinQueueSection(from: Int, to: Int, userQueueSize: Int): Boolean {
    if (from < 0 || to < 0 || from == to) return false
    val boundary = userQueueSize.coerceAtLeast(0)
    return (from < boundary && to < boundary) || (from >= boundary && to >= boundary)
}

/**
 * Drops every track after the current one, keeping the playing track and its history.
 * This is the visible "Svuota" action in the queue panel.
 */
fun clearUpcoming(
    queue: List<Track>,
    currentIndex: Int
): ClearedUserQueue? {
    if (currentIndex !in queue.indices || currentIndex == queue.lastIndex) return null
    val remaining = queue.take(currentIndex + 1)
    return ClearedUserQueue(
        queue = remaining,
        userQueueSize = remaining.size,
        removedFrom = currentIndex + 1,
        removedTo = queue.size
    )
}
