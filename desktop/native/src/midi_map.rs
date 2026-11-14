//! A DJ console's controls and what they do in LFly, pure so it can be tested: MIDI bytes become a
//! typed control and a value, a learned mapping turns a control into an [`Action`], and faders
//! pick the value up softly. The actions know nothing about MIDI, so an HID console can reuse them.

use serde::{Deserialize, Serialize};

/// What kind of message a control sends.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Kind {
    /// A button sending note on and off.
    Note,
    /// A knob, fader or button sending control change.
    Cc,
}

/// One control on the console: its message kind, channel (0-15) and number.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct Control {
    pub kind: Kind,
    pub channel: u8,
    pub number: u8,
}

impl Control {
    /// "Nota 11, canale 1" as Settings shows it.
    pub fn describe(&self) -> String {
        let kind = match self.kind {
            Kind::Note => "Nota",
            Kind::Cc => "Controllo",
        };
        format!("{kind} {}, canale {}", self.number, self.channel + 1)
    }
}

/// A message from the console: the control and its value (0-127; a note off is 0).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Input {
    pub control: Control,
    pub value: u8,
}

impl Input {
    /// A button went down (note on, or a control change at half or more).
    pub fn pressed(&self) -> bool {
        self.value >= 64 || (self.control.kind == Kind::Note && self.value > 0)
    }
}

/// The message in `bytes`, when it is a note or a control change (clock, sysex and the rest are
/// not controls).
pub fn parse(bytes: &[u8]) -> Option<Input> {
    let (&status, rest) = bytes.split_first()?;
    let channel = status & 0x0f;
    let (number, value) = (*rest.first()? & 0x7f, rest.get(1).map_or(0, |v| v & 0x7f));
    match status & 0xf0 {
        0x90 => Some(Input { control: Control { kind: Kind::Note, channel, number }, value }),
        0x80 => Some(Input { control: Control { kind: Kind::Note, channel, number }, value: 0 }),
        0xb0 => Some(Input { control: Control { kind: Kind::Cc, channel, number }, value }),
        _ => None,
    }
}

/// What a control does in LFly.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Action {
    PlayPause,
    Cue,
    Next,
    Previous,
    /// A fader: the player's volume.
    Volume,
    /// A fader: the playback speed, ±8% around the centre.
    Tempo,
    /// Automix on or off.
    Automix,
}

impl Action {
    /// The order Settings lists them in.
    pub const ALL: [Action; 7] = [Action::PlayPause, Action::Cue, Action::Next, Action::Previous, Action::Volume, Action::Tempo, Action::Automix];

    pub fn label(self) -> &'static str {
        match self {
            Action::PlayPause => "Play / pausa",
            Action::Cue => "Cue",
            Action::Next => "Brano successivo",
            Action::Previous => "Brano precedente",
            Action::Volume => "Volume",
            Action::Tempo => "Tempo",
            Action::Automix => "Automix",
        }
    }

    /// A fader or knob (its value matters), not a button.
    pub fn is_fader(self) -> bool {
        matches!(self, Action::Volume | Action::Tempo)
    }

    pub fn from_id(id: &str) -> Option<Action> {
        Action::ALL.into_iter().find(|a| serde_json::to_value(a).ok().and_then(|v| v.as_str().map(|s| s == id)).unwrap_or(false))
    }
}

/// The learned controls, one per action.
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct Mapping {
    #[serde(default)]
    pub binds: Vec<(Action, Control)>,
}

impl Mapping {
    pub fn action_for(&self, control: &Control) -> Option<Action> {
        self.binds.iter().find(|(_, c)| c == control).map(|(a, _)| *a)
    }

    pub fn control_for(&self, action: Action) -> Option<Control> {
        self.binds.iter().find(|(a, _)| *a == action).map(|(_, c)| *c)
    }

    /// `control` now does `action`: its old action and the action's old control are dropped.
    pub fn learn(&mut self, action: Action, control: Control) {
        self.binds.retain(|(a, c)| *a != action && *c != control);
        self.binds.push((action, control));
    }

    pub fn forget(&mut self, action: Action) {
        self.binds.retain(|(a, _)| *a != action);
    }
}

/// A fader's 0-127 as the player's volume, 0-100.
pub fn volume_of(value: u8) -> i64 {
    (value as i64 * 100 + 63) / 127
}

/// A tempo fader's 0-127 as a speed: 1 at the centre (64), 0.92 and 1.08 at the ends.
pub fn speed_of(value: u8) -> f32 {
    let v = value as f32;
    let t = if v >= 64. { (v - 64.) / 63. } else { (v - 64.) / 64. };
    ((1. + 0.08 * t) * 1000.).round() / 1000.
}

