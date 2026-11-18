//! Whether two analysed songs can be mixed on the beat, and how (the Android app's
//! `AutomixPlanner.kt`). Pure, so it can be tested.
//!
//! The rules follow how a club DJ, and Spotify's Automix, blend two songs: the overlap is counted
//! in bars (16, else 8, 4 or 2 when there is no room) and capped at 35 seconds and a fifth of the
//! outgoing song; it starts on a bar line over the outgoing song's outro; the incoming song plays
//! its intro from the first bar, and its first rise lands as the outgoing song leaves whenever the
//! intro is long enough. Bars are the grid's downbeats; a grid without downbeats counts groups of
//! four beats.

use crate::beat_grid::BeatGrid;
use crate::energy::EnergyProfile;
use crate::util::jround;

/// Below this, a grid is a guess: the transition falls back to a plain crossfade.
pub const MIN_CONFIDENCE: f64 = 0.55;
/// Farther apart than this, stretching one song to the other's tempo becomes audible.
pub const MAX_STRETCH: f64 = 0.10;
/// Songs shorter than this end on their own.
pub const MIN_SONG_MS: u64 = 60_000;
const MIX_BARS: [u32; 4] = [16, 8, 4, 2];
/// The shortest mix that is worth skipping part of an intro for, so its rise lands on the mix end.
const MIN_LANDING_BARS: u32 = 8;
const BAR_BEATS: u32 = 4;
const PHRASE_BARS: u32 = 8;
const MAX_MIX_MS: f64 = 35_000.0;
/// A mix never takes more than this share of the outgoing song.
const MAX_MIX_SHARE: f64 = 0.2;
/// Nothing after the last beat is worth mixing over: a fade-out tail or silence.
const END_MARGIN_BEATS: f64 = 1.0;
/// A marker this close after a bar line still belongs to it.
const SNAP_TOLERANCE_BEATS: f64 = 0.5;
/// The incoming grid may stop a beat short of the mix end: its phase keeps counting with the tempo.
const TRACKED_TOLERANCE_BEATS: f64 = 1.0;
/// The longest mix the listener can choose in the transition editor.
const MAX_FIXED_BARS: u32 = 32;

/// What the listener fixed in the transition editor; the rest Automix chooses.
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct PlanFixes {
    pub bars: Option<u32>,
    /// Where the outgoing song should hand over; moved to its nearest bar line that leaves room.
    pub exit_seconds: Option<f64>,
    /// Where the incoming song should start; moved to its nearest bar line.
    pub entry_seconds: Option<f64>,
}

/// A beat-matched transition between two songs.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct AutomixPlan {
    /// Where in the outgoing song the incoming one starts: a bar line, on a phrase when one is known.
    pub exit_ms: u64,
    /// Where the incoming song starts: the bar its intro starts on, or later.
    pub entry_ms: u64,
    /// Tempo of the incoming song during the mix, as a factor of its own (pitch kept).
    pub incoming_rate: f64,
    /// How long the two songs overlap, on the outgoing song's clock.
    pub mix_ms: u64,
    /// Length of the overlap in bars of the outgoing song: 16, 8, 4 or 2.
    pub mix_bars: u32,
    /// The incoming song's first rise lands as the mix ends.
    pub lands_rise: bool,
}

impl AutomixPlan {
    pub fn mix_beats(&self) -> u32 {
        self.mix_bars * BAR_BEATS
    }
}

/// The plan for `outgoing` (its ending) into `incoming` (its start), or `None` when they are left to
/// a plain crossfade: a short song, a weak grid, or tempos more than [`MAX_STRETCH`] apart.
pub fn plan(
    outgoing: &BeatGrid,
    outgoing_duration_ms: u64,
    incoming: &BeatGrid,
    outgoing_energy: Option<&EnergyProfile>,
    incoming_energy: Option<&EnergyProfile>,
) -> Option<AutomixPlan> {
    plan_fixed(outgoing, outgoing_duration_ms, incoming, outgoing_energy, incoming_energy, None)
}

