//! The local player: the queue, YouTube Music streams and the audio engine together.
//!
//! It reports its state in LFly Connect's own shape, so the window shows it like a remote device
//! and the Connect host sends it to phones and watches as it is. Phones control it through
//! [HostPlayer]; the window through the `player_*` commands.

use std::collections::HashMap;
use std::sync::{Arc, Mutex, OnceLock, Weak};
use std::time::{Duration, Instant};

use lfly_audio::{PlayerEvent, Source};
use lfly_connect::protocol::{self, State, TrackRef, Transfer};
use lfly_connect::{Host, HostPlayer};
use lfly_yt::YtMusic;

use crate::queue::{Queue, Repeat};

/// What the player needs from the app it runs in: where to report, and a few settings.
/// The Tauri app emits window events; the GPUI app updates its views.
pub trait PlayerHost: Send + Sync + 'static {
    /// Something real changed (song, queue, play/pause, volume...).
    fn state(&self, state: &State, received_at: i64);
    /// The 4 Hz position tick: only the position, never the whole state.
    fn position(&self, position_ms: i64, received_at: i64);
    /// A short message for the person (a song that cannot play...).
    fn notice(&self, text: &str);
    /// Playback was handed over from another device.
    fn arrived(&self, _from_device_id: String) {}
    /// The downloaded file of a song, if there is one.
    fn downloaded(&self, _id: &str) -> Option<std::path::PathBuf> {
        None
    }
    /// Keep playing similar songs when the queue ends.
    fn autoplay(&self) -> bool {
        true
    }
    /// A library sync offer from a paired device (LFly Connect).
    fn library(&self, _peer_id: &str, _offered: serde_json::Value) -> Option<serde_json::Value> {
        None
    }
}

/// How long a resolved stream URL is trusted when YouTube does not say (they last ~6 h).
const URL_LIFETIME: Duration = Duration::from_secs(4 * 3600);
/// How often a playing host repeats its state to the devices controlling it.
const STATE_EVERY: Duration = Duration::from_secs(3);

struct Resolved {
    url: String,
    user_agent: Option<String>,
    until: Instant,
}

#[derive(Default)]
struct Now {
    queue: Queue,
    playing: bool,
    /// The song the window wants playing (true from the press, before the audio starts).
    wants_play: bool,
    position_ms: i64,
    duration_ms: i64,
    /// Bumped on every load: a stream resolved for an older load is dropped.
    load_seq: u64,
    /// The queue index of the preloaded song, to follow a gapless switch.
    preloaded: Option<i32>,
    /// One retry with a fresh URL before giving up on a song.
    retried: bool,
    volume: i32,
    /// A seek the engine has not reported yet: until it does, this is the position to show,
    /// so controllers never see the bar jump back.
    seeking: Option<(i64, Instant)>,
}

impl Now {
    fn position(&mut self, engine: Option<u64>) -> i64 {
        if let Some((target, since)) = self.seeking {
            let reached = engine.is_some_and(|p| (p as i64 - target).abs() < 1_500);
            if !reached && since.elapsed() < Duration::from_secs(3) {
                return target;
            }
            self.seeking = None;
        }
        engine.map_or(self.position_ms, |p| p as i64)
    }
}

pub struct LocalPlayer {
    me: Weak<LocalPlayer>,
    sink: Arc<dyn PlayerHost>,
    rt: tokio::runtime::Handle,
    audio: lfly_audio::Player,
    yt: YtMusic,
    now: Mutex<Now>,
    urls: Mutex<HashMap<String, Resolved>>,
    host: OnceLock<Host>,
    device_id: String,
}

