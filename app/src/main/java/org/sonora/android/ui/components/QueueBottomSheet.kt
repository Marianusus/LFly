package org.sonora.android.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode as AnimationRepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import org.sonora.android.R
import org.sonora.android.api.Track
import org.sonora.android.playback.PlaybackState
import org.sonora.android.ui.theme.LocalBackgroundScheme
import org.sonora.android.ui.theme.SonoraTheme
import org.sonora.android.ui.theme.SonoraOpaqueSurfaceCard
import org.sonora.android.ui.theme.SonoraEmerald
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Animated vertical equalizer bars indicating active audio playback.
 */
@Composable
fun AnimatedPlayingBars(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    barColor: Color = SonoraEmerald
) {
    val infiniteTransition = rememberInfiniteTransition(label = "PlayingBarsTransition")

    val bar1Height by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 420, easing = LinearEasing),
            repeatMode = AnimationRepeatMode.Reverse
        ),
        label = "Bar1"
    )

    val bar2Height by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 320, easing = LinearEasing),
            repeatMode = AnimationRepeatMode.Reverse
        ),
        label = "Bar2"
    )

    val bar3Height by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 520, easing = LinearEasing),
            repeatMode = AnimationRepeatMode.Reverse
        ),
        label = "Bar3"
    )

    val bar4Height by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.80f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 380, easing = LinearEasing),
            repeatMode = AnimationRepeatMode.Reverse
        ),
        label = "Bar4"
    )

    Row(
        modifier = modifier
            .size(width = 22.dp, height = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        val bars = listOf(
            if (isPlaying) bar1Height else 0.35f,
            if (isPlaying) bar2Height else 0.80f,
            if (isPlaying) bar3Height else 0.55f,
            if (isPlaying) bar4Height else 0.25f
        )

        bars.forEach { fraction ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(fraction.coerceIn(0.15f, 1f))
                    .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                    .background(barColor)
            )
        }
    }
}

// ==========================================
// Pure helpers (unit-testable)
// ==========================================

/**
 * Builds a unique, stable key per queue entry so reordering keeps item identity.
 *
 * A plain track id is not enough because the same track can appear multiple times in a
 * queue. Occurrence counters disambiguate duplicates while remaining stable across reorders.
 */
fun stableTrackKeys(tracks: List<Track>): List<String> {
    val occurrences = HashMap<String, Int>()
    return tracks.map { track ->
        val seen = occurrences.getOrDefault(track.id, 0)
        occurrences[track.id] = seen + 1
        if (seen == 0) track.id else "${track.id}#$seen"
    }
}

/**
 * Maps a lazy-list reorder (identified by stable item keys) to absolute queue indices.
 *
 * Upcoming tracks live at `queue[upcomingBaseIndex ..]`, so the absolute index of an
 * upcoming row is `upcomingBaseIndex + localIndex`. Returns `null` when the move is a
 * no-op or one of the keys is unknown (e.g. the drag never reached a reorderable row).
 */
fun mapQueueReorderByKey(
    keys: List<String>,
    fromKey: Any?,
    toKey: Any?,
    upcomingBaseIndex: Int
): Pair<Int, Int>? {
    val from = keys.indexOfFirst { it == fromKey }
    val to = keys.indexOfFirst { it == toKey }
    if (from < 0 || to < 0 || from == to) return null
    return (upcomingBaseIndex + from) to (upcomingBaseIndex + to)
}

