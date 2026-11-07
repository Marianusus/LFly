// LFly's own design, ported from the phone app so both look the same:
//   AppearanceMode.kt  → MODES
//   AccentPalette.kt   → ACCENTS (same ids, colors and Italian names)
//   BackgroundScheme.kt → buildScheme()  (same color math)
//   CoverPalette.kt    → coverAccent()   (same warm/cool split of the cover)

export type AppearanceMode = "AMOLED_BLACK" | "PURE_WHITE" | "DYNAMIC_DARK" | "DYNAMIC_LIGHT" | "DYNAMIC_COVER";

export type ModeInfo = { id: AppearanceMode; title: string; subtitle: string; light: boolean; dynamic: boolean };

/** The phone's "Aspetto" modes, in the phone's order and words. */
export const MODES: ModeInfo[] = [
  { id: "AMOLED_BLACK", title: "Nero AMOLED", subtitle: "Nero assoluto con vetro satinato e accento LFly", light: false, dynamic: false },
  { id: "PURE_WHITE", title: "Bianco", subtitle: "Canvas chiaro, vetro avorio e testo scuro ad alto contrasto", light: true, dynamic: false },
  { id: "DYNAMIC_DARK", title: "Dinamico scuro", subtitle: "Sfondo scuro derivato dalla copertina in riproduzione", light: false, dynamic: true },
  { id: "DYNAMIC_LIGHT", title: "Dinamico chiaro", subtitle: "Sfondo chiaro pastello derivato dalla copertina", light: true, dynamic: true },
  {
    id: "DYNAMIC_COVER",
    title: "Copertina dinamica",
    subtitle: "La copertina in riproduzione, sfocata e in movimento, come sfondo dell'app",
    light: false,
    dynamic: true,
  },
];

export function modeInfo(mode: AppearanceMode): ModeInfo {
  return MODES.find((m) => m.id === mode) ?? MODES[0];
}

export function isMode(value: unknown): value is AppearanceMode {
  return typeof value === "string" && MODES.some((m) => m.id === value);
}

export type Accent = { id: string; name: string; color: string; premium?: boolean };

/** The phone's accent palette: same ids, colors and names. */
export const ACCENTS: Accent[] = [
  { id: "emerald", name: "Smeraldo", color: "#1db954" },
  { id: "blue", name: "Blu", color: "#4d7cff" },
  { id: "indigo", name: "Indaco", color: "#5b5bd6" },
  { id: "violet", name: "Viola", color: "#7c4dff" },
  { id: "pink", name: "Rosa", color: "#ff2d78" },
  { id: "red", name: "Rosso", color: "#ff3b30" },
  { id: "orange", name: "Arancio", color: "#ff7a00" },
  { id: "amber", name: "Ambra", color: "#ffb300" },
  { id: "teal", name: "Verde acqua", color: "#00bfa5" },
  { id: "cyan", name: "Ciano", color: "#00b8d9" },
  { id: "champagne_gold", name: "Champagne", color: "#d4af37", premium: true },
  { id: "rose_gold", name: "Oro rosa", color: "#b76e79", premium: true },
  { id: "platinum", name: "Platino", color: "#c9ccd1", premium: true },
  { id: "titanium", name: "Titanio", color: "#5c6670", premium: true },
  { id: "sapphire", name: "Zaffiro", color: "#1f4fd8", premium: true },
  { id: "midnight", name: "Mezzanotte", color: "#20304a", premium: true },
  { id: "burgundy", name: "Bordeaux", color: "#8e1b3a", premium: true },
  { id: "bronze", name: "Bronzo", color: "#a9714b", premium: true },
  { id: "obsidian", name: "Ossidiana", color: "#3a3f44", premium: true },
  { id: "sand", name: "Sabbia", color: "#c2a878", premium: true },
];

export const DEFAULT_ACCENT = "emerald";

export function accentById(id: string | null | undefined): Accent {
  return ACCENTS.find((a) => a.id === id) ?? ACCENTS[0];
}

/** Two colors read from a cover (CoverPalette.kt). */
export type CoverAccent = { primary: string; secondary: string };

