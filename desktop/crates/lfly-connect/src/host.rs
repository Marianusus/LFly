//! This computer as a player the other LFly devices can see and control, like the phone's
//! `ConnectManager.HostServer`: a WebSocket server that admits devices of the same account at
//! once, asks the owner about any other, then runs their commands on the local player.

use std::collections::{HashMap, HashSet};
use std::net::SocketAddr;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use futures_util::{SinkExt, StreamExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{mpsc, oneshot};
use tokio_tungstenite::tungstenite;

use crate::protocol::{self, Device, Message, State, TrackRef, Transfer};

/// What the host needs from the local player. Called from the network tasks.
pub trait HostPlayer: Send + Sync + 'static {
    /// What plays now; the host fills in `device_id` and `at`.
    fn state(&self) -> State;
    /// A transport command (`protocol::CMD_*`), except `release`.
    fn command(&self, action: &str, value: i64);
    /// A queue change (`protocol::EDIT_*`).
    fn edit(&self, op: &str, tracks: Vec<TrackRef>, index: i32, to: i32);
    /// The music moves here.
    fn transfer(&self, transfer: Transfer);
    /// The music leaves: everything the other device needs to play on (the stream included),
    /// then the local player pauses. None when nothing plays.
    fn release(&self) -> Option<Transfer>;
}

/// What the window must show.
#[derive(Debug, Clone, serde::Serialize)]
#[serde(tag = "kind", rename_all = "camelCase", rename_all_fields = "camelCase")]
pub enum HostEvent {
    /// A device of another account asks to control this computer; answer with [Host::answer].
    PairRequest { request: u64, device_name: String, code: String },
    /// The request was withdrawn (the device closed the link).
    PairGone { request: u64 },
    /// A device was admitted, and this id should be remembered as trusted.
    Trusted { device_id: String, device_name: String },
    /// How many devices are connected now.
    Controllers { count: usize },
}

type Listener = Arc<dyn Fn(HostEvent) + Send + Sync>;

pub struct Host {
    inner: Arc<Inner>,
    port: u16,
}

struct Inner {
    /// This device and its account key; changes when the account does.
    ident: std::sync::RwLock<(Device, Option<[u8; 32]>)>,
    player: Arc<dyn HostPlayer>,
    listener: Listener,
    trusted: Mutex<HashSet<String>>,
    sessions: Mutex<HashMap<u64, mpsc::UnboundedSender<String>>>,
    requests: Mutex<HashMap<u64, oneshot::Sender<bool>>>,
    next: AtomicU64,
}

impl Host {
    /// Starts serving on a free port. [trusted] are the devices accepted before.
    pub async fn start(
        me: Device,
        account_secret: Option<&str>,
        trusted: impl IntoIterator<Item = String>,
        player: Arc<dyn HostPlayer>,
        listener: impl Fn(HostEvent) + Send + Sync + 'static,
    ) -> std::io::Result<Self> {
        let socket = TcpListener::bind(SocketAddr::from(([0, 0, 0, 0], 0))).await?;
        let port = socket.local_addr()?.port();
        let inner = Arc::new(Inner {
            ident: std::sync::RwLock::new((me, account_secret.map(protocol::account_key))),
            player,
            listener: Arc::new(listener),
            trusted: Mutex::new(trusted.into_iter().collect()),
            sessions: Mutex::default(),
            requests: Mutex::default(),
            next: AtomicU64::new(1),
        });
        let accepting = inner.clone();
        tokio::spawn(async move {
            while let Ok((stream, addr)) = socket.accept().await {
                let inner = accepting.clone();
                tokio::spawn(async move {
                    if let Err(e) = serve(inner, stream).await {
                        log::debug!("Connect link from {addr}: {e}");
                    }
                });
            }
        });
        log::info!("Connect host on port {port}");
        Ok(Self { inner, port })
    }

    /// The account changed: new links are checked against the new account.
    pub fn set_identity(&self, me: Device, account_secret: Option<&str>) {
        *self.inner.ident.write().unwrap() = (me, account_secret.map(protocol::account_key));
    }

    pub fn port(&self) -> u16 {
        self.port
    }

    /// The owner's answer to a [HostEvent::PairRequest].
    pub fn answer(&self, request: u64, accept: bool) {
        if let Some(tx) = self.inner.requests.lock().unwrap().remove(&request) {
            let _ = tx.send(accept);
        }
    }

    /// Tells every connected device what plays now. Call it on every change, and every few
    /// seconds while playing.
    pub fn broadcast(&self) {
        let text = Message::State(self.inner.state()).encode();
        self.inner.sessions.lock().unwrap().retain(|_, tx| tx.send(text.clone()).is_ok());
    }

    /// The music moved to [device_id]: controllers learn it and follow it there.
    pub fn handed_to(&self, device_id: &str) {
        let mut state = self.inner.state();
        state.active = false;
        state.playing = false;
        state.handed_to = Some(device_id.to_string());
        let text = Message::State(state).encode();
        self.inner.sessions.lock().unwrap().retain(|_, tx| tx.send(text.clone()).is_ok());
    }

    pub fn controllers(&self) -> usize {
        self.inner.sessions.lock().unwrap().len()
    }
}

