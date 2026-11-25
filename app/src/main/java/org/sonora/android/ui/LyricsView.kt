package org.sonora.android.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import org.sonora.android.R
import org.sonora.android.api.HighlightMode
import org.sonora.android.api.LyricLine
import org.sonora.android.api.Lyrics
import org.sonora.android.api.LyricsTiming
import org.sonora.android.api.Track
import org.sonora.android.api.lyrics.sources.LyricsProviderState
import org.sonora.android.api.lyrics.sources.LyricsSource
import org.sonora.android.api.activeLineIndex
import org.sonora.android.playback.PlaybackState
import org.sonora.android.ui.components.LyricsProviderSheet
import org.sonora.android.ui.theme.LocalBackgroundScheme
import org.sonora.android.ui.theme.LocalMotionPreferences
import org.sonora.android.ui.theme.SonoraEmerald
import org.sonora.android.ui.theme.SonoraTextPrimary
import org.sonora.android.ui.theme.SonoraTextSecondary
import org.sonora.android.ui.theme.SonoraTextTertiary
import org.sonora.android.ui.theme.SonoraOpaqueSurfaceCard
import org.sonora.android.ui.theme.SonoraTheme
import android.os.Build
import android.os.SystemClock
import kotlin.math.abs

private const val LYRICS_ANCHOR_FRACTION = 0.33f
private const val FOLLOW_RESUME_DELAY_MS = 3_000L

