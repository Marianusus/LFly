//! Beats and downbeats of a stretch of a song, ported from the Android app's `BeatGrid.kt`.

use crate::util::{first_min_by, jround, median};

/// A frame is a beat when it tops a seven-frame window and the model leans towards yes.
const PEAK_WINDOW: usize = 7;

/// Fewer beats than this make no grid.
pub const MIN_BEATS: usize = 8;

/// Tempos outside this range are not a pulse a mix can follow: [`BeatGrid::tempo`] answers 0.
pub const MIN_BPM: f64 = 50.0;
pub const MAX_BPM: f64 = 210.0;

/// Beats and downbeats of a stretch of a song, in seconds on the song's own timeline.
///
/// The phase functions extrapolate with the tempo outside the tracked beats, so a mix can keep
/// counting through a breakdown or past the end of the analysed window.
#[derive(Clone, Debug, PartialEq)]
pub struct BeatGrid {
    beats: Vec<f64>,
    downbeats: Vec<f64>,
    bpm: f64,
    confidence: f64,
    /// The number of each tracked beat, counted gap by gap at the grid's tempo: a beat the model
    /// missed (a gap of two beats, common in a quiet intro) still counts, and a stray one between
    /// two beats (a gap of nearly nothing) does not, so the count never slips against the music.
    numbers: Vec<f64>,
    /// The beats' least-squares line, time = `fit.0` + number × `fit.1`: where a beat falls with
    /// the model's frame-to-frame jitter (10–20 ms) averaged out.
    fit: (f64, f64),
}

/// Beats farther than this share of a beat from the first line are left out of the second.
const FIT_OUTLIER_BEATS: f64 = 0.15;

/// Least squares of `ts` against `ns` over the indexes `keep` lets through: (intercept, slope).
fn line(ns: &[f64], ts: &[f64], keep: impl Fn(usize) -> bool) -> Option<(f64, f64)> {
    let idx: Vec<usize> = (0..ns.len()).filter(|&i| keep(i)).collect();
    if idx.len() < 2 {
        return None;
    }
    let n = idx.len() as f64;
    let mn = idx.iter().map(|&i| ns[i]).sum::<f64>() / n;
    let mt = idx.iter().map(|&i| ts[i]).sum::<f64>() / n;
    let (mut cov, mut var) = (0.0, 0.0);
    for &i in &idx {
        cov += (ns[i] - mn) * (ts[i] - mt);
        var += (ns[i] - mn) * (ns[i] - mn);
    }
    if var <= 0.0 {
        return None;
    }
    let slope = cov / var;
    Some((mt - slope * mn, slope))
}

impl BeatGrid {
    /// A grid from beats already known (a saved analysis, a test). `beats` and `downbeats` are
    /// ascending, in seconds; `confidence` is 0..1. Nothing is checked: the tempo range is
    /// [`BeatGrid::tempo`]'s business, and the planner refuses weak or empty grids itself.
    pub fn new(beats: Vec<f64>, downbeats: Vec<f64>, bpm: f64, confidence: f64) -> BeatGrid {
        let beat_seconds = 60.0 / bpm;
        let mut numbers = vec![0.0; beats.len()];
        for index in 1..beats.len() {
            numbers[index] = numbers[index - 1] + jround((beats[index] - beats[index - 1]) / beat_seconds);
        }
        let first = beats.first().copied().unwrap_or(0.0);
        let fit = line(&numbers, &beats, |_| true)
            .and_then(|(t0, k)| {
                // Once more without the beats far off the first line (a stray, a phantom beat).
                line(&numbers, &beats, |i| (beats[i] - (t0 + numbers[i] * k)).abs() <= FIT_OUTLIER_BEATS * beat_seconds).or(Some((t0, k)))
            })
            .filter(|(_, k)| *k > 0.0 && (k - beat_seconds).abs() <= beat_seconds * 0.1)
            .unwrap_or((first, beat_seconds));
        BeatGrid { beats, downbeats, bpm, confidence, numbers, fit }
    }

    /// How far the tracked beats stray from the fitted line, at worst, in seconds (outliers left
    /// out of the fit included): how steady the tempo is, and how much a mix on it may drift.
    pub fn worst_residual(&self) -> f64 {
        let (t0, k) = self.fit;
        self.beats.iter().zip(&self.numbers).map(|(b, n)| (b - (t0 + n * k)).abs()).fold(0.0, f64::max)
    }

