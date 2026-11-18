//! Which lyrics win, as the phone picks them (LyricsRepository.lookup, from BitChord): the
//! listener's sources in the listener's order all start at once, their answers are read in that
//! order, a word by word answer wins at once, a line-synced one too unless word by word is put
//! first, otherwise the first found is kept; Genius (plain text) is asked only when no source had
//! synced lines. Settings choose the sources, their order, the priority and the PaxSenix key.

use serde::{Deserialize, Serialize};

/// A lyrics source as Settings lists it. `id` is the phone's name for it (LyricsSource.kt).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SourceInfo {
    pub id: &'static str,
    pub label: &'static str,
    /// What it is good for, in Italian.
    pub detail: &'static str,
    /// It can time each word.
    pub words: bool,
    /// It needs the listener's PaxSenix key.
    pub needs_key: bool,
}

/// Every source offered, in the phone's default order. PaxSenix's keyless source is not here:
/// it reads a token out of Apple Music's web player.
pub const SOURCES: &[SourceInfo] = &[
    SourceInfo { id: "LRC_RED", label: "lrc.red", detail: "Tempi di Apple per registrazione, sillaba per sillaba", words: true, needs_key: false },
    SourceInfo { id: "BINI_LYRICS", label: "BiniLyrics", detail: "Tempi di Apple abbinati alla registrazione stessa", words: true, needs_key: false },
    SourceInfo { id: "BETTER_LYRICS", label: "BetterLyrics", detail: "Tempi di Apple Music, parola per parola", words: true, needs_key: false },
    SourceInfo { id: "BETTER_LYRICS_PORTATO", label: "BetterLyrics Portato", detail: "Tempi karaoke di QQ Music tramite BetterLyrics", words: true, needs_key: false },
    SourceInfo { id: "PAXSENIX_SPOTIFY", label: "PaxSenix: Spotify", detail: "Testi di Spotify; serve la chiave PaxSenix", words: false, needs_key: true },
    SourceInfo { id: "PAXSENIX_MUSIXMATCH", label: "PaxSenix: Musixmatch", detail: "Tempi di Musixmatch; serve la chiave PaxSenix", words: true, needs_key: true },
    SourceInfo { id: "LYRICS_PLUS", label: "LyricsPlus", detail: "Sillaba per sillaba, su mirror della comunità", words: true, needs_key: false },
    SourceInfo { id: "UNISON", label: "Unison", detail: "Scritti dagli ascoltatori: ha anche ciò che nessuno ha licenziato", words: true, needs_key: false },
    SourceInfo { id: "YOUTUBE_TRANSCRIPT", label: "Sottotitoli di YouTube", detail: "Sottotitoli a tempo del video che suona", words: false, needs_key: false },
    SourceInfo { id: "YOUTUBE_MUSIC", label: "YouTube Music", detail: "La scheda Testo del video che suona", words: false, needs_key: false },
    SourceInfo { id: "KUGOU", label: "KuGou", detail: "Righe intere, forte fuori dal catalogo inglese", words: false, needs_key: false },
    SourceInfo { id: "LRCLIB", label: "LRCLIB", detail: "Solo righe intere, e sempre in linea", words: false, needs_key: false },
    SourceInfo { id: "MUSIXMATCH", label: "Musixmatch", detail: "Righe intere, dal catalogo di testi più grande", words: false, needs_key: false },
    SourceInfo { id: "GENIUS", label: "Genius", detail: "Solo testo, se nessun'altra fonte ha i tempi", words: false, needs_key: false },
];

pub const GENIUS: &str = "GENIUS";

/// The listener's lyrics settings (the phone's `lyrics_sources`, `lyrics_source_order`,
/// `lyrics_prioritize_syllable`, `paxsenix_api_key`).
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct LyricsPrefs {
    /// Source ids in the listener's order; those missing go where the default order has them.
    #[serde(default)]
    pub order: Vec<String>,
    /// Source ids turned off.
    #[serde(default)]
    pub off: Vec<String>,
    /// A word by word answer is waited for even when a line-synced one came first.
    #[serde(default)]
    pub words_first: bool,
    #[serde(default)]
    pub paxsenix_key: Option<String>,
}

impl LyricsPrefs {
    /// Every offered source in the listener's order (`LyricsSource.ordered`): the saved ones as
    /// saved, each one missing put after the source that precedes it by default.
    pub fn ordered(&self) -> Vec<&'static SourceInfo> {
        let mut out: Vec<&'static SourceInfo> = Vec::new();
        for id in &self.order {
            if let Some(s) = SOURCES.iter().find(|s| s.id == id) {
                if !out.iter().any(|o| o.id == s.id) {
                    out.push(s);
                }
            }
        }
        for (i, s) in SOURCES.iter().enumerate() {
            if out.iter().any(|o| o.id == s.id) {
                continue;
            }
            let previous = SOURCES[..i].iter().rev().find_map(|p| out.iter().position(|o| o.id == p.id));
            out.insert(previous.map_or(0, |at| at + 1), s);
        }
        out
    }

    /// The sources asked, in order: those on (and never none: LRCLIB alone then, as the phone).
    pub fn enabled(&self) -> Vec<&'static SourceInfo> {
        let on: Vec<_> = self.ordered().into_iter().filter(|s| !self.off.iter().any(|o| o == s.id)).collect();
        if on.is_empty() {
            SOURCES.iter().filter(|s| s.id == "LRCLIB").collect()
        } else {
            on
        }
    }

    pub fn key(&self) -> Option<&str> {
        self.paxsenix_key.as_deref().map(str::trim).filter(|k| !k.is_empty())
    }
}

