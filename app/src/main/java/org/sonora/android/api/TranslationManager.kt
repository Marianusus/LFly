package org.sonora.android.api

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
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
    private val modelManager = RemoteModelManager.getInstance()

    // Only fetch translation models on Wi-Fi: never surprise the user with large
    // downloads on mobile data.
    private val downloadConditions = DownloadConditions.Builder().requireWifi().build()

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

        // Identify the song language ONCE over the whole lyrics. Detecting per line used to
        // download a ~40 MB model for every mis-detected short line, which could pile up to
        // gigabytes; one detection per song keeps it to a single source language.
        val sample = lines.asSequence()
            .map { it.text }
            .filter { it.isNotBlank() }
            .take(40)
            .joinToString(" ")
        if (sample.isBlank()) return lines
        val detected = detectLanguage(sample) ?: return lines
        if (!shouldTranslate(detected, targetLang)) return lines
        val source = TranslateLanguage.fromLanguageTag(detected) ?: return lines
        val target = TranslateLanguage.fromLanguageTag(targetLang) ?: return lines
        if (source == target) return lines

        val translated = try {
            lines.map { line ->
                if (line.text.isBlank()) line.copy(translation = null)
                else translateLine(line, source, target)
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

    private suspend fun translateLine(line: LyricLine, source: String, target: String): LyricLine {
        return try {
            val translated = translateWithModel(source, target, line.text)
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
            translator.downloadModelIfNeeded(downloadConditions).awaitResult()
        }
        return translator.translate(text).awaitResult()
    }

    /** Languages whose translation model is currently downloaded on this device. */
    suspend fun downloadedLanguages(): List<String> = try {
        modelManager.getDownloadedModels(TranslateRemoteModel::class.java).awaitResult()
            ?.map { it.language }
            ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    /** Deletes every downloaded translation model, freeing the space they occupy (~40 MB each). */
    suspend fun deleteAllModels(): Int {
        val models = try {
            modelManager.getDownloadedModels(TranslateRemoteModel::class.java).awaitResult()
        } catch (_: Exception) {
            return 0
        } ?: return 0
        var deleted = 0
        models.forEach { model ->
            try {
                modelManager.deleteDownloadedModel(model).awaitResult()
                deleted++
            } catch (_: Exception) {
            }
        }
        translators.values.forEach {
            try {
                it.close()
            } catch (_: Exception) {
            }
        }
        translators.clear()
        readyModels.clear()
        return deleted
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
