//! Decodes every fixture completely (no audio device needed).

mod common;

use std::sync::Arc;

use common::*;
use lfly_audio::decode::TrackDecoder;
use lfly_audio::http::ReaderCtl;
use lfly_audio::{DiskCache, Source};

fn cache() -> Arc<DiskCache> {
    Arc::new(DiskCache::new(temp_dir("decode-cache"), 1 << 20).unwrap())
}

fn check(name: &str, rate: u32) {
    let d = decode_all(&Source::file(fixture(name)), &cache()).unwrap_or_else(|e| panic!("{name}: {e}"));
    println!("{name}: {} frames at {} Hz = {:.4} s, container says {:?} ms", d.samples.len() / 2, d.rate, d.seconds(), d.duration_ms);
    assert_eq!(d.rate, rate, "{name}: rate");
    assert!((d.seconds() - 3.0).abs() < 0.03, "{name}: decoded {} s", d.seconds());
    let dur = d.duration_ms.unwrap_or_else(|| panic!("{name}: no duration"));
    assert!((dur as i64 - 3000).abs() < 60, "{name}: duration {dur} ms");
    assert_tone_440(&d, name);
}

#[test]
fn webm_opus() {
    check("sine.webm", 48_000);
}

#[test]
fn ogg_opus() {
    check("sine.opus", 48_000);
}

#[test]
fn m4a_aac() {
    check("sine.m4a", 44_100);
}

#[test]
fn fragmented_m4a_aac() {
    check("sine_dash.m4a", 44_100);
}

#[test]
fn mp3() {
    check("sine.mp3", 44_100);
}

#[test]
fn flac_mono() {
    check("sine.flac", 44_100);
}

#[test]
fn ogg_vorbis() {
    check("sine.ogg", 44_100);
}

#[test]
fn wav_mono() {
    check("sine.wav", 22_050);
}

#[test]
fn seeking_lands_on_the_target() {
    for name in ["sine.webm", "sine.m4a", "sine.mp3", "sine.flac", "sine.ogg", "sine.opus"] {
        let mut dec = TrackDecoder::open(&Source::file(fixture(name)), &cache(), ReaderCtl::detached()).unwrap();
        assert_eq!(dec.seek(2_000).unwrap(), 2_000);
        let rest = drain(&mut dec).unwrap();
        assert!((rest.seconds() - 1.0).abs() < 0.03, "{name}: {} s after seeking to 2 s", rest.seconds());
        // And back to the start.
        dec.seek(0).unwrap();
        let all = drain(&mut dec).unwrap();
        assert!((all.seconds() - 3.0).abs() < 0.03, "{name}: {} s after seeking to 0", all.seconds());
    }
}

#[test]
fn resampling_keeps_the_length_and_the_tone() {
    let d = decode_all(&Source::file(fixture("sine.m4a")), &cache()).unwrap();
    let mut rs = lfly_audio::decode::Resample::new(44_100, 48_000);
    let mut out = Vec::new();
    for piece in d.samples.chunks(1_234 * 2) {
        rs.process(piece, &mut out);
    }
    rs.flush(&mut out);
    let expected = (d.samples.len() / 2) as f64 * 48_000.0 / 44_100.0;
    assert!(((out.len() / 2) as f64 - expected).abs() <= 1.0, "{} vs {expected}", out.len() / 2);
    let r = Decoded { samples: out, rate: 48_000, duration_ms: None };
    assert_tone_440(&r, "resampled");
}
