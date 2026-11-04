package org.sonora.android.playback.automix

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.sonora.android.data.NetworkMonitor
import org.sonora.android.playback.AudioCacheManager
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Everything one analysis found about a stretch of a song. */
internal class MixAnalysis(
    /** Null when the song has no trackable beat there. */
    val grid: BeatGrid?,
    val loudness: Double?,
    val energy: EnergyProfile?,
    val waveform: Waveform? = null,
    val key: MusicalKey? = null
)

/**
 * Finds the beats and phrases where songs meet: the last minute of the playing song and the first
 * minute of the next. One analysis runs at a time, in the background, and each result is kept in
 * memory and on disk (the audio itself is dropped as soon as it is measured), so a mixed playlist
 * shows its tempos and keys again after a restart.
 */
@OptIn(UnstableApi::class)
internal class AutomixAnalyzer(private val context: Context, private val scope: CoroutineScope) {

    enum class Part { HEAD, TAIL }

    private companion object {
        const val TAG = "AutomixAnalyzer"

        /**
         * About two model chunks of audio: long enough to hold a whole outro or intro and the section
         * next to it, which the energy profile needs to tell them apart. 1.3 M samples at the model rate.
         */
        const val WINDOW_SECONDS = 59.0
        const val MAX_RESULTS = 64

        /** Saved analyses kept on disk; the oldest go first. */
        const val MAX_SAVED = 800
        const val SAVED_VERSION = 1

        /**
         * A song end that could not be read (no full stream, or nothing decoded) is not tried again
         * for this long, so a mixed playlist does not stall on the same songs every time it opens.
         */
        const val MISS_RETRY_MS = 3L * 24 * 60 * 60 * 1_000
        const val MISSES_FILE = "automix_misses.json"
    }

    private val tracker by lazy { BeatTracker(context) }
    private val results = LruCache<String, MixAnalysis>(MAX_RESULTS)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<MixAnalysis?>>()
    private val oneAtATime = Mutex()
    private val gson = Gson()
    private val savedDir: File by lazy { File(context.filesDir, "automix").apply { mkdirs() } }

    /** When each unreadable song end last failed, by [key]; on disk next to the analyses. */
    private val misses: MutableMap<String, Long> by lazy { loadMisses() }

    /** The grid, once analysed. */
    fun grid(trackId: String, part: Part): BeatGrid? = analysis(trackId, part)?.grid

    /** Bar-by-bar energy of the analysed stretch, once analysed and when it had a beat grid. */
    fun energy(trackId: String, part: Part): EnergyProfile? = analysis(trackId, part)?.energy

    /** Loudness of the analysed stretch in LUFS, once analysed and when it is not silence. */
    fun loudness(trackId: String, part: Part): Double? = analysis(trackId, part)?.loudness

    fun isDone(trackId: String, part: Part): Boolean = analysis(trackId, part) != null

    /** True when this song end could not be read lately: it is skipped until [MISS_RETRY_MS] has passed. */
    fun isUnavailable(trackId: String, part: Part): Boolean {
        val failedAt = synchronized(misses) { misses[key(trackId, part)] } ?: return false
        return System.currentTimeMillis() - failedAt < MISS_RETRY_MS
    }

    /**
     * Remembers that this song end could not be read. Offline nothing can be read, so nothing is
     * remembered then: the songs are tried again as soon as there is a network.
     */
    fun markUnavailable(trackId: String, part: Part) {
        if (!NetworkMonitor.isOnline.value) return
        synchronized(misses) {
            misses[key(trackId, part)] = System.currentTimeMillis()
            saveMisses()
        }
    }

    /** Everything found about this stretch, from memory or from disk; null when it was never analysed. */
    fun analysis(trackId: String, part: Part): MixAnalysis? {
        val key = key(trackId, part)
        results.get(key)?.let { return it }
        val saved = load(key) ?: return null
        results.put(key, saved)
        return saved
    }

    /** Starts analysing unless it is done or under way. [durationMs] is needed for the tail. */
    fun request(trackId: String, source: String, part: Part, durationMs: Long) {
        if (analysis(trackId, part) != null || pending.containsKey(key(trackId, part))) return
        scope.launch(Dispatchers.Default) { analyze(trackId, source, part, durationMs) }
    }

