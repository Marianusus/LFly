//! The engine's lyrics model plus the LRC parser and normalizer (port of the
//! phone's `Lyrics.kt` and `Lrc.kt`).
//!
//! A [`Sheet`] is plain text or synced [`Line`]s; a line may carry word or
//! syllable timings ([`Word`]). Times are milliseconds.

/// One timed word (or syllable) of a line.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct Word {
    pub start: i64,
    pub end: i64,
    pub text: String,
}

impl Word {
    pub fn new(start: i64, end: i64, text: impl Into<String>) -> Self {
        Self { start, end, text: text.into() }
    }
}

/// One synced line.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub(crate) struct Line {
    pub start: i64,
    pub end: Option<i64>,
    pub text: String,
    pub words: Option<Vec<Word>>,
    /// Background vocals timed apart from the lead (Apple TTML `x-bg`).
    pub background: Option<String>,
}

impl Line {
    pub fn new(start: i64, end: Option<i64>, text: impl Into<String>) -> Self {
        Self { start, end, text: text.into(), ..Self::default() }
    }

    pub fn worded(&self) -> bool {
        self.words.as_ref().is_some_and(|w| !w.is_empty())
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum Sheet {
    Plain(String),
    Synced(Vec<Line>),
}

impl Sheet {
    pub fn is_empty(&self) -> bool {
        match self {
            Sheet::Plain(t) => t.trim().is_empty(),
            Sheet::Synced(l) => l.is_empty(),
        }
    }

    pub fn synced(&self) -> bool {
        matches!(self, Sheet::Synced(_))
    }

    pub fn worded(&self) -> bool {
        matches!(self, Sheet::Synced(l) if l.iter().any(Line::worded))
    }

    pub fn span_ms(&self) -> Option<i64> {
        let Sheet::Synced(lines) = self else { return None };
        let (first, last) = (lines.first()?, lines.last()?);
        Some((last.end.unwrap_or(last.start) - first.start).max(0))
    }

    /// Plain when every line sits at zero without words, synced otherwise.
    pub fn from_lines(lines: Vec<Line>) -> Option<Sheet> {
        if lines.is_empty() {
            return None;
        }
        if lines.iter().all(|l| l.start == 0 && !l.worded()) {
            return Some(Sheet::Plain(lines.iter().map(|l| l.text.as_str()).collect::<Vec<_>>().join("\n")));
        }
        Some(Sheet::Synced(lines))
    }
}

// --- timestamps ------------------------------------------------------------------------------

/// `mm:ss.xx`, `mm:ss:xx`, `mm:ss.xxx` or `hh:mm:ss.xx` into milliseconds.
pub(crate) fn stamp_of(stamp: &str) -> Option<i64> {
    let s = stamp.trim().replace(',', ".");
    let parts: Vec<&str> = s.split(':').collect();
    if !(2..=3).contains(&parts.len()) {
        return None;
    }
    let int = |x: &str| x.parse::<i64>().ok().filter(|v| *v >= 0);
    let float = |x: &str| x.parse::<f64>().ok().filter(|v| v.is_finite() && *v >= 0.0);
    let total = if parts.len() == 3 {
        if parts[2].contains('.') {
            int(parts[0])? as f64 * 3600.0 + int(parts[1])? as f64 * 60.0 + float(parts[2])?
        } else {
            let fraction = int(parts[2])?;
            let frac = if fraction >= 100 { fraction as f64 / 1000.0 } else { fraction as f64 / 100.0 };
            int(parts[0])? as f64 * 60.0 + int(parts[1])? as f64 + frac
        }
    } else {
        int(parts[0])? as f64 * 60.0 + float(parts[1])?
    };
    (total.is_finite() && total < 9.0e12).then(|| (total * 1000.0).round() as i64)
}

fn offset_of(line: &str) -> Option<i64> {
    let rest = line.trim().strip_prefix('[')?;
    let close = rest.find(']')?;
    if !rest[close + 1..].trim().is_empty() {
        return None;
    }
    let (name, value) = rest[..close].split_once(':')?;
    name.trim().eq_ignore_ascii_case("offset").then(|| value.trim().parse().ok()).flatten()
}

// --- reading ---------------------------------------------------------------------------------

/// Parses LRC (several stamps per line, Enhanced LRC `<mm:ss.xx>` word tags,
/// a trailing end stamp, `[offset:]`) and normalizes the result.
pub(crate) fn parse_lrc(lrc: &str) -> Vec<Line> {
    let mut shift = 0i64;
    let mut lines = Vec::new();
    for line in lrc.split('\n') {
        if let Some(ms) = offset_of(line) {
            shift += ms;
            continue;
        }
        lines.extend(read(line));
    }
    if shift != 0 {
        for line in &mut lines {
            let s = |at: i64| (at - shift).max(0);
            line.start = s(line.start);
            line.end = line.end.map(s);
            for w in line.words.iter_mut().flatten() {
                w.start = s(w.start);
                w.end = s(w.end);
            }
        }
    }
    normalize(lines)
}

fn read(line: &str) -> Vec<Line> {
    let mut rest = line.trim();
    let mut stamps = Vec::new();
    while let Some(body) = rest.strip_prefix('[') {
        let Some(close) = body.find(']') else { break };
        let Some(at) = stamp_of(&body[..close]) else { break };
        stamps.push(at);
        rest = body[close + 1..].trim_start();
    }
    let (body, closed) = closed_at(rest);
    let (text, words) = spoken(body);
    stamps
        .into_iter()
        .map(|start| Line {
            start,
            end: closed.filter(|c| *c > start),
            text: text.clone(),
            words: words.as_ref().map(|w| shifted(w, start)),
            background: None,
        })
        .collect()
}

fn shifted(words: &[Word], start: i64) -> Vec<Word> {
    let drift = words.first().map(|w| start - w.start).unwrap_or(0);
    words.iter().map(|w| Word::new((w.start + drift).max(0), (w.end + drift).max(0), w.text.clone())).collect()
}

fn closed_at(rest: &str) -> (&str, Option<i64>) {
    let trimmed = rest.trim_end();
    let Some(body) = trimmed.strip_suffix(']') else { return (rest, None) };
    let Some(open) = body.rfind('[') else { return (rest, None) };
    let Some(at) = stamp_of(&body[open + 1..]) else { return (rest, None) };
    let text = body[..open].trim_end();
    if text.is_empty() {
        return (rest, None);
    }
    (text, Some(at))
}

fn spoken(body: &str) -> (String, Option<Vec<Word>>) {
    // Segments of text, each optionally opened by a `<mm:ss.xx>` stamp.
    let mut segments: Vec<(Option<i64>, String)> = vec![(None, String::new())];
    let mut rest = body;
    while let Some(open) = rest.find('<') {
        let tail = &rest[open + 1..];
        let Some(shut) = tail.find('>') else { break };
        let last = segments.last_mut().unwrap();
        if let Some(at) = stamp_of(&tail[..shut]) {
            last.1.push_str(&rest[..open]);
            segments.push((Some(at), String::new()));
        } else {
            last.1.push_str(&rest[..open + shut + 2]);
        }
        rest = &tail[shut + 1..];
    }
    segments.last_mut().unwrap().1.push_str(rest);
    let whole: String = segments.iter().map(|s| s.1.as_str()).collect();
    let timed: Vec<&(Option<i64>, String)> = segments.iter().filter(|s| s.0.is_some()).collect();
    if timed.is_empty() {
        return (whole.trim().to_string(), None);
    }
    let mut words: Vec<Word> = Vec::new();
    for (i, (at, text)) in timed.iter().map(|s| (s.0.unwrap(), &s.1)).enumerate() {
        if text.is_empty() {
            if let Some(w) = words.last_mut() {
                w.end = at.max(w.start);
            }
            continue;
        }
        let end = timed.get(i + 1).and_then(|s| s.0).filter(|e| *e > at).unwrap_or(at);
        words.push(Word::new(at, end, text.clone()));
    }
    (whole.trim_end().to_string(), (!words.is_empty()).then_some(words))
}

// --- normalization ---------------------------------------------------------------------------

const STRUCTURAL_LABELS: &[&str] =
    &["intro", "verse", "pre-chorus", "chorus", "refrain", "hook", "bridge", "breakdown", "solo", "instrumental", "outro"];

/// Sorts, closes line ends, drops section labels and empty lines, removes
/// numeric `<..>` marks, tightens spacing, undoes homoglyph spoofing and wide
/// marks, and recapitalizes mostly capitalized sheets.
pub(crate) fn normalize(mut lines: Vec<Line>) -> Vec<Line> {
    lines.sort_by_key(|l| l.start);
    close(&mut lines);
    lines.retain(|l| !structural(&l.text) && !l.text.trim().is_empty());
    for line in &mut lines {
        demark(line);
        tighten(line);
        unspoof(line);
        unwiden(line);
    }
    lines.retain(|l| !l.text.trim().is_empty());
    recapitalize(&mut lines);
    lines
}

fn close(lines: &mut [Line]) {
    for i in 0..lines.len().saturating_sub(1) {
        if lines[i].end.is_none() {
            lines[i].end = Some(lines[i + 1].start);
        }
    }
    for line in lines.iter_mut() {
        let Some(end) = line.end else { continue };
        if let Some(last) = line.words.as_mut().and_then(|w| w.last_mut()) {
            if last.end <= last.start {
                last.end = end.max(last.start);
            }
        }
    }
}

fn structural(text: &str) -> bool {
    let trimmed = text.trim();
    if trimmed.is_empty() || !trimmed.chars().any(char::is_alphanumeric) {
        return true;
    }
    let label = trimmed.trim_matches(|c: char| c.is_whitespace() || "[]-—".contains(c)).to_lowercase();
    let wrapped = (trimmed.starts_with('[') && trimmed.ends_with(']')) || (trimmed.starts_with('-') && trimmed.ends_with('-'));
    wrapped && STRUCTURAL_LABELS.contains(&label.as_str())
}

fn joined(words: &[Word]) -> String {
    words.iter().map(|w| w.text.as_str()).collect()
}

/// Character ranges of `<123>`-style numeric marks.
fn marks(chars: &[char]) -> Vec<(usize, usize)> {
    let mut out = Vec::new();
    let mut opened = None;
    for (i, c) in chars.iter().enumerate() {
        match c {
            '<' => opened = Some(i),
            '>' => {
                if let Some(start) = opened.take() {
                    let inner: String = chars[start + 1..i].iter().collect();
                    let digits = inner.trim_start_matches(['+', '-']);
                    if !inner.is_empty() && digits.chars().all(|c| c.is_ascii_digit()) {
                        out.push((start, i + 1));
                    }
                }
            }
            _ => {}
        }
    }
    out
}

fn demark(line: &mut Line) {
    let whole = line.words.as_deref().map(joined).unwrap_or_else(|| line.text.clone());
    let chars: Vec<char> = whole.chars().collect();
    let marks = marks(&chars);
    if marks.is_empty() {
        return;
    }
    let inside = |pos: usize| marks.iter().any(|(s, e)| pos >= *s && pos < *e);
    if let Some(words) = line.words.as_mut() {
        let mut cursor = 0;
        words.retain_mut(|w| {
            let n = w.text.chars().count();
            let cleaned: String = w.text.chars().enumerate().filter(|(i, _)| !inside(cursor + i)).map(|(_, c)| c).collect();
            cursor += n;
            w.text = cleaned;
            !w.text.is_empty()
        });
        line.text = joined(words);
    } else {
        line.text = chars.iter().enumerate().filter(|(i, _)| !inside(*i)).map(|(_, c)| c).collect();
    }
}

fn closing_punctuation(c: char) -> bool {
    ")]},.!?;:%".contains(c)
}

fn unspace_punctuation(text: &str) -> String {
    let mut out = String::new();
    let mut spacing = String::new();
    for c in text.chars() {
        if c.is_whitespace() {
            spacing.push(c);
            continue;
        }
        if !closing_punctuation(c) {
            out.push_str(&spacing);
        }
        spacing.clear();
        out.push(c);
    }
    out.push_str(&spacing);
    out
}

fn squeezed(text: &str) -> String {
    let mut out = String::new();
    let mut spacing = false;
    for c in text.chars() {
        if c.is_whitespace() {
            spacing = true;
        } else {
            if spacing {
                out.push(' ');
                spacing = false;
            }
            out.push(c);
        }
    }
    if spacing {
        out.push(' ');
    }
    out
}

fn tighten(line: &mut Line) {
    let spaced = line.words.as_ref().is_some_and(|w| w.iter().any(|w| w.text.chars().any(char::is_whitespace)));
    match line.words.as_mut() {
        Some(words) if spaced => {
            let mut trailing = true;
            for i in 0..words.len() {
                let mut tidied = unspace_punctuation(&squeezed(&words[i].text));
                if trailing && tidied.starts_with(' ') {
                    tidied.remove(0);
                }
                if tidied.chars().next().is_some_and(closing_punctuation) {
                    if let Some(prev) = words[..i].iter_mut().rev().find(|w| !w.text.is_empty()) {
                        prev.text = prev.text.trim_end().to_string();
                    }
                }
                trailing = tidied.ends_with(' ');
                words[i].text = tidied;
            }
            if let Some(last) = words.last_mut() {
                if last.text.ends_with(' ') {
                    last.text.pop();
                }
            }
            words.retain(|w| !w.text.is_empty());
            line.text = joined(words);
        }
        _ => {
            let source = line.words.as_deref().map(joined).unwrap_or_else(|| line.text.clone());
            line.text = unspace_punctuation(&squeezed(&source)).trim().to_string();
        }
    }
}

fn homoglyph(c: char) -> Option<char> {
    const PAIRS: &[(char, char)] = &[
        ('а', 'a'), ('в', 'b'), ('е', 'e'), ('к', 'k'), ('м', 'm'), ('н', 'h'), ('о', 'o'), ('р', 'p'), ('с', 'c'),
        ('т', 't'), ('у', 'y'), ('х', 'x'), ('і', 'i'), ('ј', 'j'), ('ѕ', 's'), ('ԁ', 'd'), ('А', 'A'), ('В', 'B'),
        ('Е', 'E'), ('К', 'K'), ('М', 'M'), ('Н', 'H'), ('О', 'O'), ('Р', 'P'), ('С', 'C'), ('Т', 'T'), ('У', 'Y'),
        ('Х', 'X'), ('І', 'I'), ('Ј', 'J'), ('Ѕ', 'S'), ('Α', 'A'), ('Β', 'B'), ('Ε', 'E'), ('Ζ', 'Z'), ('Η', 'H'),
        ('Ι', 'I'), ('Κ', 'K'), ('Μ', 'M'), ('Ν', 'N'), ('Ο', 'O'), ('Ρ', 'P'), ('Τ', 'T'), ('Υ', 'Y'), ('Χ', 'X'),
        ('ο', 'o'), ('ρ', 'p'), ('τ', 't'), ('ι', 'i'),
    ];
    PAIRS.iter().find(|(from, _)| *from == c).map(|(_, to)| *to)
}

/// Latin text with a few look-alike Cyrillic/Greek letters mixed in.
fn spoofed(text: &str) -> bool {
    let (mut latin, mut masked) = (false, false);
    for c in text.chars().filter(|c| c.is_alphabetic()) {
        if c.is_ascii_alphabetic() {
            latin = true;
        } else if homoglyph(c).is_some() {
            masked = true;
        } else {
            return false;
        }
    }
    latin && masked
}

fn map_chars(text: &str, f: impl Fn(char) -> Option<char>) -> String {
    text.chars().map(|c| f(c).unwrap_or(c)).collect()
}

fn unspoof(line: &mut Line) {
    if spoofed(&line.text) {
        line.text = map_chars(&line.text, homoglyph);
        for w in line.words.iter_mut().flatten() {
            w.text = map_chars(&w.text, homoglyph);
        }
    }
}

fn narrow(c: char) -> Option<char> {
    matches!(c, '\u{FF07}' | '\u{02BC}' | '\u{2032}' | '\u{00B4}' | '`').then_some('\'')
}

fn unwiden(line: &mut Line) {
    line.text = map_chars(&line.text, narrow);
    for w in line.words.iter_mut().flatten() {
        w.text = map_chars(&w.text, narrow);
    }
}

fn uppercased(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut done = false;
    for c in text.chars() {
        if !done && c.is_alphanumeric() {
            done = true;
            if c.is_lowercase() {
                out.extend(c.to_uppercase());
                continue;
            }
        }
        out.push(c);
    }
    out
}

fn recapitalize(lines: &mut [Line]) {
    let (mut upper, mut lower) = (0, 0);
    for l in lines.iter() {
        match l.text.chars().find(|c| c.is_alphanumeric()) {
            Some(c) if c.is_uppercase() => upper += 1,
            Some(c) if c.is_lowercase() => lower += 1,
            _ => {}
        }
    }
    if upper == 0 || upper < lower {
        return;
    }
    for line in lines.iter_mut() {
        if let Some(w) = line.words.as_mut().and_then(|w| w.iter_mut().find(|w| w.text.chars().any(char::is_alphanumeric))) {
            w.text = uppercased(&w.text);
        }
        line.text = uppercased(&line.text);
    }
}

// --- output ----------------------------------------------------------------------------------

/// Makes the words of every line cover its text exactly: each word keeps its
/// trailing space, so joining the word texts gives the line. Words that cannot
/// be placed in the text are re-spaced and become the text.
pub(crate) fn finish(lines: &mut [Line]) {
    for line in lines.iter_mut() {
        line.text = line.text.trim().to_string();
        let Some(words) = line.words.as_mut() else { continue };
        words.retain(|w| !w.text.trim().is_empty());
        if words.is_empty() {
            line.words = None;
            continue;
        }
        if !place(&line.text, words) {
            let n = words.len();
            for (i, w) in words.iter_mut().enumerate() {
                let t = w.text.trim().to_string();
                w.text = if i + 1 < n { format!("{t} ") } else { t };
            }
            line.text = joined(words);
        }
        for w in words.iter_mut() {
            w.end = w.end.max(w.start);
        }
    }
}

/// Re-slices `text` among `words` (found in order); false when a word is missing.
fn place(text: &str, words: &mut [Word]) -> bool {
    let mut starts = Vec::with_capacity(words.len());
    let mut cursor = 0;
    for w in words.iter() {
        let needle = w.text.trim();
        let Some(rel) = text[cursor..].find(needle) else { return false };
        starts.push(cursor + rel);
        cursor += rel + needle.len();
    }
    if text[..starts[0]].chars().any(char::is_alphanumeric) {
        return false;
    }
    starts[0] = 0;
    for i in 0..words.len() {
        let end = starts.get(i + 1).copied().unwrap_or(text.len());
        words[i].text = text[starts[i]..end].to_string();
    }
    true
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stamps() {
        assert_eq!(stamp_of("01:02.50"), Some(62_500));
        assert_eq!(stamp_of("00:05.123"), Some(5_123));
        assert_eq!(stamp_of("00:05:12"), Some(5_120));
        assert_eq!(stamp_of("1:00:00.5"), Some(3_600_500));
        assert_eq!(stamp_of("ar:Someone"), None);
    }

    #[test]
    fn lrc_with_metadata_offset_repeats_and_labels() {
        let lrc = "[ar:Daft Punk]\n[offset:500]\n[00:10.50]Hello there\n[00:05.00][00:20.00]again\n[00:12.00][Chorus]\n[00:13.00]♪\n[00:30.00]end line[00:32.00]";
        let lines = parse_lrc(lrc);
        let got: Vec<(i64, Option<i64>, &str)> = lines.iter().map(|l| (l.start, l.end, l.text.as_str())).collect();
        assert_eq!(got, [(4_500, Some(10_000), "again"), (10_000, Some(11_500), "Hello there"), (19_500, Some(29_500), "again"), (29_500, Some(31_500), "end line")]);
    }

    #[test]
    fn enhanced_lrc_words() {
        let lines = parse_lrc("[00:30.00]<00:30.00>One <00:30.50>more <00:31.00>time<00:31.80>\n[00:33.00]Next");
        let words = lines[0].words.as_ref().unwrap();
        assert_eq!(lines[0].text, "One more time");
        assert_eq!(words.iter().map(|w| (w.start, w.end, w.text.as_str())).collect::<Vec<_>>(), [(30_000, 30_500, "One "), (30_500, 31_000, "more "), (31_000, 31_800, "time")]);
    }

    #[test]
    fn spacing_marks_and_homoglyphs() {
        let lines = vec![Line::new(0, None, "Hеllo   world , <123>yes !"), Line::new(1_000, None, "fine")];
        let lines = normalize(lines);
        assert_eq!(lines[0].text, "Hello world, yes!");
    }

    #[test]
    fn finish_gives_words_trailing_spaces() {
        let mut lines = vec![Line {
            text: "I've been on".into(),
            words: Some(vec![Word::new(0, 1, "I've"), Word::new(1, 2, "been"), Word::new(2, 3, "on")]),
            ..Line::default()
        }];
        finish(&mut lines);
        let w: Vec<&str> = lines[0].words.as_ref().unwrap().iter().map(|w| w.text.as_str()).collect();
        assert_eq!(w, ["I've ", "been ", "on"]);
        let mut odd = vec![Line { text: "abc".into(), words: Some(vec![Word::new(0, 1, "x"), Word::new(1, 2, "y")]), ..Line::default() }];
        finish(&mut odd);
        assert_eq!(odd[0].text, "x y");
    }
}
