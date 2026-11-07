// The look in use: the phone's appearance mode and accent, the Liquid Glass settings, the colors of
// the playing cover, and (Avanzate) the person's own Spicetify-style themes.

import { invoke } from "@tauri-apps/api/core";
import { applyLook } from "./apply";
import { endBoot, rememberLook } from "./boot";
import { loadCoverArt, type CoverArt } from "./coverArt";
import { ACCENTS, DEFAULT_ACCENT, MODES, accentById, buildScheme, isMode, luminance, modeInfo, type AppearanceMode, type Scheme } from "./palette";
import { EXTRA_THEMES, themeFromScheme, type Theme } from "./themes";
import { scene } from "../glass/scene";

export type GlassSettings = {
  /** Liquid Glass on the floating surfaces; off makes them solid. */
  enabled: boolean;
  /** Real lens refraction, dispersion and rim light (more GPU). */
  lens: boolean;
  /** 0..1: how see-through and how strong the lens is. */
  strength: number;
};

type SavedLook = {
  mode: AppearanceMode;
  accent: string;
  glass: GlassSettings;
  /** A custom theme used instead of the modes, or null. */
  customId: string | null;
};

type SavedThemes = { activeId?: string; custom: Theme[] };

const DEFAULT_GLASS: GlassSettings = { enabled: true, lens: true, strength: 0.75 };
const FADE_MS = 380;
const COVER_FADE_MS = 1100;

function media(query: string): boolean {
  return typeof matchMedia === "function" && matchMedia(query).matches;
}

class Themes {
  // ---- The phone's appearance ----
  mode = $state<AppearanceMode>("DYNAMIC_DARK");
  accent = $state<string>(DEFAULT_ACCENT);
  glass = $state<GlassSettings>({ ...DEFAULT_GLASS });

  /** System preferences, followed live. */
  reduceMotion = $state(media("(prefers-reduced-motion: reduce)"));
  private systemReduceMotion = media("(prefers-reduced-motion: reduce)");
  /** Set in Settings (true: reduce, false: full motion); null follows the system. */
  private motionOverride: boolean | null = null;

  setMotionOverride(v: boolean | null) {
    this.motionOverride = v;
    this.reduceMotion = v ?? this.systemReduceMotion;
  }
  reduceTransparency = $state(media("(prefers-reduced-transparency: reduce)"));

  /** The playing cover, read once per song. */
  art = $state.raw<CoverArt | null>(null);

  // ---- Custom themes (Avanzate) ----
  custom = $state<Theme[]>([]);
  /** The custom or extra theme in use instead of the modes, or null for LFly's own look. */
  customId = $state<string | null>(null);
  /** A theme being edited: shown live, saved only on request. */
  draft = $state<Theme | null>(null);

  readonly modes = MODES;
  readonly accents = ACCENTS;
  readonly extra = EXTRA_THEMES;

  /** Every theme of the advanced section: the person's own, then the extra palettes. */
  all = $derived([...this.custom, ...EXTRA_THEMES]);
  /** The custom theme in use (or being edited), or null when LFly's modes are in use. */
  active = $derived(this.draft ?? this.all.find((t) => t.id === this.customId) ?? null);
  /** Kept for older callers: the theme shown now. */
  shown = $derived(this.active);
  /** The color of the accent chosen in Settings. */
  accentColor = $derived(accentById(this.accent).color);
  /** The cover's colors (for dynamic modes), null without a song or outside them. */
  cover = $derived(this.art?.accent ?? null);
  /** The mode's resolved colors, as BackgroundScheme on the phone. */
  scheme = $derived.by<Scheme>(() => {
    const info = modeInfo(this.mode);
    const c = info.dynamic ? this.art?.accent : null;
    return buildScheme(this.mode, c?.primary ?? this.accentColor, c?.secondary ?? this.accentColor);
  });
  /** Glass on, unless turned off here or by the system's "reduce transparency". */
  glassOn = $derived(this.glass.enabled && !this.reduceTransparency);
  /** Lens refraction on: needs glass, and is off under reduced transparency. */
  lensOn = $derived(this.glassOn && this.glass.lens);
  /** Light canvas: dark text on light colors. */
  isLight = $derived(this.active ? luminance(this.active.colors.main) >= 0.4 : this.scheme.light);

