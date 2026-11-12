//! Your library (Tauri: pages/Library.svelte, parts/LibraryItem.svelte): the title and counts, the
//! import and new-playlist pills, the filters on glass, grid or list, then your collections and
//! what you played lately. Every item opens on a click and plays from its button.

use std::sync::Arc;

use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use serde_json::Value;

use crate::core::Core;
use crate::covers::Covers;
use crate::glass;
use crate::icons::icon;
use crate::library_model::LibraryModel;
use crate::look::Look;
use crate::nav::{Nav, Route};
use crate::player_model::PlayerModel;
use crate::theme::{mix, Rgb, Scheme};
use crate::toast::Toasts;

#[derive(Clone, Copy, PartialEq)]
enum Filter {
    All,
    Playlists,
    Downloads,
    Recent,
}

/// The grid or the list, kept by this window (Tauri keeps it in the webview's storage).
const VIEW_KEY: &str = "native-library-view";

pub struct LibraryPage {
    core: Arc<Core>,
    covers: Entity<Covers>,
    look: Entity<Look>,
    nav: Entity<Nav>,
    library: Entity<LibraryModel>,
    model: Entity<PlayerModel>,
    filter: Filter,
    list: bool,
    pub spotify: bool,
    spotify_panel: Option<Entity<crate::spotify_import::SpotifyImport>>,
    pub page_width: f32,
    _subs: Vec<Subscription>,
}

impl LibraryPage {
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
            cx.observe(&library, |_, _, cx| cx.notify()),
            cx.subscribe(&model, |_, _, _: &crate::player_model::PlayerEvent, cx| cx.notify()),
        ];
        // LFLY_LIBRARY_VIEW=list|grid and LFLY_SPOTIFY=1, for screenshots (nothing saved).
        let list = match std::env::var("LFLY_LIBRARY_VIEW").as_deref() {
            Ok(v) => v == "list",
            Err(_) => lfly_core::store::get(VIEW_KEY).ok().flatten().and_then(|v| v.as_str().map(|s| s == "list")).unwrap_or(false),
        };
        let spotify = std::env::var_os("LFLY_SPOTIFY").is_some();
        Self { core, covers, look, nav, library, model, filter: Filter::All, list, spotify, spotify_panel: None, page_width: 800., _subs: subs }
    }
}

fn tracks_of(values: &[Value]) -> Vec<lfly_yt::Track> {
    values.iter().filter_map(|v| serde_json::from_value(v.clone()).ok()).collect()
}

/// Library.svelte `newPlaylist`: "La mia playlist n. N", then its page.
pub fn new_playlist(library: &Entity<LibraryModel>, nav: &Entity<Nav>, cx: &mut App) {
    let n = library.read(cx).playlists.len() + 1;
    let nav = nav.clone();
    library.update(cx, |l, cx| {
        l.create_playlist(format!("La mia playlist n. {n}"), Vec::new(), move |id, name, cx| nav.update(cx, |n, cx| n.go(Route::Playlist { id, name, local: true }, cx)), cx)
    });
}

/// library.importBackup: the person picks the phone's .lfly backup; its library replaces this one.
pub fn import_backup(library: &Entity<LibraryModel>, cx: &mut App) {
    let picked = cx.prompt_for_paths(PathPromptOptions { files: true, directories: false, multiple: false, prompt: Some("Importa".into()) });
    let library = library.clone();
    cx.spawn(async move |cx| {
        let Ok(Ok(Some(paths))) = picked.await else { return };
        let Some(path) = paths.into_iter().next() else { return };
        let _ = cx.update(|cx| {
            library.update(cx, |l, cx| {
                l.import_backup(
                    path,
                    |r, cx| match r {
                        Ok(s) => Toasts::say(format!("Importati {} playlist, {} preferiti e {} brani ascoltati", s.playlists, s.favorites, s.history), cx),
                        Err(e) => Toasts::say(e, cx),
                    },
                    cx,
                )
            })
        });
    })
    .detach();
}

