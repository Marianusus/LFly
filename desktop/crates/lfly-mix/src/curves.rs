//! The shape of each transition over its progress (the Android app's `TransitionCurves.kt` and
//! `MixStyle.kt`): 0 at the hand-off, 1 when the outgoing song is gone. Pure, so the curves can be
//! tested and the engine only applies them.
//!
//! A beat-matched mix runs over whole bars and starts on a bar line, so the quarter, the middle and
//! the end of the progress all fall on downbeats.
//!
//! Not ported yet: the send effects (echo, reverb, loop roll, jogwheel stop) of the phone's
//! `EffectCurve`, `LoopCurve` and `JogwheelCurve`. The styles that add one (ECHO_OUT, REVERB_FADE,
//! ROLL_DROP) play their base recipe until then: FILTER, FADE and BASS_SWAP.

use crate::filter::{DjFilterSettings, FLAT_Q, OPEN_HIGH_PASS_HZ};
use std::f64::consts::PI;

/// How the two faders move.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum VolumeCurve {
    /// Equal-power crossfade over the whole mix.
    Crossfade,
    /// The incoming song comes in at 40%, the outgoing one leaves at 60%: both full in between.
    SmoothCrossfade,
    /// Both songs at full level for the whole mix; the outgoing one leaves on the last beat.
    Overlap,
    /// The incoming song fades in over the first half, the outgoing one fades out over the second.
    FadeInFadeOut,
    /// The incoming song cuts in at the start, the outgoing one fades out over the second half.
    CutInFadeOut,
    /// The incoming song fades in over the first half, the outgoing one cuts out on the last beat.
    FadeInCutOut,
    /// A cut on the middle downbeat.
    CenterCut,
}

/// What the EQ does.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum EqCurve {
    None,
    /// The incoming song plays without bass until the middle downbeat, where the bass lines swap.
    CenterBassSwap,
    /// As [`EqCurve::CenterBassSwap`], on the last downbeat.
    EndBassSwap,
    /// Highs change places on the first quarter's downbeat, mids and bass in the middle.
    ThreeBand,
}

/// A sweeping filter on one deck: on the incoming song it opens during the first half, on the
/// outgoing one it closes during the second.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum FilterCurve {
    None,
    LowPass,
    HighPass,
}

/// One transition's ingredients.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct MixRecipe {
    pub volume: VolumeCurve,
    pub eq: EqCurve,
    pub filter_in: FilterCurve,
    pub filter_out: FilterCurve,
    /// The outgoing song builds up under a rising high-pass before the cut ([`TransitionStyle::Rise`]).
    pub riser: bool,
}

impl MixRecipe {
    const fn new(volume: VolumeCurve, eq: EqCurve, filter_in: FilterCurve, filter_out: FilterCurve) -> Self {
        MixRecipe { volume, eq, filter_in, filter_out, riser: false }
    }

    /// Both songs are heard together at full level, which needs the beats to line up.
    pub fn needs_beat_match(&self) -> bool {
        matches!(self.volume, VolumeCurve::Overlap | VolumeCurve::SmoothCrossfade | VolumeCurve::FadeInCutOut)
    }
}

/// The transition style picked in Settings; `Auto` lets Automix choose one for each transition.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub enum MixStyle {
    #[default]
    Auto,
    Fade,
    BassSwap,
    EqSwap,
    Filter,
    Rise,
}

impl MixStyle {
    /// The phone's names ("AUTO", "BASS_SWAP"…); anything else is AUTO.
    pub fn from_key(key: &str) -> MixStyle {
        match key {
            "FADE" => MixStyle::Fade,
            "BASS_SWAP" => MixStyle::BassSwap,
            "EQ_SWAP" => MixStyle::EqSwap,
            "FILTER" => MixStyle::Filter,
            "RISE" => MixStyle::Rise,
            _ => MixStyle::Auto,
        }
    }

    pub fn key(self) -> &'static str {
        match self {
            MixStyle::Auto => "AUTO",
            MixStyle::Fade => "FADE",
            MixStyle::BassSwap => "BASS_SWAP",
            MixStyle::EqSwap => "EQ_SWAP",
            MixStyle::Filter => "FILTER",
            MixStyle::Rise => "RISE",
        }
    }
}

