package org.sonora.android.api.lyrics.sources

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.sonora.android.api.YouTubeMusicClient
import org.sonora.android.api.lyrics.LyricsHit
import org.sonora.android.api.lyrics.Sheet
import org.sonora.android.api.lyrics.SheetLine

internal object SourceYouTube {
    private val client = YouTubeMusicClient()

    suspend fun music(query: SourceQuery): LyricsHit? {
        val videoId = query.videoId?.takeIf { YouTubeMusicClient.isValidVideoId(it) } ?: return null
        val next = client.fetchNextJson(videoId) ?: return null
        val tabs = next.objectsNamed("tabRenderer").toList()
        val lyricsTab = tabs.firstOrNull { tab ->
            tab.youtubeStrings().any { it.equals("Lyrics", ignoreCase = true) }
        }
        val endpoint = lyricsTab?.endpoint() ?: tabs.drop(1).firstNotNullOfOrNull { it.endpoint() }
            ?: return null
        val browseId = endpoint.string("browseId") ?: return null
        val page = client.browseJson(browseId, endpoint.string("params")) ?: return null
        val shelf = page.objectsNamed("musicDescriptionShelfRenderer").firstOrNull() ?: return null
        val text = shelf.get("description")?.youtubeStrings()?.joinToString("").orEmpty().trim()
        if (text.isBlank()) return null
        return lyricsHit(LyricsSource.YOUTUBE_MUSIC, query, Sheet.Plain(text))
    }

    suspend fun transcript(query: SourceQuery): LyricsHit? {
        val videoId = query.videoId?.takeIf { YouTubeMusicClient.isValidVideoId(it) } ?: return null
        val response = client.fetchTranscriptJson(videoId) ?: return null
        val lines = response.objectsNamed("transcriptCueRenderer").mapNotNull { cue ->
            val start = cue.long("startOffsetMs") ?: return@mapNotNull null
            val text = cue.get("cue")?.youtubeStrings()?.joinToString("").orEmpty()
                .trim(' ', '\n', '♪')
            text.takeIf { it.isNotEmpty() }?.let { SheetLine(start, null, it) }
        }.sortedBy { it.startMs }.toMutableList()
        return lyricsHit(
            LyricsSource.YOUTUBE_TRANSCRIPT,
            query,
            lines.takeIf { it.isNotEmpty() }?.let { Sheet.Synced(it) },
        )
    }

    private fun JsonObject.endpoint(): JsonObject? =
        objectsNamed("browseEndpoint").firstOrNull()

    private fun JsonElement.objectsNamed(name: String): Sequence<JsonObject> = sequence {
        when (this@objectsNamed) {
            is JsonObject -> for ((key, value) in this@objectsNamed.entrySet()) {
                if (key == name && value is JsonObject) yield(value)
                yieldAll(value.objectsNamed(name))
            }
            is JsonArray -> for (value in this@objectsNamed) yieldAll(value.objectsNamed(name))
            else -> Unit
        }
    }

    private fun JsonElement.youtubeStrings(): List<String> = when (this) {
        is com.google.gson.JsonPrimitive -> if (isString) listOf(asString) else emptyList()
        is JsonArray -> flatMap { it.youtubeStrings() }
        is JsonObject -> {
            val direct = get("text")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString
                ?: get("simpleText")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.asString
            direct?.let(::listOf) ?: entrySet().flatMap { it.value.youtubeStrings() }
        }
        else -> emptyList()
    }
}
