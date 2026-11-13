//! The library kept the same on the listener's devices (Tauri: libsync.rs, drive.rs and
//! drive.svelte.ts): with the phones of the account on this network over LFly Connect, and from
//! far away through Google Drive. lfly-core does the work; this starts it, tells it who is in
//! sight and when the library changed here, and keeps what Settings shows.
//!
//! Test runs (LFLY_MUTE, LFLY_AUTOPLAY) work on the listener's real library and account, so
//! nothing of this starts in them: no offers, no answers to a phone's offer, no Drive. Only
//! LFLY_SYNC_TEST=1 lets one sync (on a copy of the config). The service is installed in one
//! place, [SyncModel::start]; everything else asks whether it is there and does nothing if not.

use std::sync::{Arc, Mutex, OnceLock};

use gpui_kit::*;
use lfly_connect::protocol::Device;
use lfly_connect::Discovery;
use lfly_core::drive::Drive;
use lfly_core::libsync::{LibSync, Link};
use serde_json::Value;

use crate::core::runtime;
use crate::library_model::LibraryModel;
use crate::toast::Toasts;

#[derive(Clone)]
struct Service {
    libsync: Arc<LibSync>,
    drive: Arc<Drive>,
}

/// The running sync; empty in test runs and until the window starts it.
static SERVICE: OnceLock<Service> = OnceLock::new();

/// Who this computer is on LFly Connect, as ConnectModel resolved it, and the network it sees.
#[derive(Default)]
struct Who {
    me: Option<Device>,
    secret: Option<String>,
    discovery: Option<Arc<Discovery>>,
}

static WHO: Mutex<Who> = Mutex::new(Who { me: None, secret: None, discovery: None });

fn who() -> std::sync::MutexGuard<'static, Who> {
    WHO.lock().unwrap_or_else(|e| e.into_inner())
}

/// Whether a run may sync: a normal one, or a test run that asked for it.
fn allowed(mute: bool, autoplay: bool, opt_in: bool) -> bool {
    !(mute || autoplay) || opt_in
}

fn allowed_now() -> bool {
    // Never under `cargo test`: it runs with the listener's own folders.
    !cfg!(test)
        && allowed(std::env::var_os("LFLY_MUTE").is_some(), std::env::var_os("LFLY_AUTOPLAY").is_some(), std::env::var_os("LFLY_SYNC_TEST").is_some())
}

/// ConnectModel's identity: at start, and when the name or the account changes.
pub fn set_identity(me: &Device, secret: Option<&str>) {
    let mut who = who();
    who.me = Some(me.clone());
    who.secret = secret.map(str::to_string);
}

pub fn set_discovery(discovery: Option<Arc<Discovery>>) {
    who().discovery = discovery;
}

fn link() -> Option<Link> {
    let who = who();
    Some(Link { me: who.me.clone()?, secret: who.secret.clone()?, devices: who.discovery.as_ref().map(|d| d.devices()).unwrap_or_default() })
}

/// A phone of the account offers its library (the player's `library` hook): merged and answered,
/// or declined when the sync does not run.
pub fn answer(peer: &str, offered: Value) -> Option<Value> {
    SERVICE.get()?.libsync.answer(peer, offered)
}

/// Favorites, playlists or history were saved here: the devices and Drive hear of it shortly.
pub fn changed() {
    let Some(s) = SERVICE.get() else { return };
    runtime().spawn(s.libsync.changed());
    runtime().spawn(s.drive.changed());
}

enum Ev {
    LibraryChanged,
    Local(lfly_core::libsync::Status),
    Drive(lfly_core::drive::Status),
}

pub struct SyncModel {
    service: Option<Service>,
    /// The sync over LFly Connect, with the phones of the account on this network.
    pub local: lfly_core::libsync::Status,
    pub local_busy: bool,
    pub drive: lfly_core::drive::Status,
    pub drive_busy: bool,
    pub drive_error: String,
}

struct SyncGlobal(Entity<SyncModel>);
impl Global for SyncGlobal {}

