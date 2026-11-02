package org.sonora.android.playback.dj

import org.sonora.android.api.ArtistCredits
import org.sonora.android.api.Track
import org.sonora.android.data.FollowedArtist
import org.sonora.android.data.MIX_KIND_GENRE
import org.sonora.android.data.Mix
import kotlin.random.Random

/** The listener's own songs, the material for the blocks that need no network. */
data class DjLibrary(
    val topPlayed: List<Track> = emptyList(),
    val favorites: List<Track> = emptyList(),
    /** Most recent first. */
    val history: List<Track> = emptyList(),
    val followedArtists: List<FollowedArtist> = emptyList(),
    val mixes: List<Mix> = emptyList()
)

/** Songs the DJ must not pick. */
data class DjExclusions(
    val dislikedTrackIds: Set<String> = emptySet(),
    val dislikedArtists: Set<String> = emptySet(),
    /** Songs already played or queued in this DJ session. */
    val sessionTracks: List<Track> = emptyList(),
    /** The latest history entries, most recent first; only TOP_PLAYED may repeat them. */
    val recentHistory: List<Track> = emptyList()
) {
    internal val dislikedArtistKeys: Set<String> =
        dislikedArtists.map { ArtistCredits.normalize(it) }.filter { it.isNotBlank() }.toSet()
    internal val sessionIds: Set<String> = sessionTracks.mapTo(HashSet()) { it.id }
    internal val sessionKeys: Set<String> = sessionTracks.mapTo(HashSet()) { DjBlocks.trackKey(it) }
    internal val historyIds: Set<String> = recentHistory.mapTo(HashSet()) { it.id }
    internal val historyKeys: Set<String> = recentHistory.mapTo(HashSet()) { DjBlocks.trackKey(it) }
}

/**
 * The DJ's choices: which kind of block comes next and which songs go in it. Pure, and every random
 * choice goes through the given [Random], so a seeded session always plays out the same way.
 */
object DjBlocks {
    const val MIN_TRACKS = 4
    const val MAX_TRACKS = 6

    /** How much of the history counts as "heard lately". */
    const val RECENT_HISTORY = 50

    /** Most songs by one artist in a row in a DISCOVERY block, so it feels like discovery. */
    const val MAX_ARTIST_RUN = 2

    /** The order kinds come back in, so consecutive blocks differ in feel. */
    val ROTATION = listOf(
        DjBlockKind.TOP_PLAYED,
        DjBlockKind.DISCOVERY,
        DjBlockKind.FOLLOWED_ARTIST,
        DjBlockKind.THROWBACK,
        DjBlockKind.MIX
    )

    /** How many top played songs a TOP_PLAYED block draws from, so it favours the real favourites. */
    private const val TOP_POOL = MAX_TRACKS * 3

    /**
     * Kinds worth trying after [previous], in rotation order; never [previous] itself and only kinds in
     * [available]. With no previous block the session opens with TOP_PLAYED, then DISCOVERY.
     */
    fun kindOrder(previous: DjBlockKind?, available: Set<DjBlockKind>): List<DjBlockKind> {
        val start = previous?.let { ROTATION.indexOf(it) + 1 } ?: 0
        return ROTATION.indices
            .map { ROTATION[(start + it) % ROTATION.size] }
            .filter { it != previous && it in available }
    }

    fun nextKind(previous: DjBlockKind?, available: Set<DjBlockKind>): DjBlockKind? =
        kindOrder(previous, available).firstOrNull()

