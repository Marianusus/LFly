//! Loudness of a song edge bar by bar, on the song's own timeline (the Android app's
//! `EnergyProfile.kt`). It lets the planner find where an outro winds down and where an intro
//! opens up, and tells a calm edge (a ballad, an ambient ending) from one with a driving beat.

use crate::beat_grid::BeatGrid;
use crate::filter::{self, BiquadState, FLAT_Q};
use crate::loudness::{self, KWeighting};
use crate::util::jround;

/// Fewer bars than this say nothing about sections.
const MIN_BARS: usize = 4;
/// Silence reads as this, so one empty bar cannot stretch the scale.
const FLOOR_DB: f64 = -60.0;
/// A section change must be at least this big: about what a listener hears as "it drops".
const CHANGE_DB: f64 = 5.0;
/// After a change, the music may drift back this close before the change stops counting.
const HOLD_DB: f64 = 3.0;
const MIN_MAIN_BARS: usize = 4;
const MIN_INTRO_BARS: usize = 2;
const MIN_OUTRO_BARS: usize = 2;
/// Within this of the window's loud level, the music is already at full energy.
const NEAR_MAIN_DB: f64 = 4.0;
/// Downbeats that miss the window edge by less than this share of a bar still make a bar.
const EDGE_BARS: f64 = 0.1;
/// Envelope resolution for the beat measure: fine enough to see a drum hit.
const FRAME_SECONDS: f64 = 0.01;
/// Where the kick lives and where hats and snares live.
const LOW_BAND_HZ: f64 = 150.0;
const HIGH_BAND_HZ: f64 = 4_000.0;
/// A band this far below the whole mix is inaudible behind it and says nothing about the beat.
const QUIET_BAND_DB: f64 = -30.0;
/// Envelope contrast within a beat below which nothing drives the music.
const NO_BEAT_DB: f64 = 6.0;
/// A moderate beat still counts as calm when even the loudest bar is softer than [`SOFT_LUFS`].
const SOFT_BEAT_DB: f64 = 10.0;
/// On the mono analysis audio, which reads a few dB under a stereo meter.
const SOFT_LUFS: f64 = -14.0;

#[derive(Clone, Debug, PartialEq)]
pub struct EnergyProfile {
    /// Start of each bar, in seconds on the song's timeline.
    pub bar_starts: Vec<f64>,
    /// Short-term loudness of each bar in dB, relative to the loudest bar (so 0 or below).
    pub bar_energy_db: Vec<f64>,
    /// The stretch is soft or has no driving beat throughout.
    pub is_calm: bool,
    /// Where the music winds down for good (the outro), on a bar line.
    pub outro_start: Option<f64>,
    /// Where the music first opens up after the intro (drop or first verse), on a bar line.
    pub first_rise: Option<f64>,
}

impl EnergyProfile {
    /// [samples] are mono at [sample_rate] and start at [start_seconds] in the song; [grid] was
    /// tracked on the same audio. `None` when there is too little to measure.
    ///
    /// Calm is judged from how hard the music hits on the beat: within every beat, the contrast
    /// between the loudest 10 ms and the typical level, below 150 Hz (the kick) and above 4 kHz
    /// (hats and snare). The median beat of the louder band decides: under 6 dB the stretch is calm,
    /// under 10 dB it is calm when it is also soft.
    pub fn compute(samples: &[f32], sample_rate: u32, start_seconds: f64, grid: &BeatGrid) -> Option<EnergyProfile> {
        if sample_rate == 0 || samples.is_empty() || grid.beats().is_empty() {
            return None;
        }
        let rate = sample_rate as f64;
        let window_end = start_seconds + samples.len() as f64 / rate;
        let bars = bar_lines(grid, start_seconds, window_end);
        if bars.len() < MIN_BARS + 1 {
            return None;
        }
        let env = Envelope::measure(samples, rate);
        let frame_at = |time: f64| -> usize { (jround((time - start_seconds) * rate / env.frame as f64).max(0.0) as usize).min(env.count) };

        let count = bars.len() - 1;
        let mean_squares: Vec<f64> = (0..count).map(|i| env.mean_square(&env.weighted, frame_at(bars[i]), frame_at(bars[i + 1]))).collect();
        let loudest = mean_squares.iter().copied().fold(f64::MIN, f64::max);
        if loudest <= 1e-12 {
            return None;
        }
        let energy_db: Vec<f64> = mean_squares.iter().map(|m| (10.0 * (m.max(1e-12) / loudest).log10()).max(FLOOR_DB)).collect();

        let mut beats: Vec<(usize, usize)> = Vec::with_capacity(count * 4);
        for bar in 0..count {
            let quarter = (bars[bar + 1] - bars[bar]) / 4.0;
            for beat in 0..4 {
                beats.push((frame_at(bars[bar] + beat as f64 * quarter), frame_at(bars[bar] + (beat + 1) as f64 * quarter)));
            }
        }
        let pulse = env.pulse_db(&env.low, &beats).max(env.pulse_db(&env.high, &beats));
        let soft = loudness::to_lufs(loudest) < SOFT_LUFS;
        let calm = pulse < NO_BEAT_DB || (pulse < SOFT_BEAT_DB && soft);
        let starts = bars[..count].to_vec();
        Some(EnergyProfile {
            outro_start: outro_bar(&energy_db).map(|i| starts[i]),
            first_rise: rise_bar(&energy_db).map(|i| starts[i]),
            bar_starts: starts,
            bar_energy_db: energy_db,
            is_calm: calm,
        })
    }
}