impl LocalPlayer {
    pub fn new(
        sink: Arc<dyn PlayerHost>,
        rt: tokio::runtime::Handle,
        yt: YtMusic,
        device_id: String,
        // The audio cache; one engine per folder (the Tauri and GPUI apps may run side by side).
        cache_dir: std::path::PathBuf,
    ) -> Result<Arc<Self>, String> {
        let audio = lfly_audio::Player::new(cache_dir, 2 * 1024 * 1024 * 1024)?;
        let player = Arc::new_cyclic(|me| LocalPlayer {
            me: me.clone(),
            sink,
            rt,
            audio,
            yt,
            now: Mutex::new(Now { volume: 80, ..Default::default() }),
            urls: Mutex::default(),
            host: OnceLock::new(),
            device_id,
        });
        player.audio.set_volume(perceptual(80));
        let events = player.audio.subscribe();
        let weak = Arc::downgrade(&player);
        player.rt.spawn(listen(weak.clone(), events));
        player.rt.spawn(heartbeat(weak));
        Ok(player)
    }

    /// The Connect host, once it runs: every change goes out to the devices controlling this one.
    pub fn attach_host(&self, host: Host) {
        let _ = self.host.set(host);
    }

    pub fn host(&self) -> Option<&Host> {
        self.host.get()
    }

    // ---- What the window asks ----

    /// Plays [tracks] from [index] at [position_ms].
    pub fn play_queue(&self, tracks: Vec<TrackRef>, index: i32, position_ms: i64, autoplay: bool) {
        {
            let mut now = self.now.lock().unwrap();
            now.queue.set(tracks, index);
        }
        self.load_current(position_ms, autoplay);
    }

    pub fn run(&self, action: &str, value: i64) {
        match action {
            protocol::CMD_PLAY => self.play(),
            protocol::CMD_PAUSE => self.pause(),
            protocol::CMD_TOGGLE => {
                if self.now.lock().unwrap().wants_play {
                    self.pause()
                } else {
                    self.play()
                }
            }
            protocol::CMD_NEXT => {
                let next = self.now.lock().unwrap().queue.after_skip();
                match next {
                    Some(i) => self.jump(i),
                    None => self.pause(),
                }
            }
            protocol::CMD_PREVIOUS => {
                let (target, current) = {
                    let now = self.now.lock().unwrap();
                    (now.queue.before(self.position()), now.queue.index)
                };
                match target {
                    Some(i) if i == current => self.seek(0),
                    Some(i) => self.jump(i),
                    None => {}
                }
            }
            protocol::CMD_SEEK => self.seek(value),
            protocol::CMD_SKIP_TO => self.jump(value as i32),
            protocol::CMD_VOLUME => {
                let v = value.clamp(0, 100) as i32;
                self.now.lock().unwrap().volume = v;
                self.audio.set_volume(perceptual(v));
                self.changed(true);
            }
            protocol::CMD_VOLUME_STEP => {
                let v = (self.now.lock().unwrap().volume + if value > 0 { 7 } else { -7 }).clamp(0, 100);
                self.run(protocol::CMD_VOLUME, v as i64);
            }
            protocol::CMD_SHUFFLE => {
                self.now.lock().unwrap().queue.set_shuffle(value == 1);
                self.preload_next();
                self.changed(true);
            }
            protocol::CMD_REPEAT => {
                self.now.lock().unwrap().queue.repeat = Repeat::from_wire(value);
                self.preload_next();
                self.changed(true);
            }
            _ => {}
        }
    }

    pub fn edit_queue(&self, op: &str, tracks: Vec<TrackRef>, index: i32, to: i32) {
        let (reload, was_empty) = {
            let mut now = self.now.lock().unwrap();
            let was_empty = now.queue.index < 0;
            let reload = match op {
                protocol::EDIT_ADD => {
                    now.queue.add(tracks);
                    false
                }
                protocol::EDIT_NEXT => {
                    now.queue.add_next(tracks);
                    false
                }
                protocol::EDIT_REMOVE => now.queue.remove(index),
                protocol::EDIT_MOVE => {
                    now.queue.move_item(index, to);
                    false
                }
                protocol::EDIT_CLEAR => {
                    now.queue.clear_upcoming();
                    false
                }
                _ => false,
            };
            (reload, was_empty)
        };
        if reload || (was_empty && self.now.lock().unwrap().queue.index >= 0) {
            let play = self.now.lock().unwrap().wants_play || was_empty;
            self.load_current(0, play);
        } else {
            self.preload_next();
        }
        self.changed(true);
    }

