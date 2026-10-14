package org.sonora.android.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.clickable
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import org.sonora.android.api.YouTubeAlbum
import org.sonora.android.api.YouTubePlaylist
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import org.sonora.android.R
import org.sonora.android.api.Track
import org.sonora.android.playback.PlaybackState
import org.sonora.android.ui.components.LocalBottomChromeInset
import org.sonora.android.ui.components.SectionHeader
import org.sonora.android.ui.components.TrackItem
import org.sonora.android.ui.components.TrackSourceActions
import org.sonora.android.ui.components.asHttpImageUrlOrNull
import org.sonora.android.ui.components.shimmer
import org.sonora.android.ui.theme.GlassCard
import org.sonora.android.ui.theme.GlassPanel
import org.sonora.android.ui.theme.GlassPlaceholder
import org.sonora.android.ui.theme.GlassTopBar
import org.sonora.android.ui.theme.LocalBackgroundScheme
import org.sonora.android.ui.theme.Radius
import org.sonora.android.ui.theme.SonoraBackground
import org.sonora.android.ui.theme.SonoraTextPrimary
import org.sonora.android.ui.theme.SonoraTextSecondary
import org.sonora.android.ui.theme.Spacing

/**
 * Minimal lazy detail screen shared by YouTube Music artists, albums and playlists.
 * Metadata and tracks are only fetched when opened (see SonoraViewModel). Playback uses
 * the existing queue mechanism; nothing here touches the audio resolver.
 */
