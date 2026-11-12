//! Who this computer is on LFly Connect: a stable id, its name and kind.

use std::path::PathBuf;

use lfly_connect::protocol::{self, Device};
use serde::{Deserialize, Serialize};

#[derive(Default, Serialize, Deserialize)]
struct Saved {
    device_id: Option<String>,
    /// Devices of other accounts the owner accepted once.
    #[serde(default)]
    trusted: Vec<String>,
    /// The name the listener gave this computer (the system's host name otherwise).
    #[serde(default)]
    name: Option<String>,
}

fn read() -> Saved {
    file()
        .and_then(|p| std::fs::read_to_string(p).ok())
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default()
}

fn write(saved: &Saved) {
    if let Some(p) = file() {
        if let Some(dir) = p.parent() {
            let _ = std::fs::create_dir_all(dir);
        }
        let _ = std::fs::write(p, serde_json::to_string_pretty(saved).unwrap_or_default());
    }
}

/// The devices of other accounts allowed to control this computer.
pub fn trusted() -> Vec<String> {
    read().trusted
}

/// Forgets every device paired with a code: they will ask again.
pub fn forget_trusted() {
    let mut saved = read();
    saved.trusted.clear();
    write(&saved);
}

/// Renames this computer; an empty name goes back to the host name.
pub fn set_name(name: &str) -> String {
    let mut saved = read();
    let name = name.trim().chars().take(40).collect::<String>();
    saved.name = (!name.is_empty()).then_some(name);
    write(&saved);
    saved.name.unwrap_or_else(lfly_connect::discovery::hostname)
}

pub fn trust(id: &str) {
    let mut saved = read();
    if !saved.trusted.iter().any(|t| t == id) {
        saved.trusted.push(id.to_string());
        write(&saved);
    }
}

fn file() -> Option<PathBuf> {
    Some(dirs::config_dir()?.join("lfly").join("connect.json"))
}

/// This computer as the other devices see it. The id is made once and kept.
pub fn me() -> Device {
    let mut saved = read();
    let id = match &saved.device_id {
        Some(id) => id.clone(),
        None => {
            let id = uuid::Uuid::new_v4().to_string();
            saved.device_id = Some(id.clone());
            write(&saved);
            id
        }
    };
    Device {
        id,
        name: saved.name.clone().unwrap_or_else(lfly_connect::discovery::hostname),
        kind: protocol::KIND_DESKTOP.to_string(),
        // No YouTube Music account on the desktop yet: other devices pair with a code.
        owner: None,
    }
}
