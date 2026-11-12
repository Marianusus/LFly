//! LFly for computer, drawn by GPUI: no webview, the GPU draws only when something changed.
//!
//! The rewrite of the Tauri window, a screen at a time. Logic lives in lfly-core (shared with
//! the Tauri app); this crate only draws. Test modes: `LFLY_AUTOPLAY="query"` plays the first
//! result, `LFLY_MUTE=1`, and the glass comparison flags in player_bar.rs.

mod ambient;
mod art;
mod artist;
mod core;
mod collection;
mod covers;
mod glass;
mod home;
mod icons;
mod library_model;
mod library_page;
mod look;
mod lyrics;
mod lyrics_page;
mod motion;
mod nav;
mod now_playing;
mod player_bar;
mod player_model;
mod queue_list;
mod right_panel;
mod scene;
mod search;
mod sidebar;
mod slider;
mod theme;
mod track_row;
mod ui;

use std::sync::Arc;

use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use lfly_connect::protocol;

use crate::core::Core;
use crate::artist::Artist;
use crate::collection::{Collection, Source};
use crate::covers::Covers;
use crate::home::Home;
use crate::icons::icon;
use crate::library_model::LibraryModel;
use crate::library_page::LibraryPage;
use crate::look::Look;
use crate::lyrics::{Lyrics, LyricsData, Variant};
use crate::lyrics_page::LyricsPage;
use crate::nav::{Nav, Route};
use crate::now_playing::NowPlaying;
use crate::player_bar::PlayerBar;
use crate::player_model::{PlayerEvent, PlayerModel};
use crate::right_panel::RightPanel;
use crate::search::Search;
use crate::sidebar::Sidebar;
use crate::ui::Ui;
use crate::motion::Tween;

/// Inter, as the Tauri window bundles it (app.css "Inter LFly"), cut into its five weights:
/// GPUI's text system does not pick weights out of a variable font.
const FONTS: [&[u8]; 5] = [
    include_bytes!("../assets/fonts/InterLFly-Regular.ttf"),
    include_bytes!("../assets/fonts/InterLFly-Medium.ttf"),
    include_bytes!("../assets/fonts/InterLFly-SemiBold.ttf"),
    include_bytes!("../assets/fonts/InterLFly-Bold.ttf"),
    include_bytes!("../assets/fonts/InterLFly-ExtraBold.ttf"),
];

/// app.css `--font-system`: the system's own face on Windows (Segoe UI Variable), Inter elsewhere.
fn ui_font(cx: &App) -> SharedString {
    if cfg!(windows) && cx.text_system().all_font_names().iter().any(|f| f == "Segoe UI Variable Text") {
        "Segoe UI Variable Text".into()
    } else {
        "Inter LFly".into()
    }
}

/// The part of the window the view gets: the viewport less the frame gpui-kit draws around it
/// (its shadow and 1 px border) when the window is not tiled or maximized.
fn content_size(window: &Window) -> Size<Pixels> {
    let mut s = window.viewport_size();
    let pad = gpui_kit::component::window_paddings(window);
    s.width -= pad.left + pad.right;
    s.height -= pad.top + pad.bottom;
    if let Decorations::Client { tiling } = window.window_decorations() {
        let b = |tiled: bool| if tiled { px(0.) } else { px(1.) };
        s.width -= b(tiling.left) + b(tiling.right);
        s.height -= b(tiling.top) + b(tiling.bottom);
    }
    s
}

