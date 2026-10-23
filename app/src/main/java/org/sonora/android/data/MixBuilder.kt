package org.sonora.android.data

import org.sonora.android.api.Track
import java.text.Normalizer

/**
 * Pure generation logic for the Home mixes. Kept free of Android APIs so it can be
 * unit-tested and reasoned about independently from persistence.
 */

/** Lowercases, strips accents/punctuation and collapses spaces for genre matching. */
internal fun normalizeForGenre(value: String): String {
    val lowered = value.lowercase()
        .replace("ș", "s")
        .replace("ț", "t")
        .replace("ă", "a")
        .replace("â", "a")
        .replace("î", "i")
    val withoutMarks = Normalizer.normalize(lowered, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
    return withoutMarks.replace(Regex("[^a-z0-9]+"), " ").trim()
}

/** Whole-word/phrase match on already-normalized text (" inna " matches "inna feat. x"). */
private fun phraseContains(haystack: String, needle: String): Boolean {
    if (needle.isBlank()) return false
    return " $haystack ".contains(" $needle ")
}

/** True when [track] belongs to this genre according to the local curated heuristic. */
fun MixGenre.matches(track: Track): Boolean {
    val artist = normalizeForGenre(track.artist)
    if (seedArtists.any { phraseContains(artist, normalizeForGenre(it)) }) return true
    if (keywords.isEmpty()) return false
    val text = normalizeForGenre(
        listOfNotNull(track.title, track.album).joinToString(" ")
    )
    return keywords.any { phraseContains(text, normalizeForGenre(it)) }
}

/**
 * Builds all available mixes: On Repeat plus the genre mixes that have at least
 * [ON_REPEAT_MIN_TRACKS] matching tracks. Existing mixes are reused to preserve
 * their saved snapshot (`savedTracks`/`savedAt`), and a mix that temporarily falls
 * below the threshold is kept with its previous contents instead of being emptied.
 */
fun buildMixes(
    ranked: List<RankedTrack>,
    existing: List<Mix>,
    now: Long = System.currentTimeMillis()
): List<Mix> {
    val previousById = existing.associateBy { it.id }
    val result = mutableListOf<Mix>()

    val onRepeatTracks = selectOnRepeatTracks(ranked)
    val previousOnRepeat = previousById[MIX_ON_REPEAT_ID]
    if (onRepeatTracks.size >= ON_REPEAT_MIN_TRACKS) {
        result += Mix(
            id = MIX_ON_REPEAT_ID,
            kind = MIX_KIND_ON_REPEAT,
            name = "On Repeat",
            tracks = onRepeatTracks,
            updatedAt = now,
            savedTracks = previousOnRepeat?.savedTracks,
            savedAt = previousOnRepeat?.savedAt
        )
    } else if (previousOnRepeat != null) {
        result += previousOnRepeat
    }

    for (genre in MixGenre.entries) {
        val id = "mix_${genre.id}"
        val tracks = ranked.asSequence()
            .filter { genre.matches(it.track) }
            .map { it.track }
            .distinctBy { songKey(it) }
            .take(ON_REPEAT_MAX_TRACKS)
            .toList()
        val previous = previousById[id]
        if (tracks.size >= ON_REPEAT_MIN_TRACKS) {
            result += Mix(
                id = id,
                kind = MIX_KIND_GENRE,
                genreKey = genre.id,
                name = genre.displayName,
                tracks = tracks,
                updatedAt = now,
                savedTracks = previous?.savedTracks,
                savedAt = previous?.savedAt
            )
        } else if (previous != null) {
            result += previous
        }
    }
    return result
}

/**
 * Migration: the old `system_on_repeat` playlist becomes a non-saved mix and is removed
 * from the user playlists so it no longer appears in the Library.
 */
internal fun migrateOnRepeatPlaylistToMix(
    playlists: List<Playlist>,
    mixes: List<Mix>
): Pair<List<Playlist>, List<Mix>> {
    val old = playlists.firstOrNull { it.id == MusicRepository.PLAYLIST_ON_REPEAT_ID }
        ?: return playlists to mixes
    val remainingPlaylists = playlists.filterNot { it.id == MusicRepository.PLAYLIST_ON_REPEAT_ID }
    if (mixes.any { it.id == MIX_ON_REPEAT_ID }) return remainingPlaylists to mixes

    val migrated = Mix(
        id = MIX_ON_REPEAT_ID,
        kind = MIX_KIND_ON_REPEAT,
        name = "On Repeat",
        tracks = old.tracks,
        updatedAt = old.updatedAt,
        savedTracks = null,
        savedAt = null
    )
    return remainingPlaylists to (mixes + migrated)
}
