//! The engine thread (opens, decodes, resamples and feeds the ring) and the clock thread (turns
//! the frames the device has played into Position / TrackChanged / Ended / Buffering events).
//!
//! Every frame pushed into the ring has an index (`written`); the callback counts the frames it
//! takes out (`consumed`). The timeline maps frame indexes to songs and positions, so events
//! follow what is actually heard, including a gapless switch that happens 2 s after the decoder
//! moved on to the next song.

use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::mpsc::{Receiver, RecvTimeoutError, Sender, TryRecvError};
use std::sync::{Arc, Mutex, MutexGuard};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use tokio::sync::broadcast;

use crate::automix::{MixPlan, Mixer, TempoReturn};
use crate::cache::DiskCache;
use crate::decode::{DecodeError, Resample, TrackDecoder};
use crate::http::{prefetch, ReaderCtl};
use crate::output::{self, OutShared, Output};
use crate::stretch::Stretch;
use crate::{PlayerEvent, Source};

/// How much of the next song is fetched ahead.
const PRELOAD_BYTES: u64 = 2 * 1024 * 1024;

pub(crate) enum Cmd {
    Load { source: Source, start_ms: u64, autoplay: bool },
    Seek(u64),
    Stop,
    Preload(Option<Source>),
    /// The preload thread opened the next song (and moved it to `at` ms, an Automix entry).
    PreloadReady { gen: u64, track: Box<Result<TrackDecoder, DecodeError>>, at: u64 },
    /// An Automix planned for the coming change (None: none).
    Automix(Option<Box<MixPlan>>),
    SetDevice(Option<String>),
    /// Crossfade length between songs, in ms (0: gapless, no fade).
    Crossfade(u64),
    /// Playback speed, the pitch kept (1: as recorded).
    Speed(f64),
    Shutdown,
}

/// A crossfade under way: the ending song fades out while the next one fades in.
struct Mix {
    /// Output frames the fade lasts, and how many were mixed so far.
    total: u64,
    done: u64,
}

pub(crate) struct Segment {
    pub start_frame: u64,
    pub start_ms: u64,
    pub rate: u32,
    pub key: String,
    pub duration_ms: Option<u64>,
    /// Song time per output time (the playback speed) while this segment plays.
    pub speed: f64,
}

#[derive(Default)]
pub(crate) struct Timeline {
    /// The song being heard first, then (after a gapless switch was queued) the next one.
    pub segments: VecDeque<Segment>,
    /// Frame index where the audio runs out, once the last song decoded to its end.
    pub end_frame: Option<u64>,
    pub ended: bool,
    /// The song failed: no Ended, no Buffering until the next load.
    pub failed: bool,
}

impl Timeline {
    fn reset(&mut self) {
        *self = Timeline::default();
    }

    pub fn position_ms(&self, consumed: u64) -> Option<u64> {
        let s = self.segments.front()?;
        let heard = consumed.saturating_sub(s.start_frame) as f64 * s.speed;
        let ms = s.start_ms + (heard * 1000. / s.rate.max(1) as f64) as u64;
        Some(match s.duration_ms {
            Some(d) => ms.min(d),
            None => ms,
        })
    }
}

pub(crate) struct Shared {
    pub out: Arc<OutShared>,
    pub cmd_gen: Arc<AtomicU64>,
    pub timeline: Mutex<Timeline>,
    pub events: broadcast::Sender<PlayerEvent>,
    pub loaded: AtomicBool,
    pub ended: AtomicBool,
    pub shutdown: AtomicBool,
}

impl Shared {
    pub fn new(events: broadcast::Sender<PlayerEvent>) -> Self {
        Shared {
            out: Arc::new(OutShared::new()),
            cmd_gen: Arc::new(AtomicU64::new(0)),
            timeline: Mutex::new(Timeline::default()),
            events,
            loaded: AtomicBool::new(false),
            ended: AtomicBool::new(false),
            shutdown: AtomicBool::new(false),
        }
    }

    pub fn emit(&self, ev: PlayerEvent) {
        let _ = self.events.send(ev);
    }

    pub fn timeline(&self) -> MutexGuard<'_, Timeline> {
        self.timeline.lock().unwrap_or_else(|e| e.into_inner())
    }

    pub fn position_ms(&self) -> Option<u64> {
        let consumed = self.out.consumed.load(Ordering::Acquire);
        self.timeline().position_ms(consumed)
    }
}

pub(crate) fn spawn(
    sh: Arc<Shared>,
    cache: Arc<DiskCache>,
    tx: Sender<Cmd>,
    rx: Receiver<Cmd>,
) -> std::io::Result<Vec<JoinHandle<()>>> {
    let engine = Engine {
        sh: sh.clone(),
        cache,
        tx,
        rx,
        out: None,
        device: None,
        last_output_try: None,
        written: 0,
        pending: Vec::new(),
        pend_pos: 0,
        decoded: Vec::new(),
        cur: None,
        next: None,
        queued_preload: None,
        preload_gen: 0,
        preload_ctl: None,
        crossfade_ms: 0,
        mix: None,
        mix_buf: Vec::new(),
        speed: 1.,
        stretch: Stretch::new(48_000),
        stretch_rate: 48_000,
        stage: Vec::new(),
        plan: None,
        automix: None,
        automix_in_ms: 0.,
    };
    let e = std::thread::Builder::new().name("lfly-audio-engine".into()).spawn(move || engine.run())?;
    let c = std::thread::Builder::new().name("lfly-audio-clock".into()).spawn(move || clock(sh))?;
    Ok(vec![e, c])
}

