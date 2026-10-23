package org.sonora.android.ui.player

/** Release decision for the mini-player pull gesture. */
internal fun shouldOpenPlayerSheet(progress: Float, velocityY: Float): Boolean =
    progress >= 0.25f || velocityY <= -900f
