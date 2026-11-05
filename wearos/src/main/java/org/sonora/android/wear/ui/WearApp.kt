package org.sonora.android.wear.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import org.sonora.android.wear.Link
import org.sonora.android.wear.PhoneLink
import org.sonora.android.wear.R

private val Accent = Color(0xFF4F8DFF)

@Composable
fun WearApp(link: PhoneLink, ambient: Ambient = Ambient()) {
    MaterialTheme(colors = Colors(primary = Accent, background = Color.Black)) {
        // Always-on: the dimmed player, nothing to tap; the live one comes back on wake.
        val playing by link.nowPlaying.collectAsState()
        val current = playing
        if (ambient.active && current != null) {
            AmbientPlayer(current, ambient)
            return@MaterialTheme
        }
        val nav = rememberSwipeDismissableNavController()
        SwipeDismissableNavHost(navController = nav, startDestination = "player") {
            composable("player") { PlayerScreen(link, onQueue = { link.requestQueue(); nav.navigate("queue") }) }
            composable("queue") { QueueScreen(link, onPicked = { nav.popBackStack() }) }
        }
    }
}

@Composable
private fun PlayerScreen(link: PhoneLink, onQueue: () -> Unit) {
    val state by link.link.collectAsState()
    val heard by link.heard.collectAsState()
    val np by link.nowPlaying.collectAsState()
    val art by link.artwork.collectAsState()
    val volume by link.volume.collectAsState()

    // The crown turns the phone's volume, a step every so many pixels.
    val focus = remember { FocusRequester() }
    var crown by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Scaffold(
        timeText = { TimeText() },
        modifier = Modifier
            .onRotaryScrollEvent { event ->
                crown += event.verticalScrollPixels
                val steps = (crown / CROWN_PX_PER_STEP).toInt()
                if (steps != 0) {
                    crown -= steps * CROWN_PX_PER_STEP
                    link.nudgeVolume(steps)
                }
                true
            }
            .focusRequester(focus)
            .focusable()
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            // The cover, blurred and dimmed behind everything, as the phone's player does.
            art?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().blur(18.dp).alpha(0.55f)
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.15f), Color.Black.copy(alpha = 0.85f)))
                    )
                )
            }
            val current = np
            when {
                state == Link.NO_PHONE && current == null -> Message(stringResource(R.string.wear_no_phone), retry = link::retry)
                current == null && !heard -> Message(stringResource(R.string.wear_connecting))
                current == null -> Message(stringResource(R.string.wear_nothing_playing))
                else -> {
                    // The ring: the song's progress, extrapolated between the phone's words.
                    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                    LaunchedEffect(current.playing) {
                        while (current.playing) {
                            withFrameMillis { now = System.currentTimeMillis() }
                        }
                    }
                    val progress = if (current.durationMs > 0) current.positionAt(now).toFloat() / current.durationMs else 0f
                    CircularProgressIndicator(
                        progress = progress.coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxSize().padding(3.dp),
                        startAngle = 300f,
                        endAngle = 240f,
                        strokeWidth = 4.dp,
                        indicatorColor = Accent,
                        trackColor = Color.White.copy(alpha = 0.15f)
                    )
                    volume?.let { v ->
                        // The volume, along the left edge while the crown turns it.
                        if (v.max > 0) {
                            CircularProgressIndicator(
                                progress = v.level.toFloat() / v.max,
                                modifier = Modifier.fillMaxSize().padding(10.dp),
                                startAngle = 225f,
                                endAngle = 315f,
                                strokeWidth = 2.dp,
                                indicatorColor = Color.White.copy(alpha = 0.6f),
                                trackColor = Color.Transparent
                            )
                        }
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 22.dp)
                    ) {
                        Spacer(Modifier.height(18.dp))
                        Text(
                            text = current.title,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            color = Color.White
                        )
                        Text(
                            text = current.artist,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SmallButton(Icons.Default.SkipPrevious, stringResource(R.string.wear_previous), enabled = current.hasPrevious, onClick = link::previous)
                            Button(
                                onClick = link::togglePlay,
                                modifier = Modifier.size(ButtonDefaults.LargeButtonSize),
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color.White, contentColor = Color.Black)
                            ) {
                                Icon(
                                    if (current.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = stringResource(if (current.playing) R.string.wear_pause else R.string.wear_play),
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                            SmallButton(Icons.Default.SkipNext, stringResource(R.string.wear_next), enabled = current.hasNext, onClick = link::next)
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            SmallButton(
                                if (current.liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                stringResource(R.string.wear_like),
                                tint = if (current.liked) Color(0xFFFF375F) else Color.White,
                                onClick = link::toggleLike
                            )
                            SmallButton(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.wear_queue), onClick = onQueue)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    tint: Color = Color.White,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(ButtonDefaults.SmallButtonSize),
        colors = ButtonDefaults.buttonColors(backgroundColor = Color.White.copy(alpha = 0.14f), contentColor = tint)
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun Message(text: String, retry: (() -> Unit)? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
        Text(text, textAlign = TextAlign.Center, fontSize = 14.sp, color = Color.White)
        if (retry != null) {
            Spacer(Modifier.height(10.dp))
            Chip(
                onClick = retry,
                label = { Text(stringResource(R.string.wear_retry)) },
                colors = ChipDefaults.primaryChipColors()
            )
        }
    }
}

@Composable
private fun QueueScreen(link: PhoneLink, onPicked: () -> Unit) {
    val queue by link.queue.collectAsState()
    val listState = rememberScalingLazyListState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().focusRequester(focus).focusable(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item { ListHeader { Text(stringResource(R.string.wear_up_next)) } }
            val rows = queue?.rows.orEmpty()
            if (queue == null) {
                item { CircularProgressIndicator() }
            }
            items(rows) { row ->
                val current = row.index == queue?.currentIndex
                Chip(
                    onClick = {
                        link.skipTo(row.index)
                        onPicked()
                    },
                    label = { Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = { Text(row.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    colors = if (current) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** Crown travel per volume step: about a notch on a Galaxy Watch bezel or a Pixel Watch crown. */
private const val CROWN_PX_PER_STEP = 40f
