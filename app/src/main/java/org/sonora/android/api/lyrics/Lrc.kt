package org.sonora.android.api.lyrics

/**
 * LRC parser and normalizer — direct port of `crates/music/src/lyrics/lrc.rs`.
 *
 * Supports: multiple timestamps per line, Enhanced-LRC `<mm:ss.xx>` word tags, a trailing
 * end-tag, the `[offset:...]` tag, section-label stripping, homoglyph de-spoofing, wide-mark
 * narrowing, punctuation tightening, recapitalization, and extraction of parenthesised
 * background vocals into [SheetLine.secondary] lanes.
 */
object Lrc {

    private val HOMOGLYPHS: Map<Char, Char> = buildMap {
        val pairs = listOf(
            'а' to 'a', 'в' to 'b', 'е' to 'e', 'к' to 'k', 'м' to 'm', 'н' to 'h', 'о' to 'o',
            'р' to 'p', 'с' to 'c', 'т' to 't', 'у' to 'y', 'х' to 'x', 'і' to 'i', 'ј' to 'j',
            'ѕ' to 's', 'ԁ' to 'd',
            'А' to 'A', 'В' to 'B', 'Е' to 'E', 'К' to 'K', 'М' to 'M', 'Н' to 'H', 'О' to 'O',
            'Р' to 'P', 'С' to 'C', 'Т' to 'T', 'У' to 'Y', 'Х' to 'X', 'І' to 'I', 'Ј' to 'J',
            'Ѕ' to 'S',
            'Α' to 'A', 'Β' to 'B', 'Ε' to 'E', 'Ζ' to 'Z', 'Η' to 'H', 'Ι' to 'I', 'Κ' to 'K',
            'Μ' to 'M', 'Ν' to 'N', 'Ο' to 'O', 'Ρ' to 'P', 'Τ' to 'T', 'Υ' to 'Y', 'Χ' to 'X',
            'ο' to 'o', 'ρ' to 'p', 'τ' to 't', 'ι' to 'i',
        )
        for ((from, to) in pairs) put(from, to)
    }

    private val WIDE_MARKS: Map<Char, Char> = mapOf(
        '\uFF07' to '\'', '\u02BC' to '\'', '\u2032' to '\'', '\u00B4' to '\'', '`' to '\'',
    )

    private val STRUCTURAL_LABELS = setOf(
        "intro", "verse", "pre-chorus", "chorus", "refrain", "hook", "bridge",
        "breakdown", "solo", "instrumental", "outro",
    )

    // ── public API ──────────────────────────────────────────────────────────────────────────

    fun parse(lrc: String): MutableList<SheetLine> {
        var shift = 0L
        val lines = mutableListOf<SheetLine>()
        for (line in lrc.split("\n")) {
            val ms = offsetOf(line)
            if (ms != null) {
                shift += ms
                continue
            }
            lines.addAll(read(line))
        }
        if (shift != 0L) applyShift(lines, -shift)
        return normalizeInternal(lines)
    }

    /** Mutates [lines] in place, mirroring the Rust `normalize(&mut Vec<..>)`. */
    fun normalize(lines: MutableList<SheetLine>) {
        val normalized = normalizeInternal(lines.toMutableList())
        lines.clear()
        lines.addAll(normalized)
    }

    // ── timestamps / tags ───────────────────────────────────────────────────────────────────

    fun stampOf(stamp: String): Long? {
        val s = stamp.trim().replace(',', '.')
        val parts = s.split(':')
        if (parts.size < 2 || parts.size > 3) return null

        fun u64(x: String): Long? = x.toLongOrNull()?.takeIf { it >= 0L }
        fun f64(x: String): Double? = x.toDoubleOrNull()

        fun finish(totalSeconds: Double): Long? {
            if (!totalSeconds.isFinite() || totalSeconds < 0.0) return null
            if (totalSeconds > 9.0e12) return null // emulates Duration::try_from_secs_f64 overflow
            return (totalSeconds * 1000.0).toLong()
        }

        return if (parts.size == 3) {
            val third = parts[2]
            if (third.contains('.')) {
                val h = u64(parts[0]) ?: return null
                val m = u64(parts[1]) ?: return null
                val sec = f64(third) ?: return null
                finish(h.toDouble() * 3600.0 + m.toDouble() * 60.0 + sec)
            } else {
                val minutes = u64(parts[0]) ?: return null
                val seconds = u64(parts[1]) ?: return null
                val fraction = u64(third) ?: return null
                val sec = seconds.toDouble() +
                    if (fraction >= 100L) fraction.toDouble() / 1000.0 else fraction.toDouble() / 100.0
                finish(minutes.toDouble() * 60.0 + sec)
            }
        } else {
            val minutes = u64(parts[0]) ?: return null
            val sec = f64(parts[1]) ?: return null
            finish(minutes.toDouble() * 60.0 + sec)
        }
    }

