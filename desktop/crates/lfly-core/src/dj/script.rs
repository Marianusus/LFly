//! What the DJ says (the phone's `DjScript`): one line as each block starts, from phrasebooks in
//! Italian, English and Romanian. Pure, so every line can be tested.

use std::collections::HashMap;
use std::sync::LazyLock;

use lfly_connect::protocol::TrackRef;
use rand::Rng;
use regex::Regex;

use super::{Block, BlockKind};

/// Languages the DJ speaks; anything else falls back to English.
pub const LANGUAGES: [&str; 3] = ["it", "en", "ro"];

const SUBJECT: &str = "{subject}";
const SONG: &str = "{song}";
const TITLE_MAX: usize = 60;
const NAME_MAX: usize = 40;

/// One DJ session's memory: the last intro said for each kind, so the same one never comes twice
/// in a row, and the last phrase that named a first song, so blocks do not all open the same way.
/// A new session starts with a new one.
#[derive(Default)]
pub struct Script {
    last_intro: HashMap<BlockKind, &'static str>,
    last_lead: Option<&'static str>,
}

impl Script {
    /// The line spoken as `block` starts, in `language` ("it", "en" or "ro"); `first` for the
    /// first block of a session. The same seed always gives the same line.
    pub fn line<R: Rng + ?Sized>(&mut self, block: &Block, language: &str, first: bool, rng: &mut R) -> String {
        let lang = language_of(language);
        let book = book(lang);
        let track = block.tracks.first();
        let raw_subject = block.subject.clone().or_else(|| track.filter(|_| block.kind == BlockKind::FollowedArtist).map(|t| t.artist.clone()));
        let subject = raw_subject.map(|s| clean_name(&s, lang, NAME_MAX)).filter(|s| !s.is_empty());
        let song = track.and_then(|t| song_text(t, subject.as_deref(), lang));

        let usable = |t: &&'static str| (song.is_some() || !t.contains(SONG)) && (subject.is_some() || !t.contains(SUBJECT));
        let mut intros: Vec<&'static str> = book.intros(block.kind).iter().copied().filter(usable).collect();
        if intros.is_empty() {
            intros = book.generic.iter().copied().filter(usable).collect();
        }
        let mut parts: Vec<&'static str> = Vec::with_capacity(3);
        if first {
            parts.push(pick(book.greetings, rng, None));
        }
        let intro = pick(&intros, rng, self.last_intro.get(&block.kind).copied());
        self.last_intro.insert(block.kind, intro);
        parts.push(intro);
        if song.is_some() && !intro.contains(SONG) {
            let lead = pick(book.leads, rng, self.last_lead);
            self.last_lead = Some(lead);
            parts.push(lead);
        }
        parts.join(" ").replace(SUBJECT, subject.as_deref().unwrap_or("")).replace(SONG, song.as_deref().unwrap_or(""))
    }
}

/// A short line to try a voice with in Settings.
pub fn sample(language: &str) -> &'static str {
    match language_of(language) {
        "it" => "Ciao, sono il tuo DJ. Questa è la mia voce: ti piace come suono?",
        "ro" => "Salut, sunt DJ-ul tău. Aceasta e vocea mea: îți place cum sună?",
        _ => "Hey, I'm your DJ. This is my voice: do you like how I sound?",
    }
}

/// "it", "en" or "ro" from a tag like "it", "IT" or "ro-RO"; English when unknown.
pub fn language_of(language: &str) -> &'static str {
    let lower = language.trim().to_lowercase();
    let code = lower.split(['-', '_']).next().unwrap_or("");
    LANGUAGES.into_iter().find(|l| *l == code).unwrap_or("en")
}

fn pick<R: Rng + ?Sized>(list: &[&'static str], rng: &mut R, avoid: Option<&'static str>) -> &'static str {
    let pool: Vec<&'static str> = match avoid {
        Some(a) if list.len() > 1 => list.iter().copied().filter(|t| *t != a).collect(),
        _ => list.to_vec(),
    };
    pool[rng.gen_range(0..pool.len())]
}

