package org.sonora.android.playback.dj

import org.sonora.android.data.NetworkMonitor
import org.sonora.android.playback.dj.piper.PiperCatalog
import org.sonora.android.playback.dj.piper.PiperStore
import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.Locale
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.sonora.android.AppLanguage
import org.sonora.android.api.Track
import org.sonora.android.api.YouTubeMusicClient
import org.sonora.android.data.MusicRepository
import org.sonora.android.playback.PlaybackManager
import org.sonora.android.playback.PlaybackState
import org.sonora.android.playback.TasteProfile
import org.sonora.android.playback.automix.AutomixAnalyzer

/**
 * The DJ: plays the listener's music in themed blocks of a few songs and introduces each block with
 * its voice, like a radio host. One per process.
 */
class DjSession private constructor(private val context: Context) {

    companion object {
        /** `PlaybackState.sourceContext` of the queues the DJ starts. */
        const val SOURCE_CONTEXT = "dj"

        private const val TAG = "DjSession"

        /** The voice's duck ramp ends at 0.3; once the music is this low the line is being spoken. */
        private const val VOICE_STARTS_BELOW = 0.31f

        /** The first song may need a while to resolve its stream before the welcome can play. */
        private const val FIRST_LINE_WAIT_MS = 15_000L

        /**
         * Long enough for an Automix transition to finish: up to 35 s of mix, the few seconds the
         * bars take to lock before it, and an effect's tail after it.
         */
        private const val LINE_WAIT_MS = 60_000L

        /** Lets the new song be heard for a moment before the DJ talks over it. */
        private const val SETTLE_MS = 600L
        private const val POLL_MS = 250L

        private const val DISCOVERY_COUNT = 15
        private const val ARTIST_RELATED_COUNT = 10
        private const val ARTIST_TOP_TRACKS = 20

        /**
         * Candidates whose start is analysed at each step of a flow when their tempo is not known yet,
         * so the next song can be one that mixes on the beat. Each costs about a minute of audio
         * (kept in the cache, and on disk once measured, so a song is analysed only once).
         */
        private const val TEMPO_PROBES = 3
        private const val PROBE_TIMEOUT_MS = 20_000L

        @Volatile
        private var instance: DjSession? = null

        fun getInstance(context: Context): DjSession =
            instance ?: synchronized(this) {
                instance ?: DjSession(context.applicationContext).also { instance = it }
            }
    }

    private val _state = MutableStateFlow(DjState())
    val state: StateFlow<DjState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val playbackManager: PlaybackManager by lazy { PlaybackManager.getInstance(context) }
    private val repository: MusicRepository by lazy { MusicRepository.getInstance(context) }
    private val youTube: YouTubeMusicClient by lazy { YouTubeMusicClient() }
    private val random: Random = Random.Default
    private val blockMutex = Mutex()

    /** What autoplay calls while the DJ runs; compared by identity so stop() only clears its own. */
    private val provider: suspend (Track) -> List<Track> = { seed -> provideBlock(seed) }

    // Session state, touched on the main thread only.
    private var sessionJob: Job? = null
    private var speakJob: Job? = null
    private var voice: DjVoice? = null
    private val blocksById = HashMap<String, DjBlock>()
    private val blocksByKey = HashMap<String, DjBlock>()
    private val announced = HashSet<String>()
    private val sessionTracks = ArrayList<Track>()
    private var firstBlockStart: String? = null
    private var lastKind: DjBlockKind? = null
    private var lastArtist: String? = null
    private var lastMix: String? = null

    /** Starts the DJ: a first block with a spoken intro, then a new block whenever the queue runs low. */
    fun start() {
        if (playbackManager.partyGuestMode || _state.value.active) return
        resetSession()
        val job = SupervisorJob(scope.coroutineContext[Job])
        sessionJob = job
        _state.value = DjState(active = true, loading = true)
        fetchNeuralVoice()
        scope.launch(job) {
            val block = nextBlock(seed = null)
            if (block == null || playbackManager.partyGuestMode) {
                Log.w(TAG, "No first block to play; the DJ stays off")
                stop()
                return@launch
            }
            playbackManager.playQueue(block.tracks, 0, SOURCE_CONTEXT)
            playbackManager.autoplayProvider = provider
            _state.update { it.copy(loading = false, block = block) }
            launch { observePlayback() }
        }
    }

