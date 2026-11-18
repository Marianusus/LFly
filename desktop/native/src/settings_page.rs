//! Settings (Tauri: pages/Settings.svelte, parts/SettingsExtra.svelte, components/ThemeCard.svelte):
//! the tabs, then the look (modes, accent, Liquid Glass), general, playback, lyrics, devices,
//! account, space and privacy, about. Everything shown changes something real, in the stores the
//! Tauri window uses too. What the native window cannot do yet says so instead of pretending.

use std::sync::Arc;
use std::time::Instant;

use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;

use crate::core::Core;
use crate::covers::Covers;
use crate::glass;
use crate::i18n::{t, tr, trf};
use crate::icons::icon;
use crate::library_model::LibraryModel;
use crate::look::Look;
use crate::lyrics::LyricsData;
use crate::player_model::PlayerModel;
use crate::prefs::{Prefs, PrefsData};
use crate::slider::{slider, SliderBounds, SliderStyle};
use crate::theme::{build_scheme, accent_color, Mode, Rgb, Scheme, ACCENTS, MODES};
use crate::toast::Toasts;

#[derive(Clone, Copy, PartialEq)]
pub enum Section {
    General,
    Appearance,
    Sound,
    Lyrics,
    Devices,
    Account,
    Storage,
    About,
}

/// The lyrics sources' names and captions (lfly-yt `lyrics/order.rs`, in Italian), looked up where
/// Settings shows them; listed here so the translation table's test knows them.
#[allow(dead_code)]
const SOURCE_WORDS: [&str; 15] = [
    t("Tempi di Apple per registrazione, sillaba per sillaba"),
    t("Tempi di Apple abbinati alla registrazione stessa"),
    t("Tempi di Apple Music, parola per parola"),
    t("Tempi karaoke di QQ Music tramite BetterLyrics"),
    t("Testi di Spotify; serve la chiave PaxSenix"),
    t("Tempi di Musixmatch; serve la chiave PaxSenix"),
    t("Sillaba per sillaba, su mirror della comunità"),
    t("Scritti dagli ascoltatori: ha anche ciò che nessuno ha licenziato"),
    t("Sottotitoli a tempo del video che suona"),
    t("La scheda Testo del video che suona"),
    t("Righe intere, forte fuori dal catalogo inglese"),
    t("Solo righe intere, e sempre in linea"),
    t("Righe intere, dal catalogo di testi più grande"),
    t("Solo testo, se nessun'altra fonte ha i tempi"),
    t("Sottotitoli di YouTube"),
];

const TABS: [(Section, &str); 8] = [
    (Section::General, t("Generale")),
    (Section::Appearance, t("Aspetto")),
    (Section::Sound, t("Riproduzione")),
    (Section::Lyrics, t("Testi")),
    (Section::Devices, t("Dispositivi")),
    (Section::Account, t("Account")),
    (Section::Storage, t("Spazio e privacy")),
    (Section::About, t("Informazioni")),
];

/// The colours of the shared pieces (apply.ts): cards, fills, the separator.
#[derive(Clone, Copy)]
struct Ink {
    s_text: Hsla,
    text2: Hsla,
    accent: Hsla,
    on_accent: Hsla,
    card: Hsla,
    fill: Hsla,
    fill2: Hsla,
    border: Hsla,
    sep: Hsla,
    surface3: Hsla,
    bg: Hsla,
    light: bool,
}

impl Ink {
    fn of(s: &Scheme) -> Self {
        let light = s.light;
        Self {
            s_text: s.text,
            text2: s.text2,
            accent: s.accent.hsla(1.),
            on_accent: s.on_accent.hsla(1.),
            card: if light { white().opacity(0.72) } else { white().opacity(0.065) },
            fill: if light { Rgb::hex(0x15151a).hsla(0.06) } else { white().opacity(0.08) },
            fill2: if light { Rgb::hex(0x15151a).hsla(0.1) } else { white().opacity(0.14) },
            border: s.glass_border,
            sep: crate::track_row::separator(s),
            surface3: if light { white() } else { s.surface3.hsla(1.) },
            bg: s.bg.hsla(1.),
            light,
        }
    }
}

/// A two-step button for what cannot be undone: the first click asks, the second does it.
struct Confirm {
    what: &'static str,
    at: Instant,
}

pub struct SettingsPage {
    core: Arc<Core>,
    look: Entity<Look>,
    library: Entity<LibraryModel>,
    lyrics: Entity<LyricsData>,
    model: Entity<PlayerModel>,
    covers: Entity<Covers>,
    pub section: Section,
    pub page_width: f32,
    playback: lfly_core::settings::Playback,
    glass_bounds: SliderBounds,
    bass_bounds: SliderBounds,
    cross_bounds: SliderBounds,
    band_bounds: [SliderBounds; 5],
    strength_draft: Option<f32>,
    confirm: Option<Confirm>,
    cache_size: Option<u64>,
    /// Whether LFly starts with the session (its autostart file exists).
    autostart: bool,
    /// Devices > Nome: this computer's name being edited.
    name_input: Option<Entity<gpui_kit::component::input::InputState>>,
    /// Testi > Chiave PaxSenix, being typed.
    pax_input: Option<Entity<gpui_kit::component::input::InputState>>,
    health: Vec<lfly_yt::SourceHealth>,
    _subs: Vec<Subscription>,
}

/// SettingsExtra `size`: 1,2 GB, 340 MB, 12 kB, "vuota".
fn size(b: u64) -> String {
    if b >= 1_000_000_000 {
        format!("{} GB", crate::i18n::decimal(b as f64 / 1e9, 1))
    } else if b >= 1_000_000 {
        format!("{} MB", (b as f64 / 1e6).round())
    } else if b > 0 {
        format!("{} kB", ((b as f64 / 1e3).round() as u64).max(1))
    } else {
        tr("vuota").into()
    }
}

fn dir_size(dir: &std::path::Path) -> u64 {
    let Ok(entries) = std::fs::read_dir(dir) else { return 0 };
    entries
        .flatten()
        .map(|e| match e.file_type() {
            Ok(t) if t.is_dir() => dir_size(&e.path()),
            Ok(_) => e.metadata().map_or(0, |m| m.len()),
            Err(_) => 0,
        })
        .sum()
}

/// This window's audio cache (its own, beside the Tauri app's).
fn cache_dir() -> Option<std::path::PathBuf> {
    Some(dirs::cache_dir()?.join("lfly").join("audio-native"))
}

impl SettingsPage {
    pub fn new(
        core: Arc<Core>,
        look: Entity<Look>,
        library: Entity<LibraryModel>,
        lyrics: Entity<LyricsData>,
        model: Entity<PlayerModel>,
        covers: Entity<Covers>,
        cx: &mut Context<Self>,
    ) -> Self {
        let subs = vec![
            cx.observe(&look, |_, _, cx| cx.notify()),
            cx.observe(&library, |_, _, cx| cx.notify()),
            cx.observe(&Prefs::entity(cx), |_, _, cx| cx.notify()),
            cx.observe(&covers, |_, _, cx| cx.notify()),
            cx.observe(&crate::audio::AudioModel::entity(cx), |_, _, cx| cx.notify()),
            cx.observe(&crate::media::SystemMedia::entity(cx), |_, _, cx| cx.notify()),
            cx.observe(&crate::downloads::DownloadsModel::entity(cx), |_, _, cx| cx.notify()),
            cx.observe(&crate::sync::SyncModel::entity(cx), |_, _, cx| cx.notify()),
            cx.observe(&crate::midi::MidiModel::entity(cx), |_, _, cx| cx.notify()),
            cx.observe(&crate::account::AccountModel::entity(cx), |_, _, cx| cx.notify()),
            cx.observe(&crate::connect::ConnectModel::entity(cx), |_, _, cx| cx.notify()),
            cx.subscribe(&model, |_, _, _: &crate::player_model::PlayerEvent, cx| cx.notify()),
        ];
        Self {
            core,
            look,
            library,
            lyrics,
            model,
            covers,
            section: match std::env::var("LFLY_SETTINGS").as_deref() {
                Ok("general") => Section::General,
                Ok("sound") => Section::Sound,
                Ok("lyrics") => Section::Lyrics,
                Ok("devices") => Section::Devices,
                Ok("account") => Section::Account,
                Ok("storage") => Section::Storage,
                Ok("about") => Section::About,
                _ => Section::Appearance,
            },
            page_width: 800.,
            playback: lfly_core::settings::playback(),
            glass_bounds: SliderBounds::default(),
            bass_bounds: SliderBounds::default(),
            cross_bounds: SliderBounds::default(),
            band_bounds: Default::default(),
            strength_draft: None,
            confirm: None,
            cache_size: None,
            autostart: lfly_core::settings::autostart(),
            name_input: None,
            pax_input: None,
            health: Vec::new(),
            _subs: subs,
        }
    }

    /// Called when the page is opened: what it shows is read again.
    pub fn refresh(&mut self, cx: &mut Context<Self>) {
        self.playback = lfly_core::settings::playback();
        if self.section == Section::Sound {
            crate::audio::AudioModel::entity(cx).update(cx, |a, cx| a.load_devices(cx));
        }
        self.health = lfly_core::pages::health(&self.core.yt);
        cx.spawn(async move |this, cx| {
            let n = cx.background_executor().spawn(async move { cache_dir().map_or(0, |d| dir_size(&d)) }).await;
            let _ = this.update(cx, |this, cx| {
                this.cache_size = Some(n);
                cx.notify();
            });
        })
        .detach();
    }

    fn asking(&self, what: &str) -> bool {
        self.confirm.as_ref().is_some_and(|c| c.what == what && c.at.elapsed().as_secs() < 4)
    }

    fn set_playback(&mut self, f: impl FnOnce(&mut lfly_core::settings::Playback), cx: &mut Context<Self>) {
        f(&mut self.playback);
        if let Err(e) = lfly_core::settings::set_playback(&self.core.yt, &self.playback) {
            Toasts::say(e, cx);
        }
        cx.notify();
    }
}

// --- the shared pieces (app.css) ------------------------------------------------------------

/// `.group`: 28 px above, a 17px 600 title, an optional foot under it.
fn group(title: &str, foot: Option<&str>, i: &Ink) -> Div {
    div().mt(px(28.)).child(
        div()
            .mx(px(4.))
            .mb(px(12.))
            .child(div().text_size(px(17.)).line_height(px(21.)).font_weight(FontWeight::SEMIBOLD).child(title.to_string()))
            .children(foot.map(|f| div().mt(px(4.)).max_w(px(640.)).line_height(px(19.5)).text_color(i.text2).child(f.to_string()))),
    )
}

/// `.foot.below`.
fn foot(text: &str, i: &Ink) -> Div {
    div().mt(px(10.)).mx(px(4.)).max_w(px(640.)).line_height(px(19.5)).text_color(i.text2).child(text.to_string())
}

/// `.lfly-list`: a rounded card of rows with hairlines between them.
fn list(rows: Vec<AnyElement>, i: &Ink) -> Div {
    let n = rows.len();
    div()
        .flex()
        .flex_col()
        .rounded(px(12.))
        .bg(i.card)
        .border_1()
        .border_color(i.border.opacity(0.5))
        .overflow_hidden()
        .children(rows.into_iter().enumerate().map(move |(k, r)| {
            div().relative().child(r).when(k > 0 && k < n, |d| d.child(div().absolute().top_0().left(px(14.)).right_0().h(px(1.)).bg(i.sep)))
        }))
}