    /// The beat nearest `time`, on the beats' fitted line rather than as the model placed it:
    /// what two songs are lined up by.
    pub fn snap(&self, time: f64) -> f64 {
        let (t0, k) = self.fit;
        let n = jround((time - t0) / k);
        t0 + n * k
    }

    pub fn beats(&self) -> &[f64] {
        &self.beats
    }

    pub fn downbeats(&self) -> &[f64] {
        &self.downbeats
    }

    pub fn bpm(&self) -> f64 {
        self.bpm
    }

    /// 0..1: how regular the beats are and how decisive the model was.
    pub fn confidence(&self) -> f64 {
        self.confidence
    }

    pub fn beat_seconds(&self) -> f64 {
        60.0 / self.bpm
    }

    /// Beats are counted in 4/4 when the model found downbeats; otherwise only the beat lines up.
    pub fn beats_per_bar(&self) -> u32 {
        if self.downbeats.len() >= 2 {
            4
        } else {
            1
        }
    }

    /// The count of the last tracked beat: the number of beats the grid covers, missed ones included.
    pub fn last_beat_number(&self) -> f64 {
        self.numbers.last().copied().unwrap_or(0.0)
    }

    /// A continuous beat count: whole at every tracked beat, linear between beats. (A grid without
    /// beats counts from zero at the tempo alone.)
    pub fn beat_position(&self, time: f64) -> f64 {
        let (Some(&first), Some(&last)) = (self.beats.first(), self.beats.last()) else {
            return time / self.beat_seconds();
        };
        if time <= first {
            return (time - first) / self.beat_seconds();
        }
        if time >= last {
            return self.last_beat_number() + (time - last) / self.beat_seconds();
        }
        let mut low = 0;
        let mut high = self.beats.len() - 1;
        while high - low > 1 {
            let middle = (low + high) / 2;
            if self.beats[middle] <= time {
                low = middle;
            } else {
                high = middle;
            }
        }
        let span = self.numbers[high] - self.numbers[low];
        self.numbers[low] + span * (time - self.beats[low]) / (self.beats[high] - self.beats[low])
    }

    /// Where `time` falls inside its bar, in beats: 0 on a downbeat, up to [`BeatGrid::beats_per_bar`].
    pub fn bar_phase(&self, time: f64) -> f64 {
        let position = self.beat_position(time);
        if self.beats_per_bar() == 1 {
            return position - position.floor();
        }
        let anchor = self.beat_position(self.downbeats[0]);
        floor_mod(position - anchor, self.beats_per_bar() as f64)
    }

    /// Bar lines for a mix: the downbeats, or every fourth beat when the tracker found none.
    pub fn bar_lines(&self) -> Vec<f64> {
        if self.beats_per_bar() > 1 {
            self.downbeats.clone()
        } else {
            self.beats.iter().step_by(4).copied().collect()
        }
    }

    /// Builds a grid from a beat model's per-frame logits. `offset_seconds` is where the analysed
    /// audio starts in the song. `None` when the model found no usable pulse.
    pub fn from_logits(
        beat_logits: &[f32],
        downbeat_logits: &[f32],
        frame_rate: f64,
        offset_seconds: f64,
    ) -> Option<BeatGrid> {
        let beat_frames = Self::pick_peaks(beat_logits);
        if beat_frames.len() < MIN_BEATS {
            return None;
        }
        let beats: Vec<f64> = beat_frames.iter().map(|frame| frame / frame_rate + offset_seconds).collect();
        let bpm = Self::tempo(&beats);
        if bpm <= 0.0 {
            return None;
        }
        // The two heads peak independently; every downbeat is snapped onto its nearest beat.
        let mut downbeats: Vec<f64> = Vec::new();
        for frame in Self::pick_peaks(downbeat_logits) {
            let snapped = nearest(&beats, frame / frame_rate + offset_seconds);
            if !downbeats.contains(&snapped) {
                downbeats.push(snapped);
            }
        }
        downbeats.sort_by(f64::total_cmp);
        let strengths: Vec<f64> =
            beat_frames.iter().map(|&frame| beat_logits[(frame as usize).min(beat_logits.len() - 1)] as f64).collect();
        let confidence = Self::confidence_of(&beats, &strengths);
        Some(BeatGrid::new(beats, downbeats, bpm, confidence))
    }

