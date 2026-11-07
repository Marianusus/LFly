//! The lyrics providers (ports of the phone's `providers/` and `sources/`).
//!
//! Each one answers `Ok(hits)` (maybe empty: nothing found) or `Err` when the
//! service itself failed, which feeds the circuit breaker.

use super::formats::{self, ttml};
use super::sheet::{self, Line, Sheet, Word};
use super::{krc, Hit, Query};
use crate::health::{now_ms, HealthRegistry};
use serde_json::Value;
use std::sync::Mutex;
use std::time::Duration;
use unicode_normalization::UnicodeNormalization;

const BROWSER_UA: &str =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
const LRCLIB_UA: &str = "LFly-desktop/0.1 (lfly-yt)";
/// Time limit of one provider request.
const REQUEST_TIMEOUT: Duration = Duration::from_secs(8);

pub(crate) const MUSIXMATCH_URL: &str = "https://apic-desktop.musixmatch.com";
pub(crate) const KUGOU_URL: &str = "https://krcs.kugou.com";
pub(crate) const LRC_RED_URL: &str = "https://lrc.red";
pub(crate) const BINI_URL: &str = "https://lyrics-api.binimum.org";
pub(crate) const BETTER_URL: &str = "https://lyrics-api.boidu.dev/getLyrics";
pub(crate) const PORTATO_URL: &str = "https://lyrics-api.boidu.dev/qq/getLyrics";
pub(crate) const UNISON_URL: &str = "https://unison.boidu.dev/lyrics";
pub(crate) const LYRICS_PLUS_MIRRORS: &[&str] = &[
    "https://lyricsplus.prjktla.my.id",
    "https://lyricsplus.atomix.one",
    "https://lyricsplus.binimum.org",
    "https://lyricsplus.prjktla.workers.dev",
    "https://lyricsplus-seven.vercel.app",
    "https://lyrics-plus-backend.vercel.app",
];

/// Time limit of one LyricsPlus mirror (the others are asked at the same time).
const MIRROR_TIMEOUT: Duration = Duration::from_secs(5);

/// Per-client provider state: tokens, request spacing, cooldowns.
#[derive(Debug, Default)]
pub(crate) struct State {
    musixmatch: tokio::sync::Mutex<Throttle>,
    musixmatch_token: Mutex<Option<String>>,
    kugou: tokio::sync::Mutex<Throttle>,
    kugou_mid: Mutex<Option<String>>,
}

#[derive(Debug, Default)]
struct Throttle {
    latest_ms: u64,
    blocked_until_ms: u64,
}

impl Throttle {
    /// Waits until `spacing_ms` after the previous request; false while blocked.
    async fn ready(&mut self, spacing_ms: u64) -> bool {
        let now = now_ms();
        if now < self.blocked_until_ms {
            return false;
        }
        if self.latest_ms != 0 && now - self.latest_ms < spacing_ms {
            tokio::time::sleep(Duration::from_millis(spacing_ms - (now - self.latest_ms))).await;
        }
        self.latest_ms = now_ms();
        true
    }
}

/// GET returning the body; `Ok(None)` on 404 (nothing there).
async fn get(http: &reqwest::Client, url: &str, query: &[(&str, String)], headers: &[(&str, &str)]) -> Result<Option<String>, String> {
    let mut req = http.get(url).query(query).timeout(REQUEST_TIMEOUT).header("Accept", "application/json, text/plain, */*");
    for (k, v) in headers {
        req = req.header(*k, *v);
    }
    let resp = req.send().await.map_err(|e| e.to_string())?;
    match resp.status().as_u16() {
        200..=299 => resp.text().await.map(Some).map_err(|e| e.to_string()),
        404 => Ok(None),
        code => Err(format!("HTTP {code}")),
    }
}

async fn get_json(http: &reqwest::Client, url: &str, query: &[(&str, String)], headers: &[(&str, &str)]) -> Result<Option<Value>, String> {
    match get(http, url, query, headers).await? {
        Some(body) => serde_json::from_str(&body).map(Some).map_err(|e| format!("bad JSON: {e}")),
        None => Ok(None),
    }
}

fn s<'a>(v: &'a Value, key: &str) -> Option<&'a str> {
    v.get(key)?.as_str().filter(|s| !s.is_empty())
}

fn f(v: &Value, key: &str) -> Option<f64> {
    let x = v.get(key)?;
    x.as_f64().or_else(|| x.as_str()?.trim().parse().ok())
}

/// A hit carrying the query's own metadata (sources that match server side).
pub(crate) fn query_hit(source: &'static str, q: &Query, sheet: Option<Sheet>) -> Vec<Hit> {
    let Some(sheet) = sheet.filter(|s| !s.is_empty()) else { return Vec::new() };
    vec![Hit {
        source,
        trust: 0,
        sheet,
        instrumental: false,
        title: q.title.clone(),
        artist: q.artist.clone(),
        album: q.album.clone(),
        duration_ms: (q.duration_ms > 0).then_some(q.duration_ms),
        priority: 0,
    }]
}

// --- LRCLIB ----------------------------------------------------------------------------------

