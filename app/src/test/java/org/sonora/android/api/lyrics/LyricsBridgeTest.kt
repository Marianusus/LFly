package org.sonora.android.api.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class LyricsBridgeTest {

    private fun word(text: String, start: Long, end: Long) = SheetWord(start, end, text)

    @Test
    fun alignWords_singleToken() {
        val result = LyricsBridge.alignWords("Ooh", listOf(word("Ooh", 0, 500)))
        assertNotNull(result)
        assertEquals(1, result!!.size)
        assertEquals(0L, result[0].startMs)
        assertEquals(500L, result[0].endMs)
    }

    @Test
    fun alignWords_spacedWords() {
        val words = listOf(
            word("I ", 0, 100),
            word("can ", 100, 200),
            word("never ", 200, 300),
        )
        val result = LyricsBridge.alignWords("I can never", words)
        assertNotNull(result)
        assertEquals(3, result!!.size)
        assertEquals(0L, result[0].startMs)
        assertEquals(300L, result[2].endMs)
    }

    @Test
    fun alignWords_lineWithApostrophe() {
        val words = listOf(
            word("I ", 0, 100),
            word("said ", 100, 200),
            word("ooh ", 200, 300),
            word("I'm ", 300, 400),
            word("blinded", 400, 500),
        )
        val result = LyricsBridge.alignWords("I said ooh I'm blinded", words)
        assertNotNull(result)
        assertEquals(5, result!!.size)
    }

    @Test
    fun alignWords_subWordTokensGetCombinedSpan() {
        // Musixmatch splits sub-word chunks: "Blinding" arrives as "Blin"+"ding".
        val words = listOf(
            word("Blin", 0, 100),
            word("ding ", 100, 200),
            word("Lights", 200, 300),
        )
        val result = LyricsBridge.alignWords("Blinding Lights", words)
        assertNotNull(result)
        assertEquals(2, result!!.size)
        assertEquals(0L, result[0].startMs)
        assertEquals(200L, result[0].endMs)
    }
}
