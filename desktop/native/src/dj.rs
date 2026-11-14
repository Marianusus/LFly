//! The DJ in the window (the phone's HomeDj card and DjIsland), captions only for now: the shared
//! session lives in lfly-core (`dj::session`); this keeps its last state and shows its lines.
//!
//! `LFLY_DJ=<seconds>` (tests) starts the DJ that long after launch.

use std::sync::Arc;
use std::time::{Duration, Instant};

use gpui_kit::*;
use lfly_core::dj::session::DjState;
use lfly_core::dj::{Block, BlockKind};

use crate::core::Core;
use crate::glass;
use crate::look::Look;

/// How long a line stays: about as long as it would take to say, within limits.
fn line_seconds(line: &str) -> f32 {
    (line.chars().count() as f32 * 0.065).clamp(4.5, 12.)
}

const IN: f32 = 0.32;
const OUT: f32 = 0.24;

pub struct DjModel {
    core: Arc<Core>,
    pub state: DjState,
    /// When the last line arrived.
    pub line_at: Option<Instant>,
}

struct DjGlobal(Entity<DjModel>);
impl Global for DjGlobal {}

impl DjModel {
    pub fn new(core: Arc<Core>, cx: &mut App) -> Entity<Self> {
        let updates = core.dj_updates.lock().unwrap_or_else(|e| e.into_inner()).take();
        let e = cx.new(|cx: &mut Context<Self>| {
            if let Some(mut rx) = updates {
                cx.spawn(async move |this, cx| {
                    while let Some(state) = rx.recv().await {
                        if this.update(cx, |m, cx| m.apply(state, cx)).is_err() {
                            break;
                        }
                    }
                })
                .detach();
            }
            if let Some(secs) = std::env::var("LFLY_DJ").ok().and_then(|v| v.parse::<f64>().ok()) {
                let core = core.clone();
                cx.spawn(async move |_, cx| {
                    cx.background_executor().timer(Duration::from_secs_f64(secs)).await;
                    log::info!("LFLY_DJ: starting the DJ");
                    core.dj.start();
                })
                .detach();
            }
            Self { core, state: DjState::default(), line_at: None }
        });
        cx.set_global(DjGlobal(e.clone()));
        e
    }

    pub fn entity(cx: &App) -> Option<Entity<Self>> {
        cx.try_global::<DjGlobal>().map(|g| g.0.clone())
    }

    fn apply(&mut self, mut state: DjState, cx: &mut Context<Self>) {
        if let Some(text) = state.notice.take() {
            crate::toast::Toasts::say(text, cx);
            return;
        }
        if state.line.as_ref().map(|l| l.1) != self.state.line.as_ref().map(|l| l.1) && state.line.is_some() {
            self.line_at = Some(Instant::now());
            // Wake when the caption should fade, and once more when it is gone.
            let secs = state.line.as_ref().map_or(0., |l| line_seconds(&l.0));
            cx.spawn(async move |this, cx| {
                cx.background_executor().timer(Duration::from_secs_f32(secs)).await;
                let _ = this.update(cx, |_, cx| cx.notify());
                cx.background_executor().timer(Duration::from_secs_f32(OUT + 0.05)).await;
                let _ = this.update(cx, |_, cx| cx.notify());
            })
            .detach();
        }
        if !state.active {
            self.line_at = None;
        }
        self.state = state;
        cx.notify();
    }

    /// "Avvia il DJ" / "Ferma il DJ".
    pub fn toggle(&self) {
        if self.state.active {
            self.core.dj.stop();
        } else {
            self.core.dj.start();
        }
    }
}

/// The phone's `djBlockLabel`: what the block playing is.
pub fn block_label(block: &Block) -> String {
    match (block.kind, block.subject.as_deref()) {
        (BlockKind::Flow, _) => "Un flow dai tuoi preferiti".into(),
        (BlockKind::TopPlayed, _) => "I brani che ascolti di più".into(),
        (BlockKind::FollowedArtist, Some(s)) => format!("Dedicato a {s}"),
        (BlockKind::FollowedArtist, None) => "Dagli artisti che segui".into(),
        (BlockKind::Discovery, _) => "Nuove scoperte per te".into(),
        (BlockKind::Throwback, _) => "Preferiti che non senti da un po'".into(),
        (BlockKind::Mix, Some(s)) => format!("Dal mix {s}"),
        (BlockKind::Mix, None) => "Dai tuoi mix".into(),
    }
}

/// The DJ's line at the top of the window while it would be spoken: the toast's glass pill, with
/// what the block is above the line.
pub struct DjIsland {
    look: Entity<Look>,
    dj: Entity<DjModel>,
    _sub: Subscription,
}

impl DjIsland {
    pub fn new(look: Entity<Look>, dj: Entity<DjModel>, cx: &mut Context<Self>) -> Self {
        let sub = cx.observe(&dj, |_, _, cx| cx.notify());
        Self { look, dj, _sub: sub }
    }
}

impl Render for DjIsland {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let m = self.dj.read(cx);
        let (Some((line, _)), Some(at)) = (m.state.line.clone(), m.line_at) else { return div().into_any_element() };
        let label = m.state.block.as_ref().map(block_label);
        let show = line_seconds(&line);
        let t = at.elapsed().as_secs_f32();
        if t >= show + OUT {
            return div().into_any_element();
        }
        // In: down from 12 px above (cubicOut); out: a fade while it lifts 6 px. With reduced
        // motion it only fades.
        let travel = if crate::prefs::Prefs::reduce_motion(cx) { 0. } else { 1. };
        let (dy, alpha) = if t < IN {
            let e = crate::motion::CUBIC_OUT.at((t / IN) as f64) as f32;
            (-12. * (1. - e) * travel, e)
        } else if t > show {
            let k = ((t - show) / OUT).min(1.);
            (-6. * k * travel, 1. - k)
        } else {
            (0., 1.)
        };
        if t < IN || t > show {
            window.request_animation_frame();
        }
        let (s, mut look) = {
            let l = self.look.read(cx);
            (l.scheme.clone(), l.glass())
        };
        look.tint = s.surface.hsla(0.8);
        let accent = s.accent.hsla(1.);
        div()
            .absolute()
            .left_0()
            .right_0()
            .top(px(64. + dy))
            .flex()
            .justify_center()
            .opacity(alpha)
            .child(
                div()
                    .relative()
                    .max_w(px(560.))
                    .mx(px(24.))
                    .rounded(px(22.))
                    .shadow(vec![BoxShadow { color: black().opacity(0.45), offset: point(px(0.), px(18.)), blur_radius: px(48.), spread_radius: px(0.), inset: false }])
                    .child(glass::glass_layer(look, px(22.), glass::Pointer::default()))
                    .child(
                        div()
                            .relative()
                            .flex()
                            .flex_col()
                            .gap(px(4.))
                            .py(px(12.))
                            .px(px(20.))
                            .child(
                                div()
                                    .flex()
                                    .items_center()
                                    .gap(px(8.))
                                    .text_size(px(11.))
                                    .font_weight(FontWeight::BOLD)
                                    .text_color(accent)
                                    .child(crate::icons::icon("sparkles", 12., accent))
                                    .child(label.map_or("DJ".to_string(), |l| format!("DJ · {l}"))),
                            )
                            .child(div().text_size(px(14.)).font_weight(FontWeight::SEMIBOLD).line_height(relative(1.35)).text_color(s.text).child(line)),
                    ),
            )
            .into_any_element()
    }
}