/// One source's answer so far.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Slot {
    Pending,
    Missed,
    /// Found, with what it has: word timings, line timings.
    Found { words: bool, synced: bool },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Pick {
    /// An earlier source has not answered yet.
    Wait,
    /// This one wins.
    Win(usize),
    /// Every source answered and none won outright: the first found, if any, and whether any
    /// had synced lines (Genius is asked only when none had).
    Done { fallback: Option<usize>, synced: bool },
}

/// The phone's reading of the answers, in order.
pub(crate) fn pick(slots: &[Slot], words_first: bool) -> Pick {
    let (mut fallback, mut synced_seen) = (None, false);
    for (i, s) in slots.iter().enumerate() {
        match *s {
            Slot::Pending => return Pick::Wait,
            Slot::Missed => {}
            Slot::Found { words, synced } => {
                synced_seen |= synced;
                if words || (synced && !words_first) {
                    return Pick::Win(i);
                }
                fallback = fallback.or(Some(i));
            }
        }
    }
    Pick::Done { fallback, synced: synced_seen }
}

#[cfg(test)]
mod tests {
    use super::*;

    const W: Slot = Slot::Found { words: true, synced: true };
    const L: Slot = Slot::Found { words: false, synced: true };
    const P: Slot = Slot::Found { words: false, synced: false };
    use Slot::{Missed as M, Pending as Q};

    #[test]
    fn answers_are_read_in_the_listeners_order() {
        // A later word by word answer waits for an earlier source still asking.
        assert_eq!(pick(&[Q, W], false), Pick::Wait);
        assert_eq!(pick(&[M, W], false), Pick::Win(1));
        // Word by word wins at once, whatever the priority.
        assert_eq!(pick(&[W, L], true), Pick::Win(0));
        // Lines win too, unless word by word comes first.
        assert_eq!(pick(&[L, W], false), Pick::Win(0));
        assert_eq!(pick(&[L, W], true), Pick::Win(1));
        assert_eq!(pick(&[L, M], true), Pick::Done { fallback: Some(0), synced: true });
        // Plain text is kept as the fallback, the first one found.
        assert_eq!(pick(&[P, M, P], false), Pick::Done { fallback: Some(0), synced: false });
        assert_eq!(pick(&[P, L], false), Pick::Win(1));
        assert_eq!(pick(&[M, M], false), Pick::Done { fallback: None, synced: false });
        assert_eq!(pick(&[], false), Pick::Done { fallback: None, synced: false });
    }

    #[test]
    fn the_order_keeps_new_sources_where_they_belong() {
        let ids = |p: &LyricsPrefs| p.ordered().iter().map(|s| s.id).collect::<Vec<_>>();
        let default = LyricsPrefs::default();
        assert_eq!(ids(&default), SOURCES.iter().map(|s| s.id).collect::<Vec<_>>());
        // LRCLIB moved to the top (Settings saves the whole order): kept so.
        let mut full: Vec<String> = SOURCES.iter().map(|s| s.id.to_string()).filter(|id| id != "LRCLIB").collect();
        full.insert(0, "LRCLIB".into());
        let moved = LyricsPrefs { order: full.clone(), ..Default::default() };
        assert_eq!(ids(&moved)[..3], ["LRCLIB", "LRC_RED", "BINI_LYRICS"]);
        // A source the saved order does not know yet goes after the one before it by default.
        full.retain(|id| id != "UNISON");
        let older = LyricsPrefs { order: full, ..Default::default() };
        let o = ids(&older);
        assert_eq!(o[o.iter().position(|id| *id == "LYRICS_PLUS").unwrap() + 1], "UNISON");
        assert_eq!(o.len(), SOURCES.len());
        // Unknown ids (the hidden or removed ones) are dropped.
        let odd = LyricsPrefs { order: vec!["PAXSENIX".into(), "GENIUS".into(), "GENIUS".into()], ..Default::default() };
        assert_eq!(ids(&odd).iter().filter(|id| **id == "GENIUS").count(), 1);
        assert_eq!(ids(&odd).len(), SOURCES.len());
    }

    #[test]
    fn some_source_is_always_asked() {
        let all_off = LyricsPrefs { off: SOURCES.iter().map(|s| s.id.to_string()).collect(), ..Default::default() };
        assert_eq!(all_off.enabled().iter().map(|s| s.id).collect::<Vec<_>>(), ["LRCLIB"]);
        let some = LyricsPrefs { off: vec!["GENIUS".into()], ..Default::default() };
        assert!(!some.enabled().iter().any(|s| s.id == GENIUS));
        assert_eq!(LyricsPrefs { paxsenix_key: Some("  ".into()), ..Default::default() }.key(), None);
    }
}
