//! The window's languages: Italian (the text written in the code), English and Romanian.
//!
//! Text stays Italian in the code and is looked up when shown: `tr("Impostazioni")`, or
//! `trf("Aggiunto a {0}", &[&name])` with numbered places so a language may reorder them. Text
//! declared ahead (consts, arrays) is marked with `t("…")` and looked up where it is shown. The
//! rows live in `i18n_table.rs`; a test reads the sources and fails on a missing, unused or
//! mismatched row. Counts, dates and decimals have their own functions here.
//!
//! Only text shown on screen is translated: what is saved, compared or sent to another device
//! stays the Italian original.

use std::collections::HashMap;
use std::fmt::Display;
use std::sync::atomic::{AtomicU8, Ordering};
use std::sync::LazyLock;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Lang {
    It,
    En,
    Ro,
}

impl Lang {
    pub const ALL: [Lang; 3] = [Lang::It, Lang::En, Lang::Ro];

    pub fn code(self) -> &'static str {
        match self {
            Lang::It => "it",
            Lang::En => "en",
            Lang::Ro => "ro",
        }
    }

    /// "it", "en_US.UTF-8", "ro-RO"…
    pub fn from_tag(tag: &str) -> Option<Lang> {
        match tag.get(..2).map(str::to_ascii_lowercase).as_deref() {
            Some("it") => Some(Lang::It),
            Some("en") => Some(Lang::En),
            Some("ro") => Some(Lang::Ro),
            _ => None,
        }
    }

    /// Its own name, as a language picker shows it.
    pub fn name(self) -> &'static str {
        match self {
            Lang::It => "Italiano",
            Lang::En => "English",
            Lang::Ro => "Română",
        }
    }
}

static LANG: AtomicU8 = AtomicU8::new(0);

pub fn lang() -> Lang {
    match LANG.load(Ordering::Relaxed) {
        1 => Lang::En,
        2 => Lang::Ro,
        _ => Lang::It,
    }
}

/// Switches the window's text (and the core's messages and the DJ's lines). The views draw it
/// at their next redraw: the caller refreshes the window.
pub fn set_lang(l: Lang) {
    LANG.store(
        match l {
            Lang::It => 0,
            Lang::En => 1,
            Lang::Ro => 2,
        },
        Ordering::Relaxed,
    );
    lfly_core::lang::set(l.code());
}

/// The store key of the chosen language (this window's own: the Tauri app has none).
pub const STORE_KEY: &str = "native-lang";

/// The language chosen in Settings, else Italian (the app's language so far, whatever the
/// system says). `LFLY_LANG` (tests) overrides it.
pub fn saved() -> Lang {
    if let Some(l) = std::env::var("LFLY_LANG").ok().and_then(|v| Lang::from_tag(&v)) {
        return l;
    }
    lfly_core::store::get(STORE_KEY)
        .ok()
        .flatten()
        .and_then(|v| v.get("lang").and_then(|l| l.as_str()).and_then(Lang::from_tag))
        .unwrap_or(Lang::It)
}

pub fn save(l: Lang) {
    if let Err(e) = lfly_core::store::set(STORE_KEY, &serde_json::json!({ "lang": l.code() })) {
        log::warn!("language not saved: {e}");
    }
}

/// Marks text declared ahead of where it is shown, for the table's test; returns it unchanged.
pub const fn t(it: &'static str) -> &'static str {
    it
}

static TABLE: LazyLock<HashMap<&'static str, (&'static str, &'static str)>> =
    LazyLock::new(|| crate::i18n_table::ROWS.iter().map(|(it, en, ro)| (*it, (*en, *ro))).collect());

/// [it] in the window's language (itself when Italian or when no row has it). A key may carry a
/// context after `#` for a word Italian uses in two senses ("Album#plurale"): Italian shows the
/// part before it.
pub fn tr(it: &str) -> &str {
    let l = lang();
    let shown = it.split('#').next().unwrap_or(it);
    if l == Lang::It {
        return shown;
    }
    match TABLE.get(it) {
        Some((en, ro)) => {
            if l == Lang::En {
                en
            } else {
                ro
            }
        }
        None => shown,
    }
}

/// [it] translated, its `{0}`, `{1}`… filled with [args].
pub fn trf(it: &str, args: &[&dyn Display]) -> String {
    fill(tr(it), args)
}

pub fn fill(pattern: &str, args: &[&dyn Display]) -> String {
    let mut out = String::with_capacity(pattern.len() + 16);
    let mut rest = pattern;
    while let Some(open) = rest.find('{') {
        out.push_str(&rest[..open]);
        let after = &rest[open + 1..];
        match after.find('}').and_then(|close| after[..close].parse::<usize>().ok().map(|i| (close, i))) {
            Some((close, i)) => {
                if let Some(a) = args.get(i) {
                    out.push_str(&a.to_string());
                }
                rest = &after[close + 1..];
            }
            None => {
                out.push('{');
                rest = after;
            }
        }
    }
    out.push_str(rest);
    out
}

// ---- Counts, decimals, dates ----

/// "1 brano" / "12 brani"; "1 song"; "1 melodie", "2 melodii", "20 de melodii".
pub fn songs(n: u64) -> String {
    count(n, ("brano", "brani"), ("song", "songs"), ("melodie", "melodii"))
}

/// "1 minuto" / "5 minuti", in the three languages.
pub fn minutes(n: u64) -> String {
    count(n, ("minuto", "minuti"), ("minute", "minutes"), ("minut", "minute"))
}

/// A count and its noun: Romanian puts "de" before the noun from 20 on (and for 100, 200…).
pub fn count(n: u64, it: (&str, &str), en: (&str, &str), ro: (&str, &str)) -> String {
    count_in(lang(), n, it, en, ro)
}

fn count_in(l: Lang, n: u64, it: (&str, &str), en: (&str, &str), ro: (&str, &str)) -> String {
    let (one, many) = match l {
        Lang::It => it,
        Lang::En => en,
        Lang::Ro => ro,
    };
    if n == 1 {
        return format!("1 {one}");
    }
    let de = l == Lang::Ro && (n % 100 >= 20 || (n >= 100 && n.is_multiple_of(100)));
    format!("{n} {}{many}", if de { "de " } else { "" })
}

/// A decimal with a comma in Italian and Romanian, a point in English.
pub fn decimal(x: f64, places: usize) -> String {
    let s = format!("{x:.places$}");
    if lang() == Lang::En { s } else { s.replace('.', ",") }
}

/// Monday = 0.
pub fn weekday(i: usize) -> &'static str {
    const IT: [&str; 7] = ["lunedì", "martedì", "mercoledì", "giovedì", "venerdì", "sabato", "domenica"];
    const EN: [&str; 7] = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"];
    const RO: [&str; 7] = ["luni", "marți", "miercuri", "joi", "vineri", "sâmbătă", "duminică"];
    let i = i % 7;
    match lang() {
        Lang::It => IT[i],
        Lang::En => EN[i],
        Lang::Ro => RO[i],
    }
}

