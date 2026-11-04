package org.sonora.android.ui.settings

import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.sonora.android.R
import org.sonora.android.data.BackupManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Settings → General → Data: export everything to one file, and bring it back on another install. */
@Composable
internal fun DataBackupSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<android.net.Uri?>(null) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val result = runCatching { BackupManager.export(context, uri) }
            busy = false
            val message = result.fold(
                onSuccess = { context.getString(R.string.backup_export_done, Formatter.formatShortFileSize(context, it.bytes)) },
                onFailure = { context.getString(R.string.backup_failed) }
            )
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingImport = uri
    }

    pendingImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(stringResource(R.string.backup_import_confirm_title)) },
            text = { Text(stringResource(R.string.backup_import_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingImport = null
                    busy = true
                    scope.launch {
                        val result = runCatching { BackupManager.import(context, uri) }
                        busy = false
                        if (result.isSuccess) {
                            Toast.makeText(context, R.string.backup_import_done, Toast.LENGTH_SHORT).show()
                            BackupManager.restart(context)
                        } else {
                            Toast.makeText(context, R.string.backup_not_lfly, Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text(stringResource(R.string.backup_import_action), color = SettingsDestructive) }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text(stringResource(R.string.yt_account_cancel)) } }
        )
    }

    SettingsGroup(
        header = stringResource(R.string.backup_section_title),
        footer = stringResource(R.string.backup_section_footer)
    ) {
        SettingsRow(
            title = stringResource(R.string.backup_export),
            subtitle = stringResource(R.string.backup_export_subtitle),
            icon = Icons.Default.Upload,
            tile = SettingsTile.Blue,
            enabled = !busy,
            onClick = {
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
                exporter.launch("LFly-backup-$date.${BackupManager.EXTENSION}")
            },
            trailing = if (busy) {
                { CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) }
            } else {
                null
            }
        )
        SettingsRowDivider(hasLeading = true)
        SettingsRow(
            title = stringResource(R.string.backup_import),
            subtitle = stringResource(R.string.backup_import_subtitle),
            icon = Icons.Default.Download,
            tile = SettingsTile.Green,
            enabled = !busy,
            onClick = { importer.launch(arrayOf("*/*")) }
        )
    }
}
