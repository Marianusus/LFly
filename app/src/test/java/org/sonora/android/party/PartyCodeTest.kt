package org.sonora.android.party

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PartyCodeTest {

    @Test
    fun generatedCodesUseTheUnambiguousAlphabet() {
        repeat(200) {
            val code = PartyCode.generate(Random(it))
            assertEquals(PartyCode.LENGTH, code.length)
            assertTrue(code.all { ch -> ch in PartyCode.ALPHABET })
            assertFalse(code.contains('0'))
            assertFalse(code.contains('O'))
            assertFalse(code.contains('1'))
            assertFalse(code.contains('I'))
        }
    }

    @Test
    fun normalizeAcceptsMessyInput() {
        assertEquals("K7M2QX", PartyCode.normalize(" k7m2-qx "))
        assertEquals("ABCDEF", PartyCode.normalize("abcdef"))
        assertNull(PartyCode.normalize("ABC"))
        assertNull(PartyCode.normalize("ABCDEFG"))
        assertNull(PartyCode.normalize(null))
        assertNull(PartyCode.normalize("00O11I"))
    }

    @Test
    fun endpointNameRoundTrips() {
        val code = "K7M2QX"
        val name = PartyCode.endpointName(code)
        assertEquals("LFly-Party-K7M2QX", name)
        assertEquals(code, PartyCode.codeFromEndpointName(name))
        assertNull(PartyCode.codeFromEndpointName("Other-App-XYZ"))
        assertNull(PartyCode.codeFromEndpointName(null))
        assertNull(PartyCode.codeFromEndpointName("LFly-Party-short"))
    }

    @Test
    fun qrUriRoundTrips() {
        val uri = PartyCode.toUri("K7M2QX", "Marian")
        assertTrue(uri.startsWith("lfly://party?code=K7M2QX"))
        val parsed = PartyCode.parseUri(uri)
        assertNotNull(parsed)
        assertEquals("K7M2QX", parsed!!.first)
        assertEquals("Marian", parsed.second)
    }

    @Test
    fun qrUriWithoutNickStillParses() {
        val parsed = PartyCode.parseUri(PartyCode.toUri("K7M2QX"))
        assertNotNull(parsed)
        assertEquals("K7M2QX", parsed!!.first)
        assertNull(parsed.second)
    }

    @Test
    fun qrUriRejectsForeignPayloads() {
        assertNull(PartyCode.parseUri("https://example.com?code=K7M2QX"))
        assertNull(PartyCode.parseUri("lfly://party?name=Marian"))
        assertNull(PartyCode.parseUri(null))
    }
}
