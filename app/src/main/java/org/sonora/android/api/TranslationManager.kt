package org.sonora.android.api

import com.google.android.gms.tasks.Task
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.gson.Gson
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Disk cache for translated lyrics, keyed by `lyricsId|targetLang`.
 * Pure JVM (Gson) so it is unit-testable without an Android runtime.
 */
class TranslationCache(private val cacheDir: File?) {

    private val gson = Gson()

    private fun fileFor(key: String): File? {
        val base = cacheDir ?: return null
        val dir = File(base, "translations")
        if (!dir.exists() && !dir.mkdirs()) return null
        return File(dir, "${sha1(key)}.json")
    }

    fun read(key: String): List<LyricLine>? {
        val file = fileFor(key) ?: return null
        if (!file.exists()) return null
        return try {
            gson.fromJson(file.readText(), Array<LyricLine>::class.java)?.toList()
        } catch (_: Exception) {
            null
        }
    }

    fun write(key: String, lines: List<LyricLine>) {
        val file = fileFor(key) ?: return
        try {
            file.writeText(gson.toJson(lines))
        } catch (_: Exception) {
        }
    }

    private fun sha1(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/**
 * On-device lyrics translation using ML Kit Language Identification + Translation.
 * Never throws: on any failure it returns the original lines unchanged (translation = null).
 */
class TranslationManager(cacheDir: File? = null) {

    companion object {
        fun shouldTranslate(detected: String, target: String): Boolean {
            if (detected.isBlank() || target.isBlank()) return false
            if (detected.equals("und", ignoreCase = true)) return false
            return !detected.equals(target, ignoreCase = true)
        }
    }

    private val cache = TranslationCache(cacheDir)
    private val languageIdentifier = LanguageIdentification.getClient()
    private val translators = ConcurrentHashMap<String, Translator>()
    private val readyModels = ConcurrentHashMap.newKeySet<String>()

    /** Best-effort on-device language identification (BCP-47 tag), or null when undetermined. */
    suspend fun detectLanguage(text: String): String? {
        if (text.isBlank()) return null
        return try {
            languageIdentifier.identifyLanguage(text).awaitResult()?.takeIf { it != "und" }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun translateLines(
        lyricsId: String,
        lines: List<LyricLine>,
        targetLang: String
    ): List<LyricLine> {
        if (lines.isEmpty() || targetLang.isBlank()) return lines
        val key = "$lyricsId|${contentFingerprint(lines)}|$targetLang"
        cache.read(key)?.takeIf { it.size == lines.size }?.let { return it }
        val translated = try {
            lines.map { line ->
                if (line.text.isBlank()) line.copy(translation = null)
                else translateLine(line, targetLang)
            }
        } catch (_: Exception) {
            return lines
        }
        cache.write(key, translated)
        return translated
    }

    private fun contentFingerprint(lines: List<LyricLine>): String {
        var hash = 1125899906842597L // prime seed
        for (line in lines) {
            val text = line.text
            for (ch in text) hash = 31 * hash + ch.code
            hash = 31 * hash + line.timeMs
        }
        return hash.toString(16)
    }

    private suspend fun translateLine(line: LyricLine, targetLang: String): LyricLine {
        val detected = try {
            languageIdentifier.identifyLanguage(line.text).awaitResult() ?: "und"
        } catch (_: Exception) {
            return line.copy(translation = null)
        }
        if (!shouldTranslate(detected, targetLang)) return line.copy(translation = null)
        val source = TranslateLanguage.fromLanguageTag(detected) ?: return line.copy(translation = null)
        return try {
            val translated = translateWithModel(source, targetLang, line.text)
            if (translated.isNullOrBlank()) line.copy(translation = null)
            else line.copy(translation = translated)
        } catch (_: Exception) {
            line.copy(translation = null)
        }
    }

    private suspend fun translateWithModel(source: String, target: String, text: String): String? {
        val key = "$source|$target"
        val translator = translators.getOrPut(key) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage(target)
                    .build()
            )
        }
        if (readyModels.add(key)) {
            translator.downloadModelIfNeeded().awaitResult()
        }
        return translator.translate(text).awaitResult()
    }

    fun close() {
        try {
            languageIdentifier.close()
        } catch (_: Exception) {
        }
        translators.values.forEach {
            try {
                it.close()
            } catch (_: Exception) {
            }
        }
        translators.clear()
        readyModels.clear()
    }
}

private suspend fun <T> Task<T>.awaitResult(): T? = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { value -> cont.resume(value) }
    addOnFailureListener { error -> cont.resumeWithException(error) }
    addOnCanceledListener { cont.cancel() }
}
