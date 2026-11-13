//! The playing song's lyrics, moving like Liner's (Tauri: pages/parts/LyricsLines.svelte):
//! syllables light up with a soft sweeping edge, the lines glide one after another to keep the
//! sung one a third of the way down, the edges fade. The list scrolls itself; the wheel takes
//! over for a few seconds; a click on a line seeks there.
//!
//! With the phone's word by word on top (LyricsView.kt): the word being sung springs up, grows
//! and glows, what is sung glows softly, and the other lines blur the farther they are from the
//! one sung (all sharp while the pointer is on the list or the wheel moved it).
//!
//! Drawn by one canvas: every line is shaped at the box's width (that gives the boxes motion.rs
//! follows) and painted where the glide has it, glyph by glyph (`paint_glyph_fx`, a GPUI patch:
//! blurred and scaled glyphs), each letter in its own colour along the sweep. Frames are asked for
//! only while the song plays or the list moves; behind the focus thirty, off screen four. Differences
//! from Tauri: the sweep's soft edge goes letter by letter (no gradient text in GPUI), the fog
//! fades whole lines (no masks).

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
use crate::motion::{build_lines, is_live, light_of, line_blur, max_scroll, pop, word_spans, CubicBezier, Follower, LineBox, LineModel, Scroll, CUBIC_OUT, DARK, EDGE_WIDTH};
use crate::player_model::PlayerModel;

const OFFSETS_KEY: &str = "lyrics-offsets";
/// The fog's own easing (cubic-bezier(0.22, 1, 0.36, 1), 450 ms).
const FOG_EASE: CubicBezier = CubicBezier::new(0.22, 1., 0.36, 1.);
const PAD_TOP: f32 = 24.;

#[derive(Clone, Copy, PartialEq)]
pub enum Variant {
    /// The lyrics page: clamp(28px, 2.7vw, 40px).
    Page,
    /// Beside the full player: clamp(24px, 2.2vw, 34px).
    Panel,
    /// The right panel: 21 px in the theme's text colour, 14 px in from the sides.
    Side,
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

/// A line's blur easing to a new depth (the phone's 220 ms), as a Gaussian's sigma in px.
#[derive(Clone, Copy)]
struct Blur {
    from: f32,
    to: f32,
    at: f64,
}

impl Blur {
    const MS: f64 = 220.;
    const STILL: Blur = Blur { from: 0., to: 0., at: -1e9 };

    fn value(&self, now: f64) -> f32 {
        let p = ((now - self.at) / Self::MS).clamp(0., 1.);
        self.from + (self.to - self.from) * CUBIC_OUT.at(p) as f32
    }

    fn set(&mut self, to: f32, now: f64, instant: bool) {
        if instant {
            *self = Blur { from: to, to, at: -1e9 };
        } else if (to - self.to).abs() > 1e-3 {
            *self = Blur { from: self.value(now), to, at: now };
        }
    }

