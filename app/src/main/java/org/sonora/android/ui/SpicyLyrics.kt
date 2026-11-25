package org.sonora.android.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import org.sonora.android.api.WordTiming
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The lyrics look of Spicy Lyrics (github.com/Spikerko/spicy-lyrics, AGPL-3.0), written again
 * from scratch: only its numbers are taken (curves, spring tunings, opacities, blur steps).
 *
 * - Every word sweeps from dim to lit with a soft edge as it is sung, and three springs follow
 *   it: it grows (0.95 → 1.05 at 70% → 1), lifts and settles, glows and fades.
 * - A word held a second or more goes letter by letter, each letter growing more (1.175).
 * - The sung line is full white; the others sit at half and blur by their distance from it.
 * - The pauses breathe with three dots; the background is the cover, turning, saturated.
 */

/** A damped spring with a closed-form step (critically and under-damped), frequency in Hz. */
internal class SpicySpring(start: Float, private val frequency: Float, private val damping: Float) {
    var position = start
        private set
    private var velocity = 0f
    var goal = start

    fun snap(to: Float) {
        position = to
        goal = to
        velocity = 0f
    }

    fun step(dt: Float): Float {
        if (dt <= 0f) return position
        val f = frequency * 2f * PI.toFloat()
        val o = position - goal
        val v = velocity
        if (damping >= 0.999f) {
            val q = exp(-f * dt)
            val w = dt * q
            position = o * (q + w * f) + v * w + goal
            velocity = v * (q - w * f) - o * (w * f * f)
        } else {
            val q = exp(-damping * f * dt)
            val c = sqrt(1f - damping * damping)
            val i = cos(dt * f * c)
            val j = sin(dt * f * c)
            val z = j / c
            val y = j / (f * c)
            position = (o * (i + z * damping) + v * y) * q + goal
            velocity = (v * (i - z * damping) - o * (z * f)) * q
        }
        if (kotlin.math.abs(position - goal) < 1e-4f && kotlin.math.abs(velocity) < 1e-3f) {
            position = goal
            velocity = 0f
        }
        return position
    }
}

/** A smooth curve through (time, value) points: each span eased with a cosine. */
internal class SpicyCurve(private vararg val points: Pair<Float, Float>) {
    fun at(t: Float): Float {
        if (t <= points.first().first) return points.first().second
        for (k in 1 until points.size) {
            val (t1, v1) = points[k]
            if (t <= t1) {
                val (t0, v0) = points[k - 1]
                val x = (t - t0) / (t1 - t0)
                val e = (1f - cos(x * PI.toFloat())) / 2f
                return v0 + (v1 - v0) * e
            }
        }
        return points.last().second
    }
}

internal object Spicy {
    val Scale = SpicyCurve(0f to 0.95f, 0.7f to 1.0505f, 1f to 1f)
    val LetterScale = SpicyCurve(0f to 0.95f, 0.7f to 1.175f, 1f to 1f)
    /** In font sizes: positive is down. */
    val Lift = SpicyCurve(0f to 0.01f, 0.9f to -1f / 60f, 1f to 0f)
    val LetterLift = SpicyCurve(0f to 0.01f, 0.9f to -1f / 56f, 1f to 0f)
    val Glow = SpicyCurve(0f to 0f, 0.15f to 1f, 0.6f to 1f, 1f to 0f)

    /** Words sung over a second or longer go letter by letter. */
    const val LETTER_MIN_MS = 1_000L

    const val LIT = 0.85f
    const val DIM = 0.5f
    const val SUNG_LINE = 0.5f
    const val NEXT_LINE = 0.51f

    /** Blur of a line [distance] lines from the sung one, in dp. */
    fun blur(distance: Int): Dp = if (distance == 0) 0.dp else (1.25f * distance).coerceAtMost(6.8f).dp

    fun scaleSpring() = SpicySpring(Scale.at(0f), 0.88f, 0.64f)
    fun liftSpring() = SpicySpring(Lift.at(0f), 1.45f, 0.4f)
    fun glowSpring() = SpicySpring(0f, 1.18f, 0.56f)

    /**
     * The sweep: lit up to [position] (0..1 of the width, may start below 0), then a 20% fade
     * to dim. Below 0 it is all dim, past 1 all lit.
     */
    fun sweep(position: Float, lit: Color, dim: Color): Brush {
        val a = position
        val b = position + 0.2f
        if (b <= 0f) return SolidColor(dim)
        if (a >= 1f) return SolidColor(lit)
        fun at(x: Float) = lerp(lit, dim, ((x - a) / 0.2f).coerceIn(0f, 1f))
        val stops = buildList {
            add(0f to at(0f))
            if (a > 0f) add(a to lit)
            if (b < 1f) add(b to dim)
            add(1f to at(1f))
        }
        return Brush.horizontalGradient(*stops.toTypedArray())
    }
}

private fun easeSinOut(x: Float) = sin(x.coerceIn(0f, 1f) * PI.toFloat() / 2f)

