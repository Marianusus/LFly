//! Network tests. Run with `cargo test -p lfly-yt -- --ignored`.

use lfly_yt::{MediaItem, Track, YtMusic};

fn get_lucky() -> Track {
    Track {
        id: "yt_5NV6Rdv1a3I".into(),
        title: "Get Lucky (feat. Pharrell Williams & Nile Rodgers)".into(),
        artist: "Daft Punk".into(),
        album: Some("Random Access Memories".into()),
        duration_ms: 248_000,
        provider: "YouTube".into(),
        ..Track::default()
    }
}

#[tokio::test]
#[ignore = "network"]
async fn live_search_and_suggestions() {
    let yt = YtMusic::new().unwrap();
    let r = yt.search("daft punk").await.unwrap();
    assert!(r.songs.len() >= 10, "songs {}", r.songs.len());
    assert!(!r.albums.is_empty() && !r.artists.is_empty() && !r.playlists.is_empty());
    assert!(matches!(r.top, Some(MediaItem::Artist(ref a)) if a.name == "Daft Punk"), "{:?}", r.top);
    let more = yt.search_songs("daft punk", 50).await.unwrap();
    assert!(more.len() > 25, "paged songs {}", more.len());
    let s = yt.suggestions("daft pu").await.unwrap();
    assert!(s.iter().any(|q| q == "daft punk"), "{s:?}");
}

#[tokio::test]
#[ignore = "network"]
async fn live_home() {
    let yt = YtMusic::new().unwrap();
    let shelves = yt.home().await.unwrap();
    println!("home: {:?}", shelves.iter().map(|s| (&s.title, s.items.len())).collect::<Vec<_>>());
    assert!(shelves.len() >= 3, "{}", shelves.len());
    assert!(shelves.iter().all(|s| !s.items.is_empty()));
}

#[tokio::test]
#[ignore = "network"]
async fn live_pages_and_radio() {
    let yt = YtMusic::new().unwrap();
    let artist = yt.artist("UCRr1xG_2WIDs18a6cIiCxeA").await.unwrap();
    assert_eq!(artist.artist.name, "Daft Punk");
    assert!(!artist.top_songs.is_empty() && !artist.albums.is_empty());
    let songs = yt
        .artist_songs(artist.songs_browse_id.as_deref().unwrap(), artist.songs_params.as_deref(), 300)
        .await
        .unwrap();
    assert!(songs.len() > artist.top_songs.len(), "artist songs {}", songs.len());
    let album = yt.album("MPREb_K8qWMWVqXGi").await.unwrap();
    assert_eq!(album.tracks.len(), 13);
    let playlist = yt.playlist("PLMC9KNkIncKtPzgY-5rmhvj7fax8fdxoj").await.unwrap();
    assert!(playlist.tracks.len() > 100, "playlist {}", playlist.tracks.len());
    let radio = yt.up_next("yt_5NV6Rdv1a3I").await.unwrap();
    assert!(radio.len() >= 20, "radio {}", radio.len());
}

#[tokio::test]
#[ignore = "network"]
async fn live_resolve_stream_is_playable() {
    let yt = YtMusic::new().unwrap();
    let s = yt.resolve_stream(&get_lucky()).await.unwrap();
    println!("stream: {} itag={:?} {} bps via {}", s.mime, s.itag, s.bitrate, s.provider);
    assert!(s.url.contains("googlevideo.com"), "{}", s.url);
    assert!(s.mime.starts_with("audio/"));
    assert!(s.expires_at.is_some());

    let http = reqwest::Client::new();
    let mut req = http.get(&s.url).header("Range", "bytes=0-4095");
    if let Some(ua) = &s.user_agent {
        req = req.header("User-Agent", ua);
    }
    let resp = req.send().await.unwrap();
    let status = resp.status().as_u16();
    let ct = resp.headers().get("content-type").unwrap().to_str().unwrap().to_string();
    assert!(status == 200 || status == 206, "status {status}");
    assert!(ct.starts_with("audio/"), "content type {ct}");
    let bytes = resp.bytes().await.unwrap();
    assert!(bytes.len() >= 1024, "got {} bytes", bytes.len());

    // A track from another source is matched by search first.
    let imported = Track { id: "spotify_0DiWol3AO6WpXZgp0goxAV".into(), title: "One More Time".into(), artist: "Daft Punk".into(), ..Track::default() };
    let s2 = yt.resolve_stream(&imported).await.unwrap();
    assert!(s2.mime.starts_with("audio/"));

    let health = yt.health();
    let ytm = health.iter().find(|h| h.kind == lfly_yt::SourceKind::YoutubeMusic).unwrap();
    assert_eq!(ytm.consecutive_failures, 0);
    assert!(ytm.latency_ms.is_some());
}

