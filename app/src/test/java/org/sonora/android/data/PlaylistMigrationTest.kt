package org.sonora.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sonora.android.api.Track

class PlaylistMigrationTest {

    private fun track(id: String, provider: String, streamUrl: String?) = Track(
        id = id,
        title = "Song",
        artist = "Artist",
        album = "Album",
        durationMs = 200_000L,
        coverUrl = "https://cover.url/1.jpg",
        streamUrl = streamUrl,
        provider = provider
    )

    @Test
    fun migratesLegacySaavnEntryStripsRuntimeUrl() {
        val old = Playlist(
            name = "Old Import",
            tracks = listOf(track("saavn_123", "JioSaavn", "https://aac.saavncdn.com/x.mp4")),
            schemaVersion = 0
        )

        val migrated = migratePlaylist(old)

        assertEquals(PLAYLIST_SCHEMA_VERSION, migrated.schemaVersion)
        assertEquals(1, migrated.tracks.size)
        val t = migrated.tracks[0]
        assertTrue("Legacy saavn id must become stable spotify_ id", t.id.startsWith("spotify_"))
        assertEquals("Spotify", t.provider)
        assertNull("Runtime stream URL must be stripped", t.streamUrl)
        assertEquals("Song", t.title)
        assertEquals("Album", t.album)
        assertEquals("https://cover.url/1.jpg", t.coverUrl)
    }

    @Test
    fun migrationIsIdempotent() {
        val already = Playlist(
            name = "New",
            tracks = listOf(track("spotify_abc", "Spotify", null))
        )

        val once = migratePlaylist(already)
        val twice = migratePlaylist(once)

        assertEquals(once, twice)
        assertEquals(PLAYLIST_SCHEMA_VERSION, twice.schemaVersion)
        assertEquals(1, twice.tracks.size)
        assertNull(twice.tracks[0].streamUrl)
    }

    @Test
    fun doesNotAlterNonImportedTracks() {
        val other = Playlist(
            name = "Local",
            tracks = listOf(track("deezer_1", "Deezer", "https://cdn.example/track.mp3"))
        )

        val migrated = migratePlaylist(other)

        assertEquals("https://cdn.example/track.mp3", migrated.tracks[0].streamUrl)
        assertEquals("Deezer", migrated.tracks[0].provider)
    }
}
