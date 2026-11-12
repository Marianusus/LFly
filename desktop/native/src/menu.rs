//! The right-click menu on songs (Tauri: components/ContextMenu.svelte, menu.svelte.ts): the
//! song on top, then queue, like, add to a playlist, remove, go to the artist or the album. It
//! opens out of the pointer, flipping up or left near the window's edges; a click anywhere else,
//! Esc or leaving the window closes it. (Downloads are not ported yet: "Scarica" is off.)

use std::rc::Rc;
use std::sync::Arc;
use std::time::Instant;

use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use lfly_connect::protocol::TrackRef;

use crate::core::Core;
use crate::covers::Covers;
use crate::glass;
use crate::icons::icon;
use crate::library_model::LibraryModel;
use crate::look::Look;
use crate::nav::{Nav, Route};
use crate::toast::Toasts;

const WIDTH: f32 = 260.;

pub type Remove = Rc<dyn Fn(&mut App)>;

struct Open {
    at: Point<Pixels>,
    track: TrackRef,
    remove: Option<Remove>,
    since: Instant,
}

pub struct Menu {
    core: Arc<Core>,
    look: Entity<Look>,
    covers: Entity<Covers>,
    library: Entity<LibraryModel>,
    nav: Entity<Nav>,
    open: Option<Open>,
    sub: bool,
    /// The view's size and where it starts in the window, set before it draws.
    pub size: Size<Pixels>,
    pub origin: Point<Pixels>,
}

struct MenuGlobal(Entity<Menu>);
impl Global for MenuGlobal {}

impl Menu {
    pub fn new(core: Arc<Core>, look: Entity<Look>, covers: Entity<Covers>, library: Entity<LibraryModel>, nav: Entity<Nav>, cx: &mut App) -> Entity<Self> {
        let e = cx.new(|cx| {
            cx.observe(&covers, |_, _, cx| cx.notify()).detach();
            cx.observe(&library, |_, _, cx| cx.notify()).detach();
            Self { core, look, covers, library, nav, open: None, sub: false, size: size(px(1100.), px(720.)), origin: Point::default() }
        });
        cx.set_global(MenuGlobal(e.clone()));
        e
    }

    /// menu.openTrack: at [at] in the window; [remove] adds "Rimuovi da questa playlist".
    pub fn open_track(at: Point<Pixels>, track: TrackRef, remove: Option<Remove>, cx: &mut App) {
        let Some(e) = cx.try_global::<MenuGlobal>().map(|g| g.0.clone()) else { return };
        e.update(cx, |m, cx| {
            m.open = Some(Open { at, track, remove, since: Instant::now() });
            m.sub = false;
            cx.notify();
        });
    }

    pub fn close(cx: &mut App) {
        let Some(e) = cx.try_global::<MenuGlobal>().map(|g| g.0.clone()) else { return };
        e.update(cx, |m, cx| {
            if m.open.take().is_some() {
                cx.notify();
            }
        });
    }

    pub fn is_open(&self) -> bool {
        self.open.is_some()
    }
}

/// The heart, wherever it is: liked or not in favorites.json, the thumb on YouTube Music, and a
/// word when it was added (library.svelte.ts `toggleFavorite`).
pub fn like_action(library: Entity<LibraryModel>, core: Arc<Core>, t: TrackRef) -> Rc<dyn Fn(&mut App)> {
    Rc::new(move |cx| {
        let id = t.id.clone();
        let now = library.update(cx, |l, cx| l.toggle_favorite(track_value(&t), cx));
        if let Some(now) = now {
            core.rate(id, now.then_some(true));
            if now {
                Toasts::say("Aggiunto ai Brani che ti piacciono", cx);
            }
        }
    })
}

/// The value the library keeps for a song (the phone's JSON).
fn track_value(t: &TrackRef) -> serde_json::Value {
    serde_json::to_value(t).unwrap_or_default()
}

impl Render for Menu {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let Some(open) = self.open.as_ref() else { return div().into_any_element() };
        let (s, mut look) = {
            let l = self.look.read(cx);
            (l.scheme.clone(), l.glass())
        };
        look.tint = s.surface.hsla(0.82);
        let t = open.track.clone();
        let remove = open.remove.clone();
        let lib = self.library.read(cx);
        let liked = lib.is_favorite(&t.id);
        let playlists = lib.playlists.clone();
        let cover = t.cover_url.as_ref().and_then(|u| self.covers.update(cx, |c, cx| c.get(u, cx)));
        let accent = s.accent.hsla(1.);
        let on_accent = s.on_accent.hsla(1.);
        let sep = crate::track_row::separator(&s);

