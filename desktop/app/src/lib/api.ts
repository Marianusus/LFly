// What the core answers about YouTube Music. The shapes are LFly's own (src-tauri/src/yt.rs maps
// the client's results onto them), so the pages never depend on the client's internals.

import { invoke } from "@tauri-apps/api/core";
import type { Track } from "./player.svelte";

export type ItemKind = "song" | "album" | "playlist" | "artist";

/** One card on a shelf or in the results. */
export type Item = {
  kind: ItemKind;
  id: string;
  title: string;
  subtitle?: string;
  coverUrl?: string;
  /** For songs: the song itself, ready to play. */
  track?: Track;
};

export type Shelf = { title: string; items: Item[] };

export type SearchResults = {
  top?: Item | null;
  songs: Track[];
  albums: Item[];
  artists: Item[];
  playlists: Item[];
};

export type ArtistPage = {
  name: string;
  coverUrl?: string;
  description?: string;
  subscribers?: string;
  songs: Track[];
  albums: Item[];
  singles: Item[];
  related: Item[];
};

export type AlbumPage = {
  title: string;
  artist: string;
  artistId?: string;
  year?: string;
  coverUrl?: string;
  tracks: Track[];
};

export type PlaylistPage = {
  title: string;
  author?: string;
  description?: string;
  coverUrl?: string;
  tracks: Track[];
};

export type LyricWord = { ms: number; endMs: number; text: string };
export type LyricLine = { ms: number; endMs?: number; text: string; words?: LyricWord[]; bg?: string };
export type Lyrics = { synced?: LyricLine[] | null; plain?: string | null; source?: string; instrumental?: boolean };

export const api = {
  home: () => invoke<Shelf[]>("yt_home"),
  search: (query: string) => invoke<SearchResults>("yt_search", { query }),
  suggestions: (query: string) => invoke<string[]>("yt_suggestions", { query }),
  artist: (id: string) => invoke<ArtistPage>("yt_artist", { id }),
  album: (id: string) => invoke<AlbumPage>("yt_album", { id }),
  playlist: (id: string) => invoke<PlaylistPage>("yt_playlist", { id }),
  radio: (track: Track) => invoke<Track[]>("yt_radio", { track }),
  lyrics: (track: Track) => invoke<Lyrics | null>("yt_lyrics", { track }),
};

/** A promise's state for a page: loading, the value, or what went wrong. */
export function errorText(e: unknown): string {
  const s = String(e instanceof Error ? e.message : e);
  return /network|connect|dns|timed? ?out/i.test(s)
    ? "Nessuna connessione a YouTube Music. Controlla la rete e riprova."
    : `Qualcosa non ha funzionato: ${s}`;
}