@Composable
fun LyricsView(
    track: Track?,
    lyrics: Lyrics?,
    isLoading: Boolean,
    playbackState: PlaybackState,
    translateLyrics: Boolean,
    targetLang: String,
    highlightMode: HighlightMode,
    onToggleTranslate: (Boolean) -> Unit,
    onTargetLangChange: (String) -> Unit,
    onHighlightModeChange: (HighlightMode) -> Unit = {},
    musixmatchEnabled: Boolean = true,
    onToggleMusixmatch: (Boolean) -> Unit = {},
    lrclibEnabled: Boolean = true,
    onToggleLrclib: (Boolean) -> Unit = {},
    activeSource: LyricsSource? = null,
    providerStates: Map<LyricsSource, LyricsProviderState> = emptyMap(),
    onSelectLyricsSource: (LyricsSource?) -> Unit = {},
    onSeekTo: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onPlayPrevious: () -> Unit,
    onPlayNext: () -> Unit,
    onToggleShuffle: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current
    // Frame-rate live position: the player only reports every 250ms, which makes karaoke land
    // late/jittery. Advance smoothly from the last authoritative sample.
    val livePositionMs = rememberLivePositionMs(playbackState.positionMs, playbackState.isPlaying)

    var languageDialogOpen by remember { mutableStateOf(false) }
    var sourceSheetOpen by remember { mutableStateOf(false) }
    if (sourceSheetOpen) {
        LyricsProviderSheet(
            states = providerStates,
            active = activeSource,
            onSelect = { source ->
                sourceSheetOpen = false
                onSelectLyricsSource(source)
            },
            onDismiss = { sourceSheetOpen = false },
        )
    }
    if (languageDialogOpen) {
        TranslationLanguageDialog(
            current = targetLang,
            onDismiss = { languageDialogOpen = false },
            onSelect = { code ->
                languageDialogOpen = false
                onTargetLangChange(code)
            }
        )
    }

    // Convx / Apple Music lyric surface: full-bleed blurred artwork with a dark scrim.
    val activeLineColor = Color.White
    val inactiveLineColor = Color.White.copy(alpha = 0.62f)
    val futureLineColor = Color.White.copy(alpha = 0.44f)
    val shimmerColor = Color.White.copy(alpha = 0.20f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {} // Intercept click gestures so they don't leak to player controls beneath
            )
    ) {
        // Spicy Lyrics' background: the cover turning slowly, saturated and darkened.
        SpicyBackground(track?.artworkUrl, reduceMotion = LocalMotionPreferences.current.reduceMotion)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // ── Header: collapse + "COMING UP NEXT" + next track ────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(42.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.lyrics_close_desc),
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.lyrics_now_reproducing),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.4.sp
                        ),
                        color = Color.White.copy(alpha = 0.60f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = track?.let {
                            stringResource(R.string.lyrics_track_artist_title, it.artist, it.title)
                        } ?: "",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.size(42.dp))
            }

            // ── Body ───────────────────────────────────────────────────────────────────────────
            Box(modifier = Modifier.weight(1f)) {
                when {
                    isLoading -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(
                                    color = SonoraEmerald,
                                    modifier = Modifier.size(44.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = stringResource(R.string.lyrics_loading),
                                    color = SonoraTextSecondary,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }

                    lyrics == null || (lyrics.lines.isEmpty() && lyrics.plainLyrics.isNullOrBlank()) -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(horizontal = 32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = SonoraTextTertiary,
                                    modifier = Modifier.size(56.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = stringResource(R.string.lyrics_not_found),
                                    color = SonoraTextSecondary,
                                    style = MaterialTheme.typography.titleMedium,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.lyrics_not_found_hint),
                                    color = SonoraTextTertiary,
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    lyrics.hasSynced -> {
                        SyncedLyricsList(
                            lyrics = lyrics,
                            positionMs = livePositionMs,
                            durationMs = playbackState.durationMs,
                            highlightMode = highlightMode,
                            activeColor = activeLineColor,
                            accentColor = scheme.accent,
                            pastColor = inactiveLineColor,
                            futureColor = futureLineColor,
                            onSeekTo = onSeekTo
                        )
                    }

                    else -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 20.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = lyrics.plainLyrics ?: "",
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    color = Color.White,
                                    lineHeight = 34.sp,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            )
                        }
                    }
                }
            }

            // ── Footer: thin seek bar + time + controls ────────────────────────────────────────
            LyricsFooter(
                playbackState = playbackState,
                accentColor = Color.White,
                shimmerColor = shimmerColor,
                translateLyrics = translateLyrics,
                targetLang = targetLang,
                highlightMode = highlightMode,
                onToggleTranslate = onToggleTranslate,
                onOpenLanguagePicker = { languageDialogOpen = true },
                onHighlightModeChange = onHighlightModeChange,
                musixmatchEnabled = musixmatchEnabled,
                onToggleMusixmatch = onToggleMusixmatch,
                lrclibEnabled = lrclibEnabled,
                onToggleLrclib = onToggleLrclib,
                activeSource = activeSource,
                onOpenSourcePicker = { sourceSheetOpen = true },
                onSeekTo = onSeekTo,
                onPlayPause = onPlayPause,
                onPlayPrevious = onPlayPrevious,
                onPlayNext = onPlayNext,
                onToggleShuffle = onToggleShuffle
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────────────────────
// Synced list with word-level (or line-level) karaoke highlighting
// ──────────────────────────────────────────────────────────────────────────────────────────────

/**
 * A frame-rate playback position seeded from the player's 250ms samples. Without this the
 * karaoke highlight lands up to 250ms late and visibly steps.
 */
@Composable
private fun rememberLivePositionMs(authoritativeMs: Long, isPlaying: Boolean): Long {
    var anchorPos by remember { mutableLongStateOf(authoritativeMs) }
    var anchorAt by remember { mutableLongStateOf(System.nanoTime()) }
    var live by remember { mutableLongStateOf(authoritativeMs) }

    LaunchedEffect(authoritativeMs) {
        anchorPos = authoritativeMs
        anchorAt = System.nanoTime()
        live = authoritativeMs
    }
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            live = anchorPos + (System.nanoTime() - anchorAt) / 1_000_000L
        }
    }
    return live
}

