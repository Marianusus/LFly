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
    /// The current song, and the music plays (TrackRow.svelte `live`): the bars move.
    pub live: bool,
    pub liked: bool,
    /// On this computer (TrackRow.svelte `.saved`).
    pub downloaded: bool,
    pub is_video: bool,
    /// The song for the right-click menu and "more", with its "remove from here" if any.
    pub menu: Option<(lfly_connect::protocol::TrackRef, Option<crate::menu::Remove>)>,
    /// The heart's action.
    pub like: Option<crate::menu::Remove>,
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
        .when(d.live, |r| r.child(eq_row_mark()))
        .when(!d.playing, |r| r.hover(|r| r.bg(s.text.opacity(0.07))).active(|r| r.bg(s.text.opacity(0.13))))
        .on_click(on_play)
        .when_some(d.menu.clone(), |r, (t, remove)| {
            r.on_mouse_down(MouseButton::Right, move |e, _, cx| {
                cx.stop_propagation();
                crate::menu::Menu::open_track(e.position, t.clone(), remove.clone(), cx);
            })
        })
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
                .child(div().group_hover(group.clone(), |n| n.invisible()).child(if d.live { eq_mark().into_any_element() } else { div().child(SharedString::from(d.number.to_string())).into_any_element() }))
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
                        .child(icon(if d.live { "pause" } else { "play" }, 13., s.text)),
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
                        .children(d.cover.map(|i| crate::covers::fade(i.id(), img(i).size_full().object_fit(ObjectFit::Cover).rounded(px(10.))))),
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
                                .when(d.downloaded, |r| {
                                    r.child(div().size(px(15.)).flex_none().rounded_full().flex().items_center().justify_center().bg(accent).child(icon("download", 11., s.on_accent.hsla(1.))))
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
                        .id("like")
                        .size(px(30.))
                        .flex()
                        .items_center()
                        .justify_center()
                        .rounded_full()
                        .when(!d.liked, |l| l.invisible().group_hover(group.clone(), |l| l.visible()))
                        .when_some(d.like.clone(), |l, like| {
                            l.cursor_pointer().on_click(move |_, _, cx| {
                                cx.stop_propagation();
                                like(cx);
                            })
                        })
                        .child(icon(if d.liked { "heart-fill" } else { "heart" }, 15., if d.liked { accent } else { s.text2 })),
                )
                .child(div().min_w(px(36.)).text_right().text_size(px(12.5)).font_features(crate::ui::tnum()).text_color(s.text3).child(if d.duration_ms > 0 { clock(d.duration_ms as u64) } else { SharedString::default() }))
                .child(
                    div()
                        .id("more")
                        .size(px(30.))
                        .flex()
                        .items_center()
                        .justify_center()
                        .rounded_full()
                        .invisible()
                        .group_hover(group, |m| m.visible())
                        .when_some(d.menu, |m, (t, remove)| {
                            m.cursor_pointer().on_click(move |e, _, cx| {
                                cx.stop_propagation();
                                crate::menu::Menu::open_track(e.position(), t.clone(), remove.clone(), cx);
                            })
                        })
                        .child(icon("more", 18., s.text2)),
                ),
        )
}

/// The phone's GraphicEq (TrackRow.svelte `.eq`): three bars [h] px high in [color], each rising
/// from 30% to full and back every 0.9 s, a third of a turn apart, while [moving]; standing
/// staggered when [live] but still (window behind, reduced motion); low when not [live].
/// 30 frames a second is plenty for them.
pub fn eq_bars(id: &'static str, h: f32, color: Hsla, live: bool, moving: bool) -> AnyElement {
    let bars = move |scales: [f32; 3]| div().h(px(h)).flex().items_end().gap(px(2.)).children(scales.map(|k| div().w(px(3.)).h(px(h * k)).rounded(px(2.)).bg(color)));
    if !live {
        return bars([0.35, 0.35, 0.35]).into_any_element();
    }
    if !moving {
        return bars([0.7, 0.45, 0.6]).into_any_element();
    }
    div()
        .child(bars([0.3, 0.3, 0.3]))
        .with_animation(id, Animation::new(std::time::Duration::from_millis(900)).repeat().with_max_fps(30.), move |_, t| {
            let at = |phase: f32| {
                let p = (t + phase).fract();
                let q = if p < 0.5 { p * 2. } else { (1. - p) * 2. };
                0.3 + 0.7 * q * q * (3. - 2. * q)
            };
            div().child(bars([at(0.), at(1. / 3.), at(2. / 3.)]))
        })
        .into_any_element()
}

