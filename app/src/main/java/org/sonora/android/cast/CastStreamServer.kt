package org.sonora.android.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import org.sonora.android.api.Track
import org.sonora.android.playback.SongBytes
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom

/**
 * The song the Chromecast plays, served from this phone over the home network.
 *
 * A Chromecast cannot play what the phone plays by itself: YouTube's links are made for the phone's
 * own client and headers, and downloads are files on the phone. So it is handed a link to this
 * small HTTP server, which reads the song the way the phone's player does ([SongBytes]) and answers
 * byte ranges, so the Chromecast can seek. Links carry a random secret and only the song being cast
 * is served.
 */
internal object CastStreamServer {
    private const val TAG = "LFlyCastStream"
    private const val CHUNK = 64 * 1024
    private const val REOPENS = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val random = SecureRandom()

    @Volatile private var server: ServerSocket? = null
    @Volatile private var secret: String = ""

    /** The song that may be served, by id. */
    @Volatile var allowed: Track? = null

    /** Starts the server if needed; false when the phone has no address on the local network. */
    @Synchronized
    fun start(): Boolean {
        if (server?.isClosed == false) return true
        secret = ByteArray(12).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val socket = runCatching { ServerSocket(0) }.getOrElse {
            Log.w(TAG, "Could not open the server: ${it.message}")
            return false
        }
        server = socket
        Thread({ accept(socket) }, "LFlyCastStream").apply { isDaemon = true }.start()
        Log.i(TAG, "Serving on port ${socket.localPort}")
        return true
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        allowed = null
        scope.coroutineContext.cancelChildren()
    }

    /** The link the Chromecast fetches [track] from; [seq] keeps it from reusing an older copy. */
    fun urlFor(context: Context, track: Track, seq: Int): String? {
        val port = server?.localPort ?: return null
        val host = lanAddress(context)?.hostAddress ?: return null
        return "http://$host:$port/$secret/$seq/${Uri.encode(track.id)}"
    }

    private fun accept(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: SocketException) {
                return
            } catch (e: IOException) {
                Log.w(TAG, "Accept failed: ${e.message}")
                continue
            }
            scope.launch { runCatching { client.use { handle(it) } } }
        }
    }

    private suspend fun handle(socket: Socket) {
        socket.soTimeout = 30_000
        val input = socket.getInputStream()
        val requestLine = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0).orEmpty()
        val path = parts.getOrNull(1).orEmpty().substringBefore('?').trimStart('/').split('/')
        val out = BufferedOutputStream(socket.getOutputStream(), CHUNK)
        val track = allowed
        if ((method != "GET" && method != "HEAD") || path.size != 3 || path[0] != secret ||
            track == null || Uri.decode(path[2]) != track.id
        ) {
            status(out, "404 Not Found", emptyMap())
            return
        }
        val range = parseRange(headers["range"])
        val from = range?.first ?: 0L
        val app = CastSpeaker.context ?: return status(out, "503 Service Unavailable", emptyMap())
        var source = SongBytes.open(app, track, from, fresh = false)
            ?: return status(out, "502 Bad Gateway", emptyMap())
        try {
            val total = if (source.length >= 0) from + source.length else -1L
            if (total >= 0 && from >= total && total > 0) {
                return status(out, "416 Range Not Satisfiable", mapOf("Content-Range" to "bytes */$total"))
            }
            // Only open-ended ranges are answered to their end; a closed one is cut at its last byte.
            val last = when {
                total < 0 -> -1L
                range?.second != null -> minOf(range.second!!, total - 1)
                else -> total - 1
            }
            val count = if (last >= 0) last - from + 1 else -1L
            val head = linkedMapOf(
                "Content-Type" to source.mime.ifBlank { "audio/mp4" },
                "Accept-Ranges" to "bytes",
                "Access-Control-Allow-Origin" to "*",
            )
            if (count >= 0) head["Content-Length"] = count.toString()
            if (range != null && total >= 0) head["Content-Range"] = "bytes $from-$last/$total"
            status(out, if (range != null && total >= 0) "206 Partial Content" else "200 OK", head, end = false)
            if (method == "HEAD") return

            val buffer = ByteArray(CHUNK)
            var offset = from
            var left = if (count >= 0) count else Long.MAX_VALUE
            var reopens = 0
            while (left > 0) {
                val read = try {
                    source.read(buffer)
                } catch (e: IOException) {
                    // The upstream dropped (often idle while the Chromecast's buffer was full): go on from here.
                    runCatching { source.close() }
                    if (++reopens > REOPENS) throw e
                    source = SongBytes.open(app, track, offset, fresh = reopens > 1) ?: throw e
                    continue
                }
                if (read < 0) break
                val take = minOf(read.toLong(), left).toInt()
                try {
                    out.write(buffer, 0, take)
                } catch (_: IOException) {
                    return // The Chromecast closed the connection: it seeked or moved on.
                }
                offset += take
                left -= take
            }
            runCatching { out.flush() }
        } finally {
            runCatching { source.close() }
        }
    }

    private fun status(out: OutputStream, status: String, headers: Map<String, String>, end: Boolean = true) {
        val text = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
            if (end) append("Content-Length: 0\r\n")
            append("Connection: close\r\n\r\n")
        }
        runCatching {
            out.write(text.toByteArray(Charsets.ISO_8859_1))
            out.flush()
        }
    }

    /** `bytes=N-` or `bytes=N-M`; null without a usable range. */
    internal fun parseRange(header: String?): Pair<Long, Long?>? {
        val spec = header?.trim()?.removePrefix("bytes=")?.takeIf { header.trim().startsWith("bytes=") } ?: return null
        val first = spec.substringBefore(',')
        val start = first.substringBefore('-').trim().toLongOrNull() ?: return null
        val end = first.substringAfter('-', "").trim().toLongOrNull()
        if (end != null && end < start) return null
        return start to end
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (line.isEmpty()) null else line.toString()
            if (c == '\n'.code) return line.toString().trimEnd('\r')
            if (line.length > 8_192) return null
            line.append(c.toChar())
        }
    }

    /** This phone's IPv4 address on the network the Chromecast is on (Wi-Fi, or its own hotspot). */
    private fun lanAddress(context: Context): InetAddress? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        connectivity?.activeNetwork?.let { network ->
            connectivity.getLinkProperties(network)?.linkAddresses
                ?.map { it.address }
                ?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.let { return it }
        }
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
        }.getOrNull()
    }
}
