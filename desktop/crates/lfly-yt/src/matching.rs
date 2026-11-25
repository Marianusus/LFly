//! A song known only by its name (imported from Spotify, synced from the phone without a YouTube
//! id) found again on YouTube Music: the result that is the same recording, not the first one. The
//! first result is often a remix, a live take or a cover; the length, the exact title and the
//! artist tell the original apart.

use std::collections::HashMap;
use std::sync::{LazyLock, Mutex};

use crate::model::Track;

/// Words that make a title another version of the song.
const VERSION_WORDS: &[&str] = &[
    "remix", "mix", "live", "edit", "version", "acoustic", "instrumental", "cover", "karaoke", "sped up", "slowed", "reverb", "remake", "bootleg", "rework", "mashup", "nightcore", "8d", "extended", "unplugged",
];

fn norm(s: &str) -> String {
    use unicode_normalization::UnicodeNormalization;
    s.nfd()
        .filter(|c| !unicode_normalization::char::is_combining_mark(*c))
        .flat_map(char::to_lowercase)
        .map(|c| if c.is_alphanumeric() { c } else { ' ' })
        .collect::<String>()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}

/// The title without what is in brackets or after " - " (featurings, tags).
fn core_title(s: &str) -> String {
    let mut out = String::new();
    let mut depth = 0;
    for ch in s.chars() {
        match ch {
            '(' | '[' => depth += 1,
            ')' | ']' => depth = (depth - 1).max(0),
            _ if depth == 0 => out.push(ch),
            _ => {}
        }
    }
    let out = out.split(" - ").next().unwrap_or("").to_string();
    norm(&out)
}

fn versions(s: &str) -> Vec<&'static str> {
    let n = format!(" {} ", norm(s));
    VERSION_WORDS.iter().copied().filter(|w| n.contains(&format!(" {w} "))).collect()
}

/// How sure [got] is the recording named by [title], [artist] and [duration_ms] (0: unknown).
pub fn score(title: &str, artist: &str, duration_ms: i64, got: &Track) -> i64 {
    let mut score = 0;
    if duration_ms > 0 && got.duration_ms > 0 {
        let diff = (duration_ms - got.duration_ms).abs();
        score += if diff <= 3_000 {
            60
        } else if diff <= 8_000 {
            20
        } else if diff > 20_000 {
            -50
        } else {
            -10
        };
    }
    let (wt, gt) = (norm(title), norm(&got.title));
    if !wt.is_empty() && wt == gt {
        score += 50;
    } else if !core_title(title).is_empty() && core_title(title) == core_title(&got.title) {
        score += 30;
    } else if !wt.is_empty() && (gt.contains(&wt) || wt.contains(&gt)) {
        score += 5;
    } else {
        score -= 30;
    }
    // A remix, a live take, a cover the original's title does not name: another recording.
    let wanted = versions(title);
    if versions(&got.title).iter().any(|v| !wanted.contains(v)) {
        score -= 45;
    }
    let got_artist = norm(&got.artist);
    let artists: Vec<String> = artist.split([',', '&']).map(norm).filter(|a| !a.is_empty()).collect();
    if got_artist.is_empty() {
        // A result whose artist was not read (some layouts of the signed-in search): unknown,
        // neither for nor against.
    } else if artists.iter().any(|a| got_artist.contains(a.as_str()) || a.contains(&got_artist)) {
        score += 30;
    } else if !artists.is_empty() {
        score -= 40;
    }
    if got.is_video {
        score -= 10;
    }
    score
}

/// The best of [found] for the song, if any is close enough to be it.
pub fn best(title: &str, artist: &str, duration_ms: i64, found: Vec<Track>) -> Option<Track> {
    found.into_iter().filter(|t| t.video_id().is_some()).map(|t| (score(title, artist, duration_ms, &t), t)).filter(|(s, _)| *s >= 30).max_by_key(|(s, _)| *s).map(|(_, t)| t)
}

