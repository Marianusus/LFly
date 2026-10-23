package org.sonora.android.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import org.sonora.android.R
import org.sonora.android.api.CountryTopTrack
import org.sonora.android.api.EuropeChartsClient
import org.sonora.android.api.Track
import org.sonora.android.ui.components.ArtistLinks
import org.sonora.android.ui.components.LocalTrackContextMenu
import org.sonora.android.ui.components.TrackAnchor
import org.sonora.android.ui.components.SegmentedPills
import org.sonora.android.ui.theme.LocalBackgroundScheme
import org.sonora.android.ui.theme.LocalMotionPreferences
import org.sonora.android.ui.theme.pressScale

internal enum class HomeChartTab { COUNTRY, EUROPE }

@Composable
internal fun HomeCharts(
    countryName: String,
    countryTracks: List<Track>,
    europeTracks: List<CountryTopTrack>,
    isLoadingCountry: Boolean,
    isLoadingEurope: Boolean,
    currentTrackId: String?,
    isPlaying: Boolean,
    onTrackClick: (Track) -> Unit,
    modifier: Modifier = Modifier
) {
    val hasCountry = countryTracks.isNotEmpty() || isLoadingCountry
    val hasEurope = europeTracks.isNotEmpty() || isLoadingEurope
    if (!hasCountry && !hasEurope) return

    val motion = LocalMotionPreferences.current
    val availableTabs = buildList {
        if (hasCountry) add(HomeChartTab.COUNTRY)
        if (hasEurope) add(HomeChartTab.EUROPE)
    }
    var selected by rememberSaveable { mutableStateOf(if (hasCountry) HomeChartTab.COUNTRY else HomeChartTab.EUROPE) }
    LaunchedEffect(hasCountry, hasEurope) {
        if (selected !in availableTabs && availableTabs.isNotEmpty()) {
            selected = availableTabs.first()
        }
    }
    val countryLabel = countryName.ifBlank { stringResource(R.string.home_charts_default_country) }
    val tabLabels = mapOf(
        HomeChartTab.COUNTRY to countryLabel,
        HomeChartTab.EUROPE to stringResource(R.string.home_charts_europe)
    )
    val duration = if (motion.reduceMotion) 0 else 180

    Column(modifier = modifier.fillMaxWidth()) {
        HomeSectionHeader(
            title = stringResource(R.string.home_charts_title),
            subtitle = stringResource(R.string.home_charts_subtitle)
        )
        Spacer(modifier = Modifier.height(HomeDimens.HeaderGap))
        if (availableTabs.size > 1) {
            SegmentedPills(
                items = availableTabs,
                selected = selected,
                onSelect = { selected = it },
                label = { tabLabels[it].orEmpty() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HomeDimens.Gutter),
                slidingIndicator = true
            )
            Spacer(modifier = Modifier.height(HomeDimens.HeaderGap))
        }
        AnimatedContent(
            targetState = selected,
            transitionSpec = {
                (fadeIn(tween(duration)) togetherWith fadeOut(tween(duration)))
                    .using(SizeTransform(clip = false))
            },
            label = "homeChartsTab"
        ) { tab ->
            when (tab) {
                HomeChartTab.COUNTRY -> {
                    if (countryTracks.isEmpty() && isLoadingCountry) {
                        HomeChartsSkeleton()
                    } else {
                        Column(
                            modifier = Modifier.padding(horizontal = HomeDimens.Gutter),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            countryTracks.take(5).forEachIndexed { index, track ->
                                HomeRankRow(
                                    rank = index + 1,
                                    track = track,
                                    isCurrent = currentTrackId == track.id,
                                    isPlaying = isPlaying,
                                    onClick = { onTrackClick(track) }
                                )
                            }
                        }
                    }
                }

                HomeChartTab.EUROPE -> {
                    if (europeTracks.isEmpty() && isLoadingEurope) {
                        HomeEuropeSkeleton()
                    } else {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = HomeDimens.Gutter),
                            horizontalArrangement = Arrangement.spacedBy(HomeDimens.GridGap)
                        ) {
                            items(
                                items = europeTracks,
                                key = { "europe_${it.countryCode}_${it.track.id}" }
                            ) { entry ->
                                HomeEuropeCard(
                                    entry = entry,
                                    isCurrent = currentTrackId == entry.track.id,
                                    isPlaying = isPlaying,
                                    onClick = { onTrackClick(entry.track) },
                                    modifier = Modifier.animateItem()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeRankRow(
    rank: Int,
    track: Track,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    val scheme = LocalBackgroundScheme.current
    val interaction = remember { MutableInteractionSource() }
    val anchor = remember { TrackAnchor() }
    val contextMenu = LocalTrackContextMenu.current
    val playingLabel = stringResource(
        if (isPlaying) R.string.home_playing_content_description else R.string.home_paused_label
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .homeLiquidGlass(RoundedCornerShape(16.dp), scheme.glassTint)
            .onGloballyPositioned { coordinates ->
                if (coordinates.isAttached) anchor.rect = coordinates.boundsInWindow()
            }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                role = androidx.compose.ui.semantics.Role.Button,
                onClick = onClick,
                onLongClick = {
                    if (contextMenu != null && anchor.rect != Rect.Zero) contextMenu.open(track, anchor.rect)
                }
            )
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = rank.toString().padStart(2, '0'),
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Black,
                fontSize = 14.sp,
                fontFeatureSettings = "tnum"
            ),
            color = if (isCurrent) scheme.onBackground else scheme.onBackgroundSecondary,
            modifier = Modifier.width(28.dp)
        )
        HomeArtwork(
            artworkUrl = track.artworkUrl,
            modifier = Modifier.size(HomeDimens.ChartArt),
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    lineHeight = 19.sp
                ),
                color = scheme.onBackground,
                maxLines = 1,
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
            if (isCurrent) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = playingLabel,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = scheme.onBackgroundSecondary,
                    maxLines = 1
                )
            }
        }
        Icon(
            imageVector = if (isCurrent) Icons.Default.GraphicEq else Icons.Default.PlayArrow,
            contentDescription = null,
            tint = if (isCurrent) scheme.accent else scheme.onBackgroundSecondary,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun HomeEuropeCard(
    entry: CountryTopTrack,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(HomeDimens.CardRadius)
    val currentLabel = stringResource(
        if (isPlaying) R.string.home_playing_content_description else R.string.home_paused_label
    )
    Column(
        modifier = modifier
            .width(HomeDimens.RailCardWidth)
            .pressScale(interaction)
            .homeLiquidGlass(shape, scheme.glassTint)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(HomeDimens.RailCardWidth)
        ) {
            HomeArtwork(
                artworkUrl = entry.track.artworkUrl,
                modifier = Modifier.fillMaxWidth().height(HomeDimens.RailCardWidth),
                shape = shape
            )
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.50f))
                    .padding(horizontal = 7.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = EuropeChartsClient.flagUrl(entry.countryCode, 80),
                    contentDescription = entry.countryName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .height(12.dp)
                        .width(18.dp)
                        .clip(RoundedCornerShape(2.dp))
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = entry.countryName,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    ),
                    color = Color.White,
                    maxLines = 1
                )
            }
            if (isCurrent) {
                Text(
                    text = currentLabel,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.52f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = entry.track.title,
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
            text = entry.track.artist,
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
