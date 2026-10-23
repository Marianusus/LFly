package org.sonora.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import org.sonora.android.R
import org.sonora.android.api.Album
import org.sonora.android.api.Track
import org.sonora.android.playback.DownloadProgress
import org.sonora.android.ui.collection.CollectionEmptyState
import org.sonora.android.ui.collection.CollectionHero
import org.sonora.android.ui.collection.CollectionLoadingSkeleton
import org.sonora.android.ui.collection.CollectionMetrics
import org.sonora.android.ui.collection.CollectionNowPlayingThread
import org.sonora.android.ui.collection.CollectionSectionHeader
import org.sonora.android.ui.collection.CollectionTopBar
import org.sonora.android.ui.collection.CollectionTrackRow
import org.sonora.android.ui.collection.formatCollectionDuration
import org.sonora.android.ui.collection.joinCollectionMeta
import org.sonora.android.ui.components.DownloadMenuEntry
import org.sonora.android.ui.components.LocalBottomChromeInset
import org.sonora.android.ui.components.SourceSwitchMenuItems
import org.sonora.android.ui.components.TrackSourceActions
import org.sonora.android.ui.components.stableTrackKeys
import org.sonora.android.ui.theme.LocalBackgroundScheme
import org.sonora.android.ui.theme.SonoraBackground
import org.sonora.android.ui.theme.SonoraTextSecondary
import org.sonora.android.ui.theme.Spacing

