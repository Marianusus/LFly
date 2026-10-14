package org.sonora.android.api.lyrics

import org.sonora.android.api.Track

/**
 * Lyrics engine data model, ported from `sonorahq/sonora` (`crates/music/src/lyrics`).
 *
 * A [Sheet] is either untimed [Sheet.Plain] text or a [Sheet.Synced] list of [SheetLine]s.
 * A line may carry word/syllable-level [SheetWord]s (karaoke), secondary [SheetLane]s
 * (background vocals) and a [Voice]. Timings are in milliseconds.
 *
 * Fields are mutable on purpose: the parsers/normalizers are a direct port of the Rust
 * `&mut` pipeline.
 */

enum class Voice {
    LEAD,
    COUNTER;

    fun isLead(): Boolean = this == LEAD
}

enum class WritingSystem {
    JAPANESE,
    CHINESE,
    KOREAN,
    CYRILLIC,
    GREEK,
    ARABIC,
    OTHER;

    companion object {
        val ALL: List<WritingSystem> = entries.toList()
    }
}

data class RomanizedText(
    val text: String,
    val writingSystem: WritingSystem
)

/** A single timed token inside a line (word-level / syllable-level timing). */
data class SheetWord(
    var startMs: Long,
    var endMs: Long,
    var text: String
)

/** An independently timed secondary line (background vocals / echoes). */
data class SheetLane(
    var startMs: Long,
    var endMs: Long?,
    var text: String,
    var romanized: RomanizedText? = null,
    var words: MutableList<SheetWord>? = null
) {
    fun sungEnd(): Long? = words?.lastOrNull()?.endMs ?: endMs
}

/** A single line of a lyrics sheet. */
data class SheetLine(
    var startMs: Long,
    var endMs: Long?,
    var text: String,
    var romanized: RomanizedText? = null,
    var words: MutableList<SheetWord>? = null,
    var secondary: MutableList<SheetLane> = mutableListOf(),
    var voice: Voice = Voice.LEAD
) {
    fun worded(): Boolean = !words.isNullOrEmpty()

    fun sungEnd(): Long? = words?.lastOrNull()?.endMs ?: endMs
}

sealed interface Sheet {
    fun isEmpty(): Boolean
    fun synced(): Boolean
    fun worded(): Boolean

    /** The time span covered by a synced sheet, `null` for plain/empty. */
    fun spanMs(): Long?

    data class Plain(val text: String) : Sheet {
        override fun isEmpty(): Boolean = text.isBlank()
        override fun synced(): Boolean = false
        override fun worded(): Boolean = false
        override fun spanMs(): Long? = null
    }

    data class Synced(val lines: MutableList<SheetLine>) : Sheet {
        override fun isEmpty(): Boolean = lines.isEmpty()
        override fun synced(): Boolean = true
        override fun worded(): Boolean = lines.any { it.worded() }
        override fun spanMs(): Long? {
            val first = lines.firstOrNull() ?: return null
            val last = lines.lastOrNull() ?: return null
            val end = last.endMs ?: last.startMs
            return (end - first.startMs).coerceAtLeast(0L)
        }
    }
}

/** A candidate answer from a provider, plus the metadata used to rank it. */
data class LyricsHit(
    val source: String,
    val trust: Int,
    val lyrics: Sheet,
    val instrumental: Boolean,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long?,
    val writers: List<String> = emptyList()
)

/** Identifies a track in a provider's own catalogue (used for provider-specific lookups). */
data class TrackKey(
    val provider: String,
    val id: String
)

/** What a provider is asked for. */
data class LyricsQuery(
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val track: TrackKey? = null
)

/** A lyrics source (LRCLIB, Musixmatch, ...). */
interface LyricsProvider {
    val name: String

    /** Returns every plausible hit for [query]; an empty list means "nothing found". */
    suspend fun search(query: LyricsQuery): List<LyricsHit>
}

/** Builds a [LyricsQuery] from a [Track], normalizing video-style titles/artists first. */
fun trackLyricsQuery(track: Track, key: TrackKey? = null): LyricsQuery {
    val (title, artist) = LyricsText.splitArtistTitle(track.title, track.artist)
    return LyricsQuery(
        title = title,
        artist = artist,
        album = track.album?.takeIf { it.isNotBlank() },
        durationMs = track.durationMs,
        track = key,
    )
}
