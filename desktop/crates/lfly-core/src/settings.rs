//! Settings that change what the core does, kept in the store as "playback": playing on with
//! similar songs when the queue ends, and the audio format. Shared by both windows.

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
