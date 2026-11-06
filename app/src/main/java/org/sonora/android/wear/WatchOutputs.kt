package org.sonora.android.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.sonora.android.R
import org.sonora.android.cast.CastDiscovery
import org.sonora.android.connect.ConnectManager
import org.sonora.android.playback.AudioRouting
import org.sonora.android.playback.PlaybackManager
import org.sonora.android.wear.WearProtocol as P

/**
 * Where the music can play, for the watch's picker: this phone's own outputs (speaker, Bluetooth,
 * wired), the LFly Connect devices on the network and the watch itself as the speaker. Picks the
 * same way the phone's device sheet does, so both always agree on what is playing where.
 */
internal object WatchOutputs {
    private const val TAG = "LFlyWear"

    /** How long a watch's question keeps LFly Connect looking for devices. */
    private const val DISCOVERY_MS = 30_000L

    /** [P.OUTPUT_LOCAL_PREFIX] + this is the phone's own speaker. */
    private const val LOCAL_SPEAKER = "speaker"

    /** Shown while casting; picking it changes nothing (Cast is chosen on the phone). */
    private const val OUTPUT_CAST = "cast"

    /** One row of [P.PATH_OUTPUTS], and what [P.PATH_NOW_PLAYING] says the music comes out of. */
    data class Output(val id: String, val name: String, val kind: String, val active: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var discoveryJob: Job? = null

    /** The watch that asked last, so the list shows its name. */
    @Volatile private var asker: String? = null

    /** Where the music comes out now. Cheap enough for any thread. */
    fun current(context: Context): Output {
        WatchSpeaker.watch.value?.let { return Output(P.OUTPUT_WATCH, it.name, P.KIND_WATCH, true) }
        ConnectManager.following.value?.let {
            return Output(P.OUTPUT_CONNECT_PREFIX + it.id, it.name, connectKind(it.kind), true)
        }
        CastDiscovery.activeName.value?.let { return Output(OUTPUT_CAST, it, P.KIND_CAST, true) }
        val active = AudioRouting.outputs(context).firstOrNull { it.isActive }
        return active?.let { local(context, it, true) }
            ?: Output(P.OUTPUT_LOCAL_PREFIX + LOCAL_SPEAKER, context.getString(R.string.connect_phone_speaker), P.KIND_PHONE, true)
    }

    /**
     * Publishes the list for [watchNodeId], and again whenever LFly Connect finds or loses a
     * device during the next [DISCOVERY_MS] (it only looks while asked to).
     */
    fun publish(context: Context, watchNodeId: String?) {
        val app = context.applicationContext
        if (watchNodeId != null) asker = watchNodeId
        scope.launch(Dispatchers.IO) { put(app) }
        if (discoveryJob?.isActive == true) return
        val startedHere = !ConnectManager.isDiscovering
        ConnectManager.startDiscovery()
        discoveryJob = scope.launch {
            withTimeoutOrNull(DISCOVERY_MS) {
                ConnectManager.devices.drop(1).collect { withContext(Dispatchers.IO) { put(app) } }
            }
            // The phone's own picker may be looking too: only stop a search started here.
            if (startedHere) ConnectManager.stopDiscovery()
        }
    }

    /** Plays where [id] says ([P.KEY_OUTPUT_ID]); [watchNodeId] is the watch that asked. */
    fun select(context: Context, id: String, watchNodeId: String?) {
        val app = context.applicationContext
        Log.i(TAG, "Watch picks output '$id'")
        when {
            id == P.OUTPUT_WATCH -> {
                val nodeId = watchNodeId ?: WearBridge.watch.value?.id ?: return
                scope.launch {
                    val node = withContext(Dispatchers.IO) { WearBridge.nodeFor(nodeId) }
                    WatchSpeaker.start(app, node)
                    republish(app)
                }
                return
            }
            id.startsWith(P.OUTPUT_CONNECT_PREFIX) -> {
                val deviceId = id.removePrefix(P.OUTPUT_CONNECT_PREFIX)
                val device = ConnectManager.devices.value.firstOrNull { it.id == deviceId }
                    ?: ConnectManager.following.value?.takeIf { it.id == deviceId }
                // The watch lets go by itself once Connect follows the device.
                if (device != null) ConnectManager.playOn(device)
            }
            id.startsWith(P.OUTPUT_LOCAL_PREFIX) -> {
                val key = id.removePrefix(P.OUTPUT_LOCAL_PREFIX)
                val outputs = AudioRouting.outputs(app)
                val device = if (key == LOCAL_SPEAKER) {
                    outputs.firstOrNull { it.kind == AudioRouting.Kind.PHONE }
                } else {
                    key.toIntOrNull()?.let { n -> outputs.firstOrNull { it.id == n } }
                }
                // The music comes back to the phone first, from the watch or from a Connect device.
                if (WatchSpeaker.active.value) WatchSpeaker.stop()
                if (ConnectManager.following.value != null) ConnectManager.playHere()
                if (CastDiscovery.activeName.value != null) CastDiscovery.disconnect()
                device?.let { PlaybackManager.getInstance(app).setAudioOutput(it.id) }
            }
        }
        republish(app)
    }

    /** A pick lands in steps (Connect connects, Bluetooth settles): show the list again after it. */
    private fun republish(app: Context) {
        scope.launch {
            kotlinx.coroutines.delay(1_200)
            withContext(Dispatchers.IO) { put(app) }
        }
    }

    private fun rows(context: Context): List<Output> {
        val speaking = WatchSpeaker.watch.value
        val following = ConnectManager.following.value
        val casting = CastDiscovery.activeName.value
        val here = speaking == null && following == null && casting == null
        val rows = ArrayList<Output>()
        AudioRouting.outputs(context).forEach { rows += local(context, it, here && it.isActive) }
        val devices = ConnectManager.devices.value
        if (following != null && devices.none { it.id == following.id }) {
            rows += Output(P.OUTPUT_CONNECT_PREFIX + following.id, following.name, connectKind(following.kind), true)
        }
        devices.forEach {
            rows += Output(P.OUTPUT_CONNECT_PREFIX + it.id, it.name, connectKind(it.kind), following?.id == it.id)
        }
        if (casting != null) rows += Output(OUTPUT_CAST, casting, P.KIND_CAST, true)
        val watchId = asker ?: speaking?.id ?: WearBridge.watch.value?.id
        val watch = watchId?.let { WearBridge.nodeFor(it) }
        rows += Output(
            P.OUTPUT_WATCH,
            watch?.name ?: context.getString(R.string.settings_page_watch),
            P.KIND_WATCH,
            speaking != null && (watchId == null || speaking.id == watchId)
        )
        return rows
    }

    /** Blocking: runs on the IO dispatcher. */
    private fun put(app: Context) {
        val rows = runCatching { rows(app) }.getOrElse {
            Log.w(TAG, "Outputs: ${it.message}")
            return
        }
        val request = PutDataMapRequest.create(P.PATH_OUTPUTS)
        request.dataMap.apply {
            putDataMapArrayList(P.KEY_OUTPUTS, ArrayList(rows.map { it.toDataMap() }))
            putLong(P.KEY_STAMP, System.currentTimeMillis())
        }
        runCatching {
            Tasks.await(Wearable.getDataClient(app).putDataItem(request.asPutDataRequest().setUrgent()))
        }.onFailure { Log.w(TAG, "Could not send the outputs: ${it.message}") }
    }

    private fun Output.toDataMap() = DataMap().apply {
        putString(P.KEY_OUTPUT_ID, id)
        putString(P.KEY_OUTPUT_NAME, name)
        putString(P.KEY_OUTPUT_KIND, kind)
        putBoolean(P.KEY_OUTPUT_ACTIVE, active)
    }

    /** One of the phone's outputs, named as the device sheet names it. */
    private fun local(context: Context, device: AudioRouting.Device, active: Boolean): Output {
        val id = if (device.kind == AudioRouting.Kind.PHONE) LOCAL_SPEAKER else device.id.toString()
        val name = when (device.kind) {
            AudioRouting.Kind.PHONE -> context.getString(R.string.connect_phone_speaker)
            AudioRouting.Kind.WIRED -> context.getString(R.string.audio_output_wired)
            AudioRouting.Kind.BLUETOOTH -> device.name.ifBlank { context.getString(R.string.audio_output_bluetooth) }
            AudioRouting.Kind.USB -> device.name.ifBlank { context.getString(R.string.audio_output_usb) }
            AudioRouting.Kind.HDMI -> device.name.ifBlank { context.getString(R.string.audio_output_hdmi) }
            AudioRouting.Kind.OTHER -> device.name.ifBlank { context.getString(R.string.audio_output_other) }
        }
        return Output(P.OUTPUT_LOCAL_PREFIX + id, name, localKind(device), active)
    }

    private fun localKind(device: AudioRouting.Device): String = when (device.form) {
        AudioRouting.Form.EARBUDS -> P.KIND_EARBUDS
        AudioRouting.Form.HEADPHONES, AudioRouting.Form.HEADSET -> P.KIND_HEADPHONES
        AudioRouting.Form.SPEAKER -> P.KIND_SPEAKER
        AudioRouting.Form.CAR -> P.KIND_CAR
        AudioRouting.Form.WATCH -> P.KIND_WATCH
        AudioRouting.Form.DEFAULT -> when (device.kind) {
            AudioRouting.Kind.PHONE -> P.KIND_PHONE
            AudioRouting.Kind.HDMI -> P.KIND_TV
            AudioRouting.Kind.OTHER -> P.KIND_SPEAKER
            AudioRouting.Kind.WIRED, AudioRouting.Kind.USB, AudioRouting.Kind.BLUETOOTH -> P.KIND_HEADPHONES
        }
    }

    /** LFly Connect's device kinds are the protocol's own words; anything new is a phone. */
    private fun connectKind(kind: String): String = when (kind) {
        P.KIND_TABLET, P.KIND_DESKTOP, P.KIND_TV, P.KIND_WATCH, P.KIND_PHONE -> kind
        else -> P.KIND_PHONE
    }
}
