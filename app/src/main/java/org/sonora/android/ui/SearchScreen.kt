package org.sonora.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
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
import org.sonora.android.api.YouTubeAlbum
import org.sonora.android.api.YouTubeArtist
import org.sonora.android.api.YouTubePlaylist
import org.sonora.android.playback.PlaybackState
import org.sonora.android.ui.components.LocalBottomChromeInset
import org.sonora.android.ui.components.SectionHeader
import org.sonora.android.ui.components.SegmentedPills
import org.sonora.android.ui.components.TrackItem
import org.sonora.android.ui.components.TrackSourceActions
import org.sonora.android.ui.components.shimmer
import org.sonora.android.ui.theme.GlassCard
import org.sonora.android.ui.theme.GlassChip
import org.sonora.android.ui.theme.GlassPanel
import org.sonora.android.ui.theme.GlassPlaceholder
import org.sonora.android.ui.theme.LocalBackgroundScheme
import org.sonora.android.ui.theme.Radius
import org.sonora.android.ui.theme.SonoraAmoledBlack
import org.sonora.android.ui.theme.SonoraTheme
import org.sonora.android.ui.theme.SonoraSurfaceHighlight
import org.sonora.android.ui.theme.SonoraTextPrimary
import org.sonora.android.ui.theme.SonoraTextSecondary
import org.sonora.android.ui.theme.SonoraTextTertiary
import org.sonora.android.ui.theme.Spacing
import org.sonora.android.ui.theme.pressScale

enum class SearchCategory(@androidx.annotation.StringRes val labelRes: Int) {
    ALL(R.string.search_category_all),
    TRACKS(R.string.search_category_tracks),
    VIDEOS(R.string.search_category_videos),
    ARTISTS(R.string.search_category_artists),
    ALBUMS(R.string.search_category_albums),
    PLAYLISTS(R.string.search_category_playlists)
}