/// Artwork.svelte: a collection without a cover of its own, a symbol on the theme's colours.
pub fn artwork(name: &'static str, tone: Option<(u32, u32)>, size: f32, s: &Scheme) -> Div {
    let (a, b) = match tone {
        Some((a, b)) => (Rgb::hex(a).hsla(1.), Rgb::hex(b).hsla(1.)),
        None => (mix(s.accent, Rgb::hex(0xffffff), 0.08).hsla(1.), mix(s.accent, Rgb::hex(0x2a1650), 0.55).hsla(1.)),
    };
    div()
        .relative()
        .size(px(size))
        .rounded(px(12.))
        .overflow_hidden()
        .flex()
        .items_center()
        .justify_center()
        .bg(linear_gradient(140., linear_color_stop(a, 0.), linear_color_stop(b, 1.)))
        .child(div().absolute().inset_0().bg(linear_gradient(180., linear_color_stop(white().opacity(0.18), 0.), linear_color_stop(black().opacity(0.12), 1.))))
        .child(icon(name, (size * 0.36).round(), white()))
}

pub enum Art {
    Cover(Option<Arc<Image>>),
    Icon(&'static str, Option<(u32, u32)>),
}

pub type Action = std::rc::Rc<dyn Fn(&mut App)>;

/// LibraryItem.svelte: a tile in the grid (art, title, subtitle, a play disc under the pointer)
/// or a row in the list (52 px art, the text, a small play button).
#[allow(clippy::too_many_arguments)]
pub fn library_item(
    id: SharedString,
    title: String,
    subtitle: String,
    art: Art,
    list: bool,
    width: f32,
    playing: bool,
    s: &Scheme,
    on_open: impl Fn(&mut App) + 'static,
    on_play: Option<Action>,
) -> AnyElement {
    let group: SharedString = format!("li-{id}").into();
    let accent = s.accent.hsla(1.);
    let art_size = if list { 52. } else { width - 20. };
    let radius = if list { 10. } else { 12. };
    let art_el = match art {
        Art::Icon(name, tone) => artwork(name, tone, art_size, s).rounded(px(radius)),
        Art::Cover(img_) => div()
            .size(px(art_size))
            .rounded(px(radius))
            .bg(s.text.opacity(0.08))
            .children(img_.map(|i| img(i).size_full().object_fit(ObjectFit::Cover).rounded(px(radius)))),
    }
    .flex_none()
    .when(!list, |d| d.shadow(vec![BoxShadow { color: black().opacity(0.3), offset: point(px(0.), px(10.)), blur_radius: px(26.), spread_radius: px(0.), inset: false }]));
    let on_accent = s.on_accent.hsla(1.);
    let play_btn = |size: f32, icon_size: f32, inline: bool, play: Action| {
        div()
            .id(SharedString::from(format!("{id}-play")))
            .when(!inline, |d| d.absolute().right(px(10.)).bottom(px(10.)).invisible().group_hover(group.clone(), |d| d.visible()))
            .size(px(size))
            .flex_none()
            .rounded_full()
            .flex()
            .items_center()
            .justify_center()
            .pl(px(2.))
            .bg(accent)
            .shadow(vec![BoxShadow { color: black().opacity(0.35), offset: point(px(0.), px(8.)), blur_radius: px(20.), spread_radius: px(0.), inset: false }])
            .cursor_pointer()
            .on_click(move |_, _, cx| {
                cx.stop_propagation();
                play(cx);
            })
            .child(icon("play", icon_size, on_accent))
    };
    let text = div()
        .min_w_0()
        .flex()
        .flex_col()
        .gap(px(2.))
        .when(list, |d| d.flex_1())
        .child(div().text_size(px(14.)).font_weight(FontWeight::SEMIBOLD).text_color(if playing { accent } else { s.text }).truncate().child(title))
        .when(!subtitle.is_empty(), |d| d.child(div().text_size(px(12.)).text_color(s.text2).truncate().child(subtitle)));
    let mut item = div()
        .id(id.clone())
        .group(group.clone())
        .relative()
        .rounded(px(if list { 14. } else { 18. }))
        .cursor_pointer()
        .hover(|d| d.bg(s.glass_tint))
        .on_click(move |_, _, cx| on_open(cx));
    if list {
        item = item.w(px(width)).flex().items_center().gap(px(14.)).pl(px(6.)).pr(px(10.)).py(px(6.)).child(art_el).child(text);
        if let Some(play) = on_play {
            item = item.child(play_btn(34., 15., true, play));
        }
    } else {
        item = item.w(px(width)).p(px(10.)).flex().flex_col().gap(px(10.)).child(div().relative().child(art_el)).child(text);
        if let Some(play) = on_play {
            item = item.child(play_btn(44., 18., false, play));
        }
    }
    item.into_any_element()
}

impl LibraryPage {
    /// The Spotify panel (made by the window, which has what its text field needs).
    pub fn set_spotify(&mut self, panel: Entity<crate::spotify_import::SpotifyImport>, cx: &mut Context<Self>) {
        self._subs.push(cx.subscribe(&panel, |this, _, ev: &crate::spotify_import::SpotifyEvent, cx| match ev {
            crate::spotify_import::SpotifyEvent::Close => {
                this.spotify = false;
                cx.notify();
            }
        }));
        self._subs.push(cx.observe(&panel, |_, _, cx| cx.notify()));
        self.spotify_panel = Some(panel);
    }

