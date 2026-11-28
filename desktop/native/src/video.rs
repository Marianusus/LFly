//! The Console's video out: a window of its own, for a projector or a screen behind the DJ. What it
//! shows is picked in the mixer's settings card (VIDEO, [VideoSettings]):
//!
//! - **Testi**: the full player's look (Spicy Lyrics' Fullscreen) for the deck the crowd hears: its
//!   cover large on the left over the cover's own light, the song and its progress under it, the
//!   lines on the right as the app draws them (springs, glow, depth), word by word or line by line;
//! - **Video in loop**: a video file playing over and over, as VirtualDJ's video decks (ffmpeg);
//! - **Schermo**: a screen or a window (asked through the system's screen-cast portal), a part of
//!   it, live (PipeWire through GStreamer). Linux only for now.
//!
//! The deck shown is the one heard most (playing, its fader, its side of the crossfader); during
//! an autopilot mix it changes halfway. A double click (or F) puts the window full screen, Esc
//! takes it out; the mixer's VIDEO button opens and closes it.

use std::cell::Cell;
use std::io::Read;
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::rc::Rc;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use serde::{Deserialize, Serialize};

use crate::ambient::Ambient;
use crate::covers::Covers;
use crate::deck_model::DjDecks;
use crate::i18n::tr;
use crate::lyrics::{Feed, Lyrics, LyricsData, Variant};

const STORE: &str = "console-video";
/// Frames of the loop and the screen are sent at most this wide (a projector does not need more,
/// and every frame is a new texture).
const MAX_W: u32 = 1280;

// ---- What the window shows ----

#[derive(Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default, Debug)]
pub enum Show {
    #[default]
    Lyrics,
    Loop,
    Screen,
}

/// The part of the shared screen sent: all of it, a half, the middle.
#[derive(Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default, Debug)]
pub enum Zone {
    #[default]
    Whole,
    Left,
    Right,
    Top,
    Bottom,
    Middle,
}

impl Zone {
    /// x, y, width, height as shares of the source.
    fn rect(self) -> (f32, f32, f32, f32) {
        match self {
            Zone::Whole => (0., 0., 1., 1.),
            Zone::Left => (0., 0., 0.5, 1.),
            Zone::Right => (0.5, 0., 0.5, 1.),
            Zone::Top => (0., 0., 1., 0.5),
            Zone::Bottom => (0., 0.5, 1., 0.5),
            Zone::Middle => (0.25, 0.25, 0.5, 0.5),
        }
    }

    pub fn next(self) -> Zone {
        match self {
            Zone::Whole => Zone::Left,
            Zone::Left => Zone::Right,
            Zone::Right => Zone::Top,
            Zone::Top => Zone::Bottom,
            Zone::Bottom => Zone::Middle,
            Zone::Middle => Zone::Whole,
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            Zone::Whole => tr(words::ZONE_WHOLE),
            Zone::Left => tr(words::ZONE_LEFT),
            Zone::Right => tr(words::ZONE_RIGHT),
            Zone::Top => tr(words::ZONE_TOP),
            Zone::Bottom => tr(words::ZONE_BOTTOM),
            Zone::Middle => tr(words::ZONE_MIDDLE),
        }
    }
}

pub mod words {
    use crate::i18n::t;
    pub const SHOW_LYRICS: &str = t("Testi");
    pub const SHOW_LOOP: &str = t("Video in loop");
    pub const SHOW_SCREEN: &str = t("Schermo");
    pub const BY_WORD: &str = t("Parola per parola");
    pub const BY_LINE: &str = t("Frase per frase");
    pub const PICK: &str = t("Scegli…");
    pub const ZONE_WHOLE: &str = t("Tutto");
    pub const ZONE_LEFT: &str = t("Metà sinistra");
    pub const ZONE_RIGHT: &str = t("Metà destra");
    pub const ZONE_TOP: &str = t("Metà alta");
    pub const ZONE_BOTTOM: &str = t("Metà bassa");
    pub const ZONE_MIDDLE: &str = t("Centro");
    pub const NO_FILE: &str = t("Scegli un video nelle impostazioni del mixer (⋯ → Video)");
    pub const NO_SCREEN: &str = t("Scegli uno schermo nelle impostazioni del mixer (⋯ → Video)");
    #[cfg_attr(target_os = "linux", allow(dead_code))]
    pub const SCREEN_LINUX: &str = t("La condivisione dello schermo per ora c'è solo su Linux");
    pub const NEEDS_FFMPEG: &str = t("Per il video in loop serve ffmpeg");
    pub const NEEDS_GST: &str = t("Per lo schermo serve GStreamer con PipeWire");
    pub const SECTION: &str = t("Video");
    pub const SET_SHOW: &str = t("Mostra");
    pub const SET_LYRICS: &str = t("Testi");
    pub const SET_FILE: &str = t("File video");
    pub const SET_SCREEN: &str = t("Schermo");
    pub const SET_ZONE: &str = t("Zona");
}

