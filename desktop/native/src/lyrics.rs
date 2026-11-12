//! The playing song's lyrics, moving like Liner's (Tauri: pages/parts/LyricsLines.svelte):
//! syllables light up with a soft sweeping edge, the lines glide one after another to keep the
//! sung one a third of the way down, the edges fade. The list scrolls itself; the wheel takes
//! over for a few seconds; a click on a line seeks there.
//!
//! Drawn by one canvas: every line is shaped at the box's width (that gives the boxes motion.rs
//! follows) and painted where the glide has it, each letter in its own colour along the sweep.
//! Frames are asked for only while the song plays or the list moves; behind another window, four
//! a second. Differences from Tauri: the sweep's soft edge goes letter by letter (no gradient
//! text in GPUI), the fog fades whole lines (no masks), held notes do not glow (no text shadow).

use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;
use std::sync::Arc;
use std::time::{Duration, Instant};

use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use lfly_core::pages;
use serde_json::Value;

use crate::core::{track_of, Core};
use crate::glass;
use crate::icons::icon;
use crate::look::Look;
use crate::motion::{build_lines, is_live, light_of, max_scroll, CubicBezier, Follower, LineBox, LineModel, Scroll, DARK, EDGE_WIDTH};
use crate::player_model::PlayerModel;

const OFFSETS_KEY: &str = "lyrics-offsets";
/// The fog's own easing (cubic-bezier(0.22, 1, 0.36, 1), 450 ms).
const FOG_EASE: CubicBezier = CubicBezier::new(0.22, 1., 0.36, 1.);
const PAD_X: f32 = 4.;
const PAD_TOP: f32 = 24.;

#[derive(Clone, Copy, PartialEq)]
pub enum Variant {
    /// The lyrics page: clamp(28px, 2.7vw, 40px).
    Page,
    /// Beside the full player: clamp(24px, 2.2vw, 34px).
    Panel,
}

#[derive(Clone, Copy, PartialEq)]
enum Status {
    Loading,
    None,
    Ready,
}

/// A fog edge easing between shown and hidden.
#[derive(Clone, Copy)]
struct Fog {
    from: f32,
    to: f32,
    at: f64,
}

impl Fog {
    fn value(&self, now: f64) -> f32 {
        let p = ((now - self.at) / 450.).clamp(0., 1.);
        self.from + (self.to - self.from) * FOG_EASE.at(p) as f32
    }

    fn set(&mut self, to: f32, now: f64) {
        if to != self.to {
            *self = Fog { from: self.value(now), to, at: now };
        }
    }

    fn moving(&self, now: f64) -> bool {
        now - self.at < 450.
    }
}

/// Everything the canvas changes from frame to frame.
struct Motion {
    scroll: Scroll,
    follower: Follower,
    boxes: Vec<LineBox>,
    /// The sheet, width and size the boxes were measured for.
    measured: Option<(u64, u32, u32, u32)>,
    view_h: f64,
    placed: bool,
    paused_until: f64,
    last: f64,
    woke: f64,
    anchor_pos: f64,
    anchor_at: f64,
    fog_top: Fog,
    fog_bottom: Fog,
    /// When each line turned on or off (for the background vocals' 330 ms fade).
    on: Vec<(bool, f64)>,
    /// Where each line was painted, for the pointer.
    rects: Vec<Bounds<Pixels>>,
    hovered: Option<usize>,
    timer: bool,
}

impl Default for Motion {
    fn default() -> Self {
        Self {
            scroll: Scroll::default(),
            follower: Follower::default(),
            boxes: Vec::new(),
            measured: None,
            view_h: 0.,
            placed: false,
            paused_until: 0.,
            last: 0.,
            woke: 0.,
            anchor_pos: 0.,
            anchor_at: 0.,
            fog_top: Fog { from: 0., to: 0., at: -1e9 },
            fog_bottom: Fog { from: 0.26, to: 0.26, at: -1e9 },
            on: Vec::new(),
            rects: Vec::new(),
            hovered: None,
            timer: false,
        }
    }
}

pub struct Lyrics {
    core: Arc<Core>,
    model: Entity<PlayerModel>,
    look: Entity<Look>,
    pub variant: Variant,
    pub window_width: f32,
    cache: HashMap<String, Option<Rc<pages::Lyrics>>>,
    loaded_for: String,
    status: Status,
    lyrics: Option<Rc<pages::Lyrics>>,
    lines: Rc<Vec<LineModel>>,
    /// Which sheet `lines` is, and the duration it was built with.
    sheet: (u64, u64),
    generation: u64,
    offset: f64,
    offsets: serde_json::Map<String, Value>,
    hovered: bool,
    epoch: Instant,
    motion: Rc<RefCell<Motion>>,
    _subs: Vec<Subscription>,
}

