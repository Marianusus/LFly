//! The local player's queue: what plays, what comes next, shuffle and repeat. Pure logic, so the
//! rules are tested without audio.
//!
//! Shuffle works like Spotify's and the phone's: turning it on shuffles what comes after the
//! playing song; turning it off puts the remaining songs back in their original order.

use lfly_connect::protocol::TrackRef;
use rand::seq::SliceRandom;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Repeat {
    Off,
    One,
    All,
}

impl Repeat {
    pub fn from_wire(v: i64) -> Self {
        match v {
            lfly_connect::protocol::REPEAT_ONE => Repeat::One,
            lfly_connect::protocol::REPEAT_ALL => Repeat::All,
            _ => Repeat::Off,
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Repeat::Off => "off",
            Repeat::One => "one",
            Repeat::All => "all",
        }
    }
}

#[derive(Debug, Clone)]
pub struct Queue {
    pub tracks: Vec<TrackRef>,
    /// The playing song, or -1 when nothing is loaded.
    pub index: i32,
    pub shuffle: bool,
    pub repeat: Repeat,
    /// The order before shuffling, to go back to it.
    original: Vec<TrackRef>,
}

impl Default for Queue {
    fn default() -> Self {
        Self { tracks: Vec::new(), index: -1, shuffle: false, repeat: Repeat::Off, original: Vec::new() }
    }
}

impl Queue {
    pub fn current(&self) -> Option<&TrackRef> {
        usize::try_from(self.index).ok().and_then(|i| self.tracks.get(i))
    }

    /// A new queue, playing [index]. With shuffle on, the rest is shuffled at once.
    pub fn set(&mut self, tracks: Vec<TrackRef>, index: i32) {
        self.original = tracks.clone();
        self.tracks = tracks;
        self.index = if self.tracks.is_empty() { -1 } else { index.clamp(0, self.tracks.len() as i32 - 1) };
        if self.shuffle {
            self.shuffle_upcoming();
        }
    }

    /// The song after the current one when it ends by itself (repeat one plays it again).
    pub fn after_end(&self) -> Option<i32> {
        if self.repeat == Repeat::One && self.index >= 0 {
            return Some(self.index);
        }
        self.after_skip()
    }

    /// The song after the current one when the listener presses next.
    pub fn after_skip(&self) -> Option<i32> {
        if self.tracks.is_empty() {
            return None;
        }
        let next = self.index + 1;
        if (next as usize) < self.tracks.len() {
            Some(next)
        } else if self.repeat != Repeat::Off {
            Some(0)
        } else {
            None
        }
    }

    /// Previous: back to the start of the song after 3 s, else the song before.
    pub fn before(&self, position_ms: i64) -> Option<i32> {
        if self.index < 0 {
            return None;
        }
        if position_ms > 3_000 || self.index == 0 {
            if self.index == 0 && self.repeat == Repeat::All && position_ms <= 3_000 && self.tracks.len() > 1 {
                return Some(self.tracks.len() as i32 - 1);
            }
            return Some(self.index);
        }
        Some(self.index - 1)
    }

    pub fn add(&mut self, tracks: Vec<TrackRef>) {
        self.original.extend(tracks.iter().cloned());
        self.tracks.extend(tracks);
        if self.index < 0 && !self.tracks.is_empty() {
            self.index = 0;
        }
    }

    /// Right after the playing song, in the given order.
    pub fn add_next(&mut self, tracks: Vec<TrackRef>) {
        let at = (self.index + 1).max(0) as usize;
        let at = at.min(self.tracks.len());
        for (k, t) in tracks.iter().enumerate() {
            self.tracks.insert(at + k, t.clone());
        }
        self.original.extend(tracks);
        if self.index < 0 && !self.tracks.is_empty() {
            self.index = 0;
        }
    }

    /// Removes a row; true when it was the playing song (the caller loads the new current one).
    pub fn remove(&mut self, index: i32) -> bool {
        let Ok(i) = usize::try_from(index) else { return false };
        if i >= self.tracks.len() {
            return false;
        }
        let removed = self.tracks.remove(i);
        if let Some(pos) = self.original.iter().position(|t| t.id == removed.id) {
            self.original.remove(pos);
        }
        let was_current = index == self.index;
        if index < self.index {
            self.index -= 1;
        }
        if self.tracks.is_empty() {
            self.index = -1;
        } else if self.index as usize >= self.tracks.len() {
            self.index = self.tracks.len() as i32 - 1;
        }
        was_current
    }

    pub fn move_item(&mut self, from: i32, to: i32) {
        let len = self.tracks.len() as i32;
        if from < 0 || to < 0 || from >= len || to >= len || from == to {
            return;
        }
        let t = self.tracks.remove(from as usize);
        self.tracks.insert(to as usize, t);
        self.index = if self.index == from {
            to
        } else if from < self.index && to >= self.index {
            self.index - 1
        } else if from > self.index && to <= self.index {
            self.index + 1
        } else {
            self.index
        };
    }

