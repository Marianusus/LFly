package org.sonora.android.discord

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.sonora.android.api.HttpClientProvider
import org.sonora.android.api.Track
import org.sonora.android.auth.SecurePrefs
import org.sonora.android.playback.PlaybackManager
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The Discord account the presence is published as. */
data class DiscordUser(val id: String, val name: String, val avatarUrl: String?)

/** How the presence presents itself: as LFly, or as Spotify (Discord draws Spotify's own card). */
enum class DiscordPresenceStyle { LFLY, SPOTIFY }

data class DiscordState(
    val user: DiscordUser? = null,
    val enabled: Boolean = true,
    val style: DiscordPresenceStyle = DiscordPresenceStyle.LFLY,
    /** Optional: a Discord application of the listener's, which lets Discord show the covers. */
    val applicationId: String = "",
    val expired: Boolean = false
)

/**
 * What LFly plays, on the listener's Discord profile ("Listening to …"), with the cover, the artist
 * and a running bar. It goes through the account's own gateway session ([DiscordGateway]); a pause
 * clears it. With [DiscordPresenceStyle.SPOTIFY] it is published as Spotify's activity.
 */
object DiscordPresence {
    private const val TAG = "DiscordPresence"
    private const val FILE = "lfly_credentials"
    private const val KEY_TOKEN = "discord_token"
    private const val PREFS = "discord_presence"
    private const val KEY_USER = "user"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_STYLE = "style"
    private const val KEY_APP_ID = "application_id"
    private const val API = "https://discord.com/api/v9"
    /** Discord's own client, as the REST calls present themselves. */
    private const val CLIENT_BUILD = 314013
    private const val USER_AGENT = "Discord-Android/$CLIENT_BUILD;RNA"
    /** A paused player keeps the session this long, then lets it go until the next song. */
    private const val IDLE_CLOSE_MS = 5 * 60_000L

    private lateinit var app: Context
    private lateinit var secure: SecurePrefs
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var gateway: DiscordGateway? = null
    private var idleClose: Job? = null
    private val artwork = ConcurrentHashMap<String, String>()

    private val _state = MutableStateFlow(DiscordState())
    val state: StateFlow<DiscordState> = _state.asStateFlow()

    private val superProperties: String by lazy {
        val properties = JSONObject()
            .put("os", "Android")
            .put("browser", "Discord Android")
            .put("device", Build.DEVICE)
            .put("system_locale", Locale.getDefault().toString())
            .put("client_version", "314.13 - Stable")
            .put("release_channel", "googleRelease")
            .put("device_vendor_id", UUID.randomUUID().toString())
            .put("client_uuid", UUID.randomUUID().toString())
            .put("client_launch_id", UUID.randomUUID().toString())
            .put("os_version", Build.VERSION.RELEASE)
            .put("os_sdk_version", Build.VERSION.SDK_INT.toString())
            .put("client_build_number", CLIENT_BUILD)
            .put("client_event_source", JSONObject.NULL)
            .put("design_id", 0)
        Base64.encodeToString(properties.toString().toByteArray(), Base64.NO_WRAP)
    }

    /** Call once the player exists: follows it for as long as the app lives. */
    @OptIn(FlowPreview::class)
    fun start(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        secure = SecurePrefs(app, FILE)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val user = prefs.getString(KEY_USER, null)?.let { raw ->
            runCatching { JSONObject(raw) }.getOrNull()?.let {
                DiscordUser(it.optString("id"), it.optString("name"), it.optString("avatar").takeIf { a -> a.isNotBlank() })
            }
        }?.takeIf { secure.getString(KEY_TOKEN) != null }
        _state.value = DiscordState(
            user = user,
            enabled = prefs.getBoolean(KEY_ENABLED, true),
            style = runCatching { DiscordPresenceStyle.valueOf(prefs.getString(KEY_STYLE, null)!!) }.getOrDefault(DiscordPresenceStyle.LFLY),
            applicationId = prefs.getString(KEY_APP_ID, "").orEmpty()
        )

        val player = PlaybackManager.getInstance(app)
        scope.launch {
            // What the card shows changes with the song, play/pause and a seek (its start time moves);
            // the position ticking along does not.
            combine(player.playbackState, _state) { playback, settings ->
                val track = playback.currentTrack
                val active = settings.user != null && settings.enabled && !settings.expired
                if (!active || track == null || !playback.isPlaying) {
                    null
                } else {
                    val start = System.currentTimeMillis() - playback.positionMs
                    Snapshot(track, start / 5_000L, playback.durationMs.takeIf { it > 0 } ?: track.durationMs, settings)
                }
            }
                .distinctUntilChanged()
                .debounce(1_200L)
                .collect { publish(it) }
        }
    }

    private data class Snapshot(val track: Track, val startBucket: Long, val durationMs: Long, val settings: DiscordState)

    private suspend fun publish(snapshot: Snapshot?) {
        if (snapshot == null) {
            gateway?.setPresence(presence(null))
            idleClose?.cancel()
            idleClose = scope.launch {
                delay(IDLE_CLOSE_MS)
                closeGateway()
            }
            return
        }
        idleClose?.cancel()
        val token = secure.getString(KEY_TOKEN) ?: return
        val player = PlaybackManager.getInstance(app).playbackState.value
        val start = System.currentTimeMillis() - player.positionMs
        val activity = activity(snapshot.track, start, snapshot.durationMs, snapshot.settings, token)
        val socket = gateway ?: DiscordGateway(token, onAuthFailed = ::onAuthFailed).also { gateway = it }
        socket.setPresence(presence(activity))
    }

