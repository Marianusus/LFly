package org.sonora.android.wear

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.sonora.android.wear.WearProtocol as P

enum class Link { CONNECTING, CONNECTED, NO_PHONE }

data class NowPlaying(
    val trackId: String,
    val title: String,
    val artist: String,
    val playing: Boolean,
    val positionMs: Long,
    val positionAt: Long,
    val durationMs: Long,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
    val liked: Boolean
) {
    /** Where the playhead is at [now], extrapolated while playing. */
    fun positionAt(now: Long): Long =
        (positionMs + if (playing) (now - positionAt).coerceAtLeast(0) else 0).coerceIn(0, durationMs.coerceAtLeast(0))
}

data class Volume(val level: Int, val max: Int)

data class QueueRow(val index: Int, val title: String, val artist: String)

data class Queue(val rows: List<QueueRow>, val currentIndex: Int)

/**
 * The watch's whole view of the phone. Listens only while the app is on
 * screen ([start]/[stop] follow the activity). Taps are answered on the watch at once; the phone's
 * word is taken when it arrives, except a value just tapped is held for a moment so an older state
 * cannot flick it back.
 */
class PhoneLink(context: Context, private val scope: CoroutineScope) :
    DataClient.OnDataChangedListener,
    CapabilityClient.OnCapabilityChangedListener {

    private val dataClient = Wearable.getDataClient(context)
    private val messageClient = Wearable.getMessageClient(context)
    private val capabilityClient = Wearable.getCapabilityClient(context)

    private val _link = MutableStateFlow(Link.CONNECTING)
    val link: StateFlow<Link> = _link.asStateFlow()

    /** Null until the phone has said anything; [heard] tells "nothing playing" from "not heard". */
    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private val _heard = MutableStateFlow(false)
    val heard: StateFlow<Boolean> = _heard.asStateFlow()

    private val _artwork = MutableStateFlow<ImageBitmap?>(null)
    val artwork: StateFlow<ImageBitmap?> = _artwork.asStateFlow()

    private val _volume = MutableStateFlow<Volume?>(null)
    val volume: StateFlow<Volume?> = _volume.asStateFlow()

    private val _queue = MutableStateFlow<Queue?>(null)
    val queue: StateFlow<Queue?> = _queue.asStateFlow()

    @Volatile private var phoneNode: String? = null
    private var artTrack: String? = null
    private var started = false
    private var holdPlaying: Pair<Boolean, Long>? = null
    private var holdLiked: Pair<Boolean, Long>? = null
    private var holdVolumeUntil = 0L
    private var pendingSteps = 0
    private var volumeJob: Job? = null

    fun start() {
        if (started) return
        started = true
        dataClient.addListener(this)
        capabilityClient.addListener(this, P.CAPABILITY_PHONE)
        scope.launch {
            readCached()
            connect()
        }
    }

    fun stop() {
        if (!started) return
        started = false
        dataClient.removeListener(this)
        capabilityClient.removeListener(this, P.CAPABILITY_PHONE)
    }

    fun retry() {
        _link.value = Link.CONNECTING
        scope.launch { connect() }
    }

    // ---- Commands, each answered on the watch first ----

    fun togglePlay() {
        val now = System.currentTimeMillis()
        _nowPlaying.update { np ->
            np ?: return@update null
            val playing = !np.playing
            holdPlaying = playing to now + HOLD_MS
            if (playing) np.copy(playing = true, positionAt = now)
            else np.copy(playing = false, positionMs = np.positionAt(now), positionAt = now)
        }
        send(P.CMD_TOGGLE)
    }

    fun next() = send(P.CMD_NEXT)

    fun previous() = send(P.CMD_PREVIOUS)

    fun toggleLike() {
        _nowPlaying.update { np ->
            np ?: return@update null
            holdLiked = !np.liked to System.currentTimeMillis() + HOLD_MS
            np.copy(liked = !np.liked)
        }
        send(P.CMD_LIKE)
    }

    /** The crown: steps are gathered for a moment and sent as one. */
    fun nudgeVolume(steps: Int) {
        _volume.update { v -> v?.copy(level = (v.level + steps).coerceIn(0, v.max)) }
        holdVolumeUntil = System.currentTimeMillis() + HOLD_MS
        pendingSteps += steps
        if (volumeJob?.isActive == true) return
        volumeJob = scope.launch {
            delay(80)
            val n = pendingSteps
            pendingSteps = 0
            if (n != 0) send(P.CMD_VOLUME, n.toString())
        }
    }

    fun requestQueue() = send(P.CMD_QUEUE)

    fun skipTo(index: Int) = send(P.CMD_SKIP_TO, index.toString())

    // ---- The phone's state ----

    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED) read(event.dataItem)
        }
    }

    override fun onCapabilityChanged(info: CapabilityInfo) {
        val node = info.nodes.firstOrNull { it.isNearby } ?: info.nodes.firstOrNull()
        phoneNode = node?.id
        if (node == null) _link.value = Link.NO_PHONE
        else if (_link.value != Link.CONNECTED) scope.launch { connect() }
    }

    /** The last state the Data Layer kept, so the screen is never empty while the phone answers. */
    private suspend fun readCached() {
        runCatching {
            val items = dataClient.dataItems.await()
            try {
                items.forEach { read(it) }
            } finally {
                items.release()
            }
        }
    }

    private suspend fun connect() {
        val node = runCatching {
            val info = capabilityClient.getCapability(P.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE).await()
            info.nodes.firstOrNull { it.isNearby } ?: info.nodes.firstOrNull()
        }.getOrNull()
        phoneNode = node?.id
        if (node == null) {
            _link.value = Link.NO_PHONE
            return
        }
        _link.value = Link.CONNECTED
        send(P.CMD_SYNC)
    }

    private fun read(item: DataItem) {
        val path = item.uri.path ?: return
        val map = DataMapItem.fromDataItem(item).dataMap
        val now = System.currentTimeMillis()
        when (path) {
            P.PATH_NOW_PLAYING -> {
                _heard.value = true
                if (now > holdVolumeUntil) {
                    _volume.value = Volume(map.getInt(P.KEY_VOLUME), map.getInt(P.KEY_VOLUME_MAX))
                }
                if (!map.getBoolean(P.KEY_HAS_TRACK)) {
                    _nowPlaying.value = null
                    _artwork.value = null
                    artTrack = null
                    return
                }
                val trackId = map.getString(P.KEY_TRACK_ID).orEmpty()
                var playing = map.getBoolean(P.KEY_PLAYING)
                holdPlaying?.let { (value, until) -> if (now < until) playing = value else holdPlaying = null }
                var liked = map.getBoolean(P.KEY_LIKED)
                holdLiked?.let { (value, until) -> if (now < until) liked = value else holdLiked = null }
                _nowPlaying.value = NowPlaying(
                    trackId = trackId,
                    title = map.getString(P.KEY_TITLE).orEmpty(),
                    artist = map.getString(P.KEY_ARTIST).orEmpty(),
                    playing = playing,
                    positionMs = map.getLong(P.KEY_POSITION_MS),
                    positionAt = map.getLong(P.KEY_POSITION_AT),
                    durationMs = map.getLong(P.KEY_DURATION_MS),
                    hasPrevious = map.getBoolean(P.KEY_HAS_PREVIOUS),
                    hasNext = map.getBoolean(P.KEY_HAS_NEXT),
                    liked = liked
                )
                val asset = map.getAsset(P.KEY_ART)
                if (asset == null) {
                    _artwork.value = null
                    artTrack = null
                } else if (artTrack != trackId) {
                    artTrack = trackId
                    scope.launch {
                        val bitmap = withContext(Dispatchers.IO) {
                            runCatching {
                                dataClient.getFdForAsset(asset).await().inputStream.use { BitmapFactory.decodeStream(it) }
                            }.getOrNull()
                        }
                        if (artTrack == trackId) _artwork.value = bitmap?.asImageBitmap()
                    }
                }
            }
            P.PATH_QUEUE -> {
                val rows = map.getDataMapArrayList(P.KEY_ITEMS).orEmpty().map {
                    QueueRow(it.getInt(P.KEY_INDEX), it.getString(P.KEY_TITLE).orEmpty(), it.getString(P.KEY_ARTIST).orEmpty())
                }
                _queue.value = Queue(rows, map.getInt(P.KEY_CURRENT_INDEX))
            }
        }
    }

    private fun send(command: String, argument: String? = null) {
        scope.launch {
            val node = phoneNode ?: run { connect(); phoneNode } ?: return@launch
            runCatching {
                messageClient.sendMessage(node, "${P.PATH_COMMAND}/$command", argument?.toByteArray()).await()
            }.onFailure {
                Log.w(TAG, "Phone did not take '$command': ${it.message}")
                _link.value = Link.NO_PHONE
            }
        }
    }

    private companion object {
        const val TAG = "LFlyWear"

        /** How long a tapped value wins over the phone's older word. */
        const val HOLD_MS = 1_500L
    }
}
