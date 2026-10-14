package org.sonora.android.ui.theme

/**
 * Visual design systems supported by Sonora Android.
 *
 * - [SONORA_AMOLED]: Pitch black AMOLED-optimized dark palette with emerald accents.
 * - [APPLE_LIQUID_GLASS]: iOS Apple Music inspired frosted translucent glass (0x2EFFFFFF),
 *   subtle glossy highlights (0x33FFFFFF), liquid ambient gradients, rounded pill buttons,
 *   and vibrant pink/cyan neon accents.
 * - [MIDNIGHT_NEON]: Deep cyber midnight blue-violet with electric neon glow.
 */
enum class ThemeStyle(
    val title: String,
    val subtitle: String
) {
    SONORA_AMOLED(
        title = "Sonora AMOLED Pitch Black",
        subtitle = "Pure pitch black with emerald accents & maximum contrast"
    ),
    APPLE_LIQUID_GLASS(
        title = "Apple Music Liquid Glass",
        subtitle = "Frosted glass translucency, glossy borders & liquid pink/cyan glow"
    ),
    MIDNIGHT_NEON(
        title = "Midnight Neon",
        subtitle = "Deep midnight cyber tones with electric neon purple & cyan glow"
    );

    companion object {
        fun fromString(name: String?): ThemeStyle {
            return entries.find { it.name.equals(name, ignoreCase = true) } ?: SONORA_AMOLED
        }
    }
}
