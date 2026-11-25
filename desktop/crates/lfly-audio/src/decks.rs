//! Two DJ decks mixed live, for the desktop Console: each song decoded whole into memory, read at
//! any speed and direction, through gain, a three-band kill EQ, a filter and effects, then the
//! channel faders, the crossfader, the master volume and a limiter, all inside the output callback.
//!
//! The music player ([`crate::Player`]) plays one song through a 2 s ring, which is right for
//! listening and useless for a DJ: a cue, a loop or a pitch move would be heard 2 s late. The decks
//! keep no ring: what the callback renders is what a control just set, a few milliseconds later.
//! They open their own stream on the same device and share only the disk cache with the player.
//!
//! The callback takes no lock, allocates nothing and frees nothing: commands come over a ring, the
//! song buffers it lets go of go back over another one, continuous controls are atomics.
//!
//! Positions are in frames of the deck's song at its own rate; the tempo of the grid comes from
//! the caller (the core's beat analysis), as frames per beat and the frame of the first beat.

use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, AtomicU8, AtomicUsize, Ordering};
use std::sync::{mpsc, Arc, Mutex, OnceLock};
use std::thread::JoinHandle;
use std::time::Duration;

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{FromSample, SizedSample};

use crate::cache::DiskCache;
use crate::decode::{Resample, TrackDecoder};
use crate::http::ReaderCtl;
use crate::Source;

pub const DECKS: usize = 2;
/// Effect slots on each deck.
pub const FX_SLOTS: usize = 3;
/// The sampler's slots.
pub const SAMPLES: usize = 8;
/// The longest song a deck holds (an hour-long mix is cut here): 20 minutes, ~230 MB at 48 kHz.
pub const MAX_SECONDS: usize = 20 * 60;
/// Frames per buffer chunk (~0.35 s): decoding publishes a chunk at a time.
const CHUNK: usize = 1 << 14;
/// Frames rendered per pass inside the callback.
const BLOCK: usize = 512;
/// The output pauses after this long with nothing playing.
const IDLE_PAUSE: Duration = Duration::from_secs(8);

// ---- Atomics for floats ----

#[derive(Default)]
pub struct AF32(AtomicU32);

impl AF32 {
    pub fn new(v: f32) -> Self {
        AF32(AtomicU32::new(v.to_bits()))
    }
    #[inline]
    pub fn get(&self) -> f32 {
        f32::from_bits(self.0.load(Ordering::Relaxed))
    }
    #[inline]
    pub fn set(&self, v: f32) {
        self.0.store(v.to_bits(), Ordering::Relaxed)
    }
}

#[derive(Default)]
pub struct AF64(AtomicU64);

impl AF64 {
    pub fn new(v: f64) -> Self {
        AF64(AtomicU64::new(v.to_bits()))
    }
    #[inline]
    pub fn get(&self) -> f64 {
        f64::from_bits(self.0.load(Ordering::Relaxed))
    }
    #[inline]
    pub fn set(&self, v: f64) {
        self.0.store(v.to_bits(), Ordering::Relaxed)
    }
}

// ---- A song in memory ----

/// A song decoded to 16-bit stereo at its own rate, filled chunk by chunk by a decoding thread
/// while the deck may already play its start.
pub struct TrackBuf {
    rate: u32,
    chunks: Box<[OnceLock<Box<[i16]>>]>,
    /// Frames published (contiguous from the start).
    ready: AtomicUsize,
    /// Frames the container announced (0: unknown).
    expected: usize,
    done: AtomicBool,
    /// The song was longer than [`MAX_SECONDS`].
    truncated: AtomicBool,
    failed: Mutex<Option<String>>,
    ctl: Arc<ReaderCtl>,
}

#[inline]
fn to_i16(s: f32) -> i16 {
    (s.clamp(-1., 1.) * 32767.) as i16
}

impl TrackBuf {
    fn empty(rate: u32, frames: usize, ctl: Arc<ReaderCtl>, expected: usize) -> TrackBuf {
        let slots = frames.div_ceil(CHUNK).max(1);
        TrackBuf {
            rate: rate.max(1),
            chunks: (0..slots).map(|_| OnceLock::new()).collect(),
            ready: AtomicUsize::new(0),
            expected,
            done: AtomicBool::new(false),
            truncated: AtomicBool::new(false),
            failed: Mutex::new(None),
            ctl,
        }
    }

    /// Opens [source] (for a stream this waits on the network for its first chunk), then decodes
    /// it on a thread of its own. Call it off any thread that must stay responsive.
    pub fn open(source: &Source, cache: &Arc<DiskCache>) -> Result<Arc<TrackBuf>, String> {
        let ctl = ReaderCtl::detached();
        let dec = TrackDecoder::open(source, cache, ctl.clone()).map_err(|e| e.to_string())?;
        let rate = dec.sample_rate().max(1);
        let cap = MAX_SECONDS * rate as usize;
        let expected = dec.duration_ms().map_or(0, |ms| (ms as u128 * rate as u128 / 1000) as usize);
        // Room for the longest song whatever the container says (a wrong length must not cut the
        // song short): a slot is a pointer, its samples are allocated as decoding fills it.
        let buf = Arc::new(TrackBuf::empty(rate, cap, ctl, expected.min(cap)));
        let b = buf.clone();
        std::thread::Builder::new()
            .name("lfly-deck-decode".into())
            .spawn(move || b.fill(dec))
            .map_err(|e| format!("decode thread: {e}"))?;
        Ok(buf)
    }

    /// A song already in memory (interleaved stereo), complete at once. For tests and samples.
    pub fn from_samples(rate: u32, samples: &[f32]) -> Arc<TrackBuf> {
        let frames = samples.len() / 2;
        let buf = TrackBuf::empty(rate, frames, ReaderCtl::detached(), frames);
        let mut published = 0;
        for (slot, part) in samples.chunks(CHUNK * 2).enumerate() {
            let _ = buf.chunks[slot].set(part.iter().map(|s| to_i16(*s)).collect());
            published += part.len() / 2;
        }
        buf.ready.store(published, Ordering::Release);
        buf.done.store(true, Ordering::Release);
        Arc::new(buf)
    }

    fn fill(&self, mut dec: TrackDecoder) {
        let mut pcm = Vec::with_capacity(16 * 1024);
        let mut resampled = Vec::new();
        let mut rs: Option<Resample> = None;
        let mut chunk: Vec<i16> = Vec::with_capacity(CHUNK * 2);
        let mut slot = 0;
        let mut frames = 0;
        'decode: loop {
            if self.ctl.aborted() {
                break;
            }
            pcm.clear();
            match dec.decode_next(&mut pcm) {
                Ok(true) => {}
                Ok(false) => break,
                Err(e) => {
                    if !self.ctl.aborted() {
                        log::warn!("deck decode of {}: {e}", dec.source().key());
                        *self.failed.lock().unwrap_or_else(|e| e.into_inner()) = Some(e.to_string());
                    }
                    break;
                }
            }
            // A stream that changes its rate midway is brought back to the one it started with.
            let samples: &[f32] = if dec.sample_rate() != self.rate {
                let r = rs.get_or_insert_with(|| Resample::new(dec.sample_rate(), self.rate));
                if r.rates().0 != dec.sample_rate() {
                    *r = Resample::new(dec.sample_rate(), self.rate);
                }
                resampled.clear();
                r.process(&pcm, &mut resampled);
                &resampled
            } else {
                &pcm
            };
            for s in samples {
                chunk.push(to_i16(*s));
                if chunk.len() == CHUNK * 2 {
                    frames += CHUNK;
                    let full = std::mem::replace(&mut chunk, Vec::with_capacity(CHUNK * 2));
                    let _ = self.chunks[slot].set(full.into_boxed_slice());
                    self.ready.store(frames, Ordering::Release);
                    slot += 1;
                    if slot == self.chunks.len() {
                        if frames >= MAX_SECONDS * self.rate as usize {
                            self.truncated.store(true, Ordering::Release);
                        }
                        break 'decode;
                    }
                }
            }
        }
        if !chunk.is_empty() && slot < self.chunks.len() {
            frames += chunk.len() / 2;
            let _ = self.chunks[slot].set(chunk.into_boxed_slice());
            self.ready.store(frames, Ordering::Release);
        }
        self.done.store(true, Ordering::Release);
    }

    pub fn rate(&self) -> u32 {
        self.rate
    }

    /// A finished buffer from 16-bit interleaved stereo (stems read back from the disk).
    pub fn from_i16(rate: u32, samples: Vec<i16>) -> Arc<TrackBuf> {
        let frames = samples.len() / 2;
        let buf = TrackBuf::empty(rate, frames.max(1), ReaderCtl::detached(), frames);
        for (slot, part) in samples.chunks(CHUNK * 2).enumerate() {
            let _ = buf.chunks[slot].set(part.to_vec().into_boxed_slice());
        }
        buf.ready.store(frames, Ordering::Release);
        buf.done.store(true, Ordering::Release);
        Arc::new(buf)
    }

    /// The song as interleaved stereo at [rate] (the stems model's input), as far as decoded.
    pub fn stereo(&self, rate: u32) -> Vec<f32> {
        let ready = self.ready();
        let mut rs = Resample::new(self.rate, rate);
        let mut out = Vec::with_capacity((ready as u64 * rate as u64 / self.rate as u64) as usize * 2 + 8192);
        let mut part = Vec::with_capacity(CHUNK * 2);
        let mut start = 0;
        while start < ready {
            let end = (start + CHUNK).min(ready);
            part.clear();
            for i in start..end {
                let [l, r] = self.frame(i as i64, ready);
                part.push(l);
                part.push(r);
            }
            rs.process(&part, &mut out);
            start = end;
        }
        rs.flush(&mut out);
        out
    }

    /// The whole buffer as stored: 16-bit interleaved stereo (stems written to the disk).
    pub fn raw(&self) -> Vec<i16> {
        let ready = self.ready();
        let mut out = Vec::with_capacity(ready * 2);
        for c in 0..ready.div_ceil(CHUNK) {
            if let Some(chunk) = self.chunks[c].get() {
                let want = ((ready - c * CHUNK).min(CHUNK)) * 2;
                out.extend_from_slice(&chunk[..want.min(chunk.len())]);
            }
        }
        out
    }

    /// Frames [start]..[end] as a buffer of their own (a sample: it must not keep the whole song
    /// in memory).
    pub fn slice(&self, start: f64, end: f64) -> Arc<TrackBuf> {
        let ready = self.ready();
        let (a, b) = (start.max(0.) as usize, (end.max(0.) as usize).min(ready));
        let frames = b.saturating_sub(a);
        let buf = TrackBuf::empty(self.rate, frames.max(1), ReaderCtl::detached(), frames);
        let mut slot = 0;
        let mut chunk = Vec::with_capacity(CHUNK * 2);
        for i in a..b {
            // The stored samples as they are (no round trip through floats).
            let (l, r) = self.chunks[i / CHUNK].get().and_then(|c| Some((*c.get((i % CHUNK) * 2)?, *c.get((i % CHUNK) * 2 + 1)?))).unwrap_or((0, 0));
            chunk.push(l);
            chunk.push(r);
            if chunk.len() == CHUNK * 2 {
                let _ = buf.chunks[slot].set(std::mem::replace(&mut chunk, Vec::with_capacity(CHUNK * 2)).into_boxed_slice());
                slot += 1;
            }
        }
        if !chunk.is_empty() {
            let _ = buf.chunks[slot].set(chunk.into_boxed_slice());
        }
        buf.ready.store(frames, Ordering::Release);
        buf.done.store(true, Ordering::Release);
        Arc::new(buf)
    }

    /// Frames decoded so far.
    pub fn ready(&self) -> usize {
        self.ready.load(Ordering::Acquire)
    }

    pub fn is_done(&self) -> bool {
        self.done.load(Ordering::Acquire)
    }

    pub fn truncated(&self) -> bool {
        self.truncated.load(Ordering::Acquire)
    }

    pub fn error(&self) -> Option<String> {
        self.failed.lock().unwrap_or_else(|e| e.into_inner()).clone()
    }

    /// The song's length in frames: what was decoded once done, else what the container said.
    pub fn frames(&self) -> usize {
        if self.is_done() { self.ready() } else { self.expected.max(self.ready()) }
    }

    pub fn duration_ms(&self) -> u64 {
        self.frames() as u64 * 1000 / self.rate as u64
    }

    /// Stops decoding (the deck let the song go).
    pub fn cancel(&self) {
        self.ctl.cancel();
    }

    /// Frame [i], silent past what is decoded ([ready] read once by the caller).
    #[inline]
    fn frame(&self, i: i64, ready: usize) -> [f32; 2] {
        if i < 0 || i as usize >= ready {
            return [0.; 2];
        }
        let i = i as usize;
        match self.chunks[i / CHUNK].get() {
            Some(c) => {
                let j = (i % CHUNK) * 2;
                match (c.get(j), c.get(j + 1)) {
                    (Some(l), Some(r)) => [*l as f32 / 32768., *r as f32 / 32768.],
                    _ => [0.; 2],
                }
            }
            None => [0.; 2],
        }
    }

    /// The song read at a fractional frame (cubic Hermite between its neighbours; exact on a
    /// whole frame).
    #[inline]
    fn at(&self, pos: f64, ready: usize) -> [f32; 2] {
        let i = pos.floor();
        let t = (pos - i) as f32;
        let i = i as i64;
        let x1 = self.frame(i, ready);
        if t == 0. {
            return x1;
        }
        let (x0, x2, x3) = (self.frame(i - 1, ready), self.frame(i + 1, ready), self.frame(i + 2, ready));
        let mut out = [0.; 2];
        for c in 0..2 {
            let c0 = x1[c];
            let c1 = 0.5 * (x2[c] - x0[c]);
            let c2 = x0[c] - 2.5 * x1[c] + 2. * x2[c] - 0.5 * x3[c];
            let c3 = 0.5 * (x3[c] - x0[c]) + 1.5 * (x1[c] - x2[c]);
            out[c] = ((c3 * t + c2) * t + c1) * t + c0;
        }
        out
    }

    /// The song in mono at [rate] (for the beat and key analysis), as far as it is decoded.
    pub fn mono(&self, rate: u32) -> Vec<f32> {
        let ready = self.ready();
        let mut rs = Resample::new(self.rate, rate);
        let mut out = Vec::with_capacity(ready * rate as usize / self.rate as usize * 2 + 4096);
        let mut stereo = Vec::with_capacity(CHUNK * 2);
        let mut mono = Vec::with_capacity(ready * rate as usize / self.rate as usize + 4096);
        let mut start = 0;
        while start < ready {
            let end = (start + CHUNK).min(ready);
            stereo.clear();
            for i in start..end {
                let [l, r] = self.frame(i as i64, ready);
                stereo.push(l);
                stereo.push(r);
            }
            out.clear();
            rs.process(&stereo, &mut out);
            mono.extend(out.as_chunks::<2>().0.iter().map(|f| (f[0] + f[1]) * 0.5));
            start = end;
        }
        out.clear();
        rs.flush(&mut out);
        mono.extend(out.as_chunks::<2>().0.iter().map(|f| (f[0] + f[1]) * 0.5));
        mono
    }

    /// The waveform, [per_second] columns a second of the song: each the loudest sample (0..255)
    /// and how its energy splits between lows (under ~200 Hz), mids and highs (over ~2.5 kHz),
    /// each 0..255, for a waveform coloured by frequency as DJ software draws it.
    pub fn peaks(&self, per_second: u32) -> Vec<[u8; 4]> {
        let ready = self.ready();
        let step = (self.rate / per_second.max(1)).max(1) as usize;
        let one_pole = |hz: f32| 1. - (-2. * std::f32::consts::PI * hz / self.rate as f32).exp();
        let (k_low, k_high) = (one_pole(200.), one_pole(2_500.));
        let (mut lp, mut lp_high) = (0f32, 0f32);
        let mut out = Vec::with_capacity(ready / step + 1);
        let mut i = 0;
        while i < ready {
            let end = (i + step).min(ready);
            let (mut peak, mut all, mut low, mut high) = (0f32, 0f32, 0f32, 0f32);
            for j in i..end {
                let [l, r] = self.frame(j as i64, ready);
                let m = (l + r) * 0.5;
                lp += k_low * (m - lp);
                lp_high += k_high * (m - lp_high);
                let h = m - lp_high;
                peak = peak.max(l.abs()).max(r.abs());
                all += m * m;
                low += lp * lp;
                high += h * h;
            }
            let share = |x: f32| if all > 1e-9 { (x / all).clamp(0., 1.) } else { 0. };
            let (lo, hi) = (share(low), share(high));
            let mid = (1. - lo - hi).clamp(0., 1.);
            out.push([(peak.min(1.) * 255.) as u8, (lo * 255.) as u8, (mid * 255.) as u8, (hi * 255.) as u8]);
            i = end;
        }
        out
    }
}

