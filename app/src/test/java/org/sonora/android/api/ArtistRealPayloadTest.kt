package org.sonora.android.api

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parses the real InnerTube artist payload captured from YouTube Music (INNA).
 * Guards against regressions: separated sections, no fake durations, all items kept.
 */
class ArtistRealPayloadTest {

    private val client = YouTubeMusicClient()

    private fun realRoot() = JsonParser.parseString(
        ArtistRealPayloadTest::class.java.classLoader!!
            .getResource("inna_artist.json")!!
            .readText()
    )

    @Test
    fun realPayloadParsesSeparatedSections() {
        val detail = client.parseYouTubeArtistDetail(realRoot())!!
        val summary = "name=${detail.artist.name} albums=${detail.albums.size} " +
            "singles=${detail.singlesOrEps.size} playlists=${detail.playlists.size} tracks=${detail.topTracks.size} " +
            "dur0=${detail.topTracks.firstOrNull()?.durationMs}"
        assertEquals(summary, "INNA", detail.artist.name)
        // Real carousels contain 10 items each (never capped to 3/4).
        assertEquals(summary, 10, detail.albums.size)
        assertEquals(summary, 10, detail.singlesOrEps.size)
        assertTrue(summary, detail.playlists.size >= 10)
        // "Fans might also like" (UC channels) must NOT leak into playlists.
        assertTrue(detail.playlists.all { it.detailId != null })
    }

    @Test
    fun realArtistCarriesRealAudienceTextAndVideos() {
        val detail = client.parseYouTubeArtistDetail(realRoot())!!
        val subtitle = detail.artist.subtitle.orEmpty()
        assertTrue(
            "audience text must come from the payload, not be invented",
            subtitle.contains("monthly audience", ignoreCase = true) ||
                subtitle.contains("iscritti", ignoreCase = true)
        )
        assertEquals(10, detail.videos.size)
    }

    @Test
    fun realArtistExposesAllSongsEndpoint() {
        val detail = client.parseYouTubeArtistDetail(realRoot())!!
        assertTrue(detail.hasAllSongs)
        assertTrue(detail.songsBrowseId!!.startsWith("VL"))
        assertEquals("ggMCCAI%3D", detail.songsBrowseParams)
    }

    @Test
    fun songsPageParsesTracksAndContinuation() {
        val items = (1..6).joinToString(",") { i ->
            """{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"vidS%08d"},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song $i"},{"text":" • "},{"text":"3:0$i"}]}}}]}}""".format(i)
        }
        val cont = """{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"TOKEN_NEXT"}}}}"""
        val valid = """{"contents":[{"musicPlaylistShelfRenderer":{"contents":[$items]}},$cont]}"""
        val page = client.parseYouTubeSongsPage(com.google.gson.JsonParser.parseString(valid))
        assertEquals(6, page.tracks.size)
        assertEquals("TOKEN_NEXT", page.continuation)
        // durations are real (3:01..), never fabricated
        assertTrue(page.tracks.all { it.durationMs > 0L })
    }

    @Test
    fun realTopTracksKeepAllFiveAndNoFakeDuration() {
        val detail = client.parseYouTubeArtistDetail(realRoot())!!
        assertEquals(5, detail.topTracks.size)
        // Artist "Top songs" shelf has no duration → must be 0 (UI renders "—"), never 180000.
        assertTrue(detail.topTracks.all { it.durationMs == 0L })
        assertEquals("—", detail.topTracks[0].formattedDuration)
    }
}
