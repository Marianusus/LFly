package org.sonora.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NorthWest
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
import org.sonora.android.data.SearchHistoryEntry
import org.sonora.android.data.SearchHistoryType
import org.sonora.android.playback.PlaybackState
import org.sonora.android.ui.components.LocalBottomChromeInset
import org.sonora.android.ui.components.SectionHeader
import org.sonora.android.ui.components.SegmentedPills
import org.sonora.android.ui.components.TrackItem
import org.sonora.android.ui.components.TrackOptionsMenu
import org.sonora.android.ui.components.LocalTrackContextMenu
import org.sonora.android.ui.components.TrackAnchor
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
    isTyping: Boolean = false,
    suggestions: List<String> = emptyList(),
    searchHistory: List<SearchHistoryEntry> = emptyList(),
    playbackState: PlaybackState,
    isFavorite: (String) -> Boolean,
    isDisliked: (String) -> Boolean = { false },
    onQueryChange: (String) -> Unit,
    onSubmit: (String) -> Unit = {},
    onCancelTyping: () -> Unit = {},
    onSuggestionFill: (String) -> Unit = {},
    onHistoryClick: (SearchHistoryEntry) -> Unit = {},
    onHistoryRemove: (SearchHistoryType, String) -> Unit = { _, _ -> },
    onHistoryClear: () -> Unit = {},
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
    /**
     * Incremented by the host when the Search tab is tapped again: focuses the field
     * and opens the keyboard without the user having to aim at the field itself.
     */
    focusRequest: Int = 0,
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current
    val categoryLabels = SearchCategory.values().map { it to stringResource(it.labelRes) }.toMap()
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val submitAndDismiss: (String) -> Unit = { value ->
        if (value.isNotBlank()) {
            onSubmit(value)
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }

    BackHandler(enabled = isTyping) {
        focusManager.clearFocus()
        keyboardController?.hide()
        onCancelTyping()
    }

    LaunchedEffect(focusRequest) {
        if (focusRequest > 0) {
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

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
                        .focusRequester(searchFocusRequester)
                        .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onBackground),
                    cursorBrush = SolidColor(scheme.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submitAndDismiss(query) }),
                    decorationBox = { innerTextField ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = stringResource(R.string.search_title),
                                tint = if (query.isBlank()) scheme.onBackgroundSecondary else scheme.accent,
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .clickable(enabled = query.isNotBlank()) { submitAndDismiss(query) }
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
                                        .clickable {
                                            onQueryChange("")
                                            searchFocusRequester.requestFocus()
                                            keyboardController?.show()
                                        }
                                )
                            }
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(Spacing.md))

            if (isTyping || query.isBlank()) {
                if (query.isBlank()) {
                    SearchIdleContent(
                        history = searchHistory,
                        onRecentClick = { entry ->
                            focusManager.clearFocus()
                            keyboardController?.hide()
                            onHistoryClick(entry)
                        },
                        onRecentRemove = onHistoryRemove,
                        onHistoryClear = onHistoryClear,
                        onPopularClick = { submitAndDismiss(it) }
                    )
                } else {
                    SearchSuggestionsContent(
                        query = query,
                        suggestions = suggestions,
                        history = searchHistory,
                        onExactClick = { submitAndDismiss(query) },
                        onSuggestionClick = { submitAndDismiss(it) },
                        onSuggestionFill = onSuggestionFill,
                        onHistoryClick = { entry ->
                            focusManager.clearFocus()
                            keyboardController?.hide()
                            onHistoryClick(entry)
                        }
                    )
                }
            } else {
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
}

