//! The network side: InnerTube requests, stream resolution with fallbacks, lyrics.

use crate::error::{Error, Result};
use crate::health::{now_ms, HealthRegistry};
use crate::lyrics;
use crate::model::*;
use crate::parse;
use crate::stream::{self, AudioPreference};
use regex::Regex;
use serde_json::{json, Value};
use std::collections::HashSet;
use std::hash::{BuildHasher, Hasher};
use std::sync::{Arc, LazyLock, Mutex};
use std::time::Duration;

const BASE_URL: &str = "https://music.youtube.com";
const WEB_REMIX_VERSION: &str = "1.20240722.01.00";
const DESKTOP_UA: &str =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

const SONGS_FILTER: &str = "EgWKAQIIAWoKEAUQAxAEEAkQBQ==";
const ALBUMS_FILTER: &str = "EgWKAQIYAWoKEAkQChAFEAMQBA==";
const PLAYLISTS_FILTER: &str = "EgWKAQIoAWoKEAkQChAFEAMQBA==";
const ARTISTS_FILTER: &str = "EgWKAQIgAWoKEAkQChAFEAMQBA==";
const MAX_SEARCH_PAGES: usize = 5;

const YTM_PLAYER_URL: &str =
    "https://music.youtube.com/youtubei/v1/player?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8&prettyPrint=false";
const ANDROID_MUSIC_UA: &str =
    "com.google.android.apps.youtube.music/6.42.51 (Linux; U; Android 12; en_US; Pixel 5 Build/SQ3A.220705.004)";
/// ANDROID_MUSIC version the phone uses for lyrics pages.
const ANDROID_MUSIC_BROWSE_VERSION: &str = "8.12.54";
const IOS_UA: &str = "com.google.ios.youtube/19.16.2 (iPhone14,3; U; CPU iOS 15_6 like Mac OS X)";

// Guest VISIONOS client: direct (non-ciphered) urls, no API key.
const VISIONOS_PLAYER_URL: &str = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false&alt=json";
const VISITOR_URL: &str = "https://www.youtube.com/sw.js_data";
/// User agent the VISIONOS stream urls are issued for.
pub const VISIONOS_UA: &str =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15";
const VISIONOS_VERSION: &str = "1.02";
const VISIONOS_CLIENT_ID: &str = "101";
const CPN_ALPHABET: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

/// Time budget of each fallback tier (Invidious, Piped).
const TIER_TIMEOUT: Duration = Duration::from_secs(8);
/// Time budget of one fallback instance (blackout threshold).
const INSTANCE_TIMEOUT: Duration = Duration::from_millis(3_500);
const PROBE_TIMEOUT: Duration = Duration::from_secs(5);

static VISITOR_RE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r#""(Cg[A-Za-z0-9%_+=-]{20,})""#).unwrap());

/// Default Invidious instances (same list as the phone).
pub const DEFAULT_INVIDIOUS: &[&str] = &[
    "https://invidious.f5.si",
    "https://inv.nadeko.net",
    "https://yewtu.be",
    "https://invidious.nerdvpn.de",
    "https://invidious.tiekoetter.com",
    "https://invidious.protokolla.fi",
    "https://yt.chocolatemoo53.com",
];

/// Default Piped API instances (same list as the phone).
pub const DEFAULT_PIPED: &[&str] = &[
    "https://pipedapi.kavin.rocks",
    "https://pipedapi.ducks.party",
    "https://api.piped.privacydev.net",
    "https://piped-api.lunar.icu",
    "https://pipedapi.leptons.xyz",
    "https://pipedapi.drgns.space",
];

/// Settings for [`YtMusic::with_config`].
#[derive(Debug, Clone)]
pub struct YtMusicConfig {
    /// Codec tried first for streams.
    pub preference: AudioPreference,
    pub invidious: Vec<String>,
    pub piped: Vec<String>,
    pub lrclib_url: String,
    /// Overall time limit of one HTTP request.
    pub request_timeout: Duration,
}

impl Default for YtMusicConfig {
    fn default() -> Self {
        Self {
            preference: AudioPreference::default(),
            invidious: DEFAULT_INVIDIOUS.iter().map(|s| s.to_string()).collect(),
            piped: DEFAULT_PIPED.iter().map(|s| s.to_string()).collect(),
            lrclib_url: lyrics::LRCLIB_URL.to_string(),
            request_timeout: Duration::from_secs(15),
        }
    }
}

#[derive(Debug, Clone, Copy)]
enum PlayerClient {
    AndroidMusic,
    Ios,
}

struct Inner {
    http: reqwest::Client,
    config: YtMusicConfig,
    health: HealthRegistry,
    /// visitorData of the WEB_REMIX session (home continuations need it).
    web_visitor: Mutex<Option<String>>,
    /// visitorData for the VISIONOS player.
    vision_visitor: tokio::sync::Mutex<Option<String>>,
    /// The signed-in session WEB_REMIX requests are made as, if any.
    account: Mutex<Option<crate::Account>>,
    /// Lyrics providers' tokens, spacing and cooldowns.
    lyrics: lyrics::State,
    /// Codec tried first; starts as the config's, changeable while running (a setting).
    preference: Mutex<AudioPreference>,
}