/// Bar lines covering the window, the last one closing the final bar: tracked downbeats where they
/// exist (groups of four beats without them), gaps and the edges filled at the grid's tempo.
pub fn bar_lines(grid: &BeatGrid, start: f64, end: f64) -> Vec<f64> {
    let bar = 4.0 * grid.beat_seconds();
    let anchors: Vec<f64> = if grid.beats_per_bar() > 1 {
        grid.downbeats().to_vec()
    } else {
        grid.beats().iter().step_by(4).copied().collect()
    };
    if anchors.is_empty() || bar <= 0.0 {
        return Vec::new();
    }
    let edge = EDGE_BARS * bar;
    let mut lines: Vec<f64> = Vec::new();
    let mut back = anchors[0] - bar;
    while back >= start - edge {
        lines.push(back);
        back -= bar;
    }
    lines.reverse();
    lines.push(anchors[0]);
    for &a in &anchors[1..] {
        let last = *lines.last().expect("pushed");
        let gap = a - last;
        // A downbeat half a bar after the last one is a stray; one several bars on had misses between.
        if gap < bar / 2.0 {
            continue;
        }
        let steps = (jround(gap / bar) as i64).max(1);
        for step in 1..steps {
            lines.push(last + gap * step as f64 / steps as f64);
        }
        lines.push(a);
    }
    let mut next = lines.last().expect("pushed") + bar;
    while next <= end + edge {
        lines.push(next);
        next += bar;
    }
    lines.into_iter().filter(|t| *t >= start - edge && *t <= end + edge).collect()
}

/// The first bar [`CHANGE_DB`] under the main section before it (its upper quartile) with
/// everything after it staying at least [`HOLD_DB`] under.
pub fn outro_bar(energy_db: &[f64]) -> Option<usize> {
    if energy_db.len() < MIN_OUTRO_BARS {
        return None;
    }
    for index in MIN_MAIN_BARS..=energy_db.len() - MIN_OUTRO_BARS {
        let main = percentile(energy_db, 0, index, 0.75);
        if energy_db[index] > main - CHANGE_DB {
            continue;
        }
        if energy_db[index..].iter().all(|e| *e <= main - HOLD_DB) {
            return Some(index);
        }
    }
    None
}

/// The first bar [`CHANGE_DB`] over the intro before it (its median) whose next bar stays at least
/// [`HOLD_DB`] over too. A song whose opening bars are already near its loud level has no rise.
pub fn rise_bar(energy_db: &[f64]) -> Option<usize> {
    if energy_db.len() < MIN_INTRO_BARS + 2 {
        return None;
    }
    let main = percentile(energy_db, 0, energy_db.len(), 0.9);
    if energy_db[..MIN_INTRO_BARS].iter().any(|e| *e >= main - NEAR_MAIN_DB) {
        return None;
    }
    for index in MIN_INTRO_BARS..energy_db.len() - 1 {
        let intro = percentile(energy_db, 0, index, 0.5);
        if energy_db[index] >= intro + CHANGE_DB && energy_db[index + 1] >= intro + HOLD_DB {
            return Some(index);
        }
    }
    None
}

fn percentile(values: &[f64], from: usize, to: usize, share: f64) -> f64 {
    let mut sorted = values[from..to].to_vec();
    sorted.sort_by(f64::total_cmp);
    sorted[jround((sorted.len() - 1) as f64 * share) as usize]
}

/// Energy per 10 ms frame, measured in one pass: K-weighted for loudness, plus the kick and
/// hi-hat bands for the beat.
struct Envelope {
    frame: usize,
    count: usize,
    lengths: Vec<usize>,
    weighted: Vec<f64>,
    low: Vec<f64>,
    high: Vec<f64>,
}

