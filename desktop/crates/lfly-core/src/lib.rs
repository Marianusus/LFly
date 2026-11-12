//! LFly for computer without a window: the parts that do not care what draws the app.
//!
//! The Tauri app (`app/src-tauri`) and the GPUI app (`native/`) both run on this, so the
//! rewrite moves logic here a module at a time and the old window keeps working meanwhile.

pub mod discord;
pub mod identity;
pub mod library;
pub mod player;
pub mod queue;
pub mod store;
