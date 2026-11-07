//! Audio format selection for `/player` responses and the fallback sources
//! (Invidious, Piped). Pure functions; the requests live in `client.rs`.

use crate::json::{self, arr, obj, string, Obj};
use crate::model::StreamSource;
use regex::Regex;
use serde_json::Value;
use std::sync::LazyLock;

static DUR_PARAM_RE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"[?&]dur=(\d+(?:\.\d+)?)").unwrap());
static EXPIRE_RE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"[?&]expire=(\d+)").unwrap());
static CODECS_RE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r#"codecs="?([^";]+)"#).unwrap());

/// Which codec to try first when both are offered.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum AudioPreference {
    /// Opus in WebM (itag 251) first, then AAC (itag 140). Default.
    #[default]
    OpusFirst,
    /// AAC in MP4 (itag 140) first, then Opus (itag 251), for decoders without Opus.
    AacFirst,
}

/// True for a 30 second preview url (`dur=` of 30 s or less).
pub fn is_preview_url(url: &str) -> bool {
    DUR_PARAM_RE
        .captures(url)
        .and_then(|c| c[1].parse::<f64>().ok())
        .is_some_and(|d| d <= 30.0)
}

/// Unix seconds from the `expire=` parameter of a googlevideo url.
pub fn expires_at(url: &str) -> Option<u64> {
    EXPIRE_RE.captures(url)?[1].parse().ok()
}

/// Codec named in a mime type: `audio/webm; codecs="opus"` gives `opus`.
pub fn codecs_of(mime: &str) -> Option<String> {
    CODECS_RE.captures(mime).map(|c| c[1].trim().to_string())
}

fn is_opus(s: &StreamSource) -> bool {
    s.mime.to_lowercase().contains("webm") || s.codecs.as_deref().is_some_and(|c| c.contains("opus"))
}

fn is_aac(s: &StreamSource) -> bool {
    s.mime.to_lowercase().starts_with("audio/mp4")
}

/// Ranks candidates: the preferred itag (251 or 140), then the other one, then
/// the preferred codec family by bitrate, then anything by bitrate.
fn rank(s: &StreamSource, pref: AudioPreference) -> (u8, u8, u32) {
    let (first, second) = match pref {
        AudioPreference::OpusFirst => (251, 140),
        AudioPreference::AacFirst => (140, 251),
    };
    let itag_rank = match s.itag {
        Some(i) if i == first => 0,
        Some(i) if i == second => 1,
        _ => 2,
    };
    let family = match pref {
        AudioPreference::OpusFirst if is_opus(s) => 0,
        AudioPreference::AacFirst if is_aac(s) => 0,
        _ => 1,
    };
    (itag_rank, family, u32::MAX - s.bitrate)
}

/// One `streamingData` format as a candidate, or `None` when it is not a
/// direct, full-length audio stream (video, ciphered, preview, no url).
fn audio_format(format: &Obj) -> Option<StreamSource> {
    let mime = string(format, "mimeType")?;
    if !mime.to_lowercase().starts_with("audio") {
        return None;
    }
    // Ciphered formats carry no usable url.
    if format.contains_key("signatureCipher") || format.contains_key("cipher") {
        return None;
    }
    let url = string(format, "url").filter(|u| !u.trim().is_empty())?;
    if is_preview_url(&url) {
        return None;
    }
    let approx = json::long(format, "approxDurationMs");
    if approx.is_some_and(|d| (1..=30_000).contains(&d)) {
        return None;
    }
    Some(StreamSource {
        codecs: codecs_of(&mime),
        bitrate: json::long(format, "bitrate").unwrap_or(0).max(0) as u32,
        itag: json::long(format, "itag").map(|i| i as u32),
        expires_at: expires_at(&url),
        content_length: json::long(format, "contentLength").map(|c| c as u64),
        approx_duration_ms: approx.map(|a| a as u64),
        url,
        mime,
        ..StreamSource::default()
    })
}

/// Best full-length audio format of a `/player` response:
/// `adaptiveFormats` first (then `formats`), ciphered and preview streams skipped.
pub fn select_best_audio_format(root: &Value, pref: AudioPreference) -> Option<StreamSource> {
    let streaming = obj(root.as_object()?, "streamingData")?;
    let mut formats: Vec<&Obj> = arr(streaming, "adaptiveFormats").into_iter().flatten().filter_map(Value::as_object).collect();
    if formats.is_empty() {
        formats = arr(streaming, "formats").into_iter().flatten().filter_map(Value::as_object).collect();
    }
    formats.into_iter().filter_map(audio_format).min_by_key(|s| rank(s, pref))
}