@Composable
fun YouTubeDetailScreen(
    headerTitle: String,
    headerSubtitle: String?,
    coverUrl: String?,
    tracks: List<Track>,
    albums: List<YouTubeAlbum> = emptyList(),
    singlesOrEps: List<YouTubeAlbum> = emptyList(),
    videos: List<Track> = emptyList(),
    /** Full-width immersive banner (artist pages) instead of the centered square panel. */
    immersiveHero: Boolean = false,
    onAlbumClick: (YouTubeAlbum) -> Unit = {},
    isLoading: Boolean,
    errorMessage: String?,
    playbackState: PlaybackState,
    isFavorite: (String) -> Boolean,
    onBackClick: () -> Unit,
    onTrackClick: (Track, Int) -> Unit,
    onFavoriteToggle: (Track) -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onRetry: () -> Unit,
    isFollowing: Boolean? = null,
    onToggleFollow: (() -> Unit)? = null,
    onShowAllTracks: (() -> Unit)? = null,
    onAddToQueue: (Track) -> Unit = {},
    onPlayNextTrack: (Track) -> Unit = {},
    onAddToPlaylist: (Track) -> Unit = {},
    /** Playlist pages only: saves the full playlist into the local library. */
    onSaveToLibrary: (() -> Unit)? = null,
    isSavingToLibrary: Boolean = false,
    sourceActions: TrackSourceActions? = null,
    modifier: Modifier = Modifier
) {
    SonoraBackground(
        modifier = modifier.fillMaxSize(),
        coverUrl = coverUrl
    ) {
        when {
            isLoading && tracks.isEmpty() -> {
                YouTubeDetailLoadingSkeleton()
            }

            errorMessage != null && tracks.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(Spacing.xl),
                    contentAlignment = Alignment.Center
                ) {
                    GlassPlaceholder {
                        Icon(
                            imageVector = Icons.Default.Album,
                            contentDescription = null,
                            tint = LocalBackgroundScheme.current.onBackgroundTertiary,
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .size(44.dp)
                        )
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = SonoraTextSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(Spacing.lg))
                        Button(
                            onClick = onRetry,
                            shape = RoundedCornerShape(Radius.Medium),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = LocalBackgroundScheme.current.accent,
                                contentColor = if (LocalBackgroundScheme.current.isLight) Color.White else Color.Black
                            ),
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(Spacing.xs))
                            Text(stringResource(R.string.yt_detail_retry), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            else -> {
                val listState = rememberLazyListState()
                val isCollapsed by remember {
                    derivedStateOf {
                        listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 160
                    }
                }
                Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = LocalBottomChromeInset.current + 8.dp)
                ) {
                    if (!immersiveHero) {
                        item {
                            // Discreet plain back header (no glass bar, no "YouTube Music" label).
                            GlassTopBar(
                                title = "",
                                onBack = onBackClick,
                                glass = false,
                                showTitle = false
                            )
                        }
                    }

                    item {
                        if (immersiveHero) {
                            ArtistHeroBanner(
                                title = headerTitle,
                                subtitle = headerSubtitle,
                                imageUrl = coverUrl,
                                hasTracks = tracks.isNotEmpty(),
                                isFollowing = isFollowing,
                                onToggleFollow = onToggleFollow,
                                onPlayAll = onPlayAll,
                                onShuffle = onShuffle
                            )
                        } else {
                            YouTubeDetailHeroPanel(
                                title = headerTitle,
                                subtitle = headerSubtitle,
                                coverUrl = coverUrl,
                                hasTracks = tracks.isNotEmpty(),
                                isFollowing = isFollowing,
                                onToggleFollow = onToggleFollow,
                                onSaveToLibrary = onSaveToLibrary,
                                isSavingToLibrary = isSavingToLibrary,
                                onPlayAll = onPlayAll,
                                onShuffle = onShuffle,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Spacing.gutter, vertical = Spacing.sm)
                            )
                        }
                    }

                    if (tracks.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Spacing.xl, vertical = Spacing.xl),
                                contentAlignment = Alignment.Center
                            ) {
                                GlassPlaceholder {
                                    Text(
                                        text = stringResource(R.string.yt_detail_no_tracks),
                                        color = SonoraTextSecondary,
                                        style = MaterialTheme.typography.bodyMedium,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    } else {
                        item {
                            if (immersiveHero) {
                                ArtistTopSongsHeader(
                                    title = stringResource(R.string.yt_detail_popular_tracks),
                                    onPlayAll = onPlayAll,
                                    onShowAll = onShowAllTracks
                                )
                            } else {
                                SectionHeader(title = stringResource(R.string.yt_detail_popular_tracks))
                            }
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
                                rank = if (immersiveHero) null else index + 1,
                                fallbackCoverUrl = coverUrl,
                                modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs)
                            )
                        }
                    }

                    // Discography — separated sections, all real items (no artificial cap).
                    if (albums.isNotEmpty()) {
                        item { SectionHeader(title = stringResource(R.string.yt_detail_albums)) }
                        item {
                            MediaCarousel(
                                items = albums,
                                keyOf = { it.detailId ?: it.title },
                                onClick = onAlbumClick,
                                titleOf = { it.title },
                                subtitleOf = { albumSubtitle(it) },
                                imageOf = { it.thumbnailUrl }
                            )
                        }
                    }

                    if (singlesOrEps.isNotEmpty()) {
                        item { SectionHeader(title = stringResource(R.string.yt_detail_singles_eps)) }
                        item {
                            MediaCarousel(
                                items = singlesOrEps,
                                keyOf = { it.detailId ?: it.title },
                                onClick = onAlbumClick,
                                titleOf = { it.title },
                                subtitleOf = { albumSubtitle(it) },
                                imageOf = { it.thumbnailUrl }
                            )
                        }
                    }

                    // Playlists are intentionally NOT rendered on the artist page.

                    if (videos.isNotEmpty()) {
                        item { SectionHeader(title = stringResource(R.string.yt_detail_videos)) }
                        item {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = Spacing.gutter),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                            ) {
                                itemsIndexed(videos, key = { index, t -> "${t.id}_$index" }) { index, video ->
                                    YouTubeMediaCard(
                                        title = video.title,
                                        subtitle = video.artist.takeIf { it.isNotBlank() },
                                        imageUrl = video.coverUrl,
                                        onClick = { onTrackClick(video, index) }
                                    )
                                }
                            }
                        }
                    }
                }
                    if (immersiveHero) {
                        ImmersiveTopBar(
                            title = headerTitle,
                            collapsed = isCollapsed,
                            onBack = onBackClick,
                            modifier = Modifier.align(Alignment.TopCenter)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Full-width immersive artist header (YouTube Music-style): edge-to-edge artwork with a
 * bottom-up black gradient, the artist name, the real audience text and the primary
 * actions (Follow pill + Shuffle + Play). No tabs and nothing beyond this header.
 */
@Composable
private fun ArtistHeroBanner(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    hasTracks: Boolean,
    isFollowing: Boolean?,
    onToggleFollow: (() -> Unit)?,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit
) {
    val scheme = LocalBackgroundScheme.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(420.dp),
        contentAlignment = Alignment.Center
    ) {
        val image = imageUrl.asHttpImageUrlOrNull()
        if (image != null) {
            AsyncImage(
                model = image,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(scheme.gradient)))
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.30f),
                modifier = Modifier.size(96.dp)
            )
        }

        // Black gradient from the bottom up, fully faded out at 1/3 of the hero height.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.33f to Color.Transparent,
                        0.70f to Color.Black.copy(alpha = 0.55f),
                        1f to Color.Black
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = Spacing.gutter, vertical = Spacing.lg)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.82f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(Spacing.md))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isFollowing != null && onToggleFollow != null) {
                    if (isFollowing) {
                        OutlinedButton(
                            onClick = onToggleFollow,
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.6f)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = Color.Transparent,
                                contentColor = Color.White
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(Spacing.xs))
                            Text(stringResource(R.string.yt_detail_following), fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Button(
                            onClick = onToggleFollow,
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = Color.Black
                            )
                        ) {
                            Text(stringResource(R.string.yt_detail_follow), fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                IconButton(
                    onClick = onShuffle,
                    enabled = hasTracks,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.16f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Shuffle,
                        contentDescription = stringResource(R.string.yt_detail_shuffle),
                        tint = Color.White
                    )
                }

                Spacer(modifier = Modifier.width(Spacing.sm))

                IconButton(
                    onClick = onPlayAll,
                    enabled = hasTracks,
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(scheme.accent)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.yt_detail_play),
                        tint = if (scheme.isLight) Color.White else Color.Black,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
        }
    }
}