/// `.lfly-list-row`.
fn row() -> Div {
    div().flex().items_center().gap(px(12.)).min_h(px(44.)).px(px(14.)).py(px(10.))
}

/// `.grow` with a bold title and a caption.
fn label(title: &str, caption: Option<String>, i: &Ink) -> Div {
    div()
        .flex_1()
        .min_w_0()
        .flex()
        .flex_col()
        .gap(px(2.))
        .child(div().font_weight(FontWeight::SEMIBOLD).child(title.to_string()))
        .children(caption.map(|c| div().text_size(px(12.)).line_height(px(16.2)).text_color(i.text2).child(c)))
}

/// `.lfly-switch`: 38 x 22, the accent when on, an 18 px white knob.
fn switch(id: impl Into<ElementId>, on: bool, disabled: bool, i: &Ink, f: impl Fn(&mut App) + 'static) -> Stateful<Div> {
    div()
        .id(id)
        .relative()
        .w(px(38.))
        .h(px(22.))
        .flex_none()
        .rounded_full()
        .bg(if on { i.accent } else { i.fill2 })
        .border_1()
        .border_color(i.border.opacity(0.5))
        .when(disabled, |d| d.opacity(0.4))
        .when(!disabled, |d| d.cursor_pointer().on_click(move |_, _, cx| f(cx)))
        .child(
            div()
                .absolute()
                .top(px(1.))
                .left(px(if on { 17. } else { 1. }))
                .size(px(18.))
                .rounded_full()
                .bg(white())
                .shadow(vec![BoxShadow { color: black().opacity(0.25), offset: point(px(0.), px(2.)), blur_radius: px(5.), spread_radius: px(0.), inset: false }]),
        )
}

/// `.lfly-seg`: the chosen one raised on surface-3.
fn seg<T: Clone + PartialEq + 'static>(id: &str, value: &T, options: &[(T, &str)], i: &Ink, pick: impl Fn(T, &mut App) + 'static) -> Div {
    let pick = std::rc::Rc::new(pick);
    div()
        .flex()
        .flex_wrap()
        .flex_shrink(1.)
        .min_w_0()
        .max_w_full()
        .p(px(2.))
        .gap(px(2.))
        .rounded(px(9.))
        .bg(i.fill)
        .border_1()
        .border_color(i.border.opacity(0.5))
        .children(options.iter().enumerate().map(|(k, (v, text))| {
            let on = v == value;
            let (v, pick) = (v.clone(), pick.clone());
            div()
                .id(SharedString::from(format!("{id}-{k}")))
                .min_h(px(26.))
                .px(px(12.))
                .flex()
                .items_center()
                .rounded(px(7.))
                .font_weight(FontWeight::MEDIUM)
                .cursor_pointer()
                .text_color(if on { i.s_text } else { i.text2 })
                .when(on, |d| {
                    d.bg(i.surface3).shadow(vec![BoxShadow { color: black().opacity(0.18), offset: point(px(0.), px(1.)), blur_radius: px(3.), spread_radius: px(0.), inset: false }])
                })
                .when(!on, |d| d.hover(|d| d.text_color(i.s_text)))
                .on_click(move |_, _, cx| pick(v.clone(), cx))
                // Labels may come marked (t) from a const: shown in the window's language.
                .child(tr(text).to_string())
        }))
}

/// `.lfly-btn-secondary` (or primary).
fn button(id: impl Into<ElementId>, text: impl Into<SharedString>, primary: bool, disabled: bool, i: &Ink) -> Stateful<Div> {
    div()
        .id(id)
        .flex_none()
        .min_h(px(32.))
        .px(px(16.))
        .flex()
        .items_center()
        .justify_center()
        .gap(px(7.))
        .rounded_full()
        .font_weight(FontWeight::SEMIBOLD)
        .text_size(px(13.))
        .whitespace_nowrap()
        .when(primary, |d| d.bg(i.accent).text_color(i.on_accent))
        .when(!primary, |d| d.bg(i.fill).text_color(i.s_text).border_1().border_color(i.border.opacity(0.5)))
        .when(disabled, |d| d.opacity(0.45))
        .when(!disabled, |d| d.cursor_pointer().when(!primary, |d| d.hover(|d| d.bg(i.fill2))))
        .child(text.into())
}

/// `.tile`: a 28 px rounded square in a system colour, its icon in white.
fn tile(name: &'static str, from: u32, to: u32) -> Div {
    div()
        .size(px(28.))
        .flex_none()
        .rounded(px(7.))
        .flex()
        .items_center()
        .justify_center()
        .bg(linear_gradient(180., linear_color_stop(Rgb::hex(from).hsla(1.), 0.), linear_color_stop(Rgb::hex(to).hsla(1.), 1.)))
        .child(icon(name, 15., white()))
}

/// ThemeCard.svelte: a small picture of the window in a mode's colours.
fn mode_card(mode: Mode, title: &str, sub: &str, active: bool, w: f32, accent: Rgb, cover: Option<(Rgb, Rgb)>, cover_img: Option<Arc<Image>>, i: &Ink, pick: impl Fn(&mut App) + 'static) -> Stateful<Div> {
    let c = if mode.dynamic() { cover } else { None };
    let s = build_scheme(mode, Some(c.map_or(accent, |c| c.0)), Some(c.map_or(accent, |c| c.1)));
    let h = w * 10.5 / 16.;
    let pad = w * 0.06;
    let gap = w * 0.05;
    let (t, t2, a) = (s.text, s.text2, s.accent.hsla(1.));
    let card = if s.light { Rgb::hex(0x15151a).hsla(0.07) } else { white().opacity(0.09) };
    let glass = if s.light { white().opacity(0.62) } else { Rgb::hex(0x141418).hsla(0.42) };
    let border = if s.light { black().opacity(0.1) } else { white().opacity(0.16) };
    let inner_w = w - 2. * pad;
    let side_w = inner_w * 0.26;
    let top_h = (h - 2. * pad - gap) * (1. / 1.2);
    let line = |wf: f32, hp: f32, col: Hsla| div().w(relative(wf)).h(px(hp)).rounded(px(3.)).bg(col);
    let id: SharedString = format!("mode-{}", mode.id()).into();
    div()
        .id(id)
        .w(px(w))
        .flex()
        .flex_col()
        .gap(px(10.))
        .cursor_pointer()
        .group("mode-card")
        .on_click(move |_, _, cx| pick(cx))
        .child(
            div()
                .relative()
                .w(px(w))
                .h(px(h))
                .rounded(px(12.))
                .overflow_hidden()
                .bg(linear_gradient(180., linear_color_stop(s.gradient[0].hsla(1.), 0.), linear_color_stop(s.gradient[1].hsla(1.), 0.55)))
                .shadow(if active {
                    vec![
                        BoxShadow { color: i.bg, offset: point(px(0.), px(0.)), blur_radius: px(0.), spread_radius: px(2.), inset: false },
                        BoxShadow { color: i.accent, offset: point(px(0.), px(0.)), blur_radius: px(0.), spread_radius: px(4.), inset: false },
                    ]
                } else {
                    vec![BoxShadow { color: black().opacity(0.3), offset: point(px(0.), px(4.)), blur_radius: px(14.), spread_radius: px(0.), inset: false }]
                })
                // Copertina dinamica: the playing cover, blurred, under a veil (no blur here: dimmed).
                .when_some(cover_img.filter(|_| mode == Mode::DynamicCover), |d, im| {
                    d.child(div().absolute().inset_0().child(img(im).size_full().object_fit(ObjectFit::Cover).opacity(0.75)))
                        .child(div().absolute().inset_0().bg(linear_gradient(180., linear_color_stop(black().opacity(0.18), 0.), linear_color_stop(black().opacity(0.5), 1.))))
                })
                .child(
                    div()
                        .absolute()
                        .left(px(pad))
                        .top(px(pad))
                        .w(px(side_w))
                        .h(px(top_h))
                        .rounded(px(7.))
                        .bg(glass)
                        .border_1()
                        .border_color(border.opacity(0.5))
                        .px(px(side_w * 0.12))
                        .py(px(top_h * 0.14))
                        .flex()
                        .flex_col()
                        .gap(px(top_h * 0.09))
                        .child(div().size(px(side_w * 0.34)).rounded_full().bg(a))
                        .child(line(0.8, 3., t2.opacity(0.7)))
                        .child(line(0.8, 3., t2.opacity(0.7)))
                        .child(line(0.8, 3., t2.opacity(0.7))),
                )
                .child(
                    div()
                        .absolute()
                        .left(px(pad + side_w + gap))
                        .top(px(pad))
                        .right(px(pad))
                        .h(px(top_h))
                        .flex()
                        .flex_col()
                        .gap(px(top_h * 0.06))
                        .child(line(0.55, 7., t))
                        .child(line(0.32, 5., t2))
                        .child(div().flex_1().mt(px(top_h * 0.04)).flex().gap(px(inner_w * 0.03)).children((0..3).map(|_| div().flex_1().rounded(px(4.)).bg(card)))),
                )
                .child(
                    div()
                        .absolute()
                        .left(px(pad))
                        .right(px(pad))
                        .bottom(px(pad))
                        .h(px(h - 2. * pad - gap - top_h))
                        .rounded_full()
                        .bg(glass)
                        .border_1()
                        .border_color(border.opacity(0.5))
                        .px(px(inner_w * 0.05))
                        .flex()
                        .items_center()
                        .gap(px(inner_w * 0.06))
                        .child(div().size(px(9.)).flex_none().rounded_full().bg(t))
                        .child(div().flex_1().h(px(3.)).rounded(px(2.)).bg(t2.opacity(0.8)).child(div().w(relative(0.4)).h_full().bg(a))),
                )
                .when(active, |d| {
                    d.child(
                        div()
                            .absolute()
                            .top(px(7.))
                            .right(px(7.))
                            .size(px(20.))
                            .rounded_full()
                            .flex()
                            .items_center()
                            .justify_center()
                            .bg(i.accent)
                            .child(icon("check", 11., i.on_accent)),
                    )
                }),
        )
        .child(
            div()
                .flex()
                .flex_col()
                .gap(px(2.))
                .px(px(2.))
                .child(div().text_size(px(13.)).font_weight(FontWeight::SEMIBOLD).truncate().child(title.to_string()))
                .child(div().text_size(px(12.)).line_height(px(16.2)).text_color(i.text2).child(sub.to_string())),
        )
}

