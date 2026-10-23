package org.sonora.android.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val MARQUEE_DP_PER_SEC = 26f
private val MARQUEE_GAP = 48.dp
private const val MARQUEE_REST_MS = 4_000L

/**
 * BitChord-style marquee: measure once, draw two copies, slide the pair.
 * Unlike `basicMarquee` it starts from the true left edge and never shows a
 * clipped or centred first frame.
 */
@Composable
fun MarqueeText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    startDelayMillis: Long = 1_200L,
    onOverflowChange: (Boolean) -> Unit = {},
) {
    if (!enabled) {
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier
        )
        return
    }
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    BoxWithConstraints(
        modifier = modifier.clipToBounds()
    ) {
        val maxWidthPx = constraints.maxWidth
        val layout = remember(text, style, maxWidthPx) {
            textMeasurer.measure(
                text = text,
                style = style,
                maxLines = 1,
                softWrap = false
            )
        }
        val overflowing = layout.size.width > maxWidthPx
        LaunchedEffect(overflowing) { onOverflowChange(overflowing) }
        val travelPx = if (overflowing) {
            layout.size.width + with(density) { MARQUEE_GAP.roundToPx() }
        } else 0
        val offsetX = remember { Animatable(0f) }
        LaunchedEffect(text, travelPx, startDelayMillis) {
            offsetX.snapTo(0f)
            if (travelPx <= 0) return@LaunchedEffect
            val pxPerMs = with(density) { MARQUEE_DP_PER_SEC.dp.toPx() } / 1000f
            val scrollMs = (travelPx / pxPerMs).roundToInt().coerceAtLeast(500)
            delay(startDelayMillis)
            while (true) {
                offsetX.animateTo(
                    targetValue = -travelPx.toFloat(),
                    animationSpec = tween(scrollMs, easing = LinearEasing)
                )
                offsetX.snapTo(0f)
                delay(MARQUEE_REST_MS)
            }
        }
        Row(
            modifier = Modifier
                .wrapContentWidth(align = Alignment.Start, unbounded = true)
                .offset { IntOffset(offsetX.value.roundToInt(), 0) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            MarqueeLine(text, style, color)
            if (overflowing) {
                Spacer(Modifier.width(MARQUEE_GAP))
                MarqueeLine(text, style, color)
            }
        }
    }
}

@Composable
private fun MarqueeLine(text: String, style: TextStyle, color: Color) {
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
    )
}
