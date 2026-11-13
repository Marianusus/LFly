//! Songs kept on this computer (lfly-core's downloads), wired to the window: what happens comes
//! back as `downloads:progress` and `downloads:changed` events.

use std::sync::Arc;

use lfly_connect::protocol::TrackRef;
use lfly_core::downloads::{self, DownloadEvent, DownloadList};
pub use lfly_core::downloads::Downloads;
use lfly_yt::YtMusic;
use serde::Serialize;
use tauri::{AppHandle, Emitter, State};

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct Progress {
    id: String,
    /// 0..1, or -1 when it failed.
    done: f32,
}

pub fn file_for(id: &str) -> Option<std::path::PathBuf> {
    downloads::file_for(id)
}

pub fn start(app: AppHandle, yt: YtMusic) -> Option<Arc<Downloads>> {
    downloads::start(yt, tauri::async_runtime::handle().inner().clone(), move |e| {
        let _ = match e {
            DownloadEvent::Progress { id, done } => app.emit("downloads:progress", Progress { id, done }),
            DownloadEvent::Changed => app.emit("downloads:changed", ()),
        };
    })
}

#[tauri::command]
pub fn downloads_list(d: State<'_, Arc<Downloads>>) -> DownloadList {
    d.list()
}

#[tauri::command]
pub fn downloads_add(d: State<'_, Arc<Downloads>>, tracks: Vec<TrackRef>) {
    d.inner().add(tracks);
}

#[tauri::command]
pub fn downloads_remove(d: State<'_, Arc<Downloads>>, ids: Vec<String>) {
    d.remove(&ids);
}
