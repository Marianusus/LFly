package org.sonora.android.party

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.sonora.android.api.Track
import org.sonora.android.playback.PartyTransportAction
import org.sonora.android.playback.PlaybackManager
import org.sonora.android.playback.PlaybackState
import kotlin.math.abs
import java.util.concurrent.CopyOnWriteArrayList

enum class PartyRole { IDLE, HOST, GUEST }

data class PartyPeer(val endpointId: String, val nick: String)

data class DiscoveredParty(val endpointId: String, val code: String, val hostNick: String)

data class PartyState(
    val role: PartyRole = PartyRole.IDLE,
    val code: String? = null,
    val selfNick: String = "",
    val hostNick: String? = null,
    val guests: List<PartyPeer> = emptyList(),
    val allowGuestControl: Boolean = true,
    val allowQueueAdd: Boolean = true,
    val syncReady: Boolean = false,
    val nudgeMs: Long = 0L,
    val status: String? = null,
    val busy: Boolean = false,
    val error: String? = null
) {
    val isActive: Boolean get() = role != PartyRole.IDLE
}

/**
 * Orchestrates a synchronized listening "Party" across nearby devices.
 *
 * Discovery/transport is Google Nearby Connections on the [Strategy.P2P_STAR]
 * topology: the host advertises, guests discover (by code or from the nearby
 * list) and connect. Only control metadata travels the wire — every device
 * resolves and plays the audio itself, so no audio is ever relayed.
 */
class PartyManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "PartyManager"
        private const val SERVICE_ID = "org.sonora.android.PARTY"
        private val STRATEGY = Strategy.P2P_STAR
        private const val HEARTBEAT_MS = 4_000L
        private const val PING_INTERVAL_MS = 5_000L
        private const val PREFS = "sonora_party"
        private const val KEY_NUDGE = "nudge_ms"

        @Volatile
        private var instance: PartyManager? = null

        fun getInstance(context: Context): PartyManager =
            instance ?: synchronized(this) {
                instance ?: PartyManager(context.applicationContext).also { instance = it }
            }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val connections: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val playback: PlaybackManager = PlaybackManager.getInstance(context)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val clock = SyncClock()

    private val _state = MutableStateFlow(
        PartyState(selfNick = Build.MODEL ?: "LFly", nudgeMs = prefs.getLong(KEY_NUDGE, 0L))
    )
    val state: StateFlow<PartyState> = _state.asStateFlow()

    private val _discovered = MutableStateFlow<List<DiscoveredParty>>(emptyList())
    val discovered: StateFlow<List<DiscoveredParty>> = _discovered.asStateFlow()

    // Host-side guests + guest-side host
    private val guests = CopyOnWriteArrayList<PartyPeer>()
    private var hostEndpointId: String? = null

    private var hostLoopJob: Job? = null
    private var pingJob: Job? = null
    private var pendingStartJob: Job? = null
    private var seq = 0L
    private var lastHostPlaying = false
    private var connectingEndpointId: String? = null

    // ── Public API ───────────────────────────────────────────────────────────

    fun startHosting() {
        if (_state.value.role != PartyRole.IDLE) return
        val code = PartyCode.generate()
        _state.update { it.copy(role = PartyRole.HOST, code = code, busy = true, error = null, status = null) }
        guests.clear()
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connections.startAdvertising(PartyCode.endpointName(code), SERVICE_ID, connectionCallback, options)
            .addOnSuccessListener {
                Log.i(TAG, "Advertising Party $code")
                _state.update { it.copy(busy = false) }
                startHostLoop()
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "startAdvertising failed", e)
                resetToIdle(e.message)
            }
    }

    fun startDiscovery() {
        if (_state.value.role != PartyRole.IDLE) return
        _state.update { it.copy(role = PartyRole.GUEST, busy = true, error = null, status = null) }
        _discovered.value = emptyList()
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connections.startDiscovery(SERVICE_ID, discoveryCallback, options)
            .addOnSuccessListener { _state.update { it.copy(busy = false) } }
            .addOnFailureListener { e ->
                Log.w(TAG, "startDiscovery failed", e)
                resetToIdle(e.message)
            }
    }

    fun stopDiscovery() {
        if (_state.value.role != PartyRole.GUEST) return
        connections.stopDiscovery()
        _discovered.value = emptyList()
        resetToIdle(null)
    }

    /** Connects to a specific discovered host. */
    fun join(endpointId: String) {
        if (_state.value.role != PartyRole.GUEST) startDiscovery()
        connectingEndpointId = endpointId
        _state.update { it.copy(busy = true, status = null, error = null) }
        connections.requestConnection(localName(), endpointId, connectionCallback)
            .addOnFailureListener { e ->
                Log.w(TAG, "requestConnection failed", e)
                _state.update { it.copy(busy = false, error = e.message) }
            }
    }

    /** Connects to a host after a QR scan or a typed code. */
    fun joinByCode(code: String): Boolean {
        val normalized = PartyCode.normalize(code) ?: return false
        val match = _discovered.value.firstOrNull { it.code == normalized }
        if (match != null) {
            join(match.endpointId)
            return true
        }
        // Not discovered yet: keep scanning and auto-join the first match.
        if (_state.value.role != PartyRole.GUEST) startDiscovery()
        scope.launch {
            repeat(40) {
                val found = _discovered.value.firstOrNull { it.code == normalized }
                if (found != null) {
                    join(found.endpointId)
                    return@launch
                }
                delay(500)
            }
            _state.update { it.copy(error = "code_not_found") }
        }
        return true
    }

    fun leave() {
        val role = _state.value.role
        if (role == PartyRole.HOST) broadcast(PartyMessage.Bye("host_left"))
        else hostEndpointId?.let { send(it, PartyMessage.Bye("guest_left")) }
        connections.stopAdvertising()
        connections.stopDiscovery()
        connections.stopAllEndpoints()
        resetToIdle(null)
    }

    fun setAllowGuestControl(allowed: Boolean) {
        _state.update { it.copy(allowGuestControl = allowed) }
        broadcastPerms()
    }

    fun setAllowQueueAdd(allowed: Boolean) {
        _state.update { it.copy(allowQueueAdd = allowed) }
        broadcastPerms()
    }

    fun setNudge(ms: Long) {
        val clamped = ms.coerceIn(-SyncEngine.NUDGE_RANGE_MS, SyncEngine.NUDGE_RANGE_MS)
        prefs.edit().putLong(KEY_NUDGE, clamped).apply()
        _state.update { it.copy(nudgeMs = clamped) }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    /** Guest action: ask the host to queue a track (no-op unless the host allows it). */
    fun guestAddToQueue(track: Track, asNext: Boolean) {
        val host = hostEndpointId ?: return
        val s = _state.value
        if (!s.allowQueueAdd) return
        send(
            host,
            PartyMessage.Control(
                action = if (asNext) PartyControl.QUEUE_NEXT else PartyControl.QUEUE_ADD,
                track = PartyTrack.from(track)
            )
        )
    }

    // ── Nearby callbacks ─────────────────────────────────────────────────────

    private val connectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.i(TAG, "Connection initiated with ${info.endpointName}")
            connections.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { e -> Log.w(TAG, "acceptConnection failed", e) }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            if (!resolution.status.isSuccess) {
                Log.w(TAG, "Connection to $endpointId failed: ${resolution.status.statusMessage}")
                _state.update { it.copy(busy = false, error = resolution.status.statusMessage) }
                return
            }
            when (_state.value.role) {
                PartyRole.HOST -> {
                    // The guest introduces itself with a Hello; the peer is added then.
                    _state.update { it.copy(busy = false) }
                }
                PartyRole.GUEST -> {
                    hostEndpointId = endpointId
                    connections.stopDiscovery()
                    _discovered.value = emptyList()
                    _state.update { it.copy(busy = false) }
                    send(endpointId, PartyMessage.Hello(_state.value.selfNick))
                    startGuestLoops()
                }
                else -> {}
            }
            connectingEndpointId = null
        }

        override fun onDisconnected(endpointId: String) {
            if (_state.value.role == PartyRole.HOST) {
                guests.removeAll { it.endpointId == endpointId }
                _state.update { it.copy(guests = guests.toList()) }
            } else if (endpointId == hostEndpointId) {
                resetToIdle("host_disconnected")
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            val message = PartyProtocol.decode(bytes) ?: return
            when (_state.value.role) {
                PartyRole.HOST -> handleHostMessage(endpointId, message)
                PartyRole.GUEST -> handleGuestMessage(endpointId, message)
                else -> {}
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val code = PartyCode.codeFromEndpointName(info.endpointName) ?: return
            val entry = DiscoveredParty(endpointId, code, "Host")
            _discovered.update { list ->
                (list.filterNot { it.endpointId == endpointId } + entry).sortedBy { it.hostNick }
            }
        }

        override fun onEndpointLost(endpointId: String) {
            _discovered.update { list -> list.filterNot { it.endpointId == endpointId } }
        }
    }

    // ── Message handling ─────────────────────────────────────────────────────

    private fun handleHostMessage(endpointId: String, message: PartyMessage) {
        when (message) {
            is PartyMessage.Hello -> {
                val existing = guests.firstOrNull { it.endpointId == endpointId }
                if (existing == null) guests.add(PartyPeer(endpointId, message.nick))
                else guests[guests.indexOf(existing)] = existing.copy(nick = message.nick)
                _state.update { it.copy(guests = guests.toList()) }
                send(endpointId, PartyMessage.Welcome(
                    hostNick = _state.value.selfNick,
                    allowGuestControl = _state.value.allowGuestControl,
                    allowQueueAdd = _state.value.allowQueueAdd
                ))
                sendCurrentState(endpointId)
            }
            is PartyMessage.Ping -> send(endpointId, PartyMessage.Pong(message.t0, now()))
            is PartyMessage.Control -> when (message.action) {
                PartyControl.NEXT -> if (_state.value.allowGuestControl) playback.playNext()
                PartyControl.PREVIOUS -> if (_state.value.allowGuestControl) playback.playPrevious()
                PartyControl.PLAY -> if (_state.value.allowGuestControl) playback.play()
                PartyControl.PAUSE -> if (_state.value.allowGuestControl) playback.pause()
                PartyControl.SEEK -> if (_state.value.allowGuestControl) playback.seekTo(message.positionMs)
                PartyControl.QUEUE_ADD -> if (_state.value.allowQueueAdd) {
                    message.track?.let { playback.addToQueue(it.toTrack()) }
                }
                PartyControl.QUEUE_NEXT -> if (_state.value.allowQueueAdd) {
                    message.track?.let { playback.addNext(it.toTrack()) }
                }
            }
            else -> Unit
        }
    }

    private fun handleGuestMessage(endpointId: String, message: PartyMessage) {
        if (hostEndpointId != null && endpointId != hostEndpointId) return
        when (message) {
            is PartyMessage.Welcome -> {
                _state.update {
                    it.copy(
                        hostNick = message.hostNick,
                        allowGuestControl = message.allowGuestControl,
                        allowQueueAdd = message.allowQueueAdd
                    )
                }
            }
            is PartyMessage.Perms -> _state.update {
                it.copy(allowGuestControl = message.allowGuestControl, allowQueueAdd = message.allowQueueAdd)
            }
            is PartyMessage.State -> applyState(message)
            is PartyMessage.Queue -> applyQueue(message)
            is PartyMessage.Pong -> {
                val offset = clock.addRound(message.t0, message.t1, now())
                if (offset != null) _state.update { it.copy(syncReady = true) }
            }
            is PartyMessage.Bye -> resetToIdle("host_left")
            else -> Unit
        }
    }

    private fun applyQueue(message: PartyMessage.Queue) {
        val tracks = message.tracks.map { it.toTrack() }
        if (tracks.isEmpty()) return
        val index = message.currentIndex.coerceIn(0, tracks.lastIndex)
        // Guest queue is mirrored read-only from the host.
        playback.mirrorRemoteQueue(tracks, index)
    }

    private fun applyState(message: PartyMessage.State) {
        val track = message.track?.toTrack()
        val offset = clock.offsetMs() ?: 0L
        val nudge = _state.value.nudgeMs
        val delayMs = SyncEngine.delayUntilStartMs(message.startsAtHostMs, offset, now())
        pendingStartJob?.cancel()
        if (delayMs > 8L) {
            pendingStartJob = scope.launch {
                delay(delayMs)
                val target = SyncEngine.targetPositionMs(
                    statePositionMs = message.positionMs,
                    startsAtHostMs = message.startsAtHostMs,
                    hostTimeAtSendMs = 0L,
                    isPlaying = message.isPlaying,
                    clockOffsetMs = clock.offsetMs() ?: 0L,
                    localNowMs = now(),
                    nudgeMs = nudge
                )
                playback.applyRemoteState(track, target, message.isPlaying)
            }
        } else {
            val target = SyncEngine.targetPositionMs(
                statePositionMs = message.positionMs,
                startsAtHostMs = message.startsAtHostMs,
                hostTimeAtSendMs = 0L,
                isPlaying = message.isPlaying,
                clockOffsetMs = offset,
                localNowMs = now(),
                nudgeMs = nudge
            )
            playback.applyRemoteState(track, target, message.isPlaying)
        }
    }

    // ── Host loops & broadcasting ────────────────────────────────────────────

    private fun startHostLoop() {
        hostLoopJob?.cancel()
        hostLoopJob = scope.launch {
            var lastTrackId: String? = null
            var lastPlaying = false
            var lastIndex = Int.MIN_VALUE
            var lastQueueSig = ""
            var lastPosition = 0L
            var lastTick = now()
            var lastHeartbeat = now()
            while (isActive) {
                val s = playback.playbackState.value
                val tick = now()
                val expected = lastPosition + if (lastPlaying) (tick - lastTick) else 0L
                val seeked = abs(s.positionMs - expected) > 1_200L
                val queueSig = "${s.queue.size}:${s.currentIndex}:${s.queue.firstOrNull()?.id}:${s.queue.lastOrNull()?.id}"
                val changed = s.currentTrack?.id != lastTrackId ||
                    s.isPlaying != lastPlaying ||
                    s.currentIndex != lastIndex ||
                    queueSig != lastQueueSig ||
                    seeked
                if (changed || tick - lastHeartbeat >= HEARTBEAT_MS) {
                    seq++
                    broadcastState(s, startAt = tick, seq = seq)
                    if (queueSig != lastQueueSig || changed) broadcastQueue(s)
                    lastHeartbeat = tick
                    lastTrackId = s.currentTrack?.id
                    lastPlaying = s.isPlaying
                    lastIndex = s.currentIndex
                    lastQueueSig = queueSig
                    lastPosition = s.positionMs
                    lastTick = tick
                }
                delay(500L)
            }
        }
    }

    private fun startGuestLoops() {
        playback.partyGuestMode = true
        playback.partyTransportHandler = { action, positionMs ->
            if (_state.value.role != PartyRole.GUEST) false
            else {
                handleGuestTransport(action, positionMs)
                true
            }
        }
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive) {
                hostEndpointId?.let { send(it, PartyMessage.Ping(now())) }
                delay(PING_INTERVAL_MS)
            }
        }
    }

    private fun handleGuestTransport(action: PartyTransportAction, positionMs: Long) {
        val host = hostEndpointId ?: return
        val s = _state.value
        when (action) {
            PartyTransportAction.PLAY_PAUSE ->
                send(host, PartyMessage.Control(if (lastHostPlaying) PartyControl.PAUSE else PartyControl.PLAY))
            PartyTransportAction.PLAY -> send(host, PartyMessage.Control(PartyControl.PLAY))
            PartyTransportAction.PAUSE -> send(host, PartyMessage.Control(PartyControl.PAUSE))
            PartyTransportAction.NEXT -> if (s.allowGuestControl) send(host, PartyMessage.Control(PartyControl.NEXT))
            PartyTransportAction.PREVIOUS -> if (s.allowGuestControl) send(host, PartyMessage.Control(PartyControl.PREVIOUS))
            PartyTransportAction.SEEK -> if (s.allowGuestControl) {
                send(host, PartyMessage.Control(PartyControl.SEEK, positionMs))
            }
        }
    }

    private fun broadcastState(s: PlaybackState, startAt: Long, seq: Long) {
        lastHostPlaying = s.isPlaying
        broadcast(
            PartyMessage.State(
                track = s.currentTrack?.let { PartyTrack.from(it) },
                positionMs = s.positionMs,
                isPlaying = s.isPlaying,
                startsAtHostMs = startAt,
                seq = seq
            )
        )
    }

    private fun sendCurrentState(endpointId: String) {
        val s = playback.playbackState.value
        send(
            endpointId,
            PartyMessage.State(
                track = s.currentTrack?.let { PartyTrack.from(it) },
                positionMs = s.positionMs,
                isPlaying = s.isPlaying,
                startsAtHostMs = now(),
                seq = ++seq
            )
        )
        broadcastQueueTo(endpointId, s)
    }

    private fun broadcastQueue(s: PlaybackState) {
        val tracks = s.queue.map { PartyTrack.from(it) }
        if (tracks.isEmpty()) return
        broadcast(PartyProtocol.fitQueue(tracks, s.currentIndex))
    }

    private fun broadcastQueueTo(endpointId: String, s: PlaybackState) {
        val tracks = s.queue.map { PartyTrack.from(it) }
        if (tracks.isEmpty()) return
        send(endpointId, PartyProtocol.fitQueue(tracks, s.currentIndex))
    }

    private fun broadcastPerms() {
        val s = _state.value
        broadcast(PartyMessage.Perms(s.allowGuestControl, s.allowQueueAdd))
    }

    private fun broadcast(message: PartyMessage) {
        if (_state.value.role != PartyRole.HOST) return
        val bytes = PartyProtocol.encode(message)
        if (bytes.size > PartyProtocol.MAX_PAYLOAD_BYTES) {
            Log.w(TAG, "Dropping oversized Party payload (${bytes.size} bytes)")
            return
        }
        val payload = Payload.fromBytes(bytes)
        guests.forEach { guest ->
            connections.sendPayload(guest.endpointId, payload)
                .addOnFailureListener { e -> Log.w(TAG, "sendPayload to ${guest.nick} failed", e) }
        }
    }

    private fun send(endpointId: String, message: PartyMessage) {
        val bytes = PartyProtocol.encode(message)
        if (bytes.size > PartyProtocol.MAX_PAYLOAD_BYTES) {
            Log.w(TAG, "Dropping oversized Party payload (${bytes.size} bytes)")
            return
        }
        connections.sendPayload(endpointId, Payload.fromBytes(bytes))
            .addOnFailureListener { e -> Log.w(TAG, "sendPayload failed", e) }
    }

    // ── Lifecycle helpers ────────────────────────────────────────────────────

    private fun resetToIdle(reason: String?) {
        hostLoopJob?.cancel(); hostLoopJob = null
        pingJob?.cancel(); pingJob = null
        pendingStartJob?.cancel(); pendingStartJob = null
        hostEndpointId = null
        connectingEndpointId = null
        guests.clear()
        clock.reset()
        playback.partyTransportHandler = null
        playback.partyGuestMode = false
        _discovered.value = emptyList()
        _state.update {
            PartyState(
                role = PartyRole.IDLE,
                selfNick = it.selfNick,
                nudgeMs = it.nudgeMs,
                error = reason
            )
        }
    }

    private fun localName(): String = "LFly-Guest-" + (Build.MODEL ?: "device")

    private fun now(): Long = SystemClock.elapsedRealtime()
}
