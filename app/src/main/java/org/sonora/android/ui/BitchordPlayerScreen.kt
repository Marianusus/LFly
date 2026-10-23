package org.sonora.android.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode as AnimationRepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import org.sonora.android.R
import org.sonora.android.api.HighlightMode
import org.sonora.android.api.Lyrics
import org.sonora.android.api.Track
import org.sonora.android.api.activeLineIndex
import org.sonora.android.api.lyrics.sources.LyricsProviderState
import org.sonora.android.api.lyrics.sources.LyricsSource
import org.sonora.android.playback.DownloadProgress
import org.sonora.android.playback.PlaybackState
import org.sonora.android.playback.RepeatMode
import org.sonora.android.ui.components.AudioOutputSheet
import org.sonora.android.ui.components.TrackActionsSheet
import org.sonora.android.ui.components.rememberAudioOutputLauncher
import org.sonora.android.ui.components.PlayerSharedKeys
import org.sonora.android.ui.components.QueueBottomSheetContent
import org.sonora.android.ui.components.TrackSourceActions
import org.sonora.android.ui.player.bitchord.BitMeshBackground
import org.sonora.android.ui.player.bitchord.rememberBitMesh
import org.sonora.android.ui.player.v2.rememberArtworkGradient
import org.sonora.android.ui.theme.LocalBackgroundScheme
import org.sonora.android.ui.theme.pressScale

