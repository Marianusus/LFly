//! The playing cover as light (Tauri: pages/parts/Ambient.svelte), painted once on the CPU: a
//! still blurred copy filling the box and the two copies that drift over it, each blurred to soft
//! edges and saturated, under the scrim. The GPU moves the copies.
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

/// One of the two copies that drift (`.a`, `.b`): its picture with the blur's room around it,
/// where it stands (logical px, the room included) and how far it moves.
#[derive(Clone)]
pub struct Blob {
    image: Arc<RenderImage>,
    x: f32,
    y: f32,
    size: f32,
    /// The copy's own side (CSS translate percentages are of it).
    side: f32,
    /// drift-a/drift-b's `to`: translate (fractions of the side) and scale.
    to: (f32, f32, f32),
    /// Seconds one way (alternate: there and back).
    secs: f32,
}

/// The ambient as layers: the still base (with `--lfly-bg`), the two drifting copies, the scrim.
#[derive(Clone)]
pub struct Painted {
    base: Arc<RenderImage>,
    blobs: [Blob; 2],
    scrim: Arc<RenderImage>,
}

impl Painted {
    /// Its textures, to give back to the GPU when it is not shown any more.
    fn images(&self) -> Vec<Arc<RenderImage>> {
        vec![self.base.clone(), self.blobs[0].image.clone(), self.blobs[1].image.clone(), self.scrim.clone()]
    }
}

/// A part of [l] as the BGRA, straight-alpha frame GPUI draws (a decoded PNG is the same), no
/// PNG on the way; given back with `drop_image` when it is replaced.
fn to_image(l: &Layer, x0: usize, y0: usize, w: usize, h: usize) -> Option<Arc<RenderImage>> {
    let mut data = Vec::with_capacity(w * h * 4);
    for y in 0..h {
        for x in 0..w {
            let v = l.px[(y + y0) * l.w + x + x0];
            let a = v[3].clamp(0., 1.);
            let q = |c: f32| if a > 0. { ((c / a).clamp(0., 1.) * 255.).round() as u8 } else { 0 };
            data.extend_from_slice(&[q(v[2]), q(v[1]), q(v[0]), (a * 255.).round() as u8]);
        }
    }
    let buf = image::RgbaImage::from_raw(w as u32, h as u32, data)?;
    Some(Arc::new(RenderImage::new(vec![image::Frame::new(buf)])))
}

fn pixmap_image(pm: &Pixmap) -> Option<Arc<RenderImage>> {
    let l = Layer::from_pixmap(pm);
    to_image(&l, 0, 0, l.w, l.h)
}