/// A song's parts, frame for frame with it (same rate and length): what the mixer's stem knobs
/// take out of it or leave. The fourth part ("other": melody, keys, the rest) is the song less
/// these three.
pub struct Stems {
    pub vocals: Arc<TrackBuf>,
    pub drums: Arc<TrackBuf>,
    pub bass: Arc<TrackBuf>,
}

/// The song at [pos], its parts weighed by [g] (vocals, drums, bass, other; 1 each = as it is).
#[inline]
fn read(buf: &TrackBuf, stems: Option<&Stems>, g: [f32; 4], pos: f64, ready: usize) -> [f32; 2] {
    let mix = buf.at(pos, ready);
    let Some(st) = stems else { return mix };
    if g.iter().all(|x| (*x - 1.).abs() < 1e-4) {
        return mix;
    }
    let other = g[3];
    let v = st.vocals.at(pos, st.vocals.ready());
    let d = st.drums.at(pos, st.drums.ready());
    let b = st.bass.at(pos, st.bass.ready());
    let mut out = [0.; 2];
    for c in 0..2 {
        out[c] = mix[c] * other + v[c] * (g[0] - other) + d[c] * (g[1] - other) + b[c] * (g[2] - other);
    }
    out
}

// ---- Filters ----

/// A state-variable filter (Simper's trapezoidal form): stays stable while its cutoff moves.
#[derive(Clone, Copy, Default)]
struct Svf {
    a1: f32,
    a2: f32,
    a3: f32,
    k: f32,
    ic1: [f32; 2],
    ic2: [f32; 2],
}

impl Svf {
    fn set(&mut self, freq: f32, q: f32, rate: f32) {
        let f = freq.clamp(10., rate * 0.45);
        let g = (std::f32::consts::PI * f / rate).tan();
        self.k = 1. / q.max(0.05);
        self.a1 = 1. / (1. + g * (g + self.k));
        self.a2 = g * self.a1;
        self.a3 = g * self.a2;
    }

    /// (low, band, high) of one sample on channel [c].
    #[inline]
    fn run(&mut self, x: f32, c: usize) -> (f32, f32, f32) {
        let v3 = x - self.ic2[c];
        let v1 = self.a1 * self.ic1[c] + self.a2 * v3;
        let v2 = self.ic2[c] + self.a2 * self.ic1[c] + self.a3 * v3;
        self.ic1[c] = 2. * v1 - self.ic1[c];
        self.ic2[c] = 2. * v2 - self.ic2[c];
        (v2, v1, x - self.k * v1 - v2)
    }

    fn clear(&mut self) {
        self.ic1 = [0.; 2];
        self.ic2 = [0.; 2];
    }
}

/// The mixer's three bands as a DJ isolator: a Linkwitz-Riley crossover at 250 Hz, then another at
/// 2.5 kHz on what is above it, so a band turned fully down is gone (a cut of 40 dB and more an
/// octave away) and the three at unity add up flat.
#[derive(Clone, Copy, Default)]
struct Isolator {
    /// Two Butterworth stages each: low-pass and high-pass at 250 Hz, then at 2.5 kHz.
    lp1: [Svf; 2],
    hp1: [Svf; 2],
    lp2: [Svf; 2],
    hp2: [Svf; 2],
}

const BUTTERWORTH_Q: f32 = std::f32::consts::FRAC_1_SQRT_2;

impl Isolator {
    fn new(rate: f32) -> Self {
        let mut me = Isolator::default();
        for s in me.lp1.iter_mut().chain(&mut me.hp1) {
            s.set(250., BUTTERWORTH_Q, rate);
        }
        for s in me.lp2.iter_mut().chain(&mut me.hp2) {
            s.set(2_500., BUTTERWORTH_Q, rate);
        }
        me
    }

    #[inline]
    fn run(&mut self, x: f32, c: usize, g: [f32; 3]) -> f32 {
        let a = self.lp1[0].run(x, c).0;
        let low = self.lp1[1].run(a, c).0;
        let a = self.hp1[0].run(x, c).2;
        let rest = self.hp1[1].run(a, c).2;
        let a = self.lp2[0].run(rest, c).0;
        let mid = self.lp2[1].run(a, c).0;
        let a = self.hp2[0].run(rest, c).2;
        let high = self.hp2[1].run(a, c).2;
        low * g[0] + mid * g[1] + high * g[2]
    }
}

/// A knob (0..1, 0.5 = unity) to a band's gain: fully left kills it, fully right is +6 dB.
pub fn eq_gain(knob: f32) -> f32 {
    let k = knob.clamp(0., 1.);
    if k <= 0.5 {
        let x = k / 0.5;
        x * x
    } else {
        10f32.powf((k - 0.5) / 0.5 * 6. / 20.)
    }
}

// ---- Effects ----

/// The effects a slot can hold.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum FxKind {
    Echo = 1,
    Reverb = 2,
    Flanger = 3,
    WahWah = 4,
    /// A beat-synced gate (VirtualDJ's "Cut").
    Cut = 5,
    Distortion = 6,
    Phaser = 7,
    Bitcrush = 8,
    /// White noise swept in (a riser).
    Noise = 9,
    /// A high-pass that climbs with the amount (the Mix FX filter).
    HighPass = 10,
}

impl FxKind {
    pub const ALL: [FxKind; 9] =
        [FxKind::Echo, FxKind::Reverb, FxKind::Flanger, FxKind::WahWah, FxKind::Cut, FxKind::Distortion, FxKind::Phaser, FxKind::Bitcrush, FxKind::Noise];

    /// What the filter knob can be instead of the filter (Color FX); 0 is the filter itself.
    pub const COLOR: [FxKind; 6] = [FxKind::Echo, FxKind::Reverb, FxKind::Flanger, FxKind::Distortion, FxKind::Noise, FxKind::Cut];
    /// What the crossfader can bring in as it moves (Mix FX).
    pub const MIX: [FxKind; 4] = [FxKind::HighPass, FxKind::Echo, FxKind::Reverb, FxKind::Noise];

    pub fn from_u8(v: u8) -> Option<FxKind> {
        FxKind::ALL.into_iter().chain([FxKind::Noise, FxKind::HighPass]).find(|k| *k as u8 == v)
    }

    pub fn name(self) -> &'static str {
        match self {
            FxKind::Echo => "ECHO",
            FxKind::Reverb => "REVERB",
            FxKind::Flanger => "FLANGER",
            FxKind::WahWah => "WAHWAH",
            FxKind::Cut => "CUT",
            FxKind::Distortion => "DISTORTION",
            FxKind::Phaser => "PHASER",
            FxKind::Bitcrush => "BITCRUSH",
            FxKind::Noise => "NOISE",
            FxKind::HighPass => "FILTER",
        }
    }
}

/// A delay line, its room allocated once.
struct Line {
    buf: Vec<[f32; 2]>,
    at: usize,
}

impl Line {
    fn new(frames: usize) -> Self {
        Line { buf: vec![[0.; 2]; frames.max(4)], at: 0 }
    }

    #[inline]
    fn push(&mut self, x: [f32; 2]) {
        self.buf[self.at] = x;
        self.at = (self.at + 1) % self.buf.len();
    }

    /// What was pushed [d] frames ago (fractional, linear).
    #[inline]
    fn tap(&self, d: f32) -> [f32; 2] {
        let n = self.buf.len();
        let d = d.clamp(1., (n - 2) as f32);
        let i = d.floor() as usize;
        let t = d - i as f32;
        let a = self.buf[(self.at + n - i) % n];
        let b = self.buf[(self.at + n - i - 1) % n];
        [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t]
    }

    fn clear(&mut self) {
        self.buf.iter_mut().for_each(|f| *f = [0.; 2]);
    }
}

/// A Schroeder reverb (Freeverb's tunings): four combs and two all-passes a channel.
struct Reverb {
    combs: [[Vec<f32>; 4]; 2],
    comb_at: [[usize; 4]; 2],
    comb_lp: [[f32; 4]; 2],
    passes: [[Vec<f32>; 2]; 2],
    pass_at: [[usize; 2]; 2],
}

impl Reverb {
    fn new(rate: f32) -> Self {
        let s = rate / 44_100.;
        let comb = |n: f32, c: usize| vec![0.; ((n + 23. * c as f32) * s) as usize + 1];
        let pass = |n: f32, c: usize| vec![0.; ((n + 23. * c as f32) * s) as usize + 1];
        Reverb {
            combs: [0, 1].map(|c| [1116., 1188., 1277., 1356.].map(|n| comb(n, c))),
            comb_at: [[0; 4]; 2],
            comb_lp: [[0.; 4]; 2],
            passes: [0, 1].map(|c| [556., 441.].map(|n| pass(n, c))),
            pass_at: [[0; 2]; 2],
        }
    }

    #[inline]
    fn run(&mut self, x: f32, c: usize) -> f32 {
        let (feedback, damp) = (0.84, 0.2);
        let mut out = 0.;
        for i in 0..4 {
            let line = &mut self.combs[c][i];
            let at = self.comb_at[c][i];
            let y = line[at];
            self.comb_lp[c][i] = y * (1. - damp) + self.comb_lp[c][i] * damp;
            line[at] = x * 0.015 + self.comb_lp[c][i] * feedback;
            self.comb_at[c][i] = (at + 1) % line.len();
            out += y;
        }
        for i in 0..2 {
            let line = &mut self.passes[c][i];
            let at = self.pass_at[c][i];
            let b = line[at];
            line[at] = out + b * 0.5;
            out = b - out;
            self.pass_at[c][i] = (at + 1) % line.len();
        }
        out
    }

    fn clear(&mut self) {
        for c in 0..2 {
            self.combs[c].iter_mut().for_each(|l| l.iter_mut().for_each(|s| *s = 0.));
            self.passes[c].iter_mut().for_each(|l| l.iter_mut().for_each(|s| *s = 0.));
            self.comb_lp[c] = [0.; 4];
        }
    }
}

/// One effect slot: whichever effect it holds, with every effect's state allocated up front.
struct FxUnit {
    kind: u8,
    echo: Line,
    flange: Line,
    reverb: Reverb,
    wah: Svf,
    phaser: [[f32; 2]; 4],
    crush_hold: [f32; 2],
    crush_count: u32,
    gate: f32,
    noise: u32,
    hp: Svf,
    hp_at: f32,
    /// A free-running sweep (0..1) for the flanger and the phaser.
    lfo: f32,
    /// How much of the effect is heard (0..1), eased so switching on and off does not click.
    on: f32,
    /// The knob, eased.
    amount: f32,
}

/// Where the deck is in its beat (0..1) and how many output frames one beat lasts now.
#[derive(Clone, Copy, Debug)]
struct Beat {
    phase: f32,
    frames: f32,
}

impl FxUnit {
    fn new(rate: f32) -> Self {
        FxUnit {
            kind: 0,
            echo: Line::new((rate * 2.2) as usize),
            flange: Line::new((rate * 0.02) as usize),
            reverb: Reverb::new(rate),
            wah: Svf::default(),
            phaser: [[0.; 2]; 4],
            crush_hold: [0.; 2],
            crush_count: 0,
            gate: 1.,
            noise: 0x9e37_79b9,
            hp: Svf::default(),
            hp_at: -1.,
            lfo: 0.,
            on: 0.,
            amount: 0.,
        }
    }

    fn clear(&mut self) {
        self.echo.clear();
        self.flange.clear();
        self.reverb.clear();
        self.wah.clear();
        self.phaser = [[0.; 2]; 4];
        self.gate = 1.;
    }

