//! What the window talks to: YouTube Music, the audio engine and a tokio runtime for both.
//!
//! GPUI has its own executor; the network code (reqwest) needs tokio, so it runs here and the
//! window awaits the join handles (they work from any executor).

use std::sync::{Arc, OnceLock};

use lfly_audio::{Player, Source};
use lfly_yt::{AudioPreference, Track, YtMusic, YtMusicConfig};
use tokio::runtime::Runtime;

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

pub struct Core {
    pub yt: YtMusic,
    pub audio: Player,
    http: reqwest::Client,
}

impl Core {
    pub fn new() -> anyhow::Result<Arc<Self>> {
        let yt = YtMusic::with_config(YtMusicConfig { preference: AudioPreference::AacFirst, ..Default::default() })
            .map_err(|e| anyhow::anyhow!("{e}"))?;
        // Its own cache while the Tauri app lives next to it: two engines must not share one.
        let cache = dirs::cache_dir().ok_or_else(|| anyhow::anyhow!("no cache dir"))?.join("lfly").join("audio-native");
        let audio = Player::new(cache, 2 * 1024 * 1024 * 1024).map_err(|e| anyhow::anyhow!("{e}"))?;
        let http = reqwest::Client::builder().user_agent("Mozilla/5.0").build()?;
        Ok(Arc::new(Self { yt, audio, http }))
    }

    pub async fn search(self: Arc<Self>, query: String) -> Result<Vec<Track>, String> {
        runtime()
            .spawn(async move { self.yt.search_songs(&query, 50).await.map_err(|e| e.to_string()) })
            .await
            .map_err(|e| e.to_string())?
    }

    /// Resolves the full stream (never a preview) and starts it.
    pub async fn play(self: Arc<Self>, track: Track) -> Result<(), String> {
        let me = self.clone();
        runtime()
            .spawn(async move {
                let stream = me.yt.resolve_stream(&track).await.map_err(|e| e.to_string())?;
                let mut source = Source::http(stream.url, &track.id);
                if let (Source::Http { headers, .. }, Some(ua)) = (&mut source, stream.user_agent) {
                    headers.push(("User-Agent".into(), ua));
                }
                me.audio.load(source, 0, true);
                Ok(())
            })
            .await
            .map_err(|e| e.to_string())?
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
