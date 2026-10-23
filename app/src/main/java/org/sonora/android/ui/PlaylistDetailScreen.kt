package org.sonora.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import org.sonora.android.R
import org.sonora.android.api.Track
import org.sonora.android.data.Playlist
import org.sonora.android.playback.DownloadProgress
import org.sonora.android.ui.collection.CollectionEmptyState
import org.sonora.android.ui.collection.CollectionHero
import org.sonora.android.ui.collection.CollectionMetrics
import org.sonora.android.ui.collection.CollectionNowPlayingThread
import org.sonora.android.ui.collection.CollectionSectionHeader
import org.sonora.android.ui.collection.CollectionTopBar
import org.sonora.android.ui.collection.CollectionTrackRow
import org.sonora.android.ui.collection.formatCollectionDuration
import org.sonora.android.ui.collection.formatCollectionUpdated
import org.sonora.android.ui.collection.joinCollectionMeta
import org.sonora.android.ui.components.DownloadMenuEntry
import org.sonora.android.ui.components.HeldTrackAction
import org.sonora.android.ui.components.HeldTrackMenuGroup
import org.sonora.android.ui.components.LocalBottomChromeInset
import org.sonora.android.ui.components.SourceSwitchMenuItems
import org.sonora.android.ui.components.TrackSourceActions
import org.sonora.android.ui.components.mapQueueReorderByKey
import org.sonora.android.ui.components.stableTrackKeys
import org.sonora.android.ui.theme.LocalBackgroundScheme
import org.sonora.android.ui.theme.LocalMotionPreferences
import org.sonora.android.ui.theme.Radius
import org.sonora.android.ui.theme.SonoraBackground
import org.sonora.android.ui.theme.SonoraOpaqueSurfaceCard
import org.sonora.android.ui.theme.SonoraStatusOffline
import org.sonora.android.ui.theme.SonoraTextSecondary
import org.sonora.android.ui.theme.Spacing
import org.sonora.android.ui.theme.onColorFor
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private const val PLAYLIST_TRACKS_BASE_INDEX = 0

