//! What a DJ mixer does to one deck, as numbers: the filter design (RBJ audio EQ cookbook) behind
//! the three-band EQ and the sweeping filter, ported from the Android app's `DjFilterMath.kt` and
//! the coefficient part of `DjFilterProcessor.kt`.
//!
//! Nothing here touches audio buffers on its own account: [`DeckFilters::design`] turns the mixer
//! settings of one instant into coefficients, and [`BiquadState`] is the per-channel history the
//! engine runs them with.

use std::f64::consts::{FRAC_1_SQRT_2, PI};

/// Corner of the bass band, where a DJ mixer's low EQ sits.
pub const BASS_HZ: f64 = 180.0;

/// A "kill": the low band all the way down.
pub const BASS_KILL_DB: f32 = -30.0;

/// Centre of the mid band and corner of the high band, where a DJ mixer's other two knobs sit.
pub const MID_HZ: f64 = 1_000.0;
pub const HIGH_HZ: f64 = 4_000.0;

/// The mid band is wide, about two octaves, so mid and high cuts together leave no hole.
pub const MID_Q: f64 = 0.5;

/// A filter without resonance: Butterworth, flat up to the corner.
pub const FLAT_Q: f64 = FRAC_1_SQRT_2;

/// Resonance stays where a sweep can sing without whistling or going unstable.
pub const MAX_Q: f64 = 4.0;

/// Below this a high-pass changes nothing audible; it is skipped.
pub const OPEN_HIGH_PASS_HZ: f64 = 20.0;

/// A low-pass this close to Nyquist (as a share of the sample rate) changes nothing audible; it is skipped.
pub const OPEN_LOW_PASS_FRACTION: f64 = 0.45;

/// A setting closer than this to its rest value counts as at rest, and its filter is skipped.
const SNAP: f64 = 1e-3;

/// A second-order filter, normalised so that a0 is 1.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Biquad {
    pub b0: f64,
    pub b1: f64,
    pub b2: f64,
    pub a1: f64,
    pub a2: f64,
}

impl Biquad {
    pub const IDENTITY: Biquad = Biquad { b0: 1.0, b1: 0.0, b2: 0.0, a1: 0.0, a2: 0.0 };
}

/// History of one filter on one channel (direct form I, which tolerates coefficients that move).
#[derive(Clone, Copy, Debug, Default)]
pub struct BiquadState {
    x1: f64,
    x2: f64,
    y1: f64,
    y2: f64,
}

impl BiquadState {
    pub fn run(&mut self, c: &Biquad, x: f64) -> f64 {
        let y = c.b0 * x + c.b1 * self.x1 + c.b2 * self.x2 - c.a1 * self.y1 - c.a2 * self.y2;
        self.x2 = self.x1;
        self.x1 = x;
        self.y2 = self.y1;
        self.y1 = y;
        y
    }

    /// While a filter is skipped its output is its input: keeping the history so avoids a click on return.
    pub fn pass(&mut self, x: f64) {
        self.x2 = self.x1;
        self.x1 = x;
        self.y2 = self.y1;
        self.y1 = x;
    }

    pub fn clear(&mut self) {
        *self = Self::default();
    }
}

/// One deck's mixer settings: a fader (`gain`), a three-band EQ (`bass_db`, `mid_db`, `high_db`)
/// and a sweeping filter (`high_pass_hz` / `low_pass_hz`) with its `resonance` (Q). Zero means the
/// filter is open.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct DjFilterSettings {
    pub gain: f32,
    pub bass_db: f32,
    pub high_pass_hz: f32,
    pub low_pass_hz: f32,
    pub mid_db: f32,
    pub high_db: f32,
    pub resonance: f32,
}

impl DjFilterSettings {
    pub const NEUTRAL: DjFilterSettings = DjFilterSettings {
        gain: 1.0,
        bass_db: 0.0,
        high_pass_hz: 0.0,
        low_pass_hz: 0.0,
        mid_db: 0.0,
        high_db: 0.0,
        resonance: FLAT_Q as f32,
    };