        // Its height, for flipping up near the bottom: the head, the rules, the rows.
        let rows = 7 + usize::from(remove.is_some());
        let sub_h = if self.sub { (32. * (playlists.len() + 1) as f32).min(220.) + 6. } else { 0. };
        let height = 12. + 50. + 3. * 11. + 32. * rows as f32 + sub_h;
        let (ww, wh) = (f32::from(self.size.width), f32::from(self.size.height));
        let (mx, my) = (f32::from(open.at.x - self.origin.x), f32::from(open.at.y - self.origin.y));
        let left = mx + WIDTH > ww - 8.;
        let up = my + height > wh - 8.;
        let x = if left { (mx - WIDTH).max(8.) } else { mx };
        let y = if up { (my - height).max(8.) } else { my };
        let p = (open.since.elapsed().as_secs_f32() / 0.26).min(1.);
        if p < 1. {
            window.request_animation_frame();
        }

        let me = cx.entity().downgrade();
        let item = |id: &'static str, name: &'static str, size: f32, label: SharedString, enabled: bool, danger: bool| {
            let group: SharedString = format!("cm-{id}").into();
            let fg = if danger { rgb(0xff6b6b).into() } else { s.text };
            div()
                .id(id)
                .group(group.clone())
                .w_full()
                .min_h(px(32.))
                .px(px(10.))
                .flex()
                .items_center()
                .gap(px(10.))
                .rounded(px(10.))
                .text_size(px(13.))
                .text_color(if enabled { fg } else { s.text3.opacity(0.6) })
                .when(enabled, |d| d.cursor_pointer().hover(move |d| d.bg(accent).text_color(on_accent)))
                .child(icon(name, size, if enabled { s.text2 } else { s.text3.opacity(0.6) }).when(enabled, |i| i.group_hover(group, move |i| i.text_color(on_accent))))
                .child(div().flex_1().min_w_0().truncate().child(label))
        };
        let hr = || div().h(px(1.)).mx(px(8.)).my(px(5.)).bg(sep);
        let run = {
            let me = me.clone();
            move |f: Rc<dyn Fn(&mut App)>| {
                let me = me.clone();
                move |_: &ClickEvent, _: &mut Window, cx: &mut App| {
                    f(cx);
                    let _ = me.update(cx, |m, cx| {
                        m.open = None;
                        cx.notify();
                    });
                }
            }
        };

        let core = self.core.clone();
        let (t1, t2, t3) = (t.clone(), t.clone(), t.clone());
        let library = self.library.clone();
        let nav = self.nav.clone();
        let (core1, core2, core3) = (core.clone(), core.clone(), core);
        let add_queue: Rc<dyn Fn(&mut App)> = Rc::new(move |cx| {
            core1.player.edit_queue("add", vec![t1.clone()], -1, -1);
            Toasts::say("Aggiunto alla coda", cx);
        });
        let play_next: Rc<dyn Fn(&mut App)> = Rc::new(move |cx| {
            core2.player.edit_queue("next", vec![t2.clone()], -1, -1);
            Toasts::say("Suonerà dopo questo brano", cx);
        });
        let like = like_action(library.clone(), core3, t3);

