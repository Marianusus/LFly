package org.sonora.android.playback.dj

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sonora.android.api.Track
import org.sonora.android.data.FollowedArtist
import org.sonora.android.data.MIX_KIND_GENRE
import org.sonora.android.data.MIX_KIND_ON_REPEAT
import org.sonora.android.data.Mix

class DjBlocksTest {

    private fun track(id: String, artist: String = "Artist $id", title: String = "Song $id") = Track(
        id = "yt_$id",
        title = title,
        artist = artist,
        album = null,
        durationMs = 200_000L,
        coverUrl = null,
        provider = "YouTube"
    )

    private fun tracks(prefix: String, count: Int) = (1..count).map { track("$prefix$it") }

    // FLOW is built before the rotation, so it is never part of it.
    private val allKinds = DjBlockKind.entries.toSet() - DjBlockKind.FLOW

    @Test
    fun firstBlockPrefersTopPlayedThenDiscovery() {
        assertEquals(DjBlockKind.TOP_PLAYED, DjBlocks.nextKind(null, allKinds))
        assertEquals(DjBlockKind.DISCOVERY, DjBlocks.nextKind(null, allKinds - DjBlockKind.TOP_PLAYED))
    }

    @Test
    fun rotationNeverRepeatsAKindAndVisitsEveryOne() {
        var previous: DjBlockKind? = null
        val seen = mutableListOf<DjBlockKind>()
        repeat(10) {
            val next = DjBlocks.nextKind(previous, allKinds)!!
            assertTrue("Repeated $next", next != previous)
            seen += next
            previous = next
        }
        assertEquals(allKinds, seen.take(5).toSet())
    }

    @Test
    fun rotationSkipsKindsWithoutMaterial() {
        val available = setOf(DjBlockKind.DISCOVERY, DjBlockKind.MIX)
        assertEquals(DjBlockKind.MIX, DjBlocks.nextKind(DjBlockKind.DISCOVERY, available))
        assertEquals(DjBlockKind.DISCOVERY, DjBlocks.nextKind(DjBlockKind.MIX, available))
        assertNull(DjBlocks.nextKind(DjBlockKind.MIX, setOf(DjBlockKind.MIX)))
    }

    @Test
    fun availableKindsReflectTheLibrary() {
        assertEquals(emptySet<DjBlockKind>(), DjBlocks.availableKinds(DjLibrary(), DjExclusions()))

        val library = DjLibrary(
            topPlayed = tracks("t", 8),
            history = listOf(track("h1")),
            followedArtists = listOf(FollowedArtist(artistKey = "a", name = "Band")),
            mixes = listOf(Mix(id = "m", kind = MIX_KIND_GENRE, name = "Rock", tracks = tracks("m", 6)))
        )
        assertEquals(allKinds, DjBlocks.availableKinds(library, DjExclusions()))

        // Every top played song heard lately: TOP_PLAYED still works, THROWBACK has nothing left.
        val heard = DjExclusions(recentHistory = library.topPlayed)
        val kinds = DjBlocks.availableKinds(library, heard)
        assertTrue(DjBlockKind.TOP_PLAYED in kinds)
        assertFalse(DjBlockKind.THROWBACK in kinds)
    }

    @Test
    fun onRepeatMixesAreNotGenreBlocks() {
        val library = DjLibrary(mixes = listOf(Mix(id = "r", kind = MIX_KIND_ON_REPEAT, name = "On repeat", tracks = tracks("m", 6))))
        assertFalse(DjBlockKind.MIX in DjBlocks.availableKinds(library, DjExclusions()))
    }

    @Test
    fun usableDedupesByIdAndByTitleAndArtist() {
        val a = track("1", artist = "Queen", title = "Bohemian Rhapsody")
        val sameId = a.copy(title = "Bohemian Rhapsody (Remastered)")
        val sameSong = track("2", artist = "QUEEN", title = "Bohemian  Rhapsody!")
        val other = track("3")
        val usable = DjBlocks.usable(DjBlockKind.DISCOVERY, listOf(a, sameId, sameSong, other), DjExclusions())
        assertEquals(listOf(a, other), usable)
    }

    @Test
    fun usableDropsDislikedSessionAndRecentSongs() {
        val disliked = track("d")
        val badArtist = track("b", artist = "Bad Band")
        val played = track("p")
        val playedElsewhere = track("p2", artist = played.artist, title = played.title)
        val recent = track("r")
        val fresh = track("f")
        val exclusions = DjExclusions(
            dislikedTrackIds = setOf(disliked.id),
            dislikedArtists = setOf("bad band"),
            sessionTracks = listOf(played),
            recentHistory = listOf(recent)
        )
        val candidates = listOf(disliked, badArtist, played, playedElsewhere, recent, fresh)

        assertEquals(listOf(fresh), DjBlocks.usable(DjBlockKind.DISCOVERY, candidates, exclusions))
        // TOP_PLAYED may repeat recent favourites, never this session's songs.
        assertEquals(listOf(recent, fresh), DjBlocks.usable(DjBlockKind.TOP_PLAYED, candidates, exclusions))
    }

