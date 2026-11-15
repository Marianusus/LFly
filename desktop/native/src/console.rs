//! The Console page: two decks, a mixer and a browser, laid out like DJ software (the user's
//! reference is VirtualDJ's Pro layout; drawn here with LFly's own pieces, no third-party art).
//!
//! The decks are real and independent of the music player's queue (`deck_model`, over the
//! two-deck engine `lfly_audio::decks`): a song is loaded on a deck from the browser (the A and B
//! buttons, or a double click for the free deck), decoded whole, its tempo, grid and key read.
//! Each deck has transport with CDJ cue behaviour, a draggable pitch fader with ranges and bend,
//! a platter that scratches (vinyl) or bends and searches (CD), slip, sync, eight pads (hot cues,
//! loop rolls, loops, beat jumps, brake, spinback, censor, reverse, tempo ×2 ÷2, tap, eject), an
//! auto loop with halving, doubling, in, out and reloop, and three effect slots. The mixer has
//! gain, three kill bands and a filter for each deck, the channel faders with meters, the
//! crossfader with its curves, the master with its meter and the recording of the mix.
//!
//! Not built: headphone cueing (it needs a second output), stems, the sampler, video, keylock.
//!
//! What moves (waveforms, platters, clocks, meters) lives in [`Decks`], redrawn about 30 times a
//! second only while it is on screen and something moves; the browser is its own cached view.

use std::cell::Cell;
use std::ops::Range;
use std::rc::Rc;
use std::sync::Arc;
use std::time::{Duration, Instant};

use gpui_kit::*;
use gpui_kit::prelude::FluentBuilder as _;
use lfly_audio::decks::FX_SLOTS;
use lfly_connect::protocol::{self, TrackRef};
use lfly_yt::Track;
use serde_json::Value;

use crate::covers::Covers;
use crate::deck_model::{beats_label, Deck, DjDecks, PadMode, Status, HOT_CUES, PEAKS_PER_SECOND};
use crate::i18n::tr;
use crate::icons::icon;
use crate::library_model::LibraryModel;
use crate::player_model::PlayerModel;

/// The few words in Italian. DJ terms (CUE, SYNC, GAIN, FILTER, IN, OUT, the pads) stay as every
/// console prints them.
mod words {
    pub const EMPTY: &str = crate::i18n::t("Nessun brano");
    pub const LOAD_HINT: &str = crate::i18n::t("Carica un brano con A o B nella lista");
    pub const LOADING: &str = crate::i18n::t("Caricamento…");
    pub const FAILED: &str = crate::i18n::t("Non caricato");
    pub const ELAPSED: &str = crate::i18n::t("TRASCORSO");
    pub const REMAIN: &str = crate::i18n::t("RIMANENTE");
    pub const TOTAL: &str = crate::i18n::t("DURATA");
    pub const LIKED: &str = crate::i18n::t("Brani che ti piacciono");
    pub const DOWNLOADS: &str = crate::i18n::t("Scaricati");
    pub const HISTORY: &str = crate::i18n::t("Cronologia");
    pub const PLAYLISTS: &str = crate::i18n::t("Playlist#plurale");
    pub const TITLE: &str = crate::i18n::t("Titolo");
    pub const ARTIST: &str = crate::i18n::t("Artista");
    pub const ALBUM: &str = crate::i18n::t("Album");
    pub const LENGTH: &str = crate::i18n::t("Durata");
    pub const UP_NEXT: &str = crate::i18n::t("IN CODA");
    pub const NOTHING_NEXT: &str = crate::i18n::t("Niente in coda");
    pub const NO_SONGS: &str = crate::i18n::t("Nessun brano qui");
    pub const SEARCH_HINT: &str = crate::i18n::t("Cerca nella lista, Invio per cercare online");
    pub const SEARCH_ONLINE: &str = crate::i18n::t("Cerca online");
    pub const SEARCHING: &str = crate::i18n::t("Ricerca…");
    pub const SEARCH_FAILED: &str = crate::i18n::t("Ricerca non riuscita");
    pub const RESULTS: &str = crate::i18n::t("Risultati");
    pub const FOLDERS: &str = crate::i18n::t("CARTELLE");
    pub const ADD_FOLDER: &str = crate::i18n::t("Aggiungi cartella");
    pub const FOLDER_MISSING: &str = crate::i18n::t("Cartella non trovata (disco scollegato?)");
    pub const NOTHING_FOUND: &str = crate::i18n::t("Nessun brano corrisponde");
}

// ---- The console's own dark palette (DJ software is dark whatever the theme) ----

const BG: u32 = 0x131417;
const PANEL: u32 = 0x1b1d21;
const PANEL2: u32 = 0x24272c;
const LINE: u32 = 0x31343a;
const TEXT: u32 = 0xd8dade;
const TEXT2: u32 = 0x8c919a;
const TEXT3: u32 = 0x5d626b;
/// Deck A blue, deck B red.
const DECK: [u32; 2] = [0x2fa8ff, 0xff4148];
/// The hot cues' own colours, as DJ software gives each slot one.
const CUE_COLORS: [u32; HOT_CUES] = [0xff3b3b, 0xff9a1f, 0xffe03a, 0x3ddc84, 0x2ee6e6, 0x3d8bff, 0xa66bff, 0xff5fc8];
const CUE_MAIN: u32 = 0xff8c00;

fn c(hex: u32) -> Hsla {
    rgb(hex).into()
}

fn deck_color(deck: usize) -> Hsla {
    c(DECK[deck])
}

/// "0:48.5", as decks show time.
pub(crate) fn tenths(ms: f64) -> String {
    let ms = ms.max(0.) as u64;
    format!("{}:{:02}.{}", ms / 60_000, ms / 1000 % 60, ms / 100 % 10)
}

fn clock(ms: i64) -> String {
    let s = ms.max(0) / 1000;
    format!("{}:{:02}", s / 60, s % 60)
}

/// The page: the decks over the browser, on the console's own panel.
pub struct Console {
    decks: Entity<Decks>,
    browser: Entity<Browser>,
    pub page_width: f32,
    pub height: f32,
}

impl Console {
    pub fn new(core: Arc<crate::core::Core>, model: Entity<PlayerModel>, covers: Entity<Covers>, library: Entity<LibraryModel>, window: &mut Window, cx: &mut Context<Self>) -> Self {
        let dj = DjDecks::entity(cx);
        let decks = cx.new(|cx| Decks::new(dj.clone(), covers, cx));
        let browser = cx.new(|cx| Browser::new(core, model, library, dj, window, cx));
        Self { decks, browser, page_width: 900., height: 720. }
    }
}

impl Render for Console {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        // Under the top bar (72) and over the player bar (104), as the other pages.
        let inner_w = (self.page_width - 24.).max(320.);
        let inner_h = (self.height - 72. - 104.).max(300.);
        // The decks take most of the room, the browser the rest.
        let decks_h = (inner_h * 0.62).clamp(300., 470.);
        self.decks.update(cx, |d, cx| {
            if crate::put(&mut d.width, inner_w) | crate::put(&mut d.height, decks_h) {
                cx.notify();
            }
        });
        self.browser.update(cx, |b, cx| {
            if crate::put(&mut b.width, inner_w) {
                cx.notify();
            }
        });
        div()
            .size_full()
            .pt(px(72.))
            .pb(px(104.))
            .px(px(12.))
            .child(
                div()
                    .size_full()
                    .flex()
                    .flex_col()
                    .rounded(px(12.))
                    .overflow_hidden()
                    .bg(c(BG))
                    .border_1()
                    .border_color(c(LINE))
                    .text_color(c(TEXT))
                    .child(div().h(px(decks_h)).flex_none().child(self.decks.clone().cached(StyleRefinement::default().size_full())))
                    .child(div().flex_1().min_h_0().border_t_1().border_color(c(LINE)).child(self.browser.clone().cached(StyleRefinement::default().size_full()))),
            )
    }
}

// ---- The decks and the mixer ----

/// What the pointer holds: a control being turned or dragged, or a button held down.
#[derive(Clone, Copy, PartialEq, Debug)]
enum Ctl {
    /// Deck, knob (0 gain, 1 high, 2 mid, 3 low, 4 filter).
    Knob(usize, usize),
    Volume(usize),
    Pitch(usize),
    Xfader,
    Master,
    MasterFx,
    SamplerVol,
    Fx(usize, usize),
    Platter(usize),
    Cue(usize),
    Bend(usize),
    Pad(usize),
}

struct Drag {
    ctl: Ctl,
    last: Point<Pixels>,
    at: Instant,
    /// Pixels for the control's whole travel.
    span: f32,
}

/// Where an element was laid out last frame.
type Spot = Rc<Cell<Bounds<Pixels>>>;

fn spot_mark(spot: Spot) -> impl IntoElement {
    canvas(move |b, _, _| spot.set(b), |_, _, _, _| {}).absolute().inset_0()
}

pub struct Decks {
    dj: Entity<DjDecks>,
    covers: Entity<Covers>,
    width: f32,
    height: f32,
    rendered_at: Instant,
    ticking: bool,
    drag: Option<Drag>,
    dragging: Rc<Cell<bool>>,
    platters: [Spot; 2],
    overviews: [Spot; 2],
    /// Seconds of song each side of the waveforms' playhead.
    zoom: f64,
    _subs: Vec<Subscription>,
}

impl Decks {
    fn new(dj: Entity<DjDecks>, covers: Entity<Covers>, cx: &mut Context<Self>) -> Self {
        let subs = vec![cx.observe(&dj, |_, _, cx| cx.notify()), cx.observe(&covers, |_, _, cx| cx.notify())];
        Self {
            dj,
            covers,
            width: 900.,
            height: 380.,
            rendered_at: Instant::now(),
            ticking: false,
            drag: None,
            dragging: Rc::default(),
            platters: Default::default(),
            overviews: Default::default(),
            zoom: 6.,
            _subs: subs,
        }
    }

    /// About 30 frames a second while the page is shown and something on the decks moves.
    fn keep_moving(&mut self, cx: &mut Context<Self>) {
        if self.ticking {
            return;
        }
        self.ticking = true;
        cx.spawn(async move |this, cx| loop {
            cx.background_executor().timer(Duration::from_millis(33)).await;
            let go_on = this
                .update(cx, |d, cx| {
                    if let Some(Drag { ctl: Ctl::Platter(n), .. }) = d.drag {
                        d.dj.update(cx, |dj, _| dj.jog_rest(n));
                    }
                    let on = (d.dj.read(cx).moving() || d.drag.is_some()) && d.rendered_at.elapsed() < Duration::from_millis(500);
                    if on {
                        cx.notify();
                    } else {
                        d.ticking = false;
                    }
                    on
                })
                .unwrap_or(false);
            if !go_on {
                break;
            }
        })
        .detach();
    }

    /// The pointer went down on [ctl].
    fn press(&mut self, ctl: Ctl, at: Point<Pixels>, span: f32, cx: &mut Context<Self>) {
        self.drag = Some(Drag { ctl, last: at, at: Instant::now(), span: span.max(1.) });
        self.dragging.set(true);
        self.dj.update(cx, |dj, cx| match ctl {
            Ctl::Cue(d) => dj.cue_down(d, cx),
            Ctl::Platter(d) => dj.jog_touch(d, cx),
            _ => {}
        });
        self.keep_moving(cx);
        cx.notify();
    }

