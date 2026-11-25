//! The input Beat This! was trained on, written from its published preprocessing (CPJKU/beat_this,
//! `LogMelSpect`) and ported from the Android app's `LogMelSpectrogram.kt`: mono 22 050 Hz, a
//! centred and reflect-padded STFT (n_fft 1024, hop 441, periodic Hann, `normalized` so the
//! transform is divided by sqrt(n_fft)), magnitude, 128 Slaney mel bands from 30 Hz to 11 kHz with
//! unit-peak triangles, then log1p(1000 · x). Any difference here shifts the model's input and
//! quietly costs beats, so none of it is a taste setting.

use std::f64::consts::PI;
use std::sync::OnceLock;

use crate::fft::Fft;

pub const SAMPLE_RATE: u32 = 22_050;
pub const FFT_SIZE: usize = 1_024;
pub const HOP: usize = 441;
pub const MELS: usize = 128;

/// Frames per second: one every 20 ms.
pub const FRAME_RATE: f64 = SAMPLE_RATE as f64 / HOP as f64;

const MIN_HZ: f64 = 30.0;
const MAX_HZ: f64 = 11_000.0;
const LOG_MULTIPLIER: f64 = 1_000.0;
const BINS: usize = FFT_SIZE / 2 + 1;

/// A log-mel spectrogram, row-major: frame f, band m is at `f * MELS + m`.
#[derive(Clone, Debug, PartialEq)]
pub struct LogMel {
    pub frames: usize,
    pub values: Vec<f32>,
}

impl LogMel {
    /// The [`MELS`] bands of one frame.
    pub fn frame(&self, frame: usize) -> &[f32] {
        &self.values[frame * MELS..(frame + 1) * MELS]
    }
}

struct Filter {
    first_bin: usize,
    weights: Vec<f64>,
}

struct Tables {
    window: Vec<f64>,
    filters: Vec<Filter>,
    fft: Fft,
}

fn tables() -> &'static Tables {
    static TABLES: OnceLock<Tables> = OnceLock::new();
    TABLES.get_or_init(|| Tables {
        window: (0..FFT_SIZE).map(|i| 0.5 - 0.5 * (2.0 * PI * i as f64 / FFT_SIZE as f64).cos()).collect(),
        filters: mel_filters(),
        fft: Fft::new(FFT_SIZE),
    })
}

/// `samples` must already be mono at [`SAMPLE_RATE`]. `None` when there is too little audio.
pub fn compute(samples: &[f32]) -> Option<LogMel> {
    let pad = FFT_SIZE / 2;
    let count = samples.len();
    if count <= pad + 1 {
        return None;
    }
    let tables = tables();

    // torch.stft(center=True, pad_mode="reflect"): frame f is centred on sample f * HOP.
    let mut padded = Vec::with_capacity(count + 2 * pad);
    padded.extend((0..pad).map(|index| samples[pad - index]));
    padded.extend_from_slice(samples);
    padded.extend((0..pad).map(|index| samples[count - 2 - index]));

    let frames = (padded.len() - FFT_SIZE) / HOP + 1;
    let mut values = vec![0f32; frames * MELS];
    let mut real = vec![0f64; FFT_SIZE];
    let mut imaginary = vec![0f64; FFT_SIZE];
    let mut magnitude = vec![0f64; BINS];
    let normalization = (FFT_SIZE as f64).sqrt();

    for frame in 0..frames {
        let start = frame * HOP;
        for index in 0..FFT_SIZE {
            real[index] = padded[start + index] as f64 * tables.window[index];
            imaginary[index] = 0.0;
        }
        tables.fft.transform(&mut real, &mut imaginary);
        for bin in 0..BINS {
            magnitude[bin] = (real[bin] * real[bin] + imaginary[bin] * imaginary[bin]).sqrt() / normalization;
        }
        let row = &mut values[frame * MELS..(frame + 1) * MELS];
        for (value, filter) in row.iter_mut().zip(&tables.filters) {
            let mut energy = 0.0;
            for (offset, weight) in filter.weights.iter().enumerate() {
                energy += magnitude[filter.first_bin + offset] * weight;
            }
            *value = (LOG_MULTIPLIER * energy).ln_1p() as f32;
        }
    }
    Some(LogMel { frames, values })
}

/// torchaudio's `melscale_fbanks(norm=None, mel_scale="slaney")`, stored sparsely.
fn mel_filters() -> Vec<Filter> {
    let mel_min = hz_to_mel(MIN_HZ);
    let mel_max = hz_to_mel(MAX_HZ);
    let edges: Vec<f64> =
        (0..MELS + 2).map(|i| mel_to_hz(mel_min + (mel_max - mel_min) * i as f64 / (MELS + 1) as f64)).collect();
    (0..MELS)
        .map(|mel| {
            let (left, centre, right) = (edges[mel], edges[mel + 1], edges[mel + 2]);
            let weights: Vec<f64> = (0..BINS)
                .map(|bin| {
                    let hz = bin as f64 * SAMPLE_RATE as f64 / FFT_SIZE as f64;
                    let rising = (hz - left) / (centre - left);
                    let falling = (right - hz) / (right - centre);
                    rising.min(falling).max(0.0)
                })
                .collect();
            let first = weights.iter().position(|&w| w > 0.0).unwrap_or(0);
            let last = weights.iter().rposition(|&w| w > 0.0).unwrap_or(first).max(first);
            Filter { first_bin: first, weights: weights[first..=last].to_vec() }
        })
        .collect()
}

