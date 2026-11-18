//! AUTOPILOT: the Console plays a list by itself on its two decks, as a DJ would, and shows each
//! step. The deck playing is "live"; the next song goes on the other deck, is analysed, put on
//! the live deck's tempo and cued on its first downbeat; on a bar near the live song's end it
//! starts there, its bars on the live one's, and over 32 beats the crossfader moves across with
//! the basses swapped halfway. The deck let go loads the song after, and so on to the list's end.
//!
//! Songs whose tempos are far apart (over 10%) are not beat matched: a short fade instead. A hand
//! on the decks (a fader, the platter, transport, a load) takes the controls back: the autopilot
//! disengages, as a plane's does.

use std::time::{Duration, Instant};

use gpui_kit::*;
use lfly_connect::protocol::TrackRef;

use crate::deck_model::{DjDecks, PadMode, Status};
use lfly_audio::decks::FxKind;
use lfly_core::decks::Grid;
use lfly_mix::curves::{self, MixRecipe, MixStyle, TransitionStyle};
use lfly_mix::{effects, planner};
use crate::i18n::{tr, trf};

/// A fade between songs that are not matched, in seconds.
const FADE_SECONDS: f64 = 8.;
/// The most the incoming song's tempo is moved to match the live one (the planner's limit).
const MATCH_LIMIT: f64 = 0.10;
/// How often the autopilot looks at the decks.
const TICK: Duration = Duration::from_millis(40);
/// Steps of the log kept for the page.
const LOG_KEPT: usize = 30;

/// Where a change of song is.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Phase {
    /// The first song loading and starting.
    Starting,
    /// The next song loading and being analysed.
    Loading,
    /// Ready, matched and cued, waiting for its bar.
    Waiting,
    /// The two songs playing together.
    Mixing,
    /// The last song playing, nothing after it.
    Last,
}

#[derive(Clone, Copy, Debug)]
pub struct MixPlan {
    /// When the incoming song starts, in the live song's seconds (a bar line).
    pub start: f64,
    /// How long the mix lasts, in the live song's seconds.
    pub span: f64,
    /// Where the incoming song starts, in its seconds.
    pub entry: f64,
    /// Beat matched (else the styles that need no matching).
    pub matched: bool,
    pub style: TransitionStyle,
    pub recipe: MixRecipe,
    pub bars: u32,
    /// The incoming song's rise lands as the mix ends (a drop).
    pub lands_rise: bool,
    /// Brings the outgoing song to the incoming one's loudness.
    pub out_gain: f32,
    pub began: Option<Instant>,
    /// The loop roll's length now (0: none yet).
    loop_beats: f64,
    half_said: bool,
}

impl MixPlan {
    #[allow(clippy::too_many_arguments)]
    fn new(start: f64, span: f64, matched: bool, style: TransitionStyle, bars: u32, lands_rise: bool, out_gain: f32, entry: f64) -> Self {
        MixPlan { start, span, entry, matched, style, recipe: style.recipe(), bars, lands_rise, out_gain, began: None, loop_beats: 0., half_said: false }
    }
}

pub struct Autopilot {
    pub list: Vec<TrackRef>,
    /// What the list is ("Up Next", a playlist's name).
    pub name: String,
    /// The next song of the list to load.
    pub next: usize,
    pub live: usize,
    pub phase: Phase,
    pub plan: Option<MixPlan>,
    /// What it did, the latest last.
    pub log: Vec<String>,
    started: bool,
    /// A move on the live song going on (a build, an echo throw…), and the phrase last judged.
    perform: Option<Move>,
    judged_phrase: i64,
    last_move_beat: f64,
    rng: u64,
    /// Plays Up Next as it is now (songs added later too), not a copy of a list.
    from_upnext: bool,
    /// No moves on the song before this (just engaged, just handed over).
    quiet_until: std::time::Instant,
}

impl Autopilot {
    fn say(&mut self, line: String) {
        log::info!("autopilot: {line}");
        self.log.push(line);
        if self.log.len() > LOG_KEPT {
            self.log.remove(0);
        }
    }
}

fn letter(d: usize) -> &'static str {
    ["A", "B"][d]
}

fn clock(s: f64) -> String {
    let s = s.max(0.) as u64;
    format!("{}:{:02}", s / 60, s % 60)
}

impl DjDecks {
    pub fn autopilot_on(&self) -> bool {
        self.autopilot.is_some()
    }

