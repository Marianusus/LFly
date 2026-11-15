//! The Console's two decks as the window knows them: which song each holds, its waveform, tempo,
//! grid, key, cue points and loop, and every control a DJ uses, each one method here so the page,
//! the keyboard and a hardware console all go through the same door.
//!
//! The sound is `lfly_audio::decks`: its own engine and output, beside the music player. Starting
//! a deck pauses the music player, and the player starting pauses the decks. What the decks play
//! goes into no history and to no other device. Test runs open no output (silent decks).

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, Instant};

use gpui_kit::*;
use lfly_audio::decks::{Curve, FxKind, Mixer, TrackBuf, FX_SLOTS, SAMPLES};
use lfly_connect::protocol::{self, TrackRef};
use lfly_core::decks::{Analysis, Grid};
use lfly_mix::key::MusicalKey;

use crate::core::Core;
use crate::i18n::{tr, trf};
use crate::player_model::PlayerModel;
use crate::toast::Toasts;

pub const HOT_CUES: usize = 8;
/// Waveform columns per second of song.
pub const PEAKS_PER_SECOND: u32 = 100;
/// Pitch fader ranges, as shares of the tempo.
pub const RANGES: [f32; 7] = [0.06, 0.08, 0.12, 0.16, 0.25, 0.5, 1.0];
/// Auto-loop sizes, in beats.
pub const LOOP_SIZES: [f64; 13] = [1. / 32., 1. / 16., 1. / 8., 1. / 4., 1. / 2., 1., 2., 4., 8., 16., 32., 64., 128.];
/// Loop-roll pads, in beats.
pub const ROLL_SIZES: [f64; 8] = [1. / 16., 1. / 8., 1. / 4., 1. / 2., 1., 2., 4., 8.];
/// Fixed-loop pads, in beats.
pub const LOOP_PADS: [f64; 8] = [1. / 4., 1. / 2., 1., 2., 4., 8., 16., 32.];
/// Beat-jump pads: back on the top row, forward on the bottom one.
pub const JUMPS: [f64; 8] = [-16., -8., -4., -1., 1., 4., 8., 16.];
/// One turn of the platter, in seconds of song (a record at 33⅓).
pub const TURN_SECONDS: f64 = 1.8;
const STORE_CUES: &str = "console-cues";
/// Pitch bend, held: this much faster or slower.
const BEND: f32 = 0.04;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PadMode {
    HotCue,
    /// A cue that, held, loops from itself.
    CueLoop,
    /// Loops kept with the song.
    SavedLoop,
    Roll,
    /// Eight steps of the bars playing; a held pad loops its step.
    Slicer,
    Loop,
    /// Loop in, out, and moving either end or the whole loop.
    Manual,
    Jump,
    Sampler,
    /// The beat grid moved by hand, the tempo nudged, the first beat set.
    Grid,
    /// Brake, spinback, censor, reverse, tempo ×2 and ÷2, tap, eject.
    Perform,
    /// How the deck's buttons behave.
    Options,
}

impl PadMode {
    pub const ALL: [PadMode; 12] = [
        PadMode::HotCue,
        PadMode::CueLoop,
        PadMode::SavedLoop,
        PadMode::Roll,
        PadMode::Slicer,
        PadMode::Loop,
        PadMode::Manual,
        PadMode::Jump,
        PadMode::Sampler,
        PadMode::Grid,
        PadMode::Perform,
        PadMode::Options,
    ];

    pub fn label(self) -> &'static str {
        match self {
            PadMode::HotCue => "HOT CUE",
            PadMode::CueLoop => "CUELOOP",
            PadMode::SavedLoop => "SAVED LOOP",
            PadMode::Roll => "LOOP ROLL",
            PadMode::Slicer => "SLICER",
            PadMode::Loop => "LOOP",
            PadMode::Manual => "MANUAL LOOP",
            PadMode::Jump => "BEATJUMP",
            PadMode::Sampler => "SAMPLER",
            PadMode::Grid => "BEAT GRID",
            PadMode::Perform => "PERFORM",
            PadMode::Options => "OPTIONS",
        }
    }

    /// The page after (or before) this one.
    pub fn step(self, by: i32) -> PadMode {
        let i = PadMode::ALL.iter().position(|m| *m == self).unwrap_or(0) as i32;
        PadMode::ALL[(i + by).rem_euclid(PadMode::ALL.len() as i32) as usize]
    }
}

/// What CUE does (VirtualDJ's three).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CueMode {
    /// Playing: back to the cue, stopped. Stopped there: plays while held.
    Standard,
    /// As standard, and held over 3 s it keeps playing when let go.
    HoldToPlay,
    /// Back to the cue and stopped while held, playing on when let go.
    Inverted,
}

/// What the LOOP pads do: stay on until pressed again, loop while held, or roll while held.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum LoopPads {
    OnOff,
    Hold,
    Roll,
}

/// How the sampler's pads play.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SampleMode {
    /// A press starts it from the top, another stops it.
    OnOff,
    /// It plays while held.
    Hold,
    /// Every press starts it again from the top.
    Stutter,
}

/// A sampler slot: a stretch of a song kept in memory (the engine holds where it starts and
/// ends), named after the song.
#[derive(Clone)]
pub struct Sample {
    pub name: String,
    pub buf: Arc<TrackBuf>,
}

#[derive(Clone, Debug, PartialEq)]
pub enum Status {
    Empty,
    Loading,
    Ready,
    Failed(String),
}

#[derive(Clone)]
pub struct Deck {
    pub track: Option<TrackRef>,
    pub buf: Option<Arc<TrackBuf>>,
    pub status: Status,
    /// Bumped on each load: work for an older load is dropped.
    seq: u64,
    pub peaks: Arc<Vec<[u8; 2]>>,
    pub grid: Option<Grid>,
    pub key: Option<MusicalKey>,
    pub analysing: bool,
    /// The main cue (CUE button), in seconds.
    pub cue: f64,
    pub hot: [Option<f64>; HOT_CUES],
    /// The auto loop's size.
    pub loop_beats: f64,
    /// A loop-in waiting for its out.
    pub loop_in: Option<f64>,
    /// The loop on now, in seconds.
    pub looped: Option<(f64, f64)>,
    /// The last loop, for RELOOP.
    pub last_loop: Option<(f64, f64)>,
    pub range: f32,
    /// The pitch fader: -1 (top, slower) .. 1 (bottom, faster), as on a turntable.
    pub fader: f32,
    pub vinyl: bool,
    pub slip: bool,
    pub reverse: bool,
    pub censor: bool,
    pub pads: PadMode,
    /// A held pad (roll), to let go of.
    pub pad_held: Option<(PadMode, usize)>,
    /// The time shown: 0 elapsed, 1 remaining, 2 the song's length.
    pub time: u8,
    pub camelot: bool,
    /// Loops kept with the song, in seconds.
    pub saved: [Option<(f64, f64)>; HOT_CUES],
    /// Master tempo.
    pub keylock: bool,
    pub cue_mode: CueMode,
    /// PLAY while playing starts again from the cue (play-stutter).
    pub play_stutter: bool,
    /// Hot cues play while held and go back when let go.
    pub hot_stutter: bool,
    pub loop_pads: LoopPads,
    /// An auto loop ends where the deck is instead of starting there.
    pub loop_back: bool,
    /// The tempo matched to the other deck's when a song loads.
    pub auto_bpm: bool,
    /// PLAY starts on the other deck's beat.
    pub auto_sync: bool,
    /// The crossfader side: 0 none, 1 left, 2 right.
    pub assign: u8,
    /// The filter knob's effect (0: the filter).
    pub color: u8,
    /// Where a held stutter hot cue began, to go back to.
    stutter_at: Option<f64>,
    /// When CUE went down (hold-to-play).
    cue_since: Option<Instant>,
    pub fx: [FxKind; FX_SLOTS],
    pub fx_on: [bool; FX_SLOTS],
    pub fx_amount: [f32; FX_SLOTS],
    taps: Vec<Instant>,
    /// CUE held on a paused deck: it plays until let go.
    cue_preview: bool,
    /// Mixer knobs, 0..1 (0.5 is the middle): gain, high, mid, low, filter; and the fader.
    pub knobs: [f32; 5],
    pub volume: f32,
    pub bend: f32,
    /// The platter held: when, and how far it turned since.
    jog: Option<Instant>,
}

impl Deck {
    /// An empty deck keeping this one's settings (a new song, the same hands).
    fn renewed(&self) -> Deck {
        Deck {
            range: self.range,
            vinyl: self.vinyl,
            pads: self.pads,
            time: self.time,
            camelot: self.camelot,
            fx: self.fx,
            fx_amount: self.fx_amount,
            knobs: self.knobs,
            volume: self.volume,
            keylock: self.keylock,
            cue_mode: self.cue_mode,
            play_stutter: self.play_stutter,
            hot_stutter: self.hot_stutter,
            loop_pads: self.loop_pads,
            loop_back: self.loop_back,
            auto_bpm: self.auto_bpm,
            auto_sync: self.auto_sync,
            assign: self.assign,
            color: self.color,
            loop_beats: self.loop_beats,
            seq: self.seq + 1,
            ..Deck::new()
        }
    }

    fn new() -> Self {
        Deck {
            track: None,
            buf: None,
            status: Status::Empty,
            seq: 0,
            peaks: Arc::default(),
            grid: None,
            key: None,
            analysing: false,
            cue: 0.,
            hot: [None; HOT_CUES],
            loop_beats: 4.,
            loop_in: None,
            looped: None,
            last_loop: None,
            range: 0.08,
            fader: 0.,
            vinyl: true,
            slip: false,
            reverse: false,
            censor: false,
            pads: PadMode::HotCue,
            pad_held: None,
            time: 0,
            camelot: false,
            saved: [None; HOT_CUES],
            keylock: false,
            cue_mode: CueMode::Standard,
            play_stutter: false,
            hot_stutter: false,
            loop_pads: LoopPads::OnOff,
            loop_back: false,
            auto_bpm: false,
            auto_sync: false,
            assign: 0,
            color: 0,
            stutter_at: None,
            cue_since: None,
            fx: [FxKind::Echo, FxKind::Flanger, FxKind::Reverb],
            fx_on: [false; FX_SLOTS],
            fx_amount: [0.5; FX_SLOTS],
            taps: Vec::new(),
            cue_preview: false,
            knobs: [0.5; 5],
            volume: 1.,
            bend: 1.,
            jog: None,
        }
    }

