package org.sonora.android.wear.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Devices
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.ExperimentalWearFoundationApi
import androidx.wear.compose.foundation.HierarchicalFocusCoordinator
import androidx.wear.compose.foundation.rememberActiveFocusRequester
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.HorizontalPageIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PageIndicatorState
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import org.sonora.android.wear.Link
import org.sonora.android.wear.NowPlaying
import org.sonora.android.wear.PhoneLink
import org.sonora.android.wear.R
import org.sonora.android.wear.Volume
import org.sonora.android.wear.speaker.Speaker
import org.sonora.android.wear.WearProtocol as P

internal val Accent = Color(0xFF4F8DFF)

/**
 * The watch app, laid out the way music players on Wear OS are: the player first, the library one
 * swipe to the left, and volume and devices as screens of their own from the player's bottom row.
 */
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
        val backToPlayer: () -> Unit = { nav.popBackStack("home", inclusive = false) }
        SwipeDismissableNavHost(navController = nav, startDestination = "home") {
            composable("home") {
                Home(
                    link,
                    onVolume = { nav.navigate("volume") },
                    onDevices = { nav.navigate("devices") },
                    onQueue = { link.requestQueue(); nav.navigate("queue") },
                    onOpen = { id -> nav.navigate("collection/" + android.net.Uri.encode(id)) }
                )
            }
            composable("volume") { VolumeScreen(link) }
            composable("devices") { OutputsScreen(link, onPicked = backToPlayer) }
            composable("queue") { QueueScreen(link, onPicked = backToPlayer) }
            composable("collection/{id}") { entry ->
                CollectionScreen(link, entry.arguments?.getString("id").orEmpty(), onPlayed = backToPlayer)
            }
        }
    }
}

/** Player and library side by side; the dots at the bottom say which one is showing. */
@OptIn(ExperimentalWearFoundationApi::class)
@Composable
private fun Home(link: PhoneLink, onVolume: () -> Unit, onDevices: () -> Unit, onQueue: () -> Unit, onOpen: (String) -> Unit) {
    val pager = rememberPagerState(pageCount = { 2 })
    Scaffold(
        timeText = { TimeText() },
        pageIndicator = { HorizontalPageIndicator(pageIndicatorState = remember(pager) { PagerDots(pager) }) }
    ) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            // Only the page on screen takes the crown: volume on the player, scrolling in the library.
            HierarchicalFocusCoordinator(requiresFocus = { pager.currentPage == page }) {
                when (page) {
                    0 -> PlayerPage(link, onVolume, onDevices)
                    else -> LibraryPage(link, onQueue = onQueue, onOpen = onOpen)
                }
            }
        }
    }
}

private class PagerDots(private val pager: PagerState) : PageIndicatorState {
    override val pageOffset: Float get() = pager.currentPageOffsetFraction
    override val selectedPage: Int get() = pager.currentPage
    override val pageCount: Int get() = pager.pageCount
}

@OptIn(ExperimentalWearFoundationApi::class)
@Composable
private fun PlayerPage(link: PhoneLink, onVolume: () -> Unit, onDevices: () -> Unit) {
    val state by link.link.collectAsState()
    val heard by link.heard.collectAsState()
    val np by link.nowPlaying.collectAsState()
    val art by link.artwork.collectAsState()
    val tint by link.tint.collectAsState()
    val volume = rememberActiveVolume(link, np)
    val context = LocalContext.current

    // The crown turns the volume of whatever plays, a step every so many pixels.
    var crown by remember { mutableFloatStateOf(0f) }
    val focus = rememberActiveFocusRequester()

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onRotaryScrollEvent { event ->
                crown += event.verticalScrollPixels
                val steps = (crown / CROWN_PX_PER_STEP).toInt()
                if (steps != 0) {
                    crown -= steps * CROWN_PX_PER_STEP
                    if (volume.onWatch) Speaker.nudgeVolume(context, steps) else link.nudgeVolume(steps)
                }
                true
            }
            .focusRequester(focus)
            .focusable(),
        contentAlignment = Alignment.Center
    ) {
        CoverBackdrop(art, tint)
        val current = np
        when {
            state == Link.NO_PHONE && current == null -> Message(stringResource(R.string.wear_no_phone), retry = link::retry)
            current == null && !heard -> Message(stringResource(R.string.wear_connecting))
            current == null -> Message(stringResource(R.string.wear_nothing_playing), hint = stringResource(R.string.wear_swipe_library))
            else -> PlayerControls(link, current, onVolume, onDevices)
        }
    }
}