@Composable
fun PlaylistDetailScreen(
    playlist: Playlist?,
    currentTrackId: String?,
    isPlaying: Boolean,
    isFavorite: (String) -> Boolean,
    onBackClick: () -> Unit,
    onTrackClick: (Track, Int) -> Unit,
    onPlayPlaylist: () -> Unit,
    onShufflePlaylist: () -> Unit,
    onFavoriteToggle: (Track) -> Unit,
    onRemoveTrack: (String) -> Unit,
    onTogglePlaylistDownload: (() -> Unit)? = null,
    playlistDownloadProgress: DownloadProgress = DownloadProgress(),
    onDeletePlaylist: (() -> Unit)? = null,
    onRenamePlaylist: ((String, String?) -> Unit)? = null,
    onUpdatePlaylist: ((String, String?, String?) -> Unit)? = null,
    onMoveTrack: ((from: Int, to: Int) -> Unit)? = null,
    onAddToPlaylist: ((Track) -> Unit)? = null,
    onAddToQueue: ((Track) -> Unit)? = null,
    onPlayNextTrack: ((Track) -> Unit)? = null,
    onAddCollectionToQueue: (() -> Unit)? = null,
    onDiscoverTracks: (() -> Unit)? = null,
    sourceActions: TrackSourceActions? = null,
    readOnly: Boolean = false,
    isMix: Boolean = false,
    mixSaved: Boolean = false,
    mixFromLibrary: Boolean = false,
    mixSavedAt: Long? = null,
    onSaveMix: (() -> Unit)? = null,
    onRemoveMix: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (playlist == null) {
        SonoraBackground(modifier = modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                CollectionTopBar(
                    title = "",
                    collapsed = true,
                    onBack = onBackClick,
                )
                CollectionEmptyState(
                    icon = Icons.AutoMirrored.Filled.QueueMusic,
                    title = stringResource(R.string.playlist_not_found_title),
                    message = stringResource(R.string.playlist_not_found_body),
                    actionLabel = stringResource(R.string.playlist_back_action),
                    onAction = onBackClick,
                    modifier = Modifier.weight(1f),
                )
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
    var descriptionExpanded by remember(playlist.id) { mutableStateOf(false) }

    val trackKeys = remember(playlist.tracks) { stableTrackKeys(playlist.tracks) }
    val listState = rememberLazyListState()
    val scheme = LocalBackgroundScheme.current
    val motion = LocalMotionPreferences.current
    val scope = rememberCoroutineScope()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val mapped = mapQueueReorderByKey(trackKeys, from.key, to.key, PLAYLIST_TRACKS_BASE_INDEX)
        if (mapped != null) onMoveTrack?.invoke(mapped.first, mapped.second)
    }

    LaunchedEffect(playlist.id) {
        isReorderMode = false
        descriptionExpanded = false
        listState.scrollToItem(0)
    }

    val collapsed by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 120
        }
    }
    val currentIndex = remember(playlist.tracks, currentTrackId) {
        playlist.tracks.indexOfFirst { it.id == currentTrackId }
    }
    val hasCurrent = currentIndex >= 0
    val firstTrackItem = 2 + if (hasCurrent) 1 else 0
    val removeLabel = stringResource(R.string.playlist_remove_from_playlist_action)

    SonoraBackground(
        modifier = modifier.fillMaxSize(),
        coverUrl = playlist.coverUrl,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            CollectionTopBar(
                title = if (isReorderMode) stringResource(R.string.playlist_reorder_title) else playlist.name,
                collapsed = collapsed || isReorderMode,
                onBack = onBackClick,
            ) {
                if (isReorderMode) {
                    IconButton(
                        onClick = { isReorderMode = false },
                        modifier = Modifier.size(CollectionMetrics.ActionSize),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = stringResource(R.string.playlist_reorder_finish_content_description),
                            tint = scheme.accent,
                        )
                    }
                } else {
                    Box {
                        IconButton(
                            onClick = { topMenuExpanded = true },
                            modifier = Modifier.size(CollectionMetrics.ActionSize),
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.playlist_options_content_description),
                                tint = scheme.onBackground,
                            )
                        }
                        DropdownMenu(
                            expanded = topMenuExpanded,
                            onDismissRequest = { topMenuExpanded = false },
                            shape = RoundedCornerShape(16.dp),
                            containerColor = SonoraOpaqueSurfaceCard,
                            border = BorderStroke(1.dp, scheme.glassBorder),
                        ) {
                            if (onAddCollectionToQueue != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.collection_add_to_queue), color = scheme.onBackground) },
                                    leadingIcon = {
                                        Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null, tint = scheme.onBackgroundSecondary)
                                    },
                                    onClick = {
                                        topMenuExpanded = false
                                        onAddCollectionToQueue()
                                    },
                                )
                            }
                            if (!readOnly && onMoveTrack != null && playlist.tracks.size > 1) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.playlist_reorder_title), color = scheme.onBackground) },
                                    leadingIcon = {
                                        Icon(Icons.Default.SwapVert, contentDescription = null, tint = scheme.onBackgroundSecondary)
                                    },
                                    onClick = {
                                        topMenuExpanded = false
                                        isReorderMode = true
                                    },
                                )
                            }
                            if (!readOnly && (onUpdatePlaylist != null || onRenamePlaylist != null)) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.playlist_edit_action), color = scheme.onBackground) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Edit, contentDescription = null, tint = scheme.onBackgroundSecondary)
                                    },
                                    onClick = {
                                        topMenuExpanded = false
                                        editName = playlist.name
                                        editDescription = playlist.description ?: ""
                                        editCoverUrl = playlist.coverUrl ?: ""
                                        showEditDialog = true
                                    },
                                )
                            }
                            if (!readOnly && onDeletePlaylist != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.playlist_delete_action), color = SonoraStatusOffline) },
                                    leadingIcon = {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = SonoraStatusOffline)
                                    },
                                    onClick = {
                                        topMenuExpanded = false
                                        showDeleteConfirmDialog = true
                                    },
                                )
                            }
                        }
                    }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = LocalBottomChromeInset.current + Spacing.xl),
            ) {
                item(key = "hero", contentType = "hero") {
                    CollectionHero(
                        title = playlist.name,
                        subtitle = if (!readOnly) stringResource(R.string.playlist_creator_label) else null,
                        metadata = playlistMetadata(playlist),
                        description = playlist.description,
                        descriptionExpanded = descriptionExpanded,
                        onToggleDescription = { descriptionExpanded = !descriptionExpanded },
                        hasTracks = playlist.tracks.isNotEmpty(),
                        onPlay = onPlayPlaylist,
                        onShuffle = onShufflePlaylist,
                        downloadProgress = playlistDownloadProgress,
                        onToggleDownload = onTogglePlaylistDownload,
                        statusContent = when {
                            isMix -> {
                                {
                                    MixStatusContent(
                                        mixSaved = mixSaved,
                                        mixFromLibrary = mixFromLibrary,
                                        mixSavedAt = mixSavedAt,
                                        onSaveMix = onSaveMix,
                                        onRemoveMix = onRemoveMix,
                                    )
                                }
                            }
                            readOnly -> {
                                { AutoUpdatedStatus() }
                            }
                            else -> null
                        },
                        modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
                        artwork = {
                            PlaylistCoverMosaic(
                                playlist = playlist,
                                modifier = Modifier.fillMaxSize(),
                            )
                        },
                    )
                }

                if (playlist.tracks.isEmpty()) {
                    item(key = "empty", contentType = "empty") {
                        CollectionEmptyState(
                            icon = Icons.AutoMirrored.Filled.QueueMusic,
                            title = stringResource(R.string.playlist_empty_title),
                            message = stringResource(R.string.playlist_empty_body),
                            actionLabel = if (!readOnly && onDiscoverTracks != null) {
                                stringResource(R.string.collection_discover_tracks)
                            } else null,
                            onAction = if (!readOnly) onDiscoverTracks else null,
                        )
                    }
                } else {
                    item(key = "tracklist-header", contentType = "header") {
                        CollectionSectionHeader(
                            title = stringResource(R.string.playlist_tracks_count, playlist.tracks.size),
                            detail = formatCollectionDuration(playlist.totalDurationMs),
                        )
                    }

                    if (hasCurrent) {
                        item(key = "now-playing", contentType = "now-playing") {
                            val currentTrack = playlist.tracks.getOrNull(currentIndex)
                            if (currentTrack != null) {
                                CollectionNowPlayingThread(
                                    title = currentTrack.title,
                                    isPlaying = isPlaying,
                                    onClick = {
                                        scope.launch {
                                            val target = firstTrackItem + currentIndex
                                            if (motion.reduceMotion) listState.scrollToItem(target)
                                            else listState.animateScrollToItem(target)
                                        }
                                    },
                                )
                            }
                        }
                    }

                    itemsIndexed(
                        items = playlist.tracks,
                        key = { index, _ -> trackKeys.getOrElse(index) { "track_$index" } },
                        contentType = { _, _ -> "track" },
                    ) { index, track ->
                        val rowKey = trackKeys.getOrElse(index) { "track_$index" }
                        val reorderEnabled = isReorderMode && onMoveTrack != null
                        val isCurrent = track.id == currentTrackId
                        val extras = if (!readOnly) {
                            listOf(
                                HeldTrackMenuGroup(
                                    listOf(
                                        HeldTrackAction(
                                            icon = Icons.Default.DeleteOutline,
                                            label = removeLabel,
                                            tint = SonoraStatusOffline,
                                            onClick = { onRemoveTrack(track.id) },
                                        )
                                    )
                                )
                            )
                        } else {
                            emptyList()
                        }

                        ReorderableItem(
                            state = reorderState,
                            key = rowKey,
                            enabled = reorderEnabled,
                        ) { isDragging ->
                            CollectionTrackRow(
                                track = track,
                                trackNumber = null,
                                showArtwork = true,
                                showArtist = true,
                                isCurrent = isCurrent,
                                isPlaying = isCurrent && isPlaying,
                                isFavorite = isFavorite(track.id),
                                onPlay = {
                                    if (!isReorderMode) onTrackClick(track, index)
                                },
                                onFavoriteClick = { onFavoriteToggle(track) },
                                fallbackCoverUrl = playlist.coverUrl,
                                contextMenuExtras = extras,
                                reorderMode = reorderEnabled,
                                isDragging = isDragging,
                                dragHandleModifier = if (reorderEnabled) {
                                    Modifier.longPressDraggableHandle()
                                } else {
                                    Modifier
                                },
                                menuContent = { dismiss ->
                                    if (!readOnly) {
                                        DropdownMenuItem(
                                            text = { Text(removeLabel, color = SonoraStatusOffline) },
                                            leadingIcon = {
                                                Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = SonoraStatusOffline)
                                            },
                                            onClick = {
                                                dismiss()
                                                onRemoveTrack(track.id)
                                            },
                                        )
                                    }
                                    DownloadMenuEntry(
                                        track = track,
                                        onDismiss = dismiss,
                                        accent = scheme.accent,
                                        primary = scheme.onBackground,
                                        secondary = scheme.onBackgroundSecondary,
                                    )
                                    if (onPlayNextTrack != null) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.playlist_play_next_action), color = scheme.onBackground) },
                                            leadingIcon = {
                                                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = scheme.onBackgroundSecondary)
                                            },
                                            onClick = {
                                                dismiss()
                                                onPlayNextTrack(track)
                                            },
                                        )
                                    }
                                    if (onAddToQueue != null) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.playlist_add_to_queue_action), color = scheme.onBackground) },
                                            leadingIcon = {
                                                Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null, tint = scheme.onBackgroundSecondary)
                                            },
                                            onClick = {
                                                dismiss()
                                                onAddToQueue(track)
                                            },
                                        )
                                    }
                                    if (onAddToPlaylist != null) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.playlist_add_to_playlist_action), color = scheme.onBackground) },
                                            leadingIcon = {
                                                Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null, tint = scheme.onBackgroundSecondary)
                                            },
                                            onClick = {
                                                dismiss()
                                                onAddToPlaylist(track)
                                            },
                                        )
                                    }
                                    if (sourceActions != null &&
                                        (track.id.startsWith("yt_") || track.id.startsWith("spotify_")) &&
                                        !track.provider.equals("Local", ignoreCase = true)
                                    ) {
                                        SourceSwitchMenuItems(
                                            sourceOverride = sourceActions.overrideFor(track.id),
                                            onPlayViaSource = { src ->
                                                dismiss()
                                                sourceActions.onSelect(track, src)
                                            },
                                            onUseGlobalSource = {
                                                dismiss()
                                                sourceActions.onClear(track)
                                            },
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            shape = RoundedCornerShape(28.dp),
            containerColor = SonoraOpaqueSurfaceCard,
            title = {
                Text(
                    text = stringResource(R.string.playlist_edit_action),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = scheme.onBackground,
                )
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text(stringResource(R.string.playlist_name_label), color = scheme.onBackgroundSecondary) },
                        singleLine = true,
                        colors = collectionTextFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = editDescription,
                        onValueChange = { editDescription = it },
                        label = { Text(stringResource(R.string.playlist_description_label), color = scheme.onBackgroundSecondary) },
                        maxLines = 3,
                        colors = collectionTextFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = editCoverUrl,
                        onValueChange = { editCoverUrl = it },
                        label = { Text(stringResource(R.string.playlist_cover_url_label), color = scheme.onBackgroundSecondary) },
                        singleLine = true,
                        colors = collectionTextFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = editName.isNotBlank(),
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
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = scheme.accent,
                        contentColor = onColorFor(scheme.accent),
                    ),
                    modifier = Modifier.heightIn(min = CollectionMetrics.ActionSize),
                ) {
                    Text(stringResource(R.string.playlist_save_action), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text(stringResource(R.string.playlist_cancel_action), color = scheme.onBackgroundSecondary)
                }
            },
        )
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            shape = RoundedCornerShape(28.dp),
            containerColor = SonoraOpaqueSurfaceCard,
            title = {
                Text(
                    text = stringResource(R.string.playlist_delete_confirm_title),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = scheme.onBackground,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.playlist_delete_confirm_message, playlist.name),
                    color = scheme.onBackgroundSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        onDeletePlaylist?.invoke()
                    },
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SonoraStatusOffline,
                        contentColor = onColorFor(SonoraStatusOffline),
                    ),
                    modifier = Modifier.heightIn(min = CollectionMetrics.ActionSize),
                ) {
                    Text(stringResource(R.string.playlist_delete_confirm_action), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text(stringResource(R.string.playlist_cancel_action), color = scheme.onBackgroundSecondary)
                }
            },
        )
    }
}