/** Where a line stands against the clock. */
internal enum class SpicyState { NotSung, Active, Sung }

/** One frame's clock for the springs: the time of the frame in seconds, while [running]. */
@Composable
private fun rememberFrameSeconds(running: Boolean): Float {
    var nanos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        while (true) withFrameNanos { nanos = it }
    }
    return nanos / 1e9f
}

/** The springs of one word, or of each of its letters. */
private class WordMotion(letters: Int) {
    val scale = Spicy.scaleSpring()
    val lift = Spicy.liftSpring()
    val glow = Spicy.glowSpring()
    val letterScale = List(letters) { SpicySpring(Spicy.LetterScale.at(0f), 0.88f, 0.64f) }
    val letterLift = List(letters) { SpicySpring(Spicy.LetterLift.at(0f), 1.45f, 0.4f) }
    val letterGlow = List(letters) { Spicy.glowSpring() }
}

/**
 * A line in Spicy's look. [words] are its timed words (real or estimated); with none the line
 * sweeps as one word from [lineStartMs] to [lineEndMs].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SpicyLine(
    text: String,
    words: List<WordTiming>,
    lineStartMs: Long,
    lineEndMs: Long,
    positionMs: Long,
    state: SpicyState,
    style: TextStyle,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val tokens = remember(text, words) {
        if (words.isNotEmpty()) words else listOf(WordTiming(text, lineStartMs, lineEndMs.coerceAtLeast(lineStartMs + 1)))
    }
    val letterCapable = remember(tokens) {
        tokens.map { it.endMs - it.startMs >= Spicy.LETTER_MIN_MS && it.text.length in 2..12 && words.isNotEmpty() }
    }
    val motions = remember(tokens) { tokens.mapIndexed { i, w -> WordMotion(if (letterCapable[i]) w.text.length else 0) } }
    val live = state == SpicyState.Active && !reduceMotion
    // Springs keep moving a little after the line ends, so they settle instead of snapping.
    // Plain holders, not state: written while composing, read only here.
    val clock = remember { LongArray(2) } // [settle until (ms of the song), last frame (µs)]
    if (state == SpicyState.Active) clock[0] = positionMs + 1_500L
    val running = live || (!reduceMotion && positionMs < clock[0])
    val now = rememberFrameSeconds(running)
    val frameKey = (now * 1e6f).toLong()
    val dt = if (clock[1] == 0L || !running) 0f else ((frameKey - clock[1]) / 1e6f).coerceIn(0f, 0.064f)
    clock[1] = frameKey

    val fontPx = with(LocalDensity.current) { style.fontSize.toPx() }
    val white = Color.White
    val lit = white.copy(alpha = Spicy.LIT)
    val dim = white.copy(alpha = Spicy.DIM)

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy((style.fontSize.value * 0.22f).dp),
    ) {
        tokens.forEachIndexed { i, w ->
            val m = motions[i]
            val span = (w.endMs - w.startMs).coerceAtLeast(1L).toFloat()
            val p = when (state) {
                SpicyState.NotSung -> 0f
                SpicyState.Sung -> 1f
                SpicyState.Active -> ((positionMs - w.startMs) / span).coerceIn(0f, 1f)
            }
            val before = state == SpicyState.Active && positionMs < w.startMs
            val after = state == SpicyState.Sung || (state == SpicyState.Active && positionMs >= w.endMs)
            val goalT = if (before) 0f else if (after) 1f else p
            m.scale.goal = Spicy.Scale.at(goalT)
            m.lift.goal = Spicy.Lift.at(goalT)
            m.glow.goal = if (before || after) 0f else Spicy.Glow.at(p)
            if (running) {
                m.scale.step(dt); m.lift.step(dt); m.glow.step(dt)
            } else {
                m.scale.snap(m.scale.goal); m.lift.snap(m.lift.goal); m.glow.snap(m.glow.goal)
            }
            val sweepAt = if (before) -0.2f else if (after) 1f else -0.2f + 1.2f * p

            if (letterCapable[i] && state == SpicyState.Active) {
                // Letter by letter: each letter has its share of the word's time.
                val n = w.text.length
                Row(
                    modifier = Modifier.graphicsLayer {
                        translationY = m.lift.position * fontPx
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                ) {
                    w.text.forEachIndexed { k, ch ->
                        val lp = ((p * n) - k).coerceIn(0f, 1f)
                        val lBefore = before || (p * n) < k
                        val lAfter = after || (p * n) >= k + 1
                        val ls = m.letterScale[k]; val ll = m.letterLift[k]; val lg = m.letterGlow[k]
                        val t = if (lBefore) 0f else if (lAfter) 1f else lp
                        ls.goal = Spicy.LetterScale.at(t)
                        ll.goal = Spicy.LetterLift.at(t)
                        lg.goal = if (lBefore) 0f else Spicy.Glow.at(t)
                        if (running) { ls.step(dt); ll.step(dt); lg.step(dt) } else { ls.snap(ls.goal); ll.snap(ll.goal); lg.snap(lg.goal) }
                        val g = lg.position.coerceIn(0f, 1f)
                        val letterSweep = if (lBefore) -0.2f else if (lAfter) 1f else -0.2f + 1.2f * easeSinOut(lp)
                        androidx.compose.material3.Text(
                            text = ch.toString(),
                            style = style.copy(
                                brush = Spicy.sweep(letterSweep, lit, dim),
                                shadow = if (g > 0.01f) Shadow(white.copy(alpha = (g * 0.9f).coerceAtMost(0.9f)), Offset.Zero, 4f + 12f * g) else null,
                            ),
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.graphicsLayer {
                                scaleX = ls.position
                                scaleY = ls.position
                                translationY = ll.position * 2f * fontPx
                                transformOrigin = TransformOrigin(0.5f, 1f)
                            },
                        )
                    }
                }
            } else {
                val g = m.glow.position.coerceIn(0f, 1f)
                val brush = when (state) {
                    SpicyState.Active -> Spicy.sweep(sweepAt, lit, dim)
                    else -> SolidColor(white)
                }
                androidx.compose.material3.Text(
                    text = w.text,
                    style = style.copy(
                        brush = brush,
                        shadow = if (g > 0.01f) Shadow(white.copy(alpha = (g * 0.35f)), Offset.Zero, 4f + 2f * g) else null,
                    ),
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.graphicsLayer {
                        scaleX = m.scale.position
                        scaleY = m.scale.position
                        translationY = m.lift.position * fontPx
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    },
                )
            }
        }
    }
}

/**
 * A pause between lines: three dots that light one after another over its length and
 * breathe while it lasts.
 */
