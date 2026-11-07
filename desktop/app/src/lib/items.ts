// What a card does when opened or played.

import { api, type Item } from "./api";
import { nav } from "./nav.svelte";
import { player } from "./player.svelte";
import { connect } from "./connect.svelte";

export function openItem(item: Item) {
  switch (item.kind) {
    case "song":
      if (item.track) playSongWithRadio(item.track);
      break;
    case "album":
      nav.go({ page: "album", id: item.id, name: item.title });
      break;
    case "playlist":
      nav.go({ page: "playlist", id: item.id, name: item.title });
      break;
    case "artist":
      nav.go({ page: "artist", id: item.id, name: item.title });
      break;
  }
}

export async function playItem(item: Item) {
  try {
    if (item.kind === "song" && item.track) return playSongWithRadio(item.track);
    if (item.kind === "album") {
      const a = await api.album(item.id);
      return player.play(a.tracks, 0);
    }
    if (item.kind === "playlist") {
      const p = await api.playlist(item.id);
      return player.play(p.tracks, 0);
    }
  } catch (e) {
    connect.say(String(e));
  }
}

/** One song, followed by its radio, as YouTube Music does when you tap a song. */
export async function playSongWithRadio(track: import("./player.svelte").Track) {
  player.play([track], 0);
  try {
    const radio = (await api.radio(track)).filter((t) => t.id !== track.id);
    if (radio.length) player.addToQueue(radio.slice(0, 40), true);
  } catch {
    // The song plays anyway.
  }
}
