package org.sonora.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbDownOffAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import org.sonora.android.R
import org.sonora.android.api.ArtistCredit
import org.sonora.android.api.HighlightMode
import org.sonora.android.api.Lyrics
import org.sonora.android.api.LyricsTiming
import org.sonora.android.api.lyrics.sources.LyricsProviderState
import org.sonora.android.api.lyrics.sources.LyricsSource
import org.sonora.android.api.Track
import org.sonora.android.api.TrackMetadata
import org.sonora.android.api.activeLineIndex
import org.sonora.android.playback.DownloadProgress
import org.sonora.android.playback.DownloadStatus
import org.sonora.android.playback.PlaybackState
import org.sonora.android.playback.RepeatMode
import org.sonora.android.ui.components.AudioOutputSheet
import org.sonora.android.ui.components.rememberAudioOutputLauncher
import org.sonora.android.ui.components.BitchordQueueList
import org.sonora.android.ui.components.ArtistLinks
import org.sonora.android.ui.components.LocalTrackContextMenu
import org.sonora.android.ui.components.TrackAnchor
import org.sonora.android.ui.components.TrackActionsSheet
import org.sonora.android.ui.components.MarqueeText
import org.sonora.android.ui.components.PlayerSharedKeys
import org.sonora.android.ui.components.SourceSwitchMenuItems
import org.sonora.android.ui.components.TrackSourceActions
import org.sonora.android.ui.player.v2.ArtworkGradient
import org.sonora.android.ui.player.v2.rememberArtworkGradient
import org.sonora.android.ui.theme.LocalBackgroundScheme
import org.sonora.android.ui.theme.LocalMotionPreferences
import org.sonora.android.ui.theme.artworkBottomFade
import org.sonora.android.ui.theme.pressScale