/// How one song moves into the next.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TransitionStyle {
    /// Volumes cross; nothing else changes.
    Fade,
    /// Both songs at full level; the incoming one under a resonant low-pass without bass, the
    /// outgoing one thinning under a rising high-pass; on the last downbeat the bass lines change
    /// places and the outgoing song is gone: a build and a drop.
    BassSwap,
    /// Both songs at full level, a three-band EQ handing one over to the other.
    EqSwap,
    /// The incoming song thin under a falling high-pass while its fader goes up; halfway the bass
    /// lines change places and a rising high-pass takes the outgoing song away while it fades.
    Filter,
    /// Both songs at full level, each under a sweeping low-pass, the bass swapped in the middle.
    Wave,
    /// A hard cut on the middle downbeat.
    Slam,
    /// The outgoing song builds up with a rising high-pass, then cuts on a downbeat.
    Rise,
    /// [`TransitionStyle::Filter`] with an echo on the outgoing song (the echo not ported yet).
    EchoOut,
    /// A fade where the outgoing song dissolves into a reverb (the reverb not ported yet).
    ReverbFade,
    /// [`TransitionStyle::BassSwap`] with a loop roll into the drop (the roll not ported yet).
    RollDrop,
}

impl TransitionStyle {
    pub fn recipe(self) -> MixRecipe {
        use EqCurve as E;
        use FilterCurve as F;
        use VolumeCurve as V;
        match self {
            TransitionStyle::Fade | TransitionStyle::ReverbFade => MixRecipe::new(V::Crossfade, E::None, F::None, F::None),
            TransitionStyle::BassSwap | TransitionStyle::RollDrop => MixRecipe::new(V::Overlap, E::EndBassSwap, F::LowPass, F::HighPass),
            TransitionStyle::EqSwap => MixRecipe::new(V::Overlap, E::ThreeBand, F::None, F::None),
            TransitionStyle::Filter | TransitionStyle::EchoOut => MixRecipe::new(V::FadeInFadeOut, E::CenterBassSwap, F::HighPass, F::HighPass),
            TransitionStyle::Wave => MixRecipe::new(V::Overlap, E::CenterBassSwap, F::LowPass, F::LowPass),
            TransitionStyle::Slam => MixRecipe::new(V::CenterCut, E::None, F::None, F::None),
            TransitionStyle::Rise => MixRecipe { riser: true, ..MixRecipe::new(V::CenterCut, E::None, F::None, F::None) },
        }
    }
}

/// One deck at one instant: fader (0..1) and the mixer settings.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct DeckMix {
    pub volume: f32,
    pub filter: DjFilterSettings,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct MixFrame {
    pub outgoing: DeckMix,
    pub incoming: DeckMix,
}

/// The loudness match is eased in at the start, so the hand-off itself never jumps.
pub const LOUDNESS_RAMP: f32 = 0.15;
/// The key downbeat of a mix: its middle.
pub const SWAP_AT: f32 = 0.5;
/// One beat of a 16-bar mix: how long a swap or a cut takes.
pub const SWAP_WIDTH: f32 = 1.0 / 64.0;
/// A low band at this level is out of the mix; mids and highs need less to step back.
pub const LOW_CUT_DB: f32 = -20.0;
pub const MID_HIGH_CUT_DB: f32 = -12.0;
const JOIN: f32 = 0.2;
const SMOOTH_IN: f32 = 0.4;
const SMOOTH_OUT: f32 = 0.6;
const SWEEP_LOW_HZ: f32 = 120.0;
const SWEEP_TOP_HZ: f32 = 20_000.0;
const SWEEP_HIGH_PASS_END_HZ: f32 = 9_000.0;
const SWEEP_PEAK_Q: f32 = 2.5;
const RISE_HIGH_PASS_END_HZ: f32 = 1_000.0;
const RISE_LIFT_DB: f64 = 1.5;

fn flat_q() -> f32 {
    FLAT_Q as f32
}

fn open_hp() -> f32 {
    OPEN_HIGH_PASS_HZ as f32
}

/// Equal-power curves (CrossfadeMath): the two songs keep a steady loudness through the overlap.
pub fn fade_out_gain(progress: f32) -> f32 {
    (progress.clamp(0.0, 1.0) as f64 * PI / 2.0).cos() as f32
}