struct Track {
    dec: TrackDecoder,
    rs: Resample,
    key: String,
    eof: bool,
    failed: bool,
    /// Gapless next song: decoding began, its first frame is at `start_frame`.
    started: bool,
    start_frame: u64,
    /// Decoded position, to resync after an interrupted read.
    base_ms: u64,
    decoded_frames: u64,
    resync: bool,
    /// Frames the resampler gave since `base_ms` (output rate): where the song is, to the sample.
    out_frames: u64,
    /// After an Automix: the song's own tempo stretch, easing back to 1.
    deck: Option<Deck>,
}

/// The incoming deck of an Automix, played on after the mix at its tempo until it is back to 1.
struct Deck {
    stretch: Stretch,
    ret: TempoReturn,
    /// The song's time of the next output frame, in ms.
    song_ms: f64,
}

impl Track {
    fn new(dec: TrackDecoder, out_rate: u32) -> Self {
        let rs = Resample::new(dec.sample_rate(), out_rate);
        Track {
            key: dec.source().key(),
            dec,
            rs,
            eof: false,
            failed: false,
            started: false,
            start_frame: 0,
            base_ms: 0,
            decoded_frames: 0,
            resync: false,
            out_frames: 0,
            deck: None,
        }
    }

    fn seek(&mut self, ms: u64) -> Result<u64, DecodeError> {
        let at = self.dec.seek(ms)?;
        self.rs = Resample::new(self.dec.sample_rate(), self.rs.rates().1);
        self.eof = false;
        self.failed = false;
        self.resync = false;
        self.base_ms = at;
        self.decoded_frames = 0;
        self.out_frames = 0;
        Ok(at)
    }

    fn set_out_rate(&mut self, rate: u32) {
        if self.rs.rates().1 != rate {
            self.rs = Resample::new(self.dec.sample_rate(), rate);
        }
    }

    fn decoded_ms(&self) -> u64 {
        self.base_ms + self.decoded_frames * 1000 / self.dec.sample_rate().max(1) as u64
    }
}

struct Engine {
    sh: Arc<Shared>,
    cache: Arc<DiskCache>,
    tx: Sender<Cmd>,
    rx: Receiver<Cmd>,
    out: Option<Output>,
    device: Option<String>,
    last_output_try: Option<Instant>,
    /// Frames pushed into the ring so far (same counter space as `OutShared::consumed`).
    written: u64,
    /// Resampled stereo samples waiting for room in the ring.
    pending: Vec<f32>,
    pend_pos: usize,
    decoded: Vec<f32>,
    cur: Option<Track>,
    next: Option<Track>,
    queued_preload: Option<Source>,
    preload_gen: u64,
    preload_ctl: Option<Arc<ReaderCtl>>,
    crossfade_ms: u64,
    mix: Option<Mix>,
    /// The next song's decoded samples not mixed yet.
    mix_buf: Vec<f32>,
    /// Playback speed, and what applies it to everything on its way to the ring.
    speed: f64,
    stretch: Stretch,
    stretch_rate: u32,
    /// Resampled samples on their way into the stretcher.
    stage: Vec<f32>,
    /// The Automix planned for the coming change, and the one under way.
    plan: Option<MixPlan>,
    automix: Option<Box<Mixer>>,
    /// The incoming song's time (ms) of the next frame the mix gives.
    automix_in_ms: f64,
}

impl Engine {
    fn run(mut self) {
        loop {
            let gen = self.sh.cmd_gen.load(Ordering::SeqCst);
            let wait = self.idle_wait();
            let first = if wait.is_zero() {
                match self.rx.try_recv() {
                    Ok(c) => Some(c),
                    Err(TryRecvError::Empty) => None,
                    Err(TryRecvError::Disconnected) => return,
                }
            } else {
                match self.rx.recv_timeout(wait) {
                    Ok(c) => Some(c),
                    Err(RecvTimeoutError::Timeout) => None,
                    Err(RecvTimeoutError::Disconnected) => return,
                }
            };
            if let Some(cmd) = first {
                if !self.handle(cmd) {
                    return;
                }
                while let Ok(cmd) = self.rx.try_recv() {
                    if !self.handle(cmd) {
                        return;
                    }
                }
            }
            self.rearm(gen);
            if self.sh.shutdown.load(Ordering::SeqCst) {
                return;
            }
            self.check_output();
            self.promote();
            self.pump();
        }
    }

    /// How long to wait for a command before doing more work.
    fn idle_wait(&mut self) -> Duration {
        if self.out.is_none() {
            return Duration::from_millis(20);
        }
        if self.pend_pos < self.pending.len() {
            let room = self.out.as_ref().map(|o| o.producer.slots()).unwrap_or(0);
            return if room >= 2 { Duration::ZERO } else { Duration::from_millis(5) };
        }
        if self.decoding_target().is_some() {
            Duration::ZERO
        } else {
            Duration::from_millis(20)
        }
    }

    fn rearm(&self, gen: u64) {
        for t in self.cur.iter().chain(self.next.iter()) {
            t.dec.ctl().arm(&self.sh.cmd_gen, gen);
        }
    }

    /// Returns false on shutdown.
    fn handle(&mut self, cmd: Cmd) -> bool {
        self.promote();
        match cmd {
            Cmd::Load { source, start_ms, autoplay } => self.load(source, start_ms, autoplay),
            Cmd::Seek(ms) => self.seek(ms),
            Cmd::Stop => self.stop(),
            Cmd::Preload(Some(source)) => self.preload(source),
            Cmd::Preload(None) => {
                self.cancel_preload();
                if !self.next.as_ref().is_some_and(|n| n.started) {
                    self.next = None;
                }
            }
            Cmd::PreloadReady { gen, track, at } => {
                if gen == self.preload_gen {
                    self.preload_ctl = None;
                    match *track {
                        Ok(dec) => {
                            let rate = self.out.as_ref().map(|o| o.rate).unwrap_or(48_000);
                            log::info!("preloaded {}{}", dec.source().key(), if at > 0 { format!(" from {at} ms") } else { String::new() });
                            let mut t = Track::new(dec, rate);
                            t.base_ms = at;
                            self.next = Some(t);
                        }
                        Err(e) => log::warn!("preload failed: {e}"),
                    }
                }
            }
            Cmd::Automix(plan) => self.arm(plan.map(|p| *p)),
            Cmd::Crossfade(ms) => self.crossfade_ms = ms.min(12_000),
            Cmd::Speed(speed) => self.set_speed(speed),
            Cmd::SetDevice(id) => {
                self.device = id;
                self.reopen_output();
            }
            Cmd::Shutdown => {
                self.cancel_preload();
                return false;
            }
        }
        true
    }

