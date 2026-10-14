package org.sonora.android.api

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Shared HTTP client provider configured for music streaming, low latency, and connection reuse.
 */
object HttpClientProvider {

    private const val USER_AGENT =
        "Sonora/1.0.0 (Linux; Android; AudioPlayer) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile"

    private val userAgentInterceptor = Interceptor { chain ->
        val original = chain.request()
        val defaultUserAgent = if (original.url.host.contains("googlevideo.com", ignoreCase = true)) {
            YouTubeMusicClient.VISIONOS_UA
        } else {
            USER_AGENT
        }
        val isGoogleVideo = original.url.host.contains("googlevideo.com", ignoreCase = true)
        val requestBuilder = original.newBuilder()
            .header("User-Agent", original.header("User-Agent") ?: defaultUserAgent)
            .header("Accept-Language", "en-US,en;q=0.9")
        // Direct googlevideo URLs require the same Referer/Origin used by the resolver probe,
        // otherwise ExoPlayer's playback download can be rejected with HTTP 403 even though
        // the (header-complete) probe succeeded.
        if (isGoogleVideo) {
            if (original.header("Referer") == null) {
                requestBuilder.header("Referer", "https://www.youtube.com/")
            }
            if (original.header("Origin") == null) {
                requestBuilder.header("Origin", "https://www.youtube.com")
            }
            // Many googlevideo URLs are generated with rqh=1 (range required). ExoPlayer's
            // initial open (position 0) may omit the Range header, which the CDN rejects
            // with HTTP 403 — while the resolver probe (which always sends a Range) passes.
            // Force a range request so playback matches the probe and succeeds.
            if (original.header("Range") == null) {
                requestBuilder.header("Range", "bytes=0-")
            }
        }
        chain.proceed(requestBuilder.build())
    }

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(15, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor(userAgentInterceptor)
            .build()
    }

    val fastClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(7000, TimeUnit.MILLISECONDS)
            .readTimeout(7000, TimeUnit.MILLISECONDS)
            .callTimeout(7000, TimeUnit.MILLISECONDS)
            .build()
    }

    val pingClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(3500, TimeUnit.MILLISECONDS)
            .readTimeout(3500, TimeUnit.MILLISECONDS)
            .callTimeout(3500, TimeUnit.MILLISECONDS)
            .build()
    }

    val gson: Gson by lazy {
        GsonBuilder()
            .setLenient()
            .create()
    }

    /**
     * Helper to deserialize JSON with reified type safety.
     */
    inline fun <reified T> fromJson(json: String): T? {
        return try {
            gson.fromJson<T>(json, object : TypeToken<T>() {}.type)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Executes an HTTP GET request asynchronously with cancellation support and returns response body.
     */
    suspend fun getJson(
        url: String,
        headers: Map<String, String> = emptyMap(),
        client: OkHttpClient = fastClient
    ): Result<String> = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder().url(url)
        headers.forEach { (key, value) -> requestBuilder.header(key, value) }
        val request = requestBuilder.build()
        val call = client.newCall(request)

        try {
            kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation {
                    try {
                        call.cancel()
                    } catch (_: Throwable) {}
                }
                call.enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: IOException) {
                        if (continuation.isActive) {
                            continuation.resumeWith(Result.success(Result.failure(e)))
                        }
                    }

                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        response.use { resp ->
                            if (!resp.isSuccessful) {
                                if (continuation.isActive) {
                                    continuation.resumeWith(
                                        Result.success(Result.failure(IOException("HTTP ${resp.code}: ${resp.message} for $url")))
                                    )
                                }
                            } else {
                                val body = resp.body?.string()
                                if (body == null) {
                                    if (continuation.isActive) {
                                        continuation.resumeWith(
                                            Result.success(Result.failure(IOException("Empty response body from $url")))
                                        )
                                    }
                                } else {
                                    if (continuation.isActive) {
                                        continuation.resumeWith(Result.success(Result.success(body)))
                                    }
                                }
                            }
                        }
                    }
                })
            }
        } catch (e: Exception) {
            try { call.cancel() } catch (_: Throwable) {}
            Result.failure(e)
        }
    }

    /**
     * Asynchronous HEAD / GET ping to measure latency and verify responsiveness.
     */
    suspend fun ping(
        url: String,
        client: OkHttpClient = pingClient
    ): Pair<Boolean, Long> = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val request = Request.Builder()
                .url(url)
                .head()
                .build()

            client.newCall(request).execute().use { response ->
                val elapsed = System.currentTimeMillis() - start
                // Many instances might return 405 Method Not Allowed on HEAD, which still indicates connectivity!
                val reachable = response.isSuccessful || response.code == 405 || response.code == 301 || response.code == 302
                Pair(reachable, elapsed)
            }
        } catch (_: Exception) {
            // Fallback to GET with small byte limit if HEAD fails
            try {
                val getReq = Request.Builder()
                    .url(url)
                    .header("Range", "bytes=0-100")
                    .get()
                    .build()
                client.newCall(getReq).execute().use { response ->
                    val elapsed = System.currentTimeMillis() - start
                    Pair(response.isSuccessful || response.code in 200..399, elapsed)
                }
            } catch (_: Exception) {
                val elapsed = System.currentTimeMillis() - start
                Pair(false, elapsed)
            }
        }
    }
}
