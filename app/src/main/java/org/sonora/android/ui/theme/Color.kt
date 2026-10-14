package org.sonora.android.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ==========================================
// OpenDesign Standard Tokens
// ==========================================
val OpenDesignPureBlack = Color(0xFF000000)
val OpenDesignSurfaceGlass = Color(0xFFFFFFFF).copy(alpha = 0.08f)
val OpenDesignBorderGlass = Color(0xFFFFFFFF).copy(alpha = 0.15f)
val OpenDesignSurfaceGlassDark = Color(0xCC121212)
val OpenDesignBorderGlassDark = Color(0xFFFFFFFF).copy(alpha = 0.12f)
val OpenDesignTextPrimary = Color(0xFFFFFFFF)
val OpenDesignTextSecondary = Color(0xB3FFFFFF) // 70% white
val OpenDesignTextTertiary = Color(0x73FFFFFF)  // 45% white

// ==========================================
// Static Base Color Constants
// ==========================================

// Sonora AMOLED Theme Base Constants (Pitch Black #000000 + Frosted Glass Highlights)
val AmoledBlack = Color(0xFF000000)
val AmoledSurfaceCard = Color(0xFFFFFFFF).copy(alpha = 0.08f)
val AmoledSurfaceHighlight = Color(0xFFFFFFFF).copy(alpha = 0.14f)
val AmoledSurfaceSubtle = Color(0xFFFFFFFF).copy(alpha = 0.04f)
val AmoledEmerald = Color(0xFF1DB954)
val AmoledPurple = Color(0xFF8A2BE2)
val AmoledPurpleLight = Color(0xFFA855F7)
val AmoledEmeraldDark = Color(0xFF15803D)
val AmoledDivider = Color(0xFFFFFFFF).copy(alpha = 0.15f)

// Apple Music Liquid Glass Base Constants
// Translucent frosted glass card backgrounds (0xFFFFFFFF.copy(alpha = 0.08f))
val LiquidGlassCardBackground = Color(0xFFFFFFFF).copy(alpha = 0.08f)
// Subtle glossy border highlights (0xFFFFFFFF.copy(alpha = 0.15f))
val LiquidGlassCardBorder = Color(0xFFFFFFFF).copy(alpha = 0.15f)
val LiquidGlassSurfaceHighlight = Color(0xFFFFFFFF).copy(alpha = 0.14f)
val LiquidGlassSurfaceSubtle = Color(0xFFFFFFFF).copy(alpha = 0.04f)
val LiquidGlassBackground = Color(0xFF0C0A17)
val LiquidGlassNavBar = Color(0x59161428)
val LiquidGlassNavBarBorder = Color(0x33FFFFFF)

// Apple Music style pink/cyan accents
val AppleMusicPink = Color(0xFFFA2D48)
val AppleMusicPinkGlow = Color(0xFFFF2D55)
val AppleMusicCyan = Color(0xFF00E5FF)
val AppleMusicCyanGlow = Color(0xFF00C7BE)
val AppleMusicPurple = Color(0xFF7B2CBF)

// Liquid ambient gradient colors
val LiquidAmbientPink = Color(0x55FA2D48)
val LiquidAmbientCyan = Color(0x4400E5FF)
val LiquidAmbientPurple = Color(0x557B2CBF)
val LiquidAmbientDeep = Color(0xFF0C0A17)

// Midnight Neon Base Constants
val MidnightNeonBackground = Color(0xFF070712)
val MidnightNeonCardBackground = Color(0xFFFFFFFF).copy(alpha = 0.08f)
val MidnightNeonCardBorder = Color(0x4D8A2BE2)
val MidnightNeonSurfaceHighlight = Color(0xFFFFFFFF).copy(alpha = 0.14f)
val MidnightNeonSurfaceSubtle = Color(0xFF0E0E20)
val MidnightNeonNavBar = Color(0xDB0B0B18)
val MidnightNeonNavBarBorder = Color(0x33BD00FF)
val MidnightNeonPrimary = Color(0xFFB026FF)
val MidnightNeonSecondary = Color(0xFF00F0FF)
val MidnightNeonPurpleLight = Color(0xFFD946EF)
val MidnightNeonAmbient1 = Color(0x55B026FF)
val MidnightNeonAmbient2 = Color(0x4400F0FF)

// Text & Content Colors — appearance-aware.
// On AMOLED/dark canvases these resolve to the 100/70/45% white scale; on light
// canvases (PURE_WHITE / DYNAMIC_LIGHT) they resolve to high-contrast dark ink.
val SonoraTextPrimary: Color
    @Composable get() = SonoraTheme.colors.textPrimary
val SonoraTextSecondary: Color
    @Composable get() = SonoraTheme.colors.textSecondary
val SonoraTextTertiary: Color
    @Composable get() = SonoraTheme.colors.textTertiary

// Status Colors
val SonoraStatusOnline = Color(0xFF1DB954)
val SonoraStatusDegraded = Color(0xFFEAB308)
val SonoraStatusOffline = Color(0xFFEF4444)

// UI Component Colors
val SonoraOverlay = Color(0x99000000)

// ==========================================
// Dynamic Theme Colors (Bound to SonoraTheme)
// ==========================================

val SonoraAmoledBlack: Color
    @Composable
    get() = SonoraTheme.colors.background

val SonoraSurfaceCard: Color
    @Composable
    get() = SonoraTheme.colors.surfaceCard

val SonoraSurfaceHighlight: Color
    @Composable
    get() = SonoraTheme.colors.surfaceHighlight

val SonoraSurfaceSubtle: Color
    @Composable
    get() = SonoraTheme.colors.surfaceSubtle

/**
 * An opaque version of [SonoraSurfaceCard]: the translucent glass tint composited over the app
 * background. Used where a see-through panel would be wrong (e.g. import dialogs).
 */
val SonoraOpaqueSurfaceCard: Color
    @Composable
    get() = SonoraTheme.colors.surfaceCard.over(SonoraTheme.colors.background)

/**
 * Source-over compositing of a translucent [this] on top of [base], returning an opaque colour
 * that keeps the tint but no longer lets the content behind show through.
 */
internal fun Color.over(base: Color): Color {
    val a = alpha
    return Color(
        red = red * a + base.red * (1f - a),
        green = green * a + base.green * (1f - a),
        blue = blue * a + base.blue * (1f - a),
        alpha = 1f
    )
}

val SonoraEmerald: Color
    @Composable
    get() = SonoraTheme.colors.primaryAccent

val SonoraPurple: Color
    @Composable
    get() = SonoraTheme.colors.secondaryAccent

val SonoraPurpleLight: Color
    @Composable
    get() = when (SonoraTheme.style) {
        ThemeStyle.APPLE_LIQUID_GLASS -> AppleMusicPinkGlow
        ThemeStyle.MIDNIGHT_NEON -> MidnightNeonPurpleLight
        ThemeStyle.SONORA_AMOLED -> AmoledPurpleLight
    }

val SonoraEmeraldDark: Color
    @Composable
    get() = when (SonoraTheme.style) {
        ThemeStyle.APPLE_LIQUID_GLASS -> AppleMusicPink
        ThemeStyle.MIDNIGHT_NEON -> MidnightNeonPrimary
        ThemeStyle.SONORA_AMOLED -> AmoledEmeraldDark
    }

val SonoraDivider: Color
    @Composable
    get() = SonoraTheme.colors.cardBorder

val SonoraGlassBackground: Color
    @Composable
    get() = SonoraTheme.colors.surfaceCard
