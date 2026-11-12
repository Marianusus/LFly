//! Search (Tauri: pages/Search.svelte): the title and the glass field, the genres to browse
//! before typing, then the top result beside the first songs, the artists, albums and
//! playlists, and more songs; suggestions while typing, the category chips, recent searches.

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

#[derive(Clone, Copy, PartialEq)]
enum Cat {
    All,
    Songs,
    Videos,
    Artists,
    Albums,
    Playlists,
}

/// The searches and results opened lately (recents.ts), kept by this window under its own key.
const RECENTS: &str = "native-search-recents";

fn kind_static(k: &str) -> Option<&'static str> {
    ["song", "album", "playlist", "artist"].into_iter().find(|x| *x == k)
}

/// A saved recent as an item, when it is one.
fn recent_item(v: &serde_json::Value) -> Option<Item> {
    let i = v.get("item")?;
    Some(Item {
        kind: kind_static(i.get("kind")?.as_str()?)?,
        id: i.get("id")?.as_str()?.to_string(),
        title: i.get("title")?.as_str()?.to_string(),
        subtitle: i.get("subtitle").and_then(|v| v.as_str()).map(str::to_string),
        cover_url: i.get("coverUrl").and_then(|v| v.as_str()).map(str::to_string),
        track: i.get("track").and_then(|t| serde_json::from_value(t.clone()).ok()),
    })
}

