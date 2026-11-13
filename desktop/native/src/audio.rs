//! The local player's sound (Tauri: audio.svelte.ts), kept under the shared store key "audio":
//! the equalizer (the phone's bands and presets), the crossfade, the output device.

use std::sync::Arc;

use gpui_kit::*;
use lfly_audio::{EqCurve, OutputDevice};
use lfly_core::settings::Audio;

use crate::core::Core;

pub const BANDS: [&str; 5] = ["60 Hz", "230 Hz", "910 Hz", "3,6 kHz", "10 kHz"];
pub const MAX_DB: f32 = 12.;
/// ALSA's default device, which follows the system (PipeWire, PulseAudio).
pub const SYSTEM_DEFAULT: &str = "alsa:default";

/// The phone's presets (EqualizerManager.kt): name, bands, bass boost.
pub const PRESETS: [(&str, [f32; 5], i32); 7] = [
    ("Flat", [0., 0., 0., 0., 0.], 0),
    ("Bassi potenti", [7., 5., 2., 0., 0.], 500),
    ("Rock", [5., 3., -1., 3., 5.], 200),
    ("Pop", [-1., 2., 5., 3., -1.], 100),
    ("Elettronica", [6., 4., 0., 2., 5.], 400),
    ("Jazz", [3., 2., -1., 2., 4.], 150),
    ("Voce in primo piano", [-4., 0., 6., 4., 1.], 0),
];

pub struct AudioModel {
    core: Arc<Core>,
    pub v: Audio,
    pub devices: Vec<OutputDevice>,
    save_seq: u64,
}

struct AudioGlobal(Entity<AudioModel>);
impl Global for AudioGlobal {}

impl AudioModel {
    /// The saved sound, already in the player (Core::new applies it).
    pub fn new(core: Arc<Core>, cx: &mut App) -> Entity<Self> {
        let e = cx.new(|_| Self { core, v: lfly_core::settings::audio(), devices: Vec::new(), save_seq: 0 });
        cx.set_global(AudioGlobal(e.clone()));
        e
    }

    pub fn entity(cx: &App) -> Entity<Self> {
        cx.global::<AudioGlobal>().0.clone()
    }

    /// The preset the curve matches, or "Personalizzato".
    pub fn preset(&self) -> &'static str {
        let eq = &self.v.eq;
        PRESETS
            .iter()
            .find(|(_, b, bass)| *bass == eq.bass_boost && b.iter().zip(eq.bands_db.iter()).all(|(x, y)| (x - y).abs() < 0.05))
            .map_or("Personalizzato", |p| p.0)
    }

    /// The output devices, read off the UI thread (enumerating ALSA takes a moment).
    pub fn load_devices(&mut self, cx: &mut Context<Self>) {
        let core = self.core.clone();
        cx.spawn(async move |this, cx| {
            let mut list = cx.background_executor().spawn(async move { core.player.devices() }).await;
            // ALSA's "Default ALSA Output (currently PipeWire Media Server)": the system's choice.
            for d in list.iter_mut().filter(|d| d.id == SYSTEM_DEFAULT) {
                d.name = "Predefinita del sistema".into();
            }
            let _ = this.update(cx, |this, cx| {
                this.devices = list;
                cx.notify();
            });
        })
        .detach();
    }

    fn apply_eq(&mut self, cx: &mut Context<Self>) {
        self.core.player.set_equalizer(self.v.eq);
        self.save_soon(cx);
    }

    /// Saved a moment after the last change: a slider being dragged writes once.
    fn save_soon(&mut self, cx: &mut Context<Self>) {
        self.save_seq += 1;
        let mine = self.save_seq;
        cx.notify();
        cx.spawn(async move |this, cx| {
            cx.background_executor().timer(std::time::Duration::from_millis(400)).await;
            let _ = this.update(cx, |this, _| {
                if this.save_seq == mine {
                    if let Err(e) = lfly_core::settings::set_audio(&this.v) {
                        log::warn!("audio: {e}");
                    }
                }
            });
        })
        .detach();
    }

    pub fn set_band(&mut self, i: usize, db: f32, cx: &mut Context<Self>) {
        self.v.eq.bands_db[i] = ((db * 2.).round() / 2.).clamp(-MAX_DB, MAX_DB);
        self.v.eq.enabled = true;
        self.apply_eq(cx);
    }

    pub fn set_bass(&mut self, v: f32, cx: &mut Context<Self>) {
        self.v.eq.bass_boost = ((v / 10.).round() * 10.).clamp(0., 1000.) as i32;
        self.v.eq.enabled = true;
        self.apply_eq(cx);
    }

    pub fn use_preset(&mut self, name: &str, cx: &mut Context<Self>) {
        let Some((_, bands, bass)) = PRESETS.iter().find(|p| p.0 == name) else { return };
        self.v.eq = EqCurve { enabled: name != "Flat" || self.v.eq.enabled, bands_db: *bands, bass_boost: *bass };
        self.apply_eq(cx);
    }

    pub fn set_enabled(&mut self, on: bool, cx: &mut Context<Self>) {
        self.v.eq.enabled = on;
        self.apply_eq(cx);
    }

    pub fn set_crossfade(&mut self, s: f32, cx: &mut Context<Self>) {
        self.v.crossfade = s.round().clamp(0., 12.) as u32;
        self.core.player.set_crossfade(self.v.crossfade);
        self.save_soon(cx);
    }

    pub fn set_device(&mut self, id: Option<String>, cx: &mut Context<Self>) {
        self.v.device = id.clone();
        self.core.player.set_device(id);
        self.save_soon(cx);
    }
}