    fn drag_to(&mut self, at: Point<Pixels>, cx: &mut Context<Self>) {
        let Some(drag) = self.drag.as_mut() else { return };
        let dx = (at.x - drag.last.x) / px(1.);
        let dy = (at.y - drag.last.y) / px(1.);
        let secs = drag.at.elapsed().as_secs_f64();
        let (ctl, span, last) = (drag.ctl, drag.span, drag.last);
        drag.last = at;
        drag.at = Instant::now();
        let center = |spot: &Spot| {
            let b = spot.get();
            point(b.origin.x + b.size.width / 2., b.origin.y + b.size.height / 2.)
        };
        let angle = |p: Point<Pixels>, c: Point<Pixels>| ((p.y - c.y) / px(1.)).atan2((p.x - c.x) / px(1.)) as f64;
        let platter = if let Ctl::Platter(d) = ctl { Some(center(&self.platters[d])) } else { None };
        self.dj.update(cx, |dj, cx| match ctl {
            Ctl::Knob(d, k) => {
                let v = dj.decks[d].knobs[k] - dy / span;
                dj.set_knob(d, k, v, cx);
            }
            Ctl::Volume(d) => {
                let v = dj.decks[d].volume - dy / span;
                dj.set_volume(d, v, cx);
            }
            Ctl::Pitch(d) => {
                let v = dj.decks[d].fader + 2. * dy / span;
                dj.set_pitch(d, v, cx);
            }
            Ctl::Xfader => {
                let v = dj.xfader + dx / span;
                dj.set_xfader(v, cx);
            }
            Ctl::Master => {
                let v = dj.master - dy / span;
                dj.set_master(v, cx);
            }
            Ctl::MasterFx => {
                let v = dj.master_fx_amount - dy / span;
                dj.set_master_fx_amount(v, cx);
            }
            Ctl::SamplerVol => {
                let v = dj.sampler_volume - dy / span;
                dj.set_sampler_volume(v, cx);
            }
            Ctl::Fx(d, s) => {
                let v = dj.decks[d].fx_amount[s] + dx / span;
                dj.fx_amount(d, s, v, cx);
            }
            Ctl::Platter(d) => {
                if let Some(c) = platter {
                    let mut turn = angle(at, c) - angle(last, c);
                    if turn > std::f64::consts::PI {
                        turn -= std::f64::consts::TAU;
                    } else if turn < -std::f64::consts::PI {
                        turn += std::f64::consts::TAU;
                    }
                    dj.jog_move(d, turn / std::f64::consts::TAU, secs, cx);
                }
            }
            Ctl::Cue(_) | Ctl::Bend(_) | Ctl::Pad(_) => {}
        });
    }

    fn release(&mut self, cx: &mut Context<Self>) {
        let Some(drag) = self.drag.take() else { return };
        self.dragging.set(false);
        self.dj.update(cx, |dj, cx| match drag.ctl {
            Ctl::Cue(d) => dj.cue_up(d, cx),
            Ctl::Bend(d) => dj.bend(d, 0., cx),
            Ctl::Pad(d) => dj.pad_up(d, cx),
            Ctl::Platter(d) => dj.jog_release(d, cx),
            _ => {}
        });
        cx.notify();
    }

    /// A deck's piece of the page takes a song dropped on it: from the browser, or an audio file
    /// from the file manager. The wheel over it zooms the waveforms.
    fn dropzone(&self, deck: usize, cx: &Context<Self>) -> Div {
        div()
            .flex_none()
            .drag_over::<DraggedTrack>(move |st, _, _, _| st.bg(deck_color(deck).opacity(0.14)))
            .drag_over::<ExternalPaths>(move |st, _, _, _| st.bg(deck_color(deck).opacity(0.14)))
            .on_drop(cx.listener(move |this, d: &DraggedTrack, _, cx| {
                let t = d.track.clone();
                this.dj.update(cx, |dj, cx| dj.load(deck, t, cx));
            }))
            .on_drop(cx.listener(move |this, paths: &ExternalPaths, _, cx| {
                if let Some(p) = paths.paths().iter().find(|p| crate::local_music::is_audio(p)) {
                    if let Some(r) = crate::core::track_ref(&crate::local_music::track_of(p)) {
                        this.dj.update(cx, |dj, cx| dj.load(deck, r, cx));
                    }
                }
            }))
            .on_scroll(cx.listener(|this, e: &ScrollEvent, _, cx| {
                let dy = e.delta.pixel_delta(px(20.)).y / px(1.);
                if dy.abs() > 0.5 {
                    this.zoom = (this.zoom * if dy > 0. { 1. / 1.15 } else { 1.15 }).clamp(1.5, 30.);
                    cx.notify();
                }
            }))
    }

    /// Mouse down on an element starts [ctl] (with [span] pixels for its travel).
    fn grab(&self, ctl: Ctl, span: f32, cx: &Context<Self>) -> impl Fn(&MouseDownEvent, &mut Window, &mut App) + 'static {
        let me = cx.entity().downgrade();
        move |e: &MouseDownEvent, _: &mut Window, cx: &mut App| {
            cx.stop_propagation();
            let _ = me.update(cx, |this, cx| this.press(ctl, e.position, span, cx));
        }
    }
}

/// While a control is held, the pointer is heard over the whole window (as a slider's drag).
fn tracker(me: WeakEntity<Decks>, dragging: Rc<Cell<bool>>) -> impl IntoElement {
    canvas(
        |_, _, _| {},
        move |_, _, window, _| {
            let (m1, m2, d1, d2) = (me.clone(), me, dragging.clone(), dragging);
            window.on_mouse_event(move |e: &MouseMoveEvent, phase, _, cx| {
                if phase == DispatchPhase::Bubble && d1.get() {
                    if e.pressed_button == Some(MouseButton::Left) {
                        let _ = m1.update(cx, |d, cx| d.drag_to(e.position, cx));
                    } else {
                        let _ = m1.update(cx, |d, cx| d.release(cx));
                    }
                }
            });
            window.on_mouse_event(move |e: &MouseUpEvent, phase, _, cx| {
                if phase == DispatchPhase::Bubble && e.button == MouseButton::Left && d2.get() {
                    let _ = m2.update(cx, |d, cx| d.release(cx));
                }
            });
        },
    )
    .absolute()
    .inset_0()
}

impl Render for Decks {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        self.rendered_at = Instant::now();
        if self.dj.read(cx).moving() {
            self.keep_moving(cx);
        }
        let dj = self.dj.read(cx);
        let covers: [Option<String>; 2] = [0, 1].map(|i| dj.decks[i].track.as_ref().and_then(|t| t.cover_url.clone()));
        let covers = covers.map(|u| u.and_then(|u| self.covers.update(cx, |c, cx| c.get(&u, cx))));
        let dj = self.dj.read(cx);
        let (w, h) = (self.width, self.height);
        let strip_h = (h * 0.1).clamp(30., 52.);
        let head_h = 48.;
        let over_h = 18.;
        let body_h = (h - 2. * strip_h - head_h - over_h - 4.).max(170.);
        let mixer_w = (w * 0.2).clamp(190., 240.);
        let deck_w = (w - mixer_w) / 2.;
        let pos = [dj.position(0), dj.position(1)];

        let strips = div()
            .flex_none()
            .flex()
            .flex_col()
            .gap(px(2.))
            .bg(c(0x0c0d0f))
            .py(px(1.))
            .child(self.dropzone(0, cx).child(strip(&dj.decks[0], 0, pos[0], self.zoom, w, strip_h)))
            .child(self.dropzone(1, cx).child(strip(&dj.decks[1], 1, pos[1], self.zoom, w, strip_h)));
        let heads = div()
            .flex_none()
            .h(px(head_h))
            .flex()
            .border_b_1()
            .border_color(c(LINE))
            .child(self.dropzone(0, cx).h_full().child(self.head(dj, 0, covers[0].clone(), deck_w, cx)))
            .child(div().w(px(mixer_w)).flex_none().h_full().flex().items_center().justify_center().child(self.beat_lights(dj, cx)))
            .child(self.dropzone(1, cx).h_full().child(self.head(dj, 1, covers[1].clone(), deck_w, cx)));
        let overviews = div()
            .flex_none()
            .h(px(over_h))
            .flex()
            .bg(c(0x101114))
            .border_b_1()
            .border_color(c(LINE))
            .child(self.dropzone(0, cx).child(self.overview(dj, 0, pos[0], deck_w, over_h, cx)))
            .child(div().w(px(mixer_w)).flex_none())
            .child(self.dropzone(1, cx).child(self.overview(dj, 1, pos[1], deck_w, over_h, cx)));
        let body = div()
            .flex_1()
            .min_h_0()
            .flex()
            .child(self.dropzone(0, cx).h_full().child(self.deck_body(dj, 0, covers[0].clone(), deck_w, body_h, cx)))
            .child(self.mixer(dj, mixer_w, body_h, cx))
            .child(self.dropzone(1, cx).h_full().child(self.deck_body(dj, 1, covers[1].clone(), deck_w, body_h, cx)));
        div()
            .relative()
            .size_full()
            .flex()
            .flex_col()
            .bg(c(PANEL))
            .child(strips)
            .child(heads)
            .child(overviews)
            .child(body)
            .child(tracker(cx.entity().downgrade(), self.dragging.clone()))
    }
}

