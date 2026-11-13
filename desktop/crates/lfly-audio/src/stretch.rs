//! Playback speed with the pitch kept (the phone's `PlaybackParameters(speed)`, "changes tempo,
//! keeping the pitch"): WSOLA. Frames of 32 ms are cut from the input a speed's worth apart and
//! overlapped half on half under a Hann window; each one is moved by up to ±8 ms to where it best
//! continues the one before (cross-correlation), so the waveform joins without a phase jump.
//!
//! At 1× it does nothing: the samples pass through untouched.

/// Interleaved stereo in, interleaved stereo out.
pub struct Stretch {
    speed: f64,
    /// Frame length and half of it (the output hop), in frames.
    n: usize,
    hs: usize,
    /// How far a frame may move to fit, in frames.
    tol: usize,
    window: Vec<f32>,
    /// Input not used up yet (stereo), its first frame being input frame `base`.
    input: Vec<f32>,
    /// Where the next frame is cut, nominally (in input frames from the buffer's start).
    at: f64,
    /// Where the frame before continues naturally (buffer frames), once there was one.
    natural: Option<usize>,
    /// The second half of the frame before, windowed, to overlap with the next one.
    tail: Vec<f32>,
}

impl Stretch {
    /// For [rate] Hz output.
    pub fn new(rate: u32) -> Self {
        let n = ((rate as usize * 32 / 1000) & !1).max(64);
        let hs = n / 2;
        // A periodic Hann: two of them half a frame apart add up to exactly 1.
        let window = (0..n).map(|i| 0.5 - 0.5 * (2. * std::f32::consts::PI * i as f32 / n as f32).cos()).collect();
        Stretch { speed: 1., n, hs, tol: rate as usize * 8 / 1000, window, input: Vec::new(), at: 0., natural: None, tail: vec![0.; hs * 2] }
    }

    pub fn speed(&self) -> f64 {
        self.speed
    }

    /// Changes the speed (0.25..4) and starts afresh.
    pub fn set_speed(&mut self, speed: f64) {
        self.speed = speed.clamp(0.25, 4.);
        self.reset();
    }

    /// Forgets what it holds (a seek, a new song).
    pub fn reset(&mut self) {
        self.input.clear();
        self.at = 0.;
        self.natural = None;
        self.tail.iter_mut().for_each(|s| *s = 0.);
    }

    /// Output frames still to come from the input it holds: what plays before anything given
    /// to it from now on (a gapless or mixed next song starts that much later).
    pub fn latency_frames(&self) -> u64 {
        if !self.active() {
            return 0;
        }
        let held = (self.frames() as f64 - self.at).max(0.);
        (held / self.speed) as u64 + if self.natural.is_some() { self.hs as u64 } else { 0 }
    }

    pub fn active(&self) -> bool {
        (self.speed - 1.).abs() > 1e-3
    }

    /// Takes [input], appends what it can make of it so far to [out].
    pub fn process(&mut self, input: &[f32], out: &mut Vec<f32>) {
        if !self.active() {
            out.extend_from_slice(input);
            return;
        }
        self.input.extend_from_slice(input);
        self.run(out);
    }

    /// The end of the stream: what is left, played out.
    pub fn flush(&mut self, out: &mut Vec<f32>) {
        if !self.active() {
            return;
        }
        // Enough silence for the last frames to be cut, then the tail.
        let pad = (self.n + self.tol * 2) * 2;
        let real_end = self.input.len() / 2;
        self.input.extend(std::iter::repeat_n(0., pad));
        while self.natural.map_or(self.at as usize, |n| n.min(self.at as usize)) < real_end && self.step(out) {}
        out.extend_from_slice(&self.tail);
        self.reset();
    }

    fn frames(&self) -> usize {
        self.input.len() / 2
    }

    fn run(&mut self, out: &mut Vec<f32>) {
        while self.step(out) {}
        // Drop what no frame can reach any more.
        let keep_from = (self.at as usize).saturating_sub(self.tol).min(self.natural.unwrap_or(usize::MAX));
        if keep_from > 0 {
            self.input.drain(..keep_from * 2);
            self.at -= keep_from as f64;
            self.natural = self.natural.map(|n| n - keep_from);
        }
    }

    /// One output hop, if the input reaches far enough.
    fn step(&mut self, out: &mut Vec<f32>) -> bool {
        let (n, hs, tol) = (self.n, self.hs, self.tol);
        let nominal = self.at.round() as usize;
        let need = (nominal + tol + n).max(self.natural.map_or(0, |p| p + hs));
        if need > self.frames() {
            return false;
        }
        let start = match self.natural {
            None => nominal,
            Some(nat) => self.best(nominal, nat),
        };
        // Overlap the first half with the tail; keep the second half as the next tail.
        let w = &self.window;
        for i in 0..hs {
            for c in 0..2 {
                out.push(self.tail[i * 2 + c] + self.input[(start + i) * 2 + c] * w[i]);
            }
        }
        for i in 0..hs {
            for c in 0..2 {
                self.tail[i * 2 + c] = self.input[(start + hs + i) * 2 + c] * w[hs + i];
            }
        }
        self.natural = Some(start + hs);
        self.at += hs as f64 * self.speed;
        true
    }

