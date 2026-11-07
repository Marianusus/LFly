//! The other lyrics formats the providers answer with (port of the phone's
//! `LyricsSupport.kt`): Apple TTML, Enhanced ("A2") LRC, QQ/NetEase karaoke
//! ranges, LyricsPlus JSON and the JSON envelopes small APIs wrap them in.

use super::sheet::{self, Line, Sheet, Word};
use regex::Regex;
use serde_json::Value;
use std::sync::LazyLock;

/// A timed run of text (a syllable or a word, maybe with its spacing).
pub(crate) struct Run {
    pub start: i64,
    pub end: i64,
    pub raw: String,
}

fn unspaced_script(c: char) -> bool {
    matches!(c, '\u{3400}'..='\u{9FFF}' | '\u{3040}'..='\u{30FF}' | '\u{3000}'..='\u{303F}' | '\u{FF00}'..='\u{FFEF}' | '\u{0E00}'..='\u{0E7F}')
}

/// Glues syllable runs into words, whitespace being the word boundary.
/// Returns the words (trimmed) and the line text.
pub(crate) fn runs_to_words(runs: &[Run], spacing_is_explicit: bool) -> (Vec<Word>, String) {
    let glue = spacing_is_explicit || runs.iter().any(|r| r.raw.chars().any(char::is_whitespace));
    let mut words = Vec::new();
    let mut current = String::new();
    let (mut start, mut end) = (0i64, 0i64);
    let flush = |current: &mut String, words: &mut Vec<Word>, start: i64, end: i64| {
        let text = current.trim();
        if !text.is_empty() {
            words.push(Word::new(start, start.max(end), text));
        }
        current.clear();
    };
    for run in runs {
        let content = run.raw.trim();
        if content.is_empty() {
            flush(&mut current, &mut words, start, end);
            continue;
        }
        let joins = glue
            && !current.is_empty()
            && !run.raw.starts_with(char::is_whitespace)
            && !current.chars().last().is_some_and(unspaced_script)
            && !content.chars().next().is_some_and(unspaced_script);
        if !joins {
            flush(&mut current, &mut words, start, end);
        }
        if current.is_empty() {
            start = run.start;
            end = run.start;
        }
        current.push_str(content);
        end = end.max(run.start.max(run.end));
        if !glue || run.raw.ends_with(char::is_whitespace) {
            flush(&mut current, &mut words, start, end);
        }
    }
    flush(&mut current, &mut words, start, end);
    let text = words.iter().map(|w| w.text.as_str()).collect::<Vec<_>>().join(" ");
    (words, text)
}

// --- Apple TTML ------------------------------------------------------------------------------

pub(crate) mod ttml {
    use super::*;
    use roxmltree::Node;

    enum Piece {
        Text(String),
        Timed(String, i64, i64),
    }

    /// TTML clock values: `12.5`, `12.5s`, `1:02.3`, `1:00:02.3`, `500ms`.
    pub(crate) fn time(value: Option<&str>) -> Option<i64> {
        let raw = value?.trim();
        if raw.is_empty() {
            return None;
        }
        if let Some(ms) = raw.strip_suffix("ms") {
            return ms.parse::<f64>().ok().map(|v| v as i64);
        }
        let parts: Vec<f64> = raw.trim_end_matches('s').split(':').map(|p| p.parse::<f64>().ok()).collect::<Option<_>>()?;
        let seconds = match parts.as_slice() {
            [s] => *s,
            [m, s] => m * 60.0 + s,
            [h, m, s] => h * 3600.0 + m * 60.0 + s,
            _ => return None,
        };
        Some((seconds * 1000.0).round() as i64)
    }

    fn attr<'a>(node: Node<'a, '_>, local: &str) -> Option<&'a str> {
        node.attributes().find(|a| a.name() == local).map(|a| a.value())
    }

    fn has_timed_child(node: Node) -> bool {
        node.children().filter(Node::is_element).any(|c| attr(c, "begin").is_some_and(|b| !b.is_empty()) || has_timed_child(c))
    }

    fn text_of(node: Node) -> String {
        node.descendants().filter(Node::is_text).filter_map(|n| n.text()).collect()
    }

