//! What the window talks to: YouTube Music, the shared player (lfly-core, the same one the
//! Tauri app runs: queue, gapless preload of the next song, expired-URL retry) and a tokio
//! runtime for both.
//!
//! GPUI has its own executor; the network code (reqwest) needs tokio, so it runs here and the
//! window awaits the join handles (they work from any executor).

use std::sync::{Arc, OnceLock};

use lfly_connect::protocol::{State, TrackRef};
use lfly_core::player::{LocalPlayer, PlayerHost};
use lfly_yt::{AudioPreference, Track, YtMusic, YtMusicConfig};
use tokio::runtime::Runtime;
use tokio::sync::mpsc;

pub fn runtime() -> &'static Runtime {
    static RT: OnceLock<Runtime> = OnceLock::new();
    RT.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .thread_name("lfly-net")
            .enable_all()
            .build()
            .expect("tokio runtime")
    })
}

/// The player's reports, for the window.
#[derive(Debug)]
pub enum PlayerUpdate {
    State(Box<State>),
    Position { ms: i64 },
    Notice(String),
}

struct NativeHost(mpsc::UnboundedSender<PlayerUpdate>);

impl PlayerHost for NativeHost {
    fn state(&self, state: &State, _received_at: i64) {
        let _ = self.0.send(PlayerUpdate::State(Box::new(state.clone())));
    }
    fn position(&self, position_ms: i64, received_at: i64) {
        let _ = (received_at, self.0.send(PlayerUpdate::Position { ms: position_ms }));
    }
    fn notice(&self, text: &str) {
        let _ = self.0.send(PlayerUpdate::Notice(text.to_string()));
    }
    /// Settings → Riproduzione: play on with similar songs.
    fn autoplay(&self) -> bool {
        lfly_core::settings::autoplay()
    }
}

pub struct Core {
    pub yt: YtMusic,
    pub player: Arc<LocalPlayer>,
    http: reqwest::Client,
}

pub fn track_ref(t: &Track) -> Option<TrackRef> {
    serde_json::from_value(serde_json::to_value(t).ok()?).ok()
}

/// The player's track as the pages want it (lyrics, radio): the same fields, by serde.
pub fn track_of(r: &TrackRef) -> Option<Track> {
    serde_json::from_value(serde_json::to_value(r).ok()?).ok()
}

impl Core {
    pub fn new() -> anyhow::Result<(Arc<Self>, mpsc::UnboundedReceiver<PlayerUpdate>)> {
        let yt = YtMusic::with_config(YtMusicConfig { preference: AudioPreference::AacFirst, ..Default::default() })
            .map_err(|e| anyhow::anyhow!("{e}"))?;
        // Its own cache while the Tauri app lives next to it: two engines must not share one.
        let cache = dirs::cache_dir().ok_or_else(|| anyhow::anyhow!("no cache dir"))?.join("lfly").join("audio-native");
        // Settings → Riproduzione: the format and "play on", as saved (by either window).
        lfly_core::settings::load(&yt);
        let (tx, rx) = mpsc::unbounded_channel();
        let player = LocalPlayer::new(
            Arc::new(NativeHost(tx)),
            runtime().handle().clone(),
            yt.clone(),
            "lfly-native".into(),
            cache,
        )
        .map_err(|e| anyhow::anyhow!("{e}"))?;
        let http = reqwest::Client::builder().user_agent("Mozilla/5.0").build()?;
        Ok((Arc::new(Self { yt, player, http }), rx))
    }

    #[allow(dead_code)] // examples/smoke.rs
    pub async fn search(self: Arc<Self>, query: String) -> Result<Vec<Track>, String> {
        runtime()
            .spawn(async move { self.yt.search_songs(&query, 50).await.map_err(|e| e.to_string()) })
            .await
            .map_err(|e| e.to_string())?
    }

    /// Plays [tracks] from [index]: the full stream is resolved by the shared player.
    pub fn play(&self, tracks: &[Track], index: usize) {
        let queue = tracks.iter().filter_map(track_ref).collect();
        self.player.play_queue(queue, index as i32, 0, true);
    }

    /// The Home shelves (lfly_core::pages, as the Tauri window gets them).
    pub async fn home(self: Arc<Self>) -> Result<Vec<lfly_core::pages::Shelf>, String> {
        runtime().spawn(async move { lfly_core::pages::home(&self.yt).await }).await.map_err(|e| e.to_string())?
    }

    /// Album and playlist pages, for playing a card.
    pub async fn album_tracks(self: Arc<Self>, id: String) -> Result<Vec<Track>, String> {
        runtime().spawn(async move { lfly_core::pages::album(&self.yt, id).await.map(|a| a.tracks) }).await.map_err(|e| e.to_string())?
    }

    pub async fn playlist_tracks(self: Arc<Self>, id: String) -> Result<Vec<Track>, String> {
        runtime().spawn(async move { lfly_core::pages::playlist(&self.yt, id).await.map(|p| p.tracks) }).await.map_err(|e| e.to_string())?
    }

    /// One song, then its radio queued after it, as YouTube Music does (items.ts
    /// `playSongWithRadio`).
    pub fn play_with_radio(self: &Arc<Self>, track: Track) {
        self.play(std::slice::from_ref(&track), 0);
        let me = self.clone();
        runtime().spawn(async move {
            if let Ok(radio) = lfly_core::pages::radio(&me.yt, track.clone()).await {
                let more: Vec<TrackRef> = radio.iter().filter(|t| t.id != track.id).take(40).filter_map(track_ref).collect();
                if !more.is_empty() {
                    me.player.edit_queue("add", more, -1, -1);
                }
            }
        });
    }

    /// The synced (or plain) lyrics of [track], from the providers lfly-yt tries in turn.
    pub async fn lyrics(self: Arc<Self>, track: Track) -> Result<Option<lfly_core::pages::Lyrics>, String> {
        runtime().spawn(async move { lfly_core::pages::lyrics(&self.yt, track).await }).await.map_err(|e| e.to_string())?
    }

    /// Thumbs up (or nothing) on YouTube Music for a liked song; nothing when signed out.
    pub fn rate(self: &Arc<Self>, id: String, liked: Option<bool>) {
        let me = self.clone();
        runtime().spawn(async move {
            if let Err(e) = lfly_core::pages::rate(&me.yt, id, liked).await {
                log::warn!("rate: {e}");
            }
        });
    }

    pub fn remove_from_queue(&self, index: usize) {
        self.player.edit_queue("remove", Vec::new(), index as i32, -1);
    }

    pub fn command(&self, action: &str, value: i64) {
        self.player.run(action, value);
    }

    pub async fn fetch(self: Arc<Self>, url: String) -> Option<Vec<u8>> {
        runtime()
            .spawn(async move {
                let r = self.http.get(&url).send().await.ok()?.error_for_status().ok()?;
                r.bytes().await.ok().map(|b| b.to_vec())
            })
            .await
            .ok()
            .flatten()
    }
}
