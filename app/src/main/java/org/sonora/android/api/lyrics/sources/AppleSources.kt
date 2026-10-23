package org.sonora.android.api.lyrics.sources

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.sonora.android.api.lyrics.LyricsHit
import org.sonora.android.api.lyrics.Sheet
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

internal object SourceDocuments {
    suspend fun get(query: SourceQuery, url: String): String? =
        query.documents?.get(url) ?: SourceHttp.get(url)
}

object SourceBini {
    private const val BASE = "https://lyrics-api.binimum.org/"

    data class Hit(
        val isrc: String?,
        val lyricsUrl: String?,
        val title: String?,
        val artist: String?,
        val album: String?,
        val durationSec: Double?,
    )

    suspend fun identify(query: SourceQuery): Hit? = withContext(Dispatchers.IO) {
        if (!query.isrc.isNullOrBlank()) {
            fetch(query, "isrc=${SourceHttp.encode(query.isrc)}")
        } else {
            val params = buildList {
                add("track=${SourceHttp.encode(query.titleForSearch())}")
                add("artist=${SourceHttp.encode(query.artistForSearch())}")
                query.album?.takeIf { it.isNotBlank() }?.let { add("album=${SourceHttp.encode(it)}") }
                if (query.durationMs > 0) add("duration=${query.durationMs / 1000}")
            }
            fetch(query, params.joinToString("&"))
        }
    }

    private suspend fun fetch(query: SourceQuery, params: String): Hit? {
        val body = SourceHttp.get("$BASE?$params") ?: return null
        val root = SourceHttp.objectOf(body) ?: return null
        val results = root.array("results") ?: return null
        val first = results.firstOrNull { it.isJsonObject }?.asJsonObject ?: return null
        return Hit(
            isrc = first.string("isrc"),
            lyricsUrl = first.string("lyricsUrl") ?: first.string("lyrics_url"),
            title = first.string("track_name") ?: first.string("trackName"),
            artist = first.string("artist_name") ?: first.string("artistName"),
            album = first.string("album_name") ?: first.string("albumName"),
            durationSec = first.double("duration"),
        )
    }

    suspend fun lyricsFor(query: SourceQuery, hit: Hit): LyricsHit? {
        val url = hit.lyricsUrl?.takeIf { it.isNotBlank() } ?: return null
        val ttml = SourceDocuments.get(query, url) ?: return null
        val sheet = SourceTtml.parse(ttml) ?: return null
        return lyricsHit(
            LyricsSource.BINI_LYRICS,
            query,
            sheet,
            title = hit.title,
            artist = hit.artist,
            album = hit.album,
            durationMs = hit.durationSec?.times(1000)?.toLong(),
        )
    }

    suspend fun fetch(query: SourceQuery, identity: Hit? = null): LyricsHit? {
        val found = identity ?: identify(query) ?: return null
        return lyricsFor(query, found)
    }
}

internal object SourceLrcRed {
    private const val BASE = "https://lrc.red/"
    private val isrcPattern = Regex("[A-Z]{2}[A-Z0-9]{3}\\d{7}")
    private const val toleranceSeconds = 3.0

    fun documentUrl(isrc: String): String? =
        isrc.trim().uppercase(Locale.ROOT).takeIf { isrcPattern.matches(it) }
            ?.let { "${BASE}s/$it.ttml" }

    suspend fun fetch(query: SourceQuery): LyricsHit? {
        val known = query.isrc?.normalizedIsrc()?.let(::documentUrl)
        if (known != null) {
            document(query, known)?.let { return it }
        }
        val searchBody = SourceHttp.get(
            "${BASE}search.json?q=" +
                SourceHttp.encode("${query.titleForSearch()} ${query.artistForSearch()}")
        ) ?: return null
        val root = SourceHttp.objectOf(searchBody) ?: return null
        val hits = root.array("hits") ?: return null
        val chosen = best(hits, query) ?: return null
        val url = chosen.isrc?.let(::documentUrl)?.takeIf { it != known } ?: return null
        val sheet = documentSheet(query, url) ?: return null
        return lyricsHit(
            LyricsSource.LRC_RED,
            query,
            sheet,
            title = chosen.title,
            artist = chosen.artist,
            album = chosen.album,
            durationMs = chosen.duration?.times(1000)?.toLong(),
        )
    }

