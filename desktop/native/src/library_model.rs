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

    /// The heart (library.svelte.ts `toggleFavorite`): first in the list when added, saved to
    /// favorites.json as the Tauri app saves it. Only ever from a click. The file is read again
    /// first: the Tauri app may have changed it since this window read it.
    pub fn toggle_favorite(&mut self, track: Value, cx: &mut Context<Self>) -> Option<bool> {
        let id = str_field(&track, "id")?;
        let liked = !self.favorite_ids.contains(&id);
        let apply = move |list: &mut Vec<Value>| {
            list.retain(|t| str_field(t, "id").as_deref() != Some(id.as_str()));
            if liked {
                list.insert(0, track.clone());
            }
        };
        // At once on screen, then on disk.
        apply(&mut self.favorites);
        self.favorite_ids = self.favorites.iter().filter_map(|t| str_field(t, "id")).collect();
        cx.notify();
        cx.spawn(async move |this, cx| {
            let saved = cx
                .background_executor()
                .spawn(async move {
                    let dir = lfly_core::library::dir()?;
                    let mut list = lfly_core::library::load(&dir).favorites.as_array().cloned().unwrap_or_default();
                    apply(&mut list);
                    lfly_core::library::save(&dir, "favorites.json", &Value::Array(list.clone()))?;
                    Ok::<_, String>(list)
                })
                .await;
            match saved {
                Ok(list) => {
                    let _ = this.update(cx, |this, cx| {
                        this.favorite_ids = list.iter().filter_map(|t| str_field(t, "id")).collect();
                        this.favorites = list;
                        cx.notify();
                    });
                }
                Err(e) => log::warn!("favorites.json: {e}"),
            }
        })
        .detach();
        Some(liked)
    }

    /// Changes playlists.json: read again (the Tauri app may have written it), [apply], saved,
    /// then shown; [done] gets what [apply] returned. Only ever from a click.
    fn edit_playlists<R: Send + 'static>(
        &mut self,
        apply: impl FnOnce(&mut Vec<Value>) -> R + Send + 'static,
        done: impl FnOnce(R, &mut App) + 'static,
        cx: &mut Context<Self>,
    ) {
        cx.spawn(async move |this, cx| {
            let saved = cx
                .background_executor()
                .spawn(async move {
                    let dir = lfly_core::library::dir()?;
                    let mut list = lfly_core::library::load(&dir).playlists.as_array().cloned().unwrap_or_default();
                    let r = apply(&mut list);
                    lfly_core::library::save(&dir, "playlists.json", &Value::Array(list))?;
                    Ok::<_, String>(r)
                })
                .await;
            match saved {
                Ok(r) => {
                    let _ = this.update(cx, |this, cx| this.reload(cx));
                    let _ = cx.update(|cx| done(r, cx));
                }
                Err(e) => log::warn!("playlists.json: {e}"),
            }
        })
        .detach();
    }

    /// library.svelte.ts `createPlaylist`: first in the list; [done] gets its id and name.
    pub fn create_playlist(&mut self, name: String, tracks: Vec<Value>, done: impl FnOnce(String, String, &mut App) + 'static, cx: &mut Context<Self>) {
        let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_millis() as u64);
        let id = uuid::Uuid::new_v4().to_string();
        let name = if name.trim().is_empty() { "La mia playlist".to_string() } else { name.trim().to_string() };
        let p = serde_json::json!({ "id": id, "name": name, "tracks": tracks, "schemaVersion": 2, "createdAt": now, "updatedAt": now });
        self.edit_playlists(move |list| list.insert(0, p), move |_, cx| done(id, name, cx), cx);
    }

    /// `addToPlaylist`: the songs not in it yet, at its end; [done] gets how many went in.
    pub fn add_to_playlist(&mut self, id: String, tracks: Vec<Value>, done: impl FnOnce(usize, &mut App) + 'static, cx: &mut Context<Self>) {
        let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_millis() as u64);
        self.edit_playlists(
            move |list| {
                let Some(p) = list.iter_mut().find(|p| p.get("id").and_then(Value::as_str) == Some(id.as_str())) else { return 0 };
                let mut have: Vec<Value> = p.get("tracks").and_then(Value::as_array).cloned().unwrap_or_default();
                let ids: std::collections::HashSet<String> = have.iter().filter_map(|t| str_field(t, "id")).collect();
                let fresh: Vec<Value> = tracks.into_iter().filter(|t| str_field(t, "id").is_some_and(|i| !ids.contains(&i))).collect();
                let n = fresh.len();
                have.extend(fresh);
                p["tracks"] = Value::Array(have);
                p["updatedAt"] = Value::from(now);
                n
            },
            done,
            cx,
        );
    }

    /// `removeFromPlaylist`: the song at [index], if it is still [track_id] there.
    pub fn remove_from_playlist(&mut self, id: String, index: usize, track_id: String, cx: &mut Context<Self>) {
        let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_millis() as u64);
        self.edit_playlists(
            move |list| {
                if let Some(p) = list.iter_mut().find(|p| p.get("id").and_then(Value::as_str) == Some(id.as_str())) {
                    if let Some(tracks) = p.get_mut("tracks").and_then(Value::as_array_mut) {
                        if tracks.get(index).and_then(|t| str_field(t, "id")).as_deref() == Some(track_id.as_str()) {
                            tracks.remove(index);
                        }
                    }
                    p["updatedAt"] = Value::from(now);
                }
            },
            |_, _| {},
            cx,
        );
    }

    /// library.svelte.ts `played`: newest first, no repeats, 500 songs, like the phone. The file
    /// is read again first (the Tauri app adds to it too).
    pub fn played(&mut self, track: Value, cx: &mut Context<Self>) {
        let Some(id) = str_field(&track, "id") else { return };
        cx.spawn(async move |this, cx| {
            let saved = cx
                .background_executor()
                .spawn(async move {
                    let dir = lfly_core::library::dir()?;
                    let mut list = lfly_core::library::load(&dir).history.as_array().cloned().unwrap_or_default();
                    list.retain(|t| str_field(t, "id").as_deref() != Some(id.as_str()));
                    list.insert(0, track);
                    list.truncate(500);
                    lfly_core::library::save(&dir, "history.json", &Value::Array(list.clone()))?;
                    Ok::<_, String>(list)
                })
                .await;
            match saved {
                Ok(list) => {
                    let _ = this.update(cx, |this, cx| {
                        this.history = list;
                        cx.notify();
                    });
                }
                Err(e) => log::warn!("history.json: {e}"),
            }
        })
        .detach();
    }

    /// `clearHistory`: history.json emptied. Only ever from a confirmed click.
    pub fn clear_history(&mut self, cx: &mut Context<Self>) {
        self.history.clear();
        cx.notify();
        cx.background_executor()
            .spawn(async move {
                if let Err(e) = lfly_core::library::dir().and_then(|d| lfly_core::library::save(&d, "history.json", &Value::Array(Vec::new()))) {
                    log::warn!("history.json: {e}");
                }
            })
            .detach();
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
