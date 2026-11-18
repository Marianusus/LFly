//! The effect ingredients of a transition (the Android app's `TransitionEffects.kt` and
//! `DjEffects.kt`): a send effect on the outgoing song (echo, delay, reverb, a noise riser), a
//! record stop and a loop. The shapes over the mix's progress are pure; the echo line, the reverb
//! and the loop buffer are the DSP the mixer runs them with.
//!
//! Progress is 0 at the start of the mix and 1 when the outgoing song is gone; past 1 the effect
//! rings on over the new song (its tail).
//!
//! Not ported: the phone's `DeckHistory` (the record's last seconds), which only Playlist Mix's
//! jogwheel plays. Its curves are here so the port can follow.

use std::f64::consts::PI;

/// Spotify's "Effects" ingredient: a send effect on the outgoing song. "Out" lets the song fade
/// into the effect, "cut" cuts it on the last beat and leaves the effect's tail ringing.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum EffectCurve {
    None,
    EchoHalfOut,
    Echo3qOut,
    Echo1Out,
    EchoHalfCut,
    Echo3qCut,
    Echo1Cut,
    DelayHalfCut,
    Delay3qCut,
    ReverbOutCenter,
    ReverbOutEnd,
    ReverbCutEnd,
    NoiseOutEnd,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum EffectKind {
    None,
    Echo,
    Delay,
    Reverb,
    Noise,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Ending {
    None,
    OutCenter,
    OutEnd,
    CutEnd,
}

impl EffectCurve {
    pub const ALL: [EffectCurve; 13] = [
        EffectCurve::None,
        EffectCurve::EchoHalfOut,
        EffectCurve::Echo3qOut,
        EffectCurve::Echo1Out,
        EffectCurve::EchoHalfCut,
        EffectCurve::Echo3qCut,
        EffectCurve::Echo1Cut,
        EffectCurve::DelayHalfCut,
        EffectCurve::Delay3qCut,
        EffectCurve::ReverbOutCenter,
        EffectCurve::ReverbOutEnd,
        EffectCurve::ReverbCutEnd,
        EffectCurve::NoiseOutEnd,
    ];

    /// The effect, its length in beats (echo and delay) and how the song leaves into it.
    fn parts(self) -> (EffectKind, f64, Ending) {
        use EffectCurve as C;
        use EffectKind as K;
        match self {
            C::None => (K::None, 0.0, Ending::None),
            C::EchoHalfOut => (K::Echo, 0.5, Ending::OutEnd),
            C::Echo3qOut => (K::Echo, 0.75, Ending::OutEnd),
            C::Echo1Out => (K::Echo, 1.0, Ending::OutEnd),
            C::EchoHalfCut => (K::Echo, 0.5, Ending::CutEnd),
            C::Echo3qCut => (K::Echo, 0.75, Ending::CutEnd),
            C::Echo1Cut => (K::Echo, 1.0, Ending::CutEnd),
            C::DelayHalfCut => (K::Delay, 0.5, Ending::CutEnd),
            C::Delay3qCut => (K::Delay, 0.75, Ending::CutEnd),
            C::ReverbOutCenter => (K::Reverb, 0.0, Ending::OutCenter),
            C::ReverbOutEnd => (K::Reverb, 0.0, Ending::OutEnd),
            C::ReverbCutEnd => (K::Reverb, 0.0, Ending::CutEnd),
            C::NoiseOutEnd => (K::Noise, 0.0, Ending::OutEnd),
        }
    }

    pub fn kind(self) -> EffectKind {
        self.parts().0
    }

    /// The echo's or delay's length, in beats.
    pub fn beats(self) -> f64 {
        self.parts().1
    }

    pub fn ending(self) -> Ending {
        self.parts().2
    }
}

/// Spotify's "Jogwheel" ingredient: the outgoing song stops like a record over its last beats.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum JogwheelCurve {
    None,
    VinylStopFast1,
    VinylStopFast2,
    VinylStopFast4,
    VinylStopSlow,
    Spinback1,
    Spinback2,
    Spinback4,
}

impl JogwheelCurve {
    pub const ALL: [JogwheelCurve; 8] = [
        JogwheelCurve::None,
        JogwheelCurve::VinylStopFast1,
        JogwheelCurve::VinylStopFast2,
        JogwheelCurve::VinylStopFast4,
        JogwheelCurve::VinylStopSlow,
        JogwheelCurve::Spinback1,
        JogwheelCurve::Spinback2,
        JogwheelCurve::Spinback4,
    ];