private val PlayerGutter = 30.dp
private val PlayerMaxWidth = 560.dp
private val ArtTitleGap = 20.dp
private val HeaderHeight = 54.dp
private val BottomActionSize = 44.dp
private val PillWidth = 64.dp
private val SkipIcon = 53.dp
private val SkipTouch = 53.dp
private val PlayIcon = 74.dp
private val PlayTouch = 92.dp
private val ScrubIdle = 7.dp
private val ScrubActive = 12.dp

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun BitchordPlayerScreen(
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
    isPartyActive: Boolean = false,
    onArtistClick: (String) -> Unit = {},
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
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
) {
    val track = playbackState.currentTrack ?: return
    val scheme = LocalBackgroundScheme.current
    val mesh = rememberBitMesh(track.artworkUrl, scheme.accent)
    val gradient = rememberArtworkGradient(track.artworkUrl, scheme.accent)

    var menuOpen by remember { mutableStateOf(false) }
    var queueOpen by remember { mutableStateOf(false) }
    var audioOutputOpen by remember { mutableStateOf(false) }
    val openAudioOutput = rememberAudioOutputLauncher { audioOutputOpen = true }

    BackHandler(enabled = menuOpen) { menuOpen = false }
    BackHandler(enabled = audioOutputOpen) { audioOutputOpen = false }
    BackHandler(enabled = queueOpen) { queueOpen = false }

    val openLyrics: () -> Unit = {
        queueOpen = false
        onToggleLyrics()
    }
    val openQueue: () -> Unit = {
        if (isLyricsOpen) onToggleLyrics()
        queueOpen = true
    }

    SetLightStatusIcons()

    val ink = if (gradient.bottom.luminance() > 0.55f) Color(0xFF101012) else Color.White
    val inkSecondary = lerp(Color.White, gradient.top, 0.28f).copy(alpha = 0.72f)
    val accent = if (scheme.accent.luminance() < 0.12f) ink else scheme.accent

    val sharedSurface = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedBounds(
                sharedContentState = rememberSharedContentState(PlayerSharedKeys.SURFACE),
                animatedVisibilityScope = animatedVisibilityScope,
                enter = fadeIn(),
                exit = fadeOut(),
                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            )
        }
    } else Modifier

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .then(sharedSurface)
            .clipToBounds()
            .background(Color.Black)
    ) {
        val coverHeight = (maxHeight * 0.56f)
            .coerceAtMost(maxHeight - 360.dp)
            .coerceAtLeast(240.dp)

        BitMeshBackground(
            palette = mesh,
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(coverHeight)
                .align(Alignment.TopCenter)
        ) {
            BitHeroArtwork(
                track = track,
                previous = playbackState.queue.getOrNull(playbackState.currentIndex - 1),
                next = playbackState.queue.getOrNull(playbackState.currentIndex + 1),
                canPrevious = playbackState.hasPrevious,
                canNext = playbackState.hasNext,
                onPrevious = onPlayPrevious,
                onNext = onPlayNext,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.56f to Color.Transparent,
                            0.84f to Color.Black.copy(alpha = 0.18f),
                            1f to Color.Black.copy(alpha = 0.32f)
                        )
                    )
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(132.dp)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.46f),
                        0.55f to Color.Black.copy(alpha = 0.16f),
                        1f to Color.Transparent
                    )
                )
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .align(Alignment.TopCenter)
                .zIndex(3f)
        ) {
            PlayerTopBar(
                ink = ink,
                onCollapse = onCollapse
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(horizontal = PlayerGutter),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(coverHeight))

            BitCredits(
                track = track,
                isFavorite = isFavorite,
                ink = ink,
                inkSecondary = inkSecondary,
                accent = accent,
                onArtistClick = onArtistClick,
                onToggleFavorite = { onToggleFavorite(track) },
                onOpenMenu = { menuOpen = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = ArtTitleGap, bottom = 10.dp)
            )

            Spacer(Modifier.weight(1f))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to gradient.bottom.copy(alpha = 0.10f)
                        )
                    )
                    .padding(top = 8.dp, bottom = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BitCurrentLyric(
                    lyrics = lyrics,
                    positionMs = playbackState.positionMs,
                    isLoading = isLoadingLyrics,
                    inkSecondary = inkSecondary,
                    accent = accent,
                    onClick = openLyrics
                )
                Spacer(Modifier.height(12.dp))
                BitScrubber(
                    playbackState = playbackState,
                    ink = ink,
                    inkSecondary = inkSecondary,
                    accent = accent,
                    onSeekTo = onSeekTo
                )
                Spacer(Modifier.height(14.dp))
                BitTransport(
                    playbackState = playbackState,
                    ink = ink,
                    accent = accent,
                    onPlayPause = onPlayPause,
                    onPrevious = onPlayPrevious,
                    onNext = onPlayNext
                )
                Spacer(Modifier.height(20.dp))
                BitActionRow(
                    queueOpen = queueOpen,
                    shuffleEnabled = playbackState.isShuffle,
                    repeatMode = playbackState.repeatMode,
                    autoplayEnabled = autoplayEnabled,
                    ink = ink,
                    inkSecondary = inkSecondary,
                    accent = accent,
                    onToggleLyrics = openLyrics,
                    onToggleQueue = openQueue,
                    onToggleShuffle = onToggleShuffle,
                    onToggleRepeat = onToggleRepeat,
                    onToggleAutoplay = onToggleAutoplay,
                    onOpenOutput = openAudioOutput,
                    onOpenParty = onOpenParty,
                    isPartyActive = isPartyActive
                )
            }
        }

        if (menuOpen) {
            TrackActionsSheet(
                track = track,
                isFavorite = isFavorite,
                isDisliked = isDisliked,
                sourceActions = sourceActions,
                onDismissRequest = { menuOpen = false },
                onToggleFavorite = { onToggleFavorite(track) },
                onToggleDislike = { onToggleDislike(track) },
                onPlayNext = { onPlayNextTrack(track) },
                onAddToQueue = { onAddToQueue(track) },
                onAddToPlaylist = { onAddToPlaylist(track) },
                onShare = { onShareTrack(track) },
                onOpenEqualizer = onOpenEqualizer,
                onOpenArtist = { onArtistClick(track.artist) },
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

        if (queueOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(1.5f)
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.52f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { queueOpen = false }
                )
                androidx.compose.material3.Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.76f)
                        .align(Alignment.BottomCenter),
                    color = Color(0xFF151517),
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .navigationBarsPadding()
                    ) {
                        Column(Modifier.fillMaxSize()) {
                            Box(
                                Modifier
                                    .padding(top = 8.dp, bottom = 2.dp)
                                    .width(38.dp)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Color.White.copy(alpha = 0.22f))
                            )
                            QueueBottomSheetContent(
                                playbackState = playbackState,
                                onDismissRequest = { queueOpen = false },
                                onTrackClick = { index ->
                                    onQueueTrackClick(index)
                                    queueOpen = false
                                },
                                onRemoveTrack = onQueueRemove,
                                onClearQueue = onQueueClear,
                                onToggleAutoplay = onToggleAutoplay,
                                onMoveQueueItem = onQueueMove,
                                sourceActions = sourceActions,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = isLyricsOpen,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(tween(240)) + slideInVertically(tween(360, easing = FastOutSlowInEasing)) { it / 5 },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(240)) { it / 5 }
        ) {
            LyricsView(
                track = track,
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
private fun SetLightStatusIcons() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previous = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false
        onDispose {
            if (previous != null) controller.isAppearanceLightStatusBars = previous
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun BitHeroArtwork(
    track: Track,
    previous: Track?,
    next: Track?,
    canPrevious: Boolean,
    canNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    val haptics = LocalHapticFeedback.current
    val peek = when {
        offset.value > 0f -> previous
        offset.value < 0f -> next
        else -> null
    }

    LaunchedEffect(track.id) { offset.snapTo(0f) }

    Box(
        modifier = modifier
            .clipToBounds()
            .graphicsLayer { clip = true }
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(canPrevious, canNext, track.id) {
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
                                if (forward && canNext) onNext()
                                if (!forward && canPrevious) onPrevious()
                                offset.snapTo(0f)
                            } else {
                                offset.animateTo(0f, tween(180))
                            }
                        }
                    },
                    onDragCancel = { scope.launch { offset.animateTo(0f, tween(160)) } },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        val enabled = if (dragAmount < 0f) canNext else canPrevious
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
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = progress }
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = offset.value
                    scaleX = 1f + 0.018f * progress
                    scaleY = 1f + 0.018f * progress
                }
                .background(Color.Black),
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
private fun PlayerTopBar(ink: Color, onCollapse: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(HeaderHeight)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 6.dp)
                .width(38.dp)
                .height(5.dp)
                .clip(RoundedCornerShape(50))
                .background(ink.copy(alpha = 0.70f))
                .clickable(onClick = onCollapse)
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(40.dp)
                .clickable(onClick = onCollapse),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(R.string.player_collapse_desc),
                tint = ink.copy(alpha = 0.85f),
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

@Composable
private fun BitCredits(
    track: Track,
    isFavorite: Boolean,
    ink: Color,
    inkSecondary: Color,
    accent: Color,
    onArtistClick: (String) -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Crossfade(
            targetState = track.id,
            animationSpec = tween(300),
            modifier = Modifier.weight(1f),
            label = "bitCredits"
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 20.sp,
                        lineHeight = 25.sp,
                        letterSpacing = (-0.45).sp
                    ),
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 20.sp,
                        lineHeight = 25.sp,
                        letterSpacing = (-0.40).sp
                    ),
                    color = inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = { onArtistClick(track.artist) })
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        val scale by animateFloatAsState(
            targetValue = if (isFavorite) 1.10f else 1f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
            label = "bitHeart"
        )
        BitCircleGlyph(
            icon = painterResource(if (isFavorite) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline),
            description = stringResource(R.string.player_favorite_desc),
            tint = if (isFavorite) accent else ink.copy(alpha = 0.80f),
            active = isFavorite,
            onClick = onToggleFavorite,
            modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
        )
        Spacer(Modifier.width(8.dp))
        BitCircleGlyph(
            icon = painterResource(R.drawable.convx_more),
            description = stringResource(R.string.player_more_desc),
            tint = ink.copy(alpha = 0.80f),
            onClick = onOpenMenu
        )
    }
}

