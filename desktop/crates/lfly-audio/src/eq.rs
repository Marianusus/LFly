//! Five-band equalizer and bass boost, ported from the Android app's `EqualizerProcessor.kt`.
//!
//! RBJ cookbook biquads (peaking bands at 60/230/910/3600 Hz with Q 1.0, a high shelf at 10 kHz and
//! a 90 Hz low shelf for the bass boost), gains gliding 1 dB per buffer so moving a slider never
//! clicks, an automatic preamp taking 0.6x the loudest boost off, and a tanh soft limiter. A flat
//! curve passes the audio through untouched, bit for bit.

use serde::{Deserialize, Serialize};

/// What the equalizer should sound like.
#[derive(Clone, Copy, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EqCurve {
    pub enabled: bool,
    /// Gains of the five bands in dB, lowest band first.
    pub bands_db: [f32; 5],
    /// Bass boost, 0..1000 as the slider gives it.
    pub bass_boost: i32,
}

impl EqCurve {
    pub const MAX_BASS_DB: f32 = 12.0;

    pub const FLAT: EqCurve = EqCurve { enabled: false, bands_db: [0.0; 5], bass_boost: 0 };

    pub fn bass_db(&self) -> f32 {
        self.bass_boost.clamp(0, 1000) as f32 / 1000.0 * Self::MAX_BASS_DB
    }

    /// Nothing to do: the audio passes through untouched, bit for bit.
    pub fn is_flat(&self) -> bool {
        !self.enabled || (self.bands_db.iter().all(|b| b.abs() < 0.05) && self.bass_boost <= 0)
    }
}

impl Default for EqCurve {
    fn default() -> Self {
        Self::FLAT
    }
}

/// Center frequency of each band; the top one is a shelf, so it is placed lower.
const FREQS: [f64; 5] = [60.0, 230.0, 910.0, 3600.0, 10_000.0];
const Q: f64 = 1.0;
const BASS_HZ: f64 = 90.0;
/// Gains glide this many dB per buffer, so moving a slider never clicks.
const GLIDE_DB: f64 = 1.0;
/// Part of the loudest boost taken off up front; the soft limiter catches the rest.
const HEADROOM_SHARE: f64 = 0.6;
/// Where the limiter starts to round the peaks off, and the most it lets out.
const KNEE: f64 = 0.89;
const CEILING: f64 = 0.98;

/// A sample type the equalizer can work on in place.
pub trait EqSample: Copy {
    fn to_unit(self) -> f64;
    fn from_unit(x: f64) -> Self;
}

impl EqSample for f32 {
    fn to_unit(self) -> f64 {
        self as f64
    }
    fn from_unit(x: f64) -> Self {
        x as f32
    }
}

impl EqSample for i16 {
    fn to_unit(self) -> f64 {
        self as f64 / 32_768.0
    }
    fn from_unit(x: f64) -> Self {
        // Same as Kotlin's `(x * 32768).toInt().coerceIn(-32768, 32767)`.
        ((x * 32_768.0) as i32).clamp(-32_768, 32_767) as i16
    }
}

/// The equalizer for one interleaved stream. Never allocates after construction.
pub struct Equalizer {
    sample_rate: f64,
    channels: usize,
    curve: EqCurve,
    /// Gains the filters are set to now (5 bands, then bass, then preamp), gliding to the curve's.
    now: [f64; 7],
    filters: Vec<[Biquad; 6]>,
    bypassed: bool,
}

impl Equalizer {
    /// Configured and flushed, like the Media3 processor after `configure()` + `flush()`.
    pub fn new(sample_rate: u32, channels: usize, curve: EqCurve) -> Self {
        let mut eq = Equalizer {
            sample_rate: (sample_rate as f64).max(8_000.0),
            channels: channels.max(1),
            curve,
            now: [0.0; 7],
            filters: vec![[Biquad::default(); 6]; channels.max(1)],
            bypassed: true,
        };
        eq.flush();
        eq
    }

    pub fn curve(&self) -> EqCurve {
        self.curve
    }