fn lrclib_hit(v: &Value) -> Option<Hit> {
    let instrumental = v.get("instrumental").and_then(Value::as_bool).unwrap_or(false);
    let synced = s(v, "syncedLyrics").map(sheet::parse_lrc).filter(|l| !l.is_empty());
    let sheet = match synced {
        Some(lines) => Sheet::Synced(lines),
        None => match s(v, "plainLyrics").map(str::trim).filter(|p| !p.is_empty()) {
            Some(p) => Sheet::Plain(p.to_string()),
            None if instrumental => Sheet::Plain(String::new()),
            None => return None,
        },
    };
    Some(Hit {
        source: "LRCLIB",
        trust: 0,
        sheet,
        instrumental,
        title: s(v, "trackName").unwrap_or("").to_string(),
        artist: s(v, "artistName").unwrap_or("").to_string(),
        album: s(v, "albumName").map(str::to_string),
        duration_ms: f(v, "duration").filter(|d| *d > 0.0).map(|d| (d * 1000.0) as i64),
        priority: 0,
    })
}

/// Exact lookup (with album and duration) plus searches by fields, by
/// "title artist" and by title alone, all at once; the ranking keeps only
/// the hits that really match.
pub(crate) async fn lrclib(http: &reqwest::Client, base: &str, q: &Query) -> Result<Vec<Hit>, String> {
    let headers = [("User-Agent", LRCLIB_UA)];
    let fields = vec![("track_name", q.title.clone()), ("artist_name", q.artist.clone())];
    let mut exact = fields.clone();
    if let Some(a) = &q.album {
        exact.push(("album_name", a.clone()));
    }
    if q.duration_ms > 0 {
        exact.push(("duration", (q.duration_ms / 1000).to_string()));
    }
    let (get_url, search_url) = (format!("{base}/api/get"), format!("{base}/api/search"));
    let both = [("q", format!("{} {}", q.title, q.artist).trim().to_string())];
    let title_only = [("q", q.title.clone())];
    let (a, b, c, d) = tokio::join!(
        get_json(http, &get_url, &exact, &headers),
        get_json(http, &search_url, &fields, &headers),
        get_json(http, &search_url, &both, &headers),
        async {
            if q.title.chars().count() >= 3 {
                get_json(http, &search_url, &title_only, &headers).await
            } else {
                Ok(None)
            }
        },
    );
    let answers = [a, b, c, d];
    if let Some(Err(e)) = answers.iter().find(|r| r.is_err()).filter(|_| answers.iter().all(Result::is_err)) {
        return Err(e.clone());
    }
    let mut seen = std::collections::HashSet::new();
    let mut hits = Vec::new();
    for value in answers.into_iter().filter_map(|r| r.ok().flatten()) {
        let items = match value {
            Value::Array(a) => a,
            one @ Value::Object(_) => vec![one],
            _ => continue,
        };
        for item in items {
            let key = item.get("id").map(|i| i.to_string()).unwrap_or_else(|| format!("{:?}{:?}", s(&item, "trackName"), s(&item, "artistName")));
            if seen.insert(key) {
                hits.extend(lrclib_hit(&item));
            }
        }
    }
    Ok(hits)
}

// --- Musixmatch ------------------------------------------------------------------------------

const MXM_APP: &str = "web-desktop-app-v1.0";
const MXM_SPACING_MS: u64 = 1_500;
const MXM_COOLDOWN_MS: u64 = 600_000;

fn mxm_status(v: &Value) -> i64 {
    v.pointer("/message/header/status_code").and_then(Value::as_i64).unwrap_or(-1)
}

/// The `message.body` of a macro call when it answered 200.
fn answered<'a>(calls: &'a Value, name: &str) -> Option<&'a Value> {
    let call = calls.get(name)?;
    (mxm_status(call) == 200).then(|| call.pointer("/message/body")).flatten()
}

fn seconds(v: f64) -> i64 {
    if v.is_finite() && v > 0.0 { (v * 1000.0) as i64 } else { 0 }
}

/// Musixmatch richsync body (JSON array of `{ts, te, x, l: [{c, o}]}`).
pub(crate) fn richsync(body: &str) -> Option<Vec<Line>> {
    let array: Vec<Value> = serde_json::from_str(body).ok()?;
    let lines: Vec<Line> = array
        .iter()
        .map(|verse| {
            let ts = f(verse, "ts").unwrap_or(0.0);
            let start = seconds(ts);
            let end = seconds(f(verse, "te").unwrap_or(ts)).max(start);
            let tokens = verse.get("l").and_then(Value::as_array).cloned().unwrap_or_default();
            let mut words: Vec<Word> = Vec::new();
            for (i, token) in tokens.iter().enumerate() {
                let Some(text) = token.get("c").and_then(Value::as_str) else { continue };
                let opened = seconds(ts + f(token, "o").unwrap_or(0.0));
                let closed = tokens.get(i + 1).and_then(|n| f(n, "o")).map(|o| seconds(ts + o)).unwrap_or(end).max(opened);
                match words.last_mut() {
                    Some(last) if text.chars().all(char::is_whitespace) => {
                        last.text.push_str(text);
                        last.end = last.end.max(closed);
                    }
                    _ => words.push(Word::new(opened, closed, text)),
                }
            }
            Line { start, end: Some(end), text: s(verse, "x").unwrap_or("").to_string(), words: (!words.is_empty()).then_some(words), background: None }
        })
        .collect();
    (!lines.is_empty()).then_some(lines)
}