/**
 * Bottom sheet displaying the currently playing track with animated equalizer bars,
 * upcoming queue list with track art, title, artist, duration, long-press drag reordering,
 * clear queue action, and autoplay toggle. All surfaces derive from the dynamic theme.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueBottomSheet(
    playbackState: PlaybackState,
    isOpen: Boolean,
    onDismissRequest: () -> Unit,
    onTrackClick: (Int) -> Unit,
    onRemoveTrack: (Int) -> Unit,
    onClearQueue: () -> Unit,
    onToggleAutoplay: () -> Unit,
    onMoveQueueItem: ((from: Int, to: Int) -> Unit)? = null,
    sourceActions: TrackSourceActions? = null,
    modifier: Modifier = Modifier
) {
    if (!isOpen) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scheme = LocalBackgroundScheme.current
    val chrome = chromeSurface(scheme)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = chrome.tint,
        contentColor = scheme.onBackground,
        scrimColor = scheme.scrim,
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = scheme.onBackground.copy(alpha = 0.25f)
            )
        },
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = modifier
    ) {
        QueueBottomSheetContent(
            playbackState = playbackState,
            onDismissRequest = onDismissRequest,
            onTrackClick = onTrackClick,
            onRemoveTrack = onRemoveTrack,
            onClearQueue = onClearQueue,
            onToggleAutoplay = onToggleAutoplay,
            onMoveQueueItem = onMoveQueueItem,
            sourceActions = sourceActions
        )
    }
}

@Composable
fun QueueBottomSheetContent(
    playbackState: PlaybackState,
    onDismissRequest: () -> Unit,
    onTrackClick: (Int) -> Unit,
    onRemoveTrack: (Int) -> Unit,
    onClearQueue: () -> Unit,
    onToggleAutoplay: () -> Unit,
    onMoveQueueItem: ((from: Int, to: Int) -> Unit)?,
    sourceActions: TrackSourceActions? = null,
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current
    val accent = scheme.accent

    val currentTrack = playbackState.currentTrack
    val currentIndex = playbackState.currentIndex
    val fullQueue = playbackState.queue
    val userBoundary = playbackState.userQueueSize.coerceIn(0, fullQueue.size)

    val hasValidCurrent = currentIndex >= 0 && currentIndex < fullQueue.size
    val afterCurrent = if (hasValidCurrent) currentIndex + 1 else 0

    // User queue = tracks the user explicitly queued (region [0, userBoundary)), strictly
    // after the active track. Radio = autoplay additions (region [userBoundary, size)).
    val userUpcoming = fullQueue.subList(afterCurrent.coerceAtMost(userBoundary), userBoundary).toList()
    val radioStart = maxOf(afterCurrent, userBoundary)
    val radioUpcoming = fullQueue.subList(radioStart, fullQueue.size).toList()

    val userBaseIndex = afterCurrent.coerceAtMost(userBoundary)
    val userKeys = stableTrackKeys(userUpcoming)
    val radioKeys = stableTrackKeys(radioUpcoming)

    val lazyListState = rememberLazyListState()
    val canReorder = onMoveQueueItem != null && userUpcoming.size > 1

    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val mapped = mapQueueReorderByKey(userKeys, from.key, to.key, userBaseIndex)
        if (mapped != null) {
            onMoveQueueItem?.invoke(mapped.first, mapped.second)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
    ) {
        QueueSheetHeader(
            trackCount = userBoundary,
            radioCount = radioUpcoming.size,
            showClear = fullQueue.isNotEmpty(),
            onClearQueue = onClearQueue,
            onDismissRequest = onDismissRequest
        )

        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // CURRENT TRACK
            item(key = "queue_current") {
                if (currentTrack != null) {
                    NowPlayingQueueCard(
                        track = currentTrack,
                        isPlaying = playbackState.isPlaying
                    )
                } else {
                    QueuePlaceholder(text = stringResource(R.string.queue_no_track_playing))
                }
            }

            // AUTOPLAY
            item(key = "queue_autoplay") {
                AutoplayCard(
                    enabled = playbackState.isAutoplayEnabled,
                    onToggle = onToggleAutoplay
                )
            }

            // USER QUEUE HEADER
            item(key = "queue_up_next_header") {
                Column {
                    Spacer(modifier = Modifier.height(4.dp))
                    HorizontalDivider(color = scheme.glassBorder, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.queue_up_next_header, userUpcoming.size),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        ),
                        color = scheme.onBackgroundSecondary
                    )
                }
            }

            if (userUpcoming.isEmpty()) {
                item(key = "queue_empty") {
                    QueueEmptyState()
                }
            } else {
                itemsIndexed(
                    items = userUpcoming,
                    key = { index, _ -> userKeys[index] }
                ) { localIndex, track ->
                    val actualIndex = userBaseIndex + localIndex
                    ReorderableItem(
                        state = reorderState,
                        key = userKeys[localIndex],
                        enabled = canReorder
                    ) { isDragging ->
                        UpcomingQueueItem(
                            track = track,
                            isCurrent = actualIndex == currentIndex,
                            isDragging = isDragging,
                            reorderEnabled = canReorder,
                            dragHandleModifier = if (canReorder) {
                                Modifier.longPressDraggableHandle(enabled = true)
                            } else {
                                Modifier
                            },
                            onTrackClick = { onTrackClick(actualIndex) },
                            onPlayNow = { onTrackClick(actualIndex) },
                            onRemove = { onRemoveTrack(actualIndex) },
                            sourceActions = sourceActions
                        )
                    }
                }
            }

            // RADIO / AUTOPLAY (autoplay additions, not part of the user's queue)
            if (radioUpcoming.isNotEmpty()) {
                item(key = "queue_radio_header") {
                    Column {
                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = scheme.glassBorder, thickness = 1.dp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.queue_radio_header, radioUpcoming.size),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            ),
                            color = scheme.onBackgroundSecondary
                        )
                    }
                }
                itemsIndexed(
                    items = radioUpcoming,
                    key = { index, _ -> radioKeys[index] }
                ) { localIndex, track ->
                    val actualIndex = radioStart + localIndex
                    UpcomingQueueItem(
                        track = track,
                        isCurrent = false,
                        isDragging = false,
                        reorderEnabled = false,
                        dragHandleModifier = Modifier,
                        onTrackClick = { onTrackClick(actualIndex) },
                        onPlayNow = { onTrackClick(actualIndex) },
                        onRemove = { onRemoveTrack(actualIndex) },
                        sourceActions = sourceActions
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueSheetHeader(
    trackCount: Int,
    radioCount: Int,
    showClear: Boolean,
    onClearQueue: () -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = null,
                tint = scheme.accent,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = stringResource(R.string.queue_now_playing),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    ),
                    color = scheme.onBackground
                )
                Text(
                    text = buildString {
                        append(pluralStringResource(R.plurals.queue_track_count, trackCount, trackCount))
                        if (radioCount > 0) append(stringResource(R.string.queue_radio_suffix, radioCount))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onBackgroundSecondary
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showClear) {
                TextButton(
                    onClick = onClearQueue,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.ClearAll,
                        contentDescription = stringResource(R.string.queue_clear_content_description),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.queue_clear),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
            }

            IconButton(
                onClick = onDismissRequest,
                modifier = Modifier
                    .size(36.dp)
                    .background(scheme.glassTint, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.queue_close_content_description),
                    tint = scheme.onBackground,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * Now Playing Track Row with animated equalizer indicator, highlighted with the dynamic accent.
 */
