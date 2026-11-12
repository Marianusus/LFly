//! A collection page: an album, a YouTube playlist, a library playlist or the liked songs
//! (Tauri: pages/Album.svelte, RemotePlaylist.svelte, LocalPlaylist.svelte, Liked.svelte with
//! parts/Hero.svelte and TrackList). The hero scrolls with the rows; the rows are a virtual list.

use std::sync::Arc;

use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use lfly_yt::Track;
use serde_json::Value;

use crate::art;
use crate::core::{runtime, Core};
use crate::covers::Covers;
use crate::icons::icon;
use crate::library_model::LibraryModel;
use crate::look::Look;
use crate::nav::{Nav, Route};
use crate::player_model::PlayerModel;
use crate::track_row::{columns, track_row, RowData};

#[derive(Clone, Debug, PartialEq)]
pub enum Source {
    Album(String),
    Playlist(String),
    Local(String),
    Liked,
}

#[derive(Default, Clone)]
struct Header {
    kind: SharedString,
    title: SharedString,
    byline: Option<(SharedString, Option<Route>)>,
    meta: SharedString,
    description: Option<SharedString>,
    cover_url: Option<String>,
}

pub struct Collection {
    core: Arc<Core>,
    covers: Entity<Covers>,
    look: Entity<Look>,
    nav: Entity<Nav>,
    library: Entity<LibraryModel>,
    model: Entity<PlayerModel>,
    source: Source,
    header: Header,
    tracks: Vec<Track>,
    error: Option<String>,
    loading: bool,
    glow: Option<Arc<Image>>,
    list: ListState,
    pub width: f32,
    _subs: Vec<Subscription>,
}

/// Album.svelte `minutes`: "1 h 12 min" or "42 min".
fn minutes(tracks: &[Track]) -> String {
    let total: i64 = tracks.iter().map(|t| t.duration_ms.max(0)).sum::<i64>() / 60_000;
    if total == 0 {
        String::new()
    } else if total >= 60 {
        format!("{} h {} min", total / 60, total % 60)
    } else {
        format!("{total} min")
    }
}

fn tracks_of(values: &[Value]) -> Vec<Track> {
    values.iter().filter_map(|v| serde_json::from_value(v.clone()).ok()).collect()
}