/**
 * Convx-inspired immersive player.
 *
 * The artwork owns the top of the screen and fades into a gradient sampled from its own
 * edges; controls float over that gradient instead of sitting in a scrollable card.
 * The outgoing classic player is intentionally replaced: this is the only Now Playing UI.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun PlayerScreenV2(
    playbackState: PlaybackState,
    isFavorite: Boolean,
    isDisliked: Boolean = false,
    lyrics: Lyrics?,
    isLoadingLyrics: Boolean,
    isLyricsOpen: Boolean,
    onCollapse: () -> Unit,
    onPlayPause: () -> Unit,
    onPlayNext: () -> Unit,
    onPlayPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleFavorite: (Track) -> Unit,
    onToggleDislike: (Track) -> Unit = {},
    downloadProgress: DownloadProgress = DownloadProgress(),
    onToggleDownload: (Track) -> Unit = {},
    onAddToPlaylist: (Track) -> Unit = {},
    onAddToQueue: (Track) -> Unit = {},
    onPlayNextTrack: (Track) -> Unit = {},
    onShareTrack: (Track) -> Unit = {},
    sourceActions: TrackSourceActions? = null,
    onToggleLyrics: () -> Unit,
    onOpenEqualizer: () -> Unit = {},
    onOpenParty: () -> Unit = {},
    onOpenAlbum: (String) -> Unit = {},
    onRequestTrackMetadata: (Track) -> Unit = {},
    isPartyActive: Boolean = false,
    onArtistClick: (ArtistCredit) -> Unit = {},
    trackMetadata: TrackMetadata? = null,
    metadataLoading: Boolean = false,
    translateLyrics: Boolean = false,
    targetLang: String = "it",
    highlightMode: HighlightMode = HighlightMode.AUTO,
    onToggleTranslate: (Boolean) -> Unit = {},
    onTargetLangChange: (String) -> Unit = {},
    onHighlightModeChange: (HighlightMode) -> Unit = {},
    musixmatchEnabled: Boolean = true,
    onToggleMusixmatch: (Boolean) -> Unit = {},
    lrclibEnabled: Boolean = true,
    onToggleLrclib: (Boolean) -> Unit = {},
    activeLyricsSource: LyricsSource? = null,
    lyricsProviderStates: Map<LyricsSource, LyricsProviderState> = emptyMap(),
    onSelectLyricsSource: (LyricsSource?) -> Unit = {},
    onQueueTrackClick: (Int) -> Unit = {},
    onQueueRemove: (Int) -> Unit = {},
    onQueueClear: () -> Unit = {},
    onQueueMove: ((Int, Int) -> Unit)? = null,
    autoplayEnabled: Boolean = false,
    onToggleAutoplay: () -> Unit = {},
    sharedTransitionScope: androidx.compose.animation.SharedTransitionScope? = null,
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier
) {
    val currentTrack = playbackState.currentTrack ?: return
    val scheme = LocalBackgroundScheme.current
    val gradient = rememberArtworkGradient(currentTrack.artworkUrl, scheme.accent)

    var queueOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var audioOutputOpen by remember { mutableStateOf(false) }
    val openAudioOutput = rememberAudioOutputLauncher { audioOutputOpen = true }
    val openTrackMenu: () -> Unit = {
        onRequestTrackMetadata(currentTrack)
        menuOpen = true
    }

    BackHandler(enabled = menuOpen) { menuOpen = false }
    BackHandler(enabled = queueOpen) { queueOpen = false }

    val motion = LocalMotionPreferences.current
    val density = LocalDensity.current
    val queueProgress = remember { Animatable(0f) }
    LaunchedEffect(queueOpen, motion.reduceMotion) {
        val target = if (queueOpen) 1f else 0f
        if (motion.reduceMotion) {
            queueProgress.snapTo(target)
        } else {
            queueProgress.animateTo(
                targetValue = target,
                animationSpec = tween(420, easing = FastOutSlowInEasing)
            )
        }
    }
    val queueSlide = queueProgress.value
    var deckHeightPx by remember { mutableFloatStateOf(0f) }
    val playerChromeAlpha by animateFloatAsState(
        targetValue = if (isLyricsOpen) 0f else 1f,
        animationSpec = tween(if (motion.reduceMotion) 0 else 220),
        label = "playerChromeAlpha",
    )

    val ink = if (gradient.bottom.luminance() > 0.55f) Color(0xFF101012) else Color.White
    val inkSecondary = lerp(Color.White, gradient.top, 0.32f).copy(alpha = 0.80f)
    val inkTertiary = inkSecondary.copy(alpha = 0.52f)
    val accent = if (scheme.accent.luminance() < 0.12f) ink else scheme.accent

    val sharedSurfaceModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedBounds(
                sharedContentState = rememberSharedContentState(PlayerSharedKeys.SURFACE),
                animatedVisibilityScope = animatedVisibilityScope,
                enter = fadeIn(),
                exit = fadeOut(),
                resizeMode = androidx.compose.animation.SharedTransitionScope.ResizeMode.RemeasureToBounds
            )
        }
    } else {
        Modifier
    }

    val coverFraction = 0.56f

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .then(sharedSurfaceModifier)
            .clipToBounds()
            .background(
                Brush.verticalGradient(
                    0f to gradient.top,
                    coverFraction to gradient.bottom,
                    1f to gradient.bottom
                )
            )
    ) {
        val coverHeight = (maxHeight * coverFraction)
            .coerceAtMost(maxHeight - 360.dp)
            .coerceAtLeast(240.dp)
        val thumbSize = 48.dp
        val thumbStart = 56.dp
        val thumbTop = 8.dp
        val headerHeight = 64.dp
        val statusBarTopPx = WindowInsets.statusBars.getTop(density).toFloat()
        val statusBarTop = with(density) { statusBarTopPx.toDp() }
        val thumbStartPx = with(density) { thumbStart.toPx() }
        val thumbTopPx = statusBarTopPx + with(density) { thumbTop.toPx() }
        val heroScale = with(density) { thumbSize.toPx() } /
            with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val deckHeight = with(density) { deckHeightPx.toDp() }

        // One title, two positions. Scaling around the row's top-left lets the
        // same composable travel from under the artwork into the compact header
        // without a second copy or a fade-out/fade-in hand-off.
        val compactTitleStart = thumbStart + thumbSize + 12.dp
        val compactTitleTop = statusBarTop + thumbTop
        val compactTitleScale = (
            (maxWidth - compactTitleStart - 12.dp) / (maxWidth - 44.dp)
        ).coerceIn(0.70f, 0.85f)
        val titleTargetX = compactTitleStart - 22.dp * compactTitleScale
        val titleTargetY = compactTitleTop - coverHeight - 18.dp * compactTitleScale

            // The song stays as a full-screen blur; the sharp sleeve dissolves into
            // that same image so there is no seam at the bottom of the cover.
            Box(modifier = Modifier.fillMaxSize()) {
                if (!currentTrack.artworkUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = currentTrack.artworkUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .blur(64.dp)
                    )
                }
            }

            HeroCover(
                track = currentTrack,
                previousTrack = playbackState.queue.getOrNull(playbackState.currentIndex - 1),
                nextTrack = playbackState.queue.getOrNull(playbackState.currentIndex + 1),
                canGoPrevious = playbackState.hasPrevious,
                canGoNext = playbackState.hasNext,
                gradient = gradient,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(coverHeight)
                    .graphicsLayer {
                        val p = queueSlide
                        val scale = 1f + (heroScale - 1f) * p
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0f, 0f)
                        translationX = thumbStartPx * p
                        translationY = thumbTopPx * p
                    }
                    .align(Alignment.TopCenter),
                onPrevious = onPlayPrevious,
                onNext = onPlayNext
            )

            // One continuous scrim darkens sleeve and blur together, keeping the
            // lower text legible without introducing a colour band.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            (coverFraction - 0.18f).coerceAtLeast(0f) to Color.Transparent,
                            coverFraction to Color.Black.copy(alpha = 0.36f),
                            0.66f to Color.Black.copy(alpha = 0.64f),
                            0.86f to Color.Black.copy(alpha = 0.84f),
                            1f to Color.Black.copy(alpha = 0.94f)
                        )
                    )
            )

            // Keeps the status-bar glyphs legible over bright artwork.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.38f),
                            0.55f to Color.Black.copy(alpha = 0.12f),
                            1f to Color.Transparent
                        )
                    )
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = (1f - queueSlide) * playerChromeAlpha }
            ) {
                PlayerTopBar(
                    ink = ink,
                    onCollapse = onCollapse
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = playerChromeAlpha }
            ) {
                Spacer(modifier = Modifier.height(coverHeight))

                TrackMetaRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            val p = queueSlide
                            translationX = titleTargetX.toPx() * p
                            translationY = titleTargetY.toPx() * p
                            scaleX = 1f + (compactTitleScale - 1f) * p
                            scaleY = 1f + (compactTitleScale - 1f) * p
                            transformOrigin = TransformOrigin(0f, 0f)
                        }
                        .padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 8.dp),
                    track = currentTrack,
                    isFavorite = isFavorite,
                    downloadProgress = downloadProgress,
                    ink = ink,
                    inkSecondary = inkSecondary,
                    accent = accent,
                    controlsAlpha = (1f - queueSlide * 1.35f).coerceIn(0f, 1f),
                    onArtistClick = onArtistClick,
                    onToggleFavorite = { onToggleFavorite(currentTrack) },
                    onOpenMenu = openTrackMenu
                )

                Spacer(modifier = Modifier.weight(1f))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .onSizeChanged { deckHeightPx = it.height.toFloat() }
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                1f to gradient.bottom.copy(alpha = 0.20f)
                            )
                        )
                        .padding(horizontal = 22.dp, vertical = 8.dp)
                ) {
                    CurrentLyricStrip(
                        lyrics = lyrics,
                        positionMs = playbackState.positionMs,
                        isLoading = isLoadingLyrics,
                        highlightMode = highlightMode,
                        ink = ink,
                        inkSecondary = inkSecondary,
                        accent = accent,
                        onClick = onToggleLyrics
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    SeekSection(
                        playbackState = playbackState,
                        ink = ink,
                        inkSecondary = inkSecondary,
                        accent = accent,
                        onSeekTo = { onSeekTo(it) }
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    TransportRow(
                        playbackState = playbackState,
                        ink = ink,
                        accent = accent,
                        onPlayPause = onPlayPause,
                        onPrevious = onPlayPrevious,
                        onNext = onPlayNext
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    PlayerActionRow(
                        lyricsOpen = isLyricsOpen,
                        queueOpen = queueOpen,
                        shuffleEnabled = playbackState.isShuffle,
                        repeatMode = playbackState.repeatMode,
                        autoplayEnabled = autoplayEnabled,
                        ink = ink,
                        inkSecondary = inkSecondary,
                        accent = accent,
                        onToggleLyrics = onToggleLyrics,
                        onToggleQueue = { queueOpen = !queueOpen },
                        onToggleShuffle = onToggleShuffle,
                        onToggleRepeat = onToggleRepeat,
                        onToggleAutoplay = onToggleAutoplay,
                        onOpenOutput = openAudioOutput,
                        onOpenParty = onOpenParty,
                        isPartyActive = isPartyActive
                    )
                    Spacer(modifier = Modifier.height(44.dp))
                }
            }

        if (menuOpen) {
            TrackActionsSheet(
                track = currentTrack,
                metadata = trackMetadata,
                metadataLoading = metadataLoading,
                isFavorite = isFavorite,
                isDisliked = isDisliked,
                sourceActions = sourceActions,
                onDismissRequest = { menuOpen = false },
                onToggleFavorite = { onToggleFavorite(currentTrack) },
                onToggleDislike = { onToggleDislike(currentTrack) },
                onPlayNext = { onPlayNextTrack(currentTrack) },
                onAddToQueue = { onAddToQueue(currentTrack) },
                onAddToPlaylist = { onAddToPlaylist(currentTrack) },
                onShare = { onShareTrack(currentTrack) },
                onOpenEqualizer = onOpenEqualizer,
                onOpenAlbum = onOpenAlbum,
                onOpenArtist = onArtistClick,
            )
        }

        if (audioOutputOpen) {
            AudioOutputSheet(
                onDismiss = { audioOutputOpen = false },
                onOpenEqualizer = {
                    audioOutputOpen = false
                    onOpenEqualizer()
                },
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(headerHeight)
                .zIndex(4f)
                .graphicsLayer {
                    alpha = ((queueSlide - 0.48f) / 0.52f).coerceIn(0f, 1f)
                }
        ) {
            QueueHeaderControls(
                enabled = queueOpen,
                onClose = { queueOpen = false },
                onOpenMenu = openTrackMenu
            )
        }

        if (queueOpen || queueSlide > 0.001f) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = deckHeight)
                    .zIndex(3f)
                    .graphicsLayer {
                        alpha = ((queueSlide - 0.56f) / 0.44f).coerceIn(0f, 1f)
                        translationY = (1f - queueSlide) * 14.dp.toPx()
                    }
            ) {
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(headerHeight)
                )
                BitchordQueueList(
                    playbackState = playbackState,
                    onTrackClick = onQueueTrackClick,
                    onRemoveTrack = onQueueRemove,
                    onClearUpcoming = onQueueClear,
                    onToggleAutoplay = onToggleAutoplay,
                    onMoveQueueItem = onQueueMove,
                    sourceActions = sourceActions,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        AnimatedVisibility(
            visible = isLyricsOpen,
            modifier = Modifier
                .fillMaxSize()
                .zIndex(6f),
            enter = fadeIn(tween(260)) + slideInVertically(tween(320)) { it / 4 },
            exit = fadeOut(tween(220)) + slideOutVertically(tween(220)) { it / 4 }
        ) {
            LyricsView(
                track = currentTrack,
                lyrics = lyrics,
                isLoading = isLoadingLyrics,
                playbackState = playbackState,
                translateLyrics = translateLyrics,
                targetLang = targetLang,
                highlightMode = highlightMode,
                onToggleTranslate = onToggleTranslate,
                onTargetLangChange = onTargetLangChange,
                onHighlightModeChange = onHighlightModeChange,
                musixmatchEnabled = musixmatchEnabled,
                onToggleMusixmatch = onToggleMusixmatch,
                lrclibEnabled = lrclibEnabled,
                onToggleLrclib = onToggleLrclib,
                activeSource = activeLyricsSource,
                providerStates = lyricsProviderStates,
                onSelectLyricsSource = onSelectLyricsSource,
                onSeekTo = onSeekTo,
                onPlayPause = onPlayPause,
                onPlayPrevious = onPlayPrevious,
                onPlayNext = onPlayNext,
                onToggleShuffle = onToggleShuffle,
                onClose = onToggleLyrics
            )
        }
    }
}

@Composable
private fun HeroCover(
    track: Track,
    previousTrack: Track?,
    nextTrack: Track?,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    gradient: ArtworkGradient,
    modifier: Modifier = Modifier,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    val haptics = LocalHapticFeedback.current
    val peek = when {
        offset.value > 0f -> previousTrack
        offset.value < 0f -> nextTrack
        else -> null
    }

    Box(
        modifier = modifier
            .artworkBottomFade()
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(canGoPrevious, canGoNext) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val threshold = widthPx * 0.24f
                        val current = offset.value
                        scope.launch {
                            if (kotlin.math.abs(current) > threshold) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                val forward = current < 0f
                                offset.animateTo(
                                    targetValue = if (forward) -widthPx else widthPx,
                                    animationSpec = tween(180)
                                )
                                if (forward) {
                                    if (canGoNext) onNext()
                                } else if (canGoPrevious) {
                                    onPrevious()
                                }
                                offset.snapTo(0f)
                            } else {
                                offset.animateTo(0f, tween(180))
                            }
                        }
                    },
                    onDragCancel = {
                        scope.launch { offset.animateTo(0f, tween(160)) }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        val enabled = if (dragAmount < 0f) canGoNext else canGoPrevious
                        if (enabled) {
                            scope.launch {
                                offset.snapTo((offset.value + dragAmount).coerceIn(-widthPx, widthPx))
                            }
                        }
                    }
                )
            }
    ) {
        val progress = (kotlin.math.abs(offset.value) / widthPx).coerceIn(0f, 1f)
        if (peek != null && progress > 0f) {
            AsyncImage(
                model = peek.artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = progress }
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = offset.value
                    scaleX = 1f + 0.02f * progress
                    scaleY = 1f + 0.02f * progress
                }
                .background(gradient.top),
            contentAlignment = Alignment.Center
        ) {
            if (track.artworkUrl.isNullOrBlank()) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.35f),
                    modifier = Modifier.size(96.dp)
                )
            } else {
                AsyncImage(
                    model = track.artworkUrl,
                    contentDescription = track.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun QueueHeaderControls(
    enabled: Boolean,
    onClose: () -> Unit,
    onOpenMenu: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 8.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose, enabled = enabled) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(R.string.queue_close_content_description),
                tint = Color.White.copy(alpha = 0.9f)
            )
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onOpenMenu, enabled = enabled) {
            Icon(
                painter = painterResource(R.drawable.convx_more),
                contentDescription = stringResource(R.string.queue_track_options_content_description),
                tint = Color.White.copy(alpha = 0.75f)
            )
        }
    }
}

@Composable
private fun PlayerTopBar(
    ink: Color,
    onCollapse: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(44.dp)
                .height(5.dp)
                .clip(RoundedCornerShape(50))
                .background(ink.copy(alpha = 0.38f))
                .clickable(onClick = onCollapse)
        )
        IconButton(
            onClick = onCollapse,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 8.dp)
                .size(42.dp)
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(R.string.player_collapse_desc),
                tint = ink,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackMetaRow(
    track: Track,
    isFavorite: Boolean,
    downloadProgress: DownloadProgress,
    ink: Color,
    inkSecondary: Color,
    accent: Color,
    onArtistClick: (ArtistCredit) -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier,
    controlsAlpha: Float = 1f,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            val titleAnchor = remember { TrackAnchor() }
            val contextMenu = LocalTrackContextMenu.current
            MarqueeText(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp,
                    lineHeight = 25.sp,
                    letterSpacing = (-0.45).sp
                ),
                color = ink,
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coordinates ->
                        if (coordinates.isAttached) titleAnchor.rect = coordinates.boundsInWindow()
                    }
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            if (contextMenu != null && titleAnchor.rect != Rect.Zero) {
                                contextMenu.open(track, titleAnchor.rect)
                            }
                        }
                    )
            )
            Spacer(modifier = Modifier.height(2.dp))
            ArtistLinks(
                track = track,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Normal,
                    fontSize = 19.sp,
                    lineHeight = 24.sp,
                    letterSpacing = (-0.38).sp
                ),
                color = inkSecondary,
                modifier = Modifier.fillMaxWidth(),
                linksEnabled = true,
                onArtistClick = onArtistClick
            )
        }

        val heartScale by animateFloatAsState(
            targetValue = if (isFavorite) 1.10f else 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            label = "playerHeartScale"
        )
        BareIconButton(
            onClick = onToggleFavorite,
            enabled = controlsAlpha > 0.2f,
            modifier = Modifier.graphicsLayer { alpha = controlsAlpha },
        ) {
            Icon(
                painter = painterResource(
                    if (isFavorite) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline
                ),
                contentDescription = stringResource(R.string.player_favorite_desc),
                tint = if (isFavorite) accent else inkSecondary,
                modifier = Modifier
                    .size(26.dp)
                    .graphicsLayer {
                        scaleX = heartScale
                        scaleY = heartScale
                    }
            )
        }

        BareIconButton(
            onClick = onOpenMenu,
            enabled = controlsAlpha > 0.2f,
            modifier = Modifier.graphicsLayer { alpha = controlsAlpha },
        ) {
            Icon(
                painter = painterResource(R.drawable.convx_more),
                contentDescription = stringResource(R.string.player_more_desc),
                tint = inkSecondary,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
private fun BareIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center,
        content = { content() }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekSection(
    playbackState: PlaybackState,
    ink: Color,
    inkSecondary: Color,
    accent: Color,
    onSeekTo: (Long) -> Unit
) {
    var isUserSeeking by remember { mutableStateOf(false) }
    var seekProgress by remember { mutableFloatStateOf(0f) }
    val durationMs = playbackState.durationMs.coerceAtLeast(1L)
    val progress = if (isUserSeeking) {
        seekProgress
    } else {
        (playbackState.positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }
    val shownPositionMs = if (isUserSeeking) (seekProgress * durationMs).toLong() else playbackState.positionMs

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = progress,
            onValueChange = {
                isUserSeeking = true
                seekProgress = it
            },
            onValueChangeFinished = {
                isUserSeeking = false
                onSeekTo((seekProgress * durationMs).toLong())
            },
            colors = SliderDefaults.colors(
                thumbColor = Color.Transparent,
                activeTrackColor = ink,
                inactiveTrackColor = ink.copy(alpha = 0.18f)
            ),
            thumb = { Spacer(modifier = Modifier.size(0.dp)) },
            track = { sliderState ->
                val start = sliderState.valueRange.start
                val end = sliderState.valueRange.endInclusive
                val fraction = if (end > start) {
                    ((sliderState.value - start) / (end - start)).coerceIn(0f, 1f)
                } else 0f
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(ink.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .background(ink)
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatPlayerTime(shownPositionMs),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = inkSecondary
            )
            playbackState.streamProvider?.takeIf { it.isNotBlank() }?.let { provider ->
                Text(
                    text = provider,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = accent.copy(alpha = 0.90f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
            Text(
                text = formatPlayerTime(playbackState.durationMs),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = inkSecondary
            )
        }
    }
}

@Composable
private fun TransportRow(
    playbackState: PlaybackState,
    ink: Color,
    accent: Color,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onPrevious,
            modifier = Modifier
                .size(62.dp)
                .pressScale()
        ) {
            Icon(
                painter = painterResource(R.drawable.flamingo_rewind),
                contentDescription = stringResource(R.string.player_previous),
                tint = ink,
                modifier = Modifier.size(38.dp)
            )
        }

        Box(modifier = Modifier.size(76.dp), contentAlignment = Alignment.Center) {
            if (playbackState.isLoading) {
                CircularProgressIndicator(
                    color = accent,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(44.dp)
                )
            } else {
                IconButton(
                    onClick = onPlayPause,
                    modifier = Modifier
                        .size(72.dp)
                        .pressScale()
                ) {
                    Icon(
                        painter = painterResource(
                            if (playbackState.isPlaying) R.drawable.flamingo_pause else R.drawable.flamingo_play
                        ),
                        contentDescription = if (playbackState.isPlaying) {
                            stringResource(R.string.player_pause)
                        } else {
                            stringResource(R.string.player_play)
                        },
                        tint = ink,
                        modifier = Modifier.size(46.dp)
                    )
                }
            }
        }

        IconButton(
            onClick = onNext,
            modifier = Modifier
                .size(62.dp)
                .pressScale()
        ) {
            Icon(
                painter = painterResource(R.drawable.flamingo_fforward),
                contentDescription = stringResource(R.string.player_next),
                tint = ink,
                modifier = Modifier.size(38.dp)
            )
        }
    }
}

@Composable
private fun AmbientGlow(
    gradient: ArtworkGradient,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "playerAmbient")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(16000, easing = FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "playerAmbientDrift"
    )
    Canvas(modifier = modifier) {
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(gradient.top.copy(alpha = 0.26f), Color.Transparent),
                center = Offset(size.width * (0.18f + 0.18f * drift), size.height * 0.34f),
                radius = size.width * 0.95f
            )
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(gradient.bottom.copy(alpha = 0.34f), Color.Transparent),
                center = Offset(size.width * (0.82f - 0.14f * drift), size.height * 0.72f),
                radius = size.width * 0.85f
            )
        )
    }
}

@Composable
private fun CurrentLyricStrip(
    lyrics: Lyrics?,
    positionMs: Long,
    isLoading: Boolean,
    highlightMode: HighlightMode,
    ink: Color,
    inkSecondary: Color,
    accent: Color,
    onClick: () -> Unit
) {
    val lines = lyrics?.lines.orEmpty()
    val index = if (lines.isEmpty()) -1 else activeLineIndex(lines, positionMs)
    val activeLine = lines.getOrNull(index)
    val activeText = activeLine?.text?.takeIf { it.isNotBlank() }
        ?: lyrics?.plainLyrics?.lineSequence()?.firstOrNull { it.isNotBlank() }
    val shown = when {
        isLoading -> stringResource(R.string.lyrics_loading)
        activeText != null -> activeText
        else -> stringResource(R.string.lyrics_not_found)
    }
    val timed = activeLine != null && activeLine.text.isNotBlank()
    val nextLineStart = lines.getOrNull(index + 1)?.timeMs
    val words = if (timed && highlightMode != HighlightMode.LINE) {
        LyricsTiming.wordTimingsForLine(activeLine, nextLineStart)
    } else {
        emptyList()
    }
    val sweepTarget = when {
        !timed -> 0f
        highlightMode == HighlightMode.LINE || words.size < 2 -> 1f
        else -> LyricsTiming.wordProgress(words, positionMs)
    }
    val motion = LocalMotionPreferences.current
    val sweep = remember(activeLine?.timeMs) {
        Animatable(if (sweepTarget >= 1f) 1f else 0f)
    }
    LaunchedEffect(activeLine?.timeMs, sweepTarget, motion.reduceMotion) {
        if (motion.reduceMotion) {
            sweep.snapTo(sweepTarget)
        } else {
            sweep.animateTo(sweepTarget, tween(160, easing = LinearEasing))
        }
    }
    val reveal = if (motion.reduceMotion) sweepTarget else sweep.value
    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        AnimatedContent(
            targetState = shown,
            transitionSpec = {
                val ms = if (motion.reduceMotion) 0 else 300
                val enter = slideInVertically(tween(ms, easing = FastOutSlowInEasing)) { it / 2 } +
                    fadeIn(tween(if (motion.reduceMotion) 0 else 180)) +
                    scaleIn(tween(ms), initialScale = 0.97f)
                val exit = slideOutVertically(tween(if (motion.reduceMotion) 0 else 240)) { -it / 3 } +
                    fadeOut(tween(if (motion.reduceMotion) 0 else 140)) +
                    scaleOut(tween(if (motion.reduceMotion) 0 else 240), targetScale = 1.01f)
                ContentTransform(enter, exit, sizeTransform = SizeTransform(clip = false))
            },
            contentAlignment = Alignment.CenterStart,
            label = "currentLyric"
        ) { line ->
            Box(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = line,
                    style = textStyle,
                    color = when {
                        isLoading || activeText == null -> inkSecondary
                        timed -> ink.copy(alpha = 0.64f)
                        else -> ink
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (timed && !isLoading) {
                    Text(
                        text = line,
                        style = textStyle.copy(
                            shadow = Shadow(
                                color = accent.copy(alpha = 0.55f),
                                offset = Offset.Zero,
                                blurRadius = 14f
                            )
                        ),
                        color = accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .matchParentSize()
                            .drawWithContent {
                                clipRect(right = size.width * reveal) {
                                    this@drawWithContent.drawContent()
                                }
                            }
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerActionRow(
    lyricsOpen: Boolean,
    queueOpen: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    autoplayEnabled: Boolean,
    ink: Color,
    inkSecondary: Color,
    accent: Color,
    onToggleLyrics: () -> Unit,
    onToggleQueue: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleAutoplay: () -> Unit,
    onOpenOutput: () -> Unit,
    onOpenParty: () -> Unit,
    isPartyActive: Boolean
) {
    val repeatIcon = if (repeatMode == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat
    val motion = LocalMotionPreferences.current
    val slideMs = if (motion.reduceMotion) 0 else 240
    val contentMs = if (motion.reduceMotion) 0 else 180

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Both capsules share the wider, three-segment footprint so the glyphs
        // either side never move when the content changes width.
        val widest = 44.dp * 2 + 56.dp * 3 + 2.dp
        val edgeInset = ((maxWidth - widest) / 4).coerceAtLeast(0.dp)
        val toStart by animateFloatAsState(
            targetValue = if (queueOpen) 1f else 0f,
            animationSpec = tween(slideMs, easing = FastOutSlowInEasing),
            label = "playerBottomPillSlide",
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = edgeInset),
            contentAlignment = Alignment.CenterStart,
        ) {
            AnimatedVisibility(
                visible = !queueOpen,
                enter = fadeIn(tween(contentMs, delayMillis = if (motion.reduceMotion) 0 else 120)),
                exit = fadeOut(tween(if (motion.reduceMotion) 0 else 120)),
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                ActionGlyph(
                    icon = Icons.Default.Lyrics,
                    description = stringResource(R.string.player_lyrics),
                    tint = if (lyricsOpen) accent else inkSecondary,
                    background = ink,
                    onClick = onToggleLyrics,
                )
            }

            AnimatedContent(
                // Laid out across the row and placed by hand: centred at 0,
                // flush with the lyrics glyph at 1. Read only during placement,
                // so the slide does not recompose the player.
                modifier = Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minWidth = 0))
                    layout(constraints.maxWidth, placeable.height) {
                        val centred = (constraints.maxWidth - placeable.width) / 2f
                        placeable.place((centred * (1f - toStart)).roundToInt(), 0)
                    }
                },
                targetState = queueOpen,
                transitionSpec = {
                    ContentTransform(
                        fadeIn(tween(contentMs, delayMillis = if (motion.reduceMotion) 0 else 140)),
                        fadeOut(tween(if (motion.reduceMotion) 0 else 140)),
                        sizeTransform = SizeTransform(clip = false) { _, _ ->
                            tween(if (motion.reduceMotion) 0 else 220)
                        },
                    )
                },
                label = "playerBottomPill",
            ) { showQueueModes ->
                if (showQueueModes) {
                    Pill {
                        PillSegment(
                            icon = Icons.Default.Shuffle,
                            description = stringResource(R.string.player_shuffle),
                            tint = if (shuffleEnabled) accent else inkSecondary,
                            highlighted = shuffleEnabled,
                            onClick = onToggleShuffle,
                        )
                        PillDivider(ink)
                        PillSegment(
                            icon = repeatIcon,
                            description = stringResource(R.string.player_repeat),
                            tint = if (repeatMode != RepeatMode.OFF) accent else inkSecondary,
                            highlighted = repeatMode != RepeatMode.OFF,
                            onClick = onToggleRepeat,
                        )
                        PillDivider(ink)
                        PillSegment(
                            icon = Icons.Default.AllInclusive,
                            description = stringResource(R.string.queue_autoplay_compact),
                            tint = if (autoplayEnabled) accent else inkSecondary,
                            highlighted = autoplayEnabled,
                            onClick = onToggleAutoplay,
                        )
                    }
                } else {
                    Pill {
                        PillSegment(
                            icon = Icons.Default.Headphones,
                            description = stringResource(R.string.audio_output_title),
                            tint = inkSecondary,
                            onClick = onOpenOutput,
                        )
                        PillDivider(ink)
                        PillSegment(
                            icon = Icons.Default.Groups,
                            description = stringResource(R.string.player_party),
                            tint = if (isPartyActive) accent else inkSecondary,
                            highlighted = isPartyActive,
                            onClick = onOpenParty,
                        )
                    }
                }
            }

            Box(Modifier.align(Alignment.CenterEnd)) {
                ActionGlyph(
                    icon = Icons.AutoMirrored.Filled.QueueMusic,
                    description = stringResource(R.string.player_queue),
                    tint = if (queueOpen) accent else inkSecondary,
                    background = ink,
                    onClick = onToggleQueue,
                )
            }
        }
    }
}

@Composable
private fun ActionGlyph(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: Color,
    background: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(background.copy(alpha = 0.14f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(23.dp)
        )
    }
}

@Composable
private fun Pill(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .height(44.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f)),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
private fun PillDivider(ink: Color) {
    Box(
        modifier = Modifier
            .width(1.dp)
            .fillMaxHeight()
            .background(ink.copy(alpha = 0.18f))
    )
}

@Composable
private fun PillSegment(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: Color,
    highlighted: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(56.dp)
            .fillMaxHeight()
            .background(if (highlighted) Color.White.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun MicroControls(
    playbackState: PlaybackState,
    inkSecondary: Color,
    accent: Color,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit
) {
    val repeatIcon = when (playbackState.repeatMode) {
        RepeatMode.OFF -> Icons.Default.Repeat
        RepeatMode.ALL -> Icons.Default.Repeat
        RepeatMode.ONE -> Icons.Default.RepeatOne
    }
    val repeatActive = playbackState.repeatMode != RepeatMode.OFF

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onToggleShuffle, modifier = Modifier.size(42.dp)) {
            Icon(
                imageVector = Icons.Default.Shuffle,
                contentDescription = stringResource(R.string.player_shuffle),
                tint = if (playbackState.isShuffle) accent else inkSecondary,
                modifier = Modifier.size(21.dp)
            )
        }
        Spacer(modifier = Modifier.width(18.dp))
        IconButton(onClick = onToggleRepeat, modifier = Modifier.size(42.dp)) {
            Icon(
                imageVector = repeatIcon,
                contentDescription = stringResource(R.string.player_repeat),
                tint = if (repeatActive) accent else inkSecondary,
                modifier = Modifier.size(21.dp)
            )
        }
    }
}

@Composable
private fun UtilityBar(
    ink: Color,
    inkSecondary: Color,
    accent: Color,
    isLyricsOpen: Boolean,
    isPartyActive: Boolean,
    onOpenLyrics: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenParty: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        UtilityItem(
            icon = Icons.Default.Lyrics,
            label = stringResource(R.string.player_lyrics),
            tint = if (isLyricsOpen) accent else inkSecondary,
            onClick = onOpenLyrics
        )
        UtilityItem(
            icon = Icons.AutoMirrored.Filled.QueueMusic,
            label = stringResource(R.string.player_queue),
            tint = inkSecondary,
            onClick = onOpenQueue
        )
        UtilityItem(
            icon = Icons.Default.Groups,
            label = stringResource(R.string.player_party),
            tint = if (isPartyActive) accent else inkSecondary,
            onClick = onOpenParty
        )
    }
}

@Composable
private fun UtilityItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = tint,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

private fun formatPlayerTime(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}
