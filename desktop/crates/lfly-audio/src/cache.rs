//! Disk cache for HTTP sources: fixed-size chunks per track, LRU eviction by total size.
//!
//! Layout: `<dir>/<escaped key>/meta` holds `"<total length> <chunk size>"`, its modification
//! time is the entry's last use; `<dir>/<escaped key>/<index>.chunk` holds one complete chunk.
//! Chunks are written to a temporary file and renamed, so a reader never sees half a chunk.

use std::collections::HashMap;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::SystemTime;

/// Default chunk size: one Range request per MiB, like YouTube's own clients.
pub const DEFAULT_CHUNK_SIZE: u64 = 1 << 20;

pub struct DiskCache {
    dir: PathBuf,
    max_bytes: u64,
    chunk_size: u64,
    /// Keys in use by an open source: never evicted while pinned.
    pins: Mutex<HashMap<String, usize>>,
    /// Serializes eviction against writes.
    lock: Mutex<()>,
}

impl DiskCache {
    pub fn new(dir: impl Into<PathBuf>, max_bytes: u64) -> io::Result<Self> {
        Self::with_chunk_size(dir, max_bytes, DEFAULT_CHUNK_SIZE)
    }

    pub fn with_chunk_size(dir: impl Into<PathBuf>, max_bytes: u64, chunk_size: u64) -> io::Result<Self> {
        let dir = dir.into();
        fs::create_dir_all(&dir)?;
        Ok(DiskCache {
            dir,
            max_bytes,
            chunk_size: chunk_size.max(4096),
            pins: Mutex::new(HashMap::new()),
            lock: Mutex::new(()),
        })
    }

    pub fn dir(&self) -> &Path {
        &self.dir
    }

    pub fn chunk_size(&self) -> u64 {
        self.chunk_size
    }

    pub fn max_bytes(&self) -> u64 {
        self.max_bytes
    }

    fn entry(&self, key: &str) -> PathBuf {
        self.dir.join(escape(key))
    }

    /// Total length of the track in bytes, once a response told it.
    pub fn total_len(&self, key: &str) -> Option<u64> {
        let text = fs::read_to_string(self.entry(key).join("meta")).ok()?;
        let mut parts = text.split_whitespace();
        let len = parts.next()?.parse().ok()?;
        let chunk: u64 = parts.next()?.parse().ok()?;
        if chunk != self.chunk_size {
            // Written with another chunk size: useless, start over.
            let _ = fs::remove_dir_all(self.entry(key));
            return None;
        }
        Some(len)
    }

    pub fn set_total_len(&self, key: &str, len: u64) -> io::Result<()> {
        let entry = self.entry(key);
        fs::create_dir_all(&entry)?;
        fs::write(entry.join("meta"), format!("{len} {}", self.chunk_size))
    }

    /// Expected size of chunk `idx` for a track of `len` bytes.
    pub fn chunk_len(&self, len: u64, idx: u64) -> u64 {
        let start = idx * self.chunk_size;
        len.saturating_sub(start).min(self.chunk_size)
    }

    pub fn chunk_count(&self, len: u64) -> u64 {
        len.div_ceil(self.chunk_size)
    }

    fn chunk_path(&self, key: &str, idx: u64) -> PathBuf {
        self.entry(key).join(format!("{idx}.chunk"))
    }

    pub fn has_chunk(&self, key: &str, idx: u64) -> bool {
        let Some(len) = self.total_len(key) else { return false };
        fs::metadata(self.chunk_path(key, idx)).map(|m| m.len() == self.chunk_len(len, idx)).unwrap_or(false)
    }

    /// A complete chunk, or `None` when it is not cached (or damaged).
    pub fn read_chunk(&self, key: &str, idx: u64) -> Option<Vec<u8>> {
        let len = self.total_len(key)?;
        let data = fs::read(self.chunk_path(key, idx)).ok()?;
        if data.len() as u64 != self.chunk_len(len, idx) {
            let _ = fs::remove_file(self.chunk_path(key, idx));
            return None;
        }
        Some(data)
    }

    pub fn write_chunk(&self, key: &str, idx: u64, data: &[u8]) -> io::Result<()> {
        {
            let _guard = self.lock.lock().unwrap_or_else(|e| e.into_inner());
            let entry = self.entry(key);
            fs::create_dir_all(&entry)?;
            let tmp = entry.join(format!("{idx}.chunk.{}.tmp", std::process::id()));
            fs::write(&tmp, data)?;
            fs::rename(&tmp, self.chunk_path(key, idx))?;
        }
        self.touch(key);
        self.evict();
        Ok(())
    }

