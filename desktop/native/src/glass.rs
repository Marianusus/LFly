//! Liquid Glass, as the Tauri window draws it (Glass.svelte + lens.ts): a layer under an element's
//! content that bends, blurs and saturates what is behind, then tints and lights it. The GPU work
//! is GPUI's `Window::paint_glass` (LFly's patch in vendor/gpui-pre).

use gpui_kit::*;

/// The look of every glass surface, from the theme (apply.ts) and the glass setting.
#[derive(Clone, Copy, Debug)]
pub struct GlassLook {
    /// The glass setting's strength, 0..1 (default 0.75).
    pub strength: f32,
    pub light: bool,
    pub tint: Hsla,
    pub border: Hsla,
    pub highlight: Hsla,
    pub shade: Hsla,
    /// 1 draws the glass as the Windows (WebView2) build does, where the blur fades out toward
    /// the edges and the sharp content shows through; 0 is a clean blur. `LFLY_GLASS_BLEED`.
    pub edge_bleed: f32,
}

fn edge_bleed() -> f32 {
    static V: std::sync::OnceLock<f32> = std::sync::OnceLock::new();
    *V.get_or_init(|| std::env::var("LFLY_GLASS_BLEED").ok().and_then(|v| v.parse().ok()).unwrap_or(1.0))
}

fn css(r: u8, g: u8, b: u8, a: f32) -> Hsla {
    Rgba { r: r as f32 / 255.0, g: g as f32 / 255.0, b: b as f32 / 255.0, a }.into()
}

impl GlassLook {
    /// The dark scheme with no custom theme (Nero / Dinamico scuro).
    pub fn dark(strength: f32) -> Self {
        let k = strength.clamp(0.0, 1.0);
        Self {
            strength: k,
            light: false,
            // apply.ts: #121214 at 0.4 + (1 - k) * 0.4.
            tint: css(0x12, 0x12, 0x14, 0.4 + (1.0 - k) * 0.4),
            border: css(255, 255, 255, 0.15),
            highlight: css(255, 255, 255, 0.5),
            shade: css(0, 0, 0, 0.35),
            edge_bleed: edge_bleed(),
        }
    }

    /// The same glass with the "clear" tint (Glass.svelte `.clear`).
    #[allow(dead_code)]
    pub fn clear(mut self) -> Self {
        self.tint.a *= 0.35;
        self
    }

    pub fn blur(&self) -> Pixels {
        px((12.0 + 18.0 * self.strength).round())
    }

    pub fn saturation(&self) -> f32 {
        1.0 + 0.7 * self.strength
    }

    /// The shadow under every glass (`--lfly-shadow-1`): (colour, y offset, blur radius).
    /// The glass draws it itself, outside its edge only, as CSS does.
    pub fn shadows(&self) -> [(Hsla, Pixels, Pixels); 2] {
        if self.light {
            [(css(0, 0, 0, 0.06), px(1.), px(2.)), (css(0, 0, 0, 0.06), px(2.), px(10.))]
        } else {
            [(css(0, 0, 0, 0.4), px(1.), px(2.)), (css(0, 0, 0, 0.3), px(4.), px(14.))]
        }
    }
}

/// Where the pointer lights the glass, if it is over it (Glass.svelte `--g-mx/--g-my`).
#[derive(Clone, Copy, Debug, Default)]
pub struct Pointer {
    /// In window coordinates.
    pub window: Option<Point<Pixels>>,
}

/// The glass layer: put it first inside a `relative()` element, it fills it.
/// `radius` is the corner radius; a pill passes half its height (it is clamped anyway).
pub fn glass_layer(look: GlassLook, radius: Pixels, pointer: Pointer) -> impl IntoElement {
    canvas(
        |_, _, _| {},
        move |bounds, _, window, _| {
            let k = look.strength;
            let at = pointer.window.filter(|p| bounds.contains(p)).map(|p| {
                let rel = p - bounds.origin;
                point(rel.x / bounds.size.width, rel.y / bounds.size.height)
            });
            let min_half = bounds.size.width.min(bounds.size.height) / 2.0;
            // The light comes from the pointer's side, else the top left (--g-light: 315deg).
            let light_deg = match at {
                Some(p) => (p.y - 0.5).atan2(p.x - 0.5).to_degrees() + 90.0,
                None => 315.0,
            };
            window.paint_glass(
                bounds,
                GlassStyle {
                    corner_radius: radius.min(min_half),
                    blur: look.blur(),
                    saturation: look.saturation(),
                    // lens.ts buildSvg.
                    refraction_height: px(10.0 + 18.0 * k).min(min_half),
                    refraction_amount: px(8.0 + 26.0 * k),
                    dispersion: 0.06 + 0.06 * k,
                    light_angle: light_deg.to_radians(),
                    pointer: at,
                    pointer_alpha: if at.is_some() { 1.0 } else { 0.0 },
                    light_scheme: look.light,
                    flat: false,
                    tint: look.tint,
                    border: look.border,
                    highlight: look.highlight,
                    shade: look.shade,
                    shadows: look.shadows(),
                    edge_bleed: look.edge_bleed,
                },
            );
        },
    )
    .absolute()
    .inset_0()
}
