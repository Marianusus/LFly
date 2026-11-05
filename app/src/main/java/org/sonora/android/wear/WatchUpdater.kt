package org.sonora.android.wear

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import org.sonora.android.wear.WearProtocol as P

/** What the watch section of Settings shows. */
data class WatchState(
    val checking: Boolean = true,
    /** The paired watch's name, null when none is connected. */
    val watchName: String? = null,
    /** Whether LFly is installed on it (it answered the version question). */
    val installed: Boolean = false,
    val watchVersion: String? = null,
    val watchVersionCode: Long = 0,
    /** Whether this phone build carries a watch app to send. */
    val bundled: Boolean = false,
    val sending: Boolean = false,
    val sent: Boolean = false,
    val failed: Boolean = false
)

/**
 * Installs and updates the LFly watch app from the phone. The watch build travels in the phone
 * app's assets; it goes to the watch over a Data Layer channel, and the watch's own installer puts
 * it in place. The first install needs the watch app already there (the Data Layer only talks to
 * an app of the same package), so a watch without LFly is shown as such.
 */
class WatchUpdater private constructor(context: Context) : DataClient.OnDataChangedListener {
    companion object {
        private const val TAG = "LFlyWear"

        @Volatile private var instance: WatchUpdater? = null

        fun getInstance(context: Context): WatchUpdater =
            instance ?: synchronized(this) { instance ?: WatchUpdater(context.applicationContext).also { instance = it } }
    }

    private val app = context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(WatchState())
    val state: StateFlow<WatchState> = _state.asStateFlow()
    private var watchNode: String? = null

    /** The phone's own version: the watch build it carries is always the same release. */
    val phoneVersionCode: Long by lazy {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    /** Looks for the watch and asks it which build it runs. Settings calls this when it opens. */
    fun refresh() {
        scope.launch {
            _state.update { it.copy(checking = true, bundled = hasBundledApk()) }
            val node = runCatching {
                Tasks.await(Wearable.getNodeClient(app).connectedNodes).firstOrNull { it.isNearby }
                    ?: Tasks.await(Wearable.getNodeClient(app).connectedNodes).firstOrNull()
            }.getOrNull()
            watchNode = node?.id
            if (node == null) {
                _state.update { it.copy(checking = false, watchName = null, installed = false) }
                return@launch
            }
            _state.update { it.copy(watchName = node.displayName) }
            Wearable.getDataClient(app).addListener(this@WatchUpdater)
            readCachedVersion(node.id)
            runCatching { Tasks.await(Wearable.getMessageClient(app).sendMessage(node.id, P.PATH_WATCH_VERSION_ASK, null)) }
            // A watch without LFly never answers.
            delay(4_000)
            _state.update { it.copy(checking = false) }
        }
    }

    /** Sends this phone's watch build to the watch, which then asks the wearer to install it. */
    fun install() {
        val node = watchNode ?: return
        if (_state.value.sending) return
        scope.launch {
            _state.update { it.copy(sending = true, sent = false, failed = false) }
            val ok = runCatching {
                val file = File(app.cacheDir, P.ASSET_WATCH_APK)
                app.assets.open(P.ASSET_WATCH_APK).use { input -> file.outputStream().use { input.copyTo(it) } }
                val channels = Wearable.getChannelClient(app)
                val channel = Tasks.await(channels.openChannel(node, P.PATH_WATCH_APK))
                Tasks.await(channels.sendFile(channel, Uri.fromFile(file)))
                // sendFile returns once the transfer has started; wait for the watch to close it.
                withTimeoutOrNull(120_000) { awaitClosed(channels, channel) } ?: error("The watch did not take the file")
                true
            }.getOrElse {
                Log.w(TAG, "Watch install failed: ${it.message}")
                false
            }
            _state.update { it.copy(sending = false, sent = ok, failed = !ok) }
        }
    }

    private suspend fun awaitClosed(channels: ChannelClient, channel: ChannelClient.Channel) {
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        val callback = object : ChannelClient.ChannelCallback() {
            override fun onOutputClosed(c: ChannelClient.Channel, closeReason: Int, appSpecificErrorCode: Int) {
                if (c == channel) done.complete(Unit)
            }

            override fun onChannelClosed(c: ChannelClient.Channel, closeReason: Int, appSpecificErrorCode: Int) {
                if (c == channel) done.complete(Unit)
            }
        }
        Tasks.await(channels.registerChannelCallback(channel, callback))
        try {
            done.await()
        } finally {
            channels.unregisterChannelCallback(channel, callback)
        }
    }

    override fun onDataChanged(events: com.google.android.gms.wearable.DataEventBuffer) {
        events.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == P.PATH_WATCH_VERSION) {
                readVersion(DataMapItem.fromDataItem(event.dataItem).dataMap)
            }
        }
    }

    private fun readCachedVersion(nodeId: String) {
        runCatching {
            val uri = Uri.Builder().scheme("wear").authority(nodeId).path(P.PATH_WATCH_VERSION).build()
            val items = Tasks.await(Wearable.getDataClient(app).getDataItems(uri))
            try {
                items.forEach { readVersion(DataMapItem.fromDataItem(it).dataMap) }
            } finally {
                items.release()
            }
        }
    }

    private fun readVersion(map: com.google.android.gms.wearable.DataMap) {
        _state.update {
            it.copy(
                installed = true,
                checking = false,
                watchVersion = map.getString(P.KEY_VERSION_NAME),
                watchVersionCode = map.getLong(P.KEY_VERSION_CODE)
            )
        }
    }

    private fun hasBundledApk(): Boolean = runCatching { app.assets.open(P.ASSET_WATCH_APK).close(); true }.getOrDefault(false)
}
