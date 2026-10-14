package org.sonora.android.api

/**
 * Pure word-level timing utilities for karaoke-style lyric highlighting.
 *
 * LRCLIB only ships line-level timestamps, so when a line has no real word tags
 * (Enhanced LRC) we estimate per-word timings by distributing the line duration
 * across its words, weighted by syllable count. The result is deterministic and
 * unit-testable, and it is the closest approximation to "perfectly aligned"
 * words available without on-device forced alignment.
 */
object LyricsTiming {

    private val CJK_REGEX = Regex("[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff\\uf900-\\ufaff\\uac00-\\ud7af]")

    private val VOWELS = "aeiouyàáâãäåāăąèéêëēĕėęěìíîïĩīĭįıòóôõöøōŏőùúûüũūŭůűųýÿæœаеёиоуыэюяіїє"

    /** Splits a lyric line into displayable tokens (words). */
    fun splitWords(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<String>()
        for (token in text.trim().split(Regex("\\s+"))) {
            if (token.isBlank()) continue
            // Scripts without spaces (CJK) get per-character tokens for a usable sweep.
            if (!CJK_REGEX.containsMatchIn(token)) {
                out.add(token)
                continue
            }
            val buffer = StringBuilder()
            token.forEach { ch ->
                if (CJK_REGEX.matches(ch.toString())) {
                    if (buffer.isNotEmpty()) {
                        out.add(buffer.toString())
                        buffer.clear()
                    }
                    out.add(ch.toString())
                } else {
                    buffer.append(ch)
                }
            }
            if (buffer.isNotEmpty()) out.add(buffer.toString())
        }
        return out
    }

    /** Rough syllable estimate for Latin/Cyrillic text; falls back to 1 for the rest. */
    fun syllableCount(rawWord: String): Int {
        val word = rawWord.lowercase().filter { it.isLetter() }
        if (word.isEmpty()) return 1
        if (CJK_REGEX.containsMatchIn(word)) return word.length.coerceAtLeast(1)

        var count = 0
        var inVowelRun = false
        for (ch in word) {
            val isVowel = VOWELS.contains(ch)
            if (isVowel && !inVowelRun) count++
            inVowelRun = isVowel
        }
        return count.coerceAtLeast(1)
    }

    /**
     * Distributes [startMs]..[endMs] across the words of [text] weighted by syllables.
     * Every word gets at least [minWordMs] when the window allows it.
     *
     * [endMs] is normally the start of the next line. On EDM/pop tracks that gap can span a
     * whole instrumental drop, so spreading the words over it makes the sweep lag far behind
     * the vocals. The window is therefore capped to a plausible singing duration
     * (~500 ms per syllable) so a long silence never stretches the highlight.
     */
    fun estimateWordTimings(
        text: String,
        startMs: Long,
        endMs: Long,
        minWordMs: Long = 170L
    ): List<WordTiming> {
        val words = splitWords(text)
        if (words.isEmpty()) return emptyList()

        val syllables = words.sumOf { syllableCount(it) }
        val speechCap = (syllables * 500L).coerceIn(1_500L, 6_000L)
        val window = (endMs - startMs).coerceAtMost(speechCap).coerceAtLeast(words.size * minWordMs)
        val weights = words.map {
            (syllableCount(it) * 0.7f + it.length.coerceAtMost(12) * 0.3f).coerceAtLeast(0.5f)
        }
        val totalWeight = weights.sum().takeIf { it > 0f } ?: words.size.toFloat()

        val out = ArrayList<WordTiming>(words.size)
        var cumulative = 0f
        words.forEachIndexed { index, word ->
            val startFraction = cumulative / totalWeight
            cumulative += weights[index]
            val endFraction = cumulative / totalWeight
            val wordStart = startMs + (window * startFraction).toLong()
            val wordEnd = startMs + (window * endFraction).toLong()
            out.add(WordTiming(text = word, startMs = wordStart, endMs = wordEnd.coerceAtLeast(wordStart + 1)))
        }
        return out
    }

    /**
     * Word timings for a line: real Enhanced-LRC tags when present, otherwise estimated
     * between this line and the next one.
     */
    fun wordTimingsForLine(
        line: LyricLine,
        nextLineStartMs: Long?,
        fallbackWindowMs: Long = 4_000L
    ): List<WordTiming> {
        if (!line.wordTimings.isNullOrEmpty()) return line.wordTimings
        if (line.text.isBlank()) return emptyList()
        val end = nextLineStartMs?.takeIf { it > line.timeMs } ?: (line.timeMs + fallbackWindowMs)
        return estimateWordTimings(line.text, line.timeMs, end)
    }

    /** Index of the last word whose startMs <= [positionMs], or -1 when none. */
    fun activeWordIndex(words: List<WordTiming>, positionMs: Long): Int {
        var low = 0
        var high = words.size - 1
        var result = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (words[mid].startMs <= positionMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    /** Interpolated progress (0..1) across the whole word list at [positionMs]. */
    fun wordProgress(words: List<WordTiming>, positionMs: Long): Float {
        if (words.isEmpty()) return 0f
        val first = words.first().startMs
        val last = words.last().endMs
        if (last <= first) return 1f
        return ((positionMs - first).toFloat() / (last - first).toFloat()).coerceIn(0f, 1f)
    }
}