    /// Runs the slot over one block: [kind] 0 is off, [amount] 0..1, the block's beat at its start.
    fn run(&mut self, buf: &mut [[f32; 2]], kind: u8, amount: f32, beat: Beat, rate: f32) {
        // Another effect chosen: the one heard fades out first, then the new one starts clean.
        let wanted = kind != 0 && amount > 0.;
        let switching = kind != 0 && kind != self.kind;
        if switching && self.on == 0. {
            self.clear();
            self.kind = kind;
        }
        let target = if wanted && kind == self.kind { 1. } else { 0. };
        if self.on == 0. && target == 0. {
            return;
        }
        let step = 1. / (0.01 * rate);
        let amount = amount.clamp(0., 1.);
        let per_frame = 1. / beat.frames.max(1.);
        let mut phase = beat.phase;
        let tau = std::f32::consts::TAU;
        for f in buf.iter_mut() {
            self.on = if self.on < target { (self.on + step).min(target) } else { (self.on - step).max(target) };
            if wanted {
                self.amount += (amount - self.amount) * 0.002;
            }
            let w = self.amount;
            phase = (phase + per_frame).fract();
            let dry = *f;
            let wet: [f32; 2] = match FxKind::from_u8(self.kind) {
                Some(FxKind::Echo) => {
                    // Half a beat, the repeats longer as the knob turns.
                    let d = self.echo.tap(beat.frames * 0.5);
                    let fb = 0.3 + 0.5 * w;
                    self.echo.push([dry[0] + d[0] * fb, dry[1] + d[1] * fb]);
                    [dry[0] + d[0] * (0.4 + 0.6 * w), dry[1] + d[1] * (0.4 + 0.6 * w)]
                }
                Some(FxKind::Reverb) => {
                    let r = [self.reverb.run(dry[0], 0), self.reverb.run(dry[1], 1)];
                    [dry[0] * (1. - 0.4 * w) + r[0] * (0.5 + 2.5 * w), dry[1] * (1. - 0.4 * w) + r[1] * (0.5 + 2.5 * w)]
                }
                Some(FxKind::Flanger) => {
                    // 1..7 ms swept over eight beats.
                    self.lfo = (self.lfo + per_frame / 8.).fract();
                    let s = 0.5 - 0.5 * (self.lfo * tau).cos();
                    let t = self.flange.tap((0.001 + 0.006 * s) * rate);
                    let fb = 0.3 + 0.5 * w;
                    self.flange.push([dry[0] + t[0] * fb, dry[1] + t[1] * fb]);
                    [(dry[0] + t[0]) * 0.6, (dry[1] + t[1]) * 0.6]
                }
                Some(FxKind::WahWah) => {
                    let s = 0.5 - 0.5 * (phase * tau).cos();
                    self.wah.set(350. * 8f32.powf(s), 2. + 4. * w, rate);
                    [self.wah.run(dry[0], 0).1 * 1.6, self.wah.run(dry[1], 1).1 * 1.6]
                }
                Some(FxKind::Cut) => {
                    // Off on the second half of every half beat, eased over ~2 ms.
                    let g = if (phase * 2.).fract() < 0.5 { 1. } else { 1. - (0.3 + 0.7 * w) };
                    self.gate += (g - self.gate) * (1. / (0.002 * rate)).min(1.);
                    [dry[0] * self.gate, dry[1] * self.gate]
                }
                Some(FxKind::Distortion) => {
                    let drive = 2. + 20. * w;
                    let norm = 0.7 / drive.tanh();
                    [(dry[0] * drive).tanh() * norm, (dry[1] * drive).tanh() * norm]
                }
                Some(FxKind::Phaser) => {
                    self.lfo = (self.lfo + per_frame / 4.).fract();
                    let s = 0.5 - 0.5 * (self.lfo * tau).cos();
                    let t = (std::f32::consts::PI * 300. * 10f32.powf(s) / rate).tan();
                    let a = (t - 1.) / (t + 1.);
                    let mut out = [0.; 2];
                    for (c, o) in out.iter_mut().enumerate() {
                        let mut x = dry[c];
                        for st in &mut self.phaser {
                            let y = a * x + st[c];
                            st[c] = x - a * y;
                            x = y;
                        }
                        *o = (dry[c] + x * (0.5 + 0.5 * w)) * 0.7;
                    }
                    out
                }
                Some(FxKind::Noise) => {
                    // xorshift: white noise, thinned below as the knob opens.
                    let mut r = self.noise;
                    r ^= r << 13;
                    r ^= r >> 17;
                    r ^= r << 5;
                    self.noise = r;
                    let n = (r as f32 / u32::MAX as f32 - 0.5) * 0.5 * w;
                    [dry[0] + n, dry[1] + n]
                }
                Some(FxKind::HighPass) => {
                    if (w - self.hp_at).abs() > 1e-3 {
                        self.hp.set(20. * 300f32.powf(w), 0.8, rate);
                        self.hp_at = w;
                    }
                    [self.hp.run(dry[0], 0).2, self.hp.run(dry[1], 1).2]
                }
                Some(FxKind::Bitcrush) => {
                    let hold = 1 + (w * 15.) as u32;
                    if self.crush_count == 0 {
                        let levels = 2f32.powf(10. - 7. * w);
                        self.crush_hold = [(dry[0] * levels).round() / levels, (dry[1] * levels).round() / levels];
                    }
                    self.crush_count = (self.crush_count + 1) % hold;
                    self.crush_hold
                }
                None => dry,
            };
            let k = self.on;
            *f = [dry[0] + (wet[0] - dry[0]) * k, dry[1] + (wet[1] - dry[1]) * k];
        }
    }
}

// ---- Shared state ----

/// One deck as the controls see it: what they set, and where the callback says it is.
pub struct DeckState {
    /// Tempo ratio (1 = as recorded), from the pitch fader.
    pub pitch: AF32,
    /// A momentary push on the tempo (pitch bend, a nudge on the jog wheel): multiplies it.
    pub bend: AF32,
    /// Channel fader, 0..1.
    pub volume: AF32,
    /// Gain, linear.
    pub gain: AF32,
    /// LOW, MID, HIGH knobs, 0..1 (0.5 unity).
    pub eq: [AF32; 3],
    /// -1 (low-pass) .. 0 (off) .. 1 (high-pass).
    pub filter: AF32,
    pub fx_kind: [AtomicU8; FX_SLOTS],
    pub fx_amount: [AF32; FX_SLOTS],
    /// Song frames per beat (0: unknown, effects assume 120 BPM) and the first beat's frame.
    pub beat_frames: AF64,
    pub first_beat: AF64,
    /// Master tempo: the tempo changes, the key stays.
    pub keylock: AtomicBool,
    /// Which side of the crossfader: 0 none (always heard), 1 left, 2 right.
    pub assign: AtomicU8,
    /// What the filter knob does: 0 the filter, else an effect (Color FX).
    pub color: AtomicU8,
    /// The stems' levels: vocals, drums, bass, other (1 as recorded, 0 gone). Heard only once the
    /// deck has its song's stems.
    pub stems: [AF32; 4],

    /// Set by the callback.
    pub position: AF64,
    pub playing: AtomicBool,
    /// Where the song would be without loops and scratches (slip), while that differs.
    pub shadow: AF64,
    pub looping: AtomicBool,
    /// Loudest sample of the last block, after the channel fader.
    pub vu: AF32,
    /// Loads the callback has taken in.
    pub loads: AtomicU64,
    /// The song played to its end and stopped.
    pub ended: AtomicBool,
}

impl DeckState {
    fn new(side: u8) -> Self {
        DeckState {
            pitch: AF32::new(1.),
            bend: AF32::new(1.),
            volume: AF32::new(1.),
            gain: AF32::new(1.),
            eq: [AF32::new(0.5), AF32::new(0.5), AF32::new(0.5)],
            filter: AF32::new(0.),
            fx_kind: Default::default(),
            fx_amount: Default::default(),
            beat_frames: AF64::new(0.),
            first_beat: AF64::new(0.),
            keylock: AtomicBool::new(false),
            assign: AtomicU8::new(side),
            color: AtomicU8::new(0),
            stems: [AF32::new(1.), AF32::new(1.), AF32::new(1.), AF32::new(1.)],
            position: AF64::new(0.),
            playing: AtomicBool::new(false),
            shadow: AF64::new(0.),
            looping: AtomicBool::new(false),
            vu: AF32::new(0.),
            loads: AtomicU64::new(0),
            ended: AtomicBool::new(false),
        }
    }
}

/// Crossfader curves, as VirtualDJ names them.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum Curve {
    /// Both decks at full level in the middle, each fading only on the far half (the default).
    Full = 0,
    /// Constant power: the sum keeps its loudness across.
    Smooth = 1,
    /// Both full until the very ends.
    Cut = 2,
    /// Cut, sharper still, for scratching.
    Scratch = 3,
}

impl Curve {
    pub const ALL: [Curve; 4] = [Curve::Full, Curve::Smooth, Curve::Cut, Curve::Scratch];

    pub fn from_u8(v: u8) -> Curve {
        Curve::ALL.into_iter().find(|c| *c as u8 == v).unwrap_or(Curve::Full)
    }

    pub fn name(self) -> &'static str {
        match self {
            Curve::Full => "FULL",
            Curve::Smooth => "SMOOTH",
            Curve::Cut => "CUT",
            Curve::Scratch => "SCRATCH",
        }
    }

    /// The levels of the left and right deck with the crossfader at [x] (0 left .. 1 right).
    pub fn levels(self, x: f32) -> [f32; 2] {
        let x = x.clamp(0., 1.);
        let edge = |d: f32, w: f32| (d / w).clamp(0., 1.);
        match self {
            Curve::Full => [(2. * (1. - x)).min(1.), (2. * x).min(1.)],
            Curve::Smooth => [(x * std::f32::consts::FRAC_PI_2).cos(), (x * std::f32::consts::FRAC_PI_2).sin()],
            Curve::Cut => [edge(1. - x, 0.06), edge(x, 0.06)],
            Curve::Scratch => [edge(1. - x, 0.015), edge(x, 0.015)],
        }
    }
}

pub struct MixState {
    pub decks: [DeckState; DECKS],
    /// 0 (deck A only) .. 1 (deck B only).
    pub xfader: AF32,
    pub curve: AtomicU8,
    /// The crossfader reversed (hamster).
    pub hamster: AtomicBool,
    pub master: AF32,
    pub master_vu: AF32,
    /// The limiter is pulling the master down (dB of reduction, 0 when idle).
    pub limiting: AF32,
    pub out_rate: AtomicU32,
    /// Frames the callback rendered (a clock for the controls).
    pub rendered: AtomicU64,
    pub recording: AtomicBool,
    /// The crossfader's own effect (Mix FX): 0 none, else an effect on the deck it moves away from.
    pub mix_fx: AtomicU8,
    /// An effect on the whole mix, and how much.
    pub master_fx: AtomicU8,
    pub master_fx_amount: AF32,
    pub sampler_volume: AF32,
    /// The sampler's voices playing.
    pub samples_playing: [AtomicBool; SAMPLES],
    /// Something plays (or records, or an effect tail rings): the output must stay running.
    pub busy: AtomicBool,
    /// The stream failed (device gone): the device thread reopens it.
    failed: AtomicBool,
}

impl MixState {
    fn new() -> Self {
        MixState {
            decks: [DeckState::new(1), DeckState::new(2)],
            xfader: AF32::new(0.5),
            curve: AtomicU8::new(Curve::Full as u8),
            hamster: AtomicBool::new(false),
            master: AF32::new(1.),
            master_vu: AF32::new(0.),
            limiting: AF32::new(0.),
            out_rate: AtomicU32::new(48_000),
            rendered: AtomicU64::new(0),
            recording: AtomicBool::new(false),
            mix_fx: AtomicU8::new(0),
            master_fx: AtomicU8::new(0),
            master_fx_amount: AF32::new(0.5),
            sampler_volume: AF32::new(0.8),
            samples_playing: Default::default(),
            busy: AtomicBool::new(false),
            failed: AtomicBool::new(false),
        }
    }
}

// ---- Commands ----

enum Cmd {
    Load { deck: usize, buf: Arc<TrackBuf>, at: f64 },
    Eject(usize),
    Play(usize, bool),
    /// To a frame, with a few ms of crossfade from where it was.
    Jump(usize, f64),
    /// Moves the playhead by frames (beat matching by hand), without a jump's fade.
    Nudge(usize, f64),
    Loop(usize, Option<(f64, f64)>),
    /// A loop that, ended, leaves the deck where it would have been (loop roll, slicer).
    Roll(usize, Option<(f64, f64)>),
    Slip(usize, bool),
    Reverse(usize, bool),
    /// Reverse while held, the song going on underneath (censor).
    Censor(usize, bool),
    /// The platter held (Some: its speed, as a tempo ratio, 0 for a hand at rest) or let go.
    Scratch(usize, Option<f64>),
    /// Slows to a stop over that many seconds (vinyl brake), or spins back that long.
    Brake(usize, f64),
    Backspin(usize, f64),
    Record(Option<rtrb::Producer<f32>>),
    /// A sampler slot gets a stretch of a song (start, end in its frames; looping or once), or
    /// is emptied.
    Sample(usize, Option<(Arc<TrackBuf>, f64, f64, bool)>),
    /// A sampler slot starts from its beginning (true) or stops.
    Trigger(usize, bool),
    /// The deck's song's stems (its knobs then act), or none.
    Stems(usize, Option<Arc<Stems>>),
}

// ---- The callback's side ----

const DECLICK: u32 = 96;
/// How long a stopped deck's effects may still ring.
const TAIL_SECONDS: f32 = 3.;
/// Song frames fed to the time-stretch at a time.
const KS_FEED: usize = 256;

/// A sampler voice: a stretch of a song in memory, played at its own speed.
struct Voice {
    buf: Arc<TrackBuf>,
    start: f64,
    end: f64,
    looped: bool,
    pos: f64,
    playing: bool,
    level: f32,
}

struct DeckRt {
    buf: Option<Arc<TrackBuf>>,
    pos: f64,
    playing: bool,
    /// Play/pause fade, 0..1.
    level: f32,
    /// The old position faded out after a jump: (where, frames left).
    fade_from: Option<(f64, u32)>,
    looped: Option<(f64, f64)>,
    rolling: bool,
    slip: bool,
    censor: bool,
    shadow: f64,
    reverse: bool,
    scratch: Option<f64>,
    /// The speed heard (eased toward the platter's when scratching).
    speed: f64,
    /// (frames total, frames left, backwards).
    stop: Option<(f64, f64, bool)>,
    iso: Isolator,
    filter: Svf,
    filter_at: f32,
    fx: Vec<FxUnit>,
    /// The filter knob's effect (Color FX) and the crossfader's (Mix FX).
    color: FxUnit,
    mixfx: FxUnit,
    /// Master tempo: the song read at its own tempo into a time-stretch, which plays it faster
    /// or slower at its pitch.
    stretch: crate::stretch::Stretch,
    ks_in: Vec<f32>,
    ks_out: Vec<f32>,
    ks_at: usize,
    ks_live: bool,
    /// Frames the deck has been silent for (stopped, faded out): after a few seconds its effect
    /// tails are done and it costs nothing more.
    silent_for: u64,
    eq: [f32; 3],
    iso_mix: f32,
    vol: f32,
    gain: f32,
    buf_l: Vec<[f32; 2]>,
    stems: Option<Arc<Stems>>,
    /// The stems' levels, eased toward the knobs block by block.
    stem_g: [f32; 4],
}

impl DeckRt {
    fn new(rate: f32) -> Self {
        DeckRt {
            buf: None,
            pos: 0.,
            playing: false,
            level: 0.,
            fade_from: None,
            looped: None,
            rolling: false,
            slip: false,
            censor: false,
            shadow: 0.,
            reverse: false,
            scratch: None,
            speed: 0.,
            stop: None,
            iso: Isolator::new(rate),
            filter: Svf::default(),
            filter_at: 0.,
            fx: (0..FX_SLOTS).map(|_| FxUnit::new(rate)).collect(),
            color: FxUnit::new(rate),
            mixfx: FxUnit::new(rate),
            stretch: {
                let mut s = crate::stretch::Stretch::new(rate as u32);
                s.reserve(KS_FEED * 4);
                s
            },
            ks_in: Vec::with_capacity(KS_FEED * 2),
            ks_out: Vec::with_capacity(BLOCK * 32),
            ks_at: 0,
            ks_live: false,
            silent_for: 0,
            eq: [1.; 3],
            iso_mix: 0.,
            vol: 1.,
            gain: 1.,
            buf_l: vec![[0.; 2]; BLOCK],
            stems: None,
            stem_g: [1.; 4],
        }
    }

