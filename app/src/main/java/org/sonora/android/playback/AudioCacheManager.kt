package org.sonora.android.playback

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import org.sonora.android.api.HttpClientProvider
import java.io.File

/**
 * Manages native Media3 disk caching using [SimpleCache] with an LRU eviction strategy.
 * Ensures streamed audio tracks are buffered reliably, load instantly on repeat plays,
 * and work smoothly under intermittent network conditions.
 */
@OptIn(UnstableApi::class)
object AudioCacheManager {

    private const val TAG = "AudioCacheManager"
    private const val CACHE_DIR_NAME = "sonora_audio_cache"
    const val DEFAULT_MAX_CACHE_BYTES = 200L * 1024L * 1024L // 200 MB

    @Volatile
    private var simpleCacheInstance: SimpleCache? = null

    @Volatile
    private var cacheDataSourceFactoryInstance: DataSource.Factory? = null

    private val lock = Any()

    /**
     * Initializes the cache manager eagerly during application startup.
     */
    fun init(context: Context, maxCacheBytes: Long = DEFAULT_MAX_CACHE_BYTES) {
        synchronized(lock) {
            if (simpleCacheInstance == null) {
                getOrCreateSimpleCache(context.applicationContext, maxCacheBytes)
            }
        }
    }

    /**
     * Retrieves or creates the singleton [SimpleCache] instance.
     */
    fun getSimpleCache(context: Context): SimpleCache {
        return synchronized(lock) {
            simpleCacheInstance ?: getOrCreateSimpleCache(context.applicationContext, DEFAULT_MAX_CACHE_BYTES)
        }
    }

    private fun getOrCreateSimpleCache(context: Context, maxCacheBytes: Long): SimpleCache {
        val existing = simpleCacheInstance
        if (existing != null) return existing

        val cacheDir = File(context.cacheDir, CACHE_DIR_NAME).apply {
            if (!exists()) {
                mkdirs()
            }
        }

        val databaseProvider = StandaloneDatabaseProvider(context)
        val evictor = LeastRecentlyUsedCacheEvictor(maxCacheBytes)

        val cache = try {
            SimpleCache(cacheDir, evictor, databaseProvider)
        } catch (e: Throwable) {
            Log.w(TAG, "SimpleCache failed with primary directory, attempting fallback: ${e.message}")
            val fallbackDir = File(context.cacheDir, "${CACHE_DIR_NAME}_fb").apply {
                deleteRecursively()
                mkdirs()
            }
            SimpleCache(fallbackDir, evictor, databaseProvider)
        }
        simpleCacheInstance = cache
        Log.i(TAG, "Initialized SimpleCache with max size ${maxCacheBytes / (1024 * 1024)} MB")
        return cache
    }

    /**
     * Returns a [DataSource.Factory] backed by OkHttp and [SimpleCache].
     * Streamed data is cached transparently on disk with errors gracefully bypassed.
     */
    fun getCacheDataSourceFactory(context: Context): DataSource.Factory {
        val cachedFactory = cacheDataSourceFactoryInstance
        if (cachedFactory != null) return cachedFactory

        return synchronized(lock) {
            cacheDataSourceFactoryInstance ?: run {
                val appContext = context.applicationContext
                val cache = getSimpleCache(appContext)

                // High-performance OkHttp upstream source
                val httpDataSourceFactory = try {
                    OkHttpDataSource.Factory(HttpClientProvider.okHttpClient)
                } catch (e: Throwable) {
                    Log.w(TAG, "Fallback to DefaultHttpDataSource due to: ${e.message}")
                    DefaultHttpDataSource.Factory()
                        .setConnectTimeoutMs(10000)
                        .setReadTimeoutMs(15000)
                        .setAllowCrossProtocolRedirects(true)
                }

                // DefaultDataSource handles android asset/content schemes as well as HTTP
                val upstreamFactory = DefaultDataSource.Factory(appContext, httpDataSourceFactory)

                val sinkFactory = CacheDataSink.Factory()
                    .setCache(cache)
                    .setFragmentSize(CacheDataSink.DEFAULT_FRAGMENT_SIZE)

                val factory = CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(upstreamFactory)
                    .setCacheWriteDataSinkFactory(sinkFactory)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

                cacheDataSourceFactoryInstance = factory
                factory
            }
        }
    }

    /**
     * Proactively pre-fetches the initial [bytesToPrefetch] of an audio stream URL
     * into [SimpleCache]. When ExoPlayer starts playback for this stream, the initial
     * data is already stored locally on disk, resulting in 0ms startup latency.
     */
    fun prefetchAudio(context: Context, url: String, bytesToPrefetch: Long = 2L * 1024L * 1024L) {
        if (url.isBlank()) return
        val uri = try {
            Uri.parse(url)
        } catch (_: Exception) {
            return
        }

        try {
            val cache = getSimpleCache(context)
            val isCached = cache.isCached(url, 0L, minOf(512L * 1024L, bytesToPrefetch))
            if (isCached) {
                Log.d(TAG, "Audio already cached in SimpleCache for: $url")
                return
            }

            val dataSource = getCacheDataSourceFactory(context).createDataSource()
            val dataSpec = DataSpec.Builder()
                .setUri(uri)
                .setPosition(0L)
                .setLength(bytesToPrefetch)
                .build()

            dataSource.open(dataSpec)
            val buffer = ByteArray(32 * 1024)
            var totalRead = 0L
            while (totalRead < bytesToPrefetch) {
                val toRead = minOf(buffer.size.toLong(), bytesToPrefetch - totalRead).toInt()
                val read = dataSource.read(buffer, 0, toRead)
                if (read == C.RESULT_END_OF_INPUT || read <= 0) break
                totalRead += read
            }
            dataSource.close()
            Log.i(TAG, "✓ Prefetched $totalRead bytes into SimpleCache for $url")
        } catch (e: Exception) {
            Log.w(TAG, "Prefetch error for $url: ${e.message}")
        }
    }

    /**
     * Removes a single cached resource (keyed by its URL). Used to drop a corrupted or
     * partial fragment so a retry re-downloads clean bytes from upstream instead of
     * re-serving the same broken segment.
     */
    fun removeCachedUrl(url: String?) {
        if (url.isNullOrBlank()) return
        synchronized(lock) {
            try {
                simpleCacheInstance?.removeResource(url)
                Log.d(TAG, "Removed cached resource for: $url")
            } catch (e: Exception) {
                Log.w(TAG, "Failed removing cached resource for $url: ${e.message}")
            }
        }
    }

    /**
     * Returns the total number of bytes currently stored in the cache.
     */
    fun getCacheSizeBytes(): Long {
        return simpleCacheInstance?.cacheSpace ?: 0L
    }

    /**
     * Clears all cached audio data from disk.
     */
    fun clearCache(context: Context? = null) {
        synchronized(lock) {
            try {
                simpleCacheInstance?.keys?.forEach { key ->
                    simpleCacheInstance?.removeResource(key)
                }
                Log.i(TAG, "Audio cache successfully cleared.")
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing audio cache", e)
            }
        }
    }

    /**
     * Convenience 0-arg overload for clearing cache.
     */
    fun clearCache() {
        clearCache(null)
    }

    /**
     * Releases cache resources. Typically invoked during app termination or unit testing.
     */
    fun release() {
        synchronized(lock) {
            try {
                simpleCacheInstance?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing SimpleCache", e)
            } finally {
                simpleCacheInstance = null
                cacheDataSourceFactoryInstance = null
            }
        }
    }
}
