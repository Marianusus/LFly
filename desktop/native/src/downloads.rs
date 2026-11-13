//! The songs downloaded to this computer, and the ones on their way (Tauri: downloads.svelte.ts).
//! lfly-core does the work; this keeps what the views show and tells them when it changes.

use std::collections::{HashMap, HashSet};
use std::sync::Arc;

use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use lfly_connect::protocol::TrackRef;
use lfly_core::downloads::{DownloadEvent, DownloadList, Downloads};

use crate::core::{runtime, Core};
use crate::toast::Toasts;

pub struct DownloadsModel {
    d: Option<Arc<Downloads>>,
    pub list: DownloadList,
    ids: HashSet<String>,
    /// Songs on their way: id → 0..1.
    pub progress: HashMap<String, f32>,
}

struct DownloadsGlobal(Entity<DownloadsModel>);
impl Global for DownloadsGlobal {}

impl DownloadsModel {
    pub fn start(core: &Arc<Core>, cx: &mut App) -> Entity<Self> {
        let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<DownloadEvent>();
        let d = lfly_core::downloads::start(core.yt.clone(), runtime().handle().clone(), move |e| {
            let _ = tx.send(e);
        });
        if d.is_none() {
            log::warn!("downloads: no folder");
        }
        let e = cx.new(|_| {
            let mut m = Self { d, list: DownloadList::default(), ids: HashSet::new(), progress: HashMap::new() };
            m.refresh();
            m
        });
        let weak = e.downgrade();
        cx.spawn(async move |cx| {
            while let Some(ev) = rx.recv().await {
                if weak.update(cx, |m, cx| m.apply(ev, cx)).is_err() {
                    break;
                }
            }
        })
        .detach();
        cx.set_global(DownloadsGlobal(e.clone()));
        e
    }

    pub fn entity(cx: &App) -> Entity<Self> {
        cx.global::<DownloadsGlobal>().0.clone()
    }

    fn refresh(&mut self) {
        if let Some(d) = &self.d {
            self.list = d.list();
            self.ids = self.list.tracks.iter().map(|t| t.id.clone()).collect();
        }
    }

    fn apply(&mut self, ev: DownloadEvent, cx: &mut Context<Self>) {
        match ev {
            DownloadEvent::Changed => self.refresh(),
            DownloadEvent::Progress { id, done } if done < 0. => {
                self.progress.remove(&id);
                Toasts::say("Un download non è riuscito: riprova più tardi.", cx);
            }
            DownloadEvent::Progress { id, done } if done >= 1. => {
                self.progress.remove(&id);
            }
            DownloadEvent::Progress { id, done } => {
                // Per chunk: a redraw only when the ring visibly moves.
                let old = self.progress.insert(id, done);
                if old.is_some_and(|o| (done - o).abs() < 0.02) {
                    return;
                }
            }
        }
        cx.notify();
    }

    pub fn has(&self, id: &str) -> bool {
        self.ids.contains(id)
    }

    pub fn pending(&self, id: &str) -> bool {
        self.progress.contains_key(id)
    }

    /// Downloads these songs (those not here yet), and says so.
    pub fn add(&mut self, tracks: Vec<TrackRef>, cx: &mut Context<Self>) {
        let Some(d) = self.d.clone() else {
            Toasts::say("Non riesco a creare la cartella dei download", cx);
            return;
        };
        let fresh: Vec<TrackRef> = tracks.into_iter().filter(|t| !self.has(&t.id) && !self.pending(&t.id)).collect();
        if fresh.is_empty() {
            return;
        }
        Toasts::say(if fresh.len() == 1 { "Download avviato".to_string() } else { format!("Download di {} brani avviato", fresh.len()) }, cx);
        for t in &fresh {
            self.progress.insert(t.id.clone(), 0.);
        }
        d.add(fresh);
        cx.notify();
    }

