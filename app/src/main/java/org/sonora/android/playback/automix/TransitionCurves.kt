package org.sonora.android.playback.automix

import org.sonora.android.playback.CrossfadeMath
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/** How the two faders move. */
enum class VolumeCurve {
    /** Equal-power crossfade over the whole mix. */
    CROSSFADE,

    /** The incoming song comes in at 40%, the outgoing one leaves at 60%: both full in between. */
    SMOOTH_CROSSFADE,

    /** Both songs at full level for the whole mix; the outgoing one leaves on the last beat. */
    OVERLAP,

    /** The incoming song fades in over the first half, the outgoing one fades out over the second. */
    FADE_IN_FADE_OUT,

    /** The incoming song cuts in at the start, the outgoing one fades out over the second half. */
    CUT_IN_FADE_OUT,

    /** The incoming song fades in over the first half, the outgoing one cuts out on the last beat. */
    FADE_IN_CUT_OUT,

    /** A cut on the middle downbeat. */
    CENTER_CUT
}

/** What the EQ does. */
enum class EqCurve {
    NONE,

    /** The incoming song plays without bass until the middle downbeat, where the bass lines change places. */
    CENTER_BASS_SWAP,

    /** As [CENTER_BASS_SWAP], on the last downbeat. */
    END_BASS_SWAP,

    /** Highs change places on the first quarter's downbeat, mids and bass in the middle. */
    THREE_BAND
}

/** A sweeping filter on one deck: on the incoming song it opens during the first half, on the outgoing one it closes during the second. */
enum class FilterCurve { NONE, LOW_PASS, HIGH_PASS }

/** One transition's ingredients, the way a DJ (or Spotify's Mix editor) puts them together. */
data class MixRecipe(
    val volume: VolumeCurve,
    val eq: EqCurve = EqCurve.NONE,
    val filterIn: FilterCurve = FilterCurve.NONE,
    val filterOut: FilterCurve = FilterCurve.NONE,
    /** The outgoing song builds up under a rising high-pass before the cut ([TransitionStyle.RISE]). */
    val riser: Boolean = false,
    /** An echo, delay, reverb or noise on the outgoing song. */
    val effect: EffectCurve = EffectCurve.NONE,
    /** The outgoing song stops like a record. */
    val jogwheel: JogwheelCurve = JogwheelCurve.NONE,
    /** The outgoing song holds a loop. */
    val loop: LoopCurve = LoopCurve.NONE
) {
    /** True when the outgoing deck plays an effect, which runs on the song's own clock. */
    val hasEffects: Boolean
        get() = effect != EffectCurve.NONE || jogwheel != JogwheelCurve.NONE || loop != LoopCurve.NONE

    /**
     * Gson leaves a field it did not find null, whatever Kotlin says: a recipe saved before the effects
     * existed comes back with them missing. Here every missing ingredient is none.
     */
    @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
    fun repaired(): MixRecipe =
        if (volume == null || eq == null || filterIn == null || filterOut == null ||
            effect == null || jogwheel == null || loop == null
        ) {
            MixRecipe(
                volume = volume ?: VolumeCurve.CROSSFADE,
                eq = eq ?: EqCurve.NONE,
                filterIn = filterIn ?: FilterCurve.NONE,
                filterOut = filterOut ?: FilterCurve.NONE,
                riser = riser,
                effect = effect ?: EffectCurve.NONE,
                jogwheel = jogwheel ?: JogwheelCurve.NONE,
                loop = loop ?: LoopCurve.NONE
            )
        } else {
            this
        }

    /** True when both songs are heard together at full level, which needs the beats to line up. */
    val needsBeatMatch: Boolean
        get() = volume == VolumeCurve.OVERLAP || volume == VolumeCurve.SMOOTH_CROSSFADE ||
            volume == VolumeCurve.FADE_IN_CUT_OUT
}

/** How one song moves into the next. */
internal enum class TransitionStyle(val recipe: MixRecipe) {
    /** Volumes cross; nothing else changes. */
    FADE(MixRecipe(VolumeCurve.CROSSFADE)),

    /**
     * Both songs play at full level for the whole mix. The incoming one opens up under a resonant
     * low-pass and has no bass; the outgoing one thins out under a rising high-pass; on the last
     * downbeat the bass lines change places and the outgoing song is gone: a build and a drop.
     */
    BASS_SWAP(MixRecipe(VolumeCurve.OVERLAP, EqCurve.END_BASS_SWAP, FilterCurve.LOW_PASS, FilterCurve.HIGH_PASS)),

    /**
     * Both songs play at full level and a three-band EQ hands one over to the other: the highs on the
     * first quarter's downbeat, then the mids and the bass halfway through.
     */
    EQ_SWAP(MixRecipe(VolumeCurve.OVERLAP, EqCurve.THREE_BAND)),

