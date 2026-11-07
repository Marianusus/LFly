//! The system's media controls: keyboard media keys, the Linux MPRIS widgets (KDE, GNOME), the
//! Windows volume overlay and macOS Now Playing.
//!
//! The window tells this what it shows (`media_update`), whether the music plays here or on a
//! phone this computer controls, and gets the presses back as `media:command` events.

use std::sync::Mutex;
use std::time::Duration;

use serde::Deserialize;
use souvlaki::{MediaControlEvent, MediaControls, MediaMetadata, MediaPlayback, MediaPosition, PlatformConfig, SeekDirection};
use tauri::{AppHandle, Emitter, Manager};

pub struct Media(Mutex<Option<MediaControls>>);

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NowShowing {
    title: Option<String>,
    artist: Option<String>,
    album: Option<String>,
    cover_url: Option<String>,
    duration_ms: Option<u64>,
    position_ms: Option<u64>,
    playing: bool,
    /// Playing on a phone this computer controls: that phone shows it on Discord itself.
    #[serde(default)]
    remote: bool,
}

/// Starts the controls; the app works without them when the system has none.
pub fn start(app: &AppHandle) -> Media {
    #[cfg(target_os = "windows")]
    let hwnd = app
        .get_webview_window("main")
        .and_then(|w| w.hwnd().ok())
        .map(|h| h.0 as *mut std::ffi::c_void);
    #[cfg(not(target_os = "windows"))]
    let hwnd = None;

    let config = PlatformConfig { dbus_name: "lfly", display_name: "LFly", hwnd };
    let controls = match MediaControls::new(config) {
        Ok(mut controls) => {
            let handle = app.clone();
            let attached = controls.attach(move |event| {
                let command = match event {
                    MediaControlEvent::Play => ("play", 0),
                    MediaControlEvent::Pause => ("pause", 0),
                    MediaControlEvent::Toggle => ("toggle", 0),
                    MediaControlEvent::Next => ("next", 0),
                    MediaControlEvent::Previous => ("previous", 0),
                    MediaControlEvent::Stop => ("pause", 0),
                    MediaControlEvent::SetPosition(MediaPosition(at)) => ("seek", at.as_millis() as i64),
                    MediaControlEvent::Seek(SeekDirection::Forward) => ("seek_by", 10_000),
                    MediaControlEvent::Seek(SeekDirection::Backward) => ("seek_by", -10_000),
                    MediaControlEvent::SeekBy(SeekDirection::Forward, by) => ("seek_by", by.as_millis() as i64),
                    MediaControlEvent::SeekBy(SeekDirection::Backward, by) => ("seek_by", -(by.as_millis() as i64)),
                    MediaControlEvent::Raise => {
                        if let Some(w) = handle.get_webview_window("main") {
                            let _ = w.unminimize();
                            let _ = w.set_focus();
                        }
                        return;
                    }
                    _ => return,
                };
                let _ = handle.emit("media:command", serde_json::json!({ "action": command.0, "value": command.1 }));
            });
            match attached {
                Ok(()) => Some(controls),
                Err(e) => {
                    log::warn!("Media controls not attached: {e:?}");
                    None
                }
            }
        }
        Err(e) => {
            log::warn!("No system media controls: {e:?}");
            None
        }
    };
    Media(Mutex::new(controls))
}

#[tauri::command]
pub fn media_update(media: tauri::State<'_, Media>, discord: tauri::State<'_, crate::discord::Discord>, now: NowShowing) {
    discord.show(match (&now.title, now.playing && !now.remote) {
        (Some(title), true) => {
            let now_ms = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_millis() as i64);
            let start_ms = now_ms - now.position_ms.unwrap_or(0) as i64;
            Some(crate::discord::Listening {
                title: title.clone(),
                artist: now.artist.clone().unwrap_or_default(),
                album: now.album.clone(),
                cover_url: now.cover_url.clone(),
                start_ms,
                end_ms: now.duration_ms.filter(|d| *d > 0).map(|d| start_ms + d as i64),
            })
        }
        _ => None,
    });
    let mut guard = media.0.lock().unwrap();
    let Some(controls) = guard.as_mut() else { return };
    let _ = controls.set_metadata(MediaMetadata {
        title: now.title.as_deref(),
        artist: now.artist.as_deref(),
        album: now.album.as_deref(),
        cover_url: now.cover_url.as_deref(),
        duration: now.duration_ms.filter(|d| *d > 0).map(Duration::from_millis),
    });
    let progress = now.position_ms.map(|p| MediaPosition(Duration::from_millis(p)));
    let playback = match (now.title.is_some(), now.playing) {
        (false, _) => MediaPlayback::Stopped,
        (true, true) => MediaPlayback::Playing { progress },
        (true, false) => MediaPlayback::Paused { progress },
    };
    let _ = controls.set_playback(playback);
}
