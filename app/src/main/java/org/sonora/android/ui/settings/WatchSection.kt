package org.sonora.android.ui.settings

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.sonora.android.R
import org.sonora.android.wear.WatchUpdater

/** LFly on the wrist: which watch is paired, which build it runs, and installing this phone's one. */
@Composable
internal fun WatchSection() {
    val context = LocalContext.current
    val updater = remember { WatchUpdater.getInstance(context) }
    val state by updater.state.collectAsState()
    LaunchedEffect(Unit) { updater.refresh() }

    SettingsGroup(
        header = stringResource(R.string.watch_section_title),
        footer = stringResource(R.string.watch_section_footer)
    ) {
        SettingsRow(
            title = state.watchName ?: stringResource(R.string.watch_none),
            subtitle = when {
                state.watchName == null && state.checking -> stringResource(R.string.watch_looking)
                state.watchName == null -> stringResource(R.string.watch_none_hint)
                state.installed -> stringResource(R.string.watch_installed, state.watchVersion.orEmpty())
                state.checking -> stringResource(R.string.watch_looking)
                else -> stringResource(R.string.watch_not_installed)
            },
            icon = Icons.Default.Watch,
            tile = SettingsTile.Indigo,
            trailing = if (state.checking) {
                { CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) }
            } else {
                null
            }
        )
        if (state.watchName != null && state.bundled) {
            SettingsRowDivider(hasLeading = true)
            SettingsRow(
                title = stringResource(R.string.watch_install),
                subtitle = when {
                    state.sending -> stringResource(R.string.watch_sending)
                    state.sent -> stringResource(R.string.watch_sent)
                    state.failed -> stringResource(R.string.watch_failed)
                    state.installed && state.watchVersionCode >= updater.phoneVersionCode -> stringResource(R.string.watch_up_to_date)
                    else -> null
                },
                icon = Icons.Default.SystemUpdate,
                tile = SettingsTile.Green,
                enabled = !state.sending,
                onClick = { updater.install() },
                trailing = if (state.sending) {
                    { CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) }
                } else {
                    null
                }
            )
        }
    }
}
