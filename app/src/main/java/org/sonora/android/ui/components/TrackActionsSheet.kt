package org.sonora.android.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbDownOffAlt
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import org.sonora.android.R
import org.sonora.android.api.ArtistCredit
import org.sonora.android.api.ArtistCredits
import org.sonora.android.api.Track
import org.sonora.android.api.TrackMetadata
import org.sonora.android.api.withMetadata
import org.sonora.android.playback.DownloadProgress
import org.sonora.android.playback.DownloadStatus
import org.sonora.android.playback.PLAYBACK_SPEEDS
import org.sonora.android.playback.SleepTimer
import android.os.SystemClock
import java.text.DecimalFormat
import kotlinx.coroutines.delay
import org.sonora.android.ui.player.v2.rememberArtworkGradient
import org.sonora.android.ui.theme.BackgroundScheme
import org.sonora.android.ui.theme.LocalBackgroundScheme
import org.sonora.android.ui.theme.mixColor
import org.sonora.android.ui.theme.onColorFor

private enum class ActionsPage { ACTIONS, INFO, SLEEP, SPEED }

/** Sleep timer and speed, offered only in the full player's menu. */
class PlayerPlaybackOptions(
    val sleepTimer: SleepTimer,
    val speed: Float,
    val onSleepMinutes: (Int) -> Unit,
    val onSleepAtTrackEnd: () -> Unit,
    val onCancelSleep: () -> Unit,
    val onSpeed: (Float) -> Unit,
)

