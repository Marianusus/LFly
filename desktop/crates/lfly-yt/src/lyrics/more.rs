//! The phone's other lyrics sources (LyricsSources: SourcePaxSenix with a key, SourceYouTube
//! transcript, SourceGenius): PaxSenix's Spotify and Musixmatch lyrics with the listener's key,
//! the playing video's timed captions, and Genius' plain text as the last resort.

use super::formats;
use super::providers::{get, get_json, query_hit};
use super::sheet::{Line, Sheet};
use super::{Hit, Query};
use crate::json;
use crate::YtMusic;
use regex::Regex;
use serde_json::Value;
use std::sync::LazyLock;

pub(crate) const PAXSENIX_URL: &str = "https://api.paxsenix.org";
pub(crate) const GENIUS_URL: &str = "https://genius.com";

// --- PaxSenix (the listener's key) -----------------------------------------------------------

fn bearer(key: &str) -> String {
    format!("Bearer {key}")
}

/// Every object in a JSON tree, depth first (the phone's `findObjects`).
fn objects(v: &Value) -> Vec<&serde_json::Map<String, Value>> {
    let mut out = Vec::new();
    let mut stack = vec![v];
    while let Some(v) = stack.pop() {
        match v {
            Value::Object(o) => {
                out.push(o);
                stack.extend(o.values().rev());
            }
            Value::Array(a) => stack.extend(a.iter().rev()),
            _ => {}
        }
    }
    out
}

/// `lrcget`'s answer: the first entry that parses.
fn lrcget(raw: &str) -> Option<Sheet> {
    let root: Value = serde_json::from_str(raw).ok()?;
    let entries = match &root {
        Value::Array(a) => a.clone(),
        Value::Object(o) => o.get("lyrics").or_else(|| o.get("data")).and_then(Value::as_array).cloned().unwrap_or_default(),
        _ => Vec::new(),
    };
    if entries.is_empty() {
        return formats::parse_any(raw);
    }
    entries.iter().find_map(|e| formats::parse_any(&e.to_string()))
}

async fn lrcget_fallback(http: &reqwest::Client, q: &Query, auth: &str) -> Result<Option<Sheet>, String> {
    let body = get(http, &format!("{PAXSENIX_URL}/lyrics/lrcget"), &[("q", format!("{} {}", q.title, q.artist))], &[("Authorization", auth)]).await?;
    Ok(body.as_deref().and_then(lrcget))
}

pub(crate) async fn paxsenix_spotify(http: &reqwest::Client, q: &Query, key: Option<&str>) -> Result<Vec<Hit>, String> {
    let Some(key) = key else { return Ok(Vec::new()) };
    let auth = bearer(key);
    let search = get_json(http, &format!("{PAXSENIX_URL}/spotify/search"), &[("q", format!("{} {}", q.title, q.artist))], &[("Authorization", &auth)]).await?;
    let id = search.as_ref().and_then(|v| objects(v).into_iter().find_map(|o| o.get("id").and_then(Value::as_str).map(str::to_string)));
    let mut sheet = None;
    if let Some(id) = id {
        let body = get(http, &format!("{PAXSENIX_URL}/lyrics/spotify"), &[("id", id)], &[("Authorization", &auth)]).await?;
        sheet = body.as_deref().and_then(formats::parse_any);
    }
    if sheet.is_none() {
        sheet = lrcget_fallback(http, q, &auth).await?;
    }
    Ok(query_hit("PaxSenix: Spotify", q, sheet))
}

