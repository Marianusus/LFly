//! The app canvas behind everything (Tauri: glass/scene.ts + Backdrop.svelte), painted on the CPU:
//! the three-stop gradient, the ambient glow, the grain. Painted again only when the look or the
//! window size changes, at full size so the grain stays as fine as the Tauri one (Tauri paints the
//! colours at a third and lays the grain over at full size; the flowing cover will need that).

use std::sync::Arc;

use gpui_kit::{Image, ImageFormat};
use tiny_skia::{
    Color, GradientStop, LinearGradient, Paint, Pixmap, Point, RadialGradient, Rect, SpreadMode, Transform,
};

use crate::theme::{Rgb, Scheme};

const SCALE: f32 = 1.0;

fn color(c: Rgb, a: f32) -> Color {
    Color::from_rgba8(c.0 as u8, c.1 as u8, c.2 as u8, (a.clamp(0., 1.) * 255.).round() as u8)
}

/// What the scene shows; equal specs paint the same picture.
#[derive(Clone, Debug, PartialEq)]
pub struct SceneSpec {
    pub gradient: [Rgb; 3],
    pub glow: Option<Rgb>,
    pub light: bool,
    /// The grain over the dynamic modes (Backdrop.svelte, not on Nero AMOLED / Bianco).
    pub grain: bool,
}

impl SceneSpec {
    pub fn from_scheme(s: &Scheme) -> Self {
        use crate::theme::Mode;
        Self {
            gradient: s.gradient,
            glow: s.glow,
            light: s.light,
            grain: !matches!(s.mode, Mode::AmoledBlack | Mode::PureWhite),
        }
    }
}

/// The scene for a window of [w] x [h] logical px, as PNG (small: a third of the size).
pub fn paint(spec: &SceneSpec, w: f32, h: f32) -> Option<Arc<Image>> {
    let cw = ((w * SCALE).ceil() as u32).max(16);
    let ch = ((h * SCALE).ceil() as u32).max(16);
    let mut pm = Pixmap::new(cw, ch)?;
    let (fw, fh) = (cw as f32, ch as f32);
    let full = Rect::from_xywh(0., 0., fw, fh)?;

    // The gradient, top to bottom.
    let g = spec.gradient;
    let stops = vec![
        GradientStop::new(0.0, color(g[0], 1.)),
        GradientStop::new(0.5, color(g[1], 1.)),
        GradientStop::new(1.0, color(g[2], 1.)),
    ];
    let mut paint = Paint::default();
    paint.shader = LinearGradient::new(Point::from_xy(0., 0.), Point::from_xy(0., fh), stops, SpreadMode::Pad, Transform::identity())?;
    pm.fill_rect(full, &paint, Transform::identity(), None);

    // The phone's ambient glow, stretched for a wide window and lifted toward the top.
    if let Some(glow) = spec.glow {
        let r = fw.max(fh) * 0.62;
        let (a0, a1) = if spec.light { (0.28, 0.1) } else { (0.32, 0.12) };
        let stops = vec![
            GradientStop::new(0.0, color(glow, a0)),
            GradientStop::new(0.5, color(glow, a1)),
            GradientStop::new(1.0, color(glow, 0.)),
        ];
        let t = Transform::from_translate(fw * 0.5, fh * 0.3).pre_scale(1., (fh / fw) * 1.5);
        let mut paint = Paint::default();
        paint.shader = RadialGradient::new(Point::from_xy(0., 0.), Point::from_xy(0., 0.), r, stops, SpreadMode::Pad, Transform::identity())?;
        pm.fill_rect(Rect::from_xywh(-r, -r, r * 2., r * 2.)?, &paint, t, None);
    }

    if spec.grain {
        grain(&mut pm);
    }

    let png = pm.encode_png().ok()?;
    Some(Arc::new(Image::from_bytes(ImageFormat::Png, png)))
}

/// A whisper of grain (5%, overlay): no banding in the soft gradients. Deterministic noise.
fn grain(pm: &mut Pixmap) {
    let mut seed = 0x9e37_79b9u32;
    for px in pm.pixels_mut() {
        seed ^= seed << 13;
        seed ^= seed >> 17;
        seed ^= seed << 5;
        let n = (seed & 0xff) as f32 / 255.; // 0..1
        let c = px.demultiply();
        let blend = |b: u8| -> u8 {
            let b = b as f32 / 255.;
            // overlay(base, noise) at 5%
            let o = if b < 0.5 { 2. * b * n } else { 1. - 2. * (1. - b) * (1. - n) };
            ((b + (o - b) * 0.05) * 255.).round().clamp(0., 255.) as u8
        };
        let c = tiny_skia::ColorU8::from_rgba(blend(c.red()), blend(c.green()), blend(c.blue()), c.alpha());
        *px = c.premultiply();
    }
}

/// The playing cover's two accent colours (coverArt.ts: a 48 px copy, CoverPalette.kt).
pub fn cover_colors(bytes: &[u8]) -> Option<(Rgb, Rgb)> {
    let img = image::load_from_memory(bytes).ok()?;
    let small = img.resize_exact(48, 48, image::imageops::FilterType::Triangle).to_rgba8();
    Some(crate::theme::cover_accent(small.as_raw()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn paints_a_full_window_quickly() {
        let s = crate::theme::build_scheme(crate::theme::Mode::DynamicDark, None, None);
        let t = std::time::Instant::now();
        assert!(paint(&SceneSpec::from_scheme(&s), 1100., 720.).is_some());
        println!("scene 1100x720: {:?}", t.elapsed());
    }
}
