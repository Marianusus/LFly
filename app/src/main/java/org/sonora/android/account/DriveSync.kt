package org.sonora.android.account

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.sonora.android.api.HttpClientProvider
import org.sonora.android.data.MusicRepository
import org.sonora.android.data.Playlist
import org.sonora.android.api.Track
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Sync with the LFly app on the computer through the listener's Google Drive.
 *
 * Both apps keep one file, `lfly-library.json`, in Drive's hidden app folder (`appDataFolder`:
 * only LFly sees it). Whoever syncs downloads it, merges it with its own library and uploads the
 * result when it differs, so the phone and the computer meet there without being on together.
 *
 * The merge is three-way, the same as the computer's `drive.rs`: this phone remembers which songs
 * and playlists the file held at its last sync (the base), so a song deleted on the computer (in
 * the base, gone there) is deleted here too, while a song added here (not in the base) is kept.
 * A playlist edited on both sides keeps the newer one (`updatedAt`); history is joined.
 *
 * Access is Google's authorization for the `drive.appdata` scope (Play services): the first time
 * it asks the listener; afterwards it is granted silently. The Android OAuth client must be in the
 * same Google Cloud project as the computer's, or each would see its own app folder.
 */
object DriveSync {
    private const val TAG = "DriveSync"
    private const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    private const val FILE_NAME = "lfly-library.json"
    private const val PREFS = "lfly_drive"
    private const val EVERY_MS = 5 * 60_000L

    enum class Phase { OFF, IDLE, SYNCING, ERROR }

    data class Status(val enabled: Boolean, val phase: Phase, val lastSync: Long?, val message: String? = null)

