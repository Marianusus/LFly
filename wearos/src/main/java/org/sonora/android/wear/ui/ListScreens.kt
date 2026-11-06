package org.sonora.android.wear.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Earbuds
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material.icons.filled.Tablet
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Watch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.ExperimentalWearFoundationApi
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rememberActiveFocusRequester
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleButton
import androidx.wear.compose.material.ToggleButtonDefaults
import com.google.android.gms.wearable.Asset
import kotlinx.coroutines.delay
import org.sonora.android.wear.PhoneLink
import org.sonora.android.wear.R
import org.sonora.android.wear.speaker.Speaker
import org.sonora.android.wear.WearProtocol as P

private val RowColor = Color(0xFF1C1C1E)
private val Secondary = Color.White.copy(alpha = 0.6f)

/** Liked songs get a tile of their own, as the phone's library draws them: violet into the accent. */
private val LikedTile = Brush.linearGradient(listOf(Color(0xFF7B5CFF), Accent))
private val AccentTile = Brush.linearGradient(listOf(Accent, Accent))

// ---------- Building blocks ----------

/** A curved list; it takes the crown itself while on screen. */
@Composable
private fun WatchList(
    header: String,
    subtitle: String? = null,
    withTime: Boolean = true,
    content: ScalingLazyListScope.() -> Unit
) {
    val listState = rememberScalingLazyListState()
    Scaffold(
        timeText = if (withTime) ({ TimeText() }) else null,
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 4.dp)) {
                    Text(
                        header,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!subtitle.isNullOrBlank()) Text(subtitle, fontSize = 12.sp, color = Secondary)
                }
            }
            content()
        }
    }
}

private fun ScalingLazyListScope.loading() = item {
    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp), indicatorColor = Accent)
    }
}

private fun ScalingLazyListScope.note(text: String) = item {
    Text(text, fontSize = 13.sp, textAlign = TextAlign.Center, color = Secondary, modifier = Modifier.padding(12.dp))
}

private fun ScalingLazyListScope.section(text: String) = item {
    ListHeader(modifier = Modifier.height(28.dp)) { Text(text, fontSize = 12.sp, color = Secondary) }
}

/** A row's cover from the phone, decoded once; [fallback] until it arrives, or on [tile] when there is none. */
@Composable
private fun Cover(link: PhoneLink, art: Asset?, fallback: ImageVector, tile: Brush? = null) {
    val image by produceState<ImageBitmap?>(null, art) { value = art?.let { link.art(it) } }
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(tile ?: Brush.linearGradient(listOf(Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.12f)))),
        contentAlignment = Alignment.Center
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(fallback, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

/** One line of a list: cover, title, a grey line under it. The current one is in the accent. */
@Composable
private fun ListRow(
    title: String,
    subtitle: String?,
    cover: @Composable () -> Unit,
    current: Boolean = false,
    onClick: () -> Unit
) {
    Chip(
        onClick = onClick,
        label = {
            Text(
                title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (current) Accent else Color.White,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal
            )
        },
        secondaryLabel = if (subtitle.isNullOrBlank()) {
            null
        } else {
            { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Secondary) }
        },
        icon = { cover() },
        colors = ChipDefaults.chipColors(backgroundColor = RowColor),
        modifier = Modifier.fillMaxWidth()
    )
}

// ---------- Library: the page right of the player ----------

@Composable
internal fun LibraryPage(link: PhoneLink, onQueue: () -> Unit, onOpen: (String) -> Unit) {
    val library by link.library.collectAsState()
    LaunchedEffect(Unit) { link.requestLibrary() }
    val empty = stringResource(R.string.wear_library_empty)
    val upNext = stringResource(R.string.wear_up_next)
    WatchList(stringResource(R.string.wear_your_library), withTime = false) {
        item { ListRow(upNext, null, cover = { Cover(link, null, Icons.AutoMirrored.Filled.QueueMusic, tile = AccentTile) }, onClick = onQueue) }
        val list = library ?: return@WatchList loading()
        if (list.isEmpty()) note(empty)
        items(list) { collection ->
            ListRow(
                collection.title,
                collection.subtitle,
                cover = {
                    when (collection.kind) {
                        P.COLLECTION_KIND_LIKED -> Cover(link, null, Icons.Default.Favorite, tile = LikedTile)
                        P.COLLECTION_KIND_DOWNLOADS -> Cover(link, null, Icons.Default.DownloadDone, tile = AccentTile)
                        P.COLLECTION_KIND_RECENT -> Cover(link, collection.art, Icons.Default.History)
                        else -> Cover(link, collection.art, Icons.AutoMirrored.Filled.PlaylistPlay)
                    }
                }
            ) { onOpen(collection.id) }
        }
    }
}

