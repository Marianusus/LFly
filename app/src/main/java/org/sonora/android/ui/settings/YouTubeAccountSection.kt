package org.sonora.android.ui.settings

import android.text.format.DateUtils
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import org.sonora.android.R
import org.sonora.android.account.LibrarySyncState
import org.sonora.android.account.YouTubeLibrarySync
import org.sonora.android.api.YouTubeMusicClient
import org.sonora.android.auth.GoogleAccountStore
import org.sonora.android.auth.WebSessionMode
import org.sonora.android.auth.YouTubeAccount
import org.sonora.android.auth.YouTubeAuth
import org.sonora.android.auth.YtMusicLoginDialog

/**
 * The listener's YouTube Music accounts, as in BitChord: sign in on Google's own page, several
 * accounts and Brand channels, one in use, switch channel, sign out. Self-contained: it reads and
 * writes [GoogleAccountStore] directly.
 */
@Composable
internal fun YouTubeAccountSection() {
    val context = LocalContext.current
    val store = remember { GoogleAccountStore.getInstance(context) }
    val client = remember { YouTubeMusicClient() }
    val library = remember { YouTubeLibrarySync.getInstance(context) }
    val sync by library.state.collectAsState()
    val state by store.state.collectAsState()
    val expired by YouTubeAuth.expired.collectAsState()
    val scope = rememberCoroutineScope()
    var login by remember { mutableStateOf<WebSessionMode?>(null) }
    var checking by remember { mutableStateOf(false) }
    var signingOut by remember { mutableStateOf<YouTubeAccount?>(null) }
    val active = state.active

    // An identity saved before its name was known (a channel just picked) learns it here.
    LaunchedEffect(active?.id, active?.name) {
        val account = active ?: return@LaunchedEffect
        if (account.name.isNotBlank()) return@LaunchedEffect
        client.accountInfo(account).onSuccess { info ->
            store.refresh(account.copy(name = info.name, handle = info.handle, email = info.email, avatarUrl = info.avatarUrl))
        }
    }

    login?.let { mode ->
        YtMusicLoginDialog(
            mode = mode,
            initialCookie = if (mode == WebSessionMode.SWITCH_CHANNEL) active?.cookie else null,
            busy = checking,
            onCaptured = { session ->
                scope.launch {
                    checking = true
                    val account = YouTubeAccount.from(session)
                    val info = client.accountInfo(account)
                    checking = false
                    // Saved either way: the page said it is signed in and the cookie can sign. Without
                    // its name it shows as a Google account until the next look succeeds.
                    info.onFailure { Log.w("YouTubeAccount", "Signed in, but the account menu did not answer: ${it.message}") }
                    store.add(
                        info.getOrNull()?.let { account.copy(name = it.name, handle = it.handle, email = it.email, avatarUrl = it.avatarUrl) }
                            ?: account
                    )
                    login = null
                    // A new account brings its library along, as it would in YouTube Music.
                    library.sync()
                }
            },
            onDismiss = { if (!checking) login = null }
        )
    }

    signingOut?.let { account ->
        AlertDialog(
            onDismissRequest = { signingOut = null },
            title = { Text(stringResource(R.string.yt_account_sign_out_confirm_title, displayName(account))) },
            text = { Text(stringResource(R.string.yt_account_sign_out_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    store.remove(account.id)
                    signingOut = null
                }) { Text(stringResource(R.string.yt_account_sign_out), color = SettingsDestructive) }
            },
            dismissButton = { TextButton(onClick = { signingOut = null }) { Text(stringResource(R.string.yt_account_cancel)) } }
        )
    }

    SettingsGroup(
        header = stringResource(R.string.yt_account_section_title),
        footer = stringResource(R.string.yt_account_section_footer)
    ) {
        if (state.accounts.isEmpty()) {
            SettingsRow(
                title = stringResource(R.string.yt_account_sign_in),
                subtitle = stringResource(R.string.yt_account_sign_in_subtitle),
                icon = Icons.Default.AccountCircle,
                tile = SettingsTile.Red,
                showChevron = true,
                onClick = { login = WebSessionMode.SIGN_IN }
            )
            return@SettingsGroup
        }
        state.accounts.forEachIndexed { index, account ->
            if (index > 0) SettingsRowDivider(hasLeading = true)
            val inUse = account.id == active?.id
            SettingsRow(
                title = displayName(account),
                subtitle = listOfNotNull(
                    account.handle.ifBlank { account.email }.takeIf { it.isNotBlank() },
                    if (account.isBrandChannel) stringResource(R.string.yt_account_brand) else null
                ).joinToString(" · ").ifBlank { null },
                leading = { AccountAvatar(account) },
                onClick = if (inUse) null else ({ store.select(account.id) }),
                trailing = if (inUse) {
                    { Icon(Icons.Default.Check, contentDescription = stringResource(R.string.yt_account_active), tint = SettingsTile.Blue) }
                } else {
                    null
                }
            )
        }
        if (expired) {
            SettingsRowDivider()
            SettingsRow(
                title = stringResource(R.string.yt_account_expired),
                titleColor = SettingsDestructive,
                showChevron = true,
                onClick = { login = WebSessionMode.SIGN_IN }
            )
        }
        SettingsRowDivider()
        SettingsRow(
            title = stringResource(R.string.yt_library_sync),
            subtitle = syncSummary(sync),
            icon = Icons.Default.Sync,
            tile = SettingsTile.Blue,
            enabled = !sync.running,
            onClick = { library.sync() },
            trailing = if (sync.running) {
                { CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) }
            } else {
                null
            }
        )
        SettingsRowDivider(hasLeading = true)
        SettingsRow(
            title = stringResource(R.string.yt_account_switch_channel),
            icon = Icons.Default.SwitchAccount,
            tile = SettingsTile.Indigo,
            showChevron = true,
            onClick = { login = WebSessionMode.SWITCH_CHANNEL }
        )
        SettingsRowDivider(hasLeading = true)
        SettingsRow(
            title = stringResource(R.string.yt_account_add),
            icon = Icons.Default.PersonAdd,
            tile = SettingsTile.Green,
            showChevron = true,
            onClick = { login = WebSessionMode.SIGN_IN }
        )
        if (active != null) {
            SettingsRowDivider()
            SettingsRow(
                title = stringResource(R.string.yt_account_sign_out),
                titleColor = SettingsDestructive,
                onClick = { signingOut = active }
            )
        }
    }
}

@Composable
private fun syncSummary(sync: LibrarySyncState): String = when {
    sync.running && sync.playlistCount > 0 -> stringResource(R.string.yt_library_sync_playlist, sync.playlistIndex, sync.playlistCount)
    sync.running -> stringResource(R.string.yt_library_sync_liked)
    sync.failed -> stringResource(R.string.yt_library_sync_failed)
    sync.lastSyncAt != null -> stringResource(
        R.string.yt_library_sync_done,
        sync.lastPlaylists,
        sync.lastLiked,
        DateUtils.getRelativeTimeSpanString(sync.lastSyncAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    )
    else -> stringResource(R.string.yt_library_sync_never)
}

@Composable
private fun displayName(account: YouTubeAccount): String =
    account.name.ifBlank { stringResource(R.string.yt_account_unnamed) }

@Composable
private fun AccountAvatar(account: YouTubeAccount) {
    Box(
        modifier = Modifier.size(30.dp).clip(CircleShape).background(SettingsTile.Graphite),
        contentAlignment = Alignment.Center
    ) {
        if (account.avatarUrl != null) {
            AsyncImage(
                model = account.avatarUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(30.dp)
            )
        } else {
            Icon(Icons.Default.AccountCircle, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White)
        }
    }
}
