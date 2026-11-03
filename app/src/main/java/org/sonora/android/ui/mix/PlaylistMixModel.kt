package org.sonora.android.ui.mix

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.sonora.android.api.Track
import org.sonora.android.data.MusicRepository
import org.sonora.android.data.Playlist
import org.sonora.android.playback.PlaybackManager
import org.sonora.android.playback.PreviewState
import org.sonora.android.playback.TransitionPreview
import org.sonora.android.playback.automix.AutomixAnalyzer
import org.sonora.android.playback.automix.AutomixPlanner
import org.sonora.android.playback.automix.BeatGrid
import org.sonora.android.playback.automix.EnergyProfile
import org.sonora.android.playback.automix.Loudness
import org.sonora.android.playback.automix.MixOrder
import org.sonora.android.playback.automix.MixPreset
import org.sonora.android.playback.automix.MixRecipe
import org.sonora.android.playback.automix.MixTrackInfo
import org.sonora.android.playback.automix.MixTransition
import org.sonora.android.playback.automix.PlanFixes
import org.sonora.android.playback.automix.PlaylistMix
import org.sonora.android.playback.automix.TransitionCurves
import org.sonora.android.playback.automix.TransitionStyle
import org.sonora.android.playback.automix.Waveform

/** Progress of a mixed playlist's analysis: [done] of [total] song ends. */
data class MixAnalysisProgress(val playlistId: String, val done: Int, val total: Int)

/** One song of the editor: its stretch next to the transition and what is known about it. */
class EditorSide internal constructor(
    val track: Track,
    val waveform: Waveform?,
    val info: MixTrackInfo,
    internal val grid: BeatGrid?,
    internal val energy: EnergyProfile?,
    internal val loudness: Double?
)

/** What the editor needs about a transition, once both songs are analysed. */
class TransitionEditorData(
    val playlistId: String,
    val outgoing: EditorSide,
    val incoming: EditorSide,
    val saved: MixTransition
) {
    /** True when the two tempos are close enough for the beats to be matched. */
    val beatmatchPossible: Boolean
        get() {
            val a = outgoing.grid?.bpm ?: return false
            val b = incoming.grid?.bpm ?: return false
            return kotlin.math.abs(a / b - 1.0) <= AutomixPlanner.MAX_STRETCH &&
                (outgoing.grid.confidence >= AutomixPlanner.MIN_CONFIDENCE) &&
                (incoming.grid.confidence >= AutomixPlanner.MIN_CONFIDENCE)
        }
}

/** Where a transition lands with the editor's choices: what the waveforms show and the preview plays. */
data class EditorPlan(
    val exitMs: Long,
    val entryMs: Long,
    val mixMs: Long,
    val bars: Int,
    val beatMatched: Boolean,
    val incomingRate: Float,
    /** The ingredients that will play: the listener's, or what AUTO picked for these songs. */
    val recipe: MixRecipe?,
    /** What AUTO picks here, to show next to "Auto". */
    val autoPreset: MixPreset?,
    /** Bar lines the outgoing and incoming regions can be dragged to. */
    val exitChoicesMs: List<Long>,
    val entryChoicesMs: List<Long>
)

/**
 * The listener's side of Spotify-style playlist mixing: turning Mix on, analysing the songs so each
 * transition can show tempo and key, the transition editor with its preview, and Smart Reorder.
 */
