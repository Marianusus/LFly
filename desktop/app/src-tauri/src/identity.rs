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
        name: lfly_connect::discovery::hostname(),
        kind: protocol::KIND_DESKTOP.to_string(),
        // No YouTube Music account on the desktop yet: other devices pair with a code.
        owner: None,
    }
}