    pub fn state(&self) -> State {
        let mut now = self.now.lock().unwrap();
        let position_ms = now.position(self.audio.position_ms());
        let current = now.queue.current();
        State {
            device_id: self.device_id.clone(),
            active: current.is_some(),
            playing: now.wants_play && current.is_some(),
            position_ms,
            at: lfly_connect::controller::now_ms(),
            duration_ms: if now.duration_ms > 0 { now.duration_ms } else { current.map_or(0, |t| t.duration_ms) },
            index: now.queue.index,
            queue: now.queue.tracks.clone(),
            shuffle: now.queue.shuffle,
            repeat: now.queue.repeat.as_str().to_string(),
            volume: now.volume,
            handed_to: None,
        }
    }

    /// Everything another device needs to play on from here, the stream URL included.
    pub fn hand_over(&self) -> Option<Transfer> {
        let s = self.state();
        if s.queue.is_empty() || s.index < 0 {
            return None;
        }
        let mut queue = s.queue;
        if let Some(t) = queue.get_mut(s.index as usize) {
            let urls = self.urls.lock().unwrap();
            t.stream_url = urls.get(&t.id).filter(|r| r.until > Instant::now()).map(|r| r.url.clone());
        }
        Some(Transfer { queue, index: s.index, position_ms: s.position_ms, playing: s.playing, from_device_id: self.device_id.clone() })
    }

    pub fn set_crossfade(&self, seconds: u32) {
        self.audio.set_crossfade(seconds.min(12) as u64 * 1000);
    }

    pub fn set_equalizer(&self, curve: lfly_audio::EqCurve) {
        self.audio.set_equalizer(curve);
    }

    /// Output devices, without ALSA's plugin and virtual entries (on Linux it lists dozens).
    pub fn devices(&self) -> Vec<lfly_audio::OutputDevice> {
        let all = lfly_audio::Player::devices();
        let skip = ["null", "lavrate", "samplerate", "speexrate", "upmix", "vdownmix", "jack", "oss", "surround", "dmix", "dsnoop"];
        all.into_iter()
            .filter(|d| d.is_default || !skip.iter().any(|s| d.id.to_lowercase().contains(s) || d.name.to_lowercase().starts_with(s)))
            .collect()
    }

    pub fn set_device(&self, id: Option<String>) {
        self.audio.set_device(id);
    }

    pub fn is_playing(&self) -> bool {
        self.now.lock().unwrap().wants_play
    }

    // ---- Inside ----

    fn position(&self) -> i64 {
        let engine = self.audio.position_ms();
        self.now.lock().unwrap().position(engine)
    }

    fn play(&self) {
        let loaded = self.audio.position_ms().is_some();
        let has = self.now.lock().unwrap().queue.index >= 0;
        if !has {
            return;
        }
        self.now.lock().unwrap().wants_play = true;
        if loaded {
            self.audio.play();
        } else {
            let at = self.now.lock().unwrap().position_ms;
            self.load_current(at, true);
        }
        self.changed(true);
    }

    fn pause(&self) {
        self.now.lock().unwrap().wants_play = false;
        self.audio.pause();
        self.changed(true);
    }

    fn seek(&self, ms: i64) {
        let ms = ms.max(0);
        {
            let mut now = self.now.lock().unwrap();
            now.position_ms = ms;
            now.seeking = Some((ms, Instant::now()));
        }
        self.audio.seek(ms as u64);
        self.changed(true);
    }

    fn jump(&self, index: i32) {
        let ok = {
            let mut now = self.now.lock().unwrap();
            if index < 0 || index as usize >= now.queue.tracks.len() {
                false
            } else {
                now.queue.index = index;
                true
            }
        };
        if ok {
            self.load_current(0, true);
        }
    }

