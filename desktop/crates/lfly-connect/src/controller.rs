//! This computer as a remote for another LFly device, like `ConnectManager.RemoteLink` on the phone.
//!
//! Following a device means: its state is shown here as if it played here, and every transport
//! button, queue tap and edit goes to it.

use std::sync::{Arc, Mutex};
use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use tokio::sync::mpsc;
use tokio_tungstenite::tungstenite;

use crate::discovery::ConnectDevice;
use crate::protocol::{self, Device, Message, State};

/// What the remote shows.
#[derive(Debug, Clone, serde::Serialize)]
#[serde(tag = "kind", rename_all = "camelCase", rename_all_fields = "camelCase")]
pub enum ControllerEvent {
    /// Opening a link to that device (its id), or done trying.
    Connecting { device_id: Option<String> },
    /// The host's owner must accept this computer; both screens show `code`.
    Pairing { device_name: String, code: String },
    /// The host's owner refused.
    Refused { device_name: String },
    /// The device followed now, or none.
    Following { device: Option<ConnectDevice> },
    /// The followed device's state; `received_at` is this computer's clock in ms.
    State { state: State, received_at: i64 },
    /// The followed device handed the music to another one (its id).
    HandedTo { device_id: String },
    /// The followed device gave the music back to this computer (after a `release`).
    HandOver { transfer: crate::protocol::Transfer },
}

type Listener = Arc<dyn Fn(ControllerEvent) + Send + Sync>;

/// Who this device is and the key of its account; changes when the account does.
type Ident = Arc<std::sync::RwLock<(Device, Option<[u8; 32]>)>>;

pub struct Controller {
    ident: Ident,
    listener: Listener,
    inner: Arc<Mutex<Inner>>,
}

#[derive(Default)]
struct Inner {
    /// Bumped on every new link: events of an older one are ignored.
    generation: u64,
    link: Option<mpsc::UnboundedSender<Out>>,
    following: Option<ConnectDevice>,
    last_state: Option<State>,
    received_at: i64,
}

enum Out {
    Send(Message),
    Close,
}

enum LinkEnd {
    /// Never opened, or closed before the host answered.
    NotReady,
    Refused,
    /// Was working and dropped: worth reconnecting.
    Lost,
    /// Closed on purpose.
    Quiet,
}

impl Controller {
    pub fn new(me: Device, account_secret: Option<&str>, listener: impl Fn(ControllerEvent) + Send + Sync + 'static) -> Self {
        Self {
            ident: Arc::new(std::sync::RwLock::new((me, account_secret.map(protocol::account_key)))),
            listener: Arc::new(listener),
            inner: Arc::default(),
        }
    }

    /// The account changed: the next links present the new identity and prove the new account.
    pub fn set_identity(&self, me: Device, account_secret: Option<&str>) {
        *self.ident.write().unwrap() = (me, account_secret.map(protocol::account_key));
    }

    pub fn following(&self) -> Option<ConnectDevice> {
        self.inner.lock().unwrap().following.clone()
    }

    /// Where the followed device's playhead is now, by this computer's clock.
    pub fn position_now(&self) -> i64 {
        let inner = self.inner.lock().unwrap();
        let Some(state) = &inner.last_state else { return 0 };
        state.position_ms + if state.playing { (now_ms() - inner.received_at).max(0) } else { 0 }
    }

    /// Takes control of [device]. The picker shows a spinner until it answers.
    pub fn follow(&self, device: ConnectDevice) {
        if self.following().map(|d| d.id) == Some(device.id.clone()) {
            return;
        }
        self.close_link();
        (self.listener)(ControllerEvent::Connecting { device_id: Some(device.id.clone()) });
        self.open(device, 0);
    }

    /// Stops controlling the followed device; the music keeps playing there.
    pub fn disconnect(&self) {
        self.close_link();
        let mut inner = self.inner.lock().unwrap();
        let was = inner.following.take();
        inner.last_state = None;
        drop(inner);
        if was.is_some() {
            (self.listener)(ControllerEvent::Following { device: None });
        }
    }