/// Musixmatch subtitle body: a JSON array of `{text, time: {total}}` cues, or LRC.
pub(crate) fn subtitles(body: &str) -> Option<Vec<Line>> {
    let raw = body.trim();
    if raw.is_empty() {
        return None;
    }
    let lines = match serde_json::from_str::<Vec<Value>>(raw) {
        Ok(cues) => {
            let starts: Vec<i64> = cues.iter().map(|c| seconds(c.pointer("/time/total").and_then(Value::as_f64).unwrap_or(0.0))).collect();
            cues.iter()
                .enumerate()
                .map(|(i, c)| Line::new(starts[i], starts.get(i + 1).copied(), c.get("text").and_then(Value::as_str).unwrap_or("")))
                .collect()
        }
        Err(_) => sheet::parse_lrc(raw),
    };
    (!lines.is_empty()).then_some(lines)
}

/// A `macro.subtitles.get` answer into a hit (richsync first, then subtitles).
pub(crate) fn musixmatch_hit(calls: &Value) -> Option<Hit> {
    let track = answered(calls, "matcher.track.get")?.get("track")?;
    let quiet = track.get("instrumental").and_then(Value::as_i64) == Some(1);
    let sung = if quiet {
        Vec::new()
    } else {
        let rich = answered(calls, "track.richsync.get").and_then(|b| s(b.get("richsync")?, "richsync_body")).and_then(richsync);
        let subs = || {
            answered(calls, "track.subtitles.get")
                .and_then(|b| b.pointer("/subtitle_list/0/subtitle/subtitle_body")?.as_str())
                .and_then(subtitles)
        };
        sheet::normalize(rich.or_else(subs).unwrap_or_default())
    };
    if sung.is_empty() && !quiet {
        return None;
    }
    let length = track.get("track_length").and_then(Value::as_i64).unwrap_or(0);
    Some(Hit {
        source: "Musixmatch",
        trust: 35,
        sheet: if quiet { Sheet::Plain(String::new()) } else { Sheet::Synced(sung) },
        instrumental: quiet,
        title: s(track, "track_name").unwrap_or("").to_string(),
        artist: s(track, "artist_name").unwrap_or("").to_string(),
        album: s(track, "album_name").map(str::to_string),
        duration_ms: (length > 0).then_some(length * 1000),
        priority: 0,
    })
}

/// Musixmatch's desktop-app API, as the phone uses it: a user token from
/// `token.get`, then `macro.subtitles.get` with the richsync namespace.
pub(crate) async fn musixmatch(http: &reqwest::Client, state: &State, q: &Query) -> Result<Vec<Hit>, String> {
    let headers = [("User-Agent", BROWSER_UA), ("Cookie", "x-mxm-token-guid=")];
    let mut throttle = state.musixmatch.lock().await;
    for renewed in [false, true] {
        if !throttle.ready(MXM_SPACING_MS).await {
            return Err("cooling down after a refusal".into());
        }
        let held = state.musixmatch_token.lock().unwrap_or_else(|e| e.into_inner()).clone();
        let token = match held.filter(|_| !renewed) {
            Some(t) => t,
            None => {
                let answer = get_json(http, &format!("{MUSIXMATCH_URL}/ws/1.1/token.get"), &[("format", "json".into()), ("app_id", MXM_APP.into())], &headers)
                    .await?
                    .ok_or("token.get: no answer")?;
                let status = mxm_status(&answer);
                let token = answer.pointer("/message/body/user_token").and_then(Value::as_str).unwrap_or("").to_string();
                // A run of zeros is a placeholder: the API answers it with decoy lyrics.
                if status != 200 || token.is_empty() || token.contains("UpgradeOnly") || token.chars().all(|c| c == '0') {
                    throttle.blocked_until_ms = now_ms() + MXM_COOLDOWN_MS;
                    return Err(format!("no usable user token (status {status})"));
                }
                *state.musixmatch_token.lock().unwrap_or_else(|e| e.into_inner()) = Some(token.clone());
                token
            }
        };
        let mut params = vec![
            ("format", "json".to_string()),
            ("namespace", "lyrics_richsynched".into()),
            ("subtitle_format", "mxm".into()),
            ("optional_calls", "track.richsync".into()),
            ("app_id", MXM_APP.into()),
            ("usertoken", token),
            ("q_track", q.title.clone()),
            ("q_artist", q.artist.clone()),
        ];
        if let Some(a) = &q.album {
            params.push(("q_album", a.clone()));
        }
        if q.duration_ms > 0 {
            params.push(("q_duration", (q.duration_ms / 1000).to_string()));
        }
        let Some(answer) = get_json(http, &format!("{MUSIXMATCH_URL}/ws/1.1/macro.subtitles.get"), &params, &headers).await? else {
            return Ok(Vec::new());
        };
        let status = mxm_status(&answer);
        let hint = answer.pointer("/message/header/hint").and_then(Value::as_str);
        match status {
            200 => return Ok(answer.pointer("/message/body/macro_calls").and_then(musixmatch_hit).into_iter().collect()),
            401 if hint == Some("renew") && !renewed => continue,
            401 | 402 | 403 | 429 => {
                throttle.blocked_until_ms = now_ms() + MXM_COOLDOWN_MS;
                return Err(format!("refused (status {status})"));
            }
            _ => return Ok(Vec::new()),
        }
    }
    Ok(Vec::new())
}

