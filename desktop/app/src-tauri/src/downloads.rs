//! Songs kept on this computer, to play without a connection. Each one is the full stream
//! YouTube gives (AAC or Opus, as it comes), saved as one file next to an index of their tracks.
//! The player plays a downloaded song from its file before anything else.

use std::collections::VecDeque;
use std::io::Write;
use std::path::PathBuf;
use std::sync::{Arc, Mutex, OnceLock};

use lfly_audio::http::{HttpSource, ReaderCtl};
use lfly_audio::DiskCache;
use lfly_connect::protocol::TrackRef;
use lfly_yt::YtMusic;
use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Emitter, State};

static DOWNLOADS: OnceLock<Arc<Downloads>> = OnceLock::new();

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Entry {
    track: TrackRef,
    file: String,
    bytes: u64,
    added_at: u64,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct Progress {
    id: String,
    /// 0..1, or -1 when it failed.
    done: f32,
}

pub struct Downloads {
    dir: PathBuf,
    app: AppHandle,
    yt: YtMusic,
    index: Mutex<Vec<Entry>>,
    waiting: Mutex<VecDeque<TrackRef>>,
    working: Mutex<bool>,
}

/// The downloaded file of a song, if it has one.
pub fn file_for(id: &str) -> Option<PathBuf> {
    let d = DOWNLOADS.get()?;
    let index = d.index.lock().unwrap();
    let entry = index.iter().find(|e| e.track.id == id)?;
    let path = d.dir.join(&entry.file);
    path.exists().then_some(path)
}

pub fn start(app: AppHandle, yt: YtMusic) -> Option<Arc<Downloads>> {
    let dir = dirs::data_dir()?.join("lfly").join("downloads");
    std::fs::create_dir_all(&dir).ok()?;
    let index: Vec<Entry> = std::fs::read(dir.join("index.json"))
        .ok()
        .and_then(|b| serde_json::from_slice(&b).ok())
        .unwrap_or_default();
    // A file deleted by hand is not a download any more.
    let index = index.into_iter().filter(|e| dir.join(&e.file).exists()).collect();
    let d = Arc::new(Downloads { dir, app, yt, index: Mutex::new(index), waiting: Mutex::default(), working: Mutex::new(false) });
    let _ = DOWNLOADS.set(d.clone());
    Some(d)
}

impl Downloads {
    fn save_index(&self) {
        let index = self.index.lock().unwrap().clone();
        if let Ok(bytes) = serde_json::to_vec(&index) {
            let tmp = self.dir.join("index.json.tmp");
            if std::fs::write(&tmp, bytes).is_ok() {
                let _ = std::fs::rename(tmp, self.dir.join("index.json"));
            }
        }
        let tracks: Vec<TrackRef> = index.into_iter().map(|e| e.track).collect();
        let _ = self.app.emit("downloads:changed", tracks);
    }

    fn has(&self, id: &str) -> bool {
        self.index.lock().unwrap().iter().any(|e| e.track.id == id)
    }

    fn enqueue(self: &Arc<Self>, tracks: Vec<TrackRef>) {
        {
            let mut waiting = self.waiting.lock().unwrap();
            for t in tracks {
                if !self.has(&t.id) && !waiting.iter().any(|w| w.id == t.id) {
                    let _ = self.app.emit("downloads:progress", Progress { id: t.id.clone(), done: 0.0 });
                    waiting.push_back(t);
                }
            }
        }
        let mut working = self.working.lock().unwrap();
        if *working {
            return;
        }
        *working = true;
        let me = self.clone();
        tauri::async_runtime::spawn(async move {
            loop {
                let next = me.waiting.lock().unwrap().pop_front();
                let Some(track) = next else { break };
                let id = track.id.clone();
                if let Err(e) = me.fetch(track).await {
                    log::warn!("download {id}: {e}");
                    let _ = me.app.emit("downloads:progress", Progress { id, done: -1.0 });
                }
            }
            *me.working.lock().unwrap() = false;
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
        let bytes = tauri::async_runtime::spawn_blocking(move || -> Result<u64, String> {
            // The chunked reader the player uses (it copes with googlevideo's throttling), into a
            // scratch cache, then joined into one file.
            let scratch = me.dir.join(".partial");
            let cache = Arc::new(DiskCache::new(&scratch, u64::MAX).map_err(|e| e.to_string())?);
            let mut headers = Vec::new();
            if let Some(ua) = stream.user_agent {
                headers.push(("User-Agent".to_string(), ua));
            }
            let mut src =
                HttpSource::open(&stream.url, &id, &headers, cache.clone(), ReaderCtl::detached()).map_err(|e| e.to_string())?;
            let len = src.len().ok_or("Lunghezza sconosciuta")?;
            let chunks = cache.chunk_count(len);
            let tmp = me.dir.join(format!("{file_name}.part"));
            let mut out = std::fs::File::create(&tmp).map_err(|e| e.to_string())?;
            for idx in 0..chunks {
                src.fetch_chunk(idx).map_err(|e| e.to_string())?;
                let data = cache.read_chunk(&id, idx).ok_or("Pezzo mancante")?;
                out.write_all(&data).map_err(|e| e.to_string())?;
                let _ = me.app.emit("downloads:progress", Progress { id: id.clone(), done: (idx + 1) as f32 / chunks as f32 });
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
        self.index.lock().unwrap().push(Entry { track, file, bytes, added_at });
        self.save_index();
        Ok(())
    }

    fn remove(&self, id: &str) {
        let removed: Vec<Entry> = {
            let mut index = self.index.lock().unwrap();
            let (gone, kept): (Vec<_>, Vec<_>) = index.drain(..).partition(|e| e.track.id == id);
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

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DownloadList {
    tracks: Vec<TrackRef>,
    bytes: u64,
}

#[tauri::command]
pub fn downloads_list(d: State<'_, Arc<Downloads>>) -> DownloadList {
    let index = d.index.lock().unwrap();
    let mut entries = index.clone();
    entries.sort_by_key(|e| std::cmp::Reverse(e.added_at));
    DownloadList { bytes: entries.iter().map(|e| e.bytes).sum(), tracks: entries.into_iter().map(|e| e.track).collect() }
}

#[tauri::command]
pub fn downloads_add(d: State<'_, Arc<Downloads>>, tracks: Vec<TrackRef>) {
    d.inner().enqueue(tracks);
}

#[tauri::command]
pub fn downloads_remove(d: State<'_, Arc<Downloads>>, ids: Vec<String>) {
    for id in ids {
        d.remove(&id);
    }
}
