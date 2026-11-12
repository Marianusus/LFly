//! The player bar (Tauri: components/PlayerBar.svelte): a pill of glass floating over the page,
//! with the same columns, sizes and colours. It is the only view that observes the position, so
//! the 4 Hz ticks redraw nothing else.

use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use lfly_connect::protocol;

use crate::covers::Covers;
use crate::glass;
use crate::icons::icon;
use crate::look::Look;
use crate::player_model::PlayerModel;
use crate::slider::{slider, SliderBounds, SliderStyle};

pub struct PlayerBar {
    model: Entity<PlayerModel>,
    nav: Entity<crate::nav::Nav>,
    covers: Entity<Covers>,
    look: Entity<Look>,
    /// While the seek slider is held: where it is (ms).
    scrubbing: Option<u64>,
    /// While the volume slider is held.
    volume_draft: Option<i64>,
    last_volume: i64,
    seek_bounds: SliderBounds,
    volume_bounds: SliderBounds,
    /// The bar's width, set by the window before it draws (PlayerBar.svelte's container queries).
    pub width: f32,
    _subs: Vec<Subscription>,
}

impl PlayerBar {
    pub fn new(model: Entity<PlayerModel>, nav: Entity<crate::nav::Nav>, covers: Entity<Covers>, look: Entity<Look>, cx: &mut Context<Self>) -> Self {
        let subs = vec![
            cx.observe(&model, |_, _, cx| cx.notify()),
            cx.observe(&covers, |_, _, cx| cx.notify()),
            cx.observe(&look, |_, _, cx| cx.notify()),
        ];
        Self {
            model,
            nav,
            covers,
            look,
            scrubbing: None,
            volume_draft: None,
            last_volume: 60,
            seek_bounds: SliderBounds::default(),
            volume_bounds: SliderBounds::default(),
            width: 1180.,
            _subs: subs,
        }
    }
}

/// connect.svelte.ts `time`: m:ss.
pub fn clock(ms: u64) -> SharedString {
    let s = ms / 1000;
    format!("{}:{:02}", s / 60, s % 60).into()
}

/// A round control (`.ctl`): 34 px, text-2 (or [base]), the text colour and a 9% wash under the
/// pointer, the accent and a dot when on.
#[allow(clippy::too_many_arguments)]
fn ctl(
    id: &'static str,
    name: &'static str,
    icon_size: f32,
    size: f32,
    on: bool,
    disabled: bool,
    base: Option<Hsla>,
    c: &Colors,
) -> Stateful<Div> {
    let fg = if on { c.accent } else { base.unwrap_or(c.text2) };
    let hover = if on { c.accent } else { c.text };
    let group: SharedString = format!("ctl-{id}").into();
    div()
        .id(id)
        .group(group.clone())
        .relative()
        .size(px(size))
        .flex_none()
        .flex()
        .items_center()
        .justify_center()
        .rounded_full()
        .when(disabled, |s| s.opacity(0.35))
        .when(!disabled, |s| s.cursor_pointer().hover(|s| s.bg(c.text.opacity(0.09))))
        .child(
            icon(name, icon_size, fg).when(!disabled, |i| i.group_hover(group.clone(), |s| s.text_color(hover))),
        )
        .when(on, |s| {
            s.child(div().absolute().bottom(px(3.)).left(relative(0.5)).ml(px(-2.)).size(px(4.)).rounded_full().bg(fg))
        })
}

#[derive(Clone, Copy)]
struct Colors {
    text: Hsla,
    text2: Hsla,
    text3: Hsla,
    accent: Hsla,
    bg: Hsla,
}