  private started = false;
  private wanted = "";
  private startedAt = 0;
  private bgImage: HTMLImageElement | null = null;
  private bgImageSrc = "";

  async start() {
    if (this.started) return;
    this.started = true;
    this.startedAt = performance.now();
    this.watchSystem();
    try {
      const [look, saved] = await Promise.all([
        invoke<SavedLook | null>("store_get", { key: "appearance" }).catch(() => null),
        invoke<SavedThemes | null>("store_get", { key: "themes" }).catch(() => null),
      ]);
      if (saved && Array.isArray(saved.custom)) this.custom = saved.custom;
      if (look && typeof look === "object") {
        if (isMode(look.mode)) this.mode = look.mode;
        if (ACCENTS.some((a) => a.id === look.accent)) this.accent = look.accent;
        if (look.glass) this.glass = { ...DEFAULT_GLASS, ...look.glass };
        this.customId = this.all.some((t) => t.id === look.customId) ? look.customId : null;
      } else if (saved?.activeId) {
        // From the first desktop themes: the light one becomes Bianco, a custom one stays in use.
        const id = saved.activeId;
        if (id === "lfly-light") this.mode = "PURE_WHITE";
        else if (id === "amoled") this.mode = "AMOLED_BLACK";
        else if (this.all.some((t) => t.id === id)) this.customId = id;
      }
    } catch (e) {
      console.warn("Aspetto non caricato", e);
    }
    $effect.root(() => {
      $effect(() => {
        try {
          applyLook(
            { scheme: this.scheme, custom: this.active, glass: this.glassOn, lens: this.lensOn, strength: this.glass.strength },
            this.cover,
          );
          rememberLook();
        } catch (e) {
          console.warn("Aspetto non applicato", e);
        }
      });
      $effect(() => this.paintScene());
    });
    endBoot();
  }

  private watchSystem() {
    if (typeof matchMedia !== "function") return;
    const follow = (q: string, set: (v: boolean) => void) => {
      const m = matchMedia(q);
      m.addEventListener?.("change", () => set(m.matches));
    };
    follow("(prefers-reduced-motion: reduce)", (v) => {
      this.systemReduceMotion = v;
      this.reduceMotion = this.motionOverride ?? v;
    });
    follow("(prefers-reduced-transparency: reduce)", (v) => (this.reduceTransparency = v));
  }

  /** What the Backdrop paints (and the lenses refract). */
  private paintScene() {
    const custom = this.active;
    const s = this.scheme;
    const coverMode = !custom && this.mode === "DYNAMIC_COVER";
    const art = coverMode ? this.art : null;
    let image: HTMLImageElement | null = null;
    if (custom?.background) {
      if (this.bgImageSrc !== custom.background) {
        this.bgImageSrc = custom.background;
        this.bgImage = new Image();
        this.bgImage.onload = () => this.paintScene();
        this.bgImage.src = custom.background;
      }
      image = this.bgImage;
    }
    const gradient: [string, string, string] = custom
      ? [custom.colors.sidebar, custom.colors.main, custom.colors.main]
      : s.gradient;
    const key = custom
      ? `c:${gradient.join()}:${custom.background ? custom.background.length : 0}`
      : `${s.mode}:${s.gradient.join()}:${s.glow}:${art?.url ?? ""}`;
    scene().set(
      {
        key,
        gradient,
        glow: custom ? null : s.glow,
        light: custom ? false : s.light,
        cover: art?.blurred ?? null,
        image,
        imageOpacity: custom?.backgroundOpacity ?? 0.25,
        animate: !this.reduceMotion,
      },
      // No fades while the window opens: it should appear as it is, not change under the person.
      this.reduceMotion || performance.now() - this.startedAt < 1500 ? 0 : art ? COVER_FADE_MS : FADE_MS,
    );
  }

