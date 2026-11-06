package org.sonora.android.wear.speaker

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.sonora.android.wear.R
import org.sonora.android.wear.WearActivity
import org.sonora.android.wear.WearProtocol as P

/**
 * Holds the watch's player while the watch is the phone's speaker, in a media session so it plays
 * on with the screen off and shows in the watch's own media controls. [Speaker] drives the player
 * directly; the session only carries what other controllers ask (the watch's media controls, a
 * headset), and [PhoneQueuePlayer] hands those to the phone.
 */
@OptIn(UnstableApi::class)
class WatchPlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    PhoneStreamDataSource.Factory(this),
                    // Lets MP3/AAC streams without an index still seek (to the phone's resume position).
                    DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
                )
            )
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setDeviceVolumeControlEnabled(true)
            // The link to the phone is slow and drops out: keep a long stretch buffered ahead.
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(30_000, 120_000, 2_500, 5_000).build())
            .build()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).setChannelName(R.string.speaker_channel).build()
                .apply { setSmallIcon(R.drawable.ic_notification) }
        )
        val openPlayer = PendingIntent.getActivity(
            this,
            0,
            Intent(this, WearActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val session = MediaSession.Builder(this, PhoneQueuePlayer(player)).setSessionActivity(openPlayer).build()
        this.session = session
        addSession(session)
        Speaker.attach(this, player) { stopSelf() }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Closing the watch app does not stop the music: the phone says when ([P.SPEAKER_STOP]). */
    override fun onTaskRemoved(rootIntent: Intent?) {}

    override fun onDestroy() {
        Speaker.detach()
        session?.run {
            removeSession(this)
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}

/**
 * The session's view of the player. The phone owns the queue, so next/previous and play/pause from
 * the watch's media controls or a headset go to the phone, which answers with a load, a play or a
 * pause. With the phone out of reach, play/pause act here.
 */
@OptIn(UnstableApi::class)
private class PhoneQueuePlayer(player: Player) : ForwardingSimpleBasePlayer(player) {
    override fun getState(): State {
        val state = super.getState()
        val commands = state.availableCommands.buildUpon().addAll(
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
        ).build()
        return state.buildUpon().setAvailableCommands(commands).build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady != player.playWhenReady) Speaker.forward(P.CMD_TOGGLE) { player.playWhenReady = playWhenReady }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> Speaker.forward(P.CMD_NEXT)
            COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> Speaker.forward(P.CMD_PREVIOUS)
            else -> return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        }
        return Futures.immediateVoidFuture()
    }
}
