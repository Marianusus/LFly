//! STEMS: the songs being split into their parts for the Console, one at a time in the
//! background, and the list of those to prepare ahead (kept across sessions).
//!
//! A song is split once (`lfly_core::stems` keeps the result on the disk); its stems then load
//! with it on a deck, and the mixer's stem buttons take its vocals, drums, bass or melody out. The
//! model is downloaded the first time a song is asked for. A song already on a deck is split from
//! the deck's own decoded copy; any other is decoded for the purpose.

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::Arc;
use std::time::Duration;

use gpui_kit::*;
use lfly_audio::decks::{Stems, TrackBuf};
use lfly_connect::protocol::TrackRef;

use crate::core::Core;
use crate::deck_model::DjDecks;
use crate::i18n::{tr, trf};

const STORE_LIST: &str = "console-stems";

#[derive(Clone, Debug, PartialEq)]
pub enum StemState {
    Waiting,
    /// The model downloading (share).
    Model(f32),
    Decoding,
    /// Being split (share).
    Splitting(f32),
    Ready,
    Failed(String),
}

impl StemState {
    /// A short word for the lists.
    pub fn label(&self) -> String {
        match self {
            StemState::Waiting => tr("in coda").to_string(),
            StemState::Model(p) => format!("{} {:.0}%", tr("modello"), p * 100.),
            StemState::Decoding => tr("lettura").to_string(),
            StemState::Splitting(p) => format!("{:.0}%", p * 100.),
            StemState::Ready => tr("pronti").to_string(),
            StemState::Failed(_) => tr("errore").to_string(),
        }
    }
}

pub struct StemsModel {
    core: Arc<Core>,
    /// The songs to prepare (and prepared), in order.
    pub list: Vec<TrackRef>,
    state: HashMap<String, StemState>,
    working: bool,
    cancel: Arc<AtomicBool>,
    /// Songs already decoded on a deck, handed over with the request (not decoded twice).
    bufs: HashMap<String, Arc<TrackBuf>>,
}

struct StemsGlobal(Entity<StemsModel>);
impl Global for StemsGlobal {}

impl StemsModel {
    pub fn start(core: Arc<Core>, cx: &mut App) -> Entity<Self> {
        let list: Vec<TrackRef> = if crate::deck_model::test_run() {
            Vec::new()
        } else {
            lfly_core::store::get(STORE_LIST).ok().flatten().and_then(|v| serde_json::from_value(v).ok()).unwrap_or_default()
        };
        let e = cx.new(|cx| {
            let mut me = StemsModel { core, list, state: HashMap::new(), working: false, cancel: Arc::new(AtomicBool::new(false)), bufs: HashMap::new() };
            for t in me.list.clone() {
                let s = if lfly_core::stems::cached(&t.id) { StemState::Ready } else { StemState::Waiting };
                me.state.insert(t.id.clone(), s);
            }
            me.work(cx);
            me
        });
        cx.set_global(StemsGlobal(e.clone()));
        e
    }

    pub fn entity(cx: &App) -> Entity<Self> {
        cx.global::<StemsGlobal>().0.clone()
    }

    fn save(&self) {
        if crate::deck_model::test_run() {
            return;
        }
        if let Ok(v) = serde_json::to_value(&self.list) {
            if let Err(e) = lfly_core::store::set(STORE_LIST, &v) {
                log::warn!("stems list not saved: {e}");
            }
        }
    }

    pub fn state(&self, id: &str) -> Option<&StemState> {
        self.state.get(id)
    }

    /// The song's stems asked for: into the list ([first]: next to be split), and work starts.
    pub fn prepare(&mut self, track: TrackRef, first: bool, cx: &mut Context<Self>) {
        self.prepare_from(track, first, None, cx);
    }

    /// As [`Self::prepare`], with the song already decoded on a deck.
    pub fn prepare_from(&mut self, track: TrackRef, first: bool, buf: Option<Arc<TrackBuf>>, cx: &mut Context<Self>) {
        if let Some(b) = buf {
            self.bufs.insert(track.id.clone(), b);
        }
        let ready = lfly_core::stems::cached(&track.id);
        if let Some(i) = self.list.iter().position(|t| t.id == track.id) {
            let t = self.list.remove(i);
            self.list.insert(if first { 0 } else { i }, t);
        } else if first {
            self.list.insert(0, track.clone());
        } else {
            self.list.push(track.clone());
        }
        match self.state.get(&track.id) {
            Some(StemState::Ready) if ready => {}
            Some(StemState::Model(_) | StemState::Decoding | StemState::Splitting(_)) => {}
            _ => {
                self.state.insert(track.id.clone(), if ready { StemState::Ready } else { StemState::Waiting });
            }
        }
        self.save();
        self.work(cx);
        cx.notify();
    }