@Composable
private fun BitCircleGlyph(
    icon: androidx.compose.ui.graphics.painter.Painter,
    description: String,
    tint: Color,
    active: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (active) 0.30f else 0.16f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(19.dp)
        )
    }
}

@Composable
private fun BitCurrentLyric(
    lyrics: Lyrics?,
    positionMs: Long,
    isLoading: Boolean,
    inkSecondary: Color,
    accent: Color,
    onClick: () -> Unit
) {
    val lines = lyrics?.lines.orEmpty()
    val index = if (lines.isEmpty()) -1 else activeLineIndex(lines, positionMs)
    val active = lines.getOrNull(index)?.text?.takeIf { it.isNotBlank() }
    val text = when {
        isLoading -> stringResource(R.string.lyrics_loading)
        active != null -> active
        else -> stringResource(R.string.lyrics_not_found)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.CenterStart
    ) {
        Crossfade(
            targetState = text,
            animationSpec = tween(220),
            label = "bitLyric"
        ) { shown ->
            Text(
                text = shown,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    lineHeight = 22.sp
                ),
                color = if (isLoading || active == null) inkSecondary else accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun BitScrubber(
    playbackState: PlaybackState,
    ink: Color,
    inkSecondary: Color,
    accent: Color,
    onSeekTo: (Long) -> Unit
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val duration = playbackState.durationMs.coerceAtLeast(1L)
    val progress = if (dragging) dragValue else (playbackState.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    val shownMs = if (dragging) (dragValue * duration).toLong() else playbackState.positionMs
    val height by animateDpAsState(
        targetValue = if (dragging) ScrubActive else ScrubIdle,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "bitScrubHeight"
    )
    val sheen = rememberInfiniteTransition(label = "bitSheen")
    val sheenX by sheen.animateFloat(
        initialValue = -0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000), AnimationRepeatMode.Reverse),
        label = "bitSheenX"
    )

    Column(Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .pointerInput(duration) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        dragging = true
                        dragValue = (down.position.x / size.width).coerceIn(0f, 1f)
                        while (true) {
                            val event = awaitPointerEvent()
                            val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!pointer.pressed) {
                                pointer.consume()
                                break
                            }
                            if (pointer.positionChanged()) {
                                dragValue = (pointer.position.x / size.width).coerceIn(0f, 1f)
                                pointer.consume()
                            }
                        }
                        dragging = false
                        onSeekTo((dragValue * duration).toLong())
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(height)
            ) {
                val radius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f)
                drawRoundRect(color = ink.copy(alpha = 0.26f), cornerRadius = radius)
                val filled = size.width * progress.coerceIn(0f, 1f)
                if (filled > 0f) {
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.94f),
                        size = androidx.compose.ui.geometry.Size(
                            filled.coerceAtLeast(size.height),
                            size.height
                        ),
                        cornerRadius = radius
                    )
                }
                if (playbackState.isLoading) {
                    val band = size.width * 0.34f
                    val x = sheenX * size.width
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            0f to Color.Transparent,
                            0.5f to Color.White.copy(alpha = 0.72f),
                            1f to Color.Transparent,
                            startX = x,
                            endX = x + band
                        ),
                        size = androidx.compose.ui.geometry.Size(band, size.height),
                        topLeft = androidx.compose.ui.geometry.Offset(x, 0f),
                        cornerRadius = radius
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 1.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatBitTime(shownMs),
                style = MaterialTheme.typography.labelMedium,
                color = inkSecondary
            )
            playbackState.streamProvider?.takeIf { it.isNotBlank() }?.let { provider ->
                Text(
                    text = provider,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = accent.copy(alpha = 0.9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
            Text(
                text = "-" + formatBitTime((duration - shownMs).coerceAtLeast(0L)),
                style = MaterialTheme.typography.labelMedium,
                color = inkSecondary
            )
        }
    }
}

