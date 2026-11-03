package org.sonora.android.playback.dj.piper

import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Speaks with a downloaded Piper voice through sherpa-onnx, on the phone and without network. The
 * voice stays loaded between lines (loading takes a moment); one line at a time.
 */
internal class PiperSpeaker(private val store: PiperStore) {

    private companion object {
        const val TAG = "PiperSpeaker"
        const val THREADS = 2

        /** Piper's own defaults: how much the voice varies, in sound and in timing. */
        const val NOISE_SCALE = 0.667f
        const val NOISE_SCALE_W = 0.8f
    }

    private val lock = Mutex()
    private var tts: OfflineTts? = null
    private var loaded: String? = null

    /** Renders [text] with [voiceId] into a WAV file at [out]; false if the voice cannot speak. */
    suspend fun synthesize(voiceId: String, text: String, out: File, speed: Float): Boolean =
        withContext(Dispatchers.Default) {
            lock.withLock {
                // Throwable: a missing native library is an Error, and must only mean "use another voice".
                runCatching {
                    val engine = load(voiceId) ?: return@runCatching false
                    val audio = engine.generate(text, 0, speed)
                    audio.samples.isNotEmpty() && audio.save(out.path)
                }.onFailure { Log.w(TAG, "Piper could not speak with $voiceId", it) }.getOrDefault(false)
            }
        }

    /** Frees the loaded voice once any line being made is done. */
    fun release() {
        releaser.launch {
            lock.withLock {
                runCatching { tts?.release() }
                tts = null
                loaded = null
            }
        }
    }

    private val releaser = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun load(voiceId: String): OfflineTts? {
        if (loaded == voiceId) tts?.let { return it }
        if (!store.isInstalled(voiceId)) return null
        runCatching { tts?.release() }
        tts = null
        loaded = null
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = store.modelFile(voiceId).path,
                    tokens = store.tokensFile(voiceId).path,
                    dataDir = store.espeakDir.path,
                    noiseScale = NOISE_SCALE,
                    noiseScaleW = NOISE_SCALE_W,
                    lengthScale = 1f
                ),
                numThreads = THREADS,
                debug = false,
                provider = "cpu"
            )
        )
        val started = System.nanoTime()
        return OfflineTts(config = config).also {
            tts = it
            loaded = voiceId
            Log.i(TAG, "Loaded $voiceId in ${(System.nanoTime() - started) / 1_000_000} ms, ${it.sampleRate()} Hz")
        }
    }
}