    /// Engages the autopilot on [list] (named [name]): the deck playing stays live and the list
    /// follows it; with none playing, the list's first song starts on a free deck.
    pub fn autopilot_start(&mut self, list: Vec<TrackRef>, name: String, cx: &mut Context<Self>) {
        // No list given: Up Next, read live.
        let from_upnext = list.is_empty();
        if from_upnext && self.upnext.is_empty() && !self.decks.iter().any(|d| d.track.is_some()) {
            crate::toast::Toasts::say(tr("AUTOPILOT: la lista è vuota"), cx);
            return;
        }
        let playing = (0..2).find(|d| self.is_playing(*d));
        // Live: the deck playing; else one with a song ready to start; else a free one.
        let ready = (0..2).find(|d| self.decks[*d].track.is_some() && !matches!(self.decks[*d].status, Status::Failed(_)));
        let live = playing.or(ready).unwrap_or_else(|| (0..2).find(|d| self.decks[*d].track.is_none()).unwrap_or(0));
        let seed = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(7, |d| d.as_nanos() as u64) | 1;
        let count = if from_upnext { self.upnext.len() } else { list.len() };
        let mut ap = Autopilot {
            list,
            name,
            next: 0,
            live,
            phase: Phase::Starting,
            plan: None,
            log: Vec::new(),
            started: playing.is_some(),
            perform: None,
            judged_phrase: i64::MIN,
            last_move_beat: f64::MIN,
            rng: seed,
            from_upnext,
            quiet_until: Instant::now() + Duration::from_secs(20),
        };
        ap.say(trf("Inserito su «{0}», {1}", &[&ap.name, &crate::ui::brani(count as u64)]));
        // Up Next's songs already on the decks leave it (they are playing or next).
        if from_upnext {
            let on: Vec<String> = self.decks.iter().filter_map(|d| d.track.as_ref().map(|t| t.id.clone())).collect();
            let before = self.upnext.len();
            self.upnext.retain(|t| !on.contains(&t.id));
            if self.upnext.len() != before {
                self.upnext_changed(cx);
            }
        }
        // A list's songs already on the decks are not played again: it goes on after them.
        for d in 0..2 {
            let id = self.decks[d].track.as_ref().map(|t| t.id.clone());
            if let Some(i) = id.and_then(|id| ap.list.iter().position(|t| t.id == id)) {
                ap.next = ap.next.max(i + 1);
            }
        }
        if let Some(d) = playing {
            ap.phase = Phase::Loading;
            ap.say(trf("Deck {0} in onda: si parte da lì", &[&letter(d)]));
            // The song waiting on the other deck (the DJ's choice) is the next one.
            if let Some(t) = self.decks[1 - d].track.as_ref() {
                ap.say(trf("{0}: «{1}» è il prossimo", &[&letter(1 - d), &t.title]));
            }
        }
        self.autopilot = Some(ap);
        self.pending = None;
        if let Some(d) = playing {
            self.set_xfader(0.5, cx);
            self.decks[d].volume = 1.;
            self.apply_mixer(d);
        }
        cx.notify();
        cx.spawn(async move |this, cx| loop {
            cx.background_executor().timer(TICK).await;
            let go_on = this.update(cx, |me, cx| me.autopilot_tick(cx)).unwrap_or(false);
            if !go_on {
                break;
            }
        })
        .detach();
    }

    /// Disengages it ([why] said in the log and a toast; None when the AP button did it).
    pub fn autopilot_stop(&mut self, why: Option<&str>, cx: &mut Context<Self>) {
        // A move half done (a filter half way up) is put back first, and a loop it rolls let go.
        if let Some(live) = self.autopilot.as_ref().map(|a| a.live) {
            self.perform_end(live);
            for d in 0..2 {
                if matches!(self.decks[d].pad_held, Some((PadMode::Roll, _))) {
                    if let Some(m) = self.engine() {
                        m.roll(d, None);
                    }
                    self.decks[d].pad_held = None;
                }
            }
        }
        if self.autopilot.take().is_some() {
            if let Some(w) = why {
                crate::toast::Toasts::say(w.to_string(), cx);
            }
            log::info!("autopilot off");
            cx.notify();
        }
    }

    /// A hand on the decks: the autopilot gives the controls back.
    pub fn user_touch(&mut self, cx: &mut Context<Self>) {
        if self.autopilot.is_some() && !self.ap_driving {
            self.autopilot_stop(Some(tr("AUTOPILOT disinserito: i comandi sono tuoi")), cx);
        }
    }

    fn ap_say(&mut self, line: String, cx: &mut Context<Self>) {
        if let Some(ap) = self.autopilot.as_mut() {
            ap.say(line);
        }
        cx.notify();
    }

    /// The deck's song is in and analysed (or the analysis is over without a grid).
    fn deck_ready(&self, d: usize) -> bool {
        self.decks[d].status == Status::Ready && self.decks[d].buf.as_ref().is_some_and(|b| b.is_done()) && !self.decks[d].analysing && !self.decks[d].peaks.is_empty()
    }

    /// The song after: Up Next's first (taken off it) or the list's next.
    fn ap_next_song(&mut self, cx: &mut Context<Self>) -> Option<TrackRef> {
        let from_upnext = self.autopilot.as_ref()?.from_upnext;
        if from_upnext {
            return self.upnext_take(cx);
        }
        let ap = self.autopilot.as_mut()?;
        let t = ap.list.get(ap.next).cloned();
        ap.next += 1;
        t
    }

    /// One look at the decks; false once the autopilot is off.
    fn autopilot_tick(&mut self, cx: &mut Context<Self>) -> bool {
        let Some(ap) = self.autopilot.as_ref() else { return false };
        let (live, inc) = (ap.live, 1 - ap.live);
        self.ap_driving = true;
        let go_on = self.ap_step(live, inc, cx);
        self.ap_driving = false;
        go_on
    }

