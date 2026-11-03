package org.sonora.android.playback.dj

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.sonora.android.data.NetworkMonitor
import org.sonora.android.playback.dj.piper.PiperCatalog
import org.sonora.android.playback.dj.piper.PiperSpeaker
import org.sonora.android.playback.dj.piper.PiperStore
import org.sonora.android.playback.AudioRouting
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** The DJ's voice: the phone's text-to-speech, played over the music. Main thread only. */
class DjVoice(private val context: Context) {

    private val piperStore = PiperStore.getInstance(context)
    private val piper = PiperSpeaker(piperStore)
    private var tts: TextToSpeech? = null
    private var init: CompletableDeferred<Boolean>? = null
    private var ready = false
    private var player: ExoPlayer? = null
    private var counter = 0

    /** Synthesis callbacks arrive on a binder thread; each one completes its line's deferred. */
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            utteranceId?.let { pending.remove(it)?.complete(true) }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            utteranceId?.let { pending.remove(it)?.complete(false) }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            utteranceId?.let { pending.remove(it)?.complete(false) }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            utteranceId?.let { pending.remove(it)?.complete(false) }
        }
    }

    /**
     * Speaks [text] in [language] ("it", "en" or "ro") over the music and suspends until the line is
     * over. [onDuck] receives the music level while it speaks: a short ramp down to about 0.3, then
     * back up, always ending with 1f (also when cancelled). False when no voice is available, in which
     * case [onDuck] is never called below 1f.
     */
    suspend fun speak(
        text: String,
        language: String,
        onDuck: (Float) -> Unit,
        /** The voice picked in Settings, by name; null lets the DJ choose the best one. */
        preferredVoice: String? = null
    ): Boolean {
        var level = 1f
        var file: File? = null
        try {
            if (text.isBlank()) return false
            // A downloaded neural voice first; the phone's own speech engine when there is none.
            file = piperLine(text, language, preferredVoice) ?: systemLine(
                text = text,
                language = language,
                preferredVoice = preferredVoice?.takeUnless { it.startsWith(PiperCatalog.PREFIX) }
            ) ?: return false

            val voice = buildPlayer()
            player = voice
            val ended = CompletableDeferred<Boolean>()
            voice.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_ENDED -> ended.complete(true)
                        // Only stop() brings a prepared player back to idle.
                        Player.STATE_IDLE -> ended.complete(false)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    ended.complete(false)
                }
            })
            voice.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            voice.prepare()
            ramp(level, DUCK_LEVEL, DUCK_DOWN_MS) { level = it; onDuck(it) }
            voice.play()
            return withTimeoutOrNull(MAX_LINE_MS) { ended.await() } ?: false
        } finally {
            player?.release()
            player = null
            file?.delete()
            withContext(NonCancellable) {
                if (level < 1f) ramp(level, 1f, DUCK_UP_MS, onDuck)
                onDuck(1f)
            }
        }
    }

    /**
     * The line rendered by a Piper voice: the one picked in Settings when it is downloaded, else the
     * best downloaded one for the language. Null when the listener picked a phone voice, none is
     * downloaded, or Piper fails.
     */
    private suspend fun piperLine(text: String, language: String, preferredVoice: String?): File? {
        val lang = DjScript.languageOf(language)
        val picked = PiperCatalog.fromPreference(preferredVoice)
        if (preferredVoice != null && picked == null) return null
        val voice = picked?.takeIf { it.language == lang && piperStore.isInstalled(it.id) }
            ?: piperStore.installed(lang).firstOrNull()
            ?: return null
        val file = newLineFile()
        if (piper.synthesize(voice.id, text, file, PIPER_SPEED)) {
            Log.i(TAG, "Speaking with Piper ${voice.id}")
            return file
        }
        file.delete()
        return null
    }

    /** The line rendered by the phone's speech engine: a network voice when online, else an offline one. */
    private suspend fun systemLine(text: String, language: String, preferredVoice: String?): File? {
        val engine = engine() ?: return null
        val locale = localeFor(language)
        val online = NetworkMonitor.isOnline.value
        val chosen = useLanguage(engine, locale, online, preferredVoice) ?: return null
        val file = synthesize(engine, text, if (chosen.networkOnly) NETWORK_SYNTH_TIMEOUT_MS else SYNTH_TIMEOUT_MS)
        if (file != null || !chosen.networkOnly) return file
        // The connection dropped or the server did not answer: the line is said offline instead.
        Log.i(TAG, "Network voice ${chosen.name} failed; falling back to an offline voice")
        if (useLanguage(engine, locale, online = false, preferred = null) == null) return null
        return synthesize(engine, text, SYNTH_TIMEOUT_MS)
    }

    /** Stops the current line at once; the next [speak] still works. */
    fun stop() {
        tts?.stop()
        player?.stop()
        pending.values.forEach { it.complete(false) }
        pending.clear()
    }

    /** Frees the speech engines for good. */
    fun release() {
        stop()
        piper.release()
        tts?.shutdown()
        tts = null
        init = null
        ready = false
    }

    /** The speech engine, started on first use; null when it does not come up within a few seconds. */
    private suspend fun engine(): TextToSpeech? {
        tts?.takeIf { ready }?.let { return it }
        val started = init ?: CompletableDeferred<Boolean>().also { deferred ->
            init = deferred
            val onInit = TextToSpeech.OnInitListener { status -> deferred.complete(status == TextToSpeech.SUCCESS) }
            // Google's engine has the natural network voices; Samsung phones default to their own.
            tts = if (isInstalled(GOOGLE_TTS)) {
                TextToSpeech(context.applicationContext, onInit, GOOGLE_TTS)
            } else {
                TextToSpeech(context.applicationContext, onInit)
            }
        }
        val ok = withTimeoutOrNull(INIT_TIMEOUT_MS) { started.await() } ?: false
        val engine = tts
        if (!ok || engine == null) {
            // Start over next time: the engine may have been updated or installed meanwhile.
            engine?.shutdown()
            tts = null
            init = null
            return null
        }
        engine.setOnUtteranceProgressListener(progress)
        engine.setSpeechRate(SPEECH_RATE)
        engine.setPitch(SPEECH_PITCH)
        ready = true
        return engine
    }

    /**
     * Sets the voice for [locale]: the listener's [preferred] one when it can speak now, otherwise the
     * most natural one ([online] allows the engine's network voices). Null when nothing speaks it.
     */
    private fun useLanguage(engine: TextToSpeech, locale: Locale, online: Boolean, preferred: String?): VoiceOption<Voice>? {
        val voices = runCatching { engine.voices }.getOrNull()
        if (voices.isNullOrEmpty()) {
            // Some engines list no voices at all but still speak the language.
            val availability = engine.isLanguageAvailable(locale)
            if (availability == TextToSpeech.LANG_MISSING_DATA || availability == TextToSpeech.LANG_NOT_SUPPORTED) return null
            if (engine.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) return null
            val voice = engine.voice ?: return null
            return VoiceOption(voice = voice, locale = locale, quality = voice.quality, latency = voice.latency, offline = true)
        }
        val options = voices.map { it.option() }
        val best = chooseOption(options, locale, online, preferred) ?: return null
        if (engine.setVoice(best.voice) != TextToSpeech.SUCCESS) return null
        Log.i(TAG, "Speaking with ${best.name} (quality ${best.quality}, ${if (best.networkOnly) "network" else "offline"})")
        return best
    }

    /** The voices that can speak [language], most natural first, for the Settings picker. */
    suspend fun voicesFor(language: String): List<DjVoiceChoice> {
        val engine = engine() ?: return emptyList()
        val locale = localeFor(language)
        val voices = runCatching { engine.voices }.getOrNull().orEmpty()
        return voices.map { it.option() }
            .filter { (it.offline || it.networkOnly) && sameLanguage(it.locale, locale) }
            .sortedWith(
                compareByDescending<VoiceOption<Voice>> { it.locale.country.equals(locale.country, ignoreCase = true) }
                    .thenByDescending { it.quality }
                    .thenBy { it.networkOnly }
                    .thenBy { it.name }
            )
            .map { DjVoiceChoice(name = it.name, quality = it.quality, online = it.networkOnly) }
    }

    /** Renders [text] to a file in the cache and waits for the engine to finish it. */
    private suspend fun synthesize(engine: TextToSpeech, text: String, timeoutMs: Long): File? {
        val file = newLineFile()
        val id = file.nameWithoutExtension
        val done = CompletableDeferred<Boolean>()
        pending[id] = done
        if (engine.synthesizeToFile(text, Bundle(), file, id) != TextToSpeech.SUCCESS) {
            pending.remove(id)
            return null
        }
        val ok = withTimeoutOrNull(timeoutMs) { done.await() } ?: false
        pending.remove(id)
        return file.takeIf { ok && it.length() > 0 } ?: run {
            file.delete()
            null
        }
    }

    /** A fresh file for one line; lines from earlier sessions or a crash are never played again. */
    private suspend fun newLineFile(): File {
        val dir = File(context.cacheDir, "dj")
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }
        }
        return File(dir, "dj-${System.currentTimeMillis()}-${counter++}.wav")
    }

    /** A small player for one line, on the same output as the music. */
    @OptIn(UnstableApi::class)
    private fun buildPlayer(): ExoPlayer {
        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()
        // No audio focus: requesting it would pause the music, which already holds it.
        return ExoPlayer.Builder(context)
            .setAudioAttributes(attributes, /* handleAudioFocus = */ false)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
            .apply {
                AudioRouting.infoFor(context, AudioRouting.selectedId.value)?.let(::setPreferredAudioDevice)
            }
    }

    /** Moves the music level from [from] to [to] in small steps over [durationMs]. */
    private suspend fun ramp(from: Float, to: Float, durationMs: Long, onLevel: (Float) -> Unit) {
        val steps = (durationMs / RAMP_STEP_MS).toInt().coerceAtLeast(1)
        for (step in 1..steps) {
            onLevel(from + (to - from) * step / steps)
            delay(RAMP_STEP_MS)
        }
    }

    private fun Voice.option() = VoiceOption(
        voice = this,
        locale = locale,
        quality = quality,
        latency = latency,
        offline = !isNetworkConnectionRequired &&
            features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true,
        name = name,
        networkOnly = isNetworkConnectionRequired
    )

    private fun isInstalled(packageName: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess

    private companion object {
        const val TAG = "DjVoice"
        const val GOOGLE_TTS = "com.google.android.tts"
        const val INIT_TIMEOUT_MS = 3_000L
        const val SYNTH_TIMEOUT_MS = 10_000L
        /** Network voices are made on Google's servers: a line takes a little longer to come back. */
        const val NETWORK_SYNTH_TIMEOUT_MS = 15_000L
        const val MAX_LINE_MS = 30_000L
        const val DUCK_LEVEL = 0.3f
        const val DUCK_DOWN_MS = 300L
        const val DUCK_UP_MS = 600L
        const val RAMP_STEP_MS = 20L
        /** A touch quicker than the engine's default, closer to a radio host than to a reader. */
        const val SPEECH_RATE = 1.05f
        /** Piper reads a little slowly for a radio host. */
        const val PIPER_SPEED = 1.08f
        const val SPEECH_PITCH = 1.0f
    }
}