    fn ensure_output(&mut self) -> bool {
        self.open_output(true)
    }

    fn open_output(&mut self, report: bool) -> bool {
        if self.out.is_some() {
            return true;
        }
        match output::open(self.device.as_deref(), self.sh.out.clone()) {
            Ok(out) => {
                self.written = self.sh.out.consumed.load(Ordering::Acquire);
                self.sh.emit(PlayerEvent::DeviceChanged { id: out.device_id.clone(), name: out.device_name.clone() });
                self.out = Some(out);
                self.match_stretch_rate();
                true
            }
            Err(e) => {
                log::warn!("audio output: {e}");
                if report {
                    self.sh.emit(PlayerEvent::Error { message: e });
                }
                false
            }
        }
    }

    /// Fades out and empties the ring; afterwards `written == consumed`.
    fn flush(&mut self) {
        self.pending.clear();
        self.pend_pos = 0;
        self.stretch.reset();
        self.mix = None;
        self.mix_buf.clear();
        self.automix = None;
        if self.out.is_none() {
            return;
        }
        let o = self.sh.out.clone();
        let req = o.flush_req.fetch_add(1, Ordering::AcqRel) + 1;
        let deadline = Instant::now() + Duration::from_millis(500);
        while o.flush_ack.load(Ordering::Acquire) < req {
            if Instant::now() > deadline {
                // The device stopped calling back: start a fresh stream (and ring).
                log::warn!("output did not answer a flush, reopening it");
                o.flush_ack.store(req, Ordering::Release);
                self.out = None;
                o.failed.store(false, Ordering::SeqCst);
                self.ensure_output();
                break;
            }
            std::thread::sleep(Duration::from_millis(1));
        }
        self.written = o.consumed.load(Ordering::Acquire);
    }

    fn load(&mut self, source: Source, start_ms: u64, autoplay: bool) {
        self.plan = None;
        self.flush();
        self.cur = None;
        self.sh.timeline().reset();
        self.sh.ended.store(false, Ordering::SeqCst);
        self.sh.loaded.store(false, Ordering::SeqCst);
        self.sh.emit(PlayerEvent::Loading);
        self.sh.out.playing.store(autoplay, Ordering::SeqCst);
        if !self.ensure_output() {
            return;
        }
        let out_rate = self.out.as_ref().map(|o| o.rate).unwrap_or(48_000);

        let reuse = self.next.as_ref().is_some_and(|n| n.dec.source() == &source);
        let mut track = if reuse {
            let mut t = self.next.take().expect("checked");
            t.dec.ctl().arm(&self.sh.cmd_gen, self.sh.cmd_gen.load(Ordering::SeqCst));
            t.set_out_rate(out_rate);
            t.deck = None;
            // Started in a mix, or preloaded from an Automix entry: back to the top.
            if (t.started || t.base_ms != 0) && start_ms == 0 {
                if let Err(e) = t.seek(0) {
                    return self.fail_load(e);
                }
            }
            t.started = false;
            t
        } else {
            let ctl = ReaderCtl::following(self.sh.cmd_gen.clone());
            match TrackDecoder::open(&source, &self.cache, ctl) {
                Ok(dec) => Track::new(dec, out_rate),
                Err(e) => return self.fail_load(e),
            }
        };
        let mut at = 0;
        if start_ms > 0 {
            match track.seek(start_ms) {
                Ok(ms) => at = ms,
                Err(e) => return self.fail_load(e),
            }
        }
        if self.queued_preload.as_ref() == Some(&source) {
            self.queued_preload = None;
        }
        let duration = track.dec.duration_ms();
        {
            let mut tl = self.sh.timeline();
            tl.segments.push_back(Segment {
                start_frame: self.written,
                start_ms: at,
                rate: out_rate,
                key: track.key.clone(),
                duration_ms: duration,
                speed: self.speed,
            });
        }
        self.cur = Some(track);
        self.sh.loaded.store(true, Ordering::SeqCst);
        if let Some(d) = duration {
            self.sh.emit(PlayerEvent::Duration { ms: d });
        }
        self.sh.emit(PlayerEvent::Position { ms: at });
        self.sh.emit(if autoplay { PlayerEvent::Playing } else { PlayerEvent::Paused });
    }

    fn fail_load(&mut self, e: DecodeError) {
        match e {
            DecodeError::Aborted => {}
            DecodeError::Failed(message) => {
                self.sh.out.playing.store(false, Ordering::SeqCst);
                self.sh.emit(PlayerEvent::Error { message });
            }
        }
    }

