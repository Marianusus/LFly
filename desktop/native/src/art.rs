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

/// Artist.svelte's banner: the picture cropped to the banner (object-position: center 28%), the
/// two dark veils over it, and its bottom fading out (mask-image 55% -> 100%). Baked into a PNG.
pub fn banner(bytes: &[u8], aspect: f32) -> Option<Arc<Image>> {
    let img = image::load_from_memory(bytes).ok()?.to_rgba8();
    let (w, h) = (img.width() as f32, img.height() as f32);
    // Cover-fit crop to [aspect] (width / height), 28% from the top.
    let (cw, ch) = if w / h > aspect { (h * aspect, h) } else { (w, w / aspect) };
    let x = ((w - cw) / 2.).max(0.) as u32;
    let y = ((h - ch) * 0.28).max(0.) as u32;
    let mut out = image::imageops::crop_imm(&img, x, y, cw as u32, ch as u32).to_image();
    let (ow, oh) = (out.width() as f32, out.height() as f32);
    for (px_, py, p) in out.enumerate_pixels_mut() {
        let (fx, fy) = (px_ as f32 / ow, py as f32 / oh);
        // linear-gradient(to top, rgba(0,0,0,.55), transparent 55%) and (to right, .35 -> 60%).
        let up = 0.55 * ((fy - 0.45) / 0.55).clamp(0., 1.);
        let left = 0.35 * (1. - fx / 0.6).clamp(0., 1.);
        let dark = 1. - (1. - up) * (1. - left);
        let fade = if fy < 0.55 { 1. } else { 1. - (fy - 0.55) / 0.45 };
        let [r, g, b, a] = p.0;
        let k = 1. - dark;
        p.0 = [(r as f32 * k) as u8, (g as f32 * k) as u8, (b as f32 * k) as u8, (a as f32 * fade.max(0.)) as u8];
    }
    let mut png = Vec::new();
    out.write_to(&mut std::io::Cursor::new(&mut png), image::ImageFormat::Png).ok()?;
    Some(Arc::new(Image::from_bytes(ImageFormat::Png, png)))
}