    fn holds_slip(&self) -> bool {
        self.slip || self.rolling || self.censor
    }

    fn jump(&mut self, to: f64) {
        if self.level > 0. && !self.ks_live {
            self.fade_from = Some((self.pos, DECLICK));
        }
        self.pos = to.max(0.);
        // Under master tempo what the stretch read ahead is from the old place: dropped, so a
        // loop, a roll or a slip return is heard now and not a moment later.
        self.ks_live = false;
    }

    /// Back to where the song would be, if slip kept count.
    fn slip_back(&mut self) {
        if self.holds_slip() {
            let s = self.shadow;
            self.jump(s);
        }
    }
}

/// Everything the callback owns: the mixer proper, testable without a device.
pub struct MixRender {
    state: Arc<MixState>,
    cmds: rtrb::Consumer<Cmd>,
    trash: rtrb::Producer<Arc<TrackBuf>>,
    decks: Vec<DeckRt>,
    rate: f32,
    xf: [f32; 2],
    master: f32,
    limiter: f32,
    rec: Option<rtrb::Producer<f32>>,
    out: Vec<[f32; 2]>,
    voices: Vec<Option<Voice>>,
    master_fx: FxUnit,
    /// The last beat heard on each deck, for the master effect's timing.
    beats: [Beat; DECKS],
}

impl MixRender {
    fn new(state: Arc<MixState>, cmds: rtrb::Consumer<Cmd>, trash: rtrb::Producer<Arc<TrackBuf>>, rate: u32) -> Self {
        let r = rate as f32;
        state.out_rate.store(rate, Ordering::Relaxed);
        MixRender {
            state,
            cmds,
            trash,
            decks: (0..DECKS).map(|_| DeckRt::new(r)).collect(),
            rate: r,
            xf: [1.; 2],
            master: 1.,
            limiter: 1.,
            rec: None,
            out: vec![[0.; 2]; BLOCK],
            voices: (0..SAMPLES).map(|_| None).collect(),
            master_fx: FxUnit::new(r),
            beats: [Beat { phase: 0., frames: r * 0.5 }; DECKS],
        }
    }

    /// The device opened again at another rate: the filters follow, the songs keep their place.
    fn set_rate(&mut self, rate: u32) {
        if rate as f32 == self.rate {
            return;
        }
        self.rate = rate as f32;
        self.state.out_rate.store(rate, Ordering::Relaxed);
        for d in &mut self.decks {
            d.iso = Isolator::new(self.rate);
            d.filter_at = f32::NAN;
            // Effect lines sized for the old rate would echo at the wrong length; rebuilt here
            // (outside the callback: the stream is down while the device reopens).
            d.fx = (0..FX_SLOTS).map(|_| FxUnit::new(self.rate)).collect();
            d.color = FxUnit::new(self.rate);
            d.mixfx = FxUnit::new(self.rate);
            d.stretch = crate::stretch::Stretch::new(rate);
            d.stretch.reserve(KS_FEED * 4);
            d.ks_live = false;
        }
        self.master_fx = FxUnit::new(self.rate);
    }

    /// Hands a song buffer back to the control side to be freed there (the control side also
    /// stops its decoding when nothing else holds it: another deck or a sample may).
    fn throw(&mut self, buf: Arc<TrackBuf>) {
        // The ring has room for many; should it be full, the buffer is kept until it is not.
        if let Err(rtrb::PushError::Full(b)) = self.trash.push(buf) {
            std::mem::forget(b);
            log::warn!("deck trash full: a song buffer leaked");
        }
    }

    /// Lets go of a deck's stems: their buffers freed on the control side, never here.
    fn throw_stems(&mut self, old: Option<Arc<Stems>>) {
        if let Some(old) = old {
            match Arc::try_unwrap(old) {
                Ok(st) => {
                    self.throw(st.vocals);
                    self.throw(st.drums);
                    self.throw(st.bass);
                }
                // The control side still holds them: only a count goes down here.
                Err(shared) => drop(shared),
            }
        }
    }

    fn command(&mut self, cmd: Cmd) {
        match cmd {
            Cmd::Stems(deck, stems) => {
                let old = std::mem::replace(&mut self.decks[deck].stems, stems);
                self.throw_stems(old);
            }
            Cmd::Load { deck, buf, at } => {
                let d = &mut self.decks[deck];
                let old = d.buf.replace(buf);
                d.pos = at.max(0.);
                d.shadow = d.pos;
                d.playing = false;
                d.level = 0.;
                d.fade_from = None;
                d.looped = None;
                d.rolling = false;
                d.slip = false;
                d.censor = false;
                d.reverse = false;
                d.scratch = None;
                d.stop = None;
                d.speed = 0.;
                d.ks_live = false;
                for fx in &mut d.fx {
                    fx.clear();
                }
                self.state.decks[deck].ended.store(false, Ordering::Relaxed);
                self.state.decks[deck].loads.fetch_add(1, Ordering::Release);
                if let Some(old) = old {
                    self.throw(old);
                }
                let st = self.decks[deck].stems.take();
                self.throw_stems(st);
            }
            Cmd::Eject(deck) => {
                let d = &mut self.decks[deck];
                d.playing = false;
                d.level = 0.;
                d.pos = 0.;
                d.shadow = 0.;
                d.looped = None;
                d.rolling = false;
                d.slip = false;
                d.censor = false;
                d.reverse = false;
                d.scratch = None;
                d.stop = None;
                d.fade_from = None;
                if let Some(old) = d.buf.take() {
                    self.throw(old);
                }
                let st = self.decks[deck].stems.take();
                self.throw_stems(st);
                self.state.decks[deck].loads.fetch_add(1, Ordering::Release);
            }
            Cmd::Play(deck, on) => {
                let d = &mut self.decks[deck];
                if on && !d.playing {
                    d.shadow = d.pos;
                    d.stop = None;
                    self.state.decks[deck].ended.store(false, Ordering::Relaxed);
                }
                d.playing = on && d.buf.is_some();
            }
            Cmd::Jump(deck, to) => {
                let d = &mut self.decks[deck];
                d.jump(to);
                d.ks_live = false;
                // A jump by hand (a hot cue, a click on the song) moves the timeline slip keeps
                // too; only a roll, a censor or a scratch going on still returns.
                if !(d.rolling || d.censor || d.scratch.is_some()) {
                    d.shadow = d.pos;
                }
                self.state.decks[deck].ended.store(false, Ordering::Relaxed);
            }
            Cmd::Nudge(deck, by) => {
                let d = &mut self.decks[deck];
                d.pos = (d.pos + by).max(0.);
                d.shadow = (d.shadow + by).max(0.);
            }
            Cmd::Loop(deck, span) => {
                let d = &mut self.decks[deck];
                let was = d.looped.is_some();
                d.looped = span.filter(|(a, b)| b > a);
                d.rolling = false;
                if let Some((a, b)) = d.looped {
                    if d.pos >= b || d.pos < a {
                        d.jump(a);
                    }
                } else if was {
                    d.slip_back();
                }
            }
            Cmd::Roll(deck, span) => {
                let d = &mut self.decks[deck];
                match span.filter(|(a, b)| b > a) {
                    Some((a, b)) => {
                        if !d.holds_slip() {
                            d.shadow = d.pos;
                        }
                        d.looped = Some((a, b));
                        d.rolling = true;
                        if d.pos >= b || d.pos < a {
                            d.jump(a);
                        }
                    }
                    None => {
                        if d.rolling {
                            d.slip_back();
                            d.rolling = false;
                            d.looped = None;
                        }
                    }
                }
            }
            Cmd::Slip(deck, on) => {
                let d = &mut self.decks[deck];
                if on && !d.holds_slip() {
                    d.shadow = d.pos;
                }
                // Letting go of slip mid-loop leaves the deck where it is, the loop going on.
                d.slip = on;
            }
            Cmd::Reverse(deck, on) => {
                let d = &mut self.decks[deck];
                if d.reverse && !on {
                    d.reverse = false;
                    d.slip_back();
                } else {
                    if on && !d.holds_slip() {
                        d.shadow = d.pos;
                    }
                    d.reverse = on;
                }
            }
            Cmd::Censor(deck, on) => {
                let d = &mut self.decks[deck];
                if on {
                    if !d.holds_slip() {
                        d.shadow = d.pos;
                    }
                    d.censor = true;
                    d.reverse = true;
                } else if d.censor {
                    d.reverse = false;
                    d.slip_back();
                    d.censor = false;
                }
            }
            Cmd::Scratch(deck, speed) => {
                let d = &mut self.decks[deck];
                match speed {
                    Some(s) => {
                        if d.scratch.is_none() && !d.holds_slip() {
                            d.shadow = d.pos;
                        }
                        d.scratch = Some(s);
                    }
                    None => {
                        if d.scratch.take().is_some() {
                            d.slip_back();
                        }
                    }
                }
            }
            Cmd::Brake(deck, secs) => {
                let d = &mut self.decks[deck];
                if d.playing {
                    let n = (secs.max(0.02) * self.rate as f64).max(1.);
                    d.stop = Some((n, n, false));
                }
            }
            Cmd::Backspin(deck, secs) => {
                let d = &mut self.decks[deck];
                if d.playing {
                    let n = (secs.max(0.05) * self.rate as f64).max(1.);
                    d.stop = Some((n, n, true));
                }
            }
            Cmd::Sample(slot, sample) => {
                if slot < SAMPLES {
                    let old = std::mem::replace(
                        &mut self.voices[slot],
                        sample.map(|(buf, start, end, looped)| Voice { buf, start, end: end.max(start + 1.), looped, pos: start, playing: false, level: 0. }),
                    );
                    if let Some(v) = old {
                        self.throw(v.buf);
                    }
                }
            }
            Cmd::Trigger(slot, on) => {
                if let Some(Some(v)) = self.voices.get_mut(slot) {
                    if on {
                        v.pos = v.start;
                    }
                    v.playing = on;
                }
            }
            Cmd::Record(p) => {
                // The writer keeps its end of the ring; dropping ours only marks it abandoned.
                self.rec = p;
                self.state.recording.store(self.rec.is_some(), Ordering::Relaxed);
            }
        }
    }

