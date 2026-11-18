//! `cargo run --example connect_smoke`: lists the LFly devices on the network for 8 seconds.
use std::sync::Arc;

struct Print;
impl lfly_ios::ConnectListener for Print {
    fn devices(&self, peers: Vec<lfly_ios::Peer>) {
        for p in peers {
            println!("device: {} ({}) same account: {}", p.name, p.kind, p.same_account);
        }
    }
    fn pairing(&self, _: String, code: String) { println!("pairing code {code}"); }
    fn refused(&self, _: String) {}
    fn following(&self, p: Option<lfly_ios::Peer>) { println!("following {:?}", p.map(|p| p.name)); }
    fn state(&self, s: lfly_ios::RemoteState) { println!("state: playing {} {:?}", s.playing, s.track.map(|t| t.title)); }
    fn library_changed(&self) { println!("library changed"); }
}

fn main() {
    let dir = std::env::temp_dir().join("lfly-connect-smoke");
    let _ = std::fs::create_dir_all(&dir);
    let d = dir.to_string_lossy().to_string();
    let _c = lfly_ios::Connect::new("ios-smoke-test".into(), "LFly smoke".into(), d.clone(), d, Arc::new(Print)).expect("connect");
    std::thread::sleep(std::time::Duration::from_secs(8));
}