    fn collect(node: Node, lead: &mut Vec<Piece>, backing: &mut Vec<Piece>, in_bg: bool) {
        for child in node.children() {
            if child.is_element() {
                let role = attr(child, "role").unwrap_or("");
                if role == "x-translation" || role == "x-roman" {
                    continue;
                }
                let bg = in_bg || role == "x-bg";
                let (begin, end) = (time(attr(child, "begin")), time(attr(child, "end")));
                match (begin, end) {
                    (Some(b), Some(e)) if !has_timed_child(child) => {
                        let piece = Piece::Timed(text_of(child), b, e);
                        if bg { backing.push(piece) } else { lead.push(piece) }
                    }
                    _ => collect(child, lead, backing, bg),
                }
            } else if let Some(text) = child.text().filter(|t| !t.is_empty()) {
                let piece = Piece::Text(text.to_string());
                if in_bg { backing.push(piece) } else { lead.push(piece) }
            }
        }
    }

    fn merge(pieces: &[Piece]) -> (Vec<Word>, String) {
        let mut words = Vec::new();
        let mut current = String::new();
        let (mut start, mut end, mut timed) = (0i64, 0i64, false);
        fn flush(current: &mut String, words: &mut Vec<Word>, start: i64, end: i64, timed: &mut bool) {
            let text = current.trim();
            if !text.is_empty() && *timed {
                words.push(Word::new(start, start.max(end), text));
            }
            current.clear();
            *timed = false;
        }
        for piece in pieces {
            match piece {
                Piece::Text(t) if t.trim().is_empty() => flush(&mut current, &mut words, start, end, &mut timed),
                Piece::Text(t) => {
                    if timed {
                        current.push_str(t);
                    }
                }
                Piece::Timed(t, b, e) => {
                    if t.trim().is_empty() {
                        continue;
                    }
                    if t.starts_with(char::is_whitespace) {
                        flush(&mut current, &mut words, start, end, &mut timed);
                    }
                    if current.is_empty() {
                        start = *b;
                    }
                    current.push_str(t.trim());
                    end = *e;
                    timed = true;
                    if t.ends_with(char::is_whitespace) {
                        flush(&mut current, &mut words, start, end, &mut timed);
                    }
                }
            }
        }
        flush(&mut current, &mut words, start, end, &mut timed);
        let text = words.iter().map(|w| w.text.as_str()).collect::<Vec<_>>().join(" ");
        (words, text)
    }

    fn line_from(p: Node) -> Option<Line> {
        let (mut lead, mut backing) = (Vec::new(), Vec::new());
        collect(p, &mut lead, &mut backing, false);
        let (words, text) = merge(&lead);
        let (bg_words, bg_text) = merge(&backing);
        let begin = time(attr(p, "begin"));
        let end = time(attr(p, "end"));
        let mut line = if words.is_empty() {
            let text = text_of(p).trim().to_string();
            let begin = begin?;
            if text.is_empty() {
                return None;
            }
            Line::new(begin, end.filter(|e| *e > begin), text)
        } else {
            let first = words[0].start;
            let last = words.last().unwrap().end;
            Line { start: begin.unwrap_or(first).min(first), end: Some(end.unwrap_or(last)), text, words: Some(words), background: None }
        };
        if !bg_words.is_empty() {
            line.background = Some(bg_text);
        }
        Some(line)
    }

    /// Lines of an Apple Music style TTML document; `None` when nothing parses.
    pub(crate) fn parse(raw: &str) -> Option<Vec<Line>> {
        let doc = roxmltree::Document::parse(raw).ok()?;
        let mut lines: Vec<Line> =
            doc.descendants().filter(|n| n.is_element() && n.tag_name().name() == "p").filter_map(line_from).collect();
        lines.sort_by_key(|l| l.start);
        (!lines.is_empty()).then_some(lines)
    }
}

// --- Enhanced LRC ----------------------------------------------------------------------------

static ELRC_LINE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^\[(\d{1,3}):(\d{2})[.:](\d{2,3})\](.*)$").unwrap());
static ELRC_WORD: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"<(\d{1,3}):(\d{2})[.:](\d{2,3})>([^<]*)").unwrap());