pub fn fade_in_gain(progress: f32) -> f32 {
    (progress.clamp(0.0, 1.0) as f64 * PI / 2.0).sin() as f32
}

/// The two decks at `progress`. `outgoing_gain` brings the outgoing song to the incoming one's
/// level; `swap_at` is the key downbeat and `swap_width` one beat, as shares of the mix.
pub fn frame(recipe: &MixRecipe, progress: f32, outgoing_gain: f32, swap_at: f32, swap_width: f32) -> MixFrame {
    let p = progress.clamp(0.0, 1.0);
    let matched = 1.0 + (outgoing_gain - 1.0) * (p / LOUDNESS_RAMP).min(1.0);
    let width = swap_width.clamp(0.005, 0.25);
    if recipe.riser {
        return rise(p, matched, swap_at.clamp(0.05, 0.9), width);
    }
    let middle = swap_at.clamp(0.2, 0.8);
    let (out_volume, in_volume) = volumes(recipe.volume, p, middle, width);
    let eq = eq(recipe.eq, p, middle, width);
    let out_f = filter_out(recipe.filter_out, p, middle);
    let in_f = filter_in(recipe.filter_in, p, middle);
    MixFrame {
        outgoing: DeckMix {
            volume: out_volume,
            filter: DjFilterSettings {
                gain: matched,
                bass_db: eq.out_low,
                mid_db: eq.out_mid,
                high_db: eq.out_high,
                high_pass_hz: out_f.high_pass_hz,
                low_pass_hz: out_f.low_pass_hz,
                resonance: out_f.resonance,
            },
        },
        incoming: DeckMix {
            volume: in_volume,
            filter: DjFilterSettings {
                gain: 1.0,
                bass_db: eq.in_low,
                mid_db: eq.in_mid,
                high_db: eq.in_high,
                high_pass_hz: in_f.high_pass_hz,
                low_pass_hz: in_f.low_pass_hz,
                resonance: in_f.resonance,
            },
        },
    }
}

/// The style of one transition from the listener's `choice`. AUTO keeps calm songs on a fade; on
/// the beat it builds to a drop when the incoming rise lands as the mix ends (every other pair with
/// a loop roll), and blends with the EQ otherwise; off the beat it filters. The two full-level
/// styles need the beats to line up, so off the beat they filter too. RISE is never picked by AUTO.
pub fn resolve(choice: MixStyle, beat_matched: bool, calm: bool, lands_rise: bool, seed: i32) -> TransitionStyle {
    match choice {
        MixStyle::Fade => TransitionStyle::Fade,
        MixStyle::BassSwap => if beat_matched { TransitionStyle::BassSwap } else { TransitionStyle::Filter },
        MixStyle::EqSwap => if beat_matched { TransitionStyle::EqSwap } else { TransitionStyle::Filter },
        MixStyle::Filter => TransitionStyle::Filter,
        MixStyle::Rise => TransitionStyle::Rise,
        MixStyle::Auto => {
            if calm {
                TransitionStyle::ReverbFade
            } else if beat_matched && lands_rise {
                if seed & 1 == 0 { TransitionStyle::RollDrop } else { TransitionStyle::BassSwap }
            } else if beat_matched {
                TransitionStyle::EqSwap
            } else {
                TransitionStyle::EchoOut
            }
        }
    }
}

/// A number that stays the same for one pair of songs, so AUTO always mixes them the same way:
/// Java's `"$from>$to".hashCode() ushr 1`.
pub fn pair_seed(from_id: &str, to_id: &str) -> i32 {
    let mut h: i32 = 0;
    for unit in format!("{from_id}>{to_id}").encode_utf16() {
        h = h.wrapping_mul(31).wrapping_add(unit as i32);
    }
    ((h as u32) >> 1) as i32
}

