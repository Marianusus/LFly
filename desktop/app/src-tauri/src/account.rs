//! The YouTube Music account on this computer, signed in the way the phone does it
//! (`auth/YtMusicLoginScreen.kt`): Google's real sign-in page in a window of its own, then, once
//! the listener says YouTube Music shows the right account, its cookies and the page's own `ytcfg`
//! are read. The password never passes through LFly.
//!
//! The account signs the YouTube Music requests (personal home, library, likes) and gives LFly
//! Connect the same secret as the phone, so devices on the same account trust each other without
//! a code. Signing in or out applies at once, without a restart.

use std::path::PathBuf;

use lfly_yt::{account, Account, AccountInfo, YtMusic};
use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Manager, State, WebviewUrl, WebviewWindowBuilder};

const LOGIN_LABEL: &str = "login";
const LOGIN_URL: &str = "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&prompt=select_account&continue=https%3A%2F%2Fmusic.youtube.com%2F";
/// Google refuses sign-in from browsers it doesn't know; the window presents itself as Firefox.
const LOGIN_UA: &str = "Mozilla/5.0 (X11; Linux x86_64; rv:144.0) Gecko/20100101 Firefox/144.0";

/// Who the page in front of the listener says it is. Returned as an object, not double-encoded.
const YTCFG_PROBE: &str = r#"(function () {
  try {
    if (!window.ytcfg || !window.ytcfg.get) return null;
    var get = function (key) {
      var value = window.ytcfg.get(key);
      return (value === undefined || value === null || value === '') ? null : String(value);
    };
    return {
      loggedIn: String(!!window.ytcfg.get('LOGGED_IN')),
      pageId: get('DELEGATED_SESSION_ID'),
      dataSyncId: get('DATASYNC_ID'),
      authUser: get('SESSION_INDEX'),
      visitorData: get('VISITOR_DATA')
    };
  } catch (e) {
    return null;
  }
})()"#;

#[derive(Default, Serialize, Deserialize)]
pub struct Saved {
    pub account: Option<Account>,
    pub info: Option<AccountInfo>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Probe {
    logged_in: Option<String>,
    page_id: Option<String>,
    data_sync_id: Option<String>,
    auth_user: Option<String>,
    visitor_data: Option<String>,
}

fn path() -> Option<PathBuf> {
    dirs::config_dir().map(|d| d.join("lfly").join("account.json"))
}

pub fn load() -> Saved {
    path().and_then(|p| std::fs::read(p).ok()).and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default()
}

fn save(saved: &Saved) -> Result<(), String> {
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

#[tauri::command]
pub fn account_get() -> Option<AccountInfo> {
    let saved = load();
    saved.account.and(saved.info)
}

/// Opens Google's sign-in page in its own window.
#[tauri::command]
pub async fn account_login(app: AppHandle) -> Result<(), String> {
    if let Some(window) = app.get_webview_window(LOGIN_LABEL) {
        let _ = window.set_focus();
        return Ok(());
    }
    let url = LOGIN_URL.parse::<tauri::Url>().map_err(|e| e.to_string())?;
    WebviewWindowBuilder::new(&app, LOGIN_LABEL, WebviewUrl::External(url))
        .title("Accedi a YouTube Music")
        .inner_size(520.0, 720.0)
        .user_agent(LOGIN_UA)
        .build()
        .map_err(|e| e.to_string())?;
    Ok(())
}

/// Reads the session from the sign-in window once YouTube Music shows the account, checks it with
/// YouTube Music, saves it and restarts the app so LFly Connect picks it up.
#[tauri::command]
pub async fn account_login_done(app: AppHandle, yt: State<'_, YtMusic>) -> Result<AccountInfo, String> {
    let window = app.get_webview_window(LOGIN_LABEL).ok_or("La finestra di accesso è chiusa")?;
    let music = "https://music.youtube.com/".parse::<tauri::Url>().map_err(|e| e.to_string())?;
    let cookie = window
        .cookies_for_url(music)
        .map_err(|e| e.to_string())?
        .iter()
        .map(|c| format!("{}={}", c.name(), c.value()))
        .collect::<Vec<_>>()
        .join("; ");
    if account::sapisid_from(&cookie).is_none() {
        return Err("Non hai ancora fatto l'accesso a Google nella finestra".into());
    }

    let (tx, rx) = tokio::sync::oneshot::channel::<String>();
    let tx = std::sync::Mutex::new(Some(tx));
    window
        .eval_with_callback(YTCFG_PROBE, move |raw| {
            if let Some(tx) = tx.lock().unwrap().take() {
                let _ = tx.send(raw);
            }
        })
        .map_err(|e| e.to_string())?;
    let raw = tokio::time::timeout(std::time::Duration::from_secs(5), rx)
        .await
        .map_err(|_| "La pagina non risponde")?
        .map_err(|_| "La pagina non risponde")?;
    let probe: Option<Probe> = serde_json::from_str(&raw).ok();
    let Some(probe) = probe.filter(|p| p.logged_in.as_deref() == Some("true")) else {
        return Err("Apri YouTube Music nella finestra e controlla che mostri il tuo account".into());
    };

    let account = Account {
        cookie,
        data_sync_id: probe.page_id.clone().or_else(|| account::normalize_data_sync_id(probe.data_sync_id.as_deref())),
        page_id: probe.page_id,
        auth_user: probe.auth_user,
        visitor_data: probe.visitor_data,
    };
    let info = yt.account_info(&account).await.map_err(|e| format!("YouTube Music non accetta l'accesso: {e}"))?;
    save(&Saved { account: Some(account), info: Some(info.clone()) })?;
    log::info!("Signed in to YouTube Music as {}", info.name);
    let _ = window.close();
    crate::apply_account(&app, load().account);
    Ok(info)
}

#[tauri::command]
pub async fn account_login_cancel(app: AppHandle) {
    if let Some(window) = app.get_webview_window(LOGIN_LABEL) {
        let _ = window.close();
    }
}

/// Forgets the account (and the sign-in window's cookies), then restarts.
#[tauri::command]
pub async fn account_logout(app: AppHandle) -> Result<(), String> {
    if let Some(path) = path() {
        let _ = std::fs::remove_file(path);
    }
    // The next sign-in starts from a clean browser (the windows share one cookie jar).
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.clear_all_browsing_data();
    }
    crate::apply_account(&app, None);
    Ok(())
}