/// "Title by Artist", or just the title when the artist is the block's subject.
fn song_text(track: &TrackRef, subject: Option<&str>, lang: &str) -> Option<String> {
    let title = clean_title(&track.title, &track.artist, lang);
    if title.is_empty() {
        return None;
    }
    let artist = clean_name(&track.artist, lang, NAME_MAX);
    if artist.is_empty() || subject.is_some_and(|s| s.to_lowercase() == artist.to_lowercase()) {
        return Some(title);
    }
    Some(format!("{title} {} {artist}", book(lang).by))
}

static BRACKETED: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"\s*[(\[{【]([^()\[\]{}【】]*)[)\]}】]").unwrap());
static DASH_TAIL: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"\s+[-–—|]\s+([^-–—|]*)$").unwrap());
static CLUTTER: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(concat!(
        r"(?i)\b(official|video|videoclip|audio|lyrics?|visuali[sz]er|remaster(ed)?|hd|hq|4k|explicit|",
        r"mono|stereo|radio edit|mv|testo|letra|versuri|full album|color coded|ufficiale|oficial)\b"
    ))
    .unwrap()
});
static FEAT_START: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"(?i)^(feat\.?|ft\.?|featuring|with)\s").unwrap());
/// The Kotlin version checks a space or the end with a look-ahead; here it is consumed with the
/// spaces after it, which the Kotlin removes anyway.
static FEAT: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"(?i)\s*\b(featuring|feat\.?|ft\.?)(?:\s+|$)").unwrap());
static AMPERSAND: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"\s*&\s*").unwrap());
/// Variation selectors, the zero-width joiner and the keycap mark (emoji leftovers).
static INVISIBLE: LazyLock<Regex> = LazyLock::new(|| Regex::new("[\u{FE00}-\u{FE0F}\u{200D}\u{20E3}]").unwrap());
static STRAY: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"[^\p{L}\p{M}\p{N}\s.,'’!?:()\-]").unwrap());
static EMPTY_BRACKETS: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"\(\s*\)").unwrap());
static SPACES: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"\s+").unwrap());

/// A song title the voice can read: no "(Official Video)" or "- Remastered 2011", no "Artist - "
/// prefix from video titles, "feat." spoken as a word, no emoji, and not too long.
pub fn clean_title(title: &str, artist: &str, language: &str) -> String {
    let mut text = title.trim().to_string();
    let prefix = artist.trim();
    if !prefix.is_empty() {
        if let Ok(with_dash) = Regex::new(&format!(r"(?i)^{}\s+[-–—]\s+", regex::escape(prefix))) {
            text = with_dash.replace(&text, "").into_owned();
        }
    }
    text = BRACKETED
        .replace_all(&text, |c: &regex::Captures| {
            let content = c[1].trim();
            if CLUTTER.is_match(content) {
                String::new()
            } else if FEAT_START.is_match(content) {
                format!(" {content}")
            } else {
                c[0].to_string()
            }
        })
        .into_owned();
    while let Some(tail) = DASH_TAIL.captures(&text) {
        if !CLUTTER.is_match(&tail[1]) {
            break;
        }
        let start = tail.get(0).expect("whole match").start();
        text.truncate(start);
    }
    cap(&speakable(&text, language_of(language)), TITLE_MAX)
}

/// An artist or mix name the voice can read, at most `max` characters.
pub fn clean_name(name: &str, language: &str, max: usize) -> String {
    cap(&speakable(name, language_of(language)), max)
}

