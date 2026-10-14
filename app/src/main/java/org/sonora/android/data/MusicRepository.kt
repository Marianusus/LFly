package org.sonora.android.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.sonora.android.api.HighlightMode
import org.sonora.android.api.HttpClientProvider
import org.sonora.android.api.Track
import org.sonora.android.ui.theme.AppearanceMode
import org.sonora.android.ui.theme.AccentPalette
import org.sonora.android.ui.theme.ThemeStyle
import androidx.compose.ui.graphics.toArgb
import android.media.MediaMetadataRetriever
import java.io.File

/**
 * Repository managing persistent local data: favorites, play history, and user playlists.
 * Persists data as JSON files in [Context.getFilesDir] with thread-safe Mutex locks
 * and exposes reactive StateFlows for Compose UI observation.
 */
class MusicRepository(
    private val context: Context,
    private val storageDir: File? = null
) {

    companion object {
        private const val TAG = "MusicRepository"
        private const val FILE_FAVORITES = "favorites.json"
        private const val FILE_HISTORY = "history.json"
        private const val FILE_PLAYLISTS = "playlists.json"
        private const val FILE_DISLIKED = "disliked_tracks.json"
        private const val FILE_FOLLOWED_ARTISTS = "followed_artists.json"
        private const val FILE_PLAY_STATS = "play_stats.json"
        private const val PREF_SONORA = "sonora_prefs"
        private const val KEY_THEME_STYLE = "selected_theme_style"
        private const val KEY_APPEARANCE_MODE = "appearance_mode"
        private const val KEY_REDUCE_MOTION = "reduce_motion"
        private const val KEY_ACCENT_COLOR = "accent_color"
        private const val KEY_TRANSLATE_LYRICS = "translate_lyrics"
        private const val KEY_TRANSLATION_TARGET_LANG = "translation_target_lang"
        private const val KEY_LYRICS_HIGHLIGHT_MODE = "lyrics_highlight_mode"
        private const val KEY_LYRICS_PROVIDER_MUSIXMATCH = "lyrics_provider_musixmatch"
        private const val KEY_LYRICS_PROVIDER_LRCLIB = "lyrics_provider_lrclib"
        private const val MAX_HISTORY_SIZE = 100
        private const val COMPLETION_THRESHOLD = 0.85
        const val PLAYLIST_DOWNLOADED_ID = "playlist_downloaded_ytdlp"
        const val PLAYLIST_DOWNLOADED_NAME = "Brani Scaricati (yt-dlp)"

        @Volatile
        private var instance: MusicRepository? = null

        fun getInstance(context: Context): MusicRepository {
            return instance ?: synchronized(this) {
                instance ?: MusicRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    private val baseFilesDir: File?
        get() = storageDir ?: try { context.filesDir } catch (_: Exception) { null }

    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val gson: Gson = HttpClientProvider.gson
    private val prefs by lazy {
        context.getSharedPreferences(PREF_SONORA, Context.MODE_PRIVATE)
    }

    private val favoritesMutex = Mutex()
    private val historyMutex = Mutex()
    private val playlistsMutex = Mutex()
    private val dislikedMutex = Mutex()
    private val playStatsMutex = Mutex()
    private val followedArtistsMutex = Mutex()

    private val _favorites = MutableStateFlow<List<Track>>(emptyList())
    val favorites: StateFlow<List<Track>> = _favorites.asStateFlow()

    private val _history = MutableStateFlow<List<Track>>(emptyList())
    val history: StateFlow<List<Track>> = _history.asStateFlow()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _dislikedTrackIds = MutableStateFlow<Set<String>>(emptySet())
    val dislikedTrackIds: StateFlow<Set<String>> = _dislikedTrackIds.asStateFlow()

    private val _dislikedArtists = MutableStateFlow<Set<String>>(emptySet())
    val dislikedArtists: StateFlow<Set<String>> = _dislikedArtists.asStateFlow()

    private val _dislikedTracks = MutableStateFlow<List<Track>>(emptyList())
    val dislikedTracks: StateFlow<List<Track>> = _dislikedTracks.asStateFlow()

    private val _followedArtists = MutableStateFlow<List<FollowedArtist>>(emptyList())
    val followedArtists: StateFlow<List<FollowedArtist>> = _followedArtists.asStateFlow()

    private val _playStats = MutableStateFlow<List<PlayStat>>(emptyList())
    val playStats: StateFlow<List<PlayStat>> = _playStats.asStateFlow()

    private val _localTracks = MutableStateFlow<List<Track>>(emptyList())
    val localTracks: StateFlow<List<Track>> = _localTracks.asStateFlow()

    private val _themeStyle = MutableStateFlow(loadSavedThemeStyle())
    val themeStyle: StateFlow<ThemeStyle> = _themeStyle.asStateFlow()

    fun setThemeStyle(style: ThemeStyle) {
        _themeStyle.value = style
        prefs?.edit()?.putString(KEY_THEME_STYLE, style.name)?.apply()
        Log.i(TAG, "Theme style persisted: ${style.name}")
    }

    private fun loadSavedThemeStyle(): ThemeStyle {
        val name = prefs?.getString(KEY_THEME_STYLE, ThemeStyle.SONORA_AMOLED.name)
        return ThemeStyle.fromString(name)
    }

    private val _appearanceMode = MutableStateFlow(loadSavedAppearanceMode())
    val appearanceMode: StateFlow<AppearanceMode> = _appearanceMode.asStateFlow()

    fun setAppearanceMode(mode: AppearanceMode) {
        _appearanceMode.value = mode
        prefs?.edit()?.putString(KEY_APPEARANCE_MODE, mode.name)?.apply()
        Log.i(TAG, "Appearance mode persisted: ${mode.name}")
    }

    private fun loadSavedAppearanceMode(): AppearanceMode {
        val stored = prefs?.getString(KEY_APPEARANCE_MODE, null)
        if (stored != null) return AppearanceMode.fromString(stored)
        // One-time migration from the legacy ThemeStyle preference (kept intact).
        val migrated = AppearanceMode.migrateFromLegacy(prefs?.getString(KEY_THEME_STYLE, null))
        prefs?.edit()?.putString(KEY_APPEARANCE_MODE, migrated.name)?.apply()
        Log.i(TAG, "Appearance mode migrated from legacy theme -> ${migrated.name}")
        return migrated
    }

    private val _reduceMotion = MutableStateFlow(loadSavedReduceMotion())
    val reduceMotion: StateFlow<Boolean> = _reduceMotion.asStateFlow()

    fun setReduceMotion(enabled: Boolean) {
        _reduceMotion.value = enabled
        prefs?.edit()?.putBoolean(KEY_REDUCE_MOTION, enabled)?.apply()
    }

    private fun loadSavedReduceMotion(): Boolean =
        prefs?.getBoolean(KEY_REDUCE_MOTION, false) ?: false

    private val _accentColor = MutableStateFlow(loadSavedAccentColor())
    val accentColor: StateFlow<Int> = _accentColor.asStateFlow()

    fun setAccentColor(argb: Int) {
        _accentColor.value = argb
        prefs?.edit()?.putInt(KEY_ACCENT_COLOR, argb)?.apply()
        Log.i(TAG, "Accent color persisted: ${Integer.toHexString(argb)}")
    }

    private fun loadSavedAccentColor(): Int =
        prefs?.getInt(KEY_ACCENT_COLOR, AccentPalette.defaultArgb()) ?: AccentPalette.defaultArgb()

    private val _translateLyrics = MutableStateFlow(loadSavedTranslateLyrics())
    val translateLyrics: StateFlow<Boolean> = _translateLyrics.asStateFlow()

    fun setTranslateLyrics(enabled: Boolean) {
        _translateLyrics.value = enabled
        prefs?.edit()?.putBoolean(KEY_TRANSLATE_LYRICS, enabled)?.apply()
    }

    private fun loadSavedTranslateLyrics(): Boolean =
        prefs?.getBoolean(KEY_TRANSLATE_LYRICS, false) ?: false

    private val _translationTargetLang = MutableStateFlow(loadSavedTranslationTargetLang())
    val translationTargetLang: StateFlow<String> = _translationTargetLang.asStateFlow()

    fun setTranslationTargetLang(lang: String) {
        _translationTargetLang.value = lang
        prefs?.edit()?.putString(KEY_TRANSLATION_TARGET_LANG, lang)?.apply()
    }

    private fun loadSavedTranslationTargetLang(): String =
        prefs?.getString(KEY_TRANSLATION_TARGET_LANG, "it") ?: "it"

    private val _lyricsHighlightMode = MutableStateFlow(
        HighlightMode.fromString(prefs?.getString(KEY_LYRICS_HIGHLIGHT_MODE, null))
    )
    val lyricsHighlightMode: StateFlow<HighlightMode> = _lyricsHighlightMode.asStateFlow()

    fun setLyricsHighlightMode(mode: HighlightMode) {
        _lyricsHighlightMode.value = mode
        prefs?.edit()?.putString(KEY_LYRICS_HIGHLIGHT_MODE, mode.name.lowercase())?.apply()
    }

    // Lyrics provider toggles (ported engine: Musixmatch = word-level, LRCLIB = fallback).

    private val _lyricsProviderMusixmatch =
        MutableStateFlow(prefs?.getBoolean(KEY_LYRICS_PROVIDER_MUSIXMATCH, true) ?: true)
    val lyricsProviderMusixmatch: StateFlow<Boolean> = _lyricsProviderMusixmatch.asStateFlow()

    fun setLyricsProviderMusixmatch(enabled: Boolean) {
        _lyricsProviderMusixmatch.value = enabled
        prefs?.edit()?.putBoolean(KEY_LYRICS_PROVIDER_MUSIXMATCH, enabled)?.apply()
    }

    private val _lyricsProviderLrclib =
        MutableStateFlow(prefs?.getBoolean(KEY_LYRICS_PROVIDER_LRCLIB, true) ?: true)
    val lyricsProviderLrclib: StateFlow<Boolean> = _lyricsProviderLrclib.asStateFlow()

    fun setLyricsProviderLrclib(enabled: Boolean) {
        _lyricsProviderLrclib.value = enabled
        prefs?.edit()?.putBoolean(KEY_LYRICS_PROVIDER_LRCLIB, enabled)?.apply()
    }

    init {
        loadAllData()
    }

    private fun loadAllData() {
        repositoryScope.launch {
            loadFavorites()
            loadHistory()
            loadPlaylists()
            loadDislikedTracks()
            loadPlayStats()
            loadFollowedArtists()
            syncLocalMusic()
        }
    }

    // ==========================================
    // Favorites Management
    // ==========================================

    fun isFavorite(trackId: String): Boolean {
        return _favorites.value.any { it.id == trackId }
    }

    fun toggleFavorite(track: Track): Boolean {
        synchronized(this) {
            val current = _favorites.value.toMutableList()
            val existingIndex = current.indexOfFirst { it.id == track.id }
            val isNowFavorite = if (existingIndex != -1) {
                current.removeAt(existingIndex)
                false
            } else {
                current.add(0, track)
                true
            }
            // Update in-memory state immediately and synchronously
            _favorites.value = current

            val snapshot = current.toList()
            repositoryScope.launch {
                favoritesMutex.withLock {
                    saveToFile(FILE_FAVORITES, snapshot)
                    Log.d(TAG, "Persisted favorites to disk (${snapshot.size} tracks)")
                }
            }
            return isNowFavorite
        }
    }

    fun addFavorite(track: Track) {
        synchronized(this) {
            val current = _favorites.value.toMutableList()
            if (current.none { it.id == track.id }) {
                current.add(0, track)
                _favorites.value = current
                val snapshot = current.toList()
                repositoryScope.launch {
                    favoritesMutex.withLock {
                        saveToFile(FILE_FAVORITES, snapshot)
                        Log.d(TAG, "Added track to favorites: ${track.title}")
                    }
                }
            }
        }
    }

    fun removeFavorite(trackId: String) {
        synchronized(this) {
            val current = _favorites.value.toMutableList()
            val removed = current.removeAll { it.id == trackId }
            if (removed) {
                _favorites.value = current
                val snapshot = current.toList()
                repositoryScope.launch {
                    favoritesMutex.withLock {
                        saveToFile(FILE_FAVORITES, snapshot)
                        Log.d(TAG, "Removed track from favorites: $trackId")
                    }
                }
            }
        }
    }

    private suspend fun loadFavorites() = withContext(Dispatchers.IO) {
        val dir = baseFilesDir ?: return@withContext
        favoritesMutex.withLock {
            try {
                val file = File(dir, FILE_FAVORITES)
                if (file.exists()) {
                    val json = file.readText()
                    val type = object : TypeToken<List<Track>>() {}.type
                    val list: List<Track>? = gson.fromJson(json, type)
                    if (list != null) {
                        synchronized(this@MusicRepository) {
                            val inMemoryIds = _favorites.value.map { it.id }.toSet()
                            val combined = _favorites.value + list.filter { it.id !in inMemoryIds }
                            _favorites.value = combined
                        }
                    }
                    Log.i(TAG, "Loaded ${_favorites.value.size} favorite tracks")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading favorites", e)
            }
        }
    }

    // ==========================================
    // Recent Play History Management
    // ==========================================

    fun addToHistory(track: Track) {
        // Stream URLs from YouTube/imports expire: persist only stable ids, re-resolve on next playback.
        val persisted = if (track.id.startsWith("yt_") || track.id.startsWith("spotify_") || track.provider.equals("Spotify", ignoreCase = true)) {
            track.copy(streamUrl = null)
        } else {
            track
        }
        synchronized(this) {
            val current = _history.value.toMutableList()
            // Remove if already exists so we move it to the front
            current.removeAll { it.id == persisted.id }
            current.add(0, persisted)

            // Limit history capacity
            if (current.size > MAX_HISTORY_SIZE) {
                current.subList(MAX_HISTORY_SIZE, current.size).clear()
            }

            _history.value = current
        }
        val snapshot = _history.value
        repositoryScope.launch {
            historyMutex.withLock {
                saveToFile(FILE_HISTORY, snapshot)
                Log.d(TAG, "Added track to play history: ${track.title}")
            }
        }
    }

    fun clearHistory() {
        synchronized(this) {
            _history.value = emptyList()
        }
        repositoryScope.launch {
            historyMutex.withLock {
                saveToFile(FILE_HISTORY, emptyList<Track>())
                Log.i(TAG, "Cleared play history")
            }
        }
    }

    private suspend fun loadHistory() = withContext(Dispatchers.IO) {
        val dir = baseFilesDir ?: return@withContext
        historyMutex.withLock {
            try {
                val file = File(dir, FILE_HISTORY)
                if (file.exists()) {
                    val json = file.readText()
                    val type = object : TypeToken<List<Track>>() {}.type
                    val list: List<Track>? = gson.fromJson(json, type)
                    _history.value = list ?: emptyList()
                    Log.i(TAG, "Loaded ${_history.value.size} history tracks")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading history", e)
            }
        }
    }

    // ==========================================
    // Playlists Management
    // ==========================================

    fun getPlaylist(playlistId: String): Playlist? {
        return _playlists.value.find { it.id == playlistId }
    }

    fun createPlaylist(name: String, description: String? = null, coverUrl: String? = null): Playlist {
        val newPlaylist = Playlist(
            name = name.trim(),
            description = description?.trim()?.takeIf { it.isNotBlank() },
            coverUrl = coverUrl?.trim()?.takeIf { it.isNotBlank() }
        )
        synchronized(this) {
            val current = _playlists.value.toMutableList()
            current.add(0, newPlaylist)
            _playlists.value = current
        }
        val snapshot = _playlists.value
        repositoryScope.launch {
            playlistsMutex.withLock {
                saveToFile(FILE_PLAYLISTS, snapshot)
                Log.i(TAG, "Created playlist: ${newPlaylist.name} (${newPlaylist.id})")
            }
        }
        return newPlaylist
    }

    fun createPlaylistWithTracks(
        name: String,
        tracks: List<Track>,
        description: String? = null,
        coverUrl: String? = null
    ): Playlist {
        val newPlaylist = Playlist(
            name = name.trim(),
            description = description?.trim()?.takeIf { it.isNotBlank() },
            coverUrl = coverUrl?.trim()?.takeIf { it.isNotBlank() },
            tracks = tracks
        )
        synchronized(this) {
            val current = _playlists.value.toMutableList()
            current.add(0, newPlaylist)
            _playlists.value = current
        }
        val snapshot = _playlists.value
        repositoryScope.launch {
            playlistsMutex.withLock {
                saveToFile(FILE_PLAYLISTS, snapshot)
                Log.i(TAG, "Created playlist with ${tracks.size} tracks: ${newPlaylist.name} (${newPlaylist.id})")
            }
        }
        return newPlaylist
    }

    fun savePlaylist(playlist: Playlist) {
        synchronized(this) {
            val current = _playlists.value.toMutableList()
            val index = current.indexOfFirst { it.id == playlist.id }
            if (index != -1) {
                current[index] = playlist
            } else {
                current.add(0, playlist)
            }
            _playlists.value = current
        }
        val snapshot = _playlists.value
        repositoryScope.launch {
            playlistsMutex.withLock {
                saveToFile(FILE_PLAYLISTS, snapshot)
                Log.i(TAG, "Saved playlist: ${playlist.name} (${playlist.id}) with ${playlist.tracks.size} tracks")
            }
        }
    }

    fun addTrackToPlaylist(playlistId: String, track: Track) {
        var playlistModified = false
        synchronized(this) {
            val current = _playlists.value.toMutableList()
            val index = current.indexOfFirst { it.id == playlistId }
            if (index != -1) {
                val target = current[index]
                val updatedTracks = target.tracks.toMutableList()
                if (updatedTracks.none { it.id == track.id }) {
                    updatedTracks.add(track)
                    val updatedPlaylist = target.copy(
                        tracks = updatedTracks,
                        updatedAt = System.currentTimeMillis()
                    )
                    current[index] = updatedPlaylist
                    _playlists.value = current
                    playlistModified = true
                    Log.d(TAG, "Added track ${track.title} to playlist ${target.name}")
                }
            }
        }
        if (playlistModified) {
            val snapshot = _playlists.value
            repositoryScope.launch {
                playlistsMutex.withLock {
                    saveToFile(FILE_PLAYLISTS, snapshot)
                }
            }
        }
    }

    fun removeTrackFromPlaylist(playlistId: String, trackId: String) {
        var playlistModified = false
        synchronized(this) {
            val current = _playlists.value.toMutableList()
            val index = current.indexOfFirst { it.id == playlistId }
            if (index != -1) {
                val target = current[index]
                val updatedTracks = target.tracks.toMutableList()
                val removed = updatedTracks.removeAll { it.id == trackId }
                if (removed) {
                    val updatedPlaylist = target.copy(
                        tracks = updatedTracks,
                        updatedAt = System.currentTimeMillis()
                    )
                    current[index] = updatedPlaylist
                    _playlists.value = current
                    playlistModified = true
                    Log.d(TAG, "Removed track $trackId from playlist ${target.name}")
                }
            }
        }
        if (playlistModified) {
            val snapshot = _playlists.value
            repositoryScope.launch {
                playlistsMutex.withLock {
                    saveToFile(FILE_PLAYLISTS, snapshot)
                }
            }
        }
    }

    fun reorderPlaylist(playlistId: String, from: Int, to: Int) {
        var playlistModified = false
        synchronized(this) {
            val current = _playlists.value.toMutableList()
            val index = current.indexOfFirst { it.id == playlistId }
            if (index != -1) {
                val target = current[index]
                val updatedTracks = target.tracks.toMutableList()
                if (from in updatedTracks.indices && to in updatedTracks.indices && from != to) {
                    val item = updatedTracks.removeAt(from)
                    updatedTracks.add(to, item)
                    val updatedPlaylist = target.copy(
                        tracks = updatedTracks,
                        updatedAt = System.currentTimeMillis()
                    )
                    current[index] = updatedPlaylist
                    _playlists.value = current
                    playlistModified = true
                    Log.d(TAG, "Reordered playlist $playlistId from $from to $to")
                }
            }
        }
        if (playlistModified) {
            val snapshot = _playlists.value
            repositoryScope.launch {
                playlistsMutex.withLock {
                    saveToFile(FILE_PLAYLISTS, snapshot)
                }
            }
        }
    }

    /**
     * Reorders the favorites list (Liked Songs behaves like a big playlist). Persisted to
     * `favorites.json` using the same pattern as playlists. No-op when indices are invalid.
     */
    fun reorderFavorites(from: Int, to: Int) {
        var modified = false
        synchronized(this) {
            val current = _favorites.value.toMutableList()
            if (from in current.indices && to in current.indices && from != to) {
                val item = current.removeAt(from)
                current.add(to, item)
                _favorites.value = current
                modified = true
                Log.d(TAG, "Reordered favorites from $from to $to")
            }
        }
        if (modified) {
            val snapshot = _favorites.value
            repositoryScope.launch {
                favoritesMutex.withLock {
                    saveToFile(FILE_FAVORITES, snapshot)
                }
            }
        }
    }

    fun renamePlaylist(playlistId: String, newName: String) {
        var playlistModified = false
        synchronized(this) {
            val current = _playlists.value.toMutableList()
            val index = current.indexOfFirst { it.id == playlistId }
            if (index != -1) {
                current[index] = current[index].copy(
                    name = newName.trim(),
                    updatedAt = System.currentTimeMillis()
                )
                _playlists.value = current
                playlistModified = true
            }
        }
        if (playlistModified) {
            val snapshot = _playlists.value
            repositoryScope.launch {
                playlistsMutex.withLock {
                    saveToFile(FILE_PLAYLISTS, snapshot)
                }
            }
        }
    }

    fun updatePlaylist(playlistId: String, name: String, description: String? = null, coverUrl: String? = null) {
        var playlistModified = false
        synchronized(this) {
            val current = _playlists.value.toMutableList()
            val index = current.indexOfFirst { it.id == playlistId }
            if (index != -1) {
                current[index] = current[index].copy(
                    name = name.trim(),
                    description = description?.trim()?.takeIf { it.isNotBlank() },
                    coverUrl = coverUrl?.trim()?.takeIf { it.isNotBlank() },
                    updatedAt = System.currentTimeMillis()
                )
                _playlists.value = current
                playlistModified = true
            }
        }
        if (playlistModified) {
            val snapshot = _playlists.value
            repositoryScope.launch {
                playlistsMutex.withLock {
                    saveToFile(FILE_PLAYLISTS, snapshot)
                }
            }
        }
    }

    /**
     * Updates ONLY the coverUrl of an imported (spotify_) track, in the single playlist that
     * contains it. Idempotent; never touches id/provider/streamUrl/history/other playlists.
     * If more than one playlist contains the trackId, nothing is persisted (ambiguous).
     */
    fun updateImportedTrackCover(trackId: String, coverUrl: String?) {
        if (trackId.isBlank() || !trackId.startsWith("spotify_")) return
        val url = coverUrl?.trim()
        if (url.isNullOrBlank() || !(url.startsWith("http://") || url.startsWith("https://"))) return

        val matching = _playlists.value.filter { pl ->
            pl.tracks.any { it.id == trackId && it.provider.equals("Spotify", ignoreCase = true) }
        }
        if (matching.isEmpty()) return
        if (matching.size > 1) {
            Log.w(TAG, "Ambiguous cover update for $trackId in ${matching.size} playlists; skipping")
            return
        }

        val target = matching.first()
        var changed = false
        val updatedTracks = target.tracks.map { t ->
            if (t.id == trackId && t.provider.equals("Spotify", ignoreCase = true)) {
                if (t.coverUrl == url) {
                    t
                } else {
                    changed = true
                    t.copy(coverUrl = url)
                }
            } else {
                t
            }
        }
        if (!changed) return

        val updatedPlaylist = target.copy(tracks = updatedTracks, updatedAt = System.currentTimeMillis())
        synchronized(this) {
            val current = _playlists.value.toMutableList()
            val index = current.indexOfFirst { it.id == target.id }
            if (index != -1) {
                current[index] = updatedPlaylist
                _playlists.value = current
            }
        }
        val snapshot = _playlists.value
        repositoryScope.launch {
            playlistsMutex.withLock {
                saveToFile(FILE_PLAYLISTS, snapshot)
                Log.i(TAG, "Updated imported track cover for $trackId")
            }
        }
    }

    fun deletePlaylist(playlistId: String) {
        var playlistModified = false
        synchronized(this) {
            val current = _playlists.value.toMutableList()
            val removed = current.removeAll { it.id == playlistId }
            if (removed) {
                _playlists.value = current
                playlistModified = true
                Log.i(TAG, "Deleted playlist: $playlistId")
            }
        }
        if (playlistModified) {
            val snapshot = _playlists.value
            repositoryScope.launch {
                playlistsMutex.withLock {
                    saveToFile(FILE_PLAYLISTS, snapshot)
                    Log.i(TAG, "Deleted playlist: $playlistId")
                }
            }
        }
    }

    private suspend fun loadPlaylists() = withContext(Dispatchers.IO) {
        val dir = baseFilesDir ?: return@withContext
        playlistsMutex.withLock {
            try {
                val file = File(dir, FILE_PLAYLISTS)
                if (file.exists()) {
                    val json = file.readText()
                    val type = object : TypeToken<List<Playlist>>() {}.type
                    val rawList: List<Playlist>? = gson.fromJson(json, type)
                    if (rawList != null) {
                        // Idempotent migration: strip runtime stream URLs and stabilize ids.
                        val migrated = rawList.map { migratePlaylist(it) }
                        val needsSave = rawList != migrated
                        synchronized(this@MusicRepository) {
                            val inMemoryIds = _playlists.value.map { it.id }.toSet()
                            val combined = _playlists.value + migrated.filter { it.id !in inMemoryIds }
                            _playlists.value = combined
                        }
                        if (needsSave) {
                            saveToFile(FILE_PLAYLISTS, _playlists.value)
                            Log.i(TAG, "Migrated imported playlists to schema v$PLAYLIST_SCHEMA_VERSION")
                        }
                    }
                    Log.i(TAG, "Loaded ${_playlists.value.size} custom playlists")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading playlists", e)
            }
        }
    }

    // ==========================================
    // Disliked Tracks Management
    // ==========================================

    fun isDisliked(trackId: String): Boolean {
        return _dislikedTrackIds.value.contains(trackId)
    }

    fun toggleDislike(track: Track): Boolean {
        synchronized(this) {
            val currentTracks = _dislikedTracks.value.toMutableList()
            val currentIds = _dislikedTrackIds.value.toMutableSet()
            val currentArtists = _dislikedArtists.value.toMutableSet()
            val normArtist = normalizeArtist(track.artist)

            val isNowDisliked: Boolean
            if (currentIds.contains(track.id)) {
                // Remove dislike
                currentTracks.removeAll { it.id == track.id }
                currentIds.remove(track.id)
                // If no other tracks by this artist are disliked, remove artist
                if (currentTracks.none { normalizeArtist(it.artist) == normArtist }) {
                    currentArtists.remove(normArtist)
                }
                isNowDisliked = false
            } else {
                // Add dislike
                currentTracks.add(0, track)
                currentIds.add(track.id)
                if (normArtist.isNotBlank()) {
                    currentArtists.add(normArtist)
                }
                // Automatically remove from favorites if present
                if (isFavorite(track.id)) {
                    removeFavorite(track.id)
                }
                isNowDisliked = true
            }

            _dislikedTracks.value = currentTracks
            _dislikedTrackIds.value = currentIds
            _dislikedArtists.value = currentArtists

            val snapshot = currentTracks.toList()
            repositoryScope.launch {
                dislikedMutex.withLock {
                    saveToFile(FILE_DISLIKED, snapshot)
                    Log.d(TAG, "Persisted disliked tracks to disk (${snapshot.size} tracks)")
                }
            }
            return isNowDisliked
        }
    }

    fun addDislike(track: Track) {
        if (!isDisliked(track.id)) {
            toggleDislike(track)
        }
    }

    fun removeDislike(trackId: String) {
        val existing = _dislikedTracks.value.firstOrNull { it.id == trackId }
        if (existing != null) {
            toggleDislike(existing)
        } else if (_dislikedTrackIds.value.contains(trackId)) {
            val currentIds = _dislikedTrackIds.value.toMutableSet()
            currentIds.remove(trackId)
            _dislikedTrackIds.value = currentIds
        }
    }

    private suspend fun loadDislikedTracks() = withContext(Dispatchers.IO) {
        val dir = baseFilesDir ?: return@withContext
        dislikedMutex.withLock {
            try {
                val file = File(dir, FILE_DISLIKED)
                if (file.exists()) {
                    val json = file.readText()
                    val type = object : TypeToken<List<Track>>() {}.type
                    val list: List<Track>? = gson.fromJson(json, type)
                    if (list != null) {
                        synchronized(this@MusicRepository) {
                            _dislikedTracks.value = list
                            _dislikedTrackIds.value = list.map { it.id }.toSet()
                            _dislikedArtists.value = list.map { normalizeArtist(it.artist) }.filter { it.isNotBlank() }.toSet()
                        }
                    }
                    Log.i(TAG, "Loaded ${_dislikedTracks.value.size} disliked tracks")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading disliked tracks", e)
            }
        }
    }

    // ==========================================
    // Play Statistics (taste-based recommendations)
    // ==========================================

    /** Stable, persistable copy: remote stream URLs expire and are re-resolved on playback. */
    private fun stabilizeForPersistence(track: Track): Track {
        return if (track.id.startsWith("yt_") ||
            track.id.startsWith("spotify_") ||
            track.provider.equals("Spotify", ignoreCase = true)
        ) {
            track.copy(streamUrl = null)
        } else {
            track
        }
    }

    fun playCountOf(trackId: String): Int =
        _playStats.value.firstOrNull { it.track.id == trackId }?.playCount ?: 0

    fun completionCountOf(trackId: String): Int =
        _playStats.value.firstOrNull { it.track.id == trackId }?.completionCount ?: 0

    /** Increments the play count for [track]; called when playback of the track actually starts. */
    fun incrementPlayCount(track: Track) {
        val stable = stabilizeForPersistence(track)
        synchronized(this) {
            val current = _playStats.value.toMutableList()
            val index = current.indexOfFirst { it.track.id == stable.id }
            val now = System.currentTimeMillis()
            if (index >= 0) {
                val existing = current[index]
                current[index] = existing.copy(
                    track = stable,
                    playCount = existing.playCount + 1,
                    lastPlayedAtEpochMs = now
                )
            } else {
                current.add(0, PlayStat(track = stable, playCount = 1, completionCount = 0, lastPlayedAtEpochMs = now))
            }
            _playStats.value = current
        }
        persistPlayStats()
    }

    /**
     * Records a completion when [positionMs] reaches ~85% of the track duration.
     * When the duration is unknown (<= 0), a natural end (position reported) counts as completed.
     */
    fun recordCompletion(track: Track, positionMs: Long) {
        val duration = track.durationMs
        val completed = duration <= 0L || positionMs >= (duration * COMPLETION_THRESHOLD).toLong()
        if (!completed) return

        val stable = stabilizeForPersistence(track)
        synchronized(this) {
            val current = _playStats.value.toMutableList()
            val index = current.indexOfFirst { it.track.id == stable.id }
            val now = System.currentTimeMillis()
            if (index >= 0) {
                val existing = current[index]
                current[index] = existing.copy(
                    track = stable,
                    completionCount = existing.completionCount + 1,
                    lastPlayedAtEpochMs = now
                )
            } else {
                current.add(0, PlayStat(track = stable, playCount = 0, completionCount = 1, lastPlayedAtEpochMs = now))
            }
            _playStats.value = current
        }
        persistPlayStats()
    }

    /** Top played tracks ordered by play count (desc), then most recently played. */
    fun topPlayedTracks(limit: Int): List<Track> =
        _playStats.value
            .filter { it.playCount > 0 }
            .sortedWith(
                compareByDescending<PlayStat> { it.playCount }
                    .thenByDescending { it.lastPlayedAtEpochMs }
            )
            .take(limit)
            .map { it.track }

    /** Top played artists aggregated by play count, ordered desc then alphabetically. */
    fun topPlayedArtists(limit: Int): List<Pair<String, Int>> {
        val aggregated = LinkedHashMap<String, Pair<String, Int>>()
        for (stat in _playStats.value) {
            if (stat.playCount <= 0) continue
            val key = normalizeArtist(stat.track.artist)
            if (key.isBlank()) continue
            val existing = aggregated[key]
            aggregated[key] = if (existing == null) {
                stat.track.artist.trim() to stat.playCount
            } else {
                existing.first to (existing.second + stat.playCount)
            }
        }
        return aggregated.values
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.lowercase() })
            .take(limit)
    }

    private fun persistPlayStats() {
        val snapshot = _playStats.value
        repositoryScope.launch {
            playStatsMutex.withLock {
                saveToFile(FILE_PLAY_STATS, snapshot)
            }
        }
    }

    private suspend fun loadPlayStats() = withContext(Dispatchers.IO) {
        val dir = baseFilesDir ?: return@withContext
        playStatsMutex.withLock {
            try {
                val file = File(dir, FILE_PLAY_STATS)
                if (file.exists()) {
                    val json = file.readText()
                    val type = object : TypeToken<List<PlayStat>>() {}.type
                    val list: List<PlayStat>? = gson.fromJson(json, type)
                    if (list != null) {
                        synchronized(this@MusicRepository) { _playStats.value = list }
                    }
                    Log.i(TAG, "Loaded ${_playStats.value.size} play stats")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading play stats", e)
            }
        }
    }

    private fun normalizeArtist(artist: String): String {
        return artist.lowercase()
            .split(",", "&", "feat.", "ft.", "featuring")[0]
            .replace(Regex("[^a-z0-9]"), "")
            .trim()
    }

    // ==========================================
    // Additional Reordering Helpers
    // ==========================================

    fun reorderTracksInPlaylist(playlistId: String, from: Int, to: Int) {
        reorderPlaylist(playlistId, from, to)
    }

    fun reorderPlaylists(from: Int, to: Int): Boolean {
        var playlistModified = false
        synchronized(this) {
            val current = _playlists.value.toMutableList()
            if (from in current.indices && to in current.indices && from != to) {
                val item = current.removeAt(from)
                current.add(to, item)
                _playlists.value = current
                playlistModified = true
                Log.d(TAG, "Reordered playlists from $from to $to")
            }
        }
        if (playlistModified) {
            val snapshot = _playlists.value
            repositoryScope.launch {
                playlistsMutex.withLock {
                    saveToFile(FILE_PLAYLISTS, snapshot)
                }
            }
        }
        return playlistModified
    }

    // ==========================================
    // Local Music & yt-dlp Storage Management
    // ==========================================

    fun syncLocalMusic() {
        repositoryScope.launch(Dispatchers.IO) {
            val audioExtensions = setOf("mp3", "m4a", "opus", "flac", "wav", "aac", "ogg")
            val targetDirs = mutableListOf<File>()

            // 1. App-specific external storage (Android/data/org.sonora.android/files/music)
            val externalMusicDir = context.getExternalFilesDir("music")
            if (externalMusicDir != null) {
                if (!externalMusicDir.exists()) externalMusicDir.mkdirs()
                targetDirs.add(externalMusicDir)
            }

            // 2. App internal files/music fallback
            val internalMusicDir = File(context.filesDir, "music")
            if (!internalMusicDir.exists()) internalMusicDir.mkdirs()
            targetDirs.add(internalMusicDir)

            val foundTracks = mutableListOf<Track>()

            for (dir in targetDirs) {
                val files = dir.listFiles() ?: continue
                for (file in files) {
                    if (file.isFile && file.extension.lowercase() in audioExtensions) {
                        val track = parseLocalTrackFile(file)
                        if (track != null) {
                            foundTracks.add(track)
                        }
                    }
                }
            }

            Log.i(TAG, "Scanned local music directory: found ${foundTracks.size} tracks")
            _localTracks.value = foundTracks

            // Synchronize with persistent playlists
            if (foundTracks.isNotEmpty()) {
                synchronized(this@MusicRepository) {
                    val currentPlaylists = _playlists.value.toMutableList()
                    val downloadedIndex = currentPlaylists.indexOfFirst { it.id == PLAYLIST_DOWNLOADED_ID }
                    val downloadedPlaylist = Playlist(
                        id = PLAYLIST_DOWNLOADED_ID,
                        name = PLAYLIST_DOWNLOADED_NAME,
                        description = "Brani scaricati localmente via yt-dlp",
                        tracks = foundTracks,
                        updatedAt = System.currentTimeMillis()
                    )
                    if (downloadedIndex != -1) {
                        currentPlaylists[downloadedIndex] = downloadedPlaylist
                    } else {
                        currentPlaylists.add(0, downloadedPlaylist)
                    }
                    _playlists.value = currentPlaylists
                }
            }
        }
    }

    private fun parseLocalTrackFile(file: File): Track? {
        val retriever = MediaMetadataRetriever()
        var metaTitle: String? = null
        var metaArtist: String? = null
        var metaAlbum: String? = null
        var durationMs = 0L

        try {
            retriever.setDataSource(file.absolutePath)
            metaTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            metaArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            metaAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durationMs = durStr?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "Metadata extraction failed for ${file.name}: ${e.message}")
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        val cleanBaseName = file.nameWithoutExtension
            .replace(Regex("\\.(wav|mp3|m4a|opus|flac)$", RegexOption.IGNORE_CASE), "")
            .trim()

        val parts = cleanBaseName.split(" - ")
        val parsedArtist: String
        val parsedTitle: String
        when {
            parts.size >= 3 -> {
                parsedArtist = parts[1].trim()
                parsedTitle = parts.subList(2, parts.size).joinToString(" - ").trim()
            }
            parts.size == 2 -> {
                parsedArtist = parts[0].trim()
                parsedTitle = parts[1].trim()
            }
            else -> {
                parsedArtist = "Scaricato (yt-dlp)"
                parsedTitle = cleanBaseName
            }
        }

        val finalTitle = metaTitle?.takeIf { it.isNotBlank() } ?: parsedTitle
        val finalArtist = metaArtist?.takeIf { it.isNotBlank() } ?: parsedArtist
        val finalAlbum = metaAlbum?.takeIf { it.isNotBlank() } ?: "Download yt-dlp"

        return Track(
            id = "local_${file.name.hashCode()}",
            title = finalTitle,
            artist = finalArtist,
            album = finalAlbum,
            durationMs = durationMs,
            coverUrl = null,
            streamUrl = file.absolutePath,
            provider = "Local"
        )
    }

    fun findLocalTrack(queryTitle: String, queryArtist: String): Track? {
        val cleanQTitle = cleanForMatch(queryTitle)
        val cleanQArtist = cleanForMatch(queryArtist)
        if (cleanQTitle.isBlank()) return null

        return _localTracks.value.firstOrNull { local ->
            val localCleanTitle = cleanForMatch(local.title)
            val localCleanArtist = cleanForMatch(local.artist)
            val localCleanFile = cleanForMatch(File(local.streamUrl ?: "").nameWithoutExtension)

            // 1. Title match
            val titleMatches = localCleanTitle == cleanQTitle ||
                    (cleanQTitle.length >= 4 && localCleanTitle.contains(cleanQTitle)) ||
                    (localCleanTitle.length >= 4 && cleanQTitle.contains(localCleanTitle)) ||
                    (cleanQTitle.length >= 4 && localCleanFile.contains(cleanQTitle))

            if (!titleMatches) return@firstOrNull false

            // 2. Artist match (if artist query specified)
            if (cleanQArtist.isBlank() || localCleanArtist.isBlank()) return@firstOrNull true

            localCleanArtist == cleanQArtist ||
                    localCleanArtist.contains(cleanQArtist) ||
                    cleanQArtist.contains(localCleanArtist) ||
                    localCleanFile.contains(cleanQArtist)
        }
    }

    // ==========================================
    // Followed Artists (local follow only, YouTube)
    // ==========================================

    /** Alias-aware match: same key, same stable id, or same name when either side lacks an id. */
    internal fun sameFollowedArtist(a: FollowedArtist, b: FollowedArtist): Boolean {
        if (a.artistKey == b.artistKey) return true
        if (!b.browseId.isNullOrBlank() && a.browseId == b.browseId) return true
        if (!b.channelId.isNullOrBlank() && a.channelId == b.channelId) return true
        val aStable = !a.browseId.isNullOrBlank() || !a.channelId.isNullOrBlank()
        val bStable = !b.browseId.isNullOrBlank() || !b.channelId.isNullOrBlank()
        if (!aStable || !bStable) {
            val an = normalizeArtist(a.name)
            val bn = normalizeArtist(b.name)
            return an.isNotBlank() && an == bn
        }
        return false
    }

    fun isFollowing(artist: FollowedArtist): Boolean =
        _followedArtists.value.any { sameFollowedArtist(it, artist) }

    fun isFollowingKey(artistKey: String): Boolean =
        _followedArtists.value.any { it.artistKey == artistKey }

    /**
     * Optimistic follow/unfollow. Updates the StateFlow immediately and rolls back
     * if the JSON persistence fails. Returns true when the artist ends up followed.
     */
    fun toggleFollowArtist(artist: FollowedArtist): Boolean {
        synchronized(this) {
            val previous = _followedArtists.value
            val existing = previous.firstOrNull { sameFollowedArtist(it, artist) }
            val updated = ArrayList<FollowedArtist>(previous.size + 1)
            val nowFollowing: Boolean
            when {
                existing == null -> {
                    updated.add(artist)
                    updated.addAll(previous)
                    nowFollowing = true
                }
                existing.artistKey == artist.artistKey -> {
                    // Same identity tapped again -> unfollow (removes every alias).
                    updated.addAll(previous.filterNot { sameFollowedArtist(it, artist) })
                    nowFollowing = false
                }
                else -> {
                    // Same artist arrived earlier by name and now with a stable id:
                    // upgrade in place, keeping it followed and avoiding a duplicate.
                    updated.add(artist)
                    updated.addAll(previous.filterNot { sameFollowedArtist(it, artist) })
                    nowFollowing = true
                }
            }
            _followedArtists.value = updated
            repositoryScope.launch {
                followedArtistsMutex.withLock {
                    val ok = persistFollowedArtists(updated)
                    if (!ok) {
                        synchronized(this@MusicRepository) { _followedArtists.value = previous }
                        Log.w(TAG, "Follow persistence failed; rolled back (key=${artist.artistKey})")
                    }
                }
            }
            return nowFollowing
        }
    }

    private suspend fun loadFollowedArtists() = withContext(Dispatchers.IO) {
        val dir = baseFilesDir ?: return@withContext
        followedArtistsMutex.withLock {
            try {
                val file = File(dir, FILE_FOLLOWED_ARTISTS)
                if (file.exists()) {
                    val json = file.readText()
                    val type = object : TypeToken<List<FollowedArtist>>() {}.type
                    val list: List<FollowedArtist>? = gson.fromJson(json, type)
                    if (list != null) {
                        synchronized(this@MusicRepository) { _followedArtists.value = list }
                    }
                    Log.i(TAG, "Loaded ${_followedArtists.value.size} followed artists")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading followed artists", e)
            }
        }
    }

    private fun persistFollowedArtists(list: List<FollowedArtist>): Boolean = try {
        val dir = baseFilesDir ?: return false
        if (!dir.exists()) dir.mkdirs()
        val json = gson.toJson(list)
        val tempFile = File(dir, "$FILE_FOLLOWED_ARTISTS.tmp")
        val targetFile = File(dir, FILE_FOLLOWED_ARTISTS)
        tempFile.writeText(json)
        if (!tempFile.renameTo(targetFile)) {
            targetFile.writeText(json)
            tempFile.delete()
        }
        true
    } catch (e: Exception) {
        Log.e(TAG, "Failed writing $FILE_FOLLOWED_ARTISTS", e)
        false
    }

    // ==========================================
    // File I/O Helpers
    // ==========================================

    private fun cleanForMatch(s: String): String =
        s.lowercase()
            .replace(Regex("[^a-z0-9 ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()


    private suspend fun <T> saveToFile(fileName: String, data: T) = withContext(Dispatchers.IO) {
        val dir = baseFilesDir ?: return@withContext
        try {
            if (!dir.exists()) dir.mkdirs()
            val json = gson.toJson(data)
            val tempFile = File(dir, "$fileName.tmp")
            val targetFile = File(dir, fileName)

            tempFile.writeText(json)
            if (tempFile.renameTo(targetFile)) {
                // Success atomic rename
            } else {
                targetFile.writeText(json)
                tempFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed writing $fileName to internal storage", e)
        }
    }
}
