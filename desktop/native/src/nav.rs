//! Where the window is (Tauri: nav.svelte.ts): a route and its history, back and forward.

use gpui_kit::*;

#[derive(Clone, Debug, PartialEq)]
#[allow(dead_code)]
pub enum Route {
    Home,
    Search,
    Library,
    Liked,
    Downloads,
    Playlist { id: String, name: String, local: bool },
    Artist { id: String, name: String },
    Album { id: String, name: String },
    Lyrics,
    Settings,
}

impl Route {
    /// The sidebar item it belongs to (Sidebar.svelte `keyOf`).
    pub fn key(&self) -> String {
        match self {
            Route::Playlist { id, .. } => format!("playlist:{id}"),
            Route::Home => "home".into(),
            Route::Search => "search".into(),
            Route::Library => "library".into(),
            Route::Liked => "liked".into(),
            Route::Downloads => "downloads".into(),
            Route::Artist { .. } => "artist".into(),
            Route::Album { .. } => "album".into(),
            Route::Lyrics => "lyrics".into(),
            Route::Settings => "settings".into(),
        }
    }
}

pub struct Nav {
    back: Vec<Route>,
    forward: Vec<Route>,
    pub route: Route,
}

impl Nav {
    pub fn new() -> Self {
        Self { back: Vec::new(), forward: Vec::new(), route: Route::Home }
    }

    pub fn go(&mut self, route: Route, cx: &mut Context<Self>) {
        if route == self.route {
            return;
        }
        self.back.push(std::mem::replace(&mut self.route, route));
        self.forward.clear();
        cx.notify();
    }

    pub fn can_back(&self) -> bool {
        !self.back.is_empty()
    }

    pub fn can_forward(&self) -> bool {
        !self.forward.is_empty()
    }

    pub fn go_back(&mut self, cx: &mut Context<Self>) {
        if let Some(r) = self.back.pop() {
            self.forward.push(std::mem::replace(&mut self.route, r));
            cx.notify();
        }
    }

    pub fn go_forward(&mut self, cx: &mut Context<Self>) {
        if let Some(r) = self.forward.pop() {
            self.back.push(std::mem::replace(&mut self.route, r));
            cx.notify();
        }
    }
}
