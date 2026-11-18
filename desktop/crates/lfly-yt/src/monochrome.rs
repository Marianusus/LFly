//! Monochrome (port of the phone's MonochromeApiClient and of StreamResolver's race): a catalogue
//! of studio releases searched by title and artist, whose `/track/{id}` serves the whole song.
//! As measured on 2026-11-13 it is AAC 320 kbps in MP4 (not the FLAC the phone's code assumes),
//! so mime, codec and length are taken from the server's answer.
//!
//! The phone's rules: several searches per song (as written, cleaned, title alone, the part before
//! a dash) until a candidate scores 120; one scoring 75 is enough; the artist must match. Its
//! comment also promises a ±8 s duration guard that its code only weighs; here it is a guard, so
//! an album edit never plays for a radio edit (the lyrics' timings follow the edit). When no
//! instance answers at all, Monochrome is left alone for ten minutes.

use crate::health::now_ms;
use crate::model::{SourceKind, StreamSource};
use crate::YtMusic;
use regex::Regex;
use serde_json::Value;
use std::sync::LazyLock;
use std::time::Duration;
use unicode_normalization::UnicodeNormalization;

/// Healthiest first: on 2026-11-13 only tracks.monochrome.st answered.
pub const MONOCHROME_INSTANCES: &[&str] = &["https://tracks.monochrome.st", "https://api.monochrome.tf", "https://monochrome.tf", "https://lossless.wtf"];

const SEARCH_LIMIT: usize = 8;
/// A candidate this good ends the searches.
const SURE: i32 = 120;
/// The least a candidate must score to be played.
const ENOUGH: i32 = 75;
/// Longer or shorter than the song by more than this: another edit.
const DURATION_GUARD_S: i64 = 8;
const REQUEST_TIMEOUT: Duration = Duration::from_secs(5);
/// How long the whole Monochrome lookup may take (the phone's TIER_TIMEOUT_MS).
pub(crate) const TIER_TIMEOUT: Duration = Duration::from_secs(6);
/// How long Monochrome is left alone once no instance answers (MONOCHROME_PAUSE_MS).
pub(crate) const PAUSE_MS: u64 = 10 * 60_000;

pub(crate) fn register(health: &crate::health::HealthRegistry) {
    for url in MONOCHROME_INSTANCES {
        health.register(&format!("Monochrome ({})", url.trim_start_matches("https://")), url, SourceKind::Monochrome);
    }
}

#[derive(Debug, Clone, PartialEq)]
pub(crate) struct Candidate {
    pub id: String,
    pub title: String,
    pub artists: Vec<String>,
    pub duration_ms: i64,
}

/// A `search/tracks` answer; `None` when the body is not one (an error page, a redirect page).
pub(crate) fn parse_search(body: &str) -> Option<Vec<Candidate>> {
    let root: Value = serde_json::from_str(body).ok()?;
    let tracks = root.get("tracks")?.as_array()?;
    Some(
        tracks
            .iter()
            .filter_map(|t| {
                let id = t.get("id").or_else(|| t.get("trackId")).and_then(|v| v.as_str().map(str::to_string).or_else(|| v.as_i64().map(|n| n.to_string())))?;
                if t.get("playable").and_then(Value::as_bool) == Some(false) {
                    return None;
                }
                Some(Candidate {
                    id,
                    title: t.get("title").and_then(Value::as_str).unwrap_or_default().to_string(),
                    artists: t.get("artistNames").and_then(Value::as_array).map(|a| a.iter().filter_map(|x| x.as_str().map(str::to_string)).collect()).unwrap_or_default(),
                    duration_ms: t.get("duration").and_then(Value::as_i64).unwrap_or(0),
                })
            })
            .collect(),
    )
}

static TITLE_NOISE: LazyLock<Vec<Regex>> = LazyLock::new(|| {
    [
        r"\s*[(\[](?:feat\.|ft\.|with|featuring|remaster|extended|deluxe|anniversary|bonus|special edition|official|audio|video|music video).*?[)\]]",
        r"\s*\((?:club mix|original mix|radio edit).*?\)",
        r"\s*-\s*(?:Remaster|Extended|Radio Edit|Single|EP).*",
    ]
    .iter()
    .map(|p| Regex::new(&format!("(?i){p}")).unwrap())
    .collect()
});
static ARTIST_FEAT: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"(?i)\s*[(\[](?:feat\.|ft\.|with).*?[)\]]").unwrap());
static ARTIST_SPLIT: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"(?i)\s+(?:feat\.?|ft\.?|with|featuring)\s+|[,&/;+]").unwrap());

