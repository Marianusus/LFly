// The right-click menu on songs.

import type { Track } from "./player.svelte";

class Menu {
  open = $state<{ x: number; y: number; track: Track; remove?: () => void } | null>(null);

  openTrack(e: MouseEvent, track: Track, remove?: () => void) {
    e.stopPropagation();
    this.open = { x: e.clientX, y: e.clientY, track, remove };
  }

  close() {
    this.open = null;
  }
}

export const menu = new Menu();
