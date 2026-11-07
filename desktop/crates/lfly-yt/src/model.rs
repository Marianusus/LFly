//! Data types shared with the phone app and the desktop UI.
//!
//! [`Track`] and [`ArtistCredit`] use the same JSON field names as the Kotlin
//! classes the phone stores with Gson (camelCase), so a library exported from
//! the phone reads back unchanged. Unknown fields are ignored and missing or
//! `null` fields fall back to their defaults.

use serde::{Deserialize, Deserializer, Serialize};

/// Reads a field that Gson may have written as `null` into its default value.
fn null_default<'de, D, T>(deserializer: D) -> Result<T, D::Error>
where
    D: Deserializer<'de>,
    T: Default + Deserialize<'de>,
{
    Ok(Option::<T>::deserialize(deserializer)?.unwrap_or_default())
}

fn default_provider() -> String {
    "Unknown".to_string()
}

fn provider_or_unknown<'de, D>(deserializer: D) -> Result<String, D::Error>
where
    D: Deserializer<'de>,
{
    Ok(Option::<String>::deserialize(deserializer)?.unwrap_or_else(default_provider))
}

/// How an artist takes part in a track (Kotlin `ArtistRole`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Default)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ArtistRole {
    #[default]
    Primary,
    Featured,
    Producer,
}

impl<'de> Deserialize<'de> for ArtistRole {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        // Unknown or missing roles read as PRIMARY instead of failing the whole track.
        let raw = Option::<String>::deserialize(deserializer)?;
        Ok(match raw.as_deref().map(str::to_ascii_uppercase).as_deref() {
            Some("FEATURED") => ArtistRole::Featured,
            Some("PRODUCER") => ArtistRole::Producer,
            _ => ArtistRole::Primary,
        })
    }
}

/// One artist credited on a track (Kotlin `ArtistCredit`).
///
/// `browse_id` is YouTube Music's artist page id (usually a `UC...` channel id),
/// `channel_id` the channel id when the payload exposes it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct ArtistCredit {
    #[serde(deserialize_with = "null_default")]
    pub name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub browse_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub channel_id: Option<String>,
    pub role: ArtistRole,
}

impl ArtistCredit {
    /// A credit with only a name.
    pub fn named(name: impl Into<String>) -> Self {
        Self { name: name.into(), ..Self::default() }
    }

    /// Id usable to open the artist page, preferring the browse id.
    pub fn artist_id(&self) -> Option<&str> {
        self.browse_id
            .as_deref()
            .filter(|s| !s.trim().is_empty())
            .or_else(|| self.channel_id.as_deref().filter(|s| !s.trim().is_empty()))
    }
}

/// An audio track (Kotlin `Track`). YouTube tracks have the id `yt_<videoId>`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Track {
    #[serde(deserialize_with = "null_default")]
    pub id: String,
    #[serde(deserialize_with = "null_default")]
    pub title: String,
    #[serde(deserialize_with = "null_default")]
    pub artist: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub album: Option<String>,
    #[serde(deserialize_with = "null_default")]
    pub duration_ms: i64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub cover_url: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub stream_url: Option<String>,
    #[serde(deserialize_with = "provider_or_unknown")]
    pub provider: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub artist_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub bpm: Option<f32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub gain: Option<f32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub rank: Option<i32>,
    /// Play or view count when the source shows it.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub streams: Option<i64>,
    /// True when the track comes from a YouTube video rather than an official song.
    #[serde(deserialize_with = "null_default")]
    pub is_video: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub release_year: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub album_id: Option<String>,
    /// Every credited artist with ids; `None` when the source only had a string.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub artists: Option<Vec<ArtistCredit>>,
}

impl Default for Track {
    fn default() -> Self {
        Self {
            id: String::new(),
            title: String::new(),
            artist: String::new(),
            album: None,
            duration_ms: 0,
            cover_url: None,
            stream_url: None,
            provider: default_provider(),
            artist_id: None,
            bpm: None,
            gain: None,
            rank: None,
            streams: None,
            is_video: false,
            release_year: None,
            album_id: None,
            artists: None,
        }
    }
}