struct Lfly {
    font: SharedString,
    core: Arc<Core>,
    model: Entity<PlayerModel>,
    look: Entity<Look>,
    nav: Entity<Nav>,
    bar: Entity<PlayerBar>,
    sidebar: Entity<Sidebar>,
    home: Entity<Home>,
    collection: Entity<Collection>,
    artist: Entity<Artist>,
    search: Entity<Search>,
    library_page: Entity<LibraryPage>,
    now_playing: Entity<NowPlaying>,
    lyrics_page: Entity<LyricsPage>,
    ui: Entity<Ui>,
    right_panel: Entity<RightPanel>,
    /// The sidebar's width (232..280, or 88 folded) and the panel's column, easing between
    /// (App.svelte: grid-template-columns 460 ms).
    side_w: Option<Tween>,
    panel_w: Option<Tween>,
    /// The panel sliding in (true) or out, since when (`fly` x 24, 260 ms).
    panel_motion: Option<(std::time::Instant, bool)>,
    panel_shown: bool,
    /// The full player sliding in (true) or out, since when (NowPlaying.svelte `fly`, 420 ms).
    np_motion: Option<(std::time::Instant, bool)>,
    np_shown: bool,
    /// The song whose cover colours the look.
    colors_for: Option<String>,
    _subs: Vec<Subscription>,
}

impl Lfly {
    fn new(core: Arc<Core>, model: Entity<PlayerModel>, window: &mut Window, cx: &mut Context<Self>) -> Self {
        let covers = cx.new(|_| Covers::new(core.clone()));
        let look = cx.new(|_| Look::new());
        let nav = cx.new(|_| Nav::new());
        let ui = cx.new(|_| Ui::new());
        let bar = cx.new(|cx| PlayerBar::new(model.clone(), nav.clone(), ui.clone(), covers.clone(), look.clone(), cx));
        let library = cx.new(LibraryModel::new);
        let sidebar = cx.new(|cx| Sidebar::new(nav.clone(), ui.clone(), library.clone(), look.clone(), covers.clone(), cx));
        let home = cx.new(|cx| Home::new(core.clone(), covers.clone(), look.clone(), nav.clone(), library.clone(), model.clone(), cx));
        let collection = cx.new(|cx| Collection::new(core.clone(), covers.clone(), look.clone(), nav.clone(), library.clone(), model.clone(), cx));
        let artist = cx.new(|cx| Artist::new(core.clone(), covers.clone(), look.clone(), nav.clone(), library.clone(), model.clone(), cx));
        let search = cx.new(|cx| Search::new(core.clone(), covers.clone(), look.clone(), nav.clone(), library.clone(), model.clone(), window, cx));
        let library_page = cx.new(|cx| LibraryPage::new(core.clone(), covers.clone(), look.clone(), nav.clone(), library.clone(), cx));
        // One fetch per song, three views of it: the full player, the page, the panel.
        let lyrics_data = cx.new(|_| LyricsData::new(core.clone()));
        let lyrics_np = cx.new(|cx| Lyrics::new(lyrics_data.clone(), model.clone(), look.clone(), None, Variant::Panel, cx));
        let lyrics_pg = cx.new(|cx| Lyrics::new(lyrics_data.clone(), model.clone(), look.clone(), None, Variant::Page, cx));
        let lyrics_side = cx.new(|cx| Lyrics::new(lyrics_data, model.clone(), look.clone(), Some(nav.clone()), Variant::Side, cx));
        let now_playing = cx.new(|cx| NowPlaying::new(core.clone(), model.clone(), covers.clone(), look.clone(), nav.clone(), library, lyrics_np, cx));
        let lyrics_page = cx.new(|cx| LyricsPage::new(core.clone(), model.clone(), covers.clone(), look.clone(), nav.clone(), lyrics_pg, cx));
        let right_panel = cx.new(|cx| RightPanel::new(ui.clone(), model.clone(), covers.clone(), look.clone(), core.clone(), lyrics_side, cx));
        let subs = vec![
            cx.observe_window_activation(window, |this, window, cx| {
                let active = window.is_window_active();
                log::debug!("window active: {active}");
                this.model.update(cx, |m, cx| {
                    m.active = active;
                    cx.notify();
                });
            }),
            // Only real changes reach this view: the position ticks stay in the bar.
            cx.subscribe(&model, |this, _, ev: &PlayerEvent, cx| match ev {
                PlayerEvent::StateChanged => {
                    this.refresh_colors(cx);
                    cx.notify();
                }
                PlayerEvent::Notice(text) => log::info!("{text}"),
            }),
            cx.observe(&covers, |_, _, cx| cx.notify()),
            cx.observe(&look, |_, _, cx| cx.notify()),
            cx.observe(&nav, |_, _, cx| cx.notify()),
            cx.observe(&ui, |_, _, cx| cx.notify()),
        ];
        // Esc closes the full player.
        let esc_nav = nav.clone();
        cx.observe_keystrokes(move |_, e, _, cx| {
            if e.keystroke.key == "escape" && esc_nav.read(cx).route == Route::NowPlaying {
                NowPlaying::close(&esc_nav, cx);
            }
        })
        .detach();
        let this = Self {
            font: ui_font(cx),
            core,
            model,
            look,
            nav,
            bar,
            sidebar,
            home,
            collection,
            artist,
            search,
            library_page,
            now_playing,
            lyrics_page,
            ui,
            right_panel,
            side_w: None,
            panel_w: None,
            panel_motion: None,
            panel_shown: false,
            np_motion: None,
            np_shown: false,
            colors_for: None,
            _subs: subs,
        };
        if std::env::var_os("LFLY_MUTE").is_some() {
            this.core.command(protocol::CMD_VOLUME, 0);
        }
        if let Ok(q) = std::env::var("LFLY_AUTOPLAY") {
            this.nav.update(cx, |n, cx| n.go(Route::Search, cx));
            this.search.update(cx, |s, cx| {
                s.autoplay = true;
                s.run(q, cx);
            });
        }
        // LFLY_ROUTE: open a page at start (liked, library, settings, album:<id>, playlist:<id>,
        // local:<id>), for screenshots.
        if let Ok(r) = std::env::var("LFLY_ROUTE") {
            let (k, id) = r.split_once(':').unwrap_or((r.as_str(), ""));
            let route = match k {
                "liked" => Some(Route::Liked),
                "library" => Some(Route::Library),
                "settings" => Some(Route::Settings),
                "lyrics" => Some(Route::Lyrics),
                "nowplaying" => Some(Route::NowPlaying),
                "album" => Some(Route::Album { id: id.into(), name: String::new() }),
                "playlist" => Some(Route::Playlist { id: id.into(), name: String::new(), local: false }),
                "local" => Some(Route::Playlist { id: id.into(), name: String::new(), local: true }),
                "artist" => Some(Route::Artist { id: id.into(), name: String::new() }),
                _ => None,
            };
            if let Some(route) = route {
                this.nav.update(cx, |n, cx| n.go(route, cx));
            }
        }
        this
    }