    /// The Q the sweeping filter runs with: never below flat, never above [`MAX_Q`].
    pub fn q(&self) -> f64 {
        (self.resonance as f64).clamp(FLAT_Q, MAX_Q)
    }

    /// The high-pass corner in Hz. An open high-pass sits at its open frequency, so a sweep starts
    /// from where it is inaudible.
    pub fn high_pass(&self, sample_rate: f64) -> f64 {
        let top = (OPEN_LOW_PASS_FRACTION * sample_rate).max(OPEN_HIGH_PASS_HZ);
        (self.high_pass_hz as f64).clamp(OPEN_HIGH_PASS_HZ, top)
    }

    /// The low-pass corner in Hz; open (zero) sits just under Nyquist.
    pub fn low_pass(&self, sample_rate: f64) -> f64 {
        let open = (OPEN_LOW_PASS_FRACTION * sample_rate).max(OPEN_HIGH_PASS_HZ);
        if self.low_pass_hz <= 0.0 {
            open
        } else {
            (self.low_pass_hz as f64).clamp(OPEN_HIGH_PASS_HZ, open)
        }
    }
}

impl Default for DjFilterSettings {
    fn default() -> Self {
        Self::NEUTRAL
    }
}

/// The coefficients one deck runs at one instant, in the order the phone applies them: bass shelf,
/// mid bell, high shelf, high-pass, low-pass, then the gain. `None` means that stage is at rest:
/// feed it [`BiquadState::pass`] instead of [`BiquadState::run`], so it comes back without a click.
///
/// The phone does not jump to new settings: it glides gain, the three dB values, Q and the two
/// corners (in log frequency) towards them with a 30 ms time constant, redesigning every 32 frames.
/// That glide belongs to the engine; it can keep its own gliding [`DjFilterSettings`] and call
/// [`DeckFilters::design`] once per block.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct DeckFilters {
    pub gain: f64,
    pub bass: Option<Biquad>,
    pub mid: Option<Biquad>,
    pub high: Option<Biquad>,
    pub high_pass: Option<Biquad>,
    pub low_pass: Option<Biquad>,
}

impl DeckFilters {
    pub fn design(settings: &DjFilterSettings, sample_rate: f64) -> DeckFilters {
        let bass_db = settings.bass_db as f64;
        let mid_db = settings.mid_db as f64;
        let high_db = settings.high_db as f64;
        let q = settings.q();
        let high_pass = settings.high_pass(sample_rate);
        let low_pass = settings.low_pass(sample_rate);
        // Open is judged in log frequency, as the phone's glide runs there.
        let high_pass_on = high_pass.ln() > OPEN_HIGH_PASS_HZ.ln() + SNAP;
        let low_pass_on = low_pass.ln() < (OPEN_LOW_PASS_FRACTION * sample_rate).ln() - SNAP;
        DeckFilters {
            gain: settings.gain as f64,
            bass: (bass_db.abs() > SNAP).then(|| low_shelf(sample_rate, BASS_HZ, bass_db, FLAT_Q)),
            mid: (mid_db.abs() > SNAP).then(|| peaking(sample_rate, MID_HZ, mid_db, MID_Q)),
            high: (high_db.abs() > SNAP).then(|| high_shelf(sample_rate, HIGH_HZ, high_db, FLAT_Q)),
            high_pass: high_pass_on.then(|| self::high_pass(sample_rate, high_pass, q)),
            low_pass: low_pass_on.then(|| self::low_pass(sample_rate, low_pass, q)),
        }
    }

    /// True when every stage is at rest and the gain is one: the audio can pass untouched.
    pub fn is_neutral(&self) -> bool {
        (self.gain - 1.0).abs() <= SNAP
            && self.bass.is_none()
            && self.mid.is_none()
            && self.high.is_none()
            && self.high_pass.is_none()
            && self.low_pass.is_none()
    }
}

