package org.sonora.android.api

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeDiscoveryParserTest {

    private val client = YouTubeMusicClient()

    // ---------- Fixture builders ----------

    private fun artistSearchItem(
        name: String,
        browseId: String?,
        channelId: String? = null,
        subtitle: String = "Artist"
    ): String {
        val canonical = if (channelId != null) ",\"canonicalBaseUrl\":\"/channel/$channelId\"" else ""
        val nav = if (browseId != null) {
            ",\"navigationEndpoint\":{\"browseEndpoint\":{\"browseId\":\"$browseId\"$canonical}}"
        } else {
            ""
        }
        return """{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$name"$nav}]}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$subtitle"}]}}}],"thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img.example/t=w120-h120"}]}}}}}"""
    }

    private fun albumSearchItem(title: String, browseId: String?, subtitle: String): String {
        val nav = if (browseId != null) {
            ",\"navigationEndpoint\":{\"browseEndpoint\":{\"browseId\":\"$browseId\"}}"
        } else {
            ""
        }
        return """{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$title"$nav}]}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$subtitle"}]}}}],"thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img.example/a"}]}}}}}"""
    }

    private fun playlistSearchItem(
        title: String,
        playlistId: String?,
        browseId: String?,
        subtitle: String
    ): String {
        val navParts = mutableListOf<String>()
        if (playlistId != null) navParts.add("\"watchEndpoint\":{\"playlistId\":\"$playlistId\"}")
        if (browseId != null) navParts.add("\"browseEndpoint\":{\"browseId\":\"$browseId\"}")
        val nav = if (navParts.isNotEmpty()) {
            ",\"navigationEndpoint\":{${navParts.joinToString(",")}}"
        } else {
            ""
        }
        return """{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$title"$nav}]}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$subtitle"}]}}}],"thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img.example/p"}]}}}}}"""
    }

    private fun trackItem(videoId: String, title: String = "Song Title", artist: String = "Adele"): String =
        """{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"$videoId"},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$title","navigationEndpoint":{"watchEndpoint":{"videoId":"$videoId"}}}]}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song"},{"text":" • "},{"text":"$artist"},{"text":" • "},{"text":"3:45"}]}}}],"thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img.example/t"}]}}}}}"""

    private fun wrap(vararg items: String): JsonElementHolder {
        val json = "{\"root\":[${items.joinToString(",")}]}"
        return JsonElementHolder(JsonParser.parseString(json))
    }

    private class JsonElementHolder(val element: com.google.gson.JsonElement)

    /** Item whose stable id + pageType live at the renderer level (real YT Music layout). */
    private fun itemLevelEntity(
        title: String,
        subtitle: String,
        browseId: String?,
        pageType: String?,
        watchPlaylistId: String? = null
    ): String {
        val browse = if (browseId != null) {
            val ctx = if (pageType != null) {
                ",\"browseEndpointContextSupportedConfigs\":{\"browseEndpointContextMusicConfig\":{\"pageType\":\"$pageType\"}}"
            } else {
                ""
            }
            "\"browseEndpoint\":{\"browseId\":\"$browseId\"$ctx}"
        } else {
            ""
        }
        val watch = if (watchPlaylistId != null) "\"watchEndpoint\":{\"playlistId\":\"$watchPlaylistId\"}" else ""
        val navParts = listOf(browse, watch).filter { it.isNotBlank() }.joinToString(",")
        val nav = if (navParts.isNotBlank()) ",\"navigationEndpoint\":{$navParts}" else ""
        return """{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$title"}]}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$subtitle"}]}}}],"thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img.example/x"}]}}}$nav}}"""
    }

    /** Top-result card whose title run carries the browseId + pageType (real YT Music layout). */
    private fun cardShelf(title: String, subtitle: String, browseId: String, pageType: String): String =
        """{"musicCardShelfRenderer":{"title":{"runs":[{"text":"$title","navigationEndpoint":{"browseEndpoint":{"browseId":"$browseId","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"$pageType"}}}}}]},"subtitle":{"runs":[{"text":"$subtitle"}]},"thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img.example/top"}]}}}}}"""

    // ---------- Search classification ----------

    @Test
    fun searchClassifiesArtistAlbumPlaylistByBrowseId() {
        val root = wrap(
            artistSearchItem("Adele", "UC123", channelId = "UC123"),
            albumSearchItem("21", "MPREb_21", "Album • Adele • 2011"),
            playlistSearchItem("Best Hits", "PLtest123", null, "Playlist • Some Author • 30 tracks")
        ).element

        val artists = client.parseYouTubeArtistSearch(root, 20)
        assertEquals(1, artists.size)
        assertEquals("Adele", artists[0].name)
        assertEquals("UC123", artists[0].browseId)
        assertEquals("UC123", artists[0].channelId)
        assertTrue(artists[0].navigable)

        val albums = client.parseYouTubeAlbumSearch(root, 20)
        assertEquals(1, albums.size)
        assertEquals("21", albums[0].title)
        assertEquals("Adele", albums[0].artist)
        assertEquals("2011", albums[0].year)
        assertEquals("MPREb_21", albums[0].browseId)

        val playlists = client.parseYouTubePlaylistSearch(root, 20)
        assertEquals(1, playlists.size)
        assertEquals("Best Hits", playlists[0].title)
        assertEquals("Some Author", playlists[0].author)
        assertEquals(30, playlists[0].trackCount)
        assertEquals("PLtest123", playlists[0].playlistId)
    }

    @Test
    fun playlistSearchStripsVlPrefixFromBrowseId() {
        val root = wrap(
            playlistSearchItem("Mix", null, "VLPLabc", "Playlist")
        ).element
        val playlists = client.parseYouTubePlaylistSearch(root, 20)
        assertEquals(1, playlists.size)
        assertEquals("PLabc", playlists[0].playlistId)
        assertEquals("VLPLabc", playlists[0].browseId)
    }

    @Test
    fun searchFallsBackToSubtitleClassificationWhenIdUnknown() {
        val root = wrap(
            artistSearchItem("Mystery", null, subtitle = "Artist"),
            albumSearchItem("Unknown Album", null, "Album • 2020"),
            playlistSearchItem("Unknown List", null, null, "Playlist • Someone")
        ).element
        assertEquals(1, client.parseYouTubeArtistSearch(root, 20).size)
        assertEquals(1, client.parseYouTubeAlbumSearch(root, 20).size)
        assertEquals(1, client.parseYouTubePlaylistSearch(root, 20).size)
    }

    @Test
    fun searchSkipsIncompleteAndKeepsNoIdEntryNonNavigable() {
        val missingFlex = """{"musicResponsiveListItemRenderer":{"thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img/x"}]}}}}}"""
        val noId = artistSearchItem("No Id Artist", null, subtitle = "Artist")
        val root = wrap(missingFlex, noId).element

        val artists = client.parseYouTubeArtistSearch(root, 20)
        assertEquals(1, artists.size)
        assertEquals("No Id Artist", artists[0].name)
        assertNull(artists[0].browseId)
        assertNull(artists[0].channelId)
        assertFalse(artists[0].navigable)
    }

    @Test
    fun searchRespectsLimit() {
        val root = wrap(
            artistSearchItem("A", "UC_a"),
            artistSearchItem("B", "UC_b"),
            artistSearchItem("C", "UC_c")
        ).element
        assertEquals(2, client.parseYouTubeArtistSearch(root, 2).size)
        assertEquals(0, client.parseYouTubeArtistSearch(root, 0).size)
    }

    @Test
    fun searchDedupsByIdAndByNormalizedName() {
        val byId = wrap(
            artistSearchItem("Adele", "UC123"),
            artistSearchItem("Adele", "UC123")
        ).element
        assertEquals(1, client.parseYouTubeArtistSearch(byId, 20).size)

        val byName = wrap(
            artistSearchItem("Daft Punk", null, subtitle = "Artist"),
            artistSearchItem("daft  punk", null, subtitle = "Artist")
        ).element
        assertEquals(1, client.parseYouTubeArtistSearch(byName, 20).size)
    }

    @Test
    fun artistSearchReadsTopResultCardShelfFirst() {
        val root = wrap(
            itemLevelEntity("Other Artist", "Artist • 1M monthly audience", "UCother", "MUSIC_PAGE_TYPE_ARTIST"),
            cardShelf("INNA", "Artist • 67.1M monthly audience", "UCinna", "MUSIC_PAGE_TYPE_ARTIST")
        ).element

        val artists = client.parseYouTubeArtistSearch(root, 20)
        assertEquals(2, artists.size)
        assertEquals("INNA", artists[0].name)
        assertEquals("UCinna", artists[0].browseId)
        assertTrue(artists[0].navigable)
    }

    @Test
    fun artistTopResultDedupsAgainstDuplicate() {
        val root = wrap(
            cardShelf("INNA", "Artist • 67.1M monthly audience", "UCinna", "MUSIC_PAGE_TYPE_ARTIST"),
            itemLevelEntity("INNA", "Artist • 67.1M monthly audience", "UCother", "MUSIC_PAGE_TYPE_ARTIST")
        ).element

        val artists = client.parseYouTubeArtistSearch(root, 20)
        assertEquals(1, artists.size)
        assertEquals("UCinna", artists[0].browseId)
    }

    @Test
    fun albumSearchReadsItemLevelBrowseIdAsNavigable() {
        val root = wrap(
            itemLevelEntity("Hot", "Album • INNA • 2025", "MPREb_Hot", "MUSIC_PAGE_TYPE_ALBUM")
        ).element

        val albums = client.parseYouTubeAlbumSearch(root, 20)
        assertEquals(1, albums.size)
        assertEquals("MPREb_Hot", albums[0].browseId)
        assertEquals("MPREb_Hot", albums[0].detailId)
        assertTrue(albums[0].navigable)
    }

    @Test
    fun playlistSearchReadsItemLevelPlaylistIdAsNavigable() {
        val withBrowse = wrap(
            itemLevelEntity("Presenting Inna", "Playlist • YouTube Music • 45 songs", "VLRDCLAK5uy_x", "MUSIC_PAGE_TYPE_PLAYLIST")
        ).element
        val fromBrowse = client.parseYouTubePlaylistSearch(withBrowse, 20)
        assertEquals(1, fromBrowse.size)
        assertEquals("RDCLAK5uy_x", fromBrowse[0].playlistId)
        assertTrue(fromBrowse[0].navigable)

        val withWatch = wrap(
            itemLevelEntity("Mix", "Playlist • Someone", null, null, watchPlaylistId = "PLitemLevel")
        ).element
        val fromWatch = client.parseYouTubePlaylistSearch(withWatch, 20)
        assertEquals(1, fromWatch.size)
        assertEquals("PLitemLevel", fromWatch[0].playlistId)
        assertTrue(fromWatch[0].navigable)
    }

    @Test
    fun entitiesWithoutIdAreVisibleButNonNavigable() {
        val root = wrap(
            itemLevelEntity("NoId Album", "Album • Someone • 2020", null, null),
            itemLevelEntity("NoId List", "Playlist • Someone", null, null)
        ).element

        val albums = client.parseYouTubeAlbumSearch(root, 20)
        assertEquals(1, albums.size)
        assertEquals("NoId Album", albums[0].title)
        assertNull(albums[0].detailId)
        assertFalse(albums[0].navigable)

        val playlists = client.parseYouTubePlaylistSearch(root, 20)
        assertEquals(1, playlists.size)
        assertEquals("NoId List", playlists[0].title)
        assertNull(playlists[0].detailId)
        assertFalse(playlists[0].navigable)
    }

    @Test
    fun unsupportedPageTypesAreNotMisclassified() {
        val root = wrap(
            itemLevelEntity("Some Profile", "Profile • @handle", "UCprofile", "MUSIC_PAGE_TYPE_USER_CHANNEL"),
            itemLevelEntity("A Show", "Podcast", "MPSPPLxxx", "MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE")
        ).element

        assertEquals(0, client.parseYouTubeArtistSearch(root, 20).size)
        assertEquals(0, client.parseYouTubeAlbumSearch(root, 20).size)
        assertEquals(0, client.parseYouTubePlaylistSearch(root, 20).size)
    }

    // ---------- Artist detail ----------

    @Test
    fun artistDetailReturnsNameTopTracksAndAlbums() {
        val header = """{"musicImmersiveHeaderRenderer":{"title":{"runs":[{"text":"Adele"}]},"thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img.example/adele"}]}}},"buttons":[{"musicButtonRenderer":{"navigationEndpoint":{"browseEndpoint":{"browseId":"UCadele","canonicalBaseUrl":"/channel/UCadele"}}}}]}}"""
        val songShelf = """{"musicShelfRenderer":{"title":{"runs":[{"text":"Songs"}]},"contents":[${trackItem("vid00000001")}]}}"""
        val albumCarousel = """{"musicCarouselShelfRenderer":{"header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Albums"}]}}},"contents":[{"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"30"}]},"subtitle":{"runs":[{"text":"2021"}]},"thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img.example/30"}]}}},"navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_30"}}}}]}}"""
        val json = "{\"header\":$header,\"contents\":[$songShelf,$albumCarousel]}"

        val detail = client.parseYouTubeArtistDetail(JsonParser.parseString(json))
        assertTrue(detail != null)
        assertEquals("Adele", detail!!.artist.name)
        assertEquals("UCadele", detail.artist.browseId)
        assertTrue(detail.artist.navigable)
        assertEquals(1, detail.topTracks.size)
        assertTrue(detail.topTracks[0].id.startsWith("yt_"))
        assertEquals("YouTube", detail.topTracks[0].provider)
        assertEquals(1, detail.albums.size)
        assertEquals("30", detail.albums[0].title)
        assertEquals("2021", detail.albums[0].year)
        assertEquals("MPREb_30", detail.albums[0].browseId)
    }

    @Test
    fun artistDetailReturnsNullWhenHeaderNameBlank() {
        val header = """{"musicImmersiveHeaderRenderer":{"title":{"runs":[{"text":""}]}}}"""
        assertNull(client.parseYouTubeArtistDetail(JsonParser.parseString("{\"header\":$header}")))
        assertNull(client.parseYouTubeArtistDetail(JsonParser.parseString("{}")))
    }

    @Test
    fun artistDetailReturnsManyAlbumsAndPlaylists() {
        val header = """{"musicImmersiveHeaderRenderer":{"title":{"runs":[{"text":"INNA"}]},"buttons":[{"musicButtonRenderer":{"navigationEndpoint":{"browseEndpoint":{"browseId":"UCinna"}}}}]}}"""
        val albums = (1..8).joinToString(",") { i ->
            """{"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Album $i"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_$i"}}}}"""
        }
        val playlists = (1..7).joinToString(",") { i ->
            """{"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Playlist $i"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"VLPL$i"}}}}"""
        }
        val albumCarousel = """{"musicCarouselShelfRenderer":{"header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Albums"}]}}},"contents":[$albums]}}"""
        val playlistCarousel = """{"musicCarouselShelfRenderer":{"header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Playlists by INNA"}]}}},"contents":[$playlists]}}"""
        val json = "{\"header\":$header,\"contents\":[$albumCarousel,$playlistCarousel]}"

        val detail = client.parseYouTubeArtistDetail(JsonParser.parseString(json))!!
        assertEquals(8, detail.albums.size)
        assertEquals(7, detail.playlists.size)
        assertTrue(detail.albums.all { it.navigable })
        assertTrue(detail.playlists.all { it.navigable })
        assertEquals("PL1", detail.playlists[0].detailId)
    }

    @Test
    fun artistDetailSeparatesAlbumsSinglesPlaylistsAndVideos() {
        val header = """{"musicImmersiveHeaderRenderer":{"title":{"runs":[{"text":"INNA"}]}}}"""
        val albums = (1..10).joinToString(",") { i ->
            """{"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Album $i"}]},"subtitle":{"runs":[{"text":"Album"},{"text":" • "},{"text":"202$"},{"text":"4"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_A$i"}}}}"""
        }
        val singles = (1..10).joinToString(",") { i ->
            """{"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Single $i"}]},"subtitle":{"runs":[{"text":"Single"},{"text":" • "},{"text":"2024"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_S$i"}}}}"""
        }
        val playlists = (1..10).joinToString(",") { i ->
            """{"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Playlist $i"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"VLPLP$i"}}}}"""
        }
        val videos = (1..8).joinToString(",") { i ->
            """{"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Video $i"}]},"subtitle":{"runs":[{"text":"INNA"}]},"navigationEndpoint":{"watchEndpoint":{"videoId":"vidV%08d"}}}}""".format(i)
        }
        val albumC = """{"musicCarouselShelfRenderer":{"header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Albums"}]}}},"contents":[$albums]}}"""
        val singleC = """{"musicCarouselShelfRenderer":{"header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Singles & EPs"}]}}},"contents":[$singles]}}"""
        val playlistC = """{"musicCarouselShelfRenderer":{"header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Playlists by INNA"}]}}},"contents":[$playlists]}}"""
        val videoC = """{"musicCarouselShelfRenderer":{"header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Videos"}]}}},"contents":[$videos]}}"""
        val json = "{\"header\":$header,\"contents\":[$albumC,$singleC,$playlistC,$videoC]}"

        val detail = client.parseYouTubeArtistDetail(JsonParser.parseString(json))!!
        assertEquals(10, detail.albums.size)
        assertEquals(10, detail.singlesOrEps.size)
        assertEquals(10, detail.playlists.size)
        assertEquals(8, detail.videos.size)
        assertTrue(detail.albums.all { it.title.startsWith("Album") })
        assertTrue(detail.singlesOrEps.all { it.title.startsWith("Single") })
        assertTrue(detail.videos.all { it.id.startsWith("yt_") })
    }

    // ---------- Duration extraction ----------

    private fun durationItem(flexSubtitle: String? = null, fixedColumn: String? = null, lengthText: String? = null): String {
        val flex = if (flexSubtitle != null)
            """[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Title"},{"text":" • "},{"text":"$flexSubtitle"}]}}}]"""
        else """[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Title"}]}}}]"""
        val fixed = if (fixedColumn != null)
            ""","fixedColumns":[{"musicResponsiveListItemFixedColumnRenderer":{"text":{"runs":[{"text":"$fixedColumn"}]}}}]"""
        else ""
        val length = if (lengthText != null) ""","lengthText":{"simpleText":"$lengthText"}""" else ""
        return """{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"vidD0000001"},"flexColumns":$flex$fixed$length}}"""
    }

    private fun topTracksOf(vararg items: String): List<Track> {
        val header = """{"musicImmersiveHeaderRenderer":{"title":{"runs":[{"text":"X"}]}}}"""
        val shelf = """{"musicShelfRenderer":{"title":{"runs":[{"text":"Songs"}]},"contents":[${items.joinToString(",")}]}}"""
        return client.parseYouTubeArtistDetail(JsonParser.parseString("{\"header\":$header,\"contents\":[$shelf]}"))!!.topTracks
    }

    @Test
    fun durationFromFlexColumn() {
        val t = topTracksOf(durationItem(flexSubtitle = "3:47"))
        assertEquals(227_000L, t[0].durationMs)
    }

    @Test
    fun durationFromFixedColumnHoursFormat() {
        val t = topTracksOf(durationItem(fixedColumn = "1:02:15"))
        assertEquals(3_735_000L, t[0].durationMs)
    }

    @Test
    fun durationFromLengthText() {
        val t = topTracksOf(durationItem(lengthText = "2:05"))
        assertEquals(125_000L, t[0].durationMs)
    }

    @Test
    fun durationUnknownIsZeroNotNullFabricated() {
        val t = topTracksOf(durationItem())
        assertEquals(0L, t[0].durationMs)
        assertEquals("—", t[0].formattedDuration)
    }

    // ---------- Album metadata ----------

    @Test
    fun albumDetailParsesTypeYearAndTrackCount() {
        val header = """{"musicResponsiveHeaderRenderer":{"title":{"runs":[{"text":"X"}]},"subtitle":{"runs":[{"text":"Album"},{"text":" • "},{"text":"2024"}]},"secondSubtitle":{"runs":[{"text":"12 songs"},{"text":" • "},{"text":"45 minutes"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_X"}}}}"""
        val shelf = """{"musicShelfRenderer":{"contents":[${trackItem("vidA")}]}}"""
        val detail = client.parseYouTubeAlbumDetail(JsonParser.parseString("{\"header\":$header,\"contents\":[$shelf]}"))!!
        assertEquals("Album", detail.album.type)
        assertEquals(2024, detail.album.releaseYear)
        assertEquals(12, detail.album.trackCount)
    }

    @Test
    fun albumDetailMetadataNullWhenAbsentNeverFabricated() {
        val header = """{"musicResponsiveHeaderRenderer":{"title":{"runs":[{"text":"Y"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_Y"}}}}"""
        val detail = client.parseYouTubeAlbumDetail(JsonParser.parseString("{\"header\":$header}"))!!
        assertNull(detail.album.type)
        assertNull(detail.album.releaseYear)
        assertNull(detail.album.releaseDate)
        assertNull(detail.album.trackCount)
    }

    @Test
    fun searchArtistsReturnsAllDeduplicatedWithTopResultFirst() {
        val top = """{"musicCardShelfRenderer":{"title":{"runs":[{"text":"INNA"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"UCinna"}}}}"""
        val items = (1..12).joinToString(",") { i ->
            """{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Artist $i","navigationEndpoint":{"browseEndpoint":{"browseId":"UCa$i"}}}]}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Artist"}]}}}]}}"""
        }
        val json = "{\"contents\":[$top,$items]}"
        val result = client.parseYouTubeArtistSearch(JsonParser.parseString(json), limit = 30)
        assertEquals(13, result.size)
        assertEquals("INNA", result[0].name)
    }

    @Test
    fun artistTopTracksAreNotTruncated() {
        val header = """{"musicImmersiveHeaderRenderer":{"title":{"runs":[{"text":"X"}]}}}"""
        val items = (1..9).joinToString(",") { i -> trackItem("vid%08d".format(i), "Song $i") }
        val songShelf = """{"musicShelfRenderer":{"title":{"runs":[{"text":"Songs"}]},"contents":[$items]}}"""
        val json = "{\"header\":$header,\"contents\":[$songShelf]}"

        val detail = client.parseYouTubeArtistDetail(JsonParser.parseString(json))!!
        assertEquals(9, detail.topTracks.size)
    }

    // ---------- Album detail ----------

    @Test
    fun albumDetailReturnsTitleWithYtTracks() {
        val header = """{"musicDetailHeaderRenderer":{"title":{"runs":[{"text":"21"}]},"subtitle":{"runs":[{"text":"Album"},{"text":" • "},{"text":"Adele"},{"text":" • "},{"text":"2011"}]},"thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img.example/21"}]}}},"navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_21"}}}}"""
        val shelf = """{"musicShelfRenderer":{"contents":[${trackItem("vidA")},${trackItem("vidB")}]}}"""
        val json = "{\"header\":$header,\"contents\":[$shelf]}"

        val detail = client.parseYouTubeAlbumDetail(JsonParser.parseString(json))
        assertTrue(detail != null)
        assertEquals("21", detail!!.album.title)
        assertEquals("Adele", detail.album.artist)
        assertEquals("2011", detail.album.year)
        assertEquals("MPREb_21", detail.album.browseId)
        assertEquals(2, detail.tracks.size)
        detail.tracks.forEach { track ->
            assertTrue(track.id.startsWith("yt_"))
            assertEquals("YouTube", track.provider)
            assertNull(track.streamUrl)
        }
    }

    @Test
    fun albumDetailReturnsNullWhenTitleBlank() {
        val header = """{"musicDetailHeaderRenderer":{"title":{"runs":[{"text":" "}]}}}"""
        assertNull(client.parseYouTubeAlbumDetail(JsonParser.parseString("{\"root\":$header}")))
    }

    // ---------- Playlist detail ----------

    @Test
    fun playlistDetailReturnsTracksWithInvariants() {
        val header = """{"musicDetailHeaderRenderer":{"title":{"runs":[{"text":"Best Hits"}]},"subtitle":{"runs":[{"text":"Playlist"},{"text":" • "},{"text":"Some Author"}]},"thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img.example/p"}]}}}}}"""
        val playlistShelf = """{"musicPlaylistShelfRenderer":{"contents":[${trackItem("vid1")},${trackItem("vid2")}]}}"""
        val json = "{\"header\":$header,\"contents\":[$playlistShelf],\"playlistId\":\"PLdetail123\"}"

        val detail = client.parseYouTubePlaylistDetail(JsonParser.parseString(json))
        assertTrue(detail != null)
        assertEquals("Best Hits", detail!!.playlist.title)
        assertEquals("Some Author", detail.playlist.author)
        assertEquals("PLdetail123", detail.playlist.playlistId)
        assertEquals(2, detail.tracks.size)
        detail.tracks.forEach { track ->
            assertTrue(track.id.startsWith("yt_"))
            assertEquals("YouTube", track.provider)
            assertNull(track.streamUrl)
        }
    }

    @Test
    fun playlistDetailReturnsNullWhenTitleBlank() {
        val header = """{"musicEditablePlaylistDetailHeaderRenderer":{"header":{"musicDetailHeaderRenderer":{"title":{"runs":[{"text":""}]}}}}}"""
        assertNull(client.parseYouTubePlaylistDetail(JsonParser.parseString("{\"root\":$header}")))
    }

    @Test
    fun albumDetailParsesResponsiveHeaderRenderer() {
        val header = """{"musicResponsiveHeaderRenderer":{"title":{"runs":[{"text":"Ruleta"}]},"subtitle":{"runs":[{"text":"Single"},{"text":" • "},{"text":"2017"}]},"straplineTextOne":{"runs":[{"text":"Inna"}]},"thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img/x"}]}}}}}"""
        val shelf = """{"musicShelfRenderer":{"contents":[${trackItem("vidR")}]}}"""
        val json = "{\"header\":$header,\"contents\":[$shelf]}"

        val detail = client.parseYouTubeAlbumDetail(JsonParser.parseString(json))
        assertTrue(detail != null)
        assertEquals("Ruleta", detail!!.album.title)
        assertEquals("Inna", detail.album.artist)
        assertEquals("2017", detail.album.year)
        assertEquals(1, detail.tracks.size)
        assertTrue(detail.tracks[0].id.startsWith("yt_"))
        assertEquals("YouTube", detail.tracks[0].provider)
        assertNull(detail.tracks[0].streamUrl)
    }

    @Test
    fun playlistDetailParsesResponsiveHeaderRenderer() {
        val header = """{"musicResponsiveHeaderRenderer":{"title":{"runs":[{"text":"INNA CANTINA"}]},"subtitle":{"runs":[{"text":"Playlist"},{"text":" • "},{"text":"2026"}]},"thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img/p"}]}}}}}"""
        val shelf = """{"musicPlaylistShelfRenderer":{"contents":[${trackItem("vidC1")},${trackItem("vidC2")}]}}"""
        val json = "{\"header\":$header,\"contents\":[$shelf],\"playlistId\":\"PLcantina\"}"

        val detail = client.parseYouTubePlaylistDetail(JsonParser.parseString(json))
        assertTrue(detail != null)
        assertEquals("INNA CANTINA", detail!!.playlist.title)
        assertEquals(2, detail.tracks.size)
        detail.tracks.forEach { track ->
            assertTrue(track.id.startsWith("yt_"))
            assertEquals("YouTube", track.provider)
            assertNull(track.streamUrl)
        }
    }

    // ---------- Cache ----------

    @Test
    fun cachePutGetExpiryAndClear() {
        var now = 0L
        val cache = YouTubeDiscoveryCache<String>(ttlMs = 1000L, clock = { now })

        assertNull(cache.get("k"))
        cache.put("k", "v")
        assertEquals("v", cache.get("k"))

        now = 999L
        assertEquals("v", cache.get("k"))

        now = 1000L
        assertNull(cache.get("k"))

        cache.put("a", "1")
        cache.put("b", "2")
        cache.clear()
        assertNull(cache.get("a"))
        assertNull(cache.get("b"))
    }
}
