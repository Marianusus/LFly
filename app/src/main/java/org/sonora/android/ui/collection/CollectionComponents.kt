package org.sonora.android.ui.collection

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.sonora.android.R
import org.sonora.android.api.Track
import org.sonora.android.playback.DownloadProgress
import org.sonora.android.ui.components.AnimatedPlayingBars
import org.sonora.android.ui.components.ArtistLinks
import org.sonora.android.ui.components.DownloadIconButton
import org.sonora.android.ui.components.HeldTrackMenuGroup
import org.sonora.android.ui.components.LocalTrackContextMenu
import org.sonora.android.ui.components.TrackAnchor
import org.sonora.android.ui.components.TrackThumb
import org.sonora.android.ui.components.chromeSurface
import org.sonora.android.ui.components.shimmer
import org.sonora.android.ui.theme.LocalBackgroundScheme
import org.sonora.android.ui.theme.LocalMotionPreferences
import org.sonora.android.ui.theme.Radius
import org.sonora.android.ui.theme.SonoraOpaqueSurfaceCard
import org.sonora.android.ui.theme.SonoraTextPrimary
import org.sonora.android.ui.theme.SonoraTextSecondary
import org.sonora.android.ui.theme.Spacing
import org.sonora.android.ui.theme.onColorFor
import org.sonora.android.ui.theme.pressScale

/** The only playback data a collection detail screen needs; position ticks are excluded. */
@Immutable
data class CollectionPlaybackState(
    val currentTrackId: String? = null,
    val isPlaying: Boolean = false,
)

object CollectionMetrics {
    val ArtworkSize = 220.dp
    val ArtworkSizeRegular = 200.dp
    val RowHeight = 60.dp
    val ActionSize = 48.dp
}

@Composable
fun CollectionTopBar(
    title: String,
    collapsed: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val scheme = LocalBackgroundScheme.current
    val motion = LocalMotionPreferences.current
    val background by animateColorAsState(
        targetValue = if (collapsed) chromeSurface(scheme).tint else Color.Transparent,
        animationSpec = tween(motion.standardMs),
        label = "collectionTopBarBackground",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .statusBarsPadding()
            .heightIn(min = 56.dp)
            .padding(horizontal = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(CollectionMetrics.ActionSize)
                .clip(RoundedCornerShape(50))
                .clickable(role = Role.Button, onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.common_back),
                tint = scheme.onBackground,
                modifier = Modifier.size(22.dp),
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Spacing.sm),
        ) {
            if (collapsed) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = scheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
    }
}

/**
 * Cover with a thin second sleeve edge behind it. This is the collection screens'
 * signature: it reads as a physical record sleeve without adding decoration to the list.
 */
@Composable
fun SleeveArtwork(
    size: Dp = CollectionMetrics.ArtworkSize,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Radius.Medium),
    content: @Composable BoxScope.() -> Unit,
) {
    val scheme = LocalBackgroundScheme.current
    Box(modifier = modifier.size(size)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset(x = 5.dp, y = 5.dp)
                .clip(shape)
                .background(scheme.onBackground.copy(alpha = 0.055f))
                .border(1.dp, scheme.glassBorder, shape),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(scheme.glassTint)
                .border(1.dp, scheme.glassBorder, shape),
            content = content,
        )
    }
}

@Composable
fun CollectionHero(
    title: String,
    subtitle: String?,
    metadata: String?,
    hasTracks: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    descriptionExpanded: Boolean = false,
    onToggleDescription: (() -> Unit)? = null,
    downloadProgress: DownloadProgress? = null,
    onToggleDownload: (() -> Unit)? = null,
    statusContent: (@Composable () -> Unit)? = null,
    artwork: @Composable BoxScope.() -> Unit,
) {
    val scheme = LocalBackgroundScheme.current
    val descriptionCanExpand = isCollectionDescriptionExpandable(description)
    BoxWithConstraintsCompat(modifier = modifier) { wide ->
        if (wide) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                verticalAlignment = Alignment.Top,
            ) {
                SleeveArtwork(size = CollectionMetrics.ArtworkSizeRegular) { artwork() }
                Column(modifier = Modifier.weight(1f)) {
                    HeroCopy(
                        title = title,
                        subtitle = subtitle,
                        metadata = metadata,
                        description = description,
                        descriptionExpanded = descriptionExpanded,
                        descriptionCanExpand = descriptionCanExpand,
                        onToggleDescription = onToggleDescription,
                        statusContent = statusContent,
                    )
                    Spacer(Modifier.height(Spacing.lg))
                    HeroActions(
                        hasTracks = hasTracks,
                        onPlay = onPlay,
                        onShuffle = onShuffle,
                        downloadProgress = downloadProgress,
                        onToggleDownload = onToggleDownload,
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SleeveArtwork { artwork() }
                Spacer(Modifier.height(Spacing.lg))
                Column(modifier = Modifier.fillMaxWidth()) {
                    HeroCopy(
                        title = title,
                        subtitle = subtitle,
                        metadata = metadata,
                        description = description,
                        descriptionExpanded = descriptionExpanded,
                        descriptionCanExpand = descriptionCanExpand,
                        onToggleDescription = onToggleDescription,
                        statusContent = statusContent,
                    )
                    Spacer(Modifier.height(Spacing.lg))
                    HeroActions(
                        hasTracks = hasTracks,
                        onPlay = onPlay,
                        onShuffle = onShuffle,
                        downloadProgress = downloadProgress,
                        onToggleDownload = onToggleDownload,
                    )
                }
            }
        }
    }
}