    pub fn remove(&mut self, id: &str, cx: &mut Context<Self>) {
        self.list.retain(|t| t.id != id);
        if matches!(self.state.get(id), Some(StemState::Waiting | StemState::Failed(_) | StemState::Ready)) {
            self.state.remove(id);
        }
        self.save();
        cx.notify();
    }

    /// The next song waiting, split; then the next, until none waits.
    fn work(&mut self, cx: &mut Context<Self>) {
        if self.working {
            return;
        }
        let Some(track) = self.list.iter().find(|t| self.state.get(&t.id) == Some(&StemState::Waiting)).cloned() else {
            // Nothing more: the model's memory back.
            std::thread::spawn(lfly_core::stems::unload_model);
            return;
        };
        self.working = true;
        self.cancel.store(false, Ordering::Relaxed);
        let id = track.id.clone();
        let core = self.core.clone();
        let cancel = self.cancel.clone();
        // A copy already decoded on a deck saves decoding it again.
        let on_deck: Option<Arc<TrackBuf>> = self.bufs.remove(&id);
        let share = Arc::new(AtomicU32::new(0));
        let phase = Arc::new(AtomicU32::new(0));
        cx.spawn(async move |this, cx| {
            let (s2, p2, id2) = (share.clone(), phase.clone(), id.clone());
            let (tx, rx) = tokio::sync::oneshot::channel::<Result<Arc<Stems>, String>>();
            let (core2, cancel2, share2, phase2, id3) = (core.clone(), cancel.clone(), share.clone(), phase.clone(), id.clone());
            crate::core::runtime().spawn(async move {
                let result = async {
                    // 1: the model.
                    if !lfly_core::stems::model_ready() {
                        phase2.store(1, Ordering::Relaxed);
                        let s = share2.clone();
                        lfly_core::stems::download_model(move |got, total| s.store(((got as f64 / total.max(1) as f64) * 1000.) as u32, Ordering::Relaxed)).await?;
                    }
                    // 2: the song decoded whole.
                    phase2.store(2, Ordering::Relaxed);
                    let buf = match on_deck {
                        Some(b) => b,
                        None => {
                            let player = core2.player.clone();
                            let source = match crate::deck_model::local_path(&track) {
                                Some(p) => lfly_audio::Source::file(p),
                                None => player.source_of(&track, false).await?,
                            };
                            let cache = player.audio_cache();
                            tokio::task::spawn_blocking(move || TrackBuf::open(&source, &cache)).await.map_err(|e| e.to_string())??
                        }
                    };
                    while !buf.is_done() {
                        if cancel2.load(Ordering::Relaxed) {
                            buf.cancel();
                            return Err("cancelled".to_string());
                        }
                        tokio::time::sleep(Duration::from_millis(200)).await;
                    }
                    if let Some(e) = buf.error() {
                        return Err(e);
                    }
                    // 3: split.
                    phase2.store(3, Ordering::Relaxed);
                    let s = share2.clone();
                    tokio::task::spawn_blocking(move || lfly_core::stems::split(&id3, &buf, move |p| s.store((p * 1000.) as u32, Ordering::Relaxed), &cancel2)).await.map_err(|e| e.to_string())?
                }
                .await;
                let _ = tx.send(result);
            });
            // The progress shown while it works, four times a second.
            let mut rx = rx;
            let result = loop {
                match rx.try_recv() {
                    Ok(r) => break r,
                    Err(tokio::sync::oneshot::error::TryRecvError::Closed) => break Err("stopped".to_string()),
                    Err(tokio::sync::oneshot::error::TryRecvError::Empty) => {}
                }
                cx.background_executor().timer(Duration::from_millis(250)).await;
                let (k, p) = (p2.load(Ordering::Relaxed), s2.load(Ordering::Relaxed) as f32 / 1000.);
                let state = match k {
                    1 => StemState::Model(p),
                    2 => StemState::Decoding,
                    3 => StemState::Splitting(p),
                    _ => StemState::Waiting,
                };
                let id = id2.clone();
                let _ = this.update(cx, |m, cx| {
                    if m.state.get(&id) != Some(&state) {
                        m.state.insert(id, state);
                        cx.notify();
                    }
                });
            };
            let _ = this.update(cx, |m, cx| {
                m.working = false;
                match result {
                    Ok(stems) => {
                        m.state.insert(id.clone(), StemState::Ready);
                        if let Some(dj) = DjDecks::try_entity(cx) {
                            dj.update(cx, |dj, cx| dj.stems_ready(&id, stems, cx));
                        }
                    }
                    Err(e) => {
                        log::warn!("stems of {id}: {e}");
                        if e != "cancelled" {
                            crate::toast::Toasts::say(trf("Stem non preparati: {0}", &[&e]), cx);
                        }
                        m.state.insert(id.clone(), StemState::Failed(e));
                    }
                }
                cx.notify();
                m.work(cx);
            });
        })
        .detach();
    }
}
