/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord, GPL-3.0): auth/YtMusicLoginScreen.kt.
 */
package org.sonora.android.auth

import android.annotation.SuppressLint
import android.util.Log
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import org.json.JSONObject
import org.sonora.android.R
import org.sonora.android.ui.theme.LocalBackgroundScheme

private const val TAG = "YtMusicLogin"
private const val LOGIN_URL = "https://accounts.google.com/ServiceLogin" +
    "?ltmpl=music&service=youtube&prompt=select_account&continue=https%3A%2F%2Fmusic.youtube.com%2F"

/**
 * Google's own sign-in page for YouTube Music, in the app. The listener signs in on
 * accounts.google.com itself (two-step verification and passkeys work, it is the real page) and taps
 * Done once YouTube Music shows the account and channel they want. Only then is the session read:
 * the cookies, and who the page says it is from its own `ytcfg`. Reading it automatically would race
 * a multi-channel account still choosing its channel.
 *
 * [WebSessionMode.SWITCH_CHANNEL] opens YouTube Music with [initialCookie] instead, so its avatar
 * menu can switch to another channel of the same account.
 *
 * [busy] shows that the caller is checking a capture; [error] is shown under the title.
 */
@Composable
fun YtMusicLoginDialog(
    mode: WebSessionMode,
    initialCookie: String? = null,
    busy: Boolean = false,
    error: String? = null,
    onCaptured: (CapturedSession) -> Unit,
    onDismiss: () -> Unit
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageReady by remember { mutableStateOf(false) }
    var notYet by remember { mutableStateOf(false) }
    val scheme = LocalBackgroundScheme.current
    val captured by rememberUpdatedState(onCaptured)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        // The dialog's window stops above the navigation bar unless told otherwise: make it the whole screen.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        val bottomColor = scheme.gradient.last().toArgb()
        SideEffect {
            window?.let {
                WindowCompat.setDecorFitsSystemWindows(it, false)
                it.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                it.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
                it.setDimAmount(0f)
                it.setBackgroundDrawable(ColorDrawable(bottomColor))
                @Suppress("DEPRECATION")
                it.navigationBarColor = bottomColor
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(scheme.gradient))
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.yt_account_close), tint = scheme.onBackground)
                }
                Text(
                    text = stringResource(
                        if (mode == WebSessionMode.SIGN_IN) R.string.yt_account_sign_in_title else R.string.yt_account_switch_title
                    ),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = scheme.onBackground,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                Box(contentAlignment = Alignment.Center) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.padding(12.dp), strokeWidth = 2.dp)
                    } else {
                        val done = {
                            webView?.let { view ->
                                captureFrom(view, onCaptured = { notYet = false; captured(it) }, onUnavailable = { notYet = true })
                            }
                            Unit
                        }
                        // Lit up once YouTube Music is on screen, so it is clear what to tap.
                        if (pageReady) {
                            Button(onClick = done, modifier = Modifier.padding(end = 8.dp)) {
                                Text(stringResource(R.string.yt_account_done), fontWeight = FontWeight.SemiBold)
                            }
                        } else {
                            TextButton(enabled = false, onClick = done) {
                                Text(stringResource(R.string.yt_account_done), fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
            val note = error ?: when {
                notYet -> stringResource(R.string.yt_account_not_ready)
                pageReady -> stringResource(R.string.yt_account_ready)
                else -> stringResource(R.string.yt_account_hint)
            }
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = if (error != null) MaterialTheme.colorScheme.error else scheme.onBackgroundSecondary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                textAlign = TextAlign.Center
            )
            LoginWebView(
                mode = mode,
                initialCookie = initialCookie,
                onReady = { pageReady = it },
                onCreated = { webView = it },
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LoginWebView(
    mode: WebSessionMode,
    initialCookie: String?,
    onReady: (Boolean) -> Unit,
    onCreated: (WebView) -> Unit,
    modifier: Modifier = Modifier
) {
    val ready by rememberUpdatedState(onReady)
    var created by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(Unit) { onDispose { created?.destroy() } }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            if (mode == WebSessionMode.SIGN_IN) BrowserSession.clearGoogleCookies()
            else initialCookie?.let(BrowserSession::installGoogleCookies)
            WebView(context).apply {
                if (mode == WebSessionMode.SIGN_IN) {
                    clearCache(true)
                    clearHistory()
                    clearFormData()
                }
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        // Reaching YouTube Music only enables Done: the listener may still be choosing a channel.
                        Log.d(TAG, "Page: ${url?.let(::hostAndPath)}")
                        ready(url?.startsWith(GoogleSession.MUSIC_ORIGIN) == true)
                    }

                    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                        // YouTube Music moves within the page without a new load: it still counts.
                        if (url?.startsWith(GoogleSession.MUSIC_ORIGIN) == true) ready(true)
                    }
                }
                loadUrl(if (mode == WebSessionMode.SIGN_IN) LOGIN_URL else "${GoogleSession.MUSIC_ORIGIN}/")
                created = this
                onCreated(this)
            }
        }
    )
}