const SLANEY_STEP_HZ: f64 = 200.0 / 3.0;
const SLANEY_LOG_START_HZ: f64 = 1_000.0;

fn slaney_log_step() -> f64 {
    6.4f64.ln() / 27.0
}

/// Hz to mel on the Slaney scale: linear up to 1 kHz, logarithmic above.
pub fn hz_to_mel(hz: f64) -> f64 {
    let log_start_mel = SLANEY_LOG_START_HZ / SLANEY_STEP_HZ;
    if hz >= SLANEY_LOG_START_HZ {
        log_start_mel + (hz / SLANEY_LOG_START_HZ).ln() / slaney_log_step()
    } else {
        hz / SLANEY_STEP_HZ
    }
}

pub fn mel_to_hz(mel: f64) -> f64 {
    let log_start_mel = SLANEY_LOG_START_HZ / SLANEY_STEP_HZ;
    if mel >= log_start_mel {
        SLANEY_LOG_START_HZ * (slaney_log_step() * (mel - log_start_mel)).exp()
    } else {
        SLANEY_STEP_HZ * mel
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::util::testing::sine;

    #[test]
    fn one_frame_every_hop_centred_like_torch_stft() {
        let spectrogram = compute(&vec![0.0; SAMPLE_RATE as usize]).unwrap();
        assert_eq!(SAMPLE_RATE as usize / HOP + 1, spectrogram.frames);
        assert_eq!(spectrogram.frames * MELS, spectrogram.values.len());
        assert!((FRAME_RATE - 50.0).abs() < 1e-9);
    }

    #[test]
    fn silence_is_zero_after_log1p() {
        let spectrogram = compute(&[0.0; 8_000]).unwrap();
        assert!(spectrogram.values.iter().all(|&v| v == 0.0));
    }

    #[test]
    fn too_little_audio_has_no_spectrogram() {
        assert_eq!(None, compute(&[0.0; FFT_SIZE / 2 + 1]));
        assert!(compute(&[0.0; FFT_SIZE / 2 + 2]).is_some());
    }

    #[test]
    fn a_1_khz_tone_lights_the_band_centred_on_1_khz() {
        let spectrogram = compute(&sine(1_000.0, SAMPLE_RATE, 1.0, 0.5)).unwrap();
        let row = spectrogram.frame(spectrogram.frames / 2);
        let mut loudest = 0;
        for (mel, &value) in row.iter().enumerate() {
            if value > row[loudest] {
                loudest = mel;
            }
        }
        let mel_min = hz_to_mel(30.0);
        let mel_max = hz_to_mel(11_000.0);
        let centre = mel_to_hz(mel_min + (mel_max - mel_min) * (loudest + 1) as f64 / 129.0);
        assert!((centre - 1_000.0).abs() < 60.0, "{centre}");
    }

    /// The level of a steady tone, worked out by hand from the definition: a Hann window gathers
    /// amplitude · n_fft / 4 in the tone's bin, the transform is divided by sqrt(n_fft), and the
    /// triangles around the tone weigh that. A bin-centred tone keeps the sum to its three bins.
    #[test]
    fn a_bin_centred_tone_has_the_level_the_definition_gives() {
        let bin = 46;
        let hz = bin as f64 * SAMPLE_RATE as f64 / FFT_SIZE as f64;
        let spectrogram = compute(&sine(hz, SAMPLE_RATE, 1.0, 0.5)).unwrap();
        let row = spectrogram.frame(spectrogram.frames / 2);
        let peak = 0.5 * FFT_SIZE as f64 / 4.0 / (FFT_SIZE as f64).sqrt();
        for (mel, filter) in tables().filters.iter().enumerate() {
            let mut energy = 0.0;
            for (offset, weight) in filter.weights.iter().enumerate() {
                let at = filter.first_bin + offset;
                let share = if at == bin { 1.0 } else if at + 1 == bin || at == bin + 1 { 0.5 } else { 0.0 };
                energy += peak * share * weight;
            }
            let expected = (1_000.0 * energy).ln_1p();
            assert!((row[mel] as f64 - expected).abs() < 1e-3, "band {mel}: {} against {expected}", row[mel]);
        }
    }

    #[test]
    fn slaney_mel_scale_round_trips_and_bends_at_1_khz() {
        for hz in [30.0, 500.0, 1_000.0, 4_000.0, 11_000.0] {
            assert!((mel_to_hz(hz_to_mel(hz)) - hz).abs() < 1e-6);
        }
        assert!((hz_to_mel(1_000.0) - 15.0).abs() < 1e-9);
    }
}
