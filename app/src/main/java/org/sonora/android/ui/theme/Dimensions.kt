package org.sonora.android.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Liquid Glass dimension tokens (radius + spacing scale).
 *
 * Radius is intentionally limited to 20/24/28.dp to keep a single visual language
 * across floating surfaces (cards, hero, sheets, nav).
 */
object Radius {
    val Small = 20.dp
    val Medium = 24.dp
    val Large = 28.dp
}

object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 40.dp

    /** Standard horizontal gutter for screen content. */
    val gutter = 16.dp

    /** Floating bottom navigation / mini-player height. */
    val barHeight = 64.dp
}

/** Motion durations in milliseconds (zeroed by Reduce Motion at read time). */
object Durations {
    const val MICRO = 180
    const val STANDARD = 240
    const val NAVIGATION = 320
    const val BACKGROUND = 380
}
