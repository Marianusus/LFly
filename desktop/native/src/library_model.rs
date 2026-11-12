//! The listener's library on this computer (lfly-core::library, the phone's file format): what the
//! sidebar and the library pages show. Read-only for now; the Tauri app may write it meanwhile,
//! so it is read again when asked.

use gpui_kit::*;
use serde_json::Value;

#[derive(Clone, Debug)]
pub struct PlaylistEntry {
    pub id: String,
    pub name: String,
    pub cover_url: Option<String>,
    pub count: usize,
}

#[derive(Default)]
pub struct LibraryModel {
    pub favorites: Vec<Value>,
    pub playlists: Vec<PlaylistEntry>,
    pub history: Vec<Value>,
    raw_playlists: Vec<Value>,
    favorite_ids: std::collections::HashSet<String>,
}

fn str_field(v: &Value, k: &str) -> Option<String> {
    v.get(k).and_then(Value::as_str).filter(|s| !s.is_empty()).map(str::to_string)
}

impl LibraryModel {
    /// A library playlist as saved (the phone's JSON).
    pub fn raw_playlist(&self, id: &str) -> Option<Value> {
        self.raw_playlists.iter().find(|p| p.get("id").and_then(Value::as_str) == Some(id)).cloned()
    }

    pub fn is_favorite(&self, id: &str) -> bool {
        self.favorite_ids.contains(id)
    }

    pub fn new(cx: &mut Context<Self>) -> Self {
        let mut this = Self::default();
        this.reload(cx);
        this
    }

    pub fn reload(&mut self, cx: &mut Context<Self>) {
        cx.spawn(async move |this, cx| {
            let lib = cx
                .background_executor()
                .spawn(async move { lfly_core::library::dir().ok().map(|d| lfly_core::library::load(&d)) })
                .await;
            let Some(lib) = lib else { return };
            let list = |v: Value| v.as_array().cloned().unwrap_or_default();
            let raw_playlists = list(lib.playlists);
            let playlists = raw_playlists
                .clone()
                .into_iter()
                .filter_map(|p| {
                    let tracks = p.get("tracks").and_then(Value::as_array).cloned().unwrap_or_default();
                    Some(PlaylistEntry {
                        id: str_field(&p, "id")?,
                        name: str_field(&p, "name").unwrap_or_default(),
                        cover_url: str_field(&p, "coverUrl").or_else(|| tracks.first().and_then(|t| str_field(t, "coverUrl"))),
                        count: tracks.len(),
                    })
                })
                .collect();
            let _ = this.update(cx, |this, cx| {
                this.favorites = list(lib.favorites);
                this.favorite_ids = this.favorites.iter().filter_map(|t| str_field(t, "id")).collect();
                this.raw_playlists = raw_playlists;
                this.history = list(lib.history);
                this.playlists = playlists;
                cx.notify();
            });
        })
        .detach();
    }
}