/**
 * BitChord-style track menu: the track's own artwork tints the sheet, the
 * actions are one flat grouped list, and the metadata page is a subpage rather
 * than a second sheet stacked on top of this one.
 *
 * The tint is mixed with the active [BackgroundScheme] instead of using fixed
 * white-on-black colours, so the same sheet stays legible in every appearance
 * mode Sonora ships.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackActionsSheet(
    track: Track,
    metadata: TrackMetadata? = null,
    metadataLoading: Boolean = false,
    isFavorite: Boolean = false,
    isDisliked: Boolean = false,
    sourceActions: TrackSourceActions? = null,
    onDismissRequest: () -> Unit,
    onToggleFavorite: (() -> Unit)? = null,
    onToggleDislike: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onShare: (() -> Unit)?,
    onOpenEqualizer: (() -> Unit)? = null,
    onOpenAmbient: (() -> Unit)? = null,
    onOpenAlbum: ((String) -> Unit)? = null,
    onOpenArtist: ((ArtistCredit) -> Unit)? = null,
    playbackOptions: PlayerPlaybackOptions? = null,
) {
    val detailed = track.withMetadata(metadata)
    val scheme = LocalBackgroundScheme.current
    val gradient = rememberArtworkGradient(detailed.artworkUrl, scheme.accent)
    var page by remember { mutableStateOf(ActionsPage.ACTIONS) }

    BackHandler {
        if (page != ActionsPage.ACTIONS) page = ActionsPage.ACTIONS else onDismissRequest()
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.Transparent,
        contentColor = scheme.onBackground,
        scrimColor = scheme.scrim,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        TrackSheetSurface(
            track = detailed,
            scheme = scheme,
            gradient = gradient,
        ) {
            when (page) {
                ActionsPage.ACTIONS -> TrackActionsContent(
                    track = detailed,
                    metadata = metadata,
                    metadataLoading = metadataLoading,
                    scheme = scheme,
                    isFavorite = isFavorite,
                    isDisliked = isDisliked,
                    sourceActions = sourceActions,
                    onOpenInfo = { page = ActionsPage.INFO },
                    onDismissRequest = onDismissRequest,
                    onToggleFavorite = onToggleFavorite,
                    onToggleDislike = onToggleDislike,
                    onPlayNext = onPlayNext,
                    onAddToQueue = onAddToQueue,
                    onAddToPlaylist = onAddToPlaylist,
                    onShare = onShare,
                    onOpenEqualizer = onOpenEqualizer,
                    onOpenAmbient = onOpenAmbient,
                    onOpenAlbum = onOpenAlbum,
                    onOpenArtist = onOpenArtist,
                    playbackOptions = playbackOptions,
                    onOpenSleep = { page = ActionsPage.SLEEP },
                    onOpenSpeed = { page = ActionsPage.SPEED },
                )
                ActionsPage.SLEEP -> playbackOptions?.let { options ->
                    SleepTimerPage(
                        options = options,
                        scheme = scheme,
                        onBack = { page = ActionsPage.ACTIONS },
                        onDone = onDismissRequest,
                    )
                }
                ActionsPage.SPEED -> playbackOptions?.let { options ->
                    SpeedPage(
                        options = options,
                        scheme = scheme,
                        onBack = { page = ActionsPage.ACTIONS },
                        onDone = onDismissRequest,
                    )
                }
                ActionsPage.INFO -> TrackInfoPage(
                    track = detailed,
                    metadata = metadata,
                    metadataLoading = metadataLoading,
                    scheme = scheme,
                    onBack = { page = ActionsPage.ACTIONS },
                )
            }
        }
    }
}

@Composable
private fun TrackSheetSurface(
    track: Track,
    scheme: BackgroundScheme,
    gradient: org.sonora.android.ui.player.v2.ArtworkGradient,
    content: @Composable () -> Unit,
) {
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.88f
    val baseTop = mixColor(scheme.gradient.first(), gradient.top, 0.22f)
    val baseBottom = mixColor(scheme.gradient.last(), gradient.bottom, 0.30f)
    val ink = scheme.onBackground

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 640.dp)
            .heightIn(max = maxHeight)
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(
                Brush.verticalGradient(listOf(baseTop, baseBottom))
            )
    ) {
        if (!track.artworkUrl.isNullOrBlank()) {
            AsyncImage(
                model = track.artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .blur(70.dp),
            )
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            baseTop.copy(alpha = 0.86f),
                            baseBottom.copy(alpha = 0.94f),
                        )
                    )
                )
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(scheme.scrim.copy(alpha = 0.10f))
        )

        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 34.dp, height = 4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(ink.copy(alpha = 0.35f))
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState()),
            ) {
                content()
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun TrackActionsContent(
    track: Track,
    metadata: TrackMetadata?,
    metadataLoading: Boolean,
    scheme: BackgroundScheme,
    isFavorite: Boolean,
    isDisliked: Boolean,
    sourceActions: TrackSourceActions?,
    onOpenInfo: () -> Unit,
    onDismissRequest: () -> Unit,
    onToggleFavorite: (() -> Unit)?,
    onToggleDislike: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onShare: (() -> Unit)?,
    onOpenEqualizer: (() -> Unit)?,
    onOpenAmbient: (() -> Unit)?,
    onOpenAlbum: ((String) -> Unit)?,
    onOpenArtist: ((ArtistCredit) -> Unit)?,
    playbackOptions: PlayerPlaybackOptions?,
    onOpenSleep: () -> Unit,
    onOpenSpeed: () -> Unit,
) {
    val ink = scheme.onBackground
    val secondary = scheme.onBackgroundSecondary
    val accent = scheme.accent
    val downloadUi = LocalDownloadUi.current
    val isLocal = track.provider.equals("Local", ignoreCase = true)
    val progress = downloadUi.progressFor(track.id)

    TrackSheetHeader(track = track, scheme = scheme)
    GroupDivider(scheme)

    if (onToggleFavorite != null) {
        ActionRow(
            icon = if (isFavorite) Icons.Default.Check else Icons.Default.Headphones,
            label = stringResource(
                if (isFavorite) R.string.track_actions_favorite_remove
                else R.string.track_actions_favorite_add
            ),
            tint = if (isFavorite) accent else null,
            scheme = scheme,
        ) {
            onDismissRequest()
            onToggleFavorite()
        }
    }
    ActionRow(
        icon = if (isDisliked) Icons.Default.ThumbDown else Icons.Default.ThumbDownOffAlt,
        label = stringResource(
            if (isDisliked) R.string.track_item_dislike_remove
            else R.string.track_item_dislike_add
        ),
        tint = if (isDisliked) Color(0xFFE57373) else null,
        scheme = scheme,
    ) {
        onDismissRequest()
        onToggleDislike()
    }
    ActionRow(
        icon = Icons.AutoMirrored.Filled.PlaylistAdd,
        label = stringResource(R.string.track_item_add_to_playlist),
        scheme = scheme,
    ) {
        onDismissRequest()
        onAddToPlaylist()
    }

    GroupDivider(scheme)

    if (!isLocal) {
        val downloadLabel = when (progress.status) {
            DownloadStatus.DOWNLOADED -> stringResource(R.string.download_remove)
            DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> stringResource(R.string.download_cancel)
            DownloadStatus.FAILED -> stringResource(R.string.download_retry)
            DownloadStatus.NONE -> stringResource(R.string.download_action)
        }
        val downloadIcon = when (progress.status) {
            DownloadStatus.DOWNLOADED -> Icons.Default.DownloadDone
            DownloadStatus.FAILED -> Icons.Default.Refresh
            else -> Icons.Default.Download
        }
        ActionRow(
            icon = downloadIcon,
            label = downloadLabel,
            tint = when (progress.status) {
                DownloadStatus.DOWNLOADED -> accent
                DownloadStatus.FAILED -> Color(0xFFE57373)
                else -> null
            },
            scheme = scheme,
            trailing = if (progress.status == DownloadStatus.DOWNLOADING && progress.progress > 0f) {
                { DownloadProgressBadge(progress.progress, accent) }
            } else null,
        ) {
            onDismissRequest()
            downloadUi.onToggle(track)
        }
        if (downloadUi.isManagedLocal(track) || (isLocal && downloadUi.isDownloaded(track.id))) {
            ActionRow(
                icon = Icons.Default.DeleteOutline,
                label = stringResource(R.string.download_delete_local_file),
                tint = Color(0xFFE57373),
                scheme = scheme,
            ) {
                onDismissRequest()
                downloadUi.onDeleteLocal(track)
            }
        }
    }

    ActionRow(
        icon = Icons.Default.PlayArrow,
        label = stringResource(R.string.track_item_play_next),
        scheme = scheme,
    ) {
        onDismissRequest()
        onPlayNext()
    }
    ActionRow(
        icon = Icons.AutoMirrored.Filled.QueueMusic,
        label = stringResource(R.string.track_item_add_to_queue),
        scheme = scheme,
    ) {
        onDismissRequest()
        onAddToQueue()
    }

    val albumId = track.albumId
    if (albumId != null && onOpenAlbum != null) {
        ActionRow(Icons.Default.Album, stringResource(R.string.track_actions_open_album), scheme = scheme) {
            onDismissRequest()
            onOpenAlbum(albumId)
        }
    } else if (metadataLoading && onOpenAlbum != null) {
        LoadingActionRow(Icons.Default.Album, stringResource(R.string.track_actions_open_album), scheme)
    }
    if (track.artist.isNotBlank() && onOpenArtist != null) {
        ActionRow(Icons.Default.Person, stringResource(R.string.track_actions_open_artist), scheme = scheme) {
            onDismissRequest()
            onOpenArtist(
                ArtistCredits.primary(track.artistCredits())
                    ?: ArtistCredit(track.artist, track.artistId)
            )
        }
    }

    GroupDivider(scheme)

    ActionRow(Icons.Default.Info, stringResource(R.string.track_info_menu), scheme = scheme) {
        onOpenInfo()
    }
    if (onOpenEqualizer != null) {
        ActionRow(Icons.Default.Equalizer, stringResource(R.string.player_equalizer_dsp), scheme = scheme) {
            onDismissRequest()
            onOpenEqualizer()
        }
    }
    if (onOpenAmbient != null) {
        ActionRow(Icons.Default.Bedtime, stringResource(R.string.ambient_mode_title), scheme = scheme) {
            onDismissRequest()
            onOpenAmbient()
        }
    }
    if (playbackOptions != null) {
        val sleepValue = sleepTimerValue(playbackOptions.sleepTimer)
        ActionRow(
            icon = Icons.Default.Timer,
            label = stringResource(R.string.sleep_timer_title),
            scheme = scheme,
            tint = if (playbackOptions.sleepTimer != SleepTimer.Off) accent else null,
            trailing = sleepValue?.let { value -> { TrailingValue(value, accent) } },
            onClick = onOpenSleep,
        )
        ActionRow(
            icon = Icons.Default.Speed,
            label = stringResource(R.string.playback_speed_title),
            scheme = scheme,
            tint = if (playbackOptions.speed != 1f) accent else null,
            trailing = { TrailingValue(formatSpeed(playbackOptions.speed), secondary) },
            onClick = onOpenSpeed,
        )
    }
    if (onShare != null) {
        ActionRow(Icons.Default.Share, stringResource(R.string.track_item_share), scheme = scheme) {
            onDismissRequest()
            onShare()
        }
    }

    if (sourceActions != null &&
        (track.id.startsWith("yt_") || track.id.startsWith("spotify_"))
    ) {
        GroupDivider(scheme)
        Text(
            text = stringResource(R.string.track_source_section),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = secondary,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 4.dp),
        )
        val override = sourceActions.overrideFor(track.id)
        SourceActionRow(
            label = stringResource(R.string.track_source_auto),
            selected = override == null,
            scheme = scheme,
        ) {
            onDismissRequest()
            sourceActions.onClear(track)
        }
        SourceActionRow(
            label = stringResource(R.string.track_play_via_youtube),
            selected = override == org.sonora.android.api.PlaybackSource.YOUTUBE_MUSIC,
            scheme = scheme,
        ) {
            onDismissRequest()
            sourceActions.onSelect(track, org.sonora.android.api.PlaybackSource.YOUTUBE_MUSIC)
        }
        SourceActionRow(
            label = stringResource(R.string.track_play_via_monochrome),
            selected = override == org.sonora.android.api.PlaybackSource.MONOCHROME,
            scheme = scheme,
        ) {
            onDismissRequest()
            sourceActions.onSelect(track, org.sonora.android.api.PlaybackSource.MONOCHROME)
        }
    }

}

/** What the sleep timer row shows: the time left, ticking, or the end of the track. */
@Composable
private fun sleepTimerValue(timer: SleepTimer): String? {
    val remainingMs by produceState(initialValue = remainingMs(timer), timer) {
        while (timer is SleepTimer.At) {
            value = remainingMs(timer)
            delay(1_000L)
        }
        value = remainingMs(timer)
    }
    return when (timer) {
        SleepTimer.Off -> null
        SleepTimer.EndOfTrack -> stringResource(R.string.sleep_timer_end_of_track)
        is SleepTimer.At -> formatCountdown(remainingMs)
    }
}

