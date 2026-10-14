package org.sonora.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import org.sonora.android.R
import org.sonora.android.api.Album
import org.sonora.android.api.Artist
import org.sonora.android.api.Track
import org.sonora.android.playback.PlaybackState
import org.sonora.android.ui.components.LocalBottomChromeInset
import org.sonora.android.ui.components.SectionHeader
import org.sonora.android.ui.components.SourceSwitchMenuItems
import org.sonora.android.ui.components.TrackSourceActions
import org.sonora.android.ui.components.shimmer
import org.sonora.android.ui.theme.GlassCard
import org.sonora.android.ui.theme.GlassPanel
import org.sonora.android.ui.theme.GlassPlaceholder
import org.sonora.android.ui.theme.GlassTopBar
import org.sonora.android.ui.theme.LocalBackgroundScheme
import org.sonora.android.ui.theme.Radius
import org.sonora.android.ui.theme.SonoraBackground
import org.sonora.android.ui.theme.SonoraTextPrimary
import org.sonora.android.ui.theme.SonoraTheme
import org.sonora.android.ui.theme.SonoraTextSecondary
import org.sonora.android.ui.theme.SonoraTextTertiary
import org.sonora.android.ui.theme.Spacing
import org.sonora.android.ui.theme.pressScale
import java.text.NumberFormat
import java.util.Locale