impl SettingsPage {
    fn appearance(&mut self, i: &Ink, cx: &mut Context<Self>) -> Vec<AnyElement> {
        let l = self.look.read(cx);
        // On macOS the renderer has no glass yet: the switch shows it off and cannot turn it on.
        let (mode, accent_id, glass_on, lens, strength) = (l.mode, l.accent.clone(), l.glass_enabled && crate::look::GLASS_RENDERER, l.lens, l.strength);
        let cover = l.cover_colors();
        let glass_look = l.glass();
        let accent = accent_color(&accent_id);
        let track = self.model.read(cx).current().cloned();
        let cover_img = track.as_ref().and_then(|t| t.cover_url.clone()).and_then(|u| self.covers.update(cx, |c, cx| c.get(&u, cx)));
        let look = self.look.clone();

        // `.modes`: repeat(auto-fill, minmax(176px, 1fr)), gaps 20 and 16.
        let avail = (self.page_width - 56.).min(1040. - 56.);
        let cols = ((avail + 16.) / 192.).floor().max(1.);
        let card_w = ((avail - 16. * (cols - 1.)) / cols).floor();
        let modes = div().flex().flex_wrap().gap_x(px(16.)).gap_y(px(20.)).children(MODES.iter().map(|(m, _, title, sub)| {
            let look = look.clone();
            let m = *m;
            mode_card(m, tr(title), tr(sub), mode == m, card_w, accent, cover, cover_img.clone(), i, move |cx| look.update(cx, |l, cx| l.set_mode(m, cx)))
        }));

        let followed = mode.dynamic() && cover.is_some();
        let (name, premium) = ACCENTS.iter().find(|a| a.0 == accent_id).map_or(("", false), |a| (a.1, a.3));
        let swatches = div().flex().flex_wrap().gap(px(10.)).p(px(16.)).children(ACCENTS.iter().map(|(id, name, color, premium)| {
            let sw = Rgb::hex(*color).hsla(1.);
            let on = *id == accent_id;
            let look = look.clone();
            let id_s = id.to_string();
            let _ = name;
            div()
                .id(SharedString::from(format!("acc-{id}")))
                .relative()
                .size(px(30.))
                .rounded_full()
                .flex()
                .items_center()
                .justify_center()
                .cursor_pointer()
                .bg(sw)
                .when(*premium, |d| {
                    d.child(div().absolute().size(px(30.)).rounded_full().bg(linear_gradient(145., linear_color_stop(white().opacity(0.35), 0.), linear_color_stop(black().opacity(0.18), 1.))))
                })
                .shadow(if on {
                    vec![
                        BoxShadow { color: i.bg, offset: point(px(0.), px(0.)), blur_radius: px(0.), spread_radius: px(2.), inset: false },
                        BoxShadow { color: sw, offset: point(px(0.), px(0.)), blur_radius: px(0.), spread_radius: px(4.), inset: false },
                    ]
                } else {
                    vec![BoxShadow { color: black().opacity(0.25), offset: point(px(0.), px(1.)), blur_radius: px(3.), spread_radius: px(0.), inset: false }]
                })
                .on_click(move |_, _, cx| look.update(cx, |l, cx| l.set_accent(&id_s, cx)))
                .child(div().relative().when(on, |d| d.child(icon("check", 14., white()))))
                .into_any_element()
        }));
        let accents = list(
            vec![
                swatches.into_any_element(),
                row()
                    .child(div().size(px(12.)).rounded_full().bg(accent.hsla(1.)))
                    .child(div().flex_1().font_weight(FontWeight::SEMIBOLD).child(tr(name).to_string()))
                    .child(div().text_size(px(12.)).text_color(i.text2).child(if premium { "Premium" } else { tr("Classico") }))
                    .into_any_element(),
            ],
            i,
        );

        // The glass stage: something colourful behind real glass.
        let k = self.strength_draft.unwrap_or(strength);
        let mut look_preview = glass_look;
        look_preview.strength = k;
        let stage = div()
            .relative()
            .h(px(168.))
            .mb(px(14.))
            .rounded(px(18.))
            .overflow_hidden()
            .border_1()
            .border_color(i.border.opacity(0.5))
            .children((0..14).map(|n| div().absolute().top(px(-20.)).left(px(n as f32 * 56. - 40.)).w(px(14.)).h(px(220.)).bg(i.s_text.opacity(0.1))))
            .child(div().absolute().right(relative(0.1)).top(px(18.)).size(px(90.)).rounded_full().bg(i.accent.opacity(0.85)))
            .children(cover_img.clone().map(|im| div().absolute().left(relative(0.06)).top(px(-40.)).size(px(170.)).rounded(px(24.)).overflow_hidden().child(img(im).size_full().object_fit(ObjectFit::Cover).rounded(px(24.)))))
            .child(
                div().absolute().left_0().right_0().bottom(px(20.)).flex().justify_center().child(
                    div()
                        .relative()
                        .w(px(440f32.min(avail - 40.)))
                        .h(px(60.))
                        .rounded_full()
                        .child(glass::glass_layer(look_preview, px(30.), glass::Pointer::default()))
                        .child(
                            div()
                                .relative()
                                .size_full()
                                .pl(px(22.))
                                .pr(px(8.))
                                .flex()
                                .items_center()
                                .gap(px(8.))
                                .child(
                                    div()
                                        .flex_1()
                                        .min_w_0()
                                        .flex()
                                        .flex_col()
                                        .child(div().font_weight(FontWeight::BOLD).truncate().child(track.as_ref().map_or("Get Lucky".to_string(), |t| t.title.clone())))
                                        .child(div().text_size(px(12.)).text_color(i.text2).truncate().child(track.as_ref().map_or("Daft Punk".to_string(), |t| t.artist.clone()))),
                                )
                                .child(icon("previous", 16., i.s_text))
                                .child(div().size(px(46.)).rounded_full().bg(i.accent.opacity(0.5)).flex().items_center().justify_center().child(icon("play", 18., i.on_accent)))
                                .child(icon("next", 16., i.s_text)),
                        ),
                ),
            )
            .child(
                div()
                    .absolute()
                    .right(px(22.))
                    .top(px(18.))
                    .min_w(px(150.))
                    .rounded(px(22.))
                    .child(glass::glass_layer(look_preview, px(22.), glass::Pointer::default()))
                    .child(
                        div()
                            .relative()
                            .py(px(12.))
                            .px(px(16.))
                            .flex()
                            .flex_col()
                            .gap(px(2.))
                            .child(div().text_size(px(11.)).font_weight(FontWeight::BOLD).text_color(i.text2).child("VETRO"))
                            .child(div().font_weight(FontWeight::BOLD).child(if glass_on && lens { tr("Lente attiva") } else if glass_on { tr("Solo sfocatura") } else { tr("Superficie piena") })),
                    ),
            );
        let (l1, l2, l3, l4) = (look.clone(), look.clone(), look.clone(), look.clone());
        let me = cx.entity().downgrade();
        let me2 = me.clone();
        let glass_list = list(
            vec![
                row()
                    .child(tile("expand", 0x4d8dff, 0x2f6bf0))
                    .child(label(
                        "Liquid Glass",
                        Some(if crate::look::GLASS_RENDERER { tr("Vetro traslucido sulle superfici che galleggiano sopra i contenuti.") } else { tr("Su Mac arriva più avanti: per ora le superfici sono piene.") }.into()),
                        i,
                    ))
                    .child(switch("sw-glass", glass_on, !crate::look::GLASS_RENDERER, i, move |cx| l1.update(cx, |l, cx| l.set_glass(Some(!glass_on), None, None, cx))))
                    .into_any_element(),
                row()
                    .child(tile("lyrics", 0xa173ff, 0x7c4dff))
                    .child(label(tr("Effetto lente"), Some(tr("Rifrazione ai bordi, dispersione cromatica e riflessi che seguono il puntatore. Usa più GPU.").into()), i))
                    .child(switch("sw-lens", lens, !glass_on, i, move |cx| l2.update(cx, |l, cx| l.set_glass(None, Some(!lens), None, cx))))
                    .into_any_element(),
                row()
                    .flex_wrap()
                    .child(tile("volume", 0x8e8e93, 0x636366))
                    .child(label(tr("Intensità"), Some(tr("Da più solido a più trasparente, con una lente più marcata.").into()), i))
                    .child(div().ml_auto().text_size(px(12.)).text_color(i.text2).child(tr("Solido")))
                    .child(div().w(px((avail * 0.4).min(220.))).flex().child(slider(
                        "glass-strength",
                        k,
                        !glass_on,
                        SliderStyle { text: i.s_text, accent: i.accent, mix: None },
                        self.glass_bounds.clone(),
                        move |f, _, cx| {
                            let f = (f * 20.).round() / 20.;
                            let _ = me.update(cx, |this, cx| {
                                this.strength_draft = Some(f);
                                cx.notify();
                            });
                        },
                        move |f, _, cx| {
                            let f = (f * 20.).round() / 20.;
                            l3.update(cx, |l, cx| l.set_glass(None, None, Some(f), cx));
                            let _ = me2.update(cx, |this, cx| {
                                this.strength_draft = None;
                                cx.notify();
                            });
                        },
                    )))
                    .child(div().text_size(px(12.)).text_color(i.text2).child(tr("Chiaro")))
                    .into_any_element(),
            ],
            i,
        );
        let _ = l4;
        vec![
            group(tr("Aspetto"), Some(tr("Canvas Liquid Glass e animazioni, come sul telefono.")), i).child(modes).into_any_element(),
            group(
                tr("Colore accento"),
                Some(if followed {
                    tr("Si applica a tutti i temi; nei temi dinamici segue la copertina quando disponibile. Ora segue la copertina.")
                } else {
                    tr("Si applica a tutti i temi; nei temi dinamici segue la copertina quando disponibile.")
                }),
                i,
            )
            .child(accents)
            .into_any_element(),
            group("Liquid Glass", Some(tr("Vetro con rifrazione reale su barre, pannelli e pulsanti.")), i).child(stage).child(glass_list).into_any_element(),
            group(
                tr("Temi personalizzati"),
                Some(tr("I temi tuoi, quelli di Spicetify e il Marketplace non sono ancora nella versione nativa: per ora si creano e si usano nell'app LFly classica.")),
                i,
            )
            .into_any_element(),
        ]
    }

