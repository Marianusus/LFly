//! Beats and downbeats of a stretch of a song (the Android app's `BeatTracker.kt`): Beat This!
//! (CPJKU, ISMIR 2024; code and weights MIT) reads a log-mel spectrogram and answers, for every
//! 20 ms frame, how likely a beat and a downbeat are; the grid is read from those answers.
//!
//! Built without the `onnx` feature, [`track`] always answers `None`: a missing grid only means a
//! plain crossfade.

use lfly_mix::beat_grid::BeatGrid;
use lfly_mix::mel;

/// The model was trained on 30 s chunks; the edge frames of each lack context and are dropped.
pub const CHUNK_FRAMES: usize = 1_500;
pub const BORDER_FRAMES: usize = 6;

/// Whether this build can track beats at all.
pub fn available() -> bool {
    cfg!(feature = "onnx")
}

/// The grid of `samples` (mono at [`mel::SAMPLE_RATE`]) starting `offset_seconds` into the song.
/// Takes a few hundred ms of CPU per 30 s; call it off any thread that must stay responsive.
pub fn track(samples: &[f32], offset_seconds: f64) -> Option<BeatGrid> {
    let spectrogram = mel::compute(samples)?;
    let (beat, downbeat) = logits(&spectrogram)?;
    BeatGrid::from_logits(&beat, &downbeat, mel::FRAME_RATE, offset_seconds)
}

/// The model's beat and downbeat logits for every frame, chunk by chunk (stride 1 488 frames,
/// the 6 border frames of each inner edge dropped).
#[cfg(feature = "onnx")]
fn logits(spec: &mel::LogMel) -> Option<(Vec<f32>, Vec<f32>)> {
    use std::sync::{Mutex, OnceLock};
    static SESSION: OnceLock<Option<Mutex<ort::session::Session>>> = OnceLock::new();
    static MODEL: &[u8] = include_bytes!("../../../../app/src/main/assets/beat_this_final0_q8.onnx");

    let session = SESSION.get_or_init(|| {
        let made = (|| -> ort::Result<ort::session::Session> {
            let env = ort::init().with_name("lfly-beat").build()?;
            ort::session::Session::builder(&env)?.with_intra_threads(2)?.commit_from_memory(MODEL)
        })();
        match made {
            Ok(s) => {
                log::info!("beat model ready");
                Some(Mutex::new(s))
            }
            Err(e) => {
                log::warn!("beat model unavailable: {e}");
                None
            }
        }
    });
    let mut session = session.as_ref()?.lock().unwrap_or_else(|e| e.into_inner());
    let frames = spec.frames;
    let mels = mel::MELS;
    let (mut beat, mut down) = (vec![0f32; frames], vec![0f32; frames]);
    let stride = CHUNK_FRAMES - 2 * BORDER_FRAMES;
    let mut start = 0;
    while start < frames {
        let length = CHUNK_FRAMES.min(frames - start);
        if start > 0 && length <= 2 * BORDER_FRAMES {
            break;
        }
        let mut chunk = Vec::with_capacity(length * mels);
        for f in start..start + length {
            chunk.extend_from_slice(spec.frame(f));
        }
        let run = (|| -> ort::Result<(Vec<f32>, Vec<f32>)> {
            let input = ort::value::Tensor::from_array(([1usize, length, mels], chunk))?;
            let out = session.run(ort::inputs![input])?;
            let (_, b) = out[0].try_extract_tensor::<f32>()?;
            let (_, d) = out[1].try_extract_tensor::<f32>()?;
            Ok((b.to_vec(), d.to_vec()))
        })();
        let (b, d) = match run {
            Ok(v) => v,
            Err(e) => {
                log::warn!("beat inference failed: {e}");
                return None;
            }
        };
        let keep_from = if start == 0 { 0 } else { BORDER_FRAMES };
        let last = start + length >= frames;
        let keep_to = if last { length } else { length - BORDER_FRAMES };
        for i in keep_from..keep_to.min(b.len()).min(d.len()) {
            beat[start + i] = b[i];
            down[start + i] = d[i];
        }
        if last {
            break;
        }
        start += stride;
    }
    Some((beat, down))
}

#[cfg(not(feature = "onnx"))]
fn logits(_: &mel::LogMel) -> Option<(Vec<f32>, Vec<f32>)> {
    None
}

#[cfg(all(test, feature = "onnx"))]
mod tests {
    use super::*;

    /// A click every 0.5 s (120 BPM) with an accent every fourth: the model's own answer.
    fn clicks(seconds: f64, bpm: f64) -> Vec<f32> {
        let sr = mel::SAMPLE_RATE as usize;
        let mut s = vec![0f32; (seconds * sr as f64) as usize];
        let every = 60.0 / bpm;
        let mut k = 0;
        loop {
            let at = (k as f64 * every * sr as f64) as usize;
            if at >= s.len() {
                break;
            }
            let amp = if k % 4 == 0 { 0.9 } else { 0.5 };
            for i in 0..900.min(s.len() - at) {
                let env = (-(i as f32) / 150.0).exp();
                s[at + i] += amp * env * ((i as f32) * 2.0 * std::f32::consts::PI * 1_000.0 / sr as f32).sin();
            }
            k += 1;
        }
        s
    }

    #[test]
    fn a_click_track_gives_its_tempo_across_chunks() {
        for bpm in [120.0, 128.0] {
            // 59 s: two model chunks, as the Automix analyses.
            let grid = track(&clicks(59.0, bpm), 10.0).expect("grid");
            assert!((grid.bpm() - bpm).abs() < 0.5, "{bpm}: {}", grid.bpm());
            assert!(grid.beats()[0] >= 10.0 && grid.beats()[0] < 10.0 + 60.0 / bpm + 0.05);
            assert!(grid.confidence() > 0.55);
        }
    }
}