/// The scrolling waveform of one deck: the song passing the playhead in the middle, louder parts
/// taller, bass-heavy parts in the deck's colour; the beat grid, the cue points and the loop.
fn strip(d: &Deck, deck: usize, pos: f64, half: f64, w: f32, h: f32) -> impl IntoElement {
    let col = deck_color(deck);
    let peaks = d.peaks.clone();
    let grid = d.grid;
    let hot = d.hot;
    let cue = d.cue;
    let looped = d.looped;
    let dur = d.duration();
    let loaded = d.buf.is_some();
    canvas(
        |_, _, _| {},
        move |b, _, window, _| {
            let (x0, y0) = (b.origin.x, b.origin.y);
            let width = b.size.width / px(1.);
            let height = b.size.height / px(1.);
            let mid = height / 2.;
            // [half] seconds each side of the playhead (the wheel zooms).
            let px_per_s = (width / 2.) / half as f32;
            let x_of = |t: f64| width / 2. + ((t - pos) as f32) * px_per_s;
            let rect = |x: f32, y: f32, ww: f32, hh: f32, color: Hsla, window: &mut Window| {
                window.paint_quad(fill(Bounds::new(point(x0 + px(x), y0 + px(y)), size(px(ww), px(hh))), color));
            };
            if loaded {
                if let Some((a, b2)) = looped {
                    let (xa, xb) = (x_of(a).max(0.), x_of(b2).min(width));
                    if xb > xa {
                        rect(xa, 0., xb - xa, height, rgb(0x2a7a3a).into(), window);
                    }
                }
                // The waveform, two pixels a column.
                let n = peaks.len();
                let mut x = 0.;
                while x < width {
                    let t = pos + ((x - width / 2.) / px_per_s) as f64;
                    if t >= 0. && t < dur {
                        let i = (t * PEAKS_PER_SECOND as f64) as usize;
                        if i < n {
                            let [amp, bass] = peaks[i];
                            let a = amp as f32 / 255.;
                            let bh = (a * (height - 4.)).max(1.);
                            let k = bass as f32 / 255.;
                            let color = col.opacity(0.35 + 0.65 * k);
                            let shade: Hsla = if x < width / 2. { color.opacity(0.55) } else { color };
                            rect(x, mid - bh / 2., 2., bh, shade, window);
                        }
                    }
                    x += 2.;
                }
                if let Some(g) = grid {
                    let first = g.beat_at(pos - half).floor() as i64;
                    let last = g.beat_at(pos + half).ceil() as i64;
                    for k in first..=last {
                        let t = g.time_of(k as f64);
                        if t < 0. || t > dur {
                            continue;
                        }
                        let x = x_of(t);
                        let bar = (k + g.first_in_bar as i64).rem_euclid(4) == 0;
                        let (th, color) = if bar { (height, hsla(0., 0., 1., 0.55)) } else { (height * 0.3, hsla(0., 0., 1., 0.3)) };
                        rect(x, 0., 1., th * 0.5, color, window);
                        rect(x, height - th * 0.5, 1., th * 0.5, color, window);
                    }
                }
                let marker = |t: f64, color: Hsla, window: &mut Window| {
                    let x = x_of(t);
                    if (0. ..=width).contains(&x) {
                        rect(x - 1., 0., 2., height, color, window);
                        rect(x - 4., 0., 8., 4., color, window);
                    }
                };
                marker(cue, rgb(CUE_MAIN).into(), window);
                for (i, h) in hot.iter().enumerate() {
                    if let Some(t) = h {
                        marker(*t, rgb(CUE_COLORS[i]).into(), window);
                    }
                }
            }
            rect(width / 2. - 1., 0., 2., height, hsla(0., 0., 1., 0.9), window);
        },
    )
    .w(px(w))
    .h(px(h))
}

/// A rounded label button.
fn pill(label: impl Into<SharedString>, w: f32, h: f32, on: Option<Hsla>) -> Div {
    div()
        .w(px(w))
        .h(px(h))
        .flex_none()
        .flex()
        .items_center()
        .justify_center()
        .rounded(px(4.))
        .bg(on.map_or(c(PANEL2), |o| o.opacity(0.22)))
        .border_1()
        .border_color(on.map_or(c(LINE), |o| o.opacity(0.6)))
        .text_size(px(9.5))
        .font_weight(FontWeight::SEMIBOLD)
        .text_color(on.unwrap_or(c(TEXT2)))
        .whitespace_nowrap()
        .overflow_hidden()
        .child(label.into())
}

/// A knob: a disc and its mark, at [value] (0..1, 0.5 in the middle), drawn turned from 7 to 5 o'clock.
fn knob(size: f32, value: f32, mark: Hsla) -> Div {
    let angle = (-135. + 270. * value.clamp(0., 1.)).to_radians();
    let r = size / 2. - 4.;
    let (x, y) = (size / 2. + r * angle.sin() - 1.5, size / 2. - r * angle.cos() - 1.5);
    div()
        .relative()
        .size(px(size))
        .flex_none()
        .rounded_full()
        .bg(c(0x2b2e34))
        .border_1()
        .border_color(c(0x3d4148))
        .cursor(CursorStyle::ResizeUpDown)
        .child(div().absolute().left(px(x)).top(px(y)).size(px(3.)).rounded_full().bg(mark))
}

/// A vertical fader, its cap at [value] (0 bottom, 1 top).
fn fader(h: f32, value: f32, cap: Hsla) -> Div {
    let cap_h = 9.;
    let top = (h - cap_h) * (1. - value.clamp(0., 1.));
    div()
        .relative()
        .w(px(22.))
        .h(px(h))
        .flex_none()
        .cursor(CursorStyle::ResizeUpDown)
        .child(div().absolute().left(px(9.5)).w(px(3.)).top_0().bottom_0().rounded_full().bg(c(0x0b0c0e)))
        .child(div().absolute().left(px(10.)).w(px(2.)).top(px(h / 2. - 0.5)).h(px(1.)).bg(c(TEXT3)))
        .child(div().absolute().left_0().w(px(22.)).top(px(top)).h(px(cap_h)).rounded(px(2.)).bg(c(0x4a4e56)).child(div().mt(px(4.)).h(px(1.)).bg(cap)))
}

/// A level meter, lit from the bottom (green, amber, red at the top).
fn meter(h: f32, level: f32) -> impl IntoElement {
    let segs = 12;
    let lit = ((level.clamp(0., 1.)).sqrt() * segs as f32).round() as usize;
    div().w(px(5.)).h(px(h)).flex().flex_col_reverse().gap(px(1.)).children((0..segs).map(move |i| {
        let color = if i >= 11 { 0xff3b3b } else if i >= 9 { 0xffb020 } else { 0x37d67a };
        div().flex_1().w_full().rounded(px(1.)).bg(if i < lit { c(color) } else { c(0x23262b) })
    }))
}

impl Decks {
    /// Each deck's place in its 16-beat phrase: the bar (1-4) and the beat lit; a click puts
    /// that deck on the other's place in the phrase.
    fn beat_lights(&self, dj: &DjDecks, cx: &Context<Self>) -> impl IntoElement {
        let row = |d: usize| {
            let at = dj.phrase(d);
            div()
                .id(("phrase", d))
                .flex()
                .items_center()
                .gap(px(3.))
                .cursor_pointer()
                .on_click(cx.listener(move |this, _, _, cx| this.dj.update(cx, |dj, cx| dj.phrase_sync(d, cx))))
                .child(div().w(px(18.)).text_size(px(8.)).text_color(deck_color(d)).child(at.map_or("--".to_string(), |(bar, _)| format!("{}/4", bar + 1))))
                .children((0..4).map(move |i| {
                    div().w(px(14.)).h(px(5.)).rounded(px(1.)).bg(if at.map(|a| a.1) == Some(i) { deck_color(d) } else { c(PANEL2) })
                }))
        };
        div().flex().flex_col().gap(px(4.)).items_center().child(row(0)).child(row(1))
    }

    /// The deck's title row: its letter, cover, title and artist on the outside; key, tempo with
    /// the pitch and the time towards the mixer.
    fn head(&self, dj: &DjDecks, deck: usize, cover: Option<Arc<Image>>, w: f32, cx: &Context<Self>) -> impl IntoElement {
        let d = &dj.decks[deck];
        let col = deck_color(deck);
        let right = deck == 1;
        let playing = dj.is_playing(deck);
        let letter = div()
            .w(px(16.))
            .h_full()
            .flex_none()
            .flex()
            .items_center()
            .justify_center()
            .bg(col.opacity(if playing { 0.9 } else { 0.25 }))
            .text_size(px(11.))
            .font_weight(FontWeight::BOLD)
            .text_color(if playing { c(0x000000) } else { c(TEXT) })
            .child(if deck == 0 { "A" } else { "B" });
        let art = div().size(px(36.)).flex_none().rounded(px(3.)).bg(c(PANEL2)).children(cover.map(|i| img(i).size_full().rounded(px(3.)).object_fit(ObjectFit::Cover)));
        let (title, artist) = match (&d.track, &d.status) {
            (None, _) => (tr(words::EMPTY).to_string(), tr(words::LOAD_HINT).to_string()),
            (Some(t), Status::Failed(_)) => (t.title.clone(), tr(words::FAILED).to_string()),
            (Some(t), Status::Loading) => (t.title.clone(), tr(words::LOADING).to_string()),
            (Some(t), _) => (t.title.clone(), t.artist.clone()),
        };
        let names = div()
            .flex_1()
            .min_w_0()
            .flex()
            .flex_col()
            .when(right, |d| d.items_end())
            .child(div().w_full().text_size(px(13.)).font_weight(FontWeight::SEMIBOLD).text_ellipsis().whitespace_nowrap().overflow_hidden().when(right, |d| d.text_right()).child(title))
            .child(div().w_full().text_size(px(11.)).text_color(c(TEXT2)).text_ellipsis().whitespace_nowrap().overflow_hidden().when(right, |d| d.text_right()).child(artist));
        let pitch = (d.ratio() - 1.) * 100.;
        let pos = dj.position(deck);
        let dur = d.duration();
        let bpm = match (d.bpm(), d.analysing) {
            (Some(b), _) => format!("{b:.2}"),
            (None, true) => "…".to_string(),
            (None, false) => "---.--".to_string(),
        };
        let key = d.key_shown().unwrap_or_else(|| "--".into());
        let time = match d.time {
            1 => format!("-{}", tenths((dur - pos).max(0.) * 1000.)),
            2 => tenths(dur * 1000.),
            _ => tenths(pos * 1000.),
        };
        let readout = div()
            .flex_none()
            .flex()
            .items_center()
            .gap(px(10.))
            .px(px(8.))
            .when(right, |d| d.flex_row_reverse())
            .child(
                div()
                    .id(("key", deck))
                    .flex()
                    .flex_col()
                    .items_center()
                    .cursor_pointer()
                    .on_click(cx.listener(move |this, _, _, cx| this.dj.update(cx, |dj, cx| dj.toggle_key(deck, cx))))
                    .child(div().text_size(px(7.5)).text_color(c(TEXT3)).child("KEY"))
                    .child(div().text_size(px(12.)).font_weight(FontWeight::SEMIBOLD).child(key)),
            )
            .child(
                div()
                    .flex()
                    .flex_col()
                    .items_center()
                    .child(div().text_size(px(19.)).font_weight(FontWeight::BOLD).text_color(if d.grid.is_some() { col } else { c(TEXT3) }).child(bpm))
                    .child(div().text_size(px(8.)).text_color(c(TEXT3)).child(format!("{}{:.2}%", if pitch >= 0. { "+" } else { "" }, pitch))),
            )
            .child(
                div()
                    .id(("time", deck))
                    .w(px(62.))
                    .flex()
                    .flex_col()
                    .items_center()
                    .cursor_pointer()
                    .when(w < 360., |d| d.hidden())
                    .on_click(cx.listener(move |this, _, _, cx| this.dj.update(cx, |dj, cx| dj.toggle_time(deck, cx))))
                    .child(div().text_size(px(7.5)).text_color(c(TEXT3)).child(match d.time {
                        1 => tr(words::REMAIN),
                        2 => tr(words::TOTAL),
                        _ => tr(words::ELAPSED),
                    }))
                    .child(div().text_size(px(12.)).font_weight(FontWeight::SEMIBOLD).child(time)),
            );
        div()
            .w(px(w))
            .flex_none()
            .h_full()
            .flex()
            .items_center()
            .gap(px(8.))
            .when(right, |d| d.flex_row_reverse())
            .child(letter)
            .child(art)
            .child(names)
            .child(readout)
    }