fn elrc_stamp(m: &str, s: &str, f: &str) -> i64 {
    let frac: i64 = f.parse().unwrap_or(0);
    m.parse::<i64>().unwrap_or(0) * 60_000 + s.parse::<i64>().unwrap_or(0) * 1_000 + if f.len() == 3 { frac } else { frac * 10 }
}

/// Enhanced LRC with a stamp before every word; `None` when no line has word stamps.
pub(crate) fn enhanced_lrc(raw: &str) -> Option<Vec<Line>> {
    struct Row {
        at: i64,
        words: Vec<(i64, String)>,
        plain: String,
    }
    let mut rows: Vec<Row> = raw
        .lines()
        .filter_map(|l| ELRC_LINE.captures(l.trim()))
        .map(|c| Row {
            at: elrc_stamp(&c[1], &c[2], &c[3]),
            words: ELRC_WORD.captures_iter(&c[4]).map(|w| (elrc_stamp(&w[1], &w[2], &w[3]), w[4].to_string())).collect(),
            plain: c[4].trim().to_string(),
        })
        .collect();
    rows.sort_by_key(|r| r.at);
    if !rows.iter().any(|r| !r.words.is_empty()) {
        return None;
    }
    let mut lines = Vec::new();
    for (i, row) in rows.iter().enumerate() {
        let next = rows.get(i + 1).map(|r| r.at);
        if row.words.is_empty() {
            let text = decode(&row.plain);
            if !text.is_empty() {
                lines.push(Line::new(row.at, next, text));
            }
            continue;
        }
        let line_end = next.unwrap_or(row.words.last().unwrap().0 + 800);
        let runs: Vec<Run> = row
            .words
            .iter()
            .enumerate()
            .map(|(j, (at, text))| Run { start: *at, end: row.words.get(j + 1).map(|w| w.0).unwrap_or(line_end), raw: decode(text) })
            .collect();
        let (words, text) = runs_to_words(&runs, false);
        if let (Some(first), Some(last)) = (words.first(), words.last()) {
            lines.push(Line { start: row.at.min(first.start), end: Some(last.end), text, words: Some(words), background: None });
        }
    }
    (!lines.is_empty()).then_some(lines)
}

static NUM_ENTITY: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"&#(x[0-9a-fA-F]+|\d+);").unwrap());

/// HTML entities found in LRC bodies.
pub(crate) fn decode(text: &str) -> String {
    if !text.contains('&') {
        return text.to_string();
    }
    let t = NUM_ENTITY.replace_all(text, |c: &regex::Captures| {
        let v = &c[1];
        let n = match v.strip_prefix('x') {
            Some(h) => u32::from_str_radix(h, 16).ok(),
            None => v.parse().ok(),
        };
        n.and_then(char::from_u32).map(String::from).unwrap_or_default()
    });
    t.replace("&apos;", "'").replace("&quot;", "\"").replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
}

// --- QQ (QRC) / NetEase (YRC) karaoke ranges -------------------------------------------------

static KARAOKE_LINE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^\[(\d{1,8}),(\d{1,8})\](.*)$").unwrap());
static KARAOKE_PREFIX: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"\((\d{1,8}),(\d{1,8})(?:,\d{1,8})?\)([^()]*)").unwrap());
static KARAOKE_SUFFIX: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"([^()]*)\((\d{1,8}),(\d{1,8})(?:,\d{1,8})?\)").unwrap());
static KARAOKE_TIME: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"\(\d{1,8},\d{1,8}(?:,\d{1,8})?\)").unwrap());
static KARAOKE_CONTENT: LazyLock<Regex> = LazyLock::new(|| Regex::new(r#"(?is)LyricContent\s*=\s*"(.*)"\s*/>"#).unwrap());
static KARAOKE_HINT: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"\(\d{1,8},\d{1,8}").unwrap());

