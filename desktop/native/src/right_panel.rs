//! The right panel (Tauri: components/RightPanel.svelte): a glass column with the window controls,
//! the Coda / Testo / Dispositivi switch on a sliding pill, and under it the queue, the playing
//! song's lyrics or the devices. (LFly Connect is not ported yet: the devices list says so.)

use std::sync::Arc;
use std::time::Instant;

use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use lfly_connect::protocol;

use crate::core::Core;
use crate::covers::Covers;
use crate::glass;
use crate::icons::icon;
use crate::look::Look;
use crate::lyrics::Lyrics;
use crate::motion::CubicBezier;
use crate::player_bar::clock;
use crate::player_model::PlayerModel;
use crate::theme::{color_mix, Scheme};
use crate::ui::{SidePanel, Ui};

/// The pill's slide (SelectionPill.svelte springs it; this eases past the mark and back).
const SLIDE: CubicBezier = CubicBezier::new(0.34, 1.3, 0.64, 1.);

pub struct RightPanel {
    ui: Entity<Ui>,
    model: Entity<PlayerModel>,
    covers: Entity<Covers>,
    look: Entity<Look>,
    core: Arc<Core>,
    lyrics: Entity<Lyrics>,
    /// The pill: from which tab, since when.
    pill_from: f32,
    pill_to: f32,
    pill_at: Instant,
    /// Floating over the page (a window 900 px wide or less): nearly opaque.
    pub floating: bool,
    _subs: Vec<Subscription>,
}

fn index(p: Option<SidePanel>) -> f32 {
    match p {
        Some(SidePanel::Queue) | None => 0.,
        Some(SidePanel::Lyrics) => 1.,
        Some(SidePanel::Devices) => 2.,
    }
}

impl RightPanel {
    pub fn new(
        ui: Entity<Ui>,
        model: Entity<PlayerModel>,
        covers: Entity<Covers>,
        look: Entity<Look>,
        core: Arc<Core>,
        lyrics: Entity<Lyrics>,
        cx: &mut Context<Self>,
    ) -> Self {
        let subs = vec![
            cx.observe(&ui, |this, ui, cx| {
                let to = index(ui.read(cx).panel);
                if to != this.pill_to {
                    this.pill_from = this.pill_value();
                    this.pill_to = to;
                    this.pill_at = Instant::now();
                }
                cx.notify();
            }),
            cx.subscribe(&model, |_, _, _: &crate::player_model::PlayerEvent, cx| cx.notify()),
            cx.observe(&covers, |_, _, cx| cx.notify()),
            cx.observe(&look, |_, _, cx| cx.notify()),
        ];
        let at = index(ui.read(cx).panel);
        Self { ui, model, covers, look, core, lyrics, pill_from: at, pill_to: at, pill_at: Instant::now(), floating: false, _subs: subs }
    }

    fn pill_progress(&self) -> f32 {
        (self.pill_at.elapsed().as_secs_f32() / 0.46).min(1.)
    }

    fn pill_value(&self) -> f32 {
        self.pill_from + (self.pill_to - self.pill_from) * SLIDE.at(self.pill_progress() as f64) as f32
    }
}

/// `h3`: 600 11px, uppercase, text-3.
fn heading(text: String, s: &Scheme) -> Div {
    div().mt(px(18.)).mx(px(10.)).mb(px(10.)).text_size(px(11.)).font_weight(FontWeight::SEMIBOLD).text_color(s.text3).child(text)
}

/// The phone's GraphicEq: three accent bars (still here).
fn eq(s: &Scheme) -> Div {
    let a = s.accent.hsla(1.);
    div().h(px(14.)).mr(px(4.)).flex_none().flex().items_end().gap(px(2.)).children([0.55f32, 1., 0.7].map(|h| div().w(px(3.)).h(px(14. * h)).rounded(px(2.)).bg(a)))
}

