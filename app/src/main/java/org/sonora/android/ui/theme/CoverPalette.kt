package org.sonora.android.ui.theme

import android.content.Context
import android.graphics.Bitmap
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

/**
 * Two-color accent pair extracted from cover artwork.
 * Immutable so it can be safely cached and compared.
 */
@Immutable
data class CoverAccent(
    val primary: Color,
    val secondary: Color
)

/**
 * Bounded, thread-safe in-memory cache keyed by cover URL.
 *
 * The same [coverUrl] must never trigger a second pixel analysis: this cache is the
 * memoization point consumed by [rememberCoverAccent].
 */
class CoverAccentCache(private val maxEntries: Int = 32) {
    private val map = object : LinkedHashMap<String, CoverAccent>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CoverAccent>): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun get(url: String): CoverAccent? = map[url]

    @Synchronized
    fun put(url: String, accent: CoverAccent): CoverAccent {
        map[url] = accent
        return accent
    }

    @Synchronized
    fun getOrPut(url: String, compute: () -> CoverAccent?): CoverAccent? {
        map[url]?.let { return it }
        val computed = compute() ?: return null
        map[url] = computed
        return computed
    }

    @Synchronized
    fun clear() = map.clear()

    val size: Int get() = map.size
}

/** Process-wide cover accent cache (no disk, metadata-free). */
object CoverPalette {
    val cache = CoverAccentCache(maxEntries = 32)

    /**
     * Derives two accents from a decoded cover [bitmap] using a cheap saturation split.
     * Pure and deterministic for a given bitmap (no randomization).
     */
    fun accentFrom(bitmap: Bitmap): CoverAccent {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return fallback()

        val stepX = (width / 32).coerceAtLeast(1)
        val stepY = (height / 32).coerceAtLeast(1)

        var vividWarmR = 0L; var vividWarmG = 0L; var vividWarmB = 0L; var vividWarmN = 0
        var vividCoolR = 0L; var vividCoolG = 0L; var vividCoolB = 0L; var vividCoolN = 0
        var allR = 0L; var allG = 0L; var allB = 0L; var allN = 0

        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val px = bitmap.getPixel(x, y)
                val a = (px ushr 24) and 0xFF
                if (a < 32) { x += stepX; continue }
                val r = (px ushr 16) and 0xFF
                val g = (px ushr 8) and 0xFF
                val b = px and 0xFF
                val max = maxOf(r, g, b)
                val min = minOf(r, g, b)
                val sat = if (max <= 0) 0f else (max - min).toFloat() / max.toFloat()
                val luma = (0.299f * r + 0.587f * g + 0.114f * b) / 255f

                allR += r; allG += g; allB += b; allN++

                if (sat >= 0.22f && luma in 0.10f..0.94f) {
                    if (r >= b) {
                        vividWarmR += r; vividWarmG += g; vividWarmB += b; vividWarmN++
                    } else {
                        vividCoolR += r; vividCoolG += g; vividCoolB += b; vividCoolN++
                    }
                }
                x += stepX
            }
            y += stepY
        }

        if (allN == 0) return fallback()

        val primary = when {
            vividWarmN > 0 -> Color((vividWarmR / vividWarmN).toInt(), (vividWarmG / vividWarmN).toInt(), (vividWarmB / vividWarmN).toInt())
            vividCoolN > 0 -> Color((vividCoolR / vividCoolN).toInt(), (vividCoolG / vividCoolN).toInt(), (vividCoolB / vividCoolN).toInt())
            else -> Color((allR / allN).toInt(), (allG / allN).toInt(), (allB / allN).toInt())
        }
        val secondary = when {
            vividCoolN > 0 -> Color((vividCoolR / vividCoolN).toInt(), (vividCoolG / vividCoolN).toInt(), (vividCoolB / vividCoolN).toInt())
            vividWarmN > 0 -> Color((vividWarmR / vividWarmN).toInt(), (vividWarmG / vividWarmN).toInt(), (vividWarmB / vividWarmN).toInt())
            else -> mixColor(primary, Color.White, 0.35f)
        }
        return CoverAccent(primary = primary, secondary = secondary)
    }

    fun fallback(): CoverAccent = CoverAccent(
        primary = BackgroundSchemeFactory.FallbackViolet,
        secondary = BackgroundSchemeFactory.FallbackBlue
    )
}

/** Loads + analyzes a cover URL through the app-wide Coil [ImageLoader]. Returns null on failure. */
internal suspend fun loadCoverAccent(context: Context, url: String): CoverAccent? {
    return try {
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(64, 64)
            .allowHardware(false)
            .build()
        val result = context.imageLoader.execute(request)
        if (result is SuccessResult) {
            val bitmap = result.drawable.toBitmapCompat() ?: return null
            CoverPalette.accentFrom(bitmap)
        } else null
    } catch (_: Throwable) {
        null
    }
}

private fun android.graphics.drawable.Drawable.toBitmapCompat(): Bitmap? {
    if (this is android.graphics.drawable.BitmapDrawable) return bitmap
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

/**
 * Composition-safe accessor: memoizes by [url], recomputes only on URL change, and is
 * fully cancellable (LaunchedEffect keyed on url). Returns null immediately on blank input
 * so callers can fall back without waiting.
 */
@Composable
fun rememberCoverAccent(url: String?): CoverAccent? {
    if (url.isNullOrBlank()) return null
    val context = LocalContext.current
    var accent by remember(url) { mutableStateOf(CoverPalette.cache.get(url)) }
    LaunchedEffect(url) {
        if (accent == null) {
            val loaded = loadCoverAccent(context, url)
            if (loaded != null) accent = CoverPalette.cache.put(url, loaded)
        }
    }
    return accent
}
