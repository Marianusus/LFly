//! A DJ console over MIDI (class-compliant controllers): a thread finds the console and passes its
//! messages here, where a learned mapping (`midi_map`) turns them into what the player already
//! does. Settings → Dispositivi → Console DJ lists the actions and learns their controls.
//!
//! Test runs (`LFLY_MUTE`, `LFLY_AUTOPLAY`) never open a real device: with `LFLY_MIDI_TEST=<name>`
//! they take only a port whose name contains it, such as the virtual console that
//! `examples/midi_send.rs` plugs in (Linux), through the same scan a real console goes through.
//! `LFLY_MIDI_LEARN=<action>@<seconds>` starts learning an action then.

use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;

use gpui_kit::*;
use lfly_connect::protocol;

use crate::core::Core;
use crate::midi_map::{self, Action, Input, Mapping, Pickup};
use crate::player_model::PlayerModel;
use crate::toast::Toasts;

const STORE_KEY: &str = "midi";
/// How often the thread looks for a console (plugged in or out).
const SCAN: Duration = Duration::from_secs(2);
/// A tempo fader re-seeks the song (the engine's speed change): applied once it rests this long.
const TEMPO_REST: Duration = Duration::from_millis(250);

enum Msg {
    Device(Option<String>),
    Bytes(Vec<u8>),
}

pub struct MidiModel {
    core: Arc<Core>,
    model: Entity<PlayerModel>,
    pub mapping: Mapping,
    /// Waiting for a control for this action.
    pub learning: Option<Action>,
    /// The console's port, when one is open.
    pub device: Option<String>,
    pickups: HashMap<Action, Pickup>,
    /// The cue point: the song and where in it.
    cue: Option<(String, i64)>,
    tempo_seq: u64,
}

struct MidiGlobal(Entity<MidiModel>);
impl Global for MidiGlobal {}

fn test_run() -> bool {
    std::env::var_os("LFLY_MUTE").is_some() || std::env::var_os("LFLY_AUTOPLAY").is_some()
}

impl MidiModel {
    pub fn start(core: Arc<Core>, model: Entity<PlayerModel>, cx: &mut App) -> Entity<Self> {
        let mapping = lfly_core::store::get(STORE_KEY).ok().flatten().and_then(|v| serde_json::from_value(v).ok()).unwrap_or_default();
        let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<Msg>();
        let test_port = std::env::var("LFLY_MIDI_TEST").ok();
        // A test run touches no real device; without a test port it opens nothing at all.
        if !test_run() || test_port.is_some() {
            std::thread::Builder::new().name("lfly-midi".into()).spawn(move || scan(tx, test_port)).ok();
        }
        let e = cx.new(|cx: &mut Context<Self>| {
            cx.spawn(async move |this, cx| {
                while let Some(m) = rx.recv().await {
                    if this.update(cx, |me, cx| me.on_msg(m, cx)).is_err() {
                        break;
                    }
                }
            })
            .detach();
            if let Some((action, secs)) = std::env::var("LFLY_MIDI_LEARN").ok().and_then(|v| {
                let (a, s) = v.split_once('@')?;
                Some((Action::from_id(a)?, s.parse::<f64>().ok()?))
            }) {
                cx.spawn(async move |this, cx| {
                    cx.background_executor().timer(Duration::from_secs_f64(secs)).await;
                    let _ = this.update(cx, |me, cx| me.learn(Some(action), cx));
                })
                .detach();
            }
            Self { core, model, mapping, learning: None, device: None, pickups: HashMap::new(), cue: None, tempo_seq: 0 }
        });
        cx.set_global(MidiGlobal(e.clone()));
        e
    }

    pub fn entity(cx: &App) -> Entity<Self> {
        cx.global::<MidiGlobal>().0.clone()
    }

    /// Starts (or stops, `None`) waiting for a control for an action.
    pub fn learn(&mut self, action: Option<Action>, cx: &mut Context<Self>) {
        self.learning = action;
        if let Some(a) = action {
            log::info!("Console: learning {a:?}");
        }
        cx.notify();
    }

