package org.sonora.android.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.View
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.sonora.android.R
import android.os.SystemClock
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.foundation.border
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.sonora.android.ui.components.backdrop.Backdrop
import org.sonora.android.ui.components.backdrop.drawBackdrop
import org.sonora.android.ui.components.backdrop.effects.blur
import org.sonora.android.ui.components.backdrop.effects.colorControls
import org.sonora.android.ui.components.backdrop.effects.lens
import org.sonora.android.ui.components.backdrop.highlight.Highlight
import org.sonora.android.ui.components.backdrop.isLiquidGlassSupported
import org.sonora.android.ui.components.backdrop.shadow.Shadow
import androidx.compose.ui.draw.blur
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.sonora.android.playback.dj.DjBlock
import org.sonora.android.playback.dj.DjState
import org.sonora.android.ui.home.DjSpeakingBars
import org.sonora.android.ui.home.djBlockLabel
import org.sonora.android.ui.theme.LocalBackgroundScheme
import org.sonora.android.ui.theme.LocalMotionPreferences

/** Roughly how fast the phone's voice reads, to light the caption up word by word. */
private const val WORD_MS = 330L

private const val WORD_TICK_MS = 60L

/** Lines of the caption under the lens before it is cut short. */
private const val MAX_LINES = 5

private val CaptionStyle = TextStyle(fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium)

/** The lens: nearly the screen's width, rounded like the iPhone's island. */
private val LensSide = 14.dp
private val LensRadius = 42.dp

/** Under the camera, then the words, then a band of clear glass where the light runs. */
private val TextTop = 6.dp
private val ClearBand = 30.dp

/** Used for the first frame only, before the words have been measured. */
private val TextFallback = 60.dp

/**
 * The DJ talking, like Siri on the newest iPhones: a lens of clear glass drops out of the front
 * camera, black where it meets the camera and clear below, bending whatever is behind it, with a
 * thin streak of light running through it while the voice speaks. What the DJ says is written in a
 * small dark glass card under the lens. Everything goes back into the camera when the line is over.
 */
@Composable
fun DjIsland(state: DjState, backdrop: Backdrop? = null, modifier: Modifier = Modifier) {
    val visible = state.speaking && !state.lastLine.isNullOrBlank()
    // Keeps the last line on screen while the lens draws back in.
    var shownLine by remember { mutableStateOf<String?>(null) }
    var shownBlock by remember { mutableStateOf<DjBlock?>(null) }
    if (visible) {
        shownLine = state.lastLine
        shownBlock = state.block
    }

    val motion = LocalMotionPreferences.current
    val drop = remember { DropMotion() }
    LaunchedEffect(visible, motion.reduceMotion) {
        if (visible) drop.open(motion.reduceMotion) else drop.close(motion.reduceMotion)
    }
    HideStatusBarWhile(visible)

    val line = shownLine
    if (drop.isGone || line == null) return

    val camera = cameraHole()
    val shimmer = shimmer(active = visible && !motion.reduceMotion)
    val density = LocalDensity.current
    // The lens is as tall as its words really take, as laid out with the app's own font.
    var textHeight by remember(line) { mutableStateOf(0.dp) }
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val lensWidth = maxWidth - LensSide * 2
        val textBlock = if (textHeight > 0.dp) textHeight else TextFallback
        val lensHeight = camera.height + TextTop + textBlock + ClearBand
        val bead = maxOf(camera.height * 1.5f, 40.dp)
        val swell = drop.swell.value
        val width = lerp(lerp(camera.width, bead, swell), lensWidth, drop.width.value)
        val height = lerp(lerp(camera.height, bead, swell), lensHeight, drop.height.value)
        val centreX = lerp(camera.centreX, maxWidth / 2, drop.width.value)
        val radius = minOf(height / 2, LensRadius)
        val shape = RoundedCornerShape(radius)
        val open = drop.height.value.coerceIn(0f, 1f)
        // Black from the camera down past the words, then clear glass for the last band.
        val darkUntil = ((height - ClearBand) / height).coerceIn(0f, 1f)
        val textAlpha = drop.content.value

        Box(
            modifier = Modifier
                .offset(x = centreX - width / 2, y = camera.top)
                .size(width = width, height = height)
                .graphicsLayer {
                    // At the very end the lens slips into the camera: it shrinks into the hole and fades.
                    val last = (drop.swell.value / VANISH_AT).coerceIn(0f, 1f)
                    alpha = last
                    val breathe = 1f + 0.012f * shimmer.level * open
                    scaleX = (0.55f + 0.45f * last) * breathe
                    scaleY = (0.55f + 0.45f * last) * breathe
                    transformOrigin = TransformOrigin(0.5f, 0f)
                }
                .lensGlass(shape, backdrop, darkTop = darkUntil)
                .semantics { liveRegion = LiveRegionMode.Polite }
        ) {
            if (open > 0.3f) {
                LightStreak(
                    shimmer = shimmer,
                    centreFromBottom = ClearBand / 2,
                    alpha = ((open - 0.3f) / 0.7f) * shimmer.glow * 0.85f
                )
            }
            // Laid out from the first frame, invisible until there is room, so its height is known in time.
            LensText(
                line = line,
                block = shownBlock,
                voiceStartedAtMs = state.voiceStartedAtMs,
                animateWords = !motion.reduceMotion,
                modifier = Modifier
                    .requiredWidth(lensWidth)
                    .wrapContentHeight(align = Alignment.Top, unbounded = true)
                    .padding(start = 22.dp, end = 20.dp, top = camera.height + TextTop)
                    .onSizeChanged { textHeight = with(density) { it.height.toDp() } }
                    .graphicsLayer {
                        alpha = textAlpha
                        translationY = -(1f - textAlpha) * 8.dp.toPx()
                    }
            )
        }
    }
}

