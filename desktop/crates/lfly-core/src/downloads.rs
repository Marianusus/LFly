//! Songs kept on this computer, to play without a connection. Each one is the full stream
//! YouTube gives (AAC or Opus, as it comes), saved as one file next to an index of their tracks.
//! The player plays a downloaded song from its file before anything else ([`file_for`]).
//!
//! The window hears what happens through the callback given to [`start`].

use std::collections::VecDeque;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, OnceLock};

use lfly_audio::http::{HttpSource, ReaderCtl};
use lfly_audio::DiskCache;
use lfly_connect::protocol::TrackRef;
use lfly_yt::YtMusic;
use serde::{Deserialize, Serialize};

static DOWNLOADS: OnceLock<Arc<Downloads>> = OnceLock::new();

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Entry {
    track: TrackRef,
    file: String,
    bytes: u64,
    added_at: u64,
}

/// What the window hears.
#[derive(Clone, Debug)]
pub enum DownloadEvent {
    /// A song on its way: 0..1, or -1 when it failed.
    Progress { id: String, done: f32 },
    /// The downloaded songs changed (one landed, some were removed).
    Changed,
}

type Sink = Box<dyn Fn(DownloadEvent) + Send + Sync>;

pub struct Downloads {
    dir: PathBuf,
    rt: tokio::runtime::Handle,
    yt: YtMusic,
    sink: Sink,
    index: Mutex<Vec<Entry>>,
    waiting: Mutex<VecDeque<TrackRef>>,
    working: Mutex<bool>,
}

/// The downloaded songs, newest first, and the room they take.
#[derive(Serialize, Clone, Default)]
#[serde(rename_all = "camelCase")]
pub struct DownloadList {
    pub tracks: Vec<TrackRef>,
    pub bytes: u64,
}

/// Where the downloads live (LFLY_DOWNLOADS_DIR in tests).
pub fn dir() -> Option<PathBuf> {
    if let Some(d) = std::env::var_os("LFLY_DOWNLOADS_DIR") {
        return Some(PathBuf::from(d));
    }
    Some(dirs::data_dir()?.join("lfly").join("downloads"))
}

/// The downloaded file of a song, if it has one.
pub fn file_for(id: &str) -> Option<PathBuf> {
    DOWNLOADS.get()?.file_of(id)
}

/// Starts the downloads in their usual folder; the first call wins, as [`file_for`] reads it.
pub fn start(yt: YtMusic, rt: tokio::runtime::Handle, sink: impl Fn(DownloadEvent) + Send + Sync + 'static) -> Option<Arc<Downloads>> {
    let d = Downloads::open(&dir()?, yt, rt, Box::new(sink))?;
    let _ = DOWNLOADS.set(d.clone());
    Some(d)
}

impl Downloads {
    fn open(dir: &Path, yt: YtMusic, rt: tokio::runtime::Handle, sink: Sink) -> Option<Arc<Self>> {
        std::fs::create_dir_all(dir).ok()?;
        let index: Vec<Entry> = std::fs::read(dir.join("index.json")).ok().and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default();
        // A file deleted by hand is not a download any more.
        let index = index.into_iter().filter(|e| dir.join(&e.file).exists()).collect();
        Some(Arc::new(Downloads {
            dir: dir.to_path_buf(),
            rt,
            yt,
            sink,
            index: Mutex::new(index),
            waiting: Mutex::default(),
            working: Mutex::new(false),
        }))
    }

    pub fn dir(&self) -> &Path {
        &self.dir
    }

    fn file_of(&self, id: &str) -> Option<PathBuf> {
        let index = self.index.lock().unwrap_or_else(|e| e.into_inner());
        let entry = index.iter().find(|e| e.track.id == id)?;
        let path = self.dir.join(&entry.file);
        path.exists().then_some(path)
    }

    pub fn list(&self) -> DownloadList {
        let mut entries = self.index.lock().unwrap_or_else(|e| e.into_inner()).clone();
        entries.sort_by_key(|e| std::cmp::Reverse(e.added_at));
        DownloadList { bytes: entries.iter().map(|e| e.bytes).sum(), tracks: entries.into_iter().map(|e| e.track).collect() }
    }

    fn save_index(&self) {
        let index = self.index.lock().unwrap_or_else(|e| e.into_inner()).clone();
        if let Ok(bytes) = serde_json::to_vec(&index) {
            let tmp = self.dir.join("index.json.tmp");
            if std::fs::write(&tmp, bytes).is_ok() {
                let _ = std::fs::rename(tmp, self.dir.join("index.json"));
            }
        }
        (self.sink)(DownloadEvent::Changed);
    }

    pub fn has(&self, id: &str) -> bool {
        self.index.lock().unwrap_or_else(|e| e.into_inner()).iter().any(|e| e.track.id == id)
    }

