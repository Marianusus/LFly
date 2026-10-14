package org.sonora.android.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import org.sonora.android.R
import org.sonora.android.api.Track
import org.sonora.android.data.Playlist
import org.sonora.android.playback.PlaybackState
import org.sonora.android.ui.components.AnimatedPlayingBars
import org.sonora.android.ui.components.LocalBottomChromeInset
import org.sonora.android.ui.components.SectionHeader
import org.sonora.android.ui.components.SourceSwitchMenuItems
import org.sonora.android.ui.components.TrackSourceActions
import org.sonora.android.ui.theme.GlassCard
import org.sonora.android.ui.theme.GlassPanel
import org.sonora.android.ui.theme.GlassPlaceholder
import org.sonora.android.ui.theme.GlassTopBar
import org.sonora.android.ui.theme.LocalBackgroundScheme
import org.sonora.android.ui.theme.Radius
import org.sonora.android.ui.theme.SonoraBackground
import org.sonora.android.ui.theme.SonoraEmerald
import org.sonora.android.ui.theme.SonoraPurple
import org.sonora.android.ui.theme.SonoraStatusOffline
import org.sonora.android.ui.theme.SonoraSurfaceCard
import org.sonora.android.ui.theme.SonoraTextPrimary
import org.sonora.android.ui.theme.SonoraTheme
import org.sonora.android.ui.theme.SonoraTextSecondary
import org.sonora.android.ui.theme.SonoraTextTertiary
import org.sonora.android.ui.components.mapQueueReorderByKey
import org.sonora.android.ui.theme.Spacing
import org.sonora.android.ui.theme.pressScale
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Playlist reordering operates on local track indices (0-based). The lazy-list header
 * offset is irrelevant here because reorder keys are resolved to track indices by identity.
 */
private const val PLAYLIST_TRACKS_BASE_INDEX = 0

/**
 * Spotify-style playlist detail screen featuring:
 * - Large artwork header (supporting Spotify 2x2 mosaic collage or single artwork), title, creator, duration, and Pill Play/Shuffle buttons
 * - Numbered track list with active equalizer playback indicator
 * - Favorite heart toggle
 * - 3-dots track options menu with "Remove from playlist", "Add to queue", "Add to playlist", and reorder options
 * - Quick reordering mode with direct up/down controls
 * - Playlist edit (name, description, coverUrl) and delete capabilities
 */