#[derive(Clone, Default, Serialize, Deserialize)]
#[serde(default)]
struct Saved {
    show: Show,
    /// Lines word by word (false: whole lines).
    words: Option<bool>,
    file: Option<PathBuf>,
    zone: Zone,
}

/// The video out's settings (the mixer's card), kept between runs.
pub struct VideoSettings {
    pub show: Show,
    pub words: bool,
    pub file: Option<PathBuf>,
    pub zone: Zone,
    /// The screen being shared: the portal's PipeWire remote and the stream (node, size).
    screen: Option<ScreenSource>,
    /// Asking the portal right now.
    asking: bool,
}

#[derive(Clone)]
struct ScreenSource {
    #[cfg_attr(not(target_os = "linux"), allow(dead_code))]
    fd: Arc<std::os::fd::OwnedFd>,
    node: u32,
    size: (u32, u32),
    /// Dropping it ends the portal session.
    _keep: Arc<dyn Send + Sync>,
}

struct VideoGlobal(Entity<VideoSettings>);
impl Global for VideoGlobal {}

impl VideoSettings {
    pub fn entity(cx: &mut App) -> Entity<Self> {
        if let Some(g) = cx.try_global::<VideoGlobal>() {
            return g.0.clone();
        }
        let saved: Saved = lfly_core::store::get(STORE).ok().flatten().and_then(|v| serde_json::from_value(v).ok()).unwrap_or_default();
        let e = cx.new(|_| VideoSettings { show: saved.show, words: saved.words.unwrap_or(true), file: saved.file, zone: saved.zone, screen: None, asking: false });
        cx.set_global(VideoGlobal(e.clone()));
        e
    }

    fn save(&self) {
        if crate::deck_model::test_run() {
            return;
        }
        let s = Saved { show: self.show, words: Some(self.words), file: self.file.clone(), zone: self.zone };
        if let Ok(v) = serde_json::to_value(&s) {
            if let Err(e) = lfly_core::store::set(STORE, &v) {
                log::warn!("{STORE}: {e}");
            }
        }
    }

