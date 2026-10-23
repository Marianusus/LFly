package org.sonora.android.ui

import android.os.Build
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import org.sonora.android.api.YouTubeAlbum
import org.sonora.android.api.YouTubePlaylist
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import kotlinx.coroutines.launch
import coil.compose.AsyncImage
import org.sonora.android.R
import org.sonora.android.api.Track
import org.sonora.android.playback.DownloadProgress
import org.sonora.android.ui.collection.CollectionEmptyState
import org.sonora.android.ui.collection.CollectionHero
import org.sonora.android.ui.collection.CollectionLoadingSkeleton
import org.sonora.android.ui.collection.CollectionMetrics
import org.sonora.android.ui.collection.CollectionNowPlayingThread
import org.sonora.android.ui.collection.CollectionSectionHeader
import org.sonora.android.ui.collection.CollectionTopBar
import org.sonora.android.ui.collection.CollectionTrackRow
import org.sonora.android.ui.collection.formatCollectionDuration
import org.sonora.android.ui.collection.joinCollectionMeta
import org.sonora.android.ui.components.DownloadMenuEntry
import org.sonora.android.ui.components.LocalBottomChromeInset
import org.sonora.android.ui.components.SectionHeader
import org.sonora.android.ui.components.TrackItem
import org.sonora.android.ui.components.SourceSwitchMenuItems
import org.sonora.android.ui.components.TrackSourceActions
import org.sonora.android.ui.components.stableTrackKeys
import org.sonora.android.ui.components.asImageUrlOrNull
import org.sonora.android.ui.components.shimmer
import org.sonora.android.ui.theme.GlassCard
import org.sonora.android.ui.theme.LocalBackgroundScheme
import org.sonora.android.ui.theme.artworkBottomFade
import org.sonora.android.ui.theme.LocalMotionPreferences
import org.sonora.android.ui.theme.Radius
import org.sonora.android.ui.theme.SonoraBackground
import org.sonora.android.ui.theme.SonoraOpaqueSurfaceCard
import org.sonora.android.ui.theme.SonoraStatusOffline
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
    /** Full-width immersive banner (artist pages) instead of the collection sleeve. */
    immersiveHero: Boolean = false,
    onAlbumClick: (YouTubeAlbum) -> Unit = {},
    isLoading: Boolean,
    errorMessage: String?,
    currentTrackId: String?,
    isPlaying: Boolean,
    isFavorite: (String) -> Boolean,
    onBackClick: () -> Unit,
    onTrackClick: (Track, Int) -> Unit,
    onVideoClick: (Track) -> Unit = {},
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
    /** Album pages: used to hide a repeated artist on every row. */
    collectionArtist: String? = null,
    /** Album pages hide per-track artwork; playlists keep it. */
    showTrackArtwork: Boolean = true,
    /** Album pages keep the track order visible next to the cover. */
    showTrackNumbers: Boolean = false,
    downloadProgress: DownloadProgress? = null,
    onToggleCollectionDownload: (() -> Unit)? = null,
    onAddCollectionToQueue: (() -> Unit)? = null,
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
                Column(modifier = Modifier.fillMaxSize()) {
                    CollectionTopBar(
                        title = headerTitle,
                        collapsed = true,
                        onBack = onBackClick,
                    )
                    if (immersiveHero) {
                        YouTubeDetailLoadingSkeleton(modifier = Modifier.weight(1f))
                    } else {
                        CollectionLoadingSkeleton(modifier = Modifier.weight(1f))
                    }
                }
            }

            errorMessage != null && tracks.isEmpty() -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    CollectionTopBar(
                        title = headerTitle,
                        collapsed = true,
                        onBack = onBackClick,
                    )
                    CollectionEmptyState(
                        icon = Icons.Default.Album,
                        title = stringResource(R.string.collection_error_title),
                        message = errorMessage,
                        retryLabel = stringResource(R.string.yt_detail_retry),
                        onRetry = onRetry,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            else -> {
                if (immersiveHero) {
                    YouTubeArtistContent(
                        headerTitle = headerTitle,
                        headerSubtitle = headerSubtitle,
                        coverUrl = coverUrl,
                        tracks = tracks,
                        albums = albums,
                        singlesOrEps = singlesOrEps,
                        videos = videos,
                        currentTrackId = currentTrackId,
                        isPlaying = isPlaying,
                        isFavorite = isFavorite,
                        onBackClick = onBackClick,
                        onAlbumClick = onAlbumClick,
                        onTrackClick = onTrackClick,
                        onVideoClick = onVideoClick,
                        onFavoriteToggle = onFavoriteToggle,
                        onPlayAll = onPlayAll,
                        onShuffle = onShuffle,
                        isFollowing = isFollowing,
                        onToggleFollow = onToggleFollow,
                        onShowAllTracks = onShowAllTracks,
                        onAddToQueue = onAddToQueue,
                        onPlayNextTrack = onPlayNextTrack,
                        onAddToPlaylist = onAddToPlaylist,
                        sourceActions = sourceActions,
                    )
                } else {
                    YouTubeCollectionContent(
                        title = headerTitle,
                        metadata = headerSubtitle,
                        coverUrl = coverUrl,
                        tracks = tracks,
                        currentTrackId = currentTrackId,
                        isPlaying = isPlaying,
                        isFavorite = isFavorite,
                        onBackClick = onBackClick,
                        onTrackClick = onTrackClick,
                        onFavoriteToggle = onFavoriteToggle,
                        onPlayAll = onPlayAll,
                        onShuffle = onShuffle,
                        onRetry = onRetry,
                        onAddToQueue = onAddToQueue,
                        onPlayNextTrack = onPlayNextTrack,
                        onAddToPlaylist = onAddToPlaylist,
                        collectionArtist = collectionArtist,
                        showTrackArtwork = showTrackArtwork,
                        showTrackNumbers = showTrackNumbers,
                        downloadProgress = downloadProgress,
                        onToggleCollectionDownload = onToggleCollectionDownload,
                        onAddCollectionToQueue = onAddCollectionToQueue,
                        onSaveToLibrary = onSaveToLibrary,
                        isSavingToLibrary = isSavingToLibrary,
                        sourceActions = sourceActions,
                    )
                }
            }
        }
    }
}

