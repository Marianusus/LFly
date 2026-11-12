//! LFly for computer, drawn by GPUI: no webview, the GPU draws only when something changed.
//!
//! First step of the rewrite: search YouTube Music, a virtual list of songs with covers, play
//! through the Rust engine (`lfly-audio`), the cover's light behind the window.

mod art;
mod core;

use std::collections::{HashMap, HashSet};
use std::sync::Arc;

use gpui_kit::component::input::{Input, InputEvent, InputState};
use gpui_kit::component::{ActiveTheme, TitleBar};
use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use lfly_audio::PlayerEvent;
use lfly_yt::Track;

use crate::core::Core;

const ROW_H: f32 = 56.0;

struct Lfly {
    core: Arc<Core>,
    search: Entity<InputState>,
    tracks: Vec<Track>,
    status: SharedString,
    covers: HashMap<String, Arc<Image>>,
    loading_covers: HashSet<String>,
    current: Option<usize>,
    playing: bool,
    position_ms: u64,
    duration_ms: u64,
    backdrop: Option<Arc<Image>>,
    /// The window is in front: position ticks redraw only then.
    active: bool,
    /// Measuring: play the first result of `LFLY_AUTOPLAY` as soon as it arrives.
    autoplay: bool,
    _subs: Vec<Subscription>,
}

impl Lfly {
    fn new(core: Arc<Core>, window: &mut Window, cx: &mut Context<Self>) -> Self {
        let search = cx.new(|cx| InputState::new(window, cx).placeholder("Cerca brani su YouTube Music"));
        let mut subs = vec![cx.subscribe_in(&search, window, |this, state, ev: &InputEvent, _w, cx| {
            if let InputEvent::PressEnter { .. } = ev {
                let q = state.read(cx).value().to_string();
                this.run_search(q, cx);
            }
        })];
        subs.push(cx.observe_window_activation(window, |this, window, cx| {
            this.active = window.is_window_active();
            cx.notify();
        }));

        // Engine events: a foreground task, the receiver works on any executor.
        let mut events = core.audio.subscribe();
        cx.spawn(async move |this, cx| {
            loop {
                let ev = match events.recv().await {
                    Ok(ev) => ev,
                    // A burst while the window was busy: the next event is as good as the missed ones.
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
                    Err(_) => break,
                };
                if this.update(cx, |this, cx| this.on_player(ev, cx)).is_err() {
                    break;
                }
            }
        })
        .detach();

        Self {
            core,
            search,
            tracks: Vec::new(),
            status: "Cerca qualcosa da ascoltare.".into(),
            covers: HashMap::new(),
            loading_covers: HashSet::new(),
            current: None,
            playing: false,
            position_ms: 0,
            duration_ms: 0,
            backdrop: None,
            active: true,
            autoplay: false,
            _subs: subs,
        }
        .start_autoplay(cx)
    }

    fn start_autoplay(mut self, cx: &mut Context<Self>) -> Self {
        if std::env::var_os("LFLY_MUTE").is_some() {
            self.core.audio.set_volume(0.0);
        }
        if let Ok(q) = std::env::var("LFLY_AUTOPLAY") {
            self.autoplay = true;
            self.run_search(q, cx);
        }
        self
    }

    fn on_player(&mut self, ev: PlayerEvent, cx: &mut Context<Self>) {
        match ev {
            PlayerEvent::Playing => self.playing = true,
            PlayerEvent::Paused | PlayerEvent::Ended => self.playing = false,
            PlayerEvent::Position { ms } => {
                self.position_ms = ms;
                // Behind a game nobody reads the bar: no frame at all.
                if !self.active {
                    return;
                }
            }
            PlayerEvent::Duration { ms } => self.duration_ms = ms,
            PlayerEvent::Error { message } => self.status = format!("Errore: {message}").into(),
            _ => return,
        }
        cx.notify();
    }

    fn run_search(&mut self, query: String, cx: &mut Context<Self>) {
        if query.trim().is_empty() {
            return;
        }
        self.status = "Cerco…".into();
        cx.notify();
        let core = self.core.clone();
        cx.spawn(async move |this, cx| {
            let result = core.search(query).await;
            let _ = this.update(cx, |this, cx| {
                match result {
                    Ok(tracks) => {
                        this.status = if tracks.is_empty() { "Nessun risultato.".into() } else { "".into() };
                        this.tracks = tracks;
                        if std::mem::take(&mut this.autoplay) && !this.tracks.is_empty() {
                            this.play(0, cx);
                        }
                    }
                    Err(e) => this.status = format!("Ricerca non riuscita: {e}").into(),
                }
                cx.notify();
            });
        })
        .detach();
    }

