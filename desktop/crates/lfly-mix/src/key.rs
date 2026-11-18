//! A song's key and how it is found, plus the waveform the transition editor draws (the phone's
//! `MusicalKey.kt`). Pure.

use std::f64::consts::PI;

use crate::fft::Fft;
use crate::util::jround;

/// A song's key: its tonic as a pitch class (0 is C) and whether it is minor.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct MusicalKey {
    pub tonic: u8,
    pub minor: bool,
}

const NAMES: [&str; 12] = ["C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B"];

impl MusicalKey {
    pub fn new(tonic: u8, minor: bool) -> Self {
        MusicalKey { tonic: tonic % 12, minor }
    }

    /// Position on the Camelot wheel, 1 to 12: neighbours a fifth apart sit next to each other.
    pub fn camelot_number(&self) -> i32 {
        let tonic = self.tonic as i32;
        let major = if self.minor { (tonic + 3) % 12 } else { tonic };
        ((major * 7) % 12 + 7) % 12 + 1
    }

    /// The DJ's shorthand, as Spotify's Mix shows it: "8A" is A minor, "8B" C major.
    pub fn camelot(&self) -> String {
        format!("{}{}", self.camelot_number(), if self.minor { "A" } else { "B" })
    }

    /// The usual name, such as "Am" or "F♯".
    pub fn name(&self) -> String {
        format!("{}{}", NAMES[self.tonic as usize % 12], if self.minor { "m" } else { "" })
    }

    /// Steps between two keys on the Camelot wheel: 0 for the same key, 1 for a neighbour (a fifth
    /// away, or the relative major or minor), which DJs treat as compatible.
    pub fn distance(&self, other: &MusicalKey) -> i32 {
        let diff = (self.camelot_number() - other.camelot_number()).abs();
        diff.min(12 - diff) + i32::from(self.minor != other.minor)
    }
}

const FFT_SIZE: usize = 8_192;
const HOP: usize = 4_096;
const MIN_HZ: f64 = 100.0;
const MAX_HZ: f64 = 4_000.0;
/// Below this correlation the music has no clear key (drums, noise): no answer.
const MIN_CORRELATION: f64 = 0.5;
/// Krumhansl-Kessler key profiles.
const MAJOR: [f64; 12] = [6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88];
const MINOR: [f64; 12] = [6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17];

/// The key of a stretch of music (`samples` mono at `sample_rate`): a chromagram correlated with the
/// key profiles. `None` when there is too little audio or no clear key.
pub fn detect(samples: &[f32], sample_rate: u32) -> Option<MusicalKey> {
    let chroma = chroma(samples, sample_rate)?;
    let mut best = None;
    let mut best_score = -1.0;
    for tonic in 0..12u8 {
        for minor in [false, true] {
            let profile = if minor { &MINOR } else { &MAJOR };
            let score = correlation(&chroma, |pc| profile[(pc + 12 - tonic as usize) % 12]);
            if score > best_score {
                best_score = score;
                best = Some(MusicalKey::new(tonic, minor));
            }
        }
    }
    best.filter(|_| best_score >= MIN_CORRELATION)
}

/// Energy per pitch class over the whole stretch, each frame weighted the same so loud bars do not
/// decide alone.
pub(crate) fn chroma(samples: &[f32], sample_rate: u32) -> Option<[f64; 12]> {
    if samples.len() < FFT_SIZE || sample_rate == 0 {
        return None;
    }
    let window: Vec<f64> = (0..FFT_SIZE).map(|i| 0.5 - 0.5 * (2.0 * PI * i as f64 / FFT_SIZE as f64).cos()).collect();
    let pitch_class: Vec<Option<usize>> = (0..FFT_SIZE / 2)
        .map(|bin| {
            let hz = bin as f64 * sample_rate as f64 / FFT_SIZE as f64;
            if !(MIN_HZ..=MAX_HZ).contains(&hz) {
                return None;
            }
            let midi = jround(69.0 + 12.0 * (hz / 440.0).ln() / 2f64.ln()) as i64;
            Some(midi.rem_euclid(12) as usize)
        })
        .collect();
    let fft = Fft::new(FFT_SIZE);
    let (mut real, mut imaginary) = (vec![0.0; FFT_SIZE], vec![0.0; FFT_SIZE]);
    let mut total = [0.0; 12];
    let mut frames = 0;
    let mut start = 0;
    while start + FFT_SIZE <= samples.len() {
        for i in 0..FFT_SIZE {
            real[i] = samples[start + i] as f64 * window[i];
            imaginary[i] = 0.0;
        }
        fft.transform(&mut real, &mut imaginary);
        let mut frame = [0.0; 12];
        for bin in 1..FFT_SIZE / 2 {
            if let Some(pc) = pitch_class[bin] {
                frame[pc] += (real[bin] * real[bin] + imaginary[bin] * imaginary[bin]).sqrt();
            }
        }
        let sum: f64 = frame.iter().sum();
        if sum > 1e-6 {
            for pc in 0..12 {
                total[pc] += frame[pc] / sum;
            }
            frames += 1;
        }
        start += HOP;
    }
    (frames > 0).then_some(total)
}

fn correlation(chroma: &[f64; 12], profile: impl Fn(usize) -> f64) -> f64 {
    let mean_c = chroma.iter().sum::<f64>() / 12.0;
    let mean_p = (0..12).map(&profile).sum::<f64>() / 12.0;
    let (mut covariance, mut var_c, mut var_p) = (0.0, 0.0, 0.0);
    for (pc, value) in chroma.iter().enumerate() {
        let c = value - mean_c;
        let p = profile(pc) - mean_p;
        covariance += c * p;
        var_c += c * c;
        var_p += p * p;
    }
    if var_c <= 0.0 || var_p <= 0.0 { 0.0 } else { covariance / (var_c * var_p).sqrt() }
}

