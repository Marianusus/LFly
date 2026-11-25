//! Lyrics kept next to the downloaded songs, to read them without a connection (the phone's
//! DownloadLyricsStore.kt): one small file per song in `<downloads>/lyrics/`. A song with no
//! lyrics anywhere gets a "not found" file, so it is not looked for again and again; a failed
//! lookup writes nothing, so it is tried again.
//!
//! Every function takes the downloads folder ([`crate::downloads::Downloads::dir`]).

use std::collections::HashSet;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::pages::Lyrics;

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Saved {
    pub lyrics: Option<Lyrics>,
    #[serde(default)]
    pub not_found: bool,
    #[serde(default)]
    pub fetched_at: u64,
}

pub fn dir(base: &Path) -> PathBuf {
    base.join("lyrics")
}

fn file_name(id: &str) -> String {
    let name: String = id.chars().map(|c| if c.is_ascii_alphanumeric() || c == '-' || c == '_' { c } else { '_' }).collect();
    format!("{name}.json")
}

/// What is kept for the song, if anything.
pub fn read(base: &Path, id: &str) -> Option<Saved> {
    serde_json::from_slice(&std::fs::read(dir(base).join(file_name(id))).ok()?).ok()
}

/// Keeps the song's lyrics (or that it has none).
pub fn write(base: &Path, id: &str, lyrics: Option<&Lyrics>) {
    let p = dir(base).join(file_name(id));
    let saved = Saved {
        lyrics: lyrics.and_then(|l| serde_json::from_value(serde_json::to_value(l).ok()?).ok()),
        not_found: lyrics.is_none(),
        fetched_at: std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_secs()),
    };
    let Ok(bytes) = serde_json::to_vec(&saved) else { return };
    let _ = std::fs::create_dir_all(dir(base));
    let tmp = p.with_extension("json.tmp");
    if std::fs::write(&tmp, bytes).is_ok() {
        let _ = std::fs::rename(tmp, p);
    }
}

pub fn remove(base: &Path, id: &str) {
    let _ = std::fs::remove_file(dir(base).join(file_name(id)));
}

/// Forgets them all.
pub fn clear(base: &Path) {
    let _ = std::fs::remove_dir_all(dir(base));
}

/// The ids (among [ids]) whose lyrics are kept (found ones, not "not found" marks): read once
/// when the downloads open, then kept up to date by them.
pub fn scan<'a>(base: &Path, ids: impl Iterator<Item = &'a str>) -> HashSet<String> {
    ids.filter(|id| read(base, id).is_some_and(|s| s.lyrics.is_some())).map(str::to_string).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::pages::LyricLine;

    #[test]
    fn keeps_and_forgets_a_song() {
        let d = std::env::temp_dir().join(format!("lfly-lyr-test-{}", std::process::id()));
        let l = Lyrics { synced: Some(vec![LyricLine { ms: 1000, end_ms: None, text: "la".into(), words: None, bg: None }]), plain: None, source: "LRCLIB".into(), instrumental: false };
        write(&d, "a/b", Some(&l));
        assert_eq!(scan(&d, ["a/b", "x"].into_iter()), HashSet::from(["a/b".to_string()]));
        let back = read(&d, "a/b").unwrap().lyrics.unwrap();
        assert_eq!(back.synced.unwrap()[0].text, "la");
        write(&d, "none", None);
        assert!(read(&d, "none").unwrap().not_found && scan(&d, ["none"].into_iter()).is_empty());
        remove(&d, "a/b");
        assert!(read(&d, "a/b").is_none());
        clear(&d);
        assert!(!dir(&d).exists());
        let _ = std::fs::remove_dir_all(&d);
    }
}
