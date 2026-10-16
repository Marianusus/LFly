package org.sonora.android.party

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.sonora.android.api.Track

/**
 * Slim track representation exchanged over the Party wire.
 *
 * The stream URL is deliberately NOT transmitted: every device resolves the
 * stream locally through its own provider chain, so no audio is relayed.
 */
data class PartyTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val coverUrl: String?,
    val provider: String
) {
    fun toTrack(): Track = Track(
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        coverUrl = coverUrl,
        streamUrl = null,
        provider = provider
    )

    companion object {
        fun from(track: Track) = PartyTrack(
            id = track.id,
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationMs = track.durationMs,
            coverUrl = track.coverUrl,
            provider = track.provider
        )
    }
}

/** Transport/queue actions a guest may request from the host. */
object PartyControl {
    const val NEXT = "next"
    const val PREVIOUS = "previous"
    const val PLAY = "play"
    const val PAUSE = "pause"
    const val SEEK = "seek"
    const val QUEUE_ADD = "queue_add"
    const val QUEUE_NEXT = "queue_next"
}

/** Messages exchanged between the host and its guests. */
sealed interface PartyMessage {
    data class Hello(val nick: String) : PartyMessage
    data class Welcome(
        val hostNick: String,
        val allowGuestControl: Boolean,
        val allowQueueAdd: Boolean
    ) : PartyMessage

    data class State(
        val track: PartyTrack?,
        val positionMs: Long,
        val isPlaying: Boolean,
        /** Host-clock time (ms) at which [positionMs] is valid; 0 = "now". */
        val startsAtHostMs: Long,
        val seq: Long
    ) : PartyMessage

    data class Queue(val tracks: List<PartyTrack>, val currentIndex: Int) : PartyMessage
    data class Ping(val t0: Long) : PartyMessage
    data class Pong(val t0: Long, val t1: Long) : PartyMessage
    data class Control(
        val action: String,
        val positionMs: Long = 0L,
        val track: PartyTrack? = null
    ) : PartyMessage
    data class Perms(val allowGuestControl: Boolean, val allowQueueAdd: Boolean) : PartyMessage
    data class Bye(val reason: String? = null) : PartyMessage
}

/**
 * JSON wire format for [PartyMessage]. One message per Nearby Connections
 * BYTES payload, so every encode is size-checked against [MAX_PAYLOAD_BYTES].
 */
object PartyProtocol {

    const val VERSION = 1

    /** Nearby Connections rejects a single BYTES payload above 32 KiB; keep a margin. */
    const val MAX_PAYLOAD_BYTES = 30 * 1024

    private const val TYPE = "t"

