//! Artist credit parsing (port of the Kotlin `ArtistCredits` object).

use crate::json::{self, Obj};
use crate::model::{ArtistCredit, ArtistRole};
use regex::Regex;
use std::sync::LazyLock;
use unicode_normalization::char::is_combining_mark;
use unicode_normalization::UnicodeNormalization;

const ARTIST_PAGE_TYPE: &str = "MUSIC_PAGE_TYPE_ARTIST";

static DURATION_RE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^(\d{1,2}:)?\d{1,2}:\d{2}$").unwrap());
static STREAM_COUNT_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i)(\d[\d.,]*)\s*([KMB])?\s*(plays|views)").unwrap());
static TOPIC_RE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"(?i)^(.+?)\s+-\s+topic$").unwrap());
static NON_ALNUM_RE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"[^a-z0-9]+").unwrap());
static SPACES_RE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"\s+").unwrap());

/// Split markers in free-form artist text. A comma never splits ("Tyler, The
/// Creator"); `&`, `x` and `/` only split with spaces around them ("R&B", "AC/DC").
/// The Kotlin version uses look-aheads; here the trailing space is consumed
/// instead, which gives the same tokens because every token is trimmed.
static TEXT_MARKER: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(concat!(
        r"(?i)(",
        r"\s+feat\.?(?:\s|$)",
        r"|\s+ft\.?(?:\s|$)",
        r"|\s+featuring(?:\s|$)",
        r"|\s+prod\.?(?:\s|$)",
        r"|\s+&(?:\s+|$)",
        r"|\s*[×;]\s*",
        r"|\s+/\s+",
        r"|\s+[xX]\s",
        r")"
    ))
    .unwrap()
});

const KIND_WORDS: &[&str] = &[
    "song", "songs", "video", "videos", "album", "albums", "single", "singles", "ep", "eps", "playlist",
    "playlists", "artist", "artists",
];
const FEATURE_WORDS: &[&str] = &["feat", "featuring", "ft", "with", "prod", "produced", "producer"];

/// Lowercase, accent-free, punctuation-free key used for de-duplication.
pub fn normalize(value: &str) -> String {
    let stripped: String = value.trim().nfd().filter(|c| !is_combining_mark(*c)).collect();
    let lowered = stripped.to_lowercase().replace('&', " and ");
    NON_ALNUM_RE.replace_all(&lowered, " ").trim().to_string()
}

/// Drops YouTube's auto-generated "Artist - Topic" channel suffix.
fn clean_name(value: &str) -> String {
    TOPIC_RE.replace(value, "$1").trim().to_string()
}

fn is_noise(value: &str) -> bool {
    let text = value.trim();
    if text.is_empty() || matches!(text, "•" | "·" | "," | "&") {
        return true;
    }
    if DURATION_RE.is_match(text) || STREAM_COUNT_RE.is_match(text) {
        return true;
    }
    KIND_WORDS.contains(&text.to_lowercase().as_str())
}

fn credit_key(c: &ArtistCredit) -> String {
    let n = normalize(&c.name);
    if !n.is_empty() {
        return n;
    }
    c.artist_id().map(str::to_string).unwrap_or_else(|| c.name.clone())
}

/// Credits from an InnerTube text object. Only runs that point at an artist
/// page (artist page type, `UC...` id or `/channel/...` url) become credits.
pub fn from_runs(text: Option<&Obj>) -> Vec<ArtistCredit> {
    let mut out: Vec<ArtistCredit> = Vec::new();
    for run in json::runs(text) {
        let name = clean_name(json::string(run, "text").unwrap_or_default().trim());
        if name.is_empty() || is_noise(&name) {
            continue;
        }
        let Some(browse) = json::path(run, &["navigationEndpoint", "browseEndpoint"]) else { continue };
        let browse_id = json::string(browse, "browseId");
        let page_type = json::page_type(Some(browse));
        let canonical = json::string(browse, "canonicalBaseUrl").or_else(|| json::string(browse, "canonicalUrl"));
        let from_canonical = canonical.as_deref().and_then(json::channel_from_url);
        let from_browse = browse_id.clone().filter(|b| b.starts_with("UC"));
        let is_artist = page_type.as_deref() == Some(ARTIST_PAGE_TYPE) || from_browse.is_some() || from_canonical.is_some();
        if !is_artist {
            continue;
        }
        let credit = ArtistCredit {
            name: name.clone(),
            browse_id,
            channel_id: from_canonical.or(from_browse),
            role: ArtistRole::Primary,
        };
        let key = credit_key(&credit);
        if let Some(prev) = out.iter_mut().find(|c| credit_key(c) == key) {
            if prev.browse_id.is_none() {
                prev.browse_id = credit.browse_id;
            }
            if prev.channel_id.is_none() {
                prev.channel_id = credit.channel_id;
            }
        } else {
            out.push(credit);
        }
    }
    out
}

#[derive(PartialEq)]
enum Marker {
    Featured,
    With,
    Producer,
    Ampersand,
    Other,
}

