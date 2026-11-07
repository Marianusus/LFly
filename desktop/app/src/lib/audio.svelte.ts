// The local player's sound: the equalizer (the phone's own, same bands and presets) and the output.

import { invoke } from "@tauri-apps/api/core";

export type EqCurve = { enabled: boolean; bandsDb: number[]; bassBoost: number };
export type OutputDevice = { id: string; name: string; isDefault: boolean };

export const BANDS = ["60 Hz", "230 Hz", "910 Hz", "3,6 kHz", "10 kHz"];
export const MAX_DB = 12;

/** The phone's presets (EqualizerManager.kt), so a sound set there sounds the same here. */
export const PRESETS: { name: string; bands: number[]; bass: number }[] = [
  { name: "Flat", bands: [0, 0, 0, 0, 0], bass: 0 },
  { name: "Bassi potenti", bands: [7, 5, 2, 0, 0], bass: 500 },
  { name: "Rock", bands: [5, 3, -1, 3, 5], bass: 200 },
  { name: "Pop", bands: [-1, 2, 5, 3, -1], bass: 100 },
  { name: "Elettronica", bands: [6, 4, 0, 2, 5], bass: 400 },
  { name: "Jazz", bands: [3, 2, -1, 2, 4], bass: 150 },
  { name: "Voce in primo piano", bands: [-4, 0, 6, 4, 1], bass: 0 },
];

type Saved = { eq: EqCurve; device: string | null; crossfade?: number };

class Audio {
  eq = $state<EqCurve>({ enabled: false, bandsDb: [0, 0, 0, 0, 0], bassBoost: 0 });
  device = $state<string | null>(null);
  /** Seconds each song fades into the next (0: back to back, without gaps). */
  crossfade = $state(0);
  devices = $state<OutputDevice[]>([]);
  available = $state(false);

  preset = $derived(
    PRESETS.find((p) => p.bass === this.eq.bassBoost && p.bands.every((b, i) => Math.abs(b - this.eq.bandsDb[i]) < 0.05))?.name ??
      "Personalizzato",
  );

  async start() {
    try {
      this.available = await invoke<boolean>("player_ready");
      if (!this.available) return;
      const saved = await invoke<Saved | null>("store_get", { key: "audio" });
      if (saved?.eq) this.eq = saved.eq;
      if (saved?.device) this.device = saved.device;
      this.crossfade = saved?.crossfade ?? 0;
      await this.apply();
      await invoke("player_set_crossfade", { seconds: this.crossfade });
      if (this.device) await invoke("player_set_device", { id: this.device });
    } catch (e) {
      console.warn("Audio settings not loaded", e);
    }
  }

  async loadDevices() {
    try {
      this.devices = await invoke<OutputDevice[]>("player_devices");
    } catch {
      this.devices = [];
    }
  }

  private timer: ReturnType<typeof setTimeout> | undefined;

  /** Sends the curve to the player at once, saves it shortly after (sliders move fast). */
  async apply() {
    const curve = $state.snapshot(this.eq) as EqCurve;
    await invoke("player_set_equalizer", { curve }).catch(() => {});
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.save(), 400);
  }

  setBand(i: number, db: number) {
    this.eq.bandsDb[i] = Math.max(-MAX_DB, Math.min(MAX_DB, db));
    if (!this.eq.enabled) this.eq.enabled = true;
    this.apply();
  }

  setBass(v: number) {
    this.eq.bassBoost = Math.round(Math.max(0, Math.min(1000, v)));
    if (!this.eq.enabled) this.eq.enabled = true;
    this.apply();
  }

  usePreset(name: string) {
    const p = PRESETS.find((x) => x.name === name);
    if (!p) return;
    this.eq = { enabled: name !== "Flat" || this.eq.enabled, bandsDb: [...p.bands], bassBoost: p.bass };
    this.apply();
  }

  setEnabled(on: boolean) {
    this.eq.enabled = on;
    this.apply();
  }

  async setDevice(id: string | null) {
    this.device = id;
    await invoke("player_set_device", { id }).catch(() => {});
    this.save();
  }

  setCrossfade(seconds: number) {
    this.crossfade = Math.max(0, Math.min(12, Math.round(seconds)));
    invoke("player_set_crossfade", { seconds: this.crossfade }).catch(() => {});
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.save(), 400);
  }

  private save() {
    const value: Saved = { eq: $state.snapshot(this.eq) as EqCurve, device: this.device, crossfade: this.crossfade };
    invoke("store_set", { key: "audio", value }).catch(() => {});
  }
}

export const audio = new Audio();
