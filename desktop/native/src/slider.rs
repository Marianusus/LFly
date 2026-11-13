//! A slider (Tauri: components/Slider.svelte): a 4 px track that grows to 7 px under the pointer,
//! the fill at 82% of the text colour (the accent under the pointer), a 14 px white knob.
//! Dragging shows the value at once and reports it on release.

use std::cell::Cell;
use std::rc::Rc;

use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;

/// Where a slider was laid out last frame, for turning pointer positions into values, and
/// whether a drag that started on it is still going (it follows the pointer anywhere in the
/// window, as a browser's range input does, until the button is let go).
#[derive(Clone, Default)]
pub struct SliderBounds(Rc<Cell<Bounds<Pixels>>>, Rc<Cell<bool>>);

impl SliderBounds {
    /// Bottom 0, top 1 (a vertical slider).
    pub fn fraction_y(&self, at: Point<Pixels>) -> f32 {
        let b = self.0.get();
        if b.size.height <= px(0.) {
            return 0.;
        }
        (1. - (at.y - b.origin.y) / b.size.height).clamp(0., 1.)
    }

    pub fn fraction(&self, at: Point<Pixels>) -> f32 {
        let b = self.0.get();
        if b.size.width <= px(0.) {
            return 0.;
        }
        ((at.x - b.origin.x) / b.size.width).clamp(0., 1.)
    }
}

type OnFrac = Rc<dyn Fn(f32, &mut Window, &mut App)>;

/// Records the slider's bounds and, while a drag is on, hears the pointer over the whole window.
fn tracker(bounds: SliderBounds, vertical: bool, on_drag: OnFrac, on_release: Option<OnFrac>) -> impl IntoElement {
    let b = bounds.clone();
    canvas(
        move |r, _, _| b.0.set(r),
        move |_, _, window, _| {
            let frac = move |b: &SliderBounds, at: Point<Pixels>| if vertical { b.fraction_y(at) } else { b.fraction(at) };
            let (b1, b2) = (bounds.clone(), bounds);
            window.on_mouse_event(move |e: &MouseMoveEvent, phase, w, cx| {
                if phase == DispatchPhase::Bubble && b1.1.get() {
                    if e.pressed_button == Some(MouseButton::Left) {
                        on_drag(frac(&b1, e.position), w, cx);
                    } else {
                        b1.1.set(false);
                    }
                }
            });
            window.on_mouse_event(move |e: &MouseUpEvent, phase, w, cx| {
                if phase == DispatchPhase::Bubble && e.button == MouseButton::Left && b2.1.get() {
                    b2.1.set(false);
                    if let Some(f) = &on_release {
                        f(frac(&b2, e.position), w, cx);
                    }
                }
            });
        },
    )
    .absolute()
    .inset_0()
}

pub struct SliderStyle {
    pub text: Hsla,
    pub accent: Hsla,
}

/// [frac] is 0..1. [on_drag] gets the fraction while the button is held; [on_release] once.
pub fn slider(
    id: impl Into<ElementId>,
    frac: f32,
    disabled: bool,
    style: SliderStyle,
    bounds: SliderBounds,
    on_drag: impl Fn(f32, &mut Window, &mut App) + 'static,
    on_release: impl Fn(f32, &mut Window, &mut App) + 'static,
) -> impl IntoElement {
    let id: ElementId = id.into();
    let group: SharedString = format!("slider-{id}").into();
    let pct = relative(frac.clamp(0., 1.));
    let track_bg = style.text.opacity(0.18);
    let fill = style.text.opacity(0.82);
    let b1 = bounds.clone();
    let on_drag: OnFrac = Rc::new(on_drag);
    let on_release: OnFrac = Rc::new(on_release);
    let on_drag2 = on_drag.clone();
    div()
        .id(id)
        .group(group.clone())
        .relative()
        .h(px(20.))
        .flex_1()
        .min_w(px(40.))
        .flex()
        .items_center()
        .when(disabled, |s| s.opacity(0.5))
        .when(!disabled, |s| {
            s.cursor_pointer()
                .on_mouse_down(MouseButton::Left, move |e, w, cx| {
                    b1.1.set(true);
                    on_drag2(b1.fraction(e.position), w, cx)
                })
        })
        .child(tracker(bounds, false, on_drag, Some(on_release)))
        .child(
            div()
                .w_full()
                .h(px(4.))
                .rounded_full()
                .bg(track_bg)
                .overflow_hidden()
                .when(!disabled, |s| s.group_hover(group.clone(), |s| s.h(px(7.))))
                .child(
                    div()
                        .h_full()
                        .rounded_full()
                        .w(pct)
                        .bg(fill)
                        .when(!disabled, |s| s.group_hover(group.clone(), |s| s.bg(style.accent))),
                ),
        )
        .when(!disabled, |s| {
            s.child(
                div()
                    .absolute()
                    .top(px(3.))
                    .left(pct)
                    .ml(px(-7.))
                    .size(px(14.))
                    .rounded_full()
                    .bg(white())
                    .shadow(vec![BoxShadow {
                        color: black().opacity(0.35),
                        offset: point(px(0.), px(1.)),
                        blur_radius: px(4.),
                        spread_radius: px(0.),
                        inset: false,
                    }])
                    .invisible()
                    .group_hover(group, |s| s.visible()),
            )
        })
}

