//! Lyrics engine (port of the phone's LyricsRepository): the listener's sources, in the
//! listener's order, are all asked at once with an overall time limit and their answers read in
//! that order (order.rs: word by word wins, then lines unless word by word is put first, then the
//! first found; Genius only when nothing had lines). Each source's own candidates are ranked on
//! title, artist and duration; a word-level winner is re-timed on a line-synced answer already in
//! when it needs it; instrumentals are detected.
//!
//! Sources: lrc.red, BiniLyrics, BetterLyrics (Apple and QQ timings), PaxSenix's Spotify and
//! Musixmatch (with the listener's key), LyricsPlus, Unison, the video's captions, YouTube Music's
//! own lyrics, KuGou (KRC), LRCLIB, Musixmatch and Genius. Each has its own circuit in the health
//! registry.

mod formats;
mod krc;
mod more;
pub mod order;
mod providers;
mod rank;
mod sheet;
mod youtube;

use crate::health::{now_ms, HealthRegistry};
pub use order::{LyricsPrefs, SourceInfo, SOURCES};
use order::{pick, Pick, Slot};
use crate::model::{LyricLine, Lyrics, SourceKind, Track, WordTiming};
use crate::YtMusic;
use regex::Regex;
use sheet::{Line, Sheet};
use std::sync::LazyLock;
use std::time::Duration;

pub(crate) use providers::State;

pub const LRCLIB_URL: &str = "https://lrclib.net";
/// Health registry url of YouTube Music's lyrics (apart from its streams).
pub const YOUTUBE_LYRICS_URL: &str = "https://music.youtube.com/lyrics";
/// Health registry url of the videos' captions.
pub const YOUTUBE_CAPTIONS_URL: &str = "https://music.youtube.com/transcript";

/// Time limit of one provider.
const PROVIDER_TIMEOUT: Duration = Duration::from_secs(10);
/// Time limit of the whole lookup.
const TOTAL_TIMEOUT: Duration = Duration::from_secs(12);

/// What the providers are asked for (title and artist already cleaned).
#[derive(Debug, Clone)]
pub(crate) struct Query {
    pub title: String,
    pub artist: String,
    pub album: Option<String>,
    pub duration_ms: i64,
    pub video_id: Option<String>,
}

/// One provider's answer plus what ranks it.
#[derive(Debug, Clone)]
pub(crate) struct Hit {
    pub source: &'static str,
    pub trust: i32,
    pub sheet: Sheet,
    pub instrumental: bool,
    pub title: String,
    pub artist: String,
    pub album: Option<String>,
    pub duration_ms: Option<i64>,
    /// Position of the provider in the phone's default order (ties go to the lower).
    pub priority: usize,
}

/// The providers, in the phone's default priority order (order::SOURCES).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Provider {
    LrcRed,
    Bini,
    BetterLyrics,
    Portato,
    PaxSpotify,
    PaxMusixmatch,
    LyricsPlus,
    Unison,
    Captions,
    YouTube,
    Kugou,
    Lrclib,
    Musixmatch,
    Genius,
}

#[cfg(test)]
const PROVIDERS: [Provider; 14] = [
    Provider::LrcRed,
    Provider::Bini,
    Provider::BetterLyrics,
    Provider::Portato,
    Provider::PaxSpotify,
    Provider::PaxMusixmatch,
    Provider::LyricsPlus,
    Provider::Unison,
    Provider::Captions,
    Provider::YouTube,
    Provider::Kugou,
    Provider::Lrclib,
    Provider::Musixmatch,
    Provider::Genius,
];

impl Provider {
    /// The provider of a Settings source id (order::SOURCES).
    fn of(id: &str) -> Option<Provider> {
        Some(match id {
            "LRC_RED" => Provider::LrcRed,
            "BINI_LYRICS" => Provider::Bini,
            "BETTER_LYRICS" => Provider::BetterLyrics,
            "BETTER_LYRICS_PORTATO" => Provider::Portato,
            "PAXSENIX_SPOTIFY" => Provider::PaxSpotify,
            "PAXSENIX_MUSIXMATCH" => Provider::PaxMusixmatch,
            "LYRICS_PLUS" => Provider::LyricsPlus,
            "UNISON" => Provider::Unison,
            "YOUTUBE_TRANSCRIPT" => Provider::Captions,
            "YOUTUBE_MUSIC" => Provider::YouTube,
            "KUGOU" => Provider::Kugou,
            "LRCLIB" => Provider::Lrclib,
            "MUSIXMATCH" => Provider::Musixmatch,
            "GENIUS" => Provider::Genius,
            _ => return None,
        })
    }