impl Render for PlayerBar {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        // LFLY_GLASS_HIDE: no player at all (the backdrop for the glass comparison).
        if std::env::var_os("LFLY_GLASS_HIDE").is_some() {
            return div().into_any_element();
        }
        let (c, look) = {
            let l = self.look.read(cx);
            let s = &l.scheme;
            (
                Colors { text: s.text, text2: s.text2, text3: s.text3, accent: s.accent.hsla(1.), bg: s.bg.hsla(1.) },
                l.glass(),
            )
        };
        let m = self.model.read(cx);
        let track = m.current().cloned();
        let has = track.is_some();
        let playing = m.playing();
        let duration_ms = m.duration_ms();
        let position_ms = self.scrubbing.unwrap_or(m.position_ms).min(duration_ms.max(m.position_ms));
        let (shuffle, repeat, volume) = m
            .state
            .as_ref()
            .map_or((false, "off".to_string(), 100), |s| (s.shuffle, s.repeat.clone(), if s.volume >= 0 { s.volume as i64 } else { 100 }));
        let volume = self.volume_draft.unwrap_or(volume);
        let cover = track
            .as_ref()
            .and_then(|t| t.cover_url.clone())
            .and_then(|u| self.covers.update(cx, |cv, cx| cv.get(&u, cx)));

