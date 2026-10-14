package org.sonora.android.ui.theme

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * Dynamic color palette and styling definitions for Sonora Android themes.
 */
@Immutable
data class SonoraThemeColors(
    val background: Color,
    val surfaceCard: Color,
    val surfaceHighlight: Color,
    val surfaceSubtle: Color,
    val cardBorder: Color,
    val primaryAccent: Color,
    val secondaryAccent: Color,
    val navBarBackground: Color,
    val navBarBorder: Color,
    val textPrimary: Color = Color(0xFFFFFFFF),
    val textSecondary: Color = Color(0xB3FFFFFF),
    val textTertiary: Color = Color(0x73FFFFFF),
    val isLiquidGlass: Boolean = true,
    val ambientGradients: List<Color> = emptyList(),
    val pillButtons: Boolean = false,
    val cardShape: RoundedCornerShape = RoundedCornerShape(20.dp),
    val buttonShape: RoundedCornerShape = RoundedCornerShape(14.dp)
)

fun getSonoraColors(style: ThemeStyle): SonoraThemeColors = when (style) {
    ThemeStyle.SONORA_AMOLED -> SonoraThemeColors(
        background = AmoledBlack, // Pure AMOLED Pitch Black 0xFF000000
        surfaceCard = AmoledSurfaceCard, // 0xFFFFFFFF.copy(alpha = 0.08f) frosted glass
        surfaceHighlight = AmoledSurfaceHighlight, // 0xFFFFFFFF.copy(alpha = 0.14f)
        surfaceSubtle = AmoledSurfaceSubtle,
        cardBorder = AmoledDivider, // 0xFFFFFFFF.copy(alpha = 0.15f) luminous border
        primaryAccent = AmoledEmerald,
        secondaryAccent = AmoledPurple,
        navBarBackground = Color(0xDB000000), // Frosted glass nav bar over pitch black
        navBarBorder = Color(0xFFFFFFFF).copy(alpha = 0.15f),
        isLiquidGlass = true,
        ambientGradients = listOf(
            AmoledPurple.copy(alpha = 0.20f),
            Color(0xFF000000).copy(alpha = 0.90f),
            Color(0xFF000000)
        ),
        pillButtons = false,
        cardShape = RoundedCornerShape(20.dp),
        buttonShape = RoundedCornerShape(14.dp)
    )
    ThemeStyle.APPLE_LIQUID_GLASS -> SonoraThemeColors(
        background = LiquidGlassBackground,
        surfaceCard = LiquidGlassCardBackground,
        surfaceHighlight = LiquidGlassSurfaceHighlight,
        surfaceSubtle = LiquidGlassSurfaceSubtle,
        cardBorder = LiquidGlassCardBorder,
        primaryAccent = AppleMusicPink,
        secondaryAccent = AppleMusicCyan,
        navBarBackground = LiquidGlassNavBar,
        navBarBorder = LiquidGlassNavBarBorder,
        isLiquidGlass = true,
        ambientGradients = listOf(
            LiquidAmbientPink,
            LiquidAmbientPurple,
            LiquidAmbientCyan,
            LiquidAmbientDeep
        ),
        pillButtons = true,
        cardShape = RoundedCornerShape(22.dp),
        buttonShape = RoundedCornerShape(50.dp) // Apple Music rounded pill buttons
    )
    ThemeStyle.MIDNIGHT_NEON -> SonoraThemeColors(
        background = MidnightNeonBackground,
        surfaceCard = MidnightNeonCardBackground,
        surfaceHighlight = MidnightNeonSurfaceHighlight,
        surfaceSubtle = MidnightNeonSurfaceSubtle,
        cardBorder = MidnightNeonCardBorder,
        primaryAccent = MidnightNeonPrimary,
        secondaryAccent = MidnightNeonSecondary,
        navBarBackground = MidnightNeonNavBar,
        navBarBorder = MidnightNeonNavBarBorder,
        isLiquidGlass = true,
        ambientGradients = listOf(
            MidnightNeonAmbient1,
            MidnightNeonAmbient2,
            MidnightNeonBackground
        ),
        pillButtons = false,
        cardShape = RoundedCornerShape(20.dp),
        buttonShape = RoundedCornerShape(14.dp)
    )
}