/// History for one channel of a deck, with the stages run in the phone's order.
#[derive(Clone, Copy, Debug, Default)]
pub struct DeckState {
    bass: BiquadState,
    mid: BiquadState,
    high: BiquadState,
    high_pass: BiquadState,
    low_pass: BiquadState,
}

impl DeckState {
    /// One sample of one channel through the deck (the gain included).
    pub fn run(&mut self, filters: &DeckFilters, x: f64) -> f64 {
        fn stage(state: &mut BiquadState, c: &Option<Biquad>, x: f64) -> f64 {
            match c {
                Some(c) => state.run(c, x),
                None => {
                    state.pass(x);
                    x
                }
            }
        }
        let x = stage(&mut self.bass, &filters.bass, x);
        let x = stage(&mut self.mid, &filters.mid, x);
        let x = stage(&mut self.high, &filters.high, x);
        let x = stage(&mut self.high_pass, &filters.high_pass, x);
        let x = stage(&mut self.low_pass, &filters.low_pass, x);
        x * filters.gain
    }

    pub fn clear(&mut self) {
        *self = Self::default();
    }
}

/// A low shelf; `q` is [`FLAT_Q`] on the mixer.
pub fn low_shelf(sample_rate: f64, freq: f64, gain_db: f64, q: f64) -> Biquad {
    let a = 10f64.powf(gain_db / 40.0);
    let w = 2.0 * PI * freq / sample_rate;
    let cos_w = w.cos();
    let alpha = w.sin() / (2.0 * q);
    let two_root_a_alpha = 2.0 * a.sqrt() * alpha;
    let a0 = (a + 1.0) + (a - 1.0) * cos_w + two_root_a_alpha;
    normalise(
        a0,
        a * ((a + 1.0) - (a - 1.0) * cos_w + two_root_a_alpha),
        2.0 * a * ((a - 1.0) - (a + 1.0) * cos_w),
        a * ((a + 1.0) - (a - 1.0) * cos_w - two_root_a_alpha),
        -2.0 * ((a - 1.0) + (a + 1.0) * cos_w),
        (a + 1.0) + (a - 1.0) * cos_w - two_root_a_alpha,
    )
}

/// A high shelf; `q` is [`FLAT_Q`] on the mixer.
pub fn high_shelf(sample_rate: f64, freq: f64, gain_db: f64, q: f64) -> Biquad {
    let a = 10f64.powf(gain_db / 40.0);
    let w = 2.0 * PI * freq / sample_rate;
    let cos_w = w.cos();
    let alpha = w.sin() / (2.0 * q);
    let two_root_a_alpha = 2.0 * a.sqrt() * alpha;
    let a0 = (a + 1.0) - (a - 1.0) * cos_w + two_root_a_alpha;
    normalise(
        a0,
        a * ((a + 1.0) + (a - 1.0) * cos_w + two_root_a_alpha),
        -2.0 * a * ((a - 1.0) + (a + 1.0) * cos_w),
        a * ((a + 1.0) + (a - 1.0) * cos_w - two_root_a_alpha),
        2.0 * ((a - 1.0) - (a + 1.0) * cos_w),
        (a + 1.0) - (a - 1.0) * cos_w - two_root_a_alpha,
    )
}

/// A bell around `freq`: `gain_db` there, nothing far from it. `q` is [`MID_Q`] on the mixer.
pub fn peaking(sample_rate: f64, freq: f64, gain_db: f64, q: f64) -> Biquad {
    let a = 10f64.powf(gain_db / 40.0);
    let w = 2.0 * PI * freq / sample_rate;
    let cos_w = w.cos();
    let alpha = w.sin() / (2.0 * q);
    normalise(1.0 + alpha / a, 1.0 + alpha * a, -2.0 * cos_w, 1.0 - alpha * a, -2.0 * cos_w, 1.0 - alpha / a)
}

