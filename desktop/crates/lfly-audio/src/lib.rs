//! LFly desktop audio engine.
//!
//! One song at a time from a local file or an HTTPS stream (YouTube `videoplayback` URLs), decoded
//! with symphonia (Opus with the pure-Rust `opus-decoder`), resampled with rubato to the device
//! rate and played through cpal. HTTP sources are read in Range chunks through a disk cache, so a
//! song played once plays again offline. Preloading the next song makes the switch gapless.
//!
//! ```no_run
//! # async fn demo() -> Result<(), String> {
//! use lfly_audio::{Player, PlayerEvent, Source};
//! let player = Player::new("/tmp/lfly-cache", 512 << 20)?;
//! let mut events = player.subscribe();
//! player.load(Source::http("https://…googlevideo.com/videoplayback?…", "yt:dQw4w9WgXcQ"), 0, true);
//! while let Ok(ev) = events.recv().await {
//!     if let PlayerEvent::Ended = ev { break; }
//! }
//! # Ok(()) }
//! ```

pub mod cache;
pub mod decode;
mod engine;
pub mod eq;
pub mod http;
pub mod output;

use std::path::PathBuf;
use std::sync::atomic::Ordering;
use std::sync::mpsc;
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;

use serde::{Deserialize, Serialize};
use tokio::sync::broadcast;

pub use cache::DiskCache;
pub use eq::EqCurve;
pub use output::{devices, OutputDevice};

use engine::{Cmd, Shared};

/// Where a song comes from.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum Source {
    File {
        path: PathBuf,
    },
    #[serde(rename_all = "camelCase")]
    Http {
        url: String,
        /// Names the song in the disk cache (the track id): stays the same when the URL expires
        /// and is resolved again.
        cache_key: String,
        /// Extra request headers, if the URL needs any.
        #[serde(default)]
        headers: Vec<(String, String)>,
    },
}

impl Source {
    pub fn file(path: impl Into<PathBuf>) -> Self {
        Source::File { path: path.into() }
    }

    pub fn http(url: impl Into<String>, cache_key: impl Into<String>) -> Self {
        Source::Http { url: url.into(), cache_key: cache_key.into(), headers: Vec::new() }
    }

    /// The cache key, or the path for files.
    pub fn key(&self) -> String {
        match self {
            Source::File { path } => path.to_string_lossy().into_owned(),
            Source::Http { cache_key, .. } => cache_key.clone(),
        }
    }
}

/// What the player reports. Serialized as `{"type": "position", "data": {"ms": 1234}}`.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", content = "data", rename_all = "camelCase")]
pub enum PlayerEvent {
    /// A `load` started: opening the source.
    Loading,
    Playing,
    Paused,
    /// Playback position, every ~250 ms while playing (and right after a load or seek).
    Position { ms: u64 },
    Duration { ms: u64 },
    /// The song ended and nothing was preloaded after it.
    Ended,
    /// Loading or playing failed for good (after retries). Re-resolve the URL and `load` again
    /// at the last position.
    Error { message: String },
    /// Waiting for audio data (true), or flowing again (false).
    Buffering(bool),
    /// The preloaded song took over gaplessly (`key` is its [`Source::key`]).
    TrackChanged { key: String },
    /// Playing on another output device (chosen, or the old one disappeared).
    DeviceChanged { id: Option<String>, name: String },
}

/// The audio player. Every method returns at once (they post to the engine thread), so it is safe
/// to call from async code; events come through [`Player::subscribe`].
pub struct Player {
    tx: mpsc::Sender<Cmd>,
    sh: Arc<Shared>,
    cache: Arc<DiskCache>,
    threads: Mutex<Vec<JoinHandle<()>>>,
}

impl Player {
    /// Starts the engine. `cache_dir` holds the HTTP chunk cache, kept under `max_bytes`.
    /// The output device is opened at the first `load`.
    pub fn new(cache_dir: impl Into<PathBuf>, max_bytes: u64) -> Result<Self, String> {
        let cache = Arc::new(DiskCache::new(cache_dir, max_bytes).map_err(|e| format!("cache: {e}"))?);
        let (events, _) = broadcast::channel(256);
        let sh = Arc::new(Shared::new(events));
        let (tx, rx) = mpsc::channel();
        let threads = engine::spawn(sh.clone(), cache.clone(), tx.clone(), rx).map_err(|e| format!("engine: {e}"))?;
        Ok(Player { tx, sh, cache, threads: Mutex::new(threads) })
    }

    pub fn subscribe(&self) -> broadcast::Receiver<PlayerEvent> {
        self.sh.events.subscribe()
    }

