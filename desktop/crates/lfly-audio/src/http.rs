//! A seekable `Read` over an HTTP URL that fetches fixed-size Range chunks through the disk cache.
//!
//! Each chunk is one `Range: bytes=a-b` request (1 MiB by default), streamed into memory as it
//! arrives so decoding starts before the whole chunk is in, and written to the cache once
//! complete. Seeking just moves the position: the next read fetches the chunk it lands in. A
//! dropped connection is retried from where it stopped; a 4xx (e.g. an expired URL) fails at once.

use std::io::{self, Read, Seek, SeekFrom};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, OnceLock};
use std::time::Duration;

use crate::cache::DiskCache;

/// Attempts per request before giving up (the first one included).
const ATTEMPTS: u32 = 4;
const READ_PIECE: usize = 64 * 1024;

pub(crate) fn agent() -> &'static ureq::Agent {
    static AGENT: OnceLock<ureq::Agent> = OnceLock::new();
    AGENT.get_or_init(|| {
        ureq::Agent::config_builder()
            .http_status_as_error(false)
            .timeout_connect(Some(Duration::from_secs(10)))
            .timeout_recv_response(Some(Duration::from_secs(15)))
            .timeout_per_read(Some(Duration::from_secs(10)))
            .user_agent("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36")
            .build()
            .new_agent()
    })
}

/// Lets the player abort a blocked read when a newer command (load, seek, stop) comes in.
pub struct ReaderCtl {
    cmd_gen: Arc<AtomicU64>,
    armed: AtomicU64,
    attached: AtomicBool,
    cancelled: AtomicBool,
}

impl ReaderCtl {
    /// A control nothing but [`ReaderCtl::cancel`] can abort.
    pub fn detached() -> Arc<Self> {
        Arc::new(ReaderCtl {
            cmd_gen: Arc::new(AtomicU64::new(0)),
            armed: AtomicU64::new(0),
            attached: AtomicBool::new(false),
            cancelled: AtomicBool::new(false),
        })
    }

    /// Will follow the player's commands once armed (for a track opened ahead of time).
    pub(crate) fn dormant(cmd_gen: Arc<AtomicU64>) -> Arc<Self> {
        let armed = cmd_gen.load(Ordering::SeqCst);
        Arc::new(ReaderCtl {
            cmd_gen,
            armed: AtomicU64::new(armed),
            attached: AtomicBool::new(false),
            cancelled: AtomicBool::new(false),
        })
    }

    /// Follows the player's commands from the current command generation on.
    pub(crate) fn following(cmd_gen: Arc<AtomicU64>) -> Arc<Self> {
        let ctl = Self::dormant(cmd_gen);
        ctl.attached.store(true, Ordering::SeqCst);
        ctl
    }

    /// Follows the player's commands from now on, as of command generation `gen`.
    pub(crate) fn arm(&self, cmd_gen: &Arc<AtomicU64>, gen: u64) {
        if !Arc::ptr_eq(&self.cmd_gen, cmd_gen) {
            // Only reachable for a control built detached; it then simply never follows commands.
            return;
        }
        self.armed.store(gen, Ordering::SeqCst);
        self.attached.store(true, Ordering::SeqCst);
    }

    pub fn cancel(&self) {
        self.cancelled.store(true, Ordering::SeqCst);
    }

    pub fn aborted(&self) -> bool {
        self.cancelled.load(Ordering::SeqCst)
            || (self.attached.load(Ordering::SeqCst)
                && self.cmd_gen.load(Ordering::SeqCst) != self.armed.load(Ordering::SeqCst))
    }
}

pub(crate) fn aborted_error() -> io::Error {
    io::Error::other("aborted")
}

pub struct HttpSource {
    url: String,
    key: String,
    headers: Vec<(String, String)>,
    cache: Arc<DiskCache>,
    ctl: Arc<ReaderCtl>,
    len: Option<u64>,
    pos: u64,
    /// Index of the chunk in `buf`, if any.
    cur: Option<u64>,
    buf: Vec<u8>,
    complete: bool,
    body: Option<ureq::BodyReader<'static>>,
}