/**
 * How the lens moves, like a drop of water: it swells out of the camera into a bead, the bead runs
 * sideways, then falls open and settles; the caption follows once the lens is out. Closing runs the
 * other way: caption away, the lens flattens, pulls in and slips back into the camera.
 */
private class DropMotion {
    val swell = Animatable(0f)
    val width = Animatable(0f)
    val height = Animatable(0f)
    val content = Animatable(0f)

    val isGone: Boolean get() = swell.value <= 0.001f && !swell.isRunning

    suspend fun open(instant: Boolean) = coroutineScope {
        if (instant) {
            swell.snapTo(1f); width.snapTo(1f); height.snapTo(1f); content.snapTo(1f)
            return@coroutineScope
        }
        swell.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 700f))
        launch { width.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 300f)) }
        delay(80)
        launch { height.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 240f, visibilityThreshold = 0.002f)) }
        delay(220)
        content.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = 320f))
    }

    suspend fun close(instant: Boolean) = coroutineScope {
        if (instant) {
            content.snapTo(0f); height.snapTo(0f); width.snapTo(0f); swell.snapTo(0f)
            return@coroutineScope
        }
        content.animateTo(0f, tween(180, easing = FastOutLinearInEasing))
        val flatten = launch { height.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = 420f)) }
        delay(60)
        // While it pulls in, the bead it would round into shrinks too, so it lands on the camera's size.
        val pull = launch { width.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 380f)) }
        swell.animateTo(VANISH_AT, tween(320, easing = FastOutSlowInEasing))
        pull.join()
        flatten.join()
        swell.animateTo(0f, tween(140, easing = FastOutLinearInEasing))
    }
}

/**
 * Clear glass that bends what is behind it, black at the top where it meets the camera. Without
 * real glass (setting off or an older phone) it is a dark bead that clears towards the bottom.
 */