    fn ap_step(&mut self, live: usize, inc: usize, cx: &mut Context<Self>) -> bool {
        let Some(ap) = self.autopilot.as_mut() else { return false };

        // ---- The first song ----
        if !ap.started {
            if self.decks[live].track.is_none() {
                let Some(t) = self.ap_next_song(cx) else {
                    self.autopilot_stop(Some(tr("AUTOPILOT: la lista è finita")), cx);
                    return false;
                };
                self.ap_say(trf("{0}: carico «{1}»", &[&letter(live), &t.title]), cx);
                self.load_now(live, t, cx);
                return true;
            }
            if let Status::Failed(_) = self.decks[live].status {
                let title = self.decks[live].track.as_ref().map(|t| t.title.clone()).unwrap_or_default();
                self.ap_say(trf("«{0}» non si apre: salto", &[&title]), cx);
                self.eject(live, cx);
                return true;
            }
            if self.decks[live].status != Status::Ready {
                return true;
            }
            // The crossfader in the middle (the faders and the curves do the mixing), plain EQ.
            self.set_xfader(0.5, cx);
            self.set_volume(live, 1., cx);
            for k in 1..=3 {
                self.set_knob(live, k, 0.5, cx);
            }
            self.play(live, true, cx);
            let title = self.decks[live].track.as_ref().map(|t| t.title.clone()).unwrap_or_default();
            if let Some(ap) = self.autopilot.as_mut() {
                ap.started = true;
                ap.phase = Phase::Loading;
                ap.say(trf("{0} in onda: «{1}»", &[&letter(live), &title]));
            }
            cx.notify();
            return true;
        }

        let phase = ap.phase;
        let live_playing = self.is_playing(live);

        // ---- The next song onto the other deck ----
        if phase != Phase::Mixing && self.decks[inc].track.is_none() {
            let next = self.ap_next_song(cx);
            let Some(ap) = self.autopilot.as_mut() else { return false };
            match next {
                Some(t) => {
                    ap.phase = Phase::Loading;
                    ap.plan = None;
                    ap.say(trf("{0}: carico «{1}»", &[&letter(inc), &t.title]));
                    self.load_now(inc, t.clone(), cx);
                    // Its stems split while the live song plays (one voice at a time in the mix).
                    if lfly_core::stems::model_ready() && !lfly_core::stems::cached(&t.id) {
                        crate::stems_model::StemsModel::entity(cx).update(cx, |m, cx| m.prepare(t, true, cx));
                    }
                }
                None => {
                    if ap.phase != Phase::Last {
                        ap.phase = Phase::Last;
                        ap.say(if ap.from_upnext { tr("Up Next è vuota: aggiungi brani e li mixo") } else { tr("Ultimo brano della lista") }.to_string());
                        cx.notify();
                    }
                    if !live_playing {
                        self.autopilot_stop(Some(tr("AUTOPILOT: la lista è finita")), cx);
                        return false;
                    }
                }
            }
            return true;
        }
        if let Status::Failed(_) = self.decks[inc].status {
            let title = self.decks[inc].track.as_ref().map(|t| t.title.clone()).unwrap_or_default();
            self.ap_say(trf("«{0}» non si apre: salto", &[&title]), cx);
            self.eject(inc, cx);
            return true;
        }

        // The live song stopped by itself before a mix (it ended, the next was not ready): the
        // next one starts as soon as it can.
        if !live_playing && phase != Phase::Mixing {
            if self.deck_ready(inc) {
                self.set_xfader(0.5, cx);
                self.decks[inc].knobs = [0.5; 5];
                self.set_volume(inc, 1., cx);
                self.play(inc, true, cx);
                self.ap_hand_over(live, inc, cx);
            }
            return true;
        }

        // Between mixes it plays the live song as a DJ would: a move now and then, on its phrases.
        if phase != Phase::Mixing && phase != Phase::Starting {
            self.ap_perform(live, cx);
        }
        match phase {
            Phase::Starting | Phase::Last => true,
            Phase::Loading => {
                if self.deck_ready(inc) {
                    self.ap_plan(live, inc, cx);
                }
                true
            }
            Phase::Waiting => {
                let Some(plan) = self.autopilot.as_ref().and_then(|a| a.plan) else { return true };
                if self.position(live) + 0.02 >= plan.start {
                    self.ap_begin_mix(live, inc, cx);
                }
                true
            }
            Phase::Mixing => {
                self.ap_mixing(live, inc, cx);
                true
            }
        }
    }

