package org.sonora.android.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import org.sonora.android.ui.theme.LocalBackgroundScheme
import org.sonora.android.ui.theme.LocalMotionPreferences
import org.sonora.android.ui.theme.onColorFor

/**
 * A single "liquid glass" indicator that slides horizontally between equally sized
 * slots — Apple Music style. It squashes/stretches proportionally to its own velocity
 * while moving, giving a subtle water-droplet feel, and snaps instantly under
 * Reduced Motion.
 *
 * Place it as the first child of a [Box]; it measures the available width, divides it
 * by [itemCount] and positions the pill under the [selectedIndex] slot. Render the
 * actual items on top of it.
 */
@Composable
fun SlidingLiquidIndicator(
    selectedIndex: Int,
    itemCount: Int,
    modifier: Modifier = Modifier,
    horizontalInset: Dp = 6.dp,
    verticalInset: Dp = 6.dp
) {
    if (itemCount <= 0) return
    val scheme = LocalBackgroundScheme.current
    val motion = LocalMotionPreferences.current
    val accent = scheme.accent

    val target = selectedIndex.coerceIn(0, itemCount - 1).toFloat()
    val anim = remember { Animatable(target) }
    LaunchedEffect(target) {
        if (motion.reduceMotion) {
            anim.snapTo(target)
        } else {
            anim.animateTo(
                targetValue = target,
                animationSpec = spring(
                    dampingRatio = 0.62f,
                    stiffness = Spring.StiffnessLow
                )
            )
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val itemWidth = maxWidth / itemCount
        val bubbleWidth = (itemWidth - horizontalInset * 2).coerceAtLeast(0.dp)
        val bubbleHeight = (maxHeight - verticalInset * 2).coerceAtLeast(0.dp)
        val shape = RoundedCornerShape(50)

        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = (itemWidth * anim.value + horizontalInset).roundToPx(),
                        y = verticalInset.roundToPx()
                    )
                }
                .size(bubbleWidth, bubbleHeight)
                .graphicsLayer {
                    val v = abs(anim.velocity)
                    val stretch = if (motion.reduceMotion) 0f else (v * 0.03f).coerceAtMost(0.16f)
                    scaleX = 1f + stretch
                    scaleY = 1f - stretch * 0.55f
                    transformOrigin = TransformOrigin.Center
                }
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            accent.copy(alpha = 0.80f),
                            accent.copy(alpha = 0.96f)
                        )
                    )
                )
                .drawBehind {
                    // Soft continuous sheen (no hard edges) — the "water" gloss.
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(Color.White.copy(alpha = 0.30f), Color.Transparent),
                            center = Offset(size.width * 0.32f, size.height * 0.06f),
                            radius = size.maxDimension * 0.95f
                        )
                    )
                }
                .border(1.dp, Color.White.copy(alpha = 0.24f), shape)
        )
    }
}

/**
 * Content color that stays readable on top of the sliding indicator for the current accent.
 */
@Composable
fun indicatorContentColor(): Color = onColorFor(LocalBackgroundScheme.current.accent)
