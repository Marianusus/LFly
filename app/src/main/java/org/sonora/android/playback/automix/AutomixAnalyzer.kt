package org.sonora.android.playback.automix

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.sonora.android.playback.AudioCacheManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds the beats and phrases where songs meet: the last minute of the playing song and the first
 * minute of the next. One analysis runs at a time, in the background, and each result is kept
 * (the audio itself is dropped as soon as it is measured).
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
    }

    /** A finished analysis; [grid] (and with it [energy]) is null when the song has no trackable beat there. */
    private class Result(val grid: BeatGrid?, val loudness: Double?, val energy: EnergyProfile?)

    private val tracker by lazy { BeatTracker(context) }
    private val results = LruCache<String, Result>(MAX_RESULTS)
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val oneAtATime = Mutex()

    /** The grid, once analysed. */
    fun grid(trackId: String, part: Part): BeatGrid? = results.get(key(trackId, part))?.grid

    /** Bar-by-bar energy of the analysed stretch, once analysed and when it had a beat grid. */
    fun energy(trackId: String, part: Part): EnergyProfile? = results.get(key(trackId, part))?.energy

    /** Loudness of the analysed stretch in LUFS, once analysed and when it is not silence. */
    fun loudness(trackId: String, part: Part): Double? = results.get(key(trackId, part))?.loudness

    fun isDone(trackId: String, part: Part): Boolean = results.get(key(trackId, part)) != null

    /** Starts analysing unless it is done or under way. [durationMs] is needed for the tail. */
    fun request(trackId: String, source: String, part: Part, durationMs: Long) {
        val key = key(trackId, part)
        if (results.get(key) != null || !pending.add(key)) return
        scope.launch(Dispatchers.Default) {
            try {
                oneAtATime.withLock {
                    val started = SystemClock.elapsedRealtime()
                    val start = if (part == Part.TAIL) {
                        (durationMs / 1_000.0 - WINDOW_SECONDS).coerceAtLeast(0.0)
                    } else {
                        0.0
                    }
                    val window = runCatching {
                        AudioWindowDecoder.decode(
                            source = source,
                            startSeconds = start,
                            durationSeconds = WINDOW_SECONDS,
                            remote = AudioCacheManager.getCacheDataSourceFactory(context)
                        )
                    }.onFailure { Log.w(TAG, "Decoding failed for $trackId $part", it) }.getOrNull()
                    val grid = window?.let {
                        runCatching { tracker.track(it) }
                            .onFailure { e -> Log.w(TAG, "Analysis failed for $trackId $part", e) }
                            .getOrNull()
                    }
                    val loudness = window?.let { Loudness.lufs(it.samples, LogMelSpectrogram.SAMPLE_RATE) }
                    val energy = if (window != null && grid != null) {
                        runCatching {
                            EnergyProfile.compute(window.samples, LogMelSpectrogram.SAMPLE_RATE, window.startSeconds, grid)
                        }.onFailure { Log.w(TAG, "Energy failed for $trackId $part", it) }.getOrNull()
                    } else {
                        null
                    }
                    results.put(key, Result(grid, loudness, energy))
                    Log.i(
                        TAG,
                        "$trackId $part: " + (grid?.let {
                            "%.1f BPM, %d beats, %d downbeats, confidence %.2f".format(
                                it.bpm, it.beats.size, it.downbeats.size, it.confidence
                            )
                        } ?: "no grid") + ", loudness " + (loudness?.let { "%.1f LUFS".format(it) } ?: "n/a") +
                            ", energy " + (energy?.let { describe(it) } ?: "n/a") + " in ${SystemClock.elapsedRealtime() - started} ms"
                    )
                }
            } finally {
                pending.remove(key)
            }
        }
    }

    private fun key(trackId: String, part: Part) = "$trackId|$part"

    private fun describe(energy: EnergyProfile): String =
        "%d bars%s, outro %s, rise %s".format(
            energy.barStarts.size,
            if (energy.isCalm) " calm" else "",
            energy.outroStart?.let { "%.1f s".format(it) } ?: "none",
            energy.firstRise?.let { "%.1f s".format(it) } ?: "none"
        )
}