    /// The whole song in a line under the title: where the deck is, the cues, the loop; a click
    /// goes there. While the song decodes, how much of it is in.
    fn overview(&self, dj: &DjDecks, deck: usize, pos: f64, w: f32, h: f32, cx: &Context<Self>) -> impl IntoElement {
        let d = &dj.decks[deck];
        let col = deck_color(deck);
        let peaks = d.peaks.clone();
        let dur = d.duration();
        let hot = d.hot;
        let cue = d.cue;
        let looped = d.looped;
        let loading = d.buf.as_ref().filter(|b| !b.is_done()).map(|b| b.ready() as f32 / b.frames().max(1) as f32);
        let spot = self.overviews[deck].clone();
        let at = spot.clone();
        div()
            .id(("overview", deck))
            .relative()
            .w(px(w))
            .h(px(h))
            .flex_none()
            .px(px(8.))
            .cursor_pointer()
            .on_mouse_down(
                MouseButton::Left,
                cx.listener(move |this, e: &MouseDownEvent, _, cx| {
                    let b = at.get();
                    if b.size.width > px(0.) {
                        let f = (e.position.x - b.origin.x) / b.size.width;
                        this.dj.update(cx, |dj, cx| dj.seek_fraction(deck, f, cx));
                    }
                }),
            )
            .child(
                div().relative().size_full().child(spot_mark(spot)).child(
                    canvas(
                        |_, _, _| {},
                        move |b, _, window, _| {
                            let (x0, y0) = (b.origin.x, b.origin.y);
                            let width = b.size.width / px(1.);
                            let height = b.size.height / px(1.);
                            let rect = |x: f32, y: f32, ww: f32, hh: f32, color: Hsla, window: &mut Window| {
                                window.paint_quad(fill(Bounds::new(point(x0 + px(x), y0 + px(y)), size(px(ww), px(hh))), color));
                            };
                            if let Some(f) = loading {
                                rect(0., height / 2. - 1., width * f.clamp(0., 1.), 2., col.opacity(0.6), window);
                            }
                            if dur <= 0. || peaks.is_empty() {
                                return;
                            }
                            let n = peaks.len();
                            let cols = width.max(1.) as usize;
                            let played = (pos / dur) as f32 * width;
                            for x in 0..cols {
                                let (a, b2) = (x * n / cols, ((x + 1) * n / cols).min(n));
                                let amp = peaks[a..b2.max(a + 1).min(n)].iter().map(|p| p[0]).max().unwrap_or(0) as f32 / 255.;
                                let bh = (amp * (height - 2.)).max(1.);
                                let shade = if (x as f32) < played { col.opacity(0.35) } else { col.opacity(0.85) };
                                rect(x as f32, height / 2. - bh / 2., 1., bh, shade, window);
                            }
                            let x_of = |t: f64| (t / dur) as f32 * width;
                            if let Some((a, b2)) = looped {
                                rect(x_of(a), 0., (x_of(b2) - x_of(a)).max(1.), height, hsla(0.36, 0.7, 0.45, 0.45), window);
                            }
                            rect(x_of(cue) - 0.5, 0., 1.5, height, rgb(CUE_MAIN).into(), window);
                            for (i, h) in hot.iter().enumerate() {
                                if let Some(t) = h {
                                    rect(x_of(*t) - 0.5, 0., 1.5, height, rgb(CUE_COLORS[i]).into(), window);
                                }
                            }
                            rect(played - 1., 0., 2., height, hsla(0., 0., 1., 0.95), window);
                        },
                    )
                    .absolute()
                    .inset_0(),
                ),
            )
    }

    /// The jog wheel: a platter turning as the song plays (33⅓ rpm), the cover in its middle, its
    /// ring in the deck's colour; held and turned, it scratches (vinyl) or bends and searches (CD).
    fn platter(&self, dj: &DjDecks, deck: usize, cover: Option<Arc<Image>>, size: f32, cx: &Context<Self>) -> impl IntoElement {
        let col = deck_color(deck);
        let d = &dj.decks[deck];
        let live = dj.is_playing(deck);
        let pos = dj.position(deck);
        let turns = pos / crate::deck_model::TURN_SECONDS;
        let angle = (turns.fract() * std::f64::consts::TAU) as f32;
        let ring = size * 0.8;
        let r = ring / 2. - 3.;
        let (x, y) = (size / 2. + r * angle.sin() - 4., size / 2. - r * angle.cos() - 4.);
        let inner = size * 0.5;
        let held = matches!(self.drag, Some(Drag { ctl: Ctl::Platter(n), .. }) if n == deck);
        div()
            .id(("platter", deck))
            .relative()
            .size(px(size))
            .flex_none()
            .rounded_full()
            .bg(c(0x0e0f12))
            .border_2()
            .border_color(if held { col } else { c(0x2c2f35) })
            .cursor(CursorStyle::PointingHand)
            .on_mouse_down(MouseButton::Left, self.grab(Ctl::Platter(deck), size, cx))
            .child(spot_mark(self.platters[deck].clone()))
            .child(
                div()
                    .absolute()
                    .left(px((size - ring) / 2.))
                    .top(px((size - ring) / 2.))
                    .size(px(ring))
                    .rounded_full()
                    .border_2()
                    .border_color(col.opacity(if live { 0.85 } else { 0.35 })),
            )
            .child(
                div()
                    .absolute()
                    .left(px((size - inner) / 2.))
                    .top(px((size - inner) / 2.))
                    .size(px(inner))
                    .rounded_full()
                    .overflow_hidden()
                    .bg(c(0x1c1e22))
                    .border_1()
                    .border_color(c(0x34373d))
                    .children(cover.map(|i| img(i).size_full().rounded_full().object_fit(ObjectFit::Cover))),
            )
            .child(div().absolute().left(px(x)).top(px(y)).size(px(8.)).rounded_full().bg(if live { c(0xffffff) } else { col }))
            .when(d.slip, |el| {
                el.child(div().absolute().top(px(size * 0.06)).left_0().right_0().flex().justify_center().child(div().text_size(px(8.)).text_color(col).child("SLIP")))
            })
    }