    /// Resolves the current song's stream (or uses the cache) and loads it.
    fn load_current(&self, position_ms: i64, autoplay: bool) {
        let (track, seq) = {
            let mut now = self.now.lock().unwrap();
            let Some(track) = now.queue.current().cloned() else {
                now.wants_play = false;
                drop(now);
                self.audio.stop();
                self.changed(true);
                return;
            };
            now.load_seq += 1;
            now.position_ms = position_ms;
            now.seeking = None;
            now.duration_ms = track.duration_ms;
            now.wants_play = autoplay;
            now.preloaded = None;
            now.retried = false;
            (track, now.load_seq)
        };
        self.changed(true);
        let Some(me) = self.me.upgrade() else { return };
        self.rt.spawn(async move {
            match me.source_for(&track, false).await {
                Ok(source) => {
                    if me.now.lock().unwrap().load_seq != seq {
                        return;
                    }
                    let play = me.now.lock().unwrap().wants_play;
                    me.audio.load(source, position_ms.max(0) as u64, play);
                    me.preload_next();
                }
                Err(e) => {
                    if me.now.lock().unwrap().load_seq != seq {
                        return;
                    }
                    log::warn!("No stream for '{}': {e}", track.title);
                    me.say(&format!("Impossibile riprodurre “{}”: passo al prossimo", track.title));
                    me.skip_failed();
                }
            }
        });
    }

    /// The next song after one that could not play; stops at the end of the queue.
    fn skip_failed(&self) {
        let next = self.now.lock().unwrap().queue.after_skip();
        let current = self.now.lock().unwrap().queue.index;
        match next {
            Some(i) if i != current => {
                self.now.lock().unwrap().queue.index = i;
                self.load_current(0, true);
            }
            _ => {
                self.now.lock().unwrap().wants_play = false;
                self.changed(true);
            }
        }
    }

    /// Where a song plays from: its download, the cache when it holds all of it, else a fresh stream URL.
    async fn source_for(&self, track: &TrackRef, fresh: bool) -> Result<Source, String> {
        if let Some(path) = self.sink.downloaded(&track.id) {
            return Ok(Source::file(path));
        }
        if !fresh {
            let cached = {
                let urls = self.urls.lock().unwrap();
                urls.get(&track.id).filter(|r| r.until > Instant::now()).map(|r| (r.url.clone(), r.user_agent.clone()))
            };
            if let Some((url, ua)) = cached {
                return Ok(http_source(url, &track.id, ua));
            }
            // A URL handed over by another device is fresh enough to start at once.
            if let Some(url) = track.stream_url.as_deref().filter(|u| u.starts_with("https://")) {
                return Ok(http_source(url.to_string(), &track.id, None));
            }
            if self.audio.cache().is_complete(&track.id) {
                // Fully cached: the URL is never used.
                return Ok(Source::http("https://offline.invalid/", &track.id));
            }
        }
        let yt_track: lfly_yt::Track =
            serde_json::from_value(serde_json::to_value(track).map_err(|e| e.to_string())?).map_err(|e| e.to_string())?;
        let stream = self.yt.resolve_stream(&yt_track).await.map_err(|e| e.to_string())?;
        let until = stream
            .expires_at
            .and_then(|at| at.checked_sub(now_secs() + 600))
            .map_or(Instant::now() + URL_LIFETIME, |left| Instant::now() + Duration::from_secs(left));
        self.urls.lock().unwrap().insert(
            track.id.clone(),
            Resolved { url: stream.url.clone(), user_agent: stream.user_agent.clone(), until },
        );
        Ok(http_source(stream.url, &track.id, stream.user_agent))
    }

