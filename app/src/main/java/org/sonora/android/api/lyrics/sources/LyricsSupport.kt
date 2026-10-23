package org.sonora.android.api.lyrics.sources

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.sonora.android.api.HttpClientProvider
import org.sonora.android.api.lyrics.Lrc
import org.sonora.android.api.lyrics.Sheet
import org.sonora.android.api.lyrics.SheetLane
import org.sonora.android.api.lyrics.SheetLine
import org.sonora.android.api.lyrics.SheetWord
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

internal object SourceHttp {
    private val authClient by lazy {
        HttpClientProvider.okHttpClient.newBuilder()
            .callTimeout(15, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        authenticated: Boolean = false,
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json, text/plain, */*")
                .apply { headers.forEach { (key, value) -> header(key, value) } }
                .build()
            val client = if (authenticated) authClient else HttpClientProvider.fastClient
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        }.getOrNull()
    }

    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    fun json(raw: String): JsonElement? =
        runCatching { JsonParser.parseString(raw) }.getOrNull()

    fun objectOf(raw: String): JsonObject? =
        json(raw)?.takeIf { it.isJsonObject }?.asJsonObject
}

internal class TimedRun(val startMs: Long, val endMs: Long, val raw: String)

internal data class WordRuns(val words: MutableList<SheetWord>, val text: String)

/**
 * Glues syllable runs into words using whitespace as the word boundary.
 * Ported from BitChord's SyllableRuns.kt (GPLv3).
 */
internal fun runsToWords(runs: List<TimedRun>, spacingIsExplicit: Boolean = false): WordRuns {
    val glue = spacingIsExplicit || runs.any { run -> run.raw.any(Char::isWhitespace) }
    val words = mutableListOf<SheetWord>()
    val current = StringBuilder()
    var start = 0L
    var end = 0L

    fun flush() {
        val text = current.toString().trim()
        if (text.isNotEmpty()) words.add(SheetWord(start, maxOf(start, end), text))
        current.setLength(0)
    }

    for (run in runs) {
        val content = run.raw.trim()
        if (content.isEmpty()) {
            flush()
            continue
        }
        val joins = glue && current.isNotEmpty() &&
            !run.raw.first().isWhitespace() &&
            !current.last().isUnspacedScript() &&
            !content.first().isUnspacedScript()
        if (!joins) flush()
        if (current.isEmpty()) {
            start = run.startMs
            end = run.startMs
        }
        current.append(content)
        val runEnd = maxOf(run.startMs, run.endMs)
        end = maxOf(end, runEnd)
        if (!glue || run.raw.last().isWhitespace()) flush()
    }
    flush()
    return WordRuns(words, words.joinToString(" ") { it.text })
}

private fun Char.isUnspacedScript(): Boolean =
    this in '\u3400'..'\u9FFF' ||
        this in '\u3040'..'\u30FF' ||
        this in '\u3000'..'\u303F' ||
        this in '\uFF00'..'\uFFEF' ||
        this in '\u0E00'..'\u0E7F'

/** Apple Music TTML parser, producing Sonora's shared Sheet model. */
internal object SourceTtml {
    private val skippedRoles = setOf("x-translation", "x-roman")
    private const val backgroundRole = "x-bg"

    fun parse(ttml: String): Sheet.Synced? = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            harden("http://apache.org/xml/features/disallow-doctype-decl")
            harden("http://xml.org/sax/features/external-general-entities", false)
            harden("http://xml.org/sax/features/external-parameter-entities", false)
            harden(XMLConstants.FEATURE_SECURE_PROCESSING)
            runCatching { isExpandEntityReferences = false }
        }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(ttml)))
        val paragraphs = document.getElementsByTagName("p")
        val lines = mutableListOf<SheetLine>()
        for (i in 0 until paragraphs.length) {
            val paragraph = paragraphs.item(i) as? Element ?: continue
            lineFrom(paragraph)?.let(lines::add)
        }
        lines.sortBy { it.startMs }
        lines.takeIf { it.isNotEmpty() }?.let { Sheet.Synced(it) }
    }.getOrNull()

    private fun DocumentBuilderFactory.harden(feature: String, value: Boolean = true) {
        runCatching { setFeature(feature, value) }
    }

    private fun Element.qualified(name: String): String {
        getAttribute(name).takeIf { it.isNotEmpty() }?.let { return it }
        val local = name.substringAfter(':')
        val attributes = attributes ?: return ""
        for (i in 0 until attributes.length) {
            val attribute = attributes.item(i) ?: continue
            if (attribute.nodeName == name ||
                attribute.nodeName == local ||
                attribute.localName == local
            ) {
                return attribute.nodeValue.orEmpty()
            }
        }
        return ""
    }

    private sealed interface Piece {
        data class Text(val text: String) : Piece
        data class Timed(val text: String, val start: Long, val end: Long) : Piece
    }

    private fun lineFrom(paragraph: Element): SheetLine? {
        val pieces = mutableListOf<Piece>()
        val backing = mutableListOf<Piece>()
        collect(paragraph, pieces, backing)
        val words = merge(pieces)
        val background = merge(backing)
        if (words.words.isEmpty()) {
            val text = paragraph.textContent?.trim().orEmpty()
            val begin = time(paragraph.getAttribute("begin")) ?: return null
            if (text.isEmpty()) return null
            return SheetLine(
                startMs = begin,
                endMs = time(paragraph.getAttribute("end"))?.takeIf { it > begin },
                text = text,
            )
        }
        val begin = time(paragraph.getAttribute("begin"))
            ?: words.words.first().startMs
        val line = SheetLine(
            startMs = minOf(begin, words.words.first().startMs),
            endMs = time(paragraph.getAttribute("end"))
                ?: words.words.last().endMs,
            text = words.text,
            words = words.words,
        )
        if (background.words.isNotEmpty()) {
            line.secondary.add(
                SheetLane(
                    startMs = background.words.first().startMs,
                    endMs = background.words.last().endMs,
                    text = background.text,
                    words = background.words,
                )
            )
        }
        return line
    }

    private fun collect(node: Node, out: MutableList<Piece>, backing: MutableList<Piece>) {
        val children = node.childNodes
        for (i in 0 until children.length) {
            when (val child = children.item(i)) {
                is Element -> {
                    val role = child.qualified("ttm:role")
                    if (role in skippedRoles) continue
                    val sink = if (role == backgroundRole) backing else out
                    val begin = time(child.getAttribute("begin"))
                    val end = time(child.getAttribute("end"))
                    if (begin != null && end != null && !hasTimedChild(child)) {
                        sink.add(Piece.Timed(child.textContent.orEmpty(), begin, end))
                    } else {
                        collect(child, sink, backing)
                    }
                }
                else -> if (child.nodeType == Node.TEXT_NODE) {
                    val text = child.textContent.orEmpty()
                    if (text.isNotEmpty()) out.add(Piece.Text(text))
                }
            }
        }
    }

    private fun hasTimedChild(element: Element): Boolean {
        val children = element.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i) as? Element ?: continue
            if (child.getAttribute("begin").isNotEmpty() || hasTimedChild(child)) return true
        }
        return false
    }

    private fun merge(pieces: List<Piece>): WordRuns {
        val words = mutableListOf<SheetWord>()
        val current = StringBuilder()
        var start = 0L
        var end = 0L
        var timed = false

        fun flush() {
            val text = current.toString().trim()
            current.setLength(0)
            if (text.isNotEmpty() && timed) words.add(SheetWord(start, maxOf(start, end), text))
            timed = false
        }

        pieces.forEach { piece ->
            when (piece) {
                is Piece.Text -> when {
                    piece.text.isBlank() -> flush()
                    timed -> current.append(piece.text)
                    else -> Unit
                }
                is Piece.Timed -> {
                    if (piece.text.isBlank()) return@forEach
                    if (piece.text.first().isWhitespace()) flush()
                    if (current.isEmpty()) start = piece.start
                    current.append(piece.text.trim())
                    end = piece.end
                    timed = true
                    if (piece.text.last().isWhitespace()) flush()
                }
            }
        }
        flush()
        return WordRuns(words, words.joinToString(" ") { it.text })
    }

    fun time(value: String?): Long? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (raw.endsWith("ms")) return raw.dropLast(2).toDoubleOrNull()?.toLong()
        val stripped = raw.removeSuffix("s")
        val parts = stripped.split(':')
        val seconds = when (parts.size) {
            1 -> parts[0].toDoubleOrNull()
            2 -> parts[0].toDoubleOrNull()?.let { m ->
                parts[1].toDoubleOrNull()?.let { m * 60 + it }
            }
            3 -> parts[0].toDoubleOrNull()?.let { h ->
                parts[1].toDoubleOrNull()?.let { m ->
                    parts[2].toDoubleOrNull()?.let { h * 3600 + m * 60 + it }
                }
            }
            else -> null
        } ?: return null
        return (seconds * 1000).toLong()
    }
}

