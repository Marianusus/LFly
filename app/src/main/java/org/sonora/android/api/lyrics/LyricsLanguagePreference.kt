package org.sonora.android.api.lyrics

/**
 * Prefers the original, monolingual lyric sheet when a provider offers a *bilingual* sheet
 * (e.g. an original Romanian song whose only synced entry is a Romanian + translated mix).
 *
 * Rationale: the engine rewards `synced` sheets (+200), so a bilingual synced entry can beat a
 * plain, fully-original one. Here we detect that the top-ranked sheet mixes languages and, if so,
 * fall back to the first eligible hit that is monolingual — recovering the original text at the
 * cost of synchronization.
 *
 * The decision logic is pure (language detection is injected) so it is unit-testable without
 * an Android runtime; the caller wires ML Kit's language identification through [detect].
 */
object LyricsLanguagePreference {

    private const val CHUNK_LINES = 3
    private const val MAX_CHUNKS = 20
    private const val MIN_MONOLINGUAL_FRACTION = 0.85

    /**
     * Returns a monolingual alternative to the top hit when the top hit is itself mixed, or `null`
     * when the top hit already looks monolingual (keep it) or no monolingual alternative exists.
     */
    suspend fun preferred(
        hits: List<LyricsHit>,
        detect: suspend (String) -> String?
    ): LyricsHit? {
        if (hits.size < 2) return null
        val top = hits.firstOrNull() ?: return null
        if (isMonolingual(top, detect)) return null
        return hits.firstOrNull { it !== top && isMonolingual(it, detect) }
    }

    /**
     * True when the hit is dominated by a single detected language. Lines are grouped into chunks
     * (longer text → more reliable detection); a hit is "mixed" when a significant minority
     * language is present (e.g. an original song plus a translated part).
     */
    internal suspend fun isMonolingual(
        hit: LyricsHit,
        detect: suspend (String) -> String?
    ): Boolean {
        val lines = hit.lyrics.nonBlankLines()
        if (lines.isEmpty()) return false
        val chunks = lines.chunked(CHUNK_LINES).map { it.joinToString(" ") }
        val sampled = if (chunks.size > MAX_CHUNKS) sampleEvenly(chunks, MAX_CHUNKS) else chunks
        val detected = sampled.map { detect(it)?.lowercase() }.filter { it != null && it != "und" }
        if (detected.isEmpty()) return true
        val dominant = detected.groupingBy { it!! }.eachCount().values.maxOrNull() ?: 0
        return dominant.toDouble() / detected.size >= MIN_MONOLINGUAL_FRACTION
    }

    private fun Sheet.nonBlankLines(): List<String> = when (this) {
        is Sheet.Plain -> text.split('\n')
        is Sheet.Synced -> lines.map { it.text }
    }.map { it.trim() }.filter { it.isNotEmpty() }

    private fun <T> sampleEvenly(items: List<T>, count: Int): List<T> {
        if (items.size <= count) return items
        val step = items.size.toDouble() / count
        return (0 until count).map { items[(it * step).toInt().coerceIn(0, items.size - 1)] }
    }
}
