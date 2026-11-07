// Custom themes (Settings > Aspetto > Avanzate), Spicetify style: a theme is a color scheme with the same color names Spicetify uses
// (so its color.ini files import as they are), plus fonts, shape, density, effects and custom CSS.
//
// Every color becomes two CSS variables, exactly as Spicetify names them:
//   --spice-<name>       the hex color
//   --spice-rgb-<name>   "r,g,b", for rgba(var(--spice-rgb-text), 0.5)
// so a user.css written for those variables works here too.

import { mix, normalizeHex, toHex, type Scheme } from "./palette";

export const COLOR_KEYS = [
  "text",
  "subtext",
  "main",
  "main-elevated",
  "highlight",
  "highlight-elevated",
  "sidebar",
  "player",
  "card",
  "shadow",
  "selected-row",
  "button",
  "button-active",
  "button-disabled",
  "tab-active",
  "notification",
  "notification-error",
  "misc",
] as const;

export type ColorKey = (typeof COLOR_KEYS)[number];
export type ThemeColors = Record<ColorKey, string>;

export type Density = "compact" | "comfortable" | "spacious";
/** Colors taken from the playing song's cover: none, only the accent, or the whole window. */
export type Dynamic = "off" | "accent" | "full";

export type Theme = {
  id: string;
  name: string;
  author?: string;
  builtin?: boolean;
  colors: ThemeColors;
  /** CSS font-family lists; empty means the system font. */
  fontUi: string;
  fontDisplay: string;
  /** Corner radius of panels and cards, px. */
  radius: number;
  density: Density;
  /** Frosted glass on the player bar and side panels. */
  glass: boolean;
  dynamic: Dynamic;
  /** Picture behind the whole window (a data: or https: URL), and how much of it shows. */
  background?: string;
  backgroundOpacity: number;
  /** Extra CSS, applied last. */
  css: string;
};

/** Human names for the editor. */
export const COLOR_LABELS: Record<ColorKey, string> = {
  text: "Testo",
  subtext: "Testo secondario",
  main: "Sfondo principale",
  "main-elevated": "Sfondo rialzato",
  highlight: "Evidenziato",
  "highlight-elevated": "Evidenziato rialzato",
  sidebar: "Barra laterale",
  player: "Barra del player",
  card: "Schede",
  shadow: "Ombra",
  "selected-row": "Riga selezionata",
  button: "Accento",
  "button-active": "Accento attivo",
  "button-disabled": "Disattivato",
  "tab-active": "Scheda attiva",
  notification: "Notifica",
  "notification-error": "Errore",
  misc: "Varie",
};

const base = {
  fontUi: "",
  fontDisplay: "",
  radius: 8,
  density: "comfortable" as Density,
  glass: true,
  dynamic: "accent" as Dynamic,
  backgroundOpacity: 0.25,
  css: "",
  builtin: true,
};

function scheme(
  id: string,
  name: string,
  author: string,
  c: Partial<ThemeColors> & Pick<ThemeColors, "text" | "subtext" | "main" | "sidebar" | "button">,
  extra: Partial<Theme> = {},
): Theme {
  const colors: ThemeColors = {
    "main-elevated": mix(c.main, c.text, 0.06),
    highlight: mix(c.main, c.text, 0.1),
    "highlight-elevated": mix(c.main, c.text, 0.14),
    player: c.sidebar,
    card: mix(c.main, c.text, 0.04),
    shadow: "#000000",
    "selected-row": c.text,
    "button-active": mix(c.button, "#ffffff", 0.15),
    "button-disabled": mix(c.main, c.text, 0.3),
    "tab-active": mix(c.main, c.text, 0.12),
    notification: "#3d91f4",
    "notification-error": "#e22134",
    misc: mix(c.main, c.text, 0.5),
    ...c,
  };
  return { ...base, id, name, author, colors, ...extra };
}

