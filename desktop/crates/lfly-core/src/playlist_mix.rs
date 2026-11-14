//! Playlist Mix, kept and played (the phone's `MusicRepository` playlist mixes and the mixed
//! transitions in `PlaybackManager`).
//!
//! A playlist's Mix lives in `playlist_mixes.json` next to the library files, in the phone's own
//! JSON (Gson, enum names), so a phone's file reads as it is. Neither Drive nor LFly Connect carry
//! it on the phone either: it stays on the device. Writes re-read the file and change one entry,
//! so fields this version does not know (a phone's jogwheel) survive.
//!
//! A transition belongs to a pair of songs: wherever the two play one after the other in the
//! queue, the first enabled playlist that has them next to each other decides how they change.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::time::SystemTime;

use lfly_mix::curves::{EqCurve, FilterCurve, MixRecipe, VolumeCurve};
use lfly_mix::effects::{EffectCurve, LoopCurve};
use lfly_mix::planner::PlanFixes;
use lfly_mix::playlist::{pair_key, MixPreset, MixTransition, PlaylistMix};
use serde_json::{Map, Value};

pub const FILE: &str = "playlist_mixes.json";

pub fn path(dir: &Path) -> PathBuf {
    dir.join(FILE)
}

// --- The phone's JSON -------------------------------------------------------------------------

fn read_raw(dir: &Path) -> Map<String, Value> {
    std::fs::read_to_string(path(dir)).ok().and_then(|s| serde_json::from_str::<Value>(&s).ok()).and_then(|v| v.as_object().cloned()).unwrap_or_default()
}

/// Every playlist's Mix in `dir`; a transition without a known preset is left out (the phone's
/// repair on load), a missing field takes its default.
pub fn load(dir: &Path) -> HashMap<String, PlaylistMix> {
    read_raw(dir).iter().map(|(id, v)| (id.clone(), parse_mix(v))).collect()
}

fn parse_mix(v: &Value) -> PlaylistMix {
    let transitions = v
        .get("transitions")
        .and_then(Value::as_object)
        .map(|t| t.iter().filter_map(|(k, t)| Some((k.clone(), parse_transition(t)?))).collect())
        .unwrap_or_default();
    PlaylistMix { enabled: v.get("enabled").and_then(Value::as_bool).unwrap_or(false), transitions }
}

fn parse_transition(v: &Value) -> Option<MixTransition> {
    let preset = MixPreset::from_id(v.get("preset")?.as_str()?)?;
    Some(MixTransition {
        preset,
        custom: v.get("custom").filter(|c| c.is_object()).map(parse_recipe),
        bars: v.get("bars").and_then(Value::as_u64).map(|b| b as u32),
        beatmatch: v.get("beatmatch").and_then(Value::as_bool).unwrap_or(true),
        exit_ms: v.get("exitMs").and_then(Value::as_i64),
        entry_ms: v.get("entryMs").and_then(Value::as_i64),
    })
}

const VOLUMES: [(VolumeCurve, &str); 7] = [
    (VolumeCurve::Crossfade, "CROSSFADE"),
    (VolumeCurve::SmoothCrossfade, "SMOOTH_CROSSFADE"),
    (VolumeCurve::Overlap, "OVERLAP"),
    (VolumeCurve::FadeInFadeOut, "FADE_IN_FADE_OUT"),
    (VolumeCurve::CutInFadeOut, "CUT_IN_FADE_OUT"),
    (VolumeCurve::FadeInCutOut, "FADE_IN_CUT_OUT"),
    (VolumeCurve::CenterCut, "CENTER_CUT"),
];
const EQS: [(EqCurve, &str); 4] = [(EqCurve::None, "NONE"), (EqCurve::CenterBassSwap, "CENTER_BASS_SWAP"), (EqCurve::EndBassSwap, "END_BASS_SWAP"), (EqCurve::ThreeBand, "THREE_BAND")];
const FILTERS: [(FilterCurve, &str); 3] = [(FilterCurve::None, "NONE"), (FilterCurve::LowPass, "LOW_PASS"), (FilterCurve::HighPass, "HIGH_PASS")];
const EFFECTS: [(EffectCurve, &str); 13] = [
    (EffectCurve::None, "NONE"),
    (EffectCurve::EchoHalfOut, "ECHO_HALF_OUT"),
    (EffectCurve::Echo3qOut, "ECHO_3Q_OUT"),
    (EffectCurve::Echo1Out, "ECHO_1_OUT"),
    (EffectCurve::EchoHalfCut, "ECHO_HALF_CUT"),
    (EffectCurve::Echo3qCut, "ECHO_3Q_CUT"),
    (EffectCurve::Echo1Cut, "ECHO_1_CUT"),
    (EffectCurve::DelayHalfCut, "DELAY_HALF_CUT"),
    (EffectCurve::Delay3qCut, "DELAY_3Q_CUT"),
    (EffectCurve::ReverbOutCenter, "REVERB_OUT_CENTER"),
    (EffectCurve::ReverbOutEnd, "REVERB_OUT_END"),
    (EffectCurve::ReverbCutEnd, "REVERB_CUT_END"),
    (EffectCurve::NoiseOutEnd, "NOISE_OUT_END"),
];
const LOOPS: [(LoopCurve, &str); 7] = [
    (LoopCurve::None, "NONE"),
    (LoopCurve::OneBeat, "ONE_BEAT"),
    (LoopCurve::TwoBeat, "TWO_BEAT"),
    (LoopCurve::FourBeat, "FOUR_BEAT"),
    (LoopCurve::EightBeat, "EIGHT_BEAT"),
    (LoopCurve::SixteenBeat, "SIXTEEN_BEAT"),
    (LoopCurve::Halving, "HALVING"),
];