impl Lyrics {
    pub fn new(core: Arc<Core>, model: Entity<PlayerModel>, look: Entity<Look>, cx: &mut Context<Self>) -> Self {
        let subs = vec![
            // The song moved, played or paused: wake the loop.
            cx.observe(&model, |this, _, cx| {
                let now = this.now();
                this.motion.borrow_mut().woke = now;
                cx.notify();
            }),
            cx.observe(&look, |_, _, cx| cx.notify()),
        ];
        let offsets = lfly_core::store::get(OFFSETS_KEY).ok().flatten().and_then(|v| v.as_object().cloned()).unwrap_or_default();
        Self {
            core,
            model,
            look,
            variant: Variant::Page,
            window_width: 1100.,
            cache: HashMap::new(),
            loaded_for: String::new(),
            status: Status::Loading,
            lyrics: None,
            lines: Rc::new(Vec::new()),
            sheet: (0, 0),
            generation: 0,
            offset: 0.,
            offsets,
            hovered: false,
            epoch: Instant::now(),
            motion: Rc::new(RefCell::new(Motion::default())),
            _subs: subs,
        }
    }

    fn now(&self) -> f64 {
        self.epoch.elapsed().as_secs_f64() * 1000.
    }

    fn show(&mut self, l: Option<Rc<pages::Lyrics>>) {
        let ready = l.as_ref().is_some_and(|l| l.synced.as_ref().is_some_and(|s| !s.is_empty()) || l.plain.is_some());
        self.status = if ready { Status::Ready } else { Status::None };
        self.lyrics = l;
        self.generation += 1;
    }

    /// Fetches the lyrics when the song changes (the cache keeps the ones read already).
    fn follow_track(&mut self, cx: &mut Context<Self>) {
        let Some(t) = self.model.read(cx).current().cloned() else { return };
        if t.id == self.loaded_for {
            return;
        }
        let id = t.id.clone();
        self.loaded_for = id.clone();
        self.offset = self.offsets.get(&id).and_then(Value::as_f64).unwrap_or(0.);
        if let Some(l) = self.cache.get(&id).cloned() {
            self.show(l);
            return;
        }
        self.status = Status::Loading;
        self.lyrics = None;
        self.generation += 1;
        let Some(track) = track_of(&t) else {
            self.status = Status::None;
            return;
        };
        let core = self.core.clone();
        cx.spawn(async move |this, cx| {
            let got = core.lyrics(track).await;
            let _ = this.update(cx, |this, cx| {
                let l = match got {
                    Ok(l) => l.map(Rc::new),
                    Err(e) => {
                        log::info!("lyrics: {e}");
                        None
                    }
                };
                this.cache.insert(id.clone(), l.clone());
                if this.loaded_for == id {
                    this.show(l);
                    cx.notify();
                }
            });
        })
        .detach();
    }

    fn nudge(&mut self, ms: f64, cx: &mut Context<Self>) {
        self.offset = if ms == 0. { 0. } else { (self.offset + ms).clamp(-10_000., 10_000.) };
        if !self.loaded_for.is_empty() {
            if self.offset == 0. {
                self.offsets.remove(&self.loaded_for);
            } else {
                self.offsets.insert(self.loaded_for.clone(), Value::from(self.offset));
            }
            if let Err(e) = lfly_core::store::set(OFFSETS_KEY, &Value::Object(self.offsets.clone())) {
                log::warn!("{OFFSETS_KEY}: {e}");
            }
        }
        self.motion.borrow_mut().woke = self.now();
        cx.notify();
    }

    fn seek(&mut self, line: usize, cx: &mut Context<Self>) {
        let Some(start) = self.lines.get(line).map(|l| l.start) else { return };
        let m = self.model.read(cx);
        let duration = m.duration_ms() as f64;
        let to = (start + self.offset).max(0.);
        let end = if duration > 0. { (duration - 500.).max(0.) } else { to };
        m.command(lfly_connect::protocol::CMD_SEEK, to.min(end) as i64);
        let now = self.now();
        let mut st = self.motion.borrow_mut();
        st.paused_until = 0.;
        st.follower.reset();
        st.woke = now;
        drop(st);
        cx.notify();
    }
}

