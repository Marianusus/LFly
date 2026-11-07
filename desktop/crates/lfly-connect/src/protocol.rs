//! The LFly Connect wire format, field for field as `ConnectProtocol.kt` writes it.
//!
//! Every message is one JSON object with a `type`. Optional fields are left out when empty (as Gson
//! does with nulls); everything else is always written, because the Kotlin side reads a missing
//! field as null even where it has a default.

use hmac::{Hmac, Mac};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

pub const VERSION: i32 = 1;

/// DNS-SD service type, as Android's NSD announces it (mDNS adds `.local.`).
pub const SERVICE_TYPE: &str = "_lfly-connect._tcp.local.";

/// The port a computer serves Connect on when it is free (fixed, for firewall rules).
pub const DEFAULT_PORT: u16 = 45780;

pub const TXT_ID: &str = "id";
pub const TXT_NAME: &str = "name";
pub const TXT_KIND: &str = "kind";
pub const TXT_OWNER: &str = "owner";

pub const KIND_PHONE: &str = "phone";
pub const KIND_TABLET: &str = "tablet";
pub const KIND_DESKTOP: &str = "desktop";
pub const KIND_TV: &str = "tv";
pub const KIND_WATCH: &str = "watch";

pub const CMD_PLAY: &str = "play";
pub const CMD_PAUSE: &str = "pause";
pub const CMD_TOGGLE: &str = "toggle";
pub const CMD_NEXT: &str = "next";
pub const CMD_PREVIOUS: &str = "previous";
/// Value: position in ms.
pub const CMD_SEEK: &str = "seek";
/// Value: absolute queue index.
pub const CMD_SKIP_TO: &str = "skip_to";
/// Value: volume 0..100.
pub const CMD_VOLUME: &str = "volume";
/// Value: +1 or -1, one step of the host's own volume scale.
pub const CMD_VOLUME_STEP: &str = "volume_step";
/// Stop playing here: another device takes over.
pub const CMD_RELEASE: &str = "release";
/// Value: 1 on, 0 off.
pub const CMD_SHUFFLE: &str = "shuffle";
/// Value: [REPEAT_OFF], [REPEAT_ONE] or [REPEAT_ALL].
pub const CMD_REPEAT: &str = "repeat";

pub const REPEAT_OFF: i64 = 0;
pub const REPEAT_ONE: i64 = 1;
pub const REPEAT_ALL: i64 = 2;

pub const EDIT_ADD: &str = "add";
pub const EDIT_NEXT: &str = "next";
pub const EDIT_REMOVE: &str = "remove";
pub const EDIT_MOVE: &str = "move";
pub const EDIT_CLEAR: &str = "clear";

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Device {
    #[serde(default)]
    pub id: String,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub kind: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub owner: Option<String>,
}

/// A track as the wire carries it: enough for any LFly to find and stream it itself.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TrackRef {
    #[serde(default)]
    pub id: String,
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub artist: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub album: Option<String>,
    #[serde(default)]
    pub duration_ms: i64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cover_url: Option<String>,
    #[serde(default = "unknown")]
    pub provider: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub artist_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub album_id: Option<String>,
    #[serde(default)]
    pub is_video: bool,
    /// Only on hand-overs, for the playing track: the stream the old device already resolved.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub stream_url: Option<String>,
}

/// What the host is playing. Sent on change and every few seconds while playing.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct State {
    #[serde(default)]
    pub device_id: String,
    #[serde(default)]
    pub active: bool,
    #[serde(default)]
    pub playing: bool,
    #[serde(default)]
    pub position_ms: i64,
    /// Host clock when `position_ms` was read.
    #[serde(default)]
    pub at: i64,
    #[serde(default)]
    pub duration_ms: i64,
    #[serde(default)]
    pub index: i32,
    #[serde(default)]
    pub queue: Vec<TrackRef>,
    #[serde(default)]
    pub shuffle: bool,
    #[serde(default = "repeat_off")]
    pub repeat: String,
    #[serde(default = "minus_one")]
    pub volume: i32,
    /// Set when the host stopped because the music moved to that device.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub handed_to: Option<String>,
}

/// Play this queue here, from `index` at `position_ms`: the music moves to the receiving device.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Transfer {
    #[serde(default)]
    pub queue: Vec<TrackRef>,
    #[serde(default)]
    pub index: i32,
    #[serde(default)]
    pub position_ms: i64,
    #[serde(default)]
    pub playing: bool,
    #[serde(default)]
    pub from_device_id: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case", rename_all_fields = "camelCase")]
