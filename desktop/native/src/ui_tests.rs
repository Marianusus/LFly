//! Headless checks (GPUI's test platform, no window on screen) of what the screens rely on from
//! GPUI's input dispatch.

use std::cell::Cell;
use std::rc::Rc;

use gpui::{
    div, point, px, size, Context, InteractiveElement, IntoElement, Modifiers, ParentElement, Pixels, Point, Render,
    ScrollDelta, ScrollEvent, StatefulInteractiveElement, Styled, TestAppContext, TouchPhase, Window,
};

fn at(x: f32, y: f32) -> Point<Pixels> {
    point(px(x), px(y))
}

/// A row under an overlay, as a track row under the menu or the player bar.
struct Layers {
    below: Rc<Cell<u32>>,
    above: Rc<Cell<u32>>,
    occlude: bool,
}

impl Render for Layers {
    fn render(&mut self, _: &mut Window, _: &mut Context<Self>) -> impl IntoElement {
        let (b, a) = (self.below.clone(), self.above.clone());
        let top = div().id("above").absolute().top_0().left_0().size(px(100.)).on_click(move |_, _, _| a.set(a.get() + 1));
        let top = if self.occlude { top.occlude() } else { top };
        div()
            .size(px(300.))
            .relative()
            .child(div().id("below").absolute().top_0().left_0().size(px(200.)).on_click(move |_, _, _| b.set(b.get() + 1)))
            .child(top)
    }
}

fn click_layers(cx: &mut TestAppContext, occlude: bool) -> (u32, u32) {
    let (below, above) = (Rc::new(Cell::new(0)), Rc::new(Cell::new(0)));
    let (b, a) = (below.clone(), above.clone());
    let (_, cx) = cx.add_window_view(move |_, _| Layers { below: b, above: a, occlude });
    cx.simulate_click(at(50., 50.), Modifiers::default());
    (below.get(), above.get())
}

#[gpui::test]
fn a_click_reaches_what_is_under_unless_occluded(cx: &mut TestAppContext) {
    assert_eq!(click_layers(cx, false), (1, 1), "GPUI hands the click to the element below too");
    assert_eq!(click_layers(cx, true), (0, 1), "occlude() keeps it");
}

/// A button inside a clickable parent, as the lyrics' timing capsule inside the lines.
struct Nested {
    parent: Rc<Cell<u32>>,
    child: Rc<Cell<u32>>,
}

impl Render for Nested {
    fn render(&mut self, _: &mut Window, _: &mut Context<Self>) -> impl IntoElement {
        let (p, c) = (self.parent.clone(), self.child.clone());
        div().id("parent").size(px(300.)).on_click(move |_, _, _| p.set(p.get() + 1)).child(
            div().id("child").size(px(50.)).on_click(move |_, _, cx| {
                cx.stop_propagation();
                c.set(c.get() + 1);
            }),
        )
    }
}

#[gpui::test]
fn stop_propagation_keeps_the_parent_out(cx: &mut TestAppContext) {
    let (parent, child) = (Rc::new(Cell::new(0)), Rc::new(Cell::new(0)));
    let (p, c) = (parent.clone(), child.clone());
    let (_, cx) = cx.add_window_view(move |_, _| Nested { parent: p, child: c });
    cx.simulate_click(at(10., 10.), Modifiers::default());
    assert_eq!((parent.get(), child.get()), (0, 1), "the nudge does not seek");
    cx.simulate_click(at(200., 200.), Modifiers::default());
    assert_eq!(parent.get(), 1);
}

struct Plain;
impl Render for Plain {
    fn render(&mut self, _: &mut Window, _: &mut Context<Self>) -> impl IntoElement {
        div().size(px(200.))
    }
}

#[gpui::test]
fn intercepted_keys_arrive_without_focus(cx: &mut TestAppContext) {
    let seen = Rc::new(Cell::new(0));
    let s = seen.clone();
    cx.update(|cx| {
        cx.intercept_keystrokes(move |e, _, _| {
            if matches!(e.keystroke.key.as_str(), "escape" | "space") {
                s.set(s.get() + 1);
            }
        })
        .detach()
    });
    let (_, cx) = cx.add_window_view(|_, _| Plain);
    cx.simulate_keystrokes("escape space");
    assert_eq!(seen.get(), 2);
}

struct Wheel(Rc<Cell<f32>>);
impl Render for Wheel {
    fn render(&mut self, _: &mut Window, _: &mut Context<Self>) -> impl IntoElement {
        let got = self.0.clone();
        div().id("wheel").size(px(200.)).on_scroll(move |e, _, _| got.set(f32::from(e.delta.pixel_delta(px(16.)).y)))
    }
}

#[gpui::test]
fn the_wheel_keeps_its_sign(cx: &mut TestAppContext) {
    // Wayland and Windows report turning the wheel toward you as a negative y; the lyrics turn
    // it around so that scrolls the list down, as every list does.
    let got = Rc::new(Cell::new(0.));
    let g = got.clone();
    let (_, cx) = cx.add_window_view(move |_, _| Wheel(g));
    cx.simulate_event(ScrollEvent {
        position: at(50., 50.),
        delta: ScrollDelta::Pixels(point(px(0.), px(-30.))),
        modifiers: Modifiers::default(),
        touch_phase: TouchPhase::Moved,
        source: Default::default(),
    });
    assert_eq!(got.get(), -30.);
    let _ = size(px(1.), px(1.));
}

/// A library tile: its play button plays without opening, the rest of it opens.
struct Tile {
    opened: Rc<Cell<u32>>,
    played: Rc<Cell<u32>>,
}

impl Render for Tile {
    fn render(&mut self, _: &mut Window, _: &mut Context<Self>) -> impl IntoElement {
        let s = crate::theme::build_scheme(crate::theme::Mode::DynamicDark, None, None);
        let (o, p) = (self.opened.clone(), self.played.clone());
        div().size(px(400.)).child(crate::library_page::library_item(
            "t".into(),
            "Titolo".into(),
            "Sotto".into(),
            crate::library_page::Art::Cover(None),
            true,
            380.,
            false,
            &s,
            move |_| o.set(o.get() + 1),
            Some(Rc::new(move |_: &mut gpui::App| p.set(p.get() + 1))),
        ))
    }
}

#[gpui::test]
fn a_library_rows_play_button_does_not_open_it(cx: &mut TestAppContext) {
    let (opened, played) = (Rc::new(Cell::new(0)), Rc::new(Cell::new(0)));
    let (o, p) = (opened.clone(), played.clone());
    let (_, cx) = cx.add_window_view(move |_, _| Tile { opened: o, played: p });
    // The list row: 52 px art at 6 px, the 34 px play button at its right end (380 - 10 - 17).
    cx.simulate_click(at(353., 32.), Modifiers::default());
    assert_eq!((opened.get(), played.get()), (0, 1));
    cx.simulate_click(at(120., 32.), Modifiers::default());
    assert_eq!((opened.get(), played.get()), (1, 1));
}
