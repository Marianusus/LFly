//! LFly Connect (Tauri: lib.rs's Connect commands and connect.svelte.ts): the devices on the
//! network, the one this computer controls and what plays there, and this computer as a speaker
//! the phones and the watch can pick. lfly-connect does the talking; this keeps what the window
//! shows and moves the music between here and there.
//!
//! Test runs (LFLY_MUTE, LFLY_AUTOPLAY) neither browse nor announce on the network, and serve
//! only when LFLY_CONNECT_PORT gives them a loopback port. LFLY_CONNECT_FOLLOW=host:port follows
//! such a host at start; LFLY_CONNECT_ID/NAME/SECRET stand in for this computer's identity.

use std::sync::Arc;

use gpui_kit::*;
use lfly_connect::protocol::{self, Device};
use lfly_connect::{ConnectDevice, Controller, ControllerEvent, Discovery, Host, HostEvent, HostPlayer};

use crate::core::{runtime, Core, Remote};
use crate::player_model::PlayerModel;
use crate::toast::Toasts;

enum Ev {
    Controller(ControllerEvent),
    Host(HostEvent),
    Devices(Vec<ConnectDevice>),
}

/// A device asking to control this computer, for the dialog.
#[derive(Clone)]
pub struct Request {
    pub request: u64,
    pub device_name: String,
    pub code: String,
}

pub struct ConnectModel {
    core: Arc<Core>,
    model: Entity<PlayerModel>,
    pub me: Device,
    controller: Arc<Controller>,
    discovery: Option<Arc<Discovery>>,
    pub devices: Vec<ConnectDevice>,
    pub following: Option<ConnectDevice>,
    pub connecting: Option<String>,
    /// This computer pairing with a device of another account: the code to compare there.
    pub pairing: Option<(String, String)>,
    pub request: Option<Request>,
    /// How many devices control this computer now.
    pub controllers: usize,
    /// Whether the network is looked at (not in test runs).
    pub online: bool,
    /// Whether this computer is announced (not when another LFly is it already).
    announced: bool,
}

struct ConnectGlobal(Entity<ConnectModel>);
impl Global for ConnectGlobal {}

fn test_run() -> bool {
    std::env::var_os("LFLY_MUTE").is_some() || std::env::var_os("LFLY_AUTOPLAY").is_some()
}

pub fn kind_label(kind: &str) -> &'static str {
    match kind {
        "phone" => crate::i18n::t("Telefono"),
        "tablet" => crate::i18n::t("Tablet"),
        "desktop" => crate::i18n::t("Computer"),
        "tv" => crate::i18n::t("TV"),
        "watch" => crate::i18n::t("Orologio"),
        _ => crate::i18n::t("Dispositivo"),
    }
}

/// The icon of a device kind (OutputMenu.svelte `icon`).
pub fn kind_icon(kind: &str) -> &'static str {
    match kind {
        "phone" => "phone",
        "tablet" => "tablet",
        "desktop" => "desktop",
        "tv" => "tv",
        "watch" => "watch",
        _ => "devices",
    }
}

