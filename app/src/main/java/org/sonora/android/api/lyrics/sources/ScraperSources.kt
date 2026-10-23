package org.sonora.android.api.lyrics.sources

import com.google.gson.JsonObject
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode
import org.sonora.android.api.lyrics.Lrc
import org.sonora.android.api.lyrics.LyricsHit
import org.sonora.android.api.lyrics.Sheet
import java.net.URLEncoder
import java.util.Locale

internal object SourceGenius {
    private const val USER_AGENT = "BitChord"
    private val titleSeparator = Regex("""\s*[-–—:]\s*""")
    private val decorative = Regex("""[♪♫★☆【】《》「」~_]""")
    private val producer = Regex("""(?i)\b(?:prod(?:uced)?\.?(?:\s+by)?)\s+.*$""")
    private val noise = Regex(
        """\s*[(\[]\s*(?:from|feat\.?|ft\.?|featuring|with|prod\.?|produced by|official|lyrical|video|audio|remix|music video|visualizer|mv|hd|4k|hq|full song)[^)\]]*[)\]]|""" +
            """\s*\b(?:official\s+(?:music\s+)?(?:video|audio)|lyrical(?:\s+video)?|full\s+song|4k\s+video|hd\s+video|music\s+video)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val bracketed = Regex("""\s*[\(\[].*?[\)\]]""")
    private val nonAlpha = Regex("[^\\p{L}\\p{N}\\s]")
    private val youMight = Regex("""\d*You might also like""", RegexOption.IGNORE_CASE)
    private val trailingEmbed = Regex("""\d*Embed\s*$""", RegexOption.IGNORE_CASE)

    suspend fun fetch(query: SourceQuery): LyricsHit? {
        val title = clean(query.titleForSearch())
        val artist = clean(query.artistForSearch())
        val url = searchUrl(artist, title)
            ?: searchUrl("", title)
            ?: return null
        val html = SourceHttp.get(
            url,
            mapOf("User-Agent" to USER_AGENT, "Accept-Language" to "en-US,en;q=0.9"),
        ) ?: return null
        val text = parseHtml(html) ?: return null
        return lyricsHit(LyricsSource.GENIUS, query, Sheet.Plain(text))
    }

    private suspend fun searchUrl(artist: String, title: String): String? {
        val query = "$artist $title".trim()
        if (query.isBlank()) return null
        val body = SourceHttp.get(
            "https://genius.com/api/search/multi?q=${URLEncoder.encode(query, "UTF-8")}",
            mapOf("User-Agent" to USER_AGENT),
        ) ?: return null
        val root = SourceHttp.objectOf(body) ?: return null
        val response = root.obj("response") ?: return null
        val sections = response.array("sections") ?: return null
        val songSection = sections.firstOrNull {
            it.isJsonObject && it.asJsonObject.string("type") == "song"
        }?.asJsonObject ?: return null
        val hits = songSection.array("hits") ?: return null
        val candidates = hits.mapNotNull {
            it.takeIf { e -> e.isJsonObject }?.asJsonObject?.obj("result")
        }
        return best(candidates, title, artist)?.string("url")
    }

    private fun best(candidates: List<JsonObject>, title: String, artist: String): JsonObject? {
        val wantedTitle = title.lowercase(Locale.ROOT)
        val wantedArtist = artist.lowercase(Locale.ROOT)
        return candidates.mapNotNull { item ->
            val candidateTitle = item.string("title")?.lowercase(Locale.ROOT).orEmpty()
            val candidateArtist = item.string("artist_names")?.lowercase(Locale.ROOT).orEmpty()
            val titleMatches = wantedTitle.isNotBlank() &&
                (candidateTitle == wantedTitle || candidateTitle.contains(wantedTitle) ||
                    wantedTitle.contains(candidateTitle))
            val artistMatches = wantedArtist.isNotBlank() &&
                (candidateArtist == wantedArtist || candidateArtist.contains(wantedArtist) ||
                    wantedArtist.contains(candidateArtist))
            if (!titleMatches && !artistMatches) return@mapNotNull null
            var score = 0
            if (candidateTitle == wantedTitle) score += 50 else if (titleMatches) score += 25
            if (artistMatches) score += if (candidateArtist == wantedArtist) 40 else 20
            val path = item.string("path").orEmpty()
            if (path.contains("translation", true) && !wantedTitle.contains("translation")) score -= 30
            if (path.contains("türkçe", true) || path.contains("tlumaczenie", true)) score -= 40
            if (path.contains("tracklist", true) || path.contains("album-art", true)) score -= 50
            item to score
        }.maxByOrNull { it.second }?.takeIf { it.second > 0 }?.first
    }

    private fun clean(value: String): String = value
        .replace(decorative, " ")
        .replace(noise, " ")
        .replace(producer, " ")
        .substringBefore(" | ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .ifBlank { value.trim() }

    fun parseHtml(html: String): String? = runCatching {
        val document = Jsoup.parse(html)
        var containers = document.select("div[data-lyrics-container=true]")
        if (containers.isEmpty()) containers = document.select("div.lyrics")
        if (containers.isEmpty()) return@runCatching null
        val text = StringBuilder()
        for (container in containers) {
            container.select(
                "[data-exclude-from-selection=true], .LyricsHeader__Container, " +
                    ".SongBioPreview__Container, .InreadAd__Container, button, script, style",
            ).remove()
            container.select("br").forEach { it.replaceWith(TextNode("\n")) }
            container.select("p").forEach { it.prepend("\n") }
            val part = container.wholeText()
            if (part.isNotBlank()) text.append(part).append('\n')
        }
        val cleaned = text.toString()
            .replace('\u00A0', ' ')
            .replace('\u200B', ' ')
            .replace('\uFEFF', ' ')
            .replace(youMight, "")
            .trim()
            .replace(trailingEmbed, "")
            .trim()
        cleaned.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .joinToString("\n")
            .takeIf { it.isNotBlank() }
    }.getOrNull()
}

internal object SourceMegalobiz {
    private const val BASE = "https://www.megalobiz.com"
    private val link = Regex("""href=["'](/lrc/maker/download/[^"']+)["']""", RegexOption.IGNORE_CASE)
    private val body = Regex(
        """id=["']lrc_[^"']*_details["'][^>]*>(.*?)</span>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    suspend fun fetch(query: SourceQuery): LyricsHit? {
        val searchUrl = "$BASE/searchall?qry=" +
            SourceHttp.encode("${query.artistForSearch()} ${query.titleForSearch()}".trim())
        val results = SourceHttp.get(searchUrl) ?: return null
        val path = link.find(results)?.groupValues?.get(1) ?: return null
        val page = SourceHttp.get(BASE + path.replace("&amp;", "&")) ?: return null
        val raw = body.find(page)?.groupValues?.get(1) ?: return null
        val lrc = raw.replace(Regex("""(?i)<br\s*/?>"""), "\n")
            .replace(Regex("""<[^>]+>"""), "")
            .let(SourceEnhancedLrc::decode)
        val lines = Lrc.parse(lrc).filter { it.text.isNotBlank() }
        return lyricsHit(
            LyricsSource.MEGALOBIZ,
            query,
            lines.takeIf { it.isNotEmpty() }?.let { Sheet.Synced(it.toMutableList()) },
        )
    }
}
