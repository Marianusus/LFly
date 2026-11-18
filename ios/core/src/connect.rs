//! LFly Connect on iPhone, through the desktop's lfly-connect: the phones and computers in sight,
//! following one of them as a remote (what plays there, its controls; a code to accept the first
//! time when it is not the same account), and the library kept the same with the devices of the
//! same YouTube Music account (lfly-core's libsync, offered from here every minute and a half and
//! a few seconds after each edit).
//!
//! The iPhone does not host: it opens the links, so it needs no listening port.

use std::path::PathBuf;
use std::sync::{Arc, Mutex, OnceLock};

use lfly_connect::protocol::{self, Device};
use lfly_connect::{ConnectDevice, Controller, ControllerEvent, Discovery};

use crate::libsync::{Event as SyncEvent, LibSync, Link};

fn runtime() -> &'static tokio::runtime::Runtime {
    static RT: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
    RT.get_or_init(|| tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().build().expect("runtime"))
}

/// A phone or computer with LFly on the network.
#[derive(Debug, Clone, uniffi::Record)]
pub struct Peer {
    pub id: String,
    pub name: String,
    /// "phone", "computer", "watch"…
    pub kind: String,
    /// Signed into the same YouTube Music account.
    pub same_account: bool,
}

/// A song in the followed device's queue.
#[derive(Debug, Clone, uniffi::Record)]
pub struct RemoteTrack {
    pub title: String,
    pub artist: String,
    pub cover_url: Option<String>,
    pub duration_ms: i64,
}

/// What plays on the followed device.
#[derive(Debug, Clone, uniffi::Record)]
pub struct RemoteState {
    pub playing: bool,
    pub position_ms: i64,
    pub duration_ms: i64,
    pub track: Option<RemoteTrack>,
    pub volume: i32,
}

/// What Connect tells the app; called on a background thread.
#[uniffi::export(with_foreign)]
pub trait ConnectListener: Send + Sync {
    fn devices(&self, peers: Vec<Peer>);
    /// The followed device's owner must accept this iPhone: both screens show [code].
    fn pairing(&self, device_name: String, code: String);
    fn refused(&self, device_name: String);
    fn following(&self, peer: Option<Peer>);
    fn state(&self, state: RemoteState);
    /// The library files were rewritten by a sync: read them again.
    fn library_changed(&self);
}

fn peer(d: &ConnectDevice) -> Peer {
    Peer { id: d.id.clone(), name: d.name.clone(), kind: d.kind.clone(), same_account: d.same_account }
}

struct Me {
    device: Device,
    secret: Option<String>,
}

#[derive(uniffi::Object)]
pub struct Connect {
    me: Arc<Mutex<Me>>,
    discovery: Arc<Discovery>,
    controller: Controller,
    sync: Arc<LibSync>,
}

#[uniffi::export]
impl Connect {
    /// [id] stays the same across launches; [library_dir] holds favorites.json and the rest,
    /// [state_dir] what each peer agreed on last time.
    #[uniffi::constructor]
    pub fn new(id: String, name: String, library_dir: String, state_dir: String, listener: Arc<dyn ConnectListener>) -> std::result::Result<Arc<Self>, crate::CoreError> {
        let fail = |e: String| crate::CoreError::Failed { message: e };
        let device = Device { id, name, kind: "phone".into(), owner: None };
        let me = Arc::new(Mutex::new(Me { device: device.clone(), secret: None }));
        let discovery = Arc::new(Discovery::new().map_err(fail)?);

        let l = listener.clone();
        let controller = {
            let _rt = runtime().enter();
            Controller::new(device.clone(), None, move |e| match e {
                ControllerEvent::Pairing { device_name, code } => l.pairing(device_name, code),
                ControllerEvent::Refused { device_name } => l.refused(device_name),
                ControllerEvent::Following { device } => l.following(device.as_ref().map(peer)),
                ControllerEvent::State { state, .. } => {
                    let track = usize::try_from(state.index).ok().and_then(|i| state.queue.get(i)).map(|t| RemoteTrack {
                        title: t.title.clone(),
                        artist: t.artist.clone(),
                        cover_url: t.cover_url.clone(),
                        duration_ms: t.duration_ms,
                    });
                    l.state(RemoteState { playing: state.playing, position_ms: state.position_ms, duration_ms: state.duration_ms, track, volume: state.volume });
                }
                _ => {}
            })
        };

        let (m, d) = (me.clone(), discovery.clone());
        let l = listener.clone();
        let sync = Arc::new(LibSync::at(
            PathBuf::from(state_dir).join("library-sync.json"),
            PathBuf::from(library_dir),
            move || {
                let me = m.lock().unwrap();
                let secret = me.secret.clone()?;
                Some(Link { me: me.device.clone(), secret, devices: d.devices() })
            },
            move |e| {
                if let SyncEvent::LibraryChanged = e {
                    l.library_changed();
                }
            },
        ));

        let l = listener.clone();
        discovery.browse(&device, move |all| l.devices(all.iter().map(peer).collect())).map_err(fail)?;
        runtime().spawn(sync.clone().run());
        Ok(Arc::new(Self { me, discovery, controller, sync }))
    }

    /// The account's secret (from [crate::Core::connect_secret]), or none when signed out: devices
    /// of the same account trust each other and share the library.
    pub fn set_account(&self, secret: Option<String>) {
        let device = {
            let mut me = self.me.lock().unwrap();
            me.device.owner = secret.as_deref().map(protocol::owner_tag);
            me.secret = secret.clone();
            me.device.clone()
        };
        self.discovery.set_owner(device.owner.clone());
        let _rt = runtime().enter();
        self.controller.set_identity(device, secret.as_deref());
    }

    /// Follows that device as a remote.
    pub fn follow(&self, id: String) {
        let Some(d) = self.discovery.devices().into_iter().find(|d| d.id == id) else { return };
        let _rt = runtime().enter();
        self.controller.follow(d);
    }

    pub fn disconnect(&self) {
        let _rt = runtime().enter();
        self.controller.disconnect();
    }

    /// A control for the followed device: "toggle", "next", "previous", "seek" (ms), "volume"…
    pub fn command(&self, action: String, value: i64) {
        let _rt = runtime().enter();
        self.controller.command(&action, value);
    }

    /// The followed device's position now, from its last state and the time since.
    pub fn position_now(&self) -> i64 {
        self.controller.position_now()
    }

    /// The library changed here: offer it to the account's devices in a few seconds.
    pub fn library_edited(&self) {
        runtime().spawn(self.sync.changed());
    }

    /// Offers the library now; how many devices answered.
    pub async fn sync_now(&self) -> u32 {
        let sync = self.sync.clone();
        runtime().spawn(async move { sync.sync_all().await }).await.unwrap_or(0) as u32
    }
}