    pub fn spinback(self) -> bool {
        matches!(self, JogwheelCurve::Spinback1 | JogwheelCurve::Spinback2 | JogwheelCurve::Spinback4)
    }

    pub fn beats(self) -> f64 {
        match self {
            JogwheelCurve::None => 0.0,
            JogwheelCurve::VinylStopFast1 | JogwheelCurve::Spinback1 => 1.0,
            JogwheelCurve::VinylStopFast2 | JogwheelCurve::Spinback2 => 2.0,
            JogwheelCurve::VinylStopFast4 | JogwheelCurve::Spinback4 => 4.0,
            JogwheelCurve::VinylStopSlow => 8.0,
        }
    }
}

/// Spotify's "Looping" ingredient: the outgoing song holds a loop instead of running on.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum LoopCurve {
    None,
    OneBeat,
    TwoBeat,
    FourBeat,
    EightBeat,
    SixteenBeat,
    /// A loop roll over the last two bars: two beats, then one, then halving down to an eighth.
    Halving,
}

impl LoopCurve {
    pub fn beats(self) -> f64 {
        match self {
            LoopCurve::None => 0.0,
            LoopCurve::OneBeat => 1.0,
            LoopCurve::TwoBeat | LoopCurve::Halving => 2.0,
            LoopCurve::FourBeat => 4.0,
            LoopCurve::EightBeat => 8.0,
            LoopCurve::SixteenBeat => 16.0,
        }
    }
}

/// The send effect at one instant: the outgoing song's own level, what it sends, and the
/// effect's return.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct FxFrame {
    pub dry: f32,
    pub send: f32,
    pub wet: f32,
    pub noise: f32,
    pub noise_hz: f32,
}

pub const NOISE_LOW_HZ: f32 = 400.0;

impl FxFrame {
    pub const NONE: FxFrame = FxFrame { dry: 1.0, send: 0.0, wet: 1.0, noise: 0.0, noise_hz: NOISE_LOW_HZ };
}

/// Where the loop is at one instant: its start and length, both in beats from the start of the mix.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct LoopFrame {
    pub anchor_beats: f64,
    pub length_beats: f64,
}

/// Feedback of an echo (darker, longer repeats) and of a delay (clean, a few repeats).
pub const ECHO_FEEDBACK: f32 = 0.55;
pub const DELAY_FEEDBACK: f32 = 0.35;

const ECHO_TAIL_MS: u64 = 3_000;
const DELAY_TAIL_MS: u64 = 2_000;
const REVERB_TAIL_MS: u64 = 3_500;
const NOISE_TAIL_MS: u64 = 250;
const TAIL_FADE_SHARE: f32 = 0.4;

const NOISE_HIGH_HZ: f32 = 7_000.0;
const NOISE_PEAK: f32 = 0.12;

/// Turns of a spinback at its fastest, backwards, and the share of it spent reversing the record.
pub const SPINBACK_SPEED: f64 = 3.0;
const SPINBACK_ATTACK: f64 = 0.06;
const SPINBACK_FADE_FROM: f64 = 0.6;
const VINYL_FAST_EXPONENT: f64 = 1.5;
const VINYL_SLOW_EXPONENT: f64 = 1.0;
const VINYL_FADE_FROM: f64 = 0.85;

const HALVING_BEATS: f64 = 8.0;

/// How long `curve` rings after the mix, in ms of the outgoing song.
pub fn tail_ms(curve: EffectCurve) -> u64 {
    match curve.kind() {
        EffectKind::None => 0,
        EffectKind::Echo => ECHO_TAIL_MS,
        EffectKind::Delay => DELAY_TAIL_MS,
        EffectKind::Reverb => REVERB_TAIL_MS,
        EffectKind::Noise => NOISE_TAIL_MS,
    }
}

pub fn feedback(curve: EffectCurve) -> f32 {
    if curve.kind() == EffectKind::Delay { DELAY_FEEDBACK } else { ECHO_FEEDBACK }
}

