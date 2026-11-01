package org.sonora.android.ui.ambient

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Keeps the screen on and the bars hidden while ambient is up, and forces landscape if asked. */
@Composable
internal fun AmbientWindowEffect(active: Boolean, forceLandscape: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return

    // Always undone on dispose, even across a recreate: a rotation can reuse the same Window, and
    // the new composition applies this again if ambient is still up.
    DisposableEffect(activity, active) {
        if (!active) return@DisposableEffect onDispose {}
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val previousBehavior = controller.systemBarsBehavior
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = previousBehavior
        }
    }

    val forced = active && forceLandscape
    DisposableEffect(activity, forced) {
        if (forced) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else if (activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
            // Nothing else requests this. It outlives the instance that set it, so it is a leftover
            // from an ambient that ended across a recreate or a process death.
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose {
            // The request lives on the activity record, which a recreate keeps. Clearing it here
            // would rotate back, force landscape again in the new instance, and loop.
            if (forced && !activity.isChangingConfigurations) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