/** The cover blurred and dimmed behind everything, over its colour for watches that cannot blur. */
@Composable
private fun CoverBackdrop(art: androidx.compose.ui.graphics.ImageBitmap?, tint: Color?) {
    tint?.let {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(it.copy(alpha = 0.7f), Color.Black))))
    }
    art?.let {
        Image(
            bitmap = it,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().blur(24.dp).alpha(0.45f)
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.2f), Color.Black.copy(alpha = 0.85f)))
            )
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayerControls(link: PhoneLink, current: NowPlaying, onVolume: () -> Unit, onDevices: () -> Unit) {
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
        modifier = Modifier.fillMaxSize().padding(2.dp),
        startAngle = 0f,
        endAngle = 360f,
        strokeWidth = 3.dp,
        indicatorColor = Accent,
        trackColor = Color.White.copy(alpha = 0.12f)
    )
    val elsewhere = current.outputKind.isNotEmpty() && current.outputKind != P.KIND_PHONE
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp)
    ) {
        Spacer(Modifier.height(10.dp))
        Text(
            text = current.title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            textAlign = TextAlign.Center,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 18.dp).basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1_500)
        )
        Text(
            text = current.artist,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = Color.White.copy(alpha = 0.65f),
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTap(Icons.Default.SkipPrevious, stringResource(R.string.wear_previous), size = 34.dp, enabled = current.hasPrevious, onClick = link::previous)
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable(onClick = link::togglePlay),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (current.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = stringResource(if (current.playing) R.string.wear_pause else R.string.wear_play),
                    tint = Color.Black,
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            IconTap(Icons.Default.SkipNext, stringResource(R.string.wear_next), size = 34.dp, enabled = current.hasNext, onClick = link::next)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconTap(
                if (current.liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                stringResource(R.string.wear_like),
                tint = if (current.liked) Accent else Color.White,
                onClick = link::toggleLike
            )
            IconTap(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.wear_volume), onClick = onVolume)
            IconTap(
                if (elsewhere) outputIcon(current.outputKind) else Icons.Default.Devices,
                stringResource(R.string.wear_devices),
                tint = if (elsewhere) Accent else Color.White,
                onClick = onDevices
            )
        }
        // Where the music comes out, when it is not this phone: in the accent, under the buttons.
        if (elsewhere && current.outputName.isNotBlank()) {
            Text(
                text = if (current.outputKind == P.KIND_WATCH && current.watchSpeaker) stringResource(R.string.wear_output_this_watch) else current.outputName,
                fontSize = 11.sp,
                color = Accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 40.dp).clickable(onClick = onDevices)
            )
        }
        Spacer(Modifier.height(14.dp))
    }
}

/** A bare icon button, as players draw the secondary controls: no disc, just a generous touch area. */
@Composable
private fun IconTap(
    icon: ImageVector,
    description: String,
    size: Dp = 22.dp,
    enabled: Boolean = true,
    tint: Color = Color.White,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(size + 14.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = if (enabled) tint else tint.copy(alpha = 0.35f), modifier = Modifier.size(size))
    }
}

/** The volume the crown and the volume screen move: the watch's own while it plays, else the phone's. */
internal class ActiveVolume(val volume: Volume?, val onWatch: Boolean)

@Composable
internal fun rememberActiveVolume(link: PhoneLink, np: NowPlaying?): ActiveVolume {
    val phone by link.volume.collectAsState()
    val watch by Speaker.volume.collectAsState()
    val speaking by Speaker.active.collectAsState()
    val onWatch = speaking || np?.watchSpeaker == true
    return ActiveVolume(if (onWatch) watch else phone, onWatch)
}

@Composable
private fun Message(text: String, retry: (() -> Unit)? = null, hint: String? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(26.dp)) {
        Text(text, textAlign = TextAlign.Center, fontSize = 14.sp, color = Color.White)
        if (hint != null) {
            Spacer(Modifier.height(8.dp))
            Text(hint, textAlign = TextAlign.Center, fontSize = 12.sp, color = Accent)
        }
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

/** Crown travel per volume step: about a notch on a Galaxy Watch bezel or a Pixel Watch crown. */
internal const val CROWN_PX_PER_STEP = 40f
