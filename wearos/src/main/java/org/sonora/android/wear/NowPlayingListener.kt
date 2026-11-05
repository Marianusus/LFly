package org.sonora.android.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import org.sonora.android.wear.WearProtocol as P

/**
 * Wakes on each now-playing write from the phone and keeps [WatchFaceChip] in step with it.
 * Nothing runs between writes: the phone only writes on track, play and pause changes.
 * Also the watch's end of updating the watch app from the phone ([WatchInstaller]).
 */
class NowPlayingListener : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == P.PATH_WATCH_VERSION_ASK) WatchInstaller.publishVersion(this)
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path == P.PATH_WATCH_APK) WatchInstaller.receive(this, channel)
    }

    override fun onInputClosed(channel: ChannelClient.Channel, closeReason: Int, appSpecificErrorCode: Int) {
        if (channel.path == P.PATH_WATCH_APK) WatchInstaller.onReceived(this, closeReason)
    }

    override fun onDataChanged(events: DataEventBuffer) {
        var latest: DataEvent? = null
        for (event in events) {
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == P.PATH_NOW_PLAYING) latest = event
        }
        val map = latest?.let { DataMapItem.fromDataItem(it.dataItem).dataMap } ?: return
        val playing = map.getBoolean(P.KEY_HAS_TRACK) && map.getBoolean(P.KEY_PLAYING)
        if (playing) {
            WatchFaceChip.post(this, map.getString(P.KEY_TITLE).orEmpty(), map.getString(P.KEY_ARTIST).orEmpty())
        } else {
            WatchFaceChip.clear(this)
        }
    }
}

/**
 * LFly's chip on the watch face while music plays on the phone.
 *
 * Wear OS already shows a media chip for any phone playback, but it opens the system's media
 * controls. An ongoing activity is how an app gets a chip of its own, so LFly posts one whose tap
 * opens LFly's player, and removes it as soon as playback stops.
 */
object WatchFaceChip {
    private const val CHANNEL_ID = "lfly_watch_face"
    private const val ID = 7

    fun post(context: Context, title: String, artist: String) {
        val notifications = context.getSystemService(NotificationManager::class.java) ?: return
        if (!notifications.areNotificationsEnabled()) return
        if (notifications.getNotificationChannel(CHANNEL_ID) == null) {
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.wear_playing_now), NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
        }
        val openPlayer = PendingIntent.getActivity(
            context,
            ID,
            Intent(context, WearActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val line = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" – ")
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(openPlayer)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
        OngoingActivity.Builder(context, ID, notification)
            .setStaticIcon(R.drawable.ic_notification)
            .setTouchIntent(openPlayer)
            .setTitle(title)
            .setStatus(Status.forPart(Status.TextPart(line)))
            .build()
            .apply(context)
        runCatching { notifications.notify(ID, notification.build()) }
    }

    fun clear(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(ID)
    }
}
