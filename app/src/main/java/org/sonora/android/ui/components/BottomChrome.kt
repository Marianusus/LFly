package org.sonora.android.ui.components

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.sonora.android.ui.theme.AppearanceMode
import org.sonora.android.ui.theme.BackgroundScheme
import org.sonora.android.ui.theme.mixColor

/**
 * Geometry of the floating bottom chrome (Liquid Glass bottom navigation + mini player).
 *
 * These bars are rendered as an overlay (not reserved by the Scaffold), so every
 * scrollable screen must keep its last item clear of them via [LocalBottomChromeInset].
 */
object BottomChrome {
    val NavBarHeight = 64.dp
    val MiniPlayerHeight = 64.dp

    /** Vertical margin around each floating bar. */
    val EdgeMargin = 8.dp

    /** Gap between the mini player and the navigation bar. */
    val StackGap = 8.dp

    /** Extra breathing room so the last item is fully visible above the chrome. */
    val ContentBreathingRoom = 8.dp

    /**
     * Total bottom space a scrollable list must keep clear.
     * Pure function — unit tested.
     */
    fun inset(hasMiniPlayer: Boolean, navigationBarsInset: Dp): Dp =
        navigationBarsInset + EdgeMargin + NavBarHeight +
            (if (hasMiniPlayer) StackGap + MiniPlayerHeight + EdgeMargin else 0.dp) +
            ContentBreathingRoom
}

/** Bottom inset every scrollable screen should add to its `contentPadding`. */
val LocalBottomChromeInset = staticCompositionLocalOf { 0.dp }

/**
 * Resolved compositing colors for the floating bottom chrome.
 * The bars stay translucent enough to feel Liquid Glass, but full enough that
 * scrolling content behind them never fights with icons/labels (AA contrast).
 */
@Immutable
data class ChromeSurface(
    val tint: Color,
    val border: Color
)

/**
 * Adaptive chrome surface per appearance mode. Pure — unit tested.
 * Every mode keeps a hint of the contextual accent while staying >= 0.88 alpha.
 */
fun chromeSurface(scheme: BackgroundScheme): ChromeSurface = when (scheme.mode) {
    AppearanceMode.AMOLED_BLACK -> ChromeSurface(
        tint = Color(0xF00B0B12),                 // near-opaque black-blue
        border = Color(0x33FFFFFF)
    )
    AppearanceMode.PURE_WHITE -> ChromeSurface(
        tint = Color(0xF2FFFFFF),                 // near-opaque white
        border = Color(0x24000000)
    )
    AppearanceMode.DYNAMIC_DARK -> ChromeSurface(
        tint = mixColor(Color(0xF0101016), scheme.accent, 0.10f).copy(alpha = 0.94f),
        border = Color(0x30FFFFFF)
    )
    AppearanceMode.DYNAMIC_LIGHT -> ChromeSurface(
        tint = mixColor(Color(0xF2FFFFFF), scheme.accent, 0.12f).copy(alpha = 0.94f),
        border = Color(0x22000000)
    )
}
