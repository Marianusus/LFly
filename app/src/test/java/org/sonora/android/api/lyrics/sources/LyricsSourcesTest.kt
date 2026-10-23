package org.sonora.android.api.lyrics.sources

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sonora.android.api.lyrics.LyricsHit
import org.sonora.android.api.lyrics.LyricsRepository
import org.sonora.android.api.lyrics.Sheet
import org.sonora.android.api.lyrics.SheetLine
import org.sonora.android.api.lyrics.SheetWord

class LyricsSourcesTest {

    @Test
    fun ttml_parsesWordsAndMergesSyllables() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body><div>
                <p begin="1.000" end="2.500"><span begin="1.000" end="1.300">Now</span>
                <span begin="1.300" end="1.600">es</span><span begin="1.600" end="1.900">pres</span><span begin="1.900" end="2.500">so</span></p>
              </div></body>
            </tt>
        """.trimIndent()
        val sheet = SourceTtml.parse(ttml)
        assertNotNull(sheet)
        val line = sheet!!.lines.single()
        assertEquals("Now espresso", line.text)
        assertEquals(2, line.words!!.size)
        assertEquals("espresso", line.words!![1].text)
        assertEquals(1_300L, line.words!![1].startMs)
        assertEquals(2_500L, line.words!![1].endMs)
    }

    @Test
    fun ttml_keepsBackgroundVocalSeparate() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <body><div><p begin="1.0" end="3.0">
                <span begin="1.0" end="2.0">Lead</span>
                <span ttm:role="x-bg"><span begin="2.0" end="3.0">echo</span></span>
              </p></div></body>
            </tt>
        """.trimIndent()
        val line = SourceTtml.parse(ttml)!!.lines.single()
        assertEquals("Lead", line.text)
        assertEquals("echo", line.secondary.single().text)
    }

    @Test
    fun enhancedLrc_parsesWordStamps() {
        val raw = "[00:01.00]<00:01.00>Hello <00:01.50>world"
        val sheet = SourceEnhancedLrc.parse(raw)
        assertNotNull(sheet)
        val line = sheet!!.lines.single()
        assertEquals("Hello world", line.text)
        assertEquals(2, line.words!!.size)
        assertEquals(1_500L, line.words!![1].startMs)
    }

    @Test
    fun providerParser_unwrapsJsonTtml() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="1.0" end="2.0">Hi</p></div></body></tt>"""
        val raw = "{\"ttml\":\"" + ttml.replace("\"", "\\\"") + "\"}"
        val sheet = SourceLyricsParser.parse(raw)
        assertTrue(sheet is Sheet.Synced)
        assertEquals("Hi", (sheet as Sheet.Synced).lines.single().text)
    }

    @Test
    fun karaokeLrc_parsesPrefixWords() {
        val raw = """[1000,2000]Hello (1000,500)world (1500,1500)"""
        val sheet = SourceKaraokeLrc.parse(raw)
        assertNotNull(sheet)
        assertEquals("Hello world", sheet!!.lines.single().text)
        assertEquals(2, sheet.lines.single().words!!.size)
    }

    @Test
    fun lyricsPlus_parsesSyllablesIntoWords() {
        val raw = """
            {"lyrics":[
              {"time":1000,"duration":2000,"text":"Hello world","syllabus":[
                {"time":1000,"duration":400,"text":"Hel"},
                {"time":1400,"duration":300,"text":"lo "},
                {"time":1700,"duration":1300,"text":"world"}
              ]}
            ]}
        """.trimIndent()
        val sheet = SourceLyricsPlus.parse(raw)
        assertTrue(sheet is Sheet.Synced)
        val line = (sheet as Sheet.Synced).lines.single()
        assertEquals("Hello world", line.text)
        assertEquals(2, line.words!!.size)
        assertEquals("Hello", line.words!![0].text)
    }

    @Test
    fun lyricsPlus_allZeroTimesFallsBackToPlainLyrics() {
        val raw = """
            {"lyrics":[
              {"time":0,"duration":0,"text":"First line"},
              {"time":0,"duration":0,"text":"Second line"}
            ]}
        """.trimIndent()
        val sheet = SourceLyricsPlus.parse(raw)
        assertTrue(sheet is Sheet.Plain)
        assertEquals("First line\nSecond line", (sheet as Sheet.Plain).text)
    }

    @Test
    fun repository_respectsSourceOrderWhenNotPrioritizingSyllables() = runBlocking {
        val line = hit(LyricsSource.LRC_RED, synced(worded = false))
        val word = hit(LyricsSource.LYRICS_PLUS, synced(worded = true))
        val result = LyricsRepository.lookup(
            title = "Song",
            artist = "Artist",
            album = null,
            durationMs = 200_000,
            sources = setOf(LyricsSource.LRC_RED, LyricsSource.LYRICS_PLUS),
            order = listOf(LyricsSource.LRC_RED, LyricsSource.LYRICS_PLUS),
            fetcher = { source, _, _ -> if (source == LyricsSource.LRC_RED) line else word },
        )
        assertEquals(LyricsSource.LRC_RED, result?.source)
    }

    @Test
    fun repository_picksFirstWordedSourceInOrder() = runBlocking {
        val first = hit(LyricsSource.LYRICS_PLUS, synced(worded = true))
        val second = hit(LyricsSource.LRC_RED, synced(worded = true))
        val result = LyricsRepository.lookup(
            title = "Song",
            artist = "Artist",
            album = null,
            durationMs = 200_000,
            sources = setOf(LyricsSource.LYRICS_PLUS, LyricsSource.LRC_RED),
            order = listOf(LyricsSource.LYRICS_PLUS, LyricsSource.LRC_RED),
            fetcher = { source, _, _ ->
                if (source == LyricsSource.LYRICS_PLUS) first else second
            },
        )
        assertEquals(LyricsSource.LYRICS_PLUS, result?.source)
    }

    @Test
    fun sourceOrderPreservesCustomWordLevelOrder() {
        val custom = listOf(
            LyricsSource.LYRICS_PLUS,
            LyricsSource.LRC_RED,
            LyricsSource.LRCLIB,
            LyricsSource.LRC_RED,
        )
        val ordered = LyricsSource.ordered(custom)
        assertTrue(ordered.indexOf(LyricsSource.LYRICS_PLUS) < ordered.indexOf(LyricsSource.LRC_RED))
        assertTrue(ordered.indexOf(LyricsSource.LRC_RED) < ordered.indexOf(LyricsSource.LRCLIB))
        assertEquals(LyricsSource.offered.toSet(), ordered.toSet())
    }

    @Test
    fun repository_prefersWordSyncWhenRequested() = runBlocking {
        val line = hit(LyricsSource.LRC_RED, synced(worded = false))
        val word = hit(LyricsSource.LYRICS_PLUS, synced(worded = true))
        val result = LyricsRepository.lookup(
            title = "Song",
            artist = "Artist",
            album = null,
            durationMs = 200_000,
            sources = setOf(LyricsSource.LRC_RED, LyricsSource.LYRICS_PLUS),
            order = listOf(LyricsSource.LRC_RED, LyricsSource.LYRICS_PLUS),
            prioritizeSyllableSync = true,
            fetcher = { source, _, _ -> if (source == LyricsSource.LRC_RED) line else word },
        )
        assertEquals(LyricsSource.LYRICS_PLUS, result?.source)
    }

    @Test
    fun repository_doesNotWakeGeniusAfterSyncedHit() = runBlocking {
        var geniusCalls = 0
        val result = LyricsRepository.lookup(
            title = "Song",
            artist = "Artist",
            album = null,
            durationMs = 200_000,
            sources = setOf(LyricsSource.LRCLIB, LyricsSource.GENIUS),
            order = listOf(LyricsSource.LRCLIB, LyricsSource.GENIUS),
            fetcher = { source, _, _ ->
                if (source == LyricsSource.GENIUS) geniusCalls++
                if (source == LyricsSource.LRCLIB) hit(source, synced(false)) else null
            },
        )
        assertEquals(LyricsSource.LRCLIB, result?.source)
        assertEquals(0, geniusCalls)
    }

    @Test
    fun repository_fallsBackToGeniusWhenEverythingElseMisses() = runBlocking {
        val plain = LyricsHit(
            "Genius", 0, Sheet.Plain("words"), false, "Song", "Artist", null, 200_000,
        )
        val result = LyricsRepository.lookup(
            title = "Song",
            artist = "Artist",
            album = null,
            durationMs = 200_000,
            sources = setOf(LyricsSource.LRCLIB, LyricsSource.GENIUS),
            order = listOf(LyricsSource.LRCLIB, LyricsSource.GENIUS),
            fetcher = { source, _, _ -> if (source == LyricsSource.GENIUS) plain else null },
        )
        assertEquals(LyricsSource.GENIUS, result?.source)
    }

    @Test
    fun repository_marksKeyedSourceWithoutCallingIt() = runBlocking {
        var calls = 0
        var state: LyricsProviderState? = null
        val result = LyricsRepository.lookup(
            title = "Song",
            artist = "Artist",
            album = null,
            durationMs = 200_000,
            sources = setOf(LyricsSource.PAXSENIX_SPOTIFY),
            order = listOf(LyricsSource.PAXSENIX_SPOTIFY),
            apiKey = null,
            onState = { source, value ->
                if (source == LyricsSource.PAXSENIX_SPOTIFY) state = value
            },
            fetcher = { _, _, _ -> calls++ ; null },
        )
        assertNull(result)
        assertEquals(0, calls)
        assertEquals(LyricsProviderState.REQUIRES_KEY, state)
    }

    private fun synced(worded: Boolean): Sheet.Synced {
        val words = if (worded) {
            mutableListOf(SheetWord(1_000L, 2_000L, "hello"))
        } else null
        return Sheet.Synced(mutableListOf(SheetLine(1_000L, 2_000L, "hello", words = words)))
    }

    private fun hit(source: LyricsSource, sheet: Sheet): LyricsHit = LyricsHit(
        source = source.label,
        trust = 0,
        lyrics = sheet,
        instrumental = false,
        title = "Song",
        artist = "Artist",
        album = null,
        durationMs = 200_000,
    )
}
