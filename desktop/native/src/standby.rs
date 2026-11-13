//! Modalità Ambient (the phone's ui/ambient/AmbientScreen.kt, StandBy-style): the whole window,
//! full screen, as a bedside clock for the song. The cover on the left (44%); on the right the
//! time, the title, the artist, the line being sung with its sweep and a thin progress line.
//! The cover's light behind it, under a scrim darker towards the right.
//!
//! A click or the pointer moving brings the controls up over the cover (and the close button),
//! for 4 s. Every minute the content steps through eight offsets of a few px (burn-in, as the
//! phone does, a jump under reduced motion). Esc closes it.

use std::sync::Arc;
use std::time::{Duration, Instant};

use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use lfly_connect::protocol;
use lfly_core::pages;

use crate::ambient::Ambient;
use crate::covers::Covers;
use crate::icons::icon;
use crate::lyrics::LyricsData;
use crate::player_model::PlayerModel;
use crate::theme::Rgb;

const INK: u32 = 0xF5F5F7;
const INK2: u32 = 0xC7C7CC;
/// AmbientMode.kt BURN_IN_PATH (dp; px here, scaled with the rest).
const BURN_IN: [(f32, f32); 8] = [(0., 0.), (5., -3.), (-4., 4.), (6., 2.), (-6., -2.), (3., 5.), (-3., -5.), (2., 3.)];
const CONTROLS_FOR: Duration = Duration::from_secs(4);

pub struct StandBy {
    model: Entity<PlayerModel>,
    data: Entity<LyricsData>,
    covers: Entity<Covers>,
    ambient: Entity<Ambient>,
    /// The controls show until then (a click, the pointer moving).
    controls_until: Option<Instant>,
    controls_at: Instant,
    controls_on: bool,
    opened: Instant,
    /// Burn-in: the step and when it changed (for its 1.5 s ease).
    step: (usize, Instant),
    /// When the redraw on its way is due (one at a time; a sooner one replaces it).
    tick: Option<Instant>,
    _subs: Vec<Subscription>,
}

impl StandBy {
    pub fn new(core: Arc<crate::core::Core>, model: Entity<PlayerModel>, data: Entity<LyricsData>, covers: Entity<Covers>, cx: &mut Context<Self>) -> Self {
        let subs = vec![
            cx.observe(&model, |_, _, cx| cx.notify()),
            cx.observe(&data, |_, _, cx| cx.notify()),
            cx.observe(&covers, |_, _, cx| cx.notify()),
        ];
        // Darker than the full player's: it is a clock in the dark.
        let ambient = cx.new(|_| Ambient::new(core, 0.55, 64.));
        let now = Instant::now();
        Self { model, data, covers, ambient, controls_until: None, controls_at: now, controls_on: false, opened: now, step: (0, now), tick: None, _subs: subs }
    }

    fn poke(&mut self, cx: &mut Context<Self>) {
        self.controls_until = Some(Instant::now() + CONTROLS_FOR);
        if !self.controls_on {
            self.controls_on = true;
            self.controls_at = Instant::now();
        }
        cx.notify();
    }

    fn toggle_controls(&mut self, cx: &mut Context<Self>) {
        if self.controls_on {
            self.controls_on = false;
            self.controls_at = Instant::now();
            self.controls_until = None;
            cx.notify();
        } else {
            self.poke(cx);
        }
    }
}

/// "21:07" (24 h, as Italian clocks read).
fn clock_text() -> String {
    use chrono::Timelike;
    let now = chrono::Local::now();
    format!("{:02}:{:02}", now.hour(), now.minute())
}

