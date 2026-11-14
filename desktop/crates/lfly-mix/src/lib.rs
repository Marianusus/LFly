//! LFly's club Automix, the pure half (ported from the Android app's `playback/automix`): the
//! beat model's input (log-mel) and the grid read from its output, loudness, the DJ mixer's
//! filters; then energy, key, the transition planner and its curves. Arithmetic on samples and
//! numbers only, so the audio engine can depend on it without pulling anything in.

pub mod beat_grid;
pub mod curves;
pub mod effects;
pub mod energy;
mod fft;
pub mod filter;
pub mod loudness;
pub mod mel;
pub mod planner;
mod util;
