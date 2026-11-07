//! LFly's YouTube Music client for the desktop app (port of the Android
//! app's Kotlin client).
//!
//! [`YtMusic`] covers search, typeahead, the home feed, artist, album and
//! playlist pages, radio ("up next"), full-length audio streams with a
//! circuit-breaker guarded fallback chain, and synced lyrics from every
//! provider the phone uses (see [`lyrics`]). Every parser
//! lives in [`parse`] and works offline on raw InnerTube JSON.

pub mod account;
mod client;
pub mod credits;
pub mod error;
pub mod health;
mod json;
pub mod lyrics;
pub mod model;
pub mod parse;
pub mod stream;

pub use account::{Account, AccountInfo};
pub use client::{YtMusic, YtMusicConfig, DEFAULT_INVIDIOUS, DEFAULT_PIPED, VISIONOS_UA};
pub use error::{Error, Result};
pub use json::upscale_thumbnail;
pub use model::*;
pub use stream::AudioPreference;
