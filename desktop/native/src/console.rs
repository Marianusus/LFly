//! The Console page: two decks, a mixer and a browser, laid out like DJ software (the user's
//! reference is VirtualDJ's Pro layout; drawn here with LFly's own pieces, no third-party art).
//!
//! The engine plays one timeline (the song, the next one, the mix between), so the decks take
//! turns: the song at an even queue index sits on deck A, an odd one on deck B, and the other
//! deck holds the song coming next, as a DJ alternates decks. What works today: play/pause, cue,
//! starting the waiting deck, the pitch readout, the times, the strips with the coming mix and the
//! browser. The rest (effects, stems, loops, EQ, sync, faders) is drawn and waits for its job.
//!
//! What moves (platters, strips, clocks) lives in [`Decks`], redrawn about 20 times a second only
//! while it is on screen and playing; the browser is its own cached view.

use std::ops::Range;
use std::sync::Arc;
use std::time::{Duration, Instant};

use gpui_kit::*;
use gpui_kit::prelude::FluentBuilder as _;
use lfly_connect::protocol::{self, TrackRef};
use lfly_yt::Track;
use serde_json::Value;

use crate::core::Core;
use crate::covers::Covers;
use crate::icons::icon;
use crate::library_model::LibraryModel;
use crate::player_model::PlayerModel;

/// The few words in Italian, in one place for the translations to come. DJ terms (CUE, SYNC,
/// GAIN, FILTER, IN, OUT) stay as every console prints them.
mod words {
    pub const EMPTY: &str = "Nessun brano";
    pub const ELAPSED: &str = "TRASCORSO";
    pub const REMAIN: &str = "RIMANENTE";
    pub const LIKED: &str = "Brani che ti piacciono";
    pub const DOWNLOADS: &str = "Scaricati";
    pub const HISTORY: &str = "Cronologia";
    pub const PLAYLISTS: &str = "Playlist";
    pub const TITLE: &str = "Titolo";
    pub const ARTIST: &str = "Artista";
    pub const ALBUM: &str = "Album";
    pub const LENGTH: &str = "Durata";
    pub const UP_NEXT: &str = "IN CODA";
    pub const NOTHING_NEXT: &str = "Niente in coda";
    pub const NO_SONGS: &str = "Nessun brano qui";
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

fn c(hex: u32) -> Hsla {
    rgb(hex).into()
}

fn deck_color(deck: usize) -> Hsla {
    c(DECK[deck])
}

/// "0:48.5", as decks show time.
fn tenths(ms: f64) -> String {
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
    pub fn new(core: Arc<Core>, model: Entity<PlayerModel>, covers: Entity<Covers>, library: Entity<LibraryModel>, cx: &mut Context<Self>) -> Self {
        let decks = cx.new(|cx| Decks::new(core.clone(), model.clone(), covers, cx));
        let browser = cx.new(|cx| Browser::new(core, model, library, cx));
        Self { decks, browser, page_width: 900., height: 720. }
    }
}

impl Render for Console {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        // Under the top bar (72) and over the player bar (104), as the other pages.
        let inner_w = (self.page_width - 24.).max(320.);
        let inner_h = (self.height - 72. - 104.).max(300.);
        // The decks take about half the room, the browser the rest (the reference's split).
        let decks_h = (inner_h * 0.52).clamp(250., 360.);
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

/// What one deck holds now.
#[derive(Clone)]
struct DeckView {
    track: Option<TrackRef>,
    /// Its place in the queue.
    index: usize,
    /// The song playing (else waiting to come in).
    live: bool,
    position: f64,
    duration: f64,
    /// The coming mix on the playing song, as fractions (start, end).
    mix: Option<(f32, f32)>,
    speed: f32,
}

pub struct Decks {
    core: Arc<Core>,
    model: Entity<PlayerModel>,
    covers: Entity<Covers>,
    width: f32,
    height: f32,
    rendered_at: Instant,
    ticking: bool,
    _subs: Vec<Subscription>,
}

impl Decks {
    fn new(core: Arc<Core>, model: Entity<PlayerModel>, covers: Entity<Covers>, cx: &mut Context<Self>) -> Self {
        let subs = vec![cx.observe(&model, |_, _, cx| cx.notify()), cx.observe(&covers, |_, _, cx| cx.notify())];
        Self { core, model, covers, width: 900., height: 300., rendered_at: Instant::now(), ticking: false, _subs: subs }
    }

