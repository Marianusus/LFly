package org.sonora.android.api

/**
 * In-memory, generic TTL cache for YouTube Music discovery detail responses.
 * No disk persistence: entries expire after [ttlMs] using an injectable [clock].
 */
internal class YouTubeDiscoveryCache<T>(
    private val ttlMs: Long = 30 * 60 * 1000L,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    private data class Entry<T>(val value: T, val expiresAt: Long)

    private val map = HashMap<String, Entry<T>>()

    @Synchronized
    fun get(key: String): T? {
        val entry = map[key] ?: return null
        if (clock() >= entry.expiresAt) {
            map.remove(key)
            return null
        }
        return entry.value
    }

    @Synchronized
    fun put(key: String, value: T) {
        map[key] = Entry(value, clock() + ttlMs)
    }

    @Synchronized
    fun clear() {
        map.clear()
    }
}
