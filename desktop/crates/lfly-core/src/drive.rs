//! Sync with the phone through the listener's Google Drive.
//!
//! Both apps keep one file, `lfly-library.json`, in Drive's hidden app folder (`appDataFolder`:
//! only LFly sees it, and it does not count as one of the listener's files). Whoever syncs
//! downloads it, merges it with its own library and uploads the result when it differs: the
//! phone and the computer meet there without being on at the same time.
//!
//! The merge is the three-way one of `library_merge.rs`, against what the file held when this
//! device last synced (the base). The phone's `DriveSync.kt` merges the same way.
//!
//! Sign-in is Google's flow for desktop apps (a browser tab, a code back on a loopback port, PKCE).
//! The OAuth client comes from the listener's own Google Cloud project, in
//! `~/.config/lfly/google-oauth.json` (the JSON the console downloads); the phone's Android client
//! must belong to the same project, or the two apps would see different app folders.
//!
//! Nothing here knows which window runs it: the app spawns [Drive::run] and [Drive::changed] on
//! its own tokio runtime and hears what happened through the listener it gave.

use std::collections::HashMap;
use std::future::Future;
use std::io::{Read, Write};
use std::net::TcpListener;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use base64::Engine;
use rand::Rng;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use tokio::sync::Mutex;

use crate::library;
use crate::library_merge::{base_of, merge, Base, Snapshot};

const SCOPE: &str = "https://www.googleapis.com/auth/drive.appdata";
const FILE_NAME: &str = "lfly-library.json";
/// How often the computer looks for the phone's changes while it is open.
const EVERY: Duration = Duration::from_secs(180);

// --- files on this computer --------------------------------------------------------------------

fn config_dir() -> Option<PathBuf> {
    Some(dirs::config_dir()?.join("lfly"))
}

#[derive(Clone, Deserialize)]
struct Client {
    client_id: String,
    client_secret: String,
}

/// Where a [Drive] keeps its files: the listener's own folders, or others in tests.
#[derive(Clone, Default)]
struct Places {
    /// `google-oauth.json` and `drive.json`; None: `~/.config/lfly`.
    config: Option<PathBuf>,
    /// The library files; None: [library::dir].
    library: Option<PathBuf>,
}

impl Places {
    fn config(&self) -> Option<PathBuf> {
        self.config.clone().or_else(config_dir)
    }

    fn library(&self) -> Result<PathBuf, String> {
        match &self.library {
            Some(dir) => Ok(dir.clone()),
            None => library::dir(),
        }
    }


    /// The OAuth client: Google's downloaded JSON (`{"installed": {...}}`) or the bare fields.
    fn client(&self) -> Option<Client> {
        let text = std::fs::read_to_string(self.config()?.join("google-oauth.json")).ok()?;
        let v: Value = serde_json::from_str(&text).ok()?;
        let inner = v.get("installed").cloned().unwrap_or(v);
        serde_json::from_value(inner).ok()
    }

    fn saved_path(&self) -> Option<PathBuf> {
        Some(self.config()?.join("drive.json"))
    }

    fn load_saved(&self) -> Saved {
        self.saved_path().and_then(|p| std::fs::read(p).ok()).and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default()
    }

    fn store_saved(&self, s: &Saved) -> Result<(), String> {
        let path = self.saved_path().ok_or("Nessuna cartella di configurazione")?;
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

fn now_s() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_secs())
}

// --- Google ------------------------------------------------------------------------------------

/// What a sync did, for the window.
#[derive(Clone)]
pub enum Event {
    /// The library files were rewritten: show them again.
    LibraryChanged,
    Status(Status),
}

pub struct Drive {
    places: Places,
    listener: Box<dyn Fn(Event) + Send + Sync>,
    http: reqwest::Client,
    state: Mutex<Saved>,
    token: Mutex<Option<(String, SystemTime)>>,
    /// One sync at a time.
    running: Mutex<()>,
    /// The latest [Drive::changed], so that only the last of a burst of edits syncs.
    pending: AtomicU64,
}

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct Status {
    /// The OAuth client file is there.
    pub configured: bool,
    pub signed_in: bool,
    pub enabled: bool,
    pub email: Option<String>,
    pub last_sync: Option<u64>,
}

impl Drive {
    /// The listener's Drive sync, on their own files. [listener] hears what a sync did.
    pub fn new(listener: impl Fn(Event) + Send + Sync + 'static) -> Self {
        Self::with(Places::default(), listener)
    }

    /// One that keeps `google-oauth.json` and `drive.json` in [config] and syncs the library in
    /// [library]: for tests, which must never touch the listener's own.
    pub fn at(config: PathBuf, library: PathBuf, listener: impl Fn(Event) + Send + Sync + 'static) -> Self {
        Self::with(Places { config: Some(config), library: Some(library) }, listener)
    }