thread_local! {
    /// Where the live row's number cell was drawn (window px): the window draws its bars there
    /// ([eq_overlay]), so they move without drawing the page again 30 times a second.
    static EQ_CELL: std::cell::Cell<Option<Bounds<Pixels>>> = const { std::cell::Cell::new(None) };
    /// The live row itself: the pointer on it shows the pause disc instead of the bars.
    static EQ_ROW: std::cell::Cell<Option<Bounds<Pixels>>> = const { std::cell::Cell::new(None) };
    /// The list's clip where the cell was drawn: the bars are cut there too.
    static EQ_CLIP: std::cell::Cell<Option<Bounds<Pixels>>> = const { std::cell::Cell::new(None) };
    /// The bars were there before the page began drawing again (so it is not asked for one
    /// more frame each time it draws).
    static EQ_WAS: std::cell::Cell<bool> = const { std::cell::Cell::new(false) };
}

/// Forgets the live row (a page about to draw its rows again, or another page shown).
pub fn eq_begin() {
    EQ_WAS.with(|w| w.set(EQ_CELL.with(|c| c.take()).is_some()));
    EQ_ROW.with(|c| c.set(None));
}

/// Records [b] in [slot] if it can be seen (not scrolled out of its list's clip); the window is
/// asked for a frame when the bars appear, so they start at once.
fn eq_record(slot: &'static std::thread::LocalKey<std::cell::Cell<Option<Bounds<Pixels>>>>, b: Bounds<Pixels>, window: &mut Window) -> bool {
    let clip = window.content_mask().bounds;
    let seen = clip.intersects(&b);
    slot.with(|c| c.set(seen.then_some(b)));
    if seen {
        EQ_CLIP.with(|c| c.set(Some(clip)));
    }
    seen
}

/// In the live row's number cell: records where it is; when the bars were not there before,
/// asks for a frame so the window draws them at once (`refresh` is ignored while drawing).
fn eq_mark() -> impl IntoElement {
    div().size(px(13.)).child(
        canvas(
            |b, window, _| {
                if eq_record(&EQ_CELL, b, window) && !EQ_WAS.with(|w| w.get()) {
                    EQ_WAS.with(|w| w.set(true));
                    window.request_animation_frame();
                }
            },
            |_, _, _, _| {},
        )
        .size_full(),
    )
}

/// In the live row: records the row.
fn eq_row_mark() -> impl IntoElement {
    canvas(
        |b, window, _| {
            eq_record(&EQ_ROW, b, window);
        },
        |_, _, _, _| {},
    )
    .absolute()
    .inset_0()
}

/// The live row's bars, drawn by the window over the page (under the bars and menus above it),
/// moving when [moving]; none while the pointer is on that row (its pause disc shows there).
pub fn eq_overlay(color: Hsla, moving: bool) -> impl IntoElement {
    canvas(
        |_, _, _| {},
        move |_, _, window, _| {
            let Some(b) = EQ_CELL.with(|c| c.get()) else { return };
            if EQ_ROW.with(|c| c.get()).is_some_and(|r| r.contains(&window.mouse_position())) {
                return;
            }
            let t = if moving { (EQ_EPOCH.with(|e| e.elapsed().as_secs_f32()) / 0.9).fract() } else { 0. };
            let at = |phase: f32| {
                if !moving {
                    return [0.7, 0.45, 0.6][(phase * 3.).round() as usize % 3];
                }
                let p = (t + phase).fract();
                let q = if p < 0.5 { p * 2. } else { (1. - p) * 2. };
                0.3 + 0.7 * q * q * (3. - 2. * q)
            };
            let h = 13.;
            let clip = EQ_CLIP.with(|c| c.get()).unwrap_or(b);
            window.with_content_mask(Some(ContentMask { bounds: clip }), |window| {
                for (i, phase) in [0., 1. / 3., 2. / 3.].into_iter().enumerate() {
                    let bh = h * at(phase);
                    let x = b.origin.x + px(i as f32 * 5.);
                    let y = b.origin.y + b.size.height / 2. + px(h / 2. - bh);
                    window.paint_quad(fill(Bounds::new(point(x, y), size(px(3.), px(bh))), color).corner_radii(px(1.5)));
                }
            });
        },
    )
    .absolute()
    .inset_0()
}

thread_local! {
    static EQ_EPOCH: std::time::Instant = std::time::Instant::now();
}

/// Whether a live row is shown (the window then redraws itself 30 times a second for its bars).
pub fn eq_shown() -> bool {
    EQ_CELL.with(|c| c.get().is_some())
}
