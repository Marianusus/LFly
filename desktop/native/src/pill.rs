//! SelectionPill.svelte: the phone's tab-bar pill standing up. It travels to the chosen row on a
//! spring, lifts (grows a little, turns to glass) for the journey, stretches with its speed and
//! settles back into the flat accent fill where it lands. The constants are the phone's
//! (GlassSelectionPill.kt).
//!
//! The rows report where they were drawn ([Pill::mark]) and the list its own origin
//! ([Pill::origin]); the pill is drawn under them from those, one frame later.

use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::rc::Rc;
use std::time::Instant;

use gpui_kit::*;

const TRAVEL_K: f32 = 280.;
const TRAVEL_C: f32 = 31.4;
const LIFT_K: f32 = 250.;
const LIFT_CX: f32 = 19.;
const LIFT_CY: f32 = 22.1;
const MAT_K: f32 = 1000.;
const MAT_C: f32 = 63.3;
const HANDOVER_START: f32 = 0.92;

#[derive(Clone, Copy, Default)]
struct Spring {
    x: f32,
    v: f32,
}

impl Spring {
    fn step(&mut self, target: f32, dt: f32, k: f32, c: f32) {
        let mut t = dt;
        while t > 0. {
            let d = t.min(1. / 240.);
            self.v += (-k * (self.x - target) - c * self.v) * d;
            self.x += self.v * d;
            t -= d;
        }
        if (self.x - target).abs() < 0.02 && self.v.abs() < 0.05 {
            self.x = target;
            self.v = 0.;
        }
    }
}

#[derive(Clone, Copy, PartialEq, Default)]
struct Rect {
    top: f32,
    left: f32,
    width: f32,
    height: f32,
}

#[derive(Default)]
pub struct Pill {
    y: Spring,
    h: Spring,
    l: Spring,
    w: Spring,
    lx: Spring,
    ly: Spring,
    mat: Spring,
    goal: Rect,
    from: (f32, f32),
    lifted: bool,
    lift_until: Option<Instant>,
    handover: f32,
    stretch: f32,
    stretch_x: f32,
    last: Option<Instant>,
    key: Option<String>,
    /// Frames asked for a row not drawn yet (a few, then it is not in this list).
    waiting: (Option<String>, u8),
    rows: Rc<RefCell<HashMap<String, Bounds<Pixels>>>>,
    list: Rc<Cell<Point<Pixels>>>,
}

/// How the pill is drawn: the flat fill (accent over the glass tint, a thin accent ring) and the
/// glass it turns into while it travels.
pub struct PillLook {
    pub fill: Hsla,
    pub ring: Hsla,
    pub glass: Hsla,
    pub glass_rim: Hsla,
    pub radius: f32,
}

impl Pill {
    pub fn new() -> Self {
        Self { handover: 1., ..Default::default() }
    }

    /// Put inside a row: records where the row [key] was drawn.
    pub fn mark(&self, key: impl Into<String>) -> impl IntoElement {
        let (rows, key) = (self.rows.clone(), key.into());
        canvas(
            move |b, _, _| {
                rows.borrow_mut().insert(key, b);
            },
            |_, _, _, _| {},
        ).absolute().inset_0()
    }

    /// Put inside the list (the pill's parent): records its origin.
    pub fn origin(&self) -> impl IntoElement {
        let list = self.list.clone();
        canvas(move |b, _, _| list.set(b.origin), |_, _, _, _| {}).absolute().inset_0()
    }

    fn measure(&self, key: &str) -> Option<Rect> {
        let b = *self.rows.borrow().get(key)?;
        let o = self.list.get();
        Some(Rect { top: (b.origin.y - o.y).into(), left: (b.origin.x - o.x).into(), width: b.size.width.into(), height: b.size.height.into() })
    }

    fn snap(&mut self, g: Rect) {
        self.goal = g;
        self.y = Spring { x: g.top, v: 0. };
        self.h = Spring { x: g.height, v: 0. };
        self.l = Spring { x: g.left, v: 0. };
        self.w = Spring { x: g.width, v: 0. };
        self.from = (g.top, g.left);
    }

    fn settled(&self) -> bool {
        !self.lifted
            && self.y.v == 0.
            && self.h.v == 0.
            && self.l.v == 0.
            && self.w.v == 0.
            && self.lx.x == 0.
            && self.ly.x == 0.
            && self.mat.x == 0.
            && self.handover == 1.
            && self.stretch == 0.
            && self.stretch_x == 0.
    }

