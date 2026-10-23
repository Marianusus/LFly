package org.sonora.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import org.sonora.android.R
import org.sonora.android.api.SpotifyPlaylistImporter
import org.sonora.android.api.Track
import org.sonora.android.api.YouTubePlaylistImporter
import org.sonora.android.data.FollowedArtist
import org.sonora.android.data.MusicRepository
import org.sonora.android.data.Mix
import org.sonora.android.data.Playlist
import org.sonora.android.playback.DownloadProgress
import org.sonora.android.ui.components.DownloadMenuEntry
import org.sonora.android.ui.components.ImportDialog
import org.sonora.android.ui.components.LocalBottomChromeInset
import org.sonora.android.ui.components.SectionHeader
import org.sonora.android.ui.components.SourceSwitchMenuItems
import org.sonora.android.ui.components.TrackSourceActions
import org.sonora.android.ui.components.mapQueueReorderByKey
import org.sonora.android.ui.components.stableTrackKeys
import org.sonora.android.ui.theme.GlassPlaceholder
import org.sonora.android.ui.collection.CollectionEmptyState
import org.sonora.android.ui.collection.CollectionHero
import org.sonora.android.ui.collection.CollectionMetrics
import org.sonora.android.ui.collection.CollectionNowPlayingThread
import org.sonora.android.ui.collection.CollectionSectionHeader
import org.sonora.android.ui.collection.CollectionTopBar
import org.sonora.android.ui.collection.CollectionTrackRow
import org.sonora.android.ui.collection.formatCollectionDuration
import org.sonora.android.ui.collection.joinCollectionMeta
import org.sonora.android.ui.theme.LocalBackgroundScheme
import org.sonora.android.ui.theme.LocalMotionPreferences
import org.sonora.android.ui.theme.Radius
import org.sonora.android.ui.theme.SonoraAmoledBlack
import org.sonora.android.ui.theme.SonoraBackground
import org.sonora.android.ui.theme.SonoraSurfaceCard
import org.sonora.android.ui.theme.SonoraTheme
import org.sonora.android.ui.theme.Spacing
import org.sonora.android.ui.theme.pressScale
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Favorites reordering operates on local track indices (0-based). The lazy-list header
 * offset is irrelevant here because reorder keys are resolved to track indices by identity.
 */
private const val COLLECTION_TRACKS_BASE_INDEX = 0

/** Fixed, always-present collections pinned to the top of the library. */
private enum class LibraryCollection(val titleRes: Int, val subtitleRes: Int) {
    FAVORITES(R.string.library_favorites_title, R.string.library_favorites_subtitle),
    HISTORY(R.string.library_history_title, R.string.library_history_subtitle),
    DOWNLOADS(R.string.library_downloads_title, R.string.library_downloads_subtitle)
}

/**
 * Spotify-style library: a single list with two pinned collections (Liked Songs, History)
 * that always exist, followed by the user's playlists. The "Artisti seguiti" rail is kept
 * untouched. Collections open a Spotify-style detail page using local (UI-only) navigation,
 * so no backend or ViewModel changes are required.
 */
