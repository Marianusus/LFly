// Writes the look in use onto the page as CSS variables:
//   --lfly-*                 LFly's design tokens (see app.css for the full list)
//   --spice-* / --spice-rgb-* Spicetify's color names, so user.css files and older styles keep working

import { COLOR_KEYS, over, spiceFromScheme, type Density, type Theme, type ThemeColors } from "./themes";
import { accentForDisplay, contrast, luminance, mix, normalizeHex, onColorFor, rgba, rgbList, clamp, type CoverAccent, type Scheme } from "./palette";

const STYLE_ID = "lfly-theme-css";
const DENSITY_SCALE: Record<Density, number> = { compact: 0.85, comfortable: 1, spacious: 1.15 };

export type Applied = {
  scheme: Scheme;
  /** A custom theme in use instead of the LFly modes, if any. */
  custom: Theme | null;
  glass: boolean;
  lens: boolean;
  strength: number;
};

/** An LFly scheme for a custom (Spicetify-style) theme, so the new components read it the same way. */
export function schemeFromTheme(theme: Theme, cover: CoverAccent | null): { scheme: Scheme; colors: ThemeColors } {
  const colors: ThemeColors = { ...theme.colors };
  if (cover && theme.dynamic !== "off") {
    const dark = luminance(colors.main) < 0.4;
    const accent = accentForDisplay(cover.primary, !dark);
    colors.button = accent;
    colors["button-active"] = mix(accent, dark ? "#ffffff" : "#000000", 0.18);
    if (theme.dynamic === "full") {
      colors.main = mix(colors.main, cover.primary, dark ? 0.22 : 0.12);
      colors["main-elevated"] = mix(colors.main, colors.text, 0.06);
      colors.card = mix(colors.main, colors.text, 0.05);
      colors.highlight = mix(colors.main, colors.text, 0.1);
      colors["highlight-elevated"] = mix(colors.main, colors.text, 0.14);
      colors.sidebar = mix(colors.sidebar, cover.primary, dark ? 0.14 : 0.08);
      colors.player = mix(colors.player, cover.primary, dark ? 0.14 : 0.08);
    }
  }
  for (const key of COLOR_KEYS) colors[key] = normalizeHex(colors[key]) ?? "#ff00ff";
  const light = luminance(colors.main) >= 0.4;
  const scheme: Scheme = {
    mode: light ? "PURE_WHITE" : "AMOLED_BLACK",
    light,
    gradient: [colors.sidebar, colors.main, colors.main],
    text: colors.text,
    text2: colors.subtext,
    text3: contrast(colors.misc, colors.main) >= 4.5 ? colors.misc : colors.subtext,
    glassTint: light ? "rgba(0,0,0,0.06)" : "rgba(255,255,255,0.08)",
    glassBorder: rgba(colors.text, light ? 0.12 : 0.15),
    accent: colors.button,
    accent2: colors["button-active"],
    onAccent: onColorFor(colors.button),
    scrim: rgba(colors.shadow, light ? 0.25 : 0.6),
    bg: colors.main,
    surface: colors["main-elevated"],
    surface2: colors.highlight,
    surface3: colors["highlight-elevated"],
    glow: null,
  };
  return { scheme, colors };
}