    /**
     * The incoming song comes in thin under a falling high-pass while its fader goes up; halfway the
     * bass lines change places and a rising high-pass takes the outgoing song away while it fades.
     */
    FILTER(MixRecipe(VolumeCurve.FADE_IN_FADE_OUT, EqCurve.CENTER_BASS_SWAP, FilterCurve.HIGH_PASS, FilterCurve.HIGH_PASS)),

    /** Both songs at full level, each under a low-pass that sweeps, with the bass swapped in the middle. */
    WAVE(MixRecipe(VolumeCurve.OVERLAP, EqCurve.CENTER_BASS_SWAP, FilterCurve.LOW_PASS, FilterCurve.LOW_PASS)),

    /** A hard cut on the middle downbeat. */
    SLAM(MixRecipe(VolumeCurve.CENTER_CUT)),

    /** The outgoing song builds up with a rising high-pass, then cuts to the incoming song on a downbeat. */
    RISE(MixRecipe(VolumeCurve.CENTER_CUT, riser = true)),

    /**
     * Off the beat, as a DJ does it when two tempos cannot meet: [FILTER], and the outgoing song,
     * thinning out, is thrown into an echo that repeats on its own beat while the new song takes over.
     */
    ECHO_OUT(FILTER.recipe.copy(effect = EffectCurve.ECHO_1_OUT)),

    /** A calm crossfade where the outgoing song dissolves into a reverb. */
    REVERB_FADE(FADE.recipe.copy(effect = EffectCurve.REVERB_OUT_END)),

    /** [BASS_SWAP] with a loop roll on the outgoing song over the last two bars, into the drop. */
    ROLL_DROP(BASS_SWAP.recipe.copy(loop = LoopCurve.HALVING))
}

/** One deck at one instant: player volume (0..1) and the mixer settings. */
internal data class DeckMix(val volume: Float, val filter: DjFilterSettings)

internal data class MixFrame(val outgoing: DeckMix, val incoming: DeckMix)

/**
 * The shape of each transition over its progress, 0 at the hand-off and 1 when the outgoing song is
 * gone. Pure, so the curves can be tested and the controller only applies them.
 *
 * A beat-matched mix runs over whole bars and starts on a bar line, so the quarter, the middle and
 * the end of the progress all fall on downbeats.
 */
internal object TransitionCurves {
    /** The loudness match is eased in at the start, so the hand-off itself never jumps. */
    const val LOUDNESS_RAMP = 0.15f

    /** The key downbeat of a mix: its middle. */
    const val SWAP_AT = 0.5f

    /** One beat of a 16-bar mix: how long a swap or a cut takes. */
    const val SWAP_WIDTH = 1f / 64

    /** A low band at this level is out of the mix; mids and highs need less to step back. */
    const val LOW_CUT_DB = -20f
    const val MID_HIGH_CUT_DB = -12f

    /** In the full-level curves the incoming fader comes up over this share of the mix, filtered and without bass. */
    private const val JOIN = 0.2f

    /** Where the smooth crossfade brings the incoming song in and takes the outgoing one out. */
    private const val SMOOTH_IN = 0.4f
    private const val SMOOTH_OUT = 0.6f

    /** Resonant sweeps: the corner where the filters start or end, and the peak Q of the sweep. */
    private const val SWEEP_LOW_HZ = 120f
    private const val SWEEP_TOP_HZ = 20_000f
    private const val SWEEP_HIGH_PASS_END_HZ = 9_000f
    private const val SWEEP_PEAK_Q = 2.5f

    /** Rise: the outgoing high-pass tops out here, with a small lift so the build does not sound thinner. */
    private const val RISE_HIGH_PASS_END_HZ = 1_000f
    private const val RISE_LIFT_DB = 1.5

    private const val OPEN_HIGH_PASS_HZ = DjFilterMath.OPEN_HIGH_PASS_HZ.toFloat()
    private val FLAT_Q = DjFilterMath.FLAT_Q.toFloat()

    /**
     * [outgoingGain] brings the outgoing song to the incoming one's level. [swapAt] is the key
     * downbeat (the middle unless the caller moves it) and [swapWidth] one beat, as shares of the mix.
     */
    fun frame(
        style: TransitionStyle,
        progress: Float,
        outgoingGain: Float = 1f,
        swapAt: Float = SWAP_AT,
        swapWidth: Float = SWAP_WIDTH
    ): MixFrame = frame(style.recipe, progress, outgoingGain, swapAt, swapWidth)