@Composable
internal fun SpicyInterlude(startMs: Long, endMs: Long, positionMs: Long, active: Boolean, reduceMotion: Boolean, modifier: Modifier = Modifier) {
    val breathe = rememberInfiniteTransition(label = "spicyBreath")
    val phase by breathe.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2_400, easing = LinearEasing), RepeatMode.Restart),
        label = "spicyBreathPhase",
    )
    val span = (endMs - startMs).coerceAtLeast(1L).toFloat()
    val fill = if (active) ((positionMs - startMs) / span).coerceIn(0f, 1f) else 0f
    // Grows while breathing, shrinks away in its last 400 ms.
    val exit = if (active) ((endMs - positionMs) / 400f).coerceIn(0f, 1f) else 1f
    val scale = if (!active || reduceMotion) 1f else (1f + 0.08f * sin(phase * 2f * PI.toFloat())) * (0.6f + 0.4f * exit)
    Row(
        modifier = modifier
            .padding(vertical = 10.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
            },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { k ->
            val f = ((fill * 3f) - k).coerceIn(0f, 1f)
            val a = if (active) 0.35f + 0.65f * f else 0.35f
            Box(
                Modifier
                    .size(12.dp)
                    .background(Color.White.copy(alpha = a), CircleShape)
            )
        }
    }
}

/**
 * Spicy's background: the cover twice, large, turning slowly in opposite directions, blurred,
 * saturated ×2.5 and darkened to 65%.
 */
@Composable
internal fun SpicyBackground(artworkUrl: String?, reduceMotion: Boolean, modifier: Modifier = Modifier) {
    val spin = rememberInfiniteTransition(label = "spicyBg")
    val turn by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(90_000, easing = LinearEasing), RepeatMode.Restart),
        label = "spicyBgTurn",
    )
    val filter = remember {
        val m = ColorMatrix().apply { setToSaturation(2.5f) }
        val b = 0.65f
        val bright = ColorMatrix(floatArrayOf(b, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
        m.timesAssign(bright)
        ColorFilter.colorMatrix(m)
    }
    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black)) {
        if (artworkUrl.isNullOrBlank()) return@BoxWithConstraints
        val side = maxOf(maxWidth, maxHeight) * 1.6f
        val angle = if (reduceMotion) 0f else turn
        listOf(Triple(Alignment.TopStart, 1f, 0f), Triple(Alignment.BottomEnd, -0.75f, 0.55f)).forEach { (align, dir, alpha) ->
            AsyncImage(
                model = artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = filter,
                modifier = Modifier
                    .align(align)
                    .requiredSize(side)
                    .graphicsLayer {
                        rotationZ = angle * dir
                        this.alpha = if (alpha == 0f) 1f else alpha
                    }
                    .blur(110.dp),
            )
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.22f)))
    }
}

/** Fades the content out over [edge] at the top and the bottom (Spicy's mask). */
internal fun Modifier.spicyFadeEdges(edge: Dp = 64.dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val e = edge.toPx().coerceAtMost(size.height / 3f)
        val inner = 16.dp.toPx()
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                (inner / size.height) to Color.Transparent,
                ((inner + e) / size.height) to Color.Black,
                (1f - (inner + e) / size.height) to Color.Black,
                (1f - inner / size.height) to Color.Transparent,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }
