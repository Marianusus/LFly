package org.sonora.android.data

import android.content.ContextWrapper
import com.google.gson.Gson
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.sonora.android.api.Track
import java.io.File

class MixStoreTest {

    private lateinit var tempDir: File

    private val track = Track(
        id = "t1",
        title = "Song One",
        artist = "Alexandra Stan",
        album = "Album",
        durationMs = 200_000L,
        coverUrl = null
    )

    private val mix = Mix(
        id = "mix_europop",
        kind = MIX_KIND_GENRE,
        genreKey = "europop",
        name = "Europop Mix",
        tracks = listOf(track),
        updatedAt = 100L
    )

    @Before
    fun setUp() {
        tempDir = File.createTempFile("sonora_mix_test", "").apply {
            delete()
            mkdir()
        }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun repository(): MusicRepository =
        MusicRepository(ContextWrapper(null), storageDir = tempDir)

    private fun waitUntil(timeoutMs: Long = 4_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        fail("Condition not met within ${timeoutMs}ms")
    }

    @Test
    fun `saving creates a static snapshot and removing clears it`() {
        File(tempDir, "mixes.json").writeText(Gson().toJson(listOf(mix)))
        val repository = repository()
        waitUntil { repository.mixes.value.any { it.id == mix.id } }

        repository.saveMixToLibrary(mix.id)
        val saved = repository.mixes.value.first { it.id == mix.id }
        assertTrue(saved.isSaved)
        assertEquals(listOf(track), saved.savedTracks)
        assertNotNull(saved.savedAt)
        waitUntil {
            File(tempDir, "mixes.json").readText().contains("savedTracks")
        }

        repository.removeMixFromLibrary(mix.id)
        val removed = repository.mixes.value.first { it.id == mix.id }
        assertFalse(removed.isSaved)
        assertNull(removed.savedTracks)
        assertNull(removed.savedAt)
    }

    @Test
    fun `legacy On Repeat playlist is migrated to a non-saved mix`() {
        val legacy = Playlist(
            id = MusicRepository.PLAYLIST_ON_REPEAT_ID,
            name = "On Repeat",
            tracks = listOf(track)
        )
        File(tempDir, "playlists.json").writeText(Gson().toJson(listOf(legacy)))
        val repository = repository()

        waitUntil {
            repository.playlists.value.none { it.id == MusicRepository.PLAYLIST_ON_REPEAT_ID } &&
                repository.mixes.value.any { it.id == MIX_ON_REPEAT_ID }
        }

        val migrated = repository.mixes.value.first { it.id == MIX_ON_REPEAT_ID }
        assertEquals(listOf(track), migrated.tracks)
        assertFalse(migrated.isSaved)
        assertNull(migrated.savedTracks)
        assertTrue(File(tempDir, "mixes.json").exists())
    }
}