    fun frame(
        recipe: MixRecipe,
        progress: Float,
        outgoingGain: Float = 1f,
        swapAt: Float = SWAP_AT,
        swapWidth: Float = SWAP_WIDTH
    ): MixFrame {
        val p = progress.coerceIn(0f, 1f)
        val match = 1f + (outgoingGain - 1f) * (p / LOUDNESS_RAMP).coerceAtMost(1f)
        val width = swapWidth.coerceIn(0.005f, 0.25f)
        if (recipe.riser) return rise(p, match, swapAt.coerceIn(0.05f, 0.9f), width)

        val middle = swapAt.coerceIn(0.2f, 0.8f)
        val (outVolume, inVolume) = volumes(recipe.volume, p, middle, width)
        val eq = eq(recipe.eq, p, middle, width)
        val outFilter = filterOut(recipe.filterOut, p, middle)
        val inFilter = filterIn(recipe.filterIn, p, middle)
        return MixFrame(
            outgoing = DeckMix(
                outVolume,
                DjFilterSettings(
                    gain = match,
                    bassDb = eq.outLow,
                    midDb = eq.outMid,
                    highDb = eq.outHigh,
                    highPassHz = outFilter.highPassHz,
                    lowPassHz = outFilter.lowPassHz,
                    resonance = outFilter.resonance
                )
            ),
            incoming = DeckMix(
                inVolume,
                DjFilterSettings(
                    bassDb = eq.inLow,
                    midDb = eq.inMid,
                    highDb = eq.inHigh,
                    highPassHz = inFilter.highPassHz,
                    lowPassHz = inFilter.lowPassHz,
                    resonance = inFilter.resonance
                )
            )
        )
    }

    /**
     * The style of one transition from the listener's [choice]. AUTO keeps calm songs on a fade that
     * dissolves into a reverb; on the beat it builds to a drop when the incoming song's rise lands as
     * the mix ends ([landsRise]), every other pair ([seed], see [pairSeed]) with a loop roll into it,
     * and blends with the EQ otherwise; off the beat it filters, since a filter hides drums that drift
     * apart, and echoes the outgoing song out. The two full-level styles need the beats to line up,
     * so off the beat they filter too. RISE is never picked by AUTO, since a hard cut only works when
     * asked for. Only AUTO plays effects: a style the listener picked plays as it is.
     */
    fun resolve(
        choice: MixStyle,
        beatMatched: Boolean,
        calm: Boolean,
        landsRise: Boolean = false,
        seed: Int = 0
    ): TransitionStyle =
        when (choice) {
            MixStyle.FADE -> TransitionStyle.FADE
            MixStyle.BASS_SWAP -> if (beatMatched) TransitionStyle.BASS_SWAP else TransitionStyle.FILTER
            MixStyle.EQ_SWAP -> if (beatMatched) TransitionStyle.EQ_SWAP else TransitionStyle.FILTER
            MixStyle.FILTER -> TransitionStyle.FILTER
            MixStyle.RISE -> TransitionStyle.RISE
            MixStyle.AUTO -> when {
                calm -> TransitionStyle.REVERB_FADE
                beatMatched && landsRise -> if (seed and 1 == 0) TransitionStyle.ROLL_DROP else TransitionStyle.BASS_SWAP
                beatMatched -> TransitionStyle.EQ_SWAP
                else -> TransitionStyle.ECHO_OUT
            }
        }

    /**
     * A number that stays the same for one pair of songs, so AUTO always mixes them the same way: in
     * the editor's preview as in playback, today as tomorrow.
     */
    fun pairSeed(fromId: String, toId: String): Int = "$fromId>$toId".hashCode() ushr 1

    /** Outgoing and incoming fader at [p]. */
    private fun volumes(curve: VolumeCurve, p: Float, middle: Float, width: Float): Pair<Float, Float> {
        val endCut = CrossfadeMath.fadeOutGain(((p - (1f - width)) / width).coerceIn(0f, 1f))
        val secondHalfOut = if (p <= middle) 1f else CrossfadeMath.fadeOutGain((p - middle) / (1f - middle))
        return when (curve) {
            VolumeCurve.CROSSFADE -> CrossfadeMath.fadeOutGain(p) to CrossfadeMath.fadeInGain(p)
            VolumeCurve.SMOOTH_CROSSFADE ->
                CrossfadeMath.fadeOutGain((p - SMOOTH_OUT) / width) to CrossfadeMath.fadeInGain((p - SMOOTH_IN + width) / width)
            VolumeCurve.OVERLAP -> endCut to CrossfadeMath.fadeInGain(p / JOIN)
            VolumeCurve.FADE_IN_FADE_OUT -> secondHalfOut to CrossfadeMath.fadeInGain(p / middle)
            VolumeCurve.CUT_IN_FADE_OUT -> secondHalfOut to CrossfadeMath.fadeInGain(p / width)
            VolumeCurve.FADE_IN_CUT_OUT -> endCut to CrossfadeMath.fadeInGain(p / middle)
            VolumeCurve.CENTER_CUT -> {
                val t = step(p, middle, width)
                CrossfadeMath.fadeOutGain(t) to CrossfadeMath.fadeInGain(t)
            }
        }
    }

