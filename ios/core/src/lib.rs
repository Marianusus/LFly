//! LFly's core for iPhone, called from Swift through UniFFI: YouTube Music (home, search, albums,
//! playlists), full-length streams in a format AVPlayer plays (AAC first, never Opus/WebM) and
//! synced lyrics. It wraps the desktop's lfly-yt, so both apps resolve songs the same way.
//!
//! A track crosses the bridge as [Song] with its original JSON kept in `raw`, so nothing lfly-yt
//! knows about it is lost on the way back (stream, lyrics).

use std::sync::Arc;

use lfly_yt::{AudioPreference, MediaItem, Track, YtMusic};

uniffi::setup_scaffolding!();

mod connect;
pub use connect::*;

// lfly-core's library, its three-way merge and its sync over LFly Connect, compiled here from the
// desktop's own files (lfly-core itself brings the desktop's audio engine, ONNX and MPRIS, which
// have no place on a phone). One source: a fix there is a fix here. They name each other as
// `crate::library` and so on, hence at the root.
#[path = "../../../desktop/crates/lfly-core/src/lang.rs"]
#[allow(dead_code)]
mod lang;
#[path = "../../../desktop/crates/lfly-core/src/library.rs"]
#[allow(dead_code)]
mod library;
#[path = "../../../desktop/crates/lfly-core/src/library_merge.rs"]
#[allow(dead_code)]
mod library_merge;
#[path = "../../../desktop/crates/lfly-core/src/libsync.rs"]
#[allow(dead_code)]
mod libsync;
#[path = "../../../desktop/crates/lfly-core/src/spotify_account.rs"]
#[allow(dead_code)]
mod spotify_account;

/// The web player's token, as the iPhone's web view read it from open.spotify.com.
#[derive(Debug, Clone, uniffi::Record)]
pub struct SpotifyToken {
    pub access_token: String,
    pub expires_at_ms: Option<i64>,
    pub client_id: Option<String>,
    pub user_agent: String,
}

/// What a Spotify import did.
#[derive(Debug, Clone, uniffi::Record)]
pub struct SpotifySummary {
    pub playlists: u32,
    pub songs: u32,
    pub liked: u32,
    pub new_favorites: u32,
    /// Spotify's account name, when it said.
    pub account_name: Option<String>,
}

/// Brings the Spotify account's playlists (as `spl_<id>`) and Liked Songs (into the favourites)
/// into the library in [library_dir], as the phone and the computer do (lfly-core's
/// spotify_account). [config_dir] keeps the session.
#[uniffi::export(async_runtime = "tokio")]
pub async fn spotify_import(config_dir: String, library_dir: String, sp_dc: String, token: SpotifyToken) -> Result<SpotifySummary> {
    use spotify_account as sa;
    sa::set_config_dir(config_dir.into());
    let mut saved = sa::load().filter(|s| s.sp_dc == sp_dc).unwrap_or_default();
    saved.sp_dc = sp_dc;
    saved.expired = false;
    sa::save(&saved).map_err(|message| CoreError::Failed { message })?;
    let expires = token.expires_at_ms.unwrap_or_else(|| {
        std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_millis() as i64) + 50 * 60_000
    });
    sa::set_token(token.access_token, expires, token.client_id, token.user_agent);
    let http = reqwest::Client::new();
    let summary = sa::sync(&http, std::path::Path::new(&library_dir), |_, _| {}).await.map_err(|e| CoreError::Failed { message: e.to_string() })?;
    Ok(SpotifySummary {
        playlists: summary.playlists as u32,
        songs: summary.songs as u32,
        liked: summary.liked as u32,
        new_favorites: summary.new_favorites as u32,
        account_name: sa::load().and_then(|s| s.name),
    })
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    #[error("{message}")]
    Failed { message: String },
}

impl From<lfly_yt::Error> for CoreError {
    fn from(e: lfly_yt::Error) -> Self {
        CoreError::Failed { message: e.to_string() }
    }
}

type Result<T> = std::result::Result<T, CoreError>;

