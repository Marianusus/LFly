//! Covers: the downloaded bytes as GPUI images.

use std::sync::Arc;

use gpui_kit::{Image, ImageFormat};

pub fn image(bytes: Vec<u8>) -> Option<Arc<Image>> {
    let format = match image::guess_format(&bytes).ok()? {
        image::ImageFormat::Jpeg => ImageFormat::Jpeg,
        image::ImageFormat::Png => ImageFormat::Png,
        image::ImageFormat::WebP => ImageFormat::Webp,
        _ => return None,
    };
    Some(Arc::new(Image::from_bytes(format, bytes)))
}

/// Hero.svelte's glow: the cover blurred (90 px on a 240 px hero) and saturated 1.8 at 55%,
/// fading out from 35% of its height down, as its mask-image. Baked into a small PNG.
pub fn glow(bytes: &[u8]) -> Option<Arc<Image>> {
    let img = image::load_from_memory(bytes).ok()?;
    let small = img.resize_to_fill(48, 48, image::imageops::FilterType::Triangle).to_rgba8();
    let mut b = image::imageops::blur(&small, 5.0);
    let h = b.height() as f32;
    for (_, y, p) in b.enumerate_pixels_mut() {
        let [r, g, bl, _] = p.0;
        let (r, g, bl) = (r as f32, g as f32, bl as f32);
        let l = 0.213 * r + 0.715 * g + 0.072 * bl;
        let sat = |c: f32| (l + (c - l) * 1.8).clamp(0., 255.) as u8;
        let t = y as f32 / h;
        let fade = if t < 0.35 { 1. } else { (1. - (t - 0.35) / 0.65).max(0.) };
        p.0 = [sat(r), sat(g), sat(bl), (0.55 * fade * 255.) as u8];
    }
    let mut out = Vec::new();
    b.write_to(&mut std::io::Cursor::new(&mut out), image::ImageFormat::Png).ok()?;
    Some(Arc::new(Image::from_bytes(ImageFormat::Png, out)))
}
