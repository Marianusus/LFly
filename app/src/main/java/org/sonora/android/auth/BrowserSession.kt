/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord, GPL-3.0): auth/WebSession.kt.
 */
package org.sonora.android.auth

import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage

/**
 * The in-app browser's own cookie jar, which is not the app's. Signing out of LFly forgets the saved
 * session; the browser's copy has to go too, or the next sign-in is waved straight through as the
 * account just signed out of. Only Google's cookies are touched, and Google's logout is never visited,
 * so other saved accounts stay valid.
 */
object BrowserSession {
    private const val TAG = "BrowserSession"

    private val GOOGLE_ORIGINS = listOf(
        "https://accounts.google.com",
        "https://www.google.com",
        "https://google.com",
        "https://music.youtube.com",
        "https://www.youtube.com",
        "https://youtube.com"
    )
    private val YOUTUBE_ORIGINS = listOf("https://music.youtube.com", "https://www.youtube.com", "https://youtube.com")

    /** Puts a saved account's cookies in the browser, so its channel picker shows that account. */
    fun installGoogleCookies(cookieHeader: String) {
        clearGoogleCookies()
        val manager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return
        val entries = GoogleSession.cookies(cookieHeader).map { (name, value) -> "$name=$value; Path=/; Secure" }
        YOUTUBE_ORIGINS.forEach { origin -> entries.forEach { manager.setCookie(origin, it) } }
        runCatching { manager.flush() }
    }

    /**
     * Forgets the Google login the browser holds. There is no removal by domain, so each cookie is
     * overwritten with an expired one of the same name, in the forms it may have been set with.
     */
    fun clearGoogleCookies() {
        val manager = runCatching { CookieManager.getInstance() }.getOrElse {
            Log.w(TAG, "No cookie manager to clear: ${it.message}")
            return
        }
        GOOGLE_ORIGINS.forEach { origin ->
            val jar = manager.getCookie(origin) ?: return@forEach
            val host = origin.substringAfter("://")
            jar.split(';').forEach { entry ->
                val name = entry.substringBefore('=').trim()
                if (name.isEmpty()) return@forEach
                // Secure too: a __Secure- or __Host- cookie is only replaced by a Secure one, and a
                // jar left with half its cookies is what Google answers with CookieMismatch.
                val expired = "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0; Secure"
                manager.setCookie(origin, "$expired; Path=/")
                manager.setCookie(origin, "$expired; Path=/; Domain=$host")
                manager.setCookie(origin, "$expired; Path=/; Domain=.$host")
                manager.setCookie(origin, "$expired; Path=/; Domain=.${host.substringAfter('.')}")
            }
        }
        runCatching {
            val storage = WebStorage.getInstance()
            GOOGLE_ORIGINS.forEach { storage.deleteOrigin(it) }
            manager.flush()
        }
    }
}
