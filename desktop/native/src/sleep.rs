//! The phone's sleep timer (playback/SleepTimer.kt, offered in the full player's menu): the music
//! pauses after some minutes or when the song playing ends. It pauses wherever the music plays,
//! here or on the device this computer controls.

use std::time::{Duration, Instant};

use gpui_kit::*;
use lfly_connect::protocol;

use crate::player_model::{PlayerEvent, PlayerModel};
use crate::toast::Toasts;

/// SleepTimer.PRESET_MINUTES.
pub const PRESET_MINUTES: [u64; 5] = [15, 30, 45, 60, 90];

#[derive(Clone, PartialEq)]
pub enum Sleep {
    Off,
    At(Instant),
    /// Pauses when the song with this id stops being the one playing, where it plays (here, or
    /// the device this computer controlled when it was set).
    EndOfTrack(String, Option<String>),
}

pub struct SleepTimer {
    model: Entity<PlayerModel>,
    pub v: Sleep,
    seq: u64,
    _sub: Subscription,
}

struct SleepGlobal(Entity<SleepTimer>);
impl Global for SleepGlobal {}

impl SleepTimer {
    pub fn start(model: Entity<PlayerModel>, cx: &mut App) {
        let e = cx.new(|cx| {
            let sub = cx.subscribe(&model, |this: &mut Self, model, ev: &PlayerEvent, cx| {
                if !matches!(ev, PlayerEvent::StateChanged) {
                    return;
                }
                if let Sleep::EndOfTrack(id, place) = &this.v {
                    let m = model.read(cx);
                    // The music moved (here, there): another song is not the end of this one.
                    if &m.remote != place {
                        this.cancel(cx);
                        return;
                    }
                    match m.current().map(|t| t.id.clone()) {
                        Some(now) if &now != id => this.fire(cx),
                        _ => {}
                    }
                }
            });
            Self { model, v: Sleep::Off, seq: 0, _sub: sub }
        });
        // LFLY_SLEEP_AFTER=<seconds> (tests): a timer that short, from the start.
        if let Some(secs) = std::env::var("LFLY_SLEEP_AFTER").ok().and_then(|v| v.parse::<f64>().ok()) {
            e.update(cx, |t, cx| t.set_for(Duration::from_secs_f64(secs), cx));
        }
        cx.set_global(SleepGlobal(e));
    }

    pub fn entity(cx: &App) -> Entity<Self> {
        cx.global::<SleepGlobal>().0.clone()
    }

    fn fire(&mut self, cx: &mut Context<Self>) {
        log::info!("sleep timer: pause");
        self.v = Sleep::Off;
        self.seq += 1;
        let m = self.model.read(cx);
        if m.playing() {
            m.command(protocol::CMD_PAUSE, 0);
        }
        Toasts::say(crate::i18n::tr("Timer di spegnimento: musica in pausa"), cx);
        cx.notify();
    }

    pub fn set_minutes(&mut self, minutes: u64, cx: &mut Context<Self>) {
        self.set_for(Duration::from_secs(minutes * 60), cx);
        Toasts::say(crate::i18n::trf("La musica si ferma tra {0}", &[&crate::i18n::minutes(minutes)]), cx);
    }

    fn set_for(&mut self, d: Duration, cx: &mut Context<Self>) {
        self.seq += 1;
        let mine = self.seq;
        self.v = Sleep::At(Instant::now() + d);
        cx.spawn(async move |this, cx| {
            cx.background_executor().timer(d).await;
            let _ = this.update(cx, |this, cx| {
                if this.seq == mine {
                    this.fire(cx);
                }
            });
        })
        .detach();
        cx.notify();
    }

    pub fn set_end_of_track(&mut self, cx: &mut Context<Self>) {
        let m = self.model.read(cx);
        let Some(id) = m.current().map(|t| t.id.clone()) else { return };
        let place = m.remote.clone();
        self.seq += 1;
        self.v = Sleep::EndOfTrack(id, place);
        Toasts::say(crate::i18n::tr("Si ferma alla fine di questo brano"), cx);
        cx.notify();
    }

    pub fn cancel(&mut self, cx: &mut Context<Self>) {
        self.seq += 1;
        self.v = Sleep::Off;
        cx.notify();
    }

    /// The menu row's value: the time left ("12:05", "1:02:30"), or "Fine del brano".
    pub fn label(&self) -> Option<String> {
        match &self.v {
            Sleep::Off => None,
            Sleep::EndOfTrack(..) => Some(crate::i18n::tr("Fine del brano").into()),
            Sleep::At(t) => Some(countdown(t.saturating_duration_since(Instant::now()))),
        }
    }
}

/// TrackActionsSheet.kt `formatCountdown`.
pub fn countdown(left: Duration) -> String {
    let s = left.as_millis().div_ceil(1000) as u64;
    let (h, m, s) = (s / 3600, (s % 3600) / 60, s % 60);
    if h > 0 {
        format!("{h}:{m:02}:{s:02}")
    } else {
        format!("{m}:{s:02}")
    }
}

#[cfg(test)]
mod tests {
    // Not `super::*`: gpui's `test` attribute would shadow std's.
    use super::countdown;
    use std::time::Duration;

    #[test]
    fn counts_down_like_the_phone() {
        assert_eq!(countdown(Duration::from_millis(14 * 60_000 + 59_001)), "15:00");
        assert_eq!(countdown(Duration::from_secs(3723)), "1:02:03");
        assert_eq!(countdown(Duration::ZERO), "0:00");
    }
}
