package org.sonora.android.wear.speaker

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.sonora.android.wear.Volume
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.sonora.android.wear.WearProtocol as P

/**
 * The watch as the phone's speaker. The phone keeps the queue and sends one track at a time
 * ([P.PATH_SPEAKER] messages); this plays it in [WatchPlaybackService] and tells the phone how it
 * goes ([P.PATH_SPEAKER_STATUS]): "ended" is how the phone knows to send the next track.
 *
 * The service is reached through a [MediaController]: binding is allowed from the background, and
 * Media3 promotes the service to the foreground itself once playback starts.
 */
object Speaker {
    private const val TAG = "LFlyWear"
    private const val TICK_MS = 3_000L

    private val _active = MutableStateFlow(false)

    /** True while this watch is playing (or holding) the phone's music. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _volume = MutableStateFlow<Volume?>(null)

    /** The watch's own music volume (STREAM_MUSIC), level and max. */
    val volume: StateFlow<Volume?> = _volume.asStateFlow()

    private val main = Handler(Looper.getMainLooper())

    /** One thread, so the phone gets the statuses in the order they happened. */
    private val outbox = Executors.newSingleThreadExecutor()
    private lateinit var app: Context

    // Main thread only.
    private var player: Player? = null
    private var stopService: (() -> Unit)? = null
    private var binding: ListenableFuture<MediaController>? = null
    private val pending = mutableListOf<(Player) -> Unit>()
    private var lastLoad: JSONObject? = null
    private var seq = 0L
    private var trackId = ""
    private var reported: String? = null

    // Outbox thread only.
    private var phone: String? = null
    private var phoneLost = false

    /** A [P.PATH_SPEAKER] message from the phone; any thread. */
    fun onMessage(context: Context, path: String, data: ByteArray?) {
        app = context.applicationContext
        val json = runCatching { JSONObject(String(data ?: ByteArray(0))) }.getOrElse { JSONObject() }
        main.post {
            when (path.substringAfterLast('/')) {
                P.SPEAKER_LOAD -> load(json)
                P.SPEAKER_PLAY -> withPlayer { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() }
                P.SPEAKER_PAUSE -> withPlayer { it.pause() }
                P.SPEAKER_SEEK -> withPlayer { it.seekTo(json.optLong(P.J_POSITION)) }
                P.SPEAKER_STOP -> stop()
            }
        }
    }

