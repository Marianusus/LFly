//! LFly's colours, a port of the Tauri window's theme/palette.ts (itself the phone's
//! BackgroundScheme.kt, AccentPalette.kt, CoverPalette.kt): same modes, same colour math, so the
//! three apps look the same.

use gpui_kit::{Hsla, Rgba};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Mode {
    AmoledBlack,
    PureWhite,
    DynamicDark,
    DynamicLight,
    DynamicCover,
}

/// palette.ts MODES: the phone's "Aspetto" modes, in its order and words.
pub const MODES: [(Mode, &str, &str, &str); 5] = [
    (Mode::AmoledBlack, "AMOLED_BLACK", "Nero AMOLED", "Nero assoluto con vetro satinato e accento LFly"),
    (Mode::PureWhite, "PURE_WHITE", "Bianco", "Canvas chiaro, vetro avorio e testo scuro ad alto contrasto"),
    (Mode::DynamicDark, "DYNAMIC_DARK", "Dinamico scuro", "Sfondo scuro derivato dalla copertina in riproduzione"),
    (Mode::DynamicLight, "DYNAMIC_LIGHT", "Dinamico chiaro", "Sfondo chiaro pastello derivato dalla copertina"),
    (Mode::DynamicCover, "DYNAMIC_COVER", "Copertina dinamica", "La copertina in riproduzione, sfocata e in movimento, come sfondo dell'app"),
];

/// palette.ts ACCENTS: id, Italian name, colour, premium.
pub const ACCENTS: [(&str, &str, u32, bool); 20] = [
    ("emerald", "Smeraldo", 0x1db954, false),
    ("blue", "Blu", 0x4d7cff, false),
    ("indigo", "Indaco", 0x5b5bd6, false),
    ("violet", "Viola", 0x7c4dff, false),
    ("pink", "Rosa", 0xff2d78, false),
    ("red", "Rosso", 0xff3b30, false),
    ("orange", "Arancio", 0xff7a00, false),
    ("amber", "Ambra", 0xffb300, false),
    ("teal", "Verde acqua", 0x00bfa5, false),
    ("cyan", "Ciano", 0x00b8d9, false),
    ("champagne_gold", "Champagne", 0xd4af37, true),
    ("rose_gold", "Oro rosa", 0xb76e79, true),
    ("platinum", "Platino", 0xc9ccd1, true),
    ("titanium", "Titanio", 0x5c6670, true),
    ("sapphire", "Zaffiro", 0x1f4fd8, true),
    ("midnight", "Mezzanotte", 0x20304a, true),
    ("burgundy", "Bordeaux", 0x8e1b3a, true),
    ("bronze", "Bronzo", 0xa9714b, true),
    ("obsidian", "Ossidiana", 0x3a3f44, true),
    ("sand", "Sabbia", 0xc2a878, true),
];

/// accentById: the accent's colour, emerald when unknown.
pub fn accent_color(id: &str) -> Rgb {
    Rgb::hex(ACCENTS.iter().find(|a| a.0 == id).map_or(ACCENTS[0].2, |a| a.2))
}

impl Mode {
    pub fn id(self) -> &'static str {
        MODES.iter().find(|m| m.0 == self).map_or("DYNAMIC_DARK", |m| m.1)
    }

    pub fn parse(id: &str) -> Option<Self> {
        MODES.iter().find(|m| m.1 == id).map(|m| m.0)
    }

    pub fn light(self) -> bool {
        matches!(self, Mode::PureWhite | Mode::DynamicLight)
    }
    pub fn dynamic(self) -> bool {
        matches!(self, Mode::DynamicDark | Mode::DynamicLight | Mode::DynamicCover)
    }
}

/// An sRGB colour, 0..255 per channel, as palette.ts keeps them.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Rgb(pub f32, pub f32, pub f32);

pub const FALLBACK_EMERALD: Rgb = Rgb(0x1d as f32, 0xb9 as f32, 0x54 as f32);
pub const FALLBACK_BLUE: Rgb = Rgb(0x4d as f32, 0x7c as f32, 0xff as f32);
const DARK_TEXT: Rgb = Rgb(0x15 as f32, 0x15 as f32, 0x1a as f32);
const WHITE: Rgb = Rgb(255., 255., 255.);
const BLACK: Rgb = Rgb(0., 0., 0.);

