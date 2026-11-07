// Sync with the phone through Google Drive: the core does the work (drive.rs), this mirrors
// its state for the settings.

import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { connect } from "./connect.svelte";

export type DriveStatus = { configured: boolean; signedIn: boolean; enabled: boolean; email?: string | null; lastSync?: number | null };

export type LocalSyncStatus = { enabled: boolean; lastSync?: number | null };

class DriveStore {
  /** The sync over LFly Connect, with the phones of the account on this network. */
  local = $state<LocalSyncStatus>({ enabled: true });
  localBusy = $state(false);

  status = $state<DriveStatus>({ configured: false, signedIn: false, enabled: false });
  busy = $state(false);
  error = $state("");

  async start() {
    listen<LocalSyncStatus>("libsync:status", (e) => (this.local = e.payload)).catch(() => {});
    invoke<LocalSyncStatus>("libsync_status").then((s) => (this.local = s)).catch(() => {});
    listen<DriveStatus>("drive:status", (e) => (this.status = e.payload)).catch(() => {});
    this.status = await invoke<DriveStatus>("drive_status").catch(() => this.status);
  }

  private async run(f: () => Promise<void>) {
    this.busy = true;
    this.error = "";
    try {
      await f();
    } catch (e) {
      this.error = String(e);
    } finally {
      this.busy = false;
    }
  }

  async setLocal(on: boolean) {
    this.localBusy = true;
    try {
      this.local = await invoke<LocalSyncStatus>("libsync_set_enabled", { enabled: on });
    } catch (e) {
      connect.say(String(e));
    } finally {
      this.localBusy = false;
    }
  }

  async syncLocalNow() {
    this.localBusy = true;
    try {
      const n = await invoke<number>("libsync_now");
      this.local = await invoke<LocalSyncStatus>("libsync_status");
      connect.say(n ? `Libreria allineata con ${n === 1 ? "un dispositivo" : `${n} dispositivi`}` : "Nessun dispositivo del tuo account in rete");
    } catch (e) {
      connect.say(String(e));
    } finally {
      this.localBusy = false;
    }
  }

  signIn() {
    return this.run(async () => {
      this.status = await invoke<DriveStatus>("drive_sign_in");
      connect.say("Sincronizzazione con Google Drive attiva");
    });
  }

  syncNow() {
    return this.run(async () => {
      const changed = await invoke<boolean>("drive_sync");
      this.status = await invoke<DriveStatus>("drive_status");
      connect.say(changed ? "Libreria aggiornata dal telefono" : "Libreria già allineata");
    });
  }

  setEnabled(on: boolean) {
    return this.run(async () => {
      this.status = await invoke<DriveStatus>("drive_set_enabled", { enabled: on });
    });
  }

  signOut() {
    return this.run(async () => {
      this.status = await invoke<DriveStatus>("drive_sign_out");
    });
  }
}

export const drive = new DriveStore();