/// A high-pass; [`FLAT_Q`] is flat, more resonates at the corner.
pub fn high_pass(sample_rate: f64, freq: f64, q: f64) -> Biquad {
    let w = 2.0 * PI * freq / sample_rate;
    let cos_w = w.cos();
    let alpha = w.sin() / (2.0 * q);
    normalise(1.0 + alpha, (1.0 + cos_w) / 2.0, -(1.0 + cos_w), (1.0 + cos_w) / 2.0, -2.0 * cos_w, 1.0 - alpha)
}

/// A low-pass; [`FLAT_Q`] is flat, more resonates at the corner.
pub fn low_pass(sample_rate: f64, freq: f64, q: f64) -> Biquad {
    let w = 2.0 * PI * freq / sample_rate;
    let cos_w = w.cos();
    let alpha = w.sin() / (2.0 * q);
    normalise(1.0 + alpha, (1.0 - cos_w) / 2.0, 1.0 - cos_w, (1.0 - cos_w) / 2.0, -2.0 * cos_w, 1.0 - alpha)
}

/// The filter's gain at `freq`, in dB.
pub fn magnitude_db(c: &Biquad, freq: f64, sample_rate: f64) -> f64 {
    let w = 2.0 * PI * freq / sample_rate;
    let num_re = c.b0 + c.b1 * w.cos() + c.b2 * (2.0 * w).cos();
    let num_im = -c.b1 * w.sin() - c.b2 * (2.0 * w).sin();
    let den_re = 1.0 + c.a1 * w.cos() + c.a2 * (2.0 * w).cos();
    let den_im = -c.a1 * w.sin() - c.a2 * (2.0 * w).sin();
    let power = (num_re * num_re + num_im * num_im) / (den_re * den_re + den_im * den_im);
    10.0 * power.log10()
}

pub fn db_to_gain(db: f64) -> f64 {
    10f64.powf(db / 20.0)
}

