package org.sonora.android.data

import android.content.ContextWrapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.sonora.android.api.Track
import java.io.File

class MusicRepositoryTest {

    private lateinit var tempDir: File
    private lateinit var repository: MusicRepository

    private val track1 = Track(
        id = "t1",
        title = "Song One",
        artist = "Artist A",
        album = "Album Alpha",
        durationMs = 180_000L,
        coverUrl = "https://example.com/cover1.jpg"
    )

    private val track2 = Track(
        id = "t2",
        title = "Song Two",
        artist = "Artist B",
        album = "Album Beta",
        durationMs = 210_000L,
        coverUrl = "https://example.com/cover2.jpg"
    )

    private val track3 = Track(
        id = "t3",
        title = "Song Three",
        artist = "Artist C",
        album = "Album Gamma",
        durationMs = 240_000L,
        coverUrl = "https://example.com/cover3.jpg"
    )

    @Before
    fun setUp() {
        tempDir = File.createTempFile("sonora_repo_test", "").apply {
            delete()
            mkdir()
        }
        val context = ContextWrapper(null)
        repository = MusicRepository(context, storageDir = tempDir)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // ==========================================
    // Playlist CRUD Tests
    // ==========================================

    @Test
    fun testCreateAndGetPlaylist() {
        val created = repository.createPlaylist(
            name = "My Chill Mix",
            description = "Evening relax vibes",
            coverUrl = "https://example.com/chill.jpg"
        )

        assertNotNull(created.id)
        assertEquals("My Chill Mix", created.name)
        assertEquals("Evening relax vibes", created.description)
        assertEquals("https://example.com/chill.jpg", created.coverUrl)
        assertTrue(created.tracks.isEmpty())

        val retrieved = repository.getPlaylist(created.id)
        assertNotNull(retrieved)
        assertEquals(created.id, retrieved?.id)
        assertEquals("My Chill Mix", retrieved?.name)
        assertTrue(repository.playlists.value.any { it.id == created.id })
    }

    @Test
    fun testCreatePlaylistWithTracks() {
        val tracks = listOf(track1, track2)
        val created = repository.createPlaylistWithTracks(
            name = "Road Trip",
            tracks = tracks,
            description = "Highway jams"
        )

        assertEquals("Road Trip", created.name)
        assertEquals(2, created.tracks.size)
        assertEquals("t1", created.tracks[0].id)
        assertEquals("t2", created.tracks[1].id)

        val retrieved = repository.getPlaylist(created.id)
        assertEquals(2, retrieved?.tracks?.size)
    }

    @Test
    fun testAddTrackToPlaylist() {
        val playlist = repository.createPlaylist("Favorites Mix")
        assertEquals(0, playlist.tracks.size)

        repository.addTrackToPlaylist(playlist.id, track1)
        var updated = repository.getPlaylist(playlist.id)
        assertEquals(1, updated?.tracks?.size)
        assertEquals("t1", updated?.tracks?.first()?.id)

        // Adding second track
        repository.addTrackToPlaylist(playlist.id, track2)
        updated = repository.getPlaylist(playlist.id)
        assertEquals(2, updated?.tracks?.size)

        // Adding duplicate track should not duplicate
        repository.addTrackToPlaylist(playlist.id, track1)
        updated = repository.getPlaylist(playlist.id)
        assertEquals("Duplicate track should not be added", 2, updated?.tracks?.size)
    }

    @Test
    fun testRemoveTrackFromPlaylist() {
        val playlist = repository.createPlaylistWithTracks("Workout", listOf(track1, track2, track3))
        assertEquals(3, playlist.tracks.size)

        repository.removeTrackFromPlaylist(playlist.id, "t2")
        val updated = repository.getPlaylist(playlist.id)
        assertEquals(2, updated?.tracks?.size)
        assertFalse("t2 should have been removed", updated?.tracks?.any { it.id == "t2" } == true)
        assertEquals("t1", updated?.tracks?.get(0)?.id)
        assertEquals("t3", updated?.tracks?.get(1)?.id)
    }

    @Test
    fun testRenameAndUpdatePlaylist() {
        val playlist = repository.createPlaylist("Old Name", description = "Old Desc")

        repository.renamePlaylist(playlist.id, "New Name")
        var updated = repository.getPlaylist(playlist.id)
        assertEquals("New Name", updated?.name)
        assertEquals("Old Desc", updated?.description)

        repository.updatePlaylist(
            playlistId = playlist.id,
            name = "Final Name",
            description = "Updated Desc",
            coverUrl = "https://example.com/newcover.jpg"
        )
        updated = repository.getPlaylist(playlist.id)
        assertEquals("Final Name", updated?.name)
        assertEquals("Updated Desc", updated?.description)
        assertEquals("https://example.com/newcover.jpg", updated?.coverUrl)
    }

    @Test
    fun testDeletePlaylist() {
        val playlist1 = repository.createPlaylist("Playlist 1")
        val playlist2 = repository.createPlaylist("Playlist 2")
        assertEquals(2, repository.playlists.value.size)

        repository.deletePlaylist(playlist1.id)
        assertEquals(1, repository.playlists.value.size)
        assertNull(repository.getPlaylist(playlist1.id))
        assertNotNull(repository.getPlaylist(playlist2.id))
    }

    // ==========================================
    // Playlist Reordering Tests
    // ==========================================

    @Test
    fun testReorderPlaylist() {
        val playlist = repository.createPlaylistWithTracks(
            name = "Ordered Playlist",
            tracks = listOf(track1, track2, track3)
        )

        // Initial order: [track1, track2, track3]
        assertEquals(listOf("t1", "t2", "t3"), playlist.tracks.map { it.id })

        // Move track at index 0 (t1) to index 2 -> [t2, t3, t1]
        repository.reorderPlaylist(playlist.id, from = 0, to = 2)
        var reordered = repository.getPlaylist(playlist.id)
        assertEquals(listOf("t2", "t3", "t1"), reordered?.tracks?.map { it.id })

        // Move track at index 2 (t1) back to index 0 -> [t1, t2, t3]
        repository.reorderPlaylist(playlist.id, from = 2, to = 0)
        reordered = repository.getPlaylist(playlist.id)
        assertEquals(listOf("t1", "t2", "t3"), reordered?.tracks?.map { it.id })

        // Move track at index 1 (t2) to index 2 -> [t1, t3, t2]
        repository.reorderPlaylist(playlist.id, from = 1, to = 2)
        reordered = repository.getPlaylist(playlist.id)
        assertEquals(listOf("t1", "t3", "t2"), reordered?.tracks?.map { it.id })
    }

    @Test
    fun testReorderPlaylistOutOfBoundsIsNoOp() {
        val playlist = repository.createPlaylistWithTracks(
            name = "Ordered Playlist",
            tracks = listOf(track1, track2)
        )

        // Invalid indices: should not crash or change list
        repository.reorderPlaylist(playlist.id, from = -1, to = 1)
        repository.reorderPlaylist(playlist.id, from = 0, to = 10)
        repository.reorderPlaylist(playlist.id, from = 0, to = 0)

        val current = repository.getPlaylist(playlist.id)
        assertEquals(listOf("t1", "t2"), current?.tracks?.map { it.id })
    }

    // ==========================================
    // Favorite Reordering Tests
    // ==========================================

    @Test
    fun testReorderFavorites() {
        repository.addFavorite(track1)
        repository.addFavorite(track2)
        repository.addFavorite(track3)
        // addFavorite prepends, so the list is [t3, t2, t1]
        assertEquals(listOf("t3", "t2", "t1"), repository.favorites.value.map { it.id })

        // Move first (t3, local index 0) to local index 2 -> [t2, t1, t3]
        repository.reorderFavorites(from = 0, to = 2)
        assertEquals(listOf("t2", "t1", "t3"), repository.favorites.value.map { it.id })

        // Move it back to index 0 -> [t3, t2, t1]
        repository.reorderFavorites(from = 2, to = 0)
        assertEquals(listOf("t3", "t2", "t1"), repository.favorites.value.map { it.id })
    }

    @Test
    fun testReorderFavoritesOutOfBoundsIsNoOp() {
        repository.addFavorite(track1)
        repository.addFavorite(track2)

        repository.reorderFavorites(from = 0, to = 5)
        repository.reorderFavorites(from = -1, to = 1)
        repository.reorderFavorites(from = 0, to = 0)

        assertEquals(listOf("t2", "t1"), repository.favorites.value.map { it.id })
    }

    // ==========================================
    // Favorite Toggles Tests
    // ==========================================

    @Test
    fun testToggleFavorite() {
        assertFalse(repository.isFavorite("t1"))
        assertTrue(repository.favorites.value.isEmpty())

        // Toggle on: becomes favorite
        val isFav1 = repository.toggleFavorite(track1)
        assertTrue("toggleFavorite should return true when added", isFav1)
        assertTrue(repository.isFavorite("t1"))
        assertEquals(1, repository.favorites.value.size)
        assertEquals("t1", repository.favorites.value.first().id)

        // Toggle on another track
        val isFav2 = repository.toggleFavorite(track2)
        assertTrue(isFav2)
        assertTrue(repository.isFavorite("t2"))
        assertEquals(2, repository.favorites.value.size)

        // Toggle off track 1: removed from favorites
        val isFavOff = repository.toggleFavorite(track1)
        assertFalse("toggleFavorite should return false when removed", isFavOff)
        assertFalse(repository.isFavorite("t1"))
        assertTrue(repository.isFavorite("t2"))
        assertEquals(1, repository.favorites.value.size)
        assertEquals("t2", repository.favorites.value.first().id)
    }

    @Test
    fun testAddAndRemoveFavoriteExplicit() {
        repository.addFavorite(track1)
        assertTrue(repository.isFavorite("t1"))

        // Re-adding should not duplicate
        repository.addFavorite(track1)
        assertEquals(1, repository.favorites.value.size)

        repository.removeFavorite("t1")
        assertFalse(repository.isFavorite("t1"))
        assertTrue(repository.favorites.value.isEmpty())
    }

    // ==========================================
    // Dislike Management Tests
    // ==========================================

    @Test
    fun testDislikeRemovesFromFavoritesAutomatically() {
        // Track is initially favorite
        repository.addFavorite(track1)
        assertTrue(repository.isFavorite("t1"))

        // Disliking track should remove it from favorites
        val isDisliked = repository.toggleDislike(track1)
        assertTrue(isDisliked)
        assertTrue(repository.isDisliked("t1"))
        assertFalse("Disliking track must automatically remove it from favorites", repository.isFavorite("t1"))
        assertTrue("Disliked artists should include Artist A", repository.dislikedArtists.value.contains("artist a"))
    }

    @Test
    fun testDislikeToggleRemovesDislike() {
        repository.toggleDislike(track1)
        assertTrue(repository.isDisliked("t1"))

        // Toggle again removes dislike
        val isDisliked = repository.toggleDislike(track1)
        assertFalse(isDisliked)
        assertFalse(repository.isDisliked("t1"))
    }

    // ==========================================
    // Play History Tests
    // ==========================================

    @Test
    fun testAddToHistoryAndClear() {
        repository.addToHistory(track1)
        repository.addToHistory(track2)

        // Most recent track should be at index 0
        assertEquals("t2", repository.history.value[0].id)
        assertEquals("t1", repository.history.value[1].id)

        repository.clearHistory()
        assertTrue(repository.history.value.isEmpty())
    }

    // ==========================================
    // Imported Spotify cover update tests
    // ==========================================

    private fun spotifyTrack(id: String, cover: String?) = Track(
        id = id,
        title = "Song",
        artist = "Artist",
        album = "Album",
        durationMs = 200_000L,
        coverUrl = cover,
        streamUrl = null,
        provider = "Spotify"
    )

    @Test
    fun testUpdateImportedCoverUpdatesOnlyCover() {
        val sp = spotifyTrack("spotify_abc", "https://old.example/c.jpg")
        val pl = repository.createPlaylistWithTracks("Import", listOf(sp))

        repository.updateImportedTrackCover("spotify_abc", "https://yt.example/fresh.jpg")

        val t = repository.getPlaylist(pl.id)!!.tracks.first()
        assertEquals("https://yt.example/fresh.jpg", t.coverUrl)
        assertEquals("spotify_abc", t.id)
        assertEquals("Spotify", t.provider)
        assertEquals("Song", t.title)
        assertNull(t.streamUrl)
    }

    @Test
    fun testUpdateImportedCoverIgnoresNullOrNonHttpOrEqual() {
        val sp = spotifyTrack("spotify_abc", "https://old.example/c.jpg")
        repository.createPlaylistWithTracks("Import", listOf(sp))

        repository.updateImportedTrackCover("spotify_abc", null)
        repository.updateImportedTrackCover("spotify_abc", "")
        repository.updateImportedTrackCover("spotify_abc", "not-a-url")
        repository.updateImportedTrackCover("spotify_abc", "https://old.example/c.jpg")

        assertEquals("https://old.example/c.jpg", repository.playlists.value.first().tracks.first().coverUrl)
    }

    @Test
    fun testUpdateImportedCoverDoesNotTouchNonSpotify() {
        val deezerById = Track(
            id = "deezer_1", title = "X", artist = "Y", album = null,
            durationMs = 1000L, coverUrl = "https://old.example/a.jpg", provider = "Deezer"
        )
        repository.createPlaylistWithTracks("P1", listOf(deezerById))
        // id not spotify_ -> no-op
        repository.updateImportedTrackCover("deezer_1", "https://yt.example/new.jpg")
        assertEquals("https://old.example/a.jpg", repository.playlists.value.first().tracks.first().coverUrl)

        val spotifyIdDeezerProvider = Track(
            id = "spotify_zzz", title = "X", artist = "Y", album = null,
            durationMs = 1000L, coverUrl = "https://old.example/a.jpg", provider = "Deezer"
        )
        val pl2 = repository.createPlaylistWithTracks("P2", listOf(spotifyIdDeezerProvider))
        // provider is not Spotify -> no-op
        repository.updateImportedTrackCover("spotify_zzz", "https://yt.example/new.jpg")
        assertEquals("https://old.example/a.jpg", repository.getPlaylist(pl2.id)!!.tracks.first().coverUrl)
    }

    @Test
    fun testUpdateImportedCoverAmbiguousTrackIdIsSkipped() {
        val sp = spotifyTrack("spotify_abc", "https://old.example/c.jpg")
        repository.createPlaylistWithTracks("P1", listOf(sp))
        repository.createPlaylistWithTracks("P2", listOf(sp))

        repository.updateImportedTrackCover("spotify_abc", "https://yt.example/fresh.jpg")

        assertTrue(
            repository.playlists.value.all { it.tracks.first().coverUrl == "https://old.example/c.jpg" }
        )
    }

    @Test
    fun testImportedCoverPersistsAfterReload() {
        val sp = spotifyTrack("spotify_cover1", "https://old.example/c.jpg")
        val pl = repository.createPlaylistWithTracks("Cover Reload", listOf(sp))
        repository.updateImportedTrackCover("spotify_cover1", "https://yt.example/fresh.jpg")

        val file = File(tempDir, "playlists.json")
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            if (file.exists() && file.readText().contains("https://yt.example/fresh.jpg")) break
            Thread.sleep(50)
        }
        assertTrue("Cover must be persisted", file.exists() && file.readText().contains("https://yt.example/fresh.jpg"))

        val reloaded = MusicRepository(ContextWrapper(null), storageDir = tempDir)
        val reloadDeadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < reloadDeadline) {
            if (reloaded.getPlaylist(pl.id) != null) break
            Thread.sleep(50)
        }
        val t = reloaded.getPlaylist(pl.id)?.tracks?.firstOrNull { it.id == "spotify_cover1" }
        assertNotNull("Playlist must survive reload", t)
        assertEquals("https://yt.example/fresh.jpg", t!!.coverUrl)
        assertEquals("Spotify", t.provider)
        assertNull(t.streamUrl)
    }
}
