//! How the lyrics move (Tauri: lyrics/motion.ts, ported line for line): every syllable lights
//! up with a soft edge sweeping across it, long notes glow while they are held, a line without
//! word timings fades in word after word, a finished line fades back, and when the song moves on
//! the lines glide up one after the other instead of as one block. No drawing here: lyrics.rs
//! paints what these say. Times are in ms, as f64 like the JavaScript numbers.

use lfly_core::pages::LyricLine;

/// One piece of a line that lights up on its own: a syllable or word, or the space between.
#[derive(Clone, Debug, PartialEq)]
pub struct Token {
    pub text: String,
    pub start: f64,
    pub dur: f64,
    pub space: bool,
}

/// A run of tokens that must not wrap apart (the syllables of one word).
#[derive(Clone, Debug, PartialEq)]
pub struct Group {
    pub tokens: Vec<Token>,
    pub space: bool,
}

#[derive(Clone, Debug)]
pub struct LineModel {
    pub start: f64,
    pub end: f64,
    /// When the line lets go of its light: the next line or its own end, whichever is later.
    pub exit: f64,
    #[allow(dead_code)]
    pub text: String,
    pub groups: Vec<Group>,
    pub bg: Option<String>,
    /// Lines without words but with a pause in the song.
    pub instrumental: bool,
    #[allow(dead_code)]
    pub timed: bool,
}

const STAGGER_MS: f64 = 50.;

/// CSS cubic-bezier(x1, y1, x2, y2) as a function of progress: Newton steps, then bisection
/// when the slope is too flat to trust.
#[derive(Clone, Copy)]
pub struct CubicBezier {
    ax: f64,
    bx: f64,
    cx: f64,
    ay: f64,
    by: f64,
    cy: f64,
}

impl CubicBezier {
    pub const fn new(x1: f64, y1: f64, x2: f64, y2: f64) -> Self {
        let cx = 3. * x1;
        let bx = 3. * (x2 - x1) - cx;
        let ax = 1. - cx - bx;
        let cy = 3. * y1;
        let by = 3. * (y2 - y1) - cy;
        let ay = 1. - cy - by;
        Self { ax, bx, cx, ay, by, cy }
    }

    fn x(&self, t: f64) -> f64 {
        ((self.ax * t + self.bx) * t + self.cx) * t
    }

    fn y(&self, t: f64) -> f64 {
        ((self.ay * t + self.by) * t + self.cy) * t
    }

    fn dx(&self, t: f64) -> f64 {
        (3. * self.ax * t + 2. * self.bx) * t + self.cx
    }

    pub fn at(&self, p: f64) -> f64 {
        if p <= 0. {
            return 0.;
        }
        if p >= 1. {
            return 1.;
        }
        let mut t = p;
        for _ in 0..6 {
            let e = self.x(t) - p;
            let d = self.dx(t);
            if e.abs() < 1e-5 {
                return self.y(t);
            }
            if d.abs() < 1e-6 {
                break;
            }
            t -= e / d;
        }
        let (mut lo, mut hi) = (0., 1.);
        t = p;
        for _ in 0..24 {
            if self.x(t) < p {
                lo = t;
            } else {
                hi = t;
            }
            t = (lo + hi) / 2.;
        }
        self.y(t)
    }
}

fn clamp01(v: f64) -> f64 {
    v.clamp(0., 1.)
}

/// JavaScript's Math.round: halves go up.
fn js_round(v: f64) -> f64 {
    (v + 0.5).floor()
}

/// JavaScript's String.length: UTF-16 units.
fn js_len(s: &str) -> usize {
    s.encode_utf16().count()
}

/// Svelte's cubicOut.
pub const CUBIC_OUT: CubicBezier = CubicBezier::new(0.215, 0.61, 0.355, 1.);
const EASE: CubicBezier = CubicBezier::new(0.25, 0.1, 0.25, 1.);
/// Slow out, quick through the middle, soft landing: the glide of a line.
pub const GLIDE_EASE: CubicBezier = CubicBezier::new(0.86, 0., 0.2, 1.);

