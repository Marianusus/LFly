//! Ranking, eligibility, instrumental detection (port of the phone's
//! `LyricsRank.kt`) and re-timing of word-level sheets against a line-synced
//! guide (`Shape.kt`).

use super::sheet::{self, Line, Sheet, Word};
use super::{Hit, Query};
use std::collections::HashSet;

const CLOSE_ENOUGH: i64 = 3;
const WAY_OFF: i64 = 10;
const TITLE: i32 = 40;
const ARTIST: i32 = 30;
const ALBUM: i32 = 15;
const SYNCED: i32 = 200;
const WORDED: i32 = 400;
const DRIFTED: i32 = 50;
const TRUNCATED: i32 = 500;
/// Word-level sources at or above this trust are not re-timed.
pub(crate) const TRUSTED: i32 = 25;

/// Lowercase letters and digits only (accents folded), after dropping
/// bracketed parts and a " - " suffix.
pub(crate) fn undecorated(text: &str) -> String {
    use unicode_normalization::UnicodeNormalization;
    let folded: String = text.nfd().filter(|c| !unicode_normalization::char::is_combining_mark(*c)).collect();
    let before_dash = folded.split(" - ").next().unwrap_or("");
    let mut depth = 0i32;
    let mut out = String::new();
    for c in before_dash.chars() {
        match c {
            '(' | '[' => depth += 1,
            ')' | ']' => depth = (depth - 1).max(0),
            _ if depth == 0 && c.is_alphanumeric() => out.extend(c.to_lowercase()),
            _ => {}
        }
    }
    out
}

pub(crate) fn alike(left: &str, right: &str) -> bool {
    let (l, r) = (undecorated(left), undecorated(right));
    if l.is_empty() || r.is_empty() {
        return false;
    }
    if l == r {
        return true;
    }
    let (short, long) = if l.chars().count() <= r.chars().count() { (l, r) } else { (r, l) };
    long.contains(&short) && short.chars().count() * 2 >= long.chars().count()
}

pub(crate) fn artists_alike(left: &str, right: &str) -> bool {
    if alike(left, right) {
        return true;
    }
    let names = |s: &str| s.split([',', '&', ';']).map(str::trim).filter(|x| !x.is_empty()).map(str::to_string).collect::<Vec<_>>();
    let right = names(right);
    names(left).iter().any(|a| right.iter().any(|b| alike(a, b)))
}

/// Title and artist alike, and within [`WAY_OFF`] seconds when both durations are known.
pub(crate) fn matches(query: &Query, hit: &Hit) -> bool {
    if !alike(&hit.title, &query.title) || !artists_alike(&hit.artist, &query.artist) {
        return false;
    }
    match hit.duration_ms {
        Some(d) => query.duration_ms <= 0 || (d / 1000 - query.duration_ms / 1000).abs() <= WAY_OFF,
        None => true,
    }
}

pub(crate) fn eligible(query: &Query, hit: &Hit) -> bool {
    matches(query, hit) && !hit.sheet.is_empty() && !low_quality(&hit.sheet)
}

/// A matching provider says instrumental and no matching provider has words.
pub(crate) fn instrumental(query: &Query, hits: &[Hit]) -> bool {
    let matching: Vec<&Hit> = hits.iter().filter(|h| matches(query, h)).collect();
    matching.iter().any(|h| h.instrumental) && matching.iter().all(|h| h.sheet.is_empty())
}

pub(crate) fn score(query: &Query, hit: &Hit) -> i32 {
    let mut score = hit.trust;
    if let Some(d) = hit.duration_ms {
        let drift = (d / 1000 - query.duration_ms / 1000).abs();
        if drift <= CLOSE_ENOUGH {
            score += 100 - drift as i32 * 10;
        } else if drift > WAY_OFF {
            score -= DRIFTED;
        }
    }
    if alike(&hit.title, &query.title) {
        score += TITLE;
    }
    if artists_alike(&hit.artist, &query.artist) {
        score += ARTIST;
    }
    if let (Some(a), Some(b)) = (&hit.album, &query.album) {
        if alike(a, b) {
            score += ALBUM;
        }
    }
    if hit.sheet.synced() {
        score += SYNCED;
    }
    if hit.sheet.worded() {
        score += WORDED;
    }
    if query.duration_ms > 0 && hit.sheet.span_ms().is_some_and(|s| (s as f64) < query.duration_ms as f64 * 0.6) {
        score -= TRUNCATED;
    }
    score
}

