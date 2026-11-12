//! Covers: the bytes as GPUI images, and the blurred backdrop made once per song.
//!
//! GPUI has no backdrop-filter. The light behind the app is the cover, shrunk and blurred on the
//! CPU a single time; the GPU only stretches that small picture, so a still window costs nothing.

use std::io::Cursor;
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

/// A 64 px, heavily blurred, slightly darkened copy of the cover, as PNG.
pub fn backdrop(bytes: &[u8]) -> Option<Arc<Image>> {
    let img = image::load_from_memory(bytes).ok()?;
    let small = img.resize_to_fill(64, 64, image::imageops::FilterType::Triangle).to_rgb8();
    let mut blurred = image::imageops::blur(&small, 6.0);
    for p in blurred.pixels_mut() {
        for c in p.0.iter_mut() {
            *c = (*c as f32 * 0.62) as u8;
        }
    }
    let mut out = Vec::new();
    blurred.write_to(&mut Cursor::new(&mut out), image::ImageFormat::Png).ok()?;
    Some(Arc::new(Image::from_bytes(ImageFormat::Png, out)))
}