/// The full player's slider (Tauri: pages/parts/Slider.svelte): a 6 px bar at 22% of [ink],
/// 10 px under the pointer, the fill at 82% (all of [ink] under the pointer), no knob.
pub fn bar_slider(
    id: impl Into<ElementId>,
    frac: f32,
    disabled: bool,
    ink: Hsla,
    bounds: SliderBounds,
    on_drag: impl Fn(f32, &mut Window, &mut App) + 'static,
    on_release: impl Fn(f32, &mut Window, &mut App) + 'static,
) -> Stateful<Div> {
    let id: ElementId = id.into();
    let group: SharedString = format!("bar-slider-{id}").into();
    let b1 = bounds.clone();
    let on_drag: OnFrac = Rc::new(on_drag);
    let on_release: OnFrac = Rc::new(on_release);
    let on_drag2 = on_drag.clone();
    div()
        .id(id)
        .group(group.clone())
        .relative()
        .h(px(22.))
        .w_full()
        .flex()
        .items_center()
        .when(disabled, |s| s.opacity(0.5))
        .when(!disabled, |s| {
            s.cursor_pointer()
                .on_mouse_down(MouseButton::Left, move |e, w, cx| {
                    b1.1.set(true);
                    on_drag2(b1.fraction(e.position), w, cx)
                })
        })
        .child(tracker(bounds, false, on_drag, Some(on_release)))
        .child(
            div()
                .w_full()
                .h(px(6.))
                .rounded_full()
                .bg(ink.opacity(0.22))
                .overflow_hidden()
                .when(!disabled, |s| s.group_hover(group.clone(), |s| s.h(px(10.))))
                .child(
                    div()
                        .h_full()
                        .rounded_full()
                        .w(relative(frac.clamp(0., 1.)))
                        .bg(ink.opacity(0.82))
                        .when(!disabled, |s| s.group_hover(group, move |s| s.bg(ink))),
                ),
        )
}

/// EqualizerPanel.svelte `.band input`: a vertical 6 px track (accent from the bottom) with a
/// 20 px white thumb; a drag sets it, a double click calls [on_reset].
pub fn vslider(
    id: impl Into<ElementId>,
    frac: f32,
    accent: Hsla,
    track: Hsla,
    height: f32,
    bounds: SliderBounds,
    on_drag: impl Fn(f32, &mut Window, &mut App) + 'static,
    on_reset: impl Fn(&mut Window, &mut App) + 'static,
) -> Stateful<Div> {
    let f = frac.clamp(0., 1.);
    let b1 = bounds.clone();
    let on_drag: OnFrac = Rc::new(on_drag);
    let on_drag2 = on_drag.clone();
    div()
        .id(id)
        .relative()
        .w(px(24.))
        .h(px(height))
        .cursor_pointer()
        .on_mouse_down(MouseButton::Left, move |e, w, cx| {
            if e.click_count == 2 {
                on_reset(w, cx);
            } else {
                b1.1.set(true);
                on_drag2(b1.fraction_y(e.position), w, cx);
            }
        })
        .child(tracker(bounds, true, on_drag, None))
        .child(
            div()
                .absolute()
                .top_0()
                .bottom_0()
                .left(px(9.))
                .w(px(6.))
                .rounded_full()
                .bg(track)
                .child(div().absolute().left_0().right_0().bottom_0().h(relative(f)).rounded_full().bg(accent)),
        )
        .child(
            div()
                .absolute()
                .left(px(2.))
                .top(px((1. - f) * (height - 20.)))
                .size(px(20.))
                .rounded_full()
                .bg(white())
                .shadow(vec![BoxShadow { color: black().opacity(0.35), offset: point(px(0.), px(2.)), blur_radius: px(6.), spread_radius: px(0.), inset: false }]),
        )
}

/// EqualizerPanel.svelte `.bass input`: a horizontal 6 px track (accent from the left) with a
/// 20 px white thumb, the whole width of its parent.
pub fn hslider(
    id: impl Into<ElementId>,
    frac: f32,
    accent: Hsla,
    track: Hsla,
    bounds: SliderBounds,
    on_drag: impl Fn(f32, &mut Window, &mut App) + 'static,
) -> Stateful<Div> {
    let f = frac.clamp(0., 1.);
    let b1 = bounds.clone();
    let on_drag: OnFrac = Rc::new(on_drag);
    let on_drag2 = on_drag.clone();
    div()
        .id(id)
        .relative()
        .w_full()
        .h(px(20.))
        .cursor_pointer()
        .on_mouse_down(MouseButton::Left, move |e, w, cx| {
            b1.1.set(true);
            on_drag2(b1.fraction(e.position), w, cx);
        })
        .child(tracker(bounds, false, on_drag, None))
        .child(
            div()
                .absolute()
                .left_0()
                .right_0()
                .top(px(7.))
                .h(px(6.))
                .rounded_full()
                .bg(track)
                .child(div().absolute().top_0().bottom_0().left_0().w(relative(f)).rounded_full().bg(accent)),
        )
        // The thumb travels inside the track, as a browser's range thumb does.
        .child(
            div().absolute().top_0().bottom_0().left_0().right(px(20.)).child(
                div()
                    .absolute()
                    .top_0()
                    .left(relative(f))
                    .size(px(20.))
                    .rounded_full()
                    .bg(white())
                    .shadow(vec![BoxShadow { color: black().opacity(0.35), offset: point(px(0.), px(2.)), blur_radius: px(6.), spread_radius: px(0.), inset: false }]),
            ),
        )
}