fn same_recent(a: &serde_json::Value, b: &serde_json::Value) -> bool {
    match (a.get("query").and_then(|v| v.as_str()), b.get("query").and_then(|v| v.as_str())) {
        (Some(x), Some(y)) => x.to_lowercase() == y.to_lowercase(),
        (None, None) => {
            let k = |v: &serde_json::Value, f: &str| v.get("item").and_then(|i| i.get(f)).and_then(|x| x.as_str()).map(str::to_string);
            k(a, "kind") == k(b, "kind") && k(a, "id") == k(b, "id")
        }
        _ => false,
    }
}

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
    /// Which run the results belong to (a later one wins).
    seq: u64,
    suggestions: Vec<String>,
    focused: bool,
    cat: Cat,
    recents: Vec<serde_json::Value>,
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
            cx.subscribe_in(&input, window, |this, state, ev: &InputEvent, _w, cx| match ev {
                InputEvent::PressEnter { .. } => {
                    let q = state.read(cx).value().to_string();
                    this.pick(q, cx);
                }
                InputEvent::Change => {
                    let q = state.read(cx).value().to_string();
                    this.typed(q, cx);
                }
                InputEvent::Focus => {
                    this.focused = true;
                    cx.notify();
                }
                InputEvent::Blur => {
                    // A moment later, so a click on a suggestion lands first.
                    cx.spawn(async move |this, cx| {
                        cx.background_executor().timer(std::time::Duration::from_millis(150)).await;
                        let _ = this.update(cx, |this, cx| {
                            this.focused = false;
                            cx.notify();
                        });
                    })
                    .detach();
                }
            }),
            cx.observe(&covers, |_, _, cx| cx.notify()),
            cx.observe(&library, |_, _, cx| cx.notify()),
            cx.observe(&look, |_, _, cx| cx.notify()),
            cx.subscribe(&model, |_, _, _: &crate::player_model::PlayerEvent, cx| cx.notify()),
        ];
        let recents = lfly_core::store::get(RECENTS).ok().flatten().and_then(|v| v.as_array().cloned()).unwrap_or_default();
        Self {
            core,
            covers,
            look,
            nav,
            library,
            model,
            input,
            query: String::new(),
            loading: false,
            seq: 0,
            suggestions: Vec::new(),
            focused: false,
            cat: Cat::All,
            recents,
            results: None,
            autoplay: false,
            width: 1100.,
            page_width: 800.,
            _subs: subs,
        }
    }

    fn save_recents(&self) {
        if let Err(e) = lfly_core::store::set(RECENTS, &serde_json::Value::Array(self.recents.clone())) {
            log::warn!("{RECENTS}: {e}");
        }
    }

    /// recents.ts addRecent: first, without repeats, twelve at most.
    fn add_recent(&mut self, r: serde_json::Value) {
        self.recents.retain(|x| !same_recent(x, &r));
        self.recents.insert(0, r);
        self.recents.truncate(12);
        self.save_recents();
    }

    fn remember_item(&mut self, item: &Item) {
        if let Ok(v) = serde_json::to_value(item) {
            self.add_recent(serde_json::json!({ "item": v }));
        }
    }

    /// onInput: the results follow the typing (350 ms after the last key), the suggestions too.
    fn typed(&mut self, q: String, cx: &mut Context<Self>) {
        if q.trim().is_empty() {
            self.seq += 1;
            self.results = None;
            self.suggestions.clear();
            self.loading = false;
            cx.notify();
            return;
        }
        self.seq += 1;
        let mine = self.seq;
        let q2 = q.clone();
        cx.spawn(async move |this, cx| {
            cx.background_executor().timer(std::time::Duration::from_millis(350)).await;
            let _ = this.update(cx, |this, cx| {
                if this.seq == mine {
                    this.run(q2, cx);
                }
            });
        })
        .detach();
        if q.trim().chars().count() > 1 {
            let core = self.core.clone();
            cx.spawn(async move |this, cx| {
                let q3 = q.clone();
                let got = runtime().spawn(async move { lfly_core::pages::suggestions(&core.yt, q3).await }).await.ok().and_then(|r| r.ok());
                let _ = this.update(cx, |this, cx| {
                    if let Some(s) = got {
                        if this.input.read(cx).value() == q.as_str() {
                            this.suggestions = s.into_iter().take(6).collect();
                            cx.notify();
                        }
                    }
                });
            })
            .detach();
        } else {
            self.suggestions.clear();
        }
    }

    /// pick: a query chosen (Enter, a suggestion, a genre, a recent search).
    fn pick(&mut self, q: String, cx: &mut Context<Self>) {
        self.suggestions.clear();
        self.cat = Cat::All;
        if !q.trim().is_empty() {
            self.add_recent(serde_json::json!({ "query": q.trim() }));
        }
        self.run(q, cx);
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
        self.seq += 1;
        let mine = self.seq;
        cx.notify();
        let core = self.core.clone();
        cx.spawn(async move |this, cx| {
            let c = core.clone();
            let r = runtime().spawn(async move { lfly_core::pages::search(&c.yt, q).await }).await.map_err(|e| e.to_string()).and_then(|r| r);
            let _ = this.update(cx, |this, cx| {
                if this.seq != mine {
                    return;
                }
                this.loading = false;
                if let (true, Ok(res)) = (std::mem::take(&mut this.autoplay), &r) {
                    // The whole list from the first song, so the queue has rows to show.
                    if !res.songs.is_empty() {
                        this.core.play(&res.songs, 0);
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
                let me = cx.entity().downgrade();
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
                    // Search.svelte playSong: the song, then its radio; it joins the recents.
                    move |_, _, cx| {
                        let item = Item {
                            kind: "song",
                            id: track.id.clone(),
                            title: track.title.clone(),
                            subtitle: Some(track.artist.clone()),
                            cover_url: track.cover_url.clone(),
                            track: Some(track.clone()),
                        };
                        let _ = me.update(cx, |this, _| this.remember_item(&item));
                        core.play_with_radio(track.clone())
                    },
                )
                .into_any_element()
            })
            .collect()
    }
}

impl Search {
    /// Ricerche recenti: the queries and the results opened, with a cross to forget each.
    fn recents_section(&mut self, s: &crate::theme::Scheme, cx: &mut Context<Self>) -> AnyElement {
        // `.recent-list`: repeat(auto-fill, minmax(280px, 1fr)), gaps 4 and 16.
        let avail = (self.page_width - 48.).max(280.);
        let cols = ((avail + 16.) / 296.).floor().max(1.);
        let w = ((avail - 16. * (cols - 1.)) / cols).floor();
        let me = cx.entity().downgrade();
        let kind_name = |k: &str| match k {
            "song" => "Brano",
            "album" => "Album",
            "playlist" => "Playlist",
            _ => "Artista",
        };
        let rows: Vec<AnyElement> = self
            .recents
            .clone()
            .into_iter()
            .enumerate()
            .filter_map(|(i, r)| {
                let group: SharedString = format!("recent-{i}").into();
                let (me1, me2) = (me.clone(), me.clone());
                let (lead, title, sub): (AnyElement, String, String) = if let Some(q) = r.get("query").and_then(|v| v.as_str()) {
                    (
                        div().size(px(48.)).flex_none().rounded(px(10.)).flex().items_center().justify_center().bg(s.glass_tint).child(icon("search", 18., s.text2)).into_any_element(),
                        q.to_string(),
                        "Ricerca".to_string(),
                    )
                } else {
                    let it = recent_item(&r)?;
                    let round = it.kind == "artist";
                    let rad = if round { 24. } else { 8. };
                    let cover = it.cover_url.as_ref().and_then(|u| self.covers.update(cx, |c, cx| c.get(u, cx)));
                    (
                        div().size(px(48.)).flex_none().rounded(px(rad)).bg(s.text.opacity(0.08)).children(cover.map(|im| img(im).size_full().object_fit(ObjectFit::Cover).rounded(px(rad)))).into_any_element(),
                        it.title.clone(),
                        format!("{}{}", kind_name(it.kind), it.subtitle.as_ref().map_or(String::new(), |x| format!(" · {x}"))),
                    )
                };
                let (core, nav) = (self.core.clone(), self.nav.clone());
                let r2 = r.clone();
                Some(
                    div()
                        .id(("recent", i))
                        .group(group.clone())
                        .w(px(w))
                        .flex()
                        .items_center()
                        .rounded(px(12.))
                        .hover(|d| d.bg(s.glass_tint))
                        .child(
                            div()
                                .id(("recent-open", i))
                                .flex_1()
                                .min_w_0()
                                .flex()
                                .items_center()
                                .gap(px(12.))
                                .p(px(8.))
                                .cursor_pointer()
                                .on_click(move |_, window, cx| {
                                    let r = r.clone();
                                    let _ = me1.update(cx, |this, cx| {
                                        if let Some(q) = r.get("query").and_then(|v| v.as_str()).map(str::to_string) {
                                            this.input.update(cx, |i, cx| i.set_value(q.clone(), window, cx));
                                            this.pick(q, cx);
                                        } else if let Some(it) = recent_item(&r) {
                                            this.remember_item(&it);
                                            open_item(&it, &core, &nav, cx);
                                        }
                                    });
                                })
                                .child(lead)
                                .child(
                                    div()
                                        .min_w_0()
                                        .flex()
                                        .flex_col()
                                        .gap(px(2.))
                                        .child(div().text_size(px(14.)).font_weight(FontWeight::MEDIUM).truncate().child(title))
                                        .child(div().text_size(px(12.)).text_color(s.text2).truncate().child(sub)),
                                ),
                        )
                        .child(
                            div()
                                .id(("recent-x", i))
                                .size(px(32.))
                                .mr(px(8.))
                                .flex_none()
                                .rounded_full()
                                .flex()
                                .items_center()
                                .justify_center()
                                .cursor_pointer()
                                .invisible()
                                .group_hover(group, |d| d.visible())
                                .hover(|d| d.bg(s.glass_tint))
                                .on_click(move |_, _, cx| {
                                    let r2 = r2.clone();
                                    let _ = me2.update(cx, |this, cx| {
                                        this.recents.retain(|x| !same_recent(x, &r2));
                                        this.save_recents();
                                        cx.notify();
                                    });
                                })
                                .child(icon("close", 14., s.text3)),
                        )
                        .into_any_element(),
                )
            })
            .collect();
        let me3 = me.clone();
        div()
            .px(px(24.))
            .pb(px(32.))
            .child(
                div()
                    .flex()
                    .items_baseline()
                    .justify_between()
                    .child(section_title("Ricerche recenti"))
                    .child(
                        div()
                            .id("recents-clear")
                            .text_size(px(13.))
                            .font_weight(FontWeight::SEMIBOLD)
                            .text_color(s.accent.hsla(1.))
                            .cursor_pointer()
                            .hover(|d| d.underline())
                            .on_click(move |_, _, cx| {
                                let _ = me3.update(cx, |this, cx| {
                                    this.recents.clear();
                                    this.save_recents();
                                    cx.notify();
                                });
                            })
                            .child("Cancella"),
                    ),
            )
            .child(div().flex().flex_wrap().gap_x(px(16.)).gap_y(px(4.)).children(rows))
            .into_any_element()
    }
}

fn section_title(t: &'static str) -> Div {
    div().mb(px(12.)).text_size(px(22.)).font_weight(FontWeight::BOLD).child(t)
}

impl Search {
    /// The search field has the keyboard: shortcuts leave the keys to it.
    pub fn typing(&self, window: &Window, cx: &App) -> bool {
        self.input.read(cx).focus_handle(cx).is_focused(window)
    }

    /// Ctrl+K: the field takes the keyboard.
    pub fn field_focus(&self, cx: &App) -> FocusHandle {
        self.input.read(cx).focus_handle(cx)
    }
}

impl Render for Search {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let (s, look) = {
            let l = self.look.read(cx);
            (l.scheme.clone(), l.glass())
        };
        let w = self.width;
        let accent = s.accent.hsla(1.);
        let focused = self.focused;
        let field = div()
            .relative()
            .max_w(px(640.))
            .h(px(54.))
            .rounded_full()
            // Focused: an accent ring and a deeper shadow (`.search-field.focused`).
            .when(focused, |d| {
                d.shadow(vec![
                    BoxShadow { color: accent.opacity(0.7), offset: point(px(0.), px(0.)), blur_radius: px(0.), spread_radius: px(2.), inset: false },
                    BoxShadow { color: black().opacity(0.35), offset: point(px(0.), px(14.)), blur_radius: px(40.), spread_radius: px(0.), inset: false },
                ])
            })
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
        // The suggestions under the field, over everything below (`.sugg`).
        let sugg = (focused && !self.suggestions.is_empty()).then(|| {
            let me = cx.entity().downgrade();
            let mut sg = look;
            sg.tint = s.surface.hsla(0.82);
            deferred(
                div()
                    .absolute()
                    .left_0()
                    .right_0()
                    .top(px(54. + 8.))
                    .rounded(px(20.))
                    .occlude()
                    .shadow(vec![BoxShadow { color: black().opacity(0.45), offset: point(px(0.), px(18.)), blur_radius: px(48.), spread_radius: px(0.), inset: false }])
                    .child(glass::glass_layer(sg, px(20.), glass::Pointer::default()))
                    .child(div().relative().p(px(6.)).flex().flex_col().children(self.suggestions.iter().enumerate().map(|(i, q)| {
                        let (me, q2) = (me.clone(), q.clone());
                        div()
                            .id(("sugg", i))
                            .flex()
                            .items_center()
                            .gap(px(12.))
                            .py(px(10.))
                            .px(px(14.))
                            .rounded(px(14.))
                            .text_size(px(14.))
                            .cursor_pointer()
                            .hover(|d| d.bg(s.glass_tint))
                            .on_mouse_down(MouseButton::Left, move |_, window, cx| {
                                cx.stop_propagation();
                                let q = q2.clone();
                                let _ = me.update(cx, |this, cx| {
                                    this.input.update(cx, |i, cx| i.set_value(q.clone(), window, cx));
                                    this.pick(q, cx);
                                });
                            })
                            .child(icon("search", 14., s.text3))
                            .child(div().truncate().child(q.clone()))
                    }))),
            )
            .with_priority(1)
        });
        // Chips.svelte: the kinds of result found, on glass, the chosen one on a sliding lens.
        let chips = match &self.results {
            Some(Ok(r)) => {
                let songs = r.songs.iter().filter(|t| !t.is_video).count();
                let videos = r.songs.len() - songs;
                let cats: Vec<(Cat, &str)> = [
                    (Cat::All, "Tutto", 1),
                    (Cat::Songs, "Brani", songs),
                    (Cat::Videos, "Video", videos),
                    (Cat::Artists, "Artisti", r.artists.len()),
                    (Cat::Albums, "Album", r.albums.len()),
                    (Cat::Playlists, "Playlist", r.playlists.len()),
                ]
                .into_iter()
                .filter(|c| c.2 > 0)
                .map(|c| (c.0, c.1))
                .collect();
                (cats.len() > 1).then(|| {
                    let me = cx.entity().downgrade();
                    let bg = s.bg.hsla(1.);
                    div().mt(px(2.)).flex().child(
                        div()
                            .relative()
                            .rounded_full()
                            .child(glass::glass_layer(look, px(20.), glass::Pointer::default()))
                            .child(div().relative().p(px(4.)).flex().gap(px(2.)).children(cats.into_iter().map(|(c, label)| {
                                let on = self.cat == c;
                                let me = me.clone();
                                div()
                                    .id(label)
                                    .h(px(32.))
                                    .px(px(16.))
                                    .flex()
                                    .items_center()
                                    .rounded_full()
                                    .text_size(px(13.))
                                    .font_weight(FontWeight::SEMIBOLD)
                                    .whitespace_nowrap()
                                    .cursor_pointer()
                                    .when(on, |d| {
                                        d.bg(s.text).text_color(bg).shadow(vec![BoxShadow {
                                            color: black().opacity(0.25),
                                            offset: point(px(0.), px(4.)),
                                            blur_radius: px(14.),
                                            spread_radius: px(0.),
                                            inset: false,
                                        }])
                                    })
                                    .when(!on, |d| d.text_color(s.text2).hover(|d| d.text_color(s.text)))
                                    .on_click(move |_, _, cx| {
                                        let _ = me.update(cx, |this, cx| {
                                            this.cat = c;
                                            cx.notify();
                                        });
                                    })
                                    .child(label)
                            }))),
                    )
                })
            }
            _ => None,
        };
        let head = div()
            .pt(px(62. + 20.))
            .px(px(24.))
            .pb(px(20.))
            .flex()
            .flex_col()
            .gap(px(16.))
            .child(div().text_size(px((w * 0.036).clamp(30., 44.))).font_weight(FontWeight::EXTRA_BOLD).child("Cerca"))
            .child(div().relative().max_w(px(640.)).child(field).children(sugg))
            .children(chips);

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
                                    this.pick(q.to_string(), cx);
                                });
                            })
                            .child(q)
                            .into_any_element()
                    })
                    .collect();
                if !self.recents.is_empty() {
                    body.push(self.recents_section(&s, cx));
                }
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
                } else if self.cat != Cat::All {
                    // One kind: every song (or video) as rows, or a grid of cards.
                    match self.cat {
                        Cat::Songs | Cat::Videos => {
                            let videos = self.cat == Cat::Videos;
                            let list: Vec<Track> = r.songs.iter().filter(|t| t.is_video == videos).cloned().collect();
                            let rows = self.song_rows("cat-songs", &list, true, cx);
                            body.push(div().px(px(24.)).pb(px(32.)).child(div().mx(px(-16.)).children(rows)).into_any_element());
                        }
                        _ => {
                            let items = match self.cat {
                                Cat::Artists => &r.artists,
                                Cat::Albums => &r.albums,
                                _ => &r.playlists,
                            };
                            // `.grid`: repeat(auto-fill, minmax(170px, 1fr)), gap 8.
                            let avail = (self.page_width - 48.).max(170.);
                            let cols = ((avail + 8.) / 178.).floor().max(1.);
                            let card_w = ((avail - 8. * (cols - 1.)) / cols).floor();
                            let cards: Vec<AnyElement> = items
                                .iter()
                                .map(|it| {
                                    let cover = it.cover_url.as_ref().and_then(|u| self.covers.update(cx, |c, cx| c.get(u, cx)));
                                    crate::home::card(format!("g-{}-{}", it.kind, it.id).into(), it, card_w, cover, &s, &self.core, &self.nav).into_any_element()
                                })
                                .collect();
                            body.push(div().px(px(24.)).pb(px(32.)).flex().flex_wrap().gap(px(8.)).children(cards).into_any_element());
                        }
                    }
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
                                        .on_click({
                                            let me = cx.entity().downgrade();
                                            move |_, _, cx| {
                                                let _ = me.update(cx, |this, _| this.remember_item(&open));
                                                open_item(&open, &core, &nav, cx)
                                            }
                                        })
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
                                .child(
                                    div()
                                        .flex()
                                        .items_baseline()
                                        .justify_between()
                                        .child(section_title("Brani"))
                                        .when(r.songs.len() > 5, |d| {
                                            let me = cx.entity().downgrade();
                                            let to = if r.songs.iter().any(|t| !t.is_video) { Cat::Songs } else { Cat::Videos };
                                            d.child(
                                                div()
                                                    .id("see-all")
                                                    .text_size(px(13.))
                                                    .font_weight(FontWeight::SEMIBOLD)
                                                    .text_color(s.accent.hsla(1.))
                                                    .cursor_pointer()
                                                    .hover(|d| d.underline())
                                                    .on_click(move |_, _, cx| {
                                                        let _ = me.update(cx, |this, cx| {
                                                            this.cat = to;
                                                            cx.notify();
                                                        });
                                                    })
                                                    .child("Mostra tutti"),
                                            )
                                        }),
                                )
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