/// YouTube Music client. Cheap to clone: clones share the HTTP pool, the
/// visitor ids and the health registry.
#[derive(Clone)]
pub struct YtMusic {
    inner: Arc<Inner>,
}

impl std::fmt::Debug for YtMusic {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("YtMusic").field("config", &self.inner.config).finish_non_exhaustive()
    }
}

fn random_cpn() -> String {
    let mut out = String::with_capacity(16);
    let mut seed = std::collections::hash_map::RandomState::new().build_hasher();
    seed.write_u64(now_ms());
    let mut x = seed.finish() | 1;
    for _ in 0..16 {
        // xorshift64
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        out.push(CPN_ALPHABET[(x % CPN_ALPHABET.len() as u64) as usize] as char);
    }
    out
}

fn host_of(url: &str) -> String {
    reqwest::Url::parse(url).ok().and_then(|u| u.host_str().map(str::to_string)).unwrap_or_else(|| url.to_string())
}

/// Strips `yt_` and checks the id shape.
fn video_id_of(id: &str) -> Option<String> {
    let raw = id.trim();
    let raw = raw.strip_prefix("yt_").unwrap_or(raw);
    parse::is_valid_video_id(raw).then(|| raw.to_string())
}

impl YtMusic {
    /// A client with the default settings.
    pub fn new() -> Result<Self> {
        Self::with_config(YtMusicConfig::default())
    }

    /// A client with custom settings.
    pub fn with_config(config: YtMusicConfig) -> Result<Self> {
        let http = reqwest::Client::builder()
            .timeout(config.request_timeout)
            .connect_timeout(Duration::from_secs(8))
            .gzip(true)
            .build()?;
        let health = HealthRegistry::default();
        health.register("YouTube Music", BASE_URL, SourceKind::YoutubeMusic);
        for url in &config.invidious {
            health.register(&format!("Invidious ({})", host_of(url)), url, SourceKind::Invidious);
        }
        for url in &config.piped {
            health.register(&format!("Piped ({})", host_of(url)), url, SourceKind::Piped);
        }
        lyrics::register(&health, &config.lrclib_url);
        let preference = Mutex::new(config.preference);
        Ok(Self {
            inner: Arc::new(Inner {
                http,
                preference,
                config,
                health,
                web_visitor: Mutex::new(None),
                vision_visitor: tokio::sync::Mutex::new(None),
                account: Mutex::new(None),
                lyrics: lyrics::State::default(),
            }),
        })
    }

    /// Health of every source (YouTube Music, fallback instances, LRCLIB).
    pub fn health(&self) -> Vec<SourceHealth> {
        self.inner.health.snapshot()
    }

    fn web_visitor(&self) -> Option<String> {
        self.inner.web_visitor.lock().unwrap_or_else(|e| e.into_inner()).clone()
    }

    // --- InnerTube plumbing --------------------------------------------------------------------

    async fn read_json(resp: reqwest::Response, endpoint: &str) -> Result<Value> {
        let status = resp.status();
        if !status.is_success() {
            return Err(Error::Status { status: status.as_u16(), endpoint: endpoint.to_string() });
        }
        let body = resp.bytes().await?;
        Ok(serde_json::from_slice(&body)?)
    }

    /// Signs every WEB_REMIX request (search, home, playlists…) as `account`, or anonymously with None.
    pub fn set_account(&self, account: Option<crate::Account>) {
        *self.inner.account.lock().unwrap_or_else(|e| e.into_inner()) = account;
    }

    pub fn account(&self) -> Option<crate::Account> {
        self.inner.account.lock().unwrap_or_else(|e| e.into_inner()).clone()
    }

    /// The codec tried first for the next streams resolved.
    pub fn set_preference(&self, preference: AudioPreference) {
        *self.inner.preference.lock().unwrap_or_else(|e| e.into_inner()) = preference;
    }

    pub fn preference(&self) -> AudioPreference {
        *self.inner.preference.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// POST to a WEB_REMIX endpoint (`search`, `browse`, `next`, ...), signed when an account is set.
    async fn post_web(&self, endpoint: &str, body: Value) -> Result<Value> {
        let account = self.account();
        self.post_web_as(endpoint, body, account.as_ref()).await
    }

    async fn post_web_as(&self, endpoint: &str, mut body: Value, account: Option<&crate::Account>) -> Result<Value> {
        let signed = account.and_then(|a| a.sapisid().map(|s| (a, s)));
        let visitor = signed.as_ref().and_then(|(a, _)| a.visitor_data.clone()).or_else(|| self.web_visitor());
        let mut client = json!({"clientName": "WEB_REMIX", "clientVersion": WEB_REMIX_VERSION, "hl": "en", "gl": "US"});
        if let Some(v) = &visitor {
            client["visitorData"] = json!(v);
        }
        body["context"] = json!({ "client": client });
        let mut req = self
            .inner
            .http
            .post(format!("{BASE_URL}/youtubei/v1/{endpoint}?prettyPrint=false"))
            .header("Content-Type", "application/json")
            .header("User-Agent", DESKTOP_UA)
            .header("Origin", BASE_URL)
            .header("Referer", format!("{BASE_URL}/"));
        if let Some((account, sapisid)) = signed {
            if let Some(id) = &account.data_sync_id {
                body["context"]["user"] = json!({ "onBehalfOfUser": id });
            }
            let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0);
            req = req
                .header("Cookie", &account.cookie)
                .header("Authorization", crate::account::sapisid_hash(&sapisid, now))
                .header("X-Origin", BASE_URL)
                .header("X-Goog-AuthUser", account.auth_user.as_deref().unwrap_or("0"))
                .header("X-Youtube-Client-Name", "67")
                .header("X-Youtube-Client-Version", WEB_REMIX_VERSION);
            if let Some(page) = &account.page_id {
                req = req.header("X-Goog-PageId", page);
            }
        }
        if let Some(v) = &visitor {
            req = req.header("X-Goog-Visitor-Id", v);
        }
        let root = Self::read_json(req.json(&body).send().await?, endpoint).await?;
        if visitor.is_none() {
            if let Some(v) = root.pointer("/responseContext/visitorData").and_then(Value::as_str) {
                let mut slot = self.inner.web_visitor.lock().unwrap_or_else(|e| e.into_inner());
                if slot.is_none() {
                    *slot = Some(v.to_string());
                }
            }
        }
        Ok(root)
    }

