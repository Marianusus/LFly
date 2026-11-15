//! The DJ session (the phone's `DjSession`), without the voice for now: the line is shown as a
//! caption. It builds a block, queues it, says its line when the block's first song plays, and
//! builds the next block while the last queued song plays, so Automix can mix into it.
//!
//! One task on the runtime owns the session and takes its messages in order (start, stop, the
//! player's states, finished blocks), so it never calls the player while the player is telling
//! it something. A block is built in its own task, since it asks YouTube Music a few times; it
//! carries the session's number, and a block for a session that is over is dropped.

use std::collections::{HashMap, HashSet};
use std::path::PathBuf;
use std::sync::{Arc, Weak};

use lfly_connect::protocol::{self, State, TrackRef};
use lfly_yt::YtMusic;
use rand::rngs::StdRng;
use rand::{Rng, SeedableRng};
use serde_json::Value;
use tokio::sync::mpsc;

use super::blocks::{self, MAX_TRACKS, MIN_TRACKS, RECENT_HISTORY};
use super::flow::{self, unknown};
use super::script::Script;
use super::{track_key, Block, BlockKind, Exclusions, Library};
use crate::player::LocalPlayer;

/// What the window shows about the DJ.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct DjState {
    pub active: bool,
    /// Building the first block.
    pub loading: bool,
    /// The block playing now.
    pub block: Option<Block>,
    /// The last line the DJ said, and a number that changes with every line.
    pub line: Option<(String, u64)>,
    /// Why the DJ could not start, once.
    pub notice: Option<String>,
}

pub type DjSink = Arc<dyn Fn(DjState) + Send + Sync>;

enum Msg {
    Start,
    Stop,
    Player(Box<State>),
    Position(i64),
    Built { session: u64, first: bool, block: Option<Block> },
}

/// The DJ: start it, stop it, and give it every state of the local player.
pub struct DjSession {
    tx: mpsc::UnboundedSender<Msg>,
}

impl DjSession {
    /// The session's task on `rt`. `library_dir`: the library files (None: the library's own).
    /// `language`: what the DJ speaks ("it", "en", "ro").
    pub fn spawn(rt: &tokio::runtime::Handle, player: Weak<LocalPlayer>, yt: YtMusic, library_dir: Option<PathBuf>, language: &'static str, sink: DjSink) -> Arc<Self> {
        let (tx, rx) = mpsc::unbounded_channel();
        let me = Arc::new(DjSession { tx: tx.clone() });
        let actor = Actor { tx, player, yt, library_dir, language, sink, rng: StdRng::from_entropy(), session: 0, s: Session::default(), building: false };
        rt.spawn(actor.run(rx));
        me
    }

    pub fn start(&self) {
        let _ = self.tx.send(Msg::Start);
    }

    pub fn stop(&self) {
        let _ = self.tx.send(Msg::Stop);
    }

    /// A state of the local player (never a remote device's).
    pub fn player_state(&self, state: &State) {
        let _ = self.tx.send(Msg::Player(Box::new(state.clone())));
    }

    /// The local player's position tick: a block's line waits until its first song really plays.
    pub fn player_position(&self, ms: i64) {
        let _ = self.tx.send(Msg::Position(ms));
    }
}

/// One session's memory.
#[derive(Default)]
struct Session {
    active: bool,
    loading: bool,
    script: Script,
    blocks_by_id: HashMap<String, Block>,
    blocks_by_key: HashMap<String, Block>,
    announced: HashSet<String>,
    session_tracks: Vec<TrackRef>,
    first_block_start: Option<String>,
    last_kind: Option<BlockKind>,
    last_artist: Option<String>,
    last_mix: Option<String>,
    block: Option<Block>,
    line: Option<(String, u64)>,
    lines: u64,
    /// A block whose first song is on but not heard yet: its line comes once it plays.
    pending: Option<Block>,
    /// The song playing now, by id.
    current: Option<String>,
    /// The last position tick, and whether the song now on has been heard starting (its
    /// position went back, or began near 0): the old song keeps playing while a new one loads.
    last_tick: Option<i64>,
    started: bool,
    /// A state with the DJ's own queue was seen: only then can a queue without its songs stop it.
    seen_own: bool,
}

/// Lets the new song be heard for a moment before the DJ talks over it (the phone's SETTLE_MS).
const SETTLE_MS: i64 = 600;