    private suspend fun document(query: SourceQuery, url: String): LyricsHit? {
        val sheet = documentSheet(query, url) ?: return null
        return lyricsHit(LyricsSource.LRC_RED, query, sheet)
    }

    private suspend fun documentSheet(query: SourceQuery, url: String): Sheet.Synced? {
        val body = SourceDocuments.get(query, url) ?: return null
        return SourceTtml.parse(body)
    }

    private class Hit(
        val isrc: String?,
        val title: String?,
        val artist: String?,
        val album: String?,
        val duration: Double?,
    )

    private fun best(array: JsonArray, query: SourceQuery): Hit? {
        val wantedTitle = coreOf(query.titleForSearch())
        val wantedVersion = versionOf(query.titleForSearch())
        val wantedArtists = artistsOf(query.artistForSearch())
        val seconds = query.durationMs / 1000.0
        val candidates = array.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val hit = Hit(
                isrc = obj.string("isrc"),
                title = obj.string("title"),
                artist = obj.string("artist"),
                album = obj.string("album"),
                duration = obj.double("duration"),
            )
            val name = hit.title ?: return@mapNotNull null
            if (hit.isrc?.let(::documentUrl) == null) return@mapNotNull null
            if (coreOf(name) != wantedTitle) return@mapNotNull null
            if (versionOf(name) != wantedVersion) return@mapNotNull null
            if (wantedArtists.isNotEmpty() &&
                artistsOf(hit.artist.orEmpty()).none { it in wantedArtists }
            ) return@mapNotNull null
            val distance = if (query.durationMs > 0 && hit.duration != null) {
                abs(hit.duration - seconds)
            } else 0.0
            if (distance > toleranceSeconds) return@mapNotNull null
            hit to distance
        }
        return candidates.minByOrNull { it.second }?.first
    }

    private fun coreOf(title: String): String =
        normalized(title.replace(Regex("""[(\[][^)\]]*[)\]]"""), " ").substringBefore(" - "))
            .ifEmpty { normalized(title) }

    private fun versionOf(title: String): Set<String> {
        val extras = Regex("""[(\[][^)\]]*[)\]]""")
            .findAll(title).joinToString(" ") { it.value } +
            " " + title.substringAfter(" - ", "")
        return normalized(extras).split(' ').filter { it in versionWords }.toSet()
    }

    private fun artistsOf(artist: String): Set<String> =
        artist.split(Regex("""\s*(?:,|&|;|/|\s+and\s+|\s+x\s+|\s+with\s+|\s+feat\.?\s+|\s+ft\.?\s+)\s*"""))
            .map(::normalized).filter { it.isNotEmpty() }.toSet()

    private fun normalized(value: String): String =
        java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
            .lowercase(Locale.ROOT)
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .trim()

    private val versionWords = setOf(
        "live", "remix", "remixed", "mix", "acoustic", "unplugged", "instrumental",
        "karaoke", "cappella", "acapella", "demo", "edit", "version", "cover",
        "sped", "slowed", "reverb", "nightcore", "lofi", "orchestral", "extended",
    )
}

internal object SourceBetterLyrics {
    private const val BASE = "https://lyrics-api.boidu.dev/getLyrics"
    private const val PORTATO = "https://lyrics-api.boidu.dev/qq/getLyrics"

