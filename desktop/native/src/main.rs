//! LFly for computer, drawn by GPUI: no webview, the GPU draws only when something changed.
//!
//! The rewrite of the Tauri window, a screen at a time. Logic lives in lfly-core (shared with
//! the Tauri app); this crate only draws. Test modes: `LFLY_AUTOPLAY="query"` plays the first
//! result, `LFLY_MUTE=1`, and the glass comparison flags in player_bar.rs.

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
mod motion;
mod nav;
mod player_bar;
mod player_model;
mod scene;
mod search;
mod sidebar;
mod slider;
mod theme;
mod track_row;

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
use crate::nav::{Nav, Route};
use crate::player_bar::PlayerBar;
use crate::player_model::{PlayerEvent, PlayerModel};
use crate::search::Search;
use crate::sidebar::Sidebar;

struct Lfly {
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
    /// The song whose cover colours the look.
    colors_for: Option<String>,
    _subs: Vec<Subscription>,
}

impl Lfly {
    fn new(core: Arc<Core>, model: Entity<PlayerModel>, window: &mut Window, cx: &mut Context<Self>) -> Self {
        let covers = cx.new(|_| Covers::new(core.clone()));
        let look = cx.new(|_| Look::new());
        let bar = cx.new(|cx| PlayerBar::new(model.clone(), covers.clone(), look.clone(), cx));
        let nav = cx.new(|_| Nav::new());
        let library = cx.new(LibraryModel::new);
        let sidebar = cx.new(|cx| Sidebar::new(nav.clone(), library.clone(), look.clone(), covers.clone(), cx));
        let home = cx.new(|cx| Home::new(core.clone(), covers.clone(), look.clone(), nav.clone(), library.clone(), model.clone(), cx));
        let collection = cx.new(|cx| Collection::new(core.clone(), covers.clone(), look.clone(), nav.clone(), library.clone(), model.clone(), cx));
        let artist = cx.new(|cx| Artist::new(core.clone(), covers.clone(), look.clone(), nav.clone(), library.clone(), model.clone(), cx));
        let search = cx.new(|cx| Search::new(core.clone(), covers.clone(), look.clone(), nav.clone(), library.clone(), model.clone(), window, cx));
        let library_page = cx.new(|cx| LibraryPage::new(core.clone(), covers.clone(), look.clone(), nav.clone(), library, cx));
        let subs = vec![
            cx.observe_window_activation(window, |this, window, cx| {
                let active = window.is_window_active();
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
        ];
        let this = Self {
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
            colors_for: None,
            _subs: subs,
        };
        if std::env::var_os("LFLY_MUTE").is_some() {
            this.core.command(protocol::CMD_VOLUME, 0);
        }
        // LFLY_ROUTE: open a page at start (liked, library, settings, album:<id>, playlist:<id>,
        // local:<id>), for screenshots.
        if let Ok(r) = std::env::var("LFLY_ROUTE") {
            let (k, id) = r.split_once(':').unwrap_or((r.as_str(), ""));
            let route = match k {
                "liked" => Some(Route::Liked),
                "library" => Some(Route::Library),
                "settings" => Some(Route::Settings),
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
        if let Ok(q) = std::env::var("LFLY_AUTOPLAY") {
            this.nav.update(cx, |n, cx| n.go(Route::Search, cx));
            this.search.update(cx, |s, cx| {
                s.autoplay = true;
                s.run(q, cx);
            });
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
fn window_button(id: &'static str, name: &'static str, close: bool, text: Hsla, text2: Hsla) -> Stateful<Div> {
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
    fn top_bar(&self, window: &Window, cx: &mut Context<Self>) -> impl IntoElement {
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
            .child(
                div()
                    .flex()
                    .items_center()
                    .gap(px(6.))
                    .flex_none()
                    .child(window_button("min", "wc-min", false, text, text2).on_click(|_, w, _| w.minimize_window()))
                    .child(window_button("max", if maximized { "wc-restore" } else { "wc-max" }, false, text, text2).on_click(|_, w, _| w.zoom_window()))
                    .child(window_button("close", "wc-close", true, text, text2).on_click(|_, w, _| w.remove_window())),
            )
    }

    fn show_collection(&mut self, source: Source, name: Option<String>, cx: &mut Context<Self>) -> AnyElement {
        self.collection.update(cx, |c, cx| c.show(source, name, cx));
        self.collection.clone().into_any_element()
    }

    fn page(&mut self, cx: &mut Context<Self>) -> AnyElement {
        let route = self.nav.read(cx).route.clone();
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
            other => div()
                .size_full()
                .pt(px(80.))
                .px(px(28.))
                .flex()
                .flex_col()
                .gap(px(6.))
                .child(div().text_size(px(28.)).font_weight(FontWeight::BOLD).child(match other {
                    Route::Downloads => "Scaricati",
                    Route::Lyrics => "Testo",
                    Route::Settings => "Impostazioni",
                    Route::Search | Route::Home | Route::Album { .. } | Route::Playlist { .. } | Route::Liked | Route::Artist { .. } | Route::Library => unreachable!(),
                }))
                .child(div().text_color(text3).child("Questa pagina non è ancora stata portata nella versione nativa."))
                .into_any_element(),
        }
    }
}

impl Render for Lfly {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let size = window.viewport_size();
        let scene_img = self.look.update(cx, |l, cx| l.scene(size, cx));
        let (bg, text) = {
            let s = &self.look.read(cx).scheme;
            (s.bg.hsla(1.), s.text)
        };
        // App.svelte: --side-w: clamp(232px, 19vw, 280px); --edge: 12px.
        let side_w = (f32::from(size.width) * 0.19).clamp(232., 280.);
        let bar_w = (f32::from(size.width) - side_w - 24.).min(1180.);
        self.bar.update(cx, |b, _| b.width = bar_w);
        let (win_w, stage_w) = (f32::from(size.width), f32::from(size.width) - side_w);
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
        let win_h = f32::from(size.height);
        self.artist.update(cx, |a, _| {
            a.width = win_w;
            a.height = win_h;
        });
        let top = self.top_bar(window, cx);
        let page = self.page(cx);
        div()
            .size_full()
            .relative()
            .bg(bg)
            .text_color(text)
            // The scene (scene.rs), painted again only on change.
            .children(scene_img.map(|b| div().absolute().inset_0().child(img(b).size_full().object_fit(ObjectFit::Fill))))
            .child(
                div()
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
                    ),
            )
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