impl ConnectModel {
    pub fn start(core: Arc<Core>, model: Entity<PlayerModel>, cx: &mut App) -> Entity<Self> {
        let quiet = test_run();
        let mut me = lfly_core::identity::me();
        if let Ok(id) = std::env::var("LFLY_CONNECT_ID") {
            me.id = id;
        }
        if let Ok(name) = std::env::var("LFLY_CONNECT_NAME") {
            me.name = name;
        }
        // Signed in to YouTube Music: the phone's own secret, so the devices of one account
        // trust each other without a code.
        let secret = std::env::var("LFLY_CONNECT_SECRET")
            .ok()
            .or_else(|| (!quiet).then(|| lfly_core::account::load().account.and_then(|a| a.connect_secret())).flatten());
        me.owner = secret.as_deref().map(protocol::owner_tag);

        let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<Ev>();
        let ctl_tx = tx.clone();
        let controller = Arc::new(Controller::new(me.clone(), secret.as_deref(), move |e| {
            let _ = ctl_tx.send(Ev::Controller(e));
        }));

        let discovery = if quiet {
            None
        } else {
            match Discovery::new() {
                Ok(d) => {
                    let dev_tx = tx.clone();
                    if let Err(e) = d.browse(&me, move |list| {
                        let _ = dev_tx.send(Ev::Devices(list));
                    }) {
                        log::warn!("Cannot look for devices: {e}");
                    }
                    Some(Arc::new(d))
                }
                Err(e) => {
                    log::warn!("No network discovery: {e}");
                    None
                }
            }
        };

        // Library sync offers as this computer, to the devices it sees (sync.rs).
        crate::sync::set_identity(&me, secret.as_deref());
        crate::sync::set_discovery(discovery.clone());
        // This computer as a speaker the phones and the watch can pick.
        let mut announced = false;
        if !quiet || std::env::var_os("LFLY_CONNECT_PORT").is_some() {
            let host_tx = tx.clone();
            let player: Arc<dyn HostPlayer> = core.player.clone();
            let started = runtime().block_on(Host::start(me.clone(), secret.as_deref(), lfly_core::identity::trusted(), player, move |e| {
                if let HostEvent::Trusted { device_id, .. } = &e {
                    lfly_core::identity::trust(device_id);
                }
                let _ = host_tx.send(Ev::Host(e));
            }));
            match started {
                Ok(host) => {
                    // Another LFly (the Tauri app) holds the usual port: it is this computer on
                    // the network already, with the same id; a second announcement would only
                    // show the phones a twin.
                    let twin = host.port() != protocol::DEFAULT_PORT && std::env::var_os("LFLY_CONNECT_PORT").is_none();
                    if twin {
                        log::warn!("Connect: another LFly holds port {}; this window is not announced", protocol::DEFAULT_PORT);
                    }
                    if let (Some(d), false) = (&discovery, twin) {
                        match d.announce(&me, host.port()) {
                            Ok(()) => announced = true,
                            Err(e) => log::warn!("Cannot announce this computer: {e}"),
                        }
                    }
                    core.player.attach_host(host);
                }
                Err(e) => log::warn!("Connect host did not start: {e}"),
            }
        }

        let online = discovery.is_some();
        let model_sub = model.clone();
        let e = cx.new(|_| Self {
            core,
            model,
            me,
            controller,
            discovery,
            devices: Vec::new(),
            following: None,
            connecting: None,
            pairing: None,
            request: None,
            controllers: 0,
            online,
            announced,
        });
        let weak = e.downgrade();
        cx.spawn(async move |cx| {
            while let Some(ev) = rx.recv().await {
                if weak.update(cx, |m, cx| m.apply(ev, cx)).is_err() {
                    break;
                }
            }
        })
        .detach();
        cx.set_global(ConnectGlobal(e.clone()));
        // The music came here from another device: what plays is here now, not over there.
        e.update(cx, |_, cx| {
            cx.subscribe(&model_sub, |this: &mut Self, _, ev: &crate::player_model::PlayerEvent, cx| {
                if matches!(ev, crate::player_model::PlayerEvent::Arrived) && this.following.is_some() {
                    let c = this.controller.clone();
                    runtime().spawn(async move { c.disconnect() });
                    this.set_following(None, cx);
                    cx.notify();
                }
            })
            .detach();
        });

        if let Some((host, port)) = std::env::var("LFLY_CONNECT_FOLLOW").ok().and_then(|v| v.rsplit_once(':').map(|(h, p)| (h.to_string(), p.parse::<u16>().ok()))) {
            if let Some(port) = port {
                let device = ConnectDevice { id: "test-host".into(), name: "Host di prova".into(), kind: "desktop".into(), same_account: true, host, port };
                // LFLY_CONNECT_FOLLOW_AFTER=<seconds>: later, so that something plays here first.
                match std::env::var("LFLY_CONNECT_FOLLOW_AFTER").ok().and_then(|v| v.parse::<f64>().ok()) {
                    Some(secs) => {
                        let weak = e.downgrade();
                        cx.spawn(async move |cx| {
                            cx.background_executor().timer(std::time::Duration::from_secs_f64(secs)).await;
                            let _ = weak.update(cx, |m, cx| m.follow(device, cx));
                        })
                        .detach();
                    }
                    None => e.update(cx, |m, cx| m.follow(device, cx)),
                }
            }
        }
        // LFLY_CONNECT_PLAY_HERE_AFTER=<seconds> (tests): "Ascolta qui" by itself, later.
        if let Some(secs) = std::env::var("LFLY_CONNECT_PLAY_HERE_AFTER").ok().and_then(|v| v.parse::<f64>().ok()) {
            let weak = e.downgrade();
            cx.spawn(async move |cx| {
                cx.background_executor().timer(std::time::Duration::from_secs_f64(secs)).await;
                let _ = weak.update(cx, |m, cx| m.play_here(cx));
            })
            .detach();
        }
        e
    }

