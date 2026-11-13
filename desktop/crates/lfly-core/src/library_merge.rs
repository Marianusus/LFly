//! The three-way merge of the library that every sync uses (over LFly Connect and through Google
//! Drive), the same as the phone's `LibraryMerge.kt`.
//!
//! Each device remembers which songs and playlists the other side held when they last agreed (the
//! base), so it can tell a song deleted on the other side (in the base, gone there: delete it here
//! too) from a song added here (not in the base: keep it). Playlists edited on both sides keep the
//! most recently changed one (`updatedAt`). History is joined.

use std::collections::{HashMap, HashSet};
use std::path::Path;

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::library;

const HISTORY_MAX: usize = 100;

/// The library as the sync file and the phone hold it.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct Snapshot {
    pub favorites: Vec<Value>,
    pub playlists: Vec<Value>,
    pub history: Vec<Value>,
}

/// What the file held at this device's last sync: ids only.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Base {
    pub favorites: Vec<String>,
    pub playlists: Vec<String>,
}

fn id_of(v: &Value) -> Option<&str> {
    v.get("id").and_then(Value::as_str)
}

fn updated_at(v: &Value) -> i64 {
    v.get("updatedAt").and_then(Value::as_i64).unwrap_or(0)
}

/// Three-way merge of one list by id. Local order first, then what only the other side has.
fn merge_list(base: &[String], local: &[Value], remote: &[Value], newer: impl Fn(&Value, &Value) -> bool) -> Vec<Value> {
    let base: HashSet<&str> = base.iter().map(String::as_str).collect();
    let theirs: HashMap<&str, &Value> = remote.iter().filter_map(|v| Some((id_of(v)?, v))).collect();
    let mine: HashSet<&str> = local.iter().filter_map(id_of).collect();
    let mut out = Vec::new();
    let mut seen = HashSet::new();
    for v in local {
        let Some(id) = id_of(v) else { continue };
        if !seen.insert(id) {
            continue;
        }
        match theirs.get(id) {
            Some(r) => out.push(if newer(r, v) { (*r).clone() } else { v.clone() }),
            // In the base but gone there: the other side deleted it.
            None if base.contains(id) => {}
            None => out.push(v.clone()),
        }
    }
    for v in remote {
        let Some(id) = id_of(v) else { continue };
        if mine.contains(id) || !seen.insert(id) {
            continue;
        }
        // In the base but gone here: deleted here.
        if !base.contains(id) {
            out.push(v.clone());
        }
    }
    out
}

pub fn merge(base: &Base, local: &Snapshot, remote: &Snapshot) -> Snapshot {
    let favorites = merge_list(&base.favorites, &local.favorites, &remote.favorites, |_, _| false);
    let playlists = merge_list(&base.playlists, &local.playlists, &remote.playlists, |r, l| updated_at(r) > updated_at(l));
    // History has no deletions worth keeping: the newest plays of both, without repeats.
    let mut history = Vec::new();
    let mut seen = HashSet::new();
    for v in local.history.iter().chain(&remote.history) {
        if let Some(id) = id_of(v) {
            if seen.insert(id.to_string()) {
                history.push(v.clone());
            }
        }
    }
    history.truncate(HISTORY_MAX.max(local.history.len()));
    Snapshot { favorites, playlists, history }
}

pub fn base_of(s: &Snapshot) -> Base {
    let ids = |l: &[Value]| l.iter().filter_map(id_of).map(str::to_string).collect();
    Base { favorites: ids(&s.favorites), playlists: ids(&s.playlists) }
}

impl Snapshot {
    /// The library in `dir`, as the files hold it now.
    pub fn load(dir: &Path) -> Snapshot {
        let lib = library::load(dir);
        let list = |v: Value| v.as_array().cloned().unwrap_or_default();
        Snapshot { favorites: list(lib.favorites), playlists: list(lib.playlists), history: list(lib.history) }
    }

    /// Replaces the three library files in `dir`.
    pub fn save(&self, dir: &Path) -> Result<(), String> {
        library::save(dir, library::FAVORITES, &Value::Array(self.favorites.clone()))?;
        library::save(dir, library::PLAYLISTS, &Value::Array(self.playlists.clone()))?;
        library::save(dir, library::HISTORY, &Value::Array(self.history.clone()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn t(id: &str) -> Value {
        json!({ "id": id, "title": id })
    }
    fn p(id: &str, name: &str, at: i64) -> Value {
        json!({ "id": id, "name": name, "updatedAt": at, "tracks": [] })
    }
    fn ids(l: &[Value]) -> Vec<&str> {
        l.iter().filter_map(id_of).collect()
    }

    #[test]
    fn the_first_sync_joins_both_libraries() {
        let local = Snapshot { favorites: vec![t("a"), t("b")], ..Default::default() };
        let remote = Snapshot { favorites: vec![t("b"), t("c")], ..Default::default() };
        let m = merge(&Base::default(), &local, &remote);
        assert_eq!(ids(&m.favorites), ["a", "b", "c"]);
    }

    #[test]
    fn a_deletion_on_either_side_wins_over_the_base() {
        let base = Base { favorites: vec!["a".into(), "b".into(), "c".into()], ..Default::default() };
        // The phone removed b; the computer removed c and added d.
        let remote = Snapshot { favorites: vec![t("a"), t("c")], ..Default::default() };
        let local = Snapshot { favorites: vec![t("a"), t("b"), t("d")], ..Default::default() };
        let m = merge(&base, &local, &remote);
        assert_eq!(ids(&m.favorites), ["a", "d"]);
    }

    #[test]
    fn the_newer_edit_of_a_playlist_wins() {
        let base = Base { playlists: vec!["p".into()], ..Default::default() };
        let local = Snapshot { playlists: vec![p("p", "Old", 10)], ..Default::default() };
        let remote = Snapshot { playlists: vec![p("p", "Renamed on the phone", 20)], ..Default::default() };
        let m = merge(&base, &local, &remote);
        assert_eq!(m.playlists[0]["name"], "Renamed on the phone");
    }

    #[test]
    fn history_joins_without_repeats() {
        let local = Snapshot { history: vec![t("x"), t("y")], ..Default::default() };
        let remote = Snapshot { history: vec![t("y"), t("z")], ..Default::default() };
        let m = merge(&Base::default(), &local, &remote);
        assert_eq!(ids(&m.history), ["x", "y", "z"]);
    }

    #[test]
    fn merging_again_changes_nothing() {
        let local = Snapshot { favorites: vec![t("a")], playlists: vec![p("p", "P", 1)], history: vec![t("h")] };
        let remote = Snapshot { favorites: vec![t("b")], ..Default::default() };
        let m = merge(&Base::default(), &local, &remote);
        let again = merge(&base_of(&m), &m, &m);
        assert_eq!(again, m);
    }
}
