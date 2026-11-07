// The window's own settings (the core keeps the ones that change playback): lyrics, the page
// LFly opens on, motion and the listening history. Saved in the store as "prefs".

import { invoke } from "@tauri-apps/api/core";
import { nav } from "./nav.svelte";
import { themes } from "./theme/theme.svelte";

export type Prefs = {
  lyricsSize: "s" | "m" | "l";
  lyricsAlign: "left" | "center";
  /** Word by word when the source has the timings; off: whole lines. */
  lyricsWords: boolean;
  lyricsSource: boolean;
  startPage: "home" | "library" | "liked" | "search";
  /** "system" follows the system's reduce-motion setting. */
  motion: "system" | "reduce" | "full";
  /** Songs played are not added to the history. */
  pauseHistory: boolean;
};

const DEFAULTS: Prefs = {
  lyricsSize: "m",
  lyricsAlign: "left",
  lyricsWords: true,
  lyricsSource: true,
  startPage: "home",
  motion: "system",
  pauseHistory: false,
};

class PrefsStore {
  v = $state<Prefs>({ ...DEFAULTS });

  async start() {
    const saved = await invoke<Partial<Prefs> | null>("store_get", { key: "prefs" }).catch(() => null);
    if (saved && typeof saved === "object") this.v = { ...DEFAULTS, ...saved };
    this.applyMotion();
    // The page to open on, unless the address already names one.
    if (!location.hash && this.v.startPage !== "home") nav.replace({ page: this.v.startPage } as never);
  }

  set<K extends keyof Prefs>(key: K, value: Prefs[K]) {
    this.v = { ...this.v, [key]: value };
    if (key === "motion") this.applyMotion();
    invoke("store_set", { key: "prefs", value: $state.snapshot(this.v) }).catch(() => {});
  }

  private applyMotion() {
    themes.setMotionOverride(this.v.motion === "system" ? null : this.v.motion === "reduce");
  }
}

export const prefs = new PrefsStore();
