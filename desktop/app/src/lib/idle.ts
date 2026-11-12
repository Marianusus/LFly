// The window is in the background: not focused, minimised or hidden (a game in front, another app).
// Decoration then holds still (the flowing cover, the glass, the playing bars): only what tells
// something keeps moving (the time, the lyrics).
//
// `document.hidden` alone is not enough: a window behind a fullscreen game, or on a second screen,
// usually still reports itself visible, and kept animating at the monitor's refresh rate.

import { getCurrentWindow } from "@tauri-apps/api/window";

type Listener = (idle: boolean) => void;

// Focused until told otherwise: at boot (and in headless test pages) hasFocus() can be false for
// a window that is in front, and no focus event would follow to wake it.
let focused = true;
let idle = false;
const listeners = new Set<Listener>();

function update() {
  const next = document.hidden || !focused;
  if (next === idle) return;
  idle = next;
  if (idle) document.documentElement.dataset.lflyIdle = "";
  else delete document.documentElement.dataset.lflyIdle;
  for (const fn of listeners) fn(idle);
}

export function isIdle(): boolean {
  return idle;
}

export function onIdle(fn: Listener): () => void {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

export function startIdle() {
  document.addEventListener("visibilitychange", update);
  addEventListener("focus", () => ((focused = true), update()));
  addEventListener("blur", () => ((focused = false), update()));
  // The webview's own focus events can miss the native window's (WebView2): ask Tauri too.
  try {
    getCurrentWindow()
      .onFocusChanged(({ payload }) => {
        focused = payload;
        update();
      })
      .catch(() => {});
  } catch {
    // Not inside Tauri (the mock page): the webview's events are all there is.
  }
  update();
}