@Composable
fun ArtistDetailScreen(
    artist: Artist?,
    topTracks: List<Track>,
    albums: List<Album>,
    isLoading: Boolean,
    errorMessage: String?,
    playbackState: PlaybackState,
    isFavorite: (String) -> Boolean,
    onBackClick: () -> Unit,
    onTrackClick: (Track) -> Unit,
    onFavoriteToggle: (Track) -> Unit,
    onPlayAllTracks: () -> Unit,
    onShuffleTracks: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onRetry: () -> Unit,
    onAddToQueue: ((Track) -> Unit)? = null,
    onPlayNextTrack: ((Track) -> Unit)? = null,
    onAddToPlaylist: ((Track) -> Unit)? = null,
    sourceActions: TrackSourceActions? = null,
    modifier: Modifier = Modifier
) {
    SonoraBackground(
        modifier = modifier.fillMaxSize(),
        coverUrl = artist?.pictureUrl
    ) {
        when {
            isLoading && artist == null -> {
                ArtistLoadingSkeleton()
            }

            errorMessage != null && artist == null -> {
                ArtistMessageState(
                    icon = Icons.Default.Person,
                    title = stringResource(R.string.artist_failed_to_load_title),
                    message = errorMessage,
                    onRetry = onRetry
                )
            }

            artist != null -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = LocalBottomChromeInset.current + 24.dp)
                ) {
                    item {
                        GlassTopBar(title = "", onBack = onBackClick)
                    }

                    // Hero panel: cover, real name, subtitle and primary actions
                    item {
                        ArtistHeroPanel(
                            artist = artist,
                            topTracksAvailable = topTracks.isNotEmpty(),
                            onPlayAllTracks = onPlayAllTracks,
                            onShuffleTracks = onShuffleTracks,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.gutter, vertical = Spacing.sm)
                        )
                    }

                    // Popular tracks
                    if (topTracks.isNotEmpty()) {
                        item {
                            SectionHeader(title = stringResource(R.string.artist_popular_tracks_count, topTracks.size))
                        }

                        itemsIndexed(topTracks) { index, track ->
                            val isCurrent = playbackState.currentTrack?.id == track.id
                            ArtistGlassTrackRow(
                                index = index + 1,
                                track = track,
                                isCurrent = isCurrent,
                                isPlaying = isCurrent && playbackState.isPlaying,
                                isFavorite = isFavorite(track.id),
                                onClick = { onTrackClick(track) },
                                onFavoriteToggle = { onFavoriteToggle(track) },
                                onAddToQueue = onAddToQueue?.let { { it(track) } },
                                onPlayNextTrack = onPlayNextTrack?.let { { it(track) } },
                                onAddToPlaylist = onAddToPlaylist?.let { { it(track) } },
                                sourceActions = sourceActions,
                                modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs)
                            )
                        }
                    }

                    // Discography / Albums row
                    if (albums.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(Spacing.md))
                            SectionHeader(title = stringResource(R.string.artist_albums_discography))
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = Spacing.gutter, vertical = Spacing.xs),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                            ) {
                                items(albums) { album ->
                                    ArtistAlbumCard(
                                        album = album,
                                        onClick = { onAlbumClick(album.id) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            else -> {
                ArtistMessageState(
                    icon = Icons.Default.Person,
                    title = stringResource(R.string.artist_not_available_title),
                    message = stringResource(R.string.artist_not_available_message),
                    onRetry = onRetry
                )
            }
        }
    }
}

@Composable
private fun ArtistHeroPanel(
    artist: Artist,
    topTracksAvailable: Boolean,
    onPlayAllTracks: () -> Unit,
    onShuffleTracks: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current
    GlassPanel(modifier = modifier, shape = RoundedCornerShape(Radius.Large)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(scheme.glassTint),
                contentAlignment = Alignment.Center
            ) {
                if (!artist.pictureUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = artist.pictureUrl,
                        contentDescription = artist.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = SonoraTextTertiary,
                        modifier = Modifier.size(52.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.md))

            Text(
                text = artist.name,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
                color = SonoraTextPrimary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            val subtitle = artistSubtitle(artist)
            if (subtitle.isNotBlank()) {
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = SonoraTextSecondary,
                    textAlign = TextAlign.Center
                )
            }

            if (topTracksAvailable) {
                Spacer(modifier = Modifier.height(Spacing.lg))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onPlayAllTracks,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = scheme.accent,
                            contentColor = if (scheme.isLight) Color.White else Color.Black
                        ),
                        shape = RoundedCornerShape(Radius.Medium),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(Spacing.sm))
                        Text(text = stringResource(R.string.artist_action_play), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }

                    OutlinedButton(
                        onClick = onShuffleTracks,
                        border = BorderStroke(1.dp, scheme.glassBorder),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = scheme.glassTint,
                            contentColor = SonoraTextPrimary
                        ),
                        shape = RoundedCornerShape(Radius.Medium)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shuffle,
                            contentDescription = stringResource(R.string.artist_action_shuffle),
                            tint = scheme.accent,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        Text(text = stringResource(R.string.artist_action_shuffle), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistGlassTrackRow(
    index: Int,
    track: Track,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onAddToQueue: (() -> Unit)? = null,
    onPlayNextTrack: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    sourceActions: TrackSourceActions? = null,
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current
    var menuExpanded by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(Radius.Small)

    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .clip(shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        shape = shape
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.width(28.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isCurrent) {
                    Icon(
                        imageVector = Icons.Default.Equalizer,
                        contentDescription = stringResource(R.string.artist_content_description_playing),
                        tint = scheme.accent,
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    Text(
                        text = "$index",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = SonoraTextTertiary,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.width(Spacing.sm))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 15.sp
                    ),
                    color = if (isCurrent) scheme.accent else SonoraTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (track.artist.isNotBlank()) {
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                        color = SonoraTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(Spacing.sm))

            Text(
                text = track.formattedDuration,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                color = SonoraTextTertiary
            )

            IconButton(
                onClick = onFavoriteToggle,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (isFavorite) stringResource(R.string.artist_content_description_remove_favorite) else stringResource(R.string.artist_content_description_add_favorite),
                    tint = if (isFavorite) scheme.accent else SonoraTextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }

            if (onPlayNextTrack != null || onAddToQueue != null || onAddToPlaylist != null || sourceActions != null) {
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.artist_content_description_more_options),
                            tint = SonoraTextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        shape = RoundedCornerShape(16.dp),
                        containerColor = org.sonora.android.ui.theme.SonoraOpaqueSurfaceCard,
                        border = androidx.compose.foundation.BorderStroke(1.dp, SonoraTheme.colors.cardBorder)
                    ) {
                        if (onPlayNextTrack != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.artist_action_play_next), color = SonoraTextPrimary) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = scheme.accent
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onPlayNextTrack()
                                }
                            )
                        }
                        if (onAddToQueue != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.artist_action_add_to_queue), color = SonoraTextPrimary) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                        contentDescription = null,
                                        tint = scheme.accent
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onAddToQueue()
                                }
                            )
                        }
                        if (onAddToPlaylist != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.artist_action_add_to_playlist), color = SonoraTextPrimary) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                                        contentDescription = null,
                                        tint = scheme.accent
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onAddToPlaylist()
                                }
                            )
                        }
                        if (sourceActions != null &&
                            (track.id.startsWith("yt_") || track.id.startsWith("spotify_")) &&
                            !track.provider.equals("Local", ignoreCase = true)
                        ) {
                            SourceSwitchMenuItems(
                                sourceOverride = sourceActions.overrideFor(track.id),
                                onPlayViaSource = { src ->
                                    menuExpanded = false
                                    sourceActions.onSelect(track, src)
                                },
                                onUseGlobalSource = {
                                    menuExpanded = false
                                    sourceActions.onClear(track)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistAlbumCard(
    album: Album,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(Radius.Small)

    Column(
        modifier = modifier
            .width(150.dp)
            .pressScale(interaction)
            .clip(shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    ) {
        GlassCard(
            modifier = Modifier.size(150.dp),
            shape = shape
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (!album.coverUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = album.coverUrl,
                        contentDescription = album.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Album,
                        contentDescription = null,
                        tint = SonoraTextTertiary,
                        modifier = Modifier.size(44.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(Spacing.sm))

        Text(
            text = album.title,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            ),
            color = SonoraTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        val year = album.releaseDate?.take(4)
        val tracksLabel = if (album.trackCount > 0) {
            pluralStringResource(R.plurals.artist_track_count, album.trackCount, album.trackCount)
        } else {
            ""
        }
        val subtitle = when {
            !year.isNullOrBlank() && tracksLabel.isNotEmpty() ->
                stringResource(R.string.artist_album_year_tracks_separator, year, tracksLabel)
            !year.isNullOrBlank() -> year
            else -> tracksLabel
        }

        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = SonoraTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ArtistLoadingSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.gutter)
            .padding(top = Spacing.lg, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .shimmer(RoundedCornerShape(Radius.Large))
        )
        repeat(6) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .shimmer(RoundedCornerShape(Radius.Small))
            )
        }
    }
}

@Composable
private fun ArtistMessageState(
    icon: ImageVector,
    title: String,
    message: String,
    onRetry: () -> Unit
) {
    val scheme = LocalBackgroundScheme.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xl),
        contentAlignment = Alignment.Center
    ) {
        GlassPlaceholder {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = scheme.onBackgroundTertiary,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(44.dp)
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = SonoraTextPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = SonoraTextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(Spacing.lg))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = scheme.accent,
                    contentColor = if (scheme.isLight) Color.White else Color.Black
                ),
                shape = RoundedCornerShape(Radius.Medium),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(Spacing.xs))
                Text(text = stringResource(R.string.artist_action_retry), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun artistSubtitle(artist: Artist): String {
    val fanFormatted = formatFans(artist.nbFan)
    val fansLabel = if (artist.nbFan > 0) {
        stringResource(R.string.artist_monthly_listeners, fanFormatted)
    } else {
        ""
    }
    val albumsLabel = if (artist.nbAlbum > 0) {
        pluralStringResource(R.plurals.artist_albums_count, artist.nbAlbum, artist.nbAlbum)
    } else {
        ""
    }
    return when {
        fansLabel.isNotEmpty() && albumsLabel.isNotEmpty() ->
            stringResource(R.string.artist_subtitle_separator, fansLabel, albumsLabel)
        fansLabel.isNotEmpty() -> fansLabel
        else -> albumsLabel
    }
}

private fun formatFans(fans: Int): String {
    return when {
        fans >= 1_000_000 -> String.format(Locale.US, "%.1fM", fans / 1_000_000.0)
        fans >= 1_000 -> String.format(Locale.US, "%.1fK", fans / 1_000.0)
        fans > 0 -> NumberFormat.getNumberInstance(Locale.US).format(fans)
        else -> "0"
    }
}