    /// The incoming song is in: the Automix planner (the phone's) picks where the live song hands
    /// over (on its outro), where the incoming one starts (its intro, its rise landing as the mix
    /// ends), how many bars, and the style; the tempo goes onto the live one's, the song is cued
    /// there with a hot cue marking it.
    fn ap_plan(&mut self, live: usize, inc: usize, cx: &mut Context<Self>) {
        let dur = self.decks[live].duration();
        let here = self.position(live);
        let (lg, ig) = (self.decks[live].grid, self.decks[inc].grid);
        let la = self.analysis_of(live).cloned().unwrap_or_default();
        let ia = self.analysis_of(inc).cloned().unwrap_or_default();
        let ids = (self.decks[live].track.as_ref().map(|t| t.id.clone()).unwrap_or_default(), self.decks[inc].track.as_ref().map(|t| t.id.clone()).unwrap_or_default());
        let seed = curves::pair_seed(&ids.0, &ids.1);
        let calm = la.energy.as_ref().is_some_and(|e| e.is_calm) || ia.energy.as_ref().is_some_and(|e| e.is_calm);
        let out_gain = lfly_mix::loudness::gain(lfly_mix::loudness::match_gain_db(la.lufs, ia.lufs));
        let ratio = self.decks[live].ratio();

        // Tempo: the live one's heard (half or double time counts as the same).
        let mut tempo_ok = false;
        if let (Some(target), Some(ig)) = (self.decks[live].bpm(), ig) {
            let mut r = target / ig.bpm;
            for k in [0.5, 2.] {
                if (r * k - 1.).abs() < (r - 1.).abs() {
                    r *= k;
                }
            }
            if (r - 1.).abs() <= MATCH_LIMIT {
                tempo_ok = true;
                self.set_ratio(inc, target / ig.bpm);
                if !self.decks[inc].keylock {
                    self.toggle_keylock(inc, cx);
                }
                self.flash(inc, Lamp::Sync);
                self.ap_say(trf("SYNC: {0} a {1} BPM", &[&letter(inc), &format!("{:.1}", target)]), cx);
            } else {
                self.ap_say(trf("Tempi troppo diversi ({0} e {1} BPM): dissolvenza", &[&format!("{:.0}", target), &format!("{:.0}", ig.bpm)]), cx);
            }
        }

        let planned = match (&la.beats, &ia.beats, tempo_ok, lg) {
            (Some(lb), Some(ib), true, Some(_)) => planner::plan(lb, (dur * 1000.) as u64, ib, la.energy.as_ref(), ia.energy.as_ref()),
            _ => None,
        };
        let plan = match (planned, lg) {
            (Some(p), Some(g)) => {
                let bars = p.mix_bars.max(2);
                // The planner's bars on this deck's grid (its own tempo count may be off).
                let span = bars as f64 * 4. * g.beat_seconds();
                let mut start = p.exit_ms as f64 / 1000.;
                if start < here + 1. {
                    start = next_bar(&g, here + 1.);
                }
                let style = curves::resolve(MixStyle::Auto, true, calm, p.lands_rise, seed);
                MixPlan::new(start, span, true, style, bars, p.lands_rise, out_gain, p.entry_ms as f64 / 1000.)
            }
            _ if tempo_ok && lg.is_some() => {
                // No plan (a short song, a weak grid): 8 bars on a bar near the end.
                let g = lg.unwrap_or(Grid { bpm: 120., first_beat: 0., first_in_bar: 0 });
                let span = 32. * g.beat_seconds();
                let start = next_bar(&g, (dur - span - 4.).max(here + 1.));
                let style = curves::resolve(MixStyle::Auto, true, calm, false, seed);
                MixPlan::new(start, span, true, style, 8, false, out_gain, first_downbeat(ig))
            }
            _ => {
                let style = curves::resolve(MixStyle::Auto, false, calm, false, seed);
                let span = FADE_SECONDS * ratio;
                let outro = la.energy.as_ref().and_then(|e| e.outro_start).filter(|o| *o + span < dur - 1. && *o > here + 1.);
                let start = outro.unwrap_or((dur - span - 2.).max(here + 1.));
                MixPlan::new(start, span, false, style, 4, false, out_gain, first_downbeat(ig))
            }
        };
        // Cued at its entry, a hot cue on it (pad 1 lights up).
        self.decks[inc].cue = plan.entry;
        self.decks[inc].hot[0] = Some(plan.entry);
        self.decks[inc].pads = PadMode::HotCue;
        self.seek(inc, plan.entry, cx);
        self.flash(inc, Lamp::Cue);
        // Silent until the mix (the curves bring it in).
        self.decks[inc].volume = 0.;
        self.apply_mixer(inc);
        let when = clock(plan.start);
        let what = style_name(plan.style);
        if let Some(ap) = self.autopilot.as_mut() {
            ap.say(trf("Mix alle {0}: {1}, {2} battute{3}", &[&when, &what, &(plan.bars * 4), &if plan.lands_rise { " → DROP" } else { "" }]));
            ap.plan = Some(plan);
            ap.phase = Phase::Waiting;
        }
        cx.notify();
    }