// --- Kugou -----------------------------------------------------------------------------------

const KUGOU_SPACING_MS: u64 = 1_200;

fn normalized(text: &str) -> String {
    text.chars().filter(|c| c.is_alphanumeric()).flat_map(char::to_lowercase).collect()
}

fn kugou_artists(text: &str) -> Vec<String> {
    text.to_lowercase().replace('、', ",").split(',').map(normalized).filter(|s| !s.is_empty()).collect()
}

fn kugou_artist_matches(candidate: &Value, artist: &str) -> bool {
    let wanted = kugou_artists(artist);
    kugou_artists(s(candidate, "SingerName").unwrap_or(""))
        .iter()
        .any(|s| wanted.iter().any(|w| s == w || s.contains(w.as_str()) || w.contains(s.as_str())))
}

fn kugou_score(candidate: &Value, title: &str, artist: &str, duration_ms: i64) -> i32 {
    let name = normalized(s(candidate, "SongName").unwrap_or(""));
    let target = normalized(title);
    let mut score = if name.is_empty() || target.is_empty() {
        0
    } else if name == target {
        50
    } else if name.contains(&target) || target.contains(&name) {
        25
    } else {
        -20
    };
    if kugou_artist_matches(candidate, artist) {
        score += 30;
    }
    let ms = (f(candidate, "Duration").unwrap_or(0.0) * 1000.0) as i64;
    if duration_ms > 0 && ms > 0 {
        let drift = (ms - duration_ms).abs();
        score += if drift <= 5_000 { 20 } else if drift > 15_000 { -15 } else { 0 };
    }
    score
}

/// Best catalogue entry whose singer matches the artist (never another artist's song).
pub(crate) fn kugou_pick<'a>(candidates: &'a [Value], title: &str, artist: &str, duration_ms: i64) -> Option<&'a Value> {
    candidates
        .iter()
        .filter(|c| kugou_artist_matches(c, artist))
        .max_by_key(|c| kugou_score(c, title, artist, duration_ms))
}

/// Drops the "Title - Artist" card Kugou puts first.
pub(crate) fn drop_intro_card(lines: &mut Vec<Line>, title: &str, artist: &str) {
    let Some(first) = lines.first() else { return };
    let text = normalized(&first.text);
    let (t, a) = (normalized(title), normalized(artist));
    let like = (!t.is_empty() && text.contains(&t)) || (!a.is_empty() && text.contains(&a));
    if !text.is_empty() && first.text.contains(" - ") && like {
        lines.remove(0);
    }
}