    /// Every chunk of the track is on disk: it plays offline.
    pub fn is_complete(&self, key: &str) -> bool {
        match self.total_len(key) {
            Some(len) => (0..self.chunk_count(len)).all(|i| self.has_chunk(key, i)),
            None => false,
        }
    }

    /// Marks the entry as just used.
    pub fn touch(&self, key: &str) {
        let meta = self.entry(key).join("meta");
        if let Ok(f) = fs::File::options().append(true).open(&meta) {
            let _ = f.set_modified(SystemTime::now());
        }
    }

    pub fn pin(&self, key: &str) {
        *self.pins.lock().unwrap_or_else(|e| e.into_inner()).entry(key.to_string()).or_insert(0) += 1;
    }

    pub fn unpin(&self, key: &str) {
        let mut pins = self.pins.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(n) = pins.get_mut(key) {
            *n -= 1;
            if *n == 0 {
                pins.remove(key);
            }
        }
    }

    pub fn remove(&self, key: &str) {
        let _guard = self.lock.lock().unwrap_or_else(|e| e.into_inner());
        let _ = fs::remove_dir_all(self.entry(key));
    }

    /// Bytes used by all entries.
    pub fn size(&self) -> u64 {
        self.entries().iter().map(|e| e.bytes).sum()
    }

    fn entries(&self) -> Vec<Entry> {
        let Ok(dirs) = fs::read_dir(&self.dir) else { return Vec::new() };
        dirs.flatten()
            .filter(|d| d.file_type().map(|t| t.is_dir()).unwrap_or(false))
            .map(|d| {
                let path = d.path();
                let bytes = fs::read_dir(&path)
                    .map(|files| files.flatten().filter_map(|f| f.metadata().ok()).map(|m| m.len()).sum())
                    .unwrap_or(0);
                let used = fs::metadata(path.join("meta"))
                    .or_else(|_| fs::metadata(&path))
                    .and_then(|m| m.modified())
                    .unwrap_or(SystemTime::UNIX_EPOCH);
                let key = d.file_name().to_str().map(unescape).unwrap_or_default();
                Entry { path, key, bytes, used }
            })
            .collect()
    }

    /// Deletes the least recently used entries until the cache fits in `max_bytes`.
    pub fn evict(&self) {
        let _guard = self.lock.lock().unwrap_or_else(|e| e.into_inner());
        let mut entries = self.entries();
        let mut total: u64 = entries.iter().map(|e| e.bytes).sum();
        if total <= self.max_bytes {
            return;
        }
        entries.sort_by_key(|e| e.used);
        let pins = self.pins.lock().unwrap_or_else(|e| e.into_inner()).clone();
        for e in entries {
            if total <= self.max_bytes {
                break;
            }
            if pins.contains_key(&e.key) {
                continue;
            }
            if fs::remove_dir_all(&e.path).is_ok() {
                total = total.saturating_sub(e.bytes);
            }
        }
    }
}

struct Entry {
    path: PathBuf,
    key: String,
    bytes: u64,
    used: SystemTime,
}

/// Turns any key into a safe directory name: `[A-Za-z0-9_-]` as is, everything else as `.XX`.
fn escape(key: &str) -> String {
    let mut out = String::with_capacity(key.len());
    for b in key.bytes() {
        if b.is_ascii_alphanumeric() || b == b'_' || b == b'-' {
            out.push(b as char);
        } else {
            out.push_str(&format!(".{b:02x}"));
        }
    }
    if out.is_empty() {
        out.push('.');
    }
    out
}

fn unescape(name: &str) -> String {
    let bytes = name.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'.' && i + 3 <= bytes.len() {
            let hex = std::str::from_utf8(&bytes[i + 1..i + 3]).ok();
            if let Some(b) = hex.and_then(|h| u8::from_str_radix(h, 16).ok()) {
                out.push(b);
                i += 3;
                continue;
            }
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keys_round_trip() {
        for key in ["abc", "yt:dQw4w9WgXcQ", "a/b c.é", ""] {
            if key.is_empty() {
                continue;
            }
            assert_eq!(unescape(&escape(key)), key);
        }
    }
}