    /// The dynamic modes take their colours from the playing cover (coverArt.ts).
    fn refresh_colors(&mut self, cx: &mut Context<Self>) {
        let Some(track) = self.model.read(cx).current().cloned() else { return };
        if self.colors_for.as_deref() == Some(track.id.as_str()) {
            return;
        }
        self.colors_for = Some(track.id.clone());
        let Some(url) = track.cover_url.clone() else { return };
        let core = self.core.clone();
        let id = track.id;
        cx.spawn(async move |this, cx| {
            let Some(bytes) = core.fetch(url).await else { return };
            let colors = cx.background_executor().spawn(async move { scene::cover_colors(&bytes) }).await;
            let _ = this.update(cx, |this, cx| {
                if this.colors_for.as_deref() == Some(id.as_str()) {
                    this.look.update(cx, |l, cx| l.set_cover_colors(colors, cx));
                }
            });
        })
        .detach();
    }
}

/// A round 32 px control of the top bar's glass pills (App.svelte `.round`).
fn round(id: &'static str, name: &'static str, disabled: bool, text: Hsla) -> Stateful<Div> {
    div()
        .id(id)
        .size(px(32.))
        .flex()
        .items_center()
        .justify_center()
        .rounded_full()
        .when(disabled, |s| s.opacity(0.32))
        .when(!disabled, |s| s.cursor_pointer().hover(|s| s.bg(text.opacity(0.12))))
        .child(icon(name, 16., text))
}