static SANITIZE: LazyLock<Vec<Regex>> = LazyLock::new(|| {
    [
        r"\s*[(\[](?:feat\.|ft\.|remaster|extended|deluxe|bonus|official).*?[)\]]",
        r"\s*\((?:with|club mix|original mix|radio edit).*?\)",
        r"\s*-\s*(?:Remaster|Extended|Radio Edit|Single|EP).*",
        r"\s*[(\[](?:music video|lyric video|lyrics|visualizer)[)\]]",
        r"\s*\|.*",
    ]
    .iter()
    .map(|p| Regex::new(&format!("(?i){p}")).unwrap())
    .collect()
});

/// The song's title as the phone asks Monochrome for it (StreamResolver.sanitizeTitle): without
/// featurings, remaster, edit and video tags, and without a leading "Artist - ". A "(Live)" stays,
/// so a live take never stands for the studio one.
pub(crate) fn sanitize_title(title: &str, artist: &str) -> String {
    let mut t = title.to_string();
    for re in SANITIZE.iter() {
        t = re.replace_all(&t, "").into_owned();
    }
    let t = t.trim();
    match t.split_once(" - ") {
        Some((left, right)) if !artist.trim().is_empty() && left.trim().eq_ignore_ascii_case(artist.trim()) => right.trim().to_string(),
        _ => t.to_string(),
    }
}

/// The first artist (StreamResolver.sanitizeArtist).
pub(crate) fn sanitize_artist(artist: &str) -> String {
    let mut first = artist;
    for sep in [",", "&", "feat.", "ft."] {
        first = first.split(sep).next().unwrap_or_default();
    }
    first.trim().to_string()
}

fn clean_title(title: &str) -> String {
    let mut t = title.to_string();
    for re in TITLE_NOISE.iter() {
        t = re.replace_all(&t, "").into_owned();
    }
    t.trim().to_string()
}

fn clean_artist(artist: &str) -> String {
    let a = ARTIST_FEAT.replace_all(artist, "");
    ARTIST_SPLIT.split(&a).next().unwrap_or_default().trim().to_string()
}

/// Lowercase letters and digits only, accents dropped.
fn alnum(text: &str) -> String {
    text.nfd().filter(|c| !unicode_normalization::char::is_combining_mark(*c)).flat_map(char::to_lowercase).filter(|c| c.is_ascii_alphanumeric()).collect()
}

fn has_version(title: &str) -> bool {
    let l = title.to_lowercase();
    ["extended", "remix", "mix", "edit", "version", "live", "acoustic", "instrumental"].iter().any(|k| l.contains(k))
}

/// The searches tried, best first (`buildSearchQueries`).
pub(crate) fn queries(title: &str, artist: &str) -> Vec<String> {
    let (t, a) = (clean_title(title), clean_artist(artist));
    let mut out = vec![format!("{artist} {title}").trim().to_string()];
    if !t.is_empty() && !a.is_empty() {
        out.push(format!("{a} {t}"));
        out.push(format!("{t} {a}"));
    }
    if !t.is_empty() {
        out.push(t.clone());
    }
    if let Some((base, _)) = t.split_once('-') {
        let base = base.trim();
        if !base.is_empty() {
            if !a.is_empty() {
                out.push(format!("{a} {base}"));
            }
            out.push(base.to_string());
        }
    }
    let mut seen = std::collections::HashSet::new();
    out.into_iter().filter(|q| !q.trim().is_empty() && seen.insert(q.clone())).collect()
}

