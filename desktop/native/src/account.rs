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
}

struct AccountGlobal(Entity<AccountModel>);
impl Global for AccountGlobal {}

impl AccountModel {
    pub fn start(core: Arc<Core>, cx: &mut App) -> Entity<Self> {
        let saved = lfly_core::account::load();
        let info = saved.account.and(saved.info);
        let signed_in = info.is_some();
        let e = cx.new(|_| Self { core, info, playlists: Vec::new() });
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

    /// Forgets the account here and in the Tauri app (they share it).
    pub fn sign_out(&mut self, cx: &mut Context<Self>) {
        lfly_core::account::forget();
        self.core.yt.set_account(None);
        self.info = None;
        self.playlists.clear();
        crate::toast::Toasts::say("Sei uscito dall'account YouTube Music", cx);
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
            title: "Mi piace".into(),
            subtitle: Some("I brani a cui hai messo mi piace su YouTube Music".into()),
            cover_url: None,
            track: None,
        };
        std::iter::once(liked).chain(self.playlists.iter().map(CloneItem::clone_item)).collect()
    }
}