class PlaylistMixModel internal constructor(
    private val scope: CoroutineScope,
    private val repository: MusicRepository,
    private val playback: PlaybackManager
) {
    private companion object {
        const val TAG = "PlaylistMix"

        /** Transitions without beat matching fall back to Spotify's length for them. */
        const val UNMATCHED_MS = 5_000L
    }

    val mixes: StateFlow<Map<String, PlaylistMix>> = repository.playlistMixes

    private val _info = MutableStateFlow<Map<String, MixTrackInfo>>(emptyMap())

    /** Tempo and key of the songs analysed so far, by track id. */
    val trackInfo: StateFlow<Map<String, MixTrackInfo>> = _info.asStateFlow()

    private val _progress = MutableStateFlow<MixAnalysisProgress?>(null)
    val progress: StateFlow<MixAnalysisProgress?> = _progress.asStateFlow()

    private val _editor = MutableStateFlow<TransitionEditorData?>(null)

    /** The transition being edited, once its songs are analysed. */
    val editor: StateFlow<TransitionEditorData?> = _editor.asStateFlow()

    private val _editorLoading = MutableStateFlow(false)
    val editorLoading: StateFlow<Boolean> = _editorLoading.asStateFlow()

    val preview: StateFlow<PreviewState?> get() = playback.previewState

    private var analysisJob: Job? = null
    private var analysisPlaylistId: String? = null

    fun isMixed(playlistId: String): Boolean = repository.playlistMix(playlistId).enabled

    fun setMixed(playlist: Playlist, enabled: Boolean) {
        repository.setPlaylistMixEnabled(playlist.id, enabled)
        if (enabled) analyze(playlist) else if (analysisPlaylistId == playlist.id) stopAnalysis()
    }

    /** Loads what is already known about [playlist]'s songs and, when it is mixed, analyses the rest. */
    fun open(playlist: Playlist) {
        refreshInfo(playlist.tracks)
        if (isMixed(playlist.id)) analyze(playlist)
    }

    fun transition(playlistId: String, from: Track, to: Track): MixTransition =
        repository.playlistMix(playlistId).transition(from.id, to.id)

    /** The ends each transition needs: every song's start but the first's, every ending but the last's. */
    fun analyze(playlist: Playlist) {
        if (analysisJob?.isActive == true && analysisPlaylistId == playlist.id) return
        stopAnalysis()
        val tracks = playlist.tracks
        if (tracks.size < 2) return
        val work = buildList {
            tracks.forEachIndexed { index, track ->
                if (index < tracks.size - 1) add(track to AutomixAnalyzer.Part.TAIL)
                if (index > 0) add(track to AutomixAnalyzer.Part.HEAD)
            }
        }
        analysisPlaylistId = playlist.id
        analysisJob = scope.launch {
            var done = 0
            for ((track, part) in work) {
                if (playback.mixAnalysis(track.id, part) == null) {
                    _progress.value = MixAnalysisProgress(playlist.id, done, work.size)
                    runCatching { playback.analyzeForMix(track, part) }
                        .onFailure { Log.w(TAG, "Analysis of '${track.title}' $part failed", it) }
                }
                done++
                refreshInfo(listOf(track))
            }
            _progress.value = null
        }
    }

    private fun stopAnalysis() {
        analysisJob?.cancel()
        analysisJob = null
        analysisPlaylistId = null
        _progress.value = null
    }

    private fun refreshInfo(tracks: List<Track>) {
        val found = tracks.associate { it.id to infoOf(it) }.filterValues { it.bpm != null || it.key != null }
        if (found.isNotEmpty()) _info.update { it + found }
    }

    private fun infoOf(track: Track): MixTrackInfo {
        val head = playback.mixAnalysis(track.id, AutomixAnalyzer.Part.HEAD)
        val tail = playback.mixAnalysis(track.id, AutomixAnalyzer.Part.TAIL)
        return MixTrackInfo(
            headBpm = head?.grid?.bpm,
            tailBpm = tail?.grid?.bpm,
            key = head?.key ?: tail?.key
        )
    }

    // ── Editor ──────────────────────────────────────────────────────────────

    /** Opens the editor on the transition after the song at [index]; it shows once both ends are analysed. */
    fun openEditor(playlist: Playlist, index: Int) {
        val from = playlist.tracks.getOrNull(index) ?: return
        val to = playlist.tracks.getOrNull(index + 1) ?: return
        _editor.value = null
        _editorLoading.value = true
        scope.launch {
            val tail = playback.mixAnalysis(from.id, AutomixAnalyzer.Part.TAIL)
                ?: runCatching { playback.analyzeForMix(from, AutomixAnalyzer.Part.TAIL) }.getOrNull()
            val head = playback.mixAnalysis(to.id, AutomixAnalyzer.Part.HEAD)
                ?: runCatching { playback.analyzeForMix(to, AutomixAnalyzer.Part.HEAD) }.getOrNull()
            refreshInfo(listOf(from, to))
            _editor.value = TransitionEditorData(
                playlistId = playlist.id,
                outgoing = EditorSide(from, tail?.waveform, infoOf(from), tail?.grid, tail?.energy, tail?.loudness),
                incoming = EditorSide(to, head?.waveform, infoOf(to), head?.grid, head?.energy, head?.loudness),
                saved = repository.playlistMix(playlist.id).transition(from.id, to.id)
            )
            _editorLoading.value = false
        }
    }

    fun closeEditor() {
        playback.stopTransitionPreview()
        _editor.value = null
        _editorLoading.value = false
    }

    fun save(data: TransitionEditorData, transition: MixTransition) {
        repository.setMixTransition(data.playlistId, data.outgoing.track.id, data.incoming.track.id, transition)
    }

    /** Where [transition] lands between the two songs, as the player will play it. */
    fun plan(data: TransitionEditorData, transition: MixTransition): EditorPlan? {
        val out = data.outgoing
        val inc = data.incoming
        val outGrid = out.grid
        val inGrid = inc.grid
        val duration = out.track.durationMs
        val barMs = outGrid?.let { 4 * it.beatSeconds * 1_000 }

        if (transition.beatmatch && data.beatmatchPossible && outGrid != null && inGrid != null) {
            val fixes = PlanFixes(transition.bars, transition.exitMs?.div(1_000.0), transition.entryMs?.div(1_000.0))
            val plan = AutomixPlanner.plan(outGrid, duration, inGrid, out.energy, inc.energy, fixes)
            if (plan != null) {
                val calm = out.energy?.isCalm == true || inc.energy?.isCalm == true
                val auto = TransitionCurves.resolve(repository.automixStyle.value, true, calm, plan.landsRise)
                val mixSeconds = plan.mixMs / 1_000.0
                return EditorPlan(
                    exitMs = plan.exitMs,
                    entryMs = plan.entryMs,
                    mixMs = plan.mixMs,
                    bars = plan.mixBars,
                    beatMatched = true,
                    incomingRate = plan.incomingRate,
                    recipe = transition.recipe ?: auto.recipe,
                    autoPreset = presetOf(auto),
                    exitChoicesMs = AutomixPlanner.exitChoices(outGrid, duration, mixSeconds).map { (it * 1_000).toLong() },
                    entryChoicesMs = AutomixPlanner.entryChoices(inGrid, plan.mixBeats).map { (it * 1_000).toLong() }
                )
            }
        }

        // Off the beat: the chosen bars at the outgoing tempo when it is known, else Spotify's five seconds.
        val bars = transition.bars ?: 4
        val mixMs = barMs?.let { (bars * it).toLong() } ?: UNMATCHED_MS
        if (duration <= mixMs) return null
        val exit = (transition.exitMs ?: (duration - mixMs)).coerceIn(0L, duration - mixMs)
        val auto = TransitionStyle.FILTER
        return EditorPlan(
            exitMs = exit,
            entryMs = transition.entryMs ?: 0L,
            mixMs = mixMs,
            bars = bars,
            beatMatched = false,
            incomingRate = 1f,
            recipe = transition.recipe?.takeIf { !it.needsBeatMatch || !transition.beatmatch } ?: auto.recipe,
            autoPreset = presetOf(auto),
            exitChoicesMs = out.waveform?.let { wave -> stepsMs(wave.startSeconds, duration / 1_000.0 - mixMs / 1_000.0, barMs) }
                ?: emptyList(),
            entryChoicesMs = inc.waveform?.let { wave -> stepsMs(0.0, wave.endSeconds - mixMs / 1_000.0, barMs) }
                ?: emptyList()
        )
    }

    /** Plays [plan] between the editor's two songs. */
    fun preview(data: TransitionEditorData, plan: EditorPlan) {
        val recipe = plan.recipe ?: return
        scope.launch {
            val outSource = playback.mixSourceFor(data.outgoing.track) ?: return@launch
            val inSource = playback.mixSourceFor(data.incoming.track) ?: return@launch
            playback.previewTransition(
                TransitionPreview.Request(
                    outgoingSource = outSource,
                    incomingSource = inSource,
                    outgoingGrid = data.outgoing.grid,
                    incomingGrid = data.incoming.grid,
                    exitMs = plan.exitMs,
                    entryMs = plan.entryMs,
                    mixMs = plan.mixMs,
                    incomingRate = plan.incomingRate,
                    recipe = recipe,
                    outgoingGainDb = Loudness.matchGainDb(data.outgoing.loudness, data.incoming.loudness),
                    beatMatched = plan.beatMatched
                )
            )
        }
    }

    fun stopPreview() = playback.stopTransitionPreview()

    // ── Smart Reorder ───────────────────────────────────────────────────────

    /** The order Smart Reorder suggests for [playlist], or null when too little is known to suggest one. */
    fun suggestOrder(playlist: Playlist): List<Track>? {
        val infos = playlist.tracks.map { track -> infoOf(track).takeIf { it.bpm != null } }
        if (infos.count { it != null } < 3) return null
        val order = MixOrder.suggest(infos)
        if (order == playlist.tracks.indices.toList()) return null
        return order.map { playlist.tracks[it] }
    }

    /** True when every song of [playlist] has been analysed at least at one end. */
    fun knowsAll(playlist: Playlist): Boolean = playlist.tracks.all { infoOf(it).bpm != null }

    fun applyOrder(playlist: Playlist, tracks: List<Track>) {
        repository.savePlaylist(playlist.copy(tracks = tracks, updatedAt = System.currentTimeMillis()))
    }

    private fun presetOf(style: TransitionStyle): MixPreset? = MixPreset.entries.firstOrNull { it.recipe == style.recipe }

    private fun stepsMs(from: Double, to: Double, barMs: Double?): List<Long> {
        val step = (barMs ?: 2_000.0) / 1_000.0
        if (to <= from) return emptyList()
        return generateSequence(from) { it + step }.takeWhile { it <= to }.map { (it * 1_000).toLong() }.toList()
    }
}
