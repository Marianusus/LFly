package org.sonora.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import sh.calvin.reorderable.ReorderableCollectionItemScope

/** The gestures of a row that can be moved: [row] for the whole row, [handle] for its grip. */
internal class HoldToMove(val row: Modifier, val handle: Modifier)

/**
 * A queue row as in Apple Music: a tap plays, holding lifts the row so it can be moved, and holding
 * without moving opens the song's menu when the finger lets go. The grip moves the row at once.
 */
@Composable
internal fun ReorderableCollectionItemScope.rememberHoldToMove(onTap: () -> Unit, onHeld: () -> Unit): HoldToMove {
    val haptics = LocalHapticFeedback.current
    val tap by rememberUpdatedState(onTap)
    val held by rememberUpdatedState(onHeld)
    val gesture = remember { HoldGesture() }
    val row = Modifier
        .onGloballyPositioned { gesture.coordinates = it }
        .pointerInput(Unit) {
            // Only watches: how far the finger travels on screen (the lifted row follows it, so
            // positions inside the row would not show it).
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                gesture.lifted = false
                gesture.travelled = false
                val start = gesture.onScreen(down.position)
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (start != null) {
                        val now = gesture.onScreen(change.position)
                        if (now != null && (now - start).getDistance() > viewConfiguration.touchSlop) gesture.travelled = true
                    }
                    if (!change.pressed) break
                }
            }
        }
        .longPressDraggableHandle(
            onDragStarted = {
                gesture.lifted = true
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            },
            onDragStopped = { if (!gesture.travelled) held() }
        )
        .clickable { if (!gesture.lifted) tap() }
    val handle = Modifier.draggableHandle(
        onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    )
    return HoldToMove(row, handle)
}

private class HoldGesture {
    var coordinates: LayoutCoordinates? = null
    var lifted = false
    var travelled = false

    fun onScreen(local: Offset): Offset? = coordinates?.takeIf { it.isAttached }?.localToWindow(local)
}