/// All-caps lines with stretched letters ("NOOOO") on a sixth of the sheet.
fn low_quality(sheet: &Sheet) -> bool {
    let Sheet::Synced(lines) = sheet else { return false };
    let texts: Vec<&str> = lines.iter().flat_map(|l| std::iter::once(l.text.as_str()).chain(l.background.as_deref())).collect();
    let noisy = texts.iter().filter(|t| stretched_shout(t)).count();
    noisy >= 3 && noisy * 6 >= texts.len()
}

fn stretched_shout(text: &str) -> bool {
    let (mut upper, mut lower) = (false, false);
    let (mut previous, mut run, mut longest) = (None, 0, 0);
    for c in text.chars().filter(|c| c.is_alphabetic()) {
        upper |= c.is_uppercase();
        lower |= c.is_lowercase();
        let folded = c.to_lowercase().next();
        if previous == folded {
            run += 1;
        } else {
            previous = folded;
            run = 1;
        }
        longest = longest.max(run);
    }
    upper && !lower && longest >= 3
}

fn fingerprint(sheet: &Sheet) -> String {
    let trim = |t: &str| t.chars().filter(|c| c.is_alphanumeric()).flat_map(char::to_lowercase).collect::<String>();
    match sheet {
        Sheet::Plain(t) => format!("plain:{}", trim(t)),
        Sheet::Synced(lines) => {
            let text: String = lines.iter().flat_map(|l| std::iter::once(l.text.as_str()).chain(l.background.as_deref())).map(trim).collect();
            format!("synced:{}:{text}", sheet.worded())
        }
    }
}

/// Eligible hits, best first (score, then provider priority), without duplicates.
pub(crate) fn rank(query: &Query, hits: Vec<Hit>) -> Vec<Hit> {
    let mut scored: Vec<(i32, Hit)> = hits.into_iter().filter(|h| eligible(query, h)).map(|h| (score(query, &h), h)).collect();
    scored.sort_by(|a, b| b.0.cmp(&a.0).then(a.1.priority.cmp(&b.1.priority)).then_with(|| a.1.title.cmp(&b.1.title)));
    let mut seen = HashSet::new();
    scored.into_iter().map(|(_, h)| h).filter(|h| seen.insert(fingerprint(&h.sheet))).collect()
}

/// Re-times untrusted word-level hits (without background vocals) on the best
/// line-synced hit's lines.
pub(crate) fn reshape(hits: &mut [Hit]) {
    let Some(guide) = hits.iter().find(|h| h.sheet.synced() && !h.sheet.worded()).map(|h| h.sheet.clone()) else { return };
    for hit in hits.iter_mut() {
        let layered = matches!(&hit.sheet, Sheet::Synced(l) if l.iter().any(|l| l.background.is_some()));
        if hit.sheet.worded() && hit.trust < TRUSTED && !layered {
            if let Some(conformed) = conform(&hit.sheet, &guide) {
                hit.sheet = conformed;
            }
        }
    }
}

// --- shape -----------------------------------------------------------------------------------

const LEAST: usize = 6;
const MATCHED: f64 = 0.7;
const KEPT: f64 = 0.5;
const SEATED: f64 = 0.9;
const LIMIT: usize = 3000;
const CELLS: usize = 2_000_000;
/// Length given to each unmatched word before the first matched one.
const LEADING_MS: i64 = 250;

struct Sung {
    start: i64,
    end: i64,
    key: String,
}

/// A token of a line: its start (char index) and lowercase key.
struct Slot {
    at: usize,
    key: String,
}

fn wide(c: char) -> bool {
    matches!(c as u32, 0x3040..=0x30FF | 0x3400..=0x4DBF | 0x4E00..=0x9FFF | 0xAC00..=0xD7AF | 0xF900..=0xFAFF)
}

fn tokens(text: &str) -> Vec<Slot> {
    let mut slots = Vec::new();
    let mut open: Option<Slot> = None;
    for (at, c) in text.chars().enumerate() {
        if wide(c) {
            slots.extend(open.take());
            slots.push(Slot { at, key: c.to_lowercase().collect() });
            continue;
        }
        if matches!(c, '\'' | '\u{2019}' | '\u{FF07}' | '\u{2018}' | '\u{00B4}' | '`') {
            continue;
        }
        if c.is_alphanumeric() {
            open.get_or_insert(Slot { at, key: String::new() }).key.extend(c.to_lowercase());
        } else {
            slots.extend(open.take());
        }
    }
    slots.extend(open);
    slots
}

