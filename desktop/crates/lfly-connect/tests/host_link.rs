//! This computer as a host, driven by the remote side of the same crate (both speak the phone's
//! protocol, checked separately against the shared vectors).

use std::sync::{Arc, Mutex};
use std::time::Duration;

use lfly_connect::protocol::{self, Device, State, TrackRef, Transfer};
use lfly_connect::{ConnectDevice, Controller, ControllerEvent, Host, HostEvent, HostPlayer};
use tokio::sync::mpsc;

#[derive(Default)]
struct FakePlayer {
    log: Mutex<Vec<String>>,
    playing: Mutex<bool>,
}

fn track(id: &str) -> TrackRef {
    TrackRef {
        id: id.into(),
        title: "T".into(),
        artist: "A".into(),
        album: None,
        duration_ms: 1000,
        cover_url: None,
        provider: "YouTube Music".into(),
        artist_id: None,
        album_id: None,
        is_video: false,
        stream_url: None,
    }
}

impl HostPlayer for FakePlayer {
    fn state(&self) -> State {
        State {
            device_id: String::new(),
            active: true,
            playing: *self.playing.lock().unwrap(),
            position_ms: 500,
            at: 0,
            duration_ms: 1000,
            index: 0,
            queue: vec![track("yt_a")],
            shuffle: false,
            repeat: "off".into(),
            volume: 70,
            handed_to: None,
        }
    }
    fn command(&self, action: &str, value: i64) {
        if action == protocol::CMD_PLAY {
            *self.playing.lock().unwrap() = true;
        }
        self.log.lock().unwrap().push(format!("{action}:{value}"));
    }
    fn edit(&self, op: &str, tracks: Vec<TrackRef>, _index: i32, _to: i32) {
        self.log.lock().unwrap().push(format!("edit:{op}:{}", tracks.len()));
    }
    fn transfer(&self, t: Transfer) {
        self.log.lock().unwrap().push(format!("transfer:{}", t.queue.len()));
    }
    fn release(&self) -> Option<Transfer> {
        *self.playing.lock().unwrap() = false;
        Some(Transfer { queue: vec![track("yt_a")], index: 0, position_ms: 500, playing: true, from_device_id: "pc".into() })
    }
}

fn me(id: &str, kind: &str) -> Device {
    Device { id: id.into(), name: id.into(), kind: kind.into(), owner: None }
}

async fn wait<T>(rx: &mut mpsc::UnboundedReceiver<T>, pick: impl Fn(&T) -> bool) -> T {
    tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            let e = rx.recv().await.expect("events end");
            if pick(&e) {
                return e;
            }
        }
    })
    .await
    .expect("event in time")
}

#[tokio::test]
async fn a_new_device_pairs_once_then_controls_the_player() {
    let player = Arc::new(FakePlayer::default());
    let (host_tx, mut host_rx) = mpsc::unbounded_channel();
    let host = Host::start(me("pc", "desktop"), None, Vec::<String>::new(), player.clone(), move |e| {
        let _ = host_tx.send(e);
    })
    .await
    .unwrap();

    let (tx, mut events) = mpsc::unbounded_channel();
    let phone = Controller::new(me("phone", "phone"), None, move |e| {
        let _ = tx.send(e);
    });
    let device = ConnectDevice {
        id: "pc".into(),
        name: "PC".into(),
        kind: "desktop".into(),
        same_account: false,
        host: "127.0.0.1".into(),
        port: host.port(),
    };
    phone.follow(device.clone());

    // Both screens show the same code; the owner accepts.
    let HostEvent::PairRequest { request, code, .. } = wait(&mut host_rx, |e| matches!(e, HostEvent::PairRequest { .. })).await else {
        unreachable!()
    };
    let ControllerEvent::Pairing { code: shown, .. } = wait(&mut events, |e| matches!(e, ControllerEvent::Pairing { .. })).await else {
        unreachable!()
    };
    assert_eq!(code, shown);
    host.answer(request, true);
    wait(&mut host_rx, |e| matches!(e, HostEvent::Trusted { device_id, .. } if device_id == "phone")).await;
    let ControllerEvent::State { state, .. } = wait(&mut events, |e| matches!(e, ControllerEvent::State { .. })).await else {
        unreachable!()
    };
    assert_eq!(state.device_id, "pc");
    assert_eq!(state.volume, 70);

    phone.command(protocol::CMD_PLAY, 0);
    phone.send(protocol::Message::QueueEdit { op: "add".into(), tracks: vec![track("yt_b")], index: -1, to: -1 });
    tokio::time::sleep(Duration::from_millis(200)).await;
    host.broadcast();
    let ControllerEvent::State { state, .. } = wait(&mut events, |e| matches!(e, ControllerEvent::State { state, .. } if state.playing)).await else {
        unreachable!()
    };
    assert!(state.playing);
    let log = player.log.lock().unwrap().clone();
    assert_eq!(log, vec!["play:0".to_string(), "edit:add:1".to_string()]);
    assert_eq!(host.controllers(), 1);
}

#[tokio::test]
async fn a_trusted_device_gets_in_without_asking() {
    let player = Arc::new(FakePlayer::default());
    let (host_tx, mut host_rx) = mpsc::unbounded_channel();
    let host = Host::start(me("pc", "desktop"), None, vec!["phone".to_string()], player, move |e| {
        let _ = host_tx.send(e);
    })
    .await
    .unwrap();
    let (tx, mut events) = mpsc::unbounded_channel();
    let phone = Controller::new(me("phone", "phone"), None, move |e| {
        let _ = tx.send(e);
    });
    phone.follow(ConnectDevice {
        id: "pc".into(),
        name: "PC".into(),
        kind: "desktop".into(),
        same_account: false,
        host: "127.0.0.1".into(),
        port: host.port(),
    });
    wait(&mut events, |e| matches!(e, ControllerEvent::Following { device: Some(_) })).await;
    assert!(matches!(wait(&mut host_rx, |e| matches!(e, HostEvent::Controllers { .. })).await, HostEvent::Controllers { count: 1 }));
}

#[tokio::test]
async fn the_same_account_is_admitted_and_release_hands_the_music_back() {
    let player = Arc::new(FakePlayer::default());
    let host = Host::start(me("pc", "desktop"), Some("secret"), Vec::<String>::new(), player, |_| {}).await.unwrap();
    let (tx, mut events) = mpsc::unbounded_channel();
    let phone = Controller::new(me("phone", "phone"), Some("secret"), move |e| {
        let _ = tx.send(e);
    });
    phone.follow(ConnectDevice {
        id: "pc".into(),
        name: "PC".into(),
        kind: "desktop".into(),
        same_account: true,
        host: "127.0.0.1".into(),
        port: host.port(),
    });
    wait(&mut events, |e| matches!(e, ControllerEvent::Following { device: Some(_) })).await;
    host.handed_to("phone");
    wait(&mut events, |e| matches!(e, ControllerEvent::HandedTo { device_id } if device_id == "phone")).await;
}
