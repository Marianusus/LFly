package org.sonora.android.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbDownOffAlt
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.sonora.android.R
import org.sonora.android.api.Track
import org.sonora.android.data.SearchHistoryEntry
import org.sonora.android.data.SearchHistoryStore
import org.sonora.android.data.SearchHistoryType
import org.sonora.android.data.MusicRepository
import org.sonora.android.data.Playlist
import org.sonora.android.party.PartyPermissions
import org.sonora.android.party.PartyRole
import org.sonora.android.playback.DownloadManager
import org.sonora.android.playback.DownloadProgress
import org.sonora.android.playback.DownloadStatus
import org.sonora.android.api.ArtistCredit
import org.sonora.android.api.ArtistCredits
import org.sonora.android.ui.components.AddToPlaylistDialog
import org.sonora.android.ui.components.DownloadUiState
import org.sonora.android.ui.components.BottomChrome
import org.sonora.android.ui.components.PartyBottomSheet
import org.sonora.android.ui.components.TrackSourceActions
import org.sonora.android.ui.components.chromeSurface
import org.sonora.android.ui.player.shouldOpenPlayerSheet
import org.sonora.android.ui.components.EqualizerDialog
import org.sonora.android.ui.components.HeldTrackAction
import org.sonora.android.ui.components.HeldTrackMenuGroup
import org.sonora.android.ui.components.HeldTrackMenuHost
import org.sonora.android.ui.components.HeldTrackMenuRequest
import org.sonora.android.ui.components.LocalBottomChromeInset
import org.sonora.android.ui.components.LocalDownloadUi
import org.sonora.android.ui.components.LocalTrackContextMenu
import org.sonora.android.ui.components.TrackContextMenuController
import org.sonora.android.ui.components.TrackInfoSheet
import org.sonora.android.ui.components.artistCredits
import org.sonora.android.ui.components.aggregateDownloadProgress
import org.sonora.android.ui.components.backdrop.LocalAppBackdrop
import org.sonora.android.ui.components.backdrop.LocalLiquidGlassEnabled
import org.sonora.android.ui.components.backdrop.isLiquidGlassSupported
import org.sonora.android.ui.components.backdrop.backdrops.layerBackdrop
import org.sonora.android.ui.components.backdrop.backdrops.rememberLayerBackdrop
import org.sonora.android.ui.components.LiquidGlassChrome
import org.sonora.android.ui.components.MiniPlayer
import org.sonora.android.ui.components.rememberGlassChromeScrollState
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
    val searchFocusRequests by viewModel.searchFocusRequests.collectAsState()
    val activityContext = LocalContext.current
    val motion = LocalMotionPreferences.current
    val playbackState by viewModel.playbackState.collectAsState()
    val trackMetadata by viewModel.trackMetadata.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val history by viewModel.history.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val homeTopTracks by viewModel.homeTopTracks.collectAsState()
    val homeTopPlaylists by viewModel.homeTopPlaylists.collectAsState()
    val mixes by viewModel.mixes.collectAsState()
    val savedMixes by viewModel.savedMixes.collectAsState()
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
    val homeCountry by viewModel.homeCountry.collectAsState()
    val countryTrendingTracks by viewModel.countryTrendingTracks.collectAsState()
    val isLoadingCountryTrending by viewModel.isLoadingCountryTrending.collectAsState()
    val dislikedTrackIds by viewModel.dislikedTrackIds.collectAsState()

    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchCategory by viewModel.searchCategory.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val searchSuggestions by viewModel.searchSuggestions.collectAsState()
    val isSearchTyping by viewModel.isSearchTyping.collectAsState()
    val searchHistory by viewModel.searchHistory.collectAsState()
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
    val playerScreenHeightPx = with(LocalDensity.current) {
        LocalConfiguration.current.screenHeightDp.dp.toPx()
    }
    var playerDragVisible by remember { mutableStateOf(false) }
    var playerDragSession by remember { mutableStateOf(false) }
    var playerCustomExit by remember { mutableStateOf(false) }
    var playerSuppressShared by remember { mutableStateOf(false) }
    var playerDragProgress by remember { mutableFloatStateOf(0f) }
    var playerSettleJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(isPlayerExpanded, playerDragVisible, playerDragSession) {
        if (!isPlayerExpanded && !playerDragVisible) {
            if (playerDragSession) {
                // Let the drag-driven exit be removed for one frame before the
                // normal AnimatedVisibility exit transition is restored.
                withFrameNanos { }
                playerDragSession = false
                playerDragProgress = 0f
                playerCustomExit = false
                playerSuppressShared = false
            } else {
                playerDragProgress = 0f
            }
        }
    }
    val isLyricsOpen by viewModel.isLyricsOpen.collectAsState()
    val lyrics by viewModel.lyrics.collectAsState()
    val isLoadingLyrics by viewModel.isLoadingLyrics.collectAsState()
    val isQueueSheetOpen by viewModel.isQueueSheetOpen.collectAsState()
    val isPartySheetOpen by viewModel.isPartySheetOpen.collectAsState()
    val partyState by viewModel.partyState.collectAsState()
    val partyDiscovered by viewModel.partyDiscovered.collectAsState()
    val partyContext = LocalContext.current
    var partyPermissionsGranted by remember { mutableStateOf(PartyPermissions.granted(partyContext)) }
    val partyPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { partyPermissionsGranted = PartyPermissions.granted(partyContext) }
    LaunchedEffect(isPartySheetOpen) {
        if (isPartySheetOpen) partyPermissionsGranted = PartyPermissions.granted(partyContext)
    }
    val isEqualizerDialogOpen by viewModel.isEqualizerDialogOpen.collectAsState()
    val equalizerState by viewModel.equalizerState.collectAsState()
    val trackToAddToPlaylist by viewModel.trackToAddToPlaylist.collectAsState()

    var trackToShare by remember { mutableStateOf<Track?>(null) }
    var heldTrackMenu by remember { mutableStateOf<HeldTrackMenuRequest?>(null) }
    var infoTrack by remember { mutableStateOf<Track?>(null) }
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
    val downloadQuality by viewModel.downloadQuality.collectAsState()
    val downloadWifiOnly by viewModel.downloadWifiOnly.collectAsState()
    val downloadLyrics by viewModel.downloadLyrics.collectAsState()
    val downloadStates by viewModel.downloadStates.collectAsState()
    val offlineTracks by viewModel.offlineTracks.collectAsState()
    val appDownloadedTracks by viewModel.downloadedTracks.collectAsState()
    val offlineLyricsStates by viewModel.offlineLyricsStates.collectAsState()
    val downloadsSizeBytes by viewModel.downloadsSizeBytes.collectAsState()
    val isOnline by viewModel.isOnline.collectAsState()
    val isAutoplayEnabled by viewModel.isAutoplayEnabled.collectAsState()
    val cacheSizeBytes by viewModel.cacheSizeBytes.collectAsState()
    val currentThemeStyle by viewModel.themeStyle.collectAsState()
    val appearanceMode by viewModel.appearanceMode.collectAsState()
    val reduceMotion by viewModel.reduceMotion.collectAsState()
    val isLiquidGlassEnabled by viewModel.liquidGlass.collectAsState()
    val glassScrollState = rememberGlassChromeScrollState()

    val openFullPlayer: () -> Unit = {
        playerSettleJob?.cancel()
        playerDragSession = false
        playerCustomExit = false
        playerSuppressShared = false
        playerDragProgress = 0f
        viewModel.setPlayerExpanded(true)
    }
    val beginPlayerPull: () -> Unit = {
        playerSettleJob?.cancel()
        playerDragVisible = true
        playerDragSession = true
        playerCustomExit = true
        playerSuppressShared = true
        playerDragProgress = 0f
        viewModel.setPlayerExpanded(true)
    }
    val updatePlayerPull: (Float) -> Unit = { pulledPx ->
        playerDragProgress = (pulledPx / playerScreenHeightPx).coerceIn(0f, 1f)
    }
    val releasePlayerPull: (Float) -> Unit = { velocityY ->
        val shouldOpen = shouldOpenPlayerSheet(playerDragProgress, velocityY)
        playerSettleJob?.cancel()
        playerSettleJob = uiScope.launch {
            animate(
                initialValue = playerDragProgress,
                targetValue = if (shouldOpen) 1f else 0f,
                animationSpec = spring(
                    dampingRatio = 0.78f,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) { value, _ -> playerDragProgress = value }
            if (shouldOpen) {
                playerDragSession = false
                playerCustomExit = false
                playerDragVisible = false
            } else {
                viewModel.setPlayerExpanded(false)
                playerDragVisible = false
            }
        }
    }
    val selectTab: (NavigationTab) -> Unit = { tab ->
        if (tab == NavigationTab.SEARCH) {
            // BitChord behaviour: every Search tap resets the field, returns to the
            // search root and opens the keyboard, even when Search is already active.
            viewModel.selectTab(NavigationTab.SEARCH)
            viewModel.onSearchQueryChange("")
            viewModel.requestSearchFocus()
        } else {
            viewModel.selectTab(tab)
        }
    }
    val translateLyrics by viewModel.translateLyrics.collectAsState()
    val translationTargetLang by viewModel.translationTargetLang.collectAsState()
    val lyricsHighlightMode by viewModel.lyricsHighlightMode.collectAsState()
    val lyricsProviderMusixmatch by viewModel.lyricsProviderMusixmatch.collectAsState()
    val lyricsProviderLrclib by viewModel.lyricsProviderLrclib.collectAsState()
    val lyricsSources by viewModel.lyricsSources.collectAsState()
    val lyricsSourceOrder by viewModel.lyricsSourceOrder.collectAsState()
    val prioritizeSyllableSync by viewModel.prioritizeSyllableSync.collectAsState()
    val paxsenixApiKey by viewModel.paxsenixApiKey.collectAsState()
    val activeLyricsSource by viewModel.activeLyricsSource.collectAsState()
    val lyricsProviderStates by viewModel.lyricsProviderStates.collectAsState()

    val navItems = listOf(
        NavItem.Home,
        NavItem.Search,
        NavItem.Library,
        NavItem.Settings
    )

    val favoriteIds = remember(favorites) { favorites.map { it.id }.toSet() }
    val favoriteLookup = remember(favoriteIds) { { id: String -> id in favoriteIds } }
    // Favorites are stored under the catalog id; a track that is currently playing from
    // a local file can still carry that catalog id (or resolve back to it by path).
    val currentFavoriteId = remember(playbackState.currentTrack, downloadStates) {
        playbackState.currentTrack?.let { track ->
            DownloadManager.canonicalTrackFor(track)?.id ?: track.id
        }
    }
    val isCurrentFavorite = currentFavoriteId?.let { it in favoriteIds } == true
    val isCurrentDisliked = currentFavoriteId?.let { it in dislikedTrackIds } == true
    // A local-file match can replace `currentTrack` with a different id; the queued id
    // keeps the collection's now-playing row stable.
    val collectionCurrentTrackId =
        playbackState.queue.getOrNull(playbackState.currentIndex)?.id ?: playbackState.currentTrack?.id
    val albumDownloadProgress = remember(albumTracks, downloadStates) {
        aggregateDownloadProgress(albumTracks, downloadStates)
    }
    val favoritesDownloadProgress = remember(favorites, downloadStates) {
        aggregateDownloadProgress(favorites, downloadStates)
    }

    // Home does not need the 4 Hz playback position ticker: the resume hero is hidden
    // while a track is playing. Keeping the last state for unrelated fields prevents
    // the whole Home glass tree from recomposing on every position update.
    val homePlaybackState = remember(
        playbackState.currentTrack,
        playbackState.isPlaying,
        playbackState.isLoading,
        playbackState.queue,
        playbackState.currentIndex,
        playbackState.durationMs,
    ) {
        playbackState.copy(positionMs = 0L)
    }

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

    val handleSearchHistoryClick: (SearchHistoryEntry) -> Unit = { entry ->
        when (entry.type) {
            SearchHistoryType.QUERY -> viewModel.submitSearch(entry.title)
            SearchHistoryType.TRACK -> {
                viewModel.recordSearchHistory(entry)
                val track = entry.track ?: Track(
                    id = entry.id,
                    title = entry.title,
                    artist = entry.subtitle,
                    album = "",
                    durationMs = 0L,
                    coverUrl = entry.artworkUrl,
                    streamUrl = null,
                    provider = "YouTube"
                )
                viewModel.playTrack(track, mainContextSearch)
            }
            SearchHistoryType.ARTIST -> {
                viewModel.recordSearchHistory(entry)
                viewModel.navigateToYouTubeArtist(entry.id, entry.title)
            }
            SearchHistoryType.ALBUM -> {
                viewModel.recordSearchHistory(entry)
                viewModel.navigateToYouTubeAlbum(entry.id, entry.title)
            }
            SearchHistoryType.PLAYLIST -> {
                viewModel.recordSearchHistory(entry)
                viewModel.navigateToYouTubePlaylist(entry.id, entry.title)
            }
        }
    }

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
    } else if (playerDragSession) {
        BackHandler {
            playerSettleJob?.cancel()
            playerDragProgress = 0f
            playerDragVisible = false
            viewModel.setPlayerExpanded(false)
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

    val downloadUi = remember(downloadStates, isOnline, viewModel) {
        DownloadUiState(
            states = downloadStates,
            onToggle = { track -> viewModel.toggleDownload(track) },
            onDeleteLocal = { track -> viewModel.deleteLocalDownload(track) },
            isManagedLocal = { track -> DownloadManager.isManagedLocalPath(track.streamUrl) },
            isOnline = isOnline
        )
    }

    val menuContext = LocalContext.current
    val menuAccent = LocalBackgroundScheme.current.accent
    fun action(icon: ImageVector, label: String, tint: Color? = null, onClick: () -> Unit) =
        HeldTrackAction(icon = icon, label = label, tint = tint, onClick = {
            heldTrackMenu = null
            onClick()
        })

    val openHeldTrackMenu: (Track, Rect, List<HeldTrackMenuGroup>) -> Unit = { track, anchor, extras ->
        val isFav = track.id in favoriteIds
        val disliked = track.id in dislikedTrackIds
        val progress = downloadUi.progressFor(track.id)
        val isLocal = track.provider.equals("Local", ignoreCase = true)
        val groups = buildList {
            add(HeldTrackMenuGroup(buildList {
                add(action(
                    if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    menuContext.getString(if (isFav) R.string.track_actions_favorite_remove else R.string.track_actions_favorite_add),
                    if (isFav) menuAccent else null,
                ) { viewModel.toggleFavorite(track) })
                add(action(
                    if (disliked) Icons.Default.ThumbDown else Icons.Default.ThumbDownOffAlt,
                    menuContext.getString(if (disliked) R.string.track_item_dislike_remove else R.string.track_item_dislike_add),
                    if (disliked) Color(0xFFE57373) else null,
                ) { viewModel.toggleDislike(track) })
                add(action(Icons.AutoMirrored.Filled.PlaylistAdd, menuContext.getString(R.string.track_item_add_to_playlist)) {
                    viewModel.showAddToPlaylistDialog(track)
                })
            }))
            if (!isLocal) {
                add(HeldTrackMenuGroup(buildList {
                    add(action(
                        if (progress.status == DownloadStatus.DOWNLOADED) Icons.Default.DownloadDone else Icons.Default.Download,
                        menuContext.getString(
                            when (progress.status) {
                                DownloadStatus.DOWNLOADED -> R.string.download_remove
                                DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> R.string.download_cancel
                                DownloadStatus.FAILED -> R.string.download_retry
                                else -> R.string.download_action
                            }
                        ),
                        if (progress.status == DownloadStatus.DOWNLOADED) menuAccent else null,
                    ) { viewModel.toggleDownload(track) })
                    if (downloadUi.isManagedLocal(track) || (isLocal && downloadUi.isDownloaded(track.id))) {
                        add(action(Icons.Default.DeleteOutline, menuContext.getString(R.string.download_delete_local_file), Color(0xFFE57373)) {
                            viewModel.deleteLocalDownload(track)
                        })
                    }
                }))
            }
            add(HeldTrackMenuGroup(buildList {
                add(action(Icons.Default.PlayArrow, menuContext.getString(R.string.track_item_play_next)) { viewModel.addToQueueNext(track) })
                add(action(Icons.Default.QueueMusic, menuContext.getString(R.string.track_item_add_to_queue)) { viewModel.addToQueue(track) })
            }))
            add(HeldTrackMenuGroup(buildList {
                track.albumId?.let { albumId ->
                    add(action(Icons.Default.Album, menuContext.getString(R.string.track_actions_open_album)) {
                        viewModel.navigateToYouTubeAlbum(albumId, track.album)
                    })
                }
                ArtistCredits.primary(track.artistCredits())?.let { credit ->
                    add(action(Icons.Default.Person, menuContext.getString(R.string.track_actions_open_artist)) {
                        viewModel.openArtist(credit)
                    })
                }
                add(action(Icons.Default.Info, menuContext.getString(R.string.track_info_menu)) { infoTrack = track })
                add(action(Icons.Default.Share, menuContext.getString(R.string.track_item_share)) { trackToShare = track })
                add(action(Icons.Default.Equalizer, menuContext.getString(R.string.player_equalizer_dsp)) { viewModel.openEqualizerDialog() })
            }))
            if (trackSourceActions != null &&
                (track.id.startsWith("yt_") || track.id.startsWith("spotify_")) && !isLocal
            ) {
                val override = trackSourceActions.overrideFor(track.id)
                add(HeldTrackMenuGroup(buildList {
                    add(action(if (override == null) Icons.Default.Check else Icons.Default.Language, menuContext.getString(R.string.track_source_auto)) {
                        trackSourceActions.onClear(track)
                    })
                    add(action(
                        if (override == org.sonora.android.api.PlaybackSource.YOUTUBE_MUSIC) Icons.Default.Check else Icons.Default.Language,
                        menuContext.getString(R.string.track_play_via_youtube)
                    ) { trackSourceActions.onSelect(track, org.sonora.android.api.PlaybackSource.YOUTUBE_MUSIC) })
                    add(action(
                        if (override == org.sonora.android.api.PlaybackSource.MONOCHROME) Icons.Default.Check else Icons.Default.Language,
                        menuContext.getString(R.string.track_play_via_monochrome)
                    ) { trackSourceActions.onSelect(track, org.sonora.android.api.PlaybackSource.MONOCHROME) })
                }))
            }
            addAll(extras)
        }
        heldTrackMenu = HeldTrackMenuRequest(
            track = track,
            anchor = anchor,
            groups = groups,
            onDismiss = { heldTrackMenu = null }
        )
    }
    val latestOpenHeldTrackMenu = rememberUpdatedState(openHeldTrackMenu)
    val trackMenuController = remember {
        TrackContextMenuController { track, anchor, extras ->
            latestOpenHeldTrackMenu.value(track, anchor, extras)
        }
    }
    val playerArtistClick = remember(viewModel) {
        { credit: ArtistCredit ->
            viewModel.setPlayerExpanded(false)
            viewModel.openArtist(credit)
        }
    }

    SonoraBackground(
        modifier = modifier,
        coverUrl = playbackState.currentTrack?.coverUrl
    ) {
        val hasMiniPlayer = playbackState.currentTrack != null &&
            (!isPlayerExpanded || playerDragSession)
        val navigationBarsInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val glassActive = isLiquidGlassEnabled && isLiquidGlassSupported()
        val chromeDensity = LocalDensity.current
        val bottomChromeInset = if (glassActive) {
            val glassHeight = if (playbackState.currentTrack != null) {
                BottomChrome.GlassExpandedHeight
            } else {
                BottomChrome.NavBarHeight
            }
            glassHeight + navigationBarsInset + 8.dp
        } else {
            BottomChrome.inset(hasMiniPlayer, navigationBarsInset)
        }
        val glassScheme = LocalBackgroundScheme.current
        val glassBackdropBrush = remember(glassScheme) {
            Brush.verticalGradient(glassScheme.gradient)
        }
        val appBackdrop = rememberLayerBackdrop(onDraw = {
            drawRect(brush = glassBackdropBrush)
            drawContent()
        })

        // Provide the floating-chrome inset to all scrollable screens below.
        CompositionLocalProvider(
            LocalBottomChromeInset provides bottomChromeInset,
            LocalDownloadUi provides downloadUi,
            LocalTrackContextMenu provides trackMenuController,
            LocalAppBackdrop provides appBackdrop,
            LocalLiquidGlassEnabled provides glassActive
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (glassActive) Modifier.layerBackdrop(appBackdrop) else Modifier)
                    .then(if (glassActive) Modifier.nestedScroll(glassScrollState.connection) else Modifier)
            ) {
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
                                    topTracks = homeTopTracks,
                                    topPlaylists = homeTopPlaylists,
                                    mixes = mixes,
                                    onPlaylistClick = { playlist -> viewModel.navigateToPlaylist(playlist.id) },
                                    onMixClick = { mix -> viewModel.navigateToMix(mix.id, fromLibrary = false) },
                                    onMixSaveToggle = { mix -> viewModel.toggleMixSaved(mix.id) },
                                    playbackState = homePlaybackState,
                                    isFavorite = favoriteLookup,
                                    isDisliked = { id -> viewModel.isDisliked(id) },
                                    recommendedTracks = recommendedTracks,
                                    isLoadingRecommendations = isLoadingRecommendations,
                                    countryTopTracks = countryTopTracks,
                                    isLoadingCountryTopTracks = isLoadingCountryTopTracks,
                                    countryTrendingTracks = countryTrendingTracks,
                                    isLoadingCountryTrending = isLoadingCountryTrending,
                                    homeCountryName = homeCountry.name,
                                    isLoadingCharts = isLoadingCharts,
                                    onOpenPlayer = { viewModel.setPlayerExpanded(true) },
                                    onOpenQueue = viewModel::openQueueSheet,
                                    onTogglePlayPause = viewModel::togglePlayPause,
                                    onOpenSearch = {
                                        viewModel.selectTab(NavigationTab.SEARCH)
                                        viewModel.requestSearchFocus()
                                    },
                                    onTrackClick = { track ->
                                        val context = if (track.provider == org.sonora.android.api.EuropeChartsClient.PROVIDER) {
                                            mainContextTopEu
                                        } else {
                                            mainContextTop50
                                        }
                                        viewModel.playTrack(track, context)
                                    },
                                    onFavoriteToggle = { track -> viewModel.toggleFavorite(track) },
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
                                    focusRequest = searchFocusRequests,
                                    searchResults = searchResults,
                                    ytArtistResults = searchYtArtists,
                                    ytAlbumResults = searchYtAlbums,
                                    ytPlaylistResults = searchYtPlaylists,
                                    isSearching = isSearching,
                                    isTyping = isSearchTyping,
                                    suggestions = searchSuggestions,
                                    searchHistory = searchHistory,
                                    playbackState = playbackState,
                                    isFavorite = favoriteLookup,
                                    isDisliked = { id -> viewModel.isDisliked(id) },
                                    onQueryChange = { q -> viewModel.onSearchQueryChange(q) },
                                    onSubmit = { q -> viewModel.submitSearch(q) },
                                    onCancelTyping = { viewModel.cancelSearchTyping() },
                                    onSuggestionFill = { q -> viewModel.onSearchSuggestionFill(q) },
                                    onHistoryClick = { entry -> handleSearchHistoryClick(entry) },
                                    onHistoryRemove = { type, id -> viewModel.removeSearchHistory(type, id) },
                                    onHistoryClear = { viewModel.clearSearchHistory() },
                                    onCategoryChange = { c -> viewModel.onSearchCategoryChange(c) },
                                    onTrackClick = { track ->
                                        viewModel.recordSearchHistory(SearchHistoryStore.fromTrack(track))
                                        val ctx = if (track.provider.equals("YouTube", ignoreCase = true)) mainContextYoutubeMusic else mainContextSearch
                                        viewModel.playTrack(track, ctx)
                                    },
                                    onFavoriteToggle = { track -> viewModel.toggleFavorite(track) },
                                    onAddToQueue = { track -> viewModel.addToQueue(track) },
                                    onPlayNextTrack = { track -> viewModel.addToQueueNext(track) },
                                    onYouTubeArtistClick = { artist ->
                                        viewModel.recordSearchHistory(SearchHistoryStore.fromArtist(artist))
                                        val id = artist.discoveryDetailId()
                                        if (id != null) {
                                            viewModel.navigateToYouTubeArtist(id, artist.name)
                                        } else {
                                            uiScope.launch { snackbarHostState.showSnackbar(mainDetailNotAvailable) }
                                        }
                                    },
                                    onYouTubeAlbumClick = { album ->
                                        viewModel.recordSearchHistory(SearchHistoryStore.fromAlbum(album))
                                        val id = album.discoveryDetailId()
                                        if (id != null) {
                                            viewModel.navigateToYouTubeAlbum(id, album.title)
                                        } else {
                                            uiScope.launch { snackbarHostState.showSnackbar(mainDetailNotAvailable) }
                                        }
                                    },
                                    onYouTubePlaylistClick = { playlist ->
                                        viewModel.recordSearchHistory(SearchHistoryStore.fromPlaylist(playlist))
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
                                    currentTrackId = collectionCurrentTrackId,
                                    isPlaying = playbackState.isPlaying,
                                    isFavorite = favoriteLookup,
                                    onTrackClick = { track ->
                                        val ctx = if (track.provider.equals("Local", ignoreCase = true)) mainContextDownloaded else mainContextFavorites
                                        viewModel.playTrack(track, ctx)
                                    },
                                    onFavoriteToggle = { track -> viewModel.toggleFavorite(track) },
                                    onPlayAllFavorites = { viewModel.playQueue(favorites, 0, mainContextFavorites) },
                                    onShuffleFavorites = {
                                        viewModel.playQueue(favorites.shuffled(), 0, mainContextFavorites)
                                    },
                                    onToggleFavoritesDownload = { viewModel.toggleFavoritesDownload() },
                                    favoritesDownloadProgress = favoritesDownloadProgress,
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
                                            viewModel.recordPlaylistPlay(playlist.id)
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
                                    isOnline = isOnline,
                                    downloadedTracks = offlineTracks,
                                    savedMixes = savedMixes,
                                    onMixClick = { mix -> viewModel.navigateToMix(mix.id, fromLibrary = true) },
                                    onPlayDownloads = { tracks ->
                                        if (tracks.isNotEmpty()) viewModel.playQueue(tracks, 0, mainContextDownloaded)
                                    },
                                    onShuffleDownloads = { tracks ->
                                        if (tracks.isNotEmpty()) viewModel.playQueue(tracks.shuffled(), 0, mainContextDownloaded)
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
                                    liquidGlass = isLiquidGlassEnabled,
                                    onLiquidGlassChange = { enabled -> viewModel.setLiquidGlass(enabled) },
                                    currentAudioQuality = audioQuality,
                                    onAudioQualityChange = { q -> viewModel.setAudioQuality(q) },
                                    downloadQuality = downloadQuality,
                                    onDownloadQualityChange = { q -> viewModel.setDownloadQuality(q) },
                                    downloadWifiOnly = downloadWifiOnly,
                                    onDownloadWifiOnlyChange = { e -> viewModel.setDownloadWifiOnly(e) },
                                    downloadLyrics = downloadLyrics,
                                    onDownloadLyricsChange = { e -> viewModel.setDownloadLyrics(e) },
                                    onOpenOfflineLyrics = { viewModel.navigateToOfflineLyrics() },
                                    downloadsSizeBytes = downloadsSizeBytes,
                                    onClearDownloads = { viewModel.clearDownloads() },
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
                                    lyricsSources = lyricsSources,
                                    lyricsSourceOrder = lyricsSourceOrder,
                                    prioritizeSyllableSync = prioritizeSyllableSync,
                                    paxsenixApiKey = paxsenixApiKey,
                                    onLyricsSourceEnabledChange = { source, enabled ->
                                        viewModel.setLyricsSourceEnabled(source, enabled)
                                    },
                                    onLyricsSourceOrderChange = { order -> viewModel.setLyricsSourceOrder(order) },
                                    onPrioritizeSyllableSyncChange = { enabled ->
                                        viewModel.setPrioritizeSyllableSync(enabled)
                                    },
                                    onPaxsenixApiKeyChange = { key -> viewModel.setPaxsenixApiKey(key) },
                                    onResetLyricsSources = { viewModel.resetLyricsSources() },
                                    onClearTranslationModels = { viewModel.clearTranslationModels() },
                                    appLanguage = appLanguage,
                                    onAppLanguageChange = { tag ->
                                        viewModel.setAppLanguage(tag)
                                        (activityContext as? Activity)?.recreate()
                                    },
                                    homeCountryCode = homeCountry.code,
                                    onHomeCountryChange = { code -> viewModel.setHomeCountry(code) },
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
                            isFavorite = favoriteLookup,
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
                            currentTrackId = collectionCurrentTrackId,
                            isPlaying = playbackState.isPlaying,
                            isFavorite = favoriteLookup,
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
                            onRetry = { viewModel.loadAlbumDetail(dest.albumId) },
                            onAddToQueue = { track -> viewModel.addToQueue(track) },
                                    onPlayNextTrack = { track -> viewModel.addToQueueNext(track) },
                            onAddToPlaylist = { track -> viewModel.showAddToPlaylistDialog(track) },
                            onAddCollectionToQueue = {
                                if (albumTracks.isNotEmpty()) viewModel.addToQueue(albumTracks)
                            },
                            downloadProgress = albumDownloadProgress,
                            onToggleCollectionDownload = {
                                if (albumTracks.isNotEmpty()) viewModel.toggleCollectionDownload(albumTracks)
                            },
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

                    is ScreenDestination.OfflineLyrics -> {
                        OfflineLyricsScreen(
                            downloads = appDownloadedTracks,
                            lyricsStates = offlineLyricsStates,
                            sourceForTrack = { track -> viewModel.lyricsSourceForTrack(track) },
                            onBackClick = { viewModel.navigateBack() },
                            onDownloadLyrics = { track, source ->
                                viewModel.downloadLyricsFor(track, source)
                            },
                            onRemoveLyrics = { track -> viewModel.removeOfflineLyrics(track) },
                            onDownloadMissing = {
                                viewModel.downloadMissingLyrics(appDownloadedTracks)
                            },
                            onLoadStates = { viewModel.loadOfflineLyricsStates() },
                        )
                    }

                    is ScreenDestination.PlaylistDetail -> {
                        val activePlaylist = playlists.firstOrNull { it.id == dest.playlistId }
                        val isAutoPlaylist = activePlaylist?.id == MusicRepository.PLAYLIST_ON_REPEAT_ID
                        val activePlaylistDownloadProgress = remember(activePlaylist?.tracks, downloadStates) {
                            aggregateDownloadProgress(activePlaylist?.tracks.orEmpty(), downloadStates)
                        }
                        PlaylistDetailScreen(
                            playlist = activePlaylist,
                            currentTrackId = collectionCurrentTrackId,
                            isPlaying = playbackState.isPlaying,
                            isFavorite = favoriteLookup,
                            onBackClick = { viewModel.navigateBack() },
                            onTrackClick = { track, index ->
                                if (activePlaylist != null && activePlaylist.tracks.isNotEmpty()) {
                                    viewModel.recordPlaylistPlay(activePlaylist.id)
                                    viewModel.playQueue(activePlaylist.tracks, index, activityContext.getString(R.string.main_context_playlist_fmt, activePlaylist.name))
                                } else {
                                    viewModel.playTrack(track, mainContextPlaylist)
                                }
                            },
                            onPlayPlaylist = {
                                activePlaylist?.let { pl ->
                                    if (pl.tracks.isNotEmpty()) {
                                        viewModel.recordPlaylistPlay(pl.id)
                                        viewModel.playQueue(pl.tracks, 0, activityContext.getString(R.string.main_context_playlist_fmt, pl.name))
                                    }
                                }
                            },
                            onShufflePlaylist = {
                                activePlaylist?.let { pl ->
                                    if (pl.tracks.isNotEmpty()) {
                                        viewModel.recordPlaylistPlay(pl.id)
                                        viewModel.playQueue(pl.tracks.shuffled(), 0, activityContext.getString(R.string.main_context_playlist_fmt, pl.name))
                                    }
                                }
                            },
                            onFavoriteToggle = { track -> viewModel.toggleFavorite(track) },
                            onTogglePlaylistDownload = {
                                activePlaylist?.let { pl -> viewModel.togglePlaylistDownload(pl) }
                            },
                            playlistDownloadProgress = activePlaylistDownloadProgress,
                            onRemoveTrack = { trackId ->
                                activePlaylist?.let { pl ->
                                    viewModel.removeTrackFromPlaylist(pl.id, trackId)
                                }
                            },
                            onDeletePlaylist = if (isAutoPlaylist) null else {
                                {
                                    activePlaylist?.let { pl ->
                                        viewModel.deletePlaylist(pl.id)
                                        viewModel.navigateBack()
                                    }
                                }
                            },
                            onRenamePlaylist = if (isAutoPlaylist) null else { newName, newDesc ->
                                activePlaylist?.let { pl ->
                                    viewModel.updatePlaylist(pl.id, newName, newDesc)
                                }
                            },
                            onUpdatePlaylist = if (isAutoPlaylist) null else { newName, newDesc, newCover ->
                                activePlaylist?.let { pl ->
                                    viewModel.updatePlaylist(pl.id, newName, newDesc, newCover)
                                }
                            },
                            onMoveTrack = if (isAutoPlaylist) null else { from, to ->
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
                            onAddCollectionToQueue = {
                                activePlaylist?.tracks?.takeIf { it.isNotEmpty() }?.let(viewModel::addToQueue)
                            },
                            onDiscoverTracks = if (isAutoPlaylist) null else {
                                {
                                    viewModel.onSearchQueryChange("")
                                    viewModel.selectTab(NavigationTab.SEARCH)
                                    viewModel.requestSearchFocus()
                                }
                            },
                            readOnly = isAutoPlaylist,
                            onPlayNextTrack = { track ->
                                viewModel.addToQueueNext(track)
                            },
                            sourceActions = trackSourceActions
                        )
                    }

                    is ScreenDestination.MixDetail -> {
                        val mix = mixes.firstOrNull { it.id == dest.mixId }
                        val liveTracks = mix?.tracks.orEmpty()
                        val snapshotTracks = mix?.savedTracks.orEmpty()
                        val tracks = if (dest.fromLibrary) {
                            snapshotTracks.ifEmpty { liveTracks }
                        } else {
                            liveTracks
                        }
                        val contextLabel = activityContext.getString(
                            R.string.main_context_mix_fmt,
                            mix?.name.orEmpty()
                        )
                        val mixDownloadProgress = remember(tracks, downloadStates) {
                            aggregateDownloadProgress(tracks, downloadStates)
                        }
                        val mixPlaylist = mix?.let {
                            Playlist(
                                id = it.id,
                                name = it.name,
                                description = null,
                                coverUrl = if (dest.fromLibrary) it.savedCoverUrl else it.coverUrl,
                                tracks = tracks,
                                createdAt = it.updatedAt,
                                updatedAt = it.updatedAt
                            )
                        }
                        PlaylistDetailScreen(
                            playlist = mixPlaylist,
                            currentTrackId = collectionCurrentTrackId,
                            isPlaying = playbackState.isPlaying,
                            isFavorite = favoriteLookup,
                            onBackClick = { viewModel.navigateBack() },
                            onTrackClick = { _, index ->
                                if (tracks.isNotEmpty()) {
                                    viewModel.playQueue(tracks, index, contextLabel)
                                }
                            },
                            onPlayPlaylist = {
                                if (tracks.isNotEmpty()) viewModel.playQueue(tracks, 0, contextLabel)
                            },
                            onShufflePlaylist = {
                                if (tracks.isNotEmpty()) viewModel.playQueue(tracks.shuffled(), 0, contextLabel)
                            },
                            onFavoriteToggle = { track -> viewModel.toggleFavorite(track) },
                            onRemoveTrack = {},
                            onAddToPlaylist = { track -> viewModel.showAddToPlaylistDialog(track) },
                            onAddToQueue = { track -> viewModel.addToQueue(track) },
                            onPlayNextTrack = { track -> viewModel.addToQueueNext(track) },
                            onAddCollectionToQueue = {
                                tracks.takeIf { it.isNotEmpty() }?.let(viewModel::addToQueue)
                            },
                            onTogglePlaylistDownload = {
                                tracks.takeIf { it.isNotEmpty() }?.let(viewModel::toggleCollectionDownload)
                            },
                            playlistDownloadProgress = mixDownloadProgress,
                            readOnly = true,
                            isMix = true,
                            mixSaved = mix?.isSaved == true,
                            mixFromLibrary = dest.fromLibrary,
                            mixSavedAt = mix?.savedAt,
                            onSaveMix = { mix?.let { viewModel.saveMixToLibrary(it.id) } },
                            onRemoveMix = {
                                mix?.let { viewModel.removeMixFromLibrary(it.id) }
                                if (dest.fromLibrary) viewModel.navigateBack()
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
                            currentTrackId = collectionCurrentTrackId,
                            isPlaying = playbackState.isPlaying,
                            isFavorite = favoriteLookup,
                            onBackClick = { viewModel.navigateBack() },
                            onTrackClick = { track, index ->
                                val list = detail?.topTracks.orEmpty()
                                if (list.isNotEmpty()) viewModel.playQueue(list, index, activityContext.getString(R.string.main_context_artist_fmt, artist?.name.orEmpty()))
                                else viewModel.playTrack(track, mainContextYoutubeMusic)
                            },
                            onVideoClick = { video ->
                                viewModel.playTrack(
                                    video,
                                    activityContext.getString(R.string.main_context_artist_fmt, artist?.name.orEmpty())
                                )
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
                            isFavorite = favoriteLookup,
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
                        val ytAlbumTracks = detail?.tracks.orEmpty()
                        val ytAlbumDownloadProgress = remember(ytAlbumTracks, downloadStates) {
                            aggregateDownloadProgress(ytAlbumTracks, downloadStates)
                        }
                        YouTubeDetailScreen(
                            headerTitle = album?.title ?: dest.title.orEmpty(),
                            headerSubtitle = listOfNotNull(
                                album?.type?.takeIf { it.isNotBlank() },
                                album?.artist?.takeIf { it.isNotBlank() },
                                album?.year?.takeIf { it.isNotBlank() },
                                album?.trackCount?.let { activityContext.getString(R.string.main_track_count, it) }
                            ).joinToString(" • ").ifBlank { null },
                            coverUrl = album?.thumbnailUrl,
                            tracks = ytAlbumTracks,
                            isLoading = isLoadingYtDetail,
                            errorMessage = ytDetailError,
                            currentTrackId = collectionCurrentTrackId,
                            isPlaying = playbackState.isPlaying,
                            isFavorite = favoriteLookup,
                            onBackClick = { viewModel.navigateBack() },
                            onTrackClick = { track, index ->
                                if (ytAlbumTracks.isNotEmpty()) viewModel.playQueue(ytAlbumTracks, index, activityContext.getString(R.string.main_context_album_fmt, album?.title.orEmpty()))
                                else viewModel.playTrack(track, mainContextYoutubeMusic)
                            },
                            onFavoriteToggle = { track -> viewModel.toggleFavorite(track) },
                            onPlayAll = { detail?.let { viewModel.playQueue(it.tracks, 0, activityContext.getString(R.string.main_context_album_fmt, album?.title.orEmpty())) } },
                            onShuffle = { detail?.let { viewModel.playQueue(it.tracks.shuffled(), 0, activityContext.getString(R.string.main_context_album_fmt, album?.title.orEmpty())) } },
                            onRetry = { viewModel.loadYouTubeAlbumDetail(dest.browseId) },
                            onAddToQueue = { track -> viewModel.addToQueue(track) },
                                    onPlayNextTrack = { track -> viewModel.addToQueueNext(track) },
                            onAddToPlaylist = { track -> viewModel.showAddToPlaylistDialog(track) },
                            collectionArtist = album?.artist,
                            showTrackArtwork = true,
                            showTrackNumbers = true,
                            downloadProgress = ytAlbumDownloadProgress,
                            onToggleCollectionDownload = {
                                ytAlbumTracks.takeIf { it.isNotEmpty() }?.let(viewModel::toggleCollectionDownload)
                            },
                            onAddCollectionToQueue = {
                                ytAlbumTracks.takeIf { it.isNotEmpty() }?.let(viewModel::addToQueue)
                            },
                            sourceActions = trackSourceActions
                        )
                    }

                    is ScreenDestination.YouTubePlaylistPage -> {
                        val detail = selectedYtPlaylist
                        val playlist = detail?.playlist
                        val ytPlaylistTracks = detail?.tracks.orEmpty()
                        val ytPlaylistDownloadProgress = remember(ytPlaylistTracks, downloadStates) {
                            aggregateDownloadProgress(ytPlaylistTracks, downloadStates)
                        }
                        YouTubeDetailScreen(
                            headerTitle = playlist?.title ?: dest.title.orEmpty(),
                            headerSubtitle = listOfNotNull(
                                playlist?.author?.takeIf { it.isNotBlank() },
                                playlist?.trackCount?.let { activityContext.getString(R.string.main_track_count, it) }
                            ).joinToString(" • ").ifBlank { null },
                            coverUrl = playlist?.thumbnailUrl,
                            tracks = ytPlaylistTracks,
                            isLoading = isLoadingYtDetail,
                            errorMessage = ytDetailError,
                            currentTrackId = collectionCurrentTrackId,
                            isPlaying = playbackState.isPlaying,
                            isFavorite = favoriteLookup,
                            onBackClick = { viewModel.navigateBack() },
                            onTrackClick = { track, index ->
                                if (ytPlaylistTracks.isNotEmpty()) viewModel.playQueue(ytPlaylistTracks, index, activityContext.getString(R.string.main_context_playlist_fmt, playlist?.title.orEmpty()))
                                else viewModel.playTrack(track, mainContextYoutubeMusic)
                            },
                            onFavoriteToggle = { track -> viewModel.toggleFavorite(track) },
                            onPlayAll = { detail?.let { viewModel.playQueue(it.tracks, 0, activityContext.getString(R.string.main_context_playlist_fmt, playlist?.title.orEmpty())) } },
                            onShuffle = { detail?.let { viewModel.playQueue(it.tracks.shuffled(), 0, activityContext.getString(R.string.main_context_playlist_fmt, playlist?.title.orEmpty())) } },
                            onRetry = { viewModel.loadYouTubePlaylistDetail(dest.playlistId) },
                            onAddToQueue = { track -> viewModel.addToQueue(track) },
                                    onPlayNextTrack = { track -> viewModel.addToQueueNext(track) },
                            onAddToPlaylist = { track -> viewModel.showAddToPlaylistDialog(track) },
                            downloadProgress = ytPlaylistDownloadProgress,
                            onToggleCollectionDownload = {
                                ytPlaylistTracks.takeIf { it.isNotEmpty() }?.let(viewModel::toggleCollectionDownload)
                            },
                            onAddCollectionToQueue = {
                                ytPlaylistTracks.takeIf { it.isNotEmpty() }?.let(viewModel::addToQueue)
                            },
                            sourceActions = trackSourceActions,
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
        }

        // The player, mini player, queue and sheets are rendered outside the Scaffold
        // provider above, so expose the download state/actions to them as well.
        CompositionLocalProvider(
            LocalDownloadUi provides downloadUi,
            LocalTrackContextMenu provides trackMenuController,
            LocalAppBackdrop provides appBackdrop,
            LocalLiquidGlassEnabled provides glassActive
        ) {

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
            if (glassActive) {
                LiquidGlassChrome(
                    navItems = navItems,
                    currentTab = currentTab,
                    isTabActive = currentDestination is ScreenDestination.TabContent,
                    onSelect = selectTab,
                    playbackState = playbackState,
                    isFavorite = isCurrentFavorite,
                    isInline = glassScrollState.isInline,
                    onExpandChrome = { glassScrollState.expand() },
                    onPlayPause = { viewModel.togglePlayPause() },
                    onSkipNext = { viewModel.playNext() },
                    onSkipPrevious = { viewModel.playPrevious() },
                    onFavoriteToggle = { track?.let { viewModel.toggleFavorite(it) } },
                    onOpenPlayer = openFullPlayer,
                    onPullStart = beginPlayerPull,
                    onPull = updatePlayerPull,
                    onPullRelease = releasePlayerPull,
                    sharedTransitionScope = sharedScope,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 2.dp)
                )
            } else {
                AnimatedVisibility(
                    visible = track != null && (!isPlayerExpanded || playerDragSession),
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
                            isFavorite = isCurrentFavorite,
                            onPlayPause = { viewModel.togglePlayPause() },
                            onSkipNext = { viewModel.playNext() },
                            onSkipPrevious = { viewModel.playPrevious() },
                            onFavoriteToggle = { viewModel.toggleFavorite(track) },
                            onClick = openFullPlayer,
                            onPullStart = beginPlayerPull,
                            onPull = updatePlayerPull,
                            onPullRelease = releasePlayerPull,
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
                    onSelect = selectTab,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 2.dp)
                )
            }
        }

        // Full player. Tap-open keeps the existing slide/shared-element motion;
        // a finger pull drives the player's translation directly from the drag.
        AnimatedVisibility(
            visible = (isPlayerExpanded || playerDragVisible) && playbackState.currentTrack != null,
            enter = if (playerDragSession) {
                EnterTransition.None
            } else {
                slideInVertically(initialOffsetY = { fullHeight -> fullHeight })
            },
            exit = if (playerCustomExit) {
                ExitTransition.None
            } else {
                slideOutVertically(targetOffsetY = { fullHeight -> fullHeight })
            }
        ) {
            val visibilityScope = this
            val track = playbackState.currentTrack
            if (track != null) {
                PlayerScreenV2(
                    playbackState = playbackState,
                    isFavorite = isCurrentFavorite,
                    isDisliked = isCurrentDisliked,
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
                    downloadProgress = downloadStates[track.id] ?: DownloadProgress(),
                    onToggleDownload = { t -> viewModel.toggleDownload(t) },
                    onAddToPlaylist = { t -> viewModel.showAddToPlaylistDialog(t) },
                    onShareTrack = { t -> trackToShare = t },
                    onToggleLyrics = { viewModel.toggleLyricsOpen() },
                    onOpenEqualizer = { viewModel.openEqualizerDialog() },
                    onOpenParty = { viewModel.openPartySheet() },
                    onOpenAlbum = { albumId ->
                        viewModel.setPlayerExpanded(false)
                        viewModel.navigateToAlbum(albumId)
                    },
                    onRequestTrackMetadata = viewModel::requestTrackMetadata,
                    isPartyActive = partyState.role != PartyRole.IDLE,
                    onArtistClick = playerArtistClick,
                    trackMetadata = trackMetadata[track.id],
                    metadataLoading = !track.provider.equals("Local", ignoreCase = true) &&
                        track.id !in trackMetadata,
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
                    activeLyricsSource = activeLyricsSource,
                    lyricsProviderStates = lyricsProviderStates,
                    onSelectLyricsSource = { source -> viewModel.selectLyricsSource(source) },
                    onAddToQueue = { viewModel.addToQueue(it) },
                    onPlayNextTrack = { viewModel.addToQueueNext(it) },
                    onQueueTrackClick = { index -> viewModel.playFromQueueIndex(index) },
                    onQueueRemove = { index -> viewModel.removeFromQueue(index) },
                    onQueueClear = { viewModel.clearUpcoming() },
                    onQueueMove = { from, to -> viewModel.moveQueueItem(from, to) },
                    autoplayEnabled = isAutoplayEnabled,
                    onToggleAutoplay = { viewModel.toggleAutoplay() },
                    sourceActions = trackSourceActions,
                    sharedTransitionScope = if (playerDragSession || playerSuppressShared) {
                        null
                    } else {
                        sharedScope
                    },
                    animatedVisibilityScope = visibilityScope,
                    modifier = Modifier.graphicsLayer {
                        translationY = if (playerDragSession) {
                            (1f - playerDragProgress) * size.height
                        } else {
                            0f
                        }
                    }
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
                onClearQueue = { viewModel.clearUpcoming() },
                onToggleAutoplay = { viewModel.toggleAutoplay() },
                onMoveQueueItem = { from, to -> viewModel.moveQueueItem(from, to) },
                sourceActions = trackSourceActions
            )
        }

        // Party Bottom Sheet
        if (isPartySheetOpen) {
            PartyBottomSheet(
                isOpen = isPartySheetOpen,
                state = partyState,
                discovered = partyDiscovered,
                permissionsGranted = partyPermissionsGranted,
                onDismissRequest = { viewModel.closePartySheet() },
                onCreate = { viewModel.createParty() },
                onScan = { viewModel.scanForParties() },
                onStopScan = { viewModel.stopPartyScan() },
                onJoin = { endpointId -> viewModel.joinParty(endpointId) },
                onJoinByCode = { code -> viewModel.joinPartyByCode(code) },
                onLeave = { viewModel.leaveParty() },
                onToggleAllowControl = { viewModel.setPartyAllowGuestControl(it) },
                onToggleAllowQueue = { viewModel.setPartyAllowQueueAdd(it) },
                onNudgeChange = { viewModel.setPartyNudge(it) },
                onRequestPermissions = { partyPermissionLauncher.launch(PartyPermissions.required().toTypedArray()) }
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

        HeldTrackMenuHost(request = heldTrackMenu)
        infoTrack?.let { track ->
            TrackInfoSheet(track = track, onDismiss = { infoTrack = null })
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
}
