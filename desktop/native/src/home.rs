//! Home (Tauri: pages/Home.svelte, components/Shelf.svelte, components/Card.svelte): the
//! greeting, shortcuts to your playlists, what you played lately and YouTube Music's shelves.

use std::sync::Arc;

use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use lfly_core::pages::{Item, Shelf};
use serde_json::Value;

use crate::core::Core;
use crate::covers::Covers;
use crate::icons::icon;
use crate::library_model::LibraryModel;
use crate::look::Look;
use crate::nav::{Nav, Route};
use crate::player_model::PlayerModel;
use crate::theme::{Rgb, Scheme};

pub struct Home {
    core: Arc<Core>,
    covers: Entity<Covers>,
    look: Entity<Look>,
    nav: Entity<Nav>,
    library: Entity<LibraryModel>,
    model: Entity<PlayerModel>,
    shelves: Option<Result<Vec<Shelf>, String>>,
    /// The window's width, for the clamp()s, and the page's (set by the window before it draws).
    pub width: f32,
    pub page_width: f32,
    _subs: Vec<Subscription>,
}

impl Home {
    pub fn new(
        core: Arc<Core>,
        covers: Entity<Covers>,
        look: Entity<Look>,
        nav: Entity<Nav>,
        library: Entity<LibraryModel>,
        model: Entity<PlayerModel>,
        cx: &mut Context<Self>,
    ) -> Self {
        let subs = vec![
            cx.observe(&covers, |_, _, cx| cx.notify()),
            cx.observe(&look, |_, _, cx| cx.notify()),
            cx.observe(&library, |_, _, cx| cx.notify()),
        ];
        let mut this = Self { core, covers, look, nav, library, model, shelves: None, width: 1100., page_width: 800., _subs: subs };
        this.load(cx);
        this
    }

    fn load(&mut self, cx: &mut Context<Self>) {
        self.shelves = None;
        let core = self.core.clone();
        cx.spawn(async move |this, cx| {
            let r = core.home().await;
            let _ = this.update(cx, |this, cx| {
                this.shelves = Some(r);
                cx.notify();
            });
        })
        .detach();
    }

    fn cover(&self, url: Option<&str>, cx: &mut Context<Self>) -> Option<Arc<Image>> {
        url.and_then(|u| self.covers.update(cx, |c, cx| c.get(u, cx)))
    }
}

/// items.ts `openItem`.
fn open_item(item: &Item, core: &Arc<Core>, nav: &Entity<Nav>, cx: &mut App) {
    match item.kind {
        "song" => {
            if let Some(t) = item.track.clone() {
                core.play_with_radio(t);
            }
        }
        "album" => nav.update(cx, |n, cx| n.go(Route::Album { id: item.id.clone(), name: item.title.clone() }, cx)),
        "playlist" => nav.update(cx, |n, cx| n.go(Route::Playlist { id: item.id.clone(), name: item.title.clone(), local: false }, cx)),
        "artist" => nav.update(cx, |n, cx| n.go(Route::Artist { id: item.id.clone(), name: item.title.clone() }, cx)),
        _ => {}
    }
}

/// items.ts `playItem`.
fn play_item(item: &Item, core: &Arc<Core>, cx: &mut App) {
    let core = core.clone();
    match item.kind {
        "song" => {
            if let Some(t) = item.track.clone() {
                core.play_with_radio(t);
            }
        }
        "album" | "playlist" => {
            let (kind, id) = (item.kind, item.id.clone());
            cx.spawn(async move |_| {
                let tracks = if kind == "album" { core.clone().album_tracks(id).await } else { core.clone().playlist_tracks(id).await };
                if let Ok(t) = tracks {
                    if !t.is_empty() {
                        core.play(&t, 0);
                    }
                }
            })
            .detach();
        }
        _ => {}
    }
}

fn glass_tile_shadow(s: &Scheme) -> Hsla {
    s.glass_border
}