    pub fn rate(&self) -> f64 {
        self.buf.as_ref().map_or(48_000., |b| b.rate() as f64)
    }

    /// The tempo ratio from the pitch fader.
    pub fn ratio(&self) -> f64 {
        1. + (self.fader * self.range) as f64
    }

    /// The song's length in seconds.
    pub fn duration(&self) -> f64 {
        match &self.buf {
            Some(b) => b.frames() as f64 / b.rate() as f64,
            None => self.track.as_ref().map_or(0., |t| t.duration_ms.max(0) as f64 / 1000.),
        }
    }

    /// The tempo heard now.
    pub fn bpm(&self) -> Option<f64> {
        self.grid.map(|g| g.bpm * self.ratio())
    }

    /// One beat in seconds of song (120 BPM without a grid).
    pub fn beat(&self) -> f64 {
        self.grid.map_or(0.5, |g| g.beat_seconds())
    }

    pub fn key_shown(&self) -> Option<String> {
        self.key.map(|k| if self.camelot { k.camelot() } else { k.name() })
    }
}

pub struct DjDecks {
    core: Arc<Core>,
    model: Entity<PlayerModel>,
    mixer: Option<Arc<Mixer>>,
    pub decks: [Deck; 2],
    pub quantize: bool,
    pub xfader: f32,
    pub curve: Curve,
    /// The master knob, 0..1 (0.5 unity).
    pub master: f32,
    pub recording: Option<PathBuf>,
    /// Moving one pitch fader moves the other, the tempos kept equal.
    pub pitch_lock: bool,
    pub hamster: bool,
    /// The crossfader's effect (0 none).
    pub mix_fx: u8,
    pub master_fx: u8,
    pub master_fx_amount: f32,
    pub sampler_volume: f32,
    pub samples: [Option<Sample>; SAMPLES],
    pub sample_mode: SampleMode,
    /// The songs the browser shows, for EJECT on an empty deck (load the next).
    pub browse: Arc<Vec<TrackRef>>,
    last_loaded: Option<String>,
    analyses: HashMap<String, Analysis>,
    player_playing: bool,
    /// A gradual pitch reset going on, per deck.
    resetting: [bool; 2],
}

struct DecksGlobal(Entity<DjDecks>);
impl Global for DecksGlobal {}

pub(crate) fn test_run() -> bool {
    std::env::var_os("LFLY_MUTE").is_some() || std::env::var_os("LFLY_AUTOPLAY").is_some() || std::env::var_os("LFLY_ROUTE").is_some()
}

/// A song's cue points and saved loops as kept: `{"cues": [s|null ×8], "loops": [[a, b]|null ×8]}`
/// (an older entry is the cue list alone).
/// A song's hot cues and saved loops.
type Points = ([Option<f64>; HOT_CUES], [Option<(f64, f64)>; HOT_CUES]);

fn saved_points(id: &str) -> Points {
    let mut cues = [None; HOT_CUES];
    let mut loops = [None; HOT_CUES];
    let Some(v) = lfly_core::store::get(STORE_CUES).ok().flatten().and_then(|v| v.get(id).cloned()) else { return (cues, loops) };
    let cue_list = if v.is_array() { v.as_array().cloned() } else { v.get("cues").and_then(|c| c.as_array().cloned()) };
    for (slot, v) in cues.iter_mut().zip(cue_list.unwrap_or_default()) {
        *slot = v.as_f64();
    }
    for (slot, v) in loops.iter_mut().zip(v.get("loops").and_then(|l| l.as_array().cloned()).unwrap_or_default()) {
        *slot = v.as_array().and_then(|p| Some((p.first()?.as_f64()?, p.get(1)?.as_f64()?)));
    }
    (cues, loops)
}

fn save_points(id: &str, cues: &[Option<f64>; HOT_CUES], loops: &[Option<(f64, f64)>; HOT_CUES]) {
    if test_run() {
        return;
    }
    let mut all = lfly_core::store::get(STORE_CUES).ok().flatten().unwrap_or_else(|| serde_json::json!({}));
    if let Some(map) = all.as_object_mut() {
        if cues.iter().all(Option::is_none) && loops.iter().all(Option::is_none) {
            map.remove(id);
        } else {
            let loops: Vec<serde_json::Value> = loops.iter().map(|l| l.map_or(serde_json::Value::Null, |(a, b)| serde_json::json!([a, b]))).collect();
            map.insert(id.to_string(), serde_json::json!({ "cues": cues.to_vec(), "loops": loops }));
        }
    }
    if let Err(e) = lfly_core::store::set(STORE_CUES, &all) {
        log::warn!("console cues not saved: {e}");
    }
}

/// A local file as the decks take it: its id is `file:` and the path.
pub fn local_path(t: &TrackRef) -> Option<PathBuf> {
    t.id.strip_prefix("file:").map(PathBuf::from)
}

/// Where a recording goes: the music folder's LFly folder, named by the time.
fn recording_path() -> Option<PathBuf> {
    let dir = dirs::audio_dir().or_else(dirs::home_dir)?.join("LFly");
    std::fs::create_dir_all(&dir).ok()?;
    let secs = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_secs());
    Some(dir.join(format!("LFly Console {secs}.wav")))
}

impl DjDecks {
    pub fn start(core: Arc<Core>, model: Entity<PlayerModel>, cx: &mut App) -> Entity<Self> {
        let e = cx.new(|cx: &mut Context<Self>| {
            // The music player starting pauses the decks (one music at a time).
            // (Not a device this one controls: that plays elsewhere.)
            cx.observe(&model, |me, model, cx| {
                let m = model.read(cx);
                let playing = m.playing() && m.remote.is_none();
                if playing && !me.player_playing && me.any_playing() {
                    for d in 0..2 {
                        me.play(d, false, cx);
                    }
                }
                me.player_playing = playing;
            })
            .detach();
            DjDecks {
                core,
                model,
                mixer: None,
                decks: [Deck::new(), Deck::new()],
                quantize: true,
                xfader: 0.5,
                curve: Curve::Full,
                master: 0.5,
                recording: None,
                pitch_lock: false,
                hamster: false,
                mix_fx: 0,
                master_fx: 0,
                master_fx_amount: 0.5,
                sampler_volume: 0.8,
                samples: Default::default(),
                sample_mode: SampleMode::OnOff,
                browse: Arc::default(),
                last_loaded: None,
                analyses: HashMap::new(),
                player_playing: false,
                resetting: [false; 2],
            }
        });
        cx.set_global(DecksGlobal(e.clone()));
        e.update(cx, |_, cx| Self::run_steps(cx));
        e
    }

    /// LFLY_CONSOLE_STEPS="<seconds>:<action>:<deck>;…" (tests): controls pressed at those times,
    /// what the decks then do logged. Actions: play, pause, sync, loop, half, double, roll<pad>,
    /// unroll, hot<pad>, jump<pad>, brake, reverse, slip, cue, uncue, eq-low-kill, report.
    fn run_steps(cx: &mut Context<Self>) {
        let Ok(steps) = std::env::var("LFLY_CONSOLE_STEPS") else { return };
        let started = Instant::now();
        for step in steps.split(';') {
            let mut parts = step.split(':');
            let (Some(at), Some(action), Some(deck)) = (parts.next().and_then(|s| s.trim().parse::<f64>().ok()), parts.next().map(str::to_string), parts.next().and_then(|s| s.trim().parse::<usize>().ok())) else {
                continue;
            };
            let d = deck.min(1);
            cx.spawn(async move |this, cx| {
                let wait = Duration::from_secs_f64(at).saturating_sub(started.elapsed());
                cx.background_executor().timer(wait).await;
                let _ = this.update(cx, |me, cx| {
                    let pad = |p: &str| action.trim_start_matches(p).parse::<usize>().unwrap_or(0).min(7);
                    match action.as_str() {
                        "play" => me.play(d, true, cx),
                        "pause" => me.play(d, false, cx),
                        "sync" => me.sync(d, cx),
                        "loop" => me.auto_loop(d, cx),
                        "half" => me.loop_size(d, false, cx),
                        "double" => me.loop_size(d, true, cx),
                        "unroll" => me.pad_up(d, cx),
                        "brake" => me.pad_down_in(d, PadMode::Perform, 0, cx),
                        "reverse" => me.pad_down_in(d, PadMode::Perform, 3, cx),
                        "slip" => me.toggle_slip(d, cx),
                        "cue" => me.cue_down(d, cx),
                        "uncue" => me.cue_up(d, cx),
                        "eq-low-kill" => me.kill(d, 3, cx),
                        "keylock" => me.toggle_keylock(d, cx),
                        "match" => me.match_tempo(d, cx),
                        "stop" => me.stop(d, cx),
                        "eject" => me.eject_or_next(d, cx),
                        "gridbeat" => me.grid_shift(d, 1., cx),
                        "phrase" => me.phrase_sync(d, cx),
                        "unpad" => me.pad_up(d, cx),
                        "color" => {
                            me.cycle_color(d, cx);
                            me.set_knob(d, 4, 0.85, cx);
                        }
                        "mixfx" => me.cycle_mix_fx(cx),
                        "pitch+" => me.set_pitch(d, 0.5, cx),
                        "pitch0" => me.pitch_reset(d, cx),
                        "plock" => me.toggle_pitch_lock(cx),
                        "ham" => me.toggle_hamster(cx),
                        "masterfx" => me.cycle_master_fx(cx),
                        "cuenext" => me.cue_step(d, 1, cx),
                        "cueprev" => me.cue_step(d, -1, cx),
                        "playpause" => me.play_pause(d, cx),
                        a if a.starts_with("opt") => me.pad_down_in(d, PadMode::Options, pad("opt"), cx),
                        a if a.starts_with("manual") => me.pad_down_in(d, PadMode::Manual, pad("manual"), cx),
                        a if a.starts_with("grid") && a != "gridbeat" => me.pad_down_in(d, PadMode::Grid, pad("grid"), cx),
                        a if a.starts_with("loop") && a.len() > 4 => me.pad_down_in(d, PadMode::Loop, pad("loop"), cx),
                        a if a.starts_with("xf") => me.set_xfader(a[2..].parse().unwrap_or(0.5), cx),
                        a if a.starts_with("slice") => me.pad_down_in(d, PadMode::Slicer, pad("slice"), cx),
                        a if a.starts_with("cueloop") => me.pad_down_in(d, PadMode::CueLoop, pad("cueloop"), cx),
                        a if a.starts_with("saveloop") => me.pad_down_in(d, PadMode::SavedLoop, pad("saveloop"), cx),
                        a if a.starts_with("sample") => me.pad_down_in(d, PadMode::Sampler, pad("sample"), cx),
                        a if a.starts_with("roll") => me.pad_down_in(d, PadMode::Roll, pad("roll"), cx),
                        a if a.starts_with("hot") => me.pad_down_in(d, PadMode::HotCue, pad("hot"), cx),
                        a if a.starts_with("jump") => me.pad_down_in(d, PadMode::Jump, pad("jump"), cx),
                        _ => {}
                    }
                    let phase = |me: &DjDecks, d: usize| me.decks[d].grid.map(|g| g.beat_at(me.position(d)));
                    log::info!(
                        "console step {action} on {d}: A {:.3} s {} beat {:?} | B {:.3} s {} beat {:?} | tempo {:.4} {:.4} | loop {:?} {:?} | vu {:.2} {:.2} | phrase {:?} {:?} | samples {:?} | keylock {} {} | fader {:.2} {:.2} | tracks {:?} {:?} | grid {:?} | options {:?} {} {} {:?} {} {} {} | lock {} ham {} mfx {}",
                        me.position(0),
                        if me.is_playing(0) { "playing" } else { "stopped" },
                        phase(me, 0).map(|b| (b * 1000.).round() / 1000.),
                        me.position(1),
                        if me.is_playing(1) { "playing" } else { "stopped" },
                        phase(me, 1).map(|b| (b * 1000.).round() / 1000.),
                        me.decks[0].bpm().unwrap_or(0.),
                        me.decks[1].bpm().unwrap_or(0.),
                        me.decks[0].looped,
                        me.decks[1].looped,
                        me.vu(0),
                        me.vu(1),
                        me.phrase(0),
                        me.phrase(1),
                        (0..SAMPLES).map(|i| (me.samples[i].is_some(), me.sample_playing(i))).filter(|s| s.0).collect::<Vec<_>>(),
                        me.decks[0].keylock,
                        me.decks[1].keylock,
                        me.decks[0].fader,
                        me.decks[1].fader,
                        me.decks[0].track.as_ref().map(|t| t.title.clone()),
                        me.decks[1].track.as_ref().map(|t| t.title.clone()),
                        me.decks[d].grid.map(|g| (g.bpm, (g.first_beat * 1000.).round() / 1000., g.first_in_bar)),
                        me.decks[d].cue_mode,
                        me.decks[d].play_stutter,
                        me.decks[d].hot_stutter,
                        me.decks[d].loop_pads,
                        me.decks[d].loop_back,
                        me.decks[d].auto_bpm,
                        me.decks[d].auto_sync,
                        me.pitch_lock,
                        me.hamster,
                        me.master_fx,
                    );
                });
            })
            .detach();
        }
    }