  private saveTimer = 0;

  /** Saved a moment after the last change: a slider being dragged writes once. */
  private saveLook() {
    clearTimeout(this.saveTimer);
    this.saveTimer = window.setTimeout(() => this.writeLook(), 250);
  }

  private writeLook() {
    const data: SavedLook = {
      mode: this.mode,
      accent: this.accent,
      glass: $state.snapshot(this.glass),
      customId: this.customId,
    };
    invoke("store_set", { key: "appearance", value: data }).catch((e) => console.warn("Aspetto non salvato", e));
  }

  private saveThemes() {
    const data: SavedThemes = { custom: $state.snapshot(this.custom) as Theme[] };
    invoke("store_set", { key: "themes", value: data }).catch((e) => console.warn("Temi non salvati", e));
  }

  // ---- The phone's settings ----

  /** Uses an appearance mode (and leaves any custom theme). */
  setMode(mode: AppearanceMode) {
    this.mode = mode;
    this.customId = null;
    this.draft = null;
    this.saveLook();
  }

  setAccent(id: string) {
    this.accent = accentById(id).id;
    this.saveLook();
  }

  setGlass(patch: Partial<GlassSettings>) {
    this.glass = { ...this.glass, ...patch };
    this.saveLook();
  }

  // ---- Custom themes ----

  /** Uses a custom or extra theme instead of the modes. */
  use(id: string) {
    this.draft = null;
    this.customId = id;
    this.saveLook();
  }

  /** Back to LFly's own look (the mode and accent chosen). */
  useLFly() {
    this.draft = null;
    this.customId = null;
    this.saveLook();
  }

  /** Starts editing; a theme that is not the person's own is copied first, so it stays as it was. */
  edit(theme?: Theme | null) {
    const source = theme ?? this.active;
    if (!source) {
      this.draft = themeFromScheme($state.snapshot(this.scheme) as Scheme, `${modeInfo(this.mode).title} (mio)`);
      return;
    }
    const copy = structuredClone($state.snapshot(source)) as Theme;
    const own = this.custom.some((t) => t.id === copy.id);
    this.draft = own ? copy : { ...copy, builtin: false, id: "", name: `${source.name} (mio)` };
  }

  /** Keeps the draft: a new theme, or the edited one replaced. Then uses it. */
  saveDraft() {
    const d = this.draft;
    if (!d) return;
    const theme = $state.snapshot(d) as Theme;
    if (!theme.id) theme.id = `mio-${Date.now().toString(36)}`;
    const i = this.custom.findIndex((t) => t.id === theme.id);
    if (i >= 0) this.custom[i] = theme;
    else this.custom.push(theme);
    this.draft = null;
    this.saveThemes();
    this.use(theme.id);
  }

  cancelDraft() {
    this.draft = null;
  }

  add(theme: Theme) {
    this.custom.push(theme);
    this.saveThemes();
    this.use(theme.id);
  }

  remove(id: string) {
    this.custom = this.custom.filter((t) => t.id !== id);
    if (this.customId === id) this.customId = null;
    if (this.draft?.id === id) this.draft = null;
    this.saveThemes();
    this.saveLook();
  }

  /** Reads the playing cover: its colors for the dynamic modes and the flowing backdrop. */
  async readCover(url: string | undefined | null) {
    if (!url) {
      this.art = null;
      return;
    }
    if (this.art?.url === url) return;
    this.wanted = url;
    try {
      const art = await loadCoverArt(url);
      if (this.wanted === url) this.art = art;
    } catch {
      // A cover that cannot be read keeps the colors in use.
    }
  }
}

export const themes = new Themes();
export type { AppearanceMode, Scheme };
