package org.sonora.android.playback

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.sonora.android.playback.automix.BeatGrid
import org.sonora.android.playback.automix.DjAudio
import org.sonora.android.playback.automix.DjFilterProcessor
import org.sonora.android.playback.automix.Loudness
import org.sonora.android.playback.automix.MixRecipe
import org.sonora.android.playback.automix.TransitionCurves
import org.sonora.android.playback.automix.TransitionStyle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Timing of a crossfade, apart from the players so it can be tested. */
internal object CrossfadeMath {
    const val MAX_SECONDS = 12

    /** Shorter songs (intros, skits) just end. */
    const val MIN_TRACK_MS = 30_000L

    /**
     * How long before the hand-off a transition is set up: the second player loads the song, starts
     * muted [CrossfadeController.PRE_ROLL_MS] early and lines itself up with the main one.
     */
    const val PREPARE_LEAD_MS = 4_500L
    private const val MIN_PREPARE_MS = 700L
    private const val MIN_OVERLAP_MS = 1_000L

    /**
     * Where in the outgoing song the next one takes over, or null when the song should simply end:
     * crossfade off, a short song, too early to prepare, or too late for an audible overlap.
     */
    fun handoffPositionMs(positionMs: Long, durationMs: Long, fadeMs: Long): Long? {
        if (fadeMs <= 0L || durationMs < MIN_TRACK_MS || positionMs < 0L) return null
        if (durationMs - positionMs > fadeMs + PREPARE_LEAD_MS) return null
        val handoff = maxOf(durationMs - fadeMs, positionMs + MIN_PREPARE_MS)
        return handoff.takeIf { durationMs - it >= MIN_OVERLAP_MS }
    }

    /** Equal-power curves: the sum of both songs keeps a steady loudness through the overlap. */
    fun fadeOutGain(progress: Float): Float = cos(progress.coerceIn(0f, 1f) * PI / 2).toFloat()

    fun fadeInGain(progress: Float): Float = sin(progress.coerceIn(0f, 1f) * PI / 2).toFloat()
}

/** What an Automix transition needs once the incoming song plays: both beat grids and the tempo match. */
internal class BeatMatch(
    val incomingId: String,
    val outgoing: BeatGrid,
    val incoming: BeatGrid,
    /** Tempo of the incoming song during the mix, as a factor of its own. */
    val incomingRate: Float
) {
    /** Bars line up when both songs have downbeats; otherwise only beats do. */
    val period: Int = if (outgoing.beatsPerBar > 1 && incoming.beatsPerBar > 1) outgoing.beatsPerBar else 1
}

/**
 * Crossfades without touching the media session: at the hand-off a second, private player carries on
 * with the tail of the outgoing song and fades out, while the main player (the one the session,
 * notification and equalizer know) moves to the next song and fades in.
 */
