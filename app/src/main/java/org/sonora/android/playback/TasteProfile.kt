package org.sonora.android.playback

import org.sonora.android.api.Track
import org.sonora.android.data.FollowedArtist

/**
 * A weighted artist seed derived from the user's taste signals.
 *
 * @param name display artist name as it should be searched/matched.
 * @param browseId YouTube Music artist browseId when known (enables artist top-tracks harvesting).
 * @param weight aggregated taste weight; higher = stronger signal.
 */
data class TasteArtistSeed(
    val name: String,
    val browseId: String?,
    val weight: Double
)

/**
 * Deterministic, testable taste profile aggregating the user's explicit and implicit signals:
 * - followed artists (explicit, high weight),
 * - top-played artists / tracks (implicit, play-count weighted),
 * - recent play history (implicit, recency weighted).
 *
 * Disliked tracks and artists are always excluded.
 */
data class TasteProfile(
    val seedTracks: List<Track>,
    val seedArtists: List<TasteArtistSeed>
) {
    val isEmpty: Boolean get() = seedTracks.isEmpty() && seedArtists.isEmpty()

    companion object {
        const val FOLLOW_WEIGHT = 3.0
        const val PLAY_COUNT_WEIGHT = 0.5
        const val HISTORY_WEIGHT = 1.0
        const val HISTORY_DECAY = 0.04
        const val MIN_HISTORY_WEIGHT = 0.2
        const val MAX_SEED_TRACKS = 8
        const val MAX_SEED_ARTISTS = 10

        /**
         * Builds the profile from raw signals. Pure function: no I/O, fully deterministic.
         *
         * @param followedArtists explicitly followed YouTube artists (highest weight).
         * @param history recent play history, most recent first.
         * @param topPlayedTracks (track, playCount) pairs ordered by relevance.
         * @param topPlayedArtists (artistName, playCount) pairs.
         */
        fun build(
            followedArtists: List<FollowedArtist> = emptyList(),
            history: List<Track> = emptyList(),
            topPlayedTracks: List<Pair<Track, Int>> = emptyList(),
            topPlayedArtists: List<Pair<String, Int>> = emptyList(),
            dislikedTrackIds: Set<String> = emptySet(),
            dislikedArtists: Set<String> = emptySet()
        ): TasteProfile {
            val dislikedArtistKeys = dislikedArtists.map { normalizeArtist(it) }.filter { it.isNotBlank() }.toSet()

            fun isDislikedTrack(track: Track): Boolean =
                track.id in dislikedTrackIds || normalizeArtist(track.artist) in dislikedArtistKeys

            // --- Seed tracks: top played first, then recent history (deduped, not disliked) ---
            val seedTracks = LinkedHashMap<String, Track>()
            topPlayedTracks
                .sortedWith(compareByDescending<Pair<Track, Int>> { it.second }.thenBy { it.first.title })
                .forEach { (track, count) ->
                    if (count > 0 && !isDislikedTrack(track)) seedTracks.putIfAbsent(track.id, track)
                }
            history.forEach { track ->
                if (!isDislikedTrack(track)) seedTracks.putIfAbsent(track.id, track)
            }

            // --- Artist weights ---
            data class Acc(var name: String, var browseId: String?, var weight: Double)
            val artists = LinkedHashMap<String, Acc>()

            fun addArtistWeight(name: String, browseId: String?, weight: Double) {
                if (name.isBlank()) return
                val key = normalizeArtist(name)
                if (key.isBlank() || key in dislikedArtistKeys) return
                val acc = artists[key]
                if (acc == null) {
                    artists[key] = Acc(name.trim(), browseId?.takeIf { it.isNotBlank() }, weight)
                } else {
                    acc.weight += weight
                    if (acc.browseId == null && !browseId.isNullOrBlank()) acc.browseId = browseId
                }
            }

            followedArtists.forEach { artist ->
                addArtistWeight(artist.name, artist.browseId ?: artist.channelId, FOLLOW_WEIGHT)
            }
            topPlayedArtists.forEach { (name, count) ->
                if (count > 0) addArtistWeight(name, null, count * PLAY_COUNT_WEIGHT)
            }
            history.forEachIndexed { index, track ->
                if (isDislikedTrack(track)) return@forEachIndexed
                val recency = (HISTORY_WEIGHT - index * HISTORY_DECAY).coerceAtLeast(MIN_HISTORY_WEIGHT)
                addArtistWeight(track.artist, null, recency)
            }

            val seedArtists = artists.values
                .sortedWith(compareByDescending<Acc> { it.weight }.thenBy { it.name.lowercase() })
                .take(MAX_SEED_ARTISTS)
                .map { TasteArtistSeed(name = it.name, browseId = it.browseId, weight = it.weight) }

            return TasteProfile(
                seedTracks = seedTracks.values.take(MAX_SEED_TRACKS),
                seedArtists = seedArtists
            )
        }

        private fun normalizeArtist(artist: String): String =
            artist.lowercase()
                .split(",", "&", "feat.", "ft.", "featuring")[0]
                .replace(Regex("[^a-z0-9]"), "")
                .trim()
    }
}