    /// Who `account` is, from YouTube Music's account menu. Fails when Google no longer accepts
    /// the session (signed out, cookies expired).
    pub async fn account_info(&self, account: &crate::Account) -> Result<crate::AccountInfo> {
        let root = self.post_web_as("account/account_menu", json!({}), Some(account)).await?;
        parse::account_menu(&root).ok_or(Error::Parse("account menu"))
    }

    /// The playlists in the signed-in listener's library (their own and the saved ones).
    pub async fn library_playlists(&self) -> Result<Vec<PlaylistSummary>> {
        if self.account().is_none() {
            return Ok(Vec::new());
        }
        let mut root = self.browse_json("FEmusic_liked_playlists", None, None).await?;
        let mut found: Vec<PlaylistSummary> = Vec::new();
        for _ in 0..20 {
            for p in parse::library_playlists(&root) {
                if !found.iter().any(|f| f.playlist_id == p.playlist_id) {
                    found.push(p);
                }
            }
            let Some(next) = parse::continuation_token(&root) else { break };
            match self.browse_json("", None, Some(&next)).await {
                Ok(r) => root = r,
                Err(_) => break,
            }
        }
        found.retain(|p| !matches!(p.playlist_id.as_deref(), Some("LM") | Some("SE")));
        Ok(found)
    }

    /// Thumbs up (`Some(true)`), thumbs down (`Some(false)`) or neither on YouTube Music.
    pub async fn rate(&self, video_id: &str, liked: Option<bool>) -> Result<()> {
        let vid = video_id_of(video_id).ok_or_else(|| Error::InvalidInput(format!("bad video id {video_id}")))?;
        let endpoint = match liked {
            Some(true) => "like/like",
            Some(false) => "like/dislike",
            None => "like/removelike",
        };
        self.post_web(endpoint, json!({ "target": { "videoId": vid } })).await.map(|_| ())
    }

    async fn search_root(&self, query: Option<&str>, params: Option<&str>, continuation: Option<&str>) -> Result<Value> {
        let body = match continuation {
            Some(c) => json!({ "continuation": c }),
            None => {
                let mut b = json!({ "query": query.unwrap_or("") });
                if let Some(p) = params {
                    b["params"] = json!(p);
                }
                b
            }
        };
        self.post_web("search", body).await
    }

    /// Raw `browse` response.
    pub async fn browse_json(&self, browse_id: &str, params: Option<&str>, continuation: Option<&str>) -> Result<Value> {
        let mut body = json!({});
        if !browse_id.is_empty() {
            body["browseId"] = json!(browse_id);
        }
        if let Some(p) = params.filter(|p| !p.is_empty()) {
            body["params"] = json!(p);
        }
        if let Some(c) = continuation.filter(|c| !c.is_empty()) {
            body["continuation"] = json!(c);
        }
        self.post_web("browse", body).await
    }

    /// Raw `next` (watch next / radio) response for a video.
    pub async fn next_json(&self, video_id: &str) -> Result<Value> {
        let vid = video_id_of(video_id).ok_or_else(|| Error::InvalidInput(format!("bad video id {video_id}")))?;
        self.post_web("next", json!({ "videoId": vid, "playlistId": format!("RDAMVM{vid}"), "isAudioOnly": true }))
            .await
    }

    // --- search --------------------------------------------------------------------------------

    /// Everything for a search screen: top result, songs, albums, artists and
    /// playlists. Five requests run at once; only the general one must succeed.
    pub async fn search(&self, query: &str) -> Result<SearchResults> {
        let q = query.trim();
        if q.is_empty() {
            return Ok(SearchResults::default());
        }
        let (general, songs, albums, artists, playlists) = tokio::join!(
            self.search_root(Some(q), None, None),
            self.search_root(Some(q), Some(SONGS_FILTER), None),
            self.search_root(Some(q), Some(ALBUMS_FILTER), None),
            self.search_root(Some(q), Some(ARTISTS_FILTER), None),
            self.search_root(Some(q), Some(PLAYLISTS_FILTER), None),
        );
        let general = general?;
        let filtered = |r: Result<Value>| r.map_err(|e| log::debug!("filtered search failed: {e}")).ok();
        let songs = filtered(songs).map(|v| parse::search_tracks(&v)).unwrap_or_default();
        let albums = filtered(albums).map(|v| parse::search_albums(&v, 20)).unwrap_or_default();
        let artists = filtered(artists).map(|v| parse::search_artists(&v, 20)).unwrap_or_default();
        let playlists = filtered(playlists).map(|v| parse::search_playlists(&v, 20)).unwrap_or_default();
        Ok(SearchResults {
            top: parse::search_top_result(&general),
            songs: parse::merge_search_results(parse::search_tracks(&general), songs, 25),
            albums: parse::merge_albums(albums, parse::search_albums(&general, 20), 20),
            artists: parse::merge_artists(artists, parse::search_artists(&general, 20), 20),
            playlists: parse::merge_playlists(playlists, parse::search_playlists(&general, 20), 20),
        })
    }