@Composable
fun AlbumDetailScreen(
    album: Album?,
    tracks: List<Track>,
    isLoading: Boolean,
    errorMessage: String?,
    currentTrackId: String?,
    isPlaying: Boolean,
    isFavorite: (String) -> Boolean,
    onBackClick: () -> Unit,
    onTrackClick: (Track, Int) -> Unit,
    onPlayAlbum: () -> Unit,
    onShuffleAlbum: () -> Unit,
    onFavoriteToggle: (Track) -> Unit,
    onRetry: () -> Unit,
    onAddToQueue: ((Track) -> Unit)? = null,
    onPlayNextTrack: ((Track) -> Unit)? = null,
    onAddToPlaylist: ((Track) -> Unit)? = null,
    onAddCollectionToQueue: (() -> Unit)? = null,
    downloadProgress: DownloadProgress = DownloadProgress(),
    onToggleCollectionDownload: (() -> Unit)? = null,
    sourceActions: TrackSourceActions? = null,
    modifier: Modifier = Modifier,
) {
    SonoraBackground(
        modifier = modifier.fillMaxSize(),
        coverUrl = album?.coverUrl,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            val listState = rememberLazyListState()
            val collapsed by remember {
                derivedStateOf {
                    listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 120
                }
            }
            var topMenuExpanded by remember { mutableStateOf(false) }
            val scheme = LocalBackgroundScheme.current

            CollectionTopBar(
                title = album?.title ?: stringResource(R.string.album_top_bar_title),
                collapsed = album == null || collapsed,
                onBack = onBackClick,
            ) {
                if (onAddCollectionToQueue != null) Box {
                    IconButton(
                        onClick = { topMenuExpanded = true },
                        modifier = Modifier.size(CollectionMetrics.ActionSize),
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.collection_more_options),
                            tint = scheme.onBackground,
                        )
                    }
                    DropdownMenu(
                        expanded = topMenuExpanded,
                        onDismissRequest = { topMenuExpanded = false },
                        shape = RoundedCornerShape(16.dp),
                        containerColor = org.sonora.android.ui.theme.SonoraOpaqueSurfaceCard,
                        border = androidx.compose.foundation.BorderStroke(1.dp, scheme.glassBorder),
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.collection_add_to_queue), color = scheme.onBackground) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                        contentDescription = null,
                                        tint = scheme.onBackgroundSecondary,
                                    )
                                },
                            onClick = {
                                topMenuExpanded = false
                                onAddCollectionToQueue()
                            },
                        )
                    }
                }
            }

            when {
                isLoading && album == null -> {
                    CollectionLoadingSkeleton(modifier = Modifier.weight(1f))
                }

                errorMessage != null && album == null -> {
                    CollectionEmptyState(
                        icon = Icons.Default.Album,
                        title = stringResource(R.string.album_error_load_title),
                        message = errorMessage,
                        retryLabel = stringResource(R.string.album_retry_action),
                        onRetry = onRetry,
                        modifier = Modifier.weight(1f),
                    )
                }

                album != null -> {
                    val keys = remember(tracks) { stableTrackKeys(tracks) }
                    val currentIndex = remember(tracks, currentTrackId) {
                        tracks.indexOfFirst { it.id == currentTrackId }
                    }
                    val hasCurrent = currentIndex >= 0
                    val firstTrackItem = 2 + if (hasCurrent) 1 else 0
                    val scope = rememberCoroutineScope()
                    val motion = org.sonora.android.ui.theme.LocalMotionPreferences.current

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = LocalBottomChromeInset.current + Spacing.xl),
                    ) {
                        item(key = "hero", contentType = "hero") {
                            CollectionHero(
                                title = album.title,
                                subtitle = album.artist,
                                metadata = albumMetadata(album, tracks),
                                hasTracks = tracks.isNotEmpty(),
                                onPlay = onPlayAlbum,
                                onShuffle = onShuffleAlbum,
                                downloadProgress = downloadProgress,
                                onToggleDownload = onToggleCollectionDownload,
                                modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
                                artwork = {
                                    if (!album.coverUrl.isNullOrBlank()) {
                                        AsyncImage(
                                            model = album.coverUrl,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Album,
                                                contentDescription = null,
                                                tint = scheme.onBackgroundTertiary,
                                                modifier = Modifier.size(64.dp),
                                            )
                                        }
                                    }
                                },
                            )
                        }

                        if (tracks.isEmpty()) {
                            item(key = "empty", contentType = "empty") {
                                CollectionEmptyState(
                                    icon = Icons.Default.Album,
                                    title = stringResource(R.string.collection_album_empty_title),
                                    message = stringResource(R.string.collection_album_empty_body),
                                    retryLabel = stringResource(R.string.album_retry_action),
                                    onRetry = onRetry,
                                )
                            }
                        } else {
                            item(key = "tracklist-header", contentType = "header") {
                                CollectionSectionHeader(
                                    title = stringResource(R.string.album_section_tracklist),
                                    detail = collectionTrackDetail(tracks),
                                )
                            }

                            if (hasCurrent) {
                                item(key = "now-playing", contentType = "now-playing") {
                                    val currentTrack = tracks.getOrNull(currentIndex)
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
                                items = tracks,
                                key = { index, _ -> keys.getOrElse(index) { "track_$index" } },
                                contentType = { _, _ -> "track" },
                            ) { index, track ->
                                val isCurrent = track.id == currentTrackId
                                CollectionTrackRow(
                                    track = track,
                                    trackNumber = index + 1,
                                    showArtwork = true,
                                    showArtist = !sameAlbumArtist(track.artist, album.artist),
                                    isCurrent = isCurrent,
                                    isPlaying = isCurrent && isPlaying,
                                    isFavorite = isFavorite(track.id),
                                    onPlay = { onTrackClick(track, index) },
                                    onFavoriteClick = { onFavoriteToggle(track) },
                                    fallbackCoverUrl = album.coverUrl,
                                    menuContent = { dismiss ->
                                        DownloadMenuEntry(
                                            track = track,
                                            onDismiss = dismiss,
                                            accent = scheme.accent,
                                            primary = scheme.onBackground,
                                            secondary = scheme.onBackgroundSecondary,
                                        )
                                        if (onPlayNextTrack != null) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.album_play_next_action), color = scheme.onBackground) },
                                                leadingIcon = {
                                                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = scheme.accent)
                                                },
                                                onClick = {
                                                    dismiss()
                                                    onPlayNextTrack(track)
                                                },
                                            )
                                        }
                                        if (onAddToQueue != null) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.album_add_to_queue_action), color = scheme.onBackground) },
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
                                                text = { Text(stringResource(R.string.album_add_to_playlist_action), color = scheme.onBackground) },
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

                            if (!album.label.isNullOrBlank() || !album.releaseDate.isNullOrBlank()) {
                                item(key = "footer", contentType = "footer") {
                                    AlbumFooter(
                                        album = album,
                                        modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.xl),
                                    )
                                }
                            }
                        }
                    }
                }

                else -> {
                    CollectionEmptyState(
                        icon = Icons.Default.Album,
                        title = stringResource(R.string.album_not_available_title),
                        message = stringResource(R.string.album_not_available_message),
                        retryLabel = stringResource(R.string.album_retry_action),
                        onRetry = onRetry,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun albumMetadata(album: Album, tracks: List<Track>): String? {
    val year = album.releaseDate?.take(4)
    val count = if (tracks.isNotEmpty()) tracks.size else album.trackCount
    val duration = if (tracks.isNotEmpty()) formatCollectionDuration(tracks.sumOf { it.durationMs }) else null
    val genres = album.genres.take(2).joinToString(", ").takeIf { it.isNotBlank() }
    return joinCollectionMeta(
        year,
        if (count > 0) pluralStringResource(R.plurals.collection_track_count, count, count) else null,
        duration,
        genres,
    )
}

@Composable
private fun collectionTrackDetail(tracks: List<Track>): String? = joinCollectionMeta(
    pluralStringResource(R.plurals.collection_track_count, tracks.size, tracks.size),
    formatCollectionDuration(tracks.sumOf { it.durationMs }),
)

private fun sameAlbumArtist(trackArtist: String, albumArtist: String): Boolean =
    trackArtist.trim().equals(albumArtist.trim(), ignoreCase = true)

@Composable
private fun AlbumFooter(
    album: Album,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (!album.releaseDate.isNullOrBlank()) {
            Text(
                text = stringResource(R.string.album_released_format, album.releaseDate),
                style = MaterialTheme.typography.bodySmall,
                color = SonoraTextSecondary,
            )
        }
        if (!album.label.isNullOrBlank()) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = stringResource(R.string.album_copyright_format, album.label),
                style = MaterialTheme.typography.bodySmall,
                color = SonoraTextSecondary,
            )
        }
    }
}