    /// A pad of page [mode] pressed, whatever page the deck shows.
    fn pad_down_in(&mut self, d: usize, mode: PadMode, i: usize, cx: &mut Context<Self>) {
        let shown = self.decks[d].pads;
        self.decks[d].pads = mode;
        self.pad_down(d, i, false, cx);
        self.decks[d].pads = shown;
    }

    pub fn entity(cx: &App) -> Entity<Self> {
        cx.global::<DecksGlobal>().0.clone()
    }

    pub fn try_entity(cx: &App) -> Option<Entity<Self>> {
        cx.try_global::<DecksGlobal>().map(|g| g.0.clone())
    }

    /// The engine, opened at the first use (and the controls set so far handed to it).
    fn mixer(&mut self) -> Option<Arc<Mixer>> {
        if self.mixer.is_none() {
            let m = if test_run() {
                Mixer::silent()
            } else {
                match Mixer::start(self.core.player.output_device()) {
                    Ok(m) => m,
                    Err(e) => {
                        log::warn!("console decks: {e}");
                        return None;
                    }
                }
            };
            self.mixer = Some(Arc::new(m));
            for d in 0..2 {
                self.apply_mixer(d);
            }
            self.apply_master();
        }
        self.mixer.clone()
    }

    pub fn engine(&self) -> Option<&Arc<Mixer>> {
        self.mixer.as_ref()
    }

    fn apply_mixer(&self, d: usize) {
        let Some(m) = &self.mixer else { return };
        let deck = &self.decks[d];
        let st = m.deck(d);
        // Gain ±12 dB around the middle.
        st.gain.set(10f32.powf((deck.knobs[0] - 0.5) * 24. / 20.));
        st.eq[2].set(deck.knobs[1]);
        st.eq[1].set(deck.knobs[2]);
        st.eq[0].set(deck.knobs[3]);
        st.filter.set((deck.knobs[4] - 0.5) * 2.);
        st.volume.set(deck.volume);
        st.pitch.set(deck.ratio() as f32);
        st.bend.set(deck.bend);
        for s in 0..FX_SLOTS {
            st.fx_kind[s].store(if deck.fx_on[s] { deck.fx[s] as u8 } else { 0 }, std::sync::atomic::Ordering::Relaxed);
            st.fx_amount[s].set(deck.fx_amount[s]);
        }
        let rate = deck.rate();
        match deck.grid {
            Some(g) => {
                st.beat_frames.set(g.beat_seconds() * rate);
                st.first_beat.set(g.first_beat * rate);
            }
            None => st.beat_frames.set(0.),
        }
        use std::sync::atomic::Ordering::Relaxed;
        st.keylock.store(deck.keylock, Relaxed);
        // Unassigned in the model means the deck's own side (A left, B right).
        st.assign.store(if deck.assign == 3 { 0 } else if deck.assign == 0 { d as u8 + 1 } else { deck.assign }, Relaxed);
        st.color.store(deck.color, Relaxed);
    }

    fn apply_master(&self) {
        let Some(m) = &self.mixer else { return };
        m.state.xfader.set(self.xfader);
        m.state.curve.store(self.curve as u8, std::sync::atomic::Ordering::Relaxed);
        m.state.master.set(lfly_audio::decks::eq_gain(self.master));
        use std::sync::atomic::Ordering::Relaxed;
        m.state.hamster.store(self.hamster, Relaxed);
        m.state.mix_fx.store(self.mix_fx, Relaxed);
        m.state.master_fx.store(self.master_fx, Relaxed);
        m.state.master_fx_amount.set(self.master_fx_amount);
        m.state.sampler_volume.set(self.sampler_volume);
    }

    // ---- Reading ----

    /// Where the deck is, in seconds.
    pub fn position(&self, d: usize) -> f64 {
        let deck = &self.decks[d];
        match &self.mixer {
            Some(m) if deck.buf.is_some() => m.deck(d).position.get() / deck.rate(),
            _ => 0.,
        }
    }

    pub fn is_playing(&self, d: usize) -> bool {
        self.mixer.as_ref().is_some_and(|m| m.deck(d).playing.load(std::sync::atomic::Ordering::Relaxed))
    }

    pub fn any_playing(&self) -> bool {
        (0..2).any(|d| self.is_playing(d))
    }

    pub fn vu(&self, d: usize) -> f32 {
        self.mixer.as_ref().map_or(0., |m| m.deck(d).vu.get())
    }

    pub fn master_vu(&self) -> f32 {
        self.mixer.as_ref().map_or(0., |m| m.state.master_vu.get())
    }

    /// Something on the decks moves: the page redraws while it does.
    pub fn moving(&self) -> bool {
        self.any_playing() || self.decks.iter().any(|d| d.status == Status::Loading || d.analysing || d.jog.is_some())
    }

    fn snap(&self, d: usize, t: f64) -> f64 {
        match self.decks[d].grid {
            Some(g) if self.quantize => g.snap(t).max(0.),
            _ => t,
        }
    }

    // ---- Loading ----

    /// Loads [track] on deck [d] (stopped there, at its cue).
    pub fn load(&mut self, d: usize, track: TrackRef, cx: &mut Context<Self>) {
        if self.is_playing(d) {
            Toasts::say(tr("Ferma il deck prima di caricare un altro brano"), cx);
            return;
        }
        let Some(mixer) = self.mixer() else {
            Toasts::say(tr("La console non trova un'uscita audio"), cx);
            return;
        };
        mixer.eject(d);
        self.let_go(d);
        let deck = &mut self.decks[d];
        *deck = deck.renewed();
        let seq = deck.seq;
        (deck.hot, deck.saved) = saved_points(&track.id);
        deck.track = Some(track.clone());
        self.last_loaded = Some(track.id.clone());
        deck.status = Status::Loading;
        if let Some(a) = self.analyses.get(&track.id) {
            deck.grid = a.grid;
            deck.key = a.key;
        }
        self.apply_mixer(d);
        cx.notify();

        let player = self.core.player.clone();
        let (tx, rx) = tokio::sync::oneshot::channel();
        // A file on this computer opens as it is; a song of the catalogue as the player would.
        if let Some(path) = local_path(&track) {
            let cache = player.audio_cache();
            crate::core::runtime().spawn(async move {
                let source = lfly_audio::Source::file(path);
                let result = tokio::task::spawn_blocking(move || TrackBuf::open(&source, &cache)).await.map_err(|e| e.to_string()).and_then(|r| r);
                let _ = tx.send(result);
            });
            cx.spawn(async move |this, cx| {
                let Ok(result) = rx.await else { return };
                let _ = this.update(cx, |me, cx| me.opened(d, seq, result, cx));
            })
            .detach();
            return;
        }
        crate::core::runtime().spawn(async move {
            let open = |source: lfly_audio::Source, player: Arc<lfly_core::player::LocalPlayer>| async move {
                let cache = player.audio_cache();
                tokio::task::spawn_blocking(move || TrackBuf::open(&source, &cache)).await.map_err(|e| e.to_string())?
            };
            let mut result = match player.source_of(&track, false).await {
                Ok(s) => open(s, player.clone()).await,
                Err(e) => Err(e),
            };
            // A stream URL that expired: resolved once more.
            if result.is_err() {
                if let Ok(s) = player.source_of(&track, true).await {
                    result = open(s, player.clone()).await;
                }
            }
            let _ = tx.send(result);
        });
        cx.spawn(async move |this, cx| {
            let Ok(result) = rx.await else { return };
            let _ = this.update(cx, |me, cx| me.opened(d, seq, result, cx));
        })
        .detach();
    }