@Composable
private fun BoxWithConstraintsCompat(
    modifier: Modifier,
    content: @Composable (wide: Boolean) -> Unit,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier = modifier) {
        content(maxWidth >= 520.dp)
    }
}

@Composable
private fun ColumnScope.HeroCopy(
    title: String,
    subtitle: String?,
    metadata: String?,
    description: String?,
    descriptionExpanded: Boolean,
    descriptionCanExpand: Boolean,
    onToggleDescription: (() -> Unit)?,
    statusContent: (@Composable () -> Unit)?,
) {
    val scheme = LocalBackgroundScheme.current
    var descriptionOverflow by remember(description) { mutableStateOf(false) }
    Text(
        text = title,
        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
        color = scheme.onBackground,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
    if (!subtitle.isNullOrBlank()) {
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = scheme.onBackgroundSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (!metadata.isNullOrBlank()) {
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = metadata,
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onBackgroundSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (!description.isNullOrBlank()) {
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onBackgroundSecondary,
            maxLines = if (descriptionExpanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result ->
                if (!descriptionExpanded) descriptionOverflow = result.hasVisualOverflow
            },
        )
        if ((descriptionExpanded || descriptionCanExpand || descriptionOverflow) && onToggleDescription != null) {
            TextButton(
                onClick = onToggleDescription,
                modifier = Modifier.heightIn(min = CollectionMetrics.ActionSize),
            ) {
                Text(
                    text = stringResource(
                        if (descriptionExpanded) R.string.collection_show_less
                        else R.string.collection_show_more
                    ),
                    color = scheme.accent,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }
    if (statusContent != null) {
        Spacer(Modifier.height(Spacing.sm))
        statusContent()
    }
}

@Composable
private fun HeroActions(
    hasTracks: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    downloadProgress: DownloadProgress?,
    onToggleDownload: (() -> Unit)?,
) {
    val scheme = LocalBackgroundScheme.current
    val largeText = LocalDensity.current.fontScale > 1.15f
    val play: @Composable (Modifier) -> Unit = { buttonModifier ->
        Button(
            onClick = onPlay,
            enabled = hasTracks,
            colors = ButtonDefaults.buttonColors(
                containerColor = scheme.accent,
                contentColor = onColorFor(scheme.accent),
            ),
            shape = RoundedCornerShape(50),
            modifier = buttonModifier.heightIn(min = CollectionMetrics.ActionSize),
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = stringResource(R.string.collection_play_action),
                fontWeight = FontWeight.Bold,
            )
        }
    }
    val shuffle: @Composable (Modifier) -> Unit = { buttonModifier ->
        OutlinedButton(
            onClick = onShuffle,
            enabled = hasTracks,
            border = androidx.compose.foundation.BorderStroke(1.dp, scheme.glassBorder),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = scheme.glassTint,
                contentColor = scheme.onBackground,
            ),
            shape = RoundedCornerShape(50),
            modifier = buttonModifier.heightIn(min = CollectionMetrics.ActionSize),
        ) {
            Icon(
                imageVector = Icons.Default.Shuffle,
                contentDescription = null,
                tint = if (hasTracks) scheme.onBackgroundSecondary else scheme.onBackgroundTertiary,
                modifier = Modifier.size(19.dp),
            )
            Spacer(Modifier.width(Spacing.xs))
            Text(
                text = stringResource(R.string.collection_shuffle_action),
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
    val download: @Composable (Modifier) -> Unit = { buttonModifier ->
        if (onToggleDownload != null) {
            DownloadIconButton(
                progress = downloadProgress ?: DownloadProgress(),
                onClick = { if (hasTracks) onToggleDownload() },
                modifier = buttonModifier,
                buttonSize = CollectionMetrics.ActionSize,
                iconSize = 22.dp,
                accent = scheme.accent,
                tint = if (hasTracks) scheme.onBackgroundSecondary else scheme.onBackgroundTertiary,
                enabled = hasTracks,
            )
        }
    }

    if (largeText) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            play(Modifier.fillMaxWidth())
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                shuffle(Modifier.weight(1f))
                download(Modifier)
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            play(Modifier.weight(1f))
            shuffle(Modifier)
            download(Modifier)
        }
    }
}

@Composable
fun CollectionSectionHeader(
    title: String,
    detail: String? = null,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalBackgroundScheme.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = scheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!detail.isNullOrBlank()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onBackgroundSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = Spacing.sm),
            )
        }
    }
}