/// How sure a candidate is the song (`scoreCandidate`): 0 when its artist does not match, or
/// when its length says it is another edit.
pub(crate) fn score(c: &Candidate, title: &str, artist: &str, duration_ms: i64) -> i32 {
    if duration_ms > 0 && c.duration_ms > 0 && ((duration_ms - c.duration_ms).abs() / 1000) > DURATION_GUARD_S {
        return 0;
    }
    let mut score = 0;
    let (raw_t, raw_c) = (alnum(title), alnum(&c.title));
    let (clean_t, clean_c) = (alnum(&clean_title(title)), alnum(&clean_title(&c.title)));
    if !raw_t.is_empty() && raw_t == raw_c {
        score += 120;
    } else if !clean_t.is_empty() && clean_t == clean_c {
        score += 90;
        if has_version(title) == has_version(&c.title) {
            score += 15;
        }
    } else if !clean_t.is_empty() && !clean_c.is_empty() && (clean_t.contains(&clean_c) || clean_c.contains(&clean_t)) {
        score += 65;
    }
    let want = alnum(&clean_artist(artist));
    if !want.is_empty() {
        let matched = c.artists.iter().map(|a| alnum(a)).any(|a| !a.is_empty() && (a == want || a.contains(&want) || want.contains(&a)));
        if !matched {
            return 0;
        }
        score += 50;
    }
    if duration_ms > 0 && c.duration_ms > 0 {
        let diff = (duration_ms - c.duration_ms).abs() / 1000;
        score += match diff {
            0..=4 => 40,
            5..=8 => 20,
            _ => 0,
        };
    }
    score
}

/// Longest gap between the two lengths for the same recording (catalogues round differently).
const SAME_LENGTH_S: i64 = 4;

/// Whether [c] is surely the song the listener picked, not one like it: the same length (known on
/// both sides), the same title (whole, or without featurings and tags but with the same version
/// words), and its artist. Monochrome is searched by name, so anything less may be another song
/// (a "Sorry" for another "Sorry", the original for a rework): YouTube, by its id, plays then.
pub(crate) fn same_song(c: &Candidate, title: &str, artist: &str, duration_ms: i64) -> bool {
    if duration_ms <= 0 || c.duration_ms <= 0 || (duration_ms - c.duration_ms).abs() / 1000 > SAME_LENGTH_S {
        return false;
    }
    let (raw_t, raw_c) = (alnum(title), alnum(&c.title));
    let (clean_t, clean_c) = (alnum(&clean_title(title)), alnum(&clean_title(&c.title)));
    let titled = (!raw_t.is_empty() && raw_t == raw_c) || (!clean_t.is_empty() && clean_t == clean_c && has_version(title) == has_version(&c.title));
    let want = alnum(&clean_artist(artist));
    let by = !want.is_empty() && c.artists.iter().map(|a| alnum(a)).any(|a| !a.is_empty() && (a == want || a.contains(&want) || want.contains(&a)));
    titled && by
}

/// What a Monochrome lookup found.
#[derive(Debug)]
pub(crate) enum Outcome {
    Found(StreamSource),
    /// It answered, without the song.
    Missing,
    /// No instance answered.
    Unreachable,
}

async fn search(http: &reqwest::Client, base: &str, query: &str) -> Result<Vec<Candidate>, String> {
    let resp = http
        .get(format!("{base}/search/tracks"))
        .query(&[("q", query), ("limit", &SEARCH_LIMIT.to_string())])
        .timeout(REQUEST_TIMEOUT)
        .send()
        .await
        .map_err(|e| e.to_string())?;
    let status = resp.status().as_u16();
    if !(200..300).contains(&status) {
        return Err(format!("HTTP {status}"));
    }
    let body = resp.text().await.map_err(|e| e.to_string())?;
    // A 200 that is not a search answer (an HTML page) counts as no answer.
    parse_search(&body).ok_or_else(|| "not a Monochrome answer".to_string())
}

/// The audio format of a file's first bytes, as a mime type (the server may say only
/// `application/octet-stream`).
pub(crate) fn sniff(head: &[u8]) -> Option<&'static str> {
    if head.starts_with(b"fLaC") {
        Some("audio/flac")
    } else if head.len() >= 8 && &head[4..8] == b"ftyp" {
        Some("audio/mp4")
    } else if head.starts_with(b"OggS") {
        Some("audio/ogg")
    } else if head.starts_with(b"ID3") || (head.len() >= 2 && head[0] == 0xFF && head[1] & 0xE0 == 0xE0) {
        Some("audio/mpeg")
    } else {
        None
    }
}