pub enum Message {
    /// First message from the controller.
    Hello {
        device: Device,
        #[serde(default = "version")]
        protocol: i32,
    },
    /// Host's answer to `Hello`: a nonce to prove the account with.
    Challenge { device: Device, nonce: String },
    /// HMAC-SHA256(account key, nonce) in hex, or none without an account.
    Proof {
        #[serde(default, skip_serializing_if = "Option::is_none")]
        hmac: Option<String>,
    },
    /// The host waits for its owner to accept; the code shows on both screens.
    PairPending { code: String },
    /// The session is open, or refused.
    Welcome { accepted: bool },
    State(State),
    Command {
        action: String,
        #[serde(default)]
        value: i64,
    },
    QueueEdit {
        op: String,
        #[serde(default)]
        tracks: Vec<TrackRef>,
        #[serde(default = "minus_one")]
        index: i32,
        #[serde(default = "minus_one")]
        to: i32,
    },
    Transfer(Transfer),
    /// The library (favorites, playlists, history, in the phone's own JSON), to merge with the
    /// receiver's; the receiver answers with the merged one (`reply`). Same account only.
    Library {
        library: serde_json::Value,
        #[serde(default)]
        reply: bool,
    },
}

impl Message {
    pub fn command(action: &str, value: i64) -> Self {
        Message::Command { action: action.to_string(), value }
    }

    pub fn encode(&self) -> String {
        serde_json::to_string(self).expect("Connect messages always serialize")
    }

    /// None for anything this version does not understand: newer peers may send more.
    pub fn decode(text: &str) -> Option<Self> {
        serde_json::from_str(text).ok()
    }
}

fn version() -> i32 {
    VERSION
}
fn minus_one() -> i32 {
    -1
}
fn repeat_off() -> String {
    "off".into()
}
fn unknown() -> String {
    "Unknown".into()
}

// ---- Account proof ----

/// The key every device signed into the same account derives alike. Never sent.
pub fn account_key(account_secret: &str) -> [u8; 32] {
    Sha256::digest(format!("lfly-connect-key:{account_secret}")).into()
}

/// Public tag of the account for the `owner` TXT key: says "same account", proves nothing.
pub fn owner_tag(account_secret: &str) -> String {
    hex::encode(Sha256::digest(format!("lfly-connect-owner:{account_secret}")))[..12].to_string()
}

pub fn proof(account_key: &[u8], nonce: &str) -> String {
    let mut mac = Hmac::<Sha256>::new_from_slice(account_key).expect("HMAC takes any key length");
    mac.update(nonce.as_bytes());
    hex::encode(mac.finalize().into_bytes())
}

/// The four digits both screens show while pairing.
pub fn pairing_code(host_id: &str, client_id: &str, nonce: &str) -> String {
    let d = Sha256::digest(format!("{host_id}|{client_id}|{nonce}"));
    let n = ((u32::from(d[0]) << 8) | u32::from(d[1])) % 10_000;
    format!("{n:04}")
}

/// A fresh random nonce, 16 bytes in hex, like the phone's.
pub fn new_nonce() -> String {
    hex::encode(rand::random::<[u8; 16]>())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;

    fn vectors() -> Value {
        let path = concat!(env!("CARGO_MANIFEST_DIR"), "/../../../connect/fixtures/connect-vectors.json");
        serde_json::from_str(&std::fs::read_to_string(path).expect("shared vectors")).unwrap()
    }

    #[test]
    fn account_proof_and_pairing_code_match_the_vectors() {
        let v = vectors();
        let c = &v["crypto"];
        let s = |k: &str| c[k].as_str().unwrap().to_string();
        let key = account_key(&s("accountSecret"));
        assert_eq!(hex::encode(key), s("accountKeyHex"));
        assert_eq!(owner_tag(&s("accountSecret")), s("ownerTag"));
        assert_eq!(proof(&key, &s("nonce")), s("proof"));
        assert_eq!(pairing_code(&s("pairingHostId"), &s("pairingClientId"), &s("nonce")), s("pairingCode"));
    }

    #[test]
    fn every_message_reads_and_writes_back_the_same() {
        for m in vectors()["messages"].as_array().unwrap() {
            let text = m.to_string();
            let message = Message::decode(&text).unwrap_or_else(|| panic!("not understood: {text}"));
            let written: Value = serde_json::from_str(&message.encode()).unwrap();
            assert_eq!(&written, m, "{text}");
        }
    }

    #[test]
    fn unknown_messages_are_ignored() {
        assert_eq!(Message::decode(r#"{"type":"from_the_future","x":1}"#), None);
    }

    #[test]
    fn nonces_are_32_hex_digits() {
        let n = new_nonce();
        assert_eq!(n.len(), 32);
        assert!(n.chars().all(|c| c.is_ascii_hexdigit()));
    }
}
