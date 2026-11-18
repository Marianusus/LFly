//! The macOS menu bar, in the order and with the items a Mac app has (HIG › The menu bar): LFly,
//! Archivio, Modifica, Vista, then LFly's own Riproduzione and Vai, then Finestra; and the Dock
//! menu's playback controls, as Music has them. Elsewhere the window draws its own buttons and
//! there is no menu bar to fill.

use gpui_kit::component::input::{Copy, Cut, Paste, Redo, SelectAll, ShowCharacterPalette, Undo};
use gpui_kit::*;

use crate::i18n::tr;
use crate::nav::{Nav, Route};
use crate::player_model::PlayerModel;
use crate::settings_page::{Section, SettingsPage};
use crate::ui::Ui;
use lfly_connect::protocol;

gpui_kit::actions!(
    lfly,
    [
        About,
        Quit,
        HideApp,
        HideOthers,
        ShowAll,
        CloseWindow,
        Minimize,
        Zoom,
        BringAllToFront,
        Fullscreen,
        ToggleSidebar,
        PlayPause,
        NextTrack,
        PreviousTrack,
        VolumeUp,
        VolumeDown,
        OpenSettings,
        GoHome,
        GoSearch,
        GoLibrary,
        GoConsole,
    ]
);

struct MenuState {
    ui: Entity<Ui>,
    model: Entity<PlayerModel>,
    _watch: Vec<Subscription>,
}
impl Global for MenuState {}

/// The actions behind the menus and their keys; on macOS the menus themselves too. Linux and
/// Windows have no menu bar, so the same actions get Ctrl keys there (Quit, Settings, the pages,
/// full screen, the sidebar).
pub fn install(nav: Entity<Nav>, model: Entity<PlayerModel>, ui: Entity<Ui>, settings: Entity<SettingsPage>, cx: &mut App) {
    cx.on_action(|_: &Quit, cx| cx.quit());
    cx.on_action(|_: &HideApp, cx| cx.hide());
    cx.on_action(|_: &HideOthers, cx| cx.hide_other_apps());
    cx.on_action(|_: &ShowAll, cx| cx.unhide_other_apps());
    // Closing the window keeps the music going, as Music does: the app hides and comes back
    // from the Dock.
    cx.on_action(|_: &CloseWindow, cx| cx.hide());
    cx.on_action(|_: &Minimize, cx| on_window(cx, |w| w.minimize_window()));
    cx.on_action(|_: &Zoom, cx| on_window(cx, |w| w.zoom_window()));
    cx.on_action(|_: &BringAllToFront, cx| cx.activate(true));
    cx.on_action(|_: &Fullscreen, cx| on_window(cx, |w| w.toggle_fullscreen()));
    let u = ui.clone();
    cx.on_action(move |_: &ToggleSidebar, cx| u.update(cx, |u, cx| u.toggle_sidebar(cx)));
    let play = |cmd: &'static str, value: i64| {
        let model = model.clone();
        move |cx: &mut App| {
            let pm = model.read(cx);
            if pm.current().is_some() {
                pm.command(cmd, value);
            }
        }
    };
    let (toggle, next, prev) = (play(protocol::CMD_TOGGLE, 0), play(protocol::CMD_NEXT, 0), play(protocol::CMD_PREVIOUS, 0));
    let (up, down) = (play(protocol::CMD_VOLUME_STEP, 1), play(protocol::CMD_VOLUME_STEP, -1));
    cx.on_action(move |_: &PlayPause, cx| toggle(cx));
    cx.on_action(move |_: &NextTrack, cx| next(cx));
    cx.on_action(move |_: &PreviousTrack, cx| prev(cx));
    cx.on_action(move |_: &VolumeUp, cx| up(cx));
    cx.on_action(move |_: &VolumeDown, cx| down(cx));
    let go = |route: Route, section: Option<Section>| {
        let (nav, settings) = (nav.clone(), settings.clone());
        move |cx: &mut App| {
            if let Some(s) = section {
                settings.update(cx, |p, cx| {
                    p.section = s;
                    cx.notify();
                });
            }
            nav.update(cx, |n, cx| n.go(route.clone(), cx));
            cx.activate(true);
        }
    };
    let about = go(Route::Settings, Some(Section::About));
    let open_settings = go(Route::Settings, None);
    let (home, search, library, console) = (go(Route::Home, None), go(Route::Search, None), go(Route::Library, None), go(Route::Console, None));
    cx.on_action(move |_: &About, cx| about(cx));
    cx.on_action(move |_: &OpenSettings, cx| open_settings(cx));
    cx.on_action(move |_: &GoHome, cx| home(cx));
    cx.on_action(move |_: &GoSearch, cx| search(cx));
    cx.on_action(move |_: &GoLibrary, cx| library(cx));
    cx.on_action(move |_: &GoConsole, cx| console(cx));
    if !cfg!(target_os = "macos") {
        // The same keys as the Mac's, with Ctrl; F11 is full screen there.
        cx.bind_keys([
            KeyBinding::new("ctrl-q", Quit, None),
            KeyBinding::new("f11", Fullscreen, None),
            KeyBinding::new("ctrl-alt-s", ToggleSidebar, None),
            KeyBinding::new("ctrl-,", OpenSettings, None),
            KeyBinding::new("ctrl-1", GoHome, None),
            KeyBinding::new("ctrl-2", GoSearch, None),
            KeyBinding::new("ctrl-3", GoLibrary, None),
            KeyBinding::new("ctrl-4", GoConsole, None),
        ]);
        return;
    }
    // Only the keys the window does not read itself (Space, ⌘K, ⌘←/→, ⌘↑/↓ stay there, so a
    // press never acts twice). Cut, Copy, Paste, Undo and Select All keep gpui-kit's own keys.
    cx.bind_keys([
        KeyBinding::new("cmd-q", Quit, None),
        KeyBinding::new("cmd-h", HideApp, None),
        KeyBinding::new("alt-cmd-h", HideOthers, None),
        KeyBinding::new("cmd-w", CloseWindow, None),
        KeyBinding::new("cmd-m", Minimize, None),
        KeyBinding::new("ctrl-cmd-f", Fullscreen, None),
        KeyBinding::new("ctrl-cmd-s", ToggleSidebar, None),
        KeyBinding::new("cmd-,", OpenSettings, None),
        KeyBinding::new("cmd-1", GoHome, None),
        KeyBinding::new("cmd-2", GoSearch, None),
        KeyBinding::new("cmd-3", GoLibrary, None),
        KeyBinding::new("cmd-4", GoConsole, None),
    ]);
    // A click on the Dock icon unhides the app by itself (AppKit).
    // The menus follow what they name: the sidebar's state, and playing or paused in the Dock.
    let watch = vec![cx.observe(&ui, |_, cx| set_menus(cx)), cx.observe(&model, |_, cx| set_dock(cx))];
    cx.set_global(MenuState { ui, model, _watch: watch });
    set_menus(cx);
}

