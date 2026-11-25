//! How the DJ moves from song to song like a real one (the phone's `DjFlow`): it starts from a
//! song the listener loves and each next song flows out of the one before.

use lfly_connect::protocol::TrackRef;
use lfly_mix::key::MusicalKey;
use lfly_mix::planner::MAX_STRETCH;
use rand::Rng;

use super::artist_key;

/// What the flow knows about how a song sounds: its tempo and key, each `None` when unknown.
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct Groove {
    pub bpm: Option<f32>,
    pub key: Option<MusicalKey>,
}

/// Songs the recommendation engine offers for each step of the flow.
pub const CANDIDATES: usize = 15;
/// Most played songs a block may start from.
pub const ANCHOR_POOL: usize = 12;
/// Tempos further apart than this (Automix's own limit) no longer mix on the beat.
const TEMPO_TOLERANCE: f64 = MAX_STRETCH;
/// A song this close in tempo is sure to mix on the beat: a little inside Automix's limit, since
/// the start of a song, where its tempo was measured, can differ slightly from its ending.
const ON_THE_BEAT: f64 = 0.08;
/// How much a key that fits (same or a neighbour on the wheel) or clashes (3 steps or more) counts.
const KEY_WEIGHT: f64 = 0.75;
/// Too short for Automix to blend: such songs just end.
const MIN_MIX_MS: i64 = 60_000;
/// The best, second and third candidate are picked this often, so a session never repeats itself.
const PICK_WEIGHTS: [f64; 3] = [0.55, 0.3, 0.15];

/// No tempo or key known (the default: `TrackRef` carries neither).
pub fn unknown(_: &TrackRef) -> Groove {
    Groove::default()
}

/// How far apart two tempos are, as a ratio in log scale: 0 when they match. Double or half time
/// counts as far apart, since Automix cannot mix those on the beat.
pub fn tempo_distance(a: Option<f32>, b: Option<f32>) -> Option<f64> {
    match (a, b) {
        (Some(a), Some(b)) if a > 0.0 && b > 0.0 => Some((b as f64 / a as f64).ln().abs()),
        _ => None,
    }
}

/// How well `candidate` follows `previous`. The engine's own order says how close the style is
/// (`rank` of `total`); a matching tempo counts most, then a key that fits, a different artist
/// keeps the set moving, and songs too short to mix are pushed back.
pub fn score(previous: &TrackRef, candidate: &TrackRef, rank: usize, total: usize, recent_artists: &[String], groove: &dyn Fn(&TrackRef) -> Groove) -> f64 {
    let mut score = if total > 1 { 1.5 * (1.0 - rank as f64 / (total - 1) as f64) } else { 1.5 };
    let (from, to) = (groove(previous), groove(candidate));
    if let Some(d) = tempo_distance(from.bpm, to.bpm) {
        score += 2.0 * (1.0 - d / TEMPO_TOLERANCE).clamp(-1.0, 1.0);
    }
    if let (Some(a), Some(b)) = (from.key, to.key) {
        let steps = a.distance(&b);
        score += KEY_WEIGHT
            * match steps {
                ..=1 => 1.0,
                2 => 0.0,
                _ => -1.0,
            };
    }
    let artist = artist_key(&candidate.artist);
    if artist == artist_key(&previous.artist) {
        score -= 2.0;
    } else if recent_artists.iter().any(|a| artist_key(a) == artist) {
        score -= 1.0;
    }
    if (1..MIN_MIX_MS).contains(&candidate.duration_ms) {
        score -= 1.5;
    }
    score
}

/// True when `candidate` is known to mix on the beat after `previous`.
pub fn on_the_beat(previous: &TrackRef, candidate: &TrackRef, groove: &dyn Fn(&TrackRef) -> Groove) -> bool {
    tempo_distance(groove(previous).bpm, groove(candidate).bpm).is_some_and(|d| d <= ON_THE_BEAT)
}

