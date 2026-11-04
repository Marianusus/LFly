package org.sonora.android.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.sonora.android.R
import org.sonora.android.api.Track
import org.sonora.android.playback.PlaybackState
import org.sonora.android.ui.components.ArtistLinks
import org.sonora.android.ui.components.LocalTrackContextMenu
import org.sonora.android.ui.components.TrackAnchor
import org.sonora.android.ui.theme.LocalBackgroundScheme
import org.sonora.android.ui.theme.pressScale

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomeHero(
    playbackState: PlaybackState,
    fallbackTrack: Track?,
    fallbackLabel: String,
    onOpenPlayer: () -> Unit,
    onPlayTrack: (Track) -> Unit,
    onTogglePlayPause: () -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current
    val active = playbackState.currentTrack
    val track = active ?: fallbackTrack ?: return
    val isActive = active != null
    val duration = playbackState.durationMs
    val next = if (isActive && playbackState.currentIndex >= 0) {
        playbackState.queue.getOrNull(playbackState.currentIndex + 1)
    } else {
        null
    }
    val label = when {
        !isActive -> fallbackLabel
        playbackState.isPlaying -> stringResource(R.string.home_resume_playing_label)
        else -> stringResource(R.string.home_paused_label)
    }
    val interaction = remember { MutableInteractionSource() }
    val anchor = remember { TrackAnchor() }
    val contextMenu = LocalTrackContextMenu.current
    val open = {
        if (isActive) onOpenPlayer() else onPlayTrack(track)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = HomeDimens.Gutter)
            .homeLiquidGlass(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(HomeDimens.HeroRadius),
                fallbackColor = scheme.glassTint
            )
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressScale(interaction)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(HomeDimens.HeroRadius))
                .onGloballyPositioned { coordinates ->
                    if (coordinates.isAttached) anchor.rect = coordinates.boundsInWindow()
                }
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    onClick = open,
                    onLongClick = {
                        if (contextMenu != null && anchor.rect != Rect.Zero) contextMenu.open(track, anchor.rect)
                    }
                )
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HomeArtwork(
                artworkUrl = track.artworkUrl,
                modifier = Modifier.size(HomeDimens.HeroArt),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(HomeDimens.CardRadius)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(scheme.accent)
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            letterSpacing = 1.sp
                        ),
                        color = scheme.onBackgroundSecondary,
                        maxLines = 1
                    )
                }
                Spacer(modifier = Modifier.height(5.dp))
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 21.sp,
                        lineHeight = 25.sp,
                        letterSpacing = (-0.4).sp
                    ),
                    color = scheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.sp,
                        lineHeight = 19.sp
                    ),
                    color = scheme.onBackgroundSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (next != null) {
                    Spacer(modifier = Modifier.height(5.dp))
                    Text(
                        text = stringResource(R.string.home_next_in_queue, next.title),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        ),
                        color = scheme.onBackgroundSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            if (isActive) {
                HeroRoundButton(
                    icon = Icons.AutoMirrored.Filled.QueueMusic,
                    label = stringResource(R.string.home_queue),
                    filled = false,
                    onClick = onOpenQueue
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            HeroRoundButton(
                icon = if (isActive && playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                label = if (isActive && playbackState.isPlaying) {
                    stringResource(R.string.home_pause)
                } else {
                    stringResource(R.string.home_play_content_description)
                },
                filled = true,
                onClick = if (isActive) onTogglePlayPause else ({ onPlayTrack(track) })
            )
        }

        if (isActive && duration > 0L) {
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { playbackState.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(CircleShape),
                color = scheme.accent,
                trackColor = scheme.glassTint
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatHomeTime(playbackState.positionMs),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = scheme.onBackgroundSecondary
                )
                Text(
                    text = formatHomeTime(duration),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = scheme.onBackgroundSecondary
                )
            }
        }

    }
}

/** A round button beside the track, as in Apple Music's resume card: the play one filled with the accent. */
@Composable
private fun HeroRoundButton(icon: ImageVector, label: String, filled: Boolean, onClick: () -> Unit) {
    val scheme = LocalBackgroundScheme.current
    val size = if (filled) 48.dp else 40.dp
    Box(
        modifier = Modifier
            .size(size)
            .then(
                if (filled) Modifier.clip(CircleShape).background(scheme.accent)
                else Modifier.homeLiquidGlass(CircleShape, scheme.glassTint)
            )
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (filled) homeOnColor(scheme.accent) else scheme.onBackground,
            modifier = Modifier.size(if (filled) 26.dp else 20.dp)
        )
    }
}