    fn set_view(&mut self, list: bool, cx: &mut Context<Self>) {
        self.list = list;
        if let Err(e) = lfly_core::store::set(VIEW_KEY, &Value::from(if list { "list" } else { "grid" })) {
            log::warn!("{VIEW_KEY}: {e}");
        }
        cx.notify();
    }
}

impl Render for LibraryPage {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let (s, look) = {
            let l = self.look.read(cx);
            (l.scheme.clone(), l.glass())
        };
        let accent = s.accent.hsla(1.);
        let lib = self.library.read(cx);
        let playlists = lib.playlists.clone();
        let raw: Vec<Option<Value>> = playlists.iter().map(|p| lib.raw_playlist(&p.id)).collect();
        let favorites = tracks_of(&lib.favorites);
        let history = tracks_of(&lib.history);
        let current = self.model.read(cx).current().map(|t| t.id.clone());

        // `.p-pill`: 34 px, 13px 600, the glass tint and border; `.accent` in the accent.
        let pill = |id: &'static str, name: &'static str, label: &'static str, primary: bool, on: bool| {
            div()
                .id(id)
                .h(px(34.))
                .px(px(16.))
                .flex()
                .items_center()
                .gap(px(8.))
                .rounded_full()
                .text_size(px(13.))
                .font_weight(FontWeight::SEMIBOLD)
                .when(primary, |d| d.bg(accent).text_color(s.on_accent.hsla(1.)))
                .when(!primary, |d| {
                    d.bg(if on { s.text.opacity(0.16) } else { s.glass_tint }).border_1().border_color(s.glass_border).text_color(s.text).hover(|d| d.bg(s.text.opacity(0.14)))
                })
                .cursor_pointer()
                .child(icon(name, 15., if primary { s.on_accent.hsla(1.) } else { s.text }))
                .child(label)
        };
        let (lib1, lib2, nav1) = (self.library.clone(), self.library.clone(), self.nav.clone());
        let me = cx.entity().downgrade();
        let me_sp = me.clone();
        let acts = div()
            .flex()
            .flex_wrap()
            .gap(px(8.))
            .child(pill("import", "upload", "Importa dal telefono", false, false).on_click(move |_, _, cx| import_backup(&lib1, cx)))
            .child(pill("spotify", "download", "Importa da Spotify", false, self.spotify).on_click(move |_, _, cx| {
                let _ = me_sp.update(cx, |this, cx| {
                    this.spotify = !this.spotify;
                    cx.notify();
                });
            }))
            .child(pill("new", "plus", "Nuova playlist", true, false).on_click(move |_, _, cx| new_playlist(&lib2, &nav1, cx)));

