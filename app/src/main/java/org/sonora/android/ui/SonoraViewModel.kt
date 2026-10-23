package org.sonora.android.ui

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.sonora.android.AppLanguage
import org.sonora.android.R
import org.sonora.android.api.Album
import org.sonora.android.api.ApiHealthManager
import org.sonora.android.api.ArtistCredit
import org.sonora.android.api.ArtistCredits
import org.sonora.android.api.HighlightMode
import org.sonora.android.api.lyrics.LyricsBridge
import org.sonora.android.api.lyrics.LyricsRepository
import org.sonora.android.api.lyrics.sources.LyricsProviderState
import org.sonora.android.api.lyrics.sources.LyricsSource
import org.sonora.android.api.Artist
import org.sonora.android.api.AudioQuality
import org.sonora.android.api.DeezerClient
import org.sonora.android.api.InvidiousStreamResolver
import org.sonora.android.api.YouTubeMusicClient
import org.sonora.android.api.YouTubePlaylistImporter
import org.sonora.android.api.JioSaavnFallbackClient
import org.sonora.android.api.SpotifyPlaylistImporter
import org.sonora.android.api.LrclibClient
import org.sonora.android.api.Lyrics
import org.sonora.android.api.CountryTopTrack
import org.sonora.android.api.EuropeChartsClient
import org.sonora.android.api.EuropeanCountry
import org.sonora.android.api.ProviderHealth
import org.sonora.android.api.Track
import org.sonora.android.api.TrackMetadata
import org.sonora.android.api.TrackSourcePreferences
import org.sonora.android.api.PlaybackSource
import org.sonora.android.api.TranslationManager
import org.sonora.android.api.YouTubeAlbum
import org.sonora.android.api.YouTubeAlbumDetail
import org.sonora.android.api.YouTubeArtist
import org.sonora.android.api.YouTubeArtistDetail
import org.sonora.android.api.YouTubePlaylist
import org.sonora.android.api.YouTubePlaylistDetail
import org.sonora.android.api.YouTubeSongsPage
import org.sonora.android.ui.theme.AppearanceMode
import java.util.Locale
import org.sonora.android.ui.theme.ThemeStyle
import org.sonora.android.data.EuropeChartsCache
import org.sonora.android.data.FollowedArtist
import org.sonora.android.data.Mix
import org.sonora.android.data.MusicRepository
import org.sonora.android.data.NetworkMonitor
import org.sonora.android.data.Playlist
import org.sonora.android.data.RankedPlaylist
import org.sonora.android.data.RankedTrack
import org.sonora.android.data.SearchHistoryEntry
import org.sonora.android.data.SearchHistoryStore
import org.sonora.android.data.SearchHistoryType
import org.sonora.android.data.normalizedMatchKey
import org.sonora.android.data.rankPlaylists
import org.sonora.android.data.rankTopTracks
import org.sonora.android.playback.AudioCacheManager
import org.sonora.android.playback.DownloadLyricsStore
import org.sonora.android.playback.DownloadManager
import org.sonora.android.playback.OfflineLyrics
import org.sonora.android.playback.OfflineLyricsInfo
import org.sonora.android.playback.OfflineLyricsStatus
import org.sonora.android.playback.DownloadProgress
import org.sonora.android.playback.DownloadStatus
import org.sonora.android.playback.EqualizerManager
import org.sonora.android.playback.EqualizerPreset
import org.sonora.android.playback.EqualizerState
import org.sonora.android.playback.PlaybackManager
import org.sonora.android.party.DiscoveredParty
import org.sonora.android.party.PartyManager
import org.sonora.android.party.PartyRole
import org.sonora.android.party.PartyState
import org.sonora.android.playback.RecommendationEngine
import org.sonora.android.playback.PlaybackState
import org.sonora.android.playback.TasteProfile

private const val RECOMMENDATION_REFRESH_DEBOUNCE_MS = 600L

sealed class ScreenDestination {
    object TabContent : ScreenDestination()
    data class ArtistDetail(val artistId: String, val artistName: String? = null) : ScreenDestination()
    data class AlbumDetail(val albumId: String, val albumTitle: String? = null) : ScreenDestination()
    data class PlaylistDetail(val playlistId: String) : ScreenDestination()
    data class MixDetail(val mixId: String, val fromLibrary: Boolean = false) : ScreenDestination()
    data class YouTubeArtistPage(val browseId: String, val name: String? = null) : ScreenDestination()
    data class YouTubeAlbumPage(val browseId: String, val title: String? = null) : ScreenDestination()
    data class YouTubePlaylistPage(val playlistId: String, val title: String? = null) : ScreenDestination()
    data class YouTubeArtistSongsPage(
        val browseId: String,
        val params: String?,
        val title: String? = null
    ) : ScreenDestination()
    object Diagnostics : ScreenDestination()
    object OfflineLyrics : ScreenDestination()
}

enum class NavigationTab {
    HOME,
    SEARCH,
    LIBRARY,
    SETTINGS,

    /** @deprecated retained for backward compatibility; Health is now a pushed screen under Settings. */
    DIAGNOSTICS
}

/**
 * Builds the single search list: all YouTube results first (in received order), then
 * Deezer results only as enrichment. A Deezer entry is dropped when it shares the
 * normalized title+artist of an already-present track, so a yt_ result is never removed.
 */
internal fun mergeYouTubeFirst(ytTracks: List<Track>, deezerTracks: List<Track>): List<Track> {
    val result = ArrayList<Track>(ytTracks.size + deezerTracks.size)
    val seen = HashSet<String>(ytTracks.size + deezerTracks.size)
    for (track in ytTracks) {
        result.add(track)
        seen.add(normalizedMatchKey(track.title, track.artist))
    }
    for (track in deezerTracks) {
        val key = normalizedMatchKey(track.title, track.artist)
        if (key != "|" && !seen.add(key)) continue
        result.add(track)
    }
    return result
}

/**
 * Orders search hits by popularity ("3.6B plays" / "20M views"), most streamed first. The sort is
 * stable, so ties keep the source order (YouTube's own ranking) and entries without a metric
 * (Deezer enrichment) fall to the bottom.
 */
internal fun sortByStreams(tracks: List<Track>): List<Track> =
    tracks.sortedByDescending { it.streams ?: -1L }

/** Maps a YouTube discovery artist to the locally persisted follow entity. */
internal fun YouTubeArtist.toFollowedArtist(nowMs: Long = 0L): FollowedArtist = FollowedArtist(
    artistKey = artistKey,
    browseId = browseId,
    channelId = channelId,
    name = name,
    coverUrl = thumbnailUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") },
    provider = "YouTube",
    followedAtEpochMs = nowMs
)

/** Tag for lyrics provider diagnostics (which source/timing each track resolved to). */
private const val LYRICS_TAG = "LyricsResolve"