    fn needs_key(self) -> bool {
        matches!(self, Provider::PaxSpotify | Provider::PaxMusixmatch)
    }

    /// Matched on the playing video itself: its answer is not checked against the query.
    fn on_the_video(self) -> bool {
        matches!(self, Provider::YouTube | Provider::Captions)
    }

    fn name(self) -> &'static str {
        match self {
            Provider::LrcRed => "lrc.red",
            Provider::Bini => "BiniLyrics",
            Provider::BetterLyrics => "BetterLyrics",
            Provider::Portato => "BetterLyrics Portato",
            Provider::PaxSpotify => "PaxSenix: Spotify",
            Provider::PaxMusixmatch => "PaxSenix: Musixmatch",
            Provider::LyricsPlus => "LyricsPlus",
            Provider::Unison => "Unison",
            Provider::Captions => "Sottotitoli di YouTube",
            Provider::YouTube => "YouTube Music",
            Provider::Kugou => "KuGou",
            Provider::Lrclib => "LRCLIB",
            Provider::Musixmatch => "Musixmatch",
            Provider::Genius => "Genius",
        }
    }

    /// The health registry entry; `None` for LyricsPlus, whose mirrors have their own.
    fn url(self, yt: &YtMusic) -> Option<String> {
        Some(match self {
            Provider::LrcRed => providers::LRC_RED_URL.into(),
            Provider::Bini => providers::BINI_URL.into(),
            Provider::BetterLyrics => providers::BETTER_URL.into(),
            Provider::Portato => providers::PORTATO_URL.into(),
            Provider::PaxSpotify | Provider::PaxMusixmatch => more::PAXSENIX_URL.into(),
            Provider::LyricsPlus => return None,
            Provider::Unison => providers::UNISON_URL.into(),
            Provider::Captions => YOUTUBE_CAPTIONS_URL.into(),
            Provider::YouTube => YOUTUBE_LYRICS_URL.into(),
            Provider::Kugou => providers::KUGOU_URL.into(),
            Provider::Lrclib => yt.lrclib_url().to_string(),
            Provider::Musixmatch => providers::MUSIXMATCH_URL.into(),
            Provider::Genius => more::GENIUS_URL.into(),
        })
    }

    async fn run(self, yt: &YtMusic, q: &Query, key: Option<&str>) -> Result<Vec<Hit>, String> {
        let http = yt.http();
        match self {
            Provider::PaxSpotify => more::paxsenix_spotify(http, q, key).await,
            Provider::PaxMusixmatch => more::paxsenix_musixmatch(http, q, key).await,
            Provider::Captions => more::captions(yt, q).await,
            Provider::Genius => more::genius(http, q).await,
            Provider::LrcRed => providers::lrc_red(http, q).await,
            Provider::Bini => providers::bini(http, q).await,
            Provider::BetterLyrics => providers::better_lyrics(http, q, false).await,
            Provider::Portato => providers::better_lyrics(http, q, true).await,
            Provider::LyricsPlus => providers::lyrics_plus(http, yt.health_registry(), q).await,
            Provider::Unison => providers::unison(http, q).await,
            Provider::YouTube => {
                let Some(video) = q.video_id.as_deref() else { return Ok(Vec::new()) };
                let sheet = youtube::fetch(yt, video).await?;
                // Matched on the video itself: it carries the query's metadata.
                let mut hits = providers::query_hit(self.name(), q, sheet);
                for h in &mut hits {
                    h.sheet = match std::mem::replace(&mut h.sheet, Sheet::Plain(String::new())) {
                        Sheet::Synced(lines) => Sheet::Synced(sheet::normalize(lines)),
                        plain => plain,
                    };
                }
                Ok(hits)
            }
            Provider::Kugou => providers::kugou(http, yt.lyrics_state(), q).await,
            Provider::Lrclib => providers::lrclib(http, yt.lrclib_url(), q).await,
            Provider::Musixmatch => providers::musixmatch(http, yt.lyrics_state(), q).await,
        }
    }
}

