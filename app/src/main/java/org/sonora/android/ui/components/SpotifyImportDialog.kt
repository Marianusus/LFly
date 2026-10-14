package org.sonora.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import coil.compose.AsyncImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.sonora.android.R
import org.sonora.android.api.SpotifyDataExportImporter
import org.sonora.android.api.SpotifyPlaylistImporter
import org.sonora.android.data.MusicRepository
import org.sonora.android.data.Playlist
import org.sonora.android.ui.theme.SonoraDivider
import org.sonora.android.ui.theme.SonoraEmerald
import org.sonora.android.ui.theme.SonoraSurfaceHighlight
import org.sonora.android.ui.theme.SonoraTextPrimary
import org.sonora.android.ui.theme.SonoraTextSecondary
import org.sonora.android.ui.theme.SonoraTextTertiary
import java.io.IOException

private val SpotifyBrandGreen = Color(0xFF1DB954)

private enum class SpotifyImportMode {
    LINK,
    FILE
}

sealed class SpotifyImportState {
    object Idle : SpotifyImportState()
    data class Importing(
        val current: Int,
        val total: Int,
        val currentTrack: String
    ) : SpotifyImportState()
    data class Success(val playlists: List<Playlist>) : SpotifyImportState()
    data class Error(val message: String) : SpotifyImportState()
}

/**
 * Body of the Spotify transfer flow (pills + states + actions), without any dialog chrome.
 *
 * Rendered inside the unified [ImportDialog]. All state is local to this composable, so switching
 * source (Spotify <-> YouTube Music) resets it, which is the desired behaviour.
 */
