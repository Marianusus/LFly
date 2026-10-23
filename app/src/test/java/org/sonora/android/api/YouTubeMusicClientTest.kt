package org.sonora.android.api

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeMusicClientTest {

    @Test
    fun testSearchTracksParsingLiveOrFallback() = runBlocking {
        val client = YouTubeMusicClient()
        val result = client.searchTracks("panzerkraken", limit = 10)
        assertTrue(result.isSuccess)
        val tracks = result.getOrNull()
        assertNotNull(tracks)
        // Ensure at least one track returned for panzerkraken
        assertTrue("YouTube Music search should find panzerkraken tracks", tracks!!.isNotEmpty())
        val firstTrack = tracks.first()
        assertTrue("Track ID should have yt_ prefix", firstTrack.id.startsWith("yt_"))
        assertEquals("YouTube", firstTrack.provider)
        assertTrue("Title should not be blank", firstTrack.title.isNotBlank())
        // General search keeps video-only entries whose duration is legitimately unknown (0L),
        // so require real durations to be present somewhere in the page instead of on track #1.
        assertTrue("At least one track should carry a real duration", tracks!!.any { it.durationMs > 0 })
    }

    @Test
    fun testSelectBestAudioFormatPrefersHighestBitrateAndDiscardsPreview() {
        val json = """
        {
          "streamingData": {
            "adaptiveFormats": [
              {"mimeType":"audio/webm; codecs=\"opus\"","bitrate":128000,"url":"https://x.googlevideo.com/videoplayback?itag=251&dur=180"},
              {"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":256000,"url":"https://x.googlevideo.com/videoplayback?itag=140&dur=180"},
              {"mimeType":"audio/webm; codecs=\"opus\"","bitrate":320000,"url":"https://x.googlevideo.com/videoplayback?itag=999&dur=30"}
            ]
          }
        }
        """.trimIndent()

        val selected = YouTubeMusicClient.selectBestAudioFormat(json)
        assertNotNull(selected)
        assertEquals(256000, selected!!.bitrate)
        assertTrue(selected.mimeType.startsWith("audio/mp4"))
        assertFalse("Preview URL must be discarded", selected.url.contains("dur=30"))
    }

    @Test
    fun testSelectBestAudioFormatDiscardsApproxDurationPreview() {
        val json = """
        {
          "streamingData": {
            "adaptiveFormats": [
              {"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":256000,"approxDurationMs":30000,"url":"https://x.googlevideo.com/videoplayback?itag=140"},
              {"mimeType":"audio/webm; codecs=\"opus\"","bitrate":160000,"approxDurationMs":210000,"url":"https://x.googlevideo.com/videoplayback?itag=251"}
            ]
          }
        }
        """.trimIndent()

        val selected = YouTubeMusicClient.selectBestAudioFormat(json)
        assertNotNull(selected)
        assertEquals(160000, selected!!.bitrate)
    }

    @Test
    fun testSelectBestAudioFormatReturnsNullWhenOnlyPreviews() {
        val json = """
        {
          "streamingData": {
            "adaptiveFormats": [
              {"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":128000,"url":"https://x.googlevideo.com/videoplayback?dur=15"},
              {"mimeType":"audio/webm; codecs=\"opus\"","bitrate":96000,"url":"https://x.googlevideo.com/videoplayback?dur=30"}
            ]
          }
        }
        """.trimIndent()

        org.junit.Assert.assertNull(YouTubeMusicClient.selectBestAudioFormat(json))
    }

    @Test
    fun testSelectBestAudioFormatReturnsNullWithoutStreamingData() {
        org.junit.Assert.assertNull(YouTubeMusicClient.selectBestAudioFormat("{}"))
        org.junit.Assert.assertNull(YouTubeMusicClient.selectBestAudioFormat("not-json"))
    }

    @Test
    fun testIsPreviewStreamUrl() {
        assertTrue(YouTubeMusicClient.isPreviewStreamUrl("https://x/videoplayback?dur=30"))
        assertTrue(YouTubeMusicClient.isPreviewStreamUrl("https://x/videoplayback?x=1&dur=12.5"))
        assertFalse(YouTubeMusicClient.isPreviewStreamUrl("https://x/videoplayback?dur=180"))
        assertFalse(YouTubeMusicClient.isPreviewStreamUrl("https://x/videoplayback?itag=251"))
    }

    @Test
    fun testSelectBestAudioFormatPrefersAacAndSkipsCiphered() {
        val json = """
        {
          "streamingData": {
            "adaptiveFormats": [
              {"itag":251,"mimeType":"audio/webm; codecs=\"opus\"","bitrate":160000,"url":"https://x.googlevideo.com/videoplayback?itag=251"},
              {"itag":140,"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":128000,"url":"https://x.googlevideo.com/videoplayback?itag=140"},
              {"itag":141,"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":256000,"signatureCipher":"s=abc&url=https%3A%2F%2Fx"}
            ]
          }
        }
        """.trimIndent()

        val selected = YouTubeMusicClient.selectBestAudioFormat(json)
        assertNotNull(selected)
        assertTrue(selected!!.mimeType.startsWith("audio/mp4"))
        assertEquals(128000, selected.bitrate)
        assertFalse(selected.url.contains("cipher"))
    }

    @Test
    fun testMergeSearchKeepsPrimaryOrderAndDedups() {
        val client = YouTubeMusicClient()
        val song = t("yt_song1", "Shared Song", "Artist")
        val videoOnly = t("yt_vid1", "Old Clip", "Artist")
        val dupId = t("yt_song1", "Shared Song", "Artist")
        val dupKey = t("yt_vid2", "Shared Song", "Artist")

        // general = native ranking (video + songs), songs = song-filtered result
        val merged = client.mergeSearchResults(
            general = listOf(videoOnly, dupId, dupKey),
            songs = listOf(song),
            limit = 10
        )

        assertEquals(2, merged.size)
        assertEquals("yt_vid1", merged[0].id)
        assertEquals("yt_song1", merged[1].id)
        assertTrue(merged.all { it.id.startsWith("yt_") })
        assertTrue(merged.all { it.provider == "YouTube" })
        assertTrue(merged.all { it.streamUrl == null })
    }

    @Test
    fun testMergeSearchGraftsSongDurationOntoGeneralVideoEntry() {
        val client = YouTubeMusicClient()
        val videoNoDuration = t("yt_same", "Vecchia Canzone", "Artista").copy(durationMs = 0L, album = null)
        val songWithDuration = t("yt_same", "Vecchia Canzone", "Artista").copy(
            album = "Album",
            durationMs = 231_000L
        )

        val merged = client.mergeSearchResults(
            general = listOf(videoNoDuration),
            songs = listOf(songWithDuration),
            limit = 10
        )

        assertEquals(1, merged.size)
        assertEquals("yt_same", merged[0].id)
        assertEquals(231_000L, merged[0].durationMs)
        assertEquals("Album", merged[0].album)
    }

    @Test
    fun testMergeSearchNeverDropsPrimaryVideosWhenSongsFillLimit() {
        val client = YouTubeMusicClient()
        val videoOnly = t("yt_old_video", "Vecchia Canzone", "Artista")
        val songs = (1..40).map { t("yt_song$it", "Song $it", "Artista") }

        // Regression: the old songs-first merge let 20+ songs exhaust the limit and dropped videos.
        val merged = client.mergeSearchResults(listOf(videoOnly), songs, limit = 20)

        assertEquals(20, merged.size)
        assertEquals("yt_old_video", merged[0].id)
    }

    @Test
    fun testSongsPageMarksVideoEntriesAsVideo() {
        val client = YouTubeMusicClient()
        val json = """{"contents":[{"musicPlaylistShelfRenderer":{"contents":[
            {"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"vidVideo"},"flexColumns":[
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Old Clip"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Video"},{"text":" • "},{"text":"Chan"},{"text":" • "},{"text":"2M views"}]}}}]}},
            {"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"vidSong"},"flexColumns":[
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Official Song"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song"},{"text":" • "},{"text":"Artist"},{"text":" • "},{"text":"3:00"}]}}}]}}
        ]}}]}"""
        val page = client.parseYouTubeSongsPage(com.google.gson.JsonParser.parseString(json))
        assertTrue(page.tracks.first { it.id == "yt_vidVideo" }.isVideo)
        assertFalse(page.tracks.first { it.id == "yt_vidSong" }.isVideo)
    }

    @Test
    fun parseWatchNextTracksExtractsRadioQueue() {
        val client = YouTubeMusicClient()
        val json = """{"contents":[{"musicQueueRenderer":{"content":{"playlistPanelRenderer":{"contents":[
            {"playlistPanelVideoRenderer":{"videoId":"vidAtrackAA","title":{"runs":[{"text":"Song A"}]},"shortBylineText":{"runs":[{"text":"Artist A"}]},"lengthText":{"runs":[{"text":"3:12"}]},"thumbnail":{"thumbnails":[{"url":"http://x/a_s.jpg"},{"url":"http://x/a.jpg"}]}}},
            {"playlistPanelVideoRenderer":{"videoId":"vidAtrackAA","title":{"runs":[{"text":"Song A duplicate"}]},"shortBylineText":{"runs":[{"text":"Artist A"}]}}},
            {"playlistPanelVideoRenderer":{"videoId":"shortId","title":{"runs":[{"text":"Ignored invalid id"}]}}},
            {"playlistPanelVideoRenderer":{"videoId":"vidBtrackBB","title":{"runs":[{"text":"Song B"}]},"shortBylineText":{"runs":[{"text":"Artist B"}]},"lengthText":{"simpleText":"4:05"}}}
        ]}}}}]}"""
        val tracks = client.parseWatchNextTracks(com.google.gson.JsonParser.parseString(json), limit = 10)
        assertEquals(2, tracks.size)
        assertEquals("yt_vidAtrackAA", tracks[0].id)
        assertEquals("Song A", tracks[0].title)
        assertEquals("Artist A", tracks[0].artist)
        assertEquals(192_000L, tracks[0].durationMs)
        assertEquals("http://x/a.jpg", tracks[0].coverUrl)
        assertEquals("yt_vidBtrackBB", tracks[1].id)
        assertEquals(245_000L, tracks[1].durationMs)
    }

    @Test
    fun searchDropsGenericYouTubeMusicArtistAndDerivesItFromTitle() {
        val client = YouTubeMusicClient()
        val json = """{"contents":[{"musicPlaylistShelfRenderer":{"contents":[
            {"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"vidNoArtist"},"flexColumns":[
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Random Upload"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song"},{"text":" • "},{"text":"YouTube Music"},{"text":" • "},{"text":"3:00"}]}}}]}},
            {"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"vidDashTitle"},"flexColumns":[
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Real Artist - Real Song"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song"},{"text":" • "},{"text":"YouTube Music"},{"text":" • "},{"text":"3:00"}]}}}]}}
        ]}}]}"""
        val tracks = client.parseSearchTracks(com.google.gson.JsonParser.parseString(json))
        assertEquals(1, tracks.size)
        assertEquals("yt_vidDashTitle", tracks[0].id)
        assertEquals("Real Artist", tracks[0].artist)
        assertTrue(isGenericYouTubeArtist("YouTube Music"))
        assertTrue(isGenericYouTubeArtist("YouTube Music - Topic"))
        assertFalse(isGenericYouTubeArtist("Nujabes"))
    }

    @Test
    fun albumSingleTracksInheritArtistAlbumAndCoverFromHeader() {
        val client = YouTubeMusicClient()
        val json = """{"header":{"musicResponsiveHeaderRenderer":{
            "title":{"runs":[{"text":"Morenito"}]},
            "subtitle":{"runs":[{"text":"Single"},{"text":" • "},{"text":"2026"}]},
            "straplineTextOne":{"runs":[{"text":"INNA"}]},
            "secondSubtitle":{"runs":[{"text":"1 song"}]},
            "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"http://x/cover.jpg"}]}}}
        }},"contents":{"musicShelfRenderer":{"contents":[
            {"musicResponsiveListItemRenderer":{
                "playlistItemData":{"videoId":"eyZOn6_TT0k"},
                "flexColumns":[
                    {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Morenito"}]}}},
                    {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[]}}},
                    {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"12M plays"}]}}}
                ]
            }}
        ]}}}"""
        val detail = client.parseYouTubeAlbumDetail(com.google.gson.JsonParser.parseString(json))
        assertNotNull(detail)
        assertEquals("Morenito", detail!!.album.title)
        assertEquals("INNA", detail.album.artist)
        assertEquals(1, detail.tracks.size)
        assertEquals("INNA", detail.tracks[0].artist)
        assertEquals("Morenito", detail.tracks[0].album)
        assertEquals("http://x/cover.jpg", detail.tracks[0].coverUrl)
        assertEquals(2026, detail.tracks[0].releaseYear)
    }

    @Test
    fun testParseStreamCountReadsPlaysAndViews() {
        assertEquals(3_600_000_000L, YouTubeMusicClient.parseStreamCount("3.6B plays"))
        assertEquals(20_000_000L, YouTubeMusicClient.parseStreamCount("20M views"))
        assertEquals(7_900_000L, YouTubeMusicClient.parseStreamCount("7.9M plays"))
        assertEquals(81_000L, YouTubeMusicClient.parseStreamCount("81K plays"))
        assertEquals(1_234_567L, YouTubeMusicClient.parseStreamCount("1,234,567 views"))
        assertEquals(500L, YouTubeMusicClient.parseStreamCount("500 plays"))
        org.junit.Assert.assertNull(YouTubeMusicClient.parseStreamCount("3:44"))
        org.junit.Assert.assertNull(YouTubeMusicClient.parseStreamCount("Song"))
        org.junit.Assert.assertNull(YouTubeMusicClient.parseStreamCount(""))
    }

    @Test
    fun parseSearchSuggestionsPrefersEndpointQueryAndDeduplicates() {
        val json = """
            {
              "contents": [
                {"searchSuggestionsSectionRenderer": {"contents": [
                  {"searchSuggestionRenderer": {
                    "suggestion": {"runs": [{"text": "radioh", "bold": true}, {"text": "ead"}]},
                    "navigationEndpoint": {"searchEndpoint": {"query": "radiohead"}}
                  }},
                  {"searchSuggestionRenderer": {
                    "suggestion": {"runs": [{"text": "radiohead let down"}]}
                  }},
                  {"searchSuggestionRenderer": {
                    "suggestion": {"runs": [{"text": "radiohead"}]},
                    "navigationEndpoint": {"searchEndpoint": {"query": "radiohead"}}
                  }}
                ]}}
              ]
            }
        """.trimIndent()
        val client = YouTubeMusicClient()
        val suggestions = client.parseSearchSuggestions(com.google.gson.JsonParser.parseString(json))
        assertEquals(listOf("radiohead", "radiohead let down"), suggestions)
    }

    private fun t(id: String, title: String, artist: String) = Track(
        id = id,
        title = title,
        artist = artist,
        album = "YouTube",
        durationMs = 200_000L,
        coverUrl = null,
        streamUrl = null,
        provider = "YouTube"
    )
}
