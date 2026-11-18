//! The YouTube Music account saved on this computer (config/lfly/account.json): its cookies and
//! the page's ids, as the sign-in read them, and who it is. Both windows read it; signing in needs
//! a web view, which only the Tauri app has so far.

use std::path::PathBuf;

use lfly_yt::{Account, AccountInfo};
use serde::{Deserialize, Serialize};

#[derive(Default, Serialize, Deserialize)]
pub struct Saved {
    pub account: Option<Account>,
    pub info: Option<AccountInfo>,
}

fn path() -> Option<PathBuf> {
    dirs::config_dir().map(|d| d.join("lfly").join("account.json"))
}

pub fn load() -> Saved {
    path().and_then(|p| std::fs::read(p).ok()).and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default()
}

pub fn save(saved: &Saved) -> Result<(), String> {
    let path = path().ok_or("Nessuna cartella di configurazione")?;
    if let Some(dir) = path.parent() {
        std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    }
    let bytes = serde_json::to_vec_pretty(saved).map_err(|e| e.to_string())?;
    std::fs::write(&path, bytes).map_err(|e| e.to_string())?;
    // The cookies are a login: only this user may read them.
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600));
    }
    Ok(())
}

/// Forgets the account.
pub fn forget() {
    if let Some(path) = path() {
        let _ = std::fs::remove_file(path);
    }
}

/// Google's sign-in for YouTube Music in a window of the `lfly-web` helper (for the native app,
/// which has no webview); blocks until the window closes. The session it read, not yet checked
/// with YouTube Music nor saved; `Ok(None)` when the window was closed first.
pub fn sign_in_with_helper() -> Result<Option<lfly_yt::Account>, String> {
    use lfly_yt::account;
    let helper = crate::spotify_account::helper().ok_or("lfly-web is missing")?;
    let out = std::process::Command::new(helper)
        .arg("ytmusic-login")
        .stdin(std::process::Stdio::null())
        .stderr(std::process::Stdio::inherit())
        .output()
        .map_err(|e| e.to_string())?;
    if !out.status.success() {
        return Ok(None);
    }
    let v: serde_json::Value = serde_json::from_slice(&out.stdout).map_err(|e| e.to_string())?;
    let cookie = v.get("cookie").and_then(|c| c.as_str()).unwrap_or_default().to_string();
    if account::sapisid_from(&cookie).is_none() {
        return Err("no Google session".into());
    }
    let p = |k: &str| v.pointer(&format!("/probe/{k}")).and_then(|x| x.as_str()).filter(|s| !s.is_empty()).map(str::to_string);
    let page_id = p("pageId");
    Ok(Some(lfly_yt::Account {
        cookie,
        data_sync_id: page_id.clone().or_else(|| account::normalize_data_sync_id(p("dataSyncId").as_deref())),
        page_id,
        auth_user: p("authUser"),
        visitor_data: p("visitorData"),
    }))
}