fun createColorScheme(colors: SonoraThemeColors): ColorScheme = darkColorScheme(
    primary = colors.primaryAccent,
    onPrimary = Color.White,
    primaryContainer = colors.surfaceHighlight,
    onPrimaryContainer = colors.primaryAccent,
    secondary = colors.secondaryAccent,
    onSecondary = Color.White,
    secondaryContainer = colors.surfaceHighlight,
    onSecondaryContainer = colors.secondaryAccent,
    background = colors.background,
    onBackground = colors.textPrimary,
    surface = colors.surfaceCard,
    onSurface = colors.textPrimary,
    surfaceVariant = colors.surfaceHighlight,
    onSurfaceVariant = colors.textSecondary,
    outline = colors.cardBorder,
    error = SonoraStatusOffline,
    onError = colors.textPrimary
)

val LocalSonoraColors = staticCompositionLocalOf { getSonoraColors(ThemeStyle.SONORA_AMOLED) }
val LocalThemeStyle = staticCompositionLocalOf { ThemeStyle.SONORA_AMOLED }

/**
 * User-selected accent colour (Settings → Appearance). Applies to the non-dynamic
 * (Black AMOLED / White) canvases; dynamic canvases keep their cover-derived accent.
 */
val LocalUserAccent = staticCompositionLocalOf { AccentPalette.DEFAULT.color }

object SonoraTheme {
    val colors: SonoraThemeColors
        @Composable
        get() = LocalSonoraColors.current

    val style: ThemeStyle
        @Composable
        get() = LocalThemeStyle.current

    val cardShape: RoundedCornerShape
        @Composable
        get() = LocalSonoraColors.current.cardShape

    val buttonShape: RoundedCornerShape
        @Composable
        get() = LocalSonoraColors.current.buttonShape
}

/**
 * Adapts the legacy [ThemeStyle] palette to the active Liquid Glass [BackgroundScheme]
 * (canvas, glass tint, accents and text contrast) without breaking existing call sites.
 */
private fun SonoraThemeColors.withAppearance(scheme: BackgroundScheme): SonoraThemeColors = copy(
    background = scheme.gradient.last(),
    surfaceCard = scheme.glassTint,
    surfaceHighlight = scheme.glassBorder,
    surfaceSubtle = scheme.glassTint.copy(alpha = scheme.glassTint.alpha * 0.5f),
    cardBorder = scheme.glassBorder,
    primaryAccent = scheme.accent,
    secondaryAccent = scheme.accentSecondary,
    navBarBackground = scheme.glassTint.copy(alpha = 0.85f),
    navBarBorder = scheme.glassBorder,
    isLiquidGlass = true,
    ambientGradients = scheme.gradient,
    textPrimary = scheme.onBackground,
    textSecondary = scheme.onBackgroundSecondary,
    textTertiary = scheme.onBackgroundTertiary
)

@Composable
fun SonoraTheme(
    themeStyle: ThemeStyle = ThemeStyle.SONORA_AMOLED,
    appearanceMode: AppearanceMode = AppearanceMode.AMOLED_BLACK,
    accentColor: Color = AccentPalette.DEFAULT.color,
    backgroundScheme: BackgroundScheme? = null,
    motionPreferences: MotionPreferences? = null,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val scheme = backgroundScheme ?: remember(appearanceMode, accentColor) {
        // Non-dynamic canvases use the user-selected accent; dynamic canvases derive it
        // from cover artwork inside SonoraBackground.
        val accent = if (appearanceMode.isDynamic) null else accentColor
        BackgroundSchemeFactory.build(appearanceMode, accent, accent)
    }
    val colors = remember(themeStyle, scheme) { getSonoraColors(themeStyle).withAppearance(scheme) }
    val motion = motionPreferences ?: MotionPreferences.Default
    val colorScheme = remember(colors) { createColorScheme(colors) }
    val shapes = remember(colors) {
        Shapes(
            extraSmall = RoundedCornerShape(4.dp),
            small = RoundedCornerShape(8.dp),
            medium = colors.buttonShape,
            large = colors.cardShape,
            extraLarge = if (colors.pillButtons) RoundedCornerShape(32.dp) else RoundedCornerShape(24.dp)
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        val lightSystemBars = scheme.isLight
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                // Edge-to-edge on every API level: keep both system bars transparent so the
                // app canvas (gradient + ambient glow) extends behind them. Setting a solid
                // colour here produces a flat bar on API 31-34, where these setters still apply.
                window.statusBarColor = Color.Transparent.toArgb()
                window.navigationBarColor = Color.Transparent.toArgb()
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = lightSystemBars
                    isAppearanceLightNavigationBars = lightSystemBars
                }
            }
        }
    }

    CompositionLocalProvider(
        LocalSonoraColors provides colors,
        LocalThemeStyle provides themeStyle,
        LocalAppearanceMode provides appearanceMode,
        LocalUserAccent provides accentColor,
        LocalBackgroundScheme provides scheme,
        LocalMotionPreferences provides motion
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = shapes,
            typography = SonoraTypography,
            content = content
        )
    }
}

