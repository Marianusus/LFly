//! What plays now and next, for the full-window player (Tauri: pages/parts/QueueList.svelte):
//! the playing song with its bars, then the rest; a click plays one, the cross takes it out.

use std::sync::Arc;

use gpui_kit::*;
use lfly_connect::protocol;

use crate::core::Core;
use crate::covers::Covers;
use crate::icons::icon;
use crate::player_bar::clock;
use crate::player_model::PlayerModel;

fn section(text: String, first: bool) -> Div {
    div()
        .mt(px(if first { 0. } else { 18. }))
        .mb(px(8.))
        .mx(px(8.))
        .text_size(px(11.))
        .font_weight(FontWeight::BOLD)
        .text_color(white().opacity(0.55))
        .child(text)
}

fn cover_box(image: Option<Arc<Image>>) -> Div {
    div()
        .size(px(48.))
        .flex_none()
        .rounded(px(8.))
        .bg(white().opacity(0.08))
        .children(image.map(|i| img(i).size_full().object_fit(ObjectFit::Cover).rounded(px(8.))))
}

fn text_col(title: String, artist: String) -> Div {
    div()
        .flex_1()
        .min_w_0()
        .flex()
        .flex_col()
        .gap(px(2.))
        .child(div().text_size(px(14.)).font_weight(FontWeight::SEMIBOLD).truncate().child(title))
        .child(div().text_size(px(12.)).text_color(white().opacity(0.6)).truncate().child(artist))
}

pub fn queue_list(model: &Entity<PlayerModel>, covers: &Entity<Covers>, core: &Arc<Core>, accent: Hsla, cx: &mut App) -> Div {
    let m = model.read(cx);
    let playing = m.playing();
    let (queue, index) = m.state.as_ref().map_or((Vec::new(), -1), |s| (s.queue.clone(), s.index));
    let current = usize::try_from(index).ok().and_then(|i| queue.get(i)).cloned();
    let mut cover = |url: &Option<String>| url.as_ref().and_then(|u| covers.update(cx, |c, cx| c.get(u, cx)));

    let mut out = div().flex().flex_col();
    if let Some(t) = &current {
        // `.eq`: three bars in the accent, low and still while paused (no animation here).
        let bars = div()
            .h(px(14.))
            .mr(px(6.))
            .flex()
            .items_end()
            .gap(px(2.))
            .children([1.0f32, 0.6, 0.8].map(|h| div().w(px(3.)).h(px(14. * if playing { h } else { 0.35 })).rounded(px(2.)).bg(accent)));
        out = out.child(section("IN RIPRODUZIONE".into(), true)).child(
            div()
                .flex()
                .items_center()
                .gap(px(12.))
                .px(px(8.))
                .py(px(6.))
                .rounded(px(12.))
                .bg(white().opacity(0.1))
                .child(cover_box(cover(&t.cover_url)))
                .child(text_col(t.title.clone(), t.artist.clone()))
                .child(bars),
        );
    }
    let upcoming: Vec<(usize, _)> = queue.iter().enumerate().filter(|(i, _)| *i as i32 > index).map(|(i, t)| (i, t.clone())).collect();
    let n = upcoming.len();
    out = out.child(section(if n > 0 { format!("SUCCESSIVI · {n}") } else { "SUCCESSIVI".into() }, current.is_none()));
    if upcoming.is_empty() {
        return out.child(div().mx(px(8.)).my(px(4.)).text_color(white().opacity(0.6)).child("Nient'altro in coda."));
    }
    for (i, t) in upcoming {
        let group: SharedString = format!("q-{i}").into();
        let (model, core) = (model.clone(), core.clone());
        let (menu_t, menu_core) = (t.clone(), core.clone());
        out = out.child(
            div()
                .id(("q", i))
                .on_mouse_down(MouseButton::Right, move |e, _, cx| {
                    let core = menu_core.clone();
                    let remove: crate::menu::Remove = std::rc::Rc::new(move |_| core.remove_from_queue(i));
                    crate::menu::Menu::open_track(e.position, menu_t.clone(), Some(remove), cx);
                })
                .group(group.clone())
                .flex()
                .items_center()
                .gap(px(12.))
                .px(px(8.))
                .py(px(6.))
                .rounded(px(12.))
                .hover(|d| d.bg(white().opacity(0.08)))
                .child(
                    div()
                        .id(("q-open", i))
                        .flex_1()
                        .min_w_0()
                        .flex()
                        .items_center()
                        .gap(px(12.))
                        .cursor_pointer()
                        .on_click(move |_, _, cx| model.read(cx).command(protocol::CMD_SKIP_TO, i as i64))
                        .child(
                            div()
                                .relative()
                                .flex_none()
                                .child(cover_box(cover(&t.cover_url)))
                                .child(
                                    div()
                                        .absolute()
                                        .inset_0()
                                        .rounded(px(8.))
                                        .bg(black().opacity(0.45))
                                        .flex()
                                        .items_center()
                                        .justify_center()
                                        .invisible()
                                        .group_hover(group.clone(), |d| d.visible())
                                        .child(icon("play", 16., white())),
                                ),
                        )
                        .child(text_col(t.title.clone(), t.artist.clone())),
                )
                .child(
                    div()
                        .flex_none()
                        .text_size(px(12.))
                        .text_color(white().opacity(0.6))
                        .child(if t.duration_ms > 0 { clock(t.duration_ms as u64) } else { SharedString::default() }),
                )
                .child(
                    div()
                        .id(("q-rm", i))
                        .size(px(30.))
                        .flex_none()
                        .rounded_full()
                        .flex()
                        .items_center()
                        .justify_center()
                        .cursor_pointer()
                        .invisible()
                        .group_hover(group, |d| d.visible())
                        .hover(|d| d.bg(white().opacity(0.12)))
                        .on_click(move |_, _, _| core.remove_from_queue(i))
                        .child(icon("close", 14., white().opacity(0.6))),
                ),
        );
    }
    out
}
