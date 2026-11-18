//! The listener's Spotify account, used only to bring their own library into LFly, as the phone
//! does (auth/SpotifyAuth.kt, account/SpotifyLibrarySync.kt; the library client is adapted from
//! BitChord, GPL-3.0): the web session cookie `sp_dc` from Spotify's real sign-in page, then the
//! web player's own token, read from the page it mints it for (the `lfly-web` helper, since this
//! app has no webview), then the web player's private API.
//!
//! Playlists become LFly playlists with the id `spl_<playlist id>` (a second import updates them
//! instead of making copies); Liked Songs join the favourites, which only grow. Songs keep their
//! Spotify ids and are matched to a stream when played, as a playlist imported from a link is.

use std::io::Write;
use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

const GQL: &str = "https://api-partner.spotify.com/pathfinder/v2/query";
const LIBRARY: &str = "973e511ca44261fda7eebac8b653155e7caee3675abb4fb110cc1b8c78b091c3";
const PLAYLIST: &str = "346811f856fb0b7e4f6c59f8ebea78dd081c6e2fb01b77c954b26259d5fc6763";
const LIKED: &str = "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240";
pub const PLAYLIST_PREFIX: &str = "spl_";
const LIKED_ID: &str = "liked";

/// What stays on disk: the cookie and how the account looks (config/lfly/spotify-account.json,
/// readable by this user only).
#[derive(Serialize, Deserialize, Clone, Default)]
#[serde(rename_all = "camelCase")]
pub struct Saved {
    pub sp_dc: String,
    pub name: Option<String>,
    pub avatar_url: Option<String>,
    /// Spotify refused the cookie last time: sign in again.
    #[serde(default)]
    pub expired: bool,
    #[serde(default)]
    pub last_sync_ms: Option<i64>,
    #[serde(default)]
    pub last_playlists: usize,
    #[serde(default)]
    pub last_liked: usize,
}

/// What an import did.
#[derive(Clone, Debug, Default)]
pub struct Summary {
    pub playlists: usize,
    pub songs: usize,
    pub liked: usize,
    pub new_favorites: usize,
}

#[derive(Debug)]
pub enum Error {
    /// No account, or Spotify refused it: sign in again.
    SignedOut,
    Other(String),
}

impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Error::SignedOut => f.write_str("Spotify sign-in expired"),
            Error::Other(e) => f.write_str(e),
        }
    }
}

impl From<String> for Error {
    fn from(e: String) -> Self {
        Error::Other(e)
    }
}

struct Tokens {
    bearer: String,
    expires_ms: i64,
    client_id: Option<String>,
    user_agent: String,
    client_token: Option<(String, i64)>,
}

static TOKENS: Mutex<Option<Tokens>> = Mutex::new(None);
static CLIENT_VERSION: Mutex<Option<String>> = Mutex::new(None);

fn now_ms() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_millis() as i64)
}

/// Where the config lives when the platform has no usual one (the iPhone's sandbox).
static CONFIG_DIR: std::sync::OnceLock<PathBuf> = std::sync::OnceLock::new();

/// Keeps the account under [dir] instead of the system's config folder.
pub fn set_config_dir(dir: PathBuf) {
    let _ = CONFIG_DIR.set(dir);
}

fn path() -> Option<PathBuf> {
    CONFIG_DIR.get().cloned().or_else(dirs::config_dir).map(|d| d.join("lfly").join("spotify-account.json"))
}

/// A token the web player gave a web view of the app itself (the iPhone has one; the desktop asks
/// lfly-web): used until it expires.
pub fn set_token(bearer: String, expires_ms: i64, client_id: Option<String>, user_agent: String) {
    *TOKENS.lock().unwrap() = Some(Tokens { bearer, expires_ms, client_id, user_agent, client_token: None });
}

pub fn load() -> Option<Saved> {
    let s = std::fs::read_to_string(path()?).ok()?;
    serde_json::from_str::<Saved>(&s).ok().filter(|s| !s.sp_dc.is_empty())
}