fn volumes(curve: VolumeCurve, p: f32, middle: f32, width: f32) -> (f32, f32) {
    let end_cut = fade_out_gain(((p - (1.0 - width)) / width).clamp(0.0, 1.0));
    let second_half_out = if p <= middle { 1.0 } else { fade_out_gain((p - middle) / (1.0 - middle)) };
    match curve {
        VolumeCurve::Crossfade => (fade_out_gain(p), fade_in_gain(p)),
        VolumeCurve::SmoothCrossfade => (fade_out_gain((p - SMOOTH_OUT) / width), fade_in_gain((p - SMOOTH_IN + width) / width)),
        VolumeCurve::Overlap => (end_cut, fade_in_gain(p / JOIN)),
        VolumeCurve::FadeInFadeOut => (second_half_out, fade_in_gain(p / middle)),
        VolumeCurve::CutInFadeOut => (second_half_out, fade_in_gain(p / width)),
        VolumeCurve::FadeInCutOut => (end_cut, fade_in_gain(p / middle)),
        VolumeCurve::CenterCut => {
            let t = step(p, middle, width);
            (fade_out_gain(t), fade_in_gain(t))
        }
    }
}

#[derive(Default)]
struct EqFrame {
    out_low: f32,
    out_mid: f32,
    out_high: f32,
    in_low: f32,
    in_mid: f32,
    in_high: f32,
}

fn eq(curve: EqCurve, p: f32, middle: f32, width: f32) -> EqFrame {
    match curve {
        EqCurve::None => EqFrame::default(),
        EqCurve::CenterBassSwap | EqCurve::EndBassSwap => {
            let at = if curve == EqCurve::EndBassSwap { 1.0 - width } else { middle };
            let t = step(p, at, width);
            EqFrame { out_low: swap_out_db(t, LOW_CUT_DB), in_low: swap_in_db(t, LOW_CUT_DB), ..Default::default() }
        }
        EqCurve::ThreeBand => {
            let highs = step(p, middle / 2.0, width);
            let rest = step(p, middle, width);
            EqFrame {
                out_low: swap_out_db(rest, LOW_CUT_DB),
                out_mid: swap_out_db(rest, MID_HIGH_CUT_DB),
                out_high: swap_out_db(highs, MID_HIGH_CUT_DB),
                in_low: swap_in_db(rest, LOW_CUT_DB),
                in_mid: swap_in_db(rest, MID_HIGH_CUT_DB),
                in_high: swap_in_db(highs, MID_HIGH_CUT_DB),
            }
        }
    }
}

struct FilterFrame {
    high_pass_hz: f32,
    low_pass_hz: f32,
    resonance: f32,
}

impl FilterFrame {
    fn open() -> Self {
        FilterFrame { high_pass_hz: 0.0, low_pass_hz: 0.0, resonance: flat_q() }
    }
}

/// The incoming song's filter opens during the first half and is out of the way from the middle on.
fn filter_in(curve: FilterCurve, p: f32, middle: f32) -> FilterFrame {
    let t = (p / middle).min(1.0);
    if curve == FilterCurve::None || t >= 1.0 {
        return FilterFrame::open();
    }
    match curve {
        FilterCurve::LowPass => FilterFrame { low_pass_hz: log_sweep(SWEEP_LOW_HZ, SWEEP_TOP_HZ, t), high_pass_hz: 0.0, resonance: sweep_q(t) },
        _ => FilterFrame { high_pass_hz: log_sweep(SWEEP_HIGH_PASS_END_HZ, open_hp(), t), low_pass_hz: 0.0, resonance: sweep_q(t) },
    }
}

/// The outgoing song's filter stays open until the middle, then closes on it.
fn filter_out(curve: FilterCurve, p: f32, middle: f32) -> FilterFrame {
    if curve == FilterCurve::None || p <= middle {
        return FilterFrame::open();
    }
    let t = ((p - middle) / (1.0 - middle)).clamp(0.0, 1.0);
    match curve {
        FilterCurve::LowPass => FilterFrame { low_pass_hz: log_sweep(SWEEP_TOP_HZ, SWEEP_LOW_HZ, t), high_pass_hz: 0.0, resonance: sweep_q(t) },
        _ => FilterFrame { high_pass_hz: log_sweep(open_hp(), SWEEP_HIGH_PASS_END_HZ, t), low_pass_hz: 0.0, resonance: sweep_q(t) },
    }
}