fn item(name: &str, action: impl Action) -> MenuItem {
    MenuItem::action(name.to_string(), action)
}

/// The menus in the current language (again after it changes in Settings).
pub fn set_menus(cx: &mut App) {
    if !cfg!(target_os = "macos") {
        return;
    }
    let wide = cx.try_global::<MenuState>().is_none_or(|m| m.ui.read(cx).sidebar_wide);
    cx.set_menus([
        Menu::new("LFly").items([
            item(tr("Informazioni su LFly"), About),
            MenuItem::separator(),
            item(tr("Impostazioni…"), OpenSettings),
            MenuItem::separator(),
            MenuItem::os_submenu(tr("Servizi").to_string(), SystemMenuType::Services),
            MenuItem::separator(),
            item(tr("Nascondi LFly"), HideApp),
            item(tr("Nascondi altre"), HideOthers),
            item(tr("Mostra tutte"), ShowAll),
            MenuItem::separator(),
            item(tr("Esci da LFly"), Quit),
        ]),
        Menu::new(tr("Archivio").to_string()).items([item(tr("Chiudi finestra"), CloseWindow)]),
        Menu::new(tr("Modifica").to_string()).items([
            item(tr("Annulla#modifica"), Undo),
            item(tr("Ripristina"), Redo),
            MenuItem::separator(),
            MenuItem::os_action(tr("Taglia").to_string(), Cut, OsAction::Cut),
            MenuItem::os_action(tr("Copia").to_string(), Copy, OsAction::Copy),
            MenuItem::os_action(tr("Incolla").to_string(), Paste, OsAction::Paste),
            MenuItem::os_action(tr("Seleziona tutto").to_string(), SelectAll, OsAction::SelectAll),
            MenuItem::separator(),
            item(tr("Emoji e simboli"), ShowCharacterPalette),
        ]),
        Menu::new(tr("Vista").to_string()).items([
            item(if wide { tr("Comprimi la barra laterale") } else { tr("Espandi la barra laterale") }, ToggleSidebar),
            MenuItem::separator(),
            item(tr("Schermo intero"), Fullscreen),
        ]),
        Menu::new(tr("Riproduzione").to_string()).items([
            item(tr("Play/Pausa"), PlayPause),
            item(tr("Brano successivo"), NextTrack),
            item(tr("Brano precedente"), PreviousTrack),
            MenuItem::separator(),
            item(tr("Alza il volume"), VolumeUp),
            item(tr("Abbassa il volume"), VolumeDown),
        ]),
        Menu::new(tr("Vai").to_string()).items([
            item(tr("Home"), GoHome),
            item(tr("Cerca"), GoSearch),
            item(tr("Libreria"), GoLibrary),
            item(tr("Console"), GoConsole),
        ]),
        Menu::new(tr("Finestra").to_string()).items([
            item(tr("Riduci a icona"), Minimize),
            item(tr("Zoom"), Zoom),
            MenuItem::separator(),
            item(tr("Porta tutto in primo piano"), BringAllToFront),
        ]),
    ]);
    set_dock(cx);
}

/// The Dock menu, as Music's: what plays, then the controls.
fn set_dock(cx: &mut App) {
    if !cfg!(target_os = "macos") {
        return;
    }
    static SHOWN: std::sync::Mutex<Option<(String, bool)>> = std::sync::Mutex::new(None);
    let Some(state) = cx.try_global::<MenuState>() else { return };
    let pm = state.model.read(cx);
    let now = pm.current().map(|t| (format!("{} – {}", t.title, t.artist), pm.state.as_ref().is_some_and(|s| s.playing)));
    // The player notifies four times a second: the menu is built again only when it changes.
    {
        let mut shown = SHOWN.lock().unwrap();
        if *shown == now {
            return;
        }
        shown.clone_from(&now);
    }
    let Some((title, playing)) = now else {
        cx.set_dock_menu(Vec::new());
        return;
    };
    cx.set_dock_menu(vec![
        MenuItem::action(title, BringAllToFront),
        MenuItem::separator(),
        item(if playing { tr("Pausa") } else { tr("Riproduci") }, PlayPause),
        item(tr("Brano successivo"), NextTrack),
        item(tr("Brano precedente"), PreviousTrack),
    ]);
}

fn on_window(cx: &mut App, f: impl FnOnce(&mut Window)) {
    if let Some(handle) = cx.active_window().or_else(|| cx.windows().into_iter().next()) {
        let _ = handle.update(cx, |_, window, _| f(window));
    }
}