    pub fn forget(&mut self, action: Action, cx: &mut Context<Self>) {
        self.mapping.forget(action);
        self.save();
        cx.notify();
    }

    fn save(&self) {
        if let Ok(v) = serde_json::to_value(&self.mapping) {
            if let Err(e) = lfly_core::store::set(STORE_KEY, &v) {
                log::warn!("Console mapping: {e}");
            }
        }
    }

    fn on_msg(&mut self, m: Msg, cx: &mut Context<Self>) {
        match m {
            Msg::Device(name) => {
                if name != self.device {
                    match &name {
                        Some(n) => {
                            log::info!("Console: {n}");
                            Toasts::say(crate::i18n::trf("Console collegata: {0}", &[&display_name(n)]), cx);
                        }
                        None => log::info!("Console: none"),
                    }
                    self.device = name;
                    self.pickups.clear();
                    cx.notify();
                }
            }
            Msg::Bytes(bytes) => {
                if let Some(input) = midi_map::parse(&bytes) {
                    self.on_input(input, cx);
                }
            }
        }
    }

    fn on_input(&mut self, input: Input, cx: &mut Context<Self>) {
        if let Some(action) = self.learning {
            // A button is learned when pressed (its release is ignored); a fader on any move.
            if action.is_fader() || input.pressed() {
                self.mapping.learn(action, input.control);
                self.save();
                self.learning = None;
                log::info!("Console: {action:?} is {}", input.control.describe());
                Toasts::say(format!("{}: {}", action.label(), input.control.describe()), cx);
                cx.notify();
            }
            return;
        }
        let Some(action) = self.mapping.action_for(&input.control) else { return };
        let model = self.model.clone();
        let pressed = input.pressed();
        if pressed || action.is_fader() {
            log::info!("Console: {action:?} ({})", input.value);
        }
        match action {
            Action::PlayPause if pressed => model.read(cx).command(protocol::CMD_TOGGLE, 0),
            Action::Next if pressed => model.read(cx).command(protocol::CMD_NEXT, 0),
            Action::Previous if pressed => model.read(cx).command(protocol::CMD_PREVIOUS, 0),
            Action::Cue if pressed => self.cue(cx),
            Action::Automix if pressed => {
                let audio = crate::audio::AudioModel::entity(cx);
                audio.update(cx, |a, cx| {
                    let on = !a.v.automix;
                    a.set_automix(on, None, cx);
                    Toasts::say(if on { crate::i18n::tr("Automix acceso") } else { crate::i18n::tr("Automix spento") }, cx);
                });
            }
            Action::Volume => {
                let v = midi_map::volume_of(input.value);
                let current = model.read(cx).state.as_ref().map_or(100, |s| s.volume.max(0) as i64);
                if self.pickups.entry(action).or_default().take(v as f32, current as f32, 3.) && v != current {
                    model.read(cx).command(protocol::CMD_VOLUME, v);
                }
            }
            Action::Tempo => {
                // The speed is this computer's own: never while another device is controlled.
                if model.read(cx).remote.is_some() {
                    return;
                }
                let speed = midi_map::speed_of(input.value);
                let current = self.core.player.speed();
                if !self.pickups.entry(action).or_default().take(speed * 100., current * 100., 0.4) {
                    return;
                }
                self.tempo_seq += 1;
                let seq = self.tempo_seq;
                cx.spawn(async move |this, cx| {
                    cx.background_executor().timer(TEMPO_REST).await;
                    let _ = this.update(cx, |me, _| {
                        if me.tempo_seq == seq && (me.core.player.speed() - speed).abs() > 0.0005 {
                            log::info!("Console: tempo {speed:.3}");
                            me.core.player.set_speed(speed);
                        }
                    });
                })
                .detach();
            }
            _ => {}
        }
    }