    /// A transport command; shown at once, as Spotify does, and confirmed by the device's next state.
    pub fn command(&self, action: &str, value: i64) {
        self.send(Message::command(action, value));
        let position = self.position_now();
        let mut inner = self.inner.lock().unwrap();
        let Some(state) = inner.last_state.as_mut() else { return };
        match action {
            protocol::CMD_SEEK => state.position_ms = value,
            protocol::CMD_TOGGLE => {
                state.position_ms = position;
                state.playing = !state.playing;
            }
            protocol::CMD_PLAY | protocol::CMD_PAUSE => {
                state.position_ms = position;
                state.playing = action == protocol::CMD_PLAY;
            }
            protocol::CMD_VOLUME => state.volume = value.clamp(0, 100) as i32,
            protocol::CMD_SHUFFLE => state.shuffle = value == 1,
            protocol::CMD_REPEAT => {
                state.repeat = match value {
                    protocol::REPEAT_ONE => "one",
                    protocol::REPEAT_ALL => "all",
                    _ => "off",
                }
                .into()
            }
            _ => return,
        }
        let state = state.clone();
        inner.received_at = now_ms();
        let received_at = inner.received_at;
        drop(inner);
        (self.listener)(ControllerEvent::State { state, received_at });
    }

    /// Any other message for the followed device (a queue edit, a transfer).
    pub fn send(&self, message: Message) {
        if let Some(link) = &self.inner.lock().unwrap().link {
            let _ = link.send(Out::Send(message));
        }
    }

    fn close_link(&self) {
        let mut inner = self.inner.lock().unwrap();
        inner.generation += 1;
        if let Some(link) = inner.link.take() {
            let _ = link.send(Out::Close);
        }
    }

    fn open(&self, device: ConnectDevice, attempt: u32) {
        let (tx, rx) = mpsc::unbounded_channel();
        let generation = {
            let mut inner = self.inner.lock().unwrap();
            inner.generation += 1;
            inner.link = Some(tx);
            inner.generation
        };
        let ctx = LinkContext {
            ident: self.ident.clone(),
            listener: self.listener.clone(),
            inner: self.inner.clone(),
            generation,
            device: device.clone(),
        };
        let controller = self.weak();
        tokio::spawn(async move {
            let end = run_link(&ctx, rx).await;
            let current = ctx.inner.lock().unwrap().generation == generation;
            if !current {
                return;
            }
            match end {
                LinkEnd::Lost if attempt < 4 => {
                    // A Wi-Fi blip: try again a few times before giving the player back.
                    tokio::time::sleep(Duration::from_millis(1_500 * u64::from(attempt + 1))).await;
                    if ctx.inner.lock().unwrap().generation != generation {
                        return;
                    }
                    log::info!("Reconnecting to '{}' ({})", device.name, attempt + 1);
                    if let Some(controller) = controller.upgrade() {
                        controller.open(device, attempt + 1);
                    }
                }
                LinkEnd::Quiet => {}
                LinkEnd::Refused => {
                    (ctx.listener)(ControllerEvent::Refused { device_name: device.name.clone() });
                    give_up(&ctx);
                }
                LinkEnd::NotReady if attempt > 0 => {
                    // A reconnect that failed to open: keep trying like a lost link.
                    if attempt < 4 {
                        tokio::time::sleep(Duration::from_millis(1_500 * u64::from(attempt + 1))).await;
                        if let Some(controller) = controller.upgrade() {
                            if controller.inner.lock().unwrap().generation == generation {
                                controller.open(device, attempt + 1);
                            }
                        }
                    } else {
                        give_up(&ctx);
                    }
                }
                LinkEnd::NotReady | LinkEnd::Lost => give_up(&ctx),
            }
        });
    }

    fn weak(&self) -> WeakController {
        WeakController {
            ident: self.ident.clone(),
            listener: self.listener.clone(),
            inner: Arc::downgrade(&self.inner),
        }
    }
}

/// Lets a link task reopen itself without keeping the controller alive.
struct WeakController {
    ident: Ident,
    listener: Listener,
    inner: std::sync::Weak<Mutex<Inner>>,
}

impl WeakController {
    fn upgrade(&self) -> Option<Controller> {
        Some(Controller {
            ident: self.ident.clone(),
            listener: self.listener.clone(),
            inner: self.inner.upgrade()?,
        })
    }
}

struct LinkContext {
    ident: Ident,
    listener: Listener,
    inner: Arc<Mutex<Inner>>,
    generation: u64,
    device: ConnectDevice,
}

