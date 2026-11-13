//! The system's media controls (lfly-core's media), wired to the window: the presses come back
//! as `media:command` events, and `media_update` tells the controls and Discord what plays.

use lfly_core::media::{self, MediaCommand, NowShowing};
use tauri::{AppHandle, Emitter, Manager};

pub struct Media(media::Media);

pub fn start(app: &AppHandle) -> Media {
    #[cfg(target_os = "windows")]
    let hwnd = app
        .get_webview_window("main")
        .and_then(|w| w.hwnd().ok())
        .map(|h| h.0 as *mut std::ffi::c_void);
    #[cfg(not(target_os = "windows"))]
    let hwnd = None;

    let handle = app.clone();
    Media(media::Media::start(hwnd, move |c| {
        let (action, value) = match c {
            MediaCommand::Run(action, value) => (action, value),
            MediaCommand::SeekBy(by) => ("seek_by", by),
            MediaCommand::Raise => {
                if let Some(w) = handle.get_webview_window("main") {
                    let _ = w.unminimize();
                    let _ = w.set_focus();
                }
                return;
            }
        };
        let _ = handle.emit("media:command", serde_json::json!({ "action": action, "value": value }));
    }))
}

#[tauri::command]
pub fn media_update(media: tauri::State<'_, Media>, discord: tauri::State<'_, lfly_core::discord::Discord>, now: NowShowing) {
    discord.show(media::listening(&now, media::unix_ms()));
    media.0.update(&now);
}