@Composable
fun SpotifyImportBody(
    onDismissRequest: () -> Unit,
    importer: SpotifyPlaylistImporter = remember { SpotifyPlaylistImporter() },
    repository: MusicRepository = MusicRepository.getInstance(LocalContext.current),
    onBusyChange: (Boolean) -> Unit = {},
    onPlaylistImported: ((Playlist) -> Unit)? = null,
    onPlayPlaylist: ((Playlist) -> Unit)? = null,
    dataExportImporter: SpotifyDataExportImporter = remember { SpotifyDataExportImporter() }
) {
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val appContext = LocalContext.current
    var inputUrl by remember { mutableStateOf("") }
    var importMode by remember { mutableStateOf(SpotifyImportMode.LINK) }
    var importState by remember { mutableStateOf<SpotifyImportState>(SpotifyImportState.Idle) }
    var importJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(importState) {
        onBusyChange(importState is SpotifyImportState.Importing)
    }

    // Check clipboard on launch for convenience
    var clipboardSuggestion by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val clipText = clipboardManager.getText()?.text?.trim()
        if (!clipText.isNullOrBlank() && (clipText.contains("spotify.com") || clipText.startsWith("spotify:"))) {
            clipboardSuggestion = clipText
        }
    }

    // Picker for the official Spotify data export (ZIP or JSON).
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importState = SpotifyImportState.Importing(0, 0, appContext.getString(R.string.import_spotify_reading_export))
            importJob = coroutineScope.launch {
                val result = runCatching {
                    appContext.contentResolver.openInputStream(uri)?.use { stream ->
                        dataExportImporter.importFromExport(
                            input = stream,
                            repository = repository,
                            onProgress = { cur, tot, title ->
                                importState = SpotifyImportState.Importing(cur, tot, title)
                            }
                        ).getOrThrow()
                    } ?: throw IOException(appContext.getString(R.string.import_spotify_error_open_file))
                }
                result.fold(
                    onSuccess = { playlists ->
                        importState = SpotifyImportState.Success(playlists)
                        playlists.forEach { onPlaylistImported?.invoke(it) }
                    },
                    onFailure = { err ->
                        importState = SpotifyImportState.Error(
                            err.message ?: appContext.getString(R.string.import_spotify_error_export_failed)
                        )
                    }
                )
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when (val state = importState) {
            is SpotifyImportState.Idle -> {
                val linkModeLabel = stringResource(R.string.import_spotify_mode_link)
                val fileModeLabel = stringResource(R.string.import_spotify_mode_file)
                SegmentedPills(
                    items = SpotifyImportMode.entries,
                    selected = importMode,
                    onSelect = { importMode = it },
                    label = { if (it == SpotifyImportMode.LINK) linkModeLabel else fileModeLabel },
                    modifier = Modifier.fillMaxWidth()
                )

                if (importMode == SpotifyImportMode.LINK) {
                    Text(
                        text = stringResource(R.string.import_spotify_link_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = SonoraTextSecondary
                    )

                    // Clipboard Suggestion Chip if available
                    if (!clipboardSuggestion.isNullOrBlank() && inputUrl.isBlank()) {
                        Surface(
                            onClick = {
                                inputUrl = clipboardSuggestion ?: ""
                                clipboardSuggestion = null
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = SonoraSurfaceHighlight,
                            border = androidx.compose.foundation.BorderStroke(1.dp, SonoraDivider),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentPaste,
                                    contentDescription = null,
                                    tint = SpotifyBrandGreen,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = stringResource(R.string.import_spotify_paste_from_clipboard),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        color = SonoraTextPrimary
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = inputUrl,
                        onValueChange = { inputUrl = it },
                        placeholder = {
                            Text(
                                "https://open.spotify.com/playlist/...",
                                color = SonoraTextTertiary,
                                fontSize = 13.sp
                            )
                        },
                        singleLine = true,
                        trailingIcon = {
                            if (inputUrl.isNotBlank()) {
                                IconButton(onClick = { inputUrl = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = stringResource(R.string.import_spotify_clear),
                                        tint = SonoraTextSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            } else {
                                IconButton(onClick = {
                                    clipboardManager.getText()?.text?.let { inputUrl = it.trim() }
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.ContentPaste,
                                        contentDescription = stringResource(R.string.import_spotify_paste),
                                        tint = SonoraTextTertiary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = SonoraTextPrimary,
                            unfocusedTextColor = SonoraTextPrimary,
                            focusedBorderColor = SpotifyBrandGreen,
                            unfocusedBorderColor = SonoraDivider,
                            cursorColor = SpotifyBrandGreen
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        text = stringResource(R.string.import_spotify_link_footer),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = SonoraTextTertiary,
                            fontSize = 11.sp
                        )
                    )
                } else {
                    Text(
                        text = stringResource(R.string.import_spotify_export_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = SonoraTextSecondary
                    )

                    Text(
                        text = stringResource(R.string.import_spotify_export_steps),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = SonoraTextTertiary,
                            lineHeight = 15.sp
                        )
                    )

                    Surface(
                        onClick = {
                            filePicker.launch(
                                arrayOf("application/zip", "application/json", "application/octet-stream", "*/*")
                            )
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = SonoraSurfaceHighlight,
                        border = androidx.compose.foundation.BorderStroke(1.dp, SonoraDivider),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .background(SpotifyBrandGreen.copy(alpha = 0.18f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.UploadFile,
                                    contentDescription = null,
                                    tint = SpotifyBrandGreen,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = stringResource(R.string.import_spotify_choose_export_file),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = SonoraTextPrimary
                                    )
                                )
                                Text(
                                    text = stringResource(R.string.import_spotify_file_types),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = SonoraTextTertiary
                                    )
                                )
                            }
                        }
                    }
                }
            }

            is SpotifyImportState.Importing -> {
                val progressFraction = if (state.total > 0) {
                    (state.current.toFloat() / state.total.toFloat()).coerceIn(0f, 1f)
                } else 0f

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(SpotifyBrandGreen.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(44.dp),
                            color = SpotifyBrandGreen,
                            strokeWidth = 3.dp
                        )
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = SpotifyBrandGreen,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // Track-by-track status counter: "Importing 12/45: Song Name..."
                    val statusText = if (state.total > 0 && state.current > 0) {
                        stringResource(
                            R.string.import_spotify_progress_importing,
                            state.current,
                            state.total,
                            state.currentTrack
                        )
                    } else {
                        stringResource(R.string.import_spotify_loading_metadata)
                    }

                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = SonoraTextPrimary
                        ),
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Linear progress indicator
                    if (state.total > 0) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            LinearProgressIndicator(
                                progress = { progressFraction },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = SpotifyBrandGreen,
                                trackColor = SonoraSurfaceHighlight
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.import_spotify_progress_completed,
                                        (progressFraction * 100).toInt()
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SonoraTextSecondary,
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = "${state.current}/${state.total}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SonoraTextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = SpotifyBrandGreen,
                            trackColor = SonoraSurfaceHighlight
                        )
                    }
                }
            }

            is SpotifyImportState.Success -> {
                val playlists = state.playlists
                val totalTracks = playlists.sumOf { it.tracks.size }
                val single = playlists.singleOrNull()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .background(SonoraEmerald.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = SonoraEmerald,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Text(
                        text = if (single != null) {
                            stringResource(R.string.import_spotify_success_single)
                        } else {
                            pluralStringResource(
                                R.plurals.import_spotify_success_multiple,
                                playlists.size,
                                playlists.size
                            )
                        },
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = SonoraTextPrimary
                        ),
                        textAlign = TextAlign.Center
                    )

                    // Summary card
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SonoraSurfaceHighlight),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            val cover = single?.displayCoverUrl
                            if (!cover.isNullOrBlank()) {
                                AsyncImage(
                                    model = cover,
                                    contentDescription = single?.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(SpotifyBrandGreen.copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                        contentDescription = null,
                                        tint = SpotifyBrandGreen,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = single?.name ?: stringResource(R.string.import_spotify_export_default_name),
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = SonoraTextPrimary
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (single != null) {
                                        pluralStringResource(
                                            R.plurals.import_spotify_saved_library,
                                            totalTracks,
                                            totalTracks
                                        )
                                    } else {
                                        pluralStringResource(
                                            R.plurals.import_spotify_saved_in_playlists,
                                            totalTracks,
                                            totalTracks,
                                            playlists.size
                                        )
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SonoraTextSecondary
                                )
                            }
                        }
                    }

                    if (importMode == SpotifyImportMode.LINK && playlists.any { it.tracks.size >= 100 }) {
                        Text(
                            text = stringResource(R.string.import_spotify_note_limit),
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = SonoraTextTertiary,
                                lineHeight = 15.sp
                            ),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            is SpotifyImportState.Error -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(Color.Red.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    Text(
                        text = stringResource(R.string.import_spotify_error_title),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = SonoraTextPrimary
                        )
                    )

                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = SonoraTextSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // ---- Actions (previously the AlertDialog confirm/dismiss buttons) ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (val state = importState) {
                is SpotifyImportState.Idle -> {
                    if (importMode == SpotifyImportMode.LINK) {
                        Button(
                            onClick = {
                                val url = inputUrl.trim()
                                if (url.isNotBlank()) {
                                    importState = SpotifyImportState.Importing(
                                        0,
                                        0,
                                        appContext.getString(R.string.import_spotify_initializing)
                                    )
                                    importJob = coroutineScope.launch {
                                        val result = importer.importAndSavePlaylist(
                                            urlOrId = url,
                                            repository = repository,
                                            onProgress = { cur, tot, title ->
                                                importState = SpotifyImportState.Importing(cur, tot, title)
                                            }
                                        )
                                        result.fold(
                                            onSuccess = { playlist ->
                                                importState = SpotifyImportState.Success(listOf(playlist))
                                                onPlaylistImported?.invoke(playlist)
                                            },
                                            onFailure = { err ->
                                                importState = SpotifyImportState.Error(
                                                    err.message ?: appContext.getString(R.string.import_spotify_error_fetch_playlist)
                                                )
                                            }
                                        )
                                    }
                                }
                            },
                            enabled = inputUrl.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SpotifyBrandGreen,
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(stringResource(R.string.import_spotify_action_transfer), fontWeight = FontWeight.Bold)
                        }
                    }
                    TextButton(onClick = onDismissRequest) {
                        Text(stringResource(R.string.import_spotify_action_cancel), color = SonoraTextSecondary)
                    }
                }

                is SpotifyImportState.Importing -> {
                    TextButton(
                        onClick = {
                            importJob?.cancel()
                            importState = SpotifyImportState.Idle
                        }
                    ) {
                        Text(stringResource(R.string.import_spotify_action_cancel), color = SonoraTextSecondary)
                    }
                }

                is SpotifyImportState.Success -> {
                    val firstPlaylist = state.playlists.firstOrNull()
                    if (onPlayPlaylist != null && firstPlaylist != null && firstPlaylist.tracks.isNotEmpty()) {
                        Button(
                            onClick = {
                                onPlayPlaylist(firstPlaylist)
                                onDismissRequest()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SonoraEmerald,
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.import_spotify_action_play_now), fontWeight = FontWeight.Bold)
                        }
                    }
                    TextButton(onClick = onDismissRequest) {
                        Text(stringResource(R.string.import_spotify_action_close), color = SonoraTextSecondary)
                    }
                }

                is SpotifyImportState.Error -> {
                    Button(
                        onClick = { importState = SpotifyImportState.Idle },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SpotifyBrandGreen,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.import_spotify_action_retry), fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = onDismissRequest) {
                        Text(stringResource(R.string.import_spotify_action_cancel), color = SonoraTextSecondary)
                    }
                }
            }
        }
    }
}
