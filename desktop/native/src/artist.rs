//! An artist (Tauri: pages/Artist.svelte): the banner with the name, Play and Shuffle, popular
//! songs, albums, singles, related artists and the about card.

use std::sync::Arc;

use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use lfly_core::pages::ArtistPage;

use crate::art;
use crate::core::{runtime, Core};
use crate::covers::Covers;
use crate::home::card_shelf;
use crate::icons::icon;
use crate::library_model::LibraryModel;
use crate::look::Look;
use crate::nav::Nav;
use crate::player_model::PlayerModel;
use crate::track_row::{track_row, RowData};

pub struct Artist {
    core: Arc<Core>,
    covers: Entity<Covers>,
    look: Entity<Look>,
    nav: Entity<Nav>,
    library: Entity<LibraryModel>,
    model: Entity<PlayerModel>,
    id: String,
    name: String,
    page: Option<Result<ArtistPage, String>>,
    banner: Option<Arc<Image>>,
    all: bool,
    pub width: f32,
    pub height: f32,
    _subs: Vec<Subscription>,
}

impl Artist {
    #[allow(clippy::too_many_arguments)]
    pub fn new(core: Arc<Core>, covers: Entity<Covers>, look: Entity<Look>, nav: Entity<Nav>, library: Entity<LibraryModel>, model: Entity<PlayerModel>, cx: &mut Context<Self>) -> Self {
        let subs = vec![
            cx.observe(&covers, |_, _, cx| cx.notify()),
            cx.observe(&look, |_, _, cx| cx.notify()),
            cx.subscribe(&model, |_, _, _: &crate::player_model::PlayerEvent, cx| cx.notify()),
        ];
        Self { core, covers, look, nav, library, model, id: String::new(), name: String::new(), page: None, banner: None, all: false, width: 1100., height: 720., _subs: subs }
    }

    pub fn show(&mut self, id: String, name: String, cx: &mut Context<Self>) {
        if self.id == id {
            return;
        }
        self.id = id.clone();
        self.name = name;
        self.page = None;
        self.banner = None;
        self.all = false;
        let core = self.core.clone();
        cx.spawn(async move |this, cx| {
            let c = core.clone();
            let r = runtime().spawn(async move { lfly_core::pages::artist(&c.yt, id).await }).await.map_err(|e| e.to_string()).and_then(|r| r);
            let cover = r.as_ref().ok().and_then(|p| p.cover_url.clone());
            let _ = this.update(cx, |this, cx| {
                this.page = Some(r);
                cx.notify();
            });
            // connect.svelte.ts cover(url, 1200): the banner large.
            if let Some(url) = cover {
                let url = url.replace("=w544-h544", "=w1200-h1200");
                let Some(bytes) = core.fetch(url).await else { return };
                let aspect = this.update(cx, |this, _| this.width / this.banner_h()).unwrap_or(2.);
                let b = cx.background_executor().spawn(async move { art::banner(&bytes, aspect) }).await;
                let _ = this.update(cx, |this, cx| {
                    this.banner = b;
                    cx.notify();
                });
            }
        })
        .detach();
    }

    /// clamp(300px, 48vh, 500px)
    fn banner_h(&self) -> f32 {
        (self.height * 0.48).clamp(300., 500.)
    }
}

impl Render for Artist {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let s = self.look.read(cx).scheme.clone();
        let title: SharedString = match &self.page {
            Some(Ok(p)) => p.name.clone().into(),
            _ => self.name.clone().into(),
        };
        let has_art = self.banner.is_some();
        let on_art = |c: Hsla| if has_art { white().opacity(0.78) } else { c };
        let accent = s.accent.hsla(1.);
        let card_w = (self.width * 0.15).clamp(150., 196.);
        let songs = match &self.page {
            Some(Ok(p)) => p.songs.clone(),
            _ => Vec::new(),
        };
        let (core, core2) = (self.core.clone(), self.core.clone());
        let (s1, s2) = (songs.clone(), songs.clone());