/// Spells out "feat." and "&", drops emoji and symbols and tidies the spaces.
fn speakable(text: &str, lang: &str) -> String {
    let book = book(lang);
    let t = FEAT.replace_all(text, format!(" {} ", book.with).as_str()).into_owned();
    let t = AMPERSAND.replace_all(&t, format!(" {} ", book.and).as_str()).into_owned();
    let t = INVISIBLE.replace_all(&t, "").into_owned();
    let t = STRAY.replace_all(&t, " ").into_owned();
    let t = EMPTY_BRACKETS.replace_all(&t, " ").into_owned();
    let t = SPACES.replace_all(&t, " ").into_owned();
    t.replace(" ,", ",").trim_matches([' ', '-', ',', ':', '|']).to_string()
}

/// Cuts `text` at a word boundary so a huge title does not swallow the line (in characters).
fn cap(text: &str, max: usize) -> String {
    if text.chars().count() <= max {
        return text.to_string();
    }
    let mut cut: String = text.chars().take(max).collect();
    if let Some(space) = cut.rfind(' ') {
        if cut[..space].chars().count() > max / 2 {
            cut.truncate(space);
        }
    }
    if cut.matches('(').count() > cut.matches(')').count() {
        if let Some(open) = cut.rfind('(') {
            cut.truncate(open);
        }
    }
    cut.trim_matches([' ', '-', ',', ':']).to_string()
}

/// Everything the DJ can say in one language.
struct Phrasebook {
    /// Openers for the first block; the block intro follows.
    greetings: &'static [&'static str],
    flow: &'static [&'static str],
    top_played: &'static [&'static str],
    followed_artist: &'static [&'static str],
    discovery: &'static [&'static str],
    throwback: &'static [&'static str],
    mix: &'static [&'static str],
    /// For blocks whose kind needs a subject that is missing.
    generic: &'static [&'static str],
    /// Names the first song after an intro that does not.
    leads: &'static [&'static str],
    /// "Title <by> Artist".
    by: &'static str,
    /// Spoken for "feat.".
    with: &'static str,
    /// Spoken for "&".
    and: &'static str,
}

impl Phrasebook {
    fn intros(&self, kind: BlockKind) -> &'static [&'static str] {
        match kind {
            BlockKind::Flow => self.flow,
            BlockKind::TopPlayed => self.top_played,
            BlockKind::FollowedArtist => self.followed_artist,
            BlockKind::Discovery => self.discovery,
            BlockKind::Throwback => self.throwback,
            BlockKind::Mix => self.mix,
        }
    }
}

fn book(lang: &str) -> &'static Phrasebook {
    match lang {
        "it" => &ITALIAN,
        "ro" => &ROMANIAN,
        _ => &ENGLISH,
    }
}

static ITALIAN: Phrasebook = Phrasebook {
    greetings: &["Ciao! Sono il tuo DJ.", "Eccomi, sono il tuo DJ.", "Ciao, alla consolle ci sono io.", "Eccoci qua, sono il tuo DJ."],
    flow: &[
        "Partiamo da {song}, uno dei tuoi più ascoltati, e vediamo dove ci porta.",
        "Si comincia da {song}, che ascolti spesso. Da lì ti porto io.",
        "Metto su {song}, uno dei tuoi pezzi, e da qui ci muoviamo insieme.",
        "Uno dei tuoi preferiti per aprire: {song}. Poi lasciati portare.",
    ],
    top_played: &[
        "Partiamo dai tuoi pezzi preferiti, quelli che non ti stanchi mai di ascoltare.",
        "Ecco i brani che ascolti di più.",
        "Un giro tra i tuoi preferiti di sempre.",
        "Questi li conosci a memoria. Via con {song}.",
    ],
    followed_artist: &[
        "Adesso un po' di {subject}, uno degli artisti che segui.",
        "È il momento di {subject}.",
        "Spazio a {subject}, solo per te.",
        "Ti piace {subject}, quindi ecco qualche pezzo.",
    ],
    discovery: &[
        "Ora qualcosa di nuovo, scelto apposta per i tuoi gusti.",
        "Ti porto a scoprire qualche pezzo che forse non conosci ancora.",
        "Un po' di novità, vicine a quello che ascolti di solito.",
        "Facciamo una piccola scoperta insieme: ecco {song}.",
    ],
    throwback: &[
        "Torniamo un po' indietro, a pezzi che non senti da un po'.",
        "Un tuffo nel passato: ti ricordi {song}?",
        "Rispolveriamo qualche vecchio amore.",
        "Era da un po' che non li ascoltavi.",
    ],
    mix: &["Cambiamo atmosfera con il tuo {subject}.", "Prossima tappa: {subject}.", "Ora un po' di {subject}, preparato per te."],
    generic: &["Ecco un po' di musica scelta per te.", "Andiamo avanti con la musica.", "Continuiamo con qualcosa che ti piacerà."],
    leads: &["Si parte con {song}.", "Apriamo con {song}.", "Cominciamo con {song}.", "Primo pezzo: {song}."],
    by: "di",
    with: "con",
    and: "e",
};