@Composable
fun SearchScreen(
    query: String,
    selectedCategory: SearchCategory,
    searchResults: List<Track>,
    ytArtistResults: List<YouTubeArtist> = emptyList(),
    ytAlbumResults: List<YouTubeAlbum> = emptyList(),
    ytPlaylistResults: List<YouTubePlaylist> = emptyList(),
    isSearching: Boolean,
    playbackState: PlaybackState,
    isFavorite: (String) -> Boolean,
    isDisliked: (String) -> Boolean = { false },
    onQueryChange: (String) -> Unit,
    onCategoryChange: (SearchCategory) -> Unit,
    onTrackClick: (Track) -> Unit,
    onFavoriteToggle: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit = {},
    onPlayNextTrack: (Track) -> Unit = {},
    onYouTubeArtistClick: (YouTubeArtist) -> Unit = {},
    onYouTubeAlbumClick: (YouTubeAlbum) -> Unit = {},
    onYouTubePlaylistClick: (YouTubePlaylist) -> Unit = {},
    onShareTrack: (Track) -> Unit = {},
    onDislikeTrack: (Track) -> Unit = {},
    onAddToPlaylist: (Track) -> Unit = {},
    sourceActions: TrackSourceActions? = null,
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current
    val categoryLabels = SearchCategory.values().map { it to stringResource(it.labelRes) }.toMap()

    Surface(
        modifier = modifier.fillMaxSize(),
        color = if (SonoraTheme.colors.isLiquidGlass) Color.Transparent else SonoraAmoledBlack
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(top = 16.dp)
        ) {
            // Header Title
            Text(
                text = stringResource(R.string.search_title),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = scheme.onBackground
                ),
                modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.xs)
            )

            Spacer(modifier = Modifier.height(Spacing.sm))

            // Liquid Glass search field
            GlassPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.gutter),
                shape = RoundedCornerShape(28.dp)
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onBackground),
                    cursorBrush = SolidColor(scheme.accent),
                    decorationBox = { innerTextField ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = stringResource(R.string.search_title),
                                tint = scheme.onBackgroundSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(Spacing.sm))
                            Box(modifier = Modifier.weight(1f)) {
                                if (query.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.search_field_placeholder),
                                        color = scheme.onBackgroundTertiary,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                                innerTextField()
                            }
                            if (query.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(Spacing.sm))
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = stringResource(R.string.search_clear_query),
                                    tint = scheme.onBackgroundSecondary,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .clickable { onQueryChange("") }
                                )
                            }
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(Spacing.md))

            // Category pills
            SegmentedPills(
                items = SearchCategory.values().toList(),
                selected = selectedCategory,
                onSelect = onCategoryChange,
                label = { categoryLabels.getValue(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.gutter)
            )

            Spacer(modifier = Modifier.height(Spacing.md))

            // Search Content / Results List
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                when {
                    isSearching -> {
                        SearchLoadingSkeleton()
                    }

                    query.isBlank() -> {
                        EmptySearchPrompt(onSuggestionClick = onQueryChange)
                    }

                    else -> {
                        // Keep video-only entries too: they often have no duration ("—"), but the
                        // client already validated id/title, so filter on that instead of duration.
                        val validTracks = remember(searchResults) {
                            searchResults.filter { it.id.isNotBlank() && it.title.isNotBlank() }
                        }
                        // Songs and videos share the same source list: songs live in "Brani",
                        // videos get their own dedicated tab.
                        val songResults = remember(validTracks) { validTracks.filter { !it.isVideo } }
                        val videoResults = remember(validTracks) { validTracks.filter { it.isVideo } }
                        val isAllEmpty = validTracks.isEmpty() && ytArtistResults.isEmpty() &&
                            ytAlbumResults.isEmpty() && ytPlaylistResults.isEmpty()

                        if (isAllEmpty) {
                            SearchEmptyPlaceholder(
                                title = stringResource(R.string.search_empty_results_title),
                                message = stringResource(R.string.search_empty_results_message, query)
                            )
                        } else {
                            // Results view based on selected category
                            when (selectedCategory) {
                                SearchCategory.ALL -> {
                                    SearchAllContent(
                                        tracks = songResults,
                                        ytArtists = ytArtistResults,
                                        ytAlbums = ytAlbumResults,
                                        ytPlaylists = ytPlaylistResults,
                                        playbackState = playbackState,
                                        isFavorite = isFavorite,
                                        isDisliked = isDisliked,
                                        onTrackClick = onTrackClick,
                                        onFavoriteToggle = onFavoriteToggle,
                                        onAddToQueue = onAddToQueue,
                                        onPlayNextTrack = onPlayNextTrack,
                                        onYouTubeArtistClick = onYouTubeArtistClick,
                                        onYouTubeAlbumClick = onYouTubeAlbumClick,
                                        onYouTubePlaylistClick = onYouTubePlaylistClick,
                                        onShareTrack = onShareTrack,
                                        onDislikeTrack = onDislikeTrack,
                                        onAddToPlaylist = onAddToPlaylist,
                                        sourceActions = sourceActions
                                    )
                                }

                                SearchCategory.TRACKS -> {
                                    if (songResults.isEmpty()) {
                                        SearchEmptyPlaceholder(
                                            title = stringResource(R.string.search_empty_tracks_title),
                                            message = stringResource(R.string.search_empty_tracks_message, query)
                                        )
                                    } else {
                                        LazyColumn(
                                            modifier = Modifier.fillMaxSize(),
                                            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = LocalBottomChromeInset.current + 8.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            item { SectionHeader(title = stringResource(R.string.search_category_tracks)) }
                                            items(songResults, key = { it.id }) { track ->
                                                TrackResultRow(
                                                    track = track,
                                                    playbackState = playbackState,
                                                    isFavorite = isFavorite,
                                                    isDisliked = isDisliked,
                                                    onTrackClick = onTrackClick,
                                                    onFavoriteToggle = onFavoriteToggle,
                                                    onAddToQueue = onAddToQueue,
                                                    onPlayNextTrack = onPlayNextTrack,
                                                    onAddToPlaylist = onAddToPlaylist,
                                                    onShareTrack = onShareTrack,
                                                    onDislikeTrack = onDislikeTrack,
                                                    sourceActions = sourceActions
                                                )
                                            }
                                        }
                                    }
                                }

                                SearchCategory.VIDEOS -> {
                                    if (videoResults.isEmpty()) {
                                        SearchEmptyPlaceholder(
                                            title = stringResource(R.string.search_empty_videos_title),
                                            message = stringResource(R.string.search_empty_videos_message, query)
                                        )
                                    } else {
                                        LazyColumn(
                                            modifier = Modifier.fillMaxSize(),
                                            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = LocalBottomChromeInset.current + 8.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            item { SectionHeader(title = stringResource(R.string.search_category_videos)) }
                                            items(videoResults, key = { it.id }) { track ->
                                                TrackResultRow(
                                                    track = track,
                                                    playbackState = playbackState,
                                                    isFavorite = isFavorite,
                                                    isDisliked = isDisliked,
                                                    onTrackClick = onTrackClick,
                                                    onFavoriteToggle = onFavoriteToggle,
                                                    onAddToQueue = onAddToQueue,
                                                    onPlayNextTrack = onPlayNextTrack,
                                                    onAddToPlaylist = onAddToPlaylist,
                                                    onShareTrack = onShareTrack,
                                                    onDislikeTrack = onDislikeTrack,
                                                    sourceActions = sourceActions
                                                )
                                            }
                                        }
                                    }
                                }

                                SearchCategory.ARTISTS -> {
                                    if (ytArtistResults.isEmpty()) {
                                        SearchEmptyPlaceholder(
                                            title = stringResource(R.string.search_empty_artists_title),
                                            message = stringResource(R.string.search_empty_artists_message, query)
                                        )
                                    } else {
                                        LazyVerticalGrid(
                                            columns = GridCells.Fixed(3),
                                            modifier = Modifier.fillMaxSize(),
                                            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = LocalBottomChromeInset.current + 8.dp),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            verticalArrangement = Arrangement.spacedBy(14.dp)
                                        ) {
                                            item(span = { GridItemSpan(maxLineSpan) }) {
                                                SectionHeader(title = stringResource(R.string.search_category_artists))
                                            }
                                            items(ytArtistResults, key = { it.artistKey }) { artist ->
                                                YouTubeArtistCard(
                                                    artist = artist,
                                                    onClick = { onYouTubeArtistClick(artist) },
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                        }
                                    }
                                }

                                SearchCategory.ALBUMS -> {
                                    if (ytAlbumResults.isEmpty()) {
                                        SearchEmptyPlaceholder(
                                            title = stringResource(R.string.search_empty_albums_title),
                                            message = stringResource(R.string.search_empty_albums_message, query)
                                        )
                                    } else {
                                        LazyVerticalGrid(
                                            columns = GridCells.Fixed(2),
                                            modifier = Modifier.fillMaxSize(),
                                            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = LocalBottomChromeInset.current + 8.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(16.dp)
                                        ) {
                                            item(span = { GridItemSpan(maxLineSpan) }) {
                                                SectionHeader(title = stringResource(R.string.search_category_albums))
                                            }
                                            items(ytAlbumResults, key = { it.detailId ?: it.title }) { album ->
                                                YouTubeAlbumCard(
                                                    album = album,
                                                    onClick = { onYouTubeAlbumClick(album) },
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                        }
                                    }
                                }

                                SearchCategory.PLAYLISTS -> {
                                    if (ytPlaylistResults.isEmpty()) {
                                        SearchEmptyPlaceholder(
                                            title = stringResource(R.string.search_empty_playlists_title),
                                            message = stringResource(R.string.search_empty_playlists_message, query)
                                        )
                                    } else {
                                        LazyColumn(
                                            modifier = Modifier.fillMaxSize(),
                                            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = LocalBottomChromeInset.current + 8.dp),
                                            verticalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            item { SectionHeader(title = stringResource(R.string.search_category_playlists)) }
                                            items(ytPlaylistResults, key = { it.detailId ?: it.title }) { playlist ->
                                                YouTubePlaylistRow(
                                                    playlist = playlist,
                                                    onClick = { onYouTubePlaylistClick(playlist) }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackResultRow(
    track: Track,
    playbackState: PlaybackState,
    isFavorite: (String) -> Boolean,
    isDisliked: (String) -> Boolean,
    onTrackClick: (Track) -> Unit,
    onFavoriteToggle: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onPlayNextTrack: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onShareTrack: (Track) -> Unit,
    onDislikeTrack: (Track) -> Unit,
    sourceActions: TrackSourceActions? = null,
    modifier: Modifier = Modifier
) {
    val isCurrent = playbackState.currentTrack?.id == track.id
    TrackItem(
        track = track,
        isPlaying = isCurrent && playbackState.isPlaying,
        isCurrentTrack = isCurrent,
        isFavorite = isFavorite(track.id),
        isDisliked = isDisliked(track.id),
        onTrackClick = { onTrackClick(track) },
        onFavoriteClick = { onFavoriteToggle(track) },
        onAddToQueue = { onAddToQueue(track) },
        onPlayNextTrack = { onPlayNextTrack(track) },
        onAddToPlaylist = { onAddToPlaylist(track) },
        onShareClick = { onShareTrack(track) },
        onDislikeClick = { onDislikeTrack(track) },
        sourceActions = sourceActions,
        modifier = modifier
    )
}

/** Single shimmer region used while a search is in flight. */
@Composable
private fun SearchLoadingSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.gutter)
            .padding(bottom = LocalBottomChromeInset.current + 8.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Spacer(modifier = Modifier.height(Spacing.xs))
        repeat(6) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp)
                    .shimmer(RoundedCornerShape(Radius.Small))
            )
        }
    }
}

/** Empty / no-result state rendered as a single glass placeholder. */
@Composable
private fun SearchEmptyPlaceholder(
    title: String,
    message: String
) {
    val scheme = LocalBackgroundScheme.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.gutter)
            .padding(bottom = LocalBottomChromeInset.current + 8.dp),
        contentAlignment = Alignment.Center
    ) {
        GlassPlaceholder {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = scheme.onBackgroundTertiary,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(36.dp)
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = scheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onBackgroundSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** Initial prompt shown when the query is blank. */
@Composable
private fun EmptySearchPrompt(onSuggestionClick: (String) -> Unit) {
    val scheme = LocalBackgroundScheme.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.gutter)
            .padding(bottom = LocalBottomChromeInset.current + 8.dp),
        contentAlignment = Alignment.Center
    ) {
        GlassPlaceholder {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = scheme.accent,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(40.dp)
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = stringResource(R.string.search_prompt_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = scheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = stringResource(R.string.search_prompt_message),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onBackgroundSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(Spacing.lg))
            Text(
                text = stringResource(R.string.search_popular_title),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = scheme.onBackgroundSecondary,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                listOf(
                    stringResource(R.string.search_suggestion_the_weeknd),
                    stringResource(R.string.search_suggestion_daft_punk),
                    stringResource(R.string.search_suggestion_billie_eilish)
                ).forEach { suggestion ->
                    GlassChip(
                        label = suggestion,
                        selected = false,
                        onClick = { onSuggestionClick(suggestion) }
                    )
                }
            }
        }
    }
}

/** Small source badge shown on YouTube Music results. */
@Composable
private fun YouTubeBadge(modifier: Modifier = Modifier) {
    val scheme = LocalBackgroundScheme.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(scheme.accent.copy(alpha = 0.18f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = stringResource(R.string.search_youtube_badge),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            ),
            color = scheme.accent
        )
    }
}

@Composable
private fun SearchAllContent(
    tracks: List<Track>,
    ytArtists: List<YouTubeArtist>,
    ytAlbums: List<YouTubeAlbum>,
    ytPlaylists: List<YouTubePlaylist>,
    playbackState: PlaybackState,
    isFavorite: (String) -> Boolean,
    isDisliked: (String) -> Boolean,
    onTrackClick: (Track) -> Unit,
    onFavoriteToggle: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onPlayNextTrack: (Track) -> Unit,
    onYouTubeArtistClick: (YouTubeArtist) -> Unit,
    onYouTubeAlbumClick: (YouTubeAlbum) -> Unit,
    onYouTubePlaylistClick: (YouTubePlaylist) -> Unit,
    onShareTrack: (Track) -> Unit,
    onDislikeTrack: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit = {},
    sourceActions: TrackSourceActions? = null
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = LocalBottomChromeInset.current + 8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // 1. Top Result
        val topTrack = tracks.firstOrNull()
        if (topTrack != null) {
            item {
                Column {
                    SectionHeader(title = stringResource(R.string.search_top_result_header))
                    TopResultCard(
                        track = topTrack,
                        onPlayClick = { onTrackClick(topTrack) },
                        modifier = Modifier.padding(horizontal = Spacing.gutter)
                    )
                }
            }
        }

        // 2. Track Items (Songs)
        if (tracks.isNotEmpty()) {
            val songList = if (topTrack != null) tracks.drop(1).take(5) else tracks.take(5)
            if (songList.isNotEmpty()) {
                item {
                    SectionHeader(title = stringResource(R.string.search_category_tracks))
                }

                items(songList) { track ->
                    TrackResultRow(
                        track = track,
                        playbackState = playbackState,
                        isFavorite = isFavorite,
                        isDisliked = isDisliked,
                        onTrackClick = onTrackClick,
                        onFavoriteToggle = onFavoriteToggle,
                        onAddToQueue = onAddToQueue,
                        onPlayNextTrack = onPlayNextTrack,
                        onAddToPlaylist = onAddToPlaylist,
                        onShareTrack = onShareTrack,
                        onDislikeTrack = onDislikeTrack,
                        sourceActions = sourceActions,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }
        }

        // 3. Artists Row (YouTube Music)
        if (ytArtists.isNotEmpty()) {
            item {
                Column {
                    SectionHeader(title = stringResource(R.string.search_category_artists))

                    LazyRow(
                        contentPadding = PaddingValues(horizontal = Spacing.gutter),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.lg)
                    ) {
                        items(ytArtists, key = { it.artistKey }) { artist ->
                            YouTubeArtistCard(
                                artist = artist,
                                onClick = { onYouTubeArtistClick(artist) },
                                modifier = Modifier.width(104.dp)
                            )
                        }
                    }
                }
            }
        }

        // 4. Albums Row (YouTube Music)
        if (ytAlbums.isNotEmpty()) {
            item {
                Column {
                    SectionHeader(title = stringResource(R.string.search_category_albums))

                    LazyRow(
                        contentPadding = PaddingValues(horizontal = Spacing.gutter),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                    ) {
                        items(ytAlbums, key = { it.detailId ?: it.title }) { album ->
                            YouTubeAlbumCard(
                                album = album,
                                onClick = { onYouTubeAlbumClick(album) },
                                modifier = Modifier.width(148.dp)
                            )
                        }
                    }
                }
            }
        }

        // 5. Playlists Row (YouTube Music)
        if (ytPlaylists.isNotEmpty()) {
            item {
                Column {
                    SectionHeader(title = stringResource(R.string.search_category_playlists))

                    LazyRow(
                        contentPadding = PaddingValues(horizontal = Spacing.gutter),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                    ) {
                        items(ytPlaylists, key = { it.detailId ?: it.title }) { playlist ->
                            YouTubePlaylistCard(
                                playlist = playlist,
                                onClick = { onYouTubePlaylistClick(playlist) },
                                modifier = Modifier.width(148.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun YouTubeArtistCard(
    artist: YouTubeArtist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier
            .pressScale()
            .clip(RoundedCornerShape(Radius.Small))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(Radius.Small)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(SonoraSurfaceHighlight),
                contentAlignment = Alignment.Center
            ) {
                if (!artist.thumbnailUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = artist.thumbnailUrl,
                        contentDescription = artist.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = SonoraTextSecondary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(Spacing.sm))
            YouTubeBadge()
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = artist.name,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = SonoraTextPrimary
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            if (!artist.subtitle.isNullOrBlank()) {
                Text(
                    text = artist.subtitle,
                    style = MaterialTheme.typography.labelSmall.copy(color = SonoraTextTertiary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun YouTubeAlbumCard(
    album: YouTubeAlbum,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier
            .pressScale()
            .clip(RoundedCornerShape(Radius.Small))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(Radius.Small)
    ) {
        Column(modifier = Modifier.padding(Spacing.sm)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(Radius.Small))
                    .background(SonoraSurfaceHighlight),
                contentAlignment = Alignment.Center
            ) {
                if (!album.thumbnailUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = album.thumbnailUrl,
                        contentDescription = album.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Album,
                        contentDescription = null,
                        tint = SonoraTextSecondary,
                        modifier = Modifier.size(36.dp)
                    )
                }
                YouTubeBadge(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                )
            }
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = album.title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = SonoraTextPrimary
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val meta = listOfNotNull(album.artist?.takeIf { it.isNotBlank() }, album.year?.takeIf { it.isNotBlank() })
                .joinToString(" • ")
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall.copy(color = SonoraTextTertiary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun YouTubePlaylistCard(
    playlist: YouTubePlaylist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier
            .pressScale()
            .clip(RoundedCornerShape(Radius.Small))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(Radius.Small)
    ) {
        Column(modifier = Modifier.padding(Spacing.sm)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(Radius.Small))
                    .background(SonoraSurfaceHighlight),
                contentAlignment = Alignment.Center
            ) {
                if (!playlist.thumbnailUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = playlist.thumbnailUrl,
                        contentDescription = playlist.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Album,
                        contentDescription = null,
                        tint = SonoraTextSecondary,
                        modifier = Modifier.size(36.dp)
                    )
                }
                YouTubeBadge(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                )
            }
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = playlist.title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = SonoraTextPrimary
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (!playlist.author.isNullOrBlank()) {
                Text(
                    text = playlist.author,
                    style = MaterialTheme.typography.labelSmall.copy(color = SonoraTextTertiary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun YouTubePlaylistRow(
    playlist: YouTubePlaylist,
    onClick: () -> Unit
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale()
            .clip(RoundedCornerShape(Radius.Small))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(Radius.Small)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SonoraSurfaceHighlight),
                contentAlignment = Alignment.Center
            ) {
                if (!playlist.thumbnailUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = playlist.thumbnailUrl,
                        contentDescription = playlist.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Album,
                        contentDescription = null,
                        tint = SonoraTextSecondary
                    )
                }
            }
            Spacer(modifier = Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = SonoraTextPrimary
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val meta = listOfNotNull(
                    playlist.author?.takeIf { it.isNotBlank() },
                    playlist.trackCount?.let { stringResource(R.string.search_playlist_track_count, it) }
                ).joinToString(" • ")
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall.copy(color = SonoraTextTertiary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            YouTubeBadge()
            Spacer(modifier = Modifier.width(Spacing.sm))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = SonoraTextTertiary
            )
        }
    }
}

@Composable
private fun TopResultCard(
    track: Track,
    onPlayClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .pressScale()
            .clip(RoundedCornerShape(Radius.Medium))
            .clickable(onClick = onPlayClick),
        shape = RoundedCornerShape(Radius.Medium)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(RoundedCornerShape(Radius.Small))
                    .background(SonoraSurfaceHighlight),
                contentAlignment = Alignment.Center
            ) {
                if (!track.artworkUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = track.artworkUrl,
                        contentDescription = track.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Album,
                        contentDescription = null,
                        tint = SonoraTextTertiary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(Spacing.md))

            Column(modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(scheme.accent.copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.search_top_result_badge),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        ),
                        color = scheme.accent
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.xs))

                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = SonoraTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = stringResource(R.string.search_top_result_subtitle, track.artist),
                    style = MaterialTheme.typography.bodySmall,
                    color = SonoraTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(Spacing.sm))

            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(scheme.accent)
                    .clickable(onClick = onPlayClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.search_play_desc),
                    tint = if (scheme.isLight) Color.White else Color.Black,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

@Composable
private fun ArtistAvatarCard(
    artist: Artist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(SonoraSurfaceHighlight),
            contentAlignment = Alignment.Center
        ) {
            if (!artist.artworkUrl.isNullOrBlank()) {
                AsyncImage(
                    model = artist.artworkUrl,
                    contentDescription = artist.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = SonoraTextTertiary,
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = artist.name,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp
            ),
            color = SonoraTextPrimary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(SonoraSurfaceHighlight)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = stringResource(R.string.search_artist_badge),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = SonoraTextSecondary
            )
        }
    }
}


@Composable
private fun AlbumArtworkCard(
    album: Album,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(SonoraSurfaceHighlight),
            contentAlignment = Alignment.Center
        ) {
            if (!album.artworkUrl.isNullOrBlank()) {
                AsyncImage(
                    model = album.artworkUrl,
                    contentDescription = album.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Album,
                    contentDescription = null,
                    tint = SonoraTextTertiary,
                    modifier = Modifier.size(40.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

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

        val year = album.releaseDate?.take(4) ?: ""
        val subtitle = if (year.isNotBlank()) "${album.artist} • $year" else album.artist

        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 11.sp
            ),
            color = SonoraTextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