/// A song or video.
#[derive(Debug, Clone, uniffi::Record)]
pub struct Song {
    pub id: String,
    pub title: String,
    pub artist: String,
    pub album: Option<String>,
    pub duration_ms: i64,
    pub cover_url: Option<String>,
    pub is_video: bool,
    /// lfly-yt's own JSON of the track, handed back for streams and lyrics.
    pub raw: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ItemKind {
    Song,
    Album,
    Playlist,
    Artist,
}

/// A card on a shelf or in search results.
#[derive(Debug, Clone, uniffi::Record)]
pub struct Item {
    pub kind: ItemKind,
    /// What opens it: browse id (album, artist) or playlist id; the song's id for songs.
    pub id: String,
    pub title: String,
    pub subtitle: Option<String>,
    pub cover_url: Option<String>,
    pub song: Option<Song>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct Shelf {
    pub title: String,
    pub items: Vec<Item>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SearchResult {
    pub top: Option<Item>,
    pub songs: Vec<Song>,
    pub albums: Vec<Item>,
    pub artists: Vec<Item>,
    pub playlists: Vec<Item>,
}

/// An album or playlist page.
#[derive(Debug, Clone, uniffi::Record)]
pub struct SongList {
    pub title: String,
    pub subtitle: Option<String>,
    pub cover_url: Option<String>,
    pub songs: Vec<Song>,
}

/// An artist's page.
#[derive(Debug, Clone, uniffi::Record)]
pub struct Artist {
    pub name: String,
    pub cover_url: Option<String>,
    pub subtitle: Option<String>,
    pub description: Option<String>,
    pub songs: Vec<Song>,
    pub albums: Vec<Item>,
    pub singles: Vec<Item>,
    pub playlists: Vec<Item>,
}

/// The YouTube Music session the sign-in page left (cookies and the page's ids).
#[derive(Debug, Clone, uniffi::Record)]
pub struct Session {
    pub cookie: String,
    pub data_sync_id: Option<String>,
    pub page_id: Option<String>,
    pub auth_user: Option<String>,
    pub visitor_data: Option<String>,
}

/// Who is signed in.
#[derive(Debug, Clone, uniffi::Record)]
pub struct Profile {
    pub name: String,
    pub email: String,
    pub avatar_url: Option<String>,
}

/// A stream to hand to AVPlayer.
#[derive(Debug, Clone, uniffi::Record)]
pub struct AudioStream {
    pub url: String,
    pub mime: String,
    /// Send as the User-Agent header when the source asks for it.
    pub user_agent: Option<String>,
    pub provider: String,
    pub bitrate: u32,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct LyricLine {
    pub time_ms: i64,
    pub text: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SongLyrics {
    /// Timed lines; empty when only plain text exists.
    pub lines: Vec<LyricLine>,
    pub plain: Option<String>,
    pub source: String,
}

fn song_of(t: &Track) -> Song {
    song(t)
}

fn song(t: &Track) -> Song {
    Song {
        id: t.id.clone(),
        title: t.title.clone(),
        artist: t.artist.clone(),
        album: t.album.clone(),
        duration_ms: t.duration_ms,
        cover_url: t.cover_url.clone(),
        is_video: t.is_video,
        raw: serde_json::to_string(t).unwrap_or_default(),
    }
}

fn track(s: &Song) -> Result<Track> {
    serde_json::from_str(&s.raw).map_err(|e| CoreError::Failed { message: e.to_string() })
}

fn item(m: &MediaItem) -> Option<Item> {
    Some(match m {
        MediaItem::Song(t) => Item {
            kind: ItemKind::Song,
            id: t.id.clone(),
            title: t.title.clone(),
            subtitle: Some(t.artist.clone()),
            cover_url: t.cover_url.clone(),
            song: Some(song(t)),
        },
        MediaItem::Album(a) => Item {
            kind: ItemKind::Album,
            id: a.browse_id.clone()?,
            title: a.title.clone(),
            subtitle: a.artist.clone(),
            cover_url: a.thumbnail_url.clone(),
            song: None,
        },
        MediaItem::Playlist(p) => Item {
            kind: ItemKind::Playlist,
            id: p.detail_id()?.to_string(),
            title: p.title.clone(),
            subtitle: p.author.clone(),
            cover_url: p.thumbnail_url.clone(),
            song: None,
        },
        MediaItem::Artist(a) => Item {
            kind: ItemKind::Artist,
            id: a.browse_id.clone().or_else(|| a.channel_id.clone())?,
            title: a.name.clone(),
            subtitle: a.subtitle.clone(),
            cover_url: a.thumbnail_url.clone(),
            song: None,
        },
    })
}

/// The one object Swift holds: the YouTube Music client and its runtime.
#[derive(uniffi::Object)]
pub struct Core {
    yt: YtMusic,
    /// The account's LFly Connect secret, while signed in.
    secret: std::sync::Mutex<Option<String>>,
}

#[uniffi::export(async_runtime = "tokio")]
impl Core {
    #[uniffi::constructor]
    pub fn new() -> Result<Arc<Self>> {
        let yt = YtMusic::new()?;
        // AVPlayer plays AAC (and Monochrome's AAC/FLAC), not Opus in WebM.
        yt.set_preference(AudioPreference::AacFirst);
        Ok(Arc::new(Self { yt, secret: Default::default() }))
    }

    pub async fn artist(&self, browse_id: String) -> Result<Artist> {
        let p = self.yt.artist(&browse_id).await?;
        let albums = |v: &[lfly_yt::AlbumSummary]| v.iter().filter_map(|a| item(&MediaItem::Album(a.clone()))).collect();
        Ok(Artist {
            name: p.artist.name.clone(),
            cover_url: p.banner_url.clone().or_else(|| p.artist.thumbnail_url.clone()),
            subtitle: p.monthly_audience.clone().or_else(|| p.subscribers.clone()),
            description: p.description.clone(),
            songs: p.top_songs.iter().map(song).collect(),
            albums: albums(&p.albums),
            singles: albums(&p.singles),
            playlists: p.playlists.iter().filter_map(|x| item(&MediaItem::Playlist(x.clone()))).collect(),
        })
    }

    /// Uses a YouTube Music session (from the sign-in page) for every request, after checking
    /// it with YouTube Music; `None` signs out.
    pub async fn set_session(&self, session: Option<Session>) -> Result<Option<Profile>> {
        let Some(s) = session else {
            self.yt.set_account(None);
            *self.secret.lock().unwrap() = None;
            return Ok(None);
        };
        let account = lfly_yt::Account {
            cookie: s.cookie,
            data_sync_id: s.page_id.clone().or_else(|| lfly_yt::account::normalize_data_sync_id(s.data_sync_id.as_deref())),
            page_id: s.page_id,
            auth_user: s.auth_user,
            visitor_data: s.visitor_data,
        };
        if lfly_yt::account::sapisid_from(&account.cookie).is_none() {
            return Err(CoreError::Failed { message: "no Google session".into() });
        }
        let info = self.yt.account_info(&account).await?;
        *self.secret.lock().unwrap() = account.connect_secret();
        self.yt.set_account(Some(account));
        Ok(Some(Profile { name: info.name, email: info.email, avatar_url: info.avatar_url }))
    }

    /// The signed-in account's liked songs on YouTube Music ("LM").
    pub async fn liked_songs(&self) -> Result<Vec<Song>> {
        Ok(self.yt.playlist("LM").await?.tracks.iter().map(song).collect())
    }

    /// The signed-in account's playlists on YouTube Music.
    pub async fn account_playlists(&self) -> Result<Vec<Item>> {
        Ok(self.yt.library_playlists().await?.into_iter().filter_map(|p| item(&MediaItem::Playlist(p))).collect())
    }

    /// What YouTube Music plays after this song (autoplay, the ∞ in the queue).
    pub async fn up_next(&self, song: Song) -> Result<Vec<Song>> {
        let t = track(&song)?;
        let Some(id) = t.video_id() else { return Ok(Vec::new()) };
        Ok(self.yt.up_next(id).await?.iter().filter(|x| x.id != song.id).map(song_of).collect())
    }

    /// The signed-in account's LFly Connect secret (devices of the same account trust each other).
    pub fn connect_secret(&self) -> Option<String> {
        self.secret.lock().unwrap().clone()
    }

    pub async fn home(&self) -> Result<Vec<Shelf>> {
        let shelves = self.yt.home().await?;
        Ok(shelves
            .iter()
            .map(|s| Shelf { title: s.title.clone(), items: s.items.iter().filter_map(item).collect() })
            .filter(|s| !s.items.is_empty())
            .collect())
    }

    pub async fn search(&self, query: String) -> Result<SearchResult> {
        let r = self.yt.search(&query).await?;
        let wrap = |m: MediaItem| item(&m);
        Ok(SearchResult {
            top: r.top.as_ref().and_then(item),
            songs: r.songs.iter().map(song).collect(),
            albums: r.albums.into_iter().filter_map(|a| wrap(MediaItem::Album(a))).collect(),
            artists: r.artists.into_iter().filter_map(|a| wrap(MediaItem::Artist(a))).collect(),
            playlists: r.playlists.into_iter().filter_map(|p| wrap(MediaItem::Playlist(p))).collect(),
        })
    }

    pub async fn album(&self, browse_id: String) -> Result<SongList> {
        let p = self.yt.album(&browse_id).await?;
        Ok(SongList {
            title: p.album.title.clone(),
            subtitle: p.album.artist.clone(),
            cover_url: p.album.thumbnail_url.clone(),
            // Album tracks often carry no cover of their own: the album's.
            songs: p
                .tracks
                .iter()
                .map(|t| {
                    let mut s = song(t);
                    if s.cover_url.is_none() {
                        s.cover_url = p.album.thumbnail_url.clone();
                    }
                    s
                })
                .collect(),
        })
    }

    pub async fn playlist(&self, id: String) -> Result<SongList> {
        let p = self.yt.playlist(&id).await?;
        Ok(SongList {
            title: p.playlist.title.clone(),
            subtitle: p.playlist.author.clone(),
            cover_url: p.playlist.thumbnail_url.clone(),
            songs: p.tracks.iter().map(song).collect(),
        })
    }

    /// The full-length stream (YouTube Music or Monochrome, as the desktop picks them).
    pub async fn stream(&self, song: Song) -> Result<AudioStream> {
        let s = self.yt.resolve_stream(&track(&song)?).await?;
        Ok(AudioStream { url: s.url, mime: s.mime, user_agent: s.user_agent, provider: s.provider, bitrate: s.bitrate })
    }

    pub async fn lyrics(&self, song: Song) -> Result<Option<SongLyrics>> {
        let Some(l) = self.yt.lyrics(&track(&song)?).await? else { return Ok(None) };
        Ok(Some(SongLyrics {
            lines: l.lines.iter().map(|x| LyricLine { time_ms: x.time_ms, text: x.text.clone() }).collect(),
            plain: l.plain_lyrics.clone(),
            source: l.source.clone(),
        }))
    }
}
