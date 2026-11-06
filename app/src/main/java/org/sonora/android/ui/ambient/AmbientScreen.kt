package org.sonora.android.ui.ambient

import android.content.Context
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import org.sonora.android.R
import org.sonora.android.api.HighlightMode
import org.sonora.android.api.LyricLine
import org.sonora.android.api.Lyrics
import org.sonora.android.api.LyricsTiming
import org.sonora.android.api.activeLineIndex
import org.sonora.android.playback.PlaybackState
import org.sonora.android.ui.theme.LocalBackgroundScheme
import org.sonora.android.ui.theme.sonoraMotion

private val AmbientBlack = Color(0xFF000000)
private val AmbientInk = Color(0xFFF5F5F7)
private val AmbientInkSecondary = Color(0xFFC7C7CC)
private val AmbientProgressTrack = Color.White.copy(alpha = 0.24f)
private val AmbientCoverPlaceholder = Color(0xFF1C1C1E)

private val ProgressLineHeight = 3.dp
private val ProgressLineGap = 12.dp

private const val BURN_IN_EASE_MS = 1_500
private const val CONTROLS_FADE_MS = 200
private const val MINUTE_MS = 60_000L
private const val CLOCK_WIDEST_TEXT = "00:00"

/** 3:1 on the darkest part of the scrim over a white cover. */
private const val MIN_LYRIC_ACCENT_LUMINANCE = 0.29f

private val ClockStyle = TextStyle(
    color = AmbientInk,
    fontSize = 92.sp,
    fontWeight = FontWeight.Medium,
    fontFeatureSettings = "tnum"
)
private val TitleStyle = TextStyle(color = AmbientInk, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
private val ArtistStyle = TextStyle(color = AmbientInkSecondary, fontSize = 18.sp, fontWeight = FontWeight.Normal)
private val LyricStyle = TextStyle(color = AmbientInk, fontSize = 20.sp, fontWeight = FontWeight.Medium)

/** StandBy-style bedside clock for the full player: the time and the song, on true black. */
@Composable
internal fun AmbientScreen(
    playbackState: PlaybackState,
    lyrics: Lyrics?,
    highlightMode: HighlightMode,
    onPlayPause: () -> Unit,
    onPlayNext: () -> Unit,
    onPlayPrevious: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val motion = sonoraMotion()
    val track = playbackState.currentTrack

    // Position changes constantly: children read it through these, never as a parameter.
    val latestState = rememberUpdatedState(playbackState)
    val readPositionMs = remember { { latestState.value.positionMs } }
    val readProgress = remember { { latestState.value.progress } }

    var controlsVisible by remember { mutableStateOf(false) }
    var controlsTouches by remember { mutableIntStateOf(0) }
    val controlsTimeoutMs = LocalAccessibilityManager.current?.calculateRecommendedTimeoutMillis(
        AMBIENT_CONTROLS_TIMEOUT_MS,
        containsIcons = true,
        containsControls = true
    ) ?: AMBIENT_CONTROLS_TIMEOUT_MS
    LaunchedEffect(controlsVisible, controlsTouches, controlsTimeoutMs) {
        if (controlsVisible) {
            delay(controlsTimeoutMs)
            controlsVisible = false
        }
    }
    val controlsAlpha = animateFloatAsState(
        targetValue = if (controlsVisible) 1f else 0f,
        animationSpec = tween(if (motion.reduceMotion) 0 else CONTROLS_FADE_MS),
        label = "ambientControls"
    )
    val controlsPresent by remember { derivedStateOf { controlsAlpha.value > 0f } }
    val toggleControls = remember { { controlsVisible = !controlsVisible } }
    val touchControl: (() -> Unit) -> Unit = { action ->
        controlsTouches++
        action()
    }

    val burnIn = rememberBurnInOffset(reduceMotion = motion.reduceMotion)

    BackHandler(onBack = onClose)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AmbientBlack)
            .swallowTouches(onTap = toggleControls)
            .semantics {
                onClick {
                    toggleControls()
                    true
                }
            }
    ) {
        AmbientHeroBackground(track?.artworkUrl)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .offset { burnIn.value }
        ) {
            Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .weight(0.44f)
                        .fillMaxHeight()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // A tap brings the controls up over the cover, so the lyric beside it stays readable.
                    Box(modifier = Modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
                        AmbientCover(track?.artworkUrl)
                        if (controlsPresent) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer { alpha = controlsAlpha.value }
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(Color.Black.copy(alpha = 0.46f))
                            )
                            TransportControls(
                                isPlaying = playbackState.isPlaying,
                                onPrevious = { touchControl(onPlayPrevious) },
                                onPlayPause = { touchControl(onPlayPause) },
                                onNext = { touchControl(onPlayNext) },
                                modifier = Modifier.graphicsLayer { alpha = controlsAlpha.value }
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(0.56f)
                        .fillMaxHeight()
                        .padding(top = 16.dp, end = 32.dp, bottom = 16.dp)
                ) {
                    NowPlayingColumn(
                        title = track?.title.orEmpty(),
                        artist = track?.artist.orEmpty(),
                        lyricLines = lyrics?.lines.orEmpty(),
                        highlightMode = highlightMode,
                        readPositionMs = readPositionMs,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = ProgressLineHeight + ProgressLineGap)
                    )
                    ProgressLine(
                        readProgress = readProgress,
                        modifier = Modifier.align(Alignment.BottomStart)
                    )
                }
            }
            if (controlsPresent) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(48.dp)
                        .graphicsLayer { alpha = controlsAlpha.value }
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.action_close),
                        tint = AmbientInk
                    )
                }
            }
        }
    }
}

