package org.sonora.android.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import coil.compose.AsyncImage
import kotlin.math.abs
import kotlin.math.roundToInt
import org.sonora.android.R
import org.sonora.android.api.Track
import org.sonora.android.playback.PlaybackState
import org.sonora.android.ui.NavItem
import org.sonora.android.ui.NavigationTab
import org.sonora.android.ui.navDragOffsetPx
import org.sonora.android.ui.navDragTargetIndex
import org.sonora.android.ui.navTabStepPx
import org.sonora.android.ui.navTabWidthPx
import org.sonora.android.ui.components.backdrop.backdrops.layerBackdrop
import org.sonora.android.ui.components.backdrop.backdrops.rememberLayerBackdrop
import org.sonora.android.ui.components.backdrop.chromeLiquidGlass
import org.sonora.android.ui.components.backdrop.glassContentColor
import org.sonora.android.ui.components.backdrop.glassIndicatorColor
import org.sonora.android.ui.theme.LocalBackgroundScheme
import org.sonora.android.ui.theme.LocalMotionPreferences

private const val GlassAccessorySharedKey = "sonora.glass.accessory"
private const val GlassTabGroupSharedKey = "sonora.glass.tabGroup"
private const val GlassSearchSharedKey = "sonora.glass.search"
private val GlassPillShape = RoundedCornerShape(percent = 50)
private val GlassBarHeight = 64.dp
private val GlassNowPlayingHeight = 64.dp
private val GlassSearchSize = 64.dp
private val GlassTabGap = 6.dp
private val GlassInset = 6.dp
private val GlassSpring = spring<Float>(dampingRatio = 0.72f, stiffness = 320f)

internal class GlassChromeScrollState(private val thresholdPx: Float) {
    var isInline by androidx.compose.runtime.mutableStateOf(false)
        private set
    private var accumulated = 0f

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: NestedScrollSource): androidx.compose.ui.geometry.Offset {
            val delta = available.y
            if ((accumulated > 0f && delta < 0f) || (accumulated < 0f && delta > 0f)) {
                accumulated = 0f
            }
            accumulated += delta
            if (accumulated <= -thresholdPx && !isInline) {
                isInline = true
                accumulated = 0f
            } else if (accumulated >= thresholdPx && isInline) {
                isInline = false
                accumulated = 0f
            }
            return androidx.compose.ui.geometry.Offset.Zero
        }
    }

    fun expand() {
        isInline = false
        accumulated = 0f
    }
}

