//! "Listening to LFly" on Discord, through the Discord app running on this computer (its local
//! IPC socket), as LFly's own Discord application (the phone's id). No token and no sign-in: when
//! Discord isn't open, nothing happens. Paused music clears the status, as Spotify does.

use std::io::{Read, Write};
use std::sync::mpsc::{self, RecvTimeoutError, Sender};
use std::time::Duration;

use serde_json::{json, Value};

const APPLICATION_ID: &str = "1568331550532173974";

/// What Discord shows; None clears it.
#[derive(Clone, PartialEq)]
pub struct Listening {
    pub title: String,
    pub artist: String,
    pub album: Option<String>,
    pub cover_url: Option<String>,
    /// When the song started and ends, in Unix milliseconds.
    pub start_ms: i64,
    pub end_ms: Option<i64>,
}

enum Msg {
    Show(Option<Listening>),
    Enable(bool),
}

pub struct Discord(Sender<Msg>);

impl Discord {
    pub fn start(enabled: bool) -> Self {
        let (tx, rx) = mpsc::channel::<Msg>();
        std::thread::Builder::new()
            .name("lfly-discord".into())
            .spawn(move || {
                let mut enabled = enabled;
                let mut wanted: Option<Listening> = None;
                let mut shown: Option<Option<Listening>> = None;
                let mut link: Option<Link> = None;
                loop {
                    match rx.recv_timeout(Duration::from_secs(20)) {
                        Ok(Msg::Show(l)) => wanted = l,
                        Ok(Msg::Enable(on)) => enabled = on,
                        Err(RecvTimeoutError::Timeout) => {}
                        Err(RecvTimeoutError::Disconnected) => break,
                    }
                    // Only the latest of a burst of updates matters.
                    while let Ok(msg) = rx.try_recv() {
                        match msg {
                            Msg::Show(l) => wanted = l,
                            Msg::Enable(on) => enabled = on,
                        }
                    }
                    let target = if enabled { wanted.clone() } else { None };
                    if shown.as_ref().is_some_and(|s| same(s, &target)) && link.is_some() {
                        continue;
                    }
                    if link.is_none() {
                        if target.is_none() {
                            shown = Some(None);
                            continue;
                        }
                        link = Link::open();
                    }
                    let Some(l) = link.as_mut() else { continue };
                    match l.set_activity(target.as_ref()) {
                        Ok(()) => shown = Some(target),
                        Err(e) => {
                            log::debug!("Discord: {e}");
                            link = None;
                            shown = None;
                        }
                    }
                }
            })
            .expect("discord thread");
        Discord(tx)
    }

    pub fn show(&self, listening: Option<Listening>) {
        let _ = self.0.send(Msg::Show(listening));
    }

    pub fn set_enabled(&self, on: bool) {
        let _ = self.0.send(Msg::Enable(on));
    }
}

/// One connection to Discord's IPC: frames of `[opcode u32 LE][length u32 LE][JSON]`.
struct Link {
    #[cfg(unix)]
    pipe: std::os::unix::net::UnixStream,
    #[cfg(windows)]
    pipe: std::fs::File,
    nonce: u64,
}

impl Link {
    fn open() -> Option<Link> {
        for path in candidates() {
            #[cfg(unix)]
            let pipe = std::os::unix::net::UnixStream::connect(&path).ok();
            #[cfg(windows)]
            let pipe = std::fs::OpenOptions::new().read(true).write(true).open(&path).ok();
            let Some(pipe) = pipe else { continue };
            #[cfg(unix)]
            let _ = pipe.set_read_timeout(Some(Duration::from_secs(5)));
            let mut link = Link { pipe, nonce: 0 };
            if link.send(0, &json!({ "v": 1, "client_id": APPLICATION_ID })).is_ok() && link.receive().is_ok() {
                log::info!("Discord: connected");
                return Some(link);
            }
        }
        None
    }