    pub fn show_label(&self) -> &'static str {
        match self.show {
            Show::Lyrics => tr(words::SHOW_LYRICS),
            Show::Loop => tr(words::SHOW_LOOP),
            Show::Screen => tr(words::SHOW_SCREEN),
        }
    }

    pub fn words_label(&self) -> &'static str {
        tr(if self.words { words::BY_WORD } else { words::BY_LINE })
    }

    pub fn file_label(&self) -> String {
        self.file.as_ref().and_then(|f| f.file_name()).map_or_else(|| tr(words::PICK).to_string(), |n| n.to_string_lossy().to_string())
    }

    pub fn screen_label(&self) -> String {
        match (&self.screen, self.asking) {
            (_, true) => "…".into(),
            (Some(s), _) => format!("{}×{}", s.size.0, s.size.1),
            (None, _) => tr(words::PICK).to_string(),
        }
    }

    pub fn cycle_show(&mut self, cx: &mut Context<Self>) {
        self.show = match self.show {
            Show::Lyrics => Show::Loop,
            Show::Loop => Show::Screen,
            Show::Screen => Show::Lyrics,
        };
        self.save();
        cx.notify();
    }

    pub fn toggle_words(&mut self, cx: &mut Context<Self>) {
        self.words = !self.words;
        self.save();
        cx.notify();
    }

    pub fn cycle_zone(&mut self, cx: &mut Context<Self>) {
        self.zone = self.zone.next();
        self.save();
        cx.notify();
    }

    /// The video for the loop, from the system's file dialog.
    pub fn pick_file(&mut self, cx: &mut Context<Self>) {
        let picked = cx.prompt_for_paths(PathPromptOptions { files: true, directories: false, multiple: false, prompt: Some(tr(words::PICK).into()) });
        cx.spawn(async move |this, cx| {
            let Ok(Ok(Some(paths))) = picked.await else { return };
            let Some(path) = paths.into_iter().next() else { return };
            let _ = this.update(cx, |s, cx| {
                s.file = Some(path);
                s.show = Show::Loop;
                s.save();
                cx.notify();
            });
        })
        .detach();
    }

    /// A screen or a window, through the portal (the system asks which).
    pub fn pick_screen(&mut self, cx: &mut Context<Self>) {
        if self.asking {
            return;
        }
        if !cfg!(target_os = "linux") {
            log::info!("video out: screen sharing is Linux only for now");
            return;
        }
        self.asking = true;
        cx.notify();
        // ashpd runs on async-io (as GPUI's own file dialogs do): any executor drives it.
        let task = cx.background_executor().spawn(ask_screen());
        cx.spawn(async move |this, cx| {
            let got = task.await;
            let _ = this.update(cx, |s, cx| {
                s.asking = false;
                match got {
                    Ok(src) => {
                        log::info!("video out: sharing node {} ({}×{})", src.node, src.size.0, src.size.1);
                        s.screen = Some(src);
                        s.show = Show::Screen;
                        s.save();
                    }
                    Err(e) => log::warn!("video out: screen not shared: {e}"),
                }
                cx.notify();
            });
        })
        .detach();
    }
}

/// The portal's dialog, then the PipeWire remote of the stream picked.
#[cfg(target_os = "linux")]
async fn ask_screen() -> Result<ScreenSource, String> {
    use ashpd::desktop::screencast::{CursorMode, OpenPipeWireRemoteOptions, Screencast, SelectSourcesOptions, SourceType, StartCastOptions};
    let proxy = Screencast::new().await.map_err(|e| e.to_string())?;
    let session = proxy.create_session(Default::default()).await.map_err(|e| e.to_string())?;
    proxy
        .select_sources(
            &session,
            SelectSourcesOptions::default().set_cursor_mode(CursorMode::Embedded).set_sources(SourceType::Monitor | SourceType::Window).set_multiple(false),
        )
        .await
        .map_err(|e| e.to_string())?;
    let streams = proxy.start(&session, None, StartCastOptions::default()).await.map_err(|e| e.to_string())?.response().map_err(|e| e.to_string())?;
    let stream = streams.streams().first().ok_or("no stream picked")?.clone();
    let fd = proxy.open_pipe_wire_remote(&session, OpenPipeWireRemoteOptions::default()).await.map_err(|e| e.to_string())?;
    let size = stream.size().map_or((1920, 1080), |(w, h)| (w.max(1) as u32, h.max(1) as u32));
    Ok(ScreenSource { fd: Arc::new(fd), node: stream.pipe_wire_node_id(), size, _keep: Arc::new(Mutex::new((proxy, session))) })
}

#[cfg(not(target_os = "linux"))]
async fn ask_screen() -> Result<ScreenSource, String> {
    Err("screen sharing is Linux only for now".into())
}

// ---- Frames from a child process (ffmpeg for the loop, GStreamer for the screen) ----

/// A program writing raw BGRA frames of a known size to its output; the latest one is kept.
struct FramePipe {
    child: Child,
    size: (u32, u32),
    latest: Arc<Mutex<Option<Vec<u8>>>>,
    count: Arc<AtomicU64>,
    /// What it plays (the file, or the screen's node and zone): another one starts it again.
    key: String,
    stop: Arc<AtomicBool>,
}