pub(crate) async fn paxsenix_musixmatch(http: &reqwest::Client, q: &Query, key: Option<&str>) -> Result<Vec<Hit>, String> {
    let Some(key) = key else { return Ok(Vec::new()) };
    let auth = bearer(key);
    let query = [("t", q.title.clone()), ("a", q.artist.clone()), ("d", (q.duration_ms / 1000).to_string())];
    let body = get(http, &format!("{PAXSENIX_URL}/lyrics/musixmatch"), &query, &[("Authorization", &auth)]).await?;
    let mut sheet = body.as_deref().and_then(formats::parse_any);
    if sheet.is_none() {
        sheet = lrcget_fallback(http, q, &auth).await?;
    }
    Ok(query_hit("PaxSenix: Musixmatch", q, sheet))
}

// --- YouTube captions ------------------------------------------------------------------------

/// The cues of a `get_transcript` answer as timed lines (♪ and blanks dropped).
pub(crate) fn transcript_lines(root: &Value) -> Vec<Line> {
    let mut lines: Vec<Line> = json::collect(root, "transcriptCueRenderer")
        .into_iter()
        .filter_map(|cue| {
            let start = json::long(cue, "startOffsetMs")?;
            let text = json::runs_text(json::obj(cue, "cue"));
            let text = text.trim_matches(|c: char| c == ' ' || c == '\n' || c == '♪').trim().to_string();
            (!text.is_empty()).then(|| Line::new(start, None, text))
        })
        .collect();
    lines.sort_by_key(|l| l.start);
    lines
}

pub(crate) async fn captions(yt: &YtMusic, q: &Query) -> Result<Vec<Hit>, String> {
    let Some(video) = q.video_id.as_deref() else { return Ok(Vec::new()) };
    let root = yt.transcript_json(video).await.map_err(|e| e.to_string())?;
    let lines = transcript_lines(&root);
    Ok(query_hit("Sottotitoli di YouTube", q, (!lines.is_empty()).then_some(Sheet::Synced(lines))))
}

// --- Genius ----------------------------------------------------------------------------------

/// Genius wants a plain client name (the phone's "BitChord").
const GENIUS_UA: &str = "LFly";

static DECORATIVE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"[♪♫★☆【】《》「」~_]").unwrap());
static PRODUCER: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"(?i)\b(?:prod(?:uced)?\.?(?:\s+by)?)\s+.*$").unwrap());
static NOISE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?i)\s*[(\[]\s*(?:from|feat\.?|ft\.?|featuring|with|prod\.?|produced by|official|lyrical|video|audio|remix|music video|visualizer|mv|hd|4k|hq|full song)[^)\]]*[)\]]|\s*\b(?:official\s+(?:music\s+)?(?:video|audio)|lyrical(?:\s+video)?|full\s+song|4k\s+video|hd\s+video|music\s+video)\b").unwrap()
});
static SPACES: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"\s+").unwrap());
static YOU_MIGHT: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"(?i)\d*You might also like").unwrap());
static TRAILING_EMBED: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"(?i)\d*Embed\s*$").unwrap());

fn genius_clean(value: &str) -> String {
    let v = DECORATIVE.replace_all(value, " ");
    let v = NOISE.replace_all(&v, " ");
    let v = PRODUCER.replace_all(&v, " ");
    let v = v.split(" | ").next().unwrap_or_default();
    let v = SPACES.replace_all(v, " ").trim().to_string();
    if v.is_empty() { value.trim().to_string() } else { v }
}

