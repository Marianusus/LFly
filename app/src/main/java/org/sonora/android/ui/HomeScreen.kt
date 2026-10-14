package org.sonora.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import org.sonora.android.R
import org.sonora.android.api.CountryTopTrack
import org.sonora.android.api.EuropeChartsClient
import org.sonora.android.api.Track
import org.sonora.android.playback.PlaybackState
import org.sonora.android.ui.components.LocalBottomChromeInset
import org.sonora.android.ui.components.SectionHeader
import org.sonora.android.ui.components.TrackItem
import org.sonora.android.ui.components.TrackSourceActions
import org.sonora.android.ui.components.shimmer
import org.sonora.android.ui.theme.GlassCard
import org.sonora.android.ui.theme.GlassPanel
import org.sonora.android.ui.theme.GlassPlaceholder
import org.sonora.android.ui.theme.LocalBackgroundScheme
import org.sonora.android.ui.theme.LocalMotionPreferences
import org.sonora.android.ui.theme.Radius
import org.sonora.android.ui.theme.SonoraAmoledBlack
import org.sonora.android.ui.theme.SonoraDivider
import org.sonora.android.ui.theme.SonoraTheme
import org.sonora.android.ui.theme.SonoraSurfaceCard
import org.sonora.android.ui.theme.SonoraSurfaceHighlight
import org.sonora.android.ui.theme.SonoraTextPrimary
import org.sonora.android.ui.theme.SonoraTextSecondary
import org.sonora.android.ui.theme.SonoraTextTertiary
import org.sonora.android.ui.theme.Spacing
import org.sonora.android.ui.theme.pressScale
import java.util.Calendar