impl FramePipe {
    fn start(mut cmd: Command, size: (u32, u32), key: String) -> Result<FramePipe, String> {
        let mut child = cmd.stdin(Stdio::null()).stdout(Stdio::piped()).stderr(Stdio::null()).spawn().map_err(|e| e.to_string())?;
        let mut out = child.stdout.take().ok_or("no output")?;
        let latest = Arc::new(Mutex::new(None));
        let count = Arc::new(AtomicU64::new(0));
        let stop = Arc::new(AtomicBool::new(false));
        let (l, c, st) = (latest.clone(), count.clone(), stop.clone());
        let frame = (size.0 * size.1 * 4) as usize;
        std::thread::Builder::new()
            .name("lfly-video-in".into())
            .spawn(move || {
                let mut buf = vec![0u8; frame];
                while !st.load(Ordering::Relaxed) {
                    if out.read_exact(&mut buf).is_err() {
                        break;
                    }
                    *l.lock().unwrap_or_else(|e| e.into_inner()) = Some(buf.clone());
                    c.fetch_add(1, Ordering::Relaxed);
                }
            })
            .map_err(|e| e.to_string())?;
        Ok(FramePipe { child, size, latest, count, key, stop })
    }

    fn take(&self) -> Option<Vec<u8>> {
        self.latest.lock().unwrap_or_else(|e| e.into_inner()).take()
    }
}