    fn moving(&self, now: f64) -> bool {
        now - self.at < Self::MS
    }
}

/// Past the text on either side (Page and Panel), so a glow, a blur or a word grown by its pop
/// at the edge is not cut in a straight line: the box reaches out by this, the text stays put.
const BLEED: f32 = 20.;

/// How long the lines stay sharp after the pointer moved over them.
const BROWSE_MS: f64 = 2500.;

/// A glyph where `WrappedLine::paint` would put it.
struct Placed {
    /// On the baseline.
    at: Point<Pixels>,
    font: FontId,
    glyph: GlyphId,
    /// Its byte in the text.
    index: usize,
    emoji: bool,
    advance: Pixels,
    row_top: Pixels,
}

/// `aligned_origin_x` of GPUI's line.rs: where the row starting at [row_from] begins.
fn aligned_x(x0: Pixels, width: Pixels, row_from: Pixels, align: TextAlign, layout: &LineLayout, next: Option<&WrapBoundary>) -> Pixels {
    let end = next.map_or(layout.width, |w| layout.runs[w.run_ix].glyphs[w.glyph_ix].position.x);
    let w = end - row_from;
    match align {
        TextAlign::Left => x0,
        TextAlign::Center => x0 + (width - w) / 2.,
        TextAlign::Right => x0 + width - w,
    }
}

/// Every glyph of [shaped] placed as GPUI's `paint_line` places it (rows, wraps, alignment),
/// so lyrics.rs can paint each one on its own: blurred, glowing, grown. Returns the bottom.
fn place(shaped: &[WrappedLine], origin: Point<Pixels>, line_h: Pixels, align: TextAlign, width: Pixels, out: &mut Vec<Placed>) -> Pixels {
    let mut y = origin.y;
    for wl in shaped {
        let layout = &wl.unwrapped_layout;
        let baseline = (line_h - layout.ascent - layout.descent) / 2. + layout.ascent;
        let mut wraps = wl.wrap_boundaries.iter().peekable();
        let mut x = aligned_x(origin.x, width, px(0.), align, layout, wraps.peek().copied());
        let mut prev = px(0.);
        let first = out.len();
        for (run_ix, run) in layout.runs.iter().enumerate() {
            for (glyph_ix, g) in run.glyphs.iter().enumerate() {
                x += g.position.x - prev;
                if wraps.peek().is_some_and(|w| w.run_ix == run_ix && w.glyph_ix == glyph_ix) {
                    wraps.next();
                    x = aligned_x(origin.x, width, g.position.x, align, layout, wraps.peek().copied());
                    y += line_h;
                }
                prev = g.position.x;
                out.push(Placed { at: point(x, y + baseline + g.position.y), font: run.font_id, glyph: g.id, index: g.index, emoji: g.is_emoji, advance: px(0.), row_top: y });
            }
        }
        // Each glyph's width: up to the next one in the unwrapped line.
        let xs: Vec<Pixels> = layout.runs.iter().flat_map(|r| r.glyphs.iter().map(|g| g.position.x)).collect();
        for (k, p) in out[first..].iter_mut().enumerate() {
            p.advance = xs.get(k + 1).copied().unwrap_or(layout.width) - xs[k];
        }
        y += line_h;
    }
    y
}

/// What one letter of a line is painted with.
#[derive(Clone, Copy)]
struct Ink {
    alpha: f32,
    glow: f32,
    /// The word it belongs to (its group), for the pop.
    word: usize,
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
    /// When the pointer last moved over the list: the lines stay sharp a little while after.
    browsed: f64,
    /// Each line's blur, deeper the farther it is from the one sung.
    blur: Vec<Blur>,
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
            browsed: -1e9,
            blur: Vec::new(),
        }
    }
}

/// The lyrics fetched so far and the timing corrections, shared by every lyrics view (the side
/// panel, the page and the full player read the same songs: one fetch each).
pub struct LyricsData {
    core: Arc<Core>,
    cache: HashMap<String, Option<Rc<pages::Lyrics>>>,
    loading: std::collections::HashSet<String>,
    /// Per-song timing correction in ms (positive: the words come later), kept as Tauri keeps it.
    offsets: serde_json::Map<String, Value>,
}

impl LyricsData {
    /// Songs without lyrics are asked again once the downloads kept some (a "Scarica mancanti",
    /// a download): what is cached as "none" may be there now.
    pub fn forget_missing(&mut self, cx: &mut Context<Self>) {
        let before = self.cache.len();
        self.cache.retain(|_, v| v.is_some());
        if self.cache.len() != before {
            cx.notify();
        }
    }

    /// Settings changed the sources: every song is asked again (kept lyrics stay first).
    pub fn forget_all(&mut self, cx: &mut Context<Self>) {
        if !self.cache.is_empty() {
            self.cache.clear();
            cx.notify();
        }
    }

