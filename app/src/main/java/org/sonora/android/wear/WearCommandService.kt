package org.sonora.android.wear

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import org.sonora.android.playback.PlaybackManager
import org.sonora.android.wear.WearProtocol as P

/**
 * The watch's buttons, run on the phone's player. Woken by Play services for messages under
 * [P.PATH_COMMAND], even with LFly closed.
 */
class WearCommandService : WearableListenerService() {
    private val main = Handler(Looper.getMainLooper())

    override fun onMessageReceived(event: MessageEvent) {
        if (!event.path.startsWith(P.PATH_COMMAND + "/")) return
        val command = event.path.substringAfterLast('/')
        val argument = event.data?.decodeToString().orEmpty()
        WearBridge.start(this)
        WearBridge.onWatchSeen()
        Log.d("LFlyWear", "Watch: $command $argument")
        // The player lives on the main thread.
        main.post { run(command, argument) }
    }

    private fun run(command: String, argument: String) {
        val manager = PlaybackManager.getInstance(this)
        when (command) {
            P.CMD_SYNC -> WearBridge.sync()
            P.CMD_TOGGLE -> manager.togglePlayPause()
            P.CMD_NEXT -> manager.playNext()
            P.CMD_PREVIOUS -> manager.playPrevious()
            P.CMD_LIKE -> {
                manager.toggleFavorite()
                WearBridge.sync()
            }
            P.CMD_VOLUME -> argument.toIntOrNull()?.let { WearBridge.adjustVolume(it) }
            P.CMD_QUEUE -> WearBridge.publishQueue()
            P.CMD_SKIP_TO -> argument.toIntOrNull()?.let { manager.playFromQueueIndex(it) }
            P.CMD_SEEK -> argument.toLongOrNull()?.let { manager.seekTo(it) }
        }
    }
}
