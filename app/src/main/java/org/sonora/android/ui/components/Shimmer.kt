package org.sonora.android.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import org.sonora.android.ui.theme.LocalBackgroundScheme
import org.sonora.android.ui.theme.LocalMotionPreferences
import org.sonora.android.ui.theme.Radius

/**
 * Loading placeholder surface with a single, cheap horizontal sheen.
 *
 * Under Reduce Motion this degrades to a flat glass tint (no infinite animation),
 * satisfying the "no continuous motion" accessibility policy.
 */
@Composable
fun Modifier.shimmer(shape: Shape = RoundedCornerShape(Radius.Small)): Modifier {
    val motion = LocalMotionPreferences.current
    val scheme = LocalBackgroundScheme.current
    if (!motion.allowContinuousMotion) {
        return this.background(scheme.glassTint, shape)
    }
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerProgress"
    )
    val span = 1000f
    val start = progress * (span * 2f) - span
    val base = scheme.glassTint
    val highlight = scheme.onBackground.copy(alpha = 0.10f)
    val brush = Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset(start, 0f),
        end = Offset(start + span, 0f)
    )
    return this.background(brush, shape)
}