/// When nothing is surely the song: the best of [found] that is at least by its artist and no
/// other version of it (a slowed, a remix, a live take the title does not name).
pub fn fallback(title: &str, artist: &str, duration_ms: i64, found: &[Track]) -> Option<Track> {
    let wanted = versions(title);
    let artists: Vec<String> = artist.split([',', '&']).map(norm).filter(|a| !a.is_empty()).collect();
    found
        .iter()
        .filter(|t| t.video_id().is_some())
        .filter(|t| !versions(&t.title).iter().any(|v| !wanted.contains(v)))
        .filter(|t| {
            let a = norm(&t.artist);
            a.is_empty() || artists.is_empty() || artists.iter().any(|w| a.contains(w.as_str()))
        })
        .max_by_key(|t| score(title, artist, duration_ms, t))
        .cloned()
}

/// What a song known by name was found as, kept for the session so it plays the same each time.
static FOUND: LazyLock<Mutex<HashMap<String, String>>> = LazyLock::new(|| Mutex::new(HashMap::new()));

pub(crate) fn remembered(key: &str) -> Option<String> {
    FOUND.lock().ok()?.get(key).cloned()
}

pub(crate) fn remember(key: &str, video_id: &str) {
    if let Ok(mut m) = FOUND.lock() {
        m.insert(key.to_string(), video_id.to_string());
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn t(title: &str, artist: &str, secs: i64) -> Track {
        // A valid video id of eleven characters.
        let id = format!("{:0>11}", format!("v{secs}{}", artist.len()));
        Track { id, title: title.into(), artist: artist.into(), duration_ms: secs * 1000, ..Default::default() }
    }

    #[test]
    fn the_original_beats_a_remix_found_first() {
        let found = vec![t("Saint Tropez (Remix)", "Florin Salam", 189), t("Saint Tropez", "Florin Salam", 211), t("Saint Tropez", "Alt Cineva", 211)];
        let got = best("Saint Tropez", "Florin Salam", 210_808, found).unwrap();
        assert_eq!(got.duration_ms, 211_000);
        assert_eq!(got.artist, "Florin Salam");
    }

    #[test]
    fn a_remix_asked_for_is_kept() {
        let found = vec![t("Saint Tropez", "Florin Salam", 211), t("Saint Tropez (Remix)", "Florin Salam", 189)];
        assert_eq!(best("Saint Tropez (Remix)", "Florin Salam", 189_000, found).unwrap().duration_ms, 189_000);
    }

    #[test]
    fn the_fallback_skips_other_versions_and_artists() {
        let found = vec![t("Otilia - Bilionera (slowed+reverb)", "GOLDEN XML LOFI", 205), t("Bilionera (slowed)", "Otilia", 0), t("Bilionera Official Video", "Otilia", 0)];
        assert_eq!(fallback("Bilionera", "Otilia", 185_202, &found).unwrap().title, "Bilionera Official Video");
    }

    #[test]
    fn a_cover_or_another_song_is_not_taken() {
        assert!(best("Saint Tropez", "Florin Salam", 210_808, vec![t("Saint Tropez", "Cover Band", 300), t("Monaco", "Florin Salam", 200)]).is_none());
        // Featurings in brackets do not make another song.
        assert!(best("Zalele", "Florin Salam", 200_000, vec![t("Zalele (feat. Asu & Ticy)", "Florin Salam", 201)]).is_some());
    }

    /// The search as LFly makes it for a song known by name: what comes first, what is picked.
    #[tokio::test]
    #[ignore = "network"]
    async fn live_saint_tropez() {
        let yt = crate::YtMusic::new().unwrap();
        for (q, title, artist, ms) in [("Saint Tropez Florin Salam", "Saint Tropez", "Florin Salam", 210_808), ("Bilionera Otilia", "Bilionera", "Otilia", 185_202)] {
            let found = yt.search_songs(q, 10).await.unwrap();
            for t in &found {
                println!("{:>4}  {} | {} | {} s | {}", score(title, artist, ms, t), t.title, t.artist, t.duration_ms / 1000, t.id);
            }
            println!("picked: {:?}", best(title, artist, ms, found).map(|t| (t.title, t.artist, t.duration_ms / 1000)));
        }
    }
}
