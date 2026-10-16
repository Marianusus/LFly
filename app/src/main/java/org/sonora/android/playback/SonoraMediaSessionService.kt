package org.sonora.android.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.core.app.NotificationManagerCompat
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.sonora.android.R
import org.sonora.android.api.Track
import org.sonora.android.data.MusicRepository

/**
 * Native Android foreground [MediaSessionService] managing the Media3 [ExoPlayer] instance,
 * lock-screen & status-bar media notification controls, audio focus handling,
 * and audio becoming noisy events (pause on disconnect).
 */
class SonoraMediaSessionService : MediaSessionService() {

    companion object {
        private const val TAG = "SonoraMediaService"
        const val CHANNEL_ID = "sonora_media_playback_channel_v2"
        const val NOTIFICATION_ID = 1001
        const val ACTION_TOGGLE_FAVORITE = "ACTION_TOGGLE_FAVORITE"
        const val ACTION_DISLIKE = "ACTION_DISLIKE"
    }

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private fun buildCustomLayout(track: Track?, isFavorite: Boolean): List<CommandButton> {
        val favoriteButton = CommandButton.Builder(
            if (isFavorite) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED
        )
            .setSessionCommand(SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY))
            .setIconResId(if (isFavorite) org.sonora.android.R.drawable.ic_heart_filled else org.sonora.android.R.drawable.ic_heart_outline)
            .setDisplayName(getString(if (isFavorite) org.sonora.android.R.string.action_unlike else org.sonora.android.R.string.action_like))
            .setEnabled(track != null)
            .build()

        val dislikeButton = CommandButton.Builder(CommandButton.ICON_THUMB_DOWN_UNFILLED)
            .setSessionCommand(SessionCommand(ACTION_DISLIKE, Bundle.EMPTY))
            .setIconResId(org.sonora.android.R.drawable.ic_thumb_down)
            .setDisplayName(getString(org.sonora.android.R.string.action_dislike))
            .setEnabled(track != null)
            .build()

