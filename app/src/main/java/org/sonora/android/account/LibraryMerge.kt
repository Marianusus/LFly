package org.sonora.android.account

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import org.sonora.android.api.HttpClientProvider
import org.sonora.android.api.Track
import org.sonora.android.data.MusicRepository
import org.sonora.android.data.Playlist

/**
 * The three-way merge of two libraries, shared by the sync over LFly Connect ([LibrarySync]) and
 * over Google Drive ([DriveSync]), and the same as the computer's `drive.rs`.
 *
 * The base is what the two sides agreed on last time (ids only): a song in the base but gone on
 * one side was deleted there, so it goes; a song missing from the base is new, so it stays.
 * A playlist changed on both sides keeps the newer one (`updatedAt`); history is joined.
 */
object LibraryMerge {
    private const val HISTORY_MAX = 100
    private val KEYS = listOf("favorites", "playlists", "history")

    data class Base(val favorites: Set<String>, val playlists: Set<String>)

    private fun JsonElement.id(): String? = runCatching { asJsonObject.get("id")?.asString }.getOrNull()
    private fun JsonElement.updatedAt(): Long = runCatching { asJsonObject.get("updatedAt")?.asLong ?: 0L }.getOrDefault(0L)

    internal fun mergeList(base: Set<String>, local: JsonArray, remote: JsonArray, newer: (JsonElement, JsonElement) -> Boolean): JsonArray {
        val theirs = remote.mapNotNull { e -> e.id()?.let { it to e } }.toMap()
        val mine = local.mapNotNull { it.id() }.toSet()
        val seen = HashSet<String>()
        val out = JsonArray()
        for (e in local) {
            val id = e.id() ?: continue
            if (!seen.add(id)) continue
            val r = theirs[id]
            when {
                r != null -> out.add(if (newer(r, e)) r else e)
                id in base -> Unit // deleted on the other side
                else -> out.add(e)
            }
        }
        for (e in remote) {
            val id = e.id() ?: continue
            if (id in mine || !seen.add(id)) continue
            if (id !in base) out.add(e) // otherwise deleted here
        }
        return out
    }

    internal fun mergeHistory(local: JsonArray, remote: JsonArray): JsonArray {
        val seen = HashSet<String>()
        val out = JsonArray()
        val cap = maxOf(HISTORY_MAX, local.size())
        for (e in local + remote) {
            val id = e.id() ?: continue
            if (out.size() < cap && seen.add(id)) out.add(e)
        }
        return out
    }

    fun list(o: JsonObject, key: String): JsonArray = o.getAsJsonArray(key) ?: JsonArray()

    fun merge(base: Base, local: JsonObject, remote: JsonObject): JsonObject = JsonObject().apply {
        add("favorites", mergeList(base.favorites, list(local, "favorites"), list(remote, "favorites")) { _, _ -> false })
        add("playlists", mergeList(base.playlists, list(local, "playlists"), list(remote, "playlists")) { r, l -> r.updatedAt() > l.updatedAt() })
        add("history", mergeHistory(list(local, "history"), list(remote, "history")))
    }

    fun baseOf(o: JsonObject) = Base(ids(list(o, "favorites")), ids(list(o, "playlists")))

    fun same(a: JsonObject, b: JsonObject) = KEYS.all { list(a, it) == list(b, it) }

    private fun ids(a: JsonArray): Set<String> = a.mapNotNull { it.id() }.toSet()

    /** This phone's library, as the sync carries it. */
    fun snapshot(repository: MusicRepository): JsonObject {
        val gson = HttpClientProvider.gson
        return JsonObject().apply {
            add("favorites", gson.toJsonTree(repository.favorites.value))
            add("playlists", gson.toJsonTree(repository.playlists.value))
            add("history", gson.toJsonTree(repository.history.value))
        }
    }

    /** Puts [merged] in the library if it differs from [local]; true if it did. */
    fun apply(repository: MusicRepository, local: JsonObject, merged: JsonObject): Boolean {
        if (same(local, merged)) return false
        val gson = HttpClientProvider.gson
        repository.applySyncedLibrary(
            gson.fromJson(list(merged, "favorites"), object : TypeToken<List<Track>>() {}.type),
            gson.fromJson(list(merged, "playlists"), object : TypeToken<List<Playlist>>() {}.type),
            gson.fromJson(list(merged, "history"), object : TypeToken<List<Track>>() {}.type),
        )
        return true
    }
}
