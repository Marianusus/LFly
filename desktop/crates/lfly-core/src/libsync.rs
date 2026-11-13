//! Library sync with the listener's other devices over LFly Connect, on the same network and the
//! same account: no cloud, nothing to set up. This computer opens a short link to each phone of
//! the account it sees (an outgoing link, so a firewall here does not get in the way), offers its
//! library and takes back the merged one. A phone that opens the link to us is answered the same.
//!
//! The merge is the three-way one of `library_merge.rs` (and the phone's `LibrarySync.kt`): each
//! device keeps, per peer, which songs and playlists they agreed on last time (the base), so
//! deletions travel too.
//!
//! Nothing here knows which window runs it: the app says who is in sight ([Link]) and hears what
//! happened ([Event]) through the two closures it gives, and spawns [LibSync::run] and
//! [LibSync::changed] on its own tokio runtime.

use std::collections::HashMap;
use std::future::Future;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use lfly_connect::protocol::Device;
use lfly_connect::ConnectDevice;
use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::library;
use crate::library_merge::{base_of, merge, Base, Snapshot};

/// How often the computer offers its library to the phones it sees.
const EVERY: Duration = Duration::from_secs(90);

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct Saved {
    enabled: bool,
    /// What this computer and each peer agreed on last time, by peer id.
    peers: HashMap<String, Base>,
    /// Last sync with each peer, in seconds.
    last: HashMap<String, u64>,
}

impl Default for Saved {
    fn default() -> Self {
        Saved { enabled: true, peers: HashMap::new(), last: HashMap::new() }
    }
}

/// Who an offer goes out as and to: asked from the app each time, since the name, the account
/// and the devices in sight all change while it runs.
pub struct Link {
    pub me: Device,
    /// The account's LFly Connect secret: only devices that prove the same one are offered to.
    pub secret: String,
    /// The devices on the network; those of another account are left alone.
    pub devices: Vec<ConnectDevice>,
}

/// What a sync did, for the window.
#[derive(Clone)]
pub enum Event {
    /// The library files were rewritten: show them again.
    LibraryChanged,
    Status(Status),
}

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct Status {
    pub enabled: bool,
    /// Most recent sync with any device, in seconds.
    pub last_sync: Option<u64>,
}

pub struct LibSync {
    /// `library-sync.json`.
    path: Option<PathBuf>,
    /// The library files; None: [library::dir].
    library: Option<PathBuf>,
    state: Mutex<Saved>,
    /// One merge at a time, offered or answered: both read and write the library files.
    merging: Mutex<()>,
    /// The latest [LibSync::changed], so that only the last of a burst of edits offers.
    ticket: AtomicU64,
    link: Box<dyn Fn() -> Option<Link> + Send + Sync>,
    listener: Box<dyn Fn(Event) + Send + Sync>,
}

fn now_s() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_secs())
}

impl LibSync {
    /// The listener's library sync, on their own files (`~/.config/lfly/library-sync.json`).
    /// [link] says who to offer to (None: not signed in, nobody); [listener] hears what a sync did.
    pub fn new(link: impl Fn() -> Option<Link> + Send + Sync + 'static, listener: impl Fn(Event) + Send + Sync + 'static) -> Self {
        Self::with(dirs::config_dir().map(|d| d.join("lfly").join("library-sync.json")), None, link, listener)
    }

    /// One that keeps its state in [state] and syncs the library in [library]: for tests, which
    /// must never touch the listener's own.
    pub fn at(state: PathBuf, library: PathBuf, link: impl Fn() -> Option<Link> + Send + Sync + 'static, listener: impl Fn(Event) + Send + Sync + 'static) -> Self {
        Self::with(Some(state), Some(library), link, listener)
    }

    fn with(path: Option<PathBuf>, library: Option<PathBuf>, link: impl Fn() -> Option<Link> + Send + Sync + 'static, listener: impl Fn(Event) + Send + Sync + 'static) -> Self {
        let saved = path.as_ref().and_then(|p| std::fs::read(p).ok()).and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default();
        LibSync {
            path,
            library,
            state: Mutex::new(saved),
            merging: Mutex::new(()),
            ticket: AtomicU64::new(0),
            link: Box::new(link),
            listener: Box::new(listener),
        }
    }