    /// One deck's controls: effects and pads on the outside, the pitch fader, the platter, the
    /// loop and transport rows under them.
    fn deck_body(&self, dj: &DjDecks, deck: usize, cover: Option<Arc<Image>>, w: f32, h: f32, cx: &Context<Self>) -> impl IntoElement {
        let d = &dj.decks[deck];
        let col = deck_color(deck);
        let right = deck == 1;
        let bottom_h = 30.;
        let top_h = h - bottom_h - 14.;
        let platter_d = (top_h - 22.).min(w * 0.36).max(90.);
        let side_w = (w - platter_d - 70.).clamp(0., 210.);
        let show_side = side_w >= 150.;

        // Effects: three slots, each its name (on/off), the next effect, and the amount.
        let fx = div().flex().flex_col().gap(px(4.)).children((0..FX_SLOTS).map(|s| {
            let on = d.fx_on[s];
            let amount = d.fx_amount[s];
            let bar_w = (side_w - 96.).max(30.);
            div()
                .flex()
                .items_center()
                .gap(px(4.))
                .child(
                    pill(d.fx[s].name(), 70., 18., on.then_some(col))
                        .id(("fx", deck * 10 + s))
                        .cursor_pointer()
                        .on_click(cx.listener(move |this, _, _, cx| this.dj.update(cx, |dj, cx| dj.fx_toggle(deck, s, cx)))),
                )
                .child(
                    pill("›", 16., 18., None)
                        .id(("fxn", deck * 10 + s))
                        .cursor_pointer()
                        .on_click(cx.listener(move |this, _, _, cx| this.dj.update(cx, |dj, cx| dj.fx_next(deck, s, cx)))),
                )
                .child(
                    div()
                        .id(("fxa", deck * 10 + s))
                        .relative()
                        .w(px(bar_w))
                        .h(px(18.))
                        .cursor(CursorStyle::ResizeLeftRight)
                        .on_mouse_down(MouseButton::Left, self.grab(Ctl::Fx(deck, s), bar_w, cx))
                        .child(div().absolute().left_0().right_0().top(px(7.5)).h(px(3.)).rounded_full().bg(c(0x0b0c0e)))
                        .child(div().absolute().left_0().w(px(bar_w * amount)).top(px(7.5)).h(px(3.)).rounded_full().bg(if on { col } else { c(TEXT3) }))
                        .child(div().absolute().left(px((bar_w - 6.) * amount)).w(px(6.)).top(px(4.)).h(px(10.)).rounded(px(1.)).bg(c(0x6a6f78))),
                )
        }));

        // Pads: the page (‹ name ›, as a dropdown steps) and eight pads; on the hot cue pages,
        // the previous and next cue.
        let cue_pages = matches!(d.pads, PadMode::HotCue | PadMode::CueLoop);
        let tabs = div()
            .flex()
            .gap(px(2.))
            .child(pill("‹", 16., 16., None).id(("pp", deck)).cursor_pointer().on_click(cx.listener(move |this, _, _, cx| this.dj.update(cx, |dj, cx| dj.step_pads(deck, -1, cx)))))
            .child(
                div()
                    .flex_1()
                    .h(px(16.))
                    .flex()
                    .items_center()
                    .justify_center()
                    .rounded(px(3.))
                    .bg(col.opacity(0.18))
                    .text_size(px(8.))
                    .font_weight(FontWeight::SEMIBOLD)
                    .text_color(col)
                    .child(d.pads.label()),
            )
            .child(pill("›", 16., 16., None).id(("pn", deck)).cursor_pointer().on_click(cx.listener(move |this, _, _, cx| this.dj.update(cx, |dj, cx| dj.step_pads(deck, 1, cx)))))
            .when(cue_pages, |row| {
                row.child(pill("◀", 18., 16., None).id(("cprev", deck)).cursor_pointer().on_click(cx.listener(move |this, _, _, cx| this.dj.update(cx, |dj, cx| dj.cue_step(deck, -1, cx)))))
                    .child(pill("▶", 18., 16., None).id(("cnext", deck)).cursor_pointer().on_click(cx.listener(move |this, _, _, cx| this.dj.update(cx, |dj, cx| dj.cue_step(deck, 1, cx)))))
            });
        let pad_w = ((side_w - 9.) / 4. - 1.).floor().max(20.);
        let pad_h = ((top_h - 3. * 22. - 40. - 16.) / 2.).clamp(20., 34.);
        let pads = div().w(px(side_w)).flex().flex_wrap().gap(px(3.)).children((0..8).map(|i| {
            let (label, lit) = dj.pad_label(deck, i);
            let tint = if matches!(d.pads, PadMode::HotCue | PadMode::CueLoop) { c(CUE_COLORS[i]) } else { col };
            div()
                .id(("pad", deck * 10 + i))
                .w(px(pad_w))
                .h(px(pad_h))
                .flex()
                .items_center()
                .justify_center()
                .rounded(px(4.))
                .cursor_pointer()
                .bg(if lit { tint.opacity(0.3) } else { c(0x202328) })
                .border_1()
                .border_color(if lit { tint } else { c(LINE) })
                .text_size(px(if label.len() > 8 { 7.5 } else { 9. }))
                .font_weight(FontWeight::SEMIBOLD)
                .text_color(if lit { tint } else { c(TEXT2) })
                .overflow_hidden()
                .whitespace_nowrap()
                .hover(|d| d.bg(c(0x2a2e34)))
                .on_mouse_down(
                    MouseButton::Left,
                    cx.listener(move |this, e: &MouseDownEvent, _, cx| {
                        cx.stop_propagation();
                        this.press(Ctl::Pad(deck), e.position, 1., cx);
                        this.dj.update(cx, |dj, cx| dj.pad_down(deck, i, false, cx));
                    }),
                )
                .on_mouse_down(
                    MouseButton::Right,
                    cx.listener(move |this, _: &MouseDownEvent, _, cx| {
                        cx.stop_propagation();
                        this.dj.update(cx, |dj, cx| dj.pad_down(deck, i, true, cx));
                    }),
                )
                .child(label)
        }));
        let side = div()
            .w(px(side_w))
            .flex_none()
            .flex()
            .flex_col()
            .gap(px(6.))
            .when(!show_side, |d| d.hidden())
            .child(fx)
            .child(div().flex().flex_col().gap(px(3.)).child(tabs).child(pads));

        // Pitch: range, fader, bend.
        let fader_h = (platter_d - 50.).max(50.);
        let pitch_v = 1. - (d.fader + 1.) / 2.;
        let pitch = div()
            .flex_none()
            .flex()
            .flex_col()
            .items_center()
            .gap(px(3.))
            .child(
                div()
                    .flex()
                    .gap(px(2.))
                    .child(
                        pill("MT", 19., 16., d.keylock.then_some(col))
                            .id(("mt", deck))
                            .cursor_pointer()
                            .text_size(px(8.))
                            .on_click(cx.listener(move |this, _, _, cx| this.dj.update(cx, |dj, cx| dj.toggle_keylock(deck, cx)))),
                    )
                    .child(
                        pill("0", 19., 16., None)
                            .id(("p0", deck))
                            .cursor_pointer()
                            .text_size(px(8.))
                            .on_click(cx.listener(move |this, _, _, cx| this.dj.update(cx, |dj, cx| dj.pitch_reset(deck, cx)))),
                    ),
            )
            .child(
                pill(format!("±{}%", (d.range * 100.).round()), 40., 16., None)
                    .id(("range", deck))
                    .cursor_pointer()
                    .text_size(px(8.5))
                    .on_click(cx.listener(move |this, _, _, cx| this.dj.update(cx, |dj, cx| dj.cycle_range(deck, cx)))),
            )
            .child(
                fader(fader_h, pitch_v, col)
                    .id(("pitch", deck))
                    .on_mouse_down(MouseButton::Left, self.grab(Ctl::Pitch(deck), fader_h, cx))
                    .on_click(cx.listener(move |this, e: &ClickEvent, _, cx| {
                        if e.click_count() == 2 {
                            this.dj.update(cx, |dj, cx| dj.set_pitch(deck, 0., cx));
                        }
                    })),
            )
            .child(
                div()
                    .flex()
                    .gap(px(2.))
                    .child(pill("−", 19., 16., (d.bend < 1.).then_some(col)).cursor_pointer().on_mouse_down(
                        MouseButton::Left,
                        cx.listener(move |this, e: &MouseDownEvent, _, cx| {
                            cx.stop_propagation();
                            this.press(Ctl::Bend(deck), e.position, 1., cx);
                            this.dj.update(cx, |dj, cx| dj.bend(deck, -1., cx));
                        }),
                    ))
                    .child(pill("+", 19., 16., (d.bend > 1.).then_some(col)).cursor_pointer().on_mouse_down(
                        MouseButton::Left,
                        cx.listener(move |this, e: &MouseDownEvent, _, cx| {
                            cx.stop_propagation();
                            this.press(Ctl::Bend(deck), e.position, 1., cx);
                            this.dj.update(cx, |dj, cx| dj.bend(deck, 1., cx));
                        }),
                    )),
            );

        let platter_col = div()
            .flex_1()
            .flex()
            .flex_col()
            .items_center()
            .gap(px(4.))
            .child(self.platter(dj, deck, cover, platter_d, cx))
            .child(
                div()
                    .flex()
                    .gap(px(4.))
                    .child(
                        pill(if d.vinyl { "VINYL" } else { "CD" }, 44., 16., d.vinyl.then_some(col))
                            .id(("vinyl", deck))
                            .cursor_pointer()
                            .text_size(px(8.))
                            .on_click(cx.listener(move |this, _, _, cx| this.dj.update(cx, |dj, cx| dj.toggle_vinyl(deck, cx)))),
                    )
                    .child(
                        pill("SLIP", 36., 16., d.slip.then_some(col))
                            .id(("slip", deck))
                            .cursor_pointer()
                            .text_size(px(8.))
                            .on_click(cx.listener(move |this, _, _, cx| this.dj.update(cx, |dj, cx| dj.toggle_slip(deck, cx)))),
                    ),
            );

        // The loop row: size with halving and doubling (a click on it loops), in, out, reloop.
        let looping = d.looped.is_some();
        let looper = div()
            .flex()
            .items_center()
            .gap(px(3.))
            .when(right, |d| d.flex_row_reverse())
            .child(pill("½", 18., 22., None).id(("lhalf", deck)).cursor_pointer().on_click(cx.listener(move |this, _, _, cx| this.dj.update(cx, |dj, cx| dj.loop_size(deck, false, cx)))))
            .child(
                pill(beats_label(d.loop_beats), 34., 22., looping.then_some(c(0x3ddc84)))
                    .id(("loop", deck))
                    .cursor_pointer()
                    .text_size(px(11.))
                    .on_click(cx.listener(move |this, _, _, cx| this.dj.update(cx, |dj, cx| dj.auto_loop(deck, cx)))),
            )
            .child(pill("×2", 18., 22., None).id(("ldouble", deck)).cursor_pointer().on_click(cx.listener(move |this, _, _, cx| this.dj.update(cx, |dj, cx| dj.loop_size(deck, true, cx)))))
            .child(pill("IN", 26., 22., d.loop_in.is_some().then_some(c(0x3ddc84))).id(("lin", deck)).cursor_pointer().on_click(cx.listener(move |this, _, _, cx| this.dj.update(cx, |dj, cx| dj.loop_in(deck, cx)))))
            .child(pill("OUT", 30., 22., None).id(("lout", deck)).cursor_pointer().on_click(cx.listener(move |this, _, _, cx| this.dj.update(cx, |dj, cx| dj.loop_out(deck, cx)))))
            .child(
                pill(if looping { "EXIT" } else { "RELOOP" }, 44., 22., None)
                    .id(("lre", deck))
                    .cursor_pointer()
                    .text_size(px(8.5))
                    .on_click(cx.listener(move |this, _, _, cx| this.dj.update(cx, |dj, cx| dj.reloop(deck, cx)))),
            );

        let playing = dj.is_playing(deck);
        let loaded = d.buf.is_some();
        let transport = div()
            .flex()
            .items_center()
            .gap(px(5.))
            .child(
                pill("■", 22., 26., None)
                    .id(("stop", deck))
                    .cursor_pointer()
                    .on_click(cx.listener(move |this, _, _, cx| this.dj.update(cx, |dj, cx| dj.stop(deck, cx)))),
            )
            .child(pill("CUE", 40., 26., loaded.then_some(c(CUE_MAIN))).cursor_pointer().on_mouse_down(MouseButton::Left, self.grab(Ctl::Cue(deck), 1., cx)))
            .child(
                div()
                    .id(("play", deck))
                    .w(px(48.))
                    .h(px(26.))
                    .flex()
                    .items_center()
                    .justify_center()
                    .rounded(px(4.))
                    .cursor_pointer()
                    .bg(if playing { col.opacity(0.3) } else { c(PANEL2) })
                    .border_1()
                    .border_color(if playing { col } else { c(LINE) })
                    .hover(|d| d.bg(col.opacity(0.4)))
                    .on_click(cx.listener(move |this, _, _, cx| this.dj.update(cx, |dj, cx| dj.play_pause(deck, cx))))
                    .child(icon(if playing { "pause" } else { "play" }, 12., if playing { col } else { c(TEXT2) })),
            )
            .child(
                pill("SYNC", 40., 26., None)
                    .id(("sync", deck))
                    .cursor_pointer()
                    .hover(|d| d.bg(c(0x2e3238)))
                    .on_click(cx.listener(move |this, _, _, cx| this.dj.update(cx, |dj, cx| dj.sync(deck, cx)))),
            );

        let top = div()
            .h(px(top_h))
            .flex_none()
            .flex()
            .items_center()
            .gap(px(8.))
            .when(right, |d| d.flex_row_reverse())
            .child(side)
            .child(pitch)
            .child(platter_col);
        let bottom = div()
            .h(px(bottom_h))
            .flex_none()
            .flex()
            .items_center()
            .justify_between()
            .when(right, |d| d.flex_row_reverse())
            .child(div().when(w < 380., |d| d.hidden()).child(looper))
            .child(transport);
        div().w(px(w)).flex_none().h_full().flex().flex_col().px(px(10.)).pt(px(6.)).pb(px(6.)).gap(px(2.)).child(top).child(bottom)
    }