    private fun offsetOf(line: String): Long? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("[")) return null
        val rest = trimmed.substring(1)
        val close = rest.indexOf(']')
        if (close < 0) return null
        val tag = rest.substring(0, close)
        val tail = rest.substring(close + 1)
        if (tail.trim().isNotEmpty()) return null
        val split = tag.indexOf(':')
        if (split < 0) return null
        val name = tag.substring(0, split).trim()
        if (!name.equals("offset", ignoreCase = true)) return null
        return tag.substring(split + 1).trim().toLongOrNull()
    }

    private fun applyShift(lines: MutableList<SheetLine>, ms: Long) {
        fun shift(at: Long): Long = if (ms >= 0) at + ms else (at - (-ms)).coerceAtLeast(0L)
        for (line in lines) {
            line.startMs = shift(line.startMs)
            line.endMs = line.endMs?.let { shift(it) }
            line.words?.forEach { it.startMs = shift(it.startMs); it.endMs = shift(it.endMs) }
        }
    }

    // ── reading ─────────────────────────────────────────────────────────────────────────────

    private class Segment(val at: Long?, val text: String)

    private fun read(line: String): List<SheetLine> {
        var rest = line.trim()
        val stamps = mutableListOf<Long>()
        while (rest.startsWith("[")) {
            val body = rest.substring(1)
            val close = body.indexOf(']')
            if (close < 0) break
            val at = stampOf(body.substring(0, close)) ?: break
            stamps.add(at)
            rest = body.substring(close + 1).trimStart()
        }

        val (bodyText, closed) = closedAt(rest)
        val (text, words) = spoken(bodyText)

        return stamps.map { start ->
            SheetLine(
                startMs = start,
                endMs = closed?.takeIf { it > start },
                text = text,
                words = words?.let { shiftedWords(it, start) },
            )
        }
    }

    private fun shiftedWords(words: MutableList<SheetWord>, start: Long): MutableList<SheetWord> {
        val first = words.firstOrNull() ?: return words
        val drift = start - first.startMs
        if (drift == 0L) return words
        return words.map {
            SheetWord(
                startMs = (it.startMs + drift).coerceAtLeast(0L),
                endMs = (it.endMs + drift).coerceAtLeast(0L),
                text = it.text,
            )
        }.toMutableList()
    }

    private fun closedAt(rest: String): Pair<String, Long?> {
        val trimmed = rest.trimEnd()
        if (!trimmed.endsWith("]")) return rest to null
        val body = trimmed.dropLast(1)
        val open = body.lastIndexOf('[')
        if (open < 0) return rest to null
        val at = stampOf(body.substring(open + 1)) ?: return rest to null
        val text = body.substring(0, open).trimEnd()
        if (text.isEmpty()) return rest to null
        return text to at
    }

    private fun spoken(body: String): Pair<String, MutableList<SheetWord>?> {
        val segments = cut(body)
        val whole = segments.joinToString("") { it.text }
        val timed = segments.filter { it.at != null }
        if (timed.isEmpty()) return whole.trim() to null

        val words = mutableListOf<SheetWord>()
        for ((index, segment) in timed.withIndex()) {
            val start = segment.at!!
            if (segment.text.isEmpty()) {
                words.lastOrNull()?.let { it.endMs = maxOf(start, it.startMs) }
                continue
            }
            val end = timed.getOrNull(index + 1)?.at?.takeIf { it > start } ?: start
            words.add(SheetWord(startMs = start, endMs = end, text = segment.text))
        }
        return whole.trimEnd() to words.ifEmpty { null }
    }

    private fun cut(body: String): MutableList<Segment> {
        val segments = mutableListOf(Segment(null, ""))
        var rest = body
        while (true) {
            val open = rest.indexOf('<')
            if (open < 0) break
            val tail = rest.substring(open + 1)
            val shut = tail.indexOf('>')
            if (shut < 0) break
            val last = segments.last()
            val at = stampOf(tail.substring(0, shut))
            if (at != null) {
                segments[segments.size - 1] = Segment(last.at, last.text + rest.substring(0, open))
                segments.add(Segment(at, ""))
            } else {
                segments[segments.size - 1] =
                    Segment(last.at, last.text + rest.substring(0, open + shut + 2))
            }
            rest = tail.substring(shut + 1)
        }
        val last = segments.last()
        segments[segments.size - 1] = Segment(last.at, last.text + rest)
        return segments
    }

    // ── normalization ───────────────────────────────────────────────────────────────────────

    private fun normalizeInternal(input: MutableList<SheetLine>): MutableList<SheetLine> {
        input.sortBy { it.startMs }
        close(input)
        input.removeAll { structural(it.text) }
        if (input.any { it.worded() }) {
            for (line in input) separateBackground(line)
        }

        val normalized = mutableListOf<SheetLine>()
        val leading = mutableListOf<SheetLane>()
        for (line in input) {
            if (line.text.trim().isEmpty()) {
                if (line.secondary.isEmpty()) continue
                val previous = normalized.lastOrNull()
                if (previous != null) previous.secondary.addAll(line.secondary)
                else leading.addAll(line.secondary)
                continue
            }
            if (leading.isNotEmpty()) {
                line.secondary.addAll(0, leading)
                leading.clear()
            }
            normalized.add(line)
        }

        demark(normalized)
        collapse(normalized)
        unspoof(normalized)
        unwiden(normalized)
        bracket(normalized)
        recapitalize(normalized)
        Romanize.apply(normalized)
        return normalized
    }

    private fun bracket(lines: MutableList<SheetLine>) {
        for (lane in lines.flatMap { it.secondary }) {
            if (lane.text.trim().isEmpty() || lane.text.contains('(') || lane.text.contains(')')) continue
            val words = lane.words
            val firstWord = words?.firstOrNull { it.text.trim().isNotEmpty() }
            if (words == null || firstWord == null) {
                lane.text = "(${lane.text})"
                continue
            }
            firstWord.text = "(" + firstWord.text
            words.last { it.text.trim().isNotEmpty() }.text =
                words.last { it.text.trim().isNotEmpty() }.text.trimEnd() + ")"
            lane.text = words.joinToString("") { it.text }
        }
    }

    private fun unwiden(lines: MutableList<SheetLine>) {
        for (line in lines) {
            narrow(line.words, { line.text }, { line.text = it })
            for (lane in line.secondary) narrow(lane.words, { lane.text }, { lane.text = it })
        }
    }

    private inline fun narrow(
        words: MutableList<SheetWord>?,
        getText: () -> String,
        setText: (String) -> Unit
    ) {
        words?.forEach { it.text = narrowed(it.text) }
        setText(narrowed(getText()))
    }

    private fun narrowed(text: String): String = text.map { WIDE_MARKS[it] ?: it }.joinToString("")

    private fun recapitalize(lines: MutableList<SheetLine>) {
        if (!mostlyCapitalized(lines)) return
        for (line in lines) {
            capitalize(line.words, { line.text }, { line.text = it })
            for (lane in line.secondary) capitalize(lane.words, { lane.text }, { lane.text = it })
        }
    }

    private fun mostlyCapitalized(lines: List<SheetLine>): Boolean {
        var upper = 0
        var lower = 0
        for (line in lines) {
            val letter = line.text.firstOrNull { it.isLetterOrDigit() } ?: continue
            when {
                letter.isUpperCase() -> upper++
                letter.isLowerCase() -> lower++
            }
        }
        return upper > 0 && upper >= lower
    }

    private inline fun capitalize(
        words: MutableList<SheetWord>?,
        getText: () -> String,
        setText: (String) -> Unit
    ) {
        words?.firstOrNull { it.text.any(Char::isLetterOrDigit) }?.let { it.text = uppercased(it.text) }
        setText(uppercased(getText()))
    }

    private fun uppercased(text: String): String {
        val at = text.indexOfFirst { it.isLetterOrDigit() }
        if (at < 0) return text
        val letter = text[at]
        if (!letter.isLowerCase()) return text
        return text.substring(0, at) + letter.uppercaseChar() + text.substring(at + 1)
    }

    private fun demark(lines: MutableList<SheetLine>) {
        for (line in lines) {
            unmark(line.words, { line.text }, { line.text = it })
            for (lane in line.secondary) unmark(lane.words, { lane.text }, { lane.text = it })
        }
    }

    private inline fun unmark(
        words: MutableList<SheetWord>?,
        getText: () -> String,
        setText: (String) -> Unit
    ) {
        val whole = words?.joinToString("") { it.text } ?: getText()
        val marks = marks(whole)
        if (marks.isEmpty()) return
        if (words != null) {
            var cursor = 0
            val kept = mutableListOf<SheetWord>()
            for (w in words) {
                val at = cursor
                cursor += w.text.length
                val cleaned = outside(w.text, at, marks)
                if (cleaned.isNotEmpty()) {
                    w.text = cleaned
                    kept.add(w)
                }
            }
            words.clear()
            words.addAll(kept)
            setText(words.joinToString("") { it.text })
        } else {
            setText(outside(whole, 0, marks))
        }
    }

    private fun marks(text: String): List<IntRange> {
        val marks = mutableListOf<IntRange>()
        var opened: Int? = null
        for (i in text.indices) {
            when (text[i]) {
                '<' -> opened = i
                '>' -> {
                    val start = opened
                    opened = null
                    if (start != null) {
                        val inner = text.substring(start + 1, i)
                        val numeric = inner.isNotEmpty() &&
                            inner.trimStart('+', '-').all { it.isDigit() }
                        if (numeric) marks.add(start until (i + 1))
                    }
                }
            }
        }
        return marks
    }

    private fun outside(text: String, at: Int, marks: List<IntRange>): String {
        val sb = StringBuilder()
        for (i in text.indices) {
            val pos = at + i
            if (marks.none { pos in it }) sb.append(text[i])
        }
        return sb.toString()
    }

    private fun collapse(lines: MutableList<SheetLine>) {
        for (line in lines) {
            tighten(line.words, { line.text }, { line.text = it })
            for (lane in line.secondary) tighten(lane.words, { lane.text }, { lane.text = it })
        }
    }

    private inline fun tighten(
        words: MutableList<SheetWord>?,
        getText: () -> String,
        setText: (String) -> Unit
    ) {
        val spaced = words != null && words.any { w -> w.text.any(Char::isWhitespace) }
        if (spaced && words != null) {
            var trailing = true
            for (i in words.indices) {
                var tidied = unspacePunctuation(squeezed(words[i].text))
                if (trailing && tidied.startsWith(" ")) tidied = tidied.substring(1)
                if (tidied.firstOrNull()?.let { closingPunctuation(it) } == true) {
                    words.take(i).reversed().firstOrNull { it.text.isNotEmpty() }?.let {
                        it.text = it.text.trimEnd()
                    }
                }
                trailing = tidied.endsWith(" ")
                words[i].text = tidied
            }
            words.lastOrNull()?.let { if (it.text.endsWith(" ")) it.text = it.text.dropLast(1) }
            setText(words.joinToString("") { it.text })
        } else {
            setText(unspacePunctuation(squeezed(words?.joinToString("") { it.text } ?: getText())).trim())
        }
    }

    private fun unspacePunctuation(text: String): String {
        val tightened = StringBuilder()
        val spacing = StringBuilder()
        for (letter in text) {
            if (letter.isWhitespace()) {
                spacing.append(letter)
                continue
            }
            if (!closingPunctuation(letter)) tightened.append(spacing)
            spacing.clear()
            tightened.append(letter)
        }
        tightened.append(spacing)
        return tightened.toString()
    }

    private fun closingPunctuation(letter: Char): Boolean = letter in ")]},.!?;:%"

    private fun squeezed(text: String): String {
        val sb = StringBuilder()
        var spacing = false
        for (letter in text) {
            if (letter.isWhitespace()) {
                spacing = true
            } else {
                if (spacing) {
                    sb.append(' ')
                    spacing = false
                }
                sb.append(letter)
            }
        }
        if (spacing) sb.append(' ')
        return sb.toString()
    }

    private fun unspoof(lines: MutableList<SheetLine>) {
        for (line in lines) {
            if (spoofed(line.text)) {
                line.text = latinized(line.text)
                line.words?.forEach { it.text = latinized(it.text) }
            }
        }
        for (lane in lines.flatMap { it.secondary }) {
            if (spoofed(lane.text)) lane.text = latinized(lane.text)
        }
    }

    private fun spoofed(text: String): Boolean {
        var latin = false
        var masked = false
        for (letter in text) {
            if (!letter.isLetter()) continue
            if (letter.code in 0x41..0x5A || letter.code in 0x61..0x7A) {
                latin = true
                continue
            }
            if (HOMOGLYPHS.containsKey(letter)) masked = true else return false
        }
        return latin && masked
    }

    private fun latinized(text: String): String = text.map { HOMOGLYPHS[it] ?: it }.joinToString("")

    private fun structural(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.none { it.isLetterOrDigit() }) return true
        val label = trimmed.trim { it.isWhitespace() || it in "[]-—" }.lowercase()
        val wrapped = (trimmed.startsWith("[") && trimmed.endsWith("]")) ||
            (trimmed.startsWith("-") && trimmed.endsWith("-"))
        return wrapped && label in STRUCTURAL_LABELS
    }

    // ── background extraction ───────────────────────────────────────────────────────────────

    private class Parenthetical(val outer: IntRange, val inner: IntRange)

    private fun separateBackground(line: SheetLine) {
        val original = line.text
        val spans = parentheticalSpans(original)
        if (spans.isEmpty()) return
        val placements = line.words?.let { placeWords(original, it) }
        val lanes = mutableListOf<SheetLane>()

        for (span in spans) {
            val text = original.substring(span.inner.first, span.inner.last + 1).trim()
            if (text.isEmpty()) continue
            val laneWords: MutableList<SheetWord>? = if (placements != null && line.words != null) {
                val out = mutableListOf<SheetWord>()
                for ((word, placed) in line.words!!.zip(placements)) {
                    val start = maxOf(placed.first, span.inner.first)
                    val end = minOf(placed.last + 1, span.inner.last + 1)
                    if (start < end) {
                        val t = original.substring(start, end)
                        if (t.trim().isNotEmpty()) out.add(SheetWord(word.startMs, word.endMs, t))
                    }
                }
                out.ifEmpty { null }
            } else null
            val start = laneWords?.firstOrNull()?.startMs ?: line.startMs
            val end = laneWords?.lastOrNull()?.endMs ?: line.endMs
            lanes.add(SheetLane(startMs = start, endMs = end, text = text, words = laneWords))
        }

        if (lanes.isEmpty()) return
        val words = line.words
        if (words != null && placements != null) {
            val kept = mutableListOf<SheetWord>()
            for ((word, placed) in words.zip(placements)) {
                val t = wordWithoutSpans(original, placed, spans)
                if (t.trim().isNotEmpty()) kept.add(SheetWord(word.startMs, word.endMs, t))
            }
            words.clear()
            words.addAll(kept)
            if (words.isEmpty()) line.words = null
        }
        line.text = withoutSpans(original, spans)
        line.secondary.addAll(lanes)
    }

    private fun parentheticalSpans(text: String): List<Parenthetical> {
        val spans = mutableListOf<Parenthetical>()
        var opened: Pair<Int, Int>? = null
        var depth = 0
        for (i in text.indices) {
            when (text[i]) {
                '(', '（' -> {
                    if (depth == 0) opened = i to (i + 1)
                    depth++
                }
                ')', '）' -> {
                    if (depth > 0) {
                        depth--
                        if (depth == 0) {
                            val o = opened
                            opened = null
                            if (o != null) spans.add(Parenthetical(o.first until (i + 1), o.second until i))
                        }
                    }
                }
            }
        }
        return spans
    }

    private fun placeWords(text: String, words: List<SheetWord>): List<IntRange>? {
        val placed = mutableListOf<IntRange>()
        var cursor = 0
        for (word in words) {
            if (cursor > text.length) return null
            val remainder = text.substring(cursor)
            val relative = remainder.indexOf(word.text)
            if (relative < 0) return null
            val start = cursor + relative
            val end = start + word.text.length
            placed.add(start until end)
            cursor = end
        }
        return placed
    }

    private fun withoutSpans(text: String, spans: List<Parenthetical>): String {
        val primary = StringBuilder()
        var cursor = 0
        for (span in spans) {
            primary.append(text.substring(cursor, span.outer.first))
            cursor = span.outer.last + 1
        }
        primary.append(text.substring(cursor))
        return primary.toString().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun wordWithoutSpans(text: String, placed: IntRange, spans: List<Parenthetical>): String {
        val primary = StringBuilder()
        var cursor = placed.first
        for (span in spans.filter { it.outer.first < placed.last + 1 && it.outer.last + 1 > placed.first }) {
            val end = minOf(span.outer.first, placed.last + 1)
            if (cursor < end) primary.append(text.substring(cursor, end))
            cursor = maxOf(cursor, minOf(span.outer.last + 1, placed.last + 1))
        }
        if (cursor < placed.last + 1) primary.append(text.substring(cursor, placed.last + 1))
        return primary.toString()
    }

    // ── line closing ────────────────────────────────────────────────────────────────────────

    private fun close(lines: MutableList<SheetLine>) {
        for (index in 0 until lines.size - 1) {
            if (lines[index].endMs == null) lines[index].endMs = lines[index + 1].startMs
        }
        for (line in lines) {
            val end = line.endMs ?: continue
            val last = line.words?.lastOrNull() ?: continue
            if (last.endMs <= last.startMs) last.endMs = maxOf(end, last.startMs)
        }
    }
}