/** Enhanced ("A2") LRC: line stamps plus a stamp in front of every word. */
internal object SourceEnhancedLrc {
    private val line = Regex("""^\[(\d{1,3}):(\d{2})[.:](\d{2,3})](.*)$""")
    private val word = Regex("""<(\d{1,3}):(\d{2})[.:](\d{2,3})>([^<]*)""")
    private const val tailMs = 800L

    fun parse(raw: String): Sheet.Synced? {
        val rows = raw.lineSequence()
            .mapNotNull { line.matchEntire(it.trim()) }
            .map { match ->
                Row(
                    timeMs = stamp(match.groupValues[1], match.groupValues[2], match.groupValues[3]),
                    words = word.findAll(match.groupValues[4]).toList(),
                    plain = match.groupValues[4].trim(),
                )
            }
            .sortedBy { it.timeMs }
            .toList()
        if (rows.isEmpty()) return null
        if (rows.none { it.words.isNotEmpty() }) return null

        val lines = mutableListOf<SheetLine>()
        rows.forEachIndexed { index, row ->
            if (row.words.isEmpty()) {
                val text = decode(row.plain)
                if (text.isNotEmpty()) {
                    lines.add(
                        SheetLine(
                            startMs = row.timeMs,
                            endMs = rows.getOrNull(index + 1)?.timeMs,
                            text = text,
                        )
                    )
                }
                return@forEachIndexed
            }
            val lineEnd = rows.getOrNull(index + 1)?.timeMs
                ?: stamp(row.words.last()) + tailMs
            val runs = row.words.mapIndexed { i, match ->
                val start = stamp(match)
                val end = row.words.getOrNull(i + 1)?.let(::stamp) ?: lineEnd
                TimedRun(start, end, decode(match.groupValues[4]))
            }
            val merged = runsToWords(runs)
            if (merged.words.isNotEmpty()) {
                lines.add(
                    SheetLine(
                        startMs = minOf(row.timeMs, merged.words.first().startMs),
                        endMs = merged.words.last().endMs,
                        text = merged.text,
                        words = merged.words,
                    )
                )
            }
        }
        return lines.takeIf { it.isNotEmpty() }?.let { Sheet.Synced(it) }
    }

