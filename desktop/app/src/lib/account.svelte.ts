// The YouTube Music account on this computer: who is signed in, the sign-in steps, and the
// listener's playlists on YouTube Music. The core holds the session; this only mirrors it.

import { invoke } from "@tauri-apps/api/core";
import type { Item } from "./api";
import { connect } from "./connect.svelte";

export type AccountInfo = { name: string; handle: string; email: string; avatarUrl?: string | null };

class AccountStore {
  info = $state<AccountInfo | null>(null);
  /** "browser": Google's window is open; "checking": reading the session. */
  step = $state<"idle" | "browser" | "checking">("idle");
  error = $state("");
  playlists = $state<Item[]>([]);
  /** "Listening to" on Discord, through the Discord app on this computer. */
  discord = $state(true);

  async load() {
    invoke<{ enabled?: boolean } | null>("store_get", { key: "discord" })
      .then((d) => (this.discord = d?.enabled ?? true))
      .catch(() => {});
    try {
      this.info = await invoke<AccountInfo | null>("account_get");
      if (this.info) this.playlists = await invoke<Item[]>("yt_library");
    } catch (e) {
      console.warn("account", e);
    }
  }

  async signIn() {
    this.error = "";
    try {
      await invoke("account_login");
      this.step = "browser";
    } catch (e) {
      this.error = String(e);
    }
  }

  /** The listener says YouTube Music shows their account. */
  async done() {
    this.step = "checking";
    this.error = "";
    try {
      const info = await invoke<AccountInfo>("account_login_done");
      connect.say(`Accesso come ${info.name}: i tuoi dispositivi si collegano senza codice`);
      this.info = info;
      this.step = "idle";
      this.playlists = await invoke<Item[]>("yt_library").catch(() => []);
    } catch (e) {
      this.error = String(e);
      this.step = "browser";
    }
  }

  async cancel() {
    this.step = "idle";
    this.error = "";
    await invoke("account_login_cancel").catch(() => {});
  }

  async signOut() {
    try {
      await invoke("account_logout");
      this.info = null;
      this.playlists = [];
      connect.say("Sei uscito dall'account YouTube Music");
    } catch (e) {
      connect.say(String(e));
    }
  }

  setDiscord(on: boolean) {
    this.discord = on;
    invoke("discord_set_enabled", { enabled: on }).catch((e) => connect.say(String(e)));
  }

  /** Thumbs up on YouTube Music too, when signed in. */
  rate(id: string, liked: boolean | null) {
    if (this.info) invoke("yt_rate", { id, liked }).catch(() => {});
  }
}

export const account = new AccountStore();
