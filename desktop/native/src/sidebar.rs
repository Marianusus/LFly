//! The sidebar (Tauri: components/Sidebar.svelte): a glass panel with the mark, the three tabs,
//! the playlists (liked songs, downloads, local and account playlists) and Settings.
//! The selected row sits on the accent-tinted pill of SelectionPill.svelte (still, for now:
//! Tauri slides it with a spring).

use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;

use crate::covers::Covers;
use crate::glass;
use crate::icons::icon;
use crate::library_model::LibraryModel;
use crate::look::Look;
use crate::nav::{Nav, Route};
use crate::theme::Rgb;

pub struct Sidebar {
    nav: Entity<Nav>,
    ui: Entity<crate::ui::Ui>,
    /// Set by the window before it draws: `ui.sideWide`, and whether the toggle shows.
    pub wide: bool,
    pub can_toggle: bool,
    library: Entity<LibraryModel>,
    look: Entity<Look>,
    covers: Entity<Covers>,
    _subs: Vec<Subscription>,
}

impl Sidebar {
    pub fn new(
        nav: Entity<Nav>,
        ui: Entity<crate::ui::Ui>,
        library: Entity<LibraryModel>,
        look: Entity<Look>,
        covers: Entity<Covers>,
        cx: &mut Context<Self>,
    ) -> Self {
        let subs = vec![
            cx.observe(&nav, |_, _, cx| cx.notify()),
            cx.observe(&library, |_, _, cx| cx.notify()),
            cx.observe(&look, |_, _, cx| cx.notify()),
            cx.observe(&covers, |_, _, cx| cx.notify()),
        ];
        Self { nav, ui, wide: true, can_toggle: true, library, look, covers, _subs: subs }
    }
}

#[derive(Clone, Copy)]
struct C {
    text: Hsla,
    text2: Hsla,
    text3: Hsla,
    accent: Rgb,
    tint: Hsla,
    separator: Hsla,
}

/// SelectionPill.svelte `.fill`: the accent at 20% over the glass tint, a 22% accent ring.
fn pill_bg(c: &C) -> Hsla {
    let tint = c.tint;
    let a = c.accent.hsla(1.);
    // color-mix(accent 20%, tint): mix in premultiplied space.
    let ta = tint.a;
    let alpha = 0.2 + 0.8 * ta;
    let mix = |x: f32, y: f32| (0.2 * x + 0.8 * ta * y) / alpha.max(1e-4);
    let ar: Rgba = a.into();
    let tr: Rgba = tint.into();
    Rgba { r: mix(ar.r, tr.r), g: mix(ar.g, tr.g), b: mix(ar.b, tr.b), a: alpha }.into()
}

impl Render for Sidebar {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let (c, look) = {
            let l = self.look.read(cx);
            let s = &l.scheme;
            (
                C {
                    text: s.text,
                    text2: s.text2,
                    text3: s.text3,
                    accent: s.accent,
                    tint: s.glass_tint,
                    // apply.ts --lfly-separator.
                    separator: if s.light { Rgb::hex(0x15151a).hsla(0.1) } else { Rgb::hex(0xffffff).hsla(0.1) },
                },
                l.glass(),
            )
        };
        let key = self.nav.read(cx).page_route().key();
        // Folded: every icon stays where it is, the words go (Sidebar.svelte `.narrow`).
        let wide = self.wide;
        let lib = self.library.read(cx);
        let favorites = lib.favorites.len();
        let playlists = lib.playlists.clone();
        let accent = c.accent.hsla(1.);
        let nav = self.nav.clone();
        let go = move |route: Route| {
            let nav = nav.clone();
            move |_: &ClickEvent, _: &mut Window, cx: &mut App| nav.update(cx, |n, cx| n.go(route.clone(), cx))
        };