    /// Follows search continuations until `limit` unique tracks (max 5 pages).
    async fn search_track_pages(&self, query: &str, params: Option<&str>, limit: usize) -> Result<Vec<Track>> {
        let mut out: Vec<Track> = Vec::new();
        let mut seen = HashSet::new();
        let mut continuation: Option<String> = None;
        for page in 0..MAX_SEARCH_PAGES {
            if out.len() >= limit {
                break;
            }
            let root = match self.search_root(Some(query), params, continuation.as_deref()).await {
                Ok(r) => r,
                Err(e) if page > 0 => {
                    log::debug!("search page {page} failed: {e}");
                    break;
                }
                Err(e) => return Err(e),
            };
            for t in parse::search_tracks(&root) {
                if seen.insert(t.id.clone()) {
                    out.push(t);
                }
            }
            continuation = parse::continuation_token(&root);
            if continuation.is_none() {
                break;
            }
        }
        out.truncate(limit);
        Ok(out)
    }

    /// Songs and videos for a query, with more pages than [`Self::search`]:
    /// general ranking merged with the song filter, like the phone's song tab.
    pub async fn search_songs(&self, query: &str, limit: usize) -> Result<Vec<Track>> {
        let q = query.trim();
        if q.is_empty() {
            return Ok(Vec::new());
        }
        let (general, songs) = tokio::join!(self.search_track_pages(q, None, limit), self.search_track_pages(q, Some(SONGS_FILTER), limit));
        let songs = songs.unwrap_or_default();
        let general = match general {
            Ok(g) => g,
            Err(e) if songs.is_empty() => return Err(e),
            Err(_) => Vec::new(),
        };
        Ok(parse::merge_search_results(general, songs, limit))
    }

    /// Artists for a query: artist filter first, general search as a safety net.
    pub async fn search_artists(&self, query: &str, limit: usize) -> Result<Vec<ArtistSummary>> {
        let q = query.trim();
        if q.is_empty() {
            return Ok(Vec::new());
        }
        let filtered = parse::search_artists(&self.search_root(Some(q), Some(ARTISTS_FILTER), None).await?, limit);
        if filtered.len() >= limit {
            return Ok(filtered);
        }
        let general = parse::search_artists(&self.search_root(Some(q), None, None).await?, limit);
        Ok(parse::merge_artists(filtered, general, limit))
    }

    /// Albums for a query: album filter first, general search as a safety net.
    pub async fn search_albums(&self, query: &str, limit: usize) -> Result<Vec<AlbumSummary>> {
        let q = query.trim();
        if q.is_empty() {
            return Ok(Vec::new());
        }
        let filtered = parse::search_albums(&self.search_root(Some(q), Some(ALBUMS_FILTER), None).await?, limit);
        if filtered.len() >= limit {
            return Ok(filtered);
        }
        let general = parse::search_albums(&self.search_root(Some(q), None, None).await?, limit);
        Ok(parse::merge_albums(filtered, general, limit))
    }

    /// Playlists for a query: playlist filter first, general search as a safety net.
    pub async fn search_playlists(&self, query: &str, limit: usize) -> Result<Vec<PlaylistSummary>> {
        let q = query.trim();
        if q.is_empty() {
            return Ok(Vec::new());
        }
        let filtered = parse::search_playlists(&self.search_root(Some(q), Some(PLAYLISTS_FILTER), None).await?, limit);
        if filtered.len() >= limit {
            return Ok(filtered);
        }
        let general = parse::search_playlists(&self.search_root(Some(q), None, None).await?, limit);
        Ok(parse::merge_playlists(filtered, general, limit))
    }

    /// Typeahead completions for the search box.
    pub async fn suggestions(&self, query: &str) -> Result<Vec<String>> {
        let q = query.trim();
        if q.is_empty() {
            return Ok(Vec::new());
        }
        let root = self.post_web("music/get_search_suggestions", json!({ "input": q })).await?;
        Ok(parse::search_suggestions(&root))
    }

    // --- home, artist, album, playlist, radio -------------------------------------------------

    /// One page of the anonymous home feed; pass the previous page's
    /// `continuation` to get the next shelves.
    pub async fn home_page(&self, continuation: Option<&str>) -> Result<HomePage> {
        let root = match continuation {
            Some(c) => self.browse_json("", None, Some(c)).await?,
            None => self.browse_json("FEmusic_home", None, None).await?,
        };
        Ok(parse::home_page(&root))
    }

