//! The lyrics page (Tauri: pages/Lyrics.svelte): the playing cover's light behind, the song on
//! the left, the lines on the right; one screen tall, the lines scroll inside.

use gpui_kit::*;

use crate::ambient::Ambient;
use crate::covers::Covers;
use crate::icons::icon;
use crate::look::Look;
use crate::lyrics::{Lyrics, Variant};
use crate::nav::{Nav, Route};
use crate::player_model::PlayerModel;

pub struct LyricsPage {
    model: Entity<PlayerModel>,
    covers: Entity<Covers>,
    look: Entity<Look>,
    nav: Entity<Nav>,
    lyrics: Entity<Lyrics>,
    ambient: Entity<Ambient>,
    pub width: f32,
    pub page_width: f32,
    pub height: f32,
    _subs: Vec<Subscription>,
}

impl LyricsPage {
    pub fn new(
        core: std::sync::Arc<crate::core::Core>,
        model: Entity<PlayerModel>,
        covers: Entity<Covers>,
        look: Entity<Look>,
        nav: Entity<Nav>,
        lyrics: Entity<Lyrics>,
        cx: &mut Context<Self>,
    ) -> Self {
        let subs = vec![
            cx.observe(&covers, |_, _, cx| cx.notify()),
            cx.observe(&look, |_, _, cx| cx.notify()),
            cx.subscribe(&model, |_, _, _: &crate::player_model::PlayerEvent, cx| cx.notify()),
        ];
        let ambient = cx.new(|_| Ambient::new(core, 0.5, 70.));
        Self { model, covers, look, nav, lyrics, ambient, width: 1100., page_width: 820., height: 720., _subs: subs }
    }
}

impl Render for LyricsPage {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let track = self.model.read(cx).current().cloned();
        let bg = self.look.read(cx).scheme.bg;
        let (w, h, pw) = (self.width, self.height, self.page_width);
        self.ambient.update(cx, |a, _| {
            a.url = track.as_ref().and_then(|t| t.cover_url.clone());
            a.width = pw;
            a.height = h;
            a.bg = bg;
        });
        self.lyrics.update(cx, |l, _| {
            l.variant = Variant::Page;
            l.window_width = w;
        });
        let narrow = w <= 900.;
        // page.css `--page-x`: 24px; the grid's gap clamp(28px, 5vw, 80px).
        let page_x = 24.;
        let gap = (0.05 * w).clamp(28., 80.);
        let free = pw - 2. * page_x - if narrow { 0. } else { gap };
        let aside_w = if narrow { 0. } else { (free * 0.3).clamp(200., 300.) };
        let cover = track.as_ref().and_then(|t| t.cover_url.clone()).and_then(|u| self.covers.update(cx, |c, cx| c.get(&u, cx)));
        let aside = track.as_ref().filter(|_| !narrow).map(|t| {
            let (nav, nav2) = (self.nav.clone(), self.nav.clone());
            let (artist_id, artist) = (t.artist_id.clone(), t.artist.clone());
            div()
                .w(px(aside_w))
                .flex_none()
                .flex()
                .flex_col()
                .gap(px(4.))
                .min_w_0()
                .child(
                    div()
                        .id("lyr-art")
                        .group("lyr-art")
                        .relative()
                        .size(px(aside_w))
                        .mb(px(14.))
                        .rounded(px(12.))
                        .cursor_pointer()
                        .bg(white().opacity(0.08))
                        .shadow(vec![BoxShadow { color: black().opacity(0.5), offset: point(px(0.), px(24.)), blur_radius: px(60.), spread_radius: px(0.), inset: false }])
                        .on_click(move |_, _, cx| nav.update(cx, |n, cx| n.go(Route::NowPlaying, cx)))
                        .children(cover.map(|i| img(i).size_full().object_fit(ObjectFit::Cover).rounded(px(12.))))
                        .child(
                            div()
                                .absolute()
                                .right(px(10.))
                                .bottom(px(10.))
                                .size(px(32.))
                                .rounded_full()
                                .flex()
                                .items_center()
                                .justify_center()
                                .bg(black().opacity(0.45))
                                .invisible()
                                .group_hover("lyr-art", |d| d.visible())
                                .child(icon("g-expand", 16., white())),
                        ),
                )
                .child(div().text_size(px(20.)).line_height(px(24.)).font_weight(FontWeight(750.)).child(t.title.clone()))
                .child(
                    div()
                        .id("lyr-artist")
                        .text_size(px(15.))
                        .text_color(white().opacity(0.7))
                        .cursor_pointer()
                        .hover(|d| d.text_color(white()).underline())
                        .on_click(move |_, _, cx| {
                            if let Some(id) = artist_id.clone() {
                                nav2.update(cx, |n, cx| n.go(Route::Artist { id, name: artist.clone() }, cx));
                            }
                        })
                        .child(t.artist.clone()),
                )
        });
        div()
            .size_full()
            .relative()
            .text_color(white())
            .child(self.ambient.clone())
            .child(
                div()
                    .absolute()
                    .inset_0()
                    // Under the top bar (56 + 16), above the player bar (104).
                    .pt(px(72.))
                    .pb(px(104.))
                    .px(px(page_x))
                    .flex()
                    .gap(px(gap))
                    .children(aside)
                    .child(div().flex_1().min_w_0().max_w(px(820.)).h_full().child(self.lyrics.clone())),
            )
    }
}
