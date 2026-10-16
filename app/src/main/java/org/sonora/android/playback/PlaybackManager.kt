package org.sonora.android.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.session.MediaSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import org.sonora.android.R
import org.sonora.android.api.DeezerClient
import org.sonora.android.api.AudioQuality
import org.sonora.android.api.StreamResolver
import org.sonora.android.api.StreamSource
import org.sonora.android.api.Track
import org.sonora.android.data.MusicRepository
import org.sonora.android.party.SyncEngine
import java.io.File
import android.media.MediaMetadataRetriever
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** Transport actions a Party guest forwards to its host instead of playing locally. */
enum class PartyTransportAction { PLAY_PAUSE, PLAY, PAUSE, NEXT, PREVIOUS, SEEK }

/**
 * Central singleton managing player state, track queues, playback controls,
 * stream resolution integration, and reactive [StateFlow] updates for the UI.
 */
class PlaybackManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "PlaybackManager"
        private const val MAX_RETRY_COUNT = 2
        // How many consecutive track failures to ride through (by skipping to the next
        // track) before giving up and stopping playback.
        private const val MAX_TRACK_SKIP = 3

        @Volatile
        private var instance: PlaybackManager? = null

        fun getInstance(context: Context): PlaybackManager {
            return instance ?: synchronized(this) {
                instance ?: PlaybackManager(context.applicationContext).also { instance = it }
            }
        }

        fun isPreviewUrl(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            return url.contains("dzcdn.net") || url.contains("/preview/")
        }

        fun sanitizeTrack(track: Track): Track {
            return if (isPreviewUrl(track.streamUrl) || track.provider.equals("Deezer", ignoreCase = true)) {
                track.copy(streamUrl = null)
            } else {
                track
            }
        }
    }

    private val managerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Dependencies
    private val musicRepository: MusicRepository by lazy { MusicRepository.getInstance(context) }
    var streamResolver: StreamResolver = StreamResolver.getInstance()
    private val deezerClient: DeezerClient by lazy { DeezerClient() }
    var recommendationEngine: RecommendationEngine = RecommendationEngine.getInstance(
        deezerClient = deezerClient,
        streamResolver = streamResolver
    )
    val equalizerManager: EqualizerManager by lazy { EqualizerManager.getInstance(context) }

    // Media3 player & session references (provided by SonoraMediaSessionService)
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    // State flow for UI
    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    // Shuffled queue indices helper
    private val shuffledIndices = mutableListOf<Int>()
    private var shufflePointer = -1

    // Retry tracking for stream failures
    private var retryCount = 0

    // Consecutive track-level failures (across tracks) used to bound skip-on-error
    private var consecutiveFailures = 0

    // Prefetch job tracking for tracks N+1 and aggressive stream caching
    private val activePrefetchJobs = ConcurrentHashMap<String, Job>()
    private val prefetchedTrackIds = ConcurrentHashMap.newKeySet<String>()

    // Memory cache for prefetched album artwork bytes
    private val artworkMemoryCache = ConcurrentHashMap<String, ByteArray>()

    // Guard to prevent concurrent autoplay recommendation queries
    private var isFetchingAutoplay = false

    // Position updater ticker
    private var positionTickerJob: Job? = null

    // Pending action in case play is requested before service is attached
    private var pendingPlayTrack: Track? = null

    // ── Party (group listening) hooks ────────────────────────────────────────
    /** True while this device follows a Party host: autoplay/queue logic is suspended. */
    @Volatile
    var partyGuestMode: Boolean = false

    /** Returns true when the Party layer already handled the action (guest forwarding). */
    @Volatile
    var partyTransportHandler: ((PartyTransportAction, Long) -> Boolean)? = null

    /** Set while applying a host state so transport calls don't bounce back to the host. */
    @Volatile
    private var partyApplyingRemote: Boolean = false

    /** Last drift correction applied to a Party guest (hysteresis state). */
    @Volatile
    private var partyDriftAction: SyncEngine.DriftAction = SyncEngine.DriftAction.NONE

    private fun forwardToParty(action: PartyTransportAction, positionMs: Long = 0L): Boolean {
        if (partyApplyingRemote) return false
        return partyTransportHandler?.invoke(action, positionMs) == true
    }

    /**
     * Applies a playback state pushed by the Party host (guest side).
     *
     * [targetPositionMs] is already compensated for clock offset and the local
     * headphone nudge by the caller. When the host is on a different track, the guest
     * switches to it; otherwise only the position/playing state is corrected.
     */
    fun applyRemoteState(track: Track?, targetPositionMs: Long, isPlaying: Boolean) {
        partyApplyingRemote = true
        try {
            val state = _playbackState.value
            val current = state.currentTrack
            if (track == null) return

            if (current?.id != track.id) {
                player?.setPlaybackParameters(PlaybackParameters.DEFAULT)
                partyDriftAction = SyncEngine.DriftAction.NONE
                val index = state.queue.indexOfFirst { it.id == track.id }
                if (index >= 0) {
                    playFromQueueIndex(index)
                } else {
                    playTrack(track, state.sourceContext)
                }
                return
            }

            applyRemotePositionLocked(targetPositionMs, isPlaying)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply remote Party state", e)
        } finally {
            partyApplyingRemote = false
        }
    }

    /**
     * Corrects the guest's position/playing state only, never changing the track.
     * Safe to call repeatedly (used to realign right after a new track starts).
     */
    fun applyRemotePosition(targetPositionMs: Long, isPlaying: Boolean) {
        partyApplyingRemote = true
        try {
            applyRemotePositionLocked(targetPositionMs, isPlaying)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply remote Party position", e)
        } finally {
            partyApplyingRemote = false
        }
    }

    private fun applyRemotePositionLocked(targetPositionMs: Long, isPlaying: Boolean) {
        val p = player ?: return

        if (!isPlaying) {
            p.setPlaybackParameters(PlaybackParameters.DEFAULT)
            partyDriftAction = SyncEngine.DriftAction.NONE
            if (p.isPlaying) p.pause()
            return
        }

        val drift = targetPositionMs - p.currentPosition
        when (SyncEngine.driftAction(drift, true, partyDriftAction)) {
            SyncEngine.DriftAction.SEEK -> {
                p.setPlaybackParameters(PlaybackParameters.DEFAULT)
                partyDriftAction = SyncEngine.DriftAction.NONE
                seekTo(targetPositionMs)
            }
            SyncEngine.DriftAction.SPEED_UP -> {
                partyDriftAction = SyncEngine.DriftAction.SPEED_UP
                p.setPlaybackParameters(PlaybackParameters(SyncEngine.SPEED_UP, 1f))
            }
            SyncEngine.DriftAction.SLOW_DOWN -> {
                partyDriftAction = SyncEngine.DriftAction.SLOW_DOWN
                p.setPlaybackParameters(PlaybackParameters(SyncEngine.SLOW_DOWN, 1f))
            }
            SyncEngine.DriftAction.NONE -> {
                partyDriftAction = SyncEngine.DriftAction.NONE
                p.setPlaybackParameters(PlaybackParameters.DEFAULT)
            }
        }

        if (!p.isPlaying) {
            if (p.playbackState == Player.STATE_IDLE) resumeOrRecover() else p.play()
        }
    }

    /** Restores normal playback speed and clears Party drift state (called when leaving a Party). */
    fun resetPartyPlaybackAdjustments() {
        partyDriftAction = SyncEngine.DriftAction.NONE
        player?.setPlaybackParameters(PlaybackParameters.DEFAULT)
    }

    /** Live playback position (the state flow's value can lag behind the ticker). */
    fun livePositionMs(): Long = player?.currentPosition ?: _playbackState.value.positionMs

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    _playbackState.update { it.copy(isBuffering = true) }
                }
                Player.STATE_READY -> {
                    val p = player
                    val duration = if (p != null && p.duration > 0) p.duration else _playbackState.value.currentTrack?.durationMs ?: 0L
                    _playbackState.update {
                        it.copy(
                            isLoading = false,
                            isBuffering = false,
                            durationMs = duration
                        )
                    }
                    retryCount = 0
                    consecutiveFailures = 0
                    prefetchNextTrack()
                    checkAndTriggerAutoplayIfNeeded()
                }
                Player.STATE_ENDED -> {
                    handleTrackEnded()
                }
                Player.STATE_IDLE -> {
                    _playbackState.update { it.copy(isLoading = false, isBuffering = false) }
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _playbackState.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) {
                startPositionTicker()
            } else {
                stopPositionTicker()
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            val pos = player?.currentPosition ?: 0L
            _playbackState.update { it.copy(positionMs = pos) }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "ExoPlayer playback error: ${error.errorCodeName} - ${error.message}", error)
            handlePlaybackError(error)
        }
    }

    private val analyticsListener = object : AnalyticsListener {
        override fun onAudioSessionIdChanged(
            eventTime: AnalyticsListener.EventTime,
            audioSessionId: Int
        ) {
            Log.i(TAG, "ExoPlayer onAudioSessionIdChanged: $audioSessionId")
            equalizerManager.attachAudioSession(audioSessionId)
        }
    }

    // ==========================================
    // Service Binding Lifecycle
    // ==========================================

    @OptIn(UnstableApi::class)
    fun attachPlayer(service: SonoraMediaSessionService, exoPlayer: ExoPlayer, session: MediaSession) {
        Log.i(TAG, "Attaching player and session to PlaybackManager")
        this.player = exoPlayer
        this.mediaSession = session

        exoPlayer.addListener(playerListener)
        exoPlayer.addAnalyticsListener(analyticsListener)

        // Attach native equalizer and bass boost to ExoPlayer's audio session
        val currentAudioSession = exoPlayer.audioSessionId
        if (currentAudioSession > 0 && currentAudioSession != C.AUDIO_SESSION_ID_UNSET) {
            equalizerManager.attachAudioSession(currentAudioSession)
        }

        // Sync repeat mode with ExoPlayer
        applyRepeatModeToPlayer(_playbackState.value.repeatMode)

        // Process any pending track play request
        val pending = pendingPlayTrack
        if (pending != null) {
            pendingPlayTrack = null
            playTrack(pending)
        }
    }

    @OptIn(UnstableApi::class)
    fun detachPlayer() {
        Log.i(TAG, "Detaching player from PlaybackManager")
        player?.removeListener(playerListener)
        player?.removeAnalyticsListener(analyticsListener)
        equalizerManager.detachAudioSession()
        stopPositionTicker()
        this.player = null
        this.mediaSession = null
    }

    private fun ensureServiceStarted() {
        try {
            val intent = Intent(context, SonoraMediaSessionService::class.java)
            context.bindService(intent, object : android.content.ServiceConnection {
                override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {}
                override fun onServiceDisconnected(name: android.content.ComponentName?) {}
            }, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed binding SonoraMediaSessionService", e)
        }
    }

    /**
     * Ensures the playback service is alive/bound when playback begins.
     *
     * The foreground promotion itself is owned by [SonoraMediaSessionService] together with Media3's
     * MediaNotificationManager. Calling `ContextCompat.startForegroundService()` from here is
     * redundant and, when it happens from the background (track switches driven by the notification,
     * lock screen or Bluetooth), illegal on Android 12+: it throws
     * `ForegroundServiceStartNotAllowedException` and, if the 5s started-service contract expires,
     * ANRs the app.
     */
    fun startForegroundPlaybackService() {
        ensureServiceStarted()
    }

    private suspend fun fetchArtworkBytes(url: String?): ByteArray? = withContext(Dispatchers.IO) {
        if (url.isNullOrBlank()) return@withContext null
        try {
            val request = Request.Builder()
                .url(url)
                .build()
            val client = OkHttpClient.Builder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(4, TimeUnit.SECONDS)
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.bytes()
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ==========================================
    // Public Playback Controls
    // ==========================================

    /**
     * Plays a single track immediately. If queue is empty, sets queue to this single track.
     */
    fun playTrack(track: Track, context: String? = null) {
        val sanitized = sanitizeTrack(track)
        val currentQueue = _playbackState.value.queue
        val existingIndex = currentQueue.indexOfFirst { it.id == sanitized.id }

        if (existingIndex != -1) {
            if (context != null) {
                _playbackState.update { it.copy(sourceContext = context) }
            }
            playFromQueueIndex(existingIndex)
        } else {
            // Append or create new queue
            val newQueue = listOf(sanitized)
            playQueue(newQueue, startIndex = 0, context = context)
        }
    }

    /**
     * Replaces the active queue and starts playback from [startIndex].
     */
    fun playQueue(tracks: List<Track>, startIndex: Int = 0, context: String? = null) {
        if (tracks.isEmpty()) {
            clearQueue()
            return
        }

        val sanitizedTracks = tracks.map { sanitizeTrack(it) }
        val clampedIndex = startIndex.coerceIn(0, sanitizedTracks.size - 1)
        val targetTrack = sanitizedTracks[clampedIndex]

        _playbackState.update {
            it.copy(
                queue = sanitizedTracks,
                currentIndex = clampedIndex,
                currentTrack = targetTrack,
                userQueueSize = sanitizedTracks.size,
                isLoading = !hasResolvedStream(targetTrack),
                isBuffering = false,
                positionMs = 0L,
                durationMs = targetTrack.durationMs,
                sourceContext = context ?: it.sourceContext,
                error = null
            )
        }

        rebuildShuffleList(sanitizedTracks.size, clampedIndex)

        if (player == null) {
            pendingPlayTrack = targetTrack
            ensureServiceStarted()
            return
        }

        executeStreamResolutionAndPlay(targetTrack)
    }

    /**
     * Re-resolves and restarts the current track from the beginning, bypassing any cached
     * stream URL. Used after the user switches the audio source for the currently playing track.
     */
    fun refreshCurrentStream() {
        val track = _playbackState.value.currentTrack ?: return
        Log.i(TAG, "Refreshing current stream for '${track.title}' (source switch)")
        executeStreamResolutionAndPlay(track, forceRefresh = true, resumePositionMs = 0L)
    }

    /**
     * Toggles play/pause state.
     */
    fun togglePlayPause() {
        if (forwardToParty(PartyTransportAction.PLAY_PAUSE)) return
        val p = player
        if (p == null) {
            val current = _playbackState.value.currentTrack
            if (current != null) {
                playTrack(current)
            }
            return
        }

        if (p.isPlaying) {
            p.pause()
        } else {
            resumeOrRecover()
        }
    }

    fun play() {
        if (forwardToParty(PartyTransportAction.PLAY)) return
        resumeOrRecover()
    }

    /**
     * Resumes playback, recovering from a failed/idle player when needed.
     *
     * A "play" command can arrive while ExoPlayer is stuck in STATE_IDLE after a stream
     * error (e.g. pressed from the notification or lock screen). A bare [Player.play] is
     * then a no-op and playback would never restart, so we re-resolve + re-prepare the
     * current track instead. This also clears any bad URL / cache state.
     */
    fun resumeOrRecover() {
        val p = player
        val state = _playbackState.value
        val current = state.currentTrack

        if (p == null || current == null) {
            current?.let { playTrack(it) } ?: ensureServiceStarted()
            return
        }

        if (p.playbackState == Player.STATE_ENDED) {
            p.seekTo(0L)
            p.play()
            startForegroundPlaybackService()
            return
        }

        val hasError = p.playerError != null
        val noMediaItems = p.mediaItemCount == 0
        val isIdle = p.playbackState == Player.STATE_IDLE

        when {
            hasError || noMediaItems -> {
                Log.i(TAG, "Recovering playback for '${current.title}' (error=$hasError, noItems=$noMediaItems)")
                retryCount = 0
                consecutiveFailures = 0
                if (state.currentIndex in state.queue.indices) {
                    playFromQueueIndex(state.currentIndex)
                } else {
                    playTrack(current)
                }
            }
            isIdle -> {
                // Item is present but never prepared (e.g. recovered from stop()).
                p.prepare()
                p.play()
                startForegroundPlaybackService()
            }
            else -> {
                p.play()
                startForegroundPlaybackService()
            }
        }
    }

    /**
     * Mirrors the host's queue on a Party guest without touching playback.
     *
     * The guest queue is read-only: it is whatever the host broadcasts, so autoplay
     * and local queue edits are intentionally not applied here.
     */
    fun mirrorRemoteQueue(tracks: List<Track>, currentIndex: Int) {
        if (tracks.isEmpty()) return
        val sanitized = tracks.map { sanitizeTrack(it) }
        val index = currentIndex.coerceIn(0, sanitized.lastIndex)
        _playbackState.update {
            it.copy(
                queue = sanitized,
                currentIndex = index,
                userQueueSize = sanitized.size
            )
        }
    }

    fun pause() {
        if (forwardToParty(PartyTransportAction.PAUSE)) return
        player?.pause()
    }

    fun seekTo(positionMs: Long) {
        if (forwardToParty(PartyTransportAction.SEEK, positionMs)) return
        val clamped = positionMs.coerceAtLeast(0L)
        _playbackState.update { it.copy(positionMs = clamped) }
        player?.seekTo(clamped)
    }

    fun playNext() {
        if (forwardToParty(PartyTransportAction.NEXT)) return
        val state = _playbackState.value
        val queue = state.queue
        if (queue.isEmpty()) return

        if (state.isShuffle) {
            if (shuffledIndices.isEmpty()) {
                rebuildShuffleList(queue.size, state.currentIndex)
            }
            var attempts = 0
            while (attempts < shuffledIndices.size) {
                shufflePointer = (shufflePointer + 1) % shuffledIndices.size
                val candidateIdx = shuffledIndices[shufflePointer]
                val candidateTrack = queue.getOrNull(candidateIdx)
                if (candidateTrack != null && !musicRepository.isDisliked(candidateTrack.id)) {
                    playFromQueueIndex(candidateIdx)
                    return
                }
                attempts++
            }
            if (state.isAutoplayEnabled && state.currentTrack != null) {
                triggerAutoplay(state.currentTrack, playImmediately = true)
            }
            return
        }

        var nextIndex = state.currentIndex + 1
        while (nextIndex < queue.size && musicRepository.isDisliked(queue[nextIndex].id)) {
            nextIndex++
        }
        if (nextIndex < queue.size) {
            playFromQueueIndex(nextIndex)
        } else if (state.repeatMode == RepeatMode.ALL) {
            playFromQueueIndex(0)
        } else if (state.isAutoplayEnabled && state.currentTrack != null) {
            triggerAutoplay(state.currentTrack, playImmediately = true)
        }
    }

    fun playPrevious() {
        if (forwardToParty(PartyTransportAction.PREVIOUS)) return
        val p = player
        val currentPos = p?.currentPosition ?: _playbackState.value.positionMs

        // If played more than 3 seconds, previous button restarts current track
        if (currentPos > 3000L) {
            seekTo(0L)
            return
        }

        val state = _playbackState.value
        val queue = state.queue
        if (queue.isEmpty()) return

        if (state.isShuffle) {
            if (shuffledIndices.isNotEmpty()) {
                shufflePointer = if (shufflePointer - 1 < 0) shuffledIndices.size - 1 else shufflePointer - 1
                val prevIndex = shuffledIndices[shufflePointer]
                playFromQueueIndex(prevIndex)
                return
            }
        }

        val prevIndex = state.currentIndex - 1
        if (prevIndex >= 0) {
            playFromQueueIndex(prevIndex)
        } else if (state.repeatMode == RepeatMode.ALL) {
            playFromQueueIndex(queue.size - 1)
        } else {
            seekTo(0L)
        }
    }

    fun isFavorite(trackId: String): Boolean {
        return musicRepository.isFavorite(trackId)
    }

    fun toggleFavorite(track: Track? = _playbackState.value.currentTrack): Boolean {
        val target = track ?: return false
        val isFav = musicRepository.toggleFavorite(target)
        Log.i(TAG, "Toggled favorite for '${target.title}': $isFav")
        return isFav
    }

    fun dislikeTrack(track: Track? = _playbackState.value.currentTrack) {
        val target = track ?: return
        Log.i(TAG, "Disliking track '${target.title}' by '${target.artist}'")
        musicRepository.addDislike(target)
        recommendationEngine.blacklistTrack(target.id)
        recommendationEngine.blacklistArtist(target.artist)
        playNext()
    }

    fun setRepeatMode(mode: RepeatMode) {
        _playbackState.update { it.copy(repeatMode = mode) }
        applyRepeatModeToPlayer(mode)
    }

    fun toggleRepeatMode() {
        setRepeatMode(_playbackState.value.repeatMode.next())
    }

    fun toggleShuffle() {
        val newShuffle = !_playbackState.value.isShuffle
        _playbackState.update { it.copy(isShuffle = newShuffle) }
        if (newShuffle) {
            rebuildShuffleList(_playbackState.value.queue.size, _playbackState.value.currentIndex)
        }
    }

    fun setAutoplayEnabled(enabled: Boolean) {
        _playbackState.update { it.copy(isAutoplayEnabled = enabled) }
        if (enabled) {
            checkAndTriggerAutoplayIfNeeded()
        }
    }

    fun toggleAutoplay() {
        val newEnabled = !_playbackState.value.isAutoplayEnabled
        setAutoplayEnabled(newEnabled)
    }

    fun addToQueue(track: Track) {
        val sanitized = sanitizeTrack(track)
        val wasEmpty = _playbackState.value.queue.isEmpty() || _playbackState.value.currentTrack == null
        _playbackState.update { state ->
            val updated = state.queue.toMutableList()
            // Append to the END of the user queue, i.e. just before any autoplay/radio tracks.
            val insertAt = state.userQueueSize.coerceIn(0, updated.size)
            updated.add(insertAt, sanitized)
            val newCurrentIndex = if (state.currentIndex == -1 && wasEmpty) 0 else state.currentIndex
            val newCurrentTrack = if (state.currentTrack == null && wasEmpty) sanitized else state.currentTrack
            state.copy(
                queue = updated,
                currentIndex = newCurrentIndex,
                currentTrack = newCurrentTrack,
                userQueueSize = state.userQueueSize + 1
            )
        }
        if (wasEmpty) {
            Log.i(TAG, "Queue was empty. Starting playback for: ${sanitized.title}")
            playFromQueueIndex(0)
        } else {
            if (_playbackState.value.isShuffle) {
                rebuildShuffleList(_playbackState.value.queue.size, _playbackState.value.currentIndex)
            }
            Log.i(TAG, "Added '${sanitized.title}' to user queue. User queue size: ${_playbackState.value.userQueueSize}")
            managerScope.launch(Dispatchers.Main) {
                try {
                    Toast.makeText(
                        context,
                        context.getString(R.string.playback_added_to_queue, sanitized.title),
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    Log.w(TAG, "Could not show toast", e)
                }
            }
            prefetchNextTrack()
            checkAndTriggerAutoplayIfNeeded()
        }
    }

    fun addToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val sanitized = tracks.map { sanitizeTrack(it) }
        val wasEmpty = _playbackState.value.queue.isEmpty() || _playbackState.value.currentTrack == null
        _playbackState.update { state ->
            val updated = state.queue.toMutableList()
            val insertAt = state.userQueueSize.coerceIn(0, updated.size)
            updated.addAll(insertAt, sanitized)
            val newCurrentIndex = if (state.currentIndex == -1 && wasEmpty) 0 else state.currentIndex
            val newCurrentTrack = if (state.currentTrack == null && wasEmpty) sanitized.first() else state.currentTrack
            state.copy(
                queue = updated,
                currentIndex = newCurrentIndex,
                currentTrack = newCurrentTrack,
                userQueueSize = state.userQueueSize + sanitized.size
            )
        }
        if (wasEmpty) {
            Log.i(TAG, "Queue was empty. Starting playback for: ${sanitized.first().title}")
            playFromQueueIndex(0)
        } else {
            if (_playbackState.value.isShuffle) {
                rebuildShuffleList(_playbackState.value.queue.size, _playbackState.value.currentIndex)
            }
            Log.i(TAG, "Added ${sanitized.size} tracks to user queue. User queue size: ${_playbackState.value.userQueueSize}")
            managerScope.launch(Dispatchers.Main) {
                try {
                    Toast.makeText(
                        context,
                        context.resources.getQuantityString(
                            R.plurals.playback_tracks_added_to_queue,
                            sanitized.size,
                            sanitized.size
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    Log.w(TAG, "Could not show toast", e)
                }
            }
            prefetchNextTrack()
            checkAndTriggerAutoplayIfNeeded()
        }
    }

    /**
     * Inserts [track] immediately AFTER the currently playing track (i.e. it becomes the
     * next track), keeping it inside the user queue. Used by the "Riproduci dopo" action.
     */
    fun addNext(track: Track) {
        val sanitized = sanitizeTrack(track)
        val state = _playbackState.value
        if (state.queue.isEmpty() || state.currentTrack == null) {
            addToQueue(sanitized)
            return
        }
        val insertAt = (state.currentIndex + 1).coerceIn(0, state.userQueueSize)
        _playbackState.update { s ->
            val updated = s.queue.toMutableList()
            updated.add(insertAt, sanitized)
            s.copy(
                queue = updated,
                userQueueSize = s.userQueueSize + 1
            )
        }
        if (_playbackState.value.isShuffle) {
            rebuildShuffleList(_playbackState.value.queue.size, _playbackState.value.currentIndex)
        }
        Log.i(TAG, "Inserted '${sanitized.title}' after current. User queue size: ${_playbackState.value.userQueueSize}")
        managerScope.launch(Dispatchers.Main) {
            try {
                Toast.makeText(
                    context,
                    context.getString(R.string.playback_will_play_next, sanitized.title),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Log.w(TAG, "Could not show toast", e)
            }
        }
        prefetchNextTrack()
    }

    fun removeFromQueue(index: Int) {
        val state = _playbackState.value
        if (index !in state.queue.indices) return

        val isRemovingCurrent = index == state.currentIndex
        val updated = state.queue.toMutableList()
        updated.removeAt(index)

        if (updated.isEmpty()) {
            clearQueue()
            return
        }

        val newIndex = when {
            index < state.currentIndex -> state.currentIndex - 1
            index == state.currentIndex -> index.coerceAtMost(updated.size - 1)
            else -> state.currentIndex
        }

        _playbackState.update {
            val newUserQueueSize = (state.userQueueSize - if (index < state.userQueueSize) 1 else 0)
                .coerceIn(0, updated.size)
            it.copy(
                queue = updated,
                currentIndex = newIndex,
                currentTrack = if (isRemovingCurrent) updated[newIndex] else it.currentTrack,
                userQueueSize = newUserQueueSize
            )
        }

        if (isRemovingCurrent) {
            playFromQueueIndex(newIndex)
        } else {
            prefetchNextTrack()
            checkAndTriggerAutoplayIfNeeded()
        }
    }

    fun moveQueueItem(from: Int, to: Int) {
        val state = _playbackState.value
        val queue = state.queue
        if (from !in queue.indices || to !in queue.indices || from == to) return
        // Only the user queue is reorderable; never let a drag move a radio track into it
        // (or vice-versa), which would corrupt the user-queue boundary.
        if (from >= state.userQueueSize || to >= state.userQueueSize) return

        val updated = queue.toMutableList()
        val item = updated.removeAt(from)
        updated.add(to, item)

        val currentIdx = state.currentIndex
        val newIndex = when {
            currentIdx == from -> to
            from < currentIdx && to >= currentIdx -> currentIdx - 1
            from > currentIdx && to <= currentIdx -> currentIdx + 1
            else -> currentIdx
        }

        _playbackState.update {
            it.copy(
                queue = updated,
                currentIndex = newIndex
            )
        }

        if (_playbackState.value.isShuffle) {
            rebuildShuffleList(updated.size, newIndex)
        }
    }

    fun clearQueue() {
        player?.stop()
        player?.clearMediaItems()
        prefetchedTrackIds.clear()
        _playbackState.update {
            it.copy(
                queue = emptyList(),
                currentIndex = -1,
                currentTrack = null,
                userQueueSize = 0,
                isPlaying = false,
                isLoading = false,
                isBuffering = false,
                positionMs = 0L,
                durationMs = 0L,
                sourceContext = null,
                streamProvider = null,
                error = null
            )
        }
    }

    // ==========================================
    // Internal Playback & Resolution Logic
    // ==========================================

    /**
     * True when [track] already carries a directly playable stream (a prefetched/resolved full
     * stream URL or a local file), so playback can start instantly without a resolution round-trip.
     * Used to avoid showing the loading spinner for queue tracks that were already prefetched.
     */
    private fun hasResolvedStream(track: Track): Boolean {
        if (track.provider.equals("Local", ignoreCase = true)) {
            return !track.streamUrl.isNullOrBlank()
        }
        val url = track.streamUrl
        if (url.isNullOrBlank() || isPreviewUrl(url)) return false
        if (track.provider.equals("Deezer", ignoreCase = true)) return false
        return true
    }

    fun playFromQueueIndex(index: Int) {
        val state = _playbackState.value
        if (index !in state.queue.indices) return

        val target = state.queue[index]
        _playbackState.update {
            it.copy(
                currentIndex = index,
                currentTrack = target,
                isLoading = !hasResolvedStream(target),
                isBuffering = false,
                positionMs = 0L,
                durationMs = target.durationMs,
                error = null
            )
        }

        executeStreamResolutionAndPlay(target)
    }

    private fun executeStreamResolutionAndPlay(
        track: Track,
        forceRefresh: Boolean = false,
        resumePositionMs: Long = 0L,
        forceSource: org.sonora.android.api.PlaybackSource? = null
    ) {
        managerScope.launch {
            try {
                _playbackState.update { it.copy(isLoading = !hasResolvedStream(track), error = null) }

                // Preload album artwork bytes concurrently with stream resolution for Spotify-like rich notification
                val cachedArtwork = artworkMemoryCache[track.id]
                val artworkDeferred = if (cachedArtwork != null) {
                    CompletableDeferred(cachedArtwork)
                } else {
                    async(Dispatchers.IO) {
                        val bytes = fetchArtworkBytes(track.coverUrl)
                        if (bytes != null) artworkMemoryCache[track.id] = bytes
                        bytes
                    }
                }

                // 0. Check local fast-path (yt-dlp downloaded tracks on device storage)
                val isDirectLocal = !track.streamUrl.isNullOrBlank() && (
                    track.provider.equals("Local", ignoreCase = true) ||
                    track.streamUrl.startsWith("/") ||
                    track.streamUrl.startsWith("file://") ||
                    File(track.streamUrl).exists()
                )

                val localTrackMatch = if (!isDirectLocal) {
                    musicRepository.findLocalTrack(track.title, track.artist)
                } else null

                val localAudioPath = when {
                    isDirectLocal -> track.streamUrl
                    localTrackMatch != null -> localTrackMatch.streamUrl
                    else -> null
                }

                // 1. Resolve audio stream URL via multi-tier StreamResolver or local fast-path
                // Never play 30s Deezer preview directly; always resolve full-length stream through StreamResolver
                val isDeezerPreview = isPreviewUrl(track.streamUrl) ||
                        track.provider.equals("Deezer", ignoreCase = true)
                var resolvedCoverUrl: String? = null
                var resolvedProvider: String? = null
                val streamUrl = if (localAudioPath != null) {
                    Log.i(TAG, "Using local fast-path for '${track.title}': $localAudioPath")
                    localAudioPath
                } else if (!forceRefresh && !track.streamUrl.isNullOrBlank() && !isDeezerPreview) {
                    track.streamUrl
                } else {
                    // Check if an in-flight prefetch job is already resolving this track
                    val inFlightJob = activePrefetchJobs[track.id]
                    if (inFlightJob != null && inFlightJob.isActive) {
                        Log.d(TAG, "Awaiting in-flight prefetch resolution for '${track.title}'")
                        inFlightJob.join()
                    }

                    // Check if queue track was updated by prefetch job
                    val currentInQueue = _playbackState.value.queue.find { it.id == track.id }
                    val currentUrl = currentInQueue?.streamUrl
                    val isCurrPreview = isPreviewUrl(currentUrl) ||
                            currentInQueue?.provider.equals("Deezer", ignoreCase = true)

                    if (!forceRefresh && !currentUrl.isNullOrBlank() && !isCurrPreview) {
                        Log.d(TAG, "Using prefetched stream URL for '${track.title}': $currentUrl")
                        resolvedProvider = currentInQueue?.provider
                        currentUrl
                    } else {
                        if (forceRefresh) {
                            streamResolver.invalidateCache(track.id, track.title, track.artist)
                        }
                        withContext(Dispatchers.IO) {
                            val resolved = streamResolver.resolveStream(track, forceSource)
                            resolvedCoverUrl = resolved?.resolvedCoverUrl
                            resolvedProvider = resolved?.provider
                            resolved?.url
                        }
                    }
                }

                // Discard a resolution that finished after the user switched track.
                if (_playbackState.value.currentTrack?.id != track.id) {
                    Log.d(TAG, "Discarding stale resolution for '${track.title}' (${track.id})")
                    return@launch
                }

                if (streamUrl.isNullOrBlank() || isPreviewUrl(streamUrl)) {
                    val errorMsg = "Unable to resolve full stream for ${track.title} (${track.artist})"
                    Log.w(TAG, errorMsg)
                    consecutiveFailures++
                    _playbackState.update {
                        it.copy(isLoading = false, isBuffering = false, error = errorMsg)
                    }

                    // Never silently stall or drop the media notification: stopping the player and
                    // clearing the timeline would demote the foreground service, which Android
                    // forbids re-promoting from the background (killing background playback). Skip
                    // to the next playable track instead (bounded), mirroring handlePlaybackError.
                    if (consecutiveFailures > MAX_TRACK_SKIP) {
                        Log.w(TAG, "Too many consecutive resolution failures ($consecutiveFailures); stopping playback.")
                        runCatching { player?.pause() }
                            .onFailure { Log.w(TAG, "Error pausing player after failed resolutions", it) }
                        _playbackState.update { it.copy(isPlaying = false, positionMs = 0L) }
                        return@launch
                    }
                    retryCount = 0
                    val failureState = _playbackState.value
                    val failureQueue = failureState.queue
                    val canAdvance = failureState.currentIndex in 0 until failureQueue.size - 1
                    when {
                        canAdvance -> playNext()
                        failureState.repeatMode == RepeatMode.ALL && failureQueue.isNotEmpty() ->
                            playFromQueueIndex(0)
                        failureState.isAutoplayEnabled && failureState.currentTrack != null ->
                            triggerAutoplay(failureState.currentTrack, playImmediately = true)
                        else -> {
                            runCatching { player?.pause() }
                                .onFailure { Log.w(TAG, "Error pausing player after failed resolution", it) }
                            _playbackState.update { it.copy(isPlaying = false, positionMs = 0L) }
                        }
                    }
                    return@launch
                }

                val isResolvedLocal = localAudioPath != null || streamUrl.startsWith("/") || streamUrl.startsWith("file://")
                val playableTrack = if (localTrackMatch != null) {
                    localTrackMatch.copy(streamUrl = streamUrl, provider = "Local")
                } else {
                    track.copy(
                        streamUrl = streamUrl,
                        coverUrl = resolvedCoverUrl ?: track.coverUrl,
                        provider = if (isResolvedLocal) "Local" else track.provider
                    )
                }
                _playbackState.update { state ->
                    val updatedQueue = state.queue.toMutableList()
                    val idx = updatedQueue.indexOfFirst { it.id == playableTrack.id }
                    if (idx != -1) {
                        updatedQueue[idx] = playableTrack
                    }
                    state.copy(
                        currentTrack = playableTrack,
                        queue = updatedQueue,
                        streamProvider = resolvedProvider ?: state.streamProvider
                    )
                }

                var artworkBytes = runCatching { artworkDeferred.await() }.getOrNull()
                if (artworkBytes == null && isResolvedLocal) {
                    artworkBytes = runCatching {
                        val path = if (streamUrl.startsWith("file://")) streamUrl.removePrefix("file://") else streamUrl
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(path)
                            retriever.embeddedPicture
                        } finally {
                            retriever.release()
                        }
                    }.getOrNull()
                }

                // 2. Prepare Media3 ExoPlayer with MediaItem metadata and front cover artwork
                val metadataBuilder = MediaMetadata.Builder()
                    .setTitle(playableTrack.title)
                    .setArtist(playableTrack.artist)
                    .setAlbumTitle(playableTrack.album)
                    .setDisplayTitle(playableTrack.title)
                    .setIsPlayable(true)

                playableTrack.coverUrl?.let {
                    metadataBuilder.setArtworkUri(Uri.parse(it))
                }
                if (artworkBytes != null) {
                    metadataBuilder.setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                }
                val mediaMetadata = metadataBuilder.build()

                val mediaUri = if (streamUrl.startsWith("/")) {
                    Uri.fromFile(File(streamUrl))
                } else {
                    Uri.parse(streamUrl)
                }

                val mediaItem = MediaItem.Builder()
                    .setMediaId(playableTrack.id)
                    .setUri(mediaUri)
                    .setMediaMetadata(mediaMetadata)
                    .build()

                val p = player
                if (p != null) {
                    // Replace the item WITHOUT calling stop(): stop() drops the Media3 media
                    // notification and demotes the foreground service, forcing a re-promotion
                    // from the background on the very next frame (denied / ANR on Android 12+).
                    // setMediaItem() keeps the notification alive across the track switch.
                    p.setMediaItem(mediaItem)
                    p.prepare()
                    // Recovery of the same track resumes where it was instead of restarting at 0
                    // (e.g. a seek that hit an expired signed URL triggers this path).
                    if (resumePositionMs > 0L) {
                        p.seekTo(resumePositionMs)
                        _playbackState.update { it.copy(positionMs = resumePositionMs) }
                    }
                    p.play()
                    // Promote only once the player is actually running, so Media3 posts the
                    // foreground notification immediately and the 5s contract is satisfied.
                    startForegroundPlaybackService()
                    Log.i(TAG, "Started playback for: ${playableTrack.title} -> $streamUrl (resume=${resumePositionMs}ms)")
                } else {
                    Log.w(TAG, "ExoPlayer not attached yet. Queuing track.")
                    pendingPlayTrack = playableTrack
                    ensureServiceStarted()
                }

                // 3. Save to recent history
                musicRepository.addToHistory(playableTrack)

                // 3b. Taste tracking: count the play when playback actually starts
                musicRepository.incrementPlayCount(playableTrack)

                // 4. Background prefetching and proactive queue extension
                prefetchNextTrack()
                checkAndTriggerAutoplayIfNeeded()

            } catch (e: Exception) {
                Log.e(TAG, "Error initiating playback for ${track.title}", e)
                _playbackState.update {
                    it.copy(
                        isLoading = false,
                        isBuffering = false,
                        error = "Playback failed: ${e.message}"
                    )
                }
            }
        }
    }

    private fun handleTrackEnded() {
        val state = _playbackState.value
        // Taste tracking: register a completion on a natural track end.
        state.currentTrack?.let { track ->
            val playedMs = if (state.positionMs > 0L) state.positionMs else state.durationMs
            musicRepository.recordCompletion(track, playedMs)
        }
        // In a Party the host owns the queue: wait for its next STATE instead of advancing.
        if (partyGuestMode) return
        when (state.repeatMode) {
            RepeatMode.ONE -> {
                seekTo(0L)
                player?.play()
            }
            RepeatMode.ALL -> {
                playNext()
            }
            RepeatMode.OFF -> {
                if (state.currentIndex in 0 until state.queue.size - 1 || state.isShuffle) {
                    playNext()
                } else if (state.isAutoplayEnabled && state.currentTrack != null) {
                    triggerAutoplay(state.currentTrack, playImmediately = true)
                } else {
                    _playbackState.update { it.copy(isPlaying = false, positionMs = 0L) }
                }
            }
        }
    }

    // ==========================================
    // Smart Autoplay & Continuous Radio
    // ==========================================

    private fun checkAndTriggerAutoplayIfNeeded() {
        val state = _playbackState.value
        if (!state.isAutoplayEnabled) return
        if (state.repeatMode != RepeatMode.OFF) return
        val currentTrack = state.currentTrack ?: return

        // Calculate remaining tracks in active queue
        val remainingTracks = if (state.isShuffle) {
            (shuffledIndices.size - 1) - shufflePointer
        } else {
            (state.queue.size - 1) - state.currentIndex
        }

        // Proactively fetch recommendations when queue has 2, 1, or 0 tracks remaining
        if (remainingTracks <= 2 && !isFetchingAutoplay) {
            triggerAutoplay(currentTrack, playImmediately = false)
        }
    }

    private fun triggerAutoplay(seedTrack: Track, playImmediately: Boolean = false) {
        // The host drives the queue during a Party; guests never self-expand it.
        if (partyGuestMode) return
        if (isFetchingAutoplay) return
        isFetchingAutoplay = true

        managerScope.launch {
            try {
                Log.d(TAG, "Autoplay: Fetching style/genre recommendations for '${seedTrack.title}' by '${seedTrack.artist}'")

                // Filter out already queued or recently played tracks (last 100 from history.json)
                val queueTracks = _playbackState.value.queue
                val queueIds = queueTracks.map { it.id }.toSet()
                val historyTracks = musicRepository.history.value.take(100)
                val historyIds = historyTracks.map { it.id }.toSet()
                val excludedIds = queueIds + historyIds

                val tasteProfile = TasteProfile.build(
                    followedArtists = musicRepository.followedArtists.value,
                    history = historyTracks,
                    topPlayedTracks = musicRepository.topPlayedTracks(15)
                        .map { it to musicRepository.playCountOf(it.id) },
                    topPlayedArtists = musicRepository.topPlayedArtists(10),
                    dislikedTrackIds = musicRepository.dislikedTrackIds.value,
                    dislikedArtists = musicRepository.dislikedArtists.value
                )

                val recommendations = withContext(Dispatchers.IO) {
                    recommendationEngine.getRecommendedTracks(
                        currentTrack = seedTrack,
                        count = 10,
                        excludedTrackIds = excludedIds,
                        activeQueueTracks = queueTracks,
                        historyTracks = historyTracks,
                        dislikedTrackIds = musicRepository.dislikedTrackIds.value,
                        dislikedArtists = musicRepository.dislikedArtists.value,
                        tasteProfile = tasteProfile
                    )
                }

                if (recommendations.isNotEmpty()) {
                    val oldQueueSize = _playbackState.value.queue.size
                    val wasAtEnd = _playbackState.value.currentIndex >= oldQueueSize - 1

                    _playbackState.update { current ->
                        val currentQueueIds = current.queue.map { it.id }.toSet()
                        val newUniqueTracks = recommendations.filter { it.id !in currentQueueIds }
                        val updatedQueue = current.queue.toMutableList()
                        updatedQueue.addAll(newUniqueTracks)
                        current.copy(queue = updatedQueue)
                    }

                    if (_playbackState.value.isShuffle) {
                        val newTotalSize = _playbackState.value.queue.size
                        val newIndices = (oldQueueSize until newTotalSize).toMutableList()
                        Collections.shuffle(newIndices)
                        shuffledIndices.addAll(newIndices)
                    }

                    Log.i(TAG, "Autoplay appended ${recommendations.size} tracks to queue. Total queue size: ${_playbackState.value.queue.size}")

                    // Immediately prefetch track N+1 from newly expanded queue
                    prefetchNextTrack()

                    if (playImmediately && wasAtEnd) {
                        playNext()
                    }
                } else {
                    Log.w(TAG, "Autoplay: No recommendations returned for '${seedTrack.title}'")
                    if (playImmediately) {
                        _playbackState.update { it.copy(isPlaying = false, isLoading = false, positionMs = 0L) }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during autoplay recommendation flow", e)
                if (playImmediately) {
                    _playbackState.update { it.copy(isPlaying = false, isLoading = false, positionMs = 0L) }
                }
            } finally {
                isFetchingAutoplay = false
            }
        }
    }

    // ==========================================
    // Stream Prefetching
    // ==========================================

    private fun prefetchNextTrack() {
        val state = _playbackState.value
        val queue = state.queue
        if (queue.isEmpty() || state.currentIndex !in queue.indices) return

        val nextIndex = when {
            state.isShuffle -> {
                if (shuffledIndices.isNotEmpty()) {
                    var nextPointer = (shufflePointer + 1) % shuffledIndices.size
                    var attempts = 0
                    var foundIdx = -1
                    while (attempts < shuffledIndices.size) {
                        val candidateIdx = shuffledIndices[nextPointer]
                        val candTrack = queue.getOrNull(candidateIdx)
                        if (candTrack != null && !musicRepository.isDisliked(candTrack.id)) {
                            foundIdx = candidateIdx
                            break
                        }
                        nextPointer = (nextPointer + 1) % shuffledIndices.size
                        attempts++
                    }
                    foundIdx
                } else -1
            }
            state.repeatMode == RepeatMode.ALL -> {
                var idx = (state.currentIndex + 1) % queue.size
                var attempts = 0
                while (attempts < queue.size && musicRepository.isDisliked(queue[idx].id)) {
                    idx = (idx + 1) % queue.size
                    attempts++
                }
                idx
            }
            state.repeatMode == RepeatMode.ONE -> {
                state.currentIndex
            }
            else -> {
                var idx = state.currentIndex + 1
                while (idx < queue.size && musicRepository.isDisliked(queue[idx].id)) {
                    idx++
                }
                idx
            }
        }

        if (nextIndex in queue.indices && nextIndex != state.currentIndex) {
            val nextTrack = queue[nextIndex]

            // If this track has already been prefetched, skip to prevent infinite loops
            if (prefetchedTrackIds.contains(nextTrack.id)) {
                return
            }

            // Check if next track is available locally on device
            val isNextDirectLocal = !nextTrack.streamUrl.isNullOrBlank() && (
                nextTrack.provider.equals("Local", ignoreCase = true) ||
                nextTrack.streamUrl.startsWith("/") ||
                nextTrack.streamUrl.startsWith("file://") ||
                File(nextTrack.streamUrl).exists()
            )
            val localNextMatch = if (!isNextDirectLocal) {
                musicRepository.findLocalTrack(nextTrack.title, nextTrack.artist)
            } else null

            if (isNextDirectLocal || localNextMatch != null) {
                prefetchedTrackIds.add(nextTrack.id)
                val resolvedLocalUrl = localNextMatch?.streamUrl ?: nextTrack.streamUrl
                if (localNextMatch != null && resolvedLocalUrl != null) {
                    _playbackState.update { current ->
                        if (nextIndex in current.queue.indices && current.queue[nextIndex].id == nextTrack.id) {
                            val updatedQueue = current.queue.toMutableList()
                            updatedQueue[nextIndex] = nextTrack.copy(
                                streamUrl = resolvedLocalUrl,
                                provider = "Local"
                            )
                            current.copy(queue = updatedQueue)
                        } else current
                    }
                }
                return
            }

            val isDeezerPreview = isPreviewUrl(nextTrack.streamUrl) ||
                    nextTrack.provider.equals("Deezer", ignoreCase = true)

            // If an active prefetch job is already running for nextTrack.id, do not cancel or restart it
            val existingJob = activePrefetchJobs[nextTrack.id]
            if (existingJob != null && existingJob.isActive) {
                return
            }

            val job = managerScope.launch(Dispatchers.IO) {
                try {
                    // Pre-fetch album artwork bytes into memory cache for instant notification artwork
                    nextTrack.coverUrl?.let { url ->
                        launch {
                            val bytes = fetchArtworkBytes(url)
                            if (bytes != null) {
                                artworkMemoryCache[nextTrack.id] = bytes
                            }
                        }
                    }
                    val streamSource = if (nextTrack.streamUrl.isNullOrBlank() || isDeezerPreview) {
                        Log.d(TAG, "Prefetching full stream for track N+1: '${nextTrack.title}' by '${nextTrack.artist}'")
                        streamResolver.resolveStream(nextTrack)
                    } else {
                        StreamSource(
                            url = nextTrack.streamUrl,
                            format = "audio/flac",
                            quality = AudioQuality.HIGH,
                            provider = nextTrack.provider
                        )
                    }

                    if (streamSource != null && streamSource.url.isNotBlank() && !streamSource.isPreview && !isPreviewUrl(streamSource.url)) {
                        prefetchedTrackIds.add(nextTrack.id)
                        withContext(Dispatchers.Main) {
                            _playbackState.update { current ->
                                if (nextIndex in current.queue.indices && current.queue[nextIndex].id == nextTrack.id) {
                                    val updatedQueue = current.queue.toMutableList()
                                    updatedQueue[nextIndex] = nextTrack.copy(
                                        streamUrl = streamSource.url,
                                        provider = streamSource.provider
                                    )
                                    current.copy(queue = updatedQueue)
                                } else {
                                    current
                                }
                            }
                        }
                        Log.i(TAG, "Prefetched stream successfully for '${nextTrack.title}' (${streamSource.provider}). Pre-caching audio bytes...")

                        // Proactively pre-cache the first 2MB into SimpleCache for instantaneous 0ms playback startup
                        AudioCacheManager.prefetchAudio(context, streamSource.url, 2L * 1024L * 1024L)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Prefetch failed for track '${nextTrack.title}'", e)
                } finally {
                    activePrefetchJobs.remove(nextTrack.id)
                }
            }
            activePrefetchJobs[nextTrack.id] = job
        }
    }

    private fun handlePlaybackError(error: PlaybackException) {
        val state = _playbackState.value
        val currentTrack = state.currentTrack

        // Drop any corrupted/partial cached fragment for the failing URL so the retry
        // re-downloads clean bytes from upstream instead of re-serving the same segment.
        runCatching {
            player?.currentMediaItem?.localConfiguration?.uri?.toString()
        }.getOrNull()?.let { AudioCacheManager.removeCachedUrl(it) }

        if (currentTrack != null && retryCount < MAX_RETRY_COUNT) {
            retryCount++
            // Resume at the last known position: the error (frequently a seek onto an expired
            // signed URL) must not throw the listener back to the start of the track.
            val resumeMs = _playbackState.value.positionMs
                .takeIf { it > 0L } ?: (player?.currentPosition ?: 0L)
            Log.w(
                TAG,
                "Retrying track playback (attempt $retryCount) for ${currentTrack.title} at ${resumeMs}ms: ${error.errorCodeName}"
            )
            executeStreamResolutionAndPlay(currentTrack, forceRefresh = true, resumePositionMs = resumeMs)
            return
        }

        // Retries exhausted: never silently stall mid-queue. Bump a bounded consecutive
        // failure counter and skip to the next playable track instead of leaving the
        // player dead in an error state (which also makes notification play a no-op).
        consecutiveFailures++
        Log.w(
            TAG,
            "Track '${currentTrack?.title}' failed with ${error.errorCodeName} " +
                "(consecutive #$consecutiveFailures); skipping if possible."
        )
        _playbackState.update {
            it.copy(isLoading = false, isBuffering = false, error = error.message ?: "Playback error encountered")
        }

        if (consecutiveFailures > MAX_TRACK_SKIP) {
            Log.w(TAG, "Too many consecutive track failures ($consecutiveFailures); stopping playback.")
            _playbackState.update { it.copy(isPlaying = false, positionMs = 0L) }
            return
        }

        retryCount = 0
        val queue = state.queue
        val canAdvance = state.currentIndex in 0 until queue.size - 1
        when {
            canAdvance -> playNext()
            state.repeatMode == RepeatMode.ALL && queue.isNotEmpty() -> playFromQueueIndex(0)
            state.isAutoplayEnabled && currentTrack != null ->
                triggerAutoplay(currentTrack, playImmediately = true)
            else -> _playbackState.update { it.copy(isPlaying = false, positionMs = 0L) }
        }
    }

    private fun applyRepeatModeToPlayer(mode: RepeatMode) {
        val p = player ?: return
        p.repeatMode = when (mode) {
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
        }
    }

    private fun rebuildShuffleList(size: Int, currentIndex: Int) {
        shuffledIndices.clear()
        if (size <= 0) return

        val indices = (0 until size).toMutableList()
        indices.remove(currentIndex)
        Collections.shuffle(indices)
        indices.add(0, currentIndex)

        shuffledIndices.addAll(indices)
        shufflePointer = 0
    }

    // ==========================================
    // Position Ticker Job
    // ==========================================

    private fun startPositionTicker() {
        positionTickerJob?.cancel()
        positionTickerJob = managerScope.launch {
            while (isActive) {
                val p = player
                if (p != null && p.isPlaying) {
                    val pos = p.currentPosition
                    val dur = p.duration
                    if (pos >= 0) {
                        _playbackState.update { state ->
                            state.copy(
                                positionMs = pos,
                                durationMs = if (dur > 0) dur else state.durationMs
                            )
                        }
                    }

                    // Proactively trigger autoplay queue extension when <= 45s remain
                    if (dur > 0 && pos > 0 && (dur - pos) <= 45_000L) {
                        checkAndTriggerAutoplayIfNeeded()
                    }

                    // Proactively pre-fetch and pre-cache next track when 25s or less remain
                    if (dur > 0 && pos > 0 && (dur - pos) <= 25_000L) {
                        prefetchNextTrack()
                    }
                }
                delay(250L) // 4 updates/sec for ultra-smooth UI progress bar
            }
        }
    }

    private fun stopPositionTicker() {
        positionTickerJob?.cancel()
        positionTickerJob = null
    }
}
