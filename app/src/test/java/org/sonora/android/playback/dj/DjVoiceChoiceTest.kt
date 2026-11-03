package org.sonora.android.playback.dj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class DjVoiceChoiceTest {

    private val italy = Locale.forLanguageTag("it-IT")

    private fun voice(name: String, quality: Int, network: Boolean, locale: Locale = italy, installed: Boolean = true) =
        VoiceOption(
            voice = name,
            locale = locale,
            quality = quality,
            latency = 200,
            offline = !network && installed,
            name = name,
            networkOnly = network
        )

    private val voices = listOf(
        voice("it-local", quality = 400, network = false),
        voice("it-network", quality = 500, network = true),
        voice("it-missing", quality = 500, network = false, installed = false),
        voice("en-network", quality = 500, network = true, locale = Locale.US)
    )

    @Test
    fun `online the most natural network voice wins`() {
        assertEquals("it-network", chooseOption(voices, italy, online = true, preferred = null)?.name)
    }

    @Test
    fun `offline only installed voices can speak`() {
        assertEquals("it-local", chooseOption(voices, italy, online = false, preferred = null)?.name)
    }

    @Test
    fun `the listener's voice wins when it can speak now`() {
        assertEquals("it-local", chooseOption(voices, italy, online = true, preferred = "it-local")?.name)
        // A network voice picked by the listener waits for a connection: offline the best local one speaks.
        assertEquals("it-local", chooseOption(voices, italy, online = false, preferred = "it-network")?.name)
    }

    @Test
    fun `a picked voice of another language or not installed is ignored`() {
        assertEquals("it-network", chooseOption(voices, italy, online = true, preferred = "en-network")?.name)
        assertEquals("it-network", chooseOption(voices, italy, online = true, preferred = "it-missing")?.name)
    }

    @Test
    fun `no voice for the language means none`() {
        assertNull(chooseOption(voices.filter { it.locale == Locale.US }, italy, online = true, preferred = null))
    }
}
