//! Settings that change what the core does (the window keeps its own look and lyrics settings in
//! the store): playing on with similar songs, the audio format, starting with the session,
//! the space LFly takes on disk, and this computer's name on LFly Connect.

use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};

use lfly_yt::{AudioPreference, YtMusic};
use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Emitter, Manager, State};

use crate::store;

/// Play on with songs like the last one when the queue ends.
static AUTOPLAY: AtomicBool = AtomicBool::new(true);

pub fn autoplay() -> bool {
    AUTOPLAY.load(Ordering::Relaxed)
}

#[derive(Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase", default)]
pub struct Playback {
    autoplay: bool,
    /// "aac" (plays everywhere) or "opus" (smaller for the same quality).
    format: String,
}

impl Default for Playback {
    fn default() -> Self {
        Playback { autoplay: true, format: "aac".into() }
    }
}

fn preference(format: &str) -> AudioPreference {
    if format == "opus" {
        AudioPreference::OpusFirst
    } else {
        AudioPreference::AacFirst
    }
}

/// At start: the saved playback settings into the core.
pub fn load(yt: &YtMusic) {
    let p: Playback = store::get("playback").ok().flatten().and_then(|v| serde_json::from_value(v).ok()).unwrap_or_default();
    AUTOPLAY.store(p.autoplay, Ordering::Relaxed);
    yt.set_preference(preference(&p.format));
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Everything {
    playback: Playback,
    autostart: bool,
    device_name: String,
}

#[tauri::command]
pub fn settings_get(app: AppHandle) -> Everything {
    let playback = store::get("playback").ok().flatten().and_then(|v| serde_json::from_value(v).ok()).unwrap_or_default();
    let name = app.state::<crate::Core>().me.read().unwrap().name.clone();
    Everything { playback, autostart: autostart_file().is_some_and(|f| f.exists()), device_name: name }
}

#[tauri::command]
pub fn settings_set_playback(yt: State<'_, YtMusic>, playback: Playback) -> Result<(), String> {
    AUTOPLAY.store(playback.autoplay, Ordering::Relaxed);
    yt.set_preference(preference(&playback.format));
    store::set("playback", &serde_json::to_value(&playback).map_err(|e| e.to_string())?)
}

// --- starting with the session ------------------------------------------------------------

#[cfg(target_os = "linux")]
fn autostart_file() -> Option<PathBuf> {
    Some(dirs::config_dir()?.join("autostart").join("lfly.desktop"))
}

#[cfg(target_os = "macos")]
fn autostart_file() -> Option<PathBuf> {
    Some(dirs::home_dir()?.join("Library/LaunchAgents/org.sonora.lfly.plist"))
}

#[cfg(target_os = "windows")]
fn autostart_file() -> Option<PathBuf> {
    // A shortcut-free way: a tiny .cmd in the user's Startup folder.
    Some(dirs::data_dir()?.join("Microsoft/Windows/Start Menu/Programs/Startup/LFly.cmd"))
}

#[tauri::command]
pub fn settings_set_autostart(on: bool) -> Result<bool, String> {
    let file = autostart_file().ok_or("Cartella di avvio non trovata")?;
    if !on {
        if file.exists() {
            std::fs::remove_file(&file).map_err(|e| e.to_string())?;
        }
        return Ok(false);
    }
    let exe = std::env::current_exe().map_err(|e| e.to_string())?;
    let exe = exe.to_string_lossy();
    #[cfg(target_os = "linux")]
    let body = format!("[Desktop Entry]\nType=Application\nName=LFly\nExec=\"{exe}\"\nIcon=lfly-desktop\nX-GNOME-Autostart-enabled=true\nComment=LFly music player\n");
    #[cfg(target_os = "macos")]
    let body = format!(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n<plist version=\"1.0\"><dict><key>Label</key><string>org.sonora.lfly</string><key>ProgramArguments</key><array><string>{exe}</string></array><key>RunAtLoad</key><true/></dict></plist>\n"
    );
    #[cfg(target_os = "windows")]
    let body = format!("@start \"\" \"{exe}\"\r\n");
    std::fs::create_dir_all(file.parent().unwrap()).map_err(|e| e.to_string())?;
    std::fs::write(&file, body).map_err(|e| e.to_string())?;
    Ok(true)
}

// --- disk ----------------------------------------------------------------------------------

fn size_of(dir: &Path) -> u64 {
    let Ok(entries) = std::fs::read_dir(dir) else { return 0 };
    entries
        .flatten()
        .map(|e| match e.file_type() {
            Ok(t) if t.is_dir() => size_of(&e.path()),
            Ok(_) => e.metadata().map_or(0, |m| m.len()),
            Err(_) => 0,
        })
        .sum()
}

fn cache(kind: &str) -> Option<PathBuf> {
    Some(dirs::cache_dir()?.join("lfly").join(kind))
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Disk {
    audio: u64,
    covers: u64,
    downloads: u64,
    downloads_dir: String,
}

#[tauri::command]
pub async fn disk_usage() -> Disk {
    tauri::async_runtime::spawn_blocking(|| {
        let downloads = dirs::data_dir().map(|d| d.join("lfly").join("downloads")).unwrap_or_default();
        Disk {
            audio: cache("audio").map_or(0, |d| size_of(&d)),
            covers: cache("covers").map_or(0, |d| size_of(&d)),
            downloads: size_of(&downloads),
            downloads_dir: downloads.to_string_lossy().into_owned(),
        }
    })
    .await
    .unwrap_or(Disk { audio: 0, covers: 0, downloads: 0, downloads_dir: String::new() })
}

/// Empties the audio or cover cache. The song playing keeps its file open and finishes.
#[tauri::command]
pub fn disk_clear(kind: String) -> Result<(), String> {
    if kind != "audio" && kind != "covers" {
        return Err("Cache sconosciuta".into());
    }
    let dir = cache(&kind).ok_or("Nessuna cartella cache")?;
    let Ok(entries) = std::fs::read_dir(&dir) else { return Ok(()) };
    for e in entries.flatten() {
        let p = e.path();
        let _ = if p.is_dir() { std::fs::remove_dir_all(&p) } else { std::fs::remove_file(&p) };
    }
    Ok(())
}

/// Opens a folder of LFly's in the system's file manager.
#[tauri::command]
pub fn open_folder(path: String) -> Result<(), String> {
    let p = PathBuf::from(&path);
    let ours = [dirs::data_dir(), dirs::cache_dir(), dirs::config_dir()].into_iter().flatten().any(|d| p.starts_with(d.join("lfly")));
    if !ours || !p.is_dir() {
        return Err("Cartella non valida".into());
    }
    #[cfg(target_os = "linux")]
    let r = std::process::Command::new("xdg-open").arg(&p).spawn();
    #[cfg(target_os = "macos")]
    let r = std::process::Command::new("open").arg(&p).spawn();
    #[cfg(target_os = "windows")]
    let r = std::process::Command::new("explorer").arg(&p).spawn();
    r.map(|_| ()).map_err(|e| e.to_string())
}

// --- LFly Connect --------------------------------------------------------------------------

/// Renames this computer and announces it again under the new name.
#[tauri::command]
pub fn connect_set_name(app: AppHandle, name: String) -> Result<String, String> {
    let name = crate::identity::set_name(&name);
    let core = app.state::<crate::Core>();
    let me = {
        let mut me = core.me.write().unwrap();
        me.name = name.clone();
        me.clone()
    };
    let secret = app.try_state::<YtMusic>().and_then(|yt| yt.account()).and_then(|a| a.connect_secret());
    core.controller.set_identity(me.clone(), secret.as_deref());
    if let Some(host) = core.local.as_ref().and_then(|l| l.host()) {
        host.set_identity(me.clone(), secret.as_deref());
        if let Some(d) = &core.discovery {
            d.announce(&me, host.port())?;
        }
    }
    let _ = app.emit("connect:me", me);
    Ok(name)
}

/// Forgets the devices of other accounts paired with a code.
#[tauri::command]
pub fn connect_forget_paired(app: AppHandle) {
    crate::identity::forget_trusted();
    if let Some(host) = app.state::<crate::Core>().local.as_ref().and_then(|l| l.host()) {
        host.forget_trusted();
    }
}