    /// Renders [frames] stereo frames into `self.out`.
    fn render_block(&mut self, frames: usize) {
        while let Ok(cmd) = self.cmds.pop() {
            self.command(cmd);
        }
        let rate = self.rate;
        let curve = Curve::from_u8(self.state.curve.load(Ordering::Relaxed));
        let mut x = self.state.xfader.get();
        if self.state.hamster.load(Ordering::Relaxed) {
            x = 1. - x;
        }
        let xf_target = curve.levels(x);
        let x = x.clamp(0., 1.);
        let master_target = self.state.master.get().clamp(0., 2.);
        self.out[..frames].iter_mut().for_each(|f| *f = [0.; 2]);
        let mut busy = self.rec.is_some();

        for (n, d) in self.decks.iter_mut().enumerate() {
            let sh = &self.state.decks[n];
            let Some(buf) = d.buf.clone() else {
                sh.vu.set(0.);
                sh.playing.store(false, Ordering::Relaxed);
                continue;
            };
            let ready = buf.ready();
            let done = buf.is_done();
            let end = if done { ready as f64 } else { f64::INFINITY };
            let base = buf.rate as f64 / rate as f64;
            let tempo = (sh.pitch.get() as f64).clamp(0., 4.) * (sh.bend.get() as f64).clamp(0., 4.);
            let ease = 1. / (0.004 * rate) as f64;
            let fade_step = 1. / (0.004 * rate);
            let out = &mut d.buf_l[..frames];
            // The stem knobs, eased (about 30 ms) so a part never clicks in or out.
            for k in 0..4 {
                let t = sh.stems[k].get().clamp(0., 1.5);
                d.stem_g[k] += (t - d.stem_g[k]) * 0.35;
                if (d.stem_g[k] - t).abs() < 1e-3 {
                    d.stem_g[k] = t;
                }
            }
            let stems = d.stems.clone();
            let sg = d.stem_g;

            // Master tempo: while plainly playing (no hand on the platter, no reverse or brake).
            let keylock = sh.keylock.load(Ordering::Relaxed) && d.playing && d.scratch.is_none() && !d.reverse && d.stop.is_none() && tempo > 0.05;
            if keylock != d.ks_live || !keylock {
                if d.ks_live && !keylock {
                    // Leaving master tempo (a pause, a hand on the platter, MT off): the plain
                    // path goes on from what was heard, not from what the stretch had read ahead.
                    let lag = (d.stretch.latency_frames() as f64 + ((d.ks_out.len() - d.ks_at) / 2) as f64) * d.speed.abs() * base;
                    d.pos = (d.pos - lag).max(0.);
                }
                if keylock {
                    d.stretch.reset();
                    d.stretch.engage();
                }
                d.ks_out.clear();
                d.ks_at = 0;
                d.ks_live = keylock;
            }
            if keylock {
                d.stretch.glide_to(tempo);
                d.speed = tempo;
                let mut held = false;
                while (d.ks_out.len() - d.ks_at) / 2 < frames && !held {
                    d.ks_in.clear();
                    for _ in 0..KS_FEED {
                        let s = read(&buf, stems.as_deref(), sg, d.pos, ready);
                        d.ks_in.push(s[0]);
                        d.ks_in.push(s[1]);
                        let mut p = d.pos + base;
                        if let Some((a, b)) = d.looped {
                            if p >= b && d.pos < b + 1. {
                                p -= b - a;
                            }
                        }
                        if !done && p >= ready as f64 - 2. {
                            held = true;
                            break;
                        }
                        if p >= end {
                            p = end;
                            d.pos = p;
                            held = true;
                            if d.playing {
                                d.playing = false;
                                sh.ended.store(true, Ordering::Relaxed);
                            }
                            break;
                        }
                        d.pos = p;
                    }
                    if d.ks_in.is_empty() {
                        break;
                    }
                    let input = std::mem::take(&mut d.ks_in);
                    d.stretch.process(&input, &mut d.ks_out);
                    d.ks_in = input;
                }
                for f in out.iter_mut() {
                    d.level = if d.playing { (d.level + fade_step).min(1.) } else { (d.level - fade_step).max(0.) };
                    let s = if d.ks_at + 1 < d.ks_out.len() {
                        let v = [d.ks_out[d.ks_at], d.ks_out[d.ks_at + 1]];
                        d.ks_at += 2;
                        v
                    } else {
                        [0.; 2]
                    };
                    *f = [s[0] * d.level, s[1] * d.level];
                    if d.slip || d.rolling || d.censor {
                        d.shadow += tempo * base;
                    }
                }
                // What was heard is behind what was read by what the stretch still holds.
                if d.ks_at > d.ks_out.len() / 2 {
                    d.ks_out.drain(..d.ks_at);
                    d.ks_at = 0;
                }
            }

            for f in out.iter_mut().filter(|_| !keylock) {
                // The speed: the platter's while held, else the tempo (braking, spinning back).
                let mut target = if let Some(s) = d.scratch {
                    s
                } else if d.playing {
                    let mut s = tempo;
                    if let Some((total, left, back)) = d.stop.as_mut() {
                        let k = *left / *total;
                        s = if *back { -3. * k } else { tempo * k };
                        *left -= 1.;
                        if *left <= 0. {
                            d.stop = None;
                            d.playing = false;
                            s = 0.;
                        }
                    }
                    s
                } else {
                    0.
                };
                if d.reverse && d.scratch.is_none() {
                    target = -target;
                }
                // Scratches follow the hand smoothly; play and pause start and stop at once.
                d.speed = if d.scratch.is_some() { d.speed + (target - d.speed) * ease.min(1.) * 4. } else { target };
                let audible = d.playing || d.scratch.is_some();
                d.level = if audible { (d.level + fade_step).min(1.) } else { (d.level - fade_step).max(0.) };
                if d.level <= 0. && !audible {
                    *f = [0.; 2];
                    continue;
                }
                let mut s = read(&buf, stems.as_deref(), sg, d.pos, ready);
                if let Some((old, left)) = d.fade_from.as_mut() {
                    let o = read(&buf, stems.as_deref(), sg, *old, ready);
                    let k = *left as f32 / DECLICK as f32;
                    s = [s[0] * (1. - k) + o[0] * k, s[1] * (1. - k) + o[1] * k];
                    *old += d.speed * base;
                    *left -= 1;
                    if *left == 0 {
                        d.fade_from = None;
                    }
                }
                *f = [s[0] * d.level, s[1] * d.level];

                let step = d.speed * base;
                if (d.slip || d.rolling || d.censor) && d.playing {
                    d.shadow += tempo * base;
                }
                let mut p = d.pos + step;
                if let Some((a, b)) = d.looped {
                    let len = b - a;
                    if step > 0. && p >= b && d.pos < b + 1. {
                        d.fade_from = Some((p, DECLICK));
                        p -= len;
                    } else if step < 0. && p < a && d.pos >= a - 1. {
                        d.fade_from = Some((p, DECLICK));
                        p += len;
                    }
                }
                // Past what is decoded (a stream still coming in), the deck waits there.
                if !done && step > 0. && p >= ready as f64 - 2. {
                    p = d.pos;
                }
                if p >= end {
                    p = end;
                    if d.playing && d.scratch.is_none() && !d.reverse {
                        d.playing = false;
                        sh.ended.store(true, Ordering::Relaxed);
                    }
                } else if p < 0. {
                    p = 0.;
                    // Played backwards to the start: the deck stops there (a hand may go on).
                    if d.playing && d.scratch.is_none() && d.stop.is_none() && !d.censor {
                        d.playing = false;
                    }
                }
                d.pos = p;
            }

            // A deck silent for a few seconds (its effect tails done) costs nothing more.
            if d.level <= 0. && !d.playing && d.scratch.is_none() {
                d.silent_for += frames as u64;
            } else {
                d.silent_for = 0;
            }
            let quiet = d.silent_for > (TAIL_SECONDS * rate) as u64;
            if quiet {
                d.iso = Isolator::new(rate);
                d.filter.clear();
                sh.vu.set(0.);
                sh.position.set(d.pos);
                sh.shadow.set(d.pos);
                sh.playing.store(false, Ordering::Relaxed);
                sh.looping.store(d.looped.is_some(), Ordering::Relaxed);
                continue;
            }
            busy = true;

            // Gain, EQ, filter, effects.
            let gain_t = sh.gain.get().clamp(0., 8.);
            let eq_t = [eq_gain(sh.eq[0].get()), eq_gain(sh.eq[1].get()), eq_gain(sh.eq[2].get())];
            // At unity the song passes untouched (a crossover shifts phase); the bands always run,
            // and the deck crosses over to them in 10 ms when a knob leaves the middle.
            let flat = eq_t.iter().all(|g| (*g - 1.).abs() < 1e-4);
            let iso_t = if flat { 0. } else { 1. };
            let iso_step = 1. / (0.01 * rate);
            let inv = 1. / frames as f32;
            let (g0, e0) = (d.gain, d.eq);
            for (i, f) in out.iter_mut().enumerate() {
                let t = (i + 1) as f32 * inv;
                let g = g0 + (gain_t - g0) * t;
                let e = [e0[0] + (eq_t[0] - e0[0]) * t, e0[1] + (eq_t[1] - e0[1]) * t, e0[2] + (eq_t[2] - e0[2]) * t];
                d.iso_mix = if d.iso_mix < iso_t { (d.iso_mix + iso_step).min(1.) } else { (d.iso_mix - iso_step).max(0.) };
                for (c, s) in f.iter_mut().enumerate() {
                    let x = *s * g;
                    let y = d.iso.run(x, c, e);
                    *s = x + (y - x) * d.iso_mix;
                }
            }
            d.gain = gain_t;
            d.eq = eq_t;

            let fl = sh.filter.get().clamp(-1., 1.);
            let color = sh.color.load(Ordering::Relaxed);
            if color != 0 {
                d.filter.clear();
                d.filter_at = 0.;
            }
            if color == 0 && fl.abs() > 0.02 {
                if (fl - d.filter_at).abs() > 1e-4 || d.filter_at.is_nan() {
                    let freq = if fl < 0. { 20_000. * (40f32 / 20_000.).powf(-fl) } else { 20. * (8_000f32 / 20.).powf(fl) };
                    d.filter.set(freq, 0.7 + 0.5 * fl.abs(), rate);
                    d.filter_at = fl;
                }
                for f in out.iter_mut() {
                    for (c, s) in f.iter_mut().enumerate() {
                        let (lo, _, hi) = d.filter.run(*s, c);
                        *s = if fl < 0. { lo } else { hi };
                    }
                }
            } else if d.filter_at != 0. {
                d.filter.clear();
                d.filter_at = 0.;
            }

            let bf = sh.beat_frames.get();
            let beat_frames_song = if bf > 1. { bf } else { buf.rate as f64 * 0.5 };
            let speed = d.speed.abs().max(0.05);
            let beat = Beat {
                phase: (((d.pos - sh.first_beat.get()) / beat_frames_song).rem_euclid(1.)) as f32,
                frames: (beat_frames_song / (base * speed)) as f32,
            };
            for (slot, fx) in d.fx.iter_mut().enumerate() {
                let kind = sh.fx_kind[slot].load(Ordering::Relaxed);
                fx.run(out, kind, sh.fx_amount[slot].get(), beat, rate);
            }
            // The filter knob as an effect: its distance from the middle is the amount.
            let amount = if fl.abs() > 0.02 { fl.abs() } else { 0. };
            d.color.run(out, if color != 0 && amount > 0. { color } else { 0 }, amount, beat, rate);
            // The crossfader's effect, on the deck it moves away from.
            let assign = sh.assign.load(Ordering::Relaxed);
            let away = match assign {
                1 => x,
                2 => 1. - x,
                _ => 0.,
            };
            let mix = self.state.mix_fx.load(Ordering::Relaxed);
            let amount = if away > 0.02 && away < 0.98 { away } else { 0. };
            d.mixfx.run(out, if mix != 0 && amount > 0. { mix } else { 0 }, amount, beat, rate);
            self.beats[n] = beat;

            // Channel fader (an audio taper) and crossfader, into the master.
            let vol_t = {
                let v = sh.volume.get().clamp(0., 1.);
                v * v
            };
            let side_target = match sh.assign.load(Ordering::Relaxed) {
                1 => xf_target[0],
                2 => xf_target[1],
                _ => 1.,
            };
            let (v0, x0) = (d.vol, self.xf[n]);
            let mut peak = 0f32;
            for (i, f) in out.iter().enumerate() {
                let t = (i + 1) as f32 * inv;
                let v = v0 + (vol_t - v0) * t;
                let xg = x0 + (side_target - x0) * t;
                let k = v * xg;
                let m = &mut self.out[i];
                m[0] += f[0] * k;
                m[1] += f[1] * k;
                peak = peak.max((f[0] * v).abs()).max((f[1] * v).abs());
            }
            d.vol = vol_t;
            self.xf[n] = side_target;
            sh.vu.set(peak);
            // Under master tempo the playhead heard is behind the one read by what the stretch holds.
            let lag = if d.ks_live {
                (d.stretch.latency_frames() as f64 + ((d.ks_out.len() - d.ks_at) / 2) as f64) * d.speed * base
            } else {
                0.
            };
            sh.position.set((d.pos - lag).max(0.));
            sh.shadow.set(if d.holds_slip() { d.shadow } else { d.pos });
            sh.playing.store(d.playing, Ordering::Relaxed);
            sh.looping.store(d.looped.is_some(), Ordering::Relaxed);
        }
        // Quiet decks keep their crossfader level where it was told.
        for (n, sh) in self.state.decks.iter().enumerate() {
            if self.decks[n].buf.is_none() || (self.decks[n].level <= 0. && !self.decks[n].playing) {
                self.xf[n] = match sh.assign.load(Ordering::Relaxed) {
                    1 => xf_target[0],
                    2 => xf_target[1],
                    _ => 1.,
                };
            }
        }

        // The sampler: its voices into the mix at the sampler's volume.
        let svol = self.state.sampler_volume.get().clamp(0., 1.5);
        let fade = 1. / (0.003 * rate);
        for (slot, v) in self.voices.iter_mut().enumerate() {
            let Some(v) = v else {
                self.state.samples_playing[slot].store(false, Ordering::Relaxed);
                continue;
            };
            if !v.playing && v.level <= 0. {
                self.state.samples_playing[slot].store(false, Ordering::Relaxed);
                continue;
            }
            busy = true;
            let ready = v.buf.ready();
            let step = v.buf.rate as f64 / rate as f64;
            for f in self.out[..frames].iter_mut() {
                v.level = if v.playing { (v.level + fade).min(1.) } else { (v.level - fade).max(0.) };
                if v.level <= 0. {
                    break;
                }
                let s = v.buf.at(v.pos, ready);
                f[0] += s[0] * v.level * svol;
                f[1] += s[1] * v.level * svol;
                v.pos += step;
                if v.pos >= v.end {
                    if v.looped {
                        v.pos -= v.end - v.start;
                    } else {
                        v.playing = false;
                    }
                }
            }
            self.state.samples_playing[slot].store(v.playing, Ordering::Relaxed);
        }

        // The master effect, timed on the louder deck.
        let mfx = self.state.master_fx.load(Ordering::Relaxed);
        let lead = if self.state.decks[1].vu.get() > self.state.decks[0].vu.get() { 1 } else { 0 };
        let beat = self.beats[lead];
        self.master_fx.run(&mut self.out[..frames], mfx, self.state.master_fx_amount.get(), beat, rate);

        // Master volume and the limiter: it never lets a sample past 0.98.
        let m0 = self.master;
        let inv = 1. / frames as f32;
        let release = 1. / (0.15 * rate);
        let mut peak = 0f32;
        let mut lowest = 1f32;
        const CEILING: f32 = 0.98;
        for (i, f) in self.out[..frames].iter_mut().enumerate() {
            let m = m0 + (master_target - m0) * (i + 1) as f32 * inv;
            let (l, r) = (f[0] * m, f[1] * m);
            let a = l.abs().max(r.abs());
            self.limiter = (self.limiter + release).min(1.);
            if a * self.limiter > CEILING {
                self.limiter = CEILING / a;
            }
            lowest = lowest.min(self.limiter);
            *f = [l * self.limiter, r * self.limiter];
            peak = peak.max(a * self.limiter);
        }
        self.master = master_target;
        self.state.master_vu.set(peak);
        self.state.limiting.set(-20. * lowest.log10());
        if let Some(rec) = self.rec.as_mut() {
            if let Ok(mut chunk) = rec.write_chunk_uninit(frames * 2).map_err(|_| ()) {
                let (a, b) = chunk.as_mut_slices();
                let flat = self.out[..frames].as_flattened();
                for (dst, src) in a.iter_mut().chain(b.iter_mut()).zip(flat) {
                    dst.write(*src);
                }
                unsafe { chunk.commit_all() };
            }
        }
        self.state.rendered.fetch_add(frames as u64, Ordering::Relaxed);
        self.state.busy.store(busy, Ordering::Relaxed);
    }

    /// Fills interleaved output of [channels] channels.
    pub fn fill<T: SizedSample + FromSample<f32>>(&mut self, data: &mut [T], channels: usize) {
        let ch = channels.max(1);
        let total = data.len() / ch;
        let mut done = 0;
        while done < total {
            let frames = (total - done).min(BLOCK);
            self.render_block(frames);
            for (i, frame) in data[done * ch..(done + frames) * ch].chunks_exact_mut(ch).enumerate() {
                let [l, r] = self.out[i];
                if ch == 1 {
                    frame[0] = T::from_sample((l + r) * 0.5);
                } else {
                    frame[0] = T::from_sample(l);
                    frame[1] = T::from_sample(r);
                    for s in &mut frame[2..] {
                        *s = T::from_sample(0f32);
                    }
                }
            }
            done += frames;
        }
    }
}

// ---- The handle ----

enum DevMsg {
    Device(Option<String>),
    /// A command is coming: a stream paused while idle runs again.
    Wake,
    Shutdown,
}

/// The two decks and their mixer. Every method returns at once.
pub struct Mixer {
    pub state: Arc<MixState>,
    cmds: Mutex<rtrb::Producer<Cmd>>,
    trash: Mutex<rtrb::Consumer<Arc<TrackBuf>>>,
    dev: Mutex<Option<mpsc::Sender<DevMsg>>>,
    thread: Mutex<Option<JoinHandle<()>>>,
    recorder: Mutex<Option<(JoinHandle<()>, Arc<AtomicBool>)>>,
}

/// The parts a mixer is made of, before a device is opened (tests render straight from here).
fn parts(rate: u32) -> (Arc<MixState>, rtrb::Producer<Cmd>, rtrb::Consumer<Arc<TrackBuf>>, MixRender) {
    let state = Arc::new(MixState::new());
    let (cmd_tx, cmd_rx) = rtrb::RingBuffer::new(256);
    let (trash_tx, trash_rx) = rtrb::RingBuffer::new(64);
    let render = MixRender::new(state.clone(), cmd_rx, trash_tx, rate);
    (state, cmd_tx, trash_rx, render)
}

