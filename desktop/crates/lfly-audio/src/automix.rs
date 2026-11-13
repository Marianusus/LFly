//! The two decks of an Automix (the Android app's `CrossfadeController.mixOnTheBeat` with its
//! `DjFilterProcessor`s), as a pure mixer: no device, no threads, so it is tested on click tracks.
//!
//! The engine hands it the outgoing song from the planned exit, sample-exact, and the incoming
//! song from its planned entry; the incoming deck runs through its own time stretcher at the
//! plan's rate, so its bars fall on the outgoing song's. Unlike the phone, which steers two players
//! it can only read approximately, both decks here are counted in samples: they start together on
//! the frame the plan chose and stay there, and the mixer never pads one deck with silence to keep
//! up with the other (it mixes only what both have).
//!
//! The progress of the mix is the outgoing song's own time since the exit, over the mix length, so
//! every key moment of the recipe (the swap, the cut) lands on the downbeat the plan put it on.

use lfly_mix::curves::{self, MixRecipe};
use lfly_mix::filter::{BiquadState, DeckFilters, DjFilterSettings};

use crate::stretch::Stretch;

/// A beat-matched transition, as the core planned it for the engine.
#[derive(Clone, Debug, PartialEq)]
pub struct MixPlan {
    /// The two songs, as the engine names them (`Source::key`): the plan is dropped if either changes.
    pub outgoing_key: String,
    pub incoming_key: String,
    /// Where in the outgoing song the mix starts, and where the incoming song starts.
    pub exit_ms: u64,
    pub entry_ms: u64,
    /// Tempo of the incoming song during the mix, as a factor of its own.
    pub rate: f64,
    /// How long the overlap lasts on the outgoing song's clock.
    pub mix_ms: u64,
    pub recipe: MixRecipe,
    /// Brings the outgoing song to the incoming one's level (linear).
    pub outgoing_gain: f32,
    /// One beat, as a share of the mix: how long a swap or a cut takes.
    pub swap_width: f32,
}

/// The mixer settings are designed again every this many frames (as the phone's processor does).
const BLOCK: usize = 32;

/// One deck's EQ, sweeping filter and gain, on stereo.
struct DeckDsp {
    rate: f64,
    states: [[BiquadState; 5]; 2],
}

impl DeckDsp {
    fn new(rate: u32) -> Self {
        DeckDsp { rate: rate as f64, states: [[BiquadState::default(); 5]; 2] }
    }

    /// Runs `frames` (interleaved stereo) through `settings`, the fader moving linearly from
    /// `from` to `to` across them.
    fn process(&mut self, settings: &DjFilterSettings, from: f32, to: f32, frames: &mut [f32]) {
        let f = DeckFilters::design(settings, self.rate);
        let stages = [f.bass, f.mid, f.high, f.high_pass, f.low_pass];
        let n = frames.len() / 2;
        for i in 0..n {
            let fader = from + (to - from) * (i as f32 / n.max(1) as f32);
            for (ch, states) in self.states.iter_mut().enumerate() {
                let mut x = frames[i * 2 + ch] as f64;
                for (stage, state) in stages.iter().zip(states.iter_mut()) {
                    match stage {
                        Some(c) => x = state.run(c, x),
                        None => state.pass(x),
                    }
                }
                frames[i * 2 + ch] = (x * f.gain) as f32 * fader;
            }
        }
    }
}

pub(crate) struct Mixer {
    pub plan: MixPlan,
    /// Output frames the mix lasts, and how many were mixed so far.
    total: u64,
    done: u64,
    /// Outgoing samples (from the exit on) and stretched incoming samples, not mixed yet.
    out_q: Vec<f32>,
    in_q: Vec<f32>,
    /// The incoming deck's tempo; it keeps playing the song after the mix.
    pub in_stretch: Stretch,
    out_dsp: DeckDsp,
    in_dsp: DeckDsp,
    last_volumes: (f32, f32),
    /// The limiter's gain on the sum: two songs at full level would clip it.
    limit: f32,
    /// Blocks mixed, and those the limiter turned down (for the log).
    pub blocks: u64,
    pub limited: u64,
}

