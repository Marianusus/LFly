package org.sonora.android.account

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.sonora.android.data.MusicRepository

/**
 * Library sync with LFly for computer over LFly Connect: same network, same account, nothing to
 * set up. The computer opens a short link to this phone, proves the account, offers its library;
 * this phone merges it with its own ([LibraryMerge]), keeps the result and answers with it.
 * Each peer has its own base, kept here by its device id.
 */
object LibrarySync {
    private const val TAG = "LibrarySync"
    private const val PREFS = "lfly_library_sync"

    data class Status(val enabled: Boolean, val lastSync: Long?, val lastDevice: String?)

    private lateinit var app: Context
    private val _status = MutableStateFlow(Status(true, null, null))
    val status: StateFlow<Status> = _status.asStateFlow()

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init(context: Context) {
        app = context.applicationContext
        val p = prefs()
        _status.value = Status(p.getBoolean("enabled", true), p.getLong("lastSync", 0L).takeIf { it > 0 }, p.getString("lastDevice", null))
    }

    fun setEnabled(on: Boolean) {
        prefs().edit().putBoolean("enabled", on).apply()
        _status.value = _status.value.copy(enabled = on)
    }

    /** A computer of the account offers its library: merge, keep, and return what to answer (null: off). */
    @Synchronized
    fun answer(repository: MusicRepository, peerId: String, peerName: String?, offered: JsonObject): JsonObject? {
        if (!::app.isInitialized || !_status.value.enabled) return null
        val p = prefs()
        val base = LibraryMerge.Base(
            p.getStringSet("fav:$peerId", emptySet()) ?: emptySet(),
            p.getStringSet("pl:$peerId", emptySet()) ?: emptySet(),
        )
        val local = LibraryMerge.snapshot(repository)
        val merged = LibraryMerge.merge(base, local, offered)
        val changed = LibraryMerge.apply(repository, local, merged)
        val agreed = LibraryMerge.baseOf(merged)
        val now = System.currentTimeMillis()
        p.edit()
            .putStringSet("fav:$peerId", agreed.favorites)
            .putStringSet("pl:$peerId", agreed.playlists)
            .putLong("lastSync", now)
            .putString("lastDevice", peerName)
            .apply()
        _status.value = Status(true, now, peerName)
        Log.i(TAG, "Library merged with '$peerName': ${if (changed) "updated here" else "already the same"}")
        return merged
    }
}
