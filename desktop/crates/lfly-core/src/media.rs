//! The system's media controls: keyboard media keys, the Linux MPRIS widgets (KDE, GNOME), the
//! Windows volume overlay and macOS Now Playing; and what Discord shows, from the same song.
//!
//! The window tells this what it shows ([`Media::update`]), whether the music plays here or on a
//! phone this computer controls, and gets the presses back through the callback given to
//! [`Media::start`] (on the controls' own thread).

use std::sync::Mutex;
use std::time::Duration;

use serde::Deserialize;
use souvlaki::{MediaControlEvent, MediaControls, MediaMetadata, MediaPlayback, MediaPosition, PlatformConfig, SeekDirection};

use crate::discord::Listening;

/// A press on the system's controls.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum MediaCommand {
    /// A player command (`lfly_connect::protocol::CMD_*`) and its value.
    Run(&'static str, i64),
    /// Forward (positive) or back, in milliseconds from where the song is.
    SeekBy(i64),
    /// Bring the window to the front.
    Raise,
}

/// What the window shows.
#[derive(Deserialize, Clone, Debug, Default, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct NowShowing {
    pub title: Option<String>,
    pub artist: Option<String>,
    pub album: Option<String>,
    pub cover_url: Option<String>,
    pub duration_ms: Option<u64>,
    pub position_ms: Option<u64>,
    pub playing: bool,
    /// Playing on a phone this computer controls: that phone shows it on Discord itself.
    #[serde(default)]
    pub remote: bool,
}

type Shown = (Option<String>, Option<String>, Option<String>, Option<String>, Option<u64>);

/// The controls, and the song they show: Windows re-reads the cover on every `set_metadata`.
struct Controls {
    controls: MediaControls,
    shown: Option<Shown>,
}

pub struct Media(Mutex<Option<Controls>>);

impl Media {
    /// Starts the controls; the app works without them when the system has none. [hwnd] is the
    /// window's handle on Windows (its volume overlay belongs to a window), None elsewhere.
    pub fn start(hwnd: Option<*mut std::ffi::c_void>, on_command: impl Fn(MediaCommand) + Send + 'static) -> Self {
        // souvlaki panics on Windows without a window: no controls instead.
        if cfg!(target_os = "windows") && hwnd.is_none() {
            log::warn!("No window handle: no system media controls");
            return Media(Mutex::new(None));
        }
        let config = PlatformConfig { dbus_name: "lfly", display_name: "LFly", hwnd };
        let controls = match MediaControls::new(config) {
            Ok(mut controls) => match controls.attach(move |event| {
                if let Some(c) = command(event) {
                    on_command(c);
                }
            }) {
                Ok(()) => Some(Controls { controls, shown: None }),
                Err(e) => {
                    log::warn!("Media controls not attached: {e:?}");
                    None
                }
            },
            Err(e) => {
                log::warn!("No system media controls: {e:?}");
                None
            }
        };
        Media(Mutex::new(controls))
    }

    /// Shows [now] on the system's widgets.
    pub fn update(&self, now: &NowShowing) {
        let mut guard = self.0.lock().unwrap_or_else(|e| e.into_inner());
        let Some(c) = guard.as_mut() else { return };
        let song = (now.title.clone(), now.artist.clone(), now.album.clone(), now.cover_url.clone(), now.duration_ms);
        if c.shown.as_ref() != Some(&song) {
            let _ = c.controls.set_metadata(MediaMetadata {
                title: now.title.as_deref(),
                artist: now.artist.as_deref(),
                album: now.album.as_deref(),
                cover_url: now.cover_url.as_deref(),
                duration: now.duration_ms.filter(|d| *d > 0).map(Duration::from_millis),
            });
            c.shown = Some(song);
        }
        let progress = now.position_ms.map(|p| MediaPosition(Duration::from_millis(p)));
        let playback = match (now.title.is_some(), now.playing) {
            (false, _) => MediaPlayback::Stopped,
            (true, true) => MediaPlayback::Playing { progress },
            (true, false) => MediaPlayback::Paused { progress },
        };
        let _ = c.controls.set_playback(playback);
    }
}

fn command(event: MediaControlEvent) -> Option<MediaCommand> {
    use lfly_connect::protocol::{CMD_NEXT, CMD_PAUSE, CMD_PLAY, CMD_PREVIOUS, CMD_SEEK, CMD_TOGGLE};
    let ms = |d: Duration| d.as_millis() as i64;
    Some(match event {
        MediaControlEvent::Play => MediaCommand::Run(CMD_PLAY, 0),
        MediaControlEvent::Pause | MediaControlEvent::Stop => MediaCommand::Run(CMD_PAUSE, 0),
        MediaControlEvent::Toggle => MediaCommand::Run(CMD_TOGGLE, 0),
        MediaControlEvent::Next => MediaCommand::Run(CMD_NEXT, 0),
        MediaControlEvent::Previous => MediaCommand::Run(CMD_PREVIOUS, 0),
        MediaControlEvent::SetPosition(MediaPosition(at)) => MediaCommand::Run(CMD_SEEK, ms(at)),
        MediaControlEvent::Seek(SeekDirection::Forward) => MediaCommand::SeekBy(10_000),
        MediaControlEvent::Seek(SeekDirection::Backward) => MediaCommand::SeekBy(-10_000),
        MediaControlEvent::SeekBy(SeekDirection::Forward, by) => MediaCommand::SeekBy(ms(by)),
        MediaControlEvent::SeekBy(SeekDirection::Backward, by) => MediaCommand::SeekBy(-ms(by)),
        MediaControlEvent::Raise => MediaCommand::Raise,
        _ => return None,
    })
}

/// What Discord shows for [now]: the song while it plays here, nothing otherwise (paused music
/// clears the status, as Spotify does).
pub fn listening(now: &NowShowing, now_unix_ms: i64) -> Option<Listening> {
    let title = now.title.as_ref().filter(|_| now.playing && !now.remote)?;
    let start_ms = now_unix_ms - now.position_ms.unwrap_or(0) as i64;
    Some(Listening {
        title: title.clone(),
        artist: now.artist.clone().unwrap_or_default(),
        album: now.album.clone(),
        cover_url: now.cover_url.clone(),
        start_ms,
        end_ms: now.duration_ms.filter(|d| *d > 0).map(|d| start_ms + d as i64),
    })
}

/// Unix time in milliseconds.
pub fn unix_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_millis() as i64)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn discord_shows_only_what_plays_here() {
        let mut now = NowShowing {
            title: Some("Get Lucky".into()),
            artist: Some("Daft Punk".into()),
            duration_ms: Some(248_000),
            position_ms: Some(8_000),
            playing: true,
            ..Default::default()
        };
        let l = listening(&now, 100_000).unwrap();
        assert_eq!((l.start_ms, l.end_ms), (92_000, Some(340_000)));
        now.playing = false;
        assert!(listening(&now, 100_000).is_none());
        now.playing = true;
        now.remote = true;
        assert!(listening(&now, 100_000).is_none());
    }

    #[test]
    fn presses_become_player_commands() {
        assert_eq!(command(MediaControlEvent::Toggle), Some(MediaCommand::Run("toggle", 0)));
        assert_eq!(command(MediaControlEvent::Seek(SeekDirection::Backward)), Some(MediaCommand::SeekBy(-10_000)));
        assert_eq!(command(MediaControlEvent::SetPosition(MediaPosition(Duration::from_secs(3)))), Some(MediaCommand::Run("seek", 3000)));
    }
}