/// The send effect at `p` (past 1 in the tail). `beat` is one beat as a share of the mix, `tail`
/// the tail as a share of the mix (the phone's default is 0.1). The send always closes by the time
/// the song's own level is gone, so the effect only repeats what was heard.
pub fn effect(curve: EffectCurve, p: f32, beat: f32, tail: f32) -> FxFrame {
    if curve.kind() == EffectKind::None {
        return FxFrame::NONE;
    }
    let b = beat.clamp(0.005, 0.25);
    let last_beat = 1.0 - b;
    let wet = if p <= 1.0 { 1.0 } else { tail_fade((p - 1.0) / tail.max(1e-4)) };
    if curve.kind() == EffectKind::Noise {
        // A white-noise riser: up over the second half under a rising high-pass, gone on the end.
        let t = ramp(p, 0.5, last_beat);
        let level = if p >= 1.0 { 0.0 } else { NOISE_PEAK * t * t };
        return FxFrame { noise: level, noise_hz: log_sweep(NOISE_LOW_HZ, NOISE_HIGH_HZ, t), ..FxFrame::NONE };
    }
    match curve.ending() {
        Ending::OutCenter => FxFrame {
            dry: fade_out(ramp(p, 0.3, 0.5)),
            send: ramp(p, 0.2, 0.4) * (1.0 - ramp(p, 0.45, 0.5)),
            wet,
            ..FxFrame::NONE
        },
        Ending::OutEnd => FxFrame {
            dry: fade_out(ramp(p, 0.75, 1.0)),
            send: if p >= 1.0 { 0.0 } else { ramp(p, 0.5, 0.75) },
            wet,
            ..FxFrame::NONE
        },
        Ending::CutEnd => {
            let send_from = if curve.kind() == EffectKind::Reverb { 0.6 } else { 0.75 };
            let cut = p >= last_beat;
            FxFrame {
                dry: if cut { 0.0 } else { 1.0 },
                send: if cut { 0.0 } else { ramp(p, send_from, (send_from + last_beat) / 2.0) },
                wet,
                ..FxFrame::NONE
            }
        }
        Ending::None => FxFrame::NONE,
    }
}

/// Where the jogwheel starts and how long it lasts, as shares of the mix; never longer than the mix.
pub fn jog_span(curve: JogwheelCurve, beat: f32) -> Option<(f32, f32)> {
    if curve == JogwheelCurve::None {
        return None;
    }
    let length = (curve.beats() as f32 * beat).clamp(1e-4, 1.0);
    Some((1.0 - length, length))
}

/// Playback rate of the record at `u` (0..1 across the jogwheel): 1 is the song's own speed,
/// below 0 backwards.
pub fn jog_rate(curve: JogwheelCurve, u: f64) -> f64 {
    let t = u.clamp(0.0, 1.0);
    if curve.spinback() {
        if t < SPINBACK_ATTACK {
            1.0 - (1.0 + SPINBACK_SPEED) * t / SPINBACK_ATTACK
        } else {
            let left = (1.0 - t) / (1.0 - SPINBACK_ATTACK);
            -SPINBACK_SPEED * left * left
        }
    } else {
        let exponent = if curve == JogwheelCurve::VinylStopSlow { VINYL_SLOW_EXPONENT } else { VINYL_FAST_EXPONENT };
        (1.0 - t).powf(exponent)
    }
}

/// Level of the record at `u`: it dies away as it stops, so it never ends on a click.
pub fn jog_level(curve: JogwheelCurve, u: f64) -> f64 {
    if u >= 1.0 {
        return 0.0;
    }
    let from = if curve.spinback() { SPINBACK_FADE_FROM } else { VINYL_FADE_FROM };
    if u <= from {
        return 1.0;
    }
    ((u - from) / (1.0 - from) * PI / 2.0).cos()
}

/// How many beats back a spinback reads at most, so the deck keeps enough of the song.
pub fn jog_reach_beats(curve: JogwheelCurve) -> f64 {
    if curve.spinback() { curve.beats() * 1.1 } else { curve.beats() }
}

/// The loop at `p`, or None while the song runs freely. A plain loop is set at the start of the
/// mix; the halving one rolls over the last two bars.
pub fn loop_at(curve: LoopCurve, p: f32, mix_beats: f64) -> Option<LoopFrame> {
    let beat = p as f64 * mix_beats;
    let length = loop_length_beats(curve, beat, mix_beats);
    (length > 0.0).then(|| LoopFrame { anchor_beats: loop_anchor_beats(curve, mix_beats), length_beats: length })
}

/// Where the loop starts, in beats from the start of the mix.
pub fn loop_anchor_beats(curve: LoopCurve, mix_beats: f64) -> f64 {
    if curve == LoopCurve::Halving { (mix_beats - HALVING_BEATS).max(0.0) } else { 0.0 }
}