// ==========================================
// Liquid Glass UI Helpers (OpenDesign Standard)
// ==========================================

/**
 * OpenDesign Liquid Glass modifier.
 * Applies a translucent frosted glass surface with a subtle luminous border
 * and smooth rounded corners (16.dp - 24.dp).
 */
fun Modifier.liquidGlass(
    shape: Shape = RoundedCornerShape(20.dp),
    backgroundColor: Color = Color(0xFFFFFFFF).copy(alpha = 0.08f),
    borderColor: Color = Color(0xFFFFFFFF).copy(alpha = 0.15f),
    borderWidth: androidx.compose.ui.unit.Dp = 1.dp
): Modifier = this
    .clip(shape)
    .background(backgroundColor, shape)
    .border(borderWidth, borderColor, shape)

/**
 * OpenDesign Dark Liquid Glass modifier.
 * Used for floating bars or panels where dark translucency is preferred on AMOLED screens.
 */
fun Modifier.liquidGlassDark(
    shape: Shape = RoundedCornerShape(20.dp),
    backgroundColor: Color = Color(0xCC121212),
    borderColor: Color = Color(0xFFFFFFFF).copy(alpha = 0.15f),
    borderWidth: androidx.compose.ui.unit.Dp = 1.dp
): Modifier = this
    .clip(shape)
    .background(backgroundColor, shape)
    .border(borderWidth, borderColor, shape)

fun Modifier.glassCard(
    shape: Shape = RoundedCornerShape(20.dp),
    backgroundColor: Color = LiquidGlassCardBackground,
    borderColor: Color = LiquidGlassCardBorder
): Modifier = this.liquidGlass(
    shape = shape,
    backgroundColor = backgroundColor,
    borderColor = borderColor,
    borderWidth = 1.dp
)

@Composable
fun Modifier.sonoraCard(
    shape: Shape = SonoraTheme.cardShape,
    border: BorderStroke? = BorderStroke(1.dp, SonoraTheme.colors.cardBorder)
): Modifier = this
    .clip(shape)
    .background(SonoraTheme.colors.surfaceCard, shape)
    .then(
        if (border != null) {
            Modifier.border(border.width, border.brush, shape)
        } else Modifier
    )

@Composable
fun Modifier.liquidAmbientBackground(): Modifier {
    val isGlass = SonoraTheme.colors.isLiquidGlass
    return if (isGlass) {
        this.background(
            Brush.verticalGradient(
                colors = SonoraTheme.colors.ambientGradients
            )
        )
    } else {
        this.background(SonoraTheme.colors.background)
    }
}

@Composable
fun SonoraPillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(
        containerColor = SonoraTheme.colors.primaryAccent,
        contentColor = Color.White
    ),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = SonoraTheme.buttonShape,
        colors = colors,
        contentPadding = contentPadding,
        content = content
    )
}

@Composable
fun SonoraGlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = SonoraTheme.cardShape,
    border: BorderStroke? = BorderStroke(1.dp, SonoraTheme.colors.cardBorder),
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = SonoraTheme.colors.surfaceCard
        ),
        border = border,
        content = content
    )
}
