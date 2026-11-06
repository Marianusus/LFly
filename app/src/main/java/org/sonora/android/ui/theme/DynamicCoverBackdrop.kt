package org.sonora.android.ui.theme

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The playing cover as a living backdrop, as music players on the desktop draw it: the artwork
 * blurred to pure colour, in three copies that turn and drift past each other, so the colours flow
 * slowly without ever settling; a dark veil keeps text readable.
 *
 * Cheap on purpose: the cover is blurred once, on a 48 px copy, and every frame only moves those
 * three small bitmaps. No per-frame blur, so it runs on any Android version. Under Reduce Motion it
 * holds still. A new song fades in over the old one.
 */
@Composable
internal fun DynamicCoverBackdrop(
    coverUrl: String?,
    modifier: Modifier = Modifier,
    /** False when the caller lays its own scrim on top (lyrics, Ambient). */
    veil: Boolean = true
) {
    val context = LocalContext.current
    val motion = LocalMotionPreferences.current
    val blurred by rememberBlurredCover(context, coverUrl)

    Box(modifier = modifier.fillMaxSize()) {
        Crossfade(targetState = blurred, animationSpec = tween(if (motion.reduceMotion) 0 else 1100), label = "cover") { image ->
            if (image != null) FlowingCover(image, animate = motion.allowContinuousMotion)
        }
        // The veil: darker at the bottom, where the mini player and the bar sit.
        if (veil) Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.16f), Color.Black.copy(alpha = 0.26f), Color.Black.copy(alpha = 0.52f))
                    )
                )
        )
    }
}

@Composable
private fun FlowingCover(image: ImageBitmap, animate: Boolean) {
    val time = if (animate) {
        val transition = rememberInfiniteTransition(label = "flow")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(LOOP_MS, easing = LinearEasing), RepeatMode.Restart),
            label = "t"
        )
    } else {
        remember { mutableStateOf(0.18f) }
    }
    val saturate = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.6f) }) }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val t = time.value * 2f * Math.PI.toFloat()
        // Each layer is big enough that, turned and moved as far as it goes, its edges never reach the
        // screen (half the side >= half the screen's diagonal + the orbit): no straight lines show.
        drawLayer(image, saturate, t, turns = 1f, orbit = 0.16f, phase = 0f, scale = 1.9f, alpha = 1f)
        drawLayer(image, saturate, t, turns = -1f, orbit = 0.22f, phase = 2.1f, scale = 1.7f, alpha = 0.8f)
        drawLayer(image, saturate, t, turns = 2f, orbit = 0.26f, phase = 4.2f, scale = 1.6f, alpha = 0.65f)
    }
}

/**
 * One copy of the cover, larger than the screen, turning [turns] times per loop around a centre that
 * circles the middle at [orbit] of the screen. Whole turns per loop keep the motion seamless.
 */
private fun DrawScope.drawLayer(
    image: ImageBitmap,
    filter: ColorFilter,
    t: Float,
    turns: Float,
    orbit: Float,
    phase: Float,
    scale: Float,
    alpha: Float
) {
    val side = max(size.width, size.height) * scale
    val centre = Offset(
        size.width / 2f + cos(t + phase) * size.width * orbit,
        size.height / 2f + sin(t * 2f + phase) * size.height * orbit * 0.6f
    )
    withTransform({ rotate(degrees = t * turns * 180f / Math.PI.toFloat(), pivot = centre) }) {
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset((centre.x - side / 2f).toInt(), (centre.y - side / 2f).toInt()),
            dstSize = IntSize(side.toInt(), side.toInt()),
            alpha = alpha,
            colorFilter = filter,
            filterQuality = FilterQuality.High
        )
    }
}

/** One loop of the flow: slow enough to feel like weather, not like a screensaver. */
private const val LOOP_MS = 36_000

private const val BLUR_SIZE = 48

@Composable
private fun rememberBlurredCover(context: Context, url: String?): State<ImageBitmap?> =
    produceState<ImageBitmap?>(initialValue = url?.let { BlurredCovers.cache.get(it) }, url) {
        if (url.isNullOrBlank()) {
            value = null
            return@produceState
        }
        BlurredCovers.cache.get(url)?.let { value = it; return@produceState }
        val image = withContext(Dispatchers.Default) { BlurredCovers.load(context, url) }
        if (image != null) {
            BlurredCovers.cache.put(url, image)
            value = image
        }
    }

private object BlurredCovers {
    val cache = android.util.LruCache<String, ImageBitmap>(12)

    suspend fun load(context: Context, url: String): ImageBitmap? = runCatching {
        val result = context.imageLoader.execute(
            ImageRequest.Builder(context).data(url).size(BLUR_SIZE, BLUR_SIZE).allowHardware(false).build()
        ) as? SuccessResult ?: return null
        val source = (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            ?: Bitmap.createBitmap(BLUR_SIZE, BLUR_SIZE, Bitmap.Config.ARGB_8888).also { bitmap ->
                val canvas = android.graphics.Canvas(bitmap)
                result.drawable.setBounds(0, 0, BLUR_SIZE, BLUR_SIZE)
                result.drawable.draw(canvas)
            }
        // A mutable copy of our own: Coil's bitmap is shared with its memory cache.
        val scaled = Bitmap.createScaledBitmap(source, BLUR_SIZE, BLUR_SIZE, true).copy(Bitmap.Config.ARGB_8888, true)
        // A light blur on a tiny copy: drawn huge, the artwork keeps its colour regions (sky, skin,
        // stage lights) as soft shapes instead of melting into one colour.
        repeat(2) { boxBlur(scaled, radius = 2) }
        scaled.asImageBitmap()
    }.getOrNull()

    /** In-place box blur, horizontal then vertical, clamped at the edges. */
    private fun boxBlur(bitmap: Bitmap, radius: Int) {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        pass(pixels, out, w, h, radius, horizontal = true)
        pass(out, pixels, w, h, radius, horizontal = false)
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val length = if (horizontal) w else h
        val window = 2 * r + 1
        for (line in 0 until lines) {
            fun at(i: Int): Int {
                val k = i.coerceIn(0, length - 1)
                return if (horizontal) src[line * w + k] else src[k * w + line]
            }
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (i in -r..r) {
                val p = at(i)
                a += p ushr 24; rr += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
            }
            for (i in 0 until length) {
                val index = if (horizontal) line * w + i else i * w + line
                dst[index] = ((a / window) shl 24) or ((rr / window) shl 16) or ((g / window) shl 8) or (b / window)
                val leaving = at(i - r)
                val entering = at(i + r + 1)
                a += (entering ushr 24) - (leaving ushr 24)
                rr += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
                g += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
                b += (entering and 0xFF) - (leaving and 0xFF)
            }
        }
    }
}
