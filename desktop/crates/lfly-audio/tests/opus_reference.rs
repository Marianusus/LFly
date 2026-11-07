//! The pure-Rust Opus decoder against the reference libopus (Linux only, system library).
#![cfg(target_os = "linux")]

mod common;

use symphonia::core::codecs::audio::well_known::CODEC_ID_OPUS;
use symphonia::core::formats::probe::Hint;
use symphonia::core::formats::{FormatOptions, TrackType};
use symphonia::core::io::{MediaSourceStream, MediaSourceStreamOptions};
use symphonia::core::meta::MetadataOptions;

fn packets(name: &str) -> Vec<Vec<u8>> {
    let file = std::fs::File::open(common::fixture(name)).unwrap();
    let mss = MediaSourceStream::new(Box::new(file), MediaSourceStreamOptions::default());
    let mut format = symphonia::default::get_probe()
        .probe(&Hint::new(), mss, FormatOptions::default(), MetadataOptions::default())
        .unwrap();
    let track = format.default_track(TrackType::Audio).unwrap();
    assert_eq!(track.codec_params.as_ref().unwrap().audio().unwrap().codec, CODEC_ID_OPUS);
    let id = track.id;
    let mut out = Vec::new();
    while let Ok(Some(p)) = format.next_packet() {
        if p.track_id == id {
            out.push(p.data.to_vec());
        }
    }
    out
}

#[test]
fn matches_libopus() {
    // 64 kb/s stereo is CELT (what music streams use): practically identical. 8 kb/s mono is SILK,
    // where the fixed-point path differs from the float libopus build by a few LSB.
    for (name, channels, min_snr) in [("sine.webm", 2usize, 80.0), ("long_cues_front.webm", 1, 45.0)] {
        let packets = packets(name);
        assert!(packets.len() > 100);

        let mut ours = opus_decoder::OpusDecoder::new(48_000, channels).unwrap();
        let mut err = 0;
        let reference = unsafe { opusic_sys::opus_decoder_create(48_000, channels as i32, &mut err) };
        assert!(!reference.is_null() && err == 0);

        let (mut signal, mut noise, mut max_diff) = (0f64, 0f64, 0i32);
        let mut a = vec![0i16; 5760 * channels];
        let mut b = vec![0i16; 5760 * channels];
        for p in &packets {
            let n = ours.decode(p, &mut a, false).unwrap();
            let m = unsafe {
                opusic_sys::opus_decode(reference, p.as_ptr(), p.len() as i32, b.as_mut_ptr(), 5760, 0)
            };
            assert_eq!(n as i32, m, "{name}: frame size");
            for i in 0..n * channels {
                let d = a[i] as i32 - b[i] as i32;
                max_diff = max_diff.max(d.abs());
                signal += (b[i] as f64).powi(2);
                noise += (d as f64).powi(2);
            }
        }
        unsafe { opusic_sys::opus_decoder_destroy(reference) };
        let snr = 10.0 * (signal / noise.max(1e-9)).log10();
        println!("{name}: max sample difference {max_diff}, SNR vs libopus {snr:.1} dB");
        assert!(snr > min_snr, "{name}: SNR {snr} dB against libopus");
    }
}
