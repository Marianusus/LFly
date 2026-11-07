// Small helpers the collection pages share.
import { player, type Track } from "../../player.svelte";

/** "42 min" or "1 h 12 min" for a list of songs, or "" when the lengths are unknown. */
export function minutes(tracks: Track[]): string {
  const ms = tracks.reduce((s, t) => s + (t.durationMs || 0), 0);
  if (!ms) return "";
  const m = Math.round(ms / 60000);
  return m >= 60 ? `${Math.floor(m / 60)} h ${m % 60} min` : `${m} min`;
}

/** Plays the songs in random order, from a random one. */
export function shuffle(tracks: Track[]) {
  if (!tracks.length) return;
  player.play(tracks, Math.floor(Math.random() * tracks.length));
  player.setShuffle(true);
}
