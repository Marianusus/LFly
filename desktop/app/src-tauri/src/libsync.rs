//! Library sync with the listener's other devices over LFly Connect: the work is lfly-core's
//! `libsync` (the GPUI app runs the same); this gives it the window's devices and account, turns
//! what it reports into window events and exposes it to the Settings page.

use std::sync::{Arc, OnceLock};

use lfly_core::libsync::{Event, LibSync, Link, Status};
use serde_json::Value;
use tauri::{AppHandle, Emitter, Manager};

static SYNC: OnceLock<Arc<LibSync>> = OnceLock::new();

/// Who the window is on LFly Connect and the devices it sees; None while signed out.
fn link(app: &AppHandle) -> Option<Link> {
    let secret = app.try_state::<lfly_yt::YtMusic>().and_then(|yt| yt.account()).and_then(|a| a.connect_secret())?;
    let core = app.try_state::<crate::Core>()?;
    let me = core.me.read().unwrap().clone();
    let devices = core.discovery.as_ref().map(|d| d.devices()).unwrap_or_default();
    Some(Link { me, secret, devices })
}

fn sync(app: &AppHandle) -> &'static Arc<LibSync> {
    SYNC.get_or_init(|| {
        let (who, events) = (app.clone(), app.clone());
        Arc::new(LibSync::new(
            move || link(&who),
            move |e| match e {
                Event::LibraryChanged => {
                    let _ = events.emit("library:changed", ());
                }
                Event::Status(status) => {
                    let _ = events.emit("libsync:status", status);
                }
            },
        ))
    })
}

/// A phone of the account offers its library (we are its host): merge, keep, answer.
pub fn answer(app: &AppHandle, peer: &str, offered: Value) -> Option<Value> {
    sync(app).answer(peer, offered)
}

pub fn start(app: AppHandle) {
    tauri::async_runtime::spawn(sync(&app).clone().run());
}

/// The library changed here: offer it shortly, once the edits settle.
pub fn changed(app: &AppHandle) {
    tauri::async_runtime::spawn(sync(app).changed());
}

#[tauri::command]
pub fn libsync_status(app: AppHandle) -> Status {
    sync(&app).status()
}

#[tauri::command]
pub async fn libsync_set_enabled(app: AppHandle, enabled: bool) -> Result<Status, String> {
    let sync = sync(&app);
    sync.set_enabled(enabled);
    if enabled {
        sync.sync_all().await;
    }
    Ok(sync.status())
}

/// Syncs now; returns how many devices answered.
#[tauri::command]
pub async fn libsync_now(app: AppHandle) -> Result<usize, String> {
    let sync = sync(&app);
    if !sync.enabled() {
        return Err("La sincronizzazione è spenta".into());
    }
    Ok(sync.sync_all().await)
}
