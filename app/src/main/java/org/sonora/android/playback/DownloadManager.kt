package org.sonora.android.playback

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.sonora.android.api.AudioQuality
import org.sonora.android.api.HttpClientProvider
import org.sonora.android.api.StreamResolver
import org.sonora.android.api.Track
import org.sonora.android.api.lyrics.sources.LyricsSource
import org.sonora.android.data.MusicRepository
import org.sonora.android.data.NetworkMonitor
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Lifecycle state of a single offline download. */
enum class DownloadStatus { NONE, QUEUED, DOWNLOADING, DOWNLOADED, FAILED }

/** UI-facing progress for a track download. [progress] is 0f..1f, or 0f when indeterminate. */
data class DownloadProgress(
    val status: DownloadStatus = DownloadStatus.NONE,
    val progress: Float = 0f,
    val message: String? = null
) {
    val isDownloaded: Boolean get() = status == DownloadStatus.DOWNLOADED
    val isActive: Boolean
        get() = status == DownloadStatus.QUEUED || status == DownloadStatus.DOWNLOADING
}

/** Lifecycle state of the offline lyrics belonging to a downloaded track. */
enum class OfflineLyricsStatus { NONE, FETCHING, FOUND, NOT_FOUND, ERROR }

/** UI-facing snapshot of the offline lyrics state for a single track. */
data class OfflineLyricsInfo(
    val status: OfflineLyricsStatus,
    val source: String? = null,
    val fetchedAt: Long = 0L
)

/** Persisted record for a completed download (full track snapshot keeps the offline UI usable). */
data class DownloadEntry(
    val track: Track,
    val fileName: String,
    val coverFileName: String? = null,
    val quality: AudioQuality = AudioQuality.HIGH,
    val bytes: Long = 0L,
    val downloadedAt: Long = System.currentTimeMillis()
)

/**
 * Application-wide offline download engine.
 *
 * Files are written to the app-specific external `Music` folder (no runtime permission needed),
 * so they are also picked up by [MusicRepository.syncLocalMusic] and the existing "downloaded"
 * playlist. Exact playback mapping is kept here by track id, independent of fuzzy title matching.
 */
object DownloadManager {

    private const val TAG = "DownloadManager"
    private const val MANIFEST_FILE = "downloads.json"
    private const val MUSIC_DIR = "music"
    private const val MAX_PARALLEL_DOWNLOADS = 3
    private const val PROGRESS_CHUNK = 64 * 1024

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val semaphore = Semaphore(MAX_PARALLEL_DOWNLOADS)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val lyricsJobs = ConcurrentHashMap<String, Job>()
    private val lyricsSemaphore = Semaphore(2)
    private val lock = Any()
    private val entries = LinkedHashMap<String, DownloadEntry>()

    private var appContext: Context? = null
    private var localSyncJob: Job? = null

    private val _states = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val states: StateFlow<Map<String, DownloadProgress>> = _states.asStateFlow()

    private val _lyricsStates = MutableStateFlow<Map<String, OfflineLyricsInfo>>(emptyMap())
    val lyricsStates: StateFlow<Map<String, OfflineLyricsInfo>> = _lyricsStates.asStateFlow()

    private val _downloadedTracks = MutableStateFlow<List<Track>>(emptyList())
    val downloadedTracks: StateFlow<List<Track>> = _downloadedTracks.asStateFlow()