@Composable
private fun Modifier.lensGlass(shape: RoundedCornerShape, backdrop: Backdrop?, darkTop: Float): Modifier {
    val density = LocalDensity.current
    val cameraShade: DrawScope.() -> Unit = {
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Black,
                (darkTop - 0.05f).coerceAtLeast(0f) to Color.Black,
                (darkTop + 0.1f).coerceAtMost(1f) to Color.Black.copy(alpha = 0f)
            )
        )
    }
    // Light catching the drop: bright along the bottom curve, faint on the top.
    val glint = Modifier.drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.verticalGradient(
                0.55f to Color.Transparent,
                1f to Color.White.copy(alpha = 0.10f)
            )
        )
    }
    val rim = Brush.verticalGradient(
        0f to Color.White.copy(alpha = 0.10f),
        0.6f to Color.White.copy(alpha = 0.22f),
        1f to Color.White.copy(alpha = 0.65f)
    )
    if (backdrop != null && isLiquidGlassSupported()) {
        val blurPx = with(density) { 1.5.dp.toPx() } * LENS_SCALE
        val lensHeightPx = with(density) { 30.dp.toPx() } * LENS_SCALE
        val lensAmountPx = with(density) { 64.dp.toPx() } * LENS_SCALE
        return this
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    colorControls(brightness = 0.02f, saturation = 1.2f)
                    blur(blurPx)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        lens(
                            refractionHeight = lensHeightPx,
                            refractionAmount = lensAmountPx,
                            depthEffect = true,
                            chromaticAberration = true
                        )
                    }
                },
                highlight = { Highlight(width = 1.25.dp) },
                shadow = { Shadow.Default },
                onDrawSurface = { cameraShade() },
                backdropScale = LENS_SCALE
            )
            .then(glint)
            .border(0.75.dp, rim, shape)
    }
    return this
        .shadow(16.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
        .clip(shape)
        .background(Color.Black.copy(alpha = 0.55f))
        .drawBehind(cameraShade)
        .then(glint)
        .border(0.75.dp, rim, shape)
}

private const val LENS_SCALE = 0.6f

/** Below this much swell the lens is back at the camera's size and only fades into the hole. */
private const val VANISH_AT = 0.18f

/** The thin bar of light across the lens, the way light splits through a drop of water. */
@Composable
private fun LightStreak(shimmer: Shimmer, centreFromBottom: Dp, alpha: Float) {
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha.coerceIn(0f, 1f) }
            .blur(3.dp, BlurredEdgeTreatment.Rectangle)
    ) {
        val bandHeight = 5.dp.toPx()
        val centreY = size.height - centreFromBottom.toPx()
        val drift = size.width * 0.06f * shimmer.drift
        val left = size.width * 0.2f + drift
        val right = size.width * 0.8f + drift
        drawOval(
            brush = Brush.horizontalGradient(
                0f to Color.Transparent,
                0.18f to Color(0xFFFFB86B),
                0.38f to Color(0xFFFF5E7E),
                0.55f to Color(0xFF9F7BFF),
                0.74f to Color(0xFF5AB4FF),
                0.88f to Color(0xFF9BF6FF),
                1f to Color.Transparent,
                startX = left,
                endX = right
            ),
            topLeft = Offset(left, centreY - bandHeight / 2),
            size = Size(right - left, bandHeight)
        )
        // A white core keeps it looking like light, not paint.
        drawOval(
            brush = Brush.horizontalGradient(
                0f to Color.Transparent,
                0.5f to Color.White.copy(alpha = 0.7f),
                1f to Color.Transparent,
                startX = left + (right - left) * 0.25f,
                endX = right - (right - left) * 0.25f
            ),
            topLeft = Offset(left + (right - left) * 0.25f, centreY - bandHeight / 4),
            size = Size((right - left) * 0.5f, bandHeight / 2)
        )
    }
}

/** How the light moves while the DJ talks: it drifts along the lens and brightens with the voice. */
private class Shimmer(val drift: Float, val glow: Float, val level: Float)

@Composable
private fun shimmer(active: Boolean): Shimmer {
    if (!active) return Shimmer(drift = 0f, glow = 0.85f, level = 0f)
    val transition = rememberInfiniteTransition(label = "djShimmer")
    val drift by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2_400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "djShimmerDrift"
    )
    val slow by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(520, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "djShimmerSlow"
    )
    val fast by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(190, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "djShimmerFast"
    )
    val level = 0.65f * slow + 0.35f * fast
    return Shimmer(drift = drift, glow = 0.7f + 0.3f * level, level = level)
}