fn spread(text: &str, start: i64, end: i64) -> Vec<Sung> {
    let slots = tokens(text);
    let total: usize = slots.iter().map(|s| s.key.chars().count()).sum();
    if total == 0 {
        return Vec::new();
    }
    let span = (end - start).max(0) as f64;
    let mut passed = 0;
    slots
        .into_iter()
        .map(|s| {
            let from = start + (span * passed as f64 / total as f64) as i64;
            passed += s.key.chars().count();
            Sung { start: from, end: start + (span * passed as f64 / total as f64) as i64, key: s.key }
        })
        .collect()
}

fn sung(lines: &[Line]) -> Vec<Sung> {
    let mut out = Vec::new();
    for (i, line) in lines.iter().enumerate() {
        match line.words.as_ref().filter(|w| !w.is_empty()) {
            Some(words) => {
                for w in words {
                    out.extend(tokens(&w.text).into_iter().map(|s| Sung { start: w.start, end: w.end.max(w.start), key: s.key }));
                }
            }
            None => {
                let until = line.end.or(lines.get(i + 1).map(|l| l.start)).unwrap_or(line.start).max(line.start);
                out.extend(spread(&line.text, line.start, until));
            }
        }
    }
    out
}

fn akin(left: &str, right: &str) -> bool {
    if left == right {
        return true;
    }
    let (short, long) = if left.len() <= right.len() { (left, right) } else { (right, left) };
    short.chars().count() >= 4 && long.chars().count() - short.chars().count() <= 2 && long.starts_with(short)
}

/// Longest common subsequence of the two token lists, as index pairs.
fn paired(left: &[&str], right: &[&str]) -> Vec<(usize, usize)> {
    let (n, m) = (left.len(), right.len());
    let mut table = vec![vec![0u16; m + 1]; n + 1];
    for r in (0..n).rev() {
        for c in (0..m).rev() {
            table[r][c] = if akin(left[r], right[c]) { table[r + 1][c + 1] + 1 } else { table[r + 1][c].max(table[r][c + 1]) };
        }
    }
    let (mut r, mut c) = (0, 0);
    let mut pairs = Vec::new();
    while r < n && c < m {
        if akin(left[r], right[c]) {
            pairs.push((r, c));
            r += 1;
            c += 1;
        } else if table[r + 1][c] >= table[r][c + 1] {
            r += 1;
        } else {
            c += 1;
        }
    }
    pairs
}

fn hints(guide: &[Line], slotted: &[Vec<Slot>]) -> Vec<(i64, i64)> {
    let mut out = Vec::new();
    for (i, line) in guide.iter().enumerate() {
        let slots = &slotted[i];
        if slots.is_empty() {
            continue;
        }
        let until = line.end.or(guide.get(i + 1).map(|l| l.start)).unwrap_or(line.start).max(line.start);
        let span = (until - line.start).max(0) as f64;
        let total: usize = slots.iter().map(|s| s.key.chars().count().max(1)).sum();
        let mut passed = 0;
        for s in slots {
            let start = line.start + (span * passed as f64 / total as f64) as i64;
            passed += s.key.chars().count().max(1);
            out.push((start, line.start + (span * passed as f64 / total as f64) as i64));
        }
    }
    out
}

fn filled(spans: &[Option<(i64, i64)>], hints: &[(i64, i64)]) -> Vec<(i64, i64)> {
    let mut settled: Vec<(i64, i64)> = Vec::new();
    let mut index = 0;
    while index < spans.len() {
        if let Some(s) = spans[index] {
            settled.push(s);
            index += 1;
            continue;
        }
        let mut until = index;
        while until < spans.len() && spans[until].is_none() {
            until += 1;
        }
        let after = settled.last().map(|s| s.1);
        let before = spans.get(until).copied().flatten().map(|s| s.0);
        match (after, before) {
            // Leading words the worded sheet never sang: just before the first matched one
            // (the guide's own line start can be far earlier in another edit).
            (None, Some(b)) => {
                let count = (until - index) as i64;
                for k in 0..count {
                    let start = (b - (count - k) * LEADING_MS).max(0);
                    settled.push((start, (start + LEADING_MS).min(b).max(start)));
                }
            }
            (Some(a), Some(b)) if b > a => {
                let count = (until - index) as i64;
                let span = b - a;
                for step in 0..count {
                    settled.push((a + span * step / count, a + span * (step + 1) / count));
                }
            }
            _ => {
                for at in index..until {
                    let mut start = hints.get(at).map(|h| h.0).or(after).unwrap_or(0);
                    let mut end = hints.get(at).map(|h| h.1).or(after).unwrap_or(0);
                    if let Some(a) = after {
                        start = start.max(a);
                        end = end.max(start);
                    }
                    if let Some(b) = before {
                        start = start.min(b);
                        end = end.min(b).max(start);
                    }
                    let floor = settled.last().map(|s| s.1).unwrap_or(start);
                    let s = start.max(floor);
                    settled.push((s, end.max(s).max(floor)));
                }
            }
        }
        index = until;
    }
    settled
}