    pub fn new(core: Arc<Core>) -> Self {
        let offsets = lfly_core::store::get(OFFSETS_KEY).ok().flatten().and_then(|v| v.as_object().cloned()).unwrap_or_default();
        Self { core, cache: HashMap::new(), loading: Default::default(), offsets }
    }

    /// The song's lyrics: `None` while they are being fetched (the fetch starts here).
    pub(crate) fn get(&mut self, t: &lfly_connect::protocol::TrackRef, cx: &mut Context<Self>) -> Option<Option<Rc<pages::Lyrics>>> {
        if let Some(l) = self.cache.get(&t.id) {
            return Some(l.clone());
        }
        // Kept with a download: read here, no connection needed (DownloadLyricsStore.kt).
        if self.loading.contains(&t.id) {
            return None;
        }
        if let Some(saved) = crate::downloads::DownloadsModel::entity(cx).read(cx).kept_lyrics(&t.id) {
            log::info!("lyrics of {}: kept offline", t.id);
            let l = saved.lyrics.map(Rc::new);
            self.cache.insert(t.id.clone(), l.clone());
            return Some(l);
        }
        if self.loading.insert(t.id.clone()) {
            let id = t.id.clone();
            let Some(track) = track_of(t) else {
                self.cache.insert(id, None);
                return Some(None);
            };
            let core = self.core.clone();
            cx.spawn(async move |this, cx| {
                let got = core.lyrics(track).await;
                let _ = this.update(cx, |this, cx| {
                    // A downloaded song keeps what was found, when the downloads bring lyrics.
                    if let Ok(l) = &got {
                        let dl = crate::downloads::DownloadsModel::entity(cx);
                        if dl.read(cx).has(&id) && crate::prefs::Prefs::get(cx).download_lyrics {
                            dl.update(cx, |m, cx| m.keep_lyrics(&id, l.as_ref(), cx));
                        }
                    }
                    let l = match got {
                        Ok(l) => l.map(Rc::new),
                        Err(e) => {
                            log::info!("lyrics: {e}");
                            None
                        }
                    };
                    this.loading.remove(&id);
                    this.cache.insert(id, l);
                    cx.notify();
                });
            })
            .detach();
        }
        None
    }

    /// Settings → Testi → Azzera: every saved correction forgotten.
    pub fn reset_offsets(&mut self, cx: &mut Context<Self>) {
        self.offsets.clear();
        if let Err(e) = lfly_core::store::set(OFFSETS_KEY, &Value::Object(Default::default())) {
            log::warn!("{OFFSETS_KEY}: {e}");
        }
        cx.notify();
    }

    pub(crate) fn offset(&self, id: &str) -> f64 {
        self.offsets.get(id).and_then(Value::as_f64).unwrap_or(0.)
    }

    fn set_offset(&mut self, id: &str, ms: f64, cx: &mut Context<Self>) {
        // Read again first: the Tauri app may have kept others since.
        if let Some(saved) = lfly_core::store::get(OFFSETS_KEY).ok().flatten().and_then(|v| v.as_object().cloned()) {
            self.offsets = saved;
        }
        if ms == 0. {
            self.offsets.remove(id);
        } else {
            self.offsets.insert(id.to_string(), Value::from(ms));
        }
        if let Err(e) = lfly_core::store::set(OFFSETS_KEY, &Value::Object(self.offsets.clone())) {
            log::warn!("{OFFSETS_KEY}: {e}");
        }
        cx.notify();
    }
}

pub struct Lyrics {
    data: Entity<LyricsData>,
    model: Entity<PlayerModel>,
    look: Entity<Look>,
    nav: Option<Entity<crate::nav::Nav>>,
    pub variant: Variant,
    pub window_width: f32,
    loaded_for: String,
    status: Status,
    lyrics: Option<Rc<pages::Lyrics>>,
    lines: Rc<Vec<LineModel>>,
    /// When each word of each line is the one sung (motion::word_spans).
    spans: Rc<Vec<Vec<(usize, f64, f64)>>>,
    /// Which sheet `lines` is, and the duration it was built with.
    sheet: (u64, u64, bool),
    generation: u64,
    offset: f64,
    hovered: bool,
    epoch: Instant,
    motion: Rc<RefCell<Motion>>,
    _subs: Vec<Subscription>,
}