/// [`plan`] with what the listener fixed in a mixed playlist's transition editor: a chosen length
/// is kept even past the caps (it only has to fit the analysed music), a chosen exit or entry
/// moves to the nearest bar line that works.
pub fn plan_fixed(
    outgoing: &BeatGrid,
    outgoing_duration_ms: u64,
    incoming: &BeatGrid,
    outgoing_energy: Option<&EnergyProfile>,
    incoming_energy: Option<&EnergyProfile>,
    fixes: Option<&PlanFixes>,
) -> Option<AutomixPlan> {
    if outgoing_duration_ms < MIN_SONG_MS {
        return None;
    }
    if outgoing.confidence() < MIN_CONFIDENCE || incoming.confidence() < MIN_CONFIDENCE {
        return None;
    }
    if outgoing.beats().is_empty() || incoming.beats().is_empty() || incoming.bpm() <= 0.0 {
        return None;
    }
    let rate = outgoing.bpm() / incoming.bpm();
    if (rate - 1.0).abs() > MAX_STRETCH {
        return None;
    }
    let bar_ms = BAR_BEATS as f64 * 60_000.0 / outgoing.bpm();
    let lengths: Vec<u32> = match fixes.and_then(|f| f.bars) {
        Some(bars) => vec![bars.clamp(1, MAX_FIXED_BARS)],
        None => MIX_BARS.iter().copied().filter(|&b| b as f64 * bar_ms <= MAX_MIX_MS && b as f64 * bar_ms <= MAX_MIX_SHARE * outgoing_duration_ms as f64).collect(),
    };
    let duration_s = outgoing_duration_ms as f64 / 1_000.0;
    let music_end = duration_s.min(outgoing.beats().last().copied().unwrap_or(0.0) + END_MARGIN_BEATS * outgoing.beat_seconds());
    let outro = outgoing_energy.and_then(|e| e.outro_start);
    let rise = incoming_energy.and_then(|e| e.first_rise);

    let fixed_entry = fixes.and_then(|f| f.entry_seconds);
    let fixed_exit = fixes.and_then(|f| f.exit_seconds);
    let mut candidates: Vec<(u32, Option<f64>)> = Vec::new();
    if let Some(r) = rise.filter(|_| fixed_entry.is_none()) {
        candidates.extend(lengths.iter().filter(|&&b| b >= MIN_LANDING_BARS).map(|&b| (b, Some(r))));
    }
    candidates.extend(lengths.iter().map(|&b| (b, None)));
    for (bars, target) in candidates {
        let mix_beats = bars * BAR_BEATS;
        let mix_seconds = bars as f64 * bar_ms / 1_000.0;
        let exit = fixed_exit.and_then(|w| nearest_exit(outgoing, music_end, mix_seconds, w)).or_else(|| exit_point(outgoing, music_end, mix_seconds, outro));
        let Some(exit) = exit else { continue };
        let entry = match fixed_entry {
            Some(w) => fitted_entry(incoming, mix_beats, w),
            None => entry_point(incoming, mix_beats, target),
        };
        let Some(entry) = entry else { continue };
        let lands = target.is_some()
            || (fixed_entry.is_some()
                && rise.is_some_and(|r| (incoming.beat_position(entry) + mix_beats as f64 - incoming.beat_position(r)).abs() <= SNAP_TOLERANCE_BEATS * 2.0));
        // Bars come from the tracked downbeats; where the two songs meet comes from each grid's
        // fitted line. The phone corrects the phase while it mixes; here both decks start on the
        // sample, so the starting points themselves must be free of the model's jitter.
        let (exit, entry) = (outgoing.snap(exit), incoming.snap(entry).max(0.0));
        return Some(AutomixPlan {
            exit_ms: jround(exit * 1_000.0) as u64,
            entry_ms: jround(entry * 1_000.0) as u64,
            incoming_rate: rate,
            mix_ms: jround(mix_seconds * 1_000.0) as u64,
            mix_bars: bars,
            lands_rise: lands,
        });
    }
    None
}

/// Bar lines of the outgoing song where a mix of `mix_seconds` fits, for the editor to snap a drag to.
pub fn exit_choices(grid: &BeatGrid, outgoing_duration_ms: u64, mix_seconds: f64) -> Vec<f64> {
    let Some(&last) = grid.beats().last() else { return Vec::new() };
    let music_end = (outgoing_duration_ms as f64 / 1_000.0).min(last + END_MARGIN_BEATS * grid.beat_seconds());
    let earliest = grid.beats()[0].max(0.0);
    bar_lines(grid).into_iter().filter(|&b| b >= earliest && b + mix_seconds <= music_end).collect()
}

