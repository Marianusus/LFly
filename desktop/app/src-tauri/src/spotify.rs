//! A Spotify playlist, album or song brought into LFly from its link, without a Spotify account:
//! the titles come from Spotify's public embed page (what any website embedding the playlist
//! shows, up to its first 100 songs), and each song is then found on YouTube Music, where LFly
//! plays it from.

use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;

use futures_util::stream::{self, StreamExt};
use lfly_yt::{Track, YtMusic};
use serde::Serialize;
use serde_json::Value;
use tauri::{AppHandle, Emitter, State};

const UA: &str = "Mozilla/5.0 (X11; Linux x86_64; rv:144.0) Gecko/20100101 Firefox/144.0";

#[derive(Debug, Clone, PartialEq)]
struct Wanted {
    title: String,
    artist: String,
    duration_ms: i64,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Imported {
    name: String,
    cover_url: Option<String>,
    tracks: Vec<Track>,
    /// Songs Spotify listed that YouTube Music doesn't seem to have.
    missing: Vec<String>,
    /// Spotify's page showed only part of a longer playlist.
    truncated: bool,
}

/// `open.spotify.com/intl-it/playlist/<id>?si=…` or `spotify:album:<id>` → ("playlist", id).
fn parse_link(link: &str) -> Option<(&'static str, String)> {
    let link = link.trim();
    let (kind, rest) = if let Some(rest) = link.strip_prefix("spotify:") {
        rest.split_once(':')?
    } else {
        let path = link.split("open.spotify.com/").nth(1)?;
        let path = path.split(['?', '#']).next()?;
        let mut parts = path.split('/').filter(|p| !p.is_empty() && !p.starts_with("intl-"));
        (parts.next()?, parts.next()?)
    };
    let kind = match kind {
        "playlist" => "playlist",
        "album" => "album",
        "track" => "track",
        _ => return None,
    };
    let id: String = rest.chars().take_while(|c| c.is_ascii_alphanumeric()).collect();
    (id.len() >= 10).then_some((kind, id))
}

/// The entity in an embed page's `__NEXT_DATA__`.
fn entity(html: &str) -> Option<Value> {
    let start = html.find(r#"<script id="__NEXT_DATA__" type="application/json">"#)?;
    let body = &html[start..];
    let body = &body[body.find('>')? + 1..];
    let end = body.find("</script>")?;
    let data: Value = serde_json::from_str(&body[..end]).ok()?;
    data.pointer("/props/pageProps/state/data/entity").cloned()
}

fn wanted_of(entity: &Value) -> Vec<Wanted> {
    let one = |t: &Value| -> Option<Wanted> {
        Some(Wanted {
            title: t.get("title")?.as_str()?.to_string(),
            artist: t
                .get("subtitle")
                .and_then(Value::as_str)
                .map(str::to_string)
                .or_else(|| {
                    let names: Vec<&str> = t.get("artists")?.as_array()?.iter().filter_map(|a| a.get("name")?.as_str()).collect();
                    Some(names.join(", "))
                })
                .unwrap_or_default(),
            duration_ms: t.get("duration").and_then(Value::as_i64).unwrap_or(0),
        })
    };
    match entity.get("trackList").and_then(Value::as_array) {
        Some(list) => list.iter().filter_map(one).collect(),
        None => one(entity).into_iter().collect(),
    }
}

/// How well a YouTube Music result fits the Spotify song: the length matters most (live and
/// remixed versions differ), then the title.
fn score(want: &Wanted, got: &Track) -> i64 {
    let norm = |s: &str| s.to_lowercase().chars().filter(|c| c.is_alphanumeric() || c.is_whitespace()).collect::<String>();
    let (wt, gt) = (norm(&want.title), norm(&got.title));
    let mut score = 0;
    if want.duration_ms > 0 && got.duration_ms > 0 {
        let diff = (want.duration_ms - got.duration_ms).abs();
        score += if diff <= 3_000 { 50 } else if diff <= 10_000 { 20 } else if diff > 30_000 { -40 } else { 0 };
    }
    if gt == wt {
        score += 40;
    } else if gt.contains(&wt) || wt.contains(&gt) {
        score += 20;
    }
    // Any of the Spotify artists on the result; none at all is most likely someone's re-upload.
    let got_artist = norm(&got.artist);
    let artists: Vec<String> = want.artist.split(',').map(|a| norm(a).trim().to_string()).filter(|a| !a.is_empty()).collect();
    if artists.iter().any(|a| got_artist.contains(a.as_str())) {
        score += 30;
    } else if !artists.is_empty() {
        score -= 25;
    }
    if got.is_video {
        score -= 10;
    }
    score
}

async fn find(yt: &YtMusic, want: &Wanted) -> Option<Track> {
    let artist = want.artist.split(',').next().unwrap_or("").trim();
    let found = yt.search_songs(&format!("{} {}", want.title, artist), 8).await.ok()?;
    found.into_iter().map(|t| (score(want, &t), t)).filter(|(s, _)| *s >= 30).max_by_key(|(s, _)| *s).map(|(_, t)| t)
}

#[derive(Clone, Serialize)]
struct Progress {
    done: usize,
    total: usize,
}

#[tauri::command]
pub async fn spotify_import(app: AppHandle, yt: State<'_, YtMusic>, link: String) -> Result<Imported, String> {
    let (kind, id) = parse_link(&link).ok_or("Non è un link di Spotify a una playlist, un album o un brano")?;
    let html = reqwest::Client::new()
        .get(format!("https://open.spotify.com/embed/{kind}/{id}"))
        .header("User-Agent", UA)
        .send()
        .await
        .map_err(|e| format!("Spotify non risponde: {e}"))?
        .error_for_status()
        .map_err(|_| "Spotify non mostra questo link: è privato o non esiste?".to_string())?
        .text()
        .await
        .map_err(|e| e.to_string())?;
    let entity = entity(&html).ok_or("Spotify ha cambiato la sua pagina: non riesco a leggerla")?;
    let name = entity.get("name").or_else(|| entity.get("title")).and_then(Value::as_str).unwrap_or("Da Spotify").to_string();
    let cover_url = entity
        .pointer("/coverArt/sources")
        .and_then(Value::as_array)
        .and_then(|s| s.iter().rev().find_map(|x| x.get("url")?.as_str()))
        .map(str::to_string);
    let wanted = wanted_of(&entity);
    if wanted.is_empty() {
        return Err("La playlist è vuota".into());
    }
    let truncated = kind == "playlist" && wanted.len() >= 100;

    let total = wanted.len();
    let done = Arc::new(AtomicUsize::new(0));
    let yt = yt.inner().clone();
    let results: Vec<(usize, Option<Track>)> = stream::iter(wanted.iter().cloned().enumerate())
        .map(|(i, want)| {
            let (yt, app, done) = (yt.clone(), app.clone(), done.clone());
            async move {
                let found = find(&yt, &want).await;
                let n = done.fetch_add(1, Ordering::Relaxed) + 1;
                let _ = app.emit("spotify:progress", Progress { done: n, total });
                (i, found)
            }
        })
        .buffer_unordered(4)
        .collect()
        .await;
    let mut ordered = results;
    ordered.sort_by_key(|(i, _)| *i);
    let mut tracks = Vec::new();
    let mut missing = Vec::new();
    for (i, found) in ordered {
        match found {
            Some(t) if !tracks.iter().any(|x: &Track| x.id == t.id) => tracks.push(t),
            Some(_) => {}
            None => missing.push(format!("{} – {}", wanted[i].title, wanted[i].artist)),
        }
    }
    Ok(Imported { name, cover_url, tracks, missing, truncated })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_links() {
        assert_eq!(
            parse_link("https://open.spotify.com/intl-it/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc"),
            Some(("playlist", "37i9dQZF1DXcBWIGoYBM5M".into()))
        );
        assert_eq!(parse_link("spotify:album:4aawyAB9vmqN3uQ7FjRGTy"), Some(("album", "4aawyAB9vmqN3uQ7FjRGTy".into())));
        assert_eq!(parse_link("https://open.spotify.com/track/3zOYmiNOuyDL0ClxJcEJ0T"), Some(("track", "3zOYmiNOuyDL0ClxJcEJ0T".into())));
        assert_eq!(parse_link("https://example.com/playlist/x"), None);
        assert_eq!(parse_link("https://open.spotify.com/artist/3zOYmiNOuyDL0ClxJcEJ0T"), None);
    }

    #[test]
    fn prefers_the_same_length_and_artist() {
        let want = Wanted { title: "Stop The Wedding!".into(), artist: "Ashe".into(), duration_ms: 198_840 };
        let t = |title: &str, artist: &str, ms: i64| Track { title: title.into(), artist: artist.into(), duration_ms: ms, ..Default::default() };
        let right = t("Stop The Wedding!", "Ashe", 199_000);
        let live = t("Stop The Wedding! (Live)", "Ashe", 260_000);
        let other = t("Stop The Wedding", "Someone Else", 150_000);
        assert!(score(&want, &right) > score(&want, &live));
        assert!(score(&want, &right) > score(&want, &other));
        assert!(score(&want, &other) < 30);
    }

    #[test]
    fn reads_an_embed_page() {
        let html = r#"<html><script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"state":{"data":{"entity":{"name":"Mix","trackList":[{"title":"A","subtitle":"X, Y","duration":1000},{"title":"B","subtitle":"Z","duration":2000}]}}}}}}</script></html>"#;
        let e = entity(html).unwrap();
        let w = wanted_of(&e);
        assert_eq!(w.len(), 2);
        assert_eq!(w[0], Wanted { title: "A".into(), artist: "X, Y".into(), duration_ms: 1000 });
    }
}

#[cfg(test)]
mod live {
    //! Against Spotify's embed page and YouTube Music: `cargo test -p lfly-desktop -- --ignored`.
    use super::*;

    #[tokio::test]
    #[ignore]
    async fn a_public_playlist_matches_on_youtube_music() {
        let html = reqwest::Client::new()
            .get("https://open.spotify.com/embed/playlist/37i9dQZF1DXcBWIGoYBM5M")
            .header("User-Agent", UA)
            .send()
            .await
            .unwrap()
            .text()
            .await
            .unwrap();
        let wanted = wanted_of(&entity(&html).unwrap());
        assert!(wanted.len() >= 20);
        let yt = YtMusic::new().unwrap();
        let mut hits = 0;
        for w in wanted.iter().take(10) {
            let found = find(&yt, w).await;
            println!("{} – {}  =>  {:?}", w.title, w.artist, found.as_ref().map(|t| format!("{} – {} ({} s)", t.title, t.artist, t.duration_ms / 1000)));
            hits += found.is_some() as usize;
        }
        assert!(hits >= 8, "{hits}/10 found");
    }
}