/** What the DJ says, on the black of the lens, lighting up as it is said. */
@Composable
private fun LensText(
    line: String,
    block: DjBlock?,
    voiceStartedAtMs: Long?,
    animateWords: Boolean,
    modifier: Modifier
) {
    val accent = LocalBackgroundScheme.current.accent
    val words = remember(line) { line.split(' ').filter { it.isNotBlank() } }
    // Follows the voice from the moment it really starts, so the caption never runs ahead or lags.
    var lit by remember(line) { mutableIntStateOf(if (animateWords) 0 else words.size) }
    LaunchedEffect(line, animateWords, voiceStartedAtMs) {
        if (!animateWords) return@LaunchedEffect
        val start = voiceStartedAtMs ?: return@LaunchedEffect
        while (lit < words.size) {
            lit = ((SystemClock.uptimeMillis() - start) / WORD_MS + 1).toInt().coerceIn(lit, words.size)
            delay(WORD_TICK_MS)
        }
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = block?.let { "DJ · " + djBlockLabel(it) } ?: "DJ",
                color = accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(3.dp))
            // Said words light up as the voice reaches them, the rest wait dimmed.
            Text(
                text = buildAnnotatedString {
                    words.forEachIndexed { index, word ->
                        if (index > 0) append(' ')
                        withStyle(SpanStyle(color = if (index < lit) Color.White else Color.White.copy(alpha = 0.5f))) {
                            append(word)
                        }
                    }
                },
                style = CaptionStyle,
                maxLines = MAX_LINES,
                overflow = TextOverflow.Ellipsis
            )
        }
        DjSpeakingBars(color = accent)
    }
}


/** Where the island starts and ends: around the front camera, or a small pill at the top centre. */
private class CameraHole(val centreX: Dp, val top: Dp, val width: Dp, val height: Dp)

@Composable
private fun cameraHole(): CameraHole {
    val view = LocalView.current
    val density = LocalDensity.current
    val rect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        view.rootWindowInsets?.displayCutout?.boundingRects
            ?.filter { it.top <= 0 || it.top < view.height / 4 }
            ?.minByOrNull { kotlin.math.abs(it.centerX() - view.width / 2) }
    } else {
        null
    }
    return with(density) {
        if (rect != null && rect.width() > 0) {
            val size = rect.height().toDp()
            CameraHole(
                centreX = rect.exactCenterX().toDp(),
                top = (rect.top.toDp() - 4.dp).coerceAtLeast(0.dp),
                width = maxOf(rect.width().toDp() + 8.dp, size + 8.dp),
                height = size + 8.dp
            )
        } else {
            // No cutout reported: a dot the size of a typical punch-hole camera.
            CameraHole(centreX = (view.width / 2).toDp(), top = 10.dp, width = 24.dp, height = 24.dp)
        }
    }
}

/**
 * Clears the clock, battery and notification icons while the island is open, as the system's own
 * island does. The app keeps being told the bar is there, so nothing laid out under it moves.
 */
@Composable
private fun HideStatusBarWhile(hidden: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    val owner = remember { Any() }
    DisposableEffect(hidden, activity) {
        if (!hidden) return@DisposableEffect onDispose {}
        val window = activity.window
        val content = window.decorView.findViewById<View>(android.R.id.content)
        content.setTag(R.id.dj_island_insets_owner, owner)
        ViewCompat.setOnApplyWindowInsetsListener(content) { _, insets ->
            val bar = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars())
            WindowInsetsCompat.Builder(insets).setInsets(WindowInsetsCompat.Type.statusBars(), bar).build()
        }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.statusBars())
        onDispose {
            controller.show(WindowInsetsCompat.Type.statusBars())
            // Let the bar come back first; a newer island may have taken the listener meanwhile.
            content.postDelayed({
                if (content.getTag(R.id.dj_island_insets_owner) === owner) {
                    ViewCompat.setOnApplyWindowInsetsListener(content, null)
                    content.setTag(R.id.dj_island_insets_owner, null)
                    ViewCompat.requestApplyInsets(content)
                }
            }, STATUS_BAR_RETURN_MS)
        }
    }
}

private const val STATUS_BAR_RETURN_MS = 600L

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