    fn seek(&mut self, ms: u64) {
        if self.cur.is_none() {
            return;
        }
        // A seek ends any Automix: the plan's places are gone, the song plays at its own tempo.
        let mixing = self.mix.is_some() || self.automix.is_some();
        self.plan = None;
        if let Some(c) = self.cur.as_mut() {
            c.deck = None;
        }
        // Mid-crossfade, once the next song is heard, the seek is in the next song.
        if mixing {
            let consumed = self.sh.out.consumed.load(Ordering::Acquire);
            if self.next.as_ref().is_some_and(|n| n.started && consumed >= n.start_frame) {
                self.cur = self.next.take();
                if let Some(c) = self.cur.as_mut() {
                    c.started = false;
                    c.deck = None;
                }
            }
        }
        self.flush();
        if let Some(n) = self.next.as_mut() {
            if n.started || n.base_ms != 0 {
                n.started = false;
                n.deck = None;
                if n.seek(0).is_err() {
                    self.next = None;
                }
            }
        }
        let cur = self.cur.as_mut().expect("checked");
        let at = match cur.seek(ms) {
            Ok(at) => at,
            Err(DecodeError::Aborted) => {
                cur.resync = true;
                cur.base_ms = ms;
                cur.decoded_frames = 0;
                ms
            }
            Err(DecodeError::Failed(message)) => {
                cur.failed = true;
                cur.eof = true;
                self.sh.timeline().failed = true;
                self.sh.emit(PlayerEvent::Error { message });
                return;
            }
        };
        let rate = self.out.as_ref().map(|o| o.rate).unwrap_or(48_000);
        {
            let mut tl = self.sh.timeline();
            let seg = Segment {
                start_frame: self.written,
                start_ms: at,
                rate,
                key: cur.key.clone(),
                duration_ms: cur.dec.duration_ms(),
                speed: self.speed,
            };
            tl.reset();
            tl.segments.push_back(seg);
        }
        self.sh.ended.store(false, Ordering::SeqCst);
        self.sh.emit(PlayerEvent::Position { ms: at });
    }

    fn stop(&mut self) {
        self.plan = None;
        self.flush();
        self.cur = None;
        if self.next.as_ref().is_some_and(|n| n.started) {
            self.next = None;
        }
        self.sh.timeline().reset();
        self.sh.loaded.store(false, Ordering::SeqCst);
        self.sh.ended.store(false, Ordering::SeqCst);
        if self.sh.out.playing.swap(false, Ordering::SeqCst) {
            self.sh.emit(PlayerEvent::Paused);
        }
    }

    fn preload(&mut self, source: Source) {
        // The Automix plan's incoming song is opened at its entry.
        let entry = self.plan.as_ref().filter(|p| p.incoming_key == source.key()).map_or(0, |p| p.entry_ms);
        if self.cur.as_ref().is_some_and(|c| c.dec.source() == &source)
            || self.next.as_ref().is_some_and(|n| n.dec.source() == &source && (n.started || n.base_ms == entry))
        {
            return;
        }
        if self.next.as_ref().is_some_and(|n| n.started) {
            // The next song is already playing out of the ring: preload after it takes over.
            self.queued_preload = Some(source);
            return;
        }
        self.cancel_preload();
        self.next = None;
        self.preload_gen += 1;
        let gen = self.preload_gen;
        let ctl = ReaderCtl::dormant(self.sh.cmd_gen.clone());
        self.preload_ctl = Some(ctl.clone());
        let cache = self.cache.clone();
        let tx = self.tx.clone();
        let spawned = std::thread::Builder::new().name("lfly-audio-preload".into()).spawn(move || {
            if let Source::Http { url, cache_key, headers } = &source {
                if let Err(e) = prefetch(url, cache_key, headers, cache.clone(), PRELOAD_BYTES, ctl.clone()) {
                    log::warn!("prefetch of {cache_key}: {e}");
                }
            }
            if ctl.aborted() {
                return;
            }
            let mut opened = TrackDecoder::open(&source, &cache, ctl.clone());
            // An entry seek can take seconds (a FLAC without a seek table): here, not on the engine.
            let mut at = 0;
            if entry > 0 {
                if let Ok(dec) = opened.as_mut() {
                    match dec.seek(entry) {
                        Ok(ms) => at = ms,
                        Err(e) => opened = Err(e),
                    }
                }
            }
            if ctl.aborted() {
                return;
            }
            let _ = tx.send(Cmd::PreloadReady { gen, track: Box::new(opened), at });
        });
        if let Err(e) = spawned {
            log::warn!("preload thread: {e}");
        }
    }

    fn cancel_preload(&mut self) {
        if let Some(ctl) = self.preload_ctl.take() {
            ctl.cancel();
        }
        self.preload_gen += 1;
    }

    /// The gapless next song became the one heard.
    fn promote(&mut self) {
        let consumed = self.sh.out.consumed.load(Ordering::Acquire);
        // Not while a crossfade or an Automix still decodes both songs: the mix promotes at its end.
        if self.mix.is_none() && self.automix.is_none() && self.next.as_ref().is_some_and(|n| n.started && consumed >= n.start_frame) {
            self.cur = self.next.take();
            if let Some(c) = self.cur.as_mut() {
                c.started = false;
            }
            if let Some(src) = self.queued_preload.take() {
                self.preload(src);
            }
        }
    }

    fn check_output(&mut self) {
        if self.out.is_some() && self.sh.out.failed.load(Ordering::SeqCst) {
            log::warn!("output device failed, reopening");
            self.reopen_output();
        } else if self.out.is_none() && self.cur.is_some() {
            let due = self.last_output_try.is_none_or(|t| t.elapsed() > Duration::from_secs(1));
            if due {
                self.reopen_output();
            }
        }
    }

    /// Opens the chosen device (or the default) again and continues where the ear was.
    fn reopen_output(&mut self) {
        self.last_output_try = Some(Instant::now());
        let position = self.sh.position_ms();
        self.pending.clear();
        self.pend_pos = 0;
        self.out = None;
        self.sh.out.failed.store(false, Ordering::SeqCst);
        // Nothing from the old ring will play: acknowledge any flush and drop it.
        let req = self.sh.out.flush_req.load(Ordering::Acquire);
        self.sh.out.flush_ack.store(req, Ordering::Release);
        if self.cur.is_none() && self.next.is_none() {
            // Opened lazily at the next load.
            return;
        }
        if !self.open_output(false) {
            return;
        }
        let rate = self.out.as_ref().map(|o| o.rate).unwrap_or(48_000);
        for t in self.cur.iter_mut().chain(self.next.iter_mut()) {
            t.set_out_rate(rate);
        }
        if self.cur.is_some() {
            let ended = self.sh.timeline().ended;
            if !ended {
                self.seek(position.unwrap_or(0));
            }
        }
    }