    /** Stops the DJ; the music keeps playing and autoplay goes back to normal. */
    fun stop() {
        if (playbackManager.autoplayProvider === provider) playbackManager.autoplayProvider = null
        val job = sessionJob
        sessionJob = null
        speakJob = null
        job?.cancel()
        voice?.let {
            voice = null
            it.stop()
            it.release()
        }
        if (job != null) playbackManager.setVoiceDuck(1f)
        resetSession()
        _state.value = DjState()
    }

    private fun resetSession() {
        blocksById.clear()
        blocksByKey.clear()
        announced.clear()
        sessionTracks.clear()
        firstBlockStart = null
        lastKind = null
        lastArtist = null
        lastMix = null
    }

    // ── Blocks ───────────────────────────────────────────────────────────────

    /** Autoplay's hook: the next block's songs, or nothing so autoplay recommends as usual. */
    private suspend fun provideBlock(seed: Track): List<Track> {
        val job = sessionJob?.takeIf { it.isActive } ?: return emptyList()
        return try {
            // The new block has to follow the last song queued, not the one playing now.
            val last = playbackManager.playbackState.value.queue.lastOrNull() ?: seed
            scope.async(job) { nextBlock(last)?.tracks.orEmpty() }.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            emptyList()
        }
    }

    /**
     * Builds and records the next block: a flow from one of the most played songs when it can,
     * otherwise the kinds in rotation until one has enough songs.
     */
    private suspend fun nextBlock(seed: Track?): DjBlock? = blockMutex.withLock {
        val library = library()
        val exclusions = exclusions(library)
        val flow = try {
            // The first block must start at once; later ones are built while music plays, with time to listen ahead.
            flowBlock(seed, library, exclusions, probe = firstBlockStart != null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not build a flow", e)
            null
        }
        if (flow != null) {
            record(flow)
            Log.i(TAG, "Next block: FLOW from '${flow.tracks.first().title}': " + flow.tracks.joinToString(" -> ") { it.title })
            return@withLock flow
        }
        for (kind in DjBlocks.kindOrder(lastKind, DjBlocks.availableKinds(library, exclusions))) {
            val block = try {
                buildBlock(kind, seed, library, exclusions)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not build a $kind block", e)
                null
            }
            if (block != null) {
                record(block)
                Log.i(TAG, "Next block: $kind (${block.subject.orEmpty()}), ${block.tracks.size} songs")
                return@withLock block
            }
        }
        Log.w(TAG, "No block available after $lastKind")
        null
    }

    private suspend fun buildBlock(
        kind: DjBlockKind,
        seed: Track?,
        library: DjLibrary,
        exclusions: DjExclusions
    ): DjBlock? = when (kind) {
        DjBlockKind.DISCOVERY -> {
            val start = seed ?: DjBlocks.seedFor(library)
            start?.let { DjBlocks.block(kind, recommend(it, library, exclusions, DISCOVERY_COUNT), exclusions, random) }
        }
        DjBlockKind.FOLLOWED_ARTIST -> artistBlock(library, exclusions)
        else -> DjBlocks.localBlock(kind, library, exclusions, random, lastMix)
    }

    /**
     * Like a DJ working a set: it opens on one of the listener's most played songs (the one that
     * follows best from [previous]) and then lets each next song grow out of the one before, asking
     * the engine what fits that song and keeping tempo, key and variety in mind. With [probe] the
     * start of the most promising candidates is analysed first, so the set stays on the beat.
     */
    private suspend fun flowBlock(
        previous: Track?,
        library: DjLibrary,
        exclusions: DjExclusions,
        probe: Boolean
    ): DjBlock? {
        val favourites = DjBlocks.usable(DjBlockKind.TOP_PLAYED, library.topPlayed + library.favorites, exclusions)
        val anchors = favourites.take(DjFlow.ANCHOR_POOL)
        if (probe && previous != null) learnGrooves(listOf(previous) + anchors.shuffled(random).take(TEMPO_PROBES))
        val anchorGrooves = grooves(listOfNotNull(previous) + anchors)
        val anchor = DjFlow.pickAnchor(favourites, previous, random) { anchorGrooves.of(it) } ?: return null
        val chain = mutableListOf(anchor)
        val size = random.nextInt(DjBlocks.MIN_TRACKS, DjBlocks.MAX_TRACKS + 1)
        while (chain.size < size) {
            val taken = exclusions.copy(sessionTracks = exclusions.sessionTracks + chain)
            val candidates = DjBlocks.usable(
                DjBlockKind.DISCOVERY,
                recommend(chain.last(), library, taken, DjFlow.CANDIDATES),
                taken
            )
            val recentArtists = chain.takeLast(3).map { it.artist }
            if (probe) learnGrooves(listOf(chain.last()) + DjFlow.probeOrder(chain.last(), candidates, recentArtists).take(TEMPO_PROBES))
            val known = grooves(listOf(chain.last()) + candidates)
            val next = DjFlow.pickNext(chain.last(), candidates, recentArtists, random) { known.of(it) } ?: break
            chain += next
        }
        if (chain.size < DjBlocks.MIN_TRACKS) return null
        val sounds = grooves(chain)
        Log.i(TAG, "Flow tempos: " + chain.joinToString(" -> ") { track ->
            sounds.of(track).let { g -> "${g.bpm?.let { "%.0f".format(it) } ?: "?"} ${g.key?.camelot.orEmpty()}".trim() }
        })
        return DjBlock(DjBlockKind.FLOW, chain)
    }

    /** Tempo and key of each of [tracks] as far as Automix has analysed them; read from disk, so off the main thread. */
    private suspend fun grooves(tracks: List<Track>): Map<String, DjFlow.Groove> = withContext(Dispatchers.IO) {
        tracks.associate { track ->
            val head = playbackManager.mixAnalysis(track.id, AutomixAnalyzer.Part.HEAD)
            val tail = playbackManager.mixAnalysis(track.id, AutomixAnalyzer.Part.TAIL)
            track.id to DjFlow.Groove(
                bpm = (head?.grid ?: tail?.grid)?.bpm?.toFloat() ?: track.bpm,
                key = head?.key ?: tail?.key
            )
        }
    }

    private fun Map<String, DjFlow.Groove>.of(track: Track): DjFlow.Groove = this[track.id] ?: DjFlow.Groove(track.bpm)

    /**
     * Analyses the start of each of [tracks] whose tempo is not known yet, one after the other.
     * Playback's own analyses queue fairly in between, so a mix about to happen never waits long.
     */
    private suspend fun learnGrooves(tracks: List<Track>) {
        val known = grooves(tracks)
        val unreadable = withContext(Dispatchers.IO) {
            tracks.filter { playbackManager.mixUnavailable(it.id, AutomixAnalyzer.Part.HEAD) }.mapTo(HashSet()) { it.id }
        }
        for (track in tracks.distinctBy { it.id }) {
            if (known.of(track).bpm != null || track.id in unreadable) continue
            val found = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                try {
                    playbackManager.analyzeForMix(track, AutomixAnalyzer.Part.HEAD)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Could not analyse '${track.title}'", e)
                    null
                }
            }
            Log.d(TAG, "Tempo of '${track.title}': ${found?.grid?.bpm?.let { "%.1f BPM".format(it) } ?: "unknown"}")
        }
    }