/// WindowControls.svelte: 28 px discs, text-2 on an 8% wash; close turns red under the pointer.
pub(crate) fn window_button(id: &'static str, name: &'static str, close: bool, text: Hsla, text2: Hsla) -> Stateful<Div> {
    let group: SharedString = format!("wc-{id}").into();
    div()
        .id(id)
        .group(group.clone())
        .size(px(28.))
        .flex()
        .items_center()
        .justify_center()
        .rounded_full()
        .bg(text.opacity(0.08))
        .cursor_pointer()
        .hover(move |s| if close { s.bg(rgb(0xe5484d)) } else { s.bg(text.opacity(0.16)) })
        .child(icon(name, 12., text2).group_hover(group, move |s| s.text_color(if close { white() } else { text })))
}

impl Lfly {
    fn top_bar(&self, window: &Window, panel_open: bool, cx: &mut Context<Self>) -> impl IntoElement {
        let (text, text2, text3, look) = {
            let l = self.look.read(cx);
            (l.scheme.text, l.scheme.text2, l.scheme.text3, l.glass())
        };
        let n = self.nav.read(cx);
        let (can_back, can_forward, on_search) = (n.can_back(), n.can_forward(), n.route == Route::Search);
        let nav = self.nav.clone();
        let (n1, n2, n3, n4) = (nav.clone(), nav.clone(), nav.clone(), nav);
        let maximized = window.is_maximized();
        div()
            .absolute()
            .top_0()
            .left_0()
            .right_0()
            .flex()
            .items_center()
            .gap(px(10.))
            .pt(px(14.))
            .px(px(24.))
            .pb(px(10.))
            // The back/forward pair (App.svelte `.nav-pair`): a glass pill, 3 px around 32 px discs.
            .child(
                div()
                    .relative()
                    .flex_none()
                    .rounded_full()
                    .child(glass::glass_layer(look, px(19.), glass::Pointer::default()))
                    .child(
                        div()
                            .relative()
                            .flex()
                            .gap(px(2.))
                            .p(px(3.))
                            .child(round("back", "back", !can_back, text).on_click(move |_, _, cx| n1.update(cx, |n, cx| n.go_back(cx))))
                            .child(round("forward", "forward", !can_forward, text).on_click(move |_, _, cx| n2.update(cx, |n, cx| n.go_forward(cx)))),
                    ),
            )
            .when(!on_search, |s| {
                s.child(
                    div()
                        .id("search-pill")
                        .relative()
                        .h(px(38.))
                        .flex_shrink(1.)
                        .min_w_0()
                        .w(px(400.))
                        .rounded_full()
                        .cursor_pointer()
                        .on_click(move |_, _, cx| n3.update(cx, |n, cx| n.go(Route::Search, cx)))
                        .child(glass::glass_layer(look, px(19.), glass::Pointer::default()))
                        .child(
                            div()
                                .relative()
                                .size_full()
                                .flex()
                                .items_center()
                                .gap(px(10.))
                                .pl(px(15.))
                                .pr(px(8.))
                                .text_size(px(13.5))
                                .text_color(text2)
                                .child(icon("search", 16., text2))
                                .child(div().flex_1().truncate().child("Cosa vuoi ascoltare?"))
                                .child(
                                    div()
                                        .flex_none()
                                        .text_size(px(10.5))
                                        .font_weight(FontWeight::SEMIBOLD)
                                        .text_color(text3)
                                        .bg(text.opacity(0.08))
                                        .rounded_full()
                                        .px(px(9.))
                                        .py(px(5.))
                                        .child("Ctrl K"),
                                ),
                        ),
                )
            })
            // The free space moves the window (Tauri: data-tauri-drag-region).
            .child(
                div()
                    .id("drag")
                    .flex_1()
                    .h(px(38.))
                    .on_mouse_down(MouseButton::Left, |_, window, _| window.start_window_move())
                    .on_click(|e, window, _| {
                        if e.click_count() == 2 {
                            window.zoom_window()
                        }
                    }),
            )
            .child(
                div()
                    .id("theme")
                    .relative()
                    .size(px(38.))
                    .flex_none()
                    .rounded_full()
                    .cursor_pointer()
                    .on_click(move |_, _, cx| n4.update(cx, |n, cx| n.go(Route::Settings, cx)))
                    .child(glass::glass_layer(look, px(19.), glass::Pointer::default()))
                    .child(div().relative().size_full().flex().items_center().justify_center().child(icon("palette", 17., text))),
            )
            // With the panel open the window controls sit at its top instead.
            .when(!panel_open, |d| d.child(
                div()
                    .flex()
                    .items_center()
                    .gap(px(6.))
                    .flex_none()
                    .child(window_button("min", "wc-min", false, text, text2).on_click(|_, w, _| w.minimize_window()))
                    .child(window_button("max", if maximized { "wc-restore" } else { "wc-max" }, false, text, text2).on_click(|_, w, _| w.zoom_window()))
                    .child(window_button("close", "wc-close", true, text, text2).on_click(|_, w, _| w.remove_window())),
            ))
    }