    /// A new speed: heard at once (the ring is emptied and the song goes on from where the ear
    /// is, as a seek does).
    fn set_speed(&mut self, speed: f64) {
        let speed = speed.clamp(0.25, 4.);
        if (speed - self.speed).abs() < 1e-6 {
            return;
        }
        let position = self.sh.position_ms();
        self.speed = speed;
        self.stretch.set_speed(speed);
        if self.cur.is_some() && !self.sh.timeline().ended {
            self.seek(position.unwrap_or(0));
        }
    }

    /// The stretcher's frame follows the output's rate.
    fn match_stretch_rate(&mut self) {
        let rate = self.out.as_ref().map(|o| o.rate).unwrap_or(48_000);
        if rate != self.stretch_rate {
            self.stretch = Stretch::new(rate);
            self.stretch.set_speed(self.speed);
            self.stretch_rate = rate;
        }
    }

    /// Which song to decode now: the current one, or the preloaded one once the current is done.
    fn decoding_target(&self) -> Option<bool> {
        let cur = self.cur.as_ref()?;
        if !cur.eof {
            return Some(false);
        }
        if cur.failed {
            return None;
        }
        let next = self.next.as_ref()?;
        if next.eof || next.failed || (!next.started && self.sh.timeline().ended) {
            return None;
        }
        Some(true)
    }

    fn pump(&mut self) {
        let Some(out) = self.out.as_mut() else { return };
        let out_rate = out.rate;
        if self.pend_pos < self.pending.len() {
            let room = out.producer.slots() & !1;
            let n = room.min(self.pending.len() - self.pend_pos);
            if n > 0 {
                if let Ok(chunk) = out.producer.write_chunk_uninit(n) {
                    let src = &self.pending[self.pend_pos..self.pend_pos + n];
                    chunk.fill_from_iter(src.iter().copied());
                }
                self.pend_pos += n;
                self.written += (n / 2) as u64;
            }
            if self.pend_pos < self.pending.len() {
                return;
            }
        }
        self.pending.clear();
        self.pend_pos = 0;
        if self.automix.is_some() {
            self.pump_automix(out_rate);
            return;
        }
        let exit_frame = self.armed_exit(out_rate);
        if self.mix.is_none() && exit_frame.is_none() && self.should_start_mix() {
            self.start_mix(out_rate);
        }
        if self.mix.is_some() {
            self.pump_mix();
            return;
        }
        let Some(use_next) = self.decoding_target() else { return };
        if use_next && !self.next.as_ref().is_some_and(|n| n.started) {
            let next = self.next.as_mut().expect("target");
            next.started = true;
            // After what the stretcher still holds of the song before.
            next.start_frame = self.written + self.stretch.latency_frames();
            let mut tl = self.sh.timeline();
            tl.end_frame = None;
            tl.segments.push_back(Segment {
                start_frame: next.start_frame,
                start_ms: next.decoded_ms(),
                rate: out_rate,
                key: next.key.clone(),
                duration_ms: next.dec.duration_ms(),
                speed: self.speed,
            });
        }
        let has_next = self.next.is_some();
        let track = if use_next { self.next.as_mut() } else { self.cur.as_mut() }.expect("target");
        if track.resync {
            let ms = track.decoded_ms();
            if let Err(e) = track.seek(ms) {
                if e == DecodeError::Aborted {
                    return;
                }
            }
        }
        self.decoded.clear();
        // A decoder that panics on a damaged or unusual packet must not take the player with it:
        // the song fails like any decoding error, and the caller tries again or moves on.
        let decoded = &mut self.decoded;
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| track.dec.decode_next(decoded)))
            .unwrap_or_else(|_| Err(DecodeError::Failed("the decoder crashed on this stream".into())));
        match result {
            Ok(true) => {
                if track.dec.sample_rate() != track.rs.rates().0 {
                    track.rs = Resample::new(track.dec.sample_rate(), track.rs.rates().1);
                }
                track.decoded_frames += (self.decoded.len() / 2) as u64;
                self.stage.clear();
                track.rs.process(&self.decoded, &mut self.stage);
                let produced = (self.stage.len() / 2) as u64;
                let exit_ms = exit_frame.filter(|_| !use_next);
                // The Automix exit falls in this packet: the part before it plays as it is, the
                // rest is the first of the mix. Counted in resampled frames, or (a song that came
                // in through an Automix) on its deck's clock, after the deck.
                let plain_split = exit_ms.filter(|_| track.deck.is_none()).and_then(|ms| {
                    let e = ms.saturating_sub(track.base_ms) * out_rate as u64 / 1000;
                    (track.out_frames + produced > e).then(|| (e.saturating_sub(track.out_frames) * 2) as usize)
                });
                track.out_frames += produced;
                if let Some(at) = plain_split {
                    let rest = self.stage.split_off(at.min(self.stage.len()));
                    self.stretch.process(&self.stage, &mut self.pending);
                    self.start_automix(rest, out_rate);
                    return;
                }
                let deck_before = track.deck.as_ref().map(|d| d.song_ms);
                let deck_change = Self::through_deck(track, &mut self.stage, false, out_rate);
                if let (Some(ms), Some(before), Some(after)) = (exit_ms, deck_before, track.deck.as_ref().map(|d| d.song_ms)) {
                    if ms as f64 <= after && after > before {
                        let frames = self.stage.len() / 2;
                        let k = (((ms as f64 - before) / (after - before)) * frames as f64).round().clamp(0.0, frames as f64) as usize;
                        let rest = self.stage.split_off(k * 2);
                        self.stretch.process(&self.stage, &mut self.pending);
                        self.start_automix(rest, out_rate);
                        return;
                    }
                }
                self.stretch.process(&self.stage, &mut self.pending);
                if let Some((speed, song_ms)) = deck_change {
                    self.deck_segment(speed, song_ms, out_rate);
                }
            }
            Ok(false) => {
                track.eof = true;
                self.stage.clear();
                track.rs.flush(&mut self.stage);
                let _ = Self::through_deck(track, &mut self.stage, true, out_rate);
                self.stretch.process(&self.stage, &mut self.pending);
                let continues = !use_next && has_next;
                if !continues {
                    // The end of the music: what the stretcher still holds plays out too.
                    self.stretch.flush(&mut self.pending);
                    self.sh.timeline().end_frame = Some(self.written + (self.pending.len() / 2) as u64);
                }
            }
            Err(DecodeError::Aborted) => track.resync = true,
            Err(DecodeError::Failed(message)) => {
                log::warn!("playback of {} failed: {message}", track.key);
                track.eof = true;
                track.failed = true;
                self.sh.timeline().failed = true;
                self.sh.emit(PlayerEvent::Error { message });
            }
        }
    }
}

