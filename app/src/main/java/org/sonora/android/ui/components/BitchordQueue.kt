/*
 * Queue presentation adapted from BitChord
 * https://github.com/kushagrasinghx/BitChord (GPL-3.0).
 */
package org.sonora.android.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
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
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

internal data class QueuePanelEntry(
    val index: Int,
    val track: Track,
    val key: String
)

internal data class QueuePanelSections(
    val current: QueuePanelEntry?,
    val upcoming: List<QueuePanelEntry>,
    val radio: List<QueuePanelEntry>
) {
    companion object {
        val Empty = QueuePanelSections(null, emptyList(), emptyList())
    }
}

internal fun buildQueuePanelSections(
    queue: List<Track>,
    currentIndex: Int,
    userQueueSize: Int
): QueuePanelSections {
    if (currentIndex !in queue.indices) return QueuePanelSections.Empty
    val boundary = userQueueSize.coerceIn(0, queue.size)
    val start = currentIndex + 1
    val userEnd = boundary.coerceAtLeast(start)
    val userTracks = queue.subList(start, userEnd)
    val radioStart = maxOf(start, boundary)
    val radioTracks = queue.subList(radioStart, queue.size)

    val current = QueuePanelEntry(
        index = currentIndex,
        track = queue[currentIndex],
        key = "np_${queue[currentIndex].id}"
    )
    val userKeys = stableTrackKeys(userTracks)
    val radioKeys = stableTrackKeys(radioTracks)

    return QueuePanelSections(
        current = current,
        upcoming = userTracks.mapIndexed { local, track ->
            QueuePanelEntry(
                index = start + local,
                track = track,
                key = "u_${userKeys[local]}"
            )
        },
        radio = radioTracks.mapIndexed { local, track ->
            QueuePanelEntry(
                index = radioStart + local,
                track = track,
                key = "r_${radioKeys[local]}"
            )
        }
    )
}

private val QueueFade = 28.dp

private fun Modifier.queueFadingEdges(): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        val fade = QueueFade.toPx()
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color.Black),
                startY = 0f,
                endY = fade
            ),
            blendMode = BlendMode.DstIn
        )
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Black, Color.Transparent),
                startY = size.height - fade,
                endY = size.height
            ),
            blendMode = BlendMode.DstIn
        )
    }

