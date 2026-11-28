//! The Console's video out: a window of its own, for a projector or a screen behind the DJ, with
//! the lyrics of the deck the crowd hears over its cover's light (drifting, saturated, as the
//! lyrics page has it). The line sung is large and fills word by word in time, the one before
//! faint above it, the next two below; a new line rises in. Pauses breathe with three dots.
//! Words without times of their own are spread over the line by their length.
//!
//! The deck shown is the one heard most (playing, its fader, its side of the crossfader); during
//! an autopilot mix it changes halfway. A double click (or F) puts the window full screen, Esc
//! takes it out; the mixer's VIDEO button opens and closes it.

use std::ops::Range;
use std::rc::Rc;
use std::sync::Arc;
use std::time::{Duration, Instant};

use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use lfly_core::pages;

use crate::ambient::Ambient;
use crate::covers::Covers;
use crate::deck_model::DjDecks;
use crate::i18n::tr;
use crate::lyrics::LyricsData;

/// How early a line shows (the eye reads ahead of the voice).
const LEAD_MS: f64 = 250.;
/// How early the fill runs, just ahead of the voice.
const SWEEP_LEAD_MS: f64 = 60.;
/// How long a new line takes to rise in.
const ENTER_MS: f64 = 450.;
/// Words not sung yet, and what is sung.
const DIM: f32 = 0.3;
const LIT: f32 = 1.;

pub struct VideoOut {
    dj: Entity<DjDecks>,
    lyrics: Entity<LyricsData>,
    covers: Entity<Covers>,
    /// The cover's light behind everything.
    ambient: Entity<Ambient>,
    /// The deck shown last (kept while the two are about as loud, so it does not flicker).
    shown: usize,
    ticking: bool,
    /// The line shown large (song, index) and when it came, for its entrance.
    current: (Option<(String, usize)>, Instant),
    _subs: Vec<Subscription>,
}