@Composable
fun CollectionNowPlayingThread(
    title: String,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalBackgroundScheme.current
    val motion = LocalMotionPreferences.current
    val shape = RoundedCornerShape(Radius.Small)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter, vertical = Spacing.xs)
            .clip(shape)
            .background(scheme.accent.copy(alpha = 0.10f))
            .border(1.dp, scheme.accent.copy(alpha = 0.24f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isPlaying && motion.allowContinuousMotion) {
            AnimatedPlayingBars(
                isPlaying = true,
                modifier = Modifier.size(18.dp),
                barColor = scheme.accent,
            )
        } else {
            Icon(
                imageVector = Icons.Default.Equalizer,
                contentDescription = null,
                tint = scheme.accent,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(Spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.collection_now_playing),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = scheme.accent,
                maxLines = 1,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = scheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = stringResource(R.string.collection_go_to_now_playing),
            tint = scheme.onBackgroundSecondary,
            modifier = Modifier.size(20.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CollectionTrackRow(
    track: Track,
    trackNumber: Int?,
    showArtwork: Boolean,
    showArtist: Boolean,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isFavorite: Boolean,
    onPlay: () -> Unit,
    onFavoriteClick: () -> Unit,
    menuContent: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    fallbackCoverUrl: String? = null,
    contextMenuExtras: List<HeldTrackMenuGroup> = emptyList(),
    reorderMode: Boolean = false,
    isDragging: Boolean = false,
    dragHandleModifier: Modifier = Modifier,
) {
    val scheme = LocalBackgroundScheme.current
    val motion = LocalMotionPreferences.current
    val density = LocalDensity.current
    val contextMenu = LocalTrackContextMenu.current
    val anchor = remember { TrackAnchor() }
    val interaction = remember { MutableInteractionSource() }
    var menuExpanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Radius.Small)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressScale(interaction)
                .clip(shape)
                .background(
                    when {
                        isDragging -> scheme.accent.copy(alpha = 0.16f)
                        isCurrent -> scheme.accent.copy(alpha = 0.08f)
                        else -> Color.Transparent
                    }
                )
                .onGloballyPositioned { coordinates ->
                    if (coordinates.isAttached) anchor.rect = coordinates.boundsInWindow()
                }
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onPlay,
                    onLongClick = {
                        if (!reorderMode && contextMenu != null && anchor.rect != Rect.Zero) {
                            contextMenu.open(track, anchor.rect, extraGroups = contextMenuExtras)
                        } else if (!reorderMode) {
                            menuExpanded = true
                        }
                    },
                )
                .padding(horizontal = Spacing.gutter, vertical = Spacing.xs)
                .heightIn(min = CollectionMetrics.RowHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (trackNumber != null) {
                Box(
                    modifier = Modifier.width(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isCurrent) {
                        CurrentTrackGlyph(isPlaying = isPlaying, color = scheme.accent)
                    } else {
                        Text(
                            text = trackNumber.toString(),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                            color = scheme.onBackgroundSecondary,
                        )
                    }
                }
                Spacer(Modifier.width(Spacing.xs))
            }

            if (showArtwork) {
                val artSize = if (trackNumber != null) 40.dp else 48.dp
                Box(
                    modifier = Modifier.size(artSize),
                    contentAlignment = Alignment.Center,
                ) {
                    TrackThumb(
                        url = track.artworkUrl,
                        fallbackUrl = fallbackCoverUrl,
                        size = artSize,
                        shape = RoundedCornerShape(8.dp),
                        contentDescription = track.title,
                    )
                    if (isCurrent && trackNumber == null) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(alpha = 0.48f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            CurrentTrackGlyph(isPlaying = isPlaying, color = scheme.accent)
                        }
                    }
                }
                Spacer(Modifier.width(Spacing.sm))
            } else if (trackNumber == null) {
                Spacer(Modifier.width(Spacing.xs))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                    ),
                    color = if (isCurrent) scheme.accent else scheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showArtist && track.artist.isNotBlank()) {
                    ArtistLinks(
                        track = track,
                        linksEnabled = false,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onBackgroundSecondary,
                    )
                }
            }

            if (density.fontScale < 1.25f) {
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = track.formattedDuration,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onBackgroundSecondary,
                    maxLines = 1,
                )
            }

            if (reorderMode) {
                Box(
                    modifier = Modifier
                        .size(CollectionMetrics.ActionSize)
                        .then(dragHandleModifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.DragHandle,
                        contentDescription = stringResource(R.string.playlist_drag_handle_content_description),
                        tint = scheme.onBackgroundSecondary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            } else {
                IconButton(
                    onClick = onFavoriteClick,
                    modifier = Modifier.size(CollectionMetrics.ActionSize),
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = stringResource(
                            if (isFavorite) R.string.track_item_favorite_remove_content_description
                            else R.string.track_item_favorite_add_content_description
                        ),
                        tint = if (isFavorite) scheme.accent else scheme.onBackgroundSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }

                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(CollectionMetrics.ActionSize),
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.collection_more_options),
                            tint = scheme.onBackgroundSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        shape = RoundedCornerShape(16.dp),
                        containerColor = SonoraOpaqueSurfaceCard,
                        border = androidx.compose.foundation.BorderStroke(1.dp, scheme.glassBorder),
                        content = { menuContent { menuExpanded = false } },
                    )
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(
                start = Spacing.gutter + when {
                    trackNumber != null && showArtwork -> 24.dp + Spacing.xs + 40.dp + Spacing.sm
                    trackNumber != null -> 24.dp + Spacing.xs
                    showArtwork -> 48.dp + Spacing.sm
                    else -> 0.dp
                },
                end = Spacing.gutter,
            ),
            thickness = 1.dp,
            color = scheme.glassBorder.copy(alpha = 0.35f),
        )
    }
}