impl Envelope {
    fn measure(samples: &[f32], rate: f64) -> Envelope {
        let frame = (jround(rate * FRAME_SECONDS) as usize).max(1);
        let count = samples.len().div_ceil(frame);
        let lengths: Vec<usize> = (0..count).map(|i| frame.min(samples.len() - i * frame)).collect();
        let (mut weighted, mut low, mut high) = (vec![0.0; count], vec![0.0; count], vec![0.0; count]);
        let mut weighting = KWeighting::new(rate);
        // Two biquads each: 24 dB per octave keeps the vocals out of the kick band and vice versa.
        let lp = filter::low_pass(rate, LOW_BAND_HZ, FLAT_Q);
        let hp = filter::high_pass(rate, HIGH_BAND_HZ, FLAT_Q);
        let (mut l1, mut l2, mut h1, mut h2) = (BiquadState::default(), BiquadState::default(), BiquadState::default(), BiquadState::default());
        for (i, s) in samples.iter().enumerate() {
            let x = *s as f64;
            let slot = i / frame;
            let w = weighting.run(x);
            weighted[slot] += w * w;
            let l = l2.run(&lp, l1.run(&lp, x));
            low[slot] += l * l;
            let h = h2.run(&hp, h1.run(&hp, x));
            high[slot] += h * h;
        }
        Envelope { frame, count, lengths, weighted, low, high }
    }

    fn mean_square(&self, band: &[f64], from: usize, to: usize) -> f64 {
        let to = to.min(self.count);
        let from = from.min(to);
        let sum: f64 = band[from..to].iter().sum();
        let length: usize = self.lengths[from..to].iter().sum();
        if length == 0 { 0.0 } else { sum / length as f64 }
    }

    /// Median, over the beats, of how far each beat's loudest frame stands over its typical frame.
    fn pulse_db(&self, band: &[f64], beats: &[(usize, usize)]) -> f64 {
        if self.mean_square(band, 0, self.count) < self.mean_square(&self.weighted, 0, self.count) * 10f64.powf(QUIET_BAND_DB / 10.0) {
            return 0.0;
        }
        let mut levels: Vec<f64> = (0..self.count).map(|i| 10.0 * (band[i] / self.lengths[i].max(1) as f64 + 1e-20).log10()).collect();
        let floor = levels.iter().copied().fold(f64::MIN, f64::max) + FLOOR_DB;
        for l in &mut levels {
            *l = l.max(floor);
        }
        // Kotlin's `from until to` ranges: a beat of at least three frames ([first, last] with
        // last - first >= 2).
        let mut contrasts: Vec<f64> = beats
            .iter()
            .filter(|(from, to)| to.saturating_sub(1) >= from + 2 && *to >= 1)
            .map(|&(from, to)| {
                let mut in_beat = levels[from..to.min(self.count)].to_vec();
                in_beat.sort_by(f64::total_cmp);
                in_beat[in_beat.len() - 1] - in_beat[in_beat.len() / 2]
            })
            .collect();
        if contrasts.is_empty() {
            return 0.0;
        }
        contrasts.sort_by(f64::total_cmp);
        contrasts[contrasts.len() / 2]
    }
}

#[cfg(test)]
mod tests {
    //! The phone's EnergyProfileTest, case for case.
    use super::*;
    use crate::mel::SAMPLE_RATE as RATE;
    use crate::util::testing::Noise;

    const START: f64 = 100.0;
    const FIRST_BEAT: f64 = 100.25;
    const BAR: f64 = 2.0;

    fn grid(first: f64, count: usize, with_downbeats: bool) -> BeatGrid {
        let beats: Vec<f64> = (0..count).map(|i| first + 0.5 * i as f64).collect();
        let downbeats = if with_downbeats { beats.iter().step_by(4).copied().collect() } else { Vec::new() };
        BeatGrid::new(beats, downbeats, 120.0, 0.9)
    }

    fn grid_for(seconds: f64) -> BeatGrid {
        grid(FIRST_BEAT, ((seconds - (FIRST_BEAT - START)) / 0.5) as usize, true)
    }

    /// Steady noise with a decaying noise click on every beat, scaled by [gain_db] over the song.
    fn drums(seconds: f64, noise: f64, click: f64, gain_db: impl Fn(f64) -> f64) -> Vec<f32> {
        let mut random = Noise(7);
        (0..(seconds * RATE as f64) as usize)
            .map(|i| {
                let time = START + i as f64 / RATE as f64;
                let since = (time - FIRST_BEAT).rem_euclid(0.5);
                let burst = if time >= FIRST_BEAT { click * (-since / 0.01).exp() } else { 0.0 };
                let value = (noise + burst) * (random.next() * 2.0 - 1.0);
                (value * 10f64.powf(gain_db(time) / 20.0)) as f32
            })
            .collect()
    }