/// Registers every lyrics source in the health registry.
pub(crate) fn register(health: &HealthRegistry, lrclib_base: &str) {
    health.register("LRCLIB", lrclib_base, SourceKind::Lrclib);
    for (name, url) in [
        ("YouTube Music lyrics", YOUTUBE_LYRICS_URL),
        ("lrc.red", providers::LRC_RED_URL),
        ("BiniLyrics", providers::BINI_URL),
        ("BetterLyrics", providers::BETTER_URL),
        ("BetterLyrics Portato", providers::PORTATO_URL),
        ("Unison", providers::UNISON_URL),
        ("KuGou", providers::KUGOU_URL),
        ("Musixmatch", providers::MUSIXMATCH_URL),
        ("PaxSenix", more::PAXSENIX_URL),
        ("YouTube captions", YOUTUBE_CAPTIONS_URL),
        ("Genius", more::GENIUS_URL),
    ] {
        health.register(name, url, SourceKind::Lyrics);
    }
    for mirror in providers::LYRICS_PLUS_MIRRORS {
        let host = mirror.trim_start_matches("https://");
        health.register(&format!("LyricsPlus ({host})"), mirror, SourceKind::Lyrics);
    }
}

/// The query for a track: "Artist - Title" video titles split, decorations
/// ("(Official Video)", "feat. X", "- Topic") removed.
pub(crate) fn query_for(track: &Track) -> Query {
    let (raw_artist, raw_title) = split_artist_title(&track.title, &track.artist);
    let title = clean_title(&raw_title);
    let artist = clean_artist(&raw_artist);
    let album = track
        .album
        .clone()
        .map(|a| a.trim().to_string())
        .filter(|a| !a.is_empty() && !a.eq_ignore_ascii_case("youtube") && !a.eq_ignore_ascii_case(&track.title));
    Query {
        title: if title.is_empty() { raw_title } else { title },
        artist: if artist.is_empty() { raw_artist } else { artist },
        album,
        duration_ms: track.duration_ms.max(0),
        video_id: track.video_id().map(str::to_string),
    }
}

/// One provider asked, with its time limit and its circuit: its hits (none on failure).
async fn ask(yt: &YtMusic, provider: Provider, q: &Query, key: Option<&str>) -> Vec<Hit> {
    let health = yt.health_registry();
    let url = provider.url(yt);
    let start = now_ms();
    let outcome = tokio::time::timeout(PROVIDER_TIMEOUT, provider.run(yt, q, key)).await.unwrap_or_else(|_| Err("timeout".into()));
    let elapsed = now_ms().saturating_sub(start);
    match outcome {
        Ok(hits) => {
            if let Some(u) = &url {
                health.record_success(u, elapsed);
            }
            log::debug!("lyrics: {} answered {} hit(s) in {elapsed} ms", provider.name(), hits.len());
            hits
        }
        Err(e) => {
            log::debug!("lyrics: {} failed: {e}", provider.name());
            if let Some(u) = &url {
                health.record_failure(u, &e);
            }
            Vec::new()
        }
    }
}

/// A provider's best answer for the query: its own best match (the video's sources answer for
/// the video, unchecked).
fn best_of(provider: Provider, q: &Query, hits: Vec<Hit>) -> Option<Hit> {
    if provider.on_the_video() {
        hits.into_iter().find(|h| !h.sheet.is_empty())
    } else {
        rank::rank(q, hits).into_iter().next()
    }
}

