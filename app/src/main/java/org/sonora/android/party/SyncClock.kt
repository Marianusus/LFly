package org.sonora.android.party

/**
 * Estimates the offset between the local clock and the host clock using
 * NTP-style ping/pong rounds (guest side only).
 *
 * Offset convention: `hostNow = localNow + offsetMs`.
 */
class SyncClock(
    private val maxSamples: Int = 12,
    private val maxRttMs: Long = 2000L
) {

    private val offsets = ArrayDeque<Long>()

    /**
     * Feeds one completed round.
     *
     * @param localSendMs guest clock when the ping was sent
     * @param hostRecvMs host clock when the pong was stamped
     * @param localRecvMs guest clock when the pong came back
     * @return the updated (median) offset estimate, or null when the round was discarded
     */
    fun addRound(localSendMs: Long, hostRecvMs: Long, localRecvMs: Long): Long? {
        val rtt = localRecvMs - localSendMs
        if (rtt < 0 || rtt > maxRttMs) return null
        offsets.addLast(hostRecvMs - (localSendMs + rtt / 2))
        while (offsets.size > maxSamples) offsets.removeFirst()
        return offsetMs()
    }

    /** Median of the recent offsets, robust to one-off network spikes. */
    fun offsetMs(): Long? =
        if (offsets.isEmpty()) null else offsets.sorted()[offsets.size / 2]

    val isReady: Boolean get() = offsets.isNotEmpty()

    fun reset() = offsets.clear()
}
