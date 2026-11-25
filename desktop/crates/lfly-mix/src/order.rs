//! Spotify's "Smart Reorder" (the phone's `MixOrder.kt`): puts a playlist in an order where each
//! song flows into the next, by tempo first (so the beats can be matched) and key second (so the
//! harmonies do not clash). Pure.
//!
//! The first song stays first, as the listener chose how the set opens. From there a greedy walk
//! takes the closest song each time, then 2-opt passes undo the crossings the greedy walk leaves.
//! Songs nothing is known about keep their order at the end.

use crate::key::MusicalKey;
use crate::planner::MAX_STRETCH;
use crate::util::first_min_by;

/// What the reorder needs to know about one song: its tempo at both ends and its key.
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct MixTrackInfo {
    /// Tempo of the song's start, as the next song after another one would meet it.
    pub head_bpm: Option<f64>,
    /// Tempo of the song's ending.
    pub tail_bpm: Option<f64>,
    pub key: Option<MusicalKey>,
}

impl MixTrackInfo {
    /// The tempo to show: the start's, else the ending's.
    pub fn bpm(&self) -> Option<f64> {
        self.head_bpm.or(self.tail_bpm)
    }
}

/// Past [`MAX_STRETCH`] the beats cannot be matched: such a step costs a lot more.
const UNMATCHABLE: f64 = 4.0;
const KEY_STEP: f64 = 0.35;
const UNKNOWN_KEY: f64 = 0.6;
const UNKNOWN_TEMPO: f64 = 1.5;
const PASSES: usize = 4;

/// How hard it is to mix `from` into `to`: 0 for the same tempo and key.
pub fn cost(from: &MixTrackInfo, to: &MixTrackInfo) -> f64 {
    let tempo = match (from.tail_bpm.or(from.head_bpm), to.head_bpm.or(to.tail_bpm)) {
        (Some(a), Some(b)) if a > 0.0 && b > 0.0 => {
            let gap = (b / a).ln().abs();
            gap / MAX_STRETCH + if gap > MAX_STRETCH { UNMATCHABLE } else { 0.0 }
        }
        _ => UNKNOWN_TEMPO,
    };
    let key = match (from.key, to.key) {
        (Some(a), Some(b)) => a.distance(&b) as f64 * KEY_STEP,
        _ => UNKNOWN_KEY,
    };
    tempo + key
}

/// The suggested order, as indexes into `infos` (`None` for songs not analysed).
pub fn suggest(infos: &[Option<MixTrackInfo>]) -> Vec<usize> {
    let all: Vec<usize> = (0..infos.len()).collect();
    if infos.len() < 3 {
        return all;
    }
    let known_info = |i: usize| infos[i].filter(|info| info.bpm().is_some());
    let known: Vec<usize> = all.iter().copied().filter(|&i| known_info(i).is_some()).collect();
    let unknown: Vec<usize> = all.iter().copied().filter(|&i| known_info(i).is_none()).collect();
    if known.len() < 3 {
        return all;
    }
    let info = |i: usize| infos[i].expect("known");
    // The opening song stays where it is when it is known; otherwise the first known one opens.
    let mut remaining = known;
    let mut order = vec![remaining.remove(0)];
    while !remaining.is_empty() {
        let last = info(*order.last().expect("one at least"));
        let next = first_min_by(remaining.iter().copied(), |i| cost(&last, &info(i))).expect("not empty");
        remaining.retain(|&i| i != next);
        order.push(next);
    }
    improve(&mut order, |a, b| cost(&info(a), &info(b)));
    order.extend(unknown);
    order
}

/// Total cost of playing the songs in `order`.
pub fn total_cost(infos: &[Option<MixTrackInfo>], order: &[usize]) -> f64 {
    order
        .windows(2)
        .map(|w| match (infos[w[0]], infos[w[1]]) {
            (Some(from), Some(to)) => cost(&from, &to),
            _ => UNKNOWN_TEMPO + UNKNOWN_KEY,
        })
        .sum()
}

/// 2-opt on an open path with a fixed start: reverses any stretch whose reversal makes the path cheaper.
fn improve(order: &mut [usize], cost: impl Fn(usize, usize) -> f64) {
    let n = order.len();
    for _ in 0..PASSES {
        let mut changed = false;
        for i in 1..n.saturating_sub(1) {
            for j in i + 1..n {
                let tail = |o: &[usize], a: usize| if j + 1 < n { cost(o[a], o[j + 1]) } else { 0.0 };
                let before = cost(order[i - 1], order[i]) + tail(order, j);
                let after = cost(order[i - 1], order[j]) + tail(order, i);
                // Reversing changes the direction of the inner steps too: count them.
                let inner: f64 = (i..j).map(|k| cost(order[k], order[k + 1])).sum();
                let inner_reversed: f64 = (i..j).map(|k| cost(order[k + 1], order[k])).sum();
                if after + inner_reversed + 1e-9 < before + inner {
                    order[i..=j].reverse();
                    changed = true;
                }
            }
        }
        if !changed {
            return;
        }
    }
}

#[cfg(test)]
mod tests {
    //! The Smart Reorder part of the phone's PlaylistMixTest, case for case.
    use super::*;

    fn info(bpm: f64, key: Option<MusicalKey>) -> Option<MixTrackInfo> {
        Some(MixTrackInfo { head_bpm: Some(bpm), tail_bpm: Some(bpm), key })
    }

    fn minor(tonic: u8) -> Option<MusicalKey> {
        Some(MusicalKey::new(tonic, true))
    }

    #[test]
    fn smart_reorder_walks_the_tempos_and_keeps_the_opener() {
        let infos = [info(120.0, minor(9)), info(140.0, minor(9)), info(122.0, minor(4)), info(138.0, minor(4)), info(124.0, minor(11))];
        let order = suggest(&infos);
        assert_eq!(order, vec![0, 2, 4, 3, 1]);
        assert!(total_cost(&infos, &order) < total_cost(&infos, &[0, 1, 2, 3, 4]));
    }

    #[test]
    fn songs_without_analysis_keep_their_order_at_the_end() {
        let infos = [info(120.0, None), None, info(140.0, None), info(121.0, None), None];
        assert_eq!(suggest(&infos), vec![0, 3, 2, 1, 4]);
    }

    #[test]
    fn too_little_to_go_on_leaves_the_order_alone() {
        assert_eq!(suggest(&[info(120.0, None), info(150.0, None)]), vec![0, 1]);
        assert_eq!(suggest(&[info(120.0, None), None, None]), vec![0, 1, 2]);
    }

    #[test]
    fn a_clashing_key_costs_more_than_a_neighbour() {
        let from = info(120.0, minor(9)).unwrap();
        let near = info(120.0, Some(MusicalKey::new(0, false))).unwrap();
        let far = info(120.0, minor(3)).unwrap();
        assert!(cost(&from, &near) < cost(&from, &far));
        // A tempo the beats cannot follow costs more than any key.
        assert!(cost(&from, &info(150.0, minor(9)).unwrap()) > cost(&from, &far));
    }
}