/// `.now` and `.here.active`: the accent at 14% over the glass tint, a 30% accent ring.
fn lit_card(s: &Scheme, radius: f32) -> Div {
    let a = s.accent.hsla(1.);
    div()
        .flex()
        .items_center()
        .gap(px(12.))
        .p(px(10.))
        .rounded(px(radius))
        .bg(color_mix(a, 0.14, s.glass_tint))
        .border_1()
        .border_color(a.opacity(0.3))
}

impl RightPanel {
    fn queue(&mut self, s: &Scheme, cx: &mut Context<Self>) -> Div {
        let m = self.model.read(cx);
        let playing = m.playing();
        let (queue, index) = m.state.as_ref().map_or((Vec::new(), -1), |st| (st.queue.clone(), st.index));
        let current = usize::try_from(index).ok().and_then(|i| queue.get(i)).cloned();
        let covers = self.covers.clone();
        let mut cover = |url: &Option<String>| url.as_ref().and_then(|u| covers.update(cx, |c, cx| c.get(u, cx)));
        let Some(now) = current else {
            return div().flex().flex_col().items_center().gap(px(10.)).py(px(48.)).px(px(20.)).text_color(s.text2).child(
                div().size(px(56.)).rounded_full().flex().items_center().justify_center().bg(s.glass_tint).border_1().border_color(s.glass_border).child(icon("queue", 26., s.text2)),
            ).child(div().text_center().line_height(px(20.)).child("La coda è vuota. Fai partire un brano o una playlist."));
        };
        let art = |image: Option<Arc<Image>>, size: f32, radius: f32| {
            div().size(px(size)).flex_none().rounded(px(radius)).bg(s.text.opacity(0.08)).children(image.map(|i| img(i).size_full().object_fit(ObjectFit::Cover).rounded(px(radius))))
        };
        let text_col = |title: String, artist: String, bold: bool| {
            div()
                .flex_1()
                .min_w_0()
                .flex()
                .flex_col()
                .gap(px(1.))
                .child(div().text_size(px(14.)).when(bold, |d| d.font_weight(FontWeight::SEMIBOLD)).text_color(s.text).truncate().child(title))
                .child(div().text_size(px(12.)).text_color(s.text2).truncate().child(artist))
        };
        let upcoming: Vec<(usize, protocol::TrackRef)> = queue.iter().enumerate().filter(|(i, _)| *i as i32 > index).map(|(i, t)| (i, t.clone())).collect();
        let mut out = div()
            .flex()
            .flex_col()
            .child(heading("IN RIPRODUZIONE".into(), s))
            .child(lit_card(s, 20.).child(art(cover(&now.cover_url), 56., 12.)).child(text_col(now.title.clone(), now.artist.clone(), true)).when(playing, |d| d.child(eq(s))))
            .child(heading(if upcoming.is_empty() { "PROSSIMI BRANI".into() } else { format!("PROSSIMI BRANI  {}", upcoming.len()) }, s));
        if upcoming.is_empty() {
            return out.child(div().mx(px(10.)).my(px(8.)).text_color(s.text2).child("Niente in coda dopo questo brano."));
        }
        for (i, t) in upcoming {
            let group: SharedString = format!("rq-{i}").into();
            let (m1, m2, core) = (self.model.clone(), self.model.clone(), self.core.clone());
            out = out.child(
                div()
                    .id(("rq", i))
                    .group(group.clone())
                    .flex()
                    .items_center()
                    .gap(px(10.))
                    .px(px(8.))
                    .py(px(6.))
                    .rounded(px(16.))
                    .hover(|d| d.bg(s.text.opacity(0.07)))
                    // A double click plays it, as in Tauri; a click on the cover too.
                    .on_click(move |e, _, cx| {
                        if e.click_count() == 2 {
                            m1.read(cx).command(protocol::CMD_SKIP_TO, i as i64);
                        }
                    })
                    .child(
                        div()
                            .id(("rq-cover", i))
                            .relative()
                            .flex_none()
                            .cursor_pointer()
                            .on_click(move |_, _, cx| {
                                cx.stop_propagation();
                                m2.read(cx).command(protocol::CMD_SKIP_TO, i as i64);
                            })
                            .child(art(cover(&t.cover_url), 42., 10.))
                            .child(
                                div()
                                    .absolute()
                                    .inset_0()
                                    .rounded(px(10.))
                                    .bg(black().opacity(0.45))
                                    .flex()
                                    .items_center()
                                    .justify_center()
                                    .invisible()
                                    .group_hover(group.clone(), |d| d.visible())
                                    .child(icon("play", 15., white())),
                            ),
                    )
                    .child(text_col(t.title.clone(), t.artist.clone(), false))
                    .child(div().flex_none().text_size(px(12.)).text_color(s.text3).child(if t.duration_ms > 0 { clock(t.duration_ms as u64) } else { SharedString::default() }))
                    .child(
                        div()
                            .id(("rq-rm", i))
                            .size(px(24.))
                            .flex_none()
                            .rounded_full()
                            .flex()
                            .items_center()
                            .justify_center()
                            .cursor_pointer()
                            .invisible()
                            .group_hover(group, |d| d.visible())
                            .hover(|d| d.bg(s.text.opacity(0.12)))
                            .on_click(move |_, _, cx| {
                                cx.stop_propagation();
                                core.remove_from_queue(i);
                            })
                            .child(icon("close", 12., s.text2)),
                    ),
            );
        }
        out
    }

