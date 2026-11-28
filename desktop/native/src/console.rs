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
use lfly_connect::protocol::TrackRef;
use lfly_yt::Track;
use serde_json::Value;

use crate::covers::Covers;
use crate::autopilot::Lamp;
use crate::deck_model::{beats_label, Deck, DjDecks, PadMode, Status, HOT_CUES, PEAKS_PER_SECOND};
use crate::i18n::tr;
use crate::icons::icon;
use crate::library_model::LibraryModel;
use crate::player_model::PlayerModel;

/// The few words in Italian. DJ terms (CUE, SYNC, GAIN, FILTER, IN, OUT, the pads) stay as every
/// console prints them.
mod words {
    pub const EMPTY: &str = crate::i18n::t("Nessun brano");
    pub const LOAD_HINT: &str = crate::i18n::t("Trascina un brano qui, o tasto destro nella lista");
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
    pub const NO_SONGS: &str = crate::i18n::t("Nessun brano qui");
    pub const SEARCH_HINT: &str = crate::i18n::t("Cerca nella lista, Invio per cercare online");
    pub const SEARCHING: &str = crate::i18n::t("Ricerca…");
    pub const SEARCH_FAILED: &str = crate::i18n::t("Ricerca non riuscita");
    pub const ONLINE: &str = crate::i18n::t("Online");
    pub const ONLINE_HINT: &str = crate::i18n::t("Scrivi cosa cercare: i risultati arrivano qui");
    pub const LOAD_A: &str = crate::i18n::t("Carica sul deck A");
    pub const LOAD_B: &str = crate::i18n::t("Carica sul deck B");
    pub const UPNEXT_ADD: &str = crate::i18n::t("Aggiungi a Up Next");
    pub const UPNEXT_REMOVE: &str = crate::i18n::t("Togli da Up Next");
    pub const UPNEXT_CLEAR: &str = crate::i18n::t("Svuota Up Next");
    pub const UPNEXT_EMPTY: &str = crate::i18n::t("Trascina qui i brani da tenere pronti");
    pub const UPNEXT_MOVE: &str = crate::i18n::t("Trascina per spostarla a sinistra o a destra");
    pub const PLAYING_TITLE: &str = crate::i18n::t("Il deck {0} sta suonando");
    pub const PLAYING_BODY: &str = crate::i18n::t("Caricare «{0}» lo ferma.");
    pub const CANCEL: &str = crate::i18n::t("Annulla");
    pub const LOAD: &str = crate::i18n::t("Carica");
    pub const SYNC_TIP: &str = crate::i18n::t("Allinea tempo e beat a quelli dell'altro deck");
    pub const MT_TIP: &str = crate::i18n::t("Master tempo: cambia velocità senza cambiare tonalità");
    pub const SLIP_TIP: &str = crate::i18n::t("Slip: dopo loop e scratch il brano riprende dove sarebbe stato");
    pub const VINYL_TIP: &str = crate::i18n::t("VINYL: il piatto fa scratch. CD: il piatto spinge e rallenta");
    pub const STEM_VOX: &str = crate::i18n::t("Voce: dentro o fuori");
    pub const STEM_DRM: &str = crate::i18n::t("Batteria: dentro o fuori");
    pub const STEM_BAS: &str = crate::i18n::t("Basso: dentro o fuori");
    pub const STEM_MEL: &str = crate::i18n::t("Melodia e resto: dentro o fuori");
    pub const MASTER_TIP: &str = crate::i18n::t("Volume generale (doppio clic: al centro)");
    pub const Q_TIP: &str = crate::i18n::t("Quantize: cue e loop cadono sul beat");
    pub const REC_TIP: &str = crate::i18n::t("Registra il mix in un file WAV nella cartella Musica");
    pub const MORE_TIP: &str = crate::i18n::t("Altre impostazioni del mixer");
    pub const MIXER_SETTINGS: &str = crate::i18n::t("MIXER");
    pub const SET_COLOR: &str = crate::i18n::t("Manopola FILTER");
    pub const SET_SIDE: &str = crate::i18n::t("Lato del crossfader");
    pub const SET_CURVE: &str = crate::i18n::t("Curva del crossfader");
    pub const SET_MIXFX: &str = crate::i18n::t("Effetto sul crossfader");
    pub const SET_HAMSTER: &str = crate::i18n::t("Crossfader invertito");
    pub const SET_LOCK: &str = crate::i18n::t("Pitch dei deck legati");
    pub const SET_SAMPLER: &str = crate::i18n::t("Volume del sampler");
    pub const STEMS_TIP: &str = crate::i18n::t("Separa il brano in voce, batteria, basso e melodia (una volta sola, poi resta pronto)");
    pub const STEMS_PREPARE: &str = crate::i18n::t("Prepara gli stem");
    pub const STEMS_FOLDER: &str = crate::i18n::t("Stem da preparare");
    pub const STEMS_HINT: &str = crate::i18n::t("Trascina qui i brani: la console li separa in anticipo, uno alla volta");
    pub const STEMS_REMOVE: &str = crate::i18n::t("Togli dalla lista degli stem");
    pub const VIDEO_TIP: &str = crate::i18n::t("Testi su un'altra finestra, per il proiettore (doppio clic: schermo intero)");
    pub const AP_TIP: &str = crate::i18n::t("AUTOPILOT: suona da solo Up Next (o la lista aperta), mix compresi");
    pub const AP_HINT: &str = crate::i18n::t("AUTOPILOT spento: premi AP e la console suona da sola Up Next o la lista aperta");
    pub const AP_LOAD: &str = crate::i18n::t("CARICA");
    pub const AP_WAIT: &str = crate::i18n::t("ATTESA");
    pub const AP_ON_AIR: &str = crate::i18n::t("IN ONDA");
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

thread_local! {
    /// The app's dark tint and accent; the console's greys lean to the tint (None: plain greys).
    static THEME: Cell<(Option<crate::theme::Rgb>, Option<Hsla>)> = const { Cell::new((None, None)) };
}

/// Follows the app's look; true when the console must be drawn again.
pub fn set_theme(s: &crate::theme::Scheme) -> bool {
    use crate::theme::{build_scheme, luma, Mode};
    // DJ software stays dark: a light look lends the console its dark twin.
    let dark = if s.light { build_scheme(Mode::DynamicDark, Some(s.accent), Some(s.accent2)) } else { s.clone() };
    let tint = dark.gradient[0];
    let next = ((luma(tint) > 0.02).then_some(tint), Some(dark.accent.hsla(1.)));
    THEME.with(|t| t.replace(next) != next)
}

/// The main cue and the other accents: the app's accent.
fn accent() -> Hsla {
    THEME.with(|t| t.get().1).unwrap_or(c(CUE_MAIN))
}

/// A colour; the near-black and near-white greys take the app's tint, darker ones more.
fn c(hex: u32) -> Hsla {
    let k = crate::theme::Rgb::hex(hex);
    let (hi, lo) = (k.0.max(k.1).max(k.2), k.0.min(k.1).min(k.2));
    let g = hi / 255.;
    match THEME.with(|t| t.get().0) {
        Some(tint) if hi - lo <= 12. && g > 0.02 && g < 0.98 => crate::theme::mix(k, tint, 0.35 * (1. - g)).hsla(1.),
        _ => rgb(hex).into(),
    }
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
    dj: Entity<DjDecks>,
    /// The "deck playing, load anyway?" question is up.
    asking: bool,
    _sub: Subscription,
    decks: Entity<Decks>,
    browser: Entity<Browser>,
    pub page_width: f32,
    pub height: f32,
}

impl Console {
    pub fn new(core: Arc<crate::core::Core>, model: Entity<PlayerModel>, covers: Entity<Covers>, library: Entity<LibraryModel>, lyrics: Entity<crate::lyrics::LyricsData>, window: &mut Window, cx: &mut Context<Self>) -> Self {
        let dj = DjDecks::entity(cx);
        let decks = cx.new(|cx| Decks::new(core.clone(), dj.clone(), covers, lyrics, cx));
        // LFLY_CONSOLE_MIXMENU=<seconds> (tests): the mixer's settings card opened then.
        if let Some(secs) = std::env::var("LFLY_CONSOLE_MIXMENU").ok().and_then(|v| v.parse::<f64>().ok()) {
            let d = decks.downgrade();
            cx.spawn(async move |_, cx| {
                cx.background_executor().timer(Duration::from_secs_f64(secs)).await;
                let _ = d.update(cx, |d, cx| {
                    d.mixer_menu = true;
                    cx.notify();
                });
            })
            .detach();
        }
        // LFLY_CONSOLE_VIDEO=<seconds> (tests): the video out opened then.
        if let Some(secs) = std::env::var("LFLY_CONSOLE_VIDEO").ok().and_then(|v| v.parse::<f64>().ok()) {
            let d = decks.downgrade();
            cx.spawn(async move |_, cx| {
                cx.background_executor().timer(Duration::from_secs_f64(secs)).await;
                let _ = d.update(cx, |d, cx| d.toggle_video(cx));
            })
            .detach();
        }
        let browser = cx.new(|cx| Browser::new(core, model, library, dj.clone(), window, cx));
        // Redrawn when the question comes or goes, not on every control the decks change.
        let sub = cx.observe(&dj, |c, dj, cx| {
            let asking = dj.read(cx).pending.is_some();
            if asking != c.asking {
                c.asking = asking;
                cx.notify();
            }
        });
        Self { dj, asking: false, _sub: sub, decks, browser, page_width: 900., height: 720. }
    }
}

impl Console {
    /// The decks and the browser are views of their own: a new look reaches them here.
    pub fn repaint(&self, cx: &mut App) {
        self.decks.update(cx, |_, cx| cx.notify());
        self.browser.update(cx, |_, cx| cx.notify());
    }
}

/// The Console's strip at the top of the window: the headphones back to the app, the free
/// space to move the window, its buttons.
pub const STRIP_H: f32 = 52.;

impl Render for Console {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        // The whole window (the Console has it to itself): under its own strip with the
        // headphones (main.rs, console_strip), 12 px in from the other edges.
        let inner_w = (self.page_width - 24.).max(320.);
        let inner_h = (self.height - STRIP_H - 12.).max(300.);
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
        // A song asked onto a deck that plays: stop it for this one?
        let question = self.dj.read(cx).pending.as_ref().map(|(d, t)| {
            let (d, title) = (*d, t.title.clone());
            let button = |id: &'static str, label: &'static str, color: Option<Hsla>| {
                div()
                    .id(id)
                    .h(px(30.))
                    .px(px(16.))
                    .flex()
                    .items_center()
                    .rounded_full()
                    .cursor_pointer()
                    .text_size(px(12.5))
                    .font_weight(FontWeight::SEMIBOLD)
                    .bg(color.map_or(c(PANEL2), |k| k.opacity(0.25)))
                    .border_1()
                    .border_color(color.unwrap_or(c(LINE)))
                    .text_color(color.unwrap_or(c(TEXT)))
                    .hover(|s| s.opacity(0.85))
                    .child(tr(label))
            };
            deferred(
                div()
                    .id("load-question")
                    .absolute()
                    .inset_0()
                    .occlude()
                    .flex()
                    .items_center()
                    .justify_center()
                    .bg(black().opacity(0.45))
                    .child(
                        div()
                            .w(px(340.))
                            .p(px(18.))
                            .flex()
                            .flex_col()
                            .gap(px(8.))
                            .rounded(px(18.))
                            .bg(c(PANEL))
                            .border_1()
                            .border_color(deck_color(d))
                            .text_color(c(TEXT))
                            .child(div().text_size(px(14.)).font_weight(FontWeight::BOLD).child(crate::i18n::trf(words::PLAYING_TITLE, &[&["A", "B"][d]])))
                            .child(div().text_size(px(12.)).text_color(c(TEXT2)).child(crate::i18n::trf(words::PLAYING_BODY, &[&title])))
                            .child(
                                div()
                                    .pt(px(8.))
                                    .flex()
                                    .justify_end()
                                    .gap(px(8.))
                                    .child(button("lq-no", words::CANCEL, None).on_click(cx.listener(|this, _, _, cx| this.dj.update(cx, |dj, cx| dj.confirm_load(false, cx)))))
                                    .child(button("lq-yes", words::LOAD, Some(deck_color(d))).on_click(cx.listener(|this, _, _, cx| this.dj.update(cx, |dj, cx| dj.confirm_load(true, cx))))),
                            ),
                    ),
            )
            .with_priority(3)
        });
        div()
            .size_full()
            .relative()
            .pt(px(STRIP_H))
            .pb(px(12.))
            .px(px(12.))
            .children(question)
            .child(
                div()
                    .size_full()
                    .flex()
                    .flex_col()
                    // The app's card corners, the app's tint (c()).
                    .rounded(px(18.))
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
    core: Arc<crate::core::Core>,
    dj: Entity<DjDecks>,
    covers: Entity<Covers>,
    lyrics: Entity<crate::lyrics::LyricsData>,
    /// The video out's window, while it is open.
    video: Option<AnyWindowHandle>,
    /// The mixer's settings card is open (and where its button is).
    mixer_menu: bool,
    mixer_menu_spot: Spot,
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
    fn new(core: Arc<crate::core::Core>, dj: Entity<DjDecks>, covers: Entity<Covers>, lyrics: Entity<crate::lyrics::LyricsData>, cx: &mut Context<Self>) -> Self {
        let stems = crate::stems_model::StemsModel::entity(cx);
        let subs = vec![cx.observe(&dj, |_, _, cx| cx.notify()), cx.observe(&covers, |_, _, cx| cx.notify()), cx.observe(&stems, |_, _, cx| cx.notify())];
        Self {
            core,
            dj,
            covers,
            lyrics,
            video: None,
            mixer_menu: false,
            mixer_menu_spot: Spot::default(),
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

    /// VIDEO: the lyrics window opened, or closed when it is open.
    fn toggle_video(&mut self, cx: &mut Context<Self>) {
        if let Some(h) = self.video.take() {
            // Closed by hand already: then it opens again.
            if h.update(cx, |_, window, _| window.remove_window()).is_ok() {
                cx.notify();
                return;
            }
        }
        self.video = crate::video::VideoOut::open(self.core.clone(), self.dj.clone(), self.lyrics.clone(), self.covers.clone(), cx);
        cx.notify();
    }

    /// The video window is still open (it may have been closed from its own title bar).
    fn video_open(&self, cx: &App) -> bool {
        self.video.is_some_and(|h| cx.windows().iter().any(|w| w.window_id() == h.window_id()))
    }

    /// The pointer went down on [ctl].
    fn press(&mut self, ctl: Ctl, at: Point<Pixels>, span: f32, cx: &mut Context<Self>) {
        self.drag = Some(Drag { ctl, last: at, at: Instant::now(), span: span.max(1.) });
        self.dragging.set(true);
        self.dj.update(cx, |dj, cx| match ctl {
            Ctl::Knob(..) | Ctl::Volume(_) | Ctl::Pitch(_) | Ctl::Xfader | Ctl::Bend(_) => dj.user_touch(cx),
            Ctl::Cue(d) => dj.cue_down(d, cx),
            Ctl::Platter(d) => {
                dj.user_touch(cx);
                dj.jog_touch(d, cx);
            }
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
        // Where the autopilot mixes, on each waveform.
        let marks = dj.autopilot_marks();

        let strips = div()
            .flex_none()
            .flex()
            .flex_col()
            .gap(px(2.))
            .bg(c(0x0c0d0f))
            .py(px(1.))
            .child(self.dropzone(0, cx).child(strip(&dj.decks[0], 0, pos[0], self.zoom, w, strip_h, marks[0])))
            .child(self.dropzone(1, cx).child(strip(&dj.decks[1], 1, pos[1], self.zoom, w, strip_h, marks[1])));
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
fn strip(d: &Deck, deck: usize, pos: f64, half: f64, w: f32, h: f32, ap_mark: Option<(f64, f64)>) -> impl IntoElement {
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
                // The autopilot's mix: a green band from where it starts to where it ends.
                if let Some((a, b2)) = ap_mark {
                    let (xa, xb) = (x_of(a).max(0.), x_of(b2).min(width));
                    if xb > xa {
                        rect(xa, 0., xb - xa, height, hsla(0.40, 0.7, 0.5, 0.16), window);
                    }
                    if (0. ..=width).contains(&x_of(a)) {
                        rect(x_of(a) - 1., 0., 2., height, rgb(AP_ON).into(), window);
                    }
                }
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
                            let [amp, low, mids, high] = peaks[i];
                            let a = amp as f32 / 255.;
                            let bh = (a * (height - 4.)).max(1.);
                            let color = wave_color(low, mids, high);
                            // What was played is dimmed; the bass core drawn brighter inside.
                            let past = x < width / 2.;
                            rect(x, mid - bh / 2., 2., bh, color.opacity(if past { 0.4 } else { 0.85 }), window);
                            let core = bh * (low as f32 / 255.).sqrt() * 0.8;
                            if core > 1. {
                                rect(x, mid - core / 2., 2., core, hsla(0.01, 0.8, 0.5, if past { 0.3 } else { 0.55 }), window);
                            }
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
                marker(cue, accent(), window);
                for (i, h) in hot.iter().enumerate() {
                    if let Some(t) = h {
                        marker(*t, rgb(CUE_COLORS[i]).into(), window);
                    }
                }
            }
            rect(width / 2. - 1., 0., 2., height, hsla(0., 0., 1., 0.9), window);
            // The deck's colour on the strip's edge: which waveform is which at a glance.
            rect(0., 0., 3., height, col, window);
        },
    )
    .w(px(w))
    .h(px(h))
}

/// A waveform column's colour from where its energy is, as DJ software colours it: bass red,
/// mids green, highs blue, mixtures in between (a kick orange, a voice yellow-green, hats cyan).
fn wave_color(low: u8, mid: u8, high: u8) -> Hsla {
    let (l, m, h) = (low as f32 / 255., mid as f32 / 255., high as f32 / 255.);
    let top = l.max(m).max(h).max(1e-3);
    let (r, g, b) = (l / top, m / top * 0.9, h / top);
    // A little white so quiet mixtures stay visible on the dark strip.
    let mix = |x: f32| (0.18 + 0.82 * x).min(1.);
    let rgba = Rgba { r: mix(r), g: mix(g), b: mix(b), a: 1. };
    rgba.into()
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
    knob_lit(size, value, mark, false)
}

/// A knob the autopilot is turning: a green ring around it.
fn knob_lit(size: f32, value: f32, mark: Hsla, lit: bool) -> Div {
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
        .border_color(if lit { c(AP_ON) } else { c(0x3d4148) })
        .when(lit, |d| d.shadow(vec![BoxShadow { color: c(AP_ON).opacity(0.55), offset: point(px(0.), px(0.)), blur_radius: px(6.), spread_radius: px(0.), inset: false }]))
        .cursor(CursorStyle::ResizeUpDown)
        .child(div().absolute().left(px(x)).top(px(y)).size(px(3.)).rounded_full().bg(mark))
}

/// A vertical fader, its cap at [value] (0 bottom, 1 top).
fn fader(h: f32, value: f32, cap: Hsla) -> Div {
    fader_lit(h, value, cap, false)
}

/// A fader the autopilot is moving: its cap lit green.
fn fader_lit(h: f32, value: f32, cap: Hsla, lit: bool) -> Div {
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
        .child(div().absolute().left_0().w(px(22.)).top(px(top)).h(px(cap_h)).rounded(px(2.)).bg(if lit { c(AP_ON) } else { c(0x4a4e56) }).child(div().mt(px(4.)).h(px(1.)).bg(cap)))
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
                                let span = &peaks[a..b2.max(a + 1).min(n)];
                                let amp = span.iter().map(|p| p[0]).max().unwrap_or(0) as f32 / 255.;
                                let loudest = span.iter().max_by_key(|p| p[0]).copied().unwrap_or([0; 4]);
                                let bh = (amp * (height - 2.)).max(1.);
                                let color = wave_color(loudest[1], loudest[2], loudest[3]);
                                let shade = if (x as f32) < played { color.opacity(0.3) } else { color.opacity(0.9) };
                                rect(x as f32, height / 2. - bh / 2., 1., bh, shade, window);
                            }
                            let x_of = |t: f64| (t / dur) as f32 * width;
                            if let Some((a, b2)) = looped {
                                rect(x_of(a), 0., (x_of(b2) - x_of(a)).max(1.), height, hsla(0.36, 0.7, 0.45, 0.45), window);
                            }
                            rect(x_of(cue) - 0.5, 0., 1.5, height, accent(), window);
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
        // The song's progress round the platter; its last 30 seconds red and blinking.
        let dur = d.duration();
        let frac = if dur > 0. { (pos / dur).clamp(0., 1.) as f32 } else { 0. };
        let left = dur - pos;
        let ending = d.buf.is_some() && dur > 40. && left < 30.;
        let blink = ending && live && (std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |t| t.as_millis()) / 400) % 2 == 0;
        let ring_col = if ending { if blink { c(0xff3b3b) } else { c(0xff3b3b).opacity(0.35) } } else { col.opacity(0.9) };
        let progress = canvas(
            |_, _, _| {},
            move |b, _, window, _| {
                let width = 3.;
                let r = (size - width) / 2. - 1.;
                let cpt = point(b.origin.x + px(size / 2.), b.origin.y + px(size / 2.));
                let arc = |from: f32, to: f32| {
                    let mut p = PathBuilder::stroke(px(width));
                    let steps = ((to - from) * 96.).ceil().max(1.) as usize;
                    for k in 0..=steps {
                        let a = (from + (to - from) * k as f32 / steps as f32) * std::f32::consts::TAU - std::f32::consts::FRAC_PI_2;
                        let at = point(cpt.x + px(r * a.cos()), cpt.y + px(r * a.sin()));
                        if k == 0 { p.move_to(at) } else { p.line_to(at) }
                    }
                    p.build().ok()
                };
                if frac > 0.002 {
                    if let Some(p) = arc(0., frac) {
                        window.paint_path(p, ring_col);
                    }
                }
            },
        )
        .absolute()
        .inset_0();
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
            .child(progress)
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
        // Narrow (a small window, the right panel open): the platter makes room for the pads
        // and effects, and the loop and transport rows stack.
        let narrow = w < 345.;
        let bottom_h = if narrow { 64. } else { 34. };
        let top_h = h - bottom_h - 14.;
        // The platter gives up room before the pads and effects do.
        let platter_d = (top_h - 22.).min(w * 0.36).min(w - 220.).max(90.);
        let side_w = if narrow { (w - 124.).max(80.) } else { (w - platter_d - 70.).clamp(0., 210.) };
        let show_side = narrow || side_w >= 150.;
        let fx_name_w = if narrow { 48. } else { 70. };

        // Effects: three slots, each its name (on/off), the next effect, and the amount.
        let fx = div().flex().flex_col().gap(px(4.)).children((0..FX_SLOTS).map(|s| {
            let on = d.fx_on[s];
            let amount = d.fx_amount[s];
            let bar_w = (side_w - fx_name_w - 26.).max(24.);
            div()
                .flex()
                .items_center()
                .gap(px(4.))
                .child(
                    pill(d.fx[s].name(), fx_name_w, 18., on.then_some(col))
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
        // Narrow: the two rows of pads fit what is left under the effects (no platter to match).
        let pad_h = if narrow { ((top_h - 100.) / 2.).clamp(14., 34.) } else { ((top_h - 3. * 22. - 40. - 16.) / 2.).clamp(20., 34.) };
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
                .text_size(px(if label.chars().count() > 8 || (narrow && label.chars().count() > 4) { 7. } else { 9. }))
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
                            .tooltip(tip(words::MT_TIP))
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
            .when(!narrow, |col| col.child(self.platter(dj, deck, cover, platter_d, cx)))
            .child(
                div()
                    .flex()
                    .when(narrow, |row| row.flex_col())
                    .gap(px(4.))
                    .child(
                        pill(if d.vinyl { "VINYL" } else { "CD" }, 44., 16., d.vinyl.then_some(col))
                            .id(("vinyl", deck))
                            .tooltip(tip(words::VINYL_TIP))
                            .cursor_pointer()
                            .text_size(px(8.))
                            .on_click(cx.listener(move |this, _, _, cx| this.dj.update(cx, |dj, cx| dj.toggle_vinyl(deck, cx)))),
                    )
                    .child(
                        pill("SLIP", 36., 16., d.slip.then_some(col))
                            .id(("slip", deck))
                            .tooltip(tip(words::SLIP_TIP))
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
            // Narrow, IN and OUT go (the MANUAL LOOP pads have them).
            .when(w >= 420., |row| {
                row.child(pill("IN", 26., 22., d.loop_in.is_some().then_some(c(0x3ddc84))).id(("lin", deck)).cursor_pointer().on_click(cx.listener(move |this, _, _, cx| this.dj.update(cx, |dj, cx| dj.loop_in(deck, cx)))))
                    .child(pill("OUT", 30., 22., None).id(("lout", deck)).cursor_pointer().on_click(cx.listener(move |this, _, _, cx| this.dj.update(cx, |dj, cx| dj.loop_out(deck, cx)))))
            })
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
                pill("■", 26., 30., None)
                    .id(("stop", deck))
                    .cursor_pointer()
                    .on_click(cx.listener(move |this, _, _, cx| this.dj.update(cx, |dj, cx| dj.stop(deck, cx)))),
            )
            .child(
                pill("CUE", 46., 30., loaded.then_some(accent())).text_size(px(11.))
                    .when(dj.lit(deck, Lamp::Cue), |p| p.bg(accent().opacity(0.7)).text_color(c(0x000000)))
                    .cursor_pointer()
                    .on_mouse_down(MouseButton::Left, self.grab(Ctl::Cue(deck), 1., cx)),
            )
            .child(
                div()
                    .id(("play", deck))
                    .w(px(56.))
                    .h(px(30.))
                    .flex()
                    .items_center()
                    .justify_center()
                    .rounded(px(4.))
                    .cursor_pointer()
                    .bg(if dj.lit(deck, Lamp::Play) { col.opacity(0.75) } else if playing { col.opacity(0.3) } else { c(PANEL2) })
                    .border_1()
                    .border_color(if playing { col } else { c(LINE) })
                    .hover(|d| d.bg(col.opacity(0.4)))
                    .on_click(cx.listener(move |this, _, _, cx| this.dj.update(cx, |dj, cx| dj.play_pause(deck, cx))))
                    .child(icon(if playing { "pause" } else { "play" }, 15., if playing { col } else { c(TEXT2) })),
            )
            .child(
                pill("SYNC", 46., 30., dj.lit(deck, Lamp::Sync).then_some(c(AP_ON))).text_size(px(10.5))
                    .id(("sync", deck))
                    .tooltip(tip(words::SYNC_TIP))
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
            .when(narrow, |d| d.flex_col().justify_center().gap(px(4.)).when(right, |d| d.items_end()).when(!right, |d| d.items_start()))
            .child(looper)
            .child(transport);
        div().w(px(w)).flex_none().h_full().flex().flex_col().px(px(10.)).pt(px(6.)).pb(px(6.)).gap(px(2.)).child(top).child(bottom)
    }

    /// A deck's stem buttons: VOX, DRUM, BASS, MELO, lit while the part plays; before its song
    /// is split, one STEMS button that asks for it (with the progress while it is being split).
    fn stem_row(&self, dj: &DjDecks, deck: usize, cx: &Context<Self>) -> AnyElement {
        let d = &dj.decks[deck];
        let col = deck_color(deck);
        if d.stems.is_some() {
            let names = ["VOX", "DRM", "BAS", "MEL"];
            return div()
                .flex()
                .gap(px(2.))
                .when(deck == 1, |r| r.flex_row_reverse())
                .children((0..4).map(|k| {
                    let on = d.stem_gain[k] > 0.5;
                    let lit = k == 0 && dj.lit(deck, Lamp::Stem);
                    pill(names[k], 21., 17., on.then_some(if lit { c(AP_ON) } else { col }))
                        .id(("stem", deck * 10 + k))
                        .tooltip(tip([words::STEM_VOX, words::STEM_DRM, words::STEM_BAS, words::STEM_MEL][k]))
                        .cursor_pointer()
                        .text_size(px(7.5))
                        .when(!on, |p| p.text_color(c(0xff5a5a)).line_through())
                        .on_click(cx.listener(move |this, _, _, cx| this.dj.update(cx, |dj, cx| dj.toggle_stem(deck, k, cx))))
                }))
                .into_any_element();
        }
        let state = d.track.as_ref().and_then(|t| crate::stems_model::StemsModel::entity(cx).read(cx).state(&t.id).cloned());
        let label = match &state {
            Some(st @ (crate::stems_model::StemState::Model(_) | crate::stems_model::StemState::Decoding | crate::stems_model::StemState::Splitting(_) | crate::stems_model::StemState::Waiting)) => format!("STEMS {}", st.label()),
            _ => "STEMS".to_string(),
        };
        let busy = state.as_ref().is_some_and(|s| !matches!(s, crate::stems_model::StemState::Ready | crate::stems_model::StemState::Failed(_)));
        pill(label, 88., 17., busy.then_some(col))
            .id(("stems", deck))
            .cursor_pointer()
            .text_size(px(7.))
            .tooltip(tip(words::STEMS_TIP))
            .when(d.track.is_none(), |p| p.opacity(0.4))
            .on_click(cx.listener(move |this, _, _, cx| this.dj.update(cx, |dj, cx| dj.prepare_stems(deck, cx))))
            .into_any_element()
    }

    /// The mixer's less used settings, in a card over it: each deck's filter-knob effect and
    /// crossfader side, the crossfader's curve and effect, hamster, pitch lock, the sampler's level.
    fn mixer_settings(&self, dj: &DjDecks, cx: &Context<Self>) -> impl IntoElement {
        let at = self.mixer_menu_spot.get();
        let row = |label: &'static str| div().flex().items_center().justify_between().gap(px(8.)).child(div().text_size(px(9.5)).text_color(c(TEXT2)).child(tr(label)));
        let opt = |id: (&'static str, usize), label: String, on: Option<Hsla>, w: f32| pill(label, w, 20., on).id(id).cursor_pointer().text_size(px(8.5));
        let per_deck = |label: &'static str, f: &dyn Fn(usize) -> AnyElement| row(label).child(div().flex().gap(px(4.)).child(f(0)).child(f(1)));
        let card = div()
            .id("mixer-settings")
            .occlude()
            .w(px(260.))
            .p(px(10.))
            .flex()
            .flex_col()
            .gap(px(7.))
            .rounded(px(8.))
            .bg(c(0x202328))
            .border_1()
            .border_color(c(LINE))
            .shadow(vec![BoxShadow { color: black().opacity(0.5), offset: point(px(0.), px(10.)), blur_radius: px(28.), spread_radius: px(0.), inset: false }])
            .on_mouse_down_out(cx.listener(|this, _, _, cx| {
                this.mixer_menu = false;
                cx.notify();
            }))
            .child(div().text_size(px(9.)).font_weight(FontWeight::BOLD).text_color(c(TEXT3)).child(tr(words::MIXER_SETTINGS)))
            .child(per_deck(words::SET_COLOR, &|deck| {
                opt(("color", deck), dj.color_label(deck).to_string(), (dj.decks[deck].color != 0).then_some(deck_color(deck)), 74.)
                    .on_click(cx.listener(move |this, _, _, cx| this.dj.update(cx, |dj, cx| dj.cycle_color(deck, cx))))
                    .into_any_element()
            }))
            .child(per_deck(words::SET_SIDE, &|deck| {
                opt(("assign", deck), format!("{} → {}", ["A", "B"][deck], dj.assign_label(deck)), None, 74.)
                    .on_click(cx.listener(move |this, _, _, cx| this.dj.update(cx, |dj, cx| dj.cycle_assign(deck, cx))))
                    .into_any_element()
            }))
            .child(row(words::SET_CURVE).child(opt(("curve", 0), dj.curve.name().to_string(), None, 80.).on_click(cx.listener(|this, _, _, cx| this.dj.update(cx, |dj, cx| dj.cycle_curve(cx))))))
            .child(row(words::SET_MIXFX).child(opt(("mixfx", 0), dj.mix_fx_label().to_string(), (dj.mix_fx != 0).then_some(c(TEXT)), 80.).on_click(cx.listener(|this, _, _, cx| this.dj.update(cx, |dj, cx| dj.cycle_mix_fx(cx))))))
            .child(row(words::SET_HAMSTER).child(opt(("hamster", 0), if dj.hamster { "ON" } else { "OFF" }.into(), dj.hamster.then_some(c(TEXT)), 80.).on_click(cx.listener(|this, _, _, cx| this.dj.update(cx, |dj, cx| dj.toggle_hamster(cx))))))
            .child(row(words::SET_LOCK).child(opt(("plock", 0), if dj.pitch_lock { "ON" } else { "OFF" }.into(), dj.pitch_lock.then_some(c(TEXT)), 80.).on_click(cx.listener(|this, _, _, cx| this.dj.update(cx, |dj, cx| dj.toggle_pitch_lock(cx))))))
            .child(row(words::SET_SAMPLER).child(knob(20., dj.sampler_volume, c(TEXT2)).id("sampler-vol").on_mouse_down(MouseButton::Left, self.grab(Ctl::SamplerVol, 160., cx))));
        deferred(anchored().position(point(at.origin.x - px(240.), at.origin.y - px(8.))).anchor(Anchor::BottomLeft).snap_to_window_with_margin(px(8.)).child(card)).with_priority(2)
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
                knob_lit(knob_d, v, if killed { c(0xff3b3b) } else { deck_color(deck) }, dj.lit(deck, Lamp::Knob(k)))
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
            fader_lit(fader_h, dj.decks[deck].volume, deck_color(deck), dj.lit(deck, Lamp::Fader))
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
            // STEMS: each deck's vocals, drums, bass and melody, in or out (or their preparation).
            .child(div().w_full().px(px(6.)).flex().items_center().justify_between().children([0usize, 1].map(|deck| self.stem_row(dj, deck, cx))))
            // The crossfader, its curve named under it.
            .child(
                div()
                    .pt(px(2.))
                    .flex()
                    .items_center()
                    .gap(px(4.))
                    .child(div().text_size(px(9.)).font_weight(FontWeight::BOLD).text_color(deck_color(0)).child("A"))
                    .child(xfader)
                    .child(div().text_size(px(9.)).font_weight(FontWeight::BOLD).text_color(deck_color(1)).child("B")),
            )
            // The master and what records or shows the mix; the rest of the mixer's settings in ⋯.
            .child(
                div()
                    .w_full()
                    .px(px(8.))
                    .pt(px(2.))
                    .flex()
                    .items_center()
                    .justify_between()
                    .child(
                        div()
                            .flex()
                            .items_center()
                            .gap(px(5.))
                            .child(
                                knob(22., dj.master, c(TEXT))
                                    .id("master")
                                    .tooltip(tip(words::MASTER_TIP))
                                    .on_mouse_down(MouseButton::Left, self.grab(Ctl::Master, 160., cx))
                                    .on_click(cx.listener(|this, e: &ClickEvent, _, cx| {
                                        if e.click_count() == 2 {
                                            this.dj.update(cx, |dj, cx| dj.set_master(0.5, cx));
                                        }
                                    })),
                            )
                            .child(div().flex().gap(px(1.)).child(meter(22., dj.master_vu())).child(meter(22., dj.master_vu()))),
                    )
                    .child(
                        div()
                            .flex()
                            .items_center()
                            .gap(px(4.))
                            .child(
                                pill("Q", 20., 20., dj.quantize.then_some(c(0x3ddc84)))
                                    .id("quantize")
                                    .cursor_pointer()
                                    .tooltip(tip(words::Q_TIP))
                                    .on_click(cx.listener(|this, _, _, cx| this.dj.update(cx, |dj, cx| dj.toggle_quantize(cx)))),
                            )
                            .child(
                                pill("REC", 32., 20., rec_on.then_some(c(0xff3b3b)))
                                    .id("rec")
                                    .cursor_pointer()
                                    .tooltip(tip(words::REC_TIP))
                                    .on_click(cx.listener(|this, _, _, cx| this.dj.update(cx, |dj, cx| dj.toggle_record(cx)))),
                            )
                            .child(
                                pill("VIDEO", 38., 20., self.video_open(cx).then_some(c(0x5ac8fa)))
                                    .id("video")
                                    .cursor_pointer()
                                    .tooltip(tip(words::VIDEO_TIP))
                                    .on_click(cx.listener(|this, _, _, cx| this.toggle_video(cx))),
                            )
                            .child({
                                let spot = self.mixer_menu_spot.clone();
                                pill("⋯", 22., 20., self.mixer_menu.then_some(c(TEXT)))
                                    .id("mixer-more")
                                    .relative()
                                    .cursor_pointer()
                                    .tooltip(tip(words::MORE_TIP))
                                    .child(spot_mark(spot))
                                    .on_click(cx.listener(|this, _, _, cx| {
                                        this.mixer_menu = !this.mixer_menu;
                                        cx.notify();
                                    }))
                            }),
                    ),
            )
            .children(self.mixer_menu.then(|| self.mixer_settings(dj, cx)))
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
    /// The catalogue searched online from the box.
    Online,
    /// The songs whose stems are prepared ahead.
    Stems,
}

/// A song being dragged from the browser or from Up Next onto a deck, or into Up Next (where a
/// song already kept moves to where it is dropped).
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

/// The Up Next panel (or its button) being dragged to the browser's other side.
#[derive(Clone)]
struct PanelMove;

impl Render for PanelMove {
    fn render(&mut self, _window: &mut Window, _cx: &mut Context<Self>) -> impl IntoElement {
        div()
            .px(px(10.))
            .py(px(5.))
            .rounded(px(6.))
            .bg(c(PANEL2))
            .border_1()
            .border_color(c(UPNEXT))
            .text_size(px(10.5))
            .font_weight(FontWeight::SEMIBOLD)
            .text_color(c(UPNEXT))
            .child("UP NEXT")
    }
}

/// Up Next's own colour.
const UPNEXT: u32 = 0xc89bff;
const UPNEXT_W: f32 = 220.;
const STORE_UPNEXT: &str = "console-upnext";

/// A right click on a song: where, which, and its place in Up Next when it is there.
struct RowMenu {
    at: Point<Pixels>,
    track: TrackRef,
    upnext: Option<usize>,
}

pub struct Browser {
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
    /// Songs the DJ keeps ready, shown beside the list (left or right, or hidden); kept.
    upnext_shown: bool,
    upnext_left: bool,
    /// The deck model's Up Next as last drawn (a change redraws the browser).
    upnext_rev: u64,
    menu: Option<RowMenu>,
    ap_bar: Entity<ApBar>,
    /// Where the list sits, to tell which side a dropped Up Next panel goes to.
    middle: Spot,
    core: Arc<crate::core::Core>,
    scroll: UniformListScrollHandle,
    width: f32,
    _subs: Vec<Subscription>,
}

fn tracks_of(values: &[Value]) -> Vec<Track> {
    values.iter().filter_map(|v| serde_json::from_value(v.clone()).ok()).collect()
}

impl Browser {
    fn new(core: Arc<crate::core::Core>, _model: Entity<PlayerModel>, library: Entity<LibraryModel>, dj: Entity<DjDecks>, window: &mut Window, cx: &mut Context<Self>) -> Self {
        use gpui_kit::component::input::{InputEvent, InputState};
        let downloads = crate::downloads::DownloadsModel::entity(cx);
        let input = cx.new(|cx| InputState::new(window, cx).placeholder(tr(words::SEARCH_HINT)));
        // A field the app's single-key shortcuts leave alone while it has the focus.
        crate::ui::register_field(&input.read(cx).focus_handle(cx), cx);
        let subs = vec![
            cx.subscribe_in(&input, window, |b, state, ev: &InputEvent, _w, cx| match ev {
                InputEvent::Change => {
                    let text = state.read(cx).value().to_string();
                    if b.folder == Folder::Online {
                        // Online, typing searches once the hand rests a moment.
                        b.search_soon(text, cx);
                    } else {
                        b.filter = text;
                        b.apply_filter(cx);
                    }
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
            // The stems list: its songs and their progress.
            cx.observe(&crate::stems_model::StemsModel::entity(cx), |b, m, cx| {
                if b.folder == Folder::Stems {
                    if m.read(cx).list.len() != b.all.len() {
                        b.reload(cx);
                    } else {
                        cx.notify();
                    }
                }
            }),
            // The marks of the songs on the decks: when those change, not on every knob turned.
            cx.observe(&dj, |b, dj, cx| {
                let now = [0, 1].map(|d| dj.read(cx).decks[d].track.as_ref().map(|t| t.id.clone()));
                let rev = dj.read(cx).upnext_rev;
                if now != b.on_decks || rev != b.upnext_rev {
                    b.on_decks = now;
                    b.upnext_rev = rev;
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
        let saved = lfly_core::store::get(STORE_UPNEXT).ok().flatten().unwrap_or_default();
        let me = cx.entity().downgrade();
        let ap_dj = dj.clone();
        let ap_bar = cx.new(|cx| ApBar::new(ap_dj, me, cx));
        let mut b = Self {
            ap_bar,
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
            upnext_rev: 0,
            upnext_shown: saved.get("shown").and_then(Value::as_bool).unwrap_or(true),
            upnext_left: saved.get("side").and_then(Value::as_str) == Some("left"),
            menu: None,
            middle: Spot::default(),
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
        // LFLY_CONSOLE_UPNEXT="<seconds>" (tests): the list's first song into Up Next then, and a
        // load asked on deck A while it plays (the question shows).
        if let Some(secs) = std::env::var("LFLY_CONSOLE_UPNEXT").ok().and_then(|v| v.parse::<f64>().ok()) {
            cx.spawn(async move |this, cx| {
                cx.background_executor().timer(Duration::from_secs_f64(secs)).await;
                let _ = this.update(cx, |b, cx| {
                    if let Some(r) = b.tracks.first().and_then(crate::core::track_ref) {
                        b.add_upnext(r.clone(), None, cx);
                        b.dj.update(cx, |dj, cx| dj.load(0, r, cx));
                    }
                });
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
            Folder::Online => self.results.to_vec(),
            Folder::Stems => crate::stems_model::StemsModel::entity(cx).read(cx).list.iter().filter_map(crate::core::track_of).collect(),
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

    /// The folder's songs whose title, artist or album hold every word typed in the box (online,
    /// the box is the search itself: its results all show).
    fn apply_filter(&mut self, cx: &mut Context<Self>) {
        let words: Vec<String> = if self.folder == Folder::Online { Vec::new() } else { self.filter.to_lowercase().split_whitespace().map(str::to_string).collect() };
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

    /// Online, a search half a second after the last key (two letters at least).
    fn search_soon(&mut self, q: String, cx: &mut Context<Self>) {
        self.search_seq += 1;
        let seq = self.search_seq;
        if q.trim().chars().count() < 2 {
            return;
        }
        cx.spawn(async move |this, cx| {
            cx.background_executor().timer(Duration::from_millis(500)).await;
            let _ = this.update(cx, |b, cx| {
                if b.search_seq == seq {
                    b.search_online(q, cx);
                }
            });
        })
        .detach();
    }

    /// The catalogue searched for [q], its songs under Online.
    fn search_online(&mut self, q: String, cx: &mut Context<Self>) {
        let q = q.trim().to_string();
        if q.is_empty() {
            return;
        }
        self.search_seq += 1;
        let seq = self.search_seq;
        self.searching = true;
        self.asked = q.clone();
        if self.folder != Folder::Online {
            self.folder = Folder::Online;
            self.filter.clear();
            self.reload(cx);
        }
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
                        if b.folder == Folder::Online {
                            b.reload(cx);
                            b.scroll.scroll_to_item(0, ScrollStrategy::Top);
                        }
                    }
                    Err(e) => {
                        log::warn!("console search: {e}");
                        crate::toast::Toasts::say(tr(words::SEARCH_FAILED), cx);
                    }
                }
                cx.notify();
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

    /// AUTOPILOT pressed: on, it flies Up Next (or, empty, the list shown); off, it lets go.
    fn toggle_autopilot(&mut self, cx: &mut Context<Self>) {
        if self.dj.read(cx).autopilot_on() {
            self.dj.update(cx, |dj, cx| dj.autopilot_stop(None, cx));
            return;
        }
        // Up Next, live: songs added while it flies are played too, each taken off as it loads.
        if !self.dj.read(cx).upnext.is_empty() {
            self.dj.update(cx, |dj, cx| dj.autopilot_start(Vec::new(), "Up Next".to_string(), cx));
            return;
        }
        let list = self.tracks.iter().filter_map(crate::core::track_ref).collect();
        let name = self.folder_name(cx);
        self.dj.update(cx, |dj, cx| dj.autopilot_start(list, name, cx));
    }

    /// The name of the folder shown.
    fn folder_name(&self, cx: &App) -> String {
        match &self.folder {
            Folder::Liked => tr(words::LIKED).to_string(),
            Folder::Downloads => tr(words::DOWNLOADS).to_string(),
            Folder::History => tr(words::HISTORY).to_string(),
            Folder::Online => format!("{} «{}»", tr(words::ONLINE), self.asked),
            Folder::Local(dir) => crate::local_music::name_of(dir),
            Folder::Playlist(id) => self.library.read(cx).playlists.iter().find(|p| &p.id == id).map(|p| p.name.clone()).unwrap_or_default(),
            Folder::Stems => tr(words::STEMS_FOLDER).to_string(),
        }
    }

    // ---- Up Next ----

    /// Where Up Next shows (its songs are the deck model's).
    fn save_upnext(&self) {
        if crate::deck_model::test_run() {
            return;
        }
        let v = serde_json::json!({ "shown": self.upnext_shown, "side": if self.upnext_left { "left" } else { "right" } });
        if let Err(e) = lfly_core::store::set(STORE_UPNEXT, &v) {
            log::warn!("up next not saved: {e}");
        }
    }

    /// [track] into Up Next before place [at] (the end without one); a song already there moves.
    fn add_upnext(&mut self, track: TrackRef, at: Option<usize>, cx: &mut Context<Self>) {
        self.dj.update(cx, |dj, cx| dj.upnext_add(track, at, cx));
        if !self.upnext_shown {
            self.upnext_shown = true;
            self.save_upnext();
        }
        cx.notify();
    }

    fn remove_upnext(&mut self, i: usize, cx: &mut Context<Self>) {
        self.dj.update(cx, |dj, cx| dj.upnext_remove(i, cx));
        cx.notify();
    }

    fn toggle_upnext(&mut self, cx: &mut Context<Self>) {
        self.upnext_shown = !self.upnext_shown;
        self.save_upnext();
        cx.notify();
    }

    /// The panel dropped: on the side of the list's middle the pointer is on.
    fn place_upnext(&mut self, x: Pixels, cx: &mut Context<Self>) {
        let b = self.middle.get();
        let left = b.size.width > px(0.) && x < b.origin.x + b.size.width / 2.;
        self.upnext_left = left;
        self.upnext_shown = true;
        self.save_upnext();
        cx.notify();
    }

    fn open_menu(&mut self, at: Point<Pixels>, track: TrackRef, upnext: Option<usize>, cx: &mut Context<Self>) {
        self.menu = Some(RowMenu { at, track, upnext });
        cx.notify();
    }

    /// The right-click menu on a song: load it on either deck, keep it in Up Next or take it out.
    fn menu_view(&self, cx: &Context<Self>) -> Option<impl IntoElement> {
        let m = self.menu.as_ref()?;
        let item = |id: &'static str, label: String, color: Hsla| {
            div()
                .id(id)
                .h(px(26.))
                .px(px(10.))
                .flex()
                .items_center()
                .gap(px(8.))
                .rounded(px(4.))
                .text_size(px(12.))
                .text_color(color)
                .cursor_pointer()
                .hover(|d| d.bg(c(0x2c3036)))
                .child(label)
        };
        let (track, upnext) = (m.track.clone(), m.upnext);
        let (t1, t2, t3, t4) = (track.clone(), track.clone(), track.clone(), track.clone());
        let title = track.title.clone();
        Some(
            deferred(
                anchored().position(m.at).snap_to_window_with_margin(px(8.)).child(
                    div()
                        .id("console-menu")
                        .occlude()
                        .w(px(210.))
                        .p(px(4.))
                        .flex()
                        .flex_col()
                        .rounded(px(8.))
                        .bg(c(0x202328))
                        .border_1()
                        .border_color(c(LINE))
                        .shadow(vec![BoxShadow { color: black().opacity(0.5), offset: point(px(0.), px(10.)), blur_radius: px(28.), spread_radius: px(0.), inset: false }])
                        .on_mouse_down_out(cx.listener(|b, _, _, cx| {
                            b.menu = None;
                            cx.notify();
                        }))
                        .child(div().px(px(10.)).py(px(5.)).text_size(px(11.)).text_color(c(TEXT3)).whitespace_nowrap().text_ellipsis().overflow_hidden().child(title))
                        .child(item("cm-a", tr(words::LOAD_A).to_string(), deck_color(0)).on_click(cx.listener(move |b, _, _, cx| {
                            b.menu = None;
                            let t = t1.clone();
                            b.dj.update(cx, |dj, cx| dj.load(0, t, cx));
                        })))
                        .child(item("cm-b", tr(words::LOAD_B).to_string(), deck_color(1)).on_click(cx.listener(move |b, _, _, cx| {
                            b.menu = None;
                            let t = t2.clone();
                            b.dj.update(cx, |dj, cx| dj.load(1, t, cx));
                        })))
                        .child(div().my(px(3.)).h(px(1.)).bg(c(LINE)))
                        .child(match upnext {
                            Some(i) => item("cm-un", tr(words::UPNEXT_REMOVE).to_string(), c(TEXT)).on_click(cx.listener(move |b, _, _, cx| {
                                b.menu = None;
                                b.remove_upnext(i, cx);
                            })),
                            None => item("cm-un", tr(words::UPNEXT_ADD).to_string(), c(UPNEXT)).on_click(cx.listener(move |b, _, _, cx| {
                                b.menu = None;
                                b.add_upnext(t3.clone(), None, cx);
                            })),
                        })
                        .child(if self.folder == Folder::Stems {
                            let id = t4.id.clone();
                            item("cm-st", tr(words::STEMS_REMOVE).to_string(), c(TEXT)).on_click(cx.listener(move |b, _, _, cx| {
                                b.menu = None;
                                let id = id.clone();
                                crate::stems_model::StemsModel::entity(cx).update(cx, |m, cx| m.remove(&id, cx));
                            }))
                        } else {
                            item("cm-st", tr(words::STEMS_PREPARE).to_string(), c(TEXT)).on_click(cx.listener(move |b, _, _, cx| {
                                b.menu = None;
                                let t = t4.clone();
                                crate::stems_model::StemsModel::entity(cx).update(cx, |m, cx| m.prepare(t, false, cx));
                            }))
                        }),
                ),
            )
            .with_priority(2),
        )
    }

    /// The Up Next panel: its header (dragged, it moves the panel to the other side), the songs
    /// kept (dragged onto a deck, or within the list to reorder), a drop zone for more.
    fn upnext_view(&self, cx: &Context<Self>) -> impl IntoElement {
        let me = cx.entity().downgrade();
        let songs = self.dj.read(cx).upnext.clone();
        let count = songs.len();
        let header = div()
            .id("un-head")
            .h(px(22.))
            .flex_none()
            .flex()
            .items_center()
            .gap(px(6.))
            .px(px(8.))
            .bg(c(0x1e2024))
            .border_b_1()
            .border_color(c(LINE))
            .cursor(CursorStyle::OpenHand)
            .on_drag(PanelMove, |p, _, _, cx| cx.new(|_| p.clone()))
            .tooltip(tip(words::UPNEXT_MOVE))
            .child(div().text_size(px(10.)).text_color(c(TEXT3)).child("⠿"))
            .child(div().flex_1().text_size(px(9.5)).font_weight(FontWeight::SEMIBOLD).text_color(c(UPNEXT)).child(format!("UP NEXT · {count}")))
            .when(count > 0, |d| {
                d.child(
                    div()
                        .id("un-clear")
                        .cursor_pointer()
                        .tooltip(tip(words::UPNEXT_CLEAR))
                        .on_click(cx.listener(|b, _, _, cx| b.dj.update(cx, |dj, cx| dj.upnext_clear(cx))))
                        .child(icon("trash", 11., c(TEXT3))),
                )
            })
            .child(div().id("un-hide").cursor_pointer().on_click(cx.listener(|b, _, _, cx| b.toggle_upnext(cx))).child(icon("close", 11., c(TEXT3))));
        let rows = songs.iter().enumerate().map(|(i, t)| {
            let deck_of = self.on_decks.iter().position(|d| d.as_deref() == Some(t.id.as_str()));
            let (me, menu_t, load_t) = (me.clone(), t.clone(), t.clone());
            let dj = self.dj.clone();
            div()
                .id(("un", i))
                .px(px(10.))
                .py(px(4.))
                .cursor_pointer()
                .hover(|d| d.bg(c(0x23262b)))
                .on_drag(DraggedTrack { track: t.clone(), title: t.title.clone() }, |d, _, _, cx| cx.new(|_| d.clone()))
                .drag_over::<DraggedTrack>(|st, _, _, _| st.border_t_2().border_color(c(UPNEXT)))
                .on_drop(cx.listener(move |b, d: &DraggedTrack, _, cx| {
                    cx.stop_propagation();
                    b.add_upnext(d.track.clone(), Some(i), cx);
                }))
                .on_mouse_down(MouseButton::Right, move |e, _, cx| {
                    cx.stop_propagation();
                    let _ = me.update(cx, |b, cx| b.open_menu(e.position, menu_t.clone(), Some(i), cx));
                })
                .on_click(move |e, _, cx| {
                    if e.click_count() == 2 {
                        let t = load_t.clone();
                        dj.update(cx, |dj, cx| {
                            let deck = (0..2).find(|d| dj.decks[*d].track.is_none()).unwrap_or(if dj.is_playing(0) { 1 } else { 0 });
                            dj.load(deck, t, cx);
                        });
                    }
                })
                .child(div().text_size(px(11.)).text_color(deck_of.map_or(c(TEXT), deck_color)).whitespace_nowrap().text_ellipsis().overflow_hidden().child(t.title.clone()))
                .child(div().text_size(px(10.)).text_color(c(TEXT3)).whitespace_nowrap().text_ellipsis().overflow_hidden().child(t.artist.clone()))
        });
        let side_line = if self.upnext_left { div().border_r_1() } else { div().border_l_1() };
        side_line
            .id("console-upnext")
            .w(px(UPNEXT_W))
            .flex_none()
            .h_full()
            .flex()
            .flex_col()
            .border_color(c(LINE))
            .bg(c(0x16181b))
            .drag_over::<DraggedTrack>(|st, _, _, _| st.bg(c(UPNEXT).opacity(0.08)))
            .on_drop(cx.listener(|b, d: &DraggedTrack, _, cx| b.add_upnext(d.track.clone(), None, cx)))
            .child(header)
            .child(
                div()
                    .id("console-upnext-list")
                    .flex_1()
                    .min_h_0()
                    .overflow_y_scroll()
                    .children(rows)
                    .when(count == 0, |d| d.child(div().p(px(12.)).text_size(px(11.)).text_color(c(TEXT3)).child(tr(words::UPNEXT_EMPTY)))),
            )
    }
}

impl Render for Browser {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let this = cx.entity().downgrade();
        let on_decks = self.on_decks.clone();

        // The folders: LFly's own sources.
        let folder_row = |id: SharedString, label: String, name: &'static str, folder: Folder, on: bool| {
            let this = this.clone();
            div()
                .id(id)
                .h(px(24.))
                .flex()
                .items_center()
                .gap(px(7.))
                .px(px(8.))
                .rounded(px(4.))
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
        let section = |label: String| div().pt(px(12.)).pb(px(4.)).px(px(8.)).text_size(px(9.)).font_weight(FontWeight::SEMIBOLD).text_color(c(TEXT3)).child(label);
        let playlists = self.library.read(cx).playlists.clone();
        let folders = self.folders.clone();
        let tree_w = if self.width < 900. { 150. } else { 190. };
        let upnext_on = self.upnext_shown && self.width >= 640.;
        // The list's columns in pixels: a uniform list's rows are sized by their content.
        let list_w = self.width - tree_w - if upnext_on { UPNEXT_W } else { 0. } - 2.;
        let artist_w = (list_w * 0.24).floor();
        let album_w = if list_w < 520. { 0. } else { (list_w * 0.2).floor() };
        let online_label = if self.searching {
            tr(words::SEARCHING).to_string()
        } else if self.asked.is_empty() {
            tr(words::ONLINE).to_string()
        } else {
            format!("{} «{}»", tr(words::ONLINE), self.asked)
        };
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
            .drag_over::<PanelMove>(|st, _, _, _| st.bg(c(UPNEXT).opacity(0.06)))
            .on_drop(cx.listener(|b, _: &PanelMove, _, cx| {
                b.upnext_left = true;
                b.upnext_shown = true;
                b.save_upnext();
                cx.notify();
            }))
            .child(folder_row("cf-online".into(), online_label, "wifi", Folder::Online, self.folder == Folder::Online))
            .child(folder_row("cf-liked".into(), tr(words::LIKED).into(), "heart", Folder::Liked, self.folder == Folder::Liked))
            .child(folder_row("cf-dl".into(), tr(words::DOWNLOADS).into(), "download", Folder::Downloads, self.folder == Folder::Downloads))
            .child(folder_row("cf-history".into(), tr(words::HISTORY).into(), "clock", Folder::History, self.folder == Folder::History))
            .child({
                let n = crate::stems_model::StemsModel::entity(cx).read(cx).list.len();
                folder_row("cf-stems".into(), format!("{} · {n}", tr(words::STEMS_FOLDER)), "music", Folder::Stems, self.folder == Folder::Stems)
                    .drag_over::<DraggedTrack>(|st, _, _, _| st.bg(c(0x3ddc84).opacity(0.15)))
                    .on_drop(cx.listener(|_, d: &DraggedTrack, _, cx| {
                        let t = d.track.clone();
                        crate::stems_model::StemsModel::entity(cx).update(cx, |m, cx| m.prepare(t, false, cx));
                    }))
            })
            .child(section(tr(words::FOLDERS).to_string()))
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
                    .h(px(24.))
                    .flex()
                    .items_center()
                    .gap(px(7.))
                    .px(px(8.))
                    .rounded(px(4.))
                    .cursor_pointer()
                    .hover(|d| d.bg(c(0x22252a)))
                    .on_click(cx.listener(|b, _, _, cx| b.add_folder(cx)))
                    .child(icon("plus", 12., c(TEXT2)))
                    .child(div().text_size(px(11.5)).text_color(c(TEXT2)).child(tr(words::ADD_FOLDER))),
            )
            .child(section(tr(words::PLAYLISTS).to_uppercase()))
            .children(playlists.into_iter().map(|p| {
                let on = self.folder == Folder::Playlist(p.id.clone());
                folder_row(format!("cf-pl-{}", p.id).into(), p.name.clone(), "music", Folder::Playlist(p.id.clone()), on)
            }));

        // The list: a table of the folder's songs. A double click loads one on the free deck, a
        // right click opens its menu, a drag takes it onto a deck or into Up Next.
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
            .border_b_1()
            .border_color(c(LINE))
            .text_size(px(9.5))
            .font_weight(FontWeight::SEMIBOLD)
            .text_color(c(TEXT3))
            .child(div().w(px(14.)).flex_none())
            .child(div().flex_1().min_w_0().child(tr(words::TITLE)))
            .child(div().w(px(artist_w)).flex_none().child(tr(words::ARTIST)))
            .when(album_w > 0., |d| d.child(div().w(px(album_w)).flex_none().child(tr(words::ALBUM))))
            .child(div().w(px(34.)).flex_none().text_right().child("KEY"))
            .child(div().w(px(56.)).flex_none().text_right().child(if self.folder == Folder::Stems { "STEMS" } else { "BPM" }))
            .child(div().w(px(46.)).flex_none().text_right().child(tr(words::LENGTH)));
        let list_this = this.clone();
        // In the stems list, each song's state where the tempo goes.
        let stem_states: Arc<std::collections::HashMap<String, crate::stems_model::StemState>> = Arc::new(if self.folder == Folder::Stems {
            let m = crate::stems_model::StemsModel::entity(cx);
            let m = m.read(cx);
            self.tracks.iter().filter_map(|t| m.state(&t.id).map(|s| (t.id.clone(), s.clone()))).collect()
        } else {
            Default::default()
        });
        // Tempo and key of the songs the decks have analysed (Camelot, as DJs read it).
        let known: Arc<std::collections::HashMap<String, (Option<f64>, Option<String>)>> = {
            let dj = self.dj.read(cx);
            Arc::new(self.tracks.iter().filter_map(|t| dj.known(&t.id).map(|k| (t.id.clone(), k))).collect())
        };
        let list = uniform_list("console-list", count, move |range: Range<usize>, _, _| {
            range
                .map(|i| {
                    let t = &tracks[i];
                    let deck_of = on_decks.iter().position(|d| d.as_deref() == Some(t.id.as_str()));
                    let on = deck_of.is_some();
                    let live_col = deck_color(deck_of.unwrap_or(0));
                    let sel = selected == Some(i);
                    let (this, menu_this, dj, all) = (list_this.clone(), list_this.clone(), dj.clone(), tracks.clone());
                    let text = if on { live_col } else { c(TEXT) };
                    let reference = crate::core::track_ref(t);
                    let dragged = reference.clone().map(|r| DraggedTrack { title: t.title.clone(), track: r });
                    div()
                        .id(("cl", i))
                        .w(px(list_w))
                        .when_some(dragged, |row, d| row.on_drag(d, |d, _, _, cx| cx.new(|_| d.clone())))
                        .h(px(24.))
                        .flex()
                        .items_center()
                        .px(px(10.))
                        .gap(px(8.))
                        .text_size(px(11.5))
                        .when(i % 2 == 1, |d| d.bg(c(0x17191c)))
                        .when(sel, |d| d.bg(c(0x2a2e34)))
                        .hover(|d| d.bg(c(0x23262b)))
                        .when_some(reference, |row, r| {
                            row.on_mouse_down(MouseButton::Right, move |e, _, cx| {
                                cx.stop_propagation();
                                let _ = menu_this.update(cx, |b, cx| {
                                    b.selected = Some(i);
                                    b.open_menu(e.position, r.clone(), None, cx);
                                });
                            })
                        })
                        .on_click(move |e, _, cx| {
                            if e.click_count() == 2 {
                                if let Some(r) = crate::core::track_ref(&all[i]) {
                                    dj.update(cx, |dj, cx| {
                                        // An empty deck first (A before B), else one not playing.
                                        let empty = (0..2).find(|d| dj.decks[*d].track.is_none());
                                        let deck = empty.unwrap_or(if dj.is_playing(0) { 1 } else { 0 });
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
                        .child(div().w(px(14.)).flex_none().flex().items_center().child(match deck_of {
                            Some(d) => div().text_size(px(9.)).font_weight(FontWeight::BOLD).text_color(live_col).child(if d == 0 { "A" } else { "B" }).into_any_element(),
                            None => icon("music", 10., c(TEXT3)).into_any_element(),
                        }))
                        .child(div().flex_1().min_w_0().text_color(text).whitespace_nowrap().text_ellipsis().overflow_hidden().child(t.title.clone()))
                        .child(div().w(px(artist_w)).flex_none().text_color(if on { live_col } else { c(TEXT2) }).whitespace_nowrap().text_ellipsis().overflow_hidden().child(t.artist.clone()))
                        .when(album_w > 0., |d| d.child(div().w(px(album_w)).flex_none().text_color(c(TEXT3)).whitespace_nowrap().text_ellipsis().overflow_hidden().child(t.album.clone().unwrap_or_default())))
                        .child(div().w(px(34.)).flex_none().text_right().text_color(c(0x9b8cff)).child(known.get(&t.id).and_then(|k| k.1.clone()).unwrap_or_default()))
                        .child(div().w(px(56.)).flex_none().text_right().text_color(c(TEXT2)).whitespace_nowrap().child(match stem_states.get(&t.id) {
                            Some(st) => st.label(),
                            None => t.bpm.filter(|b| *b > 0.).map(|b| b as f64).or(known.get(&t.id).and_then(|k| k.0)).map(|b| format!("{b:.1}")).unwrap_or_default(),
                        }))
                        .child(div().w(px(46.)).flex_none().text_right().text_color(c(TEXT2)).child(if t.duration_ms > 0 { clock(t.duration_ms) } else { String::new() }))
                })
                .collect::<Vec<_>>()
        })
        .track_scroll(&self.scroll)
        .flex_1()
        .min_h_0();
        let typed = self.filter.trim().to_string();
        // UP NEXT: on and off with a click; dragged, it puts the panel on the side it is dropped.
        let upnext_button = div()
            .id("upnext-toggle")
            .h(px(20.))
            .px(px(8.))
            .flex()
            .items_center()
            .gap(px(5.))
            .rounded(px(4.))
            .cursor_pointer()
            .bg(if self.upnext_shown { c(UPNEXT).opacity(0.18) } else { c(PANEL2) })
            .border_1()
            .border_color(if self.upnext_shown { c(UPNEXT).opacity(0.6) } else { c(LINE) })
            .text_size(px(9.5))
            .font_weight(FontWeight::SEMIBOLD)
            .text_color(if self.upnext_shown { c(UPNEXT) } else { c(TEXT2) })
            .on_click(cx.listener(|b, _, _, cx| b.toggle_upnext(cx)))
            .on_drag(PanelMove, |p, _, _, cx| cx.new(|_| p.clone()))
            .tooltip(tip(words::UPNEXT_MOVE))
            .child(icon("queue", 11., if self.upnext_shown { c(UPNEXT) } else { c(TEXT2) }))
            .child({
                let n = self.dj.read(cx).upnext.len();
                format!("UP NEXT{}", if n == 0 { String::new() } else { format!(" {n}") })
            });
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
            .child(icon(if self.folder == Folder::Online { "wifi" } else { "search" }, 12., c(TEXT3)))
            .child(div().flex_1().min_w_0().text_size(px(11.5)).child(gpui_kit::component::input::Input::new(&self.input).appearance(false).cleanable(true)))
            .when(self.searching, |d| d.child(div().text_size(px(10.)).text_color(c(TEXT3)).child(tr(words::SEARCHING))))
            .child(upnext_button);
        let empty_text = match &self.folder {
            _ if self.scanning => tr(words::LOADING).to_string(),
            Folder::Online if self.asked.is_empty() => tr(words::ONLINE_HINT).to_string(),
            Folder::Stems => tr(words::STEMS_HINT).to_string(),
            Folder::Local(dir) if !dir.is_dir() => tr(words::FOLDER_MISSING).to_string(),
            _ if !typed.is_empty() => tr(words::NOTHING_FOUND).to_string(),
            _ => tr(words::NO_SONGS).to_string(),
        };
        let middle = div()
            .relative()
            .flex_1()
            .min_w_0()
            .h_full()
            .flex()
            .flex_col()
            .child(spot_mark(self.middle.clone()))
            .drag_over::<PanelMove>(|st, _, _, _| st.bg(c(UPNEXT).opacity(0.06)))
            .on_drop(cx.listener(|b, _: &PanelMove, window, cx| b.place_upnext(window.mouse_position().x, cx)))
            .child(search_bar)
            .child(self.ap_bar.clone())
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

        let upnext = upnext_on.then(|| self.upnext_view(cx));
        let (left, right) = if self.upnext_left { (upnext, None) } else { (None, upnext) };
        div()
            .size_full()
            .flex()
            .bg(c(BG))
            .child(tree)
            .children(left)
            .child(middle)
            .children(right)
            .children(self.menu_view(cx))
    }
}

// ---- AUTOPILOT ----

/// The autopilot's lamp: green as an engaged autopilot's.
const AP_ON: u32 = 0x3ddc84;

/// The strip over the list: the AP button, what it flies, the steps of the change going on with
/// the time to the mix and its progress, the songs on air and next, and what it did last.
pub struct ApBar {
    dj: Entity<DjDecks>,
    browser: WeakEntity<Browser>,
    ticking: bool,
    _sub: Subscription,
}

impl ApBar {
    fn new(dj: Entity<DjDecks>, browser: WeakEntity<Browser>, cx: &mut Context<Self>) -> Self {
        let sub = cx.observe(&dj, |bar, dj, cx| {
            if dj.read(cx).autopilot_on() {
                bar.keep_ticking(cx);
            }
            cx.notify();
        });
        Self { dj, browser, ticking: false, _sub: sub }
    }

    /// The countdown and the mix's progress move four times a second while it flies.
    fn keep_ticking(&mut self, cx: &mut Context<Self>) {
        if self.ticking {
            return;
        }
        self.ticking = true;
        cx.spawn(async move |this, cx| loop {
            cx.background_executor().timer(Duration::from_millis(250)).await;
            let on = this
                .update(cx, |bar, cx| {
                    let on = bar.dj.read(cx).autopilot_on();
                    if !on {
                        bar.ticking = false;
                    }
                    cx.notify();
                    on
                })
                .unwrap_or(false);
            if !on {
                break;
            }
        })
        .detach();
    }
}

impl Render for ApBar {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        use crate::autopilot::Phase;
        let dj = self.dj.read(cx);
        let ap = dj.autopilot.as_ref();
        let on = ap.is_some();
        let lamp = c(AP_ON);
        let browser = self.browser.clone();
        let button = div()
            .id("ap-button")
            .h(px(24.))
            .px(px(9.))
            .flex()
            .flex_none()
            .items_center()
            .gap(px(6.))
            .rounded(px(5.))
            .cursor_pointer()
            .bg(if on { lamp.opacity(0.2) } else { c(PANEL2) })
            .border_1()
            .border_color(if on { lamp } else { c(LINE) })
            .hover(|d| d.bg(c(0x2c3036)))
            .tooltip(tip(words::AP_TIP))
            .on_click(move |_, _, cx| {
                let _ = browser.update(cx, |b, cx| b.toggle_autopilot(cx));
            })
            .child(icon("autopilot", 15., if on { lamp } else { c(TEXT2) }))
            .child(div().text_size(px(10.5)).font_weight(FontWeight::BOLD).text_color(if on { lamp } else { c(TEXT2) }).child("AP"));
        // From the left, cut at the right edge when the room ends (never over the folders).
        let row = div().h(px(34.)).flex_none().flex().items_center().gap(px(10.)).px(px(10.)).overflow_hidden().bg(c(0x14161a)).border_b_1().border_color(c(LINE)).child(button);
        let Some(ap) = ap else {
            return row.child(div().flex_1().min_w_0().truncate().text_size(px(11.)).text_color(c(TEXT3)).child(tr(words::AP_HINT)));
        };
        let (phase, to_mix, mixed, style) = dj.autopilot_status().unwrap_or((Phase::Starting, None, None, None));
        // The steps of a change of song, the one going on lit, those done ticked.
        let at = match phase {
            Phase::Starting | Phase::Loading => 0,
            Phase::Waiting => 2,
            Phase::Mixing => 3,
            Phase::Last => 4,
        };
        let labels = [
            tr(words::AP_LOAD).to_string(),
            "SYNC".to_string(),
            match to_mix {
                Some(s) if phase == Phase::Waiting => format!("{} -{}", tr(words::AP_WAIT), clock((s * 1000.) as i64 + 999)),
                _ => tr(words::AP_WAIT).to_string(),
            },
            match (mixed, &style) {
                (Some(p), Some(s)) if phase == Phase::Mixing => format!("{s} {:.0}%", p * 100.),
                (_, Some(s)) => s.clone(),
                _ => "MIX".to_string(),
            },
            tr(words::AP_ON_AIR).to_string(),
        ];
        let steps = div().flex().flex_none().items_center().gap(px(3.)).children(labels.into_iter().enumerate().map(|(i, l)| {
            let (bg, fg, border) = if i == at {
                (lamp.opacity(0.22), lamp, lamp)
            } else if i < at {
                (c(PANEL2), c(TEXT2), c(LINE))
            } else {
                (c(0x1a1c20), c(TEXT3), c(LINE))
            };
            div()
                .flex()
                .items_center()
                .gap(px(3.))
                .when(i > 0, |d| d.child(div().text_size(px(9.)).text_color(c(TEXT3)).child("›")))
                .child(div().h(px(18.)).px(px(7.)).flex().items_center().rounded(px(9.)).bg(bg).border_1().border_color(border).text_size(px(9.)).font_weight(FontWeight::SEMIBOLD).text_color(fg).child(l))
        }));
        let title = |d: usize| dj.decks[d].track.as_ref().map(|t| t.title.clone());
        let live = ap.live;
        let now_next = div()
            .flex()
            .flex_none()
            .items_center()
            .gap(px(5.))
            .max_w(px(320.))
            .text_size(px(10.5))
            .child(div().text_color(deck_color(live)).font_weight(FontWeight::BOLD).child(format!("{} ▶", ["A", "B"][live])))
            .child(div().max_w(px(130.)).truncate().child(title(live).unwrap_or_else(|| "—".into())))
            .children(title(1 - live).map(|t| {
                div().flex().items_center().gap(px(5.)).child(div().text_color(c(TEXT3)).child("→")).child(div().text_color(deck_color(1 - live)).font_weight(FontWeight::BOLD).child(["A", "B"][1 - live])).child(div().max_w(px(130.)).truncate().text_color(c(TEXT2)).child(t))
            }));
        let last = ap.log.last().cloned().unwrap_or_default();
        let count = format!("{} · {}/{}", ap.name, ap.next.min(ap.list.len()), ap.list.len());
        row.child(div().flex_none().max_w(px(150.)).truncate().text_size(px(10.)).text_color(c(TEXT3)).child(count))
            .child(steps)
            .child(now_next)
            .child(div().flex_1().min_w_0().truncate().text_size(px(10.)).text_color(c(TEXT2)).child(last))
    }
}

/// A short label shown while the pointer rests on a control.
struct Tip(SharedString);

impl Render for Tip {
    fn render(&mut self, _window: &mut Window, _cx: &mut Context<Self>) -> impl IntoElement {
        div().px(px(8.)).py(px(4.)).rounded(px(5.)).bg(c(0x2a2d33)).border_1().border_color(c(LINE)).text_size(px(11.)).text_color(c(TEXT)).child(self.0.clone())
    }
}

pub(crate) fn tip(text: &'static str) -> impl Fn(&mut Window, &mut App) -> AnyView + 'static {
    move |_, cx| cx.new(|_| Tip(tr(text).into())).into()
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

#[cfg(test)]
mod theme_tests {
    use super::{c, set_theme, BG, DECK};
    use gpui_kit::rgb;

    #[test]
    fn greys_take_the_tint_colours_and_extremes_stay() {
        let plain = c(BG);
        assert!(set_theme(&crate::theme::build_scheme(crate::theme::Mode::DynamicDark, Some(crate::theme::Rgb::hex(0xd62f2f)), None)));
        assert_ne!(c(BG), plain, "a dark grey leans to the tint");
        assert_eq!(c(0x000000), rgb(0x000000).into());
        assert_eq!(c(DECK[0]), rgb(DECK[0]).into(), "a colour is left alone");
        // AMOLED black: plain greys again.
        set_theme(&crate::theme::build_scheme(crate::theme::Mode::AmoledBlack, None, None));
        assert_eq!(c(BG), plain);
    }
}