static ENGLISH: Phrasebook = Phrasebook {
    greetings: &["Hey, it's your DJ.", "Hi there, your DJ here.", "Good to have you back. It's your DJ.", "Hey! I'm your DJ today."],
    flow: &[
        "Let's start from {song}, one you play a lot, and see where it takes us.",
        "Kicking off with {song}, one of your regulars. I'll take it from there.",
        "One of yours to open: {song}. Then let the music lead.",
        "Starting with {song}, a song you keep coming back to, and flowing on from it.",
    ],
    top_played: &[
        "Let's start with your favorites, the ones you keep coming back to.",
        "Here are the songs you play the most.",
        "A little run through your all-time favorites.",
        "You know these by heart, so let's go with {song}.",
    ],
    followed_artist: &[
        "Time for some {subject}, one of the artists you follow.",
        "Let's spend a few songs with {subject}.",
        "Up next, a little set of {subject}.",
        "You follow {subject}, so here's a handful just for you.",
    ],
    discovery: &[
        "Now something new, picked for your taste.",
        "Let me show you a few songs you might not know yet.",
        "Time for some discoveries, close to what you usually play.",
        "Here's something fresh you might love: {song}.",
    ],
    throwback: &[
        "Let's go back a bit, to songs you haven't heard in a while.",
        "Time for a throwback. Remember {song}?",
        "Let's dust off a few old favorites.",
        "It's been a while since you played these.",
    ],
    mix: &["Switching the mood with your {subject}.", "Next stop, {subject}.", "Now a little {subject}, made for you."],
    generic: &["Here's some music picked just for you.", "Let's keep the music going.", "More music coming your way."],
    leads: &["Starting with {song}.", "First up, {song}.", "Kicking off with {song}.", "Here's {song}."],
    by: "by",
    with: "with",
    and: "and",
};