impl Inner {
    fn state(&self) -> State {
        let mut s = self.player.state();
        s.device_id = self.ident.read().unwrap().0.id.clone();
        s.at = crate::controller::now_ms();
        s
    }

    fn controllers_changed(&self) {
        let count = self.sessions.lock().unwrap().len();
        (self.listener)(HostEvent::Controllers { count });
    }
}

async fn serve(inner: Arc<Inner>, stream: TcpStream) -> Result<(), tungstenite::Error> {
    let ws = tokio_tungstenite::accept_async(stream).await?;
    let (mut sink, mut source) = ws.split();
    let (tx, mut rx) = mpsc::unbounded_channel::<String>();
    // Everything this session sends goes through one task, in order.
    let writer = tokio::spawn(async move {
        while let Some(text) = rx.recv().await {
            if sink.send(tungstenite::Message::text(text)).await.is_err() {
                break;
            }
        }
        let _ = sink.close().await;
    });
    let send = |m: Message| {
        let _ = tx.send(m.encode());
    };

    let id = inner.next.fetch_add(1, Ordering::Relaxed);
    let nonce = protocol::new_nonce();
    let mut peer: Option<Device> = None;
    let mut trusted = false;
    let mut pending: Option<(u64, oneshot::Receiver<bool>)> = None;

    loop {
        // While the owner decides, the answer and the link are both watched.
        let incoming = if let Some((_, answer)) = pending.as_mut() {
            tokio::select! {
                accepted = answer => {
                    pending = None;
                    // A pairing request only starts after a Hello, so the peer is known.
                    match (accepted.unwrap_or(false), peer.clone()) {
                        (true, Some(device)) => {
                            inner.trusted.lock().unwrap().insert(device.id.clone());
                            (inner.listener)(HostEvent::Trusted { device_id: device.id.clone(), device_name: device.name.clone() });
                            admit(&inner, id, &tx, &device);
                            trusted = true;
                        }
                        _ => {
                            send(Message::Welcome { accepted: false });
                            break;
                        }
                    }
                    continue;
                }
                next = source.next() => next,
            }
        } else {
            source.next().await
        };
        let text = match incoming {
            Some(Ok(tungstenite::Message::Text(text))) => text,
            Some(Ok(tungstenite::Message::Close(_))) | None | Some(Err(_)) => break,
            Some(Ok(_)) => continue,
        };
        match Message::decode(text.as_str()) {
            Some(Message::Hello { device, .. }) => {
                peer = Some(device);
                let me = inner.ident.read().unwrap().0.clone();
                send(Message::Challenge { device: me, nonce: nonce.clone() });
            }
            Some(Message::Proof { hmac }) => {
                let Some(device) = peer.clone() else { continue };
                if trusted || pending.is_some() {
                    continue;
                }
                let key = inner.ident.read().unwrap().1;
                let same_account = match (&key, &hmac) {
                    (Some(key), Some(h)) => *h == protocol::proof(key, &nonce),
                    _ => false,
                };
                if same_account || inner.trusted.lock().unwrap().contains(&device.id) {
                    admit(&inner, id, &tx, &device);
                    trusted = true;
                } else {
                    let code = protocol::pairing_code(&inner.ident.read().unwrap().0.id, &device.id, &nonce);
                    send(Message::PairPending { code: code.clone() });
                    let request = inner.next.fetch_add(1, Ordering::Relaxed);
                    let (answer_tx, answer_rx) = oneshot::channel();
                    inner.requests.lock().unwrap().insert(request, answer_tx);
                    pending = Some((request, answer_rx));
                    (inner.listener)(HostEvent::PairRequest { request, device_name: device.name.clone(), code });
                }
            }
            Some(Message::Command { action, value }) if trusted => {
                log::info!("Command from '{}': {action} {value}", peer.as_ref().map_or("", |d| d.name.as_str()));
                if action == protocol::CMD_RELEASE {
                    // The music goes back to that device: hand it everything, the stream included.
                    if let Some(handover) = inner.player.release() {
                        send(Message::Transfer(handover));
                    }
                } else {
                    inner.player.command(&action, value);
                }
            }
            Some(Message::QueueEdit { op, tracks, index, to }) if trusted => inner.player.edit(&op, tracks, index, to),
            Some(Message::Transfer(transfer)) if trusted => {
                log::info!("Music moved here: {} tracks, #{} at {} ms", transfer.queue.len(), transfer.index, transfer.position_ms);
                inner.player.transfer(transfer);
            }
            _ => {}
        }
    }

    if let Some((request, _)) = pending.take() {
        inner.requests.lock().unwrap().remove(&request);
        (inner.listener)(HostEvent::PairGone { request });
    }
    let was = inner.sessions.lock().unwrap().remove(&id).is_some();
    if was {
        inner.controllers_changed();
    }
    drop(tx);
    let _ = writer.await;
    Ok(())
}

fn admit(inner: &Arc<Inner>, id: u64, tx: &mpsc::UnboundedSender<String>, device: &Device) {
    log::info!("'{}' connected", device.name);
    let _ = tx.send(Message::Welcome { accepted: true }.encode());
    let _ = tx.send(Message::State(inner.state()).encode());
    inner.sessions.lock().unwrap().insert(id, tx.clone());
    inner.controllers_changed();
}