/// Bar lines of the incoming song a mix of `mix_beats` can start on.
pub fn entry_choices(grid: &BeatGrid, mix_beats: u32) -> Vec<f64> {
    if grid.beats().is_empty() {
        return Vec::new();
    }
    bar_lines(grid)
        .into_iter()
        .filter(|&b| grid.beat_position(b) + mix_beats as f64 <= grid.last_beat_number() + TRACKED_TOLERANCE_BEATS)
        .map(|b| b.max(0.0))
        .collect()
}

/// The outgoing bar line nearest `wanted` that leaves room for the mix.
fn nearest_exit(grid: &BeatGrid, music_end: f64, mix_seconds: f64, wanted: f64) -> Option<f64> {
    let earliest = grid.beats()[0].max(0.0);
    crate::util::first_min_by(bar_lines(grid).into_iter().filter(|&b| b >= earliest && b + mix_seconds <= music_end), |b| (b - wanted).abs())
}

/// The incoming bar line nearest `wanted` from which the mix stays inside the tracked beats.
fn fitted_entry(grid: &BeatGrid, mix_beats: u32, wanted: f64) -> Option<f64> {
    crate::util::first_min_by(entry_choices(grid, mix_beats), |b| (b - wanted).abs())
}

/// Bar lines of `grid`: its downbeats, or every fourth beat when the model found none.
fn bar_lines(grid: &BeatGrid) -> Vec<f64> {
    if grid.beats_per_bar() > 1 {
        grid.downbeats().to_vec()
    } else {
        grid.beats().iter().step_by(BAR_BEATS as usize).copied().collect()
    }
}

/// Where the outgoing song hands over, or `None` when a mix of `mix_seconds` does not fit. With an
/// outro, the bar line at or just before it (moved back until the mix ends with the music);
/// without one, the last phrase line that leaves room, then the last bar line.
fn exit_point(grid: &BeatGrid, music_end: f64, mix_seconds: f64, outro: Option<f64>) -> Option<f64> {
    let earliest = grid.beats()[0].max(0.0);
    let fits = |t: f64| t >= earliest && t + mix_seconds <= music_end;
    let bars = bar_lines(grid);
    if bars.is_empty() {
        return None;
    }
    if let Some(outro) = outro {
        let start = bars.iter().copied().rev().find(|&b| b <= outro + SNAP_TOLERANCE_BEATS * grid.beat_seconds()).unwrap_or(bars[0]);
        if fits(start) {
            return Some(start);
        }
        return bars.iter().copied().rev().find(|&b| fits(b));
    }
    let anchor = grid.beat_position(bars[0]);
    let phrase = bars.iter().copied().rev().find(|&b| fits(b) && (jround(grid.beat_position(b) - anchor) as i64).rem_euclid((PHRASE_BARS * BAR_BEATS) as i64) == 0);
    phrase.or_else(|| bars.iter().copied().rev().find(|&b| fits(b)))
}

/// Where the incoming song starts: its first bar, or with a rise the bar line `mix_beats` before
/// it so the rise lands as the outgoing song leaves (`None` when the intro is shorter than that).
fn entry_point(grid: &BeatGrid, mix_beats: u32, rise: Option<f64>) -> Option<f64> {
    let bars = bar_lines(grid);
    let first = bars.first().copied().unwrap_or(grid.beats()[0]);
    let entry = match rise {
        None => first,
        Some(r) => {
            let target = grid.beat_position(r) - mix_beats as f64;
            if target < grid.beat_position(first) - SNAP_TOLERANCE_BEATS {
                return None;
            }
            crate::util::first_min_by(bars.iter().copied(), |b| (grid.beat_position(b) - target).abs())?
        }
    };
    if grid.beat_position(entry) + mix_beats as f64 > grid.last_beat_number() + TRACKED_TOLERANCE_BEATS {
        return None;
    }
    Some(entry.max(0.0))
}

#[cfg(test)]
mod tests {
    //! The planner part of the phone's AutomixTest, case for case.
    use super::*;