/// Kugou's word-level KRC: catalogue search (title, then title + artist),
/// local artist match, KRC candidate by hash; when the catalogue answers
/// empty (it often does), the KRC search by "Artist - Title". Then download
/// and decode.
pub(crate) async fn kugou(http: &reqwest::Client, state: &State, q: &Query) -> Result<Vec<Hit>, String> {
    let mid = {
        let mut slot = state.kugou_mid.lock().unwrap_or_else(|e| e.into_inner());
        slot.get_or_insert_with(|| format!("{:016x}{:016x}", now_ms().wrapping_mul(0x9E37_79B9_7F4A_7C15), std::process::id() as u64 ^ now_ms().rotate_left(17)))
            .clone()
    };
    let cookie = format!("kg_mid={mid}; dfid=1{0}; kg_dfid=1{0}", &mid[..8]);
    let headers = [("User-Agent", BROWSER_UA), ("Cookie", cookie.as_str())];
    let mut throttle = state.kugou.lock().await;
    let search = |keyword: String| {
        async move {
            let params = [
                ("keyword", keyword),
                ("page", "1".into()),
                ("pagesize", "5".into()),
                ("platform", "WebFilter".into()),
                ("userid", "0".into()),
                ("clientver", "2000".into()),
                ("iscorrection", "1".into()),
                ("privilege_filter", "0".into()),
            ];
            let v = get_json(http, "https://songsearch.kugou.com/song_search_v2", &params, &headers).await?;
            Ok::<Vec<Value>, String>(v.and_then(|v| v.pointer("/data/lists")?.as_array().cloned()).unwrap_or_default())
        }
    };
    throttle.ready(KUGOU_SPACING_MS).await;
    let candidates = search(q.title.clone()).await?;
    let mut best = kugou_pick(&candidates, &q.title, &q.artist, q.duration_ms).cloned();
    if best.is_none() && !q.artist.is_empty() {
        throttle.ready(KUGOU_SPACING_MS).await;
        let more = search(format!("{} {}", q.title, q.artist)).await?;
        best = kugou_pick(&more, &q.title, &q.artist, q.duration_ms).cloned();
    }
    let krc_search = |keyword: String, duration_ms: i64, hash: String| {
        async move {
            let params = [
                ("ver", "1".to_string()),
                ("man", "yes".into()),
                ("client", "mobi".into()),
                ("keyword", keyword),
                ("duration", duration_ms.to_string()),
                ("hash", hash),
            ];
            let v = get_json(http, &format!("{KUGOU_URL}/search"), &params, &headers).await?;
            Ok::<Vec<Value>, String>(v.and_then(|v| v.get("candidates")?.as_array().cloned()).unwrap_or_default())
        }
    };
    // (KRC candidate, title, singer, album, duration in ms)
    let chosen = match best {
        Some(best) => {
            let length = (f(&best, "Duration").unwrap_or(0.0) * 1000.0) as i64;
            let song = s(&best, "SongName").unwrap_or(&q.title).to_string();
            throttle.ready(KUGOU_SPACING_MS).await;
            let found = krc_search(song.clone(), length, s(&best, "FileHash").unwrap_or("").to_string()).await?;
            found.into_iter().next().map(|c| (c, song, s(&best, "SingerName").unwrap_or(&q.artist).to_string(), s(&best, "AlbumName").map(str::to_string), length))
        }
        None => {
            // The catalogue search often answers empty; the lyrics search alone
            // ("Artist - Title") lists candidates with their own song, singer and length.
            throttle.ready(KUGOU_SPACING_MS).await;
            let found = krc_search(format!("{} - {}", q.artist, q.title), q.duration_ms, String::new()).await?;
            let wanted = kugou_artists(&q.artist);
            found
                .into_iter()
                .filter(|c| {
                    let singers = kugou_artists(s(c, "singer").unwrap_or(""));
                    singers.iter().any(|s| wanted.iter().any(|w| s == w || s.contains(w.as_str()) || w.contains(s.as_str())))
                })
                .min_by_key(|c| (f(c, "duration").unwrap_or(0.0) as i64 - q.duration_ms).abs())
                .map(|c| {
                    let (song, singer) = (s(&c, "song").unwrap_or(&q.title).to_string(), s(&c, "singer").unwrap_or(&q.artist).to_string());
                    let length = f(&c, "duration").unwrap_or(0.0) as i64;
                    (c, song, singer, None, length)
                })
        }
    };
    let Some((candidate, song, singer, album, length)) = chosen else { return Ok(Vec::new()) };
    let (Some(id), Some(key)) = (candidate.get("id").map(|i| i.as_str().map(str::to_string).unwrap_or_else(|| i.to_string())), s(&candidate, "accesskey")) else {
        return Ok(Vec::new());
    };
    throttle.ready(KUGOU_SPACING_MS).await;
    let download = get_json(
        http,
        &format!("{KUGOU_URL}/download"),
        &[("ver", "1".into()), ("client", "mobi".into()), ("id", id), ("accesskey", key.to_string()), ("fmt", "krc".into()), ("charset", "utf8".into())],
        &headers,
    )
    .await?;
    let Some(plain) = download.as_ref().and_then(|d| s(d, "content")).and_then(krc::decode) else { return Ok(Vec::new()) };
    let mut lines = krc::parse(&plain);
    drop_intro_card(&mut lines, &q.title, &q.artist);
    if lines.is_empty() {
        return Ok(Vec::new());
    }
    Ok(vec![Hit {
        source: "KuGou",
        trust: 25,
        sheet: Sheet::Synced(lines),
        instrumental: false,
        title: song,
        artist: singer.replace('、', ","),
        album,
        duration_ms: (length > 0).then_some(length),
        priority: 0,
    }])
}

// --- Apple timings: lrc.red, BiniLyrics, BetterLyrics ---------------------------------------

async fn ttml_document(http: &reqwest::Client, url: &str) -> Result<Option<Vec<Line>>, String> {
    Ok(get(http, url, &[], &[]).await?.and_then(|body| ttml::parse(&body)))
}

fn simple(value: &str) -> String {
    let folded: String = value.nfd().filter(|c| !unicode_normalization::char::is_combining_mark(*c)).flat_map(char::to_lowercase).collect();
    folded.split(|c: char| !c.is_alphanumeric()).filter(|w| !w.is_empty()).collect::<Vec<_>>().join(" ")
}

fn strip_brackets(title: &str) -> String {
    let mut depth = 0;
    title.chars().filter(|c| {
        match c {
            '(' | '[' => depth += 1,
            ')' | ']' => depth = (depth - 1).max(0),
            _ => return depth == 0,
        }
        false
    }).collect()
}

const VERSION_WORDS: &[&str] = &[
    "live", "remix", "remixed", "mix", "acoustic", "unplugged", "instrumental", "karaoke", "cappella", "acapella", "demo", "edit",
    "version", "cover", "sped", "slowed", "reverb", "nightcore", "lofi", "orchestral", "extended",
];

fn core_of(title: &str) -> String {
    let core = simple(strip_brackets(title).split(" - ").next().unwrap_or(""));
    if core.is_empty() { simple(title) } else { core }
}

