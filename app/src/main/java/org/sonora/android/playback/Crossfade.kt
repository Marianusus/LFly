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

    /** Time the second player gets to load the tail of the song from the cache. */
    const val PREPARE_LEAD_MS = 1_500L
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
        const val RAMP_STEP_MS = 40L
        const val HANDOFF_SLACK_MS = 30L

        /** Automix waits this long for the incoming song before settling for a plain crossfade. */
        const val BEAT_MATCH_WAIT_MS = 4_000L
        const val ALIGN_TOLERANCE_BEATS = 0.03
        const val ALIGN_ATTEMPTS = 3
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
        const val LOCK_WAIT_MS = 3_000L
        const val LOCK_TOLERANCE_BEATS = 0.06
        const val MAX_CORRECTION = 0.05
        const val LOG_TAG = "Automix"

        const val MIN_MIX_SPAN_MS = 2_000L
        /** Past this, the mix ends even if the outgoing song's clock stalled. */
        const val MIX_OVERRUN_MS = 3_000L
    }

    private var job: Job? = null
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
        advance: () -> Unit
    ) {
        cancel()
        val item = main.currentMediaItem ?: return
        val outgoingId = item.mediaId
        val outgoingGain = Loudness.gain(outgoingGainDb)
        val tailPlayer = buildTailPlayer(main, speed).apply {
            setMediaItem(item, handoffMs)
            prepare()
        }
        tail = tailPlayer
        this.main = main
        // The overlap is song time: at 2x it passes in half the real time.
        val realFadeMs = (fadeMs / speed.coerceAtLeast(0.1f)).toLong().coerceAtLeast(1L)

        job = scope.launch {
            while (main.currentPosition < handoffMs - HANDOFF_SLACK_MS) {
                if (!main.isPlaying || main.currentMediaItem?.mediaId != outgoingId) {
                    finish()
                    return@launch
                }
                delay(POLL_MS)
            }
            if (tailPlayer.playbackState != Player.STATE_READY) {
                finish()
                return@launch
            }

            tailPlayer.play()
            main.volume = 0f
            val handoffAt = SystemClock.elapsedRealtime()
            advance()

            if (beatMatch != null &&
                mixOnTheBeat(main, tailPlayer, beatMatch, realFadeMs, handoffMs + fadeMs, speed, outgoingGain, style)
            ) {
                finish()
                return@launch
            }

            // Both decks follow the wall clock from the hand-off. The next song may still be resolving:
            // it joins the mix wherever it is, and if it starts after the mix it simply plays in full.
            Log.i(LOG_TAG, "Crossfade %s over %d ms".format(style, realFadeMs))
            runMix(main, tailPlayer, style, outgoingGain) {
                (SystemClock.elapsedRealtime() - handoffAt).toFloat() / realFadeMs
            }
            finish()
        }
    }

    /** Stops any crossfade at once and gives the main player its full volume back. */
    fun cancel() {
        job?.cancel()
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
     * bar, held there by small tempo corrections while the volumes cross, then eased back to its own
     * tempo. False when the incoming song did not start in time: the caller does a plain crossfade.
     */
    private suspend fun mixOnTheBeat(
        main: ExoPlayer,
        tail: ExoPlayer,
        match: BeatMatch,
        realMixMs: Long,
        mixEndMs: Long,
        speed: Float,
        outgoingGain: Float,
        style: TransitionStyle
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

        // Coarse alignment by seeking while the incoming song is still silent.
        for (attempt in 1..ALIGN_ATTEMPTS) {
            delay(SETTLE_MS)
            val error = phaseError(main, tail, match)
            if (abs(error) <= ALIGN_TOLERANCE_BEATS) break
            val shiftMs = (error * match.incoming.beatSeconds * 1_000).toLong()
            main.seekTo((main.currentPosition - shiftMs).coerceAtLeast(0L))
            withTimeoutOrNull(2_000L) {
                delay(POLL_MS * 2)
                while (!main.isPlaying) delay(POLL_MS)
            }
        }
        Log.i(LOG_TAG, "Automix alignment done, last reading %.3f beats".format(phaseError(main, tail, match)))

        // During the overlap, a rare tempo nudge cancels drift left by the tempo estimates.
        val corrector = launch {
            var applied = base
            while (isActive) {
                delay(CORRECTION_INTERVAL_MS)
                val error = phaseError(main, tail, match)
                val target = if (abs(error) > CORRECTION_THRESHOLD_BEATS) {
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
                    "Automix check: error %.3f beats, rate %.4f (player %.4f), out %d ms, in %d ms".format(
                        error, target, main.playbackParameters.speed, tail.currentPosition, main.currentPosition
                    )
                )
            }
        }
        // Alignment used part of the overlap: the fade still has to end with the outgoing music.
        // Stay silent until the corrector has pulled the bars together, so a flam is never heard.
        withTimeoutOrNull(LOCK_WAIT_MS) {
            delay(SETTLE_MS)
            while (abs(phaseError(main, tail, match)) > LOCK_TOLERANCE_BEATS) delay(POLL_MS * 5)
        }
        Log.i(LOG_TAG, "Automix %s starts: phase error %.3f beats".format(style, phaseError(main, tail, match)))
        mixOnSongClock(main, tail, match, realMixMs, mixEndMs, speed, outgoingGain, style)
        corrector.cancel()
        Log.i(LOG_TAG, "Automix %s mixed: phase error %.3f beats at the end".format(style, phaseError(main, tail, match)))
        releaseTail()
        main.volume = 1f
        DjAudio.main?.setNeutral()

        // Back to the song's own tempo in one step, now that nothing plays against it.
        delay(CORRECTION_INTERVAL_MS)
        main.setPlaybackParameters(PlaybackParameters(speed))
        restoreSpeed = null
        true
    }

    /**
     * Runs the mixer on the outgoing song's clock, so the style's key moment (the bass swap, the cut)
     * lands on its downbeat nearest the middle of the mix, over one beat.
     */
    private suspend fun mixOnSongClock(
        main: ExoPlayer,
        tail: ExoPlayer,
        match: BeatMatch,
        realMixMs: Long,
        mixEndMs: Long,
        speed: Float,
        outgoingGain: Float,
        style: TransitionStyle
    ) {
        val startMs = tail.currentPosition
        val spanMs = (mixEndMs - startMs).coerceAtLeast(MIN_MIX_SPAN_MS).toDouble()
        val beatMs = match.outgoing.beatSeconds * 1_000
        val swapMs = swapPointMs(match, startMs, spanMs, beatMs)
        val swapAt = ((swapMs - startMs) / spanMs).toFloat()
        val swapWidth = (beatMs / spanMs).toFloat()
        val deadline = SystemClock.elapsedRealtime() + (spanMs / speed).toLong().coerceAtMost(realMixMs) + MIX_OVERRUN_MS
        Log.i(LOG_TAG, "Automix %s: swap at %d ms (%.2f of %.0f ms)".format(style, swapMs.toLong(), swapAt, spanMs))
        runMix(main, tail, style, outgoingGain, swapAt, swapWidth) {
            val over = tail.playbackState == Player.STATE_ENDED || SystemClock.elapsedRealtime() >= deadline
            if (over) 1f else ((tail.currentPosition - startMs) / spanMs).toFloat()
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
        style: TransitionStyle,
        outgoingGain: Float,
        swapAt: Float = TransitionCurves.SWAP_AT,
        swapWidth: Float = TransitionCurves.SWAP_WIDTH,
        progress: () -> Float
    ) {
        var joined = false
        while (true) {
            val p = progress().coerceIn(0f, 1f)
            val frame = TransitionCurves.frame(style, p, outgoingGain, swapAt, swapWidth)
            joined = joined || main.isPlaying
            main.volume = if (joined) frame.incoming.volume else 0f
            DjAudio.main?.set(frame.incoming.filter)
            tail.volume = frame.outgoing.volume
            tailDeck?.set(frame.outgoing.filter)
            if (p >= 1f) return
            delay(RAMP_STEP_MS)
        }
    }

    /** The outgoing song's bar line nearest the middle of the mix, kept clear of both ends. */
    private fun swapPointMs(match: BeatMatch, startMs: Long, spanMs: Double, beatMs: Double): Double {
        val middleMs = startMs + spanMs / 2
        val period = match.outgoing.beatsPerBar.toDouble()
        val phase = match.outgoing.barPhase(middleMs / 1_000.0)
        val toBar = if (phase < period / 2) -phase else period - phase
        val earliest = startMs + beatMs
        val latest = startMs + spanMs - 2 * beatMs
        if (latest < earliest) return middleMs
        return (middleMs + toBar * beatMs).coerceIn(earliest, latest)
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
