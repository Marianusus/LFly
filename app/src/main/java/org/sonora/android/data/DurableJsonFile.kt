package org.sonora.android.data

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Crash-safe storage for the JSON files that hold the user's library.
 *
 * The library exists only on this device, so a damaged file must never turn into silent data
 * loss:
 * - [write] syncs the new content to disk and keeps the previous version as `<name>.bak`.
 * - [read] falls back to `<name>.bak` when the main file is unreadable. The damaged file is moved
 *   to `<name>.corrupt-<timestamp>` first, so the next save cannot destroy it.
 */
internal object DurableJsonFile {

    private const val TAG = "DurableJsonFile"
    private const val BACKUP_SUFFIX = ".bak"
    private const val CORRUPT_INFIX = ".corrupt-"
    private const val MAX_CORRUPT_COPIES = 3

    internal fun backupOf(target: File): File = File(target.parentFile, target.name + BACKUP_SUFFIX)

    @Throws(IOException::class)
    fun write(target: File, content: String) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(temp).use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }

        val backup = backupOf(target)
        val hadPrevious = target.exists() && target.length() > 0L
        if (hadPrevious && !target.renameTo(backup)) {
            temp.delete()
            throw IOException("Unable to back up ${target.name}")
        }
        if (!temp.renameTo(target)) {
            if (hadPrevious) backup.renameTo(target)
            temp.delete()
            throw IOException("Unable to replace ${target.name}")
        }
    }

    /**
     * Returns the parsed content of [target], or of its backup when [target] is unreadable.
     * [parse] must return null or throw when the text is not valid. Returns null when neither
     * file holds usable data.
     */
    fun <T> read(target: File, parse: (String) -> T?): T? {
        if (target.exists()) {
            val parsed = parseOrNull(target, parse)
            if (parsed != null) return parsed
            quarantine(target)
        }

        val backup = backupOf(target)
        if (!backup.exists()) return null
        val recovered = parseOrNull(backup, parse)
        if (recovered != null) {
            Log.w(TAG, "Recovered ${target.name} from its backup")
        } else {
            Log.e(TAG, "Backup of ${target.name} is unreadable too")
        }
        return recovered
    }

    private fun <T> parseOrNull(file: File, parse: (String) -> T?): T? = try {
        parse(file.readText())
    } catch (e: Exception) {
        Log.e(TAG, "Unable to parse ${file.name}", e)
        null
    }

    private fun quarantine(file: File) {
        if (file.length() == 0L) {
            file.delete()
            return
        }
        val copy = File(file.parentFile, "${file.name}$CORRUPT_INFIX${System.currentTimeMillis()}")
        if (file.renameTo(copy)) {
            Log.e(TAG, "Kept unreadable ${file.name} as ${copy.name}")
        } else {
            Log.e(TAG, "Unable to keep unreadable ${file.name}")
        }
        pruneQuarantine(file)
    }

    private fun pruneQuarantine(file: File) {
        val prefix = file.name + CORRUPT_INFIX
        file.parentFile
            ?.listFiles { candidate -> candidate.name.startsWith(prefix) }
            ?.sortedByDescending { it.name }
            ?.drop(MAX_CORRUPT_COPIES)
            ?.forEach { it.delete() }
    }
}
