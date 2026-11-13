//! The system's media controls (media keys, the MPRIS widgets of KDE and GNOME, Windows' overlay)
//! and "Listening to LFly" on Discord, from the song the player has (Tauri: media.rs and
//! App.svelte's `media_update` effect). Test runs (LFLY_MUTE, LFLY_AUTOPLAY) show nothing.

use std::sync::Arc;

use gpui_kit::*;
use lfly_core::discord::Discord;
use lfly_core::media::{self, Media, MediaCommand, NowShowing};

use crate::player_model::{PlayerEvent, PlayerModel};

pub struct SystemMedia {
    media: Option<Arc<Media>>,
    discord: Option<Discord>,
    /// What was last told, without the position (it moves on its own), and where the song was
    /// then: a seek is told again, the widgets carry the position forward by themselves.
    told: Option<NowShowing>,
    told_at: Option<(f64, std::time::Instant)>,
    discord_on: bool,
    _sub: Subscription,
}

struct MediaGlobal(Entity<SystemMedia>);
impl Global for MediaGlobal {}

fn test_run() -> bool {
    std::env::var_os("LFLY_MUTE").is_some() || std::env::var_os("LFLY_AUTOPLAY").is_some()
}

/// The "discord" store key (Tauri: discord_set_enabled), on unless turned off.
fn discord_saved() -> bool {
    lfly_core::store::get("discord").ok().flatten().and_then(|v| v.get("enabled").and_then(|e| e.as_bool())).unwrap_or(true)
}

impl SystemMedia {
    pub fn start(model: Entity<PlayerModel>, window: &mut Window, cx: &mut App) {
        let quiet = test_run();
        let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<MediaCommand>();
        // LFLY_MEDIA_TEST: the controls in a test run too (on a private bus), Discord still not.
        // Windows ties the controls to a window (its HWND); elsewhere they stand alone.
        #[cfg(target_os = "windows")]
        let hwnd = match raw_window_handle::HasWindowHandle::window_handle(window).map(|h| h.as_raw()) {
            Ok(raw_window_handle::RawWindowHandle::Win32(h)) => Some(h.hwnd.get() as *mut std::ffi::c_void),
            _ => None,
        };
        #[cfg(not(target_os = "windows"))]
        let hwnd = None;
        let media = (!quiet || std::env::var_os("LFLY_MEDIA_TEST").is_some()).then(|| Arc::new(Media::start(hwnd, move |c| {
            let _ = tx.send(c);
        })));
        let discord_on = discord_saved();
        let discord = (!quiet).then(|| Discord::start(discord_on));

        // The presses arrive on the controls' thread; the player is told from here.
        let handle = window.window_handle();
        let m = model.clone();
        cx.spawn(async move |cx| {
            while let Some(c) = rx.recv().await {
                let _ = cx.update(|cx| match c {
                    MediaCommand::Run(action, value) => m.read(cx).command(action, value),
                    MediaCommand::SeekBy(by) => {
                        let p = m.read(cx);
                        p.command(lfly_connect::protocol::CMD_SEEK, (p.live_position() as i64 + by).max(0));
                    }
                    MediaCommand::Raise => {
                        let _ = handle.update(cx, |_, window, _| window.activate_window());
                    }
                });
            }
        })
        .detach();

        let e = cx.new(|cx| {
            let sub = cx.subscribe(&model, |this: &mut Self, model, ev: &PlayerEvent, cx| {
                if matches!(ev, PlayerEvent::StateChanged) {
                    this.tell(&model, cx);
                }
            });
            Self { media, discord, told: None, told_at: None, discord_on, _sub: sub }
        });
        cx.set_global(MediaGlobal(e));
    }

    pub fn entity(cx: &App) -> Entity<Self> {
        cx.global::<MediaGlobal>().0.clone()
    }

    fn tell(&mut self, model: &Entity<PlayerModel>, cx: &mut Context<Self>) {
        let p = model.read(cx);
        let t = p.current();
        let duration = p.duration_ms();
        let mut now = NowShowing {
            title: t.map(|t| t.title.clone()),
            artist: t.map(|t| t.artist.clone()),
            album: t.and_then(|t| t.album.clone()),
            cover_url: t.and_then(|t| t.cover_url.clone()),
            duration_ms: (duration > 0).then_some(duration),
            position_ms: None,
            playing: p.playing(),
            remote: p.remote.is_some(),
        };
        // Volume and queue changes come through here too: the widgets hear only what they show.
        let pos = p.live_position().max(0.);
        let expected = self.told_at.map(|(at, when)| if now.playing { at + when.elapsed().as_secs_f64() * 1000. } else { at });
        if self.told.as_ref() == Some(&now) && expected.is_some_and(|e| (e - pos).abs() < 1500.) {
            return;
        }
        self.told = Some(now.clone());
        self.told_at = Some((pos, std::time::Instant::now()));
        now.position_ms = Some(pos as u64);
        if let Some(d) = &self.discord {
            d.show(media::listening(&now, media::unix_ms()));
        }
        if let Some(m) = self.media.clone() {
            // D-Bus round trips: off the UI thread.
            cx.background_executor().spawn(async move { m.update(&now) }).detach();
        }
    }

    pub fn discord_on(&self) -> bool {
        self.discord_on
    }

    pub fn set_discord(&mut self, on: bool, cx: &mut Context<Self>) {
        self.discord_on = on;
        if let Some(d) = &self.discord {
            d.set_enabled(on);
        }
        if let Err(e) = lfly_core::store::set("discord", &serde_json::json!({ "enabled": on })) {
            log::warn!("discord: {e}");
        }
        cx.notify();
    }
}