/// One position tick: true once the song now on has really played [`SETTLE_MS`]. It counts as
/// started when its position went back from the last tick or is still under that, since the song
/// before keeps sending ticks while the new one loads.
fn heard(last_tick: Option<i64>, ms: i64, started: &mut bool) -> bool {
    if !*started && (ms < SETTLE_MS || last_tick.is_some_and(|l| ms < l)) {
        *started = true;
    }
    *started && ms >= SETTLE_MS
}

impl Session {
    fn block_of(&self, track: &TrackRef) -> Option<&Block> {
        self.blocks_by_id.get(&track.id).or_else(|| self.blocks_by_key.get(&track_key(track)))
    }

    fn record(&mut self, block: &Block) {
        if self.first_block_start.is_none() {
            self.first_block_start = block.tracks.first().map(|t| t.id.clone());
        }
        for t in &block.tracks {
            self.blocks_by_id.insert(t.id.clone(), block.clone());
            self.blocks_by_key.insert(track_key(t), block.clone());
        }
        self.session_tracks.extend(block.tracks.iter().cloned());
        self.last_kind = Some(block.kind);
        match block.kind {
            BlockKind::FollowedArtist => self.last_artist = block.subject.clone(),
            BlockKind::Mix => self.last_mix = block.subject.clone(),
            _ => {}
        }
    }
}

/// What a player state asks of the session.
#[derive(Debug, Default, PartialEq)]
struct Steps {
    /// The listener started something else.
    stop: bool,
    /// The playing song opens this block, not announced yet: say its line.
    announce: Option<Block>,
    /// One song (or none) is left after the playing one: build the next block.
    build: bool,
}

/// The decisions on one player state, pure.
fn steps(s: &Session, state: &State, building: bool) -> Steps {
    let queue = &state.queue;
    let current = usize::try_from(state.index).ok().and_then(|i| queue.get(i));
    // A queue the listener replaced holds none of the DJ's songs (once the DJ's own was seen: a
    // state of the old queue can still arrive after the first block is queued).
    let own = current.is_some_and(|c| s.block_of(c).is_some()) || queue.iter().any(|t| s.block_of(t).is_some());
    if !own {
        return Steps { stop: s.seen_own, ..Default::default() };
    }
    let Some(current) = current else { return Steps::default() };
    let announce = s.block_of(current).filter(|b| b.tracks.first().is_some_and(|f| f.id == current.id || track_key(f) == track_key(current))).filter(|b| !s.announced.contains(&b.tracks[0].id)).cloned();
    let left = queue.len() as i64 - 1 - state.index as i64;
    Steps { stop: false, announce, build: !building && left <= 1 }
}

struct Actor {
    tx: mpsc::UnboundedSender<Msg>,
    player: Weak<LocalPlayer>,
    yt: YtMusic,
    library_dir: Option<PathBuf>,
    language: &'static str,
    sink: DjSink,
    rng: StdRng,
    /// Bumped by every start and stop: a block built for another session is dropped.
    session: u64,
    s: Session,
    building: bool,
}

impl Actor {
    async fn run(mut self, mut rx: mpsc::UnboundedReceiver<Msg>) {
        while let Some(msg) = rx.recv().await {
            match msg {
                Msg::Start => self.start(),
                Msg::Stop => self.stop(),
                Msg::Player(state) => self.on_state(&state),
                Msg::Position(ms) => self.on_position(ms),
                Msg::Built { session, first, block } => self.built(session, first, block),
            }
        }
    }

    fn tell(&self) {
        (self.sink)(DjState { active: self.s.active, loading: self.s.loading, block: self.s.block.clone(), line: self.s.line.clone(), notice: None });
    }

    fn start(&mut self) {
        if self.s.active {
            return;
        }
        self.session += 1;
        self.s = Session { active: true, loading: true, ..Default::default() };
        self.building = true;
        // The DJ's songs always mix, as on the phone, whatever the Automix switch says.
        if let Some(p) = self.player.upgrade() {
            p.set_dj_mixing(true);
        }
        self.tell();
        self.build(None, true);
    }

