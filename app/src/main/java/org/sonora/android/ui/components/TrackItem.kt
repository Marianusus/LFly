package org.sonora.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbDownOffAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import org.sonora.android.R
import org.sonora.android.api.Track
import org.sonora.android.ui.theme.SonoraEmerald
import org.sonora.android.ui.theme.SonoraSurfaceCard
import org.sonora.android.ui.theme.SonoraSurfaceHighlight
import org.sonora.android.ui.theme.SonoraTheme
import org.sonora.android.ui.theme.SonoraTextPrimary
import org.sonora.android.ui.theme.SonoraTextSecondary
import org.sonora.android.ui.theme.SonoraTextTertiary

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
            .clickable(onClick = onTrackClick)
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

            val subtitle = buildString {
                append(track.artist)
                if (!track.album.isNullOrBlank() && track.album != track.title) {
                    append(" • ${track.album}")
                }
            }

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 13.sp
                ),
                color = SonoraTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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

        if (onMoreClick != null || onPlayNextTrack != null || onAddToQueue != null || onAddToPlaylist != null || onShareClick != null || onDislikeClick != null || sourceSwitchable) {
            var menuExpanded by remember { mutableStateOf(false) }
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

                if ((onPlayNextTrack != null || onAddToQueue != null || onAddToPlaylist != null || onShareClick != null || onDislikeClick != null || sourceSwitchable) && onMoreClick == null) {
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        shape = RoundedCornerShape(16.dp),
                        containerColor = org.sonora.android.ui.theme.SonoraOpaqueSurfaceCard,
                        border = androidx.compose.foundation.BorderStroke(1.dp, SonoraTheme.colors.cardBorder)
                    ) {
                        if (onPlayNextTrack != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.track_item_play_next), color = SonoraTextPrimary) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = SonoraEmerald
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
                                text = { Text(stringResource(R.string.track_item_add_to_queue), color = SonoraTextPrimary) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                        contentDescription = null,
                                        tint = SonoraEmerald
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
                                text = { Text(stringResource(R.string.track_item_add_to_playlist), color = SonoraTextPrimary) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                                        contentDescription = null,
                                        tint = SonoraEmerald
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onAddToPlaylist()
                                }
                            )
                        }
                        if (onShareClick != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.track_item_share), color = SonoraTextPrimary) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Share,
                                        contentDescription = null,
                                        tint = SonoraEmerald
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onShareClick()
                                }
                            )
                        }
                        if (onDislikeClick != null) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (isDisliked) stringResource(R.string.track_item_dislike_remove) else stringResource(R.string.track_item_dislike_add),
                                        color = if (isDisliked) androidx.compose.ui.graphics.Color(0xFFE57373) else SonoraTextPrimary
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (isDisliked) Icons.Default.ThumbDown else Icons.Default.ThumbDownOffAlt,
                                        contentDescription = null,
                                        tint = if (isDisliked) androidx.compose.ui.graphics.Color(0xFFE57373) else SonoraTextSecondary
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onDislikeClick()
                                }
                            )
                        }
                        if (sourceSwitchable) {
                            SourceSwitchMenuItems(
                                sourceOverride = sourceActions?.overrideFor(track.id),
                                onPlayViaSource = { src ->
                                    menuExpanded = false
                                    sourceActions?.onSelect(track, src)
                                },
                                onUseGlobalSource = {
                                    menuExpanded = false
                                    sourceActions?.onClear(track)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