    fn with_state<T>(&self, f: impl FnOnce(&mut Saved) -> T) -> T {
        f(&mut self.state.lock().unwrap())
    }

    fn save(&self) {
        let Some(p) = &self.path else { return };
        let bytes = self.with_state(|s| serde_json::to_vec(s).unwrap_or_default());
        let _ = std::fs::create_dir_all(p.parent().unwrap());
        let tmp = p.with_extension("json.tmp");
        if std::fs::write(&tmp, bytes).is_ok() {
            let _ = std::fs::rename(tmp, p);
        }
    }

    fn dir(&self) -> Result<PathBuf, String> {
        match &self.library {
            Some(dir) => Ok(dir.clone()),
            None => library::dir(),
        }
    }

    fn local(&self) -> Result<Snapshot, String> {
        Ok(Snapshot::load(&self.dir()?))
    }

    /// Writes `merged` here if it differs from `local`; tells the window.
    fn apply(&self, local: &Snapshot, merged: &Snapshot) -> Result<bool, String> {
        if merged == local {
            return Ok(false);
        }
        merged.save(&self.dir()?)?;
        (self.listener)(Event::LibraryChanged);
        Ok(true)
    }

    pub fn enabled(&self) -> bool {
        self.with_state(|s| s.enabled)
    }

    /// Turns the sync on or off (the saved choice only: the caller offers when it turns on).
    pub fn set_enabled(&self, enabled: bool) {
        self.with_state(|s| s.enabled = enabled);
        self.save();
    }

    pub fn status(&self) -> Status {
        self.with_state(|s| Status { enabled: s.enabled, last_sync: s.last.values().max().copied() })
    }

    /// A phone of the account offers its library (we are its host): merge, keep, answer.
    pub fn answer(&self, peer: &str, offered: Value) -> Option<Value> {
        if !self.enabled() {
            return None;
        }
        let _one = self.merging.lock().unwrap();
        let remote: Snapshot = serde_json::from_value(offered).ok()?;
        let mine = self.local().ok()?;
        let base = self.with_state(|s| s.peers.get(peer).cloned().unwrap_or_default());
        let merged = merge(&base, &mine, &remote);
        if let Err(e) = self.apply(&mine, &merged) {
            log::warn!("library sync from {peer}: {e}");
            return None;
        }
        self.with_state(|s| {
            s.peers.insert(peer.to_string(), base_of(&merged));
            s.last.insert(peer.to_string(), now_s());
        });
        self.save();
        log::info!("Library merged with an offer from {peer}");
        serde_json::to_value(&merged).ok()
    }

    /// What [peer] merged from our offer comes back: keep it, with the edits made here while the
    /// link was open merged on top.
    fn take_back(&self, peer: &str, merged: &Snapshot) -> Result<(), String> {
        let _one = self.merging.lock().unwrap();
        let now = self.local()?;
        let base = self.with_state(|s| s.peers.get(peer).cloned().unwrap_or_default());
        let last = merge(&base, &now, merged);
        self.apply(&now, &last)?;
        self.with_state(|s| {
            s.peers.insert(peer.to_string(), base_of(merged));
            s.last.insert(peer.to_string(), now_s());
        });
        self.save();
        Ok(())
    }

    /// Offers the library to every phone of the account in sight; returns how many answered.
    pub async fn sync_all(&self) -> usize {
        if !self.enabled() {
            return 0;
        }
        let Some(Link { me, secret, devices }) = (self.link)() else {
            return 0;
        };
        let peers: Vec<_> = devices.into_iter().filter(|d| d.same_account && d.id != me.id).collect();
        let mut synced = 0;
        for peer in peers {
            let Ok(offer) = self.local().and_then(|l| serde_json::to_value(&l).map_err(|e| e.to_string())) else { continue };
            match lfly_connect::library::exchange(me.clone(), &secret, &peer, offer).await {
                Ok(merged) => {
                    let Ok(merged) = serde_json::from_value::<Snapshot>(merged) else { continue };
                    if let Err(e) = self.take_back(&peer.id, &merged) {
                        log::warn!("library sync with '{}': {e}", peer.name);
                        continue;
                    }
                    synced += 1;
                    log::info!("Library synced with '{}'", peer.name);
                }
                Err(e) => log::info!("library sync with '{}': {e}", peer.name),
            }
        }
        if synced > 0 {
            (self.listener)(Event::Status(self.status()));
        }
        synced
    }

