package org.sonora.android.data

import android.content.ContextWrapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.sonora.android.api.Track
import java.io.File

class PlayStatsTest {

    private lateinit var tempDir: File
    private lateinit var repository: MusicRepository

    private fun track(id: String, title: String, artist: String, durationMs: Long = 200_000L) = Track(
        id = "yt_$id",
        title = title,
        artist = artist,
        album = "Album",
        durationMs = durationMs,
        coverUrl = null,
        provider = "YouTube"
    )

    @Before
    fun setUp() {
        tempDir = File.createTempFile("sonora_play_stats_test", "").apply {
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
    fun incrementPlayCountAccumulatesPerTrack() {
        val t = track("a1", "Song A", "Artist A")
        assertEquals(0, repository.playCountOf(t.id))

        repository.incrementPlayCount(t)
        repository.incrementPlayCount(t)
        repository.incrementPlayCount(t)

        assertEquals(3, repository.playCountOf(t.id))
    }

    @Test
    fun recordCompletionHonoursThreshold() {
        val t = track("b1", "Song B", "Artist B", durationMs = 200_000L)

        // Below 85% -> not completed.
        repository.recordCompletion(t, 100_000L)
        assertEquals(0, repository.completionCountOf(t.id))

        // At/above 85% -> completed.
        repository.recordCompletion(t, 175_000L)
        repository.recordCompletion(t, 200_000L)
        assertEquals(2, repository.completionCountOf(t.id))
    }

    @Test
    fun recordCompletionCountsNaturalEndWhenDurationUnknown() {
        val t = track("c1", "Song C", "Artist C", durationMs = 0L)
        repository.recordCompletion(t, 0L)
        assertEquals(1, repository.completionCountOf(t.id))
    }

    @Test
    fun topPlayedTracksAndArtistsAreRanked() {
        val a = track("d1", "Song D1", "Artist D")
        val b = track("d2", "Song D2", "Artist D")
        val c = track("d3", "Song E", "Artist E")

        repository.incrementPlayCount(c)
        repository.incrementPlayCount(a)
        repository.incrementPlayCount(a)
        repository.incrementPlayCount(b)

        val topTracks = repository.topPlayedTracks(2)
        assertEquals("Most played track first", "yt_d1", topTracks.first().id)

        val topArtists = repository.topPlayedArtists(2)
        assertEquals("Artist D", topArtists.first().first)
        assertEquals(3, topArtists.first().second)
    }

    @Test
    fun statsPersistToDisk() {
        val t = track("e1", "Song E", "Artist E")
        repository.incrementPlayCount(t)

        val file = File(tempDir, "play_stats.json")
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            if (file.exists() && file.readText().contains("yt_e1")) break
            Thread.sleep(30)
        }
        assertTrue("play_stats.json must be persisted", file.exists() && file.readText().contains("yt_e1"))
    }
}