    fn stop(&mut self) {
        if !self.s.active {
            return;
        }
        self.session += 1;
        self.s = Session::default();
        self.building = false;
        if let Some(p) = self.player.upgrade() {
            p.set_dj_mixing(false);
        }
        log::info!("DJ: stopped");
        self.tell();
    }

    /// Builds a block in its own task; `seed`: the last song queued (None: the first block).
    fn build(&mut self, seed: Option<TrackRef>, first: bool) {
        let state = self.player.upgrade().map(|p| p.state());
        // Before the first block only the song on now is off limits; afterwards the whole queue is.
        let queued: Vec<TrackRef> = match state {
            Some(st) if first => usize::try_from(st.index).ok().and_then(|i| st.queue.get(i).cloned()).into_iter().collect(),
            Some(st) => st.queue,
            None => Vec::new(),
        };
        let job = Build {
            yt: self.yt.clone(),
            library_dir: self.library_dir.clone(),
            seed,
            taken: self.s.session_tracks.iter().cloned().chain(queued).collect(),
            last_kind: self.s.last_kind,
            last_artist: self.s.last_artist.clone(),
            last_mix: self.s.last_mix.clone(),
            probe_first: first,
            rng: StdRng::seed_from_u64(self.rng.gen()),
        };
        let (tx, session) = (self.tx.clone(), self.session);
        tokio::spawn(async move {
            let block = job.run().await;
            let _ = tx.send(Msg::Built { session, first, block });
        });
    }

    fn built(&mut self, session: u64, first: bool, block: Option<Block>) {
        if session != self.session || !self.s.active {
            return;
        }
        self.building = false;
        let Some(block) = block else {
            log::warn!("DJ: no block to play after {:?}", self.s.last_kind);
            if first {
                self.stop();
                (self.sink)(DjState {
                    notice: Some(crate::lang::pick("Il DJ non ha trovato abbastanza brani: metti il cuore a qualche canzone e riprova", "The DJ found too few songs: heart a few and try again", "DJ-ul nu a găsit destule melodii: dă inimă câtorva și încearcă din nou").into()),
                    ..Default::default()
                });
            }
            return;
        };
        log::info!(
            "DJ: next block {:?}{}: {}",
            block.kind,
            block.subject.as_deref().map(|s| format!(" ({s})")).unwrap_or_default(),
            block.tracks.iter().map(|t| t.title.as_str()).collect::<Vec<_>>().join(" -> ")
        );
        self.s.record(&block);
        let Some(player) = self.player.upgrade() else { return };
        // The tracks the library keeps can carry a stream URL from an old hand-over: resolved again.
        let tracks: Vec<TrackRef> = block.tracks.iter().cloned().map(|t| TrackRef { stream_url: None, ..t }).collect();
        if first {
            self.s.loading = false;
            self.s.block = Some(block);
            self.tell();
            player.play_queue(tracks, 0, 0, true);
        } else {
            player.edit_queue(protocol::EDIT_ADD, tracks, 0, 0);
        }
    }

    fn on_position(&mut self, ms: i64) {
        let last = self.s.last_tick.replace(ms);
        if !self.s.active || !heard(last, ms, &mut self.s.started) {
            return;
        }
        let Some(block) = self.s.pending.take() else { return };
        let first = self.s.first_block_start.as_deref() == Some(block.tracks[0].id.as_str());
        let line = self.s.script.line(&block, crate::lang::get().unwrap_or(self.language), first, &mut self.rng);
        log::info!("DJ says: {line}");
        self.s.lines += 1;
        self.s.line = Some((line, self.s.lines));
        self.s.block = Some(block);
        self.tell();
    }

    fn on_state(&mut self, state: &State) {
        if !self.s.active || self.s.loading {
            return;
        }
        let steps = steps(&self.s, state, self.building);
        if !steps.stop && usize::try_from(state.index).ok().and_then(|i| state.queue.get(i)).is_some_and(|c| self.s.block_of(c).is_some()) {
            self.s.seen_own = true;
        }
        if steps.stop {
            log::info!("DJ: the listener moved on from the DJ's queue");
            self.stop();
            return;
        }
        let current = usize::try_from(state.index).ok().and_then(|i| state.queue.get(i)).map(|t| t.id.clone());
        if current != self.s.current {
            // Another song: a line still waiting for the one before is not said.
            self.s.pending = None;
            self.s.current = current;
            self.s.started = false;
        }
        if let Some(block) = steps.announce {
            self.s.announced.insert(block.tracks[0].id.clone());
            self.s.pending = Some(block);
        }
        if steps.build {
            self.building = true;
            let seed = state.queue.last().cloned();
            self.build(seed, false);
        }
    }
}

