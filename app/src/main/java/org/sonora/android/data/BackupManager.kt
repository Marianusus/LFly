package org.sonora.android.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.sonora.android.MainActivity
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * One file with everything that makes LFly yours, to move it to another install: a release build
 * over a development one (which Android only allows after uninstalling), or a new phone.
 *
 * In it: playlists, favourites, history, stats, mixes, playlist Mix settings, followed artists,
 * dislikes, search history, settings and the Automix analyses. Not in it: downloaded audio (it can
 * be gigabytes; songs are downloaded again), the beat model and voices (downloaded again), the
 * YouTube Music sign-in (a credential stays on its phone) and the browser's own files.
 */
object BackupManager {
    private const val TAG = "Backup"
    private const val META = "lfly-backup.json"
    private const val FORMAT = 1
    const val EXTENSION = "lfly"

    /** Data files left out: the download manifest points at audio the new install does not have. */
    private val SKIPPED_FILES = setOf("downloads.json")

    /** Preference files left out: the credential store, and ones the WebView and ML Kit own. */
    private val SKIPPED_PREFS = setOf("lfly_credentials.xml", "WebViewChromiumPrefs.xml", "AwOriginVisitLoggerPrefs.xml")

    private class Meta(val format: Int, val appVersion: String, val createdAt: Long)

    /** What a backup holds, for the confirmation and the result. */
    data class Summary(val files: Int, val bytes: Long)

    /** Writes the backup to [target]; returns what went in. */
    suspend fun export(context: Context, target: Uri): Summary = withContext(Dispatchers.IO) {
        val entries = entriesOf(context)
        var bytes = 0L
        context.contentResolver.openOutputStream(target)?.use { raw ->
            ZipOutputStream(raw.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(META))
                zip.write(Gson().toJson(Meta(FORMAT, appVersion(context), System.currentTimeMillis())).toByteArray())
                zip.closeEntry()
                for ((name, file) in entries) {
                    zip.putNextEntry(ZipEntry(name))
                    file.inputStream().use { bytes += it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } ?: error("Cannot write the backup")
        Log.i(TAG, "Exported ${entries.size} files, $bytes bytes")
        Summary(entries.size, bytes)
    }

    /**
     * Restores the backup at [source] over this install's data. Nothing is written unless the file
     * is an LFly backup. The app must restart right after ([restart]): data in memory would
     * otherwise be written back over what was restored.
     */
    suspend fun import(context: Context, source: Uri): Summary = withContext(Dispatchers.IO) {
        val root = context.filesDir.parentFile ?: error("No data directory")
        val staged = File(context.cacheDir, "backup-import").apply { deleteRecursively(); mkdirs() }
        var sawMeta = false
        var count = 0
        var bytes = 0L
        context.contentResolver.openInputStream(source)?.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    when {
                        name == META -> {
                            val meta = Gson().fromJson(zip.readBytes().decodeToString(), Meta::class.java)
                            require(meta != null && meta.format <= FORMAT) { "Backup from a newer LFly" }
                            sawMeta = true
                        }
                        !entry.isDirectory && isRestorable(name) -> {
                            val out = File(staged, name)
                            // Never outside the staging folder, whatever the archive says.
                            require(out.canonicalPath.startsWith(staged.canonicalPath + File.separator)) { "Bad entry" }
                            out.parentFile?.mkdirs()
                            out.outputStream().use { bytes += zip.copyTo(it) }
                            count++
                        }
                    }
                    zip.closeEntry()
                }
            }
        } ?: error("Cannot read the backup")
        require(sawMeta) { "Not an LFly backup" }
        // Only now, with the whole archive read, does anything of this install change.
        staged.walkTopDown().filter { it.isFile }.forEach { file ->
            val target = File(root, file.relativeTo(staged).path)
            target.parentFile?.mkdirs()
            file.copyTo(target, overwrite = true)
            // A stale journal copy would win over the restored file on the next read.
            File(target.path + ".bak").delete()
        }
        staged.deleteRecursively()
        Log.i(TAG, "Imported $count files, $bytes bytes")
        Summary(count, bytes)
    }

    /** Opens LFly afresh so every store reads the restored data. */
    fun restart(context: Context) {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        Process.killProcess(Process.myPid())
    }

    /** The files to back up, by their name in the archive. */
    private fun entriesOf(context: Context): List<Pair<String, File>> {
        val files = context.filesDir
        val prefs = File(files.parentFile, "shared_prefs")
        val out = ArrayList<Pair<String, File>>()
        files.listFiles()?.filter { it.isFile && it.name.endsWith(".json") && it.name !in SKIPPED_FILES }
            ?.forEach { out += "files/${it.name}" to it }
        File(files, "automix").listFiles()?.filter { it.isFile }?.forEach { out += "files/automix/${it.name}" to it }
        prefs.listFiles()?.filter { it.isFile && it.name.endsWith(".xml") && it.name !in SKIPPED_PREFS && !it.name.startsWith("com.google.") }
            ?.forEach { out += "shared_prefs/${it.name}" to it }
        return out
    }

    private fun isRestorable(name: String): Boolean {
        if (name.contains("..")) return false
        val file = name.substringAfterLast('/')
        return when {
            name.startsWith("files/automix/") -> name.count { it == '/' } == 2
            name.startsWith("files/") -> name.count { it == '/' } == 1 && file.endsWith(".json") && file !in SKIPPED_FILES
            name.startsWith("shared_prefs/") -> name.count { it == '/' } == 1 && file.endsWith(".xml") && file !in SKIPPED_PREFS
            else -> false
        }
    }

    private fun appVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty()
}
