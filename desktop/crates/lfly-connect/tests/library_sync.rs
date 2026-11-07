//! The library link: a device of the same account offers its library and gets the merged one
//! back; another account is refused without bothering the host's owner with a code.

use std::sync::{Arc, Mutex};

use lfly_connect::library::exchange;
use lfly_connect::{ConnectDevice, Device, Host, HostEvent, HostPlayer, State, TrackRef, Transfer};
use serde_json::{json, Value};

struct Lib {
    offered: Mutex<Option<(String, Value)>>,
}

impl HostPlayer for Lib {
    fn state(&self) -> State {
        serde_json::from_value(json!({})).unwrap()
    }
    fn command(&self, _: &str, _: i64) {}
    fn edit(&self, _: &str, _: Vec<TrackRef>, _: i32, _: i32) {}
    fn transfer(&self, _: Transfer) {}
    fn release(&self) -> Option<Transfer> {
        None
    }
    fn library(&self, peer: &str, offered: Value) -> Option<Value> {
        *self.offered.lock().unwrap() = Some((peer.to_string(), offered.clone()));
        let mut merged = offered;
        merged["favorites"].as_array_mut().unwrap().push(json!({ "id": "from-host" }));
        Some(merged)
    }
}

fn me(id: &str, kind: &str) -> Device {
    Device { id: id.into(), name: id.into(), kind: kind.into(), owner: None }
}

fn at(port: u16) -> ConnectDevice {
    ConnectDevice { id: "phone".into(), name: "Phone".into(), kind: "phone".into(), same_account: true, host: "127.0.0.1".into(), port }
}

#[tokio::test]
async fn the_same_account_gets_the_merged_library_back() {
    let lib = Arc::new(Lib { offered: Mutex::new(None) });
    let host = Host::start(me("phone", "phone"), Some("secret"), Vec::<String>::new(), lib.clone(), |_| {}).await.unwrap();
    let offered = json!({ "favorites": [{ "id": "from-pc" }], "playlists": [], "history": [] });
    let merged = exchange(me("pc", "desktop"), "secret", &at(host.port()), offered).await.unwrap();
    let ids: Vec<&str> = merged["favorites"].as_array().unwrap().iter().map(|t| t["id"].as_str().unwrap()).collect();
    assert_eq!(ids, ["from-pc", "from-host"]);
    assert_eq!(lib.offered.lock().unwrap().as_ref().unwrap().0, "pc");
}

#[tokio::test]
async fn another_account_is_refused_without_a_pairing_prompt_kept_open() {
    let lib = Arc::new(Lib { offered: Mutex::new(None) });
    let events = Arc::new(Mutex::new(Vec::new()));
    let seen = events.clone();
    let host = Host::start(me("phone", "phone"), Some("secret"), Vec::<String>::new(), lib.clone(), move |e| seen.lock().unwrap().push(e)).await.unwrap();
    let r = exchange(me("pc", "desktop"), "another", &at(host.port()), json!({ "favorites": [] })).await;
    assert!(r.is_err());
    assert!(lib.offered.lock().unwrap().is_none());
    // The request the host showed is withdrawn when the link closes.
    for _ in 0..50 {
        if events.lock().unwrap().iter().any(|e| matches!(e, HostEvent::PairGone { .. })) {
            return;
        }
        tokio::time::sleep(std::time::Duration::from_millis(20)).await;
    }
    panic!("the pairing request stayed open");
}