    private suspend fun artistBlock(library: DjLibrary, exclusions: DjExclusions): DjBlock? {
        val artist = DjBlocks.pickArtist(library.followedArtists, exclusions, lastArtist, random) ?: return null
        val own = DjBlocks.tracksByArtist(artist.name, library.history + library.favorites + library.topPlayed)
        val top = artist.browseId?.takeIf { it.isNotBlank() }?.let { browseId ->
            withContext(Dispatchers.IO) { youTube.getYouTubeArtistTopTracks(browseId, ARTIST_TOP_TRACKS) }.getOrNull()
        }.orEmpty()
        val artistTracks = own + top
        val seed = artistTracks.firstOrNull() ?: return null
        val related = recommend(seed, library, exclusions, ARTIST_RELATED_COUNT)
        return DjBlocks.artistBlock(artist.name, artistTracks, related, exclusions, random)
    }

    /** The recommendation engine with the same taste profile normal autoplay uses. */
    private suspend fun recommend(seed: Track, library: DjLibrary, exclusions: DjExclusions, count: Int): List<Track> {
        val history = library.history.take(100)
        val dislikedIds = repository.dislikedTrackIds.value
        val dislikedArtists = repository.dislikedArtists.value
        val tasteProfile = TasteProfile.build(
            followedArtists = library.followedArtists,
            history = history,
            topPlayedTracks = repository.topPlayedTracks(15).map { it to repository.playCountOf(it.id) },
            topPlayedArtists = repository.topPlayedArtists(10),
            dislikedTrackIds = dislikedIds,
            dislikedArtists = dislikedArtists
        )
        return playbackManager.recommendationEngine.getRecommendedTracks(
            currentTrack = seed,
            count = count,
            excludedTrackIds = exclusions.sessionTracks.mapTo(HashSet()) { it.id } +
                exclusions.recentHistory.map { it.id },
            activeQueueTracks = playbackManager.playbackState.value.queue,
            historyTracks = history,
            dislikedTrackIds = dislikedIds,
            dislikedArtists = dislikedArtists,
            tasteProfile = tasteProfile
        )
    }

