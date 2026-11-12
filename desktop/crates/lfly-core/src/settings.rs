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
