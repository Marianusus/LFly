package org.sonora.android.ui.home

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.sonora.android.R
import org.sonora.android.api.Track
import org.sonora.android.data.Mix
import org.sonora.android.ui.components.ArtistLinks
import org.sonora.android.ui.components.LocalTrackContextMenu
import org.sonora.android.ui.components.TrackAnchor
import org.sonora.android.ui.theme.LocalBackgroundScheme
import org.sonora.android.ui.theme.pressScale

internal sealed interface HomeRailItem {
    data class MixItem(val mix: Mix) : HomeRailItem
    data class TrackItem(val track: Track) : HomeRailItem
}

@Composable
internal fun HomeRail(
    items: List<HomeRailItem>,
    currentTrackId: String?,
    isPlaying: Boolean,
    onTrackClick: (Track) -> Unit,
    onMixClick: (Mix) -> Unit,
    onMixSaveToggle: (Mix) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        HomeSectionHeader(
            title = stringResource(R.string.home_recommendations_title),
            subtitle = stringResource(R.string.home_recommendations_subtitle)
        )
        Spacer(modifier = Modifier.height(HomeDimens.HeaderGap))
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = HomeDimens.Gutter),
            horizontalArrangement = Arrangement.spacedBy(HomeDimens.GridGap)
        ) {
            items(
                items = items,
                key = { item ->
                    when (item) {
                        is HomeRailItem.MixItem -> "rail_mix_${item.mix.id}"
                        is HomeRailItem.TrackItem -> "rail_track_${item.track.id}"
                    }
                }
            ) { item ->
                when (item) {
                    is HomeRailItem.MixItem -> HomeMixCard(
                        mix = item.mix,
                        onOpen = { onMixClick(item.mix) },
                        onSaveToggle = { onMixSaveToggle(item.mix) },
                        modifier = Modifier.animateItem()
                    )
                    is HomeRailItem.TrackItem -> HomeTrackCard(
                        track = item.track,
                        isCurrent = currentTrackId == item.track.id,
                        isPlaying = isPlaying,
                        onClick = { onTrackClick(item.track) },
                        modifier = Modifier.animateItem()
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeMixCard(
    mix: Mix,
    onOpen: () -> Unit,
    onSaveToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(HomeDimens.CardRadius)
    val saveLabel = stringResource(
        if (mix.isSaved) R.string.mix_remove_from_library_content_description
        else R.string.mix_save_content_description
    )
    Box(modifier = modifier.width(HomeDimens.RailCardWidth)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pressScale(interaction)
                .homeLiquidGlass(shape, scheme.glassTint)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    onClick = onOpen
                )
        ) {
            Box {
                HomeArtwork(
                    artworkUrl = mix.displayCoverUrl,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(HomeDimens.RailCardWidth),
                    shape = shape
                )
                HomeBadge(
                    text = stringResource(R.string.mix_badge),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = mix.name,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    lineHeight = 19.sp
                ),
                color = scheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.mix_label) + " · " +
                    pluralStringResource(
                        R.plurals.home_shelf_playlist_track_count,
                        mix.displayTrackCount,
                        mix.displayTrackCount
                    ),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                ),
                color = scheme.onBackgroundSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(HomeDimens.Target)
                .semantics { contentDescription = saveLabel }
                .clickable(role = Role.Button, onClick = onSaveToggle),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.44f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (mix.isSaved) Icons.Default.Check else Icons.Default.Add,
                    contentDescription = null,
                    tint = if (mix.isSaved) scheme.accent else Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeTrackCard(
    track: Track,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current
    val interaction = remember { MutableInteractionSource() }
    val anchor = remember { TrackAnchor() }
    val contextMenu = LocalTrackContextMenu.current
    val shape = RoundedCornerShape(HomeDimens.CardRadius)
    val currentLabel = stringResource(
        if (isPlaying) R.string.home_playing_content_description else R.string.home_paused_label
    )
    Column(
        modifier = modifier
            .width(HomeDimens.RailCardWidth)
            .pressScale(interaction)
            .homeLiquidGlass(shape, scheme.glassTint)
            .semantics {
                if (isCurrent) stateDescription = currentLabel
            }
            .onGloballyPositioned { coordinates ->
                if (coordinates.isAttached) anchor.rect = coordinates.boundsInWindow()
            }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
                onLongClick = {
                    if (contextMenu != null && anchor.rect != Rect.Zero) contextMenu.open(track, anchor.rect)
                }
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(HomeDimens.RailCardWidth)
        ) {
            HomeArtwork(
                artworkUrl = track.artworkUrl,
                modifier = Modifier.fillMaxWidth().height(HomeDimens.RailCardWidth),
                shape = shape
            )
            if (isCurrent) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.52f))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = scheme.accent,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = currentLabel,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = Color.White,
                        maxLines = 1
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = track.title,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                lineHeight = 19.sp
            ),
            color = scheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        ArtistLinks(
            track = track,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 12.sp,
                lineHeight = 16.sp
            ),
            color = scheme.onBackgroundSecondary
        )
    }
}