    /**
     * Kinds that have material. Local kinds need enough usable songs; FOLLOWED_ARTIST needs a followed
     * artist and DISCOVERY a seed, and only find out whether they work once the network answers.
     */
    fun availableKinds(library: DjLibrary, exclusions: DjExclusions): Set<DjBlockKind> = buildSet {
        if (usable(DjBlockKind.TOP_PLAYED, topPlayedCandidates(library), exclusions).size >= MIN_TRACKS) {
            add(DjBlockKind.TOP_PLAYED)
        }
        if (seedFor(library) != null) add(DjBlockKind.DISCOVERY)
        if (library.followedArtists.any { !isDislikedArtist(it.name, exclusions) }) add(DjBlockKind.FOLLOWED_ARTIST)
        if (usable(DjBlockKind.THROWBACK, throwbackCandidates(library), exclusions).size >= MIN_TRACKS) {
            add(DjBlockKind.THROWBACK)
        }
        if (genreMixes(library).any { usable(DjBlockKind.MIX, it.tracks, exclusions).size >= MIN_TRACKS }) {
            add(DjBlockKind.MIX)
        }
    }

    /** The song DISCOVERY starts from when autoplay gave none: the latest listen, else a favourite. */
    fun seedFor(library: DjLibrary): Track? =
        library.history.firstOrNull() ?: library.topPlayed.firstOrNull() ?: library.favorites.firstOrNull()

    /**
     * [candidates] minus disliked songs and artists, this session's songs and, except for TOP_PLAYED,
     * the recent history; deduplicated by id and by title and artist. Order is kept.
     */
    fun usable(kind: DjBlockKind, candidates: List<Track>, exclusions: DjExclusions): List<Track> {
        val seenIds = HashSet<String>()
        val seenKeys = HashSet<String>()
        return candidates.filter { track ->
            val key = trackKey(track)
            when {
                track.id.isBlank() -> false
                track.id in exclusions.dislikedTrackIds -> false
                artistKeys(track).any { it in exclusions.dislikedArtistKeys } -> false
                track.id in exclusions.sessionIds || key in exclusions.sessionKeys -> false
                kind != DjBlockKind.TOP_PLAYED &&
                    (track.id in exclusions.historyIds || key in exclusions.historyKeys) -> false
                else -> seenIds.add(track.id) and seenKeys.add(key)
            }
        }
    }

    /**
     * A block of [kind] from [candidates], or null when fewer than [MIN_TRACKS] usable songs are left.
     * DISCOVERY keeps the engine's order and caps runs by one artist; the listener's own lists are
     * shuffled so the same favourites don't always open the block.
     */
    fun block(
        kind: DjBlockKind,
        candidates: List<Track>,
        exclusions: DjExclusions,
        random: Random,
        subject: String? = null
    ): DjBlock? {
        val pool = usable(kind, candidates, exclusions)
        val size = random.nextInt(MIN_TRACKS, MAX_TRACKS + 1)
        val picked = when (kind) {
            DjBlockKind.DISCOVERY -> limitArtistRuns(pool, size)
            DjBlockKind.TOP_PLAYED -> pool.take(TOP_POOL).shuffled(random).take(size)
            else -> pool.shuffled(random).take(size)
        }
        return if (picked.size >= MIN_TRACKS) DjBlock(kind, picked, subject) else null
    }

    /** A TOP_PLAYED, THROWBACK or MIX block from the listener's own songs; null for other kinds. */
    fun localBlock(
        kind: DjBlockKind,
        library: DjLibrary,
        exclusions: DjExclusions,
        random: Random,
        avoidMix: String? = null
    ): DjBlock? = when (kind) {
        DjBlockKind.TOP_PLAYED -> block(kind, topPlayedCandidates(library), exclusions, random)
        DjBlockKind.THROWBACK -> block(kind, throwbackCandidates(library), exclusions, random)
        DjBlockKind.MIX -> {
            val mixes = genreMixes(library)
                .filter { usable(kind, it.tracks, exclusions).size >= MIN_TRACKS }
            val choices = mixes.filter { it.name != avoidMix }.ifEmpty { mixes }
            choices.randomOrNull(random)?.let { block(kind, it.tracks, exclusions, random, it.name) }
        }
        else -> null
    }

    /** A followed artist for the next FOLLOWED_ARTIST block, other than [avoid] when there is a choice. */
    fun pickArtist(followed: List<FollowedArtist>, exclusions: DjExclusions, avoid: String?, random: Random): FollowedArtist? {
        val liked = followed.filter { it.name.isNotBlank() && !isDislikedArtist(it.name, exclusions) }
        val avoidKey = ArtistCredits.normalize(avoid)
        return liked.filter { ArtistCredits.normalize(it.name) != avoidKey }.ifEmpty { liked }.randomOrNull(random)
    }

