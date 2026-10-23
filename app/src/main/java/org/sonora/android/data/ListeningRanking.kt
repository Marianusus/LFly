package org.sonora.android.data

import org.sonora.android.api.Track
import java.text.Normalizer

/**
 * Pure ranking helpers behind the Home "I tuoi più ascoltati" shelf and the
 * auto-generated "On Repeat" playlist. Kept free of Android dependencies so the
 * behavior is unit-testable.
 */

/** Minimum number of distinct tracks needed before "On Repeat" is created. */
const val ON_REPEAT_MIN_TRACKS = 8

/** Hard cap for the auto-generated playlist. */
const val ON_REPEAT_MAX_TRACKS = 30

/** A song needs at least this many plays to enter "On Repeat". */
const val ON_REPEAT_MIN_PLAYS = 2

/** Spotify refreshes "On Repeat" roughly every five days. */
const val ON_REPEAT_REFRESH_INTERVAL_MS = 5L * 24L * 60L * 60L * 1000L

/** One merged song: variants (yt_/spotify_/local) are summed under the same key. */
data class RankedTrack(
    val track: Track,
    val playCount: Int,
    val lastPlayedAtEpochMs: Long
)

/** A playlist ranked by explicit plays plus the affinity of the songs it contains. */
data class RankedPlaylist(
    val playlist: Playlist,
    val score: Double,
    val lastPlayedAtEpochMs: Long
)

/** A single card in the mixed Home shelf. */
sealed interface ShelfEntry {
    data class TrackEntry(val track: Track, val playCount: Int) : ShelfEntry
    data class PlaylistEntry(val playlist: Playlist) : ShelfEntry
}

private class TrackAggregate(
    var playCount: Int,
    var lastPlayedAtEpochMs: Long,
    var representative: Track
)

/**
 * Normalizes a title/artist pair for match deduplication: strips accents,
 * feat./featuring, remix/remaster/deluxe/version/edit tags, bracketed content,
 * punctuation and casing.
 */
