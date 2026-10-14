package org.sonora.android.api.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater

class KrcTest {

    private val key = byteArrayOf(
        0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
        0x51, 0x36, 0x31, 0x2d, 0xce.toByte(), 0xd2.toByte(), 0x6e, 0x69,
    )

    private fun encode(plain: String): String {
        val deflater = Deflater()
        deflater.setInput(plain.toByteArray(Charsets.UTF_8))
        deflater.finish()
        val compressed = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!deflater.finished()) {
            val n = deflater.deflate(buffer)
            compressed.write(buffer, 0, n)
        }
        deflater.end()
        val body = compressed.toByteArray()
        val masked = ByteArray(body.size)
        for (i in body.indices) {
            masked[i] = ((body[i].toInt() and 0xFF) xor (key[i % key.size].toInt() and 0xFF)).toByte()
        }
        val withHeader = ByteArray(4 + masked.size)
        "krc1".toByteArray().copyInto(withHeader)
        masked.copyInto(withHeader, 4)
        return Base64.getEncoder().encodeToString(withHeader)
    }

    @Test
    fun decode_roundTripsARealKrcBlob() {
        val plain = "[ti:x]\n[0,500]<0,500,0>Hello"
        assertEquals(plain, Krc.decode(encode(plain)))
    }

    @Test
    fun decode_rejectsGarbage() {
        assertNull(Krc.decode("not base64 @" + "!!"))
        assertNull(Krc.decode(""))
    }

    @Test
    fun parse_readsWordsLiftedByTheLineStart() {
        val text = """
            [ti:Blinding Lights]
            [ar:The Weeknd]
            [1757,4829]<0,439,0>Lyrics by：Max <439,439,0>Martin
            [27672,2784]<0,200,0>I've <200,144,0>been <344,176,0>tryna <520,632,0>call
        """.trimIndent()

        val lines = Krc.parse(text)
        assertEquals(1, lines.size)
        val line = lines[0]
        assertEquals(27_672L, line.startMs)
        assertEquals("I've been tryna call", line.text)
        val words = line.words!!
        assertEquals(4, words.size)
        assertEquals("I've ", words[0].text)
        assertEquals(27_672L, words[0].startMs)
        assertEquals(27_872L, words[0].endMs)
        assertEquals(28_016L, words[2].startMs)
        assertEquals(28_192L, words[3].startMs)
        assertEquals(28_824L, words[3].endMs)
    }

    @Test
    fun parse_dropsCreditLinesAndSortsByStart() {
        val text = """
            [ti:x]
            [0,1000]<0,1000,0>Title - Artist
            [5000,1000]<0,1000,0>real line
            [3000,1000]<0,1000,0>earlier line
            [6000,1000]<0,1000,0>Produced by Foo
        """.trimIndent()

        // The title card is left in by parse (the provider drops it); the credit line is not.
        val lines = Krc.parse(text)
        assertEquals(3, lines.size)
        assertEquals(0L, lines[0].startMs)
        assertEquals(3_000L, lines[1].startMs)
        assertEquals(5_000L, lines[2].startMs)
    }
}