@Composable
fun PlaylistDetailScreen(
    playlist: Playlist?,
    playbackState: PlaybackState,
    isFavorite: (String) -> Boolean,
    onBackClick: () -> Unit,
    onTrackClick: (Track, Int) -> Unit,
    onPlayPlaylist: () -> Unit,
    onShufflePlaylist: () -> Unit,
    onFavoriteToggle: (Track) -> Unit,
    onRemoveTrack: (String) -> Unit,
    onDeletePlaylist: (() -> Unit)? = null,
    onRenamePlaylist: ((String, String?) -> Unit)? = null,
    onUpdatePlaylist: ((String, String?, String?) -> Unit)? = null,
    onMoveTrack: ((from: Int, to: Int) -> Unit)? = null,
    onAddToPlaylist: ((Track) -> Unit)? = null,
    onAddToQueue: ((Track) -> Unit)? = null,
    onPlayNextTrack: ((Track) -> Unit)? = null,
    sourceActions: TrackSourceActions? = null,
    modifier: Modifier = Modifier
) {
    if (playlist == null) {
        val scheme = LocalBackgroundScheme.current
        SonoraBackground(modifier = modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(Spacing.xl),
                contentAlignment = Alignment.Center
            ) {
                GlassPlaceholder {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = null,
                        tint = scheme.onBackgroundTertiary,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .size(52.dp)
                    )
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    Text(
                        text = stringResource(R.string.playlist_not_found_title),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = SonoraTextPrimary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(Spacing.lg))
                    Button(
                        onClick = onBackClick,
                        shape = RoundedCornerShape(Radius.Medium),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = scheme.accent,
                            contentColor = if (scheme.isLight) Color.White else Color.Black
                        ),
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text(stringResource(R.string.playlist_back_action), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        return
    }

    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var editName by remember(playlist.name) { mutableStateOf(playlist.name) }
    var editDescription by remember(playlist.description) { mutableStateOf(playlist.description ?: "") }
    var editCoverUrl by remember(playlist.coverUrl) { mutableStateOf(playlist.coverUrl ?: "") }
    var topMenuExpanded by remember { mutableStateOf(false) }
    var isReorderMode by remember { mutableStateOf(false) }

    // Leaving reorder mode when the screen shows a different playlist avoids a stale edit state.
    LaunchedEffect(playlist.id) { isReorderMode = false }

    val trackKeys = remember(playlist.tracks) {
        val counts = mutableMapOf<String, Int>()
        playlist.tracks.map { track ->
            val count = counts.getOrDefault(track.id, 0)
            counts[track.id] = count + 1
            if (count == 0) track.id else "${track.id}#$count"
        }
    }

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val mapped = mapQueueReorderByKey(trackKeys, from.key, to.key, PLAYLIST_TRACKS_BASE_INDEX)
        if (mapped != null) {
            onMoveTrack?.invoke(mapped.first, mapped.second)
        }
    }

    SonoraBackground(
        modifier = modifier.fillMaxSize(),
        coverUrl = playlist.coverUrl
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Bar with Back Button & More Options (or Done button in reorder mode).
            // Kept outside the LazyColumn so the reorder controls never scroll out of reach.
            GlassTopBar(
                title = if (isReorderMode) stringResource(R.string.playlist_reorder_title) else stringResource(R.string.playlist_title),
                onBack = onBackClick
            ) {
                if (isReorderMode) {
                    IconButton(onClick = { isReorderMode = false }) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = stringResource(R.string.playlist_reorder_finish_content_description),
                            tint = LocalBackgroundScheme.current.accent
                        )
                    }
                } else {
                    Box {
                        IconButton(onClick = { topMenuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.playlist_options_content_description),
                                tint = LocalBackgroundScheme.current.onBackground
                            )
                        }

                        DropdownMenu(
                            expanded = topMenuExpanded,
                            onDismissRequest = { topMenuExpanded = false },
                            shape = RoundedCornerShape(16.dp),
                            containerColor = org.sonora.android.ui.theme.SonoraOpaqueSurfaceCard,
                            border = androidx.compose.foundation.BorderStroke(1.dp, SonoraTheme.colors.cardBorder)
                        ) {
                            if (onMoveTrack != null && playlist.tracks.size > 1) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.playlist_reorder_title), color = SonoraTextPrimary) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.SwapVert,
                                            contentDescription = null,
                                            tint = SonoraEmerald
                                        )
                                    },
                                    onClick = {
                                        topMenuExpanded = false
                                        isReorderMode = true
                                    }
                                )
                            }
                            if (onUpdatePlaylist != null || onRenamePlaylist != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.playlist_edit_action), color = SonoraTextPrimary) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = null,
                                            tint = SonoraEmerald
                                        )
                                    },
                                    onClick = {
                                        topMenuExpanded = false
                                        editName = playlist.name
                                        editDescription = playlist.description ?: ""
                                        editCoverUrl = playlist.coverUrl ?: ""
                                        showEditDialog = true
                                    }
                                )
                            }
                            if (onDeletePlaylist != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.playlist_delete_action), color = SonoraStatusOffline) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = null,
                                            tint = SonoraStatusOffline
                                        )
                                    },
                                    onClick = {
                                        topMenuExpanded = false
                                        showDeleteConfirmDialog = true
                                    }
                                )
                            }
                        }
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                state = listState,
                contentPadding = PaddingValues(bottom = LocalBottomChromeInset.current + 24.dp)
            ) {
            // Liquid Glass hero header: artwork, title, creator, duration and actions
            item {
                PlaylistHeroPanel(
                    playlist = playlist,
                    onPlayPlaylist = onPlayPlaylist,
                    onShufflePlaylist = onShufflePlaylist,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.gutter, vertical = Spacing.sm)
                )
            }

            // Tracklist Section
            if (playlist.tracks.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.xl, vertical = Spacing.xl),
                        contentAlignment = Alignment.Center
                    ) {
                        GlassPlaceholder {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                contentDescription = null,
                                tint = LocalBackgroundScheme.current.onBackgroundTertiary,
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(Spacing.sm))
                            Text(
                                text = stringResource(R.string.playlist_empty_title),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = SonoraTextPrimary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(Spacing.xs))
                            Text(
                                text = stringResource(R.string.playlist_empty_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = SonoraTextSecondary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            } else {
                item {
                    SectionHeader(title = stringResource(R.string.playlist_tracks_count, playlist.tracks.size))
                }

                // Numbered Track Items
                itemsIndexed(playlist.tracks, key = { index, _ -> trackKeys.getOrElse(index) { "track_$index" } }) { index, track ->
                    val trackNumber = index + 1
                    val isCurrent = playbackState.currentTrack?.id == track.id
                    val isCurrentlyPlaying = isCurrent && playbackState.isPlaying
                    val rowKey = trackKeys.getOrElse(index) { "track_$index" }
                    val reorderEnabled = isReorderMode && onMoveTrack != null

                    ReorderableItem(
                        state = reorderState,
                        key = rowKey,
                        enabled = reorderEnabled
                    ) { isDragging ->
                        PlaylistTrackRow(
                            trackNumber = trackNumber,
                            track = track,
                            isCurrent = isCurrent,
                            isPlaying = isCurrentlyPlaying,
                            isFavorite = isFavorite(track.id),
                            isReorderMode = isReorderMode,
                            isDragging = isDragging,
                            onClick = {
                                if (!isReorderMode) {
                                    onTrackClick(track, index)
                                }
                            },
                            onFavoriteToggle = { onFavoriteToggle(track) },
                            onRemoveFromPlaylist = { onRemoveTrack(track.id) },
                            onAddToQueue = onAddToQueue?.let { { it(track) } },
                            onPlayNextTrack = onPlayNextTrack?.let { { it(track) } },
                            onAddToPlaylist = onAddToPlaylist?.let { { it(track) } },
                            sourceActions = sourceActions,
                            dragHandleModifier = if (reorderEnabled) {
                                Modifier.longPressDraggableHandle()
                            } else {
                                Modifier
                            },
                            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs)
                        )
                    }
                }
            }
            }
        }
    }

    // Edit Playlist Dialog
    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            containerColor = SonoraSurfaceCard,
            title = {
                Text(
                    text = stringResource(R.string.playlist_edit_action),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = SonoraTextPrimary
                    )
                )
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text(stringResource(R.string.playlist_name_label), color = SonoraTextSecondary) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = SonoraTextPrimary,
                            unfocusedTextColor = SonoraTextPrimary,
                            focusedBorderColor = SonoraEmerald,
                            unfocusedBorderColor = SonoraTextTertiary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = editDescription,
                        onValueChange = { editDescription = it },
                        label = { Text(stringResource(R.string.playlist_description_label), color = SonoraTextSecondary) },
                        maxLines = 3,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = SonoraTextPrimary,
                            unfocusedTextColor = SonoraTextPrimary,
                            focusedBorderColor = SonoraEmerald,
                            unfocusedBorderColor = SonoraTextTertiary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = editCoverUrl,
                        onValueChange = { editCoverUrl = it },
                        label = { Text(stringResource(R.string.playlist_cover_url_label), color = SonoraTextSecondary) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = SonoraTextPrimary,
                            unfocusedTextColor = SonoraTextPrimary,
                            focusedBorderColor = SonoraEmerald,
                            unfocusedBorderColor = SonoraTextTertiary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (editName.isNotBlank()) {
                            val cleanName = editName.trim()
                            val cleanDesc = editDescription.trim().takeIf { it.isNotBlank() }
                            val cleanCover = editCoverUrl.trim().takeIf { it.isNotBlank() }
                            if (onUpdatePlaylist != null) {
                                onUpdatePlaylist(cleanName, cleanDesc, cleanCover)
                            } else {
                                onRenamePlaylist?.invoke(cleanName, cleanDesc)
                            }
                            showEditDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SonoraEmerald,
                        contentColor = Color.Black
                    )
                ) {
                    Text(stringResource(R.string.playlist_save_action), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text(stringResource(R.string.playlist_cancel_action), color = SonoraTextSecondary)
                }
            }
        )
    }

    // Delete Playlist Confirmation Dialog
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            containerColor = SonoraSurfaceCard,
            title = {
                Text(
                    text = stringResource(R.string.playlist_delete_confirm_title),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = SonoraTextPrimary
                    )
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.playlist_delete_confirm_message, playlist.name),
                    color = SonoraTextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        onDeletePlaylist?.invoke()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SonoraStatusOffline,
                        contentColor = Color.White
                    )
                ) {
                    Text(stringResource(R.string.playlist_delete_confirm_action), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text(stringResource(R.string.playlist_cancel_action), color = SonoraTextSecondary)
                }
            }
        )
    }
}

