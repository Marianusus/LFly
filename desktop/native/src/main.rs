//! LFly for computer, drawn by GPUI: no webview, the GPU draws only when something changed.
//!
//! The rewrite of the Tauri window, a screen at a time. Logic lives in lfly-core (shared with
//! the Tauri app); this crate only draws. Test modes: `LFLY_AUTOPLAY="query"` plays the first
//! result, `LFLY_MUTE=1`, and the glass comparison flags in player_bar.rs.

mod account;
mod ambient;
mod app_menu;
mod art;
mod audio;
mod artist;
mod core;
mod autopilot;
mod video;
mod stems_model;
mod deck_model;
mod dj;
mod downloads;
mod collection;
mod connect;
mod covers;
mod flow;
mod glass;
mod home;
mod icons;
mod library_model;
mod library_page;
mod look;
mod menu;
mod midi;
mod midi_map;
mod local_music;
mod lyrics;
mod console;
mod i18n;
mod i18n_table;
mod lyrics_page;
mod media;
mod motion;
mod nav;
mod now_playing;
mod pill;
mod player_bar;
mod player_model;
mod prefs;
mod queue_list;
mod right_panel;
mod scene;
mod search;
mod settings_page;
mod sleep;
mod spotify_account;
mod spotify_import;
mod sync;
mod sidebar;
mod skeleton;
mod standby;
mod slider;
mod theme;
mod toast;
mod track_row;
mod ui;
#[cfg(test)]
mod ui_tests;

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

/// Where the view starts in the window (past that frame): pointer positions are the window's.
fn content_origin(window: &Window) -> Point<Pixels> {
    let pad = gpui_kit::component::window_paddings(window);
    let mut o = point(pad.left, pad.top);
    if let Decorations::Client { tiling } = window.window_decorations() {
        if !tiling.left {
            o.x += px(1.);
        }
        if !tiling.top {
            o.y += px(1.);
        }
    }
    o
}