fn version_of(title: &str) -> Vec<String> {
    let mut extras: String = String::new();
    let mut depth = 0;
    for c in title.chars() {
        match c {
            '(' | '[' => depth += 1,
            ')' | ']' => {
                depth = (depth - 1).max(0);
                extras.push(' ');
            }
            _ if depth > 0 => extras.push(c),
            _ => {}
        }
    }
    if let Some((_, after)) = title.split_once(" - ") {
        extras.push(' ');
        extras.push_str(after);
    }
    let mut words: Vec<String> = simple(&extras).split(' ').filter(|w| VERSION_WORDS.contains(w)).map(str::to_string).collect();
    words.sort();
    words.dedup();
    words
}

fn artists_of(artist: &str) -> Vec<String> {
    static SPLIT: std::sync::LazyLock<regex::Regex> = std::sync::LazyLock::new(|| {
        regex::Regex::new(r"(?i)\s*(?:,|&|;|/|\s+and\s+|\s+x\s+|\s+with\s+|\s+feat\.?\s+|\s+ft\.?\s+)\s*").unwrap()
    });
    SPLIT.split(artist).map(simple).filter(|s| !s.is_empty()).collect()
}

/// lrc.red search hit with the same title core, the same version tags, a
/// shared artist and a duration within 3 s.
pub(crate) fn lrc_red_pick<'a>(hits: &'a [Value], q: &Query) -> Option<&'a Value> {
    let (title, version, artists) = (core_of(&q.title), version_of(&q.title), artists_of(&q.artist));
    let wanted = q.duration_ms as f64 / 1000.0;
    hits.iter()
        .filter_map(|h| {
            let isrc = s(h, "isrc")?;
            let name = s(h, "title")?;
            let ok_isrc = isrc.len() == 12 && isrc.chars().all(|c| c.is_ascii_alphanumeric());
            if !ok_isrc || core_of(name) != title || version_of(name) != version {
                return None;
            }
            if !artists.is_empty() && !artists_of(s(h, "artist").unwrap_or("")).iter().any(|a| artists.contains(a)) {
                return None;
            }
            let distance = match f(h, "duration") {
                Some(d) if q.duration_ms > 0 => (d - wanted).abs(),
                _ => 0.0,
            };
            (distance <= 3.0).then_some((h, distance))
        })
        .min_by(|a, b| a.1.total_cmp(&b.1))
        .map(|(h, _)| h)
}

/// Trust of Apple Music's own word timings: above KuGou, so they win ties (the
/// phone asks them first) and are never re-timed on another source's lines.
const APPLE_TRUST: i32 = 30;

fn apple(mut hits: Vec<Hit>) -> Vec<Hit> {
    for h in &mut hits {
        h.trust = APPLE_TRUST;
    }
    hits
}

fn apple_hit(source: &'static str, lines: Vec<Line>, meta: &Value, q: &Query, keys: [&str; 4]) -> Vec<Hit> {
    let mut hits = apple(query_hit(source, q, Some(Sheet::Synced(lines))));
    if let Some(h) = hits.first_mut() {
        h.title = s(meta, keys[0]).unwrap_or(&q.title).to_string();
        h.artist = s(meta, keys[1]).unwrap_or(&q.artist).to_string();
        h.album = s(meta, keys[2]).map(str::to_string).or(h.album.take());
        h.duration_ms = f(meta, keys[3]).filter(|d| *d > 0.0).map(|d| (d * 1000.0) as i64).or(h.duration_ms);
    }
    hits
}

/// lrc.red: catalogue search, then the recording's Apple TTML by ISRC.
pub(crate) async fn lrc_red(http: &reqwest::Client, q: &Query) -> Result<Vec<Hit>, String> {
    let found = get_json(http, &format!("{LRC_RED_URL}/search.json"), &[("q", format!("{} {}", q.title, q.artist))], &[]).await?;
    let hits = found.as_ref().and_then(|v| v.get("hits")?.as_array().cloned()).unwrap_or_default();
    let Some(chosen) = lrc_red_pick(&hits, q) else { return Ok(Vec::new()) };
    let isrc = s(chosen, "isrc").unwrap_or("").to_uppercase();
    let Some(lines) = ttml_document(http, &format!("{LRC_RED_URL}/s/{isrc}.ttml")).await? else { return Ok(Vec::new()) };
    Ok(apple_hit("lrc.red", lines, chosen, q, ["title", "artist", "album", "duration"]))
}

/// BiniLyrics: matched on title, artist, album and duration server side.
pub(crate) async fn bini(http: &reqwest::Client, q: &Query) -> Result<Vec<Hit>, String> {
    let mut params = vec![("track", q.title.clone()), ("artist", q.artist.clone())];
    if let Some(a) = &q.album {
        params.push(("album", a.clone()));
    }
    if q.duration_ms > 0 {
        params.push(("duration", (q.duration_ms / 1000).to_string()));
    }
    let found = get_json(http, &format!("{BINI_URL}/"), &params, &[]).await?;
    let Some(first) = found.as_ref().and_then(|v| v.get("results")?.as_array()?.first().cloned()) else { return Ok(Vec::new()) };
    let Some(url) = s(&first, "lyricsUrl").or_else(|| s(&first, "lyrics_url")) else { return Ok(Vec::new()) };
    let Some(lines) = ttml_document(http, url).await? else { return Ok(Vec::new()) };
    Ok(apple_hit("BiniLyrics", lines, &first, q, ["track_name", "artist_name", "album_name", "duration"]))
}