/** Suggestion panel shown while the search field is being edited. */
@Composable
private fun SearchSuggestionsContent(
    query: String,
    suggestions: List<String>,
    history: List<SearchHistoryEntry>,
    onExactClick: () -> Unit,
    onSuggestionClick: (String) -> Unit,
    onSuggestionFill: (String) -> Unit,
    onHistoryClick: (SearchHistoryEntry) -> Unit
) {
    val scheme = LocalBackgroundScheme.current
    val needle = query.trim().lowercase()
    val matchingHistory = remember(history, needle) {
        if (needle.isBlank()) emptyList()
        else history.filter {
            it.title.lowercase().contains(needle) || it.subtitle.lowercase().contains(needle)
        }.take(2)
    }
    val visibleSuggestions = remember(suggestions, query) {
        suggestions.filterNot { it.equals(query.trim(), ignoreCase = true) }.take(4)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 8.dp,
            end = 8.dp,
            top = 4.dp,
            bottom = LocalBottomChromeInset.current + 8.dp
        ),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        item(key = "suggest:exact") {
            ExactQueryRow(query = query, onClick = onExactClick)
        }
        items(visibleSuggestions, key = { "suggest:$it" }) { term ->
            SuggestionRow(
                term = term,
                onFill = { onSuggestionFill(term) },
                onClick = { onSuggestionClick(term) }
            )
        }
        if (matchingHistory.isNotEmpty()) {
            item(key = "suggest:recent-header") {
                Text(
                    text = stringResource(R.string.search_suggestions_recent),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = scheme.onBackgroundSecondary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp)
                )
            }
            items(matchingHistory, key = { "suggest:history:${it.type}:${it.id}" }) { entry ->
                RecentSearchRow(entry = entry, onClick = { onHistoryClick(entry) })
            }
        }
        if (visibleSuggestions.isEmpty() && matchingHistory.isEmpty()) {
            item(key = "suggest:empty") {
                Text(
                    text = stringResource(R.string.search_suggestions_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onBackgroundTertiary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                )
            }
        }
    }
}

@Composable
private fun ExactQueryRow(query: String, onClick: () -> Unit) {
    val scheme = LocalBackgroundScheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = scheme.accent,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = stringResource(R.string.search_suggestions_exact, query),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = scheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = scheme.onBackgroundTertiary,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun SuggestionRow(
    term: String,
    onFill: () -> Unit,
    onClick: () -> Unit
) {
    val scheme = LocalBackgroundScheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = scheme.onBackgroundSecondary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = term,
            style = MaterialTheme.typography.titleMedium,
            color = scheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = onFill),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.NorthWest,
                contentDescription = stringResource(R.string.search_suggestion_fill, term),
                tint = scheme.onBackgroundSecondary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** Idle search page: cover-backed recents when there are any, otherwise the prompt. */
@Composable
private fun SearchIdleContent(
    history: List<SearchHistoryEntry>,
    onRecentClick: (SearchHistoryEntry) -> Unit,
    onRecentRemove: (SearchHistoryType, String) -> Unit,
    onHistoryClear: () -> Unit,
    onPopularClick: (String) -> Unit
) {
    val scheme = LocalBackgroundScheme.current
    if (history.isEmpty()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = LocalBottomChromeInset.current + 8.dp)
        ) {
            item(key = "idle:recent-title") {
                Text(
                    text = stringResource(R.string.search_recent_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = scheme.onBackground,
                    modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.sm)
                )
            }
            item(key = "idle:recent-empty") {
                Text(
                    text = stringResource(R.string.search_recent_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onBackgroundTertiary,
                    modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.xs)
                )
            }
            item(key = "idle:popular") {
                PopularSearchChips(
                    onSuggestionClick = onPopularClick,
                    modifier = Modifier.padding(top = Spacing.md)
                )
            }
            item(key = "idle:hint") {
                Text(
                    text = stringResource(R.string.search_prompt_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onBackgroundTertiary,
                    modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.lg)
                )
            }
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = LocalBottomChromeInset.current + 8.dp)
    ) {
        item(key = "recent:header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.gutter, end = Spacing.sm, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.search_recent_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = scheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(R.string.search_recent_clear),
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .clickable(onClick = onHistoryClear)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
        items(history, key = { "recent:${it.type}:${it.id}" }) { entry ->
            RecentSearchRow(
                entry = entry,
                onClick = { onRecentClick(entry) },
                onRemove = { onRecentRemove(entry.type, entry.id) }
            )
        }
        item(key = "recent:popular") {
            PopularSearchChips(onSuggestionClick = onPopularClick)
        }
    }
}