/// A build and a cut: until the downbeat at `swap_at` only the outgoing song plays, its high-pass
/// rising faster towards the end, with a small lift; on the downbeat the songs trade places.
fn rise(p: f32, matched: f32, swap_at: f32, swap_width: f32) -> MixFrame {
    let swap_end = (swap_at + swap_width).min(0.95);
    let build = (p / swap_at).min(1.0);
    let high_pass = log_sweep(open_hp(), RISE_HIGH_PASS_END_HZ, build * build);
    let lift = 1.0 + (crate::filter::db_to_gain(RISE_LIFT_DB) as f32 - 1.0) * build;
    let t = ((p - swap_at) / (swap_end - swap_at)).clamp(0.0, 1.0);
    MixFrame {
        outgoing: DeckMix { volume: fade_out_gain(t), filter: DjFilterSettings { gain: matched * lift, high_pass_hz: high_pass, ..DjFilterSettings::NEUTRAL } },
        incoming: DeckMix { volume: fade_in_gain(t), filter: DjFilterSettings::NEUTRAL },
    }
}

/// 0..1 across a change that starts at `at` and lasts `width`, always over by the end of the mix.
fn step(p: f32, at: f32, width: f32) -> f32 {
    let end = (at + width).min(1.0);
    if end <= at {
        return if p >= at { 1.0 } else { 0.0 };
    }
    ((p - at) / (end - at)).clamp(0.0, 1.0)
}

/// Resonance over a sweep: flat at the start, building to its peak, flat again at the end.
fn sweep_q(t: f32) -> f32 {
    let flat = flat_q();
    if t < 0.1 {
        flat
    } else if t < 0.9 {
        flat + (SWEEP_PEAK_Q - flat) * (t - 0.1) / 0.8
    } else {
        SWEEP_PEAK_Q + (flat - SWEEP_PEAK_Q) * (t - 0.9) / 0.1
    }
}

/// A band handed over on an equal-power curve as `t` goes 0..1: never doubled, never missing.
fn swap_out_db(t: f32, floor_db: f32) -> f32 {
    band_db((t as f64 * PI / 2.0).cos(), floor_db)
}

fn swap_in_db(t: f32, floor_db: f32) -> f32 {
    band_db((t as f64 * PI / 2.0).sin(), floor_db)
}

/// From `from` to `to` at `t` (0..1), evenly in log frequency.
fn log_sweep(from: f32, to: f32, t: f32) -> f32 {
    from * (to / from).powf(t.clamp(0.0, 1.0))
}

fn band_db(amplitude: f64, floor_db: f32) -> f32 {
    if amplitude >= 0.9999 {
        0.0
    } else {
        ((20.0 * amplitude.max(1e-6).log10()) as f32).max(floor_db)
    }
}

#[cfg(test)]
mod tests {
    //! The phone's TransitionCurvesTest, case for case.
    use super::*;

    const ALL: [TransitionStyle; 10] = [
        TransitionStyle::Fade,
        TransitionStyle::BassSwap,
        TransitionStyle::EqSwap,
        TransitionStyle::Filter,
        TransitionStyle::Wave,
        TransitionStyle::Slam,
        TransitionStyle::Rise,
        TransitionStyle::EchoOut,
        TransitionStyle::ReverbFade,
        TransitionStyle::RollDrop,
    ];
    const TOP_HZ: f32 = 20_000.0;

    fn f(style: TransitionStyle, p: f32) -> MixFrame {
        frame(&style.recipe(), p, 1.0, SWAP_AT, SWAP_WIDTH)
    }

    fn near(a: f32, b: f32, tol: f32, what: &str) {
        assert!((a - b).abs() <= tol, "{what}: expected {a}, got {b}");
    }

    #[test]
    fn every_style_starts_on_the_outgoing_song_and_ends_on_the_incoming_one() {
        for s in ALL {
            let start = frame(&s.recipe(), 0.0, 0.5, SWAP_AT, SWAP_WIDTH);
            near(1.0, start.outgoing.volume, 1e-4, &format!("{s:?} start volume"));
            near(1.0, start.outgoing.filter.gain, 1e-4, &format!("{s:?} start gain"));
            near(0.0, start.outgoing.filter.bass_db, 1e-4, &format!("{s:?} start bass"));
            assert!(start.outgoing.filter.high_pass_hz <= open_hp());
            assert_eq!(start.outgoing.filter.low_pass_hz, 0.0);
            near(0.0, start.incoming.volume, 1e-4, &format!("{s:?} start incoming"));
            let end = frame(&s.recipe(), 1.0, 0.5, SWAP_AT, SWAP_WIDTH);
            near(0.0, end.outgoing.volume, 1e-4, &format!("{s:?} end outgoing"));
            near(1.0, end.incoming.volume, 1e-4, &format!("{s:?} end incoming"));
            assert_eq!(end.incoming.filter, DjFilterSettings::NEUTRAL, "{s:?} end incoming filter");
        }
    }