    /// The home feed: the first page plus up to three continuation pages.
    pub async fn home(&self) -> Result<Vec<Shelf>> {
        let mut page = self.home_page(None).await?;
        let mut shelves = std::mem::take(&mut page.shelves);
        let mut token = page.continuation;
        for _ in 0..3 {
            let Some(t) = token.take() else { break };
            match self.home_page(Some(&t)).await {
                Ok(next) => {
                    shelves.extend(next.shelves);
                    token = next.continuation;
                }
                Err(e) => {
                    log::debug!("home continuation failed: {e}");
                    break;
                }
            }
        }
        Ok(shelves)
    }

    /// An artist page (`UC...` browse id).
    pub async fn artist(&self, browse_id: &str) -> Result<ArtistPage> {
        let id = browse_id.trim();
        if id.is_empty() {
            return Err(Error::InvalidInput("blank artist id".into()));
        }
        parse::artist_page(&self.browse_json(id, None, None).await?).ok_or(Error::Parse("artist"))
    }

    /// The artist's full song list (from [`ArtistPage::songs_browse_id`]),
    /// continuations followed, at most `limit` tracks.
    pub async fn artist_songs(&self, browse_id: &str, params: Option<&str>, limit: usize) -> Result<Vec<Track>> {
        let id = browse_id.trim();
        if id.is_empty() {
            return Err(Error::InvalidInput("blank songs id".into()));
        }
        let root = self.browse_json(id, params, None).await?;
        let first = parse::tracks_page(&root);
        self.follow_track_pages(id, first.tracks, first.continuation, limit).await
    }

    async fn follow_track_pages(&self, browse_id: &str, first: Vec<Track>, token: Option<String>, limit: usize) -> Result<Vec<Track>> {
        let mut seen: HashSet<String> = HashSet::new();
        let mut all: Vec<Track> = Vec::new();
        for t in first {
            if seen.insert(t.id.clone()) {
                all.push(t);
            }
        }
        let mut token = token;
        let mut guard = 0;
        while let Some(t) = token.take() {
            if all.len() >= limit || guard >= 60 {
                break;
            }
            guard += 1;
            let page = match self.browse_json(browse_id, None, Some(&t)).await {
                Ok(root) => parse::tracks_page(&root),
                Err(e) => {
                    log::debug!("continuation failed for {browse_id}: {e}");
                    break;
                }
            };
            let mut added = 0;
            for tr in page.tracks {
                if seen.insert(tr.id.clone()) {
                    all.push(tr);
                    added += 1;
                }
            }
            if added == 0 || page.continuation.as_deref() == Some(t.as_str()) {
                break;
            }
            token = page.continuation;
        }
        all.truncate(limit);
        Ok(all)
    }

    /// An album page (`MPRE...` browse id).
    pub async fn album(&self, browse_id: &str) -> Result<AlbumPage> {
        let id = browse_id.trim();
        if id.is_empty() {
            return Err(Error::InvalidInput("blank album id".into()));
        }
        parse::album_page(&self.browse_json(id, None, None).await?, Some(id)).ok_or(Error::Parse("album"))
    }

    /// A playlist with all its tracks (continuations followed, capped at 1000).
    /// Accepts the playlist id with or without the `VL` prefix.
    pub async fn playlist(&self, id: &str) -> Result<PlaylistPage> {
        self.playlist_limited(id, 1000).await
    }

    /// Same as [`Self::playlist`] with a custom track cap.
    pub async fn playlist_limited(&self, id: &str, max_tracks: usize) -> Result<PlaylistPage> {
        let raw = id.trim();
        if raw.is_empty() {
            return Err(Error::InvalidInput("blank playlist id".into()));
        }
        let browse_id = if raw.starts_with("VL") { raw.to_string() } else { format!("VL{raw}") };
        let root = self.browse_json(&browse_id, None, None).await?;
        let (mut page, token) = parse::playlist_page(&root, Some(&browse_id)).ok_or(Error::Parse("playlist"))?;
        let first = std::mem::take(&mut page.tracks);
        page.tracks = self.follow_track_pages(&browse_id, first, token, max_tracks).await?;
        Ok(page)
    }

    /// The radio queue for a track: what YouTube Music plays next
    /// (`yt_<id>` or a bare video id). The seed itself is usually first.
    pub async fn up_next(&self, id: &str) -> Result<Vec<Track>> {
        Ok(parse::watch_next_tracks(&self.next_json(id).await?, 50))
    }

    // --- lyrics --------------------------------------------------------------------------------

    /// The best lyrics for a track: every provider the phone uses (YouTube Music's own lyrics,
    /// Apple-timed lrc.red / BiniLyrics / BetterLyrics, LyricsPlus, Unison, KuGou, LRCLIB,
    /// Musixmatch) asked at once, ranked on title, artist and duration. Word timings when a
    /// source has them. `None` when nothing reliable exists (an instrumental gives `Some`
    /// with `instrumental` set and no lines).
    pub async fn lyrics(&self, track: &Track) -> Result<Option<Lyrics>> {
        Ok(lyrics::resolve(self, track).await)
    }

    pub(crate) fn http(&self) -> &reqwest::Client {
        &self.inner.http
    }

    pub(crate) fn health_registry(&self) -> &HealthRegistry {
        &self.inner.health
    }

    pub(crate) fn lyrics_state(&self) -> &lyrics::State {
        &self.inner.lyrics
    }