    fn opened(&mut self, d: usize, seq: u64, result: Result<Arc<TrackBuf>, String>, cx: &mut Context<Self>) {
        if self.decks[d].seq != seq {
            if let Ok(b) = result {
                b.cancel();
            }
            return;
        }
        let buf = match result {
            Ok(b) => b,
            Err(e) => {
                log::warn!("console deck {d}: {e}");
                self.decks[d].status = Status::Failed(e);
                Toasts::say(tr("Il brano non si apre sulla console"), cx);
                cx.notify();
                return;
            }
        };
        let Some(mixer) = self.mixer() else { return };
        let cue = self.decks[d].cue;
        mixer.load(d, buf.clone(), cue * buf.rate() as f64);
        self.decks[d].buf = Some(buf.clone());
        self.decks[d].status = Status::Ready;
        self.apply_mixer(d);
        cx.notify();
        // While it decodes: the progress shows; once decoded, the waveform and the analysis.
        cx.spawn(async move |this, cx| {
            loop {
                let done = buf.is_done();
                let alive = this
                    .update(cx, |me, cx| {
                        cx.notify();
                        me.decks[d].seq == seq
                    })
                    .unwrap_or(false);
                if !alive {
                    return;
                }
                if done {
                    break;
                }
                cx.background_executor().timer(Duration::from_millis(250)).await;
            }
            if buf.truncated() {
                let _ = this.update(cx, |_, cx| Toasts::say(tr("Brano troppo lungo: la console ne tiene 20 minuti"), cx));
            }
            // A stream that broke off: what came in plays, and the deck says so.
            if let Some(e) = buf.error() {
                log::warn!("console deck {d}: decoding stopped: {e}");
                let _ = this.update(cx, |_, cx| Toasts::say(tr("Il brano si è interrotto: sulla console ne è arrivata solo una parte"), cx));
            }
            let b = buf.clone();
            let peaks = cx.background_executor().spawn(async move { b.peaks(PEAKS_PER_SECOND) }).await;
            let cached = this
                .update(cx, |me, cx| {
                    if me.decks[d].seq != seq {
                        return true;
                    }
                    me.decks[d].peaks = Arc::new(peaks);
                    let have = me.decks[d].grid.is_some() || me.decks[d].key.is_some();
                    me.decks[d].analysing = !have;
                    if have {
                        me.grid_arrived(d, cx);
                    }
                    cx.notify();
                    have
                })
                .unwrap_or(true);
            if cached {
                return;
            }
            let b = buf.clone();
            let analysis = cx.background_executor().spawn(async move { lfly_core::decks::analyse(&b) }).await;
            let _ = this.update(cx, |me, cx| {
                if let Some(id) = me.decks[d].track.as_ref().map(|t| t.id.clone()) {
                    me.analyses.insert(id, analysis.clone());
                }
                if me.decks[d].seq != seq {
                    return;
                }
                me.decks[d].grid = analysis.grid;
                me.decks[d].key = analysis.key;
                me.decks[d].analysing = false;
                me.apply_mixer(d);
                me.grid_arrived(d, cx);
                cx.notify();
            });
        })
        .detach();
    }

    pub fn eject(&mut self, d: usize, cx: &mut Context<Self>) {
        if let Some(m) = &self.mixer {
            m.eject(d);
        }
        self.let_go(d);
        let deck = &mut self.decks[d];
        *deck = deck.renewed();
        self.apply_mixer(d);
        cx.notify();
    }

    /// The deck lets its song go: its decoding stops unless the other deck or a sample holds the
    /// same buffer.
    fn let_go(&mut self, d: usize) {
        let Some(buf) = self.decks[d].buf.take() else { return };
        let shared = self.decks[1 - d].buf.as_ref().is_some_and(|b| Arc::ptr_eq(b, &buf)) || self.samples.iter().flatten().any(|s| Arc::ptr_eq(&s.buf, &buf));
        if !shared {
            buf.cancel();
        }
    }

    // ---- Transport ----

    pub fn play(&mut self, d: usize, on: bool, cx: &mut Context<Self>) {
        if self.decks[d].buf.is_none() {
            return;
        }
        let Some(m) = self.mixer() else { return };
        if on {
            self.pause_player(cx);
            self.decks[d].cue_preview = false;
            // Auto sync on play: in on the other deck's beat, when it plays.
            if self.decks[d].auto_sync && !self.is_playing(d) && self.is_playing(1 - d) && self.decks[d].grid.is_some() && self.decks[1 - d].grid.is_some() {
                self.sync(d, cx);
            }
        }
        m.play(d, on);
        cx.notify();
    }

    pub fn play_pause(&mut self, d: usize, cx: &mut Context<Self>) {
        if self.decks[d].cue_preview {
            // PLAY while CUE is held: it goes on playing when CUE is let go.
            self.decks[d].cue_preview = false;
            cx.notify();
            return;
        }
        let playing = self.is_playing(d);
        if playing && self.decks[d].play_stutter {
            // Play-stutter: from the cue again, playing on.
            let cue = self.decks[d].cue;
            self.seek(d, cue, cx);
            return;
        }
        self.play(d, !playing, cx);
    }

    fn pause_player(&mut self, cx: &mut Context<Self>) {
        let m = self.model.read(cx);
        if m.playing() && m.remote.is_none() {
            m.command(protocol::CMD_PAUSE, 0);
        }
    }

    /// CUE pressed. Standard and hold-to-play: playing, back to the cue and stopped; stopped
    /// elsewhere, the cue moves here; stopped on it, it plays while held. Inverted: back to the
    /// cue and held there, playing on when let go.
    pub fn cue_down(&mut self, d: usize, cx: &mut Context<Self>) {
        if self.decks[d].buf.is_none() {
            return;
        }
        let Some(m) = self.mixer() else { return };
        let rate = self.decks[d].rate();
        self.decks[d].cue_since = Some(Instant::now());
        if self.decks[d].cue_mode == CueMode::Inverted {
            m.play(d, false);
            m.jump(d, self.decks[d].cue * rate);
            self.decks[d].cue_preview = true;
        } else if self.is_playing(d) {
            m.play(d, false);
            m.jump(d, self.decks[d].cue * rate);
        } else {
            let here = self.position(d);
            if (here - self.decks[d].cue).abs() > 0.01 {
                self.decks[d].cue = here;
            }
            self.pause_player(cx);
            m.play(d, true);
            self.decks[d].cue_preview = true;
        }
        cx.notify();
    }

    pub fn cue_up(&mut self, d: usize, cx: &mut Context<Self>) {
        let held = self.decks[d].cue_since.take().map_or(Duration::ZERO, |t| t.elapsed());
        if !self.decks[d].cue_preview {
            return;
        }
        self.decks[d].cue_preview = false;
        let Some(m) = self.mixer.clone() else { return };
        match self.decks[d].cue_mode {
            CueMode::Inverted => {
                self.pause_player(cx);
                m.play(d, true);
            }
            // Held over 3 s: it plays on.
            CueMode::HoldToPlay if held > Duration::from_secs(3) => {}
            _ => {
                m.play(d, false);
                m.jump(d, self.decks[d].cue * self.decks[d].rate());
            }
        }
        cx.notify();
    }

    /// STOP: playing, stopped at the start; stopped, on to the next hot cue; on an empty deck,
    /// the other deck's song cloned there, at its place and playing as it does.
    pub fn stop(&mut self, d: usize, cx: &mut Context<Self>) {
        if self.decks[d].buf.is_none() {
            self.clone_from(d, cx);
            return;
        }
        let Some(m) = self.mixer() else { return };
        if self.is_playing(d) {
            m.play(d, false);
            m.jump(d, 0.);
        } else {
            self.cue_step(d, 1, cx);
        }
        cx.notify();
    }

    /// The other deck's song on this one, at the same place, tempo and grid.
    pub fn clone_from(&mut self, d: usize, cx: &mut Context<Self>) {
        let o = 1 - d;
        let Some(buf) = self.decks[o].buf.clone() else { return };
        let Some(m) = self.mixer() else { return };
        let other = self.decks[o].clone();
        self.let_go(d);
        let deck = &mut self.decks[d];
        *deck = deck.renewed();
        deck.track = other.track.clone();
        deck.buf = Some(buf.clone());
        deck.status = Status::Ready;
        deck.peaks = other.peaks.clone();
        deck.grid = other.grid;
        deck.key = other.key;
        deck.hot = other.hot;
        deck.saved = other.saved;
        deck.cue = other.cue;
        deck.range = other.range;
        deck.fader = other.fader;
        let rate = buf.rate() as f64;
        m.load(d, buf, self.position(o) * rate);
        self.apply_mixer(d);
        if self.is_playing(o) {
            m.play(d, true);
        }
        cx.notify();
    }

    /// The next (or previous) hot cue: stopped, the deck goes there; playing, it jumps there.
    pub fn cue_step(&mut self, d: usize, dir: i32, cx: &mut Context<Self>) {
        let here = self.position(d);
        let mut set: Vec<f64> = self.decks[d].hot.iter().flatten().copied().collect();
        if set.is_empty() {
            return;
        }
        set.sort_by(f64::total_cmp);
        let to = if dir > 0 {
            set.iter().copied().find(|t| *t > here + 0.05).unwrap_or(set[0])
        } else {
            set.iter().rev().copied().find(|t| *t < here - 0.05).unwrap_or(*set.last().unwrap_or(&0.))
        };
        self.seek(d, to, cx);
    }

    /// To a share of the song (a click on its overview).
    pub fn seek_fraction(&mut self, d: usize, f: f32, cx: &mut Context<Self>) {
        let dur = self.decks[d].duration();
        self.seek(d, f.clamp(0., 1.) as f64 * dur, cx);
    }

    pub fn seek(&mut self, d: usize, t: f64, cx: &mut Context<Self>) {
        if self.decks[d].buf.is_none() {
            return;
        }
        if let Some(m) = self.mixer() {
            m.jump(d, t.max(0.) * self.decks[d].rate());
        }
        cx.notify();
    }

    // ---- Tempo ----

    pub fn set_pitch(&mut self, d: usize, fader: f32, cx: &mut Context<Self>) {
        self.resetting[d] = false;
        self.decks[d].fader = fader.clamp(-1., 1.);
        self.apply_mixer(d);
        // Pitch lock: the other deck follows, at the same tempo.
        // (Its range widens when the tempo is past it, as MATCH does.)
        if self.pitch_lock && self.decks[d].grid.is_some() {
            self.match_tempo(1 - d, cx);
        }
        cx.notify();
    }