    private class Row(val timeMs: Long, val words: List<MatchResult>, val plain: String)

    private fun stamp(match: MatchResult): Long =
        stamp(match.groupValues[1], match.groupValues[2], match.groupValues[3])

    private fun stamp(minutes: String, seconds: String, fraction: String): Long {
        val fractionMs = if (fraction.length == 3) fraction.toLong() else fraction.toLong() * 10
        return minutes.toLong() * 60_000 + seconds.toLong() * 1_000 + fractionMs
    }

    fun decode(text: String): String {
        if ('&' !in text) return text
        return text
            .replace(Regex("&#x([0-9a-fA-F]+);")) {
                runCatching { it.groupValues[1].toInt(16).toChar().toString() }.getOrDefault("")
            }
            .replace(Regex("&#(\\d+);")) {
                runCatching { it.groupValues[1].toInt().toChar().toString() }.getOrDefault("")
            }
            .replace("&apos;", "'")
            .replace("&quot;", "\"")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
    }
}

/** QQ/QRC and NetEase YRC millisecond line and word ranges. */
internal object SourceKaraokeLrc {
    private val line = Regex("""^\[(\d{1,8}),(\d{1,8})](.*)$""")
    private val prefix = Regex("""\((\d{1,8}),(\d{1,8})(?:,\d{1,8})?\)([^()]*)""")
    private val suffix = Regex("""([^()]*)\((\d{1,8}),(\d{1,8})(?:,\d{1,8})?\)""")
    private val wordTime = Regex("""\(\d{1,8},\d{1,8}(?:,\d{1,8})?\)""")
    private val content = Regex("""LyricContent\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)

    fun looksLike(raw: String): Boolean = raw.trimStart().startsWith("[")

    fun parse(raw: String): Sheet.Synced? {
        val rows = content.find(raw)?.groupValues?.get(1)
            ?.replace("&quot;", "\"")?.replace("&apos;", "'")
            ?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&amp;", "&")
            ?: raw
        val lines = rows.lineSequence().mapNotNull { source ->
            val match = line.matchEntire(source.trim()) ?: return@mapNotNull null
            val start = match.groupValues[1].toLong()
            val duration = match.groupValues[2].toLong()
            val body = match.groupValues[3]
            val prefixed = runsToWords(
                prefix.findAll(body).map { word ->
                    val at = word.groupValues[1].toLong()
                    TimedRun(at, at + word.groupValues[2].toLong(), word.groupValues[3])
                }.toList()
            )
            val suffixed = runsToWords(
                suffix.findAll(body).map { word ->
                    val at = word.groupValues[2].toLong()
                    TimedRun(at, at + word.groupValues[3].toLong(), word.groupValues[1])
                }.toList()
            )
            val merged = if (prefixed.text.length >= suffixed.text.length) prefixed else suffixed
            if (merged.words.isEmpty()) return@mapNotNull null
            SheetLine(
                startMs = minOf(start, merged.words.first().startMs),
                endMs = (start + duration).takeIf { duration > 0 },
                text = SourceEnhancedLrc.decode(body.replace(wordTime, "")).trim(),
                words = merged.words,
            )
        }.filter { it.text.isNotBlank() }.sortedBy { it.startMs }.toMutableList()
        return lines.takeIf { it.isNotEmpty() }?.let { Sheet.Synced(it) }
    }
}

/** Parses the many envelopes the small providers wrap their lyrics in. */
internal object SourceLyricsParser {
    private val contentKeys = listOf(
        "ttml", "ttmlContent", "lyrics", "lrc", "content", "text",
        "plainLyrics", "syncedLyrics", "line", "lines", "lyric",
        "data", "result", "response",
    )

    fun parse(raw: String): Sheet? {
        val content = unescape(unwrap(raw) ?: return null)
        val lines = when {
            content.contains(Regex("""<tt(?:\s|>)""", RegexOption.IGNORE_CASE)) ||
                content.contains("http://www.w3.org/ns/ttml", ignoreCase = true) ->
                SourceTtml.parse(content)?.lines.orEmpty()

            SourceKaraokeLrc.looksLike(content) && content.contains(Regex("""\(\d{1,8},\d{1,8}""")) ->
                SourceKaraokeLrc.parse(content)?.lines.orEmpty()

            content.trimStart().startsWith("<") -> emptyList()

            else -> SourceEnhancedLrc.parse(content)?.lines
                ?: Lrc.parse(content).takeIf { it.isNotEmpty() }
                ?: plainLines(content)
        }
        if (lines.isEmpty()) return null
        return if (lines.all { it.startMs == 0L && !it.worded() }) {
            Sheet.Plain(lines.joinToString("\n") { it.text })
        } else {
            Sheet.Synced(lines.toMutableList())
        }
    }

    fun plainLines(content: String): MutableList<SheetLine> {
        if (content.contains(Regex("""(?i)\b(?:lyrics? (?:not found|unavailable)|error)\b"""))) {
            return mutableListOf()
        }
        return content.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .filterNot { it.matches(Regex("""\[[A-Za-z]+:.*]""")) }
            .map { SheetLine(0L, null, it) }
            .toMutableList()
    }

    fun unwrap(raw: String): String? {
        var value = raw.replace("\uFEFF", "").trim()
        if (value.startsWith("```")) {
            value = value.lineSequence().drop(1).toList()
                .let { if (it.lastOrNull()?.trim() == "```") it.dropLast(1) else it }
                .joinToString("\n").trim()
        }
        if (value.isBlank()) return null
        val json = SourceHttp.json(value) ?: return value
        return extract(json)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun extract(element: JsonElement): String? {
        return when (element) {
            is com.google.gson.JsonNull -> null
            is com.google.gson.JsonPrimitive -> if (element.isString) {
                val text = element.asString.trim()
                val nested = SourceHttp.json(text)
                if (nested != null && nested !is com.google.gson.JsonPrimitive) extract(nested) else text
            } else null
            is JsonArray -> element.mapNotNull(::extract).joinToString("\n").takeIf { it.isNotBlank() }
            is JsonObject -> {
                val error = element.get("error")
                if (element.get("isError")?.takeIf { it.isJsonPrimitive }?.asString == "true" ||
                    element.get("ok")?.takeIf { it.isJsonPrimitive }?.asString == "false" ||
                    (error != null && !error.isJsonNull && error.toString() !in setOf("false", "\"\""))
                ) {
                    null
                } else {
                    contentKeys.asSequence().mapNotNull { key ->
                        element.get(key)?.let(::extract)
                    }.firstOrNull()
                        ?: (element.get("metadata") as? JsonObject)?.let(::extract)
                        ?: element.get("words")?.let(::extract)
                }
            }
            else -> null
        }
    }

    private fun unescape(value: String): String = if (value.contains("&lt;tt", ignoreCase = true)) {
        value.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&")
    } else value
}

internal fun plainSheet(text: String): Sheet.Plain? =
    text.trim().takeIf { it.isNotEmpty() }?.let { Sheet.Plain(it) }

internal fun JsonObject.string(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString

internal fun JsonObject.long(key: String): Long? =
    get(key)?.takeIf { it.isJsonPrimitive }?.let {
        runCatching { it.asLong }.getOrNull()
    }

internal fun JsonObject.double(key: String): Double? =
    get(key)?.takeIf { it.isJsonPrimitive }?.let {
        runCatching { it.asDouble }.getOrNull()
    }

internal fun JsonObject.obj(key: String): JsonObject? =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject

internal fun JsonObject.array(key: String): JsonArray? =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray

internal fun lyricsHit(
    source: LyricsSource,
    query: SourceQuery,
    sheet: Sheet?,
    title: String? = null,
    artist: String? = null,
    album: String? = null,
    durationMs: Long? = null,
    writers: List<String> = emptyList(),
): org.sonora.android.api.lyrics.LyricsHit? {
    if (sheet == null || sheet.isEmpty()) return null
    return org.sonora.android.api.lyrics.LyricsHit(
        source = source.label,
        trust = 0,
        lyrics = sheet,
        instrumental = false,
        title = title?.takeIf { it.isNotBlank() } ?: query.title,
        artist = artist?.takeIf { it.isNotBlank() } ?: query.artist,
        album = album?.takeIf { it.isNotBlank() } ?: query.album,
        durationMs = durationMs?.takeIf { it > 0 } ?: query.durationMs.takeIf { it > 0 },
        writers = writers,
    )
}

internal fun SourceQuery.titleForSearch(): String =
    org.sonora.android.api.lyrics.LyricsText.cleanTitle(title).ifBlank { title }

internal fun SourceQuery.artistForSearch(): String =
    org.sonora.android.api.lyrics.LyricsText.cleanArtist(artist).ifBlank { artist }