    /// A CDJ's cue: paused, it marks the point; playing, it goes back there and pauses.
    pub fn cue(&mut self, cx: &mut Context<Self>) {
        let m = self.model.read(cx);
        let Some(song) = m.current().map(|t| t.id.clone()) else { return };
        if m.playing() {
            let at = self.cue.as_ref().filter(|(id, _)| *id == song).map_or(0, |(_, ms)| *ms);
            m.command(protocol::CMD_SEEK, at);
            m.command(protocol::CMD_PAUSE, 0);
        } else {
            let at = m.live_position().max(0.) as i64;
            log::info!("Console: cue at {at} ms");
            self.cue = Some((song, at));
        }
    }
}

/// A port as people know it: "DDJ-400" for ALSA's "DDJ-400:DDJ-400 MIDI 1 24:0".
pub fn display_name(port: &str) -> String {
    // ALSA ends the name with "client:port" numbers; a model number like "Inpulse 300" stays.
    let is_address = |t: &str| t.split_once(':').is_some_and(|(a, b)| !a.is_empty() && !b.is_empty() && a.chars().chain(b.chars()).all(|c| c.is_ascii_digit()));
    let without_numbers = match port.trim_end().rsplit_once(' ') {
        Some((head, last)) if is_address(last) => head.trim_end(),
        _ => port.trim(),
    };
    let (client, rest) = without_numbers.split_once(':').unwrap_or((without_numbers, ""));
    if rest.trim().is_empty() || rest.contains(client) { client.trim().to_string() } else { format!("{} ({})", client.trim(), rest.trim()) }
}

/// Port names never taken for a console: ALSA's loopback, PipeWire's own, LFly's.
fn ignored(name: &str) -> bool {
    let n = name.to_lowercase();
    n.contains("midi through") || n.contains("pipewire") || n.starts_with("lfly")
}

/// The thread: keeps one console open, looking again every [`SCAN`] while none is (or the open
/// one went away).
fn scan(tx: tokio::sync::mpsc::UnboundedSender<Msg>, test_port: Option<String>) {
    // A test run takes only its own port; otherwise the first that is not ignored.
    let wanted = |n: &str| match &test_port {
        Some(t) => n.contains(t.as_str()),
        None => !ignored(n),
    };
    let Ok(lister) = midir::MidiInput::new("lfly-scan") else { return };
    let mut open: Option<(String, midir::MidiInputConnection<()>)> = None;
    loop {
        let names: Vec<String> = lister.ports().iter().filter_map(|p| lister.port_name(p).ok()).collect();
        if open.as_ref().is_some_and(|(n, _)| !names.contains(n)) {
            open = None;
            let _ = tx.send(Msg::Device(None));
        }
        if open.is_none() {
            if let Some(name) = names.iter().find(|n| wanted(n)).cloned() {
                if let Ok(input) = midir::MidiInput::new("lfly") {
                    let port = input.ports().into_iter().find(|p| input.port_name(p).ok().as_deref() == Some(name.as_str()));
                    let tx2 = tx.clone();
                    if let Some(port) = port {
                        match input.connect(&port, "lfly-console", move |_, bytes, _| {
                            let _ = tx2.send(Msg::Bytes(bytes.to_vec()));
                        }, ()) {
                            Ok(conn) => {
                                let _ = tx.send(Msg::Device(Some(name.clone())));
                                open = Some((name, conn));
                            }
                            Err(e) => log::warn!("Console {name}: {e}"),
                        }
                    }
                }
            }
        }
        if tx.is_closed() {
            return;
        }
        std::thread::sleep(SCAN);
    }
}

#[cfg(test)]
mod tests {
    use super::display_name;

    #[test]
    fn ports_are_named_as_people_know_them() {
        assert_eq!(display_name("DDJ-400:DDJ-400 MIDI 1 24:0"), "DDJ-400");
        assert_eq!(display_name("midi-send:lfly-test-console 129:0"), "midi-send (lfly-test-console)");
        assert_eq!(display_name("Hercules DJControl Inpulse 300"), "Hercules DJControl Inpulse 300");
    }
}