/// `text.match(/\s+|\S+/gu)`: runs of spaces and runs of the rest.
fn chunks(text: &str) -> Vec<&str> {
    let mut out = Vec::new();
    let mut from = 0;
    let mut kind: Option<bool> = None;
    for (i, c) in text.char_indices() {
        let space = c.is_whitespace();
        if kind.is_some_and(|k| k != space) {
            out.push(&text[from..i]);
            from = i;
        }
        kind = Some(space);
    }
    if from < text.len() {
        out.push(&text[from..]);
    }
    out
}

fn is_space(c: &str) -> bool {
    !c.is_empty() && c.chars().all(char::is_whitespace)
}

/// A timed word cut at its spaces, its time shared by letters.
fn split(text: &str, start: f64, dur: f64) -> Vec<Token> {
    let parts = chunks(text);
    let letters = parts.iter().filter(|c| !is_space(c)).map(|c| js_len(c)).sum::<usize>().max(1) as f64;
    let mut seen = 0.;
    parts
        .into_iter()
        .map(|c| {
            if is_space(c) {
                return Token { text: c.into(), start: 0., dur: 0., space: true };
            }
            let from = start + js_round(dur * seen / letters);
            seen += js_len(c) as f64;
            Token { text: c.into(), start: from, dur: start + js_round(dur * seen / letters) - from, space: false }
        })
        .collect()
}

fn group(tokens: Vec<Token>) -> Vec<Group> {
    let mut out: Vec<Group> = Vec::new();
    let mut in_word = false;
    for t in tokens {
        if t.space {
            in_word = false;
            out.push(Group { tokens: vec![t], space: true });
        } else {
            if !in_word {
                out.push(Group { tokens: Vec::new(), space: false });
                in_word = true;
            }
            out.last_mut().expect("pushed").tokens.push(t);
        }
    }
    out
}

/// [whole_lines]: Settings → Testi asks for lines instead of word by word.
pub fn build_lines(lines: &[LyricLine], duration_ms: f64, whole_lines: bool) -> Vec<LineModel> {
    lines
        .iter()
        .enumerate()
        .map(|(i, l)| {
            let ms = l.ms as f64;
            let next = lines.get(i + 1).map(|n| n.ms as f64).unwrap_or(if duration_ms > ms { duration_ms } else { ms + 6000. });
            let end = match l.end_ms {
                Some(e) if e as f64 > ms => e as f64,
                _ => next,
            };
            let words = l.words.as_ref().filter(|w| !whole_lines && !w.is_empty());
            let tokens: Vec<Token> = match words {
                Some(words) => words.iter().flat_map(|w| split(&w.text, w.ms as f64, (w.end_ms - w.ms).max(0) as f64)).collect(),
                None => {
                    // No word timings: the words show one after the other as the line starts.
                    let mut n = 0.;
                    chunks(&l.text)
                        .into_iter()
                        .map(|c| {
                            if is_space(c) {
                                Token { text: c.into(), start: 0., dur: 0., space: true }
                            } else {
                                let t = Token { text: c.into(), start: ms + n * STAGGER_MS, dur: 0., space: false };
                                n += 1.;
                                t
                            }
                        })
                        .collect()
                }
            };
            LineModel {
                start: ms,
                end,
                exit: next.max(end + 50.),
                text: l.text.clone(),
                groups: group(tokens),
                bg: l.bg.clone(),
                instrumental: l.text.trim().is_empty(),
                timed: words.is_some(),
            }
        })
        .collect()
}

// --- one token's light ---------------------------------------------------------------------

const LEAD: f64 = 0.1;
const SWEEP: f64 = 1.6;
const FROM: f64 = -0.2;
const TO: f64 = 1.4;
/// The soft edge of the sweep, as a share of the token's width.
pub const EDGE_WIDTH: f64 = 0.1;
const FADE_IN_MS: f64 = 330.;
const FADE_OUT_MS: f64 = 500.;
const LONG_MS: f64 = 1500.;
const GLOW_MIN_MS: f64 = 1200.;