    /// The mixer between the decks: gain, three bands and the filter for each, the channel faders
    /// with their meters, the crossfader with its curve, the master with its meter and REC.
    fn mixer(&self, dj: &DjDecks, w: f32, h: f32, cx: &Context<Self>) -> impl IntoElement {
        let rows = ["GAIN", "HIGH", "MID", "LOW", "FILTER"];
        let knob_d = ((h - 165.) / rows.len() as f32 - 4.).clamp(15., 26.);
        let column = |deck: usize| {
            div().flex().flex_col().gap(px(4.)).children(rows.iter().enumerate().map(|(k, _)| {
                let v = dj.decks[deck].knobs[k];
                let killed = (1..=3).contains(&k) && v <= 0.001;
                knob(knob_d, v, if killed { c(0xff3b3b) } else { deck_color(deck) })
                    .id(("knob", deck * 10 + k))
                    .on_mouse_down(MouseButton::Left, self.grab(Ctl::Knob(deck, k), 160., cx))
                    .on_mouse_down(
                        MouseButton::Right,
                        cx.listener(move |this, _: &MouseDownEvent, _, cx| {
                            // A right click kills a band (and brings it back).
                            if (1..=3).contains(&k) {
                                this.dj.update(cx, |dj, cx| dj.kill(deck, k, cx));
                            }
                        }),
                    )
                    .on_click(cx.listener(move |this, e: &ClickEvent, _, cx| {
                        if e.click_count() == 2 {
                            this.dj.update(cx, |dj, cx| dj.set_knob(deck, k, 0.5, cx));
                        }
                    }))
            }))
        };
        let labels = div().flex().flex_col().gap(px(4.)).children(rows.map(|t| {
            div().h(px(knob_d)).flex().items_center().justify_center().text_size(px(8.)).font_weight(FontWeight::SEMIBOLD).text_color(c(TEXT3)).child(t)
        }));
        let fader_h = rows.len() as f32 * (knob_d + 4.) - 4.;
        let channel = |deck: usize| {
            fader(fader_h, dj.decks[deck].volume, deck_color(deck))
                .id(("vol", deck))
                .on_mouse_down(MouseButton::Left, self.grab(Ctl::Volume(deck), fader_h, cx))
                .on_click(cx.listener(move |this, e: &ClickEvent, _, cx| {
                    if e.click_count() == 2 {
                        this.dj.update(cx, |dj, cx| dj.set_volume(deck, 1., cx));
                    }
                }))
        };
        let xf_w = w - 70.;
        let x = dj.xfader;
        let xfader = div()
            .id("xfader")
            .relative()
            .w(px(xf_w))
            .h(px(18.))
            .cursor(CursorStyle::ResizeLeftRight)
            .on_mouse_down(MouseButton::Left, self.grab(Ctl::Xfader, xf_w, cx))
            .on_click(cx.listener(|this, e: &ClickEvent, _, cx| {
                if e.click_count() == 2 {
                    this.dj.update(cx, |dj, cx| dj.set_xfader(0.5, cx));
                }
            }))
            .child(div().absolute().left_0().right_0().top(px(7.5)).h(px(3.)).rounded_full().bg(c(0x0b0c0e)))
            .child(div().absolute().left(px(xf_w / 2. - 0.5)).w(px(1.)).top(px(3.)).h(px(12.)).bg(c(TEXT3)))
            .child(div().absolute().left(px((xf_w - 12.) * x)).w(px(12.)).top_0().h(px(18.)).rounded(px(2.)).bg(c(0x4a4e56)).child(div().ml(px(5.5)).mt(px(3.)).w(px(1.)).h(px(12.)).bg(c(0xffffff))));
        let rec_on = dj.recording.is_some();
        div()
            .w(px(w))
            .flex_none()
            .h_full()
            .flex()
            .flex_col()
            .items_center()
            .gap(px(4.))
            .pt(px(6.))
            .bg(c(0x17191c))
            .border_l_1()
            .border_r_1()
            .border_color(c(LINE))
            .child(
                div()
                    .flex()
                    .items_start()
                    .gap(px(4.))
                    .child(column(0))
                    .child(meter(fader_h, dj.vu(0)))
                    .child(channel(0))
                    .child(labels)
                    .child(channel(1))
                    .child(meter(fader_h, dj.vu(1)))
                    .child(column(1)),
            )
            // Each deck's filter-knob effect (Color FX) and crossfader side.
            .child(
                div()
                    .flex()
                    .items_center()
                    .gap(px(3.))
                    .children([0usize, 1].map(|deck| {
                        div()
                            .flex()
                            .gap(px(3.))
                            .when(deck == 1, |d| d.flex_row_reverse())
                            .child(
                                pill(dj.color_label(deck), 54., 15., (dj.decks[deck].color != 0).then_some(deck_color(deck)))
                                    .id(("color", deck))
                                    .cursor_pointer()
                                    .text_size(px(7.))
                                    .on_click(cx.listener(move |this, _, _, cx| this.dj.update(cx, |dj, cx| dj.cycle_color(deck, cx)))),
                            )
                            .child(
                                pill(dj.assign_label(deck), 30., 15., None)
                                    .id(("assign", deck))
                                    .cursor_pointer()
                                    .text_size(px(7.))
                                    .on_click(cx.listener(move |this, _, _, cx| this.dj.update(cx, |dj, cx| dj.cycle_assign(deck, cx)))),
                            )
                    })),
            )
            .child(
                div()
                    .flex()
                    .items_center()
                    .gap(px(4.))
                    .child(
                        pill(dj.curve.name(), 44., 16., None)
                            .id("curve")
                            .cursor_pointer()
                            .text_size(px(7.5))
                            .on_click(cx.listener(|this, _, _, cx| this.dj.update(cx, |dj, cx| dj.cycle_curve(cx)))),
                    )
                    .child(
                        pill(format!("MIX {}", dj.mix_fx_label()), 66., 16., (dj.mix_fx != 0).then_some(c(TEXT)))
                            .id("mixfx")
                            .cursor_pointer()
                            .text_size(px(7.))
                            .on_click(cx.listener(|this, _, _, cx| this.dj.update(cx, |dj, cx| dj.cycle_mix_fx(cx)))),
                    )
                    .child(
                        pill("HAM", 26., 16., dj.hamster.then_some(c(TEXT)))
                            .id("hamster")
                            .cursor_pointer()
                            .text_size(px(7.))
                            .on_click(cx.listener(|this, _, _, cx| this.dj.update(cx, |dj, cx| dj.toggle_hamster(cx)))),
                    )
                    .child(
                        pill("LOCK", 28., 16., dj.pitch_lock.then_some(c(TEXT)))
                            .id("plock")
                            .cursor_pointer()
                            .text_size(px(7.))
                            .on_click(cx.listener(|this, _, _, cx| this.dj.update(cx, |dj, cx| dj.toggle_pitch_lock(cx)))),
                    )
                    .child(
                        pill("Q", 18., 16., dj.quantize.then_some(c(0x3ddc84)))
                            .id("quantize")
                            .cursor_pointer()
                            .on_click(cx.listener(|this, _, _, cx| this.dj.update(cx, |dj, cx| dj.toggle_quantize(cx)))),
                    ),
            )
            .child(div().flex().items_center().gap(px(4.)).child(div().text_size(px(9.)).text_color(deck_color(0)).child("A")).child(xfader).child(div().text_size(px(9.)).text_color(deck_color(1)).child("B")))
            .child(
                div()
                    .flex()
                    .items_center()
                    .gap(px(8.))
                    .child(div().text_size(px(8.)).font_weight(FontWeight::SEMIBOLD).text_color(c(TEXT3)).child("MASTER"))
                    .child(
                        knob(22., dj.master, c(TEXT))
                            .id("master")
                            .on_mouse_down(MouseButton::Left, self.grab(Ctl::Master, 160., cx))
                            .on_click(cx.listener(|this, e: &ClickEvent, _, cx| {
                                if e.click_count() == 2 {
                                    this.dj.update(cx, |dj, cx| dj.set_master(0.5, cx));
                                }
                            })),
                    )
                    .child(div().flex().gap(px(1.)).child(meter(22., dj.master_vu())).child(meter(22., dj.master_vu())))
                    .child(
                        pill("REC", 34., 18., rec_on.then_some(c(0xff3b3b)))
                            .id("rec")
                            .cursor_pointer()
                            .on_click(cx.listener(|this, _, _, cx| this.dj.update(cx, |dj, cx| dj.toggle_record(cx)))),
                    ),
            )
            // The master's effect and its amount; the sampler's volume.
            .child(
                div()
                    .flex()
                    .items_center()
                    .gap(px(5.))
                    .child(
                        pill(format!("FX {}", dj.master_fx_label()), 76., 16., (dj.master_fx != 0).then_some(c(TEXT)))
                            .id("masterfx")
                            .cursor_pointer()
                            .text_size(px(7.))
                            .on_click(cx.listener(|this, _, _, cx| this.dj.update(cx, |dj, cx| dj.cycle_master_fx(cx)))),
                    )
                    .child(knob(18., dj.master_fx_amount, c(TEXT2)).id("masterfx-amt").on_mouse_down(MouseButton::Left, self.grab(Ctl::MasterFx, 160., cx)))
                    .child(div().text_size(px(7.)).text_color(c(TEXT3)).child("SAMPLER"))
                    .child(knob(18., dj.sampler_volume, c(TEXT2)).id("sampler-vol").on_mouse_down(MouseButton::Left, self.grab(Ctl::SamplerVol, 160., cx))),
            )
    }
}

// ---- The browser ----

#[derive(Clone, PartialEq)]
enum Folder {
    Liked,
    Downloads,
    History,
    Playlist(String),
    /// A folder of this computer.
    Local(std::path::PathBuf),
    /// What the search found online.
    Search,
}

/// A song being dragged from the browser onto a deck.
#[derive(Clone)]
pub struct DraggedTrack {
    pub track: TrackRef,
    title: String,
}

impl Render for DraggedTrack {
    fn render(&mut self, _window: &mut Window, _cx: &mut Context<Self>) -> impl IntoElement {
        div()
            .px(px(10.))
            .py(px(5.))
            .rounded(px(6.))
            .bg(c(PANEL2))
            .border_1()
            .border_color(c(TEXT3))
            .text_size(px(11.5))
            .text_color(c(TEXT))
            .flex()
            .items_center()
            .gap(px(6.))
            .child(icon("music", 11., c(TEXT2)))
            .child(self.title.clone())
    }
}

pub struct Browser {
    model: Entity<PlayerModel>,
    dj: Entity<DjDecks>,
    /// The songs on the decks, to mark them in the list.
    on_decks: [Option<String>; 2],
    library: Entity<LibraryModel>,
    folder: Folder,
    /// The folder's songs, and those the search box lets through.
    all: Arc<Vec<Track>>,
    tracks: Arc<Vec<Track>>,
    selected: Option<usize>,
    /// Folders of this computer added to the browser.
    folders: Vec<std::path::PathBuf>,
    scanning: bool,
    input: Entity<gpui_kit::component::input::InputState>,
    /// What the box filters by.
    filter: String,
    /// The online search: its songs, the one asked, and a count to drop late answers.
    results: Arc<Vec<Track>>,
    asked: String,
    search_seq: u64,
    searching: bool,
    core: Arc<crate::core::Core>,
    scroll: UniformListScrollHandle,
    width: f32,
    _subs: Vec<Subscription>,
}

fn tracks_of(values: &[Value]) -> Vec<Track> {
    values.iter().filter_map(|v| serde_json::from_value(v.clone()).ok()).collect()
}