static ROMANIAN: Phrasebook = Phrasebook {
    greetings: &["Salut, sunt DJ-ul tău.", "Bună! Aici e DJ-ul tău.", "Mă bucur că ești aici. Sunt DJ-ul tău.", "Hei, la pupitru sunt eu, DJ-ul tău."],
    flow: &[
        "Pornim de la {song}, una dintre piesele tale preferate, și vedem unde ne duce.",
        "Începem cu {song}, pe care o asculți des. De aici te duc eu.",
        "Una de-a ta pentru început: {song}. Apoi lasă-te purtat de muzică.",
        "Deschidem cu {song}, o piesă la care revii mereu, și mergem mai departe de la ea.",
    ],
    top_played: &[
        "Începem cu preferatele tale, piesele la care revii mereu.",
        "Iată melodiile pe care le asculți cel mai des.",
        "O tură prin preferatele tale dintotdeauna.",
        "Pe astea le știi pe de rost. Hai cu {song}.",
    ],
    followed_artist: &[
        "Urmează câteva piese de la {subject}, din artiștii pe care îi urmărești.",
        "E momentul pentru {subject}.",
        "Facem loc pentru {subject}, special pentru tine.",
        "Îți place {subject}, așa că am pregătit câteva piese.",
    ],
    discovery: &[
        "Acum ceva nou, ales pe gustul tău.",
        "Hai să descoperim câteva piese pe care poate nu le știi încă.",
        "Puțină noutate, aproape de ce asculți de obicei.",
        "Uite ceva proaspăt care s-ar putea să-ți placă: {song}.",
    ],
    throwback: &[
        "Ne întoarcem puțin în timp, la piese pe care nu le-ai mai ascultat de ceva vreme.",
        "Un pic de nostalgie. Îți amintești de {song}?",
        "Scoatem din sertar câteva preferate mai vechi.",
        "Nu le-ai mai ascultat de ceva timp.",
    ],
    mix: &["Schimbăm atmosfera cu {subject}.", "Următoarea oprire: {subject}.", "Acum puțin {subject}, pregătit pentru tine."],
    generic: &["Iată puțină muzică aleasă pentru tine.", "Mergem mai departe cu muzica.", "Urmează ceva ce sigur o să-ți placă."],
    leads: &["Începem cu {song}.", "Pornim cu {song}.", "Deschidem cu {song}.", "Prima piesă: {song}."],
    by: "de la",
    with: "cu",
    and: "și",
};

#[cfg(test)]
mod tests {
    //! The phone's DjScriptTest, case for case (the Android voice-choice cases stay on the phone).
    use super::*;
    use crate::dj::testing::{rng, track};

    fn songs() -> Vec<TrackRef> {
        vec![track("Blinding Lights", "Blinding Lights", "The Weeknd"), track("Levitating", "Levitating", "Dua Lipa")]
    }

    fn subject_of(kind: BlockKind) -> Option<String> {
        match kind {
            BlockKind::FollowedArtist => Some("Måneskin".into()),
            BlockKind::Mix => Some("Pop Mix".into()),
            _ => None,
        }
    }

    fn line(block: &Block, lang: &str, first: bool, seed: u64) -> String {
        Script::default().line(block, lang, first, &mut rng(seed))
    }

    #[test]
    fn every_kind_language_and_opening_gives_a_line_naming_its_subject() {
        for kind in BlockKind::ALL {
            for lang in LANGUAGES {
                for first in [true, false] {
                    let subject = subject_of(kind);
                    for seed in 0..20 {
                        let l = line(&Block { kind, tracks: songs(), subject: subject.clone() }, lang, first, seed);
                        assert!(!l.trim().is_empty(), "{kind:?}/{lang}/{first}");
                        assert!(!l.contains('{') && !l.contains('}'), "unfilled placeholder in '{l}'");
                        if let Some(s) = &subject {
                            assert!(l.contains(s.as_str()), "'{l}' misses {s}");
                        }
                    }
                }
            }
        }
    }

    #[test]
    fn lines_without_songs_or_subject_still_read_well() {
        for kind in BlockKind::ALL {
            for lang in LANGUAGES {
                for seed in 0..10 {
                    let l = line(&Block { kind, tracks: vec![], subject: None }, lang, seed % 2 == 0, seed);
                    assert!(!l.trim().is_empty());
                    assert!(!l.contains('{') && !l.contains('}'));
                }
            }
        }
    }

    #[test]
    fn a_followed_artist_block_without_subject_names_the_first_artist() {
        let l = line(&Block { kind: BlockKind::FollowedArtist, tracks: songs(), subject: None }, "it", false, 1);
        assert!(l.contains("The Weeknd"), "{l}");
    }

    #[test]
    fn the_first_song_is_usually_named() {
        let block = Block { kind: BlockKind::TopPlayed, tracks: songs(), subject: None };
        let named = (0..50).filter(|seed| line(&block, "it", false, *seed).contains("Blinding Lights")).count();
        assert!(named >= 40, "only {named} of 50");
    }

