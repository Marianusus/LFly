package org.sonora.android.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sonora.android.api.Track
import org.sonora.android.data.FollowedArtist

class TasteProfileTest {

    private fun track(id: String, title: String, artist: String) = Track(
        id = "yt_$id",
        title = title,
        artist = artist,
        album = "Album",
        durationMs = 200_000L,
        coverUrl = null,
        provider = "YouTube"
    )

    private fun follow(browseId: String, name: String) = FollowedArtist(
        artistKey = "yt_browse_$browseId",
        browseId = browseId,
        channelId = browseId,
        name = name
    )

    @Test
    fun followedArtistsAreTopWeightedSeedsWithBrowseIdPreserved() {
        val profile = TasteProfile.build(
            followedArtists = listOf(follow("UC_abba", "ABBA")),
            history = listOf(track("h1", "Song", "ABBA"))
        )

        val top = profile.seedArtists.first()
        assertEquals("ABBA", top.name)
        assertEquals("UC_abba", top.browseId)
        assertTrue("Followed weight must exceed history-only weight", top.weight >= TasteProfile.FOLLOW_WEIGHT)
    }

    @Test
    fun topPlayedArtistsWeightScalesWithPlayCount() {
        val profile = TasteProfile.build(
            topPlayedArtists = listOf("Madonna" to 4, "Cher" to 1)
        )

        val madonna = profile.seedArtists.first { it.name == "Madonna" }
        val cher = profile.seedArtists.first { it.name == "Cher" }
        assertTrue("Higher play count must yield higher weight", madonna.weight > cher.weight)
        assertEquals("Weight must equal count * factor", 4 * TasteProfile.PLAY_COUNT_WEIGHT, madonna.weight, 0.0001)
    }

    @Test
    fun recentHistoryWeighsMoreThanOlderHistory() {
        val profile = TasteProfile.build(
            history = listOf(
                track("r1", "Recent", "New Artist"),
                track("r2", "Old", "Old Artist")
            )
        )

        val recent = profile.seedArtists.first { it.name == "New Artist" }
        val old = profile.seedArtists.first { it.name == "Old Artist" }
        assertTrue("Most recent artist must weigh more than the older one", recent.weight > old.weight)
        // Most recent is the first seed artist.
        assertEquals("New Artist", profile.seedArtists.first().name)
    }

    @Test
    fun dislikedTracksAndArtistsAreExcluded() {
        val profile = TasteProfile.build(
            followedArtists = listOf(follow("UC_bad", "Bad Artist")),
            history = listOf(
                track("h1", "Good", "Good Artist"),
                track("h2", "Bad", "Bad Artist"),
                track("h3", "Removed", "Bad Artist")
            ),
            dislikedTrackIds = setOf("yt_h3"),
            dislikedArtists = setOf("Bad Artist")
        )

        assertFalse("Disliked artist must be excluded from artist seeds", profile.seedArtists.any { it.name == "Bad Artist" })
        assertFalse("Disliked track must be excluded from track seeds", profile.seedTracks.any { it.id == "yt_h3" })
        assertTrue("Non-disliked artist remains", profile.seedArtists.any { it.name == "Good Artist" })
    }

    @Test
    fun seedTracksPreferTopPlayedThenHistoryWithoutDuplicates() {
        val played = track("p1", "Played", "Artist P")
        val history = track("h1", "History", "Artist H")

        val profile = TasteProfile.build(
            history = listOf(history),
            topPlayedTracks = listOf(played to 5, history to 2)
        )

        assertEquals("Top played track must lead the seed tracks", "yt_p1", profile.seedTracks.first().id)
        assertEquals("No duplicate seed tracks", profile.seedTracks.map { it.id }.distinct().size, profile.seedTracks.size)
    }

    @Test
    fun emptySignalsProduceEmptyProfile() {
        val profile = TasteProfile.build()
        assertTrue(profile.isEmpty)
    }
}