@Composable
internal fun CollectionScreen(link: PhoneLink, id: String, onPlayed: () -> Unit) {
    val opened by link.collection.collectAsState()
    val library by link.library.collectAsState()
    LaunchedEffect(id) { link.openCollection(id) }
    val info = library?.firstOrNull { it.id == id }
    val empty = stringResource(R.string.wear_collection_empty)
    val shufflePlay = stringResource(R.string.wear_shuffle_play)
    WatchList(info?.title ?: stringResource(R.string.wear_your_library), subtitle = info?.subtitle) {
        val tracks = opened?.takeIf { it.id == id } ?: return@WatchList loading()
        if (tracks.rows.isEmpty()) return@WatchList note(empty)
        item {
            // The big round shuffle button under the title, where music apps put it.
            Box(
                Modifier
                    .padding(vertical = 4.dp)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Accent)
                    .clickable {
                        link.playCollection(id, -1)
                        onPlayed()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Shuffle, contentDescription = shufflePlay, tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
        items(tracks.rows) { row ->
            ListRow(row.title, row.artist, cover = { Cover(link, row.art, Icons.Default.MusicNote) }) {
                link.playCollection(id, row.index)
                onPlayed()
            }
        }
    }
}

// ---------- Queue ----------

@Composable
internal fun QueueScreen(link: PhoneLink, onPicked: () -> Unit) {
    val queue by link.queue.collectAsState()
    val np by link.nowPlaying.collectAsState()
    val empty = stringResource(R.string.wear_queue_empty)
    val nowPlaying = stringResource(R.string.wear_playing_now)
    val nextUp = stringResource(R.string.wear_next_up)
    val shuffle = stringResource(R.string.wear_shuffle)
    val repeat = stringResource(R.string.wear_repeat)
    WatchList(stringResource(R.string.wear_up_next)) {
        np?.let { current ->
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                    ToggleButton(
                        checked = current.shuffle,
                        onCheckedChange = { link.toggleShuffle() },
                        colors = ToggleButtonDefaults.toggleButtonColors(checkedBackgroundColor = Accent),
                        modifier = Modifier.size(ToggleButtonDefaults.SmallToggleButtonSize)
                    ) { Icon(Icons.Default.Shuffle, contentDescription = shuffle, modifier = Modifier.size(18.dp)) }
                    ToggleButton(
                        checked = current.repeat != P.REPEAT_OFF,
                        onCheckedChange = { link.cycleRepeat() },
                        colors = ToggleButtonDefaults.toggleButtonColors(checkedBackgroundColor = Accent),
                        modifier = Modifier.size(ToggleButtonDefaults.SmallToggleButtonSize)
                    ) {
                        Icon(
                            if (current.repeat == P.REPEAT_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                            contentDescription = repeat,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
        val current = queue ?: return@WatchList loading()
        if (current.rows.isEmpty()) return@WatchList note(empty)
        current.rows.firstOrNull { it.index == current.currentIndex }?.let { playing ->
            section(nowPlaying)
            item {
                ListRow(playing.title, playing.artist, cover = { Cover(link, playing.art, Icons.Default.MusicNote) }, current = true, onClick = onPicked)
            }
        }
        val upcoming = current.rows.filter { it.index != current.currentIndex }
        if (upcoming.isNotEmpty()) section(nextUp)
        items(upcoming) { row ->
            ListRow(row.title, row.artist, cover = { Cover(link, row.art, Icons.Default.MusicNote) }) {
                link.skipTo(row.index)
                onPicked()
            }
        }
    }
}

// ---------- Devices ----------

/**
 * Where to play: the device playing now at the top, then everything else the phone can reach (its
 * outputs, LFly devices on the network, this watch). Asked again every few seconds while open,
 * since devices on the network turn up one by one.
 */
@Composable
internal fun OutputsScreen(link: PhoneLink, onPicked: () -> Unit) {
    val outputs by link.outputs.collectAsState()
    LaunchedEffect(Unit) {
        repeat(6) {
            link.requestOutputs()
            delay(5_000)
        }
    }
    val none = stringResource(R.string.wear_no_outputs)
    val listening = stringResource(R.string.wear_now_playing_on)
    val select = stringResource(R.string.wear_select_device)
    val thisWatch = stringResource(R.string.wear_output_this_watch)
    WatchList(stringResource(R.string.wear_devices)) {
        val list = outputs ?: return@WatchList loading()
        if (list.isEmpty()) return@WatchList note(none)
        fun name(id: String, name: String) = if (id == P.OUTPUT_WATCH) thisWatch else name
        list.firstOrNull { it.active }?.let { active ->
            section(listening)
            item {
                ListRow(name(active.id, active.name), null, cover = { DeviceIcon(active.kind, active = true) }, current = true, onClick = onPicked)
            }
        }
        val others = list.filter { !it.active }
        if (others.isNotEmpty()) section(select)
        items(others) { output ->
            ListRow(name(output.id, output.name), null, cover = { DeviceIcon(output.kind, active = false) }) {
                link.selectOutput(output.id)
                onPicked()
            }
        }
    }
}

@Composable
private fun DeviceIcon(kind: String, active: Boolean) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(if (active) Accent.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(outputIcon(kind), contentDescription = null, tint = if (active) Accent else Color.White, modifier = Modifier.size(20.dp))
    }
}

// ---------- Volume ----------

/**
 * The volume on a screen of its own: a big arc, the level, where it plays, and - / + for those who
 * would rather tap than turn the crown.
 */
@OptIn(ExperimentalWearFoundationApi::class)
@Composable
internal fun VolumeScreen(link: PhoneLink) {
    val np by link.nowPlaying.collectAsState()
    val active = rememberActiveVolume(link, np)
    val context = LocalContext.current
    val focus = rememberActiveFocusRequester()
    var crown by remember { mutableFloatStateOf(0f) }
    val nudge: (Int) -> Unit = { steps -> if (active.onWatch) Speaker.nudgeVolume(context, steps) else link.nudgeVolume(steps) }
    val volume = active.volume
    val fraction = if (volume != null && volume.max > 0) volume.level.toFloat() / volume.max else 0f
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onRotaryScrollEvent { event ->
                crown += event.verticalScrollPixels
                val steps = (crown / CROWN_PX_PER_STEP).toInt()
                if (steps != 0) {
                    crown -= steps * CROWN_PX_PER_STEP
                    nudge(steps)
                }
                true
            }
            .focusRequester(focus)
            .focusable(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            progress = fraction,
            modifier = Modifier.fillMaxSize().padding(6.dp),
            startAngle = 135f,
            endAngle = 45f,
            strokeWidth = 8.dp,
            indicatorColor = Accent,
            trackColor = Color.White.copy(alpha = 0.15f)
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.wear_volume), fontSize = 13.sp, color = Secondary)
            Text(
                if (volume == null) "–" else "${(fraction * 100).toInt()}%",
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            val where = if (active.onWatch) {
                stringResource(R.string.wear_output_this_watch)
            } else {
                np?.outputName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.wear_output_phone)
            }
            Text(
                where,
                fontSize = 12.sp,
                color = Accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 36.dp)
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                VolumeButton(Icons.AutoMirrored.Filled.VolumeDown) { nudge(-1) }
                VolumeButton(Icons.AutoMirrored.Filled.VolumeUp) { nudge(1) }
            }
        }
    }
}

@Composable
private fun VolumeButton(icon: ImageVector, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

internal fun outputIcon(kind: String): ImageVector = when (kind) {
    P.KIND_EARBUDS -> Icons.Default.Earbuds
    P.KIND_HEADPHONES -> Icons.Default.Headphones
    P.KIND_SPEAKER -> Icons.Default.Speaker
    P.KIND_CAR -> Icons.Default.DirectionsCar
    P.KIND_WATCH -> Icons.Default.Watch
    P.KIND_TV -> Icons.Default.Tv
    P.KIND_DESKTOP -> Icons.Default.Computer
    P.KIND_TABLET -> Icons.Default.Tablet
    P.KIND_CAST -> Icons.Default.Cast
    P.KIND_PHONE, "" -> Icons.Default.Smartphone
    else -> Icons.Default.SpeakerGroup
}