@Composable
fun BitchordQueueList(
    playbackState: PlaybackState,
    onTrackClick: (Int) -> Unit,
    onRemoveTrack: (Int) -> Unit,
    onClearUpcoming: () -> Unit,
    onToggleAutoplay: () -> Unit,
    onMoveQueueItem: ((from: Int, to: Int) -> Unit)? = null,
    sourceActions: TrackSourceActions? = null,
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current
    val accent = scheme.accent
    val sections = remember(playbackState.queue, playbackState.currentIndex, playbackState.userQueueSize) {
        buildQueuePanelSections(
            queue = playbackState.queue,
            currentIndex = playbackState.currentIndex,
            userQueueSize = playbackState.userQueueSize
        )
    }
    val listState = rememberLazyListState()
    val canReorder = onMoveQueueItem != null && sections.upcoming.size > 1
    val canReorderRadio = onMoveQueueItem != null && sections.radio.size > 1
    val userKeys = remember(sections.upcoming) { sections.upcoming.map { it.key } }
    val radioKeys = remember(sections.radio) { sections.radio.map { it.key } }
    LaunchedEffect(sections.current?.key) {
        if (sections.current != null) listState.animateScrollToItem(0)
    }
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromKey = from.key
        val toKey = to.key
        val mapped = when {
            userKeys.contains(fromKey) && userKeys.contains(toKey) ->
                mapQueueReorderByKey(
                    keys = userKeys,
                    fromKey = fromKey,
                    toKey = toKey,
                    upcomingBaseIndex = sections.upcoming.firstOrNull()?.index ?: 0
                )
            radioKeys.contains(fromKey) && radioKeys.contains(toKey) ->
                mapQueueReorderByKey(
                    keys = radioKeys,
                    fromKey = fromKey,
                    toKey = toKey,
                    upcomingBaseIndex = sections.radio.firstOrNull()?.index ?: 0
                )
            else -> null
        }
        if (mapped != null) onMoveQueueItem?.invoke(mapped.first, mapped.second)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent()
                }
            }
    ) {
        QueueListHeader(
            count = sections.upcoming.size + sections.radio.size,
            canClear = sections.upcoming.isNotEmpty() || sections.radio.isNotEmpty(),
            onClear = onClearUpcoming
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .queueFadingEdges()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp)
            ) {
                sections.current?.let { current ->
                    item(key = "queue_current_heading") {
                        QueuePanelHeading(stringResource(R.string.queue_now_playing))
                    }
                    item(key = current.key) {
                        QueuePanelRow(
                            entry = current,
                            isCurrent = true,
                            canRemove = false,
                            canReorder = false,
                            dragHandleModifier = Modifier,
                            onPlay = { onTrackClick(current.index) },
                            onRemove = {},
                            sourceActions = sourceActions
                        )
                    }
                }

                item(key = "queue_upcoming_heading") {
                    QueuePanelHeading(
                        text = stringResource(R.string.queue_up_next_header, sections.upcoming.size),
                        modifier = Modifier.padding(top = 18.dp)
                    )
                }

                if (sections.upcoming.isEmpty()) {
                    item(key = "queue_upcoming_empty") {
                        QueuePanelEmpty(
                            title = stringResource(R.string.queue_empty_title),
                            subtitle = stringResource(R.string.queue_empty_subtitle)
                        )
                    }
                } else {
                    items(
                        items = sections.upcoming,
                        key = { it.key }
                    ) { entry ->
                        ReorderableItem(
                            state = reorderState,
                            key = entry.key,
                            modifier = Modifier.animateItem(),
                            enabled = canReorder
                        ) { isDragging ->
                            QueuePanelRow(
                                entry = entry,
                                isCurrent = false,
                                canRemove = true,
                                canReorder = canReorder,
                                isDragging = isDragging,
                                dragHandleModifier = if (canReorder) {
                                    Modifier.longPressDraggableHandle(enabled = true)
                                } else {
                                    Modifier
                                },
                                onPlay = { onTrackClick(entry.index) },
                                onRemove = { onRemoveTrack(entry.index) },
                                sourceActions = sourceActions
                            )
                        }
                    }
                }

                item(key = "queue_autoplay_heading") {
                    QueueAutoplayHeading(
                        enabled = playbackState.isAutoplayEnabled,
                        count = sections.radio.size,
                        onToggle = onToggleAutoplay,
                        modifier = Modifier.padding(top = 20.dp)
                    )
                }

                if (sections.radio.isEmpty()) {
                    item(key = "queue_radio_empty") {
                        QueuePanelEmpty(
                            title = stringResource(R.string.queue_radio_empty_title),
                            subtitle = stringResource(R.string.queue_radio_empty_subtitle)
                        )
                    }
                } else {
                    items(
                        items = sections.radio,
                        key = { it.key }
                    ) { entry ->
                        ReorderableItem(
                            state = reorderState,
                            key = entry.key,
                            modifier = Modifier.animateItem(),
                            enabled = canReorderRadio
                        ) { isDragging ->
                            QueuePanelRow(
                                entry = entry,
                                isCurrent = false,
                                canRemove = true,
                                canReorder = canReorderRadio,
                                isDragging = isDragging,
                                dragHandleModifier = if (canReorderRadio) {
                                    Modifier.longPressDraggableHandle(enabled = true)
                                } else {
                                    Modifier
                                },
                                onPlay = { onTrackClick(entry.index) },
                                onRemove = { onRemoveTrack(entry.index) },
                                sourceActions = sourceActions
                            )
                        }
                    }
                }

                item(key = "queue_bottom_space") {
                    Spacer(Modifier.height(22.dp))
                }
            }
        }
    }
}

@Composable
private fun QueueListHeader(
    count: Int,
    canClear: Boolean,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.queue_title),
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.4).sp
                ),
                color = Color.White
            )
            Text(
                text = pluralStringResource(R.plurals.queue_track_count, count, count),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = Color.White.copy(alpha = 0.55f)
            )
        }
        if (canClear) {
            TextButton(
                onClick = onClear,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Color.White.copy(alpha = 0.62f)
                )
            ) {
                Text(
                    text = stringResource(R.string.queue_clear),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun QueuePanelHeading(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp
        ),
        color = Color.White.copy(alpha = 0.72f),
        modifier = modifier.padding(bottom = 6.dp)
    )
}