@Composable
private fun NowPlayingQueueCard(
    track: Track,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current
    val accent = scheme.accent
    val accentFillAlpha = if (scheme.isLight) 0.14f else 0.18f

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = accentFillAlpha)),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TrackArtwork(
                track = track,
                size = 56.dp,
                cornerRadius = 12.dp,
                placeholderBackground = scheme.glassBorder
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    ),
                    color = accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = track.subtitle(),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    color = scheme.onBackgroundSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            AnimatedPlayingBars(
                isPlaying = isPlaying,
                barColor = accent
            )

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = track.formattedDuration,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                color = scheme.onBackgroundSecondary
            )
        }
    }
}

@Composable
private fun AutoplayCard(
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current
    val accent = scheme.accent

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = scheme.glassTint),
        border = BorderStroke(1.dp, scheme.glassBorder),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(accent.copy(alpha = 0.16f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = stringResource(R.string.queue_autoplay_title),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        ),
                        color = scheme.onBackground
                    )
                    Text(
                        text = stringResource(R.string.queue_autoplay_subtitle),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = scheme.onBackgroundSecondary
                    )
                }
            }

            Switch(
                checked = enabled,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = accent,
                    checkedTrackColor = accent.copy(alpha = 0.40f),
                    uncheckedThumbColor = scheme.onBackgroundSecondary,
                    uncheckedTrackColor = scheme.glassBorder
                )
            )
        }
    }
}

