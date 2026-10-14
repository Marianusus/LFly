package org.sonora.android.ui

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import org.sonora.android.api.Album
import org.sonora.android.api.Track
import org.sonora.android.playback.PlaybackState
import org.sonora.android.ui.components.LocalBottomChromeInset
import org.sonora.android.ui.components.SectionHeader
import org.sonora.android.ui.components.SourceSwitchMenuItems
import org.sonora.android.ui.components.TrackSourceActions
import org.sonora.android.ui.components.TrackThumb
import org.sonora.android.ui.components.shimmer
import org.sonora.android.ui.theme.GlassCard
import org.sonora.android.ui.theme.GlassPanel
import org.sonora.android.ui.theme.GlassPlaceholder
import org.sonora.android.ui.theme.GlassTopBar
import org.sonora.android.ui.theme.LocalBackgroundScheme
import org.sonora.android.ui.theme.Radius
import org.sonora.android.ui.theme.SonoraBackground
import org.sonora.android.ui.theme.SonoraTextPrimary
import org.sonora.android.ui.theme.SonoraTheme
import org.sonora.android.ui.theme.SonoraTextSecondary
import org.sonora.android.ui.theme.SonoraTextTertiary
import org.sonora.android.ui.theme.Spacing
import org.sonora.android.ui.theme.pressScale

@Composable
fun AlbumDetailScreen(
    album: Album?,
    tracks: List<Track>,
    isLoading: Boolean,
    errorMessage: String?,
    playbackState: PlaybackState,
    isFavorite: (String) -> Boolean,
    onBackClick: () -> Unit,
    onTrackClick: (Track, Int) -> Unit,
    onPlayAlbum: () -> Unit,
    onShuffleAlbum: () -> Unit,
    onFavoriteToggle: (Track) -> Unit,
    onArtistClick: ((String) -> Unit)? = null,
    onRetry: () -> Unit,
    onAddToQueue: ((Track) -> Unit)? = null,
    onPlayNextTrack: ((Track) -> Unit)? = null,
    onAddToPlaylist: ((Track) -> Unit)? = null,
    sourceActions: TrackSourceActions? = null,
    modifier: Modifier = Modifier
) {
    SonoraBackground(
        modifier = modifier.fillMaxSize(),
        coverUrl = album?.coverUrl
    ) {
        when {
            isLoading && album == null -> {
                AlbumLoadingSkeleton()
            }

            errorMessage != null && album == null -> {
                AlbumMessageState(
                    icon = Icons.Default.Album,
                    title = stringResource(R.string.album_error_load_title),
                    message = errorMessage,
                    onRetry = onRetry
                )
            }

            album != null -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = LocalBottomChromeInset.current + 24.dp)
                ) {
                    item {
                        GlassTopBar(title = stringResource(R.string.album_top_bar_title), onBack = onBackClick)
                    }

                    // Hero panel: artwork, title, artist, metadata and primary actions
                    item {
                        AlbumHeroPanel(
                            album = album,
                            tracks = tracks,
                            onArtistClick = onArtistClick,
                            onPlayAlbum = onPlayAlbum,
                            onShuffleAlbum = onShuffleAlbum,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.gutter, vertical = Spacing.sm)
                        )
                    }

                    // Tracklist
                    if (tracks.isNotEmpty()) {
                        item {
                            SectionHeader(title = stringResource(R.string.album_section_tracklist))
                        }

                        itemsIndexed(tracks) { index, track ->
                            val isCurrent = playbackState.currentTrack?.id == track.id
                            AlbumTrackRow(
                                trackNumber = index + 1,
                                track = track,
                                isCurrent = isCurrent,
                                isPlaying = isCurrent && playbackState.isPlaying,
                                isFavorite = isFavorite(track.id),
                                onClick = { onTrackClick(track, index) },
                                onFavoriteToggle = { onFavoriteToggle(track) },
                                onAddToQueue = onAddToQueue?.let { cb -> { cb(track) } },
                                onPlayNextTrack = onPlayNextTrack?.let { cb -> { cb(track) } },
                                onAddToPlaylist = onAddToPlaylist?.let { cb -> { cb(track) } },
                                sourceActions = sourceActions,
                                fallbackCoverUrl = album?.coverUrl
                            )
                        }
                    }

                    // Label / copyright footer
                    if (!album.label.isNullOrBlank() || !album.releaseDate.isNullOrBlank()) {
                        item {
                            AlbumFooter(
                                album = album,
                                modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.lg)
                            )
                        }
                    }
                }
            }

            else -> {
                AlbumMessageState(
                    icon = Icons.Default.Album,
                    title = stringResource(R.string.album_not_available_title),
                    message = stringResource(R.string.album_not_available_message),
                    onRetry = onRetry
                )
            }
        }
    }
}

