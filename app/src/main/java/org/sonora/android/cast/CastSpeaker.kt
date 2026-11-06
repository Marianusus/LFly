package org.sonora.android.cast

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.sonora.android.api.Track
import org.sonora.android.connect.ConnectManager
import org.sonora.android.playback.PartyTransportAction
import org.sonora.android.playback.PlaybackManager
import org.sonora.android.playback.SongBytes
import org.sonora.android.wear.WatchSpeaker

/**
 * A Chromecast (or any Google Cast speaker or TV) as the speaker.
 *
 * Choosing a Cast device used to open a session and nothing more: the music stayed on the phone.
 * Now, while a session is up, the phone keeps the queue (next, previous, shuffle, repeat, autoplay
 * and stream resolution all stay here, like with the watch) and the Cast device plays each song from
 * [CastStreamServer]. Play, pause and seek go to it; what it reports shows in the player.
 *
 * Everything here runs on the main thread, like the player.
 */
object CastSpeaker : PlaybackManager.ExternalSpeaker {
    private const val TAG = "LFlyCast"
    private const val TICK_MS = 500L

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile internal var context: Context? = null
        private set

    private var bound = false
    private var session: CastSession? = null
    private var client: RemoteMediaClient? = null
    private var ticker: Job? = null
    private var loading: Job? = null

    /** The song last sent and the load it belongs to. */
    private var loaded: Track? = null
    private var seq = 0
    private var wantsPlay = false
    private var endHandled = false
    private var retried = false

    private val manager: PlaybackManager
        get() = PlaybackManager.getInstance(context!!)

    val active: Boolean get() = session != null

    /** Follows the Cast sessions; called once the Cast framework is up ([CastDiscovery]). */
    internal fun bind(app: Context, cast: CastContext) {
        context = app.applicationContext
        if (bound) return
        bound = true
        cast.sessionManager.addSessionManagerListener(sessions, CastSession::class.java)
        // A session that outlived the app (the Cast device kept playing): pick it up.
        cast.sessionManager.currentCastSession?.takeIf { it.isConnected }?.let { begin(it) }
    }