/// BetterLyrics (Apple timings) or its QQ Music karaoke flavour ("Portato").
pub(crate) async fn better_lyrics(http: &reqwest::Client, q: &Query, portato: bool) -> Result<Vec<Hit>, String> {
    let mut params = vec![("s", q.title.clone()), ("a", q.artist.clone())];
    if q.duration_ms > 0 {
        params.push(("d", (q.duration_ms / 1000).to_string()));
    }
    if let Some(a) = &q.album {
        params.push(("al", a.clone()));
    }
    let url = if portato { PORTATO_URL } else { BETTER_URL };
    // Uncached queries need an API key (401): only the cache answers anonymously.
    let body = match get(http, url, &params, &[]).await {
        Err(e) if e == "HTTP 401" => None,
        other => other?,
    };
    let mut sheet = body.as_deref().and_then(formats::parse_any);
    if let Some(Sheet::Synced(lines)) = sheet.as_mut() {
        drop_intro_card(lines, &q.title, &q.artist);
    }
    if portato {
        return Ok(query_hit("BetterLyrics Portato", q, sheet));
    }
    Ok(apple(query_hit("BetterLyrics", q, sheet)))
}

// --- community -------------------------------------------------------------------------------

/// LyricsPlus: every available mirror at once, the first answer wins.
/// Each mirror has its own circuit.
pub(crate) async fn lyrics_plus(http: &reqwest::Client, health: &HealthRegistry, q: &Query) -> Result<Vec<Hit>, String> {
    let mut params = vec![("title", q.title.clone()), ("artist", q.artist.clone())];
    if q.duration_ms > 0 {
        params.push(("duration", (q.duration_ms / 1000).to_string()));
    }
    if let Some(a) = &q.album {
        params.push(("album", a.clone()));
    }
    let mut set = tokio::task::JoinSet::new();
    for mirror in health.ordered(crate::model::SourceKind::Lyrics).into_iter().filter(|u| LYRICS_PLUS_MIRRORS.contains(&u.as_str())) {
        if !health.is_available(&mirror) {
            continue;
        }
        let (http, health, params) = (http.clone(), health.clone(), params.clone());
        set.spawn(async move {
            let start = now_ms();
            let answer = tokio::time::timeout(MIRROR_TIMEOUT, get_json(&http, &format!("{mirror}/v2/lyrics/get"), &params, &[]))
                .await
                .unwrap_or_else(|_| Err("timeout".into()));
            match answer {
                Ok(v) => {
                    health.record_success(&mirror, now_ms().saturating_sub(start));
                    v.as_ref().and_then(formats::lyrics_plus)
                }
                Err(e) => {
                    health.record_failure(&mirror, &e);
                    None
                }
            }
        });
    }
    if set.is_empty() {
        return Err("every mirror is cooling down".into());
    }
    while let Some(done) = set.join_next().await {
        if let Ok(Some(sheet)) = done {
            return Ok(apple(query_hit("LyricsPlus", q, Some(sheet))));
        }
    }
    Ok(Vec::new())
}

