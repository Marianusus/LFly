package org.sonora.android.api.lyrics.sources

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import org.sonora.android.api.HttpClientProvider

/**
 * Every lyrics database Sonora can ask, in default priority order.
 *
 * Ported from BitChord (GPLv3):
 * https://github.com/kushagrasinghx/BitChord
 */
enum class LyricsSource(
    val label: String,
    val detail: String,
    val wordSynced: Boolean,
    val hidden: Boolean = false,
    val requiresKey: Boolean = false,
) {
    LRC_RED(
        "lrc.red",
        "Apple timings by recording, syllable by syllable",
        wordSynced = true,
    ),
    BINI_LYRICS(
        "BiniLyrics",
        "Apple timings matched on the recording itself",
        wordSynced = true,
    ),
    BETTER_LYRICS(
        "BetterLyrics",
        "Apple Music timings, word by word",
        wordSynced = true,
    ),
    BETTER_LYRICS_PORTATO(
        "BetterLyrics Portato",
        "QQ Music karaoke timings through BetterLyrics",
        wordSynced = true,
    ),
    PAXSENIX(
        "PaxSenix",
        "Apple Music timings through the keyless provider",
        wordSynced = true,
    ),
    PAXSENIX_SPOTIFY(
        "PaxSenix: Spotify",
        "Spotify lyrics with PaxSenix fallback; API key required",
        wordSynced = false,
        requiresKey = true,
    ),
    PAXSENIX_MUSIXMATCH(
        "PaxSenix: Musixmatch",
        "Musixmatch timings with PaxSenix fallback; API key required",
        wordSynced = true,
        requiresKey = true,
    ),
    LYRICS_PLUS(
        "LyricsPlus",
        "Syllable by syllable, on community mirrors",
        wordSynced = true,
    ),
    SIMP_MUSIC(
        "SimpMusic",
        "Matched on the video, so never the wrong edit",
        wordSynced = true,
        hidden = true,
    ),
    UNISON(
        "Unison",
        "Contributed by listeners, so it has what nobody licensed",
        wordSynced = true,
    ),
    YOUTUBE_TRANSCRIPT(
        "YouTube captions",
        "Timed captions matched to the exact playing video",
        wordSynced = false,
    ),
    YOUTUBE_MUSIC(
        "YouTube Music",
        "Plain lyrics from the playing video's Lyrics tab",
        wordSynced = false,
    ),
    MEGALOBIZ(
        "Megalobiz",
        "Community-made, whole-line LRC",
        wordSynced = false,
        hidden = true,
    ),
    KUGOU(
        "KuGou",
        "Whole lines, strong outside the English catalogue",
        wordSynced = false,
    ),
    LRCLIB(
        "LRCLIB",
        "Whole lines only, and always up",
        wordSynced = false,
    ),
    MUSIXMATCH(
        "Musixmatch",
        "Whole lines, from the biggest lyrics database",
        wordSynced = false,
    ),
    GENIUS(
        "Genius",
        "Plain text fallback, massive web catalogue",
        wordSynced = false,
    ),
    ;

    companion object {
        val offered: List<LyricsSource> = entries.filterNot { it.hidden }

        fun fromName(value: String?): LyricsSource? =
            entries.firstOrNull { it.name == value }

        fun ordered(saved: List<LyricsSource>): List<LyricsSource> {
            val filtered = saved.filter { !it.hidden }.distinct().toMutableList()
            offered.forEachIndexed { index, source ->
                if (source in filtered) return@forEachIndexed
                val previous = offered.subList(0, index).lastOrNull { it in filtered }
                filtered.add(previous?.let { filtered.indexOf(it) + 1 } ?: 0, source)
            }
            return filtered
        }
    }
}

enum class LyricsProviderState {
    NOT_FETCHED,
    FETCHING,
    FOUND,
    NOT_FOUND,
    REQUIRES_KEY,
    SKIPPED,
}

data class SourceQuery(
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val videoId: String? = null,
    val isrc: String? = null,
    val apiKey: String? = null,
    val documents: LyricsDocuments? = null,
)

/** One document download shared by every source that wants the same URL. */
class LyricsDocuments(private val scope: CoroutineScope) {
    private val downloads = HashMap<String, Deferred<String?>>()

    suspend fun get(url: String): String? {
        val job = synchronized(downloads) {
            downloads.getOrPut(url) {
                scope.async {
                    HttpClientProvider.getJson(
                        url,
                        mapOf("Accept" to "application/json, text/plain, */*"),
                        HttpClientProvider.fastClient,
                    ).getOrNull()
                }
            }
        }
        return runCatching { job.await() }.getOrNull()
    }

    suspend fun document(url: String): String? = get(url)

    fun cancel() {
        synchronized(downloads) {
            downloads.values.forEach { it.cancel() }
            downloads.clear()
        }
    }

    companion object {
        fun none(): LyricsDocuments? = null
    }
}

private val ISRC_PATTERN = Regex("[A-Z]{2}[A-Z0-9]{3}\\d{7}")

fun String.normalizedIsrc(): String? {
    val compact = filter { it.isLetterOrDigit() }.uppercase()
    return compact.takeIf { ISRC_PATTERN.matches(it) }
}