    pub fn entity(cx: &App) -> Entity<Self> {
        cx.global::<ConnectGlobal>().0.clone()
    }

    pub fn try_entity(cx: &App) -> Option<Entity<Self>> {
        cx.try_global::<ConnectGlobal>().map(|g| g.0.clone())
    }

    fn apply(&mut self, ev: Ev, cx: &mut Context<Self>) {
        match ev {
            Ev::Devices(list) => self.devices = list,
            Ev::Host(e) => match e {
                HostEvent::PairRequest { request, device_name, code } => {
                    log::info!("Connect: '{device_name}' asks to control this computer");
                    self.request = Some(Request { request, device_name, code });
                    // LFLY_CONNECT_ACCEPT=1 (tests, on a copy of the config): "Accetta" by itself.
                    if std::env::var_os("LFLY_CONNECT_ACCEPT").is_some() {
                        self.answer(true, cx);
                    }
                }
                HostEvent::PairGone { request } => {
                    if self.request.as_ref().is_some_and(|r| r.request == request) {
                        self.request = None;
                    }
                }
                HostEvent::Trusted { device_name, .. } => Toasts::say(crate::i18n::trf("{0} ora può controllare questo computer", &[&device_name]), cx),
                HostEvent::Controllers { count } => self.controllers = count,
            },
            Ev::Controller(e) => self.on_controller(e, cx),
        }
        cx.notify();
    }

    fn on_controller(&mut self, e: ControllerEvent, cx: &mut Context<Self>) {
        match e {
            ControllerEvent::Connecting { device_id } => {
                if device_id.is_none() {
                    self.pairing = None;
                }
                self.connecting = device_id;
            }
            ControllerEvent::Pairing { device_name, code } => self.pairing = Some((device_name, code)),
            ControllerEvent::Refused { device_name } => {
                self.pairing = None;
                self.connecting = None;
                Toasts::say(crate::i18n::trf("{0} non ha accettato questo computer.", &[&device_name]), cx);
            }
            ControllerEvent::Following { device } => {
                self.pairing = None;
                self.connecting = None;
                if device.is_none() {
                    if let Some(old) = &self.following {
                        Toasts::say(crate::i18n::trf("Collegamento con {0} perso.", &[&old.name]), cx);
                    }
                }
                self.set_following(device, cx);
            }
            ControllerEvent::State { state, .. } => {
                let position = self.controller.position_now();
                self.model.update(cx, |m, cx| m.apply_remote(state, position, cx));
            }
            ControllerEvent::HandedTo { device_id } => match self.devices.iter().find(|d| d.id == device_id).cloned() {
                Some(next) => self.follow(next, cx),
                None => {
                    Toasts::say(crate::i18n::tr("La musica è passata a un altro dispositivo."), cx);
                    self.disconnect(cx);
                }
            },
            // The device gave the music back: it plays on here, from where it was.
            ControllerEvent::HandOver { transfer } => {
                self.controller.disconnect();
                self.set_following(None, cx);
                self.core.player.play_queue(transfer.queue, transfer.index, transfer.position_ms, transfer.playing);
            }
        }
    }

