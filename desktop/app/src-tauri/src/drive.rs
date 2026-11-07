//! Sync with the phone through the listener's Google Drive.
//!
//! Both apps keep one file, `lfly-library.json`, in Drive's hidden app folder (`appDataFolder`:
//! only LFly sees it, and it does not count as one of the listener's files). Whoever syncs
//! downloads it, merges it with its own library and uploads the result when it differs: the
//! phone and the computer meet there without being on at the same time.
//!
//! The merge is three-way. Each device remembers which songs and playlists the file held when it
//! last synced (the base), so it can tell a song deleted on the other side (in the base, gone
//! there: delete it here too) from a song added here (not in the base: keep it). Playlists edited
//! on both sides keep the most recently changed one (`updatedAt`). History is joined.
//! The phone's `DriveSync.kt` merges the same way.
//!
//! Sign-in is Google's flow for desktop apps (a browser tab, a code back on a loopback port, PKCE).
//! The OAuth client comes from the listener's own Google Cloud project, in
//! `~/.config/lfly/google-oauth.json` (the JSON the console downloads); the phone's Android client
//! must belong to the same project, or the two apps would see different app folders.

use std::collections::{HashMap, HashSet};
use std::io::{Read, Write};
use std::net::TcpListener;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use base64::Engine;
use rand::Rng;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use tauri::{AppHandle, Emitter, Manager, State};
use tokio::sync::Mutex;

use crate::library;

const SCOPE: &str = "https://www.googleapis.com/auth/drive.appdata";
const FILE_NAME: &str = "lfly-library.json";
const HISTORY_MAX: usize = 100;
/// How often the computer looks for the phone's changes while it is open.
const EVERY: Duration = Duration::from_secs(180);

// --- the merge ------------------------------------------------------------------------------

/// The library as the sync file and the phone hold it.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct Snapshot {
    pub favorites: Vec<Value>,
    pub playlists: Vec<Value>,
    pub history: Vec<Value>,
}

/// What the file held at this device's last sync: ids only.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Base {
    pub favorites: Vec<String>,
    pub playlists: Vec<String>,
}

fn id_of(v: &Value) -> Option<&str> {
    v.get("id").and_then(Value::as_str)
}

fn updated_at(v: &Value) -> i64 {
    v.get("updatedAt").and_then(Value::as_i64).unwrap_or(0)
}

/// Three-way merge of one list by id. Local order first, then what only the other side has.
fn merge_list(base: &[String], local: &[Value], remote: &[Value], newer: impl Fn(&Value, &Value) -> bool) -> Vec<Value> {
    let base: HashSet<&str> = base.iter().map(String::as_str).collect();
    let theirs: HashMap<&str, &Value> = remote.iter().filter_map(|v| Some((id_of(v)?, v))).collect();
    let mine: HashSet<&str> = local.iter().filter_map(id_of).collect();
    let mut out = Vec::new();
    let mut seen = HashSet::new();
    for v in local {
        let Some(id) = id_of(v) else { continue };
        if !seen.insert(id) {
            continue;
        }
        match theirs.get(id) {
            Some(r) => out.push(if newer(r, v) { (*r).clone() } else { v.clone() }),
            // In the base but gone there: the other side deleted it.
            None if base.contains(id) => {}
            None => out.push(v.clone()),
        }
    }
    for v in remote {
        let Some(id) = id_of(v) else { continue };
        if mine.contains(id) || !seen.insert(id) {
            continue;
        }
        // In the base but gone here: deleted here.
        if !base.contains(id) {
            out.push(v.clone());
        }
    }
    out
}

