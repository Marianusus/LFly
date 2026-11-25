//! The DJ's choices (the phone's `DjBlocks`): which kind of block comes next and which songs go in
//! it. Every random choice goes through the given generator, so a seeded session always plays out
//! the same way.

use std::collections::HashSet;

use lfly_connect::protocol::TrackRef;
use rand::seq::SliceRandom;
use rand::Rng;

use super::{artist_key, artist_keys, track_key, Block, BlockKind, DjMix, Exclusions, FollowedArtist, Library};

pub const MIN_TRACKS: usize = 4;
pub const MAX_TRACKS: usize = 6;
/// How much of the history counts as "heard lately".
pub const RECENT_HISTORY: usize = 50;
/// Most songs by one artist in a row in a DISCOVERY block, so it feels like discovery.
pub const MAX_ARTIST_RUN: usize = 2;
/// The order kinds come back in, so consecutive blocks differ in feel (FLOW is built before it).
pub const ROTATION: [BlockKind; 5] = [BlockKind::TopPlayed, BlockKind::Discovery, BlockKind::FollowedArtist, BlockKind::Throwback, BlockKind::Mix];
/// How many top played songs a TOP_PLAYED block draws from, so it favours the real favourites.
const TOP_POOL: usize = MAX_TRACKS * 3;

/// Kinds worth trying after `previous`, in rotation order; never `previous` itself and only kinds
/// in `available`. With no previous block the session opens with TOP_PLAYED, then DISCOVERY.
pub fn kind_order(previous: Option<BlockKind>, available: &HashSet<BlockKind>) -> Vec<BlockKind> {
    let start = previous.and_then(|p| ROTATION.iter().position(|k| *k == p)).map_or(0, |i| i + 1);
    (0..ROTATION.len()).map(|i| ROTATION[(start + i) % ROTATION.len()]).filter(|k| Some(*k) != previous && available.contains(k)).collect()
}

pub fn next_kind(previous: Option<BlockKind>, available: &HashSet<BlockKind>) -> Option<BlockKind> {
    kind_order(previous, available).first().copied()
}

/// Kinds that have material. Local kinds need enough usable songs; FOLLOWED_ARTIST needs a
/// followed artist and DISCOVERY a seed, and only find out whether they work once the network
/// answers.
pub fn available_kinds(library: &Library, exclusions: &Exclusions) -> HashSet<BlockKind> {
    let mut out = HashSet::new();
    if usable(BlockKind::TopPlayed, &top_played_candidates(library), exclusions).len() >= MIN_TRACKS {
        out.insert(BlockKind::TopPlayed);
    }
    if seed_for(library).is_some() {
        out.insert(BlockKind::Discovery);
    }
    if library.followed_artists.iter().any(|a| !is_disliked_artist(&a.name, exclusions)) {
        out.insert(BlockKind::FollowedArtist);
    }
    if usable(BlockKind::Throwback, &throwback_candidates(library), exclusions).len() >= MIN_TRACKS {
        out.insert(BlockKind::Throwback);
    }
    if genre_mixes(library).any(|m| usable(BlockKind::Mix, &m.tracks, exclusions).len() >= MIN_TRACKS) {
        out.insert(BlockKind::Mix);
    }
    out
}

/// The song DISCOVERY starts from when autoplay gave none: the latest listen, else a favourite.
pub fn seed_for(library: &Library) -> Option<&TrackRef> {
    library.history.first().or(library.top_played.first()).or(library.favorites.first())
}

/// `candidates` minus disliked songs and artists, this session's songs and, except for
/// TOP_PLAYED, the recent history; deduplicated by id and by title and artist. Order is kept.
pub fn usable(kind: BlockKind, candidates: &[TrackRef], exclusions: &Exclusions) -> Vec<TrackRef> {
    let mut seen_ids = HashSet::new();
    let mut seen_keys = HashSet::new();
    candidates
        .iter()
        .filter(|track| {
            let key = track_key(track);
            if track.id.trim().is_empty() || exclusions.disliked_track_ids.contains(&track.id) {
                return false;
            }
            if artist_keys(track).iter().any(|k| exclusions.disliked_artist_keys.contains(k)) {
                return false;
            }
            if exclusions.session_ids.contains(&track.id) || exclusions.session_keys.contains(&key) {
                return false;
            }
            if kind != BlockKind::TopPlayed && (exclusions.history_ids.contains(&track.id) || exclusions.history_keys.contains(&key)) {
                return false;
            }
            // Both sets take the song (Kotlin's non-short-circuit `and`).
            let new_id = seen_ids.insert(track.id.clone());
            let new_key = seen_keys.insert(key);
            new_id && new_key
        })
        .cloned()
        .collect()
}