fn marker_kind(marker: &str) -> Marker {
    let v = marker.trim().to_lowercase();
    if v.starts_with("prod") {
        Marker::Producer
    } else if v == "with" {
        Marker::With
    } else if v.starts_with("feat") || v.starts_with("ft") || v == "featuring" {
        Marker::Featured
    } else if v == "&" || v == "and" {
        Marker::Ampersand
    } else {
        Marker::Other
    }
}

/// Splits free-form artist text ("A feat. B & C", "A x B", "A; B") into credits.
pub fn from_text(raw: Option<&str>) -> Vec<ArtistCredit> {
    let original = raw.unwrap_or("").trim();
    if original.is_empty() {
        return Vec::new();
    }
    let replaced: String = original
        .chars()
        .map(|c| if matches!(c, '(' | ')' | '[' | ']') { ' ' } else { c })
        .collect();
    let text = SPACES_RE.replace_all(&replaced, " ").trim().to_string();

    let mut out: Vec<ArtistCredit> = Vec::new();
    let mut emit = |value: &str, role: ArtistRole| {
        let trimmed = value.trim().trim_matches(|c: char| matches!(c, ',' | ';' | '/' | '&' | '×' | ' '));
        let name = clean_name(trimmed);
        if name.is_empty() || FEATURE_WORDS.contains(&name.to_lowercase().as_str()) || is_noise(&name) {
            return;
        }
        let credit = ArtistCredit { name: name.clone(), role, ..ArtistCredit::default() };
        let mut key = normalize(&name);
        if key.is_empty() {
            key = name.to_lowercase();
        }
        if !out.iter().any(|c| {
            let k = normalize(&c.name);
            (if k.is_empty() { c.name.to_lowercase() } else { k }) == key
        }) {
            out.push(credit);
        }
    };

    let mut role = ArtistRole::Primary;
    let mut last = 0usize;
    let mut current = String::new();
    for m in TEXT_MARKER.find_iter(&text) {
        let before = &text[last..m.start()];
        current.push_str(before);
        let marker = m.as_str();
        let kind = marker_kind(marker);

        // Keep band and duo names together ("Earth, Wind & Fire", "Simon & Garfunkel")
        // unless both sides are clearly full names or the & follows a featured credit.
        if kind == Marker::Ampersand {
            let before_text = before.trim();
            let after_start = m.end();
            let next = TEXT_MARKER.find_at(&text, after_start);
            let after_text = text[after_start..next.map(|n| n.start()).unwrap_or(text.len())].trim();
            let single_word_pair = role == ArtistRole::Primary
                && !before_text.is_empty()
                && !before_text.contains(' ')
                && !after_text.is_empty()
                && !after_text.contains(' ');
            let comma_band =
                role == ArtistRole::Primary && before.contains(',') && !after_text.is_empty() && !after_text.contains(' ');
            if after_text.is_empty() || single_word_pair || comma_band {
                current.push_str(marker);
                last = after_start;
                continue;
            }
        }

        emit(&current, role);
        current.clear();
        role = match kind {
            Marker::Featured | Marker::With => ArtistRole::Featured,
            Marker::Producer => ArtistRole::Producer,
            Marker::Ampersand | Marker::Other => role,
        };
        last = m.end();
    }
    current.push_str(&text[last.min(text.len())..]);
    emit(&current, role);
    out
}

/// Every credit joined with ", ", in order.
pub fn display_name(credits: &[ArtistCredit]) -> String {
    credits
        .iter()
        .map(|c| c.name.trim())
        .filter(|n| !n.is_empty())
        .collect::<Vec<_>>()
        .join(", ")
}

/// First credit that carries a navigable id.
pub fn first_artist_id(credits: &[ArtistCredit]) -> Option<String> {
    credits.iter().find_map(|c| c.artist_id().map(str::to_string))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn names(v: &[ArtistCredit]) -> Vec<&str> {
        v.iter().map(|c| c.name.as_str()).collect()
    }

    #[test]
    fn splits_featured_and_keeps_bands() {
        let c = from_text(Some("Daft Punk feat. Pharrell Williams & Nile Rodgers"));
        assert_eq!(names(&c), ["Daft Punk", "Pharrell Williams", "Nile Rodgers"]);
        assert_eq!(c[1].role, ArtistRole::Featured);
        assert_eq!(names(&from_text(Some("Earth, Wind & Fire"))), ["Earth, Wind & Fire"]);
        assert_eq!(names(&from_text(Some("Simon & Garfunkel"))), ["Simon & Garfunkel"]);
        assert_eq!(names(&from_text(Some("Tyler, The Creator"))), ["Tyler, The Creator"]);
        assert_eq!(names(&from_text(Some("AC/DC"))), ["AC/DC"]);
        assert_eq!(names(&from_text(Some("Calvin Harris x Dua Lipa"))), ["Calvin Harris", "Dua Lipa"]);
        assert_eq!(names(&from_text(Some("Daft Punk - Topic"))), ["Daft Punk"]);
    }

    #[test]
    fn normalizes_accents() {
        assert_eq!(normalize("Beyoncé & JAY-Z"), "beyonce and jay z");
    }
}
