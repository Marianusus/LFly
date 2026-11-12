//! Your library (Tauri: pages/Library.svelte, parts/LibraryItem.svelte), grid view: the title
//! and counts, the import and new-playlist pills, the filters, your collections.
//! (Still missing: the list view, the import and create actions, the downloads filter.)

use std::sync::Arc;

use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use serde_json::Value;

use crate::core::Core;
use crate::covers::Covers;
use crate::icons::icon;
use crate::library_model::LibraryModel;
use crate::look::Look;
use crate::nav::{Nav, Route};
use crate::theme::{mix, Rgb};

#[derive(Clone, Copy, PartialEq)]
enum Filter {
    All,
    Playlists,
    Recent,
}

pub struct LibraryPage {
    core: Arc<Core>,
    covers: Entity<Covers>,
    look: Entity<Look>,
    nav: Entity<Nav>,
    library: Entity<LibraryModel>,
    filter: Filter,
    pub page_width: f32,
    _subs: Vec<Subscription>,
}

impl LibraryPage {
    pub fn new(core: Arc<Core>, covers: Entity<Covers>, look: Entity<Look>, nav: Entity<Nav>, library: Entity<LibraryModel>, cx: &mut Context<Self>) -> Self {
        let subs = vec![
            cx.observe(&covers, |_, _, cx| cx.notify()),
            cx.observe(&look, |_, _, cx| cx.notify()),
            cx.observe(&library, |_, _, cx| cx.notify()),
        ];
        Self { core, covers, look, nav, library, filter: Filter::All, page_width: 800., _subs: subs }
    }
}

fn tracks_of(values: &[Value]) -> Vec<lfly_yt::Track> {
    values.iter().filter_map(|v| serde_json::from_value(v.clone()).ok()).collect()
}

impl Render for LibraryPage {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let s = self.look.read(cx).scheme.clone();
        let accent = s.accent.hsla(1.);
        let lib = self.library.read(cx);
        let playlists = lib.playlists.clone();
        let favorites = lib.favorites.clone();
        let history = lib.history.clone();