/**
 * Liquid Glass hero panel: large artwork (single cover or 2x2 mosaic), title,
 * description, creator/duration metadata and the primary Play / Shuffle actions.
 */
@Composable
private fun PlaylistHeroPanel(
    playlist: Playlist,
    onPlayPlaylist: () -> Unit,
    onShufflePlaylist: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current
    val hasTracks = playlist.tracks.isNotEmpty()

    GlassPanel(modifier = modifier, shape = RoundedCornerShape(Radius.Large)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            GlassCard(
                modifier = Modifier.size(210.dp),
                shape = RoundedCornerShape(Radius.Medium)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    PlaylistCoverMosaic(
                        playlist = playlist,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.lg))

            Text(
                text = playlist.name,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp
                ),
                color = SonoraTextPrimary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (!playlist.description.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = playlist.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SonoraTextSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(Spacing.sm))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(R.string.playlist_creator_label),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = scheme.accent
                )
                Text(
                    text = " • ",
                    color = SonoraTextTertiary,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = pluralStringResource(R.plurals.playlist_song_count, playlist.trackCount, playlist.trackCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = SonoraTextSecondary
                )
                if (playlist.totalDurationMs > 0) {
                    Text(
                        text = stringResource(R.string.playlist_duration_suffix, playlist.formattedDuration),
                        style = MaterialTheme.typography.bodySmall,
                        color = SonoraTextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.lg))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onPlayPlaylist,
                    enabled = hasTracks,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = scheme.accent,
                        contentColor = if (scheme.isLight) Color.White else Color.Black
                    ),
                    shape = RoundedCornerShape(Radius.Medium),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    Text(text = stringResource(R.string.playlist_play_action), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }

                OutlinedButton(
                    onClick = onShufflePlaylist,
                    enabled = hasTracks,
                    border = BorderStroke(1.dp, scheme.glassBorder),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = scheme.glassTint,
                        contentColor = SonoraTextPrimary
                    ),
                    shape = RoundedCornerShape(Radius.Medium)
                ) {
                    Icon(
                        imageVector = Icons.Default.Shuffle,
                        contentDescription = stringResource(R.string.playlist_shuffle_content_description),
                        tint = if (hasTracks) scheme.accent else SonoraTextTertiary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    Text(text = stringResource(R.string.playlist_shuffle_action), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * Spotify-style playlist artwork renderer:
 * Displays custom cover art if present. If absent, renders a 2x2 mosaic collage of up to 4 distinct album artworks,
 * falls back to the first track's artwork if fewer than 4 are available, or a gradient placeholder if empty.
 */
@Composable
fun PlaylistCoverMosaic(
    playlist: Playlist,
    modifier: Modifier = Modifier
) {
    val customCover = playlist.coverUrl?.takeIf { it.isNotBlank() }
    if (customCover != null) {
        AsyncImage(
            model = customCover,
            contentDescription = playlist.name,
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
        return
    }

    val distinctArtworks = remember(playlist.tracks) {
        playlist.tracks
            .mapNotNull { it.coverUrl?.takeIf { url -> url.isNotBlank() } }
            .distinct()
            .take(4)
    }

    when {
        distinctArtworks.size >= 4 -> {
            Column(modifier = modifier) {
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    AsyncImage(
                        model = distinctArtworks[0],
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                    AsyncImage(
                        model = distinctArtworks[1],
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    AsyncImage(
                        model = distinctArtworks[2],
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                    AsyncImage(
                        model = distinctArtworks[3],
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
            }
        }
        distinctArtworks.isNotEmpty() -> {
            AsyncImage(
                model = distinctArtworks[0],
                contentDescription = playlist.name,
                contentScale = ContentScale.Crop,
                modifier = modifier
            )
        }
        else -> {
            Box(
                modifier = modifier.background(
                    Brush.verticalGradient(
                        listOf(
                            SonoraPurple.copy(alpha = 0.45f),
                            SonoraSurfaceCard
                        )
                    )
                ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = null,
                    tint = SonoraEmerald,
                    modifier = Modifier.size(72.dp)
                )
            }
        }
    }
}

/**
 * Single numbered track row in playlist detail screen rendered as a Liquid Glass card.
 */
@Composable
private fun PlaylistTrackRow(
    trackNumber: Int,
    track: Track,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isFavorite: Boolean,
    isReorderMode: Boolean,
    isDragging: Boolean = false,
    onClick: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onRemoveFromPlaylist: () -> Unit,
    onAddToQueue: (() -> Unit)?,
    onPlayNextTrack: (() -> Unit)?,
    onAddToPlaylist: (() -> Unit)?,
    sourceActions: TrackSourceActions? = null,
    modifier: Modifier = Modifier,
    dragHandleModifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current
    var menuExpanded by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(Radius.Small)
    val dragScale by animateFloatAsState(
        targetValue = if (isDragging) 1.02f else 1f,
        label = "playlistTrackDragScale"
    )

    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .scale(dragScale)
            .pressScale(interaction)
            .clip(shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        shape = shape,
        tint = if (isDragging) scheme.accent.copy(alpha = 0.16f) else scheme.glassTint,
        border = if (isDragging) scheme.accent.copy(alpha = 0.55f) else scheme.glassBorder
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Track number or active playing equalizer indicator
            Box(
                modifier = Modifier.width(32.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isCurrent && isPlaying) {
                    AnimatedPlayingBars(
                        isPlaying = true,
                        modifier = Modifier.size(16.dp),
                        barColor = scheme.accent
                    )
                } else if (isCurrent) {
                    Icon(
                        imageVector = Icons.Default.Equalizer,
                        contentDescription = stringResource(R.string.playlist_playing_content_description),
                        tint = scheme.accent,
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    Text(
                        text = "$trackNumber",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = SonoraTextTertiary,
                            fontWeight = FontWeight.Medium
                        ),
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.width(Spacing.sm))

            // Track Artwork Thumbnail
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(scheme.glassTint),
                contentAlignment = Alignment.Center
            ) {
                if (!track.artworkUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = track.artworkUrl,
                        contentDescription = track.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = SonoraTextTertiary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(Spacing.md))

            // Title and Artist
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 15.sp
                    ),
                    color = if (isCurrent) scheme.accent else SonoraTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (track.artist.isNotBlank()) {
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                        color = SonoraTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(Spacing.sm))

            // Track Duration (only in normal mode)
            if (!isReorderMode) {
                Text(
                    text = track.formattedDuration,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                    color = SonoraTextTertiary
                )

                Spacer(modifier = Modifier.width(Spacing.xs))

                // Favorite Heart Button
                IconButton(
                    onClick = onFavoriteToggle,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = if (isFavorite) stringResource(R.string.playlist_favorite_remove_content_description) else stringResource(R.string.playlist_favorite_add_content_description),
                        tint = if (isFavorite) scheme.accent else SonoraTextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // 3-Dots Options Menu
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.playlist_more_options_content_description),
                            tint = SonoraTextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        shape = RoundedCornerShape(16.dp),
                        containerColor = org.sonora.android.ui.theme.SonoraOpaqueSurfaceCard,
                        border = androidx.compose.foundation.BorderStroke(1.dp, SonoraTheme.colors.cardBorder)
                    ) {
                        // "Remove from playlist" option
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.playlist_remove_from_playlist_action), color = SonoraStatusOffline) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = null,
                                    tint = SonoraStatusOffline
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onRemoveFromPlaylist()
                            }
                        )

                        // "Play next" option
                        if (onPlayNextTrack != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.playlist_play_next_action), color = SonoraTextPrimary) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = scheme.accent
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onPlayNextTrack()
                                }
                            )
                        }

                        // "Add to Queue" option
                        if (onAddToQueue != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.playlist_add_to_queue_action), color = SonoraTextPrimary) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                        contentDescription = null,
                                        tint = scheme.accent
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onAddToQueue()
                                }
                            )
                        }

                        // "Add to another playlist" option
                        if (onAddToPlaylist != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.playlist_add_to_playlist_action), color = SonoraTextPrimary) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                                        contentDescription = null,
                                        tint = scheme.accent
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onAddToPlaylist()
                                }
                            )
                        }

                        if (sourceActions != null &&
                            (track.id.startsWith("yt_") || track.id.startsWith("spotify_")) &&
                            !track.provider.equals("Local", ignoreCase = true)
                        ) {
                            SourceSwitchMenuItems(
                                sourceOverride = sourceActions.overrideFor(track.id),
                                onPlayViaSource = { src ->
                                    menuExpanded = false
                                    sourceActions.onSelect(track, src)
                                },
                                onUseGlobalSource = {
                                    menuExpanded = false
                                    sourceActions.onClear(track)
                                }
                            )
                        }

                    }
                }
            } else {
                // Reorder mode: long-press drag handle (same architecture as the queue).
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .then(dragHandleModifier),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.DragHandle,
                        contentDescription = stringResource(R.string.playlist_drag_handle_content_description),
                        tint = scheme.onBackgroundSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}
