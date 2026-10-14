package org.sonora.android.api.lyrics

/**
 * Conforms a word-level sheet to a line-synced guide — direct port of
 * `crates/music/src/lyrics/shape.rs`. Used to re-time a karaoke sheet against a trusted
 * line-synced sheet (Apple's "shape" technique).
 */
internal object Shape {

    private const val LEAST = 6
    private const val MATCHED = 0.7
    private const val KEPT = 0.5
    private const val SEATED = 0.9
    private const val LIMIT = 3000
    private const val CELLS = 2_000_000
    private val TICKS = setOf('\'', '\u2019', '\uFF07', '\u2018', '\u00B4', '`')

    private class Sung(val startMs: Long, val endMs: Long, val key: String)
    private class Slot(val at: Int, val key: String)

    fun conform(worded: Sheet, guide: Sheet): Sheet.Synced? {
        if (guide !is Sheet.Synced || worded !is Sheet.Synced) return null
        val guideLines = guide.lines
        val wordedLines = worded.lines
        if (guideLines.isEmpty() || wordedLines.isEmpty()) return null

        val sung = sung(wordedLines)
        val slotted: List<List<Slot>> = guideLines.map { tokens(it.text) }
        val places: List<Pair<Int, Int>> = slotted.flatMapIndexed { line, slots ->
            (0 until slots.size).map { line to it }
        }
        if (sung.size < LEAST || places.size < LEAST || sung.size > LIMIT || places.size > LIMIT) return null
        if (sung.size.toLong() * places.size.toLong() > CELLS) return null

        val left = sung.map { it.key }
        val right = places.map { (line, slot) -> slotted[line][slot].key }
        val pairs = paired(left, right)
        if (pairs.size < LEAST ||
            pairs.size.toDouble() < MATCHED * places.size ||
            pairs.size.toDouble() < KEPT * sung.size ||
            !seated(pairs, places, slotted)
        ) return null

        var spans: MutableList<Pair<Long, Long>?> = MutableList(places.size) { null }
        for ((word, place) in pairs) {
            spans[place] = sung[word].startMs to sung[word].endMs
        }
        val filledSpans = filled(spans, hints(guideLines, slotted))

        val lines = mutableListOf<SheetLine>()
        var cursor = 0
        for ((index, line) in guideLines.withIndex()) {
            val slots = slotted[index]
            if (slots.isEmpty()) continue
            val mine = filledSpans.subList(cursor, cursor + slots.size)
            cursor += slots.size
            val words = wordedFrom(line.text, slots, mine)
            val start = words.firstOrNull()?.startMs ?: return null
            val end = words.maxOf { it.endMs }
            lines.add(
                SheetLine(
                    startMs = start,
                    endMs = maxOf(end, start),
                    text = line.text,
                    words = words,
                )
            )
        }
        Lrc.normalize(lines)
        if (lines.size < 2) return null
        return Sheet.Synced(lines)
    }

    private fun seated(
        pairs: List<Pair<Int, Int>>,
        places: List<Pair<Int, Int>>,
        slotted: List<List<Slot>>
    ): Boolean {
        val held = BooleanArray(slotted.size)
        for ((_, place) in pairs) held[places[place].first] = true
        val counted = slotted.count { it.isNotEmpty() }
        val anchored = held.indices.count { i -> held[i] && slotted[i].isNotEmpty() }
        return anchored.toDouble() >= SEATED * counted
    }

    private fun sung(lines: List<SheetLine>): List<Sung> {
        val sung = mutableListOf<Sung>()
        for ((index, line) in lines.withIndex()) {
            val words = line.words?.takeIf { it.isNotEmpty() }
            if (words != null) {
                sung.addAll(timed(words))
            } else {
                val until = (line.endMs ?: lines.getOrNull(index + 1)?.startMs ?: line.startMs)
                    .coerceAtLeast(line.startMs)
                sung.addAll(spread(line.text, line.startMs, until))
            }
            for (lane in line.secondary) {
                val laneWords = lane.words?.takeIf { it.isNotEmpty() }
                if (laneWords != null) {
                    sung.addAll(timed(laneWords))
                } else {
                    val until = (lane.endMs ?: lane.startMs).coerceAtLeast(lane.startMs)
                    sung.addAll(spread(lane.text, lane.startMs, until))
                }
            }
        }
        return sung
    }

    private fun timed(words: List<SheetWord>): List<Sung> =
        words.flatMap { word ->
            tokens(word.text).map { slot -> Sung(word.startMs, maxOf(word.endMs, word.startMs), slot.key) }
        }

    private fun spread(text: String, startMs: Long, endMs: Long): List<Sung> {
        val slots = tokens(text)
        val total = slots.sumOf { it.key.length }
        if (total == 0) return emptyList()
        val span = (endMs - startMs).coerceAtLeast(0L)
        val sung = mutableListOf<Sung>()
        var passed = 0
        for (slot in slots) {
            val length = slot.key.length
            val from = startMs + (span * (passed.toDouble() / total)).toLong()
            passed += length
            val to = startMs + (span * (passed.toDouble() / total)).toLong()
            sung.add(Sung(from, to, slot.key))
        }
        return sung
    }