impl HttpSource {
    /// Opens the URL; `key` names the track in the cache. Learns the length first (from the cache,
    /// or from the first chunk's response).
    pub fn open(
        url: &str,
        key: &str,
        headers: &[(String, String)],
        cache: Arc<DiskCache>,
        ctl: Arc<ReaderCtl>,
    ) -> io::Result<Self> {
        // The same key may have been cached from another format of the song (WebM vs MP4, both
        // tried by the resolver): googlevideo URLs carry the length (`clen=`), so a mismatch shows.
        if let (Some(clen), Some(cached)) = (url_param(url, "clen").and_then(|v| v.parse::<u64>().ok()), cache.total_len(key)) {
            if clen != cached {
                log::info!("cache entry {key} is another file ({cached} vs {clen} bytes): dropped");
                cache.remove(key);
            }
        }
        cache.pin(key);
        cache.touch(key);
        let mut src = HttpSource {
            url: url.to_string(),
            key: key.to_string(),
            headers: headers.to_vec(),
            len: cache.total_len(key),
            cache,
            ctl,
            pos: 0,
            cur: None,
            buf: Vec::new(),
            complete: false,
            body: None,
        };
        if src.len.is_none() {
            // The first response tells the length (needed to seek); retried like any read.
            src.load_chunk(0)?;
            src.pull()?;
        }
        Ok(src)
    }

    pub fn len(&self) -> Option<u64> {
        self.len
    }

    pub fn is_empty(&self) -> bool {
        self.len == Some(0)
    }

    pub fn key(&self) -> &str {
        &self.key
    }

    /// Downloads chunk `idx` completely (into the cache) unless it is already there.
    pub fn fetch_chunk(&mut self, idx: u64) -> io::Result<()> {
        self.load_chunk(idx)?;
        while !self.complete {
            self.pull()?;
        }
        Ok(())
    }

    fn chunk_start(&self, idx: u64) -> u64 {
        idx * self.cache.chunk_size()
    }

    /// Size chunk `idx` will have, when the length is known.
    fn expected(&self, idx: u64) -> Option<u64> {
        self.len.map(|len| self.cache.chunk_len(len, idx))
    }

    /// Makes chunk `idx` the current one, from the cache when possible.
    fn load_chunk(&mut self, idx: u64) -> io::Result<()> {
        if self.cur == Some(idx) {
            return Ok(());
        }
        self.body = None;
        self.cur = Some(idx);
        self.buf.clear();
        self.complete = false;
        if let Some(data) = self.cache.read_chunk(&self.key, idx) {
            self.buf = data;
            self.complete = true;
        } else if self.expected(idx) == Some(0) {
            self.complete = true;
        }
        Ok(())
    }