    /// The pitch back to 0 over a second and a half (the arrow by the fader).
    pub fn pitch_reset(&mut self, d: usize, cx: &mut Context<Self>) {
        if self.resetting[d] || self.decks[d].fader == 0. {
            return;
        }
        self.resetting[d] = true;
        let from = self.decks[d].fader;
        let started = Instant::now();
        cx.spawn(async move |this, cx| loop {
            cx.background_executor().timer(Duration::from_millis(30)).await;
            let go_on = this
                .update(cx, |me, cx| {
                    if !me.resetting[d] {
                        return false;
                    }
                    let k = (started.elapsed().as_secs_f32() / 1.5).min(1.);
                    me.decks[d].fader = from * (1. - k);
                    me.apply_mixer(d);
                    cx.notify();
                    if k >= 1. {
                        me.resetting[d] = false;
                    }
                    k < 1.
                })
                .unwrap_or(false);
            if !go_on {
                break;
            }
        })
        .detach();
    }

    /// MATCH: the other deck's tempo, the phase left alone.
    pub fn match_tempo(&mut self, d: usize, cx: &mut Context<Self>) {
        let (Some(g), Some(target)) = (self.decks[d].grid, self.decks[1 - d].bpm()) else { return };
        let deck = &mut self.decks[d];
        let need = (target / g.bpm - 1.) as f32;
        if need.abs() > deck.range {
            deck.range = RANGES.iter().copied().find(|r| *r >= need.abs()).unwrap_or(1.);
        }
        deck.fader = (need / deck.range).clamp(-1., 1.);
        self.apply_mixer(d);
        cx.notify();
    }

    /// A song's grid is known: with auto BPM on, its tempo goes to the other deck's.
    fn grid_arrived(&mut self, d: usize, cx: &mut Context<Self>) {
        if self.decks[d].auto_bpm && self.decks[1 - d].grid.is_some() {
            self.match_tempo(d, cx);
        }
    }

    pub fn cycle_range(&mut self, d: usize, cx: &mut Context<Self>) {
        let deck = &mut self.decks[d];
        let ratio = deck.ratio() as f32;
        let i = RANGES.iter().position(|r| (*r - deck.range).abs() < 1e-4).map_or(0, |i| (i + 1) % RANGES.len());
        deck.range = RANGES[i];
        // The tempo stays where it is when the new range holds it.
        deck.fader = ((ratio - 1.) / deck.range).clamp(-1., 1.);
        self.apply_mixer(d);
        cx.notify();
    }

    /// Pitch bend held (+1 faster, -1 slower) or let go (0).
    pub fn bend(&mut self, d: usize, dir: f32, cx: &mut Context<Self>) {
        self.decks[d].bend = 1. + dir.signum() * BEND * (dir != 0.) as u8 as f32;
        self.apply_mixer(d);
        cx.notify();
    }

    pub fn toggle_keylock(&mut self, d: usize, cx: &mut Context<Self>) {
        self.decks[d].keylock = !self.decks[d].keylock;
        self.mixer();
        self.apply_mixer(d);
        cx.notify();
    }

    pub fn toggle_pitch_lock(&mut self, cx: &mut Context<Self>) {
        self.pitch_lock = !self.pitch_lock;
        cx.notify();
    }

    /// SYNC: the other deck's tempo, and its beat phase.
    pub fn sync(&mut self, d: usize, cx: &mut Context<Self>) {
        let o = 1 - d;
        let (Some(g), Some(og)) = (self.decks[d].grid, self.decks[o].grid) else {
            Toasts::say(tr("SYNC: serve il tempo di entrambi i deck"), cx);
            return;
        };
        if self.decks[o].buf.is_none() {
            return;
        }
        let mut target = og.bpm * self.decks[o].ratio() / g.bpm;
        // Half or double time when that is closer (a 70 BPM song against a 140 one).
        for k in [0.5, 2.] {
            if (target * k - 1.).abs() < (target - 1.).abs() {
                target *= k;
            }
        }
        let deck = &mut self.decks[d];
        let need = (target - 1.).abs() as f32;
        if need > deck.range {
            deck.range = RANGES.iter().copied().find(|r| *r >= need).unwrap_or(1.);
        }
        deck.fader = (((target - 1.) as f32) / deck.range).clamp(-1., 1.);
        self.apply_mixer(d);
        // The phase: the beat fraction of each deck, read from the same block of the engine.
        let (here, there) = (self.position(d), self.position(o));
        let mine = g.beat_at(here);
        let theirs = og.beat_at(there);
        let diff = lfly_mix::beat_grid::BeatGrid::wrap_centered(theirs.rem_euclid(1.) - mine.rem_euclid(1.), 1.);
        if let Some(m) = &self.mixer {
            m.nudge(d, diff * g.beat_seconds() * self.decks[d].rate());
        }
        cx.notify();
    }

    /// The tempo doubled or halved (the analysis took half or double time).
    pub fn scale_bpm(&mut self, d: usize, k: f64, cx: &mut Context<Self>) {
        if let Some(g) = self.decks[d].grid {
            self.decks[d].grid = Some(g.scaled(k));
            self.remember_grid(d);
            self.apply_mixer(d);
            cx.notify();
        }
    }

    /// TAP: four taps or more set the tempo, the grid's beat on the last tap.
    pub fn tap(&mut self, d: usize, cx: &mut Context<Self>) {
        let now = Instant::now();
        let deck = &mut self.decks[d];
        deck.taps.retain(|t| now.duration_since(*t) < Duration::from_secs(2));
        deck.taps.push(now);
        if deck.taps.len() >= 4 {
            let n = deck.taps.len();
            let span = deck.taps[n - 1].duration_since(deck.taps[0]).as_secs_f64();
            let bpm = 60. * (n - 1) as f64 / span / deck.ratio();
            if (50. ..=220.).contains(&bpm) {
                let at = self.position(d);
                self.decks[d].grid = Some(Grid::tapped((bpm * 10.).round() / 10., at));
                self.remember_grid(d);
                self.apply_mixer(d);
            }
        }
        cx.notify();
    }

    fn remember_grid(&mut self, d: usize) {
        if let Some(id) = self.decks[d].track.as_ref().map(|t| t.id.clone()) {
            let a = self.analyses.entry(id).or_default();
            a.grid = self.decks[d].grid;
            a.key = self.decks[d].key;
        }
    }

    // ---- The platter ----

    pub fn jog_touch(&mut self, d: usize, cx: &mut Context<Self>) {
        if self.decks[d].buf.is_none() {
            return;
        }
        self.decks[d].jog = Some(Instant::now());
        if self.decks[d].vinyl {
            if let Some(m) = self.mixer() {
                m.scratch(d, Some(0.));
            }
        }
        cx.notify();
    }

    /// The platter turned by [turns] over [secs].
    pub fn jog_move(&mut self, d: usize, turns: f64, secs: f64, cx: &mut Context<Self>) {
        let Some(m) = self.mixer.clone() else { return };
        if self.decks[d].jog.is_none() {
            return;
        }
        self.decks[d].jog = Some(Instant::now());
        let secs = secs.max(0.004);
        if self.decks[d].vinyl {
            m.scratch(d, Some((turns * TURN_SECONDS / secs).clamp(-8., 8.)));
        } else if self.is_playing(d) {
            // CD mode: the platter bends the tempo while it turns.
            let bend = 1. + (turns / secs * 0.3).clamp(-0.5, 0.5);
            m.deck(d).bend.set(bend as f32);
        } else {
            // Stopped: it searches.
            m.nudge(d, turns * TURN_SECONDS * self.decks[d].rate());
        }
        let _ = cx;
    }

    /// The hand rests on the platter: a held record stops.
    pub fn jog_rest(&mut self, d: usize) {
        let Some(m) = &self.mixer else { return };
        if self.decks[d].jog.is_some_and(|t| t.elapsed() > Duration::from_millis(40)) {
            if self.decks[d].vinyl {
                m.scratch(d, Some(0.));
            } else {
                m.deck(d).bend.set(self.decks[d].bend);
            }
        }
    }

    pub fn jog_release(&mut self, d: usize, cx: &mut Context<Self>) {
        if self.decks[d].jog.take().is_none() {
            return;
        }
        if let Some(m) = &self.mixer {
            if self.decks[d].vinyl {
                m.scratch(d, None);
            } else {
                m.deck(d).bend.set(self.decks[d].bend);
            }
        }
        cx.notify();
    }

    pub fn toggle_vinyl(&mut self, d: usize, cx: &mut Context<Self>) {
        self.decks[d].vinyl = !self.decks[d].vinyl;
        cx.notify();
    }

    pub fn toggle_slip(&mut self, d: usize, cx: &mut Context<Self>) {
        self.decks[d].slip = !self.decks[d].slip;
        if let Some(m) = self.mixer() {
            m.slip(d, self.decks[d].slip);
        }
        cx.notify();
    }

    // ---- Cue points ----

    /// A hot cue pad: set where the deck is when empty, else jump there (with [delete], cleared).
    pub fn hot_cue(&mut self, d: usize, i: usize, delete: bool, cx: &mut Context<Self>) {
        if self.decks[d].buf.is_none() || i >= HOT_CUES {
            return;
        }
        if delete {
            self.decks[d].hot[i] = None;
        } else if let Some(t) = self.decks[d].hot[i] {
            if self.decks[d].hot_stutter {
                // Stutter: it plays from the cue while held, and goes back when let go.
                if self.decks[d].stutter_at.is_none() {
                    let playing = self.is_playing(d);
                    self.decks[d].stutter_at = Some(if playing { -1. } else { self.position(d) });
                }
                self.decks[d].pad_held = Some((PadMode::HotCue, i));
                self.seek(d, t, cx);
                self.play(d, true, cx);
                return;
            }
            self.seek(d, t, cx);
            return;
        } else {
            let t = self.snap(d, self.position(d));
            self.decks[d].hot[i] = Some(t);
        }
        if let Some(id) = self.decks[d].track.as_ref().map(|t| t.id.clone()) {
            save_points(&id, &self.decks[d].hot, &self.decks[d].saved);
        }
        cx.notify();
    }

    // ---- Loops ----

    fn engage(&mut self, d: usize, span: Option<(f64, f64)>, cx: &mut Context<Self>) {
        let rate = self.decks[d].rate();
        self.decks[d].looped = span;
        if span.is_some() {
            self.decks[d].last_loop = span;
        }
        if let Some(m) = self.mixer() {
            m.set_loop(d, span.map(|(a, b)| (a * rate, b * rate)));
        }
        cx.notify();
    }

    /// The auto loop on (from the beat just passed, [loop_beats] long) or off.
    pub fn auto_loop(&mut self, d: usize, cx: &mut Context<Self>) {
        if self.decks[d].buf.is_none() {
            return;
        }
        if self.decks[d].looped.is_some() {
            self.engage(d, None, cx);
            return;
        }
        let beats = self.decks[d].loop_beats;
        self.loop_of(d, beats, cx);
    }

