/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord, GPL-3.0): data/canvas/SpotifyToken.kt.
 */
package org.sonora.android.auth

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.sonora.android.api.HttpClientProvider
import java.util.UUID

/** The Spotify account LFly reads playlists from. */
data class SpotifyAccount(val name: String, val avatarUrl: String?)

/**
 * The listener's Spotify login, used only to read their own playlists. Spotify has no public way
 * for an app like this to do that, so it goes through the door the web player uses: the `sp_dc`
 * session cookie from the real sign-in page, encrypted at rest ([SecurePrefs]), never backed up.
 *
 * The web player's bearer cannot be minted by signing a request ourselves (Spotify checks a TOTP
 * from its own bundle and refuses forged tokens downstream), so [tokens] loads open.spotify.com
 * offscreen with the cookie and reads the token the page mints for itself.
 */
object SpotifyAuth {
    private const val TAG = "SpotifyAuth"
    private const val FILE = "lfly_credentials"
    private const val KEY_COOKIE = "spotify_sp_dc"
    private const val KEY_NAME = "spotify_name"
    private const val KEY_AVATAR = "spotify_avatar"
    private const val BRIDGE = "LFlySpotifyTokenBridge"
    private const val HARVEST_TIMEOUT_MS = 20_000L
    private const val DEFAULT_LIFETIME_MS = 3_600_000L
    internal const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    val SPOTIFY_ORIGINS = listOf(
        "https://open.spotify.com",
        "https://accounts.spotify.com",
        "https://www.spotify.com",
        "https://spotify.com"
    )

    class Tokens(val bearer: String, val clientToken: String?)

    private lateinit var app: Context
    private lateinit var prefs: SecurePrefs
    private val mutex = Mutex()

    @Volatile private var bearer: String? = null
    @Volatile private var bearerExpiresAt = 0L
    @Volatile private var clientId: String? = null
    @Volatile private var clientToken: String? = null
    @Volatile private var clientTokenExpiresAt = 0L
    @Volatile private var clientVersion: String? = null
    private val deviceId = UUID.randomUUID().toString()

    private val _account = MutableStateFlow<SpotifyAccount?>(null)

    /** The connected account; null when not connected. */
    val account: StateFlow<SpotifyAccount?> = _account.asStateFlow()

    private val _expired = MutableStateFlow(false)

