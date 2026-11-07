// LFly Connect as the window sees it: the devices on the network, the one this computer controls
// and what plays there. The core (Rust) does the talking; this only mirrors its events.

import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";

export type ConnectDevice = {
  id: string;
  name: string;
  kind: string;
  sameAccount: boolean;
  host: string;
  port: number;
};

export type TrackRef = {
  id: string;
  title: string;
  artist: string;
  album?: string;
  durationMs: number;
  coverUrl?: string;
  provider: string;
  artistId?: string;
  albumId?: string;
  isVideo: boolean;
};

export type RemoteState = {
  deviceId: string;
  active: boolean;
  playing: boolean;
  positionMs: number;
  at: number;
  durationMs: number;
  index: number;
  queue: TrackRef[];
  shuffle: boolean;
  repeat: "off" | "one" | "all";
  volume: number;
  handedTo?: string;
};

type ControllerEvent =
  | { kind: "connecting"; deviceId: string | null }
  | { kind: "pairing"; deviceName: string; code: string }
  | { kind: "refused"; deviceName: string }
  | { kind: "following"; device: ConnectDevice | null }
  | { kind: "state"; state: RemoteState; receivedAt: number }
  | { kind: "handedTo"; deviceId: string };

type HostEvent =
  | { kind: "pairRequest"; request: number; deviceName: string; code: string }
  | { kind: "pairGone"; request: number }
  | { kind: "trusted"; deviceId: string; deviceName: string }
  | { kind: "controllers"; count: number };

export const REPEAT = { off: 0, one: 1, all: 2 } as const;

class Connect {
  devices = $state<ConnectDevice[]>([]);
  following = $state<ConnectDevice | null>(null);
  connecting = $state<string | null>(null);
  pairing = $state<{ deviceName: string; code: string } | null>(null);
  notice = $state<string | null>(null);
  remote = $state<RemoteState | null>(null);
  me = $state<{ id: string; name: string } | null>(null);
  /** A device asking to control this computer, for the dialog. */
  request = $state<{ request: number; deviceName: string; code: string } | null>(null);
  /** How many devices control this computer now. */
  controllers = $state(0);

  /** This computer's clock when [remote] arrived, to run the progress bar between updates. */
  private receivedAt = 0;
  /** Ticks while something plays, so the position below moves. */
  private now = $state(Date.now());
  /** A seek being dragged: the bar follows the hand, not the device. */
  scrubbing = $state<number | null>(null);

  current = $derived(this.remote && this.remote.index >= 0 ? this.remote.queue[this.remote.index] ?? null : null);

  position = $derived.by(() => {
    if (this.scrubbing != null) return this.scrubbing;
    const s = this.remote;
    if (!s) return 0;
    const moved = s.playing ? Math.max(0, this.now - this.receivedAt) : 0;
    return Math.min(s.positionMs + moved, s.durationMs || Number.MAX_SAFE_INTEGER);
  });

  async start() {
    await listen<ConnectDevice[]>("connect:devices", (e) => (this.devices = e.payload));
    await listen<ControllerEvent>("connect:event", (e) => this.on(e.payload));
    await listen<HostEvent>("host:event", (e) => this.onHost(e.payload));
    await listen<typeof this.me>("connect:me", (e) => (this.me = e.payload));
    this.me = await invoke("connect_me");
    this.devices = await invoke("connect_devices");
    this.following = await invoke("connect_following");
    setInterval(() => {
      if (this.remote?.playing) this.now = Date.now();
    }, 250);
  }

  private on(e: ControllerEvent) {
    switch (e.kind) {
      case "connecting":
        this.connecting = e.deviceId;
        if (!e.deviceId) this.pairing = null;
        break;
      case "pairing":
        this.pairing = { deviceName: e.deviceName, code: e.code };
        break;
      case "refused":
        this.pairing = null;
        this.say(`${e.deviceName} non ha accettato questo computer.`);
        break;
      case "following":
        this.pairing = null;
        if (!e.device && this.following) this.say(`Collegamento con ${this.following.name} perso.`);
        this.following = e.device;
        if (!e.device) this.remote = null;
        break;
      case "state":
        this.receivedAt = e.receivedAt;
        this.now = Date.now();
        this.remote = e.state;
        break;
      case "handedTo": {
        const next = this.devices.find((d) => d.id === e.deviceId);
        if (next) {
          this.follow(next);
        } else {
          this.say("La musica è passata a un altro dispositivo.");
          this.disconnect();
        }
        break;
      }
    }
  }

  private onHost(e: HostEvent) {
    switch (e.kind) {
      case "pairRequest":
        this.request = { request: e.request, deviceName: e.deviceName, code: e.code };
        break;
      case "pairGone":
        if (this.request?.request === e.request) this.request = null;
        break;
      case "trusted":
        this.say(`${e.deviceName} ora può controllare questo computer`);
        break;
      case "controllers":
        this.controllers = e.count;
        break;
    }
  }

  answer(accept: boolean) {
    const r = this.request;
    if (!r) return;
    this.request = null;
    invoke("host_answer", { request: r.request, accept }).catch(() => {});
  }

  say(text: string) {
    this.notice = text;
    setTimeout(() => {
      if (this.notice === text) this.notice = null;
    }, 5000);
  }

  follow(device: ConnectDevice) {
    this.connecting = device.id;
    invoke("connect_follow", { id: device.id }).catch((err) => {
      this.connecting = null;
      this.say(String(err));
    });
  }

  disconnect() {
    this.pairing = null;
    this.connecting = null;
    invoke("connect_disconnect");
  }

  command(action: string, value = 0) {
    invoke("connect_command", { action, value });
  }

  toggle() {
    this.command("toggle");
  }

  seek(ms: number) {
    const s = this.remote;
    if (!s) return;
    this.command("seek", Math.round(Math.max(0, Math.min(ms, s.durationMs || ms))));
  }

  skipTo(index: number) {
    this.command("skip_to", index);
  }

  removeAt(index: number) {
    invoke("connect_edit", { op: "remove", index });
  }

  setVolume(percent: number) {
    this.command("volume", Math.round(percent));
  }

  cycleRepeat() {
    const next = { off: "all", all: "one", one: "off" } as const;
    this.command("repeat", REPEAT[next[this.remote?.repeat ?? "off"]]);
  }
}

export const connect = new Connect();

export function time(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${m}:${s.toString().padStart(2, "0")}`;
}

/** Bigger covers from YouTube Music's image server, which sizes them by URL. */
/**
 * A cover at about `size` pixels, loaded through the core (`cover://`): Google's image servers
 * refuse the window's own requests with 429, and the core also keeps the covers on disk.
 */
export function cover(url: string | undefined, size: number): string | undefined {
  if (!url) return undefined;
  const sized = url.replace(/=w\d+-h\d+/, `=w${size}-h${size}`).replace(/=s\d+$/, `=s${size}`);
  if (!sized.startsWith("https://")) return sized;
  const internals = (window as { __TAURI_INTERNALS__?: { convertFileSrc?: (p: string, protocol: string) => string } })
    .__TAURI_INTERNALS__;
  return internals?.convertFileSrc ? internals.convertFileSrc(sized, "cover") : sized;
}
