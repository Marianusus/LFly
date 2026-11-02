package org.sonora.android.playback.automix

import org.sonora.android.playback.CrossfadeMath
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/** How one song moves into the next. */
internal enum class TransitionStyle {
    /** Volumes cross; nothing else changes. */
    FADE,

    /** The incoming song comes in without its bass; on a downbeat the bass lines change places. */
    BASS_SWAP,

    /** The outgoing song is filtered away with a rising high-pass while a low-pass opens on the incoming one. */
    FILTER,

    /** The outgoing song builds up with a rising high-pass, then cuts to the incoming song on a downbeat. */
    RISE
}

/** One deck at one instant: player volume (0..1) and the mixer settings. */
internal data class DeckMix(val volume: Float, val filter: DjFilterSettings)

internal data class MixFrame(val outgoing: DeckMix, val incoming: DeckMix)

/**
 * The shape of each transition over its progress, 0 at the hand-off and 1 when the outgoing song is
 * gone. Pure, so the curves can be tested and the controller only applies them.
 */
internal object TransitionCurves {
    /** The loudness match is eased in at the start, so the hand-off itself never jumps. */
    const val LOUDNESS_RAMP = 0.15f

    /** Default bass swap: halfway through, over one beat of a 16-beat mix. */
    const val SWAP_AT = 0.5f
    const val SWAP_WIDTH = 1f / 16

    /** Filter sweep: the outgoing high-pass ends here, thin enough to sound like a radio. */
    private const val FILTER_HIGH_PASS_END_HZ = 2_500f

    /** Filter sweep: the incoming song starts as bass and kick only, then opens fully by [FILTER_OPEN_AT]. */
    private const val FILTER_LOW_PASS_START_HZ = 280f
    private const val FILTER_LOW_PASS_END_HZ = 20_000f
    private const val FILTER_OPEN_AT = 0.65f

    /** Rise: the outgoing high-pass tops out here, with a small lift so the build does not sound thinner. */
    private const val RISE_HIGH_PASS_END_HZ = 1_000f
    private const val RISE_LIFT_DB = 1.5

    private const val OPEN_HIGH_PASS_HZ = DjFilterMath.OPEN_HIGH_PASS_HZ.toFloat()

    /**
     * [outgoingGain] brings the outgoing song to the incoming one's level. [swapAt] and [swapWidth]
     * place the bass swap, ideally on a downbeat and over one beat.
     */
    fun frame(
        style: TransitionStyle,
        progress: Float,
        outgoingGain: Float = 1f,
        swapAt: Float = SWAP_AT,
        swapWidth: Float = SWAP_WIDTH
    ): MixFrame {
        val p = progress.coerceIn(0f, 1f)
        val match = 1f + (outgoingGain - 1f) * (p / LOUDNESS_RAMP).coerceAtMost(1f)
        return when (style) {
            TransitionStyle.FADE -> MixFrame(
                outgoing = DeckMix(CrossfadeMath.fadeOutGain(p), DjFilterSettings(gain = match)),
                incoming = DeckMix(CrossfadeMath.fadeInGain(p), DjFilterSettings.NEUTRAL)
            )
            TransitionStyle.BASS_SWAP -> bassSwap(p, match, swapAt.coerceIn(0.05f, 0.9f), swapWidth.coerceIn(0.01f, 0.25f))
            TransitionStyle.FILTER -> filterSweep(p, match, swapAt.coerceIn(0.4f, 0.6f))
            TransitionStyle.RISE -> rise(p, match, swapAt.coerceIn(0.05f, 0.9f), swapWidth.coerceIn(0.01f, 0.25f))
        }
    }

    /**
     * The style of one transition from the listener's [choice]. AUTO keeps calm songs on a plain
     * fade, swaps the bass when the beats line up and filters when they cannot: a filter sweep hides
     * drums that drift apart, where a bass swap would make them flam. RISE is never picked by AUTO,
     * since a hard cut only works when the listener asked for it.
     */
    fun resolve(choice: MixStyle, beatMatched: Boolean, calm: Boolean): TransitionStyle = when (choice) {
        MixStyle.FADE -> TransitionStyle.FADE
        MixStyle.BASS_SWAP -> TransitionStyle.BASS_SWAP
        MixStyle.FILTER -> TransitionStyle.FILTER
        MixStyle.RISE -> TransitionStyle.RISE
        MixStyle.AUTO -> when {
            calm -> TransitionStyle.FADE
            beatMatched -> TransitionStyle.BASS_SWAP
            else -> TransitionStyle.FILTER
        }
    }