/// Card.svelte: glass, 22 px corners, an 8 px inset cover (15 px corners), title (600 14px, two
/// lines) and subtitle; the accent play button rises on hover.
#[allow(clippy::too_many_arguments)]
fn card(
    id: SharedString,
    item: &Item,
    width: f32,
    cover: Option<Arc<Image>>,
    s: &Scheme,
    core: &Arc<Core>,
    nav: &Entity<Nav>,
) -> impl IntoElement {
    let round = item.kind == "artist";
    let art = width - 16.;
    let group: SharedString = format!("card-{id}").into();
    let (open, play) = (item.clone_item(), item.clone_item());
    let (c1, c2, n1) = (core.clone(), core.clone(), nav.clone());
    let accent = s.accent.hsla(1.);
    div()
        .id(id)
        .group(group.clone())
        .relative()
        .w(px(width))
        .flex_none()
        .flex()
        .flex_col()
        .gap(px(3.))
        .pt(px(8.))
        .px(px(8.))
        .pb(px(12.))
        .rounded(px(22.))
        .cursor_pointer()
        .when(!round, |d| d.bg(s.glass_tint).border_1().border_color(glass_tile_shadow(s)))
        .hover(|d| d.bg(s.text.opacity(0.1)))
        .on_click(move |_, _, cx| open_item(&open, &c1, &n1, cx))
        .child(
            div()
                .relative()
                .mb(px(8.))
                .size(px(art))
                .rounded(px(if round { art / 2. } else { 15. }))
                .overflow_hidden()
                .bg(s.text.opacity(0.08))
                .children(cover.map(|i| img(i).size_full().object_fit(ObjectFit::Cover))),
        )
        .child(
            div()
                .px(px(4.))
                .text_size(px(14.))
                .line_height(px(18.2))
                .font_weight(FontWeight::SEMIBOLD)
                .text_color(s.text)
                .when(round, |d| d.text_center())
                .line_clamp(2)
                .child(item.title.clone()),
        )
        .children(item.subtitle.clone().map(|sub| {
            div()
                .px(px(4.))
                .text_size(px(12.))
                .line_height(px(16.2))
                .text_color(s.text2)
                .when(round, |d| d.text_center())
                .truncate()
                .child(sub)
        }))
        .when(!round, |d| {
            d.child(
                div()
                    .id("play")
                    .absolute()
                    .right(px(8.))
                    .top(px(art - 44.))
                    .size(px(44.))
                    .rounded_full()
                    .flex()
                    .items_center()
                    .justify_center()
                    .pl(px(2.))
                    .bg(accent)
                    .shadow(vec![BoxShadow { color: black().opacity(0.35), offset: point(px(0.), px(8.)), blur_radius: px(20.), spread_radius: px(0.), inset: false }])
                    .invisible()
                    .group_hover(group, |d| d.visible())
                    .on_click(move |_, _, cx| {
                        cx.stop_propagation();
                        play_item(&play, &c2, cx)
                    })
                    .child(icon("play", 18., s.on_accent.hsla(1.))),
            )
        })
}

trait CloneItem {
    fn clone_item(&self) -> Item;
}

impl CloneItem for Item {
    fn clone_item(&self) -> Item {
        Item {
            kind: self.kind,
            id: self.id.clone(),
            title: self.title.clone(),
            subtitle: self.subtitle.clone(),
            cover_url: self.cover_url.clone(),
            track: self.track.clone(),
        }
    }
}

/// Shelf.svelte's song tile (the phone's HomeShelf.kt): glass, 20 px corners, a 56 px cover.
fn song_tile(id: SharedString, item: &Item, width: f32, cover: Option<Arc<Image>>, current: bool, s: &Scheme, core: &Arc<Core>) -> impl IntoElement {
    let group: SharedString = format!("tile-{id}").into();
    let open = item.clone_item();
    let core = core.clone();
    let accent = s.accent.hsla(1.);
    div()
        .id(id)
        .group(group.clone())
        .relative()
        .w(px(width))
        .flex_none()
        .flex()
        .items_center()
        .gap(px(12.))
        .p(px(8.))
        .rounded(px(20.))
        .bg(s.glass_tint)
        .border_1()
        .border_color(if current { accent } else { s.glass_border })
        .cursor_pointer()
        .hover(|d| d.bg(s.text.opacity(0.1)))
        .on_click(move |_, _, _| {
            if let Some(t) = open.track.clone() {
                core.play_with_radio(t);
            }
        })
        .child(
            div()
                .relative()
                .size(px(56.))
                .flex_none()
                .rounded(px(14.))
                .overflow_hidden()
                .bg(s.text.opacity(0.08))
                .children(cover.map(|i| img(i).size_full().object_fit(ObjectFit::Cover)))
                .child(
                    div()
                        .absolute()
                        .inset_0()
                        .flex()
                        .items_center()
                        .justify_center()
                        .bg(black().opacity(0.42))
                        .invisible()
                        .group_hover(group, |d| d.visible())
                        .child(icon("play", 16., white())),
                ),
        )
        .child(
            div()
                .min_w_0()
                .flex()
                .flex_col()
                .gap(px(2.))
                .child(div().text_size(px(14.)).font_weight(FontWeight::SEMIBOLD).text_color(if current { accent } else { s.text }).truncate().child(item.title.clone()))
                .children(item.subtitle.clone().map(|sub| div().text_size(px(12.5)).text_color(s.text2).truncate().child(sub))),
        )
}

