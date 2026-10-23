package org.sonora.android.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.sonora.android.R
import org.sonora.android.api.Track
import org.sonora.android.playback.DownloadProgress
import org.sonora.android.playback.DownloadStatus
import org.sonora.android.ui.theme.SonoraEmerald
import org.sonora.android.ui.theme.SonoraTextSecondary

/** App-wide download state + action, provided once in [org.sonora.android.ui.MainScreen]. */
data class DownloadUiState(
    val states: Map<String, DownloadProgress> = emptyMap(),
    val onToggle: (Track) -> Unit = {},
    val onDeleteLocal: (Track) -> Unit = {},
    val isManagedLocal: (Track) -> Boolean = { false },
    val isOnline: Boolean = true
) {
    fun progressFor(trackId: String): DownloadProgress =
        states[trackId] ?: DownloadProgress()

    fun isDownloaded(trackId: String): Boolean = progressFor(trackId).isDownloaded
}

val LocalDownloadUi = compositionLocalOf { DownloadUiState() }

/**
 * Download action icon for a track row: download → progress (tap cancels) → done (tap removes).
 * Hidden for locally-imported tracks, which are already offline by definition.
 */
@Composable
fun TrackDownloadButton(
    track: Track,
    buttonSize: Dp = 34.dp,
    iconSize: Dp = 19.dp
) {
    if (track.provider.equals("Local", ignoreCase = true)) return
    val ui = LocalDownloadUi.current
    DownloadIconButton(
        progress = ui.progressFor(track.id),
        onClick = { ui.onToggle(track) },
        buttonSize = buttonSize,
        iconSize = iconSize
    )
}

@Composable
fun DownloadIconButton(
    progress: DownloadProgress,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    buttonSize: Dp = 36.dp,
    iconSize: Dp = 20.dp,
    tint: Color = SonoraTextSecondary,
    accent: Color = SonoraEmerald,
    enabled: Boolean = true
) {
    val description = when (progress.status) {
        DownloadStatus.DOWNLOADED -> stringResource(R.string.download_remove)
        DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> stringResource(R.string.download_cancel)
        DownloadStatus.FAILED -> stringResource(R.string.download_retry)
        DownloadStatus.NONE -> stringResource(R.string.download_action)
    }

    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(buttonSize)) {
        when (progress.status) {
            DownloadStatus.DOWNLOADING -> {
                if (progress.progress > 0f) {
                    CircularProgressIndicator(
                        progress = { progress.progress },
                        modifier = Modifier.size(iconSize),
                        color = accent,
                        strokeWidth = 2.dp
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(iconSize),
                        color = accent,
                        strokeWidth = 2.dp
                    )
                }
            }

            DownloadStatus.QUEUED -> CircularProgressIndicator(
                modifier = Modifier.size(iconSize),
                color = tint,
                strokeWidth = 2.dp
            )

            DownloadStatus.DOWNLOADED -> Icon(
                imageVector = Icons.Default.DownloadDone,
                contentDescription = description,
                tint = accent,
                modifier = Modifier.size(iconSize)
            )

            DownloadStatus.FAILED -> Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = description,
                tint = Color(0xFFE57373),
                modifier = Modifier.size(iconSize)
            )

            DownloadStatus.NONE -> Icon(
                imageVector = Icons.Default.Download,
                contentDescription = description,
                tint = tint,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

/** Aggregated download state for a playlist header button. */
fun aggregateDownloadProgress(
    tracks: List<Track>,
    states: Map<String, DownloadProgress>
): DownloadProgress {
    val downloadable = tracks.filterNot { it.provider.equals("Local", ignoreCase = true) }
    if (downloadable.isEmpty()) return DownloadProgress()

    val progress = downloadable.map { states[it.id] ?: DownloadProgress() }
    return when {
        progress.all { it.isDownloaded } -> DownloadProgress(DownloadStatus.DOWNLOADED, 1f)
        progress.any { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED } -> {
            val done = progress.count { it.isDownloaded }
            DownloadProgress(
                status = DownloadStatus.DOWNLOADING,
                progress = (done.toFloat() / downloadable.size).coerceIn(0f, 1f)
            )
        }
        progress.any { it.status == DownloadStatus.FAILED } -> DownloadProgress(DownloadStatus.FAILED)
        else -> DownloadProgress()
    }
}

/**
 * Dropdown entry for custom track rows that build their own menu.
 * Renders nothing for locally-imported tracks.
 */
@Composable
fun DownloadMenuEntry(
    track: Track,
    onDismiss: () -> Unit,
    accent: Color = SonoraEmerald,
    primary: Color = org.sonora.android.ui.theme.SonoraTextPrimary,
    secondary: Color = SonoraTextSecondary
) {
    if (track.provider.equals("Local", ignoreCase = true)) return

    val ui = LocalDownloadUi.current
    val progress = ui.progressFor(track.id)
    val label = when (progress.status) {
        DownloadStatus.DOWNLOADED -> stringResource(R.string.download_remove)
        DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> stringResource(R.string.download_cancel)
        DownloadStatus.FAILED -> stringResource(R.string.download_retry)
        DownloadStatus.NONE -> stringResource(R.string.download_action)
    }
    val icon = when (progress.status) {
        DownloadStatus.DOWNLOADED -> Icons.Default.DownloadDone
        DownloadStatus.FAILED -> Icons.Default.Refresh
        else -> Icons.Default.Download
    }
    val tint = when (progress.status) {
        DownloadStatus.DOWNLOADED -> accent
        DownloadStatus.FAILED -> Color(0xFFE57373)
        else -> secondary
    }

    androidx.compose.material3.DropdownMenuItem(
        text = { androidx.compose.material3.Text(label, color = primary) },
        leadingIcon = {
            Icon(imageVector = icon, contentDescription = null, tint = tint)
        },
        onClick = {
            onDismiss()
            ui.onToggle(track)
        }
    )
}
