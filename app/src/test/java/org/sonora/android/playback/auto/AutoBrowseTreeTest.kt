package org.sonora.android.playback.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sonora.android.api.Track
import org.sonora.android.data.Mix
import org.sonora.android.data.Playlist

class AutoBrowseTreeTest {

    private val labels = AutoLabels("Home", "Fav", "Lists", "Down", "History", "Charts")

    private fun track(id: String, streamUrl: String? = null) = Track(
        id = id,
        title = "Title $id",
        artist = "Artist",
        album = null,
        durationMs = 200_000L,
        coverUrl = null,
        streamUrl = streamUrl
    )

    @Test
    fun `root exposes at most four tabs and all are browsable folders`() {
        val root = AutoBrowseTree.root(labels)

        assertEquals(listOf("home", "favorites", "playlists", "downloads"), root.map { it.id })
        assertTrue(root.size <= 4)
    }

    @Test
    fun `track ids keep their folder and survive a round trip`() {
        val id = AutoIds.track(AutoIds.playlist("abc"), "yt_a|b")

        assertEquals("playlist/abc" to "yt_a|b", AutoIds.parseTrack(id))
    }

    @Test
    fun `ids that are not tracks do not parse`() {
        assertNull(AutoIds.parseTrack("favorites"))
        assertNull(AutoIds.parseTrack("yt_abc"))
        assertNull(AutoIds.parseTrack("t|only-folder"))
    }

    @Test
    fun `favorites become songs that point back to their folder`() {
        val snapshot = AutoLibrarySnapshot(favorites = listOf(track("yt_1")))

        val children = AutoBrowseTree.children(AutoIds.FAVORITES, snapshot, labels)

        assertEquals(listOf(AutoIds.track(AutoIds.FAVORITES, "yt_1")), children?.map { it.id })
    }

    @Test
    fun `pseudo tracks, disliked tracks and duplicates are left out`() {
        val snapshot = AutoLibrarySnapshot(
            favorites = listOf(track("yt_1"), track("album_x"), track("artist_y"), track("yt_2"), track("yt_1")),
            dislikedTrackIds = setOf("yt_2")
        )

        assertEquals(listOf("yt_1"), AutoBrowseTree.tracksFor(AutoIds.FAVORITES, snapshot)?.map { it.id })
    }

    @Test
    fun `empty playlists and the downloads playlist are hidden`() {
        val snapshot = AutoLibrarySnapshot(
            playlists = listOf(
                Playlist(id = "p1", name = "Road trip", tracks = listOf(track("yt_1"))),
                Playlist(id = "p2", name = "Empty"),
                Playlist(id = "playlist_downloaded_ytdlp", name = "Downloads", tracks = listOf(track("yt_2")))
            )
        )

        val folders = AutoBrowseTree.children(AutoIds.PLAYLISTS, snapshot, labels)

        assertEquals(listOf(AutoIds.playlist("p1")), folders?.map { it.id })
    }

    @Test
    fun `playlist folder returns its tracks in order`() {
        val snapshot = AutoLibrarySnapshot(
            playlists = listOf(Playlist(id = "p1", name = "List", tracks = listOf(track("yt_2"), track("yt_1"))))
        )

        val tracks = AutoBrowseTree.tracksFor(AutoIds.playlist("p1"), snapshot)

        assertEquals(listOf("yt_2", "yt_1"), tracks?.map { it.id })
    }

    @Test
    fun `saved mixes play their frozen snapshot`() {
        val mix = Mix(id = "mix_1", name = "Mix", tracks = listOf(track("yt_live")), savedTracks = listOf(track("yt_saved")))
        val snapshot = AutoLibrarySnapshot(mixes = listOf(mix))

        assertEquals(listOf("yt_saved"), AutoBrowseTree.tracksFor(AutoIds.mix("mix_1"), snapshot)?.map { it.id })
    }

    @Test
    fun `home lists history, charts and non empty mixes`() {
        val snapshot = AutoLibrarySnapshot(
            mixes = listOf(
                Mix(id = "mix_1", name = "Mix", tracks = listOf(track("yt_1"))),
                Mix(id = "mix_2", name = "Empty mix")
            )
        )

        val ids = AutoBrowseTree.children(AutoIds.HOME, snapshot, labels)?.map { it.id }

        assertEquals(listOf(AutoIds.HISTORY, AutoIds.CHARTS, AutoIds.mix("mix_1")), ids)
    }

    @Test
    fun `offline list counts the same file once`() {
        val merged = AutoBrowseTree.mergeOffline(
            downloads = listOf(track("yt_1", streamUrl = "file:///music/a.m4a")),
            locals = listOf(track("local_1", streamUrl = "/music/a.m4a"), track("local_2", streamUrl = "/music/b.m4a"))
        )

        assertEquals(listOf("yt_1", "local_2"), merged.map { it.id })
    }

    @Test
    fun `unknown folders return null`() {
        val snapshot = AutoLibrarySnapshot()

        assertNull(AutoBrowseTree.children("nope", snapshot, labels))
        assertNull(AutoBrowseTree.tracksFor(AutoIds.playlist("missing"), snapshot))
    }
}