/** Reads the session from [view] when it holds one; otherwise tells [onUnavailable] (mid-login, or not signed in). */
private fun captureFrom(view: WebView, onCaptured: (CapturedSession) -> Unit, onUnavailable: () -> Unit) {
    val cookies = CookieManager.getInstance().getCookie(GoogleSession.MUSIC_ORIGIN)
    if (cookies == null || !GoogleSession.hasApiSid(cookies)) {
        Log.w(TAG, "Done tapped, but the browser holds no Google login yet (${cookies?.let { GoogleSession.cookies(it).size } ?: 0} cookies)")
        onUnavailable()
        return
    }
    CookieManager.getInstance().flush()
    view.evaluateJavascript(YTCFG_PROBE) { raw ->
        val config = parseProbe(raw)
        if (config == null || config.optString("loggedIn") != "true") {
            Log.w(TAG, "Done tapped before the page showed a signed-in identity")
            onUnavailable()
            return@evaluateJavascript
        }
        val pageId = config.stringOrNull("pageId")
        Log.i(TAG, "Signed-in page read: brand channel ${pageId != null}")
        onCaptured(
            CapturedSession(
                cookie = cookies,
                pageId = pageId,
                dataSyncId = pageId ?: GoogleSession.normalizeDataSyncId(config.stringOrNull("dataSyncId")),
                authUser = config.stringOrNull("authUser"),
                visitorData = config.stringOrNull("visitorData"),
                clientVersion = config.stringOrNull("clientVersion")
            )
        )
    }
}

/** The page's identity as the page has it; null on a page without `ytcfg`. Returned as an object, so it is not double-encoded. */
private const val YTCFG_PROBE = """
(function () {
  try {
    if (!window.ytcfg || !window.ytcfg.get) return null;
    var get = function (key) {
      var value = window.ytcfg.get(key);
      return (value === undefined || value === null || value === '') ? null : String(value);
    };
    return {
      loggedIn: String(!!window.ytcfg.get('LOGGED_IN')),
      pageId: get('DELEGATED_SESSION_ID'),
      dataSyncId: get('DATASYNC_ID'),
      authUser: get('SESSION_INDEX'),
      visitorData: get('VISITOR_DATA'),
      clientVersion: get('INNERTUBE_CLIENT_VERSION')
    };
  } catch (e) {
    return null;
  }
})()
"""

/** Where a page is, for the log: never its query, which can carry tokens. */
private fun hostAndPath(url: String): String = runCatching {
    val uri = android.net.Uri.parse(url)
    "${uri.host}${uri.path.orEmpty()}"
}.getOrDefault("?")

private fun parseProbe(raw: String?): JSONObject? =
    raw?.takeIf { it != "null" && it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() }

private fun JSONObject.stringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }
