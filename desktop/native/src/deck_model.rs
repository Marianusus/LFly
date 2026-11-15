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
use lfly_audio::decks::{Curve, FxKind, Mixer, TrackBuf, FX_SLOTS};
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
    Roll,
    Loop,
    Jump,
    /// Brake, spinback, censor, reverse, tempo ×2 and ÷2, tap, eject.
    Perform,
}

impl PadMode {
    pub const ALL: [PadMode; 5] = [PadMode::HotCue, PadMode::Roll, PadMode::Loop, PadMode::Jump, PadMode::Perform];

    pub fn label(self) -> &'static str {
        match self {
            PadMode::HotCue => "HOT CUE",
            PadMode::Roll => "ROLL",
            PadMode::Loop => "LOOP",
            PadMode::Jump => "JUMP",
            PadMode::Perform => "PERFORM",
        }
    }
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
    pub remain: bool,
    pub camelot: bool,
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
            remain: false,
            camelot: false,
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
    analyses: HashMap<String, Analysis>,
    player_playing: bool,
}

struct DecksGlobal(Entity<DjDecks>);
impl Global for DecksGlobal {}

fn test_run() -> bool {
    std::env::var_os("LFLY_MUTE").is_some() || std::env::var_os("LFLY_AUTOPLAY").is_some() || std::env::var_os("LFLY_ROUTE").is_some()
}

/// A song's cue points as saved: seconds or nothing, eight of them.
fn saved_cues(id: &str) -> [Option<f64>; HOT_CUES] {
    let mut out = [None; HOT_CUES];
    if let Some(list) = lfly_core::store::get(STORE_CUES).ok().flatten().and_then(|v| v.get(id).cloned()).and_then(|v| v.as_array().cloned()) {
        for (slot, v) in out.iter_mut().zip(list) {
            *slot = v.as_f64();
        }
    }
    out
}

