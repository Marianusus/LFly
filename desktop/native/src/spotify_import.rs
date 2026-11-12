//! Importa da Spotify (Tauri: components/SpotifyImport.svelte): paste a playlist, album or song
//! link; each song is found on YouTube Music (lfly-core::spotify) and a playlist is made in the
//! library, then opened. No Spotify account; the playlist must be public.

use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;

use gpui_kit::component::input::{Input, InputEvent, InputState};
use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;

use crate::core::{runtime, Core};
use crate::icons::icon;
use crate::library_model::LibraryModel;
use crate::look::Look;
use crate::nav::{Nav, Route};
use crate::toast::Toasts;

pub enum SpotifyEvent {
    Close,
}

pub struct SpotifyImport {
    core: Arc<Core>,
    look: Entity<Look>,
    library: Entity<LibraryModel>,
    nav: Entity<Nav>,
    input: Entity<InputState>,
    busy: bool,
    /// (done, total), shared with the search running on the network threads.
    progress: Arc<(AtomicUsize, AtomicUsize)>,
    error: Option<String>,
    missing: Vec<String>,
    show_missing: bool,
    _subs: Vec<Subscription>,
}

impl EventEmitter<SpotifyEvent> for SpotifyImport {}

impl SpotifyImport {
    pub fn new(core: Arc<Core>, look: Entity<Look>, library: Entity<LibraryModel>, nav: Entity<Nav>, window: &mut Window, cx: &mut Context<Self>) -> Self {
        let input = cx.new(|cx| InputState::new(window, cx).placeholder("https://open.spotify.com/playlist/…"));
        let subs = vec![
            cx.subscribe_in(&input, window, |this, _, ev: &InputEvent, _w, cx| {
                if let InputEvent::PressEnter { .. } = ev {
                    this.start(cx);
                }
                cx.notify();
            }),
            cx.observe(&look, |_, _, cx| cx.notify()),
        ];
        Self {
            core,
            look,
            library,
            nav,
            input,
            busy: false,
            progress: Arc::new((AtomicUsize::new(0), AtomicUsize::new(0))),
            error: None,
            missing: Vec::new(),
            show_missing: false,
            _subs: subs,
        }
    }

    fn start(&mut self, cx: &mut Context<Self>) {
        let link = self.input.read(cx).value().trim().to_string();
        if link.is_empty() || self.busy {
            return;
        }
        self.busy = true;
        self.error = None;
        self.missing.clear();
        self.progress.0.store(0, Ordering::Relaxed);
        self.progress.1.store(0, Ordering::Relaxed);
        cx.notify();
        let (core, progress) = (self.core.clone(), self.progress.clone());
        // The bar moves while the songs are found: a redraw a few times a second.
        cx.spawn(async move |this, cx| loop {
            cx.background_executor().timer(std::time::Duration::from_millis(250)).await;
            let busy = this.update(cx, |this, cx| {
                cx.notify();
                this.busy
            });
            if !matches!(busy, Ok(true)) {
                break;
            }
        })
        .detach();
        cx.spawn(async move |this, cx| {
            let p = progress.clone();
            let r = runtime()
                .spawn(async move {
                    lfly_core::spotify::import(&core.yt, &link, move |done, total| {
                        p.0.store(done, Ordering::Relaxed);
                        p.1.store(total, Ordering::Relaxed);
                    })
                    .await
                })
                .await
                .map_err(|e| e.to_string())
                .and_then(|r| r);
            let _ = this.update(cx, |this, cx| {
                this.busy = false;
                match r {
                    Ok(r) if r.tracks.is_empty() => this.error = Some("Nessun brano trovato su YouTube Music".into()),
                    Ok(r) => {
                        let mut notes = Vec::new();
                        if !r.missing.is_empty() {
                            notes.push(format!("{} non trovati", r.missing.len()));
                        }
                        if r.truncated {
                            notes.push("Spotify mostra solo i primi 100 brani".to_string());
                        }
                        let n = r.tracks.len();
                        let say = format!("\"{}\" importata: {n} brani{}", r.name, if notes.is_empty() { String::new() } else { format!(" ({})", notes.join(", ")) });
                        this.missing = r.missing;
                        let tracks: Vec<serde_json::Value> = r.tracks.iter().filter_map(|t| serde_json::to_value(t).ok()).collect();
                        let nav = this.nav.clone();
                        let close = this.missing.is_empty();
                        let me = cx.entity().downgrade();
                        this.library.update(cx, |l, cx| {
                            l.create_playlist_with_cover(
                                r.name,
                                tracks,
                                r.cover_url,
                                move |id, name, cx| {
                                    Toasts::say(say, cx);
                                    nav.update(cx, |n, cx| n.go(Route::Playlist { id, name, local: true }, cx));
                                    if close {
                                        let _ = me.update(cx, |_, cx| cx.emit(SpotifyEvent::Close));
                                    }
                                },
                                cx,
                            )
                        });
                    }
                    Err(e) => this.error = Some(e),
                }
                cx.notify();
            });
        })
        .detach();
    }
}

