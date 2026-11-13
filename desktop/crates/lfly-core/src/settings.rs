//! Settings that change what the core does, kept in the store as "playback": playing on with
//! similar songs when the queue ends, and the audio format; the sound ("audio"); starting with the
//! session. Shared by both windows.

use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};

use lfly_yt::{AudioPreference, YtMusic};
use serde::{Deserialize, Serialize};

use crate::store;

const KEY: &str = "playback";

/// Play on with songs like the last one when the queue ends.
static AUTOPLAY: AtomicBool = AtomicBool::new(true);

pub fn autoplay() -> bool {
    AUTOPLAY.load(Ordering::Relaxed)
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct Playback {
    pub autoplay: bool,
    /// "aac" (plays everywhere) or "opus" (smaller for the same quality).
    pub format: String,
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

pub fn playback() -> Playback {
    store::get(KEY).ok().flatten().and_then(|v| serde_json::from_value(v).ok()).unwrap_or_default()
}

/// At start: the saved playback settings into the core.
pub fn load(yt: &YtMusic) {
    let p = playback();
    AUTOPLAY.store(p.autoplay, Ordering::Relaxed);
    yt.set_preference(preference(&p.format));
}

pub fn set_playback(yt: &YtMusic, p: &Playback) -> Result<(), String> {
    AUTOPLAY.store(p.autoplay, Ordering::Relaxed);
    yt.set_preference(preference(&p.format));
    store::set(KEY, &serde_json::to_value(p).map_err(|e| e.to_string())?)
}

/// The local player's sound, kept as "audio" (audio.svelte.ts Saved): the equalizer (the phone's
/// bands and presets), the output device, the crossfade.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct Audio {
    pub eq: lfly_audio::EqCurve,
    pub device: Option<String>,
    /// Seconds each song fades into the next (0: back to back, without gaps).
    pub crossfade: u32,
}

impl Default for Audio {
    fn default() -> Self {
        Audio { eq: lfly_audio::EqCurve::FLAT, device: None, crossfade: 0 }
    }
}

pub fn audio() -> Audio {
    store::get("audio").ok().flatten().and_then(|v| serde_json::from_value(v).ok()).unwrap_or_default()
}

pub fn set_audio(a: &Audio) -> Result<(), String> {
    store::set("audio", &serde_json::to_value(a).map_err(|e| e.to_string())?)
}

/// At start: the saved sound into the player.
pub fn apply_audio(player: &crate::player::LocalPlayer, a: &Audio) {
    player.set_equalizer(a.eq);
    player.set_crossfade(a.crossfade);
    if a.device.is_some() {
        player.set_device(a.device.clone());
    }
}


// --- starting with the session ------------------------------------------------------------

/// Whether LFly starts with the session.
pub fn autostart() -> bool {
    autostart_file().is_some_and(|f| f.exists())
}

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

/// Starts LFly with the session (or stops it); returns whether it now does.
pub fn set_autostart(on: bool) -> Result<bool, String> {
    let file = autostart_file().ok_or("Cartella di avvio non trovata")?;
    if !on {
        if file.exists() {
            std::fs::remove_file(&file).map_err(|e| e.to_string())?;
        }
        return Ok(false);
    }
    // An AppImage runs from a mount that is gone at the next login: its own path instead.
    let exe = std::env::var_os("APPIMAGE").map(PathBuf::from).map_or_else(std::env::current_exe, Ok).map_err(|e| e.to_string())?;
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_what_the_tauri_window_saves() {
        let v = serde_json::json!({ "crossfade": 4, "device": null, "eq": { "bandsDb": [1.5, 0, 0, 0, -2], "bassBoost": 300, "enabled": true } });
        let a: Audio = serde_json::from_value(v).unwrap();
        assert_eq!(a.crossfade, 4);
        assert!(a.eq.enabled);
        assert_eq!(a.eq.bands_db[0], 1.5);
        assert_eq!(a.eq.bass_boost, 300);
        let back = serde_json::to_value(&a).unwrap();
        assert_eq!(back["eq"]["bandsDb"][4], -2.0);
    }
}