    fn grid_c(bpm: f64, start: f64, seconds: f64, confidence: f64) -> BeatGrid {
        let interval = 60.0 / bpm;
        let mut beats = Vec::new();
        let mut t = start;
        while t < start + seconds {
            beats.push(t);
            t += interval;
        }
        let downbeats = beats.iter().step_by(4).copied().collect();
        BeatGrid::new(beats, downbeats, bpm, confidence)
    }

    fn grid(bpm: f64, start: f64, seconds: f64) -> BeatGrid {
        grid_c(bpm, start, seconds, 0.9)
    }

    fn energy(outro: Option<f64>, rise: Option<f64>) -> EnergyProfile {
        EnergyProfile { bar_starts: vec![], bar_energy_db: vec![], is_calm: false, outro_start: outro, first_rise: rise }
    }

    fn tail() -> BeatGrid {
        grid(120.0, 120.5, 59.0)
    }

    fn head() -> BeatGrid {
        grid(123.0, 0.8, 59.0)
    }

    #[test]
    fn close_tempos_mix_on_a_downbeat_the_incoming_song_stretched_to_match() {
        let out = grid(120.0, 150.5, 29.0);
        let p = plan(&out, 180_000, &grid(123.0, 0.8, 29.0), None, None).unwrap();
        assert!(out.downbeats().iter().any(|d| (d * 1_000.0 - p.exit_ms as f64).abs() < 1.0));
        assert_eq!(p.entry_ms, 800);
        assert!((p.incoming_rate - 120.0 / 123.0).abs() < 1e-4);
        assert_eq!((p.mix_bars, p.mix_ms), (8, 16_000));
        assert!(p.exit_ms + p.mix_ms <= 180_000);
    }

    #[test]
    fn far_apart_tempos_weak_grids_and_short_songs_are_left_to_the_crossfade() {
        assert!(plan(&grid(120.0, 150.5, 29.0), 180_000, &grid(140.0, 0.5, 29.0), None, None).is_none());
        assert!(plan(&grid(120.0, 150.5, 29.0), 180_000, &grid_c(121.0, 0.5, 29.0, 0.4), None, None).is_none());
        assert!(plan(&grid(120.0, 20.0, 29.0), 50_000, &grid(120.0, 0.5, 29.0), None, None).is_none());
    }

    #[test]
    fn tempos_up_to_ten_percent_apart_still_mix_on_the_beat() {
        let p = plan(&grid(120.0, 120.5, 59.0), 180_000, &grid(132.0, 0.5, 59.0), None, None).unwrap();
        assert!((p.incoming_rate - 120.0 / 132.0).abs() < 1e-4);
    }

    #[test]
    fn slow_songs_get_fewer_bars_so_the_mix_stays_under_thirty_five_seconds() {
        let p = plan(&grid(45.0, 150.0, 29.0), 180_000, &grid(46.0, 0.0, 29.0), None, None).unwrap();
        assert_eq!((p.mix_bars, p.mix_ms), (4, 21_333));
    }

    #[test]
    fn a_mix_never_takes_more_than_a_fifth_of_the_outgoing_song() {
        let p = plan(&grid(120.0, 61.0, 59.0), 120_000, &grid(120.0, 0.5, 59.0), None, None).unwrap();
        assert_eq!((p.mix_bars, p.exit_ms), (8, 93_000));
    }

    #[test]
    fn without_energy_sixteen_bars_start_on_the_last_phrase_that_leaves_room() {
        let p = plan(&tail(), 180_000, &head(), None, None).unwrap();
        assert_eq!((p.exit_ms, p.entry_ms, p.mix_bars, p.mix_beats(), p.mix_ms, p.lands_rise), (136_500, 800, 16, 64, 32_000, false));
    }

    #[test]
    fn energy_without_markers_plans_like_no_energy_at_all() {
        assert_eq!(plan(&tail(), 180_000, &head(), None, None), plan(&tail(), 180_000, &head(), Some(&energy(None, None)), Some(&energy(None, None))));
    }

    #[test]
    fn the_mix_starts_where_a_long_outro_starts_even_off_the_phrase() {
        let p = plan(&tail(), 180_000, &head(), Some(&energy(Some(140.6), None)), None).unwrap();
        assert_eq!((p.exit_ms, p.mix_bars), (140_500, 16));
    }