impl Engine {
    /// Near the end of the song, with the next one ready and a crossfade chosen.
    fn should_start_mix(&self) -> bool {
        if self.crossfade_ms == 0 {
            return false;
        }
        let (Some(cur), Some(next)) = (self.cur.as_ref(), self.next.as_ref()) else { return false };
        if cur.eof || cur.failed || cur.resync || next.started || next.failed || next.eof {
            return false;
        }
        let Some(dur) = cur.dec.duration_ms() else { return false };
        // Short songs get a shorter fade: never more than a third of the song.
        let fade = self.crossfade_ms.min(dur / 3);
        fade >= 1_000 && cur.decoded_ms() + fade >= dur
    }

    fn start_mix(&mut self, out_rate: u32) {
        let cur = self.cur.as_ref().expect("checked");
        let left_ms = cur.dec.duration_ms().unwrap_or(0).saturating_sub(cur.decoded_ms()).max(500);
        let next = self.next.as_mut().expect("checked");
        next.started = true;
        next.start_frame = self.written + self.stretch.latency_frames();
        log::info!("crossfade into {} over {left_ms} ms", next.key);
        let mut tl = self.sh.timeline();
        tl.end_frame = None;
        tl.segments.push_back(Segment {
            start_frame: next.start_frame,
            start_ms: next.decoded_ms(),
            rate: out_rate,
            key: next.key.clone(),
            duration_ms: next.dec.duration_ms(),
            speed: self.speed,
        });
        drop(tl);
        self.mix_buf.clear();
        self.mix = Some(Mix { total: left_ms * out_rate as u64 / 1000, done: 0 });
    }

    /// The planned Automix, if the songs are the plan's and the next one waits at its entry:
    /// the exit's frame in the current song (output rate, from its `base_ms`). A plan whose exit
    /// has passed (a seek, a late plan) is dropped, and the change is a plain crossfade.
    fn armed_exit(&mut self, out_rate: u32) -> Option<u64> {
        let plan = self.plan.as_ref()?;
        let cur = self.cur.as_ref()?;
        let next = self.next.as_ref()?;
        if cur.key != plan.outgoing_key || next.key != plan.incoming_key || next.started || next.failed || next.base_ms != plan.entry_ms {
            return None;
        }
        if cur.eof || cur.failed || cur.resync {
            return None;
        }
        // Where the song is: its deck's own clock after an Automix brought it in, else the
        // resampler's frame count.
        let passed = match cur.deck.as_ref() {
            Some(d) => d.song_ms > plan.exit_ms as f64,
            None => cur.base_ms > plan.exit_ms || cur.out_frames > (plan.exit_ms - cur.base_ms) * out_rate as u64 / 1000,
        };
        if passed {
            log::info!("Automix: the exit at {} ms has passed, a plain crossfade instead", plan.exit_ms);
            self.plan = None;
            self.unpark_next();
            return None;
        }
        Some(plan.exit_ms)
    }

    /// No plan any more: a next song waiting at a plan's entry goes back to its start, so a plain
    /// change plays its intro.
    fn unpark_next(&mut self) {
        if let Some(n) = self.next.as_mut().filter(|n| !n.started && n.base_ms != 0) {
            if n.seek(0).is_err() {
                self.next = None;
            }
        }
    }

    /// The decks' samples of a song that came in through an Automix go through its tempo stretch:
    /// what it holds plays too (`end`: and the rest of it).
    fn deck_pass(t: &mut Track, out: &mut Vec<f32>, from: usize, end: bool) {
        let Some(d) = t.deck.as_mut() else { return };
        let fresh = out.split_off(from.min(out.len()));
        d.stretch.process(&fresh, out);
        if end {
            d.stretch.flush(out);
        }
    }

    /// Takes in a new plan; the next song is opened again at the plan's entry when it waits elsewhere.
    fn arm(&mut self, plan: Option<MixPlan>) {
        if self.automix.is_some() {
            // For the change after this one (a short song): read once this mix is over.
            self.plan = plan;
            return;
        }
        self.plan = plan;
        let Some(p) = self.plan.as_ref() else {
            self.unpark_next();
            return;
        };
        log::info!("Automix planned: exit {} ms, entry {} ms, rate {:.4}, mix {} ms", p.exit_ms, p.entry_ms, p.rate, p.mix_ms);
        let reopen = self.next.as_ref().filter(|n| n.key == p.incoming_key && !n.started && n.base_ms != p.entry_ms).map(|n| n.dec.source().clone());
        if let Some(source) = reopen {
            self.next = None;
            self.preload(source);
        }
    }