impl LinkContext {
    fn current(&self) -> bool {
        self.inner.lock().unwrap().generation == self.generation
    }
}

fn give_up(ctx: &LinkContext) {
    let mut inner = ctx.inner.lock().unwrap();
    inner.link = None;
    inner.last_state = None;
    let was = inner.following.take();
    drop(inner);
    (ctx.listener)(ControllerEvent::Connecting { device_id: None });
    if was.is_some() {
        log::info!("Lost '{}'", ctx.device.name);
        (ctx.listener)(ControllerEvent::Following { device: None });
    }
}

async fn run_link(ctx: &LinkContext, mut out: mpsc::UnboundedReceiver<Out>) -> LinkEnd {
    let device = &ctx.device;
    let host = if device.host.contains(':') { format!("[{}]", device.host) } else { device.host.clone() };
    let url = format!("ws://{host}:{}", device.port);
    let connecting = tokio::time::timeout(Duration::from_secs(6), tokio_tungstenite::connect_async(&url)).await;
    let mut socket = match connecting {
        Ok(Ok((socket, _))) => socket,
        Ok(Err(e)) => {
            log::warn!("Link to '{}': {e}", device.name);
            return LinkEnd::NotReady;
        }
        Err(_) => {
            log::warn!("Link to '{}': no answer", device.name);
            return LinkEnd::NotReady;
        }
    };
    let me = ctx.ident.read().unwrap().0.clone();
    let hello = Message::Hello { device: me, protocol: protocol::VERSION };
    if socket.send(tungstenite::Message::text(hello.encode())).await.is_err() {
        return LinkEnd::NotReady;
    }
    let mut ready = false;
    loop {
        tokio::select! {
            outgoing = out.recv() => match outgoing {
                Some(Out::Send(message)) => {
                    if socket.send(tungstenite::Message::text(message.encode())).await.is_err() {
                        break;
                    }
                }
                Some(Out::Close) | None => {
                    let _ = socket.close(None).await;
                    return LinkEnd::Quiet;
                }
            },
            incoming = socket.next() => {
                let text = match incoming {
                    Some(Ok(tungstenite::Message::Text(text))) => text,
                    Some(Ok(tungstenite::Message::Close(_))) | None | Some(Err(_)) => break,
                    Some(Ok(_)) => continue,
                };
                if !ctx.current() {
                    let _ = socket.close(None).await;
                    return LinkEnd::Quiet;
                }
                match Message::decode(text.as_str()) {
                    Some(Message::Challenge { nonce, .. }) => {
                        let key = ctx.ident.read().unwrap().1;
                        let hmac = key.map(|key| protocol::proof(&key, &nonce));
                        let proof = Message::Proof { hmac };
                        if socket.send(tungstenite::Message::text(proof.encode())).await.is_err() {
                            break;
                        }
                    }
                    Some(Message::PairPending { code }) => {
                        (ctx.listener)(ControllerEvent::Pairing { device_name: device.name.clone(), code });
                    }
                    Some(Message::Welcome { accepted }) => {
                        if !accepted {
                            let _ = socket.close(None).await;
                            return LinkEnd::Refused;
                        }
                        ready = true;
                        log::info!("Now controlling '{}'", device.name);
                        ctx.inner.lock().unwrap().following = Some(device.clone());
                        (ctx.listener)(ControllerEvent::Connecting { device_id: None });
                        (ctx.listener)(ControllerEvent::Following { device: Some(device.clone()) });
                    }
                    Some(Message::State(state)) => on_state(ctx, state),
                    Some(Message::Transfer(transfer)) => (ctx.listener)(ControllerEvent::HandOver { transfer }),
                    _ => {}
                }
            }
        }
    }
    if ready { LinkEnd::Lost } else { LinkEnd::NotReady }
}

fn on_state(ctx: &LinkContext, state: State) {
    if !state.active {
        if let Some(to) = state.handed_to.clone() {
            // The music moved on to another device: follow it there, or let the app decide.
            (ctx.listener)(ControllerEvent::HandedTo { device_id: to });
            return;
        }
    }
    let received_at = now_ms();
    {
        let mut inner = ctx.inner.lock().unwrap();
        inner.last_state = Some(state.clone());
        inner.received_at = received_at;
    }
    (ctx.listener)(ControllerEvent::State { state, received_at });
}

pub fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}
