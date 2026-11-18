//! The Tauri window's side of the shared player (lfly-core): its reports become window events.

use std::path::PathBuf;

use lfly_connect::protocol::State;
use serde::Serialize;
use tauri::{AppHandle, Emitter};

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct StateEvent<'a> {
    state: &'a State,
    received_at: i64,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct PositionEvent {
    position_ms: i64,
    received_at: i64,
}

pub struct TauriPlayerHost(pub AppHandle);

impl lfly_core::player::PlayerHost for TauriPlayerHost {
    fn state(&self, state: &State, received_at: i64) {
        let _ = self.0.emit("player:state", StateEvent { state, received_at });
    }
    fn position(&self, position_ms: i64, received_at: i64) {
        let _ = self.0.emit("player:position", PositionEvent { position_ms, received_at });
    }
    fn notice(&self, text: &str) {
        let _ = self.0.emit("player:notice", text.to_string());
    }
    fn arrived(&self, from_device_id: String) {
        let _ = self.0.emit("player:arrived", from_device_id);
    }
    fn downloaded(&self, id: &str) -> Option<PathBuf> {
        crate::downloads::file_for(id)
    }
    fn autoplay(&self) -> bool {
        crate::settings::autoplay()
    }
    fn library(&self, peer_id: &str, offered: serde_json::Value) -> Option<serde_json::Value> {
        crate::libsync::answer(&self.0, peer_id, offered)
    }
}