@Composable
private fun AlbumHeroPanel(
    album: Album,
    tracks: List<Track>,
    onArtistClick: ((String) -> Unit)?,
    onPlayAlbum: () -> Unit,
    onShuffleAlbum: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current
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
                    if (!album.coverUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = album.coverUrl,
                            contentDescription = album.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Album,
                            contentDescription = null,
                            tint = SonoraTextTertiary,
                            modifier = Modifier.size(72.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.lg))

            Text(
                text = album.title,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = SonoraTextPrimary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(Spacing.xs))

            Text(
                text = album.artist,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = scheme.accent,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = onArtistClick != null) { onArtistClick?.invoke(album.artist) }
                    .padding(horizontal = Spacing.sm, vertical = 2.dp)
            )

            val metadataText = albumMetadata(album, tracks)
            if (metadataText.isNotBlank()) {
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = metadataText,
                    style = MaterialTheme.typography.bodySmall,
                    color = SonoraTextSecondary,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(Spacing.lg))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onPlayAlbum,
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
                    Text(text = stringResource(R.string.album_play_action), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }

                OutlinedButton(
                    onClick = onShuffleAlbum,
                    border = BorderStroke(1.dp, scheme.glassBorder),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = scheme.glassTint,
                        contentColor = SonoraTextPrimary
                    ),
                    shape = RoundedCornerShape(Radius.Medium)
                ) {
                    Icon(
                        imageVector = Icons.Default.Shuffle,
                        contentDescription = stringResource(R.string.album_shuffle_content_description),
                        tint = scheme.accent,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    Text(text = stringResource(R.string.album_shuffle_action), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun AlbumTrackRow(
    trackNumber: Int,
    track: Track,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onAddToQueue: (() -> Unit)? = null,
    onPlayNextTrack: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    fallbackCoverUrl: String? = null,
    sourceActions: TrackSourceActions? = null,
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current
    var menuExpanded by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(Radius.Small)

    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .clip(shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        shape = shape
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.width(32.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isCurrent) {
                    Icon(
                        imageVector = Icons.Default.Equalizer,
                        contentDescription = stringResource(R.string.album_playing_content_description),
                        tint = scheme.accent,
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    Text(
                        text = "$trackNumber",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = SonoraTextTertiary,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.width(Spacing.sm))

            // Track thumbnail: real track cover → album cover fallback → placeholder.
            TrackThumb(
                url = track.artworkUrl,
                fallbackUrl = fallbackCoverUrl,
                size = 44.dp,
                shape = RoundedCornerShape(Radius.Small),
                contentDescription = track.title
            )

            Spacer(modifier = Modifier.width(Spacing.sm))

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

            Text(
                text = track.formattedDuration,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                color = SonoraTextTertiary
            )

            IconButton(
                onClick = onFavoriteToggle,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (isFavorite) stringResource(R.string.album_remove_favorite_content_description) else stringResource(R.string.album_add_favorite_content_description),
                    tint = if (isFavorite) scheme.accent else SonoraTextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }

            if (onPlayNextTrack != null || onAddToQueue != null || onAddToPlaylist != null || sourceActions != null) {
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.album_more_options_content_description),
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
                        if (onPlayNextTrack != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.album_play_next_action), color = SonoraTextPrimary) },
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
                        if (onAddToQueue != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.album_add_to_queue_action), color = SonoraTextPrimary) },
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
                        if (onAddToPlaylist != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.album_add_to_playlist_action), color = SonoraTextPrimary) },
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
            }
        }
    }
}

@Composable
private fun AlbumFooter(
    album: Album,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.Small)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg)
        ) {
            if (!album.releaseDate.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.album_released_format, album.releaseDate),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = SonoraTextTertiary
                )
            }
            if (!album.label.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.album_copyright_format, album.label),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = SonoraTextTertiary
                )
            }
        }
    }
}

@Composable
private fun AlbumLoadingSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.gutter)
            .padding(top = Spacing.lg, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .shimmer(RoundedCornerShape(Radius.Large))
        )
        repeat(6) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .shimmer(RoundedCornerShape(Radius.Small))
            )
        }
    }
}

@Composable
private fun AlbumMessageState(
    icon: ImageVector,
    title: String,
    message: String,
    onRetry: () -> Unit
) {
    val scheme = LocalBackgroundScheme.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xl),
        contentAlignment = Alignment.Center
    ) {
        GlassPlaceholder {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = scheme.onBackgroundTertiary,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(44.dp)
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = SonoraTextPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = SonoraTextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(Spacing.lg))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = scheme.accent,
                    contentColor = if (scheme.isLight) Color.White else Color.Black
                ),
                shape = RoundedCornerShape(Radius.Medium),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(Spacing.xs))
                Text(text = stringResource(R.string.album_retry_action), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun albumMetadata(album: Album, tracks: List<Track>): String {
    val year = album.releaseDate?.take(4)
    val count = if (tracks.isNotEmpty()) tracks.size else album.trackCount
    val totalDurationMs = tracks.sumOf { it.durationMs }
    return buildString {
        if (!year.isNullOrBlank()) append(year)
        if (count > 0) {
            if (isNotEmpty()) append(" • ")
            append(pluralStringResource(R.plurals.album_songs_count, count, count))
        }
        if (totalDurationMs > 0) {
            val totalMinutes = totalDurationMs / 1000 / 60
            append(stringResource(R.string.album_metadata_minutes, totalMinutes))
        }
    }
}