@Composable
fun LibraryScreen(
    favorites: List<Track>,
    playlists: List<Playlist>,
    history: List<Track>,
    currentTrackId: String?,
    isPlaying: Boolean,
    isFavorite: (String) -> Boolean,
    onTrackClick: (Track) -> Unit,
    onFavoriteToggle: (Track) -> Unit,
    onPlayAllFavorites: () -> Unit,
    onShuffleFavorites: () -> Unit,
    onToggleFavoritesDownload: (() -> Unit)? = null,
    favoritesDownloadProgress: DownloadProgress = DownloadProgress(),
    onCreatePlaylist: (String) -> Unit,
    onAddToQueue: (Track) -> Unit = {},
    onPlayNextTrack: (Track) -> Unit = {},
    onPlaylistClick: (Playlist) -> Unit = {},
    onAddToPlaylist: ((Track) -> Unit)? = null,
    onPlayPlaylist: ((Playlist) -> Unit)? = null,
    spotifyPlaylistImporter: SpotifyPlaylistImporter? = null,
    youTubePlaylistImporter: YouTubePlaylistImporter? = null,
    followedArtists: List<FollowedArtist> = emptyList(),
    onFollowedArtistClick: (FollowedArtist) -> Unit = {},
    onPlayAllHistory: (() -> Unit)? = null,
    onShuffleHistory: (() -> Unit)? = null,
    onReorderFavorites: ((Int, Int) -> Unit)? = null,
    onPlayDownloads: ((List<Track>) -> Unit)? = null,
    onShuffleDownloads: ((List<Track>) -> Unit)? = null,
    sourceActions: TrackSourceActions? = null,
    isOnline: Boolean = true,
    downloadedTracks: List<Track> = emptyList(),
    savedMixes: List<Mix> = emptyList(),
    onMixClick: (Mix) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var showImportDialog by remember { mutableStateOf(false) }
    var openCollection by remember { mutableStateOf<LibraryCollection?>(null) }
    val listState = rememberLazyListState()
    val detailState = rememberLazyListState()
    // The yt-dlp collection is merged into the offline section instead of being a separate row.
    val visiblePlaylists = remember(playlists) {
        playlists.filterNot { it.id == MusicRepository.PLAYLIST_DOWNLOADED_ID }
    }

    BackHandler(enabled = openCollection != null) { openCollection = null }
    LaunchedEffect(openCollection) {
        if (openCollection != null) detailState.scrollToItem(0)
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = if (SonoraTheme.colors.isLiquidGlass) Color.Transparent else SonoraAmoledBlack
    ) {
        val collection = openCollection
        if (collection != null) {
            val tracks = when (collection) {
                LibraryCollection.FAVORITES -> favorites
                LibraryCollection.HISTORY -> history
                LibraryCollection.DOWNLOADS -> downloadedTracks
            }
            CollectionDetail(
                collection = collection,
                tracks = tracks,
                currentTrackId = currentTrackId,
                isPlaying = isPlaying,
                isFavorite = isFavorite,
                listState = detailState,
                onBack = { openCollection = null },
                onTrackClick = onTrackClick,
                onFavoriteToggle = onFavoriteToggle,
                onPlayAll = {
                    when (collection) {
                        LibraryCollection.FAVORITES -> onPlayAllFavorites()
                        LibraryCollection.HISTORY -> onPlayAllHistory?.invoke() ?: tracks.firstOrNull()?.let(onTrackClick)
                        LibraryCollection.DOWNLOADS -> onPlayDownloads?.invoke(tracks)
                            ?: tracks.firstOrNull()?.let(onTrackClick)
                    }
                },
                onShuffle = {
                    when (collection) {
                        LibraryCollection.FAVORITES -> onShuffleFavorites()
                        LibraryCollection.HISTORY -> onShuffleHistory?.invoke() ?: tracks.shuffled().firstOrNull()?.let(onTrackClick)
                        LibraryCollection.DOWNLOADS -> onShuffleDownloads?.invoke(tracks)
                            ?: tracks.shuffled().firstOrNull()?.let(onTrackClick)
                    }
                },
                onAddToQueue = onAddToQueue,
                onPlayNextTrack = onPlayNextTrack,
                onAddToPlaylist = onAddToPlaylist,
                onReorderFavorites = onReorderFavorites,
                onToggleDownload = if (collection == LibraryCollection.FAVORITES) onToggleFavoritesDownload else null,
                downloadProgress = favoritesDownloadProgress,
                sourceActions = sourceActions
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(top = 16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.library_title),
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = LocalBackgroundScheme.current.onBackground
                        )
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = { showImportDialog = true },
                            modifier = Modifier
                                .size(40.dp)
                                .background(LocalBackgroundScheme.current.glassTint, CircleShape)
                                .border(1.dp, LocalBackgroundScheme.current.glassBorder, CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SwapHoriz,
                                contentDescription = stringResource(R.string.library_import_playlist_content_description),
                                tint = LocalBackgroundScheme.current.onBackground,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        IconButton(
                            onClick = { showCreateDialog = true },
                            modifier = Modifier
                                .size(40.dp)
                                .background(LocalBackgroundScheme.current.accent, CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = stringResource(R.string.library_new_playlist_content_description),
                                tint = accentContentColor(),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Followed Artists (local follow, YouTube Music) — unchanged rail
                if (followedArtists.isNotEmpty()) {
                    SectionHeader(title = stringResource(R.string.library_followed_artists_title))
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(followedArtists, key = { it.artistKey }) { artist ->
                            LibraryFollowedArtist(
                                artist = artist,
                                onClick = { onFollowedArtistClick(artist) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    state = listState,
                    contentPadding = PaddingValues(
                        top = Spacing.xs,
                        bottom = LocalBottomChromeInset.current + Spacing.lg
                    ),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    item {
                        LibraryCollectionRow(
                            title = stringResource(LibraryCollection.FAVORITES.titleRes),
                            subtitle = pluralStringResource(R.plurals.collection_track_count, favorites.size, favorites.size),
                            onClick = { openCollection = LibraryCollection.FAVORITES }
                        ) {
                            LikedSongsCover()
                        }
                    }

                    item {
                        LibraryCollectionRow(
                            title = stringResource(LibraryCollection.HISTORY.titleRes),
                            subtitle = pluralStringResource(R.plurals.collection_track_count, history.size, history.size),
                            onClick = { openCollection = LibraryCollection.HISTORY }
                        ) {
                            HistoryCover()
                        }
                    }

                    // Offline-only collection: appears when the device has no validated internet.
                    if (!isOnline) {
                        item {
                            LibraryCollectionRow(
                                title = stringResource(LibraryCollection.DOWNLOADS.titleRes),
                                subtitle = pluralStringResource(R.plurals.collection_track_count, downloadedTracks.size, downloadedTracks.size),
                                onClick = { openCollection = LibraryCollection.DOWNLOADS }
                            ) {
                                DownloadsCover()
                            }
                        }
                    }

                    // Saved mixes appear inline with a small "Mix · N brani" subtitle.
                    items(savedMixes, key = { "saved_mix_${it.id}" }) { mix ->
                        LibraryMixRow(
                            mix = mix,
                            onClick = { onMixClick(mix) }
                        )
                    }

                    if (visiblePlaylists.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = Spacing.xl, start = Spacing.gutter, end = Spacing.gutter),
                                contentAlignment = Alignment.Center
                            ) {
                                GlassPlaceholder {
                                    Icon(
                                        imageVector = Icons.Default.LibraryMusic,
                                        contentDescription = null,
                                        tint = LocalBackgroundScheme.current.onBackgroundTertiary,
                                        modifier = Modifier
                                            .align(Alignment.CenterHorizontally)
                                            .size(40.dp)
                                    )
                                    Spacer(modifier = Modifier.height(Spacing.sm))
                                    Text(
                                        text = stringResource(R.string.library_no_playlists_title),
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = LocalBackgroundScheme.current.onBackground,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(modifier = Modifier.height(Spacing.xs))
                                    Text(
                                        text = stringResource(R.string.library_no_playlists_body),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = LocalBackgroundScheme.current.onBackgroundSecondary,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    } else {
                        items(visiblePlaylists, key = { it.id }) { playlist ->
                            LibraryPlaylistRow(
                                playlist = playlist,
                                onClick = { onPlaylistClick(playlist) }
                            )
                        }
                    }
                }
            }
        }

        // Create Playlist Dialog
        if (showCreateDialog) {
            val scheme = LocalBackgroundScheme.current
            AlertDialog(
                onDismissRequest = {
                    showCreateDialog = false
                    newPlaylistName = ""
                },
                containerColor = SonoraSurfaceCard,
                title = {
                    Text(
                        text = stringResource(R.string.library_new_playlist_dialog_title),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = scheme.onBackground
                        )
                    )
                },
                text = {
                    Column {
                        Text(
                            text = stringResource(R.string.library_new_playlist_dialog_message),
                            color = scheme.onBackgroundSecondary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = newPlaylistName,
                            onValueChange = { newPlaylistName = it },
                            placeholder = { Text(stringResource(R.string.library_new_playlist_placeholder), color = scheme.onBackgroundTertiary) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = scheme.onBackground,
                                unfocusedTextColor = scheme.onBackground,
                                focusedBorderColor = scheme.accent,
                                unfocusedBorderColor = scheme.glassBorder
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (newPlaylistName.isNotBlank()) {
                                onCreatePlaylist(newPlaylistName.trim())
                                newPlaylistName = ""
                                showCreateDialog = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = scheme.accent,
                            contentColor = accentContentColor()
                        )
                    ) {
                        Text(stringResource(R.string.library_create_action), fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showCreateDialog = false
                        newPlaylistName = ""
                    }) {
                        Text(stringResource(R.string.library_cancel_action), color = scheme.onBackgroundSecondary)
                    }
                }
            )
        }

        // Unified Import Dialog (Spotify / YouTube Music)
        if (showImportDialog) {
            val context = LocalContext.current
            val repository = MusicRepository.getInstance(context)
            ImportDialog(
                onDismissRequest = { showImportDialog = false },
                spotifyImporter = spotifyPlaylistImporter ?: remember(context) {
                    SpotifyPlaylistImporter(repository = repository)
                },
                youTubeImporter = youTubePlaylistImporter ?: remember(context) {
                    YouTubePlaylistImporter(repository = repository)
                },
                repository = repository,
                onPlayPlaylist = { playlist ->
                    if (onPlayPlaylist != null) {
                        onPlayPlaylist(playlist)
                    } else {
                        onPlaylistClick(playlist)
                    }
                }
            )
        }
    }
}

/** Content color that stays readable on top of the dynamic accent. */
@Composable
private fun accentContentColor(): Color {
    val scheme = LocalBackgroundScheme.current
    return if (scheme.isLight) Color.White else Color.Black
}

// ==========================================
// Spotify-style list rows
// ==========================================

@Composable
private fun LibraryCollectionRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    cover: @Composable () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(6.dp))
        ) {
            cover()
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                ),
                color = LocalBackgroundScheme.current.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                color = LocalBackgroundScheme.current.onBackgroundSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LibraryMixRow(
    mix: Mix,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val snapshotPlaylist = remember(mix.id, mix.savedTracks) {
        Playlist(
            id = mix.id,
            name = mix.name,
            tracks = mix.savedTracks.orEmpty()
        )
    }
    val scheme = LocalBackgroundScheme.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(scheme.glassTint),
            contentAlignment = Alignment.Center
        ) {
            PlaylistCoverMosaic(
                playlist = snapshotPlaylist,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = mix.name,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                ),
                color = scheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = pluralStringResource(
                    R.plurals.library_mix_track_count,
                    mix.savedTrackCount,
                    mix.savedTrackCount
                ),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                color = scheme.onBackgroundSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LibraryPlaylistRow(
    playlist: Playlist,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(LocalBackgroundScheme.current.glassTint),
            contentAlignment = Alignment.Center
        ) {
            PlaylistCoverMosaic(
                playlist = playlist,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                ),
                color = LocalBackgroundScheme.current.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = pluralStringResource(R.plurals.library_playlist_track_count, playlist.trackCount, playlist.trackCount),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                color = LocalBackgroundScheme.current.onBackgroundSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ==========================================
// Collection covers
// ==========================================

@Composable
private fun LikedSongsCover(modifier: Modifier = Modifier, iconSize: Int = 26) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF4527A0), Color(0xFF2962FF))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Favorite,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(iconSize.dp)
        )
    }
}

@Composable
private fun DownloadsCover(modifier: Modifier = Modifier, iconSize: Int = 26) {
    val scheme = LocalBackgroundScheme.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(scheme.accent.copy(alpha = 0.55f), scheme.glassTint)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.DownloadDone,
            contentDescription = null,
            tint = scheme.onBackground,
            modifier = Modifier.size(iconSize.dp)
        )
    }
}

@Composable
private fun HistoryCover(modifier: Modifier = Modifier, iconSize: Int = 26) {
    val scheme = LocalBackgroundScheme.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(scheme.glassTint, scheme.accent.copy(alpha = 0.45f))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.History,
            contentDescription = null,
            tint = scheme.onBackground,
            modifier = Modifier.size(iconSize.dp)
        )
    }
}