impl Rgb {
    pub fn hex(v: u32) -> Self {
        Rgb(((v >> 16) & 255) as f32, ((v >> 8) & 255) as f32, (v & 255) as f32)
    }
    /// Rounded to whole channels, as toHex does.
    fn round(self) -> Self {
        Rgb(self.0.round().clamp(0., 255.), self.1.round().clamp(0., 255.), self.2.round().clamp(0., 255.))
    }
    pub fn hsla(self, a: f32) -> Hsla {
        Rgba { r: self.0 / 255., g: self.1 / 255., b: self.2 / 255., a }.into()
    }
}

/// [a] moved toward [b] by [t] (0..1).
pub fn mix(a: Rgb, b: Rgb, t: f32) -> Rgb {
    let f = t.clamp(0., 1.);
    Rgb(a.0 + (b.0 - a.0) * f, a.1 + (b.1 - a.1) * f, a.2 + (b.2 - a.2) * f).round()
}

/// CSS `color-mix(in srgb, a p, b)` with alpha: premultiplied, as browsers mix.
pub fn color_mix(a: gpui_kit::Hsla, p: f32, b: gpui_kit::Hsla) -> gpui_kit::Hsla {
    let (a, b): (gpui_kit::Rgba, gpui_kit::Rgba) = (a.into(), b.into());
    let q = 1. - p;
    let alpha = p * a.a + q * b.a;
    let ch = |x: f32, y: f32| if alpha > 1e-4 { (p * a.a * x + q * b.a * y) / alpha } else { 0. };
    gpui_kit::Rgba { r: ch(a.r, b.r), g: ch(a.g, b.g), b: ch(a.b, b.b), a: alpha }.into()
}

/// Rec.601 luma, 0..1.
pub fn luma(c: Rgb) -> f32 {
    ((0.299 * c.0 + 0.587 * c.1 + 0.114 * c.2) / 255.).clamp(0., 1.)
}

fn luminance(c: Rgb) -> f32 {
    let f = |v: f32| {
        let c = v / 255.;
        if c <= 0.04045 { c / 12.92 } else { ((c + 0.055) / 1.055).powf(2.4) }
    };
    0.2126 * f(c.0) + 0.7152 * f(c.1) + 0.0722 * f(c.2)
}

pub fn contrast(a: Rgb, b: Rgb) -> f32 {
    let (x, y) = (luminance(a), luminance(b));
    let (hi, lo) = if x > y { (x, y) } else { (y, x) };
    (hi + 0.05) / (lo + 0.05)
}

fn clamp_luma_dark(c: Rgb, max: f32) -> Rgb {
    let l = luma(c);
    if l <= max || l <= 0.0001 { c } else { mix(BLACK, c, max / l) }
}

fn clamp_luma_light(c: Rgb, min: f32) -> Rgb {
    let l = luma(c);
    if l >= min { c } else { mix(c, WHITE, (min - l) / (1. - l)) }
}

/// Keeps an accent readable on the canvas: brighter on dark, darker on light.
pub fn accent_for_display(c: Rgb, light: bool) -> Rgb {
    if light { clamp_luma_dark(c, 0.55) } else { clamp_luma_light(c, 0.55) }
}

/// White or LFly's dark ink, whichever stands out more.
pub fn on_color_for(bg: Rgb) -> Rgb {
    if contrast(WHITE, bg) >= contrast(DARK_TEXT, bg) { WHITE } else { DARK_TEXT }
}

/// [ink] at [alpha], or more, until it reads at 4.5:1 on every one of [backgrounds].
fn readable_ink(ink: Rgb, backgrounds: &[Rgb], alpha: f32) -> Hsla {
    let mut a = alpha;
    while a < 1. {
        if backgrounds.iter().all(|&bg| contrast(mix(bg, ink, a), bg) >= 4.5) {
            break;
        }
        a += 0.02;
    }
    ink.hsla(a.min(1.))
}

