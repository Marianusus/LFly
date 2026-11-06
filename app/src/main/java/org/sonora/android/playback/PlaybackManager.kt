package org.sonora.android.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
import org.sonora.android.api.ArtistCredits
import org.sonora.android.api.AudioQuality
import org.sonora.android.api.PlaybackSource
import org.sonora.android.api.StreamResolver
import org.sonora.android.api.StreamSource
import org.sonora.android.api.Track
import org.sonora.android.api.TrackSourcePreferences
import org.sonora.android.data.MusicRepository
import org.sonora.android.data.NetworkMonitor
import org.sonora.android.party.SyncEngine
import org.sonora.android.playback.automix.AutomixAnalyzer
import org.sonora.android.playback.dj.DjSession
import org.sonora.android.playback.automix.AutomixPlanner
import org.sonora.android.playback.automix.MixAnalysis
import org.sonora.android.playback.automix.MixAnalysisProgress
import org.sonora.android.playback.automix.MixTransition
import org.sonora.android.playback.automix.PlanFixes
import org.sonora.android.playback.automix.EnergyProfile
import org.sonora.android.playback.automix.Loudness
import org.sonora.android.playback.automix.MixPoint
import org.sonora.android.playback.automix.TransitionCurves
import org.sonora.android.playback.automix.TransitionStyle
import java.io.File
import android.media.MediaMetadataRetriever
import android.media.AudioDeviceInfo
import android.media.AudioManager
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Transport actions a Party guest forwards to its host instead of playing locally. */
enum class PartyTransportAction { PLAY_PAUSE, PLAY, PAUSE, NEXT, PREVIOUS, SEEK }