/// `playabilityStatus.status` of a `/player` response ("OK", "LOGIN_REQUIRED", ...).
pub fn playability_status(root: &Value) -> Option<String> {
    string(obj(root.as_object()?, "playabilityStatus")?, "status")
}

/// Best audio format of an Invidious `/api/v1/videos/<id>` response, as
/// `(itag, mime, bitrate)`. Used to build the instance's proxy url.
pub fn invidious_best_format(root: &Value, pref: AudioPreference) -> Option<(u32, String, u32)> {
    let o = root.as_object()?;
    let mut list: Vec<StreamSource> = Vec::new();
    for key in ["adaptiveFormats", "formatStreams"] {
        for f in arr(o, key).into_iter().flatten().filter_map(Value::as_object) {
            let mime = string(f, "type").unwrap_or_default();
            if !mime.to_lowercase().contains("audio") {
                continue;
            }
            let Some(itag) = json::long(f, "itag") else { continue };
            list.push(StreamSource {
                codecs: codecs_of(&mime),
                mime,
                bitrate: json::long(f, "bitrate").unwrap_or(0).max(0) as u32,
                itag: Some(itag as u32),
                ..StreamSource::default()
            });
        }
    }
    let best = list.into_iter().min_by_key(|s| rank(s, pref))?;
    Some((best.itag?, best.mime, best.bitrate))
}

/// Best audio stream of a Piped `/streams/<id>` response.
pub fn piped_best_stream(root: &Value, pref: AudioPreference) -> Option<StreamSource> {
    let o = root.as_object()?;
    let mut list: Vec<StreamSource> = Vec::new();
    for s in arr(o, "audioStreams").into_iter().flatten().filter_map(Value::as_object) {
        let mime = string(s, "mimeType").unwrap_or_default();
        let format = string(s, "format").unwrap_or_default().to_lowercase();
        if !(mime.to_lowercase().contains("audio") || format.contains("m4a") || format.contains("opus")) {
            continue;
        }
        let Some(url) = string(s, "url").filter(|u| !u.is_empty()) else { continue };
        if is_preview_url(&url) {
            continue;
        }
        list.push(StreamSource {
            codecs: string(s, "codec").or_else(|| codecs_of(&mime)),
            mime: if mime.is_empty() { "audio/mp4".to_string() } else { mime },
            bitrate: json::long(s, "bitrate").unwrap_or(0).max(0) as u32,
            itag: json::long(s, "itag").map(|i| i as u32),
            expires_at: expires_at(&url),
            content_length: json::long(s, "contentLength").filter(|c| *c > 0).map(|c| c as u64),
            url,
            ..StreamSource::default()
        });
    }
    list.into_iter().min_by_key(|s| rank(s, pref))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn preview_urls_are_detected() {
        assert!(is_preview_url("https://x/videoplayback?a=1&dur=30.000&b=2"));
        assert!(is_preview_url("https://x/videoplayback?dur=29.5"));
        assert!(!is_preview_url("https://x/videoplayback?dur=248.661"));
        assert!(!is_preview_url("https://x/videoplayback?a=1"));
    }

    #[test]
    fn skips_ciphered_and_short_formats() {
        let root: Value = serde_json::json!({
            "streamingData": {"adaptiveFormats": [
                {"itag": 251, "mimeType": "audio/webm; codecs=\"opus\"", "bitrate": 140000, "signatureCipher": "s=..."},
                {"itag": 140, "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 130000, "url": "https://a/videoplayback?dur=30.0"},
                {"itag": 250, "mimeType": "audio/webm; codecs=\"opus\"", "bitrate": 70000, "url": "https://a/videoplayback?x=1", "approxDurationMs": "29000"},
                {"itag": 249, "mimeType": "audio/webm; codecs=\"opus\"", "bitrate": 50000, "url": "https://a/videoplayback?expire=1700000000&dur=200.1", "approxDurationMs": "200100"},
                {"itag": 137, "mimeType": "video/mp4", "bitrate": 900000, "url": "https://a/v"}
            ]}
        });
        let best = select_best_audio_format(&root, AudioPreference::OpusFirst).unwrap();
        assert_eq!(best.itag, Some(249));
        assert_eq!(best.expires_at, Some(1_700_000_000));
        assert_eq!(best.codecs.as_deref(), Some("opus"));
    }
}