@Composable
private fun SyncedLyricsList(
    lyrics: Lyrics,
    positionMs: Long,
    durationMs: Long,
    highlightMode: HighlightMode,
    activeColor: Color,
    accentColor: Color,
    pastColor: Color,
    futureColor: Color,
    onSeekTo: (Long) -> Unit
) {
    val lines = lyrics.lines
    val activeIndex = remember(positionMs, lines) { activeLineIndex(lines, positionMs) }
    val listState = rememberLazyListState()

    // Pause auto-follow while the user is dragging (and briefly after) so the scroll
    // never fights the finger. Resumes on the next line change.
    var followPausedUntil by remember { mutableLongStateOf(0L) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> followPausedUntil = Long.MAX_VALUE
                is DragInteraction.Stop, is DragInteraction.Cancel ->
                    followPausedUntil = SystemClock.elapsedRealtime() + FOLLOW_RESUME_DELAY_MS
                else -> Unit
            }
        }
    }

    // Keep the active line anchored at a fixed fraction of the viewport (a bit above center),
    // with a single short animated nudge per line change.
    LaunchedEffect(activeIndex) {
        if (activeIndex < 0) return@LaunchedEffect
        if (SystemClock.elapsedRealtime() < followPausedUntil) return@LaunchedEffect

        val viewport = listState.layoutInfo.viewportSize.height
        if (viewport <= 0) {
            listState.scrollToItem(activeIndex)
            return@LaunchedEffect
        }

        var item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeIndex }
        if (item == null) {
            // Not composed yet (seek/fast jump): bring it in, then measure for real.
            listState.animateScrollToItem(activeIndex)
            item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeIndex }
        }

        if (item != null) {
            // Item offsets share the content origin, which is shifted by the (non-zero)
            // beforeContentPadding: the visible viewport starts at [viewportStartOffset],
            // not at 0. Anchoring must be measured from there.
            val viewportStart = listState.layoutInfo.viewportStartOffset
            val desiredTop = viewportStart + viewport * LYRICS_ANCHOR_FRACTION - item.size / 2f
            val delta = item.offset - desiredTop
            if (abs(delta) > 2f) {
                listState.animateScrollBy(delta, animationSpec = tween(durationMillis = 220))
            }
        }
    }

    val motion = LocalMotionPreferences.current
    val introMs = lines.firstOrNull()?.timeMs ?: 0L
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().spicyFadeEdges(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 180.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            // The intro: dots until the first line, when it comes after a few seconds.
            if (introMs > 3_000L) {
                item(key = "intro") {
                    SpicyInterlude(0L, introMs, positionMs, active = activeIndex < 0, reduceMotion = motion.reduceMotion)
                }
            }
            itemsIndexed(lines, key = { index, line -> "$index-${line.timeMs}" }) { index, line ->
                val isActive = index == activeIndex
                val isPast = index < activeIndex
                val nextLineStartMs = lines.getOrNull(index + 1)?.timeMs
                val state = when {
                    isActive -> SpicyState.Active
                    isPast -> SpicyState.Sung
                    else -> SpicyState.NotSung
                }
                val alpha by animateFloatAsState(
                    targetValue = when (state) {
                        SpicyState.Active -> 1f
                        SpicyState.Sung -> Spicy.SUNG_LINE
                        SpicyState.NotSung -> Spicy.NEXT_LINE
                    },
                    animationSpec = tween(durationMillis = 260),
                    label = "lyricsAlpha"
                )
                val distance = if (activeIndex < 0) index + 1 else abs(index - activeIndex)
                val blurTarget = if (Build.VERSION.SDK_INT >= 31) Spicy.blur(distance) else 0.dp
                val blurRadius by androidx.compose.animation.core.animateDpAsState(
                    targetValue = blurTarget,
                    animationSpec = tween(durationMillis = 300),
                    label = "lyricsBlur"
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            // A line beyond the track's length (lyrics from a longer edit) must not
                            // seek past the end and trip the "ended"/restart path.
                            val lastSafe = if (durationMs > 0L) (durationMs - 500L).coerceAtLeast(0L) else line.timeMs
                            onSeekTo(line.timeMs.coerceIn(0L, lastSafe))
                        }
                        .padding(vertical = 2.dp)
                        .graphicsLayer { this.alpha = alpha }
                        .blur(blurRadius)
                ) {
                    if (line.text.isBlank()) {
                        // An instrumental pause: Spicy's breathing dots.
                        SpicyInterlude(
                            startMs = line.timeMs,
                            endMs = nextLineStartMs ?: (line.timeMs + 4_000L),
                            positionMs = positionMs,
                            active = isActive,
                            reduceMotion = motion.reduceMotion
                        )
                        return@Column
                    }
                    val lineStyle = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 30.sp,
                        lineHeight = 40.sp
                    )
                    // Word by word only when the source really has word timings; otherwise the
                    // line sweeps as a whole. HighlightMode.WORD still estimates the words.
                    val hasRealWords = !line.wordTimings.isNullOrEmpty()
                    val useWords = when (highlightMode) {
                        HighlightMode.WORD -> true
                        HighlightMode.AUTO -> hasRealWords
                        HighlightMode.LINE -> false
                    }
                    val timed = remember(line, nextLineStartMs) { LyricsTiming.wordTimingsForLine(line, nextLineStartMs) }
                    val tokens = remember(line.text) { LyricsTiming.splitWords(line.text) }
                    val words = if (useWords && timed.size == tokens.size) timed else emptyList()
                    SpicyLine(
                        text = line.text,
                        words = words,
                        lineStartMs = line.timeMs,
                        lineEndMs = timed.lastOrNull()?.endMs ?: (nextLineStartMs ?: (line.timeMs + 4_000L)),
                        positionMs = positionMs,
                        state = state,
                        style = lineStyle,
                        reduceMotion = motion.reduceMotion,
                    )

                    val background = line.background?.takeIf { it.isNotBlank() }
                    if (background != null) {
                        Text(
                            text = background,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 20.sp,
                                lineHeight = 28.sp
                            ),
                            color = Color.White.copy(alpha = if (isActive) 0.78f else 0.5f),
                            modifier = Modifier.padding(top = 4.dp),
                            textAlign = TextAlign.Start
                        )
                    }

                    val translated = line.translation?.takeIf { it.isNotBlank() }
                    if (translated != null) {
                        Text(
                            text = translated,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Normal,
                                fontSize = 15.sp,
                                lineHeight = 22.sp
                            ),
                            color = Color.White.copy(alpha = 0.65f),
                            modifier = Modifier.padding(top = 3.dp),
                            textAlign = TextAlign.Start
                        )
                    }
                }
            }
        }

    }
}

