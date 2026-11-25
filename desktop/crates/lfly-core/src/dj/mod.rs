//! The DJ (the phone's `playback/dj`): plays the listener's music in themed blocks of a few songs
//! and introduces each block with a spoken line, like a radio host.
//!
//! This is the pure half, every choice testable with a seeded generator: which kind of block comes
//! next and which songs go in it ([`blocks`]), how a "flow" grows each song out of the one before
//! ([`flow`]), and what the DJ says ([`script`]). The session that queues the blocks and the voice
//! come on top of it.

pub mod blocks;
pub mod flow;
pub mod script;
pub mod session;

use lfly_connect::protocol::TrackRef;
use lfly_yt::credits;

/// Why the DJ picked a block of songs; the DJ says it out loud.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum BlockKind {
    /// Starts from one of the most played songs, then each song flows out of the one before.
    Flow,
    /// Songs the listener plays most.
    TopPlayed,
    /// Around one followed artist; [`Block::subject`] is the artist's name.
    FollowedArtist,
    /// New songs close to the listener's taste.
    Discovery,
    /// Favourites not played for a while.
    Throwback,
    /// From one of the listener's genre mixes; [`Block::subject`] is the mix's name.
    Mix,
}

impl BlockKind {
    pub const ALL: [BlockKind; 6] = [BlockKind::Flow, BlockKind::TopPlayed, BlockKind::FollowedArtist, BlockKind::Discovery, BlockKind::Throwback, BlockKind::Mix];
}

/// A few songs the DJ plays together and introduces with one line.
#[derive(Clone, Debug, PartialEq)]
pub struct Block {
    pub kind: BlockKind,
    pub tracks: Vec<TrackRef>,
    pub subject: Option<String>,
}

/// An artist the listener follows.
#[derive(Clone, Debug, PartialEq, Default)]
pub struct FollowedArtist {
    pub name: String,
    /// YouTube Music's artist id, to fetch the artist's top songs.
    pub browse_id: Option<String>,
}

/// One of the listener's mixes; only genre mixes become MIX blocks.
#[derive(Clone, Debug, PartialEq, Default)]
pub struct DjMix {
    pub name: String,
    pub genre: bool,
    pub tracks: Vec<TrackRef>,
}

/// The listener's own songs, the material for the blocks that need no network.
#[derive(Clone, Debug, Default)]
pub struct Library {
    pub top_played: Vec<TrackRef>,
    pub favorites: Vec<TrackRef>,
    /// Most recent first.
    pub history: Vec<TrackRef>,
    pub followed_artists: Vec<FollowedArtist>,
    pub mixes: Vec<DjMix>,
}

/// Songs the DJ must not pick.
#[derive(Clone, Debug, Default)]
pub struct Exclusions {
    disliked_track_ids: std::collections::HashSet<String>,
    disliked_artist_keys: std::collections::HashSet<String>,
    /// Songs already played or queued in this DJ session.
    pub session_tracks: Vec<TrackRef>,
    session_ids: std::collections::HashSet<String>,
    session_keys: std::collections::HashSet<String>,
    history_ids: std::collections::HashSet<String>,
    history_keys: std::collections::HashSet<String>,
}

impl Exclusions {
    /// `recent_history`: the latest history entries, most recent first; only TOP_PLAYED may repeat them.
    pub fn new(disliked_track_ids: impl IntoIterator<Item = String>, disliked_artists: impl IntoIterator<Item = String>, session_tracks: Vec<TrackRef>, recent_history: &[TrackRef]) -> Self {
        let disliked_artist_keys = disliked_artists.into_iter().map(|a| credits::normalize(&a)).filter(|k| !k.trim().is_empty()).collect();
        Exclusions {
            disliked_track_ids: disliked_track_ids.into_iter().collect(),
            disliked_artist_keys,
            session_ids: session_tracks.iter().map(|t| t.id.clone()).collect(),
            session_keys: session_tracks.iter().map(track_key).collect(),
            history_ids: recent_history.iter().map(|t| t.id.clone()).collect(),
            history_keys: recent_history.iter().map(track_key).collect(),
            session_tracks,
        }
    }

    /// The same exclusions with `more` songs taken in this session.
    pub fn with_session(&self, more: &[TrackRef]) -> Self {
        let mut e = self.clone();
        for t in more {
            e.session_ids.insert(t.id.clone());
            e.session_keys.insert(track_key(t));
            e.session_tracks.push(t.clone());
        }
        e
    }
}

/// Same song across providers and uploads: normalised title plus artist.
pub fn track_key(track: &TrackRef) -> String {
    format!("{}|{}", credits::normalize(&track.title), credits::normalize(&track.artist))
}

/// The artist as a comparison key.
pub fn artist_key(name: &str) -> String {
    credits::normalize(name)
}

/// Every credited artist of a song, and the whole credit, as keys.
pub(crate) fn artist_keys(track: &TrackRef) -> std::collections::HashSet<String> {
    credits::from_text(Some(&track.artist))
        .iter()
        .map(|c| credits::normalize(&c.name))
        .chain(std::iter::once(credits::normalize(&track.artist)))
        .filter(|k| !k.trim().is_empty())
        .collect()
}

#[cfg(test)]
pub(crate) mod testing {
    use lfly_connect::protocol::TrackRef;

    pub fn track(id: &str, title: &str, artist: &str) -> TrackRef {
        TrackRef {
            id: id.into(),
            title: title.into(),
            artist: artist.into(),
            album: None,
            duration_ms: 200_000,
            cover_url: None,
            provider: "YouTube".into(),
            artist_id: None,
            album_id: None,
            is_video: false,
            stream_url: None,
        }
    }

    pub fn rng(seed: u64) -> rand::rngs::StdRng {
        rand::SeedableRng::seed_from_u64(seed)
    }
}
