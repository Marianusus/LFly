package org.sonora.android.api

import android.content.ContextWrapper
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.sonora.android.data.MusicRepository
import java.io.File

class YouTubePlaylistImporterTest {

    private val importer = YouTubePlaylistImporter()

    private lateinit var tempDir: File
    private lateinit var repository: MusicRepository

    @Before
    fun setUp() {
        tempDir = File.createTempFile("sonora_yt_import_test", "").apply {
            delete()
            mkdir()
        }
        repository = MusicRepository(ContextWrapper(null), storageDir = tempDir)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testExtractPlaylistIdFromMusicUrl() {
        assertEquals(
            "PLabc123DEF456",
            importer.extractPlaylistId("https://music.youtube.com/playlist?list=PLabc123DEF456")
        )
    }

    @Test
    fun testExtractPlaylistIdFromWatchUrlWithList() {
        assertEquals(
            "OLAK5uy_kABCDEF",
            importer.extractPlaylistId("https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=OLAK5uy_kABCDEF")
        )
    }

    @Test
    fun testExtractPlaylistIdFromRawId() {
        assertEquals("PLabc123DEF456", importer.extractPlaylistId("PLabc123DEF456"))
    }

    @Test
    fun testExtractPlaylistIdStripsVlPrefix() {
        assertEquals("PLabc123DEF456", importer.extractPlaylistId("VLPLabc123DEF456"))
    }

    @Test
    fun testExtractPlaylistIdInvalid() {
        assertNull(importer.extractPlaylistId("https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(importer.extractPlaylistId("not a playlist"))
        assertNull(importer.extractPlaylistId(""))
    }

    @Test
    fun testImportSavesPlaylist() = runTest {
        val fake = FakeYouTubeImporter()
        fake.detailToReturn = YouTubePlaylistDetail(
            playlist = YouTubePlaylist(
                playlistId = "PLtest",
                browseId = "VLPLtest",
                title = "Road Trip",
                author = "Test User",
                trackCount = 2,
                thumbnailUrl = "https://yt/img.jpg"
            ),
            tracks = listOf(
                Track(id = "yt_aaaaaaaaaaa", title = "One", artist = "A", album = null, durationMs = 1000L, coverUrl = null, provider = "YouTube"),
                Track(id = "yt_bbbbbbbbbbb", title = "Two", artist = "B", album = null, durationMs = 2000L, coverUrl = null, provider = "YouTube")
            )
        )

        val progress = mutableListOf<Triple<Int, Int, String>>()
        val result = fake.importAndSavePlaylist(
            urlOrId = "https://music.youtube.com/playlist?list=PLtest",
            repository = repository,
            onProgress = { cur, tot, title -> progress.add(Triple(cur, tot, title)) }
        )

        assertTrue("Import should succeed", result.isSuccess)
        val saved = result.getOrThrow()
        assertEquals("Road Trip", saved.name)
        assertEquals(2, saved.tracks.size)
        assertEquals(listOf(Triple(1, 2, "One"), Triple(2, 2, "Two")), progress)
    }

    @Test
    fun testImportFailsOnInvalidLink() = runTest {
        val fake = FakeYouTubeImporter()
        val result = fake.importAndSavePlaylist(urlOrId = "not a playlist", repository = repository)
        assertTrue(result.isFailure)
    }

    class FakeYouTubeImporter : YouTubePlaylistImporter() {
        var detailToReturn: YouTubePlaylistDetail? = null

        override suspend fun fetchPlaylistFull(
            playlistId: String,
            onProgress: ((loaded: Int) -> Unit)?
        ): Result<YouTubePlaylistDetail> {
            val detail = detailToReturn ?: return Result.failure(Exception("Not found"))
            return Result.success(detail)
        }
    }
}
