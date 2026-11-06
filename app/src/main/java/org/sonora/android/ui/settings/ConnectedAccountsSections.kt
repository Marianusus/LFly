package org.sonora.android.ui.settings

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import org.sonora.android.R
import org.sonora.android.account.LibrarySyncState
import org.sonora.android.account.SpotifyLibrarySync
import org.sonora.android.auth.DiscordLoginDialog
import org.sonora.android.auth.SpotifyAuth
import org.sonora.android.auth.SpotifyLoginDialog
import org.sonora.android.discord.DiscordPresence
import org.sonora.android.discord.DiscordPresenceStyle

private val SpotifyGreen = Color(0xFF1DB954)
private val DiscordBlurple = Color(0xFF5865F2)

/** Spotify, for its playlists: connect on Spotify's own page, import the library, disconnect. */
@Composable
internal fun SpotifyAccountSection() {
    val context = LocalContext.current
    val sync = remember { SpotifyLibrarySync.getInstance(context) }
    val account by SpotifyAuth.account.collectAsState()
    val expired by SpotifyAuth.expired.collectAsState()
    val state by sync.state.collectAsState()
    var login by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }

    if (login) {
        SpotifyLoginDialog(
            busy = false,
            onConnected = { cookie ->
                SpotifyAuth.connect(cookie)
                login = false
                sync.refreshProfile()
                // A new account brings its playlists along.
                sync.sync()
            },
            onDismiss = { login = false }
        )
    }
    if (confirmDisconnect) {
        DisconnectDialog(
            title = stringResource(R.string.spotify_disconnect_confirm_title),
            body = stringResource(R.string.spotify_disconnect_confirm_body),
            onConfirm = {
                SpotifyAuth.disconnect()
                sync.reset()
                confirmDisconnect = false
            },
            onDismiss = { confirmDisconnect = false }
        )
    }

    SettingsGroup(header = stringResource(R.string.spotify_section_title), footer = stringResource(R.string.spotify_section_footer)) {
        val current = account
        if (current == null) {
            SettingsRow(
                title = stringResource(R.string.spotify_connect),
                subtitle = stringResource(R.string.spotify_connect_subtitle),
                icon = Icons.Default.LibraryMusic,
                tile = SpotifyGreen,
                showChevron = true,
                onClick = { login = true }
            )
            return@SettingsGroup
        }
        SettingsRow(
            title = current.name.ifBlank { stringResource(R.string.spotify_account_unnamed) },
            subtitle = stringResource(R.string.spotify_connected),
            leading = { Avatar(current.avatarUrl, Icons.Default.LibraryMusic, SpotifyGreen) }
        )
        if (expired) {
            SettingsRowDivider()
            SettingsRow(
                title = stringResource(R.string.spotify_expired),
                titleColor = SettingsDestructive,
                showChevron = true,
                onClick = { login = true }
            )
        }
        SettingsRowDivider()
        SettingsRow(
            title = stringResource(R.string.spotify_sync),
            subtitle = spotifySyncSummary(state),
            icon = Icons.Default.Sync,
            tile = SettingsTile.Blue,
            enabled = !state.running,
            onClick = { sync.sync() },
            trailing = if (state.running) {
                { CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) }
            } else {
                null
            }
        )
        SettingsRowDivider()
        SettingsRow(
            title = stringResource(R.string.spotify_disconnect),
            titleColor = SettingsDestructive,
            onClick = { confirmDisconnect = true }
        )
    }
}