fn font_size(variant: Variant, window_width: f32) -> f32 {
    match variant {
        Variant::Page => (window_width * 0.027).clamp(28., 40.),
        Variant::Panel => (window_width * 0.022).clamp(24., 34.),
    }
}

/// Ink at [a] of its alpha.
fn ink(a: f32) -> Hsla {
    white().opacity(a)
}

impl Render for Lyrics {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        self.follow_track(cx);
        let (track, duration) = {
            let m = self.model.read(cx);
            (m.current().cloned(), m.duration_ms())
        };
        let size = font_size(self.variant, self.window_width);
        let glass_look = {
            let l = self.look.read(cx).glass();
            // LyricsLines `.sync`: blur(18px) saturate(1.6) over the glass tint, no lens.
            glass::GlassLook { strength: 1. / 3., lens: false, ..l }
        };

        let Some(track) = track else {
            return div().p(px(24.)).px(px(PAD_X)).text_size(px(15.)).text_color(ink(0.7)).child("Fai partire un brano per vederne il testo.").into_any_element();
        };
        match self.status {
            Status::Loading => {
                return div()
                    .flex()
                    .flex_col()
                    .gap(px(18.))
                    .p(px(24.))
                    .px(px(PAD_X))
                    .children([72., 54., 80., 46., 64.].map(|w: f32| div().w(relative(w / 100.)).h(px(size * 0.8)).rounded(px(10.)).bg(ink(0.12))))
                    .into_any_element()
            }
            Status::None => {
                return div()
                    .p(px(24.))
                    .flex()
                    .flex_col()
                    .items_center()
                    .gap(px(10.))
                    .text_size(px(15.))
                    .text_color(ink(0.7))
                    .child(icon("lyrics", 30., ink(0.7)))
                    .child(format!("Nessun testo trovato per “{}”.", track.title))
                    .into_any_element()
            }
            Status::Ready => {}
        }
        let lyrics = self.lyrics.clone().expect("ready");
        let Some(synced) = lyrics.synced.as_ref().filter(|s| !s.is_empty()) else {
            // Plain text: a page to scroll.
            return div()
                .id("lyrics-plain")
                .size_full()
                .overflow_y_scroll()
                .pt(px(24.))
                .px(px(PAD_X))
                .child(div().text_size(px(size * 0.7)).line_height(px(size * 0.7 * 1.55)).font_weight(FontWeight::BOLD).children(
                    lyrics.plain.clone().unwrap_or_default().lines().map(|l| div().min_h(px(size * 0.7 * 1.55)).child(l.to_string())).collect::<Vec<_>>(),
                ))
                .child(div().mt(px(24.)).text_size(px(12.)).text_color(ink(0.5)).child(format!("Testo da {}", lyrics.source)))
                .child(div().h(relative(0.4)))
                .into_any_element();
        };

        // The sheet, built again when the song or its duration changes.
        if self.sheet != (self.generation, duration) {
            self.lines = Rc::new(build_lines(synced, duration as f64, false));
            self.sheet = (self.generation, duration);
            let mut st = self.motion.borrow_mut();
            st.measured = None;
            st.on = vec![(false, -1e9); self.lines.len()];
        }

        let lines = self.lines.clone();
        let motion = self.motion.clone();
        let model = self.model.clone();
        let epoch = self.epoch;
        let offset = self.offset;
        let generation = self.generation;
        let source: SharedString = format!("Testo da {}", lyrics.source).into();
        let weak = cx.entity().downgrade();
        let active = window.is_window_active();
        let font = {
            let mut f = window.text_style().font();
            f.weight = FontWeight::BOLD;
            f
        };