/// What a block is built from, taken when the build starts.
struct Build {
    yt: YtMusic,
    library_dir: Option<PathBuf>,
    seed: Option<TrackRef>,
    taken: Vec<TrackRef>,
    last_kind: Option<BlockKind>,
    last_artist: Option<String>,
    last_mix: Option<String>,
    /// The first block starts at once (the phone probes tempos only for later ones; this one never does yet).
    #[allow(dead_code)]
    probe_first: bool,
    rng: StdRng,
}

impl Build {
    async fn run(mut self) -> Option<Block> {
        let library = self.library();
        let recent = &library.history[..library.history.len().min(RECENT_HISTORY)];
        let exclusions = Exclusions::new([], [], self.taken.clone(), recent);
        if let Some(flow) = self.flow(&library, &exclusions).await {
            return Some(flow);
        }
        let available = blocks::available_kinds(&library, &exclusions);
        for kind in blocks::kind_order(self.last_kind, &available) {
            let block = match kind {
                BlockKind::Discovery => {
                    let start = self.seed.clone().or_else(|| blocks::seed_for(&library).cloned());
                    match start {
                        Some(s) => {
                            let candidates = self.recommend(&s).await;
                            blocks::block(kind, &candidates, &exclusions, &mut self.rng, None)
                        }
                        None => None,
                    }
                }
                // The desktop keeps no followed artists yet: never available.
                BlockKind::FollowedArtist => None,
                _ => blocks::local_block(kind, &library, &exclusions, &mut self.rng, self.last_mix.as_deref()),
            };
            if block.is_some() {
                return block;
            }
        }
        let _ = &self.last_artist;
        None
    }

    /// Like a DJ working a set: it opens on one of the listener's favourites (the one that follows
    /// best from the song before) and lets each next song grow out of the one before, asking
    /// YouTube Music what fits it. No tempo or key is known here yet, so the flow goes by the
    /// engine's order and variety.
    async fn flow(&mut self, library: &Library, exclusions: &Exclusions) -> Option<Block> {
        let all: Vec<TrackRef> = library.top_played.iter().chain(&library.favorites).cloned().collect();
        let favourites = blocks::usable(BlockKind::TopPlayed, &all, exclusions);
        let anchor = flow::pick_anchor(&favourites, self.seed.as_ref(), &mut self.rng, &unknown)?;
        let mut chain = vec![anchor];
        let size = self.rng.gen_range(MIN_TRACKS..=MAX_TRACKS);
        while chain.len() < size {
            let taken = exclusions.with_session(&chain);
            let last = chain.last().expect("the anchor").clone();
            let candidates = blocks::usable(BlockKind::Discovery, &self.recommend(&last).await, &taken);
            let recent: Vec<String> = chain.iter().rev().take(3).rev().map(|t| t.artist.clone()).collect();
            let Some(next) = flow::pick_next(&last, &candidates, &recent, &mut self.rng, &unknown) else { break };
            chain.push(next);
        }
        (chain.len() >= MIN_TRACKS).then_some(Block { kind: BlockKind::Flow, tracks: chain, subject: None })
    }

    /// What YouTube Music plays after `seed` (its radio), as the DJ's candidates.
    async fn recommend(&self, seed: &TrackRef) -> Vec<TrackRef> {
        match self.yt.up_next(&seed.id).await {
            Ok(found) => found.iter().filter_map(|t| serde_json::to_value(t).ok().and_then(|v| serde_json::from_value::<TrackRef>(v).ok())).filter(|t| t.id != seed.id).collect(),
            Err(e) => {
                log::warn!("DJ: songs after '{}': {e}", seed.title);
                Vec::new()
            }
        }
    }

