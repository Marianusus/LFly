//! The Console's video out: a window of its own, for a projector or a screen behind the DJ, with
//! the lyrics of the deck the crowd hears, line by line in time, over its cover.
//!
//! The deck shown is the one heard most (playing, its fader, its side of the crossfader); during
//! an autopilot mix it changes halfway. A double click (or F) puts the window full screen, Esc
//! takes it out; the mixer's VIDEO button opens and closes it.

use std::rc::Rc;
use std::time::Duration;

use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use lfly_core::pages;

use crate::covers::Covers;
use crate::deck_model::DjDecks;
use crate::i18n::tr;
use crate::lyrics::LyricsData;

/// How early a line shows (the eye reads ahead of the voice).
const LEAD_MS: f64 = 250.;

pub struct VideoOut {
    dj: Entity<DjDecks>,
    lyrics: Entity<LyricsData>,
    covers: Entity<Covers>,
    /// The deck shown last (kept while the two are about as loud, so it does not flicker).
    shown: usize,
    ticking: bool,
    _subs: Vec<Subscription>,
}

impl VideoOut {
    /// Opens the window; its handle closes it again.
    pub fn open(dj: Entity<DjDecks>, lyrics: Entity<LyricsData>, covers: Entity<Covers>, cx: &mut App) -> Option<AnyWindowHandle> {
        let bounds = Bounds::centered(None, size(px(1280.), px(720.)), cx);
        let options = WindowOptions {
            window_bounds: Some(WindowBounds::Windowed(bounds)),
            titlebar: Some(TitlebarOptions { title: Some("LFly · Video".into()), ..Default::default() }),
            app_id: Some("lfly-video".into()),
            window_min_size: Some(size(px(320.), px(180.))),
            focus: false,
            ..Default::default()
        };
        let handle = cx
            .open_window(options, |window, cx| {
                cx.new(|cx| {
                    let subs = vec![cx.observe(&dj, |_, _, cx| cx.notify()), cx.observe(&lyrics, |_, _, cx| cx.notify()), cx.observe(&covers, |_, _, cx| cx.notify())];
                    let _ = window;
                    VideoOut { dj, lyrics, covers, shown: 0, ticking: false, _subs: subs }
                })
            })
            .map_err(|e| log::warn!("video out: {e}"))
            .ok()?;
        Some(handle.into())
    }

    /// The deck the crowd hears most.
    fn heard(&mut self, cx: &App) -> Option<usize> {
        let dj = self.dj.read(cx);
        let curve = dj.curve.levels(if dj.hamster { 1. - dj.xfader } else { dj.xfader });
        let level = |d: usize| -> f32 {
            if !dj.is_playing(d) || dj.decks[d].track.is_none() {
                return 0.;
            }
            let side = match dj.decks[d].assign {
                3 => 1.,
                0 => curve[d],
                a => curve[(a as usize).saturating_sub(1).min(1)],
            };
            dj.decks[d].volume.powi(2) * side
        };
        let (a, b) = (level(0), level(1));
        if a == 0. && b == 0. {
            // Nothing plays: the last one shown, if it still has a song.
            return dj.decks[self.shown].track.is_some().then_some(self.shown);
        }
        let other = 1 - self.shown;
        // Another deck takes over when it is clearly the louder one.
        if [a, b][other] > [a, b][self.shown] * 1.15 {
            self.shown = other;
        }
        Some(self.shown)
    }

    /// Thirty frames a second while a deck plays and the window is open.
    fn keep_ticking(&mut self, cx: &mut Context<Self>) {
        if self.ticking {
            return;
        }
        self.ticking = true;
        cx.spawn(async move |this, cx| loop {
            cx.background_executor().timer(Duration::from_millis(33)).await;
            let on = this
                .update(cx, |v, cx| {
                    let on = v.dj.read(cx).any_playing();
                    if on {
                        cx.notify();
                    } else {
                        v.ticking = false;
                    }
                    on
                })
                .unwrap_or(false);
            if !on {
                break;
            }
        })
        .detach();
    }
}

