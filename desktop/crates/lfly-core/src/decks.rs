//! What DJ software reads from a song loaded on a deck: its tempo as a fixed beat grid, its key,
//! from the whole song decoded in memory (the Console's decks, `lfly_audio::decks`).

use lfly_audio::decks::TrackBuf;
use lfly_mix::beat_grid::BeatGrid;
use lfly_mix::key::MusicalKey;
use lfly_mix::mel;

/// A constant beat grid, as DJ software keeps one for each song: the tempo, where the first beat
/// falls, and which beat of the bar that one is.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Grid {
    pub bpm: f64,
    /// Seconds: the first beat at or after the song's start.
    pub first_beat: f64,
    /// The beat (0..4) of the bar the first beat is; 0 when it is a downbeat.
    pub first_in_bar: u32,
}

impl Grid {
    pub fn beat_seconds(&self) -> f64 {
        60. / self.bpm
    }

    /// The beat count at [seconds] (whole on every beat of the grid).
    pub fn beat_at(&self, seconds: f64) -> f64 {
        (seconds - self.first_beat) / self.beat_seconds()
    }

    pub fn time_of(&self, beat: f64) -> f64 {
        self.first_beat + beat * self.beat_seconds()
    }

    /// The nearest beat to [seconds], in seconds.
    pub fn snap(&self, seconds: f64) -> f64 {
        self.time_of(self.beat_at(seconds).round())
    }

    /// Where [seconds] falls in its bar: (beat 0..4, its fraction).
    pub fn in_bar(&self, seconds: f64) -> (u32, f64) {
        // (A hundredth of a beat early counts as on it, as the ear hears it.)
        let b = self.beat_at(seconds) + self.first_in_bar as f64 + 0.01;
        (b.floor().rem_euclid(4.) as u32, b - b.floor())
    }

    /// The same music half or twice as fast (VirtualDJ's BPM ×2, ÷2).
    pub fn scaled(&self, k: f64) -> Grid {
        Grid { bpm: self.bpm * k, ..*self }
    }

    /// Moved by [seconds] (the grid nudged by hand).
    pub fn shifted(&self, seconds: f64) -> Grid {
        let p = self.beat_seconds();
        Grid { first_beat: (self.first_beat + seconds).rem_euclid(p), ..*self }
    }

    /// A grid from a tapped tempo, its first beat on [beat_at] seconds.
    pub fn tapped(bpm: f64, beat_at: f64) -> Grid {
        let p = 60. / bpm;
        Grid { bpm, first_beat: beat_at.rem_euclid(p), first_in_bar: 0 }
    }
}

#[derive(Clone, Debug, Default)]
pub struct Analysis {
    pub grid: Option<Grid>,
    pub key: Option<MusicalKey>,
}

/// Tempo, grid and key of the song in [buf] (as much as is decoded). Takes a few seconds of CPU
/// for a song of a few minutes: call it off any thread that must stay responsive. Without the
/// beat model in the build there is no grid.
pub fn analyse(buf: &TrackBuf) -> Analysis {
    let started = std::time::Instant::now();
    let mono = buf.mono(mel::SAMPLE_RATE);
    let grid = lfly_beat::track(&mono, 0.).as_ref().and_then(constant);
    let key = lfly_mix::key::detect(&mono, mel::SAMPLE_RATE);
    log::info!(
        "deck analysis: {} s of audio, {} , key {} in {} ms",
        mono.len() / mel::SAMPLE_RATE as usize,
        grid.map_or("no grid".to_string(), |g| format!("{:.2} BPM, first beat {:.3} s", g.bpm, g.first_beat)),
        key.map_or("?".to_string(), |k| k.name()),
        started.elapsed().as_millis()
    );
    Analysis { grid, key }
}

/// How well a fixed grid of [period] fits [beats]: the length of their mean phase vector (1 when
/// every beat falls on it), and that phase in seconds.
fn fit_of(beats: &[f64], period: f64) -> (f64, f64) {
    let (mut x, mut y) = (0., 0.);
    for b in beats {
        let a = (b / period).fract() * std::f64::consts::TAU;
        x += a.cos();
        y += a.sin();
    }
    let n = beats.len().max(1) as f64;
    let strength = (x * x + y * y).sqrt() / n;
    let phase = (y.atan2(x) / std::f64::consts::TAU).rem_euclid(1.) * period;
    (strength, phase)
}

/// Beats taken together when judging a tempo: a song's tempo may wander (a live drummer, a
/// slower intro), so a grid is judged stretch by stretch and the phase may differ between them.
const WINDOW: usize = 32;