    /// The listener's songs: favourites, then the history. The desktop keeps no play counts, so
    /// "top played" is the favourites first and the latest listens after them.
    fn library(&self) -> Library {
        let dir = self.library_dir.clone().or_else(|| crate::library::dir().ok());
        let Some(dir) = dir else { return Library::default() };
        let lib = crate::library::load(&dir);
        let tracks = |v: &Value| -> Vec<TrackRef> {
            v.as_array().into_iter().flatten().filter_map(|t| serde_json::from_value::<TrackRef>(t.clone()).ok()).filter(|t| !t.id.is_empty()).collect()
        };
        let favorites = tracks(&lib.favorites);
        let history = tracks(&lib.history);
        Library { top_played: favorites.iter().chain(&history).cloned().collect(), favorites, history, ..Default::default() }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::dj::testing::track;

    fn state(queue: Vec<TrackRef>, index: i32) -> State {
        let mut s: State = serde_json::from_value(serde_json::json!({})).expect("a default state");
        s.queue = queue;
        s.index = index;
        s
    }

    fn session_with(blocks: &[Block]) -> Session {
        let mut s = Session { active: true, ..Default::default() };
        for b in blocks {
            s.record(b);
        }
        s
    }

    fn songs(prefix: &str, n: usize) -> Vec<TrackRef> {
        (1..=n).map(|i| track(&format!("{prefix}{i}"), &format!("Song {prefix}{i}"), &format!("Artist {prefix}{i}"))).collect()
    }

    #[test]
    fn the_first_song_of_a_block_is_announced_once() {
        let a = Block { kind: BlockKind::TopPlayed, tracks: songs("a", 4), subject: None };
        let mut s = session_with(std::slice::from_ref(&a));
        let st = state(a.tracks.clone(), 0);
        assert_eq!(steps(&s, &st, false).announce, Some(a.clone()));
        s.announced.insert(a.tracks[0].id.clone());
        assert_eq!(steps(&s, &st, false).announce, None);
        // Not the opening song: nothing to say.
        assert_eq!(steps(&s, &state(a.tracks.clone(), 1), false).announce, None);
    }

    #[test]
    fn the_next_block_is_built_when_one_song_is_left() {
        let a = Block { kind: BlockKind::TopPlayed, tracks: songs("a", 4), subject: None };
        let s = session_with(std::slice::from_ref(&a));
        assert!(!steps(&s, &state(a.tracks.clone(), 1), false).build);
        assert!(steps(&s, &state(a.tracks.clone(), 2), false).build);
        assert!(steps(&s, &state(a.tracks.clone(), 3), false).build);
        // Once: a build under way is not started again.
        assert!(!steps(&s, &state(a.tracks.clone(), 3), true).build);
    }

    #[test]
    fn a_line_waits_for_the_new_song_to_play() {
        // The old song at 1:30 keeps ticking while the new one loads: not heard yet.
        let mut started = false;
        assert!(!heard(Some(89_000), 90_000, &mut started));
        // The new one starts at 0, then passes the settle time.
        assert!(!heard(Some(90_000), 0, &mut started));
        assert!(heard(Some(0), 700, &mut started));
        // Through an Automix the new song comes in at its entry, behind the old song's end.
        let mut started = false;
        assert!(heard(Some(271_000), 2_800, &mut started));
    }

    #[test]
    fn a_queue_the_listener_replaced_stops_the_dj() {
        let a = Block { kind: BlockKind::Discovery, tracks: songs("a", 4), subject: None };
        let mut s = session_with(std::slice::from_ref(&a));
        // The listener's old queue, before the DJ's was seen: not a stop.
        assert!(!steps(&s, &state(songs("old", 3), 0), false).stop);
        s.seen_own = true;
        assert!(steps(&s, &state(songs("other", 5), 0), false).stop);
        assert!(steps(&s, &state(vec![], -1), false).stop);
        // A song the listener added in the DJ's queue does not stop it.
        let mut mixed = a.tracks.clone();
        mixed.insert(1, track("mine", "Mine", "Me"));
        assert!(!steps(&s, &state(mixed, 1), false).stop);
    }

    #[test]
    fn the_same_song_from_another_source_still_counts() {
        let a = Block { kind: BlockKind::Flow, tracks: songs("a", 4), subject: None };
        let s = session_with(std::slice::from_ref(&a));
        let other_source = TrackRef { id: "mono_1".into(), ..a.tracks[0].clone() };
        let mut q = a.tracks.clone();
        q[0] = other_source;
        let got = steps(&s, &state(q, 0), false);
        assert!(!got.stop);
        assert_eq!(got.announce, Some(a));
    }
}