impl Mixer {
    /// Opens the output ([device] as the music player's choice, `None` the system's default) on
    /// a thread of its own, which reopens it when it fails.
    pub fn start(device: Option<String>) -> Result<Mixer, String> {
        let (state, cmds, trash, render) = parts(48_000);
        let render = Arc::new(Mutex::new(render));
        let (tx, rx) = mpsc::channel();
        let (ready_tx, ready_rx) = mpsc::channel();
        let st = state.clone();
        let thread = std::thread::Builder::new()
            .name("lfly-decks-out".into())
            .spawn(move || device_thread(device, st, render, rx, ready_tx))
            .map_err(|e| format!("decks thread: {e}"))?;
        match ready_rx.recv_timeout(Duration::from_secs(5)) {
            Ok(Ok(())) => {}
            Ok(Err(e)) => {
                let _ = thread.join();
                return Err(e);
            }
            Err(_) => log::warn!("decks: the output is slow to open"),
        }
        Ok(Mixer {
            state,
            cmds: Mutex::new(cmds),
            trash: Mutex::new(trash),
            dev: Mutex::new(Some(tx)),
            thread: Mutex::new(Some(thread)),
            recorder: Mutex::new(None),
        })
    }

    /// Decks that play to no device, in real time (test runs: they must not sound, but the decks
    /// still move as they would).
    pub fn silent() -> Mixer {
        let (state, cmds, trash, mut render) = parts(48_000);
        let (tx, rx) = mpsc::channel();
        let thread = std::thread::Builder::new()
            .name("lfly-decks-silent".into())
            .spawn(move || {
                let mut out = vec![0f32; 480 * 2];
                let started = std::time::Instant::now();
                let mut frames = 0u64;
                loop {
                    match rx.recv_timeout(Duration::from_millis(10)) {
                        Ok(DevMsg::Shutdown) | Err(mpsc::RecvTimeoutError::Disconnected) => break,
                        Ok(DevMsg::Device(_)) | Ok(DevMsg::Wake) => {}
                        Err(mpsc::RecvTimeoutError::Timeout) => {}
                    }
                    // As many frames as the clock says have passed.
                    let due = (started.elapsed().as_secs_f64() * 48_000.) as u64;
                    while frames + 480 <= due {
                        render.fill(&mut out, 2);
                        frames += 480;
                    }
                }
            })
            .ok();
        Mixer {
            state,
            cmds: Mutex::new(cmds),
            trash: Mutex::new(trash),
            dev: Mutex::new(Some(tx)),
            thread: Mutex::new(thread),
            recorder: Mutex::new(None),
        }
    }

    fn send(&self, cmd: Cmd) {
        self.collect_trash();
        if let Some(dev) = self.dev.lock().unwrap_or_else(|e| e.into_inner()).as_ref() {
            let _ = dev.send(DevMsg::Wake);
        }
        let mut tx = self.cmds.lock().unwrap_or_else(|e| e.into_inner());
        if tx.push(cmd).is_err() {
            log::warn!("decks: command ring full, a command was dropped");
        }
    }

    /// Frees the song buffers the callback let go of (here, never in the callback).
    pub fn collect_trash(&self) {
        let mut rx = self.trash.lock().unwrap_or_else(|e| e.into_inner());
        while let Ok(b) = rx.pop() {
            drop(b);
        }
    }

    pub fn set_device(&self, id: Option<String>) {
        if let Some(tx) = self.dev.lock().unwrap_or_else(|e| e.into_inner()).as_ref() {
            let _ = tx.send(DevMsg::Device(id));
        }
    }

    pub fn deck(&self, deck: usize) -> &DeckState {
        &self.state.decks[deck.min(DECKS - 1)]
    }

    pub fn load(&self, deck: usize, buf: Arc<TrackBuf>, at_frame: f64) {
        self.send(Cmd::Load { deck: deck.min(DECKS - 1), buf, at: at_frame })
    }
    pub fn eject(&self, deck: usize) {
        self.send(Cmd::Eject(deck.min(DECKS - 1)))
    }
    pub fn play(&self, deck: usize, on: bool) {
        self.send(Cmd::Play(deck.min(DECKS - 1), on))
    }
    pub fn jump(&self, deck: usize, frame: f64) {
        self.send(Cmd::Jump(deck.min(DECKS - 1), frame))
    }
    pub fn nudge(&self, deck: usize, frames: f64) {
        self.send(Cmd::Nudge(deck.min(DECKS - 1), frames))
    }
    pub fn set_loop(&self, deck: usize, span: Option<(f64, f64)>) {
        self.send(Cmd::Loop(deck.min(DECKS - 1), span))
    }
    pub fn roll(&self, deck: usize, span: Option<(f64, f64)>) {
        self.send(Cmd::Roll(deck.min(DECKS - 1), span))
    }
    pub fn slip(&self, deck: usize, on: bool) {
        self.send(Cmd::Slip(deck.min(DECKS - 1), on))
    }
    pub fn reverse(&self, deck: usize, on: bool) {
        self.send(Cmd::Reverse(deck.min(DECKS - 1), on))
    }
    pub fn censor(&self, deck: usize, on: bool) {
        self.send(Cmd::Censor(deck.min(DECKS - 1), on))
    }
    pub fn scratch(&self, deck: usize, speed: Option<f64>) {
        self.send(Cmd::Scratch(deck.min(DECKS - 1), speed))
    }
    pub fn brake(&self, deck: usize, seconds: f64) {
        self.send(Cmd::Brake(deck.min(DECKS - 1), seconds))
    }
    pub fn backspin(&self, deck: usize, seconds: f64) {
        self.send(Cmd::Backspin(deck.min(DECKS - 1), seconds))
    }
    /// Puts a stretch of [buf] (frames [start]..[end]) in sampler slot [slot], or empties it.
    pub fn set_sample(&self, slot: usize, sample: Option<(Arc<TrackBuf>, f64, f64, bool)>) {
        self.send(Cmd::Sample(slot.min(SAMPLES - 1), sample))
    }
    /// The deck's song's stems (none: the stem knobs do nothing).
    pub fn set_stems(&self, deck: usize, stems: Option<Arc<Stems>>) {
        self.send(Cmd::Stems(deck.min(DECKS - 1), stems))
    }

    /// Starts sampler slot [slot] from its beginning, or stops it.
    pub fn trigger(&self, slot: usize, on: bool) {
        self.send(Cmd::Trigger(slot.min(SAMPLES - 1), on))
    }

    /// Records the master output to a 16-bit WAV at [path] (None: stops).
    pub fn record(&self, path: Option<std::path::PathBuf>) -> Result<(), String> {
        let old = self.recorder.lock().unwrap_or_else(|e| e.into_inner()).take();
        self.send(Cmd::Record(None));
        if let Some((t, stop)) = old {
            stop.store(true, Ordering::SeqCst);
            let _ = t.join();
        }
        let Some(path) = path else { return Ok(()) };
        let rate = self.state.out_rate.load(Ordering::Relaxed);
        let (tx, rx) = rtrb::RingBuffer::new(rate as usize * 2 * 4);
        let file = std::fs::File::create(&path).map_err(|e| format!("{}: {e}", path.display()))?;
        let stop = Arc::new(AtomicBool::new(false));
        let st = stop.clone();
        let t = std::thread::Builder::new()
            .name("lfly-decks-rec".into())
            .spawn(move || write_wav(file, rate, rx, st))
            .map_err(|e| format!("recorder: {e}"))?;
        *self.recorder.lock().unwrap_or_else(|e| e.into_inner()) = Some((t, stop));
        self.send(Cmd::Record(Some(tx)));
        Ok(())
    }
}

impl Drop for Mixer {
    fn drop(&mut self) {
        let _ = self.record(None);
        if let Some(tx) = self.dev.lock().unwrap_or_else(|e| e.into_inner()).take() {
            let _ = tx.send(DevMsg::Shutdown);
        }
        if let Some(t) = self.thread.lock().unwrap_or_else(|e| e.into_inner()).take() {
            let _ = t.join();
        }
        self.collect_trash();
    }
}

/// Writes 16-bit stereo WAV from the ring until the callback lets go of it.
/// [stop] ends it too (the stream may be down and never let go): what is in the ring is written,
/// then it waits a moment for the callback to drop its end, so the ring is never freed there.
fn write_wav(file: std::fs::File, rate: u32, mut rx: rtrb::Consumer<f32>, stop: Arc<AtomicBool>) {
    use std::io::{Seek, SeekFrom, Write};
    let mut w = std::io::BufWriter::new(file);
    let header = |data: u32| {
        let mut h = Vec::with_capacity(44);
        h.extend_from_slice(b"RIFF");
        h.extend_from_slice(&(36 + data).to_le_bytes());
        h.extend_from_slice(b"WAVEfmt ");
        h.extend_from_slice(&16u32.to_le_bytes());
        h.extend_from_slice(&1u16.to_le_bytes());
        h.extend_from_slice(&2u16.to_le_bytes());
        h.extend_from_slice(&rate.to_le_bytes());
        h.extend_from_slice(&(rate * 4).to_le_bytes());
        h.extend_from_slice(&4u16.to_le_bytes());
        h.extend_from_slice(&16u16.to_le_bytes());
        h.extend_from_slice(b"data");
        h.extend_from_slice(&data.to_le_bytes());
        h
    };
    if w.write_all(&header(0)).is_err() {
        return;
    }
    let mut bytes: u32 = 0;
    loop {
        let n = rx.slots();
        if n == 0 {
            if rx.is_abandoned() {
                break;
            }
            if stop.load(Ordering::SeqCst) {
                for _ in 0..50 {
                    if rx.is_abandoned() {
                        break;
                    }
                    std::thread::sleep(Duration::from_millis(20));
                }
                break;
            }
            std::thread::sleep(Duration::from_millis(20));
            continue;
        }
        if let Ok(chunk) = rx.read_chunk(n) {
            let (a, b) = chunk.as_slices();
            for s in a.iter().chain(b) {
                if bytes < u32::MAX - 64 {
                    let _ = w.write_all(&to_i16(*s).to_le_bytes());
                    bytes += 2;
                }
            }
            chunk.commit_all();
        }
    }
    let _ = w.flush();
    if let Ok(mut f) = w.into_inner() {
        if f.seek(SeekFrom::Start(0)).is_ok() {
            let _ = f.write_all(&header(bytes));
        }
    }
}

fn find_device(host: &cpal::Host, id: Option<&str>) -> Option<cpal::Device> {
    let wanted = id.and_then(|id| {
        let parsed = id.parse::<cpal::DeviceId>().ok()?;
        host.device_by_id(&parsed).or_else(|| host.output_devices().ok()?.find(|d| d.id().map(|d| d.to_string() == id).unwrap_or(false)))
    });
    wanted.or_else(|| host.default_output_device())
}

fn open_stream(id: Option<&str>, state: &Arc<MixState>, render: &Arc<Mutex<MixRender>>) -> Result<cpal::Stream, String> {
    let host = cpal::default_host();
    let device = find_device(&host, id).ok_or("no audio output device")?;
    let supported = device.default_output_config().map_err(|e| format!("output config: {e}"))?;
    let mut config = supported.config();
    // Small buffers: a cue is heard within ~11 ms when the device allows it.
    if let cpal::SupportedBufferSize::Range { min, max } = supported.buffer_size() {
        let want = 512.clamp(*min, *max);
        config.buffer_size = cpal::BufferSize::Fixed(want);
    }
    render.lock().unwrap_or_else(|e| e.into_inner()).set_rate(config.sample_rate);
    let stream = match supported.sample_format() {
        cpal::SampleFormat::F32 => build::<f32>(&device, &config, state, render),
        cpal::SampleFormat::I16 => build::<i16>(&device, &config, state, render),
        cpal::SampleFormat::I32 => build::<i32>(&device, &config, state, render),
        cpal::SampleFormat::U16 => build::<u16>(&device, &config, state, render),
        cpal::SampleFormat::F64 => build::<f64>(&device, &config, state, render),
        other => return Err(format!("unsupported sample format {other}")),
    };
    // A fixed buffer size the backend refuses: the device's own instead.
    let stream = match stream {
        Ok(s) => s,
        Err(e) => {
            log::info!("decks: {e}; trying the device's own buffer size");
            config.buffer_size = cpal::BufferSize::Default;
            match supported.sample_format() {
                cpal::SampleFormat::F32 => build::<f32>(&device, &config, state, render),
                cpal::SampleFormat::I16 => build::<i16>(&device, &config, state, render),
                cpal::SampleFormat::I32 => build::<i32>(&device, &config, state, render),
                cpal::SampleFormat::U16 => build::<u16>(&device, &config, state, render),
                _ => build::<f64>(&device, &config, state, render),
            }?
        }
    };
    stream.play().map_err(|e| format!("output start: {e}"))?;
    state.failed.store(false, Ordering::SeqCst);
    log::info!("decks output: {} Hz, {} ch, buffer {:?}", config.sample_rate, config.channels, config.buffer_size);
    Ok(stream)
}

fn build<T: SizedSample + FromSample<f32>>(
    device: &cpal::Device,
    config: &cpal::StreamConfig,
    state: &Arc<MixState>,
    render: &Arc<Mutex<MixRender>>,
) -> Result<cpal::Stream, String> {
    let channels = config.channels as usize;
    let r = render.clone();
    let failed = state.clone();
    device
        .build_output_stream::<T, _, _>(
            *config,
            move |data: &mut [T], _| match r.try_lock() {
                // Only the device thread takes this lock, and only while the stream is down.
                Ok(mut render) => render.fill(data, channels),
                Err(_) => data.iter_mut().for_each(|s| *s = T::from_sample(0f32)),
            },
            move |err: cpal::Error| {
                use cpal::ErrorKind::*;
                match err.kind() {
                    Xrun | DeviceChanged | RealtimeDenied => log::debug!("decks output: {err}"),
                    _ => {
                        log::warn!("decks output error: {err}");
                        failed.failed.store(true, Ordering::SeqCst);
                    }
                }
            },
            None,
        )
        .map_err(|e| format!("output stream: {e}"))
}

fn device_thread(
    mut device: Option<String>,
    state: Arc<MixState>,
    render: Arc<Mutex<MixRender>>,
    rx: mpsc::Receiver<DevMsg>,
    ready: mpsc::Sender<Result<(), String>>,
) {
    let mut stream = match open_stream(device.as_deref(), &state, &render) {
        Ok(s) => {
            let _ = ready.send(Ok(()));
            Some(s)
        }
        Err(e) => {
            let _ = ready.send(Err(e));
            return;
        }
    };
    // Idle (nothing plays) for a while, the stream pauses: a small-buffer stream left running
    // keeps the whole sound server at its quantum. The next command wakes it.
    let mut paused = false;
    let mut idle_since: Option<std::time::Instant> = None;
    loop {
        match rx.recv_timeout(Duration::from_millis(250)) {
            Ok(DevMsg::Shutdown) | Err(mpsc::RecvTimeoutError::Disconnected) => break,
            Ok(DevMsg::Wake) => {
                idle_since = None;
                if paused {
                    if let Some(s) = stream.as_ref() {
                        let _ = s.play();
                    }
                    paused = false;
                }
            }
            Ok(DevMsg::Device(id)) => {
                device = id;
                drop(stream.take());
                stream = open_stream(device.as_deref(), &state, &render).map_err(|e| log::warn!("decks: {e}")).ok();
                paused = false;
                idle_since = None;
            }
            Err(mpsc::RecvTimeoutError::Timeout) => {
                if stream.is_none() || state.failed.load(Ordering::SeqCst) {
                    drop(stream.take());
                    // The chosen device gone: the default one.
                    stream = open_stream(device.as_deref(), &state, &render)
                        .or_else(|_| open_stream(None, &state, &render))
                        .map_err(|e| log::warn!("decks: {e}"))
                        .ok();
                    paused = false;
                    idle_since = None;
                } else if !paused && !state.busy.load(Ordering::Relaxed) {
                    let since = *idle_since.get_or_insert_with(std::time::Instant::now);
                    if since.elapsed() > IDLE_PAUSE {
                        if let Some(s) = stream.as_ref() {
                            let _ = s.pause();
                        }
                        paused = true;
                        log::info!("decks output paused (idle)");
                    }
                } else if state.busy.load(Ordering::Relaxed) {
                    idle_since = None;
                }
            }
        }
    }
    drop(stream);
}