pub fn merge(base: &Base, local: &Snapshot, remote: &Snapshot) -> Snapshot {
    let favorites = merge_list(&base.favorites, &local.favorites, &remote.favorites, |_, _| false);
    let playlists = merge_list(&base.playlists, &local.playlists, &remote.playlists, |r, l| updated_at(r) > updated_at(l));
    // History has no deletions worth keeping: the newest plays of both, without repeats.
    let mut history = Vec::new();
    let mut seen = HashSet::new();
    for v in local.history.iter().chain(&remote.history) {
        if let Some(id) = id_of(v) {
            if seen.insert(id.to_string()) {
                history.push(v.clone());
            }
        }
    }
    history.truncate(HISTORY_MAX.max(local.history.len()));
    Snapshot { favorites, playlists, history }
}

pub fn base_of(s: &Snapshot) -> Base {
    let ids = |l: &[Value]| l.iter().filter_map(id_of).map(str::to_string).collect();
    Base { favorites: ids(&s.favorites), playlists: ids(&s.playlists) }
}

// --- files on this computer --------------------------------------------------------------------

fn config_dir() -> Option<PathBuf> {
    Some(dirs::config_dir()?.join("lfly"))
}

#[derive(Clone, Deserialize)]
struct Client {
    client_id: String,
    client_secret: String,
}

/// The OAuth client: Google's downloaded JSON (`{"installed": {...}}`) or the bare fields.
fn client() -> Option<Client> {
    let text = std::fs::read_to_string(config_dir()?.join("google-oauth.json")).ok()?;
    let v: Value = serde_json::from_str(&text).ok()?;
    let inner = v.get("installed").cloned().unwrap_or(v);
    serde_json::from_value(inner).ok()
}

#[derive(Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct Saved {
    refresh_token: String,
    email: Option<String>,
    enabled: bool,
    base: Base,
    last_sync: Option<u64>,
}

fn saved_path() -> Option<PathBuf> {
    Some(config_dir()?.join("drive.json"))
}

fn load_saved() -> Saved {
    saved_path().and_then(|p| std::fs::read(p).ok()).and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default()
}

fn store_saved(s: &Saved) -> Result<(), String> {
    let path = saved_path().ok_or("Nessuna cartella di configurazione")?;
    std::fs::create_dir_all(path.parent().unwrap()).map_err(|e| e.to_string())?;
    let tmp = path.with_extension("json.tmp");
    std::fs::write(&tmp, serde_json::to_vec(s).map_err(|e| e.to_string())?).map_err(|e| e.to_string())?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(&tmp, std::fs::Permissions::from_mode(0o600));
    }
    std::fs::rename(tmp, path).map_err(|e| e.to_string())
}

fn now_s() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_secs())
}

// --- Google ------------------------------------------------------------------------------------

pub struct Drive {
    http: reqwest::Client,
    state: Mutex<Saved>,
    token: Mutex<Option<(String, SystemTime)>>,
    /// One sync at a time.
    running: Mutex<()>,
}

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct Status {
    /// The OAuth client file is there.
    configured: bool,
    signed_in: bool,
    enabled: bool,
    email: Option<String>,
    last_sync: Option<u64>,
}

impl Drive {
    pub fn new() -> Self {
        Drive {
            http: reqwest::Client::builder().timeout(Duration::from_secs(30)).build().unwrap_or_default(),
            state: Mutex::new(load_saved()),
            token: Mutex::new(None),
            running: Mutex::new(()),
        }
    }

    async fn status(&self) -> Status {
        let s = self.state.lock().await;
        Status {
            configured: client().is_some(),
            signed_in: !s.refresh_token.is_empty(),
            enabled: s.enabled && !s.refresh_token.is_empty(),
            email: s.email.clone(),
            last_sync: s.last_sync,
        }
    }