    /// About 20 frames a second while the page is shown, the window in front and the music on.
    fn keep_moving(&mut self, cx: &mut Context<Self>) {
        if self.ticking {
            return;
        }
        self.ticking = true;
        cx.spawn(async move |this, cx| loop {
            cx.background_executor().timer(Duration::from_millis(50)).await;
            let go_on = this
                .update(cx, |d, cx| {
                    let m = d.model.read(cx);
                    let on = m.playing() && m.active && d.rendered_at.elapsed() < Duration::from_millis(500);
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

    /// The two decks: the playing song on its deck, the next one on the other.
    fn views(&self, cx: &App) -> [DeckView; 2] {
        let m = self.model.read(cx);
        let empty = DeckView { track: None, index: 0, live: false, position: 0., duration: 0., mix: None, speed: 1. };
        let mut decks = [empty.clone(), empty];
        let Some(s) = m.state.as_ref() else { return decks };
        let Ok(index) = usize::try_from(s.index) else { return decks };
        let Some(current) = s.queue.get(index) else { return decks };
        let live = index % 2;
        let speed = if m.remote.is_none() { self.core.player.speed() } else { 1. };
        decks[live] = DeckView {
            track: Some(current.clone()),
            index,
            live: true,
            position: m.live_position(),
            duration: m.duration_ms() as f64,
            mix: m.mix_span(),
            speed,
        };
        if let Some((n, t)) = coming(index, s.queue.len(), &s.repeat).and_then(|n| s.queue.get(n).map(|t| (n, t))) {
            decks[1 - live] =
                DeckView { track: Some(t.clone()), index: n, live: false, position: 0., duration: t.duration_ms.max(0) as f64, mix: None, speed: 1. };
        }
        decks
    }

    fn play(&mut self, deck: &DeckView, cx: &mut Context<Self>) {
        let m = self.model.read(cx);
        if deck.live {
            m.command(protocol::CMD_TOGGLE, 0);
        } else if deck.track.is_some() {
            // The waiting deck comes in: the queue moves on to the song it shows.
            m.command(protocol::CMD_SKIP_TO, deck.index as i64);
        }
    }

    /// A CDJ's cue on the playing deck: the hardware console's own (midi.rs), so both share the
    /// point.
    fn cue(&mut self, deck: &DeckView, cx: &mut Context<Self>) {
        if deck.live && deck.track.is_some() {
            crate::midi::MidiModel::entity(cx).update(cx, |m, cx| m.cue(cx));
        }
    }
}

impl Render for Decks {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        self.rendered_at = Instant::now();
        let (playing, active) = {
            let m = self.model.read(cx);
            (m.playing(), m.active)
        };
        if playing && active {
            self.keep_moving(cx);
        }
        let decks = self.views(cx);
        let covers: [Option<Arc<Image>>; 2] = [0, 1].map(|i| {
            decks[i].track.as_ref().and_then(|t| t.cover_url.clone()).and_then(|u| self.covers.update(cx, |c, cx| c.get(&u, cx)))
        });
        let (w, h) = (self.width, self.height);
        let strip_h = (h * 0.14).clamp(28., 46.);
        let head_h = 46.;
        let body_h = (h - 2. * strip_h - head_h - 4.).max(150.);
        let mixer_w = (w * 0.22).clamp(170., 230.);
        let deck_w = (w - mixer_w) / 2.;
        let this = cx.entity().downgrade();

        let strips = div()
            .flex_none()
            .flex()
            .flex_col()
            .gap(px(2.))
            .bg(c(0x0c0d0f))
            .py(px(1.))
            .child(strip(&decks[0], 0, w, strip_h))
            .child(strip(&decks[1], 1, w, strip_h));
        let heads = div()
            .flex_none()
            .h(px(head_h))
            .flex()
            .border_b_1()
            .border_color(c(LINE))
            .child(head(&decks[0], 0, covers[0].clone(), deck_w))
            .child(div().w(px(mixer_w)).flex_none().h_full().flex().items_end().justify_center().gap(px(14.)).pb(px(6.)).children(
                ["AUDIO", "VIDEO", "SCRATCH", "MASTER"].into_iter().enumerate().map(|(i, t)| {
                    div()
                        .text_size(px(9.5))
                        .font_weight(FontWeight::SEMIBOLD)
                        .text_color(if i == 0 { c(TEXT) } else { c(TEXT3) })
                        .when(i == 0, |d| d.border_b_2().border_color(c(TEXT2)))
                        .child(t)
                }),
            ))
            .child(head(&decks[1], 1, covers[1].clone(), deck_w));
        let body = div()
            .flex_1()
            .min_h_0()
            .flex()
            .child(deck_body(&decks[0], 0, deck_w, body_h, this.clone()))
            .child(mixer(&decks, mixer_w, body_h))
            .child(deck_body(&decks[1], 1, deck_w, body_h, this));
        div().size_full().flex().flex_col().bg(c(PANEL)).child(strips).child(heads).child(body)
    }
}

/// The queue index played after [index] (lfly-core queue `after_end`; shuffle reorders the queue
/// itself, so the order is the queue's).
fn coming(index: usize, len: usize, repeat: &str) -> Option<usize> {
    match repeat {
        "one" => Some(index),
        _ if index + 1 < len => Some(index + 1),
        "all" if len > 0 => Some(0),
        _ => None,
    }
}

/// The scrolling strip of one deck: the song as a band passing the playhead in the middle, a mark
/// every second (taller every four), the coming mix tinted. Real peaks come later.
fn strip(d: &DeckView, deck: usize, w: f32, h: f32) -> impl IntoElement {
    let col = deck_color(deck);
    // 8 s each side of the playhead.
    let half_ms = 8_000.;
    let px_per_ms = (w / 2.) / half_ms as f32;
    let x_of = |ms: f64| w / 2. + ((ms - d.position) as f32) * px_per_ms;
    let mut el = div().relative().w_full().h(px(h)).overflow_hidden().bg(c(0x101114));
    if d.track.is_some() && d.duration > 0. {
        let (x0, x1) = (x_of(0.).max(0.), x_of(d.duration).min(w));
        if x1 > x0 {
            el = el.child(div().absolute().left(px(x0)).w(px(x1 - x0)).top(px(h * 0.2)).h(px(h * 0.6)).bg(col.opacity(0.16)));
        }
        if let Some((a, b)) = d.mix {
            let (xa, xb) = (x_of(a as f64 * d.duration).max(0.), x_of(b as f64 * d.duration).min(w));
            if xb > xa {
                el = el.child(div().absolute().left(px(xa)).w(px(xb - xa)).top_0().bottom_0().bg(col.opacity(0.28)));
            }
        }
        let first = ((d.position - half_ms) / 1000.).floor().max(0.) as i64;
        let last = ((d.position + half_ms) / 1000.).ceil().min(d.duration / 1000.) as i64;
        for s in first..=last {
            let x = x_of(s as f64 * 1000.);
            if !(0. ..=w).contains(&x) {
                continue;
            }
            let tall = s % 4 == 0;
            let th = if tall { h * 0.6 } else { h * 0.28 };
            el = el.child(div().absolute().left(px(x)).w(px(1.)).top(px((h - th) / 2.)).h(px(th)).bg(col.opacity(if tall { 0.9 } else { 0.5 })));
        }
    }
    el.child(div().absolute().left(px(w / 2. - 1.)).w(px(2.)).top_0().bottom_0().bg(c(0xffffff).opacity(0.85)))
}

/// The deck's title row: its letter, cover, title and artist on the outside; tempo, pitch and the
/// times towards the mixer.
fn head(d: &DeckView, deck: usize, cover: Option<Arc<Image>>, w: f32) -> impl IntoElement {
    let col = deck_color(deck);
    let right = deck == 1;
    let letter = div()
        .w(px(16.))
        .h_full()
        .flex_none()
        .flex()
        .items_center()
        .justify_center()
        .bg(col.opacity(if d.live { 0.9 } else { 0.25 }))
        .text_size(px(11.))
        .font_weight(FontWeight::BOLD)
        .text_color(if d.live { c(0x000000) } else { c(TEXT) })
        .child(if deck == 0 { "A" } else { "B" });
    let art = div().size(px(34.)).flex_none().rounded(px(3.)).bg(c(PANEL2)).children(cover.map(|i| img(i).size_full().rounded(px(3.)).object_fit(ObjectFit::Cover)));
    let (title, artist) = d.track.as_ref().map_or((words::EMPTY.to_string(), String::new()), |t| (t.title.clone(), t.artist.clone()));
    let names = div()
        .flex_1()
        .min_w_0()
        .flex()
        .flex_col()
        .when(right, |d| d.items_end())
        .child(div().w_full().text_size(px(13.)).font_weight(FontWeight::SEMIBOLD).text_ellipsis().whitespace_nowrap().overflow_hidden().when(right, |d| d.text_right()).child(title))
        .child(div().w_full().text_size(px(11.)).text_color(c(TEXT2)).text_ellipsis().whitespace_nowrap().overflow_hidden().when(right, |d| d.text_right()).child(artist));
    let pitch = (d.speed - 1.) * 100.;
    let elapsed = d.position;
    let remain = (d.duration - d.position).max(0.);
    let stat = |label: &'static str, value: String| {
        div()
            .flex()
            .flex_col()
            .items_center()
            .child(div().text_size(px(7.5)).text_color(c(TEXT3)).child(label))
            .child(div().text_size(px(11.)).font_weight(FontWeight::SEMIBOLD).child(value))
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
                .flex()
                .flex_col()
                .items_center()
                // No tempo known yet (it comes with the beat analysis): the box stays.
                .child(div().text_size(px(20.)).font_weight(FontWeight::BOLD).text_color(if d.track.is_some() { col } else { c(TEXT3) }).child("---.-"))
                .child(div().text_size(px(8.)).text_color(c(TEXT3)).child(format!("{}{:.1}%", if pitch >= 0. { "+" } else { "" }, pitch))),
        )
        .child(
            div()
                .flex()
                .gap(px(8.))
                .when(w < 330., |d| d.hidden())
                .child(stat(words::ELAPSED, tenths(elapsed)))
                .child(stat(words::REMAIN, tenths(remain))),
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

/// A knob: a disc and its mark, at [value] (0..1, 0.5 in the middle), drawn turned from 7 to 5 o'clock.
fn knob(size: f32, value: f32, mark: Hsla) -> impl IntoElement {
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
        .child(div().absolute().left(px(x)).top(px(y)).size(px(3.)).rounded_full().bg(mark))
}

/// A vertical fader, its cap at [value] (0 bottom, 1 top).
fn fader(h: f32, value: f32, cap: Hsla) -> impl IntoElement {
    let cap_h = 9.;
    let top = (h - cap_h) * (1. - value.clamp(0., 1.));
    div()
        .relative()
        .w(px(22.))
        .h(px(h))
        .flex_none()
        .child(div().absolute().left(px(9.5)).w(px(3.)).top_0().bottom_0().rounded_full().bg(c(0x0b0c0e)))
        .child(div().absolute().left_0().w(px(22.)).top(px(top)).h(px(cap_h)).rounded(px(2.)).bg(c(0x4a4e56)).child(div().mt(px(4.)).h(px(1.)).bg(cap)))
}

/// A rounded label button.
fn pill(label: &'static str, w: f32, h: f32, on: Option<Hsla>) -> Div {
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
        .child(label)
}

/// The jog wheel: a platter turning as the song plays (33⅓ rpm), its ring in the deck's colour.
fn platter(d: &DeckView, deck: usize, size: f32) -> impl IntoElement {
    let col = deck_color(deck);
    let turns = d.position / 1000. * (100. / 3. / 60.);
    let angle = (turns.fract() * std::f64::consts::TAU) as f32;
    let ring = size * 0.78;
    let r = ring / 2. - 3.;
    let (x, y) = (size / 2. + r * angle.sin() - 4., size / 2. - r * angle.cos() - 4.);
    let inner = size * 0.36;
    div()
        .relative()
        .size(px(size))
        .flex_none()
        .rounded_full()
        .bg(c(0x0e0f12))
        .border_2()
        .border_color(c(0x2c2f35))
        .child(
            div()
                .absolute()
                .left(px((size - ring) / 2.))
                .top(px((size - ring) / 2.))
                .size(px(ring))
                .rounded_full()
                .border_2()
                .border_color(col.opacity(if d.live { 0.85 } else { 0.35 })),
        )
        .child(
            div()
                .absolute()
                .left(px((size - inner) / 2.))
                .top(px((size - inner) / 2.))
                .size(px(inner))
                .rounded_full()
                .bg(c(0x1c1e22))
                .border_1()
                .border_color(c(0x34373d)),
        )
        .child(div().absolute().left(px(x)).top(px(y)).size(px(8.)).rounded_full().bg(if d.live { c(0xffffff) } else { col }))
}

/// One deck's controls: effects and stems on the outside, the pitch fader, the platter with the
/// loop and transport rows under it.
fn deck_body(d: &DeckView, deck: usize, w: f32, h: f32, decks: WeakEntity<Decks>) -> impl IntoElement {
    let col = deck_color(deck);
    let right = deck == 1;
    let bottom_h = 30.;
    let platter_d = (h - bottom_h - 18.).min(w * 0.42).max(90.);
    let fx_w = (w - platter_d - 60.).clamp(0., 150.);
    let show_fx = fx_w >= 96.;

    let fx = div()
        .w(px(fx_w))
        .flex_none()
        .flex()
        .flex_col()
        .gap(px(5.))
        .when(!show_fx, |d| d.hidden())
        .children(["FLANGER", "REVERB", "WAHWAH"].map(|name| {
            div()
                .flex()
                .items_center()
                .gap(px(6.))
                .child(pill(name, fx_w * 0.55, 18., None).justify_start().pl(px(6.)))
                .child(div().flex_1().h(px(3.)).rounded_full().bg(c(0x0b0c0e)).child(div().w(px(6.)).h(px(9.)).mt(px(-3.)).rounded(px(1.)).bg(c(0x4a4e56))))
        }))
        .child(div().mt(px(4.)).child(pill("STEMS", fx_w * 0.55, 18., None).justify_start().pl(px(6.))))
        .child(
            div().flex().flex_wrap().gap(px(4.)).children(
                ["Vocal", "Instru", "Bass", "Kick", "HiHat", "Stems FX"].map(|t| pill(t, (fx_w - 8.) / 3., 18., Some(col.opacity(0.75)))),
            ),
        );

    let pitch_v = 0.5 - (d.speed - 1.) / 0.16;
    let pitch = div()
        .flex_none()
        .flex()
        .flex_col()
        .items_center()
        .gap(px(4.))
        .child(div().text_size(px(8.5)).text_color(c(TEXT2)).child(format!("{:+.1}", (d.speed - 1.) * 100.)))
        .child(fader(platter_d - 16., pitch_v, col));

    let (dp, dc) = (decks.clone(), decks);
    let (vp, vc) = (d.clone(), d.clone());
    let play_on = d.live && d.track.is_some();
    // CUE, play, SYNC read the same way on both decks, as on the reference.
    let transport = div()
        .flex()
        .items_center()
        .gap(px(5.))
        .child(
            pill("CUE", 40., 24., None)
                .id(("cue", deck))
                .cursor_pointer()
                .hover(|d| d.bg(c(0x2e3238)))
                .on_click(move |_, _, cx| {
                    let _ = dc.update(cx, |me, cx| me.cue(&vc, cx));
                }),
        )
        .child(
            div()
                .id(("play", deck))
                .w(px(46.))
                .h(px(24.))
                .flex()
                .items_center()
                .justify_center()
                .rounded(px(4.))
                .cursor_pointer()
                .bg(if play_on { col.opacity(0.3) } else { c(PANEL2) })
                .border_1()
                .border_color(if play_on { col } else { c(LINE) })
                .hover(|d| d.bg(col.opacity(0.4)))
                .on_click(move |_, _, cx| {
                    let _ = dp.update(cx, |me, cx| me.play(&vp, cx));
                })
                .child(icon(if play_on && d.track.is_some() { "pause" } else { "play" }, 12., if play_on { col } else { c(TEXT2) })),
        )
        .child(pill("SYNC", 40., 24., None));
    let looper = div()
        .flex()
        .items_center()
        .gap(px(4.))
        .when(right, |d| d.flex_row_reverse())
        .child(pill("‹", 16., 20., None))
        .child(div().w(px(22.)).text_center().text_size(px(11.)).font_weight(FontWeight::SEMIBOLD).child("4"))
        .child(pill("›", 16., 20., None))
        .child(pill("IN", 30., 20., None))
        .child(pill("OUT", 30., 20., None));

    let top = div()
        .flex_1()
        .min_h_0()
        .flex()
        .items_center()
        .gap(px(10.))
        .when(right, |d| d.flex_row_reverse())
        .child(fx)
        .child(pitch)
        .child(div().flex_1().flex().justify_center().child(platter(d, deck, platter_d)));
    let bottom = div()
        .h(px(bottom_h))
        .flex_none()
        .flex()
        .items_center()
        .justify_between()
        .when(right, |d| d.flex_row_reverse())
        .child(div().when(w < 300., |d| d.hidden()).child(looper))
        .child(transport);
    div().w(px(w)).flex_none().h_full().flex().flex_col().px(px(10.)).pt(px(8.)).pb(px(6.)).child(top).child(bottom)
}

/// The mixer between the decks: gain, three bands and the filter for each, the channel faders and
/// the crossfader, which leans towards the playing deck and travels across during a mix.
fn mixer(decks: &[DeckView; 2], w: f32, h: f32) -> impl IntoElement {
    let rows = ["GAIN", "HIGH", "MID", "LOW", "FILTER"];
    let knob_d = ((h - 70.) / rows.len() as f32 - 6.).clamp(16., 26.);
    let column = |deck: usize| div().flex().flex_col().gap(px(6.)).children(rows.map(|_| knob(knob_d, 0.5, if deck == 0 { c(DECK[0]) } else { c(DECK[1]) })));
    let labels = div().flex().flex_col().gap(px(6.)).children(rows.map(|t| {
        div().h(px(knob_d)).flex().items_center().justify_center().text_size(px(8.)).font_weight(FontWeight::SEMIBOLD).text_color(c(TEXT3)).child(t)
    }));
    let fader_h = rows.len() as f32 * (knob_d + 6.) - 6.;
    // The crossfader: on the playing deck's side, across it while the coming mix plays.
    let live = decks.iter().position(|d| d.live);
    let x = match live {
        Some(i) => {
            let d = &decks[i];
            let p = d.mix.filter(|_| d.duration > 0.).map_or(0., |(a, b)| {
                let at = (d.position / d.duration) as f32;
                if b > a { ((at - a) / (b - a)).clamp(0., 1.) } else { 0. }
            });
            if i == 0 { p } else { 1. - p }
        }
        None => 0.5,
    };
    let xf_w = w - 40.;
    let headphones = |deck: usize| {
        div()
            .size(px(18.))
            .flex()
            .items_center()
            .justify_center()
            .rounded(px(3.))
            .bg(c(PANEL2))
            .border_1()
            .border_color(c(LINE))
            .child(icon("headphones", 12., if decks[deck].live { deck_color(deck) } else { c(TEXT2) }))
    };
    div()
        .w(px(w))
        .flex_none()
        .h_full()
        .flex()
        .flex_col()
        .items_center()
        .gap(px(6.))
        .pt(px(8.))
        .bg(c(0x17191c))
        .border_l_1()
        .border_r_1()
        .border_color(c(LINE))
        .child(
            div()
                .flex()
                .items_start()
                .gap(px(6.))
                .child(column(0))
                .child(fader(fader_h, 1., deck_color(0)))
                .child(labels)
                .child(fader(fader_h, 1., deck_color(1)))
                .child(column(1)),
        )
        .child(div().flex().gap(px(26.)).child(headphones(0)).child(headphones(1)))
        .child(
            div()
                .relative()
                .w(px(xf_w))
                .h(px(16.))
                .child(div().absolute().left_0().right_0().top(px(6.5)).h(px(3.)).rounded_full().bg(c(0x0b0c0e)))
                .child(div().absolute().left(px(xf_w / 2. - 0.5)).w(px(1.)).top(px(2.)).h(px(12.)).bg(c(TEXT3)))
                .child(div().absolute().left(px((xf_w - 12.) * x)).w(px(12.)).top_0().h(px(16.)).rounded(px(2.)).bg(c(0x4a4e56)).child(div().ml(px(5.5)).mt(px(3.)).w(px(1.)).h(px(10.)).bg(c(0xffffff)))),
        )
}

// ---- The browser ----

#[derive(Clone, PartialEq)]
enum Folder {
    Liked,
    Downloads,
    History,
    Playlist(String),
}

pub struct Browser {
    core: Arc<Core>,
    model: Entity<PlayerModel>,
    library: Entity<LibraryModel>,
    folder: Folder,
    tracks: Arc<Vec<Track>>,
    selected: Option<usize>,
    scroll: UniformListScrollHandle,
    width: f32,
    _subs: Vec<Subscription>,
}

fn tracks_of(values: &[Value]) -> Vec<Track> {
    values.iter().filter_map(|v| serde_json::from_value(v.clone()).ok()).collect()
}

impl Browser {
    fn new(core: Arc<Core>, model: Entity<PlayerModel>, library: Entity<LibraryModel>, cx: &mut Context<Self>) -> Self {
        let downloads = crate::downloads::DownloadsModel::entity(cx);
        let subs = vec![
            cx.observe(&library, |b, _, cx| b.reload(cx)),
            cx.observe(&downloads, |b, _, cx| {
                if b.folder == Folder::Downloads {
                    b.reload(cx);
                }
            }),
            // The playing row's mark and the queue on the right: on a change of song, not per tick.
            cx.subscribe(&model, |_, _, e: &crate::player_model::PlayerEvent, cx| {
                if matches!(e, crate::player_model::PlayerEvent::StateChanged) {
                    cx.notify();
                }
            }),
        ];
        let mut b = Self {
            core,
            model,
            library,
            folder: Folder::Liked,
            tracks: Arc::default(),
            selected: None,
            scroll: UniformListScrollHandle::new(),
            width: 900.,
            _subs: subs,
        };
        b.reload(cx);
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
        };
        self.tracks = Arc::new(tracks);
        self.selected = None;
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
        let (state_queue, index, playing_id) = {
            let m = self.model.read(cx);
            let s = m.state.as_ref();
            (s.map(|s| s.queue.clone()).unwrap_or_default(), s.map_or(-1, |s| s.index), m.current().map(|t| t.id.clone()))
        };
        let live_col = deck_color(usize::try_from(index).map_or(0, |i| i % 2));

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
            .child(folder_row("cf-liked".into(), words::LIKED.into(), "heart", Folder::Liked, self.folder == Folder::Liked))
            .child(folder_row("cf-dl".into(), words::DOWNLOADS.into(), "download", Folder::Downloads, self.folder == Folder::Downloads))
            .child(folder_row("cf-history".into(), words::HISTORY.into(), "clock", Folder::History, self.folder == Folder::History))
            .child(div().pt(px(10.)).pb(px(4.)).px(px(8.)).text_size(px(9.)).font_weight(FontWeight::SEMIBOLD).text_color(c(TEXT3)).child(words::PLAYLISTS.to_uppercase()))
            .children(playlists.into_iter().map(|p| {
                let on = self.folder == Folder::Playlist(p.id.clone());
                folder_row(format!("cf-pl-{}", p.id).into(), p.name.clone(), "music", Folder::Playlist(p.id.clone()), on)
            }));

        // The list: a table of the folder's songs; a double click plays from there.
        let tracks = self.tracks.clone();
        let count = tracks.len();
        let selected = self.selected;
        let core = self.core.clone();
        let header = div()
            .h(px(22.))
            .flex_none()
            .flex()
            .items_center()
            .px(px(10.))
            .gap(px(8.))
            .bg(c(0x1e2024))
            .border_b_1()
            .border_color(c(LINE))
            .text_size(px(9.5))
            .font_weight(FontWeight::SEMIBOLD)
            .text_color(c(TEXT3))
            .child(div().flex_1().min_w_0().child(words::TITLE))
            .child(div().w(px(artist_w)).flex_none().child(words::ARTIST))
            .when(album_w > 0., |d| d.child(div().w(px(album_w)).flex_none().child(words::ALBUM)))
            .child(div().w(px(40.)).flex_none().text_right().child("BPM"))
            .child(div().w(px(46.)).flex_none().text_right().child(words::LENGTH));
        let list_this = this.clone();
        let list = uniform_list("console-list", count, move |range: Range<usize>, _, _| {
            range
                .map(|i| {
                    let t = &tracks[i];
                    let on = playing_id.as_deref() == Some(t.id.as_str());
                    let sel = selected == Some(i);
                    let (this, core, all) = (list_this.clone(), core.clone(), tracks.clone());
                    let text = if on { live_col } else { c(TEXT) };
                    div()
                        .id(("cl", i))
                        .w(px(list_w))
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
                                core.play(&all, i);
                            } else {
                                let _ = this.update(cx, |b, cx| {
                                    b.selected = Some(i);
                                    cx.notify();
                                });
                            }
                        })
                        .child(div().flex_1().min_w_0().flex().items_center().gap(px(6.)).child(icon("music", 10., if on { live_col } else { c(TEXT3) })).child(
                            div().min_w_0().text_color(text).whitespace_nowrap().text_ellipsis().overflow_hidden().child(t.title.clone()),
                        ))
                        .child(div().w(px(artist_w)).flex_none().text_color(if on { live_col } else { c(TEXT2) }).whitespace_nowrap().text_ellipsis().overflow_hidden().child(t.artist.clone()))
                        .when(album_w > 0., |d| d.child(div().w(px(album_w)).flex_none().text_color(c(TEXT3)).whitespace_nowrap().text_ellipsis().overflow_hidden().child(t.album.clone().unwrap_or_default())))
                        .child(div().w(px(40.)).flex_none().text_right().text_color(c(TEXT2)).child(t.bpm.filter(|b| *b > 0.).map(|b| format!("{b:.1}")).unwrap_or_default()))
                        .child(div().w(px(46.)).flex_none().text_right().text_color(c(TEXT2)).child(clock(t.duration_ms)))
                })
                .collect::<Vec<_>>()
        })
        .track_scroll(&self.scroll)
        .flex_1()
        .min_h_0();
        let middle = div()
            .flex_1()
            .min_w_0()
            .h_full()
            .flex()
            .flex_col()
            .child(header)
            .when(count == 0, |d| d.child(div().p(px(16.)).text_size(px(12.)).text_color(c(TEXT3)).child(words::NO_SONGS)))
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

        // On the right: what comes after the two decks.
        let after = usize::try_from(index).map_or(0, |i| i + 2);
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
            .child(div().h(px(22.)).flex_none().flex().items_center().px(px(10.)).bg(c(0x1e2024)).border_b_1().border_color(c(LINE)).text_size(px(9.5)).font_weight(FontWeight::SEMIBOLD).text_color(c(TEXT3)).child(words::UP_NEXT))
            .when(coming.is_empty(), |d| d.child(div().p(px(10.)).text_size(px(11.)).text_color(c(TEXT3)).child(words::NOTHING_NEXT)))
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
    use super::{clock, coming, tenths};

    #[test]
    fn the_waiting_deck_holds_what_the_queue_plays_next() {
        assert_eq!(coming(0, 3, "off"), Some(1));
        assert_eq!(coming(2, 3, "off"), None, "the end of the queue: nothing waits");
        assert_eq!(coming(2, 3, "all"), Some(0), "repeat all starts over");
        assert_eq!(coming(1, 3, "one"), Some(1), "repeat one plays the song again");
        assert_eq!(coming(0, 0, "all"), None);
    }

    #[test]
    fn deck_times_read_as_decks_show_them() {
        assert_eq!(tenths(48_512.), "0:48.5");
        assert_eq!(tenths(3_725_000.), "62:05.0");
        assert_eq!(clock(201_000), "3:21");
    }
}
