package org.sonora.android.playback.auto

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import okhttp3.Request
import org.sonora.android.api.HttpClientProvider
import java.io.File
import java.io.FileNotFoundException

/**
 * Serves browse artwork to Android Auto, which only loads local `content://` image URIs.
 * Remote covers are downloaded on demand and cached.
 */
class AutoArtworkProvider : ContentProvider() {

    companion object {
        private const val TAG = "AutoArtworkProvider"
        private const val CACHE_DIR = "auto_artwork"
        private const val MAX_CACHED_FILES = 300
        private const val MAX_IMAGE_BYTES = 8L * 1024L * 1024L

        fun uriFor(context: Context, coverUrl: String?): Uri? {
            val key = AutoArtworkRegistry.register(coverUrl) ?: return null
            return Uri.Builder()
                .scheme("content")
                .authority("${context.packageName}.artwork")
                .appendPath(key)
                .build()
        }
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read-only provider")
        val key = uri.lastPathSegment ?: throw FileNotFoundException("Missing key")
        val source = AutoArtworkRegistry.sourceFor(key) ?: throw FileNotFoundException("Unknown key")
        val file = if (source.startsWith("/")) File(source) else cachedRemote(key, source)
        if (!file.isFile) throw FileNotFoundException("Artwork not available")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun cachedRemote(key: String, url: String): File {
        val context = context ?: throw FileNotFoundException("No context")
        val directory = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        val target = File(directory, key)
        if (target.length() > 0L) return target

        val temp = File(directory, "$key.tmp")
        try {
            val request = Request.Builder().url(url).build()
            HttpClientProvider.okHttpClient.newCall(request).execute().use { response ->
                val body = response.body
                val isImage = body?.contentType()?.type == "image"
                if (!response.isSuccessful || body == null || !isImage) {
                    throw FileNotFoundException("Artwork download failed: ${response.code}")
                }
                body.byteStream().use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            total += read
                            if (total > MAX_IMAGE_BYTES) throw FileNotFoundException("Artwork too large")
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }
            if (!temp.renameTo(target)) throw FileNotFoundException("Unable to cache artwork")
            prune(directory)
            return target
        } catch (e: FileNotFoundException) {
            temp.delete()
            throw e
        } catch (e: Exception) {
            temp.delete()
            Log.w(TAG, "Artwork download failed: ${e.message}")
            throw FileNotFoundException("Artwork download failed")
        }
    }

    private fun prune(directory: File) {
        directory.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_CACHED_FILES)
            ?.forEach { it.delete() }
    }

    override fun getType(uri: Uri): String = "image/*"
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