        let sheet = canvas(
            |_, _, _| {},
            move |bounds, _, window, cx| {
                let now = epoch.elapsed().as_secs_f64() * 1000.;
                let mut st = motion.borrow_mut();
                let st = &mut *st;
                let width = f32::from(bounds.size.width);
                let text_w = (width - 2. * PAD_X).max(40.);
                let main_lh = size * 1.3;
                let bg_size = size * 0.72;
                let bg_lh = bg_size * 1.3;
                let pad_y = size * 0.36;
                let bg_font = Font { weight: FontWeight::SEMIBOLD, ..font.clone() };

                // Every line measured at this width: its box, as offsetTop/offsetHeight.
                let key = (generation, width.round() as u32, f32::from(bounds.size.height).round() as u32, (size * 10.).round() as u32);
                if st.measured != Some(key) {
                    let mut top = PAD_TOP as f64;
                    st.boxes = lines
                        .iter()
                        .map(|l| {
                            let inner = if l.instrumental {
                                32.
                            } else {
                                let rows = |text: &str, sz: f32, f: &Font| {
                                    let run = TextRun { len: text.len(), font: f.clone(), color: white(), background_color: None, underline: None, strikethrough: None };
                                    window
                                        .text_system()
                                        .shape_text(SharedString::from(text.to_string()), px(sz), &[run], Some(px(text_w)), None)
                                        .map(|ls| ls.iter().map(|l| l.wrap_boundaries().len() + 1).sum::<usize>())
                                        .unwrap_or(1)
                                };
                                let painted: String = l.groups.iter().flat_map(|g| g.tokens.iter().map(|t| t.text.as_str())).collect();
                                let mut h = rows(&painted, size, &font) as f32 * main_lh;
                                if let Some(bg) = &l.bg {
                                    h += 4. + rows(bg, bg_size, &bg_font) as f32 * bg_lh;
                                }
                                h
                            };
                            let b = LineBox { top, height: (inner + 2. * pad_y) as f64 };
                            top += b.height;
                            b
                        })
                        .collect();
                    st.view_h = f64::from(f32::from(bounds.size.height));
                    let max = max_scroll(&st.boxes, st.view_h);
                    st.scroll.limit(max);
                    st.follower.reset();
                    st.placed = false;
                    st.measured = Some(key);
                    st.woke = now;
                }

                // The clock between the player's samples: carried forward, re-anchored on drift.
                let (raw, playing_now) = {
                    let m = model.read(cx);
                    (m.live_position(), m.playing())
                };
                let clock = if !playing_now || (st.anchor_pos + (now - st.anchor_at) - raw).abs() > 150. {
                    st.anchor_pos = raw;
                    st.anchor_at = now;
                    raw
                } else {
                    st.anchor_pos + (now - st.anchor_at)
                };
                let at = clock - offset;
                let dt = (now - st.last).min(64.);
                st.last = now;

                if now >= st.paused_until {
                    let view_h = st.view_h;
                    if let Some(d) = st.follower.decide(&lines, &st.boxes, at, view_h) {
                        if st.placed {
                            st.scroll.glide(d.top, d.anchor, now);
                        } else {
                            st.scroll.jump(d.top);
                        }
                        st.placed = true;
                    }
                }
                st.scroll.step(now, dt);
                st.fog_top.set(if st.scroll.pos <= 4. { 0. } else { 0.22 }, now);
                st.fog_bottom.set(if st.scroll.max - st.scroll.pos <= 4. { 0. } else { 0.26 }, now);
                let (fog_t, fog_b) = (st.fog_top.value(now), st.fog_bottom.value(now));
                let view_h = st.view_h as f32;
                // The mask: transparent at the edges, opaque past the fog.
                let fog = |y: f32| -> f32 {
                    let f = y / view_h.max(1.);
                    let top = if fog_t > 0. { (f / fog_t).clamp(0., 1.) } else { 1. };
                    let bottom = if fog_b > 0. { ((1. - f) / fog_b).clamp(0., 1.) } else { 1. };
                    top.min(bottom)
                };

                let mouse = window.mouse_position();
                let origin = bounds.origin;
                st.rects.clear();
                st.hovered = None;
                window.with_content_mask(Some(ContentMask { bounds }), |window| {
                    let mut last_y = 0.;
                    for (i, line) in lines.iter().enumerate() {
                        let b = st.boxes[i];
                        let y = ((st.scroll.lag(i, now) - st.scroll.pos) * 2.).round() / 2. + b.top;
                        last_y = y + b.height;
                        let rect = Bounds::new(point(origin.x + px(PAD_X - 12.), origin.y + px(y as f32)), gpui_kit::size(px(text_w + 24.), px(b.height as f32)));
                        st.rects.push(rect);
                        let on = at >= line.start && at < line.exit;
                        if st.on[i].0 != on {
                            st.on[i] = (on, now);
                        }
                        if y + b.height < 0. || y > st.view_h {
                            continue;
                        }
                        let alpha = fog((y + b.height / 2.) as f32);
                        if alpha <= 0.01 {
                            continue;
                        }
                        if rect.contains(&mouse) {
                            st.hovered = Some(i);
                            window.paint_quad(fill(rect, ink(0.07 * alpha)).corner_radii(px(14.)));
                        }
                        let text_origin = point(origin.x + px(PAD_X), origin.y + px(y as f32 + pad_y));
                        let dim = 0.3;
                        if line.instrumental {
                            // Three dots, breathing while their pause lasts.
                            for k in 0..3 {
                                let s = if on {
                                    let p = (((now - st.on[i].1) / 1000. - 0.2 * k as f64) / 1.6).rem_euclid(1.);
                                    1. + 0.3 * (0.5 - 0.5 * (p * std::f64::consts::TAU).cos()) as f32
                                } else {
                                    1.
                                };
                                let d = 12. * s;
                                let cx0 = PAD_X + 6. + k as f32 * 22.;
                                let c = point(origin.x + px(cx0 - d / 2.), text_origin.y + px(10. + 6. - d / 2.));
                                window.paint_quad(fill(Bounds::new(c, gpui_kit::size(px(d), px(d))), ink(if on { 1. } else { dim } * alpha)).corner_radii(px(d / 2.)));
                            }
                            continue;
                        }
                        // One run per letter, coloured along its token's sweep.
                        let live = is_live(line, at);
                        let mut text = String::new();
                        let mut runs: Vec<TextRun> = Vec::new();
                        let push = |s: &str, a: f32, runs: &mut Vec<TextRun>| {
                            let color = ink(a * alpha);
                            if let Some(last) = runs.last_mut().filter(|r| r.color == color) {
                                last.len += s.len();
                            } else {
                                runs.push(TextRun { len: s.len(), font: font.clone(), color, background_color: None, underline: None, strikethrough: None });
                            }
                        };
                        for g in &line.groups {
                            for t in &g.tokens {
                                text.push_str(&t.text);
                                let light = if g.space || !live { DARK } else { light_of(t, line, at) };
                                if light.alpha <= 0. {
                                    push(&t.text, dim, &mut runs);
                                    continue;
                                }
                                let lit = 0.3 + 0.7 * light.alpha as f32;
                                let n = t.text.chars().count().max(1) as f64;
                                for (j, ch) in t.text.chars().enumerate() {
                                    let f = (j as f64 + 0.5) / n;
                                    let k = ((f - light.solid) / EDGE_WIDTH).clamp(0., 1.) as f32;
                                    let mut buf = [0u8; 4];
                                    push(ch.encode_utf8(&mut buf), lit + (dim - lit) * k, &mut runs);
                                }
                            }
                        }
                        if let Ok(shaped) = window.text_system().shape_text(text.into(), px(size), &runs, Some(px(text_w)), None) {
                            let mut oy = text_origin.y;
                            for l in &shaped {
                                let _ = l.paint(point(text_origin.x, oy), px(main_lh), TextAlign::Left, None, window, cx);
                                oy += l.size(px(main_lh)).height;
                            }
                            if let Some(bg) = &line.bg {
                                // `.bgv`: 23% of the ink, 78% while the line is sung, 330 ms between.
                                let p = (((now - st.on[i].1) / 330.).clamp(0., 1.)) as f32;
                                let (from, to) = if on { (0.23, 0.78) } else { (0.78, 0.23) };
                                let a = from + (to - from) * p;
                                let run = TextRun { len: bg.len(), font: bg_font.clone(), color: ink(a * alpha), background_color: None, underline: None, strikethrough: None };
                                if let Ok(shaped) = window.text_system().shape_text(SharedString::from(bg.clone()), px(bg_size), &[run], Some(px(text_w)), None) {
                                    let mut oy = oy + px(4.);
                                    for l in &shaped {
                                        let _ = l.paint(point(text_origin.x, oy), px(bg_lh), TextAlign::Left, None, window, cx);
                                        oy += l.size(px(bg_lh)).height;
                                    }
                                }
                            }
                        }
                    }
                    // "Testo da …", riding with the last line.
                    let y = last_y as f32 + 24.;
                    if y < view_h {
                        let run = TextRun { len: source.len(), font: Font { weight: FontWeight::NORMAL, ..font.clone() }, color: ink(0.5 * fog(y + 8.)), background_color: None, underline: None, strikethrough: None };
                        if let Ok(shaped) = window.text_system().shape_text(source.clone(), px(12.), &[run], None, None) {
                            for l in &shaped {
                                let _ = l.paint(point(origin.x + px(PAD_X), origin.y + px(y)), px(16.), TextAlign::Left, None, window, cx);
                            }
                        }
                    }
                });

                // The next frame: while the song plays, the list moves or just woke.
                let busy = playing_now || st.scroll.moving() || now - st.woke < 900. || st.fog_top.moving(now) || st.fog_bottom.moving(now);
                // At most 60 a second, whatever the monitor's rate (a 180 Hz screen would draw
                // three times the frames for a sweep nobody can tell apart); behind another
                // window (a game), four.
                if busy && !st.timer {
                    st.timer = true;
                    let wait = if active { 16 } else { 250 };
                    let weak = weak.clone();
                    let motion = motion.clone();
                    cx.spawn(async move |cx| {
                        cx.background_executor().timer(Duration::from_millis(wait)).await;
                        motion.borrow_mut().timer = false;
                        let _ = weak.update(cx, |_, cx| cx.notify());
                    })
                    .detach();
                }
            },
        )
        .size_full();

