//! The app canvas behind everything (Tauri: glass/scene.ts + Backdrop.svelte), painted on the CPU:
//! the three-stop gradient, the ambient glow, the grain. Painted again only when the look or the
//! window size changes, at full size so the grain stays as fine as the Tauri one (Tauri paints the
//! colours at a third and lays the grain over at full size; the flowing cover will need that).

use std::sync::Arc;

use gpui_kit::{Image, ImageFormat};
use tiny_skia::{
    Color, FilterQuality, GradientStop, LinearGradient, Paint, Pixmap, PixmapPaint, Point, RadialGradient, Rect, SpreadMode,
    Transform,
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

/// The playing cover read once (coverArt.ts): its two accent colours (CoverPalette.kt) and a
/// tiny blurred, saturated copy that Copertina dinamica draws huge.
pub fn cover_art(bytes: &[u8]) -> Option<((Rgb, Rgb), Arc<Pixmap>)> {
    const N: u32 = 48;
    let img = image::load_from_memory(bytes).ok()?;
    let mut px = img.resize_exact(N, N, image::imageops::FilterType::Triangle).to_rgba8().into_raw();
    let colors = crate::theme::cover_accent(&px);
    box_blur(&mut px, N as usize, N as usize, 2);
    box_blur(&mut px, N as usize, N as usize, 2);
    // ColorMatrix.setToSaturation(1.6), clamped as a Uint8ClampedArray clamps.
    for p in px.chunks_exact_mut(4) {
        let (r, g, b) = (p[0] as f32, p[1] as f32, p[2] as f32);
        let l = 0.213 * r + 0.715 * g + 0.072 * b;
        for (c, v) in [(0, r), (1, g), (2, b)] {
            p[c] = (l + (v - l) * 1.6).round().clamp(0., 255.) as u8;
        }
        p[3] = 255;
    }
    let pm = Pixmap::from_vec(px, tiny_skia::IntSize::from_wh(N, N)?)?;
    Some((colors, Arc::new(pm)))
}

/// coverArt.ts boxBlur: a box of 2r+1, edges clamped, horizontal then vertical.
fn box_blur(px: &mut [u8], w: usize, h: usize, r: i32) {
    let win = (2 * r + 1) as f32;
    let mut tmp = vec![0u8; px.len()];
    let pass = |src: &[u8], dst: &mut [u8], horizontal: bool| {
        let (lines, len) = if horizontal { (h, w) } else { (w, h) };
        for line in 0..lines {
            let idx = |i: i32| {
                let k = i.clamp(0, len as i32 - 1) as usize;
                (if horizontal { line * w + k } else { k * w + line }) * 4
            };
            let mut sum = [0f32; 4];
            for i in -r..=r {
                let p = idx(i);
                for c in 0..4 {
                    sum[c] += src[p + c] as f32;
                }
            }
            for i in 0..len as i32 {
                let o = idx(i);
                for c in 0..4 {
                    // Uint8ClampedArray rounds half to even; plain rounding differs by one at most.
                    dst[o + c] = (sum[c] / win).round().clamp(0., 255.) as u8;
                }
                let (out, inn) = (idx(i - r), idx(i + r + 1));
                for c in 0..4 {
                    sum[c] += src[inn + c] as f32 - src[out + c] as f32;
                }
            }
        }
    };
    pass(px, &mut tmp, true);
    pass(&tmp, px, false);
}

/// One frame of Copertina dinamica (scene.ts paint) into [pm], at time [t] of the loop (radians):
/// the gradient, three copies of the cover turning around wandering centres, the veil.
pub fn paint_flow(pm: &mut Pixmap, gradient: [Rgb; 3], cover: &Pixmap, t: f32) {
    let (w, h) = (pm.width() as f32, pm.height() as f32);
    let Some(full) = Rect::from_xywh(0., 0., w, h) else { return };
    let stops = vec![
        GradientStop::new(0.0, color(gradient[0], 1.)),
        GradientStop::new(0.5, color(gradient[1], 1.)),
        GradientStop::new(1.0, color(gradient[2], 1.)),
    ];
    let mut paint = Paint::default();
    if let Some(sh) = LinearGradient::new(Point::from_xy(0., 0.), Point::from_xy(0., h), stops, SpreadMode::Pad, Transform::identity()) {
        paint.shader = sh;
        pm.fill_rect(full, &paint, Transform::identity(), None);
    }
    let n = cover.width() as f32;
    // (turns, orbit, phase, scale, alpha) as scene.ts draws them.
    for (turns, orbit, phase, scale, alpha) in [(1., 0.16, 0., 1.9, 1.), (-1., 0.22, 2.1, 1.7, 0.8), (2., 0.26, 4.2, 1.6, 0.65)] {
        let reach = (w * w + h * h).sqrt() / 2. + ((w * orbit).powi(2) + (h * orbit * 0.6).powi(2)).sqrt();
        let side = (reach * 2. * 1.06).max((w * w + h * h).sqrt() * scale * 0.82);
        let cx = w / 2. + (t + phase).cos() * w * orbit;
        let cy = h / 2. + (t * 2. + phase).sin() * h * orbit * 0.6;
        let k = side / n;
        let tr = Transform::from_translate(cx, cy).pre_rotate((t * turns).to_degrees()).pre_scale(k, k).pre_translate(-n / 2., -n / 2.);
        pm.draw_pixmap(0, 0, cover.as_ref(), &PixmapPaint { opacity: alpha, quality: FilterQuality::Bilinear, ..Default::default() }, tr, None);
    }
    // The veil: darker at the bottom, where the player sits.
    let veil = vec![
        GradientStop::new(0.0, Color::from_rgba8(0, 0, 0, 51)),
        GradientStop::new(0.5, Color::from_rgba8(0, 0, 0, 77)),
        GradientStop::new(1.0, Color::from_rgba8(0, 0, 0, 143)),
    ];
    if let Some(sh) = LinearGradient::new(Point::from_xy(0., 0.), Point::from_xy(0., h), veil, SpreadMode::Pad, Transform::identity()) {
        paint.shader = sh;
        pm.fill_rect(full, &paint, Transform::identity(), None);
    }
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

    #[test]
    fn a_flow_frame_is_cheap() {
        let mut img = image::RgbaImage::new(96, 96);
        for (x, y, p) in img.enumerate_pixels_mut() {
            *p = image::Rgba([(x * 2) as u8, (y * 2) as u8, 160, 255]);
        }
        let mut bytes = Vec::new();
        image::DynamicImage::ImageRgba8(img).write_to(&mut std::io::Cursor::new(&mut bytes), image::ImageFormat::Png).unwrap();
        let (_, cover) = cover_art(&bytes).expect("art");
        let mut pm = Pixmap::new(367, 240).unwrap();
        let t = std::time::Instant::now();
        for i in 0..24 {
            paint_flow(&mut pm, [Rgb::hex(0x223344), Rgb::hex(0x112233), Rgb::hex(0x050509)], &cover, i as f32 * 0.01);
        }
        println!("24 flow frames at a third of 1100x720: {:?}", t.elapsed());
    }
}
