package org.sonora.android.playback.dj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sonora.android.api.Track
import java.util.Locale
import kotlin.random.Random

class DjScriptTest {

    private fun track(title: String, artist: String) =
        Track(id = title, title = title, artist = artist, album = null, durationMs = 200_000L, coverUrl = null)

    private val songs = listOf(track("Blinding Lights", "The Weeknd"), track("Levitating", "Dua Lipa"))

    private fun subjectOf(kind: DjBlockKind) = when (kind) {
        DjBlockKind.FOLLOWED_ARTIST -> "Måneskin"
        DjBlockKind.MIX -> "Pop Mix"
        else -> null
    }

    @Test
    fun `every kind, language and opening gives a line naming its subject`() {
        for (kind in DjBlockKind.entries) for (lang in DjScript.LANGUAGES) for (first in listOf(true, false)) {
            val subject = subjectOf(kind)
            repeat(20) { seed ->
                val line = DjScript.line(DjBlock(kind, songs, subject), lang, first, Random(seed))
                assertTrue("$kind/$lang/$first: '$line'", line.isNotBlank())
                assertFalse("unfilled placeholder in '$line'", '{' in line || '}' in line)
                if (subject != null) assertTrue("'$line' misses $subject", subject in line)
            }
        }
    }

    @Test
    fun `lines without songs or subject still read well`() {
        for (kind in DjBlockKind.entries) for (lang in DjScript.LANGUAGES) {
            repeat(10) { seed ->
                val line = DjScript.line(DjBlock(kind, emptyList()), lang, seed % 2 == 0, Random(seed))
                assertTrue(line.isNotBlank())
                assertFalse('{' in line || '}' in line)
            }
        }
    }

    @Test
    fun `a followed artist block without subject names the first artist`() {
        val line = DjScript.line(DjBlock(DjBlockKind.FOLLOWED_ARTIST, songs), "it", false, Random(1))
        assertTrue(line, "The Weeknd" in line)
    }

    @Test
    fun `the first song is usually named`() {
        val named = (0 until 50).count { seed ->
            "Blinding Lights" in DjScript.line(DjBlock(DjBlockKind.TOP_PLAYED, songs), "it", false, Random(seed))
        }
        assertTrue("only $named of 50", named >= 40)
    }

    @Test
    fun `clutter is stripped from titles`() {
        assertEquals("Song", DjScript.cleanTitle("Song (Official Video) [Remastered]", "X", "en"))
        assertEquals("Yesterday", DjScript.cleanTitle("Yesterday - Remastered 2009", "The Beatles", "en"))
        assertEquals("Hello", DjScript.cleanTitle("Adele - Hello (Official Music Video)", "Adele", "en"))
        assertEquals("Song", DjScript.cleanTitle("Song (Lyrics) (Visualizer)", "X", "it"))
        assertEquals("Live Forever (Live at Wembley)", DjScript.cleanTitle("Live Forever (Live at Wembley)", "Oasis", "en"))
    }

    @Test
    fun `feat and ampersand are spoken in each language`() {
        assertEquals("Song con B", DjScript.cleanTitle("Song (feat. B)", "A", "it"))
        assertEquals("Song with B", DjScript.cleanTitle("Song ft. B", "A", "en"))
        assertEquals("A cu B", DjScript.cleanName("A feat. B", "ro"))
        assertEquals("Simon e Garfunkel", DjScript.cleanName("Simon & Garfunkel", "it"))
        assertEquals("Simon and Garfunkel", DjScript.cleanName("Simon & Garfunkel", "en"))
        assertEquals("Simon și Garfunkel", DjScript.cleanName("Simon & Garfunkel", "ro"))
        assertEquals("Daft Punk", DjScript.cleanName("Daft Punk", "en"))
    }

    @Test
    fun `emoji and symbols are dropped and long titles are capped`() {
        assertEquals("Love Song", DjScript.cleanTitle("Love ❤️ Song 🔥 ★", "X", "en"))
        val long = DjScript.cleanTitle("word ".repeat(40), "X", "en")
        assertTrue(long.length <= 60)
        assertFalse(long.endsWith(" "))
    }

    @Test
    fun `unknown languages fall back to English`() {
        val block = DjBlock(DjBlockKind.DISCOVERY, songs)
        assertEquals(DjScript.line(block, "en", true, Random(3)), DjScript.line(block, "de", true, Random(3)))
        assertEquals(DjScript.line(block, "it", false, Random(3)), DjScript.line(block, "it-IT", false, Random(3)))
        assertEquals("en", DjScript.languageOf(""))
    }

    @Test
    fun `the same seed gives the same line`() {
        for (kind in DjBlockKind.entries) for (lang in DjScript.LANGUAGES) {
            val block = DjBlock(kind, songs, subjectOf(kind))
            assertEquals(DjScript.line(block, lang, true, Random(42)), DjScript.line(block, lang, true, Random(42)))
        }
    }

    @Test
    fun `an intro is never used twice in a row within a session`() {
        val random = Random(7)
        val block = DjBlock(DjBlockKind.THROWBACK, emptyList())
        var previous: String? = null
        repeat(40) {
            val line = DjScript.line(block, "en", false, random)
            assertNotEquals(previous, line)
            previous = line
        }
    }

    @Test
    fun `lines stay short`() {
        val longSong = track("A".repeat(30) + " " + "B".repeat(30) + " " + "C".repeat(30), "D".repeat(80))
        for (kind in DjBlockKind.entries) for (lang in DjScript.LANGUAGES) repeat(20) { seed ->
            val normal = DjScript.line(DjBlock(kind, songs, subjectOf(kind)), lang, seed % 2 == 0, Random(seed))
            assertTrue("${normal.length}: $normal", normal.length <= 200)
            val huge = DjScript.line(DjBlock(kind, listOf(longSong), subjectOf(kind)), lang, true, Random(seed))
            assertTrue("${huge.length}: $huge", huge.length <= 280)
        }
    }

    @Test
    fun `the voice locale follows the script language`() {
        assertEquals(Locale.forLanguageTag("it-IT"), localeFor("it"))
        assertEquals(Locale.forLanguageTag("ro-RO"), localeFor("ro"))
        assertEquals(Locale.forLanguageTag("en-US"), localeFor("fr"))
    }

    @Test
    fun `the best offline voice wins`() {
        val italy = Locale.forLanguageTag("it-IT")
        val options = listOf(
            VoiceOption("network", italy, quality = 500, latency = 100, offline = false),
            VoiceOption("swiss", Locale.forLanguageTag("it-CH"), quality = 500, latency = 100, offline = true),
            VoiceOption("low", italy, quality = 300, latency = 100, offline = true),
            VoiceOption("high-slow", italy, quality = 400, latency = 400, offline = true),
            VoiceOption("high-fast", italy, quality = 400, latency = 200, offline = true),
            VoiceOption("english", Locale.US, quality = 500, latency = 100, offline = true)
        )
        assertEquals("high-fast", chooseVoice(options, italy))
        assertEquals("high-slow", chooseVoice(options.filter { it.voice != "high-fast" }, italy))
        assertNull(chooseVoice(options.take(1), italy))
        assertEquals("swiss", chooseVoice(options.filter { it.voice == "swiss" }, italy))
    }
}
