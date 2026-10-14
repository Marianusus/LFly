package org.sonora.android.api.lyrics

/**
 * Ranking, eligibility and clock helpers — direct port of `crates/music/src/lyrics/mod.rs`.
 */
object LyricsRank {

    private const val CLOSE_ENOUGH = 3L
    private const val WAY_OFF = 10L
    private const val TITLE = 40
    private const val ARTIST = 30
    private const val ALBUM = 15
    private const val SYNCED = 200
    private const val WORDED = 400
    private const val DRIFTED = 50
    private const val TRUNCATED = 500
    private const val TRUSTED = 25

    /** Ranks [hits] for [query], dropping the ineligible and de-duplicating by content. */
    fun rank(query: LyricsQuery, hits: List<LyricsHit>): List<LyricsHit> {
        val scored = hits
            .filter { eligible(query, it) }
            .map { it to score(query, it) }
            .sortedWith(
                compareByDescending<Pair<LyricsHit, Int>> { it.second }
                    .thenBy { it.first.source }
                    .thenBy { it.first.title }
            )
        val seen = HashSet<String>()
        return scored.map { it.first }.filter { seen.add(fingerprint(it.lyrics)) }
    }

    /** Aligns word-level hits (that aren't trusted or layered) to a line-synced guide. */
    fun reshape(hits: MutableList<LyricsHit>) {
        val guide = hits.firstOrNull { it.lyrics.synced() && !it.lyrics.worded() }?.lyrics ?: return
        for (index in hits.indices) {
            val hit = hits[index]
            if (hit.lyrics.worded() && hit.trust < TRUSTED && !layered(hit.lyrics)) {
                val conformed = Shape.conform(hit.lyrics, guide)
                if (conformed != null) hits[index] = hit.copy(lyrics = conformed)
            }
        }
    }

    private fun layered(lyrics: Sheet): Boolean {
        val lines = (lyrics as? Sheet.Synced)?.lines ?: return false
        return lines.any { it.secondary.isNotEmpty() || !it.voice.isLead() }
    }

    fun eligible(query: LyricsQuery, hit: LyricsHit): Boolean =
        matched(query, hit) && !hit.lyrics.isEmpty() && !lowQuality(hit.lyrics)

    fun instrumental(query: LyricsQuery, hits: List<LyricsHit>): Boolean {
        val matching = hits.filter { matched(query, it) }
        return matching.any { it.instrumental } && matching.none { !it.lyrics.isEmpty() }
    }

    private fun matched(query: LyricsQuery, hit: LyricsHit): Boolean =
        matches(
            title = query.title,
            artist = query.artist,
            durationMs = query.durationMs,
            hitTitle = hit.title,
            hitArtist = hit.artist,
            hitDurationMs = hit.durationMs
        )

    /**
     * Pure reliability check shared with the legacy LRCLIB client: a hit is acceptable only when
     * its title and artist are alike and (when both durations are known) it is within [WAY_OFF] s.
     */
    internal fun matches(
        title: String,
        artist: String,
        durationMs: Long,
        hitTitle: String,
        hitArtist: String,
        hitDurationMs: Long?
    ): Boolean {
        if (!alike(hitTitle, title) || !artistsAlike(hitArtist, artist)) return false
        val duration = hitDurationMs ?: return true
        return durationMs == 0L ||
            kotlin.math.abs(duration / 1000L - durationMs / 1000L) <= WAY_OFF
    }

    fun score(query: LyricsQuery, hit: LyricsHit): Int {
        var score = hit.trust
        hit.durationMs?.let { duration ->
            val drift = kotlin.math.abs(duration / 1000L - query.durationMs / 1000L)
            if (drift <= CLOSE_ENOUGH) score += (100 - drift.toInt() * 10)
            else if (drift > WAY_OFF) score -= DRIFTED
        }
        if (alike(hit.title, query.title)) score += TITLE
        if (artistsAlike(hit.artist, query.artist)) score += ARTIST
        if (hit.album != null && query.album != null && alike(hit.album, query.album)) score += ALBUM
        if (hit.lyrics.synced()) score += SYNCED
        if (hit.lyrics.worded()) score += WORDED
        if (truncated(hit.lyrics, query.durationMs)) score -= TRUNCATED
        return score
    }