/// A block of `kind` from `candidates`, or `None` when fewer than [`MIN_TRACKS`] usable songs are
/// left. DISCOVERY keeps the engine's order and caps runs by one artist; the listener's own lists
/// are shuffled so the same favourites don't always open the block.
pub fn block<R: Rng + ?Sized>(kind: BlockKind, candidates: &[TrackRef], exclusions: &Exclusions, rng: &mut R, subject: Option<String>) -> Option<Block> {
    let mut pool = usable(kind, candidates, exclusions);
    let size = rng.gen_range(MIN_TRACKS..=MAX_TRACKS);
    let picked: Vec<TrackRef> = match kind {
        BlockKind::Discovery => limit_artist_runs(pool, size),
        BlockKind::TopPlayed => {
            pool.truncate(TOP_POOL);
            pool.shuffle(rng);
            pool.into_iter().take(size).collect()
        }
        _ => {
            pool.shuffle(rng);
            pool.into_iter().take(size).collect()
        }
    };
    (picked.len() >= MIN_TRACKS).then_some(Block { kind, tracks: picked, subject })
}

/// A TOP_PLAYED, THROWBACK or MIX block from the listener's own songs; `None` for other kinds.
pub fn local_block<R: Rng + ?Sized>(kind: BlockKind, library: &Library, exclusions: &Exclusions, rng: &mut R, avoid_mix: Option<&str>) -> Option<Block> {
    match kind {
        BlockKind::TopPlayed => block(kind, &top_played_candidates(library), exclusions, rng, None),
        BlockKind::Throwback => block(kind, &throwback_candidates(library), exclusions, rng, None),
        BlockKind::Mix => {
            let mixes: Vec<&DjMix> = genre_mixes(library).filter(|m| usable(kind, &m.tracks, exclusions).len() >= MIN_TRACKS).collect();
            let others: Vec<&DjMix> = mixes.iter().copied().filter(|m| Some(m.name.as_str()) != avoid_mix).collect();
            let choices = if others.is_empty() { mixes } else { others };
            let mix = choices.choose(rng)?;
            block(kind, &mix.tracks, exclusions, rng, Some(mix.name.clone()))
        }
        _ => None,
    }
}

/// A followed artist for the next FOLLOWED_ARTIST block, other than `avoid` when there is a choice.
pub fn pick_artist<'a, R: Rng + ?Sized>(followed: &'a [FollowedArtist], exclusions: &Exclusions, avoid: Option<&str>, rng: &mut R) -> Option<&'a FollowedArtist> {
    let liked: Vec<&FollowedArtist> = followed.iter().filter(|a| !a.name.trim().is_empty() && !is_disliked_artist(&a.name, exclusions)).collect();
    let avoid_key = artist_key(avoid.unwrap_or(""));
    let others: Vec<&FollowedArtist> = liked.iter().copied().filter(|a| artist_key(&a.name) != avoid_key).collect();
    let pool = if others.is_empty() { liked } else { others };
    pool.choose(rng).copied()
}

/// Songs in `tracks` credited to `artist`.
pub fn tracks_by_artist(artist: &str, tracks: &[TrackRef]) -> Vec<TrackRef> {
    let key = artist_key(artist);
    if key.trim().is_empty() {
        return Vec::new();
    }
    tracks.iter().filter(|t| artist_keys(t).contains(&key)).cloned().collect()
}

