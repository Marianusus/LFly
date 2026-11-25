//! Placeholders at the real sizes while a page loads, breathing gently, instead of "Caricamento…":
//! the page has its shape before its content lands.

use std::time::Duration;

use gpui_kit::*;

use crate::theme::Scheme;

fn block(w: impl Into<Length>, h: f32, radius: f32, s: &Scheme) -> Div {
    div().w(w.into()).h(px(h)).flex_none().rounded(px(radius)).bg(s.text.opacity(0.07))
}

/// [el] breathing between 55% and 100% every 1.4 s, at 30 fps at most (still when motion is
/// reduced).
fn breathe(id: &'static str, el: Div, cx: &App) -> AnyElement {
    if crate::prefs::Prefs::reduce_motion(cx) {
        return el.into_any_element();
    }
    el.with_animation(id, Animation::new(Duration::from_millis(1400)).repeat().with_max_fps(30.).with_easing(pulsating_between(0.55, 1.)), |d, t| d.opacity(t)).into_any_element()
}

/// Two shelves of cards [card_w] wide (Home).
pub fn shelves(card_w: f32, s: &Scheme, cx: &App) -> AnyElement {
    let shelf = |n: usize| {
        div()
            .mb(px(36.))
            .child(div().px(px(24.)).mb(px(14.)).child(block(px(180.), 22., 8., s)))
            .child(
                div().mx(px(24.)).pt(px(6.)).flex().gap(px(crate::home::CARD_GAP)).overflow_hidden().children((0..n).map(|_| {
                    div()
                        .w(px(card_w))
                        .flex_none()
                        .p(px(8.))
                        .flex()
                        .flex_col()
                        .gap(px(8.))
                        .child(block(px(card_w - 16.), card_w - 16., 15., s))
                        .child(block(relative(0.8), 13., 6., s))
                        .child(block(relative(0.5), 11., 6., s))
                })),
            )
    };
    breathe("skel-shelves", div().child(shelf(8)).child(shelf(8)), cx)
}

/// Song rows (a playlist, an album).
pub fn rows(n: usize, s: &Scheme, cx: &App) -> AnyElement {
    let list = div().px(px(24.)).flex().flex_col().children((0..n).map(|i| {
        div()
            .h(px(56.))
            .px(px(12.))
            .flex()
            .items_center()
            .gap(px(14.))
            .child(block(px(40.), 40., 8., s))
            .child(
                div()
                    .flex_1()
                    .flex()
                    .flex_col()
                    .gap(px(7.))
                    // Not all the same width: a list, not a barcode.
                    .child(block(px([220., 170., 260., 140., 200., 180.][i % 6]), 12., 6., s))
                    .child(block(px([120., 150., 90., 130., 110., 100.][i % 6]), 10., 5., s)),
            )
            .child(block(px(32.), 10., 5., s))
    }));
    breathe("skel-rows", list, cx)
}