struct Lfly {
    font: SharedString,
    core: Arc<Core>,
    /// For Modalità Ambient (standby.rs), which reads the same lyrics and covers.
    lyrics_data: Entity<LyricsData>,
    covers_all: Entity<Covers>,
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
    console: Entity<crate::console::Console>,
    ui: Entity<Ui>,
    right_panel: Entity<RightPanel>,
    settings: Entity<crate::settings_page::SettingsPage>,
    settings_shown: bool,
    toasts: Entity<crate::toast::Toasts>,
    dj_island: Entity<crate::dj::DjIsland>,
    menu: Entity<crate::menu::Menu>,
    flow: Entity<crate::flow::Flow>,
    menu_shown: bool,
    /// The window size being saved (the last change wins).
    bounds_seq: u64,
    library: Entity<LibraryModel>,
    last_played: Option<String>,
    /// The sidebar's width (232..280, or 88 folded) and the panel's column, easing between
    /// (App.svelte: grid-template-columns 460 ms).
    side_w: Option<Tween>,
    panel_w: Option<Tween>,
    /// The panel sliding in (true) or out, since when (`fly` x 24, 260 ms).
    panel_motion: Option<(std::time::Instant, bool)>,
    panel_shown: bool,
    /// The full player sliding in (true) or out, since when (NowPlaying.svelte `fly`, 420 ms).
    np_motion: Option<(std::time::Instant, bool)>,
    /// The page shown last, and when it changed: a new page fades in rising 8 px (220 ms).
    page_at: Option<(Route, std::time::Instant)>,
    /// A redraw for the live row bars is on its way.
    eq_tick: bool,
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
        // macOS: the red light hides the app and the music goes on (the Dock brings it back).
        if SYSTEM_WINDOW_BUTTONS {
            window.on_window_should_close(cx, |_, cx| {
                cx.hide();
                false
            });
        }
        let start = crate::prefs::Prefs::new(cx).read(cx).v.start_page.clone();
        crate::audio::AudioModel::new(core.clone(), cx);
        crate::media::SystemMedia::start(model.clone(), window, cx);
        crate::downloads::DownloadsModel::start(&core, cx);
        crate::account::AccountModel::start(core.clone(), cx);
        crate::connect::ConnectModel::start(core.clone(), model.clone(), cx);
        crate::sleep::SleepTimer::start(model.clone(), cx);
        crate::midi::MidiModel::start(core.clone(), model.clone(), cx);
        crate::deck_model::DjDecks::start(core.clone(), model.clone(), cx);
        crate::stems_model::StemsModel::start(core.clone(), cx);
        let look_for_flow = look.clone();
        let ui = cx.new(|_| Ui::new());
        let library = cx.new(LibraryModel::new);
        crate::spotify_account::SpotifyModel::start(library.clone(), cx);
        // Library sync with the phones and Google Drive (not in test runs: sync.rs).
        crate::sync::SyncModel::start(library.clone(), cx);
        let _ = crate::core::LIBRARY_ANSWER.set(crate::sync::answer);
        let bar = cx.new(|cx| PlayerBar::new(model.clone(), nav.clone(), ui.clone(), library.clone(), covers.clone(), look.clone(), cx));
        let toasts = crate::toast::Toasts::new(look.clone(), cx);
        let dj_island = {
            let dj = crate::dj::DjModel::entity(cx).expect("made before the window");
            cx.new(|cx| crate::dj::DjIsland::new(look.clone(), dj, cx))
        };
        let menu = crate::menu::Menu::new(core.clone(), look.clone(), covers.clone(), library.clone(), nav.clone(), cx);
        let sidebar = cx.new(|cx| Sidebar::new(nav.clone(), ui.clone(), library.clone(), look.clone(), covers.clone(), cx));
        let home = cx.new(|cx| Home::new(core.clone(), covers.clone(), look.clone(), nav.clone(), library.clone(), model.clone(), cx));
        let collection = cx.new(|cx| Collection::new(core.clone(), covers.clone(), look.clone(), nav.clone(), library.clone(), model.clone(), cx));
        let artist = cx.new(|cx| Artist::new(core.clone(), covers.clone(), look.clone(), nav.clone(), library.clone(), model.clone(), cx));
        let search = cx.new(|cx| Search::new(core.clone(), covers.clone(), look.clone(), nav.clone(), library.clone(), model.clone(), window, cx));
        let library_page = cx.new(|cx| LibraryPage::new(core.clone(), covers.clone(), look.clone(), nav.clone(), library.clone(), model.clone(), cx));
        // One fetch per song, three views of it: the full player, the page, the panel.
        let lyrics_data = cx.new(|cx| {
            // Only when the kept lyrics changed, not at each progress step of a download.
            let mut kept = None;
            cx.observe(&crate::downloads::DownloadsModel::entity(cx), move |d: &mut LyricsData, dl, cx| {
                let now = { let m = dl.read(cx); m.list.tracks.len() - m.missing_lyrics };
                if kept.replace(now).is_some_and(|k| k != now) {
                    d.forget_missing(cx);
                }
            })
            .detach();
            // The lyrics sources Settings chose, their order, the priority and the PaxSenix key:
            // given to the engine now and whenever they change (the songs are then asked again).
            let prefs = crate::prefs::Prefs::entity(cx);
            let mut sources = prefs.read(cx).v.lyrics_sources();
            core.yt.set_lyrics_prefs(sources.clone());
            let yt = core.yt.clone();
            cx.observe(&prefs, move |d: &mut LyricsData, p, cx| {
                let now = p.read(cx).v.lyrics_sources();
                if now != sources {
                    sources = now.clone();
                    yt.set_lyrics_prefs(now);
                    d.forget_all(cx);
                }
            })
            .detach();
            LyricsData::new(core.clone())
        });
        let lyrics_np = cx.new(|cx| Lyrics::new(lyrics_data.clone(), model.clone(), look.clone(), None, Variant::Panel, cx));
        let lyrics_pg = cx.new(|cx| Lyrics::new(lyrics_data.clone(), model.clone(), look.clone(), None, Variant::Page, cx));
        let lyrics_side = cx.new(|cx| Lyrics::new(lyrics_data.clone(), model.clone(), look.clone(), Some(nav.clone()), Variant::Side, cx));
        let now_playing = cx.new(|cx| NowPlaying::new(core.clone(), model.clone(), covers.clone(), look.clone(), nav.clone(), library.clone(), lyrics_np, cx));
        let spotify = cx.new(|cx| crate::spotify_import::SpotifyImport::new(core.clone(), look.clone(), library.clone(), nav.clone(), window, cx));
        library_page.update(cx, |l, cx| l.set_spotify(spotify, cx));
        let settings = cx.new(|cx| crate::settings_page::SettingsPage::new(core.clone(), look.clone(), library.clone(), lyrics_data.clone(), model.clone(), covers.clone(), cx));
        crate::app_menu::install(nav.clone(), model.clone(), ui.clone(), settings.clone(), cx);
        let lyrics_page = cx.new(|cx| LyricsPage::new(core.clone(), model.clone(), covers.clone(), look.clone(), nav.clone(), lyrics_pg, cx));
        let console = cx.new(|cx| crate::console::Console::new(core.clone(), model.clone(), covers.clone(), library.clone(), lyrics_data.clone(), window, cx));
        let right_panel = cx.new(|cx| RightPanel::new(ui.clone(), model.clone(), covers.clone(), look.clone(), core.clone(), lyrics_side, cx));
        let subs = vec![
            // The window's size, kept for next time (not by test runs), once it stops changing.
            cx.observe_window_bounds(window, |this, window, cx| {
                if std::env::var_os("LFLY_MUTE").is_some() || std::env::var_os("LFLY_AUTOPLAY").is_some() || std::env::var_os("LFLY_SIZE").is_some() {
                    return;
                }
                // Full screen for Modalità Ambient is not how the window should open next time.
                if crate::standby::view(cx).is_some() {
                    return;
                }
                let (b, maximized) = match window.window_bounds() {
                    WindowBounds::Windowed(b) => (b, false),
                    WindowBounds::Maximized(b) | WindowBounds::Fullscreen(b) => (b, true),
                };
                this.bounds_seq += 1;
                let mine = this.bounds_seq;
                cx.spawn(async move |this, cx| {
                    cx.background_executor().timer(std::time::Duration::from_millis(600)).await;
                    let _ = this.update(cx, |this, _| {
                        if this.bounds_seq == mine {
                            let v = serde_json::json!({ "width": f32::from(b.size.width), "height": f32::from(b.size.height), "maximized": maximized });
                            if let Err(e) = lfly_core::store::set(WINDOW_KEY, &v) {
                                log::warn!("{WINDOW_KEY}: {e}");
                            }
                        }
                    });
                })
                .detach();
            }),
            // The "where" pill above the bar follows LFly Connect.
            cx.observe(&crate::connect::ConnectModel::entity(cx), |_, _, cx| cx.notify()),
            cx.observe_window_activation(window, |_, window, cx| {
                let active = window.is_window_active() || std::env::var_os("LFLY_FORCE_ACTIVE").is_some();
                log::debug!("window active: {active}");
                // (LFLY_MENU keeps it for the screenshot, taken with the window in the back.)
                if !active && std::env::var_os("LFLY_MENU").is_none() {
                    crate::menu::Menu::close(cx);
                }
            }),
            // What moves (the position, the bars, the lyrics) stops only when the window is not
            // on screen at all; behind the focus it is still watched.
            cx.observe_window_visibility(window, |this, _, window, cx| {
                let shown = crate::presented(window);
                log::debug!("window presented: {shown}");
                this.model.update(cx, |m, cx| {
                    if m.active != shown {
                        m.active = shown;
                        cx.emit(PlayerEvent::Activity);
                        cx.notify();
                    }
                });
            }),
            // Only real changes reach this view: the position ticks stay in the bar.
            cx.subscribe(&model, |this, _, ev: &PlayerEvent, cx| match ev {
                PlayerEvent::StateChanged => {
                    this.refresh_colors(cx);
                    this.record_play(cx);
                    // LFLY_STANDBY (tests): Modalità Ambient once a song is there (not full screen).
                    if std::env::var_os("LFLY_STANDBY").is_some() && !this.menu_shown && this.model.read(cx).current().is_some() {
                        this.menu_shown = true;
                        crate::standby::request(cx);
                    }
                    // LFLY_MENU: the song's menu and a toast, once, for screenshots.
                    if std::env::var_os("LFLY_MENU").is_some() && !this.menu_shown {
                        if let Some(t) = this.model.read(cx).current().cloned() {
                            this.menu_shown = true;
                            // LFLY_MENU=sleep: the full player's menu, its sleep timer open.
                            if std::env::var("LFLY_MENU").as_deref() == Ok("sleep") {
                                crate::menu::Menu::open_player_track(point(px(520.), px(160.)), t, cx);
                                crate::menu::Menu::toggle_sleep(cx);
                            } else if std::env::var("LFLY_MENU").as_deref() == Ok("speed") {
                                // LFLY_MENU=speed: the full player's menu, its speeds open.
                                crate::menu::Menu::open_player_track(point(px(520.), px(120.)), t, cx);
                                crate::menu::Menu::toggle_speed(cx);
                            } else {
                                crate::menu::Menu::open_track(point(px(520.), px(260.)), t, None, cx);
                            }
                            crate::toast::Toasts::say(crate::i18n::tr("Aggiunto alla coda"), cx);
                        }
                    }
                    cx.notify();
                }
                PlayerEvent::Notice(text) => crate::toast::Toasts::say(text.clone(), cx),
                PlayerEvent::Arrived | PlayerEvent::Activity => {}
            }),
            cx.observe(&covers, |_, _, cx| cx.notify()),
            cx.observe(&look, |_, _, cx| cx.notify()),
            cx.observe(&nav, |_, _, cx| cx.notify()),
            cx.observe(&ui, |_, _, cx| cx.notify()),
            // The Console wants the whole width: the side panel closes while it is shown and comes
            // back as it was when another page opens.
            {
                let ui = ui.clone();
                let mut hidden: Option<crate::ui::SidePanel> = None;
                let mut was_console = false;
                cx.observe(&nav, move |_, nav, cx| {
                    let console = nav.read(cx).route == Route::Console;
                    if console && !was_console {
                        hidden = ui.read(cx).panel;
                        if hidden.is_some() {
                            ui.update(cx, |u, cx| u.set_panel(None, cx));
                        }
                    } else if !console && was_console {
                        if let Some(p) = hidden.take() {
                            if ui.read(cx).panel.is_none() {
                                ui.update(cx, |u, cx| u.set_panel(Some(p), cx));
                            }
                        }
                    }
                    was_console = console;
                })
            },
        ];
        // Esc closes the full player.
        let esc_nav = nav.clone();
        // The keyboard (App.svelte onKey). Intercepted, not observed: a focused field or gpui-kit
        // may take a key first.
        let (esc_menu, keys_model, keys_search) = (menu.clone(), model.clone(), search.clone());
        cx.intercept_keystrokes(move |e, window, cx| {
            let k = &e.keystroke;
            let m = k.modifiers;
            let ctrl = m.control || m.platform;
            let key = k.key.as_str();
            let stop = |cx: &mut App| cx.stop_propagation();
            if key == "escape" {
                // The menu first, then Modalità Ambient, then the full player.
                if esc_menu.read(cx).is_open() {
                    crate::menu::Menu::close(cx);
                } else if crate::standby::view(cx).is_some() {
                    crate::standby::close(window, cx);
                } else if esc_nav.read(cx).route == Route::NowPlaying {
                    NowPlaying::close(&esc_nav, cx);
                }
                return;
            }
            // Under Modalità Ambient the keys that move between pages do nothing (the window
            // shows none of them; a hidden search field would take the typing).
            if crate::standby::view(cx).is_some() && ((ctrl && matches!(key, "k" | "l" | "f")) || (m.alt && matches!(key, "left" | "right"))) {
                return stop(cx);
            }
            if ctrl && matches!(key, "k" | "l" | "f") {
                esc_nav.update(cx, |n, cx| n.go(Route::Search, cx));
                let h = keys_search.read(cx).field_focus(cx);
                h.focus(window, cx);
                return stop(cx);
            }
            if m.alt && key == "left" {
                esc_nav.update(cx, |n, cx| n.go_back(cx));
                return stop(cx);
            }
            if m.alt && key == "right" {
                esc_nav.update(cx, |n, cx| n.go_forward(cx));
                return stop(cx);
            }
            if crate::ui::typing(window, cx) {
                return;
            }
            let pm = keys_model.read(cx);
            let has = pm.current().is_some();
            match key {
                "space" if has && !ctrl && !m.shift && !m.alt => pm.command(protocol::CMD_TOGGLE, 0),
                "right" if ctrl => pm.command(protocol::CMD_NEXT, 0),
                "left" if ctrl => pm.command(protocol::CMD_PREVIOUS, 0),
                "right" if m.shift => pm.command(protocol::CMD_SEEK, pm.live_position() as i64 + 10_000),
                "left" if m.shift => pm.command(protocol::CMD_SEEK, (pm.live_position() as i64 - 10_000).max(0)),
                // As in Spotify: the volume, shuffle and repeat from the keyboard.
                "up" if ctrl => pm.command(protocol::CMD_VOLUME_STEP, 1),
                "down" if ctrl => pm.command(protocol::CMD_VOLUME_STEP, -1),
                "s" if ctrl && !m.shift && has => {
                    let on = !pm.state.as_ref().is_some_and(|s| s.shuffle);
                    pm.command(protocol::CMD_SHUFFLE, i64::from(on));
                    crate::toast::Toasts::say(if on { crate::i18n::tr("Riproduzione casuale attiva") } else { crate::i18n::tr("Riproduzione casuale disattivata") }, cx);
                }
                "r" if ctrl && !m.shift && has => {
                    // PlayerBar's cycle: off → all → one → off.
                    let (next, say) = match pm.state.as_ref().map_or("off", |s| s.repeat.as_str()) {
                        "off" => (protocol::REPEAT_ALL, crate::i18n::t("Ripeti tutto")),
                        "all" => (protocol::REPEAT_ONE, crate::i18n::t("Ripeti questo brano")),
                        _ => (protocol::REPEAT_OFF, crate::i18n::t("Ripetizione disattivata")),
                    };
                    pm.command(protocol::CMD_REPEAT, next);
                    crate::toast::Toasts::say(crate::i18n::tr(say), cx);
                }
                _ => return,
            }
            stop(cx);
        })
        .detach();
        let this = Self {
            lyrics_data: lyrics_data.clone(),
            covers_all: covers.clone(),
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
            console,
            ui,
            right_panel,
            settings,
            settings_shown: false,
            toasts,
            dj_island,
            menu,
            flow: cx.new(|cx| crate::flow::Flow::new(look_for_flow, cx)),
            menu_shown: false,
            bounds_seq: 0,
            library: library.clone(),
            last_played: None,
            side_w: None,
            panel_w: None,
            panel_motion: None,
            panel_shown: false,
            np_motion: None,
            page_at: None,
            eq_tick: false,
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
        // LFLY_SEEK="<seconds>@<ms>[;<seconds>@<ms>…]" (tests): moves the song to <ms> after <seconds>.
        for (after, to) in std::env::var("LFLY_SEEK").unwrap_or_default().split(';').filter_map(|v| v.split_once('@').and_then(|(a, b)| Some((a.trim().parse::<f64>().ok()?, b.trim().parse::<i64>().ok()?)))) {
            let model = this.model.clone();
            cx.spawn(async move |_, cx| {
                cx.background_executor().timer(std::time::Duration::from_secs_f64(after)).await;
                model.update(cx, |m, _| {
                    // Negative: that long before the song's end.
                    let to = if to < 0 { (m.duration_ms() as i64 + to).max(0) } else { to };
                    log::info!("LFLY_SEEK: to {to} ms");
                    m.command(lfly_connect::protocol::CMD_SEEK, to);
                });
            })
            .detach();
        }
        // LFLY_SKIP_LAST=<seconds> (tests): skips to the queue's last song then (the DJ's next block).
        if let Some(after) = std::env::var("LFLY_SKIP_LAST").ok().and_then(|v| v.parse::<f64>().ok()) {
            let model = this.model.clone();
            cx.spawn(async move |_, cx| {
                cx.background_executor().timer(std::time::Duration::from_secs_f64(after)).await;
                model.update(cx, |m, _| {
                    let last = m.state.as_ref().map_or(0, |s| s.queue.len() as i64 - 1);
                    log::info!("LFLY_SKIP_LAST: to #{last}");
                    m.command(lfly_connect::protocol::CMD_SKIP_TO, last);
                });
            })
            .detach();
        }
        // LFLY_ROUTE: open a page at start (liked, library, settings, album:<id>, playlist:<id>,
        // local:<id>), for screenshots.
        // Settings → Generale: the page LFly opens on (not a step to go back to).
        if std::env::var_os("LFLY_ROUTE").is_none() && std::env::var_os("LFLY_AUTOPLAY").is_none() {
            let route = match start.as_str() {
                "library" => Route::Library,
                "liked" => Route::Liked,
                "search" => Route::Search,
                _ => Route::Home,
            };
            this.nav.update(cx, |n, _| n.route = route);
        }
        if let Ok(r) = std::env::var("LFLY_ROUTE") {
            if let Some(route) = test_route(&r) {
                this.nav.update(cx, |n, cx| n.go(route, cx));
            }
        }
        // LFLY_ROUTE_AFTER="<route>@<seconds>": a second page later (the sidebar pill's journey).
        if let Some((r, secs)) = std::env::var("LFLY_ROUTE_AFTER").ok().and_then(|v| v.rsplit_once('@').map(|(r, s)| (r.to_string(), s.parse::<f64>().unwrap_or(3.)))) {
            let nav = this.nav.clone();
            cx.spawn(async move |_, cx| {
                cx.background_executor().timer(std::time::Duration::from_secs_f64(secs)).await;
                if let Some(route) = test_route(&r) {
                    let _ = nav.update(cx, |n, cx| n.go(route, cx));
                }
            })
            .detach();
        }
        // LFLY_SPEED=<speed> (tests): plays at that speed from the start.
        if let Some(v) = std::env::var("LFLY_SPEED").ok().and_then(|v| v.parse::<f32>().ok()) {
            this.core.player.set_speed(v);
        }
        // LFLY_HOVER_SWEEP="<x>,<y0>,<y1>@<start>[+<seconds>]" (tests, no pointer in a headless
        // compositor): moves the mouse from (x, y0) to (x, y1) over the window, at 60 Hz.
        if let Some((spec, at)) = std::env::var("LFLY_HOVER_SWEEP").ok().and_then(|v| v.rsplit_once('@').map(|(a, b)| (a.to_string(), b.to_string()))) {
            let n: Vec<f32> = spec.split(',').filter_map(|v| v.parse().ok()).collect();
            let (start, secs) = at.split_once('+').map_or((at.parse().unwrap_or(5.), 2.), |(a, b)| (a.parse().unwrap_or(5.), b.parse().unwrap_or(2.)));
            if let [x, y0, y1] = n[..] {
                cx.spawn_in(window, async move |_, cx| {
                    cx.background_executor().timer(std::time::Duration::from_secs_f64(start)).await;
                    let t0 = std::time::Instant::now();
                    loop {
                        let p = (t0.elapsed().as_secs_f64() / secs).min(1.) as f32;
                        let ev = PlatformInput::MouseMove(MouseMoveEvent { position: point(px(x), px(y0 + (y1 - y0) * p)), pressed_button: None, modifiers: Modifiers::default() });
                        if cx.update(|window, cx| window.dispatch_event(ev, cx)).is_err() || p >= 1. {
                            break;
                        }
                        cx.background_executor().timer(std::time::Duration::from_millis(16)).await;
                    }
                })
                .detach();
            }
        }
        this
    }

