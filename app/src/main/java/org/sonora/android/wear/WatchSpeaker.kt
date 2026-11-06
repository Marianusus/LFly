package org.sonora.android.wear

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.sonora.android.api.Track
import org.sonora.android.cast.CastDiscovery
import org.sonora.android.connect.ConnectManager
import org.sonora.android.playback.PartyTransportAction
import org.sonora.android.playback.PlaybackManager
import kotlin.math.abs
import org.sonora.android.wear.WearProtocol as P

/**
 * The watch as the speaker, phone side.
 *
 * The phone stays the brain: queue, shuffle, repeat, autoplay and stream resolution keep running
 * here, and the watch only plays the song it is told to, pulling the bytes from this phone
 * ([WatchStream]). While it is on, [PlaybackManager] keeps its own player paused and hands every
 * song and every play, pause and seek to this object ([PlaybackManager.ExternalSpeaker]); the
 * player screen, the mini player and the watch show what the watch reports.
 *
 * The wire contract is "The watch as the speaker" in [WearProtocol]. Everything here runs on the
 * main thread, like the player.
 */
object WatchSpeaker : PlaybackManager.ExternalSpeaker {
    private const val TAG = "LFlyWatchSpeaker"

    /** How often the progress bar moves while the watch plays. */
    private const val TICK_MS = 500L

    /** A command the watch leaves unanswered this long was lost (its app was restarted): load again. */
    private const val ANSWER_MS = 5_000L

    /** Loads in a row without an answer before the music comes back to the phone. */
    private const val MAX_RELOADS = 2

    /** How often the link to the watch is checked. */
    private const val CHECK_EVERY_MS = 10_000L

    /** Checks in a row without the watch before the music comes back to the phone. */
    private const val MISSES_TO_GIVE_UP = 2

    /** A playing watch reports every few seconds: this much silence means its app went away. */
    private const val SILENCE_MS = 20_000L

    /** What the watch last said, or what it was last told (shown at once, as Spotify does). */
    private data class Status(val seq: Int, val id: String, val state: String, val positionMs: Long, val at: Long)

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: Context

    private val _watch = MutableStateFlow<WatchNode?>(null)

    /** The watch the music plays on, or null while it plays anywhere else. */
    val watch: StateFlow<WatchNode?> = _watch.asStateFlow()

    private val _active = MutableStateFlow(false)

    /** True while a watch is the speaker. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private var seq = 0

    /** The song last sent, with the stream the phone resolved for it; read by [WatchStream]. */
    @Volatile private var loaded: Track? = null
    private var status: Status? = null

    /** Whether the listener wants music: the watch's "buffering" alone does not say. */
    private var wantsPlay = false
    private var heardAt = 0L

    /** When a command started waiting for the watch's answer (elapsed realtime), 0 when none waits. */
    private var askedAt = 0L
    private var reloads = 0
    private var misses = 0
    private var skippedOnError = false
    private val jobs = ArrayList<Job>()

    private val manager: PlaybackManager
        get() = PlaybackManager.getInstance(app)

    // ==========================================
    // Starting and stopping
    // ==========================================

