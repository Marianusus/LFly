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

/// After a seek the audio is where a decode from the top has it, to the sample (pink noise, which
/// has no period to line up wrongly): the Automix starts both songs on the sample its plan names.
#[test]
fn seeks_are_sample_exact() {
    for name in ["noise.m4a", "noise.webm", "noise.flac"] {
        let source = Source::file(fixture(name));
        let full = decode_all(&source, &cache()).unwrap_or_else(|e| panic!("{name}: {e}"));
        let rate = full.rate as usize;
        let left: Vec<f32> = full.samples.iter().step_by(2).copied().collect();
        let mut dec = TrackDecoder::open(&source, &cache(), ReaderCtl::detached()).unwrap();
        assert_eq!(dec.seek(2_000).unwrap(), 2_000);
        let mut after = Vec::new();
        let mut buf = Vec::new();
        while after.len() < rate * 2 * 2 {
            buf.clear();
            if !dec.decode_next(&mut buf).unwrap() {
                break;
            }
            after.extend_from_slice(&buf);
        }
        let after: Vec<f32> = after.iter().step_by(2).copied().collect();
        // Compare 0.1 s from 0.2 s after the seek (codecs need a frame to settle), lag by lag.
        let (from, len) = (rate / 5, rate / 10);
        let at = 2 * rate + from;
        let best = (-2_000i64..=2_000)
            .map(|lag| {
                let err: f64 = (0..len).map(|i| {
                    let j = (at as i64 + lag + i as i64) as usize;
                    let d = after[from + i] as f64 - left[j] as f64;
                    d * d
                }).sum();
                (lag, err)
            })
            .min_by(|a, b| a.1.total_cmp(&b.1))
            .unwrap();
        // Opus in WebM lands a millisecond early (48 samples): nothing a beat match can hear.
        let allowed = if name.ends_with(".webm") { 48 } else { 1 };
        assert!(best.0.abs() <= allowed, "{name}: the audio after a seek is {} samples off", best.0);
    }
}
