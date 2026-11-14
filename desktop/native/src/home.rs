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
            // The playing song is marked in the rails (cached view: it must hear of it).
            cx.subscribe(&model, |_, _, _: &crate::player_model::PlayerEvent, cx| cx.notify()),
            cx.observe(&look, |_, _, cx| cx.notify()),
            cx.observe(&library, |_, _, cx| cx.notify()),
        ];
        let mut subs = subs;
        // The DJ card follows the DJ (cached view).
        if let Some(dj) = crate::dj::DjModel::entity(cx) {
            subs.push(cx.observe(&dj, |_, _, cx| cx.notify()));
        }
        let mut this = Self { core, covers, look, nav, library, model, shelves: None, width: 1100., page_width: 800., _subs: subs };
        this.load(cx);
        // LFLY_SHELF_NEXT=<shelf id>@<seconds> (tests): turns that shelf's page, as its › does.
        if let Some((id, secs)) = std::env::var("LFLY_SHELF_NEXT").ok().and_then(|v| v.split_once('@').map(|(a, b)| (a.to_string(), b.parse::<f64>().ok())))
            .and_then(|(a, b)| Some((a, b?)))
        {
            let view = cx.entity_id();
            cx.spawn(async move |_, cx| {
                cx.background_executor().timer(std::time::Duration::from_secs_f64(secs)).await;
                // "all": every shelf.
                let ids: Vec<SharedString> = if id == "all" { RAILS.with(|r| r.borrow().keys().cloned().collect()) } else { vec![id.into()] };
                for id in ids {
                    let to = with_rail(&id, |r| r.fwd);
                    cx.update(|cx| slide(id, to, view, cx));
                }
            })
            .detach();
        }
        this
    }

    fn load(&mut self, cx: &mut Context<Self>) {
        self.shelves = None;
        reset_rails("shelf-");
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
pub fn open_item(item: &Item, core: &Arc<Core>, nav: &Entity<Nav>, cx: &mut App) {
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
pub fn play_item(item: &Item, core: &Arc<Core>, cx: &mut App) {
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
pub fn card(
    id: SharedString,
    item: &Item,
    width: f32,
    cover: Option<Arc<Image>>,
    s: &Scheme,
    core: &Arc<Core>,
    nav: &Entity<Nav>,
) -> Stateful<Div> {
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
        .hover(|d| d.bg(s.text.opacity(0.1))).active(|d| d.bg(s.text.opacity(0.18)))
        .on_click(move |_, _, cx| open_item(&open, &c1, &n1, cx))
        .child(
            div()
                .relative()
                .mb(px(8.))
                .size(px(art))
                .rounded(px(if round { art / 2. } else { 15. }))
                .overflow_hidden()
                .bg(s.text.opacity(0.08))
                .children(cover.map(|i| crate::covers::fade(i.id(), img(i).size_full().object_fit(ObjectFit::Cover).rounded(px(if round { art / 2. } else { 15. }))))),
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
                .text_ellipsis()
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
                    .bg(accent).active(|d| d.opacity(0.85))
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

pub trait CloneItem {
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
pub fn song_tile(id: SharedString, item: &Item, width: f32, cover: Option<Arc<Image>>, current: bool, s: &Scheme, core: &Arc<Core>) -> impl IntoElement {
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
        .hover(|d| d.bg(s.text.opacity(0.1))).active(|d| d.bg(s.text.opacity(0.18)))
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
                .children(cover.map(|i| crate::covers::fade(i.id(), img(i).size_full().object_fit(ObjectFit::Cover).rounded(px(14.)))))
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

/// How many items at least [min] px wide fit in [avail] with [gap] between them, and the width
/// that makes exactly that many fill the row (no item cut by the edge).
pub fn fit(avail: f32, min: f32, gap: f32) -> (usize, f32) {
    let cols = ((avail + gap) / (min + gap)).floor().max(1.);
    (cols as usize, ((avail - gap * (cols - 1.)) / cols).floor())
}

/// The smallest card and song tile; [fit] makes them wider to fill the row.
pub const CARD_MIN: f32 = 170.;
pub const CARD_GAP: f32 = 14.;
pub const TILE_MIN: f32 = 250.;
pub const TILE_GAP: f32 = 12.;

/// A shelf's scroll position and what moves it.
struct Rail {
    handle: ScrollHandle,
    /// Bumped by every slide (and reset): a running slide that sees it change stops.
    turn: u64,
    /// Where › goes from here (the test hook presses it).
    fwd: f32,
    /// The item pitch it was drawn with: when the width changes, the position follows in items.
    unit: f32,
    /// Where the running slide is going: a second press pages on from there.
    target: Option<f32>,
}

thread_local! {
    /// Each shelf's scroll position and the paging animation running on it (by its id).
    static RAILS: std::cell::RefCell<std::collections::HashMap<SharedString, Rail>> = Default::default();
}

fn with_rail<R>(id: &SharedString, f: impl FnOnce(&mut Rail) -> R) -> R {
    RAILS.with(|r| f(r.borrow_mut().entry(id.clone()).or_insert_with(|| Rail { handle: ScrollHandle::new(), turn: 0, fwd: 0., unit: 0., target: None })))
}

/// Back to the first page, for the shelves whose ids start with [prefix] (their content changed:
/// another artist, another search, Home loaded again).
pub fn reset_rails(prefix: &str) {
    RAILS.with(|r| {
        for (id, rail) in r.borrow_mut().iter_mut() {
            if id.starts_with(prefix) {
                rail.handle.set_offset(point(px(0.), px(0.)));
                rail.turn += 1;
                rail.target = None;
            }
        }
    });
}

/// Slides the shelf [id] to [to] (a negative x offset) in 360 ms, easing out, redrawing [view].
fn slide(id: SharedString, to: f32, view: EntityId, cx: &mut App) {
    let (handle, me) = with_rail(&id, |r| {
        r.turn += 1;
        r.target = Some(to);
        (r.handle.clone(), r.turn)
    });
    let from = f32::from(handle.offset().x);
    let done = move |id: &SharedString| with_rail(id, |r| r.target = None);
    if crate::prefs::Prefs::reduce_motion(cx) {
        handle.set_offset(point(px(to), px(0.)));
        done(&id);
        cx.notify(view);
        return;
    }
    let start = std::time::Instant::now();
    cx.spawn(async move |cx| loop {
        cx.background_executor().timer(std::time::Duration::from_millis(8)).await;
        if with_rail(&id, |r| r.turn) != me {
            return; // Another press, or new content, took over.
        }
        let p = (start.elapsed().as_secs_f32() / 0.36).min(1.);
        let e = 1. - (1. - p).powi(3);
        handle.set_offset(point(px(from + (to - from) * e), px(0.)));
        if p >= 1. {
            done(&id);
        }
        cx.update(|cx| cx.notify(view));
        if p >= 1. {
            return;
        }
    })
    .detach();
}

/// A shelf (Shelf.svelte): the title, ‹ › that turn its pages when it holds more than one, and a
/// row of items [item_w] wide, [cols] to a page (or two rows of song tiles). The row is exactly a
/// page wide and clipped there, so what shows is whole items.
#[allow(clippy::too_many_arguments)]
pub fn shelf(id: SharedString, title: SharedString, children: Vec<AnyElement>, two_rows: bool, cols: usize, item_w: f32, view: EntityId, s: &Scheme) -> AnyElement {
    let gap = if two_rows { TILE_GAP } else { CARD_GAP };
    let n = children.len();
    let columns = if two_rows { n.div_ceil(2) } else { n };
    let unit = item_w + gap;
    let step = cols as f32 * unit;
    let max = columns.saturating_sub(cols) as f32 * unit;
    let paged = columns > cols;
    // A page from wherever it rests (snapped to a whole item, in case it was scrolled by hand),
    // or from where the running slide is going.
    let snap = |x: f32| ((x / unit).round() * unit).clamp(0., max);
    let (handle, from) = with_rail(&id, |r| {
        let mut at = -f32::from(r.handle.offset().x);
        if r.unit > 0. && (r.unit - unit).abs() > 0.5 {
            // Wider or narrower now: the same first item stays first.
            at = ((at / r.unit).round() * unit).min(max);
            r.handle.set_offset(point(px(-at), px(0.)));
            r.target = None;
            r.turn += 1;
        }
        r.unit = unit;
        let from = r.target.map_or(at, |t| -t);
        (r.handle.clone(), from)
    });
    let back = -snap((from - step).max(0.));
    let fwd = -snap((from + step).min(max));
    with_rail(&id, |r| r.fwd = fwd);
    let arrow = |dir: &'static str, enabled: bool, to: f32| {
        let id = id.clone();
        div()
            .id(SharedString::from(format!("{id}-{dir}")))
            .size(px(30.))
            .flex_none()
            .rounded_full()
            .flex()
            .items_center()
            .justify_center()
            .bg(s.text.opacity(0.08))
            .when(!enabled, |d| d.opacity(0.35))
            .when(enabled, |d| {
                d.cursor_pointer().hover(|d| d.bg(s.text.opacity(0.14))).active(|d| d.bg(s.text.opacity(0.22))).on_click(move |_, _, cx| slide(id.clone(), to, view, cx))
            })
            .child(icon(dir, 14., s.text))
    };
    let half = unit * 0.5;
    div()
        .mb(px(36.))
        .child(
            div()
                .px(px(24.))
                .mb(px(12.))
                .flex()
                .items_center()
                .gap(px(8.))
                .child(div().flex_1().min_w_0().truncate().text_size(px(22.)).line_height(px(26.4)).font_weight(FontWeight::BOLD).text_color(s.text).child(title))
                .when(paged, |d| d.child(arrow("back", from > half, back)).child(arrow("forward", from < max - half, fwd))),
        )
        .child(
            div()
                .id(id.clone())
                .ml(px(24.))
                .w(px(step - gap))
                .overflow_x_scroll()
                // Only a sideways gesture (a touchpad, Shift + wheel) moves it: GPUI otherwise
                // turns the wheel's vertical turn into a sideways one, so scrolling the page with
                // the pointer over a shelf swiped the shelf.
                .restrict_scroll_to_axis()
                .track_scroll(&handle)
                .pt(px(6.))
                .pb(px(14.))
                .child(
                    // Its width told: the scroller measures what it can scroll from it (a block
                    // child would just take the scroller's width, a column-wrapped one too).
                    div()
                        .flex()
                        .flex_none()
                        .w(px(columns as f32 * unit - gap))
                        .when(two_rows, |d| d.flex_col().flex_wrap().h(px(2. * 74. + 10.)).gap_y(px(10.)).gap_x(px(gap)))
                        .when(!two_rows, |d| d.gap(px(gap)))
                        .children(children),
                ),
        )
        .into_any_element()
}

/// A titled shelf of cards filling [avail] px (Shelf.svelte), nothing when empty.
#[allow(clippy::too_many_arguments)]
pub fn card_shelf(
    key: &str,
    title: &str,
    items: &[Item],
    avail: f32,
    covers: &Entity<Covers>,
    s: &Scheme,
    core: &Arc<Core>,
    nav: &Entity<Nav>,
    // Called with the card opened (Search remembers it among the recents).
    on_open: Option<std::rc::Rc<dyn Fn(&Item, &mut App)>>,
    view: EntityId,
    cx: &mut App,
) -> Option<AnyElement> {
    if items.is_empty() {
        return None;
    }
    let (cols, card_w) = fit(avail, CARD_MIN, CARD_GAP);
    let children = items
        .iter()
        .enumerate()
        .map(|(i, it)| {
            let cover = it.cover_url.as_deref().and_then(|u| covers.update(cx, |c, cx| c.get(u, cx)));
            let c = card(format!("{key}-{i}").into(), it, card_w, cover, s, core, nav);
            match on_open.clone() {
                Some(f) => {
                    let it = it.clone_item();
                    c.on_click(move |_, _, cx| f(&it, cx)).into_any_element()
                }
                None => c.into_any_element(),
            }
        })
        .collect();
    Some(shelf(key.to_string().into(), title.to_string().into(), children, false, cols, card_w, view, s))
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
        let view = cx.entity_id();
        let row_w = (self.page_width - 48.).max(TILE_MIN);
        let (card_cols, card_w) = fit(row_w, CARD_MIN, CARD_GAP);
        let (tile_cols, tile_w) = fit(row_w, TILE_MIN, TILE_GAP);
        // `.quick`: grid-template-columns: repeat(auto-fill, minmax(230px, 1fr)), gap 10.
        let avail = (self.page_width - 48.).max(230.);
        let cols = ((avail + 10.) / 240.).floor().max(1.);
        // Floored: three 252.67 px tiles and their gaps must not add up past the row.
        let quick_w = ((avail - 10. * (cols - 1.)) / cols).floor();
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
                    .hover(|d| d.bg(s.surface2.hsla(0.85))).active(|d| d.bg(s.surface2.hsla(1.)))
                    .on_click(move |_, _, cx| nav.update(cx, |n, cx| n.go(route.clone(), cx)))
                    .child(div().size(px(56.)).flex_none().bg(s.text.opacity(0.08)).children(cover.map(|i| crate::covers::fade(i.id(), img(i).size_full().object_fit(ObjectFit::Cover).rounded_l(px(14.))))))
                    .child(
                        div()
                            .min_w_0()
                            .pr(px(48.))
                            .flex()
                            .flex_col()
                            .child(div().text_size(px(13.5)).font_weight(FontWeight::SEMIBOLD).truncate().child(p.name.clone()))
                            .child(div().text_size(px(11.5)).text_color(s.text3).child(crate::ui::brani(p.count as u64))),
                    )
                    .into_any_element()
            })
            .collect();


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
            sections.push(shelf("recent".into(), "Ascoltati di recente".into(), tiles, true, tile_cols, tile_w, view, &s));
        }
        match &self.shelves {
            None => sections.push(crate::skeleton::shelves(card_w, &s, cx)),
            Some(Err(e)) => sections.push(
                div()
                    .px(px(24.))
                    .flex()
                    .flex_col()
                    .gap(px(8.))
                    .child(div().font_weight(FontWeight::BOLD).child("Home non disponibile"))
                    .child(div().text_color(s.text2).child(e.clone()))
                    .child(
                        div()
                            .id("home-retry")
                            .mt(px(4.))
                            .h(px(34.))
                            .px(px(16.))
                            .flex()
                            .items_center()
                            .self_start()
                            .rounded_full()
                            .text_size(px(13.))
                            .font_weight(FontWeight::SEMIBOLD)
                            .bg(s.glass_tint)
                            .border_1()
                            .border_color(s.glass_border)
                            .cursor_pointer()
                            .on_click({
                                let me = cx.entity().downgrade();
                                move |_, _, cx| {
                                    let _ = me.update(cx, |this, cx| this.load(cx));
                                }
                            })
                            .child("Riprova"),
                    )
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
                    let (cols, item_w) = if songs { (tile_cols, tile_w) } else { (card_cols, card_w) };
                    sections.push(shelf(format!("shelf-{si}").into(), title.into(), children, songs, cols, item_w, view, &s));
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
            .children(self.dj_card(&s, cx))
            .when(!quick_tiles.is_empty(), |d| {
                d.child(div().px(px(24.)).mb(px(36.)).flex().flex_wrap().gap(px(10.)).children(quick_tiles))
            })
            .children(sections)
    }
}

impl Home {
    /// The phone's HomeDj card: what the DJ does, and the button that starts or stops it.
    fn dj_card(&self, s: &crate::theme::Scheme, cx: &mut Context<Self>) -> Option<Div> {
        let dj = crate::dj::DjModel::entity(cx)?;
        let state = dj.read(cx).state.clone();
        // It plays on this computer: not while another device is controlled.
        let remote = self.model.read(cx).remote.is_some();
        let accent = s.accent.hsla(1.);
        let subtitle = if state.loading {
            "Il DJ sta preparando la selezione…".to_string()
        } else if let Some(b) = state.block.as_ref().filter(|_| state.active) {
            crate::dj::block_label(b)
        } else {
            "La tua musica, scelta e presentata per te".to_string()
        };
        let label = if state.active { "Ferma il DJ" } else { "Avvia il DJ" };
        let button = div()
            .id("dj-toggle")
            .h(px(36.))
            .px(px(18.))
            .flex()
            .flex_none()
            .items_center()
            .gap(px(8.))
            .rounded_full()
            .text_size(px(13.))
            .font_weight(FontWeight::SEMIBOLD)
            .when(!state.active, |d| d.bg(accent).text_color(s.on_accent.hsla(1.)))
            .when(state.active, |d| d.bg(s.glass_tint).border_1().border_color(s.glass_border).text_color(s.text))
            .when(remote || state.loading, |d| d.opacity(0.45))
            .when(!remote && !state.loading, |d| {
                d.cursor_pointer().active(|d| d.opacity(0.85)).on_click(move |_, _, cx| dj.read(cx).toggle())
            })
            .child(crate::icons::icon(if state.active { "pause" } else { "play" }, 14., if state.active { s.text } else { s.on_accent.hsla(1.) }))
            .child(label);
        Some(
            div().px(px(24.)).mb(px(28.)).child(
                div()
                    .flex()
                    .items_center()
                    .gap(px(14.))
                    .p(px(14.))
                    .rounded(px(16.))
                    .bg(s.glass_tint)
                    .border_1()
                    .border_color(s.glass_border)
                    .child(
                        div()
                            .size(px(44.))
                            .flex_none()
                            .rounded_full()
                            .flex()
                            .items_center()
                            .justify_center()
                            .bg(accent.opacity(0.18))
                            .child(crate::icons::icon("sparkles", 20., accent)),
                    )
                    .child(
                        div()
                            .flex_1()
                            .min_w_0()
                            .flex()
                            .flex_col()
                            .gap(px(2.))
                            .child(div().text_size(px(16.)).font_weight(FontWeight::BOLD).text_color(s.text).child("DJ"))
                            .child(dj_subtitle(subtitle, state.loading, s)),
                    )
                    .child(button),
            ),
        )
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

/// The DJ card's line: a new one fades in; "preparing" breathes until the first block is in
/// (both still with reduced motion, as GPUI's animations are).
fn dj_subtitle(text: String, loading: bool, s: &crate::theme::Scheme) -> AnyElement {
    let key = text.bytes().fold(0u64, |h, b| h.wrapping_mul(31).wrapping_add(b as u64));
    let line = div().text_size(px(13.)).text_color(s.text2).text_ellipsis().child(text);
    if loading {
        return line
            .with_animation("dj-card-loading", Animation::new(std::time::Duration::from_millis(1400)).repeat().with_max_fps(30.).with_easing(pulsating_between(0.45, 1.)), |d, t| d.opacity(t))
            .into_any_element();
    }
    line.with_animation(("dj-card-line", key), Animation::new(std::time::Duration::from_millis(260)).with_easing(ease_out_quint()), |d, t| d.opacity(t)).into_any_element()
}
