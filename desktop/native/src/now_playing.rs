//! The full-window player (Tauri: pages/NowPlaying.svelte), after the phone's PlayerScreenV2: the
//! cover large over its own blurred light, the song, the progress, the transport, then the lyrics
//! or the queue beside it. It covers the whole window; the shell is not drawn under it.
//! (Still missing: the output menu's list of devices, the "more" menu.)

use std::sync::Arc;
use std::time::Instant;

use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use lfly_connect::protocol;

use crate::core::Core;
use crate::covers::Covers;
use crate::glass;
use crate::icons::icon;
use crate::library_model::LibraryModel;
use crate::look::Look;
use crate::lyrics::{Lyrics, Variant};
use crate::motion::{CubicBezier, Tween};
use crate::nav::{Nav, Route};
use crate::player_bar::clock;
use crate::player_model::PlayerModel;
use crate::queue_list::queue_list;
use crate::slider::{bar_slider, SliderBounds};

#[derive(Clone, Copy, PartialEq)]
pub enum Panel {
    Lyrics,
    Queue,
}

// Letters, digits, - and _ only (lfly_core::store).
const PANEL_KEY: &str = "native-nowplaying-panel";

/// `--lfly-ease-spring`: past the mark and back.
const SPRING: CubicBezier = CubicBezier::new(0.34, 1.56, 0.64, 1.);

pub struct NowPlaying {
    core: Arc<Core>,
    model: Entity<PlayerModel>,
    covers: Entity<Covers>,
    look: Entity<Look>,
    nav: Entity<Nav>,
    library: Entity<LibraryModel>,
    lyrics: Entity<Lyrics>,
    pub panel: Option<Panel>,
    scrubbing: Option<u64>,
    volume_draft: Option<i64>,
    seek_bounds: SliderBounds,
    volume_bounds: SliderBounds,
    /// The cover's scale: 1 playing, 0.86 paused.
    art: Tween,
    /// The play/pause glyph popping in.
    pp: Tween,
    was_playing: Option<bool>,
    ambient: Entity<crate::ambient::Ambient>,
    pub width: f32,
    pub height: f32,
    _subs: Vec<Subscription>,
}

impl NowPlaying {
    pub fn new(
        core: Arc<Core>,
        model: Entity<PlayerModel>,
        covers: Entity<Covers>,
        look: Entity<Look>,
        nav: Entity<Nav>,
        library: Entity<LibraryModel>,
        lyrics: Entity<Lyrics>,
        cx: &mut Context<Self>,
    ) -> Self {
        let subs = vec![
            cx.observe(&model, |_, _, cx| cx.notify()),
            cx.observe(&covers, |_, _, cx| cx.notify()),
            cx.observe(&look, |_, _, cx| cx.notify()),
            cx.observe(&library, |_, _, cx| cx.notify()),
        ];
        let ambient = cx.new(|_| crate::ambient::Ambient::new(core.clone(), 0.42, 80.));
        // LFLY_NP_PANEL (lyrics, queue, none) overrides the saved choice, for screenshots.
        let saved = std::env::var("LFLY_NP_PANEL").ok().map(serde_json::Value::String).or_else(|| lfly_core::store::get(PANEL_KEY).ok().flatten());
        let panel = match saved.as_ref().and_then(|v| v.as_str()) {
            Some("queue") => Some(Panel::Queue),
            Some("none") => None,
            _ => Some(Panel::Lyrics),
        };
        Self {
            core,
            model,
            covers,
            look,
            nav,
            library,
            lyrics,
            panel,
            scrubbing: None,
            volume_draft: None,
            seek_bounds: SliderBounds::default(),
            volume_bounds: SliderBounds::default(),
            art: Tween::still(1.),
            pp: Tween::still(1.),
            was_playing: None,
            ambient,
            width: 1100.,
            height: 720.,
            _subs: subs,
        }
    }

    fn set_panel(&mut self, p: Panel, cx: &mut Context<Self>) {
        self.panel = if self.panel == Some(p) { None } else { Some(p) };
        let v = match self.panel {
            Some(Panel::Lyrics) => "lyrics",
            Some(Panel::Queue) => "queue",
            None => "none",
        };
        if let Err(e) = lfly_core::store::set(PANEL_KEY, &serde_json::Value::String(v.into())) {
            log::warn!("{PANEL_KEY}: {e}");
        }
        cx.notify();
    }