// ──────────────────────────────────────────────────────────────────────────────────────────────
// Footer
// ──────────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun LyricsFooter(
    playbackState: PlaybackState,
    accentColor: Color,
    shimmerColor: Color,
    translateLyrics: Boolean,
    targetLang: String,
    highlightMode: HighlightMode,
    onToggleTranslate: (Boolean) -> Unit,
    onOpenLanguagePicker: () -> Unit,
    onHighlightModeChange: (HighlightMode) -> Unit,
    musixmatchEnabled: Boolean,
    onToggleMusixmatch: (Boolean) -> Unit,
    lrclibEnabled: Boolean,
    onToggleLrclib: (Boolean) -> Unit,
    activeSource: LyricsSource?,
    onOpenSourcePicker: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onPlayPrevious: () -> Unit,
    onPlayNext: () -> Unit,
    onToggleShuffle: () -> Unit
) {
    var dragFraction by remember { mutableFloatStateOf(-1f) }
    var menuExpanded by remember { mutableStateOf(false) }

    val durationMs = playbackState.durationMs.coerceAtLeast(1L)
    val liveFraction = if (playbackState.durationMs > 0) {
        (playbackState.positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val shownFraction = if (dragFraction >= 0f) dragFraction else liveFraction
    val displayPositionMs =
        if (dragFraction >= 0f) (dragFraction * durationMs).toLong() else playbackState.positionMs

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
    ) {
        // Thin custom seek bar with a draggable thumb dot (matches the reference design)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        onSeekTo((fraction * durationMs).toLong())
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragFraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            if (dragFraction >= 0f) onSeekTo((dragFraction * durationMs).toLong())
                            dragFraction = -1f
                        },
                        onDragCancel = { dragFraction = -1f },
                        onHorizontalDrag = { change, _ ->
                            dragFraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                        }
                    )
                }
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(shimmerColor)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth(shownFraction)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(accentColor)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatLyricsTime(displayPositionMs),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.65f)
            )
            Text(
                text = "-" + formatLyricsTime((durationMs - displayPositionMs).coerceAtLeast(0L)),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.65f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onToggleShuffle, modifier = Modifier.size(44.dp)) {
                Icon(
                    imageVector = Icons.Default.Shuffle,
                    contentDescription = stringResource(R.string.lyrics_shuffle_desc),
                    tint = if (playbackState.isShuffle) Color.White else Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.size(24.dp)
                )
            }

            IconButton(onClick = onPlayPrevious, modifier = Modifier.size(48.dp)) {
                Icon(
                    painter = painterResource(R.drawable.flamingo_rewind),
                    contentDescription = stringResource(R.string.lyrics_previous_desc),
                    tint = Color.White,
                    modifier = Modifier.size(34.dp)
                )
            }

            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onPlayPause
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(
                        if (playbackState.isPlaying) R.drawable.flamingo_pause else R.drawable.flamingo_play
                    ),
                    contentDescription = stringResource(
                        if (playbackState.isPlaying) R.string.lyrics_pause_desc else R.string.lyrics_play_desc
                    ),
                    tint = SonoraTextPrimary,
                    modifier = Modifier.size(44.dp)
                )
            }

            IconButton(onClick = onPlayNext, modifier = Modifier.size(48.dp)) {
                Icon(
                    painter = painterResource(R.drawable.flamingo_fforward),
                    contentDescription = stringResource(R.string.lyrics_next_desc),
                    tint = Color.White,
                    modifier = Modifier.size(34.dp)
                )
            }

            Box {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { menuExpanded = true }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.convx_more),
                        contentDescription = stringResource(R.string.lyrics_options_desc),
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    shape = RoundedCornerShape(16.dp),
                    containerColor = SonoraOpaqueSurfaceCard,
                    border = androidx.compose.foundation.BorderStroke(1.dp, SonoraTheme.colors.cardBorder)
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(
                                    if (translateLyrics) R.string.lyrics_translate_on else R.string.lyrics_translate_off
                                ),
                                color = SonoraTextPrimary
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Translate,
                                contentDescription = null,
                                tint = if (translateLyrics) accentColor else SonoraTextSecondary
                            )
                        },
                        trailingIcon = {
                            if (translateLyrics) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = accentColor
                                )
                            }
                        },
                        onClick = {
                            menuExpanded = false
                            onToggleTranslate(!translateLyrics)
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(
                                    R.string.lyrics_language_format,
                                    translationLanguageLabel(targetLang)
                                ),
                                color = SonoraTextPrimary
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Translate,
                                contentDescription = null,
                                tint = accentColor
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onOpenLanguagePicker()
                        }
                    )
                    HorizontalDivider(color = SonoraTheme.colors.cardBorder)
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(
                                    R.string.lyrics_source_format,
                                    activeSource?.label ?: stringResource(R.string.lyrics_source_automatic),
                                ),
                                color = SonoraTextPrimary
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                tint = SonoraTextSecondary
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onOpenSourcePicker()
                        }
                    )
                    HorizontalDivider(color = SonoraTheme.colors.cardBorder)
                    listOf(
                        HighlightMode.AUTO to stringResource(R.string.lyrics_highlight_auto),
                        HighlightMode.WORD to stringResource(R.string.lyrics_highlight_word),
                        HighlightMode.LINE to stringResource(R.string.lyrics_highlight_line)
                    ).forEach { (mode, label) ->
                        DropdownMenuItem(
                            text = { Text(label, color = SonoraTextPrimary) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = null,
                                    tint = if (mode == highlightMode) accentColor else SonoraTextSecondary
                                )
                            },
                            trailingIcon = {
                                if (mode == highlightMode) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = accentColor
                                    )
                                }
                            },
                            onClick = {
                                menuExpanded = false
                                onHighlightModeChange(mode)
                            }
                        )
                    }
                }
            }
        }

        // Lift the controls a bit above the system navigation bar.
        Spacer(modifier = Modifier.height(18.dp))
    }
}

private fun formatLyricsTime(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}
