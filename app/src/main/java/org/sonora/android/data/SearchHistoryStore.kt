package org.sonora.android.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.sonora.android.api.Track
import org.sonora.android.api.YouTubeAlbum
import org.sonora.android.api.YouTubeArtist
import org.sonora.android.api.YouTubePlaylist

enum class SearchHistoryType {
    QUERY,
    TRACK,
    ARTIST,
    ALBUM,
    PLAYLIST
}

/** A real entity the user opened from search, kept so recents can show artwork and type. */
data class SearchHistoryEntry(
    val id: String,
    val title: String,
    val subtitle: String,
    val artworkUrl: String?,
    val type: SearchHistoryType,
    val timestamp: Long = System.currentTimeMillis(),
    /** Full playable metadata for a track; null for browse entities. */
    val track: Track? = null
)

/**
 * Local, cover-backed search recents.
 *
 * Only entities the user actually opened or played are recorded: a text query
 * alone is not useful without artwork or a destination, and the exact query is
 * already in the search field while it is being typed.
 */
object SearchHistoryStore {

    private const val FILE_NAME = "search_history.json"
    private const val MAX_ENTRIES = 20

    private val gson = Gson()
    private val listType = object : TypeToken<List<SearchHistoryEntry>>() {}.type
    private lateinit var file: File

    private val _recent = MutableStateFlow<List<SearchHistoryEntry>>(emptyList())
    val recent: StateFlow<List<SearchHistoryEntry>> = _recent.asStateFlow()

    fun init(context: Context) {
        file = File(context.filesDir, FILE_NAME)
        _recent.value = runCatching {
            if (!file.exists()) emptyList() else {
                gson.fromJson<List<SearchHistoryEntry>>(file.readText(), listType).orEmpty()
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun record(entry: SearchHistoryEntry) {
        if (entry.id.isBlank() || entry.title.isBlank()) return
        val updated = mergeHistory(
            current = _recent.value,
            entry = entry.copy(timestamp = System.currentTimeMillis()),
            maxEntries = MAX_ENTRIES
        )
        _recent.value = updated
        persist(updated)
    }

    /** Records a committed search term, so the idle Search page has recents immediately. */
    fun recordQuery(query: String) {
        val text = query.trim()
        if (text.isBlank()) return
        record(
            SearchHistoryEntry(
                id = text.lowercase(Locale.ROOT),
                title = text,
                subtitle = "",
                artworkUrl = null,
                type = SearchHistoryType.QUERY
            )
        )
    }

    @Synchronized
    fun remove(type: SearchHistoryType, id: String) {
        val updated = _recent.value.filterNot { it.type == type && it.id.equals(id, ignoreCase = true) }
        _recent.value = updated
        persist(updated)
    }

    @Synchronized
    fun clear() {
        _recent.value = emptyList()
        persist(emptyList())
    }

    internal fun mergeHistory(
        current: List<SearchHistoryEntry>,
        entry: SearchHistoryEntry,
        maxEntries: Int = MAX_ENTRIES
    ): List<SearchHistoryEntry> {
        if (entry.id.isBlank() || entry.title.isBlank() || maxEntries <= 0) return current
        val deduped = current.filterNot {
            it.type == entry.type && it.id.equals(entry.id, ignoreCase = true)
        }
        return (listOf(entry) + deduped).take(maxEntries)
    }

    private fun persist(entries: List<SearchHistoryEntry>) {
        if (!this::file.isInitialized) return
        runCatching {
            file.writeText(gson.toJson(entries, listType))
        }
    }

    fun fromTrack(track: Track): SearchHistoryEntry? {
        if (track.id.isBlank() || track.title.isBlank()) return null
        return SearchHistoryEntry(
            id = track.id,
            title = track.title,
            subtitle = track.artist,
            artworkUrl = track.coverUrl,
            type = SearchHistoryType.TRACK,
            track = track.copy(streamUrl = null)
        )
    }

    fun fromArtist(artist: YouTubeArtist): SearchHistoryEntry? {
        val id = artist.browseId?.takeIf { it.isNotBlank() }
            ?: artist.channelId?.takeIf { it.isNotBlank() }
            ?: return null
        return SearchHistoryEntry(
            id = id,
            title = artist.name,
            subtitle = artist.subtitle.orEmpty(),
            artworkUrl = artist.thumbnailUrl,
            type = SearchHistoryType.ARTIST
        )
    }

    fun fromAlbum(album: YouTubeAlbum): SearchHistoryEntry? {
        val id = album.detailId ?: return null
        return SearchHistoryEntry(
            id = id,
            title = album.title,
            subtitle = album.artist.orEmpty(),
            artworkUrl = album.thumbnailUrl,
            type = SearchHistoryType.ALBUM
        )
    }

    fun fromPlaylist(playlist: YouTubePlaylist): SearchHistoryEntry? {
        val id = playlist.detailId ?: return null
        return SearchHistoryEntry(
            id = id,
            title = playlist.title,
            subtitle = playlist.author.orEmpty(),
            artworkUrl = playlist.thumbnailUrl,
            type = SearchHistoryType.PLAYLIST
        )
    }
}
