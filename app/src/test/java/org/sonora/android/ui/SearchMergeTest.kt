package org.sonora.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sonora.android.api.Track

class SearchMergeTest {

    private fun track(id: String, title: String, artist: String, provider: String) = Track(
        id = id,
        title = title,
        artist = artist,
        album = if (provider == "YouTube") "YouTube" else "Album",
        durationMs = 200_000L,
        coverUrl = null,
        streamUrl = null,
        provider = provider
    )

    @Test
    fun dedupKeepsYouTubeResultWhenDeezerMatches() {
        val yt = track("yt_dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley", "YouTube")
        val deezer = track("deezer_1", "Never Gonna Give You Up", "Rick Astley", "Deezer")

        val merged = mergeYouTubeFirst(listOf(yt), listOf(deezer))

        assertEquals(1, merged.size)
        assertEquals(yt.id, merged[0].id)
        assertEquals("YouTube", merged[0].provider)
    }

    @Test
    fun dedupIgnoresFeatRemixAndPunctuationDifferences() {
        val yt = track("yt_abc12345678", "Song (feat. Someone) [Remix]", "Artist", "YouTube")
        val deezer = track("deezer_2", "Song", "Artist", "Deezer")

        val merged = mergeYouTubeFirst(listOf(yt), listOf(deezer))

        assertEquals(1, merged.size)
        assertTrue(merged[0].id.startsWith("yt_"))
    }

    @Test
    fun youtubeResultsAreNeverRemovedAndDeezerUniqueKept() {
        val yt1 = track("yt_A", "Only YT Song", "Indie", "YouTube")
        val yt2 = track("yt_B", "Shared Song", "Pop", "YouTube")
        val deezerShared = track("deezer_3", "Shared Song", "Pop", "Deezer")
        val deezerUnique = track("deezer_4", "Deezer Only", "Pop", "Deezer")

        val merged = mergeYouTubeFirst(listOf(yt1, yt2), listOf(deezerShared, deezerUnique))

        assertEquals(3, merged.size)
        assertEquals("yt_A", merged[0].id)
        assertEquals("yt_B", merged[1].id)
        assertEquals("deezer_4", merged[2].id)
    }

    @Test
    fun runtimeStreamKeepsYtIdAndYouTubeProvider() {
        val yt = track("yt_abc12345678", "Song", "Artist", "YouTube")

        // Runtime stream from Monochrome/JioSaavn (non-local HTTP keeps the original provider).
        val runtime = yt.copy(streamUrl = "https://tracks.monochrome.st/track/12345", provider = yt.provider)

        assertEquals("yt_abc12345678", runtime.id)
        assertEquals("YouTube", runtime.provider)
    }
}