/// The sum is kept under this.
const CEILING: f32 = 0.97;
/// The limiter lets go over about 100 ms (per block of 32 frames at 48 kHz).
const RELEASE: f32 = 0.015;

impl Mixer {
    pub fn new(plan: MixPlan, out_rate: u32) -> Self {
        let mut in_stretch = Stretch::new(out_rate);
        in_stretch.set_speed(plan.rate);
        // Engaged even when the rate is 1: it eases back to 1 later without a pass-through jump.
        in_stretch.engage();
        let total = (plan.mix_ms * out_rate as u64 / 1000).max(1);
        let start = curves::frame(&plan.recipe, 0.0, plan.outgoing_gain, curves::SWAP_AT, plan.swap_width);
        Mixer {
            total,
            done: 0,
            out_q: Vec::new(),
            in_q: Vec::new(),
            in_stretch,
            out_dsp: DeckDsp::new(out_rate),
            in_dsp: DeckDsp::new(out_rate),
            last_volumes: (start.outgoing.volume, start.incoming.volume),
            limit: 1.0,
            blocks: 0,
            limited: 0,
            plan,
        }
    }

    pub fn push_outgoing(&mut self, samples: &[f32]) {
        self.out_q.extend_from_slice(samples);
    }

    /// The incoming song, resampled, as decoded: stretched to the plan's rate here.
    pub fn push_incoming(&mut self, samples: &[f32]) {
        self.in_stretch.process(samples, &mut self.in_q);
    }

    /// The incoming deck is behind the outgoing one: decode more of the incoming song.
    pub fn wants_incoming(&self) -> bool {
        self.in_q.len() < self.out_q.len().max(BLOCK * 2)
    }

    pub fn wants_outgoing(&self) -> bool {
        !self.finished() && self.out_q.len() < BLOCK * 2 * 64
    }

    /// The outgoing song is gone: what follows is the incoming one alone.
    pub fn finished(&self) -> bool {
        self.done >= self.total
    }

    /// Mixes what both decks have, in whole blocks (and the last partial block of the mix).
    pub fn mix(&mut self, out: &mut Vec<f32>) {
        loop {
            let left = (self.total - self.done.min(self.total)) as usize;
            if left == 0 {
                return;
            }
            let ready = (self.out_q.len() / 2).min(self.in_q.len() / 2);
            let n = BLOCK.min(left);
            if ready < n {
                return;
            }
            let p_end = (self.done + n as u64) as f32 / self.total as f32;
            let fr = curves::frame(&self.plan.recipe, p_end, self.plan.outgoing_gain, curves::SWAP_AT, self.plan.swap_width);
            let mut a: Vec<f32> = self.out_q.drain(..n * 2).collect();
            let mut b: Vec<f32> = self.in_q.drain(..n * 2).collect();
            let (out0, in0) = self.last_volumes;
            // Two faders open together are trimmed to the power of one song (-3 dB when both are
            // full), as a DJ pulls the gains down for a long blend; an equal-power crossfade is
            // left as it is. The limiter below only has the peaks left to catch.
            let trim = |o: f32, i: f32| 1.0 / (o * o + i * i).max(1.0).sqrt();
            let (t0, t1) = (trim(out0, in0), trim(fr.outgoing.volume, fr.incoming.volume));
            self.out_dsp.process(&fr.outgoing.filter, out0 * t0, fr.outgoing.volume * t1, &mut a);
            self.in_dsp.process(&fr.incoming.filter, in0 * t0, fr.incoming.volume * t1, &mut b);
            self.last_volumes = (fr.outgoing.volume, fr.incoming.volume);
            // A peak limiter on the sum: down at once to keep the block under the ceiling, back up
            // slowly. Two loud masters at full level would otherwise clip.
            let peak = a.iter().zip(&b).fold(0f32, |p, (x, y)| p.max((x + y).abs()));
            let target = if peak * self.limit > CEILING { CEILING / peak } else { 1.0f32.min(self.limit + (1.0 - self.limit) * RELEASE) };
            let (g0, g1) = (self.limit, if peak * target > CEILING { CEILING / peak } else { target });
            self.limit = g1;
            self.blocks += 1;
            if g1 < 0.999 {
                self.limited += 1;
            }
            out.extend(a.iter().zip(&b).enumerate().map(|(i, (x, y))| {
                let g = if g1 < g0 { g1 } else { g0 + (g1 - g0) * ((i / 2) as f32 / n as f32) };
                (x + y) * g
            }));
            self.done += n as u64;
        }
    }