/**
 * A plain clickable lets drags through to gesture handlers on ancestors, so the whole gesture is
 * consumed here. Buttons on top consume their own down first and never reach this.
 */
private fun Modifier.swallowTouches(onTap: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown()
        down.consume()
        var moved = false
        do {
            val event = awaitPointerEvent()
            event.changes.forEach { change ->
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                    moved = true
                }
                change.consume()
            }
        } while (event.changes.any { it.pressed })
        if (!moved) onTap()
    }
}

/** Keeps walking under Reduce Motion, as a jump, because it protects the panel. */
@Composable
private fun rememberBurnInOffset(reduceMotion: Boolean): State<IntOffset> {
    val lifecycleOwner = LocalLifecycleOwner.current
    var step by remember { mutableLongStateOf(0L) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(AMBIENT_BURN_IN_STEP_MS)
                step++
            }
        }
    }
    val (xDp, yDp) = ambientBurnInOffset(step)
    val target = with(LocalDensity.current) { IntOffset(xDp.dp.roundToPx(), yDp.dp.roundToPx()) }
    return animateIntOffsetAsState(
        targetValue = target,
        animationSpec = if (reduceMotion) snap() else tween(BURN_IN_EASE_MS, easing = FastOutSlowInEasing),
        label = "ambientBurnIn"
    )
}

@Composable
private fun AmbientCover(artworkUrl: String?) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(20.dp))
            .background(AmbientCoverPlaceholder),
        contentAlignment = Alignment.Center
    ) {
        if (artworkUrl.isNullOrBlank()) {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                tint = AmbientInkSecondary,
                modifier = Modifier.size(96.dp)
            )
        } else {
            AsyncImage(
                model = artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun NowPlayingColumn(
    title: String,
    artist: String,
    lyricLines: List<LyricLine>,
    highlightMode: HighlightMode,
    readPositionMs: () -> Long,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start
    ) {
        // Weighted children measure last, so the clock absorbs whatever height the text leaves.
        ClockText(modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.height(8.dp))
        Text(text = title, style = TitleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(text = artist, style = ArtistStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (lyricLines.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            ActiveLyric(
                lines = lyricLines,
                highlightMode = highlightMode,
                readPositionMs = readPositionMs,
            )
        }
    }
}

@Composable
private fun ClockText(modifier: Modifier = Modifier) {
    val time = rememberClockText()
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier = modifier) {
        val fitted = remember(measurer, constraints) {
            val full = measurer.measure(CLOCK_WIDEST_TEXT, ClockStyle, maxLines = 1, softWrap = false).size
            val scale = minOf(
                1f,
                constraints.maxWidth.toFloat() / full.width,
                constraints.maxHeight.toFloat() / full.height
            )
            ClockStyle.copy(fontSize = ClockStyle.fontSize * scale)
        }
        Text(text = time, style = fitted, maxLines = 1, softWrap = false)
    }
}

/**
 * Stopping with the lifecycle also resyncs on wake: `delay` does not count deep sleep, so a wait
 * armed before the screen went off would otherwise leave a stale time on return.
 */
@Composable
private fun rememberClockText(): String {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var text by remember { mutableStateOf(formatClock(context)) }
    LaunchedEffect(context, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                text = formatClock(context)
                delay(MINUTE_MS - System.currentTimeMillis() % MINUTE_MS)
            }
        }
    }
    return text
}

private fun formatClock(context: Context): String {
    val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
    return DateFormat.format(pattern, System.currentTimeMillis()).toString()
}

/**
 * The current line animates as in the full player: it slides in, and the accent fills it word by
 * word. Two lines are always reserved so the column does not jump.
 */
