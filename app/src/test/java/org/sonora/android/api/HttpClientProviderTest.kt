package org.sonora.android.api

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

class HttpClientProviderTest {

    /** Serves one HTTP response, then closes the connection, and returns the base URL. */
    private fun serveOnce(rawResponse: String): String {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            server.use { socket ->
                socket.accept().use { client ->
                    val reader = client.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) Unit
                    client.getOutputStream().apply {
                        write(rawResponse.toByteArray())
                        flush()
                    }
                }
            }
        }
        return "http://127.0.0.1:${server.localPort}/"
    }

    @Test
    fun `getJson returns the body of a complete response`() = runBlocking {
        val url = serveOnce("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok")

        val result = withTimeout(5_000) { HttpClientProvider.getJson(url) }

        assertEquals("ok", result.getOrNull())
    }

    @Test
    fun `getJson fails instead of hanging when the connection drops mid-body`() = runBlocking {
        val url = serveOnce("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\nConnection: close\r\n\r\npartial")

        val result = withTimeout(5_000) { HttpClientProvider.getJson(url) }

        assertTrue(result.isFailure)
    }
}
