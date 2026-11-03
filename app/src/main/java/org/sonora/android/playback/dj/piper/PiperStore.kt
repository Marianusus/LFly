package org.sonora.android.playback.dj.piper

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** Where a Piper voice stands on this phone. */
data class PiperStatus(
    val installed: Boolean = false,
    /** 0..1 while downloading, null otherwise. */
    val progress: Float? = null,
    /** The last download failed. */
    val failed: Boolean = false
)

/**
 * The Piper voices on this phone: downloads them on request, unpacks them into the app's own
 * storage and says which are ready. One per process.
 */
class PiperStore private constructor(context: Context) {

    companion object {
        private const val TAG = "PiperStore"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000

        @Volatile
        private var instance: PiperStore? = null

        fun getInstance(context: Context): PiperStore =
            instance ?: synchronized(this) {
                instance ?: PiperStore(context.applicationContext).also { instance = it }
            }
    }

    private val root = File(context.filesDir, "piper")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    private val _status = MutableStateFlow(PiperCatalog.voices.associate { it.id to PiperStatus(installed = isInstalled(it.id)) })
    val status: StateFlow<Map<String, PiperStatus>> = _status.asStateFlow()

    /** The eSpeak pronunciation data every Piper voice needs. */
    val espeakDir: File get() = File(root, PiperPackage.ESPEAK)

    fun modelFile(id: String): File = File(voiceDir(id), PiperPackage.MODEL)

    fun tokensFile(id: String): File = File(voiceDir(id), PiperPackage.TOKENS)

    fun isInstalled(id: String): Boolean =
        modelFile(id).isFile && tokensFile(id).isFile && File(espeakDir, PiperPackage.ESPEAK_DONE).isFile

    /** Ready voices for [language], best first. */
    fun installed(language: String): List<PiperVoice> = PiperCatalog.forLanguage(language).filter { isInstalled(it.id) }

    /** Starts downloading [id] unless it is installed or already on its way. */
    fun download(id: String) {
        val voice = PiperCatalog.byId(id) ?: return
        if (isInstalled(id) || jobs[id]?.isActive == true) return
        set(id) { PiperStatus(progress = 0f) }
        jobs[id] = scope.launch {
            try {
                fetch(voice)
                set(id) { PiperStatus(installed = isInstalled(id)) }
                Log.i(TAG, "Voice ${voice.id} ready")
            } catch (e: Exception) {
                ensureActive()
                Log.w(TAG, "Could not download ${voice.id}", e)
                set(id) { PiperStatus(installed = false, failed = true) }
            } finally {
                jobs.remove(id)
            }
        }
    }

    /** Removes [id]; the shared eSpeak data stays for the other voices. */
    fun delete(id: String) {
        jobs.remove(id)?.cancel()
        voiceDir(id).deleteRecursively()
        set(id) { PiperStatus(installed = false) }
    }

    private fun fetch(voice: PiperVoice) {
        val connection = (URL(voice.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
        }
        try {
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode} for ${voice.url}")
            val total = connection.contentLengthLong.takeIf { it > 0 }
            // Unpacked next to the final folders, then swapped in: a cut download leaves nothing half done.
            val voiceTmp = File(root, ".tmp-${voice.id}").apply { deleteRecursively() }
            val needEspeak = !File(espeakDir, PiperPackage.ESPEAK_DONE).isFile
            val espeakTmp = File(root, ".tmp-espeak-${voice.id}").apply { deleteRecursively() }
            try {
                val counting = CountingStream(connection.inputStream) { read ->
                    if (total != null) set(voice.id) { it.copy(progress = (read.toFloat() / total).coerceIn(0f, 1f)) }
                }
                counting.use { PiperPackage.unpack(it, voiceTmp, espeakTmp, needEspeak) }
                if (needEspeak) {
                    espeakDir.deleteRecursively()
                    check(espeakTmp.renameTo(espeakDir)) { "Unable to store the pronunciation data" }
                }
                voiceDir(voice.id).deleteRecursively()
                check(voiceTmp.renameTo(voiceDir(voice.id))) { "Unable to store the voice" }
            } finally {
                voiceTmp.deleteRecursively()
                espeakTmp.deleteRecursively()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun voiceDir(id: String) = File(root, id)

    private fun set(id: String, change: (PiperStatus) -> PiperStatus) {
        _status.update { it + (id to change(it[id] ?: PiperStatus())) }
    }

    /** Reports how much of the download has come in, at most every 256 KB. */
    private class CountingStream(stream: InputStream, private val onRead: (Long) -> Unit) : FilterInputStream(stream) {
        private var read = 0L
        private var reported = 0L

        override fun read(): Int = super.read().also { if (it >= 0) count(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count(it) }

        private fun count(bytes: Int) {
            read += bytes
            if (read - reported >= 256 * 1024) {
                reported = read
                onRead(read)
            }
        }
    }
}