    private val sessions = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = begin(session)
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = begin(session)
        override fun onSessionEnded(session: CastSession, error: Int) = finish(resume = true, play = false)
        override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
        override fun onSessionStarting(session: CastSession) = Unit
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            Log.w(TAG, "Cast session failed to start: $error")
            CastDiscovery.refresh()
        }
        override fun onSessionEnding(session: CastSession) = Unit
        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
        override fun onSessionResumeFailed(session: CastSession, error: Int) = CastDiscovery.refresh()
    }

    // ==========================================
    // Starting and stopping
    // ==========================================

    private fun begin(newSession: CastSession) {
        if (context == null) return
        if (session === newSession) return
        if (session != null) finish(resume = false, play = false)
        if (manager.partyGuestMode) return
        // The music comes back from the watch or an LFly Connect device first, then moves here.
        if (WatchSpeaker.active.value) {
            WatchSpeaker.stop()
            main.post { begin(newSession) }
            return
        }
        if (ConnectManager.following.value != null) {
            ConnectManager.playHere()
            main.post { begin(newSession) }
            return
        }
        val remote = newSession.remoteMediaClient ?: return
        if (!CastStreamServer.start()) {
            Log.w(TAG, "No local server: the Cast device cannot reach this phone")
            return
        }
        val state = manager.playbackState.value
        val position = if (state.isPlaying) manager.livePositionMs() else state.positionMs
        val play = state.isPlaying
        session = newSession
        client = remote
        loaded = null
        retried = false
        remote.registerCallback(status)
        Log.i(TAG, "The music plays on '${newSession.castDevice?.friendlyName}'")
        CastDiscovery.refresh()
        manager.attachExternalSpeaker(this)
        state.currentTrack?.let { load(it, position, play) }
        ticker = scope.launch { tick() }
    }

    /**
     * Ends the Cast device's turn. With [resume] the song goes on on the phone from where the Cast
     * device was, playing or paused as [play] says.
     */
    internal fun finish(resume: Boolean, play: Boolean) {
        val ending = session ?: return
        val position = positionMs()
        Log.i(TAG, "The music leaves '${ending.castDevice?.friendlyName}' (resume here: $resume, playing: $play)")
        runCatching { client?.unregisterCallback(status) }
        ticker?.cancel()
        loading?.cancel()
        ticker = null
        loading = null
        session = null
        client = null
        loaded = null
        CastStreamServer.stop()
        CastDiscovery.refresh()
        manager.detachExternalSpeaker(this, if (resume) position else null, play)
    }

    /** The listener picked another output: the song goes on here, playing if it played there. */
    internal fun handBack() {
        if (session == null) return
        finish(resume = true, play = wantsPlay)
    }

    // ==========================================
    // PlaybackManager.ExternalSpeaker
    // ==========================================

    override fun load(track: Track, positionMs: Long) = load(track, positionMs, play = true)

    override fun transport(action: PartyTransportAction, positionMs: Long): Boolean {
        val remote = client ?: return false
        when (action) {
            PartyTransportAction.PLAY_PAUSE -> if (wantsPlay) pause(remote) else play()
            PartyTransportAction.PLAY -> play()
            PartyTransportAction.PAUSE -> pause(remote)
            PartyTransportAction.SEEK -> {
                remote.seek(MediaSeekOptions.Builder().setPosition(positionMs.coerceAtLeast(0L)).build())
                manager.showExternalState(wantsPlay, wantsPlay, positionMs)
            }
            // The queue lives here: the phone picks the song and loads it.
            PartyTransportAction.NEXT, PartyTransportAction.PREVIOUS -> return false
        }
        return true
    }

    override fun positionMs(): Long {
        val state = manager.playbackState.value
        val remote = client
        if (remote == null || loaded?.id != state.currentTrack?.id || remote.mediaStatus == null) return state.positionMs
        return remote.approximateStreamPosition.coerceAtLeast(0L)
    }

    private fun play() {
        val remote = client ?: return
        val track = manager.playbackState.value.currentTrack ?: return
        val idle = remote.mediaStatus?.playerState?.let { it == MediaStatus.PLAYER_STATE_IDLE } ?: true
        if (loaded?.id != track.id || idle) {
            load(loaded?.takeIf { it.id == track.id } ?: track, manager.playbackState.value.positionMs, play = true)
            return
        }
        wantsPlay = true
        remote.play()
        manager.showExternalState(true, true, positionMs())
    }

    private fun pause(remote: RemoteMediaClient) {
        wantsPlay = false
        remote.pause()
        manager.showExternalState(false, false, positionMs())
    }

    private fun load(track: Track, positionMs: Long, play: Boolean) {
        val app = context ?: return
        val remote = client ?: return
        seq += 1
        val mine = seq
        loaded = track
        wantsPlay = play
        endHandled = false
        CastStreamServer.allowed = track
        manager.showExternalState(play, play, positionMs)
        loading?.cancel()
        loading = scope.launch {
            // The content type must be known up front; opening the song also warms the cache.
            val mime = withContext(Dispatchers.IO) {
                runCatching { SongBytes.open(app, track, 0L, fresh = false)?.use { it.mime } }.getOrNull()
            }
            if (mine != seq || client !== remote) return@launch
            if (mime == null) {
                Log.w(TAG, "No stream for '${track.title}': skipping")
                manager.playNext()
                return@launch
            }
            val url = CastStreamServer.urlFor(app, track, mine) ?: run {
                Log.w(TAG, "This phone has no address on the local network")
                finish(resume = true, play = play)
                return@launch
            }
            val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
                putString(MediaMetadata.KEY_TITLE, track.title)
                putString(MediaMetadata.KEY_ARTIST, track.artist)
                track.album?.takeIf { it.isNotBlank() }?.let { putString(MediaMetadata.KEY_ALBUM_TITLE, it) }
                track.coverUrl?.takeIf { it.startsWith("http") }?.let { addImage(WebImage(Uri.parse(it))) }
            }
            val info = MediaInfo.Builder(url)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(mime.ifBlank { "audio/mp4" })
                .setMetadata(metadata)
                .apply { if (track.durationMs > 0) setStreamDuration(track.durationMs) }
                .build()
            val request = MediaLoadRequestData.Builder()
                .setMediaInfo(info)
                .setAutoplay(play)
                .setCurrentTime(positionMs.coerceAtLeast(0L))
                .build()
            Log.i(TAG, "Casting '${track.title}' ($mime) from ${positionMs}ms")
            remote.load(request)
        }
    }

    // ==========================================
    // What the Cast device reports
    // ==========================================

    private val status = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            val remote = client ?: return
            val s = remote.mediaStatus ?: return
            when (s.playerState) {
                MediaStatus.PLAYER_STATE_PLAYING -> {
                    retried = false
                    manager.showExternalState(true, false, positionMs())
                }
                MediaStatus.PLAYER_STATE_BUFFERING, MediaStatus.PLAYER_STATE_LOADING ->
                    manager.showExternalState(wantsPlay, true, positionMs())
                MediaStatus.PLAYER_STATE_PAUSED -> {
                    // Paused from the TV's remote or another phone: follow it.
                    wantsPlay = false
                    manager.showExternalState(false, false, positionMs())
                }
                MediaStatus.PLAYER_STATE_IDLE -> onIdle(s.idleReason)
            }
        }
    }

    private fun onIdle(reason: Int) {
        val track = loaded ?: return
        when (reason) {
            MediaStatus.IDLE_REASON_FINISHED -> {
                if (endHandled) return
                endHandled = true
                manager.onExternalTrackEnded()
            }
            MediaStatus.IDLE_REASON_ERROR -> {
                if (endHandled) return
                if (!retried) {
                    retried = true
                    Log.w(TAG, "The Cast device could not play '${track.title}': trying again")
                    load(track, manager.playbackState.value.positionMs, wantsPlay)
                } else {
                    endHandled = true
                    Log.w(TAG, "The Cast device could not play '${track.title}': skipping")
                    manager.playNext()
                }
            }
            // A new load replaced the song, or the session's sender stopped it: nothing to do.
            else -> Unit
        }
    }

    private suspend fun tick() {
        while (true) {
            delay(TICK_MS)
            val remote = client ?: return
            val s = remote.mediaStatus ?: continue
            if (s.playerState == MediaStatus.PLAYER_STATE_PLAYING) {
                manager.showExternalState(true, false, positionMs())
            }
        }
    }
}
