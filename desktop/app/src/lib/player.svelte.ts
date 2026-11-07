// One player for the whole window: what plays here, or on the device this computer controls.
//
// The local player reports the same state shape LFly Connect sends between devices, so every
// control works the same whether the music plays here or on a phone.

import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { connect, type RemoteState, type TrackRef, REPEAT } from "./connect.svelte";

/** A song as the pages pass it around (the phone's Track fields). */
export type Track = TrackRef & { streamUrl?: string; releaseYear?: number };

class Player {
  /** What the local player reported last, and this computer's clock then. */
  private local = $state<RemoteState | null>(null);
  private localAt = 0;
  private now = $state(Date.now());
  scrubbing = $state<number | null>(null);
  /** The local engine is available (it is being built in this version). */
  localReady = $state(false);

  remote = $derived(connect.following != null);
  state = $derived<RemoteState | null>(this.remote ? connect.remote : this.local);
  queue = $derived(this.state?.queue ?? []);
  index = $derived(this.state?.index ?? -1);
  track = $derived(this.index >= 0 ? (this.queue[this.index] ?? null) : null);
  playing = $derived(this.state?.playing ?? false);
  duration = $derived(this.state?.durationMs || this.track?.durationMs || 0);
  volume = $derived(this.state && this.state.volume >= 0 ? this.state.volume : 100);
  where = $derived(connect.following?.name ?? null);

  position = $derived.by(() => {
    if (this.scrubbing != null) return this.scrubbing;
    if (this.remote) return connect.position;
    const s = this.local;
    if (!s) return 0;
    const moved = s.playing ? Math.max(0, this.now - this.localAt) : 0;
    return Math.min(s.positionMs + moved, s.durationMs || Number.MAX_SAFE_INTEGER);
  });

  /** The position right now, read without tracking (for animations running every frame). */
  livePosition(): number {
    if (this.scrubbing != null || this.remote) return this.position;
    const s = this.local;
    if (!s) return 0;
    const moved = s.playing ? Math.max(0, Date.now() - this.localAt) : 0;
    return Math.min(s.positionMs + moved, s.durationMs || Number.MAX_SAFE_INTEGER);
  }

  async start() {
    try {
      await listen<{ state: RemoteState; receivedAt: number }>("player:state", (e) => {
        this.local = e.payload.state;
        this.localAt = e.payload.receivedAt;
        this.now = Date.now();
      });
      await listen<string>("player:notice", (e) => connect.say(e.payload));
      this.localReady = await invoke<boolean>("player_ready");
      const first = await invoke<RemoteState | null>("player_state");
      if (first && !this.local) {
        this.local = first;
        this.localAt = Date.now();
      }
    } catch {
      this.localReady = false;
    }
    setInterval(() => {
      if (this.local?.playing) this.now = Date.now();
    }, 250);
  }

  private command(action: string, value = 0) {
    if (this.remote) connect.command(action, value);
    else invoke("player_command", { action, value }).catch(() => {});
  }

  private edit(op: string, args: { tracks?: Track[]; index?: number; to?: number }) {
    const cmd = this.remote ? "connect_edit" : "player_edit";
    invoke(cmd, { op, ...args }).catch(() => {});
  }

  /** Plays [tracks] from [index]: here, or on the device this computer controls. */
  play(tracks: Track[], index = 0) {
    if (tracks.length === 0) return;
    const queue = tracks.map(ref);
    if (this.remote) {
      invoke("connect_play", { tracks: queue, index }).catch((e) => connect.say(String(e)));
    } else if (this.localReady) {
      invoke("player_play", { tracks: queue, index, positionMs: 0 }).catch((e) => connect.say(String(e)));
    } else {
      connect.say("Scegli un dispositivo su cui suonare: il player di questo computer arriva a breve.");
    }
  }

  toggle() {
    this.command("toggle");
  }
  /** Takes the music back from the device this computer controls, from where it was. */
  playHere() {
    invoke("connect_play_here").catch((e) => connect.say(String(e)));
  }
  next() {
    this.command("next");
  }
  previous() {
    this.command("previous");
  }
  seek(ms: number) {
    const max = this.duration || ms;
    this.command("seek", Math.round(Math.max(0, Math.min(ms, max))));
  }
  setVolume(percent: number) {
    this.command("volume", Math.round(percent));
  }
  setShuffle(on: boolean) {
    this.command("shuffle", on ? 1 : 0);
  }
  cycleRepeat() {
    const next = { off: "all", all: "one", one: "off" } as const;
    this.command("repeat", REPEAT[next[this.state?.repeat ?? "off"]]);
  }
  skipTo(index: number) {
    this.command("skip_to", index);
  }
  removeAt(index: number) {
    this.edit("remove", { index });
  }
  addToQueue(tracks: Track[], quiet = false) {
    this.edit("add", { tracks: tracks.map(ref) });
    if (!quiet) connect.say(tracks.length === 1 ? "Aggiunto alla coda" : `${tracks.length} brani aggiunti alla coda`);
  }
  playNext(track: Track) {
    this.edit("next", { tracks: [ref(track)] });
    connect.say("Suonerà dopo questo brano");
  }
}

/** Only what travels between devices. */
function ref(t: Track): TrackRef {
  return {
    id: t.id,
    title: t.title,
    artist: t.artist,
    album: t.album,
    durationMs: t.durationMs ?? 0,
    coverUrl: t.coverUrl,
    provider: t.provider ?? "YouTube Music",
    artistId: t.artistId,
    albumId: t.albumId,
    isVideo: t.isVideo ?? false,
  };
}

export const player = new Player();