    pub(crate) fn lrclib_url(&self) -> &str {
        &self.inner.config.lrclib_url
    }

    /// A browse page as YouTube Music's Android app gets it (its lyrics page lists the lines,
    /// with times when YouTube has them). `None` on any failure.
    pub(crate) async fn browse_android_music(&self, browse_id: &str, params: Option<&str>) -> Option<Value> {
        let mut body = json!({
            "context": { "client": { "clientName": "ANDROID_MUSIC", "clientVersion": ANDROID_MUSIC_BROWSE_VERSION,
                                     "androidSdkVersion": 34, "osName": "Android", "hl": "en" } },
            "browseId": browse_id
        });
        if let Some(p) = params.filter(|p| !p.is_empty()) {
            body["params"] = json!(p);
        }
        let resp = self
            .inner
            .http
            .post(format!("{BASE_URL}/youtubei/v1/browse?prettyPrint=false"))
            .header("Content-Type", "application/json")
            .header("User-Agent", format!("com.google.android.apps.youtube.music/{ANDROID_MUSIC_BROWSE_VERSION} (Linux; U; Android 14) gzip"))
            .json(&body)
            .send()
            .await
            .ok()?;
        Self::read_json(resp, "ANDROID_MUSIC browse").await.map_err(|e| log::debug!("{e}")).ok()
    }

    // --- streams -------------------------------------------------------------------------------

    /// A full-length audio stream for a track, never a 30 s preview.
    ///
    /// Order: YouTube Music `/player` with the VISIONOS, ANDROID_MUSIC and IOS
    /// clients (each url range-probed), then Invidious and Piped raced against
    /// each other. Sources with an open circuit are skipped. Tracks without a
    /// `yt_` id are matched by a "title artist" search first.
    pub async fn resolve_stream(&self, track: &Track) -> Result<StreamSource> {
        let video_id = match track.video_id() {
            Some(v) => v.to_string(),
            None => {
                let query = format!("{} {}", track.title, track.artist);
                let found = self.search_songs(&query, 5).await.unwrap_or_default();
                found
                    .iter()
                    .find_map(|t| t.video_id().map(str::to_string))
                    .ok_or_else(|| Error::NoStream(format!("no YouTube match for '{}'", query.trim())))?
            }
        };

        let health = &self.inner.health;
        if health.is_available(BASE_URL) {
            let start = now_ms();
            match self.resolve_innertube(&video_id).await {
                Some(s) => {
                    health.record_success(BASE_URL, now_ms().saturating_sub(start));
                    return Ok(s);
                }
                None => health.record_failure(BASE_URL, &format!("no playable /player stream for {video_id}")),
            }
        } else {
            log::warn!("YouTube Music circuit open, going straight to fallbacks");
        }

        let invidious = tokio::time::timeout(TIER_TIMEOUT, self.resolve_invidious(&video_id));
        let piped = tokio::time::timeout(TIER_TIMEOUT, self.resolve_piped(&video_id));
        tokio::pin!(invidious, piped);
        let (mut inv_done, mut piped_done) = (false, false);
        loop {
            tokio::select! {
                r = &mut invidious, if !inv_done => {
                    inv_done = true;
                    if let Ok(Some(s)) = r { return Ok(s); }
                }
                r = &mut piped, if !piped_done => {
                    piped_done = true;
                    if let Ok(Some(s)) = r { return Ok(s); }
                }
                else => break,
            }
        }
        Err(Error::NoStream(format!("{video_id} ('{}')", track.title)))
    }

    /// The `/player` chain alone, without fallbacks.
    async fn resolve_innertube(&self, video_id: &str) -> Option<StreamSource> {
        let pref = self.preference();
        if let Some(root) = self.vision_player(video_id).await {
            if let Some(mut s) = stream::select_best_audio_format(&root, pref) {
                if self.probe_googlevideo(&s.url, VISIONOS_UA).await {
                    s.provider = "YouTube Music (VISIONOS)".into();
                    s.user_agent = Some(VISIONOS_UA.into());
                    return Some(s);
                }
                log::warn!("VISIONOS stream url refused for {video_id}, trying the other clients");
            }
        }
        for client in [PlayerClient::AndroidMusic, PlayerClient::Ios] {
            let Some(root) = self.player(video_id, client).await else { continue };
            let Some(mut s) = stream::select_best_audio_format(&root, pref) else { continue };
            let (name, ua) = match client {
                PlayerClient::AndroidMusic => ("ANDROID_MUSIC", ANDROID_MUSIC_UA),
                PlayerClient::Ios => ("IOS", IOS_UA),
            };
            if self.probe_googlevideo(&s.url, ua).await {
                s.provider = format!("YouTube Music ({name})");
                s.user_agent = Some(ua.into());
                return Some(s);
            }
        }
        None
    }

    async fn vision_visitor(&self, renew: bool) -> Option<String> {
        let mut slot = self.inner.vision_visitor.lock().await;
        if renew {
            *slot = None;
        }
        if slot.is_none() {
            let body = async {
                let r = self.inner.http.get(VISITOR_URL).header("User-Agent", VISIONOS_UA).send().await.ok()?;
                r.text().await.ok()
            }
            .await;
            *slot = body.and_then(|b| VISITOR_RE.captures(&b).map(|c| c[1].to_string()));
            if slot.is_none() {
                log::warn!("could not read a visitor id for VISIONOS");
            }
        }
        slot.clone()
    }

