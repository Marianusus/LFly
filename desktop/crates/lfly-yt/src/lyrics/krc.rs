//! Kugou KRC karaoke lyrics (port of the phone's `Krc.kt`).
//!
//! A download is base64 of `krc1` + an XOR-masked zlib stream. Inflated, each
//! line reads `[lineStartMs,lineDurationMs]<offsetMs,durationMs,0>word...`
//! with word offsets relative to the line start.

use super::sheet::{Line, Word};
use base64::Engine;
use regex::Regex;
use std::io::Read;
use std::sync::LazyLock;

const KEY: [u8; 16] = [0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47, 0x51, 0x36, 0x31, 0x2d, 0xce, 0xd2, 0x6e, 0x69];

static LINE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^\[(\d+),(\d+)\](.*)$").unwrap());
static TOKEN: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"<(\d+),(\d+),(\d+)>([^<]*)").unwrap());

/// Credit lines Kugou puts in the sheet (not sung).
const CREDITS: &[&str] = &[
    "lyrics by", "lyric by", "composed by", "produced by", "written by", "arranged by", "music by", "mixed by", "mastered by",
    "作词", "作曲", "编曲", "制作", "混音", "母带", "监制", "出品", "录音", "和声",
];

/// A credit line ("Lyrics by: ...", "作词 : ...") rather than a sung one.
pub(crate) fn is_credit(text: &str) -> bool {
    let lower = text.to_lowercase();
    CREDITS.iter().any(|k| lower.contains(k))
}

/// The base64 `content` of a KRC download into plain KRC text.
pub(crate) fn decode(content: &str) -> Option<String> {
    let raw = base64::engine::general_purpose::STANDARD.decode(content.trim()).ok()?;
    if raw.len() <= 4 {
        return None;
    }
    let masked: Vec<u8> = raw[4..].iter().enumerate().map(|(i, b)| b ^ KEY[i % KEY.len()]).collect();
    let mut out = String::new();
    flate2::read::ZlibDecoder::new(masked.as_slice()).read_to_string(&mut out).ok()?;
    Some(out)
}

/// Encodes plain KRC text the way Kugou ships it (for tests).
#[cfg(test)]
pub(crate) fn encode(text: &str) -> String {
    use std::io::Write;
    let mut z = flate2::write::ZlibEncoder::new(Vec::new(), flate2::Compression::default());
    z.write_all(text.as_bytes()).unwrap();
    let mut out = b"krc1".to_vec();
    out.extend(z.finish().unwrap().iter().enumerate().map(|(i, b)| b ^ KEY[i % KEY.len()]));
    base64::engine::general_purpose::STANDARD.encode(out)
}

/// Plain KRC text into word-timed lines (credit lines dropped).
pub(crate) fn parse(text: &str) -> Vec<Line> {
    let mut lines = Vec::new();
    for raw in text.split('\n') {
        let Some(c) = LINE.captures(raw.trim()) else { continue };
        let (Ok(start), duration) = (c[1].parse::<i64>(), c[2].parse::<i64>().unwrap_or(0)) else { continue };
        let tokens: Vec<regex::Captures> = TOKEN.captures_iter(&c[3]).collect();
        let mut words = Vec::new();
        for (i, t) in tokens.iter().enumerate() {
            let text = &t[4];
            if text.is_empty() {
                continue;
            }
            let at = start + t[1].parse::<i64>().unwrap_or(0);
            let length: i64 = t[2].parse().unwrap_or(0);
            let end = if length > 0 {
                at + length
            } else {
                let next = tokens.get(i + 1).and_then(|n| n[1].parse::<i64>().ok());
                next.map(|n| start + n).unwrap_or(start + duration).max(at + 1)
            };
            words.push(Word::new(at, end.max(at + 1), text));
        }
        let body: String = words.iter().map(|w| w.text.as_str()).collect::<String>().trim().to_string();
        if words.is_empty() || body.is_empty() || is_credit(&body) {
            continue;
        }
        lines.push(Line { start, end: (duration > 0).then_some(start + duration), text: body, words: Some(words), background: None });
    }
    lines.sort_by_key(|l| l.start);
    lines
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decodes_and_parses_krc() {
        let krc = "[id:$00000000]\n[ar:The Weeknd]\n[0,2000]<0,500,0>Lyrics <500,500,0>by <1000,1000,0>Someone\n[27395,1565]<0,154,0>I <154,191,0>been <345,337,0>tryna <682,883,0>call\n";
        let plain = decode(&encode(krc)).unwrap();
        assert_eq!(plain, krc);
        let lines = parse(&plain);
        assert_eq!(lines.len(), 1);
        assert_eq!((lines[0].start, lines[0].end, lines[0].text.as_str()), (27_395, Some(28_960), "I been tryna call"));
        let w = lines[0].words.as_ref().unwrap();
        assert_eq!(w[1], Word::new(27_549, 27_740, "been "));
        assert!(decode("not base64!").is_none());
    }
}