impl Collection {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        core: Arc<Core>,
        covers: Entity<Covers>,
        look: Entity<Look>,
        nav: Entity<Nav>,
        library: Entity<LibraryModel>,
        model: Entity<PlayerModel>,
        cx: &mut Context<Self>,
    ) -> Self {
        let subs = vec![
            cx.observe(&covers, |_, _, cx| cx.notify()),
            cx.observe(&look, |_, _, cx| cx.notify()),
            cx.observe(&library, |this, _, cx| {
                if matches!(this.source, Source::Liked | Source::Local(_)) {
                    this.load_local(cx);
                }
            }),
            cx.subscribe(&model, |_, _, _: &crate::player_model::PlayerEvent, cx| cx.notify()),
        ];
        Self {
            core,
            covers,
            look,
            nav,
            library,
            model,
            source: Source::Liked,
            header: Header::default(),
            tracks: Vec::new(),
            error: None,
            loading: false,
            glow: None,
            list: ListState::new(1, ListAlignment::Top, px(600.)),
            width: 1100.,
            _subs: subs,
        }
    }

    /// Shows [source] (loads it when it is not the one already shown).
    pub fn show(&mut self, source: Source, name: Option<String>, cx: &mut Context<Self>) {
        if self.source == source && (self.loading || !self.tracks.is_empty() || self.error.is_some()) {
            return;
        }
        self.source = source.clone();
        self.tracks.clear();
        self.error = None;
        self.glow = None;
        self.header = Header { title: name.unwrap_or_default().into(), ..Default::default() };
        self.list.reset(1);
        match source {
            Source::Liked | Source::Local(_) => self.load_local(cx),
            Source::Album(id) => self.load_album(id, cx),
            Source::Playlist(id) => self.load_playlist(id, cx),
        }
        cx.notify();
    }

    fn set_tracks(&mut self, tracks: Vec<Track>, cx: &mut Context<Self>) {
        self.tracks = tracks;
        self.list.reset(1 + self.tracks.len());
        self.load_glow(cx);
        cx.notify();
    }

    fn load_local(&mut self, cx: &mut Context<Self>) {
        let lib = self.library.read(cx);
        match &self.source {
            Source::Liked => {
                let tracks = tracks_of(&lib.favorites);
                self.header = Header {
                    kind: "Playlist".into(),
                    title: "Brani che ti piacciono".into(),
                    meta: [format!("{} brani", tracks.len()), minutes(&tracks)].into_iter().filter(|s| !s.is_empty()).collect::<Vec<_>>().join(" · ").into(),
                    ..Default::default()
                };
                self.set_tracks(tracks, cx);
            }
            Source::Local(id) => {
                let id = id.clone();
                let found = lib.raw_playlist(&id);
                match found {
                    Some(p) => {
                        let tracks = tracks_of(p.get("tracks").and_then(Value::as_array).map(Vec::as_slice).unwrap_or(&[]));
                        let str_of = |k: &str| p.get(k).and_then(Value::as_str).map(str::to_string);
                        self.header = Header {
                            kind: "Playlist".into(),
                            title: str_of("name").unwrap_or_default().into(),
                            meta: [format!("{} brani", tracks.len()), minutes(&tracks)].into_iter().filter(|s| !s.is_empty()).collect::<Vec<_>>().join(" · ").into(),
                            description: str_of("description").filter(|d| !d.trim().is_empty()).map(Into::into),
                            cover_url: str_of("coverUrl").or_else(|| tracks.first().and_then(|t| t.cover_url.clone())),
                            ..Default::default()
                        };
                        self.set_tracks(tracks, cx);
                    }
                    None => self.error = Some("Questa playlist non c'è più.".into()),
                }
            }
            _ => {}
        }
    }

    fn load_album(&mut self, id: String, cx: &mut Context<Self>) {
        self.loading = true;
        let core = self.core.clone();
        cx.spawn(async move |this, cx| {
            let r = runtime().spawn(async move { lfly_core::pages::album(&core.yt, id).await }).await.map_err(|e| e.to_string()).and_then(|r| r);
            let _ = this.update(cx, |this, cx| {
                this.loading = false;
                match r {
                    Ok(p) => {
                        let meta = [p.year.clone().unwrap_or_default(), format!("{} brani", p.tracks.len()), minutes(&p.tracks)]
                            .into_iter()
                            .filter(|s| !s.is_empty())
                            .collect::<Vec<_>>()
                            .join(" · ");
                        let route = p.artist_id.clone().map(|id| Route::Artist { id, name: p.artist.clone() });
                        this.header = Header {
                            kind: "Album".into(),
                            title: p.title.into(),
                            byline: Some((p.artist.into(), route)),
                            meta: meta.into(),
                            description: None,
                            cover_url: p.cover_url,
                        };
                        this.set_tracks(p.tracks, cx);
                    }
                    Err(e) => this.error = Some(e),
                }
                cx.notify();
            });
        })
        .detach();
    }

    fn load_playlist(&mut self, id: String, cx: &mut Context<Self>) {
        self.loading = true;
        let core = self.core.clone();
        cx.spawn(async move |this, cx| {
            let r = runtime().spawn(async move { lfly_core::pages::playlist(&core.yt, id).await }).await.map_err(|e| e.to_string()).and_then(|r| r);
            let _ = this.update(cx, |this, cx| {
                this.loading = false;
                match r {
                    Ok(p) => {
                        let meta = [format!("{} brani", p.tracks.len()), minutes(&p.tracks)].into_iter().filter(|s| !s.is_empty()).collect::<Vec<_>>().join(" · ");
                        this.header = Header {
                            kind: "Playlist".into(),
                            title: p.title.into(),
                            byline: p.author.map(|a| (a.into(), None)),
                            meta: meta.into(),
                            description: p.description.filter(|d| !d.trim().is_empty()).map(Into::into),
                            cover_url: p.cover_url,
                        };
                        this.set_tracks(p.tracks, cx);
                    }
                    Err(e) => this.error = Some(e),
                }
                cx.notify();
            });
        })
        .detach();
    }

    fn load_glow(&mut self, cx: &mut Context<Self>) {
        let Some(url) = self.header.cover_url.clone() else { return };
        let core = self.core.clone();
        cx.spawn(async move |this, cx| {
            let Some(bytes) = core.fetch(url).await else { return };
            let g = cx.background_executor().spawn(async move { art::glow(&bytes) }).await;
            let _ = this.update(cx, |this, cx| {
                this.glow = g;
                cx.notify();
            });
        })
        .detach();
    }

    fn hero(&mut self, cx: &mut Context<Self>) -> AnyElement {
        let s = self.look.read(cx).scheme.clone();
        let h = self.header.clone();
        let cover = h.cover_url.as_ref().and_then(|u| self.covers.update(cx, |c, cx| c.get(u, cx)));
        let art = (self.width * 0.22).clamp(180., 260.);
        let title_size = (self.width * 0.044).clamp(30., 56.);
        let gap = (self.width * 0.03).clamp(20., 40.);
        let accent = s.accent.hsla(1.);
        let playable = !self.tracks.is_empty();
        let core = self.core.clone();
        let tracks = self.tracks.clone();
        let tracks2 = self.tracks.clone();
        let core2 = self.core.clone();
        let nav = self.nav.clone();
        let liked_art = matches!(self.source, Source::Liked);
        div()
            .relative()
            .pt(px(62. + 28.))
            .px(px(24.))
            .pb(px(28.))
            .flex()
            .items_end()
            .gap(px(gap))
            // The cover's own colours glow behind it and fade into the page.
            .children(self.glow.clone().map(|g| div().absolute().top_0().left_0().right_0().bottom(px(-40.)).child(img(g).size_full().object_fit(ObjectFit::Fill))))
            .child(
                div()
                    .relative()
                    .size(px(art))
                    .flex_none()
                    .rounded(px(12.))
                    .overflow_hidden()
                    .shadow(vec![BoxShadow { color: black().opacity(0.45), offset: point(px(0.), px(18.)), blur_radius: px(48.), spread_radius: px(0.), inset: false }])
                    .when(liked_art, |d| {
                        d.bg(linear_gradient(140., linear_color_stop(crate::theme::mix(s.accent, crate::theme::Rgb::hex(0xffffff), 0.2).hsla(1.), 0.), linear_color_stop(crate::theme::mix(s.accent, crate::theme::Rgb::hex(0x3a0ca3), 0.45).hsla(1.), 1.)))
                            .flex()
                            .items_center()
                            .justify_center()
                            .child(icon("heart-fill", art * 0.33, white()))
                    })
                    .when(!liked_art, |d| d.bg(s.text.opacity(0.08)))
                    .children(cover.map(|i| img(i).size_full().object_fit(ObjectFit::Cover))),
            )
            .child(
                div()
                    .relative()
                    .min_w_0()
                    .pb(px(4.))
                    .flex()
                    .flex_col()
                    .items_start()
                    .gap(px(6.))
                    .child(div().text_size(px(11.)).font_weight(FontWeight::BOLD).text_color(s.text2).child(h.kind.to_uppercase()))
                    .child(div().text_size(px(title_size)).line_height(relative(1.04)).font_weight(FontWeight::EXTRA_BOLD).text_color(s.text).line_clamp(2).child(h.title.clone()))
                    .children(h.byline.clone().map(|(name, route)| {
                        div()
                            .id("byline")
                            .text_size(px(19.))
                            .font_weight(FontWeight::SEMIBOLD)
                            .text_color(accent)
                            .when_some(route, |d, r| d.cursor_pointer().on_click(move |_, _, cx| nav.update(cx, |n, cx| n.go(r.clone(), cx))))
                            .child(name)
                    }))
                    .when(!h.meta.is_empty(), |d| d.child(div().text_size(px(13.)).text_color(s.text2).child(h.meta.clone())))
                    .children(h.description.clone().map(|t| div().max_w(px(680.)).text_size(px(13.)).line_height(relative(1.5)).text_color(s.text2).line_clamp(2).child(t)))
                    .child(
                        div()
                            .mt(px(14.))
                            .flex()
                            .items_center()
                            .gap(px(10.))
                            .child(
                                div()
                                    .id("hero-play")
                                    .h(px(40.))
                                    .min_w(px(124.))
                                    .px(px(22.))
                                    .rounded_full()
                                    .flex()
                                    .items_center()
                                    .justify_center()
                                    .gap(px(8.))
                                    .bg(accent)
                                    .text_color(s.on_accent.hsla(1.))
                                    .text_size(px(14.))
                                    .font_weight(FontWeight::SEMIBOLD)
                                    .when(!playable, |d| d.opacity(0.45))
                                    .when(playable, |d| d.cursor_pointer().on_click(move |_, _, _| core.play(&tracks, 0)))
                                    .child(icon("play", 16., s.on_accent.hsla(1.)))
                                    .child("Riproduci"),
                            )
                            .child(
                                div()
                                    .id("hero-shuffle")
                                    .h(px(40.))
                                    .min_w(px(124.))
                                    .px(px(22.))
                                    .rounded_full()
                                    .flex()
                                    .items_center()
                                    .justify_center()
                                    .gap(px(8.))
                                    .bg(s.glass_tint)
                                    .border_1()
                                    .border_color(s.glass_border)
                                    .text_color(s.text)
                                    .text_size(px(14.))
                                    .font_weight(FontWeight::SEMIBOLD)
                                    .when(!playable, |d| d.opacity(0.45))
                                    .when(playable, |d| {
                                        d.cursor_pointer().hover(|d| d.bg(s.text.opacity(0.12))).on_click(move |_, _, _| {
                                            // collection.ts `shuffle`: play from a random song with shuffle on.
                                            let start = (std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.subsec_nanos()) as usize) % tracks2.len().max(1);
                                            core2.command(lfly_connect::protocol::CMD_SHUFFLE, 1);
                                            core2.play(&tracks2, start);
                                        })
                                    })
                                    .child(icon("shuffle", 16., s.text))
                                    .child("Casuale"),
                            ),
                    ),
            )
            .into_any_element()
    }
}