/// The song's audio, if the server really serves it: its type (from its first bytes) and its
/// length.
async fn probe(http: &reqwest::Client, url: &str) -> Option<(String, Option<u64>)> {
    let resp = http.get(url).header("Range", "bytes=0-15").timeout(REQUEST_TIMEOUT).send().await.ok()?;
    let status = resp.status().as_u16();
    if status != 206 && status != 200 {
        log::info!("Monochrome stream refused: HTTP {status}");
        return None;
    }
    let declared = resp.headers().get("content-type").and_then(|v| v.to_str().ok()).unwrap_or_default().to_string();
    let total = resp
        .headers()
        .get("content-range")
        .and_then(|v| v.to_str().ok())
        .and_then(|r| r.rsplit('/').next())
        .and_then(|n| n.trim().parse().ok())
        .or_else(|| (status == 200).then(|| resp.content_length()).flatten());
    let head = resp.bytes().await.ok()?;
    let Some(mime) = sniff(&head[..head.len().min(16)]) else {
        log::info!("Monochrome stream is not audio ({declared})");
        return None;
    };
    Some((mime.to_string(), total))
}

/// The song on Monochrome by title, artist and length.
pub(crate) async fn resolve(yt: &YtMusic, title: &str, artist: &str, duration_ms: i64) -> Outcome {
    let http = yt.http();
    let health = yt.health_registry();
    let queries = queries(title, artist);
    let mut answered = false;
    for base in MONOCHROME_INSTANCES.iter().copied().filter(|b| health.is_available(b)) {
        let start = now_ms();
        let mut best: Option<(Candidate, i32)> = None;
        let mut failed = None;
        for q in &queries {
            match search(http, base, q).await {
                Ok(found) => {
                    answered = true;
                    for c in found {
                        // Only the same recording counts; among those, the best scored.
                        let s = if same_song(&c, title, artist, duration_ms) { score(&c, title, artist, duration_ms) } else { 0 };
                        if best.as_ref().is_none_or(|b| s > b.1) {
                            best = Some((c, s));
                        }
                    }
                }
                Err(e) => {
                    failed = Some(e);
                    break;
                }
            }
            if best.as_ref().is_some_and(|b| b.1 >= SURE) {
                break;
            }
        }
        match &failed {
            Some(e) if best.is_none() => {
                log::info!("Monochrome {base}: {e}");
                health.record_failure(base, e);
                continue;
            }
            _ => health.record_success(base, now_ms().saturating_sub(start)),
        }
        let Some((c, s)) = best.filter(|b| b.1 >= ENOUGH) else {
            log::info!("Monochrome {base}: no match for '{title}' by '{artist}'");
            continue;
        };
        let Some((mime, len)) = probe(http, &format!("{base}/track/{}", c.id)).await else { continue };
        // The length in the URL as googlevideo carries it (`clen`, which the server ignores): the
        // audio cache, keyed by song, then sees a part kept from another source's file.
        let url = match len {
            Some(n) => format!("{base}/track/{}?clen={n}", c.id),
            None => format!("{base}/track/{}", c.id),
        };
        log::info!("Monochrome {base}: '{}' by {:?} (score {s}) -> {mime}", c.title, c.artists);
        let codecs = Some(
            match mime.as_str() {
                "audio/flac" => "flac",
                "audio/mp4" => "mp4a.40.2",
                "audio/ogg" => "opus",
                _ => "mp3",
            }
            .to_string(),
        );
        return Outcome::Found(StreamSource {
            url,
            mime,
            codecs,
            bitrate: 0,
            itag: None,
            expires_at: None,
            content_length: len,
            approx_duration_ms: (c.duration_ms > 0).then_some(c.duration_ms as u64),
            provider: format!("Monochrome ({})", base.trim_start_matches("https://")),
            user_agent: None,
        });
    }
    if answered { Outcome::Missing } else { Outcome::Unreachable }
}

