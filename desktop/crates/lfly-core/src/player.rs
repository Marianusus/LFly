//! The local player: the queue, YouTube Music streams and the audio engine together.
//!
//! It reports its state in LFly Connect's own shape, so the window shows it like a remote device
//! and the Connect host sends it to phones and watches as it is. Phones control it through
//! [HostPlayer]; the window through the `player_*` commands.

use std::collections::HashMap;

use crate::playlist_mix::Change;
use lfly_mix::playlist::MixTransition;
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
    /// Where the coming change sits on the playing song, when it moved (not sent to devices).
    fn mix_point(&self, _point: Option<&MixPoint>) {}
}

/// The playing song and the next one (ids), with their transition in a mixed playlist.
type PlayingPair = ((String, String), Option<MixTransition>);

/// Where the next change is planned on the playing song's timeline, so the seek bar can show it
/// (the phone's `MixPoint`). [beat_matched]: an Automix on the beat, else a plain crossfade.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MixPoint {
    pub track_id: String,
    pub start_ms: i64,
    pub end_ms: i64,
    pub beat_matched: bool,
}

/// The change's span (start, end, on the beat): the Automix plan until the song reaches its exit
/// (past it the engine fades instead), else the crossfade the engine plays (never more than a
/// third of the song, and at least a second), else none.
fn mix_span(duration_ms: i64, position_ms: i64, plan: Option<(i64, i64, bool)>, crossfade_ms: i64) -> Option<(i64, i64, bool)> {
    if let Some(span) = plan.filter(|(exit, _, _)| position_ms < *exit) {
        return Some(span);
    }
    let fade = crossfade_ms.min(duration_ms / 3);
    (duration_ms > 0 && fade >= 1_000).then_some((duration_ms - fade, duration_ms, false))
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
    /// The load whose stream is still being resolved: until it reaches the engine, whatever the
    /// engine reports is about the song before (its end or gapless switch must not move the queue).
    loading: Option<u64>,
    /// The queue index of the preloaded song, to follow a gapless switch.
    preloaded: Option<i32>,
    /// One retry with a fresh URL before giving up on a song.
    retried: bool,
    volume: i32,
    /// A seek the engine has not reported yet: until it does, this is the position to show,
    /// so controllers never see the bar jump back.
    seeking: Option<(i64, Instant)>,
    /// What the engine plays now and what it has preloaded (by song id): the Automix analyses
    /// read these very sources.
    current_source: Option<Source>,
    next_source: Option<(String, Source)>,
    /// The listener's crossfade, in seconds (Automix may fade longer when it is off).
    crossfade: u32,
    /// The crossfade the engine plays, in ms, and whether it was sent.
    fade_ms: i64,
    fade_sent: bool,
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
    automix: Arc<Mutex<crate::automix::Automix>>,
    /// The mix point last told to the sink.
    mix_point: Mutex<Option<MixPoint>>,
    /// The DJ plays: its songs always mix (Automix on whatever the switch says).
    dj_mixing: std::sync::atomic::AtomicBool,
    /// The mixed playlists' pairs, and the playing pair with its transition (None: not mixed).
    mix_index: Mutex<crate::playlist_mix::Index>,
    pair: Mutex<Option<PlayingPair>>,
    host: OnceLock<Host>,
    device_id: String,
    /// The output device chosen in Settings (None: the system's), for the Console's decks too.
    output: Mutex<Option<String>>,
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
        // Once: songs cached before Monochrome had to be the same recording may hold another
        // song under this one's id. The cache is emptied (downloads live elsewhere).
        let marker = cache_dir.join(".same-recording-v1");
        if cache_dir.is_dir() && !marker.exists() {
            if let Ok(entries) = std::fs::read_dir(&cache_dir) {
                for e in entries.flatten() {
                    let p = e.path();
                    let _ = if p.is_dir() { std::fs::remove_dir_all(&p) } else { std::fs::remove_file(&p) };
                }
            }
            log::info!("audio cache emptied once: songs must now be the very recording picked");
        }
        let audio = lfly_audio::Player::new(cache_dir.clone(), 2 * 1024 * 1024 * 1024)?;
        let _ = std::fs::write(&marker, b"");
        let player = Arc::new_cyclic(|me| LocalPlayer {
            me: me.clone(),
            sink,
            rt,
            audio,
            yt,
            now: Mutex::new(Now { volume: 80, ..Default::default() }),
            urls: Mutex::default(),
            automix: Arc::default(),
            mix_point: Mutex::default(),
            dj_mixing: Default::default(),
            mix_index: Mutex::new(crate::playlist_mix::Index::new(None)),
            pair: Mutex::default(),
            host: OnceLock::new(),
            device_id,
            output: Mutex::default(),
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
                // The position first: it takes `now` itself (a std Mutex is not reentrant, and
                // reading it under the lock deadlocked the player on every "previous").
                let position = self.position();
                let (target, current) = {
                    let now = self.now.lock().unwrap();
                    (now.queue.before(position), now.queue.index)
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
        self.now.lock().unwrap().crossfade = seconds.min(12);
        self.apply_crossfade();
    }

    /// The listener's crossfade; with Automix on (and beats tracked in this build) a change that
    /// cannot be mixed on the beat still fades, over five seconds, when the crossfade is off.
    /// The DJ's songs mix as with Automix on, whatever the switch says (the phone's `automixActive`).
    pub fn set_dj_mixing(&self, on: bool) {
        self.dj_mixing.store(on, std::sync::atomic::Ordering::Relaxed);
        self.apply_crossfade();
    }

    /// Automix runs: the switch, a mixed playlist's pair, or the DJ.
    fn automix_wanted(&self, pair: Option<&MixTransition>) -> bool {
        (self.automix.lock().unwrap().on || pair.is_some() || self.dj_mixing.load(std::sync::atomic::Ordering::Relaxed)) && crate::automix::available()
    }

    /// A mixed playlist's pair runs Automix whatever the switch says, and "Nessuna" does not fade.
    fn apply_crossfade(&self) {
        let set = self.now.lock().unwrap().crossfade;
        let pair = self.pair_transition();
        let automix = self.automix_wanted(pair.as_ref());
        let seconds = if pair.is_some_and(|t| t.is_none()) {
            0
        } else if set == 0 && automix {
            crate::automix::FALLBACK_SECONDS
        } else {
            set
        };
        let ms = seconds as i64 * 1000;
        let changed = {
            let mut now = self.now.lock().unwrap();
            let changed = now.fade_ms != ms || !now.fade_sent;
            now.fade_ms = ms;
            now.fade_sent = true;
            changed
        };
        if changed {
            self.audio.set_crossfade(ms as u64);
        }
        self.update_mix_point();
    }

    /// The playing pair's transition in a mixed playlist (None: not mixed).
    fn pair_transition(&self) -> Option<MixTransition> {
        self.pair.lock().unwrap().as_ref().and_then(|(_, t)| *t)
    }

    /// Which pair plays and whether a mixed playlist has it, from the song's start: when a mixed
    /// pair or its transition comes, goes or changes, the plan sent goes and the crossfade
    /// follows. Reads the files only when they changed on disk; without mixed pairs nothing moves.
    fn update_pair(&self) {
        let ids = {
            let now = self.now.lock().unwrap();
            let cur = now.queue.current().map(|t| t.id.clone());
            let next = now.queue.after_end().filter(|i| *i != now.queue.index).and_then(|i| now.queue.tracks.get(i as usize).map(|t| t.id.clone()));
            cur.zip(next)
        };
        let transition = {
            let mut index = self.mix_index.lock().unwrap();
            index.refresh();
            ids.as_ref().and_then(|(a, b)| index.get(a, b))
        };
        let act = {
            let mut pair = self.pair.lock().unwrap();
            let value = ids.map(|ids| (ids, transition));
            let was_mixed = pair.as_ref().is_some_and(|(_, t)| t.is_some());
            // Only a different pair or transition: an unrelated save of the playlists must not
            // drop a plan armed near its exit.
            let changed = *pair != value;
            *pair = value;
            changed && (was_mixed || transition.is_some())
        };
        if act {
            {
                let mut a = self.automix.lock().unwrap();
                a.sent = None;
                a.planned = None;
            }
            self.audio.set_automix(None);
            self.apply_crossfade();
        }
    }

    /// Automix: songs mixed on the beat when their beats and tempos allow it, in `style`.
    pub fn set_automix(&self, on: bool, style: lfly_mix::curves::MixStyle) {
        {
            let mut a = self.automix.lock().unwrap();
            if !on {
                a.cancel();
            }
            if a.on != on || a.style != style {
                a.sent = None;
            }
            a.on = on;
            a.style = style;
        }
        if !on {
            self.audio.set_automix(None);
        }
        self.apply_crossfade();
    }

    /// How fast the song heard runs: the speed, times an Automix's tempo match while it lasts.
    pub fn effective_speed(&self) -> f64 {
        self.audio.effective_speed()
    }

    /// Playback speed with the pitch kept (the phone's 0.5..2×); not kept between launches.
    pub fn set_speed(&self, speed: f32) {
        self.audio.set_speed(speed);
    }

    pub fn speed(&self) -> f32 {
        self.audio.speed()
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
        *self.output.lock().unwrap() = id.clone();
        self.audio.set_device(id);
    }

    /// The output device chosen in Settings (None: the system's default).
    pub fn output_device(&self) -> Option<String> {
        self.output.lock().unwrap().clone()
    }

    /// The audio disk cache (the Console's decks read songs through it too).
    pub fn audio_cache(&self) -> Arc<lfly_audio::DiskCache> {
        self.audio.cache().clone()
    }

    /// Where [track] plays from, as the player would open it: its download, the cache, else a
    /// stream URL (resolved again when [fresh]). For the Console's decks.
    pub async fn source_of(&self, track: &TrackRef, fresh: bool) -> Result<Source, String> {
        self.source_for(track, fresh).await
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
        // The engine dropped its plan: planned again from where the song is now.
        self.automix.lock().unwrap().sent = None;
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
            now.loading = Some(now.load_seq);
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
                    let play = {
                        let mut now = me.now.lock().unwrap();
                        if now.load_seq != seq {
                            return;
                        }
                        now.loading = None;
                        now.current_source = Some(source.clone());
                        now.wants_play
                    };
                    {
                        let mut a = me.automix.lock().unwrap();
                        a.cancel();
                        a.sent = None;
                    }
                    me.audio.load(source, position_ms.max(0) as u64, play);
                    me.preload_next();
                }
                Err(e) => {
                    {
                        let mut now = me.now.lock().unwrap();
                        if now.load_seq != seq {
                            return;
                        }
                        now.loading = None;
                    }
                    log::warn!("No stream for '{}': {e}", track.title);
                    me.say(&crate::lang::pick("Impossibile riprodurre “{}”: passo al prossimo", "Can't play “{}”: skipping to the next one", "Nu pot reda „{}”: trec la următoarea").replacen("{}", &track.title, 1));
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
        let key = cache_key(track);
        if !fresh {
            let cached = {
                let urls = self.urls.lock().unwrap();
                urls.get(&track.id).filter(|r| r.until > Instant::now()).map(|r| (r.url.clone(), r.user_agent.clone()))
            };
            if let Some((url, ua)) = cached {
                return Ok(http_source(url, &key, ua));
            }
            // A URL handed over by another device is fresh enough to start at once.
            if let Some(url) = track.stream_url.as_deref().filter(|u| u.starts_with("https://")) {
                return Ok(http_source(url.to_string(), &key, None));
            }
            if self.audio.cache().is_complete(&key) {
                // Fully cached: the URL is never used.
                return Ok(Source::http("https://offline.invalid/", &key));
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
        Ok(http_source(stream.url, &key, stream.user_agent))
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
                now.next_source = Some((track.id.clone(), source.clone()));
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
        if self.now.lock().unwrap().loading.is_some() {
            // Still the song before: a song picked a moment ago would be skipped or replaced.
            return;
        }
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
                self.update_pair();
                self.automix_tick(position_ms);
                self.update_mix_point();
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
                    if let Some((_, s)) = now.next_source.take().filter(|(_, s)| s.key() == key) {
                        now.current_source = Some(s);
                    }
                }
                self.automix.lock().unwrap().sent = None;
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
                    self.say(&crate::lang::pick("Impossibile riprodurre “{}”: passo al prossimo", "Can't play “{}”: skipping to the next one", "Nu pot reda „{}”: trec la următoarea").replacen("{}", &track.title, 1));
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

    /// Automix, on every position tick: from 150 s before the end, the playing song's tail and
    /// the next song's head are analysed (from the sources playback uses); once both are in, the
    /// plan (or none) goes to the engine, once per pair.
    fn automix_tick(&self, position_ms: i64) {
        let pair = self.pair_transition();
        if !self.automix_wanted(pair.as_ref()) {
            return;
        }
        if pair.is_some_and(|t| t.is_none()) {
            return;
        }
        let (cur, next, duration, cur_src, next_src) = {
            let now = self.now.lock().unwrap();
            let next = now.queue.after_end().filter(|i| *i != now.queue.index).and_then(|i| now.queue.tracks.get(i as usize).cloned());
            let next_src = next.as_ref().and_then(|n| now.next_source.as_ref().filter(|(id, _)| *id == n.id).map(|(_, s)| s.clone()));
            (now.queue.current().cloned(), next, now.duration_ms, now.current_source.clone(), next_src)
        };
        let (Some(cur), Some(next), Some(cur_src), Some(next_src)) = (cur, next, cur_src, next_src) else { return };
        if duration < lfly_mix::planner::MIN_SONG_MS as i64 || position_ms < duration - crate::automix::ANALYSIS_LEAD_MS {
            return;
        }
        let pair_ids = (cur.id.clone(), next.id.clone());
        let cache = self.audio.cache().clone();
        let (tail, head) = {
            let mut a = self.automix.lock().unwrap();
            if a.sent.as_ref() == Some(&pair_ids) {
                return;
            }
            let (cl, nl) = (crate::automix::source_len(&cur_src, &cache), crate::automix::source_len(&next_src, &cache));
            (a.get(&cur.id, crate::automix::Part::Tail, cl), a.get(&next.id, crate::automix::Part::Head, nl))
        };
        use crate::automix::{Automix, Part};
        if tail.is_none() {
            Automix::request(&self.automix, cur.id.clone(), Part::Tail, cur_src.clone(), cache.clone(), duration, || {});
        }
        if head.is_none() {
            Automix::request(&self.automix, next.id.clone(), Part::Head, next_src.clone(), cache, next.duration_ms, || {});
        }
        let (Some(tail), Some(head)) = (tail, head) else { return };
        let fade_ms = self.now.lock().unwrap().fade_ms;
        let tail_beat = tail.grid.as_ref().map(|g| g.beat_seconds());
        let plan = {
            let mut a = self.automix.lock().unwrap();
            a.sent = Some(pair_ids);
            let plan = match crate::playlist_mix::change(pair.as_ref(), duration, tail_beat, fade_ms) {
                Change::Plain => a.plan((&cur.id, &tail, duration), (&next.id, &head)),
                Change::Beat { fixes, recipe } => a.plan_with((&cur.id, &tail, duration), (&next.id, &head), fixes.as_ref(), recipe),
                Change::Unmatched { exit_ms, entry_ms, mix_ms, recipe } => {
                    Some(a.unmatched((&cur.id, Some(&tail)), (&next.id, Some(&head)), exit_ms, entry_ms, mix_ms, recipe))
                }
                Change::NoFade => None,
            };
            let beat = pair.is_none_or(|t| t.beatmatch);
            a.planned = plan.as_ref().map(|p| (p.exit_ms as i64, (p.exit_ms + p.mix_ms) as i64, beat));
            plan
        };
        match plan {
            Some(mut p) => {
                p.outgoing_key = cur_src.key();
                p.incoming_key = next_src.key();
                self.audio.set_automix(Some(p));
            }
            None => log::info!("Automix: '{}' -> '{}' cannot be mixed on the beat: a crossfade", cur.title, next.title),
        }
    }

    /// Where the coming change sits on the playing song (the phone's `updateMixPoint`): the
    /// Automix plan once both edges are analysed, else the crossfade; told to the sink when it
    /// moved. Locks one thing at a time.
    fn update_mix_point(&self) {
        let (pair, duration, position, fade_ms) = {
            let mut now = self.now.lock().unwrap();
            let position = now.position(self.audio.position_ms());
            let cur = now.queue.current().cloned();
            let next = now.queue.after_end().filter(|i| *i != now.queue.index).and_then(|i| now.queue.tracks.get(i as usize).map(|t| t.id.clone()));
            let duration = if now.duration_ms > 0 { now.duration_ms } else { cur.as_ref().map_or(0, |t| t.duration_ms) };
            (cur.map(|c| c.id).zip(next), duration, position, now.fade_ms)
        };
        let point = pair.and_then(|(cur, next)| {
            let plan = {
                let on = self.automix_wanted(self.pair_transition().as_ref());
                let a = self.automix.lock().unwrap();
                a.planned.filter(|_| on && a.sent.as_ref().is_some_and(|(o, i)| *o == cur && *i == next))
            };
            mix_span(duration, position, plan, fade_ms).map(|(start_ms, end_ms, beat_matched)| MixPoint { track_id: cur, start_ms, end_ms, beat_matched })
        });
        {
            let mut last = self.mix_point.lock().unwrap();
            if *last == point {
                return;
            }
            last.clone_from(&point);
        }
        self.sink.mix_point(point.as_ref());
    }

    /// Tells the window, and the controlling devices too when [important] (not every tick).
    fn changed(&self, important: bool) {
        self.update_mix_point();
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

/// The name a song's audio is kept under in the disk cache. A song with a YouTube id is that
/// recording; one known only by name (a Spotify import) is found by matching, and audio cached
/// before the matching picked the same recording (a remix for the original) must not play again:
/// its key carries the matching's version.
fn cache_key(track: &TrackRef) -> String {
    let raw = track.id.strip_prefix("yt_").unwrap_or(&track.id);
    if lfly_yt::parse::is_valid_video_id(raw) {
        track.id.clone()
    } else {
        format!("{}#match3", track.id)
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

#[cfg(test)]
mod tests {
    use super::mix_span;

    #[test]
    fn mix_span_is_the_plan_then_the_engine_fade() {
        // A plan holds until the song reaches its exit.
        assert_eq!(mix_span(300_000, 100_000, Some((272_000, 298_000, true)), 5_000), Some((272_000, 298_000, true)));
        // A playlist's change off the beat says so.
        assert_eq!(mix_span(300_000, 100_000, Some((250_000, 260_000, false)), 5_000), Some((250_000, 260_000, false)));
        // Past it the engine fades: the crossfade's span at the end.
        assert_eq!(mix_span(300_000, 280_000, Some((272_000, 298_000, true)), 5_000), Some((295_000, 300_000, false)));
        // No plan: the fade, never more than a third of the song.
        assert_eq!(mix_span(300_000, 0, None, 12_000), Some((288_000, 300_000, false)));
        assert_eq!(mix_span(9_000, 0, None, 12_000), Some((6_000, 9_000, false)));
        // Under a second (or no crossfade, or no length) the engine just changes song.
        assert_eq!(mix_span(2_400, 0, None, 12_000), None);
        assert_eq!(mix_span(300_000, 0, None, 0), None);
        assert_eq!(mix_span(0, 0, None, 5_000), None);
    }
}