/// A FOLLOWED_ARTIST block: about half `artist_tracks` (the artist's own songs, one of them first
/// so the intro fits) mixed with `related` songs. Either side fills in when the other runs short.
pub fn artist_block<R: Rng + ?Sized>(artist: &str, artist_tracks: &[TrackRef], related: &[TrackRef], exclusions: &Exclusions, rng: &mut R) -> Option<Block> {
    let kind = BlockKind::FollowedArtist;
    let own_ids: HashSet<&str> = artist_tracks.iter().map(|t| t.id.as_str()).collect();
    let all: Vec<TrackRef> = artist_tracks.iter().chain(related).cloned().collect();
    let pool = usable(kind, &all, exclusions);
    let mut own: Vec<TrackRef> = pool.iter().filter(|t| own_ids.contains(t.id.as_str())).cloned().collect();
    let others: Vec<TrackRef> = pool.iter().filter(|t| !own_ids.contains(t.id.as_str())).cloned().collect();
    if own.is_empty() {
        return None;
    }
    own.shuffle(rng);
    let size = rng.gen_range(MIN_TRACKS..=MAX_TRACKS);
    let own_picked: Vec<TrackRef> = own.into_iter().take(size.div_ceil(2).max(size.saturating_sub(others.len()))).collect();
    let others_picked: Vec<TrackRef> = others.into_iter().take(size - own_picked.len()).collect();
    let mut tracks = Vec::new();
    for i in 0..own_picked.len().max(others_picked.len()) {
        tracks.extend(own_picked.get(i).cloned());
        tracks.extend(others_picked.get(i).cloned());
    }
    (tracks.len() >= MIN_TRACKS).then(|| Block { kind, tracks, subject: Some(artist.to_string()) })
}

fn top_played_candidates(library: &Library) -> Vec<TrackRef> {
    library.top_played.iter().chain(&library.favorites).cloned().collect()
}

/// Favourites first, then the less played of the top played, which the recent-history filter thins out.
fn throwback_candidates(library: &Library) -> Vec<TrackRef> {
    library.favorites.iter().chain(library.top_played.iter().rev()).cloned().collect()
}

fn genre_mixes(library: &Library) -> impl Iterator<Item = &DjMix> {
    library.mixes.iter().filter(|m| m.genre && !m.name.trim().is_empty())
}

fn is_disliked_artist(name: &str, exclusions: &Exclusions) -> bool {
    exclusions.disliked_artist_keys.contains(&artist_key(name))
}

/// The main artist, used for the run limit: the first credited one, as the phone's credit list.
fn lead_artist(track: &TrackRef) -> String {
    lfly_yt::credits::from_text(Some(&track.artist)).first().map_or_else(|| artist_key(&track.artist), |c| artist_key(&c.name))
}

/// Takes up to `size` songs in order, skipping any that would make a run longer than [`MAX_ARTIST_RUN`].
fn limit_artist_runs(mut remaining: Vec<TrackRef>, size: usize) -> Vec<TrackRef> {
    let mut picked: Vec<TrackRef> = Vec::with_capacity(size);
    while picked.len() < size {
        let Some(index) = remaining.iter().position(|t| !extends_long_run(&picked, t)) else { break };
        picked.push(remaining.remove(index));
    }
    picked
}

fn extends_long_run(picked: &[TrackRef], next: &TrackRef) -> bool {
    if picked.len() < MAX_ARTIST_RUN {
        return false;
    }
    let artist = lead_artist(next);
    picked[picked.len() - MAX_ARTIST_RUN..].iter().all(|t| lead_artist(t) == artist)
}

#[cfg(test)]
mod tests {
    //! The phone's DjBlocksTest, case for case.
    use super::*;
    use crate::dj::testing::{rng, track as make};

    fn track(id: &str) -> TrackRef {
        make(&format!("yt_{id}"), &format!("Song {id}"), &format!("Artist {id}"))
    }

    fn by(id: &str, artist: &str) -> TrackRef {
        make(&format!("yt_{id}"), &format!("Song {id}"), artist)
    }

    fn tracks(prefix: &str, count: usize) -> Vec<TrackRef> {
        (1..=count).map(|i| track(&format!("{prefix}{i}"))).collect()
    }

    fn all_kinds() -> HashSet<BlockKind> {
        ROTATION.into_iter().collect()
    }

    fn none() -> Exclusions {
        Exclusions::default()
    }

    #[test]
    fn first_block_prefers_top_played_then_discovery() {
        assert_eq!(next_kind(None, &all_kinds()), Some(BlockKind::TopPlayed));
        let mut without = all_kinds();
        without.remove(&BlockKind::TopPlayed);
        assert_eq!(next_kind(None, &without), Some(BlockKind::Discovery));
    }

