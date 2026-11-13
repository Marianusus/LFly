//! Downloads end to end, without a window: `LFLY_DOWNLOADS_DIR=<empty dir> cargo run --release
//! --example downloads`. One song is downloaded (progress, index, file), then removed.
#[path = "../src/core.rs"]
mod core;

use std::time::{Duration, Instant};

use lfly_core::downloads::{self, DownloadEvent};

fn main() {
    let dir = std::env::var_os("LFLY_DOWNLOADS_DIR").expect("set LFLY_DOWNLOADS_DIR to a scratch folder");
    let (core, _rx) = core::Core::new().expect("core");
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
            Err(_) => {}
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
