//! Downloads end to end, without a window: `LFLY_DOWNLOADS_DIR=<empty dir> cargo run --release
//! --example downloads`. One song is downloaded (progress, index, file), then removed.
//! LYRICS=1: the lyrics of the songs already downloaded there are fetched and kept.
#[path = "../src/core.rs"]
mod core;

use std::time::{Duration, Instant};

use lfly_core::downloads::{self, DownloadEvent};

fn main() {
    // A test run: the shared player stays off the listener's YouTube Music account (core.rs).
    std::env::set_var("LFLY_MUTE", "1");
    let dir = std::env::var_os("LFLY_DOWNLOADS_DIR").expect("set LFLY_DOWNLOADS_DIR to a scratch folder");
    let (core, mut rx) = core::Core::new().expect("core");
    if std::env::var_os("OFFLINE").is_some() {
        return offline(core, &mut rx);
    }
    if std::env::var_os("LYRICS").is_some() {
        return lyrics(core);
    }
    let tracks = core::runtime().block_on(core.clone().search("Daft Punk Get Lucky".into())).expect("search");
    let t = core::track_ref(&tracks[0]).expect("track");
    println!("song: {} ({})", t.title, t.id);
    let (tx, rx) = std::sync::mpsc::channel();
    let d = downloads::start(core.yt.clone(), core::runtime().handle().clone(), move |e| {
        let _ = tx.send(e);
    })
    .expect("downloads");
    d.add(vec![t.clone()]);
    let deadline = Instant::now() + Duration::from_secs(120);
    let (mut steps, mut changed) = (0, false);
    while Instant::now() < deadline && !changed {
        match rx.recv_timeout(Duration::from_secs(1)) {
            Ok(DownloadEvent::Progress { done, .. }) if done < 0. => panic!("download failed"),
            Ok(DownloadEvent::Progress { .. }) => steps += 1,
            Ok(DownloadEvent::Changed) => changed = true,
            Ok(DownloadEvent::Lyrics { .. }) | Err(_) => {}
        }
    }
    assert!(changed, "no download in 120 s");
    let list = d.list();
    let file = downloads::file_for(&t.id).expect("file");
    println!("  ok: {steps} progress steps, {} bytes, {}", list.bytes, file.display());
    assert_eq!(list.tracks.len(), 1);
    assert_eq!(std::fs::metadata(&file).unwrap().len(), list.bytes);
    assert!(std::path::Path::new(&dir).join("index.json").exists());
    if std::env::var_os("KEEP").is_none() {
        d.remove(&[t.id.clone()]);
        assert!(!file.exists() && d.list().tracks.is_empty());
        println!("  ok: removed");
    }
}

/// OFFLINE=1 (run it without a network, e.g. `unshare -rn`): the first downloaded song plays from
/// its file, three seconds of it.
fn offline(core: std::sync::Arc<core::Core>, rx: &mut tokio::sync::mpsc::UnboundedReceiver<core::PlayerUpdate>) {
    let d = downloads::start(core.yt.clone(), core::runtime().handle().clone(), |_| {}).expect("downloads");
    let t = d.list().tracks.into_iter().next().expect("a downloaded song (run once with KEEP=1)");
    println!("offline: {} from {}", t.title, downloads::file_for(&t.id).expect("file").display());
    core.player.play_queue(vec![t], 0, 0, true);
    let deadline = Instant::now() + Duration::from_secs(30);
    while Instant::now() < deadline {
        let got = core::runtime().block_on(async { tokio::time::timeout(Duration::from_millis(500), rx.recv()).await });
        if let Ok(Some(core::PlayerUpdate::Position { ms })) = got {
            if ms >= 3000 {
                println!("  ok: playing without a network, at {ms} ms");
                return;
            }
        }
    }
    panic!("the downloaded song did not play offline");
}

/// LYRICS=1: "Scarica mancanti" over the downloads already in the folder; each one ends with
/// lyrics kept or a "not found" mark.
fn lyrics(core: std::sync::Arc<core::Core>) {
    let (tx, rx) = std::sync::mpsc::channel();
    let d = downloads::start(core.yt.clone(), core::runtime().handle().clone(), move |e| {
        let _ = tx.send(e);
    })
    .expect("downloads");
    let missing = d.missing_lyrics();
    println!("lyrics: {} downloads without them", missing.len());
    d.fetch_missing_lyrics();
    let deadline = Instant::now() + Duration::from_secs(180);
    loop {
        assert!(Instant::now() < deadline, "lyrics not done in 180 s");
        if let Ok(DownloadEvent::Lyrics { done, total }) = rx.recv_timeout(Duration::from_secs(1)) {
            println!("  {done}/{total}");
            if done >= total {
                break;
            }
        }
    }
    for t in missing {
        let saved = d.lyrics(&t.id);
        let what = match saved {
            Some(s) if s.lyrics.is_some() => format!("kept ({})", s.lyrics.unwrap().source),
            Some(_) => "none found".into(),
            None => "lookup failed".into(),
        };
        println!("  {}: {what}", t.title);
    }
}