/**
 * Transparent top bar floating over the artist hero; becomes an opaque title bar once
 * the banner is scrolled away (no continuous blur, no jank — single color animation).
 */
@Composable
private fun ImmersiveTopBar(
    title: String,
    collapsed: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current
    val topColor = scheme.gradient.firstOrNull() ?: Color.Black
    val background by animateColorAsState(
        targetValue = if (collapsed) topColor.copy(alpha = 0.94f) else Color.Transparent,
        label = "immersiveTopBar"
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .statusBarsPadding()
            .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.yt_detail_back),
            tint = scheme.onBackground,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(onClick = onBack)
                .padding(12.dp)
        )
        if (collapsed) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = scheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Spacing.xs)
            )
        }
    }
}

/**
 * Artist "Top songs" header: title + "Riproduci tutto" pill + a chevron that opens the
 * full song list (only when the artist actually exposes a Show-all endpoint).
 */
@Composable
private fun ArtistTopSongsHeader(
    title: String,
    onPlayAll: () -> Unit,
    onShowAll: (() -> Unit)?
) {
    val scheme = LocalBackgroundScheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = scheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        OutlinedButton(
            onClick = onPlayAll,
            shape = RoundedCornerShape(50),
            border = BorderStroke(1.dp, scheme.glassBorder),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = scheme.glassTint,
                contentColor = scheme.onBackground
            )
        ) {
            Text(stringResource(R.string.yt_detail_play_all), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
        if (onShowAll != null) {
            IconButton(onClick = onShowAll) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(R.string.yt_detail_all_tracks),
                    tint = scheme.onBackground
                )
            }
        }
    }
}

/** "Album • 2024 • 12 brani" (only real fields; null when nothing is available). */
@Composable
private fun albumSubtitle(album: YouTubeAlbum): String? =
    listOfNotNull(
        album.type?.takeIf { it.isNotBlank() },
        album.year?.takeIf { it.isNotBlank() },
        album.trackCount?.let { pluralStringResource(R.plurals.yt_detail_track_count, it, it) }
    ).joinToString(" • ").ifBlank { null }

