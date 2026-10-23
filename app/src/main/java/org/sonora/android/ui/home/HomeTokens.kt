package org.sonora.android.ui.home

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.pow

internal object HomeDimens {
    val Gutter = 20.dp
    val SectionGap = 32.dp
    val HeaderGap = 12.dp
    val GridGap = 12.dp
    val RailCardWidth = 160.dp
    val HeroArt = 104.dp
    val ShelfArt = 64.dp
    val ChartArt = 52.dp
    val CardRadius = 20.dp
    val HeroRadius = 24.dp
    val Target = 48.dp
    val CompactBar = 52.dp
    val ShelfPageHeight = 292.dp
}

internal fun homeShelfColumns(screenWidthDp: Int, fontScale: Float): Int =
    if (fontScale >= 1.3f || screenWidthDp < 340) 1 else 2

internal fun formatHomeTime(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) / 1000L)
    return String.format(Locale.US, "%d:%02d", totalSeconds / 60L, totalSeconds % 60L)
}

internal fun homeOnColor(background: Color): Color {
    val dark = Color(0xFF101012)
    return if (contrastRatio(dark, background) >= contrastRatio(Color.White, background)) dark else Color.White
}

internal fun contrastRatio(a: Color, b: Color): Double {
    val first = relativeLuminance(a)
    val second = relativeLuminance(b)
    val lighter = maxOf(first, second)
    val darker = minOf(first, second)
    return (lighter + 0.05) / (darker + 0.05)
}

private fun relativeLuminance(color: Color): Double {
    fun channel(value: Float): Double {
        val v = value.toDouble()
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(color.red) +
        0.7152 * channel(color.green) +
        0.0722 * channel(color.blue)
}