    private suspend fun activity(track: Track, start: Long, durationMs: Long, settings: DiscordState, token: String): JSONObject {
        val spotify = settings.style == DiscordPresenceStyle.SPOTIFY
        val activity = JSONObject()
            .put("name", if (spotify) "Spotify" else "LFly")
            .put("type", 2) // Listening to
            .put("details", track.title.take(128))
            .put("state", track.artist.ifBlank { " " }.take(128))
            .put("status_display_type", if (spotify) 0 else 1)
        val timestamps = JSONObject().put("start", start)
        if (durationMs > 0) timestamps.put("end", start + durationMs)
        activity.put("timestamps", timestamps)

        val assets = JSONObject()
        track.album?.takeIf { it.isNotBlank() }?.let { assets.put("large_text", it.take(128)) }
        image(track.coverUrl, settings, token)?.let { assets.put("large_image", it) }
        if (assets.length() > 0) activity.put("assets", assets)

        if (spotify) {
            // What makes Discord draw Spotify's card: Spotify's party, and the song's own id when it has one.
            settings.user?.id?.let { activity.put("party", JSONObject().put("id", "spotify:$it")) }
            track.id.takeIf { it.startsWith("spotify_") }?.removePrefix("spotify_")?.let { id ->
                activity.put("sync_id", id)
                activity.put("flags", 48)
            }
        } else {
            settings.applicationId.takeIf { it.isNotBlank() }?.let { activity.put("application_id", it) }
        }
        return activity
    }

    /**
     * The cover as Discord can show it: Spotify's own images by id, anything else mirrored through
     * the listener's Discord application when one is set. Null shows no cover.
     */
    private suspend fun image(url: String?, settings: DiscordState, token: String): String? {
        if (url.isNullOrBlank() || !url.startsWith("http")) return null
        Regex("""^https://i\.scdn\.co/image/([A-Za-z0-9]+)""").find(url)?.let { return "spotify:${it.groupValues[1]}" }
        val appId = settings.applicationId.takeIf { it.isNotBlank() } ?: return null
        artwork[url]?.let { return it }
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$API/applications/$appId/external-assets")
                .post(JSONObject().put("urls", JSONArray().put(url)).toString().toRequestBody("application/json".toMediaType()))
                .apply { headers(token).forEach { (k, v) -> header(k, v) } }
                .build()
            runCatching {
                HttpClientProvider.okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Discord could not mirror the cover (${response.code})")
                        return@use null
                    }
                    JSONArray(response.body?.string().orEmpty()).optJSONObject(0)?.optString("external_asset_path")
                        ?.takeIf { it.isNotBlank() }?.let { "mp:$it" }
                }
            }.getOrNull()?.also { artwork[url] = it }
        }
    }

    private fun presence(activity: JSONObject?): JSONObject = JSONObject()
        .put("since", 0)
        .put("activities", JSONArray().apply { activity?.let { put(it) } })
        .put("status", "online")
        // AFK: Discord keeps sending notifications to the listener's own devices.
        .put("afk", true)

    private fun headers(token: String) = mapOf(
        "Authorization" to token,
        "User-Agent" to USER_AGENT,
        "X-Super-Properties" to superProperties
    )

    /** Checks [token] with Discord and saves it with the account it belongs to. */
    suspend fun connect(token: String): Result<DiscordUser> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url("$API/users/@me").apply { headers(token).forEach { (k, v) -> header(k, v) } }.build()
            val body = HttpClientProvider.okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Discord answered ${response.code}")
                response.body?.string().orEmpty()
            }
            val json = JSONObject(body)
            val id = json.optString("id").takeIf { it.isNotBlank() } ?: error("Discord returned no user")
            val username = json.optString("username")
            val name = json.optString("global_name").takeIf { it.isNotBlank() && it != "null" } ?: username
            val avatar = json.optString("avatar").takeIf { it.isNotBlank() && it != "null" }
                ?.let { "https://cdn.discordapp.com/avatars/$id/$it.png" }
            DiscordUser(id, name, avatar)
        }.onSuccess { user ->
            secure.putString(KEY_TOKEN, token)
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_USER, JSONObject().put("id", user.id).put("name", user.name).put("avatar", user.avatarUrl ?: "").toString())
                .apply()
            closeGateway()
            _state.value = _state.value.copy(user = user, expired = false)
        }.onFailure { Log.w(TAG, "Discord sign-in could not be checked: ${it.message}") }
    }

    /** Clears the card, forgets the token and signs the in-app browser out of Discord. */
    fun disconnect() {
        gateway?.setPresence(presence(null))
        scope.launch {
            delay(1_000L)
            closeGateway()
        }
        secure.putString(KEY_TOKEN, null)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_USER).apply()
        _state.value = _state.value.copy(user = null, expired = false)
        runCatching {
            WebStorage.getInstance().deleteOrigin("https://discord.com")
            val cookies = CookieManager.getInstance()
            cookies.getCookie("https://discord.com")?.split(';')?.map { it.substringBefore('=').trim() }?.forEach { name ->
                cookies.setCookie("https://discord.com", "$name=; Max-Age=0; Path=/")
                cookies.setCookie("https://discord.com", "$name=; Max-Age=0; Path=/; Domain=.discord.com")
            }
            cookies.flush()
        }
    }

    fun setEnabled(enabled: Boolean) {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        _state.value = _state.value.copy(enabled = enabled)
    }

    fun setStyle(style: DiscordPresenceStyle) {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_STYLE, style.name).apply()
        _state.value = _state.value.copy(style = style)
    }

    fun setApplicationId(id: String) {
        val clean = id.filter { it.isDigit() }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_APP_ID, clean).apply()
        artwork.clear()
        _state.value = _state.value.copy(applicationId = clean)
    }

    private fun onAuthFailed() {
        closeGateway()
        _state.value = _state.value.copy(expired = true)
    }

    private fun closeGateway() {
        gateway?.close()
        gateway = null
    }
}
