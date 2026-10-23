package org.sonora.android.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.sonora.android.ui.components.backdrop.Backdrop
import org.sonora.android.ui.components.backdrop.LocalLiquidGlassEnabled
import org.sonora.android.ui.components.backdrop.chromeLiquidGlass
import org.sonora.android.ui.components.backdrop.isLiquidGlassSupported
import org.sonora.android.ui.theme.LocalBackgroundScheme

@Composable
internal fun Modifier.homeLiquidGlass(
    shape: CornerBasedShape,
    fallbackColor: Color = LocalBackgroundScheme.current.glassTint
): Modifier {
    val scheme = LocalBackgroundScheme.current
    if (LocalLiquidGlassEnabled.current && isLiquidGlassSupported()) {
        val brush = remember(scheme) { Brush.verticalGradient(scheme.gradient) }
        val backdrop = remember(brush) { HomeGradientBackdrop(brush) }
        return chromeLiquidGlass(
            shape = shape,
            fallbackColor = fallbackColor,
            backdropOverride = backdrop
        )
    }
    val sheen = if (scheme.isLight) Color.Black.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.10f)
    val rim = if (scheme.isLight) {
        Brush.linearGradient(
            listOf(
                Color.White.copy(alpha = 0.92f),
                scheme.glassBorder.copy(alpha = 0.55f),
                Color.White.copy(alpha = 0.72f)
            )
        )
    } else {
        Brush.linearGradient(
            listOf(
                Color.White.copy(alpha = 0.34f),
                scheme.glassBorder.copy(alpha = 0.65f),
                Color.White.copy(alpha = 0.12f)
            )
        )
    }
    return this
        .clip(shape)
        .background(fallbackColor, shape)
        .drawBehind {
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(sheen, Color.Transparent, Color.Transparent),
                    start = Offset.Zero,
                    end = Offset(size.width * 0.9f, size.height * 0.9f)
                )
            )
        }
        .border(BorderStroke(1.dp, rim), shape)
}

private class HomeGradientBackdrop(
    private val brush: Brush
) : Backdrop {
    override val isCoordinatesDependent: Boolean = false

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        drawRect(brush = brush)
    }
}