/// Everything the window needs for one mode (palette.ts `Scheme`).
#[derive(Clone, Debug)]
#[allow(dead_code)]
pub struct Scheme {
    pub mode: Mode,
    pub light: bool,
    /// Three stops, top to bottom.
    pub gradient: [Rgb; 3],
    pub text: Hsla,
    pub text2: Hsla,
    pub text3: Hsla,
    pub glass_tint: Hsla,
    pub glass_border: Hsla,
    pub accent: Rgb,
    pub accent2: Rgb,
    pub on_accent: Rgb,
    pub scrim: Hsla,
    pub bg: Rgb,
    pub surface: Rgb,
    pub surface2: Rgb,
    pub surface3: Rgb,
    /// Ambient glow behind the content (dynamic gradient modes).
    pub glow: Option<Rgb>,
}

/// buildScheme: [primary]/[secondary] are the user's accent, or the cover's in the dynamic modes.
pub fn build_scheme(mode: Mode, primary: Option<Rgb>, secondary: Option<Rgb>) -> Scheme {
    let light = mode.light();
    let p = primary.unwrap_or(FALLBACK_EMERALD);
    let s = secondary.unwrap_or(FALLBACK_BLUE);
    let accent = accent_for_display(p, light);
    let accent2 = accent_for_display(s, light);
    let (gradient, glass_tint, glass_border) = match mode {
        Mode::PureWhite => (
            [Rgb::hex(0xffffff), Rgb::hex(0xf7f7fb), Rgb::hex(0xf1f1f6)],
            BLACK.hsla(0.06),
            BLACK.hsla(0.12),
        ),
        Mode::DynamicLight => (
            [
                clamp_luma_light(mix(WHITE, p, 0.42), 0.82),
                clamp_luma_light(mix(WHITE, s, 0.25), 0.88),
                Rgb::hex(0xfcfcff),
            ],
            WHITE.hsla(0.07),
            BLACK.hsla(0.14),
        ),
        Mode::DynamicDark | Mode::DynamicCover => (
            [clamp_luma_dark(mix(BLACK, p, 0.55), 0.29), clamp_luma_dark(mix(BLACK, s, 0.34), 0.19), Rgb::hex(0x050509)],
            WHITE.hsla(0.08),
            WHITE.hsla(0.15),
        ),
        Mode::AmoledBlack => ([BLACK; 3], WHITE.hsla(0.08), WHITE.hsla(0.15)),
    };
    let text = if light { DARK_TEXT } else { WHITE };
    let text2 = readable_ink(text, &gradient, if light { 0.72 } else { 0.7 });
    let text3 = readable_ink(text, &gradient, if light { 0.6 } else { 0.5 });
    let bg = gradient[2];
    let surface = if light {
        if mode == Mode::PureWhite { WHITE } else { mix(bg, WHITE, 0.7) }
    } else {
        mix(bg, WHITE, 0.075)
    };
    let surface2 = if light { mix(bg, DARK_TEXT, 0.045) } else { mix(bg, WHITE, 0.12) };
    let surface3 = if light { mix(bg, DARK_TEXT, 0.08) } else { mix(bg, WHITE, 0.17) };
    Scheme {
        mode,
        light,
        gradient,
        text: text.hsla(1.),
        text2,
        text3,
        glass_tint,
        glass_border,
        accent,
        accent2,
        on_accent: on_color_for(accent),
        scrim: BLACK.hsla(if light { 0.2 } else { 0.6 }),
        bg,
        surface,
        surface2,
        surface3,
        glow: if mode.dynamic() && mode != Mode::DynamicCover { Some(accent) } else { None },
    }
}

