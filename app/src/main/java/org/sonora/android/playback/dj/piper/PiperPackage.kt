package org.sonora.android.playback.dj.piper

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Unpacks a sherpa-onnx Piper package (.tar.bz2) as it streams in: the voice's model, tokens and
 * model card into [voiceDir], and the eSpeak pronunciation data, shared by every voice, into
 * [espeakDir] unless it is already there. Pure file work, so it can be tested off-device.
 */
internal object PiperPackage {
    const val MODEL = "model.onnx"
    const val TOKENS = "tokens.txt"
    const val CARD = "MODEL_CARD"
    const val ESPEAK = "espeak-ng-data"

    /** Written last into a complete eSpeak folder, so a cut download never leaves half the data. */
    const val ESPEAK_DONE = ".complete"

    fun unpack(archive: InputStream, voiceDir: File, espeakDir: File, needEspeak: Boolean) {
        voiceDir.mkdirs()
        if (needEspeak) espeakDir.mkdirs()
        var model = false
        var tokens = false
        TarArchiveInputStream(BZip2CompressorInputStream(archive.buffered())).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                if (entry.isDirectory) continue
                // "vits-piper-it_IT-miro-high/espeak-ng-data/it_dict" -> "espeak-ng-data/it_dict"
                val path = entry.name.substringAfter('/', "")
                val target = when {
                    path.startsWith("$ESPEAK/") -> if (needEspeak) File(espeakDir, path.removePrefix("$ESPEAK/")) else null
                    path.endsWith(".onnx") && '/' !in path -> File(voiceDir, MODEL).also { model = true }
                    path == TOKENS -> File(voiceDir, TOKENS).also { tokens = true }
                    path == CARD -> File(voiceDir, CARD)
                    else -> null
                } ?: continue
                // Never write outside the folders, whatever the archive says.
                val root = if (target.startsWith(espeakDir)) espeakDir else voiceDir
                if (!target.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
                    throw IOException("Unexpected path in voice package: ${entry.name}")
                }
                target.parentFile?.mkdirs()
                target.outputStream().use { tar.copyTo(it) }
            }
        }
        if (!model || !tokens) throw IOException("The voice package has no model or tokens")
        if (needEspeak) File(espeakDir, ESPEAK_DONE).writeText("ok")
    }
}