/// The loop's length at `beat` of the mix, in beats; 0 while the song runs freely.
pub fn loop_length_beats(curve: LoopCurve, beat: f64, mix_beats: f64) -> f64 {
    if curve == LoopCurve::None || mix_beats <= 0.0 {
        return 0.0;
    }
    if beat < loop_anchor_beats(curve, mix_beats) {
        return 0.0;
    }
    if curve != LoopCurve::Halving {
        return curve.beats();
    }
    let left = mix_beats - beat;
    if left > 4.0 {
        2.0
    } else if left > 2.0 {
        1.0
    } else if left > 1.0 {
        0.5
    } else if left > 0.5 {
        0.25
    } else {
        0.125
    }
}

/// The longest loop `curve` holds, in beats: how much of the song the deck keeps for it.
pub fn loop_reach_beats(curve: LoopCurve) -> f64 {
    curve.beats()
}

fn ramp(p: f32, from: f32, to: f32) -> f32 {
    if to <= from {
        if p >= from { 1.0 } else { 0.0 }
    } else {
        ((p - from) / (to - from)).clamp(0.0, 1.0)
    }
}

fn fade_out(t: f32) -> f32 {
    (t.clamp(0.0, 1.0) as f64 * PI / 2.0).cos() as f32
}

fn tail_fade(t: f32) -> f32 {
    let from = 1.0 - TAIL_FADE_SHARE;
    if t <= from {
        return 1.0;
    }
    fade_out((t - from) / TAIL_FADE_SHARE)
}

fn log_sweep(from: f32, to: f32, t: f32) -> f32 {
    from * (to / from).powf(t.clamp(0.0, 1.0))
}

// ── The DSP that plays them ─────────────────────────────────────────────────

const DAMP_LOW_PASS_HZ: f64 = 3_500.0;
const DAMP_HIGH_PASS_HZ: f64 = 250.0;

/// A tempo-synced feedback delay, one line per channel. As an echo its repeats get darker and
/// thinner, as a DJ mixer's echo does; as a delay they stay clean.
pub struct EchoLine {
    capacity: usize,
    lines: Vec<Vec<f32>>,
    low_state: Vec<f64>,
    high_state: Vec<f64>,
    low_coef: f64,
    high_coef: f64,
    write: usize,
}

impl EchoLine {
    pub fn new(channels: usize, capacity: usize, sample_rate: f64) -> Self {
        let capacity = capacity.max(2);
        EchoLine {
            capacity,
            lines: vec![vec![0.0; capacity]; channels],
            low_state: vec![0.0; channels],
            high_state: vec![0.0; channels],
            low_coef: 1.0 - (-2.0 * PI * DAMP_LOW_PASS_HZ / sample_rate).exp(),
            high_coef: 1.0 - (-2.0 * PI * DAMP_HIGH_PASS_HZ / sample_rate).exp(),
            write: 0,
        }
    }

    /// Feeds `input` into `channel` and returns what comes back `delay_frames` later.
    pub fn process(&mut self, channel: usize, input: f64, delay_frames: usize, feedback: f64, damped: bool) -> f64 {
        let delay = delay_frames.clamp(1, self.capacity - 1);
        let read = (self.write + self.capacity - delay) % self.capacity;
        let line = &mut self.lines[channel];
        let delayed = line[read] as f64;
        let mut back = delayed;
        if damped {
            self.low_state[channel] += (back - self.low_state[channel]) * self.low_coef;
            back = self.low_state[channel];
            self.high_state[channel] += (back - self.high_state[channel]) * self.high_coef;
            back -= self.high_state[channel];
        }
        line[self.write] = (input + back * feedback) as f32;
        delayed
    }

    /// Moves on by one frame, after every channel was processed.
    pub fn advance(&mut self) {
        self.write = if self.write + 1 == self.capacity { 0 } else { self.write + 1 };
    }
}

const COMB_SIZES: [usize; 4] = [1_116, 1_188, 1_277, 1_356];
const ALL_PASS_SIZES: [usize; 2] = [556, 441];
const SPREAD: usize = 23;
const COMB_FEEDBACK: f64 = 0.86;
const COMB_DAMP: f64 = 0.3;
const REVERB_INPUT_GAIN: f64 = 0.03;
const REVERB_OUTPUT_GAIN: f64 = 2.2;