/** A change to the queue or play mode, sent to the LFly device this app controls. */
sealed interface RemoteEdit {
    data class Add(val tracks: List<Track>) : RemoteEdit
    data class Next(val track: Track) : RemoteEdit
    data class Remove(val index: Int) : RemoteEdit
    data class Move(val from: Int, val to: Int) : RemoteEdit
    data object ClearUpcoming : RemoteEdit
    data class Shuffle(val on: Boolean) : RemoteEdit
    data class Repeat(val mode: RepeatMode) : RemoteEdit
}

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

        /** Automix falls back to this crossfade when crossfade itself is off. */
        private const val AUTOMIX_FALLBACK_SECONDS = 5
        /**
         * Analysis starts this long before a song ends. The tail and the next head (about 59 s each)
         * are analysed one after the other, and the mix can leave up to 59 s before the end, so this
         * leaves about 90 s for both even on a slow phone.
         */
        private const val AUTOMIX_ANALYSIS_LEAD_MS = 150_000L
        private const val AUTOMIX_MIN_PREPARE_MS = 400L

        /** How early an Automix hands off, when the incoming song has room, so its bars lock before the mix. */
        private const val AUTOMIX_ALIGN_LEAD_MS = 3_000L

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

        /**
         * Builds the track that actually reaches the player.
         *
         * A fuzzy local match may supply the audio file (and, when there is no download
         * cover, the artwork), but it must never replace the catalog identity: favorites,
         * dislikes, history and the queue all key off [track]'s id, so swapping it for the
         * local track made the player heart look at an id that was never favorited.
         */
        internal fun isLocalStreamUrl(url: String?): Boolean =
            url != null && (url.startsWith("/") || url.startsWith("file://"))

        /** Offline the local copy keeps playing: a pinned source must never silence a download. */
        internal fun shouldStreamPinnedSource(
            trackId: String,
            pinnedSource: PlaybackSource?,
            isOnline: Boolean
        ): Boolean = pinnedSource != null && isOnline &&
            (trackId.startsWith("yt_") || trackId.startsWith("spotify_"))

        internal fun playableTrackFor(
            track: Track,
            localMatch: Track?,
            streamUrl: String,
            resolvedCoverUrl: String?,
            localCoverUrl: String?,
            isResolvedLocal: Boolean
        ): Track = track.copy(
            streamUrl = streamUrl,
            coverUrl = resolvedCoverUrl ?: localCoverUrl ?: localMatch?.coverUrl ?: track.coverUrl,
            provider = if (isResolvedLocal) "Local" else track.provider
        )
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
    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    /** Re-points this player's AudioTrack at a local output, or back to the system default. */
    fun setAudioOutput(deviceId: Int?) {
        val device: AudioDeviceInfo? = deviceId?.let { id ->
            runCatching {
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .firstOrNull { it.id == id }
            }.getOrNull()
        }
        player?.setPreferredAudioDevice(device)
        AudioRouting.select(deviceId)
    }

    fun audioOutputs(): List<AudioRouting.Device> =
        AudioRouting.outputs(context, AudioRouting.selectedId.value)

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

    // Identifies the newest playback request
    private val playRequestCounter = AtomicLong()

    // Position updater ticker
    private var positionTickerJob: Job? = null

    // Pending action in case play is requested before service is attached
    private var pendingPlayTrack: Track? = null

    // ── Sleep timer, speed and crossfade ─────────────────────────────────────
    private val _sleepTimer = MutableStateFlow<SleepTimer>(SleepTimer.Off)
    val sleepTimer: StateFlow<SleepTimer> = _sleepTimer.asStateFlow()
    private var sleepTimerJob: Job? = null

    private val _playbackSpeed = MutableStateFlow(1f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val crossfade by lazy { CrossfadeController(context, managerScope) }

    /** True while a crossfade moves to the next song, so that move does not cancel the crossfade. */
    private var crossfadeHandingOff = false

    /** The play request a crossfade was already tried for: one try per song start. */
    private var crossfadeTriedForRequest = -1L

    private val automix by lazy { AutomixAnalyzer(context, managerScope) }

    /** Where the song an Automix just moved to must start: its first downbeat. */
    private var automixEntry: Pair<String, Long>? = null

    private fun takeAutomixEntry(trackId: String): Long {
        val entry = automixEntry
        automixEntry = null
        return if (entry?.first == trackId) entry.second else 0L
    }

    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        val durationMs = minutes.coerceAtLeast(1) * 60_000L
        _sleepTimer.value = SleepTimer.At(SystemClock.elapsedRealtime() + durationMs)
        sleepTimerJob = managerScope.launch {
            delay(durationMs)
            sleepTimerJob = null
            _sleepTimer.value = SleepTimer.Off
            stopForSleep()
        }
    }

    fun setSleepTimerAtTrackEnd() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimer.value = SleepTimer.EndOfTrack
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimer.value = SleepTimer.Off
    }

    /** Local only: a Party guest going to sleep must not pause the host. */
    private fun stopForSleep() {
        cancelCrossfade()
        player?.pause()
        externalSpeaker?.transport(PartyTransportAction.PAUSE, 0L)
    }

    /** Changes tempo, keeping the pitch. A Party guest stays at the host's normal speed. */
    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(PLAYBACK_SPEEDS.first(), PLAYBACK_SPEEDS.last())
        _playbackSpeed.value = clamped
        if (!partyGuestMode) player?.setPlaybackParameters(PlaybackParameters(clamped))
    }

    private fun cancelCrossfade() {
        if (!crossfadeHandingOff) crossfade.cancel()
    }

    private val _mixPoint = MutableStateFlow<MixPoint?>(null)

    /** The transition planned for the playing song, for the seek bar; null when the song just ends. */
    val mixPoint: StateFlow<MixPoint?> = _mixPoint.asStateFlow()

    /** True when this song should hand over to the next one instead of simply ending. */
    private fun canTransition(): Boolean {
        val state = _playbackState.value
        if (partyGuestMode || _sleepTimer.value == SleepTimer.EndOfTrack) return false
        if (state.repeatMode == RepeatMode.ONE || state.queue.isEmpty()) return false
        return state.isShuffle ||
            state.repeatMode == RepeatMode.ALL ||
            state.currentIndex in 0 until state.queue.size - 1
    }

    /** Fade length of a plain crossfade: the listener's setting, or Automix's fallback. */
    private fun plainFadeMs(automixOn: Boolean): Long =
        (musicRepository.crossfadeSeconds.value.takeIf { it > 0 } ?: if (automixOn) AUTOMIX_FALLBACK_SECONDS else 0) * 1_000L

    /** Cache of [pairTransition], refreshed when the songs or the playlists change. */
    private var pairTransitionKey: String? = null
    private var pairTransitionValue: MixTransition? = null

    /**
     * The transition the listener set up in a mixed playlist between the playing song and the next,
     * or null when the two do not follow each other in one.
     */
    private fun pairTransition(): MixTransition? {
        val current = _playbackState.value.currentTrack ?: return null
        val next = peekNextTrack() ?: return null
        val key = current.id + ">" + next.id + "|" +
            System.identityHashCode(musicRepository.playlistMixes.value) + "|" +
            System.identityHashCode(musicRepository.playlists.value) + "|" +
            System.identityHashCode(musicRepository.mixes.value)
        if (key != pairTransitionKey) {
            pairTransitionValue = musicRepository.mixedTransition(current.id, next.id)
            pairTransitionKey = key
        }
        return pairTransitionValue
    }

    /** Automix runs when it is on in Settings, and always between two songs of a mixed playlist or of the DJ. */
    private fun automixActive(pair: MixTransition?): Boolean =
        pair != null || musicRepository.automixEnabled.value ||
            _playbackState.value.sourceContext == DjSession.SOURCE_CONTEXT

    private fun fixesOf(pair: MixTransition?): PlanFixes? = pair?.let {
        if (it.bars == null && it.exitMs == null && it.entryMs == null) null
        else PlanFixes(it.bars, it.exitMs?.let { ms -> ms / 1_000.0 }, it.entryMs?.let { ms -> ms / 1_000.0 })
    }

    /** Where the coming transition will sit: the Automix plan once both edges are analysed, else the fade. */
    private fun updateMixPoint(durationMs: Long) {
        val current = _playbackState.value.currentTrack
        val pair = pairTransition()
        if (current == null || !canTransition() || pair?.isNone == true) {
            _mixPoint.value = null
            return
        }
        val automixOn = automixActive(pair)
        if (automixOn && pair?.beatmatch != false) {
            val next = peekNextTrack()
            val outgoing = automix.grid(current.id, AutomixAnalyzer.Part.TAIL)
            val incoming = next?.let { automix.grid(it.id, AutomixAnalyzer.Part.HEAD) }
            if (next != null && outgoing != null && incoming != null) {
                val plan = AutomixPlanner.plan(
                    outgoing, durationMs, incoming,
                    automix.energy(current.id, AutomixAnalyzer.Part.TAIL),
                    automix.energy(next.id, AutomixAnalyzer.Part.HEAD),
                    fixesOf(pair)
                )
                if (plan != null) {
                    _mixPoint.value = MixPoint(current.id, plan.exitMs, plan.exitMs + plan.mixMs, beatMatched = true)
                    return
                }
            }
        }
        val fadeMs = unmatchedFadeMs(automixOn, pair)
        val start = pair?.exitMs?.takeIf { pair.beatmatch.not() && it + fadeMs <= durationMs } ?: (durationMs - fadeMs)
        _mixPoint.value = if (fadeMs > 0 && durationMs >= CrossfadeMath.MIN_TRACK_MS) {
            MixPoint(current.id, start, start + fadeMs, beatMatched = false)
        } else {
            null
        }
    }

    private fun maybeStartCrossfade(p: ExoPlayer, positionMs: Long, durationMs: Long) {
        if (crossfadeHandingOff || crossfade.isRunning) return
        val request = playRequestCounter.get()
        if (request == crossfadeTriedForRequest) return
        if (!canTransition()) return

        val pair = pairTransition()
        // "No transition" in a mixed playlist: the song plays to its end, then the next one starts.
        if (pair?.isNone == true) return
        val automixOn = automixActive(pair)
        if (automixOn && pair?.beatmatch != false && tryAutomix(p, positionMs, durationMs, request, pair)) return

        // Without a usable Automix plan the transition is a plain crossfade.
        val fadeMs = unmatchedFadeMs(automixOn, pair)
        val chosenExit = pair?.exitMs?.takeIf { !pair.beatmatch && it + fadeMs <= durationMs }
        val handoffMs = if (chosenExit != null) {
            if (positionMs < chosenExit - CrossfadeMath.PREPARE_LEAD_MS) return
            chosenExit.takeIf { positionMs <= it - AUTOMIX_MIN_PREPARE_MS } ?: return
        } else {
            CrossfadeMath.handoffPositionMs(positionMs, durationMs, fadeMs) ?: return
        }
        crossfadeTriedForRequest = request
        if (pair != null && !pair.beatmatch) pair.entryMs?.let { entry -> peekNextTrack()?.let { automixEntry = it.id to entry } }
        val gainDb = if (automixOn) automixGainDb() else 0.0
        // The listener's own crossfade setting stays a plain fade; only Automix shapes the transition.
        val recipe = when {
            !automixOn -> TransitionStyle.FADE.recipe
            // Turned off on purpose: the listener's ingredients play as they are, beats or not.
            pair != null && !pair.beatmatch -> pair.recipe ?: run {
                val (outgoingEnergy, incomingEnergy) = automixEnergies()
                automixStyle(beatMatched = false, outgoingEnergy, incomingEnergy).recipe
            }
            // The beats could not be matched: full-level ingredients would flam, so they are filtered instead.
            pair?.recipe?.needsBeatMatch == false -> pair.recipe!!
            else -> {
                val (outgoingEnergy, incomingEnergy) = automixEnergies()
                automixStyle(beatMatched = false, outgoingEnergy, incomingEnergy).recipe
            }
        }
        val beatMs = _playbackState.value.currentTrack
            ?.let { automix.grid(it.id, AutomixAnalyzer.Part.TAIL) }
            ?.beatSeconds?.times(1_000)
        crossfade.start(p, handoffMs, fadeMs, _playbackSpeed.value, outgoingGainDb = gainDb, recipe = recipe, beatMs = beatMs) {
            advanceForCrossfade(p)
        }
    }

    /**
     * Length of a transition that is not on the beat. A mixed playlist's transition with the beats
     * turned off keeps its bars at the outgoing song's tempo when that is known; otherwise the
     * listener's crossfade or Automix's fallback.
     */
    private fun unmatchedFadeMs(automixOn: Boolean, pair: MixTransition?): Long {
        if (pair != null && !pair.beatmatch) {
            val current = _playbackState.value.currentTrack
            val grid = current?.let { automix.grid(it.id, AutomixAnalyzer.Part.TAIL) }
            val bars = pair.bars
            if (grid != null && bars != null) return (bars * 4 * grid.beatSeconds * 1_000).toLong()
        }
        return plainFadeMs(automixOn)
    }

    /** Energy of this song's ending and the next song's start, each null until analysed. */
    private fun automixEnergies(): Pair<EnergyProfile?, EnergyProfile?> {
        val current = _playbackState.value.currentTrack ?: return null to null
        val next = peekNextTrack() ?: return null to null
        return automix.energy(current.id, AutomixAnalyzer.Part.TAIL) to
            automix.energy(next.id, AutomixAnalyzer.Part.HEAD)
    }

    /**
     * The listener's mix style for this transition; a calm edge on either side keeps AUTO on a fade.
     * AUTO's choices for this pair of songs are the ones the playlist editor shows for it.
     */
    private fun automixStyle(
        beatMatched: Boolean,
        outgoingEnergy: EnergyProfile?,
        incomingEnergy: EnergyProfile?,
        landsRise: Boolean = false
    ): TransitionStyle {
        val current = _playbackState.value.currentTrack
        val next = peekNextTrack()
        return TransitionCurves.resolve(
            musicRepository.automixStyle.value,
            beatMatched = beatMatched,
            calm = outgoingEnergy?.isCalm == true || incomingEnergy?.isCalm == true,
            landsRise = landsRise,
            seed = if (current != null && next != null) TransitionCurves.pairSeed(current.id, next.id) else 0
        )
    }

    /** Level difference between this song's ending and the next song's start, when both were analysed. */
    private fun automixGainDb(): Double {
        val current = _playbackState.value.currentTrack ?: return 0.0
        val next = peekNextTrack() ?: return 0.0
        return Loudness.matchGainDb(
            automix.loudness(current.id, AutomixAnalyzer.Part.TAIL),
            automix.loudness(next.id, AutomixAnalyzer.Part.HEAD)
        )
    }

    /** True when an Automix is planned for this song: started now, or waiting for its downbeat. */
    private fun tryAutomix(p: ExoPlayer, positionMs: Long, durationMs: Long, request: Long, pair: MixTransition?): Boolean {
        val current = _playbackState.value.currentTrack ?: return false
        val next = peekNextTrack() ?: return false
        val outgoing = automix.grid(current.id, AutomixAnalyzer.Part.TAIL) ?: return false
        val incoming = automix.grid(next.id, AutomixAnalyzer.Part.HEAD) ?: return false
        val outgoingEnergy = automix.energy(current.id, AutomixAnalyzer.Part.TAIL)
        val incomingEnergy = automix.energy(next.id, AutomixAnalyzer.Part.HEAD)
        val plan = AutomixPlanner.plan(outgoing, durationMs, incoming, outgoingEnergy, incomingEnergy, fixesOf(pair))
            ?: return false
        // When the incoming song starts past its first moments, hand off a little early so the bars
        // lock while it is still silent and the mix itself starts on its downbeat already together.
        val lead = AUTOMIX_ALIGN_LEAD_MS.takeIf {
            plan.entryMs >= it * plan.incomingRate && plan.exitMs - it >= CrossfadeMath.MIN_TRACK_MS
        } ?: 0L
        val handoffMs = plan.exitMs - lead
        if (positionMs > handoffMs - AUTOMIX_MIN_PREPARE_MS) return false
        if (positionMs < handoffMs - CrossfadeMath.PREPARE_LEAD_MS) return true

        crossfadeTriedForRequest = request
        automixEntry = next.id to (plan.entryMs - (lead * plan.incomingRate).toLong())
        val gainDb = automixGainDb()
        val style = automixStyle(beatMatched = true, outgoingEnergy, incomingEnergy, plan.landsRise)
        val recipe = pair?.recipe ?: style.recipe
        Log.i(
            TAG,
            "Automix '${current.title}' -> '${next.title}': exit ${plan.exitMs} ms, entry ${plan.entryMs} ms, " +
                "rate ${plan.incomingRate}, mix ${plan.mixMs} ms (${plan.mixBars} bars, rise ${plan.landsRise}), " +
                "${pair?.let { "playlist ${it.custom?.let { "custom" } ?: it.preset}" } ?: style}, " +
                "lead $lead ms, level %+.1f dB".format(gainDb)
        )
        crossfade.start(
            main = p,
            handoffMs = handoffMs,
            fadeMs = plan.mixMs + lead,
            speed = _playbackSpeed.value,
            beatMatch = BeatMatch(next.id, outgoing, incoming, plan.incomingRate),
            outgoingGainDb = gainDb,
            style = style,
            recipe = recipe,
            mixStartMs = plan.exitMs
        ) { advanceForCrossfade(p) }
        return true
    }

    private fun advanceForCrossfade(p: ExoPlayer) {
        crossfadeHandingOff = true
        try {
            // Paused, the old song cannot reach its end and advance the queue a second time.
            p.pause()
            handleTrackEnded()
        } finally {
            crossfadeHandingOff = false
        }
    }

    /** Analyses the end of this song and the start of the next while there is time to. */
    private fun maybePrepareAutomix(p: ExoPlayer, positionMs: Long, durationMs: Long) {
        if (partyGuestMode) return
        val pair = pairTransition()
        if (!automixActive(pair) || pair?.isNone == true) return
        if (durationMs < AutomixPlanner.MIN_SONG_MS || durationMs - positionMs > AUTOMIX_ANALYSIS_LEAD_MS) return
        val current = _playbackState.value.currentTrack ?: return
        val source = p.currentMediaItem?.localConfiguration?.uri?.toString() ?: return
        automix.request(current.id, source, AutomixAnalyzer.Part.TAIL, durationMs)
        val next = peekNextTrack() ?: return
        val nextSource = DownloadManager.localPathFor(next.id)
            ?: next.streamUrl?.takeUnless { it.isBlank() || isPreviewUrl(it) }
            ?: return
        automix.request(next.id, nextSource, AutomixAnalyzer.Part.HEAD, next.durationMs)
    }

    // ── Playlist Mix ─────────────────────────────────────────────────────────
    /** What has been found about one end of a song, from memory or disk; null until analysed. */
    internal fun mixAnalysis(trackId: String, part: AutomixAnalyzer.Part): MixAnalysis? = automix.analysis(trackId, part)

    /**
     * Analyses one end of [track] for a mixed playlist, finding its stream first. Null when the song
     * cannot be read (no stream, too short for its ending to be told apart).
     */
    internal suspend fun analyzeForMix(track: Track, part: AutomixAnalyzer.Part): MixAnalysis? {
        automix.analysis(track.id, part)?.let { return it }
        if (part == AutomixAnalyzer.Part.TAIL && track.durationMs < AutomixPlanner.MIN_SONG_MS) return null
        val source = mixSourceFor(track) ?: run {
            automix.markUnavailable(track.id, part)
            return null
        }
        return automix.analyze(track.id, source, part, track.durationMs)
    }

    /** True when this end of the song could not be read lately, so it is not worth trying again yet. */
    internal fun mixUnavailable(trackId: String, part: AutomixAnalyzer.Part): Boolean = automix.isUnavailable(trackId, part)

    private val _playlistAnalysis = MutableStateFlow<MixAnalysisProgress?>(null)

    /** The mixed playlist being analysed and how far it is; null when none is. */
    val playlistAnalysis: StateFlow<MixAnalysisProgress?> = _playlistAnalysis.asStateFlow()

    private val _analysedTracks = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /** Ids of songs a playlist analysis has just finished, so their tempo and key can be shown. */
    val analysedTracks: SharedFlow<String> = _analysedTracks.asSharedFlow()

    private var playlistAnalysisJob: Job? = null
    private var playlistAnalysisKey: List<String>? = null

    /**
     * Analyses the ends of [tracks] a mixed playlist's transitions need: every song's start but the
     * first's, every ending but the last's. It runs here rather than on the playlist's screen, so it
     * goes on when the screen is closed or the app is left, as long as the app runs. Ends analysed
     * before are skipped at once and ends that could not be read are left alone for a while, so
     * opening the playlist again only does what is left. Each song's stream is found once for both ends.
     */
    internal fun analyzePlaylist(playlistId: String, tracks: List<Track>) {
        val key = listOf(playlistId) + tracks.map { it.id }
        if (playlistAnalysisJob?.isActive == true && playlistAnalysisKey == key) return
        stopPlaylistAnalysis()
        if (tracks.size < 2) return
        playlistAnalysisKey = key
        val job = managerScope.launch {
            try {
                tracks.forEachIndexed { index, track ->
                    val needed = withContext(Dispatchers.IO) {
                        buildList {
                            if (index < tracks.size - 1) add(AutomixAnalyzer.Part.TAIL)
                            if (index > 0) add(AutomixAnalyzer.Part.HEAD)
                        }.filter { automix.analysis(track.id, it) == null && !automix.isUnavailable(track.id, it) }
                    }
                    if (needed.isEmpty()) return@forEachIndexed
                    _playlistAnalysis.value = MixAnalysisProgress(playlistId, index, tracks.size)
                    analyzeEnds(track, needed)
                    _analysedTracks.tryEmit(track.id)
                }
            } finally {
                if (playlistAnalysisJob === coroutineContext[Job]) {
                    _playlistAnalysis.value = null
                    playlistAnalysisKey = null
                }
            }
        }
        playlistAnalysisJob = job
    }

    /** Stops the playlist analysis; what it finished stays saved. */
    internal fun stopPlaylistAnalysis() {
        playlistAnalysisJob?.cancel()
        playlistAnalysisJob = null
        playlistAnalysisKey = null
        _playlistAnalysis.value = null
    }

    /** Analyses [parts] of [track], finding its stream only once; an unreadable song is remembered as such. */
    private suspend fun analyzeEnds(track: Track, parts: List<AutomixAnalyzer.Part>) {
        val readable = parts.filter { it != AutomixAnalyzer.Part.TAIL || track.durationMs >= AutomixPlanner.MIN_SONG_MS }
        if (readable.isEmpty()) return
        val source = mixSourceFor(track)
        if (source == null) {
            Log.w(TAG, "Playlist analysis: no full stream for '${track.title}'")
            readable.forEach { automix.markUnavailable(track.id, it) }
            return
        }
        for (part in readable) {
            try {
                automix.analyze(track.id, source, part, track.durationMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Playlist analysis of '${track.title}' $part failed", e)
            }
        }
    }

    /** A playable source for [track] outside the queue: a download, a local file, or a freshly resolved stream. */
    internal suspend fun mixSourceFor(track: Track): String? = withContext(Dispatchers.IO) {
        DownloadManager.localPathFor(track.id)?.let { return@withContext it }
        track.streamUrl?.takeIf { it.isNotBlank() && !isPreviewUrl(it) }?.let { return@withContext it }
        musicRepository.findLocalTrack(track.title, track.artist)?.streamUrl?.takeIf { it.isNotBlank() }
            ?.let { return@withContext it }
        val resolved = runCatching { streamResolver.resolveStream(track) }.getOrNull() ?: return@withContext null
        resolved.url.takeIf { it.isNotBlank() && !resolved.isPreview && !isPreviewUrl(it) }
    }

    private val transitionPreview by lazy { TransitionPreview(context, managerScope) }

    /** The editor's preview, null when none plays. */
    val previewState: StateFlow<PreviewState?> get() = transitionPreview.state

    /** Plays a transition preview; the music playing now pauses for it. */
    internal fun previewTransition(request: TransitionPreview.Request) {
        if (player?.isPlaying == true) player?.pause()
        transitionPreview.play(request)
    }

    fun stopTransitionPreview() = transitionPreview.stop()

    // ── DJ hooks ─────────────────────────────────────────────────────────────
    /**
     * While set, autoplay asks the DJ for the next songs instead of the recommendation engine. An
     * empty answer falls back to normal autoplay.
     */
    @Volatile
    var autoplayProvider: (suspend (seed: Track) -> List<Track>)? = null

    /** True while a crossfade or an Automix transition is running. */
    val isTransitioning: Boolean
        get() = crossfade.isRunning

    /** Lowers the music under a DJ voice line (1f is full). Ignored during a transition, which owns the volume. */
    fun setVoiceDuck(level: Float) {
        if (crossfade.isRunning) return
        player?.volume = level.coerceIn(0f, 1f)
    }

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
        if (connectTransportHandler?.invoke(action, positionMs) == true) return true
        if (externalSpeaker?.transport(action, positionMs) == true) return true
        return partyTransportHandler?.invoke(action, positionMs) == true
    }

    // ==========================================
    // Another speaker fed by this phone (the watch)
    // ==========================================

    /**
     * A speaker this phone feeds while it keeps the queue: the LFly watch. Unlike LFly Connect the
     * other side holds no queue, so next, previous, shuffle, repeat, autoplay and stream resolution
     * all keep running here; only the sound moves.
     */
    interface ExternalSpeaker {
        /** Plays [track] (its stream resolved, or a local file) from [positionMs]. */
        fun load(track: Track, positionMs: Long)

        /** Play, pause, toggle and seek; false leaves the action to the phone (next, previous). */
        fun transport(action: PartyTransportAction, positionMs: Long): Boolean

        /** Where the speaker's playhead is now. */
        fun positionMs(): Long
    }

    /** While set, the local player stays paused and every song goes to this speaker instead. */
    @Volatile
    var externalSpeaker: ExternalSpeaker? = null
        private set

    /** Moves the sound to [speaker]: the local player pauses and stays silent until it is detached. */
    fun attachExternalSpeaker(speaker: ExternalSpeaker) {
        externalSpeaker = speaker
        crossfade.cancel()
        player?.pause()
        stopPositionTicker()
    }

    /**
     * Takes the sound back from [speaker]. With [positionMs] the song goes on here from there,
     * playing or paused as [play] says; null means it moved elsewhere (LFly Connect), so the
     * local player is left alone. A speaker already replaced changes nothing.
     */
    fun detachExternalSpeaker(speaker: ExternalSpeaker, positionMs: Long?, play: Boolean) {
        if (externalSpeaker !== speaker) return
        externalSpeaker = null
        if (positionMs == null) return
        val state = _playbackState.value
        val track = state.currentTrack ?: return
        val position = positionMs.coerceAtLeast(0L)
        _playbackState.update { it.copy(positionMs = position, isPlaying = false, isBuffering = false, isLoading = false) }
        val p = player
        when {
            p != null && p.currentMediaItem?.mediaId == track.id && p.playerError == null -> {
                // The local player already holds this song (it followed along for the notification).
                p.seekTo(position)
                if (play) resumeOrRecover()
            }
            // Nothing loaded here: play starts the song from the state's position (resumeOrRecover).
            p == null || p.mediaItemCount == 0 -> if (play) resumeOrRecover()
            else -> {
                // The player holds another song: load this one, held paused if it was paused there.
                if (!play) startPausedRequest = playRequestCounter.get() + 1
                if (state.currentIndex in state.queue.indices) playFromQueueIndex(state.currentIndex, position) else playTrack(track)
            }
        }
    }

    /** The play request that loads its song paused: the music came back from the watch, paused. */
    @Volatile
    private var startPausedRequest = -1L

    /** What the speaker reports, shown as if it played here (the local player stays paused). */
    fun showExternalState(playing: Boolean, buffering: Boolean, positionMs: Long) {
        if (externalSpeaker == null) return
        _playbackState.update {
            it.copy(isPlaying = playing, isBuffering = buffering, isLoading = false, positionMs = positionMs.coerceAtLeast(0L))
        }
    }

    /** The speaker played the song to its end: move on as if it had ended here. */
    fun onExternalTrackEnded() {
        if (externalSpeaker != null) handleTrackEnded()
    }

    // ==========================================
    // LFly Connect: controlling another device
    // ==========================================

    /** While this app controls another LFly, transport goes there instead of the local player. */
    var connectTransportHandler: ((PartyTransportAction, Long) -> Boolean)? = null

    /** While this app controls another LFly, a new queue starts there. */
    var connectPlayHandler: ((List<Track>, Int) -> Boolean)? = null

    /** While this app controls another LFly, a tap in the queue plays that row there. */
    var connectSkipHandler: ((Int) -> Boolean)? = null

    /** While this app controls another LFly, shuffle, repeat and queue edits go there. */
    var connectEditHandler: ((RemoteEdit) -> Boolean)? = null

    /**
     * Shows what another device is playing, as if it played here, without touching the local
     * player (which stays paused while another device plays).
     */
    fun showRemoteState(
        queue: List<Track>,
        index: Int,
        playing: Boolean,
        positionMs: Long,
        durationMs: Long,
        shuffle: Boolean = _playbackState.value.isShuffle,
        repeat: RepeatMode = _playbackState.value.repeatMode
    ) {
        val track = queue.getOrNull(index)
        _playbackState.update {
            it.copy(
                isShuffle = shuffle,
                repeatMode = repeat,
                queue = queue,
                currentIndex = if (track != null) index else -1,
                currentTrack = track ?: it.currentTrack,
                userQueueSize = queue.size,
                isPlaying = playing,
                isLoading = false,
                isBuffering = false,
                positionMs = positionMs,
                durationMs = if (durationMs > 0) durationMs else track?.durationMs ?: it.durationMs,
                error = null
            )
        }
    }

    /** Plays [tracks] here from [index] at [positionMs]: the music has just moved to this device. */
    fun playHandedOver(tracks: List<Track>, index: Int, positionMs: Long, play: Boolean) {
        if (tracks.isEmpty()) return
        val sanitized = tracks.map { sanitizeTrack(it) }
        val start = index.coerceIn(0, sanitized.lastIndex)
        _playbackState.update { it.copy(queue = sanitized, currentIndex = start, userQueueSize = sanitized.size) }
        rebuildShuffleList(sanitized.size, start)
        playFromQueueIndex(start, resumePositionMs = positionMs.coerceAtLeast(0L))
        if (!play) managerScope.launch {
            // Let the stream start, then hold it where it was, as the other device had it.
            kotlinx.coroutines.delay(1_500)
            player?.pause()
        }
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
        player?.setPlaybackParameters(PlaybackParameters(_playbackSpeed.value))
    }

    /** Live playback position (the state flow's value can lag behind the ticker). */
    fun livePositionMs(): Long =
        externalSpeaker?.positionMs() ?: player?.currentPosition ?: _playbackState.value.positionMs

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (externalSpeaker != null) {
                // Another speaker plays: the paused local player only holds the song for the
                // notification, and can tell its length.
                val p = player
                if (playbackState == Player.STATE_READY && p != null && p.duration > 0 &&
                    p.currentMediaItem?.mediaId == _playbackState.value.currentTrack?.id
                ) {
                    _playbackState.update { it.copy(durationMs = p.duration) }
                }
                return
            }
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

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Any pause (button, call, unplugged headphones, sleep) also silences a fading tail.
            if (!playWhenReady) cancelCrossfade()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (externalSpeaker != null) {
                // The watch is the speaker: this player stays silent, whatever asked it to play.
                if (isPlaying) player?.pause()
                return
            }
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
            if (externalSpeaker != null) return
            val pos = player?.currentPosition ?: 0L
            _playbackState.update { it.copy(positionMs = pos) }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "ExoPlayer playback error: ${error.errorCodeName} - ${error.message}", error)
            // The paused local copy failing to buffer must not retry or skip what the watch plays.
            if (externalSpeaker != null) return
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
        AudioRouting.infoFor(context, AudioRouting.selectedId.value)?.let {
            exoPlayer.setPreferredAudioDevice(it)
        }

        // Attach native equalizer and bass boost to ExoPlayer's audio session
        val currentAudioSession = exoPlayer.audioSessionId
        if (currentAudioSession > 0 && currentAudioSession != C.AUDIO_SESSION_ID_UNSET) {
            equalizerManager.attachAudioSession(currentAudioSession)
        }

        // Sync repeat mode with ExoPlayer
        applyRepeatModeToPlayer(_playbackState.value.repeatMode)
        if (!partyGuestMode) exoPlayer.setPlaybackParameters(PlaybackParameters(_playbackSpeed.value))

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
        crossfade.cancel()
        player?.removeListener(playerListener)
        player?.removeAnalyticsListener(analyticsListener)
        equalizerManager.detachAudioSession()
        stopPositionTicker()
        this.player = null
        this.mediaSession = null
    }

    private fun ensureServiceStarted() {
        if (!serviceBindRequested.compareAndSet(false, true)) return
        try {
            val intent = Intent(context, SonoraMediaSessionService::class.java)
            if (!context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)) {
                serviceBindRequested.set(false)
                Log.w(TAG, "SonoraMediaSessionService refused the binding")
            }
        } catch (e: Exception) {
            serviceBindRequested.set(false)
            Log.e(TAG, "Failed binding SonoraMediaSessionService", e)
        }
    }

    /** One long-lived connection: a new one per track would stay bound for the process lifetime. */
    private val serviceBindRequested = AtomicBoolean(false)
    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {}
        override fun onServiceDisconnected(name: android.content.ComponentName?) {}
        override fun onBindingDied(name: android.content.ComponentName?) {
            runCatching { context.unbindService(this) }
            serviceBindRequested.set(false)
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
        // Offline downloads can point their cover at a local file.
        if (url.startsWith("file://") || url.startsWith("/")) {
            return@withContext try {
                File(url.removePrefix("file://")).readBytes()
            } catch (e: Exception) {
                null
            }
        }
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
        cancelCrossfade()
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
        if (tracks.isNotEmpty() && connectPlayHandler?.invoke(tracks.map { sanitizeTrack(it) }, startIndex) == true) return
        cancelCrossfade()
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
                    // An empty player means the service was rebuilt mid-track: pick up where it was.
                    val resumeMs = if (noMediaItems) state.positionMs else 0L
                    playFromQueueIndex(state.currentIndex, resumeMs)
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
        cancelCrossfade()
        // After a seek the song may reach its end again: it gets a new crossfade.
        crossfadeTriedForRequest = -1L
        val clamped = positionMs.coerceAtLeast(0L)
        _playbackState.update { it.copy(positionMs = clamped) }
        player?.seekTo(clamped)
    }

    fun playNext() {
        if (forwardToParty(PartyTransportAction.NEXT)) return
        cancelCrossfade()
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
            if (autoplayActive(state) && state.currentTrack != null) {
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
        } else if (autoplayActive(state) && state.currentTrack != null) {
            triggerAutoplay(state.currentTrack, playImmediately = true)
        }
    }

    fun playPrevious() {
        if (forwardToParty(PartyTransportAction.PREVIOUS)) return
        cancelCrossfade()
        val p = player
        val currentPos = externalSpeaker?.positionMs() ?: p?.currentPosition ?: _playbackState.value.positionMs

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
        val requested = track ?: return false
        // A favorite saved while a downloaded copy is playing must keep the catalog identity
        // (original provider/id), not the Local file snapshot.
        val target = DownloadManager.canonicalTrackFor(requested) ?: requested
        val isFav = musicRepository.toggleFavorite(target)
        org.sonora.android.account.YouTubeLibrarySync.getInstance(context).onFavoriteChanged(target, isFav)
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
        if (connectEditHandler?.invoke(RemoteEdit.Repeat(mode)) == true) return
        _playbackState.update { it.copy(repeatMode = mode) }
        applyRepeatModeToPlayer(mode)
    }

    fun toggleRepeatMode() {
        setRepeatMode(_playbackState.value.repeatMode.next())
    }

    fun toggleShuffle() {
        if (connectEditHandler?.invoke(RemoteEdit.Shuffle(!_playbackState.value.isShuffle)) == true) return
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
        if (connectEditHandler?.invoke(RemoteEdit.Add(listOf(sanitizeTrack(track)))) == true) return
        val sanitized = sanitizeTrack(track)
        val wasEmpty = _playbackState.value.queue.isEmpty() || _playbackState.value.currentTrack == null
        // Append to the END of the user queue, i.e. just before any autoplay/radio tracks.
        val insertAt = _playbackState.value.userQueueSize
            .coerceIn(0, _playbackState.value.queue.size)
        _playbackState.update { state ->
            val updated = state.queue.toMutableList()
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
                if (shuffledIndices.isEmpty()) {
                    rebuildShuffleList(_playbackState.value.queue.size, _playbackState.value.currentIndex)
                } else {
                    shufflePointer = ShuffleIndexMath.onInsert(
                        indices = shuffledIndices,
                        pointer = shufflePointer,
                        insertAt = insertAt,
                        count = 1,
                        asNext = false
                    )
                }
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
        if (connectEditHandler?.invoke(RemoteEdit.Add(tracks.map { sanitizeTrack(it) })) == true) return
        val sanitized = tracks.map { sanitizeTrack(it) }
        val wasEmpty = _playbackState.value.queue.isEmpty() || _playbackState.value.currentTrack == null
        val insertAt = _playbackState.value.userQueueSize
            .coerceIn(0, _playbackState.value.queue.size)
        _playbackState.update { state ->
            val updated = state.queue.toMutableList()
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
                if (shuffledIndices.isEmpty()) {
                    rebuildShuffleList(_playbackState.value.queue.size, _playbackState.value.currentIndex)
                } else {
                    shufflePointer = ShuffleIndexMath.onInsert(
                        indices = shuffledIndices,
                        pointer = shufflePointer,
                        insertAt = insertAt,
                        count = sanitized.size,
                        asNext = false
                    )
                }
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
        if (connectEditHandler?.invoke(RemoteEdit.Next(sanitizeTrack(track))) == true) return
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
            if (shuffledIndices.isEmpty()) {
                rebuildShuffleList(_playbackState.value.queue.size, _playbackState.value.currentIndex)
            } else {
                shufflePointer = ShuffleIndexMath.onInsert(
                    indices = shuffledIndices,
                    pointer = shufflePointer,
                    insertAt = insertAt,
                    count = 1,
                    asNext = true
                )
            }
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
        if (connectEditHandler?.invoke(RemoteEdit.Remove(index)) == true) return
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

        if (state.isShuffle) {
            shufflePointer = ShuffleIndexMath.onRemove(shuffledIndices, shufflePointer, index)
        }
        // In shuffle, deleting the current track continues with the next shuffled track,
        // not with the linearly next queue item.
        val playIndex = if (isRemovingCurrent && state.isShuffle && shuffledIndices.isNotEmpty()) {
            shuffledIndices.getOrNull(shufflePointer) ?: newIndex
        } else {
            newIndex
        }

        _playbackState.update {
            val newUserQueueSize = (state.userQueueSize - if (index < state.userQueueSize) 1 else 0)
                .coerceIn(0, updated.size)
            it.copy(
                queue = updated,
                currentIndex = playIndex,
                currentTrack = if (isRemovingCurrent) updated[playIndex] else it.currentTrack,
                userQueueSize = newUserQueueSize
            )
        }

        if (isRemovingCurrent) {
            playFromQueueIndex(playIndex)
        } else {
            prefetchNextTrack()
            checkAndTriggerAutoplayIfNeeded()
        }
    }

    fun moveQueueItem(from: Int, to: Int) {
        if (connectEditHandler?.invoke(RemoteEdit.Move(from, to)) == true) return
        val state = _playbackState.value
        val queue = state.queue
        if (from !in queue.indices || to !in queue.indices || from == to) return
        // Reorder within a section only: never let a drag move a radio track into
        // the user queue (or vice-versa), which would corrupt the boundary.
        if (!canMoveWithinQueueSection(from, to, state.userQueueSize)) return

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
            ShuffleIndexMath.onMove(shuffledIndices, from, to)
        }
    }

    fun clearQueue() {
        cancelCrossfade()
        player?.stop()
        player?.clearMediaItems()
        prefetchedTrackIds.clear()
        shuffledIndices.clear()
        shufflePointer = -1
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

    /**
     * Visible queue "Svuota": drop every track ahead of the current one while the
     * playing track keeps going.
     */
    fun clearUpcoming() {
        val state = _playbackState.value
        val result = clearUpcoming(state.queue, state.currentIndex) ?: return
        if (state.isShuffle && shuffledIndices.isNotEmpty()) {
            shufflePointer = ShuffleIndexMath.onRemoveRange(
                indices = shuffledIndices,
                pointer = shufflePointer,
                from = result.removedFrom,
                toExclusive = result.removedTo
            )
        }
        _playbackState.update {
            it.copy(
                queue = result.queue,
                userQueueSize = result.userQueueSize
            )
        }
    }

    /**
     * Bitchord-style clear: drop only the tracks the user queued ahead of the current
     * one. The playing track and the radio/autoplay tail stay in place.
     */
    fun clearUserQueue() {
        if (connectEditHandler?.invoke(RemoteEdit.ClearUpcoming) == true) return
        val state = _playbackState.value
        val result = clearUserUpcoming(state.queue, state.currentIndex, state.userQueueSize)
            ?: return

        if (state.isShuffle && shuffledIndices.isNotEmpty()) {
            shufflePointer = ShuffleIndexMath.onRemoveRange(
                indices = shuffledIndices,
                pointer = shufflePointer,
                from = result.removedFrom,
                toExclusive = result.removedTo
            )
        }

        _playbackState.update {
            it.copy(
                queue = result.queue,
                userQueueSize = result.userQueueSize
            )
        }
        prefetchNextTrack()
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
        if (DownloadManager.isDownloaded(track.id)) return true
        if (track.provider.equals("Local", ignoreCase = true)) {
            return !track.streamUrl.isNullOrBlank()
        }
        val url = track.streamUrl
        if (url.isNullOrBlank() || isPreviewUrl(url)) return false
        if (track.provider.equals("Deezer", ignoreCase = true)) return false
        return true
    }

    fun playFromQueueIndex(index: Int, resumePositionMs: Long = 0L) {
        val state = _playbackState.value
        if (index !in state.queue.indices) return
        if (connectSkipHandler?.invoke(index) == true) return
        cancelCrossfade()

        // A direct tap can jump anywhere in the queue: keep the shuffle cursor in sync so
        // the following "next" continues from the tapped track.
        if (state.isShuffle && shuffledIndices.isNotEmpty()) {
            val position = shuffledIndices.indexOf(index)
            if (position >= 0) shufflePointer = position
        }

        val target = state.queue[index]
        _playbackState.update {
            it.copy(
                currentIndex = index,
                currentTrack = target,
                isLoading = !hasResolvedStream(target),
                isBuffering = false,
                positionMs = resumePositionMs,
                durationMs = target.durationMs,
                error = null
            )
        }

        executeStreamResolutionAndPlay(target, resumePositionMs = resumePositionMs)
    }

    private fun executeStreamResolutionAndPlay(
        track: Track,
        forceRefresh: Boolean = false,
        resumePositionMs: Long = 0L,
        forceSource: org.sonora.android.api.PlaybackSource? = null
    ) {
        // Only the newest request may reach the player: resolution and artwork take seconds, and
        // the listener can move to another track (or retry this one) in the meantime.
        val requestId = playRequestCounter.incrementAndGet()
        // An Automix starts the next song on its first downbeat rather than at 0.
        val startPositionMs = if (resumePositionMs > 0L) resumePositionMs else takeAutomixEntry(track.id)
        val isSuperseded = {
            requestId != playRequestCounter.get() || _playbackState.value.currentTrack?.id != track.id
        }
        managerScope.launch {
            try {
                _playbackState.update { it.copy(isLoading = !hasResolvedStream(track), error = null) }

                // Downloaded tracks keep their cover next to the audio file: use it first so
                // artwork still loads when the device is offline.
                val localCoverPath = DownloadManager.localCoverPathFor(track.id)

                // Preload album artwork bytes concurrently with stream resolution for Spotify-like rich notification
                val cachedArtwork = artworkMemoryCache[track.id]
                val artworkDeferred = if (cachedArtwork != null) {
                    CompletableDeferred(cachedArtwork)
                } else {
                    async(Dispatchers.IO) {
                        val bytes = if (localCoverPath != null) {
                            runCatching { File(localCoverPath).readBytes() }.getOrNull()
                        } else {
                            null
                        } ?: fetchArtworkBytes(track.coverUrl)
                        if (bytes != null) artworkMemoryCache[track.id] = bytes
                        bytes
                    }
                }

                // A source the listener pinned on this track wins over a local copy while online.
                val pinnedToStream = shouldStreamPinnedSource(
                    trackId = track.id,
                    pinnedSource = TrackSourcePreferences.overrideFor(track.id),
                    isOnline = NetworkMonitor.isOnline.value
                )

                // 0. Check local fast-path (yt-dlp downloaded tracks on device storage)
                val isDirectLocal = !pinnedToStream && !track.streamUrl.isNullOrBlank() && (
                    track.provider.equals("Local", ignoreCase = true) ||
                    track.streamUrl.startsWith("/") ||
                    track.streamUrl.startsWith("file://") ||
                    File(track.streamUrl).exists()
                )

                // Exact offline download mapping (independent of fuzzy title/artist matching).
                // When an exact download exists we skip the fuzzy scan: the download already
                // owns the audio file and the catalog identity must not be replaced.
                val downloadedPath = if (isDirectLocal || pinnedToStream) null else DownloadManager.localPathFor(track.id)
                val localTrackMatch = if (!isDirectLocal && !pinnedToStream && downloadedPath == null) {
                    musicRepository.findLocalTrack(track.title, track.artist)
                } else null
                val rememberedStreamUrl = track.streamUrl.takeUnless { pinnedToStream && isLocalStreamUrl(it) }

                val localAudioPath = when {
                    isDirectLocal -> track.streamUrl
                    downloadedPath != null -> downloadedPath
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
                } else if (!forceRefresh && !rememberedStreamUrl.isNullOrBlank() && !isDeezerPreview) {
                    rememberedStreamUrl
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
                        ?.takeUnless { pinnedToStream && isLocalStreamUrl(it) }
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
                if (isSuperseded()) {
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
                        autoplayActive(failureState) && failureState.currentTrack != null ->
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
                val localCoverUrl = localCoverPath?.let { "file://$it" }
                // Keep the catalog identity even when a local file supplied the audio. Adopting
                // the local track's id made the player heart check a different id from the one
                // favorites are stored under (canonicalTrackFor), so tapping did nothing.
                val playableTrack = playableTrackFor(
                    track = track,
                    localMatch = localTrackMatch,
                    streamUrl = streamUrl,
                    resolvedCoverUrl = resolvedCoverUrl,
                    localCoverUrl = localCoverUrl,
                    isResolvedLocal = isResolvedLocal
                ).let { playable ->
                    // Leaving a local copy: the track must stop presenting itself as Local.
                    val streamedProvider = resolvedProvider
                    if (pinnedToStream && streamedProvider != null) {
                        playable.copy(provider = streamedProvider)
                    } else {
                        playable
                    }
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

                // The artwork wait above can outlast a track switch.
                if (isSuperseded()) {
                    Log.d(TAG, "Discarding stale playback for '${track.title}' (${track.id})")
                    return@launch
                }

                // 2. Prepare Media3 ExoPlayer with MediaItem metadata and front cover artwork
                val metadataBuilder = MediaMetadata.Builder()
                    .setTitle(playableTrack.title)
                    .setArtist(playableTrack.artist)
                    .setAlbumTitle(playableTrack.album)
                    .setDisplayTitle(playableTrack.title)
                    // Android Auto's player reads the second line from the subtitle.
                    .setSubtitle(playableTrack.artist)
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
                val speaker = externalSpeaker
                if (speaker != null) {
                    // The watch is the speaker: it plays the song. The local player only takes the
                    // item, paused, so the notification and lock screen show what plays.
                    if (p != null) p.setMediaItem(mediaItem) else ensureServiceStarted()
                    speaker.load(playableTrack, startPositionMs)
                    Log.i(TAG, "Sent to the external speaker: ${playableTrack.title} (from ${startPositionMs}ms)")
                } else if (p != null) {
                    // Replace the item WITHOUT calling stop(): stop() drops the Media3 media
                    // notification and demotes the foreground service, forcing a re-promotion
                    // from the background on the very next frame (denied / ANR on Android 12+).
                    // setMediaItem() keeps the notification alive across the track switch.
                    p.setMediaItem(mediaItem)
                    p.prepare()
                    // Recovery of the same track resumes where it was instead of restarting at 0
                    // (e.g. a seek that hit an expired signed URL triggers this path).
                    if (startPositionMs > 0L) {
                        p.seekTo(startPositionMs)
                        _playbackState.update { it.copy(positionMs = startPositionMs) }
                    }
                    if (requestId == startPausedRequest) {
                        // Back from the watch, paused: ready here, waiting for play.
                        _playbackState.update { it.copy(isPlaying = false) }
                    } else {
                        p.play()
                        // Promote only once the player is actually running, so Media3 posts the
                        // foreground notification immediately and the 5s contract is satisfied.
                        startForegroundPlaybackService()
                    }
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
        if (_sleepTimer.value == SleepTimer.EndOfTrack) {
            _sleepTimer.value = SleepTimer.Off
            player?.pause()
            _playbackState.update { it.copy(isPlaying = false) }
            return
        }
        // In a Party the host owns the queue: wait for its next STATE instead of advancing.
        if (partyGuestMode) return
        when (state.repeatMode) {
            RepeatMode.ONE -> {
                val speaker = externalSpeaker
                if (speaker != null) {
                    // The watch's player has ended: it gets the song again rather than a seek.
                    state.currentTrack?.let { speaker.load(it, 0L) }
                } else {
                    seekTo(0L)
                    player?.play()
                }
            }
            RepeatMode.ALL -> {
                playNext()
            }
            RepeatMode.OFF -> {
                if (state.currentIndex in 0 until state.queue.size - 1 || state.isShuffle) {
                    playNext()
                } else if (autoplayActive(state) && state.currentTrack != null) {
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

    /** The queue keeps growing on its own with autoplay on, or while the DJ picks the songs. */
    private fun autoplayActive(state: PlaybackState): Boolean =
        state.isAutoplayEnabled || autoplayProvider != null

    private fun checkAndTriggerAutoplayIfNeeded() {
        val state = _playbackState.value
        if (!autoplayActive(state)) return
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

                // The DJ keeps its own rules on what may come back (a favourite, on purpose): only what is queued is off.
                val recommendations = djTracks(seedTrack, queueIds).ifEmpty {
                    withContext(Dispatchers.IO) {
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
                        val newIndices = (oldQueueSize until newTotalSize).toList()
                        ShuffleIndexMath.onAppend(shuffledIndices, newIndices)
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

    /**
     * The DJ's next songs for [seed], minus anything queued or disliked. Empty when
     * no DJ is on or it has nothing, so autoplay falls back to the recommendation engine.
     */
    private suspend fun djTracks(seed: Track, excludedIds: Set<String>): List<Track> {
        val provider = autoplayProvider ?: return emptyList()
        val picked = try {
            provider(seed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Autoplay: the DJ failed to pick songs after '${seed.title}'", e)
            return emptyList()
        }
        val dislikedIds = musicRepository.dislikedTrackIds.value
        val dislikedArtists = musicRepository.dislikedArtists.value
        val tracks = picked
            .distinctBy { it.id }
            .filter { it.id !in excludedIds && it.id !in dislikedIds && artistKey(it.artist) !in dislikedArtists }
        Log.i(TAG, "Autoplay: the DJ picked ${tracks.size} of ${picked.size} songs after '${seed.title}'")
        return tracks
    }

    /** Same key [MusicRepository] stores disliked artists under. */
    private fun artistKey(artist: String): String {
        val primary = ArtistCredits.primaryName(ArtistCredits.fromText(artist)).ifBlank { artist }
        return ArtistCredits.normalize(primary)
    }

    // ==========================================
    // Stream Prefetching
    // ==========================================

    /** The queue index "next" would play, with the same shuffle and dislike rules as [playNext]. */
    private fun peekNextIndex(state: PlaybackState): Int {
        val queue = state.queue
        return when {
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
    }

    private fun peekNextTrack(): Track? {
        val state = _playbackState.value
        val index = peekNextIndex(state)
        return if (index in state.queue.indices && index != state.currentIndex) state.queue[index] else null
    }

    private fun prefetchNextTrack() {
        val state = _playbackState.value
        val queue = state.queue
        if (queue.isEmpty() || state.currentIndex !in queue.indices) return

        val nextIndex = peekNextIndex(state)

        if (nextIndex in queue.indices && nextIndex != state.currentIndex) {
            val nextTrack = queue[nextIndex]

            // Already available offline: nothing to prefetch from the network.
            if (DownloadManager.isDownloaded(nextTrack.id)) {
                prefetchedTrackIds.add(nextTrack.id)
                return
            }

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
            autoplayActive(state) && currentTrack != null ->
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

                    if (dur > 0) {
                        maybePrepareAutomix(p, pos, dur)
                        maybeStartCrossfade(p, pos, dur)
                        // The incoming song already plays during a mix; its own point comes after.
                        if (!crossfade.isRunning) updateMixPoint(dur)
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