impl Render for VideoOut {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        if self.dj.read(cx).any_playing() {
            self.keep_ticking(cx);
        }
        let heard = self.heard(cx);
        let (w, h) = (window.viewport_size().width / px(1.), window.viewport_size().height / px(1.));
        // Sizes follow the window: a projector or a laptop's second screen.
        let big = (h * 0.085).clamp(22., 120.);
        let small = big * 0.55;
        let root = div()
            .id("video-out")
            .size_full()
            .relative()
            .overflow_hidden()
            .bg(black())
            .text_color(white())
            .on_click(|e, window, _| {
                if e.click_count() == 2 {
                    window.toggle_fullscreen();
                }
            })
            .on_key_down(|e: &KeyDownEvent, window, _| match e.keystroke.key.as_str() {
                "f" | "f11" => window.toggle_fullscreen(),
                "escape" if window.is_fullscreen() => window.toggle_fullscreen(),
                _ => {}
            });
        let Some(d) = heard else {
            return root.child(div().size_full().flex().items_center().justify_center().text_size(px(small)).text_color(white().opacity(0.35)).child("LFly"));
        };
        let (track, pos_ms, dur_ms) = {
            let dj = self.dj.read(cx);
            (dj.decks[d].track.clone(), dj.position(d) * 1000. + LEAD_MS, dj.decks[d].duration().max(1.) * 1000.)
        };
        let Some(track) = track else { return root };
        let cover = track.cover_url.clone().and_then(|u| self.covers.update(cx, |c, cx| c.get(&u, cx)));
        let offset = self.lyrics.read(cx).offset(&track.id);
        let lyrics: Option<Option<Rc<pages::Lyrics>>> = self.lyrics.update(cx, |l, cx| l.get(&track, cx));
        let lyrics = lyrics.flatten();
        let t = pos_ms - offset;

        // The cover, large and dim behind everything.
        let side = w.max(h);
        let backdrop = cover.clone().map(|pic| {
            div()
                .absolute()
                .left(px((w - side) / 2.))
                .top(px((h - side) / 2.))
                .size(px(side))
                .opacity(0.22)
                .child(img(pic).size_full().object_fit(ObjectFit::Cover))
        });
        let shade = div().absolute().inset_0().bg(linear_gradient(180., linear_color_stop(black().opacity(0.35), 0.), linear_color_stop(black().opacity(0.85), 1.)));

        // The lines: the one sung large, the one before faint above, the next two below.
        let lines: Vec<(String, f32, f32, bool)> = match lyrics.as_ref().and_then(|l| l.synced.as_ref()).filter(|s| !s.is_empty()) {
            Some(synced) => {
                let i = synced.iter().rposition(|l| l.ms as f64 <= t);
                let at = |k: Option<usize>| k.and_then(|k| synced.get(k)).map(|l| l.text.trim().to_string()).filter(|s| !s.is_empty());
                let mut v = Vec::new();
                if let Some(prev) = at(i.and_then(|i| i.checked_sub(1))) {
                    v.push((prev, small, 0.28, false));
                }
                match at(i) {
                    Some(now) => v.push((now, big, 1., true)),
                    // Before the first line or in a pause: the song's name.
                    None => v.push((format!("♪ {}", track.title), big * 0.8, 0.85, true)),
                }
                let next = i.map_or(0, |i| i + 1);
                for k in [next, next + 1] {
                    if let Some(n) = at(Some(k)) {
                        v.push((n, small, if k == next { 0.5 } else { 0.25 }, false));
                    }
                }
                v
            }
            None => match lyrics.as_ref().and_then(|l| l.plain.clone()) {
                // Words without times: the part of the song the playhead is in.
                Some(plain) => {
                    let all: Vec<&str> = plain.lines().map(str::trim).filter(|l| !l.is_empty()).collect();
                    let k = ((t / dur_ms).clamp(0., 0.999) * all.len() as f64) as usize;
                    all.iter().skip(k).take(3).enumerate().map(|(j, l)| (l.to_string(), if j == 0 { big * 0.8 } else { small }, if j == 0 { 1. } else { 0.4 }, j == 0)).collect()
                }
                None => vec![(track.title.clone(), big, 1., true), (track.artist.clone(), small, 0.55, false)],
            },
        };
        let words = div()
            .absolute()
            .inset_0()
            .flex()
            .flex_col()
            .items_center()
            .justify_center()
            .gap(px(big * 0.35))
            .px(px(w * 0.07))
            .children(lines.into_iter().map(|(text, size, alpha, main)| {
                div()
                    .w_full()
                    .text_center()
                    .text_size(px(size))
                    .line_height(relative(1.15))
                    .font_weight(if main { FontWeight::BOLD } else { FontWeight::SEMIBOLD })
                    .text_color(white().opacity(alpha))
                    .child(text)
            }));
        // Who and what, small at the bottom.
        let credit = div()
            .absolute()
            .left(px(small))
            .bottom(px(small * 0.8))
            .flex()
            .items_center()
            .gap(px(small * 0.5))
            .children(cover.map(|i| img(i).size(px(small * 1.8)).rounded(px(6.)).object_fit(ObjectFit::Cover)))
            .child(
                div()
                    .flex()
                    .flex_col()
                    .child(div().text_size(px(small * 0.62)).font_weight(FontWeight::SEMIBOLD).child(track.title.clone()))
                    .child(div().text_size(px(small * 0.5)).text_color(white().opacity(0.6)).child(track.artist.clone())),
            )
            .when(lyrics.is_none(), |d| d.child(div().pl(px(small)).text_size(px(small * 0.4)).text_color(white().opacity(0.35)).child(tr("Testo non trovato"))));
        root.children(backdrop).child(shade).child(words).child(credit)
    }
}