        // Chips.svelte on glass, and the grid/list switch beside it.
        let mut filters = vec![(Filter::All, "Tutto"), (Filter::Playlists, "Playlist"), (Filter::Downloads, "Scaricati")];
        if !history.is_empty() {
            filters.push((Filter::Recent, "Recenti"));
        }
        let bg = s.bg.hsla(1.);
        let chips = div()
            .relative()
            .rounded_full()
            .child(glass::glass_layer(look, px(20.), glass::Pointer::default()))
            .child(div().relative().p(px(4.)).flex().gap(px(2.)).children(filters.into_iter().map(|(f, label)| {
                let on = self.filter == f;
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
                    .when(on, |d| d.bg(s.text).text_color(bg))
                    .when(!on, |d| d.text_color(s.text2).hover(|d| d.text_color(s.text)))
                    .on_click(move |_, _, cx| {
                        let _ = me.update(cx, |this, cx| {
                            this.filter = f;
                            cx.notify();
                        });
                    })
                    .child(label)
            })));
        let view_btn = |id: &'static str, glyph: &'static str, list: bool| {
            let on = self.list == list;
            let me = me.clone();
            div()
                .id(id)
                .w(px(36.))
                .h(px(32.))
                .rounded_full()
                .flex()
                .items_center()
                .justify_center()
                .cursor_pointer()
                .when(on, |d| d.bg(s.text))
                .on_click(move |_, _, cx| {
                    let _ = me.update(cx, |this, cx| this.set_view(list, cx));
                })
                .child(icon(glyph, 16., if on { bg } else { s.text2 }))
        };
        let viewer = (self.filter != Filter::Downloads).then(|| {
            div()
                .relative()
                .flex_none()
                .rounded_full()
                .child(glass::glass_layer(look, px(20.), glass::Pointer::default()))
                .child(div().relative().p(px(4.)).flex().gap(px(2.)).child(view_btn("v-grid", "g-grid", false)).child(view_btn("v-list", "g-list", true)))
        });

        // `.items`: grid repeat(auto-fill, minmax(168px, 1fr)) gap 4 (margin -10); list
        // repeat(auto-fill, minmax(min(100%, 360px), 1fr)) gaps 2 / 12 (margin -6).
        let list = self.list;
        let (avail, gap) = if list { (self.page_width - 48. + 12., 12.) } else { (self.page_width - 48. + 20., 4.) };
        let min = if list { 360f32.min(avail) } else { 168. };
        let cols = ((avail + gap) / (min + gap)).floor().max(1.);
        let item_w = ((avail - gap * (cols - 1.)) / cols).floor();
        let grid = |items: Vec<AnyElement>| {
            div()
                .mx(px(if list { -6. } else { -10. }))
                .flex()
                .flex_wrap()
                .gap_x(px(gap))
                .gap_y(px(if list { 2. } else { 4. }))
                .children(items)
        };
        let section_title = |t: &'static str| div().mb(px(12.)).text_size(px(22.)).font_weight(FontWeight::BOLD).child(t);
        let play_tracks = |tracks: Vec<lfly_yt::Track>, i: usize| -> Action {
            let core = self.core.clone();
            std::rc::Rc::new(move |_| core.play(&tracks, i))
        };
        let go = |route: Route| {
            let nav = self.nav.clone();
            move |cx: &mut App| nav.update(cx, |n, cx| n.go(route.clone(), cx))
        };