    #[test]
    fn rotation_never_repeats_a_kind_and_visits_every_one() {
        let mut previous = None;
        let mut seen = Vec::new();
        for _ in 0..10 {
            let next = next_kind(previous, &all_kinds()).unwrap();
            assert_ne!(Some(next), previous);
            seen.push(next);
            previous = Some(next);
        }
        assert_eq!(seen[..5].iter().copied().collect::<HashSet<_>>(), all_kinds());
    }

    #[test]
    fn rotation_skips_kinds_without_material() {
        let available: HashSet<_> = [BlockKind::Discovery, BlockKind::Mix].into_iter().collect();
        assert_eq!(next_kind(Some(BlockKind::Discovery), &available), Some(BlockKind::Mix));
        assert_eq!(next_kind(Some(BlockKind::Mix), &available), Some(BlockKind::Discovery));
        assert_eq!(next_kind(Some(BlockKind::Mix), &[BlockKind::Mix].into_iter().collect()), None);
    }

    #[test]
    fn available_kinds_reflect_the_library() {
        assert!(available_kinds(&Library::default(), &none()).is_empty());
        let library = Library {
            top_played: tracks("t", 8),
            history: vec![track("h1")],
            followed_artists: vec![FollowedArtist { name: "Band".into(), browse_id: None }],
            mixes: vec![DjMix { name: "Rock".into(), genre: true, tracks: tracks("m", 6) }],
            ..Default::default()
        };
        assert_eq!(available_kinds(&library, &none()), all_kinds());
        // Every top played song heard lately: TOP_PLAYED still works, THROWBACK has nothing left.
        let heard = Exclusions::new([], [], vec![], &library.top_played);
        let kinds = available_kinds(&library, &heard);
        assert!(kinds.contains(&BlockKind::TopPlayed));
        assert!(!kinds.contains(&BlockKind::Throwback));
    }

    #[test]
    fn on_repeat_mixes_are_not_genre_blocks() {
        let library = Library { mixes: vec![DjMix { name: "On repeat".into(), genre: false, tracks: tracks("m", 6) }], ..Default::default() };
        assert!(!available_kinds(&library, &none()).contains(&BlockKind::Mix));
    }

    #[test]
    fn usable_dedupes_by_id_and_by_title_and_artist() {
        let a = make("yt_1", "Bohemian Rhapsody", "Queen");
        let same_id = TrackRef { title: "Bohemian Rhapsody (Remastered)".into(), ..a.clone() };
        let same_song = make("yt_2", "Bohemian  Rhapsody!", "QUEEN");
        let other = track("3");
        let got = usable(BlockKind::Discovery, &[a.clone(), same_id, same_song, other.clone()], &none());
        assert_eq!(got, vec![a, other]);
    }

    #[test]
    fn usable_drops_disliked_session_and_recent_songs() {
        let disliked = track("d");
        let bad_artist = by("b", "Bad Band");
        let played = track("p");
        let played_elsewhere = TrackRef { id: "yt_p2".into(), ..played.clone() };
        let recent = track("r");
        let fresh = track("f");
        let exclusions = Exclusions::new([disliked.id.clone()], ["bad band".to_string()], vec![played.clone()], std::slice::from_ref(&recent));
        let candidates = [disliked, bad_artist, played, played_elsewhere, recent.clone(), fresh.clone()];
        assert_eq!(usable(BlockKind::Discovery, &candidates, &exclusions), vec![fresh.clone()]);
        // TOP_PLAYED may repeat recent favourites, never this session's songs.
        assert_eq!(usable(BlockKind::TopPlayed, &candidates, &exclusions), vec![recent, fresh]);
    }

    #[test]
    fn blocks_hold_four_to_six_songs() {
        for seed in 0..50 {
            let b = block(BlockKind::Throwback, &tracks("x", 20), &none(), &mut rng(seed), None).unwrap();
            assert!((MIN_TRACKS..=MAX_TRACKS).contains(&b.tracks.len()));
        }
        assert!(block(BlockKind::Throwback, &tracks("x", 3), &none(), &mut rng(1), None).is_none());
    }

