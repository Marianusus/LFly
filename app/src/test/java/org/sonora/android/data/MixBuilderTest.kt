package org.sonora.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sonora.android.api.Track

class MixBuilderTest {

    private fun track(
        id: String,
        title: String,
        artist: String,
        album: String? = null,
        durationMs: Long = 200_000L
    ) = Track(
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        coverUrl = null
    )

    private fun ranked(track: Track, plays: Int = 3) = RankedTrack(
        track = track,
        playCount = plays,
        lastPlayedAtEpochMs = plays.toLong()
    )

    private fun europopTracks(count: Int): List<RankedTrack> {
        val artists = listOf(
            "Alexandra Stan", "INNA", "Edward Maya", "Akcent", "DJ Project",
            "Smiley", "Delia", "Andia", "Carla's Dreams", "Irina Rimes"
        )
        return (0 until count).map {
            ranked(track("eu_$it", "Euro Track $it", artists[it % artists.size]))
        }
    }

    private fun danceTracks(count: Int): List<RankedTrack> {
        val artists = listOf(
            "Calvin Harris", "David Guetta", "Avicii", "Alesso", "Martin Garrix",
            "Tiësto", "Kygo", "Alan Walker", "Marshmello", "The Chainsmokers"
        )
        return (0 until count).map {
            ranked(track("dance_$it", "Dance Track $it", artists[it % artists.size]))
        }
    }

    @Test
    fun `genre matcher recognises curated artists and keywords`() {
        assertTrue(MixGenre.EUROPOP.matches(track("1", "Mr. Saxobeat", "Alexandra Stan")))
        assertTrue(MixGenre.DANCE.matches(track("2", "How Deep Is Your Love", "Calvin Harris")))
        assertTrue(MixGenre.MANELE.matches(track("3", "Doua Telefoane", "Denisa")))
        assertTrue(MixGenre.POP.matches(track("4", "Patient", "Taylor Swift")))
        assertTrue(MixGenre.MANELE.matches(track("5", "Manele Mix 2026", "Unknown")))
        assertFalse(MixGenre.POP.matches(track("6", "Instrumental", "Unknown Artist")))
    }

    @Test
    fun `builds on repeat and only genres with at least eight tracks`() {
        val input = europopTracks(10) + danceTracks(10) +
            (0 until 3).map { ranked(track("pop_$it", "Pop $it", "Taylor Swift")) }

        val mixes = buildMixes(ranked = input, existing = emptyList(), now = 1_000L)

        assertTrue(mixes.any { it.id == MIX_ON_REPEAT_ID })
        assertTrue(mixes.any { it.id == "mix_europop" })
        assertTrue(mixes.any { it.id == "mix_dance" })
        assertFalse(mixes.any { it.id == "mix_pop" })
        assertEquals(10, mixes.first { it.id == "mix_europop" }.trackCount)
        assertFalse(mixes.first { it.id == "mix_europop" }.isSaved)
    }

    @Test
    fun `genre mixes are capped at thirty and deduplicated`() {
        val input = danceTracks(40) +
            ranked(track("dup", "Dance Track 0", "Calvin Harris"))

        val mixes = buildMixes(ranked = input, existing = emptyList(), now = 1_000L)
        val dance = mixes.first { it.id == "mix_dance" }

        assertEquals(ON_REPEAT_MAX_TRACKS, dance.trackCount)
        assertEquals(dance.tracks.map { songKey(it) }.toSet().size, dance.trackCount)
    }

    @Test
    fun `saving creates a static snapshot that survives a rebuild`() {
        val snapshot = listOf(track("old", "Saved Song", "Alexandra Stan"))
        val existing = Mix(
            id = "mix_europop",
            kind = MIX_KIND_GENRE,
            genreKey = "europop",
            name = "Europop Mix",
            tracks = snapshot,
            updatedAt = 10L,
            savedTracks = snapshot,
            savedAt = 20L
        )

        val rebuilt = buildMixes(europopTracks(10), existing = listOf(existing), now = 5_000L)
        val mix = rebuilt.first { it.id == "mix_europop" }

        assertTrue(mix.isSaved)
        assertEquals(snapshot, mix.savedTracks)
        assertEquals(20L, mix.savedAt)
        assertEquals(5_000L, mix.updatedAt)
        assertEquals(10, mix.trackCount)
    }

    @Test
    fun `a saved mix that falls below the threshold is kept intact`() {
        val snapshot = listOf(track("old", "Saved Pop", "Taylor Swift"))
        val existing = Mix(
            id = "mix_pop",
            kind = MIX_KIND_GENRE,
            genreKey = "pop",
            name = "Pop Mix",
            tracks = snapshot,
            savedTracks = snapshot,
            savedAt = 99L
        )

        val rebuilt = buildMixes(
            ranked = listOf(ranked(track("new", "New Pop", "Taylor Swift"))),
            existing = listOf(existing),
            now = 5_000L
        )

        val pop = rebuilt.firstOrNull { it.id == "mix_pop" }
        assertNotNull(pop)
        assertEquals(1, pop!!.trackCount)
        assertTrue(pop.isSaved)
    }

    @Test
    fun `legacy On Repeat playlist is migrated out of playlists`() {
        val mixTrack = track("t1", "Song", "Artist")
        val legacyPlaylist = Playlist(
            id = MusicRepository.PLAYLIST_ON_REPEAT_ID,
            name = "On Repeat",
            tracks = listOf(mixTrack)
        )

        val (playlists, mixes) = migrateOnRepeatPlaylistToMix(
            playlists = listOf(
                Playlist(id = "user", name = "Mine", tracks = emptyList()),
                legacyPlaylist
            ),
            mixes = emptyList()
        )

        assertEquals(listOf("user"), playlists.map { it.id })
        val migrated = mixes.first { it.id == MIX_ON_REPEAT_ID }
        assertEquals(listOf(mixTrack), migrated.tracks)
        assertNull(migrated.savedTracks)
        assertFalse(migrated.isSaved)
    }

    @Test
    fun `migration is idempotent and keeps an existing mix`() {
        val existingMix = Mix(
            id = MIX_ON_REPEAT_ID,
            kind = MIX_KIND_ON_REPEAT,
            name = "On Repeat",
            tracks = listOf(track("new", "New", "Artist"))
        )
        val legacy = Playlist(
            id = MusicRepository.PLAYLIST_ON_REPEAT_ID,
            name = "On Repeat",
            tracks = listOf(track("old", "Old", "Artist"))
        )

        val (playlists, mixes) = migrateOnRepeatPlaylistToMix(
            playlists = listOf(legacy),
            mixes = listOf(existingMix)
        )

        assertTrue(playlists.isEmpty())
        assertEquals(1, mixes.size)
        assertEquals(existingMix, mixes.first())
    }
}
