package org.sonora.android.playback

internal fun audioVolumeFraction(value: Int, max: Int): Float {
    if (max <= 0) return 0f
    return (value.toFloat() / max.toFloat()).coerceIn(0f, 1f)
}

internal fun audioVolumeIndex(fraction: Float, max: Int): Int {
    if (max <= 0) return 0
    return (fraction.coerceIn(0f, 1f) * max).toInt().coerceIn(0, max)
}
