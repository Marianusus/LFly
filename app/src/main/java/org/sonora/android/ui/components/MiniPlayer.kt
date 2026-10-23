package org.sonora.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.sonora.android.R
import org.sonora.android.api.Track
import org.sonora.android.ui.theme.LocalBackgroundScheme
import org.sonora.android.ui.theme.LocalMotionPreferences
import org.sonora.android.ui.theme.SonoraSurfaceHighlight
import org.sonora.android.ui.theme.SonoraTextPrimary
import org.sonora.android.ui.theme.SonoraTextSecondary
import org.sonora.android.ui.theme.SonoraTextTertiary

private val MiniPlayerShape = RoundedCornerShape(percent = 50)
private val MiniArtShape = RoundedCornerShape(8.dp)
private val MiniGlyphSlot = 40.dp
private val MiniGlyphSize = 32.dp
private val MiniNextGlyphSize = 25.dp
private val MiniSwipeThreshold = 72.dp
private val MiniPullEase = 72.dp
private const val MiniPullBias = 1.5f
private const val MiniPullRate = 0.5f

/**
 * The mini player's pull gesture and transport layout are modelled on BitChord
 * (GPL-3.0): a horizontal swipe skips, a vertical pull lifts the bar and hands
 * the player to the host, and a tap expands. Sonora keeps its favourite button,
 * progress bar, glass and shared-element morph.
 */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class, ExperimentalFoundationApi::class)
@Composable
fun MiniPlayer(
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
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onPullStart: () -> Unit = {},
    onPull: (Float) -> Unit = {},
    onPullRelease: (Float) -> Unit = {},
    sharedTransitionScope: androidx.compose.animation.SharedTransitionScope? = null,
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope? = null
) {
    val progress = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    val scheme = LocalBackgroundScheme.current
    val chrome = chromeSurface(scheme)
    val textAnchor = remember { TrackAnchor() }
    val contextMenu = LocalTrackContextMenu.current

    // The whole mini player expands into the full player: share the container bounds.
    val sharedSurfaceModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedBounds(
                sharedContentState = rememberSharedContentState(PlayerSharedKeys.SURFACE),
                animatedVisibilityScope = animatedVisibilityScope,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
                resizeMode = androidx.compose.animation.SharedTransitionScope.ResizeMode.RemeasureToBounds
            )
        }
    } else {
        Modifier
    }

    val haptics = LocalHapticFeedback.current
    val motion = LocalMotionPreferences.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(BottomChrome.MiniPlayerHeight)
            .then(sharedSurfaceModifier)
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
                reduceMotion = motion.reduceMotion
            )
            .clip(MiniPlayerShape)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = chrome.tint),
        shape = MiniPlayerShape,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, chrome.border),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Micro progress bar across the top of the pill.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(SonoraSurfaceHighlight)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = progress)
                        .height(2.dp)
                        .background(scheme.accent)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(MiniArtShape)
                        .background(SonoraSurfaceHighlight)
                        .border(1.dp, chrome.border.copy(alpha = 0.45f), MiniArtShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (!track.artworkUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = track.artworkUrl,
                            contentDescription = track.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(40.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = SonoraTextTertiary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .onGloballyPositioned { coordinates ->
                            if (coordinates.isAttached) textAnchor.rect = coordinates.boundsInWindow()
                        }
                        .combinedClickable(
                            onClick = onClick,
                            onLongClick = {
                                if (contextMenu != null && textAnchor.rect != Rect.Zero) {
                                    contextMenu.open(track, textAnchor.rect)
                                }
                            }
                        ),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        ),
                        color = SonoraTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    ArtistLinks(
                        track = track,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = SonoraTextSecondary
                    )
                }

                val favScale by animateFloatAsState(
                    targetValue = if (isFavorite) 1.2f else 1.0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    ),
                    label = "favScale"
                )
                val favColor by animateColorAsState(
                    targetValue = if (isFavorite) scheme.accent else SonoraTextSecondary,
                    animationSpec = spring(),
                    label = "favColor"
                )

                IconButton(
                    onClick = onFavoriteToggle,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = stringResource(R.string.mini_player_favorite),
                        tint = favColor,
                        modifier = Modifier
                            .size(20.dp)
                            .graphicsLayer {
                                scaleX = favScale
                                scaleY = favScale
                            }
                    )
                }

                Box(
                    modifier = Modifier.size(MiniGlyphSlot),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            color = scheme.accent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(22.dp)
                        )
                    } else {
                        IconButton(
                            onClick = onPlayPause,
                            modifier = Modifier.size(MiniGlyphSlot)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) {
                                    stringResource(R.string.mini_player_pause)
                                } else {
                                    stringResource(R.string.mini_player_play)
                                },
                                tint = SonoraTextPrimary,
                                modifier = Modifier.size(MiniGlyphSize)
                            )
                        }
                    }
                }

                IconButton(
                    onClick = onSkipNext,
                    modifier = Modifier.size(MiniGlyphSlot)
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = stringResource(R.string.mini_player_next),
                        tint = SonoraTextPrimary,
                        modifier = Modifier.size(MiniNextGlyphSize)
                    )
                }
            }
        }
    }
}