    fn play(&mut self, ix: usize, cx: &mut Context<Self>) {
        let Some(track) = self.tracks.get(ix).cloned() else { return };
        self.current = Some(ix);
        self.position_ms = 0;
        self.duration_ms = track.duration_ms.max(0) as u64;
        self.backdrop = None;
        cx.notify();
        let core = self.core.clone();
        let cover = track.cover_url.clone();
        cx.spawn(async move |this, cx| {
            if let Err(e) = core.clone().play(track).await {
                let _ = this.update(cx, |this, cx| {
                    this.status = format!("Impossibile riprodurre: {e}").into();
                    cx.notify();
                });
            }
            // The light behind the window, from a bigger copy of the cover.
            if let Some(url) = cover {
                if let Some(bytes) = core.fetch(lfly_yt::upscale_thumbnail(&url)).await {
                    let blurred = cx.background_executor().spawn(async move { art::backdrop(&bytes) }).await;
                    let _ = this.update(cx, |this, cx| {
                        if this.current == Some(ix) {
                            this.backdrop = blurred;
                            cx.notify();
                        }
                    });
                }
            }
        })
        .detach();
    }

    fn toggle(&mut self, cx: &mut Context<Self>) {
        if self.current.is_some() {
            self.core.audio.toggle();
        }
        cx.notify();
    }

    fn next(&mut self, delta: isize, cx: &mut Context<Self>) {
        let Some(cur) = self.current else { return };
        let to = cur as isize + delta;
        if to >= 0 && (to as usize) < self.tracks.len() {
            self.play(to as usize, cx);
        }
    }

    /// Loads the covers of the rows on screen, once each.
    fn want_cover(&mut self, track: &Track, cx: &mut Context<Self>) {
        let Some(url) = track.cover_url.clone() else { return };
        if self.covers.contains_key(&url) || !self.loading_covers.insert(url.clone()) {
            return;
        }
        let core = self.core.clone();
        cx.spawn(async move |this, cx| {
            let bytes = core.fetch(url.clone()).await;
            let _ = this.update(cx, |this, cx| {
                if let Some(img) = bytes.and_then(art::image) {
                    this.covers.insert(url, img);
                    cx.notify();
                }
            });
        })
        .detach();
    }

    fn row(&mut self, ix: usize, cx: &mut Context<Self>) -> impl IntoElement {
        let track = self.tracks[ix].clone();
        self.want_cover(&track, cx);
        let cover = track.cover_url.as_ref().and_then(|u| self.covers.get(u).cloned());
        let on = self.current == Some(ix);
        let accent = cx.theme().primary;
        div()
            .id(ix)
            .h(px(ROW_H))
            .w_full()
            .px_3()
            .flex()
            .items_center()
            .gap_3()
            .rounded_lg()
            .cursor_pointer()
            .hover(|s| s.bg(white().opacity(0.06)))
            .when(on, |s| s.bg(white().opacity(0.09)))
            .on_click(cx.listener(move |this, _, _, cx| this.play(ix, cx)))
            .child(
                div().size(px(40.)).flex_none().rounded_md().overflow_hidden().bg(white().opacity(0.08)).children(
                    cover.map(|c| img(c).size_full().object_fit(ObjectFit::Cover)),
                ),
            )
            .child(
                div()
                    .flex_1()
                    .min_w_0()
                    .flex()
                    .flex_col()
                    .child(
                        div()
                            .text_sm()
                            .font_weight(FontWeight::MEDIUM)
                            .truncate()
                            .when(on, |s| s.text_color(accent))
                            .child(track.title.clone()),
                    )
                    .child(div().text_xs().text_color(white().opacity(0.6)).truncate().child(track.artist.clone())),
            )
            .when(track.duration_ms > 0, |s| {
                s.child(div().text_xs().text_color(white().opacity(0.5)).child(clock(track.duration_ms as u64)))
            })
    }

