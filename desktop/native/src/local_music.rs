//! Folders of audio files on this computer, for the Console's browser: the person adds a folder,
//! its songs (and those of its sub-folders) are listed and load on a deck straight from the disk.
//! Nothing here goes to the music player, the library, the history or another device.

use std::path::{Path, PathBuf};

use lfly_yt::Track;

/// What a deck opens (each checked with a file through `TrackBuf::open`; AIFF and WMA are not).
pub const EXTENSIONS: [&str; 9] = ["mp3", "flac", "wav", "ogg", "oga", "opus", "m4a", "aac", "webm"];
const STORE: &str = "console-folders";
/// A folder deeper than this, or with more songs, is cut short (a whole disk picked by mistake).
const MAX_DEPTH: usize = 12;
const MAX_FILES: usize = 20_000;

/// The folders added so far.
pub fn saved() -> Vec<PathBuf> {
    lfly_core::store::get(STORE)
        .ok()
        .flatten()
        .and_then(|v| v.as_array().cloned())
        .map(|list| list.iter().filter_map(|p| p.as_str()).map(PathBuf::from).collect())
        .unwrap_or_default()
}

/// Keeps the list (not in test runs, which must not change the listener's settings).
pub fn save(list: &[PathBuf]) {
    if crate::deck_model::test_run() {
        return;
    }
    let v = serde_json::json!(list.iter().map(|p| p.to_string_lossy()).collect::<Vec<_>>());
    if let Err(e) = lfly_core::store::set(STORE, &v) {
        log::warn!("console folders not saved: {e}");
    }
}

pub fn is_audio(path: &Path) -> bool {
    path.extension().and_then(|e| e.to_str()).is_some_and(|e| EXTENSIONS.contains(&e.to_ascii_lowercase().as_str()))
}

/// The songs in [dir] and its sub-folders, by path. Links to folders are not followed (a link
/// back up would never end). Slow on a big disk: call it off the window's thread.
pub fn scan(dir: &Path) -> Vec<Track> {
    let mut out = Vec::new();
    walk(dir, 0, &mut out);
    out.sort_by(|a, b| a.id.cmp(&b.id));
    out
}

fn walk(dir: &Path, depth: usize, out: &mut Vec<Track>) {
    if depth > MAX_DEPTH || out.len() >= MAX_FILES {
        return;
    }
    let Ok(entries) = std::fs::read_dir(dir) else { return };
    for entry in entries.flatten() {
        let path = entry.path();
        let Ok(meta) = std::fs::symlink_metadata(&path) else { continue };
        if meta.is_dir() {
            walk(&path, depth + 1, out);
        } else if (meta.is_file() || meta.file_type().is_symlink()) && is_audio(&path) {
            out.push(track_of(&path));
            if out.len() >= MAX_FILES {
                return;
            }
        }
    }
}

/// A file as a song: "Artist - Title.mp3" gives both, else the name is the title; the folder is
/// the album.
pub fn track_of(path: &Path) -> Track {
    let stem = path.file_stem().map(|s| s.to_string_lossy().to_string()).unwrap_or_default();
    // A track number in front goes when a separator follows it ("03 - ", "03. ", "03_"), not when
    // it is part of the name ("2Pac", "50 Cent", "1979").
    let digits = stem.chars().take_while(char::is_ascii_digit).count();
    let rest = &stem[digits..];
    let stem = if digits > 0 && digits <= 3 && rest.trim_start_matches(' ').starts_with(['.', '-', '_']) {
        rest.trim_start_matches(['.', ' ', '-', '_']).to_string()
    } else {
        stem.clone()
    };
    let stem = if stem.is_empty() { path.file_name().map(|s| s.to_string_lossy().to_string()).unwrap_or_default() } else { stem };
    let (artist, title) = match stem.split_once(" - ") {
        Some((a, t)) => (a.trim().to_string(), t.trim().to_string()),
        None => (String::new(), stem.trim().to_string()),
    };
    Track {
        id: format!("file:{}", path.to_string_lossy()),
        title,
        artist,
        album: path.parent().and_then(|p| p.file_name()).map(|n| n.to_string_lossy().to_string()),
        provider: "local".into(),
        ..Track::default()
    }
}

/// A folder's name as the browser shows it.
pub fn name_of(dir: &Path) -> String {
    dir.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_else(|| dir.to_string_lossy().to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn file_names_give_artist_and_title() {
        let t = track_of(Path::new("/m/House/03 - Daft Punk - One More Time.mp3"));
        assert_eq!((t.artist.as_str(), t.title.as_str(), t.album.as_deref()), ("Daft Punk", "One More Time", Some("House")));
        assert_eq!(t.id, "file:/m/House/03 - Daft Punk - One More Time.mp3");
        for (file, artist, title) in [("2Pac - Changes.mp3", "2Pac", "Changes"), ("50 Cent - In da Club.mp3", "50 Cent", "In da Club"), ("1979.mp3", "", "1979"), ("07_Intro.wav", "", "Intro"), ("12. Moby - Porcelain.flac", "Moby", "Porcelain")] {
            let t = track_of(&Path::new("/m").join(file));
            assert_eq!((t.artist.as_str(), t.title.as_str()), (artist, title), "{file}");
        }
        let t = track_of(Path::new("/m/intro.flac"));
        assert_eq!((t.artist.as_str(), t.title.as_str()), ("", "intro"));
        assert!(is_audio(Path::new("a.MP3")) && !is_audio(Path::new("cover.jpg")) && !is_audio(Path::new("a.wma")));
    }

    #[test]
    fn a_folder_is_scanned_with_its_sub_folders_but_not_through_links() {
        let root = std::env::temp_dir().join(format!("lfly-local-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        std::fs::create_dir_all(root.join("sub/deeper")).unwrap();
        for f in ["a.mp3", "sub/b.flac", "sub/deeper/c.wav", "sub/notes.txt"] {
            std::fs::write(root.join(f), b"x").unwrap();
        }
        #[cfg(unix)]
        std::os::unix::fs::symlink(&root, root.join("sub/loop")).unwrap();
        let found: Vec<String> = scan(&root).into_iter().map(|t| t.title).collect();
        let _ = std::fs::remove_dir_all(&root);
        assert_eq!(found, ["a", "b", "c"]);
    }
}