impl SyncModel {
    pub fn start(library: Entity<LibraryModel>, cx: &mut App) -> Entity<Self> {
        let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<Ev>();
        let service = allowed_now().then(|| {
            let (local_tx, drive_tx) = (tx.clone(), tx);
            SERVICE
                .get_or_init(|| Service {
                    libsync: Arc::new(LibSync::new(link, move |e| {
                        let _ = local_tx.send(match e {
                            lfly_core::libsync::Event::LibraryChanged => Ev::LibraryChanged,
                            lfly_core::libsync::Event::Status(s) => Ev::Local(s),
                        });
                    })),
                    drive: Arc::new(Drive::new(move |e| {
                        let _ = drive_tx.send(match e {
                            lfly_core::drive::Event::LibraryChanged => Ev::LibraryChanged,
                            lfly_core::drive::Event::Status(s) => Ev::Drive(s),
                        });
                    })),
                })
                .clone()
        });
        let off = lfly_core::drive::Status { configured: false, signed_in: false, enabled: false, email: None, last_sync: None };
        let (local, drive) = match &service {
            Some(s) => {
                runtime().spawn(s.libsync.clone().run());
                runtime().spawn(s.drive.clone().run());
                (s.libsync.status(), runtime().block_on(s.drive.status()))
            }
            None => {
                log::info!("Library sync: off in this run");
                (lfly_core::libsync::Status { enabled: false, last_sync: None }, off)
            }
        };
        let e = cx.new(|_| Self { service, local, local_busy: false, drive, drive_busy: false, drive_error: String::new() });
        let weak = e.downgrade();
        cx.spawn(async move |cx| {
            while let Some(ev) = rx.recv().await {
                let r = weak.update(cx, |m, cx| {
                    match ev {
                        // A sync rewrote the files: the window reads them again.
                        Ev::LibraryChanged => library.update(cx, |l, cx| l.reload(cx)),
                        Ev::Local(s) => m.local = s,
                        Ev::Drive(s) => m.drive = s,
                    }
                    cx.notify();
                });
                if r.is_err() {
                    break;
                }
            }
        })
        .detach();
        cx.set_global(SyncGlobal(e.clone()));
        e
    }

    pub fn entity(cx: &App) -> Entity<Self> {
        cx.global::<SyncGlobal>().0.clone()
    }

    /// Whether the sync runs at all (not in test runs): Settings greys its controls otherwise.
    pub fn active(&self) -> bool {
        self.service.is_some()
    }

    /// The switch of "Sulla stessa rete Wi-Fi" (drive.svelte.ts `setLocal`).
    pub fn set_local(&mut self, on: bool, cx: &mut Context<Self>) {
        let Some(s) = self.service.clone() else { return };
        self.local_busy = true;
        cx.notify();
        cx.spawn(async move |this, cx| {
            let status = runtime()
                .spawn(async move {
                    s.libsync.set_enabled(on);
                    if on {
                        s.libsync.sync_all().await;
                    }
                    s.libsync.status()
                })
                .await;
            let _ = this.update(cx, |this, cx| {
                if let Ok(status) = status {
                    this.local = status;
                }
                this.local_busy = false;
                cx.notify();
            });
        })
        .detach();
    }

    /// "Sincronizza ora" with the phones (`syncLocalNow`).
    pub fn sync_local_now(&mut self, cx: &mut Context<Self>) {
        let Some(s) = self.service.clone() else { return };
        self.local_busy = true;
        cx.notify();
        cx.spawn(async move |this, cx| {
            let done = runtime()
                .spawn(async move {
                    if !s.libsync.enabled() {
                        return Err("La sincronizzazione è spenta".to_string());
                    }
                    let n = s.libsync.sync_all().await;
                    Ok((n, s.libsync.status()))
                })
                .await
                .unwrap_or_else(|e| Err(e.to_string()));
            let _ = this.update(cx, |this, cx| {
                match done {
                    Ok((n, status)) => {
                        this.local = status;
                        Toasts::say(
                            match n {
                                0 => "Nessun dispositivo del tuo account in rete".to_string(),
                                1 => "Libreria allineata con un dispositivo".to_string(),
                                n => format!("Libreria allineata con {n} dispositivi"),
                            },
                            cx,
                        );
                    }
                    Err(e) => Toasts::say(e, cx),
                }
                this.local_busy = false;
                cx.notify();
            });
        })
        .detach();
    }