    /// The mix begins with `first` (the outgoing song from the exit on).
    fn start_automix(&mut self, first: Vec<f32>, out_rate: u32) {
        let Some(plan) = self.plan.take() else { return };
        let Some(next) = self.next.as_mut() else { return };
        next.started = true;
        next.start_frame = self.written + (self.pending.len() / 2) as u64 + self.stretch.latency_frames();
        log::info!("Automix into {} ({:.4}x, {} ms)", next.key, plan.rate, plan.mix_ms);
        {
            let mut tl = self.sh.timeline();
            tl.end_frame = None;
            tl.segments.push_back(Segment {
                start_frame: next.start_frame,
                start_ms: plan.entry_ms,
                rate: out_rate,
                key: next.key.clone(),
                duration_ms: next.dec.duration_ms(),
                speed: self.speed * plan.rate,
            });
        }
        self.automix_in_ms = plan.entry_ms as f64;
        let mut m = Box::new(Mixer::new(plan, out_rate));
        m.push_outgoing(&first);
        self.automix = Some(m);
    }

    /// One step of the Automix: more of either song as the mixer wants it, then what both have.
    fn pump_automix(&mut self, out_rate: u32) {
        let Engine { automix, cur, next, decoded, stage, stretch, pending, automix_in_ms, .. } = self;
        let (Some(m), Some(cur), Some(next)) = (automix.as_mut(), cur.as_mut(), next.as_mut()) else {
            self.automix = None;
            return;
        };
        let rate = m.plan.rate;
        let mut feed = |t: &mut Track, out: &mut Vec<f32>| -> bool {
            if t.eof {
                return false;
            }
            if t.resync {
                let ms = t.decoded_ms();
                if t.seek(ms).is_err() {
                    return false;
                }
            }
            decoded.clear();
            let d = &mut *decoded;
            let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| t.dec.decode_next(d)))
                .unwrap_or_else(|_| Err(DecodeError::Failed("the decoder crashed on this stream".into())));
            match result {
                Ok(true) => {
                    t.decoded_frames += (decoded.len() / 2) as u64;
                    let before = out.len();
                    t.rs.process(decoded, out);
                    t.out_frames += ((out.len() - before) / 2) as u64;
                    Engine::deck_pass(t, out, before, false);
                    true
                }
                Ok(false) | Err(DecodeError::Failed(_)) => {
                    t.eof = true;
                    let before = out.len();
                    t.rs.flush(out);
                    Engine::deck_pass(t, out, before, true);
                    true
                }
                Err(DecodeError::Aborted) => {
                    t.resync = true;
                    false
                }
            }
        };
        let mut buf = Vec::new();
        if m.wants_outgoing() {
            if feed(cur, &mut buf) {
                m.push_outgoing(&buf);
            } else if cur.eof {
                // The outgoing song ended before the mix did: it is silence from here.
                m.push_outgoing(&vec![0.0; 2 * 1024]);
            }
        }
        for _ in 0..4 {
            if !m.wants_incoming() {
                break;
            }
            buf.clear();
            if feed(next, &mut buf) {
                m.push_incoming(&buf);
            } else if next.eof {
                m.push_incoming(&vec![0.0; 2 * 1024]);
            } else {
                break;
            }
        }
        stage.clear();
        m.mix(stage);
        *automix_in_ms += (stage.len() / 2) as f64 * rate * 1000. / out_rate as f64;
        stretch.process(stage, pending);
        if m.finished() {
            let m = self.automix.take().expect("mixing");
            log::info!("Automix: the limiter held the sum down in {} of {} blocks", m.limited, m.blocks.max(1));
            let (rest, deck) = m.into_incoming();
            let mut song_ms = self.automix_in_ms + (rest.len() / 2) as f64 * rate * 1000. / out_rate as f64;
            if let Some(c) = self.cur.as_mut() {
                c.eof = true;
            }
            self.stretch.process(&rest, &mut self.pending);
            if let Some(n) = self.next.as_mut() {
                song_ms = song_ms.max(0.);
                n.deck = Some(Deck { stretch: deck, ret: TempoReturn::new(rate, out_rate), song_ms });
            }
            log::info!("Automix mixed; the incoming song eases back to its own tempo from {rate:.4}x");
        }
    }

    /// A song after an Automix plays through its own tempo stretch, which eases back to 1: returns
    /// the new speed and the song's time where it starts, when it changed in this block.
    fn through_deck(track: &mut Track, stage: &mut Vec<f32>, end: bool, out_rate: u32) -> Option<(f64, f64)> {
        let deck = track.deck.as_mut()?;
        let mut out = Vec::with_capacity(stage.len() + 64);
        deck.stretch.process(stage, &mut out);
        if end {
            deck.stretch.flush(&mut out);
        }
        *stage = out;
        let n = (stage.len() / 2) as u64;
        deck.song_ms += n as f64 * deck.ret.rate * 1000. / out_rate as f64;
        let changed = deck.ret.advance(n)?;
        deck.stretch.glide_to(changed);
        Some((changed, deck.song_ms))
    }

    /// The song's tempo moved on its way back to 1: the position follows from the next frame on.
    fn deck_segment(&mut self, deck_speed: f64, song_ms: f64, out_rate: u32) {
        let start_frame = self.written + (self.pending.len() / 2) as u64 + self.stretch.latency_frames();
        let mut tl = self.sh.timeline();
        let Some(last) = tl.segments.back() else { return };
        let (key, duration_ms) = (last.key.clone(), last.duration_ms);
        tl.segments.push_back(Segment { start_frame, start_ms: song_ms.max(0.) as u64, rate: out_rate, key, duration_ms, speed: self.speed * deck_speed });
    }

    /// One packet of the ending song, mixed with as much of the next one, equal-power curves.
    fn pump_mix(&mut self) {
        let (Some(cur), Some(next)) = (self.cur.as_mut(), self.next.as_mut()) else {
            self.mix = None;
            return;
        };
        let mut a = Vec::new();
        if cur.resync {
            let ms = cur.decoded_ms();
            if cur.seek(ms).is_err() {
                return;
            }
        }
        self.decoded.clear();
        let decoded = &mut self.decoded;
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| cur.dec.decode_next(decoded)))
            .unwrap_or_else(|_| Err(DecodeError::Failed("the decoder crashed on this stream".into())));
        match result {
            Ok(true) => {
                cur.decoded_frames += (self.decoded.len() / 2) as u64;
                cur.rs.process(&self.decoded, &mut a);
                Engine::deck_pass(cur, &mut a, 0, false);
            }
            Ok(false) | Err(DecodeError::Failed(_)) => {
                cur.eof = true;
                cur.rs.flush(&mut a);
                Engine::deck_pass(cur, &mut a, 0, true);
            }
            Err(DecodeError::Aborted) => {
                cur.resync = true;
                return;
            }
        }
        // As much of the next song as the ending one gave.
        while self.mix_buf.len() < a.len() && !next.eof && !next.failed {
            if next.resync {
                let ms = next.decoded_ms();
                if next.seek(ms).is_err() {
                    break;
                }
            }
            self.decoded.clear();
            let decoded = &mut self.decoded;
            let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| next.dec.decode_next(decoded)))
                .unwrap_or_else(|_| Err(DecodeError::Failed("the decoder crashed on this stream".into())));
            match result {
                Ok(true) => {
                    next.decoded_frames += (self.decoded.len() / 2) as u64;
                    next.rs.process(&self.decoded, &mut self.mix_buf);
                }
                Ok(false) => {
                    next.eof = true;
                    next.rs.flush(&mut self.mix_buf);
                }
                Err(DecodeError::Aborted) => {
                    next.resync = true;
                    break;
                }
                Err(DecodeError::Failed(message)) => {
                    log::warn!("next song failed during the crossfade: {message}");
                    next.failed = true;
                    next.eof = true;
                }
            }
        }
        let mix = self.mix.as_mut().expect("mixing");
        let frames = a.len() / 2;
        let take = (self.mix_buf.len() / 2).min(frames);
        self.stage.clear();
        for f in 0..frames {
            let g = ((mix.done + f as u64) as f32 / mix.total.max(1) as f32).min(1.0);
            let (fade_out, fade_in) = ((g * std::f32::consts::FRAC_PI_2).cos(), (g * std::f32::consts::FRAC_PI_2).sin());
            for ch in 0..2 {
                let b = if f < take { self.mix_buf[f * 2 + ch] } else { 0.0 };
                self.stage.push(a[f * 2 + ch] * fade_out + b * fade_in);
            }
        }
        self.mix_buf.drain(..take * 2);
        mix.done += frames as u64;
        if cur.eof {
            // The ending song is over: the rest of what was decoded of the next one, then on.
            self.stage.append(&mut self.mix_buf);
            self.mix = None;
        }
        self.stretch.process(&self.stage, &mut self.pending);
    }
}

