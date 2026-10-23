package org.sonora.android.ui.player.v2

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Two vertical colours sampled from the top and bottom of the artwork. */
@Immutable
data class ArtworkGradient(
    val top: Color,
    val bottom: Color,
    /** Less-shaded colour from the artwork's bottom edge, for seam blending. */
    val edge: Color = bottom
)

/**
 * Bounded, thread-safe cache keyed by cover URL. A track change must never re-run the
 * pixel analysis twice for the same image.
 */
class ArtworkGradientCache(private val maxEntries: Int = 64) {
    private val map = object : LinkedHashMap<String, ArtworkGradient>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArtworkGradient>): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun get(url: String): ArtworkGradient? = map[url]

    @Synchronized
    fun put(url: String, value: ArtworkGradient): ArtworkGradient {
        map[url] = value
        return value
    }

    @Synchronized
    fun clear() = map.clear()

    val size: Int
        @Synchronized get() = map.size
}

object ArtworkGradients {
    val cache = ArtworkGradientCache()

    /** Dark fallback used while the artwork loads or when it has no usable colours. */
    fun fallback(accent: Color = Color(0xFF3A3A40)): ArtworkGradient {
        val top = shadeColor(accent, maxValue = 0.34f, scale = 0.75f)
        val bottom = shadeColor(Color(0xFF0B0B0F), maxValue = 0.10f, scale = 1f)
        val edge = shadeColor(accent, maxValue = 0.58f, scale = 0.9f)
        return ArtworkGradient(top = top, bottom = bottom, edge = edge)
    }

    /**
     * iOS 26 Now Playing style: one gradient from the artwork's top edge to its bottom
     * edge, both shaded enough for white controls to stay readable on pale covers.
     */
    fun fromBitmap(bitmap: Bitmap): ArtworkGradient {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return fallback()

        val band = (height * 0.15f).toInt().coerceAtLeast(1)
        val rawBottom = averageBand(bitmap, height - band, height)
        val top = shadeArgb(averageBand(bitmap, 0, band), maxValue = 0.47f, scale = 0.80f)
        val bottom = shadeArgb(rawBottom, maxValue = 0.28f, scale = 0.50f)
        val edge = shadeArgb(rawBottom, maxValue = 0.72f, scale = 0.95f)
        return ArtworkGradient(Color(top), Color(bottom), Color(edge))
    }

    private fun averageBand(bitmap: Bitmap, fromY: Int, toY: Int): Int {
        val width = bitmap.width
        val start = fromY.coerceIn(0, bitmap.height - 1)
        val end = toY.coerceIn(start + 1, bitmap.height)
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0L
        for (y in start until end) {
            for (x in 0 until width) {
                val c = bitmap.getPixel(x, y)
                val a = (c ushr 24) and 0xFF
                if (a < 32) continue
                r += (c shr 16) and 0xFF
                g += (c shr 8) and 0xFF
                b += c and 0xFF
                n++
            }
        }
        if (n == 0L) return 0xFF202024.toInt()
        return (0xFF shl 24) or
            (((r / n).toInt() and 0xFF) shl 16) or
            (((g / n).toInt() and 0xFF) shl 8) or
            ((b / n).toInt() and 0xFF)
    }
}

/**
 * Pure ARGB shade used by [ArtworkGradients]. Keeps saturated artwork colourful while
 * capping luminance so controls remain legible. Exposed for JVM tests.
 */
internal fun shadeArgb(argb: Int, maxValue: Float, scale: Float): Int {
    val r0 = (argb shr 16) and 0xFF
    val g0 = (argb shr 8) and 0xFF
    val b0 = argb and 0xFF
    return shadeRgb(r0, g0, b0, maxValue, scale)
}

internal fun shadeColor(color: Color, maxValue: Float, scale: Float): Color {
    val argb = (color.alpha * 255f).roundToInt().coerceIn(0, 255) shl 24 or
        (color.red * 255f).roundToInt().coerceIn(0, 255) shl 16 or
        (color.green * 255f).roundToInt().coerceIn(0, 255) shl 8 or
        (color.blue * 255f).roundToInt().coerceIn(0, 255)
    return Color(shadeArgb(argb, maxValue, scale))
}

private fun shadeRgb(red0: Int, green0: Int, blue0: Int, maxValue: Float, scale: Float): Int {
    val high = max(red0, max(green0, blue0)).toFloat()
    val low = min(red0, min(green0, blue0)).toFloat()
    val saturation = if (high <= 0f) 0f else ((high - low) / high * 1.15f).coerceAtMost(1f)
    val value = (high * scale).coerceAtMost(maxValue * 255f)

    fun channel(c: Int): Int {
        val hue = if (high == low) 0f else (c - low) / (high - low)
        return (value * (1f - saturation + saturation * hue)).roundToInt().coerceIn(0, 255)
    }

    var red = channel(red0)
    var green = channel(green0)
    var blue = channel(blue0)

    fun linear(c: Int): Double {
        val n = c / 255.0
        return if (n <= 0.04045) n / 12.92 else Math.pow((n + 0.055) / 1.055, 2.4)
    }

    fun luminance(red: Int, green: Int, blue: Int): Double =
        0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue)

    while (luminance(red, green, blue) > 0.183) {
        red = (red * 0.98f).roundToInt()
        green = (green * 0.98f).roundToInt()
        blue = (blue * 0.98f).roundToInt()
    }
    return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
}

/** Composition-safe gradient accessor; recomputes only when the cover URL changes. */
@Composable
fun rememberArtworkGradient(url: String?, fallbackAccent: Color): ArtworkGradient {
    val context = LocalContext.current
    val fallback = remember(fallbackAccent) { ArtworkGradients.fallback(fallbackAccent) }
    var gradient by remember(url) {
        mutableStateOf(url?.let { ArtworkGradients.cache.get(it) } ?: fallback)
    }

    LaunchedEffect(url, fallback) {
        if (url.isNullOrBlank()) {
            gradient = fallback
            return@LaunchedEffect
        }
        ArtworkGradients.cache.get(url)?.let {
            gradient = it
            return@LaunchedEffect
        }
        val loaded = loadArtworkGradient(context, url)
        gradient = if (loaded != null) ArtworkGradients.cache.put(url, loaded) else fallback
    }
    return gradient
}

private suspend fun loadArtworkGradient(context: Context, url: String): ArtworkGradient? {
    return try {
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(64, 64)
            .allowHardware(false)
            .build()
        val result = context.imageLoader.execute(request)
        if (result is SuccessResult) {
            val bitmap = result.drawable.toBitmapCompat() ?: return null
            ArtworkGradients.fromBitmap(bitmap)
        } else null
    } catch (_: Throwable) {
        null
    }
}

private fun android.graphics.drawable.Drawable.toBitmapCompat(): Bitmap? {
    if (this is BitmapDrawable) return bitmap
    return try {
        val w = intrinsicWidth.coerceAtLeast(1)
        val h = intrinsicHeight.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        setBounds(0, 0, w, h)
        draw(canvas)
        bmp
    } catch (_: Throwable) {
        null
    }
}
