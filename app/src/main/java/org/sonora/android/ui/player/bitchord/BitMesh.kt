package org.sonora.android.ui.player.bitchord

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Immutable
data class BitMeshPalette(val colors: List<Color>)

private val Fallback = listOf(
    Color(0xFF3A1C71),
    Color(0xFFD76D77),
    Color(0xFF2B5876),
    Color(0xFFFFAF7B),
)

@Composable
fun rememberBitMesh(url: String?, accent: Color = Fallback[0]): BitMeshPalette {
    val context = LocalContext.current
    var palette by remember(url) { mutableStateOf(BitMeshPalette(Fallback)) }
    LaunchedEffect(url) {
        if (url.isNullOrBlank()) return@LaunchedEffect
        val found = withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(160)
                    .allowHardware(false)
                    .build()
                val result = context.imageLoader.execute(request)
                val bitmap = (result as? SuccessResult)?.drawable?.toBitmap() ?: return@runCatching null
                colorsOf(bitmap, accent)
            }.getOrNull()
        } ?: return@LaunchedEffect
        palette = BitMeshPalette(found)
    }
    return palette
}

private fun colorsOf(bitmap: Bitmap, accent: Color): List<Color> {
    val palette = Palette.from(bitmap).clearFilters().generate()
    val found = palette.swatches
        .sortedByDescending { it.population }
        .map { Color(it.rgb) }
        .distinctBy { (it.red * 8).toInt() * 100 + (it.green * 8).toInt() * 10 + (it.blue * 8).toInt() }
    val colors = found.take(4).toMutableList()
    while (colors.size < 4) {
        val seed = colors.getOrNull(colors.size % 3) ?: accent
        colors += if (colors.size % 2 == 0) {
            lerp(seed, accent, 0.45f)
        } else {
            lerp(seed, Color.White, 0.18f)
        }
    }
    val tuned = colors.map { it.tuned() }
    return tuned.mapIndexed { index, color ->
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color.toArgb(), hsl)
        if (hsl[1] < 0.10f) {
            val fallback = if (index % 2 == 0) accent else lerp(accent, Color.White, 0.22f)
            lerp(color, fallback, 0.55f)
        } else color
    }
}

private fun Color.tuned(): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(toArgb(), hsl)
    hsl[1] = (hsl[1] * 1.35f).coerceAtMost(1f)
    hsl[2] = hsl[2].coerceIn(0.28f, 0.58f)
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun Color.dimmed(): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(toArgb(), hsl)
    hsl[2] = 0.12f
    return Color(ColorUtils.HSLToColor(hsl))
}

@Composable
fun BitMeshBackground(
    palette: BitMeshPalette,
    modifier: Modifier = Modifier
) {
    val base = (palette.colors.firstOrNull() ?: Fallback.first()).dimmed()
    val first by animateColorAsState(palette.colors[0], tween(1200), label = "mesh0")
    val second by animateColorAsState(palette.colors[1], tween(1200), label = "mesh1")
    val third by animateColorAsState(palette.colors[2], tween(1200), label = "mesh2")
    val fourth by animateColorAsState(palette.colors[3], tween(1200), label = "mesh3")
    val baseColor by animateColorAsState(base, tween(1200), label = "meshBase")
    val phase = remember { Animatable(0f) }

    LaunchedEffect(palette) {
        phase.snapTo(0f)
        phase.animateTo((PI * 0.45f).toFloat(), tween(9000, easing = FastOutSlowInEasing))
    }

    Canvas(
        modifier = modifier
            .clipToBounds()
            .graphicsLayer {
                scaleX = 1.22f
                scaleY = 1.22f
            }
            .background(baseColor)
            .blur(56.dp)
    ) {
        val colors = listOf(first, second, third, fourth)
        val anchors = listOf(
            Offset(0.20f, 0.25f),
            Offset(0.80f, 0.20f),
            Offset(0.75f, 0.80f),
            Offset(0.25f, 0.75f)
        )
        val speeds = listOf(1f, -0.7f, 0.85f, -1.15f)
        val drift = phase.value
        colors.forEachIndexed { index, color ->
            val anchor = anchors[index]
            val center = Offset(
                x = (anchor.x + 0.16f * cos(drift * speeds[index] + index * 1.7f)) * size.width,
                y = (anchor.y + 0.16f * sin(drift * speeds[index] * 0.9f + index * 2.3f)) * size.height,
            )
            val radius = size.maxDimension * 0.62f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = 0.9f), color.copy(alpha = 0f)),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }
        drawRect(
            brush = Brush.verticalGradient(
                listOf(Color.Black.copy(alpha = 0.18f), Color.Black.copy(alpha = 0.46f))
            )
        )
    }
}
