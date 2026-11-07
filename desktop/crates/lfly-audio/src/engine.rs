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

use crate::cache::DiskCache;
use crate::decode::{DecodeError, Resample, TrackDecoder};
use crate::http::{prefetch, ReaderCtl};
use crate::output::{self, OutShared, Output};
use crate::{PlayerEvent, Source};

/// How much of the next song is fetched ahead.
const PRELOAD_BYTES: u64 = 2 * 1024 * 1024;

pub(crate) enum Cmd {
    Load { source: Source, start_ms: u64, autoplay: bool },
    Seek(u64),
    Stop,
    Preload(Option<Source>),
    PreloadReady { gen: u64, track: Box<Result<TrackDecoder, DecodeError>> },
    SetDevice(Option<String>),
    /// Crossfade length between songs, in ms (0: gapless, no fade).
    Crossfade(u64),
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
        let ms = s.start_ms + consumed.saturating_sub(s.start_frame) * 1000 / s.rate.max(1) as u64;
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
            Cmd::PreloadReady { gen, track } => {
                if gen == self.preload_gen {
                    self.preload_ctl = None;
                    match *track {
                        Ok(dec) => {
                            let rate = self.out.as_ref().map(|o| o.rate).unwrap_or(48_000);
                            log::info!("preloaded {}", dec.source().key());
                            self.next = Some(Track::new(dec, rate));
                        }
                        Err(e) => log::warn!("preload failed: {e}"),
                    }
                }
            }
            Cmd::Crossfade(ms) => self.crossfade_ms = ms.min(12_000),
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
        self.mix = None;
        self.mix_buf.clear();
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
            if t.started && start_ms == 0 {
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
        // Mid-crossfade, once the next song is heard, the seek is in the next song.
        if self.mix.is_some() {
            let consumed = self.sh.out.consumed.load(Ordering::Acquire);
            if self.next.as_ref().is_some_and(|n| n.started && consumed >= n.start_frame) {
                self.cur = self.next.take();
                if let Some(c) = self.cur.as_mut() {
                    c.started = false;
                }
            }
        }
        self.flush();
        if let Some(n) = self.next.as_mut() {
            if n.started {
                n.started = false;
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
            };
            tl.reset();
            tl.segments.push_back(seg);
        }
        self.sh.ended.store(false, Ordering::SeqCst);
        self.sh.emit(PlayerEvent::Position { ms: at });
    }

    fn stop(&mut self) {
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
        if self.cur.as_ref().is_some_and(|c| c.dec.source() == &source)
            || self.next.as_ref().is_some_and(|n| n.dec.source() == &source)
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
            let track = Box::new(TrackDecoder::open(&source, &cache, ctl));
            let _ = tx.send(Cmd::PreloadReady { gen, track });
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
        if self.mix.is_none() && self.next.as_ref().is_some_and(|n| n.started && consumed >= n.start_frame) {
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
        if self.mix.is_none() && self.should_start_mix() {
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
            next.start_frame = self.written;
            let mut tl = self.sh.timeline();
            tl.end_frame = None;
            tl.segments.push_back(Segment {
                start_frame: self.written,
                start_ms: next.decoded_ms(),
                rate: out_rate,
                key: next.key.clone(),
                duration_ms: next.dec.duration_ms(),
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
                track.rs.process(&self.decoded, &mut self.pending);
            }
            Ok(false) => {
                track.eof = true;
                track.rs.flush(&mut self.pending);
                let continues = !use_next && has_next;
                if !continues {
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
        next.start_frame = self.written;
        log::info!("crossfade into {} over {left_ms} ms", next.key);
        let mut tl = self.sh.timeline();
        tl.end_frame = None;
        tl.segments.push_back(Segment {
            start_frame: self.written,
            start_ms: next.decoded_ms(),
            rate: out_rate,
            key: next.key.clone(),
            duration_ms: next.dec.duration_ms(),
        });
        drop(tl);
        self.mix_buf.clear();
        self.mix = Some(Mix { total: left_ms * out_rate as u64 / 1000, done: 0 });
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
            }
            Ok(false) | Err(DecodeError::Failed(_)) => {
                cur.eof = true;
                cur.rs.flush(&mut a);
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
        for f in 0..frames {
            let g = ((mix.done + f as u64) as f32 / mix.total.max(1) as f32).min(1.0);
            let (fade_out, fade_in) = ((g * std::f32::consts::FRAC_PI_2).cos(), (g * std::f32::consts::FRAC_PI_2).sin());
            for ch in 0..2 {
                let b = if f < take { self.mix_buf[f * 2 + ch] } else { 0.0 };
                self.pending.push(a[f * 2 + ch] * fade_out + b * fade_in);
            }
        }
        self.mix_buf.drain(..take * 2);
        mix.done += frames as u64;
        if cur.eof {
            // The ending song is over: the rest of what was decoded of the next one, then on.
            self.pending.append(&mut self.mix_buf);
            self.mix = None;
        }
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
                tl.segments.pop_front();
                let s = &tl.segments[0];
                events.push(PlayerEvent::TrackChanged { key: s.key.clone() });
                if let Some(d) = s.duration_ms {
                    events.push(PlayerEvent::Duration { ms: d });
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