    private fun library(): DjLibrary = DjLibrary(
        topPlayed = repository.topPlayedTracks(DjBlocks.MAX_TRACKS * 5),
        favorites = repository.favorites.value,
        history = repository.history.value,
        followedArtists = repository.followedArtists.value,
        mixes = repository.mixes.value
    )

    /** Before the first block only the song on now is off limits; afterwards the whole queue is. */
    private fun exclusions(library: DjLibrary): DjExclusions {
        val playback = playbackManager.playbackState.value
        val queued = if (firstBlockStart == null) listOfNotNull(playback.currentTrack) else playback.queue
        return DjExclusions(
            dislikedTrackIds = repository.dislikedTrackIds.value,
            dislikedArtists = repository.dislikedArtists.value,
            sessionTracks = sessionTracks + queued,
            recentHistory = library.history.take(DjBlocks.RECENT_HISTORY)
        )
    }

    private fun record(block: DjBlock) {
        val start = block.tracks.first().id
        if (firstBlockStart == null) firstBlockStart = start
        block.tracks.forEach {
            blocksById[it.id] = block
            blocksByKey[DjBlocks.trackKey(it)] = block
        }
        sessionTracks += block.tracks
        lastKind = block.kind
        when (block.kind) {
            DjBlockKind.FOLLOWED_ARTIST -> lastArtist = block.subject
            DjBlockKind.MIX -> lastMix = block.subject
            else -> Unit
        }
    }

    /** Matches by id, or by title and artist when the player swapped the song for another source. */
    private fun blockOf(track: Track): DjBlock? = blocksById[track.id] ?: blocksByKey[DjBlocks.trackKey(track)]

    private fun isDjTrack(track: Track): Boolean = blockOf(track) != null

    // ── Playback and voice ───────────────────────────────────────────────────

    private suspend fun observePlayback() {
        playbackManager.playbackState
            .distinctUntilChanged { a, b ->
                a.currentTrack?.id == b.currentTrack?.id && a.queue === b.queue && a.sourceContext == b.sourceContext
            }
            .collect { onPlayback(it) }
    }