/// The ambient for a box of [w] x [h] logical px over [bg], from the cover's bytes. `dim` and
/// `blur` as Ambient.svelte takes them (the full player: 0.42 and 80).
pub fn paint(cover: &[u8], w: f32, h: f32, bg: Rgb, dim: f32, blur: f32) -> Option<Painted> {
    let src = image::load_from_memory(cover).ok()?.resize_to_fill(96, 96, image::imageops::FilterType::Triangle).to_rgba8();
    let src = premultiplied(&src)?;
    let (cw, ch) = ((w / STEP).ceil().max(8.), (h / STEP).ceil().max(8.));
    let sigma = blur / STEP;
    // Room for the blur past the edges.
    let pad = (sigma * 3.).ceil();
    let s = src.width() as f32;

    // One copy of the cover in a box (x, y, size in painted px, object-fit cover) on a canvas of
    // [pw] x [ph], turned by `turn`, blurred and saturated.
    let copy = |pw: u32, ph: u32, x: f32, y: f32, bw: f32, bh: f32, turn: f32| -> Option<Layer> {
        let mut pm = Pixmap::new(pw, ph)?;
        let scale = bw.max(bh) / s;
        let (cx, cy) = (x + bw / 2., y + bh / 2.);
        let t = Transform::from_translate(cx, cy).pre_rotate(turn).pre_scale(scale, scale).pre_translate(-s / 2., -s / 2.);
        let clip = Rect::from_xywh(x, y, bw, bh)?;
        let mut mask = tiny_skia::Mask::new(pw, ph)?;
        mask.fill_path(&tiny_skia::PathBuilder::from_rect(clip), tiny_skia::FillRule::Winding, false, Transform::identity());
        pm.draw_pixmap(0, 0, src.as_ref(), &PixmapPaint { quality: FilterQuality::Bilinear, ..Default::default() }, t, Some(&mask));
        let mut l = Layer::from_pixmap(&pm);
        l.blur(sigma);
        l.saturate(1.7);
        Some(l)
    };

    // `--lfly-bg` under the still base (inset -30%), the padding cut off.
    let (pw, ph) = ((cw + 2. * pad) as u32, (ch + 2. * pad) as u32);
    let mut out = Layer { w: pw as usize, h: ph as usize, px: vec![[bg.0 as f32 / 255., bg.1 as f32 / 255., bg.2 as f32 / 255., 1.]; (pw * ph) as usize] };
    copy(pw, ph, pad - 0.3 * cw, pad - 0.3 * ch, 1.6 * cw, 1.6 * ch, 0.)?.over(&mut out, 1.);
    let base = to_image(&out, pad as usize, pad as usize, cw as usize, ch as usize)?;

    // `.a` (left -12%, top -28%) and `.b` (right -14%, bottom -36%, turned half way), each on its
    // own canvas with room for the blur, at 0.9.
    let side = 0.75 * cw;
    let room = (side + 2. * pad).ceil() as u32;
    let blob = |x: f32, y: f32, turn: f32, to: (f32, f32, f32), secs: f32| -> Option<Blob> {
        let mut l = copy(room, room, pad, pad, side, side, turn)?;
        for p in &mut l.px {
            for c in p.iter_mut() {
                *c *= 0.9;
            }
        }
        let image = to_image(&l, 0, 0, room as usize, room as usize)?;
        Some(Blob { image, x: (x - pad) * STEP, y: (y - pad) * STEP, size: room as f32 * STEP, side: side * STEP, to, secs })
    };
    let a = blob(-0.12 * cw, -0.28 * ch, 0., (0.14, 0.10, 1.15), 26.)?;
    let b = blob(cw + 0.14 * cw - side, ch + 0.36 * ch - side, 180., (-0.12, -0.08, 1.2), 32.)?;

    // `.scrim`: radial-gradient(120% 90% at 50% 30%, dim*0.55 → dim+0.2), over rgba(0,0,0,dim*0.4).
    let mut pm = Pixmap::new(cw as u32, ch as u32)?;
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
    let scrim = pixmap_image(&pm)?;

    Some(Painted { base, blobs: [a, b], scrim })
}

/// CSS `ease-in-out` (cubic-bezier(.42, 0, .58, 1)), close enough for a 26 s drift.
fn ease_in_out(t: f32) -> f32 {
    t * t * (3. - 2. * t)
}

/// The ambient behind a page: set [url], the size and whether it moves before it draws. A new
/// cover dissolves in over the old one (900 ms) instead of cutting; the picture is painted again
/// on a resize. The two copies drift as in Tauri (translate and scale; GPUI turns no images, so
/// not their small rotation), 30 times a second, while the music plays and the window is in front.
pub struct Ambient {
    core: Arc<crate::core::Core>,
    pub url: Option<String>,
    pub width: f32,
    pub height: f32,
    pub bg: Rgb,
    /// Drifting (the music plays): set by the page with the rest.
    pub moving: bool,
    dim: f32,
    blur: f32,
    shown_url: Option<String>,
    bytes: Option<Arc<Vec<u8>>>,
    painted: (f32, f32),
    current: Option<Painted>,
    previous: Option<Painted>,
    since: Option<Instant>,
    busy: bool,
    /// Seconds drifted so far (it holds still while paused, and goes on from there).
    drift: f32,
    last: Option<Instant>,
    tick: bool,
    /// Textures no longer shown, given back at the next draw (it has the window).
    trash: Vec<Arc<RenderImage>>,
}

impl Ambient {
    pub fn new(core: Arc<crate::core::Core>, dim: f32, blur: f32) -> Self {
        Self {
            core,
            url: None,
            width: 1100.,
            height: 720.,
            bg: Rgb(0., 0., 0.),
            moving: false,
            dim,
            blur,
            shown_url: None,
            bytes: None,
            painted: (0., 0.),
            current: None,
            previous: None,
            since: None,
            busy: false,
            drift: 0.,
            last: None,
            tick: false,
            trash: Vec::new(),
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
            let (c, p) = (self.current.take(), self.previous.take());
            self.discard(c);
            self.discard(p);
            return;
        };
        self.busy = true;
        let (bytes, bg, dim, blur, core) = (self.bytes.clone(), self.bg, self.dim, self.blur, self.core.clone());
        cx.spawn(async move |this, cx| {
            let bytes = match bytes {
                Some(b) => Some(b),
                None => core.fetch(url.clone()).await.map(Arc::new),
            };
            let painted = match bytes.clone() {
                Some(b) => cx.background_executor().spawn(async move { paint(&b, w, h, bg, dim, blur) }).await,
                None => None,
            };
            let _ = this.update(cx, |this, cx| {
                this.busy = false;
                this.painted = (w, h);
                if this.shown_url.as_deref() == Some(url.as_str()) {
                    if new_cover {
                        let old = std::mem::replace(&mut this.previous, this.current.take());
                        this.discard(old);
                        this.since = Some(Instant::now());
                    }
                    this.bytes = bytes;
                    if painted.is_some() {
                        let old = std::mem::replace(&mut this.current, painted);
                        this.discard(old);
                    }
                } else {
                    this.discard(painted);
                }
                cx.notify();
            });
        })
        .detach();
    }