    /// The bar is here: the incoming song starts, on the live one's beat.
    fn ap_begin_mix(&mut self, live: usize, inc: usize, cx: &mut Context<Self>) {
        self.perform_end(live);
        let Some(plan) = self.autopilot.as_ref().and_then(|a| a.plan) else { return };
        if plan.matched {
            // The tempo once more, in case the live one moved since (its pitch easing back).
            if let (Some(target), Some(ig)) = (self.decks[live].bpm(), self.decks[inc].grid) {
                self.set_ratio(inc, target / ig.bpm);
            }
        }
        self.ap_frame(live, inc, &plan, 0.);
        self.play(inc, true, cx);
        self.flash(inc, Lamp::Play);
        if plan.matched {
            self.beat_lock(live, inc, 1.);
        }
        let title = self.decks[inc].track.as_ref().map(|t| t.title.clone()).unwrap_or_default();
        if let Some(ap) = self.autopilot.as_mut() {
            if let Some(p) = ap.plan.as_mut() {
                p.began = Some(Instant::now());
            }
            ap.phase = Phase::Mixing;
            ap.say(trf("{0} entra: «{1}»", &[&letter(inc), &title]));
        }
        cx.notify();
    }

    /// The incoming deck's beat laid on the live one's: the phase gap (under half a beat) closed by
    /// [share] of it. Called through the mix, it keeps the two locked (beat sync).
    fn beat_lock(&mut self, live: usize, inc: usize, share: f64) {
        let (Some(lg), Some(ig)) = (self.decks[live].grid, self.decks[inc].grid) else { return };
        let lp = lg.beat_at(self.position(live)).rem_euclid(1.);
        let ip = ig.beat_at(self.position(inc)).rem_euclid(1.);
        let mut diff = lp - ip;
        if diff > 0.5 {
            diff -= 1.;
        } else if diff < -0.5 {
            diff += 1.;
        }
        // Under 5 ms the ear hears one beat: left alone, the engine's own drift is far smaller.
        if (diff * ig.beat_seconds()).abs() < 0.005 && share < 1. {
            return;
        }
        if let Some(m) = self.engine() {
            m.nudge(inc, diff * share * ig.beat_seconds() * self.decks[inc].rate());
        }
    }

    /// One instant of the mix on the controls: faders, gain, EQ and filter of both decks as the
    /// style's curves say, its send effect and its loop.
    fn ap_frame(&mut self, live: usize, inc: usize, plan: &MixPlan, p: f32) {
        let beat_share = 1. / (plan.bars as f32 * 4.);
        let f = curves::frame(&plan.recipe, p, plan.out_gain, curves::SWAP_AT, beat_share);
        self.apply_deck_mix(live, &f.outgoing);
        self.apply_deck_mix(inc, &f.incoming);
        // With stems on both decks, one voice at a time: the incoming song's vocals wait until the
        // outgoing ones leave, around the middle (no two singers over each other).
        if plan.matched && self.decks[live].stems.is_some() && self.decks[inc].stems.is_some() {
            let k = ((p - 0.42) / 0.16).clamp(0., 1.);
            let k = k * k * (3. - 2. * k);
            self.set_stem(inc, 0, k);
            self.set_stem(live, 0, 1. - k);
            self.flash(inc, Lamp::Stem);
        }
        // The send effect on the outgoing song: its deck's first slot, lit while it sends.
        let fx = effects::effect(plan.recipe.effect, p, beat_share, 0.1);
        if plan.recipe.effect != effects::EffectCurve::None {
            let kind = match plan.recipe.effect.kind() {
                effects::EffectKind::Reverb => FxKind::Reverb,
                effects::EffectKind::Noise => FxKind::Noise,
                _ => FxKind::Echo,
            };
            let d = &mut self.decks[live];
            d.fx[0] = kind;
            d.fx_on[0] = fx.send > 0.02;
            d.fx_amount[0] = (fx.send * 0.9).clamp(0., 1.);
            d.volume *= fx.dry.clamp(0., 1.).sqrt();
            self.apply_mixer(live);
        }
    }

    /// A deck's fader, gain, three bands and filter set from the curves' values.
    fn apply_deck_mix(&mut self, d: usize, m: &curves::DeckMix) {
        let before = (self.decks[d].knobs, self.decks[d].volume);
        self.ap_set_deck(d, m);
        let after = (self.decks[d].knobs, self.decks[d].volume);
        for k in 0..5 {
            if (after.0[k] - before.0[k]).abs() > 0.002 {
                self.flash(d, Lamp::Knob(k));
            }
        }
        if (after.1 - before.1).abs() > 0.002 {
            self.flash(d, Lamp::Fader);
        }
    }

    fn ap_set_deck(&mut self, d: usize, m: &curves::DeckMix) {
        let deck = &mut self.decks[d];
        // The channel fader has an audio taper (level = fader²).
        deck.volume = m.volume.clamp(0., 1.).sqrt();
        let f = &m.filter;
        deck.knobs[0] = (0.5 + 20. * f.gain.max(1e-3).log10() / 24.).clamp(0., 1.);
        deck.knobs[1] = knob_of_db(f.high_db);
        deck.knobs[2] = knob_of_db(f.mid_db);
        deck.knobs[3] = knob_of_db(f.bass_db);
        // One filter on the knob: the high-pass when it is closing in, else the low-pass.
        let fl = if f.high_pass_hz > 25. {
            ((f.high_pass_hz / 20.).ln() / 400f32.ln()).clamp(0.03, 1.)
        } else if f.low_pass_hz > 0. && f.low_pass_hz < 18_000. {
            -((f.low_pass_hz / 20_000.).ln() / (40f32 / 20_000.).ln()).clamp(0.03, 1.)
        } else {
            0.
        };
        deck.knobs[4] = 0.5 + fl / 2.;
        self.apply_mixer(d);
    }