/// `items` best first by `key`, ties in their given order (Kotlin's stable `sortedByDescending`).
fn ranked<T: Clone>(items: &[T], key: impl Fn(usize, &T) -> f64) -> Vec<T> {
    let mut scored: Vec<(T, f64)> = items.iter().enumerate().map(|(i, t)| (t.clone(), key(i, t))).collect();
    scored.sort_by(|a, b| b.1.total_cmp(&a.1));
    scored.into_iter().map(|(t, _)| t).collect()
}

/// The candidates worth learning the tempo of first: the best on style and variety alone, as
/// [`score`] ranks them before any tempo is known.
pub fn probe_order(previous: &TrackRef, candidates: &[TrackRef], recent_artists: &[String]) -> Vec<TrackRef> {
    ranked(candidates, |i, t| score(previous, t, i, candidates.len(), recent_artists, &unknown))
}

/// The next song of the flow from `candidates` (in the engine's order), or `None` when there are
/// none. Like a club DJ, it stays on the beat: when some candidates are known to mix on it, the
/// pick is among those only.
pub fn pick_next<R: Rng + ?Sized>(previous: &TrackRef, candidates: &[TrackRef], recent_artists: &[String], rng: &mut R, groove: &dyn Fn(&TrackRef) -> Groove) -> Option<TrackRef> {
    if candidates.is_empty() {
        return None;
    }
    let ranked = ranked(candidates, |i, t| score(previous, t, i, candidates.len(), recent_artists, groove));
    let matched: Vec<TrackRef> = ranked.iter().filter(|t| on_the_beat(previous, t, groove)).cloned().collect();
    Some(weighted_pick(if matched.is_empty() { &ranked } else { &matched }, rng))
}

/// Where a block starts: one of the listener's most played songs (`pool`, most played first). The
/// first block just picks among the favourites; later ones pick the favourite that follows best
/// from the song playing now, so even the jump to a new block sounds mixed.
pub fn pick_anchor<R: Rng + ?Sized>(pool: &[TrackRef], previous: Option<&TrackRef>, rng: &mut R, groove: &dyn Fn(&TrackRef) -> Groove) -> Option<TrackRef> {
    let head = &pool[..pool.len().min(ANCHOR_POOL)];
    if head.is_empty() {
        return None;
    }
    let Some(previous) = previous else {
        return Some(head[rng.gen_range(0..head.len())].clone());
    };
    // Each song draws its random part in order, as the Kotlin map does.
    let scores: Vec<f64> = head
        .iter()
        .map(|t| {
            let mut s = rng.gen_range(0.0..0.5);
            if let Some(d) = tempo_distance(groove(previous).bpm, groove(t).bpm) {
                s += 2.0 * (1.0 - d / TEMPO_TOLERANCE).clamp(-1.0, 1.0);
            }
            if artist_key(&t.artist) == artist_key(&previous.artist) {
                s -= 1.5;
            }
            s
        })
        .collect();
    let ranked = ranked(head, |i, _| scores[i]);
    Some(weighted_pick(&ranked, rng))
}

fn weighted_pick<R: Rng + ?Sized>(ranked: &[TrackRef], rng: &mut R) -> TrackRef {
    let top = &ranked[..ranked.len().min(PICK_WEIGHTS.len())];
    let weights = &PICK_WEIGHTS[..top.len()];
    let mut roll = rng.gen::<f64>() * weights.iter().sum::<f64>();
    for (track, w) in top.iter().zip(weights) {
        roll -= w;
        if roll <= 0.0 {
            return track.clone();
        }
    }
    top[top.len() - 1].clone()
}

#[cfg(test)]
mod tests {
    //! The phone's DjFlowTest, case for case. `TrackRef` has no tempo, so the tests give each song
    //! its catalogue tempo through the groove, as the phone's default groove reads `track.bpm`.
    use super::*;
    use crate::dj::testing::{rng, track as make};
    use std::collections::{HashMap, HashSet};

    struct Songs(HashMap<String, Groove>);