    fn loop_of(&mut self, d: usize, beats: f64, cx: &mut Context<Self>) {
        let here = self.position(d);
        let beat = self.decks[d].beat();
        let back = self.decks[d].loop_back;
        let start = match self.decks[d].grid {
            Some(g) if self.quantize => {
                // On the grid of the loop's own size when it is under a beat, else on the beat;
                // loop back: ending on the next such line instead of starting on the last.
                let unit = beats.min(1.);
                let at = g.beat_at(here) / unit;
                if back { g.time_of(at.ceil() * unit) - beats * beat } else { g.time_of(at.floor() * unit) }
            }
            _ if back => here - beats * beat,
            _ => here,
        };
        let start = start.max(0.);
        self.engage(d, Some((start, start + beats * beat)), cx);
    }

    pub fn loop_size(&mut self, d: usize, up: bool, cx: &mut Context<Self>) {
        let deck = &mut self.decks[d];
        let i = LOOP_SIZES.iter().position(|s| (*s - deck.loop_beats).abs() < 1e-9).unwrap_or(7);
        let j = if up { (i + 1).min(LOOP_SIZES.len() - 1) } else { i.saturating_sub(1) };
        deck.loop_beats = LOOP_SIZES[j];
        if let Some((a, _)) = deck.looped {
            let b = a + deck.loop_beats * deck.beat();
            self.engage(d, Some((a, b)), cx);
        }
        cx.notify();
    }

    pub fn loop_in(&mut self, d: usize, cx: &mut Context<Self>) {
        if self.decks[d].buf.is_none() {
            return;
        }
        let t = self.snap(d, self.position(d));
        self.decks[d].loop_in = Some(t);
        cx.notify();
    }

    pub fn loop_out(&mut self, d: usize, cx: &mut Context<Self>) {
        let Some(a) = self.decks[d].loop_in.take().or(self.decks[d].looped.map(|l| l.0)) else { return };
        let b = self.snap(d, self.position(d));
        if b > a + 0.01 {
            self.decks[d].loop_beats = (b - a) / self.decks[d].beat();
            self.engage(d, Some((a, b)), cx);
        }
    }

    /// RELOOP: the last loop again, from its start; or out of the one on.
    pub fn reloop(&mut self, d: usize, cx: &mut Context<Self>) {
        if self.decks[d].looped.is_some() {
            self.engage(d, None, cx);
        } else if let Some((a, b)) = self.decks[d].last_loop {
            self.seek(d, a, cx);
            self.engage(d, Some((a, b)), cx);
        }
    }

    /// The loop moved by its own length (or [beats]), forward or back.
    pub fn loop_move(&mut self, d: usize, beats: f64, cx: &mut Context<Self>) {
        if let Some((a, b)) = self.decks[d].looped {
            let by = beats * self.decks[d].beat();
            self.engage(d, Some((a + by, b + by)), cx);
            let t = self.position(d) + by;
            self.seek(d, t, cx);
        }
    }

    // ---- Pads ----

    pub fn set_pads(&mut self, d: usize, mode: PadMode, cx: &mut Context<Self>) {
        self.decks[d].pads = mode;
        cx.notify();
    }

    pub fn step_pads(&mut self, d: usize, by: i32, cx: &mut Context<Self>) {
        let mode = self.decks[d].pads.step(by);
        self.set_pads(d, mode, cx);
    }

    fn save_points_of(&self, d: usize) {
        if let Some(id) = self.decks[d].track.as_ref().map(|t| t.id.clone()) {
            save_points(&id, &self.decks[d].hot, &self.decks[d].saved);
        }
    }

    /// The slice the SLICER's pad [i] stands for: eight slices of a beat each, from the bar pair
    /// the deck is in.
    fn slice(&self, d: usize, i: usize) -> Option<(f64, f64)> {
        let g = self.decks[d].grid?;
        let at = g.beat_at(self.position(d));
        let first = (at / 8.).floor() * 8.;
        let a = g.time_of(first + i as f64);
        Some((a.max(0.), g.time_of(first + i as f64 + 1.)))
    }

    /// The SLICER's step playing now (0..8), lit on its pad.
    pub fn slice_now(&self, d: usize) -> Option<usize> {
        let g = self.decks[d].grid?;
        Some(g.beat_at(self.position(d)).rem_euclid(8.) as usize)
    }

    fn roll_on(&mut self, d: usize, a: f64, b: f64, held: (PadMode, usize), cx: &mut Context<Self>) {
        let rate = self.decks[d].rate();
        if let Some(m) = self.mixer() {
            m.roll(d, Some((a * rate, b * rate)));
        }
        self.decks[d].pad_held = Some(held);
        cx.notify();
    }

    /// A pad pressed ([alt]: the right button, which deletes a cue, a loop or a sample).
    pub fn pad_down(&mut self, d: usize, i: usize, alt: bool, cx: &mut Context<Self>) {
        let loaded = self.decks[d].buf.is_some();
        match self.decks[d].pads {
            PadMode::HotCue => self.hot_cue(d, i, alt, cx),
            PadMode::CueLoop => {
                if !loaded {
                    return;
                }
                if alt {
                    self.decks[d].hot[i] = None;
                    self.save_points_of(d);
                } else if let Some(t) = self.decks[d].hot[i] {
                    // To the cue, looping from it for the loop size.
                    let beats = self.decks[d].loop_beats;
                    let beat = self.decks[d].beat();
                    self.engage(d, None, cx);
                    self.seek(d, t, cx);
                    self.engage(d, Some((t, t + beats * beat)), cx);
                    if !self.is_playing(d) {
                        self.play(d, true, cx);
                    }
                } else {
                    let t = self.snap(d, self.position(d));
                    self.decks[d].hot[i] = Some(t);
                    self.save_points_of(d);
                }
                cx.notify();
            }
            PadMode::SavedLoop => {
                if !loaded {
                    return;
                }
                if alt {
                    self.decks[d].saved[i] = None;
                } else if let Some((a, b)) = self.decks[d].saved[i] {
                    // Engaged where it is when the song has not passed it yet, else jumped to.
                    if self.position(d) > b {
                        self.seek(d, a, cx);
                    }
                    self.engage(d, Some((a, b)), cx);
                } else {
                    // Empty: the loop on now is saved, or one of the loop size made and saved.
                    if self.decks[d].looped.is_none() {
                        let beats = self.decks[d].loop_beats;
                        self.loop_of(d, beats, cx);
                    }
                    self.decks[d].saved[i] = self.decks[d].looped;
                }
                self.save_points_of(d);
                cx.notify();
            }
            PadMode::Roll => {
                if !loaded {
                    return;
                }
                let beats = ROLL_SIZES[i];
                let here = self.position(d);
                let beat = self.decks[d].beat();
                let start = match self.decks[d].grid {
                    Some(g) => {
                        let unit = beats.min(1.);
                        g.time_of((g.beat_at(here) / unit).floor() * unit).max(0.)
                    }
                    None => here,
                };
                self.roll_on(d, start, start + beats * beat, (PadMode::Roll, i), cx);
            }
            PadMode::Slicer => {
                if let Some((a, b)) = self.slice(d, i) {
                    self.roll_on(d, a, b, (PadMode::Slicer, i), cx);
                } else {
                    Toasts::say(tr("Lo slicer ha bisogno del tempo del brano"), cx);
                }
            }
            PadMode::Loop => {
                let beats = LOOP_PADS[i];
                match self.decks[d].loop_pads {
                    LoopPads::Roll => {
                        let here = self.position(d);
                        let beat = self.decks[d].beat();
                        let start = self.decks[d].grid.map_or(here, |g| g.time_of((g.beat_at(here) / beats.min(1.)).floor() * beats.min(1.)).max(0.));
                        self.roll_on(d, start, start + beats * beat, (PadMode::Loop, i), cx);
                    }
                    mode => {
                        let same = self.decks[d].looped.is_some_and(|(a, b)| ((b - a) / self.decks[d].beat() - beats).abs() < 1e-6);
                        if same && mode == LoopPads::OnOff {
                            self.engage(d, None, cx);
                        } else {
                            self.decks[d].loop_beats = beats;
                            self.engage(d, None, cx);
                            self.loop_of(d, beats, cx);
                            if mode == LoopPads::Hold {
                                self.decks[d].pad_held = Some((PadMode::Loop, i));
                            }
                        }
                    }
                }
            }
            PadMode::Manual => {
                // IN, OUT; the in point back and on, the out point back and on (a 16th of a
                // beat), the whole loop back and on by its length.
                let step = self.decks[d].beat() / 16.;
                match i {
                    0 => self.loop_in(d, cx),
                    1 => self.loop_out(d, cx),
                    2..=5 => {
                        if let Some((a, b)) = self.decks[d].looped {
                            let (a, b) = match i {
                                2 => (a - step, b),
                                3 => ((a + step).min(b - step), b),
                                4 => (a, (b - step).max(a + step)),
                                _ => (a, b + step),
                            };
                            self.decks[d].loop_beats = (b - a) / self.decks[d].beat();
                            self.engage(d, Some((a.max(0.), b)), cx);
                        }
                    }
                    6 => self.loop_move_by_length(d, -1., cx),
                    _ => self.loop_move_by_length(d, 1., cx),
                }
            }
            PadMode::Jump => {
                let by = JUMPS[i] * self.decks[d].beat();
                if self.decks[d].looped.is_some() {
                    self.loop_move(d, JUMPS[i], cx);
                } else {
                    let t = self.position(d) + by;
                    self.seek(d, t, cx);
                }
            }
            PadMode::Sampler => {
                if alt {
                    // The right button stops a sample playing, and clears one that is not.
                    if self.sample_playing(i) {
                        if let Some(m) = &self.mixer {
                            m.trigger(i, false);
                        }
                        cx.notify();
                    } else {
                        self.sample_clear(i, cx);
                    }
                } else {
                    self.sample_down(d, i, cx);
                }
            }
            PadMode::Grid => match i {
                0 => self.grid_shift(d, -1., cx),
                1 => self.grid_shift(d, -0.01, cx),
                2 => self.grid_shift(d, 0.01, cx),
                3 => self.grid_shift(d, 1., cx),
                4 => self.bpm_nudge(d, -0.05, cx),
                5 => self.bpm_nudge(d, 0.05, cx),
                6 => self.tap(d, cx),
                _ => self.beat_here(d, cx),
            },
            PadMode::Perform => match i {
                0 => {
                    if let Some(m) = self.mixer() {
                        m.brake(d, 1.2);
                    }
                }
                1 => {
                    if let Some(m) = self.mixer() {
                        m.backspin(d, 0.8);
                    }
                }
                2 => {
                    self.decks[d].censor = true;
                    self.decks[d].pad_held = Some((PadMode::Perform, i));
                    if let Some(m) = self.mixer() {
                        m.censor(d, true);
                    }
                    cx.notify();
                }
                3 => {
                    self.decks[d].reverse = !self.decks[d].reverse;
                    if let Some(m) = self.mixer() {
                        m.reverse(d, self.decks[d].reverse);
                    }
                    cx.notify();
                }
                4 => self.scale_bpm(d, 0.5, cx),
                5 => self.scale_bpm(d, 2., cx),
                6 => self.match_tempo(d, cx),
                _ => self.eject_or_next(d, cx),
            },
            PadMode::Options => {
                let deck = &mut self.decks[d];
                match i {
                    0 => {
                        deck.cue_mode = match deck.cue_mode {
                            CueMode::Standard => CueMode::HoldToPlay,
                            CueMode::HoldToPlay => CueMode::Inverted,
                            CueMode::Inverted => CueMode::Standard,
                        }
                    }
                    1 => deck.play_stutter = !deck.play_stutter,
                    2 => deck.hot_stutter = !deck.hot_stutter,
                    3 => {
                        deck.loop_pads = match deck.loop_pads {
                            LoopPads::OnOff => LoopPads::Hold,
                            LoopPads::Hold => LoopPads::Roll,
                            LoopPads::Roll => LoopPads::OnOff,
                        }
                    }
                    4 => deck.loop_back = !deck.loop_back,
                    5 => deck.auto_bpm = !deck.auto_bpm,
                    6 => deck.auto_sync = !deck.auto_sync,
                    _ => self.sample_mode = match self.sample_mode {
                        SampleMode::OnOff => SampleMode::Hold,
                        SampleMode::Hold => SampleMode::Stutter,
                        SampleMode::Stutter => SampleMode::OnOff,
                    },
                }
                cx.notify();
            }
        }
    }