/// Soft takeover: a fader whose position differs from what plays only takes over once it
/// reaches (or crosses) that value, so the music never jumps at the first touch.
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct Pickup {
    /// The fader's last value while it had not taken over yet.
    last: Option<f32>,
    pub engaged: bool,
}

impl Pickup {
    /// The fader moved to `fader`; `current` is the value playing now, both on one scale.
    /// True when the fader drives the value from now on.
    pub fn take(&mut self, fader: f32, current: f32, tolerance: f32) -> bool {
        if !self.engaged {
            let crossed = self.last.is_some_and(|l| (l - current).signum() != (fader - current).signum());
            if (fader - current).abs() <= tolerance || crossed {
                self.engaged = true;
            }
            self.last = Some(fader);
        }
        self.engaged
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn notes_and_controls_are_read_and_the_rest_is_not() {
        assert_eq!(parse(&[0x90, 0x0b, 0x7f]), Some(Input { control: Control { kind: Kind::Note, channel: 0, number: 11 }, value: 127 }));
        assert_eq!(parse(&[0x81, 0x0b, 0x40]).map(|i| (i.control.channel, i.value)), Some((1, 0)));
        assert_eq!(parse(&[0xb2, 0x13, 0x22]).map(|i| (i.control.kind, i.control.channel, i.control.number, i.value)), Some((Kind::Cc, 2, 19, 34)));
        // Clock, sysex, pitch bend and empty messages are not controls.
        assert_eq!(parse(&[0xf8]), None);
        assert_eq!(parse(&[0xf0, 0x00, 0xf7]), None);
        assert_eq!(parse(&[0xe0, 0x00, 0x40]), None);
        assert_eq!(parse(&[]), None);
    }

    #[test]
    fn a_note_on_at_zero_is_a_release() {
        let on = parse(&[0x90, 1, 100]).unwrap();
        let zero = parse(&[0x90, 1, 0]).unwrap();
        let off = parse(&[0x80, 1, 64]).unwrap();
        assert!(on.pressed());
        assert!(!zero.pressed());
        assert!(!off.pressed());
        assert!(parse(&[0xb0, 1, 127]).unwrap().pressed());
        assert!(!parse(&[0xb0, 1, 0]).unwrap().pressed());
    }

    #[test]
    fn learning_replaces_both_sides_and_survives_json() {
        let a = Control { kind: Kind::Note, channel: 0, number: 11 };
        let b = Control { kind: Kind::Cc, channel: 0, number: 19 };
        let mut m = Mapping::default();
        m.learn(Action::PlayPause, a);
        m.learn(Action::Volume, b);
        assert_eq!(m.action_for(&a), Some(Action::PlayPause));
        // The same control for another action: it moves.
        m.learn(Action::Cue, a);
        assert_eq!(m.action_for(&a), Some(Action::Cue));
        assert_eq!(m.control_for(Action::PlayPause), None);
        // Another control for the same action: the old one goes.
        m.learn(Action::Volume, a);
        assert_eq!(m.control_for(Action::Volume), Some(a));
        assert_eq!(m.action_for(&b), None);
        let json = serde_json::to_value(&m).unwrap();
        assert_eq!(serde_json::from_value::<Mapping>(json).unwrap(), m);
        m.forget(Action::Volume);
        assert!(m.binds.is_empty());
        assert_eq!(Action::from_id("play_pause"), Some(Action::PlayPause));
    }

    #[test]
    fn faders_map_to_volume_and_speed() {
        assert_eq!(volume_of(0), 0);
        assert_eq!(volume_of(127), 100);
        assert_eq!(volume_of(64), 50);
        assert_eq!(speed_of(64), 1.0);
        assert_eq!(speed_of(0), 0.92);
        assert_eq!(speed_of(127), 1.08);
        assert!(speed_of(70) > 1.0 && speed_of(58) < 1.0);
    }

    #[test]
    fn a_fader_takes_over_only_when_it_reaches_the_value() {
        let mut p = Pickup::default();
        // Volume plays at 80; the fader sits at 10 and moves up.
        assert!(!p.take(10., 80., 2.));
        assert!(!p.take(50., 80., 2.));
        assert!(p.take(79., 80., 2.));
        assert!(p.take(30., 80., 2.));
        // A jump across the value counts as reaching it.
        let mut q = Pickup::default();
        assert!(!q.take(60., 80., 2.));
        assert!(q.take(95., 80., 2.));
    }
}