    /// Starts a Range request for the rest of the current chunk.
    fn request(&mut self) -> io::Result<()> {
        let idx = self.cur.unwrap_or(0);
        let from = self.chunk_start(idx) + self.buf.len() as u64;
        let to = self.chunk_start(idx + 1) - 1;
        let to = match self.len {
            Some(len) => to.min(len.saturating_sub(1)),
            None => to,
        };
        let mut req = agent().get(&self.url).header("Range", format!("bytes={from}-{to}"));
        for (k, v) in &self.headers {
            req = req.header(k.as_str(), v.as_str());
        }
        let resp = req.call().map_err(|e| io::Error::other(format!("request failed: {e}")))?;
        let status = resp.status().as_u16();
        match status {
            206 => {
                let range = resp.headers().get("content-range").and_then(|v| v.to_str().ok());
                if let Some(total) = range.and_then(parse_total) {
                    self.set_len(total);
                }
                self.body = Some(resp.into_body().into_reader());
            }
            200 => {
                // The server ignored the Range: the body is the whole file.
                let total = resp
                    .headers()
                    .get("content-length")
                    .and_then(|v| v.to_str().ok())
                    .and_then(|v| v.parse::<u64>().ok());
                if let Some(total) = total {
                    self.set_len(total);
                }
                let mut body = resp.into_body().into_reader();
                if from > 0 {
                    let skipped = io::copy(&mut (&mut body).take(from), &mut io::sink())?;
                    if skipped < from {
                        return Err(io::Error::other("HTTP 200 body shorter than the requested offset"));
                    }
                }
                self.body = Some(body);
            }
            416 if self.len.is_some() => {
                // Asked past the end (length changed?): nothing more to read.
                self.complete = true;
            }
            _ => {
                let kind = if is_permanent(status) { io::ErrorKind::PermissionDenied } else { io::ErrorKind::Other };
                return Err(io::Error::new(kind, format!("HTTP {status}")));
            }
        }
        Ok(())
    }

    fn set_len(&mut self, total: u64) {
        if self.len != Some(total) {
            if self.len.is_some() {
                // The server's file is not the one in the cache: start the entry over.
                log::warn!("{}: length changed from {:?} to {total}, cache entry dropped", self.key, self.len);
                self.cache.remove(&self.key);
            }
            self.len = Some(total);
            let _ = self.cache.set_total_len(&self.key, total);
        }
    }

    /// Reads more of the current chunk from the network, retrying dropped connections.
    fn pull(&mut self) -> io::Result<()> {
        let idx = self.cur.unwrap_or(0);
        let mut failures = 0u32;
        loop {
            if self.ctl.aborted() {
                self.body = None;
                return Err(aborted_error());
            }
            if self.complete {
                return Ok(());
            }
            let result = match self.body.as_mut() {
                None => self.request().map(|_| None),
                Some(body) => {
                    let old = self.buf.len();
                    self.buf.resize(old + READ_PIECE, 0);
                    let r = body.read(&mut self.buf[old..]);
                    let n = *r.as_ref().unwrap_or(&0);
                    self.buf.truncate(old + n);
                    r.map(Some)
                }
            };
            match result {
                Ok(None) => continue,
                Ok(Some(0)) => {
                    self.body = None;
                    match self.expected(idx) {
                        Some(want) if (self.buf.len() as u64) < want => {
                            // The server closed early: ask for the rest.
                            failures += 1;
                            if failures >= ATTEMPTS {
                                return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "connection closed early"));
                            }
                        }
                        _ => {
                            self.finish_chunk(idx);
                            return Ok(());
                        }
                    }
                }
                Ok(Some(_)) => {
                    if let Some(want) = self.expected(idx) {
                        if self.buf.len() as u64 >= want {
                            self.buf.truncate(want as usize);
                            self.body = None;
                            self.finish_chunk(idx);
                        }
                    }
                    return Ok(());
                }
                Err(e) => {
                    self.body = None;
                    if e.kind() == io::ErrorKind::PermissionDenied {
                        return Err(e);
                    }
                    failures += 1;
                    log::warn!("http chunk {idx} of {}: {e} (attempt {failures})", self.key);
                    if failures >= ATTEMPTS {
                        return Err(e);
                    }
                    self.backoff(failures)?;
                }
            }
        }
    }

    fn backoff(&self, failures: u32) -> io::Result<()> {
        let total = Duration::from_millis(250 << failures.min(4));
        let step = Duration::from_millis(25);
        let mut waited = Duration::ZERO;
        while waited < total {
            if self.ctl.aborted() {
                return Err(aborted_error());
            }
            std::thread::sleep(step);
            waited += step;
        }
        Ok(())
    }

    fn finish_chunk(&mut self, idx: u64) {
        self.complete = true;
        if self.len.is_none() {
            // No length anywhere: the end of this body is the end of the file.
            let total = self.chunk_start(idx) + self.buf.len() as u64;
            self.set_len(total);
        }
        if let Err(e) = self.cache.write_chunk(&self.key, idx, &self.buf) {
            log::warn!("cache write for {} failed: {e}", self.key);
        }
    }
}

