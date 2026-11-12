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

const TABS: [(Section, &str); 8] = [
    (Section::General, "Generale"),
    (Section::Appearance, "Aspetto"),
    (Section::Sound, "Riproduzione"),
    (Section::Lyrics, "Testi"),
    (Section::Devices, "Dispositivi"),
    (Section::Account, "Account"),
    (Section::Storage, "Spazio e privacy"),
    (Section::About, "Informazioni"),
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
    strength_draft: Option<f32>,
    confirm: Option<Confirm>,
    cache_size: Option<u64>,
    health: Vec<lfly_yt::SourceHealth>,
    _subs: Vec<Subscription>,
}

/// SettingsExtra `size`: 1,2 GB, 340 MB, 12 kB, "vuota".
fn size(b: u64) -> String {
    if b >= 1_000_000_000 {
        format!("{:.1} GB", b as f64 / 1e9).replace('.', ",")
    } else if b >= 1_000_000 {
        format!("{} MB", (b as f64 / 1e6).round())
    } else if b > 0 {
        format!("{} kB", ((b as f64 / 1e3).round() as u64).max(1))
    } else {
        "vuota".into()
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
            strength_draft: None,
            confirm: None,
            cache_size: None,
            health: Vec::new(),
            _subs: subs,
        }
    }

    /// Called when the page is opened: what it shows is read again.
    pub fn refresh(&mut self, cx: &mut Context<Self>) {
        self.playback = lfly_core::settings::playback();
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
                .child(text.to_string())
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
        let (mode, accent_id, glass_on, lens, strength) = (l.mode, l.accent.clone(), l.glass_enabled, l.lens, l.strength);
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
            mode_card(m, title, sub, mode == m, card_w, accent, cover, cover_img.clone(), i, move |cx| look.update(cx, |l, cx| l.set_mode(m, cx)))
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
                    .child(div().flex_1().font_weight(FontWeight::SEMIBOLD).child(name.to_string()))
                    .child(div().text_size(px(12.)).text_color(i.text2).child(if premium { "Premium" } else { "Classico" }))
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
                            .child(div().font_weight(FontWeight::BOLD).child(if glass_on && lens { "Lente attiva" } else if glass_on { "Solo sfocatura" } else { "Superficie piena" })),
                    ),
            );
        let (l1, l2, l3, l4) = (look.clone(), look.clone(), look.clone(), look.clone());
        let me = cx.entity().downgrade();
        let me2 = me.clone();
        let glass_list = list(
            vec![
                row()
                    .child(tile("expand", 0x4d8dff, 0x2f6bf0))
                    .child(label("Liquid Glass", Some("Vetro traslucido sulle superfici che galleggiano sopra i contenuti.".into()), i))
                    .child(switch("sw-glass", glass_on, false, i, move |cx| l1.update(cx, |l, cx| l.set_glass(Some(!glass_on), None, None, cx))))
                    .into_any_element(),
                row()
                    .child(tile("lyrics", 0xa173ff, 0x7c4dff))
                    .child(label("Effetto lente", Some("Rifrazione ai bordi, dispersione cromatica e riflessi che seguono il puntatore. Usa più GPU.".into()), i))
                    .child(switch("sw-lens", lens, !glass_on, i, move |cx| l2.update(cx, |l, cx| l.set_glass(None, Some(!lens), None, cx))))
                    .into_any_element(),
                row()
                    .flex_wrap()
                    .child(tile("volume", 0x8e8e93, 0x636366))
                    .child(label("Intensità", Some("Da più solido a più trasparente, con una lente più marcata.".into()), i))
                    .child(div().ml_auto().text_size(px(12.)).text_color(i.text2).child("Solido"))
                    .child(div().w(px((avail * 0.4).min(220.))).flex().child(slider(
                        "glass-strength",
                        k,
                        !glass_on,
                        SliderStyle { text: i.s_text, accent: i.accent },
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
                    .child(div().text_size(px(12.)).text_color(i.text2).child("Chiaro"))
                    .into_any_element(),
            ],
            i,
        );
        let _ = l4;
        vec![
            group("Aspetto", Some("Canvas Liquid Glass e animazioni, come sul telefono."), i).child(modes).into_any_element(),
            group(
                "Colore accento",
                Some(if followed {
                    "Si applica a tutti i temi; nei temi dinamici segue la copertina quando disponibile. Ora segue la copertina."
                } else {
                    "Si applica a tutti i temi; nei temi dinamici segue la copertina quando disponibile."
                }),
                i,
            )
            .child(accents)
            .into_any_element(),
            group("Liquid Glass", Some("Vetro con rifrazione reale su barre, pannelli e pulsanti."), i).child(stage).child(glass_list).into_any_element(),
            group(
                "Temi personalizzati",
                Some("I temi tuoi, quelli di Spicetify e il Marketplace non sono ancora nella versione nativa: per ora si creano e si usano nell'app LFly classica."),
                i,
            )
            .into_any_element(),
        ]
    }

    fn general(&mut self, i: &Ink, p: &PrefsData, cx: &mut Context<Self>) -> Vec<AnyElement> {
        let prefs = Prefs::entity(cx);
        let (p1, p2) = (prefs.clone(), prefs);
        const KEYS: [(&str, &str); 6] = [
            ("Spazio", "Riproduci o metti in pausa"),
            ("Ctrl + →  /  Ctrl + ←", "Brano successivo / precedente"),
            ("Maiusc + →  /  Maiusc + ←", "Avanti / indietro di 10 secondi"),
            ("Ctrl + K", "Cerca"),
            ("Alt + ←  /  Alt + →", "Pagina precedente / successiva"),
            ("Esc", "Chiudi il player a schermo intero"),
        ];
        vec![
            group("Avvio", None, i)
                .child(list(
                    vec![
                        row()
                            .child(label("Apri LFly all'accesso", Some("Per ora si imposta dall'app LFly classica, che è quella installata.".into()), i))
                            .child(switch("sw-autostart", false, true, i, |_| {}))
                            .into_any_element(),
                        row()
                            .child(label("Pagina iniziale", Some("Dove si apre LFly.".into()), i))
                            .child(seg(
                                "start",
                                &p.start_page,
                                &[("home".to_string(), "Home"), ("library".to_string(), "Libreria"), ("liked".to_string(), "Preferiti"), ("search".to_string(), "Cerca")],
                                i,
                                move |v, cx| p1.update(cx, |p, cx| p.update(|d| d.start_page = v, cx)),
                            ))
                            .into_any_element(),
                    ],
                    i,
                ))
                .into_any_element(),
            group("Movimento", None, i)
                .child(list(
                    vec![row()
                        .child(label("Animazioni", Some("Ridotte: niente scorrimenti, copertina dinamica ferma, cambi istantanei.".into()), i))
                        .child(seg(
                            "motion",
                            &p.motion,
                            &[("system".to_string(), "Come il sistema"), ("full".to_string(), "Complete"), ("reduce".to_string(), "Ridotte")],
                            i,
                            move |v, cx| p2.update(cx, |p, cx| p.update(|d| d.motion = v, cx)),
                        ))
                        .into_any_element()],
                    i,
                ))
                .into_any_element(),
            group("Scorciatoie da tastiera", None, i)
                .child(list(
                    KEYS.iter()
                        .map(|(k, what)| {
                            row()
                                .child(div().flex_1().child(what.to_string()))
                                .child(div().text_size(px(12.)).font_weight(FontWeight::SEMIBOLD).px(px(9.)).py(px(6.)).rounded(px(8.)).text_color(i.text2).bg(i.s_text.opacity(0.08)).child(k.to_string()))
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
        let (me1, me2) = (cx.entity().downgrade(), cx.entity().downgrade());
        vec![
            group("Riproduzione", None, i)
                .child(list(
                    vec![
                        row()
                            .child(label("Continua con brani simili", Some("Finita la coda, la musica va avanti con brani come l'ultimo, come la radio di YouTube Music.".into()), i))
                            .child(switch("sw-autoplay", pb.autoplay, false, i, move |cx| {
                                let _ = me1.update(cx, |this, cx| this.set_playback(|p| p.autoplay = !p.autoplay, cx));
                            }))
                            .into_any_element(),
                        row()
                            .child(label(
                                "Formato audio",
                                Some("AAC (fino a 256 kbps) suona ovunque; Opus (fino a 160 kbps) occupa meno a parità di qualità. Vale dal prossimo brano.".into()),
                                i,
                            ))
                            .child(seg("format", &pb.format, &[("aac".to_string(), "AAC"), ("opus".to_string(), "Opus")], i, move |v, cx| {
                                let _ = me2.update(cx, |this, cx| this.set_playback(|p| p.format = v, cx));
                            }))
                            .into_any_element(),
                    ],
                    i,
                ))
                .into_any_element(),
            group("Equalizzatore", Some("L'equalizzatore e la dissolvenza tra i brani non sono ancora nella versione nativa."), i).into_any_element(),
        ]
    }

    fn lyrics(&mut self, i: &Ink, p: &PrefsData, cx: &mut Context<Self>) -> Vec<AnyElement> {
        let prefs = Prefs::entity(cx);
        let (p1, p2, p3, p4) = (prefs.clone(), prefs.clone(), prefs.clone(), prefs);
        let (words, source) = (p.lyrics_words, p.lyrics_source);
        let data = self.lyrics.clone();
        vec![group("Testi", None, i)
            .child(list(
                vec![
                    row()
                        .child(label("Parola per parola", Some("Ogni sillaba si accende mentre la canti, quando la fonte ha i tempi delle parole. Spento: righe intere.".into()), i))
                        .child(switch("sw-words", words, false, i, move |cx| p1.update(cx, |p, cx| p.update(|d| d.lyrics_words = !words, cx))))
                        .into_any_element(),
                    row()
                        .child(label("Dimensione", None, i))
                        .child(seg("lsize", &p.lyrics_size, &[("s".to_string(), "Piccola"), ("m".to_string(), "Media"), ("l".to_string(), "Grande")], i, move |v, cx| {
                            p2.update(cx, |p, cx| p.update(|d| d.lyrics_size = v, cx))
                        }))
                        .into_any_element(),
                    row()
                        .child(label("Allineamento", None, i))
                        .child(seg("lalign", &p.lyrics_center, &[(false, "A sinistra"), (true, "Al centro")], i, move |v, cx| {
                            p3.update(cx, |p, cx| p.update(|d| d.lyrics_center = v, cx))
                        }))
                        .into_any_element(),
                    row()
                        .child(label("Mostra la fonte", Some("\"Testo da …\" in fondo al testo.".into()), i))
                        .child(switch("sw-source", source, false, i, move |cx| p4.update(cx, |p, cx| p.update(|d| d.lyrics_source = !source, cx))))
                        .into_any_element(),
                    row()
                        .child(label("Sincronie salvate", Some("Le correzioni di tempo (− e +) che hai fatto canzone per canzone.".into()), i))
                        .child(button("reset-offsets", "Azzera", false, false, i).on_click(move |_, _, cx| {
                            data.update(cx, |d, cx| d.reset_offsets(cx));
                            Toasts::say("Sincronie dei testi azzerate (vale dal prossimo brano)", cx);
                        }))
                        .into_any_element(),
                ],
                i,
            ))
            .child(foot(
                "I testi arrivano dalle stesse fonti del telefono: YouTube Music, BetterLyrics, lrc.red, BiniLyrics, LyricsPlus, Unison, KuGou e LRCLIB. Vince quella che corrisponde meglio a titolo, artista e durata, e i tempi parola per parola hanno la precedenza.",
                i,
            ))
            .into_any_element()]
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
            group("Spazio", None, i)
                .child(list(
                    vec![
                        row()
                            .child(label("Brani scaricati", Some("I download non sono ancora nella versione nativa.".into()), i))
                            .into_any_element(),
                        row()
                            .child(label(
                                "Cache audio",
                                Some(format!("I brani ascoltati da poco, per ripartire subito e senza rete: {}", self.cache_size.map_or("…".to_string(), size))),
                                i,
                            ))
                            .child(button("clear-cache", if ask_cache { "Sicuro? Svuota" } else { "Svuota" }, ask_cache, false, i).on_click(move |_, _, cx| {
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
                                        Toasts::say("Cache audio svuotata", cx);
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
            group("Privacy", None, i)
                .child(list(
                    vec![
                        row()
                            .child(label("Metti in pausa la cronologia", Some("I brani che ascolti non finiscono in \"Ascoltati di recente\" né nei suggerimenti.".into()), i))
                            .child(switch("sw-pause", paused, false, i, move |cx| prefs.update(cx, |p, cx| p.update(|d| d.pause_history = !paused, cx))))
                            .into_any_element(),
                        row()
                            .child(label("Cronologia di ascolto", Some(format!("{history} brani")), i))
                            .child(button("clear-history", if ask_history { "Sicuro? Cancella" } else { "Cancella" }, ask_history, history == 0, i).when(history > 0, |b| {
                                b.on_click(move |_, _, cx| {
                                    let _ = me2.update(cx, |this, cx| {
                                        if this.asking("history") {
                                            this.confirm = None;
                                            library.update(cx, |l, cx| l.clear_history(cx));
                                            Toasts::say("Cronologia cancellata", cx);
                                        } else {
                                            this.confirm = Some(Confirm { what: "history", at: Instant::now() });
                                        }
                                        cx.notify();
                                    });
                                })
                            }))
                            .into_any_element(),
                    ],
                    i,
                ))
                .into_any_element(),
        ]
    }

    fn devices(&mut self, i: &Ink, cx: &mut Context<Self>) -> Vec<AnyElement> {
        let l = self.library.read(cx);
        let counts = format!("{} playlist, {} preferiti, {} brani ascoltati.", l.playlists.len(), l.favorites.len(), l.history.len());
        vec![
            group("LFly Connect", Some("Connect, la sincronizzazione con il telefono e Google Drive non sono ancora nella versione nativa: per ora restano nell'app LFly classica."), i).into_any_element(),
            group("Libreria", None, i).child(list(vec![row().child(div().flex_1().child(counts)).into_any_element()], i)).into_any_element(),
        ]
    }

    fn account(&mut self, i: &Ink) -> Vec<AnyElement> {
        vec![
            group("YouTube Music", Some("L'accesso con Google non è ancora nella versione nativa: la Home e le playlist sono quelle pubbliche."), i).into_any_element(),
            group("Discord", Some("Lo stato \"Sta ascoltando\" su Discord non è ancora nella versione nativa."), i).into_any_element(),
        ]
    }

    fn about(&mut self, i: &Ink, cx: &mut Context<Self>) -> Vec<AnyElement> {
        let me = cx.entity().downgrade();
        let rows: Vec<AnyElement> = if self.health.iter().all(|h| h.last_checked_ms.is_none()) {
            vec![row().child(div().flex_1().text_color(i.text2).child("Nessun servizio usato finora.")).into_any_element()]
        } else {
            self.health
                .iter()
                .filter(|h| h.last_checked_ms.is_some())
                .map(|h| {
                    use lfly_yt::HealthStatus::*;
                    let (dot, word) = match h.status {
                        Online => (0x30d158, "Attivo"),
                        Degraded => (0xffd60a, "Lento"),
                        Offline => (0xff453a, "Non risponde"),
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
                        .child(div().text_size(px(17.)).font_weight(FontWeight::SEMIBOLD).child("Stato dei servizi"))
                        .child(button("health", "Aggiorna", false, false, i).on_click(move |_, _, cx| {
                            let _ = me.update(cx, |this, cx| {
                                this.health = lfly_core::pages::health(&this.core.yt);
                                cx.notify();
                            });
                        })),
                )
                .child(list(rows, i))
                .child(foot("Se una fonte non risponde, LFly passa da sola alla successiva e la riprova più tardi.", i))
                .into_any_element(),
            group("LFly per computer", None, i)
                .child(list(
                    vec![
                        row().child(div().flex_1().child("Versione")).child(div().font_weight(FontWeight::BOLD).child(format!("{} (nativa)", env!("CARGO_PKG_VERSION")))).into_any_element(),
                        row().child(div().flex_1().child("Sistemi")).child(div().text_color(i.text2).child("Linux e Windows")).into_any_element(),
                        row().child(div().flex_1().child("Licenza")).child(div().text_color(i.text2).child("GPL-3.0, software libero")).into_any_element(),
                    ],
                    i,
                ))
                .child(foot(
                    "Disegnata con GPUI (Apache 2.0). Il Liquid Glass porta sul computer la lente di Kyant0/backdrop (Apache 2.0), la stessa dell'app per telefono. Il carattere Inter è di Rasmus Andersson (SIL Open Font License).",
                    i,
                ))
                .into_any_element(),
        ]
    }
}

impl Render for SettingsPage {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let s = self.look.read(cx).scheme.clone();
        let i = Ink::of(&s);
        let p = Prefs::get(cx);
        let body = match self.section {
            Section::Appearance => self.appearance(&i, cx),
            Section::General => self.general(&i, &p, cx),
            Section::Sound => self.sound(&i, cx),
            Section::Lyrics => self.lyrics(&i, &p, cx),
            Section::Storage => self.storage(&i, &p, cx),
            Section::Devices => self.devices(&i, cx),
            Section::Account => self.account(&i),
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
                    .child(div().mt(px(4.)).mb(px(18.)).text_size(px(32.)).line_height(px(35.)).font_weight(FontWeight::BOLD).child("Impostazioni"))
                    .child(div().mb(px(8.)).w_full().flex().child(tabs))
                    .children(body),
            )
    }
}