/// The best song of a `search/multi` answer for [title] by [artist] (the phone's `best`).
pub(crate) fn genius_pick(root: &Value, title: &str, artist: &str) -> Option<String> {
    let sections = root.pointer("/response/sections")?.as_array()?;
    let hits = sections.iter().find(|s| s.get("type").and_then(Value::as_str) == Some("song"))?.get("hits")?.as_array()?;
    let (want_t, want_a) = (title.to_lowercase(), artist.to_lowercase());
    let like = |a: &str, b: &str| !b.is_empty() && (a == b || a.contains(b) || b.contains(a));
    hits.iter()
        .filter_map(|h| h.get("result"))
        .filter_map(|r| {
            let t = r.get("title").and_then(Value::as_str).unwrap_or_default().to_lowercase();
            let a = r.get("artist_names").and_then(Value::as_str).unwrap_or_default().to_lowercase();
            let (tm, am) = (like(&t, &want_t), like(&a, &want_a));
            if !tm && !am {
                return None;
            }
            let mut score = if t == want_t { 50 } else if tm { 25 } else { 0 };
            if am {
                score += if a == want_a { 40 } else { 20 };
            }
            let path = r.get("path").and_then(Value::as_str).unwrap_or_default().to_lowercase();
            if path.contains("translation") && !want_t.contains("translation") {
                score -= 30;
            }
            if path.contains("türkçe") || path.contains("tlumaczenie") {
                score -= 40;
            }
            // Genius' own translation accounts ("Genius Türkçe Çeviriler", "Genius Traducciones al
            // Español"): their paths are plain ASCII, so the words above miss them.
            if a.starts_with("genius ") && !want_a.starts_with("genius") {
                score -= 40;
            }
            if path.contains("tracklist") || path.contains("album-art") {
                score -= 50;
            }
            Some((r.get("url")?.as_str()?.to_string(), score))
        })
        // The first of equal scores, in Genius' order (Kotlin's maxByOrNull; max_by_key keeps the last).
        .rev()
        .max_by_key(|(_, s)| *s)
        .filter(|(_, s)| *s > 0)
        .map(|(u, _)| u)
}

/// The tag starting at `html[at]` (`<`…`>`): its name (lowercase, `/name` when closing), the
/// whole tag, and where it ends.
fn tag_at(html: &str, at: usize) -> Option<(String, &str, usize)> {
    let end = at + html[at..].find('>')? + 1;
    let tag = &html[at..end];
    let body = &tag[1..];
    let (slash, body) = match body.strip_prefix('/') {
        Some(rest) => ("/", rest),
        None => ("", body),
    };
    let name = format!("{slash}{}", body.chars().take_while(|c| c.is_alphanumeric() || *c == '!').collect::<String>().to_lowercase());
    Some((name, tag, end))
}

/// `&amp;`, `&#x27;`, `&#39;` and the like.
fn entities(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut rest = text;
    while let Some(i) = rest.find('&') {
        out.push_str(&rest[..i]);
        rest = &rest[i..];
        let Some(semi) = rest[..rest.len().min(12)].find(';') else {
            out.push('&');
            rest = &rest[1..];
            continue;
        };
        let name = &rest[1..semi];
        let c = match name {
            "amp" => Some('&'),
            "lt" => Some('<'),
            "gt" => Some('>'),
            "quot" => Some('"'),
            "apos" => Some('\''),
            "nbsp" => Some(' '),
            n if n.starts_with("#x") || n.starts_with("#X") => u32::from_str_radix(&n[2..], 16).ok().and_then(char::from_u32),
            n if n.starts_with('#') => n[1..].parse().ok().and_then(char::from_u32),
            _ => None,
        };
        match c {
            Some(c) => {
                out.push(c);
                rest = &rest[semi + 1..];
            }
            None => {
                out.push('&');
                rest = &rest[1..];
            }
        }
    }
    out.push_str(rest);
    out
}

