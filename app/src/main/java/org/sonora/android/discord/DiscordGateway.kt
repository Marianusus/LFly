/*
 * Modelled on Kizzy's gateway client (dead8309/Kizzy, GPL-3.0) as BitChord
 * (https://github.com/kushagrasinghx/BitChord, GPL-3.0) uses it; rewritten on OkHttp and org.json.
 */
package org.sonora.android.discord

import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.sonora.android.api.HttpClientProvider
import kotlin.random.Random

/**
 * One session on Discord's gateway as the listener's own account, kept only to set its presence.
 * There is no official way for an app to set a user's presence, so the socket identifies itself as
 * Discord for Android. [setPresence] keeps the latest presence and sends it once the session is up,
 * again after every reconnect.
 */
internal class DiscordGateway(
    private val token: String,
    /** Discord refused the token (signed out, or changed password): stop and say so. */
    private val onAuthFailed: () -> Unit
) {
    private companion object {
        const val TAG = "DiscordGateway"
        const val GATEWAY = "wss://gateway.discord.gg/?encoding=json&v=10"
        const val QUERY = "/?encoding=json&v=10"
        const val OP_DISPATCH = 0
        const val OP_HEARTBEAT = 1
        const val OP_IDENTIFY = 2
        const val OP_PRESENCE = 3
        const val OP_RESUME = 6
        const val OP_RECONNECT = 7
        const val OP_INVALID_SESSION = 9
        const val OP_HELLO = 10
        const val CLOSE_AUTH_FAILED = 4004
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var socket: WebSocket? = null
    private var heartbeat: Job? = null
    private var reconnect: Job? = null
    private var sequence: Int? = null
    private var sessionId: String? = null
    private var resumeUrl: String? = null
    private var ready = false
    private var closed = false
    private var backoffMs = 2_000L
    private var presence: JSONObject = idle()

    val isConnected: Boolean get() = synchronized(lock) { ready }

    /** The `d` of a presence update; sent now when the session is up, else as soon as it is. */
    fun setPresence(presence: JSONObject) {
        synchronized(lock) {
            this.presence = presence
            if (closed) return
            if (ready) send(OP_PRESENCE, presence) else if (socket == null) open()
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            ready = false
            heartbeat?.cancel()
            reconnect?.cancel()
            socket?.close(1000, null)
            socket = null
        }
    }

    private fun open() {
        val url = resumeUrl?.takeIf { sessionId != null }?.let { it.trimEnd('/') + QUERY } ?: GATEWAY
        socket = HttpClientProvider.okHttpClient.newWebSocket(Request.Builder().url(url).build(), Listener())
    }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val payload = runCatching { JSONObject(text) }.getOrNull() ?: return
            synchronized(lock) {
                if (webSocket != socket) return
                if (!payload.isNull("s")) sequence = payload.optInt("s")
                when (payload.optInt("op", -1)) {
                    OP_HELLO -> {
                        startHeartbeat(payload.optJSONObject("d")?.optLong("heartbeat_interval") ?: 41_250L)
                        val session = sessionId
                        if (session != null && sequence != null) {
                            send(OP_RESUME, JSONObject().put("token", token).put("session_id", session).put("seq", sequence))
                        } else {
                            send(OP_IDENTIFY, identify())
                        }
                    }
                    OP_DISPATCH -> when (payload.optString("t")) {
                        "READY" -> {
                            val d = payload.optJSONObject("d")
                            sessionId = d?.optString("session_id")?.takeIf { it.isNotBlank() }
                            resumeUrl = d?.optString("resume_gateway_url")?.takeIf { it.isNotBlank() }
                            onReady()
                        }
                        "RESUMED" -> onReady()
                    }
                    OP_HEARTBEAT -> send(OP_HEARTBEAT, sequence)
                    OP_RECONNECT -> restart(resume = true)
                    OP_INVALID_SESSION -> restart(resume = payload.optBoolean("d", false))
                }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket, code)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "Gateway connection lost: ${t.message}")
            dropped(webSocket, response?.code ?: -1)
        }
    }

    private fun onReady() {
        ready = true
        backoffMs = 2_000L
        Log.i(TAG, "Gateway session ready")
        send(OP_PRESENCE, presence)
    }

    private fun dropped(webSocket: WebSocket, code: Int) {
        synchronized(lock) {
            if (webSocket != socket) return
            socket = null
            ready = false
            heartbeat?.cancel()
            if (closed) return
            if (code == CLOSE_AUTH_FAILED) {
                Log.w(TAG, "Discord refused the saved login")
                closed = true
                scope.launch { onAuthFailed() }
                return
            }
            scheduleReconnect()
        }
    }

    /** Drops this socket for a new one, resuming the session or starting over. */
    private fun restart(resume: Boolean) {
        if (!resume) {
            sessionId = null
            sequence = null
            resumeUrl = null
        }
        val old = socket
        socket = null
        ready = false
        heartbeat?.cancel()
        old?.close(4000, null)
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        reconnect?.cancel()
        val wait = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
        reconnect = scope.launch {
            delay(wait)
            synchronized(lock) { if (!closed && socket == null) open() }
        }
    }

    private fun startHeartbeat(intervalMs: Long) {
        heartbeat?.cancel()
        heartbeat = scope.launch {
            delay((intervalMs * Random.nextDouble()).toLong())
            while (isActive) {
                synchronized(lock) { send(OP_HEARTBEAT, sequence) }
                delay(intervalMs)
            }
        }
    }

    private fun send(op: Int, d: Any?) {
        socket?.send(JSONObject().put("op", op).put("d", d ?: JSONObject.NULL).toString())
    }

    private fun identify(): JSONObject = JSONObject()
        .put("token", token)
        .put("capabilities", 16381)
        .put("compress", false)
        .put("large_threshold", 100)
        .put(
            "properties",
            JSONObject().put("os", "Android").put("browser", "Discord Android").put("device", Build.DEVICE)
        )
        .put("presence", presence)

    private fun idle(): JSONObject = JSONObject()
        .put("since", 0)
        .put("activities", org.json.JSONArray())
        .put("status", "online")
        .put("afk", true)
}
