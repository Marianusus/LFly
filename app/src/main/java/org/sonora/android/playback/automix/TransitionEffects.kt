package org.sonora.android.playback.automix

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow

/**
 * Spotify's "Effects" ingredient: a send effect on the outgoing song. "Out" lets the song fade into
 * the effect, "cut" cuts it on the last beat and leaves the effect's tail ringing over the new song.
 */
enum class EffectCurve(internal val kind: Kind, internal val beats: Double, internal val ending: Ending) {
    NONE(Kind.NONE, 0.0, Ending.NONE),
    ECHO_HALF_OUT(Kind.ECHO, 0.5, Ending.OUT_END),
    ECHO_3Q_OUT(Kind.ECHO, 0.75, Ending.OUT_END),
    ECHO_1_OUT(Kind.ECHO, 1.0, Ending.OUT_END),
    ECHO_HALF_CUT(Kind.ECHO, 0.5, Ending.CUT_END),
    ECHO_3Q_CUT(Kind.ECHO, 0.75, Ending.CUT_END),
    ECHO_1_CUT(Kind.ECHO, 1.0, Ending.CUT_END),
    DELAY_HALF_CUT(Kind.DELAY, 0.5, Ending.CUT_END),
    DELAY_3Q_CUT(Kind.DELAY, 0.75, Ending.CUT_END),
    REVERB_OUT_CENTER(Kind.REVERB, 0.0, Ending.OUT_CENTER),
    REVERB_OUT_END(Kind.REVERB, 0.0, Ending.OUT_END),
    REVERB_CUT_END(Kind.REVERB, 0.0, Ending.CUT_END),
    NOISE_OUT_END(Kind.NOISE, 0.0, Ending.OUT_END);

    internal enum class Kind { NONE, ECHO, DELAY, REVERB, NOISE }
    internal enum class Ending { NONE, OUT_CENTER, OUT_END, CUT_END }
}

/** Spotify's "Jogwheel" ingredient: the outgoing song stops like a record, over its last beats. */
enum class JogwheelCurve(internal val spinback: Boolean, internal val beats: Double) {
    NONE(false, 0.0),
    VINYL_STOP_FAST_1(false, 1.0),
    VINYL_STOP_FAST_2(false, 2.0),
    VINYL_STOP_FAST_4(false, 4.0),
    VINYL_STOP_SLOW(false, 8.0),
    SPINBACK_1(true, 1.0),
    SPINBACK_2(true, 2.0),
    SPINBACK_4(true, 4.0)
}

/** Spotify's "Looping" ingredient: the outgoing song holds a loop instead of running on. */
enum class LoopCurve(internal val beats: Double) {
    NONE(0.0),
    ONE_BEAT(1.0),
    TWO_BEAT(2.0),
    FOUR_BEAT(4.0),
    EIGHT_BEAT(8.0),
    SIXTEEN_BEAT(16.0),

    /** A loop roll over the last two bars: two beats, then one, then halving down to an eighth. */
    HALVING(2.0)
}

/** The send effect at one instant: the outgoing song's own level, what it sends, and the effect's return. */
internal class FxFrame(
    val dry: Float = 1f,
    val send: Float = 0f,
    val wet: Float = 1f,
    val noise: Float = 0f,
    val noiseHz: Float = NOISE_LOW_HZ
) {
    companion object {
        const val NOISE_LOW_HZ = 400f
        val NONE = FxFrame()
    }
}

/** Where the loop is at one instant: its start and length, both in beats from the start of the mix. */
internal class LoopFrame(val anchorBeats: Double, val lengthBeats: Double)

/**
 * The shape of the effect ingredients over a mix's progress, 0 at the start and 1 when the outgoing
 * song is gone. Progress past 1 is the effect's tail, while it rings over the new song. Pure, so the
 * shapes can be tested; the deck plays them on the song's own clock.
 */
internal object TransitionEffects {
    /** Feedback of an echo (darker, longer repeats) and of a delay (clean, a few repeats). */
    const val ECHO_FEEDBACK = 0.55f
    const val DELAY_FEEDBACK = 0.35f