    #[test]
    fn discovery_never_plays_three_songs_by_one_artist_in_a_row() {
        let same: Vec<TrackRef> = (1..=5).map(|i| by(&format!("s{i}"), "Same")).collect();
        let all: Vec<TrackRef> = same.iter().cloned().chain(tracks("o", 3)).collect();
        for seed in 0..20 {
            let b = block(BlockKind::Discovery, &all, &none(), &mut rng(seed), None).unwrap();
            for run in b.tracks.windows(MAX_ARTIST_RUN + 1) {
                assert!(run.iter().map(|t| &t.artist).collect::<HashSet<_>>().len() > 1, "{:?}", b.tracks.iter().map(|t| &t.id).collect::<Vec<_>>());
            }
        }
        // Nobody else to break the run: the block can't fill up.
        assert!(block(BlockKind::Discovery, &same, &none(), &mut rng(0), None).is_none());
        // A featured credit is still the same lead artist.
        let feat = [by("1", "Band"), by("2", "Band feat. Friend"), by("3", "Band"), by("4", "Other")];
        let ids: Vec<String> = limit_artist_runs(feat.to_vec(), 4).into_iter().map(|t| t.id).collect();
        assert_eq!(ids, ["yt_1", "yt_2", "yt_4", "yt_3"]);
    }

    #[test]
    fn same_seed_gives_the_same_blocks() {
        let library = Library {
            top_played: tracks("t", 15),
            favorites: tracks("f", 15),
            mixes: vec![DjMix { name: "Rock".into(), genre: true, tracks: tracks("r", 10) }, DjMix { name: "Jazz".into(), genre: true, tracks: tracks("j", 10) }],
            ..Default::default()
        };
        for kind in [BlockKind::TopPlayed, BlockKind::Throwback, BlockKind::Mix] {
            let first = local_block(kind, &library, &none(), &mut rng(42), None);
            assert!(first.is_some());
            assert_eq!(first, local_block(kind, &library, &none(), &mut rng(42), None));
        }
    }

    #[test]
    fn mix_block_names_its_mix_and_avoids_the_last_one() {
        let library = Library {
            mixes: vec![DjMix { name: "Rock".into(), genre: true, tracks: tracks("r", 10) }, DjMix { name: "Jazz".into(), genre: true, tracks: tracks("j", 10) }],
            ..Default::default()
        };
        for seed in 0..10 {
            let b = local_block(BlockKind::Mix, &library, &none(), &mut rng(seed), Some("Rock")).unwrap();
            assert_eq!(b.subject.as_deref(), Some("Jazz"));
            assert!(b.tracks.iter().all(|t| t.id.starts_with("yt_j")));
        }
    }

    #[test]
    fn artist_block_opens_with_the_artist_and_mixes_in_related_songs() {
        let own: Vec<TrackRef> = (1..=6).map(|i| by(&format!("a{i}"), "Band")).collect();
        let related = tracks("r", 6);
        for seed in 0..20 {
            let b = artist_block("Band", &own, &related, &none(), &mut rng(seed)).unwrap();
            assert_eq!(b.kind, BlockKind::FollowedArtist);
            assert_eq!(b.subject.as_deref(), Some("Band"));
            assert_eq!(b.tracks[0].artist, "Band");
            assert!((MIN_TRACKS..=MAX_TRACKS).contains(&b.tracks.len()));
            assert!(b.tracks.iter().any(|t| t.artist != "Band"));
        }
        assert!(artist_block("Band", &[], &related, &none(), &mut rng(0)).is_none());
    }

    #[test]
    fn pick_artist_avoids_the_last_and_disliked_artists() {
        let followed: Vec<FollowedArtist> = ["Alpha", "Beta", "Gamma"].iter().map(|n| FollowedArtist { name: n.to_string(), browse_id: None }).collect();
        let exclusions = Exclusions::new([], ["Gamma".to_string()], vec![], &[]);
        for seed in 0..20 {
            assert_eq!(pick_artist(&followed, &exclusions, Some("alpha"), &mut rng(seed)).map(|a| a.name.as_str()), Some("Beta"));
        }
        assert_eq!(pick_artist(&followed[..1], &none(), Some("Alpha"), &mut rng(0)).map(|a| a.name.as_str()), Some("Alpha"));
    }

    #[test]
    fn tracks_by_artist_matches_credits() {
        let duet = by("1", "Band feat. Friend");
        let solo = by("2", "Band");
        let other = by("3", "Bandits");
        assert_eq!(tracks_by_artist("band", &[duet.clone(), solo.clone(), other]), vec![duet, solo]);
    }
}