impl Drop for HttpSource {
    fn drop(&mut self) {
        self.cache.unpin(&self.key);
    }
}

impl Read for HttpSource {
    fn read(&mut self, out: &mut [u8]) -> io::Result<usize> {
        if out.is_empty() {
            return Ok(0);
        }
        if let Some(len) = self.len {
            if self.pos >= len {
                return Ok(0);
            }
        }
        let idx = self.pos / self.cache.chunk_size();
        self.load_chunk(idx)?;
        let off = (self.pos - self.chunk_start(idx)) as usize;
        while off >= self.buf.len() {
            if self.complete {
                return Ok(0);
            }
            self.pull()?;
        }
        let n = out.len().min(self.buf.len() - off);
        out[..n].copy_from_slice(&self.buf[off..off + n]);
        self.pos += n as u64;
        Ok(n)
    }
}

impl Seek for HttpSource {
    fn seek(&mut self, to: SeekFrom) -> io::Result<u64> {
        let target = match to {
            SeekFrom::Start(p) => p as i128,
            SeekFrom::Current(d) => self.pos as i128 + d as i128,
            SeekFrom::End(d) => match self.len {
                Some(len) => len as i128 + d as i128,
                None => return Err(io::Error::new(io::ErrorKind::Unsupported, "length unknown")),
            },
        };
        if target < 0 {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "seek before start"));
        }
        self.pos = target as u64;
        Ok(self.pos)
    }
}

impl symphonia::core::io::MediaSource for HttpSource {
    fn is_seekable(&self) -> bool {
        self.len.is_some()
    }

    fn byte_len(&self) -> Option<u64> {
        self.len
    }
}

/// Downloads the first `bytes` of a track into the cache (for the next track in the queue).
pub fn prefetch(
    url: &str,
    key: &str,
    headers: &[(String, String)],
    cache: Arc<DiskCache>,
    bytes: u64,
    ctl: Arc<ReaderCtl>,
) -> io::Result<()> {
    let mut src = HttpSource::open(url, key, headers, cache.clone(), ctl)?;
    let chunks = bytes.div_ceil(cache.chunk_size()).max(1);
    let chunks = match src.len() {
        Some(len) => chunks.min(cache.chunk_count(len)),
        None => chunks,
    };
    for idx in 0..chunks {
        src.fetch_chunk(idx)?;
    }
    Ok(())
}

/// Value of a query parameter of `url`.
fn url_param<'a>(url: &'a str, name: &str) -> Option<&'a str> {
    let query = url.split_once('?')?.1;
    query.split('&').find_map(|kv| {
        let (k, v) = kv.split_once('=')?;
        (k == name).then_some(v)
    })
}

/// Status codes that will not get better by retrying the same URL.
fn is_permanent(status: u16) -> bool {
    (400..500).contains(&status) && status != 408 && status != 429
}

/// Total length from `Content-Range: bytes a-b/total`.
fn parse_total(range: &str) -> Option<u64> {
    range.rsplit('/').next()?.trim().parse().ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn content_range_totals() {
        assert_eq!(parse_total("bytes 0-1023/4096"), Some(4096));
        assert_eq!(parse_total("bytes 0-1023/*"), None);
        assert!(is_permanent(403));
        assert!(!is_permanent(429));
        assert!(!is_permanent(503));
        let url = "https://r1.googlevideo.com/videoplayback?expire=1&itag=251&clen=4155462&dur=248.661";
        assert_eq!(url_param(url, "clen"), Some("4155462"));
        assert_eq!(url_param(url, "itag"), Some("251"));
        assert_eq!(url_param(url, "len"), None);
    }
}
