package org.sonora.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.sonora.android.R
import org.sonora.android.api.Track
import org.sonora.android.api.lyrics.sources.LyricsSource
import org.sonora.android.playback.OfflineLyricsInfo
import org.sonora.android.playback.OfflineLyricsStatus
import org.sonora.android.ui.collection.CollectionEmptyState
import org.sonora.android.ui.collection.CollectionTopBar
import org.sonora.android.ui.components.LyricsProviderSheet
import org.sonora.android.ui.components.LocalBottomChromeInset
import org.sonora.android.ui.components.SegmentedPills
import org.sonora.android.ui.components.TrackThumb
import org.sonora.android.ui.theme.LocalBackgroundScheme
import org.sonora.android.ui.theme.SonoraBackground
import org.sonora.android.ui.theme.SonoraOpaqueSurfaceCard
import org.sonora.android.ui.theme.SonoraStatusOffline
import org.sonora.android.ui.theme.Spacing

private enum class LyricsFilter { ALL, WITH, WITHOUT }

@Composable
fun OfflineLyricsScreen(
    downloads: List<Track>,
    lyricsStates: Map<String, OfflineLyricsInfo>,
    sourceForTrack: (Track) -> LyricsSource?,
    onBackClick: () -> Unit,
    onDownloadLyrics: (Track, LyricsSource?) -> Unit,
    onRemoveLyrics: (Track) -> Unit,
    onDownloadMissing: () -> Unit,
    onLoadStates: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current
    var filter by remember { mutableStateOf(LyricsFilter.ALL) }
    var sheetTrack by remember { mutableStateOf<Track?>(null) }
    var removeTrack by remember { mutableStateOf<Track?>(null) }

    LaunchedEffect(downloads) { onLoadStates() }

    val foundCount = downloads.count {
        lyricsStates[it.id]?.status == OfflineLyricsStatus.FOUND
    }
    val missingCount = downloads.count {
        when (lyricsStates[it.id]?.status ?: OfflineLyricsStatus.NONE) {
            OfflineLyricsStatus.NONE,
            OfflineLyricsStatus.NOT_FOUND,
            OfflineLyricsStatus.ERROR -> true

            OfflineLyricsStatus.FOUND,
            OfflineLyricsStatus.FETCHING -> false
        }
    }
    val visible = when (filter) {
        LyricsFilter.ALL -> downloads
        LyricsFilter.WITH -> downloads.filter {
            lyricsStates[it.id]?.status == OfflineLyricsStatus.FOUND
        }
        LyricsFilter.WITHOUT -> downloads.filter {
            lyricsStates[it.id]?.status != OfflineLyricsStatus.FOUND
        }
    }

    SonoraBackground(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            CollectionTopBar(
                title = stringResource(R.string.offline_lyrics_title),
                collapsed = true,
                onBack = onBackClick,
            )

            if (downloads.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CollectionEmptyState(
                        icon = Icons.Default.DownloadDone,
                        title = stringResource(R.string.offline_lyrics_empty_title),
                        message = stringResource(R.string.offline_lyrics_empty_body),
                    )
                }
            } else {
                OfflineLyricsSummary(
                    downloadCount = downloads.size,
                    foundCount = foundCount,
                    missingCount = missingCount,
                    onDownloadMissing = onDownloadMissing,
                )

                val filterLabels = mapOf(
                    LyricsFilter.ALL to stringResource(R.string.offline_lyrics_filter_all),
                    LyricsFilter.WITH to stringResource(R.string.offline_lyrics_filter_with),
                    LyricsFilter.WITHOUT to stringResource(R.string.offline_lyrics_filter_without),
                )
                SegmentedPills(
                    items = LyricsFilter.entries.toList(),
                    selected = filter,
                    onSelect = { filter = it },
                    label = { filterLabels.getValue(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.gutter)
                        .padding(top = Spacing.xs, bottom = Spacing.md),
                    slidingIndicator = true,
                )

                if (visible.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.gutter),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.offline_lyrics_filter_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onBackgroundSecondary,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(
                            bottom = LocalBottomChromeInset.current + Spacing.xl,
                        ),
                    ) {
                        items(items = visible, key = { it.id }) { track ->
                            Column(modifier = Modifier.fillMaxWidth()) {
                                OfflineLyricsRow(
                                    track = track,
                                    info = lyricsStates[track.id],
                                    source = sourceForTrack(track),
                                    onChooseSource = { sheetTrack = track },
                                    onDownload = {
                                        onDownloadLyrics(track, sourceForTrack(track))
                                    },
                                    onRemove = { removeTrack = track },
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(
                                        start = Spacing.gutter + 48.dp + Spacing.md,
                                        end = Spacing.gutter,
                                    ),
                                    thickness = 1.dp,
                                    color = scheme.glassBorder.copy(alpha = 0.35f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val trackForSheet = sheetTrack
    if (trackForSheet != null) {
        LyricsProviderSheet(
            states = emptyMap(),
            active = sourceForTrack(trackForSheet),
            onSelect = { source ->
                onDownloadLyrics(trackForSheet, source)
                sheetTrack = null
            },
            onDismiss = { sheetTrack = null },
        )
    }

    val trackToRemove = removeTrack
    if (trackToRemove != null) {
        AlertDialog(
            onDismissRequest = { removeTrack = null },
            containerColor = SonoraOpaqueSurfaceCard,
            title = {
                Text(
                    text = stringResource(R.string.offline_lyrics_remove_title),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    color = scheme.onBackground,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.offline_lyrics_remove_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onBackgroundSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemoveLyrics(trackToRemove)
                        removeTrack = null
                    },
                ) {
                    Text(
                        text = stringResource(R.string.offline_lyrics_remove_confirm),
                        color = SonoraStatusOffline,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { removeTrack = null }) {
                    Text(
                        text = stringResource(R.string.offline_lyrics_remove_cancel),
                        color = scheme.onBackgroundSecondary,
                    )
                }
            },
        )
    }
}

@Composable
private fun OfflineLyricsSummary(
    downloadCount: Int,
    foundCount: Int,
    missingCount: Int,
    onDownloadMissing: () -> Unit,
) {
    val scheme = LocalBackgroundScheme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
    ) {
        Text(
            text = stringResource(R.string.offline_lyrics_summary, downloadCount, foundCount),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onBackgroundSecondary,
        )
        if (missingCount > 0) {
            Spacer(modifier = Modifier.height(Spacing.md))
            OutlinedButton(
                onClick = onDownloadMissing,
                modifier = Modifier.heightIn(min = 48.dp),
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.dp, scheme.glassBorder),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = scheme.accent,
                ),
                contentPadding = PaddingValues(
                    horizontal = Spacing.lg,
                    vertical = Spacing.sm,
                ),
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(
                        R.string.offline_lyrics_download_missing,
                        missingCount,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun OfflineLyricsRow(
    track: Track,
    info: OfflineLyricsInfo?,
    source: LyricsSource?,
    onChooseSource: () -> Unit,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
) {
    val scheme = LocalBackgroundScheme.current
    val status = info?.status ?: OfflineLyricsStatus.NONE
    val chooseSourceLabel = stringResource(R.string.offline_lyrics_choose_source)
    val sourceLabel = source?.label
        ?: stringResource(R.string.lyrics_source_automatic)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TrackThumb(
                url = track.coverUrl,
                size = 48.dp,
                shape = RoundedCornerShape(10.dp),
                contentDescription = track.title,
            )
            Spacer(modifier = Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onBackgroundSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            LyricsRowAction(
                status = status,
                onDownload = onDownload,
            )
        }
        Row(
            modifier = Modifier.padding(start = 48.dp + Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LyricsStatusLine(
                info = info,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onChooseSource,
                enabled = status != OfflineLyricsStatus.FETCHING,
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .semantics { contentDescription = chooseSourceLabel },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = scheme.accent,
                ),
                contentPadding = PaddingValues(horizontal = Spacing.sm),
            ) {
                Text(
                    text = sourceLabel,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 96.dp),
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (status == OfflineLyricsStatus.FOUND || status == OfflineLyricsStatus.NOT_FOUND) {
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = stringResource(R.string.offline_lyrics_action_remove),
                        tint = scheme.onBackgroundSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun LyricsStatusLine(
    info: OfflineLyricsInfo?,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalBackgroundScheme.current
    val status = info?.status ?: OfflineLyricsStatus.NONE
    val text = when (status) {
        OfflineLyricsStatus.FETCHING ->
            stringResource(R.string.offline_lyrics_status_fetching)

        OfflineLyricsStatus.FOUND ->
            stringResource(R.string.offline_lyrics_status_found, info?.source ?: "—")

        OfflineLyricsStatus.NOT_FOUND ->
            stringResource(R.string.offline_lyrics_status_not_found)

        OfflineLyricsStatus.ERROR ->
            stringResource(R.string.offline_lyrics_status_error)

        OfflineLyricsStatus.NONE ->
            stringResource(R.string.offline_lyrics_status_none)
    }
    val color = when (status) {
        OfflineLyricsStatus.FETCHING -> scheme.onBackgroundSecondary
        OfflineLyricsStatus.FOUND -> scheme.accent
        OfflineLyricsStatus.NOT_FOUND -> scheme.onBackgroundSecondary
        OfflineLyricsStatus.ERROR -> SonoraStatusOffline
        OfflineLyricsStatus.NONE -> scheme.onBackgroundSecondary
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (status == OfflineLyricsStatus.FETCHING) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = scheme.onBackgroundSecondary,
            )
            Spacer(modifier = Modifier.width(Spacing.xs))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LyricsRowAction(
    status: OfflineLyricsStatus,
    onDownload: () -> Unit,
) {
    val scheme = LocalBackgroundScheme.current
    if (status == OfflineLyricsStatus.FETCHING) {
        val label = stringResource(R.string.offline_lyrics_status_fetching)
        IconButton(
            onClick = {},
            enabled = false,
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = label },
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = scheme.accent,
            )
        }
    } else {
        val isFound = status == OfflineLyricsStatus.FOUND
        IconButton(
            onClick = onDownload,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = if (isFound) Icons.Default.Refresh else Icons.Default.Download,
                contentDescription = stringResource(
                    if (isFound) R.string.offline_lyrics_action_redownload
                    else R.string.offline_lyrics_action_download
                ),
                tint = scheme.onBackgroundSecondary,
            )
        }
    }
}
