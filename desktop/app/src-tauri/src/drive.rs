//! Sync with the phone through the listener's Google Drive: the work is lfly-core's `drive` (the
//! GPUI app runs the same); this turns what it reports into window events and exposes it to the
//! Settings page.

use std::sync::Arc;

use lfly_core::drive::{Drive, Event, Status};
use tauri::{AppHandle, Emitter, Manager, State};

/// Syncs at start, every few minutes, and shortly after the library changes here.
pub fn start(app: AppHandle) -> Arc<Drive> {
    let drive = Arc::new(Drive::new(move |e| match e {
        Event::LibraryChanged => {
            let _ = app.emit("library:changed", ());
        }
        Event::Status(status) => {
            let _ = app.emit("drive:status", status);
        }
    }));
    tauri::async_runtime::spawn(drive.clone().run());
    drive
}

/// The library was saved here: sync a little later, once the edits settle.
pub fn changed(app: &AppHandle) {
    tauri::async_runtime::spawn(app.state::<Arc<Drive>>().changed());
}

#[tauri::command]
pub async fn drive_status(drive: State<'_, Arc<Drive>>) -> Result<Status, String> {
    Ok(drive.status().await)
}

#[tauri::command]
pub async fn drive_sign_in(drive: State<'_, Arc<Drive>>) -> Result<Status, String> {
    drive.sign_in().await?;
    drive.sync().await?;
    Ok(drive.status().await)
}

#[tauri::command]
pub async fn drive_sync(drive: State<'_, Arc<Drive>>) -> Result<bool, String> {
    drive.sync().await
}

#[tauri::command]
pub async fn drive_set_enabled(drive: State<'_, Arc<Drive>>, enabled: bool) -> Result<Status, String> {
    drive.set_enabled(enabled).await?;
    if enabled {
        drive.sync().await?;
    }
    Ok(drive.status().await)
}

#[tauri::command]
pub async fn drive_sign_out(drive: State<'_, Arc<Drive>>) -> Result<Status, String> {
    drive.sign_out().await?;
    Ok(drive.status().await)
}