/// The line sung at [t] ms and how much of it is sung (0..1, by its words when timed).
fn active_line(l: &pages::Lyrics, t: f64) -> Option<(&pages::LyricLine, f32)> {
    let lines = l.synced.as_ref()?;
    let i = lines.partition_point(|x| (x.ms as f64) <= t).checked_sub(1)?;
    let line = &lines[i];
    let end = line.end_ms.map(|e| e as f64).or_else(|| lines.get(i + 1).map(|n| n.ms as f64)).unwrap_or(line.ms as f64 + 4000.);
    // Past its end with a long gap before the next: nothing is sung.
    if t > end + 1500. && lines.get(i + 1).is_some_and(|n| n.ms as f64 > t) {
        return None;
    }
    let frac = match &line.words {
        Some(words) if !words.is_empty() => {
            let total: usize = words.iter().map(|w| w.text.chars().count()).sum();
            let mut sung = 0f32;
            for w in words {
                let n = w.text.chars().count() as f32;
                let (a, b) = (w.ms as f64, (w.end_ms as f64).max(w.ms as f64 + 1.));
                sung += n * ((t - a) / (b - a)).clamp(0., 1.) as f32;
            }
            sung / total.max(1) as f32
        }
        _ => ((t - line.ms as f64) / (end - line.ms as f64).max(1.)).clamp(0., 1.) as f32,
    };
    Some((line, frac))
}

impl Render for StandBy {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let reduce = crate::prefs::Prefs::reduce_motion(cx);
        let size = window.viewport_size();
        let (w, h) = (f32::from(size.width), f32::from(size.height));
        let m = self.model.read(cx);
        let track = m.current().cloned();
        let playing = m.playing();
        let pos = m.live_position();
        let dur = m.duration_ms() as f64;
        let now = Instant::now();

        // The controls fade (200 ms) and go after 4 s.
        if self.controls_until.is_some_and(|t| now >= t) {
            self.controls_until = None;
            self.controls_on = false;
            self.controls_at = now;
        }
        let since = now.duration_since(self.controls_at).as_secs_f32();
        let fade = if reduce { 1. } else { (since / 0.2).min(1.) };
        let controls = if self.controls_on { fade } else { 1. - fade };

        // Burn-in: a step a minute, eased over 1.5 s (a jump under reduced motion).
        let step = (now.duration_since(self.opened).as_secs() / 60) as usize % BURN_IN.len();
        if step != self.step.0 {
            self.step = (step, now);
        }
        let k = (h / 480.).clamp(1., 2.2);
        let (from, to) = (BURN_IN[(self.step.0 + BURN_IN.len() - 1) % BURN_IN.len()], BURN_IN[self.step.0]);
        let e = if reduce || self.step.1 == self.opened { 1. } else { (now.duration_since(self.step.1).as_secs_f32() / 1.5).min(1.) };
        let e = 1. - (1. - e) * (1. - e);
        let (bx, by) = ((from.0 + (to.0 - from.0) * e) * k, (from.1 + (to.1 - from.1) * e) * k);

        // The line being sung (lit whole when the lyrics go line by line, Settings > Testi).
        let words = crate::prefs::Prefs::get(cx).lyrics_words;
        let mut has_lyrics = false;
        let lyric = track.as_ref().and_then(|t| {
            let offset = self.data.read(cx).offset(&t.id);
            let l = self.data.update(cx, |d, cx| d.get(t, cx)).flatten()?;
            has_lyrics = l.synced.as_ref().is_some_and(|s| !s.is_empty());
            let (line, frac) = active_line(&l, pos - offset)?;
            Some((line.text.clone(), if words { frac } else { 1. }))
        });

        // Redraws: 30 a second while a line is swept, the controls fade or the burn-in eases;
        // otherwise once a second (the clock, the progress line).
        let busy = (lyric.is_some() && playing) || (since < 0.25 && !reduce) || e < 1.;
        let wait = Duration::from_millis(if busy && window.is_window_active() { 33 } else { 1000 });
        let due = now + self.controls_until.map_or(wait, |u| u.saturating_duration_since(now).min(wait)).max(Duration::from_millis(16));
        if self.tick.is_none_or(|t| due + Duration::from_millis(5) < t) {
            self.tick = Some(due);
            cx.spawn(async move |this, cx| {
                cx.background_executor().timer(due.saturating_duration_since(Instant::now())).await;
                let _ = this.update(cx, |this, cx| {
                    // A sooner one took its place: that one redraws.
                    if this.tick == Some(due) {
                        this.tick = None;
                        cx.notify();
                    }
                });
            })
            .detach();
        }