/// January = 0.
pub fn month(i: usize) -> &'static str {
    const IT: [&str; 12] = ["gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno", "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre"];
    const EN: [&str; 12] = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];
    const RO: [&str; 12] = ["ianuarie", "februarie", "martie", "aprilie", "mai", "iunie", "iulie", "august", "septembrie", "octombrie", "noiembrie", "decembrie"];
    let i = i % 12;
    match lang() {
        Lang::It => IT[i],
        Lang::En => EN[i],
        Lang::Ro => RO[i],
    }
}

/// "mercoledì 12 novembre", "Wednesday, November 12", "miercuri, 12 noiembrie".
pub fn long_date(weekday_from_monday: usize, day: u32, month0: usize) -> String {
    let (w, m) = (weekday(weekday_from_monday), month(month0));
    match lang() {
        Lang::It => format!("{w} {day} {m}"),
        Lang::En => format!("{w}, {m} {day}"),
        Lang::Ro => format!("{w}, {day} {m}"),
    }
}

#[cfg(test)]
mod tests {
    use super::{count_in, fill, Lang};
    use std::collections::{BTreeSet, HashSet};

    fn placeholders(s: &str) -> BTreeSet<String> {
        let mut out = BTreeSet::new();
        let mut rest = s;
        while let Some(open) = rest.find('{') {
            let after = &rest[open + 1..];
            if let Some(close) = after.find('}') {
                if after[..close].parse::<usize>().is_ok() {
                    out.insert(after[..close].to_string());
                }
                rest = &after[close + 1..];
            } else {
                break;
            }
        }
        out
    }

    /// The Italian keys the code looks up: `tr("…")`, `trf("…"` and `t("…")`, read from the sources.
    fn keys_in_sources() -> Vec<(String, String)> {
        let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("src");
        let call = regex::Regex::new(r#"\b(?:tr|trf|t)\(\s*"((?:[^"\\]|\\.)*)""#).unwrap();
        let mut out = Vec::new();
        for entry in std::fs::read_dir(dir).unwrap() {
            let path = entry.unwrap().path();
            let name = path.file_name().unwrap().to_string_lossy().to_string();
            if !name.ends_with(".rs") || name == "i18n.rs" || name == "i18n_table.rs" {
                continue;
            }
            let text = std::fs::read_to_string(&path).unwrap();
            for c in call.captures_iter(&text) {
                let key = c[1].replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\");
                out.push((name.clone(), key));
            }
        }
        out
    }

    #[test]
    fn every_text_has_its_row_and_every_row_is_used() {
        let rows = crate::i18n_table::ROWS;
        let mut seen = HashSet::new();
        for (it, en, ro) in rows {
            assert!(seen.insert(*it), "two rows for {it:?}");
            assert!(!en.is_empty() && !ro.is_empty(), "an empty translation for {it:?}");
            let p = placeholders(it);
            assert_eq!(placeholders(en), p, "English places differ for {it:?}");
            assert_eq!(placeholders(ro), p, "Romanian places differ for {it:?}");
        }
        let keys = keys_in_sources();
        let missing: Vec<_> = keys.iter().filter(|(_, k)| !seen.contains(k.as_str())).collect();
        assert!(missing.is_empty(), "no row for: {missing:#?}");
        let used: HashSet<&str> = keys.iter().map(|(_, k)| k.as_str()).collect();
        let stale: Vec<_> = rows.iter().filter(|(it, _, _)| !used.contains(it)).map(|r| r.0).collect();
        assert!(stale.is_empty(), "rows nothing looks up: {stale:#?}");
    }

    #[test]
    fn places_are_filled_by_number() {
        let x = "Daft Punk";
        assert_eq!(fill("{1} by {0}", &[&x, &3]), "3 by Daft Punk");
        assert_eq!(fill("{nope} {0}", &[&1]), "{nope} 1");
    }

    #[test]
    fn romanian_counts_take_de_from_twenty() {
        let r = |n| count_in(Lang::Ro, n, ("", ""), ("", ""), ("melodie", "melodii"));
        assert_eq!((r(1), r(2), r(19), r(20), r(101), r(120), r(200)), (
            "1 melodie".to_string(),
            "2 melodii".to_string(),
            "19 melodii".to_string(),
            "20 de melodii".to_string(),
            "101 melodii".to_string(),
            "120 de melodii".to_string(),
            "200 de melodii".to_string()
        ));
        assert_eq!(Lang::from_tag("ro_RO.UTF-8"), Some(Lang::Ro));
    }
}
