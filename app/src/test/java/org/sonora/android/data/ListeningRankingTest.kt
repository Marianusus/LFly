package org.sonora.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sonora.android.api.Track

class ListeningRankingTest {

    private fun track(
        id: String,
        title: String,
        artist: String,
        durationMs: Long = 200_000L
    ) = Track(
        id = id,
        title = title,
        artist = artist,
        album = null,
        durationMs = durationMs,
        coverUrl = null
    )

    private fun stat(
        track: Track,
        plays: Int,
        lastPlayed: Long = 0L
    ) = PlayStat(
        track = track,
        playCount = plays,
        completionCount = plays,
        lastPlayedAtEpochMs = lastPlayed
    )

    @Test
    fun `normalized key strips accents tags and features`() {
        val a = normalizedMatchKey("Rüfüs Du Sol - Innerbloom (feat. Someone) [Remastered]", "RÜFÜS DU SOL")
        val b = normalizedMatchKey("Rufus Du Sol - Innerbloom", "Rufus Du Sol")
        assertEquals(a, b)
    }

    @Test
    fun `rank top tracks merges the same song across providers and sums plays`() {
        val yt = track("yt_1", "Doua Telefoane (feat. Mr. Juve)", "Denisa")
        val spotify = track("spotify_1", "Doua Telefoane", "Denisa")
        val ranked = rankTopTracks(
            listOf(
                stat(yt, plays = 4, lastPlayed = 100L),
                stat(spotify, plays = 6, lastPlayed = 200L)
            )
        )

        assertEquals(1, ranked.size)
        assertEquals(10, ranked.first().playCount)
        assertEquals("spotify_1", ranked.first().track.id)
    }

    @Test
    fun `rank top tracks filters disliked and invalid durations`() {
        val good = track("t1", "Good", "Artist")
        val disliked = track("t2", "Hidden", "Artist")
        val broken = track("t3", "Broken", "Artist", durationMs = 0L)

        val ranked = rankTopTracks(
            stats = listOf(
                stat(good, 3),
                stat(disliked, 99),
                stat(broken, 50)
            ),
            dislikedTrackIds = setOf(disliked.id)
        )

        assertEquals(listOf("t1"), ranked.map { it.track.id })
    }

    @Test
    fun `rank top tracks orders by plays then recency`() {
        val a = track("a", "A", "X")
        val b = track("b", "B", "X")
        val c = track("c", "C", "X")
        val ranked = rankTopTracks(
            listOf(
                stat(a, 2, lastPlayed = 10L),
                stat(b, 5, lastPlayed = 1L),
                stat(c, 2, lastPlayed = 50L)
            )
        )

        assertEquals(listOf("b", "c", "a"), ranked.map { it.track.id })
    }

    @Test
    fun `rank playlists combines explicit plays with song affinity`() {
        val a = track("a", "A", "X")
        val b = track("b", "B", "X")
        val p1 = Playlist(id = "p1", name = "Explicit", tracks = listOf(a))
        val p2 = Playlist(id = "p2", name = "Affinity", tracks = listOf(b))
        val system = Playlist(id = MusicRepository.PLAYLIST_ON_REPEAT_ID, name = "On Repeat", tracks = listOf(a))

        val ranked = rankPlaylists(
            playlists = listOf(p1, p2, system),
            playlistStats = listOf(PlaylistPlayStat("p1", playCount = 3)),
            trackStats = listOf(stat(b, plays = 9)),
            excludedIds = setOf(MusicRepository.PLAYLIST_ON_REPEAT_ID)
        )

        assertEquals(listOf("p1", "p2"), ranked.map { it.playlist.id })
        assertTrue(ranked.none { it.playlist.id == MusicRepository.PLAYLIST_ON_REPEAT_ID })
    }

    @Test
    fun `shelf alternates tracks and playlists and respects the limit`() {
        val tracks = (1..4).map { RankedTrack(track("t$it", "T$it", "A"), it, 0L) }
        val playlists = (1..4).map {
            RankedPlaylist(Playlist(id = "p$it", name = "P$it", tracks = emptyList()), 10.0 - it, 0L)
        }

        val shelf = buildShelfEntries(tracks, playlists, limit = 6)
        assertEquals(6, shelf.size)
        assertTrue(shelf[0] is ShelfEntry.TrackEntry)
        assertTrue(shelf[1] is ShelfEntry.PlaylistEntry)
        assertTrue(shelf[2] is ShelfEntry.TrackEntry)
        assertTrue(shelf[3] is ShelfEntry.PlaylistEntry)
    }

    @Test
    fun `on repeat requires two plays and caps the list`() {
        val ranked = (1..40).map {
            RankedTrack(
                track = track("t$it", "T$it", "A"),
                playCount = if (it == 1) 1 else 2,
                lastPlayedAtEpochMs = 0L
            )
        }
        val selected = selectOnRepeatTracks(ranked)

        assertEquals(ON_REPEAT_MAX_TRACKS, selected.size)
        assertTrue(selected.none { it.id == "t1" })
    }

    @Test
    fun `on repeat refresh gate is five days`() {
        val now = 10L * ON_REPEAT_REFRESH_INTERVAL_MS
        assertTrue(shouldRefreshOnRepeat(0L, now))
        assertFalse(shouldRefreshOnRepeat(now - ON_REPEAT_REFRESH_INTERVAL_MS + 1L, now))
        assertTrue(shouldRefreshOnRepeat(now - ON_REPEAT_REFRESH_INTERVAL_MS, now))
    }
}
