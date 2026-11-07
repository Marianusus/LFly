//! Small helpers for walking InnerTube JSON (ports of the Kotlin Gson helpers).

use regex::Regex;
use serde_json::{Map, Value};
use std::sync::LazyLock;

pub(crate) type Obj = Map<String, Value>;

const MAX_DEPTH: usize = 25;

/// `obj[name]` when it is an object.
pub(crate) fn obj<'a>(o: &'a Obj, name: &str) -> Option<&'a Obj> {
    o.get(name)?.as_object()
}

/// `obj[name]` when it is an array.
pub(crate) fn arr<'a>(o: &'a Obj, name: &str) -> Option<&'a Vec<Value>> {
    o.get(name)?.as_array()
}

/// `obj[name]` as text: strings as-is, numbers and booleans printed (like Gson `asString`).
pub(crate) fn string(o: &Obj, name: &str) -> Option<String> {
    match o.get(name)? {
        Value::String(s) => Some(s.clone()),
        Value::Number(n) => Some(n.to_string()),
        Value::Bool(b) => Some(b.to_string()),
        _ => None,
    }
}

/// `obj[name]` as a whole number, from a JSON number or a numeric string.
pub(crate) fn long(o: &Obj, name: &str) -> Option<i64> {
    match o.get(name)? {
        Value::Number(n) => n.as_i64().or_else(|| n.as_f64().map(|f| f as i64)),
        Value::String(s) => s.trim().parse().ok(),
        _ => None,
    }
}

/// Follows a path of object keys.
pub(crate) fn path<'a>(o: &'a Obj, keys: &[&str]) -> Option<&'a Obj> {
    let mut cur = o;
    for key in keys {
        cur = obj(cur, key)?;
    }
    Some(cur)
}

/// Every object found under `key`, depth first, anywhere below `root`.
pub(crate) fn collect<'a>(root: &'a Value, key: &str) -> Vec<&'a Obj> {
    let mut out = Vec::new();
    collect_into(root, key, &mut out, 0);
    out
}

fn collect_into<'a>(el: &'a Value, key: &str, out: &mut Vec<&'a Obj>, depth: usize) {
    if depth > MAX_DEPTH {
        return;
    }
    match el {
        Value::Object(o) => {
            if let Some(Value::Object(child)) = o.get(key) {
                out.push(child);
            }
            for v in o.values() {
                collect_into(v, key, out, depth + 1);
            }
        }
        Value::Array(a) => {
            for v in a {
                collect_into(v, key, out, depth + 1);
            }
        }
        _ => {}
    }
}

/// Same as [`collect`] but starting from an object.
pub(crate) fn collect_in<'a>(root: &'a Obj, key: &str) -> Vec<&'a Obj> {
    let mut out = Vec::new();
    if let Some(Value::Object(child)) = root.get(key) {
        out.push(child);
    }
    for v in root.values() {
        collect_into(v, key, &mut out, 1);
    }
    out
}

/// Every string (or number) value of `field`, depth first.
pub(crate) fn collect_strings(root: &Value, field: &str) -> Vec<String> {
    let mut out = Vec::new();
    collect_strings_into(root, field, &mut out, 0);
    out
}

fn collect_strings_into(el: &Value, field: &str, out: &mut Vec<String>, depth: usize) {
    if depth > MAX_DEPTH {
        return;
    }
    match el {
        Value::Object(o) => {
            match o.get(field) {
                Some(Value::String(s)) => out.push(s.clone()),
                Some(Value::Number(n)) => out.push(n.to_string()),
                _ => {}
            }
            for v in o.values() {
                collect_strings_into(v, field, out, depth + 1);
            }
        }
        Value::Array(a) => {
            for v in a {
                collect_strings_into(v, field, out, depth + 1);
            }
        }
        _ => {}
    }
}

/// Joined text of a `{simpleText}` or `{runs:[{text}]}` object; empty when absent.
pub(crate) fn runs_text(text: Option<&Obj>) -> String {
    let Some(text) = text else { return String::new() };
    if let Some(Value::String(s)) = text.get("simpleText") {
        return s.clone();
    }
    let Some(runs) = arr(text, "runs") else { return String::new() };
    runs.iter()
        .filter_map(|r| r.as_object()?.get("text")?.as_str())
        .collect()
}

/// The `runs` of a text object as objects.
pub(crate) fn runs(text: Option<&Obj>) -> impl Iterator<Item = &Obj> {
    text.and_then(|t| arr(t, "runs"))
        .into_iter()
        .flatten()
        .filter_map(Value::as_object)
}