    /// The curve to glide to from the next buffer on.
    pub fn set_curve(&mut self, curve: EqCurve) {
        self.curve = curve;
    }

    /// Jumps straight to the curve, with clean filter state (after a seek or a new stream).
    pub fn flush(&mut self) {
        for channel in &mut self.filters {
            for f in channel.iter_mut() {
                *f = Biquad::default();
            }
        }
        self.now = target_of(&self.curve);
        self.bypassed = self.curve.is_flat();
        self.design();
    }

    /// Processes one buffer of interleaved samples in place.
    pub fn process<S: EqSample>(&mut self, buf: &mut [S]) {
        if buf.is_empty() {
            return;
        }
        let curve = self.curve;
        if curve.is_flat() && self.bypassed {
            return;
        }
        if self.bypassed {
            // Coming back from rest: start from flat and glide in.
            self.now = [0.0; 7];
            for channel in &mut self.filters {
                for f in channel.iter_mut() {
                    f.clear();
                }
            }
            self.bypassed = false;
        }
        let target = target_of(&curve);
        if self.glide(&target) {
            self.design();
        }

        let channels = self.channels;
        let frames = buf.len() / channels;
        let preamp = 10f64.powf(self.now[6] / 20.0);
        // A partial frame at the end (never expected) is left as it is.
        for frame in buf[..frames * channels].chunks_exact_mut(channels) {
            for (ch, sample) in frame.iter_mut().enumerate() {
                let mut x = sample.to_unit() * preamp;
                for f in self.filters[ch].iter_mut() {
                    x = f.process(x);
                }
                *sample = S::from_unit(limit(x));
            }
        }

        // Settled at flat again: from the next buffer on, pass through untouched.
        if curve.is_flat() && self.now.iter().all(|g| g.abs() < 1e-3) {
            self.bypassed = true;
        }
    }

    /// Moves the gains a step toward `target`; true when anything changed.
    fn glide(&mut self, target: &[f64; 7]) -> bool {
        let mut moved = false;
        for (now, &t) in self.now.iter_mut().zip(target) {
            let diff = t - *now;
            if diff.abs() < 1e-4 {
                continue;
            }
            *now += diff.clamp(-GLIDE_DB, GLIDE_DB);
            moved = true;
        }
        moved
    }

    fn design(&mut self) {
        let rate = self.sample_rate;
        for channel in &mut self.filters {
            for b in 0..4 {
                channel[b].peaking(FREQS[b], Q, self.now[b], rate);
            }
            channel[4].high_shelf(FREQS[4], self.now[4], rate);
            channel[5].low_shelf(BASS_HZ, self.now[5], rate);
        }
    }
}

/// Band gains, bass and preamp the curve asks for; all zero when it is off.
fn target_of(curve: &EqCurve) -> [f64; 7] {
    let mut out = [0.0; 7];
    if curve.is_flat() {
        return out;
    }
    for (o, &b) in out[..5].iter_mut().zip(&curve.bands_db) {
        *o = b as f64;
    }
    out[5] = curve.bass_db() as f64;
    // The loudest the curve can get: the biggest band boost, with the bass shelf on top of the low band.
    let band_max = out[..5].iter().cloned().fold(f64::NEG_INFINITY, f64::max);
    let peak = band_max.max(out[0].max(0.0) + out[5]).max(0.0);
    out[6] = -peak * HEADROOM_SHARE;
    out
}

/// Leaves everything under the knee alone and rounds off what goes past it, instead of clipping.
fn limit(x: f64) -> f64 {
    let a = x.abs();
    if a <= KNEE {
        return x;
    }
    let room = CEILING - KNEE;
    let shaped = KNEE + room * ((a - KNEE) / room).tanh();
    if x < 0.0 {
        -shaped
    } else {
        shaped
    }
}

/// One second-order filter (RBJ cookbook), transposed direct form II.
#[derive(Clone, Copy, Debug)]
pub struct Biquad {
    b0: f64,
    b1: f64,
    b2: f64,
    a1: f64,
    a2: f64,
    z1: f64,
    z2: f64,
}

