//! The listener's library on this computer, in the phone's own file format (Gson's JSON of
//! `List<Track>` and `List<Playlist>`), so a phone backup (.lfly) imports as it is.
//!
//! Tracks stay JSON values here: fields this version does not know (a newer phone's) survive.

use std::io::Read;
use std::path::{Path, PathBuf};

use serde::Serialize;
use serde_json::Value;

pub const FAVORITES: &str = "favorites.json";
pub const PLAYLISTS: &str = "playlists.json";
pub const HISTORY: &str = "history.json";
const FILES: [&str; 3] = [FAVORITES, PLAYLISTS, HISTORY];

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Library {
    pub favorites: Value,
    pub playlists: Value,
    pub history: Value,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportSummary {
    pub favorites: usize,
    pub playlists: usize,
    pub history: usize,
}

pub fn dir() -> Result<PathBuf, String> {
    let dir = dirs::data_dir().ok_or("Nessuna cartella dati")?.join("lfly").join("library");
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    Ok(dir)
}

fn read_list(dir: &Path, name: &str) -> Value {
    std::fs::read_to_string(dir.join(name))
        .ok()
        .and_then(|s| serde_json::from_str::<Value>(&s).ok())
        .filter(Value::is_array)
        .unwrap_or_else(|| Value::Array(vec![]))
}

pub fn load(dir: &Path) -> Library {
    Library {
        favorites: read_list(dir, FAVORITES),
        playlists: read_list(dir, PLAYLISTS),
        history: read_list(dir, HISTORY),
    }
}

/// Replaces one list; only the three library files, and only JSON arrays.
pub fn save(dir: &Path, name: &str, value: &Value) -> Result<(), String> {
    if !FILES.contains(&name) {
        return Err(format!("File sconosciuto: {name}"));
    }
    if !value.is_array() {
        return Err("Serve un elenco".into());
    }
    let path = dir.join(name);
    let tmp = path.with_extension("json.tmp");
    std::fs::write(&tmp, serde_json::to_vec(value).map_err(|e| e.to_string())?).map_err(|e| e.to_string())?;
    std::fs::rename(&tmp, &path).map_err(|e| e.to_string())
}

/// Takes the library out of a phone backup. Nothing is written unless it is an LFly backup and
/// every list in it reads.
pub fn import_backup(backup: &Path, dir: &Path) -> Result<ImportSummary, String> {
    let file = std::fs::File::open(backup).map_err(|e| e.to_string())?;
    let mut zip = zip::ZipArchive::new(file).map_err(|_| "Non è un backup di LFly".to_string())?;
    if zip.by_name("lfly-backup.json").is_err() {
        return Err("Non è un backup di LFly".into());
    }
    let mut found: Vec<(&str, Value)> = Vec::new();
    for name in FILES {
        let Ok(mut entry) = zip.by_name(&format!("files/{name}")) else { continue };
        // A library list is never this big; a bigger entry is not one.
        if entry.size() > 200 * 1024 * 1024 {
            return Err(format!("{name} è troppo grande"));
        }
        let mut text = String::new();
        entry.read_to_string(&mut text).map_err(|e| format!("{name}: {e}"))?;
        let value: Value = serde_json::from_str(&text).map_err(|e| format!("{name}: {e}"))?;
        if !value.is_array() {
            return Err(format!("{name} non è un elenco"));
        }
        found.push((name, value));
    }
    if found.is_empty() {
        return Err("Il backup non contiene una libreria".into());
    }
    let count = |n: &str| found.iter().find(|(f, _)| *f == n).and_then(|(_, v)| v.as_array()).map_or(0, Vec::len);
    let summary = ImportSummary { favorites: count(FAVORITES), playlists: count(PLAYLISTS), history: count(HISTORY) };
    for (name, value) in &found {
        save(dir, name, value)?;
    }
    Ok(summary)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn backup(path: &Path, with_meta: bool) {
        let mut w = zip::ZipWriter::new(std::fs::File::create(path).unwrap());
        let opts = zip::write::SimpleFileOptions::default();
        if with_meta {
            w.start_file("lfly-backup.json", opts).unwrap();
            w.write_all(br#"{"format":1}"#).unwrap();
        }
        w.start_file("files/favorites.json", opts).unwrap();
        w.write_all(br#"[{"id":"yt_a","title":"A","artist":"X","durationMs":1000,"futureField":7}]"#).unwrap();
        w.start_file("files/playlists.json", opts).unwrap();
        w.write_all(br#"[{"id":"p1","name":"Mix","tracks":[]}]"#).unwrap();
        w.start_file("shared_prefs/x.xml", opts).unwrap();
        w.write_all(b"<map/>").unwrap();
        w.finish().unwrap();
    }

    #[test]
    fn a_phone_backup_imports_and_unknown_fields_survive() {
        let tmp = std::env::temp_dir().join(format!("lfly-lib-{}", std::process::id()));
        std::fs::create_dir_all(&tmp).unwrap();
        let file = tmp.join("b.lfly");
        backup(&file, true);
        let s = import_backup(&file, &tmp).unwrap();
        assert_eq!((s.favorites, s.playlists, s.history), (1, 1, 0));
        let lib = load(&tmp);
        assert_eq!(lib.favorites[0]["futureField"], 7);
        assert_eq!(lib.playlists[0]["name"], "Mix");
        assert!(lib.history.as_array().unwrap().is_empty());
        std::fs::remove_dir_all(&tmp).ok();
    }

    #[test]
    fn other_zips_are_refused_and_nothing_is_written() {
        let tmp = std::env::temp_dir().join(format!("lfly-lib-no-{}", std::process::id()));
        std::fs::create_dir_all(&tmp).unwrap();
        let file = tmp.join("b.zip");
        backup(&file, false);
        assert!(import_backup(&file, &tmp).is_err());
        assert!(!tmp.join(FAVORITES).exists());
        std::fs::remove_dir_all(&tmp).ok();
    }

    #[test]
    fn only_library_files_are_saved() {
        let tmp = std::env::temp_dir();
        assert!(save(&tmp, "../evil.json", &Value::Array(vec![])).is_err());
        assert!(save(&tmp, "settings.json", &Value::Array(vec![])).is_err());
    }
}