    impl Songs {
        fn new() -> Self {
            Songs(HashMap::new())
        }
        fn add(&mut self, id: &str, artist: Option<&str>, bpm: Option<f32>, duration_ms: i64) -> TrackRef {
            self.0.insert(id.into(), Groove { bpm, key: None });
            TrackRef { duration_ms, ..make(id, &format!("Song {id}"), artist.map_or(format!("Artist {id}"), str::to_string).as_str()) }
        }
        fn t(&mut self, id: &str) -> TrackRef {
            self.add(id, None, None, 200_000)
        }
        fn bpm(&mut self, id: &str, bpm: f32) -> TrackRef {
            self.add(id, None, Some(bpm), 200_000)
        }
        fn groove(&self) -> impl Fn(&TrackRef) -> Groove + '_ {
            move |t| self.0.get(&t.id).copied().unwrap_or_default()
        }
    }

    #[test]
    fn tempos_match_at_the_same_speed_not_at_double_or_half_time() {
        assert!(tempo_distance(Some(120.0), Some(120.0)).unwrap().abs() < 1e-9);
        assert!(tempo_distance(Some(70.0), Some(140.0)).unwrap() > 0.5);
        assert!((tempo_distance(Some(70.0), Some(140.0)).unwrap() - tempo_distance(Some(140.0), Some(70.0)).unwrap()).abs() < 1e-9);
        assert!(tempo_distance(Some(120.0), Some(128.0)).unwrap() < tempo_distance(Some(120.0), Some(100.0)).unwrap());
        assert_eq!(tempo_distance(None, Some(120.0)), None);
        assert_eq!(tempo_distance(Some(120.0), Some(0.0)), None);
    }

    #[test]
    fn a_matching_tempo_beats_a_better_rank_with_a_clashing_one() {
        let mut s = Songs::new();
        let (p, clashing, matching) = (s.bpm("p", 124.0), s.bpm("a", 95.0), s.bpm("b", 125.0));
        let g = s.groove();
        assert!(score(&p, &matching, 4, 10, &[], &g) > score(&p, &clashing, 0, 10, &[], &g));
    }

    #[test]
    fn the_same_artist_again_is_pushed_back_a_recent_one_a_little() {
        let p = make("p", "Song p", "Daft Punk");
        let same = score(&p, &make("a", "Song a", "Daft Punk"), 0, 5, &[], &unknown);
        let recent = score(&p, &make("b", "Song b", "Justice"), 0, 5, &["Justice".into()], &unknown);
        let fresh = score(&p, &make("c", "Song c", "Kavinsky"), 0, 5, &[], &unknown);
        assert!(fresh > recent);
        assert!(recent > same);
    }

    #[test]
    fn songs_too_short_to_mix_lose_out() {
        let mut s = Songs::new();
        let p = s.t("p");
        let short = s.add("a", None, None, 40_000);
        let normal = s.t("b");
        assert!(score(&p, &normal, 0, 5, &[], &unknown) > score(&p, &short, 0, 5, &[], &unknown));
    }

    #[test]
    fn the_next_song_is_always_one_of_the_three_best() {
        let mut s = Songs::new();
        let p = s.t("p");
        let candidates: Vec<TrackRef> = (1..=8).map(|i| s.t(&format!("c{i}"))).collect();
        for seed in 0..200 {
            let next = pick_next(&p, &candidates, &[], &mut rng(seed), &unknown).unwrap();
            assert!(["c1", "c2", "c3"].contains(&next.id.as_str()));
        }
    }

    #[test]
    fn when_some_songs_mix_on_the_beat_the_next_song_is_one_of_them() {
        let mut s = Songs::new();
        let p = s.bpm("p", 120.0);
        let candidates = vec![s.bpm("far1", 90.0), s.bpm("far2", 92.0), s.bpm("good1", 121.0), s.bpm("far3", 88.0), s.t("unknown"), s.bpm("good2", 116.0), s.bpm("double", 60.0)];
        let g = s.groove();
        let picks: HashSet<String> = (0..200).map(|seed| pick_next(&p, &candidates, &[], &mut rng(seed), &g).unwrap().id).collect();
        assert_eq!(picks, ["good1", "good2"].iter().map(|s| s.to_string()).collect());
    }

    #[test]
    fn the_analysed_tempo_and_key_are_used() {
        let mut s = Songs::new();
        let (p, clashing, fitting) = (s.t("p"), s.t("a"), s.t("b"));
        s.0.insert("p".into(), Groove { bpm: Some(124.0), key: Some(MusicalKey::new(9, true)) });
        s.0.insert("a".into(), Groove { bpm: Some(124.0), key: Some(MusicalKey::new(1, false)) });
        s.0.insert("b".into(), Groove { bpm: Some(125.0), key: Some(MusicalKey::new(4, true)) });
        let others: Vec<TrackRef> = (1..=8).map(|i| s.bpm(&format!("far{i}"), 90.0)).collect();
        let g = s.groove();
        assert!(score(&p, &fitting, 2, 10, &[], &g) > score(&p, &clashing, 0, 10, &[], &g));
        assert!(on_the_beat(&p, &fitting, &g));
        // Both are on the beat and close in the engine's order; the one in a fitting key wins more often.
        let pool: Vec<TrackRef> = [clashing, fitting].into_iter().chain(others).collect();
        let picks: Vec<String> = (0..200).map(|seed| pick_next(&p, &pool, &[], &mut rng(seed), &g).unwrap().id).collect();
        assert!(picks.iter().all(|id| id == "a" || id == "b"));
        assert!(picks.iter().filter(|id| *id == "b").count() > picks.len() / 2);
    }

    #[test]
    fn candidates_are_probed_best_first_on_style_and_variety() {
        let p = make("p", "Song p", "Same");
        let candidates = vec![make("sameArtist", "x", "Same"), make("c1", "x", "A1"), make("c2", "x", "A2"), make("c3", "x", "A3")];
        let order: Vec<String> = probe_order(&p, &candidates, &[]).into_iter().map(|t| t.id).collect();
        assert_eq!(order, ["c1", "c2", "c3", "sameArtist"]);
    }

    #[test]
    fn no_candidates_means_no_next_song() {
        assert!(pick_next(&make("p", "x", "y"), &[], &[], &mut rng(1), &unknown).is_none());
        assert!(pick_anchor(&[], None, &mut rng(1), &unknown).is_none());
    }

    #[test]
    fn the_anchor_comes_from_the_most_played_songs() {
        let mut s = Songs::new();
        let pool: Vec<TrackRef> = (1..=30).map(|i| s.t(&format!("t{i}"))).collect();
        for seed in 0..100 {
            let anchor = pick_anchor(&pool, None, &mut rng(seed), &unknown).unwrap();
            assert!(pool.iter().position(|t| *t == anchor).unwrap() < ANCHOR_POOL);
        }
    }

    #[test]
    fn a_later_anchor_follows_the_song_before_it() {
        let mut s = Songs::new();
        let p = s.add("p", Some("Same"), Some(128.0), 200_000);
        let pool = vec![s.bpm("slow", 90.0), s.add("sameArtist", Some("Same"), Some(128.0), 200_000), s.bpm("fits", 127.0), s.bpm("slow2", 85.0)];
        let g = s.groove();
        let picks: Vec<String> = (0..200).map(|seed| pick_anchor(&pool, Some(&p), &mut rng(seed), &g).unwrap().id).collect();
        assert!(picks.iter().filter(|id| *id == "fits").count() > picks.len() / 2);
        let mut counts: HashMap<&str, usize> = HashMap::new();
        for id in &picks {
            *counts.entry(id.as_str()).or_default() += 1;
        }
        assert_ne!(counts.iter().max_by_key(|(_, n)| **n).map(|(id, _)| *id), Some("sameArtist"));
    }

    #[test]
    fn same_seed_same_choice() {
        let mut s = Songs::new();
        let p = s.t("p");
        let candidates: Vec<TrackRef> = (1..=10).map(|i| s.t(&format!("c{i}"))).collect();
        assert_eq!(pick_next(&p, &candidates, &[], &mut rng(7), &unknown), pick_next(&p, &candidates, &[], &mut rng(7), &unknown));
    }
}
