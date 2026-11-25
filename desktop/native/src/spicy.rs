//! The lyrics' motion after Spicy Lyrics (github.com/Spikerko/spicy-lyrics, AGPL-3.0), written
//! again from scratch: only its numbers are taken. The phone has the same in SpicyLyrics.kt.
//!
//! Each word follows its own progress with three springs: it grows (0.95 → 1.05 at 70% → 1), lifts
//! and settles, glows and fades. A word held a second or more goes letter by letter instead, each
//! letter growing more (1.175). Words not sung yet rest at 0.95.

/// A damped spring with a closed-form step (critically and under-damped), frequency in Hz.
#[derive(Clone, Copy, Debug)]
pub struct Spring {
    pub pos: f32,
    vel: f32,
    pub goal: f32,
    freq: f32,
    damping: f32,
}

impl Spring {
    pub const fn new(pos: f32, freq: f32, damping: f32) -> Self {
        Self { pos, vel: 0., goal: pos, freq, damping }
    }

    /// Moves [dt] seconds on.
    pub fn step(&mut self, dt: f32) -> f32 {
        if dt <= 0. {
            return self.pos;
        }
        let f = self.freq * std::f32::consts::TAU;
        let (o, v, d, g) = (self.pos - self.goal, self.vel, self.damping, self.goal);
        if d >= 0.999 {
            let q = (-f * dt).exp();
            let w = dt * q;
            self.pos = o * (q + w * f) + v * w + g;
            self.vel = v * (q - w * f) - o * (w * f * f);
        } else {
            let q = (-d * f * dt).exp();
            let c = (1. - d * d).sqrt();
            let (i, j) = ((dt * f * c).cos(), (dt * f * c).sin());
            let z = j / c;
            let y = j / (f * c);
            self.pos = (o * (i + z * d) + v * y) * q + g;
            self.vel = (v * (i - z * d) - o * (z * f)) * q;
        }
        if (self.pos - g).abs() < 1e-4 && self.vel.abs() < 1e-3 {
            self.pos = g;
            self.vel = 0.;
        }
        self.pos
    }

    pub fn moving(&self) -> bool {
        self.pos != self.goal || self.vel != 0.
    }
}

/// A smooth curve through (time, value) points, each span eased with a cosine.
pub struct Curve(pub &'static [(f32, f32)]);

impl Curve {
    pub fn at(&self, t: f32) -> f32 {
        let p = self.0;
        if t <= p[0].0 {
            return p[0].1;
        }
        for w in p.windows(2) {
            let ((t0, v0), (t1, v1)) = (w[0], w[1]);
            if t <= t1 {
                let x = (t - t0) / (t1 - t0);
                return v0 + (v1 - v0) * (1. - (x * std::f32::consts::PI).cos()) / 2.;
            }
        }
        p[p.len() - 1].1
    }
}

pub const SCALE: Curve = Curve(&[(0., 0.95), (0.7, 1.0505), (1., 1.)]);
pub const LETTER_SCALE: Curve = Curve(&[(0., 0.95), (0.7, 1.175), (1., 1.)]);
/// In font sizes, positive is down.
pub const LIFT: Curve = Curve(&[(0., 0.01), (0.9, -1. / 60.), (1., 0.)]);
pub const LETTER_LIFT: Curve = Curve(&[(0., 0.01), (0.9, -1. / 56.), (1., 0.)]);
pub const GLOW: Curve = Curve(&[(0., 0.), (0.15, 1.), (0.6, 1.), (1., 0.)]);

/// Where words not sung yet rest.
pub const IDLE: f32 = 0.95;
/// A word held this long (ms) goes letter by letter.
pub const LETTER_MIN_MS: f64 = 1000.;

/// One word's three springs.
#[derive(Clone, Copy, Debug)]
pub struct Word {
    pub scale: Spring,
    pub lift: Spring,
    pub glow: Spring,
}

impl Word {
    pub const fn new() -> Self {
        Self { scale: Spring::new(IDLE, 0.88, 0.64), lift: Spring::new(0.01, 1.45, 0.4), glow: Spring::new(0., 1.18, 0.56) }
    }

    /// Follows the word at [at] (ms) sung from [from] until [until], [dt] seconds after the last frame.
    pub fn follow(&mut self, from: f64, until: f64, at: f64, dt: f32) {
        let p = ((at - from) / (until - from).max(1.)).clamp(0., 1.) as f32;
        let (before, after) = (at < from, at >= until);
        let t = if before { 0. } else if after { 1. } else { p };
        self.scale.goal = SCALE.at(t);
        self.lift.goal = LIFT.at(t);
        self.glow.goal = if before || after { 0. } else { GLOW.at(p) };
        self.scale.step(dt);
        self.lift.step(dt);
        self.glow.step(dt);
    }

    pub fn moving(&self) -> bool {
        self.scale.moving() || self.lift.moving() || self.glow.moving()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn curves_hit_their_points() {
        assert_eq!(SCALE.at(0.), 0.95);
        assert!((SCALE.at(0.7) - 1.0505).abs() < 1e-6);
        assert_eq!(SCALE.at(1.), 1.);
        assert_eq!(GLOW.at(0.4), 1.);
    }

    #[test]
    fn a_spring_settles_on_its_goal() {
        let mut s = Spring::new(0., 0.88, 0.64);
        s.goal = 1.;
        for _ in 0..600 {
            s.step(1. / 60.);
        }
        assert_eq!(s.pos, 1.);
        assert!(!s.moving());
    }

    #[test]
    fn a_word_grows_past_one_then_rests_at_one() {
        let mut w = Word::new();
        let mut peak: f32 = 0.;
        let mut at = 0.;
        while at < 3000. {
            w.follow(0., 1000., at, 1. / 60.);
            peak = peak.max(w.scale.pos);
            at += 1000. / 60.;
        }
        assert!(peak > 1.02, "peak {peak}");
        assert!((w.scale.pos - 1.).abs() < 1e-3);
    }
}