        let mut menu = div()
            .p(px(6.))
            .flex()
            .flex_col()
            .child(
                div()
                    .flex()
                    .items_center()
                    .gap(px(10.))
                    .pt(px(6.))
                    .px(px(8.))
                    .pb(px(8.))
                    .child(div().size(px(36.)).flex_none().rounded(px(8.)).bg(s.text.opacity(0.08)).children(cover.map(|i| img(i).size_full().object_fit(ObjectFit::Cover).rounded(px(8.)))))
                    .child(
                        div()
                            .min_w_0()
                            .flex()
                            .flex_col()
                            .child(div().font_weight(FontWeight::SEMIBOLD).text_size(px(14.)).text_color(s.text).truncate().child(t.title.clone()))
                            .child(div().text_size(px(12.)).text_color(s.text2).truncate().child(t.artist.clone())),
                    ),
            )
            .child(hr())
            .child(item("cm-queue", "queue", 15., "Aggiungi alla coda".into(), true, false).on_click(run(add_queue)))
            .child(item("cm-next", "next", 14., "Riproduci dopo".into(), true, false).on_click(run(play_next)))
            .child(hr())
            .child(
                item(
                    "cm-like",
                    if liked { "heart-fill" } else { "heart" },
                    15.,
                    if liked { "Togli dai Brani che ti piacciono" } else { "Aggiungi ai Brani che ti piacciono" }.into(),
                    true,
                    false,
                )
                .on_click(run(like)),
            )
            .child(
                item("cm-add", "plus", 15., "Aggiungi a playlist".into(), true, false)
                    .child(icon(if self.sub { "chevron-up" } else { "chevron-down" }, 13., s.text2))
                    .on_click({
                        let me = me.clone();
                        move |_, _, cx| {
                            let _ = me.update(cx, |m, cx| {
                                m.sub = !m.sub;
                                cx.notify();
                            });
                        }
                    }),
            );
        if self.sub {
            let n = playlists.len();
            let (lib2, nav2, t4) = (library.clone(), nav.clone(), t.clone());
            let new_playlist: Rc<dyn Fn(&mut App)> = Rc::new(move |cx| {
                let nav = nav2.clone();
                lib2.update(cx, |l, cx| {
                    l.create_playlist(
                        format!("La mia playlist n. {}", n + 1),
                        vec![track_value(&t4)],
                        move |id, name, cx| nav.update(cx, |n, cx| n.go(Route::Playlist { id, name, local: true }, cx)),
                        cx,
                    )
                });
            });
            let mut sub = div()
                .id("cm-sub")
                .max_h(px(220.))
                .overflow_y_scroll()
                .mt(px(2.))
                .mb(px(4.))
                .ml(px(18.))
                .pl(px(8.))
                .border_l_1()
                .border_color(sep)
                .child(item("cm-new", "plus", 14., "Nuova playlist".into(), true, false).on_click(run(new_playlist)));
            for (i, pl) in playlists.into_iter().enumerate() {
                let (lib3, t5) = (library.clone(), t.clone());
                let name = pl.name.clone();
                let add: Rc<dyn Fn(&mut App)> = Rc::new(move |cx| {
                    let name = name.clone();
                    lib3.update(cx, |l, cx| {
                        l.add_to_playlist(
                            pl.id.clone(),
                            vec![track_value(&t5)],
                            move |n, cx| Toasts::say(if n > 0 { format!("Aggiunto a {name}") } else { format!("Già in {name}") }, cx),
                            cx,
                        )
                    });
                });
                let group: SharedString = format!("cm-pl-{i}").into();
                sub = sub.child(
                    div()
                        .id(("cm-pl", i))
                        .group(group)
                        .min_h(px(32.))
                        .px(px(10.))
                        .flex()
                        .items_center()
                        .rounded(px(10.))
                        .text_size(px(13.))
                        .text_color(s.text)
                        .cursor_pointer()
                        .hover(move |d| d.bg(accent).text_color(on_accent))
                        .on_click(run(add))
                        .child(div().truncate().child(pl.name.clone())),
                );
            }
            menu = menu.child(sub);
        }
        menu = menu.child(item("cm-download", "download", 15., "Scarica".into(), false, false));
        if let Some(remove) = remove {
            menu = menu.child(item("cm-remove", "trash", 15., "Rimuovi da questa playlist".into(), true, true).on_click(run(remove)));
        }
        let (nav3, nav4) = (nav.clone(), nav);
        let (artist_id, artist) = (t.artist_id.clone(), t.artist.clone());
        let (album_id, album) = (t.album_id.clone(), t.album.clone().unwrap_or_default());
        let go_artist: Rc<dyn Fn(&mut App)> = Rc::new(move |cx| {
            if let Some(id) = artist_id.clone() {
                nav3.update(cx, |n, cx| n.go(Route::Artist { id, name: artist.clone() }, cx));
            }
        });
        let go_album: Rc<dyn Fn(&mut App)> = Rc::new(move |cx| {
            if let Some(id) = album_id.clone() {
                nav4.update(cx, |n, cx| n.go(Route::Album { id, name: album.clone() }, cx));
            }
        });
        menu = menu
            .child(hr())
            .child(item("cm-artist", "music", 15., "Vai all'artista".into(), t.artist_id.is_some(), false).when(t.artist_id.is_some(), |d| d.on_click(run(go_artist))))
            .child(item("cm-album", "library", 15., "Vai all'album".into(), t.album_id.is_some(), false).when(t.album_id.is_some(), |d| d.on_click(run(go_album))));

        let close = me.clone();
        div()
            .id("cm-catch")
            .absolute()
            .inset_0()
            // Anywhere else: closed, and that click does nothing more.
            .on_mouse_down(MouseButton::Left, move |_, _, cx| {
                let _ = close.update(cx, |m, cx| {
                    m.open = None;
                    cx.notify();
                });
            })
            .on_mouse_down(MouseButton::Right, {
                let me = me.clone();
                move |_, _, cx| {
                    let _ = me.update(cx, |m, cx| {
                        m.open = None;
                        cx.notify();
                    });
                }
            })
            .child(
                div()
                    .id("cm")
                    .absolute()
                    .left(px(x))
                    .top(px(y))
                    .w(px(WIDTH))
                    .rounded(px(18.))
                    .opacity(crate::motion::CUBIC_OUT.at(p as f64) as f32)
                    .bg(s.surface.hsla(0.86))
                    .shadow(vec![BoxShadow { color: black().opacity(0.45), offset: point(px(0.), px(18.)), blur_radius: px(48.), spread_radius: px(0.), inset: false }])
                    .on_mouse_down(MouseButton::Left, |_, _, cx| cx.stop_propagation())
                    .on_mouse_down(MouseButton::Right, |_, _, cx| cx.stop_propagation())
                    .child(glass::glass_layer(look, px(18.), glass::Pointer::default()))
                    .child(div().relative().child(menu)),
            )
            .into_any_element()
    }
}
