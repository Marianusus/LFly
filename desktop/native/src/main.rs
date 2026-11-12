//! LFly for computer, drawn by GPUI: no webview, the GPU draws only when something changed.
//!
//! The rewrite of the Tauri window, a screen at a time. Logic lives in lfly-core (shared with
//! the Tauri app); this crate only draws. Test modes: `LFLY_AUTOPLAY="query"` plays the first
//! result, `LFLY_MUTE=1`, and the glass comparison flags in player_bar.rs.

mod art;
mod core;
mod covers;
mod glass;
mod icons;
mod look;
mod player_bar;
mod player_model;
mod scene;
mod slider;
mod theme;

use std::sync::Arc;

use gpui_kit::component::input::{Input, InputEvent, InputState};
use gpui_kit::component::TitleBar;
use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use lfly_connect::protocol;
use lfly_yt::Track;

use crate::core::Core;
use crate::covers::Covers;
use crate::look::Look;
use crate::player_bar::{clock, PlayerBar};
use crate::player_model::{PlayerEvent, PlayerModel};

const ROW_H: f32 = 56.0;

struct Lfly {
    core: Arc<Core>,
    model: Entity<PlayerModel>,
    look: Entity<Look>,
    covers: Entity<Covers>,
    bar: Entity<PlayerBar>,
    search: Entity<InputState>,
    tracks: Vec<Track>,
    status: SharedString,
    /// The song whose cover colours the look.
    colors_for: Option<String>,
    /// Measuring: play the first result of `LFLY_AUTOPLAY` as soon as it arrives.
    autoplay: bool,
    _subs: Vec<Subscription>,
}

impl Lfly {
    fn new(core: Arc<Core>, model: Entity<PlayerModel>, window: &mut Window, cx: &mut Context<Self>) -> Self {
        let covers = cx.new(|_| Covers::new(core.clone()));
        let look = cx.new(|_| Look::new());
        let bar = cx.new(|cx| PlayerBar::new(model.clone(), covers.clone(), look.clone(), cx));
        let search = cx.new(|cx| InputState::new(window, cx).placeholder("Cerca brani su YouTube Music"));
        let subs = vec![
            cx.subscribe_in(&search, window, |this, state, ev: &InputEvent, _w, cx| {
                if let InputEvent::PressEnter { .. } = ev {
                    let q = state.read(cx).value().to_string();
                    this.run_search(q, cx);
                }
            }),
            cx.observe_window_activation(window, |this, window, cx| {
                let active = window.is_window_active();
                this.model.update(cx, |m, cx| {
                    m.active = active;
                    cx.notify();
                });
            }),
            // Only real changes reach this view: the position ticks stay in the bar.
            cx.subscribe(&model, |this, _, ev: &PlayerEvent, cx| match ev {
                PlayerEvent::StateChanged => {
                    this.refresh_colors(cx);
                    cx.notify();
                }
                PlayerEvent::Notice(text) => {
                    this.status = text.clone();
                    cx.notify();
                }
            }),
            cx.observe(&covers, |_, _, cx| cx.notify()),
            cx.observe(&look, |_, _, cx| cx.notify()),
        ];
        let mut this = Self {
            core,
            model,
            look,
            covers,
            bar,
            search,
            tracks: Vec::new(),
            status: "Cerca qualcosa da ascoltare.".into(),
            colors_for: None,
            autoplay: false,
            _subs: subs,
        };
        if std::env::var_os("LFLY_MUTE").is_some() {
            this.core.command(protocol::CMD_VOLUME, 0);
        }
        if let Ok(q) = std::env::var("LFLY_AUTOPLAY") {
            this.autoplay = true;
            this.run_search(q, cx);
        }
        this
    }

