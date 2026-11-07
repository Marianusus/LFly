package org.sonora.android.connect

import android.content.Context
import android.content.res.Configuration
import android.media.AudioManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.java_websocket.WebSocket
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshake
import org.java_websocket.server.WebSocketServer
import org.sonora.android.api.Track
import org.sonora.android.auth.YouTubeAuth
import org.sonora.android.playback.PartyTransportAction
import org.sonora.android.playback.PlaybackManager
import org.sonora.android.playback.PlaybackState
import org.sonora.android.playback.RemoteEdit
import org.sonora.android.playback.RepeatMode
import org.sonora.connect.ConnectProtocol as C
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Another LFly on the network, as the device picker shows it. */
data class ConnectDevice(
    val id: String,
    val name: String,
    val kind: String,
    /** Signed into the same account as this phone (by the public tag; proven on connect). */
    val sameAccount: Boolean,
    val host: String,
    val port: Int
)

/** What the picker shows while this phone asks another device to accept it. */
data class ConnectPairing(val deviceName: String, val code: String)

/** Another device asking to control this one; [accept] or [refuse] it. */
class ConnectRequest(val deviceName: String, val code: String, val accept: () -> Unit, val refuse: () -> Unit)

/**
 * LFly Connect on Android: this phone as a player others can see and control, and as a remote for
 * the other LFly devices on the network. See [C] for the protocol.
 *
 * The phone always serves (announced over NSD while the app runs); it only looks for other devices
 * while the picker is open. Following a device means: its state is shown in the player as if it
 * played here, and every transport button, queue tap and new queue goes to it.
 */
object ConnectManager {
    private const val TAG = "LFlyConnect"
    private const val PREFS = "lfly_connect"
    private const val KEY_ID = "device_id"
    private const val KEY_TRUSTED = "trusted"

    /** How often a playing host repeats its state, so controllers stay in step. */
    private const val STATE_EVERY_MS = 3_000L

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val random = SecureRandom()
    private lateinit var app: Context
    private lateinit var playback: PlaybackManager
    @Volatile private var started = false

    private lateinit var self: C.Device
    private var server: HostServer? = null
    private var nsd: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null

    private val _devices = MutableStateFlow<List<ConnectDevice>>(emptyList())
    val devices: StateFlow<List<ConnectDevice>> = _devices.asStateFlow()

    /** The device this phone controls, or null when the music plays here. */
    private val _following = MutableStateFlow<ConnectDevice?>(null)
    val following: StateFlow<ConnectDevice?> = _following.asStateFlow()

    /** A device asking to control this phone, for the dialog. */
    private val _request = MutableStateFlow<ConnectRequest?>(null)
    val request: StateFlow<ConnectRequest?> = _request.asStateFlow()

    /** This phone waiting for another device's owner to accept it. */
    private val _pairing = MutableStateFlow<ConnectPairing?>(null)
    val pairing: StateFlow<ConnectPairing?> = _pairing.asStateFlow()

    /** The device the picker is connecting to, for its spinner. */
    private val _connecting = MutableStateFlow<String?>(null)
    val connecting: StateFlow<String?> = _connecting.asStateFlow()

    /** The followed device's volume, 0..100, or -1 while this phone plays. */
    private val _remoteVolume = MutableStateFlow(-1)
    val remoteVolume: StateFlow<Int> = _remoteVolume.asStateFlow()

    private var remote: RemoteLink? = null
    private var stateJob: Job? = null
    private var progressJob: Job? = null
    private const val KEY_LAST_DEVICE = "last_device"

    // ==========================================
    // Start
    // ==========================================