    fn devices(&self, s: &Scheme, cx: &mut Context<Self>) -> Div {
        let playing = self.model.read(cx).playing();
        let a = s.accent.hsla(1.);
        div()
            .flex()
            .flex_col()
            .child(
                lit_card(s, 18.)
                    .mt(px(6.))
                    .child(div().size(px(40.)).flex_none().rounded_full().flex().items_center().justify_center().bg(a).child(icon("desktop", 20., s.on_accent.hsla(1.))))
                    .child(
                        div()
                            .flex_1()
                            .min_w_0()
                            .flex()
                            .flex_col()
                            .gap(px(1.))
                            .child(div().text_size(px(14.)).font_weight(FontWeight::SEMIBOLD).text_color(s.text).child("Questo computer"))
                            .child(div().text_size(px(12.)).text_color(s.text2).child("In ascolto qui")),
                    )
                    .when(playing, |d| d.child(eq(s))),
            )
            .child(heading("ALTRI DISPOSITIVI".into(), s))
            .child(div().mx(px(10.)).my(px(8.)).line_height(px(20.)).text_color(s.text2).child("LFly Connect non è ancora nella versione nativa."))
    }
}

impl Render for RightPanel {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let (s, mut look) = {
            let l = self.look.read(cx);
            (l.scheme.clone(), l.glass())
        };
        if self.floating {
            // Floating over the page, it must read on its own: nearly opaque glass.
            look.tint = s.surface.hsla(0.9);
        }
        let panel = self.ui.read(cx).panel;
        if self.pill_progress() < 1. && window.is_window_active() {
            window.request_animation_frame();
        }
        let pill_at = self.pill_value();