    pub fn pad_up(&mut self, d: usize, cx: &mut Context<Self>) {
        let Some((mode, i)) = self.decks[d].pad_held.take() else { return };
        match mode {
            PadMode::Roll | PadMode::Slicer | PadMode::Loop if mode != PadMode::Loop || self.decks[d].loop_pads == LoopPads::Roll => {
                if let Some(m) = &self.mixer {
                    m.roll(d, None);
                }
            }
            PadMode::Loop => self.engage(d, None, cx),
            PadMode::HotCue => {
                // A stutter hot cue let go: stopped back where the deck was (or playing on, if
                // it was playing).
                match self.decks[d].stutter_at.take() {
                    Some(t) if t >= 0. => {
                        self.play(d, false, cx);
                        let cue = self.decks[d].hot[i].unwrap_or(t);
                        self.seek(d, cue, cx);
                    }
                    _ => {}
                }
            }
            PadMode::Sampler => {
                if self.sample_mode == SampleMode::Hold {
                    if let Some(m) = &self.mixer {
                        m.trigger(i, false);
                    }
                }
            }
            PadMode::Perform if i == 2 => {
                self.decks[d].censor = false;
                if let Some(m) = &self.mixer {
                    m.censor(d, false);
                }
            }
            _ => {}
        }
        cx.notify();
    }

    /// What a pad shows: its label and whether it is lit.
    pub fn pad_label(&self, d: usize, i: usize) -> (String, bool) {
        let deck = &self.decks[d];
        let held = deck.pad_held == Some((deck.pads, i));
        let on_off = |b: bool| if b { "ON" } else { "OFF" };
        match deck.pads {
            PadMode::HotCue | PadMode::CueLoop => match deck.hot[i] {
                Some(t) => (crate::console::tenths(t * 1000.), true),
                None => (format!("{}", i + 1), false),
            },
            PadMode::SavedLoop => match deck.saved[i] {
                Some((a, b)) => (beats_label((b - a) / deck.beat()), deck.looped == Some((a, b))),
                None => (format!("{}", i + 1), false),
            },
            PadMode::Roll => (beats_label(ROLL_SIZES[i]), held),
            PadMode::Slicer => (format!("{}", i + 1), held || (deck.grid.is_some() && self.is_playing(d) && self.slice_now(d) == Some(i))),
            PadMode::Loop => {
                let on = deck.looped.is_some_and(|(a, b)| ((b - a) / deck.beat() - LOOP_PADS[i]).abs() < 1e-6);
                (beats_label(LOOP_PADS[i]), on || held)
            }
            PadMode::Manual => {
                let names = ["IN", "OUT", "◀ IN", "IN ▶", "◀ OUT", "OUT ▶", "◀ MOVE", "MOVE ▶"];
                (names[i].to_string(), i == 0 && deck.loop_in.is_some())
            }
            PadMode::Jump => (format!("{}{}{}", if JUMPS[i] < 0. { "◀ " } else { "" }, JUMPS[i].abs(), if JUMPS[i] > 0. { " ▶" } else { "" }), false),
            PadMode::Sampler => match &self.samples[i] {
                Some(smp) => (smp.name.clone(), self.sample_playing(i)),
                None => ("REC".to_string(), false),
            },
            PadMode::Grid => {
                let names = ["◀ BEAT", "◀ 1%", "1% ▶", "BEAT ▶", "BPM −", "BPM +", "TAP", "1 HERE"];
                (names[i].to_string(), false)
            }
            PadMode::Perform => {
                let names = ["BRAKE", "SPINBACK", "CENSOR", "REVERSE", "BPM ÷2", "BPM ×2", "MATCH", "EJECT"];
                let on = match i {
                    2 => deck.censor,
                    3 => deck.reverse,
                    _ => false,
                };
                (names[i].to_string(), on)
            }
            PadMode::Options => match i {
                0 => (
                    format!(
                        "CUE {}",
                        match deck.cue_mode {
                            CueMode::Standard => "STD",
                            CueMode::HoldToPlay => "HOLD",
                            CueMode::Inverted => "INV",
                        }
                    ),
                    deck.cue_mode != CueMode::Standard,
                ),
                1 => (format!("STUTTER {}", on_off(deck.play_stutter)), deck.play_stutter),
                2 => (format!("HC STUT {}", on_off(deck.hot_stutter)), deck.hot_stutter),
                3 => (
                    format!(
                        "PADS {}",
                        match deck.loop_pads {
                            LoopPads::OnOff => "ON/OFF",
                            LoopPads::Hold => "HOLD",
                            LoopPads::Roll => "ROLL",
                        }
                    ),
                    deck.loop_pads != LoopPads::OnOff,
                ),
                4 => (format!("LOOP BACK {}", on_off(deck.loop_back)), deck.loop_back),
                5 => (format!("AUTO BPM {}", on_off(deck.auto_bpm)), deck.auto_bpm),
                6 => (format!("AUTO SYNC {}", on_off(deck.auto_sync)), deck.auto_sync),
                _ => (
                    format!(
                        "SMP {}",
                        match self.sample_mode {
                            SampleMode::OnOff => "ON/OFF",
                            SampleMode::Hold => "HOLD",
                            SampleMode::Stutter => "STUTTER",
                        }
                    ),
                    false,
                ),
            },
        }
    }

    // ---- The beat grid by hand ----

    /// The grid moved by [beats] (a whole beat, or 0.01 for 1% of one).
    pub fn grid_shift(&mut self, d: usize, beats: f64, cx: &mut Context<Self>) {
        let Some(g) = self.decks[d].grid else { return };
        // The first beat moved; when it crosses a beat line the grid's first beat is another one
        // of the bar.
        let p = g.beat_seconds();
        let raw = g.first_beat + beats * p;
        let k = (raw / p).floor();
        let g2 = Grid { first_beat: raw - k * p, first_in_bar: ((g.first_in_bar as i64 - k as i64).rem_euclid(4)) as u32, ..g };
        self.decks[d].grid = Some(g2);
        self.remember_grid(d);
        self.apply_mixer(d);
        cx.notify();
    }

    /// The tempo nudged by [delta] BPM, the beat under the playhead kept where it is.
    pub fn bpm_nudge(&mut self, d: usize, delta: f64, cx: &mut Context<Self>) {
        let Some(g) = self.decks[d].grid else { return };
        let here = self.position(d);
        let k = g.beat_at(here);
        let bpm = (g.bpm + delta).clamp(40., 250.);
        let first = here - k * 60. / bpm;
        self.decks[d].grid = Some(Grid { bpm, first_beat: first.rem_euclid(60. / bpm), first_in_bar: ((g.first_in_bar as i64 + (first / (60. / bpm)).floor() as i64).rem_euclid(4)) as u32 });
        self.remember_grid(d);
        self.apply_mixer(d);
        cx.notify();
    }

    /// The first beat of a bar right where the deck is (the tempo kept, 120 without one).
    pub fn beat_here(&mut self, d: usize, cx: &mut Context<Self>) {
        let bpm = self.decks[d].grid.map_or(120., |g| g.bpm);
        let at = self.position(d);
        let mut g = Grid::tapped(bpm, at);
        // The beat here is the bar's first.
        g.first_in_bar = ((-(((at - g.first_beat) / g.beat_seconds()).round() as i64)).rem_euclid(4)) as u32;
        self.decks[d].grid = Some(g);
        self.remember_grid(d);
        self.apply_mixer(d);
        cx.notify();
    }

    // ---- Phrases ----

    /// Where the deck is in its 16-beat phrase: (bar 0..4, beat 0..4).
    pub fn phrase(&self, d: usize) -> Option<(u32, u32)> {
        let g = self.decks[d].grid?;
        let b = (g.beat_at(self.position(d)) + g.first_in_bar as f64 + 0.01).floor().rem_euclid(16.) as u32;
        Some((b / 4, b % 4))
    }

    /// The deck moved by whole beats to the other deck's place in its phrase.
    pub fn phrase_sync(&mut self, d: usize, cx: &mut Context<Self>) {
        let (Some((ab, at)), Some((ob, ot))) = (self.phrase(d), self.phrase(1 - d)) else { return };
        let mine = (ab * 4 + at) as i64;
        let theirs = (ob * 4 + ot) as i64;
        let mut by = (theirs - mine).rem_euclid(16);
        if by > 8 {
            by -= 16;
        }
        if by != 0 {
            let t = self.position(d) + by as f64 * self.decks[d].beat();
            self.seek(d, t, cx);
        }
    }

