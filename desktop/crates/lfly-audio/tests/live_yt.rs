//! Live tests against real YouTube streams (network needed): `cargo test -p lfly-audio --test live_yt -- --ignored`.

mod common;

use std::sync::Arc;
use std::time::{Duration, Instant};

use common::*;
use lfly_audio::decode::TrackDecoder;
use lfly_audio::http::ReaderCtl;
use lfly_audio::{DiskCache, Player, PlayerEvent, Source};
use lfly_yt::{AudioPreference, StreamSource, YtMusic, YtMusicConfig};

const TRACK_ID: &str = "yt_5NV6Rdv1a3I";

/// A second song, for the preload the app does right after every load.
const NEXT_ID: &str = "yt_dQw4w9WgXcQ";

fn resolve(preference: AudioPreference) -> StreamSource {
    resolve_id(TRACK_ID, preference)
}

fn resolve_id(id: &str, preference: AudioPreference) -> StreamSource {
    let rt = tokio::runtime::Runtime::new().unwrap();
    rt.block_on(async {
        let yt = YtMusic::with_config(YtMusicConfig { preference, ..Default::default() }).unwrap();
        let track = serde_json::from_value(serde_json::json!({ "id": id, "title": "", "artist": "" })).unwrap();
        yt.resolve_stream(&track).await.unwrap()
    })
}

fn source(s: &StreamSource) -> Source {
    source_keyed(s, TRACK_ID)
}

/// Like the app: the track id as cache key (plus the itag, so the tests do not share entries),
/// and the User-Agent the URL was issued for.
fn source_keyed(s: &StreamSource, id: &str) -> Source {
    let headers = s.user_agent.iter().map(|ua| ("User-Agent".to_string(), ua.clone())).collect();
    Source::Http { url: s.url.clone(), cache_key: format!("{id}-{:?}", s.itag), headers }
}

/// Decodes `seconds` of audio (stereo frames at the decoder's rate).
fn decode_for(dec: &mut TrackDecoder, seconds: f64) -> Vec<f32> {
    let mut out = Vec::new();
    while (out.len() / 2) as f64 / (dec.sample_rate() as f64) < seconds {
        if !dec.decode_next(&mut out).unwrap() {
            break;
        }
    }
    out
}

fn rms(x: &[f32]) -> f64 {
    (x.iter().map(|&v| (v as f64).powi(2)).sum::<f64>() / x.len().max(1) as f64).sqrt()
}

fn check_stream(preference: AudioPreference, itag: u32) {
    let s = resolve(preference);
    println!("itag {:?} {} via {} ua {:?} len {:?}", s.itag, s.mime, s.provider, s.user_agent, s.content_length);
    assert_eq!(s.itag, Some(itag), "resolved another format");
    let cache = Arc::new(DiskCache::new(temp_dir(&format!("live-{itag}")), 256 << 20).unwrap());
    let src = source(&s);

    let t = Instant::now();
    let mut dec = TrackDecoder::open(&src, &cache, ReaderCtl::detached()).unwrap();
    println!("opened in {:?}, {} Hz, duration {:?} ms", t.elapsed(), dec.sample_rate(), dec.duration_ms());
    assert!(dec.duration_ms().unwrap_or(0) > 200_000, "Get Lucky is longer than 3:20");

    let t = Instant::now();
    let head = decode_for(&mut dec, 20.0);
    let secs = head.len() as f64 / 2.0 / dec.sample_rate() as f64;
    println!("decoded {secs:.2} s in {:?}, rms {:.3}", t.elapsed(), rms(&head));
    assert!(secs >= 20.0);
    assert!(rms(&head[head.len() / 2..]) > 0.01, "not silence");
    assert!(head.iter().all(|v| v.is_finite() && v.abs() <= 1.5));

    // Again from the cache: decoding speed alone.
    dec.seek(0).unwrap();
    let t = Instant::now();
    let again = decode_for(&mut dec, 20.0);
    let cpu = t.elapsed();
    println!("cached: 20 s decoded in {cpu:?} ({:.0}x real time)", 20.0 / cpu.as_secs_f64());
    assert_eq!(again.len(), head.len(), "frames {} vs {}", again.len() / 2, head.len() / 2);

    let t = Instant::now();
    assert_eq!(dec.seek(60_000).unwrap(), 60_000);
    let after = decode_for(&mut dec, 5.0);
    println!("seek to 60 s + 5 s decoded in {:?}, rms {:.3}", t.elapsed(), rms(&after));
    assert!(after.len() / 2 >= dec.sample_rate() as usize * 5);
    assert!(rms(&after) > 0.01);
}

#[test]
#[ignore = "network: real YouTube stream"]
fn aac_itag_140_streams_and_seeks() {
    check_stream(AudioPreference::AacFirst, 140);
}

#[test]
#[ignore = "network: real YouTube stream"]
fn opus_itag_251_streams_and_seeks() {
    check_stream(AudioPreference::OpusFirst, 251);
}

fn player_advances(preference: AudioPreference) {
    player_advances_with(preference, None)
}

fn player_advances_with(preference: AudioPreference, app_like: Option<f32>) {
    if Player::devices().is_empty() {
        println!("no audio output device: skipped");
        return;
    }
    let s = resolve(preference);
    let player = Player::new(temp_dir(&format!("live-player-{:?}", s.itag)), 256 << 20).unwrap();
    let mut rx = player.subscribe();
    // Silent by default; LIVE_VOLUME=0.2 to listen.
    let volume = std::env::var("LIVE_VOLUME").ok().and_then(|v| v.parse().ok()).unwrap_or(0.0);
    player.set_volume(app_like.unwrap_or(volume));
    player.load(source(&s), 0, true);
    if app_like.is_some() {
        // What the desktop app does right after a load: drop the old preload, preload the next.
        player.cancel_preload();
        player.preload(source_keyed(&resolve_id(NEXT_ID, preference), NEXT_ID));
    }
    let start = Instant::now();
    let mut events = Vec::new();
    while start.elapsed() < Duration::from_secs(25) {
        while let Ok(ev) = rx.try_recv() {
            if let PlayerEvent::Error { message } = &ev {
                if message.contains("output") {
                    println!("no usable audio output ({message}): skipped");
                    return;
                }
            }
            if !matches!(ev, PlayerEvent::Position { .. }) {
                events.push(ev);
            }
        }
        if player.position_ms().unwrap_or(0) > 6_000 {
            break;
        }
        std::thread::sleep(Duration::from_millis(100));
    }
    let pos = player.position_ms().unwrap_or(0);
    println!("itag {:?}: position {pos} ms after {:?}; events {events:?}", s.itag, start.elapsed());
    assert!(!events.iter().any(|e| matches!(e, PlayerEvent::Error { .. })), "{events:?}");
    assert!(pos > 5_000, "position stuck at {pos}");
}

#[test]
#[ignore = "network + audio device"]
fn player_plays_aac_stream() {
    player_advances(AudioPreference::AacFirst);
}

#[test]
#[ignore = "network + audio device"]
fn player_plays_opus_stream() {
    player_advances(AudioPreference::OpusFirst);
}

#[test]
#[ignore = "network + audio device"]
fn player_plays_like_the_app_does() {
    // App volume 80 -> perceptual gain 0.64, a preload right after the load. Quiet: LIVE_VOLUME.
    let gain = std::env::var("LIVE_VOLUME").ok().and_then(|v| v.parse().ok()).unwrap_or(0.64f32 * 0.05);
    player_advances_with(AudioPreference::AacFirst, Some(gain));
    player_advances_with(AudioPreference::OpusFirst, Some(gain));
}