@Composable
private fun spotifySyncSummary(sync: LibrarySyncState): String = when {
    sync.running && sync.playlistCount > 0 -> stringResource(R.string.yt_library_sync_playlist, sync.playlistIndex, sync.playlistCount)
    sync.running -> stringResource(R.string.spotify_sync_starting)
    sync.failed -> stringResource(R.string.yt_library_sync_failed)
    sync.lastSyncAt != null -> stringResource(
        R.string.spotify_sync_done,
        sync.lastPlaylists,
        sync.lastLiked,
        DateUtils.getRelativeTimeSpanString(sync.lastSyncAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    )
    else -> stringResource(R.string.spotify_sync_subtitle)
}

/** Discord: what LFly plays on the listener's profile, as LFly or as Spotify. */
@Composable
internal fun DiscordSection() {
    val state by DiscordPresence.state.collectAsState()
    val scope = rememberCoroutineScope()
    var login by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var editAppId by remember { mutableStateOf(false) }

    if (login) {
        DiscordLoginDialog(
            busy = checking,
            onToken = { token ->
                scope.launch {
                    checking = true
                    val result = DiscordPresence.connect(token)
                    checking = false
                    failed = result.isFailure
                    login = false
                }
            },
            onDismiss = { if (!checking) login = false }
        )
    }
    if (confirmDisconnect) {
        DisconnectDialog(
            title = stringResource(R.string.discord_disconnect_confirm_title),
            body = stringResource(R.string.discord_disconnect_confirm_body),
            onConfirm = {
                DiscordPresence.disconnect()
                confirmDisconnect = false
            },
            onDismiss = { confirmDisconnect = false }
        )
    }
    if (editAppId) {
        var text by remember { mutableStateOf(state.applicationId.takeUnless { it == DiscordPresence.LFLY_APPLICATION_ID }.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editAppId = false },
            title = { Text(stringResource(R.string.discord_app_id)) },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text(stringResource(R.string.discord_app_id_body))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { value -> text = value.filter { it.isDigit() }.take(24) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    DiscordPresence.setApplicationId(text)
                    editAppId = false
                }) { Text(stringResource(R.string.discord_save)) }
            },
            dismissButton = { TextButton(onClick = { editAppId = false }) { Text(stringResource(R.string.yt_account_cancel)) } }
        )
    }

    SettingsGroup(header = stringResource(R.string.discord_section_title), footer = stringResource(R.string.discord_section_footer)) {
        val user = state.user
        if (user == null) {
            SettingsRow(
                title = stringResource(R.string.discord_connect),
                subtitle = stringResource(if (failed) R.string.discord_connect_failed else R.string.discord_connect_subtitle),
                icon = Icons.Default.Forum,
                tile = DiscordBlurple,
                showChevron = true,
                onClick = { login = true }
            )
            return@SettingsGroup
        }
        SettingsRow(
            title = user.name,
            subtitle = stringResource(R.string.discord_connected),
            leading = { Avatar(user.avatarUrl, Icons.Default.Forum, DiscordBlurple) }
        )
        if (state.expired) {
            SettingsRowDivider()
            SettingsRow(
                title = stringResource(R.string.discord_expired),
                titleColor = SettingsDestructive,
                showChevron = true,
                onClick = { login = true }
            )
        }
        SettingsRowDivider()
        SettingsToggleRow(
            title = stringResource(R.string.discord_presence),
            subtitle = stringResource(R.string.discord_presence_subtitle),
            checked = state.enabled,
            onCheckedChange = DiscordPresence::setEnabled
        )
    }

    if (state.user != null) {
        SettingsGroup(header = stringResource(R.string.discord_style_title), footer = stringResource(R.string.discord_style_footer)) {
            SettingsChoiceRow(
                title = stringResource(R.string.discord_style_lfly),
                subtitle = stringResource(R.string.discord_style_lfly_subtitle),
                selected = state.style == DiscordPresenceStyle.LFLY,
                onClick = { DiscordPresence.setStyle(DiscordPresenceStyle.LFLY) }
            )
            SettingsRowDivider()
            SettingsChoiceRow(
                title = stringResource(R.string.discord_style_spotify),
                subtitle = stringResource(R.string.discord_style_spotify_subtitle),
                selected = state.style == DiscordPresenceStyle.SPOTIFY,
                onClick = { DiscordPresence.setStyle(DiscordPresenceStyle.SPOTIFY) }
            )
        }
        SettingsGroup {
            SettingsRow(
                title = stringResource(R.string.discord_app_id),
                value = if (state.applicationId == DiscordPresence.LFLY_APPLICATION_ID) stringResource(R.string.discord_app_id_none) else state.applicationId,
                icon = Icons.Default.Image,
                tile = SettingsTile.Graphite,
                showChevron = true,
                onClick = { editAppId = true }
            )
            SettingsRowDivider()
            SettingsRow(
                title = stringResource(R.string.discord_disconnect),
                titleColor = SettingsDestructive,
                onClick = { confirmDisconnect = true }
            )
        }
    }
}

@Composable
private fun DisconnectDialog(title: String, body: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.connected_disconnect), color = SettingsDestructive) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.yt_account_cancel)) } }
    )
}

@Composable
private fun Avatar(url: String?, fallback: ImageVector, color: Color) {
    Box(
        modifier = Modifier.size(30.dp).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center
    ) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(30.dp))
        } else {
            Icon(fallback, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
        }
    }
}