    // ---- Sampler ----

    pub fn sample_playing(&self, slot: usize) -> bool {
        self.mixer.as_ref().is_some_and(|m| m.state.samples_playing[slot].load(std::sync::atomic::Ordering::Relaxed))
    }

    /// A sampler pad: empty, it records the deck's loop (or the next four beats from the
    /// playhead); full, it plays as the sampler's mode says.
    pub fn sample_down(&mut self, d: usize, slot: usize, cx: &mut Context<Self>) {
        let Some(m) = self.mixer() else { return };
        if self.samples[slot].is_none() {
            let deck = &self.decks[d];
            let (Some(buf), Some(track)) = (deck.buf.clone(), deck.track.clone()) else { return };
            let (a, b) = match deck.looped {
                Some(l) => l,
                None => {
                    let here = self.snap(d, self.position(d));
                    (here, here + 4. * deck.beat())
                }
            };
            let rate = buf.rate() as f64;
            // A copy of the stretch alone (a minute at most): the song itself may go.
            let small = buf.slice(a * rate, (b.min(a + 60.)) * rate);
            let len = small.ready() as f64;
            self.samples[slot] = Some(Sample { name: track.title.chars().take(10).collect(), buf: small.clone() });
            m.set_sample(slot, Some((small, 0., len, deck.looped.is_some())));
            cx.notify();
            return;
        }
        let playing = self.sample_playing(slot);
        match self.sample_mode {
            SampleMode::OnOff => m.trigger(slot, !playing),
            SampleMode::Hold | SampleMode::Stutter => m.trigger(slot, true),
        }
        self.decks[d].pad_held = Some((PadMode::Sampler, slot));
        cx.notify();
    }

    pub fn sample_clear(&mut self, slot: usize, cx: &mut Context<Self>) {
        if self.samples[slot].take().is_some() {
            if let Some(m) = &self.mixer {
                m.trigger(slot, false);
                m.set_sample(slot, None);
            }
        }
        cx.notify();
    }

    pub fn set_sampler_volume(&mut self, v: f32, cx: &mut Context<Self>) {
        self.sampler_volume = v.clamp(0., 1.);
        self.apply_master();
        cx.notify();
    }

    /// EJECT: a loaded deck lets its song go; an empty one loads the browser's next song.
    pub fn eject_or_next(&mut self, d: usize, cx: &mut Context<Self>) {
        if self.decks[d].buf.is_some() || self.decks[d].status == Status::Loading {
            self.eject(d, cx);
            return;
        }
        let list = self.browse.clone();
        let after = self.last_loaded.as_ref().and_then(|id| list.iter().position(|t| &t.id == id)).map_or(0, |i| i + 1);
        if let Some(t) = list.get(after).or(list.first()).cloned() {
            self.load(d, t, cx);
        }
    }

    fn loop_move_by_length(&mut self, d: usize, dir: f64, cx: &mut Context<Self>) {
        if let Some((a, b)) = self.decks[d].looped {
            let beats = (b - a) / self.decks[d].beat();
            self.loop_move(d, dir * beats, cx);
        }
    }

    // ---- Mixer ----

    /// A knob: 0 gain, 1 high, 2 mid, 3 low, 4 filter.
    pub fn set_knob(&mut self, d: usize, k: usize, v: f32, cx: &mut Context<Self>) {
        self.decks[d].knobs[k.min(4)] = v.clamp(0., 1.);
        self.mixer();
        self.apply_mixer(d);
        cx.notify();
    }

    /// An EQ band to nothing and back (a kill switch).
    pub fn kill(&mut self, d: usize, k: usize, cx: &mut Context<Self>) {
        let v = if self.decks[d].knobs[k] <= 0.001 { 0.5 } else { 0. };
        self.set_knob(d, k, v, cx);
    }

    pub fn set_volume(&mut self, d: usize, v: f32, cx: &mut Context<Self>) {
        self.decks[d].volume = v.clamp(0., 1.);
        self.mixer();
        self.apply_mixer(d);
        cx.notify();
    }

    pub fn set_xfader(&mut self, x: f32, cx: &mut Context<Self>) {
        self.xfader = x.clamp(0., 1.);
        self.mixer();
        self.apply_master();
        cx.notify();
    }

    pub fn cycle_curve(&mut self, cx: &mut Context<Self>) {
        let i = Curve::ALL.iter().position(|c| *c == self.curve).map_or(0, |i| (i + 1) % Curve::ALL.len());
        self.curve = Curve::ALL[i];
        self.apply_master();
        cx.notify();
    }

    pub fn set_master(&mut self, v: f32, cx: &mut Context<Self>) {
        self.master = v.clamp(0., 1.);
        self.mixer();
        self.apply_master();
        cx.notify();
    }

    pub fn toggle_quantize(&mut self, cx: &mut Context<Self>) {
        self.quantize = !self.quantize;
        cx.notify();
    }

    /// REC: the master output into a WAV file in the music folder.
    pub fn toggle_record(&mut self, cx: &mut Context<Self>) {
        let Some(m) = self.mixer() else { return };
        if let Some(path) = self.recording.take() {
            let _ = m.record(None);
            Toasts::say(trf("Registrazione salvata: {0}", &[&path.display()]), cx);
        } else if let Some(path) = recording_path() {
            match m.record(Some(path.clone())) {
                Ok(()) => self.recording = Some(path),
                Err(e) => Toasts::say(trf("Registrazione non avviata: {0}", &[&e]), cx),
            }
        }
        cx.notify();
    }

    // ---- Effects ----

    pub fn fx_toggle(&mut self, d: usize, slot: usize, cx: &mut Context<Self>) {
        self.decks[d].fx_on[slot] = !self.decks[d].fx_on[slot];
        self.mixer();
        self.apply_mixer(d);
        cx.notify();
    }

    /// The slot's next effect.
    pub fn fx_next(&mut self, d: usize, slot: usize, cx: &mut Context<Self>) {
        let cur = self.decks[d].fx[slot];
        let i = FxKind::ALL.iter().position(|k| *k == cur).map_or(0, |i| (i + 1) % FxKind::ALL.len());
        self.decks[d].fx[slot] = FxKind::ALL[i];
        self.apply_mixer(d);
        cx.notify();
    }

    pub fn fx_amount(&mut self, d: usize, slot: usize, v: f32, cx: &mut Context<Self>) {
        self.decks[d].fx_amount[slot] = v.clamp(0., 1.);
        self.apply_mixer(d);
        cx.notify();
    }

    // ---- Display ----

    pub fn toggle_time(&mut self, d: usize, cx: &mut Context<Self>) {
        self.decks[d].time = (self.decks[d].time + 1) % 3;
        cx.notify();
    }

    /// The crossfader side: its own, none (always heard), the other one.
    pub fn cycle_assign(&mut self, d: usize, cx: &mut Context<Self>) {
        let own = d as u8 + 1;
        let other = 2 - d as u8;
        self.decks[d].assign = match self.decks[d].assign {
            0 => 3,
            3 => other,
            x if x == own => 3,
            _ => 0,
        };
        self.mixer();
        self.apply_mixer(d);
        cx.notify();
    }

    /// The crossfader side shown: "A", "B" or "THRU".
    pub fn assign_label(&self, d: usize) -> &'static str {
        match self.decks[d].assign {
            0 => ["A", "B"][d],
            3 => "THRU",
            1 => "A",
            _ => "B",
        }
    }

    /// The filter knob's effect: the filter, then each Color FX.
    pub fn cycle_color(&mut self, d: usize, cx: &mut Context<Self>) {
        let kinds: Vec<u8> = std::iter::once(0).chain(FxKind::COLOR.iter().map(|k| *k as u8)).collect();
        let i = kinds.iter().position(|k| *k == self.decks[d].color).map_or(0, |i| (i + 1) % kinds.len());
        self.decks[d].color = kinds[i];
        self.mixer();
        self.apply_mixer(d);
        cx.notify();
    }

    pub fn color_label(&self, d: usize) -> &'static str {
        FxKind::from_u8(self.decks[d].color).map_or("FILTER", FxKind::name)
    }

    pub fn cycle_mix_fx(&mut self, cx: &mut Context<Self>) {
        let kinds: Vec<u8> = std::iter::once(0).chain(FxKind::MIX.iter().map(|k| *k as u8)).collect();
        let i = kinds.iter().position(|k| *k == self.mix_fx).map_or(0, |i| (i + 1) % kinds.len());
        self.mix_fx = kinds[i];
        self.mixer();
        self.apply_master();
        cx.notify();
    }

    pub fn mix_fx_label(&self) -> &'static str {
        FxKind::from_u8(self.mix_fx).map_or("NONE", FxKind::name)
    }

    pub fn cycle_master_fx(&mut self, cx: &mut Context<Self>) {
        let kinds: Vec<u8> = std::iter::once(0).chain(FxKind::ALL.iter().map(|k| *k as u8)).collect();
        let i = kinds.iter().position(|k| *k == self.master_fx).map_or(0, |i| (i + 1) % kinds.len());
        self.master_fx = kinds[i];
        self.mixer();
        self.apply_master();
        cx.notify();
    }

    pub fn master_fx_label(&self) -> &'static str {
        FxKind::from_u8(self.master_fx).map_or("NONE", FxKind::name)
    }

    pub fn set_master_fx_amount(&mut self, v: f32, cx: &mut Context<Self>) {
        self.master_fx_amount = v.clamp(0., 1.);
        self.apply_master();
        cx.notify();
    }

    pub fn toggle_hamster(&mut self, cx: &mut Context<Self>) {
        self.hamster = !self.hamster;
        self.mixer();
        self.apply_master();
        cx.notify();
    }

    pub fn toggle_key(&mut self, d: usize, cx: &mut Context<Self>) {
        self.decks[d].camelot = !self.decks[d].camelot;
        cx.notify();
    }
}

/// "1/4", "2", as loop sizes read.
pub fn beats_label(b: f64) -> String {
    if b >= 1. {
        format!("{}", b as u32)
    } else {
        format!("1/{}", (1. / b).round() as u32)
    }
}

#[cfg(test)]
mod tests {
    use super::beats_label;

    #[test]
    fn loop_sizes_read_as_fractions_of_a_beat() {
        assert_eq!(beats_label(0.25), "1/4");
        assert_eq!(beats_label(1. / 32.), "1/32");
        assert_eq!(beats_label(16.), "16");
    }
}