impl Lyrics {
    /// [nav]: the side panel's lyrics open the page from their capsule.
    pub fn new(
        data: Entity<LyricsData>,
        model: Entity<PlayerModel>,
        look: Entity<Look>,
        nav: Option<Entity<crate::nav::Nav>>,
        variant: Variant,
        cx: &mut Context<Self>,
    ) -> Self {
        let subs = vec![
            cx.observe(&crate::prefs::Prefs::entity(cx), |this, _, cx| {
                this.motion.borrow_mut().measured = None;
                cx.notify();
            }),
            cx.observe(&data, |this, _, cx| {
                this.loaded_for.clear();
                cx.notify();
            }),
            // The song moved, played or paused: wake the loop.
            cx.observe(&model, |this, _, cx| {
                let now = this.now();
                this.motion.borrow_mut().woke = now;
                cx.notify();
            }),
            cx.observe(&look, |_, _, cx| cx.notify()),
        ];
        Self {
            data,
            model,
            look,
            nav,
            variant,
            window_width: 1100.,
            loaded_for: String::new(),
            status: Status::Loading,
            lyrics: None,
            lines: Rc::new(Vec::new()),
            spans: Rc::new(Vec::new()),
            sheet: (0, 0, true),
            generation: 0,
            offset: 0.,
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
        let same = match (&self.lyrics, &l) {
            (Some(a), Some(b)) => Rc::ptr_eq(a, b),
            (None, None) => true,
            _ => false,
        };
        self.status = if ready { Status::Ready } else { Status::None };
        if !same {
            self.lyrics = l;
            self.generation += 1;
        }
    }

    /// The playing song's lyrics, from the shared data (which fetches them once).
    fn follow_track(&mut self, cx: &mut Context<Self>) {
        let Some(t) = self.model.read(cx).current().cloned() else { return };
        if t.id == self.loaded_for {
            return;
        }
        let got = self.data.update(cx, |d, cx| d.get(&t, cx));
        self.offset = self.data.read(cx).offset(&t.id);
        match got {
            Some(l) => {
                self.loaded_for = t.id.clone();
                self.show(l);
            }
            None => {
                // Asked again when the data arrives (it notifies).
                if self.status != Status::Loading || self.lyrics.is_some() {
                    self.status = Status::Loading;
                    self.lyrics = None;
                    self.generation += 1;
                }
            }
        }
    }

    fn nudge(&mut self, ms: f64, cx: &mut Context<Self>) {
        self.offset = if ms == 0. { 0. } else { (self.offset + ms).clamp(-10_000., 10_000.) };
        if !self.loaded_for.is_empty() {
            let (id, offset) = (self.loaded_for.clone(), self.offset);
            self.data.update(cx, |d, cx| d.set_offset(&id, offset, cx));
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
        Variant::Side => 21.,
    }
}

impl Render for Lyrics {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        self.follow_track(cx);
        let (track, duration) = {
            let m = self.model.read(cx);
            (m.current().cloned(), m.duration_ms())
        };
        let prefs = crate::prefs::Prefs::get(cx);
        // Settings → Testi: the size, centred lines, the source line.
        // Whole pixels: every size keeps its glyphs (sharp, blurred, glowing) in the atlas, so a
        // resize must not make a new size at every step.
        let size = (font_size(self.variant, self.window_width) * prefs.lyrics_scale()).round();
        let align = if prefs.lyrics_center { TextAlign::Center } else { TextAlign::Left };
        let center = prefs.lyrics_center;
        let show_source = prefs.lyrics_source;
        let reduce = crate::prefs::Prefs::reduce_motion(cx);
        let side = self.variant == Variant::Side;
        // `--lyrics-ink`: white over the cover's light, the text colour in the panel.
        let base = if side { self.look.read(cx).scheme.text } else { white() };
        let ink = move |a: f32| base.opacity(a);
        #[allow(non_snake_case)]
        let PAD_X: f32 = if side { 14. } else { 4. };
        let (fog_top_on, fog_bottom_on) = if side { (0.14, 0.18) } else { (0.22, 0.26) };
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
                .when(show_source, |d| d.child(div().mt(px(24.)).text_size(px(12.)).text_color(ink(0.5)).child(format!("Testo da {}", lyrics.source))))
                .child(div().h(relative(0.4)))
                .into_any_element();
        };

        // The sheet, built again when the song or its duration changes.
        // Whole lines instead of word by word, when asked.
        let words = prefs.lyrics_words;
        if self.sheet != (self.generation, duration, words) {
            self.lines = Rc::new(build_lines(synced, duration as f64, !words));
            self.spans = Rc::new(self.lines.iter().map(word_spans).collect());
            self.sheet = (self.generation, duration, words);
            let mut st = self.motion.borrow_mut();
            st.measured = None;
            st.on = vec![(false, -1e9); self.lines.len()];
            st.blur = vec![Blur::STILL; self.lines.len()];
        }

        let lines = self.lines.clone();
        let spans = self.spans.clone();
        let motion = self.motion.clone();
        let model = self.model.clone();
        let epoch = self.epoch;
        let offset = self.offset;
        let generation = self.generation;
        let source: SharedString = format!("Testo da {}", lyrics.source).into();
        let weak = cx.entity().downgrade();
        // In front: 60 frames a second. Behind the focus but on screen (beside the window in
        // use), 30: still smooth. Not presented at all (minimized, suspended), four.
        // LFLY_FORCE_ACTIVE: frames as for the window in front (to measure them from the back).
        let wait = if window.is_window_active() || std::env::var_os("LFLY_FORCE_ACTIVE").is_some() {
            16
        } else if crate::presented(window) {
            33
        } else {
            250
        };
        let font = {
            let mut f = window.text_style().font();
            f.weight = FontWeight::BOLD;
            f
        };

        // The sheet's box reaches BLEED past the view on both sides (Page, Panel): the text too.
        #[allow(non_snake_case)]
        let PAD_X: f32 = PAD_X + if side { 0. } else { BLEED };
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
                let (raw, playing_now, rate) = {
                    let m = model.read(cx);
                    (m.live_position(), m.playing(), m.rate())
                };
                // At the song's own rate (Velocità, an Automix's tempo match), not the wall's.
                let carried = st.anchor_pos + (now - st.anchor_at) * rate;
                let clock = if !playing_now || (carried - raw).abs() > 150. {
                    st.anchor_pos = raw;
                    st.anchor_at = now;
                    raw
                } else {
                    carried
                };
                let at = clock - offset;
                let dt = (now - st.last).min(64.);
                st.last = now;

                if now >= st.paused_until {
                    let view_h = st.view_h;
                    if let Some(d) = st.follower.decide(&lines, &st.boxes, at, view_h) {
                        if st.placed && !reduce {
                            st.scroll.glide(d.top, d.anchor, now);
                        } else {
                            st.scroll.jump(d.top);
                        }
                        st.placed = true;
                    }
                }
                st.scroll.step(now, dt);
                st.fog_top.set(if st.scroll.pos <= 4. { 0. } else { fog_top_on }, now);
                st.fog_bottom.set(if st.scroll.max - st.scroll.pos <= 4. { 0. } else { fog_bottom_on }, now);
                let (fog_t, fog_b) = (st.fog_top.value(now), st.fog_bottom.value(now));
                let view_h = st.view_h as f32;
                // The mask: transparent at the edges, opaque past the fog.
                let fog = |y: f32| -> f32 {
                    let f = y / view_h.max(1.);
                    let top = if fog_t > 0. { (f / fog_t).clamp(0., 1.) } else { 1. };
                    let bottom = if fog_b > 0. { ((1. - f) / fog_b).clamp(0., 1.) } else { 1. };
                    top.min(bottom)
                };

                // The phone's depth: the lines being sung sharp, the others blurred by how far
                // they are, easing over 220 ms; all sharp while the list is pointed at or scrolled.
                let s = size / 30.;
                // Moving the pointer over the list or scrolling it: every line sharp, to read and
                // pick one; a pointer left resting there lets them blur again.
                let still = now - st.browsed < BROWSE_MS || now < st.paused_until;
                let on_now: Vec<usize> = (0..lines.len()).filter(|&i| at >= lines[i].start && at < lines[i].exit).collect();
                let focus = if on_now.is_empty() { vec![(0..lines.len()).rev().find(|&i| lines[i].start <= at).unwrap_or(0)] } else { on_now };
                st.blur.resize(lines.len(), Blur::STILL);
                for (i, b) in st.blur.iter_mut().enumerate() {
                    let d = focus.iter().map(|&j| i.abs_diff(j)).min().unwrap_or(0);
                    b.set(if still || lines[i].instrumental { 0. } else { line_blur(d) as f32 * s }, now, reduce);
                }
                // The sung word's glow (the phone's text shadow), its lift and growth.
                let glow_sigma = px(5.2 * s);
                let mut placed: Vec<Placed> = Vec::new();

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
                                // Three 12 px dots 10 apart: 56 px, centred when the lines are.
                                let left = if center { PAD_X + (text_w - 56.) / 2. } else { PAD_X };
                                let cx0 = left + 6. + k as f32 * 22.;
                                let c = point(origin.x + px(cx0 - d / 2.), text_origin.y + px(10. + 6. - d / 2.));
                                window.paint_quad(fill(Bounds::new(c, gpui_kit::size(px(d), px(d))), ink(if on { 1. } else { dim } * alpha)).corner_radii(px(d / 2.)));
                            }
                            continue;
                        }
                        // Each letter's ink along its token's sweep, its glow, its word.
                        let live = is_live(line, at);
                        let blur = px(st.blur[i].value(now));
                        let mut text = String::new();
                        let mut inks: Vec<Ink> = Vec::new();
                        // Byte → letter.
                        let mut letter: Vec<u32> = Vec::new();
                        for (gi, g) in line.groups.iter().enumerate() {
                            for t in &g.tokens {
                                text.push_str(&t.text);
                                let light = if g.space || !live { DARK } else { light_of(t, line, at) };
                                let lit = 0.3 + 0.7 * light.alpha as f32;
                                let n = t.text.chars().count().max(1) as f64;
                                for (j, ch) in t.text.chars().enumerate() {
                                    let (a, glow) = if light.alpha <= 0. {
                                        (dim, 0.)
                                    } else {
                                        let f = (j as f64 + 0.5) / n;
                                        let k = ((f - light.solid) / EDGE_WIDTH).clamp(0., 1.) as f32;
                                        // What is sung glows softly (the phone's active line), a held
                                        // note more (Tauri's `.glow`).
                                        (lit + (dim - lit) * k, (1. - k) * light.alpha as f32 * (0.4 + 0.35 * light.glow as f32))
                                    };
                                    letter.extend(std::iter::repeat_n(inks.len() as u32, ch.len_utf8()));
                                    inks.push(Ink { alpha: a, glow, word: gi });
                                }
                            }
                        }
                        // The sung word springs up (timed lines only, never with reduced motion).
                        let mut pops = vec![0f32; line.groups.len()];
                        if live && !reduce {
                            for &(gi, from, until) in &spans[i] {
                                if at >= from && at < until + 1500. {
                                    pops[gi] = pop(from, until, at) as f32;
                                }
                            }
                        }
                        let run = TextRun { len: text.len(), font: font.clone(), color: white(), background_color: None, underline: None, strikethrough: None };
                        let Ok(shaped) = window.text_system().shape_text(text.into(), px(size), &[run], Some(px(text_w)), None) else { continue };
                        placed.clear();
                        let mut oy = place(&shaped, text_origin, px(main_lh), align, px(text_w), &mut placed);
                        let ink_of = |p: &Placed| inks[letter.get(p.index).copied().unwrap_or(0) as usize];
                        // Each popping word's middle, on its first row: what it grows around.
                        let mut pivots: Vec<Option<(Pixels, Pixels, Pixels)>> = vec![None; line.groups.len()];
                        if pops.iter().any(|p| p.abs() > 1e-3) {
                            for p in &placed {
                                let w = ink_of(p).word;
                                if pops[w].abs() <= 1e-3 {
                                    continue;
                                }
                                let e = pivots[w].get_or_insert((p.at.x, p.at.x + p.advance, p.row_top));
                                if p.row_top == e.2 {
                                    e.0 = e.0.min(p.at.x);
                                    e.1 = e.1.max(p.at.x + p.advance);
                                }
                            }
                        }
                        // A grown word pushes the others on its row aside (the phone's words have
                        // room around them; these share the spaces), keeping the aligned edge put:
                        // left-aligned rows grow to the right, centred ones both ways.
                        let k_align = match align {
                            TextAlign::Left => 0.,
                            TextAlign::Center => 0.5,
                            TextAlign::Right => 1.,
                        };
                        let push = |x: Pixels, row: Pixels| -> Pixels {
                            let mut d = px(0.);
                            for (w, e) in pivots.iter().enumerate() {
                                if let Some((x0, x1, top)) = *e {
                                    if top == row {
                                        let grow = (x1 - x0) * (0.13 * pops[w]);
                                        d += grow * if x < x0 { -k_align } else if x >= x1 { 1. - k_align } else { 0.5 - k_align };
                                    }
                                }
                            }
                            d
                        };
                        // The glows first, under every letter; then the letters.
                        for glow_pass in [true, false] {
                            for p in &placed {
                                let k = ink_of(p);
                                let pw = pops[k.word];
                                let dx = push(p.at.x, p.row_top);
                                let (scale, lift, pivot) = match pivots[k.word] {
                                    Some((x0, x1, top)) => {
                                        let lift = px(2. * s * pw);
                                        (1. + 0.13 * pw, lift, point((x0 + x1) / 2. + dx, top + px(main_lh / 2.) - lift))
                                    }
                                    None => (1., px(0.), p.at),
                                };
                                let at = point(p.at.x + dx, p.at.y - lift);
                                if p.emoji {
                                    if !glow_pass {
                                        let _ = window.paint_emoji(at, p.font, p.glyph, px(size));
                                    }
                                } else if glow_pass {
                                    let g = k.glow.max(0.7 * pw.clamp(0., 1.)) * alpha;
                                    if g > 0.004 {
                                        let _ = window.paint_glyph_fx(at, p.font, p.glyph, px(size), ink(g), glow_sigma, scale, pivot);
                                    }
                                } else {
                                    let _ = window.paint_glyph_fx(at, p.font, p.glyph, px(size), ink(k.alpha * alpha), blur, scale, pivot);
                                }
                            }
                        }
                        if let Some(bg) = &line.bg {
                            // `.bgv`: 23% of the ink, 78% while the line is sung, 330 ms between.
                            let p = (((now - st.on[i].1) / 330.).clamp(0., 1.)) as f32;
                            let (from, to) = if on { (0.23, 0.78) } else { (0.78, 0.23) };
                            let a = from + (to - from) * p;
                            let run = TextRun { len: bg.len(), font: bg_font.clone(), color: white(), background_color: None, underline: None, strikethrough: None };
                            if let Ok(shaped) = window.text_system().shape_text(SharedString::from(bg.clone()), px(bg_size), &[run], Some(px(text_w)), None) {
                                oy += px(4.);
                                placed.clear();
                                place(&shaped, point(text_origin.x, oy), px(bg_lh), align, px(text_w), &mut placed);
                                for p in &placed {
                                    let _ = if p.emoji {
                                        window.paint_emoji(p.at, p.font, p.glyph, px(bg_size))
                                    } else {
                                        window.paint_glyph_fx(p.at, p.font, p.glyph, px(bg_size), ink(a * alpha), blur, 1., p.at)
                                    };
                                }
                            }
                        }
                    }
                    // "Testo da …", riding with the last line.
                    let y = last_y as f32 + 24.;
                    if show_source && y < view_h {
                        let run = TextRun { len: source.len(), font: Font { weight: FontWeight::NORMAL, ..font.clone() }, color: ink(0.5 * fog(y + 8.)), background_color: None, underline: None, strikethrough: None };
                        if let Ok(shaped) = window.text_system().shape_text(source.clone(), px(12.), &[run], None, None) {
                            for l in &shaped {
                                let at = point(origin.x + px(PAD_X), origin.y + px(y));
                                let _ = l.paint(at, px(16.), align, Some(Bounds::new(at, gpui_kit::size(px(text_w), px(16.)))), window, cx);
                            }
                        }
                    }
                });

                // The next frame: while the song plays, the list moves or just woke.
                let busy = playing_now
                    || st.scroll.moving()
                    || now - st.woke < 900.
                    || st.fog_top.moving(now)
                    || st.fog_bottom.moving(now)
                    || now - st.browsed < BROWSE_MS + 50.
                    || st.blur.iter().any(|b| b.moving(now));
                // At most 60 a second, whatever the monitor's rate (a 180 Hz screen would draw
                // three times the frames for a sweep nobody can tell apart); behind another
                // window (a game), four.
                if busy && !st.timer {
                    st.timer = true;
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
                            .child(nudge("lyr-later", "+".into(), 250., 30., false))
                            // The side panel's way to the page: a rule, then "expand".
                            .when_some(self.nav.clone().filter(|_| side), |d, nav| {
                                d.child(div().w(px(1.)).h(px(16.)).mx(px(2.)).bg(crate::track_row::separator(&self.look.read(cx).scheme)))
                                    .child(
                                        div()
                                            .id("lyr-expand")
                                            .min_w(px(30.))
                                            .h(px(28.))
                                            .px(px(8.))
                                            .flex()
                                            .items_center()
                                            .justify_center()
                                            .rounded_full()
                                            .cursor_pointer()
                                            .hover(|d| d.bg(ink(0.1)))
                                            .on_click(move |_, _, cx| {
                                                cx.stop_propagation();
                                                nav.update(cx, |n, cx| n.go(crate::nav::Route::Lyrics, cx));
                                            })
                                            .child(icon("g-expand", 13., text)),
                                    )
                            }),
                    ),
            );

        let root = div()
            .id("lyrics")
            .relative()
            .when(side, |d| d.size_full())
            .when(!side, |d| d.absolute().top_0().bottom_0().left(px(-BLEED)).right(px(-BLEED)))
            .overflow_hidden()
            .on_hover(move |hovered, _, cx| {
                let _ = me1.update(cx, |this, cx| {
                    this.hovered = *hovered;
                    cx.notify();
                });
            })
            .on_mouse_move(move |e, _, cx| {
                let _ = me2.update(cx, |this, cx| {
                    let now = this.now();
                    let mut st = m1.borrow_mut();
                    let over = st.rects.iter().position(|r| r.contains(&e.position));
                    let woke = now - st.browsed >= BROWSE_MS;
                    st.browsed = now;
                    if woke || over != st.hovered {
                        drop(st);
                        cx.notify();
                    }
                });
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
            .child(sync);
        if side {
            root.into_any_element()
        } else {
            div().relative().size_full().child(root).into_any_element()
        }
    }
}