/// Unison: lyrics contributed by listeners.
pub(crate) async fn unison(http: &reqwest::Client, q: &Query) -> Result<Vec<Hit>, String> {
    let mut params = vec![("song", q.title.clone()), ("artist", q.artist.clone())];
    if q.duration_ms > 0 {
        params.push(("duration", (q.duration_ms / 1000).to_string()));
    }
    if let Some(a) = &q.album {
        params.push(("album", a.clone()));
    }
    let Some(root) = get_json(http, UNISON_URL, &params, &[]).await? else { return Ok(Vec::new()) };
    if root.get("success").and_then(Value::as_bool) != Some(true) {
        return Ok(Vec::new());
    }
    let Some(data) = root.get("data") else { return Ok(Vec::new()) };
    let Some(text) = s(data, "lyrics") else { return Ok(Vec::new()) };
    let format = s(data, "format").unwrap_or("");
    let sync = s(data, "syncType").or_else(|| s(data, "sync_type")).unwrap_or("");
    let sheet = if format.eq_ignore_ascii_case("ttml") {
        ttml::parse(text).map(Sheet::Synced)
    } else if sync.eq_ignore_ascii_case("plain") {
        Some(Sheet::Plain(text.to_string()))
    } else {
        formats::enhanced_lrc(text).map(Sheet::Synced).or_else(|| formats::parse_any(text))
    };
    let mut hits = query_hit("Unison", q, sheet);
    if let Some(h) = hits.first_mut() {
        h.title = s(data, "song").unwrap_or(&q.title).to_string();
        h.artist = s(data, "artist").unwrap_or(&q.artist).to_string();
        h.album = s(data, "album").map(str::to_string).or(h.album.take());
    }
    Ok(hits)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn query() -> Query {
        Query { title: "Blinding Lights".into(), artist: "The Weeknd".into(), album: None, duration_ms: 200_000, video_id: None }
    }

    #[test]
    fn musixmatch_richsync_and_subtitles() {
        let rich = r#"[{"ts":12.5,"te":15.0,"x":"Hello there world","l":[{"c":"Hello","o":0},{"c":" ","o":0.4},{"c":"there","o":0.5},{"c":" ","o":1.0},{"c":"world","o":1.2}]}]"#;
        let calls = json!({
            "matcher.track.get": {"message": {"header": {"status_code": 200}, "body": {"track": {"track_name": "Blinding Lights", "artist_name": "The Weeknd", "track_length": 200, "instrumental": 0}}}},
            "track.richsync.get": {"message": {"header": {"status_code": 200}, "body": {"richsync": {"richsync_body": rich}}}},
            "track.subtitles.get": {"message": {"header": {"status_code": 404}, "body": ""}}
        });
        let hit = musixmatch_hit(&calls).unwrap();
        let Sheet::Synced(lines) = &hit.sheet else { panic!() };
        let w: Vec<(i64, i64, &str)> = lines[0].words.as_ref().unwrap().iter().map(|w| (w.start, w.end, w.text.as_str())).collect();
        assert_eq!(w, [(12_500, 13_000, "Hello "), (13_000, 13_700, "there "), (13_700, 15_000, "world")]);
        assert_eq!((hit.duration_ms, hit.trust), (Some(200_000), 35));

        let subs = r#"[{"text":"First line","time":{"total":10.5}},{"text":"Second","time":{"total":13.25}}]"#;
        let calls = json!({
            "matcher.track.get": {"message": {"header": {"status_code": 200}, "body": {"track": {"track_name": "X", "artist_name": "Y", "instrumental": 0}}}},
            "track.subtitles.get": {"message": {"header": {"status_code": 200}, "body": {"subtitle_list": [{"subtitle": {"subtitle_body": subs}}]}}}
        });
        let Sheet::Synced(lines) = musixmatch_hit(&calls).unwrap().sheet else { panic!() };
        assert_eq!((lines[0].start, lines[0].end, lines[1].start), (10_500, Some(13_250), 13_250));

        let quiet = json!({"matcher.track.get": {"message": {"header": {"status_code": 200}, "body": {"track": {"track_name": "X", "artist_name": "Y", "instrumental": 1}}}}});
        assert!(musixmatch_hit(&quiet).unwrap().instrumental);
    }

    #[test]
    fn lrclib_entries() {
        let hit = lrclib_hit(&json!({"id": 1, "trackName": "Blinding Lights", "artistName": "The Weeknd", "duration": 200.0,
            "syncedLyrics": "[00:01.00]one\n[00:02.00]two", "plainLyrics": "one\ntwo"})).unwrap();
        assert!(hit.sheet.synced());
        let quiet = lrclib_hit(&json!({"trackName": "A", "artistName": "B", "instrumental": true})).unwrap();
        assert!(quiet.instrumental && quiet.sheet.is_empty());
        assert!(lrclib_hit(&json!({"trackName": "A", "artistName": "B"})).is_none());
    }

    #[test]
    fn kugou_matching() {
        let list = vec![
            json!({"SongName": "Blinding Lights", "SingerName": "Cover Band", "Duration": 200, "FileHash": "A"}),
            json!({"SongName": "Blinding Lights (Remix)", "SingerName": "The Weeknd、ROSALÍA", "Duration": 216, "FileHash": "B"}),
            json!({"SongName": "Blinding Lights", "SingerName": "The Weeknd", "Duration": 200, "FileHash": "C"}),
        ];
        assert_eq!(s(kugou_pick(&list, "Blinding Lights", "The Weeknd", 200_000).unwrap(), "FileHash"), Some("C"));
        assert!(kugou_pick(&list[..1], "Blinding Lights", "The Weeknd", 200_000).is_none());
        let mut lines = vec![Line::new(0, None, "Blinding Lights - The Weeknd"), Line::new(1, None, "I been tryna call")];
        drop_intro_card(&mut lines, "Blinding Lights", "The Weeknd");
        assert_eq!(lines.len(), 1);
    }

    #[test]
    fn lrc_red_matching() {
        let hits = vec![
            json!({"isrc": "USUG12004507", "title": "Blinding Lights (Remix)", "artist": "The Weeknd, ROSALÍA", "duration": 216.1}),
            json!({"isrc": "XXUG11904206", "title": "Blinding Lights", "artist": "Someone", "duration": 200.0}),
            json!({"isrc": "USUG11904206", "title": "Blinding Lights", "artist": "The Weeknd", "duration": 200.046}),
        ];
        assert_eq!(s(lrc_red_pick(&hits, &query()).unwrap(), "isrc"), Some("USUG11904206"));
        let remix = Query { title: "Blinding Lights (Remix)".into(), duration_ms: 216_000, ..query() };
        assert_eq!(s(lrc_red_pick(&hits, &remix).unwrap(), "isrc"), Some("USUG12004507"));
        assert_eq!(version_of("Song (Live at Wembley) - Remastered"), ["live"]);
        assert_eq!(core_of("Perché (feat. X) - Live"), "perche");
    }
}
