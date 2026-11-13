//! The Automix's analysis and planning (the Android app's `AutomixAnalyzer.kt` and the Automix
//! part of `PlaybackManager.kt`): the last minute of the playing song and the first minute of the
//! next are read from the very sources playback uses, their beats tracked (lfly-beat), their
//! loudness and energy measured; with both in hand the planner (lfly-mix) decides whether and how
//! they mix on the beat, and the plan goes to the engine.
//!
//! One analysis runs at a time, on its own thread and reader (cancelled when the songs change),
//! and each result is kept in memory and on disk, keyed by the song, the edge and the file's length
//! (YouTube and Monochrome can serve different edits of one song). A missing or late analysis only
//! means a plain crossfade.

use std::collections::{HashMap, HashSet};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use lfly_audio::{http::ReaderCtl, DiskCache, MixPlan, Source};
use lfly_mix::beat_grid::BeatGrid;
use lfly_mix::curves::{self, MixStyle};
use lfly_mix::energy::EnergyProfile;
use lfly_mix::{loudness, mel, planner};
use serde::{Deserialize, Serialize};

/// About two model chunks: long enough for a whole outro or intro and the section next to it.
pub const WINDOW_SECONDS: f64 = 59.0;
/// Analysis starts this long before a song ends (the tail and the next head, one after the other).
pub const ANALYSIS_LEAD_MS: i64 = 150_000;
/// With Automix on and the crossfade off, a change that cannot be mixed on the beat fades this long.
pub const FALLBACK_SECONDS: u32 = 5;
const SAVED_VERSION: u32 = 1;
const MAX_SAVED: usize = 800;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Part {
    Head,
    Tail,
}

/// Everything one analysis found about a song's edge.
#[derive(Clone, Debug, Default)]
pub struct Analysis {
    pub grid: Option<BeatGrid>,
    pub lufs: Option<f64>,
    pub energy: Option<EnergyProfile>,
}

#[derive(Serialize, Deserialize)]
struct Saved {
    version: u32,
    beats: Vec<f64>,
    downbeats: Vec<f64>,
    bpm: f64,
    confidence: f64,
    lufs: Option<f64>,
    bar_starts: Vec<f64>,
    bar_energy_db: Vec<f64>,
    is_calm: bool,
    outro_start: Option<f64>,
    first_rise: Option<f64>,
    has_energy: bool,
}

pub struct Automix {
    pub on: bool,
    pub style: MixStyle,
    dir: Option<PathBuf>,
    results: HashMap<String, Arc<Analysis>>,
    busy: HashSet<String>,
    /// The readers of the analyses under way, to cancel them when the songs change.
    readers: Vec<Arc<ReaderCtl>>,
    /// The pair whose plan the engine has (outgoing id, incoming id).
    pub sent: Option<(String, String)>,
}

/// One analysis at a time across the app: decoding and the model take seconds of CPU.
static ONE_AT_A_TIME: Mutex<()> = Mutex::new(());

impl Default for Automix {
    fn default() -> Self {
        Automix {
            on: false,
            style: MixStyle::Auto,
            dir: dirs::data_dir().map(|d| d.join("lfly").join("automix")),
            results: HashMap::new(),
            busy: HashSet::new(),
            readers: Vec::new(),
            sent: None,
        }
    }
}

/// Whether this build tracks beats at all (the native app; the classic one does not).
pub fn available() -> bool {
    lfly_beat::available()
}

fn file_key(id: &str, part: Part, len: Option<u64>) -> String {
    let safe: String = id.chars().map(|c| if c.is_ascii_alphanumeric() || c == '_' || c == '-' { c } else { '_' }).collect();
    format!("{safe}-{}-{}", if part == Part::Head { "head" } else { "tail" }, len.map_or("x".to_string(), |l| l.to_string()))
}

/// The length of what `source` plays, when known (the cache entry's, or the file's).
pub fn source_len(source: &Source, cache: &DiskCache) -> Option<u64> {
    match source {
        Source::File { path } => std::fs::metadata(path).ok().map(|m| m.len()),
        Source::Http { cache_key, .. } => cache.total_len(cache_key),
    }
}

impl Automix {
    /// What is known about this edge, from memory or disk.
    pub fn get(&mut self, id: &str, part: Part, len: Option<u64>) -> Option<Arc<Analysis>> {
        let key = file_key(id, part, len);
        if let Some(a) = self.results.get(&key) {
            return Some(a.clone());
        }
        let saved = self.load(&key)?;
        let a = Arc::new(saved);
        self.results.insert(key, a.clone());
        Some(a)
    }

