//! The playing cover as light (Tauri: pages/parts/Ambient.svelte), painted once on the CPU: a
//! still blurred copy filling the box, the two copies that drift over it in Tauri (here where
//! their drift starts, so `still`), each blurred to soft edges and saturated, under the scrim.
//! Painted at an eighth of the window: a blur this wide hides the scale, the GPU stretches it.

use std::sync::Arc;
use std::time::Instant;

use gpui_kit::*;
use tiny_skia::{
    Color, FilterQuality, GradientStop, IntSize, Paint, Pixmap, PixmapPaint, Point, RadialGradient, Rect, SpreadMode,
    Transform,
};

use crate::theme::Rgb;

/// Logical px per painted px.
const STEP: f32 = 8.;

/// A premultiplied RGBA picture as floats, for blurring without banding.
struct Layer {
    w: usize,
    h: usize,
    px: Vec<[f32; 4]>,
}

impl Layer {
    fn from_pixmap(pm: &Pixmap) -> Self {
        let px = pm
            .pixels()
            .iter()
            .map(|p| [p.red() as f32 / 255., p.green() as f32 / 255., p.blue() as f32 / 255., p.alpha() as f32 / 255.])
            .collect();
        Self { w: pm.width() as usize, h: pm.height() as usize, px }
    }

    /// CSS blur(): a Gaussian of deviation [sigma] px, transparent outside.
    fn blur(&mut self, sigma: f32) {
        if sigma < 0.3 {
            return;
        }
        let r = (sigma * 3.).ceil() as i32;
        let k: Vec<f32> = (-r..=r).map(|i| (-(i * i) as f32 / (2. * sigma * sigma)).exp()).collect();
        let sum: f32 = k.iter().sum();
        let k: Vec<f32> = k.iter().map(|v| v / sum).collect();
        let (w, h) = (self.w as i32, self.h as i32);
        let mut tmp = vec![[0f32; 4]; self.px.len()];
        for y in 0..h {
            for x in 0..w {
                let mut acc = [0f32; 4];
                for (j, kv) in k.iter().enumerate() {
                    let sx = x + j as i32 - r;
                    if sx < 0 || sx >= w {
                        continue;
                    }
                    let p = self.px[(y * w + sx) as usize];
                    for c in 0..4 {
                        acc[c] += p[c] * kv;
                    }
                }
                tmp[(y * w + x) as usize] = acc;
            }
        }
        for y in 0..h {
            for x in 0..w {
                let mut acc = [0f32; 4];
                for (j, kv) in k.iter().enumerate() {
                    let sy = y + j as i32 - r;
                    if sy < 0 || sy >= h {
                        continue;
                    }
                    let p = tmp[(sy * w + x) as usize];
                    for c in 0..4 {
                        acc[c] += p[c] * kv;
                    }
                }
                self.px[(y * w + x) as usize] = acc;
            }
        }
    }

    /// CSS saturate(s), on the straight colours.
    fn saturate(&mut self, s: f32) {
        for p in &mut self.px {
            let a = p[3];
            if a <= 0. {
                continue;
            }
            let (r, g, b) = (p[0] / a, p[1] / a, p[2] / a);
            let nr = (0.213 + 0.787 * s) * r + (0.715 - 0.715 * s) * g + (0.072 - 0.072 * s) * b;
            let ng = (0.213 - 0.213 * s) * r + (0.715 + 0.285 * s) * g + (0.072 - 0.072 * s) * b;
            let nb = (0.213 - 0.213 * s) * r + (0.715 - 0.715 * s) * g + (0.072 + 0.928 * s) * b;
            p[0] = nr.clamp(0., 1.) * a;
            p[1] = ng.clamp(0., 1.) * a;
            p[2] = nb.clamp(0., 1.) * a;
        }
    }

    /// This layer over [below] at [opacity], source-over.
    fn over(&self, below: &mut Layer, opacity: f32) {
        for (d, s) in below.px.iter_mut().zip(&self.px) {
            let k = 1. - s[3] * opacity;
            for c in 0..4 {
                d[c] = s[c] * opacity + d[c] * k;
            }
        }
    }
}

fn premultiplied(img: &image::RgbaImage) -> Option<Pixmap> {
    let mut data = img.as_raw().clone();
    for p in data.chunks_exact_mut(4) {
        let a = p[3] as u16;
        for c in &mut p[..3] {
            *c = ((*c as u16 * a + 127) / 255) as u8;
        }
    }
    Pixmap::from_vec(data, IntSize::from_wh(img.width(), img.height())?)
}

