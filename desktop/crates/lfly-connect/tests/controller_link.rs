//! The remote against a host that answers like the phone's `ConnectManager.HostServer`.

use std::sync::{Arc, Mutex};
use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use lfly_connect::protocol::{self, Device, Message, State};
use lfly_connect::{ConnectDevice, Controller, ControllerEvent};
use tokio::net::TcpListener;
use tokio::sync::mpsc;
use tokio_tungstenite::tungstenite;

const SECRET: &str = "12345678901234567890";

#[derive(Clone, Copy)]
enum Owner {
    /// Signed into the same account as the remote.
    Same,
    /// Another account: its owner accepts (true) or refuses (false).
    Other(bool),
}

/// Starts a fake phone; returns its address and what it received.
async fn fake_phone(owner: Owner) -> (ConnectDevice, Arc<Mutex<Vec<Message>>>) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let received = Arc::new(Mutex::new(Vec::new()));
    let log = received.clone();
    tokio::spawn(async move {
        let (stream, _) = listener.accept().await.unwrap();
        let mut ws = tokio_tungstenite::accept_async(stream).await.unwrap();
        let me = Device { id: "phone-1".into(), name: "Galaxy".into(), kind: "phone".into(), owner: None };
        let nonce = protocol::new_nonce();
        let mut client_id = String::new();
        let send = |m: Message| tungstenite::Message::text(m.encode());
        while let Some(Ok(tungstenite::Message::Text(text))) = ws.next().await {
            let message = Message::decode(text.as_str()).unwrap();
            log.lock().unwrap().push(message.clone());
            match message {
                Message::Hello { device, .. } => {
                    client_id = device.id;
                    ws.send(send(Message::Challenge { device: me.clone(), nonce: nonce.clone() })).await.unwrap();
                }
                Message::Proof { hmac } => {
                    let key = match owner {
                        Owner::Same => Some(protocol::account_key(SECRET)),
                        Owner::Other(_) => Some(protocol::account_key("someone-else")),
                    };
                    let same = key.is_some() && hmac.as_deref() == key.map(|k| protocol::proof(&k, &nonce)).as_deref();
                    let accepted = match owner {
                        _ if same => true,
                        Owner::Other(accept) => {
                            let code = protocol::pairing_code(&me.id, &client_id, &nonce);
                            ws.send(send(Message::PairPending { code })).await.unwrap();
                            tokio::time::sleep(Duration::from_millis(50)).await;
                            accept
                        }
                        Owner::Same => false,
                    };
                    ws.send(send(Message::Welcome { accepted })).await.unwrap();
                    if !accepted {
                        return;
                    }
                    ws.send(send(Message::State(state(false)))).await.unwrap();
                }
                Message::Command { action, .. } if action == protocol::CMD_PLAY => {
                    ws.send(send(Message::State(state(true)))).await.unwrap();
                }
                _ => {}
            }
        }
    });
    let device = ConnectDevice {
        id: "phone-1".into(),
        name: "Galaxy".into(),
        kind: "phone".into(),
        same_account: false,
        host: "127.0.0.1".into(),
        port,
    };
    (device, received)
}

fn state(playing: bool) -> State {
    State {
        device_id: "phone-1".into(),
        active: true,
        playing,
        position_ms: 1_000,
        at: 0,
        duration_ms: 200_000,
        index: 0,
        queue: vec![],
        shuffle: false,
        repeat: "off".into(),
        volume: 50,
        handed_to: None,
    }
}

fn remote(secret: Option<&str>) -> (Controller, mpsc::UnboundedReceiver<ControllerEvent>) {
    let (tx, rx) = mpsc::unbounded_channel();
    let me = Device { id: "desktop-1".into(), name: "PC".into(), kind: "desktop".into(), owner: None };
    let controller = Controller::new(me, secret, move |e| {
        let _ = tx.send(e);
    });
    (controller, rx)
}

async fn next_matching(rx: &mut mpsc::UnboundedReceiver<ControllerEvent>, pick: impl Fn(&ControllerEvent) -> bool) -> ControllerEvent {
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
async fn the_same_account_is_trusted_at_once_and_commands_reach_the_phone() {
    let (phone, received) = fake_phone(Owner::Same).await;
    let (controller, mut events) = remote(Some(SECRET));
    controller.follow(phone.clone());

    let e = next_matching(&mut events, |e| matches!(e, ControllerEvent::Following { .. })).await;
    assert!(matches!(e, ControllerEvent::Following { device: Some(d) } if d.id == "phone-1"));
    let e = next_matching(&mut events, |e| matches!(e, ControllerEvent::State { .. })).await;
    assert!(matches!(e, ControllerEvent::State { state, .. } if !state.playing));

    controller.command(protocol::CMD_PLAY, 0);
    // Shown at once, then confirmed by the phone.
    let e = next_matching(&mut events, |e| matches!(e, ControllerEvent::State { .. })).await;
    assert!(matches!(e, ControllerEvent::State { state, .. } if state.playing));
    next_matching(&mut events, |e| matches!(e, ControllerEvent::State { .. })).await;

    let got = received.lock().unwrap().clone();
    assert!(matches!(&got[0], Message::Hello { device, protocol: 1 } if device.id == "desktop-1"));
    assert!(matches!(&got[1], Message::Proof { hmac: Some(_) }));
    assert!(matches!(&got[2], Message::Command { action, .. } if action == "play"));
    assert!(controller.following().is_some());
}

#[tokio::test]
async fn another_account_shows_the_pairing_code_and_gets_in_once_accepted() {
    let (phone, _) = fake_phone(Owner::Other(true)).await;
    let (controller, mut events) = remote(None);
    controller.follow(phone);

    let e = next_matching(&mut events, |e| matches!(e, ControllerEvent::Pairing { .. })).await;
    let ControllerEvent::Pairing { code, device_name } = e else { unreachable!() };
    assert_eq!(code.len(), 4);
    assert_eq!(device_name, "Galaxy");
    let e = next_matching(&mut events, |e| matches!(e, ControllerEvent::Following { .. })).await;
    assert!(matches!(e, ControllerEvent::Following { device: Some(_) }));
}

#[tokio::test]
async fn a_refusal_is_reported_and_nothing_is_followed() {
    let (phone, _) = fake_phone(Owner::Other(false)).await;
    let (controller, mut events) = remote(None);
    controller.follow(phone);

    next_matching(&mut events, |e| matches!(e, ControllerEvent::Refused { .. })).await;
    assert!(controller.following().is_none());
}