/// Lit share of a token: fully lit left of `solid`, unlit right of `solid + EDGE_WIDTH`; `glow` 0..1.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Light {
    pub alpha: f64,
    pub solid: f64,
    pub glow: f64,
}

pub const DARK: Light = Light { alpha: 0., solid: FROM, glow: 0. };

pub fn light_of(t: &Token, line: &LineModel, at: f64) -> Light {
    if at < t.start || t.start > line.exit {
        return DARK;
    }
    if at >= line.exit {
        let fade = 1. - EASE.at(clamp01((at - line.exit) / FADE_OUT_MS));
        return if fade <= 0. { DARK } else { Light { alpha: fade, solid: TO, glow: 0. } };
    }
    let since = at - t.start;
    if t.dur <= 0. {
        return Light { alpha: EASE.at(clamp01(since / FADE_IN_MS)), solid: TO, glow: 0. };
    }
    let sweep_ms = t.dur * SWEEP;
    let solid = FROM + ((TO - FROM) * (since + t.dur * LEAD).min(sweep_ms)) / sweep_ms;
    let mut glow = 0.;
    if t.dur > LONG_MS {
        let glow_ms = (t.dur * 1.2).max(GLOW_MIN_MS);
        if since < glow_ms {
            glow = 1. - EASE.at(since / glow_ms);
        }
    }
    Light { alpha: 1., solid, glow }
}

/// Whether a line needs painting every frame at `at`.
pub fn is_live(line: &LineModel, at: f64) -> bool {
    at + 2000. >= line.start && at < line.exit + FADE_OUT_MS + 50.
}

// --- following the song --------------------------------------------------------------------

pub const TARGET: f64 = 0.37;
const LOOK_AHEAD_MS: f64 = 500.;
const EARLY_MS: f64 = 540.;
const ENDING_MS: f64 = 500.;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct LineBox {
    pub top: f64,
    pub height: f64,
}

pub struct Goal {
    pub top: f64,
    pub anchor: usize,
    pub active: Vec<usize>,
    pub overflows: bool,
}

/// Where the list should stand for the lines being sung at `at` (scroll top), and its anchor.
pub fn target(lines: &[LineModel], boxes: &[LineBox], at: f64, view: f64) -> Goal {
    let t = at + LOOK_AHEAD_MS;
    let mut active: Vec<usize> =
        (0..lines.len()).filter(|&i| t >= lines[i].start - EARLY_MS && (t < lines[i].exit - 50. || t < lines[i].end)).collect();
    if active.is_empty() {
        // Between lines: the last one that started, or the first.
        let last = (0..lines.len()).filter(|&i| lines[i].start <= t).last().unwrap_or(0);
        active = vec![last];
    }
    let n = active.len();
    let sung: Vec<usize> = active.iter().enumerate().filter(|&(k, &i)| t < lines[i].end - ENDING_MS || k == n - 1).map(|(_, &i)| i).collect();
    let running = sung.iter().copied().filter(|&i| lines[i].start <= t && t < lines[i].end).last();
    let started = sung.iter().copied().filter(|&i| lines[i].start <= t).last();
    let anchor = running.or(started).unwrap_or(sung[0]);
    let (first, last, a) = (boxes[sung[0]], boxes[sung[sung.len() - 1]], boxes[anchor]);
    let centre = sung.iter().map(|&i| boxes[i].top + boxes[i].height / 2.).sum::<f64>() / sung.len() as f64;
    let mut top = centre - view * TARGET;
    top = top.max(last.top + last.height - view);
    top = top.min(last.top).min(first.top);
    top = top.max(a.top + a.height - view);
    top = top.min(a.top);
    Goal { top, anchor: active[active.len() - 1], overflows: last.top + last.height - first.top > view, active }
}

/// How far the list can scroll: enough for the last line to reach the target height.
pub fn max_scroll(boxes: &[LineBox], view: f64) -> f64 {
    let Some(last) = boxes.last() else { return 0. };
    let content = last.top + last.height;
    let tail = 1. - TARGET;
    let extra = (last.top + last.height / 2. + view * tail - content).max(view - content).max(0.);
    (content + extra.ceil() - view).max(0.)
}