        // `.p-pill`: 34 px, 13px 600, the glass tint and border; `.accent` in the accent.
        let pill = |id: &'static str, name: &'static str, label: &'static str, primary: bool| {
            div()
                .id(id)
                .h(px(34.))
                .px(px(16.))
                .flex()
                .items_center()
                .gap(px(8.))
                .rounded_full()
                .text_size(px(13.))
                .font_weight(FontWeight::SEMIBOLD)
                .when(primary, |d| d.bg(accent).text_color(s.on_accent.hsla(1.)))
                .when(!primary, |d| d.bg(s.glass_tint).border_1().border_color(s.glass_border).text_color(s.text).hover(|d| d.bg(s.text.opacity(0.14))))
                .cursor_pointer()
                .child(icon(name, 15., if primary { s.on_accent.hsla(1.) } else { s.text }))
                .child(label)
        };

        // Chips.svelte: the filters.
        let me = cx.entity().downgrade();
        let mut chips = vec![(Filter::All, "Tutto"), (Filter::Playlists, "Playlist")];
        if !history.is_empty() {
            chips.push((Filter::Recent, "Recenti"));
        }
        let chip_row = div().flex().gap(px(8.)).children(chips.into_iter().map(|(f, label)| {
            let on = self.filter == f;
            let me = me.clone();
            div()
                .id(label)
                .h(px(32.))
                .px(px(14.))
                .flex()
                .items_center()
                .rounded_full()
                .text_size(px(13.))
                .font_weight(FontWeight::SEMIBOLD)
                .cursor_pointer()
                .when(on, |d| d.bg(s.text).text_color(s.bg.hsla(1.)))
                .when(!on, |d| d.bg(s.glass_tint).border_1().border_color(s.glass_border).hover(|d| d.bg(s.text.opacity(0.14))))
                .on_click(move |_, _, cx| {
                    let _ = me.update(cx, |this, cx| {
                        this.filter = f;
                        cx.notify();
                    });
                })
                .child(label)
        }));

        // `.items`: repeat(auto-fill, minmax(168px, 1fr)), gap 4, margin 0 -10px.
        let avail = self.page_width - 48. + 20.;
        let cols = ((avail + 4.) / 172.).floor().max(1.);
        let item_w = ((avail - 4. * (cols - 1.)) / cols).floor();
        let art = item_w - 20.;
        let item = |id: SharedString, title: SharedString, sub: SharedString, art_el: AnyElement, route: Route, nav: Entity<Nav>| {
            div()
                .id(id)
                .w(px(item_w))
                .p(px(10.))
                .flex()
                .flex_col()
                .gap(px(10.))
                .rounded(px(18.))
                .cursor_pointer()
                .hover(|d| d.bg(s.glass_tint))
                .on_click(move |_, _, cx| nav.update(cx, |n, cx| n.go(route.clone(), cx)))
                .child(art_el)
                .child(
                    div()
                        .flex()
                        .flex_col()
                        .gap(px(2.))
                        .min_w_0()
                        .child(div().text_size(px(14.)).font_weight(FontWeight::SEMIBOLD).truncate().child(title))
                        .child(div().text_size(px(12.)).text_color(s.text2).truncate().child(sub)),
                )
                .into_any_element()
        };
        let shadow = vec![BoxShadow { color: black().opacity(0.3), offset: point(px(0.), px(10.)), blur_radius: px(26.), spread_radius: px(0.), inset: false }];

        let mut items: Vec<AnyElement> = Vec::new();
        if self.filter == Filter::All {
            let liked_art = div()
                .size(px(art))
                .rounded(px(12.))
                .shadow(shadow.clone())
                .bg(linear_gradient(140., linear_color_stop(mix(s.accent, Rgb::hex(0xffffff), 0.2).hsla(1.), 0.), linear_color_stop(mix(s.accent, Rgb::hex(0x3a0ca3), 0.45).hsla(1.), 1.)))
                .flex()
                .items_center()
                .justify_center()
                .child(icon("heart-fill", art * 0.3, white()))
                .into_any_element();
            items.push(item("lib-liked".into(), "Brani che ti piacciono".into(), format!("{} brani", favorites.len()).into(), liked_art, Route::Liked, self.nav.clone()));
        }
        if matches!(self.filter, Filter::All | Filter::Playlists) {
            for p in &playlists {
                let cover = p.cover_url.as_ref().and_then(|u| self.covers.update(cx, |c, cx| c.get(u, cx)));
                let art_el = div()
                    .size(px(art))
                    .rounded(px(12.))
                    .shadow(shadow.clone())
                    .bg(s.text.opacity(0.08))
                    .children(cover.map(|i| img(i).size_full().object_fit(ObjectFit::Cover).rounded(px(12.))))
                    .into_any_element();
                items.push(item(
                    format!("lib-{}", p.id).into(),
                    p.name.clone().into(),
                    format!("Playlist · {} brani", p.count).into(),
                    art_el,
                    Route::Playlist { id: p.id.clone(), name: p.name.clone(), local: true },
                    self.nav.clone(),
                ));
            }
        }
        let mut recent_rows: Vec<AnyElement> = Vec::new();
        if self.filter == Filter::Recent {
            for (i, t) in tracks_of(&history).into_iter().take(60).enumerate() {
                let cover = t.cover_url.as_ref().and_then(|u| self.covers.update(cx, |c, cx| c.get(u, cx)));
                let core = self.core.clone();
                let track = t.clone();
                recent_rows.push(
                    div()
                        .id(("recent", i))
                        .p(px(8.))
                        .flex()
                        .items_center()
                        .gap(px(12.))
                        .rounded(px(12.))
                        .cursor_pointer()
                        .hover(|d| d.bg(s.glass_tint))
                        .on_click(move |_, _, _| core.play_with_radio(track.clone()))
                        .child(div().size(px(42.)).rounded(px(10.)).bg(s.text.opacity(0.08)).children(cover.map(|i| img(i).size_full().object_fit(ObjectFit::Cover).rounded(px(10.)))))
                        .child(
                            div()
                                .min_w_0()
                                .flex()
                                .flex_col()
                                .child(div().text_size(px(14.)).font_weight(FontWeight::MEDIUM).truncate().child(t.title.clone()))
                                .child(div().text_size(px(12.5)).text_color(s.text2).truncate().child(t.artist.clone())),
                        )
                        .into_any_element(),
                );
            }
        }

        div()
            .id("library")
            .size_full()
            .overflow_y_scroll()
            .pt(px(62.))
            .pb(px(104.))
            .child(
                div()
                    .pt(px(20.))
                    .px(px(24.))
                    .pb(px(18.))
                    .flex()
                    .flex_wrap()
                    .items_end()
                    .justify_between()
                    .gap(px(16.))
                    .child(
                        div()
                            .flex()
                            .flex_col()
                            .gap(px(4.))
                            .child(div().text_size(px(((self.page_width + 280.) * 0.036).clamp(30., 44.))).font_weight(FontWeight::EXTRA_BOLD).child("Libreria"))
                            .child(div().text_size(px(13.)).text_color(s.text3).child(format!("{} playlist · {} brani che ti piacciono", playlists.len(), favorites.len()))),
                    )
                    .child(
                        div()
                            .flex()
                            .flex_wrap()
                            .gap(px(8.))
                            .child(pill("import", "upload", "Importa dal telefono", false))
                            .child(pill("spotify", "download", "Importa da Spotify", false))
                            .child(pill("new", "plus", "Nuova playlist", true)),
                    ),
            )
            .child(div().px(px(24.)).pb(px(20.)).child(chip_row))
            .when(self.filter != Filter::Recent, |d| {
                d.child(
                    div()
                        .px(px(24.))
                        .when(self.filter == Filter::All, |d| d.child(div().mb(px(12.)).text_size(px(22.)).font_weight(FontWeight::BOLD).child("Le tue raccolte")))
                        .child(div().mx(px(-10.)).flex().flex_wrap().gap(px(4.)).children(items)),
                )
            })
            .when(self.filter == Filter::Recent, |d| d.child(div().px(px(18.)).children(recent_rows)))
    }
}