    #[test]
    fn clutter_is_stripped_from_titles() {
        assert_eq!(clean_title("Song (Official Video) [Remastered]", "X", "en"), "Song");
        assert_eq!(clean_title("Yesterday - Remastered 2009", "The Beatles", "en"), "Yesterday");
        assert_eq!(clean_title("Adele - Hello (Official Music Video)", "Adele", "en"), "Hello");
        assert_eq!(clean_title("Song (Lyrics) (Visualizer)", "X", "it"), "Song");
        assert_eq!(clean_title("Live Forever (Live at Wembley)", "Oasis", "en"), "Live Forever (Live at Wembley)");
    }

    #[test]
    fn feat_and_ampersand_are_spoken_in_each_language() {
        assert_eq!(clean_title("Song (feat. B)", "A", "it"), "Song con B");
        assert_eq!(clean_title("Song ft. B", "A", "en"), "Song with B");
        assert_eq!(clean_name("A feat. B", "ro", NAME_MAX), "A cu B");
        assert_eq!(clean_name("Simon & Garfunkel", "it", NAME_MAX), "Simon e Garfunkel");
        assert_eq!(clean_name("Simon & Garfunkel", "en", NAME_MAX), "Simon and Garfunkel");
        assert_eq!(clean_name("Simon & Garfunkel", "ro", NAME_MAX), "Simon și Garfunkel");
        assert_eq!(clean_name("Daft Punk", "en", NAME_MAX), "Daft Punk");
    }

    #[test]
    fn emoji_and_symbols_are_dropped_and_long_titles_are_capped() {
        assert_eq!(clean_title("Love ❤️ Song 🔥 ★", "X", "en"), "Love Song");
        let long = clean_title(&"word ".repeat(40), "X", "en");
        assert!(long.chars().count() <= 60);
        assert!(!long.ends_with(' '));
        // Cut by characters, never inside one (Romanian and accented titles).
        let accented = clean_title(&"șțăîâ èéù ".repeat(12), "X", "ro");
        assert!(accented.chars().count() <= 60 && !accented.is_empty());
    }

    #[test]
    fn unknown_languages_fall_back_to_english() {
        let block = Block { kind: BlockKind::Discovery, tracks: songs(), subject: None };
        assert_eq!(line(&block, "en", true, 3), line(&block, "de", true, 3));
        assert_eq!(line(&block, "it", false, 3), line(&block, "it-IT", false, 3));
        assert_eq!(language_of(""), "en");
    }

    #[test]
    fn the_same_seed_gives_the_same_line() {
        for kind in BlockKind::ALL {
            for lang in LANGUAGES {
                let block = Block { kind, tracks: songs(), subject: subject_of(kind) };
                assert_eq!(line(&block, lang, true, 42), line(&block, lang, true, 42));
            }
        }
    }

    #[test]
    fn an_intro_is_never_used_twice_in_a_row_within_a_session() {
        let mut script = Script::default();
        let mut r = rng(7);
        let block = Block { kind: BlockKind::Throwback, tracks: vec![], subject: None };
        let mut previous = None;
        for _ in 0..40 {
            let l = script.line(&block, "en", false, &mut r);
            assert_ne!(previous.as_ref(), Some(&l));
            previous = Some(l);
        }
    }

    #[test]
    fn lines_stay_short() {
        let long_song = track("x", &format!("{} {} {}", "A".repeat(30), "B".repeat(30), "C".repeat(30)), &"D".repeat(80));
        for kind in BlockKind::ALL {
            for lang in LANGUAGES {
                for seed in 0..20 {
                    let normal = line(&Block { kind, tracks: songs(), subject: subject_of(kind) }, lang, seed % 2 == 0, seed);
                    assert!(normal.chars().count() <= 200, "{normal}");
                    let huge = line(&Block { kind, tracks: vec![long_song.clone()], subject: subject_of(kind) }, lang, true, seed);
                    assert!(huge.chars().count() <= 280, "{huge}");
                }
            }
        }
    }
}
