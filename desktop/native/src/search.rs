//! Search (Tauri: pages/Search.svelte): the title and the glass field, the genres to browse
//! before typing, then the top result beside the first songs, the artists, albums and
//! playlists, and more songs. (Still missing: suggestions, the category chips, recent searches.)

use std::sync::Arc;

use gpui_kit::component::input::{Input, InputEvent, InputState};
use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use lfly_core::pages::{Item, SearchResults};
use lfly_yt::Track;

use crate::core::{runtime, Core};
use crate::covers::Covers;
use crate::glass;
use crate::home::{card_shelf, open_item, play_item, CloneItem};
use crate::icons::icon;
use crate::library_model::LibraryModel;
use crate::look::Look;
use crate::nav::Nav;
use crate::player_model::PlayerModel;
use crate::theme::Rgb;
use crate::track_row::{track_row, RowData};

/// Search.svelte `browse`: genre, two gradient colours.
const BROWSE: &[(&str, u32, u32)] = &[
    ("Pop italiano", 0xff5f6d, 0xc2185b),
    ("Hip hop", 0xf7971e, 0xb2401c),
    ("Rock", 0xe53935, 0x5d1010),
    ("Elettronica", 0x00c6ff, 0x0049b7),
    ("Chill", 0x43cea2, 0x185a9d),
    ("Allenamento", 0xf12711, 0xf5af19),
    ("Concentrazione", 0x7f7fd5, 0x3a3a8c),
    ("Anni '80", 0xfc466b, 0x3f5efb),
    ("Jazz", 0xc79081, 0x5b3a29),
    ("Indie", 0xa8e063, 0x2f7336),
    ("Lo-fi", 0xb993d6, 0x5b4a8a),
    ("Festa", 0xff0099, 0x493240),
];

pub struct Search {
    core: Arc<Core>,
    covers: Entity<Covers>,
    look: Entity<Look>,
    nav: Entity<Nav>,
    library: Entity<LibraryModel>,
    model: Entity<PlayerModel>,
    input: Entity<InputState>,
    query: String,
    loading: bool,
    results: Option<Result<SearchResults, String>>,
    /// Play the first song of the next results (LFLY_AUTOPLAY).
    pub autoplay: bool,
    pub width: f32,
    pub page_width: f32,
    _subs: Vec<Subscription>,
}

impl Search {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        core: Arc<Core>,
        covers: Entity<Covers>,
        look: Entity<Look>,
        nav: Entity<Nav>,
        library: Entity<LibraryModel>,
        model: Entity<PlayerModel>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Self {
        let input = cx.new(|cx| InputState::new(window, cx).placeholder("Artisti, brani, album e playlist"));
        let subs = vec![
            cx.subscribe_in(&input, window, |this, state, ev: &InputEvent, _w, cx| {
                if let InputEvent::PressEnter { .. } = ev {
                    let q = state.read(cx).value().to_string();
                    this.run(q, cx);
                }
            }),
            cx.observe(&covers, |_, _, cx| cx.notify()),
            cx.observe(&look, |_, _, cx| cx.notify()),
            cx.subscribe(&model, |_, _, _: &crate::player_model::PlayerEvent, cx| cx.notify()),
        ];
        Self { core, covers, look, nav, library, model, input, query: String::new(), loading: false, results: None, autoplay: false, width: 1100., page_width: 800., _subs: subs }
    }

    pub fn run(&mut self, query: String, cx: &mut Context<Self>) {
        let q = query.trim().to_string();
        if q.is_empty() {
            self.results = None;
            cx.notify();
            return;
        }
        self.query = q.clone();
        self.loading = true;
        cx.notify();
        let core = self.core.clone();
        cx.spawn(async move |this, cx| {
            let c = core.clone();
            let r = runtime().spawn(async move { lfly_core::pages::search(&c.yt, q).await }).await.map_err(|e| e.to_string()).and_then(|r| r);
            let _ = this.update(cx, |this, cx| {
                this.loading = false;
                if let (true, Ok(res)) = (std::mem::take(&mut this.autoplay), &r) {
                    if let Some(first) = res.songs.first() {
                        this.core.play(std::slice::from_ref(first), 0);
                    }
                }
                this.results = Some(r);
                cx.notify();
            });
        })
        .detach();
    }

    fn song_rows(&mut self, key: &str, songs: &[Track], show_album: bool, cx: &mut Context<Self>) -> Vec<AnyElement> {
        let s = self.look.read(cx).scheme.clone();
        let current = self.model.read(cx).current().map(|t| t.id.clone());
        songs
            .iter()
            .enumerate()
            .map(|(i, t)| {
                let cover = t.cover_url.as_ref().and_then(|u| self.covers.update(cx, |c, cx| c.get(u, cx)));
                let liked = self.library.read(cx).is_favorite(&t.id);
                let core = self.core.clone();
                let track = t.clone();
                track_row(
                    SharedString::from(format!("{key}-{i}")),
                    RowData {
                        number: i + 1,
                        title: t.title.clone().into(),
                        artist: t.artist.clone().into(),
                        album: t.album.clone().map(Into::into),
                        duration_ms: t.duration_ms,
                        cover,
                        playing: current.as_deref() == Some(t.id.as_str()),
                        liked,
                        is_video: t.is_video,
                        menu: crate::core::track_ref(t).map(|r| (r, None)),
                        like: crate::core::track_ref(t).map(|r| crate::menu::like_action(self.library.clone(), self.core.clone(), r)),
                    },
                    show_album,
                    &s,
                    // Search.svelte playSong: the song, then its radio.
                    move |_, _, _| core.play_with_radio(track.clone()),
                )
                .into_any_element()
            })
            .collect()
    }
}