export function applyLook({ scheme: s, custom, glass, lens, strength }: Applied, cover: CoverAccent | null) {
  const root = document.documentElement;
  const set = (k: string, v: string) => root.style.setProperty(k, v);

  let colors: ThemeColors;
  if (custom) {
    const r = schemeFromTheme(custom, cover);
    s = r.scheme;
    colors = r.colors;
  } else {
    colors = spiceFromScheme(s);
  }

  // ---- LFly tokens ----
  set("--lfly-bg", s.bg);
  set("--lfly-bg-top", s.gradient[0]);
  set("--lfly-bg-mid", s.gradient[1]);
  set("--lfly-surface", s.surface);
  set("--lfly-surface-2", s.surface2);
  set("--lfly-surface-3", s.surface3);
  set("--lfly-text", s.text);
  set("--lfly-text-2", s.text2);
  set("--lfly-text-3", s.text3);
  set("--lfly-accent", s.accent);
  set("--lfly-accent-2", s.accent2);
  set("--lfly-on-accent", s.onAccent);
  set("--lfly-accent-soft", rgba(s.accent, s.light ? 0.14 : 0.2));
  set("--lfly-rgb-accent", rgbList(s.accent));
  set("--lfly-rgb-text", rgbList(over(s.text, s.bg)));
  set("--lfly-rgb-bg", rgbList(s.bg));
  set("--lfly-fill", s.light ? "rgba(21,21,26,0.06)" : "rgba(255,255,255,0.08)");
  set("--lfly-fill-2", s.light ? "rgba(21,21,26,0.1)" : "rgba(255,255,255,0.14)");
  // Content cards: the phone's glass tint (white 8% on dark), so the canvas color shows through.
  set("--lfly-card", custom ? s.surface : s.light ? "rgba(255,255,255,0.72)" : "rgba(255,255,255,0.065)");
  set("--lfly-card-hover", custom ? s.surface2 : s.light ? "rgba(255,255,255,0.92)" : "rgba(255,255,255,0.1)");
  set("--lfly-separator", s.light ? "rgba(21,21,26,0.1)" : "rgba(255,255,255,0.1)");
  set("--lfly-scrim", s.scrim);
  set("--lfly-glass-border", s.glassBorder);
  set("--lfly-glass-highlight", s.light ? "rgba(255,255,255,0.9)" : "rgba(255,255,255,0.5)");
  set("--lfly-glass-shade", s.light ? "rgba(0,0,0,0.08)" : "rgba(0,0,0,0.35)");
  set("--lfly-danger", s.light ? "#d70015" : "#ff453a");
  // The chrome glass of the phone (ChromeGlass.kt): #121212 or #FAFAFA at 40%, a little more when
  // the person asked for less, and opaque when transparency is off.
  const k = clamp(strength, 0, 1);
  const tintBase = custom ? colors.main : s.light ? "#fafafa" : "#121214";
  const tintAlpha = !glass ? 0.97 : s.light ? 0.5 + (1 - k) * 0.35 : 0.4 + (1 - k) * 0.4;
  set("--lfly-glass-tint", rgba(tintBase, tintAlpha));
  set("--lfly-glass-tint-clear", rgba(tintBase, glass ? tintAlpha * 0.35 : 0.97));
  set("--lfly-glass-tint-accent", rgba(s.accent, glass ? 0.5 : 0.95));
  set("--lfly-glass-blur", glass ? `${Math.round(12 + 18 * k)}px` : "0px");
  set("--lfly-glass-sat", glass ? String(1 + 0.7 * k) : "1");
  set("--lfly-shadow-1", s.light ? "0 1px 2px rgba(0,0,0,0.06), 0 2px 10px rgba(0,0,0,0.06)" : "0 1px 2px rgba(0,0,0,0.4), 0 4px 14px rgba(0,0,0,0.3)");
  set("--lfly-shadow-2", s.light ? "0 2px 6px rgba(0,0,0,0.06), 0 18px 48px rgba(20,20,40,0.16)" : "0 2px 8px rgba(0,0,0,0.4), 0 24px 64px rgba(0,0,0,0.55)");

  // ---- Spicetify names, for user.css and the styles not moved yet ----
  for (const key of COLOR_KEYS) {
    set(`--spice-${key}`, colors[key]);
    set(`--spice-rgb-${key}`, rgbList(colors[key]));
  }
  set("--on-button", s.onAccent);

  // ---- Shape, type, density (custom themes can change them) ----
  set("--font-ui", custom?.fontUi || "var(--font-system)");
  set("--font-display", custom?.fontDisplay || custom?.fontUi || "var(--font-system-display)");
  set("--radius", `${clamp(custom ? custom.radius : 12, 0, 28)}px`);
  set("--density", String(custom ? (DENSITY_SCALE[custom.density] ?? 1) : 1));
  // The Backdrop paints the picture itself (so the glass can refract it): the old layer stays empty.
  set("--bg-image", "none");
  set("--bg-opacity", String(clamp(custom?.backgroundOpacity ?? 0.25, 0, 1)));

  root.dataset.glass = glass && (!custom || custom.glass) ? "on" : "off";
  root.dataset.lens = lens ? "on" : "off";
  root.dataset.scheme = s.light ? "light" : "dark";
  root.dataset.mode = custom ? "custom" : s.mode;
  root.style.colorScheme = s.light ? "light" : "dark";

  let style = document.getElementById(STYLE_ID) as HTMLStyleElement | null;
  if (!style) {
    style = document.createElement("style");
    style.id = STYLE_ID;
    document.head.appendChild(style);
  }
  const css = custom?.css ?? "";
  if (style.textContent !== css) style.textContent = css;
  return s;
}