/// The tracked beats as one fixed grid, as DJ software keeps one: of the tempos within 4% of the
/// tracker's or of the median beat gap's (in steps of 0.01 BPM), the one the beats fall on best stretch by stretch, its phase
/// from the stretch that holds it best.
pub fn constant(g: &BeatGrid) -> Option<Grid> {
    let beats = g.beats();
    if g.bpm() <= 0. || beats.len() < lfly_mix::beat_grid::MIN_BEATS {
        return None;
    }
    let windows: Vec<&[f64]> = if beats.len() <= WINDOW { vec![beats] } else { (0..=beats.len() - WINDOW).step_by(WINDOW / 2).map(|i| &beats[i..i + WINDOW]).collect() };
    // Around the tracker's tempo and around the median gap's: a stray half beat among the tracked
    // ones can throw the tracker's own count (seen: 137.18 for a song at 129.6).
    let mut gaps: Vec<f64> = beats.windows(2).map(|w| w[1] - w[0]).collect();
    gaps.sort_by(f64::total_cmp);
    let by_gap = 60. / gaps[gaps.len() / 2];
    let mut candidates: Vec<i64> = Vec::new();
    for centre in [g.bpm(), by_gap] {
        let around = (centre * 100.).round() as i64;
        candidates.extend((around * 96 / 100)..=(around * 104 / 100));
    }
    candidates.sort_unstable();
    candidates.dedup();
    let mut best = (0., 0., 0.);
    for c in candidates {
        let bpm = c as f64 / 100.;
        let period = 60. / bpm;
        let (mut sum, mut top, mut phase) = (0., 0., 0.);
        for w in &windows {
            let (strength, ph) = fit_of(w, period);
            sum += strength;
            if strength > top {
                top = strength;
                phase = ph;
            }
        }
        let score = sum / windows.len() as f64;
        if score > best.0 {
            best = (score, bpm, phase);
        }
    }
    let (strength, bpm, phase) = best;
    let whole = fit_of(beats, 60. / bpm).0;
    let p = 60. / bpm;
    let first_beat = phase.rem_euclid(p);
    // The downbeats: which beat of the grid they land on, counted from the first.
    let first_in_bar = if g.beats_per_bar() > 1 {
        let mut votes = [0; 4];
        for d in g.downbeats() {
            votes[((d - first_beat) / p).round().rem_euclid(4.) as usize % 4] += 1;
        }
        let downbeat_mod = (0..4).max_by_key(|i| votes[*i]).unwrap_or(0) as u32;
        (4 - downbeat_mod) % 4
    } else {
        0
    };
    // How well it holds against what was tracked: beats within 30 ms, and the song's last ones.
    let off = |b: f64| BeatGrid::wrap_centered(b - first_beat, p).abs();
    let on = beats.iter().filter(|b| off(**b) <= 0.03).count();
    let tail = beats.iter().rev().take(16).map(|b| off(*b)).sum::<f64>() / beats.len().min(16) as f64;
    log::info!(
        "deck grid: tracker {:.2} BPM, grid {bpm:.2} BPM (fit {strength:.2} by stretches, {whole:.2} whole); {on} of {} beats within 30 ms, last 16 off by {:.0} ms",
        g.bpm(),
        beats.len(),
        tail * 1000.
    );
    Some(Grid { bpm, first_beat, first_in_bar })
}

/// [semitones] up or down from [key] (the deck's key shift).
pub fn shifted_key(key: MusicalKey, semitones: i32) -> MusicalKey {
    MusicalKey::new(((key.tonic as i32 + semitones).rem_euclid(12)) as u8, key.minor)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_steady_beat_makes_a_grid_on_its_phase_and_bar() {
        // 125 BPM from 0.3 s, a few beats jittered, downbeats every fourth from the third beat.
        let p = 60. / 125.;
        let beats: Vec<f64> = (0..64).map(|i| 0.3 + i as f64 * p + if i % 7 == 0 { 0.012 } else { 0. }).collect();
        let downbeats: Vec<f64> = beats.iter().skip(2).step_by(4).copied().collect();
        let g = constant(&BeatGrid::new(beats, downbeats, 125.0, 0.9)).unwrap();
        assert_eq!(g.bpm, 125.);
        assert!((g.first_beat - 0.3).abs() < 0.002, "{}", g.first_beat);
        // The first beat is the third of its bar: beat 2 → the bar starts two beats before.
        assert_eq!(g.first_in_bar, 2);
        assert_eq!(g.in_bar(0.3 + 2. * p).0, 0);
        assert!((g.snap(0.3 + 5.1 * p) - (0.3 + 5. * p)).abs() < 0.005);
    }

    #[test]
    fn a_tapped_grid_sits_on_the_tap() {
        let g = Grid::tapped(120., 10.25);
        assert!((g.first_beat - 0.25).abs() < 1e-9);
        assert!((g.beat_at(10.25) - 20.).abs() < 1e-9);
        assert_eq!(g.scaled(2.).bpm, 240.);
    }
}