/** Everything the window needs for one mode: BackgroundScheme.kt, plus the desktop's surfaces. */
export type Scheme = {
  mode: AppearanceMode;
  light: boolean;
  /** Three stops, top to bottom. */
  gradient: [string, string, string];
  text: string;
  text2: string;
  text3: string;
  glassTint: string;
  glassBorder: string;
  accent: string;
  accent2: string;
  onAccent: string;
  scrim: string;
  /** The canvas color most of the window sits on (the gradient's last stop, as the phone does). */
  bg: string;
  /** Opaque card surfaces. */
  surface: string;
  surface2: string;
  surface3: string;
  /** Ambient glow behind the content, for the dynamic gradient modes. */
  glow: string | null;
};

const FALLBACK_EMERALD = "#1db954";
const FALLBACK_BLUE = "#4d7cff";
const DARK_TEXT = "#15151a";

/**
 * BackgroundSchemeFactory.build: [primary]/[secondary] are the user's accent, or the cover's in
 * the dynamic modes when there is one.
 */
export function buildScheme(mode: AppearanceMode, primary: string | null, secondary: string | null): Scheme {
  const info = modeInfo(mode);
  const light = info.light;
  const p = primary ?? FALLBACK_EMERALD;
  const s = secondary ?? FALLBACK_BLUE;
  const accent = accentForDisplay(p, light);
  const accent2 = accentForDisplay(s, light);

  let gradient: [string, string, string];
  let glassTint: string;
  let glassBorder: string;
  switch (mode) {
    case "PURE_WHITE":
      gradient = ["#ffffff", "#f7f7fb", "#f1f1f6"];
      glassTint = "rgba(0,0,0,0.06)";
      glassBorder = "rgba(0,0,0,0.12)";
      break;
    case "DYNAMIC_LIGHT":
      gradient = [clampLumaLight(mix("#ffffff", p, 0.42), 0.82), clampLumaLight(mix("#ffffff", s, 0.25), 0.88), "#fcfcff"];
      glassTint = "rgba(255,255,255,0.07)";
      glassBorder = "rgba(0,0,0,0.14)";
      break;
    case "DYNAMIC_DARK":
    case "DYNAMIC_COVER":
      gradient = [clampLumaDark(mix("#000000", p, 0.55), 0.29), clampLumaDark(mix("#000000", s, 0.34), 0.19), "#050509"];
      glassTint = "rgba(255,255,255,0.08)";
      glassBorder = "rgba(255,255,255,0.15)";
      break;
    default:
      gradient = ["#000000", "#000000", "#000000"];
      glassTint = "rgba(255,255,255,0.08)";
      glassBorder = "rgba(255,255,255,0.15)";
  }

  const text = light ? DARK_TEXT : "#ffffff";
  // The phone's 70% / 45% ink, raised where needed so both stay at 4.5:1 on every stop.
  const text2 = readableInk(text, gradient, light ? 0.72 : 0.7);
  const text3 = readableInk(text, gradient, light ? 0.6 : 0.5);
  const bg = gradient[2];
  // Opaque surfaces: the glass tint laid on the canvas, as SonoraOpaqueSurfaceCard does.
  const surface = light ? (mode === "PURE_WHITE" ? "#ffffff" : mix(bg, "#ffffff", 0.7)) : mix(bg, "#ffffff", 0.075);
  const surface2 = light ? mix(bg, DARK_TEXT, 0.045) : mix(bg, "#ffffff", 0.12);
  const surface3 = light ? mix(bg, DARK_TEXT, 0.08) : mix(bg, "#ffffff", 0.17);

  return {
    mode,
    light,
    gradient,
    text,
    text2,
    text3,
    glassTint,
    glassBorder,
    accent,
    accent2,
    onAccent: onColorFor(accent),
    scrim: light ? "rgba(0,0,0,0.2)" : "rgba(0,0,0,0.6)",
    bg,
    surface,
    surface2,
    surface3,
    glow: info.dynamic && mode !== "DYNAMIC_COVER" ? accent : null,
  };
}

/**
 * CoverPalette.accentFrom: the average of the vivid warm pixels and of the vivid cool ones.
 * [data] is RGBA from a small (≤ 64 px) copy of the cover.
 */
