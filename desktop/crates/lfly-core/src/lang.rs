//! The language of the few messages the core writes itself and of the DJ's lines. Italian until a
//! window sets another (the Tauri app never does, so it stays as it was).

use std::sync::atomic::{AtomicU8, Ordering};

static LANG: AtomicU8 = AtomicU8::new(0);

/// "it", "en" or "ro"; anything else is Italian.
pub fn set(code: &str) {
    LANG.store(
        match code {
            "en" => 1,
            "ro" => 2,
            _ => 0,
        },
        Ordering::Relaxed,
    );
}

/// The language set by a window, if any.
pub fn get() -> Option<&'static str> {
    match LANG.load(Ordering::Relaxed) {
        1 => Some("en"),
        2 => Some("ro"),
        _ => None,
    }
}

/// The text in the current language.
pub fn pick(it: &'static str, en: &'static str, ro: &'static str) -> &'static str {
    match LANG.load(Ordering::Relaxed) {
        1 => en,
        2 => ro,
        _ => it,
    }
}