        let mut body: Vec<AnyElement> = Vec::new();
        match &self.page {
            None => body.push(div().px(px(24.)).text_color(s.text2).child("Carico…").into_any_element()),
            Some(Err(e)) => body.push(div().px(px(24.)).text_color(s.text2).child(e.clone()).into_any_element()),
            Some(Ok(p)) => {
                let p = ArtistPage {
                    name: p.name.clone(),
                    cover_url: p.cover_url.clone(),
                    description: p.description.clone(),
                    subscribers: p.subscribers.clone(),
                    songs: p.songs.clone(),
                    albums: p.albums.iter().map(crate::home::CloneItem::clone_item).collect(),
                    singles: p.singles.iter().map(crate::home::CloneItem::clone_item).collect(),
                    related: p.related.iter().map(crate::home::CloneItem::clone_item).collect(),
                };
                if !p.songs.is_empty() {
                    let current = self.model.read(cx).current().map(|t| t.id.clone());
                    let lib = self.library.read(cx);
                    let liked: Vec<bool> = p.songs.iter().map(|t| lib.is_favorite(&t.id)).collect();
                    let n = if self.all { 16 } else { 6 };
                    let rows: Vec<AnyElement> = p
                        .songs
                        .iter()
                        .take(n)
                        .enumerate()
                        .map(|(i, t)| {
                            let cover = t.cover_url.as_ref().and_then(|u| self.covers.update(cx, |c, cx| c.get(u, cx)));
                            let core = self.core.clone();
                            let all = p.songs.clone();
                            track_row(
                                ("pop", i),
                                RowData {
                                    number: i + 1,
                                    title: t.title.clone().into(),
                                    artist: t.artist.clone().into(),
                                    album: None,
                                    duration_ms: t.duration_ms,
                                    cover,
                                    playing: current.as_deref() == Some(t.id.as_str()),
                                    liked: liked[i],
                                    is_video: t.is_video,
                                    menu: crate::core::track_ref(t).map(|r| (r, None)),
                                    like: crate::core::track_ref(t).map(|r| crate::menu::like_action(self.library.clone(), self.core.clone(), r)),
                                },
                                false,
                                &s,
                                move |_, _, _| core.play(&all, i),
                            )
                            .into_any_element()
                        })
                        .collect();
                    let me = cx.entity().downgrade();
                    let all_on = self.all;
                    body.push(
                        div()
                            .pt(px(8.))
                            .pb(px(28.))
                            .child(
                                div()
                                    .px(px(24.))
                                    .mb(px(12.))
                                    .flex()
                                    .items_baseline()
                                    .justify_between()
                                    .child(div().text_size(px(22.)).font_weight(FontWeight::BOLD).child("Brani popolari"))
                                    .when(p.songs.len() > 6, |d| {
                                        d.child(
                                            div()
                                                .id("see")
                                                .text_size(px(13.))
                                                .font_weight(FontWeight::SEMIBOLD)
                                                .text_color(accent)
                                                .cursor_pointer()
                                                .on_click(move |_, _, cx| {
                                                    let _ = me.update(cx, |this, cx| {
                                                        this.all = !this.all;
                                                        cx.notify();
                                                    });
                                                })
                                                .child(if all_on { "Mostra meno" } else { "Mostra altro" }),
                                        )
                                    }),
                            )
                            .child(div().mx(px(-8.)).children(rows))
                            .into_any_element(),
                    );
                }
                let nav = self.nav.clone();
                for (key, label, items) in [("albums", "Album", &p.albums), ("singles", "Singoli ed EP", &p.singles), ("related", "Ai fan piacciono anche", &p.related)] {
                    if let Some(shelf) = card_shelf(key, label, items, card_w, &self.covers, &s, &self.core, &nav, cx) {
                        body.push(shelf);
                    }
                }
                if let Some(desc) = p.description.clone() {
                    let cover = p.cover_url.as_ref().and_then(|u| self.covers.update(cx, |c, cx| c.get(u, cx)));
                    body.push(
                        div()
                            .px(px(24.))
                            .pt(px(8.))
                            .pb(px(28.))
                            .child(div().mb(px(12.)).text_size(px(22.)).font_weight(FontWeight::BOLD).child("Informazioni"))
                            .child(
                                div()
                                    .p(px(18.))
                                    .rounded(px(22.))
                                    .bg(s.glass_tint)
                                    .border_1()
                                    .border_color(s.glass_border)
                                    .flex()
                                    .gap(px(18.))
                                    .child(div().size(px(120.)).flex_none().rounded_full().overflow_hidden().bg(s.text.opacity(0.08)).children(cover.map(|i| img(i).size_full().object_fit(ObjectFit::Cover).rounded_full())))
                                    .child(
                                        div()
                                            .min_w_0()
                                            .flex()
                                            .flex_col()
                                            .gap(px(4.))
                                            .child(div().font_weight(FontWeight::BOLD).child(p.name.clone()))
                                            .children(p.subscribers.clone().map(|x| div().text_size(px(13.)).text_color(s.text2).child(x)))
                                            .child(div().text_size(px(13.)).line_height(relative(1.5)).text_color(s.text2).line_clamp(4).child(desc)),
                                    ),
                            )
                            .into_any_element(),
                    );
                }
            }
        }