impl Track {
    /// The YouTube video id when this is a `yt_` track with a valid id.
    pub fn video_id(&self) -> Option<&str> {
        let raw = self.id.strip_prefix("yt_").unwrap_or(&self.id).trim();
        crate::parse::is_valid_video_id(raw).then_some(raw)
    }
}

/// An artist card (Kotlin `YouTubeArtist`).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct ArtistSummary {
    pub browse_id: Option<String>,
    pub channel_id: Option<String>,
    pub name: String,
    pub thumbnail_url: Option<String>,
    /// "Artist • 67.1M monthly audience" and similar.
    pub subtitle: Option<String>,
}

/// An album card (Kotlin `YouTubeAlbum`).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct AlbumSummary {
    /// `MPRE...` id used to open the album page.
    pub browse_id: Option<String>,
    /// `OLAK...` playlist id of the album, when shown.
    pub playlist_id: Option<String>,
    pub title: String,
    pub artist: Option<String>,
    /// Artist page id (`UC...`) when the page links the artist.
    pub artist_id: Option<String>,
    pub year: Option<String>,
    pub thumbnail_url: Option<String>,
    pub release_year: Option<i32>,
    pub track_count: Option<i32>,
    /// "Album", "Single", "EP", ... when shown.
    #[serde(rename = "type")]
    pub kind: Option<String>,
}

impl AlbumSummary {
    /// Id usable to open the album (browse id first, playlist id second).
    pub fn detail_id(&self) -> Option<&str> {
        self.browse_id
            .as_deref()
            .filter(|s| !s.is_empty())
            .or_else(|| self.playlist_id.as_deref().filter(|s| !s.is_empty()))
    }
}

/// A playlist card (Kotlin `YouTubePlaylist`).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct PlaylistSummary {
    pub playlist_id: Option<String>,
    /// `VL...` browse id.
    pub browse_id: Option<String>,
    pub title: String,
    pub author: Option<String>,
    pub track_count: Option<i32>,
    pub thumbnail_url: Option<String>,
}

impl PlaylistSummary {
    /// Id usable to open the playlist (playlist id first, browse id second).
    pub fn detail_id(&self) -> Option<&str> {
        self.playlist_id
            .as_deref()
            .filter(|s| !s.is_empty())
            .or_else(|| self.browse_id.as_deref().filter(|s| !s.is_empty()))
    }
}

/// One entry of a search, shelf or top result.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "kind", content = "item", rename_all = "camelCase")]
pub enum MediaItem {
    Song(Track),
    Album(AlbumSummary),
    Playlist(PlaylistSummary),
    Artist(ArtistSummary),
}

/// Results of [`crate::YtMusic::search`].
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct SearchResults {
    /// The "Top result" card, when YouTube shows one.
    pub top: Option<MediaItem>,
    pub songs: Vec<Track>,
    pub albums: Vec<AlbumSummary>,
    pub artists: Vec<ArtistSummary>,
    pub playlists: Vec<PlaylistSummary>,
}

/// One row of the home page ("Quick picks", "New releases", ...).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct Shelf {
    pub title: String,
    /// Small line above the title, when present.
    pub strapline: Option<String>,
    /// Browse id behind the shelf's "More" button, when present.
    pub more_browse_id: Option<String>,
    pub items: Vec<MediaItem>,
}

/// One page of the home feed.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct HomePage {
    pub shelves: Vec<Shelf>,
    /// Token for [`crate::YtMusic::home_page`] to load the next shelves.
    pub continuation: Option<String>,
}