    async fn access_token(&self) -> Result<String, String> {
        if let Some((t, until)) = self.token.lock().await.clone() {
            if SystemTime::now() < until {
                return Ok(t);
            }
        }
        let c = client().ok_or("Manca il file google-oauth.json")?;
        let refresh = self.state.lock().await.refresh_token.clone();
        if refresh.is_empty() {
            return Err("Accedi a Google Drive".into());
        }
        let r: Value = self
            .http
            .post("https://oauth2.googleapis.com/token")
            .form(&[("client_id", c.client_id.as_str()), ("client_secret", &c.client_secret), ("refresh_token", &refresh), ("grant_type", "refresh_token")])
            .send()
            .await
            .map_err(|e| e.to_string())?
            .json()
            .await
            .map_err(|e| e.to_string())?;
        let Some(token) = r.get("access_token").and_then(Value::as_str) else {
            if r.get("error").and_then(Value::as_str) == Some("invalid_grant") {
                // Revoked or expired: sign in again.
                let mut s = self.state.lock().await;
                s.refresh_token.clear();
                let _ = store_saved(&s);
            }
            return Err(format!("Google non dà l'accesso: {}", r.get("error").and_then(Value::as_str).unwrap_or("errore")));
        };
        let secs = r.get("expires_in").and_then(Value::as_u64).unwrap_or(3600).saturating_sub(120);
        *self.token.lock().await = Some((token.to_string(), SystemTime::now() + Duration::from_secs(secs)));
        Ok(token.to_string())
    }

    async fn find(&self, token: &str) -> Result<Option<String>, String> {
        let r: Value = self
            .http
            .get("https://www.googleapis.com/drive/v3/files")
            .bearer_auth(token)
            .query(&[("spaces", "appDataFolder"), ("q", &format!("name = '{FILE_NAME}' and trashed = false")), ("fields", "files(id,modifiedTime)"), ("orderBy", "modifiedTime desc")])
            .send()
            .await
            .map_err(|e| e.to_string())?
            .error_for_status()
            .map_err(|e| e.to_string())?
            .json()
            .await
            .map_err(|e| e.to_string())?;
        Ok(r.pointer("/files/0/id").and_then(Value::as_str).map(str::to_string))
    }

    async fn download(&self, token: &str, id: &str) -> Result<Snapshot, String> {
        let text = self
            .http
            .get(format!("https://www.googleapis.com/drive/v3/files/{id}"))
            .bearer_auth(token)
            .query(&[("alt", "media")])
            .send()
            .await
            .map_err(|e| e.to_string())?
            .error_for_status()
            .map_err(|e| e.to_string())?
            .text()
            .await
            .map_err(|e| e.to_string())?;
        serde_json::from_str(&text).map_err(|e| format!("Il file su Drive non si legge: {e}"))
    }

    async fn upload(&self, token: &str, id: Option<&str>, snapshot: &Snapshot) -> Result<(), String> {
        let mut body = serde_json::to_value(snapshot).map_err(|e| e.to_string())?;
        body["format"] = json!(1);
        body["updatedAt"] = json!(now_s() * 1000);
        body["device"] = json!("desktop");
        let bytes = serde_json::to_vec(&body).map_err(|e| e.to_string())?;
        let req = match id {
            Some(id) => self
                .http
                .patch(format!("https://www.googleapis.com/upload/drive/v3/files/{id}"))
                .query(&[("uploadType", "media")])
                .header("Content-Type", "application/json")
                .body(bytes),
            None => {
                let boundary = format!("lfly{}", rand::thread_rng().gen::<u64>());
                let meta = json!({ "name": FILE_NAME, "parents": ["appDataFolder"], "mimeType": "application/json" });
                let mut multipart = format!("--{boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n{meta}\r\n--{boundary}\r\nContent-Type: application/json\r\n\r\n").into_bytes();
                multipart.extend_from_slice(&bytes);
                multipart.extend_from_slice(format!("\r\n--{boundary}--\r\n").as_bytes());
                self.http
                    .post("https://www.googleapis.com/upload/drive/v3/files")
                    .query(&[("uploadType", "multipart")])
                    .header("Content-Type", format!("multipart/related; boundary={boundary}"))
                    .body(multipart)
            }
        };
        req.bearer_auth(token).send().await.map_err(|e| e.to_string())?.error_for_status().map_err(|e| e.to_string())?;
        Ok(())
    }

