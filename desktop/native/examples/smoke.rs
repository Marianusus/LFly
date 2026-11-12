//! The shared player end to end, without a window, at volume 0: `cargo run --release --example smoke`.
//! Search, full stream, next, previous, and the gapless move to the next song after a seek
//! near the end.
#[path = "../src/core.rs"]
mod core;

use std::time::{Duration, Instant};

use lfly_connect::protocol;

use crate::core::PlayerUpdate;

fn wait<F: FnMut(&PlayerUpdate) -> bool>(
    rx: &mut tokio::sync::mpsc::UnboundedReceiver<PlayerUpdate>,
    secs: u64,
    what: &str,
    mut done: F,
) -> bool {
    let deadline = Instant::now() + Duration::from_secs(secs);
    let rt = core::runtime();
    while Instant::now() < deadline {
        match rt.block_on(async { tokio::time::timeout(Duration::from_millis(500), rx.recv()).await }) {
            Ok(Some(u)) => {
                if done(&u) {
                    println!("  ok: {what}");
                    return true;
                }
            }
            Ok(None) => break,
            Err(_) => {}
        }
    }
    println!("  FAILED: {what}");
    false
}

fn index_is(i: i32) -> impl FnMut(&PlayerUpdate) -> bool {
    move |u| matches!(u, PlayerUpdate::State(s) if s.index == i && s.playing)
}

fn main() {
    let (core, mut rx) = core::Core::new().expect("core");
    core.command(protocol::CMD_VOLUME, 0);
    let tracks = core::runtime().block_on(core.clone().search("Daft Punk Random Access Memories".into())).expect("search");
    println!("search: {} songs", tracks.len());
    assert!(tracks.len() >= 3, "need three songs");
    core.play(&tracks, 0);
    let mut ok = wait(&mut rx, 20, "plays the first song", index_is(0));
    ok &= wait(&mut rx, 10, "position ticks", |u| matches!(u, PlayerUpdate::Position { ms } if *ms > 1000));
    core.command(protocol::CMD_NEXT, 0);
    ok &= wait(&mut rx, 20, "next", index_is(1));
    core.command(protocol::CMD_PREVIOUS, 0);
    ok &= wait(&mut rx, 20, "previous (early in the song: the one before)", index_is(0));
    // Near the end: the preloaded next song takes over by itself.
    let mut duration = 0;
    ok &= wait(&mut rx, 20, "knows the song's length", |u| {
        if let PlayerUpdate::State(s) = u {
            if s.index == 0 && s.duration_ms > 0 {
                duration = s.duration_ms;
                return true;
            }
        }
        false
    });
    println!("  duration from the player: {duration} ms");
    core.command(protocol::CMD_SEEK, duration - 4_000);
    ok &= wait(&mut rx, 25, "moves on to the next song at the end", index_is(1));
    println!("{}", if ok { "smoke: all good" } else { "smoke: FAILED" });
    std::process::exit(if ok { 0 } else { 1 });
}