    /**
     * Moves the music to [node]: the song playing now goes on there from where it is. Music on an
     * LFly Connect device comes back first (its hand-over then lands on the watch), Cast ends.
     */
    fun start(context: Context, node: WatchNode) {
        app = context.applicationContext
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { start(context, node) }
            return
        }
        // A Party guest plays what its host plays, here.
        if (manager.partyGuestMode) return
        if (_active.value) {
            if (_watch.value?.id == node.id) return
            finish(resume = false)
        }
        val state = manager.playbackState.value
        val following = ConnectManager.following.value != null
        // A Connect device's playhead is only in the state; the local one is live.
        val position = if (following || !state.isPlaying) state.positionMs else manager.livePositionMs()
        if (CastDiscovery.activeName.value != null) CastDiscovery.disconnect()
        if (following) {
            ConnectManager.playHere()
            // Its hooks come off on the next turn of the main loop: take over after them.
            main.post { begin(node, position) }
        } else {
            begin(node, position)
        }
    }

    /** Brings the music back to this phone, from where the watch was, playing or paused as it was. */
    fun stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { stop() }
            return
        }
        finish(resume = true)
    }

    private fun begin(node: WatchNode, positionMs: Long) {
        if (_active.value) return
        Log.i(TAG, "The music plays on '${node.name}'")
        _watch.value = node
        _active.value = true
        status = null
        loaded = null
        askedAt = 0L
        reloads = 0
        misses = 0
        skippedOnError = false
        heardAt = SystemClock.elapsedRealtime()
        manager.attachExternalSpeaker(this)
        manager.playbackState.value.currentTrack?.let { load(it, positionMs, play = true) }
        jobs += scope.launch { tick() }
        jobs += scope.launch { watchLink() }
        // Picking an LFly Connect device moves the music there: the watch lets go, the phone stays quiet.
        jobs += scope.launch {
            ConnectManager.following.filterNotNull().first()
            finish(resume = false)
        }
    }

    /**
     * Ends the watch's turn. With [resume] the song goes on on the phone from the watch's playhead,
     * playing or paused as [play] says (by default as it was on the watch).
     */
    private fun finish(resume: Boolean, play: Boolean? = null) {
        if (!_active.value) return
        val node = _watch.value
        val position = positionMs()
        val playing = play ?: (wantsPlay && status?.state != P.SPEAKER_STATE_ENDED)
        Log.i(TAG, "The music leaves '${node?.name}' (resume here: $resume, playing: $playing)")
        node?.let { send(it.id, P.SPEAKER_STOP, null) }
        jobs.forEach { it.cancel() }
        jobs.clear()
        _active.value = false
        _watch.value = null
        status = null
        loaded = null
        askedAt = 0L
        WatchStream.closeAll(app)
        manager.detachExternalSpeaker(this, if (resume) position else null, playing)
    }

    // ==========================================
    // PlaybackManager.ExternalSpeaker
    // ==========================================

    override fun load(track: Track, positionMs: Long) = load(track, positionMs, play = true)

    override fun transport(action: PartyTransportAction, positionMs: Long): Boolean {
        if (!_active.value) return false
        when (action) {
            PartyTransportAction.PLAY_PAUSE -> if (wantsPlay) pause() else play()
            PartyTransportAction.PLAY -> play()
            PartyTransportAction.PAUSE -> pause()
            PartyTransportAction.SEEK -> seek(positionMs)
            // The queue lives here: the phone picks the song and loads it.
            PartyTransportAction.NEXT, PartyTransportAction.PREVIOUS -> return false
        }
        return true
    }

    /** The watch's playhead, unless the phone already moved on to a song the watch has not got yet. */
    override fun positionMs(): Long {
        val state = manager.playbackState.value
        return status?.takeIf { it.id == state.currentTrack?.id }?.let { positionNow(it) } ?: state.positionMs
    }

    private fun load(track: Track, positionMs: Long, play: Boolean) {
        val node = _watch.value ?: return
        val from = positionMs.coerceAtLeast(0L)
        seq += 1
        loaded = track
        wantsPlay = play
        status = Status(seq, track.id, if (play) P.SPEAKER_STATE_BUFFERING else P.SPEAKER_STATE_PAUSED, from, System.currentTimeMillis())
        send(
            node.id, P.SPEAKER_LOAD,
            JSONObject()
                .put(P.J_ID, track.id)
                .put(P.J_TITLE, track.title)
                .put(P.J_ARTIST, track.artist)
                .put(P.J_DURATION, track.durationMs)
                .put(P.J_POSITION, from)
                .put(P.J_PLAY, play)
                .put(P.J_SEQ, seq)
        )
        awaitAnswer()
        show()
    }

    private fun play() {
        val node = _watch.value ?: return
        val state = manager.playbackState.value
        val track = state.currentTrack ?: return
        val s = status
        if (s == null || s.id != track.id || s.state == P.SPEAKER_STATE_ENDED || s.state == P.SPEAKER_STATE_ERROR) {
            // The watch holds nothing of this song (or it is over): load it from where the phone shows it.
            val from = if (s?.id == track.id && s.state == P.SPEAKER_STATE_ENDED) 0L else state.positionMs
            load(loaded?.takeIf { it.id == track.id } ?: track, from, play = true)
            return
        }
        wantsPlay = true
        // The watch reports changes only: a watch already playing has nothing to answer.
        val changes = s.state == P.SPEAKER_STATE_PAUSED
        status = s.copy(
            positionMs = positionNow(s),
            at = System.currentTimeMillis(),
            state = if (changes) P.SPEAKER_STATE_PLAYING else s.state
        )
        send(node.id, P.SPEAKER_PLAY, null)
        if (changes) awaitAnswer()
        show()
    }

    private fun pause() {
        val node = _watch.value ?: return
        wantsPlay = false
        val s = status
        val moving = s != null && (s.state == P.SPEAKER_STATE_PLAYING || s.state == P.SPEAKER_STATE_BUFFERING)
        if (s != null && moving) {
            status = s.copy(positionMs = positionNow(s), at = System.currentTimeMillis(), state = P.SPEAKER_STATE_PAUSED)
        }
        send(node.id, P.SPEAKER_PAUSE, null)
        // The watch reports changes only: a watch already paused has nothing to answer.
        if (moving) awaitAnswer()
        show()
    }

    private fun seek(positionMs: Long) {
        val node = _watch.value ?: return
        val to = positionMs.coerceAtLeast(0L)
        val s = status
        val track = manager.playbackState.value.currentTrack
        if (s == null || track == null || s.id != track.id || s.state == P.SPEAKER_STATE_ENDED || s.state == P.SPEAKER_STATE_ERROR) {
            // Nothing to seek in on the watch: load the song there, at that point.
            track?.let { load(loaded?.takeIf { l -> l.id == it.id } ?: it, to, play = wantsPlay) }
            return
        }
        status = s.copy(positionMs = to, at = System.currentTimeMillis())
        send(node.id, P.SPEAKER_SEEK, JSONObject().put(P.J_POSITION, to))
        awaitAnswer()
        show()
    }

    // ==========================================
    // What the watch says
    // ==========================================

    /** A [P.PATH_SPEAKER_STATUS] message, from any thread. */
    internal fun onStatus(context: Context, nodeId: String, data: ByteArray?) {
        val json = runCatching { JSONObject(data?.decodeToString().orEmpty()) }.getOrNull() ?: return
        val receivedAt = System.currentTimeMillis()
        // The watch's clock normally follows the phone's; a far-off one is replaced by the arrival time.
        val at = json.optLong(P.J_AT, receivedAt).takeIf { abs(receivedAt - it) < 3_000 } ?: receivedAt
        val reported = Status(
            seq = json.optInt(P.J_SEQ, -1),
            id = json.optString(P.J_ID),
            state = json.optString(P.J_STATE),
            positionMs = json.optLong(P.J_POSITION, 0L).coerceAtLeast(0L),
            at = at
        )
        main.post {
            if (!::app.isInitialized) app = context.applicationContext
            handle(nodeId, reported)
        }
    }

    private fun handle(nodeId: String, reported: Status) {
        if (!_active.value || _watch.value?.id != nodeId) {
            // A watch that still thinks it is the speaker of a phone that took the music back (or restarted).
            send(nodeId, P.SPEAKER_STOP, null)
            return
        }
        heardAt = SystemClock.elapsedRealtime()
        misses = 0
        // News about an older load.
        if (reported.seq != seq) return
        askedAt = 0L
        reloads = 0
        status = reported
        val current = manager.playbackState.value.currentTrack
        when (reported.state) {
            P.SPEAKER_STATE_PLAYING -> {
                wantsPlay = true
                skippedOnError = false
            }
            // Also when the watch paused by itself (a call, its headphones gone): that is the truth.
            P.SPEAKER_STATE_PAUSED -> wantsPlay = false
            P.SPEAKER_STATE_ENDED -> {
                wantsPlay = false
                if (current?.id == reported.id) manager.onExternalTrackEnded()
                return
            }
            P.SPEAKER_STATE_ERROR -> {
                wantsPlay = false
                if (current?.id == reported.id) onError(current, reported)
                return
            }
        }
        show()
    }

    /** The watch could not play [track]: the next song once, then the music comes back to the phone. */
    private fun onError(track: Track, reported: Status) {
        if (!skippedOnError) {
            skippedOnError = true
            Log.w(TAG, "The watch could not play '${track.title}': trying the next song")
            manager.playNext()
        } else {
            Log.w(TAG, "The watch could not play twice in a row: back to the phone")
            status = reported
            finish(resume = true, play = true)
        }
    }

    // ==========================================
    // Keeping the phone in step
    // ==========================================

    /** The watch's playhead now, by this phone's clock. */
    private fun positionNow(s: Status): Long {
        if (s.state != P.SPEAKER_STATE_PLAYING) return s.positionMs
        val position = s.positionMs + (System.currentTimeMillis() - s.at).coerceAtLeast(0L)
        val duration = manager.playbackState.value.durationMs
        return if (duration > 0) position.coerceAtMost(duration) else position
    }

    /** Shows the watch's state in the player, unless the phone has already moved on to another song. */
    private fun show() {
        val s = status ?: return
        if (manager.playbackState.value.currentTrack?.id != s.id) return
        val playing = when (s.state) {
            P.SPEAKER_STATE_PLAYING -> true
            P.SPEAKER_STATE_BUFFERING -> wantsPlay
            else -> false
        }
        manager.showExternalState(playing, s.state == P.SPEAKER_STATE_BUFFERING, positionNow(s))
    }

    private fun awaitAnswer() {
        askedAt = SystemClock.elapsedRealtime()
    }

    /** The watch lost the song (its app was restarted, or never got the command): load it again. */
    private fun reload() {
        val track = manager.playbackState.value.currentTrack ?: return
        reloads += 1
        if (reloads > MAX_RELOADS) {
            Log.w(TAG, "The watch does not answer: back to the phone, paused")
            finish(resume = true, play = false)
            return
        }
        Log.i(TAG, "No answer from the watch: loading '${track.title}' again")
        load(loaded?.takeIf { it.id == track.id } ?: track, positionMs(), play = wantsPlay)
    }

    private suspend fun tick() {
        while (true) {
            delay(TICK_MS)
            if (status?.state == P.SPEAKER_STATE_PLAYING) show()
            val asked = askedAt
            if (asked != 0L && SystemClock.elapsedRealtime() - asked > ANSWER_MS) {
                askedAt = 0L
                reload()
            }
        }
    }

    /** Gives the music back to the phone (paused, as unplugged headphones do) once the watch is gone. */
    private suspend fun watchLink() {
        while (true) {
            delay(CHECK_EVERY_MS)
            val node = _watch.value ?: return
            val connected = withContext(Dispatchers.IO) {
                runCatching {
                    Tasks.await(Wearable.getNodeClient(app).connectedNodes).any { it.id == node.id }
                }.getOrDefault(true)
            }
            misses = if (connected) 0 else misses + 1
            if (misses >= MISSES_TO_GIVE_UP) {
                Log.w(TAG, "'${node.name}' is out of reach")
                finish(resume = true, play = false)
                return
            }
            val silent = SystemClock.elapsedRealtime() - heardAt > SILENCE_MS
            if (status?.state == P.SPEAKER_STATE_PLAYING && silent && askedAt == 0L) {
                heardAt = SystemClock.elapsedRealtime()
                reload()
            }
        }
    }

    // ==========================================
    // Helpers
    // ==========================================

    /** The song the watch asks the audio of: the one last sent, else one in the queue. Any thread. */
    internal fun trackFor(id: String): Track? {
        if (id.isBlank()) return null
        loaded?.takeIf { it.id == id }?.let { return it }
        if (!::app.isInitialized) return null
        val state = manager.playbackState.value
        return state.currentTrack?.takeIf { it.id == id } ?: state.queue.firstOrNull { it.id == id }
    }

    private fun send(nodeId: String, op: String, payload: JSONObject?) {
        val path = P.PATH_SPEAKER + "/" + op
        Wearable.getMessageClient(app)
            .sendMessage(nodeId, path, payload?.toString()?.toByteArray() ?: ByteArray(0))
            .addOnFailureListener { Log.w(TAG, "Could not reach the watch ($op): ${it.message}") }
    }
}