struct Glide {
    delta: f64,
    at: f64,
    anchor: usize,
}

/// The list's scroll: glides to where the song is, each line on its own delay. `now` is in ms.
#[derive(Default)]
pub struct Scroll {
    pub pos: f64,
    pub max: f64,
    glides: Vec<Glide>,
    wheel_to: Option<f64>,
}

impl Scroll {
    pub fn limit(&mut self, max: f64) {
        self.max = max.max(0.);
        self.pos = self.clamp(self.pos);
        self.wheel_to = self.wheel_to.map(|w| self.clamp(w));
    }

    pub fn jump(&mut self, to: f64) {
        self.pos = self.clamp(to);
        self.glides.clear();
        self.wheel_to = None;
    }

    pub fn glide(&mut self, to: f64, anchor: usize, now: f64) {
        let to = self.clamp(to);
        self.wheel_to = None;
        let delta = to - self.pos;
        if delta.abs() <= 2. {
            self.pos = to;
            return;
        }
        self.glides.push(Glide { delta, at: now, anchor });
        self.pos = to;
    }

    pub fn wheel(&mut self, dy: f64) {
        self.wheel_to = Some(self.clamp(self.wheel_to.unwrap_or(self.pos) + dy));
    }

    pub fn step(&mut self, now: f64, dt: f64) {
        self.glides.retain(|g| now - g.at < 1900.);
        let Some(to) = self.wheel_to else { return };
        let left = to - self.pos;
        if left.abs() < 0.5 {
            self.pos = to;
            self.wheel_to = None;
            return;
        }
        self.pos += left * (1. - (-dt / 70.).exp());
    }

    /// How far line `i` still lags behind the scroll (it catches up on its own delay).
    pub fn lag(&self, i: usize, now: f64) -> f64 {
        let mut off = 0.;
        for g in &self.glides {
            let d = i.abs_diff(g.anchor) as f64;
            let above = (i < g.anchor && g.delta >= 0.) || (i > g.anchor && g.delta < 0.);
            let ms = if above { 750. + d.min(6.) * 20. } else { 750. + (d + 2.).ln() * 80. + (d + 1.) * 20. };
            off += g.delta * (1. - GLIDE_EASE.at(clamp01((now - g.at) / ms)));
        }
        off
    }

    pub fn moving(&self) -> bool {
        !self.glides.is_empty() || self.wheel_to.is_some()
    }

    fn clamp(&self, v: f64) -> f64 {
        v.max(0.).min(self.max)
    }
}

pub struct Decision {
    pub top: f64,
    pub anchor: usize,
    #[allow(dead_code)]
    pub jump: bool,
}

/// Decides when the list moves: when a new line comes in, the song jumps, or a held line ends.
pub struct Follower {
    committed: Vec<usize>,
    pinned: Option<usize>,
    last: f64,
    forced: bool,
}

impl Default for Follower {
    fn default() -> Self {
        Self { committed: Vec::new(), pinned: None, last: f64::NAN, forced: true }
    }
}

impl Follower {
    pub fn reset(&mut self) {
        self.committed.clear();
        self.pinned = None;
        self.forced = true;
    }