/// First `navigationEndpoint.browseEndpoint` among the runs.
pub(crate) fn first_browse_endpoint(text: Option<&Obj>) -> Option<&Obj> {
    runs(text).find_map(|r| path(r, &["navigationEndpoint", "browseEndpoint"]))
}

/// `pageType` of a browse endpoint (`MUSIC_PAGE_TYPE_ARTIST`, ...).
pub(crate) fn page_type(browse: Option<&Obj>) -> Option<String> {
    string(
        path(browse?, &["browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig"])?,
        "pageType",
    )
}

/// First non-blank `watchEndpoint.videoId` among the runs.
pub(crate) fn run_video_id(text: Option<&Obj>) -> Option<String> {
    runs(text).find_map(|r| {
        let id = string(path(r, &["navigationEndpoint", "watchEndpoint"])?, "videoId")?;
        (!id.trim().is_empty()).then_some(id)
    })
}

/// First playlist id from a run's watch or watch-playlist endpoint.
pub(crate) fn run_watch_playlist_id(text: Option<&Obj>) -> Option<String> {
    runs(text).find_map(|r| {
        let nav = obj(r, "navigationEndpoint")?;
        obj(nav, "watchEndpoint")
            .and_then(|w| string(w, "playlistId"))
            .filter(|s| !s.trim().is_empty())
            .or_else(|| {
                obj(nav, "watchPlaylistEndpoint")
                    .and_then(|w| string(w, "playlistId"))
                    .filter(|s| !s.trim().is_empty())
            })
    })
}

static CHANNEL_RE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"/channel/([A-Za-z0-9_-]+)").unwrap());

/// Channel id from a run's `canonicalBaseUrl` (`/channel/UC...`).
pub(crate) fn run_channel_id(text: Option<&Obj>) -> Option<String> {
    runs(text).find_map(|r| {
        let base = string(path(r, &["navigationEndpoint", "browseEndpoint"])?, "canonicalBaseUrl")?;
        Some(CHANNEL_RE.captures(&base)?[1].to_string())
    })
}

pub(crate) fn channel_from_url(url: &str) -> Option<String> {
    Some(CHANNEL_RE.captures(url)?[1].to_string())
}

static SIZE_RE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"=w\d+-h\d+").unwrap());
static SQUARE_RE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"=s\d+").unwrap());

/// Asks googleusercontent for a 544x544 cover instead of the 60/120/226 px one
/// InnerTube lists (`=w120-h120` becomes `=w544-h544`, like the phone does).
/// `=s192` style urls get `=s544`. Other urls are returned unchanged.
pub fn upscale_thumbnail(url: &str) -> String {
    if url.contains("=w") && url.contains("-h") && SIZE_RE.is_match(url) {
        return SIZE_RE.replace(url, "=w544-h544").into_owned();
    }
    if (url.contains("googleusercontent.com") || url.contains("ggpht.com")) && SQUARE_RE.is_match(url) {
        return SQUARE_RE.replace(url, "=s544").into_owned();
    }
    url.to_string()
}

fn thumbnails_of(item: &Obj) -> Option<&Vec<Value>> {
    let holder = obj(item, "thumbnail").or_else(|| obj(item, "thumbnailRenderer"))?;
    let inner = obj(holder, "musicThumbnailRenderer")
        .and_then(|m| obj(m, "thumbnail"))
        .or_else(|| obj(holder, "croppedSquareThumbnailRenderer").and_then(|m| obj(m, "thumbnail")));
    match inner {
        Some(t) => arr(t, "thumbnails"),
        None => arr(holder, "thumbnails"),
    }
}

/// Largest listed thumbnail of an item, unchanged.
pub(crate) fn raw_thumbnail(item: &Obj) -> Option<String> {
    let list = thumbnails_of(item)?;
    let url = list.last()?.as_object().and_then(|t| string(t, "url"))?;
    (!url.is_empty()).then_some(url)
}

/// Largest listed thumbnail of an item, upscaled with [`upscale_thumbnail`].
pub(crate) fn thumbnail(item: &Obj) -> Option<String> {
    raw_thumbnail(item).map(|u| upscale_thumbnail(&u))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn upscales_known_patterns() {
        assert_eq!(
            upscale_thumbnail("https://lh3.googleusercontent.com/abc=w60-h60-l90-rj"),
            "https://lh3.googleusercontent.com/abc=w544-h544-l90-rj"
        );
        assert_eq!(
            upscale_thumbnail("https://yt3.googleusercontent.com/abc=s192"),
            "https://yt3.googleusercontent.com/abc=s544"
        );
        let plain = "https://i.ytimg.com/vi/x/hqdefault.jpg";
        assert_eq!(upscale_thumbnail(plain), plain);
    }
}
