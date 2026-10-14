package org.sonora.android.ui

import androidx.activity.compose.BackHandler
import android.app.Activity
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.sonora.android.R
import org.sonora.android.api.Track
import org.sonora.android.ui.components.AddToPlaylistDialog
import org.sonora.android.ui.components.BottomChrome
import org.sonora.android.ui.components.TrackSourceActions
import org.sonora.android.ui.components.chromeSurface
import org.sonora.android.ui.components.EqualizerDialog
import org.sonora.android.ui.components.LocalBottomChromeInset
import org.sonora.android.ui.components.MiniPlayer
import org.sonora.android.ui.components.QueueBottomSheet
import org.sonora.android.ui.components.ShareTrackDialog
import org.sonora.android.ui.theme.GlassBar
import org.sonora.android.ui.theme.LocalBackgroundScheme
import org.sonora.android.ui.theme.LocalMotionPreferences
import org.sonora.android.ui.theme.MotionPreferences
import org.sonora.android.ui.theme.SonoraBackground
import org.sonora.android.ui.theme.SonoraTextPrimary
import org.sonora.android.ui.theme.SonoraTextSecondary
import org.sonora.android.ui.theme.SonoraTheme
import org.sonora.android.ui.theme.Spacing

private fun tabMorphTransition(
    target: NavigationTab,
    initial: NavigationTab,
    motion: MotionPreferences
): ContentTransform {
    val forward = target.ordinal >= initial.ordinal
    val dir = if (forward) 1 else -1
    val nav = motion.navigationMs
    val enter = slideInHorizontally(animationSpec = tween(nav)) { w -> dir * (w / 12) } +
        fadeIn(animationSpec = tween(nav)) +
        scaleIn(animationSpec = tween(nav), initialScale = 0.96f)
    val exit = slideOutHorizontally(animationSpec = tween(nav)) { w -> -dir * (w / 16) } +
        fadeOut(animationSpec = tween(nav)) +
        scaleOut(animationSpec = tween(nav), targetScale = 1.03f)
    return ContentTransform(enter, exit, sizeTransform = SizeTransform(clip = false))
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun MainScreen(
    viewModel: SonoraViewModel,
    modifier: Modifier = Modifier
) {
    val currentTab by viewModel.currentTab.collectAsState()
    val activityContext = LocalContext.current
    val motion = LocalMotionPreferences.current
    val playbackState by viewModel.playbackState.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val history by viewModel.history.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val localTracks by viewModel.localTracks.collectAsState()
    val healthList by viewModel.healthState.collectAsState()
    val appLanguage by viewModel.appLanguage.collectAsState()
    val accentColor by viewModel.accentColor.collectAsState()
    val sourcePreference by viewModel.sourcePreference.collectAsState()
    val isRefreshingHealth by viewModel.isRefreshingHealth.collectAsState()
    val charts by viewModel.charts.collectAsState()
    val isLoadingCharts by viewModel.isLoadingCharts.collectAsState()
    val recommendedTracks by viewModel.recommendedTracks.collectAsState()
    val isLoadingRecommendations by viewModel.isLoadingRecommendations.collectAsState()
    val countryTopTracks by viewModel.countryTopTracks.collectAsState()
    val isLoadingCountryTopTracks by viewModel.isLoadingCountryTopTracks.collectAsState()
    val dislikedTrackIds by viewModel.dislikedTrackIds.collectAsState()

    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchCategory by viewModel.searchCategory.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val searchYtArtists by viewModel.searchYtArtists.collectAsState()
    val searchYtAlbums by viewModel.searchYtAlbums.collectAsState()
    val searchYtPlaylists by viewModel.searchYtPlaylists.collectAsState()
    val followedArtists by viewModel.followedArtists.collectAsState()
    val selectedYtArtist by viewModel.selectedYtArtist.collectAsState()
    val selectedYtAlbum by viewModel.selectedYtAlbum.collectAsState()
    val selectedYtPlaylist by viewModel.selectedYtPlaylist.collectAsState()
    val selectedYtSongs by viewModel.selectedYtSongs.collectAsState()
    val isLoadingYtSongs by viewModel.isLoadingYtSongs.collectAsState()
    val ytSongsError by viewModel.ytSongsError.collectAsState()
    val isLoadingYtDetail by viewModel.isLoadingYtDetail.collectAsState()
    val ytDetailError by viewModel.ytDetailError.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val isPlayerExpanded by viewModel.isPlayerExpanded.collectAsState()
    val isLyricsOpen by viewModel.isLyricsOpen.collectAsState()
    val lyrics by viewModel.lyrics.collectAsState()
    val isLoadingLyrics by viewModel.isLoadingLyrics.collectAsState()
    val isQueueSheetOpen by viewModel.isQueueSheetOpen.collectAsState()
    val isEqualizerDialogOpen by viewModel.isEqualizerDialogOpen.collectAsState()
    val equalizerState by viewModel.equalizerState.collectAsState()
    val trackToAddToPlaylist by viewModel.trackToAddToPlaylist.collectAsState()

    var trackToShare by remember { mutableStateOf<Track?>(null) }
    var savingYtPlaylistId by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val uiScope = rememberCoroutineScope()

    val currentDestination by viewModel.currentDestination.collectAsState()
    val selectedArtist by viewModel.selectedArtist.collectAsState()
    val artistTopTracks by viewModel.artistTopTracks.collectAsState()
    val artistAlbums by viewModel.artistAlbums.collectAsState()
    val isLoadingArtistDetail by viewModel.isLoadingArtistDetail.collectAsState()
    val artistDetailError by viewModel.artistDetailError.collectAsState()

    val selectedAlbum by viewModel.selectedAlbum.collectAsState()
    val albumTracks by viewModel.albumTracks.collectAsState()
    val isLoadingAlbumDetail by viewModel.isLoadingAlbumDetail.collectAsState()
    val albumDetailError by viewModel.albumDetailError.collectAsState()

    val audioQuality by viewModel.audioQuality.collectAsState()
    val isAutoplayEnabled by viewModel.isAutoplayEnabled.collectAsState()
    val cacheSizeBytes by viewModel.cacheSizeBytes.collectAsState()
    val currentThemeStyle by viewModel.themeStyle.collectAsState()
    val appearanceMode by viewModel.appearanceMode.collectAsState()
    val reduceMotion by viewModel.reduceMotion.collectAsState()
    val translateLyrics by viewModel.translateLyrics.collectAsState()
    val translationTargetLang by viewModel.translationTargetLang.collectAsState()
    val lyricsHighlightMode by viewModel.lyricsHighlightMode.collectAsState()
    val lyricsProviderMusixmatch by viewModel.lyricsProviderMusixmatch.collectAsState()
    val lyricsProviderLrclib by viewModel.lyricsProviderLrclib.collectAsState()

    val navItems = listOf(
        NavItem.Home,
        NavItem.Search,
        NavItem.Library,
        NavItem.Settings
    )

    val favoriteIds = remember(favorites) { favorites.map { it.id }.toSet() }

    val trackSourceActions = remember(viewModel) {
        TrackSourceActions(
            onSelect = { track, source -> viewModel.setTrackSource(track, source) },
            onClear = { track -> viewModel.clearTrackSource(track) },
            overrideFor = { id -> viewModel.trackSourceOverride(id) }
        )
    }

    val mainDetailNotAvailable = stringResource(R.string.main_detail_not_available)
    val mainImportFailed = stringResource(R.string.main_import_failed)
    val mainContextTopEu = stringResource(R.string.main_context_top_eu)
    val mainContextTop50 = stringResource(R.string.main_context_top_50)
    val mainContextYoutubeMusic = stringResource(R.string.main_context_youtube_music)
    val mainContextSearch = stringResource(R.string.main_context_search)
    val mainContextDownloaded = stringResource(R.string.main_context_downloaded)
    val mainContextFavorites = stringResource(R.string.main_context_favorites)
    val mainContextHistory = stringResource(R.string.main_context_history)
    val mainContextPlaylist = stringResource(R.string.main_context_playlist)

    // Back handlers for equalizer dialog, queue sheet, full player & lyrics modal, and destination stack
    if (isEqualizerDialogOpen) {
        BackHandler {
            viewModel.closeEqualizerDialog()
        }
    } else if (isQueueSheetOpen) {
        BackHandler {
            viewModel.closeQueueSheet()
        }
    } else if (isLyricsOpen) {
        BackHandler {
            viewModel.toggleLyricsOpen()
        }
    } else if (isPlayerExpanded) {
        BackHandler {
            viewModel.setPlayerExpanded(false)
        }
    } else if (currentDestination !is ScreenDestination.TabContent) {
        BackHandler {
            viewModel.navigateBack()
        }
    } else if (currentTab != NavigationTab.HOME) {
        // On any non-Home tab, back returns to Home first (a further back exits the app).
        BackHandler {
            viewModel.selectTab(NavigationTab.HOME)
        }
    }

    SonoraBackground(
        modifier = modifier,
        coverUrl = playbackState.currentTrack?.coverUrl
    ) {
        val hasMiniPlayer = playbackState.currentTrack != null && !isPlayerExpanded
        val navigationBarsInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val bottomChromeInset = BottomChrome.inset(hasMiniPlayer, navigationBarsInset)

        // Provide the floating-chrome inset to all scrollable screens below.
        CompositionLocalProvider(LocalBottomChromeInset provides bottomChromeInset) {
            Scaffold(
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                snackbarHost = { SnackbarHost(snackbarHostState) }
            ) { paddingValues ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                when (val dest = currentDestination) {
                    is ScreenDestination.TabContent -> {
                        val tabStateHolder = rememberSaveableStateHolder()
                        AnimatedContent(
                            targetState = currentTab,
                            transitionSpec = { tabMorphTransition(targetState, initialState, motion) },
                            label = "tabMorph",
                            modifier = Modifier.fillMaxSize()
                        ) { tab ->
                            tabStateHolder.SaveableStateProvider(tab.name) {
                                when (tab) {
                            NavigationTab.HOME -> {
                                HomeScreen(
                                    charts = charts,
                                    recentlyPlayed = history,
                                    isLoadingCharts = isLoadingCharts,
                                    playbackState = playbackState,
                                    isFavorite = { id -> id in favoriteIds },
                                    isDisliked = { id -> viewModel.isDisliked(id) },
                                    recommendedTracks = recommendedTracks,
                                    isLoadingRecommendations = isLoadingRecommendations,
                                    countryTopTracks = countryTopTracks,
                                    isLoadingCountryTopTracks = isLoadingCountryTopTracks,
                                    onTrackClick = { track ->
                                        val context = if (track.provider == org.sonora.android.api.EuropeChartsClient.PROVIDER) {
                                            mainContextTopEu
                                        } else {
                                            mainContextTop50
                                        }
                                        viewModel.playTrack(track, context)
                                    },
                                    onFavoriteToggle = { track -> viewModel.toggleFavorite(track) },
                                    onPlayAllCharts = { viewModel.playQueue(charts.take(5), 0, mainContextTop50) },
                                    onAddToQueue = { track -> viewModel.addToQueue(track) },
                                    onPlayNextTrack = { track -> viewModel.addToQueueNext(track) },
                                    onShareTrack = { track -> trackToShare = track },
                                    onDislikeTrack = { track -> viewModel.toggleDislike(track) },
                                    onAddToPlaylist = { track -> viewModel.showAddToPlaylistDialog(track) },
                                    sourceActions = trackSourceActions
                                )
                            }

                            NavigationTab.SEARCH -> {
                                SearchScreen(
                                    query = searchQuery,
                                    selectedCategory = searchCategory,
                                    searchResults = searchResults,
                                    ytArtistResults = searchYtArtists,
                                    ytAlbumResults = searchYtAlbums,
                                    ytPlaylistResults = searchYtPlaylists,
                                    isSearching = isSearching,
                                    playbackState = playbackState,
                                    isFavorite = { id -> id in favoriteIds },
                                    isDisliked = { id -> viewModel.isDisliked(id) },
                                    onQueryChange = { q -> viewModel.onSearchQueryChange(q) },
                                    onCategoryChange = { c -> viewModel.onSearchCategoryChange(c) },
                                    onTrackClick = { track ->
                                        val ctx = if (track.provider.equals("YouTube", ignoreCase = true)) mainContextYoutubeMusic else mainContextSearch
                                        viewModel.playTrack(track, ctx)
                                    },
                                    onFavoriteToggle = { track -> viewModel.toggleFavorite(track) },
                                    onAddToQueue = { track -> viewModel.addToQueue(track) },
                                    onPlayNextTrack = { track -> viewModel.addToQueueNext(track) },
                                    onYouTubeArtistClick = { artist ->
                                        val id = artist.discoveryDetailId()
                                        if (id != null) {
                                            viewModel.navigateToYouTubeArtist(id, artist.name)
                                        } else {
                                            uiScope.launch { snackbarHostState.showSnackbar(mainDetailNotAvailable) }
                                        }
                                    },
                                    onYouTubeAlbumClick = { album ->
                                        val id = album.discoveryDetailId()
                                        if (id != null) {
                                            viewModel.navigateToYouTubeAlbum(id, album.title)
                                        } else {
                                            uiScope.launch { snackbarHostState.showSnackbar(mainDetailNotAvailable) }
                                        }
                                    },
                                    onYouTubePlaylistClick = { playlist ->
                                        val id = playlist.discoveryDetailId()
                                        if (id != null) {
                                            viewModel.navigateToYouTubePlaylist(id, playlist.title)
                                        } else {
                                            uiScope.launch { snackbarHostState.showSnackbar(mainDetailNotAvailable) }
                                        }
                                    },
                                    onShareTrack = { track -> trackToShare = track },
                                    onDislikeTrack = { track -> viewModel.toggleDislike(track) },
                                    onAddToPlaylist = { track -> viewModel.showAddToPlaylistDialog(track) },
                                    sourceActions = trackSourceActions
                                )
                            }

                            NavigationTab.LIBRARY -> {
                                LibraryScreen(
                                    favorites = favorites,
                                    playlists = playlists,
                                    history = history,
                                    playbackState = playbackState,
                                    isFavorite = { id -> id in favoriteIds },
                                    onTrackClick = { track ->
                                        val ctx = if (track.provider.equals("Local", ignoreCase = true)) mainContextDownloaded else mainContextFavorites
                                        viewModel.playTrack(track, ctx)
                                    },
                                    onFavoriteToggle = { track -> viewModel.toggleFavorite(track) },
                                    onPlayAllFavorites = { viewModel.playQueue(favorites, 0, mainContextFavorites) },
                                    onShuffleFavorites = {
                                        viewModel.playQueue(favorites.shuffled(), 0, mainContextFavorites)
                                    },
                                    onCreatePlaylist = { name ->
                                        val pl = viewModel.createPlaylist(name)
                                        viewModel.navigateToPlaylist(pl.id)
                                    },
                                    onAddToQueue = { track -> viewModel.addToQueue(track) },
                                    onPlayNextTrack = { track -> viewModel.addToQueueNext(track) },
                                    onPlaylistClick = { playlist -> viewModel.navigateToPlaylist(playlist.id) },
                                    onAddToPlaylist = { track -> viewModel.showAddToPlaylistDialog(track) },
                                    onPlayPlaylist = { playlist ->
                                        if (playlist.tracks.isNotEmpty()) {
                                            viewModel.playQueue(playlist.tracks, 0, activityContext.getString(R.string.main_context_playlist_fmt, playlist.name))
                                        }
                                        viewModel.navigateToPlaylist(playlist.id)
                                    },
                                    spotifyPlaylistImporter = viewModel.spotifyPlaylistImporter,
                                    followedArtists = followedArtists,
                                    onFollowedArtistClick = { artist ->
                                        val id = artist.browseId ?: artist.channelId
                                        if (id != null) viewModel.navigateToYouTubeArtist(id, artist.name)
                                    },
                                    onPlayAllHistory = {
                                        if (history.isNotEmpty())
                                            viewModel.playQueue(history, 0, mainContextHistory)
                                    },
                                    onShuffleHistory = {
                                        if (history.isNotEmpty())
                                            viewModel.playQueue(history.shuffled(), 0, mainContextHistory)
                                    },
                                    onReorderFavorites = { from, to ->
                                        viewModel.reorderFavorites(from, to)
                                    },
                                    sourceActions = trackSourceActions
                                )
                            }

                            NavigationTab.SETTINGS, NavigationTab.DIAGNOSTICS -> {
                                SettingsScreen(
                                    currentThemeStyle = currentThemeStyle,
                                    onThemeStyleChange = { style -> viewModel.setThemeStyle(style) },
                                    appearanceMode = appearanceMode,
                                    onAppearanceModeChange = { mode -> viewModel.setAppearanceMode(mode) },
                                    reduceMotion = reduceMotion,
                                    onReduceMotionChange = { enabled -> viewModel.setReduceMotion(enabled) },
                                    currentAudioQuality = audioQuality,
                                    onAudioQualityChange = { q -> viewModel.setAudioQuality(q) },
                                    isAutoplayEnabled = isAutoplayEnabled,
                                    onAutoplayChange = { a -> viewModel.setAutoplayEnabled(a) },
                                    cacheSizeBytes = cacheSizeBytes,
                                    onClearCacheClick = { viewModel.clearAudioCache() },
                                    healthList = healthList,
                                    isRefreshingApis = isRefreshingHealth,
                                    onRefreshApisClick = { viewModel.refreshHealth() },
                                    onViewDiagnosticsClick = { viewModel.navigateToDiagnostics() },
                                    onBackClick = { viewModel.navigateBack() },
                                    showBack = false,
                                    onOpenEqualizerClick = { viewModel.openEqualizerDialog() },
                                    equalizerState = equalizerState,
                                    translateLyrics = translateLyrics,
                                    onTranslateLyricsChange = { e -> viewModel.setTranslateLyrics(e) },
                                    translationTargetLang = translationTargetLang,
                                    onTranslationTargetLangChange = { l -> viewModel.setTranslationTargetLang(l) },
                                    lyricsHighlightMode = lyricsHighlightMode,
                                    onLyricsHighlightModeChange = { mode -> viewModel.setLyricsHighlightMode(mode) },
                                    lyricsProviderMusixmatch = lyricsProviderMusixmatch,
                                    onLyricsProviderMusixmatchChange = { e -> viewModel.setLyricsProviderMusixmatch(e) },
                                    lyricsProviderLrclib = lyricsProviderLrclib,
                                    onLyricsProviderLrclibChange = { e -> viewModel.setLyricsProviderLrclib(e) },
                                    appLanguage = appLanguage,
                                    onAppLanguageChange = { tag ->
                                        viewModel.setAppLanguage(tag)
                                        (activityContext as? Activity)?.recreate()
                                    },
                                    accentColor = accentColor,
                                    onAccentColorChange = { argb -> viewModel.setAccentColor(argb) },
                                    sourcePreference = sourcePreference,
                                    onSourcePreferenceChange = { src -> viewModel.setSourcePreference(src) }
                                )
                            }
                                }
                            }
                        }
                    }

                    is ScreenDestination.ArtistDetail -> {
                        ArtistDetailScreen(
                            artist = selectedArtist,
                            topTracks = artistTopTracks,
                            albums = artistAlbums,
                            isLoading = isLoadingArtistDetail,
                            errorMessage = artistDetailError,
                            playbackState = playbackState,
                            isFavorite = { id -> id in favoriteIds },
                            onBackClick = { viewModel.navigateBack() },
                            onTrackClick = { track -> viewModel.playTrack(track, activityContext.getString(R.string.main_context_popular_tracks_fmt, selectedArtist?.name ?: track.artist)) },
                            onFavoriteToggle = { track -> viewModel.toggleFavorite(track) },
                            onPlayAllTracks = { viewModel.playQueue(artistTopTracks, 0, activityContext.getString(R.string.main_context_popular_tracks_fmt, selectedArtist?.name.orEmpty())) },
                            onShuffleTracks = { viewModel.playQueue(artistTopTracks.shuffled(), 0, activityContext.getString(R.string.main_context_popular_tracks_fmt, selectedArtist?.name.orEmpty())) },
                            onAlbumClick = { albumId -> viewModel.navigateToAlbum(albumId) },
                            onRetry = { viewModel.loadArtistDetail(dest.artistId) },
                            onAddToQueue = { track -> viewModel.addToQueue(track) },
                                    onPlayNextTrack = { track -> viewModel.addToQueueNext(track) },
                            onAddToPlaylist = { track -> viewModel.showAddToPlaylistDialog(track) },
                            sourceActions = trackSourceActions
                        )
                    }

                    is ScreenDestination.AlbumDetail -> {
                        AlbumDetailScreen(
                            album = selectedAlbum,
                            tracks = albumTracks,
                            isLoading = isLoadingAlbumDetail,
                            errorMessage = albumDetailError,
                            playbackState = playbackState,
                            isFavorite = { id -> id in favoriteIds },
                            onBackClick = { viewModel.navigateBack() },
                            onTrackClick = { track, index ->
                                val alb = selectedAlbum
                                if (alb != null) {
                                    viewModel.playAlbum(alb, albumTracks, index)
                                } else {
                                    viewModel.playTrack(track)
                                }
                            },
                            onPlayAlbum = {
                                selectedAlbum?.let { viewModel.playAlbum(it, albumTracks, 0) }
                            },
                            onShuffleAlbum = {
                                selectedAlbum?.let { viewModel.playAlbum(it, albumTracks.shuffled(), 0) }
                            },
                            onFavoriteToggle = { track -> viewModel.toggleFavorite(track) },
                            onArtistClick = { artistName ->
                                viewModel.onSearchQueryChange(artistName)
                                viewModel.selectTab(NavigationTab.SEARCH)
                            },
                            onRetry = { viewModel.loadAlbumDetail(dest.albumId) },
                            onAddToQueue = { track -> viewModel.addToQueue(track) },
                                    onPlayNextTrack = { track -> viewModel.addToQueueNext(track) },
                            onAddToPlaylist = { track -> viewModel.showAddToPlaylistDialog(track) },
                            sourceActions = trackSourceActions
                        )
                    }

                    is ScreenDestination.Diagnostics -> {
                        DiagnosticsScreen(
                            healthList = healthList,
                            isRefreshing = isRefreshingHealth,
                            onRefreshAll = { viewModel.refreshHealth() },
                            onBackClick = { viewModel.navigateBack() }
                        )
                    }

                    is ScreenDestination.PlaylistDetail -> {
                        val activePlaylist = playlists.firstOrNull { it.id == dest.playlistId }
                        PlaylistDetailScreen(
                            playlist = activePlaylist,
                            playbackState = playbackState,
                            isFavorite = { id -> id in favoriteIds },
                            onBackClick = { viewModel.navigateBack() },
                            onTrackClick = { track, index ->
                                if (activePlaylist != null && activePlaylist.tracks.isNotEmpty()) {
                                    viewModel.playQueue(activePlaylist.tracks, index, activityContext.getString(R.string.main_context_playlist_fmt, activePlaylist.name))
                                } else {
                                    viewModel.playTrack(track, mainContextPlaylist)
                                }
                            },
                            onPlayPlaylist = {
                                activePlaylist?.let { pl ->
                                    if (pl.tracks.isNotEmpty()) viewModel.playQueue(pl.tracks, 0, activityContext.getString(R.string.main_context_playlist_fmt, pl.name))
                                }
                            },
                            onShufflePlaylist = {
                                activePlaylist?.let { pl ->
                                    if (pl.tracks.isNotEmpty()) viewModel.playQueue(pl.tracks.shuffled(), 0, activityContext.getString(R.string.main_context_playlist_fmt, pl.name))
                                }
                            },
                            onFavoriteToggle = { track -> viewModel.toggleFavorite(track) },
                            onRemoveTrack = { trackId ->
                                activePlaylist?.let { pl ->
                                    viewModel.removeTrackFromPlaylist(pl.id, trackId)
                                }
                            },
                            onDeletePlaylist = {
                                activePlaylist?.let { pl ->
                                    viewModel.deletePlaylist(pl.id)
                                    viewModel.navigateBack()
                                }
                            },
                            onRenamePlaylist = { newName, newDesc ->
                                activePlaylist?.let { pl ->
                                    viewModel.updatePlaylist(pl.id, newName, newDesc)
                                }
                            },
                            onUpdatePlaylist = { newName, newDesc, newCover ->
                                activePlaylist?.let { pl ->
                                    viewModel.updatePlaylist(pl.id, newName, newDesc, newCover)
                                }
                            },
                            onMoveTrack = { from, to ->
                                activePlaylist?.let { pl ->
                                    viewModel.reorderPlaylist(pl.id, from, to)
                                }
                            },
                            onAddToPlaylist = { track ->
                                viewModel.showAddToPlaylistDialog(track)
                            },
                            onAddToQueue = { track ->
                                viewModel.addToQueue(track)
                            },
                            onPlayNextTrack = { track ->
                                viewModel.addToQueueNext(track)
                            },
                            sourceActions = trackSourceActions
                        )
                    }

                    is ScreenDestination.YouTubeArtistPage -> {
                        val detail = selectedYtArtist
                        val artist = detail?.artist
                        YouTubeDetailScreen(
                            headerTitle = artist?.name ?: dest.name.orEmpty(),
                            headerSubtitle = artist?.subtitle,
                            coverUrl = artist?.thumbnailUrl,
                            tracks = detail?.topTracks.orEmpty(),
                            albums = detail?.albums.orEmpty(),
                            singlesOrEps = detail?.singlesOrEps.orEmpty(),
                            videos = detail?.videos.orEmpty(),
                            immersiveHero = true,
                            onAlbumClick = { album ->
                                album.detailId?.let { viewModel.navigateToYouTubeAlbum(it, album.title) }
                            },
                            isLoading = isLoadingYtDetail,
                            errorMessage = ytDetailError,
                            playbackState = playbackState,
                            isFavorite = { id -> id in favoriteIds },
                            onBackClick = { viewModel.navigateBack() },
                            onTrackClick = { track, index ->
                                val list = detail?.topTracks.orEmpty()
                                if (list.isNotEmpty()) viewModel.playQueue(list, index, activityContext.getString(R.string.main_context_artist_fmt, artist?.name.orEmpty()))
                                else viewModel.playTrack(track, mainContextYoutubeMusic)
                            },
                            onFavoriteToggle = { track -> viewModel.toggleFavorite(track) },
                            onPlayAll = { detail?.let { viewModel.playQueue(it.topTracks, 0, activityContext.getString(R.string.main_context_artist_fmt, artist?.name.orEmpty())) } },
                            onShuffle = { detail?.let { viewModel.playQueue(it.topTracks.shuffled(), 0, activityContext.getString(R.string.main_context_artist_fmt, artist?.name.orEmpty())) } },
                            onRetry = { viewModel.loadYouTubeArtistDetail(dest.browseId) },
                            isFollowing = if (artist != null) viewModel.isFollowingArtist(artist, followedArtists) else null,
                            onToggleFollow = if (artist != null) ({ viewModel.toggleFollowArtist(artist) }) else null,
                            onShowAllTracks = if (detail?.hasAllSongs == true) {
                                {
                                    viewModel.navigateToYouTubeArtistSongs(
                                        detail.songsBrowseId!!,
                                        detail.songsBrowseParams
                                    )
                                }
                            } else null,
                            onAddToQueue = { track -> viewModel.addToQueue(track) },
                                    onPlayNextTrack = { track -> viewModel.addToQueueNext(track) },
                            onAddToPlaylist = { track -> viewModel.showAddToPlaylistDialog(track) },
                            sourceActions = trackSourceActions
                        )
                    }

                    is ScreenDestination.YouTubeArtistSongsPage -> {
                        val page = selectedYtSongs
                        YouTubeSongsScreen(
                            title = dest.title ?: stringResource(R.string.main_popular_tracks_title),
                            tracks = page?.tracks.orEmpty(),
                            isLoading = isLoadingYtSongs,
                            errorMessage = ytSongsError,
                            hasMore = !page?.continuation.isNullOrBlank(),
                            playbackState = playbackState,
                            isFavorite = { id -> id in favoriteIds },
                            onBackClick = { viewModel.navigateBack() },
                            onTrackClick = { track, index ->
                                val list = page?.tracks.orEmpty()
                                if (list.isNotEmpty()) viewModel.playQueue(list, index, mainContextYoutubeMusic)
                                else viewModel.playTrack(track, mainContextYoutubeMusic)
                            },
                            onFavoriteToggle = { track -> viewModel.toggleFavorite(track) },
                            onLoadMore = { viewModel.loadMoreYouTubeArtistSongs() },
                            onAddToQueue = { track -> viewModel.addToQueue(track) },
                                    onPlayNextTrack = { track -> viewModel.addToQueueNext(track) },
                            onAddToPlaylist = { track -> viewModel.showAddToPlaylistDialog(track) },
                            sourceActions = trackSourceActions
                        )
                    }

                    is ScreenDestination.YouTubeAlbumPage -> {
                        val detail = selectedYtAlbum
                        val album = detail?.album
                        YouTubeDetailScreen(
                            headerTitle = album?.title ?: dest.title.orEmpty(),
                            headerSubtitle = listOfNotNull(
                                album?.type?.takeIf { it.isNotBlank() },
                                album?.artist?.takeIf { it.isNotBlank() },
                                album?.year?.takeIf { it.isNotBlank() },
                                album?.trackCount?.let { activityContext.getString(R.string.main_track_count, it) }
                            ).joinToString(" • ").ifBlank { null },
                            coverUrl = album?.thumbnailUrl,
                            tracks = detail?.tracks.orEmpty(),
                            isLoading = isLoadingYtDetail,
                            errorMessage = ytDetailError,
                            playbackState = playbackState,
                            isFavorite = { id -> id in favoriteIds },
                            onBackClick = { viewModel.navigateBack() },
                            onTrackClick = { track, index ->
                                val list = detail?.tracks.orEmpty()
                                if (list.isNotEmpty()) viewModel.playQueue(list, index, activityContext.getString(R.string.main_context_album_fmt, album?.title.orEmpty()))
                                else viewModel.playTrack(track, mainContextYoutubeMusic)
                            },
                            onFavoriteToggle = { track -> viewModel.toggleFavorite(track) },
                            onPlayAll = { detail?.let { viewModel.playQueue(it.tracks, 0, activityContext.getString(R.string.main_context_album_fmt, album?.title.orEmpty())) } },
                            onShuffle = { detail?.let { viewModel.playQueue(it.tracks.shuffled(), 0, activityContext.getString(R.string.main_context_album_fmt, album?.title.orEmpty())) } },
                            onRetry = { viewModel.loadYouTubeAlbumDetail(dest.browseId) },
                            onAddToQueue = { track -> viewModel.addToQueue(track) },
                                    onPlayNextTrack = { track -> viewModel.addToQueueNext(track) },
                            onAddToPlaylist = { track -> viewModel.showAddToPlaylistDialog(track) },
                            sourceActions = trackSourceActions
                        )
                    }

                    is ScreenDestination.YouTubePlaylistPage -> {
                        val detail = selectedYtPlaylist
                        val playlist = detail?.playlist
                        YouTubeDetailScreen(
                            headerTitle = playlist?.title ?: dest.title.orEmpty(),
                            headerSubtitle = listOfNotNull(
                                playlist?.author?.takeIf { it.isNotBlank() },
                                playlist?.trackCount?.let { activityContext.getString(R.string.main_track_count, it) }
                            ).joinToString(" • ").ifBlank { null },
                            coverUrl = playlist?.thumbnailUrl,
                            tracks = detail?.tracks.orEmpty(),
                            isLoading = isLoadingYtDetail,
                            errorMessage = ytDetailError,
                            playbackState = playbackState,
                            isFavorite = { id -> id in favoriteIds },
                            onBackClick = { viewModel.navigateBack() },
                            onTrackClick = { track, index ->
                                val list = detail?.tracks.orEmpty()
                                if (list.isNotEmpty()) viewModel.playQueue(list, index, activityContext.getString(R.string.main_context_playlist_fmt, playlist?.title.orEmpty()))
                                else viewModel.playTrack(track, mainContextYoutubeMusic)
                            },
                            onFavoriteToggle = { track -> viewModel.toggleFavorite(track) },
                            onPlayAll = { detail?.let { viewModel.playQueue(it.tracks, 0, activityContext.getString(R.string.main_context_playlist_fmt, playlist?.title.orEmpty())) } },
                            onShuffle = { detail?.let { viewModel.playQueue(it.tracks.shuffled(), 0, activityContext.getString(R.string.main_context_playlist_fmt, playlist?.title.orEmpty())) } },
                            onRetry = { viewModel.loadYouTubePlaylistDetail(dest.playlistId) },
                            onAddToQueue = { track -> viewModel.addToQueue(track) },
                                    onPlayNextTrack = { track -> viewModel.addToQueueNext(track) },
                            onAddToPlaylist = { track -> viewModel.showAddToPlaylistDialog(track) },
                            onSaveToLibrary = {
                                val id = dest.playlistId
                                if (savingYtPlaylistId == null) {
                                    savingYtPlaylistId = id
                                    uiScope.launch {
                                        val result = viewModel.importYouTubePlaylist(id)
                                        savingYtPlaylistId = null
                                        result.fold(
                                            onSuccess = { pl ->
                                                snackbarHostState.showSnackbar(
                                                    activityContext.getString(R.string.main_playlist_saved, pl.name, pl.tracks.size)
                                                )
                                            },
                                            onFailure = { err ->
                                                snackbarHostState.showSnackbar(
                                                    err.message ?: mainImportFailed
                                                )
                                            }
                                        )
                                    }
                                }
                            },
                            isSavingToLibrary = savingYtPlaylistId == dest.playlistId
                        )
                    }
                }
            }
        }
        }

        // Shared-element host for the mini player ⇄ full player morph.
        SharedTransitionLayout {
            val sharedScope = this
            Box(modifier = Modifier.fillMaxSize()) {
        // Floating Liquid Glass bottom chrome overlay (rendered outside the Scaffold so
        // content scrolls behind it; lists keep clear via LocalBottomChromeInset).
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            val track = playbackState.currentTrack
            AnimatedVisibility(
                visible = track != null && !isPlayerExpanded,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                if (track != null) {
                    MiniPlayer(
                        track = track,
                        isPlaying = playbackState.isPlaying,
                        isLoading = playbackState.isLoading,
                        positionMs = playbackState.positionMs,
                        durationMs = playbackState.durationMs,
                        isFavorite = track.id in favoriteIds,
                        onPlayPause = { viewModel.togglePlayPause() },
                        onSkipNext = { viewModel.playNext() },
                        onFavoriteToggle = { viewModel.toggleFavorite(track) },
                        onClick = { viewModel.setPlayerExpanded(true) },
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                        sharedTransitionScope = sharedScope,
                        animatedVisibilityScope = this
                    )
                }
            }
            SonoraBottomNav(
                navItems = navItems,
                currentTab = currentTab,
                isTabActive = currentDestination is ScreenDestination.TabContent,
                onSelect = { tab -> viewModel.selectTab(tab) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }

        // Animated Full Player Modal
        AnimatedVisibility(
            visible = isPlayerExpanded && playbackState.currentTrack != null,
            enter = slideInVertically(initialOffsetY = { fullHeight -> fullHeight }),
            exit = slideOutVertically(targetOffsetY = { fullHeight -> fullHeight })
        ) {
            val track = playbackState.currentTrack
            if (track != null) {
                PlayerScreen(
                    playbackState = playbackState,
                    isFavorite = track.id in favoriteIds,
                    isDisliked = viewModel.isDisliked(track.id),
                    lyrics = lyrics,
                    isLoadingLyrics = isLoadingLyrics,
                    isLyricsOpen = isLyricsOpen,
                    onCollapse = { viewModel.setPlayerExpanded(false) },
                    onPlayPause = { viewModel.togglePlayPause() },
                    onPlayNext = { viewModel.playNext() },
                    onPlayPrevious = { viewModel.playPrevious() },
                    onSeekTo = { posMs -> viewModel.seekTo(posMs) },
                    onToggleShuffle = { viewModel.toggleShuffle() },
                    onToggleRepeat = { viewModel.toggleRepeat() },
                    onToggleFavorite = { t -> viewModel.toggleFavorite(t) },
                    onToggleDislike = { t -> viewModel.toggleDislike(t) },
                    onAddToPlaylist = { t -> viewModel.showAddToPlaylistDialog(t) },
                    onShareTrack = { t -> trackToShare = t },
                    onToggleLyrics = { viewModel.toggleLyricsOpen() },
                    onOpenQueue = { viewModel.openQueueSheet() },
                    onOpenEqualizer = { viewModel.openEqualizerDialog() },
                    onArtistClick = { artist ->
                        viewModel.setPlayerExpanded(false)
                        viewModel.openArtistByName(artist)
                    },
                    translateLyrics = translateLyrics,
                    targetLang = translationTargetLang,
                    highlightMode = lyricsHighlightMode,
                    onToggleTranslate = { enabled -> viewModel.setTranslateLyrics(enabled) },
                    onTargetLangChange = { code -> viewModel.setTranslationTargetLang(code) },
                    onHighlightModeChange = { mode -> viewModel.setLyricsHighlightMode(mode) },
                    musixmatchEnabled = lyricsProviderMusixmatch,
                    onToggleMusixmatch = { e -> viewModel.setLyricsProviderMusixmatch(e) },
                    lrclibEnabled = lyricsProviderLrclib,
                    onToggleLrclib = { e -> viewModel.setLyricsProviderLrclib(e) },
                    onAddToQueue = { viewModel.addToQueue(it) },
                    onPlayNextTrack = { viewModel.addToQueueNext(it) },
                    sourceActions = trackSourceActions,
                    sharedTransitionScope = sharedScope,
                    animatedVisibilityScope = this
                )
            }
        }
            } // morph Box
        } // SharedTransitionLayout

        // Queue Bottom Sheet
        if (isQueueSheetOpen) {
            QueueBottomSheet(
                playbackState = playbackState,
                isOpen = isQueueSheetOpen,
                onDismissRequest = { viewModel.closeQueueSheet() },
                onTrackClick = { index -> viewModel.playFromQueueIndex(index) },
                onRemoveTrack = { index -> viewModel.removeFromQueue(index) },
                onClearQueue = { viewModel.clearQueue() },
                onToggleAutoplay = { viewModel.toggleAutoplay() },
                onMoveQueueItem = { from, to -> viewModel.moveQueueItem(from, to) },
                sourceActions = trackSourceActions
            )
        }

        // Equalizer Modal Bottom Sheet
        if (isEqualizerDialogOpen) {
            EqualizerDialog(
                isOpen = isEqualizerDialogOpen,
                state = equalizerState,
                onDismissRequest = { viewModel.closeEqualizerDialog() },
                onEnabledChange = { viewModel.setEqualizerEnabled(it) },
                onPresetChange = { viewModel.setEqualizerPreset(it) },
                onBandGainChange = { band, gain -> viewModel.setEqualizerBandGain(band, gain) },
                onBassBoostChange = { viewModel.setBassBoostStrength(it) },
                onReset = { viewModel.resetEqualizer() }
            )
        }

        // Share Track Dialog
        trackToShare?.let { track ->
            ShareTrackDialog(
                track = track,
                onDismiss = { trackToShare = null }
            )
        }

        // Add to Playlist Bottom Sheet Dialog
        val currentTrackForPlaylist = trackToAddToPlaylist
        if (currentTrackForPlaylist != null) {
            AddToPlaylistDialog(
                track = currentTrackForPlaylist,
                playlists = playlists,
                onDismissRequest = { viewModel.hideAddToPlaylistDialog() },
                onPlaylistSelect = { targetPlaylist ->
                    if (targetPlaylist.tracks.any { it.id == currentTrackForPlaylist.id }) {
                        viewModel.removeTrackFromPlaylist(targetPlaylist.id, currentTrackForPlaylist.id)
                    } else {
                        viewModel.addTrackToPlaylist(targetPlaylist.id, currentTrackForPlaylist)
                    }
                    viewModel.hideAddToPlaylistDialog()
                },
                onCreatePlaylist = { name ->
                    viewModel.createPlaylistWithTracks(
                        name = name,
                        tracks = listOf(currentTrackForPlaylist)
                    )
                    viewModel.hideAddToPlaylistDialog()
                }
            )
        }
    }
}