/** The locale the DJ speaks for a language code; English for anything unknown, as [DjScript] does. */
internal fun localeFor(language: String): Locale = when (DjScript.languageOf(language)) {
    "it" -> Locale.forLanguageTag("it-IT")
    "ro" -> Locale.forLanguageTag("ro-RO")
    else -> Locale.forLanguageTag("en-US")
}

/** What [chooseVoice] needs to know about a voice, so the choice can be tested off-device. */
internal data class VoiceOption<T>(
    val voice: T,
    val locale: Locale,
    val quality: Int,
    val latency: Int,
    val offline: Boolean,
    val name: String = "",
    /** Spoken by the engine's servers: natural, but only with a connection. */
    val networkOnly: Boolean = false
)

/** A voice the listener can pick for the DJ in Settings. */
data class DjVoiceChoice(val name: String, val quality: Int, val online: Boolean)

/**
 * The best voice for [locale] that works without network: same country first, then highest quality,
 * then lowest latency. Null when no offline voice speaks the language.
 */
internal fun <T> chooseVoice(
    options: List<VoiceOption<T>>,
    locale: Locale,
    online: Boolean = false,
    preferred: String? = null
): T? = chooseOption(options, locale, online, preferred)?.voice

/**
 * The voice to speak [locale] with. The listener's [preferred] voice wins when it speaks the
 * language and can speak now; otherwise the most natural voice that can: with a connection that
 * includes the engine's network voices, offline only the installed ones. Same country first, then
 * highest quality, then lowest latency.
 */
internal fun <T> chooseOption(
    options: List<VoiceOption<T>>,
    locale: Locale,
    online: Boolean,
    preferred: String?
): VoiceOption<T>? {
    val usable = options.filter { (it.offline || (online && it.networkOnly)) && sameLanguage(it.locale, locale) }
    preferred?.let { name -> usable.firstOrNull { it.name == name }?.let { return it } }
    return usable.sortedWith(
        compareByDescending<VoiceOption<T>> { it.locale.country.equals(locale.country, ignoreCase = true) }
            .thenByDescending { it.quality }
            .thenBy { it.latency }
    ).firstOrNull()
}

/** Engines report languages as "it" or "ita"; both mean Italian. */
private fun sameLanguage(a: Locale, b: Locale): Boolean {
    if (a.language.equals(b.language, ignoreCase = true)) return true
    val a3 = runCatching { a.isO3Language }.getOrNull()
    val b3 = runCatching { b.isO3Language }.getOrNull()
    return a3 != null && a3.equals(b3, ignoreCase = true)
}
