// The songs downloaded to this computer, and the ones on their way. The core does the work.

import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { connect } from "./connect.svelte";
import type { Track } from "./player.svelte";

class Downloads {
  tracks = $state<Track[]>([]);
  bytes = $state(0);
  /** Songs on their way: id → 0..1. */
  progress = $state<Record<string, number>>({});
  private ids = $derived(new Set(this.tracks.map((t) => t.id)));

  async start() {
    listen<Track[]>("downloads:changed", () => this.refresh()).catch(() => {});
    listen<{ id: string; done: number }>("downloads:progress", (e) => {
      const { id, done } = e.payload;
      if (done < 0) {
        const { [id]: _, ...rest } = this.progress;
        this.progress = rest;
        connect.say("Un download non è riuscito: riprova più tardi.");
      } else if (done >= 1) {
        const { [id]: _, ...rest } = this.progress;
        this.progress = rest;
      } else {
        this.progress = { ...this.progress, [id]: done };
      }
    }).catch(() => {});
    await this.refresh();
  }

  async refresh() {
    try {
      const list = await invoke<{ tracks: Track[]; bytes: number }>("downloads_list");
      this.tracks = list.tracks;
      this.bytes = list.bytes;
    } catch (e) {
      console.warn("downloads", e);
    }
  }

  has(id: string) {
    return this.ids.has(id);
  }

  pending(id: string) {
    return id in this.progress;
  }

  add(tracks: Track[]) {
    const fresh = tracks.filter((t) => !this.has(t.id) && !this.pending(t.id));
    if (!fresh.length) return;
    invoke("downloads_add", { tracks: fresh.map((t) => $state.snapshot(t)) }).catch((e) => connect.say(String(e)));
    connect.say(fresh.length === 1 ? "Download avviato" : `Download di ${fresh.length} brani avviato`);
  }

  remove(ids: string[]) {
    invoke("downloads_remove", { ids }).catch((e) => connect.say(String(e)));
  }
}

export const downloads = new Downloads();
