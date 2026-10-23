package org.sonora.android.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.sonora.android.api.Track
import org.sonora.android.ui.theme.LocalBackgroundScheme
import org.sonora.android.ui.theme.LocalMotionPreferences
import org.sonora.android.ui.theme.SonoraOpaqueSurfaceCard
import kotlin.math.roundToInt

/** One row inside a [HeldTrackMenuGroup]. */
data class HeldTrackAction(
    val icon: ImageVector,
    val label: String,
    val tint: Color? = null,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

/** Actions separated from the previous group by a 6dp band. */
data class HeldTrackMenuGroup(val actions: List<HeldTrackAction>)

/**
 * Mutable window anchor kept outside Compose state: rows update it on every layout/scroll,
 * and only the long-press callback reads it. A snapshot state here would recompose every
 * visible row on every scroll frame.
 */
@Stable
class TrackAnchor {
    var rect: Rect = Rect.Zero
}

/** Opens the shared long-press menu for a row or card at [anchor] (window coordinates). */
class TrackContextMenuController internal constructor(
    private val show: (Track, Rect, List<HeldTrackMenuGroup>) -> Unit
) {
    fun open(
        track: Track,
        anchor: Rect,
        extraGroups: List<HeldTrackMenuGroup> = emptyList()
    ) = show(track, anchor, extraGroups)
}

val LocalTrackContextMenu = staticCompositionLocalOf<TrackContextMenuController?> { null }

/** A long-press menu request anchored to the pressed row. */
data class HeldTrackMenuRequest(
    val track: Track,
    val anchor: Rect,
    val groups: List<HeldTrackMenuGroup>,
    val onDismiss: () -> Unit
)

private const val EDGE_MARGIN = 8f
private const val ANCHOR_GAP = 8f

/**
 * Places a floating panel of [menuSize] next to [anchor].
 *
 * Coordinates are unit-agnostic: pass screen/anchor/menu values in the same unit
 * (the host converts px → dp so the 8dp margins stay density-correct).
 * The panel prefers the space below the anchor, falls back above it, and is
 * finally clamped inside the safe top/bottom edges. A panel taller than the
 * available space is pinned to the top safe edge and is expected to scroll.
 */
fun calculateHeldMenuPosition(
    anchor: Rect,
    screenSize: Size,
    menuSize: Size,
    safeTop: Float,
    safeBottom: Float
): Offset {
    val contentTop = safeTop + EDGE_MARGIN
    val contentBottom = (screenSize.height - safeBottom - EDGE_MARGIN)
        .coerceAtLeast(contentTop)

    val belowY = anchor.bottom + ANCHOR_GAP
    val aboveY = anchor.top - ANCHOR_GAP - menuSize.height
    val spaceBelow = contentBottom - belowY
    val spaceAbove = anchor.top - ANCHOR_GAP - contentTop

    val fitsBelow = menuSize.height <= spaceBelow
    val fitsAbove = menuSize.height <= spaceAbove
    val placeBelow = when {
        fitsBelow -> true
        fitsAbove -> false
        else -> spaceBelow >= spaceAbove
    }

    val rawY = if (placeBelow) belowY else aboveY
    val maxY = (contentBottom - menuSize.height).coerceAtLeast(contentTop)
    val y = rawY.coerceIn(contentTop, maxY)

    val maxX = (screenSize.width - EDGE_MARGIN - menuSize.width)
        .coerceAtLeast(EDGE_MARGIN)
    val rawX = anchor.center.x - menuSize.width / 2f
    val x = rawX.coerceIn(EDGE_MARGIN, maxX)

    return Offset(x, y)
}

/**
 * BitChord-style long-press surface: a lifted track card plus a compact,
 * neutral-glass menu anchored above/below the pressed row.
 *
 * Deliberately separate from [TrackActionsSheet]: no artwork-tinted sheet and
 * no bottom-sheet chrome. The dialog is full-screen so the menu can sit next to
 * the row while a dark scrim keeps the rest of the list behind it.
 */
@Composable
fun HeldTrackMenuHost(request: HeldTrackMenuRequest?) {
    if (request == null) return

    val scheme = LocalBackgroundScheme.current
    val motion = LocalMotionPreferences.current
    val dismiss by rememberUpdatedState(request.onDismiss)
    val animationKey = request.track.id to request.anchor
    var appeared by remember(animationKey) { mutableStateOf(false) }
    LaunchedEffect(animationKey) { appeared = true }

    val scale by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.92f,
        animationSpec = if (motion.reduceMotion) {
            snap()
        } else {
            spring(dampingRatio = 0.72f, stiffness = 420f)
        },
        label = "heldTrackMenuScale"
    )
    val alpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = if (motion.reduceMotion) snap() else tween(durationMillis = 120),
        label = "heldTrackMenuAlpha"
    )

    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val widthPx = constraints.maxWidth.toFloat()
            val heightPx = constraints.maxHeight.toFloat()
            val safeTopPx = WindowInsets.safeDrawing.getTop(density).toFloat()
            val safeBottomPx = WindowInsets.safeDrawing.getBottom(density).toFloat()

            val widthDp = widthPx / density.density
            val heightDp = heightPx / density.density
            val safeTopDp = safeTopPx / density.density
            val safeBottomDp = safeBottomPx / density.density

            var panelSize by remember { mutableStateOf(IntSize.Zero) }

            val anchorDp = Rect(
                left = request.anchor.left / density.density,
                top = request.anchor.top / density.density,
                right = request.anchor.right / density.density,
                bottom = request.anchor.bottom / density.density
            )
            val screenDp = Size(widthDp, heightDp)
            val panelDp = Size(
                panelSize.width / density.density,
                panelSize.height / density.density
            )
            val positionDp = if (panelSize != IntSize.Zero) {
                calculateHeldMenuPosition(
                    anchor = anchorDp,
                    screenSize = screenDp,
                    menuSize = panelDp,
                    safeTop = safeTopDp,
                    safeBottom = safeBottomDp
                )
            } else {
                Offset.Zero
            }
            val positionPx = IntOffset(
                (positionDp.x * density.density).roundToInt(),
                (positionDp.y * density.density).roundToInt()
            )

            val availableHeightDp =
                (heightDp - safeTopDp - safeBottomDp - 16f).coerceAtLeast(160f)
            // Card (66dp) + 8dp gap + a little breathing room around the panel.
            val menuMaxHeight = (availableHeightDp - 74f).coerceAtLeast(92f).dp

            val panelAlpha = if (panelSize == IntSize.Zero) 0f else alpha

            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Color.Black.copy(alpha = if (scheme.isLight) 0.44f else 0.62f)
                    )
                    .pointerInput(Unit) {
                        detectTapGestures { dismiss() }
                    }
            )

            Column(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .fillMaxWidth()
                    .offset { positionPx }
                    .onSizeChanged { panelSize = it }
                    .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = panelAlpha
                    }
            ) {
                HeldTrackCard(track = request.track)

                Spacer(modifier = Modifier.height(8.dp))

                val visibleGroups = request.groups.filter { it.actions.isNotEmpty() }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(elevation = 14.dp, shape = RoundedCornerShape(18.dp))
                        .clip(RoundedCornerShape(18.dp))
                        .background(SonoraOpaqueSurfaceCard)
                        .border(
                            width = 1.dp,
                            color = scheme.glassBorder,
                            shape = RoundedCornerShape(18.dp)
                        )
                        .heightIn(max = menuMaxHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 6.dp)
                ) {
                    visibleGroups.forEachIndexed { groupIndex, group ->
                        if (groupIndex > 0) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .background(scheme.onBackground.copy(alpha = 0.06f))
                            )
                        }
                        group.actions.forEach { action ->
                            HeldTrackActionRow(action = action, onDismiss = request.onDismiss)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeldTrackCard(track: Track) {
    val scheme = LocalBackgroundScheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elevation = 18.dp, shape = RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(SonoraOpaqueSurfaceCard)
            .border(
                width = 1.dp,
                color = scheme.glassBorder,
                shape = RoundedCornerShape(18.dp)
            )
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TrackThumb(
            url = track.artworkUrl,
            size = 46.dp,
            shape = RoundedCornerShape(12.dp),
            contentDescription = track.title
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onBackgroundSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun HeldTrackActionRow(action: HeldTrackAction, onDismiss: () -> Unit) {
    val scheme = LocalBackgroundScheme.current
    val contentColor = when {
        !action.enabled -> scheme.onBackgroundTertiary
        action.tint != null -> action.tint
        else -> scheme.onBackgroundSecondary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(
                enabled = action.enabled,
                role = Role.Button,
                onClick = {
                    action.onClick()
                    onDismiss()
                }
            )
            .semantics(mergeDescendants = true) {
                if (!action.enabled) disabled()
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = action.label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.width(12.dp))
        Icon(
            imageVector = action.icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(18.dp)
        )
    }
}
