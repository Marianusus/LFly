package org.sonora.android.playback.dj

import org.sonora.android.api.ArtistCredits
import org.sonora.android.api.Track
import kotlin.math.abs
import kotlin.math.ln
import kotlin.random.Random

/**
 * How the DJ moves from song to song like a real one: it starts from a song the listener loves and
 * each next song flows out of the one before. Pure, so the choices can be tested.
 */
internal object DjFlow {
    /** Songs the recommendation engine offers for each step of the flow. */
    const val CANDIDATES = 15

    /** Most played songs a block may start from. */
    const val ANCHOR_POOL = 12

    /** Tempos further apart than this (8%) no longer mix on the beat. */
    private const val TEMPO_TOLERANCE = 0.08

    /** Too short for Automix to blend: such songs just end. */
    private const val MIN_MIX_MS = 60_000L

    /** The best, second and third candidate are picked this often, so a session never repeats itself. */
    private val PICK_WEIGHTS = doubleArrayOf(0.55, 0.3, 0.15)

    /**
     * How far apart two tempos are, as a ratio in log scale: 0 when they match, also at double or
     * half time, the way a DJ hears 70 and 140 BPM as the same groove. Null when either is unknown.
     */
    fun tempoDistance(a: Float?, b: Float?): Double? {
        if (a == null || b == null || a <= 0f || b <= 0f) return null
        val ratio = ln(b.toDouble() / a)
        val octave = ln(2.0)
        return minOf(abs(ratio), abs(ratio - octave), abs(ratio + octave))
    }

    /**
     * How well [candidate] follows [previous]. The engine's own order says how close the style is
     * ([rank] of [total]); a matching tempo counts most, a different artist keeps the set moving,
     * and songs too short to mix are pushed back.
     */
    fun score(previous: Track, candidate: Track, rank: Int, total: Int, recentArtists: List<String>): Double {
        var score = if (total > 1) 1.5 * (1.0 - rank.toDouble() / (total - 1)) else 1.5
        tempoDistance(previous.bpm, candidate.bpm)?.let { distance ->
            score += 2.0 * (1.0 - distance / TEMPO_TOLERANCE).coerceIn(-1.0, 1.0)
        }
        val artist = artistKey(candidate)
        when {
            artist == artistKey(previous) -> score -= 2.0
            recentArtists.any { artistKey(it) == artist } -> score -= 1.0
        }
        if (candidate.durationMs in 1 until MIN_MIX_MS) score -= 1.5
        return score
    }

    /** The next song of the flow from [candidates] (in the engine's order), or null when there are none. */
    fun pickNext(previous: Track, candidates: List<Track>, recentArtists: List<String>, random: Random): Track? {
        if (candidates.isEmpty()) return null
        val ranked = candidates
            .mapIndexed { index, track -> track to score(previous, track, index, candidates.size, recentArtists) }
            .sortedByDescending { it.second }
            .map { it.first }
        return weightedPick(ranked, random)
    }

    /**
     * Where a block starts: one of the listener's most played songs ([pool], most played first).
     * The first block just picks among the favourites; later ones pick the favourite that follows
     * best from the song playing now, so even the jump to a new block sounds mixed.
     */
    fun pickAnchor(pool: List<Track>, previous: Track?, random: Random): Track? {
        val head = pool.take(ANCHOR_POOL)
        if (head.isEmpty()) return null
        if (previous == null) return head.random(random)
        val ranked = head
            .map { track ->
                var score = random.nextDouble(0.0, 0.5)
                tempoDistance(previous.bpm, track.bpm)?.let { score += 2.0 * (1.0 - it / TEMPO_TOLERANCE).coerceIn(-1.0, 1.0) }
                if (artistKey(track) == artistKey(previous)) score -= 1.5
                track to score
            }
            .sortedByDescending { it.second }
            .map { it.first }
        return weightedPick(ranked, random)
    }

    private fun weightedPick(ranked: List<Track>, random: Random): Track {
        val top = ranked.take(PICK_WEIGHTS.size)
        val weights = PICK_WEIGHTS.take(top.size)
        var roll = random.nextDouble() * weights.sum()
        top.forEachIndexed { index, track ->
            roll -= weights[index]
            if (roll <= 0) return track
        }
        return top.last()
    }

    private fun artistKey(track: Track): String = artistKey(track.artist)

    private fun artistKey(name: String): String = ArtistCredits.normalize(name)
}