    suspend fun fetch(query: SourceQuery, portato: Boolean = false): LyricsHit? {
        val endpoint = if (portato) PORTATO else BASE
        val url = buildString {
            append(endpoint)
            append("?s=").append(SourceHttp.encode(query.titleForSearch()))
            append("&a=").append(SourceHttp.encode(query.artistForSearch()))
            if (query.durationMs > 0) append("&d=").append(query.durationMs / 1000)
            query.album?.takeIf { it.isNotBlank() }?.let {
                append("&al=").append(SourceHttp.encode(it))
            }
        }
        val body = SourceHttp.get(url) ?: return null
        val sheet = SourceLyricsParser.parse(body) ?: return null
        return lyricsHit(
            if (portato) LyricsSource.BETTER_LYRICS_PORTATO else LyricsSource.BETTER_LYRICS,
            query,
            sheet,
        )
    }
}

internal object SourcePaxSenix {
    private const val API = "https://api.paxsenix.org"
    private const val PUBLIC = "https://lyrics.paxsenix.org"
    private const val APPLE_SEARCH = "https://amp-api.music.apple.com/v1/catalog/us/search"
    private val token = AtomicReference<String?>(null)
    private val tokenGate = Mutex()
    private val indexScript = Regex("""/assets/index~[^"]+\.js""")
    private val tokenPattern = Regex("""eyJ[A-Za-z0-9_-]+\.eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""")

    suspend fun fetch(query: SourceQuery): LyricsHit? {
        val id = appleTrackId(query) ?: return null
        val url = "$PUBLIC/apple-music/lyrics?id=${SourceHttp.encode(id)}&ttml=true"
        val raw = SourceHttp.get(url) ?: return null
        val sheet = SourceLyricsParser.parse(raw) ?: return null
        return lyricsHit(LyricsSource.PAXSENIX, query, sheet)
    }

    suspend fun spotify(query: SourceQuery): LyricsHit? {
        val key = query.apiKey?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val headers = mapOf("Authorization" to "Bearer $key")
        val searchUrl = "$API/spotify/search?q=" +
            SourceHttp.encode("${query.titleForSearch()} ${query.artistForSearch()}")
        val search = SourceHttp.get(searchUrl, headers, authenticated = true)
        val id = search?.let(::candidateId)
        val lyrics = if (id != null) {
            SourceHttp.get(
                "$API/lyrics/spotify?id=${SourceHttp.encode(id)}",
                headers,
                authenticated = true,
            )
        } else null
        val sheet = lyrics?.let(SourceLyricsParser::parse)
            ?: SourceHttp.get(
                "$API/lyrics/lrcget?q=" +
                    SourceHttp.encode("${query.titleForSearch()} ${query.artistForSearch()}"),
                headers,
                authenticated = true,
            )?.let(::parseLrcGet)
        return lyricsHit(LyricsSource.PAXSENIX_SPOTIFY, query, sheet)
    }

    suspend fun musixmatch(query: SourceQuery): LyricsHit? {
        val key = query.apiKey?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val headers = mapOf("Authorization" to "Bearer $key")
        val url = buildString {
            append(API).append("/lyrics/musixmatch?t=")
            append(SourceHttp.encode(query.titleForSearch()))
            append("&a=").append(SourceHttp.encode(query.artistForSearch()))
            append("&d=").append(query.durationMs / 1000)
        }
        val raw = SourceHttp.get(url, headers, authenticated = true) ?: return null
        val sheet = SourceLyricsParser.parse(raw)
            ?: SourceHttp.get(
                "$API/lyrics/lrcget?q=" +
                    SourceHttp.encode("${query.titleForSearch()} ${query.artistForSearch()}"),
                headers,
                authenticated = true,
            )?.let(::parseLrcGet)
        return lyricsHit(LyricsSource.PAXSENIX_MUSIXMATCH, query, sheet)
    }