@Composable
internal fun rememberGlassChromeScrollState(threshold: Dp = 50.dp): GlassChromeScrollState {
    val density = LocalDensity.current
    return remember(density, threshold) {
        GlassChromeScrollState(with(density) { threshold.toPx() })
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun LiquidGlassChrome(
    navItems: List<NavItem>,
    currentTab: NavigationTab,
    isTabActive: Boolean,
    onSelect: (NavigationTab) -> Unit,
    playbackState: PlaybackState,
    isFavorite: Boolean,
    isInline: Boolean,
    onExpandChrome: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onOpenPlayer: () -> Unit,
    onPullStart: () -> Unit,
    onPull: (Float) -> Unit,
    onPullRelease: (Float) -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    modifier: Modifier = Modifier
) {
    val track = playbackState.currentTrack
    val scheme = LocalBackgroundScheme.current
    val fallback = scheme.gradient.first().copy(alpha = 0.94f)

    LaunchedEffect(track?.id) {
        if (track == null) onExpandChrome()
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AnimatedContent(
            targetState = isInline && track != null,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            contentAlignment = Alignment.BottomCenter,
            label = "glassChromeMode"
        ) { inline ->
            val visibilityScope = this
            val accessoryModifier = sharedTransitionScope?.sharedElementModifier(
                key = GlassAccessorySharedKey,
                animatedVisibilityScope = visibilityScope
            ) ?: Modifier
            val tabGroupModifier = sharedTransitionScope?.sharedElementModifier(
                key = GlassTabGroupSharedKey,
                animatedVisibilityScope = visibilityScope
            ) ?: Modifier
            val searchModifier = sharedTransitionScope?.sharedElementModifier(
                key = GlassSearchSharedKey,
                animatedVisibilityScope = visibilityScope
            ) ?: Modifier

            if (inline && track != null) {
                InlineChrome(
                    track = track,
                    playbackState = playbackState,
                    isFavorite = isFavorite,
                    currentTab = currentTab,
                    navItems = navItems,
                    onExpandChrome = onExpandChrome,
                    onSelect = onSelect,
                    onPlayPause = onPlayPause,
                    onSkipNext = onSkipNext,
                    onSkipPrevious = onSkipPrevious,
                    onFavoriteToggle = onFavoriteToggle,
                    onOpenPlayer = onOpenPlayer,
                    onPullStart = onPullStart,
                    onPull = onPull,
                    onPullRelease = onPullRelease,
                    fallback = fallback,
                    accessoryModifier = accessoryModifier,
                    tabGroupModifier = tabGroupModifier,
                    searchModifier = searchModifier
                )
            } else {
                ExpandedChrome(
                    track = track,
                    playbackState = playbackState,
                    isFavorite = isFavorite,
                    currentTab = currentTab,
                    isTabActive = isTabActive,
                    navItems = navItems,
                    onSelect = onSelect,
                    onPlayPause = onPlayPause,
                    onSkipNext = onSkipNext,
                    onSkipPrevious = onSkipPrevious,
                    onFavoriteToggle = onFavoriteToggle,
                    onOpenPlayer = onOpenPlayer,
                    onPullStart = onPullStart,
                    onPull = onPull,
                    onPullRelease = onPullRelease,
                    fallback = fallback,
                    accessoryModifier = accessoryModifier,
                    tabGroupModifier = tabGroupModifier,
                    searchModifier = searchModifier
                )
            }
        }
    }
}

@Composable
private fun ExpandedChrome(
    track: Track?,
    playbackState: PlaybackState,
    isFavorite: Boolean,
    currentTab: NavigationTab,
    isTabActive: Boolean,
    navItems: List<NavItem>,
    onSelect: (NavigationTab) -> Unit,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onOpenPlayer: () -> Unit,
    onPullStart: () -> Unit,
    onPull: (Float) -> Unit,
    onPullRelease: (Float) -> Unit,
    fallback: Color,
    accessoryModifier: Modifier,
    tabGroupModifier: Modifier,
    searchModifier: Modifier
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (track != null) {
            GlassNowPlaying(
                track = track,
                isPlaying = playbackState.isPlaying,
                isLoading = playbackState.isLoading,
                positionMs = playbackState.positionMs,
                durationMs = playbackState.durationMs,
                isFavorite = isFavorite,
                onPlayPause = onPlayPause,
                onSkipNext = onSkipNext,
                onSkipPrevious = onSkipPrevious,
                onFavoriteToggle = onFavoriteToggle,
                onOpenPlayer = onOpenPlayer,
                onPullStart = onPullStart,
                onPull = onPull,
                onPullRelease = onPullRelease,
                fallback = fallback,
                modifier = accessoryModifier
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val pillItems = navItems.filter { it.tab != NavigationTab.SEARCH }
            GlassTabPill(
                items = pillItems,
                currentTab = currentTab,
                isTabActive = isTabActive,
                onSelect = onSelect,
                fallback = fallback,
                modifier = Modifier.weight(1f).then(tabGroupModifier)
            )
            GlassSearchCircle(
                selected = currentTab == NavigationTab.SEARCH && isTabActive,
                onClick = { onSelect(NavigationTab.SEARCH) },
                fallback = fallback,
                modifier = searchModifier
            )
        }
    }
}

@Composable
private fun InlineChrome(
    track: Track,
    playbackState: PlaybackState,
    isFavorite: Boolean,
    currentTab: NavigationTab,
    navItems: List<NavItem>,
    onExpandChrome: () -> Unit,
    onSelect: (NavigationTab) -> Unit,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onOpenPlayer: () -> Unit,
    onPullStart: () -> Unit,
    onPull: (Float) -> Unit,
    onPullRelease: (Float) -> Unit,
    fallback: Color,
    accessoryModifier: Modifier,
    tabGroupModifier: Modifier,
    searchModifier: Modifier
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val selectedTab = navItems.firstOrNull { it.tab == currentTab } ?: NavItem.Home
        GlassIconButton(
            icon = selectedTab.icon,
            contentDescription = stringResource(selectedTab.titleRes),
            selected = true,
            onClick = onExpandChrome,
            fallback = fallback,
            modifier = tabGroupModifier
        )
        GlassInlineNowPlaying(
            track = track,
            isPlaying = playbackState.isPlaying,
            isLoading = playbackState.isLoading,
            onPlayPause = onPlayPause,
            onSkipNext = onSkipNext,
            onSkipPrevious = onSkipPrevious,
            onOpenPlayer = onOpenPlayer,
            onPullStart = onPullStart,
            onPull = onPull,
            onPullRelease = onPullRelease,
            fallback = fallback,
            modifier = Modifier.weight(1f).then(accessoryModifier)
        )
        GlassSearchCircle(
            selected = currentTab == NavigationTab.SEARCH,
            onClick = { onSelect(NavigationTab.SEARCH) },
            fallback = fallback,
            modifier = searchModifier
        )
    }
}

@Composable
private fun GlassNowPlaying(
    track: Track,
    isPlaying: Boolean,
    isLoading: Boolean,
    positionMs: Long,
    durationMs: Long,
    isFavorite: Boolean,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onOpenPlayer: () -> Unit,
    onPullStart: () -> Unit,
    onPull: (Float) -> Unit,
    onPullRelease: (Float) -> Unit,
    fallback: Color,
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current
    val content = glassContentColor()
    val progress = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val haptics = LocalHapticFeedback.current
    val favScale by animateFloatAsState(
        targetValue = if (isFavorite) 1.2f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "glassFavScale"
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(GlassNowPlayingHeight)
            .miniPlayerGestures(
                onNext = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSkipNext()
                },
                onPrevious = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSkipPrevious()
                },
                onPullStart = onPullStart,
                onPull = onPull,
                onPullRelease = onPullRelease,
                reduceMotion = LocalMotionPreferences.current.reduceMotion
            )
            .chromeLiquidGlass(GlassPillShape, fallback)
            .clickable(onClick = onOpenPlayer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(GlassPillShape)
        ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(Color.White.copy(alpha = 0.12f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(2.dp)
                    .background(scheme.accent)
            )
        }
        AnimatedContent(
            targetState = track,
            transitionSpec = {
                (slideInVertically(animationSpec = tween(260)) { height -> -height / 3 } +
                    fadeIn(tween(180))) togetherWith
                    (slideOutVertically(animationSpec = tween(220)) { height -> height / 3 } +
                        fadeOut(tween(160))) using SizeTransform(clip = false)
            },
            label = "glassTrackChange"
        ) { currentTrack ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.10f))
                        .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (!currentTrack.artworkUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = currentTrack.artworkUrl,
                            contentDescription = currentTrack.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(40.dp)
                        )
                    } else {
                        Icon(Icons.Default.MusicNote, null, tint = content.copy(alpha = 0.6f), modifier = Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = currentTrack.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                        color = content,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = currentTrack.artist,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = content.copy(alpha = 0.70f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onFavoriteToggle, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = stringResource(R.string.mini_player_favorite),
                        tint = content.copy(alpha = if (isFavorite) 1f else 0.70f),
                        modifier = Modifier
                            .size(20.dp)
                            .graphicsLayer { scaleX = favScale; scaleY = favScale }
                    )
                }
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    if (isLoading) {
                        CircularProgressIndicator(color = content, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    } else {
                        IconButton(onClick = onPlayPause, modifier = Modifier.size(40.dp)) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = stringResource(if (isPlaying) R.string.mini_player_pause else R.string.mini_player_play),
                                tint = content,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }
                IconButton(onClick = onSkipNext, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = stringResource(R.string.mini_player_next),
                        tint = content,
                        modifier = Modifier.size(25.dp)
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun GlassInlineNowPlaying(
    track: Track,
    isPlaying: Boolean,
    isLoading: Boolean,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onOpenPlayer: () -> Unit,
    onPullStart: () -> Unit,
    onPull: (Float) -> Unit,
    onPullRelease: (Float) -> Unit,
    fallback: Color,
    modifier: Modifier = Modifier
) {
    val content = glassContentColor()
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = modifier
            .height(GlassBarHeight)
            .miniPlayerGestures(
                onNext = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSkipNext()
                },
                onPrevious = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSkipPrevious()
                },
                onPullStart = onPullStart,
                onPull = onPull,
                onPullRelease = onPullRelease,
                reduceMotion = LocalMotionPreferences.current.reduceMotion
            )
            .chromeLiquidGlass(GlassPillShape, fallback)
            .clickable(onClick = onOpenPlayer)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Color.White.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            if (!track.artworkUrl.isNullOrBlank()) {
                AsyncImage(
                    model = track.artworkUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(32.dp)
                )
            } else {
                Icon(Icons.Default.MusicNote, null, tint = content.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = track.title,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(4.dp))
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            if (isLoading) {
                CircularProgressIndicator(color = content, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            } else {
                IconButton(onClick = onPlayPause, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = stringResource(if (isPlaying) R.string.mini_player_pause else R.string.mini_player_play),
                        tint = content,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun GlassTabPill(
    items: List<NavItem>,
    currentTab: NavigationTab,
    isTabActive: Boolean,
    onSelect: (NavigationTab) -> Unit,
    fallback: Color,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return
    val content = glassContentColor()
    val motionPrefs = LocalMotionPreferences.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
    val isRtl = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val selectedIndex = items.indexOfFirst { it.tab == currentTab }
    val currentSelectedIndex by rememberUpdatedState(selectedIndex)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val gapPx = with(density) { GlassTabGap.toPx() }
    val insetPx = with(density) { GlassInset.toPx() }
    val pill = remember { GlassPillMotion(scope, selectedIndex.coerceAtLeast(0)) }
    pill.reduceMotion = motionPrefs.reduceMotion
    val barGlass = rememberLayerBackdrop()
    val tabRow = rememberLayerBackdrop()
    var rowSize by remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var lastHapticTab by remember { mutableIntStateOf(selectedIndex.coerceAtLeast(0)) }
    var lastSelectedTab by remember { mutableIntStateOf(selectedIndex) }

    val tabCount = items.size
    val rowWidthPx = rowSize.width.toFloat()
    val rowHeightPx = rowSize.height.toFloat()
    val tabWidthPx = navTabWidthPx(rowWidthPx, gapPx, tabCount)
    val stepPx = navTabStepPx(rowWidthPx, gapPx, tabCount)
    val restSize = androidx.compose.ui.geometry.Size(tabWidthPx, rowHeightPx)
    val liftedHeightPx = rowHeightPx + insetPx * 2f + with(density) { PILL_GROW_HEIGHT.toPx() }
    val liftedSize = if (rowHeightPx > 0f) {
        androidx.compose.ui.geometry.Size(liftedHeightPx * tabWidthPx / rowHeightPx, liftedHeightPx)
    } else {
        restSize
    }
    val pillCenterInRow: (Float) -> androidx.compose.ui.geometry.Offset = { position ->
        val x = position * stepPx + tabWidthPx / 2f
        androidx.compose.ui.geometry.Offset(if (isRtl) rowWidthPx - x else x, rowHeightPx / 2f)
    }
    val showPill = selectedIndex >= 0 && tabWidthPx > 0f
    val fillColor = glassIndicatorColor().copy(alpha = 0.5f)
    pill.stepDp = stepPx / density.density

    LaunchedEffect(selectedIndex) {
        if (selectedIndex >= 0) {
            if (lastSelectedTab < 0) pill.snapTo(selectedIndex) else pill.animateTo(selectedIndex)
        }
        lastSelectedTab = selectedIndex
        lastHapticTab = selectedIndex.coerceAtLeast(0)
    }

    Box(
        modifier = modifier.height(GlassBarHeight)
    ) {
        // Surface and content are siblings: the backdrop modifier clips its
        // own content to the bar shape, while the lifted selection pill must be
        // free to stand past the bar's edges, as in BitChord.
        Box(
            modifier = Modifier
                .matchParentSize()
                .chromeLiquidGlass(GlassPillShape, fallback, exportedBackdrop = barGlass)
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(GlassInset)
        ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (showPill && pill.isFlat) {
                FlatSelectionPill(
                    color = fillColor,
                    modifier = Modifier
                        .matchParentSize()
                        .pillPlacement(
                            center = { pillCenterInRow(pill.position) },
                            size = { pill.liveSize(restSize, liftedSize) }
                        )
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { rowSize = it }
                    .layerBackdrop(tabRow)
                    .pointerInput(stepPx, gapPx, currentSelectedIndex) {
                        if (stepPx <= 0f || currentSelectedIndex < 0) return@pointerInput
                        val direction = if (isRtl) -1f else 1f
                        var total = 0f
                        var start = currentSelectedIndex
                        detectHorizontalDragGestures(
                            onDragStart = {
                                total = 0f
                                start = currentSelectedIndex
                                lastHapticTab = currentSelectedIndex.coerceAtLeast(0)
                                pill.startDrag()
                            },
                            onDragCancel = { pill.release(currentSelectedIndex.coerceAtLeast(0)) },
                            onDragEnd = {
                                val absolute = (start + total / stepPx)
                                    .coerceIn(0f, items.lastIndex.toFloat())
                                val index = absolute.roundToInt()
                                pill.release(index)
                                if (index != currentSelectedIndex) currentOnSelect(items[index].tab)
                            },
                            onHorizontalDrag = { change, delta ->
                                change.consume()
                                total += delta * direction
                                val absolute = (start + total / stepPx)
                                    .coerceIn(0f, items.lastIndex.toFloat())
                                pill.dragTo(absolute)
                                val tick = absolute.roundToInt()
                                if (tick != lastHapticTab) {
                                    lastHapticTab = tick
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }
                        )
                    },
                horizontalArrangement = Arrangement.spacedBy(GlassTabGap),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEach { item ->
                    val selected = isTabActive && item.tab == currentTab
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(GlassPillShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                if (!selected) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    val targetIndex = items.indexOfFirst { it.tab == item.tab }
                                    if (targetIndex >= 0) pill.animateTo(targetIndex)
                                }
                                currentOnSelect(item.tab)
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        val scale by animateFloatAsState(
                            targetValue = if (selected) 1.08f else 1f,
                            animationSpec = if (motionPrefs.reduceMotion) snap() else GlassSpring,
                            label = "glassTabScale"
                        )
                        Icon(
                            imageVector = item.icon,
                            contentDescription = stringResource(item.titleRes),
                            tint = if (selected) content else content.copy(alpha = 0.65f),
                            modifier = Modifier
                                .size(24.dp)
                                .graphicsLayer { scaleX = scale; scaleY = scale }
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(item.titleRes),
                            color = if (selected) content else content.copy(alpha = 0.65f),
                            fontSize = 10.5.sp,
                            lineHeight = 13.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        }
        if (showPill && !pill.isFlat) {
            GlassSelectionPill(
                motion = pill,
                fillColor = fillColor,
                barGlass = barGlass,
                tabRow = tabRow,
                modifier = Modifier
                    .matchParentSize()
                    .pillPlacement(
                        center = {
                            pillCenterInRow(pill.position) +
                                androidx.compose.ui.geometry.Offset(insetPx, insetPx)
                        },
                        size = { pill.liveSize(restSize, liftedSize) }
                    )
            )
        }
    }
}

@Composable
private fun GlassSearchCircle(
    selected: Boolean,
    onClick: () -> Unit,
    fallback: Color,
    modifier: Modifier = Modifier
) {
    val content = glassContentColor()
    val scheme = LocalBackgroundScheme.current
    Box(
        modifier = modifier
            .size(GlassSearchSize)
            .chromeLiquidGlass(CircleShape, fallback)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = stringResource(R.string.nav_search),
            tint = if (selected) scheme.accent else content,
            modifier = Modifier.size(25.dp)
        )
    }
}

@Composable
private fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit,
    fallback: Color,
    modifier: Modifier = Modifier
) {
    val content = glassContentColor()
    val scheme = LocalBackgroundScheme.current
    Box(
        modifier = modifier
            .size(GlassSearchSize)
            .chromeLiquidGlass(CircleShape, fallback)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (selected) scheme.accent else content,
            modifier = Modifier.size(25.dp)
        )
    }
}


@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedTransitionScope.sharedElementModifier(
    key: Any,
    animatedVisibilityScope: AnimatedVisibilityScope
): Modifier = Modifier.sharedBounds(
    sharedContentState = rememberSharedContentState(key),
    animatedVisibilityScope = animatedVisibilityScope,
    enter = fadeIn(tween(170)),
    exit = fadeOut(tween(130)),
    resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds
)
