package org.sonora.android.playback.automix

import org.sonora.android.playback.CrossfadeMath
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/** How one song moves into the next. */
internal enum class TransitionStyle {
    /** Volumes cross; nothing else changes. */
    FADE,

    /** The incoming song comes in without its bass; on a downbeat the bass lines change places. */
    BASS_SWAP
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

    private fun bassDb(amplitude: Double): Float =
        if (amplitude >= 0.9999) 0f
        else (20 * log10(amplitude.coerceAtLeast(1e-6))).toFloat().coerceAtLeast(DjFilterMath.BASS_KILL_DB)
}
