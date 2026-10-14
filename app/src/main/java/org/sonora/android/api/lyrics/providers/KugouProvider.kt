package org.sonora.android.api.lyrics.providers

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.sonora.android.api.HttpClientProvider
import org.sonora.android.api.lyrics.Krc
import org.sonora.android.api.lyrics.LyricsHit
import org.sonora.android.api.lyrics.LyricsProvider
import org.sonora.android.api.lyrics.LyricsQuery
import org.sonora.android.api.lyrics.LyricsText
import org.sonora.android.api.lyrics.Sheet
import org.sonora.android.api.lyrics.SheetLine
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Random
import kotlin.math.abs

/**
 * Kugou provider — word-level (KRC karaoke) lyrics.
 *
 * Musixmatch's desktop endpoint only hands out a dummy token here, so this is the engine's real
 * word-by-word source. The catalog search is rate-limited: a stable device cookie plus request
 * spacing keeps it answering, and results are matched locally against the query before the KRC
 * sheet is downloaded.
 */
class KugouProvider : LyricsProvider {

    override val name: String = "Kugou"

    private val tag = "KugouProvider"
    private val agent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private val searchUrl = "https://songsearch.kugou.com/song_search_v2"
    private val krcSearchUrl = "https://krcs.kugou.com/search"
    private val krcDownloadUrl = "https://krcs.kugou.com/download"

    private val mid: String by lazy { md5("sonora-${System.nanoTime()}-${Random().nextInt()}") }
    private val cookie: String get() = "kg_mid=$mid; dfid=1${mid.take(8)}; kg_dfid=1${mid.take(8)}"

    private val gate = Mutex()
    private var latest = 0L
    private val spacingMs = 1_200L

    private suspend fun ready() = gate.withLock {
        val now = System.currentTimeMillis()
        if (latest != 0L) {
            val since = now - latest
            if (since < spacingMs) delay(spacingMs - since)
        }
        latest = System.currentTimeMillis()
    }

    override suspend fun search(query: LyricsQuery): List<LyricsHit> {
        val title = LyricsText.cleanTitle(query.title).ifBlank { query.title }
        val artist = LyricsText.cleanArtist(query.artist).ifBlank { query.artist }

        ready()
        val candidates = songSearch(title).orEmpty()
        var best = pickCandidate(candidates, title, artist, query.durationMs)
        if (best == null && artist.isNotBlank()) {
            // Title-only search can miss the right artist; retry biased with the artist name.
            ready()
            val withArtist = songSearch("$title $artist").orEmpty()
            if (withArtist.isNotEmpty()) {
                best = pickCandidate(withArtist, title, artist, query.durationMs)
            }
        }
        if (best == null) {
            Log.i(tag, "no artist-matching candidate for '$title' - '$artist'")
            return emptyList()
        }
        val hash = best.str("FileHash")?.takeIf { it.isNotBlank() } ?: return emptyList()
        val lengthSec = best.num("Duration") ?: 0.0
        val songName = best.str("SongName").orEmpty().ifBlank { title }
        val singer = best.str("SingerName").orEmpty().ifBlank { artist }.replace('、', ',')
        val album = best.str("AlbumName")?.takeIf { it.isNotBlank() }
        Log.i(tag, "matched '$songName' - '$singer' (hash=${hash.take(8)}, ${lengthSec.toInt()}s)")

        ready()
        val candidate = krcCandidate(songName, hash, lengthSec) ?: run {
            Log.i(tag, "no KRC candidate for '$songName'")
            return emptyList()
        }
        ready()
        val content = download(candidate.first, candidate.second) ?: return emptyList()
        val plain = Krc.decode(content) ?: run {
            Log.i(tag, "cannot decode KRC for '$songName'")
            return emptyList()
        }
        val lines = Krc.parse(plain)
        if (lines.isEmpty()) return emptyList()
        dropIntroCard(lines, title, artist)
        if (lines.isEmpty()) return emptyList()
        // KRC is already word-aligned and clean; the LRC normalizer can rewrite spacing/text and
        // break the word/token mapping the UI relies on, so it is deliberately not applied.
        Log.i(tag, "word-level sheet: ${lines.size} lines '${songName}'")

        return listOf(
            LyricsHit(
                source = name,
                trust = 25,
                lyrics = Sheet.Synced(lines),
                instrumental = false,
                title = songName,
                artist = singer,
                album = album,
                durationMs = if (lengthSec > 0) (lengthSec * 1000).toLong() else null,
            )
        )
    }

    private suspend fun songSearch(title: String): List<JsonObject>? {
        val first = songSearchOnce(title) ?: return null
        if (first.isNotEmpty()) return first
        // The catalog endpoint silently drops answers under light rate-limiting;
        // a single spaced retry recovers most of those misses.
        delay(spacingMs)
        return songSearchOnce(title) ?: first
    }