    fn show_collection(&mut self, source: Source, name: Option<String>, cx: &mut Context<Self>) -> AnyElement {
        self.collection.update(cx, |c, cx| c.show(source, name, cx));
        self.collection.clone().into_any_element()
    }

    fn page(&mut self, cx: &mut Context<Self>) -> AnyElement {
        let route = self.nav.read(cx).page_route();
        let text3 = self.look.read(cx).scheme.text3;
        match route {
            Route::Home => self.home.clone().into_any_element(),
            Route::Album { id, name } => self.show_collection(Source::Album(id), Some(name), cx),
            Route::Playlist { id, name, local } => {
                self.show_collection(if local { Source::Local(id) } else { Source::Playlist(id) }, Some(name), cx)
            }
            Route::Liked => self.show_collection(Source::Liked, None, cx),
            Route::Artist { id, name } => {
                self.artist.update(cx, |a, cx| a.show(id, name, cx));
                self.artist.clone().into_any_element()
            }
            Route::Search => self.search.clone().into_any_element(),
            Route::Library => self.library_page.clone().into_any_element(),
            Route::Lyrics => self.lyrics_page.clone().into_any_element(),
            other => div()
                .size_full()
                .pt(px(80.))
                .px(px(28.))
                .flex()
                .flex_col()
                .gap(px(6.))
                .child(div().text_size(px(28.)).font_weight(FontWeight::BOLD).child(match other {
                    Route::Downloads => "Scaricati",
                    Route::Settings => "Impostazioni",
                    _ => "",
                }))
                .child(div().text_color(text3).child("Questa pagina non è ancora stata portata nella versione nativa."))
                .into_any_element(),
        }
    }
}

/// App.svelte's fold: cubic-bezier(0.32, 0.72, 0, 1), 460 ms.
const FOLD: crate::motion::CubicBezier = crate::motion::CubicBezier::new(0.32, 0.72, 0., 1.);

impl Render for Lfly {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let size = content_size(window);
        let scene_img = self.look.update(cx, |l, cx| l.scene(size, cx));
        let (bg, text) = {
            let s = &self.look.read(cx).scheme;
            (s.bg.hsla(1.), s.text)
        };
        let (win_w, win_h) = (f32::from(size.width), f32::from(size.height));
        let (panel, sidebar_wide) = {
            let u = self.ui.read(cx);
            (u.panel, u.sidebar_wide)
        };
        let side_wide = self.ui.read(cx).side_wide(win_w);
        // ui.compact: a window 900 px wide or less; the panel then floats over the page.
        let compact = win_w <= 900.;

