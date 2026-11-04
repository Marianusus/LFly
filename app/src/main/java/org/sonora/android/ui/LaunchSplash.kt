package org.sonora.android.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.sonora.android.R

/**
 * The opening animation, drawn from the app icon's own pieces: the trail draws itself round its
 * curve, the note pops in, the plane flies in along the trail, then the whole logo lifts away into
 * the app. About a second; with Reduce Motion only a short fade.
 */
@Composable
fun LaunchSplash(reduceMotion: Boolean, onFinished: () -> Unit) {
    val trail = remember { Animatable(0f) }
    val note = remember { Animatable(0f) }
    val plane = remember { Animatable(0f) }
    val exit = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        // The app is busiest on its very first frames: start once a few are on screen, so the
        // trail is drawn rather than skipped.
        repeat(3) { withFrameNanos { } }
        delay(120)
        if (reduceMotion) {
            trail.snapTo(1f); note.snapTo(1f); plane.snapTo(1f)
            delay(250)
            exit.animateTo(1f, tween(200))
        } else {
            coroutineScope {
                launch { trail.animateTo(1f, tween(560, easing = FastOutSlowInEasing)) }
                launch {
                    delay(260)
                    note.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow))
                }
                launch {
                    delay(420)
                    plane.animateTo(1f, tween(480, easing = LinearOutSlowInEasing))
                }
            }
            delay(120)
            exit.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
        }
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = 1f - exit.value }
            .background(Brush.verticalGradient(listOf(Color(0xFF0D1934), Color(0xFF03060F)))),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.46f)
                .aspectRatio(799f / 713f)
                .graphicsLayer {
                    val lift = 1f + 0.12f * exit.value
                    scaleX = lift
                    scaleY = lift
                }
        ) {
            // The trail, revealed by a sweep round the centre of its curve: from the note's end,
            // under and round the left, up to the plane.
            Image(
                painter = painterResource(R.drawable.splash_trail),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        val centre = Offset(size.width * 0.36f, size.height * 0.56f)
                        val radius = size.maxDimension
                        val sweep = Path().apply {
                            moveTo(centre.x, centre.y)
                            arcTo(
                                Rect(centre, radius),
                                startAngleDegrees = 40f,
                                sweepAngleDegrees = 300f * trail.value,
                                forceMoveTo = false
                            )
                            close()
                        }
                        clipPath(sweep) { this@drawWithContent.drawContent() }
                    }
            )
            Image(
                painter = painterResource(R.drawable.splash_note),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val s = 0.55f + 0.45f * note.value
                        scaleX = s
                        scaleY = s
                        // Grows out of the note's own place, lower right.
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.77f, 0.8f)
                        alpha = note.value.coerceIn(0f, 1f)
                    }
            )
            Image(
                painter = painterResource(R.drawable.splash_plane),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // Comes in along the end of the trail, from below left, and settles.
                        val left = 1f - plane.value
                        translationX = -size.width * 0.22f * left
                        translationY = size.height * 0.16f * left
                        alpha = (plane.value * 2f).coerceIn(0f, 1f)
                    }
            )
        }
    }
}