    /// Drops what comes after the playing song.
    pub fn clear_upcoming(&mut self) {
        if self.index >= 0 {
            self.tracks.truncate(self.index as usize + 1);
            let keep: std::collections::HashSet<_> = self.tracks.iter().map(|t| t.id.clone()).collect();
            self.original.retain(|t| keep.contains(&t.id));
        }
    }

    pub fn set_shuffle(&mut self, on: bool) {
        if on == self.shuffle {
            return;
        }
        self.shuffle = on;
        if on {
            self.original = self.tracks.clone();
            self.shuffle_upcoming();
        } else if let Some(current) = self.current().cloned() {
            // The original order again, from the playing song on.
            let mut tracks = self.original.clone();
            if tracks.len() != self.tracks.len() || !tracks.iter().any(|t| t.id == current.id) {
                tracks = self.tracks.clone();
            }
            self.index = tracks.iter().position(|t| t.id == current.id).map_or(0, |i| i as i32);
            self.tracks = tracks;
        }
    }

    fn shuffle_upcoming(&mut self) {
        let start = (self.index + 1).max(0) as usize;
        if start < self.tracks.len() {
            self.tracks[start..].shuffle(&mut rand::thread_rng());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn t(id: &str) -> TrackRef {
        TrackRef {
            id: id.into(),
            title: id.into(),
            artist: "A".into(),
            album: None,
            duration_ms: 0,
            cover_url: None,
            provider: "YouTube Music".into(),
            artist_id: None,
            album_id: None,
            is_video: false,
            stream_url: None,
        }
    }

    fn ids(q: &Queue) -> Vec<String> {
        q.tracks.iter().map(|t| t.id.clone()).collect()
    }

    fn queue(n: usize, index: i32) -> Queue {
        let mut q = Queue::default();
        q.set((0..n).map(|i| t(&i.to_string())).collect(), index);
        q
    }

    #[test]
    fn next_stops_at_the_end_unless_repeating() {
        let mut q = queue(3, 2);
        assert_eq!(q.after_skip(), None);
        q.repeat = Repeat::All;
        assert_eq!(q.after_skip(), Some(0));
        q.repeat = Repeat::One;
        assert_eq!(q.after_end(), Some(2));
        assert_eq!(q.after_skip(), Some(0));
    }

    #[test]
    fn previous_restarts_after_three_seconds() {
        let q = queue(3, 1);
        assert_eq!(q.before(10_000), Some(1));
        assert_eq!(q.before(1_000), Some(0));
        assert_eq!(queue(3, 0).before(1_000), Some(0));
    }

    #[test]
    fn removing_keeps_the_playing_song() {
        let mut q = queue(4, 2);
        assert!(!q.remove(0));
        assert_eq!(q.current().unwrap().id, "2");
        assert!(q.remove(1));
        assert_eq!(q.index, 1);
        assert_eq!(q.current().unwrap().id, "3");
        q.remove(1);
        q.remove(0);
        assert_eq!(q.index, -1);
    }

    #[test]
    fn moving_follows_the_playing_song() {
        let mut q = queue(5, 2);
        q.move_item(2, 4);
        assert_eq!(q.index, 4);
        q.move_item(0, 4);
        assert_eq!(q.index, 3);
        assert_eq!(q.current().unwrap().id, "2");
        q.move_item(4, 0);
        assert_eq!(q.index, 4);
    }

    #[test]
    fn add_next_goes_right_after_the_playing_song() {
        let mut q = queue(3, 0);
        q.add_next(vec![t("a"), t("b")]);
        assert_eq!(ids(&q), ["0", "a", "b", "1", "2"]);
        q.clear_upcoming();
        assert_eq!(ids(&q), ["0"]);
    }

    #[test]
    fn shuffle_keeps_the_playing_song_and_off_restores_the_order() {
        let mut q = queue(30, 5);
        q.set_shuffle(true);
        assert_eq!(q.index, 5);
        assert_eq!(q.current().unwrap().id, "5");
        assert_eq!(&ids(&q)[..6], ["0", "1", "2", "3", "4", "5"]);
        let mut sorted = ids(&q);
        sorted.sort_by_key(|s| s.parse::<u32>().unwrap());
        assert_eq!(sorted, (0..30).map(|i| i.to_string()).collect::<Vec<_>>());
        q.index = 10;
        let playing = q.current().unwrap().id.clone();
        q.set_shuffle(false);
        assert_eq!(ids(&q), (0..30).map(|i| i.to_string()).collect::<Vec<_>>());
        assert_eq!(q.current().unwrap().id, playing);
    }
}