    private class EqFrame(
        val outLow: Float = 0f,
        val outMid: Float = 0f,
        val outHigh: Float = 0f,
        val inLow: Float = 0f,
        val inMid: Float = 0f,
        val inHigh: Float = 0f
    )

    private fun eq(curve: EqCurve, p: Float, middle: Float, width: Float): EqFrame = when (curve) {
        EqCurve.NONE -> EqFrame()
        EqCurve.CENTER_BASS_SWAP, EqCurve.END_BASS_SWAP -> {
            val at = if (curve == EqCurve.END_BASS_SWAP) 1f - width else middle
            val t = step(p, at, width)
            EqFrame(outLow = swapOutDb(t, LOW_CUT_DB), inLow = swapInDb(t, LOW_CUT_DB))
        }
        EqCurve.THREE_BAND -> {
            val highs = step(p, middle / 2, width)
            val rest = step(p, middle, width)
            EqFrame(
                outLow = swapOutDb(rest, LOW_CUT_DB),
                outMid = swapOutDb(rest, MID_HIGH_CUT_DB),
                outHigh = swapOutDb(highs, MID_HIGH_CUT_DB),
                inLow = swapInDb(rest, LOW_CUT_DB),
                inMid = swapInDb(rest, MID_HIGH_CUT_DB),
                inHigh = swapInDb(highs, MID_HIGH_CUT_DB)
            )
        }
    }

    private class FilterFrame(val highPassHz: Float = 0f, val lowPassHz: Float = 0f, val resonance: Float)

    /** The incoming song's filter opens during the first half and is out of the way from the middle on. */
    private fun filterIn(curve: FilterCurve, p: Float, middle: Float): FilterFrame {
        val t = (p / middle).coerceAtMost(1f)
        if (curve == FilterCurve.NONE || t >= 1f) return FilterFrame(resonance = FLAT_Q)
        return when (curve) {
            FilterCurve.LOW_PASS -> FilterFrame(lowPassHz = logSweep(SWEEP_LOW_HZ, SWEEP_TOP_HZ, t), resonance = sweepQ(t))
            else -> FilterFrame(highPassHz = logSweep(SWEEP_HIGH_PASS_END_HZ, OPEN_HIGH_PASS_HZ, t), resonance = sweepQ(t))
        }
    }

    /** The outgoing song's filter stays open until the middle, then closes on it. */
    private fun filterOut(curve: FilterCurve, p: Float, middle: Float): FilterFrame {
        if (curve == FilterCurve.NONE || p <= middle) return FilterFrame(resonance = FLAT_Q)
        val t = ((p - middle) / (1f - middle)).coerceIn(0f, 1f)
        return when (curve) {
            FilterCurve.LOW_PASS -> FilterFrame(lowPassHz = logSweep(SWEEP_TOP_HZ, SWEEP_LOW_HZ, t), resonance = sweepQ(t))
            else -> FilterFrame(highPassHz = logSweep(OPEN_HIGH_PASS_HZ, SWEEP_HIGH_PASS_END_HZ, t), resonance = sweepQ(t))
        }
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

    /** 0..1 across a change that starts at [at] and lasts [width], always over by the end of the mix. */
    private fun step(p: Float, at: Float, width: Float): Float {
        val end = (at + width).coerceAtMost(1f)
        if (end <= at) return if (p >= at) 1f else 0f
        return ((p - at) / (end - at)).coerceIn(0f, 1f)
    }

    /** Resonance over a sweep (0..1): flat at the start, building to its peak, flat again at the end. */
    private fun sweepQ(t: Float): Float = when {
        t < 0.1f -> FLAT_Q
        t < 0.9f -> FLAT_Q + (SWEEP_PEAK_Q - FLAT_Q) * (t - 0.1f) / 0.8f
        else -> SWEEP_PEAK_Q + (FLAT_Q - SWEEP_PEAK_Q) * (t - 0.9f) / 0.1f
    }

    /** A band handed over on an equal-power curve as [t] goes 0..1: never doubled, never missing. */
    private fun swapOutDb(t: Float, floorDb: Float): Float = bandDb(cos(t * PI / 2), floorDb)

    private fun swapInDb(t: Float, floorDb: Float): Float = bandDb(sin(t * PI / 2), floorDb)

    /** From [from] to [to] at [t] (0..1), evenly in log frequency. */
    private fun logSweep(from: Float, to: Float, t: Float): Float =
        from * (to / from).pow(t.coerceIn(0f, 1f))

    private fun bandDb(amplitude: Double, floorDb: Float): Float =
        if (amplitude >= 0.9999) 0f
        else (20 * log10(amplitude.coerceAtLeast(1e-6))).toFloat().coerceAtLeast(floorDb)
}