/// The guide's lines timed word by word with the worded sheet's timings;
/// `None` when the two sheets don't line up well enough.
pub(crate) fn conform(worded: &Sheet, guide: &Sheet) -> Option<Sheet> {
    let (Sheet::Synced(worded), Sheet::Synced(guide)) = (worded, guide) else { return None };
    if worded.is_empty() || guide.is_empty() {
        return None;
    }
    let sung = sung(worded);
    let slotted: Vec<Vec<Slot>> = guide.iter().map(|l| tokens(&l.text)).collect();
    let places: Vec<(usize, usize)> = slotted.iter().enumerate().flat_map(|(line, s)| (0..s.len()).map(move |i| (line, i))).collect();
    if sung.len() < LEAST || places.len() < LEAST || sung.len() > LIMIT || places.len() > LIMIT || sung.len() * places.len() > CELLS {
        return None;
    }
    let left: Vec<&str> = sung.iter().map(|s| s.key.as_str()).collect();
    let right: Vec<&str> = places.iter().map(|(l, s)| slotted[*l][*s].key.as_str()).collect();
    let pairs = paired(&left, &right);
    let mut held = vec![false; slotted.len()];
    for (_, place) in &pairs {
        held[places[*place].0] = true;
    }
    let counted = slotted.iter().filter(|s| !s.is_empty()).count();
    let anchored = held.iter().zip(&slotted).filter(|(h, s)| **h && !s.is_empty()).count();
    if pairs.len() < LEAST
        || (pairs.len() as f64) < MATCHED * places.len() as f64
        || (pairs.len() as f64) < KEPT * sung.len() as f64
        || (anchored as f64) < SEATED * counted as f64
    {
        return None;
    }
    let mut spans: Vec<Option<(i64, i64)>> = vec![None; places.len()];
    for (word, place) in &pairs {
        spans[*place] = Some((sung[*word].start, sung[*word].end));
    }
    let spans = filled(&spans, &hints(guide, &slotted));
    let mut lines = Vec::new();
    let mut cursor = 0;
    for (i, line) in guide.iter().enumerate() {
        let slots = &slotted[i];
        if slots.is_empty() {
            continue;
        }
        let mine = &spans[cursor..cursor + slots.len()];
        cursor += slots.len();
        let chars: Vec<char> = line.text.chars().collect();
        let words: Vec<Word> = slots
            .iter()
            .enumerate()
            .map(|(j, slot)| {
                let from = if j == 0 { 0 } else { slot.at.min(chars.len()) };
                let until = slots.get(j + 1).map(|s| s.at.max(from)).unwrap_or(chars.len()).min(chars.len());
                let (s, e) = mine[j];
                Word::new(s, e.max(s), chars[from..until].iter().collect::<String>())
            })
            .collect();
        let start = words.first()?.start;
        let end = words.iter().map(|w| w.end).max().unwrap_or(start);
        lines.push(Line { start, end: Some(end.max(start)), text: line.text.clone(), words: Some(words), background: None });
    }
    let lines = sheet::normalize(lines);
    (lines.len() >= 2).then_some(Sheet::Synced(lines))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn query() -> Query {
        Query { title: "Get Lucky".into(), artist: "Daft Punk".into(), album: Some("Random Access Memories".into()), duration_ms: 248_000, video_id: None }
    }

    fn hit(source: &'static str, title: &str, artist: &str, duration: Option<i64>, sheet: Sheet) -> Hit {
        Hit { source, trust: 0, sheet, instrumental: false, title: title.into(), artist: artist.into(), album: None, duration_ms: duration, priority: 0 }
    }

    fn synced(n: i64, step: i64, words: bool) -> Sheet {
        Sheet::Synced(
            (0..n)
                .map(|i| {
                    let start = 10_000 + i * step;
                    let mut l = Line::new(start, Some(start + step), format!("line number {i} here"));
                    if words {
                        l.words = Some(vec![Word::new(start, start + step / 2, "line ".to_string()), Word::new(start + step / 2, start + step, format!("number {i} here"))]);
                    }
                    l
                })
                .collect(),
        )
    }

    #[test]
    fn accents_and_case_do_not_matter() {
        assert!(alike("ZITTI E BUONI", "Zitti e buoni"));
        assert!(artists_alike("Maneskin", "Måneskin"));
        assert!(alike("Perché", "Perche (Remastered)"));
    }

    #[test]
    fn ranking_prefers_matching_worded_full_sheets() {
        let hits = vec![
            hit("Other", "Get Lucky", "Someone Else", Some(248_000), synced(20, 10_000, true)),
            hit("Plain", "Get Lucky", "Daft Punk", Some(248_000), Sheet::Plain("words".into())),
            hit("Lines", "Get Lucky (Radio Edit)", "Daft Punk & Pharrell Williams", Some(250_000), synced(20, 10_000, false)),
            hit("Short", "Get Lucky", "Daft Punk", Some(248_000), synced(5, 10_000, true)),
            hit("Words", "Get Lucky", "Daft Punk", Some(258_000), synced(20, 10_000, true)),
            hit("Far", "Get Lucky", "Daft Punk", Some(300_000), synced(20, 10_000, true)),
            hit("Dupe", "Get Lucky", "Daft Punk", None, synced(20, 10_000, false)),
        ];
        let ranked = rank(&query(), hits);
        let order: Vec<&str> = ranked.iter().map(|h| h.source).collect();
        // Words: 400+200+30+40 = 670 (10 s off: no bonus); Far is 52 s off: another edit; Lines: 80+40+30+200 = 350; Short: 770-500 = 270;
        // Plain: 170. Dupe has the same text as Lines; Other is another artist.
        assert_eq!(order, ["Words", "Lines", "Short", "Plain"]);
    }

    #[test]
    fn instrumental_needs_a_matching_flag_and_no_text() {
        let mut quiet = hit("Musixmatch", "Get Lucky", "Daft Punk", Some(248_000), Sheet::Plain(String::new()));
        quiet.instrumental = true;
        let other = hit("LRCLIB", "Get Lucky", "Nobody", None, Sheet::Plain("la la".into()));
        assert!(instrumental(&query(), &[quiet.clone(), other]));
        let words = hit("LRCLIB", "Get Lucky", "Daft Punk", None, Sheet::Plain("la la".into()));
        assert!(!instrumental(&query(), &[quiet, words]));
    }

    #[test]
    fn shouting_sheets_are_dropped() {
        let lines = (0..6).map(|i| Line::new(i * 1000, None, if i % 2 == 0 { "NOOOO WAY" } else { "fine words" })).collect();
        assert!(!eligible(&query(), &hit("X", "Get Lucky", "Daft Punk", None, Sheet::Synced(lines))));
    }

    #[test]
    fn conform_moves_word_timings_onto_the_guide_text() {
        let guide = Sheet::Synced(vec![
            Line::new(1_000, Some(3_000), "Like the legend of the phoenix"),
            Line::new(3_000, Some(5_000), "Our ends were beginnings"),
        ]);
        let words = |start: i64, text: &str| -> Line {
            let ws: Vec<Word> = text.split(' ').enumerate().map(|(i, w)| Word::new(start + i as i64 * 300, start + i as i64 * 300 + 250, format!("{w} "))).collect();
            Line { start, end: None, text: text.into(), words: Some(ws), background: None }
        };
        let worded = Sheet::Synced(vec![words(1_200, "like the legend of the phoenix"), words(3_100, "our ends were beginnings")]);
        let Some(Sheet::Synced(lines)) = conform(&worded, &guide) else { panic!("no conform") };
        assert_eq!(lines[0].text, "Like the legend of the phoenix");
        let w = lines[1].words.as_ref().unwrap();
        assert_eq!((w[0].start, w[0].text.as_str()), (3_100, "Our "));
        assert_eq!(w[3].start, 4_000);

        // "The club" sung as "A club": the unmatched first word sits right before "club".
        let guide = Sheet::Synced(vec![
            Line::new(9_840, Some(57_000), "The club isn't the best place"),
            Line::new(57_758, Some(60_000), "So the bar is where I go"),
        ]);
        let worded = Sheet::Synced(vec![words(55_222, "A club isn't the best place"), words(57_758, "So the bar is where I go")]);
        let Some(Sheet::Synced(lines)) = conform(&worded, &guide) else { panic!("no conform") };
        let w = lines[0].words.as_ref().unwrap();
        assert_eq!((lines[0].start, w[0].text.as_str(), w[1].start), (55_272, "The ", 55_522));
    }
}
