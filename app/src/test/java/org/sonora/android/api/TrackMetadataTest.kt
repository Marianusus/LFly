package org.sonora.android.api

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TrackMetadataTest {
    private val fallback = Track(
        id = "yt_3AtDnEC4zak",
        title = "We Don't Talk Anymore",
        artist = "Charlie Puth",
        album = "Nine Track Mind",
        durationMs = 0L,
        coverUrl = null,
        streamUrl = "https://media.example/audio",
        provider = "YouTube",
        albumId = "MPREb_test",
    )

    @Test
    fun playerResponseSuppliesReleaseYearAndLiveCounts() {
        val root = JsonParser.parseString(
            """
            {
              "videoDetails": {
                "title": "We Don't Talk Anymore",
                "author": "Charlie Puth",
                "channelId": "UCwppdrjsBPAZg5_cUwQjfMQ",
                "lengthSeconds": "231",
                "viewCount": "3622278565",
                "thumbnail": {"thumbnails": [
                  {"url": "https://img.example/small.jpg", "width": 120, "height": 120},
                  {"url": "https://img.example/large.jpg", "width": 1200, "height": 1200}
                ]}
              },
              "microformat": {"microformatDataRenderer": {
                "title": "We Don't Talk Anymore (feat. Selena Gomez)",
                "publishDate": "2016-08-02T13:00:26-07:00",
                "uploadDate": "2016-08-02T13:00:26-07:00",
                "viewCount": 3622278565
              }}
            }
            """.trimIndent()
        )
        val metadata = YouTubeMusicClient().parseTrackMetadata(root, fallback)
        assertEquals("We Don't Talk Anymore (feat. Selena Gomez)", metadata.title)
        assertEquals("Charlie Puth", metadata.artist)
        assertEquals(231_000L, metadata.durationMs)
        assertEquals(2016, metadata.releaseYear)
        assertEquals("2016-08-02T13:00:26-07:00", metadata.releaseDate)
        assertEquals(3_622_278_565L, metadata.streams)
        assertEquals("UCwppdrjsBPAZg5_cUwQjfMQ", metadata.artistId)
        assertEquals("https://img.example/large.jpg", metadata.artworkUrl)
        assertEquals("MPREb_test", metadata.albumId)
    }

    @Test
    fun missingDatesLeaveYearNull() {
        val root = JsonParser.parseString(
            """{"videoDetails":{"title":"Track","author":"Artist","lengthSeconds":"10"}}"""
        )
        val metadata = YouTubeMusicClient().parseTrackMetadata(root, fallback)
        assertNull(metadata.releaseYear)
        assertNull(metadata.releaseDate)
    }

    @Test
    fun mergingMetadataKeepsPlaybackIdentityAndFillsFacts() {
        val merged = fallback.withMetadata(
            TrackMetadata(
                releaseYear = 2016,
                albumId = "MPREb_album",
                streams = 42L,
            )
        )
        assertEquals("yt_3AtDnEC4zak", merged.id)
        assertEquals("https://media.example/audio", merged.streamUrl)
        assertEquals(2016, merged.releaseYear)
        assertEquals("MPREb_album", merged.albumId)
        assertEquals(42L, merged.streams)
        assertSame(fallback, fallback.withMetadata(null))
    }

    @Test
    fun albumHeaderCombinesStraplineAndSubtitleForReleaseYear() {
        val root = JsonParser.parseString(
            """
            {"header":{"musicDetailHeaderRenderer":{
              "title":{"runs":[{"text":"Nine Track Mind"}]},
              "straplineTextOne":{"runs":[{"text":"Charlie Puth"}]},
              "subtitle":{"runs":[{"text":"Album • 2016"}]}
            }}}
            """.trimIndent()
        )
        val detail = YouTubeMusicClient().parseYouTubeAlbumDetail(root)
        assertEquals(2016, detail?.album?.releaseYear)
    }

    @Test
    fun albumHeaderFindsYearInEitherHeaderLine() {
        val root = JsonParser.parseString(
            """
            {"header":{"musicResponsiveHeaderRenderer":{
              "title":{"runs":[{"text":"Release"}]},
              "straplineTextOne":{"runs":[{"text":"Album • 2021"}]},
              "subtitle":{"runs":[{"text":"Artist"}]}
            }}}
            """.trimIndent()
        )
        val detail = YouTubeMusicClient().parseYouTubeAlbumDetail(root)
        assertEquals(2021, detail?.album?.releaseYear)
    }
}