    /// Positive local maxima over a seven-frame window, ties merged, refined between frames by a
    /// parabola through the peak and its neighbours (a 20 ms frame is coarse for beat matching).
    pub fn pick_peaks(logits: &[f32]) -> Vec<f64> {
        let half = PEAK_WINDOW / 2;
        let mut peaks: Vec<usize> = Vec::new();
        for (index, &value) in logits.iter().enumerate() {
            if value <= 0.0 {
                continue;
            }
            let from = index.saturating_sub(half);
            let to = (index + half).min(logits.len() - 1);
            if logits[from..=to].iter().all(|&neighbour| neighbour <= value) {
                peaks.push(index);
            }
        }
        let mut merged: Vec<usize> = Vec::new();
        let mut index = 0;
        while index < peaks.len() {
            let mut sum = peaks[index];
            let mut count = 1;
            while index + 1 < peaks.len() && peaks[index + 1] - peaks[index] <= 1 {
                index += 1;
                sum += peaks[index];
                count += 1;
            }
            merged.push(jround(sum as f64 / count as f64) as usize);
            index += 1;
        }
        merged
            .into_iter()
            .map(|frame| {
                if frame == 0 || frame >= logits.len() - 1 {
                    return frame as f64;
                }
                let left = logits[frame - 1] as f64;
                let centre = logits[frame] as f64;
                let right = logits[frame + 1] as f64;
                let curvature = left - 2.0 * centre + right;
                if curvature.abs() < 1e-9 {
                    frame as f64
                } else {
                    frame as f64 + (0.5 * (left - right) / curvature).clamp(-0.5, 0.5)
                }
            })
            .collect()
    }

    /// The tempo in BPM, fitted over the whole stretch, or 0 without a usable one (too few beats,
    /// or outside [`MIN_BPM`]..[`MAX_BPM`]).
    ///
    /// Each beat is numbered by counting median gaps between neighbours (so a dropped beat keeps
    /// the count right), then a least-squares line through number and time gives the interval.
    /// Over thirty seconds this is far finer than any single gap, which matters when two songs
    /// must stay together for a whole mix.
    pub fn tempo(beats: &[f64]) -> f64 {
        if beats.len() < MIN_BEATS {
            return 0.0;
        }
        let gaps: Vec<f64> = beats.windows(2).map(|pair| pair[1] - pair[0]).collect();
        let rough = median(&gaps);
        if rough <= 0.0 {
            return 0.0;
        }
        // Gap by gap, so the small bias of the median cannot add up over sixty beats.
        let mut numbers = vec![0.0; beats.len()];
        for index in 1..beats.len() {
            numbers[index] = numbers[index - 1] + jround(gaps[index - 1] / rough).max(1.0);
        }
        let mean_number = numbers.iter().sum::<f64>() / numbers.len() as f64;
        let mean_time = beats.iter().sum::<f64>() / beats.len() as f64;
        let mut covariance = 0.0;
        let mut variance = 0.0;
        for index in 0..beats.len() {
            let dn = numbers[index] - mean_number;
            covariance += dn * (beats[index] - mean_time);
            variance += dn * dn;
        }
        let interval = if variance > 0.0 { covariance / variance } else { rough };
        // A fit far from the median gap means the numbering went wrong: trust the median.
        let chosen = if interval > 0.0 && (interval - rough).abs() <= rough * 0.1 { interval } else { rough };
        let bpm = 60.0 / chosen;
        if (MIN_BPM..=MAX_BPM).contains(&bpm) {
            bpm
        } else {
            0.0
        }
    }

    /// Share of one-beat gaps and the median peak strength, kept below 1: a model is evidence, not proof.
    pub fn confidence_of(beats: &[f64], peak_logits: &[f64]) -> f64 {
        if beats.len() < MIN_BEATS {
            return 0.0;
        }
        let gaps: Vec<f64> = beats.windows(2).map(|pair| pair[1] - pair[0]).collect();
        let interval = median(&gaps);
        if interval <= 0.0 {
            return 0.0;
        }
        let regular = gaps.iter().filter(|&&gap| (gap - interval).abs() <= interval * 0.1).count() as f64 / gaps.len() as f64;
        let strength = 1.0 / (1.0 + (-(median(peak_logits) - 0.5)).exp());
        (0.35 + 0.4 * regular + 0.25 * strength).clamp(0.0, 0.95)
    }

