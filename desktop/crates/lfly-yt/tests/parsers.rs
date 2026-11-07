//! Offline parser tests on real InnerTube responses saved in `tests/fixtures`
//! (fetched once on 2026-11-06, tracking fields stripped).

use lfly_yt::stream::{self, AudioPreference};
use lfly_yt::{parse, MediaItem, Track};
use serde_json::Value;

fn load(name: &str) -> Value {
    let path = format!("{}/tests/fixtures/{name}", env!("CARGO_MANIFEST_DIR"));
    serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap()
}

fn assert_playable_ids(tracks: &[Track]) {
    for t in tracks {
        assert!(t.id.starts_with("yt_"), "{}", t.id);
        assert!(t.video_id().is_some(), "bad video id {}", t.id);
        assert!(!t.title.is_empty());
        assert_eq!(t.provider, "YouTube");
    }
}

#[test]
fn general_search_tracks_and_top_result() {
    let root = load("search_daft_punk.json");
    let tracks = parse::search_tracks(&root);
    assert!(tracks.len() >= 5, "{}", tracks.len());
    assert_playable_ids(&tracks);
    assert!(tracks.iter().all(|t| !parse::is_generic_artist(&t.artist)));
    assert!(tracks.iter().any(|t| t.artist == "Daft Punk" && t.streams.unwrap_or(0) > 1_000_000));

    match parse::search_top_result(&root) {
        Some(MediaItem::Artist(a)) => {
            assert_eq!(a.name, "Daft Punk");
            assert_eq!(a.browse_id.as_deref(), Some("UCRr1xG_2WIDs18a6cIiCxeA"));
            assert!(a.thumbnail_url.unwrap().contains("=w544-h544"));
        }
        other => panic!("unexpected top result {other:?}"),
    }
}

#[test]
fn song_filtered_search_has_full_metadata() {
    let root = load("search_songs_daft_punk.json");
    let songs = parse::search_tracks(&root);
    assert_eq!(songs.len(), 20);
    assert_playable_ids(&songs);
    let crush = songs.iter().find(|t| t.title.starts_with("Instant Crush")).expect("Instant Crush");
    assert_eq!(crush.artist, "Daft Punk, Julian Casablancas");
    assert_eq!(crush.album.as_deref(), Some("Random Access Memories"));
    assert_eq!(crush.album_id.as_deref(), Some("MPREb_K8qWMWVqXGi"));
    assert_eq!(crush.artist_id.as_deref(), Some("UCRr1xG_2WIDs18a6cIiCxeA"));
    assert_eq!(crush.duration_ms, 338_000);
    assert_eq!(crush.artists.as_ref().unwrap().len(), 2);
    assert!(songs.iter().all(|t| t.duration_ms > 0));
    assert!(parse::continuation_token(&root).is_some());

    let merged = parse::merge_search_results(parse::search_tracks(&load("search_daft_punk.json")), songs.clone(), 25);
    assert!(merged.len() <= 25 && merged.len() > songs.len().min(20) / 2);
    let ids: std::collections::HashSet<_> = merged.iter().map(|t| &t.id).collect();
    assert_eq!(ids.len(), merged.len(), "merge must not repeat ids");
}

#[test]
fn album_artist_playlist_search() {
    let albums = parse::search_albums(&load("search_albums_daft_punk.json"), 20);
    assert_eq!(albums.len(), 20);
    let ram = &albums[0];
    assert_eq!(ram.title, "Random Access Memories");
    assert_eq!(ram.browse_id.as_deref(), Some("MPREb_K8qWMWVqXGi"));
    assert_eq!(ram.artist.as_deref(), Some("Daft Punk"));
    assert_eq!(ram.artist_id.as_deref(), Some("UCRr1xG_2WIDs18a6cIiCxeA"));
    assert_eq!(ram.release_year, Some(2013));
    assert_eq!(ram.kind.as_deref(), Some("Album"));

    let artists = parse::search_artists(&load("search_artists_daft_punk.json"), 20);
    assert!(artists.len() >= 5);
    assert_eq!(artists[0].name, "Daft Punk");
    assert!(artists.iter().all(|a| a.browse_id.as_deref().is_some_and(|b| b.starts_with("UC"))));

    let playlists = parse::search_playlists(&load("search_playlists_daft_punk.json"), 20);
    assert_eq!(playlists.len(), 20);
    assert_eq!(playlists[0].title, "Best Of Daft Punk");
    assert_eq!(playlists[0].playlist_id.as_deref(), Some("PLdXJrX9OsbOVq2TKvUc3xGUp2Rm8nTUVh"));
    assert_eq!(playlists[0].author.as_deref(), Some("misterepicpants"));

    // The general page also yields a few of each, which the merges de-duplicate.
    let general = load("search_daft_punk.json");
    let merged = parse::merge_albums(albums.clone(), parse::search_albums(&general, 20), 20);
    assert_eq!(merged.len(), 20);
    let merged = parse::merge_artists(artists.clone(), parse::search_artists(&general, 20), 20);
    assert_eq!(merged.iter().filter(|a| a.name == "Daft Punk").count(), 1);
}