fn shelf_header(title: SharedString, s: &Scheme) -> impl IntoElement {
    div()
        .px(px(24.))
        .mb(px(12.))
        .text_size(px(22.))
        .line_height(px(26.4))
        .font_weight(FontWeight::BOLD)
        .text_color(s.text)
        .child(title)
}

impl Render for Home {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let s = self.look.read(cx).scheme.clone();
        let w = self.width;
        let now = chrono_like_now();
        let greeting = match now.hour {
            0..=4 => "Buonanotte",
            5..=12 => "Buongiorno",
            13..=17 => "Buon pomeriggio",
            _ => "Buonasera",
        };
        let lib = self.library.read(cx);
        let quick: Vec<_> = lib.playlists.iter().take(8).cloned().collect();
        let recent: Vec<Item> = lib.history.iter().take(12).filter_map(history_item).collect();
        let current = self.model.read(cx).current().map(|t| t.id.clone());
        let card_w = (w * 0.15).clamp(150., 196.);
        // `.quick`: grid-template-columns: repeat(auto-fill, minmax(230px, 1fr)), gap 10.
        let avail = (self.page_width - 48.).max(230.);
        let cols = ((avail + 10.) / 240.).floor().max(1.);
        let quick_w = (avail - 10. * (cols - 1.)) / cols;
        let tile_w = (w * 0.26).clamp(250., 320.);
        let core = self.core.clone();
        let nav = self.nav.clone();

        // `.q`: a 56 px cover flush left, the title and count, the accent play on hover.
        let quick_tiles: Vec<AnyElement> = quick
            .iter()
            .map(|p| {
                let cover = self.cover(p.cover_url.as_deref(), cx);
                let nav = nav.clone();
                let route = Route::Playlist { id: p.id.clone(), name: p.name.clone(), local: true };
                let group: SharedString = format!("q-{}", p.id).into();
                div()
                    .id(SharedString::from(format!("q-{}", p.id)))
                    .group(group.clone())
                    .relative()
                    .w(px(quick_w))
                    .flex_none()
                    .h(px(56.))
                    .flex()
                    .items_center()
                    .gap(px(12.))
                    .rounded(px(14.))
                    .overflow_hidden()
                    .bg(s.surface.hsla(0.62))
                    .border_1()
                    .border_color(if s.light { Rgb::hex(0x15151a).hsla(0.1) } else { Rgb::hex(0xffffff).hsla(0.1) })
                    .cursor_pointer()
                    .hover(|d| d.bg(s.surface2.hsla(0.85)))
                    .on_click(move |_, _, cx| nav.update(cx, |n, cx| n.go(route.clone(), cx)))
                    .child(div().size(px(56.)).flex_none().bg(s.text.opacity(0.08)).children(cover.map(|i| img(i).size_full().object_fit(ObjectFit::Cover))))
                    .child(
                        div()
                            .min_w_0()
                            .pr(px(48.))
                            .flex()
                            .flex_col()
                            .child(div().text_size(px(13.5)).font_weight(FontWeight::SEMIBOLD).truncate().child(p.name.clone()))
                            .child(div().text_size(px(11.5)).text_color(s.text3).child(format!("{} brani", p.count))),
                    )
                    .into_any_element()
            })
            .collect();

        let rail = |id: SharedString, children: Vec<AnyElement>, two_rows: bool| {
            div()
                .id(id)
                .overflow_x_scroll()
                .px(px(24.))
                .pt(px(6.))
                .pb(px(14.))
                .child(
                    div()
                        .flex()
                        .when(two_rows, |d| d.flex_col().flex_wrap().h(px(2. * 74. + 10.)).gap_y(px(10.)).gap_x(px(12.)))
                        .when(!two_rows, |d| d.gap(px(14.)))
                        .children(children),
                )
        };