/// Turns played frames into events, every 50 ms.
fn clock(sh: Arc<Shared>) {
    let mut last_position = Instant::now() - Duration::from_secs(1);
    let mut buffering = false;
    let mut starving_ticks = 0u32;
    while !sh.shutdown.load(Ordering::SeqCst) {
        std::thread::sleep(Duration::from_millis(50));
        let consumed = sh.out.consumed.load(Ordering::Acquire);
        let playing = sh.out.playing.load(Ordering::SeqCst);
        let mut events = Vec::new();
        {
            let mut tl = sh.timeline();
            while tl.segments.len() > 1 && consumed >= tl.segments[1].start_frame {
                let gone = tl.segments.pop_front().expect("two");
                let s = &tl.segments[0];
                // The same song at another tempo (an Automix easing back) is no change of song.
                if s.key != gone.key {
                    events.push(PlayerEvent::TrackChanged { key: s.key.clone() });
                    if let Some(d) = s.duration_ms {
                        events.push(PlayerEvent::Duration { ms: d });
                    }
                }
                last_position = Instant::now() - Duration::from_secs(1);
            }
            let at_end = tl.end_frame.is_some_and(|e| consumed >= e);
            if at_end && !tl.ended && !tl.segments.is_empty() && !tl.failed {
                tl.ended = true;
                sh.ended.store(true, Ordering::SeqCst);
                sh.out.playing.store(false, Ordering::SeqCst);
                if let Some(ms) = tl.position_ms(consumed) {
                    events.push(PlayerEvent::Position { ms });
                }
                events.push(PlayerEvent::Ended);
            } else if playing && !tl.ended && last_position.elapsed() >= Duration::from_millis(250) {
                if let Some(ms) = tl.position_ms(consumed) {
                    events.push(PlayerEvent::Position { ms });
                    last_position = Instant::now();
                }
            }
            let starving = playing
                && !tl.ended
                && !tl.failed
                && !at_end
                && !tl.segments.is_empty()
                && sh.out.starved.load(Ordering::Relaxed);
            starving_ticks = if starving { starving_ticks + 1 } else { 0 };
            if starving_ticks >= 2 && !buffering {
                buffering = true;
                events.push(PlayerEvent::Buffering(true));
            } else if !starving && buffering {
                buffering = false;
                events.push(PlayerEvent::Buffering(false));
            }
        }
        for ev in events {
            sh.emit(ev);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::{Segment, Timeline};

    #[test]
    fn the_position_runs_at_the_speed() {
        let mut tl = Timeline::default();
        tl.segments.push_back(Segment { start_frame: 1_000, start_ms: 30_000, rate: 48_000, key: "k".into(), duration_ms: Some(200_000), speed: 1.5 });
        // Two seconds heard at 1.5× are three seconds of the song.
        assert_eq!(tl.position_ms(1_000 + 96_000), Some(33_000));
        tl.segments[0].speed = 1.;
        assert_eq!(tl.position_ms(1_000 + 96_000), Some(32_000));
    }
}
