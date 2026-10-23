package org.sonora.android.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Context-aware Liquid Glass canvas.
 *
 * - Reads the active [AppearanceMode] (provided by `SonoraTheme`).
 * - For dynamic modes, derives accents from [coverUrl] and builds a [BackgroundScheme].
 * - Crossfades gradient stops (320–420ms) unless Reduce Motion is on.
 * - Provides the resolved [LocalBackgroundScheme] to descendants so glass surfaces
 *   and text adapt automatically.
 */
@Composable
fun SonoraBackground(
    modifier: Modifier = Modifier,
    coverUrl: String? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val mode = LocalAppearanceMode.current
    val motion = LocalMotionPreferences.current
    val userAccent = LocalUserAccent.current
    val coverAccent = if (mode.isDynamic) rememberCoverAccent(coverUrl) else null
    val scheme = remember(mode, coverAccent, userAccent) {
        if (mode.isDynamic) {
            // Cover-derived accent when available; otherwise the user's chosen accent,
            // so a dynamic theme never falls back to a stray default colour.
            BackgroundSchemeFactory.build(
                mode,
                coverAccent?.primary ?: userAccent,
                coverAccent?.secondary ?: userAccent
            )
        } else {
            BackgroundSchemeFactory.build(mode, userAccent, userAccent)
        }
    }
    val brush = animatedCanvasBrush(scheme.gradient, motion.backgroundMs)
    // Ambient accent glow (static; only for dynamic modes so AMOLED stays pure black,
    // PURE_WHITE stays clean). Animated on accent change, snapped under Reduce Motion.
    val glowColor by animateColorAsState(scheme.accent, tween(motion.backgroundMs), label = "glow")
    CompositionLocalProvider(LocalBackgroundScheme provides scheme) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(brush),
        ) {
            if (mode.isDynamic) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    glowColor.copy(alpha = if (scheme.isLight) 0.28f else 0.32f),
                                    glowColor.copy(alpha = if (scheme.isLight) 0.10f else 0.12f),
                                    Color.Transparent
                                )
                            )
                        )
                )
            }
            content()
        }
    }
}

@Composable
private fun animatedCanvasBrush(stops: List<Color>, durationMs: Int): Brush {
    val c0 by animateColorAsState(stops[0], tween(durationMs), label = "canvas0")
    val c1 by animateColorAsState(stops[1], tween(durationMs), label = "canvas1")
    val c2 by animateColorAsState(stops[2], tween(durationMs), label = "canvas2")
    return remember(c0, c1, c2) { Brush.verticalGradient(listOf(c0, c1, c2)) }
}
