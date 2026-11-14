//! A playlist's Mix (the phone's `PlaylistMix.kt`): on or off, and the transitions the listener
//! chose between two songs, with Spotify Mix's ready-made presets. Pure data; the names in
//! [`MixPreset::id`] are the ones the phone writes in `playlist_mixes.json`.

use std::collections::HashMap;

use crate::curves::{EqCurve, MixRecipe, TransitionStyle, VolumeCurve};

/// The ready-made transitions a mixed playlist offers between two songs, as in Spotify's Mix.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub enum MixPreset {
    /// Automix picks one per transition.
    #[default]
    Auto,
    /// A smooth crossfade with the bass handed over in the middle.
    Fade,
    /// Build and drop: both songs up, the new one filtered and without bass until the last downbeat.
    Rise,
    /// Both songs up while a three-band EQ hands one over to the other.
    Blend,
    /// Both songs up, each under a sweeping low-pass, the bass swapped in the middle.
    Wave,
    /// The new song melts in under a high-pass while the old one fades under its own.
    Melt,
    /// A hard cut on the middle downbeat.
    Slam,
    /// No transition: the first song ends, then the next one starts.
    None,
}

impl MixPreset {
    /// The order the editor shows them in.
    pub const SHOWN: [MixPreset; 8] =
        [MixPreset::Auto, MixPreset::Fade, MixPreset::Rise, MixPreset::Blend, MixPreset::Wave, MixPreset::Melt, MixPreset::Slam, MixPreset::None];

    /// Its ingredients; `None` for [`MixPreset::Auto`] (Automix picks) and [`MixPreset::None`].
    pub fn recipe(self) -> Option<MixRecipe> {
        match self {
            MixPreset::Auto | MixPreset::None => None,
            MixPreset::Fade => Some(MixRecipe::new(VolumeCurve::SmoothCrossfade, EqCurve::CenterBassSwap, crate::curves::FilterCurve::None, crate::curves::FilterCurve::None)),
            MixPreset::Rise => Some(TransitionStyle::BassSwap.recipe()),
            MixPreset::Blend => Some(TransitionStyle::EqSwap.recipe()),
            MixPreset::Wave => Some(TransitionStyle::Wave.recipe()),
            MixPreset::Melt => Some(TransitionStyle::Filter.recipe()),
            MixPreset::Slam => Some(TransitionStyle::Slam.recipe()),
        }
    }

    /// The phone's enum name.
    pub fn id(self) -> &'static str {
        match self {
            MixPreset::Auto => "AUTO",
            MixPreset::Fade => "FADE",
            MixPreset::Rise => "RISE",
            MixPreset::Blend => "BLEND",
            MixPreset::Wave => "WAVE",
            MixPreset::Melt => "MELT",
            MixPreset::Slam => "SLAM",
            MixPreset::None => "NONE",
        }
    }

    pub fn from_id(id: &str) -> Option<MixPreset> {
        MixPreset::SHOWN.into_iter().find(|p| p.id() == id)
    }
}

/// What the listener chose for one transition of a mixed playlist. Every field has a default, so a
/// transition saved by an older version still loads.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct MixTransition {
    pub preset: MixPreset,
    /// Set when the listener changed the ingredients in the editor: it then wins over [`Self::preset`].
    pub custom: Option<MixRecipe>,
    /// Mix length in bars of the outgoing song; `None` lets Automix choose.
    pub bars: Option<u32>,
    /// False plays the two songs at their own tempo, without lining up the beats.
    pub beatmatch: bool,
    /// Where the outgoing song hands over, on one of its bar lines; `None` lets Automix choose.
    pub exit_ms: Option<i64>,
    /// Where the incoming song starts, on one of its bar lines; `None` lets Automix choose.
    pub entry_ms: Option<i64>,
}

impl Default for MixTransition {
    fn default() -> Self {
        MixTransition::AUTO
    }
}

impl MixTransition {
    pub const AUTO: MixTransition = MixTransition { preset: MixPreset::Auto, custom: None, bars: None, beatmatch: true, exit_ms: None, entry_ms: None };

    /// Bar counts the editor offers, as Spotify's duration sheet does.
    pub const BAR_CHOICES: [u32; 5] = [2, 4, 8, 16, 32];

    /// The ingredients this transition plays with; `None` when Automix picks them (AUTO) or there
    /// is no transition.
    pub fn recipe(&self) -> Option<MixRecipe> {
        self.custom.or(self.preset.recipe())
    }

    pub fn is_none(&self) -> bool {
        self.custom.is_none() && self.preset == MixPreset::None
    }
}