        // App.svelte: --side-w: clamp(232px, 19vw, 280px), 88 px folded; --panel-w:
        // clamp(288px, 24vw, 360px); both columns ease to their width.
        let side_target = if side_wide { (win_w * 0.19).clamp(232., 280.) } else { 88. };
        let panel_target = if panel.is_some() && !compact { (win_w * 0.24).clamp(288., 360.) } else { 0. };
        let side = self.side_w.get_or_insert(Tween::still(side_target));
        side.retarget(side_target, 460., &FOLD);
        let side_w = side.value(&FOLD);
        let pw = self.panel_w.get_or_insert(Tween::still(panel_target));
        pw.retarget(panel_target, 460., &FOLD);
        let panel_w = pw.value(&FOLD);
        let folding = self.side_w.is_some_and(|t| t.running()) || self.panel_w.is_some_and(|t| t.running());

        // The panel's content flies in from 24 px to the right (260 ms) and back out.
        let panel_open = panel.is_some();
        if panel_open != self.panel_shown {
            self.panel_shown = panel_open;
            self.panel_motion = Some((std::time::Instant::now(), panel_open));
        }
        let panel_t = match self.panel_motion {
            Some((at, opening)) => {
                let p = (at.elapsed().as_secs_f64() / 0.26).min(1.);
                if p >= 1. {
                    self.panel_motion = None;
                    None
                } else {
                    let e = crate::motion::CUBIC_OUT;
                    Some(if opening { e.at(p) } else { e.at(1. - p) } as f32)
                }
            }
            None => None,
        };
        let show_panel = panel_open || panel_t.is_some();

        self.sidebar.update(cx, |s, _| {
            s.wide = side_wide;
            s.can_toggle = !compact && !(sidebar_wide && !side_wide);
        });
        self.right_panel.update(cx, |r, _| r.floating = compact);
        let stage_w = win_w - side_w - panel_w;
        let bar_w = (stage_w - 24.).min(1180.);
        self.bar.update(cx, |b, _| b.width = bar_w);
        self.home.update(cx, |h, _| {
            h.width = win_w;
            h.page_width = stage_w;
        });
        self.collection.update(cx, |c, _| c.width = win_w);
        self.library_page.update(cx, |l, _| l.page_width = stage_w);
        self.search.update(cx, |s, _| {
            s.width = win_w;
            s.page_width = stage_w;
        });
        self.artist.update(cx, |a, _| {
            a.width = win_w;
            a.height = win_h;
        });
        self.now_playing.update(cx, |n, _| {
            n.width = win_w;
            n.height = win_h;
        });
        self.lyrics_page.update(cx, |l, _| {
            l.width = win_w;
            l.page_width = stage_w;
            l.height = win_h;
        });

        // The full player flies in from 60 px below and fades (420 ms, cubicOut), and back out.
        let on_np = self.nav.read(cx).route == Route::NowPlaying;
        if on_np != self.np_shown {
            self.np_shown = on_np;
            self.np_motion = Some((std::time::Instant::now(), on_np));
        }
        let np_t = match self.np_motion {
            Some((at, opening)) => {
                let p = (at.elapsed().as_secs_f64() / 0.42).min(1.);
                if p >= 1. {
                    self.np_motion = None;
                    None
                } else {
                    let e = crate::motion::CUBIC_OUT;
                    Some(if opening { e.at(p) } else { e.at(1. - p) } as f32)
                }
            }
            None => None,
        };
        if np_t.is_some() || panel_t.is_some() || folding {
            window.request_animation_frame();
        }
        let show_np = on_np || np_t.is_some();
        // Under the full player the shell is not drawn at all: its glass and bar would be
        // drawn, captured and blurred for nothing.
        let show_shell = !on_np || np_t.is_some();