impl Default for Biquad {
    fn default() -> Self {
        Biquad { b0: 1.0, b1: 0.0, b2: 0.0, a1: 0.0, a2: 0.0, z1: 0.0, z2: 0.0 }
    }
}

impl Biquad {
    pub fn clear(&mut self) {
        self.z1 = 0.0;
        self.z2 = 0.0;
    }

    #[inline]
    pub fn process(&mut self, x: f64) -> f64 {
        let y = self.b0 * x + self.z1;
        self.z1 = self.b1 * x - self.a1 * y + self.z2;
        self.z2 = self.b2 * x - self.a2 * y;
        y
    }

    pub fn peaking(&mut self, freq: f64, q: f64, gain_db: f64, rate: f64) {
        if gain_db.abs() < 1e-4 {
            return self.pass_through();
        }
        let a = 10f64.powf(gain_db / 40.0);
        let w = 2.0 * std::f64::consts::PI * safe(freq, rate) / rate;
        let alpha = w.sin() / (2.0 * q);
        let c = w.cos();
        self.set(1.0 + alpha * a, -2.0 * c, 1.0 - alpha * a, 1.0 + alpha / a, -2.0 * c, 1.0 - alpha / a);
    }

    pub fn low_shelf(&mut self, freq: f64, gain_db: f64, rate: f64) {
        if gain_db.abs() < 1e-4 {
            return self.pass_through();
        }
        let a = 10f64.powf(gain_db / 40.0);
        let w = 2.0 * std::f64::consts::PI * safe(freq, rate) / rate;
        let c = w.cos();
        let alpha = w.sin() / 2.0 * 2f64.sqrt();
        let s = 2.0 * a.sqrt() * alpha;
        self.set(
            a * ((a + 1.0) - (a - 1.0) * c + s),
            2.0 * a * ((a - 1.0) - (a + 1.0) * c),
            a * ((a + 1.0) - (a - 1.0) * c - s),
            (a + 1.0) + (a - 1.0) * c + s,
            -2.0 * ((a - 1.0) + (a + 1.0) * c),
            (a + 1.0) + (a - 1.0) * c - s,
        );
    }

    pub fn high_shelf(&mut self, freq: f64, gain_db: f64, rate: f64) {
        if gain_db.abs() < 1e-4 {
            return self.pass_through();
        }
        let a = 10f64.powf(gain_db / 40.0);
        let w = 2.0 * std::f64::consts::PI * safe(freq, rate) / rate;
        let c = w.cos();
        let alpha = w.sin() / 2.0 * 2f64.sqrt();
        let s = 2.0 * a.sqrt() * alpha;
        self.set(
            a * ((a + 1.0) + (a - 1.0) * c + s),
            -2.0 * a * ((a - 1.0) + (a + 1.0) * c),
            a * ((a + 1.0) + (a - 1.0) * c - s),
            (a + 1.0) - (a - 1.0) * c + s,
            2.0 * ((a - 1.0) - (a + 1.0) * c),
            (a + 1.0) - (a - 1.0) * c - s,
        );
    }

    fn pass_through(&mut self) {
        self.set(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
    }

    fn set(&mut self, nb0: f64, nb1: f64, nb2: f64, na0: f64, na1: f64, na2: f64) {
        self.b0 = nb0 / na0;
        self.b1 = nb1 / na0;
        self.b2 = nb2 / na0;
        self.a1 = na1 / na0;
        self.a2 = na2 / na0;
    }
}

/// Keeps a band under Nyquist at low sample rates.
fn safe(freq: f64, rate: f64) -> f64 {
    freq.min(rate * 0.45)
}

#[cfg(test)]
mod tests {
    //! Ported from `EqualizerProcessorTest.kt`.
    use super::*;

    const RATE: u32 = 48_000;

    fn configured(curve: EqCurve) -> Equalizer {
        Equalizer::new(RATE, 1, curve)
    }

