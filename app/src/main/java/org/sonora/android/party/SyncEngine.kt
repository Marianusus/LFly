package org.sonora.android.party

import kotlin.math.abs

/**
 * Pure helpers that decide how a guest aligns its player with the host.
 *
 * All times are milliseconds. The clock convention is `hostNow = localNow + offsetMs`,
 * where the offset comes from [SyncClock].
 */
object SyncEngine {

    /** Drift above which a playing guest re-seeks (small drift is not worth an audible jump). */
    const val SEEK_THRESHOLD_MS = 150L

    /** Drift beyond this is treated as a realignment (track start / jump) and always corrected. */
    const val HARD_RESYNC_MS = 2_000L

    /** Manual per-device headphone-latency compensation range (Bluetooth codecs differ). */
    const val NUDGE_RANGE_MS = 500L

    /** Default head start given to guests to resolve/prefetch a new track before it starts. */
    const val DEFAULT_START_LEAD_MS = 800L

    /**
     * Position the guest should be at right now.
     *
     * `nudgeMs` is this device's headphone latency relative to the host: a positive
     * nudge makes this device play earlier so both pairs of headphones sound together.
     *
     * @param statePositionMs position at [startsAtHostMs]
     * @param startsAtHostMs host clock when [statePositionMs] was valid; <= 0 means "send time"
     * @param hostTimeAtSendMs host clock when the state was sent (used when [startsAtHostMs] <= 0)
     */
    fun targetPositionMs(
        statePositionMs: Long,
        startsAtHostMs: Long,
        hostTimeAtSendMs: Long,
        isPlaying: Boolean,
        clockOffsetMs: Long,
        localNowMs: Long,
        nudgeMs: Long = 0L
    ): Long {
        if (!isPlaying) return (statePositionMs - nudgeMs).coerceAtLeast(0L)
        val anchor = if (startsAtHostMs > 0) startsAtHostMs else hostTimeAtSendMs
        if (anchor <= 0) return (statePositionMs - nudgeMs).coerceAtLeast(0L)
        val hostNow = localNowMs + clockOffsetMs
        val elapsed = (hostNow - anchor).coerceAtLeast(0L)
        return (statePositionMs + elapsed - nudgeMs).coerceAtLeast(0L)
    }

    /** True when the guest must seek to realign. */
    fun shouldSeek(currentPositionMs: Long, targetPositionMs: Long, isPlaying: Boolean): Boolean {
        val drift = abs(currentPositionMs - targetPositionMs)
        return drift > HARD_RESYNC_MS || (isPlaying && drift > SEEK_THRESHOLD_MS)
    }

    /** Milliseconds to wait before starting a scheduled track (0 = start now). */
    fun delayUntilStartMs(startsAtHostMs: Long, clockOffsetMs: Long, localNowMs: Long): Long {
        if (startsAtHostMs <= 0L) return 0L
        return (startsAtHostMs - (localNowMs + clockOffsetMs)).coerceAtLeast(0L)
    }

    /** True when [seq] supersedes [lastSeq] (state messages must be applied in order). */
    fun isNewer(seq: Long, lastSeq: Long): Boolean = seq > lastSeq
}
