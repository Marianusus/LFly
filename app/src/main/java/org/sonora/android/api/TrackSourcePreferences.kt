package org.sonora.android.api

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists the user's audio-source preferences:
 * - a single global preference ([PlaybackSource]) used as the default order of tiers;
 * - optional per-track overrides that take precedence over the global one.
 *
 * Backed by the shared "sonora_prefs" [android.content.SharedPreferences] file. Must be
 * initialized once from [org.sonora.android.SonoraApp.onCreate] before playback begins.
 */
object TrackSourcePreferences {

    private const val TAG = "TrackSourcePrefs"
    private const val PREF_FILE = "sonora_prefs"
    private const val KEY_GLOBAL_SOURCE = "preferred_playback_source"
    private const val KEY_OVERRIDE_PREFIX = "track_source_"

    @Volatile
    private var prefs: android.content.SharedPreferences? = null

    private val _globalSource = MutableStateFlow(PlaybackSource.AUTO)

    /** Global default source preference (never null; defaults to [PlaybackSource.AUTO]). */
    val globalSource: StateFlow<PlaybackSource> = _globalSource.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        prefs = p
        _globalSource.value = PlaybackSource.fromNameOrAuto(p.getString(KEY_GLOBAL_SOURCE, null))
        Log.i(TAG, "Initialized. Global source = ${_globalSource.value}")
    }

    private fun overrideKey(trackId: String) = "$KEY_OVERRIDE_PREFIX$trackId"

    /** Returns the per-track override, or null when the track has none (falls back to global). */
    fun overrideFor(trackId: String): PlaybackSource? {
        val p = prefs ?: return null
        val stored = p.getString(overrideKey(trackId), null) ?: return null
        return PlaybackSource.entries.firstOrNull { it.name == stored }
    }

    /** Effective source for a track: its override, otherwise the global preference. */
    fun effective(trackId: String): PlaybackSource = overrideFor(trackId) ?: _globalSource.value

    fun setGlobal(source: PlaybackSource) {
        _globalSource.value = source
        prefs?.edit()?.putString(KEY_GLOBAL_SOURCE, source.name)?.apply()
    }

    fun setOverride(trackId: String, source: PlaybackSource) {
        prefs?.edit()?.putString(overrideKey(trackId), source.name)?.apply()
    }

    fun clearOverride(trackId: String) {
        prefs?.edit()?.remove(overrideKey(trackId))?.apply()
    }
}
