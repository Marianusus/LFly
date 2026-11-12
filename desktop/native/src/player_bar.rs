//! The player bar: a pill of glass floating over the page (Tauri: PlayerBar.svelte). It is the
//! only view that observes the position, so the 4 Hz ticks redraw nothing else.

use gpui_kit::component::ActiveTheme;
use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use lfly_connect::protocol;

use crate::covers::Covers;
use crate::glass;
use crate::player_model::PlayerModel;

pub struct PlayerBar {
    model: Entity<PlayerModel>,
    covers: Entity<Covers>,
    _subs: Vec<Subscription>,
}

impl PlayerBar {
    pub fn new(model: Entity<PlayerModel>, covers: Entity<Covers>, cx: &mut Context<Self>) -> Self {
        let subs = vec![cx.observe(&model, |_, _, cx| cx.notify()), cx.observe(&covers, |_, _, cx| cx.notify())];
        Self { model, covers, _subs: subs }
    }
}

pub fn clock(ms: u64) -> SharedString {
    let s = ms / 1000;
    format!("{}:{:02}", s / 60, s % 60).into()
}

impl Render for PlayerBar {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        // LFLY_GLASS_HIDE: no player at all (the backdrop for the glass comparison).
        if std::env::var_os("LFLY_GLASS_HIDE").is_some() {
            return div().into_any_element();
        }
        let m = self.model.read(cx);
        let track = m.current().cloned();
        let playing = m.playing();
        let position_ms = m.position_ms;
        let duration_ms = m.duration_ms();
        let cover = track
            .as_ref()
            .and_then(|t| t.cover_url.clone())
            .and_then(|u| self.covers.update(cx, |c, cx| c.get(&u, cx)));
        let frac = if duration_ms > 0 { (position_ms as f32 / duration_ms as f32).clamp(0., 1.) } else { 0. };
        let accent = cx.theme().primary;
        let model = self.model.clone();
        let button = |id: &'static str, label: &'static str| {
            div()
                .id(id)
                .size(px(36.))
                .flex()
                .items_center()
                .justify_center()
                .rounded_full()
                .cursor_pointer()
                .hover(|s| s.bg(white().opacity(0.1)))
                .child(label)
        };
        let look = glass::GlassLook::dark(0.75);
        div()
            .id("player")
            .absolute()
            .left(px(12.))
            .right(px(12.))
            // LFLY_GLASS_DEBUG: over the list, to look at the bending and the blur.
            .bottom(px(if std::env::var_os("LFLY_GLASS_DEBUG").is_some() { 330. } else { 12. }))
            .h(px(76.))
            .rounded(px(38.))
            .px_4()
            .flex()
            .items_center()
            .gap_4()
            // PlayerBar.svelte's Glass is not `interactive`: no pointer light.
            .child(glass::glass_layer(look, px(38.), glass::Pointer::default()))
            // LFLY_GLASS_EMPTY: the glass alone, for comparing it with the Tauri one.
            .when(std::env::var_os("LFLY_GLASS_EMPTY").is_none(), |bar| {
                let (m1, m2, m3) = (model.clone(), model.clone(), model.clone());
                bar.child(
                    div()
                        .size(px(52.))
                        .flex_none()
                        .rounded_md()
                        .overflow_hidden()
                        .bg(white().opacity(0.08))
                        .children(cover.map(|c| img(c).size_full().object_fit(ObjectFit::Cover))),
                )
                .child(
                    div()
                        .w(px(220.))
                        .flex_none()
                        .flex()
                        .flex_col()
                        .child(div().text_sm().font_weight(FontWeight::MEDIUM).truncate().child(
                            track.as_ref().map_or(SharedString::from("Niente in riproduzione"), |t| t.title.clone().into()),
                        ))
                        .child(div().text_xs().text_color(white().opacity(0.6)).truncate().child(
                            track.as_ref().map_or(SharedString::default(), |t| t.artist.clone().into()),
                        )),
                )
                .child(
                    div()
                        .flex_1()
                        .flex()
                        .flex_col()
                        .items_center()
                        .gap_1()
                        .child(
                            div()
                                .flex()
                                .gap_2()
                                .child(button("prev", "⏮").on_click(move |_, _, cx| {
                                    m1.read(cx).command(protocol::CMD_PREVIOUS, 0)
                                }))
                                .child(
                                    button("toggle", if playing { "⏸" } else { "▶" })
                                        .bg(white().opacity(0.12))
                                        .on_click(move |_, _, cx| m2.read(cx).toggle()),
                                )
                                .child(button("next", "⏭").on_click(move |_, _, cx| {
                                    m3.read(cx).command(protocol::CMD_NEXT, 0)
                                })),
                        )
                        .child(
                            div()
                                .w_full()
                                .max_w(px(560.))
                                .flex()
                                .items_center()
                                .gap_2()
                                .text_xs()
                                .text_color(white().opacity(0.6))
                                .child(clock(position_ms))
                                .child(
                                    div()
                                        .flex_1()
                                        .h(px(4.))
                                        .rounded_full()
                                        .bg(white().opacity(0.15))
                                        .child(div().h_full().rounded_full().bg(accent).w(relative(frac))),
                                )
                                .child(clock(duration_ms)),
                        ),
                )
                .child(div().w(px(220.)).flex_none())
            })
            .into_any_element()
    }
}