    /** Analyses this stretch, or waits for an analysis already under way; null when it could not be decoded. */
    suspend fun analyze(trackId: String, source: String, part: Part, durationMs: Long): MixAnalysis? {
        val key = key(trackId, part)
        analysis(trackId, part)?.let { return it }
        val fresh = CompletableDeferred<MixAnalysis?>()
        val running = pending.putIfAbsent(key, fresh)
        if (running != null) return running.await()
        try {
            // Decoding reads the network and the model takes seconds: never on the caller's thread.
            val result = withContext(Dispatchers.Default) { oneAtATime.withLock { run(trackId, source, part, durationMs) } }
            fresh.complete(result)
            return result
        } catch (e: Throwable) {
            fresh.complete(null)
            throw e
        } finally {
            pending.remove(key)
        }
    }

    private fun run(trackId: String, source: String, part: Part, durationMs: Long): MixAnalysis? {
        val key = key(trackId, part)
        results.get(key)?.let { return it }
        val started = SystemClock.elapsedRealtime()
        val start = if (part == Part.TAIL) (durationMs / 1_000.0 - WINDOW_SECONDS).coerceAtLeast(0.0) else 0.0
        val window = runCatching {
            AudioWindowDecoder.decode(
                source = source,
                startSeconds = start,
                durationSeconds = WINDOW_SECONDS,
                remote = AudioCacheManager.getCacheDataSourceFactory(context)
            )
        }.onFailure { Log.w(TAG, "Decoding failed for $trackId $part", it) }.getOrNull()
        if (window == null) {
            Log.w(TAG, "$trackId $part: nothing decoded in ${SystemClock.elapsedRealtime() - started} ms")
            // Remembered in memory, so playback does not retry every tick, and on disk for a while.
            markUnavailable(trackId, part)
            return MixAnalysis(null, null, null).also { results.put(key, it) }
        }
        val decodedMs = SystemClock.elapsedRealtime() - started
        val rate = LogMelSpectrogram.SAMPLE_RATE
        val grid = runCatching { tracker.track(window) }
            .onFailure { e -> Log.w(TAG, "Analysis failed for $trackId $part", e) }
            .getOrNull()
        val loudness = Loudness.lufs(window.samples, rate)
        val energy = grid?.let {
            runCatching { EnergyProfile.compute(window.samples, rate, window.startSeconds, it) }
                .onFailure { e -> Log.w(TAG, "Energy failed for $trackId $part", e) }
                .getOrNull()
        }
        val waveform = runCatching { Waveform.compute(window.samples, rate, window.startSeconds) }.getOrNull()
        val musicalKey = runCatching { KeyDetector.detect(window.samples, rate) }.getOrNull()
        val result = MixAnalysis(grid, loudness, energy, waveform, musicalKey)
        results.put(key, result)
        save(key, result)
        synchronized(misses) { if (misses.remove(key) != null) saveMisses() }
        Log.i(
            TAG,
            "$trackId $part: " + (grid?.let {
                "%.1f BPM, %d beats, %d downbeats, confidence %.2f".format(it.bpm, it.beats.size, it.downbeats.size, it.confidence)
            } ?: "no grid") + ", key " + (musicalKey?.camelot ?: "n/a") +
                ", loudness " + (loudness?.let { "%.1f LUFS".format(it) } ?: "n/a") +
                ", energy " + (energy?.let { describe(it) } ?: "n/a") + " in ${SystemClock.elapsedRealtime() - started} ms" +
                " (reading and decoding $decodedMs ms, ${window.requests} requests, ${sourceKind(source)})"
        )
        return result
    }

    private fun key(trackId: String, part: Part) = "$trackId|$part"

    /** Where the audio came from, for the log: never the address itself, which can carry tokens. */
    private fun sourceKind(source: String): String = when {
        source.startsWith("/") || source.startsWith("file:") -> "local file"
        else -> runCatching { android.net.Uri.parse(source).host }.getOrNull() ?: "stream"
    }

    private fun describe(energy: EnergyProfile): String =
        "%d bars%s, outro %s, rise %s".format(
            energy.barStarts.size,
            if (energy.isCalm) " calm" else "",
            energy.outroStart?.let { "%.1f s".format(it) } ?: "none",
            energy.firstRise?.let { "%.1f s".format(it) } ?: "none"
        )

    // ── On disk ─────────────────────────────────────────────────────────────