    /// Offers a little after the start, then every minute and a half. Never returns: spawn it.
    pub async fn run(self: Arc<Self>) {
        tokio::time::sleep(Duration::from_secs(6)).await;
        loop {
            self.sync_all().await;
            tokio::time::sleep(EVERY).await;
        }
    }

    /// The library changed here: offer it shortly, once the edits settle. Spawn what it returns;
    /// the call itself marks the edit, so that an older one still waiting gives way.
    pub fn changed(self: &Arc<Self>) -> impl Future<Output = ()> + Send + 'static {
        let ticket = self.ticket.fetch_add(1, Ordering::SeqCst) + 1;
        let sync = self.clone();
        async move {
            tokio::time::sleep(Duration::from_secs(5)).await;
            if sync.ticket.load(Ordering::SeqCst) == ticket {
                sync.sync_all().await;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    /// A sync on a folder of its own (never the listener's), and how often it said the library
    /// changed.
    fn sync_in(name: &str) -> (Arc<LibSync>, PathBuf, Arc<AtomicU64>) {
        let tmp = std::env::temp_dir().join(format!("lfly-libsync-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&tmp);
        std::fs::create_dir_all(&tmp).unwrap();
        let changes = Arc::new(AtomicU64::new(0));
        let seen = changes.clone();
        let sync = LibSync::at(tmp.join("library-sync.json"), tmp.clone(), || None, move |e| {
            if matches!(e, Event::LibraryChanged) {
                seen.fetch_add(1, Ordering::SeqCst);
            }
        });
        (Arc::new(sync), tmp, changes)
    }

    fn t(id: &str) -> Value {
        json!({ "id": id, "title": id })
    }

    fn favorites(dir: &std::path::Path) -> Vec<String> {
        Snapshot::load(dir).favorites.iter().map(|t| t["id"].as_str().unwrap().to_string()).collect()
    }

    #[test]
    fn an_offer_is_merged_written_and_answered() {
        let (sync, dir, changes) = sync_in("answer");
        library::save(&dir, library::FAVORITES, &json!([t("a")])).unwrap();
        let merged = sync.answer("phone", json!({ "favorites": [t("b")], "playlists": [], "history": [] })).unwrap();
        assert_eq!(merged["favorites"].as_array().unwrap().len(), 2);
        assert_eq!(favorites(&dir), ["a", "b"]);
        assert_eq!(changes.load(Ordering::SeqCst), 1);
        assert!(sync.status().last_sync.is_some());

        // The base was kept, on disk too: the phone deleting "a" now deletes it here.
        let again = LibSync::at(dir.join("library-sync.json"), dir.clone(), || None, |_| {});
        again.answer("phone", json!({ "favorites": [t("b")] })).unwrap();
        assert_eq!(favorites(&dir), ["b"]);
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn nothing_is_answered_or_written_while_off() {
        let (sync, dir, changes) = sync_in("off");
        sync.set_enabled(false);
        assert!(sync.answer("phone", json!({ "favorites": [t("b")] })).is_none());
        assert!(!dir.join(library::FAVORITES).exists());
        assert_eq!(changes.load(Ordering::SeqCst), 0);
        // The choice is remembered.
        let again = LibSync::at(dir.join("library-sync.json"), dir.clone(), || None, |_| {});
        assert!(!again.enabled());
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn what_comes_back_keeps_the_edits_made_meanwhile() {
        let (sync, dir, _) = sync_in("back");
        // "c" was liked here while the link was open; the phone answered with a and b.
        library::save(&dir, library::FAVORITES, &json!([t("c"), t("a")])).unwrap();
        let merged = Snapshot { favorites: vec![t("a"), t("b")], ..Default::default() };
        sync.take_back("phone", &merged).unwrap();
        assert_eq!(favorites(&dir), ["c", "a", "b"]);
        std::fs::remove_dir_all(&dir).ok();
    }

    #[tokio::test]
    async fn nobody_is_offered_to_without_an_account() {
        let (sync, dir, _) = sync_in("nobody");
        assert_eq!(sync.sync_all().await, 0);
        std::fs::remove_dir_all(&dir).ok();
    }
}