/// `[lineStart,lineDuration]word(start,duration)...` (QRC, suffix stamps) or
/// `(start,duration)word` (YRC, prefix stamps), times absolute in ms.
pub(crate) fn karaoke(raw: &str) -> Option<Vec<Line>> {
    let rows = KARAOKE_CONTENT
        .captures(raw)
        .map(|c| c[1].replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&"))
        .unwrap_or_else(|| raw.to_string());
    let mut lines: Vec<Line> = rows
        .lines()
        .filter_map(|source| {
            let c = KARAOKE_LINE.captures(source.trim())?;
            let start: i64 = c[1].parse().ok()?;
            let duration: i64 = c[2].parse().ok()?;
            let body = &c[3];
            let num = |s: &str| s.parse::<i64>().unwrap_or(0);
            let prefixed: Vec<Run> = KARAOKE_PREFIX
                .captures_iter(body)
                .map(|w| Run { start: num(&w[1]), end: num(&w[1]) + num(&w[2]), raw: w[3].to_string() })
                .collect();
            let suffixed: Vec<Run> = KARAOKE_SUFFIX
                .captures_iter(body)
                .map(|w| Run { start: num(&w[2]), end: num(&w[2]) + num(&w[3]), raw: w[1].to_string() })
                .collect();
            let p = runs_to_words(&prefixed, false);
            let s = runs_to_words(&suffixed, false);
            let (words, _) = if p.1.len() >= s.1.len() { p } else { s };
            let first = words.first()?.start;
            let text = decode(&KARAOKE_TIME.replace_all(body, "")).trim().to_string();
            (!text.trim().is_empty() && !super::krc::is_credit(&text)).then(|| Line {
                start: start.min(first),
                end: (duration > 0).then_some(start + duration),
                text,
                words: Some(words),
                background: None,
            })
        })
        .collect();
    lines.sort_by_key(|l| l.start);
    (!lines.is_empty()).then_some(lines)
}

// --- LyricsPlus ------------------------------------------------------------------------------

fn num(v: &Value, key: &str) -> Option<i64> {
    let x = v.get(key)?;
    x.as_i64().or_else(|| x.as_f64().map(|f| f as i64)).or_else(|| x.as_str()?.parse().ok())
}

/// LyricsPlus `/v2/lyrics/get` JSON: lines with syllables (`syllabus`).
pub(crate) fn lyrics_plus(root: &Value) -> Option<Sheet> {
    let array = root.get("lyrics").or_else(|| root.get("data"))?.as_array()?;
    let mut lines = Vec::new();
    for line in array {
        let Some(start) = num(line, "time") else { continue };
        let runs: Vec<Run> = line
            .get("syllabus")
            .or_else(|| line.get("syllables"))
            .and_then(Value::as_array)
            .map(|syl| {
                syl.iter()
                    .filter_map(|s| {
                        let at = num(s, "time")?;
                        Some(Run { start: at, end: at + num(s, "duration").unwrap_or(0), raw: s.get("text")?.as_str()?.to_string() })
                    })
                    .collect()
            })
            .unwrap_or_default();
        let text = line.get("text").and_then(Value::as_str).map(str::trim).unwrap_or("");
        let (words, merged) = if runs.is_empty() { (Vec::new(), String::new()) } else { runs_to_words(&runs, true) };
        if let (Some(first), Some(last)) = (words.first(), words.last()) {
            let (s, e) = (first.start, last.end);
            lines.push(Line { start: start.min(s), end: Some(e), text: merged, words: Some(words), background: None });
        } else if !text.is_empty() {
            let end = num(line, "duration").filter(|d| *d > 0).map(|d| start + d);
            lines.push(Line::new(start, end, text));
        }
    }
    lines.sort_by_key(|l| l.start);
    if lines.is_empty() {
        return None;
    }
    // Some mirrors answer with every line at zero: that is plain text.
    if !lines.iter().any(|l| l.start > 0 || l.worded()) {
        return Some(Sheet::Plain(lines.iter().map(|l| l.text.as_str()).collect::<Vec<_>>().join("\n")));
    }
    Some(Sheet::Synced(lines))
}

// --- envelopes -------------------------------------------------------------------------------

const CONTENT_KEYS: &[&str] = &[
    "ttml", "ttmlContent", "lyrics", "lrc", "content", "text", "plainLyrics", "syncedLyrics", "line", "lines", "lyric", "data",
    "result", "response",
];

fn extract(v: &Value) -> Option<String> {
    match v {
        Value::String(s) => {
            let text = s.trim();
            match serde_json::from_str::<Value>(text) {
                Ok(nested @ (Value::Object(_) | Value::Array(_))) => extract(&nested),
                _ => Some(text.to_string()),
            }
        }
        Value::Array(a) => Some(a.iter().filter_map(extract).collect::<Vec<_>>().join("\n")).filter(|s| !s.trim().is_empty()),
        Value::Object(o) => {
            let failed = o.get("isError").is_some_and(|e| e == &Value::Bool(true) || e == "true")
                || o.get("ok").is_some_and(|e| e == &Value::Bool(false) || e == "false")
                || o.get("error").is_some_and(|e| !(e.is_null() || e == &Value::Bool(false) || e == ""));
            if failed {
                return None;
            }
            CONTENT_KEYS
                .iter()
                .find_map(|k| o.get(*k).and_then(extract))
                .or_else(|| o.get("metadata").filter(|m| m.is_object()).and_then(extract))
                .or_else(|| o.get("words").and_then(extract))
        }
        _ => None,
    }
}

/// The lyrics text inside a JSON envelope or code fence; the raw text otherwise.
pub(crate) fn unwrap(raw: &str) -> Option<String> {
    let mut value = raw.replace('\u{FEFF}', "").trim().to_string();
    if value.starts_with("```") {
        let mut rows: Vec<&str> = value.lines().skip(1).collect();
        if rows.last().is_some_and(|l| l.trim() == "```") {
            rows.pop();
        }
        value = rows.join("\n").trim().to_string();
    }
    if value.is_empty() {
        return None;
    }
    match serde_json::from_str::<Value>(&value) {
        Ok(json) => extract(&json).map(|s| s.trim().to_string()).filter(|s| !s.is_empty()),
        Err(_) => Some(value),
    }
}

static TT_TAG: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"(?i)<tt(\s|>)").unwrap());
static NOT_FOUND: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"(?i)\b(lyrics? (not found|unavailable)|error)\b").unwrap());
static META_TAG: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^\[[A-Za-z]+:.*\]$").unwrap());