    fun start(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        playback = PlaybackManager.getInstance(app)
        self = C.Device(id = deviceId(), name = deviceName(), kind = deviceKind(), owner = accountSecret()?.let { C.ownerTag(it) })
        scope.launch {
            runCatching { startServer() }.onFailure { Log.w(TAG, "Connect server did not start: ${it.message}") }
        }
        // While this phone plays, controllers hear about every change, and a heartbeat while it plays.
        scope.launch {
            playback.playbackState.collect { if (_following.value == null && changed(it)) server?.broadcastState(stateOf(it)) }
        }
        registerDebugHook()
        // Reopened while the music played on another device: find it again and show it, as Spotify does.
        prefs().getString(KEY_LAST_DEVICE, null)?.let { last ->
            scope.launch {
                delay(1_500)
                main.post { startDiscovery() }
                val found = withTimeoutOrNullCompat(15_000) { devices.first { list -> list.any { it.id == last } } }
                    ?.firstOrNull { it.id == last }
                if (found != null && _following.value == null) resume(found) else forgetLastDevice()
                main.post { stopDiscovery() }
            }
        }
        stateJob = scope.launch {
            while (isActive) {
                delay(STATE_EVERY_MS)
                val state = playback.playbackState.value
                if (_following.value == null && state.isPlaying) server?.broadcastState(stateOf(state))
            }
        }
    }

    private fun startServer() {
        val port = ServerSocket(0).use { it.localPort }
        val host = HostServer(port)
        host.isReuseAddr = true
        host.start()
        server = host
        register(port)
        Log.i(TAG, "Serving as '${self.name}' on port $port")
    }

    // ==========================================
    // Network discovery (NSD = DNS-SD)
    // ==========================================

    private fun register(port: Int) {
        val manager = app.getSystemService(NsdManager::class.java) ?: return
        nsd = manager
        val info = NsdServiceInfo().apply {
            serviceName = "LFly ${self.name}"
            serviceType = C.SERVICE_TYPE
            setPort(port)
            setAttribute(C.TXT_ID, self.id)
            setAttribute(C.TXT_NAME, self.name)
            setAttribute(C.TXT_KIND, self.kind)
            self.owner?.let { setAttribute(C.TXT_OWNER, it) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { Log.d(TAG, "Announced as ${info.serviceName}") }
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) { Log.w(TAG, "Announce failed: $error") }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
        }
        registration = listener
        manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    /** Looks for other LFly devices; the picker calls this when it opens. */
    fun startDiscovery() {
        if (!started || discovery != null) return
        val manager = nsd ?: app.getSystemService(NsdManager::class.java) ?: return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Discovery failed: $errorCode")
                discovery = null
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) = resolve(manager, info)
            override fun onServiceLost(info: NsdServiceInfo) {
                val name = info.serviceName
                _devices.update { list -> list.filterNot { "LFly ${it.name}" == name } }
            }
        }
        discovery = listener
        manager.discoverServices(C.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    /** True while looking for devices, so a short search for the watch never ends the picker's. */
    val isDiscovering: Boolean
        get() = discovery != null

    fun stopDiscovery() {
        val listener = discovery ?: return
        discovery = null
        runCatching { nsd?.stopServiceDiscovery(listener) }
    }

    /**
     * Development builds only: emulators sit behind their own NAT and cannot see each other over
     * NSD, so a test adds a device by address:
     * `adb shell am broadcast -a org.sonora.android.debug.CONNECT_ADD --es host 10.0.2.2 --ei port 45001 --es name "Emu B"`
     */
    private fun registerDebugHook() {
        if (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: android.content.Intent) {
                val host = intent.getStringExtra("host") ?: return
                val port = intent.getIntExtra("port", 0).takeIf { it > 0 } ?: return
                val name = intent.getStringExtra("name") ?: "$host:$port"
                val device = ConnectDevice("manual-$host:$port", name, intent.getStringExtra("kind") ?: C.KIND_PHONE, false, host, port)
                _devices.update { list -> list.filterNot { it.id == device.id } + device }
                Log.i(TAG, "Debug: added '$name' at $host:$port")
            }
        }
        val filter = android.content.IntentFilter("org.sonora.android.debug.CONNECT_ADD")
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") app.registerReceiver(receiver, filter)
    }

    /** Older Android resolves one service at a time; the queue keeps them in line. */
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    private fun resolve(manager: NsdManager, info: NsdServiceInfo) {
        synchronized(resolveQueue) {
            resolveQueue.addLast(info)
            if (resolving) return
            resolving = true
        }
        resolveNext(manager)
    }

