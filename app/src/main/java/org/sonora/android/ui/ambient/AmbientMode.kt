package org.sonora.android.ui.ambient

/**
 * Ambient mode is the StandBy-style landscape screen of the full player.
 *
 * It opens by rotating the phone (when the setting is on) or from the player menu. Closing it
 * while the phone is still in landscape must stick until the phone returns to portrait.
 */
internal fun shouldShowAmbient(
    hasTrack: Boolean,
    isPlayerExpanded: Boolean,
    isLandscape: Boolean,
    rotationEnabled: Boolean,
    openedFromMenu: Boolean,
    dismissed: Boolean
): Boolean {
    if (!hasTrack || !isPlayerExpanded || dismissed) return false
    return openedFromMenu || (isLandscape && rotationEnabled)
}

/** Burn-in protection: a slow walk through small offsets, in dp, for always-on OLED content. */
internal fun ambientBurnInOffset(step: Long): Pair<Int, Int> =
    BURN_IN_PATH[Math.floorMod(step, BURN_IN_PATH.size.toLong()).toInt()]

/** Splits a sweep over wrapped text: each row fills only after the one above it is complete. */
internal fun lyricRevealRows(rowWidths: List<Float>, reveal: Float): List<Float> {
    var remaining = reveal.coerceIn(0f, 1f) * rowWidths.sum()
    return rowWidths.map { width ->
        if (width <= 0f) return@map 0f
        val shown = minOf(remaining, width)
        remaining -= shown
        shown / width
    }
}

internal const val AMBIENT_BURN_IN_STEP_MS = 60_000L
internal const val AMBIENT_CONTROLS_TIMEOUT_MS = 4_000L

private val BURN_IN_PATH = listOf(
    0 to 0,
    5 to -3,
    -4 to 4,
    6 to 2,
    -6 to -2,
    3 to 5,
    -3 to -5,
    2 to 3
)
