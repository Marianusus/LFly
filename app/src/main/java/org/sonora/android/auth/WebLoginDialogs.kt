/*
 * The Spotify and Discord sign-ins are adapted from BitChord (https://github.com/kushagrasinghx/BitChord,
 * GPL-3.0): auth/SpotifyLoginScreen.kt and auth/DiscordLoginScreen.kt.
 */
package org.sonora.android.auth

import android.annotation.SuppressLint
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import org.sonora.android.R
import org.sonora.android.ui.theme.LocalBackgroundScheme

private const val TAG = "WebLogin"

/** The real sign-in page of [Spotify][SpotifyLoginDialog] or [Discord][DiscordLoginDialog], full screen. */
@Composable
private fun WebLoginDialog(
    title: String,
    hint: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    createWebView: (android.content.Context) -> WebView
) {
    val scheme = LocalBackgroundScheme.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { webView?.destroy() } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        val bottomColor = scheme.gradient.last().toArgb()
        SideEffect {
            window?.let {
                WindowCompat.setDecorFitsSystemWindows(it, false)
                it.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                it.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
                it.setDimAmount(0f)
                it.setBackgroundDrawable(ColorDrawable(bottomColor))
            }
        }
        BackHandler(enabled = canGoBack) { webView?.goBack() }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(scheme.gradient))
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.yt_account_close), tint = scheme.onBackground)
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = scheme.onBackground,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onBackgroundSecondary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                textAlign = TextAlign.Center
            )
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { context ->
                    createWebView(context).also { view ->
                        // MATCH_PARENT: with the WRAP_CONTENT Compose gives a bare view, pages sized off
                        // `height: 100%` (Discord's is) lay out to nothing.
                        view.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        webView = view
                    }
                },
                update = { canGoBack = it.canGoBack() }
            )
        }
    }
}

/** The device's own Chrome, minus the WebView markers some sign-in pages refuse to serve. */
internal fun browserUserAgent(platform: String?): String {
    val stripped = platform?.replace("; wv", "")?.replace(Regex("""Version/\d+(\.\d+)*\s*"""), "")?.replace("  ", " ")?.trim()
    return stripped?.takeIf { it.contains("Chrome/") }
        ?: "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36"
}

private val SPOTIFY_LOGIN_HOSTS = listOf("spotify.com", "scdn.co", "google.com", "gstatic.com", "facebook.com", "apple.com")

private fun isSpotifyLoginHost(uri: Uri?): Boolean {
    if (uri?.scheme != "https") return false
    val host = uri.host?.lowercase() ?: return false
    return SPOTIFY_LOGIN_HOSTS.any { host == it || host.endsWith(".$it") }
}

/**
 * Spotify's own sign-in page. Once it lands on open.spotify.com the `sp_dc` session cookie is read
 * and handed to [onConnected], once. The password never passes through LFly.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyLoginDialog(busy: Boolean, onConnected: (String) -> Unit, onDismiss: () -> Unit) {
    val connected by rememberUpdatedState(onConnected)
    WebLoginDialog(
        title = stringResource(R.string.spotify_connect_title),
        hint = stringResource(R.string.spotify_connect_hint),
        busy = busy,
        onDismiss = onDismiss
    ) { context ->
        SpotifyAuth.clearBrowserCookies()
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = browserUserAgent(settings.userAgentString)
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            var sent = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    !isSpotifyLoginHost(request.url)

                override fun onPageFinished(view: WebView, url: String?) {
                    if (sent || url?.startsWith("https://open.spotify.com") != true) return
                    val token = CookieManager.getInstance().getCookie("https://open.spotify.com")
                        ?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("sp_dc=") }
                        ?.substringAfter('=')?.takeIf { it.isNotBlank() } ?: return
                    sent = true
                    Log.i(TAG, "Spotify sign-in completed")
                    view.visibility = View.GONE
                    connected(token)
                }
            }
            loadUrl("https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F")
        }
    }
}

/**
 * Reads the token out of a completed Discord login: Discord hides `localStorage` from the page once
 * it boots, but a fresh same-origin iframe still sees it. `alert` carries it out with no JS bridge.
 */
private const val DISCORD_TOKEN_SNIPPET = """
(function () {
  try {
    var frame = document.createElement('iframe');
    document.body.appendChild(frame);
    var token = frame.contentWindow.localStorage.token;
    document.body.removeChild(frame);
    alert(token ? token.slice(1, -1) : '');
  } catch (e) {
    alert('');
  }
})()
"""

private fun isDiscordSignedIn(url: String?): Boolean =
    url != null && url.startsWith("https://discord.com/") && !url.contains("/login") && !url.contains("/register")

/**
 * Discord's own sign-in page (two-step verification and passkeys work). Once the page leaves the
 * sign-in flow the account's token is read and handed to [onToken], once.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DiscordLoginDialog(busy: Boolean, onToken: (String) -> Unit, onDismiss: () -> Unit) {
    val captured by rememberUpdatedState(onToken)
    WebLoginDialog(
        title = stringResource(R.string.discord_connect_title),
        hint = stringResource(R.string.discord_connect_hint),
        busy = busy,
        onDismiss = onDismiss
    ) { context ->
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Discord reads a WebView's user agent as "Android Browser 4.0" and serves a blank page.
            settings.userAgentString = browserUserAgent(settings.userAgentString)
            var done = false
            var attempts = 0
            fun read() {
                if (done || attempts >= 12) return
                attempts++
                evaluateJavascript(DISCORD_TOKEN_SNIPPET, null)
            }
            webViewClient = object : WebViewClient() {
                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    if (isDiscordSignedIn(url)) read()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (isDiscordSignedIn(url)) read()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                    result.confirm()
                    if (done) return true
                    val token = message?.takeUnless { it.isBlank() || it == "null" || it == "undefined" }
                    if (token == null) {
                        // The client is still booting: the token is not written yet.
                        view.postDelayed({ read() }, 500L)
                        return true
                    }
                    done = true
                    Log.i(TAG, "Discord sign-in completed after $attempts read(s)")
                    view.visibility = View.GONE
                    captured(token)
                    return true
                }
            }
            loadUrl("https://discord.com/login")
        }
    }
}