    private suspend fun songSearchOnce(title: String): List<JsonObject>? {
        val url = buildString {
            append(searchUrl)
            append("?keyword=").append(enc(title))
            append("&page=1&pagesize=5&platform=WebFilter&userid=0&clientver=2000")
            append("&iscorrection=1&privilege_filter=0")
        }
        val body = HttpClientProvider
            .getJson(url, mapOf("User-Agent" to agent, "Cookie" to cookie), HttpClientProvider.fastClient)
            .getOrNull() ?: return null
        val data = runCatching { JsonParser.parseString(body).asJsonObject.getAsJsonObject("data") }
            .getOrNull() ?: return emptyList()
        val lists = data.getAsJsonArray("lists") ?: return emptyList()
        return lists.mapNotNull { element -> if (element.isJsonObject) element.asJsonObject else null }
    }

    private suspend fun krcCandidate(song: String, hash: String, lengthSec: Double): Pair<String, String>? {
        val url = buildString {
            append(krcSearchUrl)
            append("?ver=1&man=yes&client=mobi&keyword=").append(enc(song))
            append("&duration=").append((lengthSec * 1000).toLong())
            append("&hash=").append(hash)
        }
        val body = HttpClientProvider
            .getJson(url, mapOf("User-Agent" to agent, "Cookie" to cookie), HttpClientProvider.fastClient)
            .getOrNull() ?: return null
        val candidates = runCatching {
            JsonParser.parseString(body).asJsonObject.getAsJsonArray("candidates")
        }.getOrNull() ?: return null
        val first = candidates.firstOrNull { it.isJsonObject }?.asJsonObject ?: return null
        val id = first.str("id") ?: return null
        val accesskey = first.str("accesskey") ?: return null
        return id to accesskey
    }

    private suspend fun download(id: String, accesskey: String): String? {
        val url = buildString {
            append(krcDownloadUrl)
            append("?ver=1&client=mobi&id=").append(enc(id))
            append("&accesskey=").append(enc(accesskey))
            append("&fmt=krc&charset=utf8")
        }
        val body = HttpClientProvider
            .getJson(url, mapOf("User-Agent" to agent, "Cookie" to cookie), HttpClientProvider.fastClient)
            .getOrNull() ?: return null
        return runCatching { JsonParser.parseString(body).asJsonObject.str("content") }.getOrNull()
    }

    internal fun pickCandidate(
        candidates: List<JsonObject>,
        title: String,
        artist: String,
        durationMs: Long,
    ): JsonObject? {
        // Only candidates whose singer matches the query artist are considered, so a generic title
        // (e.g. "Talk") can never resolve to another artist's song.
        val artistMatching = candidates.filter { artistMatches(it, artist) }
        if (artistMatching.isEmpty()) return null
        return artistMatching.maxByOrNull { score(it, title, artist, durationMs) }
    }

    private fun artistMatches(candidate: JsonObject, artist: String): Boolean {
        val singers = normalized(candidate.str("SingerName").orEmpty()).split(',')
        val wanted = normalized(artist).split(',')
        if (wanted.all { it.isBlank() }) return false
        return singers.any { s ->
            s.isNotBlank() && wanted.any { w -> w.isNotBlank() && (s == w || s.contains(w) || w.contains(s)) }
        }
    }

    private fun score(candidate: JsonObject, title: String, artist: String, durationMs: Long): Int {
        val songName = normalized(candidate.str("SongName").orEmpty())
        val target = normalized(title)
        var score = when {
            songName.isEmpty() || target.isEmpty() -> 0
            songName == target -> 50
            songName.contains(target) || target.contains(songName) -> 25
            else -> -20
        }
        val singers = normalized(candidate.str("SingerName").orEmpty()).split(',')
        val wanted = normalized(artist).split(',')
        if (singers.any { s -> s.isNotBlank() && wanted.any { w -> w.isNotBlank() && (s == w || s.contains(w) || w.contains(s)) } }) {
            score += 30
        }
        val candidateMs = ((candidate.num("Duration") ?: 0.0) * 1000).toLong()
        if (durationMs > 0 && candidateMs > 0) {
            val drift = abs(candidateMs - durationMs)
            score += when {
                drift <= 5_000 -> 20
                drift > 15_000 -> -15
                else -> 0
            }
        }
        return score
    }

    /** Drops a leading title card ("Title - Artist", sometimes with a remix suffix) Kugou prepends. */
    internal fun dropIntroCard(lines: MutableList<SheetLine>, title: String, artist: String) {
        val first = lines.firstOrNull() ?: return
        val text = normalized(first.text)
        if (text.isEmpty()) return
        val titleLike = normalized(title).isNotEmpty() && text.contains(normalized(title))
        val artistLike = normalized(artist).isNotEmpty() && text.contains(normalized(artist))
        if (first.text.contains(" - ") && (titleLike || artistLike)) lines.removeAt(0)
    }

    private fun normalized(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() }

    private fun md5(value: String): String =
        MessageDigest.getInstance("MD5").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString }.getOrNull() }

    private fun JsonObject.num(key: String): Double? =
        get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asDouble }.getOrNull() }
}