@Composable
private fun BitTransport(
    playbackState: PlaybackState,
    ink: Color,
    accent: Color,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        BitTransportGlyph(
            painter = painterResource(R.drawable.flamingo_rewind),
            description = stringResource(R.string.player_previous),
            size = SkipIcon,
            touch = SkipTouch,
            enabled = playbackState.hasPrevious,
            onClick = onPrevious
        )
        Box(Modifier.size(PlayTouch), contentAlignment = Alignment.Center) {
            if (playbackState.isLoading) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(38.dp)
                )
            } else {
                BitTransportGlyph(
                    painter = painterResource(
                        if (playbackState.isPlaying) R.drawable.flamingo_pause else R.drawable.flamingo_play
                    ),
                    description = stringResource(
                        if (playbackState.isPlaying) R.string.player_pause else R.string.player_play
                    ),
                    size = PlayIcon,
                    touch = PlayTouch,
                    enabled = true,
                    onClick = onPlayPause
                )
            }
        }
        BitTransportGlyph(
            painter = painterResource(R.drawable.flamingo_fforward),
            description = stringResource(R.string.player_next),
            size = SkipIcon,
            touch = SkipTouch,
            enabled = playbackState.hasNext,
            onClick = onNext
        )
    }
}

@Composable
private fun BitTransportGlyph(
    painter: androidx.compose.ui.graphics.painter.Painter,
    description: String,
    size: androidx.compose.ui.unit.Dp,
    touch: androidx.compose.ui.unit.Dp,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val alpha by animateFloatAsState(if (enabled) 1f else 0.3f, label = "bitTransportAlpha")
    Box(
        modifier = Modifier
            .size(touch)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painter,
            contentDescription = description,
            tint = Color.White.copy(alpha = alpha),
            modifier = Modifier
                .size(size)
                .graphicsLayer { scaleY = 0.85f }
        )
    }
}