    /// Downloads, merges, writes here what changed and uploads what the phone lacks.
    pub async fn sync(&self, app: &AppHandle) -> Result<bool, String> {
        let _one = self.running.lock().await;
        let token = self.access_token().await?;
        let dir = library::dir()?;
        let lib = library::load(&dir);
        let as_vec = |v: Value| v.as_array().cloned().unwrap_or_default();
        let local = Snapshot { favorites: as_vec(lib.favorites), playlists: as_vec(lib.playlists), history: as_vec(lib.history) };
        let id = self.find(&token).await?;
        let remote = match &id {
            Some(id) => Some(self.download(&token, id).await?),
            None => None,
        };
        let base = self.state.lock().await.base.clone();
        let merged = match &remote {
            Some(r) => merge(&base, &local, r),
            None => local.clone(),
        };
        let changed_here = merged != local;
        if changed_here {
            library::save(&dir, library::FAVORITES, &Value::Array(merged.favorites.clone()))?;
            library::save(&dir, library::PLAYLISTS, &Value::Array(merged.playlists.clone()))?;
            library::save(&dir, library::HISTORY, &Value::Array(merged.history.clone()))?;
            let _ = app.emit("library:changed", ());
        }
        if remote.as_ref() != Some(&merged) {
            self.upload(&token, id.as_deref(), &merged).await?;
        }
        let mut s = self.state.lock().await;
        s.base = base_of(&merged);
        s.last_sync = Some(now_s());
        store_saved(&s)?;
        let _ = app.emit("drive:status", Status { configured: true, signed_in: true, enabled: s.enabled, email: s.email.clone(), last_sync: s.last_sync });
        Ok(changed_here)
    }
}