#[test]
fn suggestions() {
    let s = parse::search_suggestions(&load("suggestions_daft_pu.json"));
    assert_eq!(s[0], "daft punk");
    assert!(s.len() >= 5);
    assert!(s.iter().all(|q| q.starts_with("daft pu")));
}

#[test]
fn home_shelves_and_continuation() {
    let page = parse::home_page(&load("home.json"));
    assert!(page.continuation.is_some());
    assert_eq!(page.shelves.len(), 3);
    let quick = &page.shelves[0];
    assert_eq!(quick.title, "Quick picks");
    assert_eq!(quick.items.len(), 20);
    assert!(quick.items.iter().all(|i| matches!(i, MediaItem::Song(t) if t.video_id().is_some() && t.cover_url.is_some())));
    let featured = &page.shelves[1];
    assert!(featured.items.iter().all(|i| matches!(i, MediaItem::Playlist(p) if p.playlist_id.is_some())));

    let more = parse::home_page(&load("home_continuation.json"));
    assert!(more.shelves.iter().any(|s| s.title == "New releases" && !s.items.is_empty()));
    for shelf in page.shelves.iter().chain(&more.shelves) {
        for item in &shelf.items {
            let title = match item {
                MediaItem::Song(t) => &t.title,
                MediaItem::Album(a) => &a.title,
                MediaItem::Playlist(p) => &p.title,
                MediaItem::Artist(a) => &a.name,
            };
            assert!(!title.is_empty());
        }
    }
}

#[test]
fn artist_page() {
    let a = parse::artist_page(&load("artist_daft_punk.json")).unwrap();
    assert_eq!(a.artist.name, "Daft Punk");
    assert_eq!(a.artist.browse_id.as_deref(), Some("UCRr1xG_2WIDs18a6cIiCxeA"));
    assert_eq!(a.subscribers.as_deref(), Some("7.21M"));
    assert!(a.monthly_audience.as_deref().unwrap().contains("monthly audience"));
    assert!(a.description.as_deref().unwrap().starts_with("Daft Punk were a French"));
    assert!(a.banner_url.is_some() && a.artist.thumbnail_url.is_some());
    assert_eq!(a.top_songs.len(), 5);
    assert_playable_ids(&a.top_songs);
    assert_eq!(a.albums.len(), 10);
    assert!(a.albums.iter().all(|al| al.browse_id.as_deref().is_some_and(|b| b.starts_with("MPRE"))));
    assert_eq!(a.singles.len(), 10);
    assert_eq!(a.videos.len(), 10);
    assert!(a.videos.iter().all(|v| v.is_video));
    assert_eq!(a.related.len(), 10);
    assert!(a.related.iter().all(|r| r.browse_id != a.artist.browse_id));
    assert!(!a.playlists.is_empty());
    assert!(a.songs_browse_id.as_deref().unwrap().starts_with("VL"));
}

#[test]
fn album_page() {
    let p = parse::album_page(&load("album_ram.json"), Some("MPREb_K8qWMWVqXGi")).unwrap();
    assert_eq!(p.album.title, "Random Access Memories");
    assert_eq!(p.album.artist.as_deref(), Some("Daft Punk"));
    assert_eq!(p.album.artist_id.as_deref(), Some("UCRr1xG_2WIDs18a6cIiCxeA"));
    assert_eq!(p.album.release_year, Some(2013));
    assert_eq!(p.album.track_count, Some(13));
    assert!(p.album.playlist_id.as_deref().unwrap().starts_with("OLAK"));
    assert!(p.description.is_some());
    assert_eq!(p.tracks.len(), 13);
    assert_playable_ids(&p.tracks);
    for t in &p.tracks {
        assert_eq!(t.album.as_deref(), Some("Random Access Memories"));
        assert_eq!(t.album_id.as_deref(), Some("MPREb_K8qWMWVqXGi"));
        assert_eq!(t.release_year, Some(2013));
        assert!(t.duration_ms > 0 && t.cover_url.is_some() && t.artist_id.is_some());
    }
    assert_eq!(p.tracks[0].title, "Give Life Back to Music");
    assert_eq!(p.tracks[0].duration_ms, 276_000);
}