    /** How long each effect may ring once the mix is over; the deck fades it during the last part. */
    private const val ECHO_TAIL_MS = 3_000L
    private const val DELAY_TAIL_MS = 2_000L
    private const val REVERB_TAIL_MS = 3_500L
    private const val NOISE_TAIL_MS = 250L
    private const val TAIL_FADE_SHARE = 0.4f

    private const val NOISE_HIGH_HZ = 7_000f
    private const val NOISE_PEAK = 0.12f

    /** Turns of a spinback at its fastest, backwards, and the share of it spent reversing the record. */
    const val SPINBACK_SPEED = 3.0
    private const val SPINBACK_ATTACK = 0.06
    private const val SPINBACK_FADE_FROM = 0.6
    private const val VINYL_FAST_EXPONENT = 1.5
    private const val VINYL_SLOW_EXPONENT = 1.0
    private const val VINYL_FADE_FROM = 0.85

    /** How long [curve] rings after the mix, in ms of the outgoing song. */
    fun tailMs(curve: EffectCurve): Long = when (curve.kind) {
        EffectCurve.Kind.NONE -> 0L
        EffectCurve.Kind.ECHO -> ECHO_TAIL_MS
        EffectCurve.Kind.DELAY -> DELAY_TAIL_MS
        EffectCurve.Kind.REVERB -> REVERB_TAIL_MS
        EffectCurve.Kind.NOISE -> NOISE_TAIL_MS
    }

    fun feedback(curve: EffectCurve): Float = if (curve.kind == EffectCurve.Kind.DELAY) DELAY_FEEDBACK else ECHO_FEEDBACK

    /**
     * The send effect at [p] (past 1 in the tail). [beat] is one beat as a share of the mix; [tail] the
     * tail as a share of the mix. The send always closes by the time the song's own level is gone,
     * so the effect only repeats what was heard.
     */
    fun effect(curve: EffectCurve, p: Float, beat: Float, tail: Float = 0.1f): FxFrame {
        if (curve.kind == EffectCurve.Kind.NONE) return FxFrame.NONE
        val b = beat.coerceIn(0.005f, 0.25f)
        val lastBeat = 1f - b
        val wet = if (p <= 1f) 1f else tailFade((p - 1f) / tail.coerceAtLeast(1e-4f))
        if (curve.kind == EffectCurve.Kind.NOISE) {
            // A white-noise riser: up over the second half under a rising high-pass, gone on the end.
            val t = ramp(p, 0.5f, lastBeat)
            val level = if (p >= 1f) 0f else NOISE_PEAK * t * t
            return FxFrame(noise = level, noiseHz = logSweep(FxFrame.NOISE_LOW_HZ, NOISE_HIGH_HZ, t))
        }
        return when (curve.ending) {
            EffectCurve.Ending.OUT_CENTER -> FxFrame(
                dry = fadeOut(ramp(p, 0.3f, 0.5f)),
                send = ramp(p, 0.2f, 0.4f) * (1f - ramp(p, 0.45f, 0.5f)),
                wet = wet
            )
            EffectCurve.Ending.OUT_END -> FxFrame(
                dry = fadeOut(ramp(p, 0.75f, 1f)),
                send = if (p >= 1f) 0f else ramp(p, 0.5f, 0.75f),
                wet = wet
            )
            EffectCurve.Ending.CUT_END -> {
                val sendFrom = if (curve.kind == EffectCurve.Kind.REVERB) 0.6f else 0.75f
                val cut = p >= lastBeat
                FxFrame(
                    dry = if (cut) 0f else 1f,
                    send = if (cut) 0f else ramp(p, sendFrom, (sendFrom + lastBeat) / 2),
                    wet = wet
                )
            }
            EffectCurve.Ending.NONE -> FxFrame.NONE
        }
    }

