//! A Spotify playlist, album or song brought into LFly from its link (lfly-core::spotify); this
//! side only reports the progress to the window.

use lfly_core::spotify::Imported;
use lfly_yt::YtMusic;
use serde::Serialize;
use tauri::{AppHandle, Emitter, State};

#[derive(Clone, Serialize)]
struct Progress {
    done: usize,
    total: usize,
}

#[tauri::command]
pub async fn spotify_import(app: AppHandle, yt: State<'_, YtMusic>, link: String) -> Result<Imported, String> {
    let yt = yt.inner().clone();
    lfly_core::spotify::import(&yt, &link, move |done, total| {
        let _ = app.emit("spotify:progress", Progress { done, total });
    })
    .await
}