    private val _status = MutableStateFlow(Status(false, Phase.OFF, null))
    val status: StateFlow<Status> = _status.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO)
    private val running = Mutex()
    private var loop: Job? = null
    private lateinit var app: Context

    private val http: OkHttpClient by lazy {
        HttpClientProvider.okHttpClient.newBuilder().readTimeout(30, TimeUnit.SECONDS).build()
    }

    private val request: AuthorizationRequest
        get() = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** At app start: resumes syncing if the listener turned it on. */
    @OptIn(FlowPreview::class)
    fun start(context: Context, repository: MusicRepository) {
        app = context.applicationContext
        val enabled = prefs().getBoolean("enabled", false)
        _status.value = Status(enabled, if (enabled) Phase.IDLE else Phase.OFF, prefs().getLong("lastSync", 0L).takeIf { it > 0 })
        if (!enabled) return
        launchLoop(repository)
    }

    @OptIn(FlowPreview::class)
    private fun launchLoop(repository: MusicRepository) {
        loop?.cancel()
        loop = scope.launch {
            repository.awaitLibraryLoaded()
            // Edits here go up shortly after they settle.
            launch {
                combine(repository.favorites, repository.playlists, repository.history) { _, _, _ -> }
                    .drop(1)
                    .debounce(10_000)
                    .collect { if (!running.isLocked) runCatching { sync(repository, interactive = false) } }
            }
            while (true) {
                runCatching { sync(repository, interactive = false) }
                delay(EVERY_MS)
            }
        }
    }

    /**
     * Asks Google for Drive access. Returns null when granted, or the screen the listener must
     * see first (start it, then pass its result to [onConsent]).
     */
    suspend fun authorize(context: Context): PendingIntent? {
        val result = authorization(context)
        return if (result.hasResolution()) result.pendingIntent else null
    }

    /** The consent screen came back: turns syncing on if Google granted access. */
    fun onConsent(context: Context, data: Intent?, repository: MusicRepository): Boolean {
        val ok = runCatching { Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data).accessToken != null }
            .getOrDefault(false)
        if (ok) enable(repository) else _status.value = _status.value.copy(phase = Phase.ERROR, message = "Accesso a Drive non concesso")
        return ok
    }

    fun enable(repository: MusicRepository) {
        prefs().edit().putBoolean("enabled", true).apply()
        _status.value = Status(true, Phase.IDLE, _status.value.lastSync)
        launchLoop(repository)
    }

    fun disable() {
        loop?.cancel()
        loop = null
        prefs().edit().putBoolean("enabled", false).apply()
        _status.value = Status(false, Phase.OFF, _status.value.lastSync)
    }

    fun syncNow(repository: MusicRepository) {
        scope.launch { runCatching { sync(repository, interactive = true) } }
    }

    private suspend fun authorization(context: Context): AuthorizationResult =
        suspendCancellableCoroutine { cont ->
            Identity.getAuthorizationClient(context).authorize(request)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }

    private suspend fun token(): String {
        val result = authorization(app)
        if (result.hasResolution()) error("Riapri Impostazioni e ricollega Google Drive")
        return result.accessToken ?: error("Google non ha dato l'accesso a Drive")
    }

    // --- Drive ------------------------------------------------------------------------------

    private fun call(req: Request): String = http.newCall(req).execute().use { r ->
        val body = r.body?.string().orEmpty()
        if (!r.isSuccessful) error("Drive ${r.code}: ${body.take(200)}")
        body
    }

    private fun findFile(token: String): String? {
        val url = okhttp3.HttpUrl.Builder().scheme("https").host("www.googleapis.com").addPathSegments("drive/v3/files")
            .addQueryParameter("spaces", "appDataFolder")
            .addQueryParameter("q", "name = '$FILE_NAME' and trashed = false")
            .addQueryParameter("fields", "files(id,modifiedTime)")
            .addQueryParameter("orderBy", "modifiedTime desc")
            .build()
        val body = call(Request.Builder().url(url).header("Authorization", "Bearer $token").build())
        val files = JsonParser.parseString(body).asJsonObject.getAsJsonArray("files") ?: return null
        return files.firstOrNull()?.asJsonObject?.get("id")?.asString
    }

    private fun download(token: String, id: String): JsonObject {
        val body = call(Request.Builder().url("https://www.googleapis.com/drive/v3/files/$id?alt=media").header("Authorization", "Bearer $token").build())
        return JsonParser.parseString(body).asJsonObject
    }

    private fun upload(token: String, id: String?, file: JsonObject) {
        val json = "application/json".toMediaType()
        val bytes = file.toString().toByteArray()
        val req = if (id != null) {
            Request.Builder().url("https://www.googleapis.com/upload/drive/v3/files/$id?uploadType=media")
                .patch(bytes.toRequestBody(json))
        } else {
            val boundary = "lfly${System.nanoTime()}"
            val meta = JsonObject().apply {
                addProperty("name", FILE_NAME)
                add("parents", JsonArray().apply { add("appDataFolder") })
                addProperty("mimeType", "application/json")
            }
            val head = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n--$boundary\r\nContent-Type: application/json\r\n\r\n"
            val tail = "\r\n--$boundary--\r\n"
            Request.Builder().url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
                .post((head.toByteArray() + bytes + tail.toByteArray()).toRequestBody("multipart/related; boundary=$boundary".toMediaType()))
        }
        call(req.header("Authorization", "Bearer $token").build())
    }

    private fun ids(a: JsonArray): Set<String> = a.mapNotNull { e -> runCatching { e.asJsonObject.get("id")?.asString }.getOrNull() }.toSet()

    private fun baseOf(key: String): Set<String> = prefs().getStringSet(key, emptySet()) ?: emptySet()

    private suspend fun sync(repository: MusicRepository, interactive: Boolean) = running.withLock {
        _status.value = _status.value.copy(phase = Phase.SYNCING, message = null)
        try {
            val gson = HttpClientProvider.gson
            val token = token()
            val local = JsonObject().apply {
                add("favorites", gson.toJsonTree(repository.favorites.value))
                add("playlists", gson.toJsonTree(repository.playlists.value))
                add("history", gson.toJsonTree(repository.history.value))
            }
            val id = withContext(Dispatchers.IO) { findFile(token) }
            val remote = id?.let { withContext(Dispatchers.IO) { download(token, it) } }
            val list = { o: JsonObject, k: String -> o.getAsJsonArray(k) ?: JsonArray() }
            val merged = if (remote == null) local else
                LibraryMerge.merge(LibraryMerge.Base(baseOf("baseFavorites"), baseOf("basePlaylists")), local, remote)
            val changedHere = listOf("favorites", "playlists", "history").any { list(merged, it) != list(local, it) }
            if (changedHere) {
                repository.applySyncedLibrary(
                    gson.fromJson(list(merged, "favorites"), object : TypeToken<List<Track>>() {}.type),
                    gson.fromJson(list(merged, "playlists"), object : TypeToken<List<Playlist>>() {}.type),
                    gson.fromJson(list(merged, "history"), object : TypeToken<List<Track>>() {}.type),
                )
            }
            val differsThere = remote == null || listOf("favorites", "playlists", "history").any { list(merged, it) != list(remote, it) }
            if (differsThere) {
                val file = merged.deepCopy().apply {
                    addProperty("format", 1)
                    addProperty("updatedAt", System.currentTimeMillis())
                    addProperty("device", "phone")
                }
                withContext(Dispatchers.IO) { upload(token, id, file) }
            }
            val now = System.currentTimeMillis()
            prefs().edit()
                .putStringSet("baseFavorites", ids(list(merged, "favorites")))
                .putStringSet("basePlaylists", ids(list(merged, "playlists")))
                .putLong("lastSync", now)
                .apply()
            _status.value = Status(true, Phase.IDLE, now, if (interactive) (if (changedHere) "Libreria aggiornata dal computer" else "Libreria già allineata") else null)
            Log.i(TAG, "Synced: here ${if (changedHere) "updated" else "unchanged"}, Drive ${if (differsThere) "updated" else "unchanged"}")
        } catch (e: Exception) {
            Log.w(TAG, "Sync failed", e)
            _status.value = _status.value.copy(phase = Phase.ERROR, message = e.message ?: "Sincronizzazione non riuscita")
        }
    }
}