    /// `value` wrapped into (-period/2, period/2]: the shortest way round a bar.
    pub fn wrap_centered(value: f64, period: f64) -> f64 {
        let wrapped = floor_mod(value, period);
        if wrapped > period / 2.0 {
            wrapped - period
        } else {
            wrapped
        }
    }
}

/// What two beat-matched songs line up on: bars (4) when both have downbeats, otherwise beats (1).
pub fn match_period(outgoing: &BeatGrid, incoming: &BeatGrid) -> u32 {
    if outgoing.beats_per_bar() > 1 && incoming.beats_per_bar() > 1 {
        outgoing.beats_per_bar()
    } else {
        1
    }
}

/// How far the incoming song's bar is ahead of the outgoing one's, in beats, the short way round:
/// what the engine drives to zero (with small tempo nudges on the incoming song) while both play.
/// The two times are what is being heard of each song, in seconds on its own timeline.
pub fn phase_error(outgoing: &BeatGrid, outgoing_seconds: f64, incoming: &BeatGrid, incoming_seconds: f64) -> f64 {
    let period = match_period(outgoing, incoming) as f64;
    // With a period of one beat only the beats are compared, whatever downbeats one side has.
    let phase = |grid: &BeatGrid, time: f64| {
        if period > 1.0 {
            grid.bar_phase(time)
        } else {
            let position = grid.beat_position(time);
            position - position.floor()
        }
    };
    BeatGrid::wrap_centered(phase(incoming, incoming_seconds) - phase(outgoing, outgoing_seconds), period)
}

fn floor_mod(value: f64, period: f64) -> f64 {
    value - period * (value / period).floor()
}