private fun remainingMs(timer: SleepTimer): Long =
    (timer as? SleepTimer.At)?.let { (it.endsAtElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L) } ?: 0L

private fun formatCountdown(ms: Long): String {
    val totalSeconds = (ms + 999L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

/** 1.25 reads "1,25×" in Italian and "1.25×" in English. */
private fun formatSpeed(speed: Float): String = DecimalFormat("0.##").format(speed.toDouble()) + "×"

@Composable
private fun TrailingValue(text: String, color: Color) {
    Text(text = text, style = MaterialTheme.typography.bodyLarge, color = color, maxLines = 1)
}

@Composable
private fun SubpageHeader(title: String, scheme: BackgroundScheme, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 22.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.audio_output_back),
                tint = scheme.onBackground,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = scheme.onBackground,
        )
    }
}

@Composable
private fun SleepTimerPage(
    options: PlayerPlaybackOptions,
    scheme: BackgroundScheme,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val timer = options.sleepTimer
    SubpageHeader(stringResource(R.string.sleep_timer_title), scheme, onBack)
    val status = when (timer) {
        SleepTimer.Off -> null
        SleepTimer.EndOfTrack -> stringResource(R.string.sleep_timer_stops_at_track_end)
        is SleepTimer.At -> sleepTimerValue(timer)?.let { stringResource(R.string.sleep_timer_stops_in, it) }
    }
    if (status != null) {
        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.accent,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 8.dp),
        )
    }
    SourceActionRow(stringResource(R.string.sleep_timer_off), selected = timer == SleepTimer.Off, scheme = scheme) {
        options.onCancelSleep()
        onDone()
    }
    SleepTimer.PRESET_MINUTES.forEach { minutes ->
        SourceActionRow(stringResource(R.string.sleep_timer_minutes, minutes), selected = false, scheme = scheme) {
            options.onSleepMinutes(minutes)
            onDone()
        }
    }
    SourceActionRow(
        stringResource(R.string.sleep_timer_end_of_track),
        selected = timer == SleepTimer.EndOfTrack,
        scheme = scheme,
    ) {
        options.onSleepAtTrackEnd()
        onDone()
    }
}