        return listOf(favoriteButton, dislikeButton)
    }

    private fun updateCustomLayout(track: Track?, isFavorite: Boolean) {
        val layout = buildCustomLayout(track, isFavorite)
        mediaSession?.setCustomLayout(layout)
        mediaSession?.setMediaButtonPreferences(layout)
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Creating SonoraMediaSessionService")

        createNotificationChannel()

        // Configure audio attributes for music playback with automatic audio focus
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        // Cache-backed DataSource factory
        val cacheDataSourceFactory = AudioCacheManager.getCacheDataSourceFactory(this)
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(cacheDataSourceFactory)

        // Instantiate ExoPlayer with full resilience
        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setMediaSourceFactory(mediaSourceFactory)
            .setSeekBackIncrementMs(10000L)
            .setSeekForwardIncrementMs(10000L)
            .build()

        this.player = exoPlayer

        // ForwardingPlayer to seamlessly support Next/Previous queue controls
        // on Lock Screen, Quick Settings notification, and Bluetooth devices
        val forwardingPlayer = object : ForwardingPlayer(exoPlayer) {
            override fun isCommandAvailable(command: Int): Boolean {
                return when (command) {
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> true
                    else -> super.isCommandAvailable(command)
                }
            }

            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands()
                    .buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()
            }

            // Route every play/pause request (notification, lock screen, media button,
            // Bluetooth, Quick Settings) through PlaybackManager so recovery works and a
            // Party guest forwards the command to its host instead of acting locally.
            override fun play() {
                PlaybackManager.getInstance(this@SonoraMediaSessionService).play()
            }

            override fun pause() {
                PlaybackManager.getInstance(this@SonoraMediaSessionService).pause()
            }

            override fun seekToNext() {
                PlaybackManager.getInstance(this@SonoraMediaSessionService).playNext()
            }

            override fun seekToNextMediaItem() {
                PlaybackManager.getInstance(this@SonoraMediaSessionService).playNext()
            }

            override fun seekToPrevious() {
                PlaybackManager.getInstance(this@SonoraMediaSessionService).playPrevious()
            }

            override fun seekToPreviousMediaItem() {
                PlaybackManager.getInstance(this@SonoraMediaSessionService).playPrevious()
            }

            override fun hasNextMediaItem(): Boolean {
                val state = PlaybackManager.getInstance(this@SonoraMediaSessionService).playbackState.value
                return state.queue.size > 1 || state.isAutoplayEnabled
            }

            override fun hasPreviousMediaItem(): Boolean {
                val state = PlaybackManager.getInstance(this@SonoraMediaSessionService).playbackState.value
                return state.currentIndex > 0 || state.positionMs > 3000L
            }
        }

        // Explicit PendingIntent to launch MainActivity when notification or lockscreen widget is tapped
        val openAppIntent = Intent(this, org.sonora.android.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val playbackManager = PlaybackManager.getInstance(this)
        val initialTrack = playbackManager.playbackState.value.currentTrack
        val initialIsFav = initialTrack != null && playbackManager.isFavorite(initialTrack.id)
        val initialLayout = buildCustomLayout(initialTrack, initialIsFav)

        // Build MediaSession using ForwardingPlayer
        val session = MediaSession.Builder(this, forwardingPlayer)
            .setSessionActivity(sessionActivityPendingIntent)
            .setCallback(SonoraSessionCallback())
            .setCustomLayout(initialLayout)
            .setMediaButtonPreferences(initialLayout)
            .build()

        this.mediaSession = session

        // CRITICAL: Register session with MediaSessionService so Media3's
        // MediaNotificationManager observes player state and posts foreground notifications
        addSession(session)

        // Configure system media notification provider with our vector small icon
        try {
            val baseProvider = DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                .setNotificationId(NOTIFICATION_ID)
                .build()
            baseProvider.setSmallIcon(org.sonora.android.R.drawable.ic_notification)
            setMediaNotificationProvider(KeepAliveNotificationProvider(baseProvider, applicationContext))
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring DefaultMediaNotificationProvider", e)
        }

        // Attach player to PlaybackManager singleton
        playbackManager.attachPlayer(this, exoPlayer, session)

        // Observe track and favorite changes to update notification custom layout & media buttons dynamically
        serviceScope.launch {
            playbackManager.playbackState
                .map { it.currentTrack?.id }
                .distinctUntilChanged()
                .collect {
                    val track = playbackManager.playbackState.value.currentTrack
                    val isFav = track != null && playbackManager.isFavorite(track.id)
                    updateCustomLayout(track, isFav)
                }
        }

        serviceScope.launch {
            val musicRepository = MusicRepository.getInstance(this@SonoraMediaSessionService)
            musicRepository.favorites.collect {
                val track = playbackManager.playbackState.value.currentTrack
                val isFav = track != null && playbackManager.isFavorite(track.id)
                updateCustomLayout(track, isFav)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Safety net: a service started via startForegroundService() must call
        // startForeground() within ~5s or the platform raises an ANR
        // ("Context.startForegroundService() did not then call Service.startForeground()").
        // With nothing loaded Media3 posts no notification, so stop promptly to satisfy the
        // started-service contract instead of lingering (the service stays alive while bound).
        if (PlaybackManager.getInstance(this).playbackState.value.currentTrack == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    /**
     * Keeps the media session in the foreground for the whole active queue instead of letting Media3
     * demote the service as soon as a track ends.
     *
     * Media3's [MediaNotificationManager] demotes the service (`stopForeground(DETACH)`) whenever
     * playback is not "ongoing" — which happens on [androidx.media3.common.Player.STATE_ENDED]
     * between two tracks. Once demoted, Android 12+ forbids re-promoting a foreground service from
     * the background: the next track then throws `ForegroundServiceStartNotAllowedException`, the
     * notification disappears and the system eventually stops the service ("app idle"), killing
     * background playback. While a track is loaded we therefore force the foreground requirement so
     * the service is never demoted. When nothing is loaded (queue cleared) Media3 demotes normally.
     */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        val keepForeground = PlaybackManager.getInstance(this).playbackState.value.currentTrack != null
        super.onUpdateNotification(session, startInForegroundRequired || keepForeground)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "onTaskRemoved called")
        // Keep the service (and lock-screen / notification controls) alive as long as there
        // is an active playback session. Only stop when nothing is loaded — stopping while
        // a queue exists would silently kill background playback when the task is swiped.
        val hasActiveSession = PlaybackManager.getInstance(this)
            .playbackState.value.currentTrack != null
        if (!hasActiveSession) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "Destroying SonoraMediaSessionService")
        serviceScope.cancel()
        PlaybackManager.getInstance(this).detachPlayer()

        mediaSession?.let { session ->
            removeSession(session)
            session.player.release()
            session.release()
            mediaSession = null
        }
        player = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // Clean up old channel if it existed
            try {
                notificationManager?.deleteNotificationChannel("sonora_media_playback_channel")
            } catch (e: Exception) {
                // Ignore
            }

            val name = getString(R.string.playback_notification_channel_name)
            val descriptionText = getString(R.string.playback_notification_channel_description)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setSound(null, null)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    /**
     * Session callback accepting player commands and enabling seamless playback resumption.
     */
    private inner class SonoraSessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY))
                .add(SessionCommand(ACTION_DISLIKE, Bundle.EMPTY))
                .build()
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build()

            val playbackManager = PlaybackManager.getInstance(this@SonoraMediaSessionService)
            val currentTrack = playbackManager.playbackState.value.currentTrack
            val isFav = currentTrack != null && playbackManager.isFavorite(currentTrack.id)
            val customLayout = buildCustomLayout(currentTrack, isFav)

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(playerCommands)
                .setCustomLayout(customLayout)
                .setMediaButtonPreferences(customLayout)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            val playbackManager = PlaybackManager.getInstance(this@SonoraMediaSessionService)
            when (customCommand.customAction) {
                ACTION_TOGGLE_FAVORITE -> {
                    val currentTrack = playbackManager.playbackState.value.currentTrack
                    if (currentTrack != null) {
                        val isFav = playbackManager.toggleFavorite(currentTrack)
                        updateCustomLayout(currentTrack, isFav)
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                ACTION_DISLIKE -> {
                    val currentTrack = playbackManager.playbackState.value.currentTrack
                    if (currentTrack != null) {
                        playbackManager.dislikeTrack(currentTrack)
                        val newTrack = playbackManager.playbackState.value.currentTrack
                        val isFav = newTrack != null && playbackManager.isFavorite(newTrack.id)
                        updateCustomLayout(newTrack, isFav)
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        // NOTE: Do NOT handle next/previous here. Media3 forwards an allowed player command
        // to the ForwardingPlayer (whose seekToNext/seekToPrevious already call playNext /
        // playPrevious). Handling it here as well would advance twice — the notification
        // "next" would then skip two tracks and play something different from the in-app
        // queue. Single source of truth = the ForwardingPlayer overrides.

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val state = PlaybackManager.getInstance(this@SonoraMediaSessionService).playbackState.value
            val currentTrack = state.currentTrack
            if (currentTrack != null) {
                val mediaMetadata = MediaMetadata.Builder()
                    .setTitle(currentTrack.title)
                    .setArtist(currentTrack.artist)
                    .setAlbumTitle(currentTrack.album)
                    .setArtworkUri(currentTrack.coverUrl?.let { android.net.Uri.parse(it) })
                    .build()

                val item = MediaItem.Builder()
                    .setMediaId(currentTrack.id)
                    .setUri(currentTrack.streamUrl ?: "")
                    .setMediaMetadata(mediaMetadata)
                    .build()

                val result = MediaSession.MediaItemsWithStartPosition(
                    listOf(item),
                    0,
                    state.positionMs
                )
                return Futures.immediateFuture(result)
            }
            return super.onPlaybackResumption(mediaSession, controller)
        }
    }
}

/**
 * Wraps the default {@link DefaultMediaNotificationProvider} so that asynchronous notification
 * refreshes (the album artwork decoded on a background executor) update the already-posted
 * notification directly instead of being forwarded to Media3's change callback.
 *
 * Media3's callback re-evaluates `shouldRunInForeground` and calls `stopForeground()` when the
 * player is momentarily not ongoing (e.g. `STATE_ENDED` between tracks). If that lands while the
 * app is in the background the service is demoted and can never be re-promoted, killing background
 * playback. Posting the refreshed notification ourselves keeps the artwork while never touching the
 * foreground state.
 */
@OptIn(UnstableApi::class)
private class KeepAliveNotificationProvider(
    private val delegate: MediaNotification.Provider,
    private val context: android.content.Context
) : MediaNotification.Provider {

    override fun createNotification(
        mediaSession: MediaSession,
        mediaButtonPreferences: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        callback: MediaNotification.Provider.Callback
    ): MediaNotification {
        return delegate.createNotification(
            mediaSession,
            mediaButtonPreferences,
            actionFactory,
            MediaNotification.Provider.Callback { notification ->
                try {
                    NotificationManagerCompat.from(context)
                        .notify(notification.notificationId, notification.notification)
                } catch (e: Exception) {
                    Log.w("SonoraMediaService", "Failed to refresh media notification", e)
                }
            }
        )
    }

    override fun handleCustomCommand(session: MediaSession, action: String, extras: Bundle): Boolean {
        return delegate.handleCustomCommand(session, action, extras)
    }
}
