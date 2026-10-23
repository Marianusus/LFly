package org.sonora.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.sonora.android.R
import org.sonora.android.api.Track
import org.sonora.android.data.Mix
import org.sonora.android.data.Playlist
import org.sonora.android.data.RankedPlaylist
import org.sonora.android.data.RankedTrack
import org.sonora.android.data.ShelfEntry
import org.sonora.android.data.buildShelfEntries
import org.sonora.android.playback.PlaybackState
import org.sonora.android.ui.components.LocalBottomChromeInset
import org.sonora.android.ui.components.TrackSourceActions
import org.sonora.android.ui.home.HomeCharts
import org.sonora.android.ui.home.HomeChartsSkeleton
import org.sonora.android.ui.home.HomeDimens
import org.sonora.android.ui.home.HomeHero
import org.sonora.android.ui.home.HomeHeroSkeleton
import org.sonora.android.ui.home.HomeRail
import org.sonora.android.ui.home.HomeRailItem
import org.sonora.android.ui.home.HomeRailSkeleton
import org.sonora.android.ui.home.HomeShelf
import org.sonora.android.ui.home.HomeShelfSkeleton
import org.sonora.android.ui.home.homeLiquidGlass
import org.sonora.android.ui.home.homeOnColor
import org.sonora.android.ui.home.homeShelfColumns
import org.sonora.android.ui.theme.LocalBackgroundScheme
import org.sonora.android.ui.theme.LocalMotionPreferences
import org.sonora.android.ui.theme.SonoraAmoledBlack
import org.sonora.android.ui.theme.SonoraTheme

