package org.sonora.connect

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * LFly Connect: play on one device, control it from any other, and move the music between them.
 *
 * Plain Kotlin on purpose (Gson and the JDK only, nothing from Android), so the phone app and the
 * coming desktop app compile this same file and cannot drift apart.
 *
 * How it works:
 *  - Every LFly announces itself on the local network over DNS-SD as [SERVICE_TYPE], with the TXT
 *    keys [TXT_ID], [TXT_NAME], [TXT_KIND] and [TXT_OWNER], and serves a WebSocket on that port.
 *  - A controller opens a WebSocket to the device it wants to see or command and sends [Hello].
 *    Devices of the same account prove it with [Proof] (an HMAC of the host's nonce under a key
 *    from the account, which never travels), and are trusted at once; any other device is shown to
 *    the host's owner with a short code to accept or refuse, and remembered once accepted.
 *  - No audio travels: the playing device streams by itself. The wire only carries what is playing
 *    ([State]), commands ([Command]) and hand-overs ([Transfer]).
 */
object ConnectProtocol {
    const val VERSION = 1
    const val SERVICE_TYPE = "_lfly-connect._tcp"

    const val TXT_ID = "id"
    const val TXT_NAME = "name"
    const val TXT_KIND = "kind"

    /** Short public tag of the owner's account, so the picker can say "your device"; not proof. */
    const val TXT_OWNER = "owner"

    // ---- Device kinds ----
    const val KIND_PHONE = "phone"
    const val KIND_TABLET = "tablet"
    const val KIND_DESKTOP = "desktop"
    const val KIND_TV = "tv"
    const val KIND_WATCH = "watch"

    // ---- Commands ----
    const val CMD_PLAY = "play"
    const val CMD_PAUSE = "pause"
    const val CMD_TOGGLE = "toggle"
    const val CMD_NEXT = "next"
    const val CMD_PREVIOUS = "previous"

    /** Value: position in ms. */
    const val CMD_SEEK = "seek"

    /** Value: absolute queue index. */
    const val CMD_SKIP_TO = "skip_to"

    /** Value: volume 0..100. */
    const val CMD_VOLUME = "volume"

    /** Value: +1 or -1, one step of the host's own volume scale (the volume keys). */
    const val CMD_VOLUME_STEP = "volume_step"

    /** Stop playing here: another device takes over. */
    const val CMD_RELEASE = "release"

    /** Value: 1 on, 0 off. */
    const val CMD_SHUFFLE = "shuffle"

    /** Value: [REPEAT_OFF], [REPEAT_ONE] or [REPEAT_ALL]. */
    const val CMD_REPEAT = "repeat"

    const val REPEAT_OFF = 0L
    const val REPEAT_ONE = 1L
    const val REPEAT_ALL = 2L

    // ---- Queue edits ([QueueEdit.op]) ----
    /** Append [QueueEdit.tracks]. */
    const val EDIT_ADD = "add"

    /** Insert [QueueEdit.tracks] right after the playing track. */
    const val EDIT_NEXT = "next"

    /** Remove the row at [QueueEdit.index]. */
    const val EDIT_REMOVE = "remove"

    /** Move the row at [QueueEdit.index] to [QueueEdit.to]. */
    const val EDIT_MOVE = "move"

    /** Drop what the listener queued after the playing track. */
    const val EDIT_CLEAR = "clear"

    private val gson = Gson()

    // ---- Messages ----

    sealed interface Message

    /** First message from the controller. */
    data class Hello(val device: Device, val protocol: Int = VERSION) : Message

    /** Host's answer to [Hello]: a nonce to prove the account with, and whether pairing is needed. */
    data class Challenge(val device: Device, val nonce: String) : Message

    /** Controller's proof: HMAC-SHA256(accountKey, nonce) in hex, or null without an account. */
    data class Proof(val hmac: String?) : Message

    /** The host waits for its owner to accept; [code] is shown on both screens to compare. */
    data class PairPending(val code: String) : Message

    /** The session is open (or refused when [accepted] is false). */
    data class Welcome(val accepted: Boolean) : Message