    fn set_following(&mut self, device: Option<ConnectDevice>, cx: &mut Context<Self>) {
        let entering = device.is_some() && self.following.is_none();
        if let Some(d) = &device {
            // Picking another device moves the music playing here to it, like Spotify.
            if entering && self.core.player.is_playing() {
                if let Some(transfer) = HostPlayer::release(self.core.player.as_ref()) {
                    self.controller.send(lfly_connect::Message::Transfer(transfer));
                    if let Some(host) = self.core.player.host() {
                        host.handed_to(&d.id);
                    }
                }
            }
            self.core.set_remote(Some(Remote { controller: self.controller.clone(), me_id: self.me.id.clone() }));
        } else {
            self.core.set_remote(None);
        }
        let name = device.as_ref().map(|d| d.name.clone());
        self.following = device;
        self.model.update(cx, |m, cx| m.set_remote(name, cx));
    }

    /// Takes control of [device]: what plays here moves there.
    pub fn follow(&mut self, device: ConnectDevice, cx: &mut Context<Self>) {
        if self.following.as_ref().is_some_and(|d| d.id == device.id) {
            return;
        }
        let c = self.controller.clone();
        runtime().spawn(async move { c.follow(device) });
        cx.notify();
    }

    /// Stops controlling the device; the music keeps playing there.
    pub fn disconnect(&mut self, cx: &mut Context<Self>) {
        let c = self.controller.clone();
        runtime().spawn(async move { c.disconnect() });
        self.set_following(None, cx);
        cx.notify();
    }

    /// The music comes back here, from where it was (the device answers with a HandOver).
    pub fn play_here(&mut self, cx: &mut Context<Self>) {
        if self.following.is_none() {
            return;
        }
        self.controller.command(protocol::CMD_RELEASE, 0);
        cx.notify();
    }

    /// The owner's answer to a device asking to control this computer.
    pub fn answer(&mut self, accept: bool, cx: &mut Context<Self>) {
        if let Some(r) = self.request.take() {
            if let Some(host) = self.core.player.host() {
                host.answer(r.request, accept);
            }
        }
        cx.notify();
    }

    /// Renames this computer and announces it again under the new name.
    pub fn rename(&mut self, name: &str, cx: &mut Context<Self>) {
        let name = lfly_core::identity::set_name(name);
        self.me.name = name;
        let secret = lfly_core::account::load().account.and_then(|a| a.connect_secret());
        crate::sync::set_identity(&self.me, secret.as_deref());
        self.controller.set_identity(self.me.clone(), secret.as_deref());
        if let Some(host) = self.core.player.host() {
            host.set_identity(self.me.clone(), secret.as_deref());
            if let (Some(d), true) = (&self.discovery, self.announced) {
                if let Err(e) = d.announce(&self.me, host.port()) {
                    log::warn!("Cannot announce this computer again: {e}");
                }
            }
        }
        cx.notify();
    }

    /// The YouTube Music account changed (signed out: None): links prove the new one, and the
    /// network hears the new owner (Tauri: apply_account).
    pub fn set_account(&mut self, secret: Option<String>, cx: &mut Context<Self>) {
        self.me.owner = secret.as_deref().map(protocol::owner_tag);
        crate::sync::set_identity(&self.me, secret.as_deref());
        self.controller.set_identity(self.me.clone(), secret.as_deref());
        if let Some(host) = self.core.player.host() {
            host.set_identity(self.me.clone(), secret.as_deref());
            if let (Some(d), true) = (&self.discovery, self.announced) {
                if let Err(e) = d.announce(&self.me, host.port()) {
                    log::warn!("Cannot announce this computer again: {e}");
                }
            }
        }
        if let Some(d) = &self.discovery {
            d.set_owner(self.me.owner.clone());
        }
        cx.notify();
    }

    /// Forgets the devices of other accounts paired with a code.
    pub fn forget_paired(&mut self, cx: &mut Context<Self>) {
        lfly_core::identity::forget_trusted();
        if let Some(host) = self.core.player.host() {
            host.forget_trusted();
        }
        Toasts::say(crate::i18n::tr("Dispositivi abbinati dimenticati"), cx);
        cx.notify();
    }
}