/// The lyrics of a Genius page (the phone's `parseHtml`): the text of every
/// `data-lyrics-container="true"` block, `<br>` as new lines, without what Genius marks
/// `data-exclude-from-selection` (headers, ads) and without buttons, scripts and styles.
pub(crate) fn genius_text(html: &str) -> Option<String> {
    let mut text = String::new();
    let mut at = 0;
    while let Some(found) = html[at..].find("data-lyrics-container=\"true\"") {
        let attr = at + found;
        let Some(open) = html[..attr].rfind('<') else { break };
        let Some((_, _, mut i)) = tag_at(html, open) else { break };
        // Inside the container: count its divs to know where it closes; skip excluded subtrees.
        let mut depth = 1;
        let mut skip: Option<(String, usize)> = None;
        let mut part = String::new();
        while depth > 0 && i < html.len() {
            let Some(next) = html[i..].find('<') else { break };
            if skip.is_none() {
                part.push_str(&entities(&html[i..i + next]));
            }
            i += next;
            let Some((name, tag, end)) = tag_at(html, i) else { break };
            i = end;
            let closing = name.starts_with('/');
            let bare = name.trim_start_matches('/').to_string();
            let self_closing = tag.ends_with("/>") || matches!(bare.as_str(), "br" | "img" | "hr" | "input" | "meta" | "link");
            if bare == "div" && !self_closing {
                depth += if closing { -1 } else { 1 };
                if depth == 0 {
                    break;
                }
            }
            if let Some((n, d)) = skip.as_mut() {
                if bare == *n && !self_closing {
                    *d = if closing { *d - 1 } else { *d + 1 };
                    if *d == 0 {
                        skip = None;
                    }
                }
                continue;
            }
            if !closing && !self_closing && (tag.contains("data-exclude-from-selection=\"true\"") || matches!(bare.as_str(), "button" | "script" | "style")) {
                skip = Some((bare, 1));
                continue;
            }
            if bare == "br" || (bare == "p" && !closing) {
                part.push('\n');
            }
        }
        if !part.trim().is_empty() {
            text.push_str(&part);
            text.push('\n');
        }
        at = i.max(attr + 1);
    }
    let cleaned = text.replace(['\u{00A0}', '\u{200B}', '\u{FEFF}'], " ");
    let cleaned = YOU_MIGHT.replace_all(&cleaned, "");
    let cleaned = TRAILING_EMBED.replace_all(cleaned.trim(), "");
    let lines: Vec<&str> = cleaned.lines().map(str::trim).filter(|l| !l.is_empty()).collect();
    (!lines.is_empty()).then(|| lines.join("\n"))
}

