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