fn save_cues(id: &str, cues: &[Option<f64>; HOT_CUES]) {
    if test_run() {
        return;
    }
    let mut all = lfly_core::store::get(STORE_CUES).ok().flatten().unwrap_or_else(|| serde_json::json!({}));
    if let Some(map) = all.as_object_mut() {
        if cues.iter().all(Option::is_none) {
            map.remove(id);
        } else {
            map.insert(id.to_string(), serde_json::json!(cues.to_vec()));
        }
    }
    if let Err(e) = lfly_core::store::set(STORE_CUES, &all) {
        log::warn!("console cues not saved: {e}");
    }
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
                analyses: HashMap::new(),
                player_playing: false,
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
                        a if a.starts_with("roll") => me.pad_down_in(d, PadMode::Roll, pad("roll"), cx),
                        a if a.starts_with("hot") => me.pad_down_in(d, PadMode::HotCue, pad("hot"), cx),
                        a if a.starts_with("jump") => me.pad_down_in(d, PadMode::Jump, pad("jump"), cx),
                        _ => {}
                    }
                    let phase = |me: &DjDecks, d: usize| me.decks[d].grid.map(|g| g.beat_at(me.position(d)));
                    log::info!(
                        "console step {action} on {d}: A {:.3} s {} beat {:?} | B {:.3} s {} beat {:?} | tempo {:.4} {:.4} | loop {:?} {:?} | vu {:.2} {:.2}",
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
    }

    fn apply_master(&self) {
        let Some(m) = &self.mixer else { return };
        m.state.xfader.set(self.xfader);
        m.state.curve.store(self.curve as u8, std::sync::atomic::Ordering::Relaxed);
        m.state.master.set(lfly_audio::decks::eq_gain(self.master));
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

    /// The beat in the bar (0..4) of the deck now, once it has a grid.
    pub fn bar_beat(&self, d: usize) -> Option<u32> {
        self.decks[d].grid.map(|g| g.in_bar(self.position(d)).0)
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
        let deck = &mut self.decks[d];
        let keep = (deck.range, deck.vinyl, deck.pads, deck.remain, deck.camelot, deck.fx, deck.fx_amount, deck.knobs, deck.volume);
        let seq = deck.seq + 1;
        *deck = Deck::new();
        (deck.range, deck.vinyl, deck.pads, deck.remain, deck.camelot, deck.fx, deck.fx_amount, deck.knobs, deck.volume) = keep;
        deck.seq = seq;
        deck.hot = saved_cues(&track.id);
        deck.track = Some(track.clone());
        deck.status = Status::Loading;
        if let Some(a) = self.analyses.get(&track.id) {
            deck.grid = a.grid;
            deck.key = a.key;
        }
        self.apply_mixer(d);
        cx.notify();

        let player = self.core.player.clone();
        let (tx, rx) = tokio::sync::oneshot::channel();
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
                cx.notify();
            });
        })
        .detach();
    }

    pub fn eject(&mut self, d: usize, cx: &mut Context<Self>) {
        if let Some(m) = &self.mixer {
            m.eject(d);
        }
        let deck = &mut self.decks[d];
        let keep = (deck.range, deck.vinyl, deck.pads, deck.remain, deck.camelot, deck.fx, deck.fx_amount, deck.knobs, deck.volume, deck.seq + 1);
        *deck = Deck::new();
        (deck.range, deck.vinyl, deck.pads, deck.remain, deck.camelot, deck.fx, deck.fx_amount, deck.knobs, deck.volume, deck.seq) = keep;
        cx.notify();
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
        }
        m.play(d, on);
        cx.notify();
    }

    pub fn play_pause(&mut self, d: usize, cx: &mut Context<Self>) {
        let on = !self.is_playing(d) || self.decks[d].cue_preview;
        if self.decks[d].cue_preview {
            // PLAY while CUE is held: it goes on playing when CUE is let go.
            self.decks[d].cue_preview = false;
            cx.notify();
            return;
        }
        self.play(d, on, cx);
    }

    fn pause_player(&mut self, cx: &mut Context<Self>) {
        let m = self.model.read(cx);
        if m.playing() && m.remote.is_none() {
            m.command(protocol::CMD_PAUSE, 0);
        }
    }

    /// CUE pressed: playing, back to the cue and stopped; stopped elsewhere, the cue moves here;
    /// stopped on it, it plays while held.
    pub fn cue_down(&mut self, d: usize, cx: &mut Context<Self>) {
        if self.decks[d].buf.is_none() {
            return;
        }
        let Some(m) = self.mixer() else { return };
        let rate = self.decks[d].rate();
        if self.is_playing(d) {
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
        if !self.decks[d].cue_preview {
            return;
        }
        self.decks[d].cue_preview = false;
        if let Some(m) = &self.mixer {
            m.play(d, false);
            m.jump(d, self.decks[d].cue * self.decks[d].rate());
        }
        cx.notify();
    }

    /// Back to the start, stopped (STOP).
    pub fn stop(&mut self, d: usize, cx: &mut Context<Self>) {
        if let Some(m) = &self.mixer {
            m.play(d, false);
            m.jump(d, 0.);
        }
        cx.notify();
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
        self.decks[d].fader = fader.clamp(-1., 1.);
        self.apply_mixer(d);
        cx.notify();
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
            self.seek(d, t, cx);
            return;
        } else {
            let t = self.snap(d, self.position(d));
            self.decks[d].hot[i] = Some(t);
        }
        if let Some(id) = self.decks[d].track.as_ref().map(|t| t.id.clone()) {
            save_cues(&id, &self.decks[d].hot);
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
        let start = match self.decks[d].grid {
            Some(g) if self.quantize => {
                // On the grid of the loop's own size when it is under a beat, else on the beat.
                let unit = beats.min(1.);
                g.time_of((g.beat_at(here) / unit).floor() * unit)
            }
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

    /// A pad pressed ([alt]: the right button, which deletes a hot cue).
    pub fn pad_down(&mut self, d: usize, i: usize, alt: bool, cx: &mut Context<Self>) {
        match self.decks[d].pads {
            PadMode::HotCue => self.hot_cue(d, i, alt, cx),
            PadMode::Roll => {
                if self.decks[d].buf.is_none() {
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
                let rate = self.decks[d].rate();
                if let Some(m) = self.mixer() {
                    m.roll(d, Some((start * rate, (start + beats * beat) * rate)));
                }
                self.decks[d].pad_held = Some((PadMode::Roll, i));
                cx.notify();
            }
            PadMode::Loop => {
                let beats = LOOP_PADS[i];
                let same = self.decks[d].looped.is_some_and(|(a, b)| ((b - a) / self.decks[d].beat() - beats).abs() < 1e-6);
                if same {
                    self.engage(d, None, cx);
                } else {
                    self.decks[d].loop_beats = beats;
                    self.engage(d, None, cx);
                    self.loop_of(d, beats, cx);
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
                6 => self.tap(d, cx),
                _ => self.eject(d, cx),
            },
        }
    }

    pub fn pad_up(&mut self, d: usize, cx: &mut Context<Self>) {
        let Some((mode, i)) = self.decks[d].pad_held.take() else { return };
        match mode {
            PadMode::Roll => {
                if let Some(m) = &self.mixer {
                    m.roll(d, None);
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
        match deck.pads {
            PadMode::HotCue => match deck.hot[i] {
                Some(t) => (crate::console::tenths(t * 1000.), true),
                None => (format!("{}", i + 1), false),
            },
            PadMode::Roll => (beats_label(ROLL_SIZES[i]), deck.pad_held == Some((PadMode::Roll, i))),
            PadMode::Loop => {
                let on = deck.looped.is_some_and(|(a, b)| ((b - a) / deck.beat() - LOOP_PADS[i]).abs() < 1e-6);
                (beats_label(LOOP_PADS[i]), on)
            }
            PadMode::Jump => (format!("{}{}{}", if JUMPS[i] < 0. { "◀ " } else { "" }, JUMPS[i].abs(), if JUMPS[i] > 0. { " ▶" } else { "" }), false),
            PadMode::Perform => {
                let names = ["BRAKE", "SPINBACK", "CENSOR", "REVERSE", "BPM ÷2", "BPM ×2", "TAP", "EJECT"];
                let on = match i {
                    2 => deck.censor,
                    3 => deck.reverse,
                    _ => false,
                };
                (names[i].to_string(), on)
            }
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
        self.decks[d].remain = !self.decks[d].remain;
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