/**
 * Upcoming track item with album art, title, artist, duration, overflow menu and drag handle.
 */
@Composable
private fun UpcomingQueueItem(
    track: Track,
    isCurrent: Boolean,
    isDragging: Boolean,
    reorderEnabled: Boolean,
    dragHandleModifier: Modifier,
    onTrackClick: () -> Unit,
    onPlayNow: () -> Unit,
    onRemove: () -> Unit,
    sourceActions: TrackSourceActions? = null,
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current
    val accent = scheme.accent

    val container = when {
        isDragging -> scheme.glassBorder
        isCurrent -> accent.copy(alpha = if (scheme.isLight) 0.12f else 0.16f)
        else -> Color.Transparent
    }
    val titleColor = if (isCurrent) accent else scheme.onBackground

    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .clickable(onClick = onTrackClick)
            .padding(start = 6.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TrackArtwork(
            track = track,
            size = 46.dp,
            cornerRadius = 8.dp,
            placeholderBackground = scheme.glassBorder
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                ),
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = track.subtitle(),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = scheme.onBackgroundSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Text(
            text = track.formattedDuration,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
            color = scheme.onBackgroundTertiary
        )

        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.queue_track_options_content_description),
                    tint = scheme.onBackgroundSecondary,
                    modifier = Modifier.size(20.dp)
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
                    text = { Text(stringResource(R.string.queue_play_now)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = scheme.accent
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onPlayNow()
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.queue_remove_from_queue)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onRemove()
                    }
                )
                if (sourceActions != null &&
                    (track.id.startsWith("yt_") || track.id.startsWith("spotify_")) &&
                    !track.provider.equals("Local", ignoreCase = true)
                ) {
                    SourceSwitchMenuItems(
                        sourceOverride = sourceActions.overrideFor(track.id),
                        onPlayViaSource = { src ->
                            menuExpanded = false
                            sourceActions.onSelect(track, src)
                        },
                        onUseGlobalSource = {
                            menuExpanded = false
                            sourceActions.onClear(track)
                        }
                    )
                }
            }
        }

        if (reorderEnabled) {
            Box(
                modifier = dragHandleModifier
                    .size(36.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.DragHandle,
                    contentDescription = stringResource(R.string.queue_drag_to_reorder_content_description),
                    tint = scheme.onBackgroundTertiary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun QueueEmptyState(
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 36.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = null,
                tint = scheme.onBackgroundTertiary,
                modifier = Modifier.size(44.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.queue_empty_title),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = scheme.onBackground
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.queue_empty_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onBackgroundSecondary
            )
        }
    }
}

@Composable
private fun QueuePlaceholder(
    text: String,
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = scheme.glassTint),
        border = BorderStroke(1.dp, scheme.glassBorder),
        shape = RoundedCornerShape(12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = scheme.onBackgroundTertiary,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun TrackArtwork(
    track: Track,
    size: androidx.compose.ui.unit.Dp,
    cornerRadius: androidx.compose.ui.unit.Dp,
    placeholderBackground: Color,
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(placeholderBackground),
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
                modifier = Modifier.size(size / 2)
            )
        }
    }
}

private fun Track.subtitle(): String = buildString {
    append(artist)
    if (!album.isNullOrBlank() && album != title) {
        append(" • $album")
    }
}
