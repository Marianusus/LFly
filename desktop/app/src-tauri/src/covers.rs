//! Cover images through the core.
//!
//! Google's image servers (`*.googleusercontent.com`) answer the window's WebKit requests with
//! 429 "too many requests" while plain HTTP clients get the image, so the window never loads
//! them itself: it asks for `cover://localhost/<encoded url>` (`http://cover.localhost/…` on
//! Windows) and this fetches the image, keeps it on disk and hands it back. A YouTube video
//! thumbnail that is gone falls back to the video's standard one.

use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use sha2::{Digest, Sha256};
use tauri::http::{Request, Response, StatusCode};
use tauri::UriSchemeResponder;
use tokio::sync::Semaphore;

const MAX_BYTES: usize = 10 * 1024 * 1024;
/// The disk cache is trimmed to `KEEP` when it grows past `LIMIT`.
const LIMIT: u64 = 300 * 1024 * 1024;
const KEEP: u64 = 200 * 1024 * 1024;

pub struct Covers {
    client: reqwest::Client,
    dir: Option<PathBuf>,
    /// A few downloads at a time, so a page full of covers doesn't look like a flood.
    gate: Semaphore,
}

impl Covers {
    pub fn new() -> Arc<Self> {
        let dir = dirs::cache_dir().map(|d| d.join("lfly").join("covers"));
        if let Some(dir) = &dir {
            let _ = std::fs::create_dir_all(dir);
            let dir = dir.clone();
            std::thread::spawn(move || trim(&dir));
        }
        let client = reqwest::Client::builder()
            .timeout(Duration::from_secs(20))
            .user_agent("LFly")
            .build()
            .expect("HTTP client");
        Arc::new(Self { client, dir, gate: Semaphore::new(6) })
    }

    /// Answers one `cover://` request.
    pub fn serve(self: Arc<Self>, request: Request<Vec<u8>>, responder: UriSchemeResponder) {
        let raw = request.uri().path().trim_start_matches('/').to_string();
        tauri::async_runtime::spawn(async move {
            let response = match percent_decode(&raw) {
                Some(url) if url.starts_with("https://") => match self.get(&url).await {
                    Some((bytes, kind)) => Response::builder()
                        .status(StatusCode::OK)
                        .header("Content-Type", kind)
                        .header("Cache-Control", "public, max-age=604800")
                        .header("Access-Control-Allow-Origin", "*")
                        .body(bytes),
                    None => Response::builder().status(StatusCode::NOT_FOUND).body(Vec::new()),
                },
                _ => Response::builder().status(StatusCode::BAD_REQUEST).body(Vec::new()),
            };
            responder.respond(response.expect("response"));
        });
    }

    async fn get(&self, url: &str) -> Option<(Vec<u8>, &'static str)> {
        let file = self.dir.as_ref().map(|d| d.join(hex(&Sha256::digest(url.as_bytes()))));
        if let Some(file) = &file {
            if let Ok(bytes) = tokio::fs::read(file).await {
                if let Some(kind) = sniff(&bytes) {
                    return Some((bytes, kind));
                }
            }
        }
        let bytes = match self.fetch(url).await {
            Some(b) => b,
            None => self.fetch(&video_fallback(url)?).await?,
        };
        let Some(kind) = sniff(&bytes) else {
            log::warn!("cover {url}: not an image");
            return None;
        };
        if let Some(file) = file {
            let _ = tokio::fs::write(file, &bytes).await;
        }
        Some((bytes, kind))
    }

    async fn fetch(&self, url: &str) -> Option<Vec<u8>> {
        let _turn = self.gate.acquire().await.ok()?;
        for attempt in 0..3 {
            let response = match self.client.get(url).send().await {
                Ok(r) => r,
                Err(e) => {
                    log::debug!("cover {url}: {e}");
                    tokio::time::sleep(Duration::from_millis(500)).await;
                    continue;
                }
            };
            let status = response.status();
            if status.is_success() {
                let bytes = response.bytes().await.ok()?;
                return (bytes.len() <= MAX_BYTES).then(|| bytes.to_vec());
            }
            if status == reqwest::StatusCode::TOO_MANY_REQUESTS || status.is_server_error() {
                tokio::time::sleep(Duration::from_millis(800 * (attempt + 1))).await;
                continue;
            }
            log::warn!("cover {url}: {status}");
            return None;
        }
        log::warn!("cover {url}: no answer");
        None
    }
}