internal fun normalizedMatchKey(title: String, artist: String): String {
    fun clean(value: String): String {
        val lowered = value.lowercase()
            .replace("ș", "s")
            .replace("ț", "t")
            .replace("ă", "a")
            .replace("â", "a")
            .replace("î", "i")
        val withoutAccents = Normalizer.normalize(lowered, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return withoutAccents
            .replace(Regex("\\(.*?\\)"), " ")
            .replace(Regex("\\[.*?\\]"), " ")
            .replace(Regex("\\b(feat\\.?|featuring|ft\\.?)"), " ")
            .replace(Regex("\\b(remix|remaster(ed)?|deluxe|version|edit|live)\\b"), " ")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    val primaryArtist = artist.split(",", "&", " feat", " ft").firstOrNull() ?: artist
    return "${clean(title)}|${clean(primaryArtist)}"
}

/** Stable identity of a song across providers. */
fun songKey(track: Track): String = normalizedMatchKey(track.title, track.artist)

/**
 * Merges play stats by [songKey], sums the plays, keeps the most recently played
 * variant and excludes disliked/invalid tracks. Sorted by plays desc, recency desc.
 */
fun rankTopTracks(
    stats: List<PlayStat>,
    dislikedTrackIds: Set<String> = emptySet(),
    limit: Int = Int.MAX_VALUE
): List<RankedTrack> {
    val aggregated = LinkedHashMap<String, TrackAggregate>()
    for (stat in stats) {
        val track = stat.track
        if (track.durationMs <= 0L) continue
        if (track.id in dislikedTrackIds) continue
        val key = songKey(track)
        if (key == "|") continue
        val existing = aggregated[key]
        if (existing == null) {
            aggregated[key] = TrackAggregate(
                playCount = stat.playCount,
                lastPlayedAtEpochMs = stat.lastPlayedAtEpochMs,
                representative = track
            )
        } else {
            existing.playCount += stat.playCount
            if (stat.lastPlayedAtEpochMs >= existing.lastPlayedAtEpochMs) {
                existing.lastPlayedAtEpochMs = stat.lastPlayedAtEpochMs
                existing.representative = track
            }
        }
    }

    return aggregated.values
        .filter { it.playCount > 0 }
        .sortedWith(
            compareByDescending<TrackAggregate> { it.playCount }
                .thenByDescending { it.lastPlayedAtEpochMs }
        )
        .take(limit)
        .map { RankedTrack(it.representative, it.playCount, it.lastPlayedAtEpochMs) }
}

/**
 * Ranks real playlists by explicit play count plus the affinity of the songs they
 * contain (how many of those songs you actually listen to, and how often). The
 * average is capped so one very repeated song cannot dominate the whole ranking.
 */
fun rankPlaylists(
    playlists: List<Playlist>,
    playlistStats: List<PlaylistPlayStat>,
    trackStats: List<PlayStat>,
    excludedIds: Set<String> = emptySet(),
    limit: Int = Int.MAX_VALUE
): List<RankedPlaylist> {
    val playsByKey = HashMap<String, Int>(trackStats.size)
    val lastByKey = HashMap<String, Long>(trackStats.size)
    for (stat in trackStats) {
        val key = songKey(stat.track)
        if (key == "|") continue
        playsByKey[key] = (playsByKey[key] ?: 0) + stat.playCount
        lastByKey[key] = maxOf(lastByKey[key] ?: 0L, stat.lastPlayedAtEpochMs)
    }
    val explicitById = playlistStats.associateBy { it.playlistId }

    return playlists.asSequence()
        .filter { it.id !in excludedIds && it.tracks.isNotEmpty() }
        .map { playlist ->
            val explicit = explicitById[playlist.id]?.playCount ?: 0
            val keys = playlist.tracks.asSequence()
                .map { songKey(it) }
                .filter { it != "|" }
                .distinct()
                .toList()
            val matchedPlays = keys.mapNotNull { playsByKey[it] }
            val averagePlays = if (matchedPlays.isEmpty()) 0.0 else
                (matchedPlays.sum().toDouble() / matchedPlays.size).coerceAtMost(20.0)
            val score = explicit * 5.0 + matchedPlays.size * 2.0 + averagePlays
            val lastPlayed = maxOf(
                explicitById[playlist.id]?.lastPlayedAtEpochMs ?: 0L,
                keys.maxOfOrNull { lastByKey[it] ?: 0L } ?: 0L
            )
            RankedPlaylist(playlist, score, lastPlayed)
        }
        .filter { it.score > 0.0 }
        .sortedWith(
            compareByDescending<RankedPlaylist> { it.score }
                .thenByDescending { it.lastPlayedAtEpochMs }
        )
        .take(limit)
        .toList()
}

/**
 * Alternates tracks and playlists so the shelf always shows both kinds when both
 * are available (Spotify-style mixed "recently played" shelf).
 */
fun buildShelfEntries(
    tracks: List<RankedTrack>,
    playlists: List<RankedPlaylist>,
    limit: Int = 9
): List<ShelfEntry> {
    val result = ArrayList<ShelfEntry>(limit)
    var trackIndex = 0
    var playlistIndex = 0
    while (result.size < limit && (trackIndex < tracks.size || playlistIndex < playlists.size)) {
        if (trackIndex < tracks.size) {
            val ranked = tracks[trackIndex++]
            result += ShelfEntry.TrackEntry(ranked.track, ranked.playCount)
        }
        if (result.size < limit && playlistIndex < playlists.size) {
            result += ShelfEntry.PlaylistEntry(playlists[playlistIndex++].playlist)
        }
    }
    return result
}

/**
 * Picks the unique tracks that deserve a spot in "On Repeat": at least two plays,
 * capped at [ON_REPEAT_MAX_TRACKS]. The input is expected to be already deduped.
 */
fun selectOnRepeatTracks(
    ranked: List<RankedTrack>,
    limit: Int = ON_REPEAT_MAX_TRACKS
): List<Track> = ranked.asSequence()
    .filter { it.playCount >= ON_REPEAT_MIN_PLAYS }
    .take(limit)
    .map { it.track }
    .toList()

/** True when the auto playlist is due for a rebuild. */
fun shouldRefreshOnRepeat(
    lastBuildEpochMs: Long,
    nowEpochMs: Long = System.currentTimeMillis()
): Boolean = lastBuildEpochMs <= 0L ||
    nowEpochMs - lastBuildEpochMs >= ON_REPEAT_REFRESH_INTERVAL_MS
