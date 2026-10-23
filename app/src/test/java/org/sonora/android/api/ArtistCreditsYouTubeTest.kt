package org.sonora.android.api

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistCreditsYouTubeTest {

    private val client = YouTubeMusicClient()

    private fun artistRun(name: String, browseId: String): String =
        """{"text":"$name","navigationEndpoint":{"browseEndpoint":{"browseId":"$browseId","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}}}"""

    private fun albumRun(name: String, browseId: String): String =
        """{"text":"$name","navigationEndpoint":{"browseEndpoint":{"browseId":"$browseId","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}}}"""

    private fun separator(): String = """{"text":" • "}"""

    private fun trackItem(videoId: String, title: String, subtitleRuns: String): String =
        """{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"$videoId"},"flexColumns":[
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$title","navigationEndpoint":{"watchEndpoint":{"videoId":"$videoId"}}}]}}},
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[$subtitleRuns]}}}
        ]}}"""

    @Test
    fun secondArtistIsNotMistakenForAlbumAndCreditsKeepIds() {
        val runs = listOf(
            artistRun("Artist One", "UCone"),
            separator(),
            artistRun("Artist Two", "UCtwo"),
            separator(),
            albumRun("Real Album", "MPREb_real"),
            separator(),
            """{"text":"3:45"}"""
        ).joinToString(",")
        val json = """{"contents":[{"musicPlaylistShelfRenderer":{"contents":[
            ${trackItem("vidMulti", "Shared Song", runs)}
        ]}}]}"""

        val tracks = client.parseSearchTracks(JsonParser.parseString(json))

        assertEquals(1, tracks.size)
        val track = tracks[0]
        assertEquals("Shared Song", track.title)
        assertEquals("Artist One, Artist Two", track.artist)
        assertEquals("Real Album", track.album)
        assertEquals("MPREb_real", track.albumId)
        assertEquals("UCone", track.artistId)
        assertEquals(225_000L, track.durationMs)
        assertNotNull(track.artists)
        assertEquals(listOf("Artist One", "Artist Two"), track.artists!!.map { it.name })
        assertEquals("UCtwo", track.artists!![1].browseId)
    }

    @Test
    fun artistDerivedFromTitleStillGetsCredits() {
        val subtitle = listOf(
            """{"text":"Song"}""",
            separator(),
            """{"text":"YouTube Music"}""",
            separator(),
            """{"text":"3:00"}"""
        ).joinToString(",")
        val json = """{"contents":[{"musicPlaylistShelfRenderer":{"contents":[
            ${trackItem("vidDash", "Real Artist - Real Song", subtitle)}
        ]}}]}"""

        val tracks = client.parseSearchTracks(JsonParser.parseString(json))

        assertEquals(1, tracks.size)
        assertEquals("Real Artist", tracks[0].artist)
        assertEquals(listOf("Real Artist"), tracks[0].artists!!.map { it.name })
    }

    @Test
    fun songTopResultIsNotReturnedAsAnArtist() {
        val json = """
            {"contents":[{"musicCardShelfRenderer":{
              "title":{"runs":[{"text":"Song Title","navigationEndpoint":{"watchEndpoint":{"videoId":"vidSong"}}}]},
              "subtitle":{"runs":[{"text":"Song"},{"text":" • "},
                {"text":"Real Artist","navigationEndpoint":{"browseEndpoint":{"browseId":"UCreal","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}}}]}
            }}]}
        """.trimIndent()

        assertTrue(client.parseYouTubeArtistSearch(JsonParser.parseString(json), 20).isEmpty())
    }

    @Test
    fun artistSearchKeepsFilteredPagesAndContinuationFixture() {
        val row1 = """{"musicResponsiveListItemRenderer":{"flexColumns":[
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Artist One","navigationEndpoint":{"browseEndpoint":{"browseId":"UCone"}}}]}}},
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Artist"}]}}}
        ]}}"""
        val row2 = """{"musicResponsiveListItemRenderer":{"flexColumns":[
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Artist Two","navigationEndpoint":{"browseEndpoint":{"browseId":"UCtwo"}}}]}}},
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Artist"}]}}}
        ]}}"""
        val continuation = """{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"ARTIST_NEXT"}}}}"""
        val root = JsonParser.parseString("""{"contents":[{"musicShelfRenderer":{"contents":[$row1,$row2]}},$continuation]}""")

        val artists = client.parseYouTubeArtistSearch(root, 20)
        assertEquals(listOf("Artist One", "Artist Two"), artists.map { it.name })
        assertEquals("ARTIST_NEXT", client.findContinuationToken(root))

        val merged = client.mergeArtists(
            primary = artists,
            secondary = listOf(YouTubeArtist("UCone", "UCone", "Artist One", null)),
            limit = 20
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun dedupUsesAllArtistsAndDurationWithoutCollapsingFeatOrRemix() {
        val base = Track(
            id = "yt_a",
            title = "Shared Song",
            artist = "A, B",
            album = null,
            durationMs = 200_000L,
            coverUrl = null,
            artists = listOf(ArtistCredit("A"), ArtistCredit("B"))
        )
        val reversedArtists = base.copy(
            id = "yt_b",
            artist = "B, A",
            artists = listOf(ArtistCredit("B"), ArtistCredit("A"))
        )
        val feat = base.copy(
            id = "yt_c",
            title = "Shared Song (feat. C)",
            artist = "A, C",
            artists = listOf(ArtistCredit("A"), ArtistCredit("C"))
        )
        val remix = base.copy(
            id = "yt_d",
            title = "Shared Song (Club Remix)",
            artist = "A",
            artists = listOf(ArtistCredit("A"))
        )

        val merged = client.mergeSearchResults(
            general = listOf(base, reversedArtists, feat, remix),
            songs = emptyList(),
            limit = 10
        )

        assertEquals(3, merged.size)
        assertEquals(listOf("yt_a", "yt_c", "yt_d"), merged.map { it.id })
    }

    @Test
    fun withMetadataMergesCreditsWithoutLosingLegacyArtistAndId() {
        val track = Track(
            id = "yt_x",
            title = "Song",
            artist = "A",
            album = null,
            durationMs = 100_000L,
            coverUrl = null,
            artistId = "UCa",
            artists = listOf(ArtistCredit("A", browseId = "UCa"))
        )

        val merged = track.withMetadata(
            TrackMetadata(
                artists = listOf(
                    ArtistCredit("A", browseId = "UCa"),
                    ArtistCredit("B", channelId = "UCb", role = ArtistRole.FEATURED)
                )
            )
        )

        assertEquals("A", merged.artist)
        assertEquals("UCa", merged.artistId)
        assertEquals(listOf("A", "B"), merged.artists!!.map { it.name })
        assertEquals("UCb", merged.artists!![1].channelId)

        // Metadata that only fills facts must still keep the legacy artist.
        val factsOnly = track.withMetadata(TrackMetadata(releaseYear = 2020))
        assertEquals("A", factsOnly.artist)
        assertEquals("UCa", factsOnly.artistId)
        assertEquals(listOf("A"), factsOnly.artists!!.map { it.name })
    }

    @Test
    fun watchNextCollectsAllArtistsFromByline() {
        val byline = listOf(
            artistRun("Artist One", "UCone"),
            separator(),
            artistRun("Artist Two", "UCtwo")
        ).joinToString(",")
        val json = """{"contents":[{"playlistPanelRenderer":{"contents":[
            {"playlistPanelVideoRenderer":{
              "videoId":"vidRadio001",
              "title":{"runs":[{"text":"Radio Song"}]},
              "shortBylineText":{"runs":[$byline]},
              "lengthText":{"runs":[{"text":"3:12"}]}
            }}
        ]}}]}"""

        val tracks = client.parseWatchNextTracks(JsonParser.parseString(json), limit = 10)

        assertEquals(1, tracks.size)
        assertEquals("Artist One, Artist Two", tracks[0].artist)
        assertEquals("UCone", tracks[0].artistId)
        assertEquals(2, tracks[0].artists!!.size)
    }

    @Test
    fun radioWithoutBylineArtistIsKeptWhenTitleCarriesIt() {
        val json = """{"contents":[{"playlistPanelRenderer":{"contents":[
            {"playlistPanelVideoRenderer":{
              "videoId":"vidRadio002",
              "title":{"runs":[{"text":"Real Artist - Radio Song"}]},
              "shortBylineText":{"runs":[{"text":"YouTube Music"}]}
            }}
        ]}}]}"""

        val tracks = client.parseWatchNextTracks(JsonParser.parseString(json), limit = 10)

        assertEquals(1, tracks.size)
        assertEquals("Real Artist", tracks[0].artist)
        assertEquals(listOf("Real Artist"), tracks[0].artists!!.map { it.name })
    }

    @Test
    fun noArtistAndNoTitleHintIsStillRejected() {
        val subtitle = listOf(
            """{"text":"Song"}""",
            separator(),
            """{"text":"YouTube Music"}""",
            separator(),
            """{"text":"3:00"}"""
        ).joinToString(",")
        val json = """{"contents":[{"musicPlaylistShelfRenderer":{"contents":[
            ${trackItem("vidNobody", "Untitled Upload", subtitle)}
        ]}}]}"""

        assertTrue(client.parseSearchTracks(JsonParser.parseString(json)).isEmpty())
        assertNull(client.parseSearchTracks(JsonParser.parseString(json)).firstOrNull())
    }
}
