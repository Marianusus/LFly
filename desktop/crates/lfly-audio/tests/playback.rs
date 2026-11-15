//! Real playback on the default output device. Run with `cargo test -p lfly-audio -- --ignored`.

mod common;

use std::time::{Duration, Instant};

use common::{fixture, temp_dir};
use lfly_audio::{Player, PlayerEvent, Source};
use tokio::sync::broadcast::error::TryRecvError;

fn collect(rx: &mut tokio::sync::broadcast::Receiver<PlayerEvent>, until: impl Fn(&PlayerEvent) -> bool, max: Duration) -> Vec<PlayerEvent> {
    let start = Instant::now();
    let mut seen = Vec::new();
    while start.elapsed() < max {
        match rx.try_recv() {
            Ok(ev) => {
                let stop = until(&ev);
                seen.push(ev);
                if stop {
                    break;
                }
            }
            Err(TryRecvError::Empty) => std::thread::sleep(Duration::from_millis(10)),
            Err(e) => panic!("{e}"),
        }
    }
    seen
}

#[test]
#[ignore = "needs an audio output device"]
fn plays_two_seconds_on_the_default_device() {
    let player = Player::new(temp_dir("play"), 64 << 20).unwrap();
    println!("devices: {:?}", Player::devices());
    let mut rx = player.subscribe();
    player.set_volume(0.3);
    player.load(Source::file(fixture("sine.webm")), 0, true);
    std::thread::sleep(Duration::from_secs(2));
    let pos = player.position_ms().unwrap();
    let events = collect(&mut rx, |_| false, Duration::from_millis(10));
    println!("events: {events:?}");
    assert!(events.contains(&PlayerEvent::Playing));
    assert!(events.iter().any(|e| matches!(e, PlayerEvent::Duration { ms } if (2950..3100).contains(ms))));
    assert!((1700..2300).contains(&pos), "position {pos} after 2 s");
    assert!(!events.iter().any(|e| matches!(e, PlayerEvent::Error { .. })));

    // Pause holds the position, seek moves it.
    player.pause();
    std::thread::sleep(Duration::from_millis(300));
    let paused_at = player.position_ms().unwrap();
    std::thread::sleep(Duration::from_millis(300));
    assert_eq!(player.position_ms().unwrap(), paused_at);
    player.seek(500);
    std::thread::sleep(Duration::from_millis(200));
    assert!((500..520).contains(&player.position_ms().unwrap()));
    player.stop();
}

#[test]
#[ignore = "needs an audio output device"]
fn preloaded_song_follows_gaplessly() {
    let player = Player::new(temp_dir("gapless"), 64 << 20).unwrap();
    let mut rx = player.subscribe();
    player.set_volume(0.3);
    player.load(Source::file(fixture("sine.m4a")), 1_500, true); // 44.1 kHz, resampled
    player.preload(Source::file(fixture("sine.webm"))); // 48 kHz
    let events = collect(&mut rx, |e| *e == PlayerEvent::Ended, Duration::from_secs(8));
    println!("events: {events:?}");
    let changed = events.iter().position(|e| matches!(e, PlayerEvent::TrackChanged { .. })).expect("TrackChanged");
    let ended = events.iter().position(|e| *e == PlayerEvent::Ended).expect("Ended");
    assert!(changed < ended);
    assert!(!events.iter().any(|e| matches!(e, PlayerEvent::Error { .. } | PlayerEvent::Buffering(true))));
}

#[test]
#[ignore = "needs an audio output device"]
fn crossfade_starts_the_next_song_before_the_end() {
    let player = Player::new(temp_dir("crossfade"), 64 << 20).unwrap();
    let mut rx = player.subscribe();
    player.set_volume(0.0); // silent: only the timing matters
    player.set_crossfade(1_000);
    player.load(Source::file(fixture("sine.m4a")), 0, true); // 3 s
    player.preload(Source::file(fixture("sine.webm"))); // 3 s
    let start = Instant::now();
    let mut changed_at = None;
    let mut ended_at = None;
    while start.elapsed() < Duration::from_secs(10) && ended_at.is_none() {
        match rx.try_recv() {
            Ok(PlayerEvent::TrackChanged { .. }) => changed_at = Some(start.elapsed()),
            Ok(PlayerEvent::Ended) => ended_at = Some(start.elapsed()),
            Ok(PlayerEvent::Error { message }) => panic!("{message}"),
            Ok(_) => {}
            Err(TryRecvError::Empty) => std::thread::sleep(Duration::from_millis(10)),
            Err(e) => panic!("{e}"),
        }
    }
    let (changed, ended) = (changed_at.expect("TrackChanged"), ended_at.expect("Ended"));
    println!("next song heard at {changed:?}, all over at {ended:?}");
    // The next song comes in a second before the first one's end, so both end a second early.
    assert!(changed >= Duration::from_millis(1_700) && changed <= Duration::from_millis(2_500), "{changed:?}");
    assert!(ended >= Duration::from_millis(4_700) && ended <= Duration::from_millis(5_600), "{ended:?}");
}

#[test]
#[ignore = "needs an audio output device"]
fn a_new_song_during_a_crossfade_is_the_one_that_plays() {
    let player = Player::new(temp_dir("switch-in-fade"), 64 << 20).unwrap();
    player.set_volume(0.0); // silent: only the timing matters
    player.set_crossfade(1_000);
    player.load(Source::file(fixture("sine.m4a")), 0, true); // 3 s
    player.preload(Source::file(fixture("sine.webm"))); // 3 s, comes in during the fade
    // The engine decodes up to 2 s ahead: at 1.7 s the fade is decoded and the next song has
    // started, though it is not heard yet. That is when a pick used to be undone.
    std::thread::sleep(Duration::from_millis(1_700));
    // The song that just came in is picked over: a different one, 4 s long, from the top.
    player.load(Source::file(fixture("noise.webm")), 0, true);
    std::thread::sleep(Duration::from_millis(400));
    // A seek lands in the song the engine really decodes, and its length shows which one it is.
    player.seek(100);
    std::thread::sleep(Duration::from_millis(200));
    assert_eq!(player.duration_ms(), Some(4_008), "the faded-in song kept playing");
    player.stop();
}

#[test]
#[ignore = "needs an audio output device"]
fn two_quick_picks_play_the_second() {
    let player = Player::new(temp_dir("quick-picks"), 64 << 20).unwrap();
    player.set_volume(0.0);
    player.load(Source::file(fixture("sine.m4a")), 0, true); // 3 s
    std::thread::sleep(Duration::from_millis(150));
    player.load(Source::file(fixture("noise.webm")), 0, true); // 4 s
    std::thread::sleep(Duration::from_millis(400));
    player.seek(100);
    std::thread::sleep(Duration::from_millis(200));
    assert_eq!(player.duration_ms(), Some(4_008), "the first pick is still playing");
    player.stop();
}