    fn close(expected: f64, actual: f64, tolerance: f64) {
        assert!((expected - actual).abs() <= tolerance, "expected {expected}, got {actual}");
    }

    #[test]
    fn a_10_db_drop_at_bar_8_starts_the_outro_there() {
        let drop = FIRST_BEAT + 8.0 * BAR;
        let p = EnergyProfile::compute(&drums(48.0, 0.1, 0.6, |t| if t >= drop { -10.0 } else { 0.0 }), RATE, START, &grid_for(48.0)).unwrap();
        close(drop, p.outro_start.unwrap(), 0.05);
        assert!(p.first_rise.is_none());
        assert!(!p.is_calm);
        assert!(p.bar_energy_db.iter().all(|e| *e <= 0.0));
        close(-10.0, *p.bar_energy_db.last().unwrap(), 0.5);
    }

    #[test]
    fn a_quiet_intro_rises_at_bar_4() {
        let rise = FIRST_BEAT + 4.0 * BAR;
        let p = EnergyProfile::compute(&drums(48.0, 0.1, 0.6, |t| if t < rise { -12.0 } else { 0.0 }), RATE, START, &grid_for(48.0)).unwrap();
        close(rise, p.first_rise.unwrap(), 0.05);
        assert!(p.outro_start.is_none());
    }

    #[test]
    fn a_constant_level_has_neither_outro_nor_rise() {
        let p = EnergyProfile::compute(&drums(48.0, 0.1, 0.6, |_| 0.0), RATE, START, &grid_for(48.0)).unwrap();
        assert!(p.outro_start.is_none() && p.first_rise.is_none());
        assert!(p.bar_energy_db.iter().all(|e| e.abs() < 1.0));
    }

    #[test]
    fn a_breakdown_that_comes_back_is_not_an_outro_and_a_full_energy_start_is_not_a_rise() {
        let energy: Vec<f64> = (0..16).map(|i| if (6..=9).contains(&i) { -10.0 } else { 0.0 }).collect();
        assert_eq!(outro_bar(&energy), None);
        assert_eq!(rise_bar(&energy), None);
        assert_eq!(rise_bar(&[-20.0, -18.0, -17.0, -16.0, 0.0, 0.0, -1.0, 0.0]), Some(4));
    }

    #[test]
    fn a_soft_sine_pad_is_calm_a_loud_click_track_is_not() {
        let pad: Vec<f32> = (0..32 * RATE as usize).map(|i| (0.1 * (2.0 * std::f64::consts::PI * 220.0 * i as f64 / RATE as f64).sin()) as f32).collect();
        assert!(EnergyProfile::compute(&pad, RATE, START, &grid_for(32.0)).unwrap().is_calm);
        let clicks = drums(32.0, 0.0, 0.9, |_| 0.0);
        assert!(!EnergyProfile::compute(&clicks, RATE, START, &grid_for(32.0)).unwrap().is_calm);
    }

    #[test]
    fn too_little_audio_gives_no_profile() {
        assert!(EnergyProfile::compute(&drums(3.0, 0.1, 0.6, |_| 0.0), RATE, START, &grid(FIRST_BEAT, 5, true)).is_none());
        assert!(EnergyProfile::compute(&[], RATE, START, &grid_for(10.0)).is_none());
    }

    #[test]
    fn bars_reach_the_window_edges_at_the_grid_tempo_through_missing_downbeats() {
        let sparse = grid(110.0, 41, false);
        let lines = bar_lines(&sparse, 100.0, 140.0);
        close(100.0, lines[0], 0.25);
        close(140.0, *lines.last().unwrap(), 0.25);
        for w in lines.windows(2) {
            close(BAR, w[1] - w[0], 1e-9);
        }
        let beats: Vec<f64> = (0..80).map(|i| 100.0 + 0.5 * i as f64).collect();
        let mut downbeats: Vec<f64> = beats.iter().enumerate().filter(|(i, _)| i % 4 == 0 && *i != 32 && *i != 36).map(|(_, b)| *b).collect();
        downbeats.push(120.5);
        downbeats.sort_by(f64::total_cmp);
        let with_gaps = bar_lines(&BeatGrid::new(beats, downbeats, 120.0, 0.9), 100.0, 140.0);
        for w in with_gaps.windows(2) {
            close(BAR, w[1] - w[0], 1e-9);
        }
        assert!(with_gaps.iter().any(|t| (t - 116.0).abs() < 1e-9));
    }
}