    /// What plays here goes into the history, as on the phone (App.svelte). Not in test runs
    /// (LFLY_MUTE, LFLY_AUTOPLAY): they must not change the listener's library.
    fn record_play(&mut self, cx: &mut Context<Self>) {
        if std::env::var_os("LFLY_MUTE").is_some() || std::env::var_os("LFLY_AUTOPLAY").is_some() {
            return;
        }
        // Settings → Spazio e privacy: "pause the history".
        if crate::prefs::Prefs::get(cx).pause_history {
            return;
        }
        let m = self.model.read(cx);
        // Playing on the device this computer controls: that device keeps its own history.
        if m.remote.is_some() {
            return;
        }
        let Some(t) = m.current().cloned().filter(|_| m.playing()) else { return };
        if self.last_played.as_deref() == Some(t.id.as_str()) {
            return;
        }
        self.last_played = Some(t.id.clone());
        if let Ok(v) = serde_json::to_value(&t) {
            self.library.update(cx, |l, cx| l.played(v, cx));
        }
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
            let art = cx.background_executor().spawn(async move { scene::cover_art(&bytes) }).await;
            let _ = this.update(cx, |this, cx| {
                if this.colors_for.as_deref() == Some(id.as_str()) {
                    this.look.update(cx, |l, cx| l.set_cover_art(art, cx));
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
        .when(!disabled, |s| s.cursor_pointer().hover(|s| s.bg(text.opacity(0.12))).active(|s| s.bg(text.opacity(0.22))))
        .child(icon(name, 16., text))
}

/// macOS draws its own traffic lights (top left, at [`MAC_TRAFFIC_LIGHTS`]): ours stay hidden there.
pub(crate) const SYSTEM_WINDOW_BUTTONS: bool = cfg!(target_os = "macos");
/// Where the traffic lights sit: inside the sidebar card's top, above the brand.
/// Centred on the folded card (76 px from x 12; the lights span about 60 px).
pub(crate) const MAC_TRAFFIC_LIGHTS: (f32, f32) = (20., 27.);

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
            .id("topbar")
            .absolute()
            .top_0()
            .left_0()
            .right_0()
            // GPUI lets clicks through to what is under an element unless told: the strip
            // covers the page as Tauri's sticky bar does (the wheel still scrolls it).
            .block_mouse_except_scroll()
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
                                .child(div().flex_1().truncate().child(crate::i18n::tr("Cosa vuoi ascoltare?")))
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
                                        .child(if cfg!(target_os = "macos") { "⌘K" } else { "Ctrl K" }),
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
            // macOS: Settings live in the app menu (⌘,), not in the toolbar (HIG › Settings).
            .when(!SYSTEM_WINDOW_BUTTONS, |d| d.child(
                div()
                    .id("theme")
                    .relative()
                    .size(px(38.))
                    .flex_none()
                    .rounded_full()
                    .cursor_pointer()
                    .on_click({
                        let settings = self.settings.clone();
                        move |_, _, cx| {
                            settings.update(cx, |s, _| s.section = crate::settings_page::Section::Appearance);
                            n4.update(cx, |n, cx| n.go(Route::Settings, cx))
                        }
                    })
                    .child(glass::glass_layer(look, px(19.), glass::Pointer::default()))
                    .child(div().relative().size_full().flex().items_center().justify_center().child(icon("palette", 17., text))),
            ))
            // With the panel open the window controls sit at its top instead.
            .when(!panel_open && !SYSTEM_WINDOW_BUTTONS, |d| d.child(
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
        cached(&self.collection)
    }

    fn page(&mut self, cx: &mut Context<Self>) -> AnyElement {
        let route = self.nav.read(cx).page_route();
        if route != Route::Settings {
            self.settings_shown = false;
        }
        let text3 = self.look.read(cx).scheme.text3;
        match route {
            Route::Home => cached(&self.home),
            Route::Album { id, name } => self.show_collection(Source::Album(id), Some(name), cx),
            Route::Playlist { id, name, local } => {
                self.show_collection(if local { Source::Local(id) } else { Source::Playlist(id) }, Some(name), cx)
            }
            Route::Liked => self.show_collection(Source::Liked, None, cx),
            Route::Downloads => self.show_collection(Source::Downloads, None, cx),
            Route::Artist { id, name } => {
                self.artist.update(cx, |a, cx| a.show(id, name, cx));
                cached(&self.artist)
            }
            Route::Search => cached(&self.search),
            Route::Library => cached(&self.library_page),
            Route::Lyrics => cached(&self.lyrics_page),
            Route::Console => cached(&self.console),
            Route::Settings => {
                // Read again what it shows each time it opens.
                if !self.settings_shown {
                    self.settings_shown = true;
                    self.settings.update(cx, |s, cx| s.refresh(cx));
                }
                cached(&self.settings)
            }
            other => div()
                .size_full()
                .pt(px(80.))
                .px(px(28.))
                .flex()
                .flex_col()
                .gap(px(6.))
                .child(div().text_size(px(28.)).font_weight(FontWeight::BOLD).child(match other {
                    Route::Settings => crate::i18n::tr("Impostazioni"),
                    _ => "",
                }))
                .child(div().text_color(text3).child(crate::i18n::tr("Questa pagina non è ancora stata portata nella versione nativa.")))
                .into_any_element(),
        }
    }
}

impl Lfly {
    /// App.svelte's two LFly Connect dialogs: a device asking to control this computer, and this
    /// computer pairing with a device of another account. Both show the code to compare.
    fn pair_dialog(&self, cx: &mut Context<Self>) -> Option<AnyElement> {
        let connect = crate::connect::ConnectModel::try_entity(cx)?;
        let (request, pairing) = {
            let c = connect.read(cx);
            (c.request.clone(), c.pairing.clone())
        };
        let s = self.look.read(cx).scheme.clone();
        let (accent, on) = (s.accent.hsla(1.), s.on_accent.hsla(1.));
        let (badge, title, text, code, asking) = match (request, pairing) {
            (Some(r), _) => (
                "phone",
                crate::i18n::trf("{0} vuole controllare questo computer", &[&r.device_name]),
                crate::i18n::trf("Accetta solo se il codice è lo stesso che vedi su {0}.", &[&r.device_name]),
                r.code,
                true,
            ),
            (None, Some((name, code))) => (
                "devices",
                crate::i18n::trf("Abbinamento con {0}", &[&name]),
                crate::i18n::trf("Accetta questo computer su {0}. Il codice deve essere lo stesso su entrambi gli schermi.", &[&name]),
                code,
                false,
            ),
            _ => return None,
        };
        let btn = |id: &'static str, label: &'static str, primary: bool| {
            div()
                .id(id)
                .flex_1()
                .max_w(px(if asking { 400. } else { 200. }))
                .min_h(px(40.))
                .flex()
                .items_center()
                .justify_center()
                .rounded_full()
                .text_size(px(13.))
                .font_weight(FontWeight::SEMIBOLD)
                .cursor_pointer()
                .when(primary, |d| d.bg(accent).text_color(on))
                .when(!primary, |d| d.bg(s.text.opacity(0.08)).text_color(s.text).border_1().border_color(s.glass_border).hover(|d| d.bg(s.text.opacity(0.14))).active(|d| d.bg(s.text.opacity(0.25))))
                .child(label)
        };
        let (c1, c2, c3) = (connect.clone(), connect.clone(), connect);
        let actions = if asking {
            div()
                .flex()
                .justify_center()
                .gap(px(10.))
                .w_full()
                .child(btn("pair-no", crate::i18n::tr("Rifiuta"), false).on_click(move |_, _, cx| c1.update(cx, |c, cx| c.answer(false, cx))))
                .child(btn("pair-yes", crate::i18n::tr("Accetta"), true).on_click(move |_, _, cx| c2.update(cx, |c, cx| c.answer(true, cx))))
        } else {
            div().flex().justify_center().w_full().child(btn("pair-cancel", crate::i18n::tr("Annulla"), false).on_click(move |_, _, cx| c3.update(cx, |c, cx| c.disconnect(cx))))
        };
        Some(
            div()
                .id("pair-scrim")
                .absolute()
                .inset_0()
                .occlude()
                .bg(black().opacity(0.42))
                .flex()
                .items_center()
                .justify_center()
                .p(px(16.))
                .child(
                    div()
                        .w(px(400.))
                        .pt(px(28.))
                        .px(px(26.))
                        .pb(px(22.))
                        .rounded(px(30.))
                        .bg(s.surface.hsla(0.94))
                        .border_1()
                        .border_color(s.glass_border)
                        .shadow(vec![BoxShadow { color: black().opacity(0.45), offset: point(px(0.), px(24.)), blur_radius: px(60.), spread_radius: px(0.), inset: false }])
                        .flex()
                        .flex_col()
                        .items_center()
                        .text_center()
                        .child(
                            div()
                                .size(px(52.))
                                .mb(px(14.))
                                .rounded_full()
                                .flex()
                                .items_center()
                                .justify_center()
                                .bg(accent)
                                .shadow(vec![BoxShadow { color: accent.opacity(0.4), offset: point(px(0.), px(8.)), blur_radius: px(24.), spread_radius: px(0.), inset: false }])
                                .child(crate::icons::icon(badge, 22., on)),
                        )
                        .child(div().mb(px(8.)).text_size(px(19.)).line_height(px(23.75)).font_weight(FontWeight::BOLD).text_color(s.text).child(title))
                        .child(div().mb(px(20.)).line_height(px(19.5)).text_color(s.text2).child(text))
                        .child(div().mb(px(22.)).flex().justify_center().gap(px(8.)).children(code.chars().map(|d| {
                            div()
                                .w(px(50.))
                                .h(px(62.))
                                .flex()
                                .items_center()
                                .justify_center()
                                .rounded(px(16.))
                                .bg(s.text.opacity(0.09))
                                .border_1()
                                .border_color(s.glass_border)
                                .text_size(px(30.))
                                .font_weight(FontWeight::BOLD)
                                .text_color(s.text)
                                .child(d.to_string())
                        })))
                        .child(actions),
                )
                .into_any_element(),
        )
    }

    fn where_pill(&self, cx: &mut Context<Self>) -> Option<AnyElement> {
        let following = crate::connect::ConnectModel::try_entity(cx)?.read(cx).following.clone()?;
        let s = self.look.read(cx).scheme.clone();
        let (accent, on) = (s.accent.hsla(1.), s.on_accent.hsla(1.));
        let ui = self.ui.clone();
        Some(
            div()
                .absolute()
                .left_0()
                .right_0()
                .bottom(px(12. + 76. + 8.))
                .flex()
                .justify_center()
                .child(
                    div()
                        .id("where")
                        .occlude()
                        .flex()
                        .items_center()
                        .gap(px(7.))
                        .py(px(6.))
                        .pl(px(10.))
                        .pr(px(14.))
                        .rounded_full()
                        .bg(accent)
                        .text_color(on)
                        .text_size(px(12.))
                        .font_weight(FontWeight::SEMIBOLD)
                        .shadow(vec![BoxShadow { color: accent.opacity(0.4), offset: point(px(0.), px(6.)), blur_radius: px(20.), spread_radius: px(0.), inset: false }])
                        .cursor_pointer()
                        .on_click(move |_, _, cx| ui.update(cx, |u, cx| u.toggle_panel(crate::ui::SidePanel::Devices, cx)))
                        .child(div().size(px(7.)).rounded_full().bg(on))
                        .child(crate::icons::icon(crate::connect::kind_icon(&following.kind), 13., on))
                        .child(crate::i18n::trf("In riproduzione su {0}", &[&following.name])),
                )
                .into_any_element(),
        )
    }
}

/// The store key of the window's size (this window's own: Tauri does not keep one).
const WINDOW_KEY: &str = "native-window";

/// The window's last size and whether it was maximized.
fn saved_window() -> Option<(f32, f32, bool)> {
    let v = lfly_core::store::get(WINDOW_KEY).ok().flatten()?;
    let n = |k: &str| v.get(k).and_then(|x| x.as_f64()).map(|x| x as f32);
    Some((n("width")?, n("height")?, v.get("maximized").and_then(|m| m.as_bool()).unwrap_or(false)))
}

/// LFLY_ROUTE's pages (`examples/ids.rs` prints real ids).
fn test_route(r: &str) -> Option<Route> {
    let (k, id) = r.split_once(':').unwrap_or((r, ""));
    match k {
        "liked" => Some(Route::Liked),
        "downloads" => Some(Route::Downloads),
        "library" => Some(Route::Library),
        "settings" => Some(Route::Settings),
        "lyrics" => Some(Route::Lyrics),
        "console" => Some(Route::Console),
        "nowplaying" => Some(Route::NowPlaying),
        "album" => Some(Route::Album { id: id.into(), name: String::new() }),
        "playlist" => Some(Route::Playlist { id: id.into(), name: String::new(), local: false }),
        "local" => Some(Route::Playlist { id: id.into(), name: String::new(), local: true }),
        "artist" => Some(Route::Artist { id: id.into(), name: String::new() }),
        _ => None,
    }
}

/// Sets [slot] to [v]; true when it changed (the view must then be told: cached views draw again
/// only when notified).
fn put<T: PartialEq>(slot: &mut T, v: T) -> bool {
    if *slot == v {
        return false;
    }
    *slot = v;
    true
}

/// A child view laid out at its parent's size and redrawn only when it changed.
fn cached<V: Render>(e: &Entity<V>) -> AnyElement {
    e.clone().cached(StyleRefinement::default().size_full()).into_any_element()
}

/// App.svelte's fold: cubic-bezier(0.32, 0.72, 0, 1), 460 ms.
const FOLD: crate::motion::CubicBezier = crate::motion::CubicBezier::new(0.32, 0.72, 0., 1.);

impl Render for Lfly {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        // Modalità Ambient: asked from the menu, opened here (it needs the window); while it is
        // open it is all the window draws.
        if crate::standby::take_request(cx) {
            crate::standby::open(self.core.clone(), self.model.clone(), self.lyrics_data.clone(), self.covers_all.clone(), window, cx);
        }
        // (Nothing left to play: back to the window, as the phone leaves Ambient.)
        if crate::standby::view(cx).is_some() && self.model.read(cx).current().is_none() {
            crate::standby::close(window, cx);
        }
        if let Some(sb) = crate::standby::view(cx) {
            return div().size_full().relative().font_family(self.font.clone()).bg(black()).child(sb).child(self.toasts.clone()).children(self.pair_dialog(cx)).into_any_element();
        }
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
        let reduce = crate::prefs::Prefs::reduce_motion(cx);
        let fold_ms = if reduce { 1. } else { 460. };
        side.retarget(side_target, fold_ms, &FOLD);
        let side_w = side.value(&FOLD);
        let pw = self.panel_w.get_or_insert(Tween::still(panel_target));
        pw.retarget(panel_target, fold_ms, &FOLD);
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
                let p = if reduce { 1. } else { (at.elapsed().as_secs_f64() / 0.26).min(1.) };
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

        self.sidebar.update(cx, |s, cx| {
            if put(&mut s.wide, side_wide) | put(&mut s.can_toggle, !compact && !(sidebar_wide && !side_wide)) {
                cx.notify();
            }
        });
        self.right_panel.update(cx, |r, cx| {
            if put(&mut r.floating, compact) {
                cx.notify();
            }
        });
        let stage_w = win_w - side_w - panel_w;
        let bar_w = (stage_w - 24.).min(1180.);
        // The sizes the views lay themselves out by; a change redraws them.
        self.bar.update(cx, |b, cx| {
            if put(&mut b.width, bar_w) {
                cx.notify();
            }
        });
        self.home.update(cx, |h, cx| {
            if put(&mut h.width, win_w) | put(&mut h.page_width, stage_w) {
                cx.notify();
            }
        });
        self.collection.update(cx, |c, cx| {
            if put(&mut c.width, win_w) {
                cx.notify();
            }
        });
        self.library_page.update(cx, |l, cx| {
            if put(&mut l.page_width, stage_w) {
                cx.notify();
            }
        });
        self.settings.update(cx, |s, cx| {
            if put(&mut s.page_width, stage_w) {
                cx.notify();
            }
        });
        self.search.update(cx, |s, cx| {
            if put(&mut s.width, win_w) | put(&mut s.page_width, stage_w) {
                cx.notify();
            }
        });
        self.artist.update(cx, |a, cx| {
            if put(&mut a.width, win_w) | put(&mut a.page_width, stage_w) | put(&mut a.height, win_h) {
                cx.notify();
            }
        });
        self.now_playing.update(cx, |n, cx| {
            if put(&mut n.width, win_w) | put(&mut n.height, win_h) {
                cx.notify();
            }
        });
        self.lyrics_page.update(cx, |l, cx| {
            if put(&mut l.width, win_w) | put(&mut l.page_width, stage_w) | put(&mut l.height, win_h) {
                cx.notify();
            }
        });
        self.console.update(cx, |c, cx| {
            if put(&mut c.page_width, stage_w) | put(&mut c.height, win_h) {
                cx.notify();
            }
        });

        // The full player flies in from 60 px below and fades (420 ms, cubicOut), and back out.
        let on_np = self.nav.read(cx).route == Route::NowPlaying;
        if on_np != self.np_shown {
            self.np_shown = on_np;
            self.np_motion = Some((std::time::Instant::now(), on_np));
        }
        let np_t = match self.np_motion {
            Some((at, opening)) => {
                let p = if reduce { 1. } else { (at.elapsed().as_secs_f64() / 0.42).min(1.) };
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
        let page_route = self.nav.read(cx).page_route();
        let page_t = match &self.page_at {
            Some((r, _)) if *r != page_route => {
                // The live row's bars belonged to the page before.
                crate::track_row::eq_begin();
                self.page_at = Some((page_route, std::time::Instant::now()));
                Some(0.)
            }
            Some((_, at)) => {
                let p = (at.elapsed().as_secs_f64() / 0.22).min(1.);
                (p < 1. && !reduce).then(|| crate::motion::CUBIC_OUT.at(p) as f32)
            }
            None => {
                // The first page is simply there.
                self.page_at = Some((page_route, std::time::Instant::now() - std::time::Duration::from_secs(1)));
                None
            }
        };
        let page_t = if reduce { None } else { page_t };
        // The live row's bars (track_row::eq_overlay): this view redraws 30 times a second for
        // them, the page under them is not drawn again.
        let accent = self.look.read(cx).scheme.accent.hsla(1.);
        let eq_moving = !reduce && { let m = self.model.read(cx); m.playing() && m.active };
        // Not under the full player: the overlay is not drawn there.
        if eq_moving && self.nav.read(cx).route != Route::NowPlaying && crate::track_row::eq_shown() && !self.eq_tick {
            self.eq_tick = true;
            cx.spawn(async move |this, cx| {
                cx.background_executor().timer(std::time::Duration::from_millis(33)).await;
                let _ = this.update(cx, |this, cx| {
                    this.eq_tick = false;
                    cx.notify();
                });
            })
            .detach();
        }
        if np_t.is_some() || panel_t.is_some() || folding || page_t.is_some() {
            window.request_animation_frame();
        }
        let show_np = on_np || np_t.is_some();
        // Under the full player the shell is not drawn at all: its glass and bar would be
        // drawn, captured and blurred for nothing.
        let show_shell = !on_np || np_t.is_some();

        let (nb, nf) = (self.nav.clone(), self.nav.clone());
        let flowing = crate::flow::Flow::active(self.look.read(cx));
        let mut root = div()
            .size_full()
            .relative()
            .font_family(self.font.clone())
            .bg(bg)
            .text_color(text)
            // The mouse's side buttons move back and forward, as in a browser.
            .on_mouse_down(MouseButton::Navigate(NavigationDirection::Back), move |_, _, cx| nb.update(cx, |n, cx| n.go_back(cx)))
            .on_mouse_down(MouseButton::Navigate(NavigationDirection::Forward), move |_, _, cx| nf.update(cx, |n, cx| n.go_forward(cx)));
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
                    .child(cached(&self.right_panel))
            });
            let mut shell = div()
                .absolute()
                .inset_0()
                .flex()
                .child(div().w(px(side_w)).flex_none().h_full().pt(px(12.)).pb(px(12.)).pl(px(12.)).child(cached(&self.sidebar)))
                .child(
                    div()
                        .relative()
                        .flex_1()
                        .min_w_0()
                        .h_full()
                        .child(match page_t {
                            Some(t) => div().size_full().relative().top(px(8. * (1. - t))).opacity(t).child(page).into_any_element(),
                            None => page,
                        })
                        .child(crate::track_row::eq_overlay(accent, eq_moving))
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
                                .child(div().relative().h_full().w_full().max_w(px(1180.)).occlude().child(cached(&self.bar))),
                        )
                        // PlayerBar.svelte `.where`: the device this computer controls, above the bar.
                        .children(self.where_pill(cx)),
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
                // The scene (scene.rs), painted again only on change; Copertina dinamica flows.
                .when(flowing, |d| d.child(div().absolute().inset_0().child(cached(&self.flow))))
                .when(!flowing, |d| d.children(scene_img.map(|b| div().absolute().inset_0().child(img(b).size_full().object_fit(ObjectFit::Fill)))))
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
                            .occlude()
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
                            .child(div().relative().size_full().occlude().children(panel_el.take())),
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
                    .occlude()
                    .child(self.now_playing.clone()),
            );
        }
        // Over everything: the toast (lower over the full player) and the menu.
        self.toasts.update(cx, |t, _| t.raised = !on_np);
        let origin = content_origin(window);
        self.menu.update(cx, |m, _| {
            m.size = size;
            m.origin = origin;
        });
        root.child(self.dj_island.clone()).child(self.toasts.clone()).child(self.menu.clone()).children(self.pair_dialog(cx)).into_any_element()
    }
}

fn main() {
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info")).init();
    // The language first: the core's messages and the DJ read it too.
    i18n::set_lang(i18n::saved());
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
        // LFLY_SIZE=WxH (test runs): another window size; otherwise the size it had last time.
        let test_size = std::env::var("LFLY_SIZE").ok().and_then(|v| v.split_once('x').and_then(|(w, h)| Some((w.parse().ok()?, h.parse().ok()?))));
        let saved = test_size.is_none().then(saved_window).flatten();
        let (w, h) = test_size.or(saved.map(|s| (s.0, s.1))).unwrap_or((1100., 720.));
        let bounds = Bounds::centered(None, size(px(w.max(720.)), px(h.max(480.))), cx);
        let options = WindowOptions {
            window_bounds: Some(if saved.is_some_and(|s| s.2) { WindowBounds::Maximized(bounds) } else { WindowBounds::Windowed(bounds) }),
            window_min_size: Some(size(px(720.), px(480.))),
            // Not "lfly" while the Tauri app is the shipped one: the two windows stay apart (KWin rules).
            app_id: Some("lfly-native".into()),
            // The title bar is ours (TitleBar draws the buttons); no second one from the system.
            window_decorations: Some(WindowDecorations::Client),
            ..gpui_kit::component::TitleBar::window_options()
        };
        let options = WindowOptions {
            titlebar: options.titlebar.map(|t| TitlebarOptions {
                traffic_light_position: Some(point(px(MAC_TRAFFIC_LIGHTS.0), px(MAC_TRAFFIC_LIGHTS.1))),
                ..t
            }),
            ..options
        };
        let model = cx.new(|cx| PlayerModel::new(core.clone(), updates, cx));
        crate::dj::DjModel::new(core.clone(), cx);
        gpui_kit::open_window(options, cx, |window, cx| cx.new(|cx| Lfly::new(core, model, window, cx)))
            .expect("window");
        cx.activate(true);
    });
}

/// Whether what moves should keep moving: the window is on screen, in front or not (the
/// platform says when it is not presented: minimized, suspended by the compositor). A window
/// only behind the focus, beside the one in use, is still watched.
pub(crate) fn presented(window: &Window) -> bool {
    window.is_visible() || std::env::var_os("LFLY_FORCE_ACTIVE").is_some()
}