    /// The mix going on: the curves over its progress, the beats kept locked, the loop rolled.
    fn ap_mixing(&mut self, live: usize, inc: usize, cx: &mut Context<Self>) {
        let Some(plan) = self.autopilot.as_ref().and_then(|a| a.plan) else { return };
        let Some(began) = plan.began else { return };
        let ratio = self.decks[live].ratio();
        let p = (began.elapsed().as_secs_f64() * ratio / plan.span.max(0.5)).clamp(0., 1.) as f32;
        self.ap_frame(live, inc, &plan, p);
        if plan.matched {
            // Beat sync: a third of any gap closed at each look.
            self.beat_lock(live, inc, 0.34);
            self.flash(inc, Lamp::Sync);
        }
        // The loop on the outgoing song (a loop roll into the drop): its pads light up.
        if let Some(lf) = effects::loop_at(plan.recipe.looping, p, plan.bars as f64 * 4.) {
            if self.decks[live].grid.is_some() && (lf.length_beats - plan.loop_beats).abs() > 1e-6 {
                let beat = self.decks[live].beat();
                let a = plan.start + lf.anchor_beats * beat;
                let b = a + lf.length_beats * beat;
                let rate = self.decks[live].rate();
                if let Some(m) = self.engine() {
                    m.roll(live, Some((a * rate, b * rate)));
                }
                self.decks[live].pads = PadMode::Roll;
                let pad = crate::deck_model::ROLL_SIZES.iter().position(|r| (*r - lf.length_beats).abs() < 1e-6).unwrap_or(0);
                self.decks[live].pad_held = Some((PadMode::Roll, pad));
                if let Some(pl) = self.autopilot.as_mut().and_then(|a| a.plan.as_mut()) {
                    if pl.loop_beats == 0. {
                        pl.loop_beats = lf.length_beats;
                        self.ap_say(trf("LOOP ROLL su {0}", &[&letter(live)]), cx);
                    } else {
                        pl.loop_beats = lf.length_beats;
                    }
                }
            }
        }
        let half = p >= curves::SWAP_AT;
        if half && !plan.half_said {
            if let Some(pl) = self.autopilot.as_mut().and_then(|a| a.plan.as_mut()) {
                pl.half_said = true;
            }
            self.ap_say(trf("Metà mix: bassi da {0} a {1}", &[&letter(live), &letter(inc)]), cx);
            if plan.matched && self.decks[live].stems.is_some() && self.decks[inc].stems.is_some() {
                self.ap_say(trf("STEMS: la voce passa da {0} a {1}", &[&letter(live), &letter(inc)]), cx);
            }
        }
        cx.notify();
        if p >= 1. {
            if plan.lands_rise {
                self.ap_say(tr("DROP").to_string(), cx);
            }
            self.ap_hand_over(live, inc, cx);
        }
    }

    /// The incoming deck is live now: the old one stops and lets its song go, every control of
    /// both back to the middle, and the list goes on.
    fn ap_hand_over(&mut self, live: usize, inc: usize, cx: &mut Context<Self>) {
        if let Some(m) = self.engine() {
            m.roll(live, None);
        }
        self.decks[live].pad_held = None;
        self.play(live, false, cx);
        for d in [live, inc] {
            let deck = &mut self.decks[d];
            deck.knobs = [0.5; 5];
            deck.stem_gain = [1.; 4];
            deck.volume = 1.;
            deck.fx_on = [false; crate::deck_model::FX_COUNT];
            self.apply_mixer(d);
        }
        self.set_xfader(0.5, cx);
        // The tempo eased back to the song's own over a while (it was moved to match).
        self.pitch_glide(inc, 20., cx);
        let title = self.decks[inc].track.as_ref().map(|t| t.title.clone()).unwrap_or_default();
        self.eject(live, cx);
        if let Some(ap) = self.autopilot.as_mut() {
            ap.live = inc;
            ap.plan = None;
            ap.phase = Phase::Loading;
            ap.quiet_until = Instant::now() + Duration::from_secs(20);
            ap.say(trf("{0} in onda: «{1}»", &[&letter(inc), &title]));
        }
        cx.notify();
    }

    /// What the page shows of the change going on: its step, seconds to the mix, the mix's share,
    /// and the style.
    pub fn autopilot_status(&self) -> Option<(Phase, Option<f64>, Option<f32>, Option<String>)> {
        let ap = self.autopilot.as_ref()?;
        let ratio = self.decks[ap.live].ratio();
        let to_mix = ap.plan.filter(|_| ap.phase == Phase::Waiting).map(|p| (p.start - self.position(ap.live)) / ratio);
        let mixed = ap.plan.and_then(|p| p.began.map(|b| (b.elapsed().as_secs_f64() * ratio / p.span.max(0.5)).min(1.) as f32));
        let style = ap.plan.map(|p| format!("{} · {}", style_name(p.style), p.bars * 4));
        Some((ap.phase, to_mix, mixed, style))
    }

