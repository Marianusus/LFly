package org.sonora.android.data

import android.content.ContextWrapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.sonora.android.api.Track
import java.io.File

/**
 * Favorites must survive every download prune, and Local snapshots must be re-pointed at the
 * original catalog identity when a download can be resolved.
 */
class FavoritePruneRepairTest {

    private class FakeContext(
        private val filesDir: File,
        private val externalRoot: File?
    ) : ContextWrapper(null) {
        override fun getFilesDir(): File = filesDir

        override fun getExternalFilesDir(type: String?): File? {
            val root = externalRoot ?: return null
            return if (type.isNullOrBlank()) root else File(root, type)
        }
    }

    private lateinit var tempDir: File
    private lateinit var externalMusic: File
    private lateinit var repository: MusicRepository

    @Before
    fun setUp() {
        tempDir = File.createTempFile("sonora_favorites_test", "").apply {
            delete()
            mkdir()
        }
        val filesDir = File(tempDir, "files").apply { mkdirs() }
        externalMusic = File(tempDir, "external/music").apply { mkdirs() }
        repository = MusicRepository(
            FakeContext(filesDir, File(tempDir, "external")),
            storageDir = tempDir
        )
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun localTrack(path: String?, id: String = "local_abc"): Track = Track(
        id = id,
        title = "Local Song",
        artist = "Local Artist",
        album = null,
        durationMs = 180_000L,
        coverUrl = null,
        streamUrl = path,
        provider = "Local"
    )

    private fun remoteTrack(id: String = "t2"): Track = Track(
        id = id,
        title = "Remote Song",
        artist = "Remote Artist",
        album = "Album",
        durationMs = 200_000L,
        coverUrl = "https://example.com/cover.jpg",
        provider = "YouTube"
    )

    @Test
    fun pruneDeletedLocalTrackNeverTouchesFavorites() {
        val deletedPath = File(externalMusic, "gone.m4a").absolutePath
        val local = localTrack(deletedPath)
        repository.addFavorite(local)
        repository.addToHistory(local)
        val playlist = repository.createPlaylistWithTracks("Mix", listOf(local))

        repository.pruneDeletedLocalTrack(deletedPath)

        assertEquals(listOf("local_abc"), repository.favorites.value.map { it.id })
        assertTrue("History may drop the missing file", repository.history.value.isEmpty())
        assertTrue(
            "Playlists may drop the missing file",
            repository.getPlaylist(playlist.id)!!.tracks.isEmpty()
        )
    }

    @Test
    fun pruneMissingManagedLocalTracksNeverTouchesFavorites() {
        val missing = File(externalMusic, "missing.m4a")
        val local = localTrack(missing.absolutePath)
        repository.addFavorite(local)
        repository.addToHistory(local)

        repository.pruneMissingManagedLocalTracks()

        assertEquals(listOf("local_abc"), repository.favorites.value.map { it.id })
        assertTrue("History may drop the missing file", repository.history.value.isEmpty())
    }

    @Test
    fun missingLocalFileIsClearedButCatalogFavoriteSurvives() {
        val missing = File(externalMusic, "gone.m4a")
        repository.addFavorite(
            Track(
                id = "yt_abc",
                title = "Remote Song",
                artist = "Remote Artist",
                album = "Album",
                durationMs = 200_000L,
                coverUrl = "file://${File(externalMusic, "gone.jpg").absolutePath}",
                streamUrl = "file://${missing.absolutePath}",
                provider = "Local"
            )
        )

        repository.repairMissingLocalFavorites()

        val favorite = repository.favorites.value.single()
        assertEquals("yt_abc", favorite.id)
        assertEquals("YouTube", favorite.provider)
        assertEquals(null, favorite.streamUrl)
        assertEquals(null, favorite.coverUrl)
    }

    @Test
    fun repairFavoritesReplacesLocalCopyWithCanonicalIdentityPreservingOrder() {
        val local = localTrack("/music/Artist - Song [abcd1234].m4a", id = "local_1")
        val canonical = remoteTrack(id = "yt_1")
        repository.addFavorite(remoteTrack(id = "t0"))
        repository.addFavorite(local)

        repository.repairFavorites { track ->
            if (track.id == "local_1") canonical else null
        }

        assertEquals(listOf("yt_1", "t0"), repository.favorites.value.map { it.id })
        assertEquals("YouTube", repository.favorites.value.first().provider)
    }

    @Test
    fun repairFavoritesNeverRemovesAFavoriteWhenNoCanonicalTrackExists() {
        val local = localTrack("/music/unknown.m4a", id = "local_unknown")
        repository.addFavorite(local)

        repository.repairFavorites { null }

        assertEquals(listOf("local_unknown"), repository.favorites.value.map { it.id })
    }
}
