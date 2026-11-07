//! A signed-in YouTube Music session, as the phone keeps it (`auth/GoogleSession.kt`): the web
//! cookies, a `SAPISIDHASH` made per request from them, and who the session acts as.

use serde::{Deserialize, Serialize};
use sha1::{Digest, Sha1};

pub const MUSIC_ORIGIN: &str = "https://music.youtube.com";

/// The cookies that sign a request, most specific first.
const SAPISID_NAMES: [&str; 3] = ["SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID"];

/// One signed-in identity: a Google account, or one of its Brand channels.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Account {
    /// The `Cookie` header for music.youtube.com.
    pub cookie: String,
    /// The active identity, sent as `context.user.onBehalfOfUser`.
    #[serde(default)]
    pub data_sync_id: Option<String>,
    /// `DELEGATED_SESSION_ID`: set only while a Brand channel is selected.
    #[serde(default)]
    pub page_id: Option<String>,
    /// `SESSION_INDEX`: which Google account of the cookie jar.
    #[serde(default)]
    pub auth_user: Option<String>,
    #[serde(default)]
    pub visitor_data: Option<String>,
}

/// Who an account is, from YouTube Music's own account menu.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct AccountInfo {
    pub name: String,
    pub handle: String,
    pub email: String,
    pub avatar_url: Option<String>,
}

impl Account {
    /// The secret LFly Connect proves the account with, as the phone has it
    /// (`ConnectManager.accountSecret`): the identity before any `||`.
    pub fn connect_secret(&self) -> Option<String> {
        self.data_sync_id
            .as_deref()
            .map(|id| id.split("||").next().unwrap_or("").to_string())
            .filter(|s| !s.trim().is_empty())
    }

    pub fn sapisid(&self) -> Option<String> {
        sapisid_from(&self.cookie)
    }
}

/// The cookies of a `Cookie` header, in order.
pub fn cookies(header: &str) -> Vec<(String, String)> {
    header
        .split(';')
        .filter_map(|entry| {
            let (name, value) = entry.split_once('=')?;
            let (name, value) = (name.trim(), value.trim());
            (!name.is_empty() && !value.is_empty()).then(|| (name.to_string(), value.to_string()))
        })
        .collect()
}

/// The secret requests are signed with; None while the jar holds no login.
pub fn sapisid_from(header: &str) -> Option<String> {
    let jar = cookies(header);
    SAPISID_NAMES.iter().find_map(|name| jar.iter().find(|(n, _)| n == name).map(|(_, v)| v.clone()))
}

/// Google's web signature, `SAPISIDHASH <ts>_sha1("<ts> <SAPISID> <origin>")`.
pub fn sapisid_hash(sapisid: &str, now_seconds: u64) -> String {
    let digest = Sha1::digest(format!("{now_seconds} {sapisid} {MUSIC_ORIGIN}").as_bytes());
    let hex: String = digest.iter().map(|b| format!("{b:02x}")).collect();
    format!("SAPISIDHASH {now_seconds}_{hex}")
}

/// `DATASYNC_ID` as `onBehalfOfUser` takes it: YouTube often gives `account||delegated`, where
/// the second half is the active identity and a plain account leaves it empty.
pub fn normalize_data_sync_id(raw: Option<&str>) -> Option<String> {
    let value = raw.map(str::trim).filter(|v| !v.is_empty())?;
    let Some((account, delegated)) = value.split_once("||") else { return Some(value.to_string()) };
    [delegated, account].into_iter().find(|s| !s.is_empty()).map(str::to_string)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn signs_like_google() {
        // sha1("1700000000 abc/def https://music.youtube.com")
        let hash = sapisid_hash("abc/def", 1_700_000_000);
        assert!(hash.starts_with("SAPISIDHASH 1700000000_"));
        assert_eq!(hash.len(), "SAPISIDHASH 1700000000_".len() + 40);
    }

    #[test]
    fn finds_the_signing_cookie() {
        assert_eq!(sapisid_from("a=1; __Secure-3PAPISID=x; SAPISID=y").as_deref(), Some("y"));
        assert_eq!(sapisid_from("a=1; __Secure-3PAPISID=x").as_deref(), Some("x"));
        assert_eq!(sapisid_from("a=1"), None);
    }

    #[test]
    fn normalizes_data_sync_ids() {
        assert_eq!(normalize_data_sync_id(Some("123||")).as_deref(), Some("123"));
        assert_eq!(normalize_data_sync_id(Some("123||456")).as_deref(), Some("456"));
        assert_eq!(normalize_data_sync_id(Some("789")).as_deref(), Some("789"));
        assert_eq!(normalize_data_sync_id(Some("")), None);
    }

    #[test]
    fn connect_secret_matches_the_phone() {
        let a = Account { data_sync_id: Some("111||222".into()), ..Default::default() };
        assert_eq!(a.connect_secret().as_deref(), Some("111"));
        let b = Account { data_sync_id: Some("333".into()), ..Default::default() };
        assert_eq!(b.connect_secret().as_deref(), Some("333"));
    }
}