        // `.item`: 40 px, 600 14px, text-2; active: text, the icon in the accent, on the pill.
        let item = |k: &'static str, label: &'static str, name: &'static str, size: f32, route: Route| {
            let on = key == k;
            let name = if on && name == "home" { "home-fill" } else { name };
            let group: SharedString = format!("sb-{k}").into();
            div()
                .id(k)
                .group(group.clone())
                .relative()
                .w_full()
                .h(px(40.))
                .px(px(14.))
                .flex()
                .items_center()
                .gap(px(12.))
                .rounded_full()
                .text_size(px(14.))
                .font_weight(FontWeight::SEMIBOLD)
                .text_color(if on { c.text } else { c.text2 })
                .cursor_pointer()
                .when(on, |s| s.bg(pill_bg(&c)).border_1().border_color(accent.opacity(0.22)))
                .when(!on, |s| s.hover(|s| s.text_color(c.text).bg(c.text.opacity(0.06))))
                .on_click(go(route))
                .child(
                    div().w(px(22.)).flex().justify_center().flex_none().child(
                        icon(name, size, if on { accent } else { c.text2 })
                            .when(!on, |i| i.group_hover(group.clone(), |s| s.text_color(c.text))),
                    ),
                )
                .when(wide, |d| d.child(div().whitespace_nowrap().child(label)))
        };

        // `.entry`: 6 px padding, a 40 px art, name 500 13.5px and a 12px subtitle.
        let entry = |k: String, art: AnyElement, name: SharedString, sub: SharedString, route: Route| {
            let on = key == k;
            div()
                .id(SharedString::from(k))
                .w_full()
                .p(px(6.))
                .flex()
                .items_center()
                .gap(px(12.))
                .rounded(px(16.))
                .cursor_pointer()
                .when(on, |s| s.bg(pill_bg(&c)).border_1().border_color(accent.opacity(0.22)))
                .when(!on, |s| s.hover(|s| s.bg(c.text.opacity(0.06))))
                .on_click(go(route))
                .child(art)
                .when(wide, |d| {
                    d.child(
                        div()
                            .min_w_0()
                            .flex()
                            .flex_col()
                            .gap(px(2.))
                            .child(div().text_size(px(13.5)).font_weight(FontWeight::MEDIUM).text_color(c.text).truncate().child(name))
                            .child(div().text_size(px(12.)).text_color(c.text3).truncate().child(sub)),
                    )
                })
                .into_any_element()
        };

        let liked_art = div()
            .size(px(40.))
            .flex_none()
            .rounded(px(11.))
            .flex()
            .items_center()
            .justify_center()
            // linear-gradient(140deg, accent+white, accent, accent+indigo): two stops in GPUI.
            .bg(linear_gradient(140., linear_color_stop(crate::theme::mix(c.accent, Rgb::hex(0xffffff), 0.15).hsla(1.), 0.), linear_color_stop(crate::theme::mix(c.accent, Rgb::hex(0x3a0ca3), 0.45).hsla(1.), 1.)))
            .child(icon("heart-fill", 17., white()))
            .into_any_element();

        let mut entries = vec![entry(
            "liked".into(),
            liked_art,
            "Brani che ti piacciono".into(),
            format!("Playlist · {favorites} brani").into(),
            Route::Liked,
        )];
        for p in playlists {
            let cover = p.cover_url.as_ref().and_then(|u| self.covers.update(cx, |cv, cx| cv.get(u, cx)));
            let art = div()
                .size(px(40.))
                .flex_none()
                .rounded(px(11.))
                .overflow_hidden()
                .bg(c.text.opacity(0.08))
                .children(cover.map(|i| img(i).size_full().object_fit(ObjectFit::Cover).rounded(px(11.))))
                .into_any_element();
            entries.push(entry(
                format!("playlist:{}", p.id),
                art,
                p.name.clone().into(),
                format!("Playlist · {} brani", p.count).into(),
                Route::Playlist { id: p.id, name: p.name, local: true },
            ));
        }

        div()
            .relative()
            .size_full()
            .rounded(px(28.))
            .child(glass::glass_layer(look, px(28.), glass::Pointer::default()))
            .child(
                div()
                    .absolute()
                    .inset_0()
                    .rounded(px(28.))
                    .overflow_hidden()
                    .pt(px(14.))
                    .px(px(10.))
                    .pb(px(10.))
                    .flex()
                    .flex_col()
                    .min_h_0()
                    .child({
                        // The brand: the mark, the word, the toggle (under the mark when folded,
                        // shown on hover).
                        let ui = self.ui.clone();
                        let toggle = div()
                            .id("sb-toggle")
                            .size(px(32.))
                            .flex_none()
                            .rounded_full()
                            .flex()
                            .items_center()
                            .justify_center()
                            .cursor_pointer()
                            .group("sb-toggle")
                            .hover(|d| d.bg(c.tint))
                            .on_click(move |_, _, cx| ui.update(cx, |u, cx| u.toggle_sidebar(cx)))
                            .child(icon("sidebar", 17., c.text2).group_hover("sb-toggle", |s| s.text_color(c.text)));
                        div()
                            .relative()
                            .group("sb-brand")
                            .flex()
                            .items_center()
                            .gap(px(10.))
                            .pt(px(2.))
                            .px(px(8.))
                            .pb(px(14.))
                            .min_h(px(44.))
                            .child(
                                div()
                                    .size(px(32.))
                                    .flex()
                                    .items_center()
                                    .justify_center()
                                    .when(!wide && self.can_toggle, |d| d.group_hover("sb-brand", |d| d.invisible()))
                                    .child(icon("mark", if wide { 28. } else { 30. }, c.text)),
                            )
                            .when(wide, |d| d.child(div().text_size(px(20.)).font_weight(FontWeight::BOLD).text_color(c.text).child("LFly")))
                            .when(self.can_toggle && wide, |d| d.child(div().ml_auto().child(toggle)))
                            .when_some(
                                (self.can_toggle && !wide).then(|| {
                                    div()
                                        .id("sb-toggle-wrap")
                                        .absolute()
                                        .left(px(8.))
                                        .top(px(2.))
                                        .rounded_full()
                                        .bg(c.tint)
                                        .invisible()
                                        .group_hover("sb-brand", |d| d.visible())
                                        .child(
                                            div()
                                                .id("sb-toggle-n")
                                                .size(px(32.))
                                                .rounded_full()
                                                .flex()
                                                .items_center()
                                                .justify_center()
                                                .cursor_pointer()
                                                .on_click({
                                                    let ui = self.ui.clone();
                                                    move |_, _, cx| ui.update(cx, |u, cx| u.toggle_sidebar(cx))
                                                })
                                                .child(icon("sidebar", 17., c.text)),
                                        )
                                }),
                                |d, t| d.child(t),
                            )
                    })
                    .child(
                        div()
                            .px(px(4.))
                            .flex()
                            .flex_col()
                            .gap(px(2.))
                            .child(item("home", "Home", "home", 20., Route::Home))
                            .child(item("search", "Cerca", "search", 20., Route::Search))
                            .child(item("library", "Libreria", "library", 20., Route::Library)),
                    )
                    .child(
                        // `.section`: the caption and the way to the library; folded, a rule.
                        div()
                            .relative()
                            .flex()
                            .items_center()
                            .justify_between()
                            .h(px(26. + 24.))
                            .pt(px(18.))
                            .pr(px(12.))
                            .pb(px(6.))
                            .pl(px(18.))
                            .when(wide, |d| {
                                d.child(div().text_size(px(11.)).font_weight(FontWeight::SEMIBOLD).text_color(c.text3).whitespace_nowrap().child("PLAYLIST")).child(
                                    div()
                                        .id("sb-mini")
                                        .size(px(26.))
                                        .rounded_full()
                                        .flex()
                                        .items_center()
                                        .justify_center()
                                        .cursor_pointer()
                                        .hover(|d| d.bg(c.tint))
                                        .on_click(go(Route::Library))
                                        .child(icon("expand", 13., c.text3)),
                                )
                            })
                            .when(!wide, |d| d.child(div().absolute().left(px(14.)).right(px(14.)).top(px(25.)).h(px(1.)).bg(c.separator))),
                    )
                    .child(
                        div()
                            .id("sb-scroll")
                            .flex_1()
                            .min_h_0()
                            .overflow_y_scroll()
                            .px(px(4.))
                            .pt(px(2.))
                            .pb(px(8.))
                            .flex()
                            .flex_col()
                            .gap(px(2.))
                            .children(entries),
                    )
                    .child(
                        div()
                            .pt(px(8.))
                            .mt(px(4.))
                            .px(px(4.))
                            .border_t_1()
                            .border_color(c.separator)
                            .child(item("settings", "Impostazioni", "settings", 19., Route::Settings)),
                    ),
            )
    }
}