    fn player_bar(&self, cx: &mut Context<Self>) -> impl IntoElement {
        let track = self.current.and_then(|i| self.tracks.get(i));
        let cover = track.and_then(|t| t.cover_url.as_ref()).and_then(|u| self.covers.get(u).cloned());
        let frac = if self.duration_ms > 0 { (self.position_ms as f32 / self.duration_ms as f32).clamp(0., 1.) } else { 0. };
        let accent = cx.theme().primary;
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
        div()
            .h(px(76.))
            .flex_none()
            .px_4()
            .flex()
            .items_center()
            .gap_4()
            .bg(black().opacity(0.35))
            .border_t_1()
            .border_color(white().opacity(0.08))
            .child(
                div().size(px(52.)).flex_none().rounded_md().overflow_hidden().bg(white().opacity(0.08)).children(
                    cover.map(|c| img(c).size_full().object_fit(ObjectFit::Cover)),
                ),
            )
            .child(
                div()
                    .w(px(220.))
                    .flex_none()
                    .flex()
                    .flex_col()
                    .child(div().text_sm().font_weight(FontWeight::MEDIUM).truncate().child(
                        track.map_or(SharedString::from("Niente in riproduzione"), |t| t.title.clone().into()),
                    ))
                    .child(div().text_xs().text_color(white().opacity(0.6)).truncate().child(
                        track.map_or(SharedString::default(), |t| t.artist.clone().into()),
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
                            .child(button("prev", "⏮").on_click(cx.listener(|this, _, _, cx| this.next(-1, cx))))
                            .child(
                                button("toggle", if self.playing { "⏸" } else { "▶" })
                                    .bg(white().opacity(0.12))
                                    .on_click(cx.listener(|this, _, _, cx| this.toggle(cx))),
                            )
                            .child(button("next", "⏭").on_click(cx.listener(|this, _, _, cx| this.next(1, cx)))),
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
                            .child(clock(self.position_ms))
                            .child(
                                div()
                                    .flex_1()
                                    .h(px(4.))
                                    .rounded_full()
                                    .bg(white().opacity(0.15))
                                    .child(div().h_full().rounded_full().bg(accent).w(relative(frac))),
                            )
                            .child(clock(self.duration_ms)),
                    ),
            )
            .child(div().w(px(220.)).flex_none())
    }
}

impl Render for Lfly {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let count = self.tracks.len();
        let list = uniform_list("tracks", count, cx.processor(|this, range: std::ops::Range<usize>, _w, cx| {
            range.map(|ix| this.row(ix, cx)).collect::<Vec<_>>()
        }))
        .flex_1()
        .px_2()
        .py_2();

        div()
            .size_full()
            .relative()
            .bg(rgb(0x0b0b0f))
            .text_color(white())
            // The cover's light, stretched: a 64 px picture, drawn again only when it changes.
            .children(self.backdrop.clone().map(|b| {
                div().absolute().inset_0().child(img(b).size_full().object_fit(ObjectFit::Fill))
            }))
            .child(
                div()
                    .absolute()
                    .inset_0()
                    .flex()
                    .flex_col()
                    .child(TitleBar::new().child(div().text_sm().font_weight(FontWeight::SEMIBOLD).child("LFly")))
                    .child(div().px_4().pt_3().pb_2().child(Input::new(&self.search).cleanable(true)))
                    .when(!self.status.is_empty(), |s| {
                        s.child(div().px_5().py_2().text_sm().text_color(white().opacity(0.6)).child(self.status.clone()))
                    })
                    .child(list)
                    .child(self.player_bar(cx)),
            )
    }
}

fn clock(ms: u64) -> SharedString {
    let s = ms / 1000;
    format!("{}:{:02}", s / 60, s % 60).into()
}

fn main() {
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info")).init();
    let core = match Core::new() {
        Ok(c) => c,
        Err(e) => {
            eprintln!("LFly non parte: {e}");
            std::process::exit(1);
        }
    };
    gpui_kit::application().with_assets(gpui_kit::assets::Assets).run(move |cx: &mut App| {
        gpui_kit::init(cx);
        gpui_kit::component::Theme::change(gpui_kit::component::ThemeMode::Dark, None, cx);
        let bounds = Bounds::centered(None, size(px(1100.), px(720.)), cx);
        let options = WindowOptions {
            window_bounds: Some(WindowBounds::Windowed(bounds)),
            window_min_size: Some(size(px(720.), px(480.))),
            app_id: Some("lfly".into()),
            // The title bar is ours (TitleBar draws the buttons); no second one from the system.
            window_decorations: Some(WindowDecorations::Client),
            ..TitleBar::window_options()
        };
        gpui_kit::open_window(options, cx, |window, cx| cx.new(|cx| Lfly::new(core, window, cx)))
            .expect("window");
        cx.activate(true);
    });
}