    /** Songs in [tracks] credited to [artist]. */
    fun tracksByArtist(artist: String, tracks: List<Track>): List<Track> {
        val key = ArtistCredits.normalize(artist)
        if (key.isBlank()) return emptyList()
        return tracks.filter { key in artistKeys(it) }
    }

    /**
     * A FOLLOWED_ARTIST block: about half [artistTracks] (the artist's own songs, one of them first so the
     * intro fits) mixed with [related] songs. Either side fills in when the other runs short.
     */
    fun artistBlock(
        artist: String,
        artistTracks: List<Track>,
        related: List<Track>,
        exclusions: DjExclusions,
        random: Random
    ): DjBlock? {
        val kind = DjBlockKind.FOLLOWED_ARTIST
        val ownIds = artistTracks.mapTo(HashSet()) { it.id }
        val pool = usable(kind, artistTracks + related, exclusions)
        val own = pool.filter { it.id in ownIds }.shuffled(random)
        val others = pool.filter { it.id !in ownIds }
        if (own.isEmpty()) return null
        val size = random.nextInt(MIN_TRACKS, MAX_TRACKS + 1)
        val ownPicked = own.take(maxOf((size + 1) / 2, size - others.size))
        val othersPicked = others.take(size - ownPicked.size)
        val tracks = buildList {
            for (i in 0 until maxOf(ownPicked.size, othersPicked.size)) {
                ownPicked.getOrNull(i)?.let(::add)
                othersPicked.getOrNull(i)?.let(::add)
            }
        }
        return if (tracks.size >= MIN_TRACKS) DjBlock(kind, tracks, artist) else null
    }

    /** Same song across providers and uploads: normalised title plus artist. */
    fun trackKey(track: Track): String =
        ArtistCredits.normalize(track.title) + "|" + ArtistCredits.normalize(track.artist)

    private fun topPlayedCandidates(library: DjLibrary): List<Track> = library.topPlayed + library.favorites

    /** Favourites first, then the less played of the top played, which the recent-history filter thins out. */
    private fun throwbackCandidates(library: DjLibrary): List<Track> = library.favorites + library.topPlayed.asReversed()

    private fun genreMixes(library: DjLibrary): List<Mix> =
        library.mixes.filter { it.kind == MIX_KIND_GENRE && it.name.isNotBlank() }

    private fun isDislikedArtist(name: String, exclusions: DjExclusions): Boolean =
        ArtistCredits.normalize(name) in exclusions.dislikedArtistKeys

    private fun artistKeys(track: Track): Set<String> {
        val credits = track.artists?.takeIf { it.isNotEmpty() } ?: ArtistCredits.fromText(track.artist)
        return (credits.map { it.normalized } + ArtistCredits.normalize(track.artist))
            .filter { it.isNotBlank() }
            .toSet()
    }

    /** The main artist, used for the run limit. */
    private fun leadArtist(track: Track): String =
        track.artists?.firstOrNull()?.normalized ?: ArtistCredits.normalize(track.artist)

    /** Takes up to [size] songs in order, skipping any that would make a run longer than [MAX_ARTIST_RUN]. */
    private fun limitArtistRuns(pool: List<Track>, size: Int): List<Track> {
        val remaining = pool.toMutableList()
        val picked = ArrayList<Track>(size)
        while (picked.size < size) {
            val index = remaining.indexOfFirst { !extendsLongRun(picked, it) }
            if (index < 0) break
            picked += remaining.removeAt(index)
        }
        return picked
    }

    private fun extendsLongRun(picked: List<Track>, next: Track): Boolean {
        if (picked.size < MAX_ARTIST_RUN) return false
        val artist = leadArtist(next)
        return picked.takeLast(MAX_ARTIST_RUN).all { leadArtist(it) == artist }
    }
}
