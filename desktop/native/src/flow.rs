//! Copertina dinamica (Tauri: glass/scene.ts with a cover): the playing cover, blurred, flowing
//! slowly behind the app. Painted on the CPU at a sixth of the window (it is all blur) 24 times a
//! second while the window is in front, still behind another window or with reduced motion; each
//! frame replaces the last one's texture, which is freed at once. A new cover fades in over 1.1 s.
//! Its own view, so a frame redraws it and the glass over it, nothing else.

use std::sync::Arc;
use std::time::{Duration, Instant};

use gpui_kit::*;
use tiny_skia::Pixmap;

use crate::look::Look;
use crate::theme::Rgb;

/// A sixth of the window: the cover is drawn ~27 times its size and blurred.
const SCALE: f32 = 1. / 6.;
/// One loop: slow enough to feel like weather, not like a screensaver.
const LOOP_S: f32 = 36.;
const FPS: u64 = 24;
const FADE_S: f32 = 1.1;

pub struct Flow {
    look: Entity<Look>,
    epoch: Instant,
    /// What is on screen, and its texture (dropped when the next one is up).
    frame: Option<Arc<RenderImage>>,
    shown: Option<Arc<Pixmap>>,
    /// The picture faded out over a new cover, and since when.
    fade: Option<(Pixmap, Instant)>,
    last: Option<(u32, u32, Arc<Pixmap>, [Rgb; 3], i64)>,
    timer: bool,
    _subs: Vec<Subscription>,
}

impl Flow {
    pub fn new(look: Entity<Look>, cx: &mut Context<Self>) -> Self {
        let subs = vec![cx.observe(&look, |_, _, cx| cx.notify())];
        Self { look, epoch: Instant::now(), frame: None, shown: None, fade: None, last: None, timer: false, _subs: subs }
    }

    /// Whether the window should show the flow: the mode, and a cover to flow.
    pub fn active(look: &Look) -> bool {
        look.mode == crate::theme::Mode::DynamicCover && look.cover_art.is_some()
    }
}

/// Premultiplied RGBA (opaque here) into GPUI's BGRA.
fn to_render_image(pm: &Pixmap) -> Option<Arc<RenderImage>> {
    let mut data = pm.data().to_vec();
    for p in data.chunks_exact_mut(4) {
        p.swap(0, 2);
    }
    let buf = image::RgbaImage::from_raw(pm.width(), pm.height(), data)?;
    Some(Arc::new(RenderImage::new(vec![image::Frame::new(buf)])))
}

impl Render for Flow {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let (gradient, art) = {
            let l = self.look.read(cx);
            (l.scheme.gradient, l.cover_art.clone())
        };
        let Some(art) = art else { return div().size_full().into_any_element() };
        let reduce = crate::prefs::Prefs::reduce_motion(cx);
        let active = window.is_window_active() || std::env::var_os("LFLY_FORCE_ACTIVE").is_some();
        let flowing = !reduce && active;

        // A new cover: what is on screen fades out over it.
        if self.shown.as_ref().is_some_and(|s| !Arc::ptr_eq(s, &art)) && !reduce {
            if let Some((w, h, cover, g, _)) = self.last.clone() {
                if let Some(mut old) = Pixmap::new(w, h) {
                    let t = self.loop_t();
                    crate::scene::paint_flow(&mut old, g, &cover, t);
                    self.fade = Some((old, Instant::now()));
                }
            }
        }
        self.shown = Some(art.clone());
        let fade_p = self.fade.as_ref().map(|(_, at)| (at.elapsed().as_secs_f32() / FADE_S).min(1.));
        if fade_p.is_some_and(|p| p >= 1.) {
            self.fade = None;
        }

        // The view's size comes from the window; the canvas paints at a sixth of it.
        let size = window.viewport_size();
        let (w, h) = (((f32::from(size.width) * SCALE).ceil() as u32).max(16), ((f32::from(size.height) * SCALE).ceil() as u32).max(16));
        // Still (behind a window, reduced motion): the phone's resting angle, 0.18 of a loop.
        let t = if flowing { self.loop_t() } else { 0.18 * std::f32::consts::TAU };
        let tick = if flowing { (self.epoch.elapsed().as_millis() / (1000 / FPS as u128)) as i64 } else { -1 };
        let key_same = self.last.as_ref().is_some_and(|(lw, lh, c, g, k)| *lw == w && *lh == h && Arc::ptr_eq(c, &art) && *g == gradient && *k == tick);
        if !key_same || self.fade.is_some() || self.frame.is_none() {
            if let Some(mut pm) = Pixmap::new(w, h) {
                crate::scene::paint_flow(&mut pm, gradient, &art, t);
                if let (Some((old, at)), true) = (self.fade.as_ref(), !reduce) {
                    let p = (at.elapsed().as_secs_f32() / FADE_S).min(1.);
                    let eased = 1. - (1. - p) * (1. - p);
                    pm.draw_pixmap(0, 0, old.as_ref(), &tiny_skia::PixmapPaint { opacity: 1. - eased, ..Default::default() }, tiny_skia::Transform::identity(), None);
                }
                if let Some(img) = to_render_image(&pm) {
                    if let Some(prev) = self.frame.replace(img) {
                        // The texture of the frame before is not needed any more.
                        let _ = window.drop_image(prev);
                    }
                }
            }
            self.last = Some((w, h, art, gradient, tick));
        }

        // The next frame: 24 a second while it flows or fades.
        if (flowing || self.fade.is_some()) && !self.timer {
            self.timer = true;
            cx.spawn(async move |this, cx| {
                cx.background_executor().timer(Duration::from_millis(1000 / FPS)).await;
                let _ = this.update(cx, |this, cx| {
                    this.timer = false;
                    cx.notify();
                });
            })
            .detach();
        }

        let frame = self.frame.clone();
        canvas(
            |_, _, _| {},
            move |bounds, _, window, _| {
                if let Some(f) = frame {
                    let _ = window.paint_image(bounds, bounds, Corners::default(), f, 0, false);
                }
            },
        )
        .size_full()
        .into_any_element()
    }
}

impl Flow {
    fn loop_t(&self) -> f32 {
        (self.epoch.elapsed().as_secs_f32() % LOOP_S) / LOOP_S * std::f32::consts::TAU
    }
}
