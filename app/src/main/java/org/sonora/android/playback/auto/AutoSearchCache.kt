package org.sonora.android.playback.auto

import org.sonora.android.api.Track

/**
 * Recent car searches. Online results stay until pushed out. Fallback results after a failed search
 * expire quickly: they spare a dead network the repeat calls of one search, but must not hide a retry.
 */
internal class AutoSearchCache(private val now: () -> Long) {

    private companion object {
        const val MAX_QUERIES = 8
        const val FALLBACK_TTL_MS = 30_000L
    }

    private class Entry(val tracks: List<Track>, val expiresAtMs: Long)

    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > MAX_QUERIES
    }

    fun get(query: String): List<Track>? {
        val entry = entries[query] ?: return null
        if (now() < entry.expiresAtMs) return entry.tracks
        entries.remove(query)
        return null
    }

    fun putOnline(query: String, tracks: List<Track>) {
        entries[query] = Entry(tracks, Long.MAX_VALUE)
    }

    fun putFallback(query: String, tracks: List<Track>) {
        entries[query] = Entry(tracks, now() + FALLBACK_TTL_MS)
    }
}
