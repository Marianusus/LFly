//! The player as the window sees it: the shared player's last state and the position.
//!
//! Its own entity, so the 4 Hz position ticks redraw only the views that show the position
//! (they `observe` it); the rest of the window listens to `StateChanged` only.

use std::sync::Arc;

use gpui_kit::*;
use lfly_connect::protocol::{State, TrackRef};

use crate::core::{Core, PlayerUpdate};

pub enum PlayerEvent {
    /// Song, queue, play/pause or volume changed (not the position).
    StateChanged,
    /// A short message for the person.
    Notice(SharedString),
    /// Another device handed its music to this computer.
    Arrived,
    /// The window came to the front or went behind (`active`): what moves starts or stops.
    Activity,
}

pub struct PlayerModel {
    pub core: Arc<Core>,
    pub state: Option<State>,
    pub position_ms: u64,
    /// When [position_ms] arrived: the lyrics carry it forward between the 4 Hz samples.
    pub position_at: std::time::Instant,
    /// The window is on screen (in front or not): position ticks redraw only then.
    pub active: bool,
    /// The device this computer controls on LFly Connect (its name): the state shown is its own.
    pub remote: Option<String>,
    /// The local player's last state while a remote one is shown.
    local: Option<(State, u64)>,
}

impl EventEmitter<PlayerEvent> for PlayerModel {}

impl PlayerModel {
    pub fn new(
        core: Arc<Core>,
        mut updates: tokio::sync::mpsc::UnboundedReceiver<PlayerUpdate>,
        cx: &mut Context<Self>,
    ) -> Self {
        // The receiver works on any executor: a foreground task feeds the entity.
        cx.spawn(async move |this, cx| {
            while let Some(update) = updates.recv().await {
                if this.update(cx, |this, cx| this.apply(update, cx)).is_err() {
                    break;
                }
            }
        })
        .detach();
        Self { core, state: None, position_ms: 0, position_at: std::time::Instant::now(), active: true, remote: None, local: None }
    }

    fn apply(&mut self, update: PlayerUpdate, cx: &mut Context<Self>) {
        // Controlling another device: the local player is kept aside, shown again after.
        if self.remote.is_some() {
            match update {
                PlayerUpdate::State(state) => {
                    let p = state.position_ms.max(0) as u64;
                    self.local = Some((*state, p));
                }
                PlayerUpdate::Position { ms } => {
                    if let Some(l) = self.local.as_mut() {
                        l.1 = ms.max(0) as u64;
                    }
                }
                PlayerUpdate::Notice(text) => cx.emit(PlayerEvent::Notice(text.into())),
                PlayerUpdate::Arrived => cx.emit(PlayerEvent::Arrived),
            }
            return;
        }
        match update {
            PlayerUpdate::State(state) => {
                self.position_ms = state.position_ms.max(0) as u64;
                self.position_at = std::time::Instant::now();
                self.state = Some(*state);
                cx.emit(PlayerEvent::StateChanged);
                cx.notify();
            }
            PlayerUpdate::Position { ms } => {
                self.position_ms = ms.max(0) as u64;
                self.position_at = std::time::Instant::now();
                // Off screen (minimized, suspended) nobody reads the bar: no frame at all.
                if self.active {
                    cx.notify();
                }
            }
            PlayerUpdate::Notice(text) => cx.emit(PlayerEvent::Notice(text.into())),
            PlayerUpdate::Arrived => cx.emit(PlayerEvent::Arrived),
        }
    }

    /// Starts or stops showing the device this computer controls ([name] None: back here).
    pub fn set_remote(&mut self, name: Option<String>, cx: &mut Context<Self>) {
        match (&self.remote, &name) {
            (None, Some(_)) => {
                self.local = self.state.take().map(|s| (s, self.position_ms));
                // The device reports every few seconds: the bar moves on by itself in between,
                // four times a second as here (connect.svelte.ts ticks at 250 ms).
                cx.spawn(async move |this, cx| loop {
                    cx.background_executor().timer(std::time::Duration::from_millis(250)).await;
                    let go_on = this
                        .update(cx, |m, cx| {
                            if m.remote.is_none() {
                                return false;
                            }
                            if m.playing() {
                                // The bar draws the sample: carry it forward, as local ticks do.
                                m.position_ms = m.live_position().max(0.) as u64;
                                m.position_at = std::time::Instant::now();
                                if m.active {
                                    cx.notify();
                                }
                            }
                            true
                        })
                        .unwrap_or(false);
                    if !go_on {
                        break;
                    }
                })
                .detach();
            }
            (Some(_), None) => {
                // The local player's latest, or what it says now.
                let (state, p) = self.local.take().unwrap_or_else(|| {
                    let s = self.core.player.state();
                    let p = s.position_ms.max(0) as u64;
                    (s, p)
                });
                self.state = Some(state);
                self.position_ms = p;
                self.position_at = std::time::Instant::now();
            }
            _ => {}
        }
        self.remote = name;
        cx.emit(PlayerEvent::StateChanged);
        cx.notify();
    }

    /// What plays on the device this computer controls, with its playhead now.
    pub fn apply_remote(&mut self, state: State, position_ms: i64, cx: &mut Context<Self>) {
        if self.remote.is_none() {
            return;
        }
        self.position_ms = position_ms.max(0) as u64;
        self.position_at = std::time::Instant::now();
        self.state = Some(state);
        cx.emit(PlayerEvent::StateChanged);
        cx.notify();
    }

    pub fn current(&self) -> Option<&TrackRef> {
        let s = self.state.as_ref()?;
        s.queue.get(usize::try_from(s.index).ok()?)
    }

    pub fn playing(&self) -> bool {
        self.state.as_ref().is_some_and(|s| s.playing)
    }

    pub fn duration_ms(&self) -> u64 {
        let d = self.state.as_ref().map_or(0, |s| s.duration_ms);
        let d = if d > 0 { d } else { self.current().map_or(0, |t| t.duration_ms) };
        d.max(0) as u64
    }

    /// The position now (player.svelte.ts `livePosition`): the last sample, carried forward at the
    /// playback speed while playing.
    pub fn live_position(&self) -> f64 {
        let p = self.position_ms as f64;
        if !self.playing() {
            return p;
        }
        let d = self.duration_ms() as f64;
        // At this computer's speed (a controlled device plays at its own, 1×).
        let speed = if self.remote.is_none() { self.core.player.speed() as f64 } else { 1. };
        let p = p + self.position_at.elapsed().as_secs_f64() * 1000. * speed;
        if d > 0. { p.min(d) } else { p }
    }

    pub fn command(&self, action: &str, value: i64) {
        self.core.command(action, value);
    }
}
