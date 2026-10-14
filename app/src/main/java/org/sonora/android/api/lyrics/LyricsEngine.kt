package org.sonora.android.api.lyrics

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.sonora.android.api.lyrics.providers.KugouProvider
import org.sonora.android.api.lyrics.providers.LrcLibProvider
import org.sonora.android.api.lyrics.providers.MusixmatchProvider

/**
 * Orchestrates lyrics lookups across providers — port of the essential flow of
 * `crates/state/src/lyrics.rs`: gather every enabled provider concurrently, rank the hits,
 * reshape word-level answers against a line-synced guide, and detect instrumentals.
 */
object LyricsEngine {

    private const val TIMEOUT_MS = 15_000L

    val musixmatch: LyricsProvider = MusixmatchProvider()
    val kugou: LyricsProvider = KugouProvider()
    val lrclib: LyricsProvider = LrcLibProvider()

    /** All providers in priority order (word-level sources first). */
    val allProviders: List<LyricsProvider> = listOf(musixmatch, kugou, lrclib)

    data class Result(val hits: List<LyricsHit>, val instrumental: Boolean) {
        val best: LyricsHit? get() = hits.firstOrNull()
        val isEmpty: Boolean get() = hits.isEmpty()
    }

    /** Looks a track up across the enabled [providers]. */
    suspend fun resolve(query: LyricsQuery, providers: List<LyricsProvider>): Result {
        if (providers.isEmpty()) return Result(emptyList(), false)

        val gathered: List<LyricsHit> = coroutineScope {
            val jobs = providers.map { provider ->
                async {
                    runCatching { provider.search(query) }.getOrDefault(emptyList())
                }
            }
            withTimeoutOrNull(TIMEOUT_MS) { jobs.awaitAll() }?.flatten() ?: emptyList()
        }

        val ranked = LyricsRank.rank(query, gathered).toMutableList()
        LyricsRank.reshape(ranked)
        val instrumental = LyricsRank.instrumental(query, gathered)
        return Result(ranked, instrumental)
    }
}