pub fn save(saved: &Saved) -> Result<(), String> {
    let path = path().ok_or("no config dir")?;
    if let Some(dir) = path.parent() {
        std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    }
    let tmp = path.with_extension("json.tmp");
    std::fs::write(&tmp, serde_json::to_vec(saved).map_err(|e| e.to_string())?).map_err(|e| e.to_string())?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(&tmp, std::fs::Permissions::from_mode(0o600));
    }
    std::fs::rename(&tmp, &path).map_err(|e| e.to_string())
}

/// Disconnects: the cookie and the token go; imported playlists stay.
pub fn forget() {
    if let Some(p) = path() {
        let _ = std::fs::remove_file(p);
    }
    *TOKENS.lock().unwrap() = None;
}

/// `lfly-web`, next to this program (inside LFly.app on macOS too).
pub fn helper() -> Option<PathBuf> {
    let name = if cfg!(windows) { "lfly-web.exe" } else { "lfly-web" };
    let exe = std::env::current_exe().ok()?;
    let p = exe.parent()?.join(name);
    p.exists().then_some(p)
}

/// Opens Spotify's sign-in window (blocking until it closes) and keeps the session.
pub fn sign_in() -> Result<Saved, Error> {
    let helper = helper().ok_or_else(|| Error::Other("lfly-web is missing".into()))?;
    let out = Command::new(helper).arg("spotify-login").stdin(Stdio::null()).stderr(Stdio::inherit()).output().map_err(|e| Error::Other(e.to_string()))?;
    if !out.status.success() {
        return Err(Error::Other("closed".into()));
    }
    let v: Value = serde_json::from_slice(&out.stdout).map_err(|e| Error::Other(e.to_string()))?;
    let cookie = v.get("sp_dc").and_then(Value::as_str).filter(|s| !s.is_empty()).ok_or(Error::SignedOut)?;
    *TOKENS.lock().unwrap() = None;
    let saved = Saved { sp_dc: cookie.to_string(), ..Default::default() };
    save(&saved)?;
    Ok(saved)
}

fn mark_expired() {
    *TOKENS.lock().unwrap() = None;
    if let Some(mut s) = load() {
        s.expired = true;
        let _ = save(&s);
    }
}

/// The web player's token for the account: from the cache, else read from the page (blocking,
/// up to about 35 s).
fn harvest(cookie: &str) -> Result<Tokens, Error> {
    let helper = helper().ok_or_else(|| Error::Other("lfly-web is missing".into()))?;
    let mut child = Command::new(helper)
        .arg("spotify-token")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::inherit())
        .spawn()
        .map_err(|e| Error::Other(e.to_string()))?;
    if let Some(mut stdin) = child.stdin.take() {
        let _ = writeln!(stdin, "{cookie}");
    }
    let out = child.wait_with_output().map_err(|e| Error::Other(e.to_string()))?;
    if !out.status.success() {
        log::warn!("Spotify: the web player gave no signed-in token ({})", out.status);
        return Err(Error::SignedOut);
    }
    let v: Value = serde_json::from_slice(&out.stdout).map_err(|e| Error::Other(e.to_string()))?;
    let bearer = v.get("accessToken").and_then(Value::as_str).filter(|s| !s.is_empty()).ok_or(Error::SignedOut)?;
    Ok(Tokens {
        bearer: bearer.to_string(),
        expires_ms: v.get("expiresAtMs").and_then(Value::as_i64).filter(|t| *t > now_ms()).unwrap_or(now_ms() + 50 * 60_000),
        client_id: v.get("clientId").and_then(Value::as_str).filter(|s| !s.is_empty()).map(str::to_string),
        user_agent: v.get("userAgent").and_then(Value::as_str).unwrap_or_default().to_string(),
        client_token: None,
    })
}