@Composable
fun HomeScreen(
    charts: List<Track>,
    recentlyPlayed: List<Track>,
    isLoadingCharts: Boolean,
    playbackState: PlaybackState,
    isFavorite: (String) -> Boolean,
    isDisliked: (String) -> Boolean = { false },
    recommendedTracks: List<Track> = emptyList(),
    isLoadingRecommendations: Boolean = false,
    countryTopTracks: List<CountryTopTrack> = emptyList(),
    isLoadingCountryTopTracks: Boolean = false,
    onTrackClick: (Track) -> Unit,
    onFavoriteToggle: (Track) -> Unit,
    onPlayAllCharts: () -> Unit,
    onAddToQueue: (Track) -> Unit = {},
    onPlayNextTrack: (Track) -> Unit = {},
    onShareTrack: (Track) -> Unit = {},
    onDislikeTrack: (Track) -> Unit = {},
    onAddToPlaylist: (Track) -> Unit = {},
    sourceActions: TrackSourceActions? = null,
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current

    val greetingRes = remember {
        when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> R.string.home_greeting_morning
            in 12..17 -> R.string.home_greeting_afternoon
            else -> R.string.home_greeting_evening
        }
    }

    val validCharts = remember(charts) { charts.filter { it.durationMs > 0 } }
    val top5Charts = remember(validCharts) { validCharts.take(5) }
    val quickPicks = remember(recentlyPlayed, validCharts) {
        if (recentlyPlayed.isNotEmpty()) {
            recentlyPlayed.filter { it.durationMs > 0 && !isDisliked(it.id) }.distinctBy { it.id }.take(10)
        } else {
            validCharts.drop(5).take(10)
        }
    }
    val validRecommendations = remember(recommendedTracks) {
        recommendedTracks.filter { it.durationMs > 0 && !isDisliked(it.id) }
    }
    // "Riprendi ad ascoltare" is shown only when the player is completely idle (no loaded
    // track at all); as soon as anything is loaded/playing/paused the block morphs away upward.
    val lastPlayedTrack = remember(recentlyPlayed) {
        recentlyPlayed.firstOrNull { it.durationMs > 0 }
    }
    val showResume = playbackState.currentTrack == null && lastPlayedTrack != null

    val currentTrackId = playbackState.currentTrack?.id
    val isPlaybackActive = playbackState.isPlaying

    Surface(
        modifier = modifier.fillMaxSize(),
        color = if (SonoraTheme.colors.isLiquidGlass) Color.Transparent else SonoraAmoledBlack
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 20.dp, bottom = LocalBottomChromeInset.current + 8.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Greeting Header + "Riprendi ad ascoltare" share the same item, so hiding the resume
            // block collapses it without leaving an extra gap from the list spacing.
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(greetingRes),
                                style = MaterialTheme.typography.headlineLarge.copy(
                                    fontWeight = FontWeight.Black,
                                    fontSize = 30.sp,
                                    letterSpacing = (-0.5).sp,
                                    color = scheme.onBackground
                                )
                            )
                            Text(
                                text = stringResource(R.string.home_tagline),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    color = scheme.accentSecondary
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = showResume,
                        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                        exit = shrinkVertically(shrinkTowards = Alignment.Top) +
                            fadeOut() +
                            slideOutVertically(targetOffsetY = { -it / 4 })
                    ) {
                        val track = lastPlayedTrack
                        if (track != null) {
                            Column {
                                SectionHeader(title = stringResource(R.string.home_resume_title))
                                ResumeHero(
                                    track = track,
                                    isPlaying = false,
                                    onClick = { onTrackClick(track) },
                                    modifier = Modifier.padding(horizontal = Spacing.gutter)
                                )
                            }
                        }
                    }
                }
            }

            // Top tracks (EU): the most-played song of each European country
            if (countryTopTracks.isNotEmpty() || isLoadingCountryTopTracks) {
                item {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = Spacing.gutter)
                        ) {
                            Text(
                                text = stringResource(R.string.home_top_tracks_title),
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = scheme.onBackground
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            AsyncImage(
                                model = EuropeChartsClient.flagUrl("eu", 80),
                                contentDescription = stringResource(R.string.home_eu_flag_content_description),
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .height(17.dp)
                                    .width(25.dp)
                                    .clip(RoundedCornerShape(3.dp))
                            )
                        }
                        Spacer(modifier = Modifier.height(Spacing.xs))
                        Text(
                            text = stringResource(R.string.home_top_tracks_subtitle),
                            style = MaterialTheme.typography.bodySmall.copy(color = scheme.onBackgroundSecondary),
                            modifier = Modifier.padding(horizontal = Spacing.gutter)
                        )
                        Spacer(modifier = Modifier.height(Spacing.sm))

                        if (countryTopTracks.isNotEmpty()) {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = Spacing.gutter),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.lg)
                            ) {
                                items(countryTopTracks, key = { "eu_${it.countryCode}" }) { topTrack ->
                                    EuCountryCard(
                                        topTrack = topTrack,
                                        isPlaying = isPlaybackActive && currentTrackId == topTrack.track.id,
                                        onPlayClick = { onTrackClick(topTrack.track) }
                                    )
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.padding(horizontal = Spacing.gutter),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.lg)
                            ) {
                                repeat(2) {
                                    Box(
                                        modifier = Modifier
                                            .width(300.dp)
                                            .height(190.dp)
                                            .shimmer(RoundedCornerShape(Radius.Large))
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 2. Quick Picks: Smart carousel based on user listening history
            if (quickPicks.isNotEmpty()) {
                item {
                    Column {
                        SectionHeader(title = stringResource(R.string.home_quick_picks_title))
                        Text(
                            text = stringResource(
                                if (recentlyPlayed.isNotEmpty()) R.string.home_quick_picks_subtitle_recent
                                else R.string.home_quick_picks_subtitle_top
                            ),
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = scheme.onBackgroundSecondary
                            ),
                            modifier = Modifier.padding(horizontal = Spacing.gutter)
                        )

                        Spacer(modifier = Modifier.height(Spacing.sm))

                        LazyRow(
                            contentPadding = PaddingValues(horizontal = Spacing.gutter),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                        ) {
                            items(quickPicks, key = { "qp_${it.id}" }) { track ->
                                QuickPickCard(
                                    track = track,
                                    isCurrent = currentTrackId == track.id,
                                    onClick = { onTrackClick(track) }
                                )
                            }
                        }
                    }
                }
            }

            // 3. Global Top Charts: Strictly limited to 5 tracks with rank badges (1..5)
            item {
                Column {
                    SectionHeader(
                        title = stringResource(R.string.home_global_charts_title),
                        actionLabel = if (top5Charts.isNotEmpty()) stringResource(R.string.home_play_all_action) else null,
                        onAction = onPlayAllCharts
                    )
                    Text(
                        text = stringResource(R.string.home_global_charts_subtitle),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = scheme.onBackgroundSecondary
                        ),
                        modifier = Modifier.padding(horizontal = Spacing.gutter)
                    )
                }
            }

            // Top Charts Strictly 5 Tracks List with Rank Badges
            if (isLoadingCharts && top5Charts.isEmpty()) {
                items(5) { _ ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.gutter)
                            .height(64.dp)
                            .shimmer(RoundedCornerShape(Radius.Small))
                    )
                }
            } else {
                itemsIndexed(top5Charts, key = { _, track -> "chart_${track.id}" }) { index, track ->
                    val isCurrent = currentTrackId == track.id
                    TrackItem(
                        track = track,
                        isPlaying = isCurrent && isPlaybackActive,
                        isCurrentTrack = isCurrent,
                        isFavorite = isFavorite(track.id),
                        isDisliked = isDisliked(track.id),
                        rank = index + 1,
                        onTrackClick = { onTrackClick(track) },
                        onFavoriteClick = { onFavoriteToggle(track) },
                        onAddToQueue = { onAddToQueue(track) },
                        onPlayNextTrack = { onPlayNextTrack(track) },
                        onAddToPlaylist = { onAddToPlaylist(track) },
                        onShareClick = { onShareTrack(track) },
                        onDislikeClick = { onDislikeTrack(track) },
                        sourceActions = sourceActions,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }

            // 4. "Consigliati per te": Dedicated recommendation section right beneath the Top 5 charts
            item {
                Column {
                    SectionHeader(title = stringResource(R.string.home_recommendations_title))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.gutter),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.home_recommendations_subtitle),
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = scheme.onBackgroundSecondary
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(Spacing.sm))
                        AiCuratedBadge()
                    }
                }
            }

            if (isLoadingRecommendations && validRecommendations.isEmpty()) {
                items(4) { _ ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.gutter)
                            .height(64.dp)
                            .shimmer(RoundedCornerShape(Radius.Small))
                    )
                }
            } else if (validRecommendations.isNotEmpty()) {
                items(validRecommendations, key = { "rec_${it.id}" }) { track ->
                    val isCurrent = currentTrackId == track.id
                    TrackItem(
                        track = track,
                        isPlaying = isCurrent && isPlaybackActive,
                        isCurrentTrack = isCurrent,
                        isFavorite = isFavorite(track.id),
                        isDisliked = isDisliked(track.id),
                        onTrackClick = { onTrackClick(track) },
                        onFavoriteClick = { onFavoriteToggle(track) },
                        onAddToQueue = { onAddToQueue(track) },
                        onPlayNextTrack = { onPlayNextTrack(track) },
                        onAddToPlaylist = { onAddToPlaylist(track) },
                        onShareClick = { onShareTrack(track) },
                        onDislikeClick = { onDislikeTrack(track) },
                        sourceActions = sourceActions,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            } else {
                item {
                    GlassPlaceholder(
                        modifier = Modifier.padding(horizontal = Spacing.gutter)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = scheme.accent,
                            modifier = Modifier.align(Alignment.CenterHorizontally).size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.home_recommendations_empty_title),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = scheme.onBackground
                            ),
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                        Spacer(modifier = Modifier.height(Spacing.xs))
                        Text(
                            text = stringResource(R.string.home_recommendations_empty_body),
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = scheme.onBackgroundSecondary
                            ),
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(horizontal = Spacing.sm)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Home "Top tracks (EU)" card: same size as the old Featured hero (300x190) but topped by a
 * country flag + name badge so it is clear which country the chart entry comes from.
 */
@Composable
fun EuCountryCard(
    topTrack: CountryTopTrack,
    isPlaying: Boolean,
    onPlayClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current
    val track = topTrack.track
    Card(
        modifier = modifier
            .width(300.dp)
            .height(190.dp)
            .pressScale()
            .clip(SonoraTheme.cardShape)
            .clickable(role = Role.Button, onClick = onPlayClick),
        shape = SonoraTheme.cardShape,
        colors = CardDefaults.cardColors(containerColor = SonoraSurfaceCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, SonoraDivider)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Background Artwork
            if (!track.artworkUrl.isNullOrBlank()) {
                AsyncImage(
                    model = track.artworkUrl,
                    contentDescription = track.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Gradient Overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.94f),
                                Color.Black.copy(alpha = 0.70f),
                                Color.Transparent
                            )
                        )
                    )
            )

            // Content
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Country flag + name badge
                Row(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = EuropeChartsClient.flagUrl(topTrack.countryCode, 80),
                        contentDescription = topTrack.countryName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .height(14.dp)
                            .width(21.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = topTrack.countryName,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Info & Play Action
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = SonoraTextPrimary
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = track.artist,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = SonoraTextSecondary
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(
                        onClick = onPlayClick,
                        modifier = Modifier
                            .size(48.dp)
                            .background(scheme.accent, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = stringResource(R.string.home_play_content_description),
                            tint = Color.Black,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun QuickPickCard(
    track: Track,
    isCurrent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current
    val motion = LocalMotionPreferences.current
    val scale by animateFloatAsState(
        targetValue = if (isCurrent && motion.allowContinuousMotion) 1.03f else 1f,
        animationSpec = motion.springSnap(),
        label = "quickPickScale"
    )

    Column(
        modifier = modifier
            .width(130.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(130.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(SonoraSurfaceHighlight)
                .border(
                    width = if (isCurrent) 1.5.dp else 1.dp,
                    color = if (isCurrent) scheme.accent.copy(alpha = 0.85f) else Color(0xFFFFFFFF).copy(alpha = 0.12f),
                    shape = RoundedCornerShape(16.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (!track.artworkUrl.isNullOrBlank()) {
                AsyncImage(
                    model = track.artworkUrl,
                    contentDescription = track.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = SonoraTextTertiary,
                    modifier = Modifier.size(36.dp)
                )
            }

            if (isCurrent) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.home_playing_content_description),
                        tint = scheme.accent,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = track.title,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            ),
            color = if (isCurrent) scheme.accent else SonoraTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Text(
            text = track.artist,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 11.sp
            ),
            color = SonoraTextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassPanel(
        modifier = modifier
            .size(40.dp)
            .pressScale()
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        shape = CircleShape
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.align(Alignment.Center).size(20.dp)
        )
    }
}

@Composable
private fun ResumeHero(
    track: Track,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current
    GlassPanel(
        modifier = modifier
            .fillMaxWidth()
            .pressScale()
            .clip(RoundedCornerShape(Radius.Large))
            .clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(Radius.Large)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(Radius.Small)),
                contentAlignment = Alignment.Center
            ) {
                if (!track.artworkUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = track.artworkUrl,
                        contentDescription = track.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = scheme.onBackgroundTertiary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(Spacing.md))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(if (isPlaying) R.string.home_resume_playing_label else R.string.home_resume_label),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        color = scheme.accent
                    ),
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = scheme.onBackground
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = scheme.onBackgroundSecondary
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(Spacing.sm))

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(scheme.accent),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.home_play_content_description),
                    tint = if (scheme.isLight) Color.White else Color.Black,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

@Composable
private fun AiCuratedBadge() {
    val scheme = LocalBackgroundScheme.current
    GlassCard(shape = RoundedCornerShape(Radius.Small)) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = scheme.accent,
                modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(Spacing.xs))
            Text(
                text = stringResource(R.string.home_ai_curated_badge),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                ),
                color = scheme.accent
            )
        }
    }
}