@Composable
private fun ActiveLyric(
    lines: List<LyricLine>,
    highlightMode: HighlightMode,
    readPositionMs: () -> Long,
    modifier: Modifier = Modifier
) {
    val motion = sonoraMotion()
    val schemeAccent = LocalBackgroundScheme.current.accent
    val accent = remember(schemeAccent) {
        schemeAccent.takeIf { it.luminance() >= MIN_LYRIC_ACCENT_LUMINANCE } ?: AmbientInk
    }
    val index by remember(lines) { derivedStateOf { activeLineIndex(lines, readPositionMs()) } }
    val activeLine = lines.getOrNull(index)
    val nextLineStart = lines.getOrNull(index + 1)?.timeMs
    val shown = activeLine?.text.orEmpty()
    val timed = shown.isNotBlank()
    val words = remember(activeLine, nextLineStart, highlightMode) {
        if (activeLine != null && timed && highlightMode != HighlightMode.LINE) {
            LyricsTiming.wordTimingsForLine(activeLine, nextLineStart)
        } else {
            emptyList()
        }
    }
    val sweepTarget by remember(timed, words) {
        derivedStateOf {
            when {
                !timed -> 0f
                words.size < 2 -> 1f
                else -> LyricsTiming.wordProgress(words, readPositionMs())
            }
        }
    }
    val sweep = remember(activeLine?.timeMs) { Animatable(if (sweepTarget >= 1f) 1f else 0f) }
    LaunchedEffect(activeLine?.timeMs, sweepTarget, motion.reduceMotion) {
        if (motion.reduceMotion) {
            sweep.snapTo(sweepTarget)
        } else {
            sweep.animateTo(sweepTarget, tween(160, easing = LinearEasing))
        }
    }

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
        label = "ambientLyric",
        modifier = modifier
    ) { line ->
        LyricSweepText(line = line, accent = accent, readReveal = { sweep.value })
    }
}

/** The player's sweep runs along one line; here it continues from the first row to the second. */
@Composable
private fun LyricSweepText(line: String, accent: Color, readReveal: () -> Float) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Box(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = line,
            style = LyricStyle,
            color = AmbientInk.copy(alpha = 0.64f),
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layout = it }
        )
        if (line.isNotBlank()) {
            Text(
                text = line,
                style = LyricStyle.copy(
                    shadow = Shadow(
                        color = accent.copy(alpha = 0.55f),
                        offset = Offset.Zero,
                        blurRadius = 14f
                    )
                ),
                color = accent,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .matchParentSize()
                    .drawWithContent {
                        val text = layout ?: return@drawWithContent
                        val widths = List(text.lineCount) { text.getLineRight(it) - text.getLineLeft(it) }
                        lyricRevealRows(widths, readReveal()).forEachIndexed { row, shown ->
                            if (shown > 0f) {
                                clipRect(
                                    top = text.getLineTop(row),
                                    right = text.getLineLeft(row) + widths[row] * shown,
                                    bottom = text.getLineBottom(row)
                                ) {
                                    this@drawWithContent.drawContent()
                                }
                            }
                        }
                    }
            )
        }
    }
}

private const val AMBIENT_BLUR_FALLBACK_PX = 24

/**
 * The song fills the screen, as in the full player. The scrim keeps the text side readable on any
 * cover: on a pure white one the ink stays above 8:1 and the secondary ink above 5:1.
 */
@Composable
private fun AmbientHeroBackground(artworkUrl: String?) {
    if (org.sonora.android.ui.theme.LocalAppearanceMode.current == org.sonora.android.ui.theme.AppearanceMode.DYNAMIC_COVER) {
        // The Dynamic cover look: the same moving artwork as the app, under the readability scrim.
        org.sonora.android.ui.theme.DynamicCoverBackdrop(artworkUrl, veil = false)
    } else if (!artworkUrl.isNullOrBlank()) {
        val context = LocalContext.current
        // Modifier.blur does nothing before Android 12: a tiny image scaled up gives the same wash.
        val model: Any = remember(artworkUrl) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                artworkUrl
            } else {
                ImageRequest.Builder(context).data(artworkUrl).size(AMBIENT_BLUR_FALLBACK_PX).build()
            }
        }
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(64.dp)
        )
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0.30f),
                    0.40f to Color.Black.copy(alpha = 0.72f),
                    1f to Color.Black.copy(alpha = 0.72f)
                )
            )
    )
}

@Composable
private fun ProgressLine(
    readProgress: () -> Float,
    modifier: Modifier = Modifier
) {
    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .height(ProgressLineHeight)
            .drawBehind {
                drawRect(AmbientProgressTrack)
                drawRect(AmbientInk, size = Size(size.width * readProgress(), size.height))
            }
    )
}

@Composable
private fun TransportControls(
    isPlaying: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TransportButton(R.drawable.flamingo_rewind, stringResource(R.string.player_previous), onPrevious)
        TransportButton(
            icon = if (isPlaying) R.drawable.flamingo_pause else R.drawable.flamingo_play,
            description = stringResource(if (isPlaying) R.string.player_pause else R.string.player_play),
            onClick = onPlayPause
        )
        TransportButton(R.drawable.flamingo_fforward, stringResource(R.string.player_next), onNext)
    }
}

@Composable
private fun TransportButton(@DrawableRes icon: Int, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(56.dp)) {
        Icon(
            painter = painterResource(icon),
            contentDescription = description,
            tint = AmbientInk,
            modifier = Modifier.size(44.dp)
        )
    }
}
