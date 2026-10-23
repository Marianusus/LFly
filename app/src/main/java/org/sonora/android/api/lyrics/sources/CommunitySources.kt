package org.sonora.android.api.lyrics.sources

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import org.sonora.android.api.lyrics.LyricsEngine
import org.sonora.android.api.lyrics.LyricsHit
import org.sonora.android.api.lyrics.LyricsQuery
import org.sonora.android.api.lyrics.Sheet
import org.sonora.android.api.lyrics.SheetLine
import org.sonora.android.api.lyrics.TrackKey
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

internal fun SourceQuery.asLyricsQuery(): LyricsQuery = LyricsQuery(
    title = titleForSearch(),
    artist = artistForSearch(),
    album = album?.takeIf { it.isNotBlank() },
    durationMs = durationMs,
    track = videoId?.takeIf { it.isNotBlank() }?.let { TrackKey("youtube", it) },
)

internal object SourceLrcLib {
    suspend fun fetch(query: SourceQuery): LyricsHit? =
        LyricsEngine.lrclib.search(query.asLyricsQuery()).firstOrNull()
            ?.copy(source = LyricsSource.LRCLIB.label)
}

internal object SourceMusixmatch {
    suspend fun fetch(query: SourceQuery): LyricsHit? =
        LyricsEngine.musixmatch.search(query.asLyricsQuery()).firstOrNull()
            ?.copy(source = LyricsSource.MUSIXMATCH.label)
}

internal object SourceKugou {
    suspend fun fetch(query: SourceQuery): LyricsHit? =
        LyricsEngine.kugou.search(query.asLyricsQuery()).firstOrNull()
            ?.copy(source = LyricsSource.KUGOU.label)
}

internal object SourceLyricsPlus {
    private val mirrors = listOf(
        "https://lyricsplus.prjktla.my.id",
        "https://lyricsplus.atomix.one",
        "https://lyricsplus.binimum.org",
        "https://lyricsplus.prjktla.workers.dev",
        "https://lyricsplus-seven.vercel.app",
        "https://lyrics-plus-backend.vercel.app",
    )
    private val lastGood = AtomicReference<String?>(null)

    suspend fun fetch(query: SourceQuery): LyricsHit? = coroutineScope {
        val ordered = lastGood.get()?.let { listOf(it) + mirrors.filterNot { m -> m == it } }
            ?: mirrors
        val pending = ordered.map { host ->
            host to async(Dispatchers.IO) { fetchHost(host, query) }
        }.toMutableList()
        try {
            while (pending.isNotEmpty()) {
                val (host, sheet) = select {
                    pending.forEach { (host, job) -> job.onAwait { host to it } }
                }
                pending.removeAll { it.first == host }
                if (sheet != null) {
                    lastGood.set(host)
                    return@coroutineScope lyricsHit(LyricsSource.LYRICS_PLUS, query, sheet)
                }
            }
            null
        } finally {
            pending.forEach { it.second.cancel() }
        }
    }

    private suspend fun fetchHost(host: String, query: SourceQuery): Sheet? {
        val url = buildString {
            append(host).append("/v2/lyrics/get?title=")
            append(SourceHttp.encode(query.titleForSearch()))
            append("&artist=").append(SourceHttp.encode(query.artistForSearch()))
            if (query.durationMs > 0) append("&duration=").append(query.durationMs / 1000)
            query.album?.takeIf { it.isNotBlank() }?.let {
                append("&album=").append(SourceHttp.encode(it))
            }
            query.isrc?.takeIf { it.isNotBlank() }?.let {
                append("&isrc=").append(SourceHttp.encode(it))
            }
        }
        val raw = SourceHttp.get(url) ?: return null
        return parse(raw)
    }