@Composable
private fun playlistMetadata(playlist: Playlist): String? = joinCollectionMeta(
    pluralStringResource(R.plurals.collection_track_count, playlist.tracks.size, playlist.tracks.size),
    formatCollectionDuration(playlist.totalDurationMs),
    formatCollectionUpdated(playlist.updatedAt),
)

@Composable
private fun AutoUpdatedStatus() {
    val scheme = LocalBackgroundScheme.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.AutoAwesome,
            contentDescription = null,
            tint = scheme.accent,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text = stringResource(R.string.playlist_auto_updated_chip),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onBackgroundSecondary,
        )
    }
}

@Composable
private fun MixStatusContent(
    mixSaved: Boolean,
    mixFromLibrary: Boolean,
    mixSavedAt: Long?,
    onSaveMix: (() -> Unit)?,
    onRemoveMix: (() -> Unit)?,
) {
    val scheme = LocalBackgroundScheme.current
    val savedDate = mixSavedAt?.let {
        java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
            .format(java.util.Date(it))
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = scheme.accent,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(Spacing.xs))
            Text(
                text = when {
                    (mixFromLibrary || mixSaved) && savedDate != null ->
                        stringResource(R.string.mix_saved_copy_on, savedDate)
                    else -> stringResource(R.string.mix_updated_chip)
                },
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onBackgroundSecondary,
            )
        }
        if (mixFromLibrary) {
            OutlinedButton(
                onClick = { onRemoveMix?.invoke() },
                enabled = onRemoveMix != null,
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.dp, scheme.accent.copy(alpha = 0.55f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.accent),
                modifier = Modifier.heightIn(min = CollectionMetrics.ActionSize),
            ) {
                Text(stringResource(R.string.mix_remove_action), fontWeight = FontWeight.SemiBold)
            }
        } else if (!mixSaved && onSaveMix != null) {
            Button(
                onClick = onSaveMix,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = scheme.accent,
                    contentColor = onColorFor(scheme.accent),
                ),
                modifier = Modifier.heightIn(min = CollectionMetrics.ActionSize),
            ) {
                Text(stringResource(R.string.mix_save_action), fontWeight = FontWeight.Bold)
            }
        } else if (mixSaved) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.mix_saved),
                    color = scheme.accent,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                )
                if (onRemoveMix != null) {
                    TextButton(onClick = onRemoveMix) {
                        Text(stringResource(R.string.mix_remove_action), color = scheme.onBackgroundSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun collectionTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = LocalBackgroundScheme.current.onBackground,
    unfocusedTextColor = LocalBackgroundScheme.current.onBackground,
    focusedBorderColor = LocalBackgroundScheme.current.accent,
    unfocusedBorderColor = LocalBackgroundScheme.current.glassBorder,
)

@Composable
fun PlaylistCoverMosaic(
    playlist: Playlist,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalBackgroundScheme.current
    val customCover = playlist.coverUrl?.takeIf { it.isNotBlank() }
    if (customCover != null) {
        AsyncImage(
            model = customCover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
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
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    AsyncImage(
                        model = distinctArtworks[1],
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    AsyncImage(
                        model = distinctArtworks[2],
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    AsyncImage(
                        model = distinctArtworks[3],
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
        distinctArtworks.isNotEmpty() -> {
            AsyncImage(
                model = distinctArtworks[0],
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = modifier,
            )
        }
        else -> {
            Box(
                modifier = modifier.background(
                    Brush.verticalGradient(
                        listOf(
                            scheme.accent.copy(alpha = 0.38f),
                            scheme.glassTint,
                        )
                    )
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = null,
                    tint = scheme.accent,
                    modifier = Modifier.size(64.dp),
                )
            }
        }
    }
}
