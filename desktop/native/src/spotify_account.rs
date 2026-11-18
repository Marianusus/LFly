//! The Spotify account in Settings › Account (the phone's ConnectedAccountsSections and
//! SpotifyLibrarySync): connect through Spotify's sign-in window, bring the playlists and Liked
//! Songs into the library, disconnect. The work is lfly-core's spotify_account.

use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::Duration;

use gpui_kit::*;
use lfly_core::spotify_account::{self as sa, Saved, Summary};

use crate::core::runtime;
use crate::i18n::{tr, trf};
use crate::library_model::LibraryModel;
use crate::toast::Toasts;

#[derive(Clone, Copy, PartialEq)]
pub enum Busy {
    Idle,
    SigningIn,
    /// (playlists read, playlists).
    Importing(usize, usize),
}

pub struct SpotifyModel {
    library: Entity<LibraryModel>,
    http: reqwest::Client,
    pub saved: Option<Saved>,
    pub busy: Busy,
    pub last: Option<Summary>,
}

struct SpotifyGlobal(Entity<SpotifyModel>);
impl Global for SpotifyGlobal {}

impl SpotifyModel {
    pub fn start(library: Entity<LibraryModel>, cx: &mut App) -> Entity<Self> {
        let e = cx.new(|_| Self { library, http: reqwest::Client::new(), saved: sa::load(), busy: Busy::Idle, last: None });
        cx.set_global(SpotifyGlobal(e.clone()));
        e
    }

    pub fn entity(cx: &App) -> Entity<Self> {
        cx.global::<SpotifyGlobal>().0.clone()
    }

    /// Whether this build carries the web helper the sign-in needs.
    pub fn available() -> bool {
        sa::helper().is_some()
    }

    /// Opens Spotify's sign-in window; once signed in, imports the library.
    pub fn connect(&mut self, cx: &mut Context<Self>) {
        if self.busy != Busy::Idle {
            return;
        }
        self.busy = Busy::SigningIn;
        cx.notify();
        cx.spawn(async move |this, cx| {
            let r = cx.background_executor().spawn(async move { sa::sign_in() }).await;
            let _ = this.update(cx, |this, cx| {
                this.busy = Busy::Idle;
                match r {
                    Ok(saved) => {
                        this.saved = Some(saved);
                        this.import(cx);
                    }
                    Err(sa::Error::Other(e)) if e == "closed" => {}
                    Err(e) => {
                        log::warn!("Spotify sign-in: {e}");
                        Toasts::say(tr("Accesso a Spotify non riuscito"), cx);
                    }
                }
                cx.notify();
            });
        })
        .detach();
    }

    /// Brings every playlist and the Liked Songs in (again: playlists are updated, not copied).
    pub fn import(&mut self, cx: &mut Context<Self>) {
        if self.busy != Busy::Idle || self.saved.is_none() {
            return;
        }
        self.busy = Busy::Importing(0, 0);
        cx.notify();
        let progress = Arc::new((AtomicUsize::new(0), AtomicUsize::new(0)));
        let p = progress.clone();
        let http = self.http.clone();
        let task = runtime().spawn(async move {
            let dir = lfly_core::library::dir().map_err(sa::Error::Other)?;
            sa::sync(&http, &dir, move |done, total| {
                p.0.store(done, Ordering::Relaxed);
                p.1.store(total, Ordering::Relaxed);
            })
            .await
        });
        cx.spawn(async move |this, cx| {
            while !task.is_finished() {
                cx.background_executor().timer(Duration::from_millis(300)).await;
                let (done, total) = (progress.0.load(Ordering::Relaxed), progress.1.load(Ordering::Relaxed));
                let _ = this.update(cx, |this, cx| {
                    this.busy = Busy::Importing(done, total);
                    cx.notify();
                });
            }
            let r = task.await;
            let _ = this.update(cx, |this, cx| {
                this.busy = Busy::Idle;
                this.saved = sa::load();
                match r {
                    Ok(Ok(summary)) => {
                        crate::sync::changed();
                        this.library.update(cx, |l, cx| l.reload(cx));
                        Toasts::say(
                            trf("Da Spotify: {0} playlist e {1} brani che ti piacciono", &[&summary.playlists, &summary.liked]),
                            cx,
                        );
                        this.last = Some(summary);
                    }
                    Ok(Err(sa::Error::SignedOut)) => Toasts::say(tr("L'accesso a Spotify è scaduto: collegalo di nuovo"), cx),
                    Ok(Err(e)) => {
                        log::warn!("Spotify import: {e}");
                        Toasts::say(tr("Importazione da Spotify non riuscita"), cx);
                    }
                    Err(e) => log::warn!("Spotify import: {e}"),
                }
                cx.notify();
            });
        })
        .detach();
    }

    /// Forgets the session; imported playlists stay.
    pub fn disconnect(&mut self, cx: &mut Context<Self>) {
        sa::forget();
        self.saved = None;
        self.last = None;
        cx.notify();
    }
}