@Composable
private fun QueueAutoplayHeading(
    enabled: Boolean,
    count: Int,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.AllInclusive,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.72f),
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.queue_radio_header, count),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                ),
                color = Color.White.copy(alpha = 0.72f)
            )
            Text(
                text = stringResource(R.string.queue_autoplay_subtitle),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                color = Color.White.copy(alpha = 0.45f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color.White.copy(alpha = 0.48f),
                uncheckedThumbColor = Color.White.copy(alpha = 0.62f),
                uncheckedTrackColor = Color.White.copy(alpha = 0.14f)
            )
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QueuePanelRow(
    entry: QueuePanelEntry,
    isCurrent: Boolean,
    canRemove: Boolean,
    canReorder: Boolean,
    dragHandleModifier: Modifier,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    sourceActions: TrackSourceActions? = null,
    isDragging: Boolean = false
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val anchor = remember { TrackAnchor() }
    val contextMenu = LocalTrackContextMenu.current
    val playNowLabel = stringResource(R.string.queue_play_now)
    val removeLabel = stringResource(R.string.queue_remove_from_queue)
    val removeTint = MaterialTheme.colorScheme.error
    val background = when {
        isDragging -> Color.White.copy(alpha = 0.09f)
        isCurrent -> Color.White.copy(alpha = 0.06f)
        else -> Color.Transparent
    }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(background)
                .onGloballyPositioned { coordinates ->
                    if (coordinates.isAttached) anchor.rect = coordinates.boundsInWindow()
                }
                .combinedClickable(
                    onClick = onPlay,
                    onLongClick = {
                        if (canRemove && contextMenu != null && anchor.rect != Rect.Zero) {
                            contextMenu.open(
                                track = entry.track,
                                anchor = anchor.rect,
                                extraGroups = listOf(HeldTrackMenuGroup(listOf(
                                    HeldTrackAction(Icons.Default.PlayArrow, playNowLabel) { onPlay() },
                                    HeldTrackAction(Icons.Default.DeleteOutline, removeLabel, removeTint) { onRemove() }
                                )))
                            )
                        } else if (canRemove) {
                            menuExpanded = true
                        }
                    }
                )
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (canReorder) {
                Box(
                    modifier = dragHandleModifier.size(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.DragHandle,
                        contentDescription = stringResource(R.string.queue_drag_to_reorder_content_description),
                        tint = Color.White.copy(alpha = 0.38f),
                        modifier = Modifier.size(19.dp)
                    )
                }
                Spacer(Modifier.width(4.dp))
            }

            QueuePanelArtwork(track = entry.track)

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.track.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                        fontSize = 16.sp,
                        letterSpacing = (-0.2).sp
                    ),
                    color = if (isCurrent) Color.White else Color.White.copy(alpha = 0.92f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = entry.track.artist,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = Color.White.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (isCurrent) {
                Icon(
                    imageVector = Icons.Default.GraphicEq,
                    contentDescription = stringResource(R.string.queue_now_playing),
                    tint = Color.White,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(18.dp)
                )
            }

            if (canRemove) {
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.queue_remove_from_queue),
                        tint = Color.White.copy(alpha = 0.55f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            modifier = Modifier.width(280.dp),
            shape = RoundedCornerShape(20.dp),
            containerColor = Color(0xF21A1A1D),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
            shadowElevation = 16.dp
        ) {
            DownloadMenuEntry(
                track = entry.track,
                onDismiss = { menuExpanded = false },
                accent = LocalBackgroundScheme.current.accent,
                primary = Color.White,
                secondary = Color.White.copy(alpha = 0.62f)
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.queue_play_now), color = Color.White) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.62f)
                    )
                },
                onClick = {
                    menuExpanded = false
                    onPlay()
                }
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.07f))
            DropdownMenuItem(
                text = { Text(stringResource(R.string.queue_remove_from_queue), color = Color.White) },
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
                (entry.track.id.startsWith("yt_") || entry.track.id.startsWith("spotify_")) &&
                !entry.track.provider.equals("Local", ignoreCase = true)
            ) {
                SourceSwitchMenuItems(
                    sourceOverride = sourceActions.overrideFor(entry.track.id),
                    onPlayViaSource = { source ->
                        menuExpanded = false
                        sourceActions.onSelect(entry.track, source)
                    },
                    onUseGlobalSource = {
                        menuExpanded = false
                        sourceActions.onClear(entry.track)
                    }
                )
            }
        }
    }
}

@Composable
private fun QueuePanelArtwork(track: Track) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White.copy(alpha = 0.08f)),
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
                tint = Color.White.copy(alpha = 0.45f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun QueuePanelEmpty(
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                ),
                color = Color.White.copy(alpha = 0.86f)
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = Color.White.copy(alpha = 0.48f)
            )
        }
    }
}