    /// The frame start within ±tol of [nominal] whose first half best matches the natural
    /// continuation at [nat] (mono, every 4th sample for the search, then the exact frame).
    fn best(&self, nominal: usize, nat: usize) -> usize {
        let (hs, tol) = (self.hs, self.tol);
        let mono = |f: usize| self.input[f * 2] + self.input[f * 2 + 1];
        let score = |s: usize, step: usize| -> f32 {
            let (mut xy, mut yy) = (0f32, 0f32);
            let mut i = 0;
            while i < hs {
                let (x, y) = (mono(nat + i), mono(s + i));
                xy += x * y;
                yy += y * y;
                i += step;
            }
            if yy <= 1e-9 { 0. } else { xy / yy.sqrt() }
        };
        let lo = nominal.saturating_sub(tol);
        let hi = nominal + tol;
        let (mut best, mut best_score) = (nominal, score(nominal.clamp(lo, hi), 4));
        let mut s = lo;
        while s <= hi {
            let v = score(s, 4);
            if v > best_score + 1e-6 {
                best_score = v;
                best = s;
            }
            s += 2;
        }
        // Refine around the coarse best, at full resolution.
        let (from, to) = (best.saturating_sub(2).max(lo), (best + 2).min(hi));
        let mut fine = (best, f32::MIN);
        for s in from..=to {
            let v = score(s, 1);
            if v > fine.1 {
                fine = (s, v);
            }
        }
        fine.0
    }
}

#[cfg(test)]
mod tests {
    use super::Stretch;

    fn tone(rate: u32, secs: f32, hz: f32) -> Vec<f32> {
        let n = (rate as f32 * secs) as usize;
        (0..n).flat_map(|i| {
            let v = (2. * std::f32::consts::PI * hz * i as f32 / rate as f32).sin() * 0.5;
            [v, v]
        })
        .collect()
    }

    /// Zero crossings per second of the left channel: the pitch.
    fn crossings(s: &[f32], rate: u32) -> f32 {
        let l: Vec<f32> = s.iter().step_by(2).copied().collect();
        let c = l.windows(2).filter(|w| w[0] < 0. && w[1] >= 0.).count();
        c as f32 * rate as f32 / l.len() as f32
    }

    #[test]
    fn one_x_passes_through() {
        let mut st = Stretch::new(48_000);
        let x = tone(48_000, 0.5, 440.);
        let mut out = Vec::new();
        st.process(&x, &mut out);
        st.flush(&mut out);
        assert_eq!(out, x);
    }

    #[test]
    fn keeps_the_pitch_and_changes_the_length() {
        for speed in [0.5, 0.75, 1.25, 1.5, 2.] {
            let rate = 48_000;
            let x = tone(rate, 3., 440.);
            let mut st = Stretch::new(rate);
            st.set_speed(speed);
            let mut out = Vec::new();
            for chunk in x.chunks(2048) {
                st.process(chunk, &mut out);
            }
            st.flush(&mut out);
            let want = x.len() as f64 / speed;
            let got = out.len() as f64;
            assert!((got - want).abs() / want < 0.03, "speed {speed}: {got} samples, want about {want}");
            // The middle, away from the ends.
            let mid = &out[out.len() / 4 & !1..out.len() * 3 / 4 & !1];
            let hz = crossings(mid, rate);
            assert!((hz - 440.).abs() < 6., "speed {speed}: {hz} Hz");
        }
    }

    #[test]
    fn a_steady_tone_stays_steady() {
        // WSOLA joins a sine without a jump: the loudness barely ripples.
        let rate = 48_000;
        let x = tone(rate, 2., 220.);
        let mut st = Stretch::new(rate);
        st.set_speed(1.5);
        let mut out = Vec::new();
        st.process(&x, &mut out);
        let l: Vec<f32> = out.iter().step_by(2).copied().collect();
        let block = 960;
        let rms: Vec<f32> = l.chunks(block).skip(2).take(30).map(|b| (b.iter().map(|v| v * v).sum::<f32>() / b.len() as f32).sqrt()).collect();
        let (lo, hi) = rms.iter().fold((f32::MAX, 0f32), |(a, b), &v| (a.min(v), b.max(v)));
        assert!(hi / lo < 1.15, "ripple {lo}..{hi}");
    }
}