    /// Gets the next song ready, for a gapless switch.
    fn preload_next(&self) {
        let (next, seq) = {
            let now = self.now.lock().unwrap();
            let next = now.queue.after_end().filter(|i| *i != now.queue.index);
            (next.and_then(|i| now.queue.tracks.get(i as usize).cloned().map(|t| (i, t))), now.load_seq)
        };
        self.audio.cancel_preload();
        self.now.lock().unwrap().preloaded = None;
        let Some((index, track)) = next else { return };
        let Some(me) = self.me.upgrade() else { return };
        self.rt.spawn(async move {
            if let Ok(source) = me.source_for(&track, false).await {
                let mut now = me.now.lock().unwrap();
                if now.load_seq != seq || now.queue.tracks.get(index as usize).map(|t| &t.id) != Some(&track.id) {
                    return;
                }
                now.preloaded = Some(index);
                drop(now);
                me.audio.preload(source);
            }
        });
    }

    fn stop_at_end(&self) {
        {
            let mut now = self.now.lock().unwrap();
            now.wants_play = false;
            now.playing = false;
            now.position_ms = 0;
        }
        self.changed(true);
    }

    /// The queue ended: on with songs like the last one (YouTube Music's radio), as the phone does.
    fn play_on_with_similar(&self) {
        let Some(me) = self.me.upgrade() else { return };
        let last = self.now.lock().unwrap().queue.current().cloned();
        let Some(last) = last else { return self.stop_at_end() };
        self.rt.spawn(async move {
            let found = me.yt.up_next(&last.id).await.unwrap_or_default();
            let tracks: Vec<TrackRef> = {
                let now = me.now.lock().unwrap();
                found
                    .into_iter()
                    .filter_map(|t| serde_json::to_value(&t).ok().and_then(|v| serde_json::from_value::<TrackRef>(v).ok()))
                    .filter(|t| !now.queue.tracks.iter().any(|q| q.id == t.id))
                    .take(25)
                    .collect()
            };
            if tracks.is_empty() {
                return me.stop_at_end();
            }
            let next = {
                let mut now = me.now.lock().unwrap();
                let next = now.queue.tracks.len() as i32;
                now.queue.add(tracks);
                now.queue.index = next;
                next
            };
            log::info!("Queue ended: on with similar songs from #{next}");
            me.load_current(0, true);
        });
    }

    fn on_event(&self, event: PlayerEvent) {
        match event {
            PlayerEvent::Position { ms } => {
                let position_ms = {
                    let mut now = self.now.lock().unwrap();
                    now.position_ms = ms as i64;
                    now.position(self.audio.position_ms())
                };
                // Four times a second: only the position, never the whole queue. A full state here
                // made every listener in the window (and the system media controls) run again.
                self.sink.position(position_ms, lfly_connect::controller::now_ms());
            }
            PlayerEvent::Duration { ms } => {
                if ms > 0 {
                    self.now.lock().unwrap().duration_ms = ms as i64;
                    self.changed(true);
                }
            }
            PlayerEvent::Playing => {
                {
                    let mut now = self.now.lock().unwrap();
                    now.playing = true;
                    now.wants_play = true;
                    now.retried = false;
                }
                self.changed(true);
            }
            PlayerEvent::Paused => {
                {
                    let mut now = self.now.lock().unwrap();
                    now.playing = false;
                    now.wants_play = false;
                }
                self.changed(true);
            }
            PlayerEvent::TrackChanged { key } => {
                // The preloaded song is playing now, with no gap.
                {
                    let mut now = self.now.lock().unwrap();
                    let index = now
                        .preloaded
                        .filter(|i| now.queue.tracks.get(*i as usize).map(|t| t.id == key).unwrap_or(false))
                        .or_else(|| now.queue.tracks.iter().position(|t| t.id == key).map(|i| i as i32));
                    if let Some(i) = index {
                        now.queue.index = i;
                    }
                    now.preloaded = None;
                    now.position_ms = 0;
                    now.duration_ms = now.queue.current().map_or(0, |t| t.duration_ms);
                    now.load_seq += 1;
                }
                self.preload_next();
                self.changed(true);
            }
            PlayerEvent::Ended => {
                let next = self.now.lock().unwrap().queue.after_end();
                match next {
                    Some(i) => {
                        self.now.lock().unwrap().queue.index = i;
                        self.load_current(0, true);
                    }
                    None if self.sink.autoplay() && self.now.lock().unwrap().queue.current().is_some() => self.play_on_with_similar(),
                    None => self.stop_at_end(),
                }
            }
            PlayerEvent::Error { message } => {
                let (track, retried, position) = {
                    let now = self.now.lock().unwrap();
                    (now.queue.current().cloned(), now.retried, now.position_ms)
                };
                let Some(track) = track else { return };
                log::warn!("Playback of '{}' failed: {message}", track.title);
                if retried {
                    self.say(&format!("Impossibile riprodurre “{}”: passo al prossimo", track.title));
                    self.skip_failed();
                    return;
                }
                // Usually an expired URL: a fresh one, from the same point.
                {
                    let mut now = self.now.lock().unwrap();
                    now.retried = true;
                    now.load_seq += 1;
                }
                self.urls.lock().unwrap().remove(&track.id);
                let seq = self.now.lock().unwrap().load_seq;
                let Some(me) = self.me.upgrade() else { return };
                self.rt.spawn(async move {
                    match me.source_for(&track, true).await {
                        Ok(source) if me.now.lock().unwrap().load_seq == seq => {
                            me.audio.load(source, position.max(0) as u64, true);
                            me.preload_next();
                        }
                        Ok(_) => {}
                        Err(_) => me.skip_failed(),
                    }
                });
            }
            PlayerEvent::Buffering(_) | PlayerEvent::Loading | PlayerEvent::DeviceChanged { .. } => {}
        }
    }

