//! Library sync with the listener's other devices over LFly Connect, on the same network and the
//! same account: no cloud, nothing to set up. This computer opens a short link to each phone of
//! the account it sees (an outgoing link, so a firewall here does not get in the way), offers its
//! library and takes back the merged one. A phone that opens the link to us is answered the same.
//!
//! The merge is the three-way one of `drive.rs` (and the phone's `LibrarySync.kt`): each device
//! keeps, per peer, which songs and playlists they agreed on last time (the base), so deletions
//! travel too.

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use serde_json::Value;
use tauri::{AppHandle, Emitter, Manager};

use crate::drive::{base_of, merge, Base, Snapshot};
use crate::library;

/// How often the computer offers its library to the phones it sees.
const EVERY: Duration = Duration::from_secs(90);

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct Saved {
    enabled: bool,
    /// What this computer and each peer agreed on last time, by peer id.
    peers: HashMap<String, Base>,
    /// Last sync with each peer, in seconds.
    last: HashMap<String, u64>,
}

impl Default for Saved {
    fn default() -> Self {
        Saved { enabled: true, peers: HashMap::new(), last: HashMap::new() }
    }
}

static STATE: Mutex<Option<Saved>> = Mutex::new(None);
/// One merge at a time, offered or answered: both read and write the library files.
static MERGING: Mutex<()> = Mutex::new(());

fn path() -> Option<PathBuf> {
    Some(dirs::config_dir()?.join("lfly").join("library-sync.json"))
}

fn with_state<T>(f: impl FnOnce(&mut Saved) -> T) -> T {
    let mut guard = STATE.lock().unwrap();
    let state = guard.get_or_insert_with(|| path().and_then(|p| std::fs::read(p).ok()).and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default());
    f(state)
}

fn save() {
    let Some(p) = path() else { return };
    let bytes = with_state(|s| serde_json::to_vec(s).unwrap_or_default());
    let _ = std::fs::create_dir_all(p.parent().unwrap());
    let tmp = p.with_extension("json.tmp");
    if std::fs::write(&tmp, bytes).is_ok() {
        let _ = std::fs::rename(tmp, p);
    }
}

fn now_s() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_secs())
}

fn local() -> Result<Snapshot, String> {
    let lib = library::load(&library::dir()?);
    let list = |v: Value| v.as_array().cloned().unwrap_or_default();
    Ok(Snapshot { favorites: list(lib.favorites), playlists: list(lib.playlists), history: list(lib.history) })
}

/// Writes `merged` here if it differs from `local`; tells the window.
fn apply(app: &AppHandle, local: &Snapshot, merged: &Snapshot) -> Result<bool, String> {
    if merged == local {
        return Ok(false);
    }
    let dir = library::dir()?;
    library::save(&dir, library::FAVORITES, &Value::Array(merged.favorites.clone()))?;
    library::save(&dir, library::PLAYLISTS, &Value::Array(merged.playlists.clone()))?;
    library::save(&dir, library::HISTORY, &Value::Array(merged.history.clone()))?;
    let _ = app.emit("library:changed", ());
    Ok(true)
}

pub fn enabled() -> bool {
    with_state(|s| s.enabled)
}

/// A phone of the account offers its library (we are its host): merge, keep, answer.
pub fn answer(app: &AppHandle, peer: &str, offered: Value) -> Option<Value> {
    if !enabled() {
        return None;
    }
    let _one = MERGING.lock().unwrap();
    let remote: Snapshot = serde_json::from_value(offered).ok()?;
    let mine = local().ok()?;
    let base = with_state(|s| s.peers.get(peer).cloned().unwrap_or_default());
    let merged = merge(&base, &mine, &remote);
    if let Err(e) = apply(app, &mine, &merged) {
        log::warn!("library sync from {peer}: {e}");
        return None;
    }
    with_state(|s| {
        s.peers.insert(peer.to_string(), base_of(&merged));
        s.last.insert(peer.to_string(), now_s());
    });
    save();
    log::info!("Library merged with an offer from {peer}");
    serde_json::to_value(&merged).ok()
}

/// Offers the library to every phone of the account in sight.
pub async fn sync_all(app: &AppHandle) -> usize {
    if !enabled() {
        return 0;
    }
    let Some(secret) = app.try_state::<lfly_yt::YtMusic>().and_then(|yt| yt.account()).and_then(|a| a.connect_secret()) else {
        return 0;
    };
    let core = app.state::<crate::Core>();
    let me = core.me.read().unwrap().clone();
    let peers: Vec<_> = core.discovery.as_ref().map(|d| d.devices()).unwrap_or_default().into_iter().filter(|d| d.same_account && d.id != me.id).collect();
    let mut synced = 0;
    for peer in peers {
        let Ok(offer) = local().and_then(|l| serde_json::to_value(&l).map_err(|e| e.to_string())) else { continue };
        match lfly_connect::library::exchange(me.clone(), &secret, &peer, offer).await {
            Ok(merged) => {
                let Ok(merged) = serde_json::from_value::<Snapshot>(merged) else { continue };
                let _one = MERGING.lock().unwrap();
                // Edits made here while the link was open are merged on top.
                let Ok(now) = local() else { continue };
                let base = with_state(|s| s.peers.get(&peer.id).cloned().unwrap_or_default());
                let last = merge(&base, &now, &merged);
                if let Err(e) = apply(app, &now, &last) {
                    log::warn!("library sync with '{}': {e}", peer.name);
                    continue;
                }
                with_state(|s| {
                    s.peers.insert(peer.id.clone(), base_of(&merged));
                    s.last.insert(peer.id.clone(), now_s());
                });
                save();
                synced += 1;
                log::info!("Library synced with '{}'", peer.name);
            }
            Err(e) => log::info!("library sync with '{}': {e}", peer.name),
        }
    }
    if synced > 0 {
        let _ = app.emit("libsync:status", status());
    }
    synced
}

pub fn start(app: AppHandle) {
    tauri::async_runtime::spawn(async move {
        tokio::time::sleep(Duration::from_secs(6)).await;
        loop {
            sync_all(&app).await;
            tokio::time::sleep(EVERY).await;
        }
    });
}

/// The library changed here: offer it shortly, once the edits settle.
pub fn changed(app: &AppHandle) {
    static TICKET: AtomicU64 = AtomicU64::new(0);
    let ticket = TICKET.fetch_add(1, Ordering::SeqCst) + 1;
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        tokio::time::sleep(Duration::from_secs(5)).await;
        if TICKET.load(Ordering::SeqCst) == ticket {
            sync_all(&app).await;
        }
    });
}

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct Status {
    enabled: bool,
    /// Most recent sync with any device, in seconds.
    last_sync: Option<u64>,
}

fn status() -> Status {
    with_state(|s| Status { enabled: s.enabled, last_sync: s.last.values().max().copied() })
}

#[tauri::command]
pub fn libsync_status() -> Status {
    status()
}

#[tauri::command]
pub async fn libsync_set_enabled(app: AppHandle, enabled: bool) -> Result<Status, String> {
    with_state(|s| s.enabled = enabled);
    save();
    if enabled {
        sync_all(&app).await;
    }
    Ok(status())
}

/// Syncs now; returns how many devices answered.
#[tauri::command]
pub async fn libsync_now(app: AppHandle) -> Result<usize, String> {
    if !enabled() {
        return Err("La sincronizzazione è spenta".into());
    }
    Ok(sync_all(&app).await)
}
