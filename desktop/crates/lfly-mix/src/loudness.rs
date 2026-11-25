//! How loud a stretch of music sounds, after ITU-R BS.1770 (K-weighting, 400 ms blocks, absolute
//! and relative gates). Measured on the mono analysis audio, so only differences between songs
//! matter. Ported from the Android app's `Loudness.kt`.

use crate::filter::{self, Biquad, BiquadState};

const BLOCK_SECONDS: f64 = 0.4;
const STEP_SECONDS: f64 = 0.1;
const ABSOLUTE_GATE: f64 = -70.0;
const RELATIVE_GATE: f64 = -10.0;

/// An Automix never turns the outgoing song down more than this ...
pub const MAX_CUT_DB: f64 = -6.0;

/// ... nor up more than this, to stay clear of clipping.
pub const MAX_BOOST_DB: f64 = 4.0;

/// Loudness in LUFS (mono), or `None` when there is too little or only silent audio.
pub fn lufs(samples: &[f32], sample_rate: u32) -> Option<f64> {
    let block = (BLOCK_SECONDS * sample_rate as f64) as usize;
    let step = (STEP_SECONDS * sample_rate as f64) as usize;
    if block == 0 || step == 0 || samples.len() < block {
        return None;
    }

    let mut weighting = KWeighting::new(sample_rate as f64);

    // Prefix sums make every overlapping block a subtraction; built straight from the filter,
    // so a minute of audio needs one array of doubles, not two.
    let mut sums = Vec::with_capacity(samples.len() + 1);
    sums.push(0.0);
    let mut sum = 0.0;
    for &x in samples {
        let y = weighting.run(x as f64);
        sum += y * y;
        sums.push(sum);
    }
    let mut powers = Vec::new();
    let mut start = 0;
    while start + block <= samples.len() {
        powers.push((sums[start + block] - sums[start]) / block as f64);
        start += step;
    }

    let loud: Vec<f64> = powers.into_iter().filter(|&p| to_lufs(p) > ABSOLUTE_GATE).collect();
    if loud.is_empty() {
        return None;
    }
    let gate = to_lufs(average(&loud)) + RELATIVE_GATE;
    let gated: Vec<f64> = loud.into_iter().filter(|&p| to_lufs(p) > gate).collect();
    Some(to_lufs(average(&gated)))
}

/// Gain, in dB, that brings the outgoing song to the incoming song's level: their difference, kept
/// between [`MAX_CUT_DB`] and [`MAX_BOOST_DB`]; zero when either loudness is unknown.
pub fn match_gain_db(outgoing_lufs: Option<f64>, incoming_lufs: Option<f64>) -> f64 {
    match (outgoing_lufs, incoming_lufs) {
        (Some(outgoing), Some(incoming)) => (incoming - outgoing).clamp(MAX_CUT_DB, MAX_BOOST_DB),
        _ => 0.0,
    }
}

/// A gain in dB as the factor the transition curves take (`outgoing_gain`).
pub fn gain(db: f64) -> f32 {
    10f64.powf(db / 20.0) as f32
}

/// BS.1770 loudness of a mean square of K-weighted samples.
pub fn to_lufs(mean_square: f64) -> f64 {
    -0.691 + 10.0 * mean_square.max(1e-12).log10()
}

fn average(values: &[f64]) -> f64 {
    values.iter().sum::<f64>() / values.len() as f64
}

/// BS.1770 K-weighting (the head's high shelf, then a low cut), run sample by sample on one channel.
pub struct KWeighting {
    shelf: Biquad,
    high_pass: Biquad,
    shelf_state: BiquadState,
    high_pass_state: BiquadState,
}

impl KWeighting {
    pub fn new(sample_rate: f64) -> Self {
        KWeighting {
            shelf: filter::high_shelf(sample_rate, 1_681.97, 4.0, filter::FLAT_Q),
            high_pass: filter::high_pass(sample_rate, 38.13, 0.5),
            shelf_state: BiquadState::default(),
            high_pass_state: BiquadState::default(),
        }
    }

    pub fn run(&mut self, x: f64) -> f64 {
        let shelved = self.shelf_state.run(&self.shelf, x);
        self.high_pass_state.run(&self.high_pass, shelved)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::util::testing::sine;

    #[test]
    fn a_1_khz_sine_at_full_scale_reads_about_minus_3_lufs_and_6_db_down_reads_6_db_less() {
        let rate = 22_050;
        let loud = lufs(&sine(1_000.0, rate, 5.0, 1.0), rate).unwrap();
        let quiet = lufs(&sine(1_000.0, rate, 5.0, 0.5), rate).unwrap();
        assert!((loud + 3.0).abs() < 0.3, "{loud}");
        assert!((loud - quiet - 6.02).abs() < 0.05);
    }

    #[test]
    fn silence_and_too_short_audio_have_no_loudness() {
        assert_eq!(None, lufs(&vec![0.0; 22_050], 22_050));
        assert_eq!(None, lufs(&[0.0; 100], 22_050));
        assert_eq!(None, lufs(&[0.5; 100], 0));
    }

    #[test]
    fn level_match_is_limited_and_missing_data_changes_nothing() {
        assert!((match_gain_db(Some(-8.0), Some(-11.0)) + 3.0).abs() < 1e-9);
        assert_eq!(MAX_CUT_DB, match_gain_db(Some(-5.0), Some(-30.0)));
        assert_eq!(MAX_BOOST_DB, match_gain_db(Some(-30.0), Some(-5.0)));
        assert_eq!(0.0, match_gain_db(None, Some(-10.0)));
        assert!((gain(-6.0) - 0.501).abs() < 1e-3);
    }
}
