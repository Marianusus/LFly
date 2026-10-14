package org.sonora.android

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.sonora.android.api.ApiHealthManager
import org.sonora.android.api.DeezerClient
import org.sonora.android.api.StreamResolver
import org.sonora.android.api.TrackSourcePreferences
import org.sonora.android.data.MusicRepository
import org.sonora.android.playback.AudioCacheManager
import org.sonora.android.playback.EqualizerManager
import org.sonora.android.playback.PlaybackManager
import org.sonora.android.playback.RecommendationEngine
import org.sonora.android.playback.SonoraMediaSessionService

/**
 * Main Android [Application] class for Sonora.
 * Initializes and wires the resilient backend architecture:
 * - [AudioCacheManager] disk caching
 * - [MusicRepository] persistent favorites, history, and playlists
 * - [ApiHealthManager] endpoint monitoring and failover
 * - [StreamResolver] multi-tier audio stream coordinator
 * - [PlaybackManager] player state and queue management
 * - [SonoraMediaSessionService] Jetpack Media3 foreground service
 */
class SonoraApp : Application() {

    companion object {
        private const val TAG = "SonoraApp"

        lateinit var instance: SonoraApp
            private set

        /** App cache dir for components without a [android.content.Context]. */
        @Volatile
        var appCacheDir: java.io.File? = null
            internal set
    }

    val appScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    val audioCacheManager: AudioCacheManager by lazy { AudioCacheManager }
    val musicRepository: MusicRepository by lazy { MusicRepository.getInstance(this) }
    val apiHealthManager: ApiHealthManager by lazy { ApiHealthManager.getInstance() }
    val streamResolver: StreamResolver by lazy { StreamResolver.getInstance() }
    val deezerClient: DeezerClient by lazy { DeezerClient() }
    val recommendationEngine: RecommendationEngine by lazy {
        RecommendationEngine.getInstance(deezerClient = deezerClient, streamResolver = streamResolver)
    }
    val playbackManager: PlaybackManager by lazy { PlaybackManager.getInstance(this) }
    val equalizerManager: EqualizerManager by lazy { EqualizerManager.getInstance(this) }

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(AppLanguage.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        appCacheDir = cacheDir
        Log.i(TAG, "Initializing Sonora Application Container")

        // 1. Initialize native audio disk cache (SimpleCache with 200MB LRU)
        AudioCacheManager.init(this)

        // 1b. Initialize persisted audio-source preferences (global + per-track overrides)
        TrackSourcePreferences.init(this)

        // 2. Eagerly load persistent data from internal storage & audio DSP settings
        musicRepository
        equalizerManager

        // 3. Connect StreamResolver and RecommendationEngine with PlaybackManager
        playbackManager.streamResolver = streamResolver
        playbackManager.recommendationEngine = recommendationEngine

        // 4. Start MediaSessionService to bind lockscreen / notification controls
        startMediaSessionService()

        // 5. Trigger initial health checks across all API providers asynchronously
        appScope.launch {
            try {
                apiHealthManager.checkAllHealth()
            } catch (e: Exception) {
                Log.w(TAG, "Initial API health check error", e)
            }
        }
    }

    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
            Log.i(TAG, "SonoraMediaSessionService bound successfully")
        }
        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            Log.w(TAG, "SonoraMediaSessionService disconnected")
        }
    }

    private fun startMediaSessionService() {
        try {
            val intent = Intent(this, SonoraMediaSessionService::class.java)
            bindService(intent, serviceConnection, BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed binding SonoraMediaSessionService during initialization", e)
        }
    }
}