impl Render for Collection {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let me = cx.entity().downgrade();
        list(self.list.clone(), move |ix, _window, cx| {
            me.update(cx, |this, cx| {
                let s = this.look.read(cx).scheme.clone();
                if ix == 0 {
                    let hero = this.hero(cx);
                    let mut d = div().child(hero);
                    if let Some(e) = &this.error {
                        d = d.child(div().px(px(24.)).py(px(8.)).text_color(s.text2).child(e.clone()));
                    } else if this.loading {
                        d = d.child(div().px(px(24.)).py(px(8.)).text_color(s.text2).child("Carico…"));
                    } else {
                        d = d.child(columns(!matches!(this.source, Source::Album(_)), &s));
                    }
                    return d.into_any_element();
                }
                let i = ix - 1;
                let Some(t) = this.tracks.get(i).cloned() else { return div().into_any_element() };
                let current = this.model.read(cx).current().map(|c| c.id.clone());
                let liked = this.library.read(cx).is_favorite(&t.id);
                let cover = t.cover_url.as_ref().and_then(|u| this.covers.update(cx, |c, cx| c.get(u, cx)));
                let show_album = !matches!(this.source, Source::Album(_));
                let core = this.core.clone();
                let tracks = this.tracks.clone();
                let row = RowData {
                    number: i + 1,
                    title: t.title.clone().into(),
                    artist: t.artist.clone().into(),
                    album: t.album.clone().map(Into::into),
                    duration_ms: t.duration_ms,
                    cover,
                    playing: current.as_deref() == Some(t.id.as_str()),
                    liked,
                    is_video: t.is_video,
                };
                let last = i + 1 == this.tracks.len();
                div()
                    .when(last, |d| d.pb(px(104. + 32.)))
                    .child(track_row(("track", i), row, show_album, &s, move |_, _, _| core.play(&tracks, i)))
                    .into_any_element()
            })
            .unwrap_or_else(|_| div().into_any_element())
        })
        .size_full()
    }
}