    fn general(&mut self, i: &Ink, p: &PrefsData, cx: &mut Context<Self>) -> Vec<AnyElement> {
        let autostart = self.autostart;
        let me_auto = cx.entity().downgrade();
        let prefs = Prefs::entity(cx);
        let (p1, p2) = (prefs.clone(), prefs);
        const MAC_KEYS: bool = cfg!(target_os = "macos");
        const KEYS: [(&str, &str); 13] = [
            (t("Spazio#tasto"), t("Riproduci o metti in pausa")),
            (if MAC_KEYS { "⌘ →  /  ⌘ ←" } else { "Ctrl + →  /  Ctrl + ←" }, t("Brano successivo / precedente")),
            (t("Maiusc + →  /  Maiusc + ←"), t("Avanti / indietro di 10 secondi")),
            (if MAC_KEYS { "⌘ ↑  /  ⌘ ↓" } else { "Ctrl + ↑  /  Ctrl + ↓" }, t("Alza / abbassa il volume")),
            (if MAC_KEYS { "⌘ S" } else { "Ctrl + S" }, t("Riproduzione casuale")),
            (if MAC_KEYS { "⌘ R" } else { "Ctrl + R" }, t("Ripeti: tutto, un brano, niente")),
            (if MAC_KEYS { "⌘ K" } else { "Ctrl + K" }, t("Cerca")),
            (if MAC_KEYS { "⌥ ←  /  ⌥ →" } else { "Alt + ←  /  Alt + →" }, t("Pagina precedente / successiva")),
            ("Esc", t("Chiudi il player a schermo intero")),
            (if MAC_KEYS { "⌘ 1 … ⌘ 4" } else { "Ctrl + 1 … Ctrl + 4" }, t("Home, Cerca, Libreria, Console")),
            (if MAC_KEYS { "⌃ ⌘ S" } else { "Ctrl + Alt + S" }, t("Comprimi o espandi la barra laterale")),
            (if MAC_KEYS { "⌃ ⌘ F" } else { "F11" }, t("Schermo intero")),
            (if MAC_KEYS { "⌘ ,  /  ⌘ Q" } else { "Ctrl + ,  /  Ctrl + Q" }, t("Impostazioni / esci da LFly")),
        ];
        let lang = crate::i18n::lang();
        let options: Vec<(crate::i18n::Lang, &str)> = crate::i18n::Lang::ALL.iter().map(|l| (*l, l.name())).collect();
        vec![
            group(tr("Lingua"), None, i)
                .child(list(
                    vec![row()
                        .child(label(tr("Lingua dell'app"), Some(tr("Testi, messaggi e le frasi del DJ.").into()), i))
                        .child(seg("lang", &lang, &options, i, |l, cx| {
                            crate::i18n::set_lang(l);
                            crate::i18n::save(l);
                            crate::app_menu::set_menus(cx);
                            // Every view draws again, cached or not, in the new language.
                            cx.refresh_windows();
                        }))
                        .into_any_element()],
                    i,
                ))
                .into_any_element(),
            group(tr("Avvio"), None, i)
                .child(list(
                    vec![
                        row()
                            .child(label(tr("Apri LFly all'accesso"), Some(tr("Si avvia quando entri nel computer, pronto con la tua musica.").into()), i))
                            .child(switch("sw-autostart", autostart, false, i, move |cx| {
                                match lfly_core::settings::set_autostart(!autostart) {
                                    Ok(on) => {
                                        let _ = me_auto.update(cx, |this, cx| {
                                            this.autostart = on;
                                            cx.notify();
                                        });
                                    }
                                    Err(e) => Toasts::say(trf("Avvio automatico non cambiato: {0}", &[&e]), cx),
                                }
                            }))
                            .into_any_element(),
                        row()
                            .child(label(tr("Pagina iniziale"), Some(tr("Dove si apre LFly.").into()), i))
                            .child(seg(
                                "start",
                                &p.start_page,
                                &[("home".to_string(), tr("Home")), ("library".to_string(), tr("Libreria")), ("liked".to_string(), tr("Preferiti")), ("search".to_string(), tr("Cerca"))],
                                i,
                                move |v, cx| p1.update(cx, |p, cx| p.update(|d| d.start_page = v, cx)),
                            ))
                            .into_any_element(),
                    ],
                    i,
                ))
                .into_any_element(),
            group(tr("Movimento"), None, i)
                .child(list(
                    vec![row()
                        .child(label(tr("Animazioni"), Some(tr("Ridotte: niente scorrimenti, copertina dinamica ferma, cambi istantanei.").into()), i))
                        .child(seg(
                            "motion",
                            &p.motion,
                            &[("system".to_string(), tr("Come il sistema")), ("full".to_string(), tr("Complete")), ("reduce".to_string(), tr("Ridotte"))],
                            i,
                            move |v, cx| p2.update(cx, |p, cx| p.update(|d| d.motion = v, cx)),
                        ))
                        .into_any_element()],
                    i,
                ))
                .into_any_element(),
            group(tr("Scorciatoie da tastiera"), None, i)
                .child(list(
                    KEYS.iter()
                        .map(|(k, what)| {
                            row()
                                .child(div().flex_1().child(tr(what).to_string()))
                                .child(div().text_size(px(12.)).font_weight(FontWeight::SEMIBOLD).px(px(9.)).py(px(6.)).rounded(px(8.)).text_color(i.text2).bg(i.s_text.opacity(0.08)).child(tr(k).to_string()))
                                .into_any_element()
                        })
                        .collect(),
                    i,
                ))
                .into_any_element(),
        ]
    }

    fn sound(&mut self, i: &Ink, cx: &mut Context<Self>) -> Vec<AnyElement> {
        let pb = self.playback.clone();
        let (me1, me2, me3) = (cx.entity().downgrade(), cx.entity().downgrade(), cx.entity().downgrade());
        vec![
            group(tr("Riproduzione"), None, i)
                .child(list(
                    vec![
                        row()
                            .child(label(tr("Continua con brani simili"), Some(tr("Finita la coda, la musica va avanti con brani come l'ultimo, come la radio di YouTube Music.").into()), i))
                            .child(switch("sw-autoplay", pb.autoplay, false, i, move |cx| {
                                let _ = me1.update(cx, |this, cx| this.set_playback(|p| p.autoplay = !p.autoplay, cx));
                            }))
                            .into_any_element(),
                        row()
                            .child(label(
                                tr("Formato audio"),
                                Some(tr("AAC (fino a 256 kbps) suona ovunque; Opus (fino a 160 kbps) occupa meno a parità di qualità. Vale dal prossimo brano.").into()),
                                i,
                            ))
                            .child(seg("format", &pb.format, &[("aac".to_string(), "AAC"), ("opus".to_string(), "Opus")], i, move |v, cx| {
                                let _ = me2.update(cx, |this, cx| this.set_playback(|p| p.format = v, cx));
                            }))
                            .into_any_element(),
                        row()
                            .child(label(
                                tr("Da dove suona"),
                                Some(
                                    tr("Automatico, come sul telefono: YouTube Music e Monochrome cercano insieme e vince il primo; Monochrome (qualità più alta) vince anche se arriva entro un secondo e mezzo da YouTube. Le altre scelte provano prima quella e poi l'altra. Vale per i brani non ancora caricati; i download usano sempre YouTube Music.")
                                        .into(),
                                ),
                                i,
                            ))
                            .child(seg(
                                "source",
                                &pb.source,
                                &[("AUTO".to_string(), tr("Automatico")), ("YOUTUBE_MUSIC".to_string(), "YouTube Music"), ("MONOCHROME".to_string(), "Monochrome")],
                                i,
                                move |v, cx| {
                                    let _ = me3.update(cx, |this, cx| this.set_playback(|p| p.source = v, cx));
                                },
                            ))
                            .into_any_element(),
                    ],
                    i,
                ))
                .into_any_element(),
            div().mt(px(28.)).max_w(px(720.)).flex().flex_col().gap(px(14.)).children(self.equalizer(i, cx)).into_any_element(),
        ]
    }

    /// EqualizerPanel.svelte: the equalizer, the crossfade (Automix), the output.
    fn equalizer(&mut self, i: &Ink, cx: &mut Context<Self>) -> Vec<AnyElement> {
        use crate::audio::{AudioModel, BANDS, MAX_DB, PRESETS};
        let audio = AudioModel::entity(cx);
        let a = audio.read(cx);
        let (eq, crossfade, device, devices, preset) = (a.v.eq, a.v.crossfade, a.v.device.clone(), a.devices.clone(), a.preset());
        let (automix_on, automix_style) = (a.v.automix, a.v.automix_style.clone());
        let text3 = self.look.read(cx).scheme.text3;
        let card = || {
            div().pt(px(18.)).px(px(20.)).pb(px(20.)).rounded(px(24.)).bg(self.look.read(cx).scheme.glass_tint).border_1().border_color(i.border)
        };
        let h2 = |t: &str| div().text_size(px(17.)).line_height(px(21.)).font_weight(FontWeight::SEMIBOLD).child(t.to_string());
        let help = |t: &str| div().mt(px(8.)).line_height(px(19.5)).text_color(i.text2).child(t.to_string());

        let au = audio.clone();
        let enabled = eq.enabled;
        let head = div()
            .flex()
            .items_center()
            .justify_between()
            .child(h2(tr("Equalizzatore")))
            .child(
                div()
                    .flex()
                    .items_center()
                    .gap(px(10.))
                    .font_weight(FontWeight::SEMIBOLD)
                    .text_color(i.text2)
                    .child(if enabled { tr("Attivo") } else { tr("Spento") })
                    .child(switch("eq-on", enabled, false, i, move |cx| au.update(cx, |a, cx| a.set_enabled(!enabled, cx)))),
            );
        let presets = div().mt(px(16.)).flex().flex_wrap().gap(px(6.)).children(PRESETS.iter().map(|(name, _, _)| {
            let on = preset == *name;
            let au = audio.clone();
            let name = *name;
            div()
                .id(SharedString::from(format!("preset-{name}")))
                .h(px(30.))
                .px(px(14.))
                .flex()
                .items_center()
                .rounded_full()
                .text_size(px(12.5))
                .font_weight(FontWeight::SEMIBOLD)
                .cursor_pointer()
                .when(on, |d| d.bg(i.accent).text_color(i.on_accent))
                .when(!on, |d| d.bg(i.s_text.opacity(0.08)).hover(|d| d.bg(i.s_text.opacity(0.14))).active(|d| d.bg(i.s_text.opacity(0.25))))
                .on_click(move |_, _, cx| au.update(cx, |a, cx| a.use_preset(name, cx)))
                .child(tr(name))
        }))
        .when(preset == "Personalizzato", |d| {
            d.child(div().h(px(30.)).px(px(14.)).flex().items_center().rounded_full().text_size(px(12.5)).font_weight(FontWeight::SEMIBOLD).bg(i.accent).text_color(i.on_accent).child(tr("Personalizzato")))
        });
        let bands = div()
            .relative()
            .mt(px(18.))
            .pt(px(20.))
            .px(px(12.))
            .pb(px(16.))
            .rounded(px(20.))
            .bg(i.bg.opacity(0.4))
            .border_1()
            .border_color(i.sep)
            .when(!enabled, |d| d.opacity(0.5))
            .flex()
            .gap(px(12.))
            // The 0 dB line across the bands.
            .child(div().absolute().left(px(16.)).right(px(16.)).top(px(20. + 26. + 80.)).h(px(1.)).bg(i.sep))
            .children(BANDS.iter().enumerate().map(|(k, label)| {
                let db = eq.bands_db[k];
                let (au1, au2) = (audio.clone(), audio.clone());
                div()
                    .flex_1()
                    .flex()
                    .flex_col()
                    .items_center()
                    .gap(px(10.))
                    .child(
                        div()
                            .h(px(16.))
                            .text_size(px(11.))
                            .text_color(if db != 0. { i.accent } else { text3 })
                            .when(db != 0., |d| d.font_weight(FontWeight::SEMIBOLD))
                            .child(format!("{}{} dB", if db > 0. { "+" } else { "" }, crate::i18n::decimal(db as f64, 1))),
                    )
                    .child(crate::slider::vslider(
                        ("band", k),
                        (db + MAX_DB) / (2. * MAX_DB),
                        i.accent,
                        i.s_text.opacity(0.16),
                        160.,
                        self.band_bounds[k].clone(),
                        move |f, _, cx| au1.update(cx, |a, cx| a.set_band(k, f * 2. * MAX_DB - MAX_DB, cx)),
                        move |_, cx| au2.update(cx, |a, cx| a.set_band(k, 0., cx)),
                    ))
                    .child(div().text_size(px(12.)).font_weight(FontWeight::SEMIBOLD).text_color(i.text2).child(label.to_string()))
            }));
        let au3 = audio.clone();
        let bass = div()
            .mt(px(16.))
            .flex()
            .flex_col()
            .gap(px(8.))
            .text_color(i.text2)
            .child(div().flex().gap(px(4.)).child(tr("Potenziamento bassi:")).child(div().font_weight(FontWeight::BOLD).text_color(i.s_text).child(format!("{}%", eq.bass_boost / 10))))
            .child(crate::slider::hslider(
                "bass",
                eq.bass_boost as f32 / 1000.,
                i.accent,
                i.s_text.opacity(0.16),
                self.bass_bounds.clone(),
                move |f, _, cx| au3.update(cx, |a, cx| a.set_bass(f * 1000., cx)),
            ));
        let eq_card = card()
            .child(head)
            .child(help(tr("Lo stesso equalizzatore del telefono, dentro il player: suona uguale su casse, cuffie e DAC. Quando è piatto l'audio passa intatto, bit per bit.")))
            .child(presets)
            .child(bands)
            .child(bass);

        let au5 = audio.clone();
        let automix = card()
            .child(h2("Automix"))
            .child(
                div()
                    .mt(px(12.))
                    .flex()
                    .flex_col()
                    .gap(px(8.))
                    .text_color(i.text2)
                    .child(
                        div()
                            .flex()
                            .gap(px(4.))
                            .child(tr("Dissolvenza tra i brani:"))
                            .child(div().font_weight(FontWeight::BOLD).text_color(i.s_text).child(if crossfade > 0 { format!("{crossfade} s") } else { tr("spenta").into() })),
                    )
                    .child(crate::slider::hslider(
                        "crossfade",
                        crossfade as f32 / 12.,
                        i.accent,
                        i.s_text.opacity(0.16),
                        self.cross_bounds.clone(),
                        move |f, _, cx| au5.update(cx, |a, cx| a.set_crossfade(f * 12., cx)),
                    )),
            )
            .child(help(tr("Ogni brano sfuma nel successivo. Con la dissolvenza spenta i brani si susseguono senza pause, come negli album dal vivo.")))
            .child({
                let (au6, au7) = (audio.clone(), audio.clone());
                let usable = lfly_core::automix::available();
                div()
                    .mt(px(14.))
                    .flex()
                    .flex_col()
                    .gap(px(10.))
                    .child(
                        div()
                            .flex()
                            .items_center()
                            .gap(px(12.))
                            .child(label(
                                tr("Mixa a tempo"),
                                Some(if usable {
                                    tr("Come sul telefono: trova le battute alla fine di un brano e all'inizio del successivo, li porta allo stesso tempo e li mixa su una battuta, con EQ e filtri. Se due brani non si possono mixare a tempo, sfumano (5 s se la dissolvenza è spenta).")
                                        .into()
                                } else {
                                    tr("Non disponibile in questa versione: serve il modello delle battute.").into()
                                }),
                                i,
                            ))
                            .child(switch("sw-automix", automix_on, !usable, i, move |cx| au6.update(cx, |a, cx| a.set_automix(!automix_on, None, cx)))),
                    )
                    .when(automix_on && usable, |d| {
                        d.child(seg(
                            "automix-style",
                            &automix_style,
                            &[
                                ("AUTO".to_string(), tr("Automatico")),
                                ("BASS_SWAP".to_string(), "Bass swap"),
                                ("EQ_SWAP".to_string(), "EQ"),
                                ("FILTER".to_string(), tr("Filtro")),
                                ("RISE".to_string(), "Rise"),
                                ("FADE".to_string(), tr("Dissolvenza")),
                            ],
                            i,
                            move |v, cx| au7.update(cx, |a, cx| a.set_automix(true, Some(&v), cx)),
                        ))
                    })
            });

        // `<select>`: the system's default, then each device; the chosen one ticked.
        let mut options: Vec<(Option<String>, String)> = vec![(None, tr("Predefinita del sistema").into())];
        options.extend(devices.iter().filter(|d| d.id != crate::audio::SYSTEM_DEFAULT).map(|d| (Some(d.id.clone()), format!("{}{}", d.name, if d.is_default { format!(" ({})", tr("predefinita")) } else { String::new() }))));
        let output = card().child(h2(tr("Uscita audio"))).child(
            div().mt(px(8.)).flex().flex_col().gap(px(2.)).children(options.into_iter().enumerate().map(|(k, (id, name))| {
                let on = id == device;
                let au = audio.clone();
                div()
                    .id(("out", k))
                    .h(px(38.))
                    .px(px(12.))
                    .flex()
                    .items_center()
                    .gap(px(10.))
                    .rounded(px(12.))
                    .cursor_pointer()
                    .when(on, |d| d.bg(i.s_text.opacity(0.08)))
                    .hover(|d| d.bg(i.s_text.opacity(0.06))).active(|d| d.bg(i.s_text.opacity(0.11)))
                    .on_click(move |_, _, cx| au.update(cx, |a, cx| a.set_device(id.clone(), cx)))
                    .child(div().flex_1().truncate().child(name))
                    .when(on, |d| d.child(icon("check", 16., i.accent)))
            })),
        );
        vec![eq_card.into_any_element(), automix.into_any_element(), output.into_any_element()]
    }