        let ink = Rgb::hex(INK);
        let cover = track.as_ref().and_then(|t| t.cover_url.as_ref()).and_then(|u| {
            self.covers.update(cx, |c, cx| {
                let big = u.contains("=w544-h544").then(|| u.replace("=w544-h544", "=w1200-h1200")).and_then(|b| c.get(&b, cx));
                big.or_else(|| c.get(u, cx))
            })
        });
        self.ambient.update(cx, |a, _| {
            a.url = track.as_ref().and_then(|t| t.cover_url.clone());
            a.width = w;
            a.height = h;
            a.bg = crate::theme::Rgb(0., 0., 0.);
            a.moving = playing;
        });

        let left_w = w * 0.44;
        let side = (left_w - 64. * k.min(1.5)).min(h - 64. * k.min(1.5)).max(80.);
        // A video's thumbnail is 16:9: whole, in a 16:9 frame (as in the full player).
        let wide = track.as_ref().and_then(|t| t.cover_url.as_deref()).is_some_and(|u| u.contains("ytimg.com"));
        let cover_h = if wide { side * 9. / 16. } else { side };
        let radius = 20. * k.min(1.5);
        let right_w = w * 0.56 - 32. * k;
        let clock_size = (right_w / 2.9).min(h * 0.3).min(92. * k * 1.6);
        let me = cx.entity().downgrade();
        let model = self.model.clone();
        let cmd = move |action: &'static str| {
            let model = model.clone();
            let me = me.clone();
            move |_: &ClickEvent, _: &mut Window, cx: &mut App| {
                cx.stop_propagation();
                model.read(cx).command(action, 0);
                let _ = me.update(cx, |this, cx| this.poke(cx));
            }
        };
        let transport = |id: &'static str, name: &'static str, size: f32, action: &'static str| {
            div()
                .id(id)
                .size(px(size * 1.9))
                .rounded_full()
                .flex()
                .items_center()
                .justify_center()
                .cursor_pointer()
                .hover(|d| d.bg(white().opacity(0.14)))
                .active(|d| d.bg(white().opacity(0.24)))
                .on_click(cmd(action))
                .child(icon(name, size, ink.hsla(1.)))
        };
        let (me2, me3) = (cx.entity().downgrade(), cx.entity().downgrade());

        // Two lines kept for it whenever the song has timed lyrics (AmbientScreen.kt minLines = 2):
        // the column does not jump as lines come and go.
        let lyric_box = has_lyrics.then(|| div().mt(px(12. * k)).h(px(2. * 1.3 * 20. * k)));
        let lyric_el = lyric.map(|(text, frac)| {
            // The sweep: what is sung in full ink, the rest at 64%, a character at a time.
            let n = text.chars().count();
            let cut = text.char_indices().nth(((n as f32) * frac).round() as usize).map_or(text.len(), |(b, _)| b);
            let rest = ink.hsla(0.64);
            let runs = vec![(0..cut, HighlightStyle { color: Some(ink.hsla(1.)), ..Default::default() }), (cut..text.len(), HighlightStyle { color: Some(rest), ..Default::default() })];
            div()
                .text_size(px(20. * k))
                .font_weight(FontWeight::MEDIUM)
                .line_height(relative(1.3))
                .line_clamp(2)
                .text_ellipsis()
                .child(StyledText::new(SharedString::from(text)).with_highlights(runs.into_iter().filter(|(r, _)| !r.is_empty())))
        });

        div()
            .id("standby")
            .absolute()
            .inset_0()
            .bg(black())
            .text_color(ink.hsla(1.))
            .on_mouse_move(move |_, _, cx| {
                let _ = me2.update(cx, |this, cx| this.poke(cx));
            })
            .on_click(move |_, _, cx| {
                let _ = me3.update(cx, |this, cx| this.toggle_controls(cx));
            })
            .child(self.ambient.clone())
            // AmbientHeroBackground's scrim: 0.30 at the left edge rising to 0.72 at 40%, then 0.72
            // to the right (0.30 everywhere, and over it 0 → 0.60 then 0.60: 0.30 + 0.70 × 0.60).
            .child(div().absolute().inset_0().bg(black().opacity(0.30)))
            .child(div().absolute().top_0().bottom_0().left_0().w(relative(0.4)).bg(linear_gradient(90., linear_color_stop(black().opacity(0.), 0.), linear_color_stop(black().opacity(0.60), 1.))))
            .child(div().absolute().top_0().bottom_0().right_0().w(relative(0.6)).bg(black().opacity(0.60)))
            .child(
                div()
                    .absolute()
                    .left(px(bx))
                    .top(px(by))
                    .size_full()
                    .flex()
                    .items_center()
                    // The cover, the controls over it.
                    .child(
                        div().w(px(left_w)).h_full().flex().items_center().justify_center().child(
                            div()
                                .relative()
                                .w(px(side))
                                .h(px(cover_h))
                                .rounded(px(radius))
                                .overflow_hidden()
                                .when(cover.is_none(), |d| d.bg(Rgb::hex(0x1C1C1E).hsla(1.)))
                                .flex()
                                .items_center()
                                .justify_center()
                                .when(cover.is_none(), |d| d.child(icon("music", 96., Rgb::hex(INK2).hsla(1.))))
                                .children(cover.map(|i| crate::covers::fade(i.id(), img(i).size_full().object_fit(ObjectFit::Cover).rounded(px(radius)))))
                                .when(controls > 0., |d| {
                                    d.child(
                                        div()
                                            .absolute()
                                            .inset_0()
                                            .rounded(px(radius))
                                            .bg(black().opacity(0.46))
                                            .opacity(controls)
                                            .flex()
                                            .items_center()
                                            .justify_center()
                                            .gap(px(18. * k))
                                            .child(transport("sb-prev", "previous", 26. * k, protocol::CMD_PREVIOUS))
                                            .child(transport("sb-play", if playing { "pause" } else { "play" }, 34. * k, protocol::CMD_TOGGLE))
                                            .child(transport("sb-next", "next", 26. * k, protocol::CMD_NEXT)),
                                    )
                                }),
                        ),
                    )
                    // The time and the song.
                    .child(
                        div()
                            .w(px(right_w))
                            .h_full()
                            .pt(px(16. * k))
                            .pb(px(16. * k))
                            .relative()
                            .flex()
                            .flex_col()
                            .justify_center()
                            .child(div().text_size(px(clock_size)).font_weight(FontWeight::MEDIUM).font_features(crate::ui::tnum()).line_height(relative(1.)).child(clock_text()))
                            .child(div().mt(px(8. * k)).text_size(px(26. * k)).font_weight(FontWeight::SEMIBOLD).truncate().child(track.as_ref().map(|t| t.title.clone()).unwrap_or_default()))
                            .child(div().mt(px(4. * k)).text_size(px(18. * k)).text_color(Rgb::hex(INK2).hsla(1.)).truncate().child(track.as_ref().map(|t| t.artist.clone()).unwrap_or_default()))
                            .children(lyric_box.map(|b| b.children(lyric_el)))
                            // The progress line, 3 px, along the bottom.
                            .child(
                                div()
                                    .absolute()
                                    .left_0()
                                    .right_0()
                                    .bottom(px(16. * k))
                                    .h(px(3.))
                                    .rounded_full()
                                    .bg(white().opacity(0.24))
                                    .overflow_hidden()
                                    .child(div().h_full().rounded_full().bg(ink.hsla(1.)).w(relative(if dur > 0. { (pos / dur).clamp(0., 1.) as f32 } else { 0. }))),
                            ),
                    ),
            )
            // Close, top right, with the controls.
            .when(controls > 0., |d| {
                d.child(
                    div()
                        .id("sb-close")
                        .absolute()
                        .top(px(16.))
                        .right(px(16.))
                        .size(px(48.))
                        .rounded_full()
                        .flex()
                        .items_center()
                        .justify_center()
                        .opacity(controls)
                        .cursor_pointer()
                        .hover(|d| d.bg(white().opacity(0.14)))
                        .on_click(|_, window, cx| {
                            cx.stop_propagation();
                            close(window, cx);
                        })
                        .child(icon("close", 20., ink.hsla(1.))),
                )
            })
    }
}