        let ui = self.ui.clone();
        let tab = |id: &'static str, label: &'static str, p: SidePanel| {
            let on = panel == Some(p);
            let ui = ui.clone();
            div()
                .id(id)
                .flex_1()
                .min_w_0()
                .h(px(30.))
                .px(px(4.))
                .flex()
                .items_center()
                .justify_center()
                .rounded_full()
                .text_size(px(12.5))
                .font_weight(FontWeight::SEMIBOLD)
                .text_color(if on { s.text } else { s.text2 })
                .hover(|d| d.text_color(s.text))
                .cursor_pointer()
                .on_mouse_down(MouseButton::Left, |_, _, cx| cx.stop_propagation())
                .on_click(move |_, _, cx| ui.update(cx, |u, cx| u.set_panel(Some(p), cx)))
                .child(div().truncate().child(label))
        };
        let ui2 = self.ui.clone();
        let maximized = window.is_maximized();
        let drag = |d: Div| d.on_mouse_down(MouseButton::Left, |_, window, _| window.start_window_move());
        let top = div()
            .flex_none()
            .child(
                drag(div())
                    .flex()
                    .justify_end()
                    .gap(px(6.))
                    .pt(px(10.))
                    .px(px(12.))
                    .child(
                        div()
                            .flex()
                            .gap(px(6.))
                            .on_mouse_down(MouseButton::Left, |_, _, cx| cx.stop_propagation())
                            .child(crate::window_button("rp-min", "wc-min", false, s.text, s.text2).on_click(|_, w, _| w.minimize_window()))
                            .child(crate::window_button("rp-max", if maximized { "wc-restore" } else { "wc-max" }, false, s.text, s.text2).on_click(|_, w, _| w.zoom_window()))
                            .child(crate::window_button("rp-close-win", "wc-close", true, s.text, s.text2).on_click(|_, w, _| w.remove_window())),
                    ),
            )
            .child(
                drag(div())
                    .flex()
                    .items_center()
                    .gap(px(8.))
                    .pt(px(12.))
                    .px(px(12.))
                    .pb(px(8.))
                    .child(
                        div()
                            .relative()
                            .flex_1()
                            .p(px(3.))
                            .rounded_full()
                            .bg(s.text.opacity(0.07))
                            // The neutral pill under the chosen tab.
                            .child(
                                div().absolute().top(px(3.)).bottom(px(3.)).left(px(3.)).right(px(3.)).child(
                                    div()
                                        .absolute()
                                        .top_0()
                                        .bottom_0()
                                        .left(relative(pill_at / 3.))
                                        .w(relative(1. / 3.))
                                        .rounded_full()
                                        .bg(s.text.opacity(0.16))
                                        .shadow(vec![
                                            BoxShadow { color: s.text.opacity(0.18), offset: point(px(0.), px(1.)), blur_radius: px(0.), spread_radius: px(0.), inset: true },
                                            BoxShadow { color: black().opacity(0.18), offset: point(px(0.), px(1.)), blur_radius: px(4.), spread_radius: px(0.), inset: false },
                                        ]),
                                ),
                            )
                            .child(
                                div()
                                    .relative()
                                    .flex()
                                    .child(tab("rp-queue", "Coda", SidePanel::Queue))
                                    .child(tab("rp-lyrics", "Testo", SidePanel::Lyrics))
                                    .child(tab("rp-devices", "Dispositivi", SidePanel::Devices)),
                            ),
                    )
                    .child(
                        div()
                            .id("rp-close")
                            .size(px(32.))
                            .flex_none()
                            .rounded_full()
                            .flex()
                            .items_center()
                            .justify_center()
                            .bg(s.text.opacity(0.07))
                            .hover(|d| d.bg(s.text.opacity(0.14)))
                            .cursor_pointer()
                            .group("rp-close")
                            .on_mouse_down(MouseButton::Left, |_, _, cx| cx.stop_propagation())
                            .on_click(move |_, _, cx| ui2.update(cx, |u, cx| u.set_panel(None, cx)))
                            .child(icon("close", 15., s.text2).group_hover("rp-close", |d| d.text_color(s.text))),
                    ),
            );

        let body: AnyElement = match panel {
            Some(SidePanel::Lyrics) => div().flex_1().min_h_0().child(self.lyrics.clone()).into_any_element(),
            Some(SidePanel::Devices) => div().id("rp-body").flex_1().min_h_0().overflow_y_scroll().px(px(8.)).pb(px(16.)).child(self.devices(&s, cx)).into_any_element(),
            _ => div().id("rp-body").flex_1().min_h_0().overflow_y_scroll().px(px(8.)).pb(px(16.)).child(self.queue(&s, cx)).into_any_element(),
        };

        div()
            .relative()
            .size_full()
            .rounded(px(28.))
            .text_color(s.text)
            .child(glass::glass_layer(look, px(28.), glass::Pointer::default()))
            .child(div().absolute().inset_0().rounded(px(28.)).overflow_hidden().flex().flex_col().child(top).child(body))
    }
}
