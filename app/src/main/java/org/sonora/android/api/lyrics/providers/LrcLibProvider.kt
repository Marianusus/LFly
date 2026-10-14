package org.sonora.android.api.lyrics.providers

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.sonora.android.api.HttpClientProvider
import org.sonora.android.api.lyrics.Lrc
import org.sonora.android.api.lyrics.LyricsHit
import org.sonora.android.api.lyrics.LyricsProvider
import org.sonora.android.api.lyrics.LyricsQuery
import org.sonora.android.api.lyrics.LyricsText
import org.sonora.android.api.lyrics.Sheet
import org.sonora.android.api.lyrics.SheetLine
import org.sonora.android.api.lyrics.SheetWord
import org.sonora.android.api.lyrics.Voice
import org.yaml.snakeyaml.Yaml
import java.net.URLEncoder

/**
 * LRCLIB provider — direct port of `crates/music/src/lrclib/mod.rs`.
 * Prefers the richer `lyricsfile` (YAML, word-level) over the synced LRC and finally plain text.
 */
class LrcLibProvider : LyricsProvider {

    override val name: String = "LrcLib"

    private val endpoint = "https://lrclib.net/api/search"
    private val agent = "sonora/android (https://github.com/sonorahq/sonora)"

    override suspend fun search(query: LyricsQuery): List<LyricsHit> {
        val title = LyricsText.cleanTitle(query.title).ifBlank { query.title }
        val artist = LyricsText.cleanArtist(query.artist).ifBlank { query.artist }

        // Exact-ish search (title + artist) plus a broader title-only search: the exact artist filter
        // can return a single (possibly translated) entry while the original-language versions are
        // credited to a different artist spelling. The engine drops anything that does not match.
        val exact = fetch("?track_name=${enc(title)}&artist_name=${enc(artist)}")
        val broad = fetch("?q=${enc(title)}")
        val merged = LinkedHashMap<String, JsonObject>()
        (exact + broad).forEach { element ->
            val id = element.get("id")?.takeIf { it.isJsonPrimitive }?.asString
            val key = id ?: (element.get("trackName")?.asString.orEmpty() + "|" + element.get("artistName")?.asString.orEmpty())
            merged.putIfAbsent(key, element)
        }
        return merged.values.mapNotNull { element -> hit(element) }
    }

    private suspend fun fetch(queryString: String): List<JsonObject> {
        val body = HttpClientProvider
            .getJson(endpoint + queryString, mapOf("User-Agent" to agent), HttpClientProvider.fastClient)
            .getOrNull() ?: return emptyList()
        val array: JsonArray = runCatching { JsonParser.parseString(body).asJsonArray }.getOrNull()
            ?: return emptyList()
        return array.mapNotNull { if (it.isJsonObject) it.asJsonObject else null }
    }

    private fun hit(found: JsonObject): LyricsHit? {
        val trackName = found.stringOrNull("trackName").orEmpty()
        val artistName = found.stringOrNull("artistName").orEmpty()
        val albumName = found.stringOrNull("albumName").orEmpty()
        val duration = found.get("duration")?.takeIf { it.isJsonPrimitive }?.asDouble
        val plain = found.stringOrNull("plainLyrics")
        val synced = found.stringOrNull("syncedLyrics")
        val lyricsfile = found.stringOrNull("lyricsfile")
        val instrumental = found.get("instrumental")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false

        val sheet: Sheet? = lyricsfile?.let { filed(it) }
            ?: synced?.let { Lrc.parse(it).takeIf { lines -> lines.isNotEmpty() }?.let { Sheet.Synced(it) } }
            ?: plain?.trim()?.takeIf { it.isNotEmpty() }?.let { Sheet.Plain(it) }

        val lyrics = when {
            sheet != null -> sheet
            instrumental -> Sheet.Plain("")
            else -> return null
        }

        return LyricsHit(
            source = name,
            trust = 0,
            lyrics = lyrics,
            instrumental = instrumental,
            title = trackName,
            artist = artistName,
            album = albumName.takeIf { it.isNotEmpty() },
            durationMs = duration?.let { (it * 1000.0).toLong() },
            writers = emptyList(),
        )
    }

    /** Parses an LRCLIB YAML "lyricsfile" (word-level) into a synced sheet. */
    private fun filed(text: String): Sheet.Synced? {
        val root = runCatching { Yaml().load<Map<String, Any?>>(text) }.getOrNull() ?: return null
        val rawLines = root["lines"] as? List<*> ?: return null
        val lines = mutableListOf<SheetLine>()
        for (raw in rawLines) {
            val map = raw as? Map<*, *> ?: continue
            val lineText = (map["text"] as? String).orEmpty()
            val start = number(map["start_ms"]) ?: continue
            val end = number(map["end_ms"])
            val words = worded(map["words"] as? List<*>)
            lines.add(
                SheetLine(
                    startMs = start,
                    endMs = end,
                    text = lineText,
                    words = words,
                    voice = Voice.LEAD,
                )
            )
        }
        Lrc.normalize(lines)
        return lines.takeIf { it.isNotEmpty() }?.let { Sheet.Synced(it) }
    }

    private fun worded(raw: List<*>?): MutableList<SheetWord>? {
        if (raw == null) return null
        val words = mutableListOf<SheetWord>()
        for (element in raw) {
            val map = element as? Map<*, *> ?: continue
            val start = number(map["start_ms"]) ?: continue
            val end = number(map["end_ms"]) ?: start
            words.add(SheetWord(startMs = start, endMs = end, text = (map["text"] as? String).orEmpty()))
        }
        return words.ifEmpty { null }
    }

    private fun number(value: Any?): Long? = when (value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        val element: JsonElement = get(key) ?: return null
        return if (element.isJsonPrimitive) element.asString else null
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