async fn headers(http: &reqwest::Client) -> Result<Vec<(&'static str, String)>, Error> {
    let cookie = load().filter(|s| !s.expired).ok_or(Error::SignedOut)?.sp_dc;
    let fresh = TOKENS.lock().unwrap().as_ref().is_some_and(|t| now_ms() < t.expires_ms - 30_000);
    if !fresh {
        let t = tokio::task::spawn_blocking(move || harvest(&cookie)).await.map_err(|e| Error::Other(e.to_string()))??;
        *TOKENS.lock().unwrap() = Some(t);
    }
    let (bearer, ua, client_id, client_token) = {
        let g = TOKENS.lock().unwrap();
        let t = g.as_ref().ok_or(Error::SignedOut)?;
        (t.bearer.clone(), t.user_agent.clone(), t.client_id.clone(), t.client_token.clone())
    };
    let client_token = match client_token.filter(|(_, at)| now_ms() < at - 30_000) {
        Some((t, _)) => Some(t),
        None => match client_id {
            Some(id) => {
                let got = fetch_client_token(http, &id, &ua).await;
                if let (Some((t, at)), Some(cache)) = (got.clone(), TOKENS.lock().unwrap().as_mut()) {
                    cache.client_token = Some((t, at));
                }
                got.map(|(t, _)| t)
            }
            None => None,
        },
    };
    let mut h = vec![
        ("Authorization", format!("Bearer {bearer}")),
        ("Accept", "application/json".to_string()),
        ("app-platform", "WebPlayer".to_string()),
        ("Origin", "https://open.spotify.com".to_string()),
        ("Referer", "https://open.spotify.com/".to_string()),
    ];
    if !ua.is_empty() {
        h.push(("User-Agent", ua));
    }
    if let Some(t) = client_token {
        h.push(("Client-Token", t));
    }
    Ok(h)
}