    /// One Drive action (`run`): busy meanwhile, then the new status, [said] or the error.
    fn drive_run<F>(&mut self, said: fn(bool) -> Option<&'static str>, f: impl FnOnce(Arc<Drive>) -> F + Send + 'static, cx: &mut Context<Self>)
    where
        F: std::future::Future<Output = Result<bool, String>> + Send + 'static,
    {
        let Some(s) = self.service.clone() else { return };
        self.drive_busy = true;
        self.drive_error.clear();
        cx.notify();
        cx.spawn(async move |this, cx| {
            let (done, status) = match runtime()
                .spawn(async move {
                    let done = f(s.drive.clone()).await;
                    (done, s.drive.status().await)
                })
                .await
            {
                Ok((done, status)) => (done, Some(status)),
                Err(e) => (Err(e.to_string()), None),
            };
            let _ = this.update(cx, |this, cx| {
                if let Some(status) = status {
                    this.drive = status;
                }
                match done {
                    Ok(changed) => {
                        if let Some(text) = said(changed) {
                            Toasts::say(text, cx);
                        }
                    }
                    Err(e) => this.drive_error = e,
                }
                this.drive_busy = false;
                cx.notify();
            });
        })
        .detach();
    }

    /// "Collega Google Drive": Google's page in the browser, then the first sync (`signIn`).
    pub fn drive_sign_in(&mut self, cx: &mut Context<Self>) {
        self.drive_run(
            |_| Some("Sincronizzazione con Google Drive attiva"),
            |d| async move {
                d.sign_in().await?;
                d.sync().await
            },
            cx,
        );
    }

    /// "Sincronizza ora" with Drive (`syncNow`).
    pub fn drive_sync_now(&mut self, cx: &mut Context<Self>) {
        self.drive_run(|changed| Some(if changed { "Libreria aggiornata dal telefono" } else { "Libreria già allineata" }), |d| async move { d.sync().await }, cx);
    }

    /// The switch of "Sincronizza tramite Google Drive" (`setEnabled`).
    pub fn drive_set_enabled(&mut self, on: bool, cx: &mut Context<Self>) {
        self.drive_run(
            |_| None,
            move |d| async move {
                d.set_enabled(on).await?;
                if on {
                    d.sync().await?;
                }
                Ok(false)
            },
            cx,
        );
    }

    /// "Scollega" (`signOut`).
    pub fn drive_sign_out(&mut self, cx: &mut Context<Self>) {
        self.drive_run(|_| None, |d| async move { d.sign_out().await.map(|()| false) }, cx);
    }
}

/// "14:05", as Settings says when the last sync was.
pub fn clock(seconds: u64) -> String {
    chrono::DateTime::from_timestamp(seconds as i64, 0).map(|t| t.with_timezone(&chrono::Local).format("%H:%M").to_string()).unwrap_or_default()
}

#[cfg(test)]
mod tests {
    // Not `super::*`: gpui's own `test` attribute would shadow std's.
    use super::{allowed, allowed_now, answer, changed, SERVICE};

    #[test]
    fn test_runs_do_not_sync_unless_asked() {
        assert!(allowed(false, false, false), "a normal run syncs");
        assert!(!allowed(true, false, false), "LFLY_MUTE");
        assert!(!allowed(false, true, false), "LFLY_AUTOPLAY");
        assert!(!allowed(true, true, false));
        assert!(allowed(true, false, true), "LFLY_SYNC_TEST lets a test run sync");
        assert!(allowed(false, true, true));
        // And never under `cargo test`, whatever the environment says.
        assert!(!allowed_now());
    }

    #[test]
    fn nothing_installed_declines_offers_and_ignores_changes() {
        // An offer that would add a song: declined before the library is even read.
        let offer = serde_json::json!({ "favorites": [{ "id": "from-a-phone" }], "playlists": [], "history": [] });
        assert!(answer("phone", offer).is_none());
        changed();
        assert!(SERVICE.get().is_none());
    }
}
