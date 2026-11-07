// The listener's library on this computer, in the phone's own format (a backup imports as is).

import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { prefs } from "./prefs.svelte";
import { connect } from "./connect.svelte";
import { account } from "./account.svelte";
import type { Track } from "./player.svelte";

export type Playlist = {
  id: string;
  name: string;
  description?: string;
  coverUrl?: string;
  tracks: Track[];
  createdAt?: number;
  updatedAt?: number;
  [key: string]: unknown;
};

type Saved = { favorites: Track[]; playlists: Playlist[]; history: Track[] };

class Library {
  favorites = $state<Track[]>([]);
  playlists = $state<Playlist[]>([]);
  history = $state<Track[]>([]);
  private favoriteIds = $derived(new Set(this.favorites.map((t) => t.id)));

  async start() {
    // Drive brought the phone's changes: read the library again.
    listen("library:changed", () => this.reload()).catch(() => {});
    await this.reload();
  }

  async reload() {
    try {
      const lib = await invoke<Saved>("library_get");
      this.favorites = lib.favorites ?? [];
      this.playlists = (lib.playlists ?? []).filter((p) => p && typeof p.id === "string");
      this.history = lib.history ?? [];
    } catch (e) {
      console.warn("Library not loaded", e);
    }
  }

  isFavorite(id: string) {
    return this.favoriteIds.has(id);
  }

  toggleFavorite(track: Track) {
    if (this.isFavorite(track.id)) {
      this.favorites = this.favorites.filter((t) => t.id !== track.id);
      account.rate(track.id, null);
    } else {
      this.favorites = [{ ...$state.snapshot(track) as Track }, ...this.favorites];
      connect.say("Aggiunto ai Brani che ti piacciono");
      account.rate(track.id, true);
    }
    this.persist("favorites.json", this.favorites);
  }

  playlist(id: string) {
    return this.playlists.find((p) => p.id === id) ?? null;
  }

  addToPlaylist(id: string, tracks: Track[]) {
    const p = this.playlist(id);
    if (!p) return;
    const have = new Set(p.tracks.map((t) => t.id));
    const fresh = tracks.filter((t) => !have.has(t.id)).map((t) => $state.snapshot(t) as Track);
    p.tracks = [...p.tracks, ...fresh];
    p.updatedAt = Date.now();
    this.persist("playlists.json", this.playlists);
    connect.say(fresh.length ? `Aggiunto a ${p.name}` : `Già in ${p.name}`);
  }

  createPlaylist(name: string, tracks: Track[] = [], coverUrl?: string) {
    const now = Date.now();
    const p: Playlist = {
      id: crypto.randomUUID(),
      name: name.trim() || "La mia playlist",
      tracks: tracks.map((t) => $state.snapshot(t) as Track),
      ...(coverUrl ? { coverUrl } : {}),
      schemaVersion: 2,
      createdAt: now,
      updatedAt: now,
    };
    this.playlists = [p, ...this.playlists];
    this.persist("playlists.json", this.playlists);
    return p;
  }

  removeFromPlaylist(id: string, index: number) {
    const p = this.playlist(id);
    if (!p) return;
    p.tracks = p.tracks.filter((_, i) => i !== index);
    p.updatedAt = Date.now();
    this.persist("playlists.json", this.playlists);
  }

  renamePlaylist(id: string, name: string) {
    const p = this.playlist(id);
    if (!p || !name.trim()) return;
    p.name = name.trim();
    p.updatedAt = Date.now();
    this.persist("playlists.json", this.playlists);
  }

  deletePlaylist(id: string) {
    this.playlists = this.playlists.filter((p) => p.id !== id);
    this.persist("playlists.json", this.playlists);
  }

  clearHistory() {
    this.history = [];
    this.persist("history.json", this.history);
  }

  /** What was just played, newest first, without repeats; kept to 500 songs like a phone. */
  played(track: Track) {
    if (prefs.v.pauseHistory) return;
    this.history = [$state.snapshot(track) as Track, ...this.history.filter((t) => t.id !== track.id)].slice(0, 500);
    this.persist("history.json", this.history);
  }

  async importBackup() {
    try {
      const done = await invoke<{ favorites: number; playlists: number; history: number } | null>("library_import");
      if (!done) return;
      await this.start();
      connect.say(`Importati ${done.playlists} playlist, ${done.favorites} preferiti e ${done.history} brani ascoltati`);
    } catch (e) {
      connect.say(String(e));
    }
  }

  private persist(name: string, value: unknown) {
    invoke("library_save", { name, value: $state.snapshot(value) }).catch((e) => connect.say(`Non salvato: ${e}`));
  }
}

export const library = new Library();
