package org.sonora.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbDownOffAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.sonora.android.R
import org.sonora.android.api.PlaybackSource
import org.sonora.android.api.Track
import org.sonora.android.playback.DownloadStatus
import org.sonora.android.ui.theme.SonoraEmerald
import org.sonora.android.ui.theme.SonoraOpaqueSurfaceCard
import org.sonora.android.ui.theme.SonoraSurfaceHighlight
import org.sonora.android.ui.theme.SonoraTheme
import org.sonora.android.ui.theme.SonoraTextPrimary
import org.sonora.android.ui.theme.SonoraTextSecondary
import org.sonora.android.ui.theme.SonoraTextTertiary
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackItem(
    track: Track,
    isPlaying: Boolean,
    isCurrentTrack: Boolean,
    isFavorite: Boolean,
    onTrackClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    modifier: Modifier = Modifier,
    rank: Int? = null,
    fallbackCoverUrl: String? = null,
    isDisliked: Boolean = false,
    onMoreClick: (() -> Unit)? = null,
    onPlayNextTrack: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onShareClick: (() -> Unit)? = null,
    onDislikeClick: (() -> Unit)? = null,
    sourceActions: TrackSourceActions? = null
) {
    // The source switch only makes sense for tracks resolved through Monochrome / YouTube Music.
    val sourceSwitchable = sourceActions != null &&
        (track.id.startsWith("yt_") || track.id.startsWith("spotify_")) &&
        !track.provider.equals("Local", ignoreCase = true)

    // App-managed local files can be deleted from disk via the options menu.
    val downloadUi = LocalDownloadUi.current
    val isLocalTrack = track.provider.equals("Local", ignoreCase = true)
    val canDeleteLocal = downloadUi.isManagedLocal(track) ||
        (isLocalTrack && downloadUi.isDownloaded(track.id))

    // The ⋯ opens the full sheet; holding the row lifts the compact context menu.
    val hasOptionsMenu = true
    var menuExpanded by remember { mutableStateOf(false) }
    val anchor = remember { TrackAnchor() }
    val contextMenu = LocalTrackContextMenu.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isCurrentTrack) SonoraSurfaceHighlight.copy(alpha = 0.5f)
                else androidx.compose.ui.graphics.Color.Transparent
            )
            .then(
                if (isCurrentTrack) {
                    Modifier.border(
                        border = androidx.compose.foundation.BorderStroke(1.dp, SonoraEmerald.copy(alpha = 0.30f)),
                        shape = RoundedCornerShape(12.dp)
                    )
                } else Modifier
            )
            .onGloballyPositioned { coordinates ->
                if (coordinates.isAttached) anchor.rect = coordinates.boundsInWindow()
            }
            .then(
                if (hasOptionsMenu) {
                    Modifier.combinedClickable(
                        onClick = onTrackClick,
                        onLongClick = {
                            if (contextMenu != null && anchor.rect != Rect.Zero) {
                                contextMenu.open(track, anchor.rect)
                            } else if (onMoreClick != null) {
                                onMoreClick()
                            } else {
                                menuExpanded = true
                            }
                        }
                    )
                } else {
                    Modifier.clickable(onClick = onTrackClick)
                }
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (rank != null) {
            Text(
                text = rank.toString(),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Black,
                    fontSize = 16.sp
                ),
                color = if (rank == 1) SonoraEmerald else SonoraTextSecondary,
                modifier = Modifier.width(24.dp),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        // Album artwork: real track cover → entity fallback → placeholder (never empty).
        Box {
            TrackThumb(
                url = track.artworkUrl,
                fallbackUrl = fallbackCoverUrl,
                size = 50.dp,
                shape = RoundedCornerShape(10.dp),
                contentDescription = track.title
            )

            // Equalizer badge if currently active track
            if (isCurrentTrack) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Equalizer,
                        contentDescription = stringResource(R.string.track_item_playing_content_description),
                        tint = SonoraEmerald,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Title and Artist
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = if (isCurrentTrack) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 15.sp
                ),
                color = if (isCurrentTrack) SonoraEmerald else SonoraTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            val subtitleStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp)
            if (!track.album.isNullOrBlank() && track.album != track.title) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ArtistLinks(
                        track = track,
                        style = subtitleStyle,
                        color = SonoraTextSecondary,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Text(
                        text = " • ${track.album}",
                        style = subtitleStyle,
                        color = SonoraTextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                ArtistLinks(
                    track = track,
                    style = subtitleStyle,
                    color = SonoraTextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Duration label
        Text(
            text = track.formattedDuration,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 12.sp
            ),
            color = SonoraTextTertiary
        )

        Spacer(modifier = Modifier.width(4.dp))

        val favScale by animateFloatAsState(
            targetValue = if (isFavorite) 1.2f else 1.0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            label = "favScale"
        )
        val favColor by animateColorAsState(
            targetValue = if (isFavorite) SonoraEmerald else SonoraTextSecondary,
            animationSpec = spring(),
            label = "favColor"
        )

        // Offline download toggle (same state/action as the ⋯ menu)
        TrackDownloadButton(track = track)

        // Favorite Toggle Button
        IconButton(
            onClick = onFavoriteClick,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = if (isFavorite) stringResource(R.string.track_item_favorite_remove_content_description) else stringResource(R.string.track_item_favorite_add_content_description),
                tint = favColor,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer {
                        scaleX = favScale
                        scaleY = favScale
                    }
            )
        }

        if (hasOptionsMenu) {
            Box {
                IconButton(
                    onClick = {
                        if (onMoreClick != null) {
                            onMoreClick()
                        } else {
                            menuExpanded = true
                        }
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.track_item_options_content_description),
                        tint = SonoraTextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                if (onMoreClick == null) {
                    TrackOptionsMenu(
                        track = track,
                        expanded = menuExpanded,
                        isDisliked = isDisliked,
                        onDismiss = { menuExpanded = false },
                        onPlayNextTrack = onPlayNextTrack,
                        onAddToQueue = onAddToQueue,
                        onAddToPlaylist = onAddToPlaylist,
                        onShareClick = onShareClick,
                        onDislikeClick = onDislikeClick,
                        sourceActions = sourceActions
                    )
                }
            }
        }
    }
}

/**
 * Track options as a bottom sheet (same visual language as the audio-output sheet):
 * artwork/header, real metadata (album, year, duration, plays, source, type) and actions.
 */
@Composable
fun TrackOptionsMenu(
    track: Track,
    expanded: Boolean,
    isDisliked: Boolean,
    onDismiss: () -> Unit,
    onPlayNextTrack: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onShareClick: (() -> Unit)? = null,
    onDislikeClick: (() -> Unit)? = null,
    sourceActions: TrackSourceActions? = null
) {
    if (!expanded) return
    TrackActionsSheet(
        track = track,
        isDisliked = isDisliked,
        sourceActions = sourceActions,
        onDismissRequest = onDismiss,
        onToggleDislike = { onDislikeClick?.invoke() },
        onPlayNext = { onPlayNextTrack?.invoke() },
        onAddToQueue = { onAddToQueue?.invoke() },
        onAddToPlaylist = { onAddToPlaylist?.invoke() },
        onShare = onShareClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackInfoSheet(
    track: Track,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SonoraOpaqueSurfaceCard,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            TrackSheetHeader(track)
            Spacer(Modifier.height(14.dp))
            TrackInfoCard(track)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun TrackSheetHeader(track: Track) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TrackThumb(
            url = track.artworkUrl,
            size = 64.dp,
            shape = RoundedCornerShape(14.dp),
            contentDescription = track.title
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                ),
                color = SonoraTextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = track.artist.ifBlank { stringResource(R.string.track_info_unknown_artist) },
                style = MaterialTheme.typography.bodyMedium.copy(color = SonoraTextSecondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private data class TrackInfoRow(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val value: String
)

@Composable
private fun TrackInfoCard(track: Track) {
    val rows = buildList {
        add(
            TrackInfoRow(
                icon = Icons.Default.Album,
                label = stringResource(R.string.track_info_album),
                value = track.album?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.track_info_unknown)
            )
        )
        track.releaseYear?.let { year ->
            add(
                TrackInfoRow(
                    icon = Icons.Default.DateRange,
                    label = stringResource(R.string.track_info_year),
                    value = year.toString()
                )
            )
        }
        add(
            TrackInfoRow(
                icon = Icons.Default.Timer,
                label = stringResource(R.string.track_info_duration),
                value = track.formattedDuration
            )
        )
        track.streams?.takeIf { it > 0 }?.let { streams ->
            add(
                TrackInfoRow(
                    icon = Icons.Default.Headphones,
                    label = stringResource(R.string.track_info_plays),
                    value = formatTrackStreams(streams)
                )
            )
        }
        add(
            TrackInfoRow(
                icon = Icons.Default.Language,
                label = stringResource(R.string.track_info_source),
                value = track.provider
            )
        )
        add(
            TrackInfoRow(
                icon = if (track.isVideo) Icons.Default.Movie else Icons.Default.MusicNote,
                label = stringResource(R.string.track_info_type),
                value = stringResource(
                    if (track.isVideo) R.string.track_info_type_video
                    else R.string.track_info_type_song
                )
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SonoraSurfaceHighlight)
            .padding(horizontal = 14.dp, vertical = 4.dp)
    ) {
        rows.forEachIndexed { index, row ->
            if (index > 0) {
                HorizontalDivider(
                    color = SonoraTextTertiary.copy(alpha = 0.12f),
                    thickness = 0.5.dp
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = row.icon,
                    contentDescription = null,
                    tint = SonoraTextTertiary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = row.label,
                    style = MaterialTheme.typography.labelMedium.copy(color = SonoraTextSecondary),
                    modifier = Modifier.width(78.dp)
                )
                Text(
                    text = row.value,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp
                    ),
                    color = SonoraTextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SheetActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    tint: Color,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (selected) SonoraEmerald.copy(alpha = 0.12f) else SonoraSurfaceHighlight
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(tint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(21.dp)
            )
        }
        Spacer(Modifier.width(13.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            ),
            color = SonoraTextPrimary,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = SonoraEmerald,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

internal fun formatTrackStreams(value: Long): String {
    val (amount, suffix) = when {
        value >= 1_000_000_000L -> value / 1_000_000_000.0 to "B"
        value >= 1_000_000L -> value / 1_000_000.0 to "M"
        value >= 1_000L -> value / 1_000.0 to "K"
        else -> return value.toString()
    }
    val pattern = if (amount >= 100) "%.0f %s" else "%.1f %s"
    return String.format(Locale.getDefault(), pattern, amount, suffix)
}