// ==========================================
// Shared collection detail (favorites / history / downloads)
// ==========================================

@Composable
private fun CollectionDetail(
    collection: LibraryCollection,
    tracks: List<Track>,
    currentTrackId: String?,
    isPlaying: Boolean,
    isFavorite: (String) -> Boolean,
    listState: LazyListState,
    onBack: () -> Unit,
    onTrackClick: (Track) -> Unit,
    onFavoriteToggle: (Track) -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onAddToQueue: (Track) -> Unit,
    onPlayNextTrack: (Track) -> Unit = {},
    onAddToPlaylist: ((Track) -> Unit)?,
    onReorderFavorites: ((Int, Int) -> Unit)? = null,
    onToggleDownload: (() -> Unit)? = null,
    downloadProgress: DownloadProgress = DownloadProgress(),
    sourceActions: TrackSourceActions? = null,
) {
    val scheme = LocalBackgroundScheme.current
    val motion = LocalMotionPreferences.current
    val scope = rememberCoroutineScope()
    val coverUrl = tracks.firstOrNull { !it.coverUrl.isNullOrBlank() }?.coverUrl
    val canReorder = collection == LibraryCollection.FAVORITES &&
        onReorderFavorites != null && tracks.size > 1
    var isReorderMode by remember { mutableStateOf(false) }

    LaunchedEffect(collection) { isReorderMode = false }
    LaunchedEffect(canReorder) { if (!canReorder) isReorderMode = false }

    val trackKeys = remember(tracks) { stableTrackKeys(tracks) }
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val mapped = mapQueueReorderByKey(trackKeys, from.key, to.key, COLLECTION_TRACKS_BASE_INDEX)
        if (mapped != null) onReorderFavorites?.invoke(mapped.first, mapped.second)
    }
    val collapsed by remember(listState) {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 120
        }
    }
    val currentIndex = remember(tracks, currentTrackId) {
        tracks.indexOfFirst { it.id == currentTrackId }
    }
    val hasCurrent = currentIndex >= 0
    val firstTrackItem = 2 + if (hasCurrent) 1 else 0

    SonoraBackground(
        modifier = Modifier.fillMaxSize(),
        coverUrl = coverUrl,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            CollectionTopBar(
                title = stringResource(collection.titleRes),
                collapsed = collapsed || isReorderMode,
                onBack = onBack,
            ) {
                if (canReorder) {
                    IconButton(
                        onClick = { isReorderMode = !isReorderMode },
                        modifier = Modifier.size(CollectionMetrics.ActionSize),
                    ) {
                        Icon(
                            imageVector = if (isReorderMode) Icons.Default.Check else Icons.Default.SwapVert,
                            contentDescription = stringResource(
                                if (isReorderMode) R.string.library_reorder_finish_content_description
                                else R.string.library_reorder_content_description
                            ),
                            tint = scheme.onBackground,
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                state = listState,
                contentPadding = PaddingValues(bottom = LocalBottomChromeInset.current + Spacing.xl),
            ) {
                item(key = "hero", contentType = "hero") {
                    CollectionHero(
                        title = stringResource(collection.titleRes),
                        subtitle = stringResource(collection.subtitleRes),
                        metadata = collectionMetadata(tracks),
                        hasTracks = tracks.isNotEmpty(),
                        onPlay = onPlayAll,
                        onShuffle = onShuffle,
                        downloadProgress = if (collection == LibraryCollection.FAVORITES) downloadProgress else null,
                        onToggleDownload = onToggleDownload,
                        modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
                        artwork = {
                            when (collection) {
                                LibraryCollection.FAVORITES -> LikedSongsCover(iconSize = 84)
                                LibraryCollection.HISTORY -> HistoryCover(iconSize = 84)
                                LibraryCollection.DOWNLOADS -> DownloadsCover(iconSize = 84)
                            }
                        },
                    )
                }

                if (tracks.isEmpty()) {
                    item(key = "empty", contentType = "empty") {
                        CollectionEmptyState(
                            icon = when (collection) {
                                LibraryCollection.FAVORITES -> Icons.Default.Favorite
                                LibraryCollection.HISTORY -> Icons.Default.History
                                LibraryCollection.DOWNLOADS -> Icons.Default.DownloadDone
                            },
                            title = stringResource(
                                when (collection) {
                                    LibraryCollection.FAVORITES -> R.string.library_no_favorites_title
                                    LibraryCollection.HISTORY -> R.string.library_no_history_title
                                    LibraryCollection.DOWNLOADS -> R.string.library_no_downloads_title
                                }
                            ),
                            message = stringResource(
                                when (collection) {
                                    LibraryCollection.FAVORITES -> R.string.library_no_favorites_body
                                    LibraryCollection.HISTORY -> R.string.library_no_history_body
                                    LibraryCollection.DOWNLOADS -> R.string.library_no_downloads_body
                                }
                            ),
                        )
                    }
                } else {
                    item(key = "tracklist-header", contentType = "header") {
                        CollectionSectionHeader(
                            title = stringResource(R.string.library_tracks_count, tracks.size),
                            detail = formatCollectionDuration(tracks.sumOf { it.durationMs }),
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
                        key = { index, _ -> trackKeys.getOrElse(index) { "track_$index" } },
                        contentType = { _, _ -> "track" },
                    ) { index, track ->
                        val rowKey = trackKeys.getOrElse(index) { "track_$index" }
                        val reorderEnabled = isReorderMode && canReorder
                        val isCurrent = track.id == currentTrackId

                        ReorderableItem(
                            state = reorderState,
                            key = rowKey,
                            enabled = reorderEnabled,
                        ) { isDragging ->
                            CollectionTrackRow(
                                track = track,
                                trackNumber = null,
                                showArtwork = true,
                                showArtist = true,
                                isCurrent = isCurrent,
                                isPlaying = isCurrent && isPlaying,
                                isFavorite = if (collection == LibraryCollection.FAVORITES) true else isFavorite(track.id),
                                onPlay = {
                                    if (!isReorderMode) onTrackClick(track)
                                },
                                onFavoriteClick = { onFavoriteToggle(track) },
                                fallbackCoverUrl = coverUrl,
                                reorderMode = reorderEnabled,
                                isDragging = isDragging,
                                dragHandleModifier = if (reorderEnabled) {
                                    Modifier.longPressDraggableHandle()
                                } else {
                                    Modifier
                                },
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
                                    if (onAddToPlaylist != null) {
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
                                    }
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
    }
}

@Composable
private fun collectionMetadata(tracks: List<Track>): String? = joinCollectionMeta(
    pluralStringResource(R.plurals.collection_track_count, tracks.size, tracks.size),
    formatCollectionDuration(tracks.sumOf { it.durationMs }),
)

@Composable
private fun LibraryFollowedArtist(
    artist: FollowedArtist,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val scheme = LocalBackgroundScheme.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(78.dp)
            .pressScale(interaction)
            .clip(RoundedCornerShape(16.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(scheme.glassTint),
            contentAlignment = Alignment.Center
        ) {
            if (!artist.coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = artist.coverUrl,
                    contentDescription = artist.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = scheme.onBackgroundSecondary,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = artist.name,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                color = scheme.onBackground
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}