    pub fn decide(&mut self, lines: &[LineModel], boxes: &[LineBox], at: f64, view: f64) -> Option<Decision> {
        if lines.is_empty() || boxes.len() != lines.len() {
            return None;
        }
        let t = at + LOOK_AHEAD_MS;
        let jumped = self.last.is_nan() || (at - self.last).abs() > 500.;
        self.last = at;
        let goal = target(lines, boxes, at, view);
        let entered = goal.active.iter().any(|&i| !self.committed.contains(&i) && t >= lines[i].start);
        let pinned_ended = self.pinned.is_some_and(|p| p != goal.anchor && (!goal.active.contains(&p) || t >= lines[p].end));
        if !self.forced && !jumped && !entered && !pinned_ended {
            return None;
        }
        let forced = self.forced;
        self.forced = false;
        self.pinned = goal.overflows.then_some(goal.anchor);
        self.committed = goal.active;
        Some(Decision { top: goal.top, anchor: goal.anchor, jump: forced })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use lfly_core::pages::LyricWord;

    fn line(ms: i64, text: &str) -> LyricLine {
        LyricLine { ms, end_ms: None, text: text.into(), words: None, bg: None }
    }

    #[test]
    fn bezier_matches_css() {
        // Values from the JavaScript implementation.
        assert!((EASE.at(0.5) - 0.8024033876954126).abs() < 1e-9);
        assert!((GLIDE_EASE.at(0.25) - 0.04010411796919536).abs() < 1e-9);
        assert_eq!(EASE.at(0.), 0.);
        assert_eq!(EASE.at(1.), 1.);
    }

    #[test]
    fn split_shares_time_by_utf16_letters() {
        let t = split("ciao mondo", 1000., 900.);
        assert_eq!(t.len(), 3);
        // 9 letters: "ciao" 4/9 of 900 = 400, "mondo" the rest.
        assert_eq!((t[0].start, t[0].dur), (1000., 400.));
        assert!(t[1].space);
        assert_eq!((t[2].start, t[2].dur), (1400., 500.));
        // An astral character counts two units, as in JavaScript.
        let e = split("a😀", 0., 300.);
        assert_eq!(e[0].dur, 300.);
    }

    #[test]
    fn lines_without_words_stagger() {
        let m = build_lines(&[line(1000, "uno due"), line(3000, "")], 10_000., false);
        assert_eq!(m[0].groups.len(), 3);
        assert_eq!(m[0].groups[2].tokens[0].start, 1050.);
        assert_eq!(m[0].end, 3000.);
        assert_eq!(m[0].exit, 3050.);
        assert!(m[1].instrumental);
        assert_eq!(m[1].end, 10_000.);
    }

    #[test]
    fn words_become_tokens_unless_whole_lines() {
        let l = LyricLine {
            ms: 0,
            end_ms: Some(2000),
            text: "la la".into(),
            words: Some(vec![LyricWord { ms: 0, end_ms: 500, text: "la ".into() }, LyricWord { ms: 600, end_ms: 1200, text: "la".into() }]),
            bg: None,
        };
        let m = build_lines(std::slice::from_ref(&l), 5000., false);
        assert!(m[0].timed);
        assert_eq!(m[0].groups.iter().filter(|g| !g.space).count(), 2);
        let whole = build_lines(&[l], 5000., true);
        assert!(!whole[0].timed);
    }

    #[test]
    fn light_sweeps_then_fades() {
        let m = build_lines(&[line(0, "x"), line(2000, "y")], 5000., false);
        let tok = &m[0].groups[0].tokens[0];
        assert_eq!(light_of(tok, &m[0], -1.), DARK);
        assert!((light_of(tok, &m[0], 165.).alpha - EASE.at(0.5)).abs() < 1e-9);
        assert_eq!(light_of(tok, &m[0], 1000.).alpha, 1.);
        assert!((light_of(tok, &m[0], 2300.).alpha - 0.19759661230458736).abs() < 1e-9);
        assert_eq!(light_of(tok, &m[0], 2600.), DARK);
    }

    #[test]
    fn follower_moves_on_new_lines() {
        let m = build_lines(&[line(0, "a"), line(2000, "b"), line(4000, "c")], 6000., false);
        let boxes: Vec<LineBox> = (0..3).map(|i| LineBox { top: i as f64 * 50., height: 50. }).collect();
        let mut f = Follower::default();
        // Reference: {"top":-123,"anchor":0,"jump":true} null {"top":-73,"anchor":1}, maxScroll 0.
        let d = f.decide(&m, &boxes, 0., 400.).expect("first");
        assert_eq!((d.top, d.anchor, d.jump), (-123., 0, true));
        assert!(f.decide(&m, &boxes, 100., 400.).is_none());
        let d = f.decide(&m, &boxes, 1600., 400.).expect("line b comes in");
        assert_eq!((d.top, d.anchor, d.jump), (-73., 1, false));
        assert_eq!(max_scroll(&boxes, 400.), 0.);
    }
}