#[tokio::test]
#[ignore = "network"]
async fn live_lyrics() {
    let yt = YtMusic::new().unwrap();
    let l = yt.lyrics(&get_lucky()).await.unwrap().expect("lyrics for Get Lucky");
    println!("Get Lucky: {} lines from {}", l.lines.len(), l.source);
    assert!(l.has_synced(), "expected synced lines");
    assert!(l.lines.windows(2).all(|w| w[0].time_ms <= w[1].time_ms));
    assert!(l.plain_lyrics.unwrap().to_lowercase().contains("lucky"));
}

/// Real songs found through search, as the player gets them: prints the winning provider,
/// the line count and whether there are word timings. Checks the lines are synced and
/// really belong to the song.
#[tokio::test]
#[ignore = "network"]
async fn live_lyrics_real_songs() {
    let yt = YtMusic::new().unwrap();
    // (search, title, artist, expected words in the lyrics, word timings expected)
    let songs = [
        ("blinding lights the weeknd", "blinding lights", "the weeknd", "tryna call", true),
        ("zitti e buoni maneskin", "zitti e buoni", "maneskin", "sporchi", true),
        ("albachiara vasco rossi", "albachiara", "vasco rossi", "respiri piano", false),
        ("bad guy billie eilish", "bad guy", "billie eilish", "white shirt", true),
        ("shape of you ed sheeran", "shape of you", "ed sheeran", "the club", false),
        ("la solitudine laura pausini", "la solitudine", "laura pausini", "marco", false),
    ];
    let mut failures = Vec::new();
    for (search, title, artist, expected, worded) in songs {
        let fold = |s: &str| s.to_lowercase().replace('å', "a");
        let found = yt.search_songs(search, 20).await.unwrap();
        let track = found
            .into_iter()
            .find(|t| !t.is_video && fold(&t.title) == title && fold(&t.artist).contains(artist))
            .expect("the studio song");
        let started = std::time::Instant::now();
        let l = yt.lyrics(&track).await.unwrap();
        let took = started.elapsed().as_millis();
        let Some(l) = l else {
            println!("{} - {}: NO LYRICS ({took} ms)", track.title, track.artist);
            failures.push(search);
            continue;
        };
        let word_lines = l.lines.iter().filter(|x| x.word_timings.is_some()).count();
        println!(
            "{} - {} ({} s): {} via {}, {} lines, {} with words, {took} ms",
            track.title,
            track.artist,
            track.duration_ms / 1000,
            if l.has_synced() { "synced" } else { "plain" },
            l.source,
            l.lines.len(),
            word_lines
        );
        for line in l.lines.iter().take(3) {
            println!("    [{} -> {:?}] {}", line.time_ms, line.end_ms, line.text);
        }
        for line in &l.lines {
            if let Some(words) = &line.word_timings {
                assert_eq!(words.iter().map(|w| w.text.as_str()).collect::<String>(), line.text, "words must join into the line");
            }
        }
        let text = l.plain_lyrics.clone().unwrap_or_default().to_lowercase();
        if !l.has_synced() || !text.contains(expected) || (worded && word_lines == 0) {
            failures.push(search);
        }
    }
    for h in yt.health().iter().filter(|h| matches!(h.kind, lfly_yt::SourceKind::Lyrics | lfly_yt::SourceKind::Lrclib)) {
        println!("{:<34} {:?} {:?} ms {:?}", h.name, h.status, h.latency_ms, h.last_error);
    }
    assert!(failures.is_empty(), "wrong or missing lyrics for {failures:?}");
}