impl Browser {
    fn new(core: Arc<crate::core::Core>, model: Entity<PlayerModel>, library: Entity<LibraryModel>, dj: Entity<DjDecks>, window: &mut Window, cx: &mut Context<Self>) -> Self {
        use gpui_kit::component::input::{InputEvent, InputState};
        let downloads = crate::downloads::DownloadsModel::entity(cx);
        let input = cx.new(|cx| InputState::new(window, cx).placeholder(tr(words::SEARCH_HINT)));
        // A field the app's single-key shortcuts leave alone while it has the focus.
        crate::ui::register_field(&input.read(cx).focus_handle(cx), cx);
        let subs = vec![
            cx.subscribe_in(&input, window, |b, state, ev: &InputEvent, _w, cx| match ev {
                InputEvent::Change => {
                    b.filter = state.read(cx).value().to_string();
                    b.apply_filter(cx);
                }
                InputEvent::PressEnter { .. } => {
                    let q = state.read(cx).value().to_string();
                    b.search_online(q, cx);
                }
                _ => {}
            }),
            // The library's folders follow its changes; a folder of this computer or the search does
            // not (re-reading a disk on every like would blank the list).
            cx.observe(&library, |b, _, cx| {
                if matches!(b.folder, Folder::Liked | Folder::History | Folder::Playlist(_)) {
                    b.reload(cx);
                }
            }),
            cx.observe(&downloads, |b, _, cx| {
                if b.folder == Folder::Downloads {
                    b.reload(cx);
                }
            }),
            // The marks of the songs on the decks: when those change, not on every knob turned.
            cx.observe(&dj, |b, dj, cx| {
                let now = [0, 1].map(|d| dj.read(cx).decks[d].track.as_ref().map(|t| t.id.clone()));
                if now != b.on_decks {
                    b.on_decks = now;
                    cx.notify();
                }
            }),
            // The playing row's mark and the queue on the right: on a change of song, not per tick.
            cx.subscribe(&model, |_, _, e: &crate::player_model::PlayerEvent, cx| {
                if matches!(e, crate::player_model::PlayerEvent::StateChanged) {
                    cx.notify();
                }
            }),
        ];
        let mut folders = crate::local_music::saved();
        // LFLY_CONSOLE_FOLDER=<path> (tests): that folder too, opened (never saved).
        let test_folder = std::env::var("LFLY_CONSOLE_FOLDER").ok().map(std::path::PathBuf::from);
        if let Some(f) = &test_folder {
            folders.push(f.clone());
        }
        let mut b = Self {
            model,
            dj,
            on_decks: [None, None],
            library,
            folder: test_folder.map_or(Folder::Liked, Folder::Local),
            all: Arc::default(),
            tracks: Arc::default(),
            selected: None,
            folders,
            scanning: false,
            input,
            filter: String::new(),
            results: Arc::default(),
            asked: String::new(),
            search_seq: 0,
            searching: false,
            core,
            scroll: UniformListScrollHandle::new(),
            width: 900.,
            _subs: subs,
        };
        b.reload(cx);
        // LFLY_CONSOLE_LOAD=<seconds> (tests): the folder's first two songs on decks A and B then,
        // A playing once it is in.
        if let Some(secs) = std::env::var("LFLY_CONSOLE_LOAD").ok().and_then(|v| v.parse::<f64>().ok()) {
            cx.spawn(async move |this, cx| {
                cx.background_executor().timer(Duration::from_secs_f64(secs)).await;
                let _ = this.update(cx, |b, cx| {
                    let refs: Vec<TrackRef> = b.tracks.iter().take(2).filter_map(crate::core::track_ref).collect();
                    b.dj.update(cx, |dj, cx| {
                        for (d, r) in refs.into_iter().enumerate() {
                            dj.load(d, r, cx);
                        }
                    });
                });
                cx.background_executor().timer(Duration::from_secs(4)).await;
                let _ = this.update(cx, |b, cx| b.dj.update(cx, |dj, cx| dj.play(0, true, cx)));
            })
            .detach();
        }
        // LFLY_CONSOLE_SEARCH="<seconds>@<query>" (tests): that search online then.
        if let Some((secs, q)) = std::env::var("LFLY_CONSOLE_SEARCH").ok().and_then(|v| v.split_once('@').and_then(|(s, q)| Some((s.parse::<f64>().ok()?, q.to_string())))) {
            cx.spawn(async move |this, cx| {
                cx.background_executor().timer(Duration::from_secs_f64(secs)).await;
                let _ = this.update(cx, |b, cx| b.search_online(q, cx));
            })
            .detach();
        }
        b
    }

    fn reload(&mut self, cx: &mut Context<Self>) {
        let lib = self.library.read(cx);
        let tracks = match &self.folder {
            Folder::Liked => tracks_of(&lib.favorites),
            Folder::History => tracks_of(&lib.history),
            Folder::Playlist(id) => lib.raw_playlist(id).map_or_else(Vec::new, |p| tracks_of(p.get("tracks").and_then(Value::as_array).map(Vec::as_slice).unwrap_or(&[]))),
            Folder::Downloads => {
                let dl = crate::downloads::DownloadsModel::entity(cx);
                dl.read(cx).list.tracks.iter().filter_map(crate::core::track_of).collect()
            }
            // The search's songs stay until the next search (a library change must not wipe them).
            Folder::Search => self.results.to_vec(),
            Folder::Local(dir) => {
                // Read off the window's thread; the list shows when it is done.
                let dir = dir.clone();
                self.scanning = true;
                cx.spawn(async move |this, cx| {
                    let d2 = dir.clone();
                    let found = cx.background_executor().spawn(async move { crate::local_music::scan(&d2) }).await;
                    let _ = this.update(cx, |b, cx| {
                        if b.folder == Folder::Local(dir) {
                            b.scanning = false;
                            b.all = Arc::new(found);
                            b.selected = None;
                            b.apply_filter(cx);
                        }
                    });
                })
                .detach();
                Vec::new()
            }
        };
        self.all = Arc::new(tracks);
        self.selected = None;
        self.apply_filter(cx);
    }

    /// The folder's songs whose title, artist or album hold every word typed in the box.
    fn apply_filter(&mut self, cx: &mut Context<Self>) {
        let words: Vec<String> = self.filter.to_lowercase().split_whitespace().map(str::to_string).collect();
        let shown: Vec<Track> = if words.is_empty() {
            self.all.to_vec()
        } else {
            self.all
                .iter()
                .filter(|t| {
                    let hay = format!("{} {} {}", t.title, t.artist, t.album.as_deref().unwrap_or("")).to_lowercase();
                    words.iter().all(|w| hay.contains(w.as_str()))
                })
                .cloned()
                .collect()
        };
        self.tracks = Arc::new(shown);
        // EJECT on an empty deck loads the next song of what is shown.
        let refs: Vec<TrackRef> = self.tracks.iter().filter_map(crate::core::track_ref).collect();
        self.dj.update(cx, |dj, _| dj.browse = Arc::new(refs));
        cx.notify();
    }

    /// The catalogue searched for [q]: a song nobody saved, on a deck in a moment.
    fn search_online(&mut self, q: String, cx: &mut Context<Self>) {
        let q = q.trim().to_string();
        if q.is_empty() {
            return;
        }
        self.search_seq += 1;
        let seq = self.search_seq;
        self.searching = true;
        self.asked = q.clone();
        cx.notify();
        let core = self.core.clone();
        let (tx, rx) = tokio::sync::oneshot::channel();
        crate::core::runtime().spawn(async move {
            let _ = tx.send(core.search(q).await);
        });
        cx.spawn(async move |this, cx| {
            let Ok(found) = rx.await else { return };
            let _ = this.update(cx, |b, cx| {
                if b.search_seq != seq {
                    return;
                }
                b.searching = false;
                match found {
                    Ok(list) => {
                        b.results = Arc::new(list);
                        b.folder = Folder::Search;
                        // The box now names the search, not a filter on its songs.
                        b.filter.clear();
                        b.reload(cx);
                        b.scroll.scroll_to_item(0, ScrollStrategy::Top);
                    }
                    Err(e) => {
                        log::warn!("console search: {e}");
                        crate::toast::Toasts::say(tr(words::SEARCH_FAILED), cx);
                        cx.notify();
                    }
                }
            });
        })
        .detach();
    }

    /// A folder of this computer, picked in the system's dialog.
    fn add_folder(&mut self, cx: &mut Context<Self>) {
        let picked = cx.prompt_for_paths(PathPromptOptions { files: false, directories: true, multiple: true, prompt: Some(tr(words::ADD_FOLDER).into()) });
        cx.spawn(async move |this, cx| {
            let Ok(Ok(Some(paths))) = picked.await else { return };
            let _ = this.update(cx, |b, cx| {
                let mut last = None;
                for p in paths {
                    if !b.folders.contains(&p) {
                        b.folders.push(p.clone());
                    }
                    last = Some(p);
                }
                crate::local_music::save(&b.folders);
                if let Some(p) = last {
                    b.open(Folder::Local(p), cx);
                }
                cx.notify();
            });
        })
        .detach();
    }

    fn remove_folder(&mut self, dir: &std::path::Path, cx: &mut Context<Self>) {
        self.folders.retain(|f| f != dir);
        crate::local_music::save(&self.folders);
        if self.folder == Folder::Local(dir.to_path_buf()) {
            self.open(Folder::Liked, cx);
        }
        cx.notify();
    }

    fn open(&mut self, folder: Folder, cx: &mut Context<Self>) {
        if self.folder != folder {
            self.folder = folder;
            self.reload(cx);
            self.scroll.scroll_to_item(0, ScrollStrategy::Top);
        }
    }
}

impl Render for Browser {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let this = cx.entity().downgrade();
        let (state_queue, index) = {
            let m = self.model.read(cx);
            let s = m.state.as_ref();
            (s.map(|s| s.queue.clone()).unwrap_or_default(), s.map_or(-1, |s| s.index))
        };
        let on_decks = self.on_decks.clone();

