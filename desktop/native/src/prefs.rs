//! The window's own settings (Tauri: prefs.svelte.ts), under the same store key "prefs": the
//! lyrics, the page LFly opens on, motion, the listening history. Shared with the Tauri app.

use gpui_kit::*;
use serde_json::{json, Value};

const KEY: &str = "prefs";

#[derive(Clone, Debug, PartialEq)]
pub struct PrefsData {
    /// "s", "m" or "l".
    pub lyrics_size: String,
    pub lyrics_center: bool,
    /// Word by word when the source has the timings; off: whole lines.
    pub lyrics_words: bool,
    pub lyrics_source: bool,
    /// "home", "library", "liked" or "search".
    pub start_page: String,
    /// "system", "reduce" or "full".
    pub motion: String,
    pub pause_history: bool,
}

impl PrefsData {
    /// The lyrics' scale: { s: 0.82, m: 1, l: 1.22 }.
    pub fn lyrics_scale(&self) -> f32 {
        match self.lyrics_size.as_str() {
            "s" => 0.82,
            "l" => 1.22,
            _ => 1.,
        }
    }
}

pub struct Prefs {
    pub v: PrefsData,
}

struct PrefsGlobal(Entity<Prefs>);
impl Global for PrefsGlobal {}

fn read() -> PrefsData {
    let s = lfly_core::store::get(KEY).ok().flatten().unwrap_or(Value::Null);
    let text = |k: &str, d: &str, ok: &[&str]| s.get(k).and_then(Value::as_str).filter(|v| ok.contains(v)).unwrap_or(d).to_string();
    let flag = |k: &str, d: bool| s.get(k).and_then(Value::as_bool).unwrap_or(d);
    PrefsData {
        lyrics_size: text("lyricsSize", "m", &["s", "m", "l"]),
        lyrics_center: s.get("lyricsAlign").and_then(Value::as_str) == Some("center"),
        lyrics_words: flag("lyricsWords", true),
        lyrics_source: flag("lyricsSource", true),
        start_page: text("startPage", "home", &["home", "library", "liked", "search"]),
        motion: text("motion", "system", &["system", "reduce", "full"]),
        pause_history: flag("pauseHistory", false),
    }
}

impl Prefs {
    pub fn new(cx: &mut App) -> Entity<Self> {
        let e = cx.new(|_| Self { v: read() });
        cx.set_global(PrefsGlobal(e.clone()));
        e
    }

    pub fn entity(cx: &App) -> Entity<Self> {
        cx.global::<PrefsGlobal>().0.clone()
    }

    pub fn get(cx: &App) -> PrefsData {
        cx.global::<PrefsGlobal>().0.read(cx).v.clone()
    }

    /// Changes them and saves over what is stored (keys only Tauri knows stay).
    pub fn update(&mut self, f: impl FnOnce(&mut PrefsData), cx: &mut Context<Self>) {
        f(&mut self.v);
        let mut s = lfly_core::store::get(KEY).ok().flatten().filter(Value::is_object).unwrap_or_else(|| json!({}));
        let v = &self.v;
        for (k, x) in [
            ("lyricsSize", json!(v.lyrics_size)),
            ("lyricsAlign", json!(if v.lyrics_center { "center" } else { "left" })),
            ("lyricsWords", json!(v.lyrics_words)),
            ("lyricsSource", json!(v.lyrics_source)),
            ("startPage", json!(v.start_page)),
            ("motion", json!(v.motion)),
            ("pauseHistory", json!(v.pause_history)),
        ] {
            s[k] = x;
        }
        if let Err(e) = lfly_core::store::set(KEY, &s) {
            log::warn!("{KEY}: {e}");
        }
        cx.notify();
    }
}
