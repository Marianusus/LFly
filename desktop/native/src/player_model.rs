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
}

pub struct PlayerModel {
    pub core: Arc<Core>,
    pub state: Option<State>,
    pub position_ms: u64,
    /// When [position_ms] arrived: the lyrics carry it forward between the 4 Hz samples.
    pub position_at: std::time::Instant,
    /// The window is in front: position ticks redraw only then.
    pub active: bool,
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
        Self { core, state: None, position_ms: 0, position_at: std::time::Instant::now(), active: true }
    }

    fn apply(&mut self, update: PlayerUpdate, cx: &mut Context<Self>) {
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
                // Behind a game nobody reads the bar: no frame at all.
                if self.active {
                    cx.notify();
                }
            }
            PlayerUpdate::Notice(text) => cx.emit(PlayerEvent::Notice(text.into())),
        }
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

    /// The position now (player.svelte.ts `livePosition`): the last sample, carried forward
    /// while playing.
    pub fn live_position(&self) -> f64 {
        let p = self.position_ms as f64;
        if !self.playing() {
            return p;
        }
        let d = self.duration_ms() as f64;
        let p = p + self.position_at.elapsed().as_secs_f64() * 1000.;
        if d > 0. { p.min(d) } else { p }
    }

    pub fn command(&self, action: &str, value: i64) {
        self.core.command(action, value);
    }
}