    @Test
    fun blocksHoldFourToSixSongs() {
        repeat(50) { seed ->
            val block = DjBlocks.block(DjBlockKind.THROWBACK, tracks("x", 20), DjExclusions(), Random(seed))!!
            assertTrue(block.tracks.size in DjBlocks.MIN_TRACKS..DjBlocks.MAX_TRACKS)
        }
        assertNull(DjBlocks.block(DjBlockKind.THROWBACK, tracks("x", 3), DjExclusions(), Random(1)))
    }

    @Test
    fun discoveryNeverPlaysThreeSongsByOneArtistInARow() {
        val sameArtist = (1..5).map { track("s$it", artist = "Same") }
        val others = tracks("o", 3)
        repeat(20) { seed ->
            val block = DjBlocks.block(DjBlockKind.DISCOVERY, sameArtist + others, DjExclusions(), Random(seed))!!
            block.tracks.windowed(DjBlocks.MAX_ARTIST_RUN + 1).forEach { run ->
                assertTrue("Run too long in ${block.tracks.map { it.id }}", run.map { it.artist }.distinct().size > 1)
            }
        }
        // Nobody else to break the run: the block can't fill up.
        assertNull(DjBlocks.block(DjBlockKind.DISCOVERY, sameArtist, DjExclusions(), Random(0)))
    }

    @Test
    fun sameSeedGivesTheSameBlocks() {
        val library = DjLibrary(
            topPlayed = tracks("t", 15),
            favorites = tracks("f", 15),
            mixes = listOf(
                Mix(id = "1", name = "Rock", tracks = tracks("r", 10)),
                Mix(id = "2", name = "Jazz", tracks = tracks("j", 10))
            )
        )
        for (kind in listOf(DjBlockKind.TOP_PLAYED, DjBlockKind.THROWBACK, DjBlockKind.MIX)) {
            val first = DjBlocks.localBlock(kind, library, DjExclusions(), Random(42))
            val second = DjBlocks.localBlock(kind, library, DjExclusions(), Random(42))
            assertNotNull(first)
            assertEquals(first, second)
        }
    }

    @Test
    fun mixBlockNamesItsMixAndAvoidsTheLastOne() {
        val library = DjLibrary(
            mixes = listOf(
                Mix(id = "1", name = "Rock", tracks = tracks("r", 10)),
                Mix(id = "2", name = "Jazz", tracks = tracks("j", 10))
            )
        )
        repeat(10) { seed ->
            val block = DjBlocks.localBlock(DjBlockKind.MIX, library, DjExclusions(), Random(seed), avoidMix = "Rock")!!
            assertEquals("Jazz", block.subject)
            assertTrue(block.tracks.all { it.id.startsWith("yt_j") })
        }
    }

    @Test
    fun artistBlockOpensWithTheArtistAndMixesInRelatedSongs() {
        val own = (1..6).map { track("a$it", artist = "Band") }
        val related = tracks("r", 6)
        repeat(20) { seed ->
            val block = DjBlocks.artistBlock("Band", own, related, DjExclusions(), Random(seed))!!
            assertEquals(DjBlockKind.FOLLOWED_ARTIST, block.kind)
            assertEquals("Band", block.subject)
            assertEquals("Band", block.tracks.first().artist)
            assertTrue(block.tracks.size in DjBlocks.MIN_TRACKS..DjBlocks.MAX_TRACKS)
            assertTrue(block.tracks.any { it.artist != "Band" })
        }
        assertNull(DjBlocks.artistBlock("Band", emptyList(), related, DjExclusions(), Random(0)))
    }

    @Test
    fun pickArtistAvoidsTheLastAndDislikedArtists() {
        val followed = listOf(
            FollowedArtist(artistKey = "1", name = "Alpha"),
            FollowedArtist(artistKey = "2", name = "Beta"),
            FollowedArtist(artistKey = "3", name = "Gamma")
        )
        val exclusions = DjExclusions(dislikedArtists = setOf("Gamma"))
        repeat(20) { seed ->
            assertEquals("Beta", DjBlocks.pickArtist(followed, exclusions, avoid = "alpha", random = Random(seed))?.name)
        }
        assertEquals("Alpha", DjBlocks.pickArtist(followed.take(1), DjExclusions(), avoid = "Alpha", random = Random(0))?.name)
    }

    @Test
    fun tracksByArtistMatchesCredits() {
        val duet = track("1", artist = "Band feat. Friend")
        val solo = track("2", artist = "Band")
        val other = track("3", artist = "Bandits")
        assertEquals(listOf(duet, solo), DjBlocks.tracksByArtist("band", listOf(duet, solo, other)))
    }
}