    pub fn close(nav: &Entity<Nav>, cx: &mut App) {
        nav.update(cx, |n, cx| if n.can_back() { n.go_back(cx) } else { n.go(Route::Home, cx) });
    }

}

/// A 38 px round button of `.meta` (`.round`).
fn round(id: &'static str, name: &'static str, color: Hsla) -> Stateful<Div> {
    div()
        .id(id)
        .size(px(38.))
        .flex_none()
        .rounded_full()
        .flex()
        .items_center()
        .justify_center()
        .bg(white().opacity(0.12))
        .hover(|s| s.bg(white().opacity(0.2)))
        .cursor_pointer()
        .child(icon(name, 20., color))
}

/// A transport button: round, a 10% wash under the pointer.
fn transport(id: &'static str, size: f32) -> Stateful<Div> {
    div()
        .id(id)
        .relative()
        .size(px(size))
        .flex_none()
        .rounded_full()
        .flex()
        .items_center()
        .justify_center()
        .cursor_pointer()
        .hover(|s| s.bg(white().opacity(0.1)))
}

impl Render for NowPlaying {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let (w, h) = (self.width, self.height);
        let (scheme, glass_look) = {
            let l = self.look.read(cx);
            (l.scheme.clone(), l.glass().clear())
        };
        let accent = scheme.accent.hsla(1.);
        let m = self.model.read(cx);
        let track = m.current().cloned();
        let playing = m.playing();
        let duration = m.duration_ms();
        let position = self.scrubbing.unwrap_or(m.position_ms).min(duration.max(m.position_ms));
        let (shuffle, repeat, volume) =
            m.state.as_ref().map_or((false, "off".to_string(), 100), |s| (s.shuffle, s.repeat.clone(), if s.volume >= 0 { s.volume as i64 } else { 100 }));
        let volume = self.volume_draft.unwrap_or(volume);

        // The cover settles back when paused; the glyph pops when it changes.
        if self.was_playing != Some(playing) {
            if self.was_playing.is_some() {
                self.pp = Tween { from: 0., to: 1., at: Instant::now(), ms: 260. };
            } else {
                self.art = Tween::still(if playing { 1. } else { 0.86 });
            }
            self.was_playing = Some(playing);
        }
        self.art.retarget(if playing { 1. } else { 0.86 }, 700., &SPRING);
        let url = track.as_ref().and_then(|t| t.cover_url.clone());
        let bg = scheme.bg;
        self.ambient.update(cx, |a, _| {
            a.url = url;
            a.width = w;
            a.height = h;
            a.bg = bg;
        });
        let animating = self.art.running() || self.pp.running();
        if animating && window.is_window_active() {
            window.request_animation_frame();
        }