    /** Where the jogwheel starts and how long it lasts, as shares of the mix; never longer than the mix. */
    fun jogSpan(curve: JogwheelCurve, beat: Float): Pair<Float, Float>? {
        if (curve == JogwheelCurve.NONE) return null
        val length = (curve.beats.toFloat() * beat).coerceIn(1e-4f, 1f)
        return (1f - length) to length
    }

    /** Playback rate of the record at [u] (0..1 across the jogwheel): 1 is the song's own speed, below 0 backwards. */
    fun jogRate(curve: JogwheelCurve, u: Double): Double {
        val t = u.coerceIn(0.0, 1.0)
        return if (curve.spinback) {
            if (t < SPINBACK_ATTACK) {
                1.0 - (1.0 + SPINBACK_SPEED) * t / SPINBACK_ATTACK
            } else {
                val left = (1.0 - t) / (1.0 - SPINBACK_ATTACK)
                -SPINBACK_SPEED * left * left
            }
        } else {
            val exponent = if (curve == JogwheelCurve.VINYL_STOP_SLOW) VINYL_SLOW_EXPONENT else VINYL_FAST_EXPONENT
            (1.0 - t).pow(exponent)
        }
    }

    /** Level of the record at [u]: it dies away as it stops, so it never ends on a click. */
    fun jogLevel(curve: JogwheelCurve, u: Double): Double {
        if (u >= 1.0) return 0.0
        val from = if (curve.spinback) SPINBACK_FADE_FROM else VINYL_FADE_FROM
        if (u <= from) return 1.0
        return cos((u - from) / (1.0 - from) * PI / 2)
    }

    /** How many beats back a spinback reads at most, so the deck keeps enough of the song. */
    fun jogReachBeats(curve: JogwheelCurve): Double = if (curve.spinback) curve.beats * 1.1 else curve.beats

    /**
     * The loop at [p], or null when the song runs freely. A plain loop is set at the start of the mix;
     * the halving one rolls over the last two bars.
     */
    fun loop(curve: LoopCurve, p: Float, mixBeats: Double): LoopFrame? {
        val beat = p * mixBeats
        val length = loopLengthBeats(curve, beat, mixBeats)
        return if (length > 0.0) LoopFrame(loopAnchorBeats(curve, mixBeats), length) else null
    }

    /** Where the loop starts, in beats from the start of the mix. */
    fun loopAnchorBeats(curve: LoopCurve, mixBeats: Double): Double =
        if (curve == LoopCurve.HALVING) (mixBeats - HALVING_BEATS).coerceAtLeast(0.0) else 0.0

    /** The loop's length at [beat] of the mix, in beats; 0 while the song runs freely. Allocation-free for the deck. */
    fun loopLengthBeats(curve: LoopCurve, beat: Double, mixBeats: Double): Double {
        if (curve == LoopCurve.NONE || mixBeats <= 0.0) return 0.0
        if (beat < loopAnchorBeats(curve, mixBeats)) return 0.0
        if (curve != LoopCurve.HALVING) return curve.beats
        val left = mixBeats - beat
        return when {
            left > 4.0 -> 2.0
            left > 2.0 -> 1.0
            left > 1.0 -> 0.5
            left > 0.5 -> 0.25
            else -> 0.125
        }
    }

    private const val HALVING_BEATS = 8.0

    /** The longest loop [curve] holds, in beats: how much of the song the deck keeps for it. */
    fun loopReachBeats(curve: LoopCurve): Double = curve.beats

    private fun ramp(p: Float, from: Float, to: Float): Float =
        if (to <= from) (if (p >= from) 1f else 0f) else ((p - from) / (to - from)).coerceIn(0f, 1f)

    private fun fadeOut(t: Float): Float = cos(t.coerceIn(0f, 1f) * PI / 2).toFloat()

    private fun tailFade(t: Float): Float {
        val from = 1f - TAIL_FADE_SHARE
        if (t <= from) return 1f
        return fadeOut((t - from) / TAIL_FADE_SHARE)
    }

    private fun logSweep(from: Float, to: Float, t: Float): Float = from * (to / from).pow(t.coerceIn(0f, 1f))
}
