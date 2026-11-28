//! A song split into its parts (drums, bass, other, vocals) by HT-Demucs v4 (Meta, MIT), as the
//! single-file ONNX export `htdemucs_fp16weights.onnx` (adowu/htdemucs-onnx on Hugging Face, MIT,
//! 166 MB, downloaded once by the core; not embedded).
//!
//! The model takes 7.8 s of 44.1 kHz stereo and gives the four stems of it; a song is cut into
//! such segments overlapping by a quarter, cross-faded back together (the reference `infer.py`).
//! On this PC's CPU a segment takes about a second and a half: a song of three minutes, half a
//! minute or so.

use std::path::Path;
use std::sync::atomic::AtomicBool;

/// The model's rate and segment.
pub const SAMPLE_RATE: u32 = 44_100;
pub const SEGMENT: usize = 343_980;
/// The stems in the model's order.
pub const NAMES: [&str; 4] = ["drums", "bass", "other", "vocals"];

/// The four stems of [stereo] (interleaved, 44.1 kHz), each interleaved stereo of the same length;
/// [progress] hears the share done; [cancel] stops it (an `Err`).
#[cfg(feature = "onnx")]
pub fn separate(model: &Path, stereo: &[f32], progress: impl Fn(f32), cancel: &AtomicBool) -> Result<[Vec<f32>; 4], String> {
    // One session, kept while songs are being split; dropped by [unload] (it holds ~1 GB).
    let mut guard = SESSION.lock().unwrap_or_else(|e| e.into_inner());
    if guard.is_none() {
        // A third of the cores at most: the decks, the interface and the beat analysis keep the rest.
        let threads = std::thread::available_parallelism().map_or(2, |n| (n.get() / 3).clamp(1, 6));
        let made = (|| -> ort::Result<ort::session::Session> {
            let env = crate::env()?;
            ort::session::Session::builder(&env)?.with_intra_threads(threads)?.commit_from_file(model)
        })();
        *guard = Some(made.map_err(|e| format!("stems model: {e}"))?);
        log::info!("stems model ready ({threads} threads)");
    }
    let session = guard.as_mut().ok_or("stems model missing")?;
    let total = stereo.len() / 2;
    let overlap = SEGMENT / 4;
    let stride = SEGMENT - overlap;
    let chunks = total.div_ceil(stride).max(1);
    let window: Vec<f32> = (0..SEGMENT)
        .map(|i| {
            let fade = |k: usize| k as f32 / (overlap - 1).max(1) as f32;
            if i < overlap {
                fade(i)
            } else if i >= SEGMENT - overlap {
                fade(SEGMENT - 1 - i)
            } else {
                1.
            }
        })
        .collect();
    let mut out: [Vec<f32>; 4] = std::array::from_fn(|_| vec![0f32; total * 2]);
    let mut weight = vec![0f32; total];
    let mut input = vec![0f32; 2 * SEGMENT];
    for c in 0..chunks {
        if cancel.load(std::sync::atomic::Ordering::Relaxed) {
            return Err("cancelled".into());
        }
        let start = c * stride;
        let end = (start + SEGMENT).min(total);
        if start >= end {
            break;
        }
        // Planar: the left channel, then the right; past the song, silence.
        input.iter_mut().for_each(|s| *s = 0.);
        for i in start..end {
            input[i - start] = stereo[2 * i];
            input[SEGMENT + i - start] = stereo[2 * i + 1];
        }
        let run = (|| -> ort::Result<Vec<f32>> {
            let x = ort::value::Tensor::from_array(([1usize, 2, SEGMENT], input.clone()))?;
            let res = session.run(ort::inputs!["mix" => x])?;
            let (_, y) = res["stems"].try_extract_tensor::<f32>()?;
            Ok(y.to_vec())
        })();
        let y = run.map_err(|e| format!("stems: {e}"))?;
        // (1, 4, 2, SEGMENT)
        if y.len() < 4 * 2 * SEGMENT {
            return Err(format!("stems: unexpected output of {} values", y.len()));
        }
        for (s, stem) in out.iter_mut().enumerate() {
            for i in start..end {
                let w = window[i - start];
                let k = i - start;
                stem[2 * i] += y[(s * 2) * SEGMENT + k] * w;
                stem[2 * i + 1] += y[(s * 2 + 1) * SEGMENT + k] * w;
            }
        }
        for i in start..end {
            weight[i] += window[i - start];
        }
        progress((c + 1) as f32 / chunks as f32);
    }
    for stem in out.iter_mut() {
        for i in 0..total {
            let w = weight[i].max(1e-8);
            stem[2 * i] /= w;
            stem[2 * i + 1] /= w;
        }
    }
    Ok(out)
}

#[cfg(feature = "onnx")]
static SESSION: std::sync::Mutex<Option<ort::session::Session>> = std::sync::Mutex::new(None);

/// The model let go of (its memory back) once no song waits to be split.
pub fn unload() {
    #[cfg(feature = "onnx")]
    {
        let mut guard = SESSION.lock().unwrap_or_else(|e| e.into_inner());
        if guard.take().is_some() {
            log::info!("stems model unloaded");
        }
    }
}

#[cfg(not(feature = "onnx"))]
pub fn separate(_: &Path, _: &[f32], _: impl Fn(f32), _: &AtomicBool) -> Result<[Vec<f32>; 4], String> {
    Err("this build has no ONNX Runtime".into())
}

#[cfg(all(test, feature = "onnx"))]
mod tests {
    use super::*;

    /// A tone with clicks through the downloaded model: four stems back, the clicks mostly in
    /// the drums, and how long it took.
    #[test]
    #[ignore = "needs the downloaded model"]
    fn a_tone_and_clicks_split() {
        let model = std::path::PathBuf::from(std::env::var("HOME").unwrap()).join(".local/share/lfly/models/htdemucs_fp16weights.onnx");
        let secs = 20usize;
        let n = SAMPLE_RATE as usize * secs;
        let mut stereo = vec![0f32; n * 2];
        for i in 0..n {
            let t = i as f32 / SAMPLE_RATE as f32;
            let tone = 0.2 * (t * 330. * std::f32::consts::TAU).sin();
            let beat = (t * 2.).fract();
            let click = if beat < 0.03 { 0.6 * (-beat * 120.).exp() * (t * 60. * std::f32::consts::TAU).sin() } else { 0. };
            stereo[2 * i] = tone + click;
            stereo[2 * i + 1] = tone + click;
        }
        let started = std::time::Instant::now();
        let stems = separate(&model, &stereo, |p| eprintln!("{:.0}%", p * 100.), &AtomicBool::new(false)).unwrap();
        let energy = |v: &[f32]| v.iter().map(|x| x * x).sum::<f32>() / v.len() as f32;
        for (k, s) in stems.iter().enumerate() {
            eprintln!("{}: rms {:.4}", NAMES[k], energy(s).sqrt());
        }
        eprintln!("{secs} s of audio in {:.1} s", started.elapsed().as_secs_f32());
        assert_eq!(stems[0].len(), stereo.len());
    }
}
