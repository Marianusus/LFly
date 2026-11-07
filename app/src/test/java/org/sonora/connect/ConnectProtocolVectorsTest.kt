package org.sonora.connect

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * The wire vectors in `connect/fixtures/connect-vectors.json` are shared with the desktop app's Rust
 * tests: both sides must read and write every message and derive every key exactly alike.
 */
class ConnectProtocolVectorsTest {
    private val vectors: JsonObject = JsonParser.parseString(fixture().readText()).asJsonObject

    private fun fixture(): File =
        listOf("../connect/fixtures/connect-vectors.json", "connect/fixtures/connect-vectors.json")
            .map(::File)
            .first { it.isFile }

    @Test
    fun accountProofAndPairingCodeMatchTheVectors() {
        val c = vectors.getAsJsonObject("crypto")
        val secret = c["accountSecret"].asString
        val key = ConnectProtocol.accountKey(secret)
        assertEquals(c["accountKeyHex"].asString, key.joinToString("") { "%02x".format(it) })
        assertEquals(c["ownerTag"].asString, ConnectProtocol.ownerTag(secret))
        assertEquals(c["proof"].asString, ConnectProtocol.proof(key, c["nonce"].asString))
        assertEquals(
            c["pairingCode"].asString,
            ConnectProtocol.pairingCode(c["pairingHostId"].asString, c["pairingClientId"].asString, c["nonce"].asString)
        )
    }

    @Test
    fun everyMessageReadsAndWritesBackTheSame() {
        for (element in vectors.getAsJsonArray("messages")) {
            val text = element.toString()
            val message = ConnectProtocol.decode(text)
            assertNotNull("Not understood: $text", message)
            val written = JsonParser.parseString(ConnectProtocol.encode(message!!))
            assertEquals(element, written)
        }
    }

    @Test
    fun unknownMessagesAreIgnored() {
        assertEquals(null, ConnectProtocol.decode("""{"type":"from_the_future","x":1}"""))
    }
}