struct Comb {
    buffer: Vec<f32>,
    index: usize,
    store: f64,
}

impl Comb {
    fn process(&mut self, x: f64) -> f64 {
        let y = self.buffer[self.index] as f64;
        self.store = y * (1.0 - COMB_DAMP) + self.store * COMB_DAMP;
        self.buffer[self.index] = (x + self.store * COMB_FEEDBACK) as f32;
        self.index = if self.index + 1 == self.buffer.len() { 0 } else { self.index + 1 };
        y
    }
}

struct AllPass {
    buffer: Vec<f32>,
    index: usize,
}

impl AllPass {
    fn process(&mut self, x: f64) -> f64 {
        let b = self.buffer[self.index] as f64;
        self.buffer[self.index] = (x + b * 0.5) as f32;
        self.index = if self.index + 1 == self.buffer.len() { 0 } else { self.index + 1 };
        b - x
    }
}

/// A small plate-like reverb (four damped combs into two all-passes per channel, the Freeverb
/// design), tuned to wash out a song rather than place it in a room.
pub struct Reverb {
    combs: Vec<Vec<Comb>>,
    all_passes: Vec<Vec<AllPass>>,
}

impl Reverb {
    pub fn new(channels: usize, sample_rate: f64) -> Self {
        let scale = sample_rate / 44_100.0;
        let size = |base: usize, ch: usize| (((base + ch * SPREAD) as f64 * scale) as usize).max(1);
        Reverb {
            combs: (0..channels)
                .map(|ch| COMB_SIZES.iter().map(|&s| Comb { buffer: vec![0.0; size(s, ch)], index: 0, store: 0.0 }).collect())
                .collect(),
            all_passes: (0..channels)
                .map(|ch| ALL_PASS_SIZES.iter().map(|&s| AllPass { buffer: vec![0.0; size(s, ch)], index: 0 }).collect())
                .collect(),
        }
    }

    pub fn process(&mut self, channel: usize, input: f64) -> f64 {
        let x = input * REVERB_INPUT_GAIN;
        let mut out = 0.0;
        for comb in &mut self.combs[channel] {
            out += comb.process(x);
        }
        for all_pass in &mut self.all_passes[channel] {
            out = all_pass.process(out);
        }
        out * REVERB_OUTPUT_GAIN
    }
}

/// White noise, the same on every channel (a riser is not meant to sound wide).
pub struct NoiseSource {
    state: i32,
}

impl Default for NoiseSource {
    fn default() -> Self {
        NoiseSource { state: 0x2545_F491 }
    }
}

impl NoiseSource {
    pub fn next_sample(&mut self) -> f64 {
        let mut x = self.state;
        x ^= x << 13;
        x ^= ((x as u32) >> 17) as i32;
        x ^= x << 5;
        self.state = x;
        x as f64 / 2_147_483_648.0
    }
}

/// The stretch of a song a loop repeats, captured as it plays: from a few milliseconds before the
/// loop's start, which lets each repeat cross into the next without a click. Interleaved frames.
pub struct LoopBuffer {
    channels: usize,
    length_frames: usize,
    pub cross_frames: usize,
    buffer: Vec<f32>,
    /// Frames captured so far, counted from `cross_frames` before the loop start.
    pub captured: usize,
}

impl LoopBuffer {
    pub fn new(channels: usize, length_frames: usize, cross_frames: usize) -> Self {
        LoopBuffer { channels, length_frames, cross_frames, buffer: vec![0.0; (length_frames + cross_frames) * channels], captured: 0 }
    }

    pub fn is_full(&self) -> bool {
        self.captured >= self.length_frames + self.cross_frames
    }

    pub fn capture(&mut self, frame: &[f32]) {
        if self.is_full() {
            return;
        }
        let base = self.captured * self.channels;
        self.buffer[base..base + self.channels].copy_from_slice(&frame[..self.channels]);
        self.captured += 1;
    }

    /// The frame `offset` frames from the loop start; negative offsets reach into the lead-in.
    pub fn at(&self, offset: i64, channel: usize) -> f64 {
        let index = offset + self.cross_frames as i64;
        if index < 0 || index >= self.captured as i64 {
            return 0.0;
        }
        self.buffer[index as usize * self.channels + channel] as f64
    }

