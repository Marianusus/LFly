package org.sonora.android.api.lyrics

import org.sonora.android.api.LyricLine
import org.sonora.android.api.Lyrics
import org.sonora.android.api.LyricsTiming
import org.sonora.android.api.WordTiming

/**
 * Adapts the engine [Sheet] model to the app's existing [Lyrics]/[LyricLine] model so the
 * current UI keeps working unchanged. Engine word tokens are re-aligned to the UI's
 * whitespace-token model ([LyricsTiming.splitWords]); a token that spans several engine words
 * (sub-word karaoke) gets the combined span.
 */
object LyricsBridge {

    fun toDomain(sheet: Sheet, id: String): Lyrics = when (sheet) {
        is Sheet.Plain -> Lyrics(
            id = id,
            plainLyrics = sheet.text,
            syncedLyrics = null,
            lines = emptyList(),
        )

        is Sheet.Synced -> Lyrics(
            id = id,
            plainLyrics = null,
            syncedLyrics = null,
            lines = sheet.lines.map { line ->
                LyricLine(
                    timeMs = line.startMs,
                    text = line.text,
                    wordTimings = alignWords(line.text, line.words),
                    background = line.secondary.firstOrNull()?.text?.takeIf { it.isNotBlank() },
                )
            },
        )
    }

    /** Re-aligns engine words to [LyricsTiming.splitWords] tokens; `null` when not mappable. */
    internal fun alignWords(lineText: String, words: List<SheetWord>?): List<WordTiming>? {
        if (words.isNullOrEmpty()) return null
        val tokens = LyricsTiming.splitWords(lineText)
        if (tokens.isEmpty()) return null

        val out = ArrayList<WordTiming>(tokens.size)
        var wordIndex = 0
        for (token in tokens) {
            val target = token.filterNot { it.isWhitespace() }
            if (target.isEmpty()) continue
            val buffer = StringBuilder()
            var startMs = -1L
            var endMs = -1L
            // NOTE: `filterNot` on a StringBuilder returns another StringBuilder, whose
            // `equals` is reference identity — comparing it to a String is always "not equal".
            // Route through `toString()` so the comparison is structural (this bug made every
            // word token consume the whole line and the karaoke fall back to line-level).
            while (buffer.toString().filterNot { it.isWhitespace() } != target && wordIndex < words.size) {
                val word = words[wordIndex++]
                // A leading whitespace-only token (Musixmatch splits spaces) starts a bit
                // before the real word; don't let it seed this token's start time.
                if (startMs < 0L && word.text.any { !it.isWhitespace() }) startMs = word.startMs
                endMs = word.endMs
                buffer.append(word.text)
            }
            if (buffer.toString().filterNot { it.isWhitespace() } != target) return null
            out.add(WordTiming(text = token, startMs = startMs, endMs = endMs.coerceAtLeast(startMs + 1)))
        }
        return out.takeIf { it.isNotEmpty() }
    }
}