/// A song stretch's loudness over time, for the transition editor to draw.
#[derive(Clone, Debug, PartialEq)]
pub struct Waveform {
    /// Where the first point is, in seconds on the song's timeline.
    pub start_seconds: f64,
    /// Points per second.
    pub rate: f64,
    /// Level of each point, 0..1 (relative to the stretch's loudest point).
    pub levels: Vec<f32>,
}

impl Waveform {
    pub const RATE: f64 = 20.0;

    pub fn end_seconds(&self) -> f64 {
        self.start_seconds + self.levels.len() as f64 / self.rate
    }

    pub fn compute(samples: &[f32], sample_rate: u32, start_seconds: f64) -> Option<Waveform> {
        if samples.is_empty() || sample_rate == 0 {
            return None;
        }
        let slot = ((sample_rate as f64 / Self::RATE) as usize).max(1);
        let count = samples.len() / slot;
        if count == 0 {
            return None;
        }
        let mut levels: Vec<f32> = (0..count)
            .map(|i| {
                let sum: f64 = samples[i * slot..(i + 1) * slot].iter().map(|s| (s * s) as f64).sum();
                (sum / slot as f64).sqrt() as f32
            })
            .collect();
        let loudest = levels.iter().copied().fold(f32::MIN, f32::max);
        if loudest <= 1e-6 {
            return None;
        }
        for level in &mut levels {
            *level = (*level / loudest).clamp(0.0, 1.0);
        }
        Some(Waveform { start_seconds, rate: sample_rate as f64 / slot as f64, levels })
    }
}

#[cfg(test)]
mod tests {
    //! The key part of the phone's PlaylistMixTest, case for case.
    use super::*;

    fn tones(rate: u32, seconds: f64, hz: &[f64]) -> Vec<f32> {
        (0..(rate as f64 * seconds) as usize).map(|i| hz.iter().map(|h| 0.2 * (2.0 * PI * h * i as f64 / rate as f64).sin()).sum::<f64>() as f32).collect()
    }

    #[test]
    fn keys_sit_on_the_camelot_wheel_like_a_dj_reads_them() {
        assert_eq!(MusicalKey::new(0, false).camelot(), "8B"); // C major
        assert_eq!(MusicalKey::new(9, true).camelot(), "8A"); // A minor
        assert_eq!(MusicalKey::new(7, false).camelot(), "9B"); // G major
        assert_eq!(MusicalKey::new(5, false).camelot(), "7B"); // F major
        assert_eq!(MusicalKey::new(0, true).camelot(), "5A"); // C minor
        assert_eq!(MusicalKey::new(11, false).camelot(), "1B"); // B major
        assert_eq!(MusicalKey::new(9, true).name(), "Am");
        assert_eq!(MusicalKey::new(6, false).name(), "F♯");
    }

    #[test]
    fn neighbours_on_the_wheel_are_one_step_apart() {
        let a_minor = MusicalKey::new(9, true);
        assert_eq!(a_minor.distance(&a_minor), 0);
        assert_eq!(a_minor.distance(&MusicalKey::new(0, false)), 1); // relative major, 8B
        assert_eq!(a_minor.distance(&MusicalKey::new(4, true)), 1); // E minor, 9A
        assert_eq!(a_minor.distance(&MusicalKey::new(2, true)), 1); // D minor, 7A
        // 12A and 1A are neighbours across the top of the wheel.
        assert_eq!(MusicalKey::new(1, true).distance(&MusicalKey::new(6, true)), 1);
        assert!(a_minor.distance(&MusicalKey::new(3, true)) >= 5);
    }

    #[test]
    fn a_sustained_chord_is_heard_in_its_key() {
        let rate = 22_050;
        // A minor triad over an A bass: A2, A3, C4, E4.
        let found = detect(&tones(rate, 6.0, &[110.0, 220.0, 261.63, 329.63]), rate).expect("a key");
        assert_eq!(found, MusicalKey::new(9, true));
        // C major triad over a C bass: C3, C4, E4, G4.
        let major = detect(&tones(rate, 6.0, &[130.81, 261.63, 329.63, 392.0]), rate).expect("a key");
        assert_eq!(major, MusicalKey::new(0, false));
    }

    #[test]
    fn silence_and_very_short_audio_have_no_key() {
        assert_eq!(detect(&vec![0.0; 22_050 * 3], 22_050), None);
        assert_eq!(detect(&[0.0; 100], 22_050), None);
    }

    #[test]
    fn the_waveform_follows_the_level_and_stays_in_range() {
        let rate = 22_050u32;
        let sine = |amp: f64| (0..rate).map(move |i| (amp * (2.0 * PI * 440.0 * i as f64 / rate as f64).sin()) as f32);
        let samples: Vec<f32> = sine(0.1).chain(sine(0.8)).collect();
        let wave = Waveform::compute(&samples, rate, 10.0).expect("a waveform");
        assert!((wave.start_seconds - 10.0).abs() < 1e-9);
        assert!((wave.rate - Waveform::RATE).abs() < 0.5);
        assert!(wave.levels.iter().all(|l| (0.0..=1.0).contains(l)));
        assert!(wave.levels[0] < 0.2);
        assert!(*wave.levels.last().unwrap() > 0.95);
        assert!((wave.end_seconds() - 12.0).abs() < 0.1);
    }
}
