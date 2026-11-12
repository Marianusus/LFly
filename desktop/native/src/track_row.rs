//! A track row and the columns above a list of them (Tauri: components/TrackRow.svelte,
//! components/TrackList.svelte): number (a play button under the pointer), 42 px cover, title and
//! artist, album, the like button, duration and "more".

use std::sync::Arc;

use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;

use crate::icons::icon;
use crate::player_bar::clock;
use crate::theme::{Rgb, Scheme};

/// What a row shows; the page owns the tracks.
pub struct RowData {
    pub number: usize,
    pub title: SharedString,
    pub artist: SharedString,
    pub album: Option<SharedString>,
    pub duration_ms: i64,
    pub cover: Option<Arc<Image>>,
    pub playing: bool,
    pub liked: bool,
    pub is_video: bool,
}

pub fn separator(s: &Scheme) -> Hsla {
    if s.light { Rgb::hex(0x15151a).hsla(0.1) } else { Rgb::hex(0xffffff).hsla(0.1) }
}

/// The grid: 36px minmax(0,4fr) minmax(0,3fr) auto, gap 14, padding 0 10 0 8.
fn grid(show_album: bool) -> (f32, f32) {
    if show_album { (4., 3.) } else { (1., 0.) }
}

/// TrackList.svelte `.cols`: #, Titolo, Album, a clock; 34 px over a separator.
pub fn columns(show_album: bool, s: &Scheme) -> impl IntoElement {
    let (t, a) = grid(show_album);
    div()
        .mx(px(16.))
        .h(px(34.))
        .mb(px(6.))
        .pl(px(8.))
        .pr(px(10.))
        .flex()
        .items_center()
        .gap(px(14.))
        .border_b_1()
        .border_color(separator(s))
        .text_size(px(11.))
        .font_weight(FontWeight::SEMIBOLD)
        .text_color(s.text3)
        .child(div().w(px(36.)).flex_none().text_center().child("#"))
        .child(div().flex_grow(t).flex_basis(relative(0.)).min_w_0().child("TITOLO"))
        .when(show_album, |d| d.child(div().flex_grow(a).flex_basis(relative(0.)).min_w_0().child("ALBUM")))
        .child(div().w(px(96.)).flex_none().flex().justify_center().child(icon("clock", 14., s.text3)))
}

/// One row (58 px). [on_play] plays it (from here in its list).
pub fn track_row(
    id: impl Into<ElementId>,
    d: RowData,
    show_album: bool,
    s: &Scheme,
    on_play: impl Fn(&ClickEvent, &mut Window, &mut App) + 'static,
) -> impl IntoElement {
    let id: ElementId = id.into();
    let group: SharedString = format!("row-{id}").into();
    let (t, a) = grid(show_album);
    let accent = s.accent.hsla(1.);
    div()
        .id(id)
        .group(group.clone())
        .mx(px(16.))
        .h(px(58.))
        .pl(px(8.))
        .pr(px(10.))
        .flex()
        .items_center()
        .gap(px(14.))
        .rounded(px(16.))
        .cursor_pointer()
        .when(d.playing, |r| r.bg(accent.opacity(0.12)))
        .when(!d.playing, |r| r.hover(|r| r.bg(s.text.opacity(0.07))))
        .on_click(on_play)
        // `.num`: the number (13px text-3), a 30 px play disc under the pointer.
        .child(
            div()
                .relative()
                .w(px(36.))
                .h_full()
                .flex_none()
                .flex()
                .items_center()
                .justify_center()
                .text_size(px(13.))
                .text_color(if d.playing { accent } else { s.text3 })
                .child(div().group_hover(group.clone(), |n| n.invisible()).child(if d.playing { SharedString::from("♪") } else { d.number.to_string().into() }))
                .child(
                    div()
                        .absolute()
                        .size(px(30.))
                        .rounded_full()
                        .flex()
                        .items_center()
                        .justify_center()
                        .pl(px(1.))
                        .bg(s.text.opacity(0.12))
                        .invisible()
                        .group_hover(group.clone(), |p| p.visible())
                        .child(icon(if d.playing { "pause" } else { "play" }, 13., s.text)),
                ),
        )
        .child(
            div()
                .flex_grow(t)
                .flex_basis(relative(0.))
                .min_w_0()
                .flex()
                .items_center()
                .gap(px(12.))
                .child(
                    div()
                        .size(px(42.))
                        .flex_none()
                        .rounded(px(10.))
                        .overflow_hidden()
                        .bg(s.text.opacity(0.08))
                        .children(d.cover.map(|i| img(i).size_full().object_fit(ObjectFit::Cover).rounded(px(10.)))),
                )
                .child(
                    div()
                        .min_w_0()
                        .flex()
                        .flex_col()
                        .gap(px(2.))
                        .child(
                            div()
                                .text_size(px(14.))
                                .line_height(px(18.2))
                                .font_weight(if d.playing { FontWeight::SEMIBOLD } else { FontWeight::MEDIUM })
                                .text_color(if d.playing { accent } else { s.text })
                                .truncate()
                                .child(d.title),
                        )
                        .child(
                            div()
                                .flex()
                                .items_center()
                                .gap(px(6.))
                                .min_w_0()
                                .text_size(px(12.5))
                                .text_color(s.text2)
                                .when(d.is_video, |r| {
                                    r.child(div().px(px(5.)).rounded(px(4.)).bg(s.text.opacity(0.12)).text_size(px(10.)).font_weight(FontWeight::BOLD).child("Video"))
                                })
                                .child(div().truncate().child(d.artist)),
                        ),
                ),
        )
        .when(show_album, |r| {
            r.child(div().flex_grow(a).flex_basis(relative(0.)).min_w_0().text_size(px(12.5)).text_color(s.text2).truncate().children(d.album))
        })
        .child(
            div()
                .w(px(96.))
                .flex_none()
                .flex()
                .items_center()
                .justify_end()
                .gap(px(4.))
                .child(
                    div()
                        .size(px(30.))
                        .flex()
                        .items_center()
                        .justify_center()
                        .when(!d.liked, |l| l.invisible().group_hover(group.clone(), |l| l.visible()))
                        .child(icon(if d.liked { "heart-fill" } else { "heart" }, 15., if d.liked { accent } else { s.text2 })),
                )
                .child(div().min_w(px(36.)).text_right().text_size(px(12.5)).text_color(s.text3).child(if d.duration_ms > 0 { clock(d.duration_ms as u64) } else { SharedString::default() }))
                .child(
                    div()
                        .size(px(30.))
                        .flex()
                        .items_center()
                        .justify_center()
                        .invisible()
                        .group_hover(group, |m| m.visible())
                        .child(icon("more", 18., s.text2)),
                ),
        )
}