/// The ambient for a box of [w] x [h] logical px over [bg], from the cover's bytes. `dim` and
/// `blur` as Ambient.svelte takes them (the full player: 0.42 and 80).
pub fn paint(cover: &[u8], w: f32, h: f32, bg: Rgb, dim: f32, blur: f32) -> Option<Arc<Image>> {
    let src = image::load_from_memory(cover).ok()?.resize_to_fill(96, 96, image::imageops::FilterType::Triangle).to_rgba8();
    let src = premultiplied(&src)?;
    let (cw, ch) = ((w / STEP).ceil().max(8.), (h / STEP).ceil().max(8.));
    let sigma = blur / STEP;
    // Room for the blur past the edges, cut off at the end.
    let pad = (sigma * 3.).ceil();
    let (pw, ph) = ((cw + 2. * pad) as u32, (ch + 2. * pad) as u32);
    let s = src.width() as f32;

    // One copy of the cover in a box (x, y, size in painted px, object-fit cover), turned by `turn`.
    let copy = |x: f32, y: f32, bw: f32, bh: f32, turn: f32| -> Option<Layer> {
        let mut pm = Pixmap::new(pw, ph)?;
        let scale = bw.max(bh) / s;
        let (cx, cy) = (pad + x + bw / 2., pad + y + bh / 2.);
        let t = Transform::from_translate(cx, cy).pre_rotate(turn).pre_scale(scale, scale).pre_translate(-s / 2., -s / 2.);
        let clip = Rect::from_xywh(pad + x, pad + y, bw, bh)?;
        let mut mask = tiny_skia::Mask::new(pw, ph)?;
        mask.fill_path(&tiny_skia::PathBuilder::from_rect(clip), tiny_skia::FillRule::Winding, false, Transform::identity());
        pm.draw_pixmap(0, 0, src.as_ref(), &PixmapPaint { quality: FilterQuality::Bilinear, ..Default::default() }, t, Some(&mask));
        let mut l = Layer::from_pixmap(&pm);
        l.blur(sigma);
        l.saturate(1.7);
        Some(l)
    };

    // `--lfly-bg`, then the still base (inset -30%), then `.a` and `.b` at 0.9.
    let mut out = Layer { w: pw as usize, h: ph as usize, px: vec![[bg.0 as f32 / 255., bg.1 as f32 / 255., bg.2 as f32 / 255., 1.]; (pw * ph) as usize] };
    copy(-0.3 * cw, -0.3 * ch, 1.6 * cw, 1.6 * ch, 0.)?.over(&mut out, 1.);
    let side = 0.75 * cw;
    copy(-0.12 * cw, -0.28 * ch, side, side, 0.)?.over(&mut out, 0.9);
    copy(cw + 0.14 * cw - side, ch + 0.36 * ch - side, side, side, 180.)?.over(&mut out, 0.9);

    // Back to a pixmap, the padding cut off.
    let mut pm = Pixmap::new(cw as u32, ch as u32)?;
    let pad = pad as usize;
    for (i, p) in pm.pixels_mut().iter_mut().enumerate() {
        let (x, y) = (i % cw as usize, i / cw as usize);
        let v = out.px[(y + pad) * out.w + x + pad];
        let a = v[3].clamp(0., 1.);
        let q = |c: f32| (c.clamp(0., a) * 255.).round() as u8;
        *p = tiny_skia::PremultipliedColorU8::from_rgba(q(v[0]), q(v[1]), q(v[2]), (a * 255.).round() as u8)?;
    }

    // `.scrim`: radial-gradient(120% 90% at 50% 30%, dim*0.55 → dim+0.2), over rgba(0,0,0,dim*0.4).
    let full = Rect::from_xywh(0., 0., cw, ch)?;
    let mut paint = Paint::default();
    paint.set_color(Color::from_rgba(0., 0., 0., (dim * 0.4).clamp(0., 1.))?);
    pm.fill_rect(full, &paint, Transform::identity(), None);
    let stops = vec![
        GradientStop::new(0., Color::from_rgba(0., 0., 0., (dim * 0.55).clamp(0., 1.))?),
        GradientStop::new(1., Color::from_rgba(0., 0., 0., (dim + 0.2).clamp(0., 1.))?),
    ];
    // A unit circle stretched to the ellipse's radii (1.2 w, 0.9 h).
    let t = Transform::from_translate(cw * 0.5, ch * 0.3).pre_scale(1.2 * cw, 0.9 * ch);
    paint.shader = RadialGradient::new(Point::from_xy(0., 0.), Point::from_xy(0., 0.), 1., stops, SpreadMode::Pad, t)?;
    pm.fill_rect(full, &paint, Transform::identity(), None);

    let png = pm.encode_png().ok()?;
    Some(Arc::new(Image::from_bytes(ImageFormat::Png, png)))
}