    /// The looped sound at `position` frames from the loop start, for a loop of `length` frames.
    /// Near the end of each repeat it crosses into the lead-in, which runs into the loop start
    /// seamlessly.
    pub fn looped(&self, position: u64, length: usize, channel: usize) -> f64 {
        let n = length.clamp(1, self.length_frames.max(1));
        let m = (position % n as u64) as i64;
        let cross = self.cross_frames.min(n / 2) as i64;
        let n = n as i64;
        if cross <= 0 || m < n - cross {
            return self.at(m, channel);
        }
        let w = (m - (n - cross) + 1) as f64 / cross as f64;
        self.at(m, channel) * (1.0 - w) + self.at(m - n, channel) * w
    }
}

#[cfg(test)]
mod tests {
    //! The phone's TransitionEffectsTest, its curve and reverb cases; the deck's cases are the
    //! mixer's tests in lfly-audio.
    use super::*;

    fn near(a: f64, b: f64, tol: f64, what: &str) {
        assert!((a - b).abs() <= tol, "{what}: expected {a}, got {b}");
    }

    #[test]
    fn every_effect_starts_silent_only_repeats_what_was_heard_and_rings_out() {
        let beat = 1.0 / 64.0;
        for curve in EffectCurve::ALL {
            let start = effect(curve, 0.0, beat, 0.1);
            near(1.0, start.dry as f64, 1e-6, &format!("{curve:?} dry at start"));
            near(0.0, start.send as f64, 1e-6, &format!("{curve:?} send at start"));
            near(0.0, start.noise as f64, 1e-6, &format!("{curve:?} noise at start"));
            let end = effect(curve, 1.0, beat, 0.1);
            near(0.0, end.send as f64, 1e-6, &format!("{curve:?} send at the end"));
            near(0.0, end.noise as f64, 1e-6, &format!("{curve:?} noise at the end"));
            if curve.kind() != EffectKind::Noise && curve != EffectCurve::None {
                near(0.0, end.dry as f64, 1e-6, &format!("{curve:?} dry at the end"));
                for i in 0..=200 {
                    let f = effect(curve, i as f32 / 200.0, beat, 0.1);
                    assert!(f.dry > 1e-3 || f.send < 1e-3 || curve.ending() == Ending::OutEnd, "{curve:?} sends after its dry is gone at {}", i as f32 / 200.0);
                }
                let tail = 0.25;
                near(0.0, effect(curve, 1.0 + tail, beat, tail).wet as f64, 1e-4, &format!("{curve:?} wet at the end of its tail"));
                near(1.0, effect(curve, 1.0 + tail * 0.3, beat, tail).wet as f64, 1e-6, &format!("{curve:?} wet early in its tail"));
            }
        }
    }

    #[test]
    fn reverb_out_center_is_gone_by_the_middle_echo_cut_keeps_the_song_until_the_last_beat() {
        let beat = 1.0 / 32.0;
        near(0.0, effect(EffectCurve::ReverbOutCenter, 0.5, beat, 0.1).dry as f64, 1e-6, "dry");
        assert!(effect(EffectCurve::ReverbOutCenter, 0.42, beat, 0.1).send > 0.9);
        let before_cut = effect(EffectCurve::Echo1Cut, 1.0 - beat - 0.001, beat, 0.1);
        near(1.0, before_cut.dry as f64, 1e-6, "dry before the cut");
        near(1.0, before_cut.send as f64, 1e-6, "send before the cut");
        near(0.0, effect(EffectCurve::Echo1Cut, 1.0 - beat, beat, 0.1).dry as f64, 1e-6, "dry on the cut");
    }

    #[test]
    fn a_record_stops_from_its_own_speed_a_spinback_goes_backwards_and_both_fall_silent() {
        for curve in JogwheelCurve::ALL.into_iter().filter(|c| *c != JogwheelCurve::None) {
            near(1.0, jog_rate(curve, 0.0), 1e-9, &format!("{curve:?} start"));
            near(0.0, jog_rate(curve, 1.0), 1e-9, &format!("{curve:?} end"));
            near(0.0, jog_level(curve, 1.0), 1e-9, &format!("{curve:?} silent at the end"));
            near(1.0, jog_level(curve, 0.0), 1e-9, &format!("{curve:?} full at the start"));
        }
        let mut previous = 1.0;
        for i in 0..=100 {
            let r = jog_rate(JogwheelCurve::VinylStopFast2, i as f64 / 100.0);
            assert!(r <= previous + 1e-12);
            previous = r;
        }
        assert!(jog_rate(JogwheelCurve::Spinback2, 0.2) < -1.0);
        let (start, length) = jog_span(JogwheelCurve::Spinback4, 1.0 / 64.0).unwrap();
        near(1.0 - 4.0 / 64.0, start as f64, 1e-6, "start");
        near(4.0 / 64.0, length as f64, 1e-6, "length");
        // Longer than the mix, it takes the whole mix.
        near(0.0, jog_span(JogwheelCurve::VinylStopSlow, 1.0 / 4.0).unwrap().0 as f64, 1e-6, "slow");
    }

