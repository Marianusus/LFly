package org.sonora.android.wear

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.sonora.android.R
import org.sonora.android.cast.CastDiscovery
import org.sonora.android.connect.ConnectManager
import org.sonora.android.playback.AudioRouting
import org.sonora.android.playback.PlaybackManager
import org.sonora.android.playback.PlaybackState
import org.sonora.android.playback.RepeatMode
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import org.sonora.android.wear.WearProtocol as P

/** A watch with the LFly app: the device sheet and the watch's own picker offer it as a speaker. */
data class WatchNode(val id: String, val name: String)

/**
 * Tells the LFly watch app what is playing.
 *
 * Costs nothing without a watch: the player's state is followed always, but it is only encoded,
 * given artwork and handed to Play services once a watch with the LFly app is reachable. A phone
 * without Wear OS at all answers that with an error once and is left alone.
 */
object WearBridge {
    private const val TAG = "LFlyWear"

    /** Coalesces the bursts the player reports in: play is two or three changes in a row. */
    private const val COALESCE_MS = 150L

    /** Rows of Up Next sent to the watch. */
    private const val QUEUE_AHEAD = 40

    /** What the watch was last told, to send only real changes (not every position tick). */
    private data class Sent(
        val trackId: String?,
        val playing: Boolean,
        val durationMs: Long,
        val liked: Boolean,
        val hasPrevious: Boolean,
        val hasNext: Boolean,
        val volume: Int,
        val album: String?,
        val shuffle: Boolean,
        val repeat: RepeatMode,
        val output: WatchOutputs.Output?,
        val watchSpeaker: Boolean,
        val positionMs: Long,
        val at: Long
    )

    private class Art(val url: String, val asset: Asset)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var app: Context
    @Volatile private var started = false
    @Volatile private var watchReachable = false
    @Volatile private var sent: Sent? = null
    @Volatile private var art: Art? = null
    private var flushJob: Job? = null

    /** Where the music comes out, refreshed when that can change (not on every position tick). */
    @Volatile private var output: WatchOutputs.Output? = null

    private val _watch = MutableStateFlow<WatchNode?>(null)

    /** The reachable watch with LFly (the nearest one), or null. */
    val watch: StateFlow<WatchNode?> = _watch.asStateFlow()

    /** Called once from Application.onCreate. */
    fun start(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        scope.launch {
            val capability = Wearable.getCapabilityClient(app)
            runCatching {
                val info = Tasks.await(capability.getCapability(P.CAPABILITY_WATCH, CapabilityClient.FILTER_REACHABLE))
                watchReachable = info.nodes.isNotEmpty()
                _watch.value = nearest(info.nodes)
                capability.addListener({ changed ->
                    val was = watchReachable
                    watchReachable = changed.nodes.isNotEmpty()
                    _watch.value = nearest(changed.nodes)
                    if (watchReachable && !was) { sent = null; schedule() }
                }, P.CAPABILITY_WATCH)
            }.onFailure {
                // No Wear OS app on this phone: nothing to talk to.
                Log.i(TAG, "No Wear OS: ${it.message}")
                return@launch
            }
            Log.i(TAG, "Watch reachable: $watchReachable")
            followOutput()
            PlaybackManager.getInstance(app).playbackState.collect { state ->
                if (watchReachable && changed(state)) schedule()
            }
        }
    }

    /** A message from a watch is proof one is reachable. */
    internal fun onWatchSeen() {
        watchReachable = true
    }

    private fun nearest(nodes: Set<Node>): WatchNode? =
        (nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull())?.let { WatchNode(it.id, it.displayName) }

    /** [nodeId] with the name the phone knows it by. Blocking: off the main thread. */
    internal fun nodeFor(nodeId: String): WatchNode {
        _watch.value?.takeIf { it.id == nodeId }?.let { return it }
        val name = runCatching {
            Tasks.await(Wearable.getNodeClient(app).connectedNodes).firstOrNull { it.id == nodeId }?.displayName
        }.getOrNull()
        return WatchNode(nodeId, name?.takeIf { it.isNotBlank() } ?: app.getString(R.string.settings_page_watch))
    }

