//! LFly for computer, drawn by GPUI: no webview, the GPU draws only when something changed.
//!
//! First step of the rewrite: search YouTube Music, a virtual list of songs with covers, play
//! through the shared player (lfly-core, as the Tauri app), the cover's light behind the window.

mod art;
mod core;
mod glass;

use std::collections::{HashMap, HashSet};
use std::sync::Arc;

use gpui_kit::component::input::{Input, InputEvent, InputState};
use gpui_kit::component::{ActiveTheme, TitleBar};
use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use lfly_connect::protocol::{self, State, TrackRef};
use lfly_yt::Track;

use crate::core::{Core, PlayerUpdate};

const ROW_H: f32 = 56.0;

struct Lfly {
    core: Arc<Core>,
    search: Entity<InputState>,
    tracks: Vec<Track>,
    status: SharedString,
    covers: HashMap<String, Arc<Image>>,
    loading_covers: HashSet<String>,
    /// What the shared player reported last (queue, index, play/pause...).
    state: Option<State>,
    position_ms: u64,
    backdrop: Option<Arc<Image>>,
    /// The song the backdrop belongs to.
    backdrop_for: Option<String>,
    /// The window is in front: position ticks redraw only then.
    active: bool,
    /// The pointer over the player glass (window coordinates), for its light.
    pointer_at: Option<Point<Pixels>>,
    /// Measuring: play the first result of `LFLY_AUTOPLAY` as soon as it arrives.
    autoplay: bool,
    _subs: Vec<Subscription>,
}

impl Lfly {
    fn new(
        core: Arc<Core>,
        mut updates: tokio::sync::mpsc::UnboundedReceiver<PlayerUpdate>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Self {
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

        // The player's reports: a foreground task, the receiver works on any executor.
        cx.spawn(async move |this, cx| {
            while let Some(update) = updates.recv().await {
                if this.update(cx, |this, cx| this.on_player(update, cx)).is_err() {
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
            state: None,
            position_ms: 0,
            backdrop: None,
            backdrop_for: None,
            active: true,
            autoplay: false,
            pointer_at: None,
            _subs: subs,
        }
        .start_autoplay(cx)
    }

    fn start_autoplay(mut self, cx: &mut Context<Self>) -> Self {
        if std::env::var_os("LFLY_MUTE").is_some() {
            self.core.command(protocol::CMD_VOLUME, 0);
        }
        if let Ok(q) = std::env::var("LFLY_AUTOPLAY") {
            self.autoplay = true;
            self.run_search(q, cx);
        }
        self
    }

    fn current(&self) -> Option<&TrackRef> {
        let s = self.state.as_ref()?;
        s.queue.get(usize::try_from(s.index).ok()?)
    }

    fn playing(&self) -> bool {
        self.state.as_ref().is_some_and(|s| s.playing)
    }

    fn duration_ms(&self) -> u64 {
        let d = self.state.as_ref().map_or(0, |s| s.duration_ms);
        let d = if d > 0 { d } else { self.current().map_or(0, |t| t.duration_ms) };
        d.max(0) as u64
    }

    fn on_player(&mut self, update: PlayerUpdate, cx: &mut Context<Self>) {
        match update {
            PlayerUpdate::State(state) => {
                self.position_ms = state.position_ms.max(0) as u64;
                self.state = Some(*state);
                self.refresh_backdrop(cx);
            }
            PlayerUpdate::Position { ms } => {
                self.position_ms = ms.max(0) as u64;
                // Behind a game nobody reads the bar: no frame at all.
                if !self.active {
                    return;
                }
            }
            PlayerUpdate::Notice(text) => self.status = text.into(),
        }
        cx.notify();
    }

    /// The light behind the window follows the song, from a bigger copy of its cover.
    fn refresh_backdrop(&mut self, cx: &mut Context<Self>) {
        let Some(track) = self.current().cloned() else { return };
        if self.backdrop_for.as_deref() == Some(track.id.as_str()) {
            return;
        }
        self.backdrop_for = Some(track.id.clone());
        let Some(url) = track.cover_url.clone() else { return };
        let core = self.core.clone();
        let id = track.id;
        cx.spawn(async move |this, cx| {
            let Some(bytes) = core.fetch(lfly_yt::upscale_thumbnail(&url)).await else { return };
            let blurred = cx.background_executor().spawn(async move { art::backdrop(&bytes) }).await;
            let _ = this.update(cx, |this, cx| {
                if this.backdrop_for.as_deref() == Some(id.as_str()) {
                    this.backdrop = blurred;
                    cx.notify();
                }
            });
        })
        .detach();
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
        if ix >= self.tracks.len() {
            return;
        }
        self.position_ms = 0;
        // The shared player resolves the full stream, preloads the next song and retries
        // expired URLs; its state comes back through `on_player`.
        self.core.play(&self.tracks, ix);
        cx.notify();
    }

    fn toggle(&mut self, cx: &mut Context<Self>) {
        self.core.command(protocol::CMD_TOGGLE, 0);
        cx.notify();
    }

    fn next(&mut self, delta: isize, cx: &mut Context<Self>) {
        self.core.command(if delta > 0 { protocol::CMD_NEXT } else { protocol::CMD_PREVIOUS }, 0);
        cx.notify();
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
        let on = self.current().is_some_and(|t| t.id == track.id);
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

    fn player_bar(&self, cx: &mut Context<Self>) -> AnyElement {
        // LFLY_GLASS_HIDE: no player at all (the backdrop for the comparison).
        if std::env::var_os("LFLY_GLASS_HIDE").is_some() {
            return div().into_any_element();
        }
        let track = self.current();
        let cover = track.and_then(|t| t.cover_url.as_ref()).and_then(|u| self.covers.get(u).cloned());
        let duration_ms = self.duration_ms();
        let frac = if duration_ms > 0 { (self.position_ms as f32 / duration_ms as f32).clamp(0., 1.) } else { 0. };
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
        let look = glass::GlassLook::dark(0.75);
        // A pill of glass floating over the list, as the Tauri PlayerBar.
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
            .on_mouse_move(cx.listener(|this, e: &MouseMoveEvent, window, cx| {
                this.pointer_at = Some(e.position);
                let _ = window;
                cx.notify();
            }))
            .on_hover(cx.listener(|this, hovered: &bool, _, cx| {
                if !*hovered {
                    this.pointer_at = None;
                    cx.notify();
                }
            }))
            .child(glass::glass_layer(look, px(38.), glass::Pointer { window: self.pointer_at }))
            // LFLY_GLASS_EMPTY: the glass alone, for comparing it with the Tauri one.
            .when(std::env::var_os("LFLY_GLASS_EMPTY").is_none(), |bar| bar
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
                                button("toggle", if self.playing() { "⏸" } else { "▶" })
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
                            .child(clock(duration_ms)),
                    ),
            )
            .child(div().w(px(220.)).flex_none()))
            .into_any_element()
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
        .pt_2()
        // Room for the floating player: the last rows scroll out from under the glass.
        .pb(px(100.));

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
                    .child(list),
            )
            .child(self.player_bar(cx))
    }
}

fn clock(ms: u64) -> SharedString {
    let s = ms / 1000;
    format!("{}:{:02}", s / 60, s % 60).into()
}

fn main() {
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info")).init();
    let (core, updates) = match Core::new() {
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
        gpui_kit::open_window(options, cx, |window, cx| cx.new(|cx| Lfly::new(core, updates, window, cx)))
            .expect("window");
        cx.activate(true);
    });
}
