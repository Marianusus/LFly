// Window layout choices that last between sessions: the right panel and the sidebar width.

import { invoke } from "@tauri-apps/api/core";

export type Panel = "queue" | "devices" | "lyrics" | null;

class Ui {
  panel = $state<Panel>(null);
  sidebarWide = $state(true);
  /** The window is narrow: the sidebar shows icons only, or hides below phone width. */
  width = $state(typeof window === "undefined" ? 1200 : window.innerWidth);
  compact = $derived(this.width <= 900);
  phone = $derived(this.width <= 640);

  async start() {
    window.addEventListener("resize", () => (this.width = window.innerWidth));
    try {
      const saved = await invoke<{ panel?: Panel; sidebarWide?: boolean } | null>("store_get", { key: "ui" });
      if (saved) {
        this.panel = saved.panel ?? null;
        this.sidebarWide = saved.sidebarWide ?? true;
      }
    } catch {
      // First run: the defaults.
    }
  }

  private save() {
    invoke("store_set", { key: "ui", value: { panel: this.panel, sidebarWide: this.sidebarWide } }).catch(() => {});
  }

  togglePanel(panel: Exclude<Panel, null>) {
    this.panel = this.panel === panel ? null : panel;
    this.save();
  }

  toggleSidebar() {
    this.sidebarWide = !this.sidebarWide;
    this.save();
  }
}

export const ui = new Ui();