    /**
     * The watch shows where the music comes out: follows what can move it (an output picked, a
     * device plugged in or out, LFly Connect, Cast, the watch as the speaker).
     */
    private fun followOutput() {
        refreshOutput()
        scope.launch {
            combine(AudioRouting.selectedId, ConnectManager.following, CastDiscovery.activeName, WatchSpeaker.watch) { _, _, _, _ -> }
                .collect { outputChanged() }
        }
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = outputChanged()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = outputChanged()
        }
        runCatching {
            app.getSystemService(AudioManager::class.java)?.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        }
    }

    private fun outputChanged() {
        scope.launch {
            refreshOutput()
            if (watchReachable && changed(PlaybackManager.getInstance(app).playbackState.value)) schedule()
        }
    }

    private fun refreshOutput() {
        output = runCatching { WatchOutputs.current(app) }.getOrNull()
    }

    /** Sends the state again, whatever was sent before: the watch app just opened. */
    internal fun sync() {
        sent = null
        schedule()
    }

    internal fun adjustVolume(steps: Int) {
        val audio = app.getSystemService(AudioManager::class.java) ?: return
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (audio.getStreamVolume(AudioManager.STREAM_MUSIC) + steps).coerceIn(0, max)
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
        schedule()
    }

    internal fun publishQueue() {
        scope.launch {
            val state = PlaybackManager.getInstance(app).playbackState.value
            val from = state.currentIndex.coerceAtLeast(0)
            val rows = ArrayList<DataMap>()
            state.queue.withIndex().drop(from).take(QUEUE_AHEAD + 1).forEach { (index, track) ->
                rows += DataMap().apply {
                    putInt(P.KEY_INDEX, index)
                    putString(P.KEY_TITLE, track.title)
                    putString(P.KEY_ARTIST, track.artist)
                }
            }
            val request = PutDataMapRequest.create(P.PATH_QUEUE)
            val now = System.currentTimeMillis()
            RowArt.putAll(app, request.dataMap, state.queue.drop(from).take(QUEUE_AHEAD + 1).map { it.coverUrl })
            request.dataMap.apply {
                putDataMapArrayList(P.KEY_ITEMS, rows)
                putInt(P.KEY_CURRENT_INDEX, state.currentIndex)
                // Always a change, so asking twice for the same queue still lands.
                putLong(P.KEY_POSITION_AT, now)
                putLong(P.KEY_STAMP, now)
            }
            put(request)
        }
    }

    private fun volume(): Pair<Int, Int> {
        val audio = app.getSystemService(AudioManager::class.java) ?: return 0 to 0
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC) to audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    }

    private fun snapshot(state: PlaybackState): Sent {
        val track = state.currentTrack
        val manager = PlaybackManager.getInstance(app)
        return Sent(
            trackId = track?.id,
            playing = state.isPlaying,
            durationMs = state.durationMs,
            liked = track != null && manager.isFavorite(track.id),
            hasPrevious = state.hasPrevious,
            hasNext = state.hasNext,
            volume = volume().first,
            album = track?.album?.takeIf { it.isNotBlank() },
            shuffle = state.isShuffle,
            repeat = state.repeatMode,
            output = output,
            watchSpeaker = WatchSpeaker.active.value,
            positionMs = state.positionMs,
            at = System.currentTimeMillis()
        )
    }

    /** True when the watch would draw something different: not the playhead moving on by itself. */
    private fun changed(state: PlaybackState): Boolean {
        val last = sent ?: return true
        val now = snapshot(state)
        if (now.copy(positionMs = 0, at = 0) != last.copy(positionMs = 0, at = 0)) return true
        // A seek: the playhead is not where it would be by now.
        val expected = last.positionMs + if (last.playing) now.at - last.at else 0
        return abs(expected - now.positionMs) > 2_500
    }

    private fun schedule() {
        if (!started) return
        synchronized(this) {
            flushJob?.cancel()
            flushJob = scope.launch {
                delay(COALESCE_MS)
                flush()
            }
        }
    }

    private suspend fun flush() {
        refreshOutput()
        val state = PlaybackManager.getInstance(app).playbackState.value
        val snap = snapshot(state)
        val track = state.currentTrack
        val request = PutDataMapRequest.create(P.PATH_NOW_PLAYING)
        val (volume, volumeMax) = volume()
        request.dataMap.apply {
            putBoolean(P.KEY_HAS_TRACK, track != null)
            putInt(P.KEY_VOLUME, volume)
            putInt(P.KEY_VOLUME_MAX, volumeMax)
            putLong(P.KEY_POSITION_AT, snap.at)
            putLong(P.KEY_STAMP, snap.at)
            putBoolean(P.KEY_WATCH_SPEAKER, snap.watchSpeaker)
            snap.output?.let {
                putString(P.KEY_OUTPUT_NAME, it.name)
                putString(P.KEY_OUTPUT_KIND, it.kind)
            }
            if (track != null) {
                putString(P.KEY_TRACK_ID, track.id)
                putString(P.KEY_TITLE, track.title)
                putString(P.KEY_ARTIST, track.artist)
                putBoolean(P.KEY_PLAYING, state.isPlaying)
                putLong(P.KEY_POSITION_MS, state.positionMs)
                putLong(P.KEY_DURATION_MS, state.durationMs)
                putBoolean(P.KEY_HAS_PREVIOUS, state.hasPrevious)
                putBoolean(P.KEY_HAS_NEXT, state.hasNext)
                putBoolean(P.KEY_LIKED, snap.liked)
                snap.album?.let { putString(P.KEY_ALBUM, it) }
                putBoolean(P.KEY_SHUFFLE, snap.shuffle)
                putInt(
                    P.KEY_REPEAT,
                    when (snap.repeat) {
                        RepeatMode.OFF -> P.REPEAT_OFF
                        RepeatMode.ALL -> P.REPEAT_ALL
                        RepeatMode.ONE -> P.REPEAT_ONE
                    }
                )
                artFor(track.coverUrl)?.let { putAsset(P.KEY_ART, it) }
            }
        }
        if (put(request)) sent = snap
    }

    /** The cover as a small JPEG, made once per cover. */
    private suspend fun artFor(url: String?): Asset? {
        if (url.isNullOrBlank()) return null
        art?.takeIf { it.url == url }?.let { return it.asset }
        val result = runCatching {
            app.imageLoader.execute(
                ImageRequest.Builder(app).data(url).size(P.ART_PX).allowHardware(false).build()
            )
        }.getOrNull() as? SuccessResult ?: return null
        val bitmap = result.drawable.toBitmap(P.ART_PX, P.ART_PX, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray()
        return Asset.createFromBytes(bytes).also { art = Art(url, it) }
    }

    private fun put(request: PutDataMapRequest): Boolean = runCatching {
        Tasks.await(Wearable.getDataClient(app).putDataItem(request.asPutDataRequest().setUrgent()))
        true
    }.getOrElse {
        Log.w(TAG, "Could not reach the watch: ${it.message}")
        false
    }
}
