package org.sonora.android.ui.theme

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.sonora.android.R

/** A selectable accent colour option shown in Settings → Appearance. */
data class AccentOption(
    val id: String,
    @StringRes val nameRes: Int,
    val color: Color
)

/**
 * Curated accent palette used to replace the fixed fallback violet on the non-dynamic
 * (Black AMOLED / White) canvases. Dynamic canvases keep their cover-derived accent.
 */
object AccentPalette {

    val options: List<AccentOption> = listOf(
        AccentOption("emerald", R.string.accent_emerald, Color(0xFF1DB954)),
        AccentOption("blue", R.string.accent_blue, Color(0xFF4D7CFF)),
        AccentOption("indigo", R.string.accent_indigo, Color(0xFF5B5BD6)),
        AccentOption("violet", R.string.accent_violet, Color(0xFF7C4DFF)),
        AccentOption("pink", R.string.accent_pink, Color(0xFFFF2D78)),
        AccentOption("red", R.string.accent_red, Color(0xFFFF3B30)),
        AccentOption("orange", R.string.accent_orange, Color(0xFFFF7A00)),
        AccentOption("amber", R.string.accent_amber, Color(0xFFFFB300)),
        AccentOption("teal", R.string.accent_teal, Color(0xFF00BFA5)),
        AccentOption("cyan", R.string.accent_cyan, Color(0xFF00B8D9))
    )

    /** Default accent (Sonora brand emerald). */
    val DEFAULT: AccentOption = options.first()

    fun defaultArgb(): Int = DEFAULT.color.toArgb()

    fun fromArgb(argb: Int): AccentOption =
        options.firstOrNull { it.color.toArgb() == argb } ?: DEFAULT
}