        let mut sections: Vec<AnyElement> = Vec::new();
        if self.filter == Filter::Downloads {
            sections.push(
                div()
                    .mx(px(24.))
                    .py(px(28.))
                    .px(px(24.))
                    .rounded(px(18.))
                    .bg(s.glass_tint)
                    .flex()
                    .flex_col()
                    .items_center()
                    .gap(px(8.))
                    .text_color(s.text2)
                    .child(icon("download", 28., s.text2))
                    .child(div().font_weight(FontWeight::BOLD).text_color(s.text).child("Nessun brano scaricato"))
                    .child(div().text_center().child("Scarica album, playlist o singoli brani (tasto destro › Scarica) per ascoltarli anche senza connessione."))
                    .into_any_element(),
            );
        } else {
            if matches!(self.filter, Filter::All | Filter::Playlists) {
                let mut items: Vec<AnyElement> = Vec::new();
                if self.filter == Filter::All {
                    items.push(library_item(
                        "lib-liked".into(),
                        "Brani che ti piacciono".into(),
                        format!("{} brani", favorites.len()),
                        Art::Icon("heart-fill", None),
                        list,
                        item_w,
                        false,
                        &s,
                        go(Route::Liked),
                        (!favorites.is_empty()).then(|| play_tracks(favorites.clone(), 0)),
                    ));
                }
                for (p, raw) in playlists.iter().zip(raw) {
                    let tracks = raw.as_ref().and_then(|r| r.get("tracks")).and_then(Value::as_array).map(|t| tracks_of(t)).unwrap_or_default();
                    let cover = p.cover_url.as_ref().and_then(|u| self.covers.update(cx, |c, cx| c.get(u, cx)));
                    items.push(library_item(
                        format!("lib-{}", p.id).into(),
                        p.name.clone(),
                        format!("Playlist · {} brani", p.count),
                        Art::Cover(cover),
                        list,
                        item_w,
                        false,
                        &s,
                        go(Route::Playlist { id: p.id.clone(), name: p.name.clone(), local: true }),
                        (!tracks.is_empty()).then(|| play_tracks(tracks, 0)),
                    ));
                }
                let (lib3, nav3) = (self.library.clone(), self.nav.clone());
                sections.push(
                    div()
                        .px(px(24.))
                        .pb(px(28.))
                        .when(self.filter == Filter::All, |d| d.child(section_title("Le tue raccolte")))
                        .child(grid(items))
                        .when(playlists.is_empty(), |d| {
                            d.child(
                                div()
                                    .mt(px(12.))
                                    .p(px(24.))
                                    .rounded(px(18.))
                                    .bg(s.glass_tint)
                                    .flex()
                                    .flex_col()
                                    .items_center()
                                    .gap(px(8.))
                                    .text_color(s.text2)
                                    .child(div().font_weight(FontWeight::BOLD).text_color(s.text).child("Ancora nessuna playlist"))
                                    .child("Crea la prima, importala dal telefono o da Spotify.")
                                    .child(pill("new-empty", "plus", "Nuova playlist", true, false).on_click(move |_, _, cx| new_playlist(&lib3, &nav3, cx))),
                            )
                        })
                        .into_any_element(),
                );
            }
            if !history.is_empty() && matches!(self.filter, Filter::All | Filter::Recent) {
                let n = if self.filter == Filter::Recent { 60 } else { 12 };
                let items: Vec<AnyElement> = history
                    .iter()
                    .take(n)
                    .enumerate()
                    .map(|(i, t)| {
                        let cover = t.cover_url.as_ref().and_then(|u| self.covers.update(cx, |c, cx| c.get(u, cx)));
                        let play = play_tracks(history.clone(), i);
                        let open = play.clone();
                        library_item(
                            format!("lib-recent-{i}").into(),
                            t.title.clone(),
                            t.artist.clone(),
                            Art::Cover(cover),
                            list,
                            item_w,
                            current.as_deref() == Some(t.id.as_str()),
                            &s,
                            move |cx| open(cx),
                            Some(play),
                        )
                    })
                    .collect();
                sections.push(div().px(px(24.)).pb(px(28.)).child(section_title("Ascoltati di recente")).child(grid(items)).into_any_element());
            }
        }

        div()
            .id("library")
            .size_full()
            .overflow_y_scroll()
            .pt(px(62.))
            .pb(px(104.))
            .text_color(s.text)
            .child(
                div()
                    .pt(px(20.))
                    .px(px(24.))
                    .pb(px(18.))
                    .flex()
                    .flex_wrap()
                    .items_end()
                    .justify_between()
                    .gap(px(16.))
                    .child(
                        div()
                            .flex()
                            .flex_col()
                            .gap(px(4.))
                            .child(div().text_size(px(((self.page_width + 280.) * 0.036).clamp(30., 44.))).font_weight(FontWeight::EXTRA_BOLD).child("Libreria"))
                            .child(div().text_size(px(13.)).text_color(s.text3).child(format!("{} playlist · {} brani che ti piacciono", playlists.len(), favorites.len()))),
                    )
                    .child(acts),
            )
            .when(self.spotify, |d| d.children(self.spotify_panel.clone().map(|p| div().px(px(24.)).pb(px(16.)).mt(px(-16.)).child(p))))
            .child(div().px(px(24.)).pb(px(20.)).flex().items_center().justify_between().gap(px(12.)).child(chips).children(viewer))
            .children(sections)
    }
}
