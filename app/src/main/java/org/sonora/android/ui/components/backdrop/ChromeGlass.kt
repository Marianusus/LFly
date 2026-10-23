/*
 * Integration glue for the vendored Kyant0/backdrop engine, adapted from
 * BitChord's LiquidGlass.kt (GPL-3.0) and EchoMusic's GlassEffectConfig.
 * Sonora uses it only for the opt-in Liquid Glass chrome.
 */
package org.sonora.android.ui.components.backdrop

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import org.sonora.android.ui.components.backdrop.backdrops.LayerBackdrop
import org.sonora.android.ui.components.backdrop.backdrops.emptyBackdrop
import org.sonora.android.ui.components.backdrop.effects.blur
import org.sonora.android.ui.components.backdrop.effects.colorControls
import org.sonora.android.ui.components.backdrop.effects.lens
import org.sonora.android.ui.components.backdrop.highlight.Highlight
import org.sonora.android.ui.components.backdrop.shadow.Shadow
import org.sonora.android.ui.theme.LocalBackgroundScheme

val LocalLiquidGlassEnabled = staticCompositionLocalOf { false }
val LocalAppBackdrop = staticCompositionLocalOf<Backdrop> { emptyBackdrop() }
internal val LocalGlassExport = compositionLocalOf<LayerBackdrop?> { null }

fun isLiquidGlassSupported(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
    sdkInt >= Build.VERSION_CODES.S

private const val VIBRANCY = 1f
private const val BLUR_RADIUS_DP = 8f
private const val LENS_HEIGHT = 0.5f
private const val LENS_AMOUNT = 0.5f
private const val LENS_MAX_DP = 48f
private const val SURFACE_OPACITY = 0.4f
private const val GLASS_RESOLUTION_SCALE = 0.33f

@Composable
fun glassContentColor(): Color =
    if (LocalBackgroundScheme.current.isLight) Color.Black else Color.White

@Composable
fun glassIndicatorColor(): Color =
    if (LocalBackgroundScheme.current.isLight) Color.White else Color.Black

/**
 * Real backdrop-sampled glass for the floating chrome. On unsupported devices
 * the modifier is a no-op and the caller keeps its normal opaque surface.
 */
@Composable
fun Modifier.chromeLiquidGlass(
    shape: CornerBasedShape,
    fallbackColor: Color,
    exportedBackdrop: LayerBackdrop? = null,
    backdropOverride: Backdrop? = null
): Modifier {
    if (!LocalLiquidGlassEnabled.current || !isLiquidGlassSupported()) {
        return background(fallbackColor, shape)
            .border(0.5.dp, Color.White.copy(alpha = 0.10f), shape)
    }
    val backdrop = backdropOverride ?: LocalAppBackdrop.current
    val scheme = LocalBackgroundScheme.current
    val density = LocalDensity.current
    val blurPx = with(density) { BLUR_RADIUS_DP.dp.toPx() } * GLASS_RESOLUTION_SCALE
    val lensHeightPx = with(density) { (LENS_HEIGHT * LENS_MAX_DP).dp.toPx() } * GLASS_RESOLUTION_SCALE
    val lensAmountPx = with(density) { (LENS_AMOUNT * LENS_MAX_DP).dp.toPx() } * GLASS_RESOLUTION_SCALE
    val surfaceTint = if (scheme.isLight) Color(0xFFFAFAFA) else Color(0xFF121212)

    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            colorControls(saturation = 1f + 0.5f * VIBRANCY)
            blur(blurPx)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                lens(
                    refractionHeight = lensHeightPx,
                    refractionAmount = lensAmountPx,
                    depthEffect = true,
                    chromaticAberration = true
                )
            }
        },
        highlight = { Highlight.Default },
        shadow = { Shadow.Default },
        onDrawSurface = {
            drawRect(color = surfaceTint.copy(alpha = SURFACE_OPACITY), size = size)
        },
        exportedBackdrop = exportedBackdrop ?: LocalGlassExport.current,
        backdropScale = GLASS_RESOLUTION_SCALE
    )
}