/// Untimed lines of plain text (start 0); empty for error messages.
pub(crate) fn plain_lines(content: &str) -> Vec<Line> {
    if NOT_FOUND.is_match(content) {
        return Vec::new();
    }
    content.lines().map(str::trim).filter(|l| !l.is_empty() && !META_TAG.is_match(l)).map(|l| Line::new(0, None, l)).collect()
}

/// Any answer (TTML, karaoke, Enhanced LRC, LRC or plain text, possibly in a
/// JSON envelope) into a sheet.
pub(crate) fn parse_any(raw: &str) -> Option<Sheet> {
    let mut content = unwrap(raw)?;
    if content.to_lowercase().contains("&lt;tt") {
        content = content.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&");
    }
    let lines = if TT_TAG.is_match(&content) || content.contains("http://www.w3.org/ns/ttml") {
        ttml::parse(&content).unwrap_or_default()
    } else if (content.trim_start().starts_with('[') || content.contains("LyricContent")) && KARAOKE_HINT.is_match(&content) {
        karaoke(&content).unwrap_or_default()
    } else if content.trim_start().starts_with('<') {
        Vec::new()
    } else if let Some(l) = enhanced_lrc(&content) {
        l
    } else {
        let lrc = sheet::parse_lrc(&content);
        if lrc.is_empty() { plain_lines(&content) } else { lrc }
    };
    Sheet::from_lines(lines)
}

#[cfg(test)]
mod tests {
    use super::*;

    const TTML: &str = r#"<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyric-ttml-internal"><head><metadata><iTunesMetadata><translations><translation><text for="L1"><span ttm:role="x-translation">no</span></text></translation></translations></iTunesMetadata></metadata></head><body><div begin="27.395" end="40.0"><p begin="27.395" end="28.960" itunes:key="L1"><span begin="27.395" end="27.549">I</span> <span begin="27.549" end="27.740">been</span> <span begin="27.740" end="28.077">try</span><span begin="28.077" end="28.300">na</span> <span begin="28.300" end="28.960">call</span><span ttm:role="x-bg"><span begin="28.5" end="28.9">(oh)</span></span></p><p begin="1:30.5" end="1:32">Plain &amp; simple</p></div></body></tt>"#;

