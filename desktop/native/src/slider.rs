//! A slider (Tauri: components/Slider.svelte): a 4 px track that grows to 7 px under the pointer,
//! the fill at 82% of the text colour (the accent under the pointer), a 14 px white knob.
//! Dragging shows the value at once and reports it on release.

use std::cell::Cell;
use std::rc::Rc;

use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;

/// Where a slider was laid out last frame, for turning pointer positions into values.
#[derive(Clone, Default)]
pub struct SliderBounds(Rc<Cell<Bounds<Pixels>>>);

impl SliderBounds {
    pub fn fraction(&self, at: Point<Pixels>) -> f32 {
        let b = self.0.get();
        if b.size.width <= px(0.) {
            return 0.;
        }
        ((at.x - b.origin.x) / b.size.width).clamp(0., 1.)
    }
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
    let (b1, b2, b3) = (bounds.clone(), bounds.clone(), bounds.clone());
    let on_drag = Rc::new(on_drag);
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
                .on_mouse_down(MouseButton::Left, move |e, w, cx| on_drag(b1.fraction(e.position), w, cx))
                .on_mouse_move(move |e, w, cx| {
                    if e.pressed_button == Some(MouseButton::Left) {
                        on_drag2(b2.fraction(e.position), w, cx);
                    }
                })
                .on_mouse_up(MouseButton::Left, move |e, w, cx| on_release(b3.fraction(e.position), w, cx))
        })
        .child(
            canvas(move |b, _, _| bounds.0.set(b), |_, _, _, _| {})
                .absolute()
                .inset_0(),
        )
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
    let (b1, b2, b3) = (bounds.clone(), bounds.clone(), bounds.clone());
    let on_drag = Rc::new(on_drag);
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
                .on_mouse_down(MouseButton::Left, move |e, w, cx| on_drag(b1.fraction(e.position), w, cx))
                .on_mouse_move(move |e, w, cx| {
                    if e.pressed_button == Some(MouseButton::Left) {
                        on_drag2(b2.fraction(e.position), w, cx);
                    }
                })
                .on_mouse_up(MouseButton::Left, move |e, w, cx| on_release(b3.fraction(e.position), w, cx))
        })
        .child(canvas(move |b, _, _| bounds.0.set(b), |_, _, _, _| {}).absolute().inset_0())
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