@Composable
private fun SpeedPage(
    options: PlayerPlaybackOptions,
    scheme: BackgroundScheme,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    SubpageHeader(stringResource(R.string.playback_speed_title), scheme, onBack)
    PLAYBACK_SPEEDS.forEach { speed ->
        val label = if (speed == 1f) {
            "${formatSpeed(speed)} · ${stringResource(R.string.playback_speed_normal)}"
        } else {
            formatSpeed(speed)
        }
        SourceActionRow(label, selected = speed == options.speed, scheme = scheme) {
            options.onSpeed(speed)
            onDone()
        }
    }
}

@Composable
private fun TrackInfoPage(
    track: Track,
    metadata: TrackMetadata?,
    metadataLoading: Boolean,
    scheme: BackgroundScheme,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 22.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.audio_output_back),
                tint = scheme.onBackground,
            )
        }
        Text(
            text = stringResource(R.string.track_info_menu),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = scheme.onBackground,
        )
    }
    TrackSheetHeader(track = track, scheme = scheme)
    Spacer(Modifier.height(14.dp))
    TrackInfoRows(
        track = track,
        metadata = metadata,
        metadataLoading = metadataLoading,
        scheme = scheme,
    )
}

@Composable
private fun TrackSheetHeader(track: Track, scheme: BackgroundScheme) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrackThumb(
            url = track.artworkUrl,
            size = 52.dp,
            shape = RoundedCornerShape(8.dp),
            contentDescription = track.title,
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                ),
                color = scheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = track.artist.ifBlank { stringResource(R.string.track_info_unknown_artist) },
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onBackgroundSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TrackInfoRows(
    track: Track,
    metadata: TrackMetadata?,
    metadataLoading: Boolean,
    scheme: BackgroundScheme,
) {
    val detailed = track.withMetadata(metadata)
    val unknown = stringResource(R.string.track_info_unknown)
    val pending = "…"
    val year = detailed.releaseYear?.toString()
        ?: if (metadataLoading) pending else unknown
    val rows = listOf(
        Icons.Default.Album to (stringResource(R.string.track_info_album) to (detailed.album ?: unknown)),
        Icons.Default.DateRange to (stringResource(R.string.track_info_year) to year),
        Icons.Default.Timer to (stringResource(R.string.track_info_duration) to detailed.formattedDuration),
        Icons.Default.Headphones to (stringResource(R.string.track_info_plays) to (detailed.streams?.takeIf { it > 0 }?.let(::formatTrackStreams) ?: unknown)),
        Icons.Default.Language to (stringResource(R.string.track_info_source) to detailed.provider),
        (if (detailed.isVideo) Icons.Default.Movie else Icons.Default.MusicNote) to (
            stringResource(R.string.track_info_type) to stringResource(
                if (detailed.isVideo) R.string.track_info_type_video else R.string.track_info_type_song
            )
        ),
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(scheme.glassTint.copy(alpha = 0.62f))
            .padding(horizontal = 14.dp, vertical = 4.dp)
    ) {
        rows.forEachIndexed { index, (icon, row) ->
            if (index > 0) {
                HorizontalDivider(
                    color = scheme.onBackground.copy(alpha = 0.08f),
                    thickness = 0.5.dp,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = scheme.onBackgroundTertiary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = row.first,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onBackgroundSecondary,
                    modifier = Modifier.width(78.dp),
                )
                Text(
                    text = row.second,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium, fontSize = 13.sp),
                    color = scheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    scheme: BackgroundScheme,
    tint: Color? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint ?: scheme.onBackground.copy(alpha = 0.78f),
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(18.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = scheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

@Composable
private fun SourceActionRow(
    label: String,
    selected: Boolean,
    scheme: BackgroundScheme,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = scheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(Icons.Default.Check, contentDescription = null, tint = scheme.accent, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun LoadingActionRow(icon: ImageVector, label: String, scheme: BackgroundScheme) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = scheme.onBackground.copy(alpha = 0.35f), modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(18.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = scheme.onBackground.copy(alpha = 0.35f), modifier = Modifier.weight(1f))
        CircularProgressIndicator(color = scheme.onBackgroundSecondary, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun DownloadProgressBadge(progress: Float, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CircularProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, color = accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
        Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = accent)
    }
}

@Composable
private fun GroupDivider(scheme: BackgroundScheme) {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 6.dp),
        thickness = 0.5.dp,
        color = scheme.onBackground.copy(alpha = 0.10f),
    )
}