    /// Plays `source` from `start_ms` (paused there unless `autoplay`). If it is the preloaded
    /// song, it starts without touching the network.
    pub fn load(&self, source: Source, start_ms: u64, autoplay: bool) {
        self.interrupt();
        self.send(Cmd::Load { source, start_ms, autoplay });
    }

    pub fn play(&self) {
        if !self.sh.loaded.load(Ordering::SeqCst) {
            return;
        }
        if self.sh.ended.load(Ordering::SeqCst) {
            self.interrupt();
            self.send(Cmd::Seek(0));
        }
        if !self.sh.out.playing.swap(true, Ordering::SeqCst) {
            self.sh.emit(PlayerEvent::Playing);
        }
    }

    pub fn pause(&self) {
        if self.sh.out.playing.swap(false, Ordering::SeqCst) {
            self.sh.emit(PlayerEvent::Paused);
        }
    }

    pub fn toggle(&self) {
        if self.is_playing() {
            self.pause()
        } else {
            self.play()
        }
    }

    pub fn is_playing(&self) -> bool {
        self.sh.out.playing.load(Ordering::SeqCst)
    }

    /// Seeks in the current song; for HTTP sources the container's index gives the byte offset,
    /// and the chunk there is fetched with a new Range request (or read from the cache).
    pub fn seek(&self, ms: u64) {
        self.interrupt();
        self.send(Cmd::Seek(ms));
    }

    /// Stops and unloads the current song (a preloaded one is kept).
    pub fn stop(&self) {
        self.interrupt();
        self.send(Cmd::Stop);
    }

    /// 0.0..=1.0, applied with a ~10 ms ramp.
    pub fn set_volume(&self, volume: f32) {
        self.sh.out.set_volume(volume);
    }

    pub fn volume(&self) -> f32 {
        self.sh.out.volume()
    }

    /// Opens the next song ahead of time (prefetching its first 2 MB into the cache). When the
    /// current song ends it continues straight into it (gapless) and emits `TrackChanged`.
    pub fn preload(&self, source: Source) {
        self.send(Cmd::Preload(Some(source)));
    }

    pub fn cancel_preload(&self) {
        self.send(Cmd::Preload(None));
    }

    /// Fades each song into the next over `ms` (0 to 12 000; 0 keeps the gapless switch).
    pub fn set_crossfade(&self, ms: u64) {
        self.send(Cmd::Crossfade(ms));
    }

    pub fn set_equalizer(&self, curve: EqCurve) {
        self.sh.out.set_eq(curve);
    }

    pub fn equalizer(&self) -> EqCurve {
        *self.sh.out.eq.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Output devices of the system (may take a moment: call it off the async runtime).
    pub fn devices() -> Vec<OutputDevice> {
        output::devices()
    }

    /// Plays on the device with this id (`None`: the system default).
    pub fn set_device(&self, id: Option<String>) {
        self.interrupt();
        self.send(Cmd::SetDevice(id));
    }

    /// Current position, if a song is loaded.
    pub fn position_ms(&self) -> Option<u64> {
        self.sh.position_ms()
    }

    pub fn duration_ms(&self) -> Option<u64> {
        self.sh.timeline().segments.front().and_then(|s| s.duration_ms)
    }

    pub fn cache(&self) -> &Arc<DiskCache> {
        &self.cache
    }

    /// Makes a blocked network read give up, so the command that follows runs at once.
    fn interrupt(&self) {
        self.sh.cmd_gen.fetch_add(1, Ordering::SeqCst);
    }

    fn send(&self, cmd: Cmd) {
        let _ = self.tx.send(cmd);
    }
}

impl Drop for Player {
    fn drop(&mut self) {
        self.sh.shutdown.store(true, Ordering::SeqCst);
        self.interrupt();
        let _ = self.tx.send(Cmd::Shutdown);
        let threads = std::mem::take(&mut *self.threads.lock().unwrap_or_else(|e| e.into_inner()));
        for t in threads {
            let _ = t.join();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn player_can_be_shared_across_tasks() {
        fn is_send_sync<T: Send + Sync>() {}
        is_send_sync::<Player>();
    }

    #[test]
    fn events_and_sources_serialize() {
        let ev = serde_json::to_string(&PlayerEvent::Position { ms: 5 }).unwrap();
        assert_eq!(ev, r#"{"type":"position","data":{"ms":5}}"#);
        let ev = serde_json::to_string(&PlayerEvent::Buffering(true)).unwrap();
        assert_eq!(ev, r#"{"type":"buffering","data":true}"#);
        let src: Source = serde_json::from_str(r#"{"kind":"http","url":"https://x","cacheKey":"yt:1"}"#).unwrap();
        assert_eq!(src, Source::http("https://x", "yt:1"));
    }
}