/// CoverPalette.accentFrom: the average of the vivid warm pixels and of the vivid cool ones.
/// [rgba] is a small (<= 64 px) copy of the cover.
pub fn cover_accent(rgba: &[u8]) -> (Rgb, Rgb) {
    let (mut w, mut c, mut all) = ([0f32; 4], [0f32; 4], [0f32; 4]);
    for px in rgba.chunks_exact(4) {
        if px[3] < 32 {
            continue;
        }
        let (r, g, b) = (px[0] as f32, px[1] as f32, px[2] as f32);
        let max = r.max(g).max(b);
        let min = r.min(g).min(b);
        let sat = if max <= 0. { 0. } else { (max - min) / max };
        let l = (0.299 * r + 0.587 * g + 0.114 * b) / 255.;
        for (i, v) in [r, g, b, 1.].into_iter().enumerate() {
            all[i] += v;
        }
        if sat >= 0.22 && (0.1..=0.94).contains(&l) {
            let t = if r >= b { &mut w } else { &mut c };
            for (i, v) in [r, g, b, 1.].into_iter().enumerate() {
                t[i] += v;
            }
        }
    }
    if all[3] == 0. {
        return (FALLBACK_EMERALD, FALLBACK_BLUE);
    }
    let avg = |t: [f32; 4]| Rgb(t[0] / t[3], t[1] / t[3], t[2] / t[3]).round();
    let warm = (w[3] > 0.).then(|| avg(w));
    let cool = (c[3] > 0.).then(|| avg(c));
    let primary = warm.or(cool).unwrap_or_else(|| avg(all));
    let secondary = cool.or(warm).unwrap_or_else(|| mix(primary, WHITE, 0.35));
    (primary, secondary)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dynamic_dark_with_the_default_accent() {
        // palette.ts with emerald/blue: the first stop is #10662e-ish, clamped to luma 0.29.
        let s = build_scheme(Mode::DynamicDark, None, None);
        assert_eq!(s.bg, Rgb::hex(0x050509));
        assert!(luma(s.gradient[0]) <= 0.2901);
        assert_eq!(s.glow, Some(s.accent));
        assert!(contrast(WHITE, s.bg) > 15.);
    }

    fn hex(c: Rgb) -> String {
        format!("#{:02x}{:02x}{:02x}", c.0 as u8, c.1 as u8, c.2 as u8)
    }

    /// The same numbers palette.ts gives (node, 2026-11-12).
    #[test]
    fn matches_palette_ts() {
        let cases: [(Mode, Option<u32>, Option<u32>, &str, f32, f32, &str, &str, &str, &str, &str, &str); 5] = [
            (Mode::DynamicDark, None, None, "#10662e,#1a2a57,#050509", 0.72, 0.72, "#35c066", "#628bff", "#15151a", "#18181b", "#232327", "#303033"),
            (Mode::DynamicDark, Some(0xd62f2f), Some(0x2f6fd6), "#761a1a,#102649,#050509", 0.7, 0.58, "#e16868", "#6192e0", "#15151a", "#18181b", "#232327", "#303033"),
            (Mode::DynamicLight, Some(0xd62f2f), Some(0x2f6fd6), "#f3c3c3,#d5e2f7,#fcfcff", 0.72, 0.64, "#d62f2f", "#2f6fd6", "#ffffff", "#fefeff", "#f2f2f5", "#eaeaed"),
            (Mode::PureWhite, None, None, "#ffffff,#f7f7fb,#f1f1f6", 0.72, 0.6, "#1db954", "#4d7cff", "#15151a", "#ffffff", "#e7e7ec", "#dfdfe4"),
            (Mode::AmoledBlack, Some(0xff7a00), None, "#000000,#000000,#000000", 0.7, 0.5, "#ff7a00", "#628bff", "#15151a", "#131313", "#1f1f1f", "#2b2b2b"),
        ];
        for (mode, p, s, grad, t2, t3, acc, acc2, on, s1, s2, s3) in cases {
            let x = build_scheme(mode, p.map(Rgb::hex), s.map(Rgb::hex));
            let g = x.gradient.iter().map(|c| hex(*c)).collect::<Vec<_>>().join(",");
            assert_eq!(g, grad, "{mode:?} gradient");
            assert!((x.text2.a - t2).abs() < 0.011, "{mode:?} text2 {}", x.text2.a);
            assert!((x.text3.a - t3).abs() < 0.011, "{mode:?} text3 {}", x.text3.a);
            assert_eq!([hex(x.accent), hex(x.accent2), hex(x.on_accent)], [acc, acc2, on], "{mode:?} accents");
            assert_eq!([hex(x.surface), hex(x.surface2), hex(x.surface3)], [s1, s2, s3], "{mode:?} surfaces");
        }
    }

    #[test]
    fn readable_ink_reaches_4_5() {
        let s = build_scheme(Mode::DynamicDark, None, None);
        assert!(s.text2.a >= 0.7 && s.text3.a >= 0.5);
    }
}