@OptIn(kotlinx.coroutines.FlowPreview::class)
class SonoraViewModel @JvmOverloads constructor(
    application: Application,
    playbackMgr: PlaybackManager? = null,
    musicRepo: MusicRepository? = null,
    healthMgr: ApiHealthManager? = null,
    deezer: DeezerClient? = null,
    lrclib: LrclibClient? = null,
    eqMgr: EqualizerManager? = null
) : AndroidViewModel(application) {

    val playbackManager: PlaybackManager = playbackMgr ?: PlaybackManager.getInstance(application)
    val musicRepository: MusicRepository = musicRepo ?: MusicRepository.getInstance(application)
    val apiHealthManager: ApiHealthManager = healthMgr ?: ApiHealthManager.getInstance()
    val deezerClient: DeezerClient = deezer ?: DeezerClient()
    val lrclibClient: LrclibClient = lrclib ?: LrclibClient(cacheDir = application.cacheDir)
    val invidiousStreamResolver: InvidiousStreamResolver = InvidiousStreamResolver()
    val youTubeMusicClient: YouTubeMusicClient = YouTubeMusicClient(invidiousStreamResolver)
    val recommendationEngine: RecommendationEngine = playbackManager.recommendationEngine
    val equalizerManager: EqualizerManager = eqMgr ?: playbackManager.equalizerManager
    val partyManager: PartyManager = PartyManager.getInstance(application)
    val spotifyPlaylistImporter: SpotifyPlaylistImporter by lazy {
        SpotifyPlaylistImporter(
            deezerClient = deezerClient,
            saavnClient = JioSaavnFallbackClient(),
            repository = musicRepository
        )
    }
    val youTubePlaylistImporter: YouTubePlaylistImporter by lazy {
        YouTubePlaylistImporter(client = youTubeMusicClient, repository = musicRepository)
    }

    // Navigation Tab
    private val _currentTab = MutableStateFlow(NavigationTab.HOME)
    val currentTab: StateFlow<NavigationTab> = _currentTab.asStateFlow()

    // Playback and Repository state streams
    val playbackState: StateFlow<PlaybackState> = playbackManager.playbackState
    val partyState: StateFlow<PartyState> = partyManager.state
    val partyDiscovered: StateFlow<List<DiscoveredParty>> = partyManager.discovered
    val favorites: StateFlow<List<Track>> = musicRepository.favorites
    val history: StateFlow<List<Track>> = musicRepository.history
    val playlists: StateFlow<List<Playlist>> = musicRepository.playlists
    val localTracks: StateFlow<List<Track>> = musicRepository.localTracks

    /** Deduped top tracks for the Home shelf, all-time plays with recency tie-break. */
    val homeTopTracks: StateFlow<List<RankedTrack>> = combine(
        musicRepository.playStats,
        musicRepository.dislikedTrackIds
    ) { stats, disliked ->
        rankTopTracks(stats = stats, dislikedTrackIds = disliked, limit = 24)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Playlists ranked by explicit starts plus track affinity (excludes system playlists). */
    val homeTopPlaylists: StateFlow<List<RankedPlaylist>> = combine(
        musicRepository.playlists,
        musicRepository.playlistPlayStats,
        musicRepository.playStats
    ) { allPlaylists, playlistStats, trackStats ->
        rankPlaylists(
            playlists = allPlaylists,
            playlistStats = playlistStats,
            trackStats = trackStats,
            excludedIds = setOf(
                MusicRepository.PLAYLIST_DOWNLOADED_ID,
                MusicRepository.PLAYLIST_ON_REPEAT_ID
            ),
            limit = 8
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Generated mixes shown in Home. */
    val mixes: StateFlow<List<Mix>> = musicRepository.mixes

    /** Only the static snapshots explicitly saved by the user (Library section). */
    val savedMixes: StateFlow<List<Mix>> = musicRepository.mixes
        .map { list -> list.filter { it.isSaved } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val healthState: StateFlow<List<ProviderHealth>> = apiHealthManager.healthState
    val themeStyle: StateFlow<ThemeStyle> = musicRepository.themeStyle
    val appearanceMode: StateFlow<AppearanceMode> = musicRepository.appearanceMode
    val accentColor: StateFlow<Int> = musicRepository.accentColor
    val reduceMotion: StateFlow<Boolean> = musicRepository.reduceMotion
    val liquidGlass: StateFlow<Boolean> = musicRepository.liquidGlass
    val translateLyrics: StateFlow<Boolean> = musicRepository.translateLyrics
    val translationTargetLang: StateFlow<String> = musicRepository.translationTargetLang
    val lyricsHighlightMode: StateFlow<HighlightMode> = musicRepository.lyricsHighlightMode
    val lyricsProviderMusixmatch: StateFlow<Boolean> = musicRepository.lyricsProviderMusixmatch
    val lyricsProviderLrclib: StateFlow<Boolean> = musicRepository.lyricsProviderLrclib
    val lyricsSources: StateFlow<Set<LyricsSource>> = musicRepository.lyricsSources
    val lyricsSourceOrder: StateFlow<List<LyricsSource>> = musicRepository.lyricsSourceOrder
    val prioritizeSyllableSync: StateFlow<Boolean> = musicRepository.prioritizeSyllableSync
    val paxsenixApiKey: StateFlow<String> = musicRepository.paxsenixApiKey

    fun setLyricsHighlightMode(mode: HighlightMode) {
        musicRepository.setLyricsHighlightMode(mode)
    }

    private val translationManager: TranslationManager = TranslationManager(application.cacheDir)

    // Home / Charts & Recommendations state
    private val _charts = MutableStateFlow<List<Track>>(emptyList())
    val charts: StateFlow<List<Track>> = _charts.asStateFlow()

    private val _isLoadingCharts = MutableStateFlow(false)
    val isLoadingCharts: StateFlow<Boolean> = _isLoadingCharts.asStateFlow()

    private val _recommendedTracks = MutableStateFlow<List<Track>>(emptyList())
    val recommendedTracks: StateFlow<List<Track>> = _recommendedTracks.asStateFlow()

    private val _isLoadingRecommendations = MutableStateFlow(false)
    val isLoadingRecommendations: StateFlow<Boolean> = _isLoadingRecommendations.asStateFlow()

    // Home "Top tracks (EU)": the most-played song of each European country.
    private val _countryTopTracks = MutableStateFlow<List<CountryTopTrack>>(emptyList())
    val countryTopTracks: StateFlow<List<CountryTopTrack>> = _countryTopTracks.asStateFlow()

    private val _isLoadingCountryTopTracks = MutableStateFlow(false)
    val isLoadingCountryTopTracks: StateFlow<Boolean> = _isLoadingCountryTopTracks.asStateFlow()

    // Trending rail for the country chosen in Settings (defaults to the device country).
    private val _homeCountry = MutableStateFlow(resolveHomeCountry())
    val homeCountry: StateFlow<EuropeanCountry> = _homeCountry.asStateFlow()

    private val _countryTrendingTracks = MutableStateFlow<List<Track>>(emptyList())
    val countryTrendingTracks: StateFlow<List<Track>> = _countryTrendingTracks.asStateFlow()

    private val _isLoadingCountryTrending = MutableStateFlow(false)
    val isLoadingCountryTrending: StateFlow<Boolean> = _isLoadingCountryTrending.asStateFlow()

    private var countryTrendingJob: Job? = null

    private fun resolveHomeCountry(): EuropeanCountry {
        val prefs = getApplication<Application>()
            .getSharedPreferences("sonora_settings", Context.MODE_PRIVATE)
        val saved = prefs.getString("home_country", null)
        val device = Locale.getDefault().country.lowercase(Locale.ROOT)
        val code = saved ?: device
        return EuropeChartsClient.COUNTRIES.firstOrNull { it.code == code }
            ?: EuropeChartsClient.COUNTRIES.first { it.code == "it" }
    }

    private var europeTopJob: Job? = null

    // Disliked state
    val dislikedTrackIds: StateFlow<Set<String>> = musicRepository.dislikedTrackIds
    val dislikedArtists: StateFlow<Set<String>> = musicRepository.dislikedArtists

    // Search state
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /** Last query actually committed; Back restores this while editing. */
    private var committedSearchQuery = ""

    private val _searchCategory = MutableStateFlow(SearchCategory.ALL)
    val searchCategory: StateFlow<SearchCategory> = _searchCategory.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Track>>(emptyList())
    val searchResults: StateFlow<List<Track>> = _searchResults.asStateFlow()

    private val _searchArtistResults = MutableStateFlow<List<Artist>>(emptyList())
    val searchArtistResults: StateFlow<List<Artist>> = _searchArtistResults.asStateFlow()

    private val _searchAlbumResults = MutableStateFlow<List<Album>>(emptyList())
    val searchAlbumResults: StateFlow<List<Album>> = _searchAlbumResults.asStateFlow()

    // YouTube Music discovery results (single catalog source for artists/albums/playlists)
    private val _searchYtArtists = MutableStateFlow<List<YouTubeArtist>>(emptyList())
    val searchYtArtists: StateFlow<List<YouTubeArtist>> = _searchYtArtists.asStateFlow()

    private val _searchYtAlbums = MutableStateFlow<List<YouTubeAlbum>>(emptyList())
    val searchYtAlbums: StateFlow<List<YouTubeAlbum>> = _searchYtAlbums.asStateFlow()

    private val _searchYtPlaylists = MutableStateFlow<List<YouTubePlaylist>>(emptyList())
    val searchYtPlaylists: StateFlow<List<YouTubePlaylist>> = _searchYtPlaylists.asStateFlow()

    private val _searchSuggestions = MutableStateFlow<List<String>>(emptyList())
    val searchSuggestions: StateFlow<List<String>> = _searchSuggestions.asStateFlow()

    /** True while the field is being edited and suggestions should replace results. */
    private val _isSearchTyping = MutableStateFlow(false)
    val isSearchTyping: StateFlow<Boolean> = _isSearchTyping.asStateFlow()

    val searchHistory: StateFlow<List<SearchHistoryEntry>> = SearchHistoryStore.recent

    // Locally followed YouTube artists
    val followedArtists: StateFlow<List<FollowedArtist>> = musicRepository.followedArtists

    // Lazy YouTube discovery detail state (only one is shown at a time)
    private val _selectedYtArtist = MutableStateFlow<YouTubeArtistDetail?>(null)
    val selectedYtArtist: StateFlow<YouTubeArtistDetail?> = _selectedYtArtist.asStateFlow()

    private val _selectedYtAlbum = MutableStateFlow<YouTubeAlbumDetail?>(null)
    val selectedYtAlbum: StateFlow<YouTubeAlbumDetail?> = _selectedYtAlbum.asStateFlow()

    private val _selectedYtPlaylist = MutableStateFlow<YouTubePlaylistDetail?>(null)
    val selectedYtPlaylist: StateFlow<YouTubePlaylistDetail?> = _selectedYtPlaylist.asStateFlow()

    private var ytDetailRequest = 0

    // Full artist song list ("Cele mai populare melodii") with lazy continuation loading
    private val _selectedYtSongs = MutableStateFlow<YouTubeSongsPage?>(null)
    val selectedYtSongs: StateFlow<YouTubeSongsPage?> = _selectedYtSongs.asStateFlow()

    private val _isLoadingYtSongs = MutableStateFlow(false)
    val isLoadingYtSongs: StateFlow<Boolean> = _isLoadingYtSongs.asStateFlow()

    private val _ytSongsError = MutableStateFlow<String?>(null)
    val ytSongsError: StateFlow<String?> = _ytSongsError.asStateFlow()

    private var ytSongsBrowseId: String? = null
    private var ytSongsParams: String? = null

    private val _isLoadingYtDetail = MutableStateFlow(false)
    val isLoadingYtDetail: StateFlow<Boolean> = _isLoadingYtDetail.asStateFlow()

    private val _ytDetailError = MutableStateFlow<String?>(null)
    val ytDetailError: StateFlow<String?> = _ytDetailError.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    // Full Player Modal & Lyrics state
    private val _isPlayerExpanded = MutableStateFlow(false)
    val isPlayerExpanded: StateFlow<Boolean> = _isPlayerExpanded.asStateFlow()

    private val _isLyricsOpen = MutableStateFlow(false)
    val isLyricsOpen: StateFlow<Boolean> = _isLyricsOpen.asStateFlow()

    private val _lyrics = MutableStateFlow<Lyrics?>(null)
    val lyrics: StateFlow<Lyrics?> = _lyrics.asStateFlow()

    private val _isLoadingLyrics = MutableStateFlow(false)
    val isLoadingLyrics: StateFlow<Boolean> = _isLoadingLyrics.asStateFlow()

    private val _activeLyricsSource = MutableStateFlow<LyricsSource?>(null)
    val activeLyricsSource: StateFlow<LyricsSource?> = _activeLyricsSource.asStateFlow()

    private val _lyricsProviderStates =
        MutableStateFlow<Map<LyricsSource, LyricsProviderState>>(emptyMap())
    val lyricsProviderStates: StateFlow<Map<LyricsSource, LyricsProviderState>> =
        _lyricsProviderStates.asStateFlow()

    // Extra provider metadata for the current track, loaded lazily and kept by id.
    private val _trackMetadata = MutableStateFlow<Map<String, TrackMetadata>>(emptyMap())
    val trackMetadata: StateFlow<Map<String, TrackMetadata>> = _trackMetadata.asStateFlow()
    private val trackMetadataJobs = mutableMapOf<String, Job>()

    // Health diagnostics refresh state
    private val _isRefreshingHealth = MutableStateFlow(false)
    val isRefreshingHealth: StateFlow<Boolean> = _isRefreshingHealth.asStateFlow()

    // Queue Sheet state
    private val _isQueueSheetOpen = MutableStateFlow(false)
    val isQueueSheetOpen: StateFlow<Boolean> = _isQueueSheetOpen.asStateFlow()

    // Party Sheet state
    private val _isPartySheetOpen = MutableStateFlow(false)
    val isPartySheetOpen: StateFlow<Boolean> = _isPartySheetOpen.asStateFlow()

    // Search focus requests (tapping the Search tab again focuses the field + opens the keyboard)
    private val _searchFocusRequests = MutableStateFlow(0)
    val searchFocusRequests: StateFlow<Int> = _searchFocusRequests.asStateFlow()

    // Equalizer Modal State
    val equalizerState: StateFlow<EqualizerState> = equalizerManager.state

    private val _isEqualizerDialogOpen = MutableStateFlow(false)
    val isEqualizerDialogOpen: StateFlow<Boolean> = _isEqualizerDialogOpen.asStateFlow()

    // Screen Navigation Destination Stack
    private val navStack = mutableListOf<ScreenDestination>(ScreenDestination.TabContent)
    private val _currentDestination = MutableStateFlow<ScreenDestination>(ScreenDestination.TabContent)
    val currentDestination: StateFlow<ScreenDestination> = _currentDestination.asStateFlow()

    // Artist Detail State
    private val _selectedArtist = MutableStateFlow<Artist?>(null)
    val selectedArtist: StateFlow<Artist?> = _selectedArtist.asStateFlow()

    private val _artistTopTracks = MutableStateFlow<List<Track>>(emptyList())
    val artistTopTracks: StateFlow<List<Track>> = _artistTopTracks.asStateFlow()

    private val _artistAlbums = MutableStateFlow<List<Album>>(emptyList())
    val artistAlbums: StateFlow<List<Album>> = _artistAlbums.asStateFlow()

    private val _isLoadingArtistDetail = MutableStateFlow(false)
    val isLoadingArtistDetail: StateFlow<Boolean> = _isLoadingArtistDetail.asStateFlow()

    private val _artistDetailError = MutableStateFlow<String?>(null)
    val artistDetailError: StateFlow<String?> = _artistDetailError.asStateFlow()

    // Album Detail State
    private val _selectedAlbum = MutableStateFlow<Album?>(null)
    val selectedAlbum: StateFlow<Album?> = _selectedAlbum.asStateFlow()

    private val _albumTracks = MutableStateFlow<List<Track>>(emptyList())
    val albumTracks: StateFlow<List<Track>> = _albumTracks.asStateFlow()

    private val _isLoadingAlbumDetail = MutableStateFlow(false)
    val isLoadingAlbumDetail: StateFlow<Boolean> = _isLoadingAlbumDetail.asStateFlow()

    private val _albumDetailError = MutableStateFlow<String?>(null)
    val albumDetailError: StateFlow<String?> = _albumDetailError.asStateFlow()

    private var albumDetailRequest = 0

    // Settings State
    private val sharedPrefs by lazy {
        getApplication<Application>().getSharedPreferences("sonora_settings", Context.MODE_PRIVATE)
    }

    private val _audioQuality = MutableStateFlow(
        try {
            val saved = sharedPrefs.getString("audio_quality", AudioQuality.HIGH.name)
            AudioQuality.valueOf(saved ?: AudioQuality.HIGH.name)
        } catch (_: Exception) {
            AudioQuality.HIGH
        }
    )
    val audioQuality: StateFlow<AudioQuality> = _audioQuality.asStateFlow()

    private val _downloadQuality = MutableStateFlow(
        try {
            val saved = sharedPrefs.getString("download_quality", AudioQuality.MEDIUM.name)
            AudioQuality.valueOf(saved ?: AudioQuality.MEDIUM.name)
        } catch (_: Exception) {
            AudioQuality.MEDIUM
        }
    )
    val downloadQuality: StateFlow<AudioQuality> = _downloadQuality.asStateFlow()

    private val _downloadWifiOnly = MutableStateFlow(
        sharedPrefs.getBoolean("download_wifi_only", true)
    )
    val downloadWifiOnly: StateFlow<Boolean> = _downloadWifiOnly.asStateFlow()

    private val _downloadLyrics = MutableStateFlow(
        sharedPrefs.getBoolean("download_lyrics", false)
    )
    val downloadLyrics: StateFlow<Boolean> = _downloadLyrics.asStateFlow()

    val downloadStates: StateFlow<Map<String, DownloadProgress>> = DownloadManager.states
    val downloadedTracks: StateFlow<List<Track>> = DownloadManager.downloadedTracks
    val offlineLyricsStates: StateFlow<Map<String, OfflineLyricsInfo>> = DownloadManager.lyricsStates
    val downloadsSizeBytes: StateFlow<Long> = DownloadManager.totalBytes
    val isOnline: StateFlow<Boolean> = NetworkMonitor.isOnline

    /**
     * Everything playable offline: in-app downloads plus files already on device
     * (e.g. added manually or with yt-dlp). Deduplicated by local file path.
     */
    val offlineTracks: StateFlow<List<Track>> = combine(
        DownloadManager.downloadedTracks,
        musicRepository.localTracks
    ) { downloads, locals ->
        val seen = HashSet<String>(downloads.size + locals.size)
        val merged = ArrayList<Track>(downloads.size + locals.size)
        (downloads + locals).forEach { track ->
            val key = track.streamUrl
                ?.removePrefix("file://")
                ?.takeIf { it.isNotBlank() }
                ?: track.id
            if (seen.add(key)) merged.add(track)
        }
        merged
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val isAutoplayEnabled: StateFlow<Boolean> = playbackManager.playbackState
        .map { it.isAutoplayEnabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val _cacheSizeBytes = MutableStateFlow(0L)
    val cacheSizeBytes: StateFlow<Long> = _cacheSizeBytes.asStateFlow()

    private val _appLanguage = MutableStateFlow(AppLanguage.preferredTag(getApplication()))
    val appLanguage: StateFlow<String> = _appLanguage.asStateFlow()

    private var searchJob: Job? = null
    private var suggestionsJob: Job? = null
    private var lyricsJob: Job? = null
    private var lyricsRefreshJob: Job? = null
    private var translationJob: Job? = null

    // In-memory (per-process) set of imported track ids already attempted for cover refresh.
    private val triedCoverRefreshes: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    init {
        // A favorite saved while an offline copy was playing keeps the Local snapshot;
        // re-point it to the catalog identity whenever the download is still known.
        musicRepository.repairFavorites { track -> DownloadManager.canonicalTrackFor(track) }

        // Load initial top charts
        loadCharts()

        // Per-country European "Top tracks" rail (cache-first, progressive refresh).
        loadEuropeTopTracks()

        // Current trending songs for the country selected in Settings.
        loadCountryTrending()

        // Reactively (re)build home recommendations from the user's taste signals:
        // play history, followed artists, dislikes and play statistics.
        viewModelScope.launch {
            combine(
                musicRepository.history,
                musicRepository.followedArtists,
                musicRepository.dislikedTrackIds,
                musicRepository.playStats
            ) { history, followed, disliked, stats ->
                "${history.firstOrNull()?.id}|${followed.joinToString(",") { it.artistKey }}|" +
                    "${disliked.size}|${stats.size}"
            }
                .distinctUntilChanged()
                .debounce(RECOMMENDATION_REFRESH_DEBOUNCE_MS)
                .collect { loadHomeRecommendations() }
        }

        // Sync audio cache size
        refreshCacheSize()

        // Automatically fetch synchronized lyrics whenever playing track changes
        viewModelScope.launch {
            playbackState
                .map { it.currentTrack?.id }
                .distinctUntilChanged()
                .collectLatest { trackId ->
                    val track = playbackState.value.currentTrack
                    if (track != null) {
                        fetchLyrics(track)
                    } else {
                        _lyrics.value = null
                    }
                }
        }

        // Resolve release year / channel / live counts without touching playback.
        viewModelScope.launch {
            playbackState
                .map { it.currentTrack }
                .distinctUntilChanged { a, b -> a?.id == b?.id }
                .collectLatest { track ->
                    if (track != null) ensureTrackMetadata(track)
                }
        }

        // Persist refreshed YT artwork for imported (spotify_) tracks when a reliable match updates it
        viewModelScope.launch {
            playbackState
                .map { it.currentTrack }
                .distinctUntilChanged { a, b -> a?.id == b?.id && a?.coverUrl == b?.coverUrl }
                .collectLatest { track ->
                    if (track != null && track.id.startsWith("spotify_") &&
                        track.provider.equals("Spotify", ignoreCase = true)
                    ) {
                        val url = track.coverUrl
                        if (!url.isNullOrBlank() &&
                            (url.startsWith("http://") || url.startsWith("https://"))
                        ) {
                            musicRepository.updateImportedTrackCover(track.id, url)
                        }
                    }
                }
        }

        // Auto-refresh imported Spotify covers when a playlist screen is opened (no playback, no stream
        // resolution). collectLatest cancels the job as soon as the user navigates away from the playlist.
        viewModelScope.launch {
            _currentDestination
                .map { it as? ScreenDestination.PlaylistDetail }
                .distinctUntilChanged()
                .collectLatest { dest ->
                    if (dest != null) refreshImportedCovers(dest.playlistId)
                }
        }

        // Re-run (or clear) translation whenever toggle or target language changes
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                musicRepository.translateLyrics,
                musicRepository.translationTargetLang
            ) { enabled, lang -> enabled to lang }
                .distinctUntilChanged()
                .collectLatest { (enabled, _) ->
                    if (enabled) translateActiveLyrics() else clearTranslations()
                }
        }
    }

    // ==========================================
    // Imported Spotify cover auto-refresh (metadata only, no player / no stream resolution)
    // ==========================================

    private suspend fun refreshImportedCovers(playlistId: String) {
        val playlist = musicRepository.getPlaylist(playlistId) ?: return
        val candidates = playlist.tracks
            .filter { isCoverRefreshCandidate(it) && it.id !in triedCoverRefreshes }
            .take(SPOTIFY_COVER_MAX_TRACKS)
        if (candidates.isEmpty()) return

        Log.d(SPOTIFY_COVER_REFRESH_TAG, "start candidates=${candidates.size}")

        var updated = 0
        var skipped = 0
        var failed = 0
        try {
            candidates.chunked(SPOTIFY_COVER_CONCURRENCY).forEachIndexed { batchIndex, batch ->
                if (batchIndex > 0) delay(SPOTIFY_COVER_BATCH_DELAY_MS)
                val outcomes = coroutineScope {
                    batch.map { track -> async(Dispatchers.IO) { refreshOneCover(track) } }.awaitAll()
                }
                outcomes.forEach { outcome ->
                    when (outcome) {
                        CoverRefreshOutcome.UPDATED -> updated++
                        CoverRefreshOutcome.SKIPPED -> skipped++
                        CoverRefreshOutcome.FAILED -> failed++
                    }
                }
            }
        } finally {
            Log.d(SPOTIFY_COVER_REFRESH_TAG, "done updated=$updated skipped=$skipped failed=$failed")
        }
    }

    private suspend fun refreshOneCover(track: Track): CoverRefreshOutcome {
        return try {
            val res = withTimeoutOrNull(SPOTIFY_COVER_SEARCH_TIMEOUT_MS) {
                youTubeMusicClient.searchTracks("${track.title} ${track.artist}".trim(), limit = 5)
            }
            val cover = pickReliableYtCover(track, res?.getOrNull().orEmpty())
            if (cover != null && cover != track.coverUrl) {
                musicRepository.updateImportedTrackCover(track.id, cover)
                triedCoverRefreshes.add(track.id)
                Log.d(SPOTIFY_COVER_REFRESH_TAG, "updated trackId=${track.id}")
                CoverRefreshOutcome.UPDATED
            } else {
                triedCoverRefreshes.add(track.id)
                Log.d(SPOTIFY_COVER_REFRESH_TAG, "skip trackId=${track.id} reason=no-match")
                CoverRefreshOutcome.SKIPPED
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            triedCoverRefreshes.add(track.id)
            Log.d(SPOTIFY_COVER_REFRESH_TAG, "fail trackId=${track.id} reason=${e.javaClass.simpleName}")
            CoverRefreshOutcome.FAILED
        }
    }

    // ==========================================
    // Navigation
    // ==========================================

    fun selectTab(tab: NavigationTab) {
        _currentTab.value = tab
        navStack.clear()
        navStack.add(ScreenDestination.TabContent)
        _currentDestination.value = ScreenDestination.TabContent
        if (tab == NavigationTab.SETTINGS) refreshCacheSize()
    }

    /** Asks the Search field to take focus and open the keyboard. */
    fun requestSearchFocus() {
        _searchFocusRequests.value++
    }

    fun navigateToArtist(artistId: String, artistName: String? = null) {
        val cleanId = artistId.removePrefix("dz_").removePrefix("artist_").trim()
        val dest = ScreenDestination.ArtistDetail(cleanId, artistName)
        navStack.add(dest)
        _currentDestination.value = dest
        loadArtistDetail(cleanId)
    }

    fun navigateToAlbum(albumId: String, albumTitle: String? = null) {
        val cleanId = albumId.removePrefix("dz_").removePrefix("album_").trim()
        val dest = ScreenDestination.AlbumDetail(cleanId, albumTitle)
        navStack.add(dest)
        _currentDestination.value = dest
        loadAlbumDetail(cleanId)
    }

    fun navigateToPlaylist(playlistId: String) {
        val dest = ScreenDestination.PlaylistDetail(playlistId)
        navStack.add(dest)
        _currentDestination.value = dest
    }

    fun navigateToMix(mixId: String, fromLibrary: Boolean = false) {
        if (mixId.isBlank()) return
        val dest = ScreenDestination.MixDetail(mixId, fromLibrary)
        navStack.add(dest)
        _currentDestination.value = dest
    }

    fun saveMixToLibrary(mixId: String) {
        musicRepository.saveMixToLibrary(mixId)
    }

    fun removeMixFromLibrary(mixId: String) {
        musicRepository.removeMixFromLibrary(mixId)
    }

    /** Home/Library "+" button: save the snapshot or remove it. */
    fun toggleMixSaved(mixId: String) {
        val mix = musicRepository.mixes.value.firstOrNull { it.id == mixId } ?: return
        if (mix.isSaved) removeMixFromLibrary(mixId) else saveMixToLibrary(mixId)
    }

    fun navigateToYouTubeArtist(browseId: String, name: String? = null) {
        if (browseId.isBlank()) return
        val dest = ScreenDestination.YouTubeArtistPage(browseId.trim(), name)
        navStack.add(dest)
        _currentDestination.value = dest
        loadYouTubeArtistDetail(dest.browseId)
    }

    /** Opens a structured credit, using its id first and a confidence-scored name search second. */
    fun openArtist(credit: ArtistCredit) {
        val name = credit.displayName
        if (name.isBlank()) return
        val id = credit.artistId
        if (!id.isNullOrBlank()) {
            navigateToYouTubeArtist(id, name)
            return
        }
        openArtistByName(name)
    }

    /**
     * Opens the artist page for a raw artist string. Never splits commas (Tyler, The Creator),
     * and only falls back to a name search when no id is available.
     */
    fun openArtistByName(rawArtist: String) {
        val credits = ArtistCredits.fromText(rawArtist)
        val name = ArtistCredits.primaryName(credits).ifBlank { rawArtist.trim() }
        if (name.isBlank()) return
        viewModelScope.launch {
            val artists = youTubeMusicClient.searchArtists(name, limit = 8).getOrNull().orEmpty()
            val wanted = ArtistCredits.normalize(name)
            val match = artists.firstOrNull { ArtistCredits.normalize(it.name) == wanted }
                ?: artists.firstOrNull()
            val id = match?.let { it.browseId ?: it.channelId }
            if (!id.isNullOrBlank()) {
                navigateToYouTubeArtist(id, name)
            } else {
                submitSearch(name)
                selectTab(NavigationTab.SEARCH)
            }
        }
    }

    fun navigateToYouTubeAlbum(browseId: String, title: String? = null) {
        if (browseId.isBlank()) return
        val dest = ScreenDestination.YouTubeAlbumPage(browseId.trim(), title)
        navStack.add(dest)
        _currentDestination.value = dest
        loadYouTubeAlbumDetail(dest.browseId)
    }

    fun navigateToYouTubePlaylist(playlistId: String, title: String? = null) {
        if (playlistId.isBlank()) return
        val dest = ScreenDestination.YouTubePlaylistPage(playlistId.trim(), title)
        navStack.add(dest)
        _currentDestination.value = dest
        loadYouTubePlaylistDetail(dest.playlistId)
    }

    fun navigateToYouTubeArtistSongs(browseId: String, params: String?, title: String? = null) {
        if (browseId.isBlank()) return
        val dest = ScreenDestination.YouTubeArtistSongsPage(browseId.trim(), params, title)
        navStack.add(dest)
        _currentDestination.value = dest
        loadYouTubeArtistSongs(dest.browseId, dest.params)
    }

    fun navigateToDiagnostics() {
        val dest = ScreenDestination.Diagnostics
        navStack.add(dest)
        _currentDestination.value = dest
        refreshHealth()
    }

    fun navigateToOfflineLyrics() {
        val dest = ScreenDestination.OfflineLyrics
        navStack.add(dest)
        _currentDestination.value = dest
        loadOfflineLyricsStates()
    }

    // ==========================================
    // YouTube Discovery Detail (lazy, metadata only)
    // ==========================================

    fun loadYouTubeArtistDetail(browseId: String) {
        val request = ++ytDetailRequest
        _selectedYtArtist.value = null
        viewModelScope.launch {
            _isLoadingYtDetail.value = true
            _ytDetailError.value = null
            val result = youTubeMusicClient.getYouTubeArtistDetail(browseId)
            if (request != ytDetailRequest) return@launch
            result.fold(
                onSuccess = { _selectedYtArtist.value = it },
                onFailure = { _ytDetailError.value = it.message ?: getApplication<Application>().getString(R.string.vm_error_load_artist) }
            )
            _isLoadingYtDetail.value = false
        }
    }

    fun loadYouTubeAlbumDetail(browseId: String) {
        val request = ++ytDetailRequest
        _selectedYtAlbum.value = null
        viewModelScope.launch {
            _isLoadingYtDetail.value = true
            _ytDetailError.value = null
            val result = youTubeMusicClient.getYouTubeAlbumDetail(browseId)
            if (request != ytDetailRequest) return@launch
            result.fold(
                onSuccess = { _selectedYtAlbum.value = it },
                onFailure = { _ytDetailError.value = it.message ?: getApplication<Application>().getString(R.string.vm_error_load_album) }
            )
            _isLoadingYtDetail.value = false
        }
    }

    fun loadYouTubePlaylistDetail(playlistId: String) {
        val request = ++ytDetailRequest
        _selectedYtPlaylist.value = null
        viewModelScope.launch {
            _isLoadingYtDetail.value = true
            _ytDetailError.value = null
            val result = youTubeMusicClient.getYouTubePlaylistDetail(playlistId)
            if (request != ytDetailRequest) return@launch
            result.fold(
                onSuccess = { _selectedYtPlaylist.value = it },
                onFailure = { _ytDetailError.value = it.message ?: getApplication<Application>().getString(R.string.vm_error_load_playlist) }
            )
            _isLoadingYtDetail.value = false
        }
    }

    fun loadYouTubeArtistSongs(browseId: String, params: String?) {
        ytSongsBrowseId = browseId
        ytSongsParams = params
        viewModelScope.launch {
            _isLoadingYtSongs.value = true
            _ytSongsError.value = null
            _selectedYtSongs.value = null
            youTubeMusicClient.getYouTubeArtistSongs(browseId, params).fold(
                onSuccess = { _selectedYtSongs.value = it },
                onFailure = { _ytSongsError.value = it.message ?: getApplication<Application>().getString(R.string.vm_error_load_songs) }
            )
            _isLoadingYtSongs.value = false
        }
    }

    fun loadMoreYouTubeArtistSongs() {
        val current = _selectedYtSongs.value ?: return
        val token = current.continuation ?: return
        val browseId = ytSongsBrowseId ?: return
        if (_isLoadingYtSongs.value) return
        viewModelScope.launch {
            _isLoadingYtSongs.value = true
            youTubeMusicClient.getYouTubeSongsContinuation(browseId, token).fold(
                onSuccess = { page ->
                    val merged = (current.tracks + page.tracks).distinctBy { it.id }
                    _selectedYtSongs.value = YouTubeSongsPage(merged, page.continuation)
                },
                onFailure = { }
            )
            _isLoadingYtSongs.value = false
        }
    }

    // ==========================================
    // Local Follow (YouTube artists only, no remote action)
    // ==========================================

    fun isFollowingArtist(artist: YouTubeArtist): Boolean =
        musicRepository.isFollowing(artist.toFollowedArtist())

    /**
     * Compose-observable follow check: evaluates [artist] against a snapshot list collected
     * from `followedArtists`. Reading the collected list here keeps the calling composable
     * subscribed, so follow buttons refresh immediately after a toggle.
     */
    fun isFollowingArtist(artist: YouTubeArtist, current: List<FollowedArtist>): Boolean {
        val target = artist.toFollowedArtist()
        return current.any { musicRepository.sameFollowedArtist(it, target) }
    }

    fun toggleFollowArtist(artist: YouTubeArtist): Boolean =
        musicRepository.toggleFollowArtist(artist.toFollowedArtist(System.currentTimeMillis()))

    fun navigateBack(): Boolean {
        if (navStack.size > 1) {
            navStack.removeAt(navStack.size - 1)
            val prev = navStack.last()
            _currentDestination.value = prev
            when (prev) {
                is ScreenDestination.ArtistDetail -> loadArtistDetail(prev.artistId)
                is ScreenDestination.AlbumDetail -> loadAlbumDetail(prev.albumId)
                is ScreenDestination.YouTubeArtistPage -> loadYouTubeArtistDetail(prev.browseId)
                is ScreenDestination.YouTubeAlbumPage -> loadYouTubeAlbumDetail(prev.browseId)
                is ScreenDestination.YouTubePlaylistPage -> loadYouTubePlaylistDetail(prev.playlistId)
                else -> {}
            }
            return true
        }
        return false
    }

    // ==========================================
    // Artist Detail Integration
    // ==========================================

    fun loadArtistDetail(artistId: String) {
        val cleanId = artistId.removePrefix("dz_").removePrefix("artist_").trim()
        viewModelScope.launch {
            _isLoadingArtistDetail.value = true
            _artistDetailError.value = null

            val artistDeferred = async { deezerClient.getArtistDetail(cleanId) }
            val topTracksDeferred = async { deezerClient.getArtistTopTracks(cleanId, limit = 30) }
            val albumsDeferred = async { deezerClient.getArtistAlbums(cleanId, limit = 50) }

            val artistRes = artistDeferred.await()
            val topTracksRes = topTracksDeferred.await()
            val albumsRes = albumsDeferred.await()

            artistRes.fold(
                onSuccess = { _selectedArtist.value = it },
                onFailure = { _artistDetailError.value = it.message ?: getApplication<Application>().getString(R.string.vm_error_load_artist_profile) }
            )

            _artistTopTracks.value = topTracksRes.getOrElse { emptyList() }
            _artistAlbums.value = albumsRes.getOrElse { emptyList() }

            _isLoadingArtistDetail.value = false
        }
    }

    // ==========================================
    // Album Detail Integration
    // ==========================================

    fun loadAlbumDetail(albumId: String) {
        val cleanId = albumId.removePrefix("dz_").removePrefix("album_").trim()
        val request = ++albumDetailRequest
        _selectedAlbum.value = null
        _albumTracks.value = emptyList()
        viewModelScope.launch {
            _isLoadingAlbumDetail.value = true
            _albumDetailError.value = null

            val result = deezerClient.getAlbumDetail(cleanId)
            if (request != albumDetailRequest) return@launch
            result.fold(
                onSuccess = { (album, tracks) ->
                    _selectedAlbum.value = album
                    _albumTracks.value = tracks
                },
                onFailure = { error ->
                    _albumDetailError.value = error.message ?: getApplication<Application>().getString(R.string.vm_error_load_album_details)
                }
            )
            _isLoadingAlbumDetail.value = false
        }
    }

    fun playAlbum(album: Album, tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        playQueue(tracks, startIndex, "Album: ${album.title}")
    }

    // ==========================================
    // Settings Integration
    // ==========================================

    fun setThemeStyle(style: ThemeStyle) {
        musicRepository.setThemeStyle(style)
    }

    fun setAppearanceMode(mode: AppearanceMode) {
        musicRepository.setAppearanceMode(mode)
    }

    fun setAccentColor(argb: Int) {
        musicRepository.setAccentColor(argb)
    }

    // ==========================================
    // Audio source preference (global + per-track)
    // ==========================================

    /** Global default streaming source order, exposed to Settings. */
    val sourcePreference: StateFlow<PlaybackSource> = TrackSourcePreferences.globalSource

    fun setSourcePreference(source: PlaybackSource) {
        TrackSourcePreferences.setGlobal(source)
    }

    /** Per-track source override, or null when the track follows the global preference. */
    fun trackSourceOverride(trackId: String): PlaybackSource? =
        TrackSourcePreferences.overrideFor(trackId)

    /** Pins a track to a specific source and, if it is playing now, restarts it from that source. */
    fun setTrackSource(track: Track, source: PlaybackSource) {
        TrackSourcePreferences.setOverride(track.id, source)
        if (playbackManager.playbackState.value.currentTrack?.id == track.id) {
            playbackManager.refreshCurrentStream()
        }
    }

    /** Removes a track's override so it follows the global preference again. */
    fun clearTrackSource(track: Track) {
        TrackSourcePreferences.clearOverride(track.id)
        if (playbackManager.playbackState.value.currentTrack?.id == track.id) {
            playbackManager.refreshCurrentStream()
        }
    }

    fun setReduceMotion(enabled: Boolean) {
        musicRepository.setReduceMotion(enabled)
    }

    fun setLiquidGlass(enabled: Boolean) {
        musicRepository.setLiquidGlass(enabled)
    }

    fun setTranslateLyrics(enabled: Boolean) {
        musicRepository.setTranslateLyrics(enabled)
    }

    fun setTranslationTargetLang(lang: String) {
        musicRepository.setTranslationTargetLang(lang)
    }

    /** Deletes every downloaded ML Kit translation model, freeing the space they occupy. */
    fun clearTranslationModels() {
        viewModelScope.launch {
            val deleted = translationManager.deleteAllModels()
            Log.i("SonoraViewModel", "Deleted $deleted translation model(s)")
        }
    }

    fun setAudioQuality(quality: AudioQuality) {
        _audioQuality.value = quality
        sharedPrefs.edit().putString("audio_quality", quality.name).apply()
    }

    fun setDownloadQuality(quality: AudioQuality) {
        _downloadQuality.value = quality
        sharedPrefs.edit().putString("download_quality", quality.name).apply()
    }

    fun setDownloadWifiOnly(enabled: Boolean) {
        _downloadWifiOnly.value = enabled
        sharedPrefs.edit().putBoolean("download_wifi_only", enabled).apply()
    }

    fun setDownloadLyrics(enabled: Boolean) {
        _downloadLyrics.value = enabled
        sharedPrefs.edit().putBoolean("download_lyrics", enabled).apply()
    }

    /** Downloads, cancels or removes [track] depending on its current download state. */
    fun toggleDownload(track: Track) {
        when (DownloadManager.progressFor(track.id).status) {
            DownloadStatus.DOWNLOADED -> DownloadManager.remove(track.id)
            DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> DownloadManager.cancel(track.id)
            else -> DownloadManager.download(
                track = track,
                quality = _downloadQuality.value,
                wifiOnly = _downloadWifiOnly.value,
                withLyrics = _downloadLyrics.value
            )
        }
    }

    /** Spotify-style toggle: downloads all missing tracks, or removes them when all are offline. */
    private fun toggleDownloadAll(tracks: List<Track>) {
        val downloadable = tracks.filterNot { it.provider.equals("Local", ignoreCase = true) }
        if (downloadable.isEmpty()) return
        if (downloadable.all { DownloadManager.isDownloaded(it.id) }) {
            downloadable.forEach { DownloadManager.remove(it.id) }
        } else {
            DownloadManager.downloadAll(
                tracks = downloadable,
                quality = _downloadQuality.value,
                wifiOnly = _downloadWifiOnly.value,
                withLyrics = _downloadLyrics.value
            )
        }
    }

    fun togglePlaylistDownload(playlist: Playlist) = toggleDownloadAll(playlist.tracks)

    /** Shared collection action for albums, playlists and mixes. */
    fun toggleCollectionDownload(tracks: List<Track>) = toggleDownloadAll(tracks)

    fun toggleFavoritesDownload() = toggleDownloadAll(favorites.value)

    /** Deletes a downloaded track: manifest entry by id, or the app-managed local file. */
    fun deleteLocalDownload(track: Track) {
        if (DownloadManager.isDownloaded(track.id)) {
            DownloadManager.remove(track.id)
        } else {
            track.streamUrl?.let { DownloadManager.deleteManagedLocalFile(it) }
        }
    }

    fun clearDownloads() {
        DownloadManager.removeAll()
    }

    fun setAppLanguage(tag: String) {
        _appLanguage.value = tag
        AppLanguage.save(getApplication(), tag)
    }

    fun refreshCacheSize() {
        viewModelScope.launch(Dispatchers.IO) {
            _cacheSizeBytes.value = AudioCacheManager.getCacheSizeBytes()
        }
    }

    fun clearAudioCache() {
        viewModelScope.launch(Dispatchers.IO) {
            AudioCacheManager.clearCache()
            _cacheSizeBytes.value = 0L
        }
    }

    // ==========================================
    // Charts & Home
    // ==========================================

    fun loadCharts() {
        viewModelScope.launch {
            _isLoadingCharts.value = true
            val result = deezerClient.getChartTopTracks(limit = 50)
            result.onSuccess { tracks ->
                _charts.value = tracks
            }.onFailure {
                // If Deezer charts fails, fallback to empty or cached
            }
            _isLoadingCharts.value = false
        }
    }

    fun setHomeCountry(code: String) {
        val country = EuropeChartsClient.COUNTRIES.firstOrNull { it.code == code } ?: return
        if (_homeCountry.value.code == country.code) return
        _homeCountry.value = country
        sharedPrefs.edit().putString("home_country", country.code).apply()
        _countryTrendingTracks.value = emptyList()
        loadCountryTrending()
    }

    /**
     * Loads the current top songs for the country chosen in Settings.
     * Keeps the previous rail visible while refreshing unless the country changed.
     */
    fun loadCountryTrending() {
        countryTrendingJob?.cancel()
        val country = _homeCountry.value
        countryTrendingJob = viewModelScope.launch {
            _isLoadingCountryTrending.value = true
            val tracks = runCatching {
                EuropeChartsClient.fetchTopTracks(country, limit = 20)
            }.getOrDefault(emptyList())
            if (_homeCountry.value.code == country.code) {
                _countryTrendingTracks.value = tracks
            }
            _isLoadingCountryTrending.value = false
        }
    }

    /**
     * Loads the "Top tracks (EU)" carousel: one most-played song per European country.
     *
     * Serves the disk cache instantly, then refreshes progressively (each country emitted as it
     * arrives) when the snapshot is stale. Failures are skipped so partial data stays usable.
     */
    fun loadEuropeTopTracks(forceRefresh: Boolean = false) {
        if (europeTopJob?.isActive == true) return
        val context = getApplication<Application>()
        europeTopJob = viewModelScope.launch {
            val snapshot = EuropeChartsCache.read(context)
            if (snapshot != null) {
                _countryTopTracks.value = snapshot.tracks
            }

            val collected = _countryTopTracks.value.associateBy { it.countryCode }.toMutableMap()
            val missing = EuropeChartsClient.COUNTRIES.filter { it.code !in collected }

            // Nothing stale and no gaps -> keep the cached snapshot.
            if (snapshot != null && snapshot.isFresh && missing.isEmpty() && !forceRefresh) return@launch

            // When a fresh cache is already on screen we top up the gaps quietly (no spinner).
            val quietTopUp = snapshot != null && snapshot.isFresh && _countryTopTracks.value.isNotEmpty()
            if (!quietTopUp) _isLoadingCountryTopTracks.value = true

            suspend fun fetchPass(countries: List<EuropeanCountry>, pauseMs: Long) {
                for (country in countries) {
                    if (pauseMs > 0) delay(pauseMs)
                    val chartTrack = EuropeChartsClient.fetchTopTrack(country) ?: continue
                    val track = resolveEuYouTubeTrack(chartTrack)
                    collected[country.code] = CountryTopTrack(country.code, country.name, track)
                    _countryTopTracks.value = EuropeChartsClient.COUNTRIES.mapNotNull { collected[it.code] }
                }
            }

            val firstSweep = if (snapshot != null && snapshot.isFresh) missing else EuropeChartsClient.COUNTRIES
            fetchPass(firstSweep, pauseMs = 120)

            // Second pass for countries that hit a transient rate limit (the endpoint is throttled),
            // so coverage keeps filling across launches instead of dropping countries.
            val stillMissing = EuropeChartsClient.COUNTRIES.filter { it.code !in collected }
            if (stillMissing.isNotEmpty()) {
                delay(1500)
                fetchPass(stillMissing, pauseMs = 250)
            }

            _isLoadingCountryTopTracks.value = false
            if (collected.isNotEmpty()) {
                EuropeChartsCache.write(context, _countryTopTracks.value)
            }
        }
    }

    /**
     * Enriches a chart entry with its real YouTube Music counterpart, so the carousel holds a
     * playable YT track (direct videoId + real duration) instead of bare chart metadata. Keeps
     * the "Charts" provider label and the crisp Apple artwork.
     */
    private suspend fun resolveEuYouTubeTrack(chartTrack: Track): Track {
        return try {
            val query = "${chartTrack.title} ${chartTrack.artist}".trim()
            val results = youTubeMusicClient.searchTracks(query, limit = 3).getOrNull().orEmpty()
            val match = results.firstOrNull { it.id.startsWith("yt_") && it.durationMs > 0L }
                ?: return chartTrack
            chartTrack.copy(
                id = match.id,
                durationMs = match.durationMs,
                album = match.album ?: chartTrack.album,
                provider = EuropeChartsClient.PROVIDER,
                artistId = match.artistId ?: chartTrack.artistId,
                artists = match.artists?.takeIf { it.isNotEmpty() } ?: chartTrack.artists,
            )
        } catch (e: Exception) {
            chartTrack
        }
    }

    // ==========================================
    // Playback Controls
    // ==========================================

    fun playTrack(track: Track, context: String? = null) {
        if (track.id.startsWith("album_")) {
            val albumId = track.id.removePrefix("album_")
            navigateToAlbum(albumId, track.title)
            return
        }
        if (track.id.startsWith("artist_")) {
            val artistId = track.id.removePrefix("artist_")
            navigateToArtist(artistId, track.title)
            return
        }
        val resolvedContext = context ?: when {
            track.provider.equals("YouTube", ignoreCase = true) -> "YouTube Music"
            !track.album.isNullOrBlank() &&
            !track.album.equals(track.title, ignoreCase = true) &&
            !track.album.equals("YouTube", ignoreCase = true) -> "Album: ${track.album}"
            else -> null
        }
        playbackManager.playTrack(track, resolvedContext)
        musicRepository.addToHistory(track)
    }

    /** Records an explicit playlist start for the Home "most listened playlists" ranking. */
    fun recordPlaylistPlay(playlistId: String) {
        musicRepository.recordPlaylistPlay(playlistId)
    }

    fun playQueue(tracks: List<Track>, startIndex: Int = 0, context: String? = null) {
        if (tracks.isEmpty()) return
        playbackManager.playQueue(tracks, startIndex, context)
        val startedTrack = tracks.getOrNull(startIndex)
        if (startedTrack != null) {
            musicRepository.addToHistory(startedTrack)
        }
    }

    fun togglePlayPause() {
        playbackManager.togglePlayPause()
    }

    fun playNext() {
        playbackManager.playNext()
    }

    /** Inserts [track] right after the currently playing one ("Riproduci dopo"). */
    fun addToQueueNext(track: Track) {
        if (partyManager.state.value.role == PartyRole.GUEST) {
            partyManager.guestAddToQueue(track, asNext = true)
        } else {
            playbackManager.addNext(track)
        }
    }

    fun playPrevious() {
        playbackManager.playPrevious()
    }

    fun seekTo(positionMs: Long) {
        playbackManager.seekTo(positionMs)
    }

    fun toggleShuffle() {
        playbackManager.toggleShuffle()
    }

    fun toggleRepeat() {
        playbackManager.toggleRepeatMode()
    }

    fun setPlayerExpanded(expanded: Boolean) {
        _isPlayerExpanded.value = expanded
        if (!expanded) {
            _isLyricsOpen.value = false
        }
    }

    fun toggleLyricsOpen() {
        _isLyricsOpen.value = !_isLyricsOpen.value
    }

    // ==========================================
    // Queue Controls
    // ==========================================

    fun openQueueSheet() {
        _isQueueSheetOpen.value = true
    }

    fun closeQueueSheet() {
        _isQueueSheetOpen.value = false
    }

    fun setQueueSheetOpen(open: Boolean) {
        _isQueueSheetOpen.value = open
    }

    // ==========================================
    // Party (synchronized group listening)
    // ==========================================

    fun openPartySheet() {
        _isPartySheetOpen.value = true
    }

    fun closePartySheet() {
        _isPartySheetOpen.value = false
    }

    fun createParty() {
        partyManager.clearError()
        partyManager.startHosting()
    }

    fun scanForParties() {
        partyManager.clearError()
        partyManager.startDiscovery()
    }

    fun stopPartyScan() = partyManager.stopDiscovery()

    fun joinParty(endpointId: String) = partyManager.join(endpointId)

    fun joinPartyByCode(code: String) {
        partyManager.clearError()
        if (!partyManager.joinByCode(code)) {
            Log.w("SonoraViewModel", "Invalid Party code: $code")
        }
    }

    fun leaveParty() {
        partyManager.leave()
        _isPartySheetOpen.value = false
    }

    fun setPartyAllowGuestControl(allowed: Boolean) = partyManager.setAllowGuestControl(allowed)

    fun setPartyAllowQueueAdd(allowed: Boolean) = partyManager.setAllowQueueAdd(allowed)

    fun setPartyNudge(ms: Long) = partyManager.setNudge(ms)

    fun clearPartyError() = partyManager.clearError()

    fun addToQueue(track: Track) {
        if (partyManager.state.value.role == PartyRole.GUEST) {
            partyManager.guestAddToQueue(track, asNext = false)
        } else {
            playbackManager.addToQueue(track)
        }
    }

    fun addToQueue(tracks: List<Track>) {
        if (partyManager.state.value.role == PartyRole.GUEST) {
            tracks.forEach { partyManager.guestAddToQueue(it, asNext = false) }
        } else {
            playbackManager.addToQueue(tracks)
        }
    }

    fun removeFromQueue(index: Int) {
        if (partyManager.state.value.role == PartyRole.GUEST) return
        playbackManager.removeFromQueue(index)
    }

    fun clearQueue() {
        if (partyManager.state.value.role == PartyRole.GUEST) return
        playbackManager.clearQueue()
    }

    fun clearUserQueue() {
        if (partyManager.state.value.role == PartyRole.GUEST) return
        playbackManager.clearUserQueue()
    }

    fun clearUpcoming() {
        if (partyManager.state.value.role == PartyRole.GUEST) return
        playbackManager.clearUpcoming()
    }

    fun moveQueueItem(from: Int, to: Int) {
        if (partyManager.state.value.role == PartyRole.GUEST) return
        playbackManager.moveQueueItem(from, to)
    }

    fun toggleAutoplay() {
        playbackManager.toggleAutoplay()
    }

    fun setAutoplayEnabled(enabled: Boolean) {
        playbackManager.setAutoplayEnabled(enabled)
    }

    fun playFromQueueIndex(index: Int) {
        playbackManager.playFromQueueIndex(index)
    }

    // ==========================================
    // Lyrics Integration
    // ==========================================

    /**
     * Loads the provider metadata once per id for the current session. Local
     * files already carry their tags, and every other failure is left for the
     * next track rather than blocking playback or the menu.
     */
    fun ensureTrackMetadata(track: Track, force: Boolean = false) {
        if (track.provider.equals("Local", ignoreCase = true)) return
        if (!force) {
            if (_trackMetadata.value.containsKey(track.id)) return
            if (trackMetadataJobs[track.id]?.isActive == true) return
        } else {
            trackMetadataJobs.remove(track.id)?.cancel()
        }
        trackMetadataJobs[track.id] = viewModelScope.launch {
            try {
                youTubeMusicClient.getTrackMetadata(track).onSuccess { metadata ->
                    _trackMetadata.update { current -> current + (track.id to metadata) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(LYRICS_TAG, "track metadata failed for '${track.title}': ${e.message}")
            } finally {
                trackMetadataJobs.remove(track.id)
            }
        }
    }

    /** Retry only when the year is still missing; a resolved track stays cached. */
    fun requestTrackMetadata(track: Track) {
        val existing = _trackMetadata.value[track.id]
        if (existing?.releaseYear != null) return
        ensureTrackMetadata(track, force = existing != null)
    }

    private fun fetchLyrics(
        track: Track,
        forced: LyricsSource? = null,
        ignoreOffline: Boolean = false,
    ) {
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch {
            _isLoadingLyrics.value = true
            _activeLyricsSource.value = forced
            val canonical = DownloadManager.canonicalTrackFor(track) ?: track
            if (!ignoreOffline && forced == null) {
                val offline = DownloadManager.lyricsFor(canonical)
                if (offline != null) {
                    if (offline.lyrics != null) {
                        _lyrics.value = offline.lyrics
                        _activeLyricsSource.value = offline.source?.let(LyricsSource::fromName)
                        _isLoadingLyrics.value = false
                        translateActiveLyrics()
                        return@launch
                    }
                    if (!NetworkMonitor.isOnline.value) {
                        _lyrics.value = null
                        _activeLyricsSource.value = null
                        _isLoadingLyrics.value = false
                        return@launch
                    }
                }
            }
            val sources = forced?.let { setOf(it) } ?: musicRepository.lyricsSources.value
            val order = forced?.let { listOf(it) } ?: musicRepository.lyricsSourceOrder.value
            val initialStates = sources.associateWith { LyricsProviderState.NOT_FETCHED }
            _lyricsProviderStates.value = initialStates

            val videoId = canonical.id.takeIf { YouTubeMusicClient.isValidVideoId(it) }
            val (title, artist) = org.sonora.android.api.lyrics.LyricsText.splitArtistTitle(
                canonical.title,
                canonical.artist,
            )

            val result = runCatching {
                LyricsRepository.lookup(
                    title = title,
                    artist = artist,
                    album = canonical.album,
                    durationMs = canonical.durationMs,
                    videoId = videoId,
                    sources = sources,
                    order = order,
                    prioritizeSyllableSync = musicRepository.prioritizeSyllableSync.value,
                    apiKey = musicRepository.paxsenixApiKey.value,
                    onState = { source, state ->
                        _lyricsProviderStates.update { current -> current + (source to state) }
                    },
                )
            }.onFailure { error ->
                if (error is CancellationException) throw error
                Log.w(LYRICS_TAG, "lookup failed '${canonical.title}': ${error.message}")
            }.getOrNull()

            if (result == null) {
                Log.i(LYRICS_TAG, "no lyrics '${canonical.title}' - '${canonical.artist}'")
                _lyrics.value = null
                _activeLyricsSource.value = null
                _isLoadingLyrics.value = false
                return@launch
            }

            val domain = LyricsBridge.toDomain(result.hit.lyrics, canonical.id)
            _lyrics.value = domain
            _activeLyricsSource.value = result.source
            if (DownloadManager.isDownloaded(canonical.id)) {
                val offline = OfflineLyrics(
                    lyrics = domain,
                    source = result.source.name,
                    notFound = false
                )
                DownloadLyricsStore.default(getApplication()).write(canonical.id, offline)
                DownloadManager.recordLyrics(canonical.id, offline)
            }
            val wordLines = domain.lines.count { !it.wordTimings.isNullOrEmpty() }
            Log.i(
                LYRICS_TAG,
                "source=${result.source.label} worded=${result.hit.lyrics.worded()} " +
                    "lines=${domain.lines.size} wordLines=$wordLines " +
                    "'${canonical.title}' - '${canonical.artist}'",
            )
            translateActiveLyrics()
            _isLoadingLyrics.value = false
        }
    }

    fun selectLyricsSource(source: LyricsSource?) {
        val track = playbackState.value.currentTrack ?: return
        val canonical = DownloadManager.canonicalTrackFor(track) ?: track
        musicRepository.setLyricsSourceFor(canonical.id, source)
        fetchLyrics(track, forced = source, ignoreOffline = true)
    }

    // ---- Offline lyrics management (Settings → Testi offline) ----

    /** Per-track source override, or null when the global source order applies. */
    fun lyricsSourceForTrack(track: Track): LyricsSource? {
        val canonical = DownloadManager.canonicalTrackFor(track) ?: track
        return musicRepository.lyricsSourceFor(canonical.id)
    }

    /**
     * Persists the per-track source (null = Automatic) and downloads the lyrics for it.
     * The same preference is used by the player and by future automatic downloads.
     */
    fun downloadLyricsFor(track: Track, source: LyricsSource?) {
        val canonical = DownloadManager.canonicalTrackFor(track) ?: track
        musicRepository.setLyricsSourceFor(canonical.id, source)
        viewModelScope.launch {
            DownloadManager.fetchLyricsNow(canonical, sourceOverride = source, force = true)
        }
    }

    fun removeOfflineLyrics(track: Track) {
        DownloadManager.removeLyrics(track)
    }

    /** Queues lyrics for the downloaded tracks that still have none (or a retryable error). */
    fun downloadMissingLyrics(tracks: List<Track>) {
        tracks.forEach { track ->
            val canonical = DownloadManager.canonicalTrackFor(track) ?: track
            when (DownloadManager.lyricsStates.value[canonical.id]?.status) {
                OfflineLyricsStatus.FOUND, OfflineLyricsStatus.FETCHING -> Unit
                else -> viewModelScope.launch {
                    DownloadManager.fetchLyricsNow(canonical)
                }
            }
        }
    }

    /** Hydrates the in-memory lyrics status for the app's downloaded tracks. */
    fun loadOfflineLyricsStates() {
        DownloadManager.ensureLyricsLoaded(
            DownloadManager.downloadedTracks.value.map { track ->
                (DownloadManager.canonicalTrackFor(track) ?: track).id
            }
        )
    }

    fun setLyricsSourceEnabled(source: LyricsSource, enabled: Boolean) {
        musicRepository.setLyricsSourceEnabled(source, enabled)
        refreshLyricsAfterConfigChange()
    }

    fun setLyricsSourceOrder(order: List<LyricsSource>) {
        musicRepository.setLyricsSourceOrder(order)
        refreshLyricsAfterConfigChange()
    }

    fun setPrioritizeSyllableSync(enabled: Boolean) {
        musicRepository.setPrioritizeSyllableSync(enabled)
        refreshLyricsAfterConfigChange()
    }

    fun setPaxsenixApiKey(value: String) {
        musicRepository.setPaxsenixApiKey(value)
        refreshLyricsAfterConfigChange()
    }

    fun resetLyricsSources() {
        musicRepository.resetLyricsSources()
        refreshLyricsAfterConfigChange()
    }

    /**
     * Applies a source/order change to the already-playing track. Without this,
     * the preference moved but the lyrics on screen stayed those fetched with
     * the previous order. Debounced because the sources dialog applies several
     * changes in one go.
     */
    private fun refreshLyricsAfterConfigChange() {
        val track = playbackState.value.currentTrack ?: return
        lyricsRefreshJob?.cancel()
        lyricsRefreshJob = viewModelScope.launch {
            delay(250)
            if (playbackState.value.currentTrack?.id == track.id) {
                Log.i(LYRICS_TAG, "Refreshing lyrics for '${track.title}' after source config change")
                fetchLyrics(track, ignoreOffline = true)
            }
        }
    }

    fun setLyricsProviderMusixmatch(enabled: Boolean) =
        musicRepository.setLyricsProviderMusixmatch(enabled)

    fun setLyricsProviderLrclib(enabled: Boolean) =
        musicRepository.setLyricsProviderLrclib(enabled)

    private fun translateActiveLyrics() {
        if (!musicRepository.translateLyrics.value) return
        val base = _lyrics.value ?: return
        if (base.lines.isEmpty()) return
        translationJob?.cancel()
        translationJob = viewModelScope.launch(Dispatchers.Default) {
            val target = musicRepository.translationTargetLang.value
            val originals = base.lines.map { if (it.translation != null) it.copy(translation = null) else it }
            val translated = translationManager.translateLines(base.id, originals, target)
            _lyrics.value = base.copy(lines = translated)
        }
    }

    private fun clearTranslations() {
        translationJob?.cancel()
        val current = _lyrics.value ?: return
        if (current.lines.none { it.translation != null }) return
        _lyrics.value = current.copy(lines = current.lines.map { it.copy(translation = null) })
    }

    // ==========================================
    // Search
    // ==========================================

    /**
     * Edits the query without committing it. The committed result page stays
     * intact underneath so Back can return to it; the UI swaps to the
     * suggestion/history panel while [isSearchTyping] is true.
     */
    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()
        suggestionsJob?.cancel()
        if (query.isBlank()) {
            _isSearchTyping.value = false
            _searchSuggestions.value = emptyList()
            _searchResults.value = emptyList()
            clearDiscoveryResults()
            _isSearching.value = false
            committedSearchQuery = ""
            return
        }

        _isSearchTyping.value = true
        _isSearching.value = false
        _searchSuggestions.value = emptyList()
        if (query.trim().length < 2) return
        suggestionsJob = viewModelScope.launch {
            delay(170)
            val fetched = youTubeMusicClient.searchSuggestions(query.trim(), limit = 6)
                .getOrElse { emptyList() }
            if (_isSearchTyping.value && _searchQuery.value.trim() == query.trim()) {
                _searchSuggestions.value = fetched
            }
        }
    }

    /** Fills a completion without running it, so the user can keep editing. */
    fun onSearchSuggestionFill(value: String) {
        onSearchQueryChange(value)
    }

    /**
     * Commits the current (or supplied) query and runs the full category search.
     * This is the only path that records a search result page; typing itself
     * never hits the full search endpoint.
     */
    fun submitSearch(query: String = _searchQuery.value, category: SearchCategory? = null) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        searchJob?.cancel()
        suggestionsJob?.cancel()
        _searchQuery.value = trimmed
        committedSearchQuery = trimmed
        SearchHistoryStore.recordQuery(trimmed)
        if (category != null) _searchCategory.value = category
        _isSearchTyping.value = false
        _searchSuggestions.value = emptyList()
        searchJob = viewModelScope.launch {
            executeSearch(trimmed)
        }
    }

    /** Dismisses the suggestion panel and restores the last committed results. */
    fun cancelSearchTyping() {
        if (!_isSearchTyping.value) return
        suggestionsJob?.cancel()
        _searchSuggestions.value = emptyList()
        _isSearchTyping.value = false
        _searchQuery.value = committedSearchQuery
    }

    fun onSearchCategoryChange(category: SearchCategory) {
        _searchCategory.value = category
        val current = _searchQuery.value
        if (current.isBlank()) return
        if (_isSearchTyping.value) {
            submitSearch(current, category)
        } else {
            searchJob?.cancel()
            searchJob = viewModelScope.launch {
                executeSearch(current)
            }
        }
    }

    fun recordSearchHistory(entry: SearchHistoryEntry?) {
        if (entry != null) SearchHistoryStore.record(entry)
    }

    fun removeSearchHistory(type: SearchHistoryType, id: String) {
        SearchHistoryStore.remove(type, id)
    }

    fun clearSearchHistory() {
        SearchHistoryStore.clear()
    }

    private fun clearDiscoveryResults() {
        _searchArtistResults.value = emptyList()
        _searchAlbumResults.value = emptyList()
        _searchYtArtists.value = emptyList()
        _searchYtAlbums.value = emptyList()
        _searchYtPlaylists.value = emptyList()
    }

    private suspend fun executeSearch(query: String) {
        _isSearching.value = true
        when (_searchCategory.value) {
            SearchCategory.ALL -> {
                // Tracks: YouTube Music primary, Deezer only as enrichment (never overrides yt_).
                val ytTracksDeferred = viewModelScope.async { youTubeMusicClient.searchTracks(query, limit = 20) }
                val dzTracksDeferred = viewModelScope.async { deezerClient.searchTracks(query, limit = 20) }
                // Artists / albums / playlists: YouTube Music only (no Deezer).
                val ytArtistsDeferred = viewModelScope.async { youTubeMusicClient.searchArtists(query, limit = 25) }
                val ytAlbumsDeferred = viewModelScope.async { youTubeMusicClient.searchAlbums(query, limit = 10) }
                val ytPlaylistsDeferred = viewModelScope.async { youTubeMusicClient.searchPlaylists(query, limit = 20) }

                val ytRes = ytTracksDeferred.await().getOrElse { emptyList() }
                val dzRes = dzTracksDeferred.await().getOrElse { emptyList() }.filter { it.durationMs > 0 }

                _searchResults.value = sortByStreams(mergeYouTubeFirst(ytRes, dzRes))
                _searchArtistResults.value = emptyList()
                _searchAlbumResults.value = emptyList()
                _searchYtArtists.value = ytArtistsDeferred.await().getOrElse { emptyList() }
                _searchYtAlbums.value = ytAlbumsDeferred.await().getOrElse { emptyList() }
                _searchYtPlaylists.value = ytPlaylistsDeferred.await().getOrElse { emptyList() }
            }
            SearchCategory.TRACKS -> {
                // YouTube Music is the primary catalog source and is queried first. A high limit
                // (paginated in the client) keeps the "Brani" tab at 50+ songs.
                val ytDeferred = viewModelScope.async { youTubeMusicClient.searchTracks(query, limit = 60) }
                val deezerDeferred = viewModelScope.async { deezerClient.searchTracks(query, limit = 30) }

                val ytTracks = ytDeferred.await().getOrElse { emptyList() }
                val deezerTracks = deezerDeferred.await().getOrElse { emptyList() }.filter { it.durationMs > 0 }

                _searchResults.value = sortByStreams(mergeYouTubeFirst(ytTracks, deezerTracks))
                clearDiscoveryResults()
            }
            SearchCategory.VIDEOS -> {
                // Dedicated YouTube video search (InnerTube video filter) → many real videos.
                val ytDeferred = viewModelScope.async { youTubeMusicClient.searchVideos(query, limit = 30) }
                val ytTracks = ytDeferred.await().getOrElse { emptyList() }

                _searchResults.value = sortByStreams(ytTracks)
                clearDiscoveryResults()
            }
            SearchCategory.ARTISTS -> {
                val res = youTubeMusicClient.searchArtists(query, limit = 30)
                _searchResults.value = emptyList()
                clearDiscoveryResults()
                _searchYtArtists.value = res.getOrElse { emptyList() }
            }
            SearchCategory.ALBUMS -> {
                val res = youTubeMusicClient.searchAlbums(query, limit = 30)
                _searchResults.value = emptyList()
                clearDiscoveryResults()
                _searchYtAlbums.value = res.getOrElse { emptyList() }
            }
            SearchCategory.PLAYLISTS -> {
                val res = youTubeMusicClient.searchPlaylists(query, limit = 20)
                _searchResults.value = emptyList()
                clearDiscoveryResults()
                _searchYtPlaylists.value = res.getOrElse { emptyList() }
            }
        }
        _isSearching.value = false
    }

    // ==========================================
    // Recommendations & Dislike
    // ==========================================

    fun loadHomeRecommendations() {
        viewModelScope.launch {
            _isLoadingRecommendations.value = true
            val history = musicRepository.history.value
            val tasteProfile = TasteProfile.build(
                followedArtists = musicRepository.followedArtists.value,
                history = history,
                topPlayedTracks = musicRepository.topPlayedTracks(15)
                    .map { it to musicRepository.playCountOf(it.id) },
                topPlayedArtists = musicRepository.topPlayedArtists(10),
                dislikedTrackIds = musicRepository.dislikedTrackIds.value,
                dislikedArtists = musicRepository.dislikedArtists.value
            )
            val recs = if (tasteProfile.isEmpty) {
                emptyList()
            } else {
                recommendationEngine.getHomeRecommendations(
                    tasteProfile = tasteProfile,
                    count = 10,
                    historyTracks = history,
                    dislikedTrackIds = musicRepository.dislikedTrackIds.value,
                    dislikedArtists = musicRepository.dislikedArtists.value
                )
            }
            _recommendedTracks.value = recs
            _isLoadingRecommendations.value = false
        }
    }

    fun isDisliked(trackId: String): Boolean {
        return musicRepository.isDisliked(trackId)
    }

    fun toggleDislike(track: Track) {
        val isNowDisliked = musicRepository.toggleDislike(track)
        if (isNowDisliked) {
            recommendationEngine.blacklistTrack(track.id)
            recommendationEngine.blacklistArtist(track.artist)
            if (playbackState.value.currentTrack?.id == track.id) {
                playNext()
            }
            loadHomeRecommendations()
        }
    }

    // ==========================================
    // Favorites & Playlists
    // ==========================================

    fun isFavorite(trackId: String): Boolean {
        return musicRepository.isFavorite(trackId)
    }

    fun toggleFavorite(track: Track) {
        val canonical = DownloadManager.canonicalTrackFor(track) ?: track
        musicRepository.toggleFavorite(canonical)
    }

    fun createPlaylist(name: String, description: String? = null, coverUrl: String? = null): Playlist {
        return musicRepository.createPlaylist(name, description, coverUrl)
    }

    fun createPlaylistWithTracks(
        name: String,
        tracks: List<Track>,
        description: String? = null,
        coverUrl: String? = null
    ): Playlist {
        return musicRepository.createPlaylistWithTracks(name, tracks, description, coverUrl)
    }

    fun deletePlaylist(playlistId: String) {
        musicRepository.deletePlaylist(playlistId)
    }

    fun addTrackToPlaylist(playlistId: String, track: Track) {
        musicRepository.addTrackToPlaylist(playlistId, track)
    }

    fun removeTrackFromPlaylist(playlistId: String, trackId: String) {
        musicRepository.removeTrackFromPlaylist(playlistId, trackId)
    }

    fun reorderPlaylist(playlistId: String, from: Int, to: Int) {
        musicRepository.reorderPlaylist(playlistId, from, to)
    }

    fun reorderFavorites(from: Int, to: Int) {
        musicRepository.reorderFavorites(from, to)
    }

    fun renamePlaylist(playlistId: String, newName: String) {
        musicRepository.renamePlaylist(playlistId, newName)
    }

    fun updatePlaylist(playlistId: String, name: String, description: String? = null, coverUrl: String? = null) {
        musicRepository.updatePlaylist(playlistId, name, description, coverUrl)
    }

    // Add To Playlist Dialog State
    private val _trackToAddToPlaylist = MutableStateFlow<Track?>(null)
    val trackToAddToPlaylist: StateFlow<Track?> = _trackToAddToPlaylist.asStateFlow()

    fun showAddToPlaylistDialog(track: Track) {
        _trackToAddToPlaylist.value = track
    }

    fun hideAddToPlaylistDialog() {
        _trackToAddToPlaylist.value = null
    }

    suspend fun importSpotifyPlaylist(
        url: String,
        onProgress: (suspend (current: Int, total: Int, trackTitle: String) -> Unit)? = null
    ): Result<Playlist> {
        return spotifyPlaylistImporter.importAndSavePlaylist(
            urlOrId = url,
            repository = musicRepository,
            onProgress = onProgress
        )
    }

    /** Imports a public YouTube Music playlist (by link, id or browse id) into the library. */
    suspend fun importYouTubePlaylist(playlistId: String): Result<Playlist> {
        return youTubePlaylistImporter.importAndSavePlaylist(
            urlOrId = playlistId,
            repository = musicRepository
        )
    }

    // ==========================================
    // Diagnostics
    // ==========================================

    fun refreshHealth() {
        viewModelScope.launch {
            _isRefreshingHealth.value = true
            apiHealthManager.recheckAll()
            _isRefreshingHealth.value = false
        }
    }

    fun refreshLocalMusic() {
        musicRepository.syncLocalMusic()
    }

    // ==========================================
    // Equalizer & Audio DSP Controls
    // ==========================================

    fun openEqualizerDialog() {
        _isEqualizerDialogOpen.value = true
    }

    fun closeEqualizerDialog() {
        _isEqualizerDialogOpen.value = false
    }

    fun setEqualizerEnabled(enabled: Boolean) {
        equalizerManager.setEnabled(enabled)
    }

    fun setEqualizerPreset(preset: EqualizerPreset) {
        equalizerManager.setPreset(preset)
    }

    fun setEqualizerBandGain(bandIndex: Int, gainDb: Float) {
        equalizerManager.setBandGain(bandIndex, gainDb)
    }

    fun setBassBoostStrength(strength: Int) {
        equalizerManager.setBassBoostStrength(strength)
    }

    fun resetEqualizer() {
        equalizerManager.resetToFlat()
    }

    override fun onCleared() {
        super.onCleared()
        translationManager.close()
    }
}