fn section_title(t: &'static str) -> Div {
    div().mb(px(12.)).text_size(px(22.)).font_weight(FontWeight::BOLD).child(t)
}

impl Render for Search {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let (s, look) = {
            let l = self.look.read(cx);
            (l.scheme.clone(), l.glass())
        };
        let w = self.width;
        let field = div()
            .relative()
            .max_w(px(640.))
            .h(px(54.))
            .rounded_full()
            .child(glass::glass_layer(look, px(27.), glass::Pointer::default()))
            .child(
                div()
                    .relative()
                    .size_full()
                    .flex()
                    .items_center()
                    .gap(px(12.))
                    .pl(px(20.))
                    .pr(px(10.))
                    .text_size(px(17.))
                    .child(icon("search", 20., s.text2))
                    .child(div().flex_1().child(Input::new(&self.input).appearance(false).cleanable(true)))
                    .when(self.loading, |d| d.child(div().text_size(px(12.)).text_color(s.text3).child("Cerco…"))),
            );
        let head = div()
            .pt(px(62. + 20.))
            .px(px(24.))
            .pb(px(20.))
            .flex()
            .flex_col()
            .gap(px(16.))
            .child(div().text_size(px((w * 0.036).clamp(30., 44.))).font_weight(FontWeight::EXTRA_BOLD).child("Cerca"))
            .child(field);