    /// After the mix: the incoming samples already stretched and not played yet (the outgoing
    /// ones left over are past the mix and dropped), and the incoming deck's stretcher.
    pub fn into_incoming(self) -> (Vec<f32>, Stretch) {
        (self.in_q, self.in_stretch)
    }

}

/// The incoming deck's way back to its own tempo once the outgoing song is gone, as Spotify's
/// Automix and the phone do it: a half-percent step every second, starting half a second after.
pub(crate) struct TempoReturn {
    pub rate: f64,
    /// Output frames until the next step.
    countdown: u64,
    step_frames: u64,
}

pub(crate) const RETURN_STEP: f64 = 0.005;

impl TempoReturn {
    pub fn new(rate: f64, out_rate: u32) -> Self {
        TempoReturn { rate, countdown: out_rate as u64 / 2, step_frames: out_rate as u64 }
    }

    pub fn done(&self) -> bool {
        (self.rate - 1.0).abs() < 1e-9
    }

    /// `frames` more played: the rate to use from now on, when it changed.
    pub fn advance(&mut self, frames: u64) -> Option<f64> {
        if self.done() {
            return None;
        }
        if frames < self.countdown {
            self.countdown -= frames;
            return None;
        }
        self.countdown = self.step_frames;
        self.rate = if self.rate > 1.0 { (self.rate - RETURN_STEP).max(1.0) } else { (self.rate + RETURN_STEP).min(1.0) };
        Some(self.rate)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use lfly_mix::curves::TransitionStyle;

    const RATE: u32 = 48_000;

    /// A short click every beat at `bpm`, the first at `first_s`, stereo, `seconds` long.
    fn clicks(bpm: f64, first_s: f64, seconds: f64, amp: f32) -> Vec<f32> {
        let n = (seconds * RATE as f64) as usize;
        let mut s = vec![0f32; n * 2];
        let every = 60.0 / bpm;
        let mut t = first_s;
        while t < seconds {
            let at = (t * RATE as f64).round() as usize;
            for i in 0..48.min(n.saturating_sub(at)) {
                let v = amp * (1.0 - i as f32 / 48.0);
                s[(at + i) * 2] = v;
                s[(at + i) * 2 + 1] = v;
            }
            t += every;
        }
        s
    }

    /// Times (s) where a channel jumps above `threshold` after silence.
    fn onsets(s: &[f32], threshold: f32) -> Vec<f64> {
        let mut out = Vec::new();
        let mut quiet = 1_000;
        for (i, f) in s.chunks_exact(2).enumerate() {
            if f[0].abs() > threshold && quiet > 400 {
                out.push(i as f64 / RATE as f64);
            }
            quiet = if f[0].abs() > threshold { 0 } else { quiet + 1 };
        }
        out
    }

    fn plan(rate: f64, mix_ms: u64, style: TransitionStyle) -> MixPlan {
        MixPlan {
            outgoing_key: "a".into(),
            incoming_key: "b".into(),
            exit_ms: 0,
            entry_ms: 0,
            rate,
            mix_ms,
            recipe: style.recipe(),
            outgoing_gain: 1.0,
            swap_width: 1.0 / 64.0,
        }
    }

    /// Runs a whole mix, feeding the decks in uneven pieces as the engine would.
    fn run(m: &mut Mixer, a: &[f32], b: &[f32]) -> Vec<f32> {
        let (mut ai, mut bi, mut out) = (0, 0, Vec::new());
        while !m.finished() {
            if m.wants_outgoing() && ai < a.len() {
                let end = (ai + 2 * 1_103).min(a.len());
                m.push_outgoing(&a[ai..end]);
                ai = end;
            }
            while m.wants_incoming() && bi < b.len() {
                let end = (bi + 2 * 977).min(b.len());
                m.push_incoming(&b[bi..end]);
                bi = end;
            }
            let before = out.len();
            m.mix(&mut out);
            if out.len() == before && ai >= a.len() && bi >= b.len() {
                break;
            }
        }
        out
    }

    #[test]
    fn the_decks_meet_on_the_beat_when_the_incoming_one_is_stretched() {
        // 120 BPM going out, 126 BPM coming in, slowed to 120: one click every 0.5 s from both.
        let a = clicks(120.0, 0.0, 9.0, 0.5);
        let b = clicks(126.0, 0.0, 9.0 * 126.0 / 120.0, 0.5);
        let mut m = Mixer::new(plan(120.0 / 126.0, 8_000, TransitionStyle::Fade), RATE);
        let out = run(&mut m, &a, &b);
        assert_eq!(out.len() / 2, 8 * RATE as usize, "the whole mix, never padded");
        // Every click of the mix sits on the 0.5 s grid: the incoming deck's clicks land on the
        // outgoing one's within 10 ms (the stretcher may move a frame by up to 8 ms to fit).
        let on = onsets(&out, 0.05);
        assert!(on.len() >= 14, "{} onsets", on.len());
        for t in on {
            let off = (t / 0.5 - (t / 0.5).round()).abs() * 0.5;
            assert!(off < 0.010, "click at {t:.4} s is {:.1} ms off the beat", off * 1000.0);
        }
    }

    #[test]
    fn nothing_clips_and_the_mix_ends_on_the_incoming_song() {
        let a = clicks(120.0, 0.0, 9.0, 0.9);
        let b = clicks(120.0, 0.0, 9.0, 0.9);
        for style in [TransitionStyle::BassSwap, TransitionStyle::EqSwap, TransitionStyle::Filter, TransitionStyle::Fade] {
            let mut m = Mixer::new(plan(1.0, 8_000, style), RATE);
            let out = run(&mut m, &a, &b);
            let peak = out.iter().fold(0f32, |p, x| p.max(x.abs()));
            assert!(peak <= 1.0, "{style:?}: peak {peak}");
            assert!(m.finished());
            // The incoming deck keeps what was stretched beyond the mix, the outgoing one is gone.
            let (rest, _) = m.into_incoming();
            assert!(rest.len() % 2 == 0);
        }
    }

    #[test]
    fn the_tempo_comes_back_half_a_percent_a_second() {
        let mut r = TempoReturn::new(0.95, RATE);
        assert_eq!(r.advance(RATE as u64 / 2 - 1), None);
        assert_eq!(r.advance(1), Some(0.955));
        let mut steps = 1;
        while !r.done() {
            if r.advance(RATE as u64).is_some() {
                steps += 1;
            }
        }
        assert_eq!(steps, 10);
        assert_eq!(r.rate, 1.0);
        let mut up = TempoReturn::new(1.02, RATE);
        up.advance(RATE as u64);
        assert!((up.rate - 1.015).abs() < 1e-9);
    }

    /// Clicks at `bpm` from `first_s`, an accent on every fourth, mono at `rate`.
    fn clicks_mono(bpm: f64, first_s: f64, from_s: f64, seconds: f64, rate: u32) -> Vec<f32> {
        let n = (seconds * rate as f64) as usize;
        let mut s = vec![0f32; n];
        let every = 60.0 / bpm;
        let mut k = 0usize;
        loop {
            let t = first_s + k as f64 * every - from_s;
            if t >= seconds {
                break;
            }
            if t >= 0.0 {
                let at = (t * rate as f64).round() as usize;
                let amp = if k % 4 == 0 { 0.8 } else { 0.45 };
                let len = (rate as usize / 50).min(n.saturating_sub(at));
                for i in 0..len {
                    let env = (-(i as f32) / (rate as f32 / 300.0)).exp();
                    s[at + i] += amp * env * ((i as f32) * 2.0 * std::f32::consts::PI * 1_000.0 / rate as f32).sin();
                }
            }
            k += 1;
        }
        s
    }

    fn stereo(m: &[f32]) -> Vec<f32> {
        m.iter().flat_map(|v| [*v, *v]).collect()
    }

    #[test]
    fn real_grids_line_the_decks_up_through_the_whole_mix() {
        use lfly_mix::{mel, planner};
        // Out: 124 BPM for 200 s. In: 128 BPM, slowed to 124 by the plan.
        let (out_bpm, in_bpm, out_first, in_first, dur) = (124.0, 128.0, 0.3, 0.55, 200.0);
        let sr = mel::SAMPLE_RATE;
        let tail_from = dur - 59.0;
        let tail = lfly_beat::track(&clicks_mono(out_bpm, out_first, tail_from, 59.0, sr), tail_from).expect("tail grid");
        let head = lfly_beat::track(&clicks_mono(in_bpm, in_first, 0.0, 59.0, sr), 0.0).expect("head grid");
        let p = planner::plan(&tail, (dur * 1000.0) as u64, &head, None, None).expect("a plan");
        let mut plan = plan(p.incoming_rate, p.mix_ms, TransitionStyle::Fade);
        plan.exit_ms = p.exit_ms;
        plan.entry_ms = p.entry_ms;
        // The decks from the plan's exit and entry, as the engine starts them.
        let exit = p.exit_ms as f64 / 1000.0;
        let entry = p.entry_ms as f64 / 1000.0;
        let a = stereo(&clicks_mono(out_bpm, out_first, exit, dur - exit, RATE));
        let b = stereo(&clicks_mono(in_bpm, in_first, entry, 80.0, RATE));
        let mut m = Mixer::new(plan, RATE);
        let out = run(&mut m, &a, &b);
        // Where the outgoing song's beats fall in the mix, and where each click starts.
        let beat = 60.0 / out_bpm;
        let phase0 = ((out_first - exit) / beat).rem_euclid(1.0) * beat;
        let left: Vec<f32> = out.iter().step_by(2).copied().collect();
        let mut worst = [0f64; 3];
        let span = left.len() as f64 / RATE as f64;
        let mut quiet = 10_000usize;
        let mut onsets = Vec::new();
        for (i, v) in left.iter().enumerate() {
            if v.abs() > 0.02 && quiet > (RATE as usize / 250) {
                onsets.push(i as f64 / RATE as f64);
            }
            quiet = if v.abs() > 0.02 { 0 } else { quiet + 1 };
        }
        for t in &onsets {
            let off = ((t - phase0) / beat - ((t - phase0) / beat).round()).abs() * beat;
            let third = ((t / span) * 3.0).min(2.0) as usize;
            worst[third] = worst[third].max(off);
        }
        println!("plan: exit {} ms, entry {} ms, rate {:.4}, {} bars; tail {:.2} BPM, head {:.2} BPM; worst offset per third {:?} ms", p.exit_ms, p.entry_ms, p.incoming_rate, p.mix_bars, tail.bpm(), head.bpm(), worst.map(|w| (w * 1000.0).round()));
        // Every click within 15 ms of the outgoing grid (the phone's own tolerance, 0.03 beats at
        // 120 BPM) at the start, middle and end of the mix: no drift. Two decks a few ms apart may
        // still read as two onsets here (the stretcher moves a frame by up to 8 ms to fit), but
        // never two per beat throughout.
        let beats = span / beat;
        assert!(onsets.len() as f64 >= beats - 2.0 && (onsets.len() as f64) < beats * 1.25, "{} onsets for {beats:.0} beats", onsets.len());
        for w in worst {
            assert!(w < 0.015, "{} ms off", w * 1000.0);
        }
    }
}