    pub fn is_busy(&self, id: &str, part: Part, len: Option<u64>) -> bool {
        self.busy.contains(&file_key(id, part, len))
    }

    /// The songs changed: analyses for the old pair stop reading.
    pub fn cancel(&mut self) {
        for r in self.readers.drain(..) {
            r.cancel();
        }
    }

    fn load(&self, key: &str) -> Option<Analysis> {
        let path = self.dir.as_ref()?.join(format!("{key}.json"));
        let s: Saved = serde_json::from_slice(&std::fs::read(&path).ok()?).ok()?;
        if s.version != SAVED_VERSION {
            return None;
        }
        let grid = (!s.beats.is_empty() && s.bpm > 0.0).then(|| BeatGrid::new(s.beats, s.downbeats, s.bpm, s.confidence));
        let energy = s.has_energy.then_some(EnergyProfile {
            bar_starts: s.bar_starts,
            bar_energy_db: s.bar_energy_db,
            is_calm: s.is_calm,
            outro_start: s.outro_start,
            first_rise: s.first_rise,
        });
        Some(Analysis { grid, lufs: s.lufs, energy })
    }

    fn save(dir: &Option<PathBuf>, key: &str, a: &Analysis) {
        let Some(dir) = dir else { return };
        let _ = std::fs::create_dir_all(dir);
        let s = Saved {
            version: SAVED_VERSION,
            beats: a.grid.as_ref().map(|g| g.beats().to_vec()).unwrap_or_default(),
            downbeats: a.grid.as_ref().map(|g| g.downbeats().to_vec()).unwrap_or_default(),
            bpm: a.grid.as_ref().map_or(0.0, |g| g.bpm()),
            confidence: a.grid.as_ref().map_or(0.0, |g| g.confidence()),
            lufs: a.lufs,
            bar_starts: a.energy.as_ref().map(|e| e.bar_starts.clone()).unwrap_or_default(),
            bar_energy_db: a.energy.as_ref().map(|e| e.bar_energy_db.clone()).unwrap_or_default(),
            is_calm: a.energy.as_ref().is_some_and(|e| e.is_calm),
            outro_start: a.energy.as_ref().and_then(|e| e.outro_start),
            first_rise: a.energy.as_ref().and_then(|e| e.first_rise),
            has_energy: a.energy.is_some(),
        };
        if let Ok(bytes) = serde_json::to_vec(&s) {
            let _ = std::fs::write(dir.join(format!("{key}.json")), bytes);
        }
        // The oldest go first.
        if let Ok(read) = std::fs::read_dir(dir) {
            let mut files: Vec<_> = read.flatten().filter_map(|e| Some((e.metadata().ok()?.modified().ok()?, e.path()))).collect();
            if files.len() > MAX_SAVED {
                files.sort();
                for (_, p) in files.iter().take(files.len() - MAX_SAVED) {
                    let _ = std::fs::remove_file(p);
                }
            }
        }
    }

    /// Starts analysing this edge unless it is known or under way; `done` runs when it is in.
    pub fn request(
        me: &Arc<Mutex<Automix>>,
        id: String,
        part: Part,
        source: Source,
        cache: Arc<DiskCache>,
        duration_ms: i64,
        done: impl FnOnce() + Send + 'static,
    ) {
        let len = source_len(&source, &cache);
        let ctl = ReaderCtl::detached();
        {
            let mut a = me.lock().unwrap_or_else(|e| e.into_inner());
            if a.get(&id, part, len).is_some() || !a.busy.insert(file_key(&id, part, len)) {
                return;
            }
            a.readers.push(ctl.clone());
        }
        let me = me.clone();
        let spawned = std::thread::Builder::new().name("lfly-automix".into()).spawn(move || {
            let result = {
                let _one = ONE_AT_A_TIME.lock().unwrap_or_else(|e| e.into_inner());
                analyse(&source, &cache, ctl.clone(), part, duration_ms)
            };
            let asked = file_key(&id, part, len);
            // The length is known once the source was read, if it was not before.
            let key = file_key(&id, part, source_len(&source, &cache).or(len));
            let mut a = me.lock().unwrap_or_else(|e| e.into_inner());
            a.busy.remove(&asked);
            a.readers.retain(|r| !Arc::ptr_eq(r, &ctl));
            if ctl.aborted() {
                return;
            }
            // A read that failed (an expired address, a refusing server) is remembered for this
            // session only, so the edge is not asked again every tick, but tried again next time.
            let read = result.is_some();
            let analysis = Arc::new(result.unwrap_or_default());
            if read {
                Self::save(&a.dir, &key, &analysis);
            }
            a.results.insert(asked, analysis.clone());
            a.results.insert(key, analysis);
            drop(a);
            done();
        });
        if let Err(e) = spawned {
            log::warn!("automix analysis thread: {e}");
        }
    }

