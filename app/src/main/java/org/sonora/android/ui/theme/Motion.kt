package org.sonora.android.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Motion preferences resolved from user settings + the system animator scale.
 *
 * The design system reads all animation timings through this object so that a single
 * toggle (Reduce Motion) can disable continuous effects (shimmer, parallax, spring
 * scale) without touching call sites.
 */
@Immutable
data class MotionPreferences(
    val reduceMotion: Boolean = false,
    val vinylRotation: Boolean = true
) {
    val microMs: Int get() = if (reduceMotion) 0 else Durations.MICRO
    val standardMs: Int get() = if (reduceMotion) 0 else Durations.STANDARD
    val navigationMs: Int get() = if (reduceMotion) 0 else Durations.NAVIGATION
    val backgroundMs: Int get() = if (reduceMotion) 0 else Durations.BACKGROUND

    /** Press target scale for cards/buttons. Never scales under Reduce Motion. */
    val pressScale: Float get() = if (reduceMotion) 1f else 0.97f

    /** Continuous shimmer is disallowed entirely under Reduce Motion. */
    val allowContinuousMotion: Boolean get() = !reduceMotion

    fun <T> tween(spec: FiniteAnimationSpec<T> = tween(Durations.STANDARD, easing = FastOutSlowInEasing), durationMs: Int): FiniteAnimationSpec<T> {
        if (reduceMotion || durationMs <= 0) return tween(0)
        @Suppress("UNCHECKED_CAST")
        return spec
    }

    /** Spring for physical elements (press, sheet, pill). Snaps when reduced. */
    fun springSnap(): FiniteAnimationSpec<Float> =
        if (reduceMotion) tween(0)
        else spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)

    companion object {
        val Default = MotionPreferences()
    }
}

val LocalMotionPreferences = staticCompositionLocalOf { MotionPreferences.Default }

@Composable
fun sonoraMotion(): MotionPreferences = LocalMotionPreferences.current