    private suspend fun appleTrackId(query: SourceQuery): String? {
        val bearer = appleToken() ?: return null
        val url = "$APPLE_SEARCH?term=" +
            SourceHttp.encode("${query.titleForSearch()} ${query.artistForSearch()}") +
            "&types=songs&limit=10&l=en-US"
        val body = SourceHttp.get(
            url,
            mapOf(
                "Authorization" to "Bearer $bearer",
                "Origin" to "https://music.apple.com",
                "Referer" to "https://music.apple.com/",
            ),
        ) ?: return null
        val root = SourceHttp.json(body) ?: return null
        return bestApple(root, query)
    }

    private suspend fun appleToken(): String? = token.get() ?: tokenGate.withLock {
        token.get() ?: scrapeToken()?.also(token::set)
    }

    private suspend fun scrapeToken(): String? {
        val page = SourceHttp.get("https://music.apple.com/us/new") ?: return null
        val path = indexScript.find(page)?.value ?: return null
        val script = SourceHttp.get("https://music.apple.com$path") ?: return null
        return tokenPattern.find(script)?.value
    }

    private fun bestApple(root: JsonElement, query: SourceQuery): String? {
        val songs = root.findObjects()
            .filter { it.has("id") && (it.has("attributes") || it.has("name")) }
        val scored = songs.mapNotNull { item ->
            val attributes = item.obj("attributes") ?: item
            val id = item.string("id") ?: return@mapNotNull null
            val title = attributes.string("name") ?: attributes.string("trackName")
                ?: attributes.string("title") ?: return@mapNotNull null
            val artist = attributes.string("artistName") ?: attributes.string("artist")
                ?: attributes.artistNames()
            val duration = attributes.double("durationInMillis")
                ?: attributes.double("durationMs")
                ?: attributes.double("duration")?.times(1000)
            score(query, title, artist, duration, id)
        }
        return scored.maxByOrNull { it.second }?.takeIf { it.second >= 10 }?.first
    }

    private fun candidateId(raw: String): String? {
        val root = SourceHttp.json(raw) ?: return null
        val objects = root.findObjects()
        return objects.firstNotNullOfOrNull { it.string("id") }
    }

    private fun parseLrcGet(raw: String): Sheet? {
        val root = SourceHttp.json(raw) ?: return null
        val entries = when (root) {
            is JsonArray -> root
            is JsonObject -> root.array("lyrics") ?: root.array("data")
            else -> null
        } ?: return SourceLyricsParser.parse(raw)
        return entries.asSequence()
            .mapNotNull { entry ->
                val sheet = SourceLyricsParser.parse(entry.toString()) ?: return@mapNotNull null
                sheet
            }
            .firstOrNull()
    }

    private fun score(
        query: SourceQuery,
        title: String,
        artist: String?,
        durationMs: Double?,
        id: String,
    ): Pair<String, Int> {
        var score = 0
        if (title.contains(query.titleForSearch(), ignoreCase = true) ||
            query.titleForSearch().contains(title, ignoreCase = true)
        ) score += 20
        if (!artist.isNullOrBlank() &&
            (artist.contains(query.artistForSearch(), ignoreCase = true) ||
                query.artistForSearch().contains(artist, ignoreCase = true))
        ) score += 15
        if (durationMs != null && query.durationMs > 0) {
            val drift = abs(durationMs - query.durationMs)
            if (drift < 3_000) score += 10 else if (drift < 10_000) score += 5
        }
        return id to score
    }

    private fun JsonElement.findObjects(): Sequence<JsonObject> = sequence {
        when (this@findObjects) {
            is JsonObject -> {
                yield(this@findObjects)
                for (value in this@findObjects.entrySet()) {
                    yieldAll(value.value.findObjects())
                }
            }
            is JsonArray -> for (value in this@findObjects) yieldAll(value.findObjects())
            else -> Unit
        }
    }

    private fun JsonObject.artistNames(): String? =
        array("artistNames")?.joinToString(", ") {
            if (it.isJsonPrimitive) it.asString else ""
        }?.takeIf { it.isNotBlank() }
}
