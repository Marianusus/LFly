//! Lyrics engine (port of the phone's lyrics engine): every provider is asked
//! at once with an overall time limit, the answers are ranked on title,
//! artist and duration, word-level answers are re-timed on a line-synced
//! guide when they need it, and instrumentals are detected.
//!
//! Providers: YouTube Music's own lyrics for the video, lrc.red, BiniLyrics,
//! BetterLyrics (Apple and QQ timings), LyricsPlus, Unison, KuGou (KRC),
//! LRCLIB and Musixmatch. Each has its own circuit in the health registry.

mod formats;
mod krc;
mod providers;
mod rank;
mod sheet;
mod youtube;

use crate::health::{now_ms, HealthRegistry};
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

/// Time limit of one provider.
const PROVIDER_TIMEOUT: Duration = Duration::from_secs(10);
/// Time limit of the whole lookup.
const TOTAL_TIMEOUT: Duration = Duration::from_secs(12);
/// Once a matching word-level answer is in, how long the others still get.
const WORDED_GRACE: Duration = Duration::from_millis(1_500);
/// Once a matching line-synced answer is in, how long the others still get.
const SYNCED_GRACE: Duration = Duration::from_millis(5_000);

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

/// The providers, in the phone's default priority order.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Provider {
    LrcRed,
    Bini,
    BetterLyrics,
    Portato,
    LyricsPlus,
    Unison,
    YouTube,
    Kugou,
    Lrclib,
    Musixmatch,
}

const PROVIDERS: [Provider; 10] = [
    Provider::LrcRed,
    Provider::Bini,
    Provider::BetterLyrics,
    Provider::Portato,
    Provider::LyricsPlus,
    Provider::Unison,
    Provider::YouTube,
    Provider::Kugou,
    Provider::Lrclib,
    Provider::Musixmatch,
];

impl Provider {
    fn name(self) -> &'static str {
        match self {
            Provider::LrcRed => "lrc.red",
            Provider::Bini => "BiniLyrics",
            Provider::BetterLyrics => "BetterLyrics",
            Provider::Portato => "BetterLyrics Portato",
            Provider::LyricsPlus => "LyricsPlus",
            Provider::Unison => "Unison",
            Provider::YouTube => "YouTube Music",
            Provider::Kugou => "KuGou",
            Provider::Lrclib => "LRCLIB",
            Provider::Musixmatch => "Musixmatch",
        }
    }

    /// The health registry entry; `None` for LyricsPlus, whose mirrors have their own.
    fn url(self, yt: &YtMusic) -> Option<String> {
        Some(match self {
            Provider::LrcRed => providers::LRC_RED_URL.into(),
            Provider::Bini => providers::BINI_URL.into(),
            Provider::BetterLyrics => providers::BETTER_URL.into(),
            Provider::Portato => providers::PORTATO_URL.into(),
            Provider::LyricsPlus => return None,
            Provider::Unison => providers::UNISON_URL.into(),
            Provider::YouTube => YOUTUBE_LYRICS_URL.into(),
            Provider::Kugou => providers::KUGOU_URL.into(),
            Provider::Lrclib => yt.lrclib_url().to_string(),
            Provider::Musixmatch => providers::MUSIXMATCH_URL.into(),
        })
    }

    async fn run(self, yt: &YtMusic, q: &Query) -> Result<Vec<Hit>, String> {
        let http = yt.http();
        match self {
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

/// Asks every available provider at once and returns the ranked hits plus
/// whether the track is an instrumental.
async fn gather(yt: &YtMusic, q: &Query) -> (Vec<Hit>, bool) {
    let health = yt.health_registry().clone();
    let mut set = tokio::task::JoinSet::new();
    for (priority, provider) in PROVIDERS.into_iter().enumerate() {
        let url = provider.url(yt);
        if url.as_deref().is_some_and(|u| !health.is_available(u)) {
            log::debug!("lyrics: {} skipped, circuit open", provider.name());
            continue;
        }
        let (yt, q, health) = (yt.clone(), q.clone(), health.clone());
        set.spawn(async move {
            let start = now_ms();
            let outcome = tokio::time::timeout(PROVIDER_TIMEOUT, provider.run(&yt, &q)).await.unwrap_or_else(|_| Err("timeout".into()));
            let elapsed = now_ms().saturating_sub(start);
            let hits = match outcome {
                Ok(hits) => {
                    if let Some(u) = &url {
                        health.record_success(u, elapsed);
                    }
                    hits
                }
                Err(e) => {
                    log::debug!("lyrics: {} failed: {e}", provider.name());
                    if let Some(u) = &url {
                        health.record_failure(u, &e);
                    }
                    Vec::new()
                }
            };
            log::debug!("lyrics: {} answered {} hit(s) in {elapsed} ms", provider.name(), hits.len());
            hits.into_iter().map(|h| Hit { priority, ..h }).collect::<Vec<_>>()
        });
    }
    let deadline = tokio::time::Instant::now() + TOTAL_TIMEOUT;
    let mut cutoff = deadline;
    let mut gathered: Vec<Hit> = Vec::new();
    while let Ok(Some(done)) = tokio::time::timeout_at(cutoff, set.join_next()).await {
        let Ok(hits) = done else { continue };
        let now = tokio::time::Instant::now();
        if hits.iter().any(|h| h.sheet.worded() && rank::eligible(q, h)) {
            cutoff = cutoff.min(now + WORDED_GRACE);
        } else if hits.iter().any(|h| h.sheet.synced() && rank::eligible(q, h)) {
            cutoff = cutoff.min(now + SYNCED_GRACE);
        }
        gathered.extend(hits);
    }
    set.abort_all();
    let instrumental = rank::instrumental(q, &gathered);
    let mut ranked = rank::rank(q, gathered);
    rank::reshape(&mut ranked);
    (ranked, instrumental)
}

/// The best lyrics for `track` from every provider; `None` when nothing
/// reliable exists and the track is not known to be instrumental.
pub(crate) async fn resolve(yt: &YtMusic, track: &Track) -> Option<Lyrics> {
    let q = query_for(track);
    if q.title.trim().is_empty() {
        return None;
    }
    let (ranked, instrumental) = gather(yt, &q).await;
    log::info!(
        "lyrics for '{}' - '{}': {}",
        q.title,
        q.artist,
        ranked.iter().map(|h| format!("{} ({})", h.source, if h.sheet.worded() { "words" } else if h.sheet.synced() { "lines" } else { "plain" })).collect::<Vec<_>>().join(", ")
    );
    match ranked.into_iter().next() {
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

/// Main artist only, without "- Topic" / "VEVO" noise.
pub fn clean_artist(artist: &str) -> String {
    let a = ARTIST_NOISE_RE.replace_all(artist, "");
    let mut first = a.as_ref();
    for sep in [",", "&", "feat.", "ft.", "x "] {
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
                let r = tokio::time::timeout(PROVIDER_TIMEOUT, p.run(&yt, q)).await.unwrap_or_else(|_| Err("timeout".into()));
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