    /// The dynamic modes take their colours from the playing cover (coverArt.ts).
    fn refresh_colors(&mut self, cx: &mut Context<Self>) {
        let Some(track) = self.model.read(cx).current().cloned() else { return };
        if self.colors_for.as_deref() == Some(track.id.as_str()) {
            return;
        }
        self.colors_for = Some(track.id.clone());
        let Some(url) = track.cover_url.clone() else { return };
        let core = self.core.clone();
        let id = track.id;
        cx.spawn(async move |this, cx| {
            let Some(bytes) = core.fetch(url).await else { return };
            let colors = cx.background_executor().spawn(async move { scene::cover_colors(&bytes) }).await;
            let _ = this.update(cx, |this, cx| {
                if this.colors_for.as_deref() == Some(id.as_str()) {
                    this.look.update(cx, |l, cx| l.set_cover_colors(colors, cx));
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
                            this.core.play(&this.tracks, 0);
                        }
                    }
                    Err(e) => this.status = format!("Ricerca non riuscita: {e}").into(),
                }
                cx.notify();
            });
        })
        .detach();
    }

    fn row(&mut self, ix: usize, current: Option<&str>, cx: &mut Context<Self>) -> impl IntoElement {
        let track = self.tracks[ix].clone();
        let cover = track.cover_url.as_ref().and_then(|u| self.covers.update(cx, |c, cx| c.get(u, cx)));
        let on = current == Some(track.id.as_str());
        let scheme = &self.look.read(cx).scheme;
        let (accent, text2, text3) = (scheme.accent.hsla(1.), scheme.text2, scheme.text3);
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
            .on_click(cx.listener(move |this, _, _, _| this.core.play(&this.tracks, ix)))
            .child(
                div()
                    .size(px(40.))
                    .flex_none()
                    .rounded_md()
                    .overflow_hidden()
                    .bg(white().opacity(0.08))
                    .children(cover.map(|c| img(c).size_full().object_fit(ObjectFit::Cover))),
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
                    .child(div().text_xs().text_color(text2).truncate().child(track.artist.clone())),
            )
            .when(track.duration_ms > 0, |s| {
                s.child(div().text_xs().text_color(text3).child(clock(track.duration_ms as u64)))
            })
    }
}

impl Render for Lfly {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let count = self.tracks.len();
        let size = window.viewport_size();
        let scene_img = self.look.update(cx, |l, cx| l.scene(size, cx));
        let (bg, text, text2) = {
            let s = &self.look.read(cx).scheme;
            (s.bg.hsla(1.), s.text, s.text2)
        };
        let list = uniform_list(
            "tracks",
            count,
            cx.processor(|this, range: std::ops::Range<usize>, _w, cx| {
                let current = this.model.read(cx).current().map(|t| t.id.clone());
                range.map(|ix| this.row(ix, current.as_deref(), cx)).collect::<Vec<_>>()
            }),
        )
        .flex_1()
        .px_2()
        .pt_2()
        // Room for the floating player: the last rows scroll out from under the glass.
        .pb(px(100.));

        div()
            .size_full()
            .relative()
            .bg(bg)
            .text_color(text)
            // The scene (scene.rs): a third of the window, stretched; painted again only on change.
            .children(scene_img.map(|b| div().absolute().inset_0().child(img(b).size_full().object_fit(ObjectFit::Fill))))
            .child(
                div()
                    .absolute()
                    .inset_0()
                    .flex()
                    .flex_col()
                    .child(TitleBar::new().child(div().text_sm().font_weight(FontWeight::SEMIBOLD).child("LFly")))
                    .child(div().px_4().pt_3().pb_2().child(Input::new(&self.search).cleanable(true)))
                    .when(!self.status.is_empty(), |s| {
                        s.child(div().px_5().py_2().text_sm().text_color(text2).child(self.status.clone()))
                    })
                    .child(list),
            )
            .child(self.bar.clone())
    }
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
    gpui_kit::application().with_assets(icons::Assets).run(move |cx: &mut App| {
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
        let model = cx.new(|cx| PlayerModel::new(core.clone(), updates, cx));
        gpui_kit::open_window(options, cx, |window, cx| cx.new(|cx| Lfly::new(core, model, window, cx)))
            .expect("window");
        cx.activate(true);
    });
}