struct StandByGlobal {
    view: Option<Entity<StandBy>>,
    /// The window was made full screen for it (and goes back when it closes).
    fullscreen: bool,
}
impl Global for StandByGlobal {}

/// Opens it (the full player's menu); the window goes full screen.
pub fn open(core: Arc<crate::core::Core>, model: Entity<PlayerModel>, data: Entity<LyricsData>, covers: Entity<Covers>, window: &mut Window, cx: &mut App) {
    if cx.try_global::<StandByGlobal>().is_some_and(|g| g.view.is_some()) {
        return;
    }
    let view = cx.new(|cx| StandBy::new(core, model, data, covers, cx));
    let fullscreen = !window.is_fullscreen() && std::env::var_os("LFLY_STANDBY").is_none();
    if fullscreen {
        window.toggle_fullscreen();
    }
    cx.set_global(StandByGlobal { view: Some(view), fullscreen });
    window.refresh();
}

/// Closes it (Esc, ✕), the window back to how it was.
pub fn close(window: &mut Window, cx: &mut App) {
    let Some(g) = cx.try_global::<StandByGlobal>() else { return };
    if g.view.is_none() {
        return;
    }
    if let Some(v) = g.view.clone() {
        let ambient = v.read(cx).ambient.clone();
        ambient.update(cx, |a, _| a.release(window));
    }
    let g = cx.global::<StandByGlobal>();
    if g.fullscreen && window.is_fullscreen() {
        window.toggle_fullscreen();
    }
    cx.set_global(StandByGlobal { view: None, fullscreen: false });
    window.refresh();
}