fn named<T: Copy>(table: &[(T, &str)], v: Option<&Value>, missing: T) -> T {
    v.and_then(Value::as_str).and_then(|s| table.iter().find(|(_, n)| *n == s)).map_or(missing, |(t, _)| *t)
}

fn name_of<T: Copy + PartialEq>(table: &[(T, &'static str)], t: T) -> &'static str {
    table.iter().find(|(x, _)| *x == t).map_or("NONE", |(_, n)| n)
}

/// A custom recipe; a missing ingredient is none (the phone's `repaired`). The jogwheel is not
/// played here yet: it is kept in the file, not in the recipe.
fn parse_recipe(v: &Value) -> MixRecipe {
    MixRecipe {
        volume: named(&VOLUMES, v.get("volume"), VolumeCurve::Crossfade),
        eq: named(&EQS, v.get("eq"), EqCurve::None),
        filter_in: named(&FILTERS, v.get("filterIn"), FilterCurve::None),
        filter_out: named(&FILTERS, v.get("filterOut"), FilterCurve::None),
        riser: v.get("riser").and_then(Value::as_bool).unwrap_or(false),
        effect: named(&EFFECTS, v.get("effect"), EffectCurve::None),
        looping: named(&LOOPS, v.get("loop"), LoopCurve::None),
    }
}

/// `t` written over `old` (a transition as the file had it): what this version knows is replaced,
/// the rest kept.
fn transition_json(t: &MixTransition, old: Option<&Value>) -> Value {
    let mut o = old.and_then(Value::as_object).cloned().unwrap_or_default();
    o.insert("preset".into(), t.preset.id().into());
    o.insert("beatmatch".into(), t.beatmatch.into());
    let custom = t.custom.map(|r| {
        let mut c = o.get("custom").and_then(Value::as_object).cloned().unwrap_or_default();
        c.insert("volume".into(), name_of(&VOLUMES, r.volume).into());
        c.insert("eq".into(), name_of(&EQS, r.eq).into());
        c.insert("filterIn".into(), name_of(&FILTERS, r.filter_in).into());
        c.insert("filterOut".into(), name_of(&FILTERS, r.filter_out).into());
        c.insert("riser".into(), r.riser.into());
        c.insert("effect".into(), name_of(&EFFECTS, r.effect).into());
        c.insert("loop".into(), name_of(&LOOPS, r.looping).into());
        Value::Object(c)
    });
    for (key, v) in [("bars", t.bars.map(Value::from)), ("exitMs", t.exit_ms.map(Value::from)), ("entryMs", t.entry_ms.map(Value::from)), ("custom", custom)] {
        match v {
            Some(v) => o.insert(key.into(), v),
            None => o.remove(key),
        };
    }
    Value::Object(o)
}

/// Changes one playlist's entry, the file read again first; an entry left off and without
/// transitions goes away (the phone's `updatePlaylistMix`).
fn edit(dir: &Path, playlist_id: &str, change: impl FnOnce(&mut Map<String, Value>)) -> Result<(), String> {
    let mut all = read_raw(dir);
    let mut entry = all.get(playlist_id).and_then(Value::as_object).cloned().unwrap_or_default();
    change(&mut entry);
    let enabled = entry.get("enabled").and_then(Value::as_bool).unwrap_or(false);
    let empty = entry.get("transitions").and_then(Value::as_object).is_none_or(Map::is_empty);
    if !enabled && empty {
        all.remove(playlist_id);
    } else {
        all.insert(playlist_id.to_string(), Value::Object(entry));
    }
    std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    let file = path(dir);
    let tmp = file.with_extension("json.tmp");
    std::fs::write(&tmp, serde_json::to_vec(&Value::Object(all)).map_err(|e| e.to_string())?).map_err(|e| e.to_string())?;
    std::fs::rename(&tmp, &file).map_err(|e| e.to_string())
}

/// Turns a playlist's Mix on or off (its transitions are kept).
pub fn set_enabled(dir: &Path, playlist_id: &str, on: bool) -> Result<(), String> {
    edit(dir, playlist_id, |e| {
        e.insert("enabled".into(), on.into());
    })
}

/// Sets the transition from `from` to `to`; `None` (or AUTO with nothing changed) goes back to
/// Automix's own.
pub fn set_transition(dir: &Path, playlist_id: &str, from: &str, to: &str, t: Option<&MixTransition>) -> Result<(), String> {
    let key = pair_key(from, to);
    edit(dir, playlist_id, |e| {
        let mut transitions = e.get("transitions").and_then(Value::as_object).cloned().unwrap_or_default();
        match t.filter(|t| **t != MixTransition::AUTO) {
            Some(t) => {
                let json = transition_json(t, transitions.get(&key));
                transitions.insert(key, json);
            }
            None => {
                transitions.remove(&key);
            }
        }
        e.insert("transitions".into(), Value::Object(transitions));
    })
}

// --- Which pairs are mixed ----------------------------------------------------------------------

/// The transition of every pair of songs that follow each other in an enabled mixed playlist
/// (`playlists`: the library's playlists.json); the first playlist wins.
pub fn pairs(playlists: &Value, mixes: &HashMap<String, PlaylistMix>) -> HashMap<(String, String), MixTransition> {
    let mut out = HashMap::new();
    for p in playlists.as_array().into_iter().flatten() {
        let Some(mix) = p.get("id").and_then(Value::as_str).and_then(|id| mixes.get(id)).filter(|m| m.enabled) else { continue };
        let ids: Vec<&str> = p.get("tracks").and_then(Value::as_array).into_iter().flatten().filter_map(|t| t.get("id").and_then(Value::as_str)).collect();
        for w in ids.windows(2) {
            out.entry((w[0].to_string(), w[1].to_string())).or_insert_with(|| mix.transition(w[0], w[1]));
        }
    }
    out
}

/// [`pairs`] kept in memory, built again when the mix file or the playlists change on disk.
#[derive(Default)]
pub struct Index {
    dir: Option<PathBuf>,
    stamp: Option<(Option<SystemTime>, Option<SystemTime>)>,
    pairs: HashMap<(String, String), MixTransition>,
}

impl Index {
    /// An index of the library files in `dir` (None: the library's own folder).
    pub fn new(dir: Option<PathBuf>) -> Self {
        Index { dir, ..Default::default() }
    }

    /// Re-reads the files when their times changed; true when it did.
    pub fn refresh(&mut self) -> bool {
        let Some(dir) = self.dir.clone().or_else(|| crate::library::dir().ok()) else { return false };
        let modified = |p: PathBuf| std::fs::metadata(p).and_then(|m| m.modified()).ok();
        let stamp = (modified(path(&dir)), modified(dir.join(crate::library::PLAYLISTS)));
        if self.stamp == Some(stamp) {
            return false;
        }
        self.stamp = Some(stamp);
        // No mix file: nothing to read (the usual case).
        self.pairs = if stamp.0.is_some() { pairs(&crate::library::load(&dir).playlists, &load(&dir)) } else { HashMap::new() };
        true
    }

    pub fn get(&self, from: &str, to: &str) -> Option<MixTransition> {
        self.pairs.get(&(from.to_string(), to.to_string())).copied()
    }
}

// --- What a change does -----------------------------------------------------------------------

/// What the coming change does, by the pair's transition (the phone's `maybeStartCrossfade`).
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Change {
    /// Not in a mixed playlist: Automix as set, or the crossfade.
    Plain,
    /// "Nessuna": the song ends, then the next one starts.
    NoFade,
    /// On the beat when the planner can (the listener's bars, exit and entry kept), with the
    /// listener's ingredients or Automix's.
    Beat { fixes: Option<PlanFixes>, recipe: Option<MixRecipe> },
    /// Beats off: each song at its own tempo, from `exit_ms`, the next one from `entry_ms`.
    Unmatched { exit_ms: i64, entry_ms: i64, mix_ms: i64, recipe: Option<MixRecipe> },
}

/// `pair`: the pair's transition (None: not in a mixed playlist); `tail_beat_s`: the playing
/// song's beat when its ending was analysed; `fade_ms`: the plain fade (the crossfade, or
/// Automix's fallback). A length that does not fit is cut to a third of the song.
pub fn change(pair: Option<&MixTransition>, duration_ms: i64, tail_beat_s: Option<f64>, fade_ms: i64) -> Change {
    let Some(t) = pair else { return Change::Plain };
    if t.is_none() {
        return Change::NoFade;
    }
    if t.beatmatch {
        let fixes = (t.bars.is_some() || t.exit_ms.is_some() || t.entry_ms.is_some()).then(|| PlanFixes {
            bars: t.bars,
            exit_seconds: t.exit_ms.map(|ms| ms as f64 / 1_000.0),
            entry_seconds: t.entry_ms.map(|ms| ms as f64 / 1_000.0),
        });
        return Change::Beat { fixes, recipe: t.recipe() };
    }
    let wanted = match (t.bars, tail_beat_s) {
        (Some(bars), Some(beat)) => (bars as f64 * 4.0 * beat * 1_000.0) as i64,
        _ => fade_ms,
    };
    let mix_ms = wanted.min(duration_ms / 3);
    if mix_ms < 1_000 {
        return Change::Plain;
    }
    let exit_ms = t.exit_ms.filter(|e| *e >= 0 && e + mix_ms <= duration_ms).unwrap_or(duration_ms - mix_ms);
    Change::Unmatched { exit_ms, entry_ms: t.entry_ms.unwrap_or(0).max(0), mix_ms, recipe: t.recipe() }
}

#[cfg(test)]
mod tests {
    use super::*;
    use lfly_mix::curves::TransitionStyle;
    use serde_json::json;

    fn tmp(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("lfly-pmix-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    #[test]
    fn a_saved_mix_written_by_an_older_version_still_loads_with_its_defaults() {
        // The phone's PlaylistMixTest string, plus a transition with no preset and one with an
        // unknown preset: both are left out.
        let dir = tmp("old");
        std::fs::write(
            path(&dir),
            r#"{"p1":{"enabled":true,"transitions":{"a>b":{"preset":"BLEND"},"b>c":{"bars":4},"c>d":{"preset":"LATER"}}}}"#,
        )
        .unwrap();
        let map = load(&dir);
        let mix = &map["p1"];
        let t = mix.transition("a", "b");
        assert_eq!(t.preset, MixPreset::Blend);
        assert!(t.beatmatch);
        assert_eq!(t.bars, None);
        assert_eq!(mix.transition("b", "a"), MixTransition::AUTO);
        assert_eq!(mix.transitions.len(), 1);
    }

    #[test]
    fn writing_keeps_what_this_version_does_not_know() {
        let dir = tmp("keep");
        std::fs::write(
            path(&dir),
            r#"{"p1":{"enabled":true,"transitions":{"a>b":{"preset":"FADE","custom":{"volume":"OVERLAP","jogwheel":"SPINBACK_2"},"future":1}}},"p2":{"enabled":true}}"#,
        )
        .unwrap();
        let custom = Some(TransitionStyle::Wave.recipe());
        let t = MixTransition { preset: MixPreset::Wave, custom, bars: Some(8), beatmatch: false, exit_ms: Some(150_500), entry_ms: None };
        set_transition(&dir, "p1", "a", "b", Some(&t)).unwrap();
        let raw: Value = serde_json::from_str(&std::fs::read_to_string(path(&dir)).unwrap()).unwrap();
        let ab = &raw["p1"]["transitions"]["a>b"];
        assert_eq!(ab["future"], json!(1));
        assert_eq!(ab["custom"]["jogwheel"], json!("SPINBACK_2"));
        assert_eq!(ab["custom"]["volume"], json!("OVERLAP"));
        assert_eq!(ab["preset"], json!("WAVE"));
        assert!(ab.get("entryMs").is_none());
        assert_eq!(raw["p2"]["enabled"], json!(true));
        assert_eq!(load(&dir)["p1"].transition("a", "b"), t);

        // Back to Automix's own, then off: the entry goes away.
        set_transition(&dir, "p1", "a", "b", None).unwrap();
        set_enabled(&dir, "p1", false).unwrap();
        assert!(!load(&dir).contains_key("p1"));
        assert!(load(&dir)["p2"].enabled);
    }

    #[test]
    fn pairs_come_from_enabled_playlists_the_first_one_winning() {
        let playlists = json!([
            {"id": "off", "tracks": [{"id": "a"}, {"id": "b"}]},
            {"id": "one", "tracks": [{"id": "a"}, {"id": "b"}, {"id": "c"}]},
            {"id": "two", "tracks": [{"id": "a"}, {"id": "b"}]}
        ]);
        let mut mixes = HashMap::new();
        mixes.insert("off".to_string(), PlaylistMix { enabled: false, ..Default::default() });
        let mut one = PlaylistMix { enabled: true, ..Default::default() };
        one.transitions.insert(pair_key("b", "c"), MixTransition { preset: MixPreset::Slam, ..Default::default() });
        mixes.insert("one".to_string(), one);
        let mut two = PlaylistMix { enabled: true, ..Default::default() };
        two.transitions.insert(pair_key("a", "b"), MixTransition { preset: MixPreset::None, ..Default::default() });
        mixes.insert("two".to_string(), two);
        let p = pairs(&playlists, &mixes);
        assert_eq!(p[&("a".into(), "b".into())], MixTransition::AUTO);
        assert_eq!(p[&("b".into(), "c".into())].preset, MixPreset::Slam);
        assert!(!p.contains_key(&("c".into(), "a".into())));
    }

    #[test]
    fn the_index_follows_the_files() {
        let dir = tmp("index");
        let mut index = Index::new(Some(dir.clone()));
        assert!(index.refresh());
        assert_eq!(index.get("a", "b"), None);
        std::fs::write(dir.join(crate::library::PLAYLISTS), r#"[{"id":"p","tracks":[{"id":"a"},{"id":"b"}]}]"#).unwrap();
        set_enabled(&dir, "p", true).unwrap();
        assert!(index.refresh());
        assert_eq!(index.get("a", "b"), Some(MixTransition::AUTO));
        assert!(!index.refresh());
    }

    #[test]
    fn a_change_follows_the_pairs_transition() {
        assert_eq!(change(None, 200_000, None, 5_000), Change::Plain);
        let none = MixTransition { preset: MixPreset::None, ..Default::default() };
        assert_eq!(change(Some(&none), 200_000, None, 5_000), Change::NoFade);

        let blend = MixTransition { preset: MixPreset::Blend, bars: Some(8), ..Default::default() };
        let fixes = Some(PlanFixes { bars: Some(8), exit_seconds: None, entry_seconds: None });
        assert_eq!(change(Some(&blend), 200_000, None, 5_000), Change::Beat { fixes, recipe: MixPreset::Blend.recipe() });
        assert_eq!(change(Some(&MixTransition::AUTO), 200_000, None, 5_000), Change::Beat { fixes: None, recipe: None });

        // Beats off: 8 bars at 120 BPM (16 s) from the chosen exit, the next song from its entry.
        let off = MixTransition { beatmatch: false, bars: Some(8), exit_ms: Some(150_000), entry_ms: Some(9_000), ..Default::default() };
        assert_eq!(change(Some(&off), 200_000, Some(0.5), 5_000), Change::Unmatched { exit_ms: 150_000, entry_ms: 9_000, mix_ms: 16_000, recipe: None });
        // An exit too late for the mix: the mix ends with the song. Unknown tempo: the plain fade.
        let late = MixTransition { exit_ms: Some(195_000), ..off };
        assert_eq!(change(Some(&late), 200_000, None, 5_000), Change::Unmatched { exit_ms: 195_000, entry_ms: 9_000, mix_ms: 5_000, recipe: None });
        assert_eq!(change(Some(&late), 200_000, Some(0.5), 5_000), Change::Unmatched { exit_ms: 184_000, entry_ms: 9_000, mix_ms: 16_000, recipe: None });
        // Longer than a third of the song: cut to a third.
        let long = MixTransition { bars: Some(32), exit_ms: None, ..off };
        assert_eq!(change(Some(&long), 90_000, Some(0.5), 5_000), Change::Unmatched { exit_ms: 60_000, entry_ms: 9_000, mix_ms: 30_000, recipe: None });
    }
}
