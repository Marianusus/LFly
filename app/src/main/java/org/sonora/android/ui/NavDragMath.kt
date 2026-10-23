package org.sonora.android.ui

import kotlin.math.roundToInt

/**
 * Pure geometry for the draggable bottom-navigation bubble.
 *
 * Width and step are in pixels. The row is laid out as [count] equal-weight
 * items separated by [gapPx]; the bubble's travel is one item plus one gap.
 */
internal fun navTabWidthPx(rowWidthPx: Float, gapPx: Float, count: Int): Float {
    if (count <= 0 || rowWidthPx <= 0f) return 0f
    return ((rowWidthPx - gapPx * (count - 1)) / count).coerceAtLeast(0f)
}

internal fun navTabStepPx(rowWidthPx: Float, gapPx: Float, count: Int): Float {
    if (count <= 0 || rowWidthPx <= 0f) return 0f
    return ((rowWidthPx + gapPx) / count).coerceAtLeast(0f)
}

internal fun navDragTargetIndex(
    dragStartIndex: Int,
    totalDragPx: Float,
    stepPx: Float,
    lastIndex: Int
): Int {
    if (stepPx <= 0f || lastIndex < 0) return dragStartIndex.coerceIn(0, lastIndex.coerceAtLeast(0))
    return (dragStartIndex + totalDragPx / stepPx)
        .coerceIn(0f, lastIndex.toFloat())
        .roundToInt()
}

internal fun navDragOffsetPx(
    dragStartIndex: Int,
    totalDragPx: Float,
    stepPx: Float,
    selectedIndex: Int,
    lastIndex: Int
): Float {
    if (stepPx <= 0f || lastIndex < 0) return 0f
    val absolute = (dragStartIndex + totalDragPx / stepPx)
        .coerceIn(0f, lastIndex.toFloat())
    return (absolute - selectedIndex) * stepPx
}