/**
 * Both mini-player drags in one detector: horizontal for previous/next and
 * vertical-up for the player pull. Axis is decided once, after touch slop, so
 * a rising thumb does not accidentally skip a track.
 */
@Composable
internal fun Modifier.miniPlayerGestures(
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onPullStart: () -> Unit,
    onPull: (Float) -> Unit,
    onPullRelease: (Float) -> Unit,
    reduceMotion: Boolean
): Modifier {
    val currentOnNext by rememberUpdatedState(onNext)
    val currentOnPrevious by rememberUpdatedState(onPrevious)
    val currentOnPullStart by rememberUpdatedState(onPullStart)
    val currentOnPull by rememberUpdatedState(onPull)
    val currentOnPullRelease by rememberUpdatedState(onPullRelease)
    val scope = rememberCoroutineScope()
    val lift = remember { mutableFloatStateOf(0f) }
    val settle = remember { arrayOfNulls<Job>(1) }

    return this
        .pointerInput(Unit) {
            val swipeThreshold = MiniSwipeThreshold.toPx()
            val liftEase = MiniPullEase.toPx()
            val maxVelocity = viewConfiguration.maximumFlingVelocity

            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val pointer = down.id

                var slopped: PointerInputChange? = null
                var vertical = false
                while (slopped == null) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == pointer }
                        ?: return@awaitEachGesture
                    if (!change.pressed || change.isConsumed) return@awaitEachGesture

                    val moved = change.position - down.position
                    if (moved.getDistance() < viewConfiguration.touchSlop) continue

                    vertical = moved.y < 0f && abs(moved.x) < -moved.y * MiniPullBias
                    if (!vertical && abs(moved.x) < abs(moved.y)) return@awaitEachGesture
                    change.consume()
                    slopped = change
                }

                val start = slopped ?: return@awaitEachGesture

                if (!vertical) {
                    var dragged = start.position.x - down.position.x
                    val finished = drag(pointer) { change ->
                        dragged += change.positionChange().x
                        change.consume()
                    }
                    if (!finished) return@awaitEachGesture
                    when {
                        dragged <= -swipeThreshold -> currentOnNext()
                        dragged >= swipeThreshold -> currentOnPrevious()
                    }
                    return@awaitEachGesture
                }

                settle[0]?.cancel()
                currentOnPullStart()
                val tracker = VelocityTracker()
                tracker.addPointerInputChange(down)
                tracker.addPointerInputChange(start)
                var pulled = down.position.y - start.position.y
                currentOnPull(pulled)
                lift.floatValue = miniPlayerPullLift(pulled, liftEase)

                val finished = drag(pointer) { change ->
                    val delta = change.positionChange().y
                    change.consume()
                    tracker.addPointerInputChange(change)
                    pulled -= delta
                    currentOnPull(pulled)
                    lift.floatValue = miniPlayerPullLift(pulled, liftEase)
                }
                currentOnPullRelease(
                    if (finished) {
                        tracker.calculateVelocity().y.coerceIn(-maxVelocity, maxVelocity)
                    } else {
                        0f
                    }
                )

                val from = lift.floatValue
                settle[0] = scope.launch {
                    if (reduceMotion) {
                        lift.floatValue = 0f
                    } else {
                        animate(
                            initialValue = from,
                            targetValue = 0f,
                            animationSpec = spring(
                                dampingRatio = 0.65f,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        ) { value, _ -> lift.floatValue = value }
                    }
                }
            }
        }
        .graphicsLayer { translationY = -lift.floatValue }
}

internal fun miniPlayerPullLift(pulled: Float, ease: Float): Float =
    if (pulled <= 0f || ease <= 0f) {
        0f
    } else {
        MiniPullRate * 2f * ease * (sqrt(1f + pulled / ease) - 1f)
    }
