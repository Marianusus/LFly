//! The YouTube Music account (Tauri: account.svelte.ts): who is signed in and their playlists on
//! YouTube Music. The session is the one the Tauri app saved (lfly-core's account); signing in
//! needs a web view, so it still happens there. Core::new hands the session to the requests.

use std::sync::Arc;

use gpui_kit::*;
use lfly_core::pages::Item;
use lfly_yt::AccountInfo;

use crate::core::{runtime, Core};
use crate::home::CloneItem;

pub struct AccountModel {
    core: Arc<Core>,
    pub info: Option<AccountInfo>,
    pub playlists: Vec<Item>,
    /// Google's sign-in window (lfly-web) is open.
    pub signing_in: bool,
}

struct AccountGlobal(Entity<AccountModel>);
impl Global for AccountGlobal {}

impl AccountModel {
    pub fn start(core: Arc<Core>, cx: &mut App) -> Entity<Self> {
        let saved = lfly_core::account::load();
        let info = saved.account.and(saved.info);
        let signed_in = info.is_some();
        let e = cx.new(|_| Self { core, info, playlists: Vec::new(), signing_in: false });
        if signed_in {
            e.update(cx, |m, cx| m.load_playlists(cx));
        }
        cx.set_global(AccountGlobal(e.clone()));
        e
    }

    pub fn entity(cx: &App) -> Entity<Self> {
        cx.global::<AccountGlobal>().0.clone()
    }

    fn load_playlists(&mut self, cx: &mut Context<Self>) {
        let core = self.core.clone();
        cx.spawn(async move |this, cx| {
            let r = runtime().spawn(async move { lfly_core::pages::library(&core.yt).await }).await;
            match r {
                Ok(Ok(list)) => {
                    let _ = this.update(cx, |this, cx| {
                        this.playlists = list;
                        cx.notify();
                    });
                }
                Ok(Err(e)) => log::warn!("YouTube Music playlists: {e}"),
                Err(e) => log::warn!("YouTube Music playlists: {e}"),
            }
        })
        .detach();
    }

    /// Google's sign-in in the lfly-web helper's window; once YouTube Music accepts the session it
    /// is saved (the Tauri app reads it too) and used at once.
    pub fn sign_in(&mut self, cx: &mut Context<Self>) {
        if self.signing_in {
            return;
        }
        self.signing_in = true;
        cx.notify();
        let core = self.core.clone();
        cx.spawn(async move |this, cx| {
            let read = cx.background_executor().spawn(async move { lfly_core::account::sign_in_with_helper() }).await;
            let checked = match read {
                Ok(Some(account)) => {
                    let yt = core.clone();
                    let a = account.clone();
                    match runtime().spawn(async move { yt.yt.account_info(&a).await }).await {
                        Ok(Ok(info)) => Ok(Some((account, info))),
                        Ok(Err(e)) => Err(e.to_string()),
                        Err(e) => Err(e.to_string()),
                    }
                }
                Ok(None) => Ok(None),
                Err(e) => Err(e),
            };
            let _ = this.update(cx, |this, cx| {
                this.signing_in = false;
                match checked {
                    Ok(Some((account, info))) => {
                        let saved = lfly_core::account::Saved { account: Some(account.clone()), info: Some(info.clone()) };
                        if let Err(e) = lfly_core::account::save(&saved) {
                            log::warn!("account.json: {e}");
                        }
                        log::info!("Signed in to YouTube Music as {}", info.name);
                        let secret = account.connect_secret();
                        this.core.yt.set_account(Some(account));
                        this.info = Some(info);
                        this.load_playlists(cx);
                        if let Some(c) = crate::connect::ConnectModel::try_entity(cx) {
                            c.update(cx, |c, cx| c.set_account(secret, cx));
                        }
                        crate::toast::Toasts::say(crate::i18n::tr("Accesso a YouTube Music fatto"), cx);
                    }
                    Ok(None) => {}
                    Err(e) => {
                        log::warn!("YouTube Music sign-in: {e}");
                        crate::toast::Toasts::say(crate::i18n::tr("YouTube Music non accetta l'accesso"), cx);
                    }
                }
                cx.notify();
            });
        })
        .detach();
    }

    /// Forgets the account here and in the Tauri app (they share it).
    pub fn sign_out(&mut self, cx: &mut Context<Self>) {
        lfly_core::account::forget();
        self.core.yt.set_account(None);
        self.info = None;
        self.playlists.clear();
        // LFly Connect stops proving the account: devices of it pair with a code again.
        if let Some(c) = crate::connect::ConnectModel::try_entity(cx) {
            c.update(cx, |c, cx| c.set_account(None, cx));
        }
        crate::toast::Toasts::say(crate::i18n::tr("Sei uscito dall'account YouTube Music"), cx);
        cx.notify();
    }

    /// "Mi piace" (YouTube Music's liked songs, the LM playlist) and the account's playlists.
    pub fn shelf(&self) -> Vec<Item> {
        if self.info.is_none() {
            return Vec::new();
        }
        let liked = Item {
            kind: "playlist",
            id: "LM".into(),
            title: crate::i18n::tr("Mi piace").into(),
            subtitle: Some(crate::i18n::tr("I brani a cui hai messo mi piace su YouTube Music").into()),
            cover_url: None,
            track: None,
        };
        std::iter::once(liked).chain(self.playlists.iter().map(CloneItem::clone_item)).collect()
    }
}
