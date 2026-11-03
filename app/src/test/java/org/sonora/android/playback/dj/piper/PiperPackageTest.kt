package org.sonora.android.playback.dj.piper

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

class PiperPackageTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun archive(vararg files: Pair<String, String>): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(bytes)).use { tar ->
            for ((name, content) in files) {
                val data = content.toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = data.size.toLong() })
                tar.write(data)
                tar.closeArchiveEntry()
            }
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }

    private val voicePackage = arrayOf(
        "vits-piper-it_IT-miro-high/it_IT-miro-high.onnx" to "model",
        "vits-piper-it_IT-miro-high/it_IT-miro-high.onnx.json" to "{}",
        "vits-piper-it_IT-miro-high/tokens.txt" to "a 1",
        "vits-piper-it_IT-miro-high/MODEL_CARD" to "card",
        "vits-piper-it_IT-miro-high/espeak-ng-data/it_dict" to "dict",
        "vits-piper-it_IT-miro-high/espeak-ng-data/lang/roa/it" to "lang"
    )

    @Test
    fun `unpacks the model, tokens and the pronunciation data`() {
        val voice = temp.newFolder("voice")
        val espeak = File(temp.root, "espeak")
        PiperPackage.unpack(archive(*voicePackage), voice, espeak, needEspeak = true)
        assertEquals("model", File(voice, PiperPackage.MODEL).readText())
        assertEquals("a 1", File(voice, PiperPackage.TOKENS).readText())
        assertEquals("card", File(voice, PiperPackage.CARD).readText())
        assertEquals("dict", File(espeak, "it_dict").readText())
        assertEquals("lang", File(espeak, "lang/roa/it").readText())
        assertTrue(File(espeak, PiperPackage.ESPEAK_DONE).isFile)
        // The JSON config is not needed: tokens.txt already carries the phoneme ids.
        assertEquals(setOf(PiperPackage.MODEL, PiperPackage.TOKENS, PiperPackage.CARD), voice.list()!!.toSet())
    }

    @Test
    fun `the pronunciation data is skipped when it is already there`() {
        val voice = temp.newFolder("voice")
        val espeak = File(temp.root, "espeak")
        PiperPackage.unpack(archive(*voicePackage), voice, espeak, needEspeak = false)
        assertFalse(espeak.exists())
        assertTrue(File(voice, PiperPackage.MODEL).isFile)
    }

    @Test
    fun `a package without a model is refused`() {
        try {
            PiperPackage.unpack(
                archive("vits-piper-x/tokens.txt" to "a 1"),
                temp.newFolder("voice"),
                File(temp.root, "espeak"),
                needEspeak = true
            )
            fail("expected an error")
        } catch (expected: IOException) {
        }
    }

    @Test
    fun `paths that climb out of the folders are refused`() {
        try {
            PiperPackage.unpack(
                archive(*voicePackage, "vits-piper-x/espeak-ng-data/../../evil" to "x"),
                temp.newFolder("voice"),
                File(temp.root, "espeak"),
                needEspeak = true
            )
            fail("expected an error")
        } catch (expected: IOException) {
        }
        assertFalse(File(temp.root, "evil").exists())
    }

    @Test
    fun `every language the DJ speaks has a voice, and preferences round-trip`() {
        for (language in listOf("it", "en", "ro")) assertNotNull(PiperCatalog.default(language))
        assertEquals(PiperCatalog.voices.size, PiperCatalog.voices.map { it.id }.toSet().size)
        val miro = PiperCatalog.byId("it_IT-miro-high")!!
        assertEquals(miro, PiperCatalog.fromPreference(PiperCatalog.preferenceKey(miro)))
        assertNull(PiperCatalog.fromPreference("it-it-x-itb-network"))
        assertNull(PiperCatalog.fromPreference(null))
        assertTrue(miro.url.endsWith("/vits-piper-it_IT-miro-high.tar.bz2"))
    }
}