impl Drop for FramePipe {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

/// The size a WxH source is sent at: at most MAX_W wide, even sides.
fn fit(w: u32, h: u32) -> (u32, u32) {
    let ow = w.min(MAX_W).max(2) & !1;
    let oh = ((h as f64 * ow as f64 / w.max(1) as f64).round() as u32).max(2) & !1;
    (ow, oh)
}

/// The video's size, from ffprobe.
fn probe(file: &std::path::Path) -> Option<(u32, u32)> {
    let out = Command::new("ffprobe")
        .args(["-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height", "-of", "csv=p=0"])
        .arg(file)
        .output()
        .ok()?;
    let text = String::from_utf8_lossy(&out.stdout);
    let (w, h) = text.trim().lines().next()?.split_once(',')?;
    Some((w.trim().parse().ok()?, h.trim().parse().ok()?))
}

/// ffmpeg playing [file] over and over at its own pace, as BGRA frames.
fn loop_pipe(file: &std::path::Path) -> Result<FramePipe, String> {
    let (w, h) = probe(file).ok_or_else(|| tr(words::NEEDS_FFMPEG).to_string())?;
    let (ow, oh) = fit(w, h);
    let mut cmd = Command::new("ffmpeg");
    cmd.args(["-hide_banner", "-loglevel", "error", "-nostdin", "-stream_loop", "-1", "-re", "-i"])
        .arg(file)
        .args(["-an", "-vf", &format!("scale={ow}:{oh},fps=30"), "-pix_fmt", "bgra", "-f", "rawvideo", "pipe:1"]);
    FramePipe::start(cmd, (ow, oh), file.to_string_lossy().to_string())
}

/// GStreamer reading the shared screen's PipeWire stream, cut to [zone], as BGRA frames.
#[cfg(target_os = "linux")]
fn screen_pipe(src: &ScreenSource, zone: Zone) -> Result<FramePipe, String> {
    use std::os::fd::AsRawFd;
    use std::os::unix::process::CommandExt;
    let (sw, sh) = src.size;
    let (x, y, w, h) = zone.rect();
    let (left, top) = ((sw as f32 * x) as u32, (sh as f32 * y) as u32);
    let (cw, ch) = ((sw as f32 * w) as u32, (sh as f32 * h) as u32);
    let (right, bottom) = (sw.saturating_sub(left + cw), sh.saturating_sub(top + ch));
    let (ow, oh) = fit(cw, ch);
    let fd = src.fd.as_raw_fd();
    let mut cmd = Command::new("gst-launch-1.0");
    cmd.args([
        "-q".to_string(),
        "pipewiresrc".into(),
        "fd=3".into(),
        format!("path={}", src.node),
        "always-copy=true".into(),
        "!".into(),
        "videoconvert".into(),
        "!".into(),
        "videocrop".into(),
        format!("left={left}"),
        format!("right={right}"),
        format!("top={top}"),
        format!("bottom={bottom}"),
        "!".into(),
        "videoscale".into(),
        "!".into(),
        "videorate".into(),
        "!".into(),
        format!("video/x-raw,format=BGRA,width={ow},height={oh},framerate=30/1"),
        "!".into(),
        "fdsink".into(),
        "fd=1".into(),
    ]);
    // SAFETY: only dup2 between fork and exec; the portal's remote becomes fd 3 of the child.
    unsafe {
        cmd.pre_exec(move || {
            if libc::dup2(fd, 3) < 0 {
                return Err(std::io::Error::last_os_error());
            }
            Ok(())
        });
    }
    FramePipe::start(cmd, (ow, oh), format!("{}:{zone:?}", src.node)).map_err(|e| format!("{}: {e}", tr(words::NEEDS_GST)))
}

#[cfg(not(target_os = "linux"))]
fn screen_pipe(_: &ScreenSource, _: Zone) -> Result<FramePipe, String> {
    Err(tr(words::SCREEN_LINUX).to_string())
}

// ---- The window ----

pub struct VideoOut {
    dj: Entity<DjDecks>,
    covers: Entity<Covers>,
    settings: Entity<VideoSettings>,
    /// The cover's light behind the lyrics.
    ambient: Entity<Ambient>,
    /// The lines, as the full player draws them, for the deck in [shown_cell].
    lines: Entity<Lyrics>,
    shown_cell: Rc<Cell<usize>>,
    /// The deck shown last (kept while the two are about as loud, so it does not flicker).
    shown: usize,
    ticking: bool,
    pipe: Option<FramePipe>,
    pipe_error: Option<String>,
    frame: Option<Arc<RenderImage>>,
    frame_count: u64,
    trash: Vec<Arc<RenderImage>>,
    _subs: Vec<Subscription>,
}

impl VideoOut {
    /// Opens the window; its handle closes it again.
    pub fn open(core: Arc<crate::core::Core>, dj: Entity<DjDecks>, lyrics: Entity<LyricsData>, covers: Entity<Covers>, cx: &mut App) -> Option<AnyWindowHandle> {
        let bounds = Bounds::centered(None, size(px(1280.), px(720.)), cx);
        let settings = VideoSettings::entity(cx);
        let look = crate::look::Look::entity(cx);
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
                    let subs = vec![
                        cx.observe(&dj, |_, _, cx| cx.notify()),
                        cx.observe(&lyrics, |_, _, cx| cx.notify()),
                        cx.observe(&covers, |_, _, cx| cx.notify()),
                        cx.observe(&settings, |_, _, cx| cx.notify()),
                    ];
                    let _ = window;
                    let ambient = cx.new(|_| {
                        let mut a = Ambient::new(core, 0.45, 80.);
                        a.saturation = 2.5;
                        a
                    });
                    let shown_cell = Rc::new(Cell::new(0));
                    let lines = cx.new(|cx| Lyrics::new(lyrics.clone(), Feed::Deck(dj.clone(), shown_cell.clone()), look, None, Variant::Video, cx));
                    VideoOut {
                        dj,
                        covers,
                        settings,
                        ambient,
                        lines,
                        shown_cell,
                        shown: 0,
                        ticking: false,
                        pipe: None,
                        pipe_error: None,
                        frame: None,
                        frame_count: 0,
                        trash: Vec::new(),
                        _subs: subs,
                    }
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

    /// Thirty frames a second while a deck plays or a video runs, and the window is open.
    fn keep_ticking(&mut self, cx: &mut Context<Self>) {
        if self.ticking {
            return;
        }
        self.ticking = true;
        cx.spawn(async move |this, cx| loop {
            cx.background_executor().timer(Duration::from_millis(33)).await;
            let on = this
                .update(cx, |v, cx| {
                    let on = v.dj.read(cx).any_playing() || v.pipe.is_some();
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

    /// The pipe the settings ask for: started, kept, or stopped.
    fn follow_settings(&mut self, cx: &mut Context<Self>) {
        let s = self.settings.read(cx);
        let wanted: Option<Result<String, String>> = match s.show {
            Show::Lyrics => None,
            Show::Loop => Some(s.file.as_ref().map(|f| f.to_string_lossy().to_string()).ok_or_else(|| tr(words::NO_FILE).to_string())),
            Show::Screen => Some(s.screen.as_ref().map(|src| format!("{}:{:?}", src.node, s.zone)).ok_or_else(|| tr(words::NO_SCREEN).to_string())),
        };
        match wanted {
            None => {
                self.pipe = None;
                self.pipe_error = None;
            }
            Some(Err(why)) => {
                self.pipe = None;
                self.pipe_error = Some(why);
            }
            Some(Ok(key)) => {
                if self.pipe.as_ref().is_some_and(|p| p.key == key) || self.pipe_error.as_ref().is_some_and(|e| e.starts_with(&key)) {
                    return;
                }
                let started = match s.show {
                    Show::Loop => loop_pipe(s.file.as_ref().expect("a file")),
                    _ => screen_pipe(s.screen.as_ref().expect("a screen"), s.zone),
                };
                self.pipe = None;
                match started {
                    Ok(p) => {
                        log::info!("video out: {key} at {}×{}", p.size.0, p.size.1);
                        self.pipe = Some(p);
                        self.pipe_error = None;
                    }
                    Err(e) => {
                        log::warn!("video out: {e}");
                        // Not tried again at every frame: only for another file or screen.
                        self.pipe_error = Some(format!("{key}\n{e}"));
                    }
                }
            }
        }
        if self.pipe.is_none() {
            if let Some(f) = self.frame.take() {
                self.trash.push(f);
            }
        }
    }

    /// The pipe's newest frame as a texture (the one it replaces given back).
    fn next_frame(&mut self) {
        let Some(p) = self.pipe.as_ref() else { return };
        let n = p.count.load(Ordering::Relaxed);
        if n == self.frame_count {
            return;
        }
        self.frame_count = n;
        let Some(data) = p.take() else { return };
        let (w, h) = p.size;
        let Some(buf) = image::RgbaImage::from_raw(w, h, data) else { return };
        let new = Arc::new(RenderImage::new(vec![image::Frame::new(buf)]));
        if let Some(old) = self.frame.replace(new) {
            self.trash.push(old);
        }
    }

    fn lyrics_view(&mut self, d: usize, w: f32, h: f32, cx: &mut Context<Self>) -> AnyElement {
        let track = self.dj.read(cx).decks[d].track.clone();
        let Some(track) = track else { return div().into_any_element() };
        let (pos, dur) = {
            let dj = self.dj.read(cx);
            (dj.position(d), dj.decks[d].duration().max(1.))
        };
        let words = self.settings.read(cx).words;
        self.shown_cell.set(d);
        let narrow = w < 900.;
        let col_w = if narrow { w * 0.9 } else { w * 0.5 };
        self.lines.update(cx, |l, _| {
            l.words = Some(words);
            l.window_width = w;
        });

        // The cover large (1200 px where YouTube Music has it), 16:9 for a video's thumbnail.
        let wide = track.cover_url.as_deref().is_some_and(|u| u.contains("ytimg.com"));
        let cover = track.cover_url.as_ref().and_then(|u| {
            self.covers.update(cx, |c, cx| {
                let big = u.contains("=w544-h544").then(|| u.replace("=w544-h544", "=w1200-h1200")).and_then(|b| c.get(&b, cx));
                big.or_else(|| c.get(u, cx))
            })
        });
        let nb = (0.30 * w).min(0.52 * h);
        let ratio = if wide { 9. / 16. } else { 1. };
        let radius = px(0.03 * nb);
        let font = (0.03 * nb).clamp(8., 38.4);
        let clock = |s: f64| format!("{}:{:02}", (s as u64) / 60, (s as u64) % 60);
        let progress = (pos / dur).clamp(0., 1.) as f32;
        let side = div()
            .w(px(nb))
            .flex_none()
            .flex()
            .flex_col()
            .items_center()
            .gap(px(font * 0.9))
            .child(
                div()
                    .w(px(nb))
                    .h(px(nb * ratio))
                    .rounded(radius)
                    .bg(white().opacity(0.08))
                    .shadow(vec![BoxShadow { color: black().opacity(0.35), offset: point(px(0.), px(14.)), blur_radius: px(40.), spread_radius: px(0.), inset: false }])
                    .children(cover.map(|i| crate::covers::fade(i.id(), img(i).size_full().object_fit(ObjectFit::Cover).rounded(radius)))),
            )
            .child(
                div()
                    .w_full()
                    .flex()
                    .flex_col()
                    .items_center()
                    .child(div().max_w(px(0.9 * nb)).text_size(px(font * 2.1)).font_weight(FontWeight::BLACK).text_color(white().opacity(0.95)).truncate().child(track.title.clone()))
                    .child(div().max_w(px(0.9 * nb)).text_size(px(font * 1.4)).font_weight(FontWeight::SEMIBOLD).text_color(white().opacity(0.7)).truncate().child(track.artist.clone())),
            )
            // The timeline, as the full player's: times either side of a thin bar.
            .child(
                div()
                    .w_full()
                    .flex()
                    .items_center()
                    .gap(px(font * 0.6))
                    .text_size(px(font * 0.95))
                    .font_weight(FontWeight::SEMIBOLD)
                    .text_color(white().opacity(0.7))
                    .child(clock(pos))
                    .child(div().flex_1().h(px((font * 0.28).max(3.))).rounded_full().bg(white().opacity(0.2)).child(div().h_full().w(relative(progress)).rounded_full().bg(white().opacity(0.9))))
                    .child(clock(dur)),
            );
        div()
            .absolute()
            .inset_0()
            .flex()
            .items_center()
            .justify_center()
            .gap(px(nb * 0.3))
            .px(px(0.05 * w))
            .when(!narrow, |d| d.child(side))
            .child(div().relative().w(px(col_w)).h(px(h * 0.86)).child(self.lines.clone()))
            .into_any_element()
    }
}

impl Render for VideoOut {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        for t in self.trash.drain(..) {
            let _ = window.drop_image(t);
        }
        self.follow_settings(cx);
        self.next_frame();
        let playing = self.dj.read(cx).any_playing();
        if playing || self.pipe.is_some() {
            self.keep_ticking(cx);
        }
        let heard = self.heard(cx);
        let (w, h) = (window.viewport_size().width / px(1.), window.viewport_size().height / px(1.));
        let small = (h * 0.045).clamp(12., 64.);
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
        let show = self.settings.read(cx).show;
        if show != Show::Lyrics {
            // The loop or the screen, whole in the window; a line instead when it cannot run.
            return match (&self.frame, &self.pipe_error) {
                (Some(f), _) => root.child(img(f.clone()).size_full().object_fit(if show == Show::Loop { ObjectFit::Cover } else { ObjectFit::Contain })),
                (None, Some(e)) => {
                    let why = e.rsplit('\n').next().unwrap_or(e).to_string();
                    root.child(div().size_full().flex().items_center().justify_center().p(px(small)).text_center().text_size(px(small * 0.6)).text_color(white().opacity(0.5)).child(why))
                }
                (None, None) => root,
            };
        }
        let track = heard.and_then(|d| self.dj.read(cx).decks[d].track.clone());
        self.ambient.update(cx, |a, _| {
            a.url = track.as_ref().and_then(|t| t.cover_url.clone());
            a.width = w;
            a.height = h;
            a.bg = crate::theme::Rgb(0., 0., 0.);
            a.moving = playing;
        });
        let Some(d) = heard.filter(|_| track.is_some()) else {
            return root.child(div().size_full().flex().items_center().justify_center().text_size(px(small)).font_weight(FontWeight::BOLD).text_color(white().opacity(0.3)).child("LFly"));
        };
        let shade = div().absolute().inset_0().bg(linear_gradient(180., linear_color_stop(black().opacity(0.15), 0.), linear_color_stop(black().opacity(0.45), 1.)));
        let body = self.lyrics_view(d, w, h, cx);
        root.child(self.ambient.clone()).child(shade).child(body)
    }
}

#[cfg(test)]
mod tests {
    // Not `super::*`: gpui's own `test` attribute would shadow the standard one.
    use super::{fit, Zone};

    #[test]
    fn frames_are_sent_at_most_1280_wide_with_even_sides() {
        assert_eq!(fit(1920, 1080), (1280, 720));
        assert_eq!(fit(640, 361), (640, 360));
        let (w, h) = fit(3440, 1440);
        assert!(w == 1280 && h % 2 == 0);
    }

    #[test]
    fn zones_cover_the_screen_or_a_part_of_it() {
        assert_eq!(Zone::Whole.rect(), (0., 0., 1., 1.));
        let mut z = Zone::Whole;
        for _ in 0..6 {
            let (x, y, w, h) = z.rect();
            assert!(x >= 0. && y >= 0. && x + w <= 1. && y + h <= 1.);
            z = z.next();
        }
        assert_eq!(z, Zone::Whole);
    }
}