@OptIn(UnstableApi::class)
internal class CrossfadeController(
    private val context: Context,
    private val scope: CoroutineScope
) {

    private companion object {
        const val POLL_MS = 20L

        /** The second player starts this long before the hand-off, muted, on the same song. */
        const val PRE_ROLL_MS = 3_000L
        const val MIN_TAIL_PREPARE_MS = 700L

        /** Closer than this, the two copies of the song cross without a flam. */
        const val SYNC_TOLERANCE_MS = 12L

        /**
         * Further apart than this, a seek is quicker than a tempo nudge. A seek restarts the audio
         * with its own delay, so below this the nudge does the work.
         */
        const val SYNC_SEEK_MS = 1_500L

        /**
         * A nudge closes the gap over about this long. The second player is muted while it catches
         * up, so it can run well off tempo: a 30% nudge makes up 300 ms every second.
         */
        const val SYNC_WINDOW_MS = 400.0
        const val MAX_SYNC_NUDGE = 0.3
        const val SYNC_STEP_MS = 100L

        /** The two players trade the song over this long, on an equal-power curve: no gap, no bump. */
        const val HANDOFF_FADE_MS = 120L
        const val RAMP_STEP_MS = 40L
        const val HANDOFF_SLACK_MS = 30L

        /** Automix waits this long for the incoming song before settling for a plain crossfade. */
        const val BEAT_MATCH_WAIT_MS = 4_000L
        const val ALIGN_TOLERANCE_BEATS = 0.03
        /**
         * A freshly started or seeked player reports its position from a rough latency estimate
         * until the audio timestamps arrive; measured earlier, the phase is off by about 70 ms.
         */
        const val SETTLE_MS = 800L

        /** Each tempo change briefly drains the time stretcher, so corrections are spaced out. */
        const val CORRECTION_INTERVAL_MS = 750L
        /** A measured error is absorbed over this long, not in one jump. */
        const val ABSORB_MS = 1_500L
        const val CORRECTION_THRESHOLD_BEATS = 0.02
        const val LOCK_WAIT_MS = 4_000L
        const val MAX_CORRECTION = 0.05

        /**
         * A reading this far from the one before, more than any correction could have moved it, is a
         * glitch of a grid (a quiet intro, a fill) rather than drift: it is not acted on.
         */
        const val GLITCH_BEATS = 0.3
        const val LOG_TAG = "Automix"

        /** Past this, the mix ends even if the outgoing song's clock stalled. */
        const val MIX_OVERRUN_MS = 3_000L

        /** The incoming fader eases up over this long once the bars are locked, so joining late never pops. */
        const val JOIN_RAMP_MS = 600L

        /**
         * After the mix the incoming song returns to its own tempo the way Spotify's Automix does: a
         * half-percent step every second, starting half a second after the outgoing song is gone.
         */
        const val TEMPO_RETURN_OFFSET_MS = 500L
        const val TEMPO_RETURN_STEP_MS = 1_000L
        const val TEMPO_RETURN_STEP = 0.005f
    }

    private var job: Job? = null

    /** The incoming song's way back to its own tempo; it outlives the mix and is not part of [isRunning]. */
    private var tempoReturn: Job? = null
    private var tail: ExoPlayer? = null
    private var tailDeck: DjFilterProcessor? = null
    private var main: ExoPlayer? = null

    /** The listener's speed, to put back if an Automix is cut short while it bends the tempo. */
    private var restoreSpeed: Float? = null

    val isRunning: Boolean
        get() = job?.isActive == true

    /**
     * Loads the end of the current song on a second player. When [main] reaches [handoffMs] it
     * calls [advance], which must pause the main player and move it to the next song. If the tail
     * is not ready by then, nothing happens and the song ends as usual.
     */
    fun start(
        main: ExoPlayer,
        handoffMs: Long,
        fadeMs: Long,
        speed: Float,
        beatMatch: BeatMatch? = null,
        /** Brings the outgoing song to the incoming one's level during the overlap. */
        outgoingGainDb: Double = 0.0,
        /** How the two songs meet. */
        style: TransitionStyle = TransitionStyle.FADE,
        /** The ingredients to mix with, when they are not one of the ready-made styles (a playlist's custom transition). */
        recipe: MixRecipe = style.recipe,
        /**
         * Where the beat-matched mix itself starts, at or after [handoffMs]: the stretch before it
         * lets the bars lock while the incoming song is still silent.
         */
        mixStartMs: Long = handoffMs,
        advance: () -> Unit
    ) {
        cancel()
        val item = main.currentMediaItem ?: return
        val outgoingId = item.mediaId
        val outgoingGain = Loudness.gain(outgoingGainDb)
        // The second player starts a little before the hand-off, muted, on the same song, so that by
        // the hand-off it already plays in step with the main one and the two can simply cross.
        val tailStartMs = maxOf(handoffMs - PRE_ROLL_MS, main.currentPosition + MIN_TAIL_PREPARE_MS)
            .coerceAtMost(handoffMs)
        val tailPlayer = buildTailPlayer(main, speed).apply {
            volume = 0f
            setMediaItem(item, tailStartMs)
            prepare()
        }
        tail = tailPlayer
        this.main = main
        // The overlap is song time: at 2x it passes in half the real time.
        val realFadeMs = (fadeMs / speed.coerceAtLeast(0.1f)).toLong().coerceAtLeast(1L)

        job = scope.launch {
            if (!waitFor(main, outgoingId, tailStartMs)) {
                finish()
                return@launch
            }
            if (tailPlayer.playbackState != Player.STATE_READY) {
                finish()
                return@launch
            }
            tailPlayer.play()
            syncTail(main, tailPlayer, outgoingId, handoffMs, speed)
            if (!waitFor(main, outgoingId, handoffMs - HANDOFF_FADE_MS)) {
                finish()
                return@launch
            }
            crossHandoff(main, tailPlayer)
            val handoffAt = SystemClock.elapsedRealtime()
            advance()

            if (beatMatch != null &&
                mixOnTheBeat(
                    main, tailPlayer, beatMatch,
                    mixStartMs.coerceIn(handoffMs, handoffMs + fadeMs - 1),
                    fadeMs - (mixStartMs - handoffMs).coerceIn(0L, fadeMs - 1),
                    handoffAt + realFadeMs, speed, outgoingGain, recipe
                )
            ) {
                finish()
                return@launch
            }

            // Both decks follow the wall clock from the hand-off. The next song may still be resolving:
            // it joins the mix wherever it is, and if it starts after the mix it simply plays in full.
            Log.i(LOG_TAG, "Crossfade %s over %d ms".format(recipe, realFadeMs))
            runMix(main, tailPlayer, recipe, outgoingGain) {
                (SystemClock.elapsedRealtime() - handoffAt).toFloat() / realFadeMs
            }
            finish()
        }
    }

    /** Waits until [main] reaches [positionMs] of the song; false when it stops or moves to another song first. */
    private suspend fun waitFor(main: ExoPlayer, songId: String, positionMs: Long): Boolean {
        while (main.currentPosition < positionMs - HANDOFF_SLACK_MS) {
            if (!main.isPlaying || main.currentMediaItem?.mediaId != songId) return false
            delay(POLL_MS)
        }
        return true
    }

    /**
     * Brings the muted second player in step with the main one before the hand-off: a seek when they
     * are far apart, then small tempo nudges, which the AudioTrack applies without a gap. Both play
     * the same song, so their positions compare directly.
     */
    private suspend fun syncTail(main: ExoPlayer, tail: ExoPlayer, songId: String, handoffMs: Long, speed: Float) {
        val lastCheck = handoffMs - HANDOFF_FADE_MS - SYNC_STEP_MS
        var settledAt = SystemClock.elapsedRealtime() + SETTLE_MS
        var nudged = false
        var offset = 0L
        var firstOffset: Long? = null
        while (main.currentPosition < lastCheck && main.currentMediaItem?.mediaId == songId && main.isPlaying) {
            delay(SYNC_STEP_MS)
            // Fresh players report a rough position until the audio timestamps arrive.
            if (SystemClock.elapsedRealtime() < settledAt || !tail.isPlaying) continue
            offset = tail.currentPosition - main.currentPosition
            if (firstOffset == null) firstOffset = offset
            when {
                abs(offset) > SYNC_SEEK_MS -> {
                    tail.seekTo((tail.currentPosition - offset).coerceAtLeast(0L))
                    settledAt = SystemClock.elapsedRealtime() + SETTLE_MS
                }
                abs(offset) <= SYNC_TOLERANCE_MS -> if (nudged) {
                    tail.setPlaybackParameters(PlaybackParameters(speed))
                    nudged = false
                }
                else -> {
                    val nudge = (offset / SYNC_WINDOW_MS).coerceIn(-MAX_SYNC_NUDGE, MAX_SYNC_NUDGE)
                    tail.setPlaybackParameters(PlaybackParameters((speed * (1 - nudge)).toFloat()))
                    nudged = true
                }
            }
        }
        if (nudged) tail.setPlaybackParameters(PlaybackParameters(speed))
        Log.i(LOG_TAG, "Hand-off: second player %+d ms from the main one (first reading %+d ms)".format(offset, firstOffset ?: offset))
    }

    /** The main player hands the song to the second one over [HANDOFF_FADE_MS]: both play it, so nothing drops out. */
    private suspend fun crossHandoff(main: ExoPlayer, tail: ExoPlayer) {
        val start = SystemClock.elapsedRealtime()
        while (true) {
            val t = ((SystemClock.elapsedRealtime() - start).toFloat() / HANDOFF_FADE_MS).coerceIn(0f, 1f)
            main.volume = CrossfadeMath.fadeOutGain(t)
            tail.volume = CrossfadeMath.fadeInGain(t)
            if (t >= 1f) break
            delay(POLL_MS)
        }
        main.volume = 0f
        tail.volume = 1f
    }

    /** Stops any crossfade at once and gives the main player its full volume back. */
    fun cancel() {
        job?.cancel()
        stopTempoReturn()
        finish()
    }

    private fun finish() {
        job = null
        releaseTail()
        main?.volume = 1f
        DjAudio.main?.setNeutral()
        restoreSpeed?.let { main?.setPlaybackParameters(PlaybackParameters(it)) }
        restoreSpeed = null
        main = null
    }

    /**
     * The Automix transition. The incoming song starts silent; it is moved onto the outgoing song's
     * bar, held there by small tempo corrections while the mix runs, then eased back to its own
     * tempo. False when the incoming song did not start in time: the caller does a plain crossfade.
     */
    private suspend fun mixOnTheBeat(
        main: ExoPlayer,
        tail: ExoPlayer,
        match: BeatMatch,
        handoffMs: Long,
        mixMs: Long,
        /** Wall-clock time the mix should be over by. */
        mixEndAt: Long,
        speed: Float,
        outgoingGain: Float,
        style: MixRecipe
    ): Boolean = coroutineScope {
        val started = withTimeoutOrNull(BEAT_MATCH_WAIT_MS) {
            while (!(main.isPlaying && main.currentMediaItem?.mediaId == match.incomingId)) delay(POLL_MS)
            true
        }
        if (started != true) return@coroutineScope false

        // The incoming deck takes the style's opening settings before anything of the song can be heard.
        DjAudio.main?.set(TransitionCurves.frame(style, 0f).incoming.filter)
        val base = speed * match.incomingRate
        restoreSpeed = speed
        main.setPlaybackParameters(PlaybackParameters(base))

        // While the incoming song is still silent it catches up with strong tempo nudges, which the
        // AudioTrack applies without a gap; a seek would restart the audio with a delay of its own.
        lockIncoming(main, tail, match, base)
        Log.i(LOG_TAG, "Automix alignment done, last reading %.3f beats".format(phaseError(main, tail, match)))

        // During the overlap, a rare tempo nudge cancels drift left by the tempo estimates.
        val corrector = launch {
            var applied = base
            var previous: Double? = null
            while (isActive) {
                delay(CORRECTION_INTERVAL_MS)
                val error = phaseError(main, tail, match)
                val glitch = previous?.let { abs(BeatGrid.wrapCentered(error - it, match.period.toDouble())) > GLITCH_BEATS } == true
                previous = error
                val target = if (glitch) {
                    applied
                } else if (abs(error) > CORRECTION_THRESHOLD_BEATS) {
                    val correction = (error * match.incoming.beatSeconds * 1_000 / ABSORB_MS)
                        .coerceIn(-MAX_CORRECTION, MAX_CORRECTION)
                    (base * (1 - correction)).toFloat()
                } else {
                    base
                }
                if (abs(target - applied) > 0.001f) {
                    main.setPlaybackParameters(PlaybackParameters(target))
                    applied = target
                }
                Log.d(
                    LOG_TAG,
                    "Automix check: error %.3f beats%s, rate %.4f (player %.4f), out %d ms, in %d ms".format(
                        error, if (glitch) " (ignored)" else "", target, main.playbackParameters.speed,
                        tail.currentPosition, main.currentPosition
                    )
                )
            }
        }
        Log.i(LOG_TAG, "Automix %s starts: phase error %.3f beats".format(style, phaseError(main, tail, match)))
        mixOnSongClock(main, tail, match, handoffMs, mixMs, mixEndAt, outgoingGain, style)
        corrector.cancel()
        Log.i(LOG_TAG, "Automix %s mixed: phase error %.3f beats at the end".format(style, phaseError(main, tail, match)))
        releaseTail()
        main.volume = 1f
        DjAudio.main?.setNeutral()

        // Back to the song's own tempo, gradually, now that nothing plays against it.
        restoreSpeed = null
        returnToTempo(main, match.incomingId, from = main.playbackParameters.speed, to = speed)
        true
    }

    /**
     * Pulls the silent incoming song onto the outgoing song's bar: a nudge proportional to the phase
     * error, up to [MAX_SYNC_NUDGE] off the tempo, until the error is under [ALIGN_TOLERANCE_BEATS]
     * or [LOCK_WAIT_MS] passes. Only an error of more than a beat, which the grids rarely leave, is
     * taken out with a seek first.
     */
    private suspend fun lockIncoming(main: ExoPlayer, tail: ExoPlayer, match: BeatMatch, base: Float) {
        val beatMs = match.incoming.beatSeconds * 1_000
        withTimeoutOrNull(LOCK_WAIT_MS) {
            delay(SETTLE_MS)
            var seeked = false
            while (true) {
                val error = phaseError(main, tail, match)
                if (abs(error) <= ALIGN_TOLERANCE_BEATS) break
                if (abs(error) > 1.0 && !seeked) {
                    main.seekTo((main.currentPosition - (error * beatMs).toLong()).coerceAtLeast(0L))
                    seeked = true
                    delay(SETTLE_MS)
                    continue
                }
                val nudge = (error * beatMs / SYNC_WINDOW_MS).coerceIn(-MAX_SYNC_NUDGE, MAX_SYNC_NUDGE)
                main.setPlaybackParameters(PlaybackParameters((base * (1 - nudge)).toFloat()))
                delay(SYNC_STEP_MS)
            }
        }
        main.setPlaybackParameters(PlaybackParameters(base))
    }

    /**
     * Glides [main] from [from] to [to] in small steps, unless the song changes or someone else sets
     * a speed meanwhile; then the glide stops and leaves the speed alone. Cancelled, it goes to [to].
     */
    private fun returnToTempo(main: ExoPlayer, songId: String, from: Float, to: Float) {
        stopTempoReturn()
        if (abs(from - to) < 1e-4f) {
            main.setPlaybackParameters(PlaybackParameters(to))
            return
        }
        tempoReturn = scope.launch {
            var applied = from
            try {
                delay(TEMPO_RETURN_OFFSET_MS)
                val step = TEMPO_RETURN_STEP * to
                while (isActive && abs(applied - to) > 1e-4f) {
                    if (main.currentMediaItem?.mediaId != songId) return@launch
                    if (abs(main.playbackParameters.speed - applied) > 1e-3f) return@launch
                    applied = if (applied > to) maxOf(to, applied - step) else minOf(to, applied + step)
                    main.setPlaybackParameters(PlaybackParameters(applied))
                    if (abs(applied - to) > 1e-4f) delay(TEMPO_RETURN_STEP_MS)
                }
                Log.i(LOG_TAG, "Automix back to its own tempo")
            } finally {
                if (abs(applied - to) > 1e-4f && main.currentMediaItem?.mediaId == songId &&
                    abs(main.playbackParameters.speed - applied) <= 1e-3f
                ) {
                    main.setPlaybackParameters(PlaybackParameters(to))
                }
            }
        }
    }

    private fun stopTempoReturn() {
        tempoReturn?.cancel()
        tempoReturn = null
    }

    /**
     * Runs the mixer on the outgoing song's clock from the planned hand-off, so each key moment of the
     * style (a swap, the cut) lands on the downbeat the plan put it on. The part used to lock the bars
     * has already passed: the incoming song joins the mix where it then is.
     */
    private suspend fun mixOnSongClock(
        main: ExoPlayer,
        tail: ExoPlayer,
        match: BeatMatch,
        handoffMs: Long,
        mixMs: Long,
        mixEndAt: Long,
        outgoingGain: Float,
        style: MixRecipe
    ) {
        val span = mixMs.coerceAtLeast(1L).toDouble()
        val beat = (match.outgoing.beatSeconds * 1_000 / span).toFloat()
        val deadline = mixEndAt + MIX_OVERRUN_MS
        Log.i(
            LOG_TAG,
            "Automix %s on the song clock from %.2f of %d ms".format(style, (tail.currentPosition - handoffMs) / span, mixMs)
        )
        runMix(main, tail, style, outgoingGain, swapWidth = beat) {
            val over = tail.playbackState == Player.STATE_ENDED || SystemClock.elapsedRealtime() >= deadline
            if (over) 1f else ((tail.currentPosition - handoffMs) / span).toFloat()
        }
    }

    /**
     * Applies [style] to both decks until [progress] reaches 1. The incoming deck stays silent, on the
     * current frame's filter, until the main player actually plays; then it joins the frame where it is.
     * Polled rather than stepped, so a busy main thread cannot stretch the mix.
     */
    private suspend fun runMix(
        main: ExoPlayer,
        tail: ExoPlayer,
        style: MixRecipe,
        outgoingGain: Float,
        swapAt: Float = TransitionCurves.SWAP_AT,
        swapWidth: Float = TransitionCurves.SWAP_WIDTH,
        progress: () -> Float
    ) {
        var joinedAt = 0L
        while (true) {
            val p = progress().coerceIn(0f, 1f)
            val frame = TransitionCurves.frame(style, p, outgoingGain, swapAt, swapWidth)
            if (joinedAt == 0L && main.isPlaying) joinedAt = SystemClock.elapsedRealtime()
            val join = if (joinedAt == 0L) 0f
            else ((SystemClock.elapsedRealtime() - joinedAt).toFloat() / JOIN_RAMP_MS).coerceIn(0f, 1f)
            main.volume = frame.incoming.volume * if (p >= 1f) 1f else join
            DjAudio.main?.set(frame.incoming.filter)
            tail.volume = frame.outgoing.volume
            tailDeck?.set(frame.outgoing.filter)
            if (p >= 1f) return
            delay(RAMP_STEP_MS)
        }
    }

    /** How far the incoming song's bar is ahead of the outgoing one's, in beats. */
    private fun phaseError(main: ExoPlayer, tail: ExoPlayer, match: BeatMatch): Double {
        val outgoing = match.outgoing.barPhase(tail.currentPosition / 1_000.0)
        val incoming = match.incoming.barPhase(main.currentPosition / 1_000.0)
        return BeatGrid.wrapCentered(incoming - outgoing, match.period.toDouble())
    }

    private fun releaseTail() {
        tail?.release()
        tail = null
        tailDeck = null
    }

    private fun buildTailPlayer(main: ExoPlayer, speed: Float): ExoPlayer {
        val attributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
        val sourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(AudioCacheManager.getCacheDataSourceFactory(context))
        val deck = DjFilterProcessor().also { tailDeck = it }
        // No audio focus: requesting it would pause the main player, which already holds it.
        return ExoPlayer.Builder(context)
            .setRenderersFactory(DjAudio.renderersFactory(context, deck))
            .setAudioAttributes(attributes, /* handleAudioFocus = */ false)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setMediaSourceFactory(sourceFactory)
            .build()
            .apply {
                // Same audio session as the main player, so the equalizer shapes the tail too.
                val sessionId = main.audioSessionId
                if (sessionId != C.AUDIO_SESSION_ID_UNSET) setAudioSessionId(sessionId)
                AudioRouting.infoFor(context, AudioRouting.selectedId.value)?.let(::setPreferredAudioDevice)
                setPlaybackParameters(PlaybackParameters(speed))
            }
    }
}