        // --- the bar: close, where it plays, the panel toggles, the window controls ----------
        let nav = self.nav.clone();
        let close = div()
            .id("np-close")
            .relative()
            .size(px(40.))
            .rounded_full()
            .cursor_pointer()
            .on_mouse_down(MouseButton::Left, |_, _, cx| cx.stop_propagation())
            .on_click(move |_, _, cx| NowPlaying::close(&nav, cx))
            .child(glass::glass_layer(glass_look, px(20.), glass::Pointer::default()))
            .child(div().relative().size_full().flex().items_center().justify_center().child(icon("g-chevron-down", 20., white())));
        let narrow = w <= 900.;
        let me = cx.entity().downgrade();
        let toggle = |id: &'static str, p: Panel, name: &'static str, label: &'static str| {
            let on = self.panel == Some(p);
            let me = me.clone();
            div()
                .id(id)
                .h(px(32.))
                .px(px(14.))
                .flex()
                .items_center()
                .gap(px(7.))
                .rounded_full()
                .text_size(px(13.))
                .font_weight(FontWeight::SEMIBOLD)
                .cursor_pointer()
                .when(on, |d| d.bg(white().opacity(0.92)).text_color(rgb(0x111111)))
                .when(!on, |d| d.text_color(white().opacity(0.75)).hover(|d| d.text_color(white())))
                .on_mouse_down(MouseButton::Left, |_, _, cx| cx.stop_propagation())
                .on_click(move |_, _, cx| {
                    let _ = me.update(cx, |this, cx| this.set_panel(p, cx));
                })
                .child(icon(name, 17., if on { rgb(0x111111).into() } else { white().opacity(0.75) }))
                .when(!narrow, |d| d.child(label))
        };
        let maximized = window.is_maximized();
        let drag = |d: Div| {
            d.on_mouse_down(MouseButton::Left, |_, window, _| window.start_window_move())
        };
        let bar = div()
            .relative()
            .flex()
            .items_center()
            .gap(px(16.))
            .pt(px(18.))
            .px(px(24.))
            .child(drag(div().flex_1().flex_basis(relative(0.)).flex().child(close)))
            .child(
                div()
                    .flex_none()
                    .flex()
                    .flex_col()
                    .items_center()
                    .gap(px(2.))
                    .min_w_0()
                    .child(div().text_size(px(11.)).font_weight(FontWeight::BOLD).text_color(white().opacity(0.65)).child("IN RIPRODUZIONE"))
                    .children(track.as_ref().and_then(|t| t.album.clone()).map(|al| {
                        div().max_w(px(w * 0.4)).text_size(px(13.)).font_weight(FontWeight::SEMIBOLD).text_color(white()).truncate().child(al)
                    })),
            )
            .child(
                drag(div())
                    .flex_1()
                    .flex_basis(relative(0.))
                    .flex()
                    .justify_end()
                    .items_center()
                    .gap(px(12.))
                    .child(
                        div()
                            .relative()
                            .rounded_full()
                            .child(glass::glass_layer(glass_look, px(20.), glass::Pointer::default()))
                            .child(
                                div()
                                    .relative()
                                    .p(px(4.))
                                    .flex()
                                    .gap(px(2.))
                                    .child(toggle("np-lyrics", Panel::Lyrics, "lyrics", "Testo"))
                                    .child(toggle("np-queue", Panel::Queue, "queue", "Coda")),
                            ),
                    )
                    .child(
                        div()
                            .flex()
                            .items_center()
                            .gap(px(6.))
                            .on_mouse_down(MouseButton::Left, |_, _, cx| cx.stop_propagation())
                            .child(crate::window_button("np-min", "wc-min", false, scheme.text, scheme.text2).on_click(|_, w, _| w.minimize_window()))
                            .child(
                                crate::window_button("np-max", if maximized { "wc-restore" } else { "wc-max" }, false, scheme.text, scheme.text2)
                                    .on_click(|_, w, _| w.zoom_window()),
                            )
                            .child(crate::window_button("np-close-win", "wc-close", true, scheme.text, scheme.text2).on_click(|_, w, _| w.remove_window())),
                    ),
            );

        let ambient = self.ambient.clone();

        let Some(t) = track else {
            let nav = self.nav.clone();
            return div()
                .size_full()
                .relative()
                .flex()
                .flex_col()
                .text_color(white())
                .child(ambient)
                .child(bar)
                .child(
                    div()
                        .relative()
                        .flex_1()
                        .flex()
                        .flex_col()
                        .items_center()
                        .justify_center()
                        .gap(px(12.))
                        .text_size(px(15.))
                        .text_color(white().opacity(0.75))
                        .child(icon("music", 40., white().opacity(0.75)))
                        .child("Niente in riproduzione.")
                        .child(
                            div()
                                .id("np-go")
                                .h(px(36.))
                                .px(px(18.))
                                .flex()
                                .items_center()
                                .rounded_full()
                                .bg(white())
                                .text_color(rgb(0x111111))
                                .font_weight(FontWeight::SEMIBOLD)
                                .cursor_pointer()
                                .on_click(move |_, _, cx| nav.update(cx, |n, cx| n.go(Route::Search, cx)))
                                .child("Cerca qualcosa da ascoltare"),
                        ),
                )
                .into_any_element();
        };

        // --- the stage: the player, the panel beside it -------------------------------------
        let with_panel = self.panel.is_some();
        let short = h <= 640.;
        let player_w = match (with_panel, short) {
            (false, false) => (0.54 * h).min(460.),
            (true, false) => (0.50 * h).min(420.),
            (false, true) => (0.46 * h).min(360.),
            (true, true) => (0.42 * h).min(340.),
        };
        let pad_x = (0.05 * w).clamp(24., 80.);
        let gap = (0.05 * w).clamp(32., 96.);
        let free = w - 2. * pad_x - if with_panel && !narrow { gap } else { 0. };
        // grid-template-columns: minmax(320px, 0.9fr) minmax(0, 1.1fr).
        let col1 = if with_panel && !narrow { (free * 0.45).max(320.).min(free) } else { free };
        let col2 = (free - col1).max(0.);
        let player_w = player_w.min(col1);

        let liked = self.library.read(cx).is_favorite(&t.id);
        let cover = t.cover_url.as_ref().and_then(|u| self.covers.update(cx, |c, cx| c.get(u, cx)));
        let scale = self.art.value(&SPRING);
        // The shadow follows the scale: 0 30 80 / .55 playing, 0 14 36 / .4 paused.
        let k = ((1. - scale) / 0.14).clamp(0., 1.);
        let lerp = |a: f32, b: f32| a + (b - a) * k;
        let art_w = player_w * scale;
        let art = div()
            .size(px(player_w))
            .mb(px(10.))
            .flex()
            .items_center()
            .justify_center()
            .child(
                div()
                    .size(px(art_w))
                    .flex_none()
                    .rounded(px(14.))
                    .bg(white().opacity(0.08))
                    .shadow(vec![
                        BoxShadow { color: black().opacity(lerp(0.55, 0.4)), offset: point(px(0.), px(lerp(30., 14.))), blur_radius: px(lerp(80., 36.)), spread_radius: px(0.), inset: false },
                        BoxShadow { color: white().opacity(0.06 * (1. - k)), offset: point(px(0.), px(0.)), blur_radius: px(0.), spread_radius: px(1.), inset: false },
                    ])
                    .children(cover.map(|i| img(i).size_full().object_fit(ObjectFit::Cover).rounded(px(14.)))),
            );

        let (nav1, nav2) = (self.nav.clone(), self.nav.clone());
        let (album_id, album) = (t.album_id.clone(), t.album.clone().unwrap_or_default());
        let (artist_id, artist) = (t.artist_id.clone(), t.artist.clone());
        let library = self.library.clone();
        let core = self.core.clone();
        let heart_track = serde_json::to_value(&t).ok();
        let meta = div()
            .flex()
            .items_center()
            .gap(px(6.))
            .child(
                div()
                    .flex_1()
                    .min_w_0()
                    .flex()
                    .flex_col()
                    .items_start()
                    .child(
                        div()
                            .id("np-title")
                            .max_w_full()
                            .text_size(px(22.))
                            .line_height(px(26.4))
                            .font_weight(FontWeight(750.))
                            .truncate()
                            .cursor_pointer()
                            .hover(|d| d.underline())
                            .on_click(move |_, _, cx| {
                                if let Some(id) = album_id.clone() {
                                    nav1.update(cx, |n, cx| n.go(Route::Album { id, name: album.clone() }, cx));
                                }
                            })
                            .child(t.title.clone()),
                    )
                    .child(
                        div()
                            .id("np-artist")
                            .max_w_full()
                            .text_size(px(17.))
                            .text_color(white().opacity(0.68))
                            .truncate()
                            .cursor_pointer()
                            .hover(|d| d.underline())
                            .on_click(move |_, _, cx| {
                                if let Some(id) = artist_id.clone() {
                                    nav2.update(cx, |n, cx| n.go(Route::Artist { id, name: artist.clone() }, cx));
                                }
                            })
                            .child(t.artist.clone()),
                    ),
            )
            .child(
                round("np-like", if liked { "heart-fill" } else { "heart" }, if liked { accent } else { white().opacity(0.85) }).on_click(move |_, _, cx| {
                    let Some(v) = heart_track.clone() else { return };
                    let id = t_id(&v);
                    let now = library.update(cx, |l, cx| l.toggle_favorite(v, cx));
                    if let (Some(now), Some(id)) = (now, id) {
                        core.rate(id, now.then_some(true));
                    }
                }),
            )
            .child(round("np-more", "more", white().opacity(0.85)).on_click({
                let t = t.clone();
                move |e, _, cx| crate::menu::Menu::open_track(e.position(), t.clone(), None, cx)
            }));

        let me = cx.entity().downgrade();
        let (me1, me2, me3, me4) = (me.clone(), me.clone(), me.clone(), me.clone());
        let model = self.model.clone();
        let (model1, model2, model3) = (model.clone(), model.clone(), model.clone());
        let seek = div()
            .flex()
            .flex_col()
            .child(bar_slider(
                "np-seek",
                if duration > 0 { position as f32 / duration as f32 } else { 0. },
                duration == 0,
                white(),
                self.seek_bounds.clone(),
                move |f, _, cx| {
                    let _ = me1.update(cx, |this, cx| {
                        this.scrubbing = Some((f as f64 * duration as f64) as u64);
                        cx.notify();
                    });
                },
                move |f, _, cx| {
                    let to = (f as f64 * duration as f64) as i64;
                    model1.read(cx).command(protocol::CMD_SEEK, to);
                    let _ = me2.update(cx, |this, cx| {
                        this.scrubbing = None;
                        cx.notify();
                    });
                },
            ))
            .child(
                div()
                    .mt(px(2.))
                    .flex()
                    .justify_between()
                    .text_size(px(11.5))
                    .font_weight(FontWeight::SEMIBOLD)
                    .text_color(white().opacity(0.6))
                    .child(clock(position))
                    .child(format!("-{}", clock(duration.saturating_sub(position)))),
            );

        let cmd = move |action: &'static str, value: i64| {
            let model = model.clone();
            move |_: &ClickEvent, _: &mut Window, cx: &mut App| model.read(cx).command(action, value)
        };
        let next_repeat = match repeat.as_str() {
            "off" => protocol::REPEAT_ALL,
            "all" => protocol::REPEAT_ONE,
            _ => protocol::REPEAT_OFF,
        };
        let small = |id: &'static str, name: &'static str, on: bool| {
            let fg = if on { accent } else { white().opacity(0.6) };
            transport(id, 40.)
                .child(icon(name, 20., fg))
                .when(on, |d| d.child(div().absolute().bottom(px(3.)).left(relative(0.5)).ml(px(-2.)).size(px(4.)).rounded_full().bg(fg)))
        };
        let pp = self.pp.value(&SPRING).clamp(0., 1.2);
        let controls = div()
            .mx(px(-6.))
            .my(px(2.))
            .flex()
            .items_center()
            .justify_between()
            .child(small("np-shuffle", "shuffle", shuffle).on_click(cmd(protocol::CMD_SHUFFLE, if shuffle { 0 } else { 1 })))
            .child(transport("np-prev", 60.).child(icon("previous", 34., white())).on_click(cmd(protocol::CMD_PREVIOUS, 0)))
            .child(
                transport("np-play", 76.)
                    .child(
                        div()
                            .size(px(44.))
                            .flex()
                            .items_center()
                            .justify_center()
                            .opacity(pp.min(1.))
                            .child(icon(if playing { "pause" } else { "play" }, 44. * (0.6 + 0.4 * pp), white())),
                    )
                    .on_click(cmd(protocol::CMD_TOGGLE, 0)),
            )
            .child(transport("np-next", 60.).child(icon("next", 34., white())).on_click(cmd(protocol::CMD_NEXT, 0)))
            .child(
                small("np-repeat", "repeat", repeat != "off")
                    .when(repeat == "one", |d| {
                        d.child(div().absolute().top(px(6.)).right(px(6.)).text_size(px(9.)).font_weight(FontWeight::EXTRA_BOLD).text_color(accent).child("1"))
                    })
                    .on_click(cmd(protocol::CMD_REPEAT, next_repeat)),
            );

        let set_volume = move |v: i64, cx: &mut App| model2.read(cx).command(protocol::CMD_VOLUME, v);
        let vol_ic = |id: &'static str, name: &'static str, to: i64| {
            let model = model3.clone();
            div()
                .id(id)
                .flex_none()
                .cursor_pointer()
                .group(id)
                .on_click(move |_, _, cx| model.read(cx).command(protocol::CMD_VOLUME, to))
                .child(icon(name, 16., white().opacity(0.6)).group_hover(id, |s| s.text_color(white())))
        };
        let volume_row = div()
            .flex()
            .items_center()
            .gap(px(10.))
            .child(vol_ic("np-vol-low", "g-volume-low", 0))
            .child(
                div().flex_1().child(bar_slider(
                    "np-volume",
                    volume as f32 / 100.,
                    false,
                    white().opacity(0.9),
                    self.volume_bounds.clone(),
                    move |f, _, cx| {
                        let _ = me3.update(cx, |this, cx| {
                            this.volume_draft = Some((f * 100.).round() as i64);
                            cx.notify();
                        });
                    },
                    move |f, _, cx| {
                        set_volume((f * 100.).round() as i64, cx);
                        let _ = me4.update(cx, |this, cx| {
                            this.volume_draft = None;
                            cx.notify();
                        });
                    },
                )),
            )
            .child(vol_ic("np-vol-high", "g-volume-high", 100));

        // OutputMenu's trigger. The device list waits for the audio-output port.
        let device = div().flex().justify_center().child(
            div()
                .id("np-output")
                .h(px(32.))
                .px(px(12.))
                .flex()
                .items_center()
                .gap(px(8.))
                .rounded_full()
                .text_size(px(13.))
                .font_weight(FontWeight::SEMIBOLD)
                .text_color(white().opacity(0.75))
                .hover(|d| d.bg(white().opacity(0.12)).text_color(white()))
                .group("np-output")
                .child(icon("g-output", 17., white().opacity(0.75)).group_hover("np-output", |s| s.text_color(white())))
                .child("Questo computer"),
        );

        let player = div()
            .w(px(player_w))
            .flex_none()
            .flex()
            .flex_col()
            .gap(px(14.))
            .child(art)
            .child(meta)
            .child(seek)
            .child(controls)
            .child(volume_row)
            .child(device);

        let side = self.panel.map(|p| {
            let side_w = if narrow { free.min(720.) } else { col2.min(720.) };
            match p {
                Panel::Lyrics => {
                    self.lyrics.update(cx, |l, _| {
                        l.variant = Variant::Panel;
                        l.window_width = w;
                    });
                    div().w(px(side_w)).h_full().px(px(8.)).child(self.lyrics.clone()).into_any_element()
                }
                Panel::Queue => {
                    let q = queue_list(&self.model, &self.covers, &self.core, accent, cx);
                    div()
                        .id("np-queue-scroll")
                        .w(px(side_w))
                        .h_full()
                        .overflow_y_scroll()
                        .child(div().max_w(px(520.)).pt(px(h * 0.1)).pb(px(h * 0.2)).px(px(8.)).child(q))
                        .into_any_element()
                }
            }
        });

        // The bar takes 18 + 40; the stage's padding 8 above, 28 below.
        let stage_h = (h - 58. - 36.).max(100.);
        let mut stage = div().relative().h(px(stage_h + 36.)).flex_none().pt(px(8.)).pb(px(28.)).px(px(pad_x)).flex().gap(px(gap));
        stage = match side {
            None => stage.justify_center().child(div().h(px(stage_h)).flex().items_center().child(player)),
            Some(side) if narrow => stage.justify_center().child(div().h(px(stage_h)).child(side)),
            Some(side) => stage
                .child(div().w(px(col1)).h(px(stage_h)).flex_none().flex().justify_end().items_center().child(player))
                .child(div().w(px(col2)).h(px(stage_h)).flex_none().child(side)),
        };

        div()
            .size_full()
            .relative()
            .flex()
            .flex_col()
            .overflow_hidden()
            .text_color(white())
            .child(ambient)
            .child(bar)
            .child(stage)
            .into_any_element()
    }
}

fn t_id(v: &serde_json::Value) -> Option<String> {
    v.get("id").and_then(|i| i.as_str()).map(str::to_string)
}
