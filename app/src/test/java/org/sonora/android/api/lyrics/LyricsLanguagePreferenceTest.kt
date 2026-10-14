package org.sonora.android.api.lyrics

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsLanguagePreferenceTest {

    private fun line(text: String) = SheetLine(startMs = 0, endMs = 0, text = text)

    private fun hit(name: String, vararg lines: String) = LyricsHit(
        source = name,
        trust = 0,
        lyrics = Sheet.Synced(lines.map { line(it) }.toMutableList()),
        instrumental = false,
        title = "t",
        artist = "a",
        album = null,
        durationMs = null
    )

    /** Fake detector: "en" when the chunk contains one of [enPhrases], otherwise "ro". */
    private fun detector(vararg enPhrases: String): suspend (String) -> String? {
        val phrases = enPhrases.toList()
        return { text -> if (phrases.any { text.contains(it) }) "en" else "ro" }
    }

    @Test
    fun mixedTopPrefersTheMonolingualAlternative() = runBlocking {
        val bilingual = hit(
            "LrcLib-synced",
            "Te iubesc oriunde ai fi", "De o mie de ori pe zi", "Te doresc mai mult",
            "You're the master of my dreams", "I cannot dream without you", "I would never leave you"
        )
        val original = hit(
            "LrcLib-plain",
            "Te iubesc oriunde ai fi", "De o mie de ori pe zi", "Te doresc mai mult", "Cu fiecare minut"
        )
        val detect = detector("You're the master", "I cannot dream", "I would never")
        val chosen = LyricsLanguagePreference.preferred(listOf(bilingual, original), detect)
        assertEquals("LrcLib-plain", chosen?.source)
    }

    @Test
    fun monolingualTopIsLeftUntouched() = runBlocking {
        val original = hit("A", "Te iubesc oriunde ai fi", "De o mie de ori pe zi", "Cu fiecare minut")
        val other = hit("B", "Something else entirely")
        assertNull(LyricsLanguagePreference.preferred(listOf(original, other), detector("Something else")))
    }

    @Test
    fun noMonolingualAlternativeKeepsTheTop() = runBlocking {
        val a = hit("A", "uno due tre", "say something", "another line here")
        val b = hit("B", "alpha beta", "gamma delta")
        val detect = detector()
        assertNull(LyricsLanguagePreference.preferred(listOf(a, b), detect))
    }

    @Test
    fun undeterminedLinesAreTreatedAsMonolingual() = runBlocking {
        val a = hit("A", "Oooo", "Aaaa", "La la la")
        val b = hit("B", "Te iubesc oriunde ai fi", "De o mie de ori pe zi")
        assertNull(LyricsLanguagePreference.preferred(listOf(a, b)) { null })
    }
}
