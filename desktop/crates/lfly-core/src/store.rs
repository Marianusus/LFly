//! Small JSON documents the window keeps (settings, themes), one file each under the config dir.

use std::path::PathBuf;

use serde_json::Value;

fn dir() -> Result<PathBuf, String> {
    let dir = dirs::config_dir().ok_or("Nessuna cartella di configurazione")?.join("lfly").join("state");
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    Ok(dir)
}

/// Only plain names: a key never reaches outside the state folder.
fn file(key: &str) -> Result<PathBuf, String> {
    if key.is_empty() || key.len() > 64 || !key.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_') {
        return Err(format!("Nome non valido: {key}"));
    }
    Ok(dir()?.join(format!("{key}.json")))
}

pub fn get(key: &str) -> Result<Option<Value>, String> {
    let path = file(key)?;
    match std::fs::read_to_string(&path) {
        Ok(text) => Ok(serde_json::from_str(&text).ok()),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(e) => Err(e.to_string()),
    }
}

/// Written to a temporary file first, so a crash never leaves half a file.
pub fn set(key: &str, value: &Value) -> Result<(), String> {
    let path = file(key)?;
    let tmp = path.with_extension("json.tmp");
    std::fs::write(&tmp, serde_json::to_vec_pretty(value).map_err(|e| e.to_string())?).map_err(|e| e.to_string())?;
    std::fs::rename(&tmp, &path).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    #[test]
    fn keys_cannot_leave_the_folder() {
        assert!(super::file("../x").is_err());
        assert!(super::file("a/b").is_err());
        assert!(super::file("").is_err());
        assert!(super::file("settings").is_ok());
    }
}