/// `https://i.ytimg.com/vi/<id>/<anything>` → that video's `hqdefault.jpg`, which every video has.
fn video_fallback(url: &str) -> Option<String> {
    let rest = url.strip_prefix("https://i.ytimg.com/vi/")?;
    let id = rest.split('/').next().filter(|id| !id.is_empty())?;
    let fallback = format!("https://i.ytimg.com/vi/{id}/hqdefault.jpg");
    (fallback != url).then_some(fallback)
}

fn sniff(bytes: &[u8]) -> Option<&'static str> {
    match bytes {
        [0xFF, 0xD8, 0xFF, ..] => Some("image/jpeg"),
        [0x89, b'P', b'N', b'G', ..] => Some("image/png"),
        [b'G', b'I', b'F', b'8', ..] => Some("image/gif"),
        [b'R', b'I', b'F', b'F', _, _, _, _, b'W', b'E', b'B', b'P', ..] => Some("image/webp"),
        [_, _, _, _, b'f', b't', b'y', b'p', b'a', b'v', b'i', b'f', ..] => Some("image/avif"),
        _ => None,
    }
}

fn percent_decode(s: &str) -> Option<String> {
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' {
            let hex = std::str::from_utf8(bytes.get(i + 1..i + 3)?).ok()?;
            out.push(u8::from_str_radix(hex, 16).ok()?);
            i += 3;
        } else {
            out.push(bytes[i]);
            i += 1;
        }
    }
    String::from_utf8(out).ok()
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// Oldest covers out first once the cache is past its limit.
fn trim(dir: &PathBuf) {
    let Ok(entries) = std::fs::read_dir(dir) else { return };
    let mut files: Vec<(std::time::SystemTime, u64, PathBuf)> = entries
        .flatten()
        .filter_map(|e| {
            let meta = e.metadata().ok()?;
            Some((meta.modified().ok()?, meta.len(), e.path()))
        })
        .collect();
    let mut total: u64 = files.iter().map(|f| f.1).sum();
    if total <= LIMIT {
        return;
    }
    files.sort();
    for (_, len, path) in files {
        if total <= KEEP {
            break;
        }
        if std::fs::remove_file(path).is_ok() {
            total -= len;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decodes_what_the_window_encodes() {
        // encodeURIComponent("https://a.b/x=w60-h60?q=1&r=2")
        let encoded = "https%3A%2F%2Fa.b%2Fx%3Dw60-h60%3Fq%3D1%26r%3D2";
        assert_eq!(percent_decode(encoded).as_deref(), Some("https://a.b/x=w60-h60?q=1&r=2"));
        assert_eq!(percent_decode("bad%2"), None);
    }

    #[test]
    fn falls_back_to_the_video_thumbnail() {
        assert_eq!(
            video_fallback("https://i.ytimg.com/vi/tPvzoqWBwx0/hq720.jpg?sqp=x").as_deref(),
            Some("https://i.ytimg.com/vi/tPvzoqWBwx0/hqdefault.jpg")
        );
        assert_eq!(video_fallback("https://i.ytimg.com/vi/abc/hqdefault.jpg"), None);
        assert_eq!(video_fallback("https://yt3.googleusercontent.com/x=w60-h60"), None);
    }

    #[test]
    fn knows_image_types() {
        assert_eq!(sniff(&[0xFF, 0xD8, 0xFF, 0xE0]), Some("image/jpeg"));
        assert_eq!(sniff(b"RIFF\0\0\0\0WEBPVP8 "), Some("image/webp"));
        assert_eq!(sniff(b"<html>"), None);
    }
}