    /// The plan for the pair, when both edges are analysed and they can be mixed on the beat.
    pub fn plan(&self, outgoing: (&str, &Analysis, i64), incoming: (&str, &Analysis)) -> Option<MixPlan> {
        let (out_id, out, duration_ms) = outgoing;
        let (in_id, inc) = incoming;
        let (og, ig) = (out.grid.as_ref()?, inc.grid.as_ref()?);
        let p = planner::plan(og, duration_ms.max(0) as u64, ig, out.energy.as_ref(), inc.energy.as_ref())?;
        let calm = out.energy.as_ref().is_some_and(|e| e.is_calm) || inc.energy.as_ref().is_some_and(|e| e.is_calm);
        let style = curves::resolve(self.style, true, calm, p.lands_rise, curves::pair_seed(out_id, in_id));
        let gain_db = loudness::match_gain_db(out.lufs, inc.lufs);
        log::info!(
            "Automix plan {out_id} -> {in_id}: exit {} ms, entry {} ms, rate {:.4}, {} bars ({} ms), {style:?}, level {gain_db:+.1} dB, grids off their lines by {:.0}/{:.0} ms at worst",
            p.exit_ms,
            p.entry_ms,
            p.incoming_rate,
            p.mix_bars,
            p.mix_ms,
            og.worst_residual() * 1000.0,
            ig.worst_residual() * 1000.0
        );
        Some(MixPlan {
            outgoing_key: String::new(),
            incoming_key: String::new(),
            exit_ms: p.exit_ms,
            entry_ms: p.entry_ms,
            rate: p.incoming_rate,
            mix_ms: p.mix_ms,
            recipe: style.recipe(),
            outgoing_gain: loudness::gain(gain_db),
            // One beat of the mix, as a share of it.
            swap_width: 1.0 / p.mix_beats().max(1) as f32,
        })
    }
}

/// Decodes the edge (mono at the model's rate) and measures it; `None` when nothing could be read.
fn analyse(source: &Source, cache: &Arc<DiskCache>, ctl: Arc<ReaderCtl>, part: Part, duration_ms: i64) -> Option<Analysis> {
    let started = std::time::Instant::now();
    let start_ms = match part {
        Part::Head => 0,
        Part::Tail => (duration_ms - (WINDOW_SECONDS * 1000.0) as i64).max(0) as u64,
    };
    let (samples, start_s) = match lfly_audio::decode::decode_window(source, cache, ctl, start_ms, WINDOW_SECONDS, mel::SAMPLE_RATE) {
        Ok(w) => w,
        Err(e) => {
            log::info!("automix {part:?} of {}: not decoded ({e})", source.key());
            return None;
        }
    };
    let decoded = started.elapsed();
    let grid = lfly_beat::track(&samples, start_s);
    let lufs = loudness::lufs(&samples, mel::SAMPLE_RATE);
    let energy = grid.as_ref().and_then(|g| EnergyProfile::compute(&samples, mel::SAMPLE_RATE, start_s, g));
    log::info!(
        "automix {part:?} of {}: {}, loudness {}, energy {} in {} ms (decoding {} ms)",
        source.key(),
        grid.as_ref().map_or("no grid".to_string(), |g| format!("{:.1} BPM, {} beats, {} downbeats, confidence {:.2}", g.bpm(), g.beats().len(), g.downbeats().len(), g.confidence())),
        lufs.map_or("n/a".to_string(), |l| format!("{l:.1} LUFS")),
        energy.as_ref().map_or("n/a".to_string(), |e| format!("{} bars{}, outro {:?}, rise {:?}", e.bar_starts.len(), if e.is_calm { " calm" } else { "" }, e.outro_start, e.first_rise)),
        started.elapsed().as_millis(),
        decoded.as_millis()
    );
    Some(Analysis { grid, lufs, energy })
}