    /** The saved form: plain arrays and numbers, so Gson needs no adapters. */
    private class Saved(
        val version: Int = SAVED_VERSION,
        val beats: DoubleArray? = null,
        val downbeats: DoubleArray? = null,
        val bpm: Double = 0.0,
        val confidence: Double = 0.0,
        val loudness: Double? = null,
        val barStarts: DoubleArray? = null,
        val barEnergyDb: DoubleArray? = null,
        val isCalm: Boolean = false,
        val outroStart: Double? = null,
        val firstRise: Double? = null,
        val waveStart: Double = 0.0,
        val waveRate: Double = 0.0,
        val wave: FloatArray? = null,
        val keyTonic: Int = -1,
        val keyMinor: Boolean = false
    )

    private fun fileFor(key: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return File(savedDir, digest.joinToString("") { "%02x".format(it) } + ".json")
    }

    private fun save(key: String, analysis: MixAnalysis) {
        runCatching {
            val grid = analysis.grid
            val energy = analysis.energy
            val wave = analysis.waveform
            val saved = Saved(
                beats = grid?.beats,
                downbeats = grid?.downbeats,
                bpm = grid?.bpm ?: 0.0,
                confidence = grid?.confidence ?: 0.0,
                loudness = analysis.loudness,
                barStarts = energy?.barStarts,
                barEnergyDb = energy?.barEnergyDb,
                isCalm = energy?.isCalm ?: false,
                outroStart = energy?.outroStart,
                firstRise = energy?.firstRise,
                waveStart = wave?.startSeconds ?: 0.0,
                waveRate = wave?.rate ?: 0.0,
                // Two decimals are plenty for a drawing and keep the file small.
                wave = wave?.levels?.map { Math.round(it * 100) / 100f }?.toFloatArray(),
                keyTonic = analysis.key?.tonic ?: -1,
                keyMinor = analysis.key?.minor ?: false
            )
            fileFor(key).writeText(gson.toJson(saved))
            trimSaved()
        }.onFailure { Log.w(TAG, "Could not save the analysis of $key", it) }
    }

    private fun load(key: String): MixAnalysis? {
        val file = fileFor(key)
        if (!file.isFile) return null
        return runCatching {
            val saved = gson.fromJson(file.readText(), Saved::class.java) ?: return null
            if (saved.version != SAVED_VERSION) return null
            val beats = saved.beats
            val grid = if (beats != null && beats.isNotEmpty() && saved.bpm > 0) {
                BeatGrid(beats, saved.downbeats ?: DoubleArray(0), saved.bpm, saved.confidence)
            } else {
                null
            }
            val energy = if (saved.barStarts != null && saved.barEnergyDb != null) {
                EnergyProfile(saved.barStarts, saved.barEnergyDb, saved.isCalm, saved.outroStart, saved.firstRise)
            } else {
                null
            }
            val wave = saved.wave?.takeIf { it.isNotEmpty() && saved.waveRate > 0 }
                ?.let { Waveform(saved.waveStart, saved.waveRate, it) }
            val key = saved.keyTonic.takeIf { it in 0..11 }?.let { MusicalKey(it, saved.keyMinor) }
            file.setLastModified(System.currentTimeMillis())
            MixAnalysis(grid, saved.loudness, energy, wave, key)
        }.onFailure { Log.w(TAG, "Dropping an unreadable analysis of $key", it); file.delete() }.getOrNull()
    }

    private fun loadMisses(): MutableMap<String, Long> = runCatching {
        val file = File(context.filesDir, MISSES_FILE)
        if (!file.isFile) return@runCatching HashMap<String, Long>()
        val type = object : TypeToken<HashMap<String, Long>>() {}.type
        val saved: HashMap<String, Long>? = gson.fromJson(file.readText(), type)
        val now = System.currentTimeMillis()
        HashMap(saved.orEmpty().filterValues { now - it < MISS_RETRY_MS })
    }.onFailure { Log.w(TAG, "Dropping unreadable misses", it) }.getOrDefault(HashMap())

    /** Called holding the lock on [misses]: the file is tiny, and writes never interleave. */
    private fun saveMisses() {
        runCatching { File(context.filesDir, MISSES_FILE).writeText(gson.toJson(misses)) }
            .onFailure { Log.w(TAG, "Could not save the misses", it) }
    }

    private fun trimSaved() {
        val files = savedDir.listFiles() ?: return
        if (files.size <= MAX_SAVED) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_SAVED).forEach { it.delete() }
    }
}
