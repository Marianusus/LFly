//! A short message at the bottom of the window (Tauri: App.svelte `.toast`, connect.say): a glass
//! pill with an accent dot that rises in (320 ms), stays five seconds and fades (160 ms).

use std::time::{Duration, Instant};

use gpui_kit::*;

use crate::glass;
use crate::look::Look;

const SHOW: f32 = 5.;
const IN: f32 = 0.32;
const OUT: f32 = 0.16;

pub struct Toasts {
    look: Entity<Look>,
    notice: Option<(SharedString, Instant)>,
    /// Over the full player it sits lower (no player bar there).
    pub raised: bool,
}

struct ToastsGlobal(Entity<Toasts>);
impl Global for ToastsGlobal {}

impl Toasts {
    pub fn new(look: Entity<Look>, cx: &mut App) -> Entity<Self> {
        let e = cx.new(|_| Self { look, notice: None, raised: true });
        cx.set_global(ToastsGlobal(e.clone()));
        e
    }

    /// Shows [text] (connect.say).
    pub fn say(text: impl Into<SharedString>, cx: &mut App) {
        let Some(e) = cx.try_global::<ToastsGlobal>().map(|g| g.0.clone()) else { return };
        let text = text.into();
        e.update(cx, |t, cx| {
            t.notice = Some((text, Instant::now()));
            cx.notify();
            // Wake when it should fade, and once more when it is gone.
            cx.spawn(async move |this, cx| {
                cx.background_executor().timer(Duration::from_secs_f32(SHOW)).await;
                let _ = this.update(cx, |_, cx| cx.notify());
            })
            .detach();
        });
    }
}

impl Render for Toasts {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let Some((text, at)) = self.notice.clone() else { return div().into_any_element() };
        let t = at.elapsed().as_secs_f32();
        if t >= SHOW + OUT {
            self.notice = None;
            return div().into_any_element();
        }
        // In: up from 16 px below (cubicOut); out: a fade.
        let (dy, alpha) = if t < IN {
            let e = crate::motion::CUBIC_OUT.at((t / IN) as f64) as f32;
            (16. * (1. - e), e)
        } else if t > SHOW {
            (0., 1. - (t - SHOW) / OUT)
        } else {
            (0., 1.)
        };
        if t < IN || t > SHOW {
            window.request_animation_frame();
        }
        let (s, mut look) = {
            let l = self.look.read(cx);
            (l.scheme.clone(), l.glass())
        };
        // App.svelte `solid`: the surface at 80% for the tint.
        look.tint = s.surface.hsla(0.8);
        let accent = s.accent.hsla(1.);
        div()
            .absolute()
            .left_0()
            .right_0()
            .bottom(px(if self.raised { 112. } else { 28. } - dy))
            .flex()
            .justify_center()
            .opacity(alpha)
            .child(
                div()
                    .relative()
                    .rounded_full()
                    .shadow(vec![BoxShadow { color: black().opacity(0.45), offset: point(px(0.), px(18.)), blur_radius: px(48.), spread_radius: px(0.), inset: false }])
                    .child(glass::glass_layer(look, px(20.), glass::Pointer::default()))
                    .child(
                        div()
                            .relative()
                            .flex()
                            .items_center()
                            .gap(px(10.))
                            .py(px(11.))
                            .pl(px(14.))
                            .pr(px(20.))
                            .text_size(px(13.))
                            .font_weight(FontWeight::SEMIBOLD)
                            .text_color(s.text)
                            .child(
                                div()
                                    .size(px(8.))
                                    .flex_none()
                                    .rounded_full()
                                    .bg(accent)
                                    .shadow(vec![BoxShadow { color: accent, offset: point(px(0.), px(0.)), blur_radius: px(10.), spread_radius: px(0.), inset: false }]),
                            )
                            .child(text),
                    ),
            )
            .into_any_element()
    }
}
