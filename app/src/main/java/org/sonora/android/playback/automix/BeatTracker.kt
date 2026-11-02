package org.sonora.android.playback.automix

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.FloatBuffer

/**
 * Runs Beat This! (CPJKU, ISMIR 2024; code and weights MIT, see assets/licenses) on the phone.
 * The model reads a log-mel spectrogram and answers, for every 20 ms frame, how likely a beat and
 * a downbeat are. Every failure returns null: a missing grid only means a plain crossfade.
 */
internal class BeatTracker(private val context: Context) {

    private companion object {
        const val TAG = "AutomixBeatTracker"
        const val MODEL_ASSET = "beat_this_final0_q8.onnx"

        /** The model was trained on 30 s chunks; the edge frames of each lack context and are dropped. */
        const val CHUNK_FRAMES = 1_500
        const val BORDER_FRAMES = 6
    }

    private var session: OrtSession? = null
    private var beatOutput = 0
    private var downbeatOutput = 1

    /** Tracks mono audio at the model rate. Called from one analysis thread at a time. */
    fun track(window: DecodedWindow): BeatGrid? {
        val spectrogram = LogMelSpectrogram.compute(window.samples) ?: return null
        val active = session() ?: return null
        val beatLogits = FloatArray(spectrogram.frames)
        val downbeatLogits = FloatArray(spectrogram.frames)
        if (!infer(active, spectrogram, beatLogits, downbeatLogits)) return null
        return BeatGrid.fromLogits(beatLogits, downbeatLogits, LogMelSpectrogram.FRAME_RATE, window.startSeconds)
    }

    private fun session(): OrtSession? {
        session?.let { return it }
        return runCatching {
            val model = modelFile()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                // The arena allocator keeps its peak forever; analysis is rare enough to allocate per run.
                setCPUArenaAllocator(false)
                setMemoryPatternOptimization(false)
            }
            OrtEnvironment.getEnvironment().createSession(model.absolutePath, options).also { created ->
                val names = created.outputNames.toList()
                val downbeatName = names.firstOrNull { it.contains("down", ignoreCase = true) }
                if (downbeatName != null && names.size >= 2) {
                    downbeatOutput = names.indexOf(downbeatName)
                    beatOutput = names.indices.first { it != downbeatOutput }
                }
                Log.i(TAG, "Beat model ready: inputs=${created.inputNames} outputs=$names")
                session = created
            }
        }.onFailure { Log.w(TAG, "Beat model unavailable", it) }.getOrNull()
    }

    /** Copies the weights out of the APK once, so the runtime can map them from disk. */
    private fun modelFile(): File {
        val target = File(File(context.filesDir, "models").apply { mkdirs() }, MODEL_ASSET)
        val expectedSize = context.assets.openFd(MODEL_ASSET).use { it.length }
        if (target.length() != expectedSize) {
            val temp = File(target.parentFile, "$MODEL_ASSET.tmp")
            context.assets.open(MODEL_ASSET).use { input -> temp.outputStream().use { input.copyTo(it) } }
            check(temp.renameTo(target)) { "Unable to store the beat model" }
        }
        return target
    }

    @Suppress("UNCHECKED_CAST")
    private fun infer(
        session: OrtSession,
        spectrogram: LogMelSpectrogram.Spectrogram,
        beatLogits: FloatArray,
        downbeatLogits: FloatArray
    ): Boolean = runCatching {
        val environment = OrtEnvironment.getEnvironment()
        val inputName = session.inputNames.first()
        val mels = LogMelSpectrogram.MELS
        val stride = CHUNK_FRAMES - 2 * BORDER_FRAMES
        var start = 0
        while (start < spectrogram.frames) {
            val length = minOf(CHUNK_FRAMES, spectrogram.frames - start)
            if (start > 0 && length <= 2 * BORDER_FRAMES) break
            val chunk = spectrogram.values.copyOfRange(start * mels, (start + length) * mels)
            val shape = longArrayOf(1, length.toLong(), mels.toLong())
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(chunk), shape).use { tensor ->
                session.run(mapOf(inputName to tensor)).use { result ->
                    val beat = (result.get(beatOutput).value as Array<FloatArray>)[0]
                    val downbeat = (result.get(downbeatOutput).value as Array<FloatArray>)[0]
                    val keepFrom = if (start == 0) 0 else BORDER_FRAMES
                    val isLast = start + length >= spectrogram.frames
                    val keepTo = if (isLast) length else length - BORDER_FRAMES
                    for (index in keepFrom until keepTo) {
                        beatLogits[start + index] = beat[index]
                        downbeatLogits[start + index] = downbeat[index]
                    }
                }
            }
            if (start + length >= spectrogram.frames) break
            start += stride
        }
        true
    }.onFailure { Log.w(TAG, "Beat inference failed", it) }.getOrDefault(false)
}
