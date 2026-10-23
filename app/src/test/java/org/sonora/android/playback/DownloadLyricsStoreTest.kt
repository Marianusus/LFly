package org.sonora.android.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.sonora.android.api.LyricLine
import org.sonora.android.api.Lyrics
import org.sonora.android.api.WordTiming
import java.io.File

class DownloadLyricsStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var store: DownloadLyricsStore

    @Before
    fun setUp() {
        root = temp.newFolder("downloaded_lyrics")
        store = DownloadLyricsStore(root)
    }

    private fun lyrics(id: String = "track-1") = Lyrics(
        id = id,
        plainLyrics = null,
        syncedLyrics = null,
        lines = listOf(
            LyricLine(
                timeMs = 1_000L,
                text = "Hello world",
                wordTimings = listOf(
                    WordTiming("Hello", 1_000L, 1_400L),
                    WordTiming("world", 1_450L, 1_900L),
                ),
                background = "echo",
            ),
            LyricLine(timeMs = 2_000L, text = "Second line"),
        ),
    )

    @Test
    fun roundTrip_preservesLyricsAndTimestamp() {
        val original = OfflineLyrics(
            lyrics = lyrics(),
            source = "LRCLIB",
            notFound = false,
            fetchedAt = 123_456L,
        )

        store.write("track-1", original)

        val read = store.read("track-1")
        assertEquals(original, read)
        assertEquals(listOf("Hello world", "Second line"), read?.lyrics?.lines?.map { it.text })
        assertEquals("Hello", read?.lyrics?.lines?.first()?.wordTimings?.first()?.text)
        assertEquals(123_456L, read?.fetchedAt)
    }

    @Test
    fun roundTrip_plainLyrics() {
        val original = OfflineLyrics(
            lyrics = Lyrics(id = "plain", plainLyrics = "just text", syncedLyrics = null),
            source = "GENIUS",
            notFound = false,
        )

        store.write("plain", original)

        val read = store.read("plain")
        assertEquals("just text", read?.lyrics?.plainLyrics)
        assertEquals("GENIUS", read?.source)
        assertFalse(read?.notFound ?: true)
    }

    @Test
    fun sourceIsPersisted() {
        store.write("track-1", OfflineLyrics(lyrics(), "BINI_LYRICS", notFound = false))

        assertEquals("BINI_LYRICS", store.read("track-1")?.source)
    }

    @Test
    fun notFoundMarker_isPersisted() {
        val marker = OfflineLyrics(lyrics = null, source = null, notFound = true)

        store.write("missing", marker)

        val read = store.read("missing")
        assertEquals(marker, read)
        assertNull(read?.lyrics)
        assertNull(read?.source)
        assertTrue(read?.notFound == true)
    }

    @Test
    fun read_returnsNullWhenNeverWritten() {
        assertNull(store.read("absent"))
        assertFalse(store.has("absent"))
    }

    @Test
    fun read_returnsNullForCorruptFile() {
        store.write("broken", OfflineLyrics(lyrics(), "LRCLIB", notFound = false))
        val file = root.listFiles()?.single() ?: error("expected one lyrics file")
        file.writeText("{ this is not json", Charsets.UTF_8)

        assertNull(store.read("broken"))
        assertTrue(store.has("broken"))
    }

    @Test
    fun read_returnsNullForEmptyFile() {
        store.write("empty", OfflineLyrics(lyrics(), "LRCLIB", notFound = false))
        val file = root.listFiles()?.single() ?: error("expected one lyrics file")
        file.writeText("", Charsets.UTF_8)

        assertNull(store.read("empty"))
    }

    @Test
    fun delete_removesOnlyThatTrack() {
        store.write("a", OfflineLyrics(lyrics("a"), "LRCLIB", notFound = false))
        store.write("b", OfflineLyrics(lyrics("b"), "LRCLIB", notFound = false))

        store.delete("a")

        assertNull(store.read("a"))
        assertFalse(store.has("a"))
        assertEquals("b", store.read("b")?.lyrics?.id)
    }

    @Test
    fun clear_removesEveryTrack() {
        store.write("a", OfflineLyrics(lyrics("a"), "LRCLIB", notFound = false))
        store.write("b", OfflineLyrics(lyrics("b"), "LRCLIB", notFound = false))

        store.clear()

        assertNull(store.read("a"))
        assertNull(store.read("b"))
        assertEquals(0, root.listFiles()?.size ?: 0)
    }

    @Test
    fun idsWithStrangeCharactersStayInsideRoot() {
        val ids = listOf(
            "a/b",
            "../escape",
            "id:with?chars*and|pipes",
            "weird id with spaces",
            "emoji-\uD83C\uDFB5",
        )

        ids.forEachIndexed { index, id ->
            store.write(id, OfflineLyrics(lyrics(id), "LRCLIB", notFound = false))
            assertEquals(id, store.read(id)?.lyrics?.id)
        }

        val files = root.listFiles()?.toList().orEmpty()
        assertEquals(ids.size, files.size)
        assertTrue(files.all { it.parentFile == root })
        assertTrue(files.all { it.name.matches(Regex("^[0-9a-f]{40}\\.json$")) })
        assertEquals(0, temp.root.listFiles()?.count { it.name == "escape" } ?: 0)
        assertFalse(store.has("a"))
        assertTrue(store.has("a/b"))
    }

    @Test
    fun write_overwritesPreviousValue() {
        store.write("a", OfflineLyrics(lyrics("a"), "LRCLIB", notFound = false))
        store.write("a", OfflineLyrics(lyrics = null, source = null, notFound = true))

        val read = store.read("a")
        assertTrue(read?.notFound == true)
        assertNull(read?.lyrics)
        assertEquals(1, root.listFiles()?.count { it.name.endsWith(".json") })
    }
}