        let mut body: Vec<AnyElement> = Vec::new();
        match &self.results {
            None => {
                // Browse: genre tiles (`.grid` auto-fill minmax(170px, 1fr), gap 8).
                let avail = (self.page_width - 48.).max(170.);
                let cols = ((avail + 8.) / 178.).floor().max(1.);
                let tile_w = ((avail - 8. * (cols - 1.)) / cols).floor();
                let me = cx.entity().downgrade();
                let tiles: Vec<AnyElement> = BROWSE
                    .iter()
                    .map(|&(q, c1, c2)| {
                        let me = me.clone();
                        div()
                            .id(q)
                            .w(px(tile_w))
                            .h(px(96.))
                            .p(px(14.))
                            .rounded(px(14.))
                            .bg(linear_gradient(135., linear_color_stop(Rgb::hex(c1).hsla(1.), 0.), linear_color_stop(Rgb::hex(c2).hsla(1.), 1.)))
                            .text_color(white())
                            .text_size(px(18.))
                            .font_weight(FontWeight::EXTRA_BOLD)
                            .cursor_pointer()
                            .on_click(move |_, window, cx| {
                                let _ = me.update(cx, |this, cx| {
                                    this.input.update(cx, |i, cx| i.set_value(q, window, cx));
                                    this.run(q.to_string(), cx);
                                });
                            })
                            .child(q)
                            .into_any_element()
                    })
                    .collect();
                body.push(
                    div()
                        .px(px(24.))
                        .pb(px(32.))
                        .child(section_title("Sfoglia"))
                        .child(div().flex().flex_wrap().gap(px(8.)).children(tiles))
                        .into_any_element(),
                );
            }
            Some(Err(e)) => body.push(
                div()
                    .px(px(24.))
                    .flex()
                    .flex_col()
                    .gap(px(6.))
                    .child(div().font_weight(FontWeight::BOLD).child("La ricerca non ha funzionato"))
                    .child(div().text_color(s.text2).child(e.clone()))
                    .into_any_element(),
            ),
            Some(Ok(r)) => {
                let r = SearchResults {
                    top: r.top.as_ref().map(|i| i.clone_item()),
                    songs: r.songs.clone(),
                    albums: r.albums.iter().map(|i| i.clone_item()).collect(),
                    artists: r.artists.iter().map(|i| i.clone_item()).collect(),
                    playlists: r.playlists.iter().map(|i| i.clone_item()).collect(),
                };
                if r.top.is_none() && r.songs.is_empty() && r.albums.is_empty() && r.artists.is_empty() && r.playlists.is_empty() {
                    body.push(
                        div()
                            .px(px(24.))
                            .flex()
                            .flex_col()
                            .gap(px(6.))
                            .child(div().font_weight(FontWeight::BOLD).child(format!("Nessun risultato per “{}”", self.query)))
                            .child(div().text_color(s.text2).child("Controlla come è scritto o prova con altre parole."))
                            .into_any_element(),
                    );
                } else {
                    // `.top-row`: the top result (minmax(280px, 2fr)) beside five songs (3fr).
                    let mut top_row = div().px(px(24.)).pb(px(32.)).flex().gap(px(28.));
                    if let Some(top) = r.top.as_ref().map(|t| t.clone_item()) {
                        let cover = top.cover_url.as_ref().and_then(|u| self.covers.update(cx, |c, cx| c.get(u, cx)));
                        let round = top.kind == "artist";
                        let kind = match top.kind {
                            "song" => "Brano",
                            "album" => "Album",
                            "playlist" => "Playlist",
                            _ => "Artista",
                        };
                        let (open, play) = (top.clone_item(), top.clone_item());
                        let (core, core2, nav) = (self.core.clone(), self.core.clone(), self.nav.clone());
                        let accent = s.accent.hsla(1.);
                        top_row = top_row.child(
                            div()
                                .flex_grow(2.)
                                .flex_basis(relative(0.))
                                .min_w(px(280.))
                                .child(section_title("Risultato principale"))
                                .child(
                                    div()
                                        .id("top")
                                        .group("top")
                                        .relative()
                                        .min_h(px(250.))
                                        .p(px(22.))
                                        .rounded(px(18.))
                                        .bg(s.surface.hsla(0.65))
                                        .hover(|d| d.bg(s.surface2.hsla(0.8)))
                                        .cursor_pointer()
                                        .flex()
                                        .flex_col()
                                        .justify_end()
                                        .gap(px(18.))
                                        .on_click(move |_, _, cx| open_item(&open, &core, &nav, cx))
                                        .child(
                                            div()
                                                .size(px(112.))
                                                .rounded(px(if round { 56. } else { 12. }))
                                                .overflow_hidden()
                                                .bg(s.text.opacity(0.08))
                                                .children(cover.map(|i| img(i).size_full().object_fit(ObjectFit::Cover).rounded(px(if round { 56. } else { 12. })))),
                                        )
                                        .child(
                                            div()
                                                .flex()
                                                .flex_col()
                                                .gap(px(8.))
                                                .child(div().text_size(px(30.)).line_height(relative(1.1)).font_weight(FontWeight::EXTRA_BOLD).line_clamp(2).child(top.title.clone()))
                                                .child(
                                                    div()
                                                        .flex()
                                                        .items_center()
                                                        .text_size(px(14.))
                                                        .text_color(s.text2)
                                                        .child(div().mr(px(8.)).px(px(10.)).py(px(3.)).rounded_full().bg(s.glass_tint).text_size(px(12.)).font_weight(FontWeight::BOLD).text_color(s.text).child(kind))
                                                        .child(top.subtitle.clone().unwrap_or_default()),
                                                ),
                                        )
                                        .when(!round, |d| {
                                            d.child(
                                                div()
                                                    .id("top-play")
                                                    .absolute()
                                                    .right(px(22.))
                                                    .bottom(px(22.))
                                                    .size(px(54.))
                                                    .rounded_full()
                                                    .flex()
                                                    .items_center()
                                                    .justify_center()
                                                    .pl(px(3.))
                                                    .bg(accent)
                                                    .invisible()
                                                    .group_hover("top", |d| d.visible())
                                                    .on_click(move |_, _, cx| {
                                                        cx.stop_propagation();
                                                        play_item(&play, &core2, cx)
                                                    })
                                                    .child(icon("play", 22., s.on_accent.hsla(1.))),
                                            )
                                        }),
                                ),
                        );
                    }
                    if !r.songs.is_empty() {
                        let first: Vec<Track> = r.songs.iter().take(5).cloned().collect();
                        let rows = self.song_rows("top-songs", &first, false, cx);
                        top_row = top_row.child(
                            div()
                                .flex_grow(3.)
                                .flex_basis(relative(0.))
                                .min_w_0()
                                .child(section_title("Brani"))
                                .child(div().mx(px(-16.)).children(rows)),
                        );
                    }
                    body.push(top_row.into_any_element());
                    let card_w = (w * 0.15).clamp(150., 196.);
                    let nav = self.nav.clone();
                    for (key, label, items) in [("s-artists", "Artisti", &r.artists), ("s-albums", "Album", &r.albums), ("s-playlists", "Playlist", &r.playlists)] {
                        let items: Vec<Item> = items.iter().map(|i| i.clone_item()).collect();
                        if let Some(shelf) = card_shelf(key, label, &items, card_w, &self.covers, &s, &self.core, &nav, cx) {
                            body.push(shelf);
                        }
                    }
                    if r.songs.len() > 5 {
                        let more: Vec<Track> = r.songs.iter().skip(5).take(15).cloned().collect();
                        let rows = self.song_rows("more-songs", &more, true, cx);
                        body.push(div().px(px(24.)).pb(px(32.)).child(section_title("Altri brani")).child(div().mx(px(-16.)).children(rows)).into_any_element());
                    }
                }
            }
        }

        div().id("search").size_full().overflow_y_scroll().pb(px(104.)).child(head).children(body)
    }
}