    fun encode(message: PartyMessage): ByteArray = root(message).toString().toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): PartyMessage? = runCatching {
        val json = JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject
        when (json.get(TYPE)?.asString) {
            "hello" -> PartyMessage.Hello(json.str("nick") ?: "?")
            "welcome" -> PartyMessage.Welcome(
                hostNick = json.str("nick") ?: "?",
                allowGuestControl = json.bool("allowControl") ?: false,
                allowQueueAdd = json.bool("allowQueue") ?: false
            )
            "state" -> PartyMessage.State(
                track = json.get("track")?.takeIf { it.isJsonObject }?.asJsonObject?.let(::readTrack),
                positionMs = json.num("pos") ?: 0L,
                isPlaying = json.bool("playing") ?: false,
                startsAtHostMs = json.num("startsAt") ?: 0L,
                seq = json.num("seq") ?: 0L
            )
            "queue" -> {
                val arr = json.getAsJsonArray("tracks") ?: JsonArray()
                PartyMessage.Queue(
                    tracks = arr.mapNotNull { element -> (element as? JsonObject)?.let(::readTrack) },
                    currentIndex = (json.num("index") ?: 0L).toInt()
                )
            }
            "ping" -> PartyMessage.Ping(json.num("t0") ?: 0L)
            "pong" -> PartyMessage.Pong(json.num("t0") ?: 0L, json.num("t1") ?: 0L)
            "control" -> PartyMessage.Control(
                action = json.str("action") ?: return null,
                positionMs = json.num("pos") ?: 0L,
                track = json.get("track")?.takeIf { it.isJsonObject }?.asJsonObject?.let(::readTrack)
            )
            "perms" -> PartyMessage.Perms(
                allowGuestControl = json.bool("allowControl") ?: false,
                allowQueueAdd = json.bool("allowQueue") ?: false
            )
            "bye" -> PartyMessage.Bye(json.str("reason"))
            else -> null
        }
    }.getOrNull()

    /**
     * Fits the host queue into a single payload: keeps a window around the
     * current index (the guest only needs what is playing plus what is coming).
     */
    fun fitQueue(tracks: List<PartyTrack>, currentIndex: Int): PartyMessage.Queue {
        if (tracks.isEmpty()) return PartyMessage.Queue(emptyList(), -1)
        val index = currentIndex.coerceIn(0, tracks.lastIndex)
        for (radius in intArrayOf(40, 20, 8, 3, 0)) {
            val start = (index - radius).coerceAtLeast(0)
            val end = (index + radius).coerceAtMost(tracks.lastIndex) + 1
            val window = tracks.subList(start, end)
            val message = PartyMessage.Queue(window.toList(), index - start)
            if (encode(message).size <= MAX_PAYLOAD_BYTES || window.size <= 1) return message
        }
        return PartyMessage.Queue(listOf(tracks[index]), 0)
    }

    // ── internals ────────────────────────────────────────────────────────────

    private fun root(message: PartyMessage): JsonObject = JsonObject().apply {
        when (message) {
            is PartyMessage.Hello -> {
                addProperty(TYPE, "hello")
                addProperty("nick", message.nick)
                addProperty("v", VERSION)
            }
            is PartyMessage.Welcome -> {
                addProperty(TYPE, "welcome")
                addProperty("nick", message.hostNick)
                addProperty("allowControl", message.allowGuestControl)
                addProperty("allowQueue", message.allowQueueAdd)
            }
            is PartyMessage.State -> {
                addProperty(TYPE, "state")
                add("track", message.track?.let(::writeTrack) ?: com.google.gson.JsonNull.INSTANCE)
                addProperty("pos", message.positionMs)
                addProperty("playing", message.isPlaying)
                addProperty("startsAt", message.startsAtHostMs)
                addProperty("seq", message.seq)
            }
            is PartyMessage.Queue -> {
                addProperty(TYPE, "queue")
                add("tracks", JsonArray().apply { message.tracks.forEach { add(writeTrack(it)) } })
                addProperty("index", message.currentIndex)
            }
            is PartyMessage.Ping -> {
                addProperty(TYPE, "ping")
                addProperty("t0", message.t0)
            }
            is PartyMessage.Pong -> {
                addProperty(TYPE, "pong")
                addProperty("t0", message.t0)
                addProperty("t1", message.t1)
            }
            is PartyMessage.Control -> {
                addProperty(TYPE, "control")
                addProperty("action", message.action)
                addProperty("pos", message.positionMs)
                message.track?.let { add("track", writeTrack(it)) }
            }
            is PartyMessage.Perms -> {
                addProperty(TYPE, "perms")
                addProperty("allowControl", message.allowGuestControl)
                addProperty("allowQueue", message.allowQueueAdd)
            }
            is PartyMessage.Bye -> {
                addProperty(TYPE, "bye")
                message.reason?.let { addProperty("reason", it) }
            }
        }
    }

    private fun writeTrack(track: PartyTrack) = JsonObject().apply {
        addProperty("id", track.id)
        addProperty("title", track.title)
        addProperty("artist", track.artist)
        track.album?.let { addProperty("album", it) }
        addProperty("duration", track.durationMs)
        track.coverUrl?.let { addProperty("cover", it) }
        addProperty("provider", track.provider)
    }

    private fun readTrack(json: JsonObject): PartyTrack {
        val id = json.str("id") ?: ""
        return PartyTrack(
            id = id,
            title = json.str("title") ?: "",
            artist = json.str("artist") ?: "",
            album = json.str("album"),
            durationMs = json.num("duration") ?: 0L,
            coverUrl = json.str("cover"),
            provider = json.str("provider") ?: "Unknown"
        )
    }

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.num(key: String): Long? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong

    private fun JsonObject.bool(key: String): Boolean? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
}