        let mut root = div().size_full().relative().font_family(self.font.clone()).bg(bg).text_color(text);
        if show_shell {
            let top = self.top_bar(window, panel_open, cx);
            let page = self.page(cx);
            let t = panel_t.unwrap_or(1.);
            let mut panel_el = show_panel.then(|| {
                div()
                    .absolute()
                    .top_0()
                    .bottom_0()
                    .left(px(24. * (1. - t)))
                    .w_full()
                    .opacity(t)
                    .child(self.right_panel.clone())
            });
            let mut shell = div()
                .absolute()
                .inset_0()
                .flex()
                .child(div().w(px(side_w)).flex_none().h_full().pt(px(12.)).pb(px(12.)).pl(px(12.)).child(self.sidebar.clone()))
                .child(
                    div()
                        .relative()
                        .flex_1()
                        .min_w_0()
                        .h_full()
                        .child(page)
                        .child(top)
                        // App.svelte `.bottom`: 12 px from the edges, at most 1180 px, centred.
                        .child(
                            div()
                                .absolute()
                                .left(px(12.))
                                .right(px(12.))
                                .bottom(px(12.))
                                .h(px(76.))
                                .flex()
                                .justify_center()
                                .child(div().relative().h_full().w_full().max_w(px(1180.)).child(self.bar.clone())),
                        ),
                );
            if !compact {
                // `.right`: its own column, 12 px in from the top, right and bottom.
                shell = shell.child(
                    div()
                        .w(px(panel_w))
                        .flex_none()
                        .h_full()
                        .overflow_hidden()
                        .child(div().relative().h_full().w(px(panel_target.max(panel_w).max(1.))).pt(px(12.)).pr(px(12.)).pb(px(12.)).children(panel_el.take().map(|p| {
                            div().relative().size_full().child(p)
                        }))),
                );
            }
            root = root
                // The scene (scene.rs), painted again only on change.
                .children(scene_img.map(|b| div().absolute().inset_0().child(img(b).size_full().object_fit(ObjectFit::Fill))))
                .child(shell);
            if compact && show_panel {
                // Narrow window: the panel floats over the page; around it, a veil that closes it.
                let ui = self.ui.clone();
                root = root
                    .child(
                        div()
                            .id("panel-veil")
                            .absolute()
                            .inset_0()
                            .bg(black().opacity(0.32 * t))
                            .on_click(move |_, _, cx| ui.update(cx, |u, cx| u.set_panel(None, cx))),
                    )
                    .child(
                        div()
                            .absolute()
                            .top_0()
                            .bottom_0()
                            .right_0()
                            .w(px(372f32.min(win_w)))
                            .p(px(12.))
                            .child(div().relative().size_full().children(panel_el.take())),
                    );
            }
        }
        if show_np {
            let t = np_t.unwrap_or(1.);
            root = root.child(
                div()
                    .absolute()
                    .left_0()
                    .right_0()
                    .top(px(60. * (1. - t)))
                    .h(px(win_h))
                    .opacity(t)
                    .child(self.now_playing.clone()),
            );
        }
        root
    }
}

fn main() {
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info")).init();
    let (core, updates) = match Core::new() {
        Ok(c) => c,
        Err(e) => {
            eprintln!("LFly non parte: {e}");
            std::process::exit(1);
        }
    };
    gpui_kit::application().with_assets(icons::Assets).run(move |cx: &mut App| {
        if let Err(e) = cx.text_system().add_fonts(FONTS.iter().map(|f| std::borrow::Cow::Borrowed(*f)).collect()) {
            log::warn!("fonts: {e}");
        }
        gpui_kit::init(cx);
        gpui_kit::component::Theme::change(gpui_kit::component::ThemeMode::Dark, None, cx);
        let bounds = Bounds::centered(None, size(px(1100.), px(720.)), cx);
        let options = WindowOptions {
            window_bounds: Some(WindowBounds::Windowed(bounds)),
            window_min_size: Some(size(px(720.), px(480.))),
            app_id: Some("lfly".into()),
            // The title bar is ours (TitleBar draws the buttons); no second one from the system.
            window_decorations: Some(WindowDecorations::Client),
            ..gpui_kit::component::TitleBar::window_options()
        };
        let model = cx.new(|cx| PlayerModel::new(core.clone(), updates, cx));
        gpui_kit::open_window(options, cx, |window, cx| cx.new(|cx| Lfly::new(core, model, window, cx)))
            .expect("window");
        cx.activate(true);
    });
}