    /** What the host is playing. Sent on change and every few seconds while playing. */
    data class State(
        val deviceId: String,
        val active: Boolean,
        val playing: Boolean,
        val positionMs: Long,
        /** Host clock when [positionMs] was read, to extrapolate between updates. */
        val at: Long,
        val durationMs: Long,
        val index: Int,
        val queue: List<TrackRef>,
        val shuffle: Boolean = false,
        val repeat: String = "off",
        val volume: Int = -1,
        /** Set when the host stopped because the music moved to that device. */
        val handedTo: String? = null
    ) : Message

    data class Command(val action: String, val value: Long = 0) : Message

    /** A change to the host's queue. */
    data class QueueEdit(val op: String, val tracks: List<TrackRef> = emptyList(), val index: Int = -1, val to: Int = -1) : Message

    /** Play this queue here, from [index] at [positionMs]: the music moves to this device. */
    data class Transfer(
        val queue: List<TrackRef>,
        val index: Int,
        val positionMs: Long,
        val playing: Boolean,
        val fromDeviceId: String
    ) : Message

    /**
     * The library (favorites, playlists, history, in the phone's JSON) to merge with the
     * receiver's; the receiver answers with the merged one ([reply]). Same account only.
     */
    data class Library(val library: JsonObject?, val reply: Boolean = false) : Message

    data class Device(val id: String, val name: String, val kind: String, val owner: String? = null)

    /** A track as the wire carries it: enough for any LFly to find and stream it itself. */
    data class TrackRef(
        val id: String,
        val title: String,
        val artist: String,
        val album: String? = null,
        val durationMs: Long = 0,
        val coverUrl: String? = null,
        val provider: String = "Unknown",
        val artistId: String? = null,
        val albumId: String? = null,
        val isVideo: Boolean = false,
        /**
         * Only on hand-overs, for the playing track: the stream the old device already resolved, so
         * the new one starts at once instead of resolving it again (Spotify switches in a second).
         */
        val streamUrl: String? = null
    )

    fun encode(message: Message): String {
        val body = gson.toJsonTree(message).asJsonObject
        body.addProperty("type", typeOf(message))
        return body.toString()
    }

    /** Null for anything this version does not understand: newer peers may send more. */
    fun decode(text: String): Message? = runCatching {
        val json: JsonObject = JsonParser.parseString(text).asJsonObject
        val type = json.get("type")?.asString ?: return null
        val cls = when (type) {
            "hello" -> Hello::class.java
            "challenge" -> Challenge::class.java
            "proof" -> Proof::class.java
            "pair_pending" -> PairPending::class.java
            "welcome" -> Welcome::class.java
            "state" -> State::class.java
            "command" -> Command::class.java
            "transfer" -> Transfer::class.java
            "queue_edit" -> QueueEdit::class.java
            "library" -> Library::class.java
            else -> return null
        }
        gson.fromJson(json, cls)
    }.getOrNull()

    private fun typeOf(message: Message): String = when (message) {
        is Hello -> "hello"
        is Challenge -> "challenge"
        is Proof -> "proof"
        is PairPending -> "pair_pending"
        is Welcome -> "welcome"
        is State -> "state"
        is Command -> "command"
        is Transfer -> "transfer"
        is QueueEdit -> "queue_edit"
        is Library -> "library"
    }

    // ---- Account proof ----

    /** The account key every device signed into the same account derives alike. Never sent. */
    fun accountKey(accountSecret: String): ByteArray = sha256("lfly-connect-key:$accountSecret")

    /** Public tag of the account, for [TXT_OWNER]: says "same account" in the picker, proves nothing. */
    fun ownerTag(accountSecret: String): String = hex(sha256("lfly-connect-owner:$accountSecret")).take(12)

    fun proof(accountKey: ByteArray, nonce: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(accountKey, "HmacSHA256"))
        return hex(mac.doFinal(nonce.toByteArray()))
    }

    /** The four digits both screens show while pairing, from both ids and the nonce. */
    fun pairingCode(hostId: String, clientId: String, nonce: String): String {
        val digest = sha256("$hostId|$clientId|$nonce")
        val n = ((digest[0].toInt() and 0xff) shl 8 or (digest[1].toInt() and 0xff)) % 10_000
        return n.toString().padStart(4, '0')
    }

    private fun sha256(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