/// The listener's sources raced and read in their order (order::pick); the winner, re-timed on
/// a line-synced answer already in when it needs it, and whether the track is instrumental.
async fn race(yt: &YtMusic, q: &Query, prefs: &LyricsPrefs) -> (Option<Hit>, bool) {
    let health = yt.health_registry().clone();
    let enabled = prefs.enabled();
    let genius = enabled.iter().any(|s| s.id == order::GENIUS);
    let racing: Vec<Provider> = enabled.iter().filter(|s| s.id != order::GENIUS).filter_map(|s| Provider::of(s.id)).collect();
    let key = prefs.key().map(str::to_string);
    let mut slots = vec![Slot::Pending; racing.len()];
    let mut found: Vec<Option<Hit>> = vec![None; racing.len()];
    let mut set = tokio::task::JoinSet::new();
    for (i, provider) in racing.iter().copied().enumerate() {
        // A keyed source without the key is not asked at all.
        if provider.needs_key() && key.is_none() {
            slots[i] = Slot::Missed;
            continue;
        }
        if provider.url(yt).as_deref().is_some_and(|u| !health.is_available(u)) {
            log::debug!("lyrics: {} skipped, circuit open", provider.name());
            slots[i] = Slot::Missed;
            continue;
        }
        let (yt, q, key) = (yt.clone(), q.clone(), key.clone());
        set.spawn(async move { (i, ask(&yt, provider, &q, key.as_deref()).await) });
    }
    let mut all: Vec<Hit> = Vec::new();
    let deadline = tokio::time::Instant::now() + TOTAL_TIMEOUT;
    let decision = loop {
        let p = pick(&slots, prefs.words_first);
        if p != Pick::Wait {
            break p;
        }
        match tokio::time::timeout_at(deadline, set.join_next()).await {
            Ok(Some(Ok((i, hits)))) => {
                all.extend(hits.iter().cloned());
                let best = best_of(racing[i], q, hits);
                slots[i] = best.as_ref().map_or(Slot::Missed, |h| Slot::Found { words: h.sheet.worded(), synced: h.sheet.synced() });
                found[i] = best;
            }
            // A task that panicked, or none left, or the time is up: what has not answered missed.
            Ok(Some(Err(_))) => {}
            Ok(None) | Err(_) => {
                for s in slots.iter_mut().filter(|s| **s == Slot::Pending) {
                    *s = Slot::Missed;
                }
            }
        }
    };
    set.abort_all();
    let instrumental = rank::instrumental(q, &all);
    let winner = match decision {
        Pick::Win(i) => found[i].take(),
        Pick::Done { fallback, synced } => {
            if genius && !synced && health.is_available(more::GENIUS_URL) {
                let hits = ask(yt, Provider::Genius, q, None).await;
                best_of(Provider::Genius, q, hits).or_else(|| fallback.and_then(|i| found[i].take()))
            } else {
                fallback.and_then(|i| found[i].take())
            }
        }
        Pick::Wait => None,
    };
    // Word timings that need it follow a line-synced answer's lines.
    let winner = winner.map(|w| {
        let mut sheets: Vec<Hit> = std::iter::once(w).chain(found.iter().flatten().filter(|h| h.sheet.synced() && !h.sheet.worded()).cloned()).collect();
        rank::reshape(&mut sheets);
        sheets.swap_remove(0)
    });
    (winner, instrumental)
}

/// The lyrics for `track` from the listener's sources; `None` when nothing reliable exists and
/// the track is not known to be instrumental.
pub(crate) async fn resolve(yt: &YtMusic, track: &Track) -> Option<Lyrics> {
    let q = query_for(track);
    if q.title.trim().is_empty() {
        return None;
    }
    let prefs = yt.lyrics_prefs();
    let (best, instrumental) = race(yt, &q, &prefs).await;
    log::info!(
        "lyrics for '{}' - '{}': {}",
        q.title,
        q.artist,
        best.as_ref().map_or("nothing".to_string(), |h| format!("{} ({})", h.source, if h.sheet.worded() { "words" } else if h.sheet.synced() { "lines" } else { "plain" }))
    );
    match best {
        Some(best) => Some(to_model(best, instrumental)),
        None if instrumental => Some(Lyrics { instrumental: true, source: "Instrumental".into(), ..Lyrics::default() }),
        None => None,
    }
}

fn lrc_stamp(ms: i64) -> String {
    let ms = ms.max(0);
    format!("{:02}:{:02}.{:02}", ms / 60_000, ms / 1000 % 60, ms % 1000 / 10)
}