    private fun truncated(lyrics: Sheet, durationMs: Long): Boolean {
        val span = lyrics.spanMs() ?: return false
        return durationMs > 0 && span < (durationMs * 0.6).toLong()
    }

    private fun lowQuality(lyrics: Sheet): Boolean {
        val lines = (lyrics as? Sheet.Synced)?.lines ?: return false
        val texts = lines.flatMap { line -> listOf(line.text) + line.secondary.map { it.text } }
        var total = 0
        var noisy = 0
        for (text in texts) {
            total++
            if (stretchedShout(text)) noisy++
        }
        return noisy >= 3 && noisy * 6 >= total
    }

    private fun stretchedShout(text: String): Boolean {
        var uppercase = false
        var lowercase = false
        var previous: Char? = null
        var run = 0
        var longest = 0
        for (letter in text.filter { it.isLetter() }) {
            if (letter.isUpperCase()) uppercase = true
            if (letter.isLowerCase()) lowercase = true
            val folded = letter.lowercaseChar()
            if (previous == folded) run++ else { previous = folded; run = 1 }
            if (run > longest) longest = run
        }
        return uppercase && !lowercase && longest >= 3
    }

    private fun fingerprint(lyrics: Sheet): String {
        fun trim(text: String): String =
            text.filter { it.isLetterOrDigit() }.map { it.lowercaseChar() }.joinToString("")
        return when (lyrics) {
            is Sheet.Plain -> "plain:${trim(lyrics.text)}"
            is Sheet.Synced -> {
                val worded = lyrics.lines.any { it.worded() }
                val text = lyrics.lines
                    .flatMap { line -> listOf(line.text) + line.secondary.map { it.text } }
                    .joinToString("") { trim(it) }
                "synced:$worded:$text"
            }
        }
    }

    internal fun alike(left: String, right: String): Boolean {
        val l = undecorated(left)
        val r = undecorated(right)
        if (l.isEmpty() || r.isEmpty()) return false
        if (l == r) return true
        val short: String
        val long: String
        if (l.length <= r.length) { short = l; long = r } else { short = r; long = l }
        return long.contains(short) && short.length * 2 >= long.length
    }

    internal fun artistsAlike(left: String, right: String): Boolean {
        if (alike(left, right)) return true
        for (a in artistNames(left)) for (b in artistNames(right)) if (alike(a, b)) return true
        return false
    }

    private fun artistNames(artists: String): List<String> =
        artists.split(',', '&', ';').map { it.trim() }.filter { it.isNotEmpty() }

    internal fun undecorated(text: String): String {
        val beforeDash = text.split(" - ").first()
        var depth = 0
        val sb = StringBuilder()
        for (letter in beforeDash) {
            when (letter) {
                '(', '[' -> depth++
                ')', ']' -> depth = (depth - 1).coerceAtLeast(0)
                else -> if (depth == 0) sb.append(letter)
            }
        }
        return sb.filter { it.isLetterOrDigit() }.map { it.lowercaseChar() }.joinToString("")
    }

    /** The index of the line playing at [atMs] (last line whose start is <= [atMs]). */
    fun activeLine(lines: List<SheetLine>, atMs: Long): Int? {
        for (index in lines.indices.reversed()) {
            if (lines[index].startMs <= atMs) return index
        }
        return null
    }

    /** The index of the word playing at [atMs], or `null` if none is within its window. */
    fun activeWord(words: List<SheetWord>, atMs: Long): Int? {
        for (index in words.indices.reversed()) {
            if (words[index].startMs <= atMs && atMs < words[index].endMs) return index
        }
        return null
    }
}