#[cfg(test)]
mod tests {
    use super::*;

    const RATE: u32 = 48_000;

    fn tone(freq: f32, seconds: f32, amp: f32) -> Vec<f32> {
        let n = (RATE as f32 * seconds) as usize;
        (0..n).flat_map(|i| {
            let s = (i as f32 * freq * std::f32::consts::TAU / RATE as f32).sin() * amp;
            [s, s]
        })
        .collect()
    }

    /// A ramp: frame i holds i / len, so a sample tells where the deck read.
    fn ramp(frames: usize) -> Vec<f32> {
        (0..frames).flat_map(|i| {
            let s = i as f32 / frames as f32 * 0.9;
            [s, s]
        })
        .collect()
    }

    struct Rig {
        state: Arc<MixState>,
        tx: rtrb::Producer<Cmd>,
        render: MixRender,
    }

    impl Rig {
        fn new() -> Rig {
            let (state, tx, _trash, render) = parts(RATE);
            Rig { state, tx, render }
        }
        fn cmd(&mut self, c: Cmd) {
            assert!(self.tx.push(c).is_ok());
        }
        fn run(&mut self, frames: usize) -> Vec<f32> {
            let mut out = vec![0f32; frames * 2];
            self.render.fill(&mut out, 2);
            out
        }
        fn pos(&self, deck: usize) -> f64 {
            self.state.decks[deck].position.get()
        }
        fn rms(v: &[f32]) -> f32 {
            (v.iter().map(|s| s * s).sum::<f32>() / v.len().max(1) as f32).sqrt()
        }
    }

    #[test]
    fn a_stem_turned_down_leaves_the_rest_of_the_song() {
        // The song: a voice (440 Hz) over a bass (110 Hz); the stems split them.
        let voice = tone(440., 1., 0.3);
        let bass = tone(110., 1., 0.3);
        let song: Vec<f32> = voice.iter().zip(&bass).map(|(a, b)| a + b).collect();
        let silent = vec![0f32; song.len()];
        let mut rig = Rig::new();
        rig.cmd(Cmd::Load { deck: 0, buf: TrackBuf::from_samples(RATE, &song), at: 0. });
        rig.cmd(Cmd::Stems(0, Some(Arc::new(Stems { vocals: TrackBuf::from_samples(RATE, &voice), drums: TrackBuf::from_samples(RATE, &silent), bass: TrackBuf::from_samples(RATE, &bass) }))));
        rig.cmd(Cmd::Play(0, true));
        rig.state.decks[0].stems[0].set(0.);
        rig.run(4800);
        let out = rig.run(9600);
        // What is left correlates with the bass, not with the voice.
        let at = rig.pos(0) as usize - 9600;
        let dot = |x: &[f32]| out.iter().step_by(2).zip(x.iter().step_by(2).skip(at)).map(|(a, b)| a * b).sum::<f32>();
        let (v, b) = (dot(&voice), dot(&bass));
        assert!(b > 10. * v.abs(), "bass {b} voice {v}");
        // Back up: the whole song.
        rig.state.decks[0].stems[0].set(1.);
        rig.run(4800);
        assert!(Rig::rms(&rig.run(4800)) > 0.25);
    }

    #[test]
    fn a_deck_plays_its_song_as_recorded() {
        let mut rig = Rig::new();
        let song = tone(440., 1., 0.5);
        rig.cmd(Cmd::Load { deck: 0, buf: TrackBuf::from_samples(RATE, &song), at: 0. });
        rig.cmd(Cmd::Play(0, true));
        let out = rig.run(4800);
        // After the 4 ms start fade the output is the song (crossfader in the middle, Full curve).
        for i in 400..4800 {
            let want = (song[i * 2] * 32767.) as i16 as f32 / 32768.;
            assert!((out[i * 2] - want).abs() < 1e-4, "frame {i}: {} vs {want}", out[i * 2]);
        }
        assert_eq!(rig.pos(0), 4800.);
        // Deck B is empty: silence from it, and the crossfader to the far right silences A.
        rig.state.xfader.set(1.);
        rig.run(BLOCK);
        let out = rig.run(4800);
        assert!(Rig::rms(&out) < 1e-4);
    }

    #[test]
    fn pitch_speeds_the_playhead_and_pause_holds_it() {
        let mut rig = Rig::new();
        rig.cmd(Cmd::Load { deck: 1, buf: TrackBuf::from_samples(RATE, &tone(220., 2., 0.3)), at: 1000. });
        rig.state.decks[1].pitch.set(1.08);
        rig.cmd(Cmd::Play(1, true));
        rig.run(10_000);
        assert!((rig.pos(1) - (1000. + 10_800.)).abs() < 1., "{}", rig.pos(1));
        rig.cmd(Cmd::Play(1, false));
        rig.run(1000);
        let held = rig.pos(1);
        rig.run(5000);
        assert_eq!(rig.pos(1), held);
    }

    #[test]
    fn a_song_at_another_rate_plays_at_its_own_speed() {
        let (state, mut tx, _t, mut render) = parts(48_000);
        let _ = tx.push(Cmd::Load { deck: 0, buf: TrackBuf::from_samples(44_100, &tone(440., 1., 0.3)), at: 0. });
        let _ = tx.push(Cmd::Play(0, true));
        let mut out = vec![0f32; 48_000];
        render.fill(&mut out, 2);
        // 24 000 output frames at 48 kHz are 0.5 s: 22 050 frames of a 44.1 kHz song.
        assert!((state.decks[0].position.get() - 22_050.).abs() < 1.);
    }

    #[test]
    fn a_loop_keeps_the_playhead_inside_and_slip_returns_where_the_song_would_be() {
        let mut rig = Rig::new();
        rig.cmd(Cmd::Load { deck: 0, buf: TrackBuf::from_samples(RATE, &ramp(RATE as usize * 4)), at: 0. });
        rig.cmd(Cmd::Slip(0, true));
        rig.cmd(Cmd::Play(0, true));
        rig.cmd(Cmd::Loop(0, Some((0., 12_000.))));
        for _ in 0..10 {
            rig.run(7_000);
            assert!(rig.pos(0) < 12_000., "{}", rig.pos(0));
        }
        rig.cmd(Cmd::Loop(0, None));
        rig.run(100);
        // 70 100 frames played in all: slip puts the deck there.
        assert!((rig.pos(0) - 70_100.).abs() < 2., "{}", rig.pos(0));
    }

    #[test]
    fn a_loop_roll_returns_even_without_slip() {
        let mut rig = Rig::new();
        rig.cmd(Cmd::Load { deck: 0, buf: TrackBuf::from_samples(RATE, &ramp(RATE as usize * 4)), at: 24_000. });
        rig.cmd(Cmd::Play(0, true));
        rig.run(1000);
        rig.cmd(Cmd::Roll(0, Some((25_000., 26_000.))));
        rig.run(9_000);
        assert!(rig.pos(0) >= 25_000. && rig.pos(0) < 26_000.);
        rig.cmd(Cmd::Roll(0, None));
        rig.run(1000);
        assert!((rig.pos(0) - 35_000.).abs() < 2., "{}", rig.pos(0));
    }

    #[test]
    fn reverse_runs_back_and_brake_stops() {
        let mut rig = Rig::new();
        rig.cmd(Cmd::Load { deck: 0, buf: TrackBuf::from_samples(RATE, &tone(100., 2., 0.3)), at: 48_000. });
        rig.cmd(Cmd::Play(0, true));
        rig.cmd(Cmd::Reverse(0, true));
        rig.run(10_000);
        assert!((rig.pos(0) - 38_000.).abs() < 1.);
        rig.cmd(Cmd::Reverse(0, false));
        rig.cmd(Cmd::Brake(0, 0.5));
        rig.run(48_000);
        assert!(!rig.state.decks[0].playing.load(Ordering::Relaxed));
        // Braking over 0.5 s covers about half of what playing would.
        assert!((rig.pos(0) - (38_000. + 12_000.)).abs() < 100., "{}", rig.pos(0));
    }

    #[test]
    fn the_eq_is_transparent_at_unity_and_kills_a_band() {
        let mut rig = Rig::new();
        let mut song = tone(60., 2., 0.3);
        for (s, h) in song.iter_mut().zip(tone(6_000., 2., 0.3)) {
            *s += h;
        }
        rig.cmd(Cmd::Load { deck: 0, buf: TrackBuf::from_samples(RATE, &song), at: 0. });
        rig.cmd(Cmd::Play(0, true));
        rig.run(4800);
        let flat = rig.run(9600);
        // Kill the bass: what is left is the 6 kHz tone only.
        rig.state.decks[0].eq[0].set(0.);
        rig.run(9600);
        let killed = rig.run(9600);
        let hi = tone(6_000., 0.2, 0.3);
        assert!(Rig::rms(&flat) > 0.28);
        assert!((Rig::rms(&killed) - Rig::rms(&hi)).abs() < 0.02, "{} vs {}", Rig::rms(&killed), Rig::rms(&hi));
        // The filter to the far left (low-pass) removes the treble too.
        rig.state.decks[0].eq[0].set(0.5);
        rig.state.decks[0].filter.set(-0.6);
        rig.run(9600);
        let filtered = rig.run(9600);
        assert!(Rig::rms(&filtered) < 0.25 && Rig::rms(&filtered) > 0.18, "{}", Rig::rms(&filtered));
    }

    #[test]
    fn two_loud_decks_never_clip() {
        let mut rig = Rig::new();
        for d in 0..2 {
            rig.cmd(Cmd::Load { deck: d, buf: TrackBuf::from_samples(RATE, &tone(100., 1., 0.95)), at: 0. });
            rig.cmd(Cmd::Play(d, true));
        }
        let out = rig.run(24_000);
        assert!(out.iter().all(|s| s.abs() <= 0.981), "{}", out.iter().fold(0f32, |a, s| a.max(s.abs())));
        assert!(rig.state.limiting.get() > 3.);
    }

    #[test]
    fn crossfader_curves_meet_as_named() {
        assert_eq!(Curve::Full.levels(0.5), [1., 1.]);
        assert_eq!(Curve::Full.levels(0.), [1., 0.]);
        let s = Curve::Smooth.levels(0.5);
        assert!((s[0] * s[0] + s[1] * s[1] - 1.).abs() < 1e-5);
        assert_eq!(Curve::Cut.levels(0.9), [1., 1.]);
        assert_eq!(Curve::Scratch.levels(1.), [0., 1.]);
    }

    #[test]
    fn effects_change_the_sound_and_turn_off_cleanly() {
        for kind in FxKind::ALL {
            // Two decks alike, one with the effect: what differs is the effect.
            let mut rigs = [Rig::new(), Rig::new()];
            for rig in &mut rigs {
                rig.cmd(Cmd::Load { deck: 0, buf: TrackBuf::from_samples(RATE, &tone(440., 3., 0.3)), at: 0. });
                rig.cmd(Cmd::Play(0, true));
                rig.run(4800);
            }
            rigs[1].state.decks[0].fx_kind[0].store(kind as u8, Ordering::Relaxed);
            rigs[1].state.decks[0].fx_amount[0].set(0.8);
            let dry = rigs[0].run(24_000);
            let wet = rigs[1].run(24_000);
            assert!(wet.iter().all(|s| s.is_finite() && s.abs() < 1.), "{kind:?}");
            let diff = Rig::rms(&dry.iter().zip(&wet).map(|(a, b)| a - b).collect::<Vec<_>>());
            assert!(diff > 0.01, "{kind:?} changed nothing ({diff})");
            rigs[1].state.decks[0].fx_kind[0].store(0, Ordering::Relaxed);
            let off = rigs[1].run(4800);
            let steps = off.chunks(2).map(|f| f[0]).collect::<Vec<_>>();
            // (Distortion, bitcrush and noise make steps of their own while they fade.)
            let jagged = matches!(kind, FxKind::Distortion | FxKind::Bitcrush | FxKind::Noise);
            assert!(jagged || steps.windows(2).all(|w| (w[1] - w[0]).abs() < 0.2), "{kind:?} clicked off");
            // Off, it is the dry deck again.
            let (a, b) = (rigs[0].run(4800 + 4800), rigs[1].run(4800));
            let tail = Rig::rms(&a[9600..].iter().zip(&b).map(|(x, y)| x - y).collect::<Vec<_>>());
            assert!(tail < 1e-4, "{kind:?} left {tail}");
        }
    }

    #[test]
    fn a_jump_lands_and_the_song_ends_by_itself() {
        let mut rig = Rig::new();
        rig.cmd(Cmd::Load { deck: 0, buf: TrackBuf::from_samples(RATE, &tone(440., 1., 0.3)), at: 0. });
        rig.cmd(Cmd::Play(0, true));
        rig.run(1000);
        rig.cmd(Cmd::Jump(0, 40_000.));
        rig.run(1000);
        assert!((rig.pos(0) - 41_000.).abs() < 1.);
        rig.run(10_000);
        assert!(!rig.state.decks[0].playing.load(Ordering::Relaxed));
        assert!(rig.state.decks[0].ended.load(Ordering::Relaxed));
        assert_eq!(rig.pos(0), 48_000.);
    }

    #[test]
    fn a_deck_waits_where_the_decoding_has_got_to() {
        let mut rig = Rig::new();
        // Half a second decoded of a song still coming in.
        let samples = tone(440., 0.5, 0.3);
        let buf = TrackBuf::empty(RATE, RATE as usize * 4, ReaderCtl::detached(), RATE as usize * 4);
        let mut published = 0;
        for (slot, part) in samples.chunks(CHUNK * 2).enumerate() {
            let _ = buf.chunks[slot].set(part.iter().map(|s| to_i16(*s)).collect());
            published += part.len() / 2;
        }
        buf.ready.store(published, Ordering::Release);
        let buf = Arc::new(buf);
        rig.cmd(Cmd::Load { deck: 0, buf: buf.clone(), at: 0. });
        rig.cmd(Cmd::Play(0, true));
        rig.run(RATE as usize);
        assert!(rig.pos(0) <= published as f64 && rig.pos(0) > published as f64 - 4., "{} of {published}", rig.pos(0));
        assert!(rig.state.decks[0].playing.load(Ordering::Relaxed), "still playing: the rest is coming");
    }