fn nearest(sorted: &[f64], time: f64) -> f64 {
    first_min_by(sorted.iter().copied(), |beat| (beat - time).abs()).unwrap_or(time)
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    /// A steady grid as the planner tests build it: beats every `60 / bpm` from `start` for
    /// `seconds`, a downbeat every fourth.
    pub(crate) fn steady(bpm: f64, start: f64, seconds: f64, confidence: f64) -> BeatGrid {
        let interval = 60.0 / bpm;
        let mut beats = Vec::new();
        let mut time = start;
        while time < start + seconds {
            beats.push(time);
            time += interval;
        }
        let downbeats = beats.iter().step_by(4).copied().collect();
        BeatGrid::new(beats, downbeats, bpm, confidence)
    }

    /// Logits with a beat every 25 frames (120 BPM at 50 fps) and a downbeat every fourth.
    fn logits(frames: usize) -> (Vec<f32>, Vec<f32>) {
        let mut beat = vec![-5f32; frames];
        let mut downbeat = vec![-5f32; frames];
        let mut index = 10;
        let mut count = 0;
        while index < frames {
            beat[index] = 4.0;
            if index > 0 {
                beat[index - 1] = 1.0;
            }
            if index + 1 < frames {
                beat[index + 1] = 1.0;
            }
            if count % 4 == 0 {
                downbeat[index] = 3.0;
            }
            index += 25;
            count += 1;
        }
        (beat, downbeat)
    }

    fn close(expected: f64, actual: f64, tolerance: f64) {
        assert!((expected - actual).abs() <= tolerance, "expected {expected}, got {actual}");
    }

    #[test]
    fn a_steady_pulse_becomes_a_confident_120_bpm_grid_in_4_4() {
        let (beat, downbeat) = logits(1_500);
        let grid = BeatGrid::from_logits(&beat, &downbeat, 50.0, 100.0).unwrap();

        close(120.0, grid.bpm(), 0.01);
        close(100.2, grid.beats()[0], 0.001);
        assert_eq!(4, grid.beats_per_bar());
        assert!(grid.confidence() > 0.8);
        close(0.0, grid.bar_phase(grid.downbeats()[1]), 1e-9);
        close(1.0, grid.bar_phase(grid.downbeats()[1] + 0.5), 1e-9);
        close(2.5, grid.bar_phase(grid.downbeats()[1] + 1.25), 1e-9);
    }

    #[test]
    fn the_phase_keeps_counting_past_the_last_tracked_beat() {
        let (beat, downbeat) = logits(1_500);
        let grid = BeatGrid::from_logits(&beat, &downbeat, 50.0, 0.0).unwrap();
        let after = grid.beats().last().unwrap() + 10.0 * grid.beat_seconds();
        close(grid.beats().len() as f64 - 1.0 + 10.0, grid.beat_position(after), 1e-9);
    }

    #[test]
    fn a_missed_beat_does_not_slip_the_count() {
        // 120 BPM from 0 s with beat 10 missing, as in a quiet intro.
        let beats: Vec<f64> = (0..40).filter(|&i| i != 10).map(|i| i as f64 * 0.5).collect();
        let grid = BeatGrid::new(beats, vec![0.0, 2.0, 4.0], 120.0, 0.9);
        close(20.0, grid.beat_position(10.0), 1e-9);
        close(39.0, grid.last_beat_number(), 1e-9);
        close(0.0, grid.bar_phase(16.0), 1e-9);
    }

    #[test]
    fn the_fitted_tempo_sees_through_jitter_and_a_dropped_beat() {
        let jitter = [0.012, -0.009, 0.004, -0.015, 0.008, 0.0, -0.006, 0.011];
        let beats: Vec<f64> =
            (0..60).filter(|&i| i != 23).map(|i| 1.0 + i as f64 * 60.0 / 124.0 + jitter[i % jitter.len()]).collect();
        close(124.0, BeatGrid::tempo(&beats), 0.05);
    }

    #[test]
    fn tempos_outside_50_to_210_bpm_are_no_tempo() {
        let at = |bpm: f64| (0..20).map(|i| i as f64 * 60.0 / bpm).collect::<Vec<f64>>();
        assert_eq!(0.0, BeatGrid::tempo(&at(45.0)));
        assert_eq!(0.0, BeatGrid::tempo(&at(220.0)));
        close(52.0, BeatGrid::tempo(&at(52.0)), 0.01);
        close(205.0, BeatGrid::tempo(&at(205.0)), 0.01);
        assert_eq!(0.0, BeatGrid::tempo(&at(120.0)[..7]));
    }

    #[test]
    fn no_pulse_no_grid() {
        assert_eq!(None, BeatGrid::from_logits(&[-3.0; 1_500], &[-3.0; 1_500], 50.0, 0.0));
        assert_eq!(None, BeatGrid::from_logits(&[], &[], 50.0, 0.0));
    }

    #[test]
    fn peaks_are_refined_between_frames() {
        let mut logits = [-1f32; 20];
        logits[9] = 2.0;
        logits[10] = 3.0;
        logits[11] = 2.5;
        let peaks = BeatGrid::pick_peaks(&logits);
        assert_eq!(1, peaks.len());
        assert!(peaks[0] > 10.0 && peaks[0] < 10.5);
    }

    #[test]
    fn phase_error_takes_the_short_way_round_the_bar() {
        close(-0.5, BeatGrid::wrap_centered(3.5, 4.0), 1e-9);
        close(1.0, BeatGrid::wrap_centered(1.0, 4.0), 1e-9);
        close(0.25, BeatGrid::wrap_centered(-3.75, 4.0), 1e-9);
    }

    #[test]
    fn two_songs_are_compared_bar_against_bar_or_beat_against_beat() {
        let outgoing = steady(120.0, 100.0, 30.0, 0.9);
        let incoming = steady(120.0, 0.5, 30.0, 0.9);
        assert_eq!(4, match_period(&outgoing, &incoming));
        close(0.0, phase_error(&outgoing, 108.0, &incoming, 4.5), 1e-9);
        // The incoming song a quarter of a beat late, then a whole beat early.
        close(-0.25, phase_error(&outgoing, 108.125, &incoming, 4.5), 1e-9);
        close(1.0, phase_error(&outgoing, 108.0, &incoming, 5.0), 1e-9);

        // Without downbeats on one side only the beats can line up: a whole beat off reads as on.
        let beats_only = BeatGrid::new(incoming.beats().to_vec(), Vec::new(), 120.0, 0.9);
        assert_eq!(1, match_period(&outgoing, &beats_only));
        close(0.0, phase_error(&outgoing, 108.0, &beats_only, 5.0), 1e-9);
        close(0.25, phase_error(&outgoing, 108.0, &beats_only, 5.125), 1e-9);
    }
}
