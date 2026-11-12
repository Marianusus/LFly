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
