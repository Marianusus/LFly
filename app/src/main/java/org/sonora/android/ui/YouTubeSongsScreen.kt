package org.sonora.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.sonora.android.api.Track
import org.sonora.android.playback.PlaybackState
import org.sonora.android.ui.components.LocalBottomChromeInset
import org.sonora.android.ui.components.TrackItem
import org.sonora.android.ui.components.TrackSourceActions
import org.sonora.android.ui.theme.GlassPlaceholder
import org.sonora.android.ui.theme.GlassTopBar
import org.sonora.android.ui.theme.LocalBackgroundScheme
import org.sonora.android.ui.theme.SonoraBackground
import org.sonora.android.ui.theme.SonoraTextSecondary
import org.sonora.android.ui.theme.Spacing

/**
 * Full artist song list ("Cele mai populare melodii"), opened from the artist page chevron.
 * Lazy-loads additional pages through the real continuation token; never invents data.
 */
@Composable
fun YouTubeSongsScreen(
    title: String,
    tracks: List<Track>,
    isLoading: Boolean,
    errorMessage: String?,
    hasMore: Boolean,
    playbackState: PlaybackState,
    isFavorite: (String) -> Boolean,
    onBackClick: () -> Unit,
    onTrackClick: (Track, Int) -> Unit,
    onFavoriteToggle: (Track) -> Unit,
    onLoadMore: () -> Unit,
    onAddToQueue: (Track) -> Unit,
    onPlayNextTrack: (Track) -> Unit = {},
    onAddToPlaylist: (Track) -> Unit,
    sourceActions: TrackSourceActions? = null,
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current

    SonoraBackground(modifier = modifier.fillMaxSize(), coverUrl = tracks.firstOrNull()?.coverUrl) {
        when {
            isLoading && tracks.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = scheme.accent)
                }
            }

            errorMessage != null && tracks.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(Spacing.xl),
                    contentAlignment = Alignment.Center
                ) {
                    GlassPlaceholder {
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = SonoraTextSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = LocalBottomChromeInset.current + 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    item {
                        GlassTopBar(
                            title = title,
                            onBack = onBackClick,
                            glass = false,
                            showTitle = true
                        )
                    }

                    itemsIndexed(tracks, key = { index, track -> "${track.id}_$index" }) { index, track ->
                        val isCurrent = playbackState.currentTrack?.id == track.id
                        TrackItem(
                            track = track,
                            isPlaying = isCurrent && playbackState.isPlaying,
                            isCurrentTrack = isCurrent,
                            isFavorite = isFavorite(track.id),
                            onTrackClick = { onTrackClick(track, index) },
                            onFavoriteClick = { onFavoriteToggle(track) },
                            onAddToQueue = { onAddToQueue(track) },
                            onPlayNextTrack = { onPlayNextTrack(track) },
                            onAddToPlaylist = { onAddToPlaylist(track) },
                            sourceActions = sourceActions,
                            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs)
                        )
                    }

                    if (hasMore) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(64.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = scheme.accent)
                            }
                            LaunchedEffect(tracks.size) { onLoadMore() }
                        }
                    }
                }
            }
        }
    }
}
