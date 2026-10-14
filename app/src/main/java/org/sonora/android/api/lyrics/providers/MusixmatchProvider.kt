package org.sonora.android.api.lyrics.providers

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.sonora.android.SonoraApp
import org.sonora.android.api.HttpClientProvider
import org.sonora.android.api.lyrics.Lrc
import org.sonora.android.api.lyrics.LyricsHit
import org.sonora.android.api.lyrics.LyricsProvider
import org.sonora.android.api.lyrics.LyricsQuery
import org.sonora.android.api.lyrics.LyricsText
import org.sonora.android.api.lyrics.Sheet
import org.sonora.android.api.lyrics.SheetLine
import org.sonora.android.api.lyrics.SheetWord
import java.io.File

/**
 * Musixmatch provider — direct port of `crates/music/src/musixmatch/mod.rs`.
 * Uses the desktop app API: a token from `token.get`, then `macro.subtitles.get` with the
 * richsync namespace to obtain word-level (karaoke) lyrics. Falls back to plain subtitles.
 *
 * The endpoint throttles aggressively, so requests are spaced and a cooldown is applied on
 * a 401/402/403/429 answer.
 */
class MusixmatchProvider : LyricsProvider {

    override val name: String = "Musixmatch"

    private val tokensUrl = "https://apic-desktop.musixmatch.com/ws/1.1/token.get"
    private val subtitlesUrl = "https://apic-desktop.musixmatch.com/ws/1.1/macro.subtitles.get"
    private val app = "web-desktop-app-v1.0"
    private val trust = 35
    private val spacingMs = 1_500L
    private val cooldownMs = 600_000L
    private val agent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val gate = Mutex()
    private var token: String? = null
    private var blockedUntil = 0L
    private var latest = 0L

    private fun now() = System.currentTimeMillis()

    private suspend fun ready(): Boolean = gate.withLock {
        if (blockedUntil != 0L && now() < blockedUntil) return@withLock false
        blockedUntil = 0L
        if (latest != 0L) {
            val since = now() - latest
            if (since < spacingMs) delay(spacingMs - since)
        }
        latest = now()
        true
    }

    private suspend fun block() = gate.withLock { blockedUntil = now() + cooldownMs }

    private suspend fun currentToken(): String? {
        token?.let { return it }
        // Reuse the token cached from a previous launch, like the upstream client, so we
        // don't hit token.get (and its 401/429 cooldown) on every cold start.
        kept()?.let { held ->
            gate.withLock { token = held }
            return held
        }
        return renew()
    }

    private fun tokenFile(): File? {
        val dir = SonoraApp.appCacheDir ?: return null
        return File(dir, "musixmatch_token.txt")
    }

    private suspend fun kept(): String? = withContext(Dispatchers.IO) {
        val file = tokenFile() ?: return@withContext null
        runCatching { file.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private suspend fun remember(value: String) {
        val file = tokenFile() ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(value)
            }
        }
    }

    private suspend fun renew(): String? {
        gate.withLock { token = null }
        val answer = ask(tokensUrl, listOf("format" to "json", "app_id" to app)) ?: return null
        val header = answer.obj("message")?.obj("header")
        val status = header?.num("status_code")?.toInt() ?: -1
        return when {
            status == 200 -> {
                val value = answer.obj("message")?.obj("body")?.str("user_token")
                    ?.takeIf { it.isNotEmpty() && !it.contains("UpgradeOnly") }
                if (value != null) {
                    gate.withLock { token = value }
                    remember(value)
                }
                value
            }
            status == 401 || status == 402 || status == 403 || status == 429 -> {
                block(); null
            }
            else -> null
        }
    }

    private suspend fun ask(endpoint: String, params: List<Pair<String, String>>): JsonObject? {
        val url = buildString {
            append(endpoint)
            append('?')
            append(params.joinToString("&") { "${it.first}=${enc(it.second)}" })
        }
        val body = HttpClientProvider.getJson(
            url,
            mapOf("User-Agent" to agent, "Cookie" to "x-mxm-token-guid="),
            HttpClientProvider.fastClient,
        ).getOrNull() ?: return null
        return runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
    }

    override suspend fun search(query: LyricsQuery): List<LyricsHit> {
        val calls = lookup(query) ?: return emptyList()
        return hit(calls)?.let { listOf(it) } ?: emptyList()
    }

    private suspend fun lookup(query: LyricsQuery): JsonObject? {
        for (renewed in listOf(false, true)) {
            if (!ready()) return null
            val usertoken = if (renewed) renew() ?: return null else currentToken() ?: return null
            val params = mutableListOf(
                "format" to "json",
                "namespace" to "lyrics_richsynched",
                "subtitle_format" to "mxm",
                "optional_calls" to "track.richsync",
                "app_id" to app,
                "usertoken" to usertoken,
            )
            val title = LyricsText.cleanTitle(query.title).ifBlank { query.title }
            val artist = LyricsText.cleanArtist(query.artist).ifBlank { query.artist }
            params.add("q_track" to title)
            params.add("q_artist" to artist)
            query.album?.takeIf { it.isNotEmpty() }?.let { params.add("q_album" to it) }
            if (query.durationMs > 0L) params.add("q_duration" to (query.durationMs / 1000L).toString())

            val answer = ask(subtitlesUrl, params) ?: return null
            val header = answer.obj("message")?.obj("header") ?: return null
            val status = header.num("status_code")?.toInt() ?: -1
            val hint = header.str("hint")
            when {
                status == 200 -> return answer.obj("message")?.obj("body")?.obj("macro_calls")
                status == 401 && hint == "renew" -> continue
                status == 401 || status == 402 || status == 403 || status == 429 -> {
                    block(); return null
                }
                status == 404 -> return null
                else -> return null
            }
        }
        return null
    }