    private fun onPlayback(playback: PlaybackState) {
        if (playbackManager.partyGuestMode || replacedByListener(playback)) {
            Log.i(TAG, "The listener moved on from the DJ's queue; stopping")
            stop()
            return
        }
        val current = playback.currentTrack ?: return
        val block = blockOf(current) ?: return
        if (_state.value.block != block) _state.update { it.copy(block = block) }
        val start = block.tracks.first()
        val isStart = current.id == start.id || DjBlocks.trackKey(current) == DjBlocks.trackKey(start)
        if (!isStart || !announced.add(start.id)) return
        val job = sessionJob ?: return
        val previous = speakJob
        speakJob = scope.launch(job) {
            previous?.cancelAndJoin()
            announce(current.id, block, first = start.id == firstBlockStart)
        }
    }

    /**
     * True when the listener started something else. sourceContext alone is not enough: a queue started
     * without a context keeps "dj". Songs the listener queued themselves leave DJ songs in the queue.
     */
    private fun replacedByListener(playback: PlaybackState): Boolean {
        val source = playback.sourceContext
        if (source != null && source != SOURCE_CONTEXT) return true
        if (playback.queue.isEmpty()) return true
        val current = playback.currentTrack
        if (current != null && isDjTrack(current)) return false
        return playback.queue.none { isDjTrack(it) }
    }

    /** Speaks the block's line once its first song is really playing and no transition is running. */
    private suspend fun announce(trackId: String, block: DjBlock, first: Boolean) {
        val wait = if (first) FIRST_LINE_WAIT_MS else LINE_WAIT_MS
        if (withTimeoutOrNull(wait) { awaitSpeakable(trackId) } != true) return
        delay(SETTLE_MS)
        if (playbackManager.playbackState.value.currentTrack?.id != trackId) return
        val language = language()
        val line = DjScript.line(block, language, first, random)
        if (line.isBlank()) return
        _state.update { it.copy(block = block, speaking = true, lastLine = line, voiceStartedAtMs = null) }
        try {
            // The voice starts right after the music has ducked: from then on the caption follows it.
            val duck: (Float) -> Unit = { level ->
                playbackManager.setVoiceDuck(level)
                if (level <= VOICE_STARTS_BELOW && _state.value.voiceStartedAtMs == null) {
                    _state.update { it.copy(voiceStartedAtMs = SystemClock.uptimeMillis()) }
                }
            }
            if (!voiceForSession().speak(line, language, duck, preferredVoice = repository.djVoiceName.value)) {
                Log.i(TAG, "No voice available; the line is only shown")
            }
        } catch (e: CancellationException) {
            voice?.stop()
            throw e
        } finally {
            _state.update { it.copy(speaking = false, voiceStartedAtMs = null) }
        }
    }

    /** Suspends until [trackId] plays outside a transition; false as soon as another song is on. */
    private suspend fun awaitSpeakable(trackId: String): Boolean {
        while (true) {
            val playback = playbackManager.playbackState.value
            if (playback.currentTrack?.id != trackId) return false
            if (playback.isPlaying && !playbackManager.isTransitioning) return true
            delay(POLL_MS)
        }
    }

    private fun voiceForSession(): DjVoice = voice ?: DjVoice(context).also { voice = it }

    /**
     * The first time the DJ starts on Wi-Fi it fetches its natural voice for the language; until it
     * is in, the phone's voice speaks. Skipped when the listener chose a phone voice.
     */
    private fun fetchNeuralVoice() {
        val picked = repository.djVoiceName.value
        if (picked != null && !picked.startsWith(PiperCatalog.PREFIX)) return
        val store = PiperStore.getInstance(context)
        val lang = language()
        if (store.installed(lang).isNotEmpty() || !NetworkMonitor.isWifiOrEthernet.value) return
        PiperCatalog.default(lang)?.let { store.download(it.id) }
    }

    /** The language the DJ speaks: the app's, or the phone's when the app follows the system. */
    fun language(): String {
        val tag = AppLanguage.preferredTag(context)
        val language = if (tag == AppLanguage.SYSTEM) Locale.getDefault().language else tag
        return language.takeIf { it in DjScript.LANGUAGES } ?: AppLanguage.ENGLISH
    }
}