    /** True once Spotify refused the saved login: the listener has to connect again. */
    val expired: StateFlow<Boolean> = _expired.asStateFlow()

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        prefs = SecurePrefs(app, FILE)
        if (!prefs.getString(KEY_COOKIE).isNullOrBlank()) {
            _account.value = SpotifyAccount(prefs.getString(KEY_NAME).orEmpty(), prefs.getString(KEY_AVATAR))
        }
    }

    val isConnected: Boolean get() = _account.value != null

    /** Saves the cookie from a completed sign-in. */
    fun connect(spDc: String) {
        prefs.putString(KEY_COOKIE, spDc)
        invalidate()
        _expired.value = false
        _account.value = SpotifyAccount(prefs.getString(KEY_NAME).orEmpty(), prefs.getString(KEY_AVATAR))
    }

    fun updateProfile(name: String, avatarUrl: String?) {
        prefs.putString(KEY_NAME, name)
        prefs.putString(KEY_AVATAR, avatarUrl)
        if (_account.value != null) _account.value = SpotifyAccount(name, avatarUrl)
    }

    /** Forgets the login, here and in the in-app browser. */
    fun disconnect() {
        prefs.putString(KEY_COOKIE, null)
        prefs.putString(KEY_NAME, null)
        prefs.putString(KEY_AVATAR, null)
        invalidate()
        _expired.value = false
        _account.value = null
        clearBrowserCookies()
    }

    fun markExpired() {
        invalidate()
        if (_account.value != null) _expired.value = true
    }

    private fun invalidate() {
        bearer = null
        bearerExpiresAt = 0L
        clientToken = null
        clientTokenExpiresAt = 0L
    }

    /** The web player's tokens for the connected account, or null when there is none or minting failed. */
    suspend fun tokens(): Tokens? {
        if (!::app.isInitialized) return null
        val cookie = prefs.getString(KEY_COOKIE)?.takeIf { it.isNotBlank() } ?: return null
        val token = mutex.withLock {
            val now = System.currentTimeMillis()
            bearer?.takeIf { now < bearerExpiresAt - 30_000 } ?: run {
                val harvested = withContext(Dispatchers.Main) { harvest(cookie) } ?: return@withLock null
                bearer = harvested.first
                bearerExpiresAt = harvested.second
                harvested.first
            }
        } ?: return null
        return Tokens(token, withContext(Dispatchers.IO) { clientToken() })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun harvest(cookie: String): Pair<String, Long>? {
        val result = CompletableDeferred<Pair<String, Long>?>()
        val cookies = CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setCookie("https://open.spotify.com/", "sp_dc=$cookie; Domain=.spotify.com; Path=/; Secure")
            setCookie("https://accounts.spotify.com/", "sp_dc=$cookie; Domain=.spotify.com; Path=/; Secure")
            flush()
        }
        // The player reuses a token kept in web storage and then never asks for one we could see.
        runCatching { SPOTIFY_ORIGINS.forEach { WebStorage.getInstance().deleteOrigin(it) } }
        var view: WebView? = null
        return try {
            view = WebView(app).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = USER_AGENT
                cookies.setAcceptThirdPartyCookies(this, true)
                addJavascriptInterface(TokenBridge(result), BRIDGE)
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                        view.evaluateJavascript(HOOK, null)
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        view.evaluateJavascript(HOOK, null)
                    }
                }
                loadUrl("https://open.spotify.com/")
            }
            withTimeoutOrNull(HARVEST_TIMEOUT_MS) { result.await() }.also {
                if (it == null) Log.w(TAG, "The web player gave no signed-in token")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Token harvest failed: ${e.message}")
            null
        } finally {
            runCatching {
                view?.removeJavascriptInterface(BRIDGE)
                view?.stopLoading()
                view?.destroy()
            }
        }
    }

    private class TokenBridge(private val result: CompletableDeferred<Pair<String, Long>?>) {
        @JavascriptInterface
        fun onToken(payload: String?) {
            if (payload.isNullOrBlank() || result.isCompleted) return
            runCatching {
                val root = JSONObject(payload)
                val token = root.optString("accessToken")
                // An anonymous token comes first, before the cookie counts: wait for the signed-in one.
                if (token.isBlank() || root.optBoolean("isAnonymous", false)) return
                val expires = root.optLong("accessTokenExpirationTimestampMs").takeIf { it > System.currentTimeMillis() }
                    ?: (System.currentTimeMillis() + DEFAULT_LIFETIME_MS)
                root.optString("clientId").takeIf { it.isNotBlank() }?.let { clientId = it }
                result.complete(token to expires)
            }
        }
    }

    /** The second header Spotify's endpoints want besides the bearer. Best effort: null sends the bearer alone. */
    @Synchronized
    private fun clientToken(): String? {
        val now = System.currentTimeMillis()
        clientToken?.takeIf { now < clientTokenExpiresAt - 30_000 }?.let { return it }
        val id = clientId ?: return null
        val version = clientVersion() ?: return null
        val payload = JSONObject().put(
            "client_data",
            JSONObject()
                .put("client_version", version)
                .put("client_id", id)
                .put(
                    "js_sdk_data",
                    JSONObject()
                        .put("device_brand", "unknown")
                        .put("device_model", "unknown")
                        .put("os", "android")
                        .put("os_version", android.os.Build.VERSION.RELEASE.orEmpty())
                        .put("device_id", deviceId)
                        .put("device_type", "smartphone")
                )
        )
        // Bytes, not a String body: OkHttp would add "; charset=utf-8", which this endpoint refuses.
        val request = Request.Builder()
            .url("https://clienttoken.spotify.com/v1/clienttoken")
            .post(payload.toString().toByteArray().toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .build()
        val body = runCatching {
            HttpClientProvider.okHttpClient.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull() ?: return null
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (root.optString("response_type") != "RESPONSE_GRANTED_TOKEN_RESPONSE") return null
        val granted = root.optJSONObject("granted_token") ?: return null
        val token = granted.optString("token").takeIf { it.isNotBlank() } ?: return null
        clientToken = token
        clientTokenExpiresAt = now + granted.optLong("expires_after_seconds", 3600L) * 1000
        return token
    }

    /** The web player's version, from the config its page embeds. */
    private fun clientVersion(): String? {
        clientVersion?.let { return it }
        val request = Request.Builder().url("https://open.spotify.com").header("User-Agent", USER_AGENT).build()
        val html = runCatching {
            HttpClientProvider.okHttpClient.newCall(request).execute().use { it.body?.string() }
        }.getOrNull() ?: return null
        val encoded = Regex("""<script id="appServerConfig" type="text/plain">([^<]+)</script>""").find(html)?.groupValues?.get(1)
            ?: return null
        return runCatching {
            JSONObject(String(Base64.decode(encoded, Base64.DEFAULT))).optString("clientVersion").takeIf { it.isNotBlank() }
        }.getOrNull()?.also { clientVersion = it }
    }

    /** Signs the in-app browser out of Spotify, so the next Connect asks for the account again. */
    fun clearBrowserCookies() {
        val manager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return
        SPOTIFY_ORIGINS.forEach { origin ->
            manager.getCookie(origin)?.split(';')?.map { it.substringBefore('=').trim() }?.filter { it.isNotEmpty() }?.forEach { name ->
                val expired = "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/"
                manager.setCookie(origin, expired)
                manager.setCookie(origin, "$expired; Domain=.spotify.com")
            }
        }
        runCatching {
            SPOTIFY_ORIGINS.forEach { WebStorage.getInstance().deleteOrigin(it) }
            manager.flush()
        }
    }

    private val HOOK = """
        (function () {
          if (window.__lflyTokenHook) return;
          window.__lflyTokenHook = true;
          var report = function (body) { try { $BRIDGE.onToken(body); } catch (e) {} };
          var isToken = function (u) { try { return String(u).indexOf('/api/token') !== -1; } catch (e) { return false; } };
          var originalFetch = window.fetch;
          if (originalFetch) {
            window.fetch = function (input, init) {
              var url = (input && input.url) ? input.url : input;
              var result = originalFetch.apply(this, arguments);
              if (isToken(url)) {
                try { result.then(function (r) { r.clone().text().then(report).catch(function () {}); }).catch(function () {}); } catch (e) {}
              }
              return result;
            };
          }
          var originalOpen = XMLHttpRequest.prototype.open;
          XMLHttpRequest.prototype.open = function (method, url) { this.__lflyUrl = url; return originalOpen.apply(this, arguments); };
          var originalSend = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.send = function () {
            var xhr = this;
            try { xhr.addEventListener('load', function () { if (isToken(xhr.__lflyUrl)) report(xhr.responseText); }); } catch (e) {}
            return originalSend.apply(this, arguments);
          };
        })();
    """.trimIndent()
}