    private fun bassSwap(p: Float, match: Float, swapAt: Float, swapWidth: Float): MixFrame {
        val swapEnd = (swapAt + swapWidth).coerceAtMost(0.95f)
        // Equal-power across the swap: the low end never doubles up or drops out.
        val t = ((p - swapAt) / (swapEnd - swapAt)).coerceIn(0f, 1f)
        val outgoingBass = bassDb(cos(t * PI / 2))
        val incomingBass = bassDb(sin(t * PI / 2))

        // The incoming fader is up by the swap; the outgoing one goes down only after it.
        val incomingVolume = CrossfadeMath.fadeInGain(p / swapAt)
        val outgoingVolume = if (p <= swapEnd) 1f else CrossfadeMath.fadeOutGain((p - swapEnd) / (1f - swapEnd))
        return MixFrame(
            outgoing = DeckMix(outgoingVolume, DjFilterSettings(gain = match, bassDb = outgoingBass)),
            incoming = DeckMix(incomingVolume, DjFilterSettings(bassDb = incomingBass))
        )
    }

    /**
     * A filter sweep. The incoming song fades in under a low-pass, so at first only its bass and kick
     * are heard, and is fully up by [fadeAt]; from there the outgoing fader goes down while its
     * high-pass climbs, mostly in the second half, until only its hats are left. Both sweeps move in
     * log frequency, as the ear hears pitch. [fadeAt] is the downbeat nearest the middle, so the
     * outgoing song starts leaving on one.
     */
    private fun filterSweep(p: Float, match: Float, fadeAt: Float): MixFrame {
        val highPass = logSweep(OPEN_HIGH_PASS_HZ, FILTER_HIGH_PASS_END_HZ, p * p)
        val lowPass = if (p >= FILTER_OPEN_AT) 0f
        else logSweep(FILTER_LOW_PASS_START_HZ, FILTER_LOW_PASS_END_HZ, p / FILTER_OPEN_AT)

        val incomingVolume = CrossfadeMath.fadeInGain(p / fadeAt)
        val outgoingVolume = if (p <= fadeAt) 1f else CrossfadeMath.fadeOutGain((p - fadeAt) / (1f - fadeAt))
        return MixFrame(
            outgoing = DeckMix(outgoingVolume, DjFilterSettings(gain = match, highPassHz = highPass)),
            incoming = DeckMix(incomingVolume, DjFilterSettings(lowPassHz = lowPass))
        )
    }

    /**
     * A build and a cut. Until the downbeat at [swapAt] only the outgoing song plays, its high-pass
     * rising faster towards the end like a riser, with a small gain lift so it keeps its energy; on
     * the downbeat the two songs trade places over [swapWidth] on an equal-power curve.
     */
    private fun rise(p: Float, match: Float, swapAt: Float, swapWidth: Float): MixFrame {
        val swapEnd = (swapAt + swapWidth).coerceAtMost(0.95f)
        val build = (p / swapAt).coerceAtMost(1f)
        val highPass = logSweep(OPEN_HIGH_PASS_HZ, RISE_HIGH_PASS_END_HZ, build * build)
        val lift = 1f + (DjFilterMath.dbToGain(RISE_LIFT_DB).toFloat() - 1f) * build

        val t = ((p - swapAt) / (swapEnd - swapAt)).coerceIn(0f, 1f)
        return MixFrame(
            outgoing = DeckMix(
                CrossfadeMath.fadeOutGain(t),
                DjFilterSettings(gain = match * lift, highPassHz = highPass)
            ),
            incoming = DeckMix(CrossfadeMath.fadeInGain(t), DjFilterSettings.NEUTRAL)
        )
    }

    /** From [from] to [to] at [t] (0..1), evenly in log frequency. */
    private fun logSweep(from: Float, to: Float, t: Float): Float =
        from * (to / from).pow(t.coerceIn(0f, 1f))

    private fun bassDb(amplitude: Double): Float =
        if (amplitude >= 0.9999) 0f
        else (20 * log10(amplitude.coerceAtLeast(1e-6))).toFloat().coerceAtLeast(DjFilterMath.BASS_KILL_DB)
}