impl VideoOut {
    /// Opens the window; its handle closes it again.
    pub fn open(core: Arc<crate::core::Core>, dj: Entity<DjDecks>, lyrics: Entity<LyricsData>, covers: Entity<Covers>, cx: &mut App) -> Option<AnyWindowHandle> {
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
                    let ambient = cx.new(|_| {
                        let mut a = Ambient::new(core, 0.45, 80.);
                        a.saturation = 2.5;
                        a
                    });
                    VideoOut { dj, lyrics, covers, ambient, shown: 0, ticking: false, current: (None, Instant::now()), _subs: subs }
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

    /// Sixty frames a second while a deck plays and the window is open (the fill moves smoothly).
    fn keep_ticking(&mut self, cx: &mut Context<Self>) {
        if self.ticking {
            return;
        }
        self.ticking = true;
        cx.spawn(async move |this, cx| loop {
            cx.background_executor().timer(Duration::from_millis(16)).await;
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

/// One line's words: where each sits in [text] and when it is sung (ms).
struct Timed {
    text: String,
    words: Vec<(Range<usize>, f64, f64)>,
}

/// [line]'s words in time: its own word timings when the source has them, else the line's time
/// shared by the words' length (no longer than such a line takes to sing, so a long pause after it
/// does not stretch the fill).
fn timed(line: &pages::LyricLine, next_ms: Option<i64>) -> Timed {
    if let Some(words) = line.words.as_ref().filter(|w| !w.is_empty()) {
        let glued: String = words.iter().map(|w| w.text.as_str()).collect();
        let sep = if glued.trim() == line.text.trim() { "" } else { " " };
        let mut text = String::new();
        let mut out = Vec::new();
        for (k, w) in words.iter().enumerate() {
            if k > 0 {
                text.push_str(sep);
            }
            let from = text.len();
            text.push_str(&w.text);
            out.push((from..text.len(), w.ms as f64, (w.end_ms.max(w.ms + 1)) as f64));
        }
        return Timed { text, words: out };
    }
    let text = line.text.trim().to_string();
    let start = line.ms as f64;
    let letters = text.chars().filter(|c| !c.is_whitespace()).count() as f64;
    let room = next_ms.map_or(f64::MAX, |n| (n - line.ms) as f64);
    let span = (letters * 75.).clamp(1200., 7000.).min(room * 0.92).max(1.);
    let mut pieces: Vec<Range<usize>> = Vec::new();
    let mut from = None;
    for (b, c) in text.char_indices() {
        match (c.is_whitespace(), from) {
            (false, None) => from = Some(b),
            (true, Some(f)) => {
                pieces.push(f..b);
                from = None;
            }
            _ => {}
        }
    }
    if let Some(f) = from {
        pieces.push(f..text.len());
    }
    let weight = |r: &Range<usize>| text[r.clone()].chars().count() as f64 + 1.;
    let total: f64 = pieces.iter().map(weight).sum::<f64>().max(1.);
    let mut at = start;
    let words = pieces
        .into_iter()
        .map(|r| {
            let d = span * weight(&r) / total;
            let w = (r, at, at + d);
            at += d;
            w
        })
        .collect();
    Timed { text, words }
}

/// Each letter's ink at [t]: the words fill from the left with a soft edge as they are sung.
fn fill(timed: &Timed, t: f64) -> Vec<(Range<usize>, f32)> {
    let mut out = Vec::new();
    for (r, from, until) in &timed.words {
        let word = &timed.text[r.clone()];
        let n = word.chars().count().max(1) as f32;
        let p = ((t - from) / (until - from)).clamp(0., 1.) as f32;
        let edge = -0.25 + 1.5 * p;
        for (j, (b, c)) in word.char_indices().enumerate() {
            let f = (j as f32 + 0.5) / n;
            let x = ((f - edge) / 0.35 + 0.5).clamp(0., 1.);
            let a = if t < *from { DIM } else if t >= *until { LIT } else { LIT - (LIT - DIM) * x };
            let at = r.start + b;
            out.push((at..at + c.len_utf8(), a));
        }
    }
    out
}

fn ease_out(x: f32) -> f32 {
    1. - (1. - x.clamp(0., 1.)).powi(3)
}

impl Render for VideoOut {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let playing = self.dj.read(cx).any_playing();
        if playing {
            self.keep_ticking(cx);
        }
        let heard = self.heard(cx);
        let (w, h) = (window.viewport_size().width / px(1.), window.viewport_size().height / px(1.));
        // Sizes follow the window: a projector or a laptop's second screen.
        let big = (h * 0.088).clamp(22., 128.);
        let small = big * 0.52;
        let root = div()
            .id("video-out")
            // The app's own font: this window does not inherit the main one's.
            .font_family(crate::ui_font(cx))
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
        let track = heard.and_then(|d| self.dj.read(cx).decks[d].track.clone());
        self.ambient.update(cx, |a, _| {
            a.url = track.as_ref().and_then(|t| t.cover_url.clone());
            a.width = w;
            a.height = h;
            a.bg = crate::theme::Rgb(0., 0., 0.);
            a.moving = playing;
        });
        let (Some(d), Some(track)) = (heard, track) else {
            return root.child(div().size_full().flex().items_center().justify_center().text_size(px(small)).font_weight(FontWeight::BOLD).text_color(white().opacity(0.3)).child("LFly"));
        };
        let (pos_ms, dur_ms) = {
            let dj = self.dj.read(cx);
            (dj.position(d) * 1000., dj.decks[d].duration().max(1.) * 1000.)
        };
        let cover = track.cover_url.clone().and_then(|u| self.covers.update(cx, |c, cx| c.get(&u, cx)));
        let offset = self.lyrics.read(cx).offset(&track.id);
        let lyrics: Option<Rc<pages::Lyrics>> = self.lyrics.update(cx, |l, cx| l.get(&track, cx)).flatten();
        let t_line = pos_ms + LEAD_MS - offset;
        let t = pos_ms + SWEEP_LEAD_MS - offset;

        let shade = div().absolute().inset_0().bg(linear_gradient(
            180.,
            linear_color_stop(black().opacity(0.2), 0.),
            linear_color_stop(black().opacity(0.62), 1.),
        ));
        let line_div = |text: String, size: f32, alpha: f32| {
            div()
                .w_full()
                .text_center()
                .text_size(px(size))
                .line_height(relative(1.15))
                .font_weight(FontWeight::BOLD)
                .text_color(white().opacity(alpha))
                .child(text)
        };
        // Three dots breathing, for a pause or the intro.
        let now_s = Instant::now().elapsed().as_secs_f32() + pos_ms as f32 / 1000.;
        let dots = |lit: f32| {
            div().flex().gap(px(big * 0.32)).py(px(big * 0.3)).children((0..3).map(|k| {
                let phase = ((now_s * 0.8 - k as f32 * 0.18) * std::f32::consts::TAU).sin() * 0.5 + 0.5;
                let on = ((lit * 3.) - k as f32).clamp(0., 1.);
                let d = big * 0.28 * (0.85 + 0.25 * phase);
                div().size(px(d)).rounded_full().bg(white().opacity(0.3 + 0.7 * on))
            }))
        };

        let mut stack: Vec<AnyElement> = Vec::new();
        match lyrics.as_ref().and_then(|l| l.synced.as_ref()).filter(|s| !s.is_empty()) {
            Some(synced) => {
                let i = synced.iter().rposition(|l| l.ms as f64 <= t_line);
                let text_at = |k: usize| synced.get(k).map(|l| l.text.trim().to_string()).filter(|s| !s.is_empty());
                // A new line rises in.
                let key = i.map(|i| (track.id.clone(), i));
                if key != self.current.0 {
                    self.current = (key, Instant::now());
                }
                let enter = ease_out((self.current.1.elapsed().as_secs_f64() * 1000. / ENTER_MS) as f32);
                if let Some(prev) = i.and_then(|i| i.checked_sub(1)).and_then(text_at) {
                    stack.push(line_div(prev, small, 0.22).into_any_element());
                }
                let next_ms = |k: usize| synced.get(k + 1).map(|l| l.ms);
                match i {
                    Some(i) if !synced[i].text.trim().is_empty() => {
                        let line = timed(&synced[i], next_ms(i));
                        let end = line.words.last().map_or(synced[i].ms as f64, |w| w.2);
                        let gap = next_ms(i).map_or(0., |n| n as f64 - t);
                        if t > end + 1500. && gap > 2500. {
                            // Sung, and a long pause before the next: the dots fill up to it.
                            let lit = ((t - end) / (gap + t - end)).clamp(0., 1.) as f32;
                            stack.push(dots(lit).into_any_element());
                        } else {
                            let runs: Vec<(Range<usize>, HighlightStyle)> = fill(&line, t)
                                .into_iter()
                                .map(|(r, a)| (r, HighlightStyle { color: Some(white().opacity(a * enter.max(0.2))), ..Default::default() }))
                                .collect();
                            stack.push(
                                div()
                                    .w_full()
                                    .text_center()
                                    .mt(px((1. - enter) * big * 0.6))
                                    .text_size(px(big))
                                    .line_height(relative(1.12))
                                    .font_weight(FontWeight::EXTRA_BOLD)
                                    .child(StyledText::new(SharedString::from(line.text.clone())).with_highlights(runs))
                                    .into_any_element(),
                            );
                            if let Some(bg) = synced[i].bg.clone().filter(|b| !b.trim().is_empty()) {
                                stack.push(line_div(bg, small * 0.8, 0.6 * enter).into_any_element());
                            }
                        }
                    }
                    Some(i) => {
                        // An empty line: an instrumental pause until the next one.
                        let (from, to) = (synced[i].ms as f64, next_ms(i).map_or(dur_ms, |n| n as f64));
                        stack.push(dots(((t - from) / (to - from).max(1.)).clamp(0., 1.) as f32).into_any_element());
                    }
                    None => {
                        // Before the first line: the song, then the dots.
                        stack.push(line_div(track.title.clone(), big * 0.9, 1.).into_any_element());
                        stack.push(line_div(track.artist.clone(), small, 0.6).into_any_element());
                        let first = synced[0].ms as f64;
                        stack.push(dots((t / first.max(1.)).clamp(0., 1.) as f32).into_any_element());
                    }
                }
                let next = i.map_or(0, |i| i + 1);
                for (k, alpha) in [(next, 0.5), (next + 1, 0.22)] {
                    if let Some(n) = text_at(k) {
                        stack.push(line_div(n, small, alpha).into_any_element());
                    }
                }
            }
            None => match lyrics.as_ref().and_then(|l| l.plain.clone()) {
                // Words without times: the part of the song the playhead is in.
                Some(plain) => {
                    let all: Vec<&str> = plain.lines().map(str::trim).filter(|l| !l.is_empty()).collect();
                    let k = ((t / dur_ms).clamp(0., 0.999) * all.len() as f64) as usize;
                    for (j, l) in all.iter().skip(k).take(3).enumerate() {
                        stack.push(line_div(l.to_string(), if j == 0 { big * 0.8 } else { small }, if j == 0 { 1. } else { 0.4 }).into_any_element());
                    }
                }
                None => {
                    stack.push(line_div(track.title.clone(), big, 1.).into_any_element());
                    stack.push(line_div(track.artist.clone(), small, 0.6).into_any_element());
                }
            },
        }
        let words = div().absolute().inset_0().flex().flex_col().items_center().justify_center().gap(px(big * 0.32)).px(px(w * 0.07)).pb(px(small * 2.)).children(stack);

        // Who and what, small at the bottom, over a thin line of the song's progress.
        let progress = (pos_ms / dur_ms).clamp(0., 1.) as f32;
        let credit = div()
            .absolute()
            .left(px(small))
            .right(px(small))
            .bottom(px(small * 0.7))
            .flex()
            .flex_col()
            .gap(px(small * 0.4))
            .child(
                div()
                    .flex()
                    .items_center()
                    .gap(px(small * 0.5))
                    .children(cover.map(|i| img(i).size(px(small * 1.6)).rounded(px(small * 0.2)).object_fit(ObjectFit::Cover)))
                    .child(
                        div()
                            .flex()
                            .flex_col()
                            .child(div().text_size(px(small * 0.6)).font_weight(FontWeight::BOLD).child(track.title.clone()))
                            .child(div().text_size(px(small * 0.48)).text_color(white().opacity(0.65)).child(track.artist.clone())),
                    )
                    .when(lyrics.is_none(), |d| d.child(div().pl(px(small)).text_size(px(small * 0.4)).text_color(white().opacity(0.35)).child(tr("Testo non trovato")))),
            )
            .child(
                div()
                    .w_full()
                    .h(px((small * 0.08).max(3.)))
                    .rounded_full()
                    .bg(white().opacity(0.15))
                    .child(div().h_full().w(relative(progress)).rounded_full().bg(white().opacity(0.85))),
            );
        root.child(self.ambient.clone()).child(shade).child(words).child(credit)
    }
}

#[cfg(test)]
mod tests {
    // Not `super::*`: gpui's own `test` attribute would shadow the standard one.
    use super::{fill, pages, timed, DIM, LIT};

    fn line(ms: i64, text: &str) -> pages::LyricLine {
        pages::LyricLine { ms, end_ms: None, text: text.into(), words: None, bg: None }
    }

    #[test]
    fn words_without_times_share_the_line_by_length() {
        let t = timed(&line(1000, "a longer word"), Some(10_000));
        assert_eq!(t.words.len(), 3);
        assert_eq!(&t.text[t.words[1].0.clone()], "longer");
        // In order, from the line's start, and short of the next line.
        assert_eq!(t.words[0].1, 1000.);
        assert!(t.words.windows(2).all(|w| w[0].2 <= w[1].1 + 1e-6));
        assert!(t.words[2].2 < 10_000.);
        // "longer" (7) takes longer than "a" (2).
        assert!(t.words[1].2 - t.words[1].1 > t.words[0].2 - t.words[0].1);
    }

    #[test]
    fn real_word_times_are_kept() {
        let mut l = line(0, "hi there");
        l.words = Some(vec![
            pages::LyricWord { ms: 100, end_ms: 400, text: "hi ".into() },
            pages::LyricWord { ms: 500, end_ms: 900, text: "there".into() },
        ]);
        let t = timed(&l, None);
        assert_eq!(t.text, "hi there");
        assert_eq!((t.words[1].1, t.words[1].2), (500., 900.));
    }

    #[test]
    fn the_fill_runs_from_dim_to_lit() {
        let t = timed(&line(0, "word"), Some(5_000));
        let (from, until) = (t.words[0].1, t.words[0].2);
        assert!(fill(&t, from - 1.).iter().all(|(_, a)| *a == DIM));
        assert!(fill(&t, until).iter().all(|(_, a)| *a == LIT));
        // Halfway: the first letter lit, the last still dim.
        let mid = fill(&t, (from + until) / 2.);
        assert!(mid[0].1 > 0.9 && mid[3].1 < 0.5, "{mid:?}");
    }
}