    fn with(places: Places, listener: impl Fn(Event) + Send + Sync + 'static) -> Self {
        Drive {
            http: reqwest::Client::builder().timeout(Duration::from_secs(30)).build().unwrap_or_default(),
            state: Mutex::new(places.load_saved()),
            places,
            listener: Box::new(listener),
            token: Mutex::new(None),
            running: Mutex::new(()),
            pending: AtomicU64::new(0),
        }
    }

    pub async fn status(&self) -> Status {
        let s = self.state.lock().await;
        Status {
            configured: self.places.client().is_some(),
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
        let c = self.places.client().ok_or("Manca il file google-oauth.json")?;
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
                let _ = self.places.store_saved(&s);
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
    pub async fn sync(&self) -> Result<bool, String> {
        let _one = self.running.lock().await;
        let token = self.access_token().await?;
        let dir = self.places.library()?;
        let local = Snapshot::load(&dir);
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
            merged.save(&dir)?;
            (self.listener)(Event::LibraryChanged);
        }
        if remote.as_ref() != Some(&merged) {
            self.upload(&token, id.as_deref(), &merged).await?;
        }
        let mut s = self.state.lock().await;
        s.base = base_of(&merged);
        s.last_sync = Some(now_s());
        self.places.store_saved(&s)?;
        (self.listener)(Event::Status(Status { configured: true, signed_in: true, enabled: s.enabled, email: s.email.clone(), last_sync: s.last_sync }));
        Ok(changed_here)
    }

    /// Opens Google's sign-in in the browser and waits for its answer on a loopback port.
    pub async fn sign_in(&self) -> Result<(), String> {
        let c = self.places.client().ok_or("Manca il file google-oauth.json: guarda le istruzioni nelle Impostazioni")?;
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

        let r: Value = self
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
            *self.token.lock().await = Some((t.to_string(), SystemTime::now() + Duration::from_secs(secs)));
        }
        let mut s = self.state.lock().await;
        s.refresh_token = refresh.to_string();
        s.email = email;
        s.enabled = true;
        self.places.store_saved(&s)
    }

    /// Turns the sync on or off (the saved choice only: the caller syncs when it turns on).
    pub async fn set_enabled(&self, enabled: bool) -> Result<(), String> {
        let mut s = self.state.lock().await;
        s.enabled = enabled;
        self.places.store_saved(&s)
    }

    /// Takes the access back from Google and forgets the account here.
    pub async fn sign_out(&self) -> Result<(), String> {
        {
            let mut s = self.state.lock().await;
            if !s.refresh_token.is_empty() {
                let _ = self.http.post("https://oauth2.googleapis.com/revoke").form(&[("token", s.refresh_token.as_str())]).send().await;
            }
            *s = Saved::default();
            self.places.store_saved(&s)?;
        }
        *self.token.lock().await = None;
        Ok(())
    }

    /// Syncs at start, every few minutes, while it is on. Never returns: spawn it.
    pub async fn run(self: Arc<Self>) {
        tokio::time::sleep(Duration::from_secs(4)).await;
        loop {
            if self.status().await.enabled {
                if let Err(e) = self.sync().await {
                    log::warn!("drive sync: {e}");
                }
            }
            tokio::time::sleep(EVERY).await;
        }
    }

    /// The library was saved here: sync a little later, once the edits settle. Spawn what it
    /// returns; the call itself marks the edit, so that an older one still waiting gives way.
    pub fn changed(self: &Arc<Self>) -> impl Future<Output = ()> + Send + 'static {
        let ticket = self.pending.fetch_add(1, Ordering::SeqCst) + 1;
        let drive = self.clone();
        async move {
            tokio::time::sleep(Duration::from_secs(8)).await;
            if drive.pending.load(Ordering::SeqCst) != ticket {
                return;
            }
            if drive.status().await.enabled {
                if let Err(e) = drive.sync().await {
                    log::warn!("drive sync: {e}");
                }
            }
        }
    }
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

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn the_choice_is_kept_in_its_own_folder_and_signed_out_means_off() {
        let tmp = std::env::temp_dir().join(format!("lfly-drive-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&tmp);
        std::fs::create_dir_all(&tmp).unwrap();
        let drive = Drive::at(tmp.clone(), tmp.clone(), |_| {});
        let s = drive.status().await;
        assert!(!s.configured && !s.signed_in && !s.enabled);
        // On, but nobody is signed in: still off for the loop, and no sync without an account.
        drive.set_enabled(true).await.unwrap();
        assert!(tmp.join("drive.json").exists());
        assert!(!drive.status().await.enabled);
        assert!(drive.sync().await.is_err());
        // The JSON as the console downloads it.
        std::fs::write(tmp.join("google-oauth.json"), r#"{"installed":{"client_id":"x","client_secret":"y"}}"#).unwrap();
        assert!(drive.status().await.configured);
        std::fs::remove_dir_all(&tmp).ok();
    }
}