    /// Tells the window, and the controlling devices too when [important] (not every tick).
    fn changed(&self, important: bool) {
        let state = self.state();
        self.sink.state(&state, lfly_connect::controller::now_ms());
        if important {
            if let Some(host) = self.host.get() {
                host.broadcast();
            }
        }
    }

    fn say(&self, text: &str) {
        self.sink.notice(text);
    }
}

impl HostPlayer for LocalPlayer {
    fn state(&self) -> State {
        LocalPlayer::state(self)
    }
    fn command(&self, action: &str, value: i64) {
        self.run(action, value);
    }
    fn edit(&self, op: &str, tracks: Vec<TrackRef>, index: i32, to: i32) {
        self.edit_queue(op, tracks, index, to);
    }
    fn transfer(&self, t: Transfer) {
        self.sink.arrived(t.from_device_id.clone());
        self.play_queue(t.queue, t.index, t.position_ms, t.playing);
    }
    fn release(&self) -> Option<Transfer> {
        let handover = self.hand_over();
        self.pause();
        handover
    }
    fn library(&self, peer_id: &str, offered: serde_json::Value) -> Option<serde_json::Value> {
        self.sink.library(peer_id, offered)
    }
}

async fn listen(player: Weak<LocalPlayer>, mut events: tokio::sync::broadcast::Receiver<PlayerEvent>) {
    loop {
        match events.recv().await {
            Ok(event) => match player.upgrade() {
                Some(p) => p.on_event(event),
                None => return,
            },
            Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
            Err(_) => return,
        }
    }
}

/// While playing, the controlling devices hear the state every few seconds.
async fn heartbeat(player: Weak<LocalPlayer>) {
    loop {
        tokio::time::sleep(STATE_EVERY).await;
        let Some(p) = player.upgrade() else { return };
        if p.is_playing() {
            if let Some(host) = p.host.get() {
                host.broadcast();
            }
        }
    }
}

fn http_source(url: String, key: &str, user_agent: Option<String>) -> Source {
    let mut source = Source::http(url, key);
    if let (Source::Http { headers, .. }, Some(ua)) = (&mut source, user_agent) {
        headers.push(("User-Agent".into(), ua));
    }
    source
}

/// Volume 0..100 to the engine's gain: the ear hears loudness, not amplitude.
fn perceptual(v: i32) -> f32 {
    let x = v.clamp(0, 100) as f32 / 100.0;
    x * x
}

fn now_secs() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_secs())
}