@Composable
private fun YouTubeArtistContent(
    headerTitle: String,
    headerSubtitle: String?,
    coverUrl: String?,
    tracks: List<Track>,
    albums: List<YouTubeAlbum>,
    singlesOrEps: List<YouTubeAlbum>,
    videos: List<Track>,
    currentTrackId: String?,
    isPlaying: Boolean,
    isFavorite: (String) -> Boolean,
    onBackClick: () -> Unit,
    onAlbumClick: (YouTubeAlbum) -> Unit,
    onTrackClick: (Track, Int) -> Unit,
    onVideoClick: (Track) -> Unit,
    onFavoriteToggle: (Track) -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    isFollowing: Boolean?,
    onToggleFollow: (() -> Unit)?,
    onShowAllTracks: (() -> Unit)?,
    onAddToQueue: (Track) -> Unit,
    onPlayNextTrack: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    sourceActions: TrackSourceActions?,
) {
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
            item(key = "artist-hero", contentType = "hero") {
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
            }

            if (tracks.isEmpty()) {
                item(key = "empty", contentType = "empty") {
                    CollectionEmptyState(
                        icon = Icons.Default.MusicNote,
                        title = stringResource(R.string.yt_detail_no_tracks),
                        message = stringResource(R.string.yt_detail_no_tracks_body),
                    )
                }
            } else {
                item(key = "top-songs", contentType = "header") {
                    ArtistTopSongsHeader(
                        title = stringResource(R.string.yt_detail_popular_tracks),
                        onPlayAll = onPlayAll,
                        onShowAll = onShowAllTracks
                    )
                }

                itemsIndexed(
                    items = tracks,
                    key = { index, track -> "${track.id}_$index" },
                    contentType = { _, _ -> "track" },
                ) { index, track ->
                    val isCurrent = track.id == currentTrackId
                    TrackItem(
                        track = track,
                        isPlaying = isCurrent && isPlaying,
                        isCurrentTrack = isCurrent,
                        isFavorite = isFavorite(track.id),
                        onTrackClick = { onTrackClick(track, index) },
                        onFavoriteClick = { onFavoriteToggle(track) },
                        onAddToQueue = { onAddToQueue(track) },
                        onPlayNextTrack = { onPlayNextTrack(track) },
                        onAddToPlaylist = { onAddToPlaylist(track) },
                        sourceActions = sourceActions,
                        rank = null,
                        fallbackCoverUrl = coverUrl,
                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs)
                    )
                }
            }

            if (albums.isNotEmpty()) {
                item(key = "albums-header", contentType = "header") {
                    SectionHeader(title = stringResource(R.string.yt_detail_albums))
                }
                item(key = "albums", contentType = "carousel") {
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
                item(key = "singles-header", contentType = "header") {
                    SectionHeader(title = stringResource(R.string.yt_detail_singles_eps))
                }
                item(key = "singles", contentType = "carousel") {
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

            if (videos.isNotEmpty()) {
                item(key = "videos-header", contentType = "header") {
                    SectionHeader(title = stringResource(R.string.yt_detail_videos))
                }
                item(key = "videos", contentType = "carousel") {
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
                                onClick = { onVideoClick(video) }
                            )
                        }
                    }
                }
            }
        }
        ImmersiveTopBar(
            title = headerTitle,
            collapsed = isCollapsed,
            onBack = onBackClick,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

@Composable
private fun YouTubeCollectionContent(
    title: String,
    metadata: String?,
    coverUrl: String?,
    tracks: List<Track>,
    currentTrackId: String?,
    isPlaying: Boolean,
    isFavorite: (String) -> Boolean,
    onBackClick: () -> Unit,
    onTrackClick: (Track, Int) -> Unit,
    onFavoriteToggle: (Track) -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onRetry: () -> Unit,
    onAddToQueue: (Track) -> Unit,
    onPlayNextTrack: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    collectionArtist: String?,
    showTrackArtwork: Boolean,
    showTrackNumbers: Boolean,
    downloadProgress: DownloadProgress?,
    onToggleCollectionDownload: (() -> Unit)?,
    onAddCollectionToQueue: (() -> Unit)?,
    onSaveToLibrary: (() -> Unit)?,
    isSavingToLibrary: Boolean,
    sourceActions: TrackSourceActions?,
) {
    val scheme = LocalBackgroundScheme.current
    val motion = LocalMotionPreferences.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var topMenuExpanded by remember { mutableStateOf(false) }
    val collapsed by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 120
        }
    }
    val keys = remember(tracks) { stableTrackKeys(tracks) }
    val currentIndex = remember(tracks, currentTrackId) {
        tracks.indexOfFirst { it.id == currentTrackId }
    }
    val hasCurrent = currentIndex >= 0
    val firstTrackItem = 2 + if (hasCurrent) 1 else 0

    Column(modifier = Modifier.fillMaxSize()) {
        CollectionTopBar(
            title = title,
            collapsed = collapsed,
            onBack = onBackClick,
        ) {
            if (onSaveToLibrary != null) {
                IconButton(
                    onClick = onSaveToLibrary,
                    enabled = !isSavingToLibrary,
                    modifier = Modifier.size(CollectionMetrics.ActionSize),
                ) {
                    if (isSavingToLibrary) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = scheme.accent,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.LibraryAdd,
                            contentDescription = stringResource(R.string.yt_detail_save_to_library),
                            tint = scheme.onBackground,
                        )
                    }
                }
            }
            if (onAddCollectionToQueue != null) {
                Box {
                    IconButton(
                        onClick = { topMenuExpanded = true },
                        modifier = Modifier.size(CollectionMetrics.ActionSize),
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.collection_more_options),
                            tint = scheme.onBackground,
                        )
                    }
                    DropdownMenu(
                        expanded = topMenuExpanded,
                        onDismissRequest = { topMenuExpanded = false },
                        shape = RoundedCornerShape(16.dp),
                        containerColor = SonoraOpaqueSurfaceCard,
                        border = BorderStroke(1.dp, scheme.glassBorder),
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.collection_add_to_queue), color = scheme.onBackground) },
                            leadingIcon = {
                                Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null, tint = scheme.onBackgroundSecondary)
                            },
                            onClick = {
                                topMenuExpanded = false
                                onAddCollectionToQueue()
                            },
                        )
                    }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = LocalBottomChromeInset.current + Spacing.xl),
        ) {
            item(key = "hero", contentType = "hero") {
                CollectionHero(
                    title = title,
                    subtitle = null,
                    metadata = metadata,
                    hasTracks = tracks.isNotEmpty(),
                    onPlay = onPlayAll,
                    onShuffle = onShuffle,
                    downloadProgress = downloadProgress,
                    onToggleDownload = onToggleCollectionDownload,
                    modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
                    artwork = {
                        if (!coverUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = coverUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Album,
                                    contentDescription = null,
                                    tint = scheme.onBackgroundTertiary,
                                    modifier = Modifier.size(64.dp),
                                )
                            }
                        }
                    },
                )
            }

            if (tracks.isEmpty()) {
                item(key = "empty", contentType = "empty") {
                    CollectionEmptyState(
                        icon = Icons.Default.MusicNote,
                        title = stringResource(R.string.yt_detail_no_tracks),
                        message = stringResource(R.string.yt_detail_no_tracks_body),
                        retryLabel = stringResource(R.string.yt_detail_retry),
                        onRetry = onRetry,
                    )
                }
            } else {
                item(key = "tracklist-header", contentType = "header") {
                    CollectionSectionHeader(
                        title = stringResource(R.string.album_section_tracklist),
                        detail = joinCollectionMeta(
                            pluralStringResource(R.plurals.collection_track_count, tracks.size, tracks.size),
                            formatCollectionDuration(tracks.sumOf { it.durationMs }),
                        ),
                    )
                }

                if (hasCurrent) {
                    item(key = "now-playing", contentType = "now-playing") {
                        val currentTrack = tracks.getOrNull(currentIndex)
                        if (currentTrack != null) {
                            CollectionNowPlayingThread(
                                title = currentTrack.title,
                                isPlaying = isPlaying,
                                onClick = {
                                    scope.launch {
                                        val target = firstTrackItem + currentIndex
                                        if (motion.reduceMotion) listState.scrollToItem(target)
                                        else listState.animateScrollToItem(target)
                                    }
                                },
                            )
                        }
                    }
                }

                itemsIndexed(
                    items = tracks,
                    key = { index, _ -> keys.getOrElse(index) { "track_$index" } },
                    contentType = { _, _ -> "track" },
                ) { index, track ->
                    val isCurrent = track.id == currentTrackId
                    CollectionTrackRow(
                        track = track,
                        trackNumber = if (showTrackNumbers) index + 1 else null,
                        showArtwork = showTrackArtwork,
                        showArtist = collectionArtist.isNullOrBlank() ||
                            !track.artist.trim().equals(collectionArtist.trim(), ignoreCase = true),
                        isCurrent = isCurrent,
                        isPlaying = isCurrent && isPlaying,
                        isFavorite = isFavorite(track.id),
                        onPlay = { onTrackClick(track, index) },
                        onFavoriteClick = { onFavoriteToggle(track) },
                        fallbackCoverUrl = coverUrl,
                        menuContent = { dismiss ->
                            DownloadMenuEntry(
                                track = track,
                                onDismiss = dismiss,
                                accent = scheme.accent,
                                primary = scheme.onBackground,
                                secondary = scheme.onBackgroundSecondary,
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.album_play_next_action), color = scheme.onBackground) },
                                leadingIcon = {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = scheme.onBackgroundSecondary)
                                },
                                onClick = {
                                    dismiss()
                                    onPlayNextTrack(track)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.album_add_to_queue_action), color = scheme.onBackground) },
                                leadingIcon = {
                                    Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null, tint = scheme.onBackgroundSecondary)
                                },
                                onClick = {
                                    dismiss()
                                    onAddToQueue(track)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.album_add_to_playlist_action), color = scheme.onBackground) },
                                leadingIcon = {
                                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null, tint = scheme.onBackgroundSecondary)
                                },
                                onClick = {
                                    dismiss()
                                    onAddToPlaylist(track)
                                },
                            )
                            if (sourceActions != null &&
                                (track.id.startsWith("yt_") || track.id.startsWith("spotify_")) &&
                                !track.provider.equals("Local", ignoreCase = true)
                            ) {
                                SourceSwitchMenuItems(
                                    sourceOverride = sourceActions.overrideFor(track.id),
                                    onPlayViaSource = { src ->
                                        dismiss()
                                        sourceActions.onSelect(track, src)
                                    },
                                    onUseGlobalSource = {
                                        dismiss()
                                        sourceActions.onClear(track)
                                    },
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

private val ArtistBlurRadius = 64.dp

/**
 * Full-width immersive artist header (YouTube Music-style): edge-to-edge artwork that
 * dissolves into a blurred copy of itself, the artist name, the real audience text and
 * the primary actions (Follow pill + Shuffle + Play). No tabs and nothing beyond this
 * header.
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
        val image = imageUrl.asImageUrlOrNull()
        if (image != null) {
            // Blurred twin of the photo, exactly like the player's backdrop. It stays
            // inside the hero so scrolling never leaves the list over a bright blur.
            AsyncImage(
                model = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .artworkBottomFade(0.84f)
                    .blur(ArtistBlurRadius)
            )
            // Compose cannot blur below API 31: dim the sharp duplicate so it still
            // reads as a backdrop instead of a second hero image.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .artworkBottomFade(0.84f)
                        .background(Color.Black.copy(alpha = 0.45f))
                )
            }
            AsyncImage(
                model = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // The bottom of the sharp photo dissolves into its blurred twin, the
                // same way the player's sleeve melts into the backdrop.
                modifier = Modifier
                    .fillMaxSize()
                    .artworkBottomFade(0.68f)
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

        // Scrim for the copy and actions: strongest behind the text, then it fades back
        // into the canvas at the very bottom so the hero has no hard dark band.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.42f to Color.Transparent,
                        0.60f to Color.Black.copy(alpha = 0.40f),
                        0.80f to Color.Black.copy(alpha = 0.66f),
                        0.97f to Color.Black.copy(alpha = 0.76f),
                        1f to Color.Transparent
                    )
                )
        )

        // Keeps status-bar glyphs and the floating back button legible over bright art.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.38f),
                        0.55f to Color.Black.copy(alpha = 0.12f),
                        1f to Color.Transparent
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
                    color = Color.White.copy(alpha = 0.90f),
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
            // Over the photo the bar is transparent, so the glyph stays white like the
            // player controls; once the opaque title bar appears it follows the canvas.
            tint = if (collapsed) scheme.onBackground else Color.White,
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
        itemsIndexed(
            items = items,
            key = { index, item -> "${keyOf(item)}_$index" },
        ) { index, item ->
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
                    contentDescription = null,
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

@Composable
private fun YouTubeDetailLoadingSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
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