    fn sine(freq: f64, amplitude: f64, frames: usize) -> Vec<i16> {
        (0..frames)
            .map(|i| {
                ((2.0 * std::f64::consts::PI * freq * i as f64 / RATE as f64).sin() * amplitude * 32_767.0)
                    as i32 as i16
            })
            .collect()
    }

    fn run(eq: &mut Equalizer, mut input: Vec<i16>) -> Vec<i16> {
        eq.process(&mut input);
        input
    }

    fn rms(s: &[i16]) -> f64 {
        // Skip the first part, where the filter is still settling on this buffer's start.
        let tail = &s[s.len() / 4..];
        (tail.iter().map(|&v| (v as f64 / 32_768.0).powi(2)).sum::<f64>() / tail.len() as f64).sqrt()
    }

    fn curve(bands: [f32; 5], bass: i32) -> EqCurve {
        EqCurve { enabled: true, bands_db: bands, bass_boost: bass }
    }

    #[test]
    fn flat_passes_the_audio_through_bit_for_bit() {
        let mut eq = configured(curve([0.0; 5], 0));
        let input = sine(1_000.0, 0.5, 4_800);
        let out = run(&mut eq, input.clone());
        assert_eq!(input, out);

        // Also for f32: untouched bit for bit.
        let mut eq = Equalizer::new(RATE, 2, curve([0.0; 5], 0));
        let input: Vec<f32> = (0..4_800).map(|i| (i as f32 * 0.01).sin() * 0.7).collect();
        let mut out = input.clone();
        eq.process(&mut out);
        assert!(input.iter().zip(&out).all(|(a, b)| a.to_bits() == b.to_bits()));
    }

    #[test]
    fn a_boosted_band_is_louder_at_its_frequency() {
        // +6 dB at 910 Hz, settled: the 910 Hz tone comes out about 6 dB louder, minus the headroom.
        let mut eq = configured(curve([0.0, 0.0, 6.0, 0.0, 0.0], 0));
        for _ in 0..10 {
            run(&mut eq, sine(910.0, 0.1, 4_800));
        }
        let out = run(&mut eq, sine(910.0, 0.1, 4_800));
        let gain_db = 20.0 * (rms(&out) / (0.1 / 2f64.sqrt())).log10();
        // 6 dB band, 3.6 dB preamp taken off: about +2.4 dB.
        assert!((gain_db - 2.4).abs() <= 0.6, "gain {gain_db}");
    }

    #[test]
    fn a_band_leaves_far_frequencies_alone() {
        let mut eq = configured(curve([0.0, 0.0, 0.0, 0.0, -12.0], 0));
        for _ in 0..10 {
            run(&mut eq, sine(100.0, 0.1, 4_800));
        }
        let out = run(&mut eq, sine(100.0, 0.1, 4_800));
        let gain_db = 20.0 * (rms(&out) / (0.1 / 2f64.sqrt())).log10();
        assert!(gain_db.abs() <= 0.3, "gain {gain_db}");
    }

    #[test]
    fn loud_boosts_are_rounded_off_not_clipped() {
        let mut eq = configured(curve([15.0; 5], 1000));
        for _ in 0..10 {
            run(&mut eq, sine(60.0, 0.95, 4_800));
        }
        let out = run(&mut eq, sine(60.0, 0.95, 4_800));
        assert!(out.iter().all(|&v| v != i16::MAX && v != i16::MIN));
    }

    #[test]
    fn turned_off_is_flat() {
        let c = EqCurve { enabled: false, bands_db: [9.0; 5], bass_boost: 800 };
        assert!(c.is_flat());
    }

    #[test]
    fn going_back_to_flat_glides_then_bypasses() {
        let mut eq = configured(curve([6.0; 5], 0));
        for _ in 0..10 {
            run(&mut eq, sine(440.0, 0.1, 480));
        }
        eq.set_curve(EqCurve::FLAT);
        for _ in 0..20 {
            run(&mut eq, sine(440.0, 0.1, 480));
        }
        let input = sine(440.0, 0.1, 480);
        assert_eq!(run(&mut eq, input.clone()), input);
    }
}