    async fn vision_player_once(&self, video_id: &str, visitor: Option<&str>) -> Option<Value> {
        let mut client = json!({"clientName": "VISIONOS", "clientVersion": VISIONOS_VERSION, "hl": "en", "gl": "US"});
        if let Some(v) = visitor {
            client["visitorData"] = json!(v);
        }
        let body = json!({
            "context": { "client": client },
            "videoId": video_id,
            "contentCheckOk": true,
            "racyCheckOk": true,
            "cpn": random_cpn(),
            "playbackContext": { "contentPlaybackContext": { "html5Preference": "HTML5_PREF_WANTS" } }
        });
        let mut req = self
            .inner
            .http
            .post(VISIONOS_PLAYER_URL)
            .header("Content-Type", "application/json")
            .header("Accept", "*/*")
            .header("Origin", "https://www.youtube.com")
            .header("User-Agent", VISIONOS_UA)
            .header("X-Youtube-Client-Name", VISIONOS_CLIENT_ID)
            .header("X-Youtube-Client-Version", VISIONOS_VERSION)
            .json(&body);
        if let Some(v) = visitor {
            req = req.header("X-Goog-Visitor-Id", v);
        }
        match req.send().await {
            Ok(r) => Self::read_json(r, "VISIONOS player").await.map_err(|e| log::warn!("VISIONOS /player: {e}")).ok(),
            Err(e) => {
                log::warn!("VISIONOS /player request failed for {video_id}: {e}");
                None
            }
        }
    }

    /// VISIONOS `/player`; on LOGIN_REQUIRED the visitor id is renewed once.
    async fn vision_player(&self, video_id: &str) -> Option<Value> {
        let visitor = self.vision_visitor(false).await;
        let root = self.vision_player_once(video_id, visitor.as_deref()).await?;
        if stream::playability_status(&root).as_deref() == Some("LOGIN_REQUIRED") {
            log::debug!("VISIONOS LOGIN_REQUIRED for {video_id}, renewing the visitor id");
            let visitor = self.vision_visitor(true).await;
            return self.vision_player_once(video_id, visitor.as_deref()).await;
        }
        Some(root)
    }

    /// ANDROID_MUSIC or IOS `/player` on music.youtube.com.
    async fn player(&self, video_id: &str, client: PlayerClient) -> Option<Value> {
        let (client_obj, ua) = match client {
            PlayerClient::AndroidMusic => (
                json!({"clientName": "ANDROID_MUSIC", "clientVersion": "6.42.51", "androidSdkVersion": 31,
                       "osName": "Android", "osVersion": "12", "hl": "en", "gl": "US"}),
                ANDROID_MUSIC_UA,
            ),
            PlayerClient::Ios => (
                json!({"clientName": "IOS", "clientVersion": "19.16.2", "deviceMake": "Apple", "deviceModel": "iPhone14,3",
                       "osName": "iPhone", "osVersion": "15.6.0.19G71", "hl": "en", "gl": "US"}),
                IOS_UA,
            ),
        };
        let body = json!({ "context": { "client": client_obj }, "videoId": video_id, "contentCheckOk": true, "racyCheckOk": true });
        let resp = self
            .inner
            .http
            .post(YTM_PLAYER_URL)
            .header("Content-Type", "application/json")
            .header("User-Agent", ua)
            .header("Origin", BASE_URL)
            .header("Referer", format!("{BASE_URL}/"))
            .json(&body)
            .send()
            .await
            .ok()?;
        Self::read_json(resp, "player").await.map_err(|e| log::debug!("{client:?} /player: {e}")).ok()
    }

    /// Range-probes a googlevideo url: 200/206 with an audio content type.
    async fn probe_googlevideo(&self, url: &str, user_agent: &str) -> bool {
        let resp = self
            .inner
            .http
            .get(url)
            .header("Range", "bytes=0-1")
            .header("User-Agent", user_agent)
            .header("Origin", "https://www.youtube.com")
            .header("Referer", "https://www.youtube.com/")
            .timeout(PROBE_TIMEOUT)
            .send()
            .await;
        match resp {
            Ok(r) => {
                let code = r.status().as_u16();
                let ct = r.headers().get("content-type").and_then(|v| v.to_str().ok()).unwrap_or("").to_lowercase();
                let ok = (code == 200 || code == 206) && ct.contains("audio");
                if !ok {
                    log::warn!("googlevideo probe refused: HTTP {code} ({ct})");
                }
                ok
            }
            Err(e) => {
                log::warn!("googlevideo probe failed: {e}");
                false
            }
        }
    }

    /// Generic probe for proxied streams: a media answer, not an HTML or JSON
    /// error page, and not a tiny body.
    async fn probe_media(&self, url: &str) -> bool {
        let Ok(r) = self
            .inner
            .http
            .get(url)
            .header("Range", "bytes=0-1024")
            .header("User-Agent", DESKTOP_UA)
            .timeout(PROBE_TIMEOUT)
            .send()
            .await
        else {
            return false;
        };
        let code = r.status().as_u16();
        if code >= 400 {
            return false;
        }
        let ct = r.headers().get("content-type").and_then(|v| v.to_str().ok()).unwrap_or("").to_lowercase();
        if ct.contains("text/html") || ct.contains("text/plain") || ct.contains("application/json") {
            return false;
        }
        let media = ["audio", "video", "application/octet-stream", "application/ogg"].iter().any(|m| ct.contains(m));
        let len = r.content_length().unwrap_or(0);
        media && !(1..=1024).contains(&len) && (r.status().is_success() || code == 206)
    }