export const EXTRA_THEMES: Theme[] = [
  // The palettes below are open-source schemes, used under their MIT licenses.
  scheme("catppuccin-mocha", "Catppuccin Mocha", "Catppuccin", {
    text: "#cdd6f4",
    subtext: "#a6adc8",
    main: "#1e1e2e",
    sidebar: "#11111b",
    player: "#181825",
    card: "#313244",
    button: "#cba6f7",
    "button-active": "#f5c2e7",
    notification: "#89b4fa",
    "notification-error": "#f38ba8",
  }),
  scheme(
    "catppuccin-latte",
    "Catppuccin Latte",
    "Catppuccin",
    {
      text: "#4c4f69",
      subtext: "#5c5f77",
      main: "#eff1f5",
      sidebar: "#dce0e8",
      player: "#e6e9ef",
      card: "#ccd0da",
      button: "#8839ef",
      "button-active": "#ea76cb",
      shadow: "#9ca0b0",
      notification: "#1e66f5",
      "notification-error": "#d20f39",
    },
  ),
  scheme("nord", "Nord", "Arctic Ice Studio", {
    text: "#eceff4",
    subtext: "#d8dee9",
    main: "#2e3440",
    sidebar: "#242933",
    player: "#3b4252",
    card: "#3b4252",
    button: "#88c0d0",
    "button-active": "#8fbcbb",
    notification: "#81a1c1",
    "notification-error": "#bf616a",
  }),
  scheme("dracula", "Dracula", "Dracula Theme", {
    text: "#f8f8f2",
    subtext: "#bdc1d6",
    main: "#282a36",
    sidebar: "#1e1f29",
    player: "#21222c",
    card: "#343746",
    button: "#ff79c6",
    "button-active": "#bd93f9",
    notification: "#8be9fd",
    "notification-error": "#ff5555",
  }),
  scheme("gruvbox", "Gruvbox", "morhetz", {
    text: "#ebdbb2",
    subtext: "#bdae93",
    main: "#282828",
    sidebar: "#1d2021",
    player: "#32302f",
    card: "#3c3836",
    button: "#fabd2f",
    "button-active": "#fe8019",
    notification: "#83a598",
    "notification-error": "#fb4934",
  }),
  scheme("rose-pine", "Rosé Pine", "Rosé Pine", {
    text: "#e0def4",
    subtext: "#908caa",
    main: "#191724",
    sidebar: "#12101b",
    player: "#1f1d2e",
    card: "#26233a",
    button: "#ebbcba",
    "button-active": "#f6c177",
    notification: "#9ccfd8",
    "notification-error": "#eb6f92",
  }),
  scheme("tokyo-night", "Tokyo Night", "enkia", {
    text: "#c0caf5",
    subtext: "#a9b1d6",
    main: "#1a1b26",
    sidebar: "#16161e",
    player: "#1f2335",
    card: "#24283b",
    button: "#7aa2f7",
    "button-active": "#bb9af7",
    notification: "#7dcfff",
    "notification-error": "#f7768e",
  }),
];


// ---- Import and export ----

/** A theme file as LFly writes it: the theme without its id or builtin flag. */
export function exportTheme(theme: Theme): string {
  const { id: _id, builtin: _b, ...rest } = theme;
  return JSON.stringify({ lflyTheme: 1, ...rest }, null, 2);
}

export function newId(name: string): string {
  const slug = name
    .toLowerCase()
    .normalize("NFD")
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-|-$/g, "");
  return `${slug || "tema"}-${Date.now().toString(36)}`;
}

/** Reads an LFly theme file (JSON). Missing parts come from [fallback]. */
export function importThemeJson(text: string, fallback: Theme): Theme {
  const data = JSON.parse(text);
  if (typeof data !== "object" || data == null) throw new Error("Non è un tema LFly");
  const colors = { ...fallback.colors };
  for (const key of COLOR_KEYS) {
    const v = normalizeHex(data.colors?.[key]);
    if (v) colors[key] = v;
  }
  const name = typeof data.name === "string" && data.name.trim() ? data.name.trim() : "Tema importato";
  return {
    ...fallback,
    ...pick(data, ["author", "fontUi", "fontDisplay", "radius", "density", "glass", "dynamic", "background", "backgroundOpacity", "css"]),
    id: newId(name),
    name,
    builtin: false,
    colors,
  };
}