/// A hit into the public model: lines with end times and, when the source
/// had them, word timings whose texts join into the line.
fn to_model(hit: Hit, instrumental: bool) -> Lyrics {
    match hit.sheet {
        Sheet::Plain(text) => Lyrics {
            id: String::new(),
            plain_lyrics: Some(text.trim().to_string()).filter(|t| !t.is_empty()),
            synced_lyrics: None,
            lines: Vec::new(),
            instrumental,
            source: hit.source.to_string(),
        },
        Sheet::Synced(mut lines) => {
            sheet::finish(&mut lines);
            lines.retain(|l| !l.text.is_empty());
            let plain = lines.iter().map(|l| l.text.as_str()).collect::<Vec<_>>().join("\n");
            let lrc = lines.iter().map(|l| format!("[{}]{}", lrc_stamp(l.start), l.text)).collect::<Vec<_>>().join("\n");
            Lyrics {
                id: String::new(),
                plain_lyrics: Some(plain).filter(|p| !p.is_empty()),
                synced_lyrics: Some(lrc),
                lines: lines.into_iter().map(model_line).collect(),
                instrumental,
                source: hit.source.to_string(),
            }
        }
    }
}

fn model_line(line: Line) -> LyricLine {
    LyricLine {
        time_ms: line.start,
        end_ms: line.end.filter(|e| *e > line.start),
        text: line.text,
        word_timings: line.words.map(|ws| ws.into_iter().map(|w| WordTiming { text: w.text, start_ms: w.start, end_ms: w.end }).collect()),
        background: line.background.map(|b| b.trim().to_string()).filter(|b| !b.is_empty()),
    }
}

// --- public helpers --------------------------------------------------------------------------

/// Parses LRC text (several stamps per line, Enhanced LRC word tags) into
/// lines sorted by time, with end times and word timings.
pub fn parse_lrc(content: Option<&str>) -> Vec<LyricLine> {
    let Some(content) = content.filter(|c| !c.trim().is_empty()) else { return Vec::new() };
    let mut lines = sheet::parse_lrc(content);
    sheet::finish(&mut lines);
    lines.into_iter().map(model_line).collect()
}

/// Index of the line playing at `position_ms` (last line starting at or before it).
pub fn active_line_index(lines: &[LyricLine], position_ms: i64) -> Option<usize> {
    let idx = lines.partition_point(|l| l.time_ms <= position_ms);
    idx.checked_sub(1)
}

static PIPE_RE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"\|.*$").unwrap());
static BRACKET_TAG_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i)\[[^\]]*?(Official|Video|Audio|Lyric|Visualizer|Clip|HD|4K|Remaster|Music)[^\]]*?\]").unwrap());
static PAREN_TAG_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i)\([^)]*?(Official|Video|Audio|Lyric|Visualizer|Clip|HD|4K|Remaster|Music|Live)[^)]*?\)").unwrap());
static FEAT_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i)\s*\((feat\.|with)[^)]*\)|\s*\[feat\.[^\]]*\]|\s*(feat|ft)\..*$").unwrap());
static DASH_TAG_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i)\s*-\s*(Official|Lyric|Visualizer|Audio|Clip|Remaster).*").unwrap());
static QUOTES_RE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r#"^["']+|["']+$"#).unwrap());
static ARTIST_NOISE_RE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"(?i)\s*-\s*Topic|\s*VEVO|\s*Official").unwrap());

/// Strips video decorations from a title ("| Official Video", "(Lyrics)", "feat. X").
pub fn clean_title(title: &str) -> String {
    let t = PIPE_RE.replace(title, "");
    let t = BRACKET_TAG_RE.replace_all(&t, "");
    let t = PAREN_TAG_RE.replace_all(&t, "");
    let t = FEAT_RE.replace_all(&t, "");
    let t = DASH_TAG_RE.replace_all(&t, "");
    let t = QUOTES_RE.replace_all(t.trim(), "");
    // "Get Lucky (Radio Edit - feat. X)" loses its "feat." tail: close what is left open.
    let mut t = t.trim_end_matches(|c: char| c.is_whitespace() || "-–(".contains(c)).to_string();
    if t.matches('(').count() > t.matches(')').count() {
        t.push(')');
    }
    t
}

/// Main artist only, without "- Topic" / "VEVO" noise. "A x B" collaborations split on the
/// whole word " x " (not inside a name: "Lex Amarni" stays).
pub fn clean_artist(artist: &str) -> String {
    let a = ARTIST_NOISE_RE.replace_all(artist, "");
    let mut first = a.as_ref();
    for sep in [",", "&", "feat.", "ft.", " x ", " X "] {
        if let Some((left, _)) = first.split_once(sep) {
            first = left;
        }
    }
    first.trim().to_string()
}