impl Render for SpotifyImport {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let s = self.look.read(cx).scheme.clone();
        let accent = s.accent.hsla(1.);
        let (done, total) = (self.progress.0.load(Ordering::Relaxed), self.progress.1.load(Ordering::Relaxed));
        let empty = self.input.read(cx).value().trim().is_empty();
        let me = cx.entity().downgrade();
        let me2 = me.clone();
        let me3 = me.clone();
        let go_label = if self.busy {
            if total > 0 { format!("{done} di {total}…") } else { "Leggo…".to_string() }
        } else {
            "Importa".to_string()
        };
        div()
            .mt(px(16.))
            .max_w(px(720.))
            .flex()
            .flex_col()
            .gap(px(12.))
            .pt(px(18.))
            .px(px(20.))
            .pb(px(20.))
            .rounded(px(24.))
            .bg(s.glass_tint)
            .border_1()
            .border_color(s.glass_border)
            .child(
                div()
                    .flex()
                    .items_center()
                    .gap(px(10.))
                    .child(div().size(px(30.)).rounded_full().flex().items_center().justify_center().bg(rgb(0x1db954)).child(icon("upload", 17., white())))
                    .child(div().flex_1().font_weight(FontWeight::BOLD).child("Importa da Spotify"))
                    .child(
                        div()
                            .id("sp-close")
                            .size(px(28.))
                            .rounded_full()
                            .flex()
                            .items_center()
                            .justify_center()
                            .cursor_pointer()
                            .hover(|d| d.bg(s.text.opacity(0.1)))
                            .on_click(move |_, _, cx| {
                                let _ = me.update(cx, |_, cx| cx.emit(SpotifyEvent::Close));
                            })
                            .child(icon("close", 14., s.text2)),
                    ),
            )
            .child(div().text_size(px(13.)).line_height(px(19.5)).text_color(s.text2).child(
                "Incolla il link di una playlist, di un album o di un brano (Spotify › Condividi › Copia link). LFly cerca ogni brano su YouTube Music e crea una playlist nella tua libreria. Non serve l'account Spotify; la playlist deve essere pubblica.",
            ))
            .child(
                div()
                    .flex()
                    .gap(px(10.))
                    .child(
                        div()
                            .flex_1()
                            .h(px(40.))
                            .px(px(16.))
                            .flex()
                            .items_center()
                            .rounded_full()
                            .bg(s.text.opacity(0.06))
                            .border_1()
                            .border_color(s.glass_border)
                            .child(Input::new(&self.input).appearance(false).disabled(self.busy)),
                    )
                    .child(
                        div()
                            .id("sp-go")
                            .h(px(40.))
                            .px(px(20.))
                            .flex()
                            .items_center()
                            .rounded_full()
                            .bg(accent)
                            .text_color(s.on_accent.hsla(1.))
                            .text_size(px(13.))
                            .font_weight(FontWeight::SEMIBOLD)
                            .when(self.busy || empty, |d| d.opacity(0.45))
                            .when(!self.busy && !empty, |d| {
                                d.cursor_pointer().on_click(move |_, _, cx| {
                                    let _ = me2.update(cx, |this, cx| this.start(cx));
                                })
                            })
                            .child(go_label),
                    ),
            )
            .when(self.busy && total > 0, |d| {
                d.child(div().h(px(4.)).rounded_full().bg(s.text.opacity(0.12)).child(div().h_full().rounded_full().bg(accent).w(relative(done as f32 / total as f32))))
            })
            .children(self.error.clone().map(|e| div().text_size(px(13.)).text_color(rgb(0xff6b6b)).child(e)))
            .when(!self.missing.is_empty(), |d| {
                let open = self.show_missing;
                d.child(
                    div()
                        .id("sp-missing")
                        .text_size(px(13.))
                        .text_color(s.text2)
                        .cursor_pointer()
                        .on_click(move |_, _, cx| {
                            let _ = me3.update(cx, |this, cx| {
                                this.show_missing = !this.show_missing;
                                cx.notify();
                            });
                        })
                        .child(format!("{} {} brani non trovati su YouTube Music", if open { "▾" } else { "▸" }, self.missing.len())),
                )
                .when(open, |d| d.children(self.missing.iter().map(|m| div().pl(px(16.)).text_size(px(12.)).text_color(s.text3).child(m.clone()))))
            })
    }
}
