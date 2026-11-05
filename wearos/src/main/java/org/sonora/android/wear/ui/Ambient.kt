package org.sonora.android.wear.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import org.sonora.android.wear.NowPlaying
import org.sonora.android.wear.R
import kotlin.math.cos
import kotlin.math.sin

/** The watch's always-on state. [minute] counts the system's once-a-minute ambient updates. */
@Immutable
data class Ambient(
    val active: Boolean = false,
    val burnInProtection: Boolean = false,
    val lowBit: Boolean = false,
    val minute: Int = 0
)

/**
 * LFly in always-on: the mark, the song and the artist, and a hairline arc along the rim for how far
 * the song has got (redrawn once a minute, like everything here). All pure white on black: Galaxy
 * Watch panels drop greys to black in always-on. With burn-in protection everything drifts round a
 * small circle, one step a minute, so no pixel stays lit for long.
 */
@Composable
fun AmbientPlayer(np: NowPlaying, ambient: Ambient) {
    val drift = if (ambient.burnInProtection) {
        val angle = ambient.minute * (Math.PI / 5)
        (5 * cos(angle)).dp to (5 * sin(angle)).dp
    } else {
        0.dp to 0.dp
    }
    val progress = if (np.durationMs > 0) {
        (np.positionAt(System.currentTimeMillis()).toFloat() / np.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!ambient.lowBit && progress > 0f) {
            Canvas(Modifier.fillMaxSize().padding(4.dp)) {
                drawArc(
                    color = Color.White,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(horizontal = 30.dp).offset(drift.first, drift.second)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_notification),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(26.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = np.title,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = np.artist,
                color = Color.White,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
