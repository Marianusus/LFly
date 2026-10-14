package org.sonora.android.ui.theme

import androidx.annotation.StringRes
import androidx.compose.runtime.staticCompositionLocalOf
import org.sonora.android.R

/**
 * User-selectable appearance mode ("Aspetto") — the M2 Sonora Liquid Glass themes.
 *
 * Unlike [ThemeStyle] (legacy accent presets), [AppearanceMode] decides the *base canvas*
 * and, for dynamic modes, derives a contextual gradient from the current cover artwork.
 */
enum class AppearanceMode(
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int
) {
    AMOLED_BLACK(
        titleRes = R.string.appearance_amoled_title,
        subtitleRes = R.string.appearance_amoled_subtitle
    ),
    PURE_WHITE(
        titleRes = R.string.appearance_white_title,
        subtitleRes = R.string.appearance_white_subtitle
    ),
    DYNAMIC_DARK(
        titleRes = R.string.appearance_dynamic_dark_title,
        subtitleRes = R.string.appearance_dynamic_dark_subtitle
    ),
    DYNAMIC_LIGHT(
        titleRes = R.string.appearance_dynamic_light_title,
        subtitleRes = R.string.appearance_dynamic_light_subtitle
    );

    /** Dynamic modes derive their gradient from the contextual cover artwork. */
    val isDynamic: Boolean
        get() = this == DYNAMIC_DARK || this == DYNAMIC_LIGHT

    /** Light canvas modes require dark-on-light text/iconography. */
    val isLight: Boolean
        get() = this == PURE_WHITE || this == DYNAMIC_LIGHT

    companion object {
        fun fromString(name: String?): AppearanceMode =
            entries.find { it.name.equals(name, ignoreCase = true) } ?: AMOLED_BLACK

        /**
         * One-time migration from the legacy [ThemeStyle] preference to an [AppearanceMode].
         * Pure so it can be unit-tested; the legacy preference itself is left intact.
         */
        fun migrateFromLegacy(themeStyleName: String?): AppearanceMode =
            when (ThemeStyle.fromString(themeStyleName)) {
                ThemeStyle.SONORA_AMOLED -> AMOLED_BLACK
                ThemeStyle.APPLE_LIQUID_GLASS, ThemeStyle.MIDNIGHT_NEON -> DYNAMIC_DARK
            }
    }
}

val LocalAppearanceMode = staticCompositionLocalOf { AppearanceMode.AMOLED_BLACK }
