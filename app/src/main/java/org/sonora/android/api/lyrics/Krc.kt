package org.sonora.android.api.lyrics

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Inflater

/**
 * Kugou KRC (karaoke) decoder and parser.
 *
 * Kugou ships its word-level lyrics as a base64 payload that is `krc1` + a 4-byte header
 * followed by an XOR-masked zlib stream. Once inflated, each timed line looks like:
 *
 *     [lineStartMs,lineDurationMs]<offsetMs,durationMs,0>word <offsetMs,durationMs,0>word
 *
 * where `offsetMs` is measured from the line start. This gives real per-syllable timing, a
 * usable substitute for Musixmatch richsync (whose desktop endpoint no longer issues tokens).
 */
object Krc {

    private val KEY = byteArrayOf(
        0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
        0x51, 0x36, 0x31, 0x2d, 0xce.toByte(), 0xd2.toByte(), 0x6e, 0x69,
    )

    private val LINE = Regex("^\\[(\\d+),(\\d+)](.*)$")
    private val TOKEN = Regex("<(\\d+),(\\d+),(\\d+)>([^<]*)")

    /** Credit/header lines Kugou embeds in the sheet (they are not sung lyrics). */
    private val CREDITS = listOf(
        "lyrics by", "lyric by", "composed by", "produced by", "written by", "arranged by",
        "music by", "mixed by", "mastered by",
        "作词", "作曲", "编曲", "制作", "混音", "母带", "监制", "出品", "录音", "和声",
    )

    /** Decodes the base64 `content` of a Kugou KRC download into its plain KRC text. */
    fun decode(base64: String): String? {
        val raw = runCatching { Base64.getDecoder().decode(base64.trim()) }.getOrNull() ?: return null
        if (raw.size <= 4) return null
        val body = raw.copyOfRange(4, raw.size)
        val masked = ByteArray(body.size)
        for (i in body.indices) {
            val k = KEY[i % KEY.size].toInt() and 0xFF
            masked[i] = ((body[i].toInt() and 0xFF) xor k).toByte()
        }
        return runCatching {
            val inflater = Inflater()
            inflater.setInput(masked)
            val out = ByteArrayOutputStream(masked.size * 4)
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val read = inflater.inflate(buffer)
                if (read <= 0) break
                out.write(buffer, 0, read)
            }
            inflater.end()
            out.toString("UTF-8")
        }.getOrNull()
    }

    /** Parses plain KRC text into timed lines with word-level [SheetWord]s. */
    fun parse(text: String): MutableList<SheetLine> {
        val lines = mutableListOf<SheetLine>()
        for (rawLine in text.split("\n")) {
            val match = LINE.matchEntire(rawLine.trim()) ?: continue
            val start = match.groupValues[1].toLongOrNull() ?: continue
            val duration = match.groupValues[2].toLongOrNull() ?: 0L
            val tokens = TOKEN.findAll(match.groupValues[3]).toList()
            if (tokens.isEmpty()) continue

            val words = mutableListOf<SheetWord>()
            tokens.forEachIndexed { index, token ->
                val offset = token.groupValues[1].toLongOrNull() ?: 0L
                val tokenDuration = token.groupValues[2].toLongOrNull() ?: 0L
                val wordText = token.groupValues[4]
                if (wordText.isEmpty()) return@forEachIndexed
                val wordStart = start + offset
                val wordEnd = if (tokenDuration > 0) {
                    wordStart + tokenDuration
                } else {
                    val next = tokens.getOrNull(index + 1)?.groupValues?.get(1)?.toLongOrNull()
                    (next?.let { start + it } ?: (start + duration)).coerceAtLeast(wordStart + 1)
                }
                words.add(SheetWord(wordStart, wordEnd.coerceAtLeast(wordStart + 1), wordText))
            }
            if (words.isEmpty()) continue

            val body = words.joinToString("") { it.text }.trim()
            if (body.isEmpty() || credit(body)) continue
            lines.add(
                SheetLine(
                    startMs = start,
                    endMs = (start + duration).takeIf { duration > 0 },
                    text = body,
                    words = words,
                )
            )
        }
        lines.sortBy { it.startMs }
        return lines
    }

    private fun credit(text: String): Boolean {
        val lower = text.lowercase()
        return CREDITS.any { lower.contains(it) }
    }
}