    fun parse(raw: String): Sheet? {
        val root = SourceHttp.objectOf(raw) ?: return null
        val array = root.array("lyrics") ?: root.array("data") ?: return null
        val lines = mutableListOf<SheetLine>()
        for (element in array) {
            val line = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val start = line.long("time") ?: continue
            val duration = line.long("duration")
            val syllabus = line.array("syllabus") ?: line.array("syllables")
            val runs = syllabus?.mapNotNull { item ->
                val syllable = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val at = syllable.long("time") ?: return@mapNotNull null
                val text = syllable.string("text") ?: return@mapNotNull null
                TimedRun(at, at + (syllable.long("duration") ?: 0L), text)
            }.orEmpty()
            val merged = if (runs.isNotEmpty()) runsToWords(runs, spacingIsExplicit = true) else null
            val text = line.string("text")?.trim()
            if (merged != null && merged.words.isNotEmpty()) {
                lines.add(
                    SheetLine(
                        startMs = minOf(start, merged.words.first().startMs),
                        endMs = merged.words.last().endMs,
                        text = merged.text,
                        words = merged.words,
                    )
                )
            } else if (!text.isNullOrBlank()) {
                lines.add(
                    SheetLine(
                        startMs = start,
                        endMs = duration?.takeIf { it > 0 }?.let { start + it },
                        text = text,
                    )
                )
            }
        }
        lines.sortBy { it.startMs }
        if (lines.isEmpty()) return null
        // Some mirrors answer with the Apple document's lines but every beat at
        // zero. Handing that to the synced view makes the cursor jump straight
        // to the last line while the song is still at the intro. Treat it as
        // plain text until a mirror returns real timings.
        if (lines.none { it.startMs > 0L || !it.words.isNullOrEmpty() }) {
            return Sheet.Plain(lines.joinToString("\n") { it.text })
        }
        return Sheet.Synced(lines)
    }
}

internal object SourceUnison {
    private const val BASE = "https://unison.boidu.dev/lyrics"

    suspend fun fetch(query: SourceQuery): LyricsHit? {
        val url = buildString {
            append(BASE)
            append("?song=").append(SourceHttp.encode(query.titleForSearch()))
            append("&artist=").append(SourceHttp.encode(query.artistForSearch()))
            if (query.durationMs > 0) append("&duration=").append(query.durationMs / 1000)
            query.album?.takeIf { it.isNotBlank() }?.let {
                append("&album=").append(SourceHttp.encode(it))
            }
        }
        val root = SourceHttp.objectOf(SourceHttp.get(url) ?: return null) ?: return null
        val success = root.get("success")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: return null
        if (!success) return null
        val data = root.obj("data") ?: return null
        val text = data.string("lyrics")?.takeIf { it.isNotBlank() } ?: return null
        val format = data.string("format").orEmpty()
        val syncType = data.string("syncType") ?: data.string("sync_type").orEmpty()
        val sheet: Sheet? = when {
            format.equals("ttml", ignoreCase = true) -> SourceTtml.parse(text)
            syncType.equals("plain", ignoreCase = true) -> Sheet.Plain(text)
            else -> SourceEnhancedLrc.parse(text)
                ?: SourceLyricsParser.parse(text)
                ?: SourceLyricsParser.plainLines(text).takeIf { it.isNotEmpty() }?.let {
                    Sheet.Plain(it.joinToString("\n") { line -> line.text })
                }
        }
        return lyricsHit(
            LyricsSource.UNISON,
            query,
            sheet,
            title = data.string("song"),
            artist = data.string("artist"),
            album = data.string("album"),
        )
    }
}

internal object SourceSimpMusic {
    private const val BASE = "https://api-lyrics.simpmusic.org/v1/"

    suspend fun fetch(query: SourceQuery): LyricsHit? {
        val videoId = query.videoId?.takeIf { it.isNotBlank() } ?: return null
        val root = SourceHttp.objectOf(SourceHttp.get(BASE + videoId) ?: return null) ?: return null
        val success = root.get("success")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        if (!success) return null
        val tracks = root.array("data") ?: return null
        val seconds = query.durationMs / 1000
        val track = tracks.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
            .filter { item ->
                seconds <= 0 || abs((item.long("duration") ?: 0L) - seconds) <= 10
            }
            .minByOrNull { abs((it.long("duration") ?: 0L) - seconds) }
            ?: return null
        val rich = track.string("richSyncLyrics")?.takeIf { it.isNotBlank() }
            ?.let { SourceEnhancedLrc.parse(it) }
            ?: track.string("syncedLyrics")?.takeIf { it.isNotBlank() }
                ?.let { SourceLyricsParser.parse(it) }
            ?: track.string("plainLyrics")?.takeIf { it.isNotBlank() }?.let { Sheet.Plain(it) }
        return lyricsHit(LyricsSource.SIMP_MUSIC, query, rich)
    }
}