@Composable
private fun RecentSearchRow(
    entry: SearchHistoryEntry,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null
) {
    val scheme = LocalBackgroundScheme.current
    val shape = if (entry.type == SearchHistoryType.ARTIST) CircleShape else RoundedCornerShape(8.dp)
    val typeLabel = searchHistoryTypeLabel(entry.type)
    val subtitle = when {
        entry.type == SearchHistoryType.QUERY -> ""
        entry.subtitle.isBlank() -> typeLabel
        else -> "$typeLabel · ${entry.subtitle}"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = Spacing.gutter, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(shape)
                .background(SonoraSurfaceHighlight),
            contentAlignment = Alignment.Center
        ) {
            if (!entry.artworkUrl.isNullOrBlank()) {
                AsyncImage(
                    model = entry.artworkUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = when (entry.type) {
                        SearchHistoryType.QUERY -> Icons.Default.Search
                        SearchHistoryType.TRACK -> Icons.Default.PlayArrow
                        SearchHistoryType.ARTIST -> Icons.Default.Person
                        else -> Icons.Default.Album
                    },
                    contentDescription = null,
                    tint = scheme.onBackgroundSecondary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = scheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onBackgroundSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (onRemove != null) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Clear,
                    contentDescription = stringResource(R.string.search_recent_remove, entry.title),
                    tint = scheme.onBackgroundSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun searchHistoryTypeLabel(type: SearchHistoryType): String = stringResource(
    when (type) {
        SearchHistoryType.QUERY -> R.string.search_history_type_query
        SearchHistoryType.TRACK -> R.string.search_history_type_track
        SearchHistoryType.ARTIST -> R.string.search_history_type_artist
        SearchHistoryType.ALBUM -> R.string.search_history_type_album
        SearchHistoryType.PLAYLIST -> R.string.search_history_type_playlist
    }
)

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

@Composable
private fun PopularSearchChips(
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.search_popular_title),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = scheme.onBackgroundSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.gutter)
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.gutter)
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
                        isDisliked = isDisliked(topTrack.id),
                        onPlayClick = { onTrackClick(topTrack) },
                        onAddToQueue = { onAddToQueue(topTrack) },
                        onPlayNextTrack = { onPlayNextTrack(topTrack) },
                        onAddToPlaylist = { onAddToPlaylist(topTrack) },
                        onShareTrack = { onShareTrack(topTrack) },
                        onDislikeTrack = { onDislikeTrack(topTrack) },
                        sourceActions = sourceActions,
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopResultCard(
    track: Track,
    isDisliked: Boolean,
    onPlayClick: () -> Unit,
    onAddToQueue: (() -> Unit)? = null,
    onPlayNextTrack: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onShareTrack: (() -> Unit)? = null,
    onDislikeTrack: (() -> Unit)? = null,
    sourceActions: TrackSourceActions? = null,
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current
    var menuExpanded by remember { mutableStateOf(false) }
    val anchor = remember { TrackAnchor() }
    val contextMenu = LocalTrackContextMenu.current
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .pressScale()
            .clip(RoundedCornerShape(Radius.Medium))
            .onGloballyPositioned { coordinates ->
                if (coordinates.isAttached) anchor.rect = coordinates.boundsInWindow()
            }
            .combinedClickable(
                onClick = onPlayClick,
                onLongClick = {
                    if (contextMenu != null && anchor.rect != Rect.Zero) contextMenu.open(track, anchor.rect)
                    else menuExpanded = true
                }
            ),
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

            Spacer(modifier = Modifier.width(Spacing.md))

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

            // Menu anchor: opened by long-pressing the card (no visible ⋯ button).
            TrackOptionsMenu(
                track = track,
                expanded = menuExpanded,
                isDisliked = isDisliked,
                onDismiss = { menuExpanded = false },
                onPlayNextTrack = onPlayNextTrack,
                onAddToQueue = onAddToQueue,
                onAddToPlaylist = onAddToPlaylist,
                onShareClick = onShareTrack,
                onDislikeClick = onDislikeTrack,
                sourceActions = sourceActions
            )
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