/// The web player's version, from the config its page embeds.
async fn client_version(http: &reqwest::Client, ua: &str) -> Option<String> {
    if let Some(v) = CLIENT_VERSION.lock().unwrap().clone() {
        return Some(v);
    }
    let html = http.get("https://open.spotify.com").header("User-Agent", ua).send().await.ok()?.text().await.ok()?;
    let start = html.find(r#"<script id="appServerConfig" type="text/plain">"#)?;
    let rest = &html[start..];
    let rest = &rest[rest.find('>')? + 1..];
    let encoded = &rest[..rest.find('<')?];
    use base64::Engine as _;
    let raw = base64::engine::general_purpose::STANDARD.decode(encoded.trim()).ok()?;
    let v: Value = serde_json::from_slice(&raw).ok()?;
    let version = v.get("clientVersion")?.as_str()?.to_string();
    *CLIENT_VERSION.lock().unwrap() = Some(version.clone());
    Some(version)
}

/// The second header Spotify's endpoints want besides the bearer. Best effort.
async fn fetch_client_token(http: &reqwest::Client, client_id: &str, ua: &str) -> Option<(String, i64)> {
    let version = client_version(http, ua).await?;
    let os = if cfg!(target_os = "macos") { "macos" } else if cfg!(windows) { "windows" } else { "linux" };
    let device_id = uuid_like();
    let payload = json!({
        "client_data": {
            "client_version": version,
            "client_id": client_id,
            "js_sdk_data": {
                "device_brand": "unknown",
                "device_model": "unknown",
                "os": os,
                "os_version": "unknown",
                "device_id": device_id,
                "device_type": "computer"
            }
        }
    });
    let r = http
        .post("https://clienttoken.spotify.com/v1/clienttoken")
        .header("Accept", "application/json")
        .header("Content-Type", "application/json")
        .header("User-Agent", ua)
        .body(payload.to_string())
        .send()
        .await
        .ok()?;
    if !r.status().is_success() {
        return None;
    }
    let v: Value = r.json().await.ok()?;
    if v.get("response_type")?.as_str()? != "RESPONSE_GRANTED_TOKEN_RESPONSE" {
        return None;
    }
    let g = v.get("granted_token")?;
    let token = g.get("token")?.as_str()?.to_string();
    let secs = g.get("expires_after_seconds").and_then(Value::as_i64).unwrap_or(3600);
    Some((token, now_ms() + secs * 1000))
}

fn uuid_like() -> String {
    let n = now_ms() as u128 ^ (std::process::id() as u128) << 64;
    format!("{n:032x}")
}

async fn gql(http: &reqwest::Client, operation: &str, hash: &str, variables: Value) -> Result<Value, Error> {
    let headers = headers(http).await?;
    let body = json!({
        "variables": variables,
        "operationName": operation,
        "extensions": { "persistedQuery": { "version": 1, "sha256Hash": hash } }
    });
    let mut req = http.post(GQL).header("Content-Type", "application/json; charset=utf-8").body(body.to_string());
    for (k, v) in headers {
        req = req.header(k, v);
    }
    let r = req.send().await.map_err(|e| Error::Other(e.to_string()))?;
    if r.status() == reqwest::StatusCode::UNAUTHORIZED {
        return Err(Error::SignedOut);
    }
    if !r.status().is_success() {
        return Err(Error::Other(format!("Spotify {operation} failed ({})", r.status())));
    }
    let v: Value = r.json().await.map_err(|e| Error::Other(e.to_string()))?;
    if let Some(m) = v.pointer("/errors/0/message").and_then(Value::as_str).filter(|m| !m.is_empty()) {
        return Err(Error::Other(m.to_string()));
    }
    Ok(v)
}

/// Name and picture of the connected account.
pub async fn profile(http: &reqwest::Client) -> Result<(String, Option<String>), Error> {
    let headers = headers(http).await?;
    let mut req = http.get("https://api.spotify.com/v1/me");
    for (k, v) in headers {
        req = req.header(k, v);
    }
    let r = req.send().await.map_err(|e| Error::Other(e.to_string()))?;
    if r.status() == reqwest::StatusCode::UNAUTHORIZED {
        return Err(Error::SignedOut);
    }
    let v: Value = r.json().await.map_err(|e| Error::Other(e.to_string()))?;
    let name = v
        .get("display_name")
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
        .or_else(|| v.get("id").and_then(Value::as_str))
        .unwrap_or_default()
        .to_string();
    let avatar = v
        .get("images")
        .and_then(Value::as_array)
        .and_then(|a| a.iter().max_by_key(|i| i.get("width").and_then(Value::as_i64).unwrap_or(0)))
        .and_then(|i| i.get("url").and_then(Value::as_str))
        .map(str::to_string);
    Ok((name, avatar))
}

struct Remote {
    id: String,
    name: String,
    owner: Option<String>,
    image: Option<String>,
}

/// The widest source of an image list's first entry: Spotify lists sizes in no fixed order.
fn largest(images: Option<&Value>) -> Option<String> {
    images?
        .pointer("/items/0/sources")?
        .as_array()?
        .iter()
        .filter(|s| s.get("url").and_then(Value::as_str).is_some_and(|u| !u.is_empty()))
        .max_by_key(|s| s.get("width").and_then(Value::as_i64).unwrap_or(0))
        .and_then(|s| s.get("url").and_then(Value::as_str))
        .map(str::to_string)
}

async fn playlists(http: &reqwest::Client) -> Result<Vec<Remote>, Error> {
    let mut all = vec![Remote { id: LIKED_ID.into(), name: String::new(), owner: None, image: None }];
    let mut offset = 0usize;
    loop {
        let variables = json!({
            "filters": ["Playlists"],
            "order": null,
            "textFilter": "",
            "features": ["LIKED_SONGS", "YOUR_EPISODES_V2", "PRERELEASES", "EVENTS"],
            "limit": 50,
            "offset": offset,
            "flatten": true,
            "expandedFolders": [],
            "folderUri": null,
            "includeFoldersWhenFlattening": false
        });
        let root = gql(http, "libraryV3", LIBRARY, variables).await?;
        let library = root.pointer("/data/me/libraryV3").ok_or_else(|| Error::Other("Spotify library response was empty".into()))?;
        let items = library.get("items").and_then(Value::as_array).cloned().unwrap_or_default();
        for item in &items {
            let Some(wrapper) = item.get("item") else { continue };
            if !wrapper.get("__typename").and_then(Value::as_str).unwrap_or_default().to_lowercase().contains("playlist") {
                continue;
            }
            let Some(data) = wrapper.get("data") else { continue };
            if data.get("__typename").and_then(Value::as_str) != Some("Playlist") {
                continue;
            }
            let (Some(uri), Some(name)) = (
                wrapper.get("_uri").and_then(Value::as_str).filter(|s| !s.is_empty()),
                data.get("name").and_then(Value::as_str).filter(|s| !s.is_empty()),
            ) else {
                continue;
            };
            all.push(Remote {
                id: uri.rsplit(':').next().unwrap_or(uri).to_string(),
                name: name.to_string(),
                owner: data.pointer("/ownerV2/data/name").and_then(Value::as_str).filter(|s| !s.is_empty()).map(str::to_string),
                image: largest(data.get("images")),
            });
        }
        offset += items.len();
        let total = library.get("totalCount").and_then(Value::as_u64).map_or(offset, |t| t as usize);
        if items.is_empty() || offset >= total {
            break;
        }
    }
    Ok(all)
}

fn track(data: &Value, wrapper_uri: &str) -> Option<Value> {
    let title = data.get("name").and_then(Value::as_str).filter(|s| !s.is_empty())?;
    let uri = data
        .get("uri")
        .and_then(Value::as_str)
        .filter(|u| u.starts_with("spotify:track:"))
        .or(Some(wrapper_uri).filter(|u| u.starts_with("spotify:track:")))?;
    let artists: Vec<&str> = data
        .pointer("/artists/items")
        .and_then(Value::as_array)
        .map(|l| l.iter().filter_map(|a| a.pointer("/profile/name").and_then(Value::as_str).filter(|n| !n.is_empty())).collect())
        .unwrap_or_default();
    let album = data.get("albumOfTrack");
    let duration = data
        .get("trackDuration")
        .or_else(|| data.get("duration"))
        .and_then(|d| d.get("totalMilliseconds"))
        .and_then(Value::as_i64)
        .unwrap_or(0);
    let mut t = json!({
        "id": format!("spotify_{}", uri.rsplit(':').next().unwrap_or(uri)),
        "title": title,
        "artist": artists.join(", "),
        "durationMs": duration,
        "provider": "Spotify",
    });
    if let Some(a) = album.and_then(|a| a.get("name")).and_then(Value::as_str).filter(|s| !s.is_empty()) {
        t["album"] = Value::from(a);
    }
    if let Some(c) = album.and_then(|a| a.get("coverArt")).and_then(|c| largest(Some(&json!({ "items": [c] })))) {
        t["coverUrl"] = Value::from(c);
    }
    Some(t)
}

async fn tracks(http: &reqwest::Client, playlist: &str) -> Result<Vec<Value>, Error> {
    let liked = playlist == LIKED_ID;
    let mut all = Vec::new();
    let mut offset = 0usize;
    loop {
        let root = if liked {
            gql(http, "fetchLibraryTracks", LIKED, json!({ "offset": offset, "limit": 100 })).await?
        } else {
            gql(
                http,
                "fetchPlaylist",
                PLAYLIST,
                json!({ "uri": format!("spotify:playlist:{playlist}"), "offset": offset, "limit": 100, "enableWatchFeedEntrypoint": false }),
            )
            .await?
        };
        let content = root
            .pointer(if liked { "/data/me/library/tracks" } else { "/data/playlistV2/content" })
            .ok_or_else(|| Error::Other("Spotify playlist response was empty".into()))?;
        let items = content.get("items").and_then(Value::as_array).cloned().unwrap_or_default();
        for entry in &items {
            let Some(wrapper) = entry.get(if liked { "track" } else { "itemV2" }) else { continue };
            let Some(data) = wrapper.get("data") else { continue };
            if let Some(t) = track(data, wrapper.get("_uri").and_then(Value::as_str).unwrap_or_default()) {
                all.push(t);
            }
        }
        offset += items.len();
        let total = content.get("totalCount").and_then(Value::as_u64).map_or(offset, |t| t as usize);
        if items.is_empty() || offset >= total {
            break;
        }
    }
    Ok(all)
}

fn id_of(v: &Value) -> Option<&str> {
    v.get("id").and_then(Value::as_str)
}

/// Imports every playlist of the connected account into the library at [dir]; [progress] gets
/// (playlist done, playlists). A playlist that cannot be read is skipped.
pub async fn sync(http: &reqwest::Client, dir: &std::path::Path, progress: impl Fn(usize, usize)) -> Result<Summary, Error> {
    let result = sync_inner(http, dir, progress).await;
    if matches!(result, Err(Error::SignedOut)) {
        mark_expired();
    }
    result
}

async fn sync_inner(http: &reqwest::Client, dir: &std::path::Path, progress: impl Fn(usize, usize)) -> Result<Summary, Error> {
    if let Some(mut s) = load() {
        if s.name.as_deref().unwrap_or_default().is_empty() {
            if let Ok((name, avatar)) = profile(http).await {
                s.name = Some(name);
                s.avatar_url = avatar;
                let _ = save(&s);
            }
        }
    }
    let remote = playlists(http).await?;
    let total = remote.len();
    let mut summary = Summary::default();
    let mut fetched: Vec<(Remote, Vec<Value>)> = Vec::new();
    let mut liked_tracks = Vec::new();
    for (i, r) in remote.into_iter().enumerate() {
        progress(i, total);
        match tracks(http, &r.id).await {
            Ok(t) if r.id == LIKED_ID => liked_tracks = t,
            Ok(t) => fetched.push((r, t)),
            Err(Error::SignedOut) => return Err(Error::SignedOut),
            Err(e) => log::warn!("Spotify playlist '{}' could not be read: {e}", r.name),
        }
    }
    progress(total, total);

    // The library files are read again just before writing: the app may have changed them.
    let lib = crate::library::load(dir);
    let now = now_ms();
    let mut lists = lib.playlists.as_array().cloned().unwrap_or_default();
    for (r, t) in fetched {
        let id = format!("{PLAYLIST_PREFIX}{}", r.id);
        let mut seen = std::collections::HashSet::new();
        let t: Vec<Value> = t.into_iter().filter(|x| id_of(x).is_some_and(|i| seen.insert(i.to_string()))).collect();
        summary.songs += t.len();
        summary.playlists += 1;
        match lists.iter_mut().find(|p| id_of(p) == Some(id.as_str())) {
            // Kept: the name, description and cover the listener may have changed in LFly.
            Some(p) => {
                p["tracks"] = Value::Array(t);
                p["updatedAt"] = Value::from(now);
            }
            None => {
                let mut p = json!({ "id": id, "name": r.name, "tracks": t, "schemaVersion": 2, "createdAt": now, "updatedAt": now });
                if let Some(o) = r.owner {
                    p["description"] = Value::from(o);
                }
                if let Some(c) = r.image {
                    p["coverUrl"] = Value::from(c);
                }
                lists.push(p);
            }
        }
    }
    crate::library::save(dir, crate::library::PLAYLISTS, &Value::Array(lists))?;

    // Liked Songs are LFly's favourites. They only grow: a song liked only in LFly stays.
    summary.liked = liked_tracks.len();
    let mut favorites = lib.favorites.as_array().cloned().unwrap_or_default();
    let have: std::collections::HashSet<String> = favorites.iter().filter_map(id_of).map(str::to_string).collect();
    let new: Vec<Value> = liked_tracks.into_iter().filter(|t| id_of(t).is_some_and(|i| !have.contains(i))).collect();
    summary.new_favorites = new.len();
    if !new.is_empty() {
        favorites.splice(0..0, new);
        crate::library::save(dir, crate::library::FAVORITES, &Value::Array(favorites))?;
    }
    if let Some(mut s) = load() {
        s.last_sync_ms = Some(now);
        s.last_playlists = summary.playlists;
        s.last_liked = summary.liked;
        let _ = save(&s);
    }
    log::info!(
        "Spotify library imported: {} playlists, {} songs, {} liked ({} new)",
        summary.playlists,
        summary.songs,
        summary.liked,
        summary.new_favorites
    );
    Ok(summary)
}
