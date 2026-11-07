//! YouTube Music for the window: the client's results mapped onto the shapes the pages use
//! (`src/lib/api.ts`), so the pages never depend on the client's internals.

use lfly_yt::{AlbumSummary, ArtistSummary, MediaItem, PlaylistSummary, Track, YtMusic};
use serde::Serialize;
use tauri::State;

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Item {
    kind: &'static str,
    id: String,
    title: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    subtitle: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    cover_url: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    track: Option<Track>,
}

#[derive(Serialize)]
pub struct Shelf {
    title: String,
    items: Vec<Item>,
}

#[derive(Serialize)]
pub struct SearchResults {
    top: Option<Item>,
    songs: Vec<Track>,
    albums: Vec<Item>,
    artists: Vec<Item>,
    playlists: Vec<Item>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ArtistPage {
    name: String,
    cover_url: Option<String>,
    description: Option<String>,
    subscribers: Option<String>,
    songs: Vec<Track>,
    albums: Vec<Item>,
    singles: Vec<Item>,
    related: Vec<Item>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AlbumPage {
    title: String,
    artist: String,
    artist_id: Option<String>,
    year: Option<String>,
    cover_url: Option<String>,
    tracks: Vec<Track>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlaylistPage {
    title: String,
    author: Option<String>,
    description: Option<String>,
    cover_url: Option<String>,
    tracks: Vec<Track>,
}

/// One timed word; its text keeps the trailing space, so the words join into the line.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LyricWord {
    ms: i64,
    end_ms: i64,
    text: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LyricLine {
    /// Line start in milliseconds.
    ms: i64,
    #[serde(skip_serializing_if = "Option::is_none")]
    end_ms: Option<i64>,
    text: String,
    /// Only when the source had real word timings.
    #[serde(skip_serializing_if = "Option::is_none")]
    words: Option<Vec<LyricWord>>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Lyrics {
    synced: Option<Vec<LyricLine>>,
    plain: Option<String>,
    /// The provider that won ("BetterLyrics", "lrc.red", "KuGou", "LRCLIB", "YouTube Music", ...).
    source: String,
    #[serde(skip_serializing_if = "std::ops::Not::not")]
    instrumental: bool,
}

fn cover(url: &Option<String>) -> Option<String> {
    url.as_deref().filter(|u| !u.is_empty()).map(lfly_yt::upscale_thumbnail)
}

fn album_item(a: &AlbumSummary) -> Option<Item> {
    let id = a.detail_id()?.to_string();
    let kind = match a.kind.as_deref().map(str::to_lowercase).as_deref() {
        Some("single") | Some("singolo") => "Singolo",
        Some("ep") => "EP",
        _ => "Album",
    };
    let parts: Vec<&str> = [Some(kind), a.year.as_deref(), a.artist.as_deref()].into_iter().flatten().filter(|s| !s.is_empty()).collect();
    Some(Item { kind: "album", id, title: a.title.clone(), subtitle: Some(parts.join(" · ")), cover_url: cover(&a.thumbnail_url), track: None })
}

fn playlist_item(p: &PlaylistSummary) -> Option<Item> {
    let id = p.detail_id()?.to_string();
    let subtitle = match (&p.author, p.track_count) {
        (Some(a), Some(n)) => format!("{a} · {n} brani"),
        (Some(a), None) => a.clone(),
        (None, Some(n)) => format!("Playlist · {n} brani"),
        (None, None) => "Playlist".into(),
    };
    Some(Item { kind: "playlist", id, title: p.title.clone(), subtitle: Some(subtitle), cover_url: cover(&p.thumbnail_url), track: None })
}

fn artist_item(a: &ArtistSummary) -> Option<Item> {
    let id = a.browse_id.clone().or_else(|| a.channel_id.clone())?;
    Some(Item {
        kind: "artist",
        id,
        title: a.name.clone(),
        subtitle: Some(a.subtitle.clone().filter(|s| !s.is_empty()).unwrap_or_else(|| "Artista".into())),
        cover_url: cover(&a.thumbnail_url),
        track: None,
    })
}

fn song_item(t: &Track) -> Item {
    Item {
        kind: "song",
        id: t.id.clone(),
        title: t.title.clone(),
        subtitle: Some(t.artist.clone()),
        cover_url: t.cover_url.clone(),
        track: Some(t.clone()),
    }
}

fn item(m: &MediaItem) -> Option<Item> {
    match m {
        MediaItem::Song(t) => Some(song_item(t)),
        MediaItem::Album(a) => album_item(a),
        MediaItem::Playlist(p) => playlist_item(p),
        MediaItem::Artist(a) => artist_item(a),
    }
}

fn err(e: lfly_yt::Error) -> String {
    e.to_string()
}

#[tauri::command]
pub async fn yt_home(yt: State<'_, YtMusic>) -> Result<Vec<Shelf>, String> {
    let shelves = yt.home().await.map_err(err)?;
    Ok(shelves
        .into_iter()
        .map(|s| Shelf { title: s.title, items: s.items.iter().filter_map(item).collect() })
        .filter(|s| !s.items.is_empty())
        .collect())
}

#[tauri::command]
pub async fn yt_search(yt: State<'_, YtMusic>, query: String) -> Result<SearchResults, String> {
    let r = yt.search(&query).await.map_err(err)?;
    Ok(SearchResults {
        top: r.top.as_ref().and_then(item),
        songs: r.songs,
        albums: r.albums.iter().filter_map(album_item).collect(),
        artists: r.artists.iter().filter_map(artist_item).collect(),
        playlists: r.playlists.iter().filter_map(playlist_item).collect(),
    })
}

#[tauri::command]
pub async fn yt_suggestions(yt: State<'_, YtMusic>, query: String) -> Result<Vec<String>, String> {
    yt.suggestions(&query).await.map_err(err)
}

#[tauri::command]
pub async fn yt_artist(yt: State<'_, YtMusic>, id: String) -> Result<ArtistPage, String> {
    let p = yt.artist(&id).await.map_err(err)?;
    let mut songs = p.top_songs;
    // The full song list when the artist has one: "Popular" shows the first, the play button all.
    if let Some(browse) = p.songs_browse_id.as_deref() {
        if let Ok(more) = yt.artist_songs(browse, p.songs_params.as_deref(), 100).await {
            let have: std::collections::HashSet<_> = songs.iter().map(|t| t.id.clone()).collect();
            songs.extend(more.into_iter().filter(|t| !have.contains(&t.id)));
        }
    }
    Ok(ArtistPage {
        name: p.artist.name.clone(),
        cover_url: cover(&p.banner_url).or_else(|| cover(&p.artist.thumbnail_url)),
        description: p.description,
        subscribers: p.monthly_audience.or(p.subscribers),
        songs,
        albums: p.albums.iter().filter_map(album_item).collect(),
        singles: p.singles.iter().filter_map(album_item).collect(),
        related: p.related.iter().filter_map(artist_item).collect(),
    })
}

#[tauri::command]
pub async fn yt_album(yt: State<'_, YtMusic>, id: String) -> Result<AlbumPage, String> {
    let p = yt.album(&id).await.map_err(err)?;
    let cover_url = cover(&p.album.thumbnail_url);
    let tracks = p
        .tracks
        .into_iter()
        .map(|mut t| {
            // Album rows carry no cover of their own: the album's.
            if t.cover_url.is_none() {
                t.cover_url = cover_url.clone();
            }
            if t.album.is_none() {
                t.album = Some(p.album.title.clone());
            }
            if t.album_id.is_none() {
                t.album_id = Some(id.clone());
            }
            t
        })
        .collect();
    Ok(AlbumPage {
        title: p.album.title.clone(),
        artist: p.album.artist.clone().unwrap_or_default(),
        artist_id: p.album.artist_id.clone(),
        year: p.album.year.clone().or_else(|| p.album.release_year.map(|y| y.to_string())),
        cover_url,
        tracks,
    })
}

#[tauri::command]
pub async fn yt_playlist(yt: State<'_, YtMusic>, id: String) -> Result<PlaylistPage, String> {
    let p = yt.playlist(&id).await.map_err(err)?;
    Ok(PlaylistPage {
        title: p.playlist.title.clone(),
        author: p.playlist.author.clone(),
        description: p.description,
        cover_url: cover(&p.playlist.thumbnail_url),
        tracks: p.tracks,
    })
}

#[tauri::command]
pub async fn yt_radio(yt: State<'_, YtMusic>, track: Track) -> Result<Vec<Track>, String> {
    yt.up_next(&track.id).await.map_err(err)
}

#[tauri::command]
pub async fn yt_lyrics(yt: State<'_, YtMusic>, track: Track) -> Result<Option<Lyrics>, String> {
    let Some(l) = yt.lyrics(&track).await.map_err(err)? else { return Ok(None) };
    let synced: Vec<LyricLine> = l
        .lines
        .into_iter()
        .map(|line| LyricLine {
            ms: line.time_ms,
            end_ms: line.end_ms,
            text: line.text,
            words: line
                .word_timings
                .filter(|w| !w.is_empty())
                .map(|w| w.into_iter().map(|w| LyricWord { ms: w.start_ms, end_ms: w.end_ms, text: w.text }).collect()),
        })
        .collect();
    Ok(Some(Lyrics {
        synced: (!synced.is_empty()).then_some(synced),
        plain: l.plain_lyrics.filter(|p| !p.trim().is_empty()),
        source: l.source,
        instrumental: l.instrumental,
    }))
}

/// The signed-in listener's playlists on YouTube Music; empty when nobody is signed in.
#[tauri::command]
pub async fn yt_library(yt: State<'_, YtMusic>) -> Result<Vec<Item>, String> {
    Ok(yt.library_playlists().await.map_err(err)?.iter().filter_map(playlist_item).collect())
}

/// Thumbs up or down on YouTube Music (`liked` null removes it); does nothing when signed out.
#[tauri::command]
pub async fn yt_rate(yt: State<'_, YtMusic>, id: String, liked: Option<bool>) -> Result<(), String> {
    if yt.account().is_none() {
        return Ok(());
    }
    yt.rate(&id, liked).await.map_err(err)
}

#[tauri::command]
pub fn yt_health(yt: State<'_, YtMusic>) -> Vec<lfly_yt::SourceHealth> {
    yt.health()
}

#[cfg(test)]
mod live {
    //! Against the real YouTube Music: `cargo test -p lfly-desktop -- --ignored`.
    use super::*;

    #[tokio::test]
    #[ignore]
    async fn home_and_search_map_onto_openable_cards() {
        let yt = YtMusic::new().unwrap();
        let shelves = yt.home().await.unwrap();
        let cards: Vec<Item> = shelves.iter().flat_map(|s| s.items.iter().filter_map(item)).collect();
        assert!(cards.len() > 10, "home has cards");
        assert!(cards.iter().all(|c| !c.id.is_empty() && !c.title.is_empty()));
        assert!(cards.iter().any(|c| c.cover_url.is_some()));

        let r = yt.search("daft punk").await.unwrap();
        let artists: Vec<Item> = r.artists.iter().filter_map(artist_item).collect();
        let albums: Vec<Item> = r.albums.iter().filter_map(album_item).collect();
        assert!(!artists.is_empty() && !albums.is_empty());
        // An album card opens its page, with tracks to play.
        let album = yt.album(&albums[0].id).await.unwrap();
        assert!(!album.tracks.is_empty());
        let artist = yt.artist(&artists[0].id).await.unwrap();
        assert!(!artist.top_songs.is_empty() || !artist.albums.is_empty());
    }
}