/// The two sources asked together (the phone's `race` in Auto): whichever answers first, except
/// that once YouTube has a stream Monochrome still wins if it is ready within [grace].
pub(crate) async fn race<T, M, Y>(monochrome: M, youtube: Y, grace: Duration) -> (Option<T>, &'static str)
where
    M: std::future::Future<Output = Option<T>>,
    Y: std::future::Future<Output = Option<T>>,
{
    tokio::pin!(monochrome, youtube);
    tokio::select! {
        m = &mut monochrome => match m {
            Some(m) => (Some(m), "monochrome"),
            None => (youtube.await, "youtube"),
        },
        y = &mut youtube => match y {
            None => (monochrome.await, "monochrome"),
            Some(y) => match tokio::time::timeout(grace, &mut monochrome).await {
                Ok(Some(m)) => (Some(m), "monochrome"),
                _ => (Some(y), "youtube"),
            },
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cand(title: &str, artists: &[&str], secs: i64) -> Candidate {
        Candidate { id: "1".into(), title: title.into(), artists: artists.iter().map(|a| a.to_string()).collect(), duration_ms: secs * 1000 }
    }

    #[test]
    fn scores_like_the_phone_with_a_duration_guard() {
        let exact = cand("Get Lucky", &["Daft Punk", "Pharrell Williams"], 248);
        assert_eq!(score(&exact, "Get Lucky", "Daft Punk", 248_000), 120 + 50 + 40);
        // The album edit for the radio edit: another length, never played.
        let album = cand("Get Lucky (feat. Pharrell Williams and Nile Rodgers)", &["Daft Punk"], 369);
        assert_eq!(score(&album, "Get Lucky", "Daft Punk", 248_000), 0);
        // Featuring dropped on both sides: the clean titles match.
        assert!(score(&album, "Get Lucky", "Daft Punk", 369_000) >= ENOUGH);
        // A cover: the artist does not match.
        assert_eq!(score(&cand("Get Lucky", &["Some Cover Band"], 248), "Get Lucky", "Daft Punk", 248_000), 0);
        // Accents and case do not matter.
        assert_eq!(score(&cand("Zitti E Buoni", &["Måneskin"], 194), "Zitti e buoni", "Maneskin", 0), 120 + 50);
        // Unknown length: no guard, no bonus.
        assert_eq!(score(&exact, "Get Lucky", "Daft Punk", 0), 170);
    }

    #[test]
    fn only_the_same_recording_is_taken() {
        let exact = cand("Get Lucky", &["Daft Punk", "Pharrell Williams"], 248);
        assert!(same_song(&exact, "Get Lucky", "Daft Punk", 249_000));
        // Unknown length: not sure enough.
        assert!(!same_song(&exact, "Get Lucky", "Daft Punk", 0));
        // Another song whose title holds this one's.
        assert!(!same_song(&cand("Sorry Not Sorry", &["Demi Lovato"], 203), "Sorry", "Demi Lovato", 203_000));
        // The original for a rework, a studio cut for a live take.
        assert!(!same_song(&cand("World Hold On", &["Bob Sinclar"], 210), "World Hold On [FISHER Rework]", "Bob Sinclar", 210_000));
        assert!(!same_song(&cand("Viva La Vida", &["Coldplay"], 242), "Viva La Vida (Live)", "Coldplay", 242_000));
        // Featurings dropped on both sides still match.
        assert!(same_song(&cand("Sexy Bitch (feat. Akon)", &["David Guetta"], 195), "Sexy Bitch", "David Guetta", 196_000));
        // Six seconds apart: another edit.
        assert!(!same_song(&exact, "Get Lucky", "Daft Punk", 254_000));
    }

    #[test]
    fn titles_and_artists_as_the_phone_asks() {
        assert_eq!(sanitize_title("Get Lucky (Radio Edit - feat. Pharrell Williams and Nile Rodgers)", "Daft Punk"), "Get Lucky");
        assert_eq!(sanitize_title("Viva La Vida (Live)", "Coldplay"), "Viva La Vida (Live)");
        assert_eq!(sanitize_title("Daft Punk - One More Time (Official Video) | HD", "Daft Punk"), "One More Time");
        assert_eq!(sanitize_title("Song - Remastered 2011", ""), "Song");
        assert_eq!(sanitize_artist("Nile Rodgers, Pharrell & Daft Punk"), "Nile Rodgers");
        assert_eq!(sanitize_artist("Queen"), "Queen");
        // A live take against the studio cut: the phone's scoring alone would take it (the
        // titles contain each other); its different length is what keeps it out.
        let studio = cand("Viva La Vida", &["Coldplay"], 242);
        assert!(score(&studio, "Viva La Vida (Live)", "Coldplay", 242_000) >= ENOUGH);
        assert_eq!(score(&studio, "Viva La Vida (Live)", "Coldplay", 262_000), 0);
    }

    #[test]
    fn searches_from_exact_to_loose() {
        assert_eq!(
            queries("Song - Live (feat. X)", "Band feat. Y"),
            ["Band feat. Y Song - Live (feat. X)", "Band Song - Live", "Song - Live Band", "Song - Live", "Band Song", "Song"]
        );
        assert_eq!(queries("Plain", "Artist"), ["Artist Plain", "Plain Artist", "Plain"]);
    }

    #[test]
    fn search_answers_and_error_pages() {
        let body = r#"{"tracks":[{"id":"155142501534011392","title":"Get Lucky","artistNames":["Daft Punk","Pharrell Williams"],"duration":369626,"playable":true},{"id":"2","title":"x","playable":false}]}"#;
        let found = parse_search(body).unwrap();
        assert_eq!(found, vec![Candidate { id: "155142501534011392".into(), title: "Get Lucky".into(), artists: vec!["Daft Punk".into(), "Pharrell Williams".into()], duration_ms: 369_626 }]);
        assert_eq!(parse_search("<head><title>monochrome</title></head><h1>meow :3</h1>"), None);
        assert_eq!(parse_search(r#"{"error":"rate limited"}"#), None);
    }

    /// Monochrome alone on a few studio songs: what it finds and how long it takes.
    #[tokio::test]
    #[ignore = "network"]
    async fn live_monochrome_alone() {
        let yt = YtMusic::new().unwrap();
        for (title, artist, secs) in [("Bohemian Rhapsody", "Queen", 355), ("Get Lucky", "Daft Punk", 369), ("Rolling in the Deep", "Adele", 228)] {
            let start = now_ms();
            let r = resolve(&yt, title, artist, secs * 1000).await;
            println!("{title}: {} ms -> {:?}", now_ms() - start, match r { Outcome::Found(s) => format!("{} {} {:?}", s.provider, s.mime, s.content_length), o => format!("{o:?}") });
            tokio::time::sleep(Duration::from_secs(5)).await;
        }
    }

    /// The whole race (Auto) on songs as YouTube Music's song search finds them: who wins, how fast.
    #[tokio::test]
    #[ignore = "network"]
    async fn live_race() {
        let yt = YtMusic::new().unwrap();
        // Studio tracks, from album pages (the song search returns live takes and videos).
        for (album, n) in [("Random Access Memories Daft Punk", 7), ("21 Adele", 0), ("Future Nostalgia Dua Lipa", 4), ("Viva la Vida or Death and All His Friends Coldplay", 6), ("A Night at the Opera Queen", 10)] {
            let Some(id) = yt.search_albums(album, 1).await.unwrap_or_default().into_iter().next().and_then(|a| a.browse_id) else { continue };
            let Some(t) = yt.album(&id).await.ok().and_then(|p| p.tracks.into_iter().nth(n)) else { continue };
            let start = now_ms();
            let r = yt.resolve_stream(&t).await;
            println!("{} - {} ({} s): {} ms -> {:?}", t.artist, t.title, t.duration_ms / 1000, now_ms() - start, r.map(|s| s.provider));
            tokio::time::sleep(Duration::from_secs(12)).await;
        }
    }

    #[test]
    fn audio_formats_by_their_first_bytes() {
        assert_eq!(sniff(b"fLaC\0\0\0\x22"), Some("audio/flac"));
        assert_eq!(sniff(b"\0\0\0\x20ftypM4A "), Some("audio/mp4"));
        assert_eq!(sniff(b"OggS\0\x02"), Some("audio/ogg"));
        assert_eq!(sniff(b"ID3\x04"), Some("audio/mpeg"));
        assert_eq!(sniff(b"<html><head>"), None);
        assert_eq!(sniff(b""), None);
    }

    async fn after(ms: u64, v: Option<&'static str>) -> Option<&'static str> {
        tokio::time::sleep(Duration::from_millis(ms)).await;
        v
    }

    #[tokio::test(start_paused = true)]
    async fn the_race_follows_the_phone() {
        let grace = Duration::from_millis(1500);
        // Monochrome first: it wins.
        assert_eq!(race(after(500, Some("m")), after(1000, Some("y")), grace).await, (Some("m"), "monochrome"));
        // YouTube first, Monochrome within the grace: lossless still wins.
        assert_eq!(race(after(2000, Some("m")), after(1000, Some("y")), grace).await, (Some("m"), "monochrome"));
        // YouTube first, Monochrome too late: YouTube.
        assert_eq!(race(after(3000, Some("m")), after(1000, Some("y")), grace).await, (Some("y"), "youtube"));
        // One of them has nothing: the other one, however late.
        assert_eq!(race(after(100, None), after(4000, Some("y")), grace).await, (Some("y"), "youtube"));
        assert_eq!(race(after(5000, Some("m")), after(100, None), grace).await, (Some("m"), "monochrome"));
        assert_eq!(race(after(100, None::<&str>), after(100, None), grace).await.0, None);
    }
}