/// Opens Google's sign-in in the browser and waits for its answer on a loopback port.
async fn sign_in(drive: &Drive) -> Result<(), String> {
    let c = client().ok_or("Manca il file google-oauth.json: guarda le istruzioni nelle Impostazioni")?;
    let listener = TcpListener::bind("127.0.0.1:0").map_err(|e| e.to_string())?;
    let redirect = format!("http://127.0.0.1:{}", listener.local_addr().map_err(|e| e.to_string())?.port());
    let b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD;
    let verifier: String = {
        let mut rng = rand::thread_rng();
        (0..64).map(|_| rng.sample(rand::distributions::Alphanumeric) as char).collect()
    };
    let challenge = b64.encode(Sha256::digest(verifier.as_bytes()));
    let state: String = {
        let mut rng = rand::thread_rng();
        (0..24).map(|_| rng.sample(rand::distributions::Alphanumeric) as char).collect()
    };
    let url = reqwest::Url::parse_with_params(
        "https://accounts.google.com/o/oauth2/v2/auth",
        &[
            ("client_id", c.client_id.as_str()),
            ("redirect_uri", &redirect),
            ("response_type", "code"),
            ("scope", &format!("{SCOPE} openid email")),
            ("code_challenge", &challenge),
            ("code_challenge_method", "S256"),
            ("access_type", "offline"),
            ("prompt", "consent"),
            ("state", &state),
        ],
    )
    .map_err(|e| e.to_string())?;
    open_browser(url.as_str());

    let code = tokio::task::spawn_blocking(move || -> Result<String, String> {
        listener.set_nonblocking(false).ok();
        let started = std::time::Instant::now();
        loop {
            if started.elapsed() > Duration::from_secs(300) {
                return Err("Accesso non completato in tempo".into());
            }
            let (mut stream, _) = listener.accept().map_err(|e| e.to_string())?;
            let mut buf = [0u8; 4096];
            let n = stream.read(&mut buf).unwrap_or(0);
            let req = String::from_utf8_lossy(&buf[..n]);
            let path = req.split_whitespace().nth(1).unwrap_or("/");
            let Ok(u) = reqwest::Url::parse(&format!("http://127.0.0.1{path}")) else { continue };
            let q: HashMap<_, _> = u.query_pairs().into_owned().collect();
            if q.is_empty() {
                // The browser asking for a favicon.
                let _ = stream.write_all(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n");
                continue;
            }
            let ok = q.get("state") == Some(&state) && q.contains_key("code");
            let page = if ok {
                "<h2 style='font-family:sans-serif'>LFly è collegato a Google Drive. Puoi chiudere questa scheda.</h2>"
            } else {
                "<h2 style='font-family:sans-serif'>Accesso annullato.</h2>"
            };
            let _ = stream.write_all(format!("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{page}", page.len()).as_bytes());
            return if ok { Ok(q["code"].clone()) } else { Err(q.get("error").cloned().unwrap_or_else(|| "Accesso annullato".into())) };
        }
    })
    .await
    .map_err(|e| e.to_string())??;

    let r: Value = drive
        .http
        .post("https://oauth2.googleapis.com/token")
        .form(&[
            ("client_id", c.client_id.as_str()),
            ("client_secret", &c.client_secret),
            ("code", &code),
            ("code_verifier", &verifier),
            ("redirect_uri", &redirect),
            ("grant_type", "authorization_code"),
        ])
        .send()
        .await
        .map_err(|e| e.to_string())?
        .json()
        .await
        .map_err(|e| e.to_string())?;
    let refresh = r.get("refresh_token").and_then(Value::as_str).ok_or_else(|| format!("Google non ha dato l'accesso: {r}"))?;
    // The address, from the ID token's payload (no signature check needed: it came over TLS from Google).
    let email = r
        .get("id_token")
        .and_then(Value::as_str)
        .and_then(|t| t.split('.').nth(1))
        .and_then(|p| b64.decode(p.trim_end_matches('=')).ok())
        .and_then(|b| serde_json::from_slice::<Value>(&b).ok())
        .and_then(|v| v.get("email").and_then(Value::as_str).map(str::to_string));
    if let Some(t) = r.get("access_token").and_then(Value::as_str) {
        let secs = r.get("expires_in").and_then(Value::as_u64).unwrap_or(3600).saturating_sub(120);
        *drive.token.lock().await = Some((t.to_string(), SystemTime::now() + Duration::from_secs(secs)));
    }
    let mut s = drive.state.lock().await;
    s.refresh_token = refresh.to_string();
    s.email = email;
    s.enabled = true;
    store_saved(&s)
}

fn open_browser(url: &str) {
    #[cfg(target_os = "linux")]
    let r = std::process::Command::new("xdg-open").arg(url).spawn();
    #[cfg(target_os = "macos")]
    let r = std::process::Command::new("open").arg(url).spawn();
    #[cfg(target_os = "windows")]
    let r = std::process::Command::new("rundll32").args(["url.dll,FileProtocolHandler", url]).spawn();
    if let Err(e) = r {
        log::warn!("browser: {e}");
    }
}

/// Syncs at start, every few minutes, and shortly after the library changes here.
pub fn start(app: AppHandle) -> Arc<Drive> {
    let drive = Arc::new(Drive::new());
    let d = drive.clone();
    tauri::async_runtime::spawn(async move {
        tokio::time::sleep(Duration::from_secs(4)).await;
        loop {
            if d.status().await.enabled {
                if let Err(e) = d.sync(&app).await {
                    log::warn!("drive sync: {e}");
                }
            }
            tokio::time::sleep(EVERY).await;
        }
    });
    drive
}

/// The library was saved here: sync a little later, once the edits settle.
pub fn changed(app: &AppHandle) {
    static PENDING: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
    let ticket = PENDING.fetch_add(1, std::sync::atomic::Ordering::SeqCst) + 1;
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        tokio::time::sleep(Duration::from_secs(8)).await;
        if PENDING.load(std::sync::atomic::Ordering::SeqCst) != ticket {
            return;
        }
        let drive = app.state::<Arc<Drive>>();
        if drive.status().await.enabled {
            if let Err(e) = drive.sync(&app).await {
                log::warn!("drive sync: {e}");
            }
        }
    });
}

#[tauri::command]
pub async fn drive_status(drive: State<'_, Arc<Drive>>) -> Result<Status, String> {
    Ok(drive.status().await)
}