pub(crate) async fn genius(http: &reqwest::Client, q: &Query) -> Result<Vec<Hit>, String> {
    let (title, artist) = (genius_clean(&q.title), genius_clean(&q.artist));
    let ua = [("User-Agent", GENIUS_UA)];
    let mut url = None;
    for query in [format!("{artist} {title}"), title.clone()] {
        let query = query.trim().to_string();
        if query.is_empty() {
            continue;
        }
        if let Some(root) = get_json(http, &format!("{GENIUS_URL}/api/search/multi"), &[("q", query)], &ua).await? {
            url = genius_pick(&root, &title, &artist);
        }
        if url.is_some() {
            break;
        }
    }
    let Some(url) = url else { return Ok(Vec::new()) };
    let page = get(http, &url, &[], &[("User-Agent", GENIUS_UA), ("Accept-Language", "en-US,en;q=0.9")]).await?;
    Ok(query_hit("Genius", q, page.as_deref().and_then(genius_text).map(Sheet::Plain)))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn genius_page_text() {
        // The shape of a Genius page (made-up words): two containers, a header to leave out,
        // nested divs, line breaks, entities.
        let html = r#"<html><body><div class="x"><div data-lyrics-container="true" class="Lyrics__Container"><div data-exclude-from-selection="true"><span>3 Contributors</span><div>Song Lyrics</div></div>[Verse 1]<br/>First line &amp; more<br><a href="/x"><span>Second <i>line</i></span></a><br/><div class="inner">Third&#x27;s line</div></div><div class="ad">not lyrics</div><div data-lyrics-container="true"><button>Share</button>Last line<br>You might also like<br/>Fifth line12Embed</div></div></body></html>"#;
        assert_eq!(genius_text(html).as_deref(), Some("[Verse 1]\nFirst line & more\nSecond line\nThird's line\nLast line\nFifth line"));
        assert_eq!(genius_text("<html><div>no lyrics here</div></html>"), None);
    }

    #[test]
    fn genius_picks_the_song_not_a_translation() {
        let root = json!({"response": {"sections": [
            {"type": "top_hit", "hits": []},
            {"type": "song", "hits": [
                {"result": {"title": "Get Lucky (Traduzione italiana)", "artist_names": "Genius Traduzioni italiane", "path": "/Genius-traduzioni-italiane-daft-punk-get-lucky-traduzione-italiana-lyrics", "url": "https://genius.com/t"}},
                {"result": {"title": "Get Lucky", "artist_names": "Daft Punk (Ft. Nile Rodgers & Pharrell Williams)", "path": "/Daft-punk-get-lucky-lyrics", "url": "https://genius.com/Daft-punk-get-lucky-lyrics"}},
                {"result": {"title": "Something else", "artist_names": "Nobody", "path": "/x", "url": "https://genius.com/x"}}]}]}});
        assert_eq!(genius_pick(&root, "get lucky", "daft punk").as_deref(), Some("https://genius.com/Daft-punk-get-lucky-lyrics"));
        assert_eq!(genius_pick(&root, "unknown", "someone"), None);
        assert_eq!(genius_clean("Get Lucky (Official Video) | Daft Punk"), "Get Lucky");
    }

    #[test]
    fn genius_skips_its_translation_accounts_for_an_upload_title() {
        // Genius' real answer for an upload named "Coldplay - The Scientist" by "OmgMati".
        let root = json!({"response": {"sections": [{"type": "song", "hits": [
            {"result": {"title": "The Scientist", "artist_names": "Coldplay", "path": "/Coldplay-the-scientist-lyrics", "url": "https://genius.com/Coldplay-the-scientist-lyrics"}},
            {"result": {"title": "Coldplay - The Scientist (Traducción al Español)", "artist_names": "Genius Traducciones al Español", "path": "/Genius-traducciones-al-espanol-coldplay-the-scientist-traduccion-al-espanol-lyrics", "url": "https://genius.com/es"}},
            {"result": {"title": "Coldplay - The Scientist (Türkçe Çeviri)", "artist_names": "Genius Türkçe Çeviriler", "path": "/Genius-turkce-ceviriler-coldplay-the-scientist-turkce-ceviri-lyrics", "url": "https://genius.com/tr"}}]}]}});
        assert_eq!(genius_pick(&root, "coldplay - the scientist", "omgmati").as_deref(), Some("https://genius.com/Coldplay-the-scientist-lyrics"));
    }

    #[test]
    fn captions_cues() {
        let root = json!({"actions": [{"updateEngagementPanelAction": {"content": {"transcriptRenderer": {"body": {"transcriptBodyRenderer": {"cueGroups": [
            {"transcriptCueGroupRenderer": {"cues": [{"transcriptCueRenderer": {"cue": {"simpleText": "♪ Like the legend ♪"}, "startOffsetMs": "12000", "durationMs": "3000"}}]}},
            {"transcriptCueGroupRenderer": {"cues": [{"transcriptCueRenderer": {"cue": {"runs": [{"text": "♪"}]}, "startOffsetMs": "15000"}}]}},
            {"transcriptCueGroupRenderer": {"cues": [{"transcriptCueRenderer": {"cue": {"simpleText": "of the phoenix"}, "startOffsetMs": "9000"}}]}}]}}}}}}]});
        let lines = transcript_lines(&root);
        assert_eq!(lines.iter().map(|l| (l.start, l.text.as_str())).collect::<Vec<_>>(), [(9000, "of the phoenix"), (12000, "Like the legend")]);
    }

    #[test]
    fn lrcget_takes_the_first_entry_that_parses() {
        let raw = r#"{"lyrics": [{"nothing": true}, {"syncedLyrics": "[00:01.00]One\n[00:02.00]Two"}]}"#;
        assert!(matches!(lrcget(raw), Some(Sheet::Synced(l)) if l.len() == 2));
    }
}