    fn lyrics(&mut self, i: &Ink, p: &PrefsData, window: &mut Window, cx: &mut Context<Self>) -> Vec<AnyElement> {
        let sources = self.lyrics_sources(i, p, window, cx);
        let prefs = Prefs::entity(cx);
        let (p1, p2, p3, p4) = (prefs.clone(), prefs.clone(), prefs.clone(), prefs);
        let (words, source) = (p.lyrics_words, p.lyrics_source);
        let data = self.lyrics.clone();
        vec![group(tr("Testi"), None, i)
            .child(list(
                vec![
                    row()
                        .child(label(tr("Parola per parola"), Some(tr("Ogni sillaba si accende mentre la canti, quando la fonte ha i tempi delle parole. Spento: righe intere.").into()), i))
                        .child(switch("sw-words", words, false, i, move |cx| p1.update(cx, |p, cx| p.update(|d| d.lyrics_words = !words, cx))))
                        .into_any_element(),
                    row()
                        .child(label(tr("Dimensione"), None, i))
                        .child(seg("lsize", &p.lyrics_size, &[("s".to_string(), tr("Piccola")), ("m".to_string(), tr("Media")), ("l".to_string(), tr("Grande"))], i, move |v, cx| {
                            p2.update(cx, |p, cx| p.update(|d| d.lyrics_size = v, cx))
                        }))
                        .into_any_element(),
                    row()
                        .child(label(tr("Allineamento"), None, i))
                        .child(seg("lalign", &p.lyrics_center, &[(false, tr("A sinistra")), (true, tr("Al centro"))], i, move |v, cx| {
                            p3.update(cx, |p, cx| p.update(|d| d.lyrics_center = v, cx))
                        }))
                        .into_any_element(),
                    row()
                        .child(label(tr("Mostra la fonte"), Some(tr("\"Testo da …\" in fondo al testo.").into()), i))
                        .child(switch("sw-source", source, false, i, move |cx| p4.update(cx, |p, cx| p.update(|d| d.lyrics_source = !source, cx))))
                        .into_any_element(),
                    row()
                        .child(label(tr("Sincronie salvate"), Some(tr("Le correzioni di tempo (− e +) che hai fatto canzone per canzone.").into()), i))
                        .child(button("reset-offsets", tr("Azzera"), false, false, i).on_click(move |_, _, cx| {
                            data.update(cx, |d, cx| d.reset_offsets(cx));
                            Toasts::say(tr("Sincronie dei testi azzerate (vale dal prossimo brano)"), cx);
                        }))
                        .into_any_element(),
                ],
                i,
            ))
            .into_any_element()]
        .into_iter()
        .chain(sources)
        .collect()
    }

    /// Testi > Fonti (the phone's LyricsSourcesDialog): the sources in the order they are read,
    /// each one on or off, word by word first, the PaxSenix key.
    fn lyrics_sources(&mut self, i: &Ink, p: &PrefsData, window: &mut Window, cx: &mut Context<Self>) -> Vec<AnyElement> {
        use gpui_kit::component::input::{Input, InputState};
        let prefs = Prefs::entity(cx);
        let wanted = p.lyrics_sources();
        let ordered: Vec<&'static lfly_yt::lyrics::SourceInfo> = wanted.ordered();
        let ids: Vec<String> = ordered.iter().map(|s| s.id.to_string()).collect();
        let n = ordered.len();
        let has_key = wanted.key().is_some();
        let small = |id: SharedString, name: &'static str, enabled: bool, i: &Ink| {
            div()
                .id(id)
                .size(px(28.))
                .flex_none()
                .rounded_full()
                .flex()
                .items_center()
                .justify_center()
                .when(!enabled, |d| d.opacity(0.3))
                .when(enabled, |d| d.cursor_pointer().hover(|d| d.bg(i.s_text.opacity(0.08))).active(|d| d.bg(i.s_text.opacity(0.14))))
                .child(icon(name, 14., i.text2))
        };
        let rows: Vec<AnyElement> = ordered
            .iter()
            .enumerate()
            .map(|(k, s)| {
                let on = !p.lyrics_off.iter().any(|o| o == s.id);
                let mut caption = tr(s.detail).to_string();
                if s.needs_key && !has_key {
                    caption.push_str(&format!(" · {}", tr("manca la chiave")));
                }
                let mv = |to: usize| {
                    let (prefs, ids) = (prefs.clone(), ids.clone());
                    move |_: &ClickEvent, _: &mut Window, cx: &mut App| {
                        let mut order = ids.clone();
                        let id = order.remove(k);
                        order.insert(to, id);
                        prefs.update(cx, |p, cx| p.update(|d| d.lyrics_order = order, cx));
                    }
                };
                let (pt, id) = (prefs.clone(), s.id);
                row()
                    .child(div().w(px(18.)).flex_none().text_color(i.text2).text_size(px(12.)).child(format!("{}", k + 1)))
                    .child(label(tr(s.label), Some(caption), i))
                    .child(small(format!("lsrc-up-{id}").into(), "chevron-up", k > 0, i).when(k > 0, |d| d.on_click(mv(k - 1))))
                    .child(small(format!("lsrc-down-{id}").into(), "chevron-down", k + 1 < n, i).when(k + 1 < n, |d| d.on_click(mv(k + 1))))
                    .child(switch(SharedString::from(format!("lsrc-{id}")), on, false, i, move |cx| {
                        pt.update(cx, |p, cx| {
                            p.update(
                                |d| {
                                    if on {
                                        d.lyrics_off.push(id.to_string());
                                    } else {
                                        d.lyrics_off.retain(|o| o != id);
                                    }
                                },
                                cx,
                            )
                        })
                    }))
                    .into_any_element()
            })
            .collect();

