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
 * Finds the beats where songs meet: the last half minute of the playing song and the first half
 * minute of the next. One analysis runs at a time, in the background, and each result is kept.
 */
@OptIn(UnstableApi::class)
internal class AutomixAnalyzer(private val context: Context, private val scope: CoroutineScope) {

    enum class Part { HEAD, TAIL }

    private companion object {
        const val TAG = "AutomixAnalyzer"

        /** Exactly one model chunk of audio. */
        const val WINDOW_SECONDS = 29.5
        const val MAX_RESULTS = 64
    }

    /** A finished analysis; [grid] is null when the song has no trackable beat there. */
    private class Result(val grid: BeatGrid?)

    private val tracker by lazy { BeatTracker(context) }
    private val results = LruCache<String, Result>(MAX_RESULTS)
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val oneAtATime = Mutex()

    /** The grid, once analysed. */
    fun grid(trackId: String, part: Part): BeatGrid? = results.get(key(trackId, part))?.grid

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
                    val grid = runCatching {
                        AudioWindowDecoder.decode(
                            source = source,
                            startSeconds = start,
                            durationSeconds = WINDOW_SECONDS,
                            remote = AudioCacheManager.getCacheDataSourceFactory(context)
                        )?.let(tracker::track)
                    }.onFailure { Log.w(TAG, "Analysis failed for $trackId $part", it) }.getOrNull()
                    results.put(key, Result(grid))
                    Log.i(
                        TAG,
                        "$trackId $part: " + (grid?.let {
                            "%.1f BPM, %d beats, %d downbeats, confidence %.2f".format(
                                it.bpm, it.beats.size, it.downbeats.size, it.confidence
                            )
                        } ?: "no grid") + " in ${SystemClock.elapsedRealtime() - started} ms"
                    )
                }
            } finally {
                pending.remove(key)
            }
        }
    }

    private fun key(trackId: String, part: Part) = "$trackId|$part"
}