        let me = cx.entity().downgrade();
        let (me1, me2, me3) = (me.clone(), me.clone(), me.clone());
        let (m1, m2, m3) = (self.motion.clone(), self.motion.clone(), self.motion.clone());
        let offset_label = {
            let sign = if self.offset > 0. { "+" } else if self.offset < 0. { "−" } else { "±" };
            format!("{sign}{:.1} s", self.offset.abs() / 1000.).replace('.', ",")
        };
        let nudge = |id: &'static str, label: SharedString, ms: f64, min_w: f32, moved: bool| {
            let me = me.clone();
            div()
                .id(id)
                .min_w(px(min_w))
                .h(px(28.))
                .px(px(8.))
                .flex()
                .items_center()
                .justify_center()
                .rounded_full()
                .cursor_pointer()
                .when(moved, |d| d.text_color(self.look.read(cx).scheme.accent.hsla(1.)))
                .hover(|d| d.bg(ink(0.1)))
                .on_click(move |_, _, cx| {
                    // Not a click on the line under the capsule as well.
                    cx.stop_propagation();
                    let _ = me.update(cx, |this, cx| this.nudge(ms, cx));
                })
                .child(label)
        };
        let text = self.look.read(cx).scheme.text;
        let sync = div()
            .absolute()
            .bottom(px(10.))
            .left_0()
            .right_0()
            .flex()
            .justify_center()
            .when(!self.hovered, |d| d.invisible())
            .child(
                div()
                    .relative()
                    .rounded_full()
                    .child(glass::glass_layer(glass_look, px(17.), glass::Pointer::default()))
                    .child(
                        div()
                            .relative()
                            .p(px(3.))
                            .flex()
                            .items_center()
                            .gap(px(2.))
                            .text_size(px(13.))
                            .font_weight(FontWeight::SEMIBOLD)
                            .text_color(text)
                            .child(nudge("lyr-earlier", "−".into(), -250., 30., false))
                            .child(nudge("lyr-reset", offset_label.into(), 0., 64., self.offset != 0.))
                            .child(nudge("lyr-later", "+".into(), 250., 30., false)),
                    ),
            );

        div()
            .id("lyrics")
            .relative()
            .size_full()
            .overflow_hidden()
            .on_hover(move |hovered, _, cx| {
                let _ = me1.update(cx, |this, cx| {
                    this.hovered = *hovered;
                    cx.notify();
                });
            })
            .on_mouse_move(move |e, _, cx| {
                let st = m1.borrow();
                let over = st.rects.iter().position(|r| r.contains(&e.position));
                if over != st.hovered {
                    drop(st);
                    let _ = me2.update(cx, |_, cx| cx.notify());
                }
            })
            .on_click(move |e, _, cx| {
                let line = m2.borrow().rects.iter().position(|r| r.contains(&e.position()));
                if let Some(i) = line {
                    let _ = me3.update(cx, |this, cx| this.seek(i, cx));
                }
            })
            .on_scroll({
                let me = cx.entity().downgrade();
                move |e, window, cx| {
                    let dy = -f64::from(f32::from(e.delta.pixel_delta(px(32.)).y));
                    let _ = me.update(cx, |this, cx| {
                        let now = this.now();
                        let mut st = m3.borrow_mut();
                        st.scroll.wheel(dy);
                        st.paused_until = now + 3500.;
                        st.follower.reset();
                        st.woke = now;
                        drop(st);
                        cx.notify();
                    });
                    let _ = window;
                    cx.stop_propagation();
                }
            })
            .child(sheet)
            .child(sync)
            .into_any_element()
    }
}
