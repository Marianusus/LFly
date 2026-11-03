package org.sonora.android.playback.dj.piper

/** One Piper neural voice the DJ can download and speak with. */
data class PiperVoice(
    /** Piper's own name, e.g. "it_IT-miro-high"; also the folder it is unpacked into. */
    val id: String,
    /** "it", "en" or "ro", as the DJ speaks. */
    val language: String,
    /** The speaker's name, shown in Settings. */
    val speaker: String,
    val highQuality: Boolean,
    /** Size of the download, for Settings. */
    val downloadMb: Int
) {
    /** The package as sherpa-onnx publishes it: model, tokens and the eSpeak pronunciation data. */
    val url: String
        get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-$id.tar.bz2"
}

/** The voices on offer, best first in each language: the first one is what the DJ downloads on its own. */
object PiperCatalog {
    val voices: List<PiperVoice> = listOf(
        PiperVoice("it_IT-miro-high", "it", "Miro", highQuality = true, downloadMb = 67),
        PiperVoice("it_IT-dii-high", "it", "Dii", highQuality = true, downloadMb = 67),
        PiperVoice("it_IT-paola-medium", "it", "Paola", highQuality = false, downloadMb = 67),
        PiperVoice("en_US-miro-high", "en", "Miro", highQuality = true, downloadMb = 67),
        PiperVoice("en_US-lessac-medium", "en", "Lessac", highQuality = false, downloadMb = 67),
        PiperVoice("en_US-ryan-medium", "en", "Ryan", highQuality = false, downloadMb = 67),
        PiperVoice("ro_RO-mihai-medium", "ro", "Mihai", highQuality = false, downloadMb = 67)
    )

    fun forLanguage(language: String): List<PiperVoice> = voices.filter { it.language == language }

    fun byId(id: String): PiperVoice? = voices.firstOrNull { it.id == id }

    /** The voice the DJ fetches by itself for [language]. */
    fun default(language: String): PiperVoice? = forLanguage(language).firstOrNull()

    /** Preferences keep Piper voices apart from the phone's own: "piper:it_IT-miro-high". */
    const val PREFIX = "piper:"

    fun preferenceKey(voice: PiperVoice): String = PREFIX + voice.id

    fun fromPreference(value: String?): PiperVoice? =
        value?.takeIf { it.startsWith(PREFIX) }?.let { byId(it.removePrefix(PREFIX)) }
}