        let input = match self.pax_input.clone() {
            Some(input) => input,
            None => {
                let input = cx.new(|cx| InputState::new(window, cx).masked(true).placeholder(tr("Incolla la chiave")).default_value(p.paxsenix_key.clone()));
                crate::ui::register_field(&input.read(cx).focus_handle(cx), cx);
                let pr = prefs.clone();
                self._subs.push(cx.subscribe(&input, move |_, state, ev: &gpui_kit::component::input::InputEvent, cx| {
                    if matches!(ev, gpui_kit::component::input::InputEvent::PressEnter { .. }) {
                        let key = state.read(cx).value().trim().trim_start_matches("Bearer ").trim().to_string();
                        pr.update(cx, |p, cx| p.update(|d| d.paxsenix_key = key, cx));
                        Toasts::say(tr("Chiave PaxSenix salvata"), cx);
                    }
                    cx.notify();
                }));
                self.pax_input = Some(input.clone());
                input
            }
        };
        let typed = input.read(cx).value().trim().trim_start_matches("Bearer ").trim().to_string();
        let can_save = typed != p.paxsenix_key;
        let (pw, pk, input2) = (prefs.clone(), prefs.clone(), input.clone());
        let words_first = p.lyrics_words_first;
        let reset = prefs.clone();
        vec![
            group(
                tr("Fonti dei testi"),
                Some(tr("Come sul telefono: le fonti accese partono tutte insieme e le risposte si leggono in quest'ordine. Vince la prima con i tempi parola per parola, oppure la prima con le righe a tempo; Genius solo se nessuna ha i tempi.")),
                i,
            )
            .child(list(rows, i))
            .child(div().mt(px(10.)).flex().justify_end().child(button("lsrc-reset", tr("Ordine predefinito"), false, p.lyrics_order.is_empty() && p.lyrics_off.is_empty(), i).on_click(move |_, _, cx| {
                reset.update(cx, |p, cx| {
                    p.update(
                        |d| {
                            d.lyrics_order.clear();
                            d.lyrics_off.clear();
                        },
                        cx,
                    )
                })
            })))
            .into_any_element(),
            group(tr("Priorità"), None, i)
                .child(list(
                    vec![
                        row()
                            .child(label(tr("Prima il parola per parola"), Some(tr("Aspetta una fonte con i tempi delle parole anche se un'altra, prima nell'ordine, ha solo le righe.").into()), i))
                            .child(switch("sw-words-first", words_first, false, i, move |cx| pw.update(cx, |p, cx| p.update(|d| d.lyrics_words_first = !words_first, cx))))
                            .into_any_element(),
                        row()
                            .child(label(tr("Chiave PaxSenix"), Some(tr("Per le fonti PaxSenix: Spotify e Musixmatch. Resta su questo computer.").into()), i))
                            .child(div().w(px(220.)).h(px(34.)).px(px(12.)).flex().items_center().rounded_full().bg(i.fill).border_1().border_color(i.border.opacity(0.5)).child(Input::new(&input).appearance(false)))
                            .child(button("pax-save", if typed.is_empty() && !p.paxsenix_key.is_empty() { tr("Togli") } else { tr("Salva") }, false, !can_save, i).when(can_save, |b| {
                                b.on_click(move |_, _, cx| {
                                    let key = input2.read(cx).value().trim().trim_start_matches("Bearer ").trim().to_string();
                                    let gone = key.is_empty();
                                    pk.update(cx, |p, cx| p.update(|d| d.paxsenix_key = key, cx));
                                    Toasts::say(if gone { tr("Chiave PaxSenix tolta") } else { tr("Chiave PaxSenix salvata") }, cx);
                                })
                            }))
                            .into_any_element(),
                    ],
                    i,
                ))
                .into_any_element(),
        ]
    }

    fn storage(&mut self, i: &Ink, p: &PrefsData, cx: &mut Context<Self>) -> Vec<AnyElement> {
        let history = self.library.read(cx).history.len();
        let prefs = Prefs::entity(cx);
        let paused = p.pause_history;
        let me = cx.entity().downgrade();
        let me2 = me.clone();
        let library = self.library.clone();
        let ask_cache = self.asking("cache");
        let ask_history = self.asking("history");
        vec![
            group(tr("Spazio"), None, i)
                .child(list(
                    vec![
                        {
                            let dl = crate::downloads::DownloadsModel::entity(cx);
                            let (n, bytes, folder) = {
                                let d = dl.read(cx);
                                (d.list.tracks.len(), d.list.bytes, d.folder())
                            };
                            let ask = self.asking("downloads");
                            let me3 = cx.entity().downgrade();
                            row()
                                .child(label(tr("Brani scaricati"), Some(format!("{} · {}", crate::ui::brani(n as u64), size(bytes))), i))
                                .when_some(folder, |r, f| {
                                    r.child(button("open-dl", tr("Apri cartella"), false, false, i).on_click(move |_, _, cx| cx.open_with_system(&f)))
                                })
                                .child(button("clear-dl", if ask { tr("Sicuro? Elimina") } else { tr("Elimina tutti") }, ask, n == 0, i).when(n > 0, |b| {
                                    b.on_click(move |_, _, cx| {
                                        let dl = dl.clone();
                                        let _ = me3.update(cx, |this, cx| {
                                            if this.asking("downloads") {
                                                this.confirm = None;
                                                dl.update(cx, |m, cx| {
                                                    let ids = m.list.tracks.iter().map(|t| t.id.clone()).collect();
                                                    m.remove(ids, cx)
                                                });
                                                Toasts::say(tr("Download eliminati"), cx);
                                            } else {
                                                this.confirm = Some(Confirm { what: "downloads", at: Instant::now() });
                                            }
                                            cx.notify();
                                        });
                                    })
                                }))
                                .into_any_element()
                        },
                        // OfflineLyricsScreen.kt, in short: lyrics kept with the downloads.
                        {
                            let on = p.download_lyrics;
                            let prefs2 = prefs.clone();
                            row()
                                .child(label(tr("Scarica anche i testi"), Some(tr("Con ogni brano scaricato, per leggerli senza connessione").into()), i))
                                .child(switch("sw-dl-lyrics", on, false, i, move |cx| prefs2.update(cx, |p, cx| p.update(|d| d.download_lyrics = !on, cx))))
                                .into_any_element()
                        },
                        {
                            let dl = crate::downloads::DownloadsModel::entity(cx);
                            let (n, missing, progress) = {
                                let d = dl.read(cx);
                                (d.list.tracks.len(), d.missing_lyrics, d.lyrics_progress)
                            };
                            let caption = match progress {
                                Some((done, total)) => trf("Cerco i testi… {0} di {1}", &[&done, &total]),
                                None if n == 0 => tr("Nessun brano scaricato").to_string(),
                                None => trf("{0} di {1} con il testo salvato", &[&(n - missing), &crate::ui::brani(n as u64)]),
                            };
                            let ask = self.asking("lyrics");
                            let (dl2, me4) = (dl.clone(), cx.entity().downgrade());
                            row()
                                .child(label(tr("Testi offline"), Some(caption), i))
                                .child(
                                    button("dl-lyrics", if missing > 0 { format!("{} ({missing})", tr("Scarica mancanti")) } else { tr("Scarica mancanti").to_string() }, false, missing == 0 || progress.is_some(), i)
                                        .when(missing > 0 && progress.is_none(), |b| b.on_click(move |_, _, cx| dl.update(cx, |m, cx| m.fetch_missing_lyrics(cx)))),
                                )
                                .child(button("rm-lyrics", if ask { tr("Sicuro? Elimina") } else { tr("Elimina testi") }, ask, n == missing || progress.is_some(), i).when(n > missing && progress.is_none(), |b| {
                                    b.on_click(move |_, _, cx| {
                                        let dl = dl2.clone();
                                        let _ = me4.update(cx, |this, cx| {
                                            if this.asking("lyrics") {
                                                this.confirm = None;
                                                dl.update(cx, |m, cx| m.remove_lyrics(cx));
                                                Toasts::say(tr("Testi offline eliminati"), cx);
                                            } else {
                                                this.confirm = Some(Confirm { what: "lyrics", at: Instant::now() });
                                            }
                                            cx.notify();
                                        });
                                    })
                                }))
                                .into_any_element()
                        },
                        row()
                            .child(label(
                                tr("Cache audio"),
                                Some(trf("I brani ascoltati da poco, per ripartire subito e senza rete: {0}", &[&self.cache_size.map_or("…".to_string(), size)])),
                                i,
                            ))
                            .child(button("clear-cache", if ask_cache { tr("Sicuro? Svuota") } else { tr("Svuota") }, ask_cache, false, i).on_click(move |_, _, cx| {
                                let _ = me.update(cx, |this, cx| {
                                    if this.asking("cache") {
                                        this.confirm = None;
                                        // What is in it, not the folder: the player keeps writing there.
                                        if let Some(entries) = cache_dir().and_then(|d| std::fs::read_dir(d).ok()) {
                                            for e in entries.flatten() {
                                                let p = e.path();
                                                let _ = if p.is_dir() { std::fs::remove_dir_all(&p) } else { std::fs::remove_file(&p) };
                                            }
                                        }
                                        Toasts::say(tr("Cache audio svuotata"), cx);
                                        this.refresh(cx);
                                    } else {
                                        this.confirm = Some(Confirm { what: "cache", at: Instant::now() });
                                    }
                                    cx.notify();
                                });
                            }))
                            .into_any_element(),
                    ],
                    i,
                ))
                .into_any_element(),
            group(tr("Privacy"), None, i)
                .child(list(
                    vec![
                        row()
                            .child(label(tr("Metti in pausa la cronologia"), Some(tr("I brani che ascolti non finiscono in \"Ascoltati di recente\" né nei suggerimenti.").into()), i))
                            .child(switch("sw-pause", paused, false, i, move |cx| prefs.update(cx, |p, cx| p.update(|d| d.pause_history = !paused, cx))))
                            .into_any_element(),
                        row()
                            .child(label(tr("Cronologia di ascolto"), Some(crate::ui::brani(history as u64)), i))
                            .child(button("clear-history", if ask_history { tr("Sicuro? Cancella") } else { tr("Cancella") }, ask_history, history == 0, i).when(history > 0, |b| {
                                b.on_click(move |_, _, cx| {
                                    let _ = me2.update(cx, |this, cx| {
                                        if this.asking("history") {
                                            this.confirm = None;
                                            library.update(cx, |l, cx| l.clear_history(cx));
                                            Toasts::say(tr("Cronologia cancellata"), cx);
                                        } else {
                                            this.confirm = Some(Confirm { what: "history", at: Instant::now() });
                                        }
                                        cx.notify();
                                    });
                                })
                            }))
                            .into_any_element(),
                        row()
                            .child(label(tr("Ricerche recenti"), None, i))
                            .child(button("clear-recents", tr("Cancella"), false, false, i).on_click(|_, _, cx| {
                                crate::search::Search::clear_recents(cx);
                                Toasts::say(tr("Ricerche recenti cancellate"), cx);
                            }))
                            .into_any_element(),
                    ],
                    i,
                ))
                .into_any_element(),
        ]
    }

    /// A DJ console: which one is connected, and the control learned for each action.
    fn console(&mut self, i: &Ink, cx: &mut Context<Self>) -> AnyElement {
        use crate::midi_map::Action;
        let midi = crate::midi::MidiModel::entity(cx);
        let (device, learning, mapping) = {
            let m = midi.read(cx);
            (m.device.clone(), m.learning, m.mapping.clone())
        };
        let mut rows = vec![row()
            .child(label(tr("Console"), Some(device.as_deref().map(crate::midi::display_name).unwrap_or_else(|| tr("Nessuna console collegata. Collegala con l'USB: LFly la trova da solo.").into())), i))
            .into_any_element()];
        for action in Action::ALL {
            let bound = mapping.control_for(action);
            let listening = learning == Some(action);
            let caption = if listening {
                if action.is_fader() { tr("Muovi il fader o la manopola sulla console…").to_string() } else { tr("Premi il tasto sulla console…").to_string() }
            } else {
                bound.map_or(tr("Non assegnato").to_string(), |c| c.describe())
            };
            let (m1, m2) = (midi.clone(), midi.clone());
            // Waiting for the console: the caption breathes until a control arrives.
            let text = label(action.label(), Some(caption), i);
            let text = if listening {
                text.with_animation(SharedString::from(format!("midi-listen-{action:?}")), Animation::new(std::time::Duration::from_millis(1400)).repeat().with_max_fps(30.).with_easing(pulsating_between(0.5, 1.)), |d, t| d.opacity(t)).into_any_element()
            } else {
                text.into_any_element()
            };
            rows.push(
                row()
                    .child(text)
                    .when(bound.is_some() && !listening, |r| {
                        r.child(button(SharedString::from(format!("midi-forget-{action:?}")), tr("Togli"), false, false, i).on_click(move |_, _, cx| m1.update(cx, |m, cx| m.forget(action, cx))))
                    })
                    .child(
                        button(SharedString::from(format!("midi-learn-{action:?}")), if listening { tr("Annulla") } else { tr("Impara") }, false, device.is_none() && !listening, i).when(
                            device.is_some() || listening,
                            |b| b.on_click(move |_, _, cx| m2.update(cx, |m, cx| m.learn(if listening { None } else { Some(action) }, cx))),
                        ),
                    )
                    .into_any_element(),
            );
        }
        group(tr("Console DJ"), None, i)
            .child(list(rows, i))
            .child(foot(tr("Premi Impara accanto a un'azione, poi il tasto o il fader della console. Il volume e il tempo prendono il comando quando il fader raggiunge il valore attuale, così la musica non salta."), i))
            .into_any_element()
    }

    fn devices(&mut self, i: &Ink, window: &mut Window, cx: &mut Context<Self>) -> Vec<AnyElement> {
        use gpui_kit::component::input::{Input, InputState};
        let connect = crate::connect::ConnectModel::entity(cx);
        let (me, n, following) = {
            let c = connect.read(cx);
            (c.me.name.clone(), c.devices.len(), c.following.as_ref().map(|f| f.name.clone()))
        };
        let input = match self.name_input.clone() {
            Some(i) => i,
            None => {
                let input = cx.new(|cx| InputState::new(window, cx).default_value(me.clone()));
                crate::ui::register_field(&input.read(cx).focus_handle(cx), cx);
                // "Salva" follows the typing; Enter saves.
                let c = crate::connect::ConnectModel::entity(cx);
                self._subs.push(cx.subscribe(&input, move |_, state, ev: &gpui_kit::component::input::InputEvent, cx| {
                    if matches!(ev, gpui_kit::component::input::InputEvent::PressEnter { .. }) {
                        let name = state.read(cx).value().to_string();
                        if !name.trim().is_empty() {
                            c.update(cx, |c, cx| c.rename(&name, cx));
                            Toasts::say(tr("Nome salvato"), cx);
                        }
                    }
                    cx.notify();
                }));
                self.name_input = Some(input.clone());
                input
            }
        };
        let typed = input.read(cx).value().trim().to_string();
        let can_save = !typed.is_empty() && typed != me;
        let l = self.library.read(cx);
        let counts = trf("{0} playlist, {1} preferiti, {2} brani ascoltati.", &[&l.playlists.len(), &l.favorites.len(), &l.history.len()]);
        let fact = |k: &str, v: String| row().child(div().flex_1().child(k.to_string())).child(div().font_weight(FontWeight::BOLD).child(v)).into_any_element();
        let (c1, c2) = (connect.clone(), connect);
        let input2 = input.clone();
        vec![
            group(
                "LFly Connect",
                Some(tr("I telefoni, gli orologi e gli altri computer con LFly sulla stessa rete Wi-Fi si vedono a vicenda. La prima volta un dispositivo con un altro account chiede di confermare un codice di quattro cifre.")),
                i,
            )
            .child(list(vec![fact(tr("Questo computer"), me.clone()), fact(tr("Dispositivi in rete"), n.to_string()), fact(tr("Controlli ora"), following.unwrap_or_else(|| tr("Nessuno").into()))], i))
            .into_any_element(),
            group(tr("Questo computer"), None, i)
                .child(list(
                    vec![
                        row()
                            .child(label(tr("Nome"), Some(tr("Come lo vedono il telefono e l'orologio.").into()), i))
                            .child(div().w(px(220.)).h(px(34.)).px(px(12.)).flex().items_center().rounded_full().bg(i.fill).border_1().border_color(i.border.opacity(0.5)).child(Input::new(&input).appearance(false)))
                            .child(button("rename", tr("Salva"), false, !can_save, i).when(can_save, |b| {
                                b.on_click(move |_, _, cx| {
                                    let name = input2.read(cx).value().to_string();
                                    c1.update(cx, |c, cx| c.rename(&name, cx));
                                    Toasts::say(tr("Nome salvato"), cx);
                                })
                            }))
                            .into_any_element(),
                        row()
                            .child(label(
                                tr("Dispositivi abbinati con il codice"),
                                Some(tr("Quelli di altri account che hai accettato una volta. I tuoi si collegano sempre senza codice.").into()),
                                i,
                            ))
                            .child(button("forget", tr("Dimentica"), false, false, i).on_click(move |_, _, cx| c2.update(cx, |c, cx| c.forget_paired(cx))))
                            .into_any_element(),
                    ],
                    i,
                ))
                .child(foot(tr("Se il telefono non trova questo computer o non riesce a comandarlo, il firewall potrebbe bloccarlo: LFly Connect usa la porta TCP 45780 e mDNS (UDP 5353) sulla rete locale."), i))
                .into_any_element(),
            self.console(i, cx),
            self.sync_local(i, cx),
            self.sync_drive(i, cx),
            group(tr("Libreria"), None, i).child(list(vec![row().child(div().flex_1().child(counts)).into_any_element()], i)).into_any_element(),
        ]
    }

    /// Settings.svelte "Sincronizza con il telefono": the phones of the account on this network.
    fn sync_local(&mut self, i: &Ink, cx: &mut Context<Self>) -> AnyElement {
        let sync = crate::sync::SyncModel::entity(cx);
        let signed_in = crate::account::AccountModel::entity(cx).read(cx).info.is_some();
        let (active, st, busy) = {
            let m = sync.read(cx);
            (m.active(), m.local.clone(), m.local_busy)
        };
        let caption = if !active {
            tr("Spenta in questa sessione di prova.").to_string()
        } else if !signed_in {
            tr("Accedi a YouTube Music con lo stesso account del telefono: la sincronizzazione parte da sola.").to_string()
        } else if let Some(t) = st.last_sync {
            trf("Ultima volta alle {0}", &[&crate::sync::clock(t)])
        } else {
            tr("Preferiti, playlist e cronologia si allineano con i telefoni del tuo account aperti sulla stessa rete.").to_string()
        };
        let (s1, s2) = (sync.clone(), sync);
        let on = st.enabled;
        let mut rows = vec![row()
            .child(label(tr("Sulla stessa rete Wi-Fi"), Some(caption), i))
            .child(switch("sw-libsync", on, busy || !active, i, move |cx| s1.update(cx, |m, cx| m.set_local(!on, cx))))
            .into_any_element()];
        if on && signed_in && active {
            rows.push(
                row()
                    .child(div().flex_1().min_w_0().text_color(i.text2).child(tr("Ogni minuto e mezzo e subito dopo ogni modifica, senza configurare niente.")))
                    .child(button("libsync-now", if busy { tr("Sincronizzo…") } else { tr("Sincronizza ora") }, false, busy, i).when(!busy, |b| b.on_click(move |_, _, cx| s2.update(cx, |m, cx| m.sync_local_now(cx)))))
                    .into_any_element(),
            );
        }
        group(tr("Sincronizza con il telefono"), None, i).child(list(rows, i)).into_any_element()
    }

    /// Settings.svelte "Anche da lontano: Google Drive".
    fn sync_drive(&mut self, i: &Ink, cx: &mut Context<Self>) -> AnyElement {
        let sync = crate::sync::SyncModel::entity(cx);
        let (active, st, busy, error) = {
            let m = sync.read(cx);
            (m.active(), m.drive.clone(), m.drive_busy, m.drive_error.clone())
        };
        let (s1, s2, s3, s4) = (sync.clone(), sync.clone(), sync.clone(), sync);
        let mut g = group(tr("Anche da lontano: Google Drive"), None, i);
        if st.signed_in {
            let on = st.enabled;
            let when = st.last_sync.map_or(tr("mai sincronizzato").to_string(), |t| trf("ultima volta alle {0}", &[&crate::sync::clock(t)]));
            g = g.child(list(
                vec![
                    row()
                        .child(label(tr("Sincronizza tramite Google Drive"), Some(format!("{} · {when}", st.email.clone().unwrap_or_else(|| tr("Account Google").into()))), i))
                        .child(switch("sw-drive", on, busy || !active, i, move |cx| s1.update(cx, |m, cx| m.drive_set_enabled(!on, cx))))
                        .into_any_element(),
                    row()
                        .child(div().flex_1().min_w_0().text_color(i.text2).child(tr("Preferiti, playlist e cronologia si allineano da soli ogni pochi minuti e dopo ogni modifica.")))
                        .child(button("drive-now", if busy { tr("Sincronizzo…") } else { tr("Sincronizza ora") }, false, busy || !on || !active, i).when(!busy && on && active, |b| b.on_click(move |_, _, cx| s2.update(cx, |m, cx| m.drive_sync_now(cx)))))
                        .child(button("drive-out", tr("Scollega"), false, busy || !active, i).when(!busy && active, |b| b.on_click(move |_, _, cx| s3.update(cx, |m, cx| m.drive_sign_out(cx)))))
                        .into_any_element(),
                ],
                i,
            ));
        } else {
            let can = active && st.configured && !busy;
            g = g.child(list(
                vec![row()
                    .child(div().flex_1().min_w_0().text_color(i.text2).child(
                        tr("Per allinearli anche quando non sono sulla stessa rete: la libreria passa da una cartella nascosta del tuo Google Drive, visibile solo a LFly. Va configurato una volta; attivalo anche sul telefono."),
                    ))
                    .child(button("drive-in", if busy { tr("Aspetto Google…") } else { tr("Collega Google Drive") }, true, !can, i).when(can, |b| b.on_click(move |_, _, cx| s4.update(cx, |m, cx| m.drive_sign_in(cx)))))
                    .into_any_element()],
                i,
            ));
            if !st.configured && active {
                g = g.child(foot(tr("Manca il client OAuth di LFly: salva il file JSON del client \"App desktop\" del progetto Google Cloud di LFly come ~/.config/lfly/google-oauth.json."), i));
            }
        }
        if !active {
            g = g.child(foot(tr("Spenta in questa sessione di prova."), i));
        }
        if !error.is_empty() {
            g = g.child(div().mt(px(8.)).text_size(px(12.5)).text_color(rgb(0xff6b6b)).child(error));
        }
        g.into_any_element()
    }

    fn account(&mut self, i: &Ink, cx: &mut Context<Self>) -> Vec<AnyElement> {
        let media = crate::media::SystemMedia::entity(cx);
        let on = media.read(cx).discord_on();
        let account = crate::account::AccountModel::entity(cx);
        let info = account.read(cx).info.clone();
        let yt = match info {
            Some(who) => {
                let avatar = who.avatar_url.as_ref().and_then(|u| self.covers.update(cx, |c, cx| c.get(u, cx)));
                let ask = self.asking("signout");
                let me = cx.entity().downgrade();
                group(
                    "YouTube Music",
                    Some(tr("La Home e le playlist sono quelle del tuo account, e i \"mi piace\" arrivano anche su YouTube Music. Uscire vale anche per l'app LFly classica.")),
                    i,
                )
                .child(list(
                    vec![row()
                        .child(
                            div()
                                .size(px(48.))
                                .flex_none()
                                .rounded_full()
                                .bg(i.fill)
                                .children(avatar.map(|a| img(a).size_full().object_fit(ObjectFit::Cover).rounded_full())),
                        )
                        .child(label(&who.name, Some(if who.email.is_empty() { who.handle.clone() } else { who.email.clone() }), i))
                        .child(button("signout", if ask { tr("Sicuro? Esci") } else { tr("Esci") }, ask, false, i).on_click(move |_, _, cx| {
                            let account = account.clone();
                            let _ = me.update(cx, |this, cx| {
                                if this.asking("signout") {
                                    this.confirm = None;
                                    account.update(cx, |a, cx| a.sign_out(cx));
                                } else {
                                    this.confirm = Some(Confirm { what: "signout", at: Instant::now() });
                                }
                                cx.notify();
                            });
                        }))
                        .into_any_element()],
                    i,
                ))
            }
            None => {
                let available = crate::spotify_account::SpotifyModel::available();
                let busy = account.read(cx).signing_in;
                let caption = if busy {
                    tr("Accedi nella finestra di Google…")
                } else if available {
                    tr("La Home e le playlist diventano quelle del tuo account.")
                } else {
                    tr("Manca lfly-web accanto all'app: accedi dall'app LFly classica, questa la ritrova da sola.")
                };
                let a = account.clone();
                group(
                    "YouTube Music",
                    Some(tr("Senza account, la Home e le playlist sono quelle pubbliche. Si apre la vera pagina di accesso di Google: la password non passa da LFly.")),
                    i,
                )
                .child(list(
                    vec![row()
                        .child(label(tr("Accedi a YouTube Music"), Some(caption.to_string()), i))
                        .child(button("yt-in", tr("Accedi"), true, busy || !available, i).on_click(move |_, _, cx| a.update(cx, |a, cx| a.sign_in(cx))))
                        .into_any_element()],
                    i,
                ))
            }
        };
        let spotify = self.spotify_group(i, cx);
        vec![
            yt.into_any_element(),
            spotify,
            group("Discord", None, i)
                .child(list(
                    vec![row()
                        .child(label(
                            tr("Mostra cosa ascolti"),
                            Some(tr("Con l'app Discord aperta su questo computer, il tuo stato diventa \"Sta ascoltando\" con il brano. Si toglie quando metti in pausa.").into()),
                            i,
                        ))
                        .child(switch("sw-discord", on, false, i, move |cx| media.update(cx, |m, cx| m.set_discord(!on, cx))))
                        .into_any_element()],
                    i,
                ))
                .into_any_element(),
        ]
    }

    /// The phone's Spotify row: connect, import the library, disconnect.
    fn spotify_group(&mut self, i: &Ink, cx: &mut Context<Self>) -> AnyElement {
        use crate::spotify_account::{Busy, SpotifyModel};
        let model = SpotifyModel::entity(cx);
        let (saved, busy) = {
            let m = model.read(cx);
            (m.saved.clone(), m.busy)
        };
        let busy_text = match busy {
            Busy::SigningIn => Some(tr("Accedi nella finestra di Spotify…").to_string()),
            Busy::Importing(_, 0) => Some(tr("Leggo la tua libreria…").to_string()),
            Busy::Importing(done, total) => Some(trf("Importo la playlist {0} di {1}…", &[&(done + 1).min(total), &total])),
            Busy::Idle => None,
        };
        let idle = busy == Busy::Idle;
        let foot = tr("Porta in LFly le tue playlist e i brani che ti piacciono su Spotify. Si apre la pagina di accesso di Spotify; LFly tiene solo la sessione, su questo computer. Ogni brano viene cercato su YouTube Music quando lo ascolti.");
        let row_el = match saved {
            Some(s) if !s.expired => {
                let avatar = s.avatar_url.as_ref().and_then(|u| self.covers.update(cx, |c, cx| c.get(u, cx)));
                let name = s.name.clone().filter(|n| !n.is_empty()).unwrap_or_else(|| tr("Account Spotify").to_string());
                let caption = busy_text.clone().or_else(|| {
                    s.last_sync_ms.map(|_| trf("Ultima importazione: {0} playlist, {1} brani che ti piacciono", &[&s.last_playlists, &s.last_liked]))
                });
                let ask = self.asking("spotify-out");
                let me = cx.entity().downgrade();
                let (m1, m2) = (model.clone(), model.clone());
                row()
                    .child(
                        div()
                            .size(px(48.))
                            .flex_none()
                            .rounded_full()
                            .bg(i.fill)
                            .children(avatar.map(|a| img(a).size_full().object_fit(ObjectFit::Cover).rounded_full())),
                    )
                    .child(label(&name, caption, i))
                    .child(button("spotify-sync", tr("Importa ora"), true, !idle, i).on_click(move |_, _, cx| m1.update(cx, |m, cx| m.import(cx))))
                    .child(button("spotify-out", if ask { tr("Sicuro? Scollega") } else { tr("Scollega") }, ask, !idle, i).on_click(move |_, _, cx| {
                        let m2 = m2.clone();
                        let _ = me.update(cx, |this, cx| {
                            if this.asking("spotify-out") {
                                this.confirm = None;
                                m2.update(cx, |m, cx| m.disconnect(cx));
                            } else {
                                this.confirm = Some(Confirm { what: "spotify-out", at: Instant::now() });
                            }
                            cx.notify();
                        });
                    }))
            }
            saved => {
                let expired = saved.is_some();
                let available = SpotifyModel::available();
                let caption = busy_text.or_else(|| {
                    Some(if !available {
                        tr("Manca lfly-web accanto all'app: questa versione non può aprire la pagina di Spotify.").to_string()
                    } else if expired {
                        tr("L'accesso è scaduto: collegalo di nuovo.").to_string()
                    } else {
                        tr("Playlist e brani che ti piacciono, dal tuo account.").to_string()
                    })
                });
                let m = model.clone();
                row()
                    .child(label(tr("Collega Spotify"), caption, i))
                    .child(button("spotify-in", tr("Collega"), true, !idle || !available, i).on_click(move |_, _, cx| m.update(cx, |m, cx| m.connect(cx))))
            }
        };
        group("Spotify", Some(foot), i).child(list(vec![row_el.into_any_element()], i)).into_any_element()
    }

    fn about(&mut self, i: &Ink, cx: &mut Context<Self>) -> Vec<AnyElement> {
        let me = cx.entity().downgrade();
        let rows: Vec<AnyElement> = if self.health.iter().all(|h| h.last_checked_ms.is_none()) {
            vec![row().child(div().flex_1().min_w_0().text_color(i.text2).child(tr("Nessun servizio usato finora."))).into_any_element()]
        } else {
            self.health
                .iter()
                .filter(|h| h.last_checked_ms.is_some())
                .map(|h| {
                    use lfly_yt::HealthStatus::*;
                    let (dot, word) = match h.status {
                        Online => (0x30d158, tr("Attivo")),
                        Degraded => (0xffd60a, tr("Lento")),
                        Offline => (0xff453a, tr("Non risponde")),
                    };
                    let latency = h.latency_ms.filter(|_| !matches!(h.status, Offline)).map_or(String::new(), |ms| format!(" · {ms} ms"));
                    row()
                        .child(div().size(px(9.)).flex_none().rounded_full().bg(Rgb::hex(dot).hsla(1.)))
                        .child(div().flex_1().child(h.name.clone()))
                        .child(div().text_color(i.text2).child(format!("{word}{latency}")))
                        .into_any_element()
                })
                .collect()
        };
        vec![
            div()
                .mt(px(28.))
                .child(
                    div()
                        .mx(px(4.))
                        .mb(px(12.))
                        .flex()
                        .items_end()
                        .justify_between()
                        .child(div().text_size(px(17.)).font_weight(FontWeight::SEMIBOLD).child(tr("Stato dei servizi")))
                        .child(button("health", tr("Aggiorna"), false, false, i).on_click(move |_, _, cx| {
                            let _ = me.update(cx, |this, cx| {
                                this.health = lfly_core::pages::health(&this.core.yt);
                                cx.notify();
                            });
                        })),
                )
                .child(list(rows, i))
                .child(foot(tr("Se una fonte non risponde, LFly passa da sola alla successiva e la riprova più tardi."), i))
                .into_any_element(),
            group(tr("LFly per computer"), None, i)
                .child(list(
                    vec![
                        row().child(div().flex_1().child(tr("Versione"))).child(div().font_weight(FontWeight::BOLD).child(format!("{} ({})", env!("CARGO_PKG_VERSION"), tr("nativa")))).into_any_element(),
                        row().child(div().flex_1().child(tr("Sistemi"))).child(div().text_color(i.text2).child(tr("Linux, Windows e macOS"))).into_any_element(),
                        row().child(div().flex_1().child(tr("Licenza"))).child(div().text_color(i.text2).child(tr("GPL-3.0, software libero"))).into_any_element(),
                    ],
                    i,
                ))
                .child(foot(
                    tr("Disegnata con GPUI (Apache 2.0). Il Liquid Glass porta sul computer la lente di Kyant0/backdrop (Apache 2.0), la stessa dell'app per telefono. Il carattere Inter è di Rasmus Andersson (SIL Open Font License)."),
                    i,
                ))
                .into_any_element(),
        ]
    }
}

