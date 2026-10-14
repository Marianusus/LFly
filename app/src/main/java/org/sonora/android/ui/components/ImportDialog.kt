package org.sonora.android.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
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
import coil.compose.AsyncImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.sonora.android.R
import org.sonora.android.api.YouTubePlaylistImporter
import org.sonora.android.api.SpotifyPlaylistImporter
import org.sonora.android.data.MusicRepository
import org.sonora.android.data.Playlist
import org.sonora.android.ui.theme.SonoraDivider
import org.sonora.android.ui.theme.SonoraEmerald
import org.sonora.android.ui.theme.SonoraOpaqueSurfaceCard
import org.sonora.android.ui.theme.SonoraSurfaceHighlight
import org.sonora.android.ui.theme.SonoraTextPrimary
import org.sonora.android.ui.theme.SonoraTextSecondary
import org.sonora.android.ui.theme.SonoraTextTertiary

private val YouTubeBrandRed = Color(0xFFFF0033)

private enum class ImportSource {
    SPOTIFY,
    YOUTUBE
}

/**
 * Unified "Importa" sheet: transfers playlists from either Spotify or YouTube Music into the
 * local Sonora library. The source is chosen with the pills at the top; each source keeps its own
 * self-contained state (switching resets it).
 */
@Composable
fun ImportDialog(
    onDismissRequest: () -> Unit,
    spotifyImporter: SpotifyPlaylistImporter,
    youTubeImporter: YouTubePlaylistImporter,
    repository: MusicRepository,
    onPlayPlaylist: ((Playlist) -> Unit)? = null
) {
    var source by remember { mutableStateOf(ImportSource.SPOTIFY) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismissRequest() },
        containerColor = SonoraOpaqueSurfaceCard,
        shape = RoundedCornerShape(20.dp),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(
                                if (source == ImportSource.SPOTIFY) Color(0xFF1DB954).copy(alpha = 0.18f)
                                else YouTubeBrandRed.copy(alpha = 0.18f),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = null,
                            tint = if (source == ImportSource.SPOTIFY) Color(0xFF1DB954) else YouTubeBrandRed,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Column {
                        Text(
                            text = stringResource(R.string.import_title),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = SonoraTextPrimary
                            )
                        )
                        Text(
                            text = stringResource(R.string.import_subtitle),
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = SonoraTextTertiary
                            )
                        )
                    }
                }

                val spotifySourceLabel = stringResource(R.string.import_source_spotify)
                val youTubeSourceLabel = stringResource(R.string.import_source_youtube)
                SegmentedPills(
                    items = ImportSource.entries,
                    selected = source,
                    onSelect = { if (!busy) source = it },
                    label = { if (it == ImportSource.SPOTIFY) spotifySourceLabel else youTubeSourceLabel },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        text = {
            when (source) {
                ImportSource.SPOTIFY -> SpotifyImportBody(
                    onDismissRequest = onDismissRequest,
                    importer = spotifyImporter,
                    repository = repository,
                    onBusyChange = { busy = it },
                    onPlayPlaylist = onPlayPlaylist
                )

                ImportSource.YOUTUBE -> YouTubeImportBody(
                    onDismissRequest = onDismissRequest,
                    importer = youTubeImporter,
                    repository = repository,
                    onBusyChange = { busy = it },
                    onPlayPlaylist = onPlayPlaylist
                )
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}

sealed class YouTubeImportState {
    object Idle : YouTubeImportState()
    data class Importing(
        val current: Int,
        val total: Int,
        val currentTrack: String
    ) : YouTubeImportState()
    data class Success(val playlist: Playlist) : YouTubeImportState()
    data class Error(val message: String) : YouTubeImportState()
}

/**
 * Body of the YouTube Music playlist transfer flow (link only, no login, no 100-track cap).
 */
@Composable
fun YouTubeImportBody(
    onDismissRequest: () -> Unit,
    importer: YouTubePlaylistImporter = remember { YouTubePlaylistImporter() },
    repository: MusicRepository = MusicRepository.getInstance(LocalContext.current),
    onBusyChange: (Boolean) -> Unit = {},
    onPlaylistImported: ((Playlist) -> Unit)? = null,
    onPlayPlaylist: ((Playlist) -> Unit)? = null
) {
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val appContext = LocalContext.current
    var inputUrl by remember { mutableStateOf("") }
    var importState by remember { mutableStateOf<YouTubeImportState>(YouTubeImportState.Idle) }
    var importJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(importState) {
        onBusyChange(importState is YouTubeImportState.Importing)
    }

    var clipboardSuggestion by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val clipText = clipboardManager.getText()?.text?.trim()
        if (!clipText.isNullOrBlank() &&
            (clipText.contains("youtube.com") || clipText.contains("youtu.be") || clipText.contains("music.youtube"))
        ) {
            clipboardSuggestion = clipText
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when (val state = importState) {
            is YouTubeImportState.Idle -> {
                Text(
                    text = stringResource(R.string.import_youtube_link_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SonoraTextSecondary
                )

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
                                tint = YouTubeBrandRed,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = stringResource(R.string.import_paste_from_clipboard),
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
                            "https://music.youtube.com/playlist?list=...",
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
                                    contentDescription = stringResource(R.string.import_clear),
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
                                    contentDescription = stringResource(R.string.import_paste),
                                    tint = SonoraTextTertiary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = SonoraTextPrimary,
                        unfocusedTextColor = SonoraTextPrimary,
                        focusedBorderColor = YouTubeBrandRed,
                        unfocusedBorderColor = SonoraDivider,
                        cursorColor = YouTubeBrandRed
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = stringResource(R.string.import_youtube_footer),
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = SonoraTextTertiary,
                        fontSize = 11.sp
                    )
                )
            }

            is YouTubeImportState.Importing -> {
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
                            .background(YouTubeBrandRed.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(44.dp),
                            color = YouTubeBrandRed,
                            strokeWidth = 3.dp
                        )
                    }

                    val statusText = if (state.total > 0 && state.current > 0) {
                        stringResource(
                            R.string.import_youtube_progress_importing,
                            state.current,
                            state.total,
                            state.currentTrack
                        )
                    } else {
                        stringResource(R.string.import_loading_metadata)
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

                    if (state.total > 0) {
                        LinearProgressIndicator(
                            progress = { progressFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = YouTubeBrandRed,
                            trackColor = SonoraSurfaceHighlight
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = YouTubeBrandRed,
                            trackColor = SonoraSurfaceHighlight
                        )
                    }
                }
            }

            is YouTubeImportState.Success -> {
                val playlist = state.playlist
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
                        text = stringResource(R.string.import_success),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = SonoraTextPrimary
                        ),
                        textAlign = TextAlign.Center
                    )

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
                            val cover = playlist.displayCoverUrl
                            if (!cover.isNullOrBlank()) {
                                AsyncImage(
                                    model = cover,
                                    contentDescription = playlist.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(YouTubeBrandRed.copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                        contentDescription = null,
                                        tint = YouTubeBrandRed,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = playlist.name,
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = SonoraTextPrimary
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = pluralStringResource(
                                        R.plurals.import_saved_library,
                                        playlist.tracks.size,
                                        playlist.tracks.size
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SonoraTextSecondary
                                )
                            }
                        }
                    }
                }
            }

            is YouTubeImportState.Error -> {
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
                        text = stringResource(R.string.import_error_title),
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (val state = importState) {
                is YouTubeImportState.Idle -> {
                    Button(
                        onClick = {
                            val url = inputUrl.trim()
                            if (url.isNotBlank()) {
                                importState = YouTubeImportState.Importing(
                                    0,
                                    0,
                                    appContext.getString(R.string.import_initializing)
                                )
                                importJob = coroutineScope.launch {
                                    val result = importer.importAndSavePlaylist(
                                        urlOrId = url,
                                        repository = repository,
                                        onProgress = { cur, tot, title ->
                                            importState = YouTubeImportState.Importing(cur, tot, title)
                                        }
                                    )
                                    result.fold(
                                        onSuccess = { playlist ->
                                            importState = YouTubeImportState.Success(playlist)
                                            onPlaylistImported?.invoke(playlist)
                                        },
                                        onFailure = { err ->
                                            importState = YouTubeImportState.Error(
                                                err.message ?: appContext.getString(R.string.import_error_fetch_playlist)
                                            )
                                        }
                                    )
                                }
                            }
                        },
                        enabled = inputUrl.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = YouTubeBrandRed,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.import_action_transfer), fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = onDismissRequest) {
                        Text(stringResource(R.string.import_action_cancel), color = SonoraTextSecondary)
                    }
                }

                is YouTubeImportState.Importing -> {
                    TextButton(
                        onClick = {
                            importJob?.cancel()
                            importState = YouTubeImportState.Idle
                        }
                    ) {
                        Text(stringResource(R.string.import_action_cancel), color = SonoraTextSecondary)
                    }
                }

                is YouTubeImportState.Success -> {
                    val playlist = state.playlist
                    if (onPlayPlaylist != null && playlist.tracks.isNotEmpty()) {
                        Button(
                            onClick = {
                                onPlayPlaylist(playlist)
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
                            Text(stringResource(R.string.import_action_play_now), fontWeight = FontWeight.Bold)
                        }
                    }
                    TextButton(onClick = onDismissRequest) {
                        Text(stringResource(R.string.import_action_close), color = SonoraTextSecondary)
                    }
                }

                is YouTubeImportState.Error -> {
                    Button(
                        onClick = { importState = YouTubeImportState.Idle },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = YouTubeBrandRed,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.import_action_retry), fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = onDismissRequest) {
                        Text(stringResource(R.string.import_action_cancel), color = SonoraTextSecondary)
                    }
                }
            }
        }
    }
}