/**
 * Reads a Spicetify color.ini: every [section] is one scheme. Values are hex without '#',
 * optionally followed by a comment; `${xrdb:...}` values are skipped.
 */
export function parseColorIni(text: string): { name: string; colors: Partial<ThemeColors> }[] {
  const out: { name: string; colors: Partial<ThemeColors> }[] = [];
  let current: { name: string; colors: Partial<ThemeColors> } | null = null;
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.replace(/[;#].*$/, (m) => (/^#[0-9a-f]{3,8}\b/i.test(m) ? m : "")).trim();
    if (!line) continue;
    const section = line.match(/^\[(.+)]$/);
    if (section) {
      current = { name: section[1].trim(), colors: {} };
      out.push(current);
      continue;
    }
    const kv = line.match(/^([\w-]+)\s*=\s*([#\w]+)/);
    if (!kv || !current) continue;
    const key = kv[1].toLowerCase() as ColorKey;
    const value = normalizeHex(kv[2]);
    if ((COLOR_KEYS as readonly string[]).includes(key) && value) current.colors[key] = value;
  }
  return out.filter((s) => Object.keys(s.colors).length > 0);
}

/** A Spicetify scheme on top of [fallback]: what it leaves out is derived like the built-ins. */
export function themeFromSpicetify(name: string, colors: Partial<ThemeColors>, fallback: Theme): Theme {
  const main = colors.main ?? fallback.colors.main;
  const text = colors.text ?? fallback.colors.text;
  const derived = scheme("x", name, "Spicetify", {
    text,
    subtext: colors.subtext ?? mix(text, main, 0.3),
    main,
    sidebar: colors.sidebar ?? main,
    button: colors.button ?? fallback.colors.button,
  }).colors;
  return {
    ...fallback,
    builtin: false,
    id: newId(name),
    name,
    author: "Spicetify",
    colors: { ...derived, ...colors },
  };
}

// ---- From LFly's own design ----

/** The Spicetify color names filled from an LFly scheme, so old CSS and imported themes keep working. */
export function spiceFromScheme(s: Scheme): ThemeColors {
  const solid = (c: string) => over(c, s.bg);
  return {
    text: s.text,
    subtext: solid(s.text2),
    main: s.bg,
    "main-elevated": s.surface,
    highlight: s.surface2,
    "highlight-elevated": s.surface3,
    sidebar: s.bg,
    player: s.surface,
    card: s.surface,
    shadow: "#000000",
    "selected-row": s.text,
    button: s.accent,
    "button-active": mix(s.accent, s.light ? "#000000" : "#ffffff", 0.15),
    "button-disabled": solid(s.text3),
    "tab-active": s.surface2,
    notification: s.light ? "#0a64d8" : "#4d7cff",
    "notification-error": s.light ? "#d70015" : "#ff453a",
    misc: solid(s.text3),
  };
}

/** A custom theme that starts as the LFly look in use. */
export function themeFromScheme(s: Scheme, name: string): Theme {
  // The sidebar color is the canvas's top, so the Backdrop keeps the mode's gradient.
  const colors = { ...spiceFromScheme(s), sidebar: s.gradient[0] };
  return { ...base, builtin: false, id: "", name, author: "LFly", colors, radius: 12, dynamic: "off" };
}

/** A CSS color (hex or rgba) laid on [bg], as an opaque hex. */
export function over(color: string, bg: string): string {
  const hex = normalizeHex(color);
  if (hex) return hex;
  const m = color.match(/rgba?\(([^)]+)\)/);
  if (!m) return bg;
  const [r, g, b, a = "1"] = m[1].split(",").map((x) => x.trim());
  return mix(bg, toHex([+r, +g, +b]), +a);
}

export { normalizeHex, mix, luminance, contrast } from "./palette";

function pick<T extends object>(obj: any, keys: (keyof T | string)[]): Partial<T> {
  const out: any = {};
  for (const k of keys) if (obj[k as string] !== undefined) out[k] = obj[k as string];
  return out;
}