@Composable
fun HomeScreen(
    charts: List<Track>,
    recentlyPlayed: List<Track>,
    topTracks: List<RankedTrack> = emptyList(),
    topPlaylists: List<RankedPlaylist> = emptyList(),
    mixes: List<Mix> = emptyList(),
    playbackState: PlaybackState,
    isFavorite: (String) -> Boolean,
    isDisliked: (String) -> Boolean = { false },
    recommendedTracks: List<Track> = emptyList(),
    isLoadingRecommendations: Boolean = false,
    countryTopTracks: List<org.sonora.android.api.CountryTopTrack> = emptyList(),
    isLoadingCountryTopTracks: Boolean = false,
    countryTrendingTracks: List<Track> = emptyList(),
    isLoadingCountryTrending: Boolean = false,
    homeCountryName: String = "",
    isLoadingCharts: Boolean = false,
    onTrackClick: (Track) -> Unit,
    onPlaylistClick: (Playlist) -> Unit = {},
    onMixClick: (Mix) -> Unit = {},
    onMixSaveToggle: (Mix) -> Unit = {},
    onFavoriteToggle: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit = {},
    onPlayNextTrack: (Track) -> Unit = {},
    onShareTrack: (Track) -> Unit = {},
    onDislikeTrack: (Track) -> Unit = {},
    onAddToPlaylist: (Track) -> Unit = {},
    sourceActions: TrackSourceActions? = null,
    onOpenPlayer: () -> Unit = {},
    onOpenQueue: () -> Unit = {},
    onTogglePlayPause: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current
    val motion = LocalMotionPreferences.current
    val fontScale = LocalDensity.current.fontScale
    val screenWidth = LocalConfiguration.current.screenWidthDp
    val columns = homeShelfColumns(screenWidth, fontScale)

    val greetingRes = remember {
        when (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) {
            in 5..11 -> R.string.home_greeting_morning
            in 12..17 -> R.string.home_greeting_afternoon
            else -> R.string.home_greeting_evening
        }
    }
    val greeting = stringResource(greetingRes)
    val tagline = stringResource(R.string.home_tagline)

    val validCharts = remember(charts) { charts.filter { it.durationMs > 0 } }
    val shelfEntries = remember(topTracks, topPlaylists) {
        buildShelfEntries(topTracks, topPlaylists, limit = 18)
    }
    val fallbackShelfTracks = remember(recentlyPlayed, validCharts) {
        if (recentlyPlayed.isNotEmpty()) {
            recentlyPlayed.filter { it.durationMs > 0 && !isDisliked(it.id) }.distinctBy { it.id }.take(18)
        } else {
            validCharts.take(18)
        }
    }
    val hasPersonalShelf = shelfEntries.isNotEmpty()
    val shelfItems: List<ShelfEntry> = remember(shelfEntries, fallbackShelfTracks, hasPersonalShelf) {
        if (hasPersonalShelf) shelfEntries else fallbackShelfTracks.map { ShelfEntry.TrackEntry(it, 0) }
    }
    val validRecommendations = remember(recommendedTracks) {
        recommendedTracks.filter { it.durationMs > 0 && !isDisliked(it.id) }
    }
    val lastPlayedTrack = remember(recentlyPlayed) {
        recentlyPlayed.firstOrNull { it.durationMs > 0 }
    }
    val heroFallback = lastPlayedTrack ?: validCharts.firstOrNull()
    val heroFallbackLabel = stringResource(
        if (lastPlayedTrack != null) R.string.home_resume_label else R.string.home_discover_label
    )
    val showHero = playbackState.currentTrack == null && heroFallback != null
    val railItems = remember(mixes, validRecommendations) {
        buildList<HomeRailItem> {
            mixes.forEach { add(HomeRailItem.MixItem(it)) }
            validRecommendations.forEach { add(HomeRailItem.TrackItem(it)) }
        }.take(16)
    }
    val showCharts = countryTrendingTracks.isNotEmpty() || countryTopTracks.isNotEmpty() ||
        isLoadingCountryTrending || isLoadingCountryTopTracks
    val isLoadingInitial = playbackState.currentTrack == null && !showHero &&
        (isLoadingCharts || isLoadingRecommendations) &&
        shelfItems.isEmpty() && railItems.isEmpty()
    val hasAnyContent = playbackState.currentTrack != null || showHero ||
        shelfItems.isNotEmpty() || railItems.isNotEmpty() || showCharts
    val currentTrackId = playbackState.currentTrack?.id

    val listState = rememberLazyListState()
    val rawCollapse by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / 150f).coerceIn(0f, 1f)
        }
    }
    val collapse = if (motion.reduceMotion) {
        if (rawCollapse > 0.35f) 1f else 0f
    } else {
        rawCollapse
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = if (SonoraTheme.colors.isLiquidGlass) Color.Transparent else SonoraAmoledBlack
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = 4.dp,
                    bottom = LocalBottomChromeInset.current + 8.dp
                ),
                verticalArrangement = Arrangement.spacedBy(HomeDimens.SectionGap)
            ) {
                item(key = "home_header") {
                    Column {
                        HomeHeader(
                            greeting = greeting,
                            tagline = tagline,
                            collapse = collapse
                        )
                        AnimatedVisibility(
                            visible = showHero,
                            enter = expandVertically(expandFrom = Alignment.Top) +
                                fadeIn(animationSpec = motion.tween(durationMs = 220)),
                            exit = shrinkVertically(shrinkTowards = Alignment.Top) +
                                fadeOut(animationSpec = motion.tween(durationMs = 160))
                        ) {
                            Column {
                                Spacer(modifier = Modifier.height(16.dp))
                                HomeHero(
                                    playbackState = PlaybackState(),
                                    fallbackTrack = heroFallback,
                                    fallbackLabel = heroFallbackLabel,
                                    onOpenPlayer = onOpenPlayer,
                                    onPlayTrack = onTrackClick,
                                    onTogglePlayPause = onTogglePlayPause,
                                    onOpenQueue = onOpenQueue
                                )
                            }
                        }
                        if (!showHero && isLoadingInitial) {
                            Spacer(modifier = Modifier.height(16.dp))
                            HomeHeroSkeleton()
                        }
                    }
                }

                if (shelfItems.isNotEmpty()) {
                    item(key = "home_shelf") {
                        HomeShelf(
                            entries = shelfItems,
                            title = stringResource(
                                if (hasPersonalShelf) R.string.home_shelf_title
                                else R.string.home_quick_picks_title
                            ),
                            subtitle = stringResource(
                                if (hasPersonalShelf) R.string.home_shelf_subtitle
                                else R.string.home_quick_picks_subtitle_recent
                            ),
                            columns = columns,
                            currentTrackId = currentTrackId,
                            isPlaying = playbackState.isPlaying,
                            onTrackClick = onTrackClick,
                            onPlaylistClick = onPlaylistClick
                        )
                    }
                } else if (isLoadingInitial) {
                    item(key = "home_shelf_skeleton") { HomeShelfSkeleton(columns = columns) }
                }

                if (railItems.isNotEmpty()) {
                    item(key = "home_rail") {
                        HomeRail(
                            items = railItems,
                            currentTrackId = currentTrackId,
                            isPlaying = playbackState.isPlaying,
                            onTrackClick = onTrackClick,
                            onMixClick = onMixClick,
                            onMixSaveToggle = onMixSaveToggle
                        )
                    }
                } else if (isLoadingRecommendations) {
                    item(key = "home_rail_skeleton") { HomeRailSkeleton() }
                }

                if (showCharts) {
                    item(key = "home_charts") {
                        HomeCharts(
                            countryName = homeCountryName,
                            countryTracks = countryTrendingTracks,
                            europeTracks = countryTopTracks,
                            isLoadingCountry = isLoadingCountryTrending,
                            isLoadingEurope = isLoadingCountryTopTracks,
                            currentTrackId = currentTrackId,
                            isPlaying = playbackState.isPlaying,
                            onTrackClick = onTrackClick
                        )
                    }
                } else if (isLoadingCountryTrending || isLoadingCountryTopTracks) {
                    item(key = "home_charts_skeleton") { HomeChartsSkeleton() }
                }

                if (!hasAnyContent && !isLoadingInitial && !isLoadingRecommendations &&
                    !isLoadingCountryTrending && !isLoadingCountryTopTracks
                ) {
                    item(key = "home_empty") {
                        HomeEmpty(onOpenSearch = onOpenSearch)
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(
    greeting: String,
    tagline: String,
    collapse: Float
) {
    val scheme = LocalBackgroundScheme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = (1f - collapse * 1.15f).coerceIn(0f, 1f)
                translationY = -collapse * 16f
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = HomeDimens.Gutter, end = HomeDimens.Gutter, top = 12.dp)
        ) {
            Text(
                text = greeting,
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 34.sp,
                    lineHeight = 40.sp,
                    letterSpacing = (-0.7).sp
                ),
                color = scheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = tagline,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp
                ),
                color = scheme.onBackgroundSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun HomeEmpty(onOpenSearch: () -> Unit) {
    val scheme = LocalBackgroundScheme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HomeDimens.Gutter)
            .homeLiquidGlass(
                androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                scheme.glassTint
            )
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.MusicNote,
            contentDescription = null,
            tint = scheme.onBackgroundSecondary,
            modifier = Modifier.size(30.dp)
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.home_empty_title),
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            ),
            color = scheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.home_empty_body),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = scheme.onBackgroundSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(14.dp))
        Button(
            onClick = onOpenSearch,
            modifier = Modifier.heightIn(min = HomeDimens.Target),
            colors = ButtonDefaults.buttonColors(
                containerColor = scheme.accent,
                contentColor = homeOnColor(scheme.accent)
            ),
            shape = CircleShape
        ) {
            Text(
                text = stringResource(R.string.home_empty_action),
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
