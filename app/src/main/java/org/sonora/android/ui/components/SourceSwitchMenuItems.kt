package org.sonora.android.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.sonora.android.R
import org.sonora.android.api.PlaybackSource
import org.sonora.android.ui.theme.SonoraEmerald
import org.sonora.android.ui.theme.SonoraTextPrimary
import org.sonora.android.ui.theme.SonoraTextSecondary

/**
 * Shared 3-dots menu entries that let the user pin a track to YouTube Music or Monochrome,
 * or reset it to follow the global "Sorgente streaming" setting (Auto).
 *
 * @param sourceOverride the track's current override, or null when it follows the global default.
 */
@Composable
fun SourceSwitchMenuItems(
    sourceOverride: PlaybackSource?,
    onPlayViaSource: (PlaybackSource) -> Unit,
    onUseGlobalSource: () -> Unit
) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.track_source_auto), color = SonoraTextPrimary) },
        leadingIcon = {
            if (sourceOverride == null) {
                Icon(imageVector = Icons.Default.Check, contentDescription = null, tint = SonoraEmerald)
            }
        },
        onClick = onUseGlobalSource
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.track_play_via_youtube), color = SonoraTextPrimary) },
        leadingIcon = {
            if (sourceOverride == PlaybackSource.YOUTUBE_MUSIC) {
                Icon(imageVector = Icons.Default.Check, contentDescription = null, tint = SonoraEmerald)
            } else {
                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = SonoraTextSecondary)
            }
        },
        onClick = { onPlayViaSource(PlaybackSource.YOUTUBE_MUSIC) }
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.track_play_via_monochrome), color = SonoraTextPrimary) },
        leadingIcon = {
            if (sourceOverride == PlaybackSource.MONOCHROME) {
                Icon(imageVector = Icons.Default.Check, contentDescription = null, tint = SonoraEmerald)
            } else {
                Icon(imageVector = Icons.Default.MusicNote, contentDescription = null, tint = SonoraTextSecondary)
            }
        },
        onClick = { onPlayViaSource(PlaybackSource.MONOCHROME) }
    )
}