    #[test]
    fn ttml_words_syllables_and_background() {
        let lines = ttml::parse(TTML).unwrap();
        assert_eq!(lines.len(), 2);
        let words: Vec<(i64, i64, &str)> = lines[0].words.as_ref().unwrap().iter().map(|w| (w.start, w.end, w.text.as_str())).collect();
        assert_eq!(words, [(27_395, 27_549, "I"), (27_549, 27_740, "been"), (27_740, 28_300, "tryna"), (28_300, 28_960, "call")]);
        assert_eq!(lines[0].text, "I been tryna call");
        assert_eq!(lines[0].background.as_deref(), Some("(oh)"));
        assert_eq!((lines[1].start, lines[1].end, lines[1].text.as_str()), (90_500, Some(92_000), "Plain & simple"));
        assert!(parse_any(&serde_json::json!({ "ttml": TTML }).to_string()).unwrap().worded());
    }

    #[test]
    fn enhanced_lrc_glues_syllables() {
        let lines = enhanced_lrc("[00:01.00]<00:01.00>Hel<00:01.20>lo <00:01.50>world\n[00:03.00]plain &amp; line").unwrap();
        let w: Vec<(i64, i64, &str)> = lines[0].words.as_ref().unwrap().iter().map(|w| (w.start, w.end, w.text.as_str())).collect();
        assert_eq!(w, [(1_000, 1_500, "Hello"), (1_500, 3_000, "world")]);
        assert_eq!(lines[1].text, "plain & line");
        assert!(enhanced_lrc("[00:01.00]no words").is_none());
    }

    #[test]
    fn qrc_karaoke() {
        let qrc = r#"<QrcInfos><LyricInfo LyricCount="1"><Lyric_1 LyricType="1" LyricContent="[ti:Blinding Lights]
[0,900]Lyrics(0,300) (300,0)by(300,300)：(600,100)"Someone"(700,200)
[1000,1500]Blinding(1000,500) (1500,0)Lights(1600,900)
[3000,1000]Ok(3000,1000)"/></LyricInfo></QrcInfos>"#;
        let Sheet::Synced(lines) = parse_any(&serde_json::json!({ "lyrics": qrc }).to_string()).unwrap() else { panic!() };
        assert_eq!(lines[0].text, "Blinding Lights");
        let w: Vec<(i64, i64, &str)> = lines[0].words.as_ref().unwrap().iter().map(|w| (w.start, w.end, w.text.as_str())).collect();
        assert_eq!(w, [(1_000, 1_500, "Blinding"), (1_600, 2_500, "Lights")]);
        assert_eq!(lines[1].end, Some(4_000));
    }

    #[test]
    fn lyrics_plus_json() {
        let root = serde_json::json!({"lyrics": [
            {"time": 30189, "duration": 2340, "text": "I've been on", "syllabus": [
                {"time": 30189, "duration": 207, "text": "I've "}, {"time": 30396, "duration": 246, "text": "been "},
                {"time": 30642, "duration": 100, "text": "o"}, {"time": 30742, "duration": 57, "text": "n"}]},
            {"time": 33000, "duration": 1000, "text": "line only"}]});
        let Some(Sheet::Synced(lines)) = lyrics_plus(&root) else { panic!() };
        assert_eq!(lines[0].text, "I've been on");
        assert_eq!(lines[0].words.as_ref().unwrap()[2], Word::new(30_642, 30_799, "on"));
        assert_eq!((lines[1].start, lines[1].end), (33_000, Some(34_000)));
        let zero = serde_json::json!({"lyrics": [{"time": 0, "text": "a"}, {"time": 0, "text": "b"}]});
        assert_eq!(lyrics_plus(&zero), Some(Sheet::Plain("a\nb".into())));
    }

    #[test]
    fn envelopes() {
        assert_eq!(parse_any(r#"{"error":"Lyrics not found"}"#), None);
        assert!(matches!(parse_any(r#"{"lyrics":"[00:01.00]One\n[00:02.00]Two"}"#), Some(Sheet::Synced(l)) if l.len() == 2));
        assert_eq!(parse_any("```\nfirst\nsecond\n```"), Some(Sheet::Plain("first\nsecond".into())));
    }
}