/// A playlist's Mix: on or off, and the transitions the listener edited, keyed by [`pair_key`].
#[derive(Clone, Debug, Default, PartialEq)]
pub struct PlaylistMix {
    pub enabled: bool,
    pub transitions: HashMap<String, MixTransition>,
}

impl PlaylistMix {
    pub fn transition(&self, from_id: &str, to_id: &str) -> MixTransition {
        self.transitions.get(&pair_key(from_id, to_id)).copied().unwrap_or(MixTransition::AUTO)
    }
}

/// A transition belongs to the pair of songs, so it follows them when the playlist is reordered.
pub fn pair_key(from_id: &str, to_id: &str) -> String {
    format!("{from_id}>{to_id}")
}

/// Progress of a mixed playlist's analysis: the song at `done` (from 0) of `total` is being analysed.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MixAnalysisProgress {
    pub playlist_id: String,
    pub done: usize,
    pub total: usize,
}

#[cfg(test)]
mod tests {
    //! The presets part of the phone's PlaylistMixTest, case for case.
    use super::*;
    use crate::curves::{frame, SWAP_AT, SWAP_WIDTH};
    use crate::filter::DjFilterSettings;

    #[test]
    fn presets_are_the_ingredients_of_the_ready_made_styles() {
        assert_eq!(MixPreset::Rise.recipe(), Some(TransitionStyle::BassSwap.recipe()));
        assert_eq!(MixPreset::Blend.recipe(), Some(TransitionStyle::EqSwap.recipe()));
        assert_eq!(MixPreset::Melt.recipe(), Some(TransitionStyle::Filter.recipe()));
        assert_eq!(MixPreset::Wave.recipe(), Some(TransitionStyle::Wave.recipe()));
        assert_eq!(MixPreset::Auto.recipe(), None);
        assert_eq!(MixPreset::None.recipe(), None);
        assert!(MixTransition { preset: MixPreset::None, ..MixTransition::AUTO }.is_none());
        let custom = Some(TransitionStyle::Fade.recipe());
        assert!(!MixTransition { preset: MixPreset::None, custom, ..MixTransition::AUTO }.is_none());
    }

    #[test]
    fn every_preset_starts_on_the_outgoing_song_and_ends_on_the_incoming_one() {
        for preset in MixPreset::SHOWN {
            let Some(recipe) = preset.recipe() else { continue };
            let start = frame(&recipe, 0.0, 1.0, SWAP_AT, SWAP_WIDTH);
            let end = frame(&recipe, 1.0, 1.0, SWAP_AT, SWAP_WIDTH);
            assert!((start.outgoing.volume - 1.0).abs() < 1e-4, "{preset:?} start");
            assert!(start.incoming.volume.abs() < 1e-4, "{preset:?} start incoming");
            assert!(end.outgoing.volume.abs() < 1e-4, "{preset:?} end");
            assert!((end.incoming.volume - 1.0).abs() < 1e-4, "{preset:?} end incoming");
            assert_eq!(end.incoming.filter, DjFilterSettings::NEUTRAL, "{preset:?} end filter");
        }
    }

    #[test]
    fn wave_sweeps_a_low_pass_on_both_songs_and_slam_cuts_in_the_middle() {
        let wave = TransitionStyle::Wave.recipe();
        let at = |r: &MixRecipe, p: f32| frame(r, p, 1.0, SWAP_AT, SWAP_WIDTH);
        assert!((200.0..=10_000.0).contains(&at(&wave, 0.75).outgoing.filter.low_pass_hz));
        assert!((200.0..=10_000.0).contains(&at(&wave, 0.25).incoming.filter.low_pass_hz));
        let slam = TransitionStyle::Slam.recipe();
        let (before, after) = (at(&slam, 0.49), at(&slam, 0.52));
        let near = |a: f32, b: f32| (a - b).abs() < 1e-6;
        assert!(near(before.outgoing.volume, 1.0) && near(before.incoming.volume, 0.0));
        assert!(near(after.outgoing.volume, 0.0) && near(after.incoming.volume, 1.0));
    }

    #[test]
    fn a_transition_follows_its_pair_and_defaults_to_auto() {
        let mut mix = PlaylistMix { enabled: true, ..Default::default() };
        mix.transitions.insert(pair_key("a", "b"), MixTransition { preset: MixPreset::Blend, ..Default::default() });
        let t = mix.transition("a", "b");
        assert_eq!(t.preset, MixPreset::Blend);
        assert!(t.beatmatch);
        assert_eq!(t.bars, None);
        assert_eq!(mix.transition("b", "a"), MixTransition::AUTO);
        for p in MixPreset::SHOWN {
            assert_eq!(MixPreset::from_id(p.id()), Some(p));
        }
        assert_eq!(MixPreset::from_id("SOMETHING_NEW"), None);
    }
}