    private fun hit(calls: JsonObject): LyricsHit? {
        val track = answered(calls.obj("matcher.track.get"))?.obj("track") ?: return null
        val quiet = (track.num("instrumental")?.toInt() ?: 0) == 1

        val sung: MutableList<SheetLine> = if (quiet) mutableListOf() else {
            (richsync(calls) ?: subtitles(calls) ?: mutableListOf())
        }
        Lrc.normalize(sung)
        if (sung.isEmpty() && !quiet) return null

        val length = track.num("track_length")?.toLong() ?: 0L
        return LyricsHit(
            source = name,
            trust = trust,
            lyrics = if (quiet) Sheet.Plain("") else Sheet.Synced(sung),
            instrumental = quiet,
            title = track.str("track_name").orEmpty(),
            artist = track.str("artist_name").orEmpty(),
            album = track.str("album_name")?.takeIf { it.isNotEmpty() },
            durationMs = if (length > 0) length * 1000L else null,
            writers = writers(calls),
        )
    }

    private fun richsync(calls: JsonObject): MutableList<SheetLine>? {
        val body = answered(calls.obj("track.richsync.get"))?.obj("richsync") ?: return null
        val json = body.str("richsync_body") ?: return null
        val array = runCatching { JsonParser.parseString(json).asJsonArray }.getOrNull() ?: return null
        val lines = array.mapNotNull { element ->
            if (element.isJsonObject) verse(element.asJsonObject) else null
        }.toMutableList()
        return lines.ifEmpty { null }
    }

    private fun verse(sung: JsonObject): SheetLine {
        val ts = sung.num("ts") ?: 0.0
        val te = sung.num("te") ?: ts
        val start = seconds(ts)
        val end = maxOf(seconds(te), start)
        val tokens = sung.arr("l") ?: JsonArray()
        val words = mutableListOf<SheetWord>()
        for (index in 0 until tokens.size()) {
            val token = tokens[index]
            if (!token.isJsonObject) continue
            val tokenObj = token.asJsonObject
            val text = tokenObj.str("c") ?: continue
            val offset = tokenObj.num("o") ?: 0.0
            val opened = seconds(ts + offset)
            val nextOffset = tokens.getOrNull(index + 1)?.takeIf { it.isJsonObject }
                ?.asJsonObject?.num("o")
            val closed = (nextOffset?.let { seconds(ts + it) } ?: end).coerceAtLeast(opened)
            if (text.all { it.isWhitespace() } && words.isNotEmpty()) {
                val last = words.last()
                last.text += text
                last.endMs = maxOf(last.endMs, closed)
            } else {
                words.add(SheetWord(startMs = opened, endMs = closed, text = text))
            }
        }
        return SheetLine(
            startMs = start,
            endMs = end,
            text = sung.str("x").orEmpty(),
            words = words.ifEmpty { null },
        )
    }

    private fun subtitles(calls: JsonObject): MutableList<SheetLine>? {
        val body = answered(calls.obj("track.subtitles.get"))?.arr("subtitle_list") ?: return null
        val first = body.getOrNull(0)?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val raw = first.obj("subtitle")?.str("subtitle_body")?.trim() ?: return null
        if (raw.isEmpty()) return null

        val cues = runCatching { JsonParser.parseString(raw).asJsonArray }.getOrNull()
        val lines = if (cues != null) cued(cues).toMutableList() else Lrc.parse(raw)
        return lines.ifEmpty { null }
    }

    private fun cued(cues: JsonArray): List<SheetLine> {
        val starts = (0 until cues.size()).map { index ->
            val cue = cues[index].takeIf { it.isJsonObject }?.asJsonObject
            seconds(cue?.obj("time")?.num("total") ?: 0.0)
        }
        return (0 until cues.size()).map { index ->
            val cue = cues[index].asJsonObject
            SheetLine(
                startMs = starts[index],
                endMs = starts.getOrNull(index + 1),
                text = cue.str("text").orEmpty(),
            )
        }
    }

    private fun writers(calls: JsonObject): List<String> {
        val credit = answered(calls.obj("track.richsync.get"))?.obj("richsync")?.str("lyrics_copyright")
            ?: answered(calls.obj("track.subtitles.get"))?.arr("subtitle_list")?.getOrNull(0)
                ?.takeIf { it.isJsonObject }?.asJsonObject?.obj("subtitle")?.str("lyrics_copyright")
            ?: return emptyList()
        val out = mutableListOf<String>()
        for (line in credit.split("\n")) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("Writer(s):")) continue
            for (name in trimmed.removePrefix("Writer(s):").split(",")) {
                val theName = name.trim()
                if (theName.isNotEmpty()) out.add(theName)
            }
        }
        return out
    }

    private fun answered(wrapped: JsonObject?): JsonObject? {
        val message = wrapped?.obj("message") ?: return null
        val status = message.obj("header")?.num("status_code")?.toInt() ?: -1
        return if (status == 200) message.obj("body") else null
    }

    private fun seconds(value: Double): Long = if (value.isFinite() && value > 0) (value * 1000.0).toLong() else 0L

    private fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.arr(key: String): JsonArray? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString }.getOrNull() }

    private fun JsonObject.num(key: String): Double? =
        get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asDouble }.getOrNull() }

    private fun JsonArray.getOrNull(index: Int): JsonElement? =
        if (index in 0 until size()) get(index) else null
}
