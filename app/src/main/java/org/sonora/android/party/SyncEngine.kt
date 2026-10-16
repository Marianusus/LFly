package org.sonora.android.party

import kotlin.math.abs

/**
 * Pure helpers that decide how a guest aligns its player with the host.
 *
 * All times are milliseconds. The clock convention is `hostNow = localNow + offsetMs`,
 * where the offset comes from [SyncClock].
 */
object SyncEngine {

    /** Below this the guest is left alone: no correction is worth the artefact. */
    const val SMALL_DRIFT_MS = 40L

    /** Above this a speed nudge starts; between [SMALL_DRIFT_MS] and this the previous action is held (hysteresis). */
    const val ENTER_SPEED_MS = 150L

    /** Above this a hard seek is used instead of a speed nudge. */
    const val DRIFT_SEEK_MS = 500L

    /** Playback-speed nudge (time-stretch, no pitch change) used to converge small drift. */
    const val SPEED_UP = 1.02f
    const val SLOW_DOWN = 0.98f

    /** Manual per-device headphone-latency compensation range (Bluetooth codecs differ). */
    const val NUDGE_RANGE_MS = 500L

    /** Default head start given to guests to resolve/prefetch a new track before it starts. */
    const val DEFAULT_START_LEAD_MS = 800L

    /** How a guest should react to the current drift. */
    enum class DriftAction { NONE, SPEED_UP, SLOW_DOWN, SEEK }

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

    /**
     * Next correction for a guest, with hysteresis: a speed nudge starts above
     * [ENTER_SPEED_MS] and only stops below [SMALL_DRIFT_MS], so the correction does not
     * flip-flop on every heartbeat (which was audible as a wobble).
     *
     * @param driftMs `target − current` (positive = the guest is behind and must catch up)
     */
    fun driftAction(driftMs: Long, isPlaying: Boolean, previous: DriftAction): DriftAction {
        if (!isPlaying) return DriftAction.NONE
        val magnitude = abs(driftMs)
        return when {
            magnitude >= DRIFT_SEEK_MS -> DriftAction.SEEK
            magnitude >= ENTER_SPEED_MS -> if (driftMs > 0) DriftAction.SPEED_UP else DriftAction.SLOW_DOWN
            magnitude <= SMALL_DRIFT_MS -> DriftAction.NONE
            else -> if (previous == DriftAction.SEEK) DriftAction.NONE else previous
        }
    }

    /** Milliseconds to wait before starting a scheduled track (0 = start now). */
    fun delayUntilStartMs(startsAtHostMs: Long, clockOffsetMs: Long, localNowMs: Long): Long {
        if (startsAtHostMs <= 0L) return 0L
        return (startsAtHostMs - (localNowMs + clockOffsetMs)).coerceAtLeast(0L)
    }

    /** True when [seq] supersedes [lastSeq] (state messages must be applied in order). */
    fun isNewer(seq: Long, lastSeq: Long): Boolean = seq > lastSeq
}