    @Suppress("DEPRECATION")
    private fun resolveNext(manager: NsdManager) {
        val next = synchronized(resolveQueue) {
            resolveQueue.removeFirstOrNull().also { if (it == null) resolving = false }
        } ?: return
        manager.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = resolveNext(manager)
            override fun onServiceResolved(info: NsdServiceInfo) {
                onResolved(info)
                resolveNext(manager)
            }
        })
    }

    @Suppress("DEPRECATION")
    private fun onResolved(info: NsdServiceInfo) {
        val attrs = info.attributes
        fun attr(key: String) = attrs[key]?.decodeToString()
        val id = attr(C.TXT_ID) ?: return
        if (id == self.id) return
        // IPv4 first: a computer also announces IPv6 (often link-local, unusable in a ws:// URL
        // without its scope) and loopback addresses.
        val addresses = if (android.os.Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
        val usable = addresses.filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
        val host = (usable.firstOrNull { it is java.net.Inet4Address } ?: usable.firstOrNull() ?: addresses.firstOrNull())
            ?.hostAddress?.substringBefore('%') ?: return
        val device = ConnectDevice(
            id = id,
            name = attr(C.TXT_NAME) ?: info.serviceName.removePrefix("LFly "),
            kind = attr(C.TXT_KIND) ?: C.KIND_PHONE,
            sameAccount = self.owner != null && attr(C.TXT_OWNER) == self.owner,
            host = host,
            port = info.port
        )
        Log.i(TAG, "Found '${device.name}' (${device.kind}) at $host:${info.port}${if (device.sameAccount) ", same account" else ""}")
        _devices.update { list -> (list.filterNot { it.id == id } + device).sortedBy { it.name.lowercase() } }
    }

    // ==========================================
    // Picking where the music plays
    // ==========================================

    /** Moves the music to [device] and controls it from here, like picking a speaker in Spotify. */
    fun playOn(device: ConnectDevice) {
        if (_following.value?.id == device.id) return
        _connecting.value = device.id
        // What moves: the music playing here, or on the device this phone controls now.
        val previous = remote
        val wasFollowing = _following.value != null
        val moving: C.Transfer? = if (wasFollowing) {
            previous?.lastState?.takeIf { it.queue.isNotEmpty() }?.let { s ->
                C.Transfer(s.queue, s.index.coerceAtLeast(0), positionNow(previous), s.playing, s.deviceId)
            }
        } else {
            handOver(playback.playbackState.value)
        }
        val link = RemoteLink(device) { ready ->
            _connecting.value = null
            if (!ready) return@RemoteLink
            if (wasFollowing) {
                previous?.send(C.Command(C.CMD_RELEASE))
                previous?.closeQuietly()
            } else if (moving != null) {
                main.post { playback.pause() }
            }
            moving?.let { remote?.send(it) }
            follow(device)
        }
        remote = link
        link.connect()
    }

    /** Everything another device needs to play on from here: queue, position and the playing stream. */
    private fun handOver(s: PlaybackState): C.Transfer? {
        if (s.queue.isEmpty()) return null
        val current = s.currentIndex.coerceAtLeast(0)
        return C.Transfer(
            s.queue.mapIndexed { i, track ->
                // The playing track carries the stream it plays from, when it is the same one.
                val playing = s.currentTrack?.takeIf { i == current && it.id == track.id && !it.streamUrl.isNullOrBlank() }
                (playing ?: track).toRef(withStream = i == current)
            },
            current, s.positionMs, s.isPlaying, self.id
        )
    }

    /** Where the followed device's playhead is now, by this phone's clock. */
    private fun positionNow(link: RemoteLink?): Long {
        val state = link?.lastState ?: return 0
        return state.positionMs + if (state.playing) (System.currentTimeMillis() - link.receivedAt).coerceAtLeast(0) else 0
    }

    /** Takes control of [device] again without moving anything (the app was reopened). */
    private fun resume(device: ConnectDevice) {
        val link = RemoteLink(device) { ready -> if (!ready) forgetLastDevice() }
        // Only if the music is still there: a device that plays nothing gives the player back.
        link.onFirstState = { state ->
            if (state.queue.isNotEmpty() && state.active) {
                follow(device)
                onRemoteState(device, state)
            } else {
                Log.i(TAG, "'${device.name}' has nothing playing: staying here")
                forgetLastDevice()
                link.closeQuietly()
                if (remote === link) remote = null
            }
        }
        remote = link
        link.connect()
    }

    /** Brings the music back to this phone from the device it plays on, from where it was. */
    fun playHere() {
        val link = remote ?: return
        val state = link.lastState
        val position = positionNow(link)
        unfollow()
        remote = null
        scope.launch {
            val handed = kotlinx.coroutines.CompletableDeferred<C.Transfer>()
            link.onHandOver = { handed.complete(it) }
            link.send(C.Command(C.CMD_RELEASE))
            val transfer = withTimeoutOrNullCompat(2_500) { handed.await() }
            link.closeQuietly()
            when {
                transfer != null -> main.post { receiveTransfer(transfer) }
                state != null && state.queue.isNotEmpty() ->
                    main.post { playback.playHandedOver(state.queue.map { it.toTrack() }, state.index, position, state.playing) }
            }
        }
    }

    private fun follow(device: ConnectDevice) {
        Log.i(TAG, "Now controlling '${device.name}'")
        _following.value = device
        main.post {
            playback.connectTransportHandler = { action, value -> sendTransport(action, value) }
            playback.connectPlayHandler = { tracks, index ->
                remote?.send(C.Transfer(tracks.map { it.toRef() }, index, 0, true, self.id))
                true
            }
            playback.connectSkipHandler = { index ->
                remote?.send(C.Command(C.CMD_SKIP_TO, index.toLong()))
                true
            }
            playback.connectEditHandler = { edit -> sendEdit(edit) }
        }
        prefs().edit().putString(KEY_LAST_DEVICE, device.id).apply()
        // The progress bar runs smoothly between the device's updates.
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                delay(500)
                val link = remote ?: continue
                val state = link.lastState ?: continue
                if (state.playing) {
                    val position = positionNow(link)
                    main.post { playback.showRemoteState(playback.playbackState.value.queue, state.index, true, position, state.durationMs) }
                }
            }
        }
    }

    private fun forgetLastDevice() = prefs().edit().remove(KEY_LAST_DEVICE).apply()

    private fun sendEdit(edit: RemoteEdit): Boolean {
        val link = remote ?: return false
        Log.i(TAG, "-> '${link.device.name}': $edit")
        link.send(
            when (edit) {
                is RemoteEdit.Add -> C.QueueEdit(C.EDIT_ADD, edit.tracks.map { it.toRef() })
                is RemoteEdit.Next -> C.QueueEdit(C.EDIT_NEXT, listOf(edit.track.toRef()))
                is RemoteEdit.Remove -> C.QueueEdit(C.EDIT_REMOVE, index = edit.index)
                is RemoteEdit.Move -> C.QueueEdit(C.EDIT_MOVE, index = edit.from, to = edit.to)
                RemoteEdit.ClearUpcoming -> C.QueueEdit(C.EDIT_CLEAR)
                is RemoteEdit.Shuffle -> C.Command(C.CMD_SHUFFLE, if (edit.on) 1 else 0)
                is RemoteEdit.Repeat -> C.Command(
                    C.CMD_REPEAT,
                    when (edit.mode) {
                        RepeatMode.ONE -> C.REPEAT_ONE
                        RepeatMode.ALL -> C.REPEAT_ALL
                        else -> C.REPEAT_OFF
                    }
                )
            }
        )
        return true
    }

    /** The followed device's volume from the picker's slider, 0..100. */
    fun setRemoteVolume(percent: Int) {
        val link = remote ?: return
        val value = percent.coerceIn(0, 100)
        _remoteVolume.value = value
        link.send(C.Command(C.CMD_VOLUME, value.toLong()))
    }

    /** The phone's volume keys while another device plays: they turn that device up or down. */
    fun nudgeRemoteVolume(up: Boolean): Boolean {
        val link = remote ?: return false
        if (_following.value == null) return false
        // One step of the device's own scale; the number shown is corrected by its answer.
        val current = _remoteVolume.value.takeIf { it >= 0 } ?: 50
        _remoteVolume.value = (current + if (up) 7 else -7).coerceIn(0, 100)
        link.send(C.Command(C.CMD_VOLUME_STEP, if (up) 1 else -1))
        return true
    }

    private fun unfollow() {
        _following.value = null
        _remoteVolume.value = -1
        progressJob?.cancel()
        forgetLastDevice()
        main.post {
            playback.connectTransportHandler = null
            playback.connectPlayHandler = null
            playback.connectSkipHandler = null
            playback.connectEditHandler = null
        }
    }

    private fun sendTransport(action: PartyTransportAction, value: Long): Boolean {
        val link = remote ?: return false
        Log.i(TAG, "-> '${link.device.name}': $action $value")
        val command = when (action) {
            PartyTransportAction.PLAY_PAUSE -> C.Command(C.CMD_TOGGLE)
            PartyTransportAction.PLAY -> C.Command(C.CMD_PLAY)
            PartyTransportAction.PAUSE -> C.Command(C.CMD_PAUSE)
            PartyTransportAction.NEXT -> C.Command(C.CMD_NEXT)
            PartyTransportAction.PREVIOUS -> C.Command(C.CMD_PREVIOUS)
            PartyTransportAction.SEEK -> C.Command(C.CMD_SEEK, value)
        }
        link.send(command)
        // Shown at once, as Spotify does; the device's next state confirms it.
        link.lastState?.let { state ->
            val now = System.currentTimeMillis()
            val optimistic = when (action) {
                PartyTransportAction.SEEK -> state.copy(positionMs = value)
                PartyTransportAction.PLAY_PAUSE -> state.copy(positionMs = positionNow(link), playing = !state.playing)
                PartyTransportAction.PLAY -> state.copy(positionMs = positionNow(link), playing = true)
                PartyTransportAction.PAUSE -> state.copy(positionMs = positionNow(link), playing = false)
                else -> null
            }
            if (optimistic != null) {
                link.lastState = optimistic
                link.receivedAt = now
                main.post {
                    playback.showRemoteState(
                        playback.playbackState.value.queue, optimistic.index, optimistic.playing,
                        optimistic.positionMs, optimistic.durationMs
                    )
                }
            }
        }
        return true
    }

    /** The followed device's state, shown in the player as if it played here. */
    private fun onRemoteState(device: ConnectDevice, state: C.State) {
        if (_following.value?.id != device.id) return
        if (!state.active && state.handedTo != null) {
            // The music moved on: to this phone (a Transfer is on its way) or to a third device.
            if (state.handedTo == self.id) {
                unfollow()
                remote?.closeQuietly()
                remote = null
            } else {
                _devices.value.firstOrNull { it.id == state.handedTo }?.let { playOn(it) }
            }
            return
        }
        if (state.volume >= 0) _remoteVolume.value = state.volume
        val position = state.positionMs
        val repeat = when (state.repeat) {
            "one" -> RepeatMode.ONE
            "all" -> RepeatMode.ALL
            else -> RepeatMode.OFF
        }
        main.post {
            playback.showRemoteState(state.queue.map { it.toTrack() }, state.index, state.playing, position, state.durationMs, state.shuffle, repeat)
        }
    }

    /** The followed device went away. */
    private fun onRemoteLost(device: ConnectDevice) {
        if (_following.value?.id != device.id) return
        // A Wi-Fi blip: try again a few times before handing the player back.
        scope.launch {
            repeat(4) { attempt ->
                delay(1_500L * (attempt + 1))
                if (_following.value?.id != device.id) return@launch
                Log.i(TAG, "Reconnecting to '${device.name}' (${attempt + 1})")
                val again = kotlinx.coroutines.CompletableDeferred<Boolean>()
                val link = RemoteLink(device) { again.complete(it) }
                remote = link
                link.connect()
                if (withTimeoutOrNullCompat(6_000) { again.await() } == true) return@launch
                link.closeQuietly()
            }
            giveUp(device)
        }
    }

    private fun giveUp(device: ConnectDevice) {
        if (_following.value?.id == device.id) {
            Log.i(TAG, "Lost '${device.name}'")
            unfollow()
            main.post { playback.showRemoteState(playback.playbackState.value.queue, playback.playbackState.value.currentIndex, false, playback.playbackState.value.positionMs, 0) }
        }
    }

    // ==========================================
    // This phone as the host
    // ==========================================

    /** [sameAccount]: proved the account (not only paired with a code); only then the library is shared. */
    private class Session(var device: C.Device? = null, val nonce: String, var trusted: Boolean = false, var sameAccount: Boolean = false)

    private class HostServer(port: Int) : WebSocketServer(InetSocketAddress(port)) {
        private val sessions = ConcurrentHashMap<WebSocket, Session>()

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            sessions[conn] = Session(nonce = newNonce())
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
            sessions.remove(conn)
        }

        override fun onError(conn: WebSocket?, ex: Exception) {
            Log.w(TAG, "Host: ${ex.message}")
        }

        override fun onStart() = Unit

        override fun onMessage(conn: WebSocket, text: String) {
            val session = sessions[conn] ?: return
            when (val message = C.decode(text)) {
                is C.Hello -> {
                    session.device = message.device
                    conn.send(C.encode(C.Challenge(self, session.nonce)))
                }
                is C.Proof -> {
                    val device = session.device ?: return
                    val key = accountSecret()?.let { C.accountKey(it) }
                    val sameAccount = key != null && message.hmac != null && message.hmac == C.proof(key, session.nonce)
                    session.sameAccount = sameAccount
                    if (sameAccount || isTrusted(device.id)) {
                        admit(conn, session)
                    } else {
                        val code = C.pairingCode(self.id, device.id, session.nonce)
                        conn.send(C.encode(C.PairPending(code)))
                        _request.value = ConnectRequest(
                            deviceName = device.name,
                            code = code,
                            accept = {
                                _request.value = null
                                trust(device.id)
                                admit(conn, session)
                            },
                            refuse = {
                                _request.value = null
                                conn.send(C.encode(C.Welcome(false)))
                                conn.close()
                            }
                        )
                    }
                }
                is C.Command -> if (session.trusted) {
                    Log.i(TAG, "Command from '${session.device?.name}': ${message.action} ${message.value}")
                    if (message.action == C.CMD_RELEASE) {
                        // The music goes back to that device: hand it everything, the stream included,
                        // so it starts at once.
                        main.post {
                            handOver(playback.playbackState.value)?.let { conn.send(C.encode(it)) }
                            runCommand(message)
                        }
                    } else {
                        main.post { runCommand(message) }
                    }
                }
                is C.QueueEdit -> if (session.trusted) {
                    Log.i(TAG, "Queue edit from '${session.device?.name}': ${message.op}")
                    main.post { runEdit(message) }
                }
                is C.Library -> if (session.trusted && session.sameAccount && !message.reply) {
                    val offered = message.library ?: return
                    val device = session.device
                    // Off the socket thread: the merge reads and writes the library.
                    scope.launch {
                        val merged = runCatching {
                            org.sonora.android.account.LibrarySync.answer(org.sonora.android.SonoraApp.instance.musicRepository, device?.id ?: return@launch, device.name, offered)
                        }.onFailure { Log.w(TAG, "Library sync failed", it) }.getOrNull()
                        if (merged != null && conn.isOpen) runCatching { conn.send(C.encode(C.Library(merged, reply = true))) }
                    }
                }
                is C.Transfer -> if (session.trusted) {
                    Log.i(TAG, "Music moved here from '${session.device?.name}': ${message.queue.size} tracks, #${message.index} at ${message.positionMs} ms")
                    main.post { receiveTransfer(message) }
                }
                else -> Unit
            }
        }

        private fun admit(conn: WebSocket, session: Session) {
            session.trusted = true
            Log.i(TAG, "'${session.device?.name}' connected")
            conn.send(C.encode(C.Welcome(true)))
            conn.send(C.encode(stateOf(playback.playbackState.value)))
        }

        fun broadcastState(state: C.State) {
            val text = C.encode(state)
            sessions.forEach { (conn, session) -> if (session.trusted && conn.isOpen) runCatching { conn.send(text) } }
        }
    }

    /** A controller's command, run on this phone's player. */
    private fun runCommand(command: C.Command) {
        // A phone that controls another device hands commands meant for it to that device.
        when (command.action) {
            C.CMD_PLAY -> playback.play()
            C.CMD_PAUSE -> playback.pause()
            C.CMD_TOGGLE -> playback.togglePlayPause()
            C.CMD_NEXT -> playback.playNext()
            C.CMD_PREVIOUS -> playback.playPrevious()
            C.CMD_SEEK -> playback.seekTo(command.value)
            C.CMD_SKIP_TO -> playback.playFromQueueIndex(command.value.toInt())
            C.CMD_VOLUME -> {
                setVolume(command.value.toInt())
                server?.broadcastState(stateOf(playback.playbackState.value))
            }
            C.CMD_VOLUME_STEP -> {
                app.getSystemService(AudioManager::class.java)?.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if (command.value > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                    0
                )
                server?.broadcastState(stateOf(playback.playbackState.value))
            }
            C.CMD_SHUFFLE -> if ((command.value == 1L) != playback.playbackState.value.isShuffle) playback.toggleShuffle()
            C.CMD_REPEAT -> playback.setRepeatMode(
                when (command.value) {
                    C.REPEAT_ONE -> RepeatMode.ONE
                    C.REPEAT_ALL -> RepeatMode.ALL
                    else -> RepeatMode.OFF
                }
            )
            C.CMD_RELEASE -> {
                playback.pause()
                server?.broadcastState(stateOf(playback.playbackState.value).copy(active = false))
            }
        }
    }

    private fun runEdit(edit: C.QueueEdit) {
        val tracks = edit.tracks.map { it.toTrack() }
        when (edit.op) {
            C.EDIT_ADD -> if (tracks.isNotEmpty()) playback.addToQueue(tracks)
            C.EDIT_NEXT -> tracks.asReversed().forEach { playback.addNext(it) }
            C.EDIT_REMOVE -> playback.removeFromQueue(edit.index)
            C.EDIT_MOVE -> playback.moveQueueItem(edit.index, edit.to)
            C.EDIT_CLEAR -> playback.clearUserQueue()
        }
    }

    /** The music moves to this phone. If it was controlling another device, it stops doing so. */
    private fun receiveTransfer(transfer: C.Transfer) {
        if (_following.value != null) {
            unfollow()
            remote?.closeQuietly()
            remote = null
        }
        playback.playHandedOver(transfer.queue.map { it.toTrack() }, transfer.index, transfer.positionMs, transfer.playing)
    }

    /** What the controllers were last told, to send only real changes, not every position tick. */
    private data class Sent(
        val trackId: String?, val index: Int, val playing: Boolean, val queue: Int, val shuffle: Boolean,
        val repeat: RepeatMode, val duration: Long, val position: Long, val at: Long
    )
    @Volatile private var sent: Sent? = null

    private fun changed(state: PlaybackState): Boolean {
        val now = System.currentTimeMillis()
        val next = Sent(
            state.currentTrack?.id, state.currentIndex, state.isPlaying, state.queue.hashCode(), state.isShuffle,
            state.repeatMode, state.durationMs, state.positionMs, now
        )
        val last = sent
        val expected = last?.let { it.position + if (it.playing) now - it.at else 0 } ?: 0
        val same = last != null && last.copy(position = 0, at = 0) == next.copy(position = 0, at = 0) &&
            kotlin.math.abs(expected - state.positionMs) < 2_000
        if (same) return false
        sent = next
        return true
    }

    private fun stateOf(state: PlaybackState): C.State = C.State(
        deviceId = self.id,
        active = state.currentTrack != null,
        playing = state.isPlaying,
        positionMs = state.positionMs,
        at = System.currentTimeMillis(),
        durationMs = state.durationMs,
        index = state.currentIndex,
        queue = state.queue.map { it.toRef() },
        shuffle = state.isShuffle,
        repeat = when (state.repeatMode) {
            RepeatMode.ONE -> "one"
            RepeatMode.ALL -> "all"
            else -> "off"
        },
        volume = volume()
    )

    // ==========================================
    // This phone as a controller
    // ==========================================

    private class RemoteLink(val device: ConnectDevice, private val onReady: (Boolean) -> Unit) :
        WebSocketClient(URI("ws://${if (':' in device.host) "[${device.host}]" else device.host}:${device.port}")) {
        @Volatile var lastState: C.State? = null

        /** This phone's clock when [lastState] arrived: positions are extrapolated from it. */
        @Volatile var receivedAt = 0L
        @Volatile private var quiet = false

        /** Called once with the first state, before it is shown (resuming after a restart). */
        @Volatile var onFirstState: ((C.State) -> Unit)? = null

        /** Set while this phone takes the music back: the host answers with a hand-over. */
        @Volatile var onHandOver: ((C.Transfer) -> Unit)? = null
        private var answered = false

        /** Closes without counting as a lost device (no reconnect). */
        fun closeQuietly() {
            quiet = true
            close()
        }

        override fun onOpen(handshake: ServerHandshake) {
            send(C.encode(C.Hello(self)))
        }

        override fun onMessage(text: String) {
            when (val message = C.decode(text)) {
                is C.Challenge -> {
                    val key = accountSecret()?.let { C.accountKey(it) }
                    send(C.encode(C.Proof(key?.let { C.proof(it, message.nonce) })))
                }
                is C.PairPending -> _pairing.value = ConnectPairing(device.name, message.code)
                is C.Welcome -> {
                    _pairing.value = null
                    if (!message.accepted) {
                        close()
                        ready(false)
                    } else {
                        ready(true)
                    }
                }
                is C.Transfer -> onHandOver?.invoke(message)
                is C.State -> {
                    receivedAt = System.currentTimeMillis()
                    lastState = message
                    onFirstState?.let { first ->
                        onFirstState = null
                        first(message)
                        return
                    }
                    onRemoteState(device, message)
                }
                else -> Unit
            }
        }

        override fun onClose(code: Int, reason: String?, remote: Boolean) {
            _pairing.value = null
            val wasReady = answered
            ready(false)
            if (!quiet && wasReady && ConnectManager.remote === this) onRemoteLost(device)
        }

        override fun onError(ex: Exception) {
            Log.w(TAG, "Link to '${device.name}': ${ex.message}")
        }

        fun send(message: C.Message) {
            if (isOpen) runCatching { send(C.encode(message)) }
        }

        private fun ready(ok: Boolean) {
            if (answered) return
            answered = true
            onReady(ok)
        }
    }

    // ==========================================
    // Identity, trust and helpers
    // ==========================================

    /** The signed-in Google account's sync id: the same on every device of that account. */
    private fun accountSecret(): String? = YouTubeAuth.active?.dataSyncId?.substringBefore("||")?.takeIf { it.isNotBlank() }

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun deviceId(): String = prefs().getString(KEY_ID, null) ?: UUID.randomUUID().toString().also {
        prefs().edit().putString(KEY_ID, it).apply()
    }

    private fun deviceName(): String =
        Settings.Global.getString(app.contentResolver, "device_name")?.trim()?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    private fun deviceKind(): String =
        if (app.resources.configuration.smallestScreenWidthDp >= 600 &&
            app.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK != Configuration.UI_MODE_TYPE_TELEVISION
        ) C.KIND_TABLET else C.KIND_PHONE

    private fun isTrusted(id: String) = prefs().getStringSet(KEY_TRUSTED, emptySet())!!.contains(id)

    private fun trust(id: String) {
        val set = prefs().getStringSet(KEY_TRUSTED, emptySet())!!.toMutableSet().apply { add(id) }
        prefs().edit().putStringSet(KEY_TRUSTED, set).apply()
    }

    private fun newNonce(): String = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private fun volume(): Int {
        val audio = app.getSystemService(AudioManager::class.java) ?: return -1
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    private fun setVolume(percent: Int) {
        val audio = app.getSystemService(AudioManager::class.java) ?: return
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, (percent.coerceIn(0, 100) * max) / 100, 0) }
    }

    private fun Track.toRef(withStream: Boolean = false) = C.TrackRef(
        id = id, title = title, artist = artist, album = album, durationMs = durationMs,
        coverUrl = coverUrl?.takeIf { it.startsWith("http") }, provider = provider,
        artistId = artistId, albumId = albumId, isVideo = isVideo,
        // A web stream only: a local file path means nothing on another device.
        streamUrl = streamUrl?.takeIf { withStream && it.startsWith("https://") }
    )

    private fun C.TrackRef.toTrack() = Track(
        id = id, title = title, artist = artist, album = album, durationMs = durationMs,
        coverUrl = coverUrl?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
        provider = provider, artistId = artistId, albumId = albumId, isVideo = isVideo,
        streamUrl = streamUrl?.takeIf { it.startsWith("https://") }
    )
}

private suspend fun <T> withTimeoutOrNullCompat(ms: Long, block: suspend () -> T): T? =
    kotlinx.coroutines.withTimeoutOrNull(ms) { block() }