    /// One Invidious instance: read the formats, then use the instance's own
    /// proxy (`latest_version?...&local=true`), because the googlevideo urls it
    /// lists are bound to the instance's address.
    async fn try_invidious(self, base: String, video_id: String) -> Option<StreamSource> {
        let pref = self.preference();
        let meta = async {
            let r = self.inner.http.get(format!("{base}/api/v1/videos/{video_id}")).send().await.ok()?;
            if !r.status().is_success() {
                return None;
            }
            let v: Value = r.json().await.ok()?;
            stream::invidious_best_format(&v, pref)
        }
        .await;
        let default_itag = if pref == AudioPreference::AacFirst { 140 } else { 251 };
        let (itag, mime, bitrate) = meta.unwrap_or((default_itag, String::new(), 0));
        let url = format!("{base}/latest_version?id={video_id}&itag={itag}&local=true");
        if !self.probe_media(&url).await {
            return None;
        }
        let mime = if mime.is_empty() {
            if itag == 251 { "audio/webm; codecs=\"opus\"" } else { "audio/mp4; codecs=\"mp4a.40.2\"" }.to_string()
        } else {
            mime
        };
        Some(StreamSource {
            codecs: stream::codecs_of(&mime),
            mime,
            bitrate,
            itag: Some(itag),
            provider: format!("Invidious ({})", host_of(&base)),
            url,
            ..StreamSource::default()
        })
    }

    /// One Piped instance: `/streams/<id>` lists proxied audio streams.
    async fn try_piped(self, base: String, video_id: String) -> Option<StreamSource> {
        let r = self.inner.http.get(format!("{base}/streams/{video_id}")).send().await.ok()?;
        if !r.status().is_success() {
            return None;
        }
        let v: Value = r.json().await.ok()?;
        let mut s = stream::piped_best_stream(&v, self.preference())?;
        if !self.probe_media(&s.url).await {
            return None;
        }
        s.provider = format!("Piped ({})", host_of(&base));
        Some(s)
    }

    /// Asks every available instance of `kind` at once and returns the first
    /// playable stream. Each instance's outcome feeds the circuit breaker;
    /// the slower ones are cancelled once a winner exists.
    async fn race_instances(&self, kind: SourceKind, video_id: &str) -> Option<StreamSource> {
        let health = self.inner.health.clone();
        let mut set = tokio::task::JoinSet::new();
        for base in health.ordered(kind) {
            if !health.is_available(&base) {
                continue;
            }
            let (me, vid, health) = (self.clone(), video_id.to_string(), health.clone());
            set.spawn(async move {
                let start = now_ms();
                let attempt = async {
                    match kind {
                        SourceKind::Piped => me.try_piped(base.clone(), vid).await,
                        _ => me.try_invidious(base.clone(), vid).await,
                    }
                };
                match tokio::time::timeout(INSTANCE_TIMEOUT * 2, attempt).await {
                    Ok(Some(s)) => {
                        health.record_success(&base, now_ms().saturating_sub(start));
                        Some(s)
                    }
                    Ok(None) => {
                        health.record_failure(&base, "no playable stream");
                        None
                    }
                    Err(_) => {
                        health.record_failure(&base, "timeout");
                        None
                    }
                }
            });
        }
        while let Some(done) = set.join_next().await {
            if let Ok(Some(s)) = done {
                return Some(s);
            }
        }
        None
    }

    async fn resolve_invidious(&self, video_id: &str) -> Option<StreamSource> {
        self.race_instances(SourceKind::Invidious, video_id).await
    }

    async fn resolve_piped(&self, video_id: &str) -> Option<StreamSource> {
        self.race_instances(SourceKind::Piped, video_id).await
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cpn_is_16_chars_from_the_alphabet() {
        let c = random_cpn();
        assert_eq!(c.len(), 16);
        assert!(c.bytes().all(|b| CPN_ALPHABET.contains(&b)));
    }

    #[tokio::test]
    #[ignore = "network; public instances come and go"]
    async fn live_fallback_sources() {
        let yt = YtMusic::new().unwrap();
        let (inv, piped) = tokio::join!(yt.resolve_invidious("5NV6Rdv1a3I"), yt.resolve_piped("5NV6Rdv1a3I"));
        println!("invidious: {:?}", inv.as_ref().map(|s| (&s.provider, s.itag)));
        println!("piped: {:?}", piped.as_ref().map(|s| (&s.provider, s.itag)));
        for h in yt.health() {
            println!("{:?} {} {:?} {:?}ms {:?}", h.kind, h.name, h.status, h.latency_ms, h.last_error);
        }
        assert!(inv.is_some() || piped.is_some(), "no fallback source answered");
    }

    #[test]
    fn video_ids_are_checked() {
        assert_eq!(video_id_of("yt_5NV6Rdv1a3I").as_deref(), Some("5NV6Rdv1a3I"));
        assert_eq!(video_id_of("spotify_1234"), None);
    }
}
