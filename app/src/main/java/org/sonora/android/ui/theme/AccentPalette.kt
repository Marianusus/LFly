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
        AccentOption("cyan", R.string.accent_cyan, Color(0xFF00B8D9)),

        // ── Premium ──────────────────────────────────────────────────────────
        AccentOption("champagne_gold", R.string.accent_champagne_gold, Color(0xFFD4AF37)),
        AccentOption("rose_gold", R.string.accent_rose_gold, Color(0xFFB76E79)),
        AccentOption("platinum", R.string.accent_platinum, Color(0xFFC9CCD1)),
        AccentOption("titanium", R.string.accent_titanium, Color(0xFF5C6670)),
        AccentOption("sapphire", R.string.accent_sapphire, Color(0xFF1F4FD8)),
        AccentOption("midnight", R.string.accent_midnight, Color(0xFF20304A)),
        AccentOption("burgundy", R.string.accent_burgundy, Color(0xFF8E1B3A)),
        AccentOption("bronze", R.string.accent_bronze, Color(0xFFA9714B)),
        AccentOption("obsidian", R.string.accent_obsidian, Color(0xFF3A3F44)),
        AccentOption("sand", R.string.accent_sand, Color(0xFFC2A878))
    )

    /** Default accent (Sonora brand emerald). */
    val DEFAULT: AccentOption = options.first()

    fun defaultArgb(): Int = DEFAULT.color.toArgb()

    fun fromArgb(argb: Int): AccentOption =
        options.firstOrNull { it.color.toArgb() == argb } ?: DEFAULT
}