    /// Where the mix falls on each deck's song, to mark on its waveform: the live song's stretch,
    /// the incoming one's.
    pub fn autopilot_marks(&self) -> [Option<(f64, f64)>; 2] {
        let mut out = [None, None];
        if let Some((ap, p)) = self.autopilot.as_ref().and_then(|a| a.plan.map(|p| (a, p))) {
            out[ap.live] = Some((p.start, p.start + p.span));
            let k = self.decks[ap.live].ratio() / self.decks[1 - ap.live].ratio();
            out[1 - ap.live] = Some((p.entry, p.entry + p.span * k));
        }
        out
    }

    /// A control the autopilot just used, lit for a moment on the page.
    pub(crate) fn flash(&mut self, d: usize, lamp: Lamp) {
        self.lamps.insert((d, lamp), Instant::now());
    }

    /// Whether [lamp] of deck [d] was used by the autopilot in the last moment.
    pub fn lit(&self, d: usize, lamp: Lamp) -> bool {
        self.autopilot.is_some() && self.lamps.get(&(d, lamp)).is_some_and(|t| t.elapsed() < Duration::from_millis(450))
    }
}

/// The controls the autopilot lights when it presses them.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Lamp {
    Sync,
    Cue,
    Play,
    /// A mixer knob: 0 gain, 1 high, 2 mid, 3 low, 4 filter.
    Knob(usize),
    Fader,
    Stem,
}

/// The moves a DJ makes on a song between mixes.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum MoveKind {
    /// Sixteen beats of a rising high-pass and echo into a louder phrase, let go on its downbeat.
    Build,
    /// An echo on the phrase's last two beats.
    EchoThrow,
    /// The bass out on the phrase's last beat, back on the downbeat.
    BassKill,
    /// The low-pass dipping and back over the phrase's second half.
    Wobble,
}

#[derive(Clone, Copy, Debug)]
pub struct Move {
    kind: MoveKind,
    /// The phrase's first beat (on the live grid).
    phrase: f64,
}

/// Beats in a phrase.
const PHRASE: f64 = 16.;
/// Beats between two moves at least.
const MOVE_GAP: f64 = 48.;

fn style_name(s: TransitionStyle) -> &'static str {
    match s {
        TransitionStyle::Fade => "FADE",
        TransitionStyle::BassSwap => "BASS SWAP",
        TransitionStyle::EqSwap => "EQ SWAP",
        TransitionStyle::Filter => "FILTER",
        TransitionStyle::Wave => "WAVE",
        TransitionStyle::Slam => "CUT",
        TransitionStyle::Rise => "RISE",
        TransitionStyle::EchoOut => "ECHO OUT",
        TransitionStyle::ReverbFade => "REVERB",
        TransitionStyle::RollDrop => "ROLL + DROP",
    }
}

/// A band's level in dB to its knob (0..1, 0.5 flat): the knob's gain is (knob / 0.5)² below the
/// middle, up to +6 dB above.
fn knob_of_db(db: f32) -> f32 {
    if db <= -60. {
        0.
    } else if db <= 0. {
        0.5 * 10f32.powf(db / 40.)
    } else {
        (0.5 + 0.5 * db / 6.).min(1.)
    }
}

/// The first bar line at or after [t].
fn next_bar(g: &Grid, t: f64) -> f64 {
    let fib = g.first_in_bar as f64;
    let mut k = g.beat_at(t).ceil();
    while (k + fib).rem_euclid(4.) != 0. {
        k += 1.;
    }
    g.time_of(k)
}

/// A song's first downbeat (its start without a grid).
fn first_downbeat(g: Option<Grid>) -> f64 {
    match g {
        Some(g) => {
            let k = (0..4).map(|k| k as f64).find(|k| (*k + g.first_in_bar as f64).rem_euclid(4.) == 0.).unwrap_or(0.);
            g.time_of(k).max(0.)
        }
        None => 0.,
    }
}

impl DjDecks {
    /// The live song's energy at [t] (dB under its loudest bar), from the analysis.
    fn energy_at(&self, d: usize, t: f64) -> Option<f64> {
        let e = self.analysis_of(d)?.energy.as_ref()?;
        let i = e.bar_starts.iter().rposition(|b| *b <= t)?;
        e.bar_energy_db.get(i).copied()
    }

    fn ap_rand(&mut self) -> u64 {
        let Some(ap) = self.autopilot.as_mut() else { return 0 };
        ap.rng ^= ap.rng << 13;
        ap.rng ^= ap.rng >> 7;
        ap.rng ^= ap.rng << 17;
        ap.rng
    }

