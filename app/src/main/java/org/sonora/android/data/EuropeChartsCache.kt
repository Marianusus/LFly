package org.sonora.android.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import org.sonora.android.api.CountryTopTrack
import org.sonora.android.api.EuropeChartsClient
import org.sonora.android.api.Track

/**
 * Disk cache for the Home "Top tracks (EU)" carousel. Keeps the last chart snapshot (already
 * enriched with a resolved YouTube videoId + real duration) so the section is instant on cold
 * start and the YT pre-resolution is not repeated within the TTL.
 */
object EuropeChartsCache {

    private const val TAG = "EuropeChartsCache"
    private const val FILE_NAME = "eu_top_tracks_v2.json"
    private const val TTL_MS = 24L * 60 * 60 * 1000

    private val gson = Gson()

    data class Snapshot(
        val tracks: List<CountryTopTrack>,
        val updatedAt: Long
    ) {
        val isFresh: Boolean get() = System.currentTimeMillis() - updatedAt < TTL_MS
    }

    private data class CacheFile(val updatedAt: Long, val entries: List<CacheEntry>)
    private data class CacheEntry(val cc: String, val track: Track)

    fun read(context: Context): Snapshot? {
        return try {
            val file = java.io.File(context.filesDir, FILE_NAME)
            if (!file.exists()) return null
            val parsed = gson.fromJson(file.readText(), CacheFile::class.java) ?: return null
            val byCode = EuropeChartsClient.COUNTRIES.associateBy { it.code }
            val tracks = parsed.entries.mapNotNull { entry ->
                val country = byCode[entry.cc] ?: return@mapNotNull null
                CountryTopTrack(country.code, country.name, entry.track.copy(streamUrl = null))
            }
            if (tracks.isEmpty()) null else Snapshot(tracks, parsed.updatedAt)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read EU charts cache: ${e.message}")
            null
        }
    }

    fun write(context: Context, tracks: List<CountryTopTrack>) {
        try {
            val payload = CacheFile(
                updatedAt = System.currentTimeMillis(),
                entries = tracks.map { CacheEntry(it.countryCode, it.track.copy(streamUrl = null)) }
            )
            java.io.File(context.filesDir, FILE_NAME).writeText(gson.toJson(payload))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write EU charts cache: ${e.message}")
        }
    }
}