@Composable
private fun CurrentTrackGlyph(
    isPlaying: Boolean,
    color: Color,
) {
    val motion = LocalMotionPreferences.current
    if (isPlaying && motion.allowContinuousMotion) {
        AnimatedPlayingBars(
            isPlaying = true,
            modifier = Modifier.size(17.dp),
            barColor = color,
        )
    } else {
        Icon(
            imageVector = Icons.Default.Equalizer,
            contentDescription = stringResource(R.string.collection_now_playing),
            tint = color,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
fun CollectionEmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    retryLabel: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    val scheme = LocalBackgroundScheme.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl, vertical = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(50))
                .background(scheme.glassTint)
                .border(1.dp, scheme.glassBorder, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = scheme.onBackgroundSecondary,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.height(Spacing.lg))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = scheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onBackgroundSecondary,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        if (onAction != null && actionLabel != null) {
            Spacer(Modifier.height(Spacing.lg))
            Button(
                onClick = onAction,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = scheme.accent,
                    contentColor = onColorFor(scheme.accent),
                ),
                modifier = Modifier.heightIn(min = CollectionMetrics.ActionSize),
            ) {
                Text(actionLabel, fontWeight = FontWeight.Bold)
            }
        } else if (onRetry != null && retryLabel != null) {
            Spacer(Modifier.height(Spacing.lg))
            Button(
                onClick = onRetry,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = scheme.accent,
                    contentColor = onColorFor(scheme.accent),
                ),
                modifier = Modifier.heightIn(min = CollectionMetrics.ActionSize),
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text(retryLabel, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun CollectionLoadingSkeleton(
    modifier: Modifier = Modifier,
    rowCount: Int = 7,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter, vertical = Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Box(
            modifier = Modifier
                .size(CollectionMetrics.ArtworkSize)
                .shimmer(RoundedCornerShape(Radius.Medium)),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.62f)
                .height(28.dp)
                .shimmer(RoundedCornerShape(8.dp)),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.42f)
                .height(18.dp)
                .shimmer(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.height(Spacing.sm))
        repeat(rowCount) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .shimmer(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.width(Spacing.md))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.72f)
                            .height(15.dp)
                            .shimmer(RoundedCornerShape(5.dp)),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.42f)
                            .height(12.dp)
                            .shimmer(RoundedCornerShape(5.dp)),
                    )
                }
            }
        }
    }
}