/// "Artist - Title" video titles give their own artist when the channel name
/// is generic or already the left part. Returns (artist, title).
fn split_artist_title(title: &str, artist: &str) -> (String, String) {
    let (t, a) = (title.trim(), artist.trim());
    if let Some((left, right)) = t.split_once(" - ") {
        let left = left.trim();
        let la = clean_artist(a).to_lowercase();
        let generic = la.is_empty() || la == "youtube" || la == "youtube music";
        if generic || (!la.is_empty() && left.to_lowercase().contains(&la)) {
            return (left.to_string(), right.trim().to_string());
        }
    }
    (a.to_string(), t.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_lrc_with_repeats_and_word_tags() {
        let lrc = "[ar:Daft Punk]\n[00:10.50]Hello\n[00:05][00:20.123]Again\n[00:30.00]<00:30.00>One <00:30.50>more <00:31.00>time\nno stamp";
        let lines = parse_lrc(Some(lrc));
        let times: Vec<i64> = lines.iter().map(|l| l.time_ms).collect();
        assert_eq!(times, [5_000, 10_500, 20_123, 30_000]);
        assert_eq!(lines[0].text, "Again");
        assert_eq!(lines[0].end_ms, Some(10_500));
        let words = lines[3].word_timings.as_ref().unwrap();
        assert_eq!(lines[3].text, "One more time");
        assert_eq!(words.iter().map(|w| w.text.as_str()).collect::<String>(), lines[3].text);
        assert_eq!((words[1].start_ms, words[1].end_ms), (30_500, 31_000));
        assert_eq!(active_line_index(&lines, 12_000), Some(1));
        assert_eq!(active_line_index(&lines, 1_000), None);
    }

    #[test]
    fn cleans_video_titles() {
        assert_eq!(clean_title("Get Lucky (Official Audio) ft. Pharrell Williams, Nile Rodgers"), "Get Lucky");
        assert_eq!(clean_title("One More Time | Official Video"), "One More Time");
        assert_eq!(clean_title("Get Lucky (Radio Edit - feat. Pharrell Williams and Nile Rodgers)"), "Get Lucky (Radio Edit)");
        assert_eq!(clean_artist("Daft Punk - Topic"), "Daft Punk");
        assert_eq!(clean_artist("Lex Amarni, 2muchmotion"), "Lex Amarni");
        assert_eq!(clean_artist("Max Pezzali"), "Max Pezzali");
        assert_eq!(clean_artist("Anuel AA x Karol G"), "Anuel AA");
        assert_eq!(split_artist_title("Daft Punk - Get Lucky", "YouTube"), ("Daft Punk".into(), "Get Lucky".into()));
        assert_eq!(split_artist_title("Daft Punk - Get Lucky", "Daft Punk - Topic"), ("Daft Punk".into(), "Get Lucky".into()));
        assert_eq!(split_artist_title("Bohemian Rhapsody - Live Aid", "Queen"), ("Queen".into(), "Bohemian Rhapsody - Live Aid".into()));
        let q = query_for(&Track { id: "yt_5NV6Rdv1a3I".into(), title: "Get Lucky (feat. Pharrell Williams)".into(), artist: "Daft Punk".into(), album: Some("YouTube".into()), duration_ms: 248_000, ..Track::default() });
        assert_eq!((q.title.as_str(), q.artist.as_str(), q.album, q.video_id.as_deref()), ("Get Lucky", "Daft Punk", None, Some("5NV6Rdv1a3I")));
    }

    #[test]
    fn model_has_end_times_and_joined_words() {
        let hit = Hit {
            source: "lrc.red",
            trust: 0,
            sheet: Sheet::Synced(vec![Line {
                start: 1_000,
                end: Some(2_000),
                text: "I been tryna call".into(),
                words: Some(vec![
                    sheet::Word::new(1_000, 1_200, "I"),
                    sheet::Word::new(1_200, 1_400, "been"),
                    sheet::Word::new(1_400, 1_700, "tryna"),
                    sheet::Word::new(1_700, 2_000, "call"),
                ]),
                background: None,
            }]),
            instrumental: false,
            title: String::new(),
            artist: String::new(),
            album: None,
            duration_ms: None,
            priority: 0,
        };
        let l = to_model(hit, false);
        assert_eq!(l.source, "lrc.red");
        assert_eq!(l.lines[0].end_ms, Some(2_000));
        let words: Vec<&str> = l.lines[0].word_timings.as_ref().unwrap().iter().map(|w| w.text.as_str()).collect();
        assert_eq!(words, ["I ", "been ", "tryna ", "call"]);
        assert_eq!(l.synced_lyrics.as_deref(), Some("[00:01.00]I been tryna call"));
    }

    /// One song (LFLY_LYRICS_ID, title, artist, seconds) through every provider: the first worded
    /// lines' word timings, then what the race picks.
    #[tokio::test]
    #[ignore = "network"]
    async fn live_one_song_words() {
        let yt = YtMusic::new().unwrap();
        let track = Track { id: "yt_gnM6jglakus".into(), title: "the kill 2".into(), artist: "Lex Amarni, 2muchmotion".into(), album: Some("the kill 2".into()), duration_ms: 122_000, ..Track::default() };
        let q = query_for(&track);
        println!("query: {q:?}");
        for p in PROVIDERS {
            let hits = tokio::time::timeout(PROVIDER_TIMEOUT, p.run(&yt, &q, None)).await.unwrap_or_else(|_| Err("timeout".into())).unwrap_or_default();
            for h in hits.iter().take(2) {
                let Sheet::Synced(lines) = &h.sheet else { println!("{:<22} plain", p.name()); continue };
                println!("{:<22} '{}' - '{}' {:?} ms, eligible {}, {} lines, span {:?}", p.name(), h.title, h.artist, h.duration_ms, rank::eligible(&q, h), lines.len(), h.sheet.span_ms());
                for l in lines.iter().filter(|l| l.worded()).take(2) {
                    let w = l.words.as_ref().unwrap();
                    println!("    [{}..{:?}] {:?}", l.start, l.end, w.iter().map(|w| (w.text.as_str(), w.start, w.end)).collect::<Vec<_>>());
                }
            }
        }
        let best = resolve(&yt, &track).await.unwrap();
        println!("RACE: {} ; first worded line: {:?}", best.source, best.lines.iter().find(|l| l.word_timings.is_some()).map(|l| (l.time_ms, l.word_timings.clone())));
    }

    /// Every provider alone on a few real songs: what each one answers.
    #[tokio::test]
    #[ignore = "network"]
    async fn live_each_provider() {
        let yt = YtMusic::new().unwrap();
        let songs = [
            Query { title: "Blinding Lights".into(), artist: "The Weeknd".into(), album: Some("After Hours".into()), duration_ms: 200_000, video_id: Some("fHI8X4OXluQ".into()) },
            Query { title: "Zitti e buoni".into(), artist: "Måneskin".into(), album: None, duration_ms: 194_000, video_id: None },
            Query { title: "Get Lucky".into(), artist: "Daft Punk".into(), album: Some("Random Access Memories".into()), duration_ms: 248_000, video_id: Some("5NV6Rdv1a3I".into()) },
        ];
        for q in &songs {
            println!("== {} - {}", q.title, q.artist);
            for p in PROVIDERS {
                let start = now_ms();
                let r = tokio::time::timeout(PROVIDER_TIMEOUT, p.run(&yt, q, None)).await.unwrap_or_else(|_| Err("timeout".into()));
                let took = now_ms() - start;
                match r {
                    Ok(hits) if hits.is_empty() => println!("  {:<22} nothing ({took} ms)", p.name()),
                    Ok(hits) => {
                        for h in hits.iter().filter(|h| rank::eligible(q, h)).take(1).chain(hits.iter().filter(|h| !rank::eligible(q, h)).take(1)) {
                            let kind = if h.sheet.worded() { "words" } else if h.sheet.synced() { "lines" } else { "plain" };
                            let n = match &h.sheet { Sheet::Synced(l) => l.len(), Sheet::Plain(t) => t.lines().count() };
                            println!("  {:<22} {kind} {n} lines, eligible {}, '{}' - '{}' score {} ({took} ms)", p.name(), rank::eligible(q, h), h.title, h.artist, rank::score(q, h));
                        }
                    }
                    Err(e) => println!("  {:<22} ERROR {e} ({took} ms)", p.name()),
                }
            }
        }
    }
}
