package org.sonora.android.playback

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.sonora.android.playback.automix.BeatGrid
import org.sonora.android.playback.automix.DjAudio
import org.sonora.android.playback.automix.DjFilterProcessor
import org.sonora.android.playback.automix.Loudness
import org.sonora.android.playback.automix.MixRecipe
import org.sonora.android.playback.automix.TransitionCurves
import kotlin.math.abs

/** Where a preview is: its position across the previewed stretch, 0..1, and whether it plays. */
data class PreviewState(val progress: Float, val playing: Boolean)

/**
 * Plays one transition of a mixed playlist on its own, for the editor: a few bars of the outgoing
 * song, the mix, and a few bars of the incoming one. Two private players, each with its own DJ deck,
 * follow the same curves as the real transition; the incoming one is brought onto the outgoing
 * song's bar the same way, while it is still silent.
 */
@OptIn(UnstableApi::class)
internal class TransitionPreview(private val context: Context, private val scope: CoroutineScope) {

    private companion object {
        const val TAG = "TransitionPreview"
        const val LEAD_BARS = 2
        const val AFTER_BARS = 2
        const val POLL_MS = 20L
        const val STEP_MS = 40L
        const val SETTLE_MS = 500L
        const val ALIGN_TOLERANCE_BEATS = 0.04
        const val READY_TIMEOUT_MS = 15_000L
    }

    private val _state = MutableStateFlow<PreviewState?>(null)
    val state: StateFlow<PreviewState?> = _state.asStateFlow()

    private var job: Job? = null
    private var outgoing: ExoPlayer? = null
    private var incoming: ExoPlayer? = null

    /** What to preview: both songs' sources and grids, and the transition as it will play. */
    class Request(
        val outgoingSource: String,
        val incomingSource: String,
        val outgoingGrid: BeatGrid?,
        val incomingGrid: BeatGrid?,
        val exitMs: Long,
        val entryMs: Long,
        val mixMs: Long,
        /** The incoming song's tempo during the mix, as a factor of its own; 1 when the beats are not matched. */
        val incomingRate: Float,
        val recipe: MixRecipe,
        val outgoingGainDb: Double,
        /** False when the beats are not lined up: the incoming song then just starts on the exit. */
        val beatMatched: Boolean
    )

    fun play(request: Request) {
        stop()
        val outDeck = DjFilterProcessor()
        val inDeck = DjFilterProcessor()
        val a = buildPlayer(outDeck)
        val b = buildPlayer(inDeck)
        outgoing = a
        incoming = b
        val beatMs = request.outgoingGrid?.beatSeconds?.times(1_000) ?: 500.0
        val leadMs = (LEAD_BARS * 4 * beatMs).toLong()
        val afterMs = (AFTER_BARS * 4 * beatMs).toLong()
        val startMs = (request.exitMs - leadMs).coerceAtLeast(0L)
        val endMs = request.exitMs + request.mixMs + afterMs
        val span = (endMs - startMs).toFloat()

        a.setMediaItem(MediaItem.fromUri(request.outgoingSource), startMs)
        a.prepare()
        b.setMediaItem(MediaItem.fromUri(request.incomingSource), request.entryMs)
        b.setPlaybackParameters(PlaybackParameters(request.incomingRate))
        b.volume = 0f
        b.prepare()
        inDeck.set(TransitionCurves.frame(request.recipe, 0f).incoming.filter)
        val gain = Loudness.gain(request.outgoingGainDb)
        _state.value = PreviewState(0f, playing = true)

        job = scope.launch {
            val ready = withTimeoutOrNull(READY_TIMEOUT_MS) {
                while (a.playbackState != Player.STATE_READY || b.playbackState != Player.STATE_READY) delay(POLL_MS)
                true
            }
            if (ready != true) {
                Log.w(TAG, "Preview sources did not load in time")
                stop()
                return@launch
            }
            a.play()
            // The incoming song starts on the outgoing song's exit downbeat.
            while (a.currentPosition < request.exitMs) {
                publish(a.currentPosition, startMs, span)
                delay(POLL_MS)
            }
            b.play()
            alignIncoming(a, b, request)
            var joinedAt = 0L
            while (true) {
                val position = a.currentPosition
                val p = ((position - request.exitMs).toFloat() / request.mixMs).coerceIn(0f, 1f)
                val frame = TransitionCurves.frame(
                    request.recipe, p, gain,
                    swapWidth = (beatMs / request.mixMs).toFloat()
                )
                if (joinedAt == 0L && b.isPlaying) joinedAt = SystemClock.elapsedRealtime()
                val join = if (joinedAt == 0L) 0f else ((SystemClock.elapsedRealtime() - joinedAt) / 400f).coerceIn(0f, 1f)
                a.volume = frame.outgoing.volume
                outDeck.set(frame.outgoing.filter)
                b.volume = frame.incoming.volume * if (p >= 1f) 1f else join
                inDeck.set(frame.incoming.filter)
                publish(position, startMs, span)
                if (p >= 1f) break
                delay(STEP_MS)
            }
            a.pause()
            // The incoming song goes on alone for a couple of bars at its own tempo.
            b.setPlaybackParameters(PlaybackParameters(1f))
            val tailStart = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - tailStart < afterMs) {
                publish(request.exitMs + request.mixMs + (SystemClock.elapsedRealtime() - tailStart), startMs, span)
                delay(STEP_MS)
            }
            stop()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        outgoing?.release()
        incoming?.release()
        outgoing = null
        incoming = null
        _state.value = null
    }

    private fun publish(position: Long, startMs: Long, span: Float) {
        _state.value = PreviewState(((position - startMs) / span).coerceIn(0f, 1f), playing = true)
    }

    /** Seeks the still silent incoming song onto the outgoing song's bar, like the real transition does. */
    private suspend fun alignIncoming(a: ExoPlayer, b: ExoPlayer, request: Request) {
        val outGrid = request.outgoingGrid ?: return
        val inGrid = request.incomingGrid ?: return
        if (!request.beatMatched) return
        val period = if (outGrid.beatsPerBar > 1 && inGrid.beatsPerBar > 1) outGrid.beatsPerBar.toDouble() else 1.0
        repeat(2) {
            delay(SETTLE_MS)
            val outPhase = outGrid.barPhase(a.currentPosition / 1_000.0)
            val inPhase = inGrid.barPhase(b.currentPosition / 1_000.0)
            val error = BeatGrid.wrapCentered(inPhase - outPhase, period)
            if (abs(error) <= ALIGN_TOLERANCE_BEATS) return
            b.seekTo((b.currentPosition - (error * inGrid.beatSeconds * 1_000).toLong()).coerceAtLeast(0L))
        }
    }

    private fun buildPlayer(deck: DjFilterProcessor): ExoPlayer {
        val attributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
        val sourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(AudioCacheManager.getCacheDataSourceFactory(context))
        return ExoPlayer.Builder(context)
            .setRenderersFactory(DjAudio.renderersFactory(context, deck))
            .setAudioAttributes(attributes, /* handleAudioFocus = */ false)
            .setMediaSourceFactory(sourceFactory)
            .build()
    }
}