/// An artist page.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct ArtistPage {
    pub artist: ArtistSummary,
    /// The wide header picture, at the size YouTube sent it.
    pub banner_url: Option<String>,
    /// Subscriber count text ("7.21M"), when shown.
    pub subscribers: Option<String>,
    /// Monthly audience text ("67.1M monthly audience"), when shown.
    pub monthly_audience: Option<String>,
    pub description: Option<String>,
    pub top_songs: Vec<Track>,
    pub albums: Vec<AlbumSummary>,
    pub singles: Vec<AlbumSummary>,
    pub videos: Vec<Track>,
    pub playlists: Vec<PlaylistSummary>,
    /// "Fans might also like".
    pub related: Vec<ArtistSummary>,
    /// Browse id + params of the "all songs" list, for [`crate::YtMusic::artist_songs`].
    pub songs_browse_id: Option<String>,
    pub songs_params: Option<String>,
}

/// An album page.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct AlbumPage {
    pub album: AlbumSummary,
    pub description: Option<String>,
    pub tracks: Vec<Track>,
}

/// A playlist page with all its tracks (continuations already followed).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct PlaylistPage {
    pub playlist: PlaylistSummary,
    pub description: Option<String>,
    pub tracks: Vec<Track>,
}

/// A page of tracks plus the token for the next page.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct TracksPage {
    pub tracks: Vec<Track>,
    pub continuation: Option<String>,
}

/// A full-length audio stream ready to play.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct StreamSource {
    pub url: String,
    /// Full mime type, e.g. `audio/webm; codecs="opus"`.
    pub mime: String,
    /// Codec named in the mime type (`opus`, `mp4a.40.2`, ...).
    pub codecs: Option<String>,
    /// Average bitrate in bits per second (0 when unknown).
    pub bitrate: u32,
    pub itag: Option<u32>,
    /// Unix time (seconds) after which the URL stops working, when known.
    pub expires_at: Option<u64>,
    pub content_length: Option<u64>,
    pub approx_duration_ms: Option<u64>,
    /// Which source produced it: "YouTube Music (VISIONOS)", "Invidious (host)", ...
    pub provider: String,
    /// User agent the URL was issued for; send it when fetching the audio.
    pub user_agent: Option<String>,
}

/// One timed word inside a lyric line.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct WordTiming {
    pub text: String,
    pub start_ms: i64,
    pub end_ms: i64,
}

/// One synced lyric line.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct LyricLine {
    pub time_ms: i64,
    /// When the line ends, if the source says so.
    pub end_ms: Option<i64>,
    pub text: String,
    /// Real word timings, when the source had them. Each word keeps its
    /// trailing space, so joining the texts gives the line.
    pub word_timings: Option<Vec<WordTiming>>,
    /// Background vocals sung over the line, when the source has them apart.
    pub background: Option<String>,
}

/// Lyrics for one track.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct Lyrics {
    pub id: String,
    pub plain_lyrics: Option<String>,
    pub synced_lyrics: Option<String>,
    /// Parsed synced lines, sorted by time; empty when only plain text exists.
    pub lines: Vec<LyricLine>,
    pub instrumental: bool,
    /// The provider the lyrics come from ("lrc.red", "LRCLIB", "YouTube Music", ...).
    pub source: String,
}

impl Lyrics {
    pub fn has_synced(&self) -> bool {
        !self.lines.is_empty()
    }
}

/// Health of a source, as tracked by the circuit breaker.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum HealthStatus {
    Online,
    Degraded,
    Offline,
}

/// Kind of stream source.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SourceKind {
    YoutubeMusic,
    Invidious,
    Piped,
    Lrclib,
    /// Any other lyrics provider.
    Lyrics,
}

/// Snapshot of one source's health.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SourceHealth {
    pub name: String,
    pub url: String,
    pub kind: SourceKind,
    pub status: HealthStatus,
    /// Latency of the last successful call, in milliseconds.
    pub latency_ms: Option<u64>,
    pub consecutive_failures: u32,
    /// Unix time (ms) until which the circuit stays open; `None` when closed.
    pub open_until_ms: Option<u64>,
    /// Unix time (ms) of the last recorded call; `None` when never used.
    pub last_checked_ms: Option<u64>,
    pub last_error: Option<String>,
}