        let model = self.model.clone();
        let cmd = move |action: &'static str, value: i64| {
            let model = model.clone();
            move |_: &ClickEvent, _: &mut Window, cx: &mut App| model.read(cx).command(action, value)
        };
        let next_repeat = match repeat.as_str() {
            "off" => protocol::REPEAT_ALL,
            "all" => protocol::REPEAT_ONE,
            _ => protocol::REPEAT_OFF,
        };
        let seek_frac = if duration_ms > 0 { position_ms as f32 / duration_ms as f32 } else { 0. };
        let style = || SliderStyle { text: c.text, accent: c.accent };
        let me = cx.entity().downgrade();
        let (me1, me2, me3, me4) = (me.clone(), me.clone(), me.clone(), me.clone());
        let mute_to = if volume > 0 { 0 } else { self.last_volume.max(1) };
        // @container bar (max-width: 900px) / (max-width: 620px).
        let medium = self.width <= 900.;
        let narrow = self.width <= 620.;

        let open_player = {
            let nav = self.nav.clone();
            move |_: &ClickEvent, _: &mut Window, cx: &mut App| {
                if has {
                    nav.update(cx, |n, cx| n.go(crate::nav::Route::NowPlaying, cx));
                }
            }
        };
        let (open1, open2, open3) = (open_player.clone(), open_player.clone(), open_player);
        // The three columns of `.bar`: minmax(0,1fr) minmax(300px,1.25fr) minmax(0,1fr).
        let now = div()
            .flex_grow(1.)
            .flex_basis(relative(0.))
            .min_w_0()
            .flex()
            .items_center()
            .gap(px(12.))
            .child(
                div()
                    .id("bar-art")
                    .size(px(if narrow { 46. } else { 52. }))
                    .flex_none()
                    .when(has, |d| d.cursor_pointer())
                    .on_click(open1)
                    .rounded(px(14.))
                    .overflow_hidden()
                    .bg(c.text.opacity(0.08))
                    .shadow(vec![BoxShadow {
                        color: black().opacity(0.35),
                        offset: point(px(0.), px(4.)),
                        blur_radius: px(14.),
                        spread_radius: px(0.),
                        inset: false,
                    }])
                    .children(cover.map(|i| img(i).size_full().object_fit(ObjectFit::Cover).rounded(px(14.)))),
            )
            .child(
                div()
                    .flex_1()
                    .min_w_0()
                    .flex()
                    .flex_col()
                    .child(
                        div()
                            .id("bar-title")
                            .when(has, |d| d.cursor_pointer())
                            .on_click(open2)
                            .text_size(px(14.))
                            .line_height(px(18.2))
                            .font_weight(FontWeight::SEMIBOLD)
                            .text_color(if has { c.text } else { c.text2 })
                            .truncate()
                            .child(track.as_ref().map_or(SharedString::from("Niente in riproduzione"), |t| t.title.clone().into())),
                    )
                    .child(
                        div()
                            .text_size(px(12.5))
                            .line_height(px(16.9))
                            .text_color(if has { c.text2 } else { c.text3 })
                            .truncate()
                            .child(track.as_ref().map_or(SharedString::from("Scegli un brano per iniziare"), |t| t.artist.clone().into())),
                    ),
            )
            .when(has && !narrow, |s| {
                s.child(
                    div()
                        .flex()
                        .items_center()
                        .gap(px(2.))
                        .flex_none()
                        .child(ctl("like", "heart", 16., 30., false, false, None, &c))
                        .when(!medium, |s| s.child(ctl("more", "more", 18., 30., false, false, None, &c)))
                        .child(
                            div()
                                .id("open")
                                .size(px(30.))
                                .ml(px(2.))
                                .flex()
                                .items_center()
                                .justify_center()
                                .rounded_full()
                                .text_color(c.text)
                                .bg(c.text.opacity(0.1))
                                .cursor_pointer()
                                .hover(|s| s.bg(c.text.opacity(0.18)))
                                .on_click(open3)
                                .child(icon("chevron-up", 16., c.text)),
                        ),
                )
            });

        // 1.25fr against the sides' 1fr, never under 300 px.
        let center = div()
            .when(!narrow, |s| {
                s.flex_grow(if medium { 1.2 } else { 1.25 })
                    .flex_basis(relative(0.))
                    .flex_shrink_0()
                    .min_w(px(if medium { 240. } else { 300. }))
            })
            .when(narrow, |s| s.flex_none())
            .when(!narrow, |s| s.pt(px(4.)))
            .flex()
            .flex_col()
            .items_center()
            .gap(px(2.))
            .child(
                div()
                    .flex()
                    .items_center()
                    .gap(px(8.))
                    .when(!medium, |s| {
                        s.child(ctl("shuffle", "shuffle", 16., 34., shuffle, !has, None, &c).on_click(cmd(protocol::CMD_SHUFFLE, if shuffle { 0 } else { 1 })))
                    })
                    .child(
                        ctl("previous", "previous", 19., 38., false, !has, Some(c.text), &c)
                            .on_click(cmd(protocol::CMD_PREVIOUS, 0)),
                    )
                    .child(
                        div()
                            .id("play")
                            .size(px(40.))
                            .mx(px(4.))
                            .flex()
                            .items_center()
                            .justify_center()
                            .rounded_full()
                            .bg(c.text)
                            .text_color(c.bg)
                            .shadow(vec![BoxShadow {
                                color: black().opacity(0.25),
                                offset: point(px(0.), px(4.)),
                                blur_radius: px(14.),
                                spread_radius: px(0.),
                                inset: false,
                            }])
                            .when(!has, |s| s.opacity(0.4))
                            .when(has, |s| s.cursor_pointer().on_click(cmd(protocol::CMD_TOGGLE, 0)))
                            .child(icon(if playing { "pause" } else { "play" }, 18., c.bg)),
                    )
                    .child(ctl("next", "next", 19., 38., false, !has, Some(c.text), &c).on_click(cmd(protocol::CMD_NEXT, 0)))
                    .when(!medium, |s| {
                        s.child(
                            ctl("repeat", "repeat", 16., 34., repeat != "off", !has, None, &c)
                                .on_click(cmd(protocol::CMD_REPEAT, next_repeat))
                                .when(repeat == "one", |s| {
                                    s.child(div().absolute().top(px(4.)).right(px(4.)).text_size(px(8.)).font_weight(FontWeight::EXTRA_BOLD).child("1"))
                                }),
                        )
                    }),
            )
            .when(!narrow, |s| s.child(
                div()
                    .w_full()
                    .flex()
                    .items_center()
                    .gap(px(10.))
                    .child(div().min_w(px(34.)).text_right().text_size(px(11.)).text_color(c.text3).child(clock(position_ms)))
                    .child(slider(
                        "seek",
                        seek_frac,
                        !has || duration_ms == 0,
                        style(),
                        self.seek_bounds.clone(),
                        move |f, _, cx| {
                            let _ = me1.update(cx, |this, cx| {
                                this.scrubbing = Some((f * duration_ms as f32) as u64);
                                cx.notify();
                            });
                        },
                        move |f, _, cx| {
                            let _ = me2.update(cx, |this, cx| {
                                this.scrubbing = None;
                                this.model.read(cx).command(protocol::CMD_SEEK, (f * duration_ms as f32) as i64);
                                cx.notify();
                            });
                        },
                    ))
                    .child(div().min_w(px(34.)).text_size(px(11.)).text_color(c.text3).child(clock(duration_ms))),
            ));

        let volume_icon = if volume == 0 { "volume-mute" } else if volume < 50 { "speaker-wave" } else { "volume" };
        let right = div()
            .when(!medium, |s| s.flex_grow(1.).flex_basis(relative(0.)))
            .when(medium, |s| s.flex_none())
            .min_w_0()
            .flex()
            .items_center()
            .justify_end()
            .gap(px(4.))
            .when(!narrow, |s| s.child(ctl("lyrics", "lyrics", 17., 34., false, !has, None, &c)))
            .child(ctl("queue", "queue", 17., 34., false, false, None, &c))
            .child(ctl("devices", "devices", 17., 34., false, false, None, &c))
            .when(!narrow, |s| s.child(
                div()
                    .flex()
                    .items_center()
                    .gap(px(4.))
                    .child(ctl("mute", volume_icon, 17., 34., false, false, None, &c).on_click({
                        let me = me.clone();
                        move |_, _, cx| {
                            let _ = me.update(cx, |this, cx| {
                                if volume > 0 {
                                    this.last_volume = volume;
                                }
                                this.model.read(cx).command(protocol::CMD_VOLUME, mute_to);
                                cx.notify();
                            });
                        }
                    }))
                    .when(!medium, |s| s.child(div().w(px(112.)).flex().child(slider(
                        "volume",
                        volume as f32 / 100.,
                        false,
                        style(),
                        self.volume_bounds.clone(),
                        move |f, _, cx| {
                            let _ = me3.update(cx, |this, cx| {
                                this.volume_draft = Some((f * 100.).round() as i64);
                                cx.notify();
                            });
                        },
                        move |f, _, cx| {
                            let _ = me4.update(cx, |this, cx| {
                                let v = (f * 100.).round() as i64;
                                this.model.read(cx).command(protocol::CMD_VOLUME, v);
                                this.volume_draft = None;
                                cx.notify();
                            });
                        },
                    )))),
            ));

        div()
            .id("player")
            .absolute()
            .left_0()
            .right_0()
            // LFLY_GLASS_DEBUG: over the list, to look at the bending and the blur.
            .bottom(px(if std::env::var_os("LFLY_GLASS_DEBUG").is_some() { 318. } else { 0. }))
            .h(px(if narrow { 64. } else { 76. }))
            .rounded(px(999.))
            // PlayerBar.svelte's Glass is not `interactive`: no pointer light.
            .child(glass::glass_layer(look, px(38.), glass::Pointer::default()))
            // LFLY_GLASS_EMPTY: the glass alone, for comparing it with the Tauri one.
            .when(std::env::var_os("LFLY_GLASS_EMPTY").is_none(), |bar| {
                bar.child(
                    div()
                        .absolute()
                        .inset_0()
                        .rounded(px(999.))
                        .overflow_hidden()
                        // Pages scroll under the bar: a little of the canvas colour keeps it legible.
                        .bg(c.bg.opacity(0.58))
                        .pl(px(if narrow { 8. } else { 12. }))
                        .pr(px(if narrow { 10. } else { 18. }))
                        .flex()
                        .items_center()
                        .gap(px(if narrow { 6. } else { 16. }))
                        .child(now)
                        .child(center)
                        .child(right)
                        // Narrow: a hairline of progress along the bottom (`.line`).
                        .when(narrow, |s| {
                            s.child(
                                div()
                                    .absolute()
                                    .left(px(28.))
                                    .right(px(28.))
                                    .bottom_0()
                                    .h(px(2.))
                                    .rounded(px(2.))
                                    .bg(c.text.opacity(0.14))
                                    .overflow_hidden()
                                    .child(div().h_full().bg(c.accent).w(relative(seek_frac.clamp(0., 1.)))),
                            )
                        }),
                )
            })
            .into_any_element()
    }
}