export function coverAccent(data: Uint8ClampedArray): CoverAccent {
  let wr = 0, wg = 0, wb = 0, wn = 0;
  let cr = 0, cg = 0, cb = 0, cn = 0;
  let ar = 0, ag = 0, ab = 0, an = 0;
  for (let i = 0; i < data.length; i += 4) {
    if (data[i + 3] < 32) continue;
    const r = data[i], g = data[i + 1], b = data[i + 2];
    const max = Math.max(r, g, b), min = Math.min(r, g, b);
    const sat = max <= 0 ? 0 : (max - min) / max;
    const luma = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
    ar += r; ag += g; ab += b; an++;
    if (sat >= 0.22 && luma >= 0.1 && luma <= 0.94) {
      if (r >= b) { wr += r; wg += g; wb += b; wn++; }
      else { cr += r; cg += g; cb += b; cn++; }
    }
  }
  if (an === 0) return { primary: FALLBACK_EMERALD, secondary: FALLBACK_BLUE };
  const avg = (r: number, g: number, b: number, n: number) => toHex([r / n, g / n, b / n]);
  const warm = wn > 0 ? avg(wr, wg, wb, wn) : null;
  const cool = cn > 0 ? avg(cr, cg, cb, cn) : null;
  const primary = warm ?? cool ?? avg(ar, ag, ab, an);
  const secondary = cool ?? warm ?? mix(primary, "#ffffff", 0.35);
  return { primary, secondary };
}

// ---- Color math (the phone's, so the numbers match) ----

export function normalizeHex(value: unknown): string | null {
  if (typeof value !== "string") return null;
  let v = value.trim().replace(/^#/, "");
  if (/^[0-9a-f]{3}$/i.test(v)) v = v.replace(/./g, (c) => c + c);
  if (/^[0-9a-f]{8}$/i.test(v)) v = v.slice(0, 6);
  return /^[0-9a-f]{6}$/i.test(v) ? `#${v.toLowerCase()}` : null;
}

export function rgb(hex: string): [number, number, number] {
  const v = (normalizeHex(hex) ?? "#000000").slice(1);
  return [0, 2, 4].map((i) => parseInt(v.slice(i, i + 2), 16)) as [number, number, number];
}

export function toHex([r, g, b]: number[]): string {
  return "#" + [r, g, b].map((x) => Math.round(clamp(x, 0, 255)).toString(16).padStart(2, "0")).join("");
}

export function rgbList(hex: string): string {
  return rgb(hex).join(",");
}

export function rgba(hex: string, a: number): string {
  return `rgba(${rgbList(hex)},${+a.toFixed(3)})`;
}

/** [a] moved toward [b] by [t] (0..1). */
export function mix(a: string, b: string, t: number): string {
  const x = rgb(a);
  const y = rgb(b);
  const f = clamp(t, 0, 1);
  return toHex(x.map((v, i) => v + (y[i] - v) * f));
}

/** Rec.601 luma, 0..1 (BackgroundScheme.kt `luma`). */
export function luma(hex: string): number {
  const [r, g, b] = rgb(hex);
  return clamp((0.299 * r + 0.587 * g + 0.114 * b) / 255, 0, 1);
}

/** WCAG relative luminance. */
export function luminance(hex: string): number {
  const [r, g, b] = rgb(hex).map((v) => {
    const c = v / 255;
    return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

export function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

function clampLumaDark(color: string, maxLuma: number): string {
  const l = luma(color);
  if (l <= maxLuma || l <= 0.0001) return color;
  return mix("#000000", color, maxLuma / l);
}

function clampLumaLight(color: string, minLuma: number): string {
  const l = luma(color);
  if (l >= minLuma) return color;
  return mix(color, "#ffffff", (minLuma - l) / (1 - l));
}

/** Keeps an accent readable on the canvas: brighter on dark, darker on light. */
export function accentForDisplay(accent: string, light: boolean): string {
  const c = normalizeHex(accent) ?? FALLBACK_EMERALD;
  return light ? clampLumaDark(c, 0.55) : clampLumaLight(c, 0.55);
}

/** White or LFly's dark ink, whichever stands out more on [background]. */
export function onColorFor(background: string): string {
  return contrast("#ffffff", background) >= contrast(DARK_TEXT, background) ? "#ffffff" : DARK_TEXT;
}

/** [ink] at [alpha], or more, until it reads at 4.5:1 on every one of [backgrounds]. */
function readableInk(ink: string, backgrounds: string[], alpha: number): string {
  let a = alpha;
  for (; a < 1; a += 0.02) {
    if (backgrounds.every((bg) => contrast(mix(bg, ink, a), bg) >= 4.5)) break;
  }
  return rgba(ink, Math.min(1, a));
}

export function clamp(v: number, lo: number, hi: number): number {
  return Math.min(hi, Math.max(lo, Number.isFinite(v) ? v : lo));
}