    /** Crown: turns the watch's own music volume, not the phone's. */
    fun nudgeVolume(context: Context, steps: Int) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val level = (audio.getStreamVolume(AudioManager.STREAM_MUSIC) + steps).coerceIn(0, max)
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0) }
        readVolume(context)
    }

    /** The service's player is up ([WatchPlaybackService.onCreate]). */
    internal fun attach(context: Context, player: Player, stop: () -> Unit) {
        app = context.applicationContext
        this.player = player
        stopService = stop
        player.addListener(listener)
        pending.forEach { it(player) }
        pending.clear()
        report()
    }

    internal fun detach() {
        player?.removeListener(listener)
        player = null
        stopService = null
        main.removeCallbacks(tick)
        binding?.let(MediaController::releaseFuture)
        binding = null
        pending.clear()
        // A load that landed on a service already on its way out (STOP, then LOAD at once): play it on a new one.
        if (_active.value) lastLoad?.let(::load)
    }

    /** A transport button on the watch itself: the phone owns the queue, so it decides. */
    internal fun forward(command: String, offline: (() -> Unit)? = null) {
        outbox.execute { if (!sendToPhone("${P.PATH_COMMAND}/$command", null) && offline != null) main.post(offline) }
    }

    private fun load(json: JSONObject) {
        lastLoad = json
        seq = json.optLong(P.J_SEQ)
        trackId = json.optString(P.J_ID)
        reported = null
        _active.value = true
        readVolume(app)
        val metadata = MediaMetadata.Builder()
            .setTitle(json.optString(P.J_TITLE))
            .setArtist(json.optString(P.J_ARTIST))
            .apply { json.optLong(P.J_DURATION).takeIf { it > 0 }?.let { setDurationMs(it) } }
            .build()
        val item = MediaItem.Builder().setMediaId(trackId).setUri(PhoneStreamDataSource.uri(trackId)).setMediaMetadata(metadata).build()
        val position = json.optLong(P.J_POSITION)
        val play = json.optBoolean(P.J_PLAY, true)
        pending.clear()
        // Also after a STOP whose service has not gone yet: a new binding keeps it.
        bind()
        withPlayer(queue = true) {
            it.setMediaItem(item, position)
            it.prepare()
            it.playWhenReady = play
        }
        report()
    }

    /** Runs [block] on the player, or after it comes up when a load is waiting for it. */
    private fun withPlayer(queue: Boolean = false, block: (Player) -> Unit) {
        val p = player
        if (p != null) block(p) else if (queue || pending.isNotEmpty()) pending += block
    }

    private fun bind() {
        if (binding != null) return
        val token = SessionToken(app, ComponentName(app, WatchPlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        binding = future
        future.addListener({
            if (binding === future && runCatching { future.get() }.isFailure) {
                Log.w(TAG, "Could not start the watch player")
                binding = null
                pending.clear()
                send(P.SPEAKER_STATE_ERROR, 0)
            }
        }, ContextCompat.getMainExecutor(app))
    }

    private fun stop() {
        lastLoad = null
        pending.clear()
        _active.value = false
        main.removeCallbacks(tick)
        // The inner player: the session's would hand the pause to the phone.
        player?.run { pause(); stop(); clearMediaItems() }
        stopService?.invoke()
        binding?.let(MediaController::releaseFuture)
        binding = null
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                main.removeCallbacks(tick)
                if (player.isPlaying) main.postDelayed(tick, TICK_MS)
            }
            report(force = events.contains(Player.EVENT_POSITION_DISCONTINUITY))
        }

        override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) = readVolume(app)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (player?.isPlaying != true) return
            report(force = true)
            main.postDelayed(this, TICK_MS)
        }
    }

    /** Tells the phone the player's state on a change; with [force], also when only the position moved. */
    private fun report(force: Boolean = false) {
        val p = player ?: return
        if (!_active.value) return
        val state = when {
            p.playerError != null -> P.SPEAKER_STATE_ERROR
            p.playbackState == Player.STATE_ENDED -> P.SPEAKER_STATE_ENDED
            p.isPlaying -> P.SPEAKER_STATE_PLAYING
            p.playWhenReady && p.playbackState == Player.STATE_BUFFERING -> P.SPEAKER_STATE_BUFFERING
            else -> P.SPEAKER_STATE_PAUSED
        }
        // "ended" and "error" go once: the phone acts on them.
        if (state == reported && (!force || state == P.SPEAKER_STATE_ENDED || state == P.SPEAKER_STATE_ERROR)) return
        reported = state
        send(state, p.currentPosition)
    }

    private fun send(state: String, position: Long) {
        val body = JSONObject()
            .put(P.J_SEQ, seq)
            .put(P.J_ID, trackId)
            .put(P.J_STATE, state)
            .put(P.J_POSITION, position)
            .put(P.J_AT, System.currentTimeMillis())
            .toString()
            .toByteArray()
        outbox.execute { sendToPhone(P.PATH_SPEAKER_STATUS, body) }
    }

    /** Outbox thread. With the phone gone the watch plays on from its buffer: say so once, not every 3 s. */
    private fun sendToPhone(path: String, body: ByteArray?): Boolean {
        val node = phone ?: PhoneStreamDataSource.findPhone(app)
        val sent = node != null && runCatching {
            Tasks.await(Wearable.getMessageClient(app).sendMessage(node, path, body), 10, TimeUnit.SECONDS)
        }.isSuccess
        phone = node.takeIf { sent }
        if (sent) {
            phoneLost = false
        } else if (!phoneLost) {
            phoneLost = true
            Log.w(TAG, "Phone not reachable; the watch plays on from its buffer")
        }
        return sent
    }

    private fun readVolume(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        _volume.value = Volume(audio.getStreamVolume(AudioManager.STREAM_MUSIC), audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
    }
}