    #[test]
    fn a_short_outro_ends_the_mix_with_the_music() {
        let p = plan(&tail(), 180_000, &head(), Some(&energy(Some(170.5), None)), None).unwrap();
        assert_eq!(p.exit_ms, 146_500);
        assert!(p.exit_ms + p.mix_ms <= 179_500 && p.exit_ms + p.mix_ms >= 177_500);
    }

    #[test]
    fn the_first_rise_lands_as_the_outgoing_song_leaves() {
        let h = head();
        let rise = h.beats()[80];
        let p = plan(&tail(), 180_000, &h, None, Some(&energy(None, Some(rise)))).unwrap();
        assert!(p.mix_bars == 16 && p.lands_rise);
        assert!((h.beats()[16] * 1_000.0 - p.entry_ms as f64).abs() <= 1.0);
        let mix_end = p.entry_ms as f64 / 1_000.0 + p.mix_beats() as f64 * h.beat_seconds();
        assert!((rise - mix_end).abs() <= h.beat_seconds());
    }

    #[test]
    fn an_intro_shorter_than_the_mix_lands_on_eight_bars_else_plays_in_full() {
        let h = head();
        let shorter = plan(&tail(), 180_000, &h, None, Some(&energy(None, Some(h.beats()[40])))).unwrap();
        assert!(shorter.mix_bars == 8 && shorter.lands_rise);
        assert!((h.beats()[8] * 1_000.0 - shorter.entry_ms as f64).abs() <= 1.0);
        let full = plan(&tail(), 180_000, &h, None, Some(&energy(None, Some(h.beats()[16])))).unwrap();
        assert_eq!((full.mix_bars, full.lands_rise, full.entry_ms), (16, false, 800));
    }

    #[test]
    fn the_exit_stays_inside_the_tracked_beats() {
        assert_eq!(plan(&tail(), 180_000, &head(), Some(&energy(Some(60.0), None)), None).unwrap().exit_ms, 120_500);
    }

    #[test]
    fn energy_does_not_rescue_songs_the_planner_refuses() {
        let (outro, rise) = (energy(Some(160.5), None), energy(None, Some(20.0)));
        assert!(plan(&tail(), 180_000, &grid(140.0, 0.5, 59.0), Some(&outro), Some(&rise)).is_none());
        assert!(plan(&tail(), 180_000, &grid_c(121.0, 0.5, 59.0, 0.4), Some(&outro), Some(&rise)).is_none());
        assert!(plan(&grid(120.0, 20.0, 29.0), 50_000, &head(), Some(&outro), Some(&rise)).is_none());
    }

    // The editor's fixes, from the phone's PlaylistMixTest.

    #[test]
    fn the_editors_bars_exit_and_entry_are_kept_on_bar_lines() {
        let (tail, head) = (tail(), head());
        let fixes = PlanFixes { bars: Some(8), exit_seconds: Some(150.9), entry_seconds: Some(9.0) };
        let plan = plan_fixed(&tail, 180_000, &head, None, None, Some(&fixes)).expect("a plan");
        assert_eq!(plan.mix_bars, 8);
        // 150.5 is the bar line nearest 150.9; bars on the incoming song fall every four beats from 0.8 s.
        assert_eq!(plan.exit_ms, 150_500);
        assert!(head.downbeats().iter().any(|d| (d * 1_000.0 - plan.entry_ms as f64).abs() < 1.0));
        assert!((plan.entry_ms as f64 / 1_000.0 - 9.0).abs() <= 2.0 * head.beat_seconds());
    }

    #[test]
    fn a_fixed_exit_too_late_for_the_mix_moves_to_the_last_bar_that_fits() {
        let fixes = PlanFixes { bars: Some(16), exit_seconds: Some(175.0), ..Default::default() };
        let plan = plan_fixed(&tail(), 180_000, &head(), None, None, Some(&fixes)).expect("a plan");
        assert_eq!(plan.exit_ms, 146_500);
    }

    #[test]
    fn the_editors_choices_leave_room_for_the_mix() {
        let exits = exit_choices(&tail(), 180_000, 16.0);
        assert_eq!(exits.first().copied(), Some(120.5));
        assert!(exits.iter().all(|e| e + 16.0 <= 179.5 + 1e-9));
        let entries = entry_choices(&head(), 32);
        assert!(!entries.is_empty() && entries.iter().all(|e| head().beat_position(*e) + 32.0 <= head().last_beat_number() + 1.0));
    }
}