    /// On each phrase of the live song: maybe a move (rarely twice running, never near a mix),
    /// then the move going on played beat by beat on the controls.
    fn ap_perform(&mut self, live: usize, cx: &mut Context<Self>) {
        let Some(g) = self.decks[live].grid else { return };
        if !self.is_playing(live) {
            return;
        }
        let here = self.position(live);
        let beat = g.beat_at(here);
        let fib = g.first_in_bar as f64;
        // The phrase the playhead is in (16 beats from a phrase's downbeat).
        let phrase = ((beat + fib) / PHRASE).floor() * PHRASE - fib;
        let mix_at = self.autopilot.as_ref().and_then(|a| a.plan).map(|p| g.beat_at(p.start));

        if let Some(mv) = self.autopilot.as_ref().and_then(|a| a.perform) {
            self.ap_move_step(live, mv, beat, cx);
            return;
        }
        let Some(ap) = self.autopilot.as_mut() else { return };
        if ap.judged_phrase == phrase as i64 || beat - phrase > 2. {
            return;
        }
        ap.judged_phrase = phrase as i64;
        let last = ap.last_move_beat;
        // Not in the song's first bars, not just after engaging or a mix, never across the next
        // mix, not too often.
        let quiet = self.autopilot.as_ref().is_some_and(|a| Instant::now() < a.quiet_until);
        if here < 20. || quiet || beat - last < MOVE_GAP || mix_at.is_some_and(|m| phrase + PHRASE > m - 8.) {
            return;
        }
        let dur = self.decks[live].duration();
        if g.time_of(phrase + PHRASE) > dur - 10. {
            return;
        }
        // A louder phrase ahead (a drop after a breakdown): the build into it.
        let now_db = self.energy_at(live, g.time_of(phrase + 4.));
        let next_db = self.energy_at(live, g.time_of(phrase + PHRASE + 4.));
        let kind = match (now_db, next_db) {
            (Some(a), Some(b)) if b - a >= 4. => Some(MoveKind::Build),
            _ => {
                let r = self.ap_rand();
                // Now and then (a phrase in three), never a loop the listener would take for a fault.
                match r % 9 {
                    0 => Some(MoveKind::EchoThrow),
                    1 => Some(MoveKind::BassKill),
                    2 => Some(MoveKind::Wobble),
                    _ => None,
                }
            }
        };
        let Some(kind) = kind else { return };
        let what = match kind {
            MoveKind::Build => "FILTER BUILD → DROP",
            MoveKind::EchoThrow => "ECHO THROW",
            MoveKind::BassKill => "BASS KILL",
            MoveKind::Wobble => "FILTER WOBBLE",
        };
        if let Some(ap) = self.autopilot.as_mut() {
            ap.perform = Some(Move { kind, phrase });
            ap.last_move_beat = phrase + PHRASE;
            ap.say(format!("{what} · {}", letter(live)));
        }
        cx.notify();
    }

    /// One look at the move going on: its controls at this beat, and everything back once done.
    fn ap_move_step(&mut self, live: usize, mv: Move, beat: f64, cx: &mut Context<Self>) {
        let u = beat - mv.phrase;
        if u >= PHRASE {
            self.perform_end(live);
            if mv.kind == MoveKind::Build {
                self.ap_say(trf("DROP su {0}", &[&letter(live)]), cx);
            }
            cx.notify();
            return;
        }
        let deck = &mut self.decks[live];
        match mv.kind {
            MoveKind::Build => {
                // The high-pass climbs (eased in), the echo comes up over the second half.
                let k = (u / PHRASE) as f32;
                deck.knobs[4] = 0.5 + 0.38 * k * k;
                deck.fx[0] = FxKind::Echo;
                deck.fx_on[0] = k > 0.5;
                deck.fx_amount[0] = ((k - 0.5) * 1.4).clamp(0., 0.7);
                // The bass thins out on the last bar.
                deck.knobs[3] = if u >= PHRASE - 4. { 0.15 } else { 0.5 };
                self.flash(live, Lamp::Knob(4));
            }
            MoveKind::EchoThrow => {
                let on = u >= PHRASE - 2.;
                deck.fx[0] = FxKind::Echo;
                deck.fx_on[0] = on;
                deck.fx_amount[0] = 0.75;
            }
            MoveKind::BassKill => {
                if u >= PHRASE - 1. {
                    deck.knobs[3] = 0.;
                    self.flash(live, Lamp::Knob(3));
                }
            }
            MoveKind::Wobble => {
                if u >= PHRASE / 2. {
                    let k = ((u - PHRASE / 2.) / (PHRASE / 2.)) as f32;
                    deck.knobs[4] = 0.5 - 0.3 * (k * std::f32::consts::PI).sin();
                    self.flash(live, Lamp::Knob(4));
                }
            }
        }
        self.apply_mixer(live);
        cx.notify();
    }

    /// The move over: the controls it touched back where they were.
    fn perform_end(&mut self, live: usize) {
        if self.autopilot.as_mut().and_then(|a| a.perform.take()).is_none() {
            return;
        }
        let deck = &mut self.decks[live];
        deck.knobs[3] = 0.5;
        deck.knobs[4] = 0.5;
        deck.fx_on[0] = false;
        self.apply_mixer(live);
    }
}