    #[test]
    fn the_loudness_match_reaches_the_outgoing_deck_in_every_style() {
        for s in ALL {
            let plain = f(s, 0.3).outgoing.filter.gain;
            let matched = frame(&s.recipe(), 0.3, 0.5, SWAP_AT, SWAP_WIDTH).outgoing.filter.gain;
            near(plain * 0.5, matched, 1e-4, &format!("{s:?}"));
        }
    }

    fn low_pass_of(d: &DeckMix) -> f32 {
        if d.filter.low_pass_hz <= 0.0 { TOP_HZ } else { d.filter.low_pass_hz }
    }

    fn log_step(a: f32, b: f32) -> f64 {
        ((b.max(open_hp()) as f64).ln() - (a.max(open_hp()) as f64).ln()).abs()
    }

    #[test]
    fn curves_are_continuous_apart_from_the_cuts() {
        let step = 0.002f32;
        for s in ALL {
            let mut previous = f(s, 0.0);
            let mut p = step;
            while p <= 1.0 {
                let now = f(s, p);
                let in_cut = match s {
                    TransitionStyle::Rise | TransitionStyle::Slam => p > SWAP_AT - step && p < SWAP_AT + SWAP_WIDTH + step,
                    TransitionStyle::BassSwap | TransitionStyle::RollDrop | TransitionStyle::EqSwap | TransitionStyle::Wave => p > 1.0 - SWAP_WIDTH - step,
                    _ => false,
                };
                let max_step = if in_cut { 0.25 } else { 0.02 };
                assert!((now.outgoing.volume - previous.outgoing.volume).abs() < max_step, "{s:?} outgoing at {p}");
                assert!((now.incoming.volume - previous.incoming.volume).abs() < max_step, "{s:?} incoming at {p}");
                assert!((now.outgoing.filter.gain - previous.outgoing.filter.gain).abs() < 0.01, "{s:?} gain at {p}");
                assert!(log_step(previous.outgoing.filter.high_pass_hz, now.outgoing.filter.high_pass_hz) < 0.05, "{s:?} hp at {p}");
                assert!(log_step(low_pass_of(&previous.incoming), low_pass_of(&now.incoming)) < 0.05, "{s:?} lp at {p}");
                previous = now;
                p += step;
            }
        }
    }

    #[test]
    fn filter_sweeps_only_ever_close_on_the_outgoing_song_and_open_on_the_incoming_one() {
        let (mut last_out, mut last_in) = (0f32, f32::MAX);
        for i in 0..=100 {
            let fr = f(TransitionStyle::Filter, i as f32 / 100.0);
            assert!(fr.outgoing.filter.high_pass_hz >= last_out);
            assert!(fr.incoming.filter.high_pass_hz <= last_in);
            last_out = fr.outgoing.filter.high_pass_hz;
            last_in = fr.incoming.filter.high_pass_hz;
        }
        assert!((8_000.0..=10_000.0).contains(&last_out));
        assert_eq!(last_in, 0.0);
        assert!(f(TransitionStyle::Filter, 0.01).incoming.filter.high_pass_hz > 5_000.0);
        assert_eq!(f(TransitionStyle::Filter, 0.55).incoming.filter.high_pass_hz, 0.0);
        assert_eq!(f(TransitionStyle::Filter, 0.45).outgoing.volume, 1.0);
        assert!(f(TransitionStyle::Filter, 0.75).outgoing.volume < 1.0);
    }

    fn perceived_db(fr: &MixFrame) -> f32 {
        let band = |d: &DeckMix| {
            let low = d.filter.high_pass_hz.clamp(open_hp(), TOP_HZ) as f64;
            let high = low_pass_of(d).clamp(open_hp(), TOP_HZ) as f64;
            ((high / low).ln() / (TOP_HZ as f64 / open_hp() as f64).ln()).max(0.0)
        };
        let power = |d: &DeckMix| {
            let a = (d.volume * d.filter.gain) as f64;
            a * a * band(d)
        };
        (10.0 * (power(&fr.outgoing) + power(&fr.incoming)).log10()) as f32
    }

