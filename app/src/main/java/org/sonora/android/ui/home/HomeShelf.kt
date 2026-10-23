package org.sonora.android.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.sonora.android.R
import org.sonora.android.data.ShelfEntry
import org.sonora.android.ui.components.ArtistLinks
import org.sonora.android.ui.components.LocalTrackContextMenu
import org.sonora.android.ui.components.TrackAnchor
import org.sonora.android.ui.theme.LocalBackgroundScheme
import org.sonora.android.ui.theme.LocalMotionPreferences
import org.sonora.android.ui.theme.pressScale

private const val ShelfPageSize = 6
private const val ShelfMaxPages = 3

@Composable
internal fun HomeShelf(
    entries: List<ShelfEntry>,
    title: String,
    subtitle: String,
    columns: Int,
    currentTrackId: String?,
    isPlaying: Boolean,
    onTrackClick: (org.sonora.android.api.Track) -> Unit,
    onPlaylistClick: (org.sonora.android.data.Playlist) -> Unit,
    modifier: Modifier = Modifier
) {
    val visible = entries.take(if (columns == 1) ShelfPageSize else ShelfPageSize * ShelfMaxPages)
    if (visible.isEmpty()) return
    val pages = visible.chunked(ShelfPageSize)
    val maxRows = pages.maxOfOrNull { (it.size + 1) / 2 }?.coerceAtLeast(1) ?: 1
    val pagerHeight = (HomeDimens.ShelfArt + 24.dp) * maxRows +
        HomeDimens.GridGap * (maxRows - 1) + 4.dp
    val pagerState = rememberPagerState(pageCount = { pages.size })
    LaunchedEffect(pages.size) {
        if (pages.isNotEmpty() && pagerState.currentPage >= pages.size) pagerState.scrollToPage(0)
    }
    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        HomeSectionHeader(title = title, subtitle = subtitle)
        Spacer(modifier = Modifier.height(HomeDimens.HeaderGap))
        if (columns == 1) {
            Column(
                modifier = Modifier.padding(horizontal = HomeDimens.Gutter),
                verticalArrangement = Arrangement.spacedBy(HomeDimens.GridGap)
            ) {
                visible.forEach { entry ->
                    HomeShelfCard(
                        entry = entry,
                        isCurrent = entry is ShelfEntry.TrackEntry && currentTrackId == entry.track.id,
                        isPlaying = isPlaying,
                        onClick = {
                            when (entry) {
                                is ShelfEntry.TrackEntry -> onTrackClick(entry.track)
                                is ShelfEntry.PlaylistEntry -> onPlaylistClick(entry.playlist)
                            }
                        }
                    )
                }
            }
        } else {
            HorizontalPager(
                state = pagerState,
                pageSpacing = 12.dp,
                beyondViewportPageCount = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(pagerHeight)
            ) { page ->
                val pageEntries = pages.getOrElse(page) { emptyList() }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = HomeDimens.Gutter),
                    verticalArrangement = Arrangement.spacedBy(HomeDimens.GridGap)
                ) {
                    repeat(ShelfPageSize / 2) { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(HomeDimens.GridGap)
                        ) {
                            for (column in 0 until 2) {
                                val entry = pageEntries.getOrNull(row * 2 + column)
                                if (entry == null) {
                                    Spacer(modifier = Modifier.weight(1f))
                                } else {
                                    HomeShelfCard(
                                        entry = entry,
                                        isCurrent = entry is ShelfEntry.TrackEntry &&
                                            currentTrackId == entry.track.id,
                                        isPlaying = isPlaying,
                                        onClick = {
                                            when (entry) {
                                                is ShelfEntry.TrackEntry -> onTrackClick(entry.track)
                                                is ShelfEntry.PlaylistEntry -> onPlaylistClick(entry.playlist)
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (pages.size > 1) {
                ShelfDots(
                    pageCount = pages.size,
                    selected = pagerState.currentPage,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun ShelfDots(
    pageCount: Int,
    selected: Int,
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current
    val motion = LocalMotionPreferences.current
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(pageCount) { index ->
            val isSelected = selected == index
            val width by animateDpAsState(
                targetValue = if (isSelected) 16.dp else 6.dp,
                animationSpec = motion.tween(durationMs = 220),
                label = "homeShelfDotWidth"
            )
            val color by animateColorAsState(
                targetValue = if (isSelected) scheme.onBackground else scheme.onBackgroundTertiary,
                animationSpec = motion.tween(durationMs = 180),
                label = "homeShelfDotColor"
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .width(width)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeShelfCard(
    entry: ShelfEntry,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current
    val interaction = remember { MutableInteractionSource() }
    val trackEntry = entry as? ShelfEntry.TrackEntry
    val anchor = remember { TrackAnchor() }
    val contextMenu = LocalTrackContextMenu.current
    val artwork = when (entry) {
        is ShelfEntry.TrackEntry -> entry.track.artworkUrl
        is ShelfEntry.PlaylistEntry -> entry.playlist.displayCoverUrl
    }
    val title = when (entry) {
        is ShelfEntry.TrackEntry -> entry.track.title
        is ShelfEntry.PlaylistEntry -> entry.playlist.name
    }
    val subtitle = when (entry) {
        is ShelfEntry.TrackEntry -> entry.track.artist
        is ShelfEntry.PlaylistEntry -> stringResource(R.string.home_shelf_playlist_label) + " · " +
            pluralStringResource(
                R.plurals.home_shelf_playlist_track_count,
                entry.playlist.trackCount,
                entry.playlist.trackCount
            )
    }
    val shape = RoundedCornerShape(HomeDimens.CardRadius)
    val playingState = stringResource(
        if (isPlaying) R.string.home_playing_content_description else R.string.home_paused_label
    )
    Row(
        modifier = modifier
            .pressScale(interaction)
            .homeLiquidGlass(shape, scheme.glassTint)
            .then(
                if (isCurrent) Modifier.border(1.5.dp, scheme.accent, shape) else Modifier
            )
            .semantics {
                if (isCurrent) stateDescription = playingState
            }
            .onGloballyPositioned { coordinates ->
                if (coordinates.isAttached) anchor.rect = coordinates.boundsInWindow()
            }
            .then(
                if (contextMenu != null) {
                    Modifier.combinedClickable(
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Button,
                        onClick = onClick,
                        onLongClick = {
                            trackEntry?.let {
                                if (anchor.rect != Rect.Zero) contextMenu.open(it.track, anchor.rect)
                            }
                        }
                    )
                } else {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Button,
                        onClick = onClick
                    )
                }
            )
            .padding(8.dp)
            .heightIn(min = HomeDimens.ShelfArt),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HomeArtwork(
            artworkUrl = artwork,
            modifier = Modifier.size(HomeDimens.ShelfArt),
            shape = RoundedCornerShape(14.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        lineHeight = 18.sp
                    ),
                    color = scheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isCurrent) {
                    Spacer(modifier = Modifier.width(5.dp))
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = scheme.accent,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            val subtitleStyle = MaterialTheme.typography.bodySmall.copy(
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
            if (trackEntry != null) {
                ArtistLinks(
                    track = trackEntry.track,
                    style = subtitleStyle,
                    color = scheme.onBackgroundSecondary
                )
            } else {
                Text(
                    text = subtitle,
                    style = subtitleStyle,
                    color = scheme.onBackgroundSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