        let mut sections: Vec<AnyElement> = Vec::new();
        if !recent.is_empty() {
            let tiles = recent
                .iter()
                .enumerate()
                .map(|(i, it)| {
                    let cover = self.cover(it.cover_url.as_deref(), cx);
                    song_tile(format!("recent-{i}").into(), it, tile_w, cover, current.as_deref() == Some(it.id.as_str()), &s, &core).into_any_element()
                })
                .collect();
            sections.push(
                div()
                    .mb(px(36.))
                    .child(shelf_header("Ascoltati di recente".into(), &s))
                    .child(rail("recent".into(), tiles, true))
                    .into_any_element(),
            );
        }
        match &self.shelves {
            None => sections.push(div().px(px(24.)).text_color(s.text3).child("Caricamento…").into_any_element()),
            Some(Err(e)) => sections.push(
                div()
                    .px(px(24.))
                    .flex()
                    .flex_col()
                    .gap(px(8.))
                    .child(div().font_weight(FontWeight::BOLD).child("Home non disponibile"))
                    .child(div().text_color(s.text2).child(e.clone()))
                    .into_any_element(),
            ),
            Some(Ok(shelves)) => {
                let shelves: Vec<(String, Vec<Item>)> = shelves.iter().map(|sh| (sh.title.clone(), sh.items.iter().map(|i| i.clone_item()).collect())).collect();
                for (si, (title, items)) in shelves.into_iter().enumerate() {
                    let songs = !items.is_empty() && items.iter().all(|i| i.kind == "song");
                    let children = items
                        .iter()
                        .enumerate()
                        .map(|(i, it)| {
                            let cover = self.cover(it.cover_url.as_deref(), cx);
                            let id: SharedString = format!("s{si}-{i}").into();
                            if songs {
                                song_tile(id, it, tile_w, cover, current.as_deref() == Some(it.id.as_str()), &s, &core).into_any_element()
                            } else {
                                card(id, it, card_w, cover, &s, &core, &nav).into_any_element()
                            }
                        })
                        .collect();
                    sections.push(
                        div()
                            .mb(px(36.))
                            .child(shelf_header(title.into(), &s))
                            .child(rail(format!("shelf-{si}").into(), children, songs))
                            .into_any_element(),
                    );
                }
            }
        }

        div()
            .id("home")
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
                    .flex_col()
                    .gap(px(2.))
                    .child(div().text_size(px(12.)).font_weight(FontWeight::BOLD).text_color(s.text3).child(now.date.to_uppercase()))
                    .child(div().text_size(px((w * 0.036).clamp(30., 44.))).font_weight(FontWeight::EXTRA_BOLD).line_height(relative(1.05)).child(greeting)),
            )
            .when(!quick_tiles.is_empty(), |d| {
                d.child(div().px(px(24.)).mb(px(36.)).flex().flex_wrap().gap(px(10.)).children(quick_tiles))
            })
            .children(sections)
    }
}

/// A history entry (the phone's Track JSON) as a song card.
fn history_item(t: &Value) -> Option<Item> {
    let track: lfly_yt::Track = serde_json::from_value(t.clone()).ok()?;
    Some(Item {
        kind: "song",
        id: track.id.clone(),
        title: track.title.clone(),
        subtitle: Some(track.artist.clone()),
        cover_url: track.cover_url.clone(),
        track: Some(track),
    })
}

struct Now {
    hour: u32,
    date: String,
}

/// The local hour and the date in Italian ("mercoledì 12 novembre"), as Home.svelte's
/// toLocaleDateString("it-IT", { weekday: "long", day: "numeric", month: "long" }).
fn chrono_like_now() -> Now {
    use chrono::{Datelike, Local, Timelike};
    const MONTHS: [&str; 12] = ["gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno", "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre"];
    const DAYS: [&str; 7] = ["lunedì", "martedì", "mercoledì", "giovedì", "venerdì", "sabato", "domenica"];
    let now = Local::now();
    let weekday = DAYS[now.weekday().num_days_from_monday() as usize];
    Now { hour: now.hour(), date: format!("{weekday} {} {}", now.day(), MONTHS[now.month0() as usize]) }
}