impl Render for SettingsPage {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let s = self.look.read(cx).scheme.clone();
        let i = Ink::of(&s);
        let p = Prefs::get(cx);
        let body = match self.section {
            Section::Appearance => self.appearance(&i, cx),
            Section::General => self.general(&i, &p, cx),
            Section::Sound => self.sound(&i, cx),
            Section::Lyrics => self.lyrics(&i, &p, window, cx),
            Section::Storage => self.storage(&i, &p, cx),
            Section::Devices => self.devices(&i, window, cx),
            Section::Account => self.account(&i, cx),
            Section::About => self.about(&i, cx),
        };
        let me = cx.entity().downgrade();
        let tabs = seg("tabs", &self.section, &TABS, &i, move |v, cx| {
            let _ = me.update(cx, |this, cx| {
                this.section = v;
                this.confirm = None;
                this.refresh(cx);
                cx.notify();
            });
        });
        let _ = i.light;
        div()
            .id("settings")
            .size_full()
            .overflow_y_scroll()
            .pt(px(62.))
            .child(
                div()
                    .max_w(px(1040.))
                    .pt(px(28.))
                    .px(px(28.))
                    .pb(px(56. + 104.))
                    .text_size(px(14.))
                    .text_color(i.s_text)
                    .child(div().mt(px(4.)).mb(px(18.)).text_size(px(32.)).line_height(px(35.)).font_weight(FontWeight::BOLD).child(tr("Impostazioni")))
                    .child(div().mb(px(8.)).w_full().flex().child(tabs))
                    .children(body),
            )
    }
}