        div()
            .id("artist")
            .size_full()
            .overflow_y_scroll()
            .pb(px(104.))
            .child(
                div()
                    .relative()
                    .h(px(self.banner_h()))
                    .mb(px(12.))
                    .flex()
                    .items_end()
                    .children(self.banner.clone().map(|b| div().absolute().inset_0().child(img(b).size_full().object_fit(ObjectFit::Fill))))
                    .child(
                        div()
                            .relative()
                            .w_full()
                            .px(px(24.))
                            .pb(px(8.))
                            .flex()
                            .flex_col()
                            .gap(px(4.))
                            .child(div().text_size(px(11.)).font_weight(FontWeight::BOLD).text_color(on_art(s.text2)).child("ARTISTA"))
                            .child(
                                div()
                                    .text_size(px((self.width * 0.07).clamp(40., 92.)))
                                    .line_height(relative(0.98))
                                    .font_weight(FontWeight::EXTRA_BOLD)
                                    .text_color(if has_art { white() } else { s.text })
                                    .truncate()
                                    .child(title),
                            )
                            .children(match &self.page {
                                Some(Ok(p)) => p.subscribers.clone().map(|x| div().mt(px(2.)).text_size(px(14.)).text_color(on_art(s.text2)).child(x)),
                                _ => None,
                            })
                            .when(!songs.is_empty(), |d| {
                                d.child(
                                    div()
                                        .mt(px(16.))
                                        .flex()
                                        .items_center()
                                        .gap(px(12.))
                                        .child(
                                            div()
                                                .id("a-play")
                                                .size(px(56.))
                                                .rounded_full()
                                                .flex()
                                                .items_center()
                                                .justify_center()
                                                .pl(px(3.))
                                                .bg(accent)
                                                .cursor_pointer()
                                                .on_click(move |_, _, _| core.play(&s1, 0))
                                                .child(icon("play", 22., s.on_accent.hsla(1.))),
                                        )
                                        .child(
                                            div()
                                                .id("a-shuffle")
                                                .size(px(44.))
                                                .rounded_full()
                                                .flex()
                                                .items_center()
                                                .justify_center()
                                                .bg(s.glass_tint)
                                                .border_1()
                                                .border_color(s.glass_border)
                                                .cursor_pointer()
                                                .on_click(move |_, _, _| {
                                                    core2.command(lfly_connect::protocol::CMD_SHUFFLE, 1);
                                                    core2.play(&s2, 0);
                                                })
                                                .child(icon("shuffle", 19., if has_art { white() } else { s.text })),
                                        ),
                                )
                            }),
                    ),
            )
            .children(body)
    }
}