#[test]
fn playlist_page_and_continuation() {
    let (p, token) = parse::playlist_page(&load("playlist_big.json"), Some("VLPLMC9KNkIncKtPzgY-5rmhvj7fax8fdxoj")).unwrap();
    assert_eq!(p.playlist.playlist_id.as_deref(), Some("PLMC9KNkIncKtPzgY-5rmhvj7fax8fdxoj"));
    assert_eq!(p.playlist.author.as_deref(), Some("Redlist - Just Hits"));
    assert_eq!(p.playlist.track_count, Some(200));
    assert!(p.description.is_some() && p.playlist.thumbnail_url.is_some());
    assert_eq!(p.tracks.len(), 100);
    assert_playable_ids(&p.tracks);
    assert!(token.is_some());

    let next = parse::tracks_page(&load("playlist_big_continuation.json"));
    assert_eq!(next.tracks.len(), 100);
    assert_playable_ids(&next.tracks);
    assert!(next.continuation.is_none());
    assert!(next.tracks.iter().all(|t| !p.tracks.iter().any(|f| f.id == t.id)));
}

#[test]
fn watch_next_radio() {
    let tracks = parse::watch_next_tracks(&load("next_get_lucky.json"), 50);
    assert_eq!(tracks.len(), 50);
    assert_playable_ids(&tracks);
    assert_eq!(tracks[0].id, "yt_5NV6Rdv1a3I");
    assert_eq!(tracks[0].artist, "Daft Punk");
    assert!(tracks.iter().all(|t| t.duration_ms > 0 && t.cover_url.is_some()));
    assert_eq!(parse::watch_next_tracks(&load("next_get_lucky.json"), 10).len(), 10);
}

#[test]
fn player_format_selection() {
    let root = load("player_visionos.json");
    assert_eq!(stream::playability_status(&root).as_deref(), Some("OK"));
    let opus = stream::select_best_audio_format(&root, AudioPreference::OpusFirst).unwrap();
    assert_eq!(opus.itag, Some(251));
    assert!(opus.mime.starts_with("audio/webm"));
    assert_eq!(opus.codecs.as_deref(), Some("opus"));
    assert!(opus.url.contains("googlevideo.com/videoplayback"));
    assert!(opus.expires_at.is_some() && opus.content_length.is_some());
    assert!(opus.approx_duration_ms.unwrap() > 200_000);
    assert!(!stream::is_preview_url(&opus.url));
    let aac = stream::select_best_audio_format(&root, AudioPreference::AacFirst).unwrap();
    assert_eq!(aac.itag, Some(140));

    let refused = load("player_login_required.json");
    assert_eq!(stream::playability_status(&refused).as_deref(), Some("LOGIN_REQUIRED"));
    assert!(stream::select_best_audio_format(&refused, AudioPreference::OpusFirst).is_none());
}

#[test]
fn invidious_format_selection() {
    let root = load("invidious_video.json");
    let (itag, mime, bitrate) = stream::invidious_best_format(&root, AudioPreference::OpusFirst).unwrap();
    assert_eq!(itag, 251);
    assert!(mime.contains("opus") && bitrate > 100_000);
    assert_eq!(stream::invidious_best_format(&root, AudioPreference::AacFirst).unwrap().0, 140);
}

#[test]
fn track_json_matches_the_phone() {
    // As the phone's Gson writes it: camelCase, nulls left out, extra fields present.
    let phone = r#"{"id":"yt_5NV6Rdv1a3I","title":"Get Lucky","artist":"Daft Punk","durationMs":248000,
        "coverUrl":"https://x/c.jpg","provider":"YouTube","artistId":"UCRr1xG_2WIDs18a6cIiCxeA","isVideo":false,
        "releaseYear":2013,"albumId":"MPREb_K8qWMWVqXGi","streams":1200000000,"album":null,"gain":-6.5,
        "artists":[{"name":"Daft Punk","browseId":"UCRr1xG_2WIDs18a6cIiCxeA","role":"PRIMARY"},
                   {"name":"Pharrell Williams","role":"FEATURED"},{"name":"Nile Rodgers","role":"SOMETHING_NEW"}],
        "futureField":{"a":1}}"#;
    let t: Track = serde_json::from_str(phone).unwrap();
    assert_eq!(t.video_id(), Some("5NV6Rdv1a3I"));
    assert_eq!(t.duration_ms, 248_000);
    assert_eq!(t.album, None);
    assert_eq!(t.gain, Some(-6.5));
    let credits = t.artists.as_ref().unwrap();
    assert_eq!(credits[1].role, lfly_yt::ArtistRole::Featured);
    assert_eq!(credits[2].role, lfly_yt::ArtistRole::Primary);
    assert_eq!(credits[0].artist_id(), Some("UCRr1xG_2WIDs18a6cIiCxeA"));

    let out = serde_json::to_value(&t).unwrap();
    assert_eq!(out["durationMs"], 248_000);
    assert_eq!(out["isVideo"], false);
    assert!(out.get("album").is_none() && out.get("streamUrl").is_none());

    let sparse: Track = serde_json::from_str(r#"{"id":"yt_x","title":null}"#).unwrap();
    assert_eq!((sparse.title.as_str(), sparse.provider.as_str(), sparse.duration_ms), ("", "Unknown", 0));
}