    #[test]
    fn scratch_censor_nudge_and_eject() {
        let mut rig = Rig::new();
        rig.cmd(Cmd::Load { deck: 0, buf: TrackBuf::from_samples(RATE, &ramp(RATE as usize * 4)), at: 48_000. });
        // A hand on the record, pulling it back at the record's speed: the deck runs backwards.
        rig.cmd(Cmd::Scratch(0, Some(-1.)));
        rig.run(24_000);
        assert!(rig.pos(0) < 48_000. - 20_000., "{}", rig.pos(0));
        assert!(rig.pos(0) > 48_000. - 24_001.);
        rig.cmd(Cmd::Scratch(0, None));
        rig.run(100);
        let held = rig.pos(0);
        rig.run(1000);
        assert_eq!(rig.pos(0), held, "stopped deck: the hand let go, it stays");
        // Censor: backwards while held, then where the song would be.
        rig.cmd(Cmd::Jump(0, 96_000.));
        rig.cmd(Cmd::Play(0, true));
        rig.run(1000);
        rig.cmd(Cmd::Censor(0, true));
        rig.run(10_000);
        assert!(rig.pos(0) < 97_000. - 9_000.);
        rig.cmd(Cmd::Censor(0, false));
        rig.run(1000);
        assert!((rig.pos(0) - 108_000.).abs() < 2., "{}", rig.pos(0));
        // A nudge moves the playhead by that much.
        rig.cmd(Cmd::Nudge(0, 500.));
        rig.run(1000);
        assert!((rig.pos(0) - 109_500.).abs() < 2., "{}", rig.pos(0));
        // Slip on, then a new song: slip is off again.
        rig.cmd(Cmd::Slip(0, true));
        rig.cmd(Cmd::Load { deck: 0, buf: TrackBuf::from_samples(RATE, &ramp(RATE as usize)), at: 0. });
        rig.run(10);
        assert!(!rig.render.decks[0].slip);
        rig.cmd(Cmd::Eject(0));
        rig.run(100);
        assert!(rig.render.decks[0].buf.is_none());
        assert_eq!(rig.pos(0), 0.);
    }

    #[test]
    fn a_hot_cue_with_slip_on_moves_the_timeline() {
        let mut rig = Rig::new();
        rig.cmd(Cmd::Load { deck: 0, buf: TrackBuf::from_samples(RATE, &ramp(RATE as usize * 4)), at: 0. });
        rig.cmd(Cmd::Slip(0, true));
        rig.cmd(Cmd::Play(0, true));
        rig.run(1000);
        rig.cmd(Cmd::Jump(0, 100_000.));
        rig.cmd(Cmd::Loop(0, Some((100_000., 101_000.))));
        rig.run(5000);
        rig.cmd(Cmd::Loop(0, None));
        rig.run(100);
        // Back where the music would be counting from the jump, not from before it.
        assert!((rig.pos(0) - 105_100.).abs() < 2., "{}", rig.pos(0));
    }

    #[test]
    fn a_recording_is_a_wav_of_the_mix() {
        let path = std::env::temp_dir().join(format!("lfly-decks-rec-{}.wav", std::process::id()));
        let (mut tx, rx) = rtrb::RingBuffer::<f32>::new(4096);
        for i in 0..2000 {
            tx.push(if i % 2 == 0 { 0.5 } else { -0.5 }).unwrap();
        }
        drop(tx);
        write_wav(std::fs::File::create(&path).unwrap(), 48_000, rx, Arc::new(AtomicBool::new(false)));
        let bytes = std::fs::read(&path).unwrap();
        let _ = std::fs::remove_file(&path);
        assert_eq!(bytes.len(), 44 + 4000);
        assert_eq!(&bytes[..4], b"RIFF");
        assert_eq!(u32::from_le_bytes(bytes[40..44].try_into().unwrap()), 4000);
        assert_eq!(i16::from_le_bytes([bytes[44], bytes[45]]), 16383);
    }

    #[test]
    fn the_master_records_while_asked() {
        let mut rig = Rig::new();
        let (tx, mut rx) = rtrb::RingBuffer::<f32>::new(48_000);
        rig.cmd(Cmd::Load { deck: 0, buf: TrackBuf::from_samples(RATE, &tone(440., 1., 0.3)), at: 0. });
        rig.cmd(Cmd::Play(0, true));
        rig.cmd(Cmd::Record(Some(tx)));
        let out = rig.run(4800);
        assert_eq!(rx.slots(), 9600);
        let chunk = rx.read_chunk(9600).unwrap();
        let (a, _) = chunk.as_slices();
        assert_eq!(a, &out[..a.len()]);
        chunk.commit_all();
        rig.cmd(Cmd::Record(None));
        rig.run(4800);
        assert!(rx.is_abandoned());
        assert!(!rig.state.recording.load(Ordering::Relaxed));
    }

    /// Which audio files a deck opens (run by hand: LFLY_FORMAT_DIR holds one file a format).
    #[test]
    #[ignore]
    fn the_formats_a_deck_opens() {
        let dir = std::env::var("LFLY_FORMAT_DIR").expect("LFLY_FORMAT_DIR");
        let cache = Arc::new(DiskCache::new(std::env::temp_dir().join("lfly-format-cache"), 1 << 20).unwrap());
        let mut entries: Vec<_> = std::fs::read_dir(dir).unwrap().flatten().map(|e| e.path()).collect();
        entries.sort();
        for path in entries {
            let result = TrackBuf::open(&Source::file(&path), &cache).map(|b| {
                while !b.is_done() {
                    std::thread::sleep(Duration::from_millis(10));
                }
                (b.duration_ms(), b.error())
            });
            println!("FORMAT {}: {:?}", path.display(), result);
        }
    }

    /// The real output (run by hand: it opens the default device, silent).
    #[test]
    #[ignore]
    fn the_real_output_runs_and_pauses_when_idle() {
        let m = Mixer::start(None).expect("output");
        m.state.master.set(0.);
        std::thread::sleep(Duration::from_millis(1500));
        let rendered = m.state.rendered.load(Ordering::Relaxed);
        let rate = m.state.out_rate.load(Ordering::Relaxed) as u64;
        assert!(rendered > rate, "{rendered} frames in 1.5 s at {rate}");
        assert!(!m.state.failed.load(Ordering::SeqCst));
        std::thread::sleep(IDLE_PAUSE + Duration::from_secs(1));
        let a = m.state.rendered.load(Ordering::Relaxed);
        std::thread::sleep(Duration::from_millis(500));
        assert_eq!(m.state.rendered.load(Ordering::Relaxed), a, "idle: paused");
        m.play(0, false);
        std::thread::sleep(Duration::from_millis(500));
        assert!(m.state.rendered.load(Ordering::Relaxed) > a, "a command woke it");
    }

    /// The frequency of a stretch of mono-ish output, from its zero crossings.
    fn pitch_of(out: &[f32], rate: f32) -> f32 {
        let l: Vec<f32> = out.chunks(2).map(|f| f[0]).collect();
        let ups = l.windows(2).filter(|w| w[0] < 0. && w[1] >= 0.).count();
        ups as f32 / (l.len() as f32 / rate)
    }

    #[test]
    fn master_tempo_keeps_the_key_while_the_tempo_moves() {
        let mut rig = Rig::new();
        rig.cmd(Cmd::Load { deck: 0, buf: TrackBuf::from_samples(RATE, &tone(440., 6., 0.3)), at: 0. });
        rig.state.decks[0].pitch.set(1.08);
        rig.state.decks[0].keylock.store(true, Ordering::Relaxed);
        rig.cmd(Cmd::Play(0, true));
        rig.run(9_600);
        let out = rig.run(48_000);
        let hz = pitch_of(&out, RATE as f32);
        assert!((hz - 440.).abs() < 6., "keylock on: {hz} Hz");
        let p0 = rig.pos(0);
        rig.run(48_000);
        assert!((rig.pos(0) - p0 - 51_840.).abs() < 600., "1.08× tempo: {}", rig.pos(0) - p0);
        // Off: the pitch moves with the tempo, as a record does.
        rig.state.decks[0].keylock.store(false, Ordering::Relaxed);
        rig.run(4_800);
        let hz = pitch_of(&rig.run(48_000), RATE as f32);
        assert!((hz - 475.2).abs() < 6., "keylock off: {hz} Hz");
    }

    #[test]
    fn leaving_master_tempo_keeps_the_place_heard() {
        let mut rig = Rig::new();
        rig.cmd(Cmd::Load { deck: 0, buf: TrackBuf::from_samples(RATE, &ramp(RATE as usize * 6)), at: 0. });
        rig.state.decks[0].pitch.set(1.08);
        rig.state.decks[0].keylock.store(true, Ordering::Relaxed);
        rig.cmd(Cmd::Play(0, true));
        rig.run(48_000);
        let heard = rig.pos(0);
        rig.cmd(Cmd::Play(0, false));
        rig.run(BLOCK);
        assert!((rig.pos(0) - heard).abs() < 700., "paused at {} after {heard}", rig.pos(0));
        // MT off while playing: the position runs on from where it was heard.
        rig.cmd(Cmd::Play(0, true));
        rig.run(24_000);
        let heard = rig.pos(0);
        rig.state.decks[0].keylock.store(false, Ordering::Relaxed);
        rig.run(BLOCK);
        let step = rig.pos(0) - heard;
        assert!((step - BLOCK as f64 * 1.08).abs() < 700., "moved {step} in one block");
    }

    #[test]
    fn a_stopped_deck_with_an_effect_on_lets_the_output_rest() {
        let mut rig = Rig::new();
        rig.cmd(Cmd::Load { deck: 0, buf: TrackBuf::from_samples(RATE, &tone(440., 2., 0.3)), at: 0. });
        rig.state.decks[0].fx_kind[0].store(FxKind::Echo as u8, Ordering::Relaxed);
        rig.state.decks[0].fx_amount[0].set(0.8);
        rig.state.master_fx.store(FxKind::Reverb as u8, Ordering::Relaxed);
        rig.cmd(Cmd::Play(0, true));
        rig.run(9_600);
        assert!(rig.state.busy.load(Ordering::Relaxed));
        rig.cmd(Cmd::Play(0, false));
        rig.run(4_800);
        assert!(rig.state.busy.load(Ordering::Relaxed), "the echo still rings");
        rig.run((TAIL_SECONDS * RATE as f32) as usize + 4_800);
        assert!(!rig.state.busy.load(Ordering::Relaxed), "then the output may rest");
    }

    #[test]
    fn a_slice_is_a_small_copy() {
        let song = TrackBuf::from_samples(RATE, &ramp(RATE as usize * 4));
        let s = song.slice(48_000., 72_000.);
        assert_eq!(s.ready(), 24_000);
        assert_eq!(s.frame(0, s.ready()), song.frame(48_000, song.ready()));
        assert_eq!(s.frame(23_999, s.ready()), song.frame(71_999, song.ready()));
    }

    #[test]
    fn the_sampler_plays_a_stretch_once_or_looping() {
        let mut rig = Rig::new();
        let buf = TrackBuf::from_samples(RATE, &tone(440., 1., 0.3));
        rig.cmd(Cmd::Sample(0, Some((buf.clone(), 0., 4_800., false))));
        rig.cmd(Cmd::Sample(1, Some((buf, 0., 4_800., true))));
        rig.cmd(Cmd::Trigger(0, true));
        let out = rig.run(2_400);
        assert!(Rig::rms(&out) > 0.1, "the one-shot sounds");
        rig.run(4_800);
        assert!(!rig.state.samples_playing[0].load(Ordering::Relaxed), "the one-shot ended");
        assert!(Rig::rms(&rig.run(2_400)) < 1e-4);
        rig.cmd(Cmd::Trigger(1, true));
        rig.run(24_000);
        assert!(rig.state.samples_playing[1].load(Ordering::Relaxed), "the loop goes on");
        rig.cmd(Cmd::Trigger(1, false));
        rig.run(960);
        assert!(Rig::rms(&rig.run(960)) < 1e-4);
    }

    #[test]
    fn color_fx_mix_fx_and_the_crossfader_sides() {
        // The filter knob as an echo: the deck sounds different from the plain one.
        let mut rigs = [Rig::new(), Rig::new()];
        for rig in &mut rigs {
            rig.cmd(Cmd::Load { deck: 0, buf: TrackBuf::from_samples(RATE, &tone(440., 2., 0.3)), at: 0. });
            rig.cmd(Cmd::Play(0, true));
        }
        rigs[1].state.decks[0].color.store(FxKind::Echo as u8, Ordering::Relaxed);
        rigs[1].state.decks[0].filter.set(0.7);
        rigs[0].run(24_000);
        rigs[1].run(24_000);
        let (a, b) = (rigs[0].run(9_600), rigs[1].run(9_600));
        let diff = Rig::rms(&a.iter().zip(&b).map(|(x, y)| x - y).collect::<Vec<_>>());
        assert!(diff > 0.01, "{diff}");
        // Mix FX: the crossfader three quarters over to B puts A through the high-pass.
        let mut rig = Rig::new();
        rig.cmd(Cmd::Load { deck: 0, buf: TrackBuf::from_samples(RATE, &tone(60., 2., 0.3)), at: 0. });
        rig.cmd(Cmd::Play(0, true));
        rig.state.mix_fx.store(FxKind::HighPass as u8, Ordering::Relaxed);
        rig.state.xfader.set(0.4);
        rig.run(9_600);
        let near = Rig::rms(&rig.run(4_800));
        rig.state.xfader.set(0.0);
        rig.run(9_600);
        let full = Rig::rms(&rig.run(4_800));
        assert!(near < full * 0.6, "{near} vs {full}");
        // A deck on no side is heard whatever the crossfader says.
        rig.state.decks[0].assign.store(0, Ordering::Relaxed);
        rig.state.mix_fx.store(0, Ordering::Relaxed);
        rig.state.xfader.set(1.);
        rig.run(9_600);
        assert!(Rig::rms(&rig.run(4_800)) > 0.15);
    }

    #[test]
    fn peaks_and_mono_cover_the_song() {
        let buf = TrackBuf::from_samples(RATE, &tone(50., 2., 0.5));
        let p = buf.peaks(100);
        assert_eq!(p.len(), 200);
        assert!(p.iter().skip(2).all(|c| c[0] > 120 && c[1] > 150), "{:?}", &p[..4]);
        let m = buf.mono(22_050);
        assert!((m.len() as i64 - 44_100).abs() < 64, "{}", m.len());
        assert_eq!(buf.duration_ms(), 2000);
    }
}
