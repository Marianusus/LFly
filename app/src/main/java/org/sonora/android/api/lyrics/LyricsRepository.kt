package org.sonora.android.api.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.sonora.android.api.lyrics.sources.LyricsDocuments
import org.sonora.android.api.lyrics.sources.LyricsFetchers
import org.sonora.android.api.lyrics.sources.LyricsProviderState
import org.sonora.android.api.lyrics.sources.LyricsSource
import org.sonora.android.api.lyrics.sources.SourceBini
import org.sonora.android.api.lyrics.sources.SourceQuery

/**
 * Fetches lyrics from every enabled source, in the listener's priority order.
 *
 * Ported from BitChord (GPLv3): all sources race, answers are consumed in order,
 * a syllable-synced answer wins when requested, and Genius is only started when
 * every synced source has missed.
 */
object LyricsRepository {

    private const val IDENTIFY_TIMEOUT_MS = 2_500L
    private const val TOTAL_TIMEOUT_MS = 15_000L
    private const val ISRC_CACHE_SIZE = 100

    data class Result(
        val source: LyricsSource,
        val hit: LyricsHit,
    )

    private val isrcCache = object : LinkedHashMap<String, SourceBini.Hit>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SourceBini.Hit>?): Boolean =
            size > ISRC_CACHE_SIZE
    }

    suspend fun lookup(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long,
        videoId: String? = null,
        sources: Set<LyricsSource> = LyricsSource.offered.toSet(),
        order: List<LyricsSource> = LyricsSource.entries,
        prioritizeSyllableSync: Boolean = false,
        apiKey: String? = null,
        isrc: String? = null,
        onState: ((LyricsSource, LyricsProviderState) -> Unit)? = null,
        fetcher: suspend (LyricsSource, SourceQuery, SourceBini.Hit?) -> LyricsHit? =
            { source, query, bini -> LyricsFetchers.fetch(source, query, bini) },
    ): Result? {
        val sequence = LyricsSource.ordered(order)
            .filter { it in sources && !it.hidden }
        if (sequence.isEmpty()) return null

        val cleanTitle = LyricsText.cleanTitle(title).ifBlank { title }
        val cleanArtist = LyricsText.cleanArtist(artist).ifBlank { artist }
        val known = isrc?.takeIf { it.isNotBlank() }
        val cached = videoId?.let { synchronized(isrcCache) { isrcCache[it] } }

        var identity: SourceBini.Hit? = cached
        if (identity == null && LyricsSource.BINI_LYRICS in sequence) {
            val seed = SourceQuery(
                title = cleanTitle,
                artist = cleanArtist,
                album = album,
                durationMs = durationMs,
                videoId = videoId,
                isrc = known,
                apiKey = apiKey,
            )
            identity = withTimeoutOrNull(IDENTIFY_TIMEOUT_MS) {
                runCatching { SourceBini.identify(seed) }.getOrNull()
            }
            if (identity != null && videoId != null) {
                identity?.let { matched ->
                    synchronized(isrcCache) { isrcCache[videoId] = matched }
                }
            }
        }

        val recording = identity?.isrc?.takeIf { it.isNotBlank() } ?: known
        return coroutineScope {
            val documents = LyricsDocuments(this)
            val query = SourceQuery(
                title = cleanTitle,
                artist = cleanArtist,
                album = album,
                durationMs = durationMs,
                videoId = videoId,
                isrc = recording,
                apiKey = apiKey,
                documents = documents,
            )

            val rankQuery = LyricsQuery(
                title = cleanTitle,
                artist = cleanArtist,
                album = album,
                durationMs = durationMs,
            )
            val racing = sequence.filter { it != LyricsSource.GENIUS }
            val jobs: List<Pair<LyricsSource, Deferred<Result?>>> = racing.map { source ->
                val job = async(Dispatchers.IO) {
                    onState?.invoke(source, LyricsProviderState.FETCHING)
                    if (source.requiresKey && apiKey.isNullOrBlank()) {
                        onState?.invoke(source, LyricsProviderState.REQUIRES_KEY)
                        return@async null
                    }
                    try {
                        val hit = fetcher(source, query, identity)
                            ?.takeIf {
                                source == LyricsSource.YOUTUBE_MUSIC ||
                                    source == LyricsSource.YOUTUBE_TRANSCRIPT ||
                                    LyricsRank.eligible(rankQuery, it)
                            }
                        if (hit != null) {
                            onState?.invoke(source, LyricsProviderState.FOUND)
                            Result(source, hit)
                        } else {
                            onState?.invoke(source, LyricsProviderState.NOT_FOUND)
                            null
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        onState?.invoke(source, LyricsProviderState.NOT_FOUND)
                        null
                    }
                }
                source to job
            }

            try {
                var fallback: Result? = null
                var syncedFound = false
                val winner = withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
                    for ((_, job) in jobs) {
                        val found = runCatching { job.await() }.getOrNull() ?: continue
                        val synced = found.hit.lyrics.synced()
                        if (synced) syncedFound = true
                        if (found.hit.lyrics.worded()) return@withTimeoutOrNull found
                        if (synced && !prioritizeSyllableSync) return@withTimeoutOrNull found
                        if (fallback == null) fallback = found
                    }
                    null
                }
                if (winner != null) return@coroutineScope winner
                if (LyricsSource.GENIUS in sequence && !syncedFound) {
                    onState?.invoke(LyricsSource.GENIUS, LyricsProviderState.FETCHING)
                    val found = withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
                        val hit = runCatching { fetcher(LyricsSource.GENIUS, query, identity) }
                            .getOrNull()
                            ?.takeIf { LyricsRank.eligible(rankQuery, it) }
                        if (hit != null) {
                            onState?.invoke(LyricsSource.GENIUS, LyricsProviderState.FOUND)
                            Result(LyricsSource.GENIUS, hit)
                        } else {
                            onState?.invoke(LyricsSource.GENIUS, LyricsProviderState.NOT_FOUND)
                            null
                        }
                    }
                    if (found != null) return@coroutineScope found
                } else if (LyricsSource.GENIUS in sequence) {
                    onState?.invoke(LyricsSource.GENIUS, LyricsProviderState.SKIPPED)
                }
                fallback
            } finally {
                jobs.forEach { (source, job) ->
                    if (job.isActive) {
                        job.cancel()
                        onState?.invoke(source, LyricsProviderState.SKIPPED)
                    }
                }
                documents.cancel()
            }
        }
    }

    fun clearIsrcCache() {
        synchronized(isrcCache) { isrcCache.clear() }
    }
}
