package org.sonora.android.api.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsTextTest {

    @Test
    fun splitArtistTitle_stripsVideoNoiseAndTopicSuffix() {
        val (title, artist) = LyricsText.splitArtistTitle(
            "Blinding Lights (Official Music Video)",
            "The Weeknd - Topic",
        )
        assertEquals("Blinding Lights", title)
        assertEquals("The Weeknd", artist)
    }

    @Test
    fun splitArtistTitle_swapsArtistTitleVideoTitles() {
        val (title, artist) = LyricsText.splitArtistTitle(
            "Edward Maya, Vika Jigulina - Stereo love (Radio Edit) (Lyrics)",
            "LyricsZone",
        )
        assertEquals("Stereo love", title)
        assertEquals("Edward Maya, Vika Jigulina", artist)
    }

    @Test
    fun splitArtistTitle_leavesCleanMetadataAlone() {
        val (title, artist) = LyricsText.splitArtistTitle("Jaded", "Spiritbox")
        assertEquals("Jaded", title)
        assertEquals("Spiritbox", artist)
    }

    @Test
    fun splitArtistTitle_removesRemasterSuffixWithoutSwapping() {
        val (title, artist) = LyricsText.splitArtistTitle("Love - Remastered 2011", "X")
        assertEquals("Love", title)
        assertEquals("X", artist)
    }
}
