package org.sonora.android.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Dissolves the bottom of an artwork into whatever sits behind it.
 *
 * The opaque-to-transparent [BlendMode.DstIn] mask is the same treatment the full-screen
 * player uses on its sleeve, so a large photo can fade into a blurred copy of itself (or
 * into the canvas) instead of ending on a hard rectangular edge.
 *
 * @param startFraction vertical position, relative to the composable height, where the
 * fade begins. `0.76f` keeps 76% of the photo crisp and dissolves the remaining 24%.
 */
fun Modifier.artworkBottomFade(startFraction: Float = 0.76f): Modifier {
    val start = startFraction.coerceIn(0f, 1f)
    return this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Black,
                    start to Color.Black,
                    1f to Color.Transparent,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
}