        // The folders: LFly's own sources.
        let folder_row = |id: SharedString, label: String, name: &'static str, folder: Folder, on: bool| {
            let this = this.clone();
            div()
                .id(id)
                .h(px(22.))
                .flex()
                .items_center()
                .gap(px(6.))
                .px(px(8.))
                .rounded(px(3.))
                .cursor_pointer()
                .when(on, |d| d.bg(c(PANEL2)))
                .hover(|d| d.bg(c(0x22252a)))
                .on_click(move |_, _, cx| {
                    let f = folder.clone();
                    let _ = this.update(cx, |b, cx| b.open(f, cx));
                })
                .child(icon(name, 12., if on { c(TEXT) } else { c(TEXT2) }))
                .child(div().flex_1().min_w_0().text_size(px(11.5)).text_color(if on { c(TEXT) } else { c(TEXT2) }).whitespace_nowrap().text_ellipsis().overflow_hidden().child(label))
        };
        let playlists = self.library.read(cx).playlists.clone();
        let folders = self.folders.clone();
        // Narrow: the folders get thinner, then the queue column goes (the list keeps the room).
        let tree_w = if self.width < 900. { 150. } else { 190. };
        let show_queue = self.width >= 820.;
        // The list's columns in pixels: a uniform list's rows are sized by their content.
        let list_w = self.width - tree_w - if show_queue { 200. } else { 0. } - 2.;
        let artist_w = (list_w * 0.24).floor();
        let album_w = if list_w < 520. { 0. } else { (list_w * 0.2).floor() };
        let tree = div()
            .id("console-tree")
            .w(px(tree_w))
            .flex_none()
            .h_full()
            .overflow_y_scroll()
            .py(px(6.))
            .px(px(4.))
            .border_r_1()
            .border_color(c(LINE))
            .bg(c(0x16181b))
            .child(folder_row("cf-liked".into(), tr(words::LIKED).into(), "heart", Folder::Liked, self.folder == Folder::Liked))
            .child(folder_row("cf-dl".into(), tr(words::DOWNLOADS).into(), "download", Folder::Downloads, self.folder == Folder::Downloads))
            .child(folder_row("cf-history".into(), tr(words::HISTORY).into(), "clock", Folder::History, self.folder == Folder::History))
            .when(!self.results.is_empty() || self.searching, |t| {
                let label = if self.searching { tr(words::SEARCHING).to_string() } else { format!("{} «{}»", tr(words::RESULTS), self.asked) };
                t.child(folder_row("cf-search".into(), label, "search", Folder::Search, self.folder == Folder::Search))
            })
            .child(div().pt(px(10.)).pb(px(4.)).px(px(8.)).text_size(px(9.)).font_weight(FontWeight::SEMIBOLD).text_color(c(TEXT3)).child(tr(words::FOLDERS)))
            .children(folders.into_iter().enumerate().map(|(i, dir)| {
                let on = self.folder == Folder::Local(dir.clone());
                let this = this.clone();
                let gone = dir.clone();
                folder_row(format!("cf-dir-{i}").into(), crate::local_music::name_of(&dir), "library", Folder::Local(dir.clone()), on).on_mouse_down(MouseButton::Right, move |_, _, cx| {
                    // A right click takes the folder off the list (its files stay).
                    let _ = this.update(cx, |b, cx| b.remove_folder(&gone, cx));
                })
            }))
            .child(
                div()
                    .id("cf-add")
                    .h(px(22.))
                    .flex()
                    .items_center()
                    .gap(px(6.))
                    .px(px(8.))
                    .rounded(px(3.))
                    .cursor_pointer()
                    .hover(|d| d.bg(c(0x22252a)))
                    .on_click(cx.listener(|b, _, _, cx| b.add_folder(cx)))
                    .child(icon("plus", 12., c(TEXT2)))
                    .child(div().text_size(px(11.5)).text_color(c(TEXT2)).child(tr(words::ADD_FOLDER))),
            )
            .child(div().pt(px(10.)).pb(px(4.)).px(px(8.)).text_size(px(9.)).font_weight(FontWeight::SEMIBOLD).text_color(c(TEXT3)).child(tr(words::PLAYLISTS).to_uppercase()))
            .children(playlists.into_iter().map(|p| {
                let on = self.folder == Folder::Playlist(p.id.clone());
                folder_row(format!("cf-pl-{}", p.id).into(), p.name.clone(), "music", Folder::Playlist(p.id.clone()), on)
            }));

        // The list: a table of the folder's songs; A and B load one on a deck, a double click on
        // the deck that is free (A, unless A plays).
        let tracks = self.tracks.clone();
        let count = tracks.len();
        let selected = self.selected;
        let dj = self.dj.clone();
        let header = div()
            .h(px(22.))
            .flex_none()
            .flex()
            .items_center()
            .px(px(10.))
            .gap(px(8.))
            .bg(c(0x1e2024))
            .child(div().w(px(46.)).flex_none())
            .border_b_1()
            .border_color(c(LINE))
            .text_size(px(9.5))
            .font_weight(FontWeight::SEMIBOLD)
            .text_color(c(TEXT3))
            .child(div().flex_1().min_w_0().child(tr(words::TITLE)))
            .child(div().w(px(artist_w)).flex_none().child(tr(words::ARTIST)))
            .when(album_w > 0., |d| d.child(div().w(px(album_w)).flex_none().child(tr(words::ALBUM))))
            .child(div().w(px(40.)).flex_none().text_right().child("BPM"))
            .child(div().w(px(46.)).flex_none().text_right().child(tr(words::LENGTH)));
        let list_this = this.clone();
        let list = uniform_list("console-list", count, move |range: Range<usize>, _, _| {
            range
                .map(|i| {
                    let t = &tracks[i];
                    let deck_of = on_decks.iter().position(|d| d.as_deref() == Some(t.id.as_str()));
                    let on = deck_of.is_some();
                    let live_col = deck_color(deck_of.unwrap_or(0));
                    let sel = selected == Some(i);
                    let (this, dj, all) = (list_this.clone(), dj.clone(), tracks.clone());
                    let (dj_load, all_load) = (dj.clone(), all.clone());
                    let text = if on { live_col } else { c(TEXT) };
                    let load = |deck: usize| {
                        let (dj, all) = (dj_load.clone(), all_load.clone());
                        div()
                            .id(("load", i * 2 + deck))
                            .w(px(20.))
                            .h(px(16.))
                            .flex()
                            .items_center()
                            .justify_center()
                            .rounded(px(3.))
                            .cursor_pointer()
                            .bg(deck_color(deck).opacity(0.14))
                            .text_size(px(9.))
                            .font_weight(FontWeight::BOLD)
                            .text_color(deck_color(deck))
                            .hover(|d| d.bg(deck_color(deck).opacity(0.35)))
                            .on_click(move |_, _, cx| {
                                if let Some(r) = crate::core::track_ref(&all[i]) {
                                    dj.update(cx, |dj, cx| dj.load(deck, r, cx));
                                }
                            })
                            .child(if deck == 0 { "A" } else { "B" })
                    };
                    let dragged = crate::core::track_ref(t).map(|r| DraggedTrack { title: t.title.clone(), track: r });
                    div()
                        .id(("cl", i))
                        .w(px(list_w))
                        .when_some(dragged, |row, d| row.on_drag(d, |d, _, _, cx| cx.new(|_| d.clone())))
                        .h(px(22.))
                        .flex()
                        .items_center()
                        .px(px(10.))
                        .gap(px(8.))
                        .text_size(px(11.5))
                        .when(i % 2 == 1, |d| d.bg(c(0x17191c)))
                        .when(sel, |d| d.bg(c(0x2a2e34)))
                        .hover(|d| d.bg(c(0x23262b)))
                        .on_click(move |e, _, cx| {
                            if e.click_count() == 2 {
                                if let Some(r) = crate::core::track_ref(&all[i]) {
                                    dj.update(cx, |dj, cx| {
                                        let deck = if dj.is_playing(0) { 1 } else { 0 };
                                        dj.load(deck, r, cx);
                                    });
                                }
                            } else {
                                let _ = this.update(cx, |b, cx| {
                                    b.selected = Some(i);
                                    cx.notify();
                                });
                            }
                        })
                        .child(div().w(px(46.)).flex_none().flex().gap(px(4.)).child(load(0)).child(load(1)))
                        .child(div().flex_1().min_w_0().flex().items_center().gap(px(6.)).child(icon("music", 10., if on { live_col } else { c(TEXT3) })).child(
                            div().min_w_0().text_color(text).whitespace_nowrap().text_ellipsis().overflow_hidden().child(t.title.clone()),
                        ))
                        .child(div().w(px(artist_w)).flex_none().text_color(if on { live_col } else { c(TEXT2) }).whitespace_nowrap().text_ellipsis().overflow_hidden().child(t.artist.clone()))
                        .when(album_w > 0., |d| d.child(div().w(px(album_w)).flex_none().text_color(c(TEXT3)).whitespace_nowrap().text_ellipsis().overflow_hidden().child(t.album.clone().unwrap_or_default())))
                        .child(div().w(px(40.)).flex_none().text_right().text_color(c(TEXT2)).child(t.bpm.filter(|b| *b > 0.).map(|b| format!("{b:.1}")).unwrap_or_default()))
                        .child(div().w(px(46.)).flex_none().text_right().text_color(c(TEXT2)).child(if t.duration_ms > 0 { clock(t.duration_ms) } else { String::new() }))
                })
                .collect::<Vec<_>>()
        })
        .track_scroll(&self.scroll)
        .flex_1()
        .min_h_0();
        let typed = self.filter.trim().to_string();
        let search_bar = div()
            .h(px(30.))
            .flex_none()
            .flex()
            .items_center()
            .gap(px(6.))
            .px(px(8.))
            .bg(c(0x16181b))
            .border_b_1()
            .border_color(c(LINE))
            .child(icon("search", 12., c(TEXT3)))
            .child(div().flex_1().min_w_0().text_size(px(11.5)).child(gpui_kit::component::input::Input::new(&self.input).appearance(false).cleanable(true)))
            .child(
                pill(if self.searching { tr(words::SEARCHING).to_string() } else { tr(words::SEARCH_ONLINE).to_string() }, 96., 20., (!typed.is_empty()).then_some(c(TEXT)))
                    .id("search-online")
                    .cursor_pointer()
                    .on_click(cx.listener(move |b, _, _, cx| {
                        let q = b.input.read(cx).value().to_string();
                        b.search_online(q, cx);
                    })),
            );
        let empty_text = match &self.folder {
            _ if self.scanning => tr(words::LOADING).to_string(),
            Folder::Local(dir) if !dir.is_dir() => tr(words::FOLDER_MISSING).to_string(),
            _ if !typed.is_empty() => tr(words::NOTHING_FOUND).to_string(),
            _ => tr(words::NO_SONGS).to_string(),
        };
        let middle = div()
            .flex_1()
            .min_w_0()
            .h_full()
            .flex()
            .flex_col()
            .child(search_bar)
            .child(header)
            .when(count == 0, |d| d.child(div().p(px(16.)).text_size(px(12.)).text_color(c(TEXT3)).child(empty_text)))
            .child(list)
            .child(
                div()
                    .h(px(20.))
                    .flex_none()
                    .flex()
                    .items_center()
                    .px(px(10.))
                    .border_t_1()
                    .border_color(c(LINE))
                    .text_size(px(10.))
                    .text_color(c(TEXT3))
                    .child(crate::ui::brani(count as u64)),
            );

        // On the right: what the music player plays next (a double click goes there).
        let after = usize::try_from(index).map_or(0, |i| i + 1);
        let coming: Vec<(usize, TrackRef)> = state_queue.into_iter().enumerate().skip(after).take(40).collect();
        let model = self.model.clone();
        let queue = div()
            .id("console-queue")
            .w(px(200.))
            .flex_none()
            .h_full()
            .flex()
            .flex_col()
            .border_l_1()
            .border_color(c(LINE))
            .bg(c(0x16181b))
            .child(div().h(px(22.)).flex_none().flex().items_center().px(px(10.)).bg(c(0x1e2024)).border_b_1().border_color(c(LINE)).text_size(px(9.5)).font_weight(FontWeight::SEMIBOLD).text_color(c(TEXT3)).child(tr(words::UP_NEXT)))
            .when(coming.is_empty(), |d| d.child(div().p(px(10.)).text_size(px(11.)).text_color(c(TEXT3)).child(tr(words::NOTHING_NEXT))))
            .child(div().id("console-queue-list").flex_1().min_h_0().overflow_y_scroll().children(coming.into_iter().map(|(i, t)| {
                let model = model.clone();
                div()
                    .id(("cq", i))
                    .px(px(10.))
                    .py(px(4.))
                    .cursor_pointer()
                    .hover(|d| d.bg(c(0x23262b)))
                    .on_click(move |e, _, cx| {
                        if e.click_count() == 2 {
                            model.read(cx).command(protocol::CMD_SKIP_TO, i as i64);
                        }
                    })
                    .child(div().text_size(px(11.)).whitespace_nowrap().text_ellipsis().overflow_hidden().child(t.title.clone()))
                    .child(div().text_size(px(10.)).text_color(c(TEXT3)).whitespace_nowrap().text_ellipsis().overflow_hidden().child(t.artist.clone()))
            })));

        div().size_full().flex().bg(c(BG)).child(tree).child(middle).when(show_queue, |d| d.child(queue))
    }
}

#[cfg(test)]
mod tests {
    use super::{clock, tenths};

    #[test]
    fn deck_times_read_as_decks_show_them() {
        assert_eq!(tenths(48_512.), "0:48.5");
        assert_eq!(tenths(3_725_000.), "62:05.0");
        assert_eq!(clock(201_000), "3:21");
    }
}