/** Horizontal, fully scrollable carousel of media cards (all received items). */
@Composable
private fun <T> MediaCarousel(
    items: List<T>,
    keyOf: (T) -> Any,
    onClick: (T) -> Unit,
    titleOf: (T) -> String,
    subtitleOf: @Composable (T) -> String?,
    imageOf: (T) -> String?
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = Spacing.gutter),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        items(items, key = { keyOf(it) }) { item ->
            YouTubeMediaCard(
                title = titleOf(item),
                subtitle = subtitleOf(item),
                imageUrl = imageOf(item),
                onClick = { onClick(item) }
            )
        }
    }
}

/**
 * Compact glass media card used for the artist Album / Playlist carousels.
 */
@Composable
private fun YouTubeMediaCard(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    onClick: () -> Unit
) {
    val scheme = LocalBackgroundScheme.current
    GlassCard(
        modifier = Modifier
            .width(180.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(Radius.Small)),
            contentAlignment = Alignment.Center
        ) {
            if (!imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Album,
                    contentDescription = null,
                    tint = scheme.onBackgroundTertiary,
                    modifier = Modifier.size(40.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(Spacing.sm))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = scheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = Spacing.sm)
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onBackgroundSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs)
            )
        }
    }
}

/**
 * Liquid Glass hero panel: artwork, title, subtitle and the primary actions
 * (Play, Shuffle and an optional Follow toggle).
 */
@Composable
private fun YouTubeDetailHeroPanel(
    title: String,
    subtitle: String?,
    coverUrl: String?,
    hasTracks: Boolean,
    isFollowing: Boolean?,
    onToggleFollow: (() -> Unit)?,
    onSaveToLibrary: (() -> Unit)? = null,
    isSavingToLibrary: Boolean = false,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
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
            GlassCard(
                modifier = Modifier.size(200.dp),
                shape = RoundedCornerShape(Radius.Medium)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    if (!coverUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = coverUrl,
                            contentDescription = title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = SonoraTextSecondary,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.lg))

            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = SonoraTextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )

            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SonoraTextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(Spacing.lg))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                Button(
                    onClick = onPlayAll,
                    enabled = hasTracks,
                    shape = RoundedCornerShape(Radius.Medium),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = scheme.accent,
                        contentColor = if (scheme.isLight) Color.White else Color.Black
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    Text(stringResource(R.string.yt_detail_play), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }

                OutlinedButton(
                    onClick = onShuffle,
                    enabled = hasTracks,
                    border = BorderStroke(1.dp, scheme.glassBorder),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = scheme.glassTint,
                        contentColor = SonoraTextPrimary
                    ),
                    shape = RoundedCornerShape(Radius.Medium)
                ) {
                    Icon(
                        imageVector = Icons.Default.Shuffle,
                        contentDescription = stringResource(R.string.yt_detail_shuffle),
                        tint = if (hasTracks) scheme.accent else SonoraTextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    Text(stringResource(R.string.yt_detail_shuffle), fontWeight = FontWeight.SemiBold)
                }

                if (isFollowing != null && onToggleFollow != null) {
                    IconButton(onClick = onToggleFollow) {
                        Icon(
                            imageVector = if (isFollowing) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = if (isFollowing) stringResource(R.string.yt_detail_unfollow) else stringResource(R.string.yt_detail_follow),
                            tint = if (isFollowing) scheme.accent else SonoraTextSecondary
                        )
                    }
                }

                if (onSaveToLibrary != null) {
                    IconButton(onClick = onSaveToLibrary, enabled = !isSavingToLibrary) {
                        if (isSavingToLibrary) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = scheme.accent
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.LibraryAdd,
                                contentDescription = stringResource(R.string.yt_detail_save_to_library),
                                tint = SonoraTextSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun YouTubeDetailLoadingSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.gutter)
            .padding(top = Spacing.lg, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
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