    /// The pill for the row [key] (None: no row of this list is chosen). Asks for the next frame
    /// while it moves, or while the row has not been drawn yet.
    pub fn render(&mut self, key: Option<&str>, look: &PillLook, reduce: bool, window: &mut Window) -> Option<AnyElement> {
        let Some(key) = key else {
            self.key = None;
            return None;
        };
        let Some(g) = self.measure(key) else {
            if self.waiting.0.as_deref() != Some(key) {
                self.waiting = (Some(key.to_string()), 0);
            }
            if self.waiting.1 < 3 {
                self.waiting.1 += 1;
                window.request_animation_frame();
            }
            // A page whose row is not here (a YouTube playlist): no pill, and none to travel from.
            self.key = None;
            return None;
        };
        let now = Instant::now();
        if self.key.as_deref() != Some(key) {
            let fresh = self.key.is_none();
            self.key = Some(key.to_string());
            if reduce {
                self.snap(g);
            } else if fresh {
                // Appears where it is needed, with a short lift and nothing to travel.
                self.snap(g);
                self.lifted = true;
                self.lift_until = Some(now + std::time::Duration::from_millis(140));
            } else {
                self.from = (self.y.x, self.l.x);
                self.goal = g;
                self.lifted = true;
                self.lift_until = None;
            }
            self.last = None;
        } else if g != self.goal {
            // The rows moved (the list changed, the window resized): follow without a journey.
            if self.settled() {
                self.snap(g);
            } else {
                self.goal = g;
            }
        }

        if !self.settled() {
            let dt = self.last.map_or(1. / 60., |l| now.duration_since(l).as_secs_f32().min(0.05));
            self.last = Some(now);
            let g = self.goal;
            self.y.step(g.top, dt, TRAVEL_K, TRAVEL_C);
            self.h.step(g.height, dt, TRAVEL_K, TRAVEL_C);
            self.l.step(g.left, dt, TRAVEL_K, TRAVEL_C);
            self.w.step(g.width, dt, TRAVEL_K, TRAVEL_C);
            let (span_y, span_x) = ((g.top - self.from.0).abs(), (g.left - self.from.1).abs());
            let progress = if span_x.max(span_y) < 0.5 {
                1.
            } else if span_y >= span_x {
                1. - (g.top - self.y.x).abs() / span_y
            } else {
                1. - (g.left - self.l.x).abs() / span_x
            };
            if progress >= HANDOVER_START || self.lift_until.is_some_and(|t| now >= t) {
                self.lifted = false;
                self.lift_until = None;
            }
            let lt = if self.lifted { 1. } else { 0. };
            self.lx.step(lt, dt, LIFT_K, LIFT_CX);
            self.ly.step(lt, dt, LIFT_K, LIFT_CY);
            self.mat.step(lt, dt, MAT_K, MAT_C);
            let ht = if self.lifted { 0. } else { 1. };
            let tau = if ht > self.handover { 0.09 } else { 0.05 };
            self.handover += (ht - self.handover) * (1. - (-dt / tau).exp());
            if (ht - self.handover).abs() < 0.002 {
                self.handover = ht;
            }
            let target = (self.y.v.abs() * 0.00022).min(0.28);
            self.stretch += (target - self.stretch) * (1. - (-dt / 0.06).exp());
            if self.stretch < 0.0005 && target == 0. {
                self.stretch = 0.;
            }
            let target_x = (self.l.v.abs() * 0.00018).min(0.22);
            self.stretch_x += (target_x - self.stretch_x) * (1. - (-dt / 0.06).exp());
            if self.stretch_x < 0.0005 && target_x == 0. {
                self.stretch_x = 0.;
            }
            if self.settled() {
                self.last = None;
            } else {
                window.request_animation_frame();
            }
        }

        let lift = if reduce { 0. } else { 1. };
        let mut hh = self.h.x + 6. * self.ly.x * lift;
        let mut ww = self.w.x + 8. * self.lx.x * lift;
        hh *= 1. + self.stretch - self.stretch_x * 0.35;
        ww *= 1. + self.stretch_x - self.stretch * 0.35;
        let cy = self.y.x + self.h.x / 2.;
        let cx = self.l.x + self.w.x / 2.;
        let m = self.mat.x.clamp(0., 1.);
        let presence = 1. - self.handover;
        let rim = ((presence - 0.45) / 0.55).clamp(0., 1.);
        let glass = m * rim.max(presence * 0.5);
        let r = px(look.radius.min(hh / 2.));
        Some(
            div()
                .absolute()
                .top(px(cy - hh / 2.))
                .left(px(cx - ww / 2.))
                .w(px(ww.max(0.)))
                .h(px(hh.max(0.)))
                .child(div().absolute().inset_0().rounded(r).bg(look.fill).border_1().border_color(look.ring).opacity(1. - 0.8 * m))
                .child(
                    div()
                        .absolute()
                        .inset_0()
                        .rounded(r)
                        .bg(look.glass)
                        .border_1()
                        .border_color(look.glass_rim)
                        .shadow(vec![BoxShadow { color: black().opacity(0.3), offset: point(px(0.), px(2.)), blur_radius: px(12.), spread_radius: px(0.), inset: false }])
                        .opacity(glass),
                )
                .into_any_element(),
        )
    }
}

#[cfg(test)]
mod tests {
    // Not `super::*`: gpui's own `test` attribute would shadow std's.
    use super::{Spring, TRAVEL_C, TRAVEL_K};

    #[test]
    fn the_spring_lands_on_its_target() {
        let mut s = Spring { x: 0., v: 0. };
        let mut t = 0.;
        while t < 2. {
            s.step(100., 1. / 60., TRAVEL_K, TRAVEL_C);
            t += 1. / 60.;
        }
        assert_eq!((s.x, s.v), (100., 0.));
    }
}
