//! The full-window player (Tauri: pages/NowPlaying.svelte), after the phone's PlayerScreenV2: the
//! cover large over its own blurred light, the song, the progress, the transport, then the lyrics
//! or the queue beside it. It covers the whole window; the shell is not drawn under it.
//! The output menu lists this computer's outputs and the LFly Connect devices.

use std::sync::Arc;
use std::time::Instant;

use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;

use crate::i18n::{tr, trf};
use lfly_connect::protocol;

use crate::core::Core;
use crate::covers::Covers;
use crate::glass;
use crate::icons::icon;
use crate::library_model::LibraryModel;
use crate::look::Look;
use crate::lyrics::{Lyrics, Variant};
use crate::spicy::{Curve, Spring};
use crate::nav::{Nav, Route};
use crate::player_bar::clock;
use crate::player_model::PlayerModel;
use crate::queue_list::queue_list;
use crate::slider::{bar_slider, SliderBounds};
use crate::theme::Rgb;

#[derive(Clone, Copy, PartialEq)]
pub enum Panel {
    Lyrics,
    Queue,
}

// Letters, digits, - and _ only (lfly_core::store).
const PANEL_KEY: &str = "native-nowplaying-panel";

/// Spicy Lyrics' `pressAnimation` (0.6 s): down to 0.9, past 1 and settling.
const PRESS: Curve = Curve(&[(0., 0.9), (0.16, 1.032), (0.28, 0.987), (0.44, 1.005), (0.59, 0.998), (0.73, 1.001), (0.88, 1.), (1., 1.)]);

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
    /// Spicy Lyrics' Fullscreen: the controls on the cover fade in with the pointer (0.65 over
    /// the page, 0.985 over the cover) as the cover dims (0.78, 0.55); 750 ms still, both go.
    controls: Spring,
    bright: Spring,
    moved_at: Option<Instant>,
    over_art: bool,
    frame_at: Instant,
    idle: Option<Task<()>>,
    /// The control last pressed, and when: its 0.6 s press bounce.
    press: Option<(&'static str, Instant)>,
    /// OutputMenu.svelte `open`.
    output_open: bool,
    /// When the menu opened: OutputMenu.svelte's `pop` (240 ms, up 8 px, fading in).
    output_at: Instant,
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
        if std::env::var_os("LFLY_NP_OUTPUT").is_some() {
            crate::audio::AudioModel::entity(cx).update(cx, |a, cx| a.load_devices(cx));
        }
        let subs = vec![
            cx.observe(&model, |_, _, cx| cx.notify()),
            cx.observe(&crate::audio::AudioModel::entity(cx), |_, _, cx| cx.notify()),
            cx.observe(&crate::connect::ConnectModel::entity(cx), |_, _, cx| cx.notify()),
            cx.observe(&covers, |_, _, cx| cx.notify()),
            cx.observe(&look, |_, _, cx| cx.notify()),
            cx.observe(&library, |_, _, cx| cx.notify()),
            // Left (Esc, Back): no menu, hover or drag carried over to the next time it opens.
            cx.observe(&nav, |this, nav, cx| {
                if nav.read(cx).route != Route::NowPlaying {
                    (this.output_open, this.over_art, this.scrubbing, this.volume_draft) = (false, false, None, None);
                }
            }),
        ];
        // Spicy Lyrics' background, as the lyrics page has it: the cover saturated ×2.5, darker.
        let ambient = cx.new(|_| {
            let mut a = crate::ambient::Ambient::new(core.clone(), 0.55, 70.);
            a.saturation = 2.5;
            a
        });
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
            controls: Spring::new(0., 2., 2.),
            bright: Spring::new(1., 2., 2.),
            moved_at: None,
            over_art: false,
            frame_at: Instant::now(),
            idle: None,
            press: None,
            output_open: std::env::var_os("LFLY_NP_OUTPUT").is_some(),
            output_at: Instant::now(),
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

    /// The pointer moved over the page: the controls show, and go 750 ms after it stops.
    /// Redraws only when the goal changes (not on every move: the lyrics are drawn with it), and
    /// one timer, pushed back while the pointer moves, hides the controls.
    fn moved(&mut self, cx: &mut Context<Self>) {
        let before = self.goals();
        self.moved_at = Some(Instant::now());
        if self.goals() != before {
            cx.notify();
        }
        if self.idle.is_some() {
            return;
        }
        self.idle = Some(cx.spawn(async move |this, cx| {
            loop {
                let left = this.update(cx, |t, _| 760u64.saturating_sub(t.moved_at.map_or(760, |m| m.elapsed().as_millis() as u64))).unwrap_or(0);
                if left == 0 {
                    break;
                }
                cx.background_executor().timer(std::time::Duration::from_millis(left)).await;
            }
            let _ = this.update(cx, |t, cx| {
                t.idle = None;
                cx.notify();
            });
        }));
    }

    /// Fullscreen.ts ToggleControls' goals: (controls' opacity, the cover's brightness).
    fn goals(&self) -> (f32, f32) {
        let recent = self.moved_at.is_some_and(|t| t.elapsed().as_millis() < 750);
        if self.over_art {
            (0.985, 0.55)
        } else if recent {
            (0.65, 0.78)
        } else {
            (0., 1.)
        }
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
        .hover(|s| s.bg(white().opacity(0.2))).active(|s| s.bg(white().opacity(0.30)))
        .cursor_pointer()
        .child(icon(name, 20., color))
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
        let mix = m.mix_span().map(|(a, b)| (a, b, accent));
        let track = m.current().cloned();
        let playing = m.playing();
        let duration = m.duration_ms();
        let position = self.scrubbing.unwrap_or(m.position_ms).min(duration.max(m.position_ms));
        let (shuffle, repeat, volume) =
            m.state.as_ref().map_or((false, "off".to_string(), 100), |s| (s.shuffle, s.repeat.clone(), if s.volume >= 0 { s.volume as i64 } else { 100 }));
        let volume = self.volume_draft.unwrap_or(volume);

        let prefs = crate::prefs::Prefs::get(cx);
        let reduce = crate::prefs::Prefs::reduce_motion(cx);
        // The pointer can be "over" the cover only while the cover is drawn (GPUI forgets the hover
        // of what it stops painting, and no leave comes).
        if self.nav.read(cx).route != Route::NowPlaying || track.is_none() || (w <= 900. && self.panel.is_some()) {
            self.over_art = false;
        }
        // Still springs start from this frame, not from the idle gap before it.
        let was_moving = self.controls.moving() || self.bright.moving();
        let (goal_c, goal_b) = self.goals();
        self.controls.goal = goal_c;
        self.bright.goal = goal_b;
        let dt = if was_moving { self.frame_at.elapsed().as_secs_f32().min(0.1) } else { 0. };
        self.frame_at = Instant::now();
        if reduce {
            (self.controls.pos, self.bright.pos) = (goal_c, goal_b);
        }
        let (fade, bright) = (self.controls.step(dt), self.bright.step(dt));
        let pressed = self.press.filter(|(_, at)| !reduce && at.elapsed().as_secs_f32() < 0.6);
        let url = track.as_ref().and_then(|t| t.cover_url.clone());
        // The player's ink is white: a dark base under it even in the light looks (no cover yet).
        let bg = if scheme.light { crate::theme::Rgb::hex(0x101014) } else { scheme.bg };
        self.ambient.update(cx, |a, _| {
            a.moving = playing && prefs.np_animated_bg;
            a.url = url;
            a.width = w;
            a.height = h;
            a.bg = bg;
        });
        if (self.controls.moving() || self.bright.moving() || pressed.is_some()) && crate::presented(window) {
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
            // Clear of the traffic lights (three 14 px lights over 52 px, then a gap).
            .when(crate::SYSTEM_WINDOW_BUTTONS, |d| d.pl(px(crate::MAC_TRAFFIC_LIGHTS.0 + 64.)))
            .child(drag(div().flex_1().flex_basis(relative(0.)).flex().child(close)))
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
                                    .child(toggle("np-lyrics", Panel::Lyrics, "lyrics", tr("Testo")))
                                    .child(toggle("np-queue", Panel::Queue, "queue", tr("Coda"))),
                            ),
                    )
                    .when(!crate::SYSTEM_WINDOW_BUTTONS, |d| d.child(
                        div()
                            .flex()
                            .items_center()
                            .gap(px(6.))
                            .on_mouse_down(MouseButton::Left, |_, _, cx| cx.stop_propagation())
                            .child(crate::window_button("np-min", "wc-min", false, white(), white().opacity(0.7)).on_click(|_, w, _| w.minimize_window()))
                            .child(
                                crate::window_button("np-max", if maximized { "wc-restore" } else { "wc-max" }, false, white(), white().opacity(0.7))
                                    .on_click(|_, w, _| w.zoom_window()),
                            )
                            .child(crate::window_button("np-close-win", "wc-close", true, white(), white().opacity(0.7)).on_click(|_, w, _| w.remove_window())),
                    )),
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
                        .child(tr("Niente in riproduzione."))
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
                                .child(tr("Cerca qualcosa da ascoltare")),
                        ),
                )
                .into_any_element();
        };

        // --- Spicy Lyrics' Fullscreen: the NowBar (cover, song, timeline) left of the lyrics -----
        let with_panel = self.panel.is_some();
        // --NowBarWidth: min(30cqw, 52cqh) × 1.1, a quarter of it kept clear before the middle.
        let nb = (0.30 * w).min(0.52 * h) * 1.1;
        let right_gap = nb / 4.;
        let left_gap = if with_panel { w / 2. - nb - right_gap } else { (w - nb) / 2. };
        let outside = prefs.np_timeline_outside;
        let nb_top = h / 2. - nb * if outside { 0.625 } else { 0.6 };
        // --default-font-size: 3cqw of the NowBar, 8 to 38.4 px.
        let font = (0.03 * nb).clamp(8., 38.4);

        let liked = self.library.read(cx).is_favorite(&t.id);
        // The cover large (1200 px where YouTube has it, the 544 px one until it lands). A video's
        // thumbnail is 16:9: shown whole in a 16:9 frame, never cropped to a square.
        let wide = t.cover_url.as_deref().is_some_and(|u| u.contains("ytimg.com"));
        // Both are asked for: the small one stays under the large one while that fades in.
        let (cover, big) = t.cover_url.as_ref().map_or((None, None), |u| {
            self.covers.update(cx, |c, cx| {
                let big = u.contains("=w544-h544").then(|| u.replace("=w544-h544", "=w1200-h1200")).and_then(|b| c.get(&b, cx));
                (c.get(u, cx), big)
            })
        });
        let ratio = if wide { 9. / 16. } else { 1. };
        // A 16:9 cover is shorter than the square the NowBar is laid out for: centred in it.
        let nb_top = nb_top + nb * (1. - ratio) / 2.;
        let radius = px(0.02 * nb);
        let image = div()
            .w(px(nb))
            .h(px(nb * ratio))
            .rounded(radius)
            .bg(white().opacity(0.08))
            .opacity(0.95)
            .shadow(vec![BoxShadow { color: black().opacity(0.271), offset: point(px(0.), px(9.)), blur_radius: px(20.), spread_radius: px(0.), inset: false }])
            .relative()
            .children(cover.map(|i| {
                let el = img(i.clone()).size_full().object_fit(ObjectFit::Cover).rounded(radius);
                // Under a large one: simply there (it fades in over it).
                let el = if big.is_some() { el.into_any_element() } else { crate::covers::fade(i.id(), el) };
                div().absolute().inset_0().child(el)
            }))
            .children(big.map(|i| div().absolute().inset_0().child(crate::covers::fade(i.id(), img(i).size_full().object_fit(ObjectFit::Cover).rounded(radius)))))
            // brightness(var(--ArtworkBrightness)).
            .child(div().absolute().inset_0().rounded(radius).bg(black().opacity(1. - bright)));

        let (nav1, nav2) = (self.nav.clone(), self.nav.clone());
        let (album_id, album) = (t.album_id.clone(), t.album.clone().unwrap_or_default());
        let (artist_id, artist) = (t.artist_id.clone(), t.artist.clone());
        let library = self.library.clone();
        let core = self.core.clone();
        let heart_track = serde_json::to_value(&t).ok();
        // .Metadata: 10cqh tall, the title 900 at 2.25×, the artists at 1.5× and 70%, centred.
        let meta_h = 0.1 * h;
        let meta = div()
            .w(px(nb))
            .h(px(meta_h))
            .flex()
            .flex_col()
            .items_center()
            .justify_center()
            .child(
                div()
                    .id("np-title")
                    .max_w(px(0.85 * nb))
                    .text_size(px(font * 2.25))
                    .line_height(px(0.504 * meta_h))
                    .font_weight(FontWeight::BLACK)
                    .text_color(white().opacity(0.95))
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
                    .max_w(px(0.8 * nb))
                    .text_size(px(font * 1.5))
                    .line_height(px(0.336 * meta_h))
                    .text_color(white().opacity(0.7))
                    .truncate()
                    .cursor_pointer()
                    .hover(|d| d.underline())
                    .on_click(move |_, _, cx| {
                        if let Some(id) = artist_id.clone() {
                            nav2.update(cx, |n, cx| n.go(Route::Artist { id, name: artist.clone() }, cx));
                        }
                    })
                    .child(t.artist.clone()),
            );

        let me = cx.entity().downgrade();
        let (me1, me2, me3, me4) = (me.clone(), me.clone(), me.clone(), me.clone());
        let model = self.model.clone();
        let (model1, model2) = (model.clone(), model.clone());
        // .Timeline: the time gone, the bar, the time left; padding 4.5cqw 1cqw 3cqw.
        let time = |s: SharedString| div().min_w(px(font * 3.)).flex_none().text_size(px(font)).font_weight(FontWeight::MEDIUM).font_features(crate::ui::tnum()).text_color(white().opacity(0.7)).child(s);
        let seek = div()
            .w(px(nb))
            .flex()
            .items_center()
            .gap(px(0.006 * nb))
            .child(time(clock(position)).flex().justify_end())
            .child(div().flex_1().mx(px(0.015 * nb)).child(bar_slider(
                "np-seek",
                if duration > 0 { position as f32 / duration as f32 } else { 0. },
                duration == 0,
                white(),
                mix,
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
            )))
            .child(time(clock(duration)));

        let next_repeat = match repeat.as_str() {
            "off" => protocol::REPEAT_ALL,
            "all" => protocol::REPEAT_ONE,
            _ => protocol::REPEAT_OFF,
        };
        // .PlaybackControls: 10cqh tall, 20cqh in from each side; the toggles 7cqw wide, the skips
        // 12, play 9.25. The others dim to 50% under the pointer; a press bounces (pressAnimation).
        let control = |id: &'static str, name: &'static str, width: f32, glyph: f32, color: Hsla, action: &'static str, value: i64| {
            let model = model.clone();
            let me = me.clone();
            let scale = pressed.filter(|(p, _)| *p == id).map_or(1., |(_, at)| PRESS.at(at.elapsed().as_secs_f32() / 0.6));
            div()
                .id(id)
                .w(px(width * nb))
                .h_full()
                .flex()
                .items_center()
                .justify_center()
                .cursor_pointer()
                .group_hover("np-pc", |s| s.opacity(0.5))
                .hover(|s| s.opacity(1.))
                .on_mouse_down(MouseButton::Left, move |_, _, cx| {
                    cx.stop_propagation();
                    let _ = me.update(cx, |this, cx| {
                        this.press = Some((id, Instant::now()));
                        cx.notify();
                    });
                })
                .on_click(move |_, _, cx| model.read(cx).command(action, value))
                .child(icon(name, glyph * nb * scale, color))
        };
        let toggle_color = |on: bool| if on { accent } else { white() };
        let controls = div()
            .group("np-pc")
            .w(px(nb))
            .h(px(0.1 * nb))
            .px(px(0.2 * nb))
            .flex()
            .items_center()
            .justify_between()
            .child(control("np-shuffle", "shuffle", 0.07, 0.045, toggle_color(shuffle), protocol::CMD_SHUFFLE, if shuffle { 0 } else { 1 }))
            .child(control("np-prev", "previous", 0.12, 0.07, white(), protocol::CMD_PREVIOUS, 0))
            .child(control("np-play", if playing { "pause" } else { "play" }, 0.0925, 0.085, white(), protocol::CMD_TOGGLE, 0))
            .child(control("np-next", "next", 0.12, 0.07, white(), protocol::CMD_NEXT, 0))
            .child(
                control("np-repeat", "repeat", 0.07, 0.045, toggle_color(repeat != "off"), protocol::CMD_REPEAT, next_repeat)
                    .relative()
                    .when(repeat == "one", |d| d.child(div().absolute().top_0().right_0().text_size(px(0.022 * nb)).font_weight(FontWeight::EXTRA_BOLD).text_color(accent).child("1"))),
            );

        // .VolumeControl: a capsule 4cqw by 32cqh, 4.5cqw in from the cover's right edge.
        let set_volume = move |v: i64, cx: &mut App| model2.read(cx).command(protocol::CMD_VOLUME, v);
        let volume_capsule = prefs.np_volume.then(|| {
            div().absolute().right(px(0.045 * nb)).top(px(nb * ratio * 0.5 - 0.16 * nb)).child(crate::slider::vcapsule(
                "np-volume",
                volume as f32 / 100.,
                0.04 * nb,
                0.32 * nb,
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
            ))
        });

        // OutputMenu.svelte: the output in use; a click lists this computer's devices.
        let audio = crate::audio::AudioModel::entity(cx);
        let (device_id, devices) = {
            let a = audio.read(cx);
            (a.v.device.clone(), a.devices.clone())
        };
        let local_name = devices
            .iter()
            .find(|d| Some(&d.id) == device_id.as_ref())
            .or_else(|| devices.iter().find(|d| d.is_default))
            .map_or(tr("Questo computer").to_string(), |d| d.name.clone());
        let open = self.output_open;
        let pop = if crate::prefs::Prefs::reduce_motion(cx) { 1. } else { (self.output_at.elapsed().as_secs_f32() / 0.24).min(1.) };
        if open && pop < 1. {
            window.request_animation_frame();
        }
        // ease-out: fast, then settling.
        let pop = 1. - (1. - pop).powi(3);
        let me_out = cx.entity().downgrade();
        // LFly Connect: the device this computer controls, the others on the network.
        let connect = crate::connect::ConnectModel::entity(cx);
        let (following, connecting, others) = {
            let c = connect.read(cx);
            (c.following.clone(), c.connecting.clone(), c.devices.clone())
        };
        let remote = following.is_some();
        let menu = open.then(|| {
            let empty = devices.is_empty();
            let on_accent = scheme.on_accent.hsla(1.);
            let close = {
                let me = me_out.clone();
                move |cx: &mut App| {
                    let _ = me.update(cx, |this, cx| {
                        this.output_open = false;
                        cx.notify();
                    });
                }
            };
            // `.opt`: a 32 px disc with the icon (accent when chosen), the name, a tick.
            let opt = |id: SharedString, ic: &'static str, name: String, on: bool, tail: Option<&'static str>, quiet: bool, act: Option<std::rc::Rc<dyn Fn(&mut App)>>| {
                let close = close.clone();
                div()
                    .id(id)
                    .flex()
                    .items_center()
                    .gap(px(12.))
                    .py(px(9.))
                    .px(px(10.))
                    .rounded(px(12.))
                    .text_size(px(if quiet { 13. } else { 14. }))
                    .text_color(if quiet { white().opacity(0.65) } else { white() })
                    .when_some(act, |d, act| {
                        d.cursor_pointer().hover(|d| d.bg(white().opacity(0.1))).active(|d| d.bg(white().opacity(0.18))).on_click(move |_, _, cx| {
                            act(cx);
                            close(cx);
                        })
                    })
                    .child(
                        div()
                            .size(px(32.))
                            .flex_none()
                            .rounded_full()
                            .flex()
                            .items_center()
                            .justify_center()
                            .bg(if on { accent } else { white().opacity(0.1) })
                            .child(icon(ic, if quiet { 16. } else { 18. }, if on { on_accent } else { white() })),
                    )
                    .child(div().flex_1().min_w_0().truncate().when(on, |d| d.font_weight(FontWeight::SEMIBOLD)).child(name))
                    .when_some(tail, |d, t| d.child(div().text_size(px(12.)).text_color(white().opacity(0.6)).child(t)))
                    .when(on && tail.is_none(), |d| d.child(icon("check", 16., accent)))
            };
            let sec = |t: String| div().mt(px(8.)).mx(px(10.)).mb(px(4.)).text_size(px(11.)).font_weight(FontWeight::BOLD).text_color(white().opacity(0.5)).child(t);
            let mut items: Vec<AnyElement> = vec![sec(tr("Questo computer").to_uppercase()).into_any_element()];
            if remote {
                let c = connect.clone();
                items.push(opt("np-here".into(), "desktop", tr("Ascolta qui").into(), false, None, false, Some(std::rc::Rc::new(move |cx: &mut App| c.update(cx, |c, cx| c.play_here(cx))))).into_any_element());
            }
            if empty {
                if !remote {
                    items.push(opt("np-out-0".into(), "desktop", tr("Questo computer").into(), true, None, false, None).into_any_element());
                }
            } else {
                for (k, d) in devices.iter().enumerate() {
                    let on = !remote && if device_id.is_some() { device_id.as_deref() == Some(d.id.as_str()) } else { d.is_default };
                    let id = if d.is_default { None } else { Some(d.id.clone()) };
                    let au = audio.clone();
                    items.push(opt(format!("np-out-{k}").into(), "volume", d.name.clone(), on, None, false, Some(std::rc::Rc::new(move |cx: &mut App| au.update(cx, |a, cx| a.set_device(id.clone(), cx))))).into_any_element());
                }
            }
            if !others.is_empty() {
                items.push(sec("LFLY CONNECT".into()).into_any_element());
                for (k, d) in others.iter().enumerate() {
                    let on = following.as_ref().is_some_and(|f| f.id == d.id);
                    let busy = connecting.as_deref() == Some(d.id.as_str());
                    let (c, dev) = (connect.clone(), d.clone());
                    let act: Option<std::rc::Rc<dyn Fn(&mut App)>> = (!on).then(|| std::rc::Rc::new(move |cx: &mut App| c.update(cx, |c, cx| c.follow(dev.clone(), cx))) as std::rc::Rc<dyn Fn(&mut App)>);
                    items.push(opt(format!("np-cd-{k}").into(), crate::connect::kind_icon(&d.kind), d.name.clone(), on, busy.then_some(tr("Collego…")), false, act).into_any_element());
                }
            }
            if let Some(f) = &following {
                let c = connect.clone();
                items.push(opt("np-stop".into(), "close", trf("Smetti di controllare {0}", &[&f.name]), false, None, true, Some(std::rc::Rc::new(move |cx: &mut App| c.update(cx, |c, cx| c.disconnect(cx))))).into_any_element());
            }
            let mut mg = glass_look;
            mg.tint = Rgb::hex(0x1c1c20).hsla(0.72);
            deferred(
                div()
                    .absolute()
                    .bottom(px(32. + 10. - 8. * (1. - pop)))
                    .left(px(-150. + 0.))
                    .w(px(300.))
                    .opacity(pop)
                    .max_h(px(self.height * 0.6))
                    .rounded(px(22.))
                    .occlude()
                    .shadow(vec![BoxShadow { color: black().opacity(0.5), offset: point(px(0.), px(24.)), blur_radius: px(60.), spread_radius: px(0.), inset: false }])
                    .child(glass::glass_layer(mg, px(22.), glass::Pointer::default()))
                    .child(div().id("np-out-list").relative().max_h(px(self.height * 0.6)).overflow_y_scroll().p(px(8.)).flex().flex_col().children(items)),
            )
            .with_priority(2)
        });
        let audio2 = audio.clone();
        let me_t = cx.entity().downgrade();
        let device = div().flex().justify_center().child(
            div()
                .relative()
                .child(
                    div()
                        .id("np-output")
                        .h(px(32.))
                        .max_w(px(260.))
                        .px(px(12.))
                        .flex()
                        .items_center()
                        .gap(px(8.))
                        .rounded_full()
                        .text_size(px(13.))
                        .font_weight(FontWeight::SEMIBOLD)
                        .text_color(if remote { accent } else if open { white() } else { white().opacity(0.75) })
                        .when(open, |d| d.bg(white().opacity(0.12)))
                        .hover(|d| d.bg(white().opacity(0.12)).text_color(white()))
                        .cursor_pointer()
                        .group("np-output")
                        .on_click(move |_, _, cx| {
                            audio2.update(cx, |a, cx| a.load_devices(cx));
                            let _ = me_t.update(cx, |this, cx| {
                                this.output_open = !this.output_open;
                                this.output_at = Instant::now();
                                cx.notify();
                            });
                        })
                        .child(icon("g-output", 17., if remote { accent } else { white().opacity(0.75) }).group_hover("np-output", |s| s.text_color(white())))
                        .child(div().truncate().child(following.as_ref().map_or(local_name, |f| f.name.clone()))),
                )
                .child(div().absolute().left(relative(0.5)).top_0().bottom_0().children(menu)),
        );

        // .MediaContent: over the cover, fading with the pointer: the heart, the volume, the
        // controls near the bottom (and the timeline under them when it is not outside).
        let me_art = cx.entity().downgrade();
        let overlay = div()
            .absolute()
            .inset_0()
            .opacity(fade)
            .child(
                div()
                    .absolute()
                    .top(px(0.245 * nb * ratio))
                    .left_0()
                    .right_0()
                    .flex()
                    .justify_center()
                    .child(
                        div()
                            .id("np-like")
                            .cursor_pointer()
                            .on_mouse_down(MouseButton::Left, |_, _, cx| cx.stop_propagation())
                            .on_click(move |_, _, cx| {
                                let Some(v) = heart_track.clone() else { return };
                                let id = t_id(&v);
                                let now = library.update(cx, |l, cx| l.toggle_favorite(v, cx));
                                if let (Some(now), Some(id)) = (now, id) {
                                    core.rate(id, now.then_some(true));
                                }
                            })
                            .child(icon(if liked { "heart-fill" } else { "heart" }, 0.2 * nb * ratio, if liked { accent } else { white() })),
                    ),
            )
            .child(
                div()
                    .absolute()
                    .top(px(0.03 * nb))
                    .right(px(0.03 * nb))
                    .child(round("np-more", "more", white().opacity(0.85)).on_click({
                        let t = t.clone();
                        move |e, _, cx| crate::menu::Menu::open_player_track(e.position(), t.clone(), cx)
                    })),
            )
            .children(volume_capsule)
            .child(
                div()
                    .absolute()
                    .left_0()
                    .right_0()
                    // Inside, the timeline (22 px bar, 5cqw under it) sits below the controls.
                    .bottom(px(if outside { 0.065 * nb * ratio } else { (0.12 * nb * ratio).max(0.05 * nb + 30.) }))
                    .child(controls),
            );
        let art = div()
            .id("np-art")
            .relative()
            .w(px(nb))
            .h(px(nb * ratio))
            .on_hover(move |over, _, cx| {
                let _ = me_art.update(cx, |this, cx| {
                    this.over_art = *over && this.nav.read(cx).route == Route::NowPlaying;
                    cx.notify();
                });
            })
            .child(image)
            .child(overlay);
        let (seek_out, seek_in) = if outside { (Some(seek), None) } else { (None, Some(seek)) };
        let art = art.children(seek_in.map(|s| div().absolute().left_0().right_0().bottom_0().px(px(0.07 * nb)).pb(px(0.05 * nb)).opacity(fade).child(s.w_full())));

        let player = div()
            .w(px(nb))
            .flex_none()
            .flex()
            .flex_col()
            .items_center()
            .child(art)
            .child(meta)
            .children(seek_out.map(|s| div().w_full().pt(px(0.045 * nb)).px(px(0.01 * nb)).pb(px(0.03 * nb)).child(s.w_full())))
            .child(device);

        let narrow = w <= 900.;
        let side = self.panel.map(|p| {
            // Lyrics padding: from the middle less 0.3 of the gap, to 0.35 of the left margin.
            let (x, side_w) = if narrow { (24., w - 48.) } else { (w / 2. - right_gap * 0.3, w / 2. + right_gap * 0.3 - left_gap * 0.35) };
            let inner = match p {
                Panel::Lyrics => {
                    self.lyrics.update(cx, |l, _| {
                        l.variant = Variant::Panel;
                        l.window_width = w;
                    });
                    div().size_full().child(self.lyrics.clone()).into_any_element()
                }
                Panel::Queue => {
                    let q = queue_list(&self.model, &self.covers, &self.core, accent, cx);
                    div()
                        .id("np-queue-scroll")
                        .size_full()
                        .overflow_y_scroll()
                        .child(div().max_w(px(520.)).pt(px(h * 0.1)).pb(px(h * 0.2)).px(px(8.)).child(q))
                        .into_any_element()
                }
            };
            div().absolute().left(px(x)).w(px(side_w.max(200.))).top(px(58.)).bottom_0().child(inner)
        });
        // Narrow with a panel open: the panel alone, as before.
        let nowbar = (!(narrow && with_panel)).then(|| div().absolute().left(px(left_gap)).top(px(nb_top)).child(player));

        let me_move = cx.entity().downgrade();
        let stage = div()
            .id("np-stage")
            .absolute()
            .inset_0()
            .on_mouse_move(move |_, _, cx| {
                let _ = me_move.update(cx, |this, cx| this.moved(cx));
            })
            .children(side)
            .children(nowbar);

        div()
            .size_full()
            .relative()
            .flex()
            .flex_col()
            .overflow_hidden()
            .text_color(white())
            .child(ambient)
            .child(stage)
            .child(bar)
            // OutputMenu's `.catch`: a click anywhere else closes the menu and goes no further.
            .when(open, |d| {
                let me = cx.entity().downgrade();
                d.child(div().id("np-out-catch").absolute().inset_0().occlude().on_mouse_down(MouseButton::Left, move |_, _, cx| {
                    let _ = me.update(cx, |this, cx| {
                        this.output_open = false;
                        cx.notify();
                    });
                }))
            })
            .into_any_element()
    }
}

fn t_id(v: &serde_json::Value) -> Option<String> {
    v.get("id").and_then(|i| i.as_str()).map(str::to_string)
}