    pub fn remove(&mut self, ids: Vec<String>, cx: &mut Context<Self>) {
        if let Some(d) = &self.d {
            d.remove(&ids);
            self.refresh();
            cx.notify();
        }
    }

    pub fn folder(&self) -> Option<std::path::PathBuf> {
        self.d.as_ref().map(|d| d.dir().to_path_buf())
    }
}

/// A ring of [frac] (0..1) round a [size] px circle, from the top, clockwise.
fn ring(frac: f32, size: f32, width: f32, track: Hsla, fill: Hsla) -> impl IntoElement {
    canvas(
        |_, _, _| {},
        move |b, _, window, _| {
            let r = (size - width) / 2.;
            let c = point(b.origin.x + px(size / 2.), b.origin.y + px(size / 2.));
            let arc = |from: f32, to: f32| {
                let mut p = PathBuilder::stroke(px(width));
                let steps = ((to - from) * 64.).ceil().max(1.) as usize;
                for k in 0..=steps {
                    let a = (from + (to - from) * k as f32 / steps as f32) * std::f32::consts::TAU - std::f32::consts::FRAC_PI_2;
                    let at = point(c.x + px(r * a.cos()), c.y + px(r * a.sin()));
                    if k == 0 { p.move_to(at) } else { p.line_to(at) }
                }
                p.build().ok()
            };
            if let Some(p) = arc(0., 1.) {
                window.paint_path(p, track);
            }
            if frac > 0. {
                if let Some(p) = arc(0., frac.min(1.)) {
                    window.paint_path(p, fill);
                }
            }
        },
    )
    .absolute()
    .inset_0()
}

/// DownloadButton.svelte: a 44 px glass disc that downloads [tracks], fills a ring as they
/// land ("3/12" beside it) and, once all are here, turns accent with a tick and removes them.
pub fn download_button(id: impl Into<ElementId>, tracks: Vec<TrackRef>, s: &crate::theme::Scheme, cx: &App) -> AnyElement {
    let dl = DownloadsModel::entity(cx);
    let m = dl.read(cx);
    let done = tracks.iter().filter(|t| m.has(&t.id)).count();
    let going = tracks.iter().filter(|t| m.pending(&t.id)).count();
    let n = tracks.len();
    let all = n > 0 && done == n;
    // Each song on its way counts with how far it got.
    let partial: f32 = tracks.iter().filter_map(|t| m.progress.get(&t.id)).sum();
    let frac = if n > 0 { (done as f32 + partial) / n as f32 } else { 0. };
    let accent = s.accent.hsla(1.);
    let ink = if all { s.on_accent.hsla(1.) } else { s.text };
    let group: SharedString = "dl-btn".into();
    div()
        .flex()
        .items_center()
        .gap(px(8.))
        .child(
            div()
                .id(id)
                .group(group.clone())
                .relative()
                .size(px(44.))
                .flex_none()
                .rounded_full()
                .flex()
                .items_center()
                .justify_center()
                .when(all, |d| d.bg(accent))
                .when(!all, |d| d.bg(s.glass_tint).border_1().border_color(s.glass_border))
                .when(n == 0, |d| d.opacity(0.4))
                .when(n > 0, |d| {
                    d.cursor_pointer().when(!all, |d| d.hover(|d| d.bg(s.text.opacity(0.14)))).on_click(move |_, _, cx| {
                        let tracks = tracks.clone();
                        dl.update(cx, |m, cx| if all { m.remove(tracks.iter().map(|t| t.id.clone()).collect(), cx) } else { m.add(tracks, cx) })
                    })
                })
                .when(going > 0, |d| d.child(ring(frac, 44., 2.5, s.text.opacity(0.14), accent)))
                .child(crate::icons::icon(if all { "check" } else { "download" }, 20., ink)),
        )
        .when(going > 0, |d| d.child(div().text_size(px(11.5)).text_color(s.text2).child(format!("{done}/{n}"))))
        .into_any_element()
}