@Composable
private fun BitActionRow(
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
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widest = BottomActionSize * 2 + PillWidth * 3 + 2.dp
        val edgeInset = ((maxWidth - widest) / 4).coerceAtLeast(0.dp)
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = edgeInset),
            contentAlignment = Alignment.CenterStart
        ) {
            BitBottomGlyph(
                icon = Icons.Default.Lyrics,
                description = stringResource(R.string.player_lyrics),
                highlighted = false,
                onClick = onToggleLyrics
            )
            Crossfade(
                targetState = queueOpen,
                animationSpec = tween(220),
                modifier = Modifier.align(Alignment.Center),
                label = "bitPill"
            ) { showQueue ->
                BitPill {
                    if (showQueue) {
                        BitPillSegment(
                            icon = Icons.Default.Shuffle,
                            description = stringResource(R.string.player_shuffle),
                            highlighted = shuffleEnabled,
                            onClick = onToggleShuffle
                        )
                        BitPillDivider()
                        BitPillSegment(
                            icon = if (repeatMode == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                            description = stringResource(R.string.player_repeat),
                            highlighted = repeatMode != RepeatMode.OFF,
                            onClick = onToggleRepeat
                        )
                        BitPillDivider()
                        BitPillSegment(
                            icon = null,
                            label = if (autoplayEnabled) "∞" else "∞",
                            description = stringResource(R.string.queue_autoplay_compact),
                            highlighted = autoplayEnabled,
                            onClick = onToggleAutoplay
                        )
                    } else {
                        BitPillSegment(
                            icon = Icons.Default.Headphones,
                            description = stringResource(R.string.audio_output_title),
                            highlighted = false,
                            onClick = onOpenOutput
                        )
                        BitPillDivider()
                        BitPillSegment(
                            icon = Icons.Default.Groups,
                            description = stringResource(R.string.player_party),
                            highlighted = isPartyActive,
                            onClick = onOpenParty
                        )
                    }
                }
            }
            Box(Modifier.align(Alignment.CenterEnd)) {
                BitBottomGlyph(
                    icon = Icons.AutoMirrored.Filled.QueueMusic,
                    description = stringResource(R.string.player_queue),
                    highlighted = queueOpen,
                    onClick = onToggleQueue
                )
            }
        }
    }
}

@Composable
private fun BitBottomGlyph(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    highlighted: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(BottomActionSize)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (highlighted) 0.28f else 0.12f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = Color.White.copy(alpha = if (highlighted) 1f else 0.78f),
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun BitPill(
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    Row(
        modifier = Modifier
            .height(BottomActionSize)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f)),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
private fun BitPillDivider() {
    Box(
        Modifier
            .width(1.dp)
            .fillMaxHeight()
            .background(Color.White.copy(alpha = 0.20f))
    )
}

@Composable
private fun BitPillSegment(
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    description: String,
    highlighted: Boolean,
    onClick: () -> Unit,
    label: String? = null
) {
    Box(
        modifier = Modifier
            .width(PillWidth)
            .height(BottomActionSize)
            .background(if (highlighted) Color.White.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) {
            Crossfade(targetState = icon, animationSpec = tween(200), label = "bitPillIcon") { shown ->
                Icon(
                    imageVector = shown,
                    contentDescription = description,
                    tint = Color.White.copy(alpha = if (highlighted) 1f else 0.75f),
                    modifier = Modifier.size(if (shown == Icons.Default.Headphones) 23.dp else 22.dp)
                )
            }
        } else if (label != null) {
            Text(
                text = label,
                color = Color.White.copy(alpha = if (highlighted) 1f else 0.75f),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private fun formatBitTime(millis: Long): String {
    val total = (millis / 1000).coerceAtLeast(0)
    val minutes = total / 60
    val seconds = total % 60
    return String.format("%d:%02d", minutes, seconds)
}