    fn set_activity(&mut self, listening: Option<&Listening>) -> std::io::Result<()> {
        self.nonce += 1;
        let activity = listening.map(|l| {
            let mut a = json!({
                "type": 2, // Listening to
                "details": clip(&l.title),
                "state": clip(if l.artist.is_empty() { " " } else { &l.artist }),
                "timestamps": { "start": l.start_ms },
            });
            if let Some(end) = l.end_ms {
                a["timestamps"]["end"] = json!(end);
            }
            let mut assets = serde_json::Map::new();
            if let Some(cover) = l.cover_url.as_deref().filter(|u| u.starts_with("https://")) {
                assets.insert("large_image".into(), json!(cover));
            }
            if let Some(album) = l.album.as_deref().filter(|a| !a.is_empty()) {
                assets.insert("large_text".into(), json!(clip(album)));
            }
            if !assets.is_empty() {
                a["assets"] = Value::Object(assets);
            }
            a
        });
        let payload = json!({
            "cmd": "SET_ACTIVITY",
            "args": { "pid": std::process::id(), "activity": activity },
            "nonce": self.nonce.to_string(),
        });
        self.send(1, &payload)?;
        let answer = self.receive()?;
        if answer.get("evt").and_then(Value::as_str) == Some("ERROR") {
            log::debug!("Discord refused the status: {answer}");
        }
        Ok(())
    }

    fn send(&mut self, op: u32, value: &Value) -> std::io::Result<()> {
        let body = value.to_string();
        let mut frame = Vec::with_capacity(8 + body.len());
        frame.extend_from_slice(&op.to_le_bytes());
        frame.extend_from_slice(&(body.len() as u32).to_le_bytes());
        frame.extend_from_slice(body.as_bytes());
        self.pipe.write_all(&frame)
    }

    fn receive(&mut self) -> std::io::Result<Value> {
        let mut head = [0u8; 8];
        self.pipe.read_exact(&mut head)?;
        let len = u32::from_le_bytes([head[4], head[5], head[6], head[7]]) as usize;
        if len > 1 << 20 {
            return Err(std::io::Error::other("frame too large"));
        }
        let mut body = vec![0u8; len];
        self.pipe.read_exact(&mut body)?;
        serde_json::from_slice(&body).map_err(std::io::Error::other)
    }
}

/// The same status, give or take the jitter of when the window measured the position.
fn same(a: &Option<Listening>, b: &Option<Listening>) -> bool {
    match (a, b) {
        (None, None) => true,
        (Some(a), Some(b)) => {
            a.title == b.title
                && a.artist == b.artist
                && a.cover_url == b.cover_url
                && (a.start_ms - b.start_ms).abs() < 2_000
                && a.end_ms.is_some() == b.end_ms.is_some()
        }
        _ => false,
    }
}

/// Discord's text fields take 2 to 128 characters.
fn clip(s: &str) -> String {
    let t: String = s.chars().take(128).collect();
    if t.chars().count() < 2 { format!("{t} ") } else { t }
}

/// Where Discord's socket may be: the usual runtime folder, and the Flatpak and Snap ones.
fn candidates() -> Vec<std::path::PathBuf> {
    let mut out = Vec::new();
    #[cfg(windows)]
    for i in 0..10 {
        out.push(std::path::PathBuf::from(format!(r"\\.\pipe\discord-ipc-{i}")));
    }
    #[cfg(unix)]
    {
        let mut bases: Vec<std::path::PathBuf> = ["XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP"]
            .iter()
            .filter_map(|v| std::env::var_os(v).map(std::path::PathBuf::from))
            .collect();
        bases.push("/tmp".into());
        for base in bases {
            for sub in ["", "app/com.discordapp.Discord", "app/com.discordapp.DiscordCanary", "snap.discord", ".flatpak/dev.vencord.Vesktop/xdg-run"] {
                for i in 0..10 {
                    out.push(base.join(sub).join(format!("discord-ipc-{i}")));
                }
            }
        }
    }
    out
}