    private val _totalBytes = MutableStateFlow(0L)
    val totalBytes: StateFlow<Long> = _totalBytes.asStateFlow()

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        // Load synchronously so a playback request during cold start already sees the files.
        synchronized(lock) {
            loadManifest()
            publish()
        }
        scope.launch {
            synchronized(lock) {
                pruneMissingFiles()
                cleanupPartialFiles()
                publish()
            }
        }
    }

    /** Absolute path of the downloaded audio file for [trackId], or null when absent. */
    fun localPathFor(trackId: String): String? {
        val context = appContext ?: return null
        val entry = synchronized(lock) { entries[trackId] } ?: return null
        val file = File(musicDir(context), entry.fileName)
        return if (file.exists() && file.length() > 0L) file.absolutePath else null
    }

    fun isDownloaded(trackId: String): Boolean = localPathFor(trackId) != null

    /**
     * Resolves [track] back to the original catalog snapshot that produced its download.
     *
     * A favorite saved while an offline copy is playing keeps the Local id/file path, but the
     * download entry still holds the provider identity (e.g. `yt_...`). The lookup matches the
     * entry id first, then the audio path, accepting both `file://` URIs and absolute paths.
     * Returns null when no download matches. Thread-safe and never throws.
     */
    fun canonicalTrackFor(track: Track): Track? {
        return try {
            val context = appContext ?: return null
            synchronized(lock) {
                canonicalEntryFor(track, entries) { entry ->
                    runCatching { File(musicDir(context), entry.fileName).canonicalPath }.getOrNull()
                }?.track
            }
        } catch (e: Exception) {
            Log.d(TAG, "Unable to resolve canonical track for '${track.id}': ${e.message}")
            null
        }
    }

    /** Absolute path of the downloaded cover for [trackId], or null when absent. */
    fun localCoverPathFor(trackId: String): String? {
        val context = appContext ?: return null
        val entry = synchronized(lock) { entries[trackId] } ?: return null
        val name = entry.coverFileName ?: return null
        val file = File(musicDir(context), name)
        return if (file.exists() && file.length() > 0L) file.absolutePath else null
    }

    fun progressFor(trackId: String): DownloadProgress =
        _states.value[trackId] ?: DownloadProgress()

    /** Lyrics saved alongside a download, or null when none were requested/found. */
    fun lyricsFor(track: Track): OfflineLyrics? {
        val context = appContext ?: return null
        val canonical = canonicalTrackFor(track) ?: track
        return DownloadLyricsStore.default(context).read(canonical.id)
    }

    fun hasLyrics(trackId: String): Boolean {
        val context = appContext ?: return false
        return DownloadLyricsStore.default(context).has(trackId)
    }

    /** Loads the stored lyrics status for [trackIds] that are not already tracked in memory. */
    fun ensureLyricsLoaded(trackIds: Collection<String>) {
        if (trackIds.isEmpty()) return
        val context = appContext ?: return
        scope.launch(Dispatchers.IO) {
            val missing = trackIds.distinct().filterNot { it in _lyricsStates.value }
            if (missing.isEmpty()) return@launch
            val store = DownloadLyricsStore.default(context)
            val loaded = missing.associateWith { id -> offlineLyricsInfo(store.read(id)) }
            _lyricsStates.update { current ->
                val next = current.toMutableMap()
                loaded.forEach { (id, info) -> if (id !in next) next[id] = info }
                next
            }
        }
    }

    /** Updates the in-memory lyrics status after the player persisted [lyrics] for [trackId]. */
    fun recordLyrics(trackId: String, lyrics: OfflineLyrics) {
        _lyricsStates.update { it + (trackId to offlineLyricsInfo(lyrics)) }
    }

    /**
     * Resolves and persists offline lyrics for [track].
     *
     * Returns the stored lyrics immediately when present (unless [force]), otherwise performs
     * the same network lookup the player uses. A `null` result means a retryable failure and is
     * reflected as [OfflineLyricsStatus.ERROR]; a negative marker ([OfflineLyrics.notFound]) is
     * written to disk and returned as a normal result.
     */
    suspend fun fetchLyricsNow(
        track: Track,
        sourceOverride: LyricsSource? = null,
        force: Boolean = false
    ): OfflineLyrics? {
        val context = appContext ?: return null
        val canonical = canonicalTrackFor(track) ?: track
        val store = DownloadLyricsStore.default(context)

        if (!force) {
            val existing = withContext(Dispatchers.IO) { store.read(canonical.id) }
            if (existing != null && !existing.notFound) {
                recordLyrics(canonical.id, existing)
                return existing
            }
        }

        _lyricsStates.update {
            it + (canonical.id to OfflineLyricsInfo(OfflineLyricsStatus.FETCHING))
        }

        try {
            val repository = MusicRepository.getInstance(context)
            val override = sourceOverride ?: repository.lyricsSourceFor(canonical.id)
            val sources = override?.let { setOf(it) } ?: repository.lyricsSources.value
            val order = override?.let { listOf(it) } ?: repository.lyricsSourceOrder.value
            val result = lyricsSemaphore.withPermit {
                DownloadLyricsFetcher.fetch(
                    track = canonical,
                    sources = sources,
                    order = order,
                    prioritizeSyllableSync = repository.prioritizeSyllableSync.value,
                    apiKey = repository.paxsenixApiKey.value,
                )
            }
            if (result == null) {
                _lyricsStates.update {
                    it + (canonical.id to OfflineLyricsInfo(OfflineLyricsStatus.ERROR))
                }
                return null
            }
            withContext(Dispatchers.IO) { store.write(canonical.id, result) }
            recordLyrics(canonical.id, result)
            Log.i(TAG, "Lyrics for '${canonical.title}': ${if (result.notFound) "not found" else result.source}")
            return result
        } catch (cancelled: CancellationException) {
            _lyricsStates.update {
                it + (canonical.id to OfflineLyricsInfo(OfflineLyricsStatus.NONE))
            }
            throw cancelled
        }
    }

    /** Deletes the stored lyrics for [track] and resets its in-memory status. */
    fun removeLyrics(track: Track) {
        val context = appContext ?: return
        val canonical = canonicalTrackFor(track) ?: track
        DownloadLyricsStore.default(context).delete(canonical.id)
        _lyricsStates.update {
            it + (canonical.id to OfflineLyricsInfo(OfflineLyricsStatus.NONE))
        }
    }

    /** Starts downloading [track] unless it is already downloading/downloaded. */
    fun download(
        track: Track,
        quality: AudioQuality,
        wifiOnly: Boolean = true,
        withLyrics: Boolean = false
    ) {
        if (track.provider.equals("Local", ignoreCase = true)) return
        if (isDownloaded(track.id)) return
        if (jobs[track.id]?.isActive == true) return

        setState(track.id, DownloadProgress(DownloadStatus.QUEUED))

        jobs[track.id] = scope.launch {
            try {
                semaphore.withPermit { performDownload(track, quality, wifiOnly) }
                if (withLyrics) fetchLyrics(track)
            } catch (e: CancellationException) {
                setState(track.id, DownloadProgress())
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Download failed for '${track.title}': ${e.message}")
                setState(
                    track.id,
                    DownloadProgress(DownloadStatus.FAILED, message = e.message ?: "Download failed")
                )
            } finally {
                jobs.remove(track.id)
            }
        }
    }

    fun downloadAll(
        tracks: List<Track>,
        quality: AudioQuality,
        wifiOnly: Boolean = true,
        withLyrics: Boolean = false
    ) {
        tracks.forEach { download(it, quality, wifiOnly, withLyrics) }
    }

    fun cancel(trackId: String) {
        jobs[trackId]?.cancel()
        jobs.remove(trackId)
        setState(trackId, DownloadProgress())
    }

    /** Removes the downloaded file (and its cached cover) from disk. */
    fun remove(trackId: String) {
        jobs[trackId]?.cancel()
        jobs.remove(trackId)
        lyricsJobs.remove(trackId)?.cancel()
        val context = appContext ?: return
        DownloadLyricsStore.default(context).delete(trackId)
        _lyricsStates.update { it - trackId }
        synchronized(lock) {
            val entry = entries.remove(trackId)
            if (entry != null) {
                File(musicDir(context), entry.fileName).delete()
                entry.coverFileName?.let { File(musicDir(context), it).delete() }
                saveManifest()
            }
        }
        _states.update { it - trackId }
        publish()
        runCatching { MusicRepository.getInstance(context).repairMissingLocalFavorites() }
        scheduleLocalSync()
    }

    /** True when [path] points to an audio file inside the app-managed downloads folder. */
    fun isManagedLocalPath(path: String?): Boolean {
        val context = appContext ?: return false
        val raw = path?.removePrefix("file://")?.takeIf { it.isNotBlank() } ?: return false
        val dir = musicDir(context)
        return runCatching {
            File(raw).canonicalPath.startsWith(dir.canonicalPath + File.separator)
        }.getOrDefault(false)
    }

    /** Deletes an app-managed downloaded file (audio + cached cover) and rescans the library. */
    fun deleteManagedLocalFile(path: String) {
        val context = appContext ?: return
        if (!isManagedLocalPath(path)) return
        val file = File(path.removePrefix("file://"))
        scope.launch(Dispatchers.IO) {
            val manifestKey = synchronized(lock) {
                val key = entries.entries.firstOrNull { it.value.fileName == file.name }?.key
                if (key != null) {
                    entries.remove(key)
                    lyricsJobs.remove(key)?.cancel()
                    DownloadLyricsStore.default(context).delete(key)
                    _states.update { it - key }
                }
                val base = file.nameWithoutExtension
                val hash = Regex("\\[([0-9a-fA-F]{8})]").find(base)?.groupValues?.get(1)
                val coverNames = buildList {
                    listOf("jpg", "jpeg", "png", "webp").forEach { add("$base.$it") }
                    hash?.let { h -> listOf("jpg", "jpeg", "png", "webp").forEach { add("$h.$it") } }
                }
                coverNames.forEach { name ->
                    File(musicDir(context), name).takeIf { it.isFile }?.delete()
                }
                File(context.filesDir, "local_covers/${"local_${file.name.hashCode()}".hashCode()}.jpg")
                    .takeIf { it.isFile }?.delete()
                file.delete()
                saveManifest()
                publish()
                key
            }
            val repository = MusicRepository.getInstance(context)
            runCatching { repository.pruneDeletedLocalTrack(file.absolutePath, manifestKey) }
            runCatching { repository.repairMissingLocalFavorites() }
            runCatching { repository.syncLocalMusic() }
        }
    }

    fun removeAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        lyricsJobs.values.forEach { it.cancel() }
        lyricsJobs.clear()
        val context = appContext ?: return
        DownloadLyricsStore.default(context).clear()
        synchronized(lock) {
            entries.values.forEach { entry ->
                File(musicDir(context), entry.fileName).delete()
                entry.coverFileName?.let { File(musicDir(context), it).delete() }
            }
            entries.clear()
            saveManifest()
        }
        _states.value = emptyMap()
        publish()
        runCatching { MusicRepository.getInstance(context).repairMissingLocalFavorites() }
        scheduleLocalSync()
    }

    /** Deletes every entry and file whose audio is missing, keeping the manifest truthful. */
    fun refresh() {
        scope.launch {
            synchronized(lock) {
                pruneMissingFiles()
                publish()
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Download pipeline
    // ---------------------------------------------------------------------------------------

    private suspend fun performDownload(
        track: Track,
        quality: AudioQuality,
        wifiOnly: Boolean
    ) {
        val context = appContext ?: throw IOException("Download manager not initialized")

        if (!NetworkMonitor.isOnline.value) {
            throw IOException("Nessuna connessione a Internet")
        }
        if (wifiOnly && !NetworkMonitor.isWifiOrEthernet.value) {
            throw IOException("Connessione Wi-Fi richiesta")
        }

        setState(track.id, DownloadProgress(DownloadStatus.DOWNLOADING, 0f))

        val source = StreamResolver.getInstance().resolveStreamForDownload(track, quality)
            ?: throw IOException("Stream non disponibile")
        val url = source.url
        if (url.isBlank()) throw IOException("Stream non disponibile")

        val extension = extensionFor(source.format)
        val fileName = buildFileName(track, extension)
        val directory = musicDir(context)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Cartella di download non accessibile")
        }
        val target = File(directory, fileName)
        val part = File(directory, "$fileName.part")

        try {
            downloadToFile(url, part) { written, total ->
                val progress = if (total > 0L) (written.toFloat() / total).coerceIn(0f, 1f) else 0f
                setState(track.id, DownloadProgress(DownloadStatus.DOWNLOADING, progress))
            }
            if (part.length() <= 0L) throw IOException("File scaricato vuoto")
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IOException("Impossibile salvare il file")
        } catch (e: Exception) {
            part.delete()
            throw e
        }

        val coverFileName = downloadCover(track, source.resolvedCoverUrl, target)
        val entry = DownloadEntry(
            track = track,
            fileName = fileName,
            coverFileName = coverFileName,
            quality = quality,
            bytes = target.length(),
            downloadedAt = System.currentTimeMillis()
        )
        synchronized(lock) {
            entries[track.id] = entry
            saveManifest()
            publish()
        }
        setState(track.id, DownloadProgress(DownloadStatus.DOWNLOADED, 1f))
        Log.i(TAG, "Downloaded '${track.title}' -> $fileName (${target.length()} bytes)")
        scheduleLocalSync()
    }

    private suspend fun downloadToFile(
        url: String,
        part: File,
        onProgress: (written: Long, total: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        HttpClientProvider.okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("Risposta vuota")
            val total = body.contentLength()
            body.byteStream().use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(PROGRESS_CHUNK)
                    var written = 0L
                    var lastReportedPct = -1
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        written += read
                        val pct = if (total > 0L) ((written * 100) / total).toInt() else -1
                        if (pct != lastReportedPct) {
                            lastReportedPct = pct
                            onProgress(written, total)
                        }
                    }
                    output.flush()
                }
            }
        }
    }

    /**
     * Saves a cover for the downloaded track, trying the track art, the resolved stream art,
     * and finally the artwork embedded in the audio file itself.
     */
    private suspend fun downloadCover(
        track: Track,
        resolvedCoverUrl: String?,
        audioFile: File
    ): String? {
        val context = appContext ?: return null
        val name = "${track.id.hashCode().toUInt().toString(16)}.jpg"
        val target = File(musicDir(context), name)

        val candidates = listOfNotNull(
            track.coverUrl,
            resolvedCoverUrl
        ).map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("file://") || it.startsWith("/") }
            .distinct()

        for (url in candidates) {
            val bytes = fetchBytes(url)
            if (bytes != null && bytes.isNotEmpty()) {
                target.writeBytes(bytes)
                return name
            }
        }

        // Last resort: artwork embedded in the downloaded audio container.
        val embedded = extractEmbeddedArtwork(audioFile)
        if (embedded != null && embedded.isNotEmpty()) {
            target.writeBytes(embedded)
            return name
        }

        Log.d(TAG, "No cover available for '${track.title}'")
        return null
    }

    private suspend fun fetchBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).build()
            HttpClientProvider.okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.bytes()
            }
        } catch (e: Exception) {
            Log.d(TAG, "Cover fetch failed ($url): ${e.message}")
            null
        }
    }

    private fun extractEmbeddedArtwork(file: File): ByteArray? {
        if (!file.exists() || file.length() <= 0L) return null
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.embeddedPicture
        } catch (e: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /** Fetches and stores lyrics after a successful audio download, without holding an audio slot. */
    private fun fetchLyrics(track: Track) {
        if (appContext == null) return
        if (lyricsJobs[track.id]?.isActive == true) return
        lyricsJobs[track.id] = scope.launch {
            try {
                fetchLyricsNow(track, force = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.d(TAG, "Lyrics download skipped for '${track.title}': ${e.message}")
            } finally {
                lyricsJobs.remove(track.id)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Persistence & helpers
    // ---------------------------------------------------------------------------------------

    private fun musicDir(context: Context): File {
        val dir = context.getExternalFilesDir(MUSIC_DIR) ?: File(context.filesDir, MUSIC_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun manifestFile(context: Context): File = File(context.filesDir, MANIFEST_FILE)

    private fun loadManifest() {
        val context = appContext ?: return
        val file = manifestFile(context)
        if (!file.exists()) return
        try {
            val type = object : TypeToken<List<DownloadEntry>>() {}.type
            val stored: List<DownloadEntry>? = gson.fromJson(file.readText(), type)
            entries.clear()
            stored.orEmpty().forEach { entry ->
                entries[entry.track.id] = entry
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read download manifest: ${e.message}")
        }
    }

    private fun saveManifest() {
        val context = appContext ?: return
        try {
            val snapshot = entries.values.toList()
            val target = manifestFile(context)
            val temp = File(target.parentFile, "${target.name}.tmp")
            temp.writeText(gson.toJson(snapshot))
            if (target.exists()) target.delete()
            temp.renameTo(target)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to save download manifest: ${e.message}")
        }
    }

    private fun pruneMissingFiles() {
        val context = appContext ?: return
        val directory = musicDir(context)
        val store = DownloadLyricsStore.default(context)
        val missing = entries.entries.filter { (_, entry) ->
            val file = File(directory, entry.fileName)
            !file.exists() || file.length() <= 0L
        }
        missing.forEach { (id, _) ->
            lyricsJobs.remove(id)?.cancel()
            store.delete(id)
        }
        entries.entries.removeAll { (_, entry) ->
            val file = File(directory, entry.fileName)
            !file.exists() || file.length() <= 0L
        }
    }

    private fun cleanupPartialFiles() {
        val context = appContext ?: return
        musicDir(context).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".part") }
            ?.forEach { it.delete() }
    }

    private fun publish() {
        val context = appContext ?: return
        val directory = musicDir(context)
        val activeIds = jobs.keys
        val downloadedStates = entries.keys.associateWith {
            DownloadProgress(DownloadStatus.DOWNLOADED, 1f)
        }
        _states.update { current ->
            val next = current.toMutableMap()
            next.keys.retainAll { it in activeIds || it in entries }
            downloadedStates.forEach { (id, progress) ->
                if (next[id]?.isActive != true) next[id] = progress
            }
            next
        }
        _downloadedTracks.value = entries.values.map { entry ->
            val audio = File(directory, entry.fileName).takeIf { it.exists() }
            val cover = entry.coverFileName
                ?.let { File(directory, it) }
                ?.takeIf { it.exists() }
            entry.track.copy(
                streamUrl = audio?.absolutePath ?: entry.track.streamUrl,
                coverUrl = cover?.let { "file://${it.absolutePath}" } ?: entry.track.coverUrl
            )
        }
        _totalBytes.value = entries.values.sumOf { entry ->
            File(directory, entry.fileName).let { if (it.exists()) it.length() else entry.bytes }
        }
    }

    private fun setState(trackId: String, progress: DownloadProgress) {
        _states.update { it + (trackId to progress) }
    }

    /** Debounced rescan so the "downloaded" playlist picks up the new files without thrashing. */
    private fun scheduleLocalSync() {
        localSyncJob?.cancel()
        localSyncJob = scope.launch {
            delay(2_500)
            val context = appContext ?: return@launch
            try {
                MusicRepository.getInstance(context).syncLocalMusic()
            } catch (e: Exception) {
                Log.d(TAG, "Local music rescan skipped: ${e.message}")
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Pure helpers (unit-tested)
    // ---------------------------------------------------------------------------------------

    /**
     * Pure lookup behind [canonicalTrackFor]: matches by entry id first, then by audio path.
     * [entryAudioPath] maps an entry to its on-disk path; both sides are normalized so that
     * `file://` URIs and plain absolute paths compare equal. Never throws.
     */
    internal fun canonicalEntryFor(
        track: Track,
        entries: Map<String, DownloadEntry>,
        entryAudioPath: (DownloadEntry) -> String?
    ): DownloadEntry? {
        entries[track.id]?.let { return it }
        val target = normalizeLocalPath(track.streamUrl) ?: return null
        return entries.values.firstOrNull { entry ->
            val candidate = runCatching { entryAudioPath(entry) }.getOrNull()
                ?.let(::normalizeLocalPath)
            candidate != null && candidate == target
        }
    }

    /** Strips an optional `file://` prefix and canonicalizes the path. Returns null when blank. */
    internal fun normalizeLocalPath(raw: String?): String? {
        val path = raw?.removePrefix("file://")?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { File(path).canonicalPath }.getOrDefault(path)
    }

    internal fun sanitizeFileName(raw: String): String {
        val cleaned = raw
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
        return cleaned.take(80).ifBlank { "track" }
    }

    internal fun buildFileName(track: Track, extension: String): String {
        val base = sanitizeFileName("${track.artist} - ${track.title}")
        val hash = track.id.hashCode().toUInt().toString(16)
        return "$base [$hash].$extension"
    }

    internal fun extensionFor(format: String?): String {
        val normalized = format?.lowercase(Locale.US)?.substringAfter('/') ?: return "m4a"
        return when (normalized) {
            "flac", "x-flac" -> "flac"
            "mpeg", "mp3" -> "mp3"
            "opus", "ogg", "x-opus" -> "opus"
            "webm" -> "webm"
            "m4a", "mp4", "aac", "x-m4a" -> "m4a"
            else -> "m4a"
        }
    }
}

/**
 * Pure mapping from a stored [OfflineLyrics] (or a store miss) to the UI-facing
 * [OfflineLyricsInfo]. A negative marker becomes [OfflineLyricsStatus.NOT_FOUND].
 */
internal fun offlineLyricsInfo(lyrics: OfflineLyrics?): OfflineLyricsInfo = when {
    lyrics == null -> OfflineLyricsInfo(OfflineLyricsStatus.NONE)
    lyrics.notFound -> OfflineLyricsInfo(OfflineLyricsStatus.NOT_FOUND)
    else -> OfflineLyricsInfo(OfflineLyricsStatus.FOUND, lyrics.source, lyrics.fetchedAt)
}

/** True when the lyrics for this state are absent or retryable. */
internal fun needsLyricsDownload(info: OfflineLyricsInfo?): Boolean = when (info?.status) {
    null,
    OfflineLyricsStatus.NONE,
    OfflineLyricsStatus.NOT_FOUND,
    OfflineLyricsStatus.ERROR -> true
    OfflineLyricsStatus.FETCHING,
    OfflineLyricsStatus.FOUND -> false
}