fn normalise(a0: f64, b0: f64, b1: f64, b2: f64, a1: f64, a2: f64) -> Biquad {
    Biquad { b0: b0 / a0, b1: b1 / a0, b2: b2 / a0, a1: a1 / a0, a2: a2 / a0 }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::util::testing::sine;

    const RATE: f64 = 44_100.0;

    fn rms(values: &[f64]) -> f64 {
        (values.iter().map(|v| v * v).sum::<f64>() / values.len() as f64).sqrt()
    }

    /// A tone through a deck held at `settings`; the second half, once the filters have settled.
    fn settled(settings: DjFilterSettings, freq: f64, amplitude: f64) -> Vec<f64> {
        let filters = DeckFilters::design(&settings, RATE);
        let mut deck = DeckState::default();
        let out: Vec<f64> = sine(freq, RATE as u32, 1.0, amplitude).iter().map(|&x| deck.run(&filters, x as f64)).collect();
        out[out.len() / 2..].to_vec()
    }

    #[test]
    fn bass_kill_cuts_the_kick_and_leaves_the_vocals() {
        let shelf = low_shelf(RATE, BASS_HZ, BASS_KILL_DB as f64, FLAT_Q);
        assert!(magnitude_db(&shelf, 50.0, RATE) < -25.0);
        assert!(magnitude_db(&shelf, 2_000.0, RATE) > -1.0);
    }

    #[test]
    fn filters_are_flat_in_their_pass_band_and_3_db_down_at_the_corner() {
        let hp = high_pass(RATE, 1_000.0, FLAT_Q);
        let lp = low_pass(RATE, 1_000.0, FLAT_Q);
        assert!((magnitude_db(&hp, 1_000.0, RATE) + 3.0).abs() < 0.1);
        assert!((magnitude_db(&lp, 1_000.0, RATE) + 3.0).abs() < 0.1);
        assert!(magnitude_db(&hp, 15_000.0, RATE).abs() < 0.1);
        assert!(magnitude_db(&lp, 50.0, RATE).abs() < 0.1);
        assert!(magnitude_db(&hp, 100.0, RATE) < -35.0);
        assert!(magnitude_db(&lp, 10_000.0, RATE) < -35.0);
    }

    #[test]
    fn zero_db_shelf_changes_nothing() {
        let shelf = low_shelf(RATE, BASS_HZ, 0.0, FLAT_Q);
        for freq in [30.0, 180.0, 1_000.0, 12_000.0] {
            assert!(magnitude_db(&shelf, freq, RATE).abs() < 1e-9);
        }
    }

    #[test]
    fn at_rest_the_deck_passes_audio_through_bit_for_bit() {
        let filters = DeckFilters::design(&DjFilterSettings::NEUTRAL, RATE);
        assert!(filters.is_neutral());
        let mut deck = DeckState::default();
        for x in sine(440.0, RATE as u32, 0.2, 0.5) {
            assert_eq!(x as f64, deck.run(&filters, x as f64));
        }
    }

    #[test]
    fn a_bass_kill_silences_a_low_tone_and_keeps_a_high_one() {
        let kill = DjFilterSettings { bass_db: BASS_KILL_DB, ..DjFilterSettings::NEUTRAL };
        assert!(rms(&settled(kill, 50.0, 0.5)) < 0.5 / 2f64.sqrt() * 0.06);
        assert!((rms(&settled(kill, 3_000.0, 0.5)) - 0.5 / 2f64.sqrt()).abs() < 0.02);
    }

    #[test]
    fn mid_and_high_cuts_leave_the_other_bands_alone() {
        let both = DjFilterSettings { mid_db: -12.0, high_db: -12.0, ..DjFilterSettings::NEUTRAL };
        assert!((rms(&settled(both, 60.0, 0.5)) - 0.5 / 2f64.sqrt()).abs() < 0.03);
        let high = DjFilterSettings { high_db: -12.0, ..DjFilterSettings::NEUTRAL };
        assert!(rms(&settled(high, 10_000.0, 0.5)) < 0.5 / 2f64.sqrt() * 0.35);
    }

    #[test]
    fn a_resonant_high_pass_peaks_at_its_corner_and_stays_stable() {
        let resonant = high_pass(RATE, 1_000.0, 2.5);
        assert!(magnitude_db(&resonant, 1_000.0, RATE) > 6.0);
        let sweeping = DjFilterSettings { high_pass_hz: 2_000.0, resonance: 2.5, ..DjFilterSettings::NEUTRAL };
        let filters = DeckFilters::design(&sweeping, RATE);
        let mut deck = DeckState::default();
        for x in sine(2_000.0, RATE as u32, 1.0, 0.2) {
            let y = deck.run(&filters, x as f64);
            assert!(y.is_finite() && y.abs() <= 1.0);
        }
    }

    #[test]
    fn resonance_and_corners_stay_in_their_range() {
        let wild = DjFilterSettings { resonance: 40.0, high_pass_hz: 90_000.0, low_pass_hz: 1.0, ..DjFilterSettings::NEUTRAL };
        assert_eq!(MAX_Q, wild.q());
        assert_eq!(OPEN_LOW_PASS_FRACTION * RATE, wild.high_pass(RATE));
        assert_eq!(OPEN_HIGH_PASS_HZ, wild.low_pass(RATE));
        assert_eq!(FLAT_Q, DjFilterSettings { resonance: 0.0, ..DjFilterSettings::NEUTRAL }.q());
        // Open filters design to nothing at all.
        let open = DeckFilters::design(&DjFilterSettings { high_pass_hz: 20.0, ..DjFilterSettings::NEUTRAL }, RATE);
        assert!(open.high_pass.is_none() && open.low_pass.is_none());
    }
}