    /// Gives every texture back (the view is going away).
    pub fn release(&mut self, window: &mut Window) {
        let (c, p) = (self.current.take(), self.previous.take());
        self.discard(c);
        self.discard(p);
        for t in self.trash.drain(..) {
            let _ = window.drop_image(t);
        }
        self.shown_url = None;
    }

    fn discard(&mut self, p: Option<Painted>) {
        if let Some(p) = p {
            self.trash.extend(p.images());
        }
    }

    /// The base and the drifting copies (the scrim goes once over all of them).
    fn layers(&self, p: &Painted) -> Div {
        let full = |i: Arc<RenderImage>| div().absolute().inset_0().child(img(i).size_full().object_fit(ObjectFit::Fill));
        let mut d = div().absolute().inset_0().child(full(p.base.clone()));
        for b in &p.blobs {
            // alternate: there and back, eased each way.
            let u = self.drift / b.secs;
            let leg = u.rem_euclid(2.);
            let e = ease_in_out(if leg <= 1. { leg } else { 2. - leg });
            let (tx, ty, sc) = b.to;
            let scale = 1. + (sc - 1.) * e;
            let size = b.size * scale;
            // Scaled about its centre, then moved.
            let x = b.x + b.size / 2. - size / 2. + tx * b.side * e;
            let y = b.y + b.size / 2. - size / 2. + ty * b.side * e;
            d = d.child(div().absolute().left(px(x)).top(px(y)).size(px(size)).child(img(b.image.clone()).size_full().object_fit(ObjectFit::Fill)));
        }
        d
    }
}

impl Render for Ambient {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        self.ensure(cx);
        for t in self.trash.drain(..) {
            let _ = window.drop_image(t);
        }
        let reduce = crate::prefs::Prefs::reduce_motion(cx);
        let active = window.is_window_active() || std::env::var_os("LFLY_FORCE_ACTIVE").is_some();
        let fade = if reduce { 1. } else { self.since.map_or(1., |t| (t.elapsed().as_secs_f32() / 0.9).min(1.)) };
        if fade >= 1. {
            if let Some(p) = self.previous.take() {
                for t in p.images() {
                    let _ = window.drop_image(t);
                }
            }
        } else if active {
            window.request_animation_frame();
        }
        // The drift: 30 frames a second, only while the music plays and the window is in front.
        let now = Instant::now();
        let drifting = self.moving && !reduce && active && self.current.is_some();
        if drifting {
            if let Some(l) = self.last {
                self.drift += now.duration_since(l).as_secs_f32().min(0.1);
            }
            self.last = Some(now);
            if !self.tick {
                self.tick = true;
                cx.spawn(async move |this, cx| {
                    cx.background_executor().timer(std::time::Duration::from_millis(33)).await;
                    let _ = this.update(cx, |this, cx| {
                        this.tick = false;
                        cx.notify();
                    });
                })
                .detach();
            }
        } else {
            self.last = None;
        }
        div()
            .absolute()
            .inset_0()
            .overflow_hidden()
            .bg(self.bg.hsla(1.))
            .children(self.previous.as_ref().map(|p| self.layers(p)))
            .children(self.current.as_ref().map(|p| self.layers(p).opacity(if self.previous.is_some() { fade } else { 1. })))
            // `.scrim`: one, over both while they dissolve.
            .children(self.current.as_ref().or(self.previous.as_ref()).map(|p| div().absolute().inset_0().child(img(p.scrim.clone()).size_full().object_fit(ObjectFit::Fill))))
    }
}

#[cfg(test)]
mod tests {
    // Not `super::*`: GPUI's own `test` attribute would come with it.
    use super::{paint, Rgb};

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
    }
}