    #[test]
    fn a_loop_holds_from_the_start_of_the_mix_and_a_loop_roll_halves_over_the_last_two_bars() {
        assert!(loop_at(LoopCurve::FourBeat, -0.01, 64.0).is_none());
        let held = loop_at(LoopCurve::FourBeat, 0.5, 64.0).unwrap();
        near(4.0, held.length_beats, 1e-9, "length");
        near(0.0, held.anchor_beats, 1e-9, "anchor");
        assert!(loop_at(LoopCurve::Halving, 0.5, 64.0).is_none());
        let lengths: Vec<f64> =
            [56.5, 60.5, 62.5, 63.2, 63.7, 63.99].iter().map(|b| loop_at(LoopCurve::Halving, (b / 64.0) as f32, 64.0).unwrap().length_beats).collect();
        assert_eq!(lengths, vec![2.0, 1.0, 0.5, 0.25, 0.125, 0.125]);
        near(56.0, loop_anchor_beats(LoopCurve::Halving, 64.0), 1e-9, "anchor");
    }

    fn rms(v: &[f64]) -> f64 {
        (v.iter().map(|x| x * x).sum::<f64>() / v.len() as f64).sqrt()
    }

    #[test]
    fn the_reverb_washes_at_about_the_songs_level_and_dies_away() {
        let rate = 44_100usize;
        let mut reverb = Reverb::new(1, rate as f64);
        let mut random = NoiseSource::default();
        let noise: Vec<f64> = (0..rate * 2).map(|_| random.next_sample() * 0.3).collect();
        let wet: Vec<f64> = noise.iter().map(|x| reverb.process(0, *x)).collect();
        let ratio = rms(&wet[rate..]) / rms(&noise);
        assert!((0.3..=1.5).contains(&ratio), "wet level {ratio} of the dry one");

        let mut impulse = Reverb::new(1, rate as f64);
        let response: Vec<f64> = (0..rate * 4).map(|i| impulse.process(0, if i == 0 { 1.0 } else { 0.0 })).collect();
        let early = rms(&response[(0.1 * rate as f64) as usize..(0.3 * rate as f64) as usize]);
        let late = rms(&response[(3.5 * rate as f64) as usize..rate * 4]);
        assert!(early > 1e-4, "rings: {early}");
        assert!(late < early * 0.05, "and dies away: {late} vs {early}");
    }

    #[test]
    fn an_echo_line_repeats_at_its_delay_and_dies_away() {
        let mut echo = EchoLine::new(1, 1_000, 44_100.0);
        let out: Vec<f64> = (0..400)
            .map(|i| {
                let y = echo.process(0, if i == 0 { 1.0 } else { 0.0 }, 100, 0.5, false);
                echo.advance();
                y
            })
            .collect();
        near(1.0, out[100], 1e-6, "first repeat");
        near(0.5, out[200], 1e-6, "second repeat");
        near(0.25, out[300], 1e-6, "third repeat");
        assert!(out.iter().enumerate().all(|(i, v)| i % 100 == 0 || v.abs() < 1e-9));
    }

    #[test]
    fn a_loop_buffer_repeats_its_loop_and_crosses_into_the_lead_in() {
        let mut l = LoopBuffer::new(1, 10, 2);
        for i in 0..20 {
            l.capture(&[i as f32]);
        }
        assert!(l.is_full());
        // Frames 0 and 1 are the lead-in, the loop starts on frame 2.
        near(2.0, l.looped(0, 10, 0), 1e-9, "start");
        near(3.0, l.looped(11, 10, 0), 1e-9, "second repeat");
        // A 4-frame loop crosses in over its last two frames into the lead-in.
        near(2.0, l.looped(4, 4, 0), 1e-9, "repeat");
        let crossing = l.looped(3, 4, 0);
        assert!(crossing < 5.0 && crossing >= 1.0, "{crossing}");
    }
}