    /// Queues [tracks] (those not already here or on their way), one download at a time.
    pub fn add(self: &Arc<Self>, tracks: Vec<TrackRef>) {
        {
            let mut waiting = self.waiting.lock().unwrap_or_else(|e| e.into_inner());
            for t in tracks {
                if !self.has(&t.id) && !waiting.iter().any(|w| w.id == t.id) {
                    (self.sink)(DownloadEvent::Progress { id: t.id.clone(), done: 0.0 });
                    waiting.push_back(t);
                }
            }
        }
        let mut working = self.working.lock().unwrap_or_else(|e| e.into_inner());
        if *working {
            return;
        }
        *working = true;
        let me = self.clone();
        self.rt.spawn(async move {
            loop {
                let next = me.waiting.lock().unwrap_or_else(|e| e.into_inner()).pop_front();
                let Some(track) = next else { break };
                let id = track.id.clone();
                if let Err(e) = me.fetch(track).await {
                    log::warn!("download {id}: {e}");
                    (me.sink)(DownloadEvent::Progress { id, done: -1.0 });
                }
            }
            *me.working.lock().unwrap_or_else(|e| e.into_inner()) = false;
        });
    }

    async fn fetch(self: &Arc<Self>, track: TrackRef) -> Result<(), String> {
        let yt_track: lfly_yt::Track =
            serde_json::from_value(serde_json::to_value(&track).map_err(|e| e.to_string())?).map_err(|e| e.to_string())?;
        let stream = self.yt.resolve_stream(&yt_track).await.map_err(|e| e.to_string())?;
        let ext = if stream.mime.contains("webm") { "webm" } else if stream.mime.contains("mp4") { "m4a" } else { "audio" };
        let file = format!("{}.{ext}", safe_name(&track.id));
        let me = self.clone();
        let id = track.id.clone();
        let file_name = file.clone();
        let bytes = tokio::task::spawn_blocking(move || -> Result<u64, String> {
            // The chunked reader the player uses (it copes with googlevideo's throttling), into a
            // scratch cache, then joined into one file.
            let scratch = me.dir.join(".partial");
            let cache = Arc::new(DiskCache::new(&scratch, u64::MAX).map_err(|e| e.to_string())?);
            let mut headers = Vec::new();
            if let Some(ua) = stream.user_agent {
                headers.push(("User-Agent".to_string(), ua));
            }
            let mut src = HttpSource::open(&stream.url, &id, &headers, cache.clone(), ReaderCtl::detached()).map_err(|e| e.to_string())?;
            let len = src.len().ok_or("Lunghezza sconosciuta")?;
            let chunks = cache.chunk_count(len);
            let tmp = me.dir.join(format!("{file_name}.part"));
            let mut out = std::fs::File::create(&tmp).map_err(|e| e.to_string())?;
            for idx in 0..chunks {
                src.fetch_chunk(idx).map_err(|e| e.to_string())?;
                let data = cache.read_chunk(&id, idx).ok_or("Pezzo mancante")?;
                out.write_all(&data).map_err(|e| e.to_string())?;
                (me.sink)(DownloadEvent::Progress { id: id.clone(), done: (idx + 1) as f32 / chunks as f32 });
            }
            out.flush().map_err(|e| e.to_string())?;
            drop(src);
            cache.remove(&id);
            std::fs::rename(&tmp, me.dir.join(&file_name)).map_err(|e| e.to_string())?;
            Ok(len)
        })
        .await
        .map_err(|e| e.to_string())??;
        let added_at = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_secs());
        self.index.lock().unwrap_or_else(|e| e.into_inner()).push(Entry { track, file, bytes, added_at });
        self.save_index();
        Ok(())
    }

    /// Deletes these songs' files.
    pub fn remove(&self, ids: &[String]) {
        let removed: Vec<Entry> = {
            let mut index = self.index.lock().unwrap_or_else(|e| e.into_inner());
            let (gone, kept): (Vec<_>, Vec<_>) = index.drain(..).partition(|e| ids.contains(&e.track.id));
            *index = kept;
            gone
        };
        for e in removed {
            let _ = std::fs::remove_file(self.dir.join(e.file));
        }
        self.save_index();
    }
}

fn safe_name(id: &str) -> String {
    id.chars().map(|c| if c.is_ascii_alphanumeric() || c == '-' || c == '_' { c } else { '_' }).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn names_stay_inside_the_folder() {
        assert_eq!(safe_name("abc-_9"), "abc-_9");
        assert_eq!(safe_name("../x/y"), "___x_y");
    }

    #[test]
    fn a_file_deleted_by_hand_is_not_a_download() {
        let dir = std::env::temp_dir().join(format!("lfly-dl-test-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let t = |id: &str| serde_json::json!({ "id": id, "title": id, "artist": "a", "durationMs": 1000 });
        let index = serde_json::json!([
            { "track": t("kept"), "file": "kept.m4a", "bytes": 10, "addedAt": 2 },
            { "track": t("gone"), "file": "gone.m4a", "bytes": 20, "addedAt": 1 },
        ]);
        std::fs::write(dir.join("index.json"), index.to_string()).unwrap();
        std::fs::write(dir.join("kept.m4a"), b"x").unwrap();
        let rt = tokio::runtime::Builder::new_current_thread().build().unwrap();
        let yt = YtMusic::with_config(Default::default()).unwrap();
        let d = Downloads::open(&dir, yt, rt.handle().clone(), Box::new(|_| {})).unwrap();
        let list = d.list();
        assert_eq!(list.tracks.len(), 1);
        assert_eq!(list.bytes, 10);
        assert!(d.has("kept") && !d.has("gone"));
        d.remove(&["kept".to_string()]);
        assert!(!dir.join("kept.m4a").exists());
        assert!(d.list().tracks.is_empty());
        let _ = std::fs::remove_dir_all(&dir);
    }
}