/// The ambient behind a page: set [url] and the size before it draws. A new cover dissolves in
/// over the old one (900 ms) instead of cutting; the picture is painted again on a resize.
pub struct Ambient {
    core: Arc<crate::core::Core>,
    pub url: Option<String>,
    pub width: f32,
    pub height: f32,
    pub bg: Rgb,
    dim: f32,
    blur: f32,
    shown_url: Option<String>,
    bytes: Option<Arc<Vec<u8>>>,
    painted: (f32, f32),
    current: Option<Arc<gpui_kit::Image>>,
    previous: Option<Arc<gpui_kit::Image>>,
    since: Option<Instant>,
    busy: bool,
}

impl Ambient {
    pub fn new(core: Arc<crate::core::Core>, dim: f32, blur: f32) -> Self {
        Self {
            core,
            url: None,
            width: 1100.,
            height: 720.,
            bg: Rgb(0., 0., 0.),
            dim,
            blur,
            shown_url: None,
            bytes: None,
            painted: (0., 0.),
            current: None,
            previous: None,
            since: None,
            busy: false,
        }
    }

    fn ensure(&mut self, cx: &mut Context<Self>) {
        let (w, h, url) = (self.width, self.height, self.url.clone());
        let new_cover = url != self.shown_url;
        let (pw, ph) = self.painted;
        let resized = (w - pw).abs() > pw * 0.04 || (h - ph).abs() > ph * 0.04;
        if self.busy || (!new_cover && !resized) {
            return;
        }
        if new_cover {
            self.shown_url = url.clone();
            self.bytes = None;
        }
        let Some(url) = url else {
            self.current = None;
            self.previous = None;
            return;
        };
        self.busy = true;
        let (bytes, bg, dim, blur, core) = (self.bytes.clone(), self.bg, self.dim, self.blur, self.core.clone());
        cx.spawn(async move |this, cx| {
            let bytes = match bytes {
                Some(b) => Some(b),
                None => core.fetch(url.clone()).await.map(Arc::new),
            };
            let image = match bytes.clone() {
                Some(b) => cx.background_executor().spawn(async move { paint(&b, w, h, bg, dim, blur) }).await,
                None => None,
            };
            let _ = this.update(cx, |this, cx| {
                this.busy = false;
                this.painted = (w, h);
                if this.shown_url.as_deref() == Some(url.as_str()) {
                    if new_cover {
                        this.previous = this.current.take();
                        this.since = Some(Instant::now());
                    }
                    this.bytes = bytes;
                    if image.is_some() {
                        this.current = image;
                    }
                }
                cx.notify();
            });
        })
        .detach();
    }
}

impl Render for Ambient {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        self.ensure(cx);
        let fade = self.since.map_or(1., |t| (t.elapsed().as_secs_f32() / 0.9).min(1.));
        if fade >= 1. {
            self.previous = None;
        } else if window.is_window_active() {
            window.request_animation_frame();
        }
        let layer = |i: Arc<gpui_kit::Image>| div().absolute().inset_0().child(img(i).size_full().object_fit(ObjectFit::Fill));
        div()
            .absolute()
            .inset_0()
            .bg(self.bg.hsla(1.))
            .children(self.previous.clone().map(layer))
            .children(self.current.clone().map(|i| layer(i).opacity(if self.previous.is_some() { fade } else { 1. })))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn paints_quickly() {
        let mut img = image::RgbaImage::new(120, 120);
        for (x, y, p) in img.enumerate_pixels_mut() {
            *p = image::Rgba([(x * 2) as u8, (y * 2) as u8, 160, 255]);
        }
        let mut bytes = Vec::new();
        image::DynamicImage::ImageRgba8(img).write_to(&mut std::io::Cursor::new(&mut bytes), image::ImageFormat::Png).unwrap();
        let t = std::time::Instant::now();
        assert!(paint(&bytes, 1100., 720., Rgb::hex(0x101014), 0.42, 80.).is_some());
        let took = t.elapsed();
        println!("ambient 1100x720: {took:?}");
        assert!(took.as_millis() < 200);
    }
}
