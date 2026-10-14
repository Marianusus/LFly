package org.sonora.android.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * A resolved, context-aware background for the Sonora Liquid Glass shell.
 *
 * Pure value object: derived from an [AppearanceMode] plus (for dynamic modes) 1–2
 * accent colors extracted from the contextual cover artwork. Contains everything the
 * glass components need so no component recomputes palette math per frame.
 */
@Immutable
data class BackgroundScheme(
    val mode: AppearanceMode,
    /** Exactly three gradient stops (top → bottom) so crossfades are cheap. */
    val gradient: List<Color>,
    val onBackground: Color,
    val onBackgroundSecondary: Color,
    val onBackgroundTertiary: Color,
    val glassTint: Color,
    val glassBorder: Color,
    val accent: Color,
    val accentSecondary: Color,
    val scrim: Color,
    val isLight: Boolean
)

// ==========================================
// Color math (pure, unit-testable)
// ==========================================

/** Perceptual-ish luma in [0,1] (Rec.601 weights). */
fun Color.luma(): Float = (0.299f * red + 0.587f * green + 0.114f * blue).coerceIn(0f, 1f)

/** Linear interpolation between two colors; [t]=0 returns [a], [t]=1 returns [b]. */
fun mixColor(a: Color, b: Color, t: Float): Color {
    val f = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * f,
        green = a.green + (b.green - a.green) * f,
        blue = a.blue + (b.blue - a.blue) * f,
        alpha = a.alpha + (b.alpha - a.alpha) * f
    )
}

/** Scales a color toward black until its luma is at or below [maxLuma]. */
internal fun clampLumaDark(color: Color, maxLuma: Float): Color {
    val l = color.luma()
    if (l <= maxLuma || l <= 0.0001f) return color
    return mixColor(Color.Black, color, (maxLuma / l)).copy(alpha = color.alpha)
}

/** Scales a color toward white until its luma is at or above [minLuma]. */
internal fun clampLumaLight(color: Color, minLuma: Float): Color {
    val l = color.luma()
    if (l >= minLuma) return color
    val t = (minLuma - l) / (1f - l)
    return mixColor(color, Color.White, t).copy(alpha = color.alpha)
}

/** Keeps an accent readable on the given canvas (brighter on dark, darker on light). */
internal fun accentForDisplay(accent: Color, light: Boolean): Color =
    if (light) clampLumaDark(accent, 0.55f) else clampLumaLight(accent, 0.55f)

/** Picks a foreground color (dark/white) with adequate contrast for [background]. */
fun onColorFor(background: Color): Color =
    if (background.luma() > 0.55f) Color(0xFF15151A) else Color.White

// ==========================================
// Factory
// ==========================================

object BackgroundSchemeFactory {

    val FallbackViolet = Color(0xFF7C4DFF)
    val FallbackBlue = Color(0xFF4D7CFF)

    private val DarkText = Color(0xFF15151A)
    private val DarkTextSecondary = Color(0xB315151A)
    private val DarkTextTertiary = Color(0x8015151A)
    private val WhiteText = Color(0xFFFFFFFF)
    private val WhiteTextSecondary = Color(0xB3FFFFFF)
    private val WhiteTextTertiary = Color(0x73FFFFFF)

    /** Builds a scheme; [accent]/[accentSecondary] are only used by dynamic modes. */
    fun build(mode: AppearanceMode, accent: Color? = null, accentSecondary: Color? = null): BackgroundScheme {
        val light = mode.isLight
        val primary = accent ?: FallbackViolet
        val secondary = accentSecondary ?: FallbackBlue
        val displayAccent = accentForDisplay(primary, light)
        val displaySecondary = accentForDisplay(secondary, light)

        return when (mode) {
            AppearanceMode.AMOLED_BLACK -> BackgroundScheme(
                mode = mode,
                gradient = listOf(Color(0xFF000000), Color(0xFF000000), Color(0xFF000000)),
                onBackground = WhiteText,
                onBackgroundSecondary = WhiteTextSecondary,
                onBackgroundTertiary = WhiteTextTertiary,
                glassTint = Color(0x14FFFFFF),
                glassBorder = Color(0x26FFFFFF),
                accent = displayAccent,
                accentSecondary = displaySecondary,
                scrim = Color(0x99000000),
                isLight = false
            )

            AppearanceMode.PURE_WHITE -> BackgroundScheme(
                mode = mode,
                gradient = listOf(Color(0xFFFFFFFF), Color(0xFFF7F7FB), Color(0xFFF1F1F6)),
                onBackground = DarkText,
                onBackgroundSecondary = DarkTextSecondary,
                onBackgroundTertiary = DarkTextTertiary,
                glassTint = Color(0x0F000000),
                glassBorder = Color(0x1F000000),
                accent = displayAccent,
                accentSecondary = displaySecondary,
                scrim = Color(0x33000000),
                isLight = true
            )

            AppearanceMode.DYNAMIC_DARK -> {
                val g0 = clampLumaDark(mixColor(Color.Black, primary, 0.55f), 0.29f)
                val g1 = clampLumaDark(mixColor(Color.Black, secondary, 0.34f), 0.19f)
                BackgroundScheme(
                    mode = mode,
                    gradient = listOf(g0, g1, Color(0xFF050509)),
                    onBackground = WhiteText,
                    onBackgroundSecondary = WhiteTextSecondary,
                    onBackgroundTertiary = WhiteTextTertiary,
                    glassTint = Color(0x14FFFFFF),
                    glassBorder = Color(0x26FFFFFF),
                    accent = displayAccent,
                    accentSecondary = displaySecondary,
                    scrim = Color(0x99000000),
                    isLight = false
                )
            }

            AppearanceMode.DYNAMIC_LIGHT -> {
                val g0 = clampLumaLight(mixColor(Color.White, primary, 0.42f), 0.82f)
                val g1 = clampLumaLight(mixColor(Color.White, secondary, 0.25f), 0.88f)
                BackgroundScheme(
                    mode = mode,
                    gradient = listOf(g0, g1, Color(0xFFFCFCFF)),
                    onBackground = DarkText,
                    onBackgroundSecondary = DarkTextSecondary,
                    onBackgroundTertiary = DarkTextTertiary,
                    glassTint = Color(0x12FFFFFF),
                    glassBorder = Color(0x24000000),
                    accent = displayAccent,
                    accentSecondary = displaySecondary,
                    scrim = Color(0x33000000),
                    isLight = true
                )
            }
        }
    }

    fun fallback(mode: AppearanceMode): BackgroundScheme = build(mode, null, null)
}

val LocalBackgroundScheme = staticCompositionLocalOf {
    BackgroundSchemeFactory.fallback(AppearanceMode.AMOLED_BLACK)
}