    private fun tokens(text: String): List<Slot> {
        val slots = mutableListOf<Slot>()
        var open: Slot? = null
        for ((at, letter) in text.withIndex()) {
            if (wide(letter)) {
                open?.let { slots.add(it) }
                open = null
                slots.add(Slot(at, letter.lowercaseChar().toString()))
                continue
            }
            if (letter in TICKS) continue
            if (letter.isLetterOrDigit()) {
                if (open == null) open = Slot(at, "")
                open = Slot(open!!.at, open!!.key + letter.lowercaseChar())
            } else {
                open?.let { slots.add(it) }
                open = null
            }
        }
        open?.let { slots.add(it) }
        return slots
    }

    private fun wide(letter: Char): Boolean = letter.code in 0x3040..0x30FF ||
        letter.code in 0x3400..0x4DBF ||
        letter.code in 0x4E00..0x9FFF ||
        letter.code in 0xAC00..0xD7AF ||
        letter.code in 0xF900..0xFAFF

    private fun paired(left: List<String>, right: List<String>): List<Pair<Int, Int>> {
        val rows = left.size + 1
        val columns = right.size + 1
        val table = Array(rows) { IntArray(columns) }
        for (row in left.size - 1 downTo 0) {
            for (column in right.size - 1 downTo 0) {
                table[row][column] = if (akin(left[row], right[column])) {
                    table[row + 1][column + 1] + 1
                } else {
                    maxOf(table[row + 1][column], table[row][column + 1])
                }
            }
        }
        val pairs = mutableListOf<Pair<Int, Int>>()
        var row = 0
        var column = 0
        while (row < left.size && column < right.size) {
            if (akin(left[row], right[column])) {
                pairs.add(row to column)
                row++
                column++
                continue
            }
            if (table[row + 1][column] >= table[row][column + 1]) row++ else column++
        }
        return pairs
    }

    private fun akin(left: String, right: String): Boolean {
        if (left == right) return true
        val (short, long) = if (left.length <= right.length) left to right else right to left
        return short.length >= 4 && long.length - short.length <= 2 && long.startsWith(short)
    }

    private fun hints(guide: List<SheetLine>, slotted: List<List<Slot>>): List<Pair<Long, Long>> {
        val hints = mutableListOf<Pair<Long, Long>>()
        for ((index, line) in guide.withIndex()) {
            val slots = slotted[index]
            if (slots.isEmpty()) continue
            val until = (line.endMs ?: guide.getOrNull(index + 1)?.startMs ?: line.startMs)
                .coerceAtLeast(line.startMs)
            val span = (until - line.startMs).coerceAtLeast(0L)
            val total = slots.sumOf { it.key.length.coerceAtLeast(1) }
            var passed = 0
            for (slot in slots) {
                val start = line.startMs + (span * (passed.toDouble() / total)).toLong()
                passed += slot.key.length.coerceAtLeast(1)
                val end = line.startMs + (span * (passed.toDouble() / total)).toLong()
                hints.add(start to end)
            }
        }
        return hints
    }

    private fun shared(afterMs: Long, beforeMs: Long, count: Int): List<Pair<Long, Long>> {
        val span = (beforeMs - afterMs).coerceAtLeast(0L)
        return (0 until count).map { step ->
            val from = afterMs + (span * (step.toDouble() / count)).toLong()
            val to = afterMs + (span * ((step + 1).toDouble() / count)).toLong()
            from to to
        }
    }

    private fun filled(
        spans: List<Pair<Long, Long>?>,
        hints: List<Pair<Long, Long>>
    ): List<Pair<Long, Long>> {
        val settled = mutableListOf<Pair<Long, Long>>()
        var index = 0
        while (index < spans.size) {
            val span = spans[index]
            if (span != null) {
                settled.add(span)
                index++
                continue
            }
            var until = index
            while (until < spans.size && spans[until] == null) until++
            val after = settled.lastOrNull()?.second
            val before = spans.getOrNull(until)?.first
            if (after != null && before != null && before > after) {
                settled.addAll(shared(after, before, until - index))
            } else {
                for (at in index until until) {
                    var start = hints.getOrNull(at)?.first ?: after ?: 0L
                    var end = hints.getOrNull(at)?.second ?: after ?: 0L
                    if (after != null) {
                        start = maxOf(start, after)
                        end = maxOf(end, start)
                    }
                    if (before != null) {
                        start = minOf(start, before)
                        end = minOf(end, before).coerceAtLeast(start)
                    }
                    val floor = settled.lastOrNull()?.second ?: start
                    val s = maxOf(start, floor)
                    settled.add(s to maxOf(end, maxOf(s, floor)))
                }
            }
            index = until
        }
        return settled
    }

    private fun wordedFrom(text: String, slots: List<Slot>, spans: List<Pair<Long, Long>>): MutableList<SheetWord> {
        val words = mutableListOf<SheetWord>()
        for ((index, slot) in slots.withIndex()) {
            val from = if (index == 0) 0 else slot.at
            val until = slots.getOrNull(index + 1)?.at?.coerceAtLeast(from) ?: text.length
            val (start, end) = spans[index]
            words.add(
                SheetWord(
                    startMs = start,
                    endMs = maxOf(end, start),
                    text = text.substring(from.coerceIn(0, text.length), until.coerceIn(from, text.length)),
                )
            )
        }
        return words
    }
}
