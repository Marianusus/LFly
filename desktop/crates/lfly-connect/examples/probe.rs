//! Development probe: acts as a phone against a running LFly desktop, to test it end to end
//! without touching the window. `cargo run -p lfly-connect --example probe -- <port> [videoId]`
//! The probe's id must be in the desktop's trusted list (or the owner accepts it in the window).

use std::time::Duration;

use lfly_connect::protocol::{self, Device, Message, TrackRef, Transfer};
use lfly_connect::{ConnectDevice, Controller, ControllerEvent};

#[tokio::main]
async fn main() {
    let mut args = std::env::args().skip(1);
    let port: u16 = args.next().and_then(|p| p.parse().ok()).expect("port");
    let video = args.next().unwrap_or_else(|| "5NV6Rdv1a3I".into());
    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel();
    let me = Device { id: "probe-1".into(), name: "Probe".into(), kind: "phone".into(), owner: None };
    let remote = Controller::new(me, None, move |e| {
        let _ = tx.send(e);
    });
    remote.follow(ConnectDevice {
        id: "desktop".into(),
        name: "Desktop".into(),
        kind: "desktop".into(),
        same_account: false,
        host: "127.0.0.1".into(),
        port,
    });
    let deadline = tokio::time::Instant::now() + Duration::from_secs(40);
    let mut sent = false;
    let mut last = String::new();
    let mut sought = false;
    while let Ok(Some(e)) = tokio::time::timeout_at(deadline, rx.recv()).await {
        match e {
            ControllerEvent::Pairing { code, .. } => println!("PAIRING {code}"),
            ControllerEvent::Refused { .. } => {
                println!("REFUSED");
                return;
            }
            ControllerEvent::Following { device: Some(_) } if !sent => {
                sent = true;
                println!("CONNECTED");
                remote.command(protocol::CMD_VOLUME, 0);
                remote.send(Message::Transfer(Transfer {
                    queue: vec![TrackRef {
                        id: format!("yt_{video}"),
                        title: "Probe".into(),
                        artist: "Probe".into(),
                        album: None,
                        duration_ms: 0,
                        cover_url: None,
                        provider: "YouTube Music".into(),
                        artist_id: None,
                        album_id: None,
                        is_video: false,
                        stream_url: None,
                    }],
                    index: 0,
                    position_ms: 0,
                    playing: true,
                    from_device_id: "probe-1".into(),
                }));
            }
            ControllerEvent::State { state, .. } => {
                let line = format!(
                    "STATE playing={} pos={} dur={} index={} queue={} volume={}",
                    state.playing, state.position_ms, state.duration_ms, state.index, state.queue.len(), state.volume
                );
                if line != last {
                    println!("{line}");
                    last = line;
                }
                if state.playing && state.position_ms > 8_000 && !sought {
                    sought = true;
                    remote.command(protocol::CMD_SEEK, 60_000);
                }
                // After the seek, the position must never go back under it.
                if sought && state.position_ms < 60_000 && state.position_ms > 0 {
                    println!("JUMPED BACK to {}", state.position_ms);
                }
                if state.position_ms > 62_000 {
                    remote.command(protocol::CMD_PAUSE, 0);
                    tokio::time::sleep(Duration::from_millis(800)).await;
                    println!("DONE");
                    return;
                }
            }
            other => println!("{other:?}"),
        }
    }
    println!("TIMEOUT");
}