    #[test]
    fn the_sum_neither_dips_nor_peaks() {
        for (s, top) in [(TransitionStyle::Fade, 3.0f32), (TransitionStyle::Filter, 3.1)] {
            for swap in [0.4f32, 0.5, 0.6] {
                for i in 0..=20 {
                    let p = i as f32 / 20.0;
                    let db = perceived_db(&frame(&s.recipe(), p, 1.0, swap, SWAP_WIDTH));
                    assert!((-3.0..=top).contains(&db), "{s:?} at {p}: {db} dB");
                }
            }
        }
    }

    #[test]
    fn rise_keeps_the_incoming_song_out_until_the_downbeat() {
        let (swap, width) = (0.6f32, 0.05f32);
        let r = TransitionStyle::Rise.recipe();
        for i in 0..=59 {
            let fr = frame(&r, i as f32 / 100.0, 1.0, swap, width);
            assert_eq!(fr.incoming.volume, 0.0);
            near(1.0, fr.outgoing.volume, 1e-6, "outgoing");
        }
        let build = frame(&r, swap, 1.0, swap, width).outgoing.filter;
        assert!((800.0..=1_200.0).contains(&build.high_pass_hz));
        assert!(build.gain > 1.0 && build.gain <= crate::filter::db_to_gain(1.6) as f32);
        for i in 66..=100 {
            let fr = frame(&r, i as f32 / 100.0, 1.0, swap, width);
            near(1.0, fr.incoming.volume, 1e-6, "incoming");
            assert_eq!(fr.incoming.filter, DjFilterSettings::NEUTRAL);
            near(0.0, fr.outgoing.volume, 1e-6, "outgoing");
        }
    }

    #[test]
    fn out_of_range_swap_points_are_clamped() {
        for s in ALL {
            for (swap, width) in [(-1.0f32, 0.0f32), (0.0, -1.0), (2.0, 2.0), (0.99, 0.5)] {
                for i in 0..=20 {
                    let fr = frame(&s.recipe(), i as f32 / 20.0, 1.0, swap, width);
                    for d in [fr.outgoing, fr.incoming] {
                        assert!(d.volume.is_finite() && (-1e-4..=1.0001).contains(&d.volume));
                        assert!(d.filter.gain.is_finite() && d.filter.high_pass_hz.is_finite());
                    }
                }
                let end = frame(&s.recipe(), 1.0, 1.0, swap, width);
                near(1.0, end.incoming.volume, 1e-4, "end in");
                near(0.0, end.outgoing.volume, 1e-4, "end out");
            }
        }
    }

    #[test]
    fn resolve_follows_the_listener_and_auto_reads_the_songs() {
        for beat in [false, true] {
            for calm in [false, true] {
                for lands in [false, true] {
                    assert_eq!(resolve(MixStyle::Fade, beat, calm, lands, 0), TransitionStyle::Fade);
                    assert_eq!(resolve(MixStyle::Filter, beat, calm, lands, 0), TransitionStyle::Filter);
                    assert_eq!(resolve(MixStyle::Rise, beat, calm, lands, 0), TransitionStyle::Rise);
                    let full = if beat { TransitionStyle::BassSwap } else { TransitionStyle::Filter };
                    assert_eq!(resolve(MixStyle::BassSwap, beat, calm, lands, 0), full);
                    for seed in 0..=1 {
                        let auto = if calm {
                            TransitionStyle::ReverbFade
                        } else if beat && lands {
                            if seed == 0 { TransitionStyle::RollDrop } else { TransitionStyle::BassSwap }
                        } else if beat {
                            TransitionStyle::EqSwap
                        } else {
                            TransitionStyle::EchoOut
                        };
                        assert_eq!(resolve(MixStyle::Auto, beat, calm, lands, seed), auto);
                    }
                }
            }
        }
    }

    #[test]
    fn the_pair_seed_is_javas_string_hash() {
        // "a>b".hashCode() = 97 * 961 + 62 * 31 + 98 = 95_237, ushr 1 = 47_618.
        assert_eq!(pair_seed("a", "b"), 47_618);
        assert!(pair_seed("yt_aaaaaaaaaaa", "yt_bbbbbbbbbbb") >= 0);
    }
}