/// Asked from the full player's menu (it has no window): the window opens it at its next draw.
pub fn request(cx: &mut App) {
    cx.set_global(StandByRequest(true));
    cx.refresh_windows();
}

pub struct StandByRequest(pub bool);
impl Global for StandByRequest {}

/// Takes the request, if one is pending.
pub fn take_request(cx: &mut App) -> bool {
    let asked = cx.try_global::<StandByRequest>().is_some_and(|r| r.0);
    if asked {
        cx.set_global(StandByRequest(false));
    }
    asked
}

/// The open view, if any (the window draws it over everything).
pub fn view(cx: &App) -> Option<Entity<StandBy>> {
    cx.try_global::<StandByGlobal>().and_then(|g| g.view.clone())
}

#[cfg(test)]
mod tests {
    use super::active_line;
    use lfly_core::pages::{LyricLine, Lyrics};

    #[test]
    fn the_line_being_sung() {
        let line = |ms: i64, text: &str| LyricLine { ms, end_ms: None, text: text.into(), words: None, bg: None };
        let due = LyricLine { end_ms: Some(4000), ..line(3000, "due") };
        let l = Lyrics { synced: Some(vec![line(1000, "uno"), due, line(20_000, "tre")]), plain: None, source: "x".into(), instrumental: false };
        assert!(active_line(&l, 500.).is_none());
        let (a, f) = active_line(&l, 2000.).unwrap();
        assert_eq!((a.text.as_str(), f), ("uno", 0.5));
        // Long after "due" and long before "tre": nothing.
        assert!(active_line(&l, 15_000.).is_none());
        assert_eq!(active_line(&l, 21_000.).unwrap().0.text, "tre");
    }
}