#[tauri::command]
pub async fn drive_sign_in(app: AppHandle, drive: State<'_, Arc<Drive>>) -> Result<Status, String> {
    sign_in(&drive).await?;
    drive.sync(&app).await?;
    Ok(drive.status().await)
}

#[tauri::command]
pub async fn drive_sync(app: AppHandle, drive: State<'_, Arc<Drive>>) -> Result<bool, String> {
    drive.sync(&app).await
}

#[tauri::command]
pub async fn drive_set_enabled(app: AppHandle, drive: State<'_, Arc<Drive>>, enabled: bool) -> Result<Status, String> {
    {
        let mut s = drive.state.lock().await;
        s.enabled = enabled;
        store_saved(&s)?;
    }
    if enabled {
        drive.sync(&app).await?;
    }
    Ok(drive.status().await)
}

#[tauri::command]
pub async fn drive_sign_out(drive: State<'_, Arc<Drive>>) -> Result<Status, String> {
    {
        let mut s = drive.state.lock().await;
        if !s.refresh_token.is_empty() {
            let _ = drive.http.post("https://oauth2.googleapis.com/revoke").form(&[("token", s.refresh_token.as_str())]).send().await;
        }
        *s = Saved::default();
        store_saved(&s)?;
    }
    *drive.token.lock().await = None;
    Ok(drive.status().await)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn t(id: &str) -> Value {
        json!({ "id": id, "title": id })
    }
    fn p(id: &str, name: &str, at: i64) -> Value {
        json!({ "id": id, "name": name, "updatedAt": at, "tracks": [] })
    }
    fn ids(l: &[Value]) -> Vec<&str> {
        l.iter().filter_map(id_of).collect()
    }

    #[test]
    fn the_first_sync_joins_both_libraries() {
        let local = Snapshot { favorites: vec![t("a"), t("b")], ..Default::default() };
        let remote = Snapshot { favorites: vec![t("b"), t("c")], ..Default::default() };
        let m = merge(&Base::default(), &local, &remote);
        assert_eq!(ids(&m.favorites), ["a", "b", "c"]);
    }

    #[test]
    fn a_deletion_on_either_side_wins_over_the_base() {
        let base = Base { favorites: vec!["a".into(), "b".into(), "c".into()], ..Default::default() };
        // The phone removed b; the computer removed c and added d.
        let remote = Snapshot { favorites: vec![t("a"), t("c")], ..Default::default() };
        let local = Snapshot { favorites: vec![t("a"), t("b"), t("d")], ..Default::default() };
        let m = merge(&base, &local, &remote);
        assert_eq!(ids(&m.favorites), ["a", "d"]);
    }

    #[test]
    fn the_newer_edit_of_a_playlist_wins() {
        let base = Base { playlists: vec!["p".into()], ..Default::default() };
        let local = Snapshot { playlists: vec![p("p", "Old", 10)], ..Default::default() };
        let remote = Snapshot { playlists: vec![p("p", "Renamed on the phone", 20)], ..Default::default() };
        let m = merge(&base, &local, &remote);
        assert_eq!(m.playlists[0]["name"], "Renamed on the phone");
    }

    #[test]
    fn history_joins_without_repeats() {
        let local = Snapshot { history: vec![t("x"), t("y")], ..Default::default() };
        let remote = Snapshot { history: vec![t("y"), t("z")], ..Default::default() };
        let m = merge(&Base::default(), &local, &remote);
        assert_eq!(ids(&m.history), ["x", "y", "z"]);
    }

    #[test]
    fn merging_again_changes_nothing() {
        let local = Snapshot { favorites: vec![t("a")], playlists: vec![p("p", "P", 1)], history: vec![t("h")] };
        let remote = Snapshot { favorites: vec![t("b")], ..Default::default() };
        let m = merge(&Base::default(), &local, &remote);
        let again = merge(&base_of(&m), &m, &m);
        assert_eq!(again, m);
    }
}
